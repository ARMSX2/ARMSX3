#pragma once

#include "../VulkanAPI.h"
#include "swapchain.h"

#include <algorithm>
#include <vector>

#ifdef __APPLE__
#include <MoltenVK/mvk_vulkan.h>
#include <MoltenVK/mvk_private_api.h>
#endif

namespace vk
{
	class supported_extensions
	{
	private:
		std::vector<VkExtensionProperties> m_vk_exts;

	public:
		enum enumeration_class
		{
			instance = 0,
			device   = 1
		};

		supported_extensions(enumeration_class _class, const char* layer_name = nullptr, VkPhysicalDevice pdev = VK_NULL_HANDLE);

		bool is_supported(std::string_view ext) const;
	};

	class instance
	{
	private:
		std::vector<physical_device> gpus;
		VkInstance m_instance = VK_NULL_HANDLE;
		VkSurfaceKHR m_surface = VK_NULL_HANDLE;

		PFN_vkDestroyDebugReportCallbackEXT _vkDestroyDebugReportCallback = nullptr;
		PFN_vkCreateDebugReportCallbackEXT _vkCreateDebugReportCallback = nullptr;
		VkDebugReportCallbackEXT m_debugger = nullptr;

		bool extensions_loaded = false;

	public:

		instance() = default;

		~instance();

		void destroy();

		// Rebuild the presentation surface against a new native window.
		//
		// Android destroys the ANativeWindow whenever the app leaves the
		// foreground, which permanently invalidates the VkSurfaceKHR built from
		// it -- every later swapchain call returns VK_ERROR_SURFACE_LOST. The
		// surface is created once in create_swapchain() and was never rebuilt,
		// so there was no way back from that.
		VkSurfaceKHR recreate_surface(display_handle_t window_handle);

		void enable_debugging();

		bool create(const char* app_name, bool fast = false);

		void bind();

		std::vector<physical_device>& enumerate_devices();

		swapchain_base* create_swapchain(display_handle_t window_handle, vk::physical_device& dev);
	};
}
