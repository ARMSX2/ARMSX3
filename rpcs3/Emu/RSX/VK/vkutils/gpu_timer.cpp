#include "stdafx.h"
#include "gpu_timer.h"
#include "Emu/RSX/rsx_profiler.h"

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

	void gpu_timer::begin(const command_buffer& cmd, region r)
	{
		// Nothing recorded unless the profiler is armed.
		//
		// The readback in VKPresent was already gated on the setting, but these were not, so
		// a release build with the profiler off still paid a vkCmdResetQueryPool and two
		// vkCmdWriteTimestamp calls per region per frame for results nobody would read.
		// Timestamp writes are not free on a tiled GPU: they are pipeline sync points.
		if (!rsx::prof::enabled()) [[likely]]
		{
			return;
		}

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

		// Writing a timestamp into a range that has not been reset is invalid, and the reset
		// only happens when the frame region opens. Anything reaching a fresh slot first --
		// the passes flip() runs after next_frame() has already rotated the slot, for one --
		// would be writing into stale queries. Drop those rather than corrupt the slot.
		if (state.needs_reset && r != region::frame)
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

		// BOTTOM_OF_PIPE at both ends, deliberately.
		//
		// This was TOP_OF_PIPE, which is not symmetric with the BOTTOM_OF_PIPE in end(): the
		// begin stamp lands as soon as the command reaches the front of the pipe, while the end
		// stamp waits for everything ahead of it to drain. A region therefore reported its own
		// work PLUS the drain of whatever was still in flight when it started, and the first
		// substantial pass of a frame absorbed the whole backlog.
		//
		// That is not a small correction: pass #6 measured 8.38ms of a 24.46ms GPU draw budget --
		// 34% in one pass -- while having the lightest workload of the expensive passes, which is
		// exactly the shape this artifact manufactures. Whether that pass is genuinely expensive
		// or a measurement ghost decides where GPU work goes next, so the ruler has to be right
		// before anything is optimised against it.
		//
		// Two BOTTOM_OF_PIPE stamps measure the interval between completion points, which is the
		// region's actual contribution to the timeline. Absolute values are no longer wall-clock
		// "time spent inside the region" and are not comparable to numbers captured before this.
		vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, *m_pool, q);
	}

	void gpu_timer::end(const command_buffer& cmd, region r)
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

		// Disarmed between this region's begin and its end. Clear the flag anyway rather than
		// returning early: leaving it set would make every later begin for this region drop
		// itself as unbalanced, so a single toggle mid-region would silently kill that region's
		// timing for the rest of the session. The timestamp is skipped, the bookkeeping is not.
		if (!rsx::prof::enabled()) [[likely]]
		{
			m_open[idx].active = false;
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

	std::string gpu_timer::debug_state() const
	{
		if (!m_pool)
		{
			return "pool not initialised";
		}

		std::string out = fmt::format("collected=%u flips=%u write_slot=%u dropped=%u",
			m_frames, m_flips, m_write_slot, m_dropped);

		for (u32 i = 0; i < region_count; i++)
		{
			if (!m_open[i].active) continue;
			fmt::append(out, " | OPEN %s slot=%u", name_of(static_cast<region>(i)), m_open[i].slot);
		}

		for (u32 s = 0; s < ring_depth; s++)
		{
			const auto& st = m_slots[s];
			if (!st.in_flight && !st.needs_reset) continue;

			fmt::append(out, " | s%u%s%s ev=", s,
				st.in_flight ? " inflight" : "",
				st.needs_reset ? " needsreset" : "");

			for (u32 i = 0; i < region_count; i++)
			{
				fmt::append(out, "%s%u", i ? "/" : "", st.events[i]);
			}
		}

		return out;
	}

	void gpu_timer::next_frame()
	{
		if (!m_pool)
		{
			return;
		}

		m_flips++;

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

			// Per-region total for THIS frame only, so the worst frame can be tracked.
			std::array<u64, region_count> frame_region_ns{};

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

					const u64 ns = static_cast<u64>(static_cast<double>(t1 - t0) * m_period_ns);

					m_totals_ns[i] += ns;
					frame_region_ns[i] += ns;
					m_events_seen[i]++;

					if (r == region::draw)
					{
						m_draw_pass_ns[e] += ns;
						m_draw_pass_samples[e]++;
					}
				}

				m_dropped += state.dropped[i];
			}

			for (u32 i = 0; i < region_count; i++)
			{
				m_worst_ns[i] = std::max(m_worst_ns[i], frame_region_ns[i]);
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

	std::vector<gpu_timer::pass_cost> gpu_timer::draw_pass_costs() const
	{
		std::vector<pass_cost> out;

		if (!m_frames)
		{
			return out;
		}

		for (u32 e = 0; e < max_events; e++)
		{
			if (!m_draw_pass_samples[e])
			{
				continue;
			}

			// Divided by frames, not by samples, so the entries sum to the draw region total
			// and each one reads as its share of the frame rather than its cost when present.
			out.push_back({
				e,
				static_cast<double>(m_draw_pass_ns[e]) / 1'000'000.0 / static_cast<double>(m_frames),
				m_draw_pass_samples[e]
			});
		}

		std::sort(out.begin(), out.end(),
			[](const auto& a, const auto& b) { return a.ms_per_frame > b.ms_per_frame; });

		return out;
	}

	void gpu_timer::reset()
	{
		m_totals_ns = {};
		m_worst_ns = {};
		m_events_seen = {};
		m_draw_pass_ns = {};
		m_draw_pass_samples = {};
		m_dropped = 0;
		m_frames = 0;
	}

	gpu_timer& get_gpu_timer()
	{
		static gpu_timer s_timer;
		return s_timer;
	}
}
