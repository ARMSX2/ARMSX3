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
// Ported from Eden (eden-emu PR #4263), src/video_core/renderer_vulkan/present/lsfg_shaders.cpp.
// Logic unchanged apart from the fp16 gate, which PCSX2 cannot express — see below.
// See FrameGenTypes.h and LsfgVkCompat.h.

#include "LosslessDll.h"
#include "LsfgShaders.h"
#include "LsfgUtil.h"
#include "LsfgVkCompat.h"

namespace Vulkan {

LsfgShaders::LsfgShaders(const Device& device) {
    if (!device.IsVulkanMemoryModelSupported() || !device.HasNullDescriptor()) {
        return;
    }

    // PORT: Eden gates on device.IsFloat16Supported(); ARMSX2 hardcodes both false because
    // PCSX2 never asks for shaderFloat16 when it creates its logical device, so a module
    // declaring the Float16 capability would be invalid usage there. RPCS3 DOES enable it where
    // the driver is trusted -- and it clears its own flag on the Adreno drivers whose compiler
    // rejects native float16, so asking the device is safe.
    //
    // This matters more than it looks: copying ARMSX2's hardcode ran the fp32 family on hardware
    // that supports fp16, which on Adreno is roughly half the compute throughput. Measured on an
    // Adreno 740, frame generation took the real frame rate from ~60 to ~25.
    const bool allow_fp16 = device.IsFloat16Supported();
    const bool prefer_fp16 = allow_fp16;

    VideoCore::FrameGen::ShaderModules code;
    if (VideoCore::FrameGen::LoadShaderModules(code, allow_fp16, prefer_fp16) !=
        VideoCore::FrameGen::LosslessStatus::Ok) {
        return;
    }

    for (const auto& [id, words] : code) {
        modules.emplace(id, CreateWrappedShaderModule(device, words));
    }
    valid = true;
}

VkShaderModule LsfgShaders::Get(u32 shader_id) const {
    const auto hit = modules.find(shader_id);
    return hit == modules.end() ? VK_NULL_HANDLE : *hit->second;
}

} // namespace Vulkan
