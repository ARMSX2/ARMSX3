// SPDX-License-Identifier: GPL-2.0-only
// The façade Swift drives the emulator through; it only marshals. Completions run on main; view calls are main only.

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

// The same names, order and values as the ABI's RPCS3_IOS_PAD_BUTTON_*, so a value passes straight through.
typedef NS_ENUM(NSInteger, Rpcs3PadButton) {
	Rpcs3PadButtonUp = 0,
	Rpcs3PadButtonDown = 1,
	Rpcs3PadButtonLeft = 2,
	Rpcs3PadButtonRight = 3,
	Rpcs3PadButtonCross = 4,
	Rpcs3PadButtonCircle = 5,
	Rpcs3PadButtonSquare = 6,
	Rpcs3PadButtonTriangle = 7,
	Rpcs3PadButtonL1 = 8,
	Rpcs3PadButtonR1 = 9,
	Rpcs3PadButtonL2 = 10,
	Rpcs3PadButtonR2 = 11,
	Rpcs3PadButtonStart = 12,
	Rpcs3PadButtonSelect = 13,
	Rpcs3PadButtonL3 = 14,
	Rpcs3PadButtonR3 = 15,
	// The core's pad_thread opens RPCS3's home menu on its rising edge; the façade adds no home-menu call.
	Rpcs3PadButtonPS = 16
};

// Volume 0-200 %, ResolutionScale 25-800 in steps of 25, FrameLimit 0 Auto, 1 Display rate, 30, 50 or 60, PerfOverlay 0/1.
typedef NS_ENUM(NSInteger, Rpcs3LiveSetting) {
	Rpcs3LiveSettingVolume = 300,
	Rpcs3LiveSettingResolutionScale = 301,
	Rpcs3LiveSettingFrameLimit = 302,
	Rpcs3LiveSettingPerfOverlay = 303
};

// One library title, as the ABI's library scan reported it. One type flows from the list to the boot.
@interface Rpcs3GameEntry : NSObject
@property (nonatomic, readonly, copy) NSString* serial;
@property (nonatomic, readonly, copy) NSString* title;
// PARAM.SFO's CATEGORY, for example HG or DG; empty when the scan reported none.
@property (nonatomic, readonly, copy) NSString* category;
// A title directory, a disc's PS3_GAME directory or an ISO file, ready for +bootGame:completion:.
@property (nonatomic, readonly, copy) NSString* bootPath;
// The title's own ICON0.PNG (for an ISO, the bridge's cached copy) and PIC1.PNG; nil when absent.
@property (nonatomic, readonly, copy, nullable) NSString* iconPath;
@property (nonatomic, readonly, copy, nullable) NSString* backdropPath;
@property (nonatomic, readonly) BOOL isISO;
// YES for a title from a registered folder (games.yml), NO for one under dev_hdd0/game.
@property (nonatomic, readonly) BOOL fromGamesYml;
- (instancetype)init NS_UNAVAILABLE;
@end

// Extents are -1 with no view attached and pixel extents 0 while not foreground-active; scale and rate 0.0 with no view.
@interface Rpcs3FrameMetrics : NSObject
@property (nonatomic, readonly) NSInteger pointsWidth;
@property (nonatomic, readonly) NSInteger pointsHeight;
@property (nonatomic, readonly) double scale;
@property (nonatomic, readonly) NSInteger pixelWidth;
@property (nonatomic, readonly) NSInteger pixelHeight;
// The screen's own maximum frame rate, as observed.
@property (nonatomic, readonly) double displayRate;
- (instancetype)init NS_UNAVAILABLE;
@end

// No pause or resume here: ios_frame.mm owns the lifecycle pause, and a second driver would race it.
@interface Rpcs3Bridge : NSObject

// The JIT verdict, callable before prepare. Its first call also writes the launch record (launch_record.h).
+ (void)isJITAvailableWithCompletion:(void (^)(BOOL available, NSString* reason))completion;

// The built-in helper's fine outcome word for this launch, or "" when it never ran. Any thread.
+ (NSString*)jitHelperOutcome;

// The pre-main pairing lookup's words for this launch under the keys used, format, result, rejected, documents and tried;
// empty when the lookup never ran; never a path or the file's contents.
+ (NSDictionary<NSString*, NSString*>*)jitPairingReport;

// Off the emulator's queue. Results: ready, unreachable, download, pairing, mount or other, with a path-free reason.
+ (void)mountDiskImageWithStage:(void (^)(NSString* stage))stage
	completion:(void (^)(NSString* result, NSString* reason))completion;

// Off the emulator's queue, check only, never downloads or mounts. Results: ready, unreachable, pairing, unmounted or other, with a path-free reason.
+ (void)testJitSetupWithStage:(void (^)(NSString* stage))stage
	completion:(void (^)(NSString* result, NSString* reason))completion;

// The helper deletes only its own cached image files. Result deleted or failed (other when it could not be asked).
+ (void)resetDiskImageWithCompletion:(void (^)(NSString* result, NSString* reason))completion;

// Synchronous syscalls. 1 present, 0 missing, -1 unknown; under LiveContainer the entitlements are its own.
+ (NSDictionary<NSString*, NSNumber*>*)deviceChecks;

// Once per process; later calls report the first results. Call only after a YES from isJITAvailable.
+ (void)prepareWithCompletion:(void (^)(NSInteger hostErrno, NSInteger initStatus))completion;

// Before +prepareWithCompletion:, so the header replay reaches only the file. 0 started, -2 no directory, -3 not one.
+ (void)startDiagnosticsInDirectory:(NSString*)directory verbose:(BOOL)verbose
	completion:(void (^)(NSInteger status))completion;

// Drains and flushes RPCS3.log before a suspension or a report. 0, also before a start.
+ (void)syncLogWithCompletion:(void (^)(NSInteger status))completion;

// YES raises the RSX channels to trace across boots; NO returns them to notice.
+ (void)setVerboseLogging:(BOOL)on completion:(void (^)(NSInteger status))completion;

// Sorted by title then serial. A title that is not valid UTF-8 is left out. Can take seconds.
+ (void)scanLibraryWithCompletion:(void (^)(NSArray<Rpcs3GameEntry*>* games, NSInteger status))completion;

// `path` must be a resolved POSIX path this process can read now; nothing is copied. Needs a stopped emulator.
+ (void)registerGameFolderAtPath:(NSString*)path completion:(void (^)(NSInteger status, NSUInteger added))completion;

// Needs STOPPED. -11 not firmware; -12 the destination failed, so installing again from the same PUP recovers.
+ (void)installFirmwareAtPath:(NSString*)path
	progress:(nullable void (^)(NSUInteger done, NSUInteger total))progress
	completion:(void (^)(NSInteger status))completion;

// In install order, at most 16. -14 not a package, -15 base game missing or wrong version, -16 extraction failed.
+ (void)installPackagesAtPaths:(NSArray<NSString*>*)paths
	progress:(nullable void (^)(NSUInteger fileIndex, NSUInteger permille))progress
	completion:(void (^)(NSInteger status, NSString* titleId))completion;

// With `serial` a RAP is named by that game's content id. -17 not a licence this game can use, nothing left behind.
+ (void)installLicenceAtPath:(NSString*)path forSerial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status))completion;

// `locked` 0 readable, 1 needs a licence, 2 unreadable even with one. Reads the whole EBOOT.
+ (void)probeLicenceForSerial:(NSString*)serial
	completion:(void (^)(NSInteger locked, NSString* contentId, NSInteger status))completion;

// "4.93", or "" when none is installed; stale while an install is incomplete.
+ (void)firmwareVersionWithCompletion:(void (^)(NSString* version, NSInteger status))completion;

// `dev_hdd0/home/<user>/`; "" on any status but 0. Creates nothing.
+ (void)userDirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion;

// Removes `dev_hdd0/game/<serial>` only. -3 not installed or holds a link, -7 not a serial, -9 running, -16 part-way.
+ (void)uninstallGameWithSerial:(NSString*)serial completion:(void (^)(NSInteger status))completion;

// PPU objects, SPU and shader caches of `serial`. -3 refused, -7 not a serial, -9 running, -16 part-way.
+ (void)clearGameCacheWithSerial:(NSString*)serial completion:(void (^)(NSInteger status))completion;

// "" on any status but 0. Creates nothing.
+ (void)hdd0DirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion;

// 0 written; -13 or -26 given up and still running; -27, -28 halted; -18 the kill happened and nothing was written.
+ (void)saveStateToSlot:(NSInteger)fileSlot resume:(BOOL)resume
	completion:(void (^)(NSInteger status, NSString* historyName))completion;

// Slots live in its `armsx3_slots/` subfolder; "" on any status but 0. Creates nothing.
+ (void)saveStateDirectoryForSerial:(NSString*)serial completion:(void (^)(NSString* path, NSInteger status))completion;

// Must lie inside the save-state tree. 0 the boot was accepted, and the caller waits for RUNNING; -6 refused.
+ (void)loadStateAtPath:(NSString*)statePath game:(Rpcs3GameEntry*)game completion:(void (^)(NSInteger status))completion;

// Nullable, because the C++ side returns nil off the main thread and Swift would otherwise import it as non-optional.
+ (nullable UIView*)makeGameView;

// A replacement view bumps the display epoch.
+ (void)attachGameView:(UIView*)view;

+ (void)detachGameView;

// Idempotent.
+ (void)installLifecycleObservers;

// Only asks ios_frame.mm, the one pause owner. `called` is NO when the pause machine owed nothing.
+ (void)setMenuPaused:(BOOL)paused completion:(void (^)(NSInteger status, BOOL called))completion;

// Session-only: a boot drops it. 0 applied, -7 an unknown setting or a value outside its set.
+ (void)setLiveSetting:(NSInteger)which value:(NSInteger)value completion:(void (^)(NSInteger status))completion;

// 0 on any status but 0; a frame limit outside the menu's five reads as -1.
+ (void)liveSetting:(NSInteger)which completion:(void (^)(NSInteger value, NSInteger status))completion;

// The settings tree as JSON, all games when `serial` is nil. -7 a bad serial, -4 before prepare.
+ (void)settingsForSerial:(nullable NSString*)serial completion:(void (^)(NSData* _Nullable json, NSInteger status))completion;

// `path` is node names joined by `@@`. -19 refused, -20 not written (previous kept), -21 forced by the iOS build.
+ (void)setSetting:(NSString*)path value:(NSString*)value serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status, BOOL live))completion;

// With `serial` an empty `paths` deletes the title's file; without, it is refused. -20 not written, -21 forced.
+ (void)resetSettings:(NSArray<NSString*>*)paths serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status, BOOL live))completion;

// One leaf of the running configuration, for a Debug check. "" on any status but 0.
+ (void)liveSettingAtPath:(NSString*)path completion:(void (^)(NSString* value, NSInteger status))completion;

// Writes nothing. -22 not a settings file this build can read.
+ (void)normaliseSettings:(NSString*)yaml
	completion:(void (^)(NSString* yaml, NSInteger kept, NSInteger unknown, NSInteger defaulted, NSInteger locked,
		NSInteger status))completion;

// The file wins; forced keys are never written. -20 not written (previous kept), -22 unreadable.
+ (void)applySettings:(NSString*)yaml serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger changed, BOOL live, NSInteger status))completion;

// All games is config.yml's changes from the defaults, minus forced keys and identity. -22 unreadable.
+ (void)exportSettingsForSerial:(nullable NSString*)serial
	completion:(void (^)(NSString* yaml, NSInteger status))completion;

// `{"perGame":0|1,"players":[...]}`; the global mapping when the title has none. -22 stored file unreadable.
+ (void)inputConfigForSerial:(nullable NSString*)serial completion:(void (^)(NSData* _Nullable json, NSInteger status))completion;

// -19 a binding over 64 bytes, a control byte or a refused number; -20 not written; -22 stored file unreadable.
+ (void)setInputBinding:(NSString*)key value:(NSString*)value player:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion;

// -20 not written, -22 stored file unreadable.
+ (void)resetInputPlayer:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion;

// The core's product ordinal (rpcs3_ios_abi.h lists them), at the next boot. NS_SWIFT_DISABLE_ASYNC: the card wraps it.
+ (void)setPlayer:(NSInteger)player device:(NSInteger)productType serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// From the title's input config when it has one, else the global one; 0 on any status but 0.
+ (void)deviceForPlayer:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status, NSInteger productType))completion NS_SWIFT_DISABLE_ASYNC;

// The patch schema version the core parses, for the rpcs3.net download; "" on any status but 0.
+ (void)patchEngineVersionWithCompletion:(void (^)(NSString* version, NSInteger status))completion;

// Only the app's own patch files. -7 another name, -24 the core refuses the body, -25 the write failed.
+ (void)writePatchFile:(NSString*)fileName yaml:(NSString*)yaml completion:(void (^)(NSInteger status))completion;

// Into imported_patch.yml. -24 the core refuses the body, -25 the merge could not be written.
+ (void)importPatches:(NSString*)yaml completion:(void (^)(NSInteger status))completion;

// The JSON array Rpcs3IOS_PatchesList documents; -7 a serial that is not one path component.
+ (void)patchesForSerial:(NSString*)serial completion:(void (^)(NSData* json, NSInteger status))completion;

// Applies at the next boot. -7 a patch no file holds or a bad serial.
+ (void)setPatchEnabled:(BOOL)enabled hash:(NSString*)hash patchDescription:(NSString*)patchDescription
	serial:(NSString*)serial appVersion:(NSString*)appVersion completion:(void (^)(NSInteger status))completion;

// The core's version line, as the problem report prints it; "" on any status but 0.
+ (void)versionWithCompletion:(void (^)(NSString* version, NSInteger status))completion;

// With a trailing `/`; "" on any status but 0.
+ (void)configDirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion;

// RGBA8 at full size, waiting at most 2 s. -9 not running, -23 no frame came.
+ (void)captureScreenshotWithCompletion:(void (^)(NSData* rgba, NSInteger width, NSInteger height, NSInteger status))completion;

// `state` 0 no swap, 1 a disc is in and can be swapped, 2 the tray is empty.
+ (void)discSwapStateWithCompletion:(void (^)(NSInteger status, NSInteger state))completion NS_SWIFT_DISABLE_ASYNC;

// The caller resumes the game first, since the eject finishes on its callback. Up to about 13 s on the bridge queue.
+ (void)changeDiscAtPath:(NSString*)path completion:(void (^)(NSInteger status, NSInteger result))completion
	NS_SWIFT_DISABLE_ASYNC;

// Oldest first, and the bridge forgets them. A path may not be readable yet: the core copies after naming.
+ (void)takeExportedPhotosWithCompletion:(void (^)(NSInteger status, NSArray<NSString*>* paths))completion
	NS_SWIFT_DISABLE_ASYNC;

// Only the core's frames and sound. -2 no path, -3 refused path, -9 already recording or no provider.
+ (void)startRecordingAtPath:(NSString*)path completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Completes once the file is finished. -9 this app was not recording.
+ (void)stopRecordingWithCompletion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// `path` must be the app's own copy, since the core writes the game's changes into it. Loads and removes never interleave.
+ (void)portalLoad:(NSInteger)portal slot:(NSInteger)slot path:(NSString*)path completion:(void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;
+ (void)portalRemove:(NSInteger)portal slot:(NSInteger)slot completion:(void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;
+ (void)portalList:(NSInteger)portal completion:(void (^)(NSInteger status, NSData* _Nullable json))completion
	NS_SWIFT_DISABLE_ASYNC;

// A figure is never overwritten: -3 when `path` exists. Nothing is loaded.
+ (void)portalCreate:(NSInteger)portal figure:(NSInteger)figureID variant:(NSInteger)variant path:(NSString*)path
	completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// The in-memory slot only, never rpcn.yml; empty strings empty it.
+ (void)rpcnSetSecret:(NSString*)secret token:(NSString*)token completion:(void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// The outcome is in `state`, `error` and `raw`. `secret` is the accepted form to keep; the façade zeroes its own copy.
+ (void)rpcnSignIn:(NSString*)npid password:(NSString*)password token:(NSString*)token
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw, NSString* _Nullable secret))completion
	NS_SWIFT_DISABLE_ASYNC;

// The username and server stay saved.
+ (void)rpcnSignOutWithCompletion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Never the secret or the token. A peek-only read on a global queue, so it never waits behind a sign-in.
+ (void)rpcnConfigWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;

// A peek-only read that never creates a client (fields in rpcs3_ios_abi.h's Rpcs3IOS_RpcnStatus).
+ (void)rpcnStatusWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;

// Nothing is saved. The outcome is in `state`, `error` (10 a taken username) and `raw`.
+ (void)rpcnCreateAccount:(NSString*)npid password:(NSString*)password email:(NSString*)email
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// As for +rpcnCreateAccount:.
+ (void)rpcnResendToken:(NSString*)npid password:(NSString*)password
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// As for +rpcnCreateAccount:; an empty email is refused before the network.
+ (void)rpcnSendResetToken:(NSString*)npid email:(NSString*)email
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// Nothing is saved; `error` 8 a wrong or expired code.
+ (void)rpcnResetPassword:(NSString*)npid code:(NSString*)code password:(NSString*)password
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// Pair every open with one close. `state` 0 signed in, 1 nothing saved, 5 unreachable, 9 password refused.
+ (void)rpcnFriendsSession:(BOOL)open
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// Every string is remote input: render it as plain text. A peek-only read that never creates a client.
+ (void)rpcnFriendsWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;

// `error` 14 not found, 15 blocked, 16 already a friend, 99 a refused remove; -9 no session is signed in.
+ (void)rpcnAddFriend:(NSString*)npid
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)rpcnRemoveFriend:(NSString*)npid
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion NS_SWIFT_DISABLE_ASYNC;

// Every string is remote input: render it as plain text. `[]` with no game or no session.
+ (void)rpcnInvitesWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;

// -9 no game, no session or (join) the game did not take it; -7 not an unused invite, which stays unused.
+ (void)rpcnAcceptInvite:(uint64_t)inviteId completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)rpcnDeclineInvite:(uint64_t)inviteId completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Saved at once. The official server always stays; adding never chooses one.
+ (void)rpcnAddHost:(NSString*)name host:(NSString*)host completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)rpcnDelHost:(NSString*)name host:(NSString*)host completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)rpcnResetHostsWithCompletion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)rpcnSelectHost:(NSString*)host completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Saved at once.
+ (void)rpcnSetIpv6:(BOOL)on completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// On the friends session only. It cannot be undone, so confirm first. -9 no signed-in session, nothing sent.
+ (void)rpcnDeleteTrophiesWithCompletion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
	NS_SWIFT_DISABLE_ASYNC;

// Android's order: firmware icons, PSN avatars, then the player's pictures. A large library takes seconds.
+ (void)avatarListWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)avatarCurrentWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)avatarSelect:(NSString*)path completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// A picture of the player's own must already be a cropped square PNG, under 16 MiB.
+ (void)avatarImage:(NSString*)path completion:(void (^)(NSInteger status, NSData* _Nullable png))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)avatarUseImage:(NSData*)png completion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;
+ (void)avatarUseDefaultWithCompletion:(void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Call before every boot. A refusal is not a boot failure.
+ (void)activateAudioSessionWithCompletion:(void (^)(NSInteger status))completion;

// Notifies others on deactivation, so interrupted music can resume. Never under a running title.
+ (void)deactivateAudioSessionWithCompletion:(void (^)(NSInteger status))completion;

// Audio was requested and possible, never that anyone heard it. Callable on main.
+ (BOOL)audioSessionActive;

// `handler` runs on main with the core's own path; a negative volume means the core gave none. nil stops the sounds.
+ (void)setSoundHandler:(nullable void (^)(NSString* path, float volume))handler;

+ (void)bootGame:(Rpcs3GameEntry*)game completion:(void (^)(NSInteger status))completion;

// 0 means STOPPED. -9 means still not stopped after about 10 s, and the caller must not boot.
+ (void)requestVMStopWithCompletion:(void (^)(NSInteger status))completion;

// The ABI's 100-107 state values.
+ (void)stateWithCompletion:(void (^)(NSInteger state, NSInteger status))completion;

// 0 and 0 before an RSX thread exists. A read-only diagnostic.
+ (void)frameCountersWithCompletion:(void (^)(uint64_t guestVblanks, uint64_t guestFlips, NSInteger status))completion;

// Plain reads of what the view publishes, with no emulator call.
+ (Rpcs3FrameMetrics*)frameMetrics;

// 0, -27, -28 (Stop, save and load refuse), -29 or -30 (offers only). Any thread, so a stuck bridge queue cannot hide it.
+ (NSInteger)haltStatus;

// Any thread, never blocks.
+ (uint64_t)stderrWitnessDropped;

// Points fd 2 at the app's drained pipe. Idempotent, any thread; NO if it did not install.
+ (BOOL)installStderrDrain;

// 0, 2 or 3; reading it clears it. At most once per game session, on the StikDebug route only.
+ (void)takeJitSecondPassOfferWithCompletion:(void (^)(NSInteger trigger))completion;

// `opened` is what iOS answered. -9 when no pass can start.
+ (void)startJitSecondPass:(NSInteger)trigger opened:(BOOL)opened completion:(void (^)(NSInteger status))completion;

// Bit i of `mask` is Rpcs3PadButton value i; sticks 0-255 with 128 centred; `l2`/`r2` pressure, 0 a plain press.
+ (void)setPadStateButtons:(uint32_t)mask leftX:(uint8_t)lx leftY:(uint8_t)ly rightX:(uint8_t)rx rightY:(uint8_t)ry
	l2:(uint8_t)l2 r2:(uint8_t)r2 completion:(nullable void (^)(NSInteger status))completion;

// NS_SWIFT_DISABLE_ASYNC: an imported `async` form would shadow the completion-less call in every async caller.
+ (void)setPlayer:(NSInteger)player padStateButtons:(uint32_t)mask leftX:(uint8_t)lx leftY:(uint8_t)ly rightX:(uint8_t)rx
	rightY:(uint8_t)ry l2:(uint8_t)l2 r2:(uint8_t)r2 completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// Port 0 is always plugged in for the touch pad. The bridge remembers the state across boots.
+ (void)setPlayer:(NSInteger)player connected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// Each 0-1023 in the core's units; a flat DS3 at rest is (512, 399, 512, 512).
+ (void)setPlayer:(NSInteger)player motionX:(uint16_t)x y:(uint16_t)y z:(uint16_t)z g:(uint16_t)g
	completion:(nullable void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// 0 to 1 per channel; pad-unavailable when the game holds none.
+ (void)moveSphereColor:(NSInteger)gem completion:(void (^)(float r, float g, float b, NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// Eight bytes, large then small for players 1-4. A player whose read is not OK reads 0, 0.
+ (void)readRumbleWithCompletion:(void (^)(NSInteger status, NSData* motors))completion NS_SWIFT_DISABLE_ASYNC;

// Only in Suspend Emulation Savestate Mode; otherwise -9, and the caller counts seconds. On the input queue.
+ (void)saveStateProgressWithCompletion:(void (^)(NSInteger status, uint64_t bytes, BOOL finalizing))completion
	NS_SWIFT_DISABLE_ASYNC;

// `hidUsage` is GCKeyCode.rawValue. Nothing logs a key.
+ (void)keyboardKey:(NSInteger)hidUsage pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// The game's keyboard is present only while one is attached; every held key is released when it goes.
+ (void)setKeyboardConnected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// Buttons 1-8, moves -127..128 with y down, wheel -128..127. Nothing logs a mouse value.
+ (void)mouseButton:(NSInteger)button pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;
+ (void)mouseMoveX:(NSInteger)x y:(NSInteger)y completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;
+ (void)mouseScrollX:(NSInteger)x y:(NSInteger)y completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// The game's mouse is present only while one is attached; every button is released when it goes.
+ (void)setMouseConnected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// Absolute, y down, inside maxX by maxY. Nothing logs a position.
+ (void)mouseAimX:(NSInteger)x y:(NSInteger)y maxX:(NSInteger)maxX maxY:(NSInteger)maxY
	completion:(nullable void (^)(NSInteger status))completion NS_SWIFT_DISABLE_ASYNC;

// Unlike +mouseButton:pressed:completion:, this reaches a gun whose game never initialised its own mouse.
+ (void)mouseAimButton:(NSInteger)button pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

// While held, a pressed digital button reads the pad's pressure intensity. Cleared by every accepted boot.
+ (void)setPressureModifier:(BOOL)held completion:(nullable void (^)(NSInteger status))completion
	NS_SWIFT_DISABLE_ASYNC;

@end

NS_ASSUME_NONNULL_END
