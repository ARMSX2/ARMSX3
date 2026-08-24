#pragma once

#include "../vkutils/sampler.h"
#include "../VKCompute.h"

#include "upscaling.h"

namespace vk
{
	// Snapdragon Game Super Resolution 1.0, mobile variant.
	//
	// A single-pass edge-directed spatial upscaler, written by Qualcomm for Adreno. Against FSR1
	// it is one dispatch instead of two and one target instead of two, which is what makes it
	// worth having on a phone -- the filter is cheaper, not better.
	//
	// Its driver requirements are a strict subset of FSR1's: textureGather with a constant
	// component and no offset (core Vulkan 1.0; only gather-with-offsets needs
	// shaderImageGatherExtended), an rgba8 storage image (mandatory format support), one
	// descriptor set and 44 bytes of push constants. So there is deliberately no vendor gate --
	// anywhere FSR1 runs, this runs.
	namespace SGSR
	{
		class sgsr_pass : public compute_task
		{
			std::unique_ptr<vk::sampler> m_sampler;
			const vk::image_view* m_input_image = nullptr;
			const vk::image_view* m_output_image = nullptr;

			// 11 words. The layout is verified against the compiled SPIR-V rather than derived
			// from the struct, because a mismatch here produces garbage that looks exactly like a
			// shader bug: dstSize@0, uvOffset@8, uvScale@16, srcSize@24, invSrcSize@32,
			// edgeSharpness@40.
			std::array<u32, 11> m_constants_buf{};

			size2u m_input_size{};
			size2u m_output_size{};
			f32 m_uv_offset[2]{};
			f32 m_uv_scale[2]{};

			std::vector<glsl::program_input> get_inputs() override;
			void bind_resources(const vk::command_buffer&) override;

		public:
			sgsr_pass();

			// uv_offset/uv_scale map the DISPLAYED region inside the source texture. The RSX
			// output target is larger than the picture in it, so without this the filter would
			// upscale the padding as well as the image.
			void run(const vk::command_buffer& cmd,
				vk::viewable_image* src,
				vk::viewable_image* dst,
				const size2u& input_size,
				const size2u& output_size,
				const f32 uv_offset[2],
				const f32 uv_scale[2]);
		};
	}

	class sgsr_upscale_pass : public upscaler
	{
		std::unique_ptr<vk::viewable_image> m_output_left;
		std::unique_ptr<vk::viewable_image> m_output_right;

		void dispose_images();
		void initialize_image(u32 output_w, u32 output_h, rsx::flags32_t mode);

	public:
		vk::viewable_image* scale_output(
			const vk::command_buffer& cmd,
			vk::viewable_image* src,
			VkImage present_surface,
			VkImageLayout present_surface_layout,
			const VkImageBlit& request,
			rsx::flags32_t mode
		) override;
	};
}
