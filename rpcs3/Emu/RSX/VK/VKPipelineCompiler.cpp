#include "stdafx.h"
#include "VKPipelineCompiler.h"
#include "VKRenderPass.h"
#include "vkutils/device.h"
#include "Utilities/Thread.h"

#include "util/sysinfo.hpp"

namespace vk
{
	// Global list of worker threads
	std::unique_ptr<named_thread_group<pipe_compiler>> g_pipe_compilers;
	int g_num_pipe_compilers = 0;
	atomic_t<int> g_compiler_index{};

	static bool extended_dynamic_state_active()
	{
		// Called from the shader cache loader and the interpreter preloader as well as the draw
		// path, and the first of those can run before a device exists.
		return g_render_device && g_render_device->get_extended_dynamic_state_support();
	}

	VkPrimitiveTopology get_pipeline_topology(VkPrimitiveTopology topology, VkBool32 primitive_restart)
	{
		if (!extended_dynamic_state_active())
		{
			return topology;
		}

		// vkCmdSetPrimitiveTopology can only move within the topology CLASS the pipeline object
		// was created with. Crossing classes needs dynamicPrimitiveTopologyUnrestricted, which
		// comes from extended_dynamic_state3 and is not something mobile drivers report, so the
		// class has to stay part of the pipeline identity. What collapses is the member inside
		// it: list, strip and fan share one triangle pipeline instead of three.
		//
		// Which member stands for the class is not a free choice. Primitive restart is illegal on
		// a *_LIST topology unless primitiveTopologyListRestart is enabled, which it is not here,
		// so a restarting draw has to be represented by the strip form or pipelines that build
		// today would start failing validation.
		switch (topology)
		{
		case VK_PRIMITIVE_TOPOLOGY_LINE_LIST:
		case VK_PRIMITIVE_TOPOLOGY_LINE_STRIP:
			return primitive_restart ? VK_PRIMITIVE_TOPOLOGY_LINE_STRIP : VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
		case VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST:
		case VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP:
		case VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN:
			return primitive_restart ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
		default:
			// Points have a class of one, and adjacency/patch topologies never come out of the
			// RSX decoder. Anything unexpected keeps its exact value rather than being guessed at.
			return topology;
		}
	}

	void normalize_dynamic_pipeline_state(pipeline_props& props)
	{
		if (!extended_dynamic_state_active())
		{
			return;
		}

		props.state.ia.topology = get_pipeline_topology(props.state.ia.topology, props.state.ia.primitiveRestartEnable);

		// The replacements are exactly what graphics_pipeline_state's constructor leaves behind,
		// so two props that differ only in these fields memcmp equal without operator== having to
		// learn about the extension -- and without the disk cache's raw struct changing shape.
		props.state.rs.cullMode  = VK_CULL_MODE_NONE;
		props.state.rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

		props.state.ds.depthTestEnable  = VK_FALSE;
		props.state.ds.depthWriteEnable = VK_FALSE;
		props.state.ds.depthCompareOp   = VK_COMPARE_OP_NEVER;
	}

	pipe_compiler::pipe_compiler()
	{
		// TODO: Initialize workqueue
	}

	pipe_compiler::~pipe_compiler()
	{
		// TODO: Destroy and do cleanup
	}

	void pipe_compiler::initialize(const vk::render_device* pdev)
	{
		m_device = pdev;
	}

	void pipe_compiler::operator()()
	{
		// Keep shader compilation off the cores the frame depends on.
		//
		// These workers never set an affinity at all, so on a big.LITTLE phone the scheduler was
		// free to put a compile burst on the prime core -- the same core RSX needs, and the same
		// cluster the SPUs were deliberately fenced into. That is what a shader "dip" is: not the
		// renderer waiting on a pipeline (async modes never stall for one), but the compile work
		// competing for the cores the emulator is already short of.
		//
		// thread_class::general is the existing policy for exactly this, and it already returns
		// the little cluster on big.LITTLE and every core on a uniform machine, so this changes
		// nothing on desktop.
		thread_ctrl::set_thread_affinity_mask(thread_ctrl::get_affinity_mask(thread_class::general));

		while (thread_ctrl::state() != thread_state::aborting)
		{
			for (auto&& job : m_work_queue.pop_all())
			{
				if (!job.is_graphics_job)
				{
					auto compiled = int_compile_compute_pipe(job.compute_data, job.inputs, job.flags);
					job.callback_func(compiled);
					continue;
				}

				if (job.create_info_func)
				{
					auto compiled = int_compile_graphics_pipe(job.create_info_func, job.inputs, {}, job.flags);
					job.callback_func(compiled);
					continue;
				}

				auto compiled = int_compile_graphics_pipe(job.graphics_data, job.graphics_modules, job.inputs, {}, job.flags);
				job.callback_func(compiled);
			}

			thread_ctrl::wait_on(m_work_queue);
		}
	}

	std::unique_ptr<glsl::program> pipe_compiler::int_compile_compute_pipe(
		const VkComputePipelineCreateInfo& create_info,
		const std::vector<glsl::program_input>& cs_inputs,
		op_flags flags)
	{
		auto program = std::make_unique<glsl::program>(*m_device, create_info, cs_inputs);
		program->link(flags & SEPARATE_SHADER_OBJECTS);
		return program;
	}

	std::unique_ptr<glsl::program> pipe_compiler::int_compile_graphics_pipe(
		const VkGraphicsPipelineCreateInfo& create_info,
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs,
		op_flags flags)
	{
		auto program = std::make_unique<glsl::program>(*m_device, create_info, vs_inputs, fs_inputs);
		program->link(flags & SEPARATE_SHADER_OBJECTS);
		return program;
	}

	std::unique_ptr<glsl::program> pipe_compiler::int_compile_graphics_pipe(
		const vk::pipeline_props &create_info,
		VkShaderModule modules[2],
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs,
		op_flags flags)
	{
		VkPipelineShaderStageCreateInfo shader_stages[2] = {};
		shader_stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
		shader_stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
		shader_stages[0].module = modules[0];
		shader_stages[0].pName = "main";

		shader_stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
		shader_stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
		shader_stages[1].module = modules[1];
		shader_stages[1].pName = "main";

		std::vector<VkDynamicState> dynamic_state_descriptors;
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_VIEWPORT);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_SCISSOR);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_LINE_WIDTH);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_BLEND_CONSTANTS);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_STENCIL_WRITE_MASK);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_STENCIL_REFERENCE);
		dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_DEPTH_BIAS);

		// Must agree exactly with normalize_dynamic_pipeline_state(): a state declared here but
		// still present in the key costs a vkCmdSet* for no reduction, and a state erased from
		// the key but NOT declared here renders with whatever value the pipeline happened to be
		// built with -- wrong culling and wrong depth compare on geometry that now shares an
		// object with unrelated draws.
		if (extended_dynamic_state_active())
		{
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY_EXT);
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_CULL_MODE_EXT);
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_FRONT_FACE_EXT);
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE_EXT);
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE_EXT);
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_DEPTH_COMPARE_OP_EXT);
		}

		auto pdss = &create_info.state.ds;
		VkPipelineDepthStencilStateCreateInfo ds2;
		if (g_render_device->get_depth_bounds_support()) [[likely]]
		{
			dynamic_state_descriptors.push_back(VK_DYNAMIC_STATE_DEPTH_BOUNDS);
		}
		else if (pdss->depthBoundsTestEnable)
		{
			rsx_log.warning("Depth bounds test is enabled in the pipeline object but not supported by the current driver.");

			ds2 = *pdss;
			pdss = &ds2;
			ds2.depthBoundsTestEnable = VK_FALSE;
		}

		VkPipelineDynamicStateCreateInfo dynamic_state_info = {};
		dynamic_state_info.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
		dynamic_state_info.pDynamicStates = dynamic_state_descriptors.data();
		dynamic_state_info.dynamicStateCount = ::size32(dynamic_state_descriptors);

		VkPipelineVertexInputStateCreateInfo vi = { VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };

		VkPipelineViewportStateCreateInfo vp = {};
		vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
		vp.viewportCount = 1;
		vp.scissorCount = 1;

		auto pmss = &create_info.state.ms;
		VkPipelineMultisampleStateCreateInfo ms2;
		ensure(pmss->rasterizationSamples == VkSampleCountFlagBits((create_info.renderpass_key >> 16) & 0xF)); // "Multisample state mismatch!"

		if (pmss->rasterizationSamples != VK_SAMPLE_COUNT_1_BIT || pmss->sampleShadingEnable) [[unlikely]]
		{
			ms2 = *pmss;
			pmss = &ms2;

			if (ms2.rasterizationSamples != VK_SAMPLE_COUNT_1_BIT)
			{
				// Update the sample mask pointer
				ms2.pSampleMask = &create_info.state.temp_storage.msaa_sample_mask;
			}

			if (g_cfg.video.antialiasing_level == msaa_level::none && ms2.sampleShadingEnable)
			{
				// Do not compile with MSAA enabled if multisampling is disabled
				rsx_log.warning("MSAA is disabled globally but a shader with multi-sampling enabled was submitted for compilation.");
				ms2.sampleShadingEnable = VK_FALSE;
			}
		}

		// Rebase pointers from pipeline structure in case it is moved/copied
		VkPipelineColorBlendStateCreateInfo cs = create_info.state.cs;
		cs.pAttachments = create_info.state.att_state;

		// Flat shading, from upstream b97f4bd8d. Kept alongside our dynamic-state work rather
		// than either side of the merge replacing the other: this rebases the rasterization
		// state so the provoking-vertex extension can be chained onto it, which is independent
		// of the topology-class collapsing above.
		VkPipelineRasterizationStateCreateInfo rs = create_info.state.rs;
		VkPipelineRasterizationProvokingVertexStateCreateInfoEXT provoking_vertex_state{};
		if (flags & USE_LAST_PROVOKING_VERTEX)
		{
			ensure(m_device->get_provoking_vertex_last_support());
			provoking_vertex_state.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_PROVOKING_VERTEX_STATE_CREATE_INFO_EXT;
			provoking_vertex_state.pNext = rs.pNext;
			provoking_vertex_state.provokingVertexMode = VK_PROVOKING_VERTEX_MODE_LAST_VERTEX_EXT;
			rs.pNext = &provoking_vertex_state;
		}

		VkGraphicsPipelineCreateInfo info = {};
		info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
		info.pVertexInputState = &vi;
		info.pInputAssemblyState = &create_info.state.ia;
		info.pRasterizationState = &rs;
		info.pColorBlendState = &cs;
		info.pMultisampleState = pmss;
		info.pViewportState = &vp;
		info.pDepthStencilState = pdss;
		info.stageCount = 2;
		info.pStages = shader_stages;
		info.pDynamicState = &dynamic_state_info;
		info.layout = VK_NULL_HANDLE;
		info.basePipelineIndex = -1;
		info.basePipelineHandle = VK_NULL_HANDLE;
		info.renderPass = vk::get_renderpass(*m_device, create_info.renderpass_key);

		return int_compile_graphics_pipe(info, vs_inputs, fs_inputs, flags);
	}

	std::unique_ptr<glsl::program> pipe_compiler::int_compile_graphics_pipe(
		graphics_pipe_create_callback_t pipe_info_create_fn,
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs,
		op_flags flags)
	{
		VkGraphicsPipelineCreateInfo create_info = pipe_info_create_fn();
		return int_compile_graphics_pipe(create_info, vs_inputs, fs_inputs, flags);
	}

	std::unique_ptr<glsl::program> pipe_compiler::compile(
		const VkComputePipelineCreateInfo& create_info,
		op_flags flags, callback_t callback,
		const std::vector<glsl::program_input>& cs_inputs)
	{
		if (flags & COMPILE_INLINE)
		{
			return int_compile_compute_pipe(create_info, cs_inputs, flags);
		}

		m_work_queue.push(create_info, cs_inputs, flags, callback);
		return {};
	}

	std::unique_ptr<glsl::program> pipe_compiler::compile(
		const VkGraphicsPipelineCreateInfo& create_info,
		op_flags flags, callback_t /*callback*/,
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs)
	{
		// It is very inefficient to defer this as all pointers need to be saved
		ensure(flags & COMPILE_INLINE, "Asynchronous compilation is not allowed for raw graphics pipeline input");
		return int_compile_graphics_pipe(create_info, vs_inputs, fs_inputs, flags);
	}

	std::unique_ptr<glsl::program> pipe_compiler::compile(
		const vk::pipeline_props &create_info,
		VkShaderModule vs,
		VkShaderModule fs,
		op_flags flags, callback_t callback,
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs)
	{
		VkShaderModule modules[] = { vs, fs };
		if (flags & COMPILE_INLINE)
		{
			return int_compile_graphics_pipe(create_info, modules, vs_inputs, fs_inputs, flags);
		}

		m_work_queue.push(create_info, modules, vs_inputs, fs_inputs, flags, callback);
		return {};
	}

	std::unique_ptr<glsl::program> pipe_compiler::compile(
		graphics_pipe_create_callback_t get_create_info,
		op_flags flags, callback_t callback,
		const std::vector<glsl::program_input>& vs_inputs,
		const std::vector<glsl::program_input>& fs_inputs)
	{
		if (flags & COMPILE_INLINE)
		{
			return int_compile_graphics_pipe(get_create_info, vs_inputs, fs_inputs, flags);
		}

		m_work_queue.push(get_create_info, vs_inputs, fs_inputs, flags, callback);
		return {};
	}

	void initialize_pipe_compiler(int num_worker_threads)
	{
		if (num_worker_threads == 0)
		{
			// Select a conservative but modern default for async pipeline compilation.
			// Older heuristics topped out too early on high-core CPUs and left large
			// shader bursts queued longer than necessary.
			const auto hw_threads = utils::get_thread_count();

			// Size to the cores these workers are actually allowed on, not to the machine.
			//
			// They are pinned to thread_class::general (see pipe_compiler::operator()), which on
			// big.LITTLE is the little cluster. Counting the whole SoC then picked a number for
			// cores the workers will never run on: an 8-thread phone landed on 2 workers by the
			// table below, while its 3 little cores sat idle through every shader burst. Sizing
			// to the cluster raises throughput without taking anything back from emulation,
			// because the cores it adds are the ones nothing else wanted.
			const u64 helper_mask = thread_ctrl::get_affinity_mask(thread_class::general);
			const int helper_cores = helper_mask ? std::popcount(helper_mask) : 0;

			if (helper_cores > 0 && static_cast<u32>(helper_cores) < hw_threads)
			{
				num_worker_threads = helper_cores;

				rsx_log.notice("Async pipeline compiler using %d worker(s) on the %d core(s) it is "
					"pinned to, of %u host thread(s).", num_worker_threads, helper_cores, hw_threads);
			}
			else if (hw_threads >= 24)
			{
				num_worker_threads = 12;
			}
			else if (hw_threads >= 16)
			{
				num_worker_threads = 8;
			}
			else if (hw_threads > 12)
			{
				num_worker_threads = 6;
			}
			else if (hw_threads > 8)
			{
				num_worker_threads = 4;
			}
			else if (hw_threads == 8)
			{
				num_worker_threads = 2;
			}
			else
			{
				num_worker_threads = 1;
			}

			rsx_log.notice("Async pipeline compiler auto-selected %d worker(s) for %u host thread(s).",
				num_worker_threads, hw_threads);
		}

		ensure(num_worker_threads >= 1);
		ensure(g_render_device); // "Cannot initialize pipe compiler before creating a logical device"

		// Create the thread pool
		g_pipe_compilers = std::make_unique<named_thread_group<pipe_compiler>>("RSX.W", num_worker_threads);
		g_num_pipe_compilers = num_worker_threads;

		// Initialize the workers. At least one inline compiler shall exist (doesn't actually run)
		for (pipe_compiler& compiler : *g_pipe_compilers.get())
		{
			compiler.initialize(g_render_device);
		}
	}

	void destroy_pipe_compiler()
	{
		g_pipe_compilers.reset();
	}

	pipe_compiler* get_pipe_compiler()
	{
		ensure(g_pipe_compilers);
		int thread_index = g_compiler_index++;

		return g_pipe_compilers.get()->begin() + (thread_index % g_num_pipe_compilers);
	}
}
