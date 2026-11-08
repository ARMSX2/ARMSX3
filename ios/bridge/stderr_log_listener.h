// SPDX-License-Identifier: GPL-2.0-only
#pragma once

namespace armsx3::bridge
{
	// Idempotent, because logs::listener::add has no duplicate check; the listener is never removed.
	void install_stderr_log_listener();
}
