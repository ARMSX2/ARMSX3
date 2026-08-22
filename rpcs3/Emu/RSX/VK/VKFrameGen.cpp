#include "stdafx.h"
#include "VKFrameGen.h"

#include "Emu/system_config.h"
#include "vkutils/device.h"
#include "vkutils/image.h"
#include "vkutils/commands.h"
#include "vkutils/barriers.h"
#include "Emu/Cell/timers.hpp"

#ifdef __ANDROID__
#include "3rdparty/lsfg/armsx3_lsfg_shim.h"
#include <dlfcn.h>
#include <map>
#include <mutex>
#include "FrameGen/FrameGen.h"
#include "FrameGen/LsfgVkCompat.h"
#include "FrameGen/LosslessDll.h"
#include <vk_mem_alloc.h>
#endif

LOG_CHANNEL(framegen_log, "FRAMEGEN");

namespace vk::frame_gen
{
#ifdef __ANDROID__
	namespace
	{
		struct lsfg_api
		{
			void* handle = nullptr;

			uint32_t (*abi_version)() = nullptr;
			int (*initialize)(uint64_t, int, float, uint64_t, int, armsx3_lsfg_shader_loader, void*) = nullptr;
			int32_t (*create_context_ahb)(void*, void*, void* const*, uint32_t, uint32_t, uint32_t, int32_t) = nullptr;
			int (*present)(int32_t, int, const int*, uint32_t) = nullptr;
			int (*destroy_context)(int32_t) = nullptr;
			void (*wait_idle)() = nullptr;
			void (*finalize)() = nullptr;
			const char* (*last_error)() = nullptr;
			int (*import_shaders)(const char*) = nullptr;
			int (*shader_count)() = nullptr;
			int (*get_shader)(const char*, const uint8_t**, uint32_t*) = nullptr;

			bool ok = false;
			std::string failure;
		};

		lsfg_api g_api;
		std::once_flag g_load_once;
		bool g_initialized = false;

		// The imported shaders, kept on THIS side of the dlopen boundary.
		//
		// They used to live only in the library's own map, which meant they lasted exactly as long
		// as the process: the user picked Lossless.dll, the shim extracted 52 shaders, the copied
		// DLL was deleted, and the next launch had nothing. generate() then returned 0 before doing
		// any work, silently, so frame generation simply never ran again and there was nothing in
		// the log to say why. Re-importing on every boot is not a reasonable thing to ask.
		//
		// Holding them here rather than adding a "put this shader back" entry point to the shim
		// keeps the C ABI as it is, and this is where they have to be serialised from anyway.
		std::map<std::string, std::vector<u8>> g_shaders;
		bool g_shaders_loaded = false;

		// The reason the last import failed, phrased for the user rather than the log. This used
		// to come from the dlopen'd library's last_error(); the ported extractor has no such
		// channel, so it is kept here.
		std::string g_import_error;   // the cache file has been looked for (found or not)

		// Every shader name the shim extracts, in its order.
		//
		// Duplicated from armsx3_lsfg_shim.cpp deliberately: the shim's C ABI can look a shader up
		// by name but cannot enumerate, and adding an enumeration entry point to serialise a map we
		// already have to copy across the boundary would be the longer way round. Both families are
		// listed because a given Lossless Scaling version ships only one, and which one is present
		// is not knowable ahead of time.
		constexpr const char* k_shader_names[] = {
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

		// Translated SPIR-V only -- never the DLL, which is the user's own property and is deleted
		// straight after extraction.
		std::string shader_cache_path()
		{
			return fs::get_cache_dir() + "framegen_shaders.bin";
		}

		constexpr u32 k_cache_magic = 0x33474641; // "AFG3"
		constexpr u32 k_cache_version = 1;

		void save_shader_cache()
		{
			fs::file out(shader_cache_path(), fs::rewrite);

			if (!out)
			{
				framegen_log.warning("Could not write the shader cache; shaders will need importing again next launch");
				return;
			}

			const u32 count = ::size32(g_shaders);

			out.write(k_cache_magic);
			out.write(k_cache_version);
			out.write(count);

			for (const auto& [name, spirv] : g_shaders)
			{
				const u32 name_len = ::size32(name);
				const u32 size = ::size32(spirv);

				out.write(name_len);
				out.write(name.data(), name.size());
				out.write(size);
				out.write(spirv.data(), spirv.size());
			}

			framegen_log.notice("Cached %u shaders to %s", g_shaders.size(), shader_cache_path());
		}

		// Returns how many were restored. Zero is the ordinary "user has not imported anything yet"
		// case and is not an error.
		u32 load_shader_cache()
		{
			if (g_shaders_loaded)
			{
				return ::size32(g_shaders);
			}

			g_shaders_loaded = true;

			fs::file in(shader_cache_path());

			if (!in)
			{
				return 0;
			}

			u32 magic = 0, version = 0, count = 0;

			if (!in.read(magic) || !in.read(version) || !in.read(count) ||
				magic != k_cache_magic || version != k_cache_version)
			{
				framegen_log.warning("Shader cache is not one this build understands; ignoring it");
				return 0;
			}

			for (u32 i = 0; i < count; ++i)
			{
				u32 name_len = 0, size = 0;

				if (!in.read(name_len) || name_len == 0 || name_len > 64)
				{
					break;
				}

				std::string name(name_len, '\0');

				if (in.read(name.data(), name_len) != name_len || !in.read(size) || size == 0 || size > 4u * 1024 * 1024)
				{
					break;
				}

				std::vector<u8> spirv(size);

				if (in.read(spirv.data(), size) != size)
				{
					break;
				}

				g_shaders[std::move(name)] = std::move(spirv);
			}

			if (g_shaders.empty())
			{
				framegen_log.warning("Shader cache was unreadable; import Lossless.dll again");
			}
			else
			{
				framegen_log.notice("Restored %u shaders from the cache", g_shaders.size());
			}

			return ::size32(g_shaders);
		}

		template <typename T>
		bool resolve(void* handle, const char* name, T& out)
		{
			out = reinterpret_cast<T>(dlsym(handle, name));

			if (!out)
			{
				framegen_log.error("libarmsx3_lsfg.so is missing '%s' (%s)", name, dlerror());
				return false;
			}

			return true;
		}

		void load_library()
		{
			// No path: the linker searches the APK's nativeLibraryDir, which is where the build
			// packages it. Naming an absolute path would break as soon as the app is installed
			// somewhere unexpected (split APKs, app cloning, some OEM launchers).
			g_api.handle = dlopen("libarmsx3_lsfg.so", RTLD_NOW | RTLD_LOCAL);

			if (!g_api.handle)
			{
				// Absent library is the normal case for a build that did not package it, so this
				// is not an error -- frame generation is simply not on offer.
				g_api.failure = "frame generation library not present in this build";
				framegen_log.notice("libarmsx3_lsfg.so not loaded: %s", dlerror());
				return;
			}

			// RTLD_LOCAL above matters as much as the separate .so does: RTLD_GLOBAL would put
			// framegen's volk symbols into the global lookup scope, where they could satisfy our
			// renderer's vk* references. The isolation is only complete with both.
			if (!resolve(g_api.handle, "armsx3_lsfg_abi_version", g_api.abi_version))
			{
				g_api.failure = "frame generation library is malformed";
				return;
			}

			if (const uint32_t v = g_api.abi_version(); v != ARMSX3_LSFG_ABI_VERSION)
			{
				// Refuse rather than adapt. A mismatched library means structs of different
				// shapes on either side of a dlopen boundary, which fails in ways that look like
				// driver bugs.
				g_api.failure = fmt::format("frame generation library is version %u, this build expects %u",
					v, ARMSX3_LSFG_ABI_VERSION);
				framegen_log.error("%s", g_api.failure);
				return;
			}

			const bool all =
				resolve(g_api.handle, "armsx3_lsfg_initialize", g_api.initialize) &&
				resolve(g_api.handle, "armsx3_lsfg_create_context_ahb", g_api.create_context_ahb) &&
				resolve(g_api.handle, "armsx3_lsfg_present", g_api.present) &&
				resolve(g_api.handle, "armsx3_lsfg_destroy_context", g_api.destroy_context) &&
				resolve(g_api.handle, "armsx3_lsfg_wait_idle", g_api.wait_idle) &&
				resolve(g_api.handle, "armsx3_lsfg_finalize", g_api.finalize) &&
				resolve(g_api.handle, "armsx3_lsfg_last_error", g_api.last_error) &&
				resolve(g_api.handle, "armsx3_lsfg_import_shaders", g_api.import_shaders) &&
				resolve(g_api.handle, "armsx3_lsfg_shader_count", g_api.shader_count) &&
				resolve(g_api.handle, "armsx3_lsfg_get_shader", g_api.get_shader);

			if (!all)
			{
				g_api.failure = "frame generation library is missing entry points";
				return;
			}

			g_api.ok = true;
			framegen_log.success("Frame generation library loaded (ABI %u)", ARMSX3_LSFG_ABI_VERSION);
		}

		// Trampoline from the C ABI back to the caller's shader provider.
		//
		// The buffer handed to framegen has to outlive the call, so it is parked in a thread_local
		// that the next request overwrites. framegen copies what it is given before asking for the
		// next shader, so one slot is enough -- and it avoids handing out a pointer into a
		// temporary, which is the obvious way to get this wrong.
		struct shader_bridge
		{
			std::vector<u8> (*fn)(const std::string&, void*) = nullptr;
			void* user = nullptr;
		};

		thread_local std::vector<u8> t_shader_scratch;

		// Static, NOT a local in initialize().
		//
		// framegen copies the std::function it is handed into ShaderPool::source and keeps it for
		// the lifetime of its VkDevice -- it does not resolve the shaders during initialize(), it
		// resolves them lazily while BUILDING THE CONTEXT. So the `user` pointer travelling with
		// that callback has to outlive initialize() too.
		//
		// It used to be a stack local. initialize() returned, the frame died, and the first shader
		// request during createContextFromAHB read fn out of dead stack and called it: "Segfault
		// executing location 000000725200d590" on the RSX thread, ~750us after the successful
		// "Frame generation initialized" line. A mapped, non-executable address is what a stale
		// stack pointer looks like when you call through it.
		shader_bridge g_shader_bridge;

		int shader_trampoline(const char* name, const uint8_t** out_data, uint32_t* out_size, void* user)
		{
			auto* bridge = static_cast<shader_bridge*>(user);

			if (!bridge || !bridge->fn || !name || !out_data || !out_size)
			{
				return ARMSX3_LSFG_ERR_BAD_ARGUMENT;
			}

			t_shader_scratch = bridge->fn(name, bridge->user);

			if (t_shader_scratch.empty())
			{
				framegen_log.error("No shader available for '%s'", name);
				return ARMSX3_LSFG_ERR_SHADERS;
			}

			*out_data = t_shader_scratch.data();
			*out_size = static_cast<uint32_t>(t_shader_scratch.size());
			return ARMSX3_LSFG_OK;
		}
	}

	bool available()
	{
		std::call_once(g_load_once, load_library);
		return g_api.ok;
	}

	std::string unavailable_reason()
	{
		std::call_once(g_load_once, load_library);
		return g_api.ok ? std::string{} : g_api.failure;
	}

	bool initialize(u64 device_uuid, bool is_hdr, f32 flow_scale, u32 generated_frames,
		bool performance, std::vector<u8> (*shader_for)(const std::string&, void*), void* user)
	{
		if (!available() || !shader_for)
		{
			return false;
		}

		if (g_initialized)
		{
			return true;
		}

		g_shader_bridge = shader_bridge{shader_for, user};

		const int rc = g_api.initialize(device_uuid, is_hdr ? 1 : 0, flow_scale, generated_frames,
			performance ? 1 : 0, &shader_trampoline, &g_shader_bridge);

		if (rc != ARMSX3_LSFG_OK)
		{
			framegen_log.error("Frame generation failed to initialize (%d): %s", rc, g_api.last_error());
			return false;
		}

		g_initialized = true;
		framegen_log.success("Frame generation initialized (%u generated frame(s), %s shaders, flow scale %.2f)",
			generated_frames, performance ? "3.1p performance" : "3.1 quality", flow_scale);
		return true;
	}

	bool initialized()
	{
		return g_initialized;
	}

	void shutdown()
	{
		if (!g_initialized)
		{
			return;
		}

		g_api.finalize();
		g_initialized = false;
		framegen_log.notice("Frame generation shut down");
	}

	const char* last_error()
	{
		// The import error first: it is the one a user can act on, and the library's own channel
		// only ever described failures inside the path that no longer runs.
		if (!g_import_error.empty())
		{
			return g_import_error.c_str();
		}

		return g_api.ok && g_api.last_error ? g_api.last_error() : "";
	}

	int import_shaders(const std::string& dll_path)
	{
		if (!available())
		{
			return -1;
		}

		// Drives the ported extractor, not the old library.
		//
		// This used to call into libarmsx3_lsfg.so, which kept its own copy of the shaders. The
		// passes read LosslessDll's cache instead, so importing through the library would have
		// looked like it worked -- a count comes back, the settings screen says so -- and then
		// frame generation would have refused to start because the cache the passes actually
		// read was still empty.
		//
		// The path is stored first: BuildShaderCache reads it back through GetLosslessDllPath,
		// and keeping it means the cache can be rebuilt later without asking the user again.
		g_cfg.video.frame_generation_dll_path.from_string(dll_path);

		// Built with the flags the passes will read with, taken from the device that will run
		// them. Without a live device -- importing from the settings screen before a game has
		// started -- fp16 is assumed, because RPCS3 only reports it where it also enabled it and
		// clears it on the Adreno drivers that cannot compile it, so it is the common case. A
		// device that disagrees rebuilds the cache on first use.
		const bool allow_fp16 = VideoCore::FrameGen::Float16Allowed();

		const VideoCore::FrameGen::LosslessStatus rc =
			VideoCore::FrameGen::BuildShaderCache(allow_fp16, allow_fp16);

		if (rc != VideoCore::FrameGen::LosslessStatus::Ok)
		{
			// Meant for the user, not the log: they chose this file and can act on the answer.
			const char* why = "could not be read";

			switch (rc)
			{
			case VideoCore::FrameGen::LosslessStatus::NotInstalled:
				why = "was not found"; break;
			case VideoCore::FrameGen::LosslessStatus::NotPortableExecutable:
				why = "is not a Lossless Scaling executable"; break;
			case VideoCore::FrameGen::LosslessStatus::MissingShaders:
				why = "does not contain the interpolation shaders"; break;
			case VideoCore::FrameGen::LosslessStatus::TranslationFailed:
				why = "has shaders this device cannot use"; break;
			case VideoCore::FrameGen::LosslessStatus::CacheUnusable:
				why = "was read, but its shaders could not be cached"; break;
			default:
				break;
			}

			g_import_error = fmt::format("Lossless Scaling %s", why);
			framegen_log.error("Shader import failed: %s", g_import_error);
			return -1;
		}

		g_shaders_loaded = true;
		g_import_error.clear();
		framegen_log.success("Interpolation shaders imported from %s", dll_path);
		return 1;
	}

	int shader_count()
	{
		if (!available())
		{
			return 0;
		}

		// Asks whether the CACHE is usable, not whether Lossless.dll is still sitting there.
		//
		// Two wrong answers were possible here and both were given. First this counted
		// g_shaders, which import_shaders stopped filling when it moved to the ported extractor,
		// so generated_frame_count() returned 0 and frame generation sat on "starting" with
		// nothing logged. Then it asked GetInstalledLosslessStatus(), which validates the DLL
		// PATH -- and the UI copies the picked file into app cache, which Android is free to
		// purge. Re-importing wrote a perfectly good 322KB shader cache and this still answered
		// "no shaders", because it was asking about a file that no longer had to exist.
		//
		// The shaders are extracted once and cached; the source is not needed again. So the
		// question is whether the cache loads.
		VideoCore::FrameGen::ShaderModules probe;

		// The SAME variant the passes load, or the cache is rejected on its flags and a freshly
		// imported one reads as "no shaders". Three callers ask this question -- the cache
		// writer, LsfgShaders, and this probe -- and any two disagreeing breaks the cache.
		const bool fp16 = VideoCore::FrameGen::Float16Allowed();

		if (VideoCore::FrameGen::LoadShaderModules(probe, fp16, fp16) !=
			VideoCore::FrameGen::LosslessStatus::Ok || probe.empty())
		{
			return 0;
		}

		return ::narrow<int>(probe.size());
	}
#else
	// Frame generation is Android-only: framegen's other path shares images by file descriptor,
	// and the desktop targets this project builds for have no reason to carry it.
	bool available() { return false; }
	std::string unavailable_reason() { return "frame generation is only available on Android"; }

	bool initialize(u64, bool, f32, u32, bool, std::vector<u8> (*)(const std::string&, void*), void*)
	{
		return false;
	}

	bool initialized() { return false; }
	void shutdown() {}
	const char* last_error() { return ""; }
	int import_shaders(const std::string&) { return -1; }
	int shader_count() { return 0; }
#endif

#ifdef __ANDROID__
	shared_image::~shared_image()
	{
		destroy();
	}

	bool shared_image::create(const vk::render_device& dev, u32 width, u32 height, VkFormat format)
	{
		destroy();

		// A PLAIN device-local image now, not AHardwareBuffer-backed.
		//
		// The AHardwareBuffer existed only because frame generation used to run on its own
		// VkDevice behind a dlopen'd library, and an AHB was the one currency both devices
		// understood. The passes run on OUR device now, so a VkImage is directly meaningful to
		// them and the allocate/import/import-again round-trip is gone -- along with the
		// requirement for external-memory AHB support, which was the narrowest gate on the
		// feature.
		//
		// STORAGE is here because the interpolation passes write through an image view rather
		// than blitting; SAMPLED because they also read the captured frames.
		VkImageCreateInfo ci = {};
		ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		ci.imageType = VK_IMAGE_TYPE_2D;
		ci.format = format;
		ci.extent = { width, height, 1 };
		ci.mipLevels = 1;
		ci.arrayLayers = 1;
		ci.samples = VK_SAMPLE_COUNT_1_BIT;
		ci.tiling = VK_IMAGE_TILING_OPTIMAL;
		ci.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
		ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
		ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

		if (vkCreateImage(dev, &ci, nullptr, &m_image) != VK_SUCCESS)
		{
			framegen_log.error("vkCreateImage failed for %ux%u", width, height);
			m_image = VK_NULL_HANDLE;
			return false;
		}

		VkMemoryRequirements req = {};
		vkGetImageMemoryRequirements(dev, m_image, &req);

		VkPhysicalDeviceMemoryProperties mem_props = {};
		vkGetPhysicalDeviceMemoryProperties(dev.gpu(), &mem_props);

		u32 type_index = UINT32_MAX;

		for (u32 i = 0; i < mem_props.memoryTypeCount; i++)
		{
			if ((req.memoryTypeBits & (1u << i)) &&
				(mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
			{
				type_index = i;
				break;
			}
		}

		if (type_index == UINT32_MAX)
		{
			framegen_log.error("No device-local memory type for the capture image");
			destroy();
			return false;
		}

		VkMemoryAllocateInfo ai = {};
		ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		ai.allocationSize = req.size;
		ai.memoryTypeIndex = type_index;

		if (vkAllocateMemory(dev, &ai, nullptr, &m_memory) != VK_SUCCESS ||
			vkBindImageMemory(dev, m_image, m_memory, 0) != VK_SUCCESS)
		{
			framegen_log.error("Could not back the capture image at %ux%u", width, height);
			destroy();
			return false;
		}

		VkImageViewCreateInfo vi = {};
		vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		vi.image = m_image;
		vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
		vi.format = format;
		vi.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

		if (vkCreateImageView(dev, &vi, nullptr, &m_view) != VK_SUCCESS)
		{
			framegen_log.error("vkCreateImageView failed for the capture image");
			destroy();
			return false;
		}

		m_width = width;
		m_height = height;
		m_device = dev;
		m_layout = VK_IMAGE_LAYOUT_UNDEFINED;
		layout = VK_IMAGE_LAYOUT_UNDEFINED;
		return true;
	}

	void shared_image::destroy()
	{
		if (m_view != VK_NULL_HANDLE)
		{
			vkDestroyImageView(m_device, m_view, nullptr);
			m_view = VK_NULL_HANDLE;
		}

		if (m_image != VK_NULL_HANDLE)
		{
			vkDestroyImage(m_device, m_image, nullptr);
			m_image = VK_NULL_HANDLE;
		}

		if (m_memory != VK_NULL_HANDLE)
		{
			vkFreeMemory(m_device, m_memory, nullptr);
			m_memory = VK_NULL_HANDLE;
		}

		m_device = VK_NULL_HANDLE;
		m_width = m_height = 0;
		m_format = VK_FORMAT_UNDEFINED;
		m_layout = VK_IMAGE_LAYOUT_UNDEFINED;
		layout = VK_IMAGE_LAYOUT_UNDEFINED;
	}
#else
	shared_image::~shared_image() = default;
	bool shared_image::create(const vk::render_device&, u32, u32, VkFormat) { return false; }
	void shared_image::destroy() {}
#endif


#ifdef __ANDROID__
	namespace
	{
		// Two inputs and the outputs framegen writes.
		//
		// Two, not more, and this is not a tuning choice. framegen binds exactly two input
		// AHardwareBuffers into a context's descriptor sets when the context is built and keeps
		// them for its lifetime, alternating which of the two counts as "the new frame" off an
		// internal frame counter (framegen/v3.1_src/shaders/mipmaps.cpp picks descriptor set
		// frameCount % 2). A third buffer has nowhere to go: it cannot be handed to an existing
		// context, and a second context is not an answer either because a context carries three
		// frames of pyramid history across presents, so rotating between contexts would feed each
		// one a history with gaps in it.

		// A capture becomes readable by framegen when it is SUBMITTED, not when it is recorded.
		//
		// These two are deliberately separate. capture_presented_frame() runs from flip() and only
		// writes commands into the frame's command buffer; framegen lives on a second VkDevice with
		// no semaphore joining it to ours, so until that buffer is submitted and retired the shared
		// image still holds the previous frame. Generating off a recorded-but-unsubmitted capture is
		// how the game image ended up flickering between real frames and half-written ones.
		std::atomic<bool> g_recorded_capture{false};
		u32 g_recorded_slot = 0;

		// Set by commit_capture(), cleared by the generate() that consumes it.
		//
		// Generation must never run on a frame the game did not draw. The capture site is gated on
		// image_to_flip, but generate() is called unconditionally from queue_swap_request, so
		// without this a frame with nothing to capture would still interpolate -- from the two
		// previous captures, producing a stale image presented between two identical real ones.
		std::atomic<bool> g_fresh_capture{false};

		// Slot holding the newest capture that has actually reached the GPU, and the slot the next
		// capture will be recorded into. They are not the same the moment a capture is recorded.
		u32 g_committed_slot = 0;
		u32 g_shared_slot = 0;

		// Real frames committed since the last resize, saturating at 2. Interpolation needs a
		// pair, so the first frame after a resize has nothing to pair with.
		u32 g_captures = 0;

		// What the pacer asked for this frame, decided at Process time so its clock stays regular.
		u32 g_planned_generations = 0;

		// Consumed by generate(), so one plan produces one set of generated frames.
		//
		// NOT g_recorded_capture: commit_capture() exchanges that one to false, and it runs
		// before generate() on the pipelined path -- so generate() saw a flag that had already
		// been eaten and returned 0 every frame, which reads as frame generation stuck on
		// "starting" with no error anywhere.
		std::atomic<bool> g_plan_ready{false};

		// The frame Process() reads, remembered at present time and used from framegen's own
		// command buffer afterwards -- the same image ARMSX2 hands its passes.
		VkImage g_source_image = VK_NULL_HANDLE;
		VkImageLayout g_source_layout = VK_IMAGE_LAYOUT_UNDEFINED;

		// Last computed display rate, for the on-screen overlay. Written once a second by
		// generate() and read by the RSX thread; a torn read of a float used for display
		// costs nothing worth a lock.
		atomic_t<f32> g_display_fps{0.f};
		u32 g_shared_w = 0;
		u32 g_shared_h = 0;

		// Rolling average of the copy cost, reported once a second.
		u64 g_capture_ns = 0;
		u32 g_capture_count = 0;
		u64 g_last_report = 0;

		// Defined further down, with the context it tears down.
		//
		// Releasing the shared images has to take the context with it. framegen imported those
		// AHardwareBuffers into images of its own and the shim is explicit that the caller keeps
		// ownership and must keep them alive for the context's lifetime, so freeing them while the
		// context still exists leaves framegen reading storage the allocator has taken back. Only
		// the resize path does this, which is why it went unnoticed: it needs a resolution change
		// with frame generation already running.
		void release_generation_context();

		// The native pass stack. This replaces the dlopen'd library and its second VkDevice:
		// everything below lives on OUR device, so the images the passes read and write are the
		// same objects the renderer blits into.
		struct native_stack
		{
			VmaAllocator vma = VK_NULL_HANDLE;
			std::unique_ptr<Vulkan::MemoryAllocator> allocator;
			std::unique_ptr<Vulkan::Device> device;
			std::unique_ptr<Vulkan::FrameGen> framegen;

			// Frame generation records into its OWN command buffer, exactly as the ARMSX2
			// driver does, rather than borrowing the renderer's: the passes have to run after
			// the captured frame is complete and before the generated images are blitted, and
			// that is a submission of its own.
			// One command buffer and fence PER SLOT, rotated per frame.
			//
			// A single pair meant every frame waited for the immediately previous interpolation
			// to finish before it could reset the buffer -- the GPU pipeline drained once per
			// frame and the CPU could never get ahead. ARMSX2 keeps one slot per swapchain image,
			// which is the depth at which the presentation engine may already be holding work,
			// and waits on a fence that is that many frames old and therefore almost always
			// already signalled.
			static constexpr u32 SLOTS = 3;

			VkCommandPool pool = VK_NULL_HANDLE;
			VkCommandBuffer cmd[SLOTS] = {};
			VkFence fence[SLOTS] = {};
			bool slot_submitted[SLOTS] = {};

			// Timestamps around the passes. This is the number every performance theory tonight
			// was a substitute for: what the interpolation actually costs the GPU per frame.
			VkQueryPool query_pool = VK_NULL_HANDLE;
			f32 timestamp_period = 0.f;
			u64 frame_index = 0;
			VkQueue queue = VK_NULL_HANDLE;

			u32 slot() const { return static_cast<u32>(frame_index % SLOTS); }

			bool valid() const { return framegen != nullptr; }
		};

		native_stack g_native;

		bool build_native_stack(const vk::render_device& dev, u32 want);
		void destroy_native_stack();
	}

	void release_shared_images()
	{
		release_generation_context();

		g_shared_w = g_shared_h = 0;
		g_shared_slot = 0;
		g_committed_slot = 0;
		g_recorded_slot = 0;

		// Both flags too: they name captures living in images that no longer exist, and a stale
		// "there is a fresh capture" survives a resize and interpolates the new size from the old.
		g_recorded_capture.store(false);
		g_fresh_capture.store(false);
		g_captures = 0;
	}

	void commit_capture()
	{
		if (!g_recorded_capture.exchange(false))
		{
			return;
		}

		g_committed_slot = g_recorded_slot;

		if (g_captures < 2)
		{
			g_captures++;
		}

		g_fresh_capture.store(true);
	}

#else
	bool capture_presented_frame(const vk::command_buffer&, const vk::render_device&, VkImage, VkImageLayout, u32, u32)
	{
		return false;
	}

	void release_shared_images() {}
	void commit_capture() {}
#endif


#ifdef __ANDROID__
	namespace
	{
		// Outputs framegen writes into, and the context tying them to the inputs.
		//
		// One set, for the same reason there are only two inputs: framegen binds each output AHB
		// into the generate pass's descriptor sets when the context is built
		// (framegen/v3.1_src/shaders/generate.cpp binds outImgs.at(i) into BOTH parity descriptor
		// sets), so a second set could not be reached without rebuilding the context and throwing
		// away the temporal history that makes the result stable. The read-vs-write hazard on these
		// is handled on the renderer's side instead, by waiting for the blit that consumed them
		// before the next generation is started -- a wait on work a whole frame old, which is where
		// it costs least.
		shared_image g_shared_out[3];   // x4 is the most the setting offers, so three generated
		int32_t g_context = -1;
		u32 g_context_outputs = 0;
		bool g_disabled = false;        // a failure is permanent for the session

		u32 wanted_generated_frames()
		{
			switch (g_cfg.video.frame_generation)
			{
			case frame_generation_mode::x2: return 1;
			case frame_generation_mode::x3: return 2;
			case frame_generation_mode::x4: return 3;
			default: return 0;
			}
		}

		// Serve a shader framegen asks for by name.
		//
		// Our own copy first: after a restart it is the only copy there is, because the library's
		// map is populated by an import that happened in a previous process. The library is still
		// asked as a fallback for the case where an import just succeeded but the cache write did
		// not.
		std::vector<u8> shader_from_library(const std::string& name, void*)
		{
			load_shader_cache();

			if (const auto it = g_shaders.find(name); it != g_shaders.end() && !it->second.empty())
			{
				return it->second;
			}

			const uint8_t* data = nullptr;
			uint32_t size = 0;

			if (!g_api.get_shader || g_api.get_shader(name.c_str(), &data, &size) != ARMSX3_LSFG_OK)
			{
				return {};
			}

			return std::vector<u8>(data, data + size);
		}

		void disable(const char* why)
		{
			g_disabled = true;
			framegen_log.error("Frame generation disabled for this session: %s", why);
		}

		void release_generation_context()
		{
			if (g_context >= 0)
			{
				// destroy_context waits for framegen's device to go idle before it drops anything,
				// so the output images below are not still being written when they are freed.
				g_api.destroy_context(g_context);
				g_context = -1;
			}

			for (auto& out : g_shared_out)
			{
				out.destroy();
			}

			g_context_outputs = 0;
		}

	}

	bool capture_presented_frame(const vk::command_buffer& cmd, const vk::render_device& dev,
		VkImage src, VkImageLayout src_layout, u32 width, u32 height)
	{
		if (g_cfg.video.frame_generation == frame_generation_mode::off || !src || !width || !height)
		{
			return false;
		}

		if (g_disabled)
		{
			return false;
		}

		static bool s_reported = false;
		const bool memory_model = dev.get_vulkan_memory_model_support();
		const bool null_descriptor = dev.get_null_descriptor_support();

		if (!memory_model || !null_descriptor)
		{
			if (!s_reported)
			{
				s_reported = true;
				framegen_log.error("Frame generation is not supported here -- vulkanMemoryModel: %s,"
					" nullDescriptor: %s", memory_model ? "yes" : "NO", null_descriptor ? "yes" : "NO");
			}

			return false;
		}

		const u32 want = wanted_generated_frames();

		if (!want || shader_count() <= 0)
		{
			return false;
		}

		// Output images, sized to the frame. These are what the passes write and what the present
		// path blits from; there is no longer an input copy beside them.
		if (g_shared_w != width || g_shared_h != height || !g_shared_out[0].valid())
		{
			release_generation_context();

			for (u32 i = 0; i < want; ++i)
			{
				if (!g_shared_out[i].create(dev, width, height, VK_FORMAT_R8G8B8A8_UNORM))
				{
					release_generation_context();
					disable("could not allocate the generated frame images");
					return false;
				}
			}

			g_shared_w = width;
			g_shared_h = height;
			g_context_outputs = want;
			framegen_log.success("Frame generation images ready at %ux%u, %u generated frame(s)",
				width, height, want);
		}

		if (!g_native.valid() && !build_native_stack(dev, want))
		{
			disable("could not build the frame generation passes");
			return false;
		}

		// Nothing is recorded into the frame's command buffer here.
		//
		// Process() WAS recorded here, to avoid the capture copy. That removed the copy and put
		// the interpolation's GPU cost inside the frame's own submission instead -- which the
		// present path waits on -- so it came straight off the real frame rate: 60fps content
		// measured 24-28 with generation on. ARMSX2 runs Process in framegen's OWN command
		// buffer, after the frame, against the same image, which costs neither the copy nor the
		// frame. This does that: remember the image, do the work in generate().
		g_source_image = src;
		g_source_layout = src_layout;

		const u64 started = get_system_time();

		// The pacer is NOT planned here any more.
		//
		// It was, to keep its clock regular. But the plan is derived from GeneratedFrameCount(),
		// which Process() sets -- and Process now runs in generate(). Planning before it meant
		// the count was always zero on the first frame, generate() was gated on the plan, and so
		// Process never ran to produce a count: frame generation sat on "starting" forever.
		//
		// ARMSX2 plans immediately AFTER Process, in the same place, once per frame. generate()
		// is reached every frame while the feature is on, so the clock stays just as regular.

		g_recorded_capture.store(true);
		g_plan_ready.store(true);
		g_capture_ns += (get_system_time() - started) * 1000;
		g_capture_count++;

		if (const u64 now = get_system_time(); now - g_last_report > 1'000'000)
		{
			if (g_capture_count)
			{
				framegen_log.notice("Frame process: %.3f ms/frame CPU over %u frames (%ux%u), %u generated",
					(g_capture_ns / 1'000'000.0) / g_capture_count, g_capture_count, width, height,
					g_planned_generations);
			}

			g_capture_ns = 0;
			g_capture_count = 0;
			g_last_report = now;
		}

		return true;
	}

	u32 generated_frame_count()
	{
		if (g_disabled || !available())
		{
			return 0;
		}

		return shader_count() > 0 ? wanted_generated_frames() : 0;
	}

	VkImage generated_image(u32 index)
	{
		return index < g_context_outputs ? g_shared_out[index].handle() : VK_NULL_HANDLE;
	}

	f32 display_fps()
	{
		return g_display_fps.load();
	}

	std::string status_text()
	{
		// Nothing at all when the user has not asked for frame generation. Every other case says
		// something, because the alternative is what shipped first: the setting was on, no line
		// appeared, and there was no way to tell whether it was working, broken, or unsupported.
		if (!wanted_generated_frames())
		{
			return {};
		}

		if (!available())
		{
			return "LSFG: unavailable";
		}

		if (g_disabled)
		{
			return "LSFG: failed";
		}

		if (shader_count() <= 0)
		{
			return "LSFG: no shaders";
		}

		if (const f32 fps = g_display_fps.load(); fps > 0.f)
		{
			return fmt::format("LSFG: %05.2f", fps);
		}

		return "LSFG: starting";
	}

	namespace
	{
		void destroy_native_stack()
		{
			if (g_native.framegen)
			{
				g_native.framegen.reset();
			}

			g_native.device.reset();
			g_native.allocator.reset();

			for (u32 i = 0; i < native_stack::SLOTS; ++i)
			{
				if (g_native.fence[i] != VK_NULL_HANDLE && vk::g_render_device)
				{
					vkDestroyFence(*vk::g_render_device, g_native.fence[i], nullptr);
					g_native.fence[i] = VK_NULL_HANDLE;
				}

				g_native.slot_submitted[i] = false;
			}

			g_native.frame_index = 0;

			if (g_native.pool != VK_NULL_HANDLE && vk::g_render_device)
			{
				// Frees g_native.cmd with it.
				vkDestroyCommandPool(*vk::g_render_device, g_native.pool, nullptr);
				g_native.pool = VK_NULL_HANDLE;

				for (auto& c : g_native.cmd)
				{
					c = VK_NULL_HANDLE;
				}
			}

			if (g_native.query_pool != VK_NULL_HANDLE && vk::g_render_device)
			{
				vkDestroyQueryPool(*vk::g_render_device, g_native.query_pool, nullptr);
				g_native.query_pool = VK_NULL_HANDLE;
			}

			if (g_native.vma != VK_NULL_HANDLE)
			{
				vmaDestroyAllocator(g_native.vma);
				g_native.vma = VK_NULL_HANDLE;
			}

			g_native.queue = VK_NULL_HANDLE;

		}

		bool build_native_stack(const vk::render_device& dev, u32 want)
		{
			(void) want;
			destroy_native_stack();

			// Its OWN VmaAllocator, on the same VkDevice.
			//
			// RPCS3 keeps its allocator private and can be built on either VMA or plain Vulkan
			// allocations, so there is no handle to borrow. VMA supports several allocators per
			// device and frame generation allocates a handful of images, so giving it one of its
			// own is cheaper than widening the renderer's abstraction to expose something that
			// might not exist.
			// Every field, for the reason memory.cpp documents at its own vmaCreateAllocator:
			// Android builds with VK_NO_PROTOTYPES, so VMA takes its dynamic path and resolves
			// the whole table through the two getters. It needs the INSTANCE to do that --
			// vkGetInstanceProcAddr(nullptr, ...) answers only for global functions -- and with
			// a null instance it stores nulls and then calls one from inside VmaAllocator_T's
			// constructor. VMA_ASSERT compiles out in release, so the first symptom is a jump
			// to address zero.
			//
			// This crashed exactly that way the first time frame generation was switched on:
			// SIGSEGV at pc 0, inside vmaCreateAllocator, called from generate().
			VmaAllocatorCreateInfo aci = {};
			aci.physicalDevice = dev.gpu();
			aci.device = dev;
			aci.instance = static_cast<VkInstance>(dev.gpu());
			aci.vulkanApiVersion = VK_API_VERSION_1_2;

			VmaVulkanFunctions fns = {};
			fns.vkGetInstanceProcAddr = ::vkGetInstanceProcAddr;
			fns.vkGetDeviceProcAddr = ::vkGetDeviceProcAddr;

			if (!fns.vkGetInstanceProcAddr || !fns.vkGetDeviceProcAddr)
			{
				framegen_log.error("Vulkan dispatch table is not loaded; frame generation cannot start");
				return false;
			}

			aci.pVulkanFunctions = &fns;

			if (vmaCreateAllocator(&aci, &g_native.vma) != VK_SUCCESS)
			{
				framegen_log.error("Could not create the frame generation allocator");
				return false;
			}

			const u32 family = dev.get_graphics_queue_family();

			VkCommandPoolCreateInfo pci = {};
			pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
			pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
			pci.queueFamilyIndex = family;

			if (vkCreateCommandPool(dev, &pci, nullptr, &g_native.pool) != VK_SUCCESS)
			{
				framegen_log.error("Could not create the frame generation command pool");
				destroy_native_stack();
				return false;
			}

			VkCommandBufferAllocateInfo cbi = {};
			cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
			cbi.commandPool = g_native.pool;
			cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
			cbi.commandBufferCount = native_stack::SLOTS;

			VkFenceCreateInfo fci = {};
			fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

			if (vkAllocateCommandBuffers(dev, &cbi, g_native.cmd) != VK_SUCCESS)
			{
				framegen_log.error("Could not create the frame generation command buffers");
				destroy_native_stack();
				return false;
			}

			for (u32 i = 0; i < native_stack::SLOTS; ++i)
			{
				if (vkCreateFence(dev, &fci, nullptr, &g_native.fence[i]) != VK_SUCCESS)
				{
					framegen_log.error("Could not create the frame generation fences");
					destroy_native_stack();
					return false;
				}
			}

			// The renderer's own graphics queue, not a fresh vkGetDeviceQueue. Same handle in
			// practice, but taking it from the device makes the important property explicit:
			// frame generation and the present blits share ONE queue, so submission order alone
			// orders them and no CPU wait is needed between the two.
			g_native.queue = dev.get_graphics_queue();

			{
				VkQueryPoolCreateInfo qci = {};
				qci.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
				qci.queryType = VK_QUERY_TYPE_TIMESTAMP;
				qci.queryCount = native_stack::SLOTS * 2;

				VkPhysicalDeviceProperties props{};
				vkGetPhysicalDeviceProperties(dev.gpu(), &props);
				g_native.timestamp_period = props.limits.timestampPeriod;

				if (g_native.timestamp_period <= 0.f ||
					vkCreateQueryPool(dev, &qci, nullptr, &g_native.query_pool) != VK_SUCCESS)
				{
					g_native.query_pool = VK_NULL_HANDLE;
				}
			}

			g_native.allocator = std::make_unique<Vulkan::MemoryAllocator>(g_native.vma);
			g_native.device = std::make_unique<Vulkan::Device>(&dev);

			if (!g_native.device->IsVulkanMemoryModelSupported() || !g_native.device->HasNullDescriptor())
			{
				framegen_log.error("Frame generation needs vulkanMemoryModel and nullDescriptor;"
					" this device enabled neither");
				destroy_native_stack();
				return false;
			}

			g_native.framegen = std::make_unique<Vulkan::FrameGen>(*g_native.allocator, &dev);
			return true;
		}
	}

	u32 generate_into_targets(const vk::render_device& dev, const generated_target* targets, u32 count)
	{
		// One command buffer, one submit -- Process, every GenerateInto, and every copy into the
		// acquired swapchain images, exactly as the ARMSX2 driver does it. The previous shape
		// submitted framegen's work and then a separate command buffer per generated frame; the
		// passes measured 4.1 ms of GPU time while adding 12 ms to the frame, and that gap was
		// the per-submit overhead.
		if (!g_native.valid() || !g_source_image || !targets || !count)
		{
			return 0;
		}

		if (!g_plan_ready.exchange(false))
		{
			return 0;
		}

		const u32 slot = g_native.slot();

		if (g_native.slot_submitted[slot])
		{
			if (vkWaitForFences(dev, 1, &g_native.fence[slot], VK_TRUE, 1'000'000'000ull) != VK_SUCCESS)
			{
				disable("frame generation timed out");
				return 0;
			}

			g_native.slot_submitted[slot] = false;

			if (g_native.query_pool != VK_NULL_HANDLE)
			{
				u64 stamps[2] = {};

				if (vkGetQueryPoolResults(dev, g_native.query_pool, slot * 2, 2, sizeof(stamps),
						stamps, sizeof(u64), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
					stamps[1] > stamps[0])
				{
					static u64 s_window = 0;
					static f64 s_sum = 0.0, s_peak = 0.0;
					static u32 s_count = 0;

					const f64 ms = (stamps[1] - stamps[0]) * g_native.timestamp_period / 1'000'000.0;
					s_sum += ms;
					s_peak = std::max(s_peak, ms);
					s_count++;

					if (const u64 now = get_system_time(); now - s_window > 1'000'000)
					{
						if (s_window && s_count)
						{
							framegen_log.notice("GPU cost: avg %.2f ms/frame, peak %.2f ms, over %u frames",
								s_sum / s_count, s_peak, s_count);
						}

						s_window = now;
						s_sum = s_peak = 0.0;
						s_count = 0;
					}
				}
			}
		}

		vkResetCommandBuffer(g_native.cmd[slot], 0);

		VkCommandBufferBeginInfo begin = {};
		begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
		begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

		if (vkBeginCommandBuffer(g_native.cmd[slot], &begin) != VK_SUCCESS)
		{
			disable("could not begin the frame generation command buffer");
			return 0;
		}

		if (g_native.query_pool != VK_NULL_HANDLE)
		{
			vkCmdResetQueryPool(g_native.cmd[slot], g_native.query_pool, slot * 2, 2);
			vkCmdWriteTimestamp(g_native.cmd[slot], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
				g_native.query_pool, slot * 2);
		}

		const Vulkan::vk::CommandBuffer cmdbuf{g_native.cmd[slot]};
		const VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

		auto barrier = [&](VkImage image, VkImageLayout from, VkImageLayout to,
			VkAccessFlags src_access, VkAccessFlags dst_access,
			VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage)
		{
			VkImageMemoryBarrier b = {};
			b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
			b.oldLayout = from;
			b.newLayout = to;
			b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
			b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
			b.image = image;
			b.subresourceRange = range;
			b.srcAccessMask = src_access;
			b.dstAccessMask = dst_access;
			vkCmdPipelineBarrier(g_native.cmd[slot], src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &b);
		};

		barrier(g_source_image, g_source_layout, VK_IMAGE_LAYOUT_GENERAL,
			VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT,
			VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

		const VkExtent2D extent{g_shared_w, g_shared_h};

		g_native.framegen->Process(*g_native.device, cmdbuf, g_source_image,
			g_shared_out[0].view(), extent, VK_FORMAT_R8G8B8A8_UNORM, extent);

		const size_t wanted = g_native.framegen->WantedGenerations(g_context_outputs);
		const size_t available = g_native.framegen->GeneratedFrameCount();
		const u32 generations = static_cast<u32>(std::min({wanted, available,
			static_cast<size_t>(g_context_outputs), static_cast<size_t>(count)}));

		g_planned_generations = generations;

		for (u32 i = 0; i < generations; ++i)
		{
			g_native.framegen->GenerateInto(*g_native.device, cmdbuf, g_shared_out[i].handle(),
				g_shared_out[i].view(), i);

			// Straight into the acquired swapchain image, in this same buffer.
			barrier(g_shared_out[i].handle(), VK_IMAGE_LAYOUT_GENERAL,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
				VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT);

			barrier(targets[i].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT);

			VkImageCopy region = {};
			region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
			region.dstSubresource = region.srcSubresource;
			region.extent = { g_shared_w, g_shared_h, 1 };

			vkCmdCopyImage(g_native.cmd[slot], g_shared_out[i].handle(),
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, targets[i].image,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

			barrier(targets[i].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_ACCESS_MEMORY_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
		}

		barrier(g_source_image, VK_IMAGE_LAYOUT_GENERAL, g_source_layout,
			VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

		if (g_native.query_pool != VK_NULL_HANDLE)
		{
			vkCmdWriteTimestamp(g_native.cmd[slot], VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				g_native.query_pool, slot * 2 + 1);
		}

		if (vkEndCommandBuffer(g_native.cmd[slot]) != VK_SUCCESS)
		{
			disable("could not end the frame generation command buffer");
			return 0;
		}

		// ONE submit: waits on every acquire, signals every present.
		VkSemaphore waits[8] = {};
		VkPipelineStageFlags wait_stages[8] = {};
		VkSemaphore signals[8] = {};
		u32 wait_count = 0, signal_count = 0;

		for (u32 i = 0; i < generations && wait_count < 8; ++i)
		{
			if (targets[i].acquire != VK_NULL_HANDLE)
			{
				waits[wait_count] = targets[i].acquire;
				wait_stages[wait_count++] = VK_PIPELINE_STAGE_TRANSFER_BIT;
			}

			if (targets[i].present != VK_NULL_HANDLE && signal_count < 8)
			{
				signals[signal_count++] = targets[i].present;
			}
		}

		VkSubmitInfo submit = {};
		submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		submit.commandBufferCount = 1;
		submit.pCommandBuffers = &g_native.cmd[slot];
		submit.waitSemaphoreCount = wait_count;
		submit.pWaitSemaphores = waits;
		submit.pWaitDstStageMask = wait_stages;
		submit.signalSemaphoreCount = signal_count;
		submit.pSignalSemaphores = signals;

		vkResetFences(dev, 1, &g_native.fence[slot]);

		if (vkQueueSubmit(g_native.queue, 1, &submit, g_native.fence[slot]) != VK_SUCCESS)
		{
			disable("frame generation submit failed");
			return 0;
		}

		g_native.slot_submitted[slot] = true;
		g_native.frame_index++;

		return generations;
	}

	u32 generate(const vk::render_device& dev)
	{
		// The whole of frame generation, in one command buffer of its own: Process the frame the
		// present path just handed us, ask the pacer how many to make from it, make them, submit.
		// This is the shape the ARMSX2 driver uses. The capture side only remembers the image.
		if (!g_native.valid() || !g_source_image)
		{
			return 0;
		}

		if (!g_plan_ready.exchange(false))
		{
			return 0;
		}

		// Wait for the PREVIOUS submission, not this one: the command buffer cannot be reset
		// while it is executing, but waiting after submitting would put the whole interpolation
		// on the critical path. The blits that read these images go to the SAME queue afterwards,
		// so they are ordered behind the passes without anyone waiting.
		const u32 slot = g_native.slot();

		if (g_native.slot_submitted[slot])
		{
			// This slot's own fence, which is SLOTS frames old -- so in the normal case it is
			// already signalled and this costs nothing.
			if (vkWaitForFences(dev, 1, &g_native.fence[slot], VK_TRUE, 1'000'000'000ull) != VK_SUCCESS)
			{
				disable("frame generation timed out");
				return 0;
			}

			g_native.slot_submitted[slot] = false;

			// Read this slot's timestamps now that its fence has signalled.
			if (g_native.query_pool != VK_NULL_HANDLE)
			{
				u64 stamps[2] = {};

				if (vkGetQueryPoolResults(dev, g_native.query_pool, slot * 2, 2, sizeof(stamps),
						stamps, sizeof(u64), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
					stamps[1] > stamps[0])
				{
					static u64 s_window = 0;
					static f64 s_sum = 0.0;
					static f64 s_peak = 0.0;
					static u32 s_count = 0;

					const f64 ms = (stamps[1] - stamps[0]) * g_native.timestamp_period / 1'000'000.0;
					s_sum += ms;
					s_peak = std::max(s_peak, ms);
					s_count++;

					if (const u64 now = get_system_time(); now - s_window > 1'000'000)
					{
						if (s_window && s_count)
						{
							framegen_log.notice("GPU cost: avg %.2f ms/frame, peak %.2f ms, over %u frames",
								s_sum / s_count, s_peak, s_count);
						}

						s_window = now;
						s_sum = 0.0;
						s_peak = 0.0;
						s_count = 0;
					}
				}
			}
		}

		vkResetCommandBuffer(g_native.cmd[slot], 0);

		VkCommandBufferBeginInfo begin = {};
		begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
		begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

		if (vkBeginCommandBuffer(g_native.cmd[slot], &begin) != VK_SUCCESS)
		{
			disable("could not begin the frame generation command buffer");
			return 0;
		}

		if (g_native.query_pool != VK_NULL_HANDLE)
		{
			vkCmdResetQueryPool(g_native.cmd[slot], g_native.query_pool, slot * 2, 2);
			vkCmdWriteTimestamp(g_native.cmd[slot], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
				g_native.query_pool, slot * 2);
		}

		const Vulkan::vk::CommandBuffer cmdbuf{g_native.cmd[slot]};

		// Process the real presented image HERE, in framegen's own command buffer, exactly as
		// the ARMSX2 driver does -- transition it to a readable layout, let Process copy it into
		// its chain itself, and put it back. No capture copy, and none of this on the frame.
		VkImageMemoryBarrier to_read = {};
		to_read.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		to_read.oldLayout = g_source_layout;
		to_read.newLayout = VK_IMAGE_LAYOUT_GENERAL;
		to_read.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_read.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_read.image = g_source_image;
		to_read.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		to_read.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
		to_read.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;

		vkCmdPipelineBarrier(g_native.cmd[slot], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			0, 0, nullptr, 0, nullptr, 1, &to_read);

		const VkExtent2D extent{g_shared_w, g_shared_h};

		g_native.framegen->Process(*g_native.device, cmdbuf, g_source_image,
			g_shared_out[0].view(), extent, VK_FORMAT_R8G8B8A8_UNORM, extent);

		VkImageMemoryBarrier from_read = to_read;
		from_read.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
		from_read.newLayout = g_source_layout;
		from_read.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
		from_read.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;

		vkCmdPipelineBarrier(g_native.cmd[slot],
			VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &from_read);

		// Plan straight after Process, as the ARMSX2 driver does: Process is what updates the
		// pacer's view of the frame, and GeneratedFrameCount() only answers once it has warmed up.
		const size_t wanted = g_native.framegen->WantedGenerations(g_context_outputs);
		const size_t available = g_native.framegen->GeneratedFrameCount();
		const u32 generations = static_cast<u32>(std::min({wanted, available,
			static_cast<size_t>(g_context_outputs)}));

		g_planned_generations = generations;

		for (u32 i = 0; i < generations; ++i)
		{
			g_native.framegen->GenerateInto(*g_native.device, cmdbuf, g_shared_out[i].handle(),
				g_shared_out[i].view(), i);
		}

		if (g_native.query_pool != VK_NULL_HANDLE)
		{
			vkCmdWriteTimestamp(g_native.cmd[slot], VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				g_native.query_pool, slot * 2 + 1);
		}

		if (vkEndCommandBuffer(g_native.cmd[slot]) != VK_SUCCESS)
		{
			disable("could not end the frame generation command buffer");
			return 0;
		}

		VkSubmitInfo submit = {};
		submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		submit.commandBufferCount = 1;
		submit.pCommandBuffers = &g_native.cmd[slot];

		vkResetFences(dev, 1, &g_native.fence[slot]);

		if (vkQueueSubmit(g_native.queue, 1, &submit, g_native.fence[slot]) != VK_SUCCESS)
		{
			disable("frame generation submit failed");
			return 0;
		}

		g_native.slot_submitted[slot] = true;
		g_native.frame_index++;

		{
			static u64 s_window = 0;
			static u32 s_real = 0;
			static u32 s_generated = 0;

			s_real++;
			s_generated += generations;

			if (const u64 now = get_system_time(); now - s_window > 1'000'000)
			{
				if (s_window)
				{
					g_display_fps.store(static_cast<f32>(s_real + s_generated));
				}

				s_window = now;
				s_real = 0;
				s_generated = 0;
			}
		}

		return generations;
	}
#else
	u32 generated_frame_count() { return 0; }
	u32 generate(const vk::render_device&) { return 0; }
	VkImage generated_image(u32) { return VK_NULL_HANDLE; }
	f32 display_fps() { return 0.f; }
	std::string status_text() { return {}; }
#endif

}
