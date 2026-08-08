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

		prof_log.success("%s", report);

		g_acc = {};
		g_acc.window_start = now;
	}
}
