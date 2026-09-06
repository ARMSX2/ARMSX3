#include "stdafx.h"

#include "VKResolveHelper.h"
#include "VKRenderPass.h"
#include "VKRenderTargets.h"
#include "vkutils/barriers.h"
#include "vkutils/scratch.h"

#include <cstdlib>

namespace
{
	const char *get_format_prefix(VkFormat format)
	{
		switch (format)
		{
			case VK_FORMAT_R5G6B5_UNORM_PACK16:
				return "r16";
			case VK_FORMAT_R8G8B8A8_UNORM:
			case VK_FORMAT_B8G8R8A8_UNORM:
				return "rgba8";
			case VK_FORMAT_R16G16B16A16_SFLOAT:
				return "rgba16f";
			case VK_FORMAT_R32G32B32A32_SFLOAT:
				return "rgba32f";
			case VK_FORMAT_A1R5G5B5_UNORM_PACK16:
				return "r16";
			case VK_FORMAT_R8_UNORM:
				return "r8";
			case VK_FORMAT_R8G8_UNORM:
				return "rg8";
			case VK_FORMAT_R32_SFLOAT:
				return "r32f";
			default:
				fmt::throw_exception("Unhandled VkFormat 0x%x", u32(format));
		}
	}
}

namespace vk
{
	std::unordered_map<VkFormat, std::unique_ptr<vk::cs_resolve_task>> g_resolve_helpers;
	std::unordered_map<VkFormat, std::unique_ptr<vk::cs_unresolve_task>> g_unresolve_helpers;
	std::unique_ptr<vk::depthonly_resolve> g_depth_resolver;
	std::unique_ptr<vk::depthonly_unresolve> g_depth_unresolver;
	std::unique_ptr<vk::stencilonly_resolve> g_stencil_resolver;
	std::unique_ptr<vk::stencilonly_unresolve> g_stencil_unresolver;
	std::unique_ptr<vk::depthstencil_resolve_EXT> g_depthstencil_resolver;
	std::unique_ptr<vk::depthstencil_unresolve_EXT> g_depthstencil_unresolver;
	std::unique_ptr<vk::gfx_shuffle_pass> g_gfx_shuffle;
	std::unique_ptr<vk::gfx_gather_d24x8_pass> g_gfx_gather_d24x8[2];

	template <typename T, typename ...Args>
	void initialize_pass(std::unique_ptr<T>& ptr, vk::render_device& dev, Args&&... extras)
	{
		if (!ptr)
		{
			ptr = std::make_unique<T>(std::forward<Args>(extras)...);
			ptr->create(dev);
		}
	}

	void resolve_image(vk::command_buffer& cmd, vk::viewable_image* dst, vk::viewable_image* src)
	{
		if (src->aspect() == VK_IMAGE_ASPECT_COLOR_BIT)
		{
			auto &job = g_resolve_helpers[src->format()];

			if (!job)
			{
				const char* format_prefix = get_format_prefix(src->format());
				bool require_bgra_swap = false;

				if (vk::get_chip_family() == vk::chip_class::NV_kepler &&
					src->format() == VK_FORMAT_B8G8R8A8_UNORM)
				{
					// Workaround for NVIDIA kepler's broken image_load_store
					require_bgra_swap = true;
				}

				job.reset(new vk::cs_resolve_task(format_prefix, require_bgra_swap));
			}

			job->run(cmd, src, dst);
		}
		else
		{
			std::vector<vk::image*> surface = { dst };
			auto& dev = cmd.get_command_pool().get_owner();

			const auto key = vk::get_renderpass_key(surface);
			auto renderpass = vk::get_renderpass(dev, key);

			if (src->aspect() & VK_IMAGE_ASPECT_STENCIL_BIT)
			{
				if (dev.get_shader_stencil_export_support())
				{
					initialize_pass(g_depthstencil_resolver, dev);
					g_depthstencil_resolver->run(cmd, src, dst, renderpass);
				}
				else
				{
					initialize_pass(g_depth_resolver, dev);
					g_depth_resolver->run(cmd, src, dst, renderpass);

					// Chance for optimization here: If the stencil buffer was not used, simply perform a clear operation
					const auto stencil_init_flags = vk::as_rtt(src)->stencil_init_flags;
					if (stencil_init_flags & 0xFF00)
					{
						initialize_pass(g_stencil_resolver, dev);
						g_stencil_resolver->run(cmd, src, dst, renderpass);
					}
					else
					{
						VkClearDepthStencilValue clear{ 1.f, stencil_init_flags & 0xFF };
						VkImageSubresourceRange range{ VK_IMAGE_ASPECT_STENCIL_BIT, 0, 1, 0, 1 };

						dst->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
						vkCmdClearDepthStencilImage(cmd, dst->value, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
						dst->pop_layout(cmd);
					}
				}
			}
			else
			{
				initialize_pass(g_depth_resolver, dev);
				g_depth_resolver->run(cmd, src, dst, renderpass);
			}
		}
	}

	void unresolve_image(vk::command_buffer& cmd, vk::viewable_image* dst, vk::viewable_image* src)
	{
		if (src->aspect() == VK_IMAGE_ASPECT_COLOR_BIT)
		{
			auto &job = g_unresolve_helpers[src->format()];

			if (!job)
			{
				const char* format_prefix = get_format_prefix(src->format());
				bool require_bgra_swap = false;

				if (vk::get_chip_family() == vk::chip_class::NV_kepler &&
					src->format() == VK_FORMAT_B8G8R8A8_UNORM)
				{
					// Workaround for NVIDIA kepler's broken image_load_store
					require_bgra_swap = true;
				}

				job.reset(new vk::cs_unresolve_task(format_prefix, require_bgra_swap));
			}

			job->run(cmd, dst, src);
		}
		else
		{
			std::vector<vk::image*> surface = { dst };
			auto& dev = cmd.get_command_pool().get_owner();

			const auto key = vk::get_renderpass_key(surface);
			auto renderpass = vk::get_renderpass(dev, key);

			if (src->aspect() & VK_IMAGE_ASPECT_STENCIL_BIT)
			{
				if (dev.get_shader_stencil_export_support())
				{
					initialize_pass(g_depthstencil_unresolver, dev);
					g_depthstencil_unresolver->run(cmd, dst, src, renderpass);
				}
				else
				{
					initialize_pass(g_depth_unresolver, dev);
					g_depth_unresolver->run(cmd, dst, src, renderpass);

					// Chance for optimization here: If the stencil buffer was not used, simply perform a clear operation
					const auto stencil_init_flags = vk::as_rtt(dst)->stencil_init_flags;
					if (stencil_init_flags & 0xFF00)
					{
						initialize_pass(g_stencil_unresolver, dev);
						g_stencil_unresolver->run(cmd, dst, src, renderpass);
					}
					else
					{
						VkClearDepthStencilValue clear{ 1.f, stencil_init_flags & 0xFF };
						VkImageSubresourceRange range{ VK_IMAGE_ASPECT_STENCIL_BIT, 0, 1, 0, 1 };

						dst->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
						vkCmdClearDepthStencilImage(cmd, dst->value, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
						dst->pop_layout(cmd);
					}
				}
			}
			else
			{
				initialize_pass(g_depth_unresolver, dev);
				g_depth_unresolver->run(cmd, dst, src, renderpass);
			}
		}
	}

	bool gfx_shuffle_32_16(const vk::command_buffer& cmd_, vk::buffer* data, u32 data_length)
	{
		// Opt-out lever. This replaces a path that has worked on every other GPU for years, and it
		// can only be exercised on hardware, so leave a way to get the old behaviour back without a
		// rebuild: ARMSX3_GFX_SHUFFLE=0 in driver_env.txt. Function-local on purpose -- at
		// namespace scope this would initialise before driver_env.txt is parsed and read as unset.
		static const bool s_enabled = []()
		{
			const char* v = std::getenv("ARMSX3_GFX_SHUFFLE");
			const bool off = v && v[0] == '0';
			if (off) rsx_log.error("ARMSX3_GFX_SHUFFLE=0: falling back to the compute byteswap.");
			return !off;
		}();

		if (!s_enabled || data_length < 4 || (data_length % 4) != 0)
		{
			return false;
		}

		auto& cmd = const_cast<vk::command_buffer&>(cmd_);
		auto& dev = cmd.get_command_pool().get_owner();

		const u32 words = data_length / 4;
		constexpr u32 tex_w = 1024;
		const u32 full_rows = words / tex_w;
		const u32 remainder = words % tex_w;
		const u32 tex_h = full_rows + (remainder ? 1 : 0);

		auto src_img = vk::get_shuffle_helper(0, VK_FORMAT_R32_UINT, tex_w, tex_h);
		auto dst_img = vk::get_shuffle_helper(1, VK_FORMAT_R32_UINT, tex_w, tex_h);

		if (!src_img || !dst_img)
		{
			return false;
		}

		// The word count is not generally a multiple of the row width, so the tail row is copied as
		// its own region. Texels past the tail are never copied back, so their contents do not
		// matter.
		const VkImageSubresourceLayers layer = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		rsx::simple_array<VkBufferImageCopy> regions;

		if (full_rows)
		{
			regions.push_back({ 0, 0, 0, layer, { 0, 0, 0 }, { tex_w, full_rows, 1 } });
		}

		if (remainder)
		{
			regions.push_back({ u64(full_rows) * tex_w * 4, 0, 0, layer,
				{ 0, static_cast<s32>(full_rows), 0 }, { remainder, 1, 1 } });
		}

		vk::insert_buffer_memory_barrier(cmd, data->value, 0, data_length,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);

		src_img->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		vkCmdCopyBufferToImage(cmd, data->value, src_img->value, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
			regions.size(), regions.data());

		src_img->change_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		dst_img->change_layout(cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

		std::vector<vk::image*> surface = { dst_img };
		auto renderpass = vk::get_renderpass(dev, vk::get_renderpass_key(surface));

		initialize_pass(g_gfx_shuffle, dev);
		g_gfx_shuffle->run(cmd, dst_img,
			src_img->get_view(rsx::default_remap_vector.with_encoding(VK_REMAP_IDENTITY)),
			tex_w, tex_h, renderpass);

		// overlay_pass::run leaves the render pass open.
		vk::end_renderpass(cmd);

		dst_img->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		vkCmdCopyImageToBuffer(cmd, dst_img->value, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, data->value,
			regions.size(), regions.data());

		return true;
	}

	bool gfx_gather_d24x8(const vk::command_buffer& cmd_, const vk::buffer* data, u32 data_offset,
		u32 z_offset, u32 s_offset, u32 width, u32 height, bool swap_bytes)
	{
		static const bool s_enabled = []()
		{
			const char* v = std::getenv("ARMSX3_GFX_GATHER");
			const bool off = v && v[0] == '0';
			if (off) rsx_log.error("ARMSX3_GFX_GATHER=0: falling back to the compute depth gather.");
			return !off;
		}();

		if (!s_enabled || !width || !height)
		{
			return false;
		}

		auto& cmd = const_cast<vk::command_buffer&>(cmd_);
		auto& dev = cmd.get_command_pool().get_owner();

		// Three slots: the two banks in, the packed result out. Slots 0 and 1 belong to
		// gfx_shuffle_32_16, which can be live in the same command buffer.
		auto depth_img   = vk::get_shuffle_helper(2, VK_FORMAT_R32_UINT, width, height);
		auto stencil_img = vk::get_shuffle_helper(3, VK_FORMAT_R8_UINT,  width, height);
		auto out_img     = vk::get_shuffle_helper(4, VK_FORMAT_R32_UINT, width, height);

		if (!depth_img || !stencil_img || !out_img)
		{
			return false;
		}

		const VkImageSubresourceLayers layer = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		const VkExtent3D extent = { width, height, 1 };

		vk::insert_buffer_memory_barrier(cmd, data->value, z_offset,
			(u64(width) * height * 4) + (u64(width) * height),
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);

		// Banks in. Both are tightly packed, so bufferRowLength/ImageHeight stay 0.
		const VkBufferImageCopy z_rgn = { z_offset, 0, 0, layer, { 0, 0, 0 }, extent };
		const VkBufferImageCopy s_rgn = { s_offset, 0, 0, layer, { 0, 0, 0 }, extent };

		depth_img->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		vkCmdCopyBufferToImage(cmd, data->value, depth_img->value, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &z_rgn);
		depth_img->change_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

		stencil_img->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		vkCmdCopyBufferToImage(cmd, data->value, stencil_img->value, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &s_rgn);
		stencil_img->change_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

		out_img->change_layout(cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

		std::vector<vk::image*> surface = { out_img };
		auto renderpass = vk::get_renderpass(dev, vk::get_renderpass_key(surface));

		auto& pass = g_gfx_gather_d24x8[swap_bytes ? 1 : 0];
		initialize_pass(pass, dev, swap_bytes);

		const auto remap = rsx::default_remap_vector.with_encoding(VK_REMAP_IDENTITY);
		std::vector<vk::image_view*> views = { depth_img->get_view(remap), stencil_img->get_view(remap) };

		pass->run(cmd, { 0, 0, width, height }, static_cast<vk::image*>(out_img), views, renderpass);

		// overlay_pass::run leaves the render pass open.
		vk::end_renderpass(cmd);

		out_img->change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

		const VkBufferImageCopy out_rgn = { data_offset, 0, 0, layer, { 0, 0, 0 }, extent };
		vkCmdCopyImageToBuffer(cmd, out_img->value, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, data->value, 1, &out_rgn);

		return true;
	}

	void clear_resolve_helpers()
	{
		for (auto& p : g_gfx_gather_d24x8)
		{
			if (p)
			{
				p->destroy();
				p.reset();
			}
		}

		if (g_gfx_shuffle)
		{
			g_gfx_shuffle->destroy();
			g_gfx_shuffle.reset();
		}

		for (auto &task : g_resolve_helpers)
		{
			task.second->destroy();
		}

		for (auto &task : g_unresolve_helpers)
		{
			task.second->destroy();
		}

		g_resolve_helpers.clear();
		g_unresolve_helpers.clear();

		if (g_depth_resolver)
		{
			g_depth_resolver->destroy();
			g_depth_resolver.reset();
		}

		if (g_stencil_resolver)
		{
			g_stencil_resolver->destroy();
			g_stencil_resolver.reset();
		}

		if (g_depthstencil_resolver)
		{
			g_depthstencil_resolver->destroy();
			g_depthstencil_resolver.reset();
		}

		if (g_depth_unresolver)
		{
			g_depth_unresolver->destroy();
			g_depth_unresolver.reset();
		}

		if (g_stencil_unresolver)
		{
			g_stencil_unresolver->destroy();
			g_stencil_unresolver.reset();
		}

		if (g_depthstencil_unresolver)
		{
			g_depthstencil_unresolver->destroy();
			g_depthstencil_unresolver.reset();
		}
	}

	void reset_resolve_resources()
	{
		if (g_depth_resolver) g_depth_resolver->free_resources();
		if (g_depth_unresolver) g_depth_unresolver->free_resources();
		if (g_stencil_resolver) g_stencil_resolver->free_resources();
		if (g_stencil_unresolver) g_stencil_unresolver->free_resources();
		if (g_depthstencil_resolver) g_depthstencil_resolver->free_resources();
		if (g_depthstencil_unresolver) g_depthstencil_unresolver->free_resources();
	}


	void cs_resolve_base::build(const std::string& format_prefix, bool unresolve, bool bgra_swap)
	{
		create();

		switch (optimal_group_size)
		{
		default:
		case 64:
			cs_wave_x = 8;
			cs_wave_y = 8;
			break;
		case 32:
			cs_wave_x = 8;
			cs_wave_y = 4;
			break;
		}

		static const char* resolve_kernel =
			#include "Emu/RSX/Program/MSAA/ColorResolvePass.glsl"
			;

		static const char* unresolve_kernel =
			#include "Emu/RSX/Program/MSAA/ColorUnresolvePass.glsl"
			;

		const std::pair<std::string_view, std::string> syntax_replace[] =
		{
			{ "%WORKGROUP_SIZE_X", std::to_string(cs_wave_x) },
			{ "%WORKGROUP_SIZE_Y", std::to_string(cs_wave_y) },
			{ "%IMAGE_FORMAT", format_prefix },
			{ "%BGRA_SWAP", bgra_swap ? "1" : "0" }
		};

		m_src = unresolve ? unresolve_kernel : resolve_kernel;
		m_src = fmt::replace_all(m_src, syntax_replace);

		rsx_log.notice("Compute shader:\n%s", m_src);
	}

	void depth_resolve_base::build(bool resolve_depth, bool resolve_stencil, bool is_unresolve)
	{
		vs_src =
			#include "Emu/RSX/Program/GLSLSnippets/GenericVSPassthrough.glsl"
			;

		static const char* depth_resolver =
			#include "Emu/RSX/Program/MSAA/DepthResolvePass.glsl"
			;

		static const char* depth_unresolver =
			#include "Emu/RSX/Program/MSAA/DepthUnresolvePass.glsl"
			;

		static const char* stencil_resolver =
			#include "Emu/RSX/Program/MSAA/StencilResolvePass.glsl"
			;

		static const char* stencil_unresolver =
			#include "Emu/RSX/Program/MSAA/StencilUnresolvePass.glsl"
			;

		static const char* depth_stencil_resolver =
			#include "Emu/RSX/Program/MSAA/DepthStencilResolvePass.glsl"
			;

		static const char* depth_stencil_unresolver =
			#include "Emu/RSX/Program/MSAA/DepthStencilUnresolvePass.glsl"
			;

		if (resolve_depth && resolve_stencil)
		{
			fs_src = is_unresolve ? depth_stencil_unresolver : depth_stencil_resolver;
		}
		else if (resolve_depth)
		{
			fs_src = is_unresolve ? depth_unresolver : depth_resolver;
		}
		else if (resolve_stencil)
		{
			fs_src = is_unresolve ? stencil_unresolver : stencil_resolver;
		}

		rsx_log.notice("Resolve shader:\n%s", fs_src);
	}
}