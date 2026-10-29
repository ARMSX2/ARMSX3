#include "stdafx.h"
#include "OpenGL.h"

#ifdef RSX_GLES

#include "Emu/RSX/Program/GLSLTypes.h"
#include "Emu/RSX/Program/SPIRVCommon.h"
#include "spirv_to_essl.h"

#include <cctype>

//
// ARMSX3: retarget RPCS3's generated GLSL at OpenGL ES.
//
// RPCS3 builds every shader at runtime, so this is a code-generation problem,
// not a matter of editing .glsl files. There are three emitters (GLVertexProgram,
// GLFragmentProgram, GLShaderInterpreter) plus a dozen fixed snippets that are
// shared verbatim with the Vulkan backend. Rather than fork all of them, the
// rewrite happens at the single point every shader passes through on its way to
// glShaderSource: gl::glsl::shader::precompile(). That function already has a
// precedent for exactly this - patch_macros_INTEL, a driver workaround applied to
// the finished source - so the hook costs upstream three guarded lines.
//
// The shaders are written for desktop GLSL 4.x, and ES 3.2 is not a subset of it:
// no implicit int/uint/float conversions, no swizzles on scalars, no {} initializer
// lists, no ## in macros, no undefined macros in #if, no computed layout values,
// const only for constant initializers. Those are throughout the shared snippets
// every game shader includes, so no header can fix them. Each shader is therefore
// compiled to SPIR-V by glslang under OpenGL rules, then written back out as GLSL ES
// 3.20 by SPIRV-Cross, which spells every one of them the way ES wants.
//
// Should either step fail, the old header rewrite below runs instead, and the
// reason is logged: the driver will most likely refuse that shader too, and its own
// compile log would not say why.
//
// What neither path does, and why it is called out rather than papered over:
//
//   * sampler1D. ES has no 1D texture of any kind. The API side is emulated
//     (height-1 2D, see OpenGL_ES.cpp) but the shader side needs the decompilers
//     in Emu/RSX/Program to emit sampler2D and pad the coordinate, and that code
//     is shared with the Vulkan backend. Programs that sample a 1D texture will
//     fail to compile until that is done.
//
//   * gl_ClipDistance needs EXT_clip_cull_distance, which is not universal on
//     mobile. Where it is missing, user clip planes are lost.
//
//   * The MSAA sampler paths assume desktop sample-count introspection
//     (ARB_shader_texture_image_samples). capabilities.cpp reports that
//     unsupported, so multisampled programs are not generated in the first place.
//

namespace gl::es
{
	namespace
	{
		// Enabled, not required: most of these are core in ES 3.2, and demanding
		// an extension a compiler has folded into core is itself an error on some
		// drivers. "enable" is the spelling that works in both cases.
		constexpr const char* k_extensions =
			"#extension GL_EXT_texture_buffer : enable\n"                     // usamplerBuffer - the vertex stream
			"#extension GL_EXT_texture_cube_map_array : enable\n"
			"#extension GL_EXT_clip_cull_distance : enable\n"                 // gl_ClipDistance - user clip planes
			"#extension GL_EXT_shader_io_blocks : enable\n"
			"#extension GL_OES_texture_storage_multisample_2d_array : enable\n"
			"#extension GL_EXT_shader_framebuffer_fetch : enable\n";          // closest thing ES has to a texture barrier

		// ES has no default precision for float in fragment shaders, and none at
		// all for most opaque types. Omitting any one of these is a compile error
		// in the shader that happens to use it, so the block is exhaustive rather
		// than minimal - the compiler discards the unused ones.
		constexpr const char* k_precision =
			"precision highp float;\n"
			"precision highp int;\n"
			"precision highp sampler2D;\n"
			"precision highp sampler3D;\n"
			"precision highp samplerCube;\n"
			"precision highp sampler2DArray;\n"
			"precision highp sampler2DShadow;\n"
			"precision highp samplerCubeShadow;\n"
			"precision highp sampler2DArrayShadow;\n"
			"precision highp isampler2D;\n"
			"precision highp isampler3D;\n"
			"precision highp isamplerCube;\n"
			"precision highp isampler2DArray;\n"
			"precision highp usampler2D;\n"
			"precision highp usampler3D;\n"
			"precision highp usamplerCube;\n"
			"precision highp usampler2DArray;\n"
			"precision highp samplerBuffer;\n"
			"precision highp isamplerBuffer;\n"
			"precision highp usamplerBuffer;\n"
			"precision highp sampler2DMS;\n"
			"precision highp isampler2DMS;\n"
			"precision highp usampler2DMS;\n"
			"precision highp image2D;\n"
			"precision highp iimage2D;\n"
			"precision highp uimage2D;\n"
			"precision highp image3D;\n"
			"precision highp uimage3D;\n"
			"precision highp image2DArray;\n"
			"precision highp uimage2DArray;\n"
			"precision highp imageCube;\n"
			"precision highp uimageCube;\n";

		// The fallback: swap the #version line for ES's, ahead of the extensions and
		// precision ES needs. The shader's own #extension lines move up with them, since
		// ES wants every one before the first declaration, and the precision block is one.
		void rewrite_header(std::string& source)
		{
			const auto version_pos = source.find("#version");
			if (version_pos == std::string::npos)
			{
				// Nothing generated by RPCS3 is versionless; if that changes, an ES
				// compiler would silently fall back to ESSL 1.00 and produce a wall of
				// unrelated errors. Say what actually happened.
				rsx_log.error("GLES: shader source has no #version directive; it cannot be retargeted at ES.");
				return;
			}

			auto line_end = source.find('\n', version_pos);
			line_end = (line_end == std::string::npos) ? source.length() : line_end + 1;

			std::string own_extensions;
			std::string body;
			body.reserve(source.length() - line_end);

			for (usz pos = line_end; pos < source.length();)
			{
				usz end = source.find('\n', pos);
				end = (end == std::string::npos) ? source.length() : end + 1;

				const std::string_view line(source.data() + pos, end - pos);
				(line.starts_with("#extension") ? own_extensions : body).append(line);
				pos = end;
			}

			// Anything before #version goes too, blank lines included: some ES compilers
			// refuse even whitespace ahead of it
			source = "#version 320 es\n";
			source += k_extensions;
			source += own_extensions;
			source += k_precision;
			source += body;
		}

		// What glslang parses: the source as written, but at #version 450 if it says less. RPCS3's
		// shaders lean on what desktop drivers allow below that, integer mix() at 430 among them,
		// and glslang holds them to the letter. 450 is a superset of the versions RPCS3 writes, and
		// only the SPIR-V that comes out of it matters.
		std::string source_for_glslang(const std::string& source)
		{
			const auto version_pos = source.find("#version");
			if (version_pos == std::string::npos)
			{
				return source;
			}

			const auto number_pos = source.find_first_of("0123456789", version_pos);
			const auto number_end = source.find_first_not_of("0123456789", number_pos);
			if (number_pos == std::string::npos || number_end == std::string::npos || number_end - number_pos != 3 ||
				source.compare(number_pos, 3, "450") >= 0)
			{
				return source;
			}

			std::string upgraded = source;
			upgraded.replace(number_pos, 3, "450");
			return upgraded;
		}

		// Uniforms the source gives a location of its own, as in
		//   layout(location=0) uniform usamplerBuffer persistent_input_stream;
		// RPCS3 sets some of those by number (the vertex stream samplers, uniforms[0] and [1]), so
		// they keep it; spirv_to_essl drops every location glslang made up. RPCS3 writes these
		// declarations one to a line.
		std::vector<std::string> located_uniforms(const std::string& source)
		{
			std::vector<std::string> names;

			for (usz pos = 0; pos < source.length();)
			{
				usz end = source.find('\n', pos);
				end = (end == std::string::npos) ? source.length() : end;
				const std::string_view line(source.data() + pos, end - pos);
				pos = end + 1;

				const usz open = line.find("layout");
				const usz close = (open == std::string_view::npos) ? open : line.find(')', open);
				if (close == std::string_view::npos || line.substr(open, close - open).find("location") == std::string_view::npos)
				{
					continue;
				}

				const usz keyword = line.find("uniform", close);
				const usz stop = (keyword == std::string_view::npos) ? keyword : line.find_first_of(";[", keyword);
				if (stop == std::string_view::npos)
				{
					continue;
				}

				usz name_end = stop;
				while (name_end > keyword && line[name_end - 1] == ' ')
				{
					name_end--;
				}

				usz name_start = name_end;
				while (name_start > keyword && (std::isalnum(static_cast<unsigned char>(line[name_start - 1])) || line[name_start - 1] == '_'))
				{
					name_start--;
				}

				if (name_start < name_end)
				{
					names.emplace_back(line.substr(name_start, name_end - name_start));
				}
			}

			return names;
		}

		const char* domain_name(::glsl::program_domain domain)
		{
			switch (domain)
			{
			case ::glsl::program_domain::glsl_vertex_program: return "vertex";
			case ::glsl::program_domain::glsl_fragment_program: return "fragment";
			default: return "compute";
			}
		}
	}

	void patch_glsl_for_es(std::string& source, ::glsl::program_domain domain)
	{
		std::vector<u32> spirv;
		std::string essl;
		std::string error;

		if (spirv::compile_glsl_to_spv_for_es(spirv, source_for_glslang(source), domain, error) &&
			spirv_to_essl(spirv, located_uniforms(source), essl, error))
		{
			source = std::move(essl);
			return;
		}

		// Every shader passes through here, so a failure common to all of them would bury
		// the log. The first few say why; after that only the count goes on.
		static atomic_t<u32> s_failures = 0;
		if (const u32 count = s_failures++; count < 8)
		{
			rsx_log.error("GLES: a %s shader could not be translated to GLSL ES; trying it with a rewritten header instead:\n%s",
				domain_name(domain), error);
		}
		else if ((count & (count - 1)) == 0)
		{
			rsx_log.error("GLES: %u shaders could not be translated to GLSL ES so far", count);
		}

		rewrite_header(source);
	}
}

#endif // RSX_GLES
