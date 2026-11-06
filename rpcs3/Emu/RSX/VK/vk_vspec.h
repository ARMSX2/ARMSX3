#pragma once

#include "vkutils/device.h"
#include "util/logs.hpp"
#include "util/types.hpp"

#include <array>
#include <atomic>
#include <memory>

// ARMSX3: vertex fetch specialization, the Vulkan setting "Vertex Fetch Specialization". Off by
// default; the config database enables it per title. Contributed by k1r4ct (PR #232).
//
// The generic vertex shader decodes every attribute at run time from its descriptor (type, size,
// stride, divisor, byte swap). With this on, every compiled vertex program also declares one
// specialization constant per attribute location (constant ids 64..79) and one vertex input per
// location, and load_program() asks for the pipeline specialized on the attribute layout the
// program was last drawn with, next to the generic one. Attributes whose address and stride are
// aligned to their element size are fetched through vertex input bindings and byte-swapped in the
// shader. The specialized shader trusts its specialization: the CPU compares every draw's layout
// with the bound one and binds the generic variant of the same program (spec constants 0, same
// module, identically defined pipeline layout) for the draws that differ.
//
// Specialized pipelines live in memory only (vk::program_cache) and are always compiled in the
// background, so RPCS3's shader cache (its shaders_cache entries and directory) is the same with
// this on or off. Like every pipeline they are created through the device's pipeline cache, so
// with this on the driver pipeline cache shared by all titles (vk_pipeline_cache.bin) also stores
// them, and the generic pipelines of the vertex shaders built for this.
//
// Spec word per attribute location:
//  [0-5]   type | size << 3                       (attrib.x bits 24..29)
//  [6-8]   swap | volatile << 1 | modulo << 2     (attrib.y bits 29..31)
//  [9-10]  divisor class: 0 none (constant), 1 per vertex, 2 divided
//  [11]    fetched through a vertex input binding ("native")
//  [12-19] binding stride when native (0 for constant attributes)
//  [31]    valid
//
// Off, none of this runs and nothing is logged: no format queries, no extra shader code, vertex
// input state, push constants, specialization constants or vertex buffer bindings.

namespace vk::vspec
{
	constexpr u32 constant_id_base = 64;
	constexpr u32 native_bit = 1u << 11;

	// One binding per attribute location plus a zero-stride dummy feeding every location the
	// shader fetches by itself. Vulkan only guarantees 16.
	constexpr u32 vertex_binding_count = 17;

	// Vertex stage push constant range carrying the per-draw offsets. The stock ranges are [0, 4)
	// vertex, only without shader_draw_parameters (which activation requires), and [4, 32) fragment.
	constexpr u32 push_constant_offset = 32;
	constexpr u32 push_constant_size = 6 * sizeof(u32);

	// Distinct layouts a vertex program gets specialized pipelines for, at most
	constexpr usz max_layouts_per_program = 8;

	inline std::atomic<bool> g_active = false;

	// Bit (type - 1) * 4 + (size - 1) set: the vertex input stage can fetch that attribute format.
	// Filled only when active.
	inline std::atomic<u32> g_native_formats = 0;

	// Background compiles of generic pipelines (of vertex programs built for specialization) that
	// have not finished. No new specialization is requested while there are any, so specialized
	// jobs never queue ahead of a pipeline a draw is waiting for.
	inline std::atomic<s32> g_pending_generic_jobs = 0;

	inline bool active()
	{
		return g_active.load(std::memory_order_relaxed);
	}

	inline u32 elem_size(u32 type)
	{
		static constexpr u32 table[8] = { 0, 2, 4, 2, 1, 2, 4, 1 };
		return table[type & 7];
	}

	inline VkFormat native_format(u32 type, u32 size)
	{
		static constexpr VkFormat f4[4] = { VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT };
		static constexpr VkFormat f2[4] = { VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT, VK_FORMAT_R16G16B16A16_UINT };
		static constexpr VkFormat f1[4] = { VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT };
		if (size < 1 || size > 4)
		{
			return VK_FORMAT_UNDEFINED;
		}

		switch (elem_size(type))
		{
		case 4: return f4[size - 1];
		case 2: return f2[size - 1];
		case 1: return f1[size - 1];
		default: return VK_FORMAT_UNDEFINED;
		}
	}

	// Decides for this boot. The renderer calls it before any vertex program is decompiled and
	// before the pipeline compiler threads are started, and deactivate() after they are gone.
	// heap_buffer_types is vk::get_heap_compatible_buffer_types(). With this on, the attribute ring
	// also gets vertex buffer usage, so both of its usages must be equally host visible compatible:
	// otherwise data_heap would drop the VRAM staging the ring gets today, or place a vertex buffer
	// in memory it cannot use. Only a requested activation is logged.
	inline bool activate(bool requested, const render_device& dev, VkFlags heap_buffer_types)
	{
		const u32 bindings = dev.gpu().get_limits().maxVertexInputBindings;
		const bool draw_parameters = dev.get_shader_draw_parameters_support();
		const bool ring_usage_ok = !(heap_buffer_types & VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ==
			!(heap_buffer_types & VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT);
		const bool on = requested && draw_parameters && bindings >= vertex_binding_count && ring_usage_ok;

		u32 formats = 0;
		if (on)
		{
			for (u32 type = 1; type < 8; ++type)
			{
				for (u32 size = 1; size <= 4; ++size)
				{
					const VkFormat format = native_format(type, size);
					if (format != VK_FORMAT_UNDEFINED &&
						(dev.get_format_properties(format).bufferFeatures & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT))
					{
						formats |= 1u << ((type - 1) * 4 + (size - 1));
					}
				}
			}
		}

		g_native_formats.store(formats, std::memory_order_relaxed);
		g_pending_generic_jobs.store(0, std::memory_order_relaxed);
		g_active.store(on, std::memory_order_relaxed);

		if (requested)
		{
			rsx_log.notice("VK: vertex fetch specialization %s (bindings=%u, SDP=%d, attrib ring usage ok=%d, native formats 0x%x)",
				on ? "on" : "off", bindings, draw_parameters ? 1 : 0, ring_usage_ok ? 1 : 0, formats);
		}

		return on;
	}

	inline void deactivate()
	{
		g_active.store(false, std::memory_order_relaxed);
		g_native_formats.store(0, std::memory_order_relaxed);
		g_pending_generic_jobs.store(0, std::memory_order_relaxed);
	}

	// Can the vertex input stage fetch an attribute of this type and size (3-bit fields)?
	inline bool native_format_ok(u32 type, u32 size)
	{
		if (!type || type > 7 || !size || size > 4 ||
			!(g_native_formats.load(std::memory_order_relaxed) & (1u << ((type - 1) * 4 + (size - 1)))))
		{
			return false;
		}

		// CMP is one packed word
		return type != 6 || size == 1;
	}

	inline u32 divisor_class(u32 x)
	{
		const u32 frequency = (x >> 8) & 0xFFFF;
		return frequency == 0 ? 0 : (frequency == 1 ? 1 : 2);
	}

	// Can this attribute ({x, y} as written by fill_vertex_layout_state) be fetched by the vertex
	// input stage from (view_base + its offset)?
	inline bool native_capable(u32 x, u32 y, u64 view_base)
	{
		if (!native_format_ok((x >> 24) & 7, (x >> 27) & 7))
		{
			return false;
		}

		// Divided and modulo attributes stay in the shader
		const u32 cls = divisor_class(x);
		if (cls == 2 || (y >> 31))
		{
			return false;
		}

		const u32 e = elem_size((x >> 24) & 7);
		const u64 address = view_base + (y & 0x1FFFFFFF);
		const u32 stride = cls == 0 ? 0 : (x & 0xFF);

		// Vulkan requires the offset and the stride to be multiples of the element size. e is 1, 2
		// or 4 here (type is 1..7), so a mask does it without two divisions per attribute per draw.
		return ((address | stride) & (e - 1)) == 0;
	}

	// The specialization a draw's layout asks for. attrib_data: 16 x {x, y} as written by
	// fill_vertex_layout_state; native_mask: the locations this draw fetches through bindings.
	inline std::array<u32, 16> from_layout(const s32* attrib_data, u16 referenced_mask, u32 native_mask)
	{
		std::array<u32, 16> spec{};
		for (u32 i = 0; i < 16; ++i)
		{
			if (!(referenced_mask & (1u << i)))
			{
				continue;
			}

			const u32 x = static_cast<u32>(attrib_data[i * 2]);
			const u32 y = static_cast<u32>(attrib_data[i * 2 + 1]);
			const u32 cls = divisor_class(x);
			u32 s = 0x80000000u | ((x >> 24) & 0x3F) | (((y >> 29) & 7) << 6) | (cls << 9);
			if (native_mask & (1u << i))
			{
				s |= native_bit | (((cls == 0) ? 0u : (x & 0xFF)) << 12);
			}
			spec[i] = s;
		}
		return spec;
	}

	inline bool any(const std::array<u32, 16>& s)
	{
		for (u32 v : s)
		{
			if (v)
			{
				return true;
			}
		}

		return false;
	}

	// One entry of g_pending_generic_jobs, held by every copy of a job's completion callback. It
	// ends when the job delivers its pipeline, or when the last copy is destroyed (a failed
	// compile, or a job dropped unrun when the compiler threads shut down).
	class pending_generic_job
	{
		std::atomic<bool> m_done = false;

	public:
		pending_generic_job()
		{
			g_pending_generic_jobs.fetch_add(1, std::memory_order_relaxed);
		}

		~pending_generic_job()
		{
			finish();
		}

		void finish()
		{
			if (!m_done.exchange(true, std::memory_order_relaxed))
			{
				g_pending_generic_jobs.fetch_sub(1, std::memory_order_relaxed);
			}
		}
	};

	// Wraps the completion callback of a background generic pipeline compile
	template <typename F>
	auto track_generic_job(F callback)
	{
		return [callback = std::move(callback), job = std::make_shared<pending_generic_job>()](auto& pipeline)
		{
			const bool built = !!pipeline;
			auto result = callback(pipeline);
			if (built)
			{
				job->finish();
			}
			return result;
		};
	}
}
