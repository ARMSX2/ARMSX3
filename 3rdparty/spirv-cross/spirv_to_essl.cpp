#include "spirv_to_essl.h"

#include "SPIRV-Cross/spirv_glsl.hpp"

#include <algorithm>
#include <exception>

bool spirv_to_essl(const std::vector<std::uint32_t>& spirv, const std::vector<std::string>& located_uniforms,
	std::string& essl, std::string& error)
{
	try
	{
		spirv_cross::CompilerGLSL compiler(spirv);

		const auto located = [&](const spirv_cross::Resource& uniform)
		{
			return std::find(located_uniforms.begin(), located_uniforms.end(), uniform.name) != located_uniforms.end();
		};

		// glslang maps a location onto every uniform the source left without one, because
		// OpenGL SPIR-V needs it, and it numbers each stage on its own. A program then has two
		// uniforms on one location (tex0 in a fragment shader took 0, which the vertex shader's
		// persistent_input_stream holds), and the link fails. RPCS3 finds those uniforms by
		// name, so made-up locations go. Spelled-out ones stay: RPCS3 sets some by number.
		const auto resources = compiler.get_shader_resources();

		for (const auto& uniform : resources.gl_plain_uniforms)
		{
			// ES allows a binding only on blocks, samplers and images
			compiler.unset_decoration(uniform.id, spv::DecorationBinding);
			compiler.unset_decoration(uniform.id, spv::DecorationDescriptorSet);

			if (!located(uniform))
			{
				compiler.unset_decoration(uniform.id, spv::DecorationLocation);
			}
		}

		for (const auto* opaque : { &resources.sampled_images, &resources.separate_images,
			&resources.separate_samplers, &resources.storage_images })
		{
			for (const auto& uniform : *opaque)
			{
				if (!located(uniform))
				{
					compiler.unset_decoration(uniform.id, spv::DecorationLocation);
				}
			}
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
