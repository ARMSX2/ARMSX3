// SPDX-License-Identifier: GPL-2.0-only
// The plain C++ surface between the iOS view and the renderer; it includes no RSX header, so ios_frame.mm can import UIKit.
#pragma once

// atomic_t cannot be forward-declared: a class template's default argument may be given by only one declaration.
#include "util/atomic.hpp"
#include "util/types.hpp"

#include <memory>
#include <string>
#include <vector>

// Forward-declared: including GSFrameBase.h would put an RSX type in front of the Objective-C++ compiler.
class GSFrameBase;

namespace armsx3::ios_frame
{
	// With CAMetalLayer visible the Vulkan headers retype VkMetalSurfaceCreateInfoEXT::pLayer and swapchain_macos.hpp stops compiling.

	// -1, not 0: a zero-pixel view is a real transient during layout, while "no view" is not a dimension.
	inline constexpr int k_metric_declined = -1;

	// Filled by the caller before the renderer starts, read by take_screenshot on the RSX thread.
	struct capture_config
	{
		std::string out_dir;       // where the .rgba and its .json sidecar are written
		std::string basename;      // stem shared by both files
		std::string title_serial;  // the title's own serial, or empty for a non-title boot target
		std::string title_role;    // "smoke", "acceptance" or "none"
		std::string boot_target;   // "title" or "vsh"
		std::string merge_commit;  // the upstream base this binary was built from
		std::string compiler_id;   // CMAKE_CXX_COMPILER_ID, passed in as a compile definition
		u64 flip_threshold = 0;    // the flip index at which the capture was requested

		// The points pair comes from the published metrics, since UIKit lays the view out and no caller knows its size.
	};

	// Not synchronised: the RSX thread does not exist yet when this is called, and nothing writes it afterwards.
	void set_capture_config(const capture_config& cfg);

	// The only writer of the renderer's screenshot request; a flip and the arming ticker both call it, so a late condition is seen.
	void maybe_arm_capture();

	// Called by the arming ticker only: VKPresent can take the request and drop it, and a re-arm from flip() would loop per frame.
	void maybe_rearm_capture();

	// Every entry into maybe_arm_capture, the denominator for the four below.
	unsigned long long arm_evaluations();

	unsigned long long arm_reject_flip_threshold();

	// The guest-vblank floor was not met.
	unsigned long long arm_reject_vblank_floor();

	// A capture had already been written or had already failed.
	unsigned long long arm_reject_settled();

	// Always zero: the one-shot flag it counted is gone, and removing the field would change the record schema.
	unsigned long long arm_reject_one_shot();

	unsigned long long capture_rearms();

	// Incremented by the frame's flip() override.
	extern atomic_t<u64> g_flip_count;

	// Not g_flip_count: VKPresent calls flip() with nothing to present, so only this says how far the guest has got.
	extern atomic_t<u64> g_guest_vblank_count;

	// When non-zero, the capture also waits for this many guest vblanks.
	extern atomic_t<u64> g_min_guest_vblanks;

	extern atomic_t<bool> g_capture_written;

	// Distinguished from "no capture arrived" so a driver's exit message can say which happened.
	extern atomic_t<bool> g_capture_failed;

	// k_metric_declined until the frame has been asked.
	extern atomic_t<s32> g_requested_client_width;
	extern atomic_t<s32> g_requested_client_height;

	// Accessors, not externs, so nothing can become a second writer of the arming state.

	bool capture_armed();

	// Zero when never armed; the caller writes the declined sentinel instead, since a zero reads as a real tally.
	unsigned long long capture_armed_flip_index();

	// 0 when never armed; formatting belongs to the record writer.
	unsigned long long capture_armed_utc_epoch();

	// Entries into the OpenGL-path virtuals, not a frame rate; a non-zero entry count with no non-null context shows they stay inert.
	unsigned long long gl_make_context_entries();
	unsigned long long gl_set_current_entries();
	unsigned long long gl_flip_entries();
	unsigned long long gl_delete_context_entries();

	// Bumped when set_current or delete_context is handed a non-null context, which this frame never hands out.
	unsigned long long gl_non_null_context_reports();

	// Main thread only: view property access off the main thread is undefined on this platform.

	// A factory, because only a view whose +layerClass is CAMetalLayer can present, and that class cannot be declared here.
	void* make_view();

	// `ui_view` is a RSX_APPLE_VIEW_CLASS*; the epoch is bumped only when this is a replacement.
	void attach_view(void* ui_view);

	// Does not bump the epoch: the swapchain's surface-lost path owns that case.
	void detach_view();

	// Null when no view is attached.
	void* current_view();

	// Atomic loads of values published on the main thread, so the RSX thread can read them every frame.

	// Pixels, not points, or the swapchain would be built at a fraction of the presented resolution; 0 while the gate is closed.
	int client_pixel_width();
	int client_pixel_height();

	// IosGraphicsFrame::flip holds the RSX thread while this is true.
	bool present_gate_closed();

	// |pixels - points * content_scale| <= 1, because MoltenVK's rounding can differ from the layer's by a pixel.
	int view_points_width();
	int view_points_height();

	// 0.0 with no view attached, since a scale cannot hold k_metric_declined.
	f64 content_scale();

	// The screen's observed maximum rate, which is not what client_display_rate() returns; 0.0 with no view.
	f64 display_rate();

	// The size check cannot see a replacement at identical dimensions, so it must not move on a re-publish of the same values.
	u64 display_epoch();

	// Instrumented, not driven: nothing reliably forces a scene disconnect on iOS, so nothing here waits for one.

	// Main thread only, and idempotent.
	void install_lifecycle_observers();

	// Any thread: it posts the idle-timer write to the main queue.
	void enable_display_sleep(bool enabled);

	bool lifecycle_observers_installed();

	// Any thread: one atomic store.
	void set_app_backgrounded(bool backgrounded);

	// Blocking: call it from the bridge queue, never the main thread or the pause queue; WRONG_STATE when observers are absent.
	s32 set_menu_pause(bool open, int* out_called);

	// Called from on_run on every boot, the core's own restarts included; it only queues onto the pause queue.
	void note_boot_accepted();

	// A save asks for the next presented frame, and take_screenshot keeps a copy at most 320 px on its long edge.
	void request_thumbnail();

	u64 thumbnail_generation();

	// False, with the outputs untouched, when none has been stored.
	bool copy_thumbnail(std::vector<u8>& rgba, u32& width, u32& height, u64& generation);

	// take_screenshot stores the next presented frame at full size; the ABI waits only while the game reads RUNNING.
	void request_screenshot();
	void cancel_screenshot();
	u64 screenshot_generation();

	// Moved out, so a full-size frame is not held twice. False, with the outputs untouched, when none is stored.
	bool copy_screenshot(std::vector<u8>& rgba, u32& width, u32& height, u64& generation);

	// A scene connect is not a foreground and a disconnect is not a background.
	u64 scene_connects();
	u64 scene_disconnects();
	u64 background_entries();
	u64 foreground_entries();

	// Counts the attached view leaving its window, not VK_ERROR_SURFACE_LOST_KHR returns.
	u64 surface_loss_notes();

	// Main thread only; false means the mechanism was not exercised, never that it failed.
	bool replace_view_in_place();

	// The get_gs_frame body; the frame it returns reads only the atomics above.
	std::unique_ptr<GSFrameBase> make_frame();
}
