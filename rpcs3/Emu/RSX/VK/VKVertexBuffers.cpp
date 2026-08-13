#include "stdafx.h"
#include "VKGSRender.h"
#include "../Common/BufferUtils.h"
#include "../rsx_methods.h"
#include "vkutils/buffer_object.h"

#include <span>

namespace vk
{
	std::pair<VkPrimitiveTopology, bool> get_appropriate_topology(rsx::primitive_type mode)
	{
		switch (mode)
		{
		case rsx::primitive_type::lines:
			return { VK_PRIMITIVE_TOPOLOGY_LINE_LIST, false };
		case rsx::primitive_type::line_loop:
			return { VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, true };
		case rsx::primitive_type::line_strip:
			return { VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, false };
		case rsx::primitive_type::points:
			return { VK_PRIMITIVE_TOPOLOGY_POINT_LIST, false };
		case rsx::primitive_type::triangles:
			return { VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false };
		case rsx::primitive_type::triangle_strip:
		case rsx::primitive_type::quad_strip:
			return { VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, false };
		case rsx::primitive_type::triangle_fan:
#ifndef __APPLE__
			return { VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN, false };
#endif
		case rsx::primitive_type::quads:
		case rsx::primitive_type::polygon:
			return { VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true };
		default:
			fmt::throw_exception("Unsupported primitive topology 0x%x", static_cast<u8>(mode));
		}
	}

	bool is_primitive_native(rsx::primitive_type mode)
	{
		return !get_appropriate_topology(mode).second;
	}

	VkIndexType get_index_type(rsx::index_array_type type)
	{
		switch (type)
		{
		case rsx::index_array_type::u32:
			return VK_INDEX_TYPE_UINT32;
		case rsx::index_array_type::u16:
			return VK_INDEX_TYPE_UINT16;
		}
		fmt::throw_exception("Invalid index array type (%u)", static_cast<u8>(type));
	}
}

namespace
{
	std::tuple<u32, std::tuple<VkDeviceSize, VkIndexType>> generate_emulating_index_buffer(
		const rsx::draw_clause& clause, u32 vertex_count,
		vk::data_heap& m_index_buffer_ring_info)
	{
		u32 index_count = get_index_count(clause.primitive, vertex_count);
		u32 upload_size = index_count * sizeof(u16);

		VkDeviceSize offset_in_index_buffer = m_index_buffer_ring_info.alloc<256>(upload_size);
		void* buf = m_index_buffer_ring_info.map(offset_in_index_buffer, upload_size);

		g_fxo->get<rsx::dma_manager>().emulate_as_indexed(buf, clause.primitive, vertex_count);

		m_index_buffer_ring_info.unmap();
		return std::make_tuple(
			index_count, std::make_tuple(offset_in_index_buffer, VK_INDEX_TYPE_UINT16));
	}

	struct vertex_input_state
	{
		VkPrimitiveTopology native_primitive_type;
		bool index_rebase;
		u32 min_index;
		u32 max_index;
		u32 vertex_draw_count;
		u32 vertex_index_offset;
		std::optional<std::tuple<VkDeviceSize, VkIndexType>> index_info;
	};

	struct draw_command_visitor
	{
		draw_command_visitor(vk::data_heap& index_buffer_ring_info, rsx::vertex_input_layout& layout)
			: m_index_buffer_ring_info(index_buffer_ring_info)
			, m_vertex_layout(layout)
		{
		}

		vertex_input_state operator()(const rsx::draw_array_command& /*command*/)
		{
			const auto [prims, primitives_emulated] = vk::get_appropriate_topology(rsx::method_registers.current_draw_clause.primitive);
			const u32 vertex_count = rsx::method_registers.current_draw_clause.get_elements_count();
			const u32 min_index = rsx::method_registers.current_draw_clause.min_index();
			const u32 max_index = (min_index + vertex_count) - 1;

			if (primitives_emulated)
			{
				u32 index_count;
				std::optional<std::tuple<VkDeviceSize, VkIndexType>> index_info;

				std::tie(index_count, index_info) =
					generate_emulating_index_buffer(rsx::method_registers.current_draw_clause,
						vertex_count, m_index_buffer_ring_info);

				return{ prims, false, min_index, max_index, index_count, 0, index_info };
			}

			return{ prims, false, min_index, max_index, vertex_count, 0, {} };
		}

		vertex_input_state operator()(const rsx::draw_indexed_array_command& command)
		{
			auto primitive = rsx::method_registers.current_draw_clause.primitive;
			const auto [prims, primitives_emulated] = vk::get_appropriate_topology(primitive);
			const bool emulate_restart = rsx::method_registers.restart_index_enabled() && vk::emulate_primitive_restart(primitive);

			rsx::index_array_type index_type = rsx::method_registers.current_draw_clause.is_immediate_draw ?
				rsx::index_array_type::u32 :
				rsx::method_registers.index_type();

			u32 type_size = get_index_type_size(index_type);

			u32 index_count = rsx::method_registers.current_draw_clause.get_elements_count();
			if (primitives_emulated)
				index_count = get_index_count(rsx::method_registers.current_draw_clause.primitive, index_count);
			u32 upload_size = index_count * type_size;

			if (emulate_restart) upload_size *= 2;

			VkDeviceSize offset_in_index_buffer = m_index_buffer_ring_info.alloc<64>(upload_size);
			void* buf = m_index_buffer_ring_info.map(offset_in_index_buffer, upload_size);

			std::span<std::byte> dst;
			stx::single_ptr<std::byte[]> tmp;
			if (emulate_restart)
			{
				tmp = stx::make_single<std::byte[], false, 64>(upload_size);
				dst = std::span<std::byte>(tmp.get(), upload_size);
			}
			else
			{
				dst = std::span<std::byte>(static_cast<std::byte*>(buf), upload_size);
			}

			/**
			* Upload index (and expands it if primitive type is not natively supported).
			*/
			u32 min_index, max_index;
			std::tie(min_index, max_index, index_count) = write_index_array_data_to_buffer(
				dst,
				command.raw_index_buffer, index_type,
				rsx::method_registers.current_draw_clause.primitive,
				rsx::method_registers.restart_index_enabled(),
				rsx::method_registers.restart_index(),
				[](auto prim) { return !vk::is_primitive_native(prim); });

			if (min_index >= max_index)
			{
				//empty set, do not draw
				m_index_buffer_ring_info.unmap();
				return{ prims, false, 0, 0, 0, 0, {} };
			}

			if (emulate_restart)
			{
				if (index_type == rsx::index_array_type::u16)
				{
					index_count = rsx::remove_restart_index(static_cast<u16*>(buf), reinterpret_cast<u16*>(tmp.get()), index_count, u16{umax});
				}
				else
				{
					index_count = rsx::remove_restart_index(static_cast<u32*>(buf), reinterpret_cast<u32*>(tmp.get()), index_count, u32{umax});
				}
			}

			m_index_buffer_ring_info.unmap();

			std::optional<std::tuple<VkDeviceSize, VkIndexType>> index_info =
				std::make_tuple(offset_in_index_buffer, vk::get_index_type(index_type));

			const auto index_offset = rsx::method_registers.vertex_data_base_index();
			return {prims, true, min_index, max_index, index_count, index_offset, index_info};
		}

		vertex_input_state operator()(const rsx::draw_inlined_array& /*command*/)
		{
			auto &draw_clause = rsx::method_registers.current_draw_clause;
			const auto [prims, primitives_emulated] = vk::get_appropriate_topology(draw_clause.primitive);

			const auto stream_length = rsx::method_registers.current_draw_clause.inline_vertex_array.size();
			const u32 vertex_count = u32(stream_length * sizeof(u32)) / m_vertex_layout.interleaved_blocks[0]->attribute_stride;

			if (!primitives_emulated)
			{
				return{ prims, false, 0, vertex_count - 1, vertex_count, 0, {} };
			}

			u32 index_count;
			std::optional<std::tuple<VkDeviceSize, VkIndexType>> index_info;
			std::tie(index_count, index_info) = generate_emulating_index_buffer(draw_clause, vertex_count, m_index_buffer_ring_info);
			return{ prims, false, 0, vertex_count - 1, index_count, 0, index_info };
		}

	private:
		vk::data_heap& m_index_buffer_ring_info;
		rsx::vertex_input_layout& m_vertex_layout;
	};
}

#ifdef __ANDROID__

namespace
{
	// Never withhold more than this fraction of the attribute ring from the allocator.
	constexpr u32 VTX_RETAIN_MAX_SHIFT = 1;   // <= half the ring

	// Free space kept available, as a multiple of the recent per-frame peak. Two frames can be in
	// flight at once (see advance_queued_frames), so anything under 2 would force the ring to grow;
	// 3 leaves a frame of slack on top of that.
	constexpr u64 VTX_RETAIN_HEADROOM_FRAMES = 3;

	// Hard ceiling on how long an entry may live regardless of how much room the ring has.
	// find_vertex_range only fingerprints the first 8 bytes of the guest source, so geometry that
	// mutates without touching its first 8 bytes reads as unchanged. That was near-harmless when
	// entries lived for one frame; capping the age bounds how long such an entry can be wrong.
	constexpr u32 VTX_RETAIN_MAX_FRAMES = 8;
}

// Bring the monotonic ring cursor up to date, and notice if the heap was thrown away underneath us.
//
// m_attrib_ring_info is the only consumer of this ring (upload_vertex_data holds the sole alloc
// sites), so sampling PUT here accounts for every byte it hands out. The cursor is what makes a
// cached offset_in_heap checkable at all: the ring wraps, so an offset on its own cannot say
// whether it still names the data that was written there or the data that lapped over it.
void VKGSRender::vertex_cache_sample_heap()
{
	const VkBuffer handle = m_attrib_ring_info.heap ? m_attrib_ring_info.heap->value : VK_NULL_HANDLE;

	if (handle != m_vtx_heap_handle)
	{
		vertex_cache_on_heap_reset();
		return;
	}

	const usz heap_size = m_attrib_ring_info.size();
	if (!heap_size)
	{
		return;
	}

	// get_current_put_pos_minus_one() reports PUT - 1, wrapping to size - 1 when PUT is zero.
	const usz put = (m_attrib_ring_info.get_current_put_pos_minus_one() + 1) % heap_size;

	// Sampled at the head of every upload and once per frame, so at most one draw's worth of
	// allocation separates two samples and the delta can never hide a full lap.
	m_vtx_heap_cursor += (put >= m_vtx_heap_put)
		? (put - m_vtx_heap_put)
		: (heap_size - m_vtx_heap_put + put);

	m_vtx_heap_put = put;
}

// vk::data_heap::grow() disposes the old VkBuffer and creates a new one without copying anything
// across, and re-inits the ring to PUT=0. Every offset the cache is holding names memory in a
// buffer that no longer exists, so nothing survives this.
void VKGSRender::vertex_cache_on_heap_reset()
{
	m_vertex_cache->purge();

	if (m_vtx_reserve_bytes)
	{
		// The ring grew while we were holding part of it back, which means the reservation was
		// sized too optimistically for this title. Growing the attribute ring on this platform is
		// expensive and has run away before (see the note in flush_command_queue), so stop
		// reserving for the rest of the session rather than risk a second one.
		rsx_log.notice("Vertex cache retention disabled: attribute ring was reallocated while %uK was reserved.",
			static_cast<u32>(m_vtx_reserve_bytes / 1024));
		m_vtx_retention_locked_out = true;
	}

	m_vtx_heap_handle = m_attrib_ring_info.heap ? m_attrib_ring_info.heap->value : VK_NULL_HANDLE;
	m_vtx_heap_cursor = 0;
	m_vtx_heap_put = 0;
	m_vtx_retain_floor = 0;
	m_vtx_frame_base = 0;
	m_vtx_frame_peak = 0;
	m_vtx_peak_ttl = 0;
	m_vtx_reserve_bytes = 0;
	m_vtx_frame_index = 0;
	std::fill(std::begin(m_vtx_frame_marks), std::end(m_vtx_frame_marks), 0ull);

	m_vertex_cache->set_epoch(0);
}

// Decide how much of the ring to withhold from the allocator, then evict everything the withheld
// window does not cover. Called where the unconditional purge() used to be, at frame end.
//
// Why any of this exists: Arkham City reports 10 vertex cache hits out of 1386 requests, so 2499us
// of vertex upload and 1.35M vertices in a single pass are re-uploaded and re-binned every frame
// even though the geometry is unchanged. The cache was purged every frame because offset_in_heap
// points into a ring, and a ring recycles.
//
// The guarantee: the allocator already refuses to allocate across GET (rsx::data_heap::can_alloc),
// which is how in-flight frames are protected from being overwritten. Publishing GET as the
// retention floor rather than the frame's PUT puts retained entries inside that same protected
// window, so the ring physically cannot lap onto them - it grows instead. The floor only ever moves
// forward, and it moves at exactly the moment the matching entries are evicted, so a live entry is
// never below GET.
void VKGSRender::vertex_cache_on_frame_end()
{
	vertex_cache_sample_heap();

	const u64 heap_size = m_attrib_ring_info.size();
	const u64 consumed = m_vtx_heap_cursor - m_vtx_frame_base;

	m_vtx_frame_base = m_vtx_heap_cursor;
	m_vtx_frame_marks[m_vtx_frame_index % VTX_RETAIN_MAX_FRAMES] = m_vtx_heap_cursor;
	m_vtx_frame_index++;

	// Hold the peak for a short window so one quiet frame cannot shrink the margin right before a
	// busy one lands on it.
	if (consumed >= m_vtx_frame_peak || !m_vtx_peak_ttl)
	{
		m_vtx_frame_peak = consumed;
		m_vtx_peak_ttl = VTX_RETAIN_MAX_FRAMES;
	}
	else
	{
		m_vtx_peak_ttl--;
	}

	u64 reserve = 0;

	// Nothing to retain when the cache is a null_vertex_cache, and reserving part of the ring for
	// it would shrink the allocator's working set for no reason at all.
	const bool cache_active = !g_cfg.video.disable_vertex_cache;

	if (cache_active && !m_vtx_retention_locked_out && m_vtx_frame_peak && heap_size)
	{
		const u64 headroom = VTX_RETAIN_HEADROOM_FRAMES * m_vtx_frame_peak;

		// Retention is only worth anything if what is left over can hold at least one frame of
		// geometry; below that every entry dies before it can be hit and the ring would just be
		// smaller for nothing. That threshold is heap_size >= (headroom + 1 frame).
		if (heap_size >= headroom + m_vtx_frame_peak)
		{
			reserve = std::min<u64>(heap_size >> VTX_RETAIN_MAX_SHIFT, heap_size - headroom);
		}
	}

	// Say when this engages or drops out, and on what measurement. Otherwise a hit rate that stays
	// at zero cannot be told apart from a reservation that was never sized above zero to begin
	// with, which is the whole question when reading the counters back off a device.
	if (!!reserve != !!m_vtx_reserve_bytes)
	{
		rsx_log.notice("Vertex cache retention %s (reserve=%uK, peak frame=%uK, ring=%uM).",
			reserve ? "engaged" : "disengaged",
			static_cast<u32>(reserve / 1024),
			static_cast<u32>(m_vtx_frame_peak / 1024),
			static_cast<u32>(heap_size / 0x100000));
	}

	if (!reserve)
	{
		// No room to promise anything. Behave exactly as this call site did before.
		m_vtx_reserve_bytes = 0;
		m_vtx_retain_floor = m_vtx_heap_cursor;
		m_vertex_cache->purge();
		return;
	}

	m_vtx_reserve_bytes = reserve;

	u64 floor = (m_vtx_heap_cursor > reserve) ? (m_vtx_heap_cursor - reserve) : 0;

	// Apply the age cap on top of the byte budget, taking whichever is stricter.
	if (m_vtx_frame_index > VTX_RETAIN_MAX_FRAMES)
	{
		floor = std::max(floor, m_vtx_frame_marks[m_vtx_frame_index % VTX_RETAIN_MAX_FRAMES]);
	}

	m_vtx_retain_floor = floor;
	m_vertex_cache->evict_before(floor);
}

#endif

vk::vertex_upload_info VKGSRender::upload_vertex_data()
{
#ifdef __ANDROID__
	// Before the lookup below: a heap reallocation since the last draw invalidates every entry.
	vertex_cache_sample_heap();

	// Stamp entries with the cursor as of the start of this call, which is at or below where their
	// own bytes actually land. Erring low can only retire an entry early, never late.
	m_vertex_cache->set_epoch(m_vtx_heap_cursor);
#endif

	draw_command_visitor visitor(m_index_buffer_ring_info, m_vertex_layout);
	auto result = std::visit(visitor, m_draw_processor.get_draw_command(rsx::method_registers));

	const u32 vertex_count = (result.max_index - result.min_index) + 1;
	u32 vertex_base = result.min_index;
	u32 index_base = 0;

	if (result.index_rebase)
	{
		vertex_base = rsx::get_index_from_base(vertex_base, rsx::method_registers.vertex_data_base_index());
		index_base = result.min_index;
	}

	//Do actual vertex upload
	auto required = calculate_memory_requirements(m_vertex_layout, vertex_base, vertex_count);
	u32 persistent_range_base = -1, volatile_range_base = -1;
	usz persistent_offset = -1, volatile_offset = -1;

	// Hoisted out of the block below so the allocations that follow can retract a cache hit if they
	// end up reallocating the heap the hit points into.
	bool in_cache = false;

	if (required.first > 0)
	{
		//Check if cacheable
		//Only data in the 'persistent' block may be cached
		//TODO: hook the notify command. Entries now outlive the frame they were written in (see
		//vertex_cache_on_frame_end), but find_vertex_range still detects guest-side edits only by
		//fingerprinting the first 8 bytes of the source, not by locking the memory range.
		bool to_store = false;
		u32  storage_address = -1;

		m_frame_stats.vertex_cache_request_count++;

		if (m_vertex_layout.interleaved_blocks.size() == 1 &&
			rsx::method_registers.current_draw_clause.command != rsx::draw_command::inlined_array)
		{
			const auto data_offset = (vertex_base * m_vertex_layout.interleaved_blocks[0]->attribute_stride);
			storage_address = m_vertex_layout.interleaved_blocks[0]->real_offset_address + data_offset;

			if (auto cached = m_vertex_cache->find_vertex_range(storage_address, required.first))
			{
				ensure(cached->local_address == storage_address);

				in_cache = true;
				persistent_range_base = cached->offset_in_heap;
			}
			else
			{
				to_store = true;
			}
		}

		if (!in_cache)
		{
			m_frame_stats.vertex_cache_miss_count++;

			persistent_offset = static_cast<u32>(m_attrib_ring_info.alloc<256>(required.first));
			persistent_range_base = static_cast<u32>(persistent_offset);

			if (to_store)
			{
				//store ref in vertex cache
				m_vertex_cache->store_range(storage_address, required.first, static_cast<u32>(persistent_offset));
			}
		}
	}

	if (required.second > 0)
	{
		volatile_offset = static_cast<u32>(m_attrib_ring_info.alloc<256>(required.second));
		volatile_range_base = static_cast<u32>(volatile_offset);
	}

#ifdef __ANDROID__
	// An allocation above may have grown the heap, which replaces the VkBuffer outright without
	// copying the old contents over. Offsets returned by alloc() are already relative to the new
	// buffer, but one taken from the cache before that point is not: it names a buffer that has
	// just been disposed. Retract the hit and upload the data normally.
	if (in_cache && m_attrib_ring_info.heap && m_attrib_ring_info.heap->value != m_vtx_heap_handle)
	{
		vertex_cache_on_heap_reset();

		in_cache = false;
		m_frame_stats.vertex_cache_miss_count++;

		persistent_offset = static_cast<u32>(m_attrib_ring_info.alloc<256>(required.first));
		persistent_range_base = static_cast<u32>(persistent_offset);
	}
#endif

	//Write all the data once if possible
	if (required.first && required.second && volatile_offset > persistent_offset)
	{
		//Do this once for both to save time on map/unmap cycles
		const usz block_end = (volatile_offset + required.second);
		const usz block_size = block_end - persistent_offset;
		const usz volatile_offset_in_block = volatile_offset - persistent_offset;

		void *block_mapping = m_attrib_ring_info.map(persistent_offset, block_size);
		m_draw_processor.write_vertex_data_to_memory(m_vertex_layout, vertex_base, vertex_count, block_mapping, static_cast<char*>(block_mapping) + volatile_offset_in_block);
		m_attrib_ring_info.unmap();
	}
	else
	{
		if (required.first > 0 && persistent_offset != umax)
		{
			void *persistent_mapping = m_attrib_ring_info.map(persistent_offset, required.first);
			m_draw_processor.write_vertex_data_to_memory(m_vertex_layout, vertex_base, vertex_count, persistent_mapping, nullptr);
			m_attrib_ring_info.unmap();
		}

		if (required.second > 0)
		{
			void *volatile_mapping = m_attrib_ring_info.map(volatile_offset, required.second);
			m_draw_processor.write_vertex_data_to_memory(m_vertex_layout, vertex_base, vertex_count, nullptr, volatile_mapping);
			m_attrib_ring_info.unmap();
		}
	}

	if (vk::test_status_interrupt(vk::heap_changed))
	{
		// Check for validity
		if (m_persistent_attribute_storage &&
			m_persistent_attribute_storage->info.buffer != m_attrib_ring_info.heap->value)
		{
			vk::get_resource_manager()->dispose(m_persistent_attribute_storage);
		}

		if (m_volatile_attribute_storage &&
			m_volatile_attribute_storage->info.buffer != m_attrib_ring_info.heap->value)
		{
			vk::get_resource_manager()->dispose(m_volatile_attribute_storage);
		}

		m_vertex_env_buffer_info = { *m_vertex_env_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_vertex_constants_buffer_info = { *m_transform_constants_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_fragment_env_buffer_info = { *m_fragment_env_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_fragment_texture_params_buffer_info = { *m_fragment_texture_params_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_raster_env_buffer_info = { *m_raster_env_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_vertex_layout_stream_info = { *m_vertex_layout_ring_info.heap, 0, VK_WHOLE_SIZE };
		m_fragment_constants_buffer_info = { *m_fragment_constants_ring_info.heap, 0, VK_WHOLE_SIZE };

		vk::clear_status_interrupt(vk::heap_changed);
	}

	if (persistent_range_base != umax)
	{
		if (!m_persistent_attribute_storage || !m_persistent_attribute_storage->in_range(persistent_range_base, required.first, persistent_range_base))
		{
			ensure(m_texbuffer_view_size >= required.first); // "Incompatible driver (MacOS?)"
			vk::get_resource_manager()->dispose(m_persistent_attribute_storage);

			//View 64M blocks at a time (different drivers will only allow a fixed viewable heap size, 64M should be safe)
			const usz view_size = (persistent_range_base + m_texbuffer_view_size) > m_attrib_ring_info.size() ? m_attrib_ring_info.size() - persistent_range_base : m_texbuffer_view_size;
			m_persistent_attribute_storage = std::make_unique<vk::buffer_view>(*m_device, m_attrib_ring_info.heap->value, VK_FORMAT_R8_UINT, persistent_range_base, view_size);
			persistent_range_base = 0;
		}
	}

	if (volatile_range_base != umax)
	{
		if (!m_volatile_attribute_storage || !m_volatile_attribute_storage->in_range(volatile_range_base, required.second, volatile_range_base))
		{
			ensure(m_texbuffer_view_size >= required.second); // "Incompatible driver (MacOS?)"
			vk::get_resource_manager()->dispose(m_volatile_attribute_storage);

			const usz view_size = (volatile_range_base + m_texbuffer_view_size) > m_attrib_ring_info.size() ? m_attrib_ring_info.size() - volatile_range_base : m_texbuffer_view_size;
			m_volatile_attribute_storage = std::make_unique<vk::buffer_view>(*m_device, m_attrib_ring_info.heap->value, VK_FORMAT_R8_UINT, volatile_range_base, view_size);
			volatile_range_base = 0;
		}
	}

	return{ result.native_primitive_type,                 // Primitive
			result.vertex_draw_count,                     // Vertex count
			vertex_count,                                 // Allocated vertex count
			vertex_base,                                  // First vertex in stream
			index_base,                                   // Index of vertex at data location 0
			result.vertex_index_offset,                   // Index offset
			persistent_range_base, volatile_range_base,   // Binding range
			result.index_info };                          // Index buffer info
}
