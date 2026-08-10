#include "stdafx.h"
#include "rsx_profiler.h"

#include "util/sysinfo.hpp"

#include <string>
#include <algorithm>
#include <utility>
#include "gcm_printing.h"

LOG_CHANNEL(prof_log, "RSXPROF");

namespace rsx::prof
{
	std::atomic<bool> g_enabled{false};
	accounting g_acc{};
	bucket g_current = bucket::unclassified;
	u64 g_last_switch = 0;
	const void* g_owner_thread = nullptr;
	u64 g_fifo_refills = 0;
	u64 g_fifo_commands = 0;
	u64 g_fifo_dispatches = 0;
	u64 g_draw_calls = 0;
	u64 g_present_checks = 0;
	u64 g_frame_cleanups = 0;
	u64 g_fence_polls = 0;
	u64 g_fence_polls_not_ready = 0;
	u64 g_xform_program_calls = 0;
	u64 g_xform_program_words = 0;
	u64 g_xform_const_calls = 0;
	u64 g_xform_const_words = 0;
	u32 g_method_counts[method_slot_count] = {};
	u64 g_fifo_refill_bytes = 0;
	u64 g_fifo_refill_stalls = 0;
	u64 g_fifo_refill_stall_us = 0;
	u64 g_render_passes = 0;
	u64 g_mprotect_calls = 0;
	u64 g_mprotect_bytes = 0;
	u64 g_access_violations = 0;
	u64 g_rp_sites[rp_site_count] = {};
	const char* g_rp_site_names[rp_site_count] = {
		"Draw:1044",
		"Draw:1093",
		"QueryPool:217",
		"Compute:146",
		"Texture:60",
		"Texture:225",
		"Texture:352",
		"Texture:494",
		"Texture:534",
		"Texture:921",
		"Barrier:img",
		"Barrier:buf",
		"Barrier:mem",
		"Barrier:inout",
		"TexCache:87",
		"TexCache:1227",
		"ImgHelper:43",
		"GSR:2811",
	};
	u64 g_flush_sites[flush_site_count] = {};
	const char* g_flush_site_names[flush_site_count] = {
		"GSR:996",
		"GSR:1064",
		"GSR:1156",
		"GSR:1171",
		"GSR:1565",
		"GSR:1650",
		"GSR:1746",
		"GSR:1781",
		"GSR:1820",
		"GSR:2628",
		"GSR:2787",
		"GSR:2861",
		"Present:77",
		"Present:156",
		"Present:247",
		"Present:251",
		"Present:413",
		"Present:573",
		"Present:845",
		"Present:1091",
		"Present:1102",
	};

	void bind_to_current_thread()
	{
		g_owner_thread = current_thread_token();
	}

	const char* name_of(bucket b)
	{
		switch (b)
		{
		case bucket::fifo_decode: return "FIFO decode";
		case bucket::fifo_refill: return "FIFO refill";
		case bucket::xform_program: return "Xform program";
		case bucket::xform_const: return "Xform constant";
		case bucket::draw_setup: return "Draw setup";
		case bucket::draw_prologue: return "Draw prologue";
		case bucket::draw_epilogue: return "Draw epilogue";
		case bucket::wr_barrier: return "Write barrier";
		case bucket::rtt_write: return "RTT on_write";
		case bucket::tex_release: return "Temp tex release";
		case bucket::present_check: return "Present check";
		case bucket::swap_wait: return "Swap fence wait";
		case bucket::res_trim: return "Resource trim";
		case bucket::res_gc: return "Resource destroy";
		case bucket::fence_poll: return "Fence poll";
		case bucket::vertex: return "Vertex/index";
		case bucket::shader_translate: return "Shader translate";
		case bucket::shader_compile: return "Shader compile";
		case bucket::pipeline: return "Pipeline";
		case bucket::descriptors: return "Descriptors";
		case bucket::texcache_lookup: return "Texcache lookup";
		case bucket::texture_upload: return "Texture upload";
		case bucket::rt_prep: return "RT prep";
		case bucket::blit_resolve: return "Blit/resolve";
		case bucket::barrier: return "Barriers";
		case bucket::cmdbuf: return "Cmdbuf record";
		case bucket::submit: return "Submit";
		case bucket::fence_wait: return "Fence wait";
		case bucket::present_wait: return "Present wait";
		case bucket::page_protect: return "Page protect";
		case bucket::zcull: return "ZCULL update";
		case bucket::local_task: return "Local task";
		case bucket::idle: return "Idle";
		case bucket::unclassified: return "Unclassified";
		default: return "?";
		}
	}

	void set_enabled(bool enabled)
	{
		if (enabled == g_enabled.load(std::memory_order_relaxed))
		{
			return;
		}

		// Drop whatever was accumulated, so a window never straddles the switch and
		// reports a partial frame's worth of one bucket against a full window.
		g_acc = {};
		g_acc.window_start = utils::get_tsc();
		g_last_switch = g_acc.window_start;
		// Arming happens from inside the RSX dispatch loop, so that is genuinely where we
		// are. Without this the loop's scope, constructed back when the profiler was off,
		// never became active and its time fell through to unclassified.
		g_current = bucket::fifo_decode;

		bind_to_current_thread();
		g_enabled.store(enabled, std::memory_order_relaxed);
		prof_log.success("RSX profiling %s", enabled ? "enabled" : "disabled");
	}

	void tick_frame()
	{
		if (!g_enabled.load(std::memory_order_relaxed)) [[likely]]
		{
			return;
		}

		g_acc.frames++;

		// Report on a frame boundary rather than a timer, so per-frame costs divide by a
		// whole number of frames and a long stall lands in the window that contains it.
		if (g_acc.frames >= 300)
		{
			dump_and_reset();
		}
	}

	void dump_and_reset()
	{
		if (!g_acc.frames)
		{
			return;
		}

		const u64 now = utils::get_tsc();
		const u64 freq = utils::get_tsc_freq();
		const u64 window = now - g_acc.window_start;

		if (!freq || !window)
		{
			g_acc = {};
			g_acc.window_start = now;
			return;
		}

		// Charge the in-flight bucket too, otherwise whatever is running at the moment of
		// the dump is silently missing from its own report.
		g_acc.ticks[static_cast<usz>(g_current)] += now - g_last_switch;
		g_last_switch = now;

		const double to_ms = 1000.0 / static_cast<double>(freq);
		const double frames = static_cast<double>(g_acc.frames);

		u64 accounted = 0;
		for (const u64 t : g_acc.ticks)
		{
			accounted += t;
		}

		std::string report = fmt::format(
			"RSX profile over %u frames, %.1f ms of thread time (%.2f ms/frame)",
			g_acc.frames, static_cast<double>(window) * to_ms,
			static_cast<double>(window) * to_ms / frames);

		for (usz i = 0; i < bucket_count; i++)
		{
			const u64 ticks = g_acc.ticks[i];
			if (!ticks)
			{
				continue;
			}

			fmt::append(report, "\n\t%-18s %7.3f ms/frame  %5.1f%%",
				name_of(static_cast<bucket>(i)),
				static_cast<double>(ticks) * to_ms / frames,
				static_cast<double>(ticks) * 100.0 / static_cast<double>(window));
		}

		// A large gap means RSX thread time is going somewhere with no scope on it, which
		// makes every percentage above an overestimate of its share. Worth saying so
		// rather than letting the buckets read as if they covered the frame.
		if (window > accounted)
		{
			const u64 gap = window - accounted;
			fmt::append(report, "\n\t%-18s %7.3f ms/frame  %5.1f%%  (no scope)",
				"Unscoped", static_cast<double>(gap) * to_ms / frames,
				static_cast<double>(gap) * 100.0 / static_cast<double>(window));
		}

		if (g_fifo_refills)
		{
			{
				std::string drains;
				for (u32 i = 0; i < flush_site_count; i++)
				{
					if (!g_flush_sites[i]) continue;
					fmt::append(drains, "%s%s %.2f", drains.empty() ? "" : ", ",
						g_flush_site_names[i], static_cast<double>(g_flush_sites[i]) / frames);
				}
				fmt::append(report, "\n\tpage protect    %.1f mprotect/frame, %.2f MB, %.1f faults/frame",
				static_cast<double>(g_mprotect_calls) / frames,
				static_cast<double>(g_mprotect_bytes) / 1048576.0 / frames,
				static_cast<double>(g_access_violations) / frames);

			fmt::append(report, "\n\trender passes   %.1f/frame",
				static_cast<double>(g_render_passes) / frames);

			{
				std::string sites;
				for (u32 i = 0; i < rp_site_count; i++)
				{
					if (!g_rp_sites[i]) continue;
					fmt::append(sites, "%s%s %.1f", sites.empty() ? "" : ", ",
						g_rp_site_names[i], static_cast<double>(g_rp_sites[i]) / frames);
				}
				fmt::append(report, "\n\trp closes/frame %s", sites.empty() ? "none" : sites);
			}

			fmt::append(report, "\n\tdrains/frame    %s", drains.empty() ? "none" : drains);
			}

			fmt::append(report, "\n\tFIFO stalls     %.1f/frame, %.3f ms/frame spinning",
				static_cast<double>(g_fifo_refill_stalls) / frames,
				static_cast<double>(g_fifo_refill_stall_us) / 1000.0 / frames);

			fmt::append(report, "\n\tFIFO cache      %.1f refills/frame, %.0f bytes each",
				static_cast<double>(g_fifo_refills) / frames,
				static_cast<double>(g_fifo_refill_bytes) / static_cast<double>(g_fifo_refills));
		}

		if (g_xform_program_calls || g_xform_const_calls)
		{
			const auto ns_each = [&](bucket b, u64 calls)
			{
				return calls
					? static_cast<double>(g_acc.ticks[static_cast<usz>(b)]) * to_ms * 1'000'000.0 / static_cast<double>(calls)
					: 0.0;
			};

			fmt::append(report, "\n\txform program   %.0f calls/frame, %.1f words each, %.0f ns each",
				static_cast<double>(g_xform_program_calls) / frames,
				g_xform_program_calls ? static_cast<double>(g_xform_program_words) / static_cast<double>(g_xform_program_calls) : 0.0,
				ns_each(bucket::xform_program, g_xform_program_calls));

			fmt::append(report, "\n\txform constant  %.0f calls/frame, %.1f words each, %.0f ns each",
				static_cast<double>(g_xform_const_calls) / frames,
				g_xform_const_calls ? static_cast<double>(g_xform_const_words) / static_cast<double>(g_xform_const_calls) : 0.0,
				ns_each(bucket::xform_const, g_xform_const_calls));
		}

		if (g_fifo_commands)
		{
			// The per-command figure is against fifo_decode specifically, since that is the
			// bucket with no owner left in it.
			const u64 decode_ticks = g_acc.ticks[static_cast<usz>(bucket::fifo_decode)];

			// Labelled packets, because that is what it counts. fifo_decode is also a
			// catch-all holding every method handler body, since no handler carries its
			// own scope, so neither figure is dispatch overhead alone.
			fmt::append(report, "\n\tFIFO packets    %.0f/frame, %.0f ns each in FIFO decode",
				static_cast<double>(g_fifo_commands) / frames,
				static_cast<double>(decode_ticks) * to_ms * 1'000'000.0 / static_cast<double>(g_fifo_commands));

			if (g_fifo_dispatches)
			{
				fmt::append(report, "\n\tFIFO dispatches %.0f/frame, %.1f/packet, %.0f ns each",
					static_cast<double>(g_fifo_dispatches) / frames,
					static_cast<double>(g_fifo_dispatches) / static_cast<double>(g_fifo_commands),
					static_cast<double>(decode_ticks) * to_ms * 1'000'000.0 / static_cast<double>(g_fifo_dispatches));
			}
		}

		if (g_draw_calls)
		{
			// Every bucket named here is entered exactly once per draw, so dividing by the
			// draw count turns "this bucket is big" into "each draw pays this much", which is
			// the form that says whether to cut the per-draw cost or the number of draws.
			const auto ns_per_draw = [&](bucket b)
			{
				return static_cast<double>(g_acc.ticks[static_cast<usz>(b)]) * to_ms * 1'000'000.0
					/ static_cast<double>(g_draw_calls);
			};

			fmt::append(report, "\n\tdraws           %.0f/frame, %.0f ns each in draw setup",
				static_cast<double>(g_draw_calls) / frames,
				ns_per_draw(bucket::draw_setup));

			fmt::append(report, "\n\tpresent checks  %.1f/frame, %.1f cleanups/frame, %.1f us each",
				static_cast<double>(g_present_checks) / frames,
				static_cast<double>(g_frame_cleanups) / frames,
				g_present_checks
					? static_cast<double>(g_acc.ticks[static_cast<usz>(bucket::present_check)]
						+ g_acc.ticks[static_cast<usz>(bucket::swap_wait)]
						+ g_acc.ticks[static_cast<usz>(bucket::res_trim)])
						* to_ms * 1000.0 / static_cast<double>(g_present_checks)
					: 0.0);

			if (g_fence_polls)
			{
				fmt::append(report, "\n\tfence polls     %.1f/frame, %.0f ns each, %.1f%% not ready",
					static_cast<double>(g_fence_polls) / frames,
					static_cast<double>(g_acc.ticks[static_cast<usz>(bucket::fence_poll)])
						* to_ms * 1'000'000.0 / static_cast<double>(g_fence_polls),
					static_cast<double>(g_fence_polls_not_ready) * 100.0 / static_cast<double>(g_fence_polls));
			}

			fmt::append(report, "\n\tper draw        prologue %.0f, epilogue %.0f, wr barrier %.0f, on_write %.0f, tex release %.0f ns",
				ns_per_draw(bucket::draw_prologue),
				ns_per_draw(bucket::draw_epilogue),
				ns_per_draw(bucket::wr_barrier),
				ns_per_draw(bucket::rtt_write),
				ns_per_draw(bucket::tex_release));
		}

		{
			// Top methods by volume. Names via gcm_printing, which is the same table the
			// command dumps use, so these read the same as the log's own FIFO traces.
			std::pair<u32, u32> top[8] = {};
			for (u32 i = 0; i < method_slot_count; i++)
			{
				if (!g_method_counts[i]) continue;
				if (g_method_counts[i] <= top[7].second) continue;
				top[7] = { i, g_method_counts[i] };
				std::sort(std::begin(top), std::end(top),
					[](const auto& a, const auto& b) { return a.second > b.second; });
			}

			if (top[0].second)
			{
				std::string list;
				for (const auto& [slot, count] : top)
				{
					if (!count) continue;
					std::string scratch;
					const auto name = rsx::get_method_name(slot << 2, scratch).second;
					fmt::append(list, "\n\t  %-46s %8.0f/frame  %4.1f%%",
						name.empty() ? fmt::format("0x%05x", slot << 2) : std::string(name),
						static_cast<double>(count) / frames,
						static_cast<double>(count) * 100.0 / static_cast<double>(g_fifo_dispatches ? g_fifo_dispatches : g_fifo_commands));
				}
				fmt::append(report, "\n\ttop methods%s", list);
			}
		}

		prof_log.success("%s", report);

		g_fifo_commands = 0;
		g_fifo_dispatches = 0;
		g_draw_calls = 0;
		g_present_checks = 0;
		g_frame_cleanups = 0;
		g_fence_polls = 0;
		g_fence_polls_not_ready = 0;
		g_xform_program_calls = 0;
		g_xform_program_words = 0;
		g_xform_const_calls = 0;
		g_xform_const_words = 0;
		std::fill(std::begin(g_method_counts), std::end(g_method_counts), 0u);
		g_fifo_refills = 0;
		g_fifo_refill_bytes = 0;
		g_fifo_refill_stalls = 0;
		g_fifo_refill_stall_us = 0;
		g_render_passes = 0;
		g_mprotect_calls = 0;
		g_mprotect_bytes = 0;
		g_access_violations = 0;
		for (auto& c : g_rp_sites) c = 0;
		for (auto& c : g_flush_sites) c = 0;

		g_acc = {};
		g_acc.window_start = now;
	}
}
