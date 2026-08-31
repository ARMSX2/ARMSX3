#pragma once

#include "util/types.hpp"
#include "util/endian.hpp"
#include "Emu/RSX/gcm_enums.h"

#include <span>
#include <string>

struct RsxDmaControl;

namespace rsx
{
	class thread;
	struct rsx_iomap_table;

	namespace FIFO
	{
		enum internal_commands : u32
		{
			FIFO_NOP = 0xBABEF1F4,
			FIFO_EMPTY = 0xDEADF1F0,
			FIFO_BUSY = 0xBABEF1F0,
			FIFO_ERROR = 0xDEADBEEF,
			FIFO_PACKET_BEGIN = 0xF1F0,
			FIFO_DISABLED_COMMAND = 0xF1F4,
			FIFO_DRAW_BARRIER = 0xF1F8,
		};

		enum flatten_op : u32
		{
			NOTHING = 0,
			EMIT_END = 1,
			EMIT_BARRIER = 2
		};

		enum class state : u8
		{
			running = 0,
			empty = 1,    // PUT == GET
			spinning = 2, // Puller continuously jumps to self addr (synchronization technique)
			nop = 3,      // Puller is processing a NOP command
			lock_wait = 4,// Puller is processing a lock acquire
			paused = 5,   // Puller is paused externallly
		};

		enum class interrupt_hint : u8
		{
			conditional_render_eval = 1,
			zcull_sync = 2
		};

		struct register_pair
		{
			u32 reg;
			u32 value;

			void set(u32 reg, u32 val)
			{
				this->reg = reg;
				this->value = val;
			}
		};

		class flattening_helper
		{
			enum register_props : u8
			{
				none = 0,
				skip_on_match = 1,
				always_ignore = 2
			};

			enum optimization_hint : u8
			{
				unknown,
				load_low,
				load_unoptimizable,
				application_not_compatible
			};

			// Workaround for MSVC, C2248
			static constexpr u8 register_props_always_ignore = register_props::always_ignore;

			static constexpr std::array<u8, 0x10000 / 4> m_register_properties = []
			{
				constexpr std::array<std::pair<u32, u32>, 4> ignorable_ranges =
				{{
					// General
					{ NV4097_INVALIDATE_VERTEX_FILE, 3 }, // PSLight clears VERTEX_FILE[0-2]
					{ NV4097_INVALIDATE_VERTEX_CACHE_FILE, 1 },
					{ NV4097_INVALIDATE_L2, 1 },
					{ NV4097_INVALIDATE_ZCULL, 1 }
				}};

				std::array<u8, 0x10000 / 4> register_properties{};

				for (const auto& method : ignorable_ranges)
				{
					for (u32 i = 0; i < method.second; ++i)
					{
						register_properties[method.first + i] |= register_props_always_ignore;
					}
				}

				return register_properties;
			}();

			u32 deferred_primitive = 0;
			u32 draw_count = 0;
			bool in_begin_end = false;

			bool enabled = false;
			u32  num_collapsed = 0;
			optimization_hint fifo_hint = unknown;

			void reset(bool _enabled);

		public:
			flattening_helper() = default;
			~flattening_helper() = default;

			u32 get_primitive() const { return deferred_primitive; }
			bool is_enabled() const { return enabled; }

			void force_disable();
			void evaluate_performance(u32 total_draw_count);
			inline flatten_op test(register_pair& command);
		};

		class FIFO_control
		{
		private:
			mutable rsx::thread* m_thread;
			RsxDmaControl* m_ctrl = nullptr;
			const rsx::rsx_iomap_table* m_iotable;
			u32 m_internal_get = 0;

			u32 m_memwatch_addr = 0;
			u32 m_memwatch_cmp = 0;

			u32 m_command_reg = 0;
			u32 m_command_inc = 0;
			u32 m_remaining_commands = 0;
			u32 m_args_ptr = 0;
			u32 m_cmd = ~0u;

			u32 m_cache_addr = 0;
			u32 m_cache_size = 0;
			// 32 lines rather than 8.
			//
			// The refill's fixed cost -- read_put, the iotable lookup, the reservation lock
			// and one reservation_acquire per line -- is paid once per refill regardless of
			// how much it fetches. Measured on Minecraft at about 10us per refill against
			// roughly 1us of actual copying, so nine tenths of it was that fixed cost, and
			// a heavy scene took over 1200 refills per frame for 1.29MB of FIFO. Fetching
			// 4KB at a time pays it a quarter as often for the same bytes moved.
			static constexpr u32 cache_line_count = 32;

			alignas(64) std::byte m_cache[cache_line_count][128];

		public:
			FIFO_control(rsx::thread* pctrl);
			~FIFO_control() = default;

			u32 translate_address(u32 addr) const;

			/**
			 * Read one command word, serving it from the local cache when possible.
			 *
			 * The hit path is inlined and the refill is not, deliberately. This is called
			 * once per FIFO command word and the guest pushes on the order of 52,000 of
			 * them per frame, while the cache refills only about 208 times, so better than
			 * 99% of calls are a compare and a load. Leaving the whole thing out of line
			 * made every one of them a real call into another translation unit for the sake
			 * of the rare case, and fetch_u32 was 15.6% of RSX thread samples against only
			 * 18us of actual refill work per frame.
			 */
			std::pair<bool, u32> fetch_u32_refill(u32 addr);

			inline std::pair<bool, u32> fetch_u32(u32 addr)
			{
				if (addr - m_cache_addr >= m_cache_size) [[unlikely]]
				{
					return fetch_u32_refill(addr);
				}

				return {true, read_from_ptr_unsafe<be_t<u32>>(+m_cache[0], addr - m_cache_addr)};
			}
			void invalidate_cache() { m_cache_size = 0; }

			u32 get_pos() const { return m_internal_get; }
			u32 last_cmd() const { return m_cmd; }
			// Publishing GET is a release store into guest DMA memory, and `get` shares a
			// 64-byte line with `put` which the guest PPU writes from another CPU cluster. At
			// ~36700 packets/frame that is a cross-cluster coherence miss per packet. Bounded
			// lag instead, with sync_get_force on every path that can idle or block.
			void sync_get() const;
			void sync_get_force() const;
			mutable u32 m_get_sync_counter = 0;

			// Last value actually stored into ctrl->get. GET is ours to write -- the guest only
			// writes put -- so an unchanged value need not be republished.
			//
			// This matters because the force paths are re-entered continuously while GET stands
			// still. An empty or blocked ring returns to the run loop immediately, so a producer
			// that has gone quiet leaves us taking the cross-cluster miss above tens of thousands
			// of times a frame against a line the guest PPU is trying to write. Announcing
			// progress once is the whole requirement; announcing it repeatedly holds up the
			// producer we are waiting for.
			mutable u32 m_published_get = umax;

			// Snapshot of g_cfg.core.rsx_fifo_accuracy, refreshed once per packet. Reading the
			// config goes through a seq_cst atomic load, an ldar on ARM64 the compiler cannot
			// hoist, and it was consulted once per FIFO argument.
			bool m_accurate_fetch = false;
			std::span<const u32> get_current_arg_ptr(u32 length_in_words) const;
			u32 get_remaining_args_count() const { return m_remaining_commands; }
			void restore_state(u32 cmd, u32 count);
			void inc_get(bool wait);

			void set_get(u32 get, u32 spin_cmd = 0);
			void abort();

			template <bool = true>
			u32 read_put() const;

			// Diagnostic snapshot for the stuck-FIFO report; see rsx::profiler poll_stall().
			std::string debug_snapshot() const;

			void read(register_pair& data);
			inline bool read_unsafe(register_pair& data);
			bool skip_methods(u32 count);
		};
	}
}
