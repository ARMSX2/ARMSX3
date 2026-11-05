#pragma once

#ifdef HAVE_X11
#include <X11/Xutil.h>
#endif

#include "../../display.h"
#include "../VulkanAPI.h"
#include "image.h"

#include <memory>

namespace vk
{
	struct swapchain_image_WSI
	{
		VkImage value = VK_NULL_HANDLE;
	};

	class swapchain_image_RPCS3 : public image
	{
		std::unique_ptr<buffer> m_dma_buffer;
		u32 m_width = 0;
		u32 m_height = 0;

	public:
		swapchain_image_RPCS3(render_device& dev, const memory_type_mapping& memory_map, u32 width, u32 height);

		void do_dma_transfer(command_buffer& cmd);

		u32 get_required_memory_size() const;

		void* get_pixels();

		void free_pixels();
	};

	class swapchain_base
	{
	protected:
		render_device dev;

		display_handle_t window_handle{};
		u32 m_width = 0;
		u32 m_height = 0;
		VkFormat m_surface_format = VK_FORMAT_B8G8R8A8_UNORM;

		// Pre-rotation (see swapchain_WSI::init): clockwise quarter turns the images were created
		// with as their preTransform. 0 = not turned, the images are the window's own shape.
		u32 m_pre_rotation = 0;

		virtual void init_swapchain_images(render_device& dev, u32 count) = 0;

	public:
		swapchain_base(physical_device& gpu, u32 present_queue, u32 graphics_queue, u32 transfer_queue, VkFormat format = VK_FORMAT_B8G8R8A8_UNORM);

		virtual ~swapchain_base() = default;

		virtual void create(display_handle_t& handle) = 0;
		virtual void destroy(bool full = true) = 0;
		virtual bool init() = 0;

		virtual u32 get_swap_image_count() const = 0;
		virtual VkImage get_image(u32 index) = 0;
		// The fence is for callers that must block on the acquire rather than chain it into a
		// submit. Frame generation needs that: see present_generated_frame.
		virtual VkResult acquire_next_swapchain_image(VkSemaphore semaphore, u64 timeout, u32* result, VkFence fence = VK_NULL_HANDLE) = 0;
		virtual void end_frame(command_buffer& cmd, u32 index) = 0;
		virtual VkResult present(VkSemaphore semaphore, u32 index) = 0;
		virtual VkImageLayout get_optimal_present_layout() const = 0;

		virtual bool supports_automatic_wm_reports() const
		{
			return false;
		}

		bool init(u32 w, u32 h)
		{
			m_width = w;
			m_height = h;
			return init();
		}

		// The extent the swapchain ACTUALLY has, which is not always the one init() was
		// handed: the WSI backend replaces it with the surface's currentExtent whenever the
		// platform reports one. Anything sized against the swapchain images - framebuffers
		// above all - has to use this and not the requested value.
		u32 get_width() const
		{
			return m_width;
		}

		u32 get_height() const
		{
			return m_height;
		}

		// Pre-rotation. get_width()/get_height() stay the window's own shape, which is what the
		// frame is laid out and assembled in; a pre-rotated swapchain's IMAGES are turned, and a
		// quarter turn swaps their axes. Only what writes into a swapchain image directly needs
		// these (see VKGSRender::flip).
		u32 get_pre_rotation() const
		{
			return m_pre_rotation;
		}

		u32 get_image_width() const
		{
			return (m_pre_rotation & 1) ? m_height : m_width;
		}

		u32 get_image_height() const
		{
			return (m_pre_rotation & 1) ? m_width : m_height;
		}

		// Whether the surface now asks for a different rotation than it did when this swapchain was
		// built (the device was turned from one landscape to the other, which keeps the size).
		virtual bool surface_rotation_changed()
		{
			return false;
		}

		const vk::render_device& get_device()
		{
			return dev;
		}

		VkFormat get_surface_format() const
		{
			return m_surface_format;
		}

		bool is_headless() const
		{
			return (dev.get_present_queue() == VK_NULL_HANDLE);
		}
	};

	template<typename T>
	class abstract_swapchain_impl : public swapchain_base
	{
	protected:
		std::vector<T> swapchain_images;

	public:
		abstract_swapchain_impl(physical_device& gpu, u32 present_queue, u32 graphics_queue, u32 transfer_queue, VkFormat format = VK_FORMAT_B8G8R8A8_UNORM)
			: swapchain_base(gpu, present_queue, graphics_queue, transfer_queue, format)
		{}

		~abstract_swapchain_impl() override = default;

		u32 get_swap_image_count() const override
		{
			return ::size32(swapchain_images);
		}

		using swapchain_base::init;
	};

	using WSI_swapchain_base = abstract_swapchain_impl<swapchain_image_WSI>;

	class native_swapchain_base : public abstract_swapchain_impl<std::pair<bool, std::unique_ptr<swapchain_image_RPCS3>>>
	{
	public:
		using abstract_swapchain_impl::abstract_swapchain_impl;

		VkResult acquire_next_swapchain_image(VkSemaphore semaphore, u64 timeout, u32* result, VkFence fence) override;

		// Clients must implement these methods to render without WSI support
		bool init() override
		{
			fmt::throw_exception("Native swapchain is not implemented yet!");
		}

		void create(display_handle_t& /*window_handle*/) override
		{
			fmt::throw_exception("Native swapchain is not implemented yet!");
		}

		void destroy(bool /*full*/ = true) override
		{
			fmt::throw_exception("Native swapchain is not implemented yet!");
		}

		VkResult present(VkSemaphore /*semaphore*/, u32 /*index*/) override
		{
			fmt::throw_exception("Native swapchain is not implemented yet!");
		}

		// Generic accessors
		void end_frame(command_buffer& cmd, u32 index) override
		{
			swapchain_images[index].second->do_dma_transfer(cmd);
		}

		VkImage get_image(u32 index) override
		{
			return swapchain_images[index].second->value;
		}

		VkImageLayout get_optimal_present_layout() const override
		{
			return VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		}

	protected:
		void init_swapchain_images(render_device& dev, u32 preferred_count) override;
	};

	class swapchain_WSI : public WSI_swapchain_base
	{
		VkSurfaceKHR m_surface = VK_NULL_HANDLE;

		// Set when a surface query inside init() came back VK_ERROR_SURFACE_LOST_KHR.
		//
		// init() can only report failure as a bool, and the caller has to tell "the window is
		// minimized, try again later" apart from "the VkSurfaceKHR is dead and has to be recreated
		// before any retry can work". Without that distinction the retry queries the same dead
		// surface forever.
		bool m_surface_is_lost = false;

	public:
		// True when the last init() failure was a lost surface. Cleared by the next successful init.
		bool surface_is_lost() const { return m_surface_is_lost; }

		// Tear down ONLY the VkSwapchainKHR, keeping the device and everything else.
		//
		// Must run before the surface it was created from is destroyed: a
		// VkSurfaceKHR with a live swapchain still referencing it may not be
		// destroyed, and doing so faults inside the driver on the next present.
		// init() tolerates a null m_vk_swapchain (its oldSwapchain path is guarded),
		// so the next rebuild is unaffected.
		void destroy_swapchain_only();

		// Point this swapchain at a freshly created surface. Only meaningful after
		// the old one was lost, and only once destroy_swapchain_only() has run.
		void replace_surface(VkSurfaceKHR surface) { m_surface = surface; }

	protected:
		VkColorSpaceKHR m_color_space = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
		VkSwapchainKHR m_vk_swapchain = nullptr;

		PFN_vkCreateSwapchainKHR _vkCreateSwapchainKHR = nullptr;
		PFN_vkDestroySwapchainKHR _vkDestroySwapchainKHR = nullptr;
		PFN_vkGetSwapchainImagesKHR _vkGetSwapchainImagesKHR = nullptr;
		PFN_vkAcquireNextImageKHR _vkAcquireNextImageKHR = nullptr;
		PFN_vkQueuePresentKHR _vkQueuePresentKHR = nullptr;

		bool m_wm_reports_flag = false;

		// The surface's currentTransform when this swapchain was built, whether or not it could be
		// pre-rotated to it. See surface_rotation_changed().
		VkSurfaceTransformFlagBitsKHR m_built_for_transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;

	protected:
		void init_swapchain_images(render_device& dev, u32 preferred_count = 0) override;

	public:
		swapchain_WSI(vk::physical_device& gpu, u32 present_queue, u32 graphics_queue, u32 transfer_queue, VkFormat format, VkSurfaceKHR surface, VkColorSpaceKHR color_space, bool force_wm_reporting_off);

		~swapchain_WSI() override = default;

		void create(display_handle_t&) override
		{}

		void destroy(bool = true) override;

		std::pair<VkSurfaceCapabilitiesKHR, bool> init_surface_capabilities();

		using WSI_swapchain_base::init;
		bool init() override;

		bool supports_automatic_wm_reports() const override
		{
			return m_wm_reports_flag;
		}

		bool surface_rotation_changed() override;

		VkResult acquire_next_swapchain_image(VkSemaphore semaphore, u64 timeout, u32* result, VkFence fence) override
		{
			return vkAcquireNextImageKHR(dev, m_vk_swapchain, timeout, semaphore, fence, result);
		}

		void end_frame(command_buffer& /*cmd*/, u32 /*index*/) override
		{}

		VkResult present(VkSemaphore semaphore, u32 image) override;

		VkImage get_image(u32 index) override
		{
			return swapchain_images[index].value;
		}

		VkImageLayout get_optimal_present_layout() const override
		{
			return VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
		}
	};

	struct WSI_config
	{
		bool supports_automatic_wm_reports = true;
	};
}
