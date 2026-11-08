// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

// The ABI's frame lives only for the sink call, so it is copied here.
typedef struct
{
	NSMutableData* rgba;
	NSInteger width;
	NSInteger height;
} screenshot_sink_state;

static void keep_screenshot(void* context, const uint8_t* rgba, uint32_t width, uint32_t height)
{
	screenshot_sink_state* state = (screenshot_sink_state*)context;
	[state->rgba appendBytes:rgba length:(NSUInteger)width * height * 4];
	state->width = width;
	state->height = height;
}

@implementation Rpcs3Bridge (Media)

+ (void)discSwapStateWithCompletion:(void (^)(NSInteger status, NSInteger state))completion
{
	void (^done)(NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t state = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_DiscSwapState(&state);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, state);
		});
	});
}

+ (void)changeDiscAtPath:(NSString*)path completion:(void (^)(NSInteger status, NSInteger result))completion
{
	NSString* disc = [path copy];
	void (^done)(NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t result = -1;
		const Rpcs3IOS_Status status = Rpcs3IOS_ChangeDisc(disc.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result);
		});
	});
}

// Each NUL-terminated path is decoded on its own, so one that is not UTF-8 drops only itself.
+ (void)takeExportedPhotosWithCompletion:(void (^)(NSInteger status, NSArray<NSString*>* paths))completion
{
	void (^done)(NSInteger, NSArray<NSString*>*) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_TakeExportedPhotos(append_settings_text, (__bridge void*)text);
		NSMutableArray<NSString*>* paths = [NSMutableArray array];
		const char* bytes = (const char*)text.bytes;
		NSUInteger start = 0;
		for (NSUInteger at = 0; status == RPCS3_IOS_STATUS_OK && at < text.length; at++)
		{
			if (bytes[at] != '\0')
				continue;
			NSString* path = [[NSString alloc] initWithBytes:bytes + start length:at - start encoding:NSUTF8StringEncoding];
			if (path.length > 0)
				[paths addObject:path];
			start = at + 1;
		}
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, paths);
		});
	});
}

// Lifecycle calls, so the bridge queue. The path is never logged.
+ (void)startRecordingAtPath:(NSString*)path completion:(void (^)(NSInteger status))completion
{
	NSString* file = [path copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_StartRecording(file.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)stopRecordingWithCompletion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_StopRecording();
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)captureScreenshotWithCompletion:(void (^)(NSData* rgba, NSInteger width, NSInteger height, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		screenshot_sink_state state = {[NSMutableData data], 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_CaptureScreenshot(keep_screenshot, &state);
		NSData* rgba = status == RPCS3_IOS_STATUS_OK ? [state.rgba copy] : [NSData data];
		const NSInteger width = status == RPCS3_IOS_STATUS_OK ? state.width : 0;
		const NSInteger height = status == RPCS3_IOS_STATUS_OK ? state.height : 0;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(rgba, width, height, status);
		});
	});
}

+ (void)activateAudioSessionWithCompletion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		// Activate, then start. The first non-zero wins.
		int status = armsx3_audio_session_configure();
		if (status == 0)
		{
			status = armsx3_audio_session_start();
		}
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)deactivateAudioSessionWithCompletion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		// Stop, then deactivate with NotifyOthersOnDeactivation.
		const int status = armsx3_audio_session_stop();
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (BOOL)audioSessionActive
{
	// The C surface takes its own lock and fills a copy, so this is safe on main.
	armsx3_audio_session_status_t s;
	armsx3_audio_session_status(&s);
	return s.state == ARMSX3_AUDIO_SESSION_ACTIVE ||
	       (s.state == ARMSX3_AUDIO_SESSION_CONFIGURED && !s.controller_present && s.code == 0 &&
	        strcmp(s.last_branch, ARMSX3_AUDIO_BRANCH_START) == 0);
}

@end
