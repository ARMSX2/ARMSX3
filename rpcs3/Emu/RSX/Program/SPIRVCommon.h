#pragma once

namespace glsl
{
	enum program_domain : unsigned char;
	enum glsl_rules : unsigned char;
}

namespace spirv
{
	bool compile_glsl_to_spv(std::vector<u32>& spv, std::string& shader, ::glsl::program_domain domain, ::glsl::glsl_rules rules);

#ifdef __ANDROID__
	// ARMSX3: desktop GLSL from the OpenGL renderer, to SPIR-V under OpenGL rules, for SPIRV-Cross
	// to write back out as GLSL ES (Emu/RSX/GL/GLSLES.cpp). Does not log: the reason a shader
	// failed goes to `error`, for the caller to decide what it is worth.
	bool compile_glsl_to_spv_for_es(std::vector<u32>& spv, const std::string& shader, ::glsl::program_domain domain, std::string& error);
#endif

	void initialize_compiler_context();
	void finalize_compiler_context();
}
