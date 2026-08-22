#include "stdafx.h"
#include "swapchain.h"

namespace vk
{
	// Swapchain image RPCS3
	swapchain_image_RPCS3::swapchain_image_RPCS3(render_device& dev, const memory_type_mapping& memory_map, u32 width, u32 height)
		:image(dev, memory_map.device_local, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_IMAGE_TYPE_2D, VK_FORMAT_B8G8R8A8_UNORM, width, height, 1, 1, 1,
			VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_TILING_OPTIMAL,
			VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, 0, VMM_ALLOCATION_POOL_SWAPCHAIN)
	{
		m_width = width;
		m_height = height;
		current_layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

		m_dma_buffer = std::make_unique<buffer>(dev, m_width * m_height * 4, memory_map.host_visible_coherent,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_BUFFER_USAGE_TRANSFER_DST_BIT, 0, VMM_ALLOCATION_POOL_SWAPCHAIN);
	}

	void swapchain_image_RPCS3::do_dma_transfer(command_buffer& cmd)
	{
		VkBufferImageCopy copyRegion = {};
		copyRegion.bufferOffset = 0;
		copyRegion.bufferRowLength = m_width;
		copyRegion.bufferImageHeight = m_height;
		copyRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		copyRegion.imageOffset = {};
		copyRegion.imageExtent = { m_width, m_height, 1 };

		change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		vkCmdCopyImageToBuffer(cmd, value, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_dma_buffer->value, 1, &copyRegion);
		change_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
	}

	u32 swapchain_image_RPCS3::get_required_memory_size() const
	{
		return m_width * m_height * 4;
	}

	void* swapchain_image_RPCS3::get_pixels()
	{
		return m_dma_buffer->map(0, VK_WHOLE_SIZE);
	}

	void swapchain_image_RPCS3::free_pixels()
	{
		m_dma_buffer->unmap();
	}

	// swapchain BASE
	swapchain_base::swapchain_base(physical_device& gpu, u32 present_queue, u32 graphics_queue, u32 transfer_queue, VkFormat format)
	{
		dev.create(gpu, graphics_queue, present_queue, transfer_queue);
		m_surface_format = format;
	}

	// NATIVE swapchain base
	VkResult native_swapchain_base::acquire_next_swapchain_image(VkSemaphore /*semaphore*/, u64 /*timeout*/, u32* result)
	{
		u32 index = 0;
		for (auto& p : swapchain_images)
		{
			if (!p.first)
			{
				p.first = true;
				*result = index;
				return VK_SUCCESS;
			}

			++index;
		}

		return VK_NOT_READY;
	}

	void native_swapchain_base::init_swapchain_images(render_device& dev, u32 preferred_count)
	{
		swapchain_images.resize(preferred_count);
		for (auto& img : swapchain_images)
		{
			img.second = std::make_unique<swapchain_image_RPCS3>(dev, dev.get_memory_mapping(), m_width, m_height);
			img.first = false;
		}
	}

	// WSI implementation
	void swapchain_WSI::init_swapchain_images(render_device& dev, u32 /*preferred_count*/)
	{
		u32 nb_swap_images = 0;
		_vkGetSwapchainImagesKHR(dev, m_vk_swapchain, &nb_swap_images, nullptr);

		if (!nb_swap_images) fmt::throw_exception("Driver returned 0 images for swapchain");

		std::vector<VkImage> vk_images;
		vk_images.resize(nb_swap_images);
		_vkGetSwapchainImagesKHR(dev, m_vk_swapchain, &nb_swap_images, vk_images.data());

		swapchain_images.resize(nb_swap_images);
		for (u32 i = 0; i < nb_swap_images; ++i)
		{
			swapchain_images[i].value = vk_images[i];
		}
	}

	swapchain_WSI::swapchain_WSI(vk::physical_device& gpu, u32 present_queue, u32 graphics_queue, u32 transfer_queue, VkFormat format, VkSurfaceKHR surface, VkColorSpaceKHR color_space, bool force_wm_reporting_off)
		: WSI_swapchain_base(gpu, present_queue, graphics_queue, transfer_queue, format)
	{
		_vkCreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(vkGetDeviceProcAddr(dev, "vkCreateSwapchainKHR"));
		_vkDestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(vkGetDeviceProcAddr(dev, "vkDestroySwapchainKHR"));
		_vkGetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(vkGetDeviceProcAddr(dev, "vkGetSwapchainImagesKHR"));
		_vkAcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(vkGetDeviceProcAddr(dev, "vkAcquireNextImageKHR"));
		_vkQueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(vkGetDeviceProcAddr(dev, "vkQueuePresentKHR"));

		m_surface = surface;
		m_color_space = color_space;

		if (!force_wm_reporting_off)
		{
			switch (gpu.get_driver_vendor())
			{
			case driver_vendor::AMD:
			case driver_vendor::INTEL:
			case driver_vendor::RADV:
			case driver_vendor::MVK:
				break;
			case driver_vendor::ANV:
			case driver_vendor::NVIDIA:
				m_wm_reports_flag = true;
				break;
			default:
				break;
			}
		}
	}

	void swapchain_WSI::destroy_swapchain_only()
	{
		if (VkDevice pdev = dev; pdev && m_vk_swapchain)
		{
			_vkDestroySwapchainKHR(pdev, m_vk_swapchain, nullptr);
			m_vk_swapchain = nullptr;
		}
	}

	void swapchain_WSI::destroy(bool)
	{
		if (VkDevice pdev = dev)
		{
			if (m_vk_swapchain)
			{
				_vkDestroySwapchainKHR(pdev, m_vk_swapchain, nullptr);
			}

			dev.destroy();
		}
	}

	std::pair<VkSurfaceCapabilitiesKHR, bool> swapchain_WSI::init_surface_capabilities()
	{
#ifdef _WIN32
		if (g_cfg.video.vk.exclusive_fullscreen_mode != vk_exclusive_fs_mode::unspecified && dev.get_surface_capabilities_2_support())
		{
			HMONITOR hmonitor = MonitorFromWindow(window_handle, MONITOR_DEFAULTTOPRIMARY);
			if (hmonitor)
			{
				VkSurfaceCapabilities2KHR pSurfaceCapabilities = {};
				pSurfaceCapabilities.sType = VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_2_KHR;

				VkPhysicalDeviceSurfaceInfo2KHR pSurfaceInfo = {};
				pSurfaceInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SURFACE_INFO_2_KHR;
				pSurfaceInfo.surface = m_surface;

				VkSurfaceCapabilitiesFullScreenExclusiveEXT full_screen_exclusive_capabilities = {};
				VkSurfaceFullScreenExclusiveWin32InfoEXT full_screen_exclusive_win32_info = {};
				full_screen_exclusive_capabilities.sType = VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_FULL_SCREEN_EXCLUSIVE_EXT;

				pSurfaceCapabilities.pNext = &full_screen_exclusive_capabilities;

				full_screen_exclusive_win32_info.sType = VK_STRUCTURE_TYPE_SURFACE_FULL_SCREEN_EXCLUSIVE_WIN32_INFO_EXT;
				full_screen_exclusive_win32_info.hmonitor = hmonitor;

				pSurfaceInfo.pNext = &full_screen_exclusive_win32_info;

				auto getPhysicalDeviceSurfaceCapabilities2KHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR>(
					vkGetInstanceProcAddr(dev.gpu(), "vkGetPhysicalDeviceSurfaceCapabilities2KHR")
					);
				ensure(getPhysicalDeviceSurfaceCapabilities2KHR);
				CHECK_RESULT(getPhysicalDeviceSurfaceCapabilities2KHR(dev.gpu(), &pSurfaceInfo, &pSurfaceCapabilities));

				return { pSurfaceCapabilities.surfaceCapabilities, !!full_screen_exclusive_capabilities.fullScreenExclusiveSupported };
			}
			else
			{
				rsx_log.warning("Swapchain: failed to get monitor for the window");
			}
		}
#endif
		VkSurfaceCapabilitiesKHR surface_descriptors = {};
		if (const VkResult res = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(dev.gpu(), m_surface, &surface_descriptors);
			res != VK_SUCCESS)
		{
			// Reported through the same flag as the present-mode queries; the caller recreates the
			// surface before retrying. currentExtent is left zeroed, which init() already reads as
			// "no usable window" and refuses to build a swapchain from.
			if (res == VK_ERROR_SURFACE_LOST_KHR)
			{
				m_surface_is_lost = true;
				rsx_log.warning("Swapchain: surface was lost while querying capabilities; it will be recreated.");
				surface_descriptors = {};
			}
			else
			{
				vk::die_with_error(res);
			}
		}
		return { surface_descriptors, false };
	}

	bool swapchain_WSI::init()
	{
		if (dev.get_present_queue() == VK_NULL_HANDLE)
		{
			rsx_log.error("Cannot create WSI swapchain without a present queue");
			return false;
		}

		m_surface_is_lost = false;

		VkSwapchainKHR old_swapchain = m_vk_swapchain;
		vk::physical_device& gpu = const_cast<vk::physical_device&>(dev.gpu());

		auto [surface_descriptors, should_specify_exclusive_full_screen_mode] = init_surface_capabilities();

		if (surface_descriptors.maxImageExtent.width < m_width ||
			surface_descriptors.maxImageExtent.height < m_height)
		{
			rsx_log.error("Swapchain: Swapchain creation failed because dimensions cannot fit. Max = %d, %d, Requested = %d, %d",
				surface_descriptors.maxImageExtent.width, surface_descriptors.maxImageExtent.height, m_width, m_height);

			return false;
		}

		if (surface_descriptors.currentExtent.width != umax)
		{
			if (surface_descriptors.currentExtent.width == 0 || surface_descriptors.currentExtent.height == 0)
			{
				rsx_log.warning("Swapchain: Current surface extent is a null region. Is the window minimized?");
				return false;
			}

			m_width = surface_descriptors.currentExtent.width;
			m_height = surface_descriptors.currentExtent.height;
		}

		u32 nb_available_modes = 0;

		if (const VkResult res = vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, m_surface, &nb_available_modes, nullptr);
			res != VK_SUCCESS)
		{
			// A lost surface is recoverable and, on Android, routine: the ANativeWindow is destroyed
			// every time the app leaves the foreground. The acquire and present paths have always
			// treated it that way; this one called die_with_error and took the process down, which
			// then tore the RSX thread apart mid-operation and turned a recoverable event into a
			// heap corruption in ~ZCULL_control.
			if (res == VK_ERROR_SURFACE_LOST_KHR)
			{
				m_surface_is_lost = true;
				rsx_log.warning("Swapchain: surface was lost while querying present modes; it will be recreated.");
				return false;
			}

			vk::die_with_error(res);
		}

		std::vector<VkPresentModeKHR> present_modes(nb_available_modes);

		if (const VkResult res = vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, m_surface, &nb_available_modes, present_modes.data());
			res != VK_SUCCESS)
		{
			if (res == VK_ERROR_SURFACE_LOST_KHR)
			{
				m_surface_is_lost = true;
				rsx_log.warning("Swapchain: surface was lost while reading present modes; it will be recreated.");
				return false;
			}

			vk::die_with_error(res);
		}

		VkPresentModeKHR swapchain_present_mode = VK_PRESENT_MODE_FIFO_KHR;
		std::vector<VkPresentModeKHR> preferred_modes;

		// Frame generation needs FIFO, whatever vsync is set to.
		//
		// Interpolated frames are presented BETWEEN the real ones, so each has to survive to a
		// vblank of its own to be seen at all. IMMEDIATE tears through them and MAILBOX keeps
		// only the newest image per vblank -- so the interpolated frame is replaced by the real
		// one that follows it microseconds later and never reaches the screen. What is left is
		// the real frames alone, at an interval now disturbed by the generation work: judder
		// that gets worse the faster the picture changes, which is exactly how it presents when
		// the camera moves.
		//
		// ARMSX2 forces FIFO for the same reason at the same point (VKSwapChain::SelectPresentMode).
		if (g_cfg.video.frame_generation != frame_generation_mode::off &&
			g_cfg.video.vsync != vsync_mode::full)
		{
			rsx_log.warning("Frame generation is enabled; using FIFO presentation so interpolated "
				"frames are displayed rather than discarded.");

			preferred_modes = { VK_PRESENT_MODE_FIFO_KHR };
		}
		else
		switch (g_cfg.video.vsync)
		{
		case vsync_mode::off:
			preferred_modes = { VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_FIFO_RELAXED_KHR };
			break;
		case vsync_mode::adaptive:
			preferred_modes = { VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_FIFO_RELAXED_KHR };
			break;
		case vsync_mode::full:
		default:
			// FIFO is guaranteed to be supported, no need to go through a preference chain
			preferred_modes = {};
			break;
		}

		bool mode_found = false;
		for (VkPresentModeKHR preferred_mode : preferred_modes)
		{
			//Search for this mode in supported modes
			for (VkPresentModeKHR mode : present_modes)
			{
				if (mode == preferred_mode)
				{
					swapchain_present_mode = mode;
					mode_found = true;
					break;
				}
			}

			if (mode_found)
				break;
		}

		rsx_log.notice("Swapchain: present mode %d in use.", static_cast<int>(swapchain_present_mode));

		u32 nb_swap_images = surface_descriptors.minImageCount + 1;

		// Frame generation presents TWO images per rendered frame, so it needs a deeper chain.
		//
		// With the usual three, the acquire for the interpolated frame finds nothing free and
		// present_generated_frame returns null -- the frame is computed in full and then dropped.
		// That presents as frame generation doing nothing except adding judder: the displayed
		// rate stays exactly what it was without it, because only the real frames ever reach the
		// screen, at intervals now disturbed by the work done for the ones that did not.
		//
		// ARMSX2 gates its own pipelined path on the chain having at least four images for the
		// same reason.
		const u32 extra_for_framegen =
			g_cfg.video.frame_generation != frame_generation_mode::off ? 2u : 0u;

		if (surface_descriptors.maxImageCount > 0)
		{
			//Try to negotiate for a triple buffer setup
			//In cases where the front-buffer isnt available for present, its better to have a spare surface
			nb_swap_images = std::max(surface_descriptors.minImageCount + 2u + extra_for_framegen,
				3u + extra_for_framegen);

			if (nb_swap_images > surface_descriptors.maxImageCount)
			{
				// Application must settle for fewer images than desired:
				nb_swap_images = surface_descriptors.maxImageCount;
			}
		}

		rsx_log.notice("Swapchain: requesting %u images (surface allows %u..%u)%s.", nb_swap_images,
			surface_descriptors.minImageCount, surface_descriptors.maxImageCount,
			extra_for_framegen ? ", deeper for frame generation" : "");

		VkSurfaceTransformFlagBitsKHR pre_transform = surface_descriptors.currentTransform;
		if (surface_descriptors.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
			pre_transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;

		// Declaring IDENTITY when the surface is actually rotated hands the rotation to the
		// compositor, which on Android can mean a full screen pass per frame and images held
		// longer before they are released back for acquire. That would show up as time in
		// check_present_status waiting on acquire_next_swapchain_image, which is where 30% of
		// this game's frame currently goes.
		//
		// Logged rather than changed: matching currentTransform means applying the rotation
		// ourselves across both the blit and the overlay pass, and it is only worth doing if
		// the two actually differ here.
		if (pre_transform != surface_descriptors.currentTransform)
		{
			rsx_log.notice("Swapchain: surface currentTransform=0x%x, overriding with IDENTITY (0x%x). Compositor will rotate.",
				static_cast<u32>(surface_descriptors.currentTransform), static_cast<u32>(pre_transform));
		}
		else
		{
			rsx_log.notice("Swapchain: preTransform matches surface currentTransform=0x%x.",
				static_cast<u32>(surface_descriptors.currentTransform));
		}

		VkSwapchainCreateInfoKHR swap_info = {};
		swap_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
		swap_info.surface = m_surface;
		swap_info.minImageCount = nb_swap_images;
		swap_info.imageFormat = m_surface_format;
		swap_info.imageColorSpace = m_color_space;

		swap_info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		swap_info.preTransform = pre_transform;
		swap_info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
		swap_info.imageArrayLayers = 1;
		swap_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
		swap_info.presentMode = swapchain_present_mode;
		swap_info.oldSwapchain = old_swapchain;
		swap_info.clipped = true;

		swap_info.imageExtent.width = std::max(m_width, surface_descriptors.minImageExtent.width);
		swap_info.imageExtent.height = std::max(m_height, surface_descriptors.minImageExtent.height);

#ifdef _WIN32
		VkSurfaceFullScreenExclusiveInfoEXT full_screen_exclusive_info = {};
		if (should_specify_exclusive_full_screen_mode)
		{
			vk_exclusive_fs_mode fs_mode = g_cfg.video.vk.exclusive_fullscreen_mode;
			ensure(fs_mode == vk_exclusive_fs_mode::enable || fs_mode == vk_exclusive_fs_mode::disable);

			full_screen_exclusive_info.sType = VK_STRUCTURE_TYPE_SURFACE_FULL_SCREEN_EXCLUSIVE_INFO_EXT;
			full_screen_exclusive_info.fullScreenExclusive =
				fs_mode == vk_exclusive_fs_mode::enable ? VK_FULL_SCREEN_EXCLUSIVE_ALLOWED_EXT : VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT;

			swap_info.pNext = &full_screen_exclusive_info;
		}

		rsx_log.notice("Swapchain: requesting full screen exclusive mode %d.", static_cast<int>(full_screen_exclusive_info.fullScreenExclusive));
#endif

		_vkCreateSwapchainKHR(dev, &swap_info, nullptr, &m_vk_swapchain);

		if (old_swapchain)
		{
			if (!swapchain_images.empty())
			{
				swapchain_images.clear();
			}

			_vkDestroySwapchainKHR(dev, old_swapchain, nullptr);
		}

		init_swapchain_images(dev);
		return true;
	}

	VkResult swapchain_WSI::present(VkSemaphore semaphore, u32 image)
	{
		VkPresentInfoKHR present = {};
		present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
		present.pNext = nullptr;
		present.swapchainCount = 1;
		present.pSwapchains = &m_vk_swapchain;
		present.pImageIndices = &image;

		if (semaphore != VK_NULL_HANDLE)
		{
			present.waitSemaphoreCount = 1;
			present.pWaitSemaphores = &semaphore;
		}

		return _vkQueuePresentKHR(dev.get_present_queue(), &present);
	}
}
