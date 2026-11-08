// SPDX-License-Identifier: GPL-2.0-only
// The launch-time JIT verdict, read from the dual map's report and never probed, because executing a byte to test is the kill itself.
#pragma once

#include <cstring>

namespace armsx3::jit_state
{
	struct inputs
	{
		bool is_iphone;
		bool block_active;
		const char* enabler_reason;
		int handshake_performed;
	};

	struct verdict
	{
		bool on;
		const char* reason;
	};

	inline verdict decide(const inputs& in)
	{
		if (!in.is_iphone)
			return {true, "macos-host"};
		if (!in.block_active)
			return {false, "no-dual-map"};
		if (in.enabler_reason != nullptr && std::strcmp(in.enabler_reason, "helper-ok") == 0)
			return {true, "helper-ok"};
		if (in.handshake_performed == 1)
			return {true, "stikdebug-handshake"};
		return {false, in.enabler_reason != nullptr ? in.enabler_reason : "no-enabler-reason"};
	}
} // namespace armsx3::jit_state
