#pragma once

#include <cstdint>
#include <string>
#include <vector>

// SPIR-V made by glslang under OpenGL rules, written back out as GLSL ES 3.20 source. Never
// throws: false, with the reason in `error`, when SPIRV-Cross cannot translate it.
bool spirv_to_essl(const std::vector<std::uint32_t>& spirv, std::string& essl, std::string& error);
