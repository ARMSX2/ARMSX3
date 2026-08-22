// SPDX-License-Identifier: GPL-2.0-or-later
//
// The settings the ported passes read.
//
// The Eden/ARMSX2 sources read a global `GSConfig` with fields named LsfgEnabled, LsfgMultiplier
// and so on. RPCS3 keeps the same values in g_cfg under different names and different types --
// a mode enum rather than a multiplier, most obviously -- so this is the one place the two
// vocabularies meet. Keeping it in a single header means a future merge from Eden touches the
// passes and not the wiring.

#pragma once

#include "Emu/system_config.h"

namespace vk::lsfg
{
	inline bool enabled()
	{
		return g_cfg.video.frame_generation != frame_generation_mode::off;
	}

	/// How many frames to show per rendered one. PCSX2 stored this directly; RPCS3 stores a mode.
	inline u32 multiplier()
	{
		switch (g_cfg.video.frame_generation)
		{
		case frame_generation_mode::x3: return 3;
		case frame_generation_mode::x4: return 4;
		default: return 2;
		}
	}

	/// 25..100. The optical flow runs at this fraction of full resolution.
	inline u32 flow_scale()
	{
		return g_cfg.video.frame_generation_flow_scale.get();
	}

	/// Hz to hold, or 0 to use multiplier() unchanged. Non-zero selects adaptive pacing.
	inline u32 target_rate()
	{
		return g_cfg.video.frame_generation_target_rate.get();
	}

	/// The cheaper 3.1p shader family.
	inline bool performance_mode()
	{
		return g_cfg.video.frame_generation_performance.get();
	}
}
