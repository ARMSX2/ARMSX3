// SPDX-License-Identifier: GPL-2.0-only
#pragma once

#include "host_services.h"

// Included, not forward-declared, because the table is returned by value.
#include "Emu/emu_callbacks.h"

namespace armsx3::bridge
{
	// `services` is copied into the table, so the result outlives a temporary argument.
	emu_callbacks make_callbacks(const host_services& services);

	// A counter, not a flag: the self-check also calls on_ready, so read it as a difference across the boot.
	u64 ready_observation_count();

	// Read as a difference across a save; seen only while the core's overlay exists.
	u64 spu_savestate_refusal_count();

	// Read as a difference across an interval; seen only while the core's overlay exists.
	u64 rpcn_relogins();

	// Called by the core's Stop Watchdog when a Stop has not finished after `seconds`.
	void stop_no_response_note(int seconds);

	unsigned long long stop_no_response_reports();
}
