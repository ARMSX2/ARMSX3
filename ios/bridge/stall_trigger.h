// SPDX-License-Identifier: GPL-2.0-only
// When the perf loop writes a stall dump; a pause holds the zero-tick count, because a pause neither is nor ends a stall.
#pragma once

#include <cstdint>

namespace armsx3::bridge::diag
{
	constexpr std::uint32_t k_stall_ticks = 5;
	constexpr std::uint32_t k_sync_ticks = 2;

	enum class stall_reason : std::uint8_t
	{
		none,
		fps0,
		stopping,
		sync,
	};

	inline const char* stall_reason_word(stall_reason r)
	{
		switch (r)
		{
		case stall_reason::fps0: return "fps0";
		case stall_reason::stopping: return "stopping";
		case stall_reason::sync: return "sync";
		default: return "none";
		}
	}

	struct stall_trigger
	{
		std::uint64_t boot = 0;
		bool drew = false;
		bool fired = false;
		std::uint32_t zero_ticks = 0;
		std::uint32_t stop_ticks = 0;
	};

	inline stall_reason stall_tick(stall_trigger& t, std::uint64_t boot, bool running, bool paused, bool stopping, std::uint64_t frames)
	{
		if (boot != t.boot)
		{
			t = stall_trigger{};
			t.boot = boot;
		}

		if (frames > 0)
		{
			t.drew = true;
			t.fired = false;
			t.zero_ticks = 0;
		}
		else if (running && t.drew)
		{
			t.zero_ticks++;
		}
		else if (paused)
		{
			// Held: neither counted nor cleared.
		}
		else
		{
			t.zero_ticks = 0;
		}

		t.stop_ticks = stopping ? t.stop_ticks + 1 : 0;

		if (t.fired)
		{
			return stall_reason::none;
		}

		if (t.stop_ticks >= k_stall_ticks)
		{
			t.fired = true;
			return stall_reason::stopping;
		}

		if (t.zero_ticks >= k_stall_ticks)
		{
			t.fired = true;
			return stall_reason::fps0;
		}

		return stall_reason::none;
	}

	inline stall_reason stall_on_sync(stall_trigger& t, std::uint64_t boot)
	{
		if (boot == t.boot && t.drew && !t.fired && t.zero_ticks >= k_sync_ticks)
		{
			t.fired = true;
			return stall_reason::sync;
		}

		return stall_reason::none;
	}
}
