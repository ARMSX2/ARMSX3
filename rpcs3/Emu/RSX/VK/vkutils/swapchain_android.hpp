#pragma once

#include "swapchain_core.h"

#include <utility>
#include <vector>

namespace vk
{
#if defined(ANDROID)
	using swapchain_ANDROID = native_swapchain_base;
	using swapchain_NATIVE = swapchain_ANDROID;

	// Android permits only one *connected* VkSurfaceKHR per ANativeWindow, and the
	// Adreno/Turnip driver only releases the window's producer claim on an explicit
	// vkDestroySurfaceKHR -- NOT when the surface is reaped as a child of vkDestroyInstance.
	// Across a savestate reload the renderer and its VkInstance are destroyed and rebuilt, and
	// opening the home menu reinitializes the swapchain (a second make_WSI_surface), leaving
	// the previous surface for vkDestroyInstance to reap. That leftover keeps the window "in
	// use", so the next session's vkCreateAndroidSurfaceKHR fails with
	// VK_ERROR_NATIVE_WINDOW_IN_USE_KHR. Track every WSI surface so each gets an explicit
	// destroy. Create/destroy is serialized by the emulation lifecycle (one renderer at a
	// time), so no lock. Ported from ouroboros420/rpcsx (7a82c7647, 52a020464).
	inline std::vector<std::pair<VkInstance, VkSurfaceKHR>> g_wsi_surfaces;

	static inline void track_WSI_surface(VkInstance vk_instance, VkSurfaceKHR surface)
	{
		if (surface != VK_NULL_HANDLE)
		{
			g_wsi_surfaces.emplace_back(vk_instance, surface);
		}
	}

	// Destroy every surface tracked for this instance. Safe to call whenever no swapchain
	// is built on them; callers guarantee that.
	static inline void destroy_WSI_surfaces(VkInstance vk_instance)
	{
		for (auto it = g_wsi_surfaces.begin(); it != g_wsi_surfaces.end();)
		{
			if (it->first == vk_instance)
			{
				vkDestroySurfaceKHR(it->first, it->second, nullptr);
				it = g_wsi_surfaces.erase(it);
			}
			else
			{
				++it;
			}
		}
	}

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

		track_WSI_surface(vk_instance, result);
		return result;
	}
#endif
}
