#pragma once

#include "upscaling.h"
#include "../vkutils/device.h"
#include "../VKResourceManager.h"
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
//   Nothing MPL-licensed is statically linked into libarmsx3-core.
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
		// A preset that would not load, build or run, so it costs one attempt rather than one per
		// frame. Only that preset: picking another one tries it, where a single latch for the whole
		// pass left every preset picked after one bad one doing nothing until the scaling changed.
		std::string m_failed_preset;
		// librashader.so is not there at all: nothing can load, whatever is picked.
		bool m_no_runtime = false;
		// Below 100% Shader Preset Resolution the chain runs smaller at both ends: the game's
		// picture is shrunk into `m_small_input` first, and the chain draws into `m_small_output`,
		// which is scaled up onto the screen. Both ends matter. A preset sizes each pass from its
		// source or from the viewport, and for a 720p game the source ones are the expensive part:
		// koko-aio runs six passes at twice its input, 2560x1440, and only its last at the screen's.
		struct small_image
		{
			std::unique_ptr<vk::viewable_image> image;
			// What it could not be made as, so a failure costs one attempt rather than one a frame.
			u32 failed_w = 0;
			u32 failed_h = 0;
			VkFormat failed_format = VK_FORMAT_UNDEFINED;
		};
		small_image m_small_input;
		small_image m_small_output;

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

		// Frames still in flight may be reading it, so the collector frees it once they are done.
		static void drop(small_image& slot)
		{
			if (slot.image)
			{
				vk::get_resource_manager()->dispose(slot.image);
			}
		}

		void drop_small()
		{
			drop(m_small_input);
			drop(m_small_output);
		}

		// `slot` as a w x h image of `format`, made when it is not that already. Null when it
		// cannot be had, and the chain then runs at full size as it always did.
		static vk::viewable_image* ensure_small(small_image& slot, VkFormat format, u32 w, u32 h,
			VkImageUsageFlags usage, VkFormatFeatureFlags needed, const char* what)
		{
			if (slot.image && slot.image->width() == w && slot.image->height() == h && slot.image->format() == format)
			{
				return slot.image.get();
			}

			drop(slot);

			if (w == slot.failed_w && h == slot.failed_h && format == slot.failed_format)
			{
				return nullptr;
			}

			const auto* pdev = vk::get_current_renderer();
			if (pdev && (pdev->get_format_properties(format).optimalTilingFeatures & needed) == needed)
			{
				auto image = std::make_unique<vk::viewable_image>(
					*pdev,
					pdev->get_memory_mapping().device_local,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
					VK_IMAGE_TYPE_2D,
					format,
					w, h, 1, 1, 1, VK_SAMPLE_COUNT_1_BIT,
					VK_IMAGE_LAYOUT_UNDEFINED,
					VK_IMAGE_TILING_OPTIMAL,
					usage,
					VK_IMAGE_CREATE_ALLOW_NULL_RPCS3,
					VMM_ALLOCATION_POOL_SWAPCHAIN,
					RSX_FORMAT_CLASS_COLOR);

				if (image->value != VK_NULL_HANDLE)
				{
					slot.image = std::move(image);
					slot.failed_w = slot.failed_h = 0;
					slot.failed_format = VK_FORMAT_UNDEFINED;
					rsx_log.notice("librashader: %s at %ux%u", what, w, h);
					return slot.image.get();
				}
			}

			rsx_log.warning("librashader: no %ux%u image for the %s; the chain runs at full size", w, h, what);
			slot.failed_w = w;
			slot.failed_h = h;
			slot.failed_format = format;
			return nullptr;
		}

		static void blit_plain(const vk::command_buffer& cmd, vk::viewable_image* src, VkImage present_surface,
			VkImageLayout present_surface_layout, const VkImageBlit& request)
		{
			src->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			vkCmdBlitImage(cmd, src->value, src->current_layout, present_surface,
				present_surface_layout, 1, &request, VK_FILTER_LINEAR);
			src->pop_layout(cmd);
		}

		// Run the chain into `output`. False when it failed, which also turns this preset off.
		bool run_chain(const vk::command_buffer& cmd, const libra_image_vk_t& input, const libra_image_vk_t& output,
			const libra_viewport_t& viewport)
		{
			if (api().vk_filter_chain_frame(&m_chain, cmd, m_frame_count++, input, output,
					&viewport, nullptr, nullptr))
			{
				rsx_log.error("librashader: frame failed; disabling shader chain");
				m_failed_preset = m_loaded_preset;
				destroy_chain();
				return false;
			}
			return true;
		}

		// Build the chain for `preset_path`. Returns false and remembers the preset
		// as failed on any error, so a broken preset costs one attempt, not one per frame.
		bool ensure_chain(const std::string& preset_path)
		{
			if (m_chain && m_loaded_preset == preset_path)
			{
				return true;
			}

			destroy_chain();

			if (preset_path.empty() || m_no_runtime || preset_path == m_failed_preset)
			{
				return false;
			}

			if (!api().instance_loaded)
			{
				// librashader.so is not installed. Expected, not an error: the
				// caller falls back to a plain blit.
				rsx_log.notice("librashader: runtime not present, shader preset ignored");
				m_no_runtime = true;
				return false;
			}

			libra_shader_preset_t preset = nullptr;
			if (api().preset_create(preset_path.c_str(), &preset) || !preset)
			{
				rsx_log.error("librashader: failed to load preset '%s'", preset_path);
				m_failed_preset = preset_path;
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
				m_failed_preset = preset_path;
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
			// The pass itself reaches here through the collector, after the frames that used it.
			destroy_chain();
			m_small_input.image.reset();
			m_small_output.image.reset();
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
				drop_small();
				blit_plain(cmd, src, present_surface, present_surface_layout, request);
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

			// Below 100% the chain runs smaller at both ends (see small_image) and its picture is
			// scaled up onto the screen: every pass costs what its pixels cost, and on a phone GPU a
			// heavy preset's passes are most of the frame. Read each frame, so it changes live.
			const u32 percent = static_cast<u32>(g_cfg.video.shader_preset_scale.get());
			const u32 out_w = std::max<u32>(1, dst_w * percent / 100);
			const u32 out_h = std::max<u32>(1, dst_h * percent / 100);
			vk::viewable_image* small_output = nullptr;
			if (percent < 100 && (out_w < dst_w || out_h < dst_h))
			{
				small_output = ensure_small(m_small_output, m_present_format, out_w, out_h,
					VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
					VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT,
					"chain output");
			}

			if (!small_output)
			{
				drop_small();
			}
			else
			{
				// The game's picture shrunk by the same share, from the part of it that is shown.
				const auto& from = request.srcOffsets;
				const u32 src_w = static_cast<u32>(std::abs(from[1].x - from[0].x));
				const u32 src_h = static_cast<u32>(std::abs(from[1].y - from[0].y));
				const u32 in_w = std::max<u32>(1, src_w * percent / 100);
				const u32 in_h = std::max<u32>(1, src_h * percent / 100);
				vk::viewable_image* small_input = nullptr;
				if (in_w < src_w || in_h < src_h)
				{
					small_input = ensure_small(m_small_input, src->format(), in_w, in_h,
						VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
						VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT,
						"game picture into the chain");
				}
				else
				{
					drop(m_small_input);
				}

				if (small_input)
				{
					src->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
					small_input->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

					VkImageBlit down{};
					down.srcSubresource = request.srcSubresource;
					down.srcOffsets[0] = from[0];
					down.srcOffsets[1] = from[1];
					down.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
					down.dstOffsets[0] = { 0, 0, 0 };
					down.dstOffsets[1] = { static_cast<s32>(in_w), static_cast<s32>(in_h), 1 };
					vkCmdBlitImage(cmd, src->value, src->current_layout, small_input->value,
						small_input->current_layout, 1, &down, VK_FILTER_LINEAR);

					src->pop_layout(cmd);
					// Sampled by the chain's first passes, as the game's picture otherwise is.
					small_input->change_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

					input.handle = small_input->value;
					input.format = small_input->format();
					input.width = in_w;
					input.height = in_h;
				}
				else
				{
					// librashader samples the source, so it must be readable rather than
					// in TRANSFER_SRC as the blit paths leave it.
					src->push_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				}

				// librashader wants its output in COLOR_ATTACHMENT_OPTIMAL and leaves it there.
				small_output->change_layout(cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

				libra_image_vk_t output{};
				output.handle = small_output->value;
				output.format = m_present_format;
				output.width = out_w;
				output.height = out_h;

				libra_viewport_t viewport{};
				viewport.width = out_w;
				viewport.height = out_h;

				const bool drawn = run_chain(cmd, input, output, viewport);
				if (!small_input)
				{
					src->pop_layout(cmd);
				}
				small_output->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

				if (!drawn)
				{
					// The chain is off now; this frame still gets its picture.
					blit_plain(cmd, src, present_surface, present_surface_layout, request);
					return nullptr;
				}

				// Onto the screen where the picture goes, filtered, which also keeps the
				// present target in the TRANSFER_DST layout the present path left it in.
				VkImageBlit up = request;
				up.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
				up.srcOffsets[0] = { 0, 0, 0 };
				up.srcOffsets[1] = { static_cast<s32>(out_w), static_cast<s32>(out_h), 1 };
				vkCmdBlitImage(cmd, small_output->value, small_output->current_layout, present_surface,
					present_surface_layout, 1, &up, VK_FILTER_LINEAR);
				return nullptr;
			}

			// librashader samples the source, so it must be readable rather than
			// in TRANSFER_SRC as the blit paths leave it.
			src->push_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

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

			run_chain(cmd, input, output, viewport);

			src->pop_layout(cmd);
			return nullptr;
		}
	};
}
