#pragma once

#include "../VulkanAPI.h"
#include "chip_class.h"
#include "pipeline_binding_table.h"
#include "memory.h"

#include <string>
#include <vector>
#include <unordered_map>

#define DESCRIPTOR_MAX_DRAW_CALLS 32768

namespace vk
{
	struct gpu_formats_support
	{
		bool d24_unorm_s8 : 1;
		bool d32_sfloat_s8 : 1;
		bool bgra8_linear : 1;
		bool argb8_linear : 1;
	};

	struct gpu_shader_types_support
	{
		bool allow_float64 : 1;
		bool allow_float16 : 1;
		bool allow_int8 : 1;
	};

	struct memory_type_mapping
	{
		std::vector<memory_heap_info> heaps;

		memory_type_info host_visible_coherent;
		memory_type_info device_local;
		memory_type_info device_bar;

		u64 device_local_total_bytes;
		u64 host_visible_total_bytes;
		u64 device_bar_total_bytes;
	};

	struct descriptor_indexing_features
	{
		bool supported = false;
		u64 update_after_bind_mask = 0;

		descriptor_indexing_features(bool supported = false)
			: supported(supported) {}

		operator bool() const { return supported; }
	};

	struct custom_border_color_features
	{
		bool supported = false;
		bool swizzle_extension_supported = false;
		bool require_border_color_remap = true;  // Assume that without the swizzle extension and explicit remap the device just samples border color as replacement.

		operator bool() const { return supported; }
	};

	struct multidraw_features
	{
		bool supported;
		u32 max_batch_size;

		operator bool() const { return supported; }
	};

	class physical_device
	{
		VkInstance parent = VK_NULL_HANDLE;
		VkPhysicalDevice dev = VK_NULL_HANDLE;
		VkPhysicalDeviceProperties props;
		VkPhysicalDeviceFeatures features;
		VkPhysicalDeviceMemoryProperties memory_properties;
		std::vector<VkQueueFamilyProperties> queue_props;

		mutable std::unordered_map<VkFormat, VkFormatProperties> format_properties;
		gpu_shader_types_support shader_types_support{};
		VkPhysicalDeviceDriverPropertiesKHR driver_properties{};

		u32 descriptor_max_draw_calls = DESCRIPTOR_MAX_DRAW_CALLS;
		descriptor_indexing_features descriptor_indexing_support{};

		custom_border_color_features custom_border_color_support{};
		// VK_EXT_shader_uniform_buffer_unsized_array. RPCS3's shaders declare
		// runtime-sized arrays inside uniform blocks, which needs this.
		bool unsized_array_support = false;
		// Largest range bindable as a uniform buffer. When unsized_array_support is
		// false the shader generators need it to emit a concrete array bound: the
		// data heaps bind a window of exactly this size and index into it, so
		// (this / element size) IS the highest index a shader can legally see.
		u32 max_ubo_range = 16384;

		multidraw_features multidraw_support{};

		struct
		{
			bool barycentric_coords = false;
			bool conditional_rendering = false;
			bool debug_utils = false;
			bool external_memory_host = false;
			bool extended_dynamic_state = false;
			bool framebuffer_loops = false;
			bool memory_budget = false;
			bool shader_stencil_export = false;
			bool surface_capabilities_2 = false;
			bool synchronization_2 = false;
			bool unrestricted_depth_range = false;

			// VK_ANDROID_external_memory_android_hardware_buffer.
			//
			// Gates frame generation. framegen runs on its OWN VkDevice, so images cannot be
			// shared as VkImage -- they have to go across as AHardwareBuffer, and importing one
			// needs this. framegen's other sharing path uses vkGetMemoryFdKHR(OPAQUE_FD), which
			// both Adreno and Mali refuse for AHB-backed memory, so there is no fallback.
			bool external_memory_ahb = false;

			// What the Lossless Scaling shaders themselves need, independent of how the images
			// are shared. Eden gates on exactly these two; see the note in device.cpp.
			bool vulkan_memory_model = false;
			bool null_descriptor = false;
			bool extended_device_fault = false;
			bool texture_compression_bc = false;
			bool portability = false;
		} optional_features_support;

		friend class render_device;
	private:
		void get_physical_device_features(bool allow_extensions);
		void get_physical_device_properties_0(bool allow_extensions);
		void get_physical_device_properties_1(bool allow_extensions);

	public:

		physical_device() = default;
		~physical_device() = default;

		void create(VkInstance context, VkPhysicalDevice pdev, bool allow_extensions);

		std::string get_name() const;

		driver_vendor get_driver_vendor() const;
		std::string get_driver_version() const;
		chip_class get_chip_class() const;

		u32 get_queue_count() const;

		// Device properties. These structs can be large so use with care.
		const VkQueueFamilyProperties& get_queue_properties(u32 queue);
		const VkPhysicalDeviceMemoryProperties& get_memory_properties() const;
		const VkPhysicalDeviceLimits& get_limits() const;

		operator VkPhysicalDevice() const;
		operator VkInstance() const;

		bool is_integrated_gpu() const { return props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU; }
	};

	/**
	 * Memory the GPU pools may size themselves against.
	 *
	 * On a discrete GPU this is simply the device local heap: a 3GB texture cache out of 8GB
	 * of dedicated VRAM costs system RAM nothing. A phone has one pool for both, so
	 * device_local_total_bytes reports system RAM, and sizing caches against it budgets
	 * memory the OS also needs.
	 *
	 * Measured: the texture cache quota resolved to 2978MB on a 7.4GB device, the process
	 * climbed to 4.3GB, drove available memory to 54MB and 3GB of swap, and was killed
	 * mid-session.
	 */
	u64 get_budgetable_device_memory(u64 device_local_total);

	class render_device
	{
		physical_device* pgpu = nullptr;
		memory_type_mapping memory_map{};
		gpu_formats_support m_formats_support{};
		std::unique_ptr<mem_allocator_base> m_allocator;
		VkDevice dev = VK_NULL_HANDLE;

		VkQueue m_graphics_queue = VK_NULL_HANDLE;
		VkQueue m_present_queue = VK_NULL_HANDLE;
		VkQueue m_transfer_queue = VK_NULL_HANDLE;

		u32 m_graphics_queue_family = 0;
		u32 m_present_queue_family = 0;
		u32 m_transfer_queue_family = 0;

		void dump_debug_info(
			const std::vector<const char*>& requested_extensions,
			const VkPhysicalDeviceFeatures& requested_features) const;

	public:
		render_device() = default;
		~render_device() = default;

		void create(vk::physical_device& pdev, u32 graphics_queue_idx, u32 present_queue_idx, u32 transfer_queue_idx);
		void destroy();

		const VkFormatProperties get_format_properties(VkFormat format) const;

		bool get_compatible_memory_type(u32 typeBits, u32 desired_mask, u32* type_index) const;
		void rebalance_memory_type_usage();

		const physical_device& gpu() const { return *pgpu; }
		const memory_type_mapping& get_memory_mapping() const { return memory_map; }
		const gpu_formats_support& get_formats_support() const { return m_formats_support; }
		const gpu_shader_types_support& get_shader_types_support() const { return pgpu->shader_types_support; }
		const custom_border_color_features& get_custom_border_color_support() const { return pgpu->custom_border_color_support; }
		bool get_unsized_array_support() const { return pgpu->unsized_array_support; }

		/**
		 * Array bound to emit for a runtime-sized uniform-block array when the
		 * device cannot do unsized ones. The heaps bind a window of
		 * maxUniformBufferRange and the shader indexes it as
		 * (dynamic_offset / element_size), so this is exactly the highest index
		 * reachable -- not an estimate.
		 */
		u32 ubo_array_bound(u32 element_size) const
		{
			return std::max<u32>(1u, pgpu->max_ubo_range / element_size);
		}
		const multidraw_features get_multidraw_support() const { return pgpu->multidraw_support; }

		bool get_shader_stencil_export_support() const { return pgpu->optional_features_support.shader_stencil_export; }
		bool get_depth_bounds_support() const { return pgpu->features.depthBounds != VK_FALSE; }
		bool get_alpha_to_one_support() const { return pgpu->features.alphaToOne != VK_FALSE; }
		bool get_anisotropic_filtering_support() const { return pgpu->features.samplerAnisotropy != VK_FALSE; }
		bool get_wide_lines_support() const { return pgpu->features.wideLines != VK_FALSE; }
		bool get_conditional_render_support() const { return pgpu->optional_features_support.conditional_rendering; }

		// Topology, cull mode, front face and the depth test are set per draw instead of being
		// baked into a pipeline object. That is what keeps the permutation count down on mobile,
		// where every extra pipeline is a compile stall the first time it is seen and another
		// entry in a cache that already takes minutes to warm. Everything keyed on this must have
		// a static fallback: the extension is core in 1.3 but plenty of shipped Android 11/13
		// drivers predate it.
		bool get_extended_dynamic_state_support() const { return pgpu->optional_features_support.extended_dynamic_state; }

		bool get_unrestricted_depth_range_support() const { return pgpu->optional_features_support.unrestricted_depth_range; }
		bool get_external_memory_ahb_support() const { return pgpu->optional_features_support.external_memory_ahb; }
		bool get_vulkan_memory_model_support() const { return pgpu->optional_features_support.vulkan_memory_model; }
		bool get_null_descriptor_support() const { return pgpu->optional_features_support.null_descriptor; }
		bool get_external_memory_host_support() const { return pgpu->optional_features_support.external_memory_host; }
		bool get_memory_budget_support() const { return pgpu->optional_features_support.memory_budget; }
		bool get_surface_capabilities_2_support() const { return pgpu->optional_features_support.surface_capabilities_2; }
		bool get_debug_utils_support() const { return g_cfg.video.renderdoc_compatiblity && pgpu->optional_features_support.debug_utils; }
		bool get_framebuffer_loops_support() const { return pgpu->optional_features_support.framebuffer_loops; }
		bool get_barycoords_support() const { return pgpu->optional_features_support.barycentric_coords; }
		bool get_synchronization2_support() const { return pgpu->optional_features_support.synchronization_2; }
		bool get_extended_device_fault_support() const { return pgpu->optional_features_support.extended_device_fault; }
		bool get_texture_compression_bc_support() const { return pgpu->optional_features_support.texture_compression_bc; }

		u64 get_descriptor_update_after_bind_support() const { return pgpu->descriptor_indexing_support.update_after_bind_mask; }
		u32 get_descriptor_max_draw_calls() const { return pgpu->descriptor_max_draw_calls; }

		VkQueue get_present_queue() const { return m_present_queue; }
		VkQueue get_graphics_queue() const { return m_graphics_queue; }
		VkQueue get_transfer_queue() const { return m_transfer_queue; }
		u32 get_graphics_queue_family() const { return m_graphics_queue_family; }
		u32 get_present_queue_family() const { return m_graphics_queue_family; }
		u32 get_transfer_queue_family() const { return m_transfer_queue_family; }

		mem_allocator_base* get_allocator() const { return m_allocator.get(); }

		operator VkDevice() const { return dev; }
	};

	memory_type_mapping get_memory_mapping(const physical_device& dev);
	gpu_formats_support get_optimal_tiling_supported_formats(const physical_device& dev);

	extern const render_device* g_render_device;
}
