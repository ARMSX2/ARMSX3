// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Camille LaVey
// Relicensed to GPL-2.0-or-later for use in ARMSX3 by Camille LaVey, founder of the Eden
// Emulator Project and author of this implementation (eden-emu PR #4263), who granted
// permission for it to be used here. ARMSX3 derives from RPCS3, which is GPL-2.0-ONLY with no
// "or later" clause, so the original GPL-3.0-or-later terms could not be carried across --
// ARMSX2 is GPL-3.0 and ships it unchanged. Eden is unaffected: "or later" leaves its own use
// exactly as it was.
// SPDX-License-Identifier: GPL-2.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Ported from Eden (eden-emu PR #4263), src/video_core/frame_gen/lossless_dll.h.
// Logic unchanged. The one substantive difference is the path type: Eden passes
// std::filesystem::path, PCSX2 uses std::string throughout with its own FileSystem/Path
// helpers, so every path here is a std::string. See FrameGenTypes.h.

#pragma once

#include "FrameGenTypes.h"

#include <array>
#include <map>
#include <string>
#include <vector>

namespace VideoCore::FrameGen {

enum class LosslessStatus : u32 {
    Ok,
    NotInstalled,
    UnreadableFile,
    NotPortableExecutable,
    MissingShaders,
    TranslationFailed,
    CacheUnusable,
};

using ShaderResources = std::map<u32, std::vector<u8>>;
using ShaderModules = std::map<u32, std::vector<u32>>;

enum class ShaderVariant : u32 {
    NativeFp32 = 1,
    NativeFp16 = 2,
};

namespace PerformanceShader {
constexpr u32 MIPMAPS = 255;
constexpr u32 GENERATE = 256;
constexpr std::array<u32, 4> ALPHA{290, 291, 292, 293};
constexpr std::array<u32, 5> BETA{298, 299, 300, 301, 302};
constexpr std::array<u32, 5> GAMMA{280, 282, 283, 284, 285};
constexpr std::array<u32, 10> DELTA{280, 286, 287, 288, 289, 281, 294, 295, 296, 297};

constexpr u32 NATIVE_FP16_OFFSET = 49;
constexpr u32 NATIVE_FP32_OFFSET = 98;
} // namespace PerformanceShader

[[nodiscard]] std::string GetLosslessDllPath();

[[nodiscard]] std::string GetShaderCachePath();

[[nodiscard]] LosslessStatus ReadShaderResources(const std::string& path,
                                                 ShaderResources& out_resources);

[[nodiscard]] LosslessStatus ValidateLosslessDll(const std::string& path);

[[nodiscard]] LosslessStatus GetInstalledLosslessStatus();

/// Whether the fp16 family may be used, asked of the live render device.
///
/// THREE callers need the same answer -- the cache writer, LsfgShaders, and the availability
/// probe -- and the cache header stores the flags and rejects an entry whose flags differ. Any
/// two of them disagreeing means a cache that can never be read: it wrote fp16 and was probed
/// with fp32, which reads to a user as "no shaders" immediately after a successful import.
[[nodiscard]] bool Float16Allowed();

/// [allow_fp16]/[prefer_fp16] MUST match what LsfgShaders asks for: the cache header stores them
/// and rejects an entry whose flags differ, so a mismatch means the cache written at import can
/// never satisfy the read that follows it.
[[nodiscard]] LosslessStatus BuildShaderCache(bool allow_fp16, bool prefer_fp16);

[[nodiscard]] LosslessStatus LoadShaderModules(ShaderModules& out_modules,
                                              bool allow_fp16 = false,
                                              bool prefer_fp16 = false);

/// Delete the on-disk SPIR-V cache, forcing a rebuild from the DLL next time.
///
/// Eden's equivalent also deleted the DLL; see the note in the .cpp for why that half is gone.
void ClearShaderCache();

} // namespace VideoCore::FrameGen
