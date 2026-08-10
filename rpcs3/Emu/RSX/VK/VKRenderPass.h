#pragma once

#include "VulkanAPI.h"
#include "Utilities/geometry.h"

namespace vk
{
	class image;
	class command_buffer;

	u64 get_renderpass_key(const std::vector<vk::image*>& images, const std::vector<u8>& input_attachment_ids = {});
	u64 get_renderpass_key(const std::vector<vk::image*>& images, u64 previous_key);
	u64 get_renderpass_key(VkFormat surface_format, u8 sample_count = 1);
	u64 get_renderpass_key(VkFormat color_format, VkFormat depth_format, u8 sample_count = 1);
	VkRenderPass get_renderpass(VkDevice dev, u64 renderpass_key);

	void clear_renderpass_cache(VkDevice dev);

	// Renderpass scope management helpers.
	// NOTE: These are not thread safe by design.
	void begin_renderpass(VkDevice dev, const vk::command_buffer& cmd, u64 renderpass_key, VkFramebuffer target, const coordu& framebuffer_region,
		const VkClearValue* clear_values = nullptr, u32 clear_value_count = 0);
	void begin_renderpass(const vk::command_buffer& cmd, VkRenderPass pass, VkFramebuffer target, const coordu& framebuffer_region,
		u64 base_key = 0, const VkClearValue* clear_values = nullptr, u32 clear_value_count = 0);

	// Same pass with LOAD_OP_CLEAR on the selected aspects. Clearing at pass begin skips
	// reading the attachment into tile memory entirely, where vkCmdClearAttachments inside the
	// pass loads the whole framebuffer first and then overwrites it.
	u64 get_renderpass_key_with_clears(u64 base_key, bool clear_color, bool clear_depth, bool clear_stencil);
	void end_renderpass(const vk::command_buffer& cmd);
	bool is_renderpass_open(const vk::command_buffer& cmd);

	using renderpass_op_callback_t = std::function<void(const vk::command_buffer&, VkRenderPass, VkFramebuffer)>;
	void renderpass_op(const vk::command_buffer& cmd, const renderpass_op_callback_t& op);
}
