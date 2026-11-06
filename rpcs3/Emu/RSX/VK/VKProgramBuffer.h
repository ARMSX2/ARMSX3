#pragma once
#include "VKVertexProgram.h"
#include "VKFragmentProgram.h"
#include "VKPipelineCompiler.h"
#include "vk_vspec.h"
#include "../Program/ProgramStateCache.h"

#include <algorithm>

namespace vk
{
	struct VKTraits
	{
		using vertex_program_type = VKVertexProgram;
		using fragment_program_type = VKFragmentProgram;
		using pipeline_type = vk::glsl::program;
		using pipeline_storage_type = std::unique_ptr<vk::glsl::program>;
		using pipeline_properties = vk::pipeline_props;

		static
			void recompile_fragment_program(const RSXFragmentProgram& RSXFP, fragment_program_type& fragmentProgramData, usz ID)
		{
			fragmentProgramData.Decompile(RSXFP);
			fragmentProgramData.id = static_cast<u32>(ID);
			fragmentProgramData.Compile();
		}

		static
			void recompile_vertex_program(const RSXVertexProgram& RSXVP, vertex_program_type& vertexProgramData, usz ID)
		{
			vertexProgramData.Decompile(RSXVP);
			vertexProgramData.id = static_cast<u32>(ID);
			vertexProgramData.Compile();
		}

		static
			void validate_pipeline_properties(const VKVertexProgram&, const VKFragmentProgram& fp, vk::pipeline_props& properties)
		{
			//Explicitly disable writing to undefined registers
			properties.state.att_state[0].colorWriteMask &= fp.output_color_masks[0];
			properties.state.att_state[1].colorWriteMask &= fp.output_color_masks[1];
			properties.state.att_state[2].colorWriteMask &= fp.output_color_masks[2];
			properties.state.att_state[3].colorWriteMask &= fp.output_color_masks[3];
		}

		static
			pipeline_type* build_pipeline(
				const vertex_program_type& vertexProgramData,
				const fragment_program_type& fragmentProgramData,
				const vk::pipeline_props& pipelineProperties,
				bool compile_async,
				std::function<pipeline_type*(pipeline_storage_type&)> callback)
		{
			vk::pipe_compiler::op_flags compiler_flags = compile_async ? vk::pipe_compiler::COMPILE_DEFERRED : vk::pipe_compiler::COMPILE_INLINE;
			compiler_flags |= vk::pipe_compiler::SEPARATE_SHADER_OBJECTS;

			if (vertexProgramData.Flags() & RSX_SHADER_CONTROL_FLAT_SHADING)
			{
				compiler_flags |= vk::pipe_compiler::USE_LAST_PROVOKING_VERTEX;
			}

			if (vertexProgramData.vspec_inputs)
			{
				// ARMSX3: vertex fetch specialization (vk_vspec.h). The vertex shader declares the vertex
				// inputs, and a background compile is counted until it delivers its pipeline.
				compiler_flags |= vk::pipe_compiler::VERTEX_FETCH_SPEC_INPUTS;

				if (compile_async)
				{
					auto tracked = vk::vspec::track_generic_job(std::move(callback));
					callback = std::move(tracked);
				}
			}

			auto compiler = vk::get_pipe_compiler();
			auto result = compiler->compile(
				pipelineProperties,
				vertexProgramData.handle,
				fragmentProgramData.handle,
				compiler_flags, callback,
				vertexProgramData.uniforms,
				fragmentProgramData.uniforms);

			return callback(result);
		}

		// ARMSX3: the pipeline of build_pipeline() with its vertex shader specialized on an attribute
		// layout (vk_vspec.h). Always compiled in the background: the generic pipeline draws until
		// this one is ready.
		static
			void build_specialized_pipeline(
				const vertex_program_type& vertexProgramData,
				const fragment_program_type& fragmentProgramData,
				const vk::pipeline_props& pipelineProperties,
				const std::array<u32, 16>& vtx_spec,
				vk::pipe_compiler::callback_t callback)
		{
			vk::pipe_compiler::op_flags compiler_flags = vk::pipe_compiler::COMPILE_DEFERRED;
			compiler_flags |= vk::pipe_compiler::SEPARATE_SHADER_OBJECTS;
			compiler_flags |= vk::pipe_compiler::VERTEX_FETCH_SPEC_INPUTS;

			if (vertexProgramData.Flags() & RSX_SHADER_CONTROL_FLAT_SHADING)
			{
				compiler_flags |= vk::pipe_compiler::USE_LAST_PROVOKING_VERTEX;
			}

			vk::get_pipe_compiler()->compile(
				pipelineProperties,
				vtx_spec,
				vertexProgramData.handle,
				fragmentProgramData.handle,
				compiler_flags, std::move(callback),
				vertexProgramData.uniforms,
				fragmentProgramData.uniforms);
		}
	};

	struct program_cache : public program_state_cache<VKTraits>
	{
	private:
		// ARMSX3: pipelines specialized on an attribute layout (vk_vspec.h), next to the generic ones
		// of program_state_cache. In memory only: they never reach RPCS3's shader cache, whose
		// shaders_cache entries and directory stay the same with vertex fetch specialization on or
		// off. Like every pipeline they are created through the device's pipeline cache, so with it
		// on the driver pipeline cache shared by all titles (vk_pipeline_cache.bin) stores them too.
		struct vspec_pipeline_key
		{
			u32 vertex_program_id;
			u32 fragment_program_id;
			vk::pipeline_props properties;
			std::array<u32, 16> vtx_spec;

			bool operator==(const vspec_pipeline_key& other) const
			{
				return vertex_program_id == other.vertex_program_id &&
					fragment_program_id == other.fragment_program_id &&
					vtx_spec == other.vtx_spec &&
					properties == other.properties;
			}
		};

		struct vspec_pipeline_key_hash
		{
			usz operator()(const vspec_pipeline_key& key) const
			{
				usz hash = rpcs3::hash_struct<vk::pipeline_props>(key.properties);
				hash ^= rpcs3::hash_struct(key.vtx_spec);
				hash ^= rpcs3::hash_base<u64>(key.vertex_program_id);
				hash ^= rpcs3::hash_base<u64>(key.fragment_program_id) << 32;
				return hash;
			}
		};

		shared_mutex m_vspec_mutex;
		std::unordered_map<vspec_pipeline_key, std::unique_ptr<vk::glsl::program>, vspec_pipeline_key_hash> m_vspec_storage;

	public:
		program_cache(decompiler_callback_t callback)
		{
			notify_pipeline_compiled = callback;
		}

		// ARMSX3: the pipeline of vp, fp and props specialized on vtx_spec, or nullptr while it is not
		// built (the caller keeps the generic one). Requested once, compiled in the background, and
		// never counted as a cache miss: the shader compilation hint follows the generic lookup.
		vk::glsl::program* get_specialized_pipeline(
			const VKVertexProgram& vp,
			const VKFragmentProgram& fp,
			const vk::pipeline_props& props,
			const std::array<u32, 16>& vtx_spec)
		{
			vspec_pipeline_key key{ vp.id, fp.id, props, vtx_spec };
			VKTraits::validate_pipeline_properties(vp, fp, key.properties);

			{
				reader_lock lock(m_vspec_mutex);
				if (const auto found = m_vspec_storage.find(key); found != m_vspec_storage.end())
				{
					return found->second.get();
				}
			}

			// Generic pipelines first: a draw can be waiting for one of those, not for this
			if (vk::vspec::g_pending_generic_jobs.load(std::memory_order_relaxed) > 0)
			{
				return nullptr;
			}

			// A bounded number of layouts per vertex program; draws with any other layout keep the
			// generic pipeline
			auto& requested = vp.requested_vtx_specs;
			if (std::find(requested.cbegin(), requested.cend(), vtx_spec) == requested.cend())
			{
				if (requested.size() >= vk::vspec::max_layouts_per_program)
				{
					if (!vp.vtx_spec_limit_logged)
					{
						vp.vtx_spec_limit_logged = true;
						rsx_log.notice("VK: vertex program %u was drawn with more than %u attribute layouts; the others use its generic pipeline",
							vp.id, static_cast<u32>(vk::vspec::max_layouts_per_program));
					}

					return nullptr;
				}

				requested.push_back(vtx_spec);
			}

			{
				// Placeholder while it compiles. A failed compile leaves it, so it is not retried.
				std::lock_guard lock(m_vspec_mutex);
				m_vspec_storage.emplace(key, nullptr);
			}

			VKTraits::build_specialized_pipeline(vp, fp, key.properties, vtx_spec,
				[this, key](std::unique_ptr<vk::glsl::program>& pipeline)
				{
					if (!pipeline)
					{
						return;
					}

					std::lock_guard lock(m_vspec_mutex);
					if (const auto found = m_vspec_storage.find(key); found != m_vspec_storage.end())
					{
						found->second = std::move(pipeline);
					}
				});

			return nullptr;
		}

		// Hides program_state_cache::clear(): the specialized pipelines go first
		void clear()
		{
			{
				std::lock_guard lock(m_vspec_mutex);
				m_vspec_storage.clear();
			}

			program_state_cache<VKTraits>::clear();
		}

		u64 get_hash(const vk::pipeline_props& props)
		{
			return rpcs3::hash_struct<vk::pipeline_props>(props);
		}

		u64 get_hash(const RSXVertexProgram& prog)
		{
			return program_hash_util::vertex_program_utils::get_vertex_program_ucode_hash(prog);
		}

		u64 get_hash(const RSXFragmentProgram& prog)
		{
			return program_hash_util::fragment_program_utils::get_fragment_program_ucode_hash(prog);
		}

		template <typename... Args>
		void add_pipeline_entry(RSXVertexProgram& vp, RSXFragmentProgram& fp, vk::pipeline_props& props, Args&& ...args)
		{
			get_graphics_pipeline(nullptr, vp, fp, props, false, false, std::forward<Args>(args)...);
		}

		void preload_programs(rsx::program_cache_hint_t* cache_hint, const RSXVertexProgram& vp, const RSXFragmentProgram& fp)
		{
			search_vertex_program(cache_hint, vp);
			search_fragment_program(cache_hint, fp);
		}

		bool check_cache_missed() const
		{
			return m_cache_miss_flag;
		}
	};
}
