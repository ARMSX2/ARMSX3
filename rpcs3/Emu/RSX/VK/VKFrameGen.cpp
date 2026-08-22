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

		const VideoCore::FrameGen::LosslessStatus rc = VideoCore::FrameGen::BuildShaderCache();

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

		if (VideoCore::FrameGen::LoadShaderModules(probe, false, false) !=
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
		shared_image g_shared_in[2];

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
			VkCommandPool pool = VK_NULL_HANDLE;
			VkCommandBuffer cmd = VK_NULL_HANDLE;
			VkFence fence = VK_NULL_HANDLE;
			VkQueue queue = VK_NULL_HANDLE;

			// Whether the fence has a submission to wait on. Waiting on a never-submitted fence
			// blocks for the full timeout, once, on the first generated frame.
			bool submitted = false;

			bool valid() const { return framegen != nullptr; }
		};

		native_stack g_native;

		bool build_native_stack(const vk::render_device& dev, u32 want);
		void destroy_native_stack();
	}

	void release_shared_images()
	{
		release_generation_context();

		g_shared_in[0].destroy();
		g_shared_in[1].destroy();
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

	bool capture_presented_frame(const vk::command_buffer& cmd, const vk::render_device& dev,
		VkImage src, VkImageLayout src_layout, u32 width, u32 height)
	{
		// Unconditional probe, once a second.
		//
		// Deliberately before every early-out. Twice now the feature has produced no output and
		// the cause was guessed rather than measured -- first the hook sat in a present branch the
		// default path never takes, then it was unclear whether the setting reached the core at
		// all. This answers both in one line: if it never prints, presentation is not calling us;
		// if it prints with mode=0, the UI value is not arriving.
		{
			static u64 s_probe = 0;

			if (const u64 now = get_system_time(); now - s_probe > 1'000'000)
			{
				s_probe = now;
				framegen_log.notice("present hook reached: mode=%d src=%s %ux%u",
					static_cast<int>(g_cfg.video.frame_generation.get()),
					src ? "yes" : "null", width, height);
			}
		}

		if (g_cfg.video.frame_generation == frame_generation_mode::off || !src || !width || !height)
		{
			// REVERTED 2026-08-19. Tearing framegen's resources down here -- release_shared_images()
			// and shutdown() -- broke rendering outright: Arkham City lost its character models first,
			// then almost everything. Adding a vkDeviceWaitIdle on our device before the release was
			// not enough, so the in-flight capture blit was not the whole story and the damage reaches
			// further than the shared images.
			//
			// The underlying complaint is real and still open: switching frame generation off does not
			// give its memory back, so the frame rate does not recover until the game is restarted.
			// But a renderer that draws nothing is worse than one that holds memory it is no longer
			// using, so this goes back to leaking until the teardown can be done somewhere the present
			// path is not mid-flight -- most likely at a device-idle point owned by the renderer,
			// not from inside the capture hook.
			return false;
		}

		// Every requirement, checked once and reported once.
		//
		// AHardwareBuffer is what OUR design needs to hand images to framegen's separate device.
		// vulkanMemoryModel and nullDescriptor are what the Lossless Scaling SHADERS need and
		// would still be required by a single-device implementation -- they are a property of the
		// shaders, not of how the images get there.
		//
		// Reported once rather than per frame: this runs on the present path, and a device that
		// fails the check fails it every single frame.
		static bool s_reported = false;

		const bool ahb = dev.get_external_memory_ahb_support();
		const bool memory_model = dev.get_vulkan_memory_model_support();
		const bool null_descriptor = dev.get_null_descriptor_support();

		if (!ahb || !memory_model || !null_descriptor)
		{
			if (!s_reported)
			{
				s_reported = true;
				framegen_log.error("Frame generation is not supported here -- AHardwareBuffer: %s,"
					" vulkanMemoryModel: %s, nullDescriptor: %s",
					ahb ? "yes" : "NO", memory_model ? "yes" : "NO", null_descriptor ? "yes" : "NO");
			}

			return false;
		}

		// Rebuild on resize. Cheap to test and the alternative is copying into an image of the
		// wrong size, which vkCmdCopyImage will happily do and produce garbage from.
		if (g_shared_w != width || g_shared_h != height)
		{
			release_shared_images();

			if (!g_shared_in[0].create(dev, width, height, VK_FORMAT_R8G8B8A8_UNORM) ||
				!g_shared_in[1].create(dev, width, height, VK_FORMAT_R8G8B8A8_UNORM))
			{
				release_shared_images();
				framegen_log.error("Could not create shared images at %ux%u, frame generation is off", width, height);
				return false;
			}

			g_shared_w = width;
			g_shared_h = height;
			g_captures = 0;
			framegen_log.success("Shared images ready at %ux%u", width, height);
		}

		shared_image& dst = g_shared_in[g_shared_slot];
		g_recorded_slot = g_shared_slot;
		g_shared_slot ^= 1u;

		const u64 started = get_system_time();

		// The imported image starts UNDEFINED and has to reach TRANSFER_DST before the copy. It is
		// not a vk::image, so the renderer's layout tracking does not apply -- the layout is
		// carried on the shared_image itself.
		VkImageMemoryBarrier to_dst = {};
		to_dst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		to_dst.oldLayout = dst.layout;
		to_dst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		to_dst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_dst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_dst.image = dst.handle();
		to_dst.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		to_dst.srcAccessMask = 0;
		to_dst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			0, 0, nullptr, 0, nullptr, 1, &to_dst);

		// Move the source into TRANSFER_SRC by hand and put it back.
		//
		// The source is the SWAPCHAIN image, not a vk::image, so there is no push_layout/pop_layout
		// and no renderer-side layout tracking to update -- the caller states the layout it is in
		// and gets it back in exactly that layout.
		VkImageMemoryBarrier to_src = {};
		to_src.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		to_src.oldLayout = src_layout;
		to_src.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		to_src.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_src.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		to_src.image = src;
		to_src.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		to_src.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
		to_src.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

		vkCmdPipelineBarrier(cmd,
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &to_src);

		// Same size on both sides now that the source is the swapchain image, but still a blit:
		// the shared image is fixed at the swapchain dimensions and a mismatch would be a validation
		// error rather than a scaled copy.
		VkImageBlit region = {};
		region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		region.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		region.srcOffsets[1] = { static_cast<s32>(width), static_cast<s32>(height), 1 };
		region.dstOffsets[1] = { static_cast<s32>(width), static_cast<s32>(height), 1 };

		vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
			dst.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_LINEAR);

		VkImageMemoryBarrier from_src = to_src;
		from_src.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		from_src.newLayout = src_layout;
		from_src.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		from_src.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;

		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
			0, 0, nullptr, 0, nullptr, 1, &from_src);

		dst.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

		// Recorded, not committed. The caller promotes this with commit_capture() once the command
		// buffer carrying it has been submitted; see the two flags above.
		g_recorded_capture.store(true);

		// CPU-side timing only: this records how long building the commands takes, not how long
		// the GPU spends on the blit. The GPU cost shows up in the existing per-region GPU timer
		// as part of the frame, which is the number that actually matters against the ~19ms of
		// idle GPU we measured. Reported together so the two can be compared.
		g_capture_ns += (get_system_time() - started) * 1000;
		g_capture_count++;

		if (const u64 now = get_system_time(); now - g_last_report > 1'000'000)
		{
			if (g_capture_count)
			{
				framegen_log.notice("Frame capture: %.3f ms/frame CPU over %u frames (%ux%u)",
					(g_capture_ns / 1'000'000.0) / g_capture_count, g_capture_count, width, height);
			}

			g_capture_ns = 0;
			g_capture_count = 0;
			g_last_report = now;
		}

		return true;
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

			if (g_native.fence != VK_NULL_HANDLE && vk::g_render_device)
			{
				vkDestroyFence(*vk::g_render_device, g_native.fence, nullptr);
				g_native.fence = VK_NULL_HANDLE;
			}

			if (g_native.pool != VK_NULL_HANDLE && vk::g_render_device)
			{
				// Frees g_native.cmd with it.
				vkDestroyCommandPool(*vk::g_render_device, g_native.pool, nullptr);
				g_native.pool = VK_NULL_HANDLE;
				g_native.cmd = VK_NULL_HANDLE;
			}

			if (g_native.vma != VK_NULL_HANDLE)
			{
				vmaDestroyAllocator(g_native.vma);
				g_native.vma = VK_NULL_HANDLE;
			}

			g_native.queue = VK_NULL_HANDLE;

			// Or the rebuilt stack waits on a fence belonging to a submission that no longer
			// exists -- which blocks for the full timeout and then disables the feature.
			g_native.submitted = false;
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
			cbi.commandBufferCount = 1;

			VkFenceCreateInfo fci = {};
			fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

			if (vkAllocateCommandBuffers(dev, &cbi, &g_native.cmd) != VK_SUCCESS ||
				vkCreateFence(dev, &fci, nullptr, &g_native.fence) != VK_SUCCESS)
			{
				framegen_log.error("Could not create the frame generation command buffer");
				destroy_native_stack();
				return false;
			}

			// The renderer's own graphics queue, not a fresh vkGetDeviceQueue. Same handle in
			// practice, but taking it from the device makes the important property explicit:
			// frame generation and the present blits share ONE queue, so submission order alone
			// orders them and no CPU wait is needed between the two.
			g_native.queue = dev.get_graphics_queue();

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

	u32 generate(const vk::render_device& dev)
	{
		const u32 want = generated_frame_count();

		if (!want || g_shared_w == 0)
		{
			return 0;
		}

		// Two real frames are needed before anything can be interpolated between them.
		if (g_captures < 2)
		{
			return 0;
		}

		// ...and a new one this frame, or there is nothing new to interpolate towards.
		if (!g_fresh_capture.exchange(false))
		{
			return 0;
		}

		// No initialize() here any more. That built the dlopen'd library's context on its own
		// VkDevice; build_native_stack() below builds the passes instead, and leaving the old
		// call in meant a failure inside a path nothing uses could still disable the feature.

		if (g_context < 0 || g_context_outputs != want)
		{
			// Through the same teardown the resize path uses, so a setting change from x4 down to x2
			// frees the outputs the smaller context no longer names instead of stranding them.
			release_generation_context();

			for (u32 i = 0; i < want; ++i)
			{
				if (!g_shared_out[i].create(dev, g_shared_w, g_shared_h, VK_FORMAT_R8G8B8A8_UNORM))
				{
					disable("could not allocate shared output images");
					return 0;
				}
			}

			// Build the passes on OUR device.
			//
			// The old library took two AHardwareBuffers in and wrote its outputs into more of
			// them, because it ran on a second VkDevice. These passes read and write the very
			// images the renderer already blitted into, so there is nothing to hand over --
			// which images are read is decided at Process() time, below, not baked into a
			// context here.
			if (!build_native_stack(dev, want))
			{
				disable("could not build the frame generation passes");
				return 0;
			}

			g_context = 0;

			g_context_outputs = want;
			framegen_log.success("Frame generation context ready: %ux%u, %u generated frame(s)",
				g_shared_w, g_shared_h, want);
		}

		// Our device wrote the inputs; framegen's device is about to read them, and there is no
		// semaphore shared between the two. A device-level wait is the only barrier available.
		//
		// It cannot be pipelined away, and that is a property of the library rather than of this
		// code: presentContext() submits on framegen's own device and the only completion signal it
		// exposes is armsx3_lsfg_wait_idle(), a vkDeviceWaitIdle. Upstream's semaphore path takes
		// sync FDs and imports them as OPAQUE_FD (framegen/src/core/semaphore.cpp), which Turnip and
		// Mesa do not support on Android -- which is why every semaphore handed over below is -1.
		// So what this costs is bounded by moving the wait, not by removing it: it now runs before
		// this frame's command buffer is submitted, on inputs that are a frame old, rather than
		// after a full frame-completion wait.
		// Record and run the passes on our own device.
		//
		// Sequenced as the ARMSX2 driver does it: the capture is complete (its blit was submitted
		// before commit_capture), so Process() takes the captured frame and GenerateInto() writes
		// each interpolated frame into an output image. Both go into framegen's OWN command
		// buffer and are submitted as one batch -- the renderer's buffer is already closed by
		// this point in the present path.
		//
		// The fence wait preserves the old contract: generate() returns only once the images are
		// ready, because the present path blits them immediately afterwards. On one device and
		// one queue a semaphore would do this without stalling the thread, and that is the
		// obvious next step -- but this path has broken before, so correctness first.
		if (!g_native.valid())
		{
			disable("frame generation passes are not built");
			return 0;
		}

		// Wait for the PREVIOUS submission, not this one.
		//
		// The command buffer cannot be reset while it is still executing, so something has to
		// wait -- but waiting at the END, after submitting, put the whole interpolation on the
		// critical path: the thread sat idle until the GPU finished, every frame. Waiting at the
		// START instead only blocks if the previous frame's passes have not finished by the time
		// the next frame is ready, which is the difference between "generation costs a frame of
		// latency" and "generation costs its full GPU time on the CPU clock".
		//
		// The blits that read these images are submitted to the SAME queue afterwards, so they
		// are already ordered behind the passes without anyone waiting.
		if (g_native.submitted)
		{
			if (vkWaitForFences(dev, 1, &g_native.fence, VK_TRUE, 1'000'000'000ull) != VK_SUCCESS)
			{
				disable("frame generation timed out");
				return 0;
			}

			g_native.submitted = false;
		}

		vkResetCommandBuffer(g_native.cmd, 0);

		VkCommandBufferBeginInfo begin = {};
		begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
		begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

		if (vkBeginCommandBuffer(g_native.cmd, &begin) != VK_SUCCESS)
		{
			disable("could not begin the frame generation command buffer");
			return 0;
		}

		const Vulkan::vk::CommandBuffer cmdbuf{g_native.cmd};
		const u32 newest = g_committed_slot;
		shared_image& latest = g_shared_in[newest];
		const VkExtent2D extent{g_shared_w, g_shared_h};

		g_native.framegen->Process(*g_native.device, cmdbuf, latest.handle(), latest.view(),
			extent, VK_FORMAT_R8G8B8A8_UNORM, extent);

		const size_t wanted = g_native.framegen->WantedGenerations(g_context_outputs);
		const size_t available = g_native.framegen->GeneratedFrameCount();
		const u32 generations = static_cast<u32>(std::min<size_t>(wanted, available));

		for (u32 i = 0; i < generations; ++i)
		{
			g_native.framegen->GenerateInto(*g_native.device, cmdbuf, g_shared_out[i].handle(),
				g_shared_out[i].view(), i);
		}

		if (vkEndCommandBuffer(g_native.cmd) != VK_SUCCESS)
		{
			disable("could not end the frame generation command buffer");
			return 0;
		}

		VkSubmitInfo submit = {};
		submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		submit.commandBufferCount = 1;
		submit.pCommandBuffers = &g_native.cmd;

		vkResetFences(dev, 1, &g_native.fence);

		if (vkQueueSubmit(g_native.queue, 1, &submit, g_native.fence) != VK_SUCCESS)
		{
			disable("frame generation submit failed");
			return 0;
		}

		g_native.submitted = true;

		if (!generations)
		{
			return 0;
		}

		g_context_outputs = generations;

		// Report the real vs generated rate once a second.
		//
		// Without this there is no way to tell the feature is doing anything: the FPS counter
		// reports the rate the GAME renders at, which frame generation deliberately does not
		// change. What changes is how many frames reach the display, and that is only visible if
		// something counts it.
		{
			static u64 s_window = 0;
			static u32 s_real = 0;
			static u32 s_generated = 0;

			s_real++;
			s_generated += g_context_outputs;

			if (const u64 now = get_system_time(); now - s_window > 1'000'000)
			{
				if (s_window)
				{
					const f64 secs = (now - s_window) / 1'000'000.0;
					g_display_fps.store(static_cast<f32>((s_real + s_generated) / secs));

					framegen_log.success("Frame generation: %.1f real + %.1f generated = %.1f fps to the display",
						s_real / secs, s_generated / secs, (s_real + s_generated) / secs);
				}

				s_window = now;
				s_real = 0;
				s_generated = 0;
			}
		}

		return g_context_outputs;
	}
#else
	u32 generated_frame_count() { return 0; }
	u32 generate(const vk::render_device&) { return 0; }
	VkImage generated_image(u32) { return VK_NULL_HANDLE; }
	f32 display_fps() { return 0.f; }
	std::string status_text() { return {}; }
#endif

}
