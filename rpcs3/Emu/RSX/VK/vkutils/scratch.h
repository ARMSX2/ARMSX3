#pragma once
#include "image.h"

namespace vk
{
	VkSampler null_sampler();
	image_view* null_image_view(const command_buffer& cmd, VkImageViewType type);
	image* get_typeless_helper(VkFormat format, rsx::format_class format_class, u32 requested_width, u32 requested_height);

	// Colour-attachment scratch for the graphics-pipeline byteswap. Unlike the typeless helper this
	// needs SAMPLED and COLOR_ATTACHMENT usage, because the swap runs as a fragment shader rather
	// than a compute dispatch. See gfx_shuffle_pass.
	viewable_image* get_shuffle_helper(u32 index, u32 requested_width, u32 requested_height);

	buffer* get_scratch_buffer(
		const command_buffer& cmd,
		u64 min_required_size,
		VkPipelineStageFlags dst_stage_flags,
		VkAccessFlags dst_access,
		bool zero_memory = false);

	void clear_scratch_resources();
}
