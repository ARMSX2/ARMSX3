// SPDX-License-Identifier: GPL-2.0-only
// Written by cellGame's eject and insert callbacks on a guest thread, read by the disc-swap entry points.
#pragma once

#include <atomic>

namespace armsx3::bridge
{
	inline std::atomic<bool> g_disc_eject_enabled{false};
	inline std::atomic<bool> g_disc_insert_enabled{false};
}
