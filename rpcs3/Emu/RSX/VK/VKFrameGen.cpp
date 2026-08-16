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
#include <android/hardware_buffer.h>
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
		bool g_shaders_loaded = false;   // the cache file has been looked for (found or not)

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
		return g_api.ok && g_api.last_error ? g_api.last_error() : "";
	}

	int import_shaders(const std::string& dll_path)
	{
		if (!available())
		{
			return -1;
		}

		if (!g_api.import_shaders)
		{
			framegen_log.error("This build's frame generation library cannot extract shaders");
			return -1;
		}

		const int rc = g_api.import_shaders(dll_path.c_str());

		if (rc <= 0)
		{
			framegen_log.error("Shader import failed: %s", g_api.last_error());
			return rc;
		}

		// Copy them across the boundary and write them out, so this is the LAST time the user has
		// to find their Lossless.dll. Enumerating by name because the C ABI has no other way to
		// walk the set; names it does not have are simply absent.
		g_shaders.clear();
		g_shaders_loaded = true;

		for (const char* name : k_shader_names)
		{
			const uint8_t* data = nullptr;
			uint32_t size = 0;

			if (g_api.get_shader && g_api.get_shader(name, &data, &size) == ARMSX3_LSFG_OK && data && size)
			{
				g_shaders[name].assign(data, data + size);
			}
		}

		if (g_shaders.empty())
		{
			// The library says it extracted some but none came back by name -- a shim/core name
			// table mismatch. Worth saying plainly rather than failing later inside framegen.
			framegen_log.error("Imported %d shaders but none could be read back by name", rc);
			return -1;
		}

		save_shader_cache();

		framegen_log.success("Imported %u Lossless Scaling shaders", g_shaders.size());
		return ::narrow<int>(g_shaders.size());
	}

	int shader_count()
	{
		if (!available())
		{
			return 0;
		}

		// The cache is consulted here rather than at load time because this is the first thing
		// anything asks, and it costs one stat() when there is nothing to restore.
		if (const u32 restored = load_shader_cache())
		{
			return ::narrow<int>(restored);
		}

		return g_api.shader_count ? g_api.shader_count() : 0;
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

		if (!dev.get_external_memory_ahb_support())
		{
			framegen_log.error("Cannot share images: this device has no AHardwareBuffer external memory");
			return false;
		}

		// GPU_SAMPLED_IMAGE | GPU_COLOR_OUTPUT so both sides can read and write it. Without
		// COLOR_OUTPUT the allocation succeeds and the later vkCreateImage fails on usage flags,
		// which reads as an unrelated format problem.
		AHardwareBuffer_Desc desc = {};
		desc.width = width;
		desc.height = height;
		desc.layers = 1;
		desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
		desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

		AHardwareBuffer* ahb = nullptr;

		if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || !ahb)
		{
			framegen_log.error("AHardwareBuffer_allocate failed for %ux%u", width, height);
			return false;
		}

		// Ask the driver what this buffer needs before creating anything. The memory type index
		// and the allocation size both come from here -- they cannot be derived from the image.
		VkAndroidHardwareBufferPropertiesANDROID props = { .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };

		if (vkGetAndroidHardwareBufferPropertiesANDROID(dev, ahb, &props) != VK_SUCCESS)
		{
			framegen_log.error("vkGetAndroidHardwareBufferPropertiesANDROID failed");
			AHardwareBuffer_release(ahb);
			return false;
		}

		const VkExternalMemoryImageCreateInfo ext_info =
		{
			.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
			.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID
		};

		const VkImageCreateInfo image_info =
		{
			.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
			.pNext = &ext_info,
			.imageType = VK_IMAGE_TYPE_2D,
			.format = format,
			.extent = { width, height, 1 },
			.mipLevels = 1,
			.arrayLayers = 1,
			.samples = VK_SAMPLE_COUNT_1_BIT,
			// OPTIMAL, not LINEAR: the driver decides the layout for an imported buffer and
			// LINEAR would refuse on most Android GPUs.
			.tiling = VK_IMAGE_TILING_OPTIMAL,
			.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
			         VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
			.sharingMode = VK_SHARING_MODE_EXCLUSIVE,
			.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
		};

		VkImage image = VK_NULL_HANDLE;

		if (vkCreateImage(dev, &image_info, nullptr, &image) != VK_SUCCESS)
		{
			framegen_log.error("vkCreateImage failed for a shared %ux%u image", width, height);
			AHardwareBuffer_release(ahb);
			return false;
		}

		// Dedicated allocation is REQUIRED for imported AHardwareBuffer memory, not merely
		// advisable: the spec forbids suballocating it, and drivers enforce that.
		const VkMemoryDedicatedAllocateInfo dedicated =
		{
			.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
			.image = image
		};

		const VkImportAndroidHardwareBufferInfoANDROID import =
		{
			.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
			.pNext = &dedicated,
			.buffer = ahb
		};

		u32 type_index = umax;

		for (u32 i = 0; i < 32; ++i)
		{
			if (props.memoryTypeBits & (1u << i))
			{
				type_index = i;
				break;
			}
		}

		if (type_index == umax)
		{
			framegen_log.error("No memory type accepts this AHardwareBuffer");
			vkDestroyImage(dev, image, nullptr);
			AHardwareBuffer_release(ahb);
			return false;
		}

		const VkMemoryAllocateInfo alloc =
		{
			.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
			.pNext = &import,
			.allocationSize = props.allocationSize,
			.memoryTypeIndex = type_index
		};

		VkDeviceMemory memory = VK_NULL_HANDLE;

		if (vkAllocateMemory(dev, &alloc, nullptr, &memory) != VK_SUCCESS)
		{
			framegen_log.error("Failed to import AHardwareBuffer memory (%llu bytes)", props.allocationSize);
			vkDestroyImage(dev, image, nullptr);
			AHardwareBuffer_release(ahb);
			return false;
		}

		if (vkBindImageMemory(dev, image, memory, 0) != VK_SUCCESS)
		{
			framegen_log.error("vkBindImageMemory failed for a shared image");
			vkFreeMemory(dev, memory, nullptr);
			vkDestroyImage(dev, image, nullptr);
			AHardwareBuffer_release(ahb);
			return false;
		}

		m_ahb = ahb;
		m_image = image;
		m_memory = memory;
		m_device = dev;
		m_width = width;
		m_height = height;
		m_format = format;
		layout = VK_IMAGE_LAYOUT_UNDEFINED;
		return true;
	}

	void shared_image::destroy()
	{
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

		if (m_ahb)
		{
			// Released last: the image and its memory refer to this buffer, and freeing it first
			// leaves them pointing at storage the allocator may already have reused.
			AHardwareBuffer_release(static_cast<AHardwareBuffer*>(m_ahb));
			m_ahb = nullptr;
		}

		m_device = VK_NULL_HANDLE;
		m_width = m_height = 0;
		m_format = VK_FORMAT_UNDEFINED;
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

		if (!initialized())
		{
			// Despite the name, framegen's "device UUID" is not one. It walks the physical devices
			// on ITS instance and matches
			//     (vendorID << 32) | deviceID
			// against this value (framegen/src/core/device.cpp), with 0x1463ABAC as a wildcard.
			// Passing 0 matches nothing, which is why this failed with "Could not find physical
			// device with UUID" even once the shaders imported cleanly.
			//
			// Built from our own adapter rather than using the wildcard on purpose: framegen
			// creates a second VkDevice, and it has to land on the SAME GPU we render with or the
			// AHardwareBuffer handover has two unrelated devices on either end.
			VkPhysicalDeviceProperties props{};
			vkGetPhysicalDeviceProperties(dev.gpu(), &props);

			const u64 device_id = (static_cast<u64>(props.vendorID) << 32) | props.deviceID;

			// SDR only -- there is no HDR output path here for it to mean anything against.
			//
			// Both of the other two are read HERE rather than cached, because this is the only
			// moment they can take effect: the shader family and the flow scale are baked into
			// framegen's device and pipelines at initialize, so changing either mid-session does
			// nothing until frame generation is turned off and on again.
			// 100 / value, NOT value / 100. framegen treats this parameter as a DIVISOR --
			// flowExtent = inputExtent / flowScale (framegen/v3.1_src/shaders/mipmaps.cpp) --
			// and upstream's own layer passes 1.0f/conf.flowScale for exactly that reason.
			// Dividing by 100 inverted the slider: "Motion detail 25%" asked for a flow pyramid
			// FOUR times larger per axis, sixteen times the pixels, which is the opposite of
			// what the setting says and gets slower the further it is turned down. Only the
			// default of 100 happened to be correct, because 1.0 is its own reciprocal.
			const f32 flow_scale = 100.f / std::max<u32>(g_cfg.video.frame_generation_flow_scale, 1u);
			const bool performance = g_cfg.video.frame_generation_performance.get();

			if (!initialize(device_id, false, flow_scale, want, performance, &shader_from_library, nullptr))
			{
				disable(fmt::format("framegen would not initialize on %s (vendor 0x%04x device 0x%04x)",
					props.deviceName, props.vendorID, props.deviceID).c_str());
				return 0;
			}
		}

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

			void* outs[3] = {};

			for (u32 i = 0; i < want; ++i)
			{
				outs[i] = g_shared_out[i].hardware_buffer();
			}

			// Inputs in capture order: g_committed_slot holds the newest capture that has actually
			// reached the GPU, so the older frame is the other one. Passing them the wrong way round
			// interpolates backwards, which looks like juddering rather than an error.
			//
			// The committed slot, not g_shared_slot ^ 1: the capture for the frame being presented
			// right now has already been recorded and has already advanced g_shared_slot, but it has
			// not been submitted, so its image still holds the frame before last.
			const u32 newest = g_committed_slot;

			g_context = g_api.create_context_ahb(
				g_shared_in[newest ^ 1u].hardware_buffer(), g_shared_in[newest].hardware_buffer(),
				outs, want, g_shared_w, g_shared_h, VK_FORMAT_R8G8B8A8_UNORM);

			if (g_context < 0)
			{
				disable(g_api.last_error());
				return 0;
			}

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
		int out_sems[3] = {-1, -1, -1};

		if (g_api.present(g_context, -1, out_sems, g_context_outputs) != ARMSX3_LSFG_OK)
		{
			disable(g_api.last_error());
			return 0;
		}

		g_api.wait_idle();

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
