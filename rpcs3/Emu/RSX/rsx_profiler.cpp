#include "stdafx.h"
#include "rsx_profiler.h"

#include "util/sysinfo.hpp"

#include <string>

LOG_CHANNEL(prof_log, "RSXPROF");

namespace rsx::prof
{
	std::atomic<bool> g_enabled{false};
	accounting g_acc{};
	bucket g_current = bucket::unclassified;
	u64 g_last_switch = 0;
	const void* g_owner_thread = nullptr;
	u64 g_fifo_refills = 0;
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
		case bucket::draw_setup: return "Draw setup";
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

		prof_log.success("%s", report);

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
