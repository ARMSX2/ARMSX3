#pragma once

#include <util/types.hpp>
#include "Emu/RSX/Common/simple_array.hpp"

#include <unordered_map>

namespace vk
{
	class data_heap;

	namespace data_heap_manager
	{
		using managed_heap_snapshot_t = std::unordered_map<const vk::data_heap*, s64>;

		// Submit ring buffer for management
		void register_ring_buffer(vk::data_heap& heap);

		// Bulk registration
		void register_ring_buffers(std::initializer_list<std::reference_wrapper<vk::data_heap>> heaps);

		// Capture managed ring buffers snapshot at current time
		managed_heap_snapshot_t get_heap_snapshot();

		// Synchronize heap with snapshot
		// Monotonic ticket for a snapshot. Completions can be observed out of order, and
		// set_get_pos is a plain assignment, so an older snapshot applied after a newer one
		// would hand back memory that has since been reused.
		u64 next_snapshot_id();

		void restore_snapshot(const managed_heap_snapshot_t& snapshot, u64 id);

		// Reset all managed heap allocations
		void reset_heap_allocations();

		// Cleanup
		void reset();

		// Retrieve as list
		rsx::simple_array<vk::data_heap*> to_list();
	}
}
