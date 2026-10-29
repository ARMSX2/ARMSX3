#include "spirv_to_essl.h"

#include "SPIRV-Cross/spirv_glsl.hpp"

#include <exception>

bool spirv_to_essl(const std::vector<std::uint32_t>& spirv, std::string& essl, std::string& error)
{
	try
	{
		spirv_cross::CompilerGLSL compiler(spirv);

		// Default-block uniforms only have a location and a binding because OpenGL SPIR-V needs
		// them and glslang mapped some. RPCS3 finds these by name, ES allows a binding only on
		// blocks, samplers and images, and a uniform both stages declare could be mapped to
		// different locations in each, which fails the link.
		for (const auto& uniform : compiler.get_shader_resources().gl_plain_uniforms)
		{
			compiler.unset_decoration(uniform.id, spv::DecorationBinding);
			compiler.unset_decoration(uniform.id, spv::DecorationLocation);
			compiler.unset_decoration(uniform.id, spv::DecorationDescriptorSet);
		}

		spirv_cross::CompilerGLSL::Options options;
		options.version = 320;
		options.es = true;
		options.vulkan_semantics = false;
		options.enable_420pack_extension = false;

		// The PS3 computes in full 32-bit float; ES fragment shaders default to mediump
		options.fragment.default_float_precision = spirv_cross::CompilerGLSL::Options::Highp;
		options.fragment.default_int_precision = spirv_cross::CompilerGLSL::Options::Highp;
		compiler.set_common_options(options);

		essl = compiler.compile();
		return true;
	}
	catch (const std::exception& e)
	{
		error = e.what();
		return false;
	}
}
