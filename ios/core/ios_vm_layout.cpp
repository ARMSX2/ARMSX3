// SPDX-License-Identifier: GPL-2.0-only
// The vm layout table and its checks; vm.cpp does the reserving.

#include "ios_vm_layout.h"
#include "ios_stderr_witness.h"

#include <cstdio>
#include <cstdlib>

namespace
{
	// memory_reserve_4GiB steps in 4 GiB, so a size off the step leaves a hole the next reservation cannot use.
	constexpr std::uint64_t k_step = 0x100000000ull; // 4 GiB

	// The highest address a device has been seen to map; a layout reaching it asks for more than iOS grants.
	constexpr std::uint64_t k_ceiling = 0x7000000000ull; // 448 GiB

	constexpr int reservation_count(const vm_layout& l)
	{
		return (l.base_size ? 1 : 0) + (l.exec_size ? 1 : 0) + (l.hook_size ? 1 : 0) + (l.stat_size ? 1 : 0);
	}

	constexpr std::uint64_t total_with_step_allowance(const vm_layout& l)
	{
		return l.base_size + l.exec_size + l.hook_size + l.stat_size
			+ k_step * static_cast<std::uint64_t>(reservation_count(l));
	}

	// constexpr so each variant below is checked by a static_assert, and callable at run time for the selector's check.
	constexpr bool layout_is_valid(const vm_layout& l)
	{
		return l.base_size != 0
			&& l.exec_size != 0
			// g_stat_addr is dereferenced behind default-off debug flags, so a missing reservation would only fail once one is on.
			&& l.stat_size != 0
			&& (l.base_size % k_step) == 0
			&& (l.exec_size % k_step) == 0
			&& (l.hook_size % k_step) == 0
			&& (l.stat_size % k_step) == 0
			&& total_with_step_allowance(l) < k_ceiling;
	}

	// Upstream's sizes, read off vm.cpp: 56 GiB in four reservations.
	constexpr vm_layout k_desktop = {
		8589934592ull,  // base   8 GiB   0x200000000
		12884901888ull, // exec  12 GiB   0x300000000
		34359738368ull, // hook  32 GiB   0x800000000
		4294967296ull,  // stat   4 GiB   0x100000000
	};

	// Upstream's sizes with the hook dropped: 24 GiB, which on iOS still needs the increased-memory-limit entitlement.
	constexpr vm_layout k_adopted = {
		8589934592ull,  // base   8 GiB   0x200000000   unchanged from upstream
		12884901888ull, // exec  12 GiB   0x300000000   unchanged from upstream
		0ull,           // hook   omitted, not zero-sized
		4294967296ull,  // stat   4 GiB   0x100000000   unchanged from upstream
	};

	static_assert(layout_is_valid(k_desktop), "the desktop layout violates its own invariants");
	static_assert(layout_is_valid(k_adopted), "the adopted layout violates its own invariants");
}

const vm_layout& vm_layout_for_this_platform()
{
	// A function-local static with the platform choice inside and an assert on the result, like utils::get_page_size().
	static const vm_layout s_layout = []() -> vm_layout {
#if defined(__APPLE__) && defined(__aarch64__)
		// All Apple ARM64, Mac included, so the macOS build exercises the phone's layout.
		return k_adopted;
#else
		return k_desktop;
#endif
	}();

	// Checked at run time for a variant added without a static_assert; abort, because this runs before main().
	if (!layout_is_valid(s_layout))
	{
		utils::stderr_witness_printf(
			"ARMSX3: vm_layout_for_this_platform() produced an invalid layout: "
			"base=%llu exec=%llu hook=%llu stat=%llu. Every non-zero size must be a "
			"multiple of 0x%llx, stat_size must be non-zero, and the total plus its step "
			"allowance must sit beneath 0x%llx.\n",
			static_cast<unsigned long long>(s_layout.base_size),
			static_cast<unsigned long long>(s_layout.exec_size),
			static_cast<unsigned long long>(s_layout.hook_size),
			static_cast<unsigned long long>(s_layout.stat_size),
			static_cast<unsigned long long>(k_step),
			static_cast<unsigned long long>(k_ceiling));
		std::abort();
	}

	return s_layout;
}
