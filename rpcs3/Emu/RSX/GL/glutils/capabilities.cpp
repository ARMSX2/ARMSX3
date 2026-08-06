#include "stdafx.h"
#include "capabilities.h"

#include "Utilities/StrUtil.h"
#include "Emu/system_config.h"

#include <unordered_set>

namespace gl
{
	version_info::version_info(const char* version_string, int major_scale)
	{
		auto tokens = fmt::split(version_string, { "." });
		if (tokens.size() < 2)
		{
			rsx_log.warning("Invalid version string: '%s'", version_string);
			version = version_major = version_minor = 0;
			return;
		}

		version_major = static_cast<u8>(std::stoi(tokens[0]));
		version_minor = static_cast<u8>(std::stoi(tokens[1]));
		version = static_cast<u16>(version_major * major_scale) + version_minor;
	}

#ifdef RSX_GLES
	// ARMSX3: the ES capability set is decided here rather than by probing for
	// desktop extension strings that can never appear. Everything asserted below
	// is either core ES 3.2 or emulated in OpenGL_ES.cpp; everything denied is
	// something ES genuinely does not have.
	void capabilities::initialize()
	{
		int ext_count = 0;
		glGetIntegerv(GL_NUM_EXTENSIONS, &ext_count);

		std::unordered_set<std::string> all_extensions;
		for (int i = 0; i < ext_count; i++)
		{
			if (const char* ext = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i)))
			{
				all_extensions.emplace(ext);
			}
		}

		const auto has = [&](const char* name) { return all_extensions.contains(name); };

		const char* vendor_c = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
		const char* renderer_c = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
		std::string vendor_string = vendor_c ? vendor_c : "";
		std::string renderer_string = renderer_c ? renderer_c : "";

		if (!vendor_string.empty())
		{
			std::transform(vendor_string.begin(), vendor_string.end(), vendor_string.begin(), ::tolower);
		}

		RENDERDOC_debug = !!g_cfg.video.renderdoc_compatiblity;

		// GLSL ES version string looks like "OpenGL ES GLSL ES 3.20", so the
		// leading words have to go before version_info's "major.minor" parse.
		if (const char* glsl_str = reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION)))
		{
			std::string_view v{ glsl_str };
			if (const auto digit = v.find_first_of("0123456789"); digit != std::string_view::npos)
			{
				v.remove_prefix(digit);
			}

			glsl_version = version_info(std::string(v).c_str());
		}

		// --- Asserted: core ES 3.2, or emulated in OpenGL_ES.cpp ---

		// EXT_texture_buffer is core in ES 3.2. GLGSRender hard-fails without this.
		ARB_texture_buffer_object_supported = true;

		// EXT, deliberately not ARB. glutils/common.h's DSA_CALL macros pass a
		// texture target to the EXT spelling and drop it for ARB, because on
		// desktop an ARB-DSA texture object remembers its own target. ES keeps no
		// such state, so the target has to survive - which only the EXT branch
		// does. See the header comment in OpenGL_ES.hpp.
		EXT_direct_state_access_supported = true;
		ARB_direct_state_access_supported = false;

		// ES 3.1 core.
		ARB_compute_shader_supported = true;
		ARB_shader_storage_buffer_object_supported = true;

		// Depth32F is core in ES 3.0.
		ARB_depth_buffer_float_supported = true;

		// --- Probed ---

		// Immutable + persistent-mapped buffers. Without it the backend falls back
		// to legacy_ring_buffer, which works but costs a map/unmap per allocation.
		ARB_buffer_storage_supported = has("GL_EXT_buffer_storage");

		EXT_texture_compression_s3tc_supported =
			has("GL_EXT_texture_compression_s3tc") || has("GL_EXT_texture_compression_dxt1");

		// --- Denied: no ES equivalent exists ---

		// ES has no raster-order framebuffer feedback primitive of any kind.
		// GLGSRender already logs "feedback loops will have undefined results" and
		// carries on when both of these are false, which is the honest state.
		ARB_texture_barrier_supported = false;
		NV_texture_barrier_supported = false;

		// No bindless textures in ES, at all. GLGSRender downgrades
		// shader_mode::*interpreter* to async_recompiler when this is false.
		ARB_bindless_texture_supported = false;

		// Vendor-specific desktop extensions. None of these can appear on ES.
		ARB_shader_draw_parameters_supported = false;
		NV_gpu_shader5_supported = false;
		AMD_gpu_shader_half_float_supported = false;
		EXT_depth_bounds_test_supported = false;
		NV_depth_buffer_float_supported = false;
		ARB_shader_stencil_export_supported = false;
		NV_fragment_shader_barycentric_supported = false;
		AMD_pinned_memory_supported = false;
		ARB_shader_texture_image_samples_supported = false;

		// ES occlusion queries are boolean (GL_ANY_SAMPLES_PASSED); there is no
		// GL_SAMPLES_PASSED equivalent, so an exact z-pass count is unobtainable.
		// Forced here rather than at the query site so the setting reflects reality.
		if (g_cfg.video.precise_zpass_count)
		{
			rsx_log.warning("[CAPS] OpenGL ES occlusion queries are boolean only; precise z-pass counting is unavailable and has been disabled.");
			g_cfg.video.precise_zpass_count.set(false);
		}

		// Vendor identification. The desktop paths key off Mesa/NVIDIA/AMD/Intel;
		// on Android it is Qualcomm, ARM, Imagination or ANGLE-over-Vulkan. None of
		// the desktop vendor workarounds apply, so leave every vendor_* flag false
		// and just record what we are on.
		rsx_log.notice("[CAPS] GLES vendor='%s' renderer='%s' glsl=%d extensions=%d",
			vendor_string, renderer_string, glsl_version.version, ext_count);

		initialized = true;
	}
#else
	void capabilities::initialize()
	{
		int ext_count = 0;
		glGetIntegerv(GL_NUM_EXTENSIONS, &ext_count);

		if (!ext_count)
		{
			rsx_log.error("Could not initialize GL driver capabilities. Is OpenGL initialized?");
			return;
		}

		std::string vendor_string = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
		std::string version_string = reinterpret_cast<const char*>(glGetString(GL_VERSION));
		std::string renderer_string = reinterpret_cast<const char*>(glGetString(GL_RENDERER));

		std::unordered_set<std::string> all_extensions;
		for (int i = 0; i < ext_count; i++)
		{
			all_extensions.emplace(reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i)));
		}

		RENDERDOC_debug = !!g_cfg.video.renderdoc_compatiblity;

#define CHECK_EXTENSION_SUPPORT(extension_short_name)\
	do {\
		if (all_extensions.contains("GL_"#extension_short_name)) {\
			extension_short_name##_supported = true;\
			rsx_log.success("[CAPS] Using GL_"#extension_short_name);\
			continue;\
		} \
	} while (0)

		CHECK_EXTENSION_SUPPORT(ARB_shader_draw_parameters);

		CHECK_EXTENSION_SUPPORT(EXT_direct_state_access);

		CHECK_EXTENSION_SUPPORT(ARB_direct_state_access);

		CHECK_EXTENSION_SUPPORT(ARB_bindless_texture);

		CHECK_EXTENSION_SUPPORT(ARB_buffer_storage);

		CHECK_EXTENSION_SUPPORT(ARB_texture_buffer_object);

		CHECK_EXTENSION_SUPPORT(ARB_depth_buffer_float);

		CHECK_EXTENSION_SUPPORT(ARB_texture_barrier);

		CHECK_EXTENSION_SUPPORT(NV_texture_barrier);

		CHECK_EXTENSION_SUPPORT(NV_gpu_shader5);

		CHECK_EXTENSION_SUPPORT(AMD_gpu_shader_half_float);

		CHECK_EXTENSION_SUPPORT(ARB_compute_shader);

		CHECK_EXTENSION_SUPPORT(EXT_depth_bounds_test);

		CHECK_EXTENSION_SUPPORT(NV_depth_buffer_float);

		CHECK_EXTENSION_SUPPORT(ARB_shader_stencil_export);

		CHECK_EXTENSION_SUPPORT(NV_fragment_shader_barycentric);

		CHECK_EXTENSION_SUPPORT(AMD_pinned_memory);

		CHECK_EXTENSION_SUPPORT(ARB_shader_texture_image_samples);

		CHECK_EXTENSION_SUPPORT(EXT_texture_compression_s3tc);

		CHECK_EXTENSION_SUPPORT(ARB_shader_storage_buffer_object);

#undef CHECK_EXTENSION_SUPPORT

		// Set GLSL version
		glsl_version = version_info(reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION)));

		// Check GL_VERSION and GL_RENDERER for the presence of Mesa
		if (version_string.find("Mesa") != umax || renderer_string.find("Mesa") != umax)
		{
			vendor_MESA = true;

			if (vendor_string.find("nouveau") != umax)
			{
				subvendor_NOUVEAU = true;
			}
			else if (vendor_string.find("AMD") != umax)
			{
				subvendor_RADEONSI = true;
			}
		}

		// Workaround for intel drivers which have terrible capability reporting
		if (!vendor_string.empty())
		{
			std::transform(vendor_string.begin(), vendor_string.end(), vendor_string.begin(), ::tolower);
		}
		else
		{
			rsx_log.error("Failed to get vendor string from driver. Are we missing a context?");
			vendor_string = "intel"; // lowest acceptable value
		}

		if (!vendor_MESA && vendor_string.find("intel") != umax)
		{
			int version_major = 0;
			int version_minor = 0;

			glGetIntegerv(GL_MAJOR_VERSION, &version_major);
			glGetIntegerv(GL_MINOR_VERSION, &version_minor);

			vendor_INTEL = true;

			// Texture buffers moved into core at GL 3.3
			if (version_major > 3 || (version_major == 3 && version_minor >= 3))
				ARB_texture_buffer_object_supported = true;

			// Check for expected library entry-points for some required functions
			if (!ARB_buffer_storage_supported && glNamedBufferStorage && glMapNamedBufferRange)
				ARB_buffer_storage_supported = true;

			if (!ARB_direct_state_access_supported && glGetTextureImage && glTextureBufferRange)
				ARB_direct_state_access_supported = true;

			if (!EXT_direct_state_access_supported && glGetTextureImageEXT && glTextureBufferRangeEXT)
				EXT_direct_state_access_supported = true;
		}
		else if (!vendor_MESA && vendor_string.find("nvidia") != umax)
		{
			vendor_NVIDIA = true;
		}
#ifdef _WIN32
		else if (vendor_string.find("amd") != umax || vendor_string.find("ati") != umax)
		{
			vendor_AMD = true;

			// NOTE: Some of the later rebrands ended up in the 7000 line with 'AMD Radeon' branding.
			// However, they all are stuck at GLSL 4.40 and below.
			subvendor_ATI = renderer_string.find("ATI Radeon") != umax || glsl_version.version < 450;
		}
#endif

		initialized = true;
	}
#endif // RSX_GLES

	const std::string get_device_name()
	{
		if (const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER)))
		{
			return renderer;
		}

		return "OpenGL GPU";
	}
}
