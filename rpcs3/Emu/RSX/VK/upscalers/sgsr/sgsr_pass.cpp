// Snapdragon Game Super Resolution, suggested by CamilleLaVey (Eden), who made the same filter
// work there first and pointed it out as the right upscaler for mobile.
//
// The filter is Qualcomm's BSD-3-Clause release (see the shader for its copyright notice). None
// of Eden's code is used: Eden is GPL-3.0-or-later and RPCS3 is GPL-2.0-only, so the crop mapping
// and the widened sharpness range are reimplemented from a description of what they do.

#include "../../vkutils/barriers.h"
#include "../../VKHelpers.h"
#include "../../VKResourceManager.h"

#include "../sgsr_pass.h"

#include "Emu/system_config.h"

namespace vk
{
	namespace SGSR
	{
		sgsr_pass_base::sgsr_pass_base(const char* shader_source)
		{
			m_src = shader_source;

			// Fill with 0 to avoid sending incomplete/unused variables to the GPU
			std::fill(m_constants_buf.begin(), m_constants_buf.end(), 0u);

			ssbo_count = 0;

			use_push_constants = true;
			push_constants_size = 44;

			create();
		}

		std::vector<glsl::program_input> sgsr_pass_base::get_inputs()
		{
			std::vector<vk::glsl::program_input> inputs =
			{
				glsl::program_input::make(
					::glsl::program_domain::glsl_compute_program,
					"InputTexture",
					vk::glsl::input_type_texture,
					0,
					0
				),

				glsl::program_input::make(
					::glsl::program_domain::glsl_compute_program,
					"OutputTexture",
					vk::glsl::input_type_storage_texture,
					0,
					1
				),
			};

			auto result = compute_task::get_inputs();
			result.insert(result.end(), inputs.begin(), inputs.end());
			return result;
		}

		void sgsr_pass_base::bind_resources(const vk::command_buffer& /*cmd*/)
		{
			if (!m_sampler)
			{
				const auto pdev = vk::get_current_renderer();
				m_sampler = std::make_unique<vk::sampler>(*pdev,
					VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
					VK_FALSE, 0.f, 1.f, 0.f, 0.f, VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK);
			}

			m_program->bind_uniform({ *m_input_image, *m_sampler }, 0, 0);
			m_program->bind_uniform({ *m_output_image }, 0, 1);
		}

		void sgsr_pass_base::run(const vk::command_buffer& cmd,
			vk::viewable_image* src,
			vk::viewable_image* dst,
			const size2u& input_size,
			const size2u& output_size,
			const f32 uv_offset[2],
			const f32 uv_scale[2])
		{
			m_input_image = src->get_view(rsx::default_remap_vector.with_encoding(VK_REMAP_IDENTITY));
			m_output_image = dst->get_view(rsx::default_remap_vector.with_encoding(VK_REMAP_IDENTITY));

			m_input_size = input_size;
			m_output_size = output_size;
			m_uv_offset[0] = uv_offset[0];
			m_uv_offset[1] = uv_offset[1];
			m_uv_scale[0] = uv_scale[0];
			m_uv_scale[1] = uv_scale[1];

			// Laid out by hand against the offsets read out of the compiled SPIR-V. Writing it
			// through a struct would be tidier and would also be the thing that silently breaks
			// if the shader's members are ever reordered.
			const f32 src_w = static_cast<f32>(src->width());
			const f32 src_h = static_cast<f32>(src->height());

			auto write_u32 = [this](usz word, u32 v) { m_constants_buf[word] = v; };
			auto write_f32 = [this](usz word, f32 v) { m_constants_buf[word] = std::bit_cast<u32>(v); };

			write_u32(0, output_size.width);            // dstSize.x   @0
			write_u32(1, output_size.height);           // dstSize.y   @4
			write_f32(2, m_uv_offset[0]);               // uvOffset    @8
			write_f32(3, m_uv_offset[1]);
			write_f32(4, m_uv_scale[0]);                // uvScale     @16
			write_f32(5, m_uv_scale[1]);
			write_f32(6, src_w);                        // srcSize     @24
			write_f32(7, src_h);
			write_f32(8, 1.f / src_w);                  // invSrcSize  @32
			write_f32(9, 1.f / src_h);

			// Qualcomm's edge_sharpness is 0..2 with 1.0 as their default, and the existing
			// 0..100 slider covers that whole range: each UI percent is worth 0.02 of edge
			// sharpness, so 50% is Qualcomm's default and 100% is the widened top end. The slider
			// itself needs no special casing -- one control for both upscalers, because they want
			// the same thing from the user and a second one only lets them disagree.
			write_f32(10, g_cfg.video.rcas_sharpening_intensity * 0.02f);

			if (!m_program)
			{
				load_program(cmd);
			}

			ensure(push_constants_size <= (m_constants_buf.size() * sizeof(decltype(m_constants_buf)::value_type)));
			vkCmdPushConstants(cmd, m_program->layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push_constants_size, m_constants_buf.data());

			// 8x8 workgroups, rounded up. The shader bounds-checks its invocation because of this.
			constexpr auto wg_size = 8;
			const auto invocations_x = utils::aligned_div(output_size.width, wg_size);
			const auto invocations_y = utils::aligned_div(output_size.height, wg_size);

			compute_task::run(cmd, invocations_x, invocations_y, 1);
		}


		sgsr_pass::sgsr_pass()
			: sgsr_pass_base(
				#include "Emu/RSX/Program/Upscalers/SGSR/sgsr_shader.glsl"
			)
		{
		}

		sgsr_edge_pass::sgsr_edge_pass()
			: sgsr_pass_base(
				#include "Emu/RSX/Program/Upscalers/SGSR/sgsr_shader_edge.glsl"
			)
		{
		}
	}

	void sgsr_upscale_pass::dispose_images()
	{
		auto safe_delete = [](auto& data)
		{
			if (data) vk::get_resource_manager()->dispose(data);
		};

		safe_delete(m_output_left);
		safe_delete(m_output_right);
	}

	void sgsr_upscale_pass::initialize_image(u32 output_w, u32 output_h, rsx::flags32_t mode)
	{
		dispose_images();

		const auto pdev = vk::get_current_renderer();
		auto initialize_image_impl = [pdev, output_w, output_h](VkImageUsageFlags usage, VkFormat format)
		{
			return std::make_unique<vk::viewable_image>(
				*pdev,
				pdev->get_memory_mapping().device_local,
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
				VK_IMAGE_TYPE_2D,
				format,
				output_w, output_h, 1, 1, 1, VK_SAMPLE_COUNT_1_BIT,
				VK_IMAGE_LAYOUT_UNDEFINED,
				VK_IMAGE_TILING_OPTIMAL,
				usage,
				VK_IMAGE_CREATE_ALLOW_NULL_RPCS3,
				VMM_ALLOCATION_POOL_SWAPCHAIN,
				RSX_FORMAT_CLASS_COLOR);
		};

		const VkFlags usage_mask_output = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

		VkFormat data_format = VK_FORMAT_UNDEFINED;
		bool failed = true;

		std::array<VkFormat, 2> supported_formats = { VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM };
		for (const auto& format : supported_formats)
		{
			const VkFlags all_required_bits = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
			if ((pdev->get_format_properties(format).optimalTilingFeatures & all_required_bits) == all_required_bits)
			{
				data_format = format;
				failed = false;
				break;
			}
		}

		if (failed)
		{
			rsx_log.error("SGSR: no writable output format is available; falling back to bilinear.");
			return;
		}

		if (mode & UPSCALE_LEFT_VIEW)
		{
			m_output_left = initialize_image_impl(usage_mask_output, data_format);
		}

		if (mode & UPSCALE_RIGHT_VIEW)
		{
			m_output_right = initialize_image_impl(usage_mask_output, data_format);
		}
	}

	vk::viewable_image* sgsr_upscale_pass::scale_output(
		const vk::command_buffer& cmd,
		vk::viewable_image* src,
		VkImage present_surface,
		VkImageLayout present_surface_layout,
		const VkImageBlit& request,
		rsx::flags32_t mode)
	{
		size2u input_size, output_size;
		input_size.width = std::abs(request.srcOffsets[1].x - request.srcOffsets[0].x);
		input_size.height = std::abs(request.srcOffsets[1].y - request.srcOffsets[0].y);
		output_size.width = std::abs(request.dstOffsets[1].x - request.dstOffsets[0].x);
		output_size.height = std::abs(request.dstOffsets[1].y - request.dstOffsets[0].y);

		auto src_image = src;
		auto output_request = request;

		// Only does anything when the rendered frame is SMALLER than the output. At or above the
		// output resolution there is nothing to reconstruct, so it correctly does nothing -- which
		// is indistinguishable from broken from the outside, hence the note in the setting text.
		if (input_size.width < output_size.width && input_size.height < output_size.height)
		{
			ensure((mode & (UPSCALE_LEFT_VIEW | UPSCALE_RIGHT_VIEW)) != (UPSCALE_LEFT_VIEW | UPSCALE_RIGHT_VIEW));

			auto& m_output_data = (mode & UPSCALE_LEFT_VIEW) ? m_output_left : m_output_right;
			if (!m_output_data || m_output_data->width() != output_size.width || m_output_data->height() != output_size.height)
			{
				initialize_image(output_size.width, output_size.height, mode);
			}

			if (m_output_data)
			{
				// Each variant is its own type, so get_compute_task hands back its own cached
				// pipeline. Swapping m_src on a shared task would leave one cached pipeline
				// serving two different programs.
				vk::SGSR::sgsr_pass_base* cs_task = m_edge_direction
					? static_cast<vk::SGSR::sgsr_pass_base*>(vk::get_compute_task<vk::SGSR::sgsr_edge_pass>())
					: static_cast<vk::SGSR::sgsr_pass_base*>(vk::get_compute_task<vk::SGSR::sgsr_pass>());

				// The displayed picture is a rectangle inside a larger target, so hand the shader
				// the crop rather than letting it assume the whole texture. Taken from the blit
				// request, which is the only thing that knows where the picture actually is.
				const f32 src_w = static_cast<f32>(src->width());
				const f32 src_h = static_cast<f32>(src->height());
				const f32 x0 = static_cast<f32>(std::min(request.srcOffsets[0].x, request.srcOffsets[1].x));
				const f32 y0 = static_cast<f32>(std::min(request.srcOffsets[0].y, request.srcOffsets[1].y));

				const f32 uv_offset[2] = { x0 / src_w, y0 / src_h };
				const f32 uv_scale[2] = { input_size.width / src_w, input_size.height / src_h };

				src->push_layout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				m_output_data->change_layout(cmd, VK_IMAGE_LAYOUT_GENERAL);

				cs_task->run(cmd, src, m_output_data.get(), input_size, output_size, uv_offset, uv_scale);

				src->pop_layout(cmd);

				src_image = m_output_data.get();

				if (mode & UPSCALE_AND_COMMIT)
				{
					vk::insert_image_memory_barrier(cmd,
						m_output_data->value,
						m_output_data->current_layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
						VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
						VK_PIPELINE_STAGE_TRANSFER_BIT,
						VK_ACCESS_SHADER_WRITE_BIT,
						VK_ACCESS_TRANSFER_READ_BIT,
						{ VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 });

					m_output_data->current_layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

					output_request.srcOffsets[0].x = 0;
					output_request.srcOffsets[1].x = output_size.width;
					output_request.srcOffsets[0].y = 0;
					output_request.srcOffsets[1].y = output_size.height;

					// Preserve mirroring/flipping
					if (request.srcOffsets[0].x > request.srcOffsets[1].x)
					{
						std::swap(output_request.srcOffsets[0].x, output_request.srcOffsets[1].x);
					}

					if (request.srcOffsets[0].y > request.srcOffsets[1].y)
					{
						std::swap(output_request.srcOffsets[0].y, output_request.srcOffsets[1].y);
					}
				}
			}
		}

		if (mode & UPSCALE_AND_COMMIT)
		{
			src_image->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			vkCmdBlitImage(cmd, src_image->value, src_image->current_layout, present_surface, present_surface_layout, 1, &output_request, VK_FILTER_LINEAR);
			src_image->pop_layout(cmd);
			return nullptr;
		}

		return src_image;
	}
}
