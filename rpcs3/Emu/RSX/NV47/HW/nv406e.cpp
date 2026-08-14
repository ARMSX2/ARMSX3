#include "stdafx.h"
#include "nv406e.h"
#include "nv47_sync.hpp"

#include "Emu/RSX/RSXThread.h"
#include "Emu/RSX/rsx_profiler.h"
#include "util/sysinfo.hpp"

#include "context_accessors.define.h"

namespace rsx
{
	namespace nv406e
	{
		void set_reference(context* ctx, u32 /*reg*/, u32 arg)
		{
			RSX(ctx)->sync();

			// Write ref+get (get will be written again with the same value at command end)
			auto& dma = *vm::_ptr<RsxDmaControl>(RSX(ctx)->dma_address);
			dma.get.release(RSX(ctx)->fifo_ctrl->get_pos());
			dma.ref.store(arg);
		}

		void semaphore_acquire(context* ctx, u32 /*reg*/, u32 arg)
		{
			RSX(ctx)->sync_point_request.release(true);
			const u32 addr = get_address(REGS(ctx)->semaphore_offset_406e(), REGS(ctx)->semaphore_context_dma_406e());

			// Syncronization point, may be associated with memory changes without actually changing addresses
			RSX(ctx)->m_graphics_state |= rsx::pipeline_state::fragment_program_needs_rehash;

			const auto& sema = vm::_ref<RsxSemaphore>(addr);
			const auto& atomic_sema = vm::_ref<atomic_t<RsxSemaphore>>(addr);

			if (sema == arg)
			{
				// Flip semaphore doesnt need wake-up delay
				if (addr != RSX(ctx)->label_addr + 0x10)
				{
					RSX(ctx)->flush_fifo();
					RSX(ctx)->fifo_wake_delay(2);
				}

				return;
			}
			else
			{
				RSX(ctx)->flush_fifo();
			}

			u64 start = get_system_time();
			u64 last_check_val = start;

#if defined(ARCH_ARM64)
			u64 spin_budget = 0;
			// Without the kernel's architected timer event stream a monitor-less
			// WFE has no bounded wake, so the event-stream fallback below must
			// stay disabled and the wait keeps the armed-spin shape.
			const bool has_event_stream = utils::has_wfe_event_stream();
#endif

			while (true)
			{
				const RsxSemaphore observed = sema;

				if (observed == arg)
				{
					break;
				}

				if (RSX(ctx)->test_stopped())
				{
					RSX(ctx)->state += cpu_flag::again;
					return;
				}

				if (const auto tdr = static_cast<u64>(g_cfg.video.driver_recovery_timeout))
				{
					const u64 current = get_system_time();

					if (current - last_check_val > 20'000)
					{
						// Suspicious amnount of time has passed
						// External pause such as debuggers' pause or operating system sleep may have taken place
						// Ignore it
						start += current - last_check_val;
					}

					last_check_val = current;

					if ((current - start) > tdr)
					{
						// If longer than driver timeout force exit. The awaited and
						// last-observed values are logged so a timeout caused by a
						// transient value (stored, then overwritten before the waiter
						// observed it) is distinguishable from a never-signaled one.
						rsx_log.error("nv406e::semaphore_acquire has timed out. semaphore_address=0x%X, awaited=0x%X, observed=0x%X", addr, arg, static_cast<u32>(observed));
						break;
					}
				}

				if (RSX(ctx)->external_interrupt_lock ||
					(RSX(ctx)->state & (cpu_flag::dbg_global_pause + cpu_flag::exit)) == cpu_flag::dbg_global_pause)
				{
					RSX(ctx)->cpu_wait({});
					continue;
				}

				RSX(ctx)->on_semaphore_acquire_wait();

				RSX_PROF_SCOPE(idle);

#if defined(ARCH_ARM64)
				// The armed one-shot wait below does not park on every core: on Oryon
				// (Snapdragon 8 Elite class) WFE returns immediately while the exclusive
				// monitor is armed, so this loop ran at tens of millions of iterations per
				// second instead of waiting. Give the armed form a short window first - on
				// cores where it parks it keeps its instant wake-on-write, and where it
				// does not it acts as a brief spin that still catches short waits - then
				// interleave the event-stream park, which is what actually paces the loop
				// on a core whose armed WFE spins. The armed one-shot still runs on every
				// iteration (it costs ~nothing where it is broken), so on cores where it
				// parks, wake-on-write is kept even after the budget is spent.
				if (has_event_stream && ++spin_budget > 500)
				{
					utils::wait_for_event();
				}
#endif
				// On x86 this waits on the address (umwait/mwaitx), bounded by the
				// timeout. On ARM the timeout is ignored: the wait ends on a write
				// to the armed cache line or an event-stream tick on cores where
				// the armed WFE parks, and returns immediately on cores where it
				// does not (measured on Oryon), where wait_for_event() above
				// provides the pacing instead.
				utils::spin_on_cacheline_once(atomic_sema, observed, 100);
			}

			RSX(ctx)->fifo_wake_delay();
			RSX(ctx)->performance_counters.idle_time += (get_system_time() - start);
		}

		void semaphore_release(context* ctx, u32 reg, u32 arg)
		{
			const u32 offset = REGS(ctx)->semaphore_offset_406e();

			if (offset % 4)
			{
				rsx_log.warning("NV406E semaphore release is using unaligned semaphore, ignoring. (offset=0x%x)", offset);
				return;
			}

			const u32 ctxt = REGS(ctx)->semaphore_context_dma_406e();

			// By avoiding doing this on flip's semaphore release
			// We allow last gcm's registers reset to occur in case of a crash
			if (const bool is_flip_sema = (offset == 0x10 && ctxt == CELL_GCM_CONTEXT_DMA_SEMAPHORE_R);
				!is_flip_sema)
			{
				RSX(ctx)->sync_point_request.release(true);
			}

			const u32 addr = get_address(offset, ctxt);

			// TODO: Check if possible to write on reservations
			if (RSX(ctx)->label_addr >> 28 != addr >> 28)
			{
				rsx_log.error("NV406E semaphore unexpected address. Please report to the developers. (offset=0x%x, addr=0x%x)", offset, addr);
				RSX(ctx)->recover_fifo();
				return;
			}

			if (addr == RSX(ctx)->device_addr + 0x30 && !arg)
			{
				// HW flip synchronization related, 1 is not written without display queue command (TODO: make it behave as real hw)
				arg = 1;
			}

			util::write_gcm_label<false, true>(ctx, reg, addr, arg);
		}
	}
}
