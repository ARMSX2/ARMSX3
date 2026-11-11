// SPDX-License-Identifier: GPL-2.0-only
// On 4 GB-class devices a first-boot PPU precompile with every core got the app killed, so those boots compile on two threads.
#pragma once

namespace armsx3::bridge::low_memory
{
	constexpr unsigned long long k_small_limit = 3ull << 30; // a jetsam limit below this is a 4 GB-class device
	constexpr unsigned k_llvm_threads = 2;                   // compile workers on such a device

	// 0 for no change; an unknown limit (the Mac) changes nothing.
	inline unsigned llvm_threads_clamp(unsigned long long jetsam_limit, unsigned long long configured)
	{
		if (jetsam_limit == 0 || jetsam_limit >= k_small_limit)
		{
			return 0;
		}
		return configured == 0 || configured > k_llvm_threads ? k_llvm_threads : 0;
	}

	// Without the shader interpreter the peak was about 400 MiB lower on a 4 GB device's limit.
	inline bool shader_interpreter_off(unsigned long long jetsam_limit, bool async_with_interpreter)
	{
		return jetsam_limit != 0 && jetsam_limit < k_small_limit && async_with_interpreter;
	}

	// The extra pass adds compile memory to the precompile that kills 4 GB-class devices.
	inline bool early_cse_off(unsigned long long jetsam_limit, bool enabled)
	{
		return jetsam_limit != 0 && jetsam_limit < k_small_limit && enabled;
	}
}
