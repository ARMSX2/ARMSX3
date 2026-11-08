// SPDX-License-Identifier: GPL-2.0-only
// The only bridge file that sees UIKit: importing it next to the VK headers does not compile, so ios_frame_impl.cpp holds the rest.

// Included explicitly: an undefined macro in a `#if` is silently 0, which selects the desktop arm on iOS.
#include <TargetConditionals.h>

#if !defined(TARGET_OS_IPHONE) || !TARGET_OS_IPHONE
#error "ios_frame.mm is the iOS half of the ios_frame.h firewall and is added to armsx3_bridge \
only inside if(IOS) -- see ios/bridge/CMakeLists.txt. Reaching this line means the guard was \
widened, or <TargetConditionals.h> did not define the macro."
#endif

#import <UIKit/UIKit.h>
#import <QuartzCore/QuartzCore.h>

#include "ios_frame.h"

#include "util/logs.hpp"

// None of these reaches a framework, an RSX header or a VK header; tests/test_ios_frame_firewall.py checks it.
#include "rpcs3_ios_abi.h"
#include "lifecycle_pause.h"
#include <mutex>

#include "present_gate.h"

LOG_CHANNEL(ios_frame_log, "IOSFRAME");

namespace armsx3::ios_frame
{
	namespace
	{
		// Written on the main thread, read from anywhere; each starts at its declined value, since at static init there is no view.
		atomic_t<s32> s_pixel_width{k_metric_declined};
		atomic_t<s32> s_pixel_height{k_metric_declined};
		atomic_t<s32> s_points_width{k_metric_declined};
		atomic_t<s32> s_points_height{k_metric_declined};

		atomic_t<bool> s_scene_active{true};
		atomic_t<u64> s_flips_at_attach{0};

		// A scale and a rate have no signed sentinel to spare, so 0.0 is theirs.
		atomic_t<f64> s_content_scale{0.0};
		atomic_t<f64> s_display_rate{0.0};

		// Bumped on replacement of the view object and on nothing else.
		atomic_t<u64> s_display_epoch{0};

		// Five separate counters, so a frequent event can never stand in for a rare one.
		atomic_t<u64> s_scene_connects{0};
		atomic_t<u64> s_scene_disconnects{0};
		atomic_t<u64> s_background_entries{0};
		atomic_t<u64> s_foreground_entries{0};
		atomic_t<u64> s_surface_loss_notes{0};

		// "No disconnect happened" and "nothing was watching" are opposite findings that a 0 counter cannot tell apart.
		atomic_t<bool> s_observers_installed{false};

		// Two pointers, so reattaching the same view does not bump the epoch while attaching a different one does.
		UIView* s_view = nil;
		const void* s_last_view = nullptr;

		bool on_main_thread()
		{
			return [NSThread isMainThread] == YES;
		}

		// Never held across an Rpcs3IOS_* call: a pause can block for seconds, and the main thread takes this lock briefly.
		std::mutex s_pause_mutex;
		pause_machine s_pause_machine; // guarded by s_pause_mutex
		dispatch_queue_t s_pause_queue = nullptr;

		// CLOCK_MONOTONIC keeps counting while the system sleeps, so a background span includes the suspended time.
		std::uint64_t monotonic_ms()
		{
			timespec ts{};
			clock_gettime(CLOCK_MONOTONIC, &ts);
			return static_cast<std::uint64_t>(ts.tv_sec) * 1000u
				+ static_cast<std::uint64_t>(ts.tv_nsec) / 1000000u;
		}

		// After a declined resume the machine keeps the pause held, so the next close or activation asks again.
		bool core_still_paused(Rpcs3IOS_Status resume_status)
		{
			int32_t state = 0;
			return resume_status != RPCS3_IOS_STATUS_OK
				&& Rpcs3IOS_GetState(&state) == RPCS3_IOS_STATUS_OK && state == RPCS3_IOS_STATE_PAUSED;
		}

		// A failed read counts as PAUSED, so a hold is never dropped on a guess.
		void drop_stale_hold()
		{
			int32_t state = 0;
			const bool paused = Rpcs3IOS_GetState(&state) != RPCS3_IOS_STATUS_OK
				|| state == RPCS3_IOS_STATE_PAUSED;
			{
				std::lock_guard lock(s_pause_mutex);
				s_pause_machine.on_core_state(paused);
			}
		}

		// Runs on s_pause_queue; only the machine runs under s_pause_mutex, which tests/test_lifecycle_pause_locking.py checks.
		void pause_on_deactivate(std::uint64_t requested_ms)
		{
			drop_stale_hold();
			bool call;
			{
				std::lock_guard lock(s_pause_mutex);
				call = s_pause_machine.on_deactivate();
			}
			if (!call)
			{
				return;
			}

			const Rpcs3IOS_Status status = Rpcs3IOS_Pause();

			const std::uint64_t landed = monotonic_ms();
			const bool capture_pending = capture_armed() && !g_capture_written.load()
				&& !g_capture_failed.load();

			{
				std::lock_guard lock(s_pause_mutex);
				s_pause_machine.on_pause_result(status, requested_ms, landed, gl_flip_entries(),
					capture_pending);
			}
		}

		// Runs on s_pause_queue, never on the main thread.
		void resume_on_activate()
		{
			bool owed;
			{
				std::lock_guard lock(s_pause_mutex);
				owed = s_pause_machine.on_activate();
			}
			if (!owed)
			{
				return;
			}

			const Rpcs3IOS_Status status = Rpcs3IOS_Resume();
			const bool still_paused = core_still_paused(status);

			{
				std::lock_guard lock(s_pause_mutex);
				s_pause_machine.on_resume_result(status, monotonic_ms(), gl_flip_entries(), still_paused);
			}
		}

		// A title compiles its PPU modules in STARTING, where Emu.Pause declines; each recheck is a dispatch_after, so it never blocks.
		void retry_deferred_pause(std::uint64_t requested_ms, std::uint64_t generation)
		{
			bool owed;
			{
				std::lock_guard lock(s_pause_mutex);
				owed = s_pause_machine.deferred_pause_owed(generation);
			}
			int32_t state = 0;
			if (!owed || Rpcs3IOS_GetState(&state) != RPCS3_IOS_STATUS_OK)
			{
				return;
			}

			if (state == RPCS3_IOS_STATE_RUNNING)
			{
				const Rpcs3IOS_Status status = Rpcs3IOS_Pause();
				if (status == RPCS3_IOS_STATUS_OK)
				{
					const std::uint64_t landed = monotonic_ms();
					const bool capture_pending = capture_armed() && !g_capture_written.load()
						&& !g_capture_failed.load();
					{
						std::lock_guard lock(s_pause_mutex);
						s_pause_machine.on_pause_result(status, requested_ms, landed, gl_flip_entries(),
							capture_pending);
					}
					return;
				}
			}
			else if (state != RPCS3_IOS_STATE_STARTING && state != RPCS3_IOS_STATE_LOADING
				&& state != RPCS3_IOS_STATE_READY)
			{
				return;
			}

			dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 100 * NSEC_PER_MSEC), s_pause_queue, ^{
				retry_deferred_pause(requested_ms, generation);
			});
		}

		void defer_declined_pause(std::uint64_t requested_ms)
		{
			std::uint64_t generation;
			{
				std::lock_guard lock(s_pause_mutex);
				generation = s_pause_machine.l.deactivations;
			}
			retry_deferred_pause(requested_ms, generation);
		}

		// A boot the core accepted while the scene is deactivated owes the new game a pause.
		void pause_on_boot_accepted(std::uint64_t requested_ms)
		{
			drop_stale_hold();
			defer_declined_pause(requested_ms);
		}

		// Returns the ABI's status, or 0 when nothing was owed.
		Rpcs3IOS_Status menu_pause_worker(bool open, int& called)
		{
			if (open)
			{
				drop_stale_hold(); // before on_menu_open trusts a hold
			}
			bool call;
			{
				std::lock_guard lock(s_pause_mutex);
				call = open ? s_pause_machine.on_menu_open() : s_pause_machine.on_menu_close();
			}
			called = call ? 1 : 0;
			if (!call)
			{
				return RPCS3_IOS_STATUS_OK;
			}

			const std::uint64_t requested = monotonic_ms();
			const Rpcs3IOS_Status status = open ? Rpcs3IOS_Pause() : Rpcs3IOS_Resume();
			const std::uint64_t landed = monotonic_ms();
			const bool still_paused = !open && core_still_paused(status);

			{
				std::lock_guard lock(s_pause_mutex);
				if (open)
				{
					s_pause_machine.on_pause_result(status, requested, landed, gl_flip_entries(), false);
				}
				else
				{
					s_pause_machine.on_resume_result(status, landed, gl_flip_entries(), still_paused);
				}
			}

			return status;
		}

		// The view leaving its window is the one place the surface-loss condition can be seen.
		void note_surface_loss()
		{
			++s_surface_loss_notes;
		}

		void publish_metrics(UIView* view)
		{
			if (view == nil)
			{
				s_pixel_width  = k_metric_declined;
				s_pixel_height = k_metric_declined;
				s_points_width  = k_metric_declined;
				s_points_height = k_metric_declined;
				s_content_scale = 0.0;
				s_display_rate  = 0.0;
				return;
			}

			const CGRect bounds = [view bounds];

			// Prefer the screen's scale, because that is the one the layer will be composited at.
			UIScreen* screen = [[view window] screen];
			CGFloat scale = (screen != nil) ? [screen nativeScale] : 0.0;

			if (scale <= 0.0)
			{
				scale = [view contentScaleFactor];
			}

			if (scale <= 0.0)
			{
				scale = 1.0;
			}

			// The view does not push scale into a layer whose contents it did not create, so it is set here.
			CAMetalLayer* layer = nil;

			if ([[view layer] isKindOfClass:[CAMetalLayer class]])
			{
				layer = (CAMetalLayer*)[view layer];
			}

			CGSize drawable = CGSizeZero;

			if (layer != nil)
			{
				[view setContentScaleFactor:scale];
				[layer setContentsScale:scale];
				[layer setDrawableSize:CGSizeMake(bounds.size.width * scale,
					bounds.size.height * scale)];
				// Opaque, as has_alpha() reports; claiming alpha on an opaque layer costs a format conversion for nothing.
				[layer setOpaque:YES];

				// Read back from the layer, since MoltenVK derives the surface extent from it and a hand multiplication can be a pixel off.
				drawable = [layer drawableSize];
			}
			else
			{
				// A layer that is not a CAMetalLayer cannot present and would fail later at surface creation with no hint of why.
				ios_frame_log.error("the attached view's layer is not a CAMetalLayer -- "
				                    "+layerClass is the whole trick and this view does not have "
				                    "it; surface creation will fail later and further away");
				drawable = CGSizeMake(bounds.size.width * scale, bounds.size.height * scale);
			}

			s_points_width  = static_cast<s32>(bounds.size.width);
			s_points_height = static_cast<s32>(bounds.size.height);
			s_content_scale = static_cast<f64>(scale);

			// A view mid-layout can be zero-sized; that is a real answer, while k_metric_declined means "no view".
			s_pixel_width  = static_cast<s32>(drawable.width);
			s_pixel_height = static_cast<s32>(drawable.height);

			s_display_rate = (screen != nil)
				? static_cast<f64>([screen maximumFramesPerSecond])
				: 0.0;
		}
	}
}

// +layerClass makes view.layer itself a CAMetalLayer, which metal_layer.mm hands to VkMetalSurfaceCreateInfoEXT.

@interface ArmsX3MetalView : UIView
@end

@implementation ArmsX3MetalView

+ (Class)layerClass
{
	return [CAMetalLayer class];
}

- (void)didMoveToWindow
{
	[super didMoveToWindow];

	// Not the epoch: the same view object will come back to the same window.
	if (self == armsx3::ios_frame::s_view && [self window] == nil)
	{
		armsx3::ios_frame::note_surface_loss();
	}

	// The scale and the screen are only meaningful once the view has a window.
	armsx3::ios_frame::publish_metrics(self);
}

- (void)layoutSubviews
{
	[super layoutSubviews];

	// New values for the same view object, so the epoch is not touched.
	armsx3::ios_frame::publish_metrics(self);
}

@end

namespace armsx3::ios_frame
{
	// Always asynchronous: the core can call this from the main thread, where a synchronous hop would deadlock.
	void enable_display_sleep(bool enabled)
	{
		dispatch_async(dispatch_get_main_queue(), ^{
			UIApplication.sharedApplication.idleTimerDisabled = !enabled;
		});
	}

	void* make_view()
	{
		if (!on_main_thread())
		{
			ios_frame_log.error("make_view called off the main thread -- refused. View creation "
			                    "is undefined off the main thread on this platform.");
			return nullptr;
		}

		// CGRectZero: the caller's layout pass sizes it and -layoutSubviews republishes the metrics.
		ArmsX3MetalView* view = [[ArmsX3MetalView alloc] initWithFrame:CGRectZero];

		// CMake passes no -fobjc-arc, so this is manual retain/release; under ARC the view would dangle without this branch.
#if __has_feature(objc_arc)
		return (__bridge_retained void*)view;
#else
		return (__bridge void*)view;
#endif
	}

	void attach_view(void* ui_view)
	{
		if (!on_main_thread())
		{
			// Every property read below is undefined off the main thread, and an undefined value reads like a measurement.
			ios_frame_log.error("attach_view called off the main thread -- refused. View property "
			                    "access is undefined off the main thread on this platform; the "
			                    "view has NOT been attached and no metric was published.");
			return;
		}

		UIView* view = (__bridge UIView*)ui_view;

		s_view = view;

		if (view != nil)
		{
			// A new view is a new renderer surface, so the gate's flipped-since-attach clock restarts.
			s_flips_at_attach = gl_flip_entries();

			const void* identity = (__bridge const void*)view;

			// The epoch moves on replacement and on nothing else, as in the Android frontend.
			if (identity != s_last_view)
			{
				s_last_view = identity;
				const u64 epoch = ++s_display_epoch;
				ios_frame_log.notice("view replaced -- display epoch is now %u", epoch);
			}
		}

		publish_metrics(view);
	}

	void detach_view()
	{
		if (!on_main_thread())
		{
			ios_frame_log.error("detach_view called off the main thread -- refused, for the "
			                    "reason attach_view states.");
			return;
		}

		s_view = nil;

		// s_last_view is kept, so the same view coming back does not bump the epoch.
		publish_metrics(nil);
	}

	void* current_view()
	{
		return (__bridge void*)s_view;
	}

	// 0 while the scene is inactive once the renderer has flipped since attach; -1 is passed through.
	int client_pixel_width()
	{
		return gated_extent(s_scene_active.load(), gl_flip_entries() > s_flips_at_attach.load(),
			static_cast<int>(s_pixel_width.load()));
	}

	int client_pixel_height()
	{
		return gated_extent(s_scene_active.load(), gl_flip_entries() > s_flips_at_attach.load(),
			static_cast<int>(s_pixel_height.load()));
	}

	// The same decision the two accessors above make, read by IosGraphicsFrame::flip's hold.
	bool present_gate_closed()
	{
		return gate_closed(s_scene_active.load(), gl_flip_entries() > s_flips_at_attach.load());
	}

	int view_points_width()   { return static_cast<int>(s_points_width.load()); }
	int view_points_height()  { return static_cast<int>(s_points_height.load()); }
	f64 content_scale()       { return s_content_scale.load(); }
	f64 display_rate()        { return s_display_rate.load(); }
	u64 display_epoch()       { return s_display_epoch.load(); }

	// Notifications rather than a UISceneDelegate, because the SwiftUI shell has none; nothing here blocks or waits.

	void install_lifecycle_observers()
	{
		if (!on_main_thread())
		{
			ios_frame_log.error("install_lifecycle_observers called off the main thread -- "
			                    "refused. Nothing is observing the scene lifecycle.");
			return;
		}

		// The exchange keeps this idempotent; registering twice would double every count.
		if (s_observers_installed.exchange(true))
		{
			return;
		}

		NSNotificationCenter* centre = [NSNotificationCenter defaultCenter];

		[centre addObserverForName:UISceneWillConnectNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				++s_scene_connects;
			}];

		// attach_view moves the epoch on object identity, so a disconnect with no reconnect moves nothing.
		[centre addObserverForName:UISceneDidDisconnectNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				++s_scene_disconnects;
			}];

		[centre addObserverForName:UISceneDidEnterBackgroundNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				// Closes the gate here too, in case WillDeactivate was missed.
				s_scene_active = false;
				++s_background_entries;
				{
					std::lock_guard lock(s_pause_mutex);
					s_pause_machine.on_background(monotonic_ms());
				}
				// Paused-game threads poll at 250 ms while this is true; one store, no lock.
				set_app_backgrounded(true);
			}];

		[centre addObserverForName:UISceneWillEnterForegroundNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				++s_foreground_entries;
				{
					std::lock_guard lock(s_pause_mutex);
					s_pause_machine.on_foreground(monotonic_ms());
				}
				// Posted before DidActivate, so the flag is clear before the resume is queued.
				set_app_backgrounded(false);
			}];

		// One serial queue carries every pause and resume in arrival order, which settles the race and rapid toggles.
		s_pause_queue = dispatch_queue_create("armsx3.lifecycle-pause",
			dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL,
				QOS_CLASS_USER_INITIATED, 0));

		// The gate closes before the pause is queued and opens before the resume, since the core still mini-flips while paused.
		[centre addObserverForName:UISceneWillDeactivateNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				s_scene_active = false;

				const std::uint64_t at = monotonic_ms();
				dispatch_async(s_pause_queue, ^{
					pause_on_deactivate(at);
					defer_declined_pause(at); // a pause declined in STARTING is retried
				});
			}];

		[centre addObserverForName:UISceneDidActivateNotification
			object:nil queue:[NSOperationQueue mainQueue]
			usingBlock:^(NSNotification*) {
				s_scene_active = true;

				dispatch_async(s_pause_queue, ^{
					resume_on_activate();
				});
			}];

		// s_scene_active starts true, so a scene already inactive here would leave the gate open; read the real state instead.
		bool active = false;
		for (UIScene* scene in UIApplication.sharedApplication.connectedScenes)
		{
			active = active || scene.activationState == UISceneActivationStateForegroundActive;
		}
		s_scene_active = active;
	}

	bool lifecycle_observers_installed() { return s_observers_installed.load(); }

	s32 set_menu_pause(bool open, int* out_called)
	{
		__block int called = 0;
		__block Rpcs3IOS_Status status = RPCS3_IOS_STATUS_WRONG_STATE;
		// shortcut: the flag flips before the queue exists; the null check covers that window, which closes long before a menu opens.
		dispatch_queue_t queue = s_pause_queue;
		if (s_observers_installed.load() && queue != nullptr)
		{
			dispatch_sync(queue, ^{
				status = menu_pause_worker(open, called);
			});
		}
		if (out_called != nullptr)
			*out_called = called;
		return status;
	}

	void note_boot_accepted()
	{
		// shortcut: the flag flips before the queue exists; the null check covers that window, which closes long before a boot.
		dispatch_queue_t queue = s_pause_queue;
		if (s_observers_installed.load() && queue != nullptr)
		{
			const std::uint64_t at = monotonic_ms();
			// Never waits on the queue: a pause on it can block for seconds.
			dispatch_async(queue, ^{
				pause_on_boot_accepted(at);
			});
		}
	}

	pause_ledger pause_ledger_snapshot()
	{
		std::lock_guard lock(s_pause_mutex);
		return s_pause_machine.l;
	}

	u64 scene_connects()      { return s_scene_connects.load(); }
	u64 scene_disconnects()   { return s_scene_disconnects.load(); }
	u64 background_entries()  { return s_background_entries.load(); }
	u64 foreground_entries()  { return s_foreground_entries.load(); }
	u64 surface_loss_notes()  { return s_surface_loss_notes.load(); }

	bool replace_view_in_place()
	{
		if (!on_main_thread())
		{
			ios_frame_log.error("replace_view_in_place called off the main thread -- refused, "
			                    "for the reason attach_view states.");
			return false;
		}

		UIView* old_view = s_view;

		if (old_view == nil)
		{
			return false;
		}

		UIView* parent = [old_view superview];

		if (parent == nil)
		{
			// False means not exercised, never failed: outside a hierarchy the replacement would publish a pre-layout size.
			return false;
		}

		// Identical dimensions on purpose: the renderer's size check cannot see this case, which is why display_epoch exists.
		const CGRect frame = [old_view frame];
		const NSUInteger index = [[parent subviews] indexOfObject:old_view];

		void* raw = make_view();

		if (raw == nullptr)
		{
			return false;
		}

		// The +1 from -alloc crosses back here, written for both memory models as in make_view.
#if __has_feature(objc_arc)
		UIView* fresh = (__bridge_transfer UIView*)raw;
#else
		UIView* fresh = [(__bridge UIView*)raw autorelease];
#endif

		[fresh setFrame:frame];

		if (index == NSNotFound)
		{
			[parent addSubview:fresh];
		}
		else
		{
			[parent insertSubview:fresh atIndex:index];
		}

		[old_view removeFromSuperview];

		// Laid out first, because attach_view publishes what the layer reports at that moment.
		[parent layoutIfNeeded];

		attach_view((__bridge void*)fresh);

		return true;
	}
}
