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
		// One pipeline per variant, each managed by get_compute_task<T> on its own type.
		//
		// Qualcomm ship the two as one file behind a UseEdgeDirection define, but a define is a
		// compile-time choice and this has to be switchable at runtime, so they are separate
		// shaders and separate tasks. Sharing a single task and swapping m_src would mean one
		// cached pipeline serving two different programs -- the second variant would silently run
		// the first one's code until something forced a rebuild.
		//
		// They differ only in shader source: the push constant layout is byte-identical (verified
		// against both compiled SPIR-V), so all the binding and dispatch plumbing is shared here.
		class sgsr_pass_base : public compute_task
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

		protected:
			// The variant's shader source. Passed up by each subclass rather than branched on,
			// so the pipeline that gets cached always matches the program that built it.
			explicit sgsr_pass_base(const char* shader_source);

		public:
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

		// Plain mobile variant: isotropic weights. Cheaper.
		class sgsr_pass : public sgsr_pass_base
		{
		public:
			sgsr_pass();
		};

		// Edge-direction variant: estimates the local edge direction and weights along it.
		// Qualcomm describe the difference as "a minimal cost increase" for better quality.
		class sgsr_edge_pass : public sgsr_pass_base
		{
		public:
			sgsr_edge_pass();
		};
	}

	class sgsr_upscale_pass : public upscaler
	{
		std::unique_ptr<vk::viewable_image> m_output_left;
		std::unique_ptr<vk::viewable_image> m_output_right;

		// Fixed at construction, because the present path rebuilds the upscaler whenever the
		// output scaling mode changes -- so the two variants can never share an instance and
		// there is nothing to re-check per frame.
		const bool m_edge_direction;

		void dispose_images();
		void initialize_image(u32 output_w, u32 output_h, rsx::flags32_t mode);

	public:
		explicit sgsr_upscale_pass(bool edge_direction)
			: m_edge_direction(edge_direction)
		{
		}

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
