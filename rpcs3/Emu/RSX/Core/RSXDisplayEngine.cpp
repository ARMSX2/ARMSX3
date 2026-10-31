#include "stdafx.h"
#include "RSXDisplayEngine.h"
#include "RSXDisplayOutput.h"
#include "RSXPowerMgmt.h"

#include "Emu/RSX/RSXThread.h"
#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/lv2/sys_rsx.h"
#include "Emu/Cell/lv2/sys_sync.h"
#include "Emu/Cell/lv2/sys_time.h"
#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/system_utils.hpp"
#include "Emu/android_app_state.h"
#include "Emu/RSX/rsx_profiler.h"

#include "util/asm.hpp"

#ifdef __ANDROID__
#include <unistd.h> // gettid, for the ADPF feed in flip()
#endif
#include "util/serialization.hpp"

extern atomic_t<u32> g_lv2_preempts_taken;

LOG_CHANNEL(perf_log, "PERF");

namespace rsx
{
	void display_engine::init(thread* pump, display_output* output)
	{
		m_pump = pump;
		m_output = output;
	}

	void display_engine::save(utils::serial& ar)
	{
		ar(vblank_count);

		b8 flip_pending{};

		if (ar.is_writing())
		{
			flip_pending = m_async_flip_request.requested.test(flip_request::emu_requested);
		}

		ar(flip_pending);

		if (flip_pending)
		{
			ar(vblank_at_flip);
			ar(m_async_flip_request.buffer, m_async_flip_request.context_id);

			if (!ar.is_writing())
			{
				m_async_flip_request.requested |= flip_request::emu_requested;
				flip_notification_count = 1;
			}
		}
	}

	void display_engine::on_thread_start()
	{
		last_guest_flip_timestamp = get_system_time() - 1000000;

		vblank_count = 0;
	}

	bool display_engine::request_emu_flip(u32 ctx_id, u32 buffer)
	{
		if (m_pump->is_current_thread()) // requested through command buffer
		{
			// The command buffer belongs to the bound context
			ensure(m_pump->get_context()->id() == ctx_id);

			// NOTE: The flip will clear any queued flip requests
			handle_emu_flip(buffer);
		}
		else // requested 'manually' through ppu syscall
		{
			if (m_async_flip_request.requested.test(flip_request::emu_requested))
			{
				// ignore multiple requests until previous happens
				return true;
			}

			m_async_flip_request.buffer = buffer;
			m_async_flip_request.context_id = ctx_id;
			m_async_flip_request.requested |= flip_request::emu_requested;

			m_pump->eng_interrupt_mask |= rsx::display_interrupt;

			if (m_pump->state & cpu_flag::exit)
			{
				// Resubmit possibly-ignored flip on savestate load
				return false;
			}
		}

		return true;
	}

	void display_engine::handle_pending_flip()
	{
		if (!m_async_flip_request.requested.test(flip_request::emu_requested))
		{
			return;
		}

		if (m_async_flip_request.context_id != m_pump->get_context()->id())
		{
			// The display buffers belong to another context. The RSX thread binds it between commands.
			return;
		}

		handle_emu_flip(m_async_flip_request.buffer);
	}

	void display_engine::handle_emu_flip(u32 buffer)
	{
		if (m_queued_flip.in_progress)
		{
			// Rescursion not allowed!
			return;
		}

		if (!m_queued_flip.pop(buffer))
		{
			// Frame was not queued before flipping
			m_pump->on_frame_end(m_pump->get_context(), buffer, true);
			ensure(m_queued_flip.pop(buffer));
		}

		double limit = 0.;
		const auto frame_limit = g_disable_frame_limit ? frame_limit_type::none : g_cfg.video.frame_limit;

		switch (frame_limit)
		{
		case frame_limit_type::none: limit = g_cfg.core.max_cpu_preempt_count_per_frame ? static_cast<double>(g_cfg.video.vblank_rate) : 0.; break;
		case frame_limit_type::_30: limit = 30.; break;
		case frame_limit_type::_50: limit = 50.; break;
		case frame_limit_type::_60: limit = 60.; break;
		case frame_limit_type::_120: limit = 120.; break;
		case frame_limit_type::display_rate: limit = cached_refresh_rate(); break;
		case frame_limit_type::_auto: limit = static_cast<double>(g_cfg.video.vblank_rate); break;
		case frame_limit_type::_ps3: limit = 0.; break;
		case frame_limit_type::infinite: limit = 0.; break;
		default:
			break;
		}

		if (double limit2 = g_cfg.video.second_frame_limit; limit2 >= 0.1 && (limit2 < limit || !limit))
		{
			// Apply a second limit
			limit = limit2;
		}

		if (limit)
		{
			const u64 needed_us = static_cast<u64>(1000000 / limit);
			const u64 time = std::max<u64>(get_system_time(), target_rsx_flip_time > needed_us ? target_rsx_flip_time - needed_us : 0);

			if (int_flip_index)
			{
				if (target_rsx_flip_time > time + 1000)
				{
					const auto delay_us = target_rsx_flip_time - time;
					{
						RSX_PROF_SCOPE(idle);
						lv2_obj::wait_timeout(delay_us, nullptr, false);
					}
					m_pump->performance_counters.idle_time += delay_us;
				}
			}

			target_rsx_flip_time = std::max(time, target_rsx_flip_time) + needed_us;
			flip_notification_count = 1;
		}
		else if (frame_limit == frame_limit_type::_ps3)
		{
			bool exit = false;

			if (vblank_at_flip == umax)
			{
				vblank_at_flip = +vblank_count;
				flip_notification_count = 1;
				exit = true;
			}

			if (m_pump->requested_vsync && (exit || vblank_at_flip == vblank_count))
			{
				// Not yet signaled, handle it later
				m_async_flip_request.buffer = buffer;
				m_async_flip_request.context_id = m_pump->get_context()->id();
				m_async_flip_request.requested |= flip_request::emu_requested;
				return;
			}

			vblank_at_flip = umax;
		}
		else
		{
			flip_notification_count = 1;
		}

		int_flip_index += flip_notification_count;

		m_pump->get_context()->current_display_buffer = buffer;
		m_queued_flip.emu_flip = true;
		m_queued_flip.in_progress = true;
		m_queued_flip.skip_frame |= g_cfg.video.disable_video_output && !g_cfg.video.perf_overlay.enabled;

		flip(m_queued_flip);

		last_guest_flip_timestamp = get_system_time() - 1000000;
		flip_status = CELL_GCM_DISPLAY_FLIP_STATUS_DONE;
		m_queued_flip.in_progress = false;

		while (flip_notification_count--)
		{
			if (!m_pump->isHLE)
			{
				// The context that flipped gets the notification
				context* ctx = m_pump->get_context();
				sys_rsx_context_attribute(ctx->id(), 0xFEC, buffer, 0, 0, 0);

				if (ctx->fw.unsent_gcm_events)
				{
					// TODO: A proper fix
					return;
				}

				continue;
			}

			if (auto ptr = m_pump->flip_handler)
			{
				m_pump->intr_thread->cmd_list
				({
					{ ppu_cmd::set_args, 1 }, u64{ 1 },
					{ ppu_cmd::lle_call, ptr },
					{ ppu_cmd::sleep, 0 }
				});

				m_pump->intr_thread->cmd_notify.store(1);
				m_pump->intr_thread->cmd_notify.notify_one();
			}
		}
	}

	void display_engine::queue_frame(const context* ctx, u32 buffer)
	{
		m_queued_flip.stats = ctx->stats;
		m_queued_flip.push(buffer);
		m_queued_flip.skip_frame = ctx->frame_skip.skip_current_frame;
	}

	void display_engine::flip(const display_flip_info_t& info)
	{
		auto renderer = m_pump->renderer();
		const bool presented = renderer->present_frame(info);

		m_pump->eng_interrupt_mask.clear(rsx::display_interrupt);

#ifdef __ANDROID__
		// ADPF feed: publish this frame's real CPU cost and the presenting thread's OS tid so
		// the app can drive PerformanceHintManager. Measured at a fixed point each iteration, so
		// the previous iteration's frame-limiter sleep lands in the idle delta and is excluded.
		// Advisory only: these go to atomics nothing in the core reads back.
		// Ported from an earlier Android fork (3d4ba6060).
		{
			static thread_local u64 s_last_now = 0;
			static thread_local u64 s_last_idle = 0;
			static thread_local s32 s_tid = 0;

			if (s_tid == 0)
			{
				s_tid = static_cast<s32>(::gettid());
			}

			// Republished every flip so a recreated RSX thread overwrites a stale tid, rather
			// than leaving the app's hint session pointed at a dead thread after a restart.
			rpcs3::utils::set_rsx_thread_tid(s_tid);

			const u64 now_us = get_system_time();
			const u64 idle_us = m_pump->performance_counters.idle_time.load();

			if (s_last_now != 0 && now_us > s_last_now)
			{
				const u64 wall = now_us - s_last_now;

				// The flip-to-flip deadline. Without it the hint judges a 30fps game against a
				// 60fps target and over-boosts, which is pure heat.
				rpcs3::utils::report_frame_period_ns(wall * 1000);

				// idle_time is reset periodically by get_load(), so a delta that went backwards is
				// a reset, not a real frame. Idle can also exceed the wall window (it accrues from
				// FIFO/semaphore paths). Reporting work == wall in either case would feed a bogus
				// fully-busy sample and over-boost; skipping leaves the last good one in place.
				if (idle_us >= s_last_idle)
				{
					if (const u64 idle_delta = idle_us - s_last_idle; idle_delta < wall)
					{
						rpcs3::utils::report_frame_work_ns((wall - idle_delta) * 1000);
					}
				}
			}

			s_last_now = now_us;
			s_last_idle = idle_us;
		}
#endif

		if (m_async_flip_request.requested & flip_request::any)
		{
			// Deferred flip
			if (info.emu_flip)
			{
				m_async_flip_request.requested.clear(flip_request::emu_requested);
			}
			else
			{
				m_async_flip_request.requested.clear(flip_request::native_ui);
			}
		}

		if (info.emu_flip)
		{
			m_pump->performance_counters.sampled_frames++;

			if (m_pause_after_x_flips && m_pause_after_x_flips-- == 1)
			{
				Emu.Pause();
			}
		}

		last_host_flip_timestamp = get_system_time();

		if (presented)
		{
			renderer->post_present();
		}

#ifndef __ANDROID__
		// Check for display config change
		if (m_pump->renderer_info() != renderer->configured_logical_id())
		{
			m_pump->request_renderer_update();
		}
#else
		// ARMSX3: the renderer is only chosen at boot on Android (_armsx3_settingsSet). Replacing it in
		// place wedged the GPU on 10-29 and then the whole phone, and a configured adapter name that
		// matches no device would ask for a replacement after every flip.
#endif
	}

	void display_engine::post_vblank_event(u64 post_event_time)
	{
		vblank_count++;

		if (m_pump->isHLE)
		{
			if (auto ptr = m_pump->vblank_handler)
			{
				m_pump->intr_thread->cmd_list
				({
					{ ppu_cmd::set_args, 1 }, u64{1},
					{ ppu_cmd::lle_call, ptr },
					{ ppu_cmd::sleep, 0 }
				});

				m_pump->intr_thread->cmd_notify.store(1);
				m_pump->intr_thread->cmd_notify.notify_one();
			}
		}
		else
		{
			// Broadcast vblank event to all context listeners.
			rsx::simple_array<u32> lv2_ids;
			m_pump->for_each_context([&](context* ctx)
			{
				if (ctx->fw.driver_info)
				{
					lv2_ids.push_back(ctx->id());
				}
			});

			const u64 guest_time = get_guest_system_time(post_event_time);
			for (const u32 id : lv2_ids)
			{
				sys_rsx_context_attribute(id, 0xFED, 1, guest_time, 0, 0);
			}
		}
	}

	void display_engine::start_vblank_thread()
	{
		g_fxo->get<vblank_thread>().set_thread(std::shared_ptr<named_thread<std::function<void()>>>(new named_thread<std::function<void()>>("VBlank Thread"sv, [this]() -> void
		{
#ifdef __linux__
			constexpr u32 host_min_quantum = 10;
#else
			constexpr u32 host_min_quantum = 500;
#endif
			rsx::prof::start_sampler();

			u64 start_time = get_system_time();

			u64 vblank_rate = g_cfg.video.vblank_rate;
			u64 vblank_period = 1'000'000 + u64{g_cfg.video.vblank_ntsc.get()} * 1000;

			u64 local_vblank_count = 0;

			// TODO: exit condition
			u64 last_heartbeat = 0;
			u64 iterations = 0;

			// NOT gated on unsent_gcm_events.
			//
			// That flag is the savestate hand-off, but any live send failure sets it too, and
			// this thread is the only vblank source in the emulator with nothing to restart it.
			// Ending the loop for it converts one transient failure into a permanent guest
			// deadlock. A savestate stops the emulator, so the two checks that remain still end
			// the loop exactly when they should.
			while (!m_pump->is_stopped() && thread_ctrl::state() != thread_state::aborting)
			{
				// Get current time
				const u64 current = get_system_time();

				// Heartbeat. This thread is the only source of the interrupt gcm waits on, so
				// when it stops the guest hangs after gcm init with everything else looking
				// idle -- and it said nothing either way. Distinguishes "still looping" from
				// "blocked inside post_vblank_event" from "left the loop", which need
				// different fixes and are indistinguishable from outside.
				if (current - last_heartbeat >= 5'000'000)
				{
					last_heartbeat = current;
					// Promoted out of notice: the SPU/RSX channels do not pass notice to logcat on Android, so
					// this heartbeat -- the one thing that says whether the vblank SOURCE is still producing
					// while the guest's gcm interrupt thread has stopped waking -- was invisible in every
					// device log we have ever collected.
					rsx_log.success("VBlank: alive, iterations=%u, vblank_count=%u", iterations, local_vblank_count);
				}

				iterations++;

				// Sampled here because this thread wakes ~60Hz regardless of what the guest
				// or the RSX are doing, so it lands mid-stall instead of at the flip.
				rsx::prof::stall_watchdog();

				// Calculate the time at which we need to send a new VBLANK signal
				const u64 post_event_time = start_time + (local_vblank_count + 1) * vblank_period / vblank_rate;

				// Calculate time remaining to that time (0 if we passed it)
				const u64 wait_for = current >= post_event_time ? 0 : post_event_time - current;

#ifdef __linux__
				const u64 wait_sleep = wait_for;
#else
				// Substract host operating system min sleep quantom to get sleep time
				const u64 wait_sleep = wait_for - u64{wait_for >= host_min_quantum} * host_min_quantum;
#endif

				if (!wait_for)
				{
					{
						local_vblank_count++;

						if (local_vblank_count == vblank_rate)
						{
							// Advance start_time to the moment of the current VBLANK
							// Which is the last VBLANK event in this period
							// This is in order for multiplication by ratio above to use only small numbers
							start_time += vblank_period;
							local_vblank_count = 0;

							// We have a rare chance to update settings without losing precision whenever local_vblank_count is 0
							vblank_rate = g_cfg.video.vblank_rate;
							vblank_period = 1'000'000 + u64{g_cfg.video.vblank_ntsc.get()} * 1000;
						}

						post_vblank_event(post_event_time);
					}
				}
				else if (wait_sleep)
				{
					thread_ctrl::wait_for(wait_sleep);
				}
				else if (wait_for >= host_min_quantum / 3 * 2)
				{
					std::this_thread::yield();
				}

				if (Emu.IsPaused())
				{
					// Save the difference before pause
					start_time = get_system_time() - start_time;

					while (Emu.IsPaused() && !m_pump->is_stopped())
					{
						// ARMSX3: 4 checks a second instead of 200 while the app is out of sight (#196)
						thread_ctrl::wait_for(g_android_app_backgrounded ? 250'000 : 5'000);
					}

					// Restore difference
					start_time = get_system_time() - start_time;
				}
			}

			// Which condition ended it. unsent_gcm_events is the savestate hand-off, so seeing
			// it here outside a savestate means a live send failed and took the vblank source
			// down with it -- permanently, since nothing restarts this thread.
			rsx_log.error("VBlank: loop exited after %u iterations (stopped=%d, unsent_gcm_events=%d, aborting=%d)",
				iterations, m_pump->is_stopped() ? 1 : 0, m_pump->has_unsent_gcm_events() ? 1 : 0,
				thread_ctrl::state() == thread_state::aborting ? 1 : 0);
		})));
	}

	void display_engine::stop_vblank_thread()
	{
		g_fxo->get<vblank_thread>() = thread_state::finished;
	}

	f64 display_engine::cached_refresh_rate()
	{
		constexpr u64 uses_per_query = 512;

		f64 result = m_cached_display_rate;
		u64 count = m_display_rate_fetch_count++;

		while (true)
		{
			if (count % 512 == 0)
			{
				result = m_output->refresh_rate();
				m_cached_display_rate.store(result);
				m_display_rate_fetch_count += uses_per_query; // Notify users of the new value
				break;
			}

			const u64 new_count = m_display_rate_fetch_count;
			const f64 new_cached = m_cached_display_rate;

			if (result == new_cached && count / uses_per_query == new_count / uses_per_query)
			{
				break;
			}

			// An update might have gone through
			count = new_count;
			result = new_cached;
		}

		return result;
	}

	void display_engine::evaluate_cpu_usage_reduction_limits()
	{
		const u64 max_preempt_count = g_cfg.core.max_cpu_preempt_count_per_frame;
		if (!max_preempt_count)
		{
			frame_times.clear();
			lv2_obj::set_yield_frequency(0, 0);
			return;
		}

		const u64 current_time = get_system_time();
		const u64 current_tsc = utils::get_tsc();

		if (frame_times.size() < power_mgmt::frame_history_length)
		{
			lv2_obj::set_yield_frequency(0, 0);
			frame_times.push_back(frame_time_t{0, current_time, current_tsc});
			return;
		}

		const auto stats = power_mgmt::collect_frame_pacing_stats(frame_times, current_time);
		const auto targets = power_mgmt::get_pacing_targets(g_cfg.video.vblank_rate * 10);

		const bool can_raise = stats.settled && !stats.hard_fails && stats.fails < 6 && power_mgmt::match_pacing_target(targets, stats.fps_10, 3, 4);

		const bool sudden_fps_drop = stats.last_frame_hard_fail &&
			stats.preempt_drops < stats.preempt_raises + stats.hard_fails &&
			(stats.fails > 13 || stats.hard_fails > 2 || !power_mgmt::match_pacing_target(targets, stats.fps_10, 20, 30));

		const u64 old_preempt_count = std::min<u64>(frame_times.back().preempt_count, max_preempt_count);
		u64 preempt_count = old_preempt_count;
		bool hard_measures_taken = false;

		if (can_raise)
		{
			if (prevent_preempt_increase_tickets)
			{
				prevent_preempt_increase_tickets--;
			}
			else
			{
				preempt_count = std::min<u64>(preempt_count + 4, max_preempt_count);
			}
		}
		else if (stats.settled || sudden_fps_drop)
		{
			preempt_count -= std::min<u64>(preempt_count, 10);

			if ((stats.hard_fails > 2 || stats.fails > 20) && stats.last_frame_hard_fail)
			{
				hard_measures_taken = preempt_count > 1;
				preempt_count = preempt_count * 7 / 8;
				prevent_preempt_increase_tickets = 10;
			}
			else
			{
				prevent_preempt_increase_tickets = std::max<u32>(7, prevent_preempt_increase_tickets);
			}
		}

		perf_log.trace("CPU preemption control: settled=%d, preempt_count=%llu, fails=%u, hard=%u, avg_frame_time=%llu, raised=%u, lowered=%u, taken=%u",
			stats.settled, preempt_count, stats.fails, stats.hard_fails, stats.avg_frame_time, stats.preempt_raises, stats.preempt_drops, ::g_lv2_preempts_taken.load());

		if (hard_measures_taken)
		{
			preempt_fail_old_preempt_count = std::max<u64>(preempt_fail_old_preempt_count, old_preempt_count);
		}
		else if (preempt_fail_old_preempt_count)
		{
			perf_log.error("Lowering current preemption count significantly due to a performance drop, if this issue persists frequently consider lowering max preemptions count to 'new-count' or lower. (old-count=%llu, new-count=%llu)", preempt_fail_old_preempt_count, preempt_count);
			preempt_fail_old_preempt_count = 0;
		}

		if (!preempt_count)
		{
			lv2_obj::set_yield_frequency(0, 0);
		}
		else
		{
			const u64 tsc_diff = current_tsc - frame_times.back().tsc;
			const u64 time_diff = current_time - frame_times.back().timestamp;
			const u64 preempt_diff = tsc_diff * (1'000'000 / 30) / (time_diff * preempt_count);

			// Stop yielding near the end of the frame so a sudden performance drop backs off instead of stuttering
			const auto target = power_mgmt::match_pacing_target(targets, stats.fps_10, 30, 40);
			const u64 yield_window_tsc = target
				? tsc_diff * (1'000'000 * target->yield_pct / (target->fps_10 * 10)) / time_diff
				: tsc_diff * 88 / 100;

			lv2_obj::set_yield_frequency(preempt_diff, current_tsc + yield_window_tsc);
		}

		frame_times.pop_front();
		frame_times.push_back(frame_time_t{preempt_count, current_time, current_tsc});
	}

	void vblank_thread::set_thread(std::shared_ptr<named_thread<std::function<void()>>> thread)
	{
		std::swap(m_thread, thread);
	}

	vblank_thread& vblank_thread::operator=(thread_state state)
	{
		if (m_thread)
		{
			*m_thread = state;
		}

		return *this;
	}
}
