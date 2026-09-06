#include "stdafx.h"
#include "rsx_profiler.h"
#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/PPUDisAsm.h"
#include "Emu/Cell/SPUDisAsm.h"
#include "Emu/Memory/vm.h"
#include "Emu/Memory/vm_reservation.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/Cell/timers.hpp"
#include "Emu/Cell/lv2/sys_event.h"
#include "Emu/Cell/lv2/sys_mutex.h"
#include "Emu/Cell/lv2/sys_lwcond.h"
#include "Emu/RSX/Overlays/overlay_message_dialog.h"
#include "Emu/RSX/Overlays/overlay_manager.h"
#include "Emu/Cell/lv2/sys_lwmutex.h"
#include "Emu/Cell/lv2/sys_semaphore.h"
#include "Emu/Cell/lv2/sys_cond.h"
#include "Emu/system_config.h"

extern atomic_t<u64> g_spu_group_susp_us;
extern atomic_t<u64> g_spu_group_susp_count;
extern atomic_t<u64> g_spu_group_susp_max;
extern atomic_t<u64> g_sema_wait_us;
extern atomic_t<u64> g_sema_wait_count;
extern atomic_t<u64> g_sema_wait_max_us;
extern atomic_t<u64> g_sema_hist[6];

#include "Emu/IdManager.h"
#include "Emu/RSX/RSXThread.h"

#include "util/sysinfo.hpp"

#include <string>
#include <algorithm>
#include <utility>
#include <dlfcn.h>
#include "gcm_printing.h"

// SPU -> PPU event delivery counters, defined in SPUThread.cpp. Neither of that path's two
// failure modes -- a silent EAGAIN retry, or an event DROPPED onto a full queue -- is visible
// in a log, so a SPURS kernel spinning on event handoff while the RSX starves reads exactly
// like one doing useful work.
extern atomic_t<u64> g_rsx_ev_attempt;
extern atomic_t<u64> g_rsx_ev_dropped;
extern atomic_t<u64> g_rsx_ev_busy_spin;
extern atomic_t<u64> g_rsx_ev_again;
extern atomic_t<u32> g_rsx_q_id;
extern atomic_t<u32> g_rsx_q_pending;
extern atomic_t<u32> g_rsx_q_waiter;
extern atomic_t<u64> g_spu_event_throw_ok;
extern atomic_t<u64> g_spu_event_throw_drop;
extern atomic_t<u64> g_spu_event_throw_again;
extern atomic_t<u64> g_spu_event_setbit_ok;
extern atomic_t<u64> g_spu_event_setbit_again;

LOG_CHANNEL(prof_log, "RSXPROF");

namespace rsx::prof
{
	std::atomic<bool> g_enabled{false};
	accounting g_acc{};
	std::atomic<u64> g_last_frame_tsc{0};

	// Guest PC sampling. The vblank thread ticks ~60Hz regardless of what anything else is
	// doing, so sampling main_thread's cia there is a cheap statistical profiler for the GUEST --
	// the one question none of this session's instrumentation could answer. Stall dumps only ever
	// fire on frames already over 60ms, so they say where a bad frame is, never where the time
	// goes overall.
	//
	// Fixed table, no allocation, no locks: PCs are quantised to 64 bytes so a loop lands in one
	// slot, and a full table just stops learning new sites rather than evicting.
	struct pc_bucket { u32 pc; u32 hits; };
	pc_bucket g_pc_samples[24]{};
	u64 g_pc_total = 0;

	// SPU-side sampling.
	//
	// The guest profiler only ever looked at PPU threads, so every conclusion about "the PPU is
	// waiting on an SPU" has been inference from the PPU side. This says what the SPUs are
	// actually doing: how many are running at all, and where their PCs sit. Saturated SPUs mean
	// the work is real and the answer is codegen or scheduling; idle SPUs while the PPU waits
	// means they are blocked on something and the answer is elsewhere entirely.
	pc_bucket g_spu_samples[24]{};
	u64 g_spu_total = 0;
	u64 g_spu_running_sum = 0;
	u64 g_spu_total_sum = 0;
	u64 g_spu_stopped_sum = 0;
	u64 g_spu_suspended_sum = 0;
	u64 g_spu_tick_count = 0;

	// Where main_thread's wall time goes, bucketed by what it is doing.
	//
	// Everything so far has measured the machine's resources -- GPU, CPU, faults, throttling --
	// and all of them came back idle during a 117ms frame. The RSX spike traces say the RSX is
	// waiting on an empty FIFO, so the guest is the critical path, and main_thread runs at only
	// 28% CPU: it alternates between executing and blocking. Nothing has ever measured WHAT it
	// blocks on, weighted by time, which is the actual question.
	//
	// Sampled at the same 200Hz as the PC histogram: a thread's share of samples is its share of
	// wall time. "RUNNING" means executing guest code; everything else is the lv2 or HLE call it
	// is sitting in.
	// A second PC histogram, sampled ONLY while a slow frame is in progress.
	//
	// Every guest measurement so far has averaged across all frames, which drowns the frames that
	// matter: 9 of 10 frames are fine, so a hot spot in the bad ones is diluted ~10x and reads as
	// background. The semaphore distribution just ruled the waits out -- nothing exceeds 14ms, so
	// a 138ms frame is not spent blocked -- which means main_thread is EXECUTING through it, and
	// the question is what.
	pc_bucket g_stall_pc[24]{};
	u64 g_stall_pc_total = 0;

	fifo_rec_t g_fifo_ring[fifo_ring_size]{};
	u32 g_fifo_ring_pos = 0;

	// SPU state during slow frames only.
	//
	// Testing one specific mechanism: an SPU that holds the guest cellSync mutex and then calls
	// sys_spu_thread_receive_event on an empty queue has its WHOLE group suspended, untimed, so
	// it cannot release the lock -- while the event that would resume it comes from the PPU,
	// which is at that moment spinning on the very lock the SPU holds. That is a circular wait,
	// and it would look exactly like this: 70% of a slow frame in cellSyncMutexTryLock, six
	// waiters queued, every resource idle.
	//
	// If group-suspension is markedly higher during slow frames than overall, that is the shape.
	// If it is flat, the circular wait is not happening and the holder is slow for another reason.
	u64 g_stall_spu_susp = 0;
	u64 g_stall_spu_wait = 0;
	u64 g_stall_spu_exec = 0;
	u64 g_stall_spu_ticks = 0;

	// Where the ONE running SPU is during a slow frame.
	//
	// Everything else is eliminated by measurement: GPU max 14.9ms, CPU 11%, semaphore waits max
	// 14ms, group parks max 9.7ms -- none can make a 135ms frame. What remains is main_thread
	// spinning ~97ms on a guest mutex while the nine worker threads are all parked at one address,
	// so none of them holds it, and about one SPU is executing. That SPU is the holder, and this
	// says what it is doing while it holds.
	pc_bucket g_stall_spu_pc[24]{};
	u64 g_stall_spu_pc_total = 0;

	// PUTLLC success vs failure, summed across SPU threads.
	//
	// These counters have existed all along and are reported nowhere, yet they settle the question
	// the last two fixes both depended on. If conditional stores are FAILING in bulk, the SPU is
	// losing a reservation race and the livelock is ours to fix in the reservation path. If they
	// are SUCCEEDING, the SPU is taking and releasing the ticket normally and the spin is the
	// game's own busy-wait -- a completely different problem, and neither of the last two commits
	// would have touched it.
	u64 g_putllc_calls_prev = 0;
	u64 g_putllc_fails_prev = 0;
	u64 g_putllc_barrier_prev = 0;
	u64 g_putllc_barrier_spurs_prev = 0;
	u64 g_spu_ev_prev[5] = {};
	u64 g_stall_ev_prev[5] = {};
	u64 g_stall_rsxev_prev[4] = {};
	u64 g_stall_putllc_prev = 0;
	u64 g_stall_barrier_prev = 0;

	struct fn_bucket { const char* fn; u32 hits; };
	fn_bucket g_main_state[20]{};
	u64 g_main_total = 0;


	// Sampled from a dedicated thread rather than the vblank thread.
	//
	// The vblank thread wakes at a fixed phase, so every sample landed within microseconds of the
	// vblank instant -- which is the one moment in the frame when the previous frame's SPU work
	// has drained and the next has not been kicked, i.e. the structural minimum of SPU occupancy,
	// and equally a biased instant for the PPU. The headline "54.8% of guest time in
	// cellSyncMutexTryLock" was measured that way and may be partly that bias.
	//
	// This runs free of the frame clock at ~200Hz, which is uncorrelated with vblank at 60Hz.
	// Defined below; the sampler drives it because it must keep running after frames stop.
	bool poll_stall();

	// The sampler thread is shared, so its handle has to outlive any one start_sampler()
	// call. stop_sampler() is the only thing that may destroy it.
	std::unique_ptr<named_thread<std::function<void()>>> g_sampler;

	void start_sampler()
	{
		auto& s_sampler = g_sampler;

		if (s_sampler)
		{
			return;
		}

		s_sampler = std::make_unique<named_thread<std::function<void()>>>("RSX Sampler"sv, []()
		{
			while (thread_ctrl::state() != thread_state::aborting)
			{
				if (g_enabled.load())
				{
					sample_guest_pc();

					// Self-rate-limited to once per 5s. Driven from here, not the frame path:
					// a hang is precisely when no frame arrives to drive anything.
					poll_stall();
				}

				// 5ms: fast enough for a useful sample count over a 300-frame window, slow enough
				// that the walk itself is not a load on a machine this contended.
				thread_ctrl::wait_for(5'000);
			}
		});
	}

	void stop_sampler()
	{
		// Join, do not merely disarm. The walks below go through idm, and Emu teardown destroys
		// the id manager's storage along with the rest of g_fxo without taking id_manager::g_mutex
		// -- so the reader lock those walks hold does not exclude it, and a sampler still running
		// at that point reads freed memory. Closing a game crashed here with a 0xcc-poisoned
		// pointer inside sample_guest_pc. Checking a flag would only narrow the window; the thread
		// has to be gone before the objects are.
		g_sampler.reset();
	}

	// NOTE: every idm::select here takes the reader lock, i.e. the DEFAULT, not idm::unlocked.
	//
	// idm::select's Lock parameter is not a hint: with idm::unlocked it binds a reference to
	// id_manager::g_mutex and never locks it, then walks map.vec_data regardless. That is
	// tolerable for a one-shot dump from the vblank thread, which is where this code started.
	// It is not tolerable here -- this samples at 200Hz and performs several traversals per
	// tick, roughly two thousand unsynchronised walks a second, while SPURS creates and
	// destroys SPU threads underneath it.
	//
	// Ratchet & Clank: A Crack in Time hung on load and then died with SEGV_ACCERR inside an
	// SPU thread, and loaded perfectly with the profiler switched off. A reader lock is shared,
	// so it excludes only thread creation and destruction -- which is precisely the mutation
	// being raced -- and nothing inside these callbacks blocks.
	// Per-THREAD histogram of running PPUs during a stall.
	//
	// The existing stall histogram records only the FIRST non-waiting PPU it finds, which on this
	// hang produced a flat, useless spread: the PPUs run about 5% of sampler ticks, so the one
	// sample per tick lands on whichever thread happened to be awake. Recording every running
	// thread WITH its id answers the actual question -- which thread is executing while the game
	// refuses to render, and where.
	struct run_slot_t
	{
		atomic_t<u32> pc;
		atomic_t<u32> tid;
		atomic_t<u32> hits;
	};

	run_slot_t g_run_hist[256]{};
	atomic_t<u32> g_run_ticks{0};

	void record_running(u32 pc, u32 tid)
	{
		const u32 h = ((pc >> 2) ^ (tid << 3)) % 256;

		for (u32 i = 0; i < 12; i++)
		{
			auto& b = g_run_hist[(h + i) % 256];

			if (b.hits.load() && b.pc.load() == pc && b.tid.load() == tid)
			{
				b.hits++;
				return;
			}

			if (!b.hits.load())
			{
				b.pc = pc;
				b.tid = tid;
				b.hits++;
				return;
			}
		}
	}

	// SPURS control-block address, learned from any SPU thread. Read by the label watch below
	// WITHOUT a second idm walk -- nesting idm::select deadlocks the sampler.
	u32 g_spurs_addr_seen = 0;

	void sample_guest_pc()
	{
		if (!g_enabled.load())
		{
			return;
		}

		// Watch the one label the whole hang turns on.
		//
		// nv406e::semaphore_acquire times out on 0x40300CC0 in 15 of 15 captured hangs, ALWAYS
		// exactly one increment short, and always 2.7-9.9s BEFORE the FIFO freezes -- so it is the
		// trigger. Neither RSX label writer ever touches that address (both instrumented), which
		// means the GUEST writes it and the guest stops. Sampling its value at 200Hz and logging
		// only transitions gives the cadence and, more importantly, the exact instant it stops --
		// which is the thing to line up against what the guest was doing.
		{
			static u32 s_label_prev = 0;
			static u64 s_label_last_us = 0;
			static u64 s_label_stuck_us = 0;
			static bool s_label_seen = false;

			constexpr u32 label = 0x40300CC0;

			// wklReadyCount1[5] climbs 0 -> 2 -> 3 across the last two frames before the label dies and
			// never falls again: workload 5 keeps asking for SPUs and nothing picks it up. Log the rows
			// that decide whether the SPURS kernel is ALLOWED to schedule it -- current/pending/max
			// contention -- so a refusal can be told apart from a workload that simply never runs.
			const auto spurs_state = []() -> std::string
			{
				const u32 sa = g_spurs_addr_seen;

				if (!sa || !vm::check_addr(sa, vm::page_readable, 0x80))
				{
					return "<no spurs>";
				}

				const auto row = [sa](u32 off) -> std::string
				{
					std::string r;

					for (u32 i = 0; i < 16; i++)
					{
						fmt::append(r, "%02x", vm::read8(sa + off + i));
					}

					return r;
				};

				return fmt::format("rdy1=%s cur=%s pend=%s max=%s sig=%04x", row(0x00), row(0x20), row(0x30),
					row(0x50), (vm::read8(sa + 0x70) << 8) | vm::read8(sa + 0x71));
			};

			if (vm::check_addr(label, vm::page_readable, 4))
			{
				const u32 now_val = vm::_ref<atomic_t<RsxSemaphore>>(label).observe();
				const u64 now_us = get_system_time();

				if (!s_label_seen)
				{
					s_label_seen = true;
					s_label_prev = now_val;
					s_label_last_us = now_us;
				}
				else if (now_val != s_label_prev)
				{
					prof_log.success("LABEL 0x%x: 0x%x -> 0x%x after %.3fs | %s", label, s_label_prev, now_val,
						(now_us - s_label_last_us) / 1000000.0, spurs_state());

					s_label_prev = now_val;
					s_label_last_us = now_us;
					s_label_stuck_us = 0;
				}
				else if (now_us - s_label_last_us > 1000000 && now_us - s_label_stuck_us > 1000000)
				{
					// The label is dead. Keep sampling: if the ready count keeps climbing while current
					// contention stays at zero, the kernel is refusing to schedule the workload and the
					// reason is in these rows, not in the workload's own code.
					s_label_stuck_us = now_us;

					prof_log.error("LABEL 0x%x STUCK at 0x%x for %.1fs | %s", label, s_label_prev,
						(now_us - s_label_last_us) / 1000000.0, spurs_state());
				}
			}
		}

		u32 pc = 0;

		idm::select<named_thread<ppu_thread>>([&pc](u32, ppu_thread& ppu)
		{
			// Only a thread actually executing guest code; a blocked one has no PC worth having.
			if (!pc && !(ppu.state.load() & cpu_flag::wait))
			{
				pc = ppu.cia & ~0x3fu;
			}
		});

		{
			bool deep = false;
			const u64 lf = g_last_frame_tsc.load();
			const u64 fq = utils::get_tsc_freq();

			if (lf && fq)
			{
				const u64 nw = utils::get_tsc();
				deep = nw > lf && ((nw - lf) * 1000ull) / fq >= 1000;
			}

			if (deep)
			{
				g_run_ticks++;

				idm::select<named_thread<ppu_thread>>([](u32 tid, ppu_thread& p)
				{
					if (!(p.state.load() & cpu_flag::wait))
					{
						record_running(p.cia, tid);
					}
				});
			}
		}

		// Slow frame in progress right now? Computed before the SPU walk so both histograms can
		// use it.
		bool in_stall = false;
		{
			const u64 last = g_last_frame_tsc.load();
			const u64 freq = utils::get_tsc_freq();

			if (last && freq)
			{
				const u64 now_tsc = utils::get_tsc();
				in_stall = now_tsc > last && ((now_tsc - last) * 1000ull) / freq >= 33;
			}
		}

		// SPU side, every tick regardless of whether a PPU was running.
		{
			u32 running = 0;
			u32 spu_pc = 0;

			u32 total = 0;
			u32 stopped = 0;
			u32 suspended = 0;

			idm::select<named_thread<spu_thread>>([&](u32, spu_thread& spu)
			{
				if (spu.spurs_addr && spu.spurs_addr != 0u - 0x80u)
				{
					g_spurs_addr_seen = spu.spurs_addr;
				}

				const auto st = spu.state.load();

				total++;

				if (st & cpu_flag::stop)
				{
					stopped++;
					return;
				}

				// Parked by the group, not merely waiting in a syscall.
				//
				// sys_spu_thread_receive_event on an empty queue sets cpu_flag::suspend on EVERY
				// thread in the group and parks them with an untimed state.wait(), cleared only by
				// resume_spu_thread_group_from_waiting. That is the one mechanism that idles a
				// whole group at once, and lumping it in with ordinary waiting hides exactly the
				// thing worth knowing during a 117ms frame where nothing is running.
				if (st & cpu_flag::suspend)
				{
					suspended++;
					return;
				}

				if (st & cpu_flag::wait)
				{
					return;
				}

				running++;

				if (!spu_pc)
				{
					spu_pc = spu.pc & ~0x3fu;
				}
			});

			g_spu_running_sum += running;
			g_spu_total_sum += total;
			g_spu_stopped_sum += stopped;
			g_spu_suspended_sum += suspended;

			if (in_stall)
			{
				g_stall_spu_susp += suspended;
				g_stall_spu_wait += total - running - stopped - suspended;
				g_stall_spu_exec += running;
				g_stall_spu_ticks++;
			}
			g_spu_tick_count++;

			if (spu_pc && in_stall)
			{
				// Disassemble the loop once. The PPU side of this only became legible when its PC was
				// disassembled -- that is what identified cellSyncMutexTryLock -- and the SPU holder is
				// now pinned to a 64-byte window, so the same treatment should say what it spins on.
				// Local store is the thread's own memory and always mapped, so no address check is
				// needed; reading it costs nothing beyond the one dump.
				g_stall_spu_pc_total++;

				for (auto& b : g_stall_spu_pc)
				{
					if (b.pc == spu_pc) { b.hits++; break; }
					if (!b.pc) { b.pc = spu_pc; b.hits = 1; break; }
				}
			}

			if (spu_pc)
			{
				g_spu_total++;

				for (auto& b : g_spu_samples)
				{
					if (b.pc == spu_pc) { b.hits++; break; }
					if (!b.pc) { b.pc = spu_pc; b.hits = 1; break; }
				}
			}
		}

		// main_thread's own time budget, independent of which thread the PC histogram picked.
		idm::select<named_thread<ppu_thread>>([](u32 id, ppu_thread& ppu)
		{
			if (id != 0x1000000)
			{
				return;
			}

			const char* what = "RUNNING";

			if (ppu.state.load() & cpu_flag::wait)
			{
				what = ppu.current_function ? ppu.current_function : "?blocked";
			}

			g_main_total++;

			for (auto& b : g_main_state)
			{
				if (b.fn == what) { b.hits++; return; }
				if (!b.fn) { b.fn = what; b.hits = 1; return; }
			}
		});

		if (!pc)
		{
			return;
		}

		g_pc_total++;

		if (in_stall)
		{
			g_stall_pc_total++;

			for (auto& b : g_stall_pc)
			{
				if (b.pc == pc) { b.hits++; break; }
				if (!b.pc) { b.pc = pc; b.hits = 1; break; }
			}
		}

		for (auto& b : g_pc_samples)
		{
			if (b.pc == pc)
			{
				b.hits++;
				return;
			}

			if (!b.pc)
			{
				b.pc = pc;
				b.hits = 1;
				return;
			}
		}
	}

	// Frame pacing for the current window only; reset by dump_and_reset.
	u64 g_slow_frames = 0;
	u64 g_very_slow_frames = 0;
	u64 g_worst_frame_ns = 0;
	bucket g_current = bucket::unclassified;
	u64 g_last_switch = 0;
	const void* g_owner_thread = nullptr;

	// Stall reporting state. See poll_stall.
	u64 g_last_stall_check = 0;
	u64 g_stall_frames = umax;
	u64 g_stall_started = 0;
	u64 g_fifo_refills = 0;
	u64 g_fifo_commands = 0;
	u64 g_fifo_dispatches = 0;
	u64 g_draw_calls = 0;
	u64 g_present_checks = 0;
	u64 g_frame_cleanups = 0;
	u64 g_fence_polls = 0;
	u64 g_fence_polls_not_ready = 0;
	u32 g_pass_ordinal = 0;
	u64 g_pass_draws[pass_slot_count] = {};
	u16 g_pass_width[pass_slot_count] = {};
	u16 g_pass_height[pass_slot_count] = {};
	u64 g_pass_vertices[pass_slot_count] = {};
	u64 g_pass_barriers[pass_slot_count] = {};
	u64 g_pass_cyclic[pass_slot_count] = {};
	u64 g_pass_vp_words[pass_slot_count] = {};
	u64 g_pass_fp_words[pass_slot_count] = {};
	u64 g_pass_subdraws[pass_slot_count] = {};
	u64 g_pass_queries[pass_slot_count] = {};
	u64 g_xform_program_calls = 0;
	u64 g_xform_program_words = 0;
	u64 g_xform_const_calls = 0;
	u64 g_xform_const_words = 0;
	u32 g_method_counts[method_slot_count] = {};
	u64 g_method_ticks[method_slot_count] = {};
	u64 g_fifo_refill_bytes = 0;
	u64 g_fifo_refill_stalls = 0;
	u64 g_fifo_refill_stall_us = 0;
	u64 g_render_passes = 0;
	u64 g_mprotect_calls = 0;
	u64 g_mprotect_bytes = 0;
	u64 g_access_violations = 0;
	std::atomic<u64> g_access_violation_tsc{0};
	u64 g_rp_sites[rp_site_count] = {};
	const char* g_rp_site_names[rp_site_count] = {
		"Draw:1044",
		"Draw:1093",
		"QueryPool:217",
		"Compute:146",
		"Texture:60",
		"Texture:225",
		"Texture:352",
		"Texture:494",
		"Texture:534",
		"Texture:921",
		"Barrier:img",
		"Barrier:buf",
		"Barrier:mem",
		"Barrier:inout",
		"TexCache:87",
		"TexCache:1227",
		"ImgHelper:43",
		"GSR:2811",
		"Barrier:xition",
	};
	u64 g_flush_sites[flush_site_count] = {};
	const char* g_flush_site_names[flush_site_count] = {
		"GSR:996",
		"GSR:1064",
		"GSR:1156",
		"GSR:1171",
		"GSR:1565",
		"GSR:1650",
		"GSR:1746",
		"GSR:1781",
		"GSR:1820",
		"GSR:2628",
		"GSR:2787",
		"GSR:2861",
		"Present:77",
		"Present:156",
		"Present:247",
		"Present:251",
		"Present:413",
		"Present:573",
		"Present:845",
		"Present:1091",
		"Present:1102",
	};

	const void* g_rp_callers[2][rp_caller_slots] = {};
	u64 g_rp_caller_counts[2][rp_caller_slots] = {};

	void note_rp_teardown(const void* caller, u32 level)
	{
		// Linear scan of a tiny table. The set of distinct callers is small and stable, so
		// this finds a hit in the first few slots; if it ever overflows, the surplus is
		// charged to the last slot rather than silently dropped.
		auto& addrs = g_rp_callers[level];
		auto& counts = g_rp_caller_counts[level];

		for (usz i = 0; i < rp_caller_slots; i++)
		{
			if (addrs[i] == caller)
			{
				counts[i]++;
				return;
			}

			if (!addrs[i])
			{
				addrs[i] = caller;
				counts[i] = 1;
				return;
			}
		}

		counts[rp_caller_slots - 1]++;
	}

	void bind_to_current_thread()
	{
		g_owner_thread = current_thread_token();
	}

	const char* name_of(bucket b)
	{
		switch (b)
		{
		case bucket::fifo_decode: return "FIFO decode";
		case bucket::fifo_refill: return "FIFO refill";
		case bucket::method_call: return "Method handlers";
		case bucket::rsx_barrier: return "RSX barrier";
		case bucket::dma_copy: return "DMA copy";
		case bucket::blit_scale: return "Blit SW scale";
		case bucket::xform_program: return "Xform program";
		case bucket::xform_const: return "Xform constant";
		case bucket::draw_setup: return "Draw setup";
		case bucket::draw_prologue: return "Draw prologue";
		case bucket::draw_epilogue: return "Draw epilogue";
		case bucket::wr_barrier: return "Write barrier";
		case bucket::rtt_write: return "RTT on_write";
		case bucket::tex_release: return "Temp tex release";
		case bucket::present_check: return "Present check";
		case bucket::swap_wait: return "Swap fence wait";
		case bucket::res_trim: return "Resource trim";
		case bucket::res_gc: return "Resource destroy";
		case bucket::fence_poll: return "Fence poll";
		case bucket::vertex: return "Vertex/index";
		case bucket::shader_translate: return "Shader translate";
		case bucket::shader_compile: return "Shader compile";
		case bucket::pipeline: return "Pipeline";
		case bucket::descriptors: return "Descriptors";
		case bucket::texcache_lookup: return "Texcache lookup";
		case bucket::texture_upload: return "Texture upload";
		case bucket::rt_prep: return "RT prep";
		case bucket::blit_resolve: return "Blit/resolve";
		case bucket::barrier: return "Barriers";
		case bucket::cmdbuf: return "Cmdbuf record";
		case bucket::submit: return "Submit";
		case bucket::fence_wait: return "Fence wait";
		case bucket::present_wait: return "Present wait";
		case bucket::page_protect: return "Page protect";
		case bucket::zcull: return "ZCULL update";
		case bucket::local_task: return "Local task";
		case bucket::idle: return "Idle";
		case bucket::idle_fifo: return "Idle: FIFO empty";
		case bucket::idle_sema: return "Idle: guest semaphore";
		case bucket::idle_pause: return "Idle: cpu_wait";
		case bucket::unclassified: return "Unclassified";
		default: return "?";
		}
	}

	void set_enabled(bool enabled)
	{
		if (enabled == g_enabled.load(std::memory_order_relaxed))
		{
			return;
		}

		// Drop whatever was accumulated, so a window never straddles the switch and
		// reports a partial frame's worth of one bucket against a full window.
		g_acc = {};
		g_acc.window_start = utils::get_tsc();
		g_last_switch = g_acc.window_start;
		// Arming happens from inside the RSX dispatch loop, so that is genuinely where we
		// are. Without this the loop's scope, constructed back when the profiler was off,
		// never became active and its time fell through to unclassified.
		g_current = bucket::fifo_decode;

		bind_to_current_thread();
		g_enabled.store(enabled, std::memory_order_relaxed);
		prof_log.success("RSX profiling %s", enabled ? "enabled" : "disabled");
	}

	// Walk every guest thread and say what it is in. Thread-local scalars only: id, name,
	// state flags, and the current blocking function each thread already records for its own
	// diagnostics. No call stacks, no registers, no guest memory reads -- those are what made
	// the earlier stall dump fault three times and freeze the emulator into something that
	// looked exactly like the hang it was supposed to explain.
	static std::string sample_guest_threads(bool running_only)
	{
		std::string out;

		idm::select<named_thread<ppu_thread>>([&out, running_only](u32 id, ppu_thread& ppu)
		{
			if (running_only && ppu.state.load() & cpu_flag::wait)
			{
				return;
			}

			const auto name = ppu.ppu_tname.load();
			const bool inside = !!ppu.current_function;
			const auto func = inside ? ppu.current_function : ppu.last_function;

			// cia is the guest PC. Across samples it says whether a running thread is
			// making progress or spinning on the same handful of instructions.
			// Argument registers for a thread parked in a syscall.
			//
			// "in=sys_cond_wait" says a thread is blocked but not on WHAT, and on a hang the identity
			// of that object is the whole question -- it is the only way to work back from a blocked
			// waiter to whoever was supposed to signal it. lv2 takes the object id in r3, so r3-r5
			// name the condvar, semaphore, mutex or queue. Printed only for threads actually inside a
			// syscall, where those registers still hold the arguments.
			std::string args;

			if (inside)
			{
				fmt::append(args, " r3=0x%x r4=0x%x r5=0x%x", ppu.gpr[3], ppu.gpr[4], ppu.gpr[5]);
			}

			fmt::append(out, "\n    PPU 0x%07x %-24s [%s] cia=0x%08x %s%s%s", id,
				name ? name->c_str() : "?", ppu.state.load(), ppu.cia,
				inside ? "in=" : "last=", func ? func : "", args);
		});

		// Which lv2 object each blocked thread is actually parked on.
		//
		// "in=sys_cond_wait" names the syscall, not the object, and the argument registers do not
		// survive the wait -- lv2 overwrites r3 with the pending return value, so a blocked waiter
		// reports r3=0. The objects themselves are the only remaining source: each keeps an
		// intrusive queue of the threads parked on it, so walking those queues is the one way to get
		// from "this thread is blocked" to "and THIS is what has to signal it".
		//
		// The state matters as much as the identity. A condvar names its mutex; a mutex names its
		// OWNER. A thread parked on a condvar whose mutex is held by another parked thread is a
		// deadlock, stated outright -- and that is invisible from the thread list alone.
		// Is a guest message dialog currently up?
		//
		// The hang always begins immediately after a cellMsgDialogOpen2 -- the trophy check, the
		// autosave notice, "Load complete." -- and those dialogs are RSX OVERLAYS, drawn during
		// flip. A dialog that needs an OK press but is never drawn cannot be dismissed, and the
		// game waits on it forever while the last frame stays on screen and audio keeps playing.
		// That is the shape of this bug, so say outright whether one is active.
		if (auto mgr = g_fxo->try_get<rsx::overlays::display_manager>())
		{
			if (auto dlg = mgr->get<rsx::overlays::message_dialog>())
			{
				fmt::append(out, "\n    -- MESSAGE DIALOG IS ACTIVE (uid=%u, visible=%d) --", dlg->uid, dlg->visible ? 1 : 0);
			}
			else
			{
				fmt::append(out, "\n    -- no message dialog active --");
			}
		}

		fmt::append(out, "\n    -- lv2 waiters --");

		idm::select<lv2_obj, lv2_cond>([&out](u32 id, lv2_cond& c)
		{
			std::string w;
			u32 n = 0;

			for (auto cpu = +c.sq; cpu && n < 32; cpu = cpu->next_cpu, n++)
			{
				fmt::append(w, " 0x%x", cpu->id);
			}

			if (!w.empty())
			{
				const u64 last = c.dbg_last_signal_us.load();

				fmt::append(out, "\n      cond  0x%08x mutex=0x%08x waiters:%s | signals=%u last_by=0x%x last=%.1fs ago",
					id, c.mtx_id, w, +c.dbg_signal_count, +c.dbg_last_signaller,
					last ? (get_system_time() - last) / 1000000.0 : -1.0);
			}
		});

		// Semaphores that have a waiter but no recent post, gathered for a second pass. The
		// producer's PC has to be resolved outside the walk; see the note inside.
		struct stale_sema_t
		{
			u32 sema_id;
			u32 waiter_id;
			u32 waiter_cia;
			u32 poster_id;
			u32 poster_cia;
			u32 post_lr;   // guest call site of the last successful post
		};

		std::vector<stale_sema_t> stale;

		idm::select<lv2_obj, lv2_sema>([&out, &stale](u32 id, lv2_sema& sem)
		{
			std::string w;
			u32 n = 0;

			for (auto cpu = +sem.sq; cpu && n < 32; cpu = cpu->next_cpu, n++)
			{
				fmt::append(w, " 0x%x", cpu->id);
			}

			if (!w.empty())
			{
				const u64 last = sem.dbg_last_post_us.load();
				const u64 age = last ? (get_system_time() - last) : 0;

				fmt::append(out, "\n      sema  0x%08x val=%d waiters:%s | posts=%u last_poster=0x%x last_post=%.1fs ago",
					id, sem.val.load(), w, +sem.dbg_post_count, +sem.dbg_last_poster, last ? age / 1000000.0 : -1.0);

				// A semaphore with a waiter that nobody has posted for seconds, while the rest of
				// the process ticks normally, is the broken producer/consumer pair -- and it is
				// the only thing on this hang that is actually stuck. Disassemble BOTH ends: the
				// consumer parked on it, and the producer that stopped posting. The registers are
				// gone by now (lv2 overwrites r3), but the code is not, and the instructions
				// around each PC say what the pair is exchanging.
				// Stale OR never posted, recorded here and resolved AFTER this walk.
				//
				// This used to call idm::select for the producer thread from INSIDE this one.
				// id_manager::g_mutex is writer-preferring, so a nested reader blocks as soon as
				// any writer queues while the outer reader still holds it -- a recursive-reader
				// deadlock that froze the VBlank thread and the sampler, and made every capture
				// come back empty. Never nest idm::select.
				if (!w.empty() && (!last || age > 5'000'000))
				{
					for (auto cpu = +sem.sq; cpu; cpu = cpu->next_cpu)
					{
						stale.push_back({ id, cpu->id, static_cast<ppu_thread*>(cpu)->cia, +sem.dbg_last_poster, 0, +sem.dbg_last_post_lr });
						break;
					}
				}
			}
		});

		// Second pass, OUTSIDE every idm walk: resolve each stale semaphore's producer PC and
		// disassemble both ends of the broken handshake.
		//
		// This must not run inside the semaphore walk. id_manager::g_mutex is writer-preferring,
		// so taking a second reader while the first is still held deadlocks the moment any writer
		// queues -- which froze the VBlank thread and the sampler and made several captures come
		// back empty with no reports at all.
		if (!stale.empty())
		{
			idm::select<named_thread<ppu_thread>>([&stale](u32 tid, ppu_thread& pp)
			{
				for (auto& e : stale)
				{
					if (e.poster_id == tid)
					{
						e.poster_cia = pp.cia;
					}
				}
			});

			PPUDisAsm da(cpu_disasm_mode::normal, vm::g_sudo_addr);

			const auto dump_around = [&](const char* who, u32 tid, u32 at)
			{
				if (!at || (at & 3))
				{
					return;
				}

				fmt::append(out, "\n        %s 0x%07x @ 0x%08x:", who, tid, at);

				for (u32 a2 = (at < 0x20 ? 0 : at - 0x20); a2 <= at + 0x1c; a2 += 4)
				{
					if (!vm::check_addr(a2, vm::page_readable, 4))
					{
						continue;
					}

					da.disasm(a2);
					fmt::append(out, "\n          %s %s", a2 == at ? "->" : "  ", da.last_opcode);
				}
			};

			for (const auto& e : stale)
			{
				fmt::append(out, "\n      STALE sema 0x%08x (waiter 0x%07x, producer 0x%07x, last post from 0x%08x):", e.sema_id, e.waiter_id, e.poster_id, e.post_lr);

				// The call site that used to post this. Disassembling here shows the branch that
				// stopped being taken, which is the actual question on a producer that goes quiet.
				// A WIDE window here on purpose. The post itself is two instructions; what matters
				// is the branch upstream that decides whether control ever reaches it, since the
				// producer is alive and simply stops taking that path.
				if (e.post_lr && !(e.post_lr & 3))
				{
					PPUDisAsm wd(cpu_disasm_mode::normal, vm::g_sudo_addr);

					fmt::append(out, "\n        post-site region 0x%08x-0x%08x:", e.post_lr - 0x120, e.post_lr + 0x10);

					for (u32 a3 = e.post_lr - 0x120; a3 <= e.post_lr + 0x10; a3 += 4)
					{
						if (!vm::check_addr(a3, vm::page_readable, 4))
						{
							continue;
						}

						wd.disasm(a3);
						fmt::append(out, "\n          %s %s", a3 == e.post_lr ? "->" : "  ", wd.last_opcode);
					}
				}
				dump_around("consumer", e.waiter_id, e.waiter_cia);

				if (e.poster_id)
				{
					dump_around("producer", e.poster_id, e.poster_cia);
				}
			}
		}

		// LIGHTWEIGHT mutexes and condvars, which were the blind spot.
		//
		// The heavy cond/sema/mutex map above never covered these, and on this title mainThread
		// was caught RUNNING with last=_sys_lwmutex_lock while the net workers sat in
		// _sys_lwcond_queue_wait -- i.e. the interesting waits were happening entirely in objects
		// this dump could not see. A lwmutex keeps its owner in a GUEST-side control block, so it
		// names the holder directly, which is the one thing needed to turn "blocked" into a chain.
		idm::select<lv2_obj, lv2_lwmutex>([&out](u32 id, lv2_lwmutex& lw)
		{
			std::string w;
			u32 n = 0;

			for (auto cpu = lw.load_sq(); cpu && n < 32; cpu = cpu->next_cpu, n++)
			{
				fmt::append(w, " 0x%x", cpu->id);
			}

			u32 owner = 0;
			u32 waiter = 0;

			if (lw.control && vm::check_addr(lw.control.addr(), vm::page_readable, sizeof(sys_lwmutex_t)))
			{
				const auto lv = lw.control->lock_var.load();
				owner = lv.owner;
				waiter = lv.waiter;
			}

			if (!w.empty() || owner)
			{
				fmt::append(out, "\n      lwmutex 0x%08x owner=0x%x waiter=0x%x lwcond_waiters=%d sq:%s",
					id, owner, waiter, +lw.lwcond_waiters, w.empty() ? " -" : w.c_str());
			}
		});

		idm::select<lv2_obj, lv2_lwcond>([&out](u32 id, lv2_lwcond& lc)
		{
			std::string w;
			u32 n = 0;

			for (auto cpu = +lc.sq; cpu && n < 32; cpu = cpu->next_cpu, n++)
			{
				fmt::append(w, " 0x%x", cpu->id);
			}

			if (!w.empty())
			{
				fmt::append(out, "\n      lwcond  0x%08x lwmutex=0x%08x waiters:%s", id, lc.lwid, w);
			}
		});

		idm::select<lv2_obj, lv2_mutex>([&out](u32 id, lv2_mutex& m)
		{
			const auto ctrl = m.control.load();
			std::string w;
			u32 n = 0;

			for (auto cpu = +ctrl.sq; cpu && n < 32; cpu = cpu->next_cpu, n++)
			{
				fmt::append(w, " 0x%x", cpu->id);
			}

			if (!w.empty() || ctrl.owner)
			{
				fmt::append(out, "\n      mutex 0x%08x owner=0x%x locks=%u waiters:%s", id, ctrl.owner, m.lock_count.load(), w.empty() ? " -" : w.c_str());
			}
		});

		// PPUs waiting on a semaphore are usually waiting for an SPU to post it, so the SPU
		// side is the half that actually names the culprit.
		idm::select<named_thread<spu_thread>>([&out, running_only](u32 id, spu_thread& spu)
		{
			if (running_only && spu.state.load() & cpu_flag::wait)
			{
				return;
			}

			const auto name = spu.spu_tname.load();

			// Reservation state, which is the whole question on a SPURS hang.
			//
			// A SPURS kernel with no work parks waiting for its reserved line to change. It wakes
			// on the reservation counter moving. So if the LIVE counter has already advanced past
			// the value this thread recorded, the write it is waiting for has already happened and
			// its wakeup was lost -- it will now sleep forever on a line that moved on without it.
			// That is a different failure from "nothing ever writes the block", and only this
			// comparison tells the two apart.
			// Contents of the line the SPU has reserved -- normally the SPURS control block.
			//
			// The kernels spin in their scheduler polling this line for work. Dumping it answers
			// the question the PC histogram cannot: is a workload actually marked ready and being
			// ignored, or did the PPU never queue one? A previous investigation on another title
			// settled exactly this way ("nothing ever writes the SPURS control block"), so print
			// the bytes rather than infer them.
			std::string line;

			if (const u32 ra = spu.raddr; ra && vm::check_addr(ra, vm::page_readable, 128))
			{
				for (u32 i = 0; i < 32; i++)
				{
					fmt::append(line, "%02x", vm::read8(ra + i));

					if ((i & 7) == 7)
					{
						line += ' ';
					}
				}
			}

			std::string rsv;

			if (const u32 raddr = spu.raddr)
			{
				const u64 live = vm::reservation_acquire(raddr) & ~127ull;
				const u64 held = spu.rtime & ~127ull;

				fmt::append(rsv, " raddr=0x%08x rtime=%u live=%u%s", raddr, held, live, live != held ? " MOVED" : "");

				if (!line.empty())
				{
					fmt::append(rsv, "\n            line: %s", line);
				}
			}

			fmt::append(out, "\n    SPU 0x%07x %-24s [%s] pc=0x%05x %s%s", id,
				name ? name->c_str() : "?", spu.state.load(), spu.pc,
				spu.current_func ? spu.current_func : "", rsv);

			// Disassemble the loop a RUNNING SPURS kernel is executing.
			//
			// On this hang three of five kernels never stop, issuing millions of conditional
			// stores a second against the SPURS control block while no workload is ever
			// dispatched -- so the question is what the guest's own libsre scheduler is testing
			// each iteration. Only for threads not in a wait: a parked one's pc is its park site
			// and says nothing.
			if (!(spu.state.load() & cpu_flag::wait))
			{
				SPUDisAsm sd(cpu_disasm_mode::normal, reinterpret_cast<const u8*>(spu.ls));

				const u32 at = spu.pc;
				const u32 lo = at < 0x18 ? 0 : at - 0x18;

				for (u32 a2 = lo; a2 <= at + 0x14 && a2 < SPU_LS_SIZE; a2 += 4)
				{
					sd.disasm(a2);
					fmt::append(out, "\n          %s 0x%05x  %s", a2 == at ? "->" : "  ", a2, sd.last_opcode);
				}
			}
		});

		return out;
	}

	void stall_watchdog()
	{
		if (!g_enabled.load())
		{
			return;
		}

		const u64 last = g_last_frame_tsc.load();
		const u64 freq = utils::get_tsc_freq();

		if (!last || !freq)
		{
			return;
		}

		const u64 now = utils::get_tsc();

		if (now <= last)
		{
			return;
		}

		const u64 stalled_ms = ((now - last) * 1000ull) / freq;

		if (stalled_ms < 60)
		{
			return;
		}

		// Resample WITHIN a stall, once per vblank tick. One sample per stall could not tell a
		// thread spinning on one instruction from a thread doing slow work sampled at the same
		// phase each time -- and 19 of 30 stalls landed on the identical guest PC, which needs
		// consecutive samples to interpret. Later samples print only the threads still running,
		// since the blocked list does not change and repeating it is just log volume.
		static u64 s_frame = umax;
		static u32 s_in_frame = 0;
		static u32 s_dumps = 0;

		if (s_frame != g_acc.frames)
		{
			s_frame = g_acc.frames;
			s_in_frame = 0;
		}

		if (s_in_frame >= 5 || ++s_dumps > 60)
		{
			return;
		}

		s_in_frame++;

		if (s_in_frame > 1)
		{
			prof_log.error("STALL+%u: %u ms in, still running:%s", s_in_frame, stalled_ms,
				sample_guest_threads(true));
			return;
		}

		prof_log.error("STALL: %u ms into a frame with no flip (sample %u/60)%s",
			stalled_ms, s_dumps, sample_guest_threads(false));
	}

	void tick_frame()
	{
		g_last_frame_tsc.store(utils::get_tsc());

		if (!g_enabled.load(std::memory_order_relaxed)) [[likely]]
		{
			return;
		}

		// Re-bind if the RSX thread is not the one we armed against.
		//
		// Booting a second game without restarting the app builds a new RSX thread, and
		// set_enabled -- the only thing that binds -- early-returns when the setting has not
		// changed, so the profiler stayed bound to the previous game's thread. Every scope
		// then failed its owner check, nothing ever switched buckets, and the whole window was
		// charged to whichever bucket happened to be current. The result reads as "FIFO decode
		// 100%", which is indistinguishable from a real finding and was briefly taken for one.
		if (current_thread_token() != g_owner_thread) [[unlikely]]
		{
			bind_to_current_thread();

			// The window so far belongs to a thread that is gone; keeping it would blend two
			// games into one report.
			g_acc = {};
			g_acc.window_start = utils::get_tsc();
			g_last_switch = g_acc.window_start;
			g_current = bucket::fifo_decode;

			for (auto& c : g_pass_draws) c = 0;
			for (auto& c : g_pass_vertices) c = 0;
			for (auto& c : g_pass_barriers) c = 0;
			for (auto& c : g_pass_cyclic) c = 0;
			for (auto& c : g_pass_vp_words) c = 0;
			for (auto& c : g_pass_fp_words) c = 0;
			for (auto& c : g_pass_subdraws) c = 0;
			for (auto& c : g_pass_queries) c = 0;

			prof_log.warning("RSX profiling re-bound to the current RSX thread");
			return;
		}

		g_acc.frames++;

		// Per-frame spike trace.
		//
		// The window report divides by 300 frames, so a frame that took 200ms and 299 that took
		// 30 average away to nothing -- and variance is the whole complaint. Averages cannot
		// describe a stall; only the frame that stalled can.
		//
		// So diff the buckets against the previous tick and, when a single frame runs long,
		// print where THAT frame's time went. Cheap: one subtraction per bucket per frame, and
		// nothing is printed unless a frame is actually slow.
		{
			static accounting s_prev{};
			static u64 s_last_tsc = 0;
			static u64 s_spikes = 0;

			const u64 now = utils::get_tsc();
			const u64 freq = utils::get_tsc_freq();

			if (s_last_tsc && freq)
			{
				const u64 frame_ns = ((now - s_last_tsc) * 1'000'000'000ull) / freq;

				// Per-window frame pacing, uncapped.
				//
				// The SPIKE trace caps at 40 and the stall watchdog at 60 -- right for log volume,
				// useless for iterative testing: once capped the numbers stop moving, so a change
				// that helped and one that did nothing read identically. I compared two builds off
				// a frozen list before catching it. These reset every window.
				if (frame_ns > 33'000'000ull) g_slow_frames++;
				if (frame_ns > 100'000'000ull) g_very_slow_frames++;
				if (frame_ns > g_worst_frame_ns) g_worst_frame_ns = frame_ns;

				// 50ms: comfortably above a bad-but-normal frame at this frame rate, so this
				// only fires on the excursions worth explaining.
				if (frame_ns > 50'000'000ull && ++s_spikes <= 40)
				{
					std::string detail;

					for (usz i = 0; i < bucket_count; i++)
					{
						const u64 d = g_acc.ticks[i] - s_prev.ticks[i];

						if (!d) continue;

						const u64 ns = (d * 1'000'000'000ull) / freq;

						// Only what actually mattered in this frame.
						if (ns >= 1'000'000ull / 2)
						{
							fmt::append(detail, "\n    %-16s %6.2f ms",
								name_of(static_cast<bucket>(i)), ns / 1'000'000.0);
						}
					}

					// When the stall is Idle, the RSX had nothing to draw -- the guest stopped
					// feeding it. Say what the guest is sitting in, because that is the actual
					// question and nothing else in this report can answer it.
					//
					// Thread-local scalars only: id, name and the current HLE/LV2 function. No
					// call stacks, no registers, no guest memory. Those are what made the old
					// stall dump fault three times and freeze the emulator.
					std::string guest;

					if (const u64 idle_ns = (((g_acc.ticks[static_cast<usz>(bucket::idle)] - s_prev.ticks[static_cast<usz>(bucket::idle)])
								+ (g_acc.ticks[static_cast<usz>(bucket::idle_fifo)] - s_prev.ticks[static_cast<usz>(bucket::idle_fifo)])
								+ (g_acc.ticks[static_cast<usz>(bucket::idle_sema)] - s_prev.ticks[static_cast<usz>(bucket::idle_sema)])
								+ (g_acc.ticks[static_cast<usz>(bucket::idle_pause)] - s_prev.ticks[static_cast<usz>(bucket::idle_pause)])) * 1'000'000'000ull) / freq;
						idle_ns * 2 > frame_ns)
					{
						idm::select<named_thread<ppu_thread>>([&guest](u32 id, ppu_thread& ppu)
						{
							const auto nameptr = ppu.ppu_tname.load();
							const bool inside = !!ppu.current_function;
							const auto func = inside ? ppu.current_function : ppu.last_function;

							fmt::append(guest, "\n    PPU 0x%07x %-24s %s=%s",
								id, nameptr ? nameptr->c_str() : "?",
								inside ? "in" : "last", func ? func : "");
						});
					}

					prof_log.error("SPIKE: frame took %.1f ms (%u so far)%s%s",
						frame_ns / 1'000'000.0, static_cast<u32>(s_spikes),
						detail.empty() ? "\n    (no bucket over 0.5ms -- the time was spent OUTSIDE any instrumented region)" : detail.c_str(),
						guest.c_str());
				}
			}

			s_prev = g_acc;
			s_last_tsc = now;
		}

		// Pass numbering is NOT restarted here.
		//
		// It used to be, on the claim that the GPU timer's event index resets on the same
		// boundary. It does not. tick_frame runs from on_frame_end, BEFORE flip; the GPU timer
		// rotates its slot at the top of flip and then drops every non-frame region recorded on
		// the fresh slot -- which is flip's own overlay and calibration passes. Those passes
		// still increment this counter, so the CPU ordinal ran ahead of the GPU ordinal by the
		// number of present-path passes, and the two by-pass tables described different passes.
		//
		// A whole "anomaly" came out of that: a pass whose GPU cost was joined to another pass's
		// workload read as 36x the per-draw cost of its neighbours. Reset at the flip point
		// instead, where the GPU slot actually rotates. See VKPresent.cpp.

		// Report on a frame boundary rather than a timer, so per-frame costs divide by a
		// whole number of frames and a long stall lands in the window that contains it.
		// ...or once the window has simply lasted long enough.
		//
		// 300 frames is a fine boundary at 60 fps and useless at 1: Soulcalibur V churns at about
		// one frame a second, so the report -- including the PUTLLC barrier histogram that says
		// which loop is causing it -- would not have appeared for five minutes, precisely in the
		// case worth reporting. The frame count still wins when frames are arriving, so healthy
		// windows divide by a whole number of frames exactly as before.
		const u64 tick_now = utils::get_tsc();
		const u64 tick_freq = utils::get_tsc_freq();
		const bool window_is_stale = tick_freq && g_acc.window_start &&
			(tick_now - g_acc.window_start) > tick_freq * 10;

		if (g_acc.frames >= 300 || window_is_stale)
		{
			dump_and_reset();
		}
	}

	// Say what the RSX thread is sitting in when frames have stopped arriving.
	//
	// tick_frame is the only thing that reports and set_enabled is the only thing that arms,
	// and both are reached from on_frame_end -- so a hang that happens before a frame completes
	// leaves the profiler switched off and silent however the setting is set. That is exactly
	// the case worth instrumenting: a boot that never presents, where the compile has finished
	// and the thread is looping somewhere without consuming. Called from do_local_task, which
	// the FIFO loop reaches whether or not frames advance.
	bool poll_stall()
	{
		if (!g_enabled.load(std::memory_order_relaxed)) [[likely]]
		{
			return false;
		}

		// Deliberately NOT gated on being the owner thread, unlike everything else here.
		// This runs from the sampler precisely because the RSX thread is the thing that has
		// stopped: requiring the owner would mean the watchdog can only report while the
		// condition it reports is absent, which is how it sat silent through two hangs.
		// The reads below are plain scalars and a stale bucket name costs nothing.

		const u64 freq = utils::get_tsc_freq();

		if (!freq)
		{
			return false;
		}

		const u64 now = utils::get_tsc();

		if (now - g_last_stall_check < freq * 5)
		{
			return false;
		}

		g_last_stall_check = now;

		// Fire on "barely moving", not only on "stopped".
		//
		// This used to require ZERO frames in the window, which silently excluded an entire
		// failure mode: Soul Calibur V's trophy-check hang keeps completing frames -- it is still
		// drawing the dialog -- so a zero-frame test never fires there, and every diagnostic in
		// this file has therefore only ever observed the other state. Fewer than ~2 fps is not a
		// game that is running, and the guest counters, lv2 waiter map and FIFO ring are exactly
		// as meaningful there.
		const u64 advanced = g_acc.frames - g_stall_frames;
		g_stall_frames = g_acc.frames;

		if (advanced > 10)
		{
			// Healthy enough; tick_frame is doing the reporting.
			g_stall_started = now;
			return false;
		}

		if (!g_stall_started)
		{
			g_stall_started = now;
			return false;
		}

		std::string fifo = "fifo: <no renderer>";

		if (auto* rsxt = rsx::get_current_renderer(); rsxt && rsxt->fifo_ctrl)
		{
			fifo = rsxt->fifo_ctrl->debug_snapshot();
		}

		// GET vs PUT is the whole question for a hang that presents as an idle RSX: equal means
		// the ring really is drained and the guest has stopped producing, unequal means we are
		// sitting on work we never consumed, or never told the guest we consumed.
		// Guest-side counters belong in THIS message, not only in the per-frame profile: the
		// profile reports every 300 frames, and the whole point of this path is that frames have
		// stopped completing, so during the hang it never prints. Deltas since the previous stall
		// report say whether the guest is still working or has genuinely stopped -- an SPU that
		// keeps issuing conditional stores is spinning, one that has gone quiet is parked.
		u64 putllc = 0;
		u64 barrier = 0;

		idm::select<named_thread<spu_thread>>([&](u32, spu_thread& spu)
		{
			putllc += spu.putllc_calls;
			barrier += spu.putllc_barrier;
		});

		const u64 rv[4] = { +g_rsx_ev_attempt, +g_rsx_ev_dropped, +g_rsx_ev_busy_spin, +g_rsx_ev_again };

		const u64 ev[5] = { +g_spu_event_throw_ok, +g_spu_event_throw_drop, +g_spu_event_throw_again, +g_spu_event_setbit_ok, +g_spu_event_setbit_again };

		prof_log.error("RSX barely advancing: %.1fs since progress reset; current bucket '%s', in it for %.2fs\n\tsince last report: PUTLLC +%u (barrier +%u) | SPU events throw +%u dropped +%u retry +%u, setbit +%u retry +%u\n\t%s%s",
			static_cast<double>(now - g_stall_started) / static_cast<double>(freq),
			name_of(g_current),
			static_cast<double>(now - g_last_switch) / static_cast<double>(freq),
			putllc - g_stall_putllc_prev, barrier - g_stall_barrier_prev,
			ev[0] - g_stall_ev_prev[0], ev[1] - g_stall_ev_prev[1], ev[2] - g_stall_ev_prev[2],
			ev[3] - g_stall_ev_prev[3], ev[4] - g_stall_ev_prev[4],
			fifo, sample_guest_threads(false));

		// Delivery of RSX events to the guest, which decides whether _gcm_intr_thread runs at all.
		// Attempts near zero means the vblank source itself has stopped producing; attempts climbing
		// alongside busy-retries means the guest is not draining the queue. Neither is visible in any
		// other counter, and both present as an idle RSX with an empty FIFO.
		// unsent_gcm_events is a LATCH, not a transient. send_event records any non-vblank event
		// it failed to deliver here, and nothing clears it during play -- the only reset is
		// exchange(0) when the RSX thread starts. Meanwhile the flip-notification loop in
		// rsx::thread::flip bails out early whenever it is set ("TODO: A proper fix"), so once a
		// single flip event fails, EVERY later flip notification is abandoned and the guest's
		// VSync semaphore is never posted again. Printing it says whether that has happened.
		if (const auto r = rsx::get_current_renderer())
		{
			prof_log.error("\tunsent_gcm_events=0x%x  (non-zero = flip notifications are being abandoned)", r->unsent_gcm_events.load());
		}

		// The stall PC histogram this file already keeps, surfaced from INSIDE the stall.
		//
		// It is accumulated whenever a slow frame is in progress and reported in the per-300-frame
		// profile -- which is exactly the report that cannot fire here, because frames have
		// stopped completing by definition. On this hang every thread and every sync object
		// measures healthy (the gcm thread posts VSync continuously, mainThread signals its own
		// condvar thousands of times, the draw thread polls every 30us) while the FIFO does not
		// move for minutes, so the remaining question is what the RUNNING code is looping on.
		{
			std::string hot;
			std::vector<pc_bucket> sorted(std::begin(g_stall_pc), std::end(g_stall_pc));

			std::sort(sorted.begin(), sorted.end(), [](const pc_bucket& x, const pc_bucket& y) { return x.hits > y.hits; });

			for (const auto& b : sorted)
			{
				if (!b.hits)
				{
					break;
				}

				fmt::append(hot, "\n      0x%08x  %4.1f%%  (%u)", b.pc, g_stall_pc_total ? b.hits * 100.0 / g_stall_pc_total : 0.0, b.hits);
			}

			if (!hot.empty())
			{
				prof_log.error("\tguest PCs while stalled (%u samples):%s", g_stall_pc_total, hot);
			}
		}

		// Which PPU threads actually execute during the stall, and where.
		{
			std::vector<std::tuple<u32, u32, u32>> top;

			for (auto& b : g_run_hist)
			{
				if (const u32 h = b.hits.load())
				{
					top.emplace_back(h, b.pc.load(), b.tid.load());
				}
			}

			std::sort(top.begin(), top.end(), [](auto& x, auto& y) { return std::get<0>(x) > std::get<0>(y); });

			std::string hot;

			for (usz i = 0; i < top.size() && i < 14; i++)
			{
				fmt::append(hot, "\n      thread=0x%x  pc=0x%08x  hits=%u", std::get<2>(top[i]), std::get<1>(top[i]), std::get<0>(top[i]));
			}

			if (!hot.empty())
			{
				prof_log.error("\trunning PPUs during stall (%u ticks sampled):%s", +g_run_ticks, hot);
			}
		}

		// The last FIFO methods executed before the guest went quiet.
		{
			const u32 end = g_fifo_ring_pos;
			std::string tail;

			for (u32 i = 0; i < fifo_ring_size; i++)
			{
				const auto& r = g_fifo_ring[(end + i) % fifo_ring_size];

				if (!r.reg && !r.arg)
				{
					continue;
				}

				std::string scratch;
				const auto nm = rsx::get_method_name(r.reg, scratch);

				fmt::append(tail, "\n      0x%04x %-46s arg=0x%08x", r.reg << 2, nm.first, r.arg);
			}

			if (!tail.empty())
			{
				prof_log.error("\tlast FIFO methods executed:%s", tail);
			}
		}

		// The SPU half of the same picture, and on this title it is the half that matters: during
		// the stall the PPUs are idle ~95% of sampler ticks while the SPUs issue ~2.2 MILLION
		// conditional stores a second. Whatever the guest is waiting for is being computed -- or
		// not computed -- here.
		{
			std::vector<pc_bucket> sorted(std::begin(g_stall_spu_pc), std::end(g_stall_spu_pc));

			std::sort(sorted.begin(), sorted.end(), [](const pc_bucket& x, const pc_bucket& y) { return x.hits > y.hits; });

			std::string hot;

			for (const auto& b : sorted)
			{
				if (!b.hits)
				{
					break;
				}

				fmt::append(hot, "\n      0x%05x  %4.1f%%  (%u)", b.pc, g_stall_spu_pc_total ? b.hits * 100.0 / g_stall_spu_pc_total : 0.0, b.hits);
			}

			if (!hot.empty())
			{
				prof_log.error("\tSPU PCs while stalled (%u samples) | exec=%u wait=%u susp=%u of %u ticks:%s",
					g_stall_spu_pc_total, g_stall_spu_exec, g_stall_spu_wait, g_stall_spu_susp, g_stall_spu_ticks, hot);
			}
		}

		prof_log.error("\tRSX->guest queue: id=0x%x pending=%u/32 ppu_waiting=%u", +g_rsx_q_id, +g_rsx_q_pending, +g_rsx_q_waiter);

		prof_log.error("\tRSX->guest events since last report: attempted +%u, dropped +%u, busy-retries +%u, aborted +%u",
			rv[0] - g_stall_rsxev_prev[0], rv[1] - g_stall_rsxev_prev[1], rv[2] - g_stall_rsxev_prev[2], rv[3] - g_stall_rsxev_prev[3]);

		for (usz i = 0; i < 4; i++)
		{
			g_stall_rsxev_prev[i] = rv[i];
		}

		g_stall_putllc_prev = putllc;
		g_stall_barrier_prev = barrier;

		for (usz i = 0; i < 5; i++)
		{
			g_stall_ev_prev[i] = ev[i];
		}

		// Disassemble whatever guest thread is actually running.
		//
		// A hang where the ring is drained and GET == PUT is the guest declining to produce, not us
		// failing to consume, so the useful question stops being about the FIFO and becomes what the
		// guest is looping on. Both the new-game black screen and the lock-up after it park
		// main_thread at the same cia with every other thread blocked, which a PC alone cannot
		// explain -- the loop body can.
		//
		// Read through g_sudo_addr, not g_base_addr. The sudo mapping is the unprotected alias, so
		// this does not touch the pages the texture cache mprotects, which is what made an earlier
		// version of this fault from a non-guest thread. check_addr still guards each word.
		{
			u32 run_cia = 0;
			std::string run_name;

			idm::select<named_thread<ppu_thread>>([&](u32, ppu_thread& ppu)
			{
				if (!run_cia && !(ppu.state.load() & cpu_flag::wait))
				{
					run_cia = ppu.cia;
					run_name = ppu.get_name();
				}
			});

			if (run_cia && !(run_cia & 3))
			{
				PPUDisAsm dis_asm(cpu_disasm_mode::normal, vm::g_sudo_addr);
				std::string out;

				// Wide enough to show how the loop was entered, not just the loop. The three
				// instructions that spin say nothing about which value is being waited for or who
				// was supposed to produce it; the setup above them, and the calls around it, do.
				const u32 lo = run_cia < 0x80 ? 0 : run_cia - 0x80;

				for (u32 a = lo; a <= run_cia + 32; a += 4)
				{
					if (!vm::check_addr(a, vm::page_readable, 4))
					{
						continue;
					}

					dis_asm.disasm(a);
					fmt::append(out, "\n\t %s %s", a == run_cia ? "->" : "  ", dis_asm.last_opcode);
				}

				prof_log.error("Running guest thread '%s' at 0x%08x:%s", run_name, run_cia, out);

				// The operands, and what the addresses among them point at.
				//
				// The loop this catches is a counter spin -- load a word, compare against a target
				// read once before the loop, branch back if unequal -- so the registers are the
				// question: which address is being polled, what it currently holds, and what it is
				// waiting to become. A pair that is close says work is still draining; a pair that
				// is far apart, or a target that was never going to arrive, says whoever advances
				// it is the thread to chase.
				std::string ops;

				idm::select<named_thread<ppu_thread>>([&](u32, ppu_thread& ppu)
				{
					if (ppu.cia != run_cia || (ppu.state.load() & cpu_flag::wait))
					{
						return;
					}

					for (u32 i = 0; i < 32; i++)
					{
						const u64 v = ppu.gpr[i];

						if (!v)
						{
							continue;
						}

						// Guest pointers only; anything else is noise at this width.
						const u32 as_addr = static_cast<u32>(v);

						if (v <= 0xffffffffull && vm::check_addr(as_addr, vm::page_readable, 8))
						{
							fmt::append(ops, "\n\t  r%-2u = 0x%08x -> [0x%08x, 0x%08x]", i, as_addr,
								*reinterpret_cast<const be_t<u32>*>(vm::g_sudo_addr + as_addr),
								*reinterpret_cast<const be_t<u32>*>(vm::g_sudo_addr + as_addr + 4));
						}
						else
						{
							fmt::append(ops, "\n\t  r%-2u = 0x%llx", i, v);
						}
					}
				});

				// Link register: names the caller, which is the context the loop lacks.
				idm::select<named_thread<ppu_thread>>([&](u32, ppu_thread& ppu)
				{
					if (ppu.cia != run_cia || (ppu.state.load() & cpu_flag::wait))
					{
						return;
					}

					fmt::append(ops, "\n\t  lr  = 0x%08x  ctr = 0x%08x", static_cast<u32>(ppu.lr), static_cast<u32>(ppu.ctr));
				});

				// The whole RSX control block, not just the two words the loop touches.
				//
				// put/get/ref sit at +0x40/+0x44/+0x48 of the DMA control area. A wait that polls
				// put while ref is the register the RSX actually advances is a different bug from
				// one where put and get simply never converge, and only ref distinguishes them.
				if (const auto* r = rsx::get_current_renderer(); r && r->ctrl)
				{
					fmt::append(ops, "\n\t  RSX ctrl: put=0x%08x get=0x%08x ref=0x%08x",
						+r->ctrl->put, +r->ctrl->get, +r->ctrl->ref);
				}

				if (!ops.empty())
				{
					prof_log.error("Its registers:%s", ops);
				}
			}
		}

		return true;
	}

	void dump_and_reset()
	{
		if (!g_acc.frames)
		{
			// No frames does not mean nothing to report -- it is the state most worth reporting.
			//
			// Everything below is normalised per frame, so the whole report used to be skipped
			// when the emulator produced none. That hid the PUTLLC barrier counters during exactly
			// the stall they explain: Soulcalibur V sat at roughly one vblank every five seconds
			// with five SPUs and the RSX thread pegged, and the barrier histogram -- the one thing
			// that says which loop is doing it -- was unavailable because the instrument needed
			// the frames the barriers were preventing.
			//
			// Emit the frame-independent part, then reset as before.
			u64 calls = 0, barriers = 0;
			idm::select<named_thread<spu_thread>>([&](u32, spu_thread& spu)
			{
				calls += spu.putllc_calls;
				barriers += spu.putllc_barrier;
			});

			if (const u64 dc = calls - g_putllc_calls_prev)
			{
				prof_log.error("RSX produced no frames this window. PUTLLC +%u (barrier +%u)\n\t    barrier sites (addr:count):%s",
					dc, barriers - g_putllc_barrier_prev, spu_putllc_barrier_sites());

				g_putllc_calls_prev = calls;
				g_putllc_barrier_prev = barriers;
			}

			return;
		}

		const u64 now = utils::get_tsc();
		const u64 freq = utils::get_tsc_freq();
		const u64 window = now - g_acc.window_start;

		if (!freq || !window)
		{
			g_acc = {};
			g_acc.window_start = now;
			return;
		}

		// Charge the in-flight bucket too, otherwise whatever is running at the moment of
		// the dump is silently missing from its own report.
		g_acc.ticks[static_cast<usz>(g_current)] += now - g_last_switch;
		g_last_switch = now;

		const double to_ms = 1000.0 / static_cast<double>(freq);
		const double frames = static_cast<double>(g_acc.frames);

		u64 accounted = 0;
		for (const u64 t : g_acc.ticks)
		{
			accounted += t;
		}

		std::string report = fmt::format(
			"RSX profile over %u frames, %.1f ms of thread time (%.2f ms/frame)",
			g_acc.frames, static_cast<double>(window) * to_ms,
			static_cast<double>(window) * to_ms / frames);

		// The number to read when testing whether a change helped.
		prof_log.success("\tpacing         %u/%u frames over 33ms, %u over 100ms, worst %.1f ms",
			static_cast<u32>(g_slow_frames), static_cast<u32>(g_acc.frames),
			static_cast<u32>(g_very_slow_frames), g_worst_frame_ns / 1'000'000.0);

		if (g_spu_tick_count)
		{
			std::sort(std::begin(g_spu_samples), std::end(g_spu_samples),
				[](const pc_bucket& a, const pc_bucket& b) { return a.hits > b.hits; });

			std::string top;

			for (const auto& b : g_spu_samples)
			{
				if (!b.hits)
				{
					break;
				}

				fmt::append(top, "\n\t  pc=0x%05x  %4.1f%%  (%u)", b.pc,
					b.hits * 100.0 / g_spu_total, b.hits);
			}

			// "running" here means "executing guest code outside a channel op" -- NOT "using CPU".
			// cpu_flag::wait is held for the whole of an SPU channel wait, including the reservation
			// loop that busy-waits at full tilt, so a core-burning SPU counts as not running. Report
			// the denominator and the stopped count alongside it, because 0.32 of an unknown total
			// invited exactly the wrong conclusion once already.
			prof_log.success("\tSPU            %.2f exec / %.2f waiting / %.2f GROUP-SUSPENDED / %.2f stopped of %.2f, %u pc samples:%s",
				static_cast<double>(g_spu_running_sum) / g_spu_tick_count,
				static_cast<double>(g_spu_total_sum - g_spu_running_sum - g_spu_stopped_sum - g_spu_suspended_sum) / g_spu_tick_count,
				static_cast<double>(g_spu_suspended_sum) / g_spu_tick_count,
				static_cast<double>(g_spu_stopped_sum) / g_spu_tick_count,
				static_cast<double>(g_spu_total_sum) / g_spu_tick_count,
				g_spu_total, top);

			for (auto& b : g_spu_samples)
			{
				b = {};
			}

			g_spu_total = 0;
			g_spu_running_sum = 0;
			g_spu_total_sum = 0;
			g_spu_stopped_sum = 0;
			g_spu_suspended_sum = 0;
			g_spu_tick_count = 0;
		}

		if (g_main_total)
		{
			std::sort(std::begin(g_main_state), std::end(g_main_state),
				[](const fn_bucket& a, const fn_bucket& b) { return a.hits > b.hits; });

			std::string top;

			for (const auto& b : g_main_state)
			{
				if (!b.hits)
				{
					break;
				}

				fmt::append(top, "\n\t  %-28s %4.1f%%  (%u)", b.fn, b.hits * 100.0 / g_main_total, b.hits);
			}

			prof_log.success("\tmain_thread    %u samples of its wall time:%s", g_main_total, top);

			{
				u64 calls = 0;
				u64 fails = 0;
				u64 barriers = 0;

				u64 barriers_spurs = 0;

				idm::select<named_thread<spu_thread>>([&](u32, spu_thread& spu)
				{
					calls += spu.putllc_calls;
					fails += spu.putllc_fails;
					barriers += spu.putllc_barrier;
					barriers_spurs += spu.putllc_barrier_spurs;
				});

				const u64 dc = calls - g_putllc_calls_prev;
				const u64 df = fails - g_putllc_fails_prev;

				if (dc)
				{
					prof_log.success("\t  PUTLLC       %.0f/frame, %.1f%% failed (%u of %u)",
						static_cast<double>(dc) / frames, df * 100.0 / dc, df, dc);

					const u64 db = barriers - g_putllc_barrier_prev;

					// Rate only. The per-call cost belongs to a profiler, not to a clock read on a path
					// that runs two million times a second -- see the note in do_putllc.
					const u64 dbs = barriers_spurs - g_putllc_barrier_spurs_prev;

					// vm::writer_lock stamps cpu_flag::memory on EVERY registered PPU and spins until each
					// parks, so its rate -- not the PUTLLC rate -- is what starves the guest's PPU threads.
					// The SPURS split says whether do_putllc's spurs_addr fast path is actually engaging;
					// that path is gated on spu_accurate_reservations, so print the gate next to it.
					prof_log.success("\t    of those %.0f/frame took vm::writer_lock (%.0f/frame on the SPURS block, accurate_reservations=%s)",
						static_cast<double>(db) / frames, static_cast<double>(dbs) / frames,
						g_cfg.core.spu_accurate_reservations ? "ON" : "off");

					g_putllc_barrier_spurs_prev = barriers_spurs;

					prof_log.success("\t    barrier sites (addr:count):%s", spu_putllc_barrier_sites());

					const u64 ev[5] = { +g_spu_event_throw_ok, +g_spu_event_throw_drop, +g_spu_event_throw_again, +g_spu_event_setbit_ok, +g_spu_event_setbit_again };

					prof_log.success("\t  SPU events   throw ok %.0f/f, DROPPED %.0f/f, retry %.0f/f | setbit ok %.0f/f, retry %.0f/f",
						(ev[0] - g_spu_ev_prev[0]) / frames, (ev[1] - g_spu_ev_prev[1]) / frames, (ev[2] - g_spu_ev_prev[2]) / frames,
						(ev[3] - g_spu_ev_prev[3]) / frames, (ev[4] - g_spu_ev_prev[4]) / frames);

					for (usz i = 0; i < 5; i++)
					{
						g_spu_ev_prev[i] = ev[i];
					}
				}

				g_putllc_calls_prev = calls;
				g_putllc_fails_prev = fails;
				g_putllc_barrier_prev = barriers;
			}

			if (const u64 n = g_sema_wait_count.load(); n)
			{
				prof_log.success("\t  sema waits   %.1f/frame, mean %.2f ms, worst %.1f ms",
					static_cast<double>(n) / frames,
					(g_sema_wait_us.load() / 1000.0) / n,
					g_sema_wait_max_us.load() / 1000.0);

				prof_log.success("\t  sema spread  <1ms:%u  1-4:%u  4-10:%u  10-14:%u  14-20:%u  >20ms:%u",
					g_sema_hist[0].load(), g_sema_hist[1].load(), g_sema_hist[2].load(),
					g_sema_hist[3].load(), g_sema_hist[4].load(), g_sema_hist[5].load());

				for (auto& h : g_sema_hist) h = 0;
			}

			g_sema_wait_us = 0;
			g_sema_wait_count = 0;
			g_sema_wait_max_us = 0;

			for (auto& b : g_main_state)
			{
				b = {};
			}

			g_main_total = 0;
		}

		if (g_pc_total)
		{
			// Ranked, so the top line is where the guest actually is.
			std::sort(std::begin(g_pc_samples), std::end(g_pc_samples),
				[](const pc_bucket& a, const pc_bucket& b) { return a.hits > b.hits; });

			std::string top;

			for (const auto& b : g_pc_samples)
			{
				if (!b.hits)
				{
					break;
				}

				fmt::append(top, "\n\t  0x%08x  %4.1f%%  (%u)", b.pc,
					b.hits * 100.0 / g_pc_total, b.hits);
			}

			prof_log.success("\tguest PC       %u samples of a RUNNING thread:%s", g_pc_total, top);

			if (g_stall_pc_total)
			{
				std::sort(std::begin(g_stall_pc), std::end(g_stall_pc),
					[](const pc_bucket& a, const pc_bucket& b) { return a.hits > b.hits; });

				std::string stop;

				for (const auto& b : g_stall_pc)
				{
					if (!b.hits) break;
					fmt::append(stop, "\n\t  0x%08x  %4.1f%%  (%u)", b.pc, b.hits * 100.0 / g_stall_pc_total, b.hits);
				}

				prof_log.success("\tSTALL-only PC  %u samples taken during frames already over 33ms:%s",
					g_stall_pc_total, stop);

				if (g_stall_spu_ticks)
				{
					if (const u64 n = g_spu_group_susp_count.load(); n)
					{
						prof_log.success("\t  group parks  %.1f/frame, mean %.2f ms, worst %.1f ms",
							static_cast<double>(n) / frames,
							(g_spu_group_susp_us.load() / 1000.0) / n,
							g_spu_group_susp_max.load() / 1000.0);

						g_spu_group_susp_us = 0;
						g_spu_group_susp_count = 0;
						g_spu_group_susp_max = 0;
					}

					prof_log.success("\tSTALL-only SPU %.2f exec / %.2f waiting / %.2f GROUP-SUSPENDED  (vs %.2f suspended overall)",
						static_cast<double>(g_stall_spu_exec) / g_stall_spu_ticks,
						static_cast<double>(g_stall_spu_wait) / g_stall_spu_ticks,
						static_cast<double>(g_stall_spu_susp) / g_stall_spu_ticks,
						g_spu_tick_count ? static_cast<double>(g_spu_suspended_sum) / g_spu_tick_count : 0.0);
				}

				g_stall_spu_susp = 0;
				g_stall_spu_wait = 0;
				g_stall_spu_exec = 0;
				g_stall_spu_ticks = 0;

				if (g_stall_spu_pc_total)
				{
					std::sort(std::begin(g_stall_spu_pc), std::end(g_stall_spu_pc),
						[](const pc_bucket& a, const pc_bucket& b) { return a.hits > b.hits; });

					std::string sp;

					for (const auto& b : g_stall_spu_pc)
					{
						if (!b.hits) break;
						fmt::append(sp, "\n\t  pc=0x%05x  %4.1f%%  (%u)", b.pc,
							b.hits * 100.0 / g_stall_spu_pc_total, b.hits);
					}

					prof_log.success("\tSTALL-only SPU PC  %u samples of the running SPU during slow frames:%s",
						g_stall_spu_pc_total, sp);

					for (auto& b : g_stall_spu_pc) b = {};
					g_stall_spu_pc_total = 0;
				}

				for (auto& b : g_stall_pc) b = {};
				g_stall_pc_total = 0;
			}


			for (auto& b : g_pc_samples)
			{
				b = {};
			}

			g_pc_total = 0;
		}

		g_slow_frames = 0;
		g_very_slow_frames = 0;
		g_worst_frame_ns = 0;

		for (usz i = 0; i < bucket_count; i++)
		{
			const u64 ticks = g_acc.ticks[i];
			if (!ticks)
			{
				continue;
			}

			fmt::append(report, "\n\t%-18s %7.3f ms/frame  %5.1f%%",
				name_of(static_cast<bucket>(i)),
				static_cast<double>(ticks) * to_ms / frames,
				static_cast<double>(ticks) * 100.0 / static_cast<double>(window));
		}

		// A large gap means RSX thread time is going somewhere with no scope on it, which
		// makes every percentage above an overestimate of its share. Worth saying so
		// rather than letting the buckets read as if they covered the frame.
		if (window > accounted)
		{
			const u64 gap = window - accounted;
			fmt::append(report, "\n\t%-18s %7.3f ms/frame  %5.1f%%  (no scope)",
				"Unscoped", static_cast<double>(gap) * to_ms / frames,
				static_cast<double>(gap) * 100.0 / static_cast<double>(window));
		}

		if (g_fifo_refills)
		{
			{
				std::string drains;
				for (u32 i = 0; i < flush_site_count; i++)
				{
					if (!g_flush_sites[i]) continue;
					fmt::append(drains, "%s%s %.2f", drains.empty() ? "" : ", ",
						g_flush_site_names[i], static_cast<double>(g_flush_sites[i]) / frames);
				}
				fmt::append(report, "\n\tpage protect    %.1f mprotect/frame, %.2f MB, %.1f faults/frame",
				static_cast<double>(g_mprotect_calls) / frames,
				static_cast<double>(g_mprotect_bytes) / 1048576.0 / frames,
				static_cast<double>(g_access_violations) / frames);

		if (const u64 av_tsc = g_access_violation_tsc.load(); av_tsc && utils::get_tsc_freq())
		{
			prof_log.success("\tguest AV time  %.3f ms/frame across guest threads (invisible to the buckets above)",
				(av_tsc * 1000.0 / utils::get_tsc_freq()) / frames);
		}

		g_access_violation_tsc.store(0);

			fmt::append(report, "\n\trender passes   %.1f/frame",
				static_cast<double>(g_render_passes) / frames);

			{
				std::string sites;
				for (u32 i = 0; i < rp_site_count; i++)
				{
					if (!g_rp_sites[i]) continue;
					fmt::append(sites, "%s%s %.1f", sites.empty() ? "" : ", ",
						g_rp_site_names[i], static_cast<double>(g_rp_sites[i]) / frames);
				}
				fmt::append(report, "\n\trp closes/frame %s", sites.empty() ? "none" : sites);
			}

			fmt::append(report, "\n\tdrains/frame    %s", drains.empty() ? "none" : drains);
			}

			fmt::append(report, "\n\tFIFO stalls     %.1f/frame, %.3f ms/frame spinning",
				static_cast<double>(g_fifo_refill_stalls) / frames,
				static_cast<double>(g_fifo_refill_stall_us) / 1000.0 / frames);

			fmt::append(report, "\n\tFIFO cache      %.1f refills/frame, %.0f bytes each",
				static_cast<double>(g_fifo_refills) / frames,
				static_cast<double>(g_fifo_refill_bytes) / static_cast<double>(g_fifo_refills));
		}

		for (u32 level = 0; level < 2; level++)
		{
			std::pair<const void*, u64> top[6] = {};
			for (usz i = 0; i < rp_caller_slots; i++)
			{
				if (!g_rp_caller_counts[level][i]) continue;
				if (g_rp_caller_counts[level][i] <= top[5].second) continue;
				top[5] = { g_rp_callers[level][i], g_rp_caller_counts[level][i] };
				std::sort(std::begin(top), std::end(top),
					[](const auto& a, const auto& b) { return a.second > b.second; });
			}

			if (!top[0].second)
			{
				continue;
			}

			std::string list;
			for (const auto& [addr, count] : top)
			{
				if (!count) continue;

				// dladdr resolves exported names directly; everything else only yields the
				// module base, which is enough to hand the offset to llvm-symbolizer against
				// the unstripped core.
				Dl_info info{};
				std::string where;

				if (addr && dladdr(addr, &info) && info.dli_fbase)
				{
					const uptr off = reinterpret_cast<uptr>(addr) - reinterpret_cast<uptr>(info.dli_fbase);
					where = info.dli_sname
						? fmt::format("%s +0x%x", info.dli_sname, off)
						: fmt::format("+0x%x", off);
				}
				else
				{
					where = fmt::format("%p", addr);
				}

				fmt::append(list, "\n\t  %-44s %6.1f/frame", where, static_cast<double>(count) / frames);
			}

			fmt::append(report, "\n\trp teardown by %s%s",
				level == 0 ? "direct caller" : "change_layout caller", list);
		}

		{
			// Draws per pass, by the same ordinal the GPU timer reports its per-pass cost
			// against, so the two can be read side by side.
			std::pair<u32, u64> top[6] = {};
			for (u32 i = 0; i < pass_slot_count; i++)
			{
				if (!g_pass_draws[i]) continue;
				if (g_pass_draws[i] <= top[5].second) continue;
				top[5] = { i, g_pass_draws[i] };
				std::sort(std::begin(top), std::end(top),
					[](const auto& a, const auto& b) { return a.second > b.second; });
			}

			if (top[0].second)
			{
				std::string list;
				for (const auto& [ordinal, count] : top)
				{
					if (!count) continue;

					const double draws_per_frame = static_cast<double>(count) / frames;
					const double verts_per_frame = static_cast<double>(g_pass_vertices[ordinal]) / frames;

					fmt::append(list, "\n\t  pass #%-3u %5.0f draws  %8.0f verts  %5.0f v/draw  %ux%u  vp %.0f fp %.0f words/draw  SUBDRAWS %.1f/draw  QUERIES %.0f (%.2f/draw)  BARRIERS %.1f/frame (in-pass %.1f)",
						ordinal, draws_per_frame, verts_per_frame,
						count ? static_cast<double>(g_pass_vertices[ordinal]) / static_cast<double>(count) : 0.0,
						g_pass_width[ordinal], g_pass_height[ordinal],
						count ? static_cast<double>(g_pass_vp_words[ordinal]) / static_cast<double>(count) : 0.0,
						count ? static_cast<double>(g_pass_fp_words[ordinal]) / static_cast<double>(count) : 0.0,
						count ? static_cast<double>(g_pass_subdraws[ordinal]) / static_cast<double>(count) : 0.0,
						static_cast<double>(g_pass_queries[ordinal]) / frames,
						count ? static_cast<double>(g_pass_queries[ordinal]) / static_cast<double>(count) : 0.0,
						static_cast<double>(g_pass_barriers[ordinal]) / frames,
						static_cast<double>(g_pass_cyclic[ordinal]) / frames);
				}
				fmt::append(report, "\n\tby pass%s", list);
			}
		}

		if (g_xform_program_calls || g_xform_const_calls)
		{
			const auto ns_each = [&](bucket b, u64 calls)
			{
				return calls
					? static_cast<double>(g_acc.ticks[static_cast<usz>(b)]) * to_ms * 1'000'000.0 / static_cast<double>(calls)
					: 0.0;
			};

			fmt::append(report, "\n\txform program   %.0f calls/frame, %.1f words each, %.0f ns each",
				static_cast<double>(g_xform_program_calls) / frames,
				g_xform_program_calls ? static_cast<double>(g_xform_program_words) / static_cast<double>(g_xform_program_calls) : 0.0,
				ns_each(bucket::xform_program, g_xform_program_calls));

			fmt::append(report, "\n\txform constant  %.0f calls/frame, %.1f words each, %.0f ns each",
				static_cast<double>(g_xform_const_calls) / frames,
				g_xform_const_calls ? static_cast<double>(g_xform_const_words) / static_cast<double>(g_xform_const_calls) : 0.0,
				ns_each(bucket::xform_const, g_xform_const_calls));
		}

		if (g_fifo_commands)
		{
			// The per-command figure is against fifo_decode specifically, since that is the
			// bucket with no owner left in it.
			const u64 decode_ticks = g_acc.ticks[static_cast<usz>(bucket::fifo_decode)];

			// Labelled packets, because that is what it counts. fifo_decode is also a
			// catch-all holding every method handler body, since no handler carries its
			// own scope, so neither figure is dispatch overhead alone.
			fmt::append(report, "\n\tFIFO packets    %.0f/frame, %.0f ns each in FIFO decode",
				static_cast<double>(g_fifo_commands) / frames,
				static_cast<double>(decode_ticks) * to_ms * 1'000'000.0 / static_cast<double>(g_fifo_commands));

			if (g_fifo_dispatches)
			{
				fmt::append(report, "\n\tFIFO dispatches %.0f/frame, %.1f/packet, %.0f ns each",
					static_cast<double>(g_fifo_dispatches) / frames,
					static_cast<double>(g_fifo_dispatches) / static_cast<double>(g_fifo_commands),
					static_cast<double>(decode_ticks) * to_ms * 1'000'000.0 / static_cast<double>(g_fifo_dispatches));
			}
		}

		if (g_draw_calls)
		{
			// Every bucket named here is entered exactly once per draw, so dividing by the
			// draw count turns "this bucket is big" into "each draw pays this much", which is
			// the form that says whether to cut the per-draw cost or the number of draws.
			const auto ns_per_draw = [&](bucket b)
			{
				return static_cast<double>(g_acc.ticks[static_cast<usz>(b)]) * to_ms * 1'000'000.0
					/ static_cast<double>(g_draw_calls);
			};

			fmt::append(report, "\n\tdraws           %.0f/frame, %.0f ns each in draw setup",
				static_cast<double>(g_draw_calls) / frames,
				ns_per_draw(bucket::draw_setup));

			fmt::append(report, "\n\tpresent checks  %.1f/frame, %.1f cleanups/frame, %.1f us each",
				static_cast<double>(g_present_checks) / frames,
				static_cast<double>(g_frame_cleanups) / frames,
				g_present_checks
					? static_cast<double>(g_acc.ticks[static_cast<usz>(bucket::present_check)]
						+ g_acc.ticks[static_cast<usz>(bucket::swap_wait)]
						+ g_acc.ticks[static_cast<usz>(bucket::res_trim)])
						* to_ms * 1000.0 / static_cast<double>(g_present_checks)
					: 0.0);

			if (g_fence_polls)
			{
				fmt::append(report, "\n\tfence polls     %.1f/frame, %.0f ns each, %.1f%% not ready",
					static_cast<double>(g_fence_polls) / frames,
					static_cast<double>(g_acc.ticks[static_cast<usz>(bucket::fence_poll)])
						* to_ms * 1'000'000.0 / static_cast<double>(g_fence_polls),
					static_cast<double>(g_fence_polls_not_ready) * 100.0 / static_cast<double>(g_fence_polls));
			}

			fmt::append(report, "\n\tper draw        prologue %.0f, epilogue %.0f, wr barrier %.0f, on_write %.0f, tex release %.0f ns",
				ns_per_draw(bucket::draw_prologue),
				ns_per_draw(bucket::draw_epilogue),
				ns_per_draw(bucket::wr_barrier),
				ns_per_draw(bucket::rtt_write),
				ns_per_draw(bucket::tex_release));
		}

		{
			// Top methods by volume. Names via gcm_printing, which is the same table the
			// command dumps use, so these read the same as the log's own FIFO traces.
			std::pair<u32, u32> top[8] = {};
			for (u32 i = 0; i < method_slot_count; i++)
			{
				if (!g_method_counts[i]) continue;
				if (g_method_counts[i] <= top[7].second) continue;
				top[7] = { i, g_method_counts[i] };
				std::sort(std::begin(top), std::end(top),
					[](const auto& a, const auto& b) { return a.second > b.second; });
			}

			if (top[0].second)
			{
				std::string list;
				for (const auto& [slot, count] : top)
				{
					if (!count) continue;
					std::string scratch;
					// The name table is keyed by register index, which is what a slot already is.
					// Passing slot << 2 matched whichever unrelated method happened to have that
					// value as its enum, so a hot slot could be reported under another method's
					// name -- NV406E_SEMAPHORE_ACQUIRE came out as NV4097_SET_CONTEXT_DMA_VERTEX_B.
					// The hex fallback below is still the byte offset, which is what a reader wants.
					const auto name = rsx::get_method_name(slot, scratch).second;
					fmt::append(list, "\n\t  %-46s %8.0f/frame  %4.1f%%",
						name.empty() ? fmt::format("0x%05x", slot << 2) : std::string(name),
						static_cast<double>(count) / frames,
						static_cast<double>(count) * 100.0 / static_cast<double>(g_fifo_dispatches ? g_fifo_dispatches : g_fifo_commands));
				}
				fmt::append(report, "\n\ttop methods%s", list);
			}
		}

		{
			// By cost, not by volume. The two disagree: the busiest method may be a register
			// write and a rare one may be doing all the work, and only this ranking can say.
			std::pair<u32, u64> top[8] = {};
			for (u32 i = 0; i < method_slot_count; i++)
			{
				if (!g_method_ticks[i]) continue;
				if (g_method_ticks[i] <= top[7].second) continue;
				top[7] = { i, g_method_ticks[i] };
				std::sort(std::begin(top), std::end(top),
					[](const auto& a, const auto& b) { return a.second > b.second; });
			}

			if (top[0].second)
			{
				std::string list;
				for (const auto& [slot, ticks] : top)
				{
					if (!ticks) continue;
					std::string scratch;
					// The name table is keyed by register index, which is what a slot already is.
					// Passing slot << 2 matched whichever unrelated method happened to have that
					// value as its enum, so a hot slot could be reported under another method's
					// name -- NV406E_SEMAPHORE_ACQUIRE came out as NV4097_SET_CONTEXT_DMA_VERTEX_B.
					// The hex fallback below is still the byte offset, which is what a reader wants.
					const auto name = rsx::get_method_name(slot, scratch).second;
					fmt::append(list, "\n\t  %-46s %7.3f ms/frame  %6.0f ns each",
						name.empty() ? fmt::format("0x%05x", slot << 2) : std::string(name),
						static_cast<double>(ticks) * to_ms / frames,
						g_method_counts[slot]
							? static_cast<double>(ticks) * to_ms * 1'000'000.0 / static_cast<double>(g_method_counts[slot])
							: 0.0);
				}
				fmt::append(report, "\n\tcostliest methods%s", list);
			}
		}

		prof_log.success("%s", report);

		g_fifo_commands = 0;
		g_fifo_dispatches = 0;
		g_draw_calls = 0;
		g_present_checks = 0;
		g_frame_cleanups = 0;
		g_fence_polls = 0;
		g_fence_polls_not_ready = 0;
		g_xform_program_calls = 0;
		g_xform_program_words = 0;
		g_xform_const_calls = 0;
		g_xform_const_words = 0;
		std::fill(std::begin(g_method_counts), std::end(g_method_counts), 0u);
		std::fill(std::begin(g_method_ticks), std::end(g_method_ticks), 0ull);
		g_fifo_refills = 0;
		g_fifo_refill_bytes = 0;
		g_fifo_refill_stalls = 0;
		g_fifo_refill_stall_us = 0;
		g_render_passes = 0;
		g_mprotect_calls = 0;
		g_mprotect_bytes = 0;
		g_access_violations = 0;
		for (auto& c : g_rp_sites) c = 0;
		for (auto& c : g_pass_draws) c = 0;
		for (auto& c : g_pass_vertices) c = 0;
		for (auto& c : g_pass_barriers) c = 0;
		for (auto& c : g_pass_cyclic) c = 0;
		for (auto& c : g_pass_vp_words) c = 0;
		for (auto& c : g_pass_fp_words) c = 0;
		for (auto& c : g_pass_subdraws) c = 0;
		for (auto& c : g_pass_queries) c = 0;
		for (auto& level : g_rp_caller_counts) for (auto& c : level) c = 0;
		for (auto& level : g_rp_callers) for (auto& a : level) a = nullptr;
		for (auto& c : g_flush_sites) c = 0;

		g_acc = {};
		g_acc.window_start = now;
	}
}
