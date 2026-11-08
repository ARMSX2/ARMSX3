// SPDX-License-Identifier: GPL-2.0-only
// What the parts of the dual mapping share: includes, helpers, state and constants.
#pragma once

#include "ios_jit_dual_map.h"
#include "ios_jit_backend.h"
#include "ios_stderr_witness.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

// Two arms: off Apple this names no POSIX constant, and Windows gets neither.
#if defined(__APPLE__)
#include <TargetConditionals.h>
#include <errno.h>
#include <sys/mman.h>
#include <unistd.h>
#if TARGET_OS_IPHONE
#include <os/proc.h>
#endif
#endif

#if defined(__APPLE__) && defined(ARCH_ARM64)
#include <dlfcn.h>
#include <libkern/OSCacheControl.h>
#include <mach/mach.h>
#include <mach/vm_map.h>
#include <mach/vm_region.h>
#include <mach/vm_statistics.h>
#include <errno.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <sys/sysctl.h>
#include <time.h>

#include <atomic>

#include "ios_jit_pool_full.h"
#include "ios_jit_split.h"
#endif

#if defined(__APPLE__) && defined(ARCH_ARM64)

namespace jit_dual_map_internal
{

	unsigned long long mono_ms();

	constexpr unsigned long long k_exec_half_size = 0x80000000ull;  // [0, 0x80000000): r-x, forever
	constexpr unsigned long long k_pool2_size = 0x40000000ull;  // pool2_code_size
	constexpr unsigned long long k_pool1_off  = 0x40000000ull;  // pool1_offset
	constexpr unsigned long long k_pool1_size = 0x1000000ull;   // pool1_size, 16 MiB
	constexpr unsigned long long k_pool3_off  = 0x41000000ull;  // pool3_offset

	// Pool 3 runs to the executable half's end: 18 units of MemoryManager1::c_max_size, which a test pins with it.
	constexpr unsigned long long k_pool3_size = 0x3f000000ull;  // pool3_size, 1008 MiB

	// Derived from the header's granularity, so no second literal has to be kept in step.
	constexpr unsigned long long k_pool3_granule = utils::jit_pool3_unit_granularity;

	// Each prepared 16 KiB page stays resident and dirty and no madvise frees it, so only what the pools use is prepared.
	// An unprepared page raises SIGBUS exactly at its 16 KiB boundary, so each pool is capped at what was prepared.
	// These are the tiers the learned split in ios_jit_split.h starts from.
	constexpr unsigned long long k_prep_pool2_bytes = 0x10000000ull; // 256 MiB of pool 2's 1 GiB code half
	constexpr unsigned long long k_prep_pool3_bytes = 0x24000000ull; // 576 MiB of pool 3's 1008 MiB
	constexpr unsigned long long k_prep_pool2_small = 0x8000000ull;    // 128 MiB
	constexpr unsigned long long k_prep_pool3_small = 0x18000000ull;   // 384 MiB
	[[noreturn]] void abort_at_the_cause(const char* what, const char* detail, long long value);
	const char* kern_return_name(kern_return_t kr);

	// Protections are read back, never assumed; vm_region_recurse_64 answers for the next region up, so check contained.
	struct region_report
	{
		int           contained;
		kern_return_t kr;
		unsigned long long base;
		unsigned long long size;
		int           prot;
		int           max_prot;
		int           share_mode;
	};
	void region_probe(unsigned long long at, region_report* out);
	void prot_string(int bits, char out[4]);
	extern _Thread_local sigjmp_buf s_brk_jmp;
	[[noreturn]] void brk_trap_handler(int);
	__attribute__((noinline, optnone)) void jit_send_script_universal(const char* script, std::size_t len);
	__attribute__((noinline, optnone)) void jit_detach_universal();
	int process_is_traced();

	// The top two 16 KiB pages of the executable half, inside pool 3, which nothing carves before main().
	constexpr unsigned long long k_stub_off     = 0x7fff8000ull; // `before` and `after`
	constexpr unsigned long long k_stub_new_off = 0x7fffc000ull; // `after_new`

	// Wall-clock bound, unlike the prepare's poll count, so it ends at the first poll after 1000 ms.
	constexpr unsigned long long k_stub_bound_ms = 1000;
	constexpr long               k_stub_poll_ns  = 10L * 1000000L;

	// Readback sentinels: -1 when the read was never taken, -2 when it faulted, so neither looks like the byte 0x00.
	constexpr long long k_readback_skipped = -1;
	bool install_stub_guard();
	void restore_stub_guard();

	// Atomic, since the constructor's last read may come from another thread.
	struct stub_call
	{
		std::atomic<const char*> outcome{"skipped"};
		std::atomic<long long>   faults_at_start{-1};
		std::atomic<long long>   faults_at_end{-1};

		unsigned char*           exec = nullptr;
		std::atomic<int>         state{0}; // 0 not resolved, 1 returned, 2 faulted
		std::atomic<long long>   value{-1};
		std::atomic<long long>   signal{0};
		std::atomic<long long>   ms{-1};
	};
	void plant_stub(unsigned char* exec);
	void run_stub(stub_call* c, unsigned char* exec);
	bool stub_unresolved(const stub_call& c);
	utils::jit_stub_call_report snapshot_stub(const stub_call& c);
	long long read_guarded_byte(const volatile unsigned char* p);
	const char* readback_tok(long long v, char* buf, std::size_t n);

	// universal.js reads the script with x1's decimal digits as hex, so 1000 reads 0x1000 bytes; keep text and NUL in the first 1000.
	constexpr std::size_t k_script_buffer_size = 0x1000;
	constexpr std::size_t k_script_x1 = 1000;

	// The walk stops after this much of its own time; the pages the checks use are written first. A stored value may replace it.
	constexpr int k_script_budget_ms = 15000;

	// "START" and "FINISH" in ASCII. Both are below 2^53, so JavaScript's Number writes them exactly.
	constexpr unsigned long long k_script_started = 0x5354415254ull;
	constexpr unsigned long long k_script_finished = 0x46494e495348ull;

	// Words 0-2 are written before the walk, words 3-8 after it.
	constexpr int k_script_report_words = 9;
	constexpr int k_script_walk_words = 12;
	void script_walk(unsigned long long pool2_bytes, unsigned long long pool3_bytes, unsigned long long share2, unsigned long long share3, unsigned long long* w);
	void proven_extents(const unsigned long long* w, long long pages, long long stop_off, unsigned long long* pool2_bytes, unsigned long long* pool3_bytes);
	alignas(16) extern char s_script_text[k_script_buffer_size];
	extern int s_script_budget_ms;
	extern volatile unsigned long long s_script_report[k_script_report_words];
	extern std::atomic<int> s_second_offer;
	extern std::atomic<bool> s_second_pass_in_flight;
	extern int s_second_trigger;
	extern std::atomic<bool> s_second_line_ready;
	extern char s_second_line[320];

	// Polled the way wait_for_debugger polls, so time the process spends suspended in the background is not counted.
	constexpr int k_second_pass_wait_ms = 60000;
	// A literal, because a test evaluates the constants here; the static_assert ties it to its parts.
	constexpr unsigned long long k_carve_second_pass_extra_ms = 90000;
	const char* build_prepare_script(unsigned long long base, const unsigned long long* walk, long long* len);

	struct prepare_args
	{
		void*            addr;
		std::atomic<int> done;
		std::atomic<int> ok;
		std::atomic<int> trapped;

		// Decides once who owns the prepare's return: the worker (1) or the caller that gave up (2), after which no request is issued.
		std::atomic<int>                claim;
		std::atomic<int>                detach_done;
		std::atomic<int>                detach_ok;
		std::atomic<int>                traced_before;
		std::atomic<unsigned long long> detach_ms;

		// 1 while the stub guard holds SIGBUS, SIGSEGV and SIGILL; no call starts without it.
		std::atomic<int> stub_guarded;
		stub_call        before;
		stub_call        after;
		stub_call        after_new;

		// Bytes read through the execute and alias views before the stub is planted.
		std::atomic<long long> readback_exec_0x0{k_readback_skipped};
		std::atomic<long long> readback_exec_0x40000000{k_readback_skipped};
		std::atomic<long long> readback_exec_0x7fff8000{k_readback_skipped};
		std::atomic<long long> readback_exec_0x7fffc000{k_readback_skipped};
		std::atomic<long long> readback_exec_0x1{k_readback_skipped};
		std::atomic<long long> readback_exec_0x40000340{k_readback_skipped};
		std::atomic<long long> readback_alias_0x0{k_readback_skipped};
		std::atomic<long long> readback_alias_0x7fff8000{k_readback_skipped};
	};
	void* prepare_worker(void* raw);

	// A hang is a result: the worker is left detached, the timeout is recorded and boot goes on.
	constexpr int k_prepare_timeout_polls = 300;
	constexpr int k_prepare_poll_ms = 100;

	// Pre-main budget for the Wait for Debugger method; a test keeps it at or below 10000.
	constexpr int k_wait_for_debugger_ms = 10000;
	bool wait_for_debugger(int budget_ms);

	// armsx3_jit_enabler_prepare_ranges: (base, size) pairs in one attach.
	using builtin_enabler_prepare_ranges_fn = int (*)(const u64* ranges, u32 count, s32 pid, void* result);

	// The launcher's OK code; anything else falls back to the StikDebug prepare.
	constexpr int k_builtin_enabler_ok = 1;

	// No readable pairing file: falls back like any failure, but recorded as pairing-absent so setup opens at that step.
	constexpr int k_builtin_enabler_pairing_absent = 4;

	// Mirrors jit_launch_plan.h's codes, which a test checks; reached through dlsym, and a missing symbol means Built-in.
	constexpr int k_jit_plan_stikdebug = 1;
	constexpr int k_jit_plan_wait = 2;
	constexpr int k_jit_plan_method_mask = 3;
	constexpr int k_jit_plan_livecontainer = 4;
	using launch_plan_fn = int (*)();
	// The learned split's store in the launcher, reached the same way.
	using history_read_fn = int (*)(armsx3_jit_pool_history*, long long*);
	using history_write_fn = void (*)(const armsx3_jit_pool_history*);
}

#endif
