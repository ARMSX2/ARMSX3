// Implementation of the C ABI in armsx3_lsfg_shim.h.
//
// This translation unit is the ONLY thing in libarmsx3_lsfg.so that anyone outside it may touch.
// Everything else -- framegen, volk, and volk's 655 vk* globals -- stays hidden behind
// -fvisibility=hidden so the dynamic linker cannot bind our renderer's vkCmdDraw to framegen's
// copy. See the header for why that matters.
//
// Rules for every entry point here:
//   * no C++ type crosses the boundary (separate libc++ per .so under c++_static),
//   * no exception crosses the boundary (framegen throws; dlopen'd code must not),
//   * a failure returns a code and leaves a message in armsx3_lsfg_last_error().

#include "armsx3_lsfg_shim.h"

#include <lsfg_3_1.hpp>
#include <lsfg_3_1p.hpp>

#ifdef ARMSX3_LSFG_HAVE_EXTRACT
#include <extract/extract.hpp>
#include <extract/trans.hpp>
#include <config/config.hpp>
#endif

#include <exception>
#include <map>
#include <string>
#include <vector>

namespace
{
	// thread_local because the renderer and whatever calls initialize() are not the same thread,
	// and a shared buffer would let one overwrite the other's message mid-report.
	thread_local std::string g_last_error;

	bool g_initialized = false;

	// Which shader family initialize() chose. Fixed until finalize(): LSFG_3_1 and LSFG_3_1P keep
	// entirely separate device state and context tables, so a context created by one cannot be
	// presented or destroyed through the other -- every entry point below has to dispatch on this.
	bool g_performance = false;

	void clear_error()
	{
		g_last_error.clear();
	}

	void set_error(const char* what)
	{
		g_last_error = what ? what : "unknown error";
	}

	void set_error(const std::string& what)
	{
		g_last_error = what.empty() ? "unknown error" : what;
	}
}

// Wrap a call so nothing escapes.
//
// catch (...) rather than catching LSFG's types: framegen throws several, they are not part of
// its public header, and an exception reaching the dlopen boundary is undefined behaviour -- so
// the exact type matters less than the guarantee that none of them get out.
#define ARMSX3_LSFG_GUARD(expr, failure_result)                       \
	try                                                               \
	{                                                                 \
		clear_error();                                                \
		expr;                                                         \
	}                                                                 \
	catch (const std::exception& e)                                   \
	{                                                                 \
		set_error(e.what());                                          \
		return (failure_result);                                      \
	}                                                                 \
	catch (...)                                                       \
	{                                                                 \
		set_error("unknown exception from framegen");                 \
		return (failure_result);                                      \
	}

extern "C" uint32_t armsx3_lsfg_abi_version(void)
{
	return ARMSX3_LSFG_ABI_VERSION;
}

extern "C" const char* armsx3_lsfg_last_error(void)
{
	return g_last_error.c_str();
}

extern "C" int armsx3_lsfg_initialize(uint64_t device_uuid, int is_hdr, float flow_scale,
	uint64_t generation_count, int performance, armsx3_lsfg_shader_loader loader, void* user)
{
	if (!loader)
	{
		set_error("no shader loader supplied");
		return ARMSX3_LSFG_ERR_BAD_ARGUMENT;
	}

	// The std::function is built HERE, on framegen's side of the boundary, from a plain C
	// function pointer. That is the whole point of taking a function pointer in the header: an
	// std::function constructed by the core would be a different type under a different libc++.
	//
	// Throwing out of this lambda is how a missing shader is reported to framegen, which is what
	// it expects -- and the throw stays inside this .so, caught by the guard below.
	const auto bridge = [loader, user](const std::string& name) -> std::vector<uint8_t>
	{
		const uint8_t* data = nullptr;
		uint32_t size = 0;

		if (loader(name.c_str(), &data, &size, user) != ARMSX3_LSFG_OK || !data || !size)
		{
			throw std::runtime_error("shader not available: " + name);
		}

		return std::vector<uint8_t>(data, data + size);
	};

	// Recorded BEFORE the call so the guard's failure path cannot leave the two disagreeing.
	g_performance = performance != 0;

	if (g_performance)
	{
		ARMSX3_LSFG_GUARD(
			LSFG_3_1P::initialize(device_uuid, is_hdr != 0, flow_scale, generation_count, bridge),
			ARMSX3_LSFG_ERR_SHADERS)
	}
	else
	{
		ARMSX3_LSFG_GUARD(
			LSFG_3_1::initialize(device_uuid, is_hdr != 0, flow_scale, generation_count, bridge),
			ARMSX3_LSFG_ERR_SHADERS)
	}

	g_initialized = true;
	return ARMSX3_LSFG_OK;
}

extern "C" int32_t armsx3_lsfg_create_context_ahb(void* in0, void* in1, void* const* out_n,
	uint32_t out_count, uint32_t width, uint32_t height, int32_t format)
{
	if (!g_initialized)
	{
		set_error("not initialized");
		return ARMSX3_LSFG_ERR_NOT_INITIALIZED;
	}

	if (!in0 || !in1 || !out_n || !out_count)
	{
		set_error("null image or empty output set");
		return ARMSX3_LSFG_ERR_BAD_ARGUMENT;
	}

	int32_t id = ARMSX3_LSFG_ERR_UNKNOWN;

	// AHardwareBuffer* arrives as void* so the header stays free of android/hardware_buffer.h,
	// which the core has no reason to include.
	std::vector<AHardwareBuffer*> outs;
	outs.reserve(out_count);

	for (uint32_t i = 0; i < out_count; ++i)
	{
		outs.push_back(static_cast<AHardwareBuffer*>(out_n[i]));
	}

	if (g_performance)
	{
		ARMSX3_LSFG_GUARD(
			id = LSFG_3_1P::createContextFromAHB(
				static_cast<AHardwareBuffer*>(in0), static_cast<AHardwareBuffer*>(in1), outs,
				VkExtent2D{width, height}, static_cast<VkFormat>(format)),
			ARMSX3_LSFG_ERR_VULKAN)
	}
	else
	{
		ARMSX3_LSFG_GUARD(
			id = LSFG_3_1::createContextFromAHB(
				static_cast<AHardwareBuffer*>(in0), static_cast<AHardwareBuffer*>(in1), outs,
				VkExtent2D{width, height}, static_cast<VkFormat>(format)),
			ARMSX3_LSFG_ERR_VULKAN)
	}

	return id;
}

extern "C" int armsx3_lsfg_present(int32_t ctx, int in_sem, const int* out_sems, uint32_t out_count)
{
	if (!g_initialized)
	{
		set_error("not initialized");
		return ARMSX3_LSFG_ERR_NOT_INITIALIZED;
	}

	std::vector<int> outs;
	outs.reserve(out_count);

	for (uint32_t i = 0; i < out_count; ++i)
	{
		outs.push_back(out_sems ? out_sems[i] : -1);
	}

	if (g_performance)
	{
		ARMSX3_LSFG_GUARD(LSFG_3_1P::presentContext(ctx, in_sem, outs), ARMSX3_LSFG_ERR_VULKAN)
	}
	else
	{
		ARMSX3_LSFG_GUARD(LSFG_3_1::presentContext(ctx, in_sem, outs), ARMSX3_LSFG_ERR_VULKAN)
	}

	return ARMSX3_LSFG_OK;
}

extern "C" int armsx3_lsfg_destroy_context(int32_t ctx)
{
	if (!g_initialized)
	{
		return ARMSX3_LSFG_OK; // nothing to release
	}

	if (g_performance)
	{
		ARMSX3_LSFG_GUARD(LSFG_3_1P::deleteContext(ctx), ARMSX3_LSFG_ERR_UNKNOWN)
	}
	else
	{
		ARMSX3_LSFG_GUARD(LSFG_3_1::deleteContext(ctx), ARMSX3_LSFG_ERR_UNKNOWN)
	}

	return ARMSX3_LSFG_OK;
}

extern "C" void armsx3_lsfg_wait_idle(void)
{
	if (!g_initialized)
	{
		return;
	}

	try
	{
		if (g_performance) LSFG_3_1P::waitIdle(); else LSFG_3_1::waitIdle();
	}
	catch (...)
	{
		// Deliberately swallowed and not recorded: this is called on the present path, and a
		// failure to wait is reported by whatever uses the images next. Setting the error string
		// here would overwrite a more useful message from the call that actually failed.
	}
}

extern "C" void armsx3_lsfg_finalize(void)
{
	if (!g_initialized)
	{
		return;
	}

	try
	{
		if (g_performance) LSFG_3_1P::finalize(); else LSFG_3_1::finalize();
	}
	catch (...)
	{
	}

	g_initialized = false;
}

#ifdef ARMSX3_LSFG_HAVE_EXTRACT
// Satisfy the one symbol upstream's extract.cpp needs from its config layer.
//
// It reads exactly one field, Config::activeConf.dll, to find the file. Defining the object here
// rather than compiling their config module avoids dragging in toml11 and a config-file format
// that has no meaning inside an APK -- the path comes from the user's file picker instead.
namespace Config { Configuration activeConf; }

namespace
{
	// name -> SPIR-V, translated once at import.
	std::map<std::string, std::vector<uint8_t>> g_shaders;
}

extern "C" int armsx3_lsfg_import_shaders(const char* dll_path)
{
	if (!dll_path || !*dll_path)
	{
		set_error("no file selected");
		return ARMSX3_LSFG_ERR_BAD_ARGUMENT;
	}

	clear_error();
	g_shaders.clear();

	// Upstream's own shader names, both families.
	//
	// Taken verbatim from nameIdxTable in extract.cpp rather than guessed -- a made-up name fails
	// as "Shader hash not found", which reads like a corrupt DLL and is not.
	//
	// Two sets: the plain names are LSFG 3.1 and the p_ prefixed ones are 3.1p. Which family gets
	// used depends on which framegen entry point runs, so both are extracted and whatever the DLL
	// actually contains is kept. Missing names are skipped rather than fatal, because a given
	// Lossless Scaling version legitimately ships only one family.
	static const char* const k_names[] = {
		"mipmaps", "alpha[0]", "alpha[1]", "alpha[2]", "alpha[3]",
		"beta[0]", "beta[1]", "beta[2]", "beta[3]", "beta[4]",
		"gamma[0]", "gamma[1]", "gamma[2]", "gamma[3]", "gamma[4]",
		"delta[0]", "delta[1]", "delta[2]", "delta[3]", "delta[4]",
		"delta[5]", "delta[6]", "delta[7]", "delta[8]", "delta[9]",
		"generate",
		"p_mipmaps", "p_alpha[0]", "p_alpha[1]", "p_alpha[2]", "p_alpha[3]",
		"p_beta[0]", "p_beta[1]", "p_beta[2]", "p_beta[3]", "p_beta[4]",
		"p_gamma[0]", "p_gamma[1]", "p_gamma[2]", "p_gamma[3]", "p_gamma[4]",
		"p_delta[0]", "p_delta[1]", "p_delta[2]", "p_delta[3]", "p_delta[4]",
		"p_delta[5]", "p_delta[6]", "p_delta[7]", "p_delta[8]", "p_delta[9]",
		"p_generate",
	};

	try
	{
		Config::activeConf.dll = dll_path;

		Extract::extractShaders();

		for (const char* name : k_names)
		{
			// getShader hands back DXBC; framegen wants SPIR-V. Translating at import rather than
			// on demand keeps the cost off the present path entirely.
			//
			// Individually guarded: a DLL that ships only one shader family throws on every name
			// in the other, and that is normal rather than a failure of the import.
			try
			{
				auto spirv = Extract::translateShader(Extract::getShader(name));

				if (!spirv.empty())
				{
					g_shaders[name] = std::move(spirv);
				}
			}
			catch (const std::exception&)
			{
				// Not in this DLL. Keep going.
			}
		}

		if (g_shaders.empty())
		{
			set_error("no usable shaders in that file -- is it Lossless.dll from Lossless Scaling?");
			return ARMSX3_LSFG_ERR_SHADERS;
		}
	}
	catch (const std::exception& e)
	{
		g_shaders.clear();
		set_error(e.what());
		return ARMSX3_LSFG_ERR_SHADERS;
	}
	catch (...)
	{
		g_shaders.clear();
		set_error("unknown failure reading the file");
		return ARMSX3_LSFG_ERR_SHADERS;
	}

	return static_cast<int>(g_shaders.size());
}

extern "C" int armsx3_lsfg_shader_count(void)
{
	return static_cast<int>(g_shaders.size());
}

extern "C" int armsx3_lsfg_get_shader(const char* name, const uint8_t** out_data, uint32_t* out_size)
{
	if (!name || !out_data || !out_size)
	{
		return ARMSX3_LSFG_ERR_BAD_ARGUMENT;
	}

	const auto it = g_shaders.find(name);

	if (it == g_shaders.end() || it->second.empty())
	{
		return ARMSX3_LSFG_ERR_SHADERS;
	}

	*out_data = it->second.data();
	*out_size = static_cast<uint32_t>(it->second.size());
	return ARMSX3_LSFG_OK;
}
#else
extern "C" int armsx3_lsfg_import_shaders(const char*)
{
	set_error("this build has no shader extraction support");
	return ARMSX3_LSFG_ERR_SHADERS;
}

extern "C" int armsx3_lsfg_shader_count(void) { return 0; }

extern "C" int armsx3_lsfg_get_shader(const char*, const uint8_t**, uint32_t*)
{
	return ARMSX3_LSFG_ERR_SHADERS;
}
#endif
