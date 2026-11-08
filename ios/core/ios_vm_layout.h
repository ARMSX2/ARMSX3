// SPDX-License-Identifier: GPL-2.0-only
// The vm reservation sizes as a value, so vm.cpp reserves the Apple layout without forking its initialisers.

#pragma once

#include <cstdint>

// One size per vm.cpp reservation, in bytes; g_sudo_addr and g_free_addr are offsets, not reservations.
struct vm_layout
{
	// Guest memory; g_sudo_addr is carved from it, so its placement must be exact.
	std::uint64_t base_size;

	// Auxiliary virtual memory for executable areas.
	std::uint64_t exec_size;

	// Zero omits the reservation: g_hook_addr stays null and vm.cpp skips its mapping and unmapping.
	std::uint64_t hook_size;

	// Never zero: g_stat_addr is dereferenced behind debug flags, and the selector asserts it.
	std::uint64_t stat_size;
};

// A function-local static, so vm.cpp's globals in another translation unit never read it uninitialised.
const vm_layout& vm_layout_for_this_platform();
