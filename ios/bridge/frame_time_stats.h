// SPDX-License-Identifier: GPL-2.0-only
// Frame-time stats for the perf line; P95 and P99 are nearest-rank, and tests/test_frame_time_stats.py pins every definition.
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace armsx3::bridge::diag
{
	struct frame_time_stats
	{
		std::uint32_t n = 0;
		double low1_fps = 0;
		double p95_ms = 0;
		double p99_ms = 0;
		std::uint32_t gt100 = 0;
		std::uint32_t gt250 = 0;
		std::uint32_t gt500 = 0;
	};

	// Sorts its argument in place.
	inline frame_time_stats compute_frame_time_stats(std::vector<std::uint32_t>& intervals_us)
	{
		frame_time_stats r{};
		const auto n = static_cast<std::uint32_t>(intervals_us.size());

		if (n == 0)
		{
			return r;
		}

		std::sort(intervals_us.begin(), intervals_us.end());

		const auto rank_ms = [&](std::uint64_t p)
		{
			return intervals_us[(p * n + 99) / 100 - 1] / 1000.0;
		};

		const std::uint32_t k = (n + 99) / 100;
		std::uint64_t slowest = 0;

		for (std::uint32_t i = n - k; i < n; i++)
		{
			slowest += intervals_us[i];
		}

		r.n = n;
		r.p95_ms = rank_ms(95);
		r.p99_ms = rank_ms(99);
		r.low1_fps = 1e6 / (static_cast<double>(slowest) / k);

		for (const std::uint32_t us : intervals_us)
		{
			r.gt100 += us > 100000;
			r.gt250 += us > 250000;
			r.gt500 += us > 500000;
		}

		return r;
	}
}
