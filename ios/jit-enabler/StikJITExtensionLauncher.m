// Copyright 2025 DolphiniOS Project
// SPDX-License-Identifier: GPL-2.0-or-later

// Adapted from DolphiniOS pull request #277: Dolphin's NSExtension launch, with this port's XPC contract and one serial coordinator.

#import "StikJITExtensionLauncher.h"
#import <objc/message.h>
#import <objc/runtime.h>
#import <os/lock.h>
#include <dlfcn.h>
#include <mach/mach_time.h>
#include <sys/resource.h>
#include <unistd.h>
#include "jit_launch_plan.h"
#include "pairing_shape.h"
#include "../core/ios_jit_split.h"

static NSString* const kArmsx3ListenerEndpointKey = @"Armsx3JITEnablerListenerEndpoint";

// Path components inside this app's Library/Application Support, resolved at run time.
static NSString* const kArmsx3PairingSubdir = @"StikJIT";
static NSString* const kArmsx3PairingName = @"pairingFile.plist";
static NSString* const kArmsx3PairingOriginName = @"pairingFile.origin";

NSString* const kArmsx3ResultOutcome = @"outcome";
NSString* const kArmsx3ResultAttachMs = @"attach_ms";
NSString* const kArmsx3ResultPrepareMs = @"prepare_ms";
NSString* const kArmsx3ResultDetachMs = @"detach_ms";
NSString* const kArmsx3ResultPagesRequested = @"pages_requested";
NSString* const kArmsx3ResultPagesWritten = @"pages_written";
NSString* const kArmsx3DDIResult = @"result";
NSString* const kArmsx3DDIReason = @"reason";

// ---- monotonic-time helpers ----

static int64_t Armsx3AbsDeltaMs(uint64_t start, uint64_t end) {
    if (start == 0 || end == 0 || end < start) return -1;
    static mach_timebase_info_data_t tb;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ mach_timebase_info(&tb); });
    if (tb.denom == 0) return -1;
    // Widen to 128 bits before multiplying by numer, which can overflow 64 bits on some hosts.
    unsigned __int128 ns = (unsigned __int128)(end - start) * (unsigned __int128)tb.numer / (unsigned __int128)tb.denom;
    return (int64_t)(ns / 1000000ULL);
}

// Pre-main time from this process's monotonic start; proc_pid_rusage comes from RTLD_DEFAULT since the SDK has no libproc.h. -1 on any failure.
static int64_t Armsx3PremainMs(uint64_t wait_returned_abs) {
    typedef int (*proc_pid_rusage_fn)(int pid, int flavor, rusage_info_t* buffer);
    proc_pid_rusage_fn fn = (proc_pid_rusage_fn)dlsym(RTLD_DEFAULT, "proc_pid_rusage");
    if (fn == NULL) return -1;
    struct rusage_info_v4 ri;
    memset(&ri, 0, sizeof ri);
    if (fn((int)getpid(), RUSAGE_INFO_V4, (rusage_info_t*)&ri) != 0) return -1;
    return Armsx3AbsDeltaMs(ri.ri_proc_start_abstime, wait_returned_abs);
}

// ---- host reads the pairing bytes (never a path to the helper) ----

// Library/Application Support, not Documents, which file sharing exposes and backups copy; a file at the old path is moved once.
static NSString* _Nullable Armsx3PairingFolder(NSSearchPathDirectory where) {
    NSArray<NSString*>* dirs = NSSearchPathForDirectoriesInDomains(where, NSUserDomainMask, YES);
    return (dirs.count == 0) ? nil : [dirs.firstObject stringByAppendingPathComponent:kArmsx3PairingSubdir];
}

static void Armsx3ExcludeFromBackup(NSString* path) {
    [[NSURL fileURLWithPath:path] setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
}

// One file the launch can send: its bytes, the source word and the shape word the pairing report uses.
@interface Armsx3PairingCandidate : NSObject
@property(nonatomic, strong) NSData* data;
@property(nonatomic, copy) NSString* source;
@property(nonatomic, copy) NSString* format;
// Set only for SideStore's files, whose fixed names are the one thing the log may say.
@property(nonatomic, copy, nullable) NSString* fileName;
@end

@implementation Armsx3PairingCandidate
@end

static NSString* Armsx3FormatWord(armsx3_pairing_shape shape) {
    switch (shape) {
        case ARMSX3_PAIRING_RP: return @"rp";
        case ARMSX3_PAIRING_RP_LOCKDOWN: return @"rp-lockdown";
        default: return @"";
    }
}

// One byte past the cap, so an oversize file reads as invalid instead of filling memory.
static NSData* _Nullable Armsx3ReadCapped(NSString* path) {
    NSFileHandle* handle = [NSFileHandle fileHandleForReadingAtPath:path];
    if (handle == nil) return nil;
    NSData* data = [handle readDataUpToLength:ARMSX3_PAIRING_MAX_BYTES + 1 error:nil];
    [handle closeAndReturnError:nil];
    return data;
}

// A file dropped in the file-sharing folder is moved out before main(), so Files and computer tools stop seeing it.
// Returns moved, lockdown or invalid, or nil when there was nothing to take or the move failed (the file then stays).
static NSString* _Nullable Armsx3TakeDocumentsDrop(NSString* folder, NSString* ownPath) {
    NSArray<NSString*>* docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
    if (docs.count == 0) return nil;
    NSString* dropPath = [docs.firstObject stringByAppendingPathComponent:kArmsx3PairingName];
    NSFileManager* files = [NSFileManager defaultManager];
    if (![files fileExistsAtPath:dropPath]) return nil;
    NSData* data = Armsx3ReadCapped(dropPath);
    const armsx3_pairing_shape shape = armsx3_pairing_shape_of(data.bytes, data.length);
    if (shape == ARMSX3_PAIRING_LOCKDOWN) return @"lockdown";
    if (shape == ARMSX3_PAIRING_INVALID) return @"invalid";

    [files createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:nil error:nil];
    NSString* incomingPath = [folder stringByAppendingPathComponent:@".incoming-pairingFile.plist"];
    NSURL* incoming = [NSURL fileURLWithPath:incomingPath];
    NSURL* own = [NSURL fileURLWithPath:ownPath];
    BOOL swapped = [data writeToURL:incoming options:0 error:nil];
    if (swapped) {
        swapped = [files fileExistsAtPath:ownPath]
            ? [files replaceItemAtURL:own withItemAtURL:incoming backupItemName:nil options:0 resultingItemURL:nil error:nil]
            : [files moveItemAtURL:incoming toURL:own error:nil];
    }
    if (!swapped) {
        [files removeItemAtPath:incomingPath error:nil];
        return nil;
    }
    Armsx3ExcludeFromBackup(folder);
    Armsx3ExcludeFromBackup(ownPath);
    [@"documents" writeToFile:[folder stringByAppendingPathComponent:kArmsx3PairingOriginName]
                   atomically:YES encoding:NSUTF8StringEncoding error:nil];
    [files removeItemAtPath:dropPath error:nil];
    NSLog(@"[JITEnablerHost] pairingFile.plist moved out of Documents");
    return @"moved";
}

// SideStore's file is read in place and never moved; only a Remote Pairing file is kept.
static NSArray<Armsx3PairingCandidate*>* Armsx3SideStoreCandidates(void) {
    const char* home = getenv("LC_HOME_PATH");
    if (home == NULL) return @[];
    NSString* folder = [[NSString stringWithUTF8String:home] stringByAppendingPathComponent:@"Documents/SideStore/Documents"];
    NSArray<NSString*>* names = @[@"PairingFile_RemoteRP.plist", @"ALTPairingFile.mobiledevicepairing"];
    NSArray<NSString*>* sources = @[@"sidestore-lc-rp", @"sidestore-lc-alt"];
    NSMutableArray<Armsx3PairingCandidate*>* found = [NSMutableArray array];
    for (NSUInteger i = 0; i < names.count; i++) {
        NSData* data = Armsx3ReadCapped([folder stringByAppendingPathComponent:names[i]]);
        const armsx3_pairing_shape shape = armsx3_pairing_shape_of(data.bytes, data.length);
        if (shape != ARMSX3_PAIRING_RP && shape != ARMSX3_PAIRING_RP_LOCKDOWN) continue;
        Armsx3PairingCandidate* candidate = [Armsx3PairingCandidate new];
        candidate.data = data;
        candidate.source = sources[i];
        candidate.format = Armsx3FormatWord(shape);
        candidate.fileName = names[i];
        [found addObject:candidate];
    }
    return found;
}

// The files to try, in order. Pre-main also handles a Documents drop, first, so a valid one replaces the old file; the
// word for what happened to it goes to documentsWord (moved, lockdown, invalid, or nil).
static NSArray<Armsx3PairingCandidate*>* Armsx3PairingLookup(BOOL premain, NSString* _Nullable * _Nullable documentsWord) {
    NSString* folder = Armsx3PairingFolder(NSApplicationSupportDirectory);
    if (documentsWord != NULL) *documentsWord = nil;
    if (folder == nil) return @[];
    NSString* path = [folder stringByAppendingPathComponent:kArmsx3PairingName];
    NSString* legacy = [Armsx3PairingFolder(NSDocumentDirectory) stringByAppendingPathComponent:kArmsx3PairingName];
    NSFileManager* files = [NSFileManager defaultManager];
    if (legacy != nil && ![files fileExistsAtPath:path] && [files fileExistsAtPath:legacy]) {
        [files createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:nil error:nil];
        [files moveItemAtPath:legacy toPath:path error:nil];
    }
    NSString* dropWord = nil;
    if (premain) dropWord = Armsx3TakeDocumentsDrop(folder, path);
    if (documentsWord != NULL) *documentsWord = dropWord;

    NSMutableArray<Armsx3PairingCandidate*>* candidates = [NSMutableArray array];
    if ([files fileExistsAtPath:path]) {
        Armsx3ExcludeFromBackup(folder);
        Armsx3ExcludeFromBackup(path);
    }
    NSData* data = [NSData dataWithContentsOfFile:path options:0 error:nil];
    if (data.length > 0) {
        Armsx3PairingCandidate* own = [Armsx3PairingCandidate new];
        own.data = data;
        own.source = @"own";
        own.format = Armsx3FormatWord(armsx3_pairing_shape_of(data.bytes, data.length));
        [candidates addObject:own];
    }
    [candidates addObjectsFromArray:Armsx3SideStoreCandidates()];
    return candidates;
}

// ---- the last-result snapshot ----

static os_unfair_lock g_last_result_lock = OS_UNFAIR_LOCK_INIT;
static armsx3_jit_enabler_result g_last_result;
static bool g_last_result_valid = false;

// The pairing report: words only, published once per launch by the pre-main pass.
static os_unfair_lock g_pairing_report_lock = OS_UNFAIR_LOCK_INIT;
static armsx3_jit_pairing_report g_pairing_report;
static bool g_pairing_report_valid = false;

static void Armsx3CopyWord(char* dest, size_t size, NSString* _Nullable word) {
    strlcpy(dest, word.UTF8String ?: "", size);
}

static void Armsx3PublishPairingReport(Armsx3PairingCandidate* _Nullable used, const char* helperWord,
                                       NSString* _Nullable rejected, NSString* _Nullable documents, int tried) {
    armsx3_jit_pairing_report report;
    memset(&report, 0, sizeof report);
    Armsx3CopyWord(report.used, sizeof report.used, used.source);
    Armsx3CopyWord(report.format, sizeof report.format, used.format);
    strlcpy(report.result, armsx3_pairing_result_word(helperWord), sizeof report.result);
    Armsx3CopyWord(report.rejected, sizeof report.rejected, rejected);
    Armsx3CopyWord(report.documents, sizeof report.documents, documents);
    report.tried = tried;
    os_unfair_lock_lock(&g_pairing_report_lock);
    g_pairing_report = report;
    g_pairing_report_valid = true;
    os_unfair_lock_unlock(&g_pairing_report_lock);
}

static void Armsx3InitResult(armsx3_jit_enabler_result* r) {
    r->outcome = ARMSX3_JIT_ENABLER_LAUNCH_FAILED;
    r->child_pid = -1;
    r->premain_ms = -1;
    r->helper_start_ms = -1;
    r->helper_connect_ms = -1;
    r->helper_attach_ms = -1;
    r->helper_prepare_ms = -1;
    r->helper_detach_ms = -1;
    r->helper_lifetime_ms = -1;
    r->pages_requested = -1;
    r->pages_written = -1;
    r->helper_outcome[0] = '\0';
}

static void Armsx3SetOutcomeWord(armsx3_jit_enabler_result* r, const char* word) {
    strlcpy(r->helper_outcome, word, sizeof r->helper_outcome);
}

// Validate the reply before trusting any of it; a malformed reply leaves the -1 readings and the outcome word untouched.
static bool Armsx3DecodeReply(NSDictionary* reply, armsx3_jit_enabler_result* r) {
    if (![reply isKindOfClass:[NSDictionary class]]) return false;

    id outcome = reply[kArmsx3ResultOutcome];
    if (![outcome isKindOfClass:[NSString class]]) return false;
    const char* word = [(NSString*)outcome UTF8String];
    if (word == NULL) return false;

    id attach = reply[kArmsx3ResultAttachMs];
    id prepare = reply[kArmsx3ResultPrepareMs];
    id detach = reply[kArmsx3ResultDetachMs];
    id requested = reply[kArmsx3ResultPagesRequested];
    id written = reply[kArmsx3ResultPagesWritten];
    if (![attach isKindOfClass:[NSNumber class]] || ![prepare isKindOfClass:[NSNumber class]] ||
        ![detach isKindOfClass:[NSNumber class]] || ![requested isKindOfClass:[NSNumber class]] ||
        ![written isKindOfClass:[NSNumber class]]) {
        return false;
    }

    long long pr = [(NSNumber*)requested longLongValue];
    long long pw = [(NSNumber*)written longLongValue];
    // -1 is the only allowed negative, and more pages written than requested is rejected.
    if (pr < -1 || pw < -1) return false;
    if (pr >= 0 && pw > pr) return false;

    Armsx3SetOutcomeWord(r, word);
    r->helper_attach_ms = [(NSNumber*)attach longLongValue];
    r->helper_prepare_ms = [(NSNumber*)prepare longLongValue];
    r->helper_detach_ms = [(NSNumber*)detach longLongValue];
    r->pages_requested = pr;
    r->pages_written = pw;
    return true;
}

// First terminal result wins: the reply, the timeout, an XPC error or an interruption each submit once, and later ones change nothing.
@interface Armsx3ResultArbiter : NSObject
- (instancetype)initWithSemaphore:(dispatch_semaphore_t)sem;
- (BOOL)tryFinish:(const armsx3_jit_enabler_result*)candidate;
- (armsx3_jit_enabler_result)finalizeHostFields:(void (^_Nullable)(armsx3_jit_enabler_result* winner))block;
@end

@implementation Armsx3ResultArbiter {
    os_unfair_lock _lock;
    armsx3_jit_enabler_result _result;
    BOOL _finished;
    dispatch_semaphore_t _sem;
}

- (instancetype)initWithSemaphore:(dispatch_semaphore_t)sem {
    if ((self = [super init])) {
        _lock = OS_UNFAIR_LOCK_INIT;
        _sem = sem;
        _finished = NO;
        Armsx3InitResult(&_result);
    }
    return self;
}

- (BOOL)tryFinish:(const armsx3_jit_enabler_result*)candidate {
    BOOL won = NO;
    os_unfair_lock_lock(&_lock);
    if (!_finished) {
        _finished = YES;
        _result = *candidate;
        won = YES;
    }
    os_unfair_lock_unlock(&_lock);
    // Only the winner signals, outside the lock, so the semaphore fires exactly once.
    if (won) dispatch_semaphore_signal(_sem);
    return won;
}

- (armsx3_jit_enabler_result)finalizeHostFields:(void (^)(armsx3_jit_enabler_result* winner))block {
    armsx3_jit_enabler_result snapshot;
    os_unfair_lock_lock(&_lock);
    // A winner has finished, so late callbacks lose; host fields and the snapshot are taken under the one lock.
    if (block) block(&_result);
    snapshot = _result;
    os_unfair_lock_unlock(&_lock);
    return snapshot;
}

@end

// A terminal winner seals the coordinator, so a late connection can neither finish bootstrap nor start a prepare.
typedef NS_ENUM(uint8_t, Armsx3BootstrapPhase) {
    Armsx3BootstrapPending = 0,
    Armsx3BootstrapSucceeded,
    Armsx3BootstrapFailed,
};

typedef NS_ENUM(uint8_t, Armsx3InterruptDisposition) {
    Armsx3InterruptWonBootstrapFailure = 0,  // won Pending->Failed: complete as a start or connect failure
    Armsx3InterruptLatchedRuntime,           // bootstrap already succeeded: latch and deliver the runtime handler
    Armsx3InterruptIgnored,                  // sealed, or bootstrap already failed: nothing to do
};

// A coherent host-timing snapshot copied out on the coordinator queue; the names differ from the ivars on purpose.
typedef struct {
    int32_t  child_pid;
    uint64_t origin_abs;
    uint64_t child_pid_known_abs;
    uint64_t connected_abs;
    bool     sealed;
} Armsx3HostTiming;

@interface Armsx3LauncherHost : NSObject <Armsx3JITEnablerHost>
@end

@implementation Armsx3LauncherHost
- (void)helperLog:(NSString*)line {
    NSLog(@"[JITEnablerHost] %@", line);
}
// The pre-main pass has no stages, so a stage word here is dropped.
- (void)helperStage:(NSString*)word {
    (void)word;
}
@end

// Forwards only the four stage words the UI knows to the caller's block, on the XPC queue they arrive on.
@interface Armsx3DDIHost : Armsx3LauncherHost
- (instancetype)initWithStage:(void (^ _Nullable)(const char* word))stage;
@end

@implementation Armsx3DDIHost {
    void (^_stage)(const char* word);
}
- (instancetype)initWithStage:(void (^)(const char* word))stage {
    if ((self = [super init])) _stage = [stage copy];
    return self;
}
- (void)helperStage:(NSString*)word {
    if (_stage == nil || ![word isKindOfClass:[NSString class]]) return;
    if (![@[@"connecting", @"downloading", @"mounting", @"checking"] containsObject:word]) return;
    _stage(word.UTF8String);
}
@end

@interface StikJITListenerDelegate : NSObject <NSXPCListenerDelegate>
// atomic, because teardown clears it on the coordinator queue while a listener callback may read it.
@property(atomic, copy, nullable) BOOL (^onConnection)(NSXPCConnection* c);
@end

@implementation StikJITListenerDelegate
- (BOOL)listener:(NSXPCListener*)listener shouldAcceptNewConnection:(NSXPCConnection*)c {
    // Read the handler once into a strong local; nil means teardown ran, so reject the connection.
    BOOL (^handler)(NSXPCConnection*) = self.onConnection;
    if (!handler) {
        [c invalidate];
        return NO;
    }
    return handler(c);
}
@end

// One serial queue orders every lifecycle, terminal and transport event; a re-entrant call runs inline.
@interface Armsx3LauncherCoordinator : NSObject
- (instancetype)initWithHost:(id<Armsx3JITEnablerHost>)host;
- (dispatch_semaphore_t)completionSemaphore;
- (void)launchWithCompletion:(void (^)(int32_t pid,
                                        id<Armsx3JITEnablerRunner> _Nullable runner,
                                        NSError* _Nullable error))completion;
- (BOOL)acceptConnection:(NSXPCConnection*)c;
- (void)dispatchInterruptionFrom:(NSString*)ctx;
- (void)installRuntimeHandler:(void (^)(void))handler;
- (BOOL)submitTerminal:(const armsx3_jit_enabler_result*)candidate;
- (int32_t)childPID;
- (Armsx3HostTiming)timingSnapshot;
- (armsx3_jit_enabler_result)finalizeWithHostStartMs:(int64_t)startMs
                                            connectMs:(int64_t)connectMs
                                            premainMs:(int64_t)premainMs
                                     snapshotChildPid:(int32_t)snapPid;
- (void)teardown;
@end

// The value is (__bridge void*)self, so the inline check knows exactly this coordinator's queue.
static const void* const kArmsx3CoordinatorQueueKey = &kArmsx3CoordinatorQueueKey;

@implementation Armsx3LauncherCoordinator {
    dispatch_queue_t _coordQueue;
    dispatch_semaphore_t _sem;
    Armsx3ResultArbiter* _arbiter;
    __weak id<Armsx3JITEnablerHost> _host;
    void (^_completion)(int32_t, id<Armsx3JITEnablerRunner> _Nullable, NSError* _Nullable);
    // lifecycle state
    Armsx3BootstrapPhase _bootstrapPhase;
    BOOL _sealed;
    BOOL _runtimeInterrupted;
    void (^_runtimeHandler)(void);
    int32_t _childPID;
    uint64_t _originAbs;
    uint64_t _childPidKnownAbs;
    uint64_t _connectedAbs;
    // transport references, owned here and touched only on this queue
    NSXPCListener* _listener;
    StikJITListenerDelegate* _delegate;
    NSXPCConnection* _connection;
    id _extension;   // NSExtension, resolved by name; no private header is imported
    NSUUID* _requestId;
}

- (instancetype)initWithHost:(id<Armsx3JITEnablerHost>)host {
    if ((self = [super init])) {
        _host = host;
        _bootstrapPhase = Armsx3BootstrapPending;
        _sealed = NO;
        _runtimeInterrupted = NO;
        _childPID = -1;
        _sem = dispatch_semaphore_create(0);
        _arbiter = [[Armsx3ResultArbiter alloc] initWithSemaphore:_sem];
        _coordQueue = dispatch_queue_create("com.armsx3.jitenabler.coordinator", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_set_specific(_coordQueue, kArmsx3CoordinatorQueueKey, (__bridge void*)self, NULL);
    }
    return self;
}

// Inline when already on the coordinator queue, else dispatch_sync onto it; never the main queue.
- (void)performOnCoordinator:(dispatch_block_t)block {
    if (dispatch_get_specific(kArmsx3CoordinatorQueueKey) == (__bridge void*)self) {
        block();
    } else {
        dispatch_sync(_coordQueue, block);
    }
}

- (dispatch_semaphore_t)completionSemaphore { return _sem; }

// ---- launch setup (transport is created and owned on the coordinator queue) ----

- (nullable NSString*)childBundleIdentifier {
    NSURL* plugins = [NSBundle mainBundle].builtInPlugInsURL;
    if (!plugins) return nil;
    NSArray<NSURL*>* items = [[NSFileManager defaultManager]
        contentsOfDirectoryAtURL:plugins
      includingPropertiesForKeys:nil
                         options:NSDirectoryEnumerationSkipsHiddenFiles
                           error:nil];
    for (NSURL* url in items) {
        if ([[url pathExtension] isEqualToString:@"appex"]) {
            NSBundle* b = [NSBundle bundleWithURL:url];
            if (b.bundleIdentifier) return b.bundleIdentifier;
        }
    }
    return nil;
}

- (nullable id)createExtension:(NSString*)identifier error:(NSError**)error {
    Class cls = NSClassFromString(@"NSExtension");
    SEL sel = NSSelectorFromString(@"extensionWithIdentifier:excludingDisabledExtensions:error:");
    if (!cls || !class_getClassMethod(cls, sel)) return nil;
    typedef id (*Fn)(id, SEL, NSString*, BOOL, NSError**);
    return ((Fn)objc_msgSend)(cls, sel, identifier, NO, error);
}

// A synchronous start or connect failure, delivered as completion(error) on the coordinator queue.
- (void)deliverStartFailure:(NSError*)err {
    [self performOnCoordinator:^{
        if (self->_sealed || self->_bootstrapPhase != Armsx3BootstrapPending) return;
        self->_bootstrapPhase = Armsx3BootstrapFailed;
        if (self->_completion) self->_completion(-1, nil, err);
    }];
}

- (void)launchWithCompletion:(void (^)(int32_t, id<Armsx3JITEnablerRunner> _Nullable, NSError* _Nullable))completion {
    __weak typeof(self) weakSelf = self;
    [self performOnCoordinator:^{
        typeof(self) s = weakSelf;
        if (!s) return;
        s->_originAbs = mach_absolute_time();
        s->_completion = [completion copy];

        s->_delegate = [StikJITListenerDelegate new];
        s->_listener = [NSXPCListener anonymousListener];
        // The listener callback forwards each connection to the coordinator and never stores it; weakSelf avoids a retain cycle.
        s->_delegate.onConnection = ^BOOL(NSXPCConnection* c) {
            Armsx3LauncherCoordinator* coordinator = weakSelf;
            if (!coordinator) { [c invalidate]; return NO; }
            return [coordinator acceptConnection:c];
        };
        s->_listener.delegate = s->_delegate;
        [s->_listener resume];

        NSString* identifier = [s childBundleIdentifier];
        if (!identifier) {
            NSLog(@"[StikJITLauncher] no .appex found in %@", [NSBundle mainBundle].builtInPlugInsURL);
            [s deliverStartFailure:[NSError errorWithDomain:@"StikJITLauncher" code:1
                                                   userInfo:@{NSLocalizedDescriptionKey: @"JIT enabler .appex not found"}]];
            return;
        }
        NSLog(@"[StikJITLauncher] launching runner extension %@", identifier);

        NSError* err = nil;
        s->_extension = [s createExtension:identifier error:&err];
        if (!s->_extension) {
            NSString* reason = err.localizedDescription
                ? [NSString stringWithFormat:@"NSExtension creation failed: %@", err.localizedDescription]
                : @"NSExtension creation failed";
            NSLog(@"[StikJITLauncher] %@ (underlying: %@)", reason, err);
            [s deliverStartFailure:(err ?: [NSError errorWithDomain:@"StikJITLauncher" code:2
                                                           userInfo:@{NSLocalizedDescriptionKey: reason}])];
            return;
        }

        SEL interruptSel = NSSelectorFromString(@"setRequestInterruptionBlock:");
        if ([s->_extension respondsToSelector:interruptSel]) {
            // The NSExtension interruption, before or after bootstrap, takes the one coordinator route.
            void (^onInterrupt)(NSUUID*) = ^(NSUUID* req) {
                Armsx3LauncherCoordinator* coordinator = weakSelf;
                if (!coordinator) return;
                [coordinator dispatchInterruptionFrom:[NSString stringWithFormat:@"request %@", req]];
            };
            ((void (*)(id, SEL, id))objc_msgSend)(s->_extension, interruptSel, onInterrupt);
        }

        NSExtensionItem* input = [NSExtensionItem new];
        input.userInfo = @{ kArmsx3ListenerEndpointKey: s->_listener.endpoint };

        NSError* reqErr = nil;
        SEL beginSel = NSSelectorFromString(@"beginExtensionRequestWithInputItems:error:");
        SEL beginFallbackSel = NSSelectorFromString(@"beginRequestWithInputItems:");
        if ([s->_extension respondsToSelector:beginSel]) {
            typedef id (*Fn)(id, SEL, NSArray*, NSError**);
            id req = ((Fn)objc_msgSend)(s->_extension, beginSel, @[input], &reqErr);
            s->_requestId = [req isKindOfClass:[NSUUID class]] ? req : nil;
        } else if ([s->_extension respondsToSelector:beginFallbackSel]) {
            typedef id (*Fn)(id, SEL, NSArray*);
            id req = ((Fn)objc_msgSend)(s->_extension, beginFallbackSel, @[input]);
            s->_requestId = [req isKindOfClass:[NSUUID class]] ? req : nil;
        }
        if (!s->_requestId) {
            NSString* reason = reqErr.localizedDescription
                ? [NSString stringWithFormat:@"begin extension request failed: %@", reqErr.localizedDescription]
                : @"begin extension request failed";
            NSLog(@"[StikJITLauncher] %@ (underlying: %@)", reason, reqErr);
            [s deliverStartFailure:[NSError errorWithDomain:@"StikJITLauncher" code:4
                                                   userInfo:@{NSLocalizedDescriptionKey: reason}]];
            return;
        }

        SEL pidSel = NSSelectorFromString(@"pidForRequestIdentifier:");
        if ([s->_extension respondsToSelector:pidSel]) {
            int32_t pid = ((pid_t (*)(id, SEL, id))objc_msgSend)(s->_extension, pidSel, s->_requestId);
            s->_childPID = pid;
            if (pid > 0 && s->_childPidKnownAbs == 0) s->_childPidKnownAbs = mach_absolute_time();
        }

        // No timeout here: the caller's wait is monotonic and off the main queue, which a static initialiser holds.
    }];
}

// One event: reject when sealed or taken, else own the connection and deliver the first prepare, so no timeout can come between.
- (BOOL)acceptConnection:(NSXPCConnection*)c {
    __weak typeof(self) weakSelf = self;
    // NO until the fully configured accept path sets it.
    __block BOOL accepted = NO;
    [self performOnCoordinator:^{
        typeof(self) s = weakSelf;
        if (!s) { [c invalidate]; return; }
        if (s->_sealed || s->_bootstrapPhase != Armsx3BootstrapPending || s->_connection != nil) {
            [c invalidate];
            return;
        }
        s->_connection = c;
        if (s->_connectedAbs == 0) s->_connectedAbs = mach_absolute_time();
        c.exportedInterface = [NSXPCInterface interfaceWithProtocol:@protocol(Armsx3JITEnablerHost)];
        c.exportedObject = s->_host;
        // The host lacks the extension's decoder swizzle, so the reply classes are pinned to NSDictionary, NSString and NSNumber.
        NSXPCInterface* runnerIface = [NSXPCInterface interfaceWithProtocol:@protocol(Armsx3JITEnablerRunner)];
        NSSet* replyClasses = [NSSet setWithObjects:[NSDictionary class], [NSString class], [NSNumber class], nil];
        [runnerIface setClasses:replyClasses
                    forSelector:@selector(prepareForParentPID:ranges:pairing:withReply:)
                  argumentIndex:0
                        ofReply:YES];
        // The Reset reply gets the same classes.
        [runnerIface setClasses:replyClasses
                    forSelector:@selector(resetDDIWithReply:)
                  argumentIndex:0
                        ofReply:YES];
        // And the Mount reply.
        [runnerIface setClasses:replyClasses
                    forSelector:@selector(mountDDIWithPairing:withReply:)
                  argumentIndex:0
                        ofReply:YES];
        c.remoteObjectInterface = runnerIface;
        [c resume];
        // A proxy error during the request takes the same coordinator route; weakSelf avoids a retain cycle.
        id<Armsx3JITEnablerRunner> runner = [c remoteObjectProxyWithErrorHandler:^(NSError* e) {
            Armsx3LauncherCoordinator* coordinator = weakSelf;
            if (!coordinator) return;
            NSLog(@"[StikJITLauncher] XPC proxy error during prepare: %@", e);
            [coordinator dispatchInterruptionFrom:@"xpc-proxy-error"];
        }];
        s->_bootstrapPhase = Armsx3BootstrapSucceeded;
        // Delivered here, so the runtime handler and the first prepare are ordered before any later timeout.
        if (s->_completion) s->_completion(s->_childPID, runner, nil);
        accepted = YES;  // configured, resumed, transitioned and delivered
    }];
    return accepted;
}

// Both interruption sources are classified and acted on in one serial event, before any later timeout.
- (void)dispatchInterruptionFrom:(NSString*)ctx {
    __weak typeof(self) weakSelf = self;
    [self performOnCoordinator:^{
        typeof(self) s = weakSelf;
        if (!s) return;
        Armsx3InterruptDisposition disp;
        void (^handler)(void) = nil;
        if (s->_sealed) {
            disp = Armsx3InterruptIgnored;
        } else if (s->_bootstrapPhase == Armsx3BootstrapPending) {
            s->_bootstrapPhase = Armsx3BootstrapFailed;
            disp = Armsx3InterruptWonBootstrapFailure;
        } else if (s->_bootstrapPhase == Armsx3BootstrapSucceeded) {
            s->_runtimeInterrupted = YES;
            handler = s->_runtimeHandler;
            disp = Armsx3InterruptLatchedRuntime;
        } else {
            disp = Armsx3InterruptIgnored;
        }
        switch (disp) {
            case Armsx3InterruptWonBootstrapFailure:
                NSLog(@"[StikJITLauncher] runner interrupted before bootstrap (%@)", ctx);
                if (s->_completion) {
                    s->_completion(-1, nil, [NSError errorWithDomain:@"StikJITLauncher" code:3
                                                            userInfo:@{NSLocalizedDescriptionKey: @"runner interrupted before bootstrap"}]);
                }
                break;
            case Armsx3InterruptLatchedRuntime:
                NSLog(@"[StikJITLauncher] runtime interruption after bootstrap (%@)", ctx);
                if (handler) handler();
                break;
            case Armsx3InterruptIgnored:
                break;
        }
    }];
}

// An interruption already latched fires inline, so a late install loses nothing.
- (void)installRuntimeHandler:(void (^)(void))handler {
    void (^copied)(void) = [handler copy];
    [self performOnCoordinator:^{
        self->_runtimeHandler = copied;
        if (self->_runtimeInterrupted && copied != nil) copied();
    }];
}

// The one -tryFinish: call site; a win seals the lifecycle in the same event, timeouts included.
- (BOOL)submitTerminal:(const armsx3_jit_enabler_result*)candidate {
    __block BOOL won = NO;
    [self performOnCoordinator:^{
        won = [self->_arbiter tryFinish:candidate];
        if (won) {
            self->_sealed = YES;
            if (self->_bootstrapPhase == Armsx3BootstrapPending) self->_bootstrapPhase = Armsx3BootstrapFailed;
        }
    }];
    return won;
}

- (int32_t)childPID {
    __block int32_t p = -1;
    [self performOnCoordinator:^{ p = self->_childPID; }];
    return p;
}

- (Armsx3HostTiming)timingSnapshot {
    __block Armsx3HostTiming t = (Armsx3HostTiming){ .child_pid = -1, .origin_abs = 0,
                                                     .child_pid_known_abs = 0, .connected_abs = 0, .sealed = false };
    [self performOnCoordinator:^{
        t.child_pid = self->_childPID;
        t.origin_abs = self->_originAbs;
        t.child_pid_known_abs = self->_childPidKnownAbs;
        t.connected_abs = self->_connectedAbs;
        t.sealed = self->_sealed ? true : false;
    }];
    return t;
}

// Applies host fields to the winning result under the arbiter lock, after the wait returned.
- (armsx3_jit_enabler_result)finalizeWithHostStartMs:(int64_t)startMs
                                            connectMs:(int64_t)connectMs
                                            premainMs:(int64_t)premainMs
                                     snapshotChildPid:(int32_t)snapPid {
    return [_arbiter finalizeHostFields:^(armsx3_jit_enabler_result* w) {
        if (w->child_pid < 0) w->child_pid = snapPid;
        w->helper_start_ms = startMs;
        w->helper_connect_ms = connectMs;
        w->premain_ms = premainMs;
    }];
}

// Detach every transport reference into locals and clear ownership before the re-entrant invalidation and kill.
- (void)teardown {
    [self performOnCoordinator:^{
        self->_sealed = YES;
        if (self->_bootstrapPhase == Armsx3BootstrapPending) self->_bootstrapPhase = Armsx3BootstrapFailed;
        NSXPCConnection* conn = self->_connection;
        self->_connection = nil;
        id ext = self->_extension;
        self->_extension = nil;
        NSUUID* req = self->_requestId;
        self->_requestId = nil;
        NSXPCListener* listener = self->_listener;
        self->_listener = nil;
        StikJITListenerDelegate* delegate = self->_delegate;
        self->_delegate = nil;
        self->_completion = nil;
        self->_runtimeHandler = nil;

        if (conn) { [conn invalidate]; }
        if (ext) {
            SEL killSel = NSSelectorFromString(@"_kill:");
            if (req && [ext respondsToSelector:killSel]) {
                ((void (*)(id, SEL, int))objc_msgSend)(ext, killSel, 9);
            }
        }
        if (listener) { listener.delegate = nil; [listener invalidate]; }
        delegate.onConnection = nil;
        (void)delegate;
    }];
}

@end

// A thin shell over the coordinator, which owns all launcher state.
@interface StikJITExtensionLauncher ()
- (BOOL)submitTerminal:(const armsx3_jit_enabler_result*)candidate;
- (Armsx3HostTiming)hostTimingSnapshot;
- (dispatch_semaphore_t)completionSemaphore;
- (armsx3_jit_enabler_result)finalizeWithHostStartMs:(int64_t)startMs
                                            connectMs:(int64_t)connectMs
                                            premainMs:(int64_t)premainMs
                                     snapshotChildPid:(int32_t)snapPid;
@end

@implementation StikJITExtensionLauncher {
    Armsx3LauncherCoordinator* _coordinator;  // the one synchronized owner of launcher state and transport
}

- (instancetype)initWithHost:(id<Armsx3JITEnablerHost>)host {
    if ((self = [super init])) {
        _coordinator = [[Armsx3LauncherCoordinator alloc] initWithHost:host];
    }
    return self;
}

- (void)launchWithCompletion:(void (^)(int32_t, id<Armsx3JITEnablerRunner> _Nullable, NSError* _Nullable))completion {
    [_coordinator launchWithCompletion:completion];
}

- (void)setRuntimeInterruptionHandler:(void (^)(void))handler {
    [_coordinator installRuntimeHandler:handler];
}

- (void)invalidate {
    [_coordinator teardown];
}

// Each read is one coherent snapshot from the coordinator.
- (int32_t)childPID { return [_coordinator childPID]; }
- (int64_t)childPidKnownMs {
    Armsx3HostTiming t = [_coordinator timingSnapshot];
    return Armsx3AbsDeltaMs(t.origin_abs, t.child_pid_known_abs);
}
- (int64_t)connectedMs {
    Armsx3HostTiming t = [_coordinator timingSnapshot];
    return Armsx3AbsDeltaMs(t.origin_abs, t.connected_abs);
}

// Terminal-path helpers for the C entry point (private to the .m).
- (BOOL)submitTerminal:(const armsx3_jit_enabler_result*)candidate { return [_coordinator submitTerminal:candidate]; }
- (Armsx3HostTiming)hostTimingSnapshot { return [_coordinator timingSnapshot]; }
- (dispatch_semaphore_t)completionSemaphore { return [_coordinator completionSemaphore]; }
- (armsx3_jit_enabler_result)finalizeWithHostStartMs:(int64_t)startMs
                                            connectMs:(int64_t)connectMs
                                            premainMs:(int64_t)premainMs
                                     snapshotChildPid:(int32_t)snapPid {
    return [_coordinator finalizeWithHostStartMs:startMs connectMs:connectMs premainMs:premainMs snapshotChildPid:snapPid];
}

@end

// used and default visibility, so it survives dead-strip and dlsym finds it; file scope already gives plain C linkage.
__attribute__((used, visibility("default")))
int armsx3_jit_enabler_prepare(uint64_t base, uint64_t size, int32_t pid,
                               armsx3_jit_enabler_result* result) {
    const uint64_t one[2] = {base, size};
    return armsx3_jit_enabler_prepare_ranges(one, 1, pid, result);
}

// One launch of the extension with one pairing file; the wait ends at `deadline`.
static armsx3_jit_enabler_result Armsx3PrepareAttempt(NSData* ranges, NSData* pairing, int32_t pid, dispatch_time_t deadline) {
    @autoreleasepool {
        Armsx3LauncherHost* host = [Armsx3LauncherHost new];
        StikJITExtensionLauncher* launcher = [[StikJITExtensionLauncher alloc] initWithHost:host];
        // The stored blocks capture the launcher weakly; this frame keeps it alive for the whole wait.
        __weak StikJITExtensionLauncher* weakLauncher = launcher;
        dispatch_semaphore_t done = [launcher completionSemaphore];

        [launcher launchWithCompletion:^(int32_t childPID, id<Armsx3JITEnablerRunner> runner, NSError* error) {
            StikJITExtensionLauncher* l = weakLauncher;
            if (!l) return;
            if (error || !runner) {
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                cand.child_pid = childPID;
                Armsx3SetOutcomeWord(&cand, "start-failed");
                cand.outcome = ARMSX3_JIT_ENABLER_LAUNCH_FAILED;
                [l submitTerminal:&cand];
                return;
            }
            // A runtime interruption finishes as interrupted through the same terminal op, waking the wait instead of timing out.
            [l setRuntimeInterruptionHandler:^{
                StikJITExtensionLauncher* l2 = weakLauncher;
                if (!l2) return;
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                cand.child_pid = childPID;
                Armsx3SetOutcomeWord(&cand, "interrupted");
                cand.outcome = ARMSX3_JIT_ENABLER_LAUNCH_FAILED;
                [l2 submitTerminal:&cand];
            }];
            [runner prepareForParentPID:pid ranges:ranges pairing:pairing withReply:^(NSDictionary* reply) {
                StikJITExtensionLauncher* l3 = weakLauncher;
                if (!l3) return;
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                cand.child_pid = childPID;
                if (Armsx3DecodeReply(reply, &cand)) {
                    // pairing-absent and pairing-rejected share the coarse pairing code, and the fine word picks the advice; anything else but finished is LAUNCH_FAILED.
                    if (strcmp(cand.helper_outcome, "finished") == 0) {
                        cand.outcome = ARMSX3_JIT_ENABLER_OK;
                    } else if (strcmp(cand.helper_outcome, "pairing-absent") == 0
                               || strcmp(cand.helper_outcome, "pairing-rejected") == 0) {
                        cand.outcome = ARMSX3_JIT_ENABLER_PAIRING_ABSENT;
                    } else {
                        cand.outcome = ARMSX3_JIT_ENABLER_LAUNCH_FAILED;
                    }
                } else {
                    // A reply that arrived but did not validate is never success.
                    Armsx3SetOutcomeWord(&cand, "interrupted");
                    cand.outcome = ARMSX3_JIT_ENABLER_LAUNCH_FAILED;
                }
                [l3 submitTerminal:&cand];
            }];
        }];

        BOOL timedOut = (dispatch_semaphore_wait(done, deadline) != 0);

        if (timedOut) {
            // The deadline passed: submit a timeout through the same op, which loses if a reply or interruption already won.
            armsx3_jit_enabler_result cand;
            Armsx3InitResult(&cand);
            Armsx3SetOutcomeWord(&cand, "timeout");
            cand.outcome = ARMSX3_JIT_ENABLER_TIMED_OUT;
            [launcher submitTerminal:&cand];
        }
        uint64_t wait_returned_abs = mach_absolute_time();

        // shortcut: helper_lifetime_ms stays -1 because nothing watches the child exit; add a kevent on the child if it is needed.
        Armsx3HostTiming ts = [launcher hostTimingSnapshot];
        int64_t startMs = Armsx3AbsDeltaMs(ts.origin_abs, ts.child_pid_known_abs);
        int64_t connectMs = Armsx3AbsDeltaMs(ts.origin_abs, ts.connected_abs);
        int64_t premainMs = Armsx3PremainMs(wait_returned_abs);
        armsx3_jit_enabler_result r = [launcher finalizeWithHostStartMs:startMs
                                                             connectMs:connectMs
                                                             premainMs:premainMs
                                                      snapshotChildPid:ts.child_pid];

        [launcher invalidate];
        return r;
    }
}

// A NULL or empty list goes to the helper as an empty request, which it refuses, so it never reads as success.
__attribute__((used, visibility("default")))
int armsx3_jit_enabler_prepare_ranges(const uint64_t* ranges_in, uint32_t count, int32_t pid,
                                      armsx3_jit_enabler_result* result) {
    @autoreleasepool {
        // A monotonic deadline, not a main-queue timer: the caller is a static initialiser holding the main thread.
        // One deadline covers every attempt, so a second file never stretches the wait.
        const dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW, 12LL * NSEC_PER_SEC);
        NSMutableData* ranges = [NSMutableData dataWithCapacity:(NSUInteger)count * 16];
        for (uint32_t i = 0; ranges_in != NULL && i < count && i < 64; i++) {
            const uint64_t pair[2] = {CFSwapInt64HostToLittle(ranges_in[2 * i]), CFSwapInt64HostToLittle(ranges_in[2 * i + 1])};
            [ranges appendBytes:pair length:sizeof pair];
        }
        // The app reads its own pairing file and sends the bytes; a missing file is pairing-absent, with no launch and no wait.
        NSString* documents = nil;
        NSArray<Armsx3PairingCandidate*>* candidates = Armsx3PairingLookup(YES, &documents);
        if (candidates.count == 0) {
            Armsx3PublishPairingReport(nil, "pairing-absent", nil, documents, 0);
            armsx3_jit_enabler_result r;
            Armsx3InitResult(&r);
            Armsx3SetOutcomeWord(&r, "pairing-absent");
            // A code of its own, so the switch can send the walkthrough to the pairing step.
            r.outcome = ARMSX3_JIT_ENABLER_PAIRING_ABSENT;
            os_unfair_lock_lock(&g_last_result_lock);
            g_last_result = r;
            g_last_result_valid = true;
            os_unfair_lock_unlock(&g_last_result_lock);
            if (result) *result = r;
            return r.outcome;
        }

        // The next file is tried only when the device refused this one and the budget is not spent.
        armsx3_jit_enabler_result r;
        Armsx3PairingCandidate* used = nil;
        NSString* rejected = nil;
        int tried = 0;
        for (NSUInteger i = 0; i < candidates.count; i++) {
            used = candidates[i];
            if (used.fileName != nil) NSLog(@"[JITEnablerHost] pairing from SideStore in LiveContainer: %@", used.fileName);
            r = Armsx3PrepareAttempt(ranges, used.data, pid, deadline);
            tried++;
            if (!armsx3_pairing_should_retry(r.helper_outcome) || i + 1 == candidates.count
                || dispatch_time(DISPATCH_TIME_NOW, 0) >= deadline) {
                break;
            }
            if (rejected == nil) rejected = used.source;
        }

        Armsx3PublishPairingReport(used, r.helper_outcome, rejected, documents, tried);
        os_unfair_lock_lock(&g_last_result_lock);
        g_last_result = r;
        g_last_result_valid = true;
        os_unfair_lock_unlock(&g_last_result_lock);

        if (result) *result = r;
        return r.outcome;
    }
}

__attribute__((used, visibility("default")))
int armsx3_jit_enabler_last_result(armsx3_jit_enabler_result* result) {
    if (result == NULL) return -1;
    os_unfair_lock_lock(&g_last_result_lock);
    bool valid = g_last_result_valid;
    if (valid) *result = g_last_result;
    os_unfair_lock_unlock(&g_last_result_lock);
    return valid ? 0 : -1;
}

__attribute__((used, visibility("default")))
int armsx3_jit_enabler_pairing_report(armsx3_jit_pairing_report* out) {
    if (out == NULL) return -1;
    os_unfair_lock_lock(&g_pairing_report_lock);
    bool valid = g_pairing_report_valid;
    if (valid) *out = g_pairing_report;
    os_unfair_lock_unlock(&g_pairing_report_lock);
    return valid ? 0 : -1;
}

// Read before main() by the dual map through dlsym: whether LiveContainer hosts this process, and the stored method; launches nothing.
__attribute__((used, visibility("default")))
int armsx3_jit_launch_plan(void) {
    const int livecontainer = getenv("LC_HOME_PATH") != NULL;
    char method[16] = {0};
    CFPropertyListRef value = CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_METHOD_KEY), kCFPreferencesCurrentApplication);
    if (value != NULL) {
        if (!(CFGetTypeID(value) == CFStringGetTypeID()
              && CFStringGetCString((CFStringRef)value, method, sizeof method, kCFStringEncodingUTF8))) {
            method[0] = '\0';
        }
        CFRelease(value);
    }
    return armsx3_jit_plan_compose(livecontainer, method);
}

// The learned split's store: one CFData of the struct's exact size in this app's UserDefaults, plus the stored script budget.
__attribute__((used, visibility("default")))
int armsx3_jit_pool_history_read(struct armsx3_jit_pool_history* out, long long* budget_ms) {
    *budget_ms = 0;
    CFPropertyListRef budget = CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_BUDGET_KEY), kCFPreferencesCurrentApplication);
    if (budget != NULL) {
        long long stored = 0;
        if (CFGetTypeID(budget) == CFNumberGetTypeID()
            && CFNumberGetValue((CFNumberRef)budget, kCFNumberLongLongType, &stored)) {
            *budget_ms = stored;
        }
        CFRelease(budget);
    }
    memset(out, 0, sizeof *out);
    int ok = 0;
    CFPropertyListRef value = CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_HISTORY_KEY), kCFPreferencesCurrentApplication);
    if (value != NULL) {
        if (CFGetTypeID(value) == CFDataGetTypeID()
            && CFDataGetLength((CFDataRef)value) == (CFIndex)sizeof *out) {
            CFDataGetBytes((CFDataRef)value, CFRangeMake(0, (CFIndex)sizeof *out), (UInt8*)out);
            ok = 1;
        }
        CFRelease(value);
    }
    return ok;
}

__attribute__((used, visibility("default")))
void armsx3_jit_pool_history_write(const struct armsx3_jit_pool_history* in) {
    CFDataRef data = CFDataCreate(kCFAllocatorDefault, (const UInt8*)in, (CFIndex)sizeof *in);
    if (data == NULL) {
        return;
    }
    CFPreferencesSetAppValue(CFSTR(ARMSX3_JIT_HISTORY_KEY), data, kCFPreferencesCurrentApplication);
    CFRelease(data);
    CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);
}

// Disk-image operations after main(), on the same coordinator; they never write the pre-main last result.

// The file this launch's pre-main pass got accepted, else the first one.
static NSData* _Nullable Armsx3MountPairing(NSArray<Armsx3PairingCandidate*>* candidates) {
    armsx3_jit_pairing_report report;
    if (armsx3_jit_enabler_pairing_report(&report) == 0 && strcmp(report.result, "accepted") == 0) {
        for (Armsx3PairingCandidate* candidate in candidates) {
            if (strcmp(candidate.source.UTF8String, report.used) == 0) return candidate.data;
        }
    }
    return candidates.firstObject.data;
}

static os_unfair_lock g_ddi_lock = OS_UNFAIR_LOCK_INIT;
static bool g_ddi_running = false;

static void Armsx3SetDDIWords(armsx3_jit_enabler_ddi_result* out, const char* result, const char* reason) {
    if (out == NULL) return;
    strlcpy(out->result, result, sizeof out->result);
    strlcpy(out->reason, reason, sizeof out->reason);
}

// A reason token is trusted only as 1-47 characters of [a-z0-9:/-], so no path can arrive as a reason.
static bool Armsx3IsDDIToken(id value) {
    if (![value isKindOfClass:[NSString class]]) return false;
    NSString* s = (NSString*)value;
    if (s.length == 0 || s.length > 47) return false;
    for (NSUInteger i = 0; i < s.length; i++) {
        unichar c = [s characterAtIndex:i];
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '/' || c == '-')) return false;
    }
    return true;
}

// The helper's closed result words per operation; none is a word the launcher writes itself.
static bool Armsx3IsDDIWord(int32_t op, const char* word) {
    static const char* const reset[] = {"deleted", "failed"};
    static const char* const mount[] = {"ready", "unreachable", "download", "pairing", "mount", "other"};
    const char* const* words = (op == ARMSX3_JIT_DDI_OP_MOUNT) ? mount : reset;
    const size_t n = (op == ARMSX3_JIT_DDI_OP_MOUNT) ? sizeof mount / sizeof *mount : sizeof reset / sizeof *reset;
    for (size_t i = 0; word != NULL && i < n; i++) {
        if (strcmp(word, words[i]) == 0) return true;
    }
    return false;
}

__attribute__((used, visibility("default")))
int armsx3_jit_enabler_ddi(int32_t op, void (^stage)(const char* word), armsx3_jit_enabler_ddi_result* out) {
    // The result starts as a failure; only a validated helper reply changes it.
    Armsx3SetDDIWords(out, "other", "none");
    // Reset has no stages; Mount's reach `stage` through Armsx3DDIHost.
    if (op != ARMSX3_JIT_DDI_OP_RESET && op != ARMSX3_JIT_DDI_OP_MOUNT) {
        Armsx3SetDDIWords(out, "other", "invalid-request");
        return -1;
    }
    // Mount sends the pairing bytes; a missing file starts no extension.
    NSData* pairing = nil;
    if (op == ARMSX3_JIT_DDI_OP_MOUNT) {
        pairing = Armsx3MountPairing(Armsx3PairingLookup(NO, NULL));
        if (pairing == nil) {
            Armsx3SetDDIWords(out, "pairing", "pairing-absent");
            return -1;
        }
    }
    bool busy;
    os_unfair_lock_lock(&g_ddi_lock);
    busy = g_ddi_running;
    if (!busy) g_ddi_running = true;
    os_unfair_lock_unlock(&g_ddi_lock);
    if (busy) {
        Armsx3SetDDIWords(out, "other", "busy");
        return -1;
    }

    int rc = -1;
    @autoreleasepool {
        Armsx3DDIHost* host = [[Armsx3DDIHost alloc] initWithStage:(op == ARMSX3_JIT_DDI_OP_MOUNT) ? stage : nil];
        StikJITExtensionLauncher* launcher = [[StikJITExtensionLauncher alloc] initWithHost:host];
        __weak StikJITExtensionLauncher* weakLauncher = launcher;
        dispatch_semaphore_t done = [launcher completionSemaphore];
        // Set by the reply block before it submits, and read only when that reply won.
        __block NSString* replyReason = nil;

        [launcher launchWithCompletion:^(int32_t childPID, id<Armsx3JITEnablerRunner> runner, NSError* error) {
            StikJITExtensionLauncher* l = weakLauncher;
            if (!l) return;
            if (error || !runner) {
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                cand.child_pid = childPID;
                Armsx3SetOutcomeWord(&cand, "start-failed");
                [l submitTerminal:&cand];
                return;
            }
            [l setRuntimeInterruptionHandler:^{
                StikJITExtensionLauncher* l2 = weakLauncher;
                if (!l2) return;
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                Armsx3SetOutcomeWord(&cand, "interrupted");
                [l2 submitTerminal:&cand];
            }];
            void (^onReply)(NSDictionary*) = ^(NSDictionary* reply) {
                StikJITExtensionLauncher* l3 = weakLauncher;
                if (!l3) return;
                armsx3_jit_enabler_result cand;
                Armsx3InitResult(&cand);
                const BOOL isDict = [reply isKindOfClass:[NSDictionary class]];
                id word = isDict ? reply[kArmsx3DDIResult] : nil;
                id reason = isDict ? reply[kArmsx3DDIReason] : nil;
                if ([word isKindOfClass:[NSString class]]
                    && Armsx3IsDDIWord(op, [(NSString*)word UTF8String])
                    && Armsx3IsDDIToken(reason)) {
                    replyReason = [(NSString*)reason copy];
                    Armsx3SetOutcomeWord(&cand, [(NSString*)word UTF8String]);
                } else {
                    // A reply that arrived but did not validate is never success.
                    Armsx3SetOutcomeWord(&cand, "bad-reply");
                }
                [l3 submitTerminal:&cand];
            };
            if (op == ARMSX3_JIT_DDI_OP_MOUNT) {
                [runner mountDDIWithPairing:pairing withReply:onReply];
            } else {
                [runner resetDDIWithReply:onReply];
            }
        }];

        // Monotonic and off the main thread; Reset 30 s, Mount 900 s because it may download the image first.
        const int64_t waitSeconds = (op == ARMSX3_JIT_DDI_OP_MOUNT) ? 900LL : 30LL;
        dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW, waitSeconds * (int64_t)NSEC_PER_SEC);
        if (dispatch_semaphore_wait(done, deadline) != 0) {
            armsx3_jit_enabler_result cand;
            Armsx3InitResult(&cand);
            Armsx3SetOutcomeWord(&cand, "timeout");
            [launcher submitTerminal:&cand];
        }
        armsx3_jit_enabler_result r = [launcher finalizeWithHostStartMs:-1 connectMs:-1 premainMs:-1 snapshotChildPid:-1];
        [launcher invalidate];

        if (Armsx3IsDDIWord(op, r.helper_outcome)) {
            Armsx3SetDDIWords(out, r.helper_outcome, replyReason.length > 0 ? replyReason.UTF8String : "none");
            rc = 0;
        } else {
            Armsx3SetDDIWords(out, "other", r.helper_outcome[0] != '\0' ? r.helper_outcome : "none");
        }
    }

    os_unfair_lock_lock(&g_ddi_lock);
    g_ddi_running = false;
    os_unfair_lock_unlock(&g_ddi_lock);
    return rc;
}
