#include "stdafx.h"
#include "Emu/RSX/rsx_profiler.h"
#include "vkutils/query_pool.hpp"
#include "VKHelpers.h"
#include "VKQueryPool.h"
#include "VKRenderPass.h"
#include "VKResourceManager.h"
#include "util/asm.hpp"

#include <chrono>
#include <thread>
#include "VKGSRender.h"

namespace vk
{
	inline bool query_pool_manager::poke_query(query_slot_info& query, u32 index, VkQueryResultFlags flags)
	{
		// Query is ready if:
		// 1. Any sample has been determined to have passed the Z test
		// 2. The backend has fully processed the query and found no hits

		u32 result[2] = { 0, 0 };
		switch (const auto error = vkGetQueryPoolResults(*owner, *query.pool, index, 1, 8, result, 8, flags | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT))
		{
		case VK_SUCCESS:
		{
			if (result[0])
			{
				query.any_passed = true;
				query.ready = true;
				query.data = result[0];
				return true;
			}
			else if (result[1])
			{
				query.any_passed = false;
				query.ready = true;
				query.data = 0;
				return true;
			}

			return false;
		}
		case VK_NOT_READY:
		{
			query.any_passed = !!result[0];
			query.ready = query.any_passed && !!(flags & VK_QUERY_RESULT_PARTIAL_BIT);
			query.data = result[0];
			return query.ready;
		}
		default:
			die_with_error(error);
			return false;
		}
	}

	query_pool_manager::query_pool_manager(vk::render_device& dev, VkQueryType type, u32 num_entries)
	{
		ensure(num_entries > 0);

		owner = &dev;
		query_type = type;
		query_slot_status.resize(num_entries, {});
		tile_based_renderer = vk::is_tile_based_renderer(vk::get_driver_vendor());

		for (unsigned i = 0; i < num_entries; ++i)
		{
			m_available_slots.push_back(i);
		}
	}

	query_pool_manager::~query_pool_manager()
	{
		if (m_current_query_pool)
		{
			m_current_query_pool.reset();
			owner = nullptr;
		}
	}

	void query_pool_manager::allocate_new_pool(vk::command_buffer& cmd)
	{
		ensure(!m_current_query_pool);

		if (m_query_pool_cache.size() > 0)
		{
			m_current_query_pool = std::move(m_query_pool_cache.front());
			m_query_pool_cache.pop_front();
		}
		else
		{
			const u32 count = ::size32(query_slot_status);
			m_current_query_pool = std::make_unique<query_pool>(*owner, query_type, count);
		}

		// From spec: "After query pool creation, each query must be reset before it is used."
		vkCmdResetQueryPool(cmd, *m_current_query_pool.get(), 0, m_current_query_pool->size());
		m_pool_lifetime_counter = m_current_query_pool->size();
	}

	void query_pool_manager::reallocate_pool(vk::command_buffer& cmd)
	{
		if (m_current_query_pool)
		{
			if (!m_current_query_pool->has_refs())
			{
				auto ref = std::make_unique<query_pool_ref>(this, m_current_query_pool);
				vk::get_resource_manager()->dispose(ref);
			}
			else
			{
				m_consumed_pools.emplace_back(std::move(m_current_query_pool));

				// Sanity check
				if (m_consumed_pools.size() > 3)
				{
					rsx_log.error("[Robustness warning] Query pool discard pile size is now %llu. Are we leaking??", m_consumed_pools.size());
				}
			}
		}

		allocate_new_pool(cmd);
	}

	void query_pool_manager::run_pool_cleanup()
	{
		for (auto It = m_consumed_pools.begin(); It != m_consumed_pools.end();)
		{
			if (!(*It)->has_refs())
			{
				auto ref = std::make_unique<query_pool_ref>(this, *It);
				vk::get_resource_manager()->dispose(ref);
				It = m_consumed_pools.erase(It);
			}
			else
			{
				It++;
			}
		}
	}

	void query_pool_manager::set_control_flags(VkQueryControlFlags control_, VkQueryResultFlags result_)
	{
		control_flags = control_;
		result_flags = result_;

		if (tile_based_renderer)
		{
			// PARTIAL_BIT exists so the poll loop can finish early the moment any sample is known
			// to have passed. That can only happen on an immediate-mode renderer, where fragments
			// rasterize as they arrive. A TBDR has nothing to report until the tiling pass ends, so
			// the flag can never pay off here and only asks the driver for extra work.
			// Ported from rfandango/rpcsx (a560768ce).
			result_flags &= ~VK_QUERY_RESULT_PARTIAL_BIT;
		}
	}

	void query_pool_manager::begin_query(vk::command_buffer& cmd, u32 index)
	{
		ensure(query_slot_status[index].active == false);

		auto& query_info = query_slot_status[index];
		query_info.pool = m_current_query_pool.get();
		query_info.active = true;

		vkCmdBeginQuery(cmd, *query_info.pool, index, control_flags);
	}

	void query_pool_manager::end_query(vk::command_buffer& cmd, u32 index)
	{
		vkCmdEndQuery(cmd, *query_slot_status[index].pool, index);
	}

	bool query_pool_manager::check_query_status(u32 index)
	{
		return poke_query(query_slot_status[index], index, result_flags);
	}

	u32 query_pool_manager::get_query_result(u32 index)
	{
		// Check for cached result
		auto& query_info = query_slot_status[index];

		if (!query_info.ready)
		{
			poke_query(query_info, index, result_flags);

			// Charged to fence_wait: this is the RSX thread blocked on a GPU result, which is
			// what that bucket means. It was invisible before, and it is not small: a
			// simpleperf profile of the RSX thread during Arkham City gameplay put 24.9% of
			// all samples in this function, the single largest entry by a wide margin, while
			// the bucket report showed nothing because no scope reached here.
			RSX_PROF_SCOPE(fence_wait);

			// Bounded, because this loop has no other way out. A query that never becomes
			// available parks the RSX thread here permanently, and nothing reports it: the stall
			// detector runs from do_local_task in the FIFO loop, which we have already left, so
			// the profiler charges the time to FIFO decode and the app looks CPU-bound while
			// audio and vblank carry on. Diagnosing one of these took a native profile to show
			// the thread was in sched_yield under this function.
			//
			// Giving up returns whatever the query holds, which costs at most wrong culling for
			// a frame. That is a better failure than a freeze, and the log line names the cause
			// instead of leaving it to be inferred.
			const auto wait_started = std::chrono::steady_clock::now();
			bool warned = false;

			for (u32 spins = 0; !query_info.ready; spins++)
			{
				// Emulation is going away; a driver that never answers must not also wedge
				// the exit path. The value is irrelevant once we are aborting.
				if (thread_ctrl::state() == thread_state::aborting)
				{
					rsx_log.warning("Occlusion query %u abandoned: emulation is shutting down.", index);
					break;
				}

				if ((spins & 0xffff) == 0xffff)
				{
					const auto waited = std::chrono::steady_clock::now() - wait_started;

					if (!warned && waited > std::chrono::seconds(1))
					{
						warned = true;
						rsx_log.error("Occlusion query %u has not completed after 1s; still waiting.", index);
					}

					if (waited > std::chrono::seconds(3))
					{
						// Ask once more directly before giving up, to record WHICH way the
						// driver is stalling: VK_NOT_READY, or VK_SUCCESS with the
						// availability word still clear. The two are indistinguishable
						// through poke_query and need different conversations with whoever
						// maintains the driver.
						u32 probe[2] = { 0, 0 };
						const VkResult status = vkGetQueryPoolResults(*owner, *query_info.pool, index, 1, 8, probe, 8,
							result_flags | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);

						rsx_log.error("Occlusion query %u never completed (last VkResult %d, result %u, availability %u); "
							"abandoning the wait and using result=%u.",
							index, static_cast<int>(status), probe[0], probe[1], query_info.data);
						query_info.ready = true;
						break;
					}
				}

				// Spin briefly, then hand the core back.
				//
				// A pure pause() spin is reasonable on a desktop, where the result lands in
				// microseconds and there are cores to spare. On a tiled renderer the result is
				// not available until the tile pass resolves, so the wait is far longer, and a
				// handheld runs eleven hot emulator threads across five usable cores. Burning
				// one of them on a spin costs an SPU thread that had real work to do.
				//
				// The short spin first keeps the fast case fast, since a result that is nearly
				// ready still returns without a scheduler round trip.
				//
				// Gated on the driver vendor rather than __ANDROID__: an immediate-mode mobile
				// GPU wants the desktop path, and a tiler on any other platform wants this one.
				if (tile_based_renderer && spins >= 64)
				{
					std::this_thread::yield();
				}
				else
				{
					utils::pause();
				}

				poke_query(query_info, index, result_flags);
			}
		}

		return query_info.data;
	}

	// Shared by both readback paths. vkCmdCopyQueryPoolResults MUST be recorded outside a render
	// pass instance; inside one it is undefined behaviour, and a tiler does not tolerate what an
	// IMR desktop GPU does. See the detailed history on get_query_result_indirect below.
	void query_pool_manager::end_renderpass_for_readback(vk::command_buffer& cmd)
	{
		if (!vk::is_renderpass_open(cmd))
		{
			return;
		}

		// A query that began inside a render pass instance has to end inside that same instance.
		// Ending the pass underneath an open one leaves it permanently unavailable: the driver
		// never marks it ready, get_query_result spins on poke_query forever, and the RSX thread
		// stops with audio and vblank still alive -- a hang, not a crash. The submit-time ensure()
		// does not catch it either, because end_occlusion_query closes the query a moment later so
		// the flag reads clear while the result never arrives.
		if (cmd.flags & vk::command_buffer::cb_has_open_query)
		{
			vk::do_query_cleanup(cmd);
		}

		if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[2]++;
		vk::end_renderpass(cmd);
	}

	void query_pool_manager::get_query_result_indirect(vk::command_buffer& cmd, u32 index, u32 count, VkBuffer dst, VkDeviceSize dst_offset)
	{
		// Not "technically supposed to" -- vkCmdCopyQueryPoolResults MUST be recorded outside a
		// render pass instance. Inside one it is undefined behaviour, and an IMR desktop GPU
		// tolerating it is not evidence that a tiler will.
		//
		// This device does not: with occlusion queries on, Spider-Man: Web of Shadows loses the
		// Vulkan device shortly after a new game starts. The fault surfaces later, in poke_query,
		// because that is the first call that waits on a GPU result -- so it reads as the query
		// READ being at fault when the damage was done when this was recorded. Only the RSX
		// thread dies, so the app keeps running with audio and vblank alive and it presents as a
		// hard freeze rather than a crash.
		//
		// Turning occlusion queries off avoids it and is not an alternative: measured here at
		// 80ms frames with broken visuals, because the game loses its culling results.
		//
		// The upstream comment feared the flush cost. It is paid only when a pass is actually
		// open, on a path that already stalls for a GPU result.
		end_renderpass_for_readback(cmd);

		vkCmdCopyQueryPoolResults(cmd, *query_slot_status[index].pool, index, count, dst, dst_offset, 4, VK_QUERY_RESULT_WAIT_BIT);
	}

	// Batched readback. Copies each result in `indices` order to `dst`, one 4-byte word per
	// entry at its position in the list, coalescing contiguous index runs into single copies.
	//
	// Pool-aware on purpose: a numerically contiguous run can still span a pool reallocation,
	// and copying across that boundary would read from the wrong VkQueryPool. Ported from
	// ouroboros420/rpcsx (7ed3365bc), reusing our renderpass handling rather than theirs --
	// theirs omits the open-query cleanup above.
	void query_pool_manager::copy_query_results(vk::command_buffer& cmd, const std::vector<u32>& indices, VkBuffer dst)
	{
		if (indices.empty())
		{
			return;
		}

		end_renderpass_for_readback(cmd);

		const usz n = indices.size();

		for (usz i = 0; i < n;)
		{
			const u32 base = indices[i];
			const auto* pool = query_slot_status[base].pool;

			usz j = i + 1;
			while (j < n && indices[j] == indices[j - 1] + 1 && query_slot_status[indices[j]].pool == pool)
			{
				j++;
			}

			const u32 count = static_cast<u32>(j - i);

			// The run is contiguous in both hardware index and list position, so query[base + k]
			// lands at destination word (i + k).
			vkCmdCopyQueryPoolResults(cmd, *query_slot_status[base].pool, base, count, dst,
				static_cast<VkDeviceSize>(i) * 4, 4, VK_QUERY_RESULT_WAIT_BIT);

			i = j;
		}
	}

	// Seed a slot from a value already read back on the host, so the next get_query_result
	// returns it without issuing another blocking read. Mirrors poke_query's VK_SUCCESS path.
	// Only valid for a value that was waited on (the copy above uses WAIT_BIT).
	void query_pool_manager::prime_query_result(u32 index, u32 value)
	{
		auto& query = query_slot_status[index];
		query.ready = true;
		query.data = value;
		query.any_passed = (value != 0);
	}

	void query_pool_manager::free_query(vk::command_buffer&/*cmd*/, u32 index)
	{
		// Release reference and discard
		auto& query = query_slot_status[index];

		ensure(query.active);
		query.pool->release();

		if (!query.pool->has_refs())
		{
			// No more refs held, remove if in discard pile
			run_pool_cleanup();
		}

		query = {};
		m_available_slots.push_back(index);
	}

	u32 query_pool_manager::allocate_query(vk::command_buffer& cmd)
	{
		if (!m_pool_lifetime_counter)
		{
			// Pool is exhaused, create a new one
			// This is basically a driver-level pool reset without synchronization
			// TODO: Alternatively, use VK_EXT_host_pool_reset to reset an old pool with no references and swap that in
			if (vk::is_renderpass_open(cmd))
			{
				// Same hazard as get_query_result_indirect above: whatever is open has to be
				// closed before the pass goes, or it never becomes available.
				if (cmd.flags & vk::command_buffer::cb_has_open_query)
				{
					vk::do_query_cleanup(cmd);
				}

				if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[2]++; vk::end_renderpass(cmd);
			}

			reallocate_pool(cmd);
		}

		if (!m_available_slots.empty())
		{
			m_pool_lifetime_counter--;

			const auto result = m_available_slots.front();
			m_available_slots.pop_front();
			return result;
		}

		return ~0u;
	}

	void query_pool_manager::on_query_pool_released(std::unique_ptr<vk::query_pool>& pool)
	{
		if (!vk::force_reuse_query_pools())
		{
			// Delete and let the driver recreate a new pool each time.
			pool.reset();
			return;
		}

		m_query_pool_cache.emplace_back(std::move(pool));
	}

	query_pool_manager::query_pool_ref::~query_pool_ref()
	{
		m_pool_man->on_query_pool_released(m_object);
	}
}
