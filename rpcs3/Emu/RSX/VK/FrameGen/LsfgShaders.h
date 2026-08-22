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
// Ported from Eden (eden-emu PR #4263), src/video_core/renderer_vulkan/present/lsfg_shaders.h.
// Logic unchanged; only the scalar aliases and the Vulkan wrapper types are ours.
// See FrameGenTypes.h and LsfgVkCompat.h.

#pragma once

#include <map>

#include "FrameGenTypes.h"
#include "LsfgVkCompat.h"

namespace Vulkan {

class Device;

class LsfgShaders {
public:
    explicit LsfgShaders(const Device& device);

    [[nodiscard]] bool IsValid() const {
        return valid;
    }

    [[nodiscard]] VkShaderModule Get(u32 shader_id) const;

private:
    std::map<u32, vk::ShaderModule> modules;
    bool valid{};
};

} // namespace Vulkan
