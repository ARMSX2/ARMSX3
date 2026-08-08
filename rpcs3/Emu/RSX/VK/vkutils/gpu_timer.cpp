#include "stdafx.h"
#include "gpu_timer.h"

#include "device.h"
#include "commands.h"

#include <vector>

namespace vk
{
	const char* gpu_timer::name_of(region r)
	{
		switch (r)
		{
		case region::frame: return "GPU frame";
		case region::readback_dma: return "GPU readback";
		case region::blit: return "GPU blit";
		case region::upload: return "GPU upload";
		case region::draw: return "GPU draw";
		default: return "?";
		}
	}

	void gpu_timer::init(const render_device& dev)
	{
		destroy();

		const auto& limits = dev.gpu().get_limits();

		// A period of zero means the device does not support timestamps at all. Bail rather
		// than divide by it and report a frame that took no time.
		if (limits.timestampPeriod == 0.f)
		{
			rsx_log.warning("[gpu_timer] Device reports no timestamp support, GPU timing unavailable.");
			return;
		}

		// Counters narrower than 64 bits must be masked before subtracting, otherwise the
		// unused high bits turn a normal wrap into an enormous duration.
		//
		// Asked of Vulkan directly rather than through physical_device::get_queue_properties,
		// which is not const and so cannot be reached through the const reference gpu()
		// hands back. Not worth a const_cast or an upstream signature change for a call that
		// happens once at device init.
		const u32 family = dev.get_graphics_queue_family();
		u32 family_count = 0;
		vkGetPhysicalDeviceQueueFamilyProperties(dev.gpu(), &family_count, nullptr);

		if (family >= family_count)
		{
			rsx_log.warning("[gpu_timer] Graphics queue family %u out of range, GPU timing unavailable.", family);
			return;
		}

		std::vector<VkQueueFamilyProperties> families(family_count);
		vkGetPhysicalDeviceQueueFamilyProperties(dev.gpu(), &family_count, families.data());

		const u32 valid_bits = families[family].timestampValidBits;

		if (valid_bits == 0)
		{
			rsx_log.warning("[gpu_timer] Graphics queue does not support timestamps, GPU timing unavailable.");
			return;
		}

		m_valid_mask = (valid_bits >= 64) ? ~0ull : ((1ull << valid_bits) - 1);
		m_period_ns = static_cast<double>(limits.timestampPeriod);
		m_device = &dev;

		m_pool = std::make_unique<query_pool>(dev, VK_QUERY_TYPE_TIMESTAMP, ring_depth * queries_per_slot);

		m_slots = {};
		m_write_slot = 0;
		reset();

		rsx_log.notice("[gpu_timer] Ready: %.2f ns per tick, %u valid bits.", m_period_ns, valid_bits);
	}

	void gpu_timer::destroy()
	{
		m_pool.reset();
		m_device = nullptr;
		m_slots = {};
		m_open = {};
		reset();
	}

	void gpu_timer::begin(command_buffer& cmd, region r)
	{
		if (!m_pool)
		{
			return;
		}

		const u32 idx = static_cast<u32>(r);
		auto& state = m_slots[m_write_slot];

		if (m_open[idx].active)
		{
			// Unbalanced begin. Dropping it keeps the pair-wise readback honest.
			state.dropped[idx]++;
			return;
		}

		if (state.events[idx] >= max_events)
		{
			state.dropped[idx]++;
			return;
		}

		m_open[idx].active = true;
		m_open[idx].slot = m_write_slot;
		m_open[idx].event = state.events[idx];

		const u32 q = index_of(m_write_slot, r, state.events[idx], false);

		// Reset the slot's whole query range once, here, where no render pass can be open.
		// Doing it per timestamp recorded vkCmdResetQueryPool inside a render pass for the
		// blit and upload regions, which is invalid: it did not fail cleanly, it corrupted
		// rendering and hung the game on exit.
		if (r == region::frame && state.needs_reset)
		{
			vkCmdResetQueryPool(cmd, *m_pool, m_write_slot * queries_per_slot, queries_per_slot);
			state.needs_reset = false;
		}

		vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, *m_pool, q);
	}

	void gpu_timer::end(command_buffer& cmd, region r)
	{
		if (!m_pool)
		{
			return;
		}

		const u32 idx = static_cast<u32>(r);

		if (!m_open[idx].active)
		{
			return;
		}

		// The slot recorded at begin, not the current one: they differ whenever a region
		// spans a flip.
		auto& state = m_slots[m_open[idx].slot];

		const u32 q = index_of(m_open[idx].slot, r, m_open[idx].event, true);
		vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, *m_pool, q);

		m_open[idx].active = false;
		state.events[idx]++;
		state.in_flight = true;
	}

	void gpu_timer::next_frame()
	{
		if (!m_pool)
		{
			return;
		}

		// Retiring the slot is driven from the frame boundary rather than from a region
		// closing. The primary command buffer is submitted several times per frame, once
		// per flush_command_queue, so treating a submission as a frame would count several
		// frames for every real one and divide every per-frame figure by the wrong number.
		if (!m_slots[m_write_slot].in_flight)
		{
			return;
		}

		m_write_slot = (m_write_slot + 1) % ring_depth;

		// Whatever occupied the slot being reused has either been collected already or is
		// being abandoned; either way it must not be read as this frame's data. Any region
		// still open against it is abandoned too, otherwise its end would write into a slot
		// that has been reset and count as this frame's work.
		for (u32 i = 0; i < region_count; i++)
		{
			if (m_open[i].active && m_open[i].slot == m_write_slot)
			{
				m_open[i].active = false;
			}
		}

		m_slots[m_write_slot] = {};
	}

	void gpu_timer::collect()
	{
		if (!m_pool)
		{
			return;
		}

		for (u32 s = 0; s < ring_depth; s++)
		{
			// Never harvest the slot currently being written into.
			if (s == m_write_slot)
			{
				continue;
			}

			auto& state = m_slots[s];

			if (!state.in_flight)
			{
				continue;
			}

			// Unreadable only while a region is still open against this particular slot.
			bool any_open = false;
			for (u32 i = 0; i < region_count; i++)
			{
				any_open |= (m_open[i].active && m_open[i].slot == s);
			}

			if (any_open)
			{
				continue;
			}

			// Probe the frame pair first. If the GPU has not reached it there is no point
			// asking about the rest, and asking must never block: no WAIT bit here, a
			// not-ready slot is simply retried on the next call.
			std::array<u64, 2> probe{};
			const u32 frame_q = index_of(s, region::frame, 0, false);

			// No frame region means the slot was never reset this cycle, so its queries hold
			// whatever the previous user left behind.
			if (!state.events[static_cast<u32>(region::frame)] || state.needs_reset)
			{
				continue;
			}

			if (vkGetQueryPoolResults(*m_device, *m_pool, frame_q, 2,
					sizeof(probe), probe.data(), sizeof(u64),
					VK_QUERY_RESULT_64_BIT) != VK_SUCCESS)
			{
				continue;
			}

			for (u32 i = 0; i < region_count; i++)
			{
				const auto r = static_cast<region>(i);

				for (u32 e = 0; e < state.events[i]; e++)
				{
					std::array<u64, 2> ts{};
					const u32 q = index_of(s, r, e, false);

					if (vkGetQueryPoolResults(*m_device, *m_pool, q, 2,
							sizeof(ts), ts.data(), sizeof(u64),
							VK_QUERY_RESULT_64_BIT) != VK_SUCCESS)
					{
						continue;
					}

					const u64 t0 = ts[0] & m_valid_mask;
					const u64 t1 = ts[1] & m_valid_mask;

					if (t1 <= t0)
					{
						// Wrapped or identical. One sample is not worth reconstructing.
						continue;
					}

					m_totals_ns[i] += static_cast<u64>(static_cast<double>(t1 - t0) * m_period_ns);
					m_events_seen[i]++;
				}

				m_dropped += state.dropped[i];
			}

			m_frames++;
			state = {};
		}
	}

	std::array<double, gpu_timer::region_count> gpu_timer::per_frame_ms() const
	{
		std::array<double, region_count> out{};

		if (!m_frames)
		{
			return out;
		}

		for (u32 i = 0; i < region_count; i++)
		{
			out[i] = static_cast<double>(m_totals_ns[i]) / 1'000'000.0 / static_cast<double>(m_frames);
		}

		return out;
	}

	void gpu_timer::reset()
	{
		m_totals_ns = {};
		m_events_seen = {};
		m_dropped = 0;
		m_frames = 0;
	}

	gpu_timer& get_gpu_timer()
	{
		static gpu_timer s_timer;
		return s_timer;
	}
}
