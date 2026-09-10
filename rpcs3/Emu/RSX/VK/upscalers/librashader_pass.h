#pragma once

#include "upscaling.h"
#include "../vkutils/device.h"
#include "Emu/system_config.h"
#include "util/logs.hpp"

#include <string>

// ARMSX3: RetroArch (.slangp) shader chains via librashader, run as the output
// scaling pass.
//
// LICENSING -- this is why we use the _ld loader and not the library directly:
//   librashader-capi is "MPL-2.0 OR GPL-3.0-only". RPCS3 is GPL-2.0-ONLY, which
//   GPL-3.0 is incompatible with, so we take the MPL-2.0 option. To keep even
//   that out of our binary, upstream ships `librashader_ld.h`: a permissively
//   licensed, header-only thunk that dlopen()s `librashader.so` at runtime and
//   resolves the entry points. So:
//       - this translation unit contains only the permissive header
//       - librashader.so (MPL-2.0) ships alongside as its own .so
//   Nothing MPL-licensed is statically linked into librpcsx/libarmsx3-core.
//
// The loader is soft-fail by design: if librashader.so is missing or a preset
// fails to compile, `instance_loaded` is false / the create call fails, and we
// fall back to a plain blit rather than dropping frames or crashing.

// librashader_ld.h is vendored upstream code and casts C-style throughout, which
// trips RPCS3's -Werror=old-style-cast. Silence it for the include only rather
// than editing a third-party header we want to keep updatable.
#define LIBRA_RUNTIME_VULKAN
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wold-style-cast"
#pragma clang diagnostic ignored "-Wcast-qual"
#pragma clang diagnostic ignored "-Wunused-function"
#endif
#include <librashader_ld.h>
#ifdef __clang__
#pragma clang diagnostic pop
#endif

namespace vk
{
	extern const vk::render_device* get_current_renderer();

	struct librashader_upscale_pass : public upscaler
	{
	private:
		libra_vk_filter_chain_t m_chain = nullptr;
		std::string m_loaded_preset;
		u64 m_frame_count = 0;
		bool m_failed = false;

		/**
		 * The dlopen()ed entry points.
		 *
		 * librashader_ld.h does NOT redirect the public names -- calling
		 * libra_preset_create() directly compiles (the prototype is visible) and
		 * then fails to LINK, because the real symbol only exists inside
		 * librashader.so, which we deliberately do not link against. The header
		 * hands back a struct of function pointers instead, so every call has to
		 * go through it.
		 *
		 * Loaded once, on first use. `instance_loaded` is false when
		 * librashader.so is absent, which is the normal case for a user who has
		 * not installed shaders -- so that path must be quiet and non-fatal.
		 */
		static const libra_instance_t& api()
		{
			static const libra_instance_t s_instance = librashader_load_instance();
			return s_instance;
		}

		void destroy_chain()
		{
			if (m_chain)
			{
				api().vk_filter_chain_free(&m_chain);
				m_chain = nullptr;
			}
			m_loaded_preset.clear();
		}

		// Build the chain for `preset_path`. Returns false and latches m_failed
		// on any error, so a broken preset costs one attempt, not one per frame.
		bool ensure_chain(const std::string& preset_path)
		{
			if (m_chain && m_loaded_preset == preset_path)
			{
				return true;
			}

			destroy_chain();

			if (preset_path.empty() || m_failed)
			{
				return false;
			}

			if (!api().instance_loaded)
			{
				// librashader.so is not installed. Expected, not an error: the
				// caller falls back to a plain blit.
				rsx_log.notice("librashader: runtime not present, shader preset ignored");
				m_failed = true;
				return false;
			}

			libra_shader_preset_t preset = nullptr;
			if (api().preset_create(preset_path.c_str(), &preset) || !preset)
			{
				rsx_log.error("librashader: failed to load preset '%s'", preset_path);
				m_failed = true;
				return false;
			}

			const auto* renderer = vk::get_current_renderer();
			if (!renderer)
			{
				api().preset_free(&preset);
				return false;
			}

			// render_device has no instance accessor. physical_device stores the
			// VkInstance that created it and exposes BOTH conversions
			// (operator VkPhysicalDevice / operator VkInstance), so gpu() gives
			// us each handle -- overload resolution picks by the target type.
			const vk::physical_device& gpu = renderer->gpu();

			libra_device_vk_t device{};
			device.physical_device = gpu;
			device.instance = gpu;
			device.device = *renderer;
			// Null queue: librashader picks a suitable graphics queue itself,
			// which avoids us handing it one that RPCS3 is already recording on.
			device.queue = VK_NULL_HANDLE;
			device.entry = vkGetInstanceProcAddr;

			if (api().vk_filter_chain_create(&preset, device, nullptr, &m_chain) || !m_chain)
			{
				rsx_log.error("librashader: failed to build filter chain for '%s'", preset_path);
				api().preset_free(&preset);
				m_failed = true;
				return false;
			}

			// create() consumes the preset; freeing it again would double-free.
			m_loaded_preset = preset_path;
			rsx_log.success("librashader: loaded shader preset '%s'", preset_path);
			return true;
		}

	public:
		bool is_rendering_pass() const override { return true; }

		~librashader_upscale_pass() override
		{
			destroy_chain();
		}

		vk::viewable_image* scale_output(
			const vk::command_buffer& cmd,
			vk::viewable_image* src,
			VkImage present_surface,
			VkImageLayout present_surface_layout,
			const VkImageBlit& request,
			rsx::flags32_t mode) override
		{
			// Only the commit path has a present target to render into. The
			// "scale source only" mode has no destination image, and a shader
			// chain has nowhere to put its output, so behave like bilinear.
			if (!(mode & UPSCALE_AND_COMMIT))
			{
				return src;
			}

			ensure(present_surface);

			const std::string preset_path = g_cfg.video.shader_preset_path.to_string();

			if (!ensure_chain(preset_path))
			{
				// Soft fallback: no shader, just present. Matches bilinear_pass
				// so a missing librashader.so degrades to normal output rather
				// than a black screen.
				src->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
				vkCmdBlitImage(cmd, src->value, src->current_layout, present_surface,
					present_surface_layout, 1, &request, VK_FILTER_LINEAR);
				src->pop_layout(cmd);
				return nullptr;
			}

			const auto& dst = request.dstOffsets;
			const u32 dst_w = static_cast<u32>(std::abs(dst[1].x - dst[0].x));
			const u32 dst_h = static_cast<u32>(std::abs(dst[1].y - dst[0].y));

			libra_image_vk_t input{};
			input.handle = src->value;
			input.format = src->format();
			input.width = src->width();
			input.height = src->height();

			libra_image_vk_t output{};
			output.handle = present_surface;
			// The present surface's own format, NOT the source's. These are different images
			// and routinely different formats (BGRA swapchain, RGBA source); librashader builds
			// its output view and render pass from what it is told here, so handing it the
			// source format made it write through a mismatched view and swap red with blue.
			output.format = m_present_format;
			output.width = dst_w;
			output.height = dst_h;

			libra_viewport_t viewport{};
			viewport.x = static_cast<float>(std::min(dst[0].x, dst[1].x));
			viewport.y = static_cast<float>(std::min(dst[0].y, dst[1].y));
			viewport.width = dst_w;
			viewport.height = dst_h;

			// librashader samples the source, so it must be readable rather than
			// in TRANSFER_SRC as the blit paths leave it.
			src->push_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

			if (api().vk_filter_chain_frame(&m_chain, cmd, m_frame_count++, input, output,
					&viewport, nullptr, nullptr))
			{
				rsx_log.error("librashader: frame failed; disabling shader chain");
				m_failed = true;
				destroy_chain();
			}

			src->pop_layout(cmd);
			return nullptr;
		}
	};
}
