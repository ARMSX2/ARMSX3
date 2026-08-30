// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Camille LaVey
// Relicensed to GPL-2.0-or-later for use in ARMSX3 by Camille LaVey, founder of the Eden
// Emulator Project and author of this implementation (eden-emu PR #4263), who granted
// permission for it to be used here. ARMSX3 derives from RPCS3, which is GPL-2.0-ONLY with no
// "or later" clause, so the original GPL-3.0-or-later terms could not be carried across --
// ARMSX2 is GPL-3.0 and ships it unchanged. Eden is unaffected: "or later" leaves its own use
// exactly as it was.
// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Compatibility shim for code ported from Eden (eden-emu PR #4263).
//
// The port is deliberately kept close to the original so upstream fixes stay easy to follow.
// Eden is a yuzu descendant and leans on two things PCSX2 does not have: an `f32` scalar alias,
// and a `Settings::values.*` configuration object. Rather than rewriting every use of those
// across ~3000 lines, they are provided here once.
//
// LICENSING: Eden is GPL-3.0-or-later and PCSX2 is GPL-3.0+, so this code may be combined.
// Note this is NOT true of RPCS3, which is GPL-2.0-only — do not carry these files there.

#pragma once

#include "util/types.hpp"
#include "FrameGenConfig.h"
#include "Config.h"

#include <algorithm>
#include <cstddef>
#include "FrameGenConfig.h"

// yuzu/Eden spell the float aliases this way; PCSX2 only defines the integer ones.
using f32 = float;
using f64 = double;

namespace VideoCore::FrameGen
{
	/// Hard ceiling on interpolated frames per rendered frame. Matches LSFG_MAX_GENERATIONS in
	/// the pass code — the shader set only carries weights for three.
	inline constexpr size_t MAX_GENERATIONS = 3;
} // namespace VideoCore::FrameGen

/// ★ __forceinline_odr, NOT __fi.
///
/// On GCC/Clang PCSX2's __forceinline expands to __attribute__((always_inline, unused)) with no
/// `inline` keyword, so a free function marked __fi in a header gets EXTERNAL linkage and every
/// translation unit that includes it emits its own copy — "duplicate symbol" at link, from a
/// header that compiles perfectly in isolation. __fi is fine on member functions defined inside a
/// class body, which are implicitly inline; that is what the rest of the renderer uses it for.
/// RPCS3 spells this FORCE_INLINE (util/types.hpp); it expands to always_inline + inline,
/// so these stay ODR-safe in a header exactly as FORCE_INLINE did.
namespace Vulkan::FrameGenSettings
{
	/// Interpolated frames per rendered frame, as the user configured it.
	///
	/// GSConfig stores the MULTIPLIER (2 = one interpolated frame, 3 = two, ...) because that is
	/// what the UI shows; the ported code counts GENERATIONS. The two differ by one, and mixing
	/// them up shows as "x2 looks like x3", so the conversion lives only here.
	FORCE_INLINE size_t Generations()
	{
		const u32 mult = std::max<u32>(::vk::lsfg::multiplier(), 2u);
		return std::min<size_t>(mult - 1u, VideoCore::FrameGen::MAX_GENERATIONS);
	}

	/// Upper bound the pacer may probe up to.
	FORCE_INLINE size_t MaxGenerations()
	{
		return ::vk::lsfg::enabled() ? Generations() : 0;
	}

	/// Target OUTPUT rate in Hz, or 0 to hold the multiplier fixed.
	///
	/// This is what makes the pacer adaptive rather than a blind multiplier, and it is the whole
	/// answer to games that oscillate between 60 and 30fps on a 60Hz panel: at a fixed x2 such a
	/// game presents 120 then 60, and the panel shows judder at every transition. Given a target
	/// the pacer instead varies the generation count to hold the output near it — two interpolated
	/// frames while the game runs at 30, one while it runs at 60.
	///
	/// Zero preserves the old fixed-multiplier behaviour exactly, so this is opt-in.
	FORCE_INLINE f32 TargetRate()
	{
		return static_cast<f32>(::vk::lsfg::target_rate());
	}
} // namespace Vulkan::FrameGenSettings
