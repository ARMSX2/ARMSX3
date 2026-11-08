// SPDX-License-Identifier: GPL-2.0-only

#include "ios_jit_pool_full.h"

#include <atomic>

#if defined(__APPLE__) && defined(__aarch64__)
#include <pthread.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

#include "util/logs.hpp"
#include "Utilities/JIT.h"
#include "ios_jit_dual_map.h"

LOG_CHANNEL(jit_log, "JIT");

namespace
{
	// Set by jit_pool_full while the allocator lock is held; the parker reads it once the lock is released.
	thread_local bool s_park_after_unlock = false;

	// Lock-free top of the code half. A failed pool keeps its sticky 0x40000000 bit in here, so it reads as full.
	u64 pool2_code_used()
	{
		// Size 0 and align 0 return the code half's base, which is constant for the process.
		static u8* const base = jit_runtime::alloc(0, 0, true);

		return static_cast<u64>(jit_runtime::peek(true) - base);
	}
}
#endif

namespace
{
	std::atomic<int> s_reason{0};
	std::atomic<u64> s_refused{0};
}

int utils::jit_pool_full_reason()
{
	return s_reason.load();
}

u64 utils::jit_pool2_refused_blocks()
{
	return s_refused.load();
}

#if defined(__APPLE__) && defined(__aarch64__)
void utils::jit_pool_full(const char* pool, u64 used, u64 limit, u64 request)
{
	// shortcut: three fixed names (pool 2 code, pool 2 data, pool 3 from the dual map's carve); a fourth would need a real parameter.
	const int reason = std::strstr(pool, "pool 3") ? 3 : std::strstr(pool, "data") ? 2 : 1;

	int expected = 0;
	if (s_reason.compare_exchange_strong(expected, reason))
	{
		jit_log.error("JIT %s is full: %u MiB of %u MiB used, a request of %u bytes did not fit. The game is halted; restart ARMSX3.",
			pool, used >> 20, limit >> 20, request);
		logs::listener::sync_all();
	}

	if (pthread_main_np() != 0)
	{
		return;
	}

	// The caller still holds jit_runtime::alloc's lock, so the park waits for the parker's destructor.
	s_park_after_unlock = true;
}

utils::jit_pool_full_parker::~jit_pool_full_parker()
{
	if (!s_park_after_unlock)
	{
		return;
	}

	// Park for good: no caller gets the nullptr, and the process ends through Close ARMSX3 rather than a teardown.
	for (;;)
	{
		std::this_thread::sleep_for(std::chrono::seconds(1));
	}
}
#else
void utils::jit_pool_full(const char*, u64, u64, u64)
{
}

utils::jit_pool_full_parker::~jit_pool_full_parker()
{
}
#endif

#if defined(__APPLE__) && defined(__aarch64__)
bool utils::jit_pool2_refuse_spu_block()
{
	// Room kept for PPU far jumps, trampolines, the SPU interpreter object and ubertrampoline rebuilds.
	constexpr u64 k_pool2_spu_reserve = 16 * 1024 * 1024;

	const u64 limit = utils::g_pool2_code_limit;
	const u64 reserve = limit < 2 * k_pool2_spu_reserve ? limit / 2 : k_pool2_spu_reserve;
	const u64 position = pool2_code_used();

	if (position + reserve < limit)
	{
		if (s_refused.load() != 0)
		{
			s_refused.store(0);
		}

		return false;
	}

	const u64 count = ++s_refused;

	if (count == 1)
	{
		jit_log.warning("JIT pool 2 is nearly full (%u of %u MiB): new SPU blocks run in the interpreter from now on; expect lower speed.",
			std::min(position, limit) >> 20, limit >> 20);
		logs::listener::sync_all();
	}
	else if ((count & (count - 1)) == 0)
	{
		jit_log.warning("JIT pool 2: %u SPU blocks have been sent to the interpreter so far.", count);
	}

	return true;
}

bool utils::jit_pool2_precompile_full()
{
	return pool2_code_used() >= utils::g_pool2_code_limit / 2;
}
#else
bool utils::jit_pool2_refuse_spu_block()
{
	return false;
}

bool utils::jit_pool2_precompile_full()
{
	return false;
}
#endif
