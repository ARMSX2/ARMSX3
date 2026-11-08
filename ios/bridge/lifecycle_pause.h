// SPDX-License-Identifier: GPL-2.0-only
// The pure pause machine: not thread-safe because ios_frame.mm serialises every call, and a resume only undoes its own pause.
#pragma once

#include <cstdint>

namespace armsx3::ios_frame
{
	struct pause_ledger
	{
		std::uint64_t deactivations = 0;
		std::uint64_t activations = 0;
		std::uint64_t pauses_ok = 0;
		std::uint64_t pauses_declined = 0;
		std::int64_t last_pause_status = 1;
		std::uint64_t resumes_ok = 0;
		std::uint64_t resumes_declined = 0;
		std::uint64_t resumes_not_owed = 0;
		std::int64_t last_resume_status = 1;
		std::uint64_t pause_latency_ms_max = 0;
		std::uint64_t pauses_landed_in_background = 0;
		std::uint64_t pauses_during_armed_capture = 0;
		std::uint64_t paused_ms_total = 0;
		std::uint64_t background_ms_total = 0;
		std::uint64_t background_ms_longest = 0;
		std::int64_t flips_at_last_pause = -1;
		std::int64_t flips_at_last_resume = -1;
		std::uint64_t menu_opens = 0;
		std::uint64_t menu_closes = 0;
		std::uint64_t resumes_kept_for_menu = 0;
		std::uint64_t resumes_kept_for_lifecycle = 0;
		bool held = false;
	};

	struct pause_machine
	{
		pause_ledger l;

		// Private by convention.
		bool in_background = false;
		std::uint64_t background_since_ms = 0;
		std::uint64_t paused_since_ms = 0;
		// Wanted from a deactivation until the next activation, whether or not the emulator took it.
		bool wanted = false;
		// The menu wants a pause from its open until its close.
		bool menu_wanted = false;

		// Not while one is already held.
		bool on_deactivate()
		{
			++l.deactivations;
			wanted = true;
			return !l.held;
		}

		// ios_frame.mm retries the pause while this holds and the core is still on its way to RUNNING.
		bool deferred_pause_owed(std::uint64_t generation) const
		{
			return wanted && !l.held && l.deactivations == generation;
		}

		void on_pause_result(std::int32_t status, std::uint64_t requested_ms, std::uint64_t landed_ms,
			std::uint64_t flips, bool capture_pending)
		{
			l.last_pause_status = status;
			if (status != 0)
			{
				++l.pauses_declined;
				return;
			}
			l.held = true;
			paused_since_ms = landed_ms;
			++l.pauses_ok;
			l.flips_at_last_pause = static_cast<std::int64_t>(flips);
			const std::uint64_t latency = landed_ms > requested_ms ? landed_ms - requested_ms : 0;
			if (latency > l.pause_latency_ms_max)
				l.pause_latency_ms_max = latency;
			if (in_background)
				++l.pauses_landed_in_background;
			if (capture_pending)
				++l.pauses_during_armed_capture;
		}

		// Only for a pause this machine holds, and not while the menu still wants it.
		bool on_activate()
		{
			++l.activations;
			wanted = false;
			if (l.held)
			{
				if (menu_wanted)
				{
					++l.resumes_kept_for_menu;
					return false;
				}
				return true;
			}
			++l.resumes_not_owed;
			return false;
		}

		// On the first open only; the background's pause serves the menu too.
		bool on_menu_open()
		{
			++l.menu_opens;
			const bool first = !menu_wanted;
			menu_wanted = true;
			return first && !l.held;
		}

		// Not while the scene is still deactivated: the activation resumes it then.
		bool on_menu_close()
		{
			++l.menu_closes;
			if (!menu_wanted)
				return false;
			menu_wanted = false;
			if (!l.held)
				return false;
			if (wanted)
			{
				++l.resumes_kept_for_lifecycle;
				return false;
			}
			return true;
		}

		// A resume declined while the core still reads PAUSED keeps the hold, so the next close or activation asks again.
		void on_resume_result(std::int32_t status, std::uint64_t landed_ms, std::uint64_t flips,
			bool still_paused)
		{
			l.last_resume_status = status;
			if (status != 0 && still_paused)
			{
				++l.resumes_declined;
				return;
			}
			l.held = false;
			if (landed_ms > paused_since_ms)
				l.paused_ms_total += landed_ms - paused_since_ms;
			if (status != 0)
			{
				++l.resumes_declined;
				return;
			}
			++l.resumes_ok;
			l.flips_at_last_resume = static_cast<std::int64_t>(flips);
		}

		// A failed read passes true, so a hold is never dropped on a guess; a dropped hold is not resumed.
		void on_core_state(bool core_paused)
		{
			if (l.held && !core_paused)
				l.held = false;
		}

		void on_background(std::uint64_t t_ms)
		{
			if (in_background)
				return;
			in_background = true;
			background_since_ms = t_ms;
		}

		void on_foreground(std::uint64_t t_ms)
		{
			if (!in_background)
				return;
			in_background = false;
			const std::uint64_t span = t_ms > background_since_ms ? t_ms - background_since_ms : 0;
			l.background_ms_total += span;
			if (span > l.background_ms_longest)
				l.background_ms_longest = span;
		}
	};

	// Defined in ios_frame.mm, a copy taken under its mutex.
	pause_ledger pause_ledger_snapshot();
} // namespace armsx3::ios_frame
