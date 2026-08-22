// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Camille LaVey
// Relicensed to GPL-2.0-or-later for use in ARMSX3 by Camille LaVey, founder of the Eden
// Emulator Project and author of this implementation (eden-emu PR #4263), who granted
// permission for it to be used here. ARMSX3 derives from RPCS3, which is GPL-2.0-ONLY with no
// "or later" clause, so the original GPL-3.0-or-later terms could not be carried across --
// ARMSX2 is GPL-3.0 and ships it unchanged. Eden is unaffected: "or later" leaves its own use
// exactly as it was.
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Ported from Eden (eden-emu PR #4263), src/video_core/frame_gen/lsfg_translate.h.
// Logic unchanged; only the scalar aliases are ours. See FrameGenTypes.h.

#pragma once

#include "FrameGenTypes.h"

#include <span>
#include <vector>

namespace VideoCore::FrameGen {

[[nodiscard]] bool IsSpirvModule(std::span<const u8> blob);

[[nodiscard]] std::vector<u32> AdoptSpirvModule(std::span<const u8> blob);

} // namespace VideoCore::FrameGen
