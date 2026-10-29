#pragma once

#include <cstdint>
#include <string>
#include <vector>

// SPIR-V made by glslang under OpenGL rules, written back out as GLSL ES 3.20 source. Never
// throws: false, with the reason in `error`, when SPIRV-Cross cannot translate it.
//
// `located_uniforms` names the uniforms whose location the original source spelled out. Every
// other uniform location in the SPIR-V is one glslang made up, and those are dropped.
bool spirv_to_essl(const std::vector<std::uint32_t>& spirv, const std::vector<std::string>& located_uniforms,
	std::string& essl, std::string& error);
