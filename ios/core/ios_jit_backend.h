// SPDX-License-Identifier: GPL-2.0-only
// Picks the JIT mapping mechanism and the reservation protection; the dual map in ios_jit_dual_map.h implements write_alias.

#pragma once

namespace utils
{
	// The mechanisms in the order the selector tries them.
	enum class jit_backend_kind
	{
		// MAP_JIT plus the write-protect toggle: macOS and upstream; a device refuses MAP_JIT with EPERM.
		apple_map_jit,

		// Executable block plus a writable alias at a fixed offset, built by jit_dual_map_carve(); the device's rung.
		write_alias,

		// A plain executable mapping, as on Linux, Windows and the BSDs.
		plain_exec,
	};

	// Chosen once on first call; a function-local static because vm.cpp reserves from static initialisers in another TU.
	jit_backend_kind jit_backend();

	// The rung's name, for logs and for callers that report it. Never nullptr.
	const char* jit_backend_name(jit_backend_kind kind);

	// The selected rung's name; the first call runs the selection, its probe and its log line.
	const char* jit_backend_name();

	// The PROT_* bits a reservation is created with; computed in the .cpp to keep <sys/mman.h> out of this header.
	int jit_backend_reservation_prot(bool is_memory_mapping, bool can_be_jit);

	// The mmap flags for the same region; a memory-mapping region never gets the JIT flag, and the .cpp asserts it.
	int jit_backend_reservation_flags(bool is_memory_mapping, bool can_be_jit);
}
