// SPDX-License-Identifier: GPL-2.0-only
// The product log's entry points; no core types here, so any bridge file can include it.
#pragma once

#include <string>

namespace armsx3::bridge::diag
{
	// False when an earlier call already installed; the caller has checked that `dir` exists.
	bool start(const std::string& dir, bool verbose);

	// Before `start` it only sets the flag, so no trace line is stored in memory before the file exists.
	void set_verbose(bool on);

	// Writes a stall dump first when the game has stopped drawing, then flushes the file writer to disk.
	void sync();

	// Called on the RSX thread at each guest flip; a no-op before `start`.
	void note_flip();

	// Called on the RSX thread when the scene-inactive hold parks and ends, so a stall dump can say how long it lasted.
	void note_rsx_hold(bool held);

	// 0 before `start`.
	unsigned long long fatal_thread_exits();
}
