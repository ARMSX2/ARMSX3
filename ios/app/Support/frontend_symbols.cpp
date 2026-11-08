// SPDX-License-Identifier: GPL-2.0-only
// The symbols the core declares and expects its frontend to define. The app target is the iOS frontend, so it defines them.

// Object definitions need their complete types; a hand-copied class would silently break the one-definition rule.
#include "Emu/Io/mouse_config.h"
#include "Emu/Io/pad_config.h"
#include "util/logs.hpp"
#include "../../core/ios_stderr_witness.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <pthread.h>
#include <string>
#include <string_view>
#include <thread>

// Named input-configuration profiles. This bundle has none, so empty is what pad_config.cpp checks for.
cfg_input_configurations g_cfg_input_configs;

// Upstream's `--input-config` override; empty means "use the default".
std::string g_input_config_override;

// Upstream defines this in basic_mouse_handler.cpp, which the iOS Input subset does not build.
mouse_config g_cfg_mouse;

// GracefulShutdown hands its real work in as `wrapped_op`, so the operation must run, retried every `repeat_duration_ms`.
void qt_events_aware_op(int repeat_duration_ms, std::function<bool()> wrapped_op)
{
	// A return, not upstream's `ensure`: util/asserts.hpp is a core header this file does not include.
	if (!wrapped_op)
	{
		return;
	}

	// On the main thread waiting would deadlock, since the Kill it waits for is posted to the main queue, so skip and say so.
	if (pthread_main_np() != 0)
	{
		armsx3_stderr_witness_printf(
			"ARMSX3: qt_events_aware_op called on the main thread; the wrapped operation was "
			"NOT run on this thread.\n");
		return;
	}

	// A `repeat_duration_ms` of 0 is legal and common; it yields rather than pinning a core.
	while (!wrapped_op())
	{
		if (repeat_duration_ms > 0)
		{
			std::this_thread::sleep_for(std::chrono::milliseconds(repeat_duration_ms));
		}
		else
		{
			std::this_thread::yield();
		}
	}
}

// stderr, a log sync, then abort(): called from emulator threads, where an alert would deadlock, and abort keeps the backtrace.
[[noreturn]] void report_fatal_error(std::string_view text, bool /*is_html*/ = false,
	bool /*include_help_text*/ = true)
{
	armsx3_stderr_witness_printf("ARMSX3: FATAL: %.*s\n", static_cast<int>(text.size()), text.data());
	logs::listener::sync_all();
	std::abort();
}
