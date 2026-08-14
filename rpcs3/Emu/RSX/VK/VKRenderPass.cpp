#include "stdafx.h"
#include "Emu/RSX/rsx_profiler.h"

#include "Utilities/mutex.h"
#include "VKRenderPass.h"
#include "VKHelpers.h"
#include "vkutils/image.h"
#include "vkutils/gpu_timer.h"

#include "Emu/RSX/Common/unordered_map.hpp"

namespace vk
{
	struct active_renderpass_info_t
	{
		VkRenderPass pass = VK_NULL_HANDLE;
		VkFramebuffer fbo = VK_NULL_HANDLE;
	};

	atomic_t<u64> g_cached_renderpass_key = 0;
	VkRenderPass  g_cached_renderpass = VK_NULL_HANDLE;
	rsx::unordered_map<VkCommandBuffer, active_renderpass_info_t>  g_current_renderpass;

	shared_mutex g_renderpass_cache_mutex;
	rsx::unordered_map<u64, VkRenderPass> g_renderpass_cache;

	// Key structure
	// 0-7 color_format
	// 8-15 depth_format
	// 16-21 sample_counts
	// 22-36 current layouts
	// 37-41 input attachments
	union renderpass_key_blob
	{
	private:
		// Internal utils
		static u64 encode_layout(VkImageLayout layout)
		{
			switch (+layout)
			{
			case VK_IMAGE_LAYOUT_GENERAL:
			case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
			case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
				return static_cast<u64>(layout);
			case VK_IMAGE_LAYOUT_ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT:
				return 4ull;
			default:
				fmt::throw_exception("Unsupported layout 0x%llx here", static_cast<usz>(layout));
			}
		}

		static VkImageLayout decode_layout(u64 encoded)
		{
			switch (encoded)
			{
			case 1:
			case 2:
			case 3:
				return static_cast<VkImageLayout>(encoded);
			case 4:
				return VK_IMAGE_LAYOUT_ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT;
			default:
				fmt::throw_exception("Unsupported layout encoding 0x%llx here", encoded);
			}
		}

	public:
		u64 encoded;

		struct
		{
			u64 color_format  : 8;
			u64 depth_format  : 8;
			u64 sample_count  : 6;
			u64 layout_blob   : 15;
			u64 input_attachments_mask : 5;
		};

		renderpass_key_blob(u64 encoded_) : encoded(encoded_)
		{}

		// Encoders
		inline void set_layout(u32 index, VkImageLayout layout)
		{
			switch (+layout)
			{
			case VK_IMAGE_LAYOUT_ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT:
			case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
			case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
			case VK_IMAGE_LAYOUT_GENERAL:
				layout_blob |= encode_layout(layout) << (index * 3);
				break;
			default:
				fmt::throw_exception("Unsupported image layout 0x%x", static_cast<u32>(layout));
			}
		}

		inline void set_input_attachment(u32 index)
		{
			input_attachments_mask |= (1ull << index);
		}

		inline void set_format(VkFormat format)
		{
			switch (format)
			{
			case VK_FORMAT_D16_UNORM:
			case VK_FORMAT_D32_SFLOAT:
			case VK_FORMAT_D24_UNORM_S8_UINT:
			case VK_FORMAT_D32_SFLOAT_S8_UINT:
				depth_format = static_cast<u64>(format);
				break;
			default:
				color_format = static_cast<u64>(format);
				break;
			}
		}

		// Decoders
		inline VkSampleCountFlagBits get_sample_count() const
		{
			return static_cast<VkSampleCountFlagBits>(sample_count);
		}

		inline VkFormat get_color_format() const
		{
			return static_cast<VkFormat>(color_format);
		}

		inline VkFormat get_depth_format() const
		{
			return static_cast<VkFormat>(depth_format);
		}

		std::vector<VkAttachmentReference> get_input_attachments() const
		{
			if (input_attachments_mask == 0) [[likely]]
			{
				return {};
			}

			std::vector<VkAttachmentReference> result;
			for (u32 i = 0; i < 5; ++i)
			{
				if (input_attachments_mask & (1ull << i))
				{
					const auto layout = decode_layout((layout_blob >> (i * 3)) & 0x7);
					result.push_back({i, layout});
				}
			}

			return result;
		}

		std::vector<VkImageLayout> get_image_layouts() const
		{
			std::vector<VkImageLayout> result;

			for (u32 i = 0, layout_offset = 0; i < 5; ++i, layout_offset += 3)
			{
				if (const auto layout_encoding = (layout_blob >> layout_offset) & 0x7)
				{
					result.push_back(decode_layout(layout_encoding));
				}
				else
				{
					break;
				}
			}

			return result;
		}
	};

	u64 get_renderpass_key(const std::vector<vk::image*>& images, const std::vector<u8>& input_attachment_ids)
	{
		renderpass_key_blob key(0);

		for (u32 i = 0; i < ::size32(images); ++i)
		{
			const auto& surface = images[i];
			key.set_format(surface->format());
			key.set_layout(i, surface->current_layout);
		}

		for (const auto& ref_id : input_attachment_ids)
		{
			key.set_input_attachment(ref_id);
		}

		key.sample_count = images[0]->samples();
		return key.encoded;
	}

	u64 get_renderpass_key(const std::vector<vk::image*>& images, u64 previous_key)
	{
		// Partial update; assumes compatible renderpass keys
		renderpass_key_blob key(previous_key);
		key.layout_blob = 0;

		for (u32 i = 0; i < ::size32(images); ++i)
		{
			key.set_layout(i, images[i]->current_layout);
		}

		return key.encoded;
	}

	u64 get_renderpass_key(VkFormat surface_format, u8 sample_count)
	{
		renderpass_key_blob key(0);
		key.sample_count = sample_count;

		switch (surface_format)
		{
		case VK_FORMAT_D16_UNORM:
		case VK_FORMAT_D32_SFLOAT:
		case VK_FORMAT_D24_UNORM_S8_UINT:
		case VK_FORMAT_D32_SFLOAT_S8_UINT:
			key.depth_format = static_cast<u64>(surface_format);
			key.layout_blob = static_cast<u64>(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
			break;
		default:
			key.color_format = static_cast<u64>(surface_format);
			key.layout_blob = static_cast<u64>(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			break;
		}

		return key.encoded;
	}

	u64 get_renderpass_key(VkFormat color_format, VkFormat depth_format, u8 sample_count)
	{
		renderpass_key_blob key(0);
		key.sample_count = sample_count;

		u32 image_index = 0;
		if (color_format != VK_FORMAT_UNDEFINED)
		{
			key.set_format(color_format);
			key.set_layout(image_index++, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		}

		if (depth_format != VK_FORMAT_UNDEFINED)
		{
			key.set_format(depth_format);
			key.set_layout(image_index++, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
		}

		return key.encoded;
	}

	VkRenderPass get_renderpass(VkDevice dev, u64 renderpass_key)
	{
		// 99.999% of checks will go through this block once on-disk shader cache has loaded
		{
			reader_lock lock(g_renderpass_cache_mutex);

			auto found = g_renderpass_cache.find(renderpass_key);
			if (found != g_renderpass_cache.end())
			{
				return found->second;
			}
		}

		std::lock_guard lock(g_renderpass_cache_mutex);

		// Check again
		auto found = g_renderpass_cache.find(renderpass_key);
		if (found != g_renderpass_cache.end())
		{
			return found->second;
		}

		// Decode
		renderpass_key_blob key(renderpass_key);
		VkSampleCountFlagBits samples = static_cast<VkSampleCountFlagBits>(key.sample_count);
		std::vector<VkImageLayout> rtv_layouts;
		VkImageLayout dsv_layout = VK_IMAGE_LAYOUT_UNDEFINED;

		VkFormat color_format = static_cast<VkFormat>(key.color_format);
		VkFormat depth_format = static_cast<VkFormat>(key.depth_format);

		std::vector<VkAttachmentDescription> attachments = {};
		std::vector<VkAttachmentReference> attachment_references;

		rtv_layouts = key.get_image_layouts();
		if (depth_format)
		{
			dsv_layout = rtv_layouts.back();
			rtv_layouts.pop_back();
		}

		u32 attachment_count = 0;
		for (const auto &layout : rtv_layouts)
		{
			VkAttachmentDescription color_attachment_description = {};
			color_attachment_description.format = color_format;
			color_attachment_description.samples = samples;
			color_attachment_description.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
			color_attachment_description.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
			color_attachment_description.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
			color_attachment_description.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
			color_attachment_description.initialLayout = layout;
			color_attachment_description.finalLayout = layout;

			attachments.push_back(std::move(color_attachment_description));
			attachment_references.push_back({ attachment_count++, layout });
		}

		if (depth_format)
		{
			VkAttachmentDescription depth_attachment_description = {};
			depth_attachment_description.format = depth_format;
			depth_attachment_description.samples = samples;
			depth_attachment_description.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
			depth_attachment_description.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
			depth_attachment_description.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
			depth_attachment_description.stencilStoreOp = VK_ATTACHMENT_STORE_OP_STORE;
			depth_attachment_description.initialLayout = dsv_layout;
			depth_attachment_description.finalLayout = dsv_layout;
			attachments.push_back(std::move(depth_attachment_description));

			attachment_references.push_back({ attachment_count, dsv_layout });
		}

		VkSubpassDescription subpass = {};
		subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
		subpass.colorAttachmentCount = attachment_count;
		subpass.pColorAttachments = attachment_count? attachment_references.data() : nullptr;
		subpass.pDepthStencilAttachment = depth_format? &attachment_references.back() : nullptr;

		const auto input_attachments = key.get_input_attachments();
		if (!input_attachments.empty())
		{
			subpass.inputAttachmentCount = ::size32(input_attachments);
			subpass.pInputAttachments = input_attachments.data();
		}

		// A self-dependency, so a barrier may be issued INSIDE the pass for the feedback case:
		// an attachment sampled while it is still bound.
		//
		// Vulkan forbids vkCmdPipelineBarrier inside a render pass unless the subpass declares
		// a dependency on itself, and this cache declared none at all. The only legal option
		// was therefore to end the pass, which is exactly what insert_texture_barrier did, 20
		// to 23 times per frame in Arkham City out of roughly 83 passes. On a tiled GPU every
		// one of those is a tile store and a reload of the whole attachment.
		//
		// Framebuffer-local stages only. VK_DEPENDENCY_BY_REGION_BIT is what makes the
		// dependency tile-local rather than a full pipeline flush, and it requires every stage
		// named here to be framebuffer-space, which rules out the vertex stage.
		VkSubpassDependency self_dependency = {};
		self_dependency.srcSubpass = 0;
		self_dependency.dstSubpass = 0;
		self_dependency.srcStageMask =
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
			VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
			VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
		self_dependency.dstStageMask =
			VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
			VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
			VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
		self_dependency.srcAccessMask =
			VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
			VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
		self_dependency.dstAccessMask =
			VK_ACCESS_SHADER_READ_BIT |
			VK_ACCESS_INPUT_ATTACHMENT_READ_BIT |
			VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
			VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
		self_dependency.dependencyFlags = VK_DEPENDENCY_BY_REGION_BIT;

		VkRenderPassCreateInfo rp_info = {};
		rp_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
		rp_info.attachmentCount = ::size32(attachments);
		rp_info.pAttachments = attachments.data();
		rp_info.subpassCount = 1;
		rp_info.pSubpasses = &subpass;
		rp_info.dependencyCount = 1;
		rp_info.pDependencies = &self_dependency;

		VkRenderPass result;
		CHECK_RESULT(vkCreateRenderPass(dev, &rp_info, NULL, &result));

		g_renderpass_cache[renderpass_key] = result;
		return result;
	}

	void clear_renderpass_cache(VkDevice dev)
	{
		// Wipe current status
		g_cached_renderpass_key = 0;
		g_cached_renderpass = VK_NULL_HANDLE;
		g_current_renderpass.clear();

		// Destroy cache
		for (const auto &renderpass : g_renderpass_cache)
		{
			vkDestroyRenderPass(dev, renderpass.second, nullptr);
		}

		g_renderpass_cache.clear();
	}

	void begin_renderpass(const vk::command_buffer& cmd, VkRenderPass pass, VkFramebuffer target, const coordu& framebuffer_region)
	{
		auto& renderpass_info = g_current_renderpass[cmd];
		if (renderpass_info.pass == pass && renderpass_info.fbo == target)
		{
			return;
		}
		else if (renderpass_info.pass != VK_NULL_HANDLE)
		{
			end_renderpass(cmd);
		}

		VkRenderPassBeginInfo rp_begin = {};
		rp_begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
		rp_begin.renderPass = pass;
		rp_begin.framebuffer = target;
		rp_begin.renderArea.offset.x = static_cast<s32>(framebuffer_region.x);
		rp_begin.renderArea.offset.y = static_cast<s32>(framebuffer_region.y);
		rp_begin.renderArea.extent.width = framebuffer_region.width;
		rp_begin.renderArea.extent.height = framebuffer_region.height;

		// Counted here, at the only place a pass actually starts. Counting calls to the
		// wrapper instead gave about 1900 per frame, because it early-outs when the same
		// pass and framebuffer are already bound, and almost every call takes that path.
		if (rsx::prof::enabled()) [[unlikely]]
		{
			rsx::prof::g_render_passes++;
			rsx::prof::g_pass_ordinal++;

			if (rsx::prof::g_pass_ordinal < rsx::prof::pass_slot_count)
			{
				rsx::prof::g_pass_width[rsx::prof::g_pass_ordinal] = static_cast<u16>(framebuffer_region.width);
				rsx::prof::g_pass_height[rsx::prof::g_pass_ordinal] = static_cast<u16>(framebuffer_region.height);
			}
		}

		// The draw region was declared and never recorded anywhere, so the one figure that
		// says how much of the GPU is actually drawing the game has been missing while
		// everything else about the GPU was measured.
		//
		// Timed from outside the pass on both ends deliberately. On a tiler the load at the
		// start and the store at the end are the expensive part, and a timestamp placed
		// inside the pass would exclude exactly the cost worth knowing about.
		vk::get_gpu_timer().begin(cmd, vk::gpu_timer::region::draw);

		vkCmdBeginRenderPass(cmd, &rp_begin, VK_SUBPASS_CONTENTS_INLINE);
		renderpass_info = { pass, target };
	}

	void begin_renderpass(VkDevice dev, const vk::command_buffer& cmd, u64 renderpass_key, VkFramebuffer target, const coordu& framebuffer_region)
	{
		if (renderpass_key != g_cached_renderpass_key)
		{
			g_cached_renderpass = get_renderpass(dev, renderpass_key);
			g_cached_renderpass_key = renderpass_key;
		}

		begin_renderpass(cmd, g_cached_renderpass, target, framebuffer_region);
	}

	void end_renderpass(const vk::command_buffer& cmd)
	{
		// A query that began inside a render pass instance has to end inside that same instance.
		// Ending the pass underneath an open one leaves it permanently unavailable: the driver
		// never marks it ready, and on Turnip it takes the device with it, reported later
		// against poke_query because that is the first call that waits on a result.
		//
		// Queries do begin inside render passes here. VKDraw only lifts them out when
		// use_strict_query_scopes() is set, and that is wired to Strict Rendering Mode, a user
		// performance setting rather than a driver quirk, so it is off for almost everyone.
		//
		// The check belongs here rather than at the call sites. There are twenty-one of them and
		// only one, in VKDraw, ever paired itself with a cleanup; change_image_layout alone ends
		// 41 passes a frame in Web of Shadows, and any of them can land while a query is open.
		// Fixing two of the sites moved the device loss later instead of removing it.
		if (cmd.flags & vk::command_buffer::cb_has_open_query)
		{
			// const_cast: ending the query is a recording operation on this very buffer, and
			// every caller here holds it non-const. The signature is const by history.
			do_query_cleanup(const_cast<vk::command_buffer&>(cmd));
		}

		vkCmdEndRenderPass(cmd);

		// After the pass ends, so the tile store it triggers is charged to the region.
		vk::get_gpu_timer().end(cmd, vk::gpu_timer::region::draw);

		g_current_renderpass[cmd] = {};
	}

	bool is_renderpass_open(const vk::command_buffer& cmd)
	{
		return g_current_renderpass[cmd].pass != VK_NULL_HANDLE;
	}

	void renderpass_op(const vk::command_buffer& cmd, const renderpass_op_callback_t& op)
	{
		const auto& active = g_current_renderpass[cmd];
		op(cmd, active.pass, active.fbo);
	}
}
