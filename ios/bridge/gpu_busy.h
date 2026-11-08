// SPDX-License-Identifier: GPL-2.0-only
// GPU busy time from Metal command-buffer spans; overlaps count once, so gpu_pct cannot pass 100. tests/test_gpu_busy.py pins it.
#pragma once

#include <algorithm>
#include <cstdint>

namespace armsx3::bridge::diag
{
	struct gpu_busy
	{
		double window_start = 0;
		double last_end = 0;
		double busy_s = 0;
		std::uint32_t cbs = 0;
	};

	// shortcut: only last_end is kept, so a span completing after a later-starting one is undercounted; keep an interval list if queues overlap.
	inline void gpu_busy_add(gpu_busy& g, double start, double end)
	{
		if (!(start > 0) || !(end > start))
		{
			return;
		}

		const double from = std::max({start, g.last_end, g.window_start});

		if (end > from)
		{
			g.busy_s += end - from;
		}

		g.last_end = std::max(g.last_end, end);
		g.cbs++;
	}

	struct gpu_busy_tick
	{
		double gpu_ms = 0;
		double gpu_pct = 0;
		std::uint32_t cbs = 0;
	};

	inline gpu_busy_tick gpu_busy_take(gpu_busy& g, double now, std::uint64_t frames)
	{
		const double window = g.window_start > 0 && now > g.window_start ? now - g.window_start : 0;
		gpu_busy_tick t{};
		t.gpu_ms = frames ? g.busy_s * 1000.0 / static_cast<double>(frames) : 0;
		t.gpu_pct = window > 0 ? g.busy_s / window * 100.0 : 0;
		t.cbs = g.cbs;
		g.busy_s = 0;
		g.cbs = 0;
		g.window_start = now;
		return t;
	}
}
