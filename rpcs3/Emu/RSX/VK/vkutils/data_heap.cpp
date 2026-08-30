#include "barriers.h"
#include "data_heap.h"
#include "device.h"

#include "../../RSXOffload.h"
#include "../VKHelpers.h"
#include "../VKResourceManager.h"
#include "Emu/IdManager.h"
#include "Emu/RSX/Overlays/overlay_message.h"
#include "Emu/System.h"
#include "util/sysinfo.hpp"

#include <memory>

namespace vk
{
	data_heap g_upload_heap;

	void data_heap::create(VkBufferUsageFlags usage, usz size, rsx::flags32_t flags, const char* name, usz guard, VkBool32 notify)
	{
		rsx::data_heap::init(size, name, guard);

		const auto& memory_map = g_render_device->get_memory_mapping();

		ensure(std::popcount(flags & (heap_pool_force_vram_shadow | heap_pool_low_latency)) <= 1,
			"Invalid data heap flag combination detected");

		if ((flags & heap_pool_low_latency) && g_cfg.video.vk.use_rebar_upload_heap)
		{
			// Prefer uploading to BAR if low latency is desired.
			const int max_usage = memory_map.device_bar_total_bytes <= (256 * 0x100000) ? 75 : 90;
			m_prefer_writethrough = can_allocate_heap(memory_map.device_bar, size, max_usage);

			// Log it
			if (m_prefer_writethrough)
			{
				rsx_log.notice("Data heap %s will attempt to use Re-BAR memory", m_name);
			}
			else
			{
				rsx_log.warning("Could not fit heap '%s' into Re-BAR memory", m_name);
			}
		}

		VkFlags memory_flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		auto memory_index = m_prefer_writethrough ? memory_map.device_bar : memory_map.host_visible_coherent;

		const bool vram_shadow_requested = !!(flags & heap_pool_force_vram_shadow);
		const bool use_shadow = vram_shadow_requested || !(get_heap_compatible_buffer_types() & usage);

		if (use_shadow)
		{
			if (!vram_shadow_requested)
			{
				rsx_log.warning("Buffer usage %u is not heap-compatible using this driver, explicit staging buffer in use", usage);
			}

			shadow = std::make_unique<buffer>(*g_render_device, size, memory_index, memory_flags, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 0, VMM_ALLOCATION_POOL_SYSTEM);
			usage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
			memory_flags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
			memory_index = memory_map.device_local;
			m_prefer_writethrough = false;
		}

		VkFlags create_flags = 0;
		if (m_prefer_writethrough)
		{
			create_flags |= (VK_BUFFER_CREATE_ALLOW_NULL_RPCS3 | VK_BUFFER_CREATE_IGNORE_VMEM_PRESSURE_RPCS3);
		}

		heap = std::make_unique<buffer>(*g_render_device, size, memory_index, memory_flags, usage, create_flags, VMM_ALLOCATION_POOL_SYSTEM);

		if (!heap->value)
		{
			rsx_log.warning("Could not place heap '%s' into Re-BAR memory. Will attempt to use regular host-visible memory.", m_name);
			ensure(m_prefer_writethrough);

			// We failed to place the buffer in rebar memory. Try again in host-visible.
			m_prefer_writethrough = false;
			auto gc = get_resource_manager();
			gc->dispose(heap);
			heap = std::make_unique<buffer>(*g_render_device, size, memory_map.host_visible_coherent, memory_flags, usage, 0, VMM_ALLOCATION_POOL_SYSTEM);
		}

		m_flags = flags;
		initial_size = size;
		notify_on_grow = bool(notify);
	}

	void data_heap::destroy()
	{
		if (mapped)
		{
			unmap(true);
		}

		heap.reset();
		shadow.reset();
	}

	bool data_heap::grow(usz size)
	{
		// Reclaim before growing.
		//
		// Ring memory is only returned when a frame RETIRES (frame_context_cleanup, reached via
		// check_present_status), and frames only queue around presents. So a game that draws
		// without presenting can never give space back, and the ring grows until an allocation
		// fails and takes the renderer with it.
		//
		// That is not a corner case: it is every long load. Sonic '06 spends sixteen seconds
		// loading a level -- 68 shared-memory allocations, SPU images, particle threads, all
		// healthy forward progress -- and correctly presents nothing the whole time, while the
		// attrib ring climbs 64M to 576M on requests no larger than 217K. Ratchet & Clank does
		// the same to its index buffer, 16M to 256M in 290ms on 2K requests.
		//
		// Growing was never the right answer to a full ring: the GPU has finished with most of
		// it. Ask for that memory back first, and only grow if it genuinely is all still in use.
		if (!(m_flags & heap_pool_fixed_size) && vk::reclaim_ring_memory())
		{
			// Conservative on purpose. The caller checks can_alloc<Alignment> with an alignment
			// this function cannot see, so claim success only with a 4K margin -- enough to cover
			// any alignment it might apply. Being wrong here would let it allocate over memory
			// still in flight, which is far worse than growing unnecessarily.
			if (can_alloc_impl(utils::align(m_put_pos, 4096), size + 4096))
			{
				rsx_log.notice("[%s] Reclaimed instead of growing (heap %uM, requested %uK).",
					m_name, static_cast<u32>(m_size / 0x100000), static_cast<u32>(size / 1024));
				return true;
			}
		}

		// Create new heap. All sizes are aligned up by 64M, upto 1GiB
		usz size_limit = (m_flags & heap_pool_fixed_size) ? initial_size : 1024 * 0x100000;

		// ...but never past what the device can actually hand over.
		//
		// The 1GiB ceiling is a desktop figure and is unrelated to whether the allocation can
		// succeed. On a phone the GPU shares system RAM, so a heap that has already reached
		// 512MB asks for 576MB, the driver refuses, and the allocation failure is FATAL -- the
		// RSX thread is terminated, rendering stops, and the game hangs with audio still playing
		// because every other thread is fine. Sonic '06 dies this way, Ratchet & Clank dies at
		// 144MB in this same function, and neither failure names the heap without the growth log
		// below.
		//
		// Growing into a size the device cannot satisfy is never right: the allocation is
		// guaranteed to fail, and the swap-out path below is the correct answer instead. Bound
		// the ceiling by free memory so that decision gets made before the fatal allocation
		// rather than after it. A quarter of what is free leaves room for the copy, since the
		// old heap is still resident while the new one is created.
#ifdef __ANDROID__
		// A hard ceiling, because free memory is not the constraint that matters.
		//
		// The first attempt bounded this by utils::get_avail_memory()/4 and did nothing: that
		// reads MemAvailable, which counts reclaimable page cache, so it reported ~2.5GB while
		// the driver would not grant a single 576MB device allocation. "Memory the kernel could
		// reclaim" and "memory the GPU can have in one contiguous block" are different numbers.
		//
		// So bound it by what the ring can plausibly need instead. Sonic '06 climbs
		// 192->256->320->384->448->512->576MB in seven seconds while its LARGEST request in that
		// span is 217K -- three orders of magnitude smaller. A ring that has reached 256MB is
		// already far past serving its traffic and is growing for some other reason; letting it
		// keep going only decides how long before an allocation fails.
		//
		// Reaching the limit is not fatal: the branch below swaps the heap out instead, which is
		// the behaviour we want and never got to because the allocation died first.
		//
		// Empirical, and deliberately generous -- retention self-disengages if the ring cannot
		// hold several frames, so this is set far above peak frame traffic rather than close to it.
		if (!(m_flags & heap_pool_fixed_size))
		{
			size_limit = std::min<usz>(size_limit, 256 * 0x100000);
		}
#endif

		usz aligned_new_size = utils::align(m_size + size, 64 * 0x100000);

		if (aligned_new_size >= size_limit)
		{
			// Too large, try to swap out the heap instead of growing.
			rsx_log.error("[%s] Pool limit was reached. Will attempt to swap out the current heap.", m_name);
			aligned_new_size = size_limit;
		}

		// Say WHICH heap grew and by how much.
		//
		// A heap that runs away is the difference between a game that plays and one whose RSX
		// thread is killed by the allocator, and until now nothing identified it: the failure is
		// reported by the allocator as a size and a pool number, and pool 1 covers every
		// data_heap there is. Ratchet & Clank dies asking for 144MB here, which is absurd for a
		// streaming ring on a phone, but vertex, texture-upload and scratch rings each run away
		// for entirely different reasons and the fix differs accordingly.
		//
		// Logged at notice: growth is rare and bounded by the pool limit, so this cannot flood,
		// and a silent reallocation is exactly what made this take three attempts to narrow.
		rsx_log.notice("[%s] Heap grew from %uM to %uM (requested %uK).",
			m_name, static_cast<u32>(m_size / 0x100000),
			static_cast<u32>(aligned_new_size / 0x100000), static_cast<u32>(size / 1024));

		// Wait for DMA activity to end
		g_fxo->get<rsx::dma_manager>().sync();

		if (mapped)
		{
			// Force reset mapping
			unmap(true);
		}

		VkBufferUsageFlags usage = heap->info.usage;
		const auto& memory_map = g_render_device->get_memory_mapping();

		if (m_prefer_writethrough)
		{
			const int max_usage = memory_map.device_bar_total_bytes <= (256 * 0x100000) ? 75 : 90;
			m_prefer_writethrough = can_allocate_heap(memory_map.device_bar, aligned_new_size, max_usage);

			if (!m_prefer_writethrough)
			{
				rsx_log.warning("Could not fit heap '%s' into Re-BAR memory during reallocation", m_name);
			}
		}

		VkFlags memory_flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		auto memory_index = m_prefer_writethrough ? memory_map.device_bar : memory_map.host_visible_coherent;

		// Refuse a growth the device cannot satisfy, and SAY SO, instead of walking into an
		// unrecoverable Vulkan assert.
		//
		// vk::die_with_error kills the RSX thread outright. Every other thread survives, so the
		// game does not crash -- it hangs with audio still playing, VBlank still ticking and CPU
		// near idle, which reads as a guest deadlock and not as the renderer having died. That
		// cost most of an evening to recognise on Sonic '06.
		//
		// This is a symptom, not the disease: the ring only reclaims in restore_snapshot(),
		// which is called from the present path alone, so a game that stops flipping can never
		// give space back and its heap climbs until an allocation fails. Nothing here fixes
		// that. What it does is turn a silent lockup into a stated reason.
		if (!can_allocate_heap(memory_index, aligned_new_size, 95))
		{
			rsx_log.fatal("[%s] Cannot grow to %uM: the device will not allocate it. The heap only "
				"reclaims when a frame is presented, so this means no frame has completed.",
				m_name, static_cast<u32>(aligned_new_size / 0x100000));

			rsx::overlays::queue_message(
				fmt::format("Out of video memory: the '%s' buffer needed %uM and could not get it.\n"
					"No frame has completed, so the renderer cannot reclaim what it already holds.",
					m_name, static_cast<u32>(aligned_new_size / 0x100000)),
				30'000'000);

			// Stop rather than die mid-frame. Emu.Pause leaves the session inspectable and the
			// log intact, where the assert took the RSX thread down and left everything else
			// running as if nothing had happened.
			Emu.Pause(true);
			return false;
		}

		// Update heap information and reset the allocator
		rsx::data_heap::init(aligned_new_size, m_name, m_min_guard_size);

		// Discard old heap and create a new one. Old heap will be garbage collected when no longer needed
		auto gc = get_resource_manager();
		if (shadow)
		{
			ensure(!m_prefer_writethrough);
			rsx_log.warning("Buffer usage %u is not heap-compatible using this driver, explicit staging buffer in use", usage);

			gc->dispose(shadow);
			shadow = std::make_unique<buffer>(*g_render_device, aligned_new_size, memory_index, memory_flags, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 0, VMM_ALLOCATION_POOL_SYSTEM);
			usage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
			memory_flags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
			memory_index = memory_map.device_local;
		}

		gc->dispose(heap);

		VkFlags create_flags = 0;
		if (m_prefer_writethrough)
		{
			create_flags |= (VK_BUFFER_CREATE_ALLOW_NULL_RPCS3 | VK_BUFFER_CREATE_IGNORE_VMEM_PRESSURE_RPCS3);
		}

		heap = std::make_unique<buffer>(*g_render_device, aligned_new_size, memory_index, memory_flags, usage, create_flags, VMM_ALLOCATION_POOL_SYSTEM);

		if (!heap->value)
		{
			rsx_log.warning("Could not place heap '%s' into Re-BAR memory. Will attempt to use regular host-visible memory.", m_name);
			ensure(m_prefer_writethrough);

			// We failed to place the buffer in rebar memory. Try again in host-visible.
			m_prefer_writethrough = false;
			gc->dispose(heap);
			heap = std::make_unique<buffer>(*g_render_device, aligned_new_size, memory_map.host_visible_coherent, memory_flags, usage, 0, VMM_ALLOCATION_POOL_SYSTEM);
		}

		if (notify_on_grow)
		{
			raise_status_interrupt(vk::heap_changed);
		}

		return true;
	}

	bool data_heap::can_allocate_heap(const vk::memory_type_info& target_heap, usz size, int max_usage_percent)
	{
		const auto current_usage = vmm_get_application_memory_usage(target_heap);
		const auto after_usage = current_usage + size;
		const auto limit = (target_heap.total_bytes() * max_usage_percent) / 100;
		return after_usage < limit;
	}

	void* data_heap::map_impl(usz offset, usz size)
	{
		if (!_ptr)
		{
			if (shadow)
				_ptr = shadow->map(0, shadow->size());
			else
				_ptr = heap->map(0, heap->size());

			mapped = true;
		}

		if (shadow)
		{
			bool insert = true;
			if (!dirty_ranges.empty() && (dirty_ranges.back().dstOffset + dirty_ranges.back().size) == offset) [[ likely ]]
			{
				dirty_ranges.back().size += size;
				insert = false;
			}

			if (insert)
			{
				dirty_ranges.push_back({ offset, offset, size });
			}

			raise_status_interrupt(runtime_state::heap_dirty);
		}

		return static_cast<u8*>(_ptr) + offset;
	}

	void data_heap::unmap(bool force)
	{
		if (force)
		{
			if (shadow)
				shadow->unmap();
			else
				heap->unmap();

			mapped = false;
			_ptr = nullptr;
		}
	}

	void data_heap::sync(const vk::command_buffer& cmd)
	{
		if (dirty_ranges.empty())
		{
			return;
		}

		ensure(shadow);
		ensure(heap);
		vkCmdCopyBuffer(cmd, shadow->value, heap->value, ::size32(dirty_ranges), dirty_ranges.data());
		dirty_ranges.clear();

		insert_buffer_memory_barrier(cmd, heap->value, 0, heap->size(),
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
	}

	bool data_heap::is_dirty() const
	{
		return !dirty_ranges.empty();
	}

	data_heap* get_upload_heap()
	{
		if (!g_upload_heap.heap)
		{
			g_upload_heap.create(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 64 * 0x100000, vk::heap_pool_default, "auxilliary upload heap", 0x100000);
		}

		return &g_upload_heap;
	}
}
