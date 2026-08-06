#pragma once

#include "swapchain_core.h"

namespace vk
{
#if defined(ANDROID)
	using swapchain_ANDROID = native_swapchain_base;
	using swapchain_NATIVE = swapchain_ANDROID;

	[[maybe_unused]] static
	VkSurfaceKHR make_WSI_surface(VkInstance vk_instance, display_handle_t window_handle, WSI_config* /*config*/)
	{
		VkSurfaceKHR result = VK_NULL_HANDLE;

		// ARMSX3: this block was copied from swapchain_win32 and never finished.
		// It declared a VkWin32SurfaceCreateInfoKHR (wrong struct -- has no
		// `window` member, and the sType below already said ANDROID), and passed
		// `this->m_instance` from inside a free static function, where `this`
		// does not exist. Neither could ever have compiled, i.e. upstream has
		// never built this file. Matches the swapchain_macos.hpp idiom now.
		VkAndroidSurfaceCreateInfoKHR createInfo = {};
		createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
		createInfo.window = std::get<ANativeWindow *>(window_handle);

		CHECK_RESULT(vkCreateAndroidSurfaceKHR(vk_instance, &createInfo, nullptr, &result));
		return result;
	}
#endif
}
