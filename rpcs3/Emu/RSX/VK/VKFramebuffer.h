#pragma once

#include "vkutils/framebuffer_object.hpp"

namespace vk
{
	struct framebuffer_holder : public vk::framebuffer, public rsx::ref_counted
	{
		using framebuffer::framebuffer;
#if defined(__APPLE__) || defined(__ANDROID__)
		// Set under the cache lock when an image dies while the holder is still referenced; read unlocked
		atomic_t<bool> retired = false;
#endif
	};

	vk::framebuffer_holder* get_framebuffer(VkDevice dev, u16 width, u16 height, VkBool32 has_input_attachments, VkRenderPass renderpass, const std::vector<vk::image*>& image_list);
	vk::framebuffer_holder* get_framebuffer(VkDevice dev, u16 width, u16 height, VkBool32 has_input_attachments, VkRenderPass renderpass, VkFormat format, VkImage attachment);

	void remove_unused_framebuffers();
	void clear_framebuffer_cache();

	void remove_framebuffers_with_image(const vk::image* image);
	void remove_framebuffers_with_image(VkImage handle);
}
