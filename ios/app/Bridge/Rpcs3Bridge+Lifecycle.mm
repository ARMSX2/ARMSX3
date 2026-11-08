// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

@implementation Rpcs3Bridge (Lifecycle)

// The game view: main thread only.

+ (UIView*)makeGameView
{
	void* raw = armsx3::ios_frame::make_view();

	if (raw == nullptr)
	{
		// make_view refuses off the main thread; nil lets the caller report it instead of failing at surface creation.
		return nil;
	}

	// The +1 from -alloc crosses back here, written for ARC and non-ARC alike.
#if __has_feature(objc_arc)
	return (__bridge_transfer UIView*)raw;
#else
	return [(__bridge UIView*)raw autorelease];
#endif
}

+ (void)attachGameView:(UIView*)view
{
	armsx3::ios_frame::attach_view((__bridge void*)view);
}

+ (void)detachGameView
{
	armsx3::ios_frame::detach_view();
}

+ (void)installLifecycleObservers
{
	armsx3::ios_frame::install_lifecycle_observers();
}

+ (void)setMenuPaused:(BOOL)paused completion:(void (^)(NSInteger status, BOOL called))completion
{
	// Blocks the façade queue until the pause queue has run the request, never the main thread.
	dispatch_async(rpcs3_bridge_queue(), ^{
		int called = 0;
		const int32_t status = armsx3::ios_frame::set_menu_pause(paused, &called);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, called != 0);
		});
	});
}

+ (void)bootGame:(Rpcs3GameEntry*)game completion:(void (^)(NSInteger status))completion
{
	NSString* path = [game.bootPath copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_Boot(path.UTF8String, s_product_config[0] != '\0' ? s_product_config : NULL);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)requestVMStopWithCompletion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		Rpcs3IOS_Status status = Rpcs3IOS_Stop();
		// OK only means teardown started on the core's join thread, so wait up to about 10 s for STOPPED, off main.
		BOOL stopped = NO;
		for (int poll = 0; status == RPCS3_IOS_STATUS_OK && !stopped && poll < 200; ++poll)
		{
			int32_t state = 0;
			stopped = Rpcs3IOS_GetState(&state) == RPCS3_IOS_STATUS_OK && state == RPCS3_IOS_STATE_STOPPED;
			if (!stopped)
			{
				usleep(50 * 1000);
			}
		}
		if (status == RPCS3_IOS_STATUS_OK && !stopped)
		{
			status = RPCS3_IOS_STATUS_WRONG_STATE;
		}
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)stateWithCompletion:(void (^)(NSInteger state, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t state = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_GetState(&state);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(state, status);
		});
	});
}

+ (void)frameCountersWithCompletion:(void (^)(uint64_t guestVblanks, uint64_t guestFlips, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		uint64_t vblanks = 0;
		uint64_t flips = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_GetFrameCounters(&vblanks, &flips);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(vblanks, flips, status);
		});
	});
}

// Main thread, no ABI call: the accessors are atomic loads of what the view published.
+ (Rpcs3FrameMetrics*)frameMetrics
{
	return [[Rpcs3FrameMetrics alloc] initWithPointsWidth:armsx3::ios_frame::view_points_width()
		pointsHeight:armsx3::ios_frame::view_points_height()
		scale:armsx3::ios_frame::content_scale()
		pixelWidth:armsx3::ios_frame::client_pixel_width()
		pixelHeight:armsx3::ios_frame::client_pixel_height()
		displayRate:armsx3::ios_frame::display_rate()];
}

// Any thread, no queue hop.
+ (NSInteger)haltStatus
{
	int32_t status = 0;
	return Rpcs3IOS_GetHaltStatus(&status) == RPCS3_IOS_STATUS_OK ? status : 0;
}

// Any thread, no queue hop, never blocks.
+ (uint64_t)stderrWitnessDropped
{
	return armsx3_stderr_witness_dropped();
}

+ (BOOL)installStderrDrain
{
	return armsx3_stderr_witness_install_drain() != 0;
}

+ (void)takeJitSecondPassOfferWithCompletion:(void (^)(NSInteger trigger))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t trigger = 0;
		if (Rpcs3IOS_TakeJitSecondPassOffer(&trigger) != RPCS3_IOS_STATUS_OK)
		{
			trigger = 0;
		}
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(trigger);
		});
	});
}

+ (void)startJitSecondPass:(NSInteger)trigger opened:(BOOL)opened completion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_StartJitSecondPass((int32_t)trigger, opened ? 1 : 0);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

@end
