// SPDX-License-Identifier: GPL-2.0-only
#pragma once

#include "util/types.hpp"

// Where the reservation info of a 128-byte guest line lives in vm::g_reservations, in C++ and in SPU LLVM IR.

namespace vm
{
#if defined(__APPLE__) && defined(__aarch64__)
	// 128-byte slots would keep adjacent guest lines' stamps on separate Apple cache lines; flipping it re-keys the SPU cache.
	inline constexpr bool rsrv_stride_128 = false;
#else
	inline constexpr bool rsrv_stride_128 = false;
#endif

	inline constexpr u32 rsrv_slot_shift = rsrv_stride_128 ? 0 : 1;
	inline constexpr u32 rsrv_slot_size = 128 >> rsrv_slot_shift;
	inline constexpr u32 rsrv_table_size = 65536 / 128 * rsrv_slot_size;

	// T is u32 in C++ and value_t<u32> in SPU LLVM IR (the expression templates take the same operators).
	template <typename T>
	constexpr auto reservation_slot_offset(T addr)
	{
		if constexpr (rsrv_slot_shift == 0)
		{
			return addr & 0xff80;
		}
		else
		{
			return (addr & 0xff80) >> rsrv_slot_shift;
		}
	}

	static_assert(rsrv_slot_size == 64 || rsrv_slot_size == 128);
	static_assert(rsrv_slot_size >= 32, "a slot holds the stamp at +0 and do_dma_transfer's byte-lock bits at +16..+31");
	static_assert(reservation_slot_offset(0x80u) == rsrv_slot_size);
	static_assert(reservation_slot_offset(0xffffu) + rsrv_slot_size == rsrv_table_size);
	static_assert(reservation_slot_offset(0x10080u) == reservation_slot_offset(0x80u));
	static_assert(rsrv_stride_128 || reservation_slot_offset(0x12345u) == (0x12345u & 0xff80) / 2);
}
