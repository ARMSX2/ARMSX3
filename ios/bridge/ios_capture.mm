// SPDX-License-Identifier: GPL-2.0-only
// Microphone permission, the audio session's recording switch and the camera source; the microphone permission is only read.

// Included explicitly: an undefined macro in a `#if` is silently 0.
#include <TargetConditionals.h>

#if !defined(TARGET_OS_IPHONE) || !TARGET_OS_IPHONE
#error "ios_capture.mm is iOS-only capture glue and is added to armsx3_bridge only inside \
if(IOS) -- see ios/bridge/CMakeLists.txt. Reaching this line means the guard was widened, or \
<TargetConditionals.h> did not define the macro."
#endif

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>

#include "ios_capture.h"
#include "ios/core/ios_stderr_witness.h"
#include "ios_camera_handler.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <utility>

// Defined in ios/app/Support/ios_audio_session.mm; the bridge has no include root into ios/app/.
extern "C" int armsx3_audio_session_set_recording(int on);

namespace
{
	AVAudioApplicationRecordPermission record_permission()
	{
		return [[AVAudioApplication sharedInstance] recordPermission];
	}

	const char* permission_word()
	{
		switch (record_permission())
		{
		case AVAudioApplicationRecordPermissionGranted: return "granted";
		case AVAudioApplicationRecordPermissionDenied: return "denied";
		default: return "undetermined";
		}
	}

	std::atomic<bool> g_said_permission{false};
}

namespace armsx3::ios_capture
{
	bool microphone_denied()
	{
		return record_permission() == AVAudioApplicationRecordPermissionDenied;
	}

	void set_recording(bool on)
	{
		if (!g_said_permission.exchange(true))
		{
			utils::stderr_witness_printf("ARMSX3: microphone permission=%s\n", permission_word());
		}

		armsx3_audio_session_set_recording(on ? 1 : 0);
	}
}

// UIKit is not imported here, so its notification names are declared as UIKit declares them and resolve at link.

extern "C" NSNotificationName const UIApplicationDidEnterBackgroundNotification;
extern "C" NSNotificationName const UIApplicationWillEnterForegroundNotification;

using frame_callback = std::function<void(const std::uint8_t*, unsigned, unsigned, std::size_t)>;

@interface Armsx3CameraFrames : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate>
{
@public
	frame_callback on_frame;
}
@end

@implementation Armsx3CameraFrames
- (void)captureOutput:(AVCaptureOutput*)output didOutputSampleBuffer:(CMSampleBufferRef)sample fromConnection:(AVCaptureConnection*)connection
{
	CVImageBufferRef pixels = CMSampleBufferGetImageBuffer(sample);
	if (pixels == nullptr || CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
	{
		return;
	}
	on_frame(static_cast<const std::uint8_t*>(CVPixelBufferGetBaseAddress(pixels)),
		static_cast<unsigned>(CVPixelBufferGetWidth(pixels)), static_cast<unsigned>(CVPixelBufferGetHeight(pixels)),
		CVPixelBufferGetBytesPerRow(pixels));
	CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
}
@end

namespace
{
	// Every session object is touched only on this serial queue; this file is manual retain/release.
	dispatch_queue_t session_queue()
	{
		static dispatch_queue_t queue = dispatch_queue_create("armsx3.camera.session",
			dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0));
		return queue;
	}

	dispatch_queue_t frame_queue()
	{
		static dispatch_queue_t queue = dispatch_queue_create("armsx3.camera.frames", DISPATCH_QUEUE_SERIAL);
		return queue;
	}

	AVCaptureSession* g_session = nil;
	AVCaptureVideoDataOutput* g_output = nil;
	Armsx3CameraFrames* g_frames = nil;
	id g_to_background = nil;
	id g_to_foreground = nil;

	int start_camera(bool front, frame_callback on_frame)
	{
		@autoreleasepool
		{
			AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
			if (status == AVAuthorizationStatusNotDetermined)
			{
				dispatch_semaphore_t answered = dispatch_semaphore_create(0);
				[AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL) {
					dispatch_semaphore_signal(answered);
				}];
				dispatch_semaphore_wait(answered, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));
				dispatch_release(answered);
				status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
			}
			if (status != AVAuthorizationStatusAuthorized)
			{
				return 1;
			}

			AVCaptureDevice* device = [AVCaptureDevice defaultDeviceWithDeviceType:AVCaptureDeviceTypeBuiltInWideAngleCamera
				mediaType:AVMediaTypeVideo position:front ? AVCaptureDevicePositionFront : AVCaptureDevicePositionBack];
			AVCaptureDeviceInput* input = device ? [AVCaptureDeviceInput deviceInputWithDevice:device error:nil] : nil;
			if (input == nil)
			{
				return 2;
			}

			AVCaptureSession* session = [[AVCaptureSession alloc] init];
			AVCaptureVideoDataOutput* output = [[AVCaptureVideoDataOutput alloc] init];
			Armsx3CameraFrames* frames = [[Armsx3CameraFrames alloc] init];
			frames->on_frame = std::move(on_frame);
			output.videoSettings = @{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)};
			output.alwaysDiscardsLateVideoFrames = YES;
			[output setSampleBufferDelegate:frames queue:frame_queue()];

			[session beginConfiguration];
			const bool fits = [session canSetSessionPreset:AVCaptureSessionPreset640x480] && [session canAddInput:input]
				&& [session canAddOutput:output];
			if (fits)
			{
				session.sessionPreset = AVCaptureSessionPreset640x480;
				[session addInput:input];
				[session addOutput:output];
			}
			[session commitConfiguration];
			if (!fits)
			{
				[output setSampleBufferDelegate:nil queue:nullptr];
				[frames release];
				[output release];
				[session release];
				return 2;
			}

			// startRunning blocks, so it never runs on this thread or main.
			dispatch_async(session_queue(), ^{
				g_session = session;
				g_output = output;
				g_frames = frames;
				NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
				g_to_background = [[center addObserverForName:UIApplicationDidEnterBackgroundNotification object:nil
					queue:nil usingBlock:^(NSNotification*) {
						dispatch_async(session_queue(), ^{ [g_session stopRunning]; });
					}] retain];
				g_to_foreground = [[center addObserverForName:UIApplicationWillEnterForegroundNotification object:nil
					queue:nil usingBlock:^(NSNotification*) {
						dispatch_async(session_queue(), ^{ [g_session startRunning]; });
					}] retain];
				[g_session startRunning];
			});
			return 0;
		}
	}

	void stop_camera()
	{
		dispatch_sync(session_queue(), ^{
			if (g_session == nil)
			{
				return;
			}
			NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
			[center removeObserver:g_to_background];
			[center removeObserver:g_to_foreground];
			[g_to_background release];
			[g_to_foreground release];
			[g_session stopRunning];
			[g_output setSampleBufferDelegate:nil queue:nullptr];
			// A frame already on its way finishes before the handler can be told frames have stopped.
			dispatch_sync(frame_queue(), ^{});
			[g_frames release];
			[g_output release];
			[g_session release];
			g_session = nil;
			g_output = nil;
			g_frames = nil;
			g_to_background = nil;
			g_to_foreground = nil;
		});
	}
}

namespace armsx3::ios_capture
{
	bool camera_denied()
	{
		const AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
		return status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted;
	}

	armsx3::bridge::camera_source camera_source()
	{
		return {start_camera, stop_camera};
	}
}
