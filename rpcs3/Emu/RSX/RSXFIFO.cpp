#include "stdafx.h"

#include "Emu/System.h"
#include "RSXFIFO.h"
#include "RSXThread.h"
#include "Capture/rsx_capture.h"
#include "Core/RSXReservationLock.hpp"
#include "Emu/Memory/vm_reservation.h"
#include "Emu/Cell/lv2/sys_rsx.h"
#include "util/sysinfo.hpp"
#include "NV47/HW/context.h"
#include "rsx_profiler.h"

#include "util/asm.hpp"

#include <thread>

using spu_rdata_t = std::byte[128];

extern void mov_rdata(spu_rdata_t& _dst, const spu_rdata_t& _src);
extern bool cmp_rdata(const spu_rdata_t& _lhs, const spu_rdata_t& _rhs);

namespace rsx
{
	namespace FIFO
	{
		FIFO_control::FIFO_control(::rsx::thread* pctrl)
		{
			m_thread = pctrl;
			m_ctrl = pctrl->ctrl;
			m_iotable = &pctrl->iomap_table;
		}

		u32 FIFO_control::translate_address(u32 address) const
		{
			return m_iotable->get_addr(address);
		}

		void FIFO_control::sync_get() const
		{
			// Every 8th packet. The guest reads GET to work out how much ring space is
			// free, and it is far ahead of us here (FIFO stalls measure 0.1/frame), so a
			// bounded lag is invisible to it. Anything that can idle or block publishes
			// immediately via sync_get_force so a waiting producer is never held up.
			if (++m_get_sync_counter & 7)
			{
				return;
			}

			m_ctrl->get.release(m_published_get = m_internal_get);
		}

		void FIFO_control::sync_get_force() const
		{
			m_get_sync_counter = 0;

			// Publish real progress only. The drain paths call this every time they are entered,
			// and while the ring is empty or blocked that is once per run loop iteration with GET
			// unmoved: measured at ~91000 of 137000 iterations per frame in Spider-Man: Web of
			// Shadows, every one of them a coherence miss on the line holding put.
			//
			// The cost lands on the guest rather than on us, which is why it reads as a freeze
			// with sound: the PPU feeding the ring is the thread contending for that line, so it
			// stops producing while threads that never touch it carry on.
			if (m_published_get == m_internal_get)
			{
				return;
			}

			m_ctrl->get.release(m_published_get = m_internal_get);
		}

		std::string FIFO_control::debug_snapshot() const
		{
			return fmt::format("fifo: get=0x%06x put=0x%06x internal=0x%06x published=0x%06x remaining=%u cmd=0x%08x memwatch=0x%06x",
				m_ctrl ? +m_ctrl->get : 0u, m_ctrl ? +m_ctrl->put : 0u,
				m_internal_get, m_published_get, m_remaining_commands, m_cmd, m_memwatch_addr);
		}

		void FIFO_control::restore_state(u32 cmd, u32 count)
		{
			m_cmd = cmd;
			m_command_inc = ((m_cmd & RSX_METHOD_NON_INCREMENT_CMD_MASK) == RSX_METHOD_NON_INCREMENT_CMD) ? 0 : 4;
			m_remaining_commands = count;
			m_internal_get = m_ctrl->get - 4;
			m_args_ptr = m_iotable->get_addr(m_internal_get);
			m_command_reg = (m_cmd & 0xffff) + m_command_inc * (((m_cmd >> 18) - count) & 0x7ff) - m_command_inc;
		}

		void FIFO_control::inc_get(bool wait)
		{
			m_internal_get += 4;

			if (wait && read_put<false>() == m_internal_get)
			{
				// NOTE: Only supposed to be invoked to wait for a single arg on command[0] (4 bytes)
				// Wait for put to allow us to procceed execution
				sync_get_force();
				invalidate_cache();

				while (read_put() == m_internal_get && !Emu.IsStopped())
				{
					m_thread->cpu_wait({});
				}
			}
		}

		template <bool Full>
		inline u32 FIFO_control::read_put() const
		{
			if constexpr (!Full)
			{
				return m_ctrl->put & ~3;
			}
			else
			{
				if (u32 put = m_ctrl->put; (put & 3) == 0) [[likely]]
				{
					return put;
				}

				return m_ctrl->put.and_fetch(~3);
			}
		}

		std::pair<bool, u32> FIFO_control::fetch_u32_refill(u32 addr)
		{
			if (addr - m_cache_addr >= m_cache_size)
			{
				const u32 put = read_put();

				if (put == addr)
				{
					return {false, FIFO_EMPTY};
				}

				m_cache_addr = addr & -128;

				const u32 addr1 = m_iotable->get_addr(m_cache_addr);

				if (addr1 == umax)
				{
					m_cache_size = 0;
					return {false, FIFO_ERROR};
				}

				m_cache_size = std::min<u32>((put | 0x7f) - m_cache_addr, u32{sizeof(m_cache)} - 1) + 1;

				if (0x100000 - (m_cache_addr & 0xfffff) < m_cache_size)
				{
					// Check if memory layout changes in the next 1MB page boundary
					if ((addr1 >> 20) + 1 != (m_iotable->get_addr(m_cache_addr + 0x100000) >> 20))
					{
						// Trim cache as needed if memory layout changes
						m_cache_size = 0x100000 - (m_cache_addr & 0xfffff);
					}
				}

				// Make mask of cache lines to fetch
				// A full mask cannot be built by shifting, since 1u << 32 is undefined.
				const u32 lines_to_fetch = m_cache_size / 128;
				u32 to_fetch = (lines_to_fetch >= cache_line_count)
					? ~0u
					: ((1u << lines_to_fetch) - 1);

				if (addr < put && put < m_cache_addr + m_cache_size)
				{
					// Adjust to knownly-prepared FIFO buffer bounds
					m_cache_size = put - m_cache_addr;
				}

				// Atomic FIFO debug options
				const bool force_cache_fill = g_cfg.core.rsx_fifo_accuracy == rsx_fifo_mode::atomic_ordered;
				const bool strict_fetch_ordering = g_cfg.core.rsx_fifo_accuracy >= rsx_fifo_mode::atomic_ordered;

				if (rsx::prof::enabled()) [[unlikely]]
				{
					rsx::prof::g_fifo_refills++;
					rsx::prof::g_fifo_refill_bytes += m_cache_size;
				}

				// Covers the reservation lock and the line fetch loop below. Refills run a few
				// hundred times a frame rather than per command, so unlike a per-command scope
				// this costs nothing measurable. Each one copies and then re-compares every
				// 128-byte line, so it moves twice the bytes it fetches, against memory the
				// guest PPU is actively writing.
				RSX_PROF_SCOPE(fifo_refill);

				rsx::reservation_lock<true, 1> rsx_lock(addr1, m_cache_size, true);
				const auto src = vm::_ptr<spu_rdata_t>(addr1);

				u64 start_time = 0;
				u32 bytes_read = 0;

				// Find the next set bit after every iteration
				for (int i = 0;; i = (std::countr_zero<u32>(std::rotl<u32>(to_fetch, 0 - i - 1)) + i + 1) % cache_line_count)
				{
					// If a reservation is being updated, try to load another
					const auto& res = vm::reservation_acquire(addr1 + i * 128);
					const u64 time0 = res;

					if (!(time0 & 127))
					{
						mov_rdata(m_cache[i], src[i]);

						if (time0 == res && cmp_rdata(m_cache[i], src[i]))
						{
							// The fetch of the cache line content has been successful, unset its bit
							to_fetch &= ~(1u << i);

							if (!to_fetch)
							{
								break;
							}

							bytes_read += 128;
							continue;
						}
					}

					if (!start_time)
					{
						if (bytes_read >= 256 && !force_cache_fill)
						{
							// Cut our losses if we have something to work with.
							// This is the first time falling out of the reservation loop above, so we have clean data with no holes.
							m_cache_size = bytes_read;
							break;
						}

						start_time = get_system_time();

						if (rsx::prof::enabled()) [[unlikely]]
						{
							rsx::prof::g_fifo_refill_stalls++;
						}
					}

					auto now = get_system_time();
					if (now - start_time >= 50u)
					{
						if (m_thread->is_stopped())
						{
							return {};
						}

						{
							RSX_PROF_SCOPE(idle_pause);
							m_thread->cpu_wait({});
						}

						const auto then = std::exchange(now, get_system_time());
						start_time = now;
						m_thread->performance_counters.idle_time += now - then;
					}
					else
					{
						busy_wait(200);
					}

					if (strict_fetch_ordering)
					{
						i = (i - 1) % cache_line_count;
					}
				}

				// start_time is only set once the refill has had to wait, so this charges
				// exactly the spin and nothing else.
				if (start_time && rsx::prof::enabled()) [[unlikely]]
				{
					rsx::prof::g_fifo_refill_stall_us += (get_system_time() - start_time);
				}
			}

			const auto ret = read_from_ptr_unsafe<be_t<u32>>(+m_cache[0], addr - m_cache_addr);
			return {true, ret};
		}

		void FIFO_control::set_get(u32 get, u32 spin_cmd)
		{
			invalidate_cache();

			if (spin_cmd && m_ctrl->get == get)
			{
				m_memwatch_addr = get;
				m_memwatch_cmp = spin_cmd;
				return;
			}

			// Update ctrl registers
			m_ctrl->get.release(m_published_get = m_internal_get = get);
			m_remaining_commands = 0;
		}

		std::span<const u32> FIFO_control::get_current_arg_ptr(u32 length_in_words) const
		{
			if (!length_in_words)
			{
				// This means the caller is doing something stupid
				rsx_log.error("Invalid access to FIFO args data, requested length = 0");
				return {};
			}

			if (m_accurate_fetch)
			{
				// Return a pointer to the cache storage with confined access
				const u32 cache_offset_in_words = (m_internal_get - m_cache_addr) / 4;
				const u32 cache_size_in_words = m_cache_size / 4;
				return {reinterpret_cast<const u32*>(&m_cache) + cache_offset_in_words, cache_size_in_words - cache_offset_in_words};
			}

			// Return a raw pointer to contiguous memory
			constexpr u32 _1M = 0x100000;
			const u32 size = length_in_words * sizeof(u32);
			const u32 from = m_iotable->get_addr(m_internal_get);

			for (u32 remaining = size, addr = m_internal_get, ptr = from; remaining > 0;)
			{
				const u32 next_block = utils::align(addr + 1, _1M);
				const u32 available = (next_block - addr);
				if (remaining <= available)
				{
					return { static_cast<const u32*>(vm::base(from)), length_in_words };
				}

				remaining -= available;
				const u32 next_ptr = m_iotable->get_addr(next_block);
				if (next_ptr != (ptr + available))
				{
					return { static_cast<const u32*>(vm::base(from)), (size - remaining) / sizeof(u32)};
				}

				ptr = next_ptr;
				addr = next_block;
			}

			fmt::throw_exception("Unreachable");
		}

		bool FIFO_control::read_unsafe(register_pair& data)
		{
			// Fast read with no processing, only safe inside a PACKET_BEGIN+count block
			if (m_remaining_commands)
			{
				bool ok{};
				u32 arg = 0;

				if (g_cfg.core.rsx_fifo_accuracy) [[ unlikely ]]
				{
					std::tie(ok, arg) = fetch_u32(m_internal_get + 4);

					if (!ok)
					{
						if (arg == FIFO_ERROR)
						{
							m_thread->recover_fifo();
						}

						return false;
					}
				}
				else
				{
					if (m_internal_get + 4 == read_put<false>())
					{
						return false;
					}

					m_args_ptr += 4;
					arg = vm::read32(m_args_ptr);
				}

				m_internal_get += 4;

				m_command_reg += m_command_inc;

				--m_remaining_commands;

				data.set(m_command_reg, arg);
				return true;
			}

			m_internal_get += 4;
			return false;
		}

		// Optimization for methods which can be batched together
		// Beware, can be easily misused
		bool FIFO_control::skip_methods(u32 count)
		{
			if (m_remaining_commands > count)
			{
				m_command_reg += m_command_inc * count;
				m_remaining_commands -= count;
				m_internal_get += 4 * count;
				m_args_ptr += 4 * count;
				return true;
			}

			m_internal_get += 4 * m_remaining_commands;
			m_remaining_commands = 0;
			return false;
		}

		void FIFO_control::abort()
		{
			m_remaining_commands = 0;
		}

		void FIFO_control::read(register_pair& data)
		{
			m_accurate_fetch = !!g_cfg.core.rsx_fifo_accuracy;

			if (m_remaining_commands)
			{
				// Previous block aborted to wait for PUT pointer
				read_unsafe(data);
				return;
			}

			if (m_memwatch_addr)
			{
				if (m_internal_get == m_memwatch_addr)
				{
					if (const u32 addr = m_iotable->get_addr(m_memwatch_addr); addr + 1)
					{
						if (vm::read32(addr) == m_memwatch_cmp)
						{
							// Still spinning in place
							data.reg = FIFO_EMPTY;
							return;
						}
					}
				}

				m_memwatch_addr = 0;
				m_memwatch_cmp = 0;
			}

			if (!m_accurate_fetch) [[ likely ]]
			{
				const u32 put = read_put();

				if (put == m_internal_get)
				{
					// Nothing to do
					data.reg = FIFO_EMPTY;
					return;
				}

				if (const u32 addr = m_iotable->get_addr(m_internal_get); addr + 1)
				{
					m_cmd = vm::read32(addr);
				}
				else
				{
					data.reg = FIFO_ERROR;
					return;
				}
			}
			else
			{
				if (auto [ok, arg] = fetch_u32(m_internal_get); ok)
				{
					m_cmd = arg;
				}
				else
				{
					data.reg = arg;
					return;
				}
			}

			if (m_cmd & RSX_METHOD_NON_METHOD_CMD_MASK) [[unlikely]]
			{
				if ((m_cmd & RSX_METHOD_OLD_JUMP_CMD_MASK) == RSX_METHOD_OLD_JUMP_CMD ||
					(m_cmd & RSX_METHOD_NEW_JUMP_CMD_MASK) == RSX_METHOD_NEW_JUMP_CMD ||
					(m_cmd & RSX_METHOD_CALL_CMD_MASK) == RSX_METHOD_CALL_CMD ||
					(m_cmd & RSX_METHOD_RETURN_MASK) == RSX_METHOD_RETURN_CMD)
				{
					// Flow control, stop reading
					data.reg = m_cmd;
					return;
				}

				// Malformed command, optional recovery
				data.reg = FIFO_ERROR;
				return;
			}

			ensure(!m_remaining_commands);
			const u32 count = (m_cmd >> 18) & 0x7ff;

			if (!count)
			{
				m_ctrl->get.release(m_published_get = (m_internal_get += 4));
				data.reg = FIFO_NOP;
				return;
			}

			if (count > 1)
			{
				// Set up readback parameters
				m_command_reg = m_cmd & 0xfffc;
				m_command_inc = ((m_cmd & RSX_METHOD_NON_INCREMENT_CMD_MASK) == RSX_METHOD_NON_INCREMENT_CMD) ? 0 : 4;
				m_remaining_commands = count - 1;
			}

			if (g_cfg.core.rsx_fifo_accuracy)
			{
				m_internal_get += 4;

				auto [ok, arg] = fetch_u32(m_internal_get);

				if (!ok)
				{
					// Optional recovery
					if (arg == FIFO_ERROR)
					{
						data.reg = FIFO_ERROR;
					}
					else
					{
						data.reg = FIFO_EMPTY;
						m_command_reg = m_cmd & 0xfffc;
						m_remaining_commands++;
					}

					return;
				}

				data.set(m_cmd & 0xfffc, arg);
				return;
			}

			inc_get(true); // Wait for data block to become available

			// Validate the args ptr if the command attempts to read from it
			m_args_ptr = m_iotable->get_addr(m_internal_get);
			if (m_args_ptr == umax) [[unlikely]]
			{
				// Optional recovery
				data.reg = FIFO_ERROR;
				return;
			}

			data.set(m_cmd & 0xfffc, vm::read32(m_args_ptr));
		}

		void flattening_helper::reset(bool _enabled)
		{
			enabled = _enabled;
			num_collapsed = 0;
			in_begin_end = false;
		}

		void flattening_helper::force_disable()
		{
			if (enabled)
			{
				rsx_log.warning("FIFO optimizations have been disabled as the application is not compatible with per-frame analysis");

				reset(false);
				fifo_hint = optimization_hint::application_not_compatible;
			}
		}

		void flattening_helper::evaluate_performance(u32 total_draw_count)
		{
			if (!enabled)
			{
				if (fifo_hint == optimization_hint::application_not_compatible)
				{
					// Not compatible, do nothing
					return;
				}

				if (total_draw_count <= 2000)
				{
					// Low draw call pressure
					fifo_hint = optimization_hint::load_low;
					return;
				}

				if (fifo_hint == optimization_hint::load_unoptimizable)
				{
					// Nope, wait for stats to change
					return;
				}
			}

			if (enabled)
			{
				// Currently activated. Check if there is any benefit
				if (num_collapsed < 500)
				{
					// Not worth it, disable
					enabled = false;
					fifo_hint = load_unoptimizable;
				}

				u32 real_total = total_draw_count + num_collapsed;
				if (real_total <= 2000)
				{
					// Low total number of draws submitted, no need to keep trying for now
					enabled = false;
					fifo_hint = load_low;
				}

				reset(enabled);
			}
			else
			{
				// Not enabled, check if we should try enabling
				ensure(total_draw_count > 2000);
				if (fifo_hint != load_unoptimizable)
				{
					// If its set to unoptimizable, we already tried and it did not work
					// If it resets to load low (usually after some kind of loading screen) we can try again
					ensure(in_begin_end == false); // "Incorrect initial state"
					ensure(num_collapsed == 0);
					enabled = true;
				}
			}
		}

		flatten_op flattening_helper::test(register_pair& command)
		{
			u32 flush_cmd = ~0u;
			switch (const u32 reg = (command.reg >> 2))
			{
			case NV4097_SET_BEGIN_END:
			{
				in_begin_end = !!command.value;

				if (command.value)
				{
					// This is a BEGIN call
					if (!deferred_primitive) [[likely]]
					{
						// New primitive block
						deferred_primitive = command.value;
					}
					else if (deferred_primitive == command.value)
					{
						// Same primitive can be chanined; do nothing
						command.reg = FIFO_DISABLED_COMMAND;
					}
					else
					{
						// Primitive command has changed!
						// Flush
						flush_cmd = command.value;
					}
				}
				else if (deferred_primitive)
				{
					command.reg = FIFO_DRAW_BARRIER;
					draw_count++;
				}
				else
				{
					rsx_log.error("Fifo flattener misalignment, disable FIFO reordering and report to developers");
					in_begin_end = false;
					flush_cmd = 0u;
				}

				break;
			}
			case NV4097_DRAW_ARRAYS:
			case NV4097_DRAW_INDEX_ARRAY:
			{
				// TODO: Check type
				break;
			}
			default:
			{
				if (draw_count) [[unlikely]]
				{
					if (m_register_properties[reg] & register_props::always_ignore) [[unlikely]]
					{
						// Always ignore
						command.reg = FIFO_DISABLED_COMMAND;
					}
					else
					{
						// Flush
						flush_cmd = (in_begin_end) ? deferred_primitive : 0u;
					}
				}
				else
				{
					// Nothing to do
					return NOTHING;
				}

				break;
			}
			}

			if (flush_cmd != ~0u)
			{
				num_collapsed += draw_count? (draw_count - 1) : 0;
				draw_count = 0;
				deferred_primitive = flush_cmd;

				return in_begin_end ? EMIT_BARRIER : EMIT_END;
			}

			return NOTHING;
		}
	}

	void thread::run_FIFO()
	{
		// Explicitly empty rather than default-initialised. read() can return without assigning:
		// when a packet is still in flight it forwards to read_unsafe, which bails out leaving
		// `data` untouched if the producer has not written the next word yet. The reg was then
		// read uninitialised -- in practice whatever the previous iteration left in the same
		// stack slot, so a stale command could be re-executed instead of the ring reading empty.
		FIFO::register_pair command{FIFO::FIFO_EMPTY, 0};
		fifo_ctrl->read(command);
		const auto cmd = command.reg;

		if (cmd & (0xffff0000 | RSX_METHOD_NON_METHOD_CMD_MASK)) [[unlikely]]
		{
			// Check for special FIFO commands
			switch (cmd)
			{
			case FIFO::FIFO_NOP:
			{
				if (performance_counters.state == FIFO::state::running)
				{
					performance_counters.FIFO_idle_timestamp = get_system_time();
					performance_counters.state = FIFO::state::nop;
				}

				// Going idle: publish GET now rather than carrying up to seven packets of
				// lag into a period where the producer may be waiting on ring space.
				fifo_ctrl->sync_get_force();
				return;
			}
			case FIFO::FIFO_EMPTY:
			{
				// Publish GET before going idle.
				//
				// GET is published on a bounded lag -- every eighth packet -- to keep a
				// cross-cluster coherence miss off the per-packet path. That is only safe while
				// something is still coming to flush it. Draining the ring is precisely when
				// nothing is: the guest reads GET to see how far we have consumed, and with up
				// to seven packets of lag frozen into it and no further packets to publish, it
				// waits forever for progress that was made and never announced.
				//
				// Presents as a boot that hangs with the RSX perfectly healthy and idle, every
				// guest thread in a legitimate wait, and sys_timer_usleep climbing -- and only
				// when the packet count is not a multiple of eight as the ring drains, which is
				// why it is game- and timing-dependent rather than reliable.
				fifo_ctrl->sync_get_force();

				// Spin budget for the idle wait below. Reset whenever a fresh idle period starts, so
				// each drain gets its own short hot window before parking.
				static thread_local u32 s_fifo_idle_spins = 0;

				if (performance_counters.state == FIFO::state::running)
				{
					performance_counters.FIFO_idle_timestamp = get_system_time();
					performance_counters.state = FIFO::state::empty;
					s_fifo_idle_spins = 0;
				}
				else
				{
					// Yield, without backing off to a sleep.
					//
					// A 50us sleep was added here when the RSX thread was measured spending 66%
					// of its cycles in sched_yield while the machine was starved for cores: the
					// affinity mask confined six SPU threads to four cores, and the reservation
					// path serialised everything behind a global lock, so a spinning RSX was
					// taking a core from threads that needed it.
					//
					// Neither of those is true any more, and the trade inverted with them.
					// Measured after both were fixed: 34% of eight cores busy, two to four
					// threads runnable, five idle. Nothing is waiting for the core this would
					// give back, and the RSX sits on the frame's dependency chain, so sleeping
					// only delays the moment it notices the guest has produced work.
					//
					// Charged to idle, because that is what it is. This yield sits inside the
					// fifo_decode scope, and fifo_decode is the enclosing scope of the whole run
					// loop, so without this the time the RSX spends waiting on an empty ring is
					// reported as decode work. That reads as a saturated RSX -- Idle 0.003 ms
					// against FIFO decode 20.4 ms -- while a native profile of the same thread
					// put 34% of its cycles in sched_yield and its kernel path. The bucket
					// report and the profiler disagreed, and the bucket report was wrong.
					//
					// It has now caused two wrong conclusions in one session: once reading a
					// starving RSX as CPU-bound decode, and once reading a thread stuck in an
					// occlusion query wait as the same thing.
					// UPDATE: the yield is now itself the problem, and it is measured. A native
					// profile of Arkham City gameplay put ~11% of TOTAL process CPU in sched_yield
					// reached from here -- 93% of the RSX thread's kernel time, and its single largest
					// cost. sched_yield is close to the worst available wait on this device: it is a
					// syscall, it forces a scheduler pass, and with ~14 hot threads over 8 cores it is
					// usually rescheduled immediately -- so it burns a core one of the five SPU threads
					// actually wants, while doing nothing to notice the guest sooner.
					//
					// A short hot spin first, so a PUT that lands within microseconds is still caught
					// without paying any wake latency; only sustained idle parks. WFE costs no syscall
					// and the architected event stream bounds the park to tens of microseconds, so the
					// RSX still sits on the frame's dependency chain rather than sleeping through work.
					//
					// The pre-spin is not optional: ouroboros420/rpcsx parked bare here (e31ef44ef) and
					// had to walk it back (832c23078) when the wake latency cost frametime smoothness.
					RSX_PROF_SCOPE(idle_fifo);

#if defined(ARCH_ARM64)
					if (s_fifo_idle_spins < 8)
					{
						s_fifo_idle_spins++;
						utils::pause();
					}
					else if (utils::has_wfe_event_stream())
					{
						utils::wait_for_event();
					}
					else
					{
						// Without the event stream a monitor-less WFE has no bounded
						// wake, so a park here could sleep through the guest's PUT
						// advance until an unrelated interrupt. Yield instead - the
						// pre-WFE behavior of this path.
						std::this_thread::yield();
					}
#else
					std::this_thread::yield();
#endif
				}

				return;
			}
			case FIFO::FIFO_BUSY:
			{
				// Do something else. Same reasoning as the empty case: this leaves the consume
				// loop, so GET goes out rather than sitting behind the lag counter.
				fifo_ctrl->sync_get_force();
				return;
			}
			case FIFO::FIFO_ERROR:
			{
				rsx_log.error("FIFO error: possible desync event (last cmd = 0x%x)", get_fifo_cmd());
				recover_fifo();
				return;
			}
			}

			// Check for flow control
			if (bit_set<2> jump_type; jump_type
				.set_unsafe(0, (cmd & RSX_METHOD_OLD_JUMP_CMD_MASK) == RSX_METHOD_OLD_JUMP_CMD)
				.set_unsafe(1, (cmd & RSX_METHOD_NEW_JUMP_CMD_MASK) == RSX_METHOD_NEW_JUMP_CMD)
				.any())
			{
				const u32 offs = cmd & (jump_type.test_unsafe(0) ? RSX_METHOD_OLD_JUMP_OFFSET_MASK : RSX_METHOD_NEW_JUMP_OFFSET_MASK);
				if (offs == fifo_ctrl->get_pos())
				{
					//Jump to self. Often preceded by NOP
					if (performance_counters.state == FIFO::state::running)
					{
						performance_counters.FIFO_idle_timestamp = get_system_time();
						sync_point_request.release(true);
					}

					performance_counters.state = FIFO::state::spinning;
				}
				else
				{
					last_known_code_start = offs;
				}

				//rsx_log.warning("rsx jump(0x%x) #addr=0x%x, cmd=0x%x, get=0x%x, put=0x%x", offs, m_ioAddress + get, cmd, get, put);
				fifo_ctrl->set_get(offs, cmd);
				return;
			}
			if ((cmd & RSX_METHOD_CALL_CMD_MASK) == RSX_METHOD_CALL_CMD)
			{
				if (fifo_ret_addr != RSX_CALL_STACK_EMPTY)
				{
					// Only one layer is allowed in the call stack.
					rsx_log.error("FIFO: CALL found inside a subroutine (last cmd = 0x%x)", get_fifo_cmd());
					recover_fifo();
					return;
				}

				const u32 offs = cmd & RSX_METHOD_CALL_OFFSET_MASK;
				fifo_ret_addr = fifo_ctrl->get_pos() + 4;
				fifo_ctrl->set_get(offs);
				last_known_code_start = offs;
				return;
			}
			if ((cmd & RSX_METHOD_RETURN_MASK) == RSX_METHOD_RETURN_CMD)
			{
				if (fifo_ret_addr == RSX_CALL_STACK_EMPTY)
				{
					rsx_log.error("FIFO: RET found without corresponding CALL (last cmd = 0x%x)", get_fifo_cmd());
					recover_fifo();
					return;
				}

				// Optimize returning to another CALL
				if ((ctrl->put & ~3) != fifo_ret_addr)
				{
					if (u32 addr = iomap_table.get_addr(fifo_ret_addr); addr != umax)
					{
						const u32 cmd0 = vm::read32(addr);

						// Check for missing step flags, in case the user is single-stepping in the debugger
						if ((cmd0 & RSX_METHOD_CALL_CMD_MASK) == RSX_METHOD_CALL_CMD && cpu_flag::dbg_step - state)
						{
							fifo_ctrl->set_get(cmd0 & RSX_METHOD_CALL_OFFSET_MASK);
							last_known_code_start = ctrl->get;
							fifo_ret_addr += 4;
							return;
						}
					}
				}

				fifo_ctrl->set_get(std::exchange(fifo_ret_addr, RSX_CALL_STACK_EMPTY));
				last_known_code_start = ctrl->get;
				return;
			}

			// If we reached here, this is likely an error
			fmt::throw_exception("Unexpected command 0x%x (last cmd: 0x%x)", cmd, fifo_ctrl->last_cmd());
		}

		if (const auto state = performance_counters.state;
			state != FIFO::state::running)
		{
			performance_counters.state = FIFO::state::running;

			// Hack: Delay FIFO wake-up according to setting
			// NOTE: The typical spin setup is a NOP followed by a jump-to-self
			// NOTE: There is a small delay when the jump address is dynamically edited by cell
			if (state != FIFO::state::nop)
			{
				fifo_wake_delay();
			}

			// Update performance counters with time spent in idle mode
			performance_counters.idle_time += (get_system_time() - performance_counters.FIFO_idle_timestamp);
		}

		do
		{
			if (capture_current_frame) [[unlikely]]
			{
				const u32 reg = (command.reg & 0xfffc) >> 2;
				const u32 value = command.value;

				frame_debug.command_queue.emplace_back(reg, value);

				if (!(reg == NV406E_SET_REFERENCE || reg == NV406E_SEMAPHORE_RELEASE || reg == NV406E_SEMAPHORE_ACQUIRE))
				{
					// todo: handle nv406e methods better?, do we care about call/jumps?
					rsx::frame_capture_data::replay_command replay_cmd;
					replay_cmd.rsx_command = std::make_pair((reg << 2) | (1u << 18), value);

					auto& commands = frame_capture.replay_commands;
					commands.push_back(replay_cmd);

					switch (reg)
					{
					case NV3089_IMAGE_IN:
						capture::capture_image_in(this, commands.back());
						break;
					case NV0039_BUFFER_NOTIFY:
						capture::capture_buffer_notify(this, commands.back());
						break;
					default:
					{
						static constexpr std::array<std::pair<u32, u32>, 3> ranges
						{{
							{NV308A_COLOR, 0x700},
							{NV4097_SET_TRANSFORM_PROGRAM, 32},
							{NV4097_SET_TRANSFORM_CONSTANT, 32}
						}};

						// Use legacy logic - enqueue leading command with count
						// Then enqueue each command arg alone with a no-op command
						for (const auto& range : ranges)
						{
							if (reg >= range.first && reg < range.first + range.second)
							{
								const u32 remaining = std::min<u32>(fifo_ctrl->get_remaining_args_count() + 1,
									(fifo_ctrl->last_cmd() & RSX_METHOD_NON_INCREMENT_CMD_MASK) ? -1 : (range.first + range.second) - reg);

								commands.back().rsx_command.first = (fifo_ctrl->last_cmd() & RSX_METHOD_NON_INCREMENT_CMD_MASK) | (reg << 2) | (remaining << 18);

								for (u32 i = 1; i < remaining && fifo_ctrl->get_pos() + i * 4 != (ctrl->put & ~3); i++)
								{
									replay_cmd.rsx_command = std::make_pair(0, vm::read32(iomap_table.get_addr(fifo_ctrl->get_pos()) + (i * 4)));

									commands.push_back(replay_cmd);
								}

								break;
							}
						}

						break;
					}
					}
				}
			}

			if (m_flattener.is_enabled()) [[unlikely]]
			{
				switch(m_flattener.test(command))
				{
				case FIFO::NOTHING:
				{
					break;
				}
				case FIFO::EMIT_END:
				{
					// Emit end command to close existing scope
					AUDIT(in_begin_end);
					methods[NV4097_SET_BEGIN_END](m_ctx, NV4097_SET_BEGIN_END, 0);
					break;
				}
				case FIFO::EMIT_BARRIER:
				{
					AUDIT(in_begin_end);
					methods[NV4097_SET_BEGIN_END](m_ctx, NV4097_SET_BEGIN_END, 0);
					methods[NV4097_SET_BEGIN_END](m_ctx, NV4097_SET_BEGIN_END, m_flattener.get_primitive());
					break;
				}
				default:
				{
					fmt::throw_exception("Unreachable");
				}
				}

				if (command.reg == FIFO::FIFO_DISABLED_COMMAND)
				{
					// Optimized away
					continue;
				}
			}

			const u32 reg = (command.reg & 0xffff) >> 2;
			const u32 value = command.value;

			m_ctx->register_state->decode(reg, value);

			if (rsx::prof::enabled()) [[unlikely]]
			{
				// The sequential counter is what the per-method figure should divide by.
				// g_fifo_commands is incremented once per run_FIFO entry, which is a whole
				// packet, so it prices packets.
				rsx::prof::g_fifo_dispatches++;
				rsx::prof::g_method_counts[reg & (rsx::prof::method_slot_count - 1)]++;
			}

			if (auto method = methods[reg])
			{
				// Splits the handler bodies out of fifo_decode, which is the enclosing scope of
				// the whole loop and therefore holds both. Arkham City spends 38.5 ms a frame in
				// there at 164 ns a dispatch against Sonic's 45 ns on the same machinery, so the
				// difference is in what the handlers do, and nothing separates the two.
				//
				// This is the one per-dispatch scope in the profiler, and an earlier attempt at
				// one measured mostly itself. It is affordable here only because it wraps a
				// call: two counter reads against a handler body, not against a loop iteration.
				// Still costs a few percent of the bucket it splits -- read the split, not the
				// total.
				// Bills the same interval to this method's slot as well, so the handlers can be
				// ranked by cost rather than by how often they are called.
				::rsx::prof::method_scope method_prof_scope{
					static_cast<u32>(reg & (::rsx::prof::method_slot_count - 1)) };

				method(m_ctx, reg, value);

				// Relaxed: `again` is only set by this thread, by the handler just called.
				// The seq_cst default is an ldar on ARM64 for no benefit here.
				if (state.observe() & cpu_flag::again)
				{
					m_ctx->register_state->decode(reg, m_ctx->register_state->latch);
					break;
				}
			}
			else if (m_ctx->register_state->latch != value)
			{
				// Something changed, set signal flags if any specified
				m_graphics_state |= state_signals[reg];
			}
		}
		while (fifo_ctrl->read_unsafe(command));

		fifo_ctrl->sync_get();
	}
}
