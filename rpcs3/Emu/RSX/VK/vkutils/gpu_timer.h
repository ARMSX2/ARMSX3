#pragma once

#include "../VulkanAPI.h"
#include "query_pool.hpp"

#include <array>
#include <memory>

namespace vk
{
	class render_device;
	class command_buffer;

	/**
	 * GPU-side timing for the RSX backend, split by what the work is.
	 *
	 * The CPU buckets in rsx_profiler answer "where does the RSX thread's time go" and, for
	 * this port, the answer turned out to be "asleep waiting for the GPU": the thread drops
	 * to ~22% host CPU while the Adreno sits at 81-88% busy pinned at its maximum clock.
	 * That makes the GPU the limiter and leaves the only open question on the GPU side.
	 *
	 * A single whole-frame timestamp would not have been worth writing, because the kernel
	 * already exposes gpu_busy_percentage and it says the same thing. What is not available
	 * anywhere is the split: how much of that GPU time is drawing versus copying a render
	 * target back for the guest to read. Hence labelled regions.
	 *
	 * Readback is deferred by design. Reading a query in the frame that wrote it means
	 * waiting for the GPU, which is the exact stall being investigated, so results are
	 * collected from slots written several frames earlier and a slot that is not ready yet
	 * is simply left for next time. vkDeviceWaitIdle is never used.
	 */
	class gpu_timer
	{
	public:
		enum class region : u32
		{
			frame,        // Whole primary command buffer
			readback_dma, // Copying render targets back to guest memory
			blit,         // Scaled copies and resolves
			upload,       // Texture uploads
			draw,         // Inside a render pass, i.e. actually drawing the game

			count
		};

		static constexpr u32 region_count = static_cast<u32>(region::count);

		// Deep enough that a slot is comfortably retired before it is reused, so collection
		// never has to wait on the GPU to make progress.
		static constexpr u32 ring_depth = 8;

		void init(const render_device& dev);
		void destroy();

		bool usable() const { return m_pool != nullptr; }

		// Bracket a region. Safe to call when uninitialised, in which case they do nothing.
		//
		// By const reference because the render pass helpers hold one and only ever record
		// commands into it, which the conversion operator already allows on a const buffer.
		void begin(const command_buffer& cmd, region r);
		void end(const command_buffer& cmd, region r);

		// Call once per presented frame. Retires the current ring slot; see the definition
		// for why this is not driven off the frame region closing.
		void next_frame();

		// Non-blocking. Harvests whatever has completed since the last call.
		void collect();

		// Milliseconds of GPU time per frame for each region, over the frames collected
		// since the last reset, plus how many frames that was.
		std::array<double, region_count> per_frame_ms() const;
		u64 collected_frames() const { return m_frames; }
		void reset();

		static const char* name_of(region r);

		// Why nothing has been collected yet. A collector that silently returns nothing is
		// indistinguishable from a GPU that is doing nothing, and the report only prints once
		// 300 frames have been gathered, so a stuck collector prints nothing at all forever.
		std::string debug_state() const;

		u64 flips() const { return m_flips; }

		/**
		 * Per-pass cost for the draw region, keyed by the pass's ordinal within the frame.
		 *
		 * The sum says the GPU is inside render passes and nothing more. Whether that is one
		 * expensive pass or thirty even ones decides what to do about it, and those want
		 * opposite fixes: a single heavy pass is a target, an even spread means the draw count
		 * itself is the wall.
		 *
		 * Ordinal rather than identity because the frame structure is stable, so pass N is the
		 * same logical pass from frame to frame, which is what makes the number actionable.
		 */
		struct pass_cost
		{
			u32 ordinal = 0;
			double ms_per_frame = 0.0;
			u64 samples = 0;
		};

		std::vector<pass_cost> draw_pass_costs() const;

		// Worst single-frame cost for a region in this window, in milliseconds.
		double worst_ms(region r) const
		{
			return static_cast<double>(m_worst_ns[static_cast<u32>(r)]) / 1'000'000.0;
		}

	private:
		// A frame issues many readbacks and blits, and the interesting number is what they
		// cost in total, so each region gets room for several timed events per frame rather
		// than one. Events past this are not timed; the overflow is reported so a truncated
		// frame is never mistaken for a cheap one.
		static constexpr u32 max_events = 96;

		static constexpr u32 queries_per_slot = region_count * max_events * 2;

		u32 index_of(u32 slot_id, region r, u32 event, bool end) const
		{
			return (slot_id * queries_per_slot) +
				(static_cast<u32>(r) * max_events * 2) + (event * 2) + (end ? 1 : 0);
		}

		std::unique_ptr<query_pool> m_pool;
		const render_device* m_device = nullptr;
		double m_period_ns = 0.0;
		u64 m_valid_mask = ~0ull;

		u32 m_write_slot = 0;

		struct slot_state
		{
			// Events completed (begin and end both recorded) per region.
			std::array<u32, region_count> events{};
			std::array<u32, region_count> dropped{};
			bool in_flight = false;

			// Queries must be reset before reuse, but vkCmdResetQueryPool is illegal inside
			// a render pass, and regions such as blit and upload are reached with one open.
			// So the reset is hoisted: done once for the whole slot at the frame region's
			// begin, which is recorded at command buffer start where no pass can be active.
			// Everything else only writes timestamps, which is legal anywhere.
			bool needs_reset = true;
		};

		// Open regions are tracked here rather than on the slot, and remember which slot
		// they were opened against.
		//
		// A command buffer's begin/end cycle does not line up with the flip boundary: the
		// buffer is opened, the frame is retired by next_frame, and only then is the buffer
		// closed. Resolving end() against whatever slot is current at the time therefore
		// lands on the wrong one, leaving the original permanently open and, since collect
		// refuses to read a slot with anything open, uncollectable. Every slot leaked and
		// nothing was ever reported.
		struct open_region
		{
			bool active = false;
			u32 slot = 0;
			u32 event = 0;
		};

		std::array<open_region, region_count> m_open{};

		std::array<slot_state, ring_depth> m_slots{};

		std::array<u64, region_count> m_totals_ns{};

		// Worst single frame in the window, per region.
		//
		// The averages alone are misleading and misled me: "GPU frame 14.16 ms/frame" over 300
		// frames is equally consistent with a steady 14ms and with 250 frames at 8ms plus 44 at
		// 50ms. The second is what a churn looks like, and the mean cannot tell them apart --
		// which is why the GPU was written off as a constant floor rather than examined as the
		// variance.
		std::array<u64, region_count> m_worst_ns{};
		std::array<u64, region_count> m_events_seen{};

		// Draw region only, indexed by pass ordinal within the frame.
		std::array<u64, max_events> m_draw_pass_ns{};
		std::array<u64, max_events> m_draw_pass_samples{};
		u64 m_dropped = 0;
		u64 m_frames = 0;
		u64 m_flips = 0;

	public:
		u64 dropped_events() const { return m_dropped; }
		std::array<u64, region_count> event_counts() const { return m_events_seen; }
	};

	// Single instance, because the regions are recorded from both the renderer and the
	// texture cache and threading a reference through the latter buys nothing.
	gpu_timer& get_gpu_timer();

	/**
	 * RAII bracket for a GPU region.
	 *
	 * Worth having rather than paired begin/end calls because these regions wrap code
	 * with early returns, and a begin whose end is skipped leaves the region open,
	 * which discards the whole slot on the next collect.
	 */
	class gpu_scope
	{
		const command_buffer& m_cmd;
		gpu_timer::region m_region;

	public:
		gpu_scope(const command_buffer& cmd, gpu_timer::region r)
			: m_cmd(cmd), m_region(r)
		{
			get_gpu_timer().begin(m_cmd, m_region);
		}

		~gpu_scope()
		{
			get_gpu_timer().end(m_cmd, m_region);
		}

		gpu_scope(const gpu_scope&) = delete;
		gpu_scope& operator=(const gpu_scope&) = delete;
	};
}
