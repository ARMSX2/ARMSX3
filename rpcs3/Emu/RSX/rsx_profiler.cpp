#include "stdafx.h"
#include "rsx_profiler.h"
#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/SPUThread.h"

extern atomic_t<u64> g_fs_read_us;
extern atomic_t<u64> g_fs_read_count;
extern atomic_t<u64> g_usleep_over_us;
extern atomic_t<u64> g_usleep_over_count;
extern atomic_t<u64> g_mmapper_calls;
extern atomic_t<u64> g_mmapper_tsc;
#include "Emu/IdManager.h"

#include "util/sysinfo.hpp"

#include <string>
#include <algorithm>
#include <utility>
#include <dlfcn.h>
#include "gcm_printing.h"

LOG_CHANNEL(prof_log, "RSXPROF");

namespace rsx::prof
{
	std::atomic<bool> g_enabled{false};
	accounting g_acc{};
	std::atomic<u64> g_last_frame_tsc{0};
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
			fmt::append(out, "\n    PPU 0x%07x %-24s [%s] cia=0x%08x %s%s", id,
				name ? name->c_str() : "?", ppu.state.load(), ppu.cia,
				inside ? "in=" : "last=", func ? func : "");
		}, idm::unlocked);

		// PPUs waiting on a semaphore are usually waiting for an SPU to post it, so the SPU
		// side is the half that actually names the culprit.
		idm::select<named_thread<spu_thread>>([&out, running_only](u32 id, spu_thread& spu)
		{
			if (running_only && spu.state.load() & cpu_flag::wait)
			{
				return;
			}

			const auto name = spu.spu_tname.load();

			fmt::append(out, "\n    SPU 0x%07x %-24s [%s] pc=0x%05x %s", id,
				name ? name->c_str() : "?", spu.state.load(), spu.pc,
				spu.current_func ? spu.current_func : "");
		}, idm::unlocked);

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

		// How much of this stall was the guest remapping memory. Every mmapper call takes the
		// VM lock and suspends every other thread, so if this number tracks the stall length
		// the barrier is the stutter and no amount of renderer work will touch it.
		static u64 s_last_calls = 0;
		static u64 s_last_tsc = 0;

		const u64 calls = g_mmapper_calls.load();
		const u64 mm_tsc = g_mmapper_tsc.load();
		const u64 mm_ms = ((mm_tsc - s_last_tsc) * 1000ull) / freq;

		static u64 s_last_over_us = 0;
		static u64 s_last_over_n = 0;

		const u64 over_us = g_usleep_over_us.load();
		const u64 over_n = g_usleep_over_count.load();

		static u64 s_last_fs_us = 0;
		static u64 s_last_fs_n = 0;

		const u64 fs_us = g_fs_read_us.load();
		const u64 fs_n = g_fs_read_count.load();

		prof_log.error("STALL: %u ms (sample %u/60) -- mmapper %u/%u ms -- usleep over %u/%u ms -- fs_read %u reads/%u ms%s",
			stalled_ms, s_dumps, calls - s_last_calls, mm_ms,
			over_n - s_last_over_n, (over_us - s_last_over_us) / 1000,
			fs_n - s_last_fs_n, ((fs_us - s_last_fs_us) * 1000ull) / freq, sample_guest_threads(false));

		s_last_fs_us = fs_us;
		s_last_fs_n = fs_n;

		s_last_over_us = over_us;
		s_last_over_n = over_n;

		s_last_calls = calls;
		s_last_tsc = mm_tsc;
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
						}, idm::unlocked);
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
		if (g_acc.frames >= 300)
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

		// Only the thread the buckets are armed against; anyone else's clock is meaningless.
		if (current_thread_token() != g_owner_thread)
		{
			return false;
		}

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

		if (g_acc.frames != g_stall_frames)
		{
			// Frames are still arriving, so tick_frame is doing the reporting.
			g_stall_frames = g_acc.frames;
			g_stall_started = now;
			return false;
		}

		if (!g_stall_started)
		{
			g_stall_started = now;
			return false;
		}

		prof_log.error("RSX has not finished a frame in %.1fs; current bucket '%s', in it for %.2fs",
			static_cast<double>(now - g_stall_started) / static_cast<double>(freq),
			name_of(g_current),
			static_cast<double>(now - g_last_switch) / static_cast<double>(freq));

		return true;
	}

	void dump_and_reset()
	{
		if (!g_acc.frames)
		{
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

					fmt::append(list, "\n\t  pass #%-3u %5.0f draws  %8.0f verts  %5.0f v/draw  %ux%u  vp %.0f fp %.0f words/draw  SUBDRAWS %.1f/draw  QUERIES %.0f (%.2f/draw)",
						ordinal, draws_per_frame, verts_per_frame,
						count ? static_cast<double>(g_pass_vertices[ordinal]) / static_cast<double>(count) : 0.0,
						g_pass_width[ordinal], g_pass_height[ordinal],
						count ? static_cast<double>(g_pass_vp_words[ordinal]) / static_cast<double>(count) : 0.0,
						count ? static_cast<double>(g_pass_fp_words[ordinal]) / static_cast<double>(count) : 0.0,
						count ? static_cast<double>(g_pass_subdraws[ordinal]) / static_cast<double>(count) : 0.0,
						static_cast<double>(g_pass_queries[ordinal]) / frames,
						count ? static_cast<double>(g_pass_queries[ordinal]) / static_cast<double>(count) : 0.0);
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
