// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"
#import "Rpcs3Bridge+Xmb.h"

@implementation Rpcs3XmbIcon
{
	Rpcs3IOS_XmbIcon* _icon;
}

- (instancetype)initWithIcon:(Rpcs3IOS_XmbIcon*)icon width:(uint32_t)width height:(uint32_t)height
{
	if ((self = [super init]))
	{
		_icon = icon;
		_width = width;
		_height = height;
	}
	return self;
}

- (int64_t)readFrameInto:(NSMutableData*)rgba
{
	rgba.length = (NSUInteger)(_width * _height * 4);
	int64_t time_ms = -1;
	const Rpcs3IOS_Status status = Rpcs3IOS_XmbIconFrame(_icon, static_cast<uint8_t*>(rgba.mutableBytes), rgba.length, &time_ms);
	return status == RPCS3_IOS_STATUS_OK ? time_ms : -1;
}

// The ABI refuses the main thread, and the last release can happen there.
- (void)dealloc
{
	Rpcs3IOS_XmbIcon* icon = _icon;
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
		Rpcs3IOS_XmbIconClose(icon);
	});
}

@end

@implementation Rpcs3Bridge (Xmb)

+ (BOOL)xmbDecodeAudioAtPath:(NSString*)path toWAV:(NSString*)wav
{
	return Rpcs3IOS_XmbDecodeAudio(path.UTF8String, wav.UTF8String) == RPCS3_IOS_STATUS_OK;
}

+ (BOOL)xmbDecodeIconAudioAtPath:(NSString*)path toWAV:(NSString*)wav
{
	return Rpcs3IOS_XmbDecodeIconAudio(path.UTF8String, wav.UTF8String) == RPCS3_IOS_STATUS_OK;
}

+ (Rpcs3XmbDiscMedia)xmbCopyDiscMediaFromImage:(NSString*)image serial:(NSString*)serial toFolder:(NSString*)folder
{
	switch (Rpcs3IOS_XmbDiscMedia(image.UTF8String, serial.UTF8String, folder.UTF8String))
	{
	case RPCS3_IOS_STATUS_OK: return Rpcs3XmbDiscMediaCopied;
	case RPCS3_IOS_STATUS_WRONG_STATE: return Rpcs3XmbDiscMediaAbsent;
	default: return Rpcs3XmbDiscMediaNotNow;
	}
}

+ (nullable Rpcs3XmbIcon*)xmbOpenIconAtPath:(NSString*)path
{
	Rpcs3IOS_XmbIcon* icon = nullptr;
	uint32_t width = 0;
	uint32_t height = 0;
	if (Rpcs3IOS_XmbIconOpen(path.UTF8String, &icon, &width, &height) != RPCS3_IOS_STATUS_OK)
	{
		return nil;
	}
	return [[Rpcs3XmbIcon alloc] initWithIcon:icon width:width height:height];
}

@end
