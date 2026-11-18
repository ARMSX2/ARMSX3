// SPDX-License-Identifier: GPL-2.0-only
// A game's own XMB media through the core: SND0 and ICON1 decoded, and a disc image's media copied out.

#import "Rpcs3Bridge.h"

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, Rpcs3XmbDiscMedia) {
	Rpcs3XmbDiscMediaCopied,
	// None in the image, or the image is another game's.
	Rpcs3XmbDiscMediaAbsent,
	// The core is not up yet, the image did not read, or a file did not write; ask again later.
	Rpcs3XmbDiscMediaNotNow,
};

// An ICON1.PAM open for playback; closed when released.
@interface Rpcs3XmbIcon : NSObject
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
- (instancetype)init NS_UNAVAILABLE;
// The next frame as RGBA into `rgba`, resized to width * height * 4; its time in milliseconds, or -1 at the end.
- (int64_t)readFrameInto:(NSMutableData*)rgba;
@end

// Every call blocks and must be made off the main thread.
@interface Rpcs3Bridge (Xmb)
+ (BOOL)xmbDecodeAudioAtPath:(NSString*)path toWAV:(NSString*)wav;
+ (BOOL)xmbDecodeIconAudioAtPath:(NSString*)path toWAV:(NSString*)wav;
+ (Rpcs3XmbDiscMedia)xmbCopyDiscMediaFromImage:(NSString*)image serial:(NSString*)serial toFolder:(NSString*)folder;
+ (nullable Rpcs3XmbIcon*)xmbOpenIconAtPath:(NSString*)path;
@end

NS_ASSUME_NONNULL_END
