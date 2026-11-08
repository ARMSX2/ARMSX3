// SPDX-License-Identifier: GPL-2.0-only
// What happens when JIT pool 2 is full or nearly full; JITASM.cpp includes it everywhere, so it stays portable.
#pragma once

#include "util/types.hpp"

namespace utils
{
	// Apple ARM64: logs once and marks a non-main caller to park, so nobody gets the nullptr upstream turns into a crash.
	void jit_pool_full(const char* pool, u64 used, u64 limit, u64 request);

	// Declare one before a lock: its destructor parks a marked thread once the lock is released.
	struct jit_pool_full_parker { ~jit_pool_full_parker(); };

	// 0 when nothing is full, 1 for pool 2's code half, 2 for its data half, 3 for pool 3. Readable from any thread.
	int jit_pool_full_reason();

	// Apple ARM64: true when pool 2's code half is near its limit, so the SPU block runs in the interpreter instead.
	bool jit_pool2_refuse_spu_block();

	// Blocks refused since pool 2 last had room; 0 elsewhere.
	u64 jit_pool2_refused_blocks();
}
