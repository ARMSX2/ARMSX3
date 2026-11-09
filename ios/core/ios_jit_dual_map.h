// SPDX-License-Identifier: GPL-2.0-only
// The JIT dual mapping on Apple ARM64: one executable block, a writable alias of its lower half, and one prepare.

#pragma once

// util/types.hpp defines ARCH_ARM64; no <sys/mman.h>, because vm_native.cpp includes this on Windows too.
#include "util/types.hpp"

#include <cstddef>

namespace utils
{
	// Add to an execute address to get the writable address of the same bytes; 0 until the block exists, so jit_rw() is the identity elsewhere.
	extern ptrdiff_t g_code_rw_offset;

	// How much of pool 2's code half may be handed out: what the prepare covered. Set once, before any carve.
	extern usz g_pool2_code_limit;

	// The caller owes that p is in the executable half; ask jit_view_of() when unsure, since inline_runtime buffers are not.
	inline u8* jit_rw(u8* p) noexcept
	{
		return p + g_code_rw_offset;
	}

	// The `void*` spelling of the same thing, for sites holding an untyped pointer.
	inline void* jit_rw(void* p) noexcept
	{
		return static_cast<u8*>(p) + g_code_rw_offset;
	}

	// Pools 1 to 3 are carves of one block, so the one g_code_rw_offset reaches all of them.
	// A pool that reserved memory anywhere else would sit outside the alias, and its writes would land on unrelated memory.
	// So a full pool waits or stops; it never falls back to upstream's memory_reserve.
	enum class jit_pool
	{
		// AsmJIT's global runtime arena, reserved at static initialisation, before main().
		asmjit_arena,

		// get_jit_memory()'s 1 GiB code half and 1 GiB data half, committed lazily.
		jit_memory,

		// LLVM's MemoryManager1 units.
		llvm_code,
	};

	// Pool 2's data half sits past the alias, since the executable half must be one run the alias and the prepare cover.
#if defined(__APPLE__) && defined(ARCH_ARM64)
	constexpr usz jit_data_off = 0x100000000;
#else
	constexpr usz jit_data_off = 0x40000000;
#endif

	// Which of the block's regions an address is in.
	enum class jit_view
	{
		execute, // [base, base + 0x80000000): r-x, never written through
		alias,   // [base + 0x80000000, base + 0x100000000): rw-, never executed
		data,    // [base + 0x100000000, base + 0x140000000): rw-, pool 2's data half
		outside, // not in the block, or the block does not exist
	};

	// Execute base of a pool's carve, building the block on first use; nullptr means use upstream's reservation. Pool 3 hands out units and waits when full.
	void* jit_dual_map_carve(jit_pool pool, usz size) noexcept;

	// Gives a pool 3 unit back after the caller decommitted it; upstream never releases, but iOS cannot spare the address space.
	void jit_dual_map_release(jit_pool pool, void* unit) noexcept;

	// One 16 KiB page, the unit the prepare proves: each PPU module has its own chunks, and most hold a few hundred KiB.
	constexpr usz jit_pool3_unit_granularity = 16 * 1024;

	// A pool 3 carve that waits this long is exhausted for good, so it stops with the unit table instead of hanging.
	constexpr unsigned long long jit_pool3_carve_wait_ms = 30000;

	// False when the mechanism is inactive, so vm_native.cpp falls through to upstream's code.
	bool jit_dual_map_contains(const void* p) noexcept;

	// Which region. `outside` whenever the mechanism is inactive.
	jit_view jit_view_of(const void* p) noexcept;

	// A string constant for the fatal signal handler, which must not allocate.
	const char* jit_view_name(jit_view view) noexcept;

	// Makes no mprotect call, since every access was set at creation; aborts on write plus execute or on write in the executable half.
	bool jit_dual_map_commit(void* p, usz size, bool executable) noexcept;

	// madvise(MADV_FREE) only: munmap would leave the alias on freed pages, and mprotect would lose execute.
	bool jit_dual_map_decommit(void* p, usz size) noexcept;

	// One call of a two-instruction stub (mov w0, #42; ret) from the prepared half, under a fault guard; -1 fields when skipped.
	struct jit_stub_call_report
	{
		const char* outcome;
		long long   value;
		long long   signal;
		long long   ms;
		long long   faults_at_start;
		long long   faults_at_end;
	};

	// What the kernel actually conferred, read back rather than assumed; -1 until measured.
	struct jit_dual_map_report
	{
		unsigned long long block_base;
		unsigned long long block_size;
		unsigned long long alias_base;
		long long          code_rw_offset;

		unsigned long long exec_base;
		unsigned long long exec_size;

		// Strings, so a log reader need not decode a bitmask.
		char               exec_prot[4];
		char               exec_max_prot[4];
		char               alias_prot[4];

		int                share_mode;

		// Whether the single prepare ran, and how long it took.
		int                handshake_performed;
		unsigned long long handshake_ms;

		// The detach after the prepare: ok, sigtrap, timeout or skipped, with P_TRACED read before and after.
		const char*        detach_outcome;
		unsigned long long detach_ms;
		int                detach_traced_before;
		int                detach_traced_after;

		// The stub calls before the detach, after it, and from a fresh copy planted after it.
		jit_stub_call_report stub_before;
		jit_stub_call_report stub_after;
		jit_stub_call_report stub_after_new;

		// Bytes read back from the prepared half before the stub is planted: 0..255, -1 skipped, -2 faulted.
		long long readback_exec_0x0;
		long long readback_exec_0x40000000;
		long long readback_exec_0x7fff8000;
		long long readback_exec_0x7fffc000;
		long long readback_exec_0x1;
		long long readback_exec_0x40000340;
		long long readback_alias_0x0;
		long long readback_alias_0x7fff8000;

		// After a built-in prepare: page starts reading 0x69 and the first offset that does not, -1 when none.
		long long readback_exec_pages_0x69;
		long long readback_exec_first_miss_off;

		// What the prepare script reported: skipped, refused-address, refused-length, not-run, timeout, started, finished or foreign.
		const char* script_outcome;
		long long   script_len;
		long long   script_pages_ok;
		long long   script_pages_refused;
		long long   script_first_refused_off;
		long long   script_elapsed_ms;
		long long   script_budget_hit;
		// The script budget in ms, from k_script_budget_ms unless a stored value replaced it.
		long long   script_budget_ms;

		// What the launch-time JIT switch saw and why; skipped when it never ran.
		const char* enabler_selector;
		const char* enabler_reason;

		// Each pool's limit and the learned split that set it.
		unsigned long long pool2_limit_bytes;
		unsigned long long pool3_limit_bytes;
		unsigned long long split_sessions;
		unsigned long long split_budget_pages;
		unsigned long long split_need2_bytes;
		unsigned long long split_need3_bytes;
		unsigned long long split_share2_bytes;
		unsigned long long split_share3_bytes;

		// For the host smoke tracer only: the script text and the report block it writes.
		const char*        script_text;
		unsigned long long script_report_addr;

		// Pool 3's unit accounting, filled for every pool; carved and released count chunks, live, peak and capacity count granules.
		unsigned long long llvm_units_carved;
		unsigned long long llvm_units_released;
		unsigned long long llvm_units_live;
		unsigned long long llvm_units_peak;

		// Carve size over the first requested unit size; 0 before any carve.
		unsigned long long llvm_units_capacity;

		// Carves that had to wait for a unit; non-zero means the title needs more than pool 3 holds.
		unsigned long long llvm_carve_waits;

		// The unit size the first carve asked for. `0` before any carve.
		unsigned long long llvm_unit_bytes;

		// 1 only when the mechanism is active AND every read-back agreed.
		int                ok;
	};

	// Fills out for one pool, or zeroes it with ? protections when inactive; pool 3 reads at its lowest live unit, ok 0 when none is live.
	void jit_dual_map_report_pool(jit_pool pool, jit_dual_map_report* out) noexcept;

	// True when the jetsam limit is under 3 GiB; the prepare sizes and Vulkan reserves shrink for it.
	bool low_memory_device() noexcept;

	// Called once a second with pool 2's bump counter; stores this process's pool peaks when one grows.
	void jit_dual_map_note_peaks(unsigned long long pool2_top, bool game_running) noexcept;

	// For the macOS test leg only: lowers pool 3 to mib MiB in whole granules.
	void jit_dual_map_cap_pool3_for_leg(unsigned mib);

	// 0, 2 or 3: the pool whose second StikDebug pass this session offers; reading it clears it.
	int jit_second_pass_take_offer() noexcept;

	// After StikDebug attaches: waits for P_TRACED, prepares above the caps, detaches and raises them; false when no pass can start.
	bool jit_second_pass_start(int trigger, bool opened) noexcept;

	// Called once a second: latches the offer and hands back a finished pass's log line once.
	bool jit_second_pass_tick(bool game_running, char* out, unsigned long long size) noexcept;
}
