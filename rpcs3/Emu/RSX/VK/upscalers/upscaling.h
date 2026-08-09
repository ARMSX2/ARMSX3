#pragma once

#include "../vkutils/commands.h"
#include "../vkutils/image.h"

namespace vk
{
	namespace upscaling_flags_
	{
		enum upscaling_flags
		{
			UPSCALE_DEFAULT_VIEW = (1 << 0),
			UPSCALE_LEFT_VIEW    = (1 << 0),
			UPSCALE_RIGHT_VIEW   = (1 << 1),
			UPSCALE_AND_COMMIT   = (1 << 2)
		};
	}

	using namespace upscaling_flags_;

	struct upscaler
	{
		virtual ~upscaler() {}

		// Real format of the image `present_surface` refers to.
		//
		// Only matters to a pass that RENDERS into the present target instead of blitting to
		// it. vkCmdBlitImage converts between formats, so the blit passes never had to know;
		// a render pass and image view must be built from the format the image actually has,
		// and a wrong one reinterprets the bits instead of converting them. Reported as
		// RetroArch shaders shifting the colours: the swapchain is BGRA and the RSX output
		// image is RGBA, so red and blue swapped and the picture came out pink.
		//
		// Set from the present path, which is the only place the swapchain is known, and set
		// per frame rather than once at construction so a swapchain rebuilt at a new format
		// (rotation, resolution change) cannot leave a stale value behind.
		void set_present_format(VkFormat format) { m_present_format = format; }

	protected:
		VkFormat m_present_format = VK_FORMAT_B8G8R8A8_UNORM;

	public:

		virtual vk::viewable_image* scale_output(
			const vk::command_buffer& cmd,          // CB
			vk::viewable_image* src,                // Source input
			VkImage present_surface,                // Present target. May be VK_NULL_HANDLE for some passes
			VkImageLayout present_surface_layout,   // Present surface layout, or VK_IMAGE_LAYOUT_UNDEFINED if no present target is provided
			const VkImageBlit& request,             // Scaling request information
			rsx::flags32_t mode                     // Mode
		) = 0;
	};
}
