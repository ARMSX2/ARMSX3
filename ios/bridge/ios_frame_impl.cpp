// SPDX-License-Identifier: GPL-2.0-only
// IosGraphicsFrame and the capture sink: plain C++ that sees the RSX headers and never UIKit; every override runs on the RSX thread.

#include "ios_frame.h"

#include "diag_log.h"

#include <TargetConditionals.h>

#include "Emu/RSX/GSFrameBase.h"
// The arming evaluators hold while paused.
#include "Emu/System.h"
// ios_frame.mm may not include a core header, so the store lives here.
#include "Emu/android_app_state.h"
#include "Emu/IdManager.h"
#include "Utilities/File.h"
#include "util/logs.hpp"
#include "util/video_provider.h"
#include "util/vm.hpp"

#include <cstdio>
#include <ctime>
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

LOG_CHANNEL(ios_frame_log, "IOSFRAME");

// Owned by RSXThread.cpp and read by VKPresent.cpp; no header exports it.
extern atomic_t<bool> g_user_asked_for_screenshot;

namespace armsx3::ios_frame
{
	atomic_t<u64> g_flip_count{0};
	atomic_t<u64> g_guest_vblank_count{0};
	atomic_t<u64> g_min_guest_vblanks{0};
	atomic_t<bool> g_capture_written{false};
	atomic_t<bool> g_capture_failed{false};
	atomic_t<s32> g_requested_client_width{k_metric_declined};
	atomic_t<s32> g_requested_client_height{k_metric_declined};

	// No header declares these, so the accessors in ios_frame.h are the only way to read the arming state.
	atomic_t<bool> g_capture_armed{false};
	atomic_t<u64> g_capture_armed_flip_index{0};
	atomic_t<u64> g_capture_armed_utc_epoch{0};

	atomic_t<u64> g_arm_evaluations{0};
	atomic_t<u64> g_arm_reject_flip_threshold{0};
	atomic_t<u64> g_arm_reject_vblank_floor{0};
	atomic_t<u64> g_arm_reject_settled{0};

	// Nothing increments it; removing the field would change a schema that already has a reader.
	atomic_t<u64> g_arm_reject_one_shot{0};

	atomic_t<u64> g_capture_rearm_count{0};

	// Each is incremented only inside its own override, so a count means the virtual was really entered.
	atomic_t<u64> g_gl_make_context_entries{0};
	atomic_t<u64> g_gl_set_current_entries{0};
	atomic_t<u64> g_gl_flip_entries{0};
	atomic_t<u64> g_gl_delete_context_entries{0};
	atomic_t<u64> g_gl_non_null_context_reports{0};

	namespace
	{
		capture_config s_cfg;

		// No edge of a stored thumbnail is longer than this; a screenshot is the same store without the downscale.
		constexpr u32 k_thumb_max_edge = 320;

		// The generation is bumped under the mutex, so a copy never pairs these pixels with another generation.
		struct frame_copy
		{
			std::mutex mutex;
			std::vector<u8> rgba;
			u32 width = 0;
			u32 height = 0;
			atomic_t<u64> generation{0};
		};

		atomic_t<bool> s_thumb_requested{false};
		frame_copy s_thumb;
		atomic_t<bool> s_shot_requested{false};
		frame_copy s_shot;

		// shortcut: point sampling, fine for a thumbnail; switch to a filtered scale if they look rough.
		bool store_frame(const std::vector<u8>& src, u32 width, u32 height, bool is_bgra, u32 max_edge, frame_copy& dst)
		{
			if (width == 0 || height == 0 || src.size() < static_cast<usz>(width) * height * 4)
			{
				return false;
			}

			const u32 step = max_edge == 0 ? 1 : std::max<u32>(1, (std::max(width, height) + max_edge - 1) / max_edge);
			const u32 out_w = std::max<u32>(1, width / step);
			const u32 out_h = std::max<u32>(1, height / step);
			std::vector<u8> out(static_cast<usz>(out_w) * out_h * 4);

			for (u32 y = 0; y < out_h; y++)
			{
				const u8* row = src.data() + static_cast<usz>(y) * step * width * 4;
				u8* to = out.data() + static_cast<usz>(y) * out_w * 4;

				for (u32 x = 0; x < out_w; x++, to += 4)
				{
					const u8* px = row + static_cast<usz>(x) * step * 4;
					to[0] = is_bgra ? px[2] : px[0];
					to[1] = px[1];
					to[2] = is_bgra ? px[0] : px[2];
					to[3] = 0xff;
				}
			}

			{
				std::lock_guard lock(dst.mutex);
				dst.rgba = std::move(out);
				dst.width = out_w;
				dst.height = out_h;
				dst.generation++;
			}

			return true;
		}

		void store_thumbnail(const std::vector<u8>& src, u32 width, u32 height, bool is_bgra)
		{
			store_frame(src, width, height, is_bgra, k_thumb_max_edge, s_thumb);
			s_thumb_requested = false;
		}

		void store_screenshot(const std::vector<u8>& src, u32 width, u32 height, bool is_bgra)
		{
			store_frame(src, width, height, is_bgra, 0, s_shot);
			s_shot_requested = false;
		}

		// Latches so each lifecycle notice is logged once per boot rather than every frame.
		atomic_t<bool> s_said_make_context{false};
		atomic_t<bool> s_said_set_current{false};
		atomic_t<bool> s_said_delete_context{false};

		std::string utc_timestamp()
		{
			const std::time_t now = std::time(nullptr);
			std::tm tm{};
			gmtime_r(&now, &tm);

			char buf[32]{};
			std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &tm);
			return std::string(buf);
		}

		// A simulator capture gets a schema value every reader refuses, stamped here so no caller can change it.
#if TARGET_OS_SIMULATOR
		constexpr const char* k_capture_schema_id = "simulator-not-evidence";
#else
		constexpr const char* k_capture_schema_id = "armsx3-ios-capture/1";
#endif

		// A small JSON writer rather than a dependency, because the guard reading it back uses the standard library only.
		std::string json_escape(const std::string& in)
		{
			std::string out;
			out.reserve(in.size() + 8);

			for (const char c : in)
			{
				switch (c)
				{
				case '"': out += "\\\""; break;
				case '\\': out += "\\\\"; break;
				case '\n': out += "\\n"; break;
				case '\r': out += "\\r"; break;
				case '\t': out += "\\t"; break;
				default:
					if (static_cast<unsigned char>(c) < 0x20)
					{
						char esc[8]{};
						std::snprintf(esc, sizeof(esc), "\\u%04x", static_cast<unsigned>(c) & 0xffu);
						out += esc;
					}
					else
					{
						out += c;
					}
					break;
				}
			}

			return out;
		}
	}

	void set_app_backgrounded(bool backgrounded)
	{
		// A polling hint the readers re-check on their next wake, so ordering does not matter.
		g_android_app_backgrounded.store(backgrounded, std::memory_order_relaxed);
	}

	void request_thumbnail()
	{
		s_thumb_requested = true;
		g_user_asked_for_screenshot = true;
	}

	u64 thumbnail_generation()
	{
		return s_thumb.generation.load();
	}

	bool copy_thumbnail(std::vector<u8>& rgba, u32& width, u32& height, u64& generation)
	{
		std::lock_guard lock(s_thumb.mutex);

		if (s_thumb.rgba.empty())
		{
			return false;
		}

		rgba = s_thumb.rgba;
		width = s_thumb.width;
		height = s_thumb.height;
		// Read under the mutex the store bumps it under, so it is exactly the generation of these pixels.
		generation = s_thumb.generation.load();
		return true;
	}

	void request_screenshot()
	{
		s_shot_requested = true;
		g_user_asked_for_screenshot = true;
	}

	void cancel_screenshot()
	{
		s_shot_requested = false;
		// The renderer's request goes too, unless a thumbnail still wants the frame, so no stray harness capture is written.
		if (!s_thumb_requested.load())
		{
			g_user_asked_for_screenshot = false;
		}
	}

	u64 screenshot_generation()
	{
		return s_shot.generation.load();
	}

	bool copy_screenshot(std::vector<u8>& rgba, u32& width, u32& height, u64& generation)
	{
		std::lock_guard lock(s_shot.mutex);

		if (s_shot.rgba.empty())
		{
			return false;
		}

		// Moved out, not copied: a full-size frame is megabytes, and the one caller takes it once.
		rgba = std::move(s_shot.rgba);
		s_shot.rgba.clear();
		width = s_shot.width;
		height = s_shot.height;
		generation = s_shot.generation.load();
		return true;
	}

	void set_capture_config(const capture_config& cfg)
	{
		s_cfg = cfg;
	}

	bool capture_armed()
	{
		return g_capture_armed.load();
	}

	unsigned long long capture_armed_flip_index()
	{
		return g_capture_armed_flip_index.load();
	}

	unsigned long long capture_armed_utc_epoch()
	{
		return g_capture_armed_utc_epoch.load();
	}

	unsigned long long arm_evaluations()          { return g_arm_evaluations.load(); }
	unsigned long long arm_reject_flip_threshold(){ return g_arm_reject_flip_threshold.load(); }
	unsigned long long arm_reject_vblank_floor()  { return g_arm_reject_vblank_floor.load(); }
	unsigned long long arm_reject_settled()       { return g_arm_reject_settled.load(); }
	unsigned long long arm_reject_one_shot()      { return g_arm_reject_one_shot.load(); }
	unsigned long long capture_rearms()           { return g_capture_rearm_count.load(); }

	unsigned long long gl_make_context_entries()   { return g_gl_make_context_entries.load(); }
	unsigned long long gl_set_current_entries()    { return g_gl_set_current_entries.load(); }
	unsigned long long gl_flip_entries()           { return g_gl_flip_entries.load(); }
	unsigned long long gl_delete_context_entries() { return g_gl_delete_context_entries.load(); }
	unsigned long long gl_non_null_context_reports(){ return g_gl_non_null_context_reports.load(); }

	// Host flips alone would photograph a black screen, so guest vblanks are the condition that counts; every early return is counted.
	void maybe_arm_capture()
	{
		// A screenshot request never outlives a pause, and paused evaluations are not counted.
		if (Emu.IsPaused())
		{
			g_user_asked_for_screenshot = false;
			return;
		}

		++g_arm_evaluations;

		const u64 index = g_flip_count.load();

		if (s_cfg.flip_threshold == 0 || index < s_cfg.flip_threshold)
		{
			++g_arm_reject_flip_threshold;
			return;
		}

		if (const u64 want = g_min_guest_vblanks.load();
			want != 0 && g_guest_vblank_count.load() < want)
		{
			++g_arm_reject_vblank_floor;
			return;
		}

		if (g_capture_written || g_capture_failed)
		{
			++g_arm_reject_settled;
			return;
		}

		// A one-shot on the record, not the request, so a later re-request cannot move the recorded arming instant.
		if (!g_capture_armed.exchange(true))
		{
			g_capture_armed_flip_index = index;
			g_capture_armed_utc_epoch = static_cast<u64>(std::time(nullptr));

			// Worded for both callers, since a tick can arm as well as a flip.
			ios_frame_log.notice("capture armed at flip index %u (threshold %u) -- requesting "
			                     "the renderer's own screenshot readback",
				index, s_cfg.flip_threshold);
		}

		g_user_asked_for_screenshot = true;
	}

	void maybe_rearm_capture()
	{
		// A screenshot request never outlives a pause, and paused evaluations are not counted.
		if (Emu.IsPaused())
		{
			g_user_asked_for_screenshot = false;
			return;
		}

		// VKPresent can consume the request before its readback gate; re-arming from here avoids a core edit and counts the drops.

		if (!g_capture_armed.load())
		{
			// Never armed, so there is nothing to have been dropped.
			return;
		}

		if (g_capture_written || g_capture_failed)
		{
			return;
		}

		if (g_user_asked_for_screenshot.load())
		{
			// Still pending. The presenter has not taken it yet.
			return;
		}

		// Armed, not settled, and the request is gone: the presenter took it and dropped it, so it is counted.
		++g_capture_rearm_count;

		g_user_asked_for_screenshot = true;
	}

	struct IosGraphicsFrame final : GSFrameBase
	{
		IosGraphicsFrame() = default;

		// close() and hide() are reached from the RSX thread, so a body here would need a hop to the main thread.
		void close() override {}
		// Apple builds have Vulkan alone.
		video_renderer renderer() const override { return video_renderer::vulkan; }
		void reset() override {}
		bool shown() override { return true; }
		void hide() override {}
		void show() override {}
		void toggle_fullscreen() override {}

		// Called on every Vulkan boot and inert: the Vulkan backend makes its own surface and never uses this context.
		draw_context_t make_context() override
		{
			++g_gl_make_context_entries;

			if (!s_said_make_context.exchange(true))
			{
				ios_frame_log.notice("make_context reached (GSRender.cpp:34) -- returning a null "
				                     "context. Called-but-inert on the Vulkan path.");
			}

			return nullptr;
		}

		void set_current(draw_context_t ctx) override
		{
			++g_gl_set_current_entries;

			if (!s_said_set_current.exchange(true))
			{
				ios_frame_log.notice("set_current reached (GSRender.cpp:35) -- nothing to make "
				                     "current. Called-but-inert on the Vulkan path.");
			}

			if (ctx != nullptr)
			{
				++g_gl_non_null_context_reports;

				ios_frame_log.error("set_current was handed a non-null drawing context. This "
				                    "frame hands out none, so something upstream believes it "
				                    "does. Ignored.");
			}
		}

		void delete_context(draw_context_t ctx) override
		{
			++g_gl_delete_context_entries;

			if (!s_said_delete_context.exchange(true))
			{
				ios_frame_log.notice("delete_context reached (GSRender.cpp:49) -- nothing to "
				                     "delete. Called-but-inert on the Vulkan path.");
			}

			if (ctx != nullptr)
			{
				++g_gl_non_null_context_reports;

				ios_frame_log.error("delete_context was handed a non-null drawing context. This "
				                    "frame hands out none. Ignored.");
			}
		}

		// Three of VKPresent's four flip calls have nothing to present, so this counts the loop, holds while gated, and asks the evaluator.
		void flip(draw_context_t /*ctx*/, bool skip_frame = false) override
		{
			// Counted before the skip return, so this is an entry counter, unlike g_flip_count.
			++g_gl_flip_entries;

			// While gated, VKGSRender submits a mini-flip and calls here; holding stops further GPU work, and Emu.IsStopped() ends the hold.
			if (armsx3::ios_frame::present_gate_closed())
			{
				ios_frame_log.notice("RSX thread held: the scene is inactive, so no GPU work is "
				                     "submitted until it is active again.");
				const auto held_since = std::chrono::steady_clock::now();
				armsx3::bridge::diag::note_rsx_hold(true);

				while (armsx3::ios_frame::present_gate_closed() && !Emu.IsStopped())
				{
					std::this_thread::sleep_for(std::chrono::milliseconds(10));
				}
				armsx3::bridge::diag::note_rsx_hold(false);
				const u64 held_ms = static_cast<u64>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - held_since).count());
				if (armsx3::ios_frame::present_gate_closed())
				{
					ios_frame_log.notice("RSX thread hold ended by a stop after %u ms, with the scene still inactive.",
					                     held_ms);
				}
				else
				{
					ios_frame_log.notice("RSX thread released: the scene is active again after a hold of %u ms.",
					                     held_ms);
				}
			}

			// After the hold, because a held RSX thread presents nothing.
			armsx3::bridge::diag::note_flip();

			if (skip_frame)
			{
				return;
			}

			++g_flip_count;

			maybe_arm_capture();
		}

		// Atomics published on the main thread, since view properties are undefined off it; pixels, not points.
		int client_width() override
		{
			const int w = armsx3::ios_frame::client_pixel_width();
			g_requested_client_width = static_cast<s32>(w);
			return w;
		}

		int client_height() override
		{
			const int h = armsx3::ios_frame::client_pixel_height();
			g_requested_client_height = static_cast<s32>(h);
			return h;
		}

		// 60.0 for now: the screen's real rate changes guest frame pacing, and that is a separate decision.
		f64 client_display_rate() override { return 60.0; }

		// The layer is opaque, and claiming alpha on an opaque layer costs a format conversion for nothing.
		bool has_alpha() override { return false; }

		// The UIView itself, which vkutils/metal_layer.mm casts back to RSX_APPLE_VIEW_CLASS.
		display_handle_t handle() const override
		{
			return armsx3::ios_frame::current_view();
		}

		u64 display_epoch() const override
		{
			return armsx3::ios_frame::display_epoch();
		}

		// The provider answers false unless a recording holds a sink, so VKPresent's per-frame GPU sync never runs otherwise.
		bool can_consume_frame() const override
		{
			return g_fxo->get<utils::video_provider>().can_consume_frame();
		}

		void present_frame(std::vector<u8>&& data, u32 pitch, u32 width, u32 height, bool is_bgra) const override
		{
			g_fxo->get<utils::video_provider>().present_frame(std::move(data), pitch, width, height, is_bgra);
		}

		// Raw pixels plus a JSON sidecar, so the guard reading them back needs no image decoder; the key names follow the macOS leg.
		void take_screenshot(std::vector<u8>&& sshot_data, u32 sshot_width, u32 sshot_height,
			bool is_bgra) override
		{
			// Stored at full size before the thumbnail and the harness capture, whose flags it never touches.
			if (s_shot_requested.load())
			{
				store_screenshot(sshot_data, sshot_width, sshot_height, is_bgra);
				if (!s_thumb_requested.load())
				{
					return;
				}
			}

			// Stored and returned before the harness capture, whose files and flags this never touches.
			if (s_thumb_requested.load())
			{
				store_thumbnail(sshot_data, sshot_width, sshot_height, is_bgra);
				return;
			}

			const u64 flip_index = g_flip_count.load();
			const usz expected = static_cast<usz>(sshot_width) * sshot_height * 4;

			if (sshot_width == 0 || sshot_height == 0 || sshot_data.size() < expected)
			{
				ios_frame_log.error("the renderer handed over a buffer this sink cannot write: "
				                    "%ux%u needs %u bytes, got %u",
					sshot_width, sshot_height, expected, sshot_data.size());
				g_capture_failed = true;
				return;
			}

			const std::string raw_path = s_cfg.out_dir + "/" + s_cfg.basename + ".rgba";
			const std::string json_path = s_cfg.out_dir + "/" + s_cfg.basename + ".json";

			if (!fs::create_path(s_cfg.out_dir))
			{
				ios_frame_log.error("could not create the capture directory");
				g_capture_failed = true;
				return;
			}

			// Written exactly as the renderer produced it; `is_bgra` goes in the sidecar instead.
			{
				fs::file out(raw_path, fs::rewrite);

				if (!out)
				{
					ios_frame_log.error("could not open the raw capture file for writing");
					g_capture_failed = true;
					return;
				}

				out.write(sshot_data.data(), expected);
			}

			std::string json;
			json += "{\n";
			json += std::string("  \"schema\": \"") + k_capture_schema_id + "\",\n";
			json += fmt::format("  \"width\": %u,\n", sshot_width);
			json += fmt::format("  \"height\": %u,\n", sshot_height);
			json += fmt::format("  \"is_bgra\": %s,\n", is_bgra ? "true" : "false");
			json += fmt::format("  \"bytes\": %u,\n", expected);
			json += fmt::format("  \"flip_index\": %u,\n", flip_index);
			json += fmt::format("  \"flip_threshold\": %u,\n", s_cfg.flip_threshold);
			json += fmt::format("  \"guest_vblank_count\": %u,\n", g_guest_vblank_count.load());
			json += fmt::format("  \"min_guest_vblanks\": %u,\n", g_min_guest_vblanks.load());
			json += fmt::format("  \"requested_client_width\": %d,\n", g_requested_client_width.load());
			json += fmt::format("  \"requested_client_height\": %d,\n", g_requested_client_height.load());
			// Both sides of the points-to-pixels invariant come from the published metrics, not from a caller.
			json += fmt::format("  \"window_points_width\": %d,\n",
				armsx3::ios_frame::view_points_width());
			json += fmt::format("  \"window_points_height\": %d,\n",
				armsx3::ios_frame::view_points_height());
			json += fmt::format("  \"backing_scale\": %f,\n", armsx3::ios_frame::content_scale());
			json += "  \"title_serial\": \"" + json_escape(s_cfg.title_serial) + "\",\n";
			json += "  \"title_role\": \"" + json_escape(s_cfg.title_role) + "\",\n";
			json += "  \"boot_target\": \"" + json_escape(s_cfg.boot_target) + "\",\n";
			// Only the `title` target can read `candidate`; every other target is `not-taken` by construction.
			json += "  \"criterion_2\": \"" +
				std::string(s_cfg.boot_target == "title" ? "candidate" : "not-taken") + "\",\n";
			json += fmt::format("  \"host_page_size\": %d,\n", static_cast<s64>(utils::get_page_size()));
			json += "  \"compiler_id\": \"" + json_escape(s_cfg.compiler_id) + "\",\n";
			json += "  \"merge_commit\": \"" + json_escape(s_cfg.merge_commit) + "\",\n";
			json += "  \"captured_utc\": \"" + json_escape(utc_timestamp()) + "\"\n";
			json += "}\n";

			{
				fs::file out(json_path, fs::rewrite);

				if (!out)
				{
					ios_frame_log.error("could not open the sidecar file for writing");
					g_capture_failed = true;
					return;
				}

				out.write(json.data(), json.size());
			}

			// The path is not logged: on a phone it carries the container UUID.
			ios_frame_log.success("capture written (%ux%u, bgra=%d, flip %u)",
				sshot_width, sshot_height, is_bgra ? 1 : 0, flip_index);

			g_capture_written = true;
		}

		void update_title(double /*fps*/) override {}
	};

	std::unique_ptr<GSFrameBase> make_frame()
	{
		return std::make_unique<IosGraphicsFrame>();
	}
}
