// Copyright 2025 DolphiniOS Project
// SPDX-License-Identifier: GPL-2.0-or-later

// Adapted from DolphiniOS pull request #277: the private NSExtension launch is kept, with this port's own XPC contract.

#import <Foundation/Foundation.h>
#include <stdint.h>

NS_ASSUME_NONNULL_BEGIN

// Host-helper XPC: pairing bytes in, never a path, and property-list replies out; ranges are 1-8 packed (base, size) uint64 pairs.
@protocol Armsx3JITEnablerRunner
- (void)prepareForParentPID:(int32_t)pid
                     ranges:(NSData*)ranges
                    pairing:(NSData*)pairing
                  withReply:(void (^)(NSDictionary<NSString*, id>* result))reply;
// The first operation after main(): deletes only the helper's cached disk-image files.
- (void)resetDDIWithReply:(void (^)(NSDictionary<NSString*, id>* result))reply;
// Mounts the disk image with the pairing bytes, reporting stages through helperStage: as it works.
- (void)mountDDIWithPairing:(NSData*)pairing withReply:(void (^)(NSDictionary<NSString*, id>* result))reply;
@end

@protocol Armsx3JITEnablerHost
- (void)helperLog:(NSString*)line;
// One stage word, forward only: connecting, downloading, mounting, checking.
- (void)helperStage:(NSString*)word;
@end

// Reply keys; the launcher validates each and never turns a malformed reply into success.
extern NSString* const kArmsx3ResultOutcome;         // NSString: the jit_helper_outcome schema word
extern NSString* const kArmsx3ResultAttachMs;        // NSNumber (ms), or -1
extern NSString* const kArmsx3ResultPrepareMs;       // NSNumber (ms), or -1
extern NSString* const kArmsx3ResultDetachMs;        // NSNumber (ms), or -1
extern NSString* const kArmsx3ResultPagesRequested;  // NSNumber, or -1
extern NSString* const kArmsx3ResultPagesWritten;    // NSNumber, or -1
// Disk-image reply keys: a closed result word and a [a-z0-9:/-] reason of at most 47 characters.
extern NSString* const kArmsx3DDIResult;             // NSString: deleted | failed for Reset; ready | unreachable | download | pairing | mount | other for Mount
extern NSString* const kArmsx3DDIReason;             // NSString: a path-free reason token

@interface StikJITExtensionLauncher : NSObject

- (instancetype)initWithHost:(id<Armsx3JITEnablerHost>)host;

- (void)launchWithCompletion:(void (^)(int32_t pid,
                                       id<Armsx3JITEnablerRunner> _Nullable runner,
                                       NSError* _Nullable error))completion;

// Called when the helper drops after connecting, so the wait wakes as interrupted instead of timing out; at once if it already dropped.
- (void)setRuntimeInterruptionHandler:(void (^)(void))handler;

// Host-observed ms from the request on a monotonic clock, or -1 when the stage was never reached.
@property(nonatomic, readonly) int64_t childPidKnownMs;    // request origin -> child pid known
@property(nonatomic, readonly) int64_t connectedMs;        // request origin -> XPC connection accepted
@property(nonatomic, readonly) int32_t childPID;           // the helper's pid, or -1

- (void)invalidate;

@end

typedef enum {
    ARMSX3_JIT_ENABLER_NOT_IMPLEMENTED = 0,  // never sent
    ARMSX3_JIT_ENABLER_OK              = 1,   // finished: attached, every page written, detached
    ARMSX3_JIT_ENABLER_LAUNCH_FAILED   = 2,   // no .appex, extension could not start, or the helper failed
    ARMSX3_JIT_ENABLER_TIMED_OUT       = 3,   // the monotonic deadline elapsed first
    ARMSX3_JIT_ENABLER_PAIRING_ABSENT  = 4,   // no readable pairing file; no helper was started
} armsx3_jit_enabler_outcome;

// The full result: numeric readings are -1 when unavailable, never 0; outcome decides the fallback, helper_outcome is the logged word.
typedef struct {
    int32_t outcome;              // an armsx3_jit_enabler_outcome
    int32_t child_pid;            // the helper's pid, or -1
    int64_t premain_ms;           // process start -> the enabler wait returned (host, monotonic); -1
    int64_t helper_start_ms;      // request origin -> helper pid known (host); -1
    int64_t helper_connect_ms;    // request origin -> XPC connection accepted (host); -1
    int64_t helper_attach_ms;     // helper: how long its attach took (from the reply); -1
    int64_t helper_prepare_ms;    // helper: its batched page-write loop (from the reply); -1
    int64_t helper_detach_ms;     // helper: how long its detach took (from the reply); -1
    int64_t helper_lifetime_ms;   // host: pid known -> exit observed; -1 when exit was not observed
    int64_t pages_requested;      // helper: page starts asked for; -1
    int64_t pages_written;        // helper: page starts debugserver answered OK; -1
    char    helper_outcome[32];   // the jit_helper_outcome word (NUL-terminated), or "" when none
} armsx3_jit_enabler_result;

// A disk-image operation's result: a closed word and a path-free reason token, both NUL-terminated.
typedef struct {
    char result[16];
    char reason[48];
} armsx3_jit_enabler_ddi_result;

typedef enum {
    ARMSX3_JIT_DDI_OP_MOUNT = 1,
    ARMSX3_JIT_DDI_OP_RESET = 2,
} armsx3_jit_ddi_op;

// extern "C" only for a C++ includer; the .m defines both with plain C linkage.
#ifdef __cplusplus
extern "C" {
#endif

// Found by the launch-time switch with dlsym and never linked, since the Mac host links the core without it; runs one pass.
int armsx3_jit_enabler_prepare(uint64_t base, uint64_t size, int32_t pid,
                               armsx3_jit_enabler_result* _Nullable result);

// The same pass over 1-8 (base, size) ranges in one attach.
int armsx3_jit_enabler_prepare_ranges(const uint64_t* _Nullable ranges, uint32_t count, int32_t pid,
                                      armsx3_jit_enabler_result* _Nullable result);

// Copies the last published result under a lock: 0 when there is one, -1 when there is none or result is NULL.
int armsx3_jit_enabler_last_result(armsx3_jit_enabler_result* _Nullable result);

// One disk-image operation, after main() and off the main queue: it blocks up to 30 s, or 900 s for Mount; 0 on a valid reply, else -1.
int armsx3_jit_enabler_ddi(int32_t op, void (^ _Nullable stage)(const char* word),
                           armsx3_jit_enabler_ddi_result* _Nullable out);

#ifdef __cplusplus
}
#endif

NS_ASSUME_NONNULL_END
