// SPDX-License-Identifier: GPL-2.0-only
// Depends on no emulator header, and must compile on Windows too because the root CMake adds it everywhere.

#include "ios_jit_backend.h"
#include "ios_stderr_witness.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

// Three arms: Apple, other POSIX, and Windows, which has no <sys/mman.h>.
#if defined(__APPLE__)
#include <TargetConditionals.h>
#include <errno.h>
#include <sys/mman.h>
#include <unistd.h>
#elif !defined(_WIN32)
#include <sys/mman.h>
#endif

namespace utils
{
	const char* jit_backend_name(jit_backend_kind kind)
	{
		switch (kind)
		{
		case jit_backend_kind::apple_map_jit: return "apple_map_jit";
		case jit_backend_kind::write_alias: return "write_alias";
		case jit_backend_kind::plain_exec: return "plain_exec";
		}

		return "unknown";
	}

	// Defined here so the selection and its name live in one translation unit.
	const char* jit_backend_name()
	{
		return jit_backend_name(jit_backend());
	}
}

namespace
{
	// A memory-mapping region is replaced by shm::map's MAP_FIXED mappings, which the JIT flag forbids, so abort at the cause.
	void assert_no_jit_flag_on_a_mapping_region(bool is_memory_mapping, int flags)
	{
		if (!is_memory_mapping || flags == 0)
		{
			return;
		}

		utils::stderr_witness_printf("ARMSX3: a memory-mapping reservation was about to be created with "
			"the Apple JIT map flag (0x%x). That region is replaced by a file-backed MAP_FIXED "
			"mapping via shm::map, which the flag forbids.\n", flags);
		std::abort();
	}
}

#if !defined(__APPLE__)

// Other platforms answer with upstream's behaviour, so vm_native.cpp links everywhere; Windows has no PROT_* at all.
namespace utils
{
	jit_backend_kind jit_backend()
	{
		return jit_backend_kind::plain_exec;
	}

	int jit_backend_reservation_prot(bool, bool)
	{
#if defined(_WIN32)
		// vm_native.cpp's Windows arm uses VirtualAlloc and never reads this, so 0 just means not applicable.
		return 0;
#else
		return PROT_NONE;
#endif
	}

	int jit_backend_reservation_flags(bool, bool)
	{
		return 0;
	}
}

#else

namespace
{
	// Maps and unmaps one MAP_JIT page: success means macOS or the simulator, EPERM a real device.
	bool map_jit_is_granted()
	{
		const long page = ::sysconf(_SC_PAGESIZE);
		const std::size_t size = page > 0 ? static_cast<std::size_t>(page) : 0x4000;

		void* ptr = ::mmap(nullptr, size, PROT_READ | PROT_WRITE,
			MAP_ANON | MAP_PRIVATE | MAP_JIT, -1, 0);

		if (ptr == MAP_FAILED)
		{
			const int err = errno;
			utils::stderr_witness_printf("ARMSX3: mmap(MAP_JIT) refused: %s (errno %d). On a real iOS "
				"device this is the EXPECTED answer -- it was refused on every measured run, "
				"granted and ungranted alike. A success here would have meant the simulator.\n",
				std::strerror(err), err);
			return false;
		}

		::munmap(ptr, size);
		return true;
	}
}

namespace utils
{
	jit_backend_kind jit_backend()
	{
		static const jit_backend_kind s_kind = []() -> jit_backend_kind {
			const bool granted = map_jit_is_granted();

#if TARGET_OS_IPHONE
			// Granted on iOS means the simulator, which keeps MAP_JIT; a device takes write_alias.
			const jit_backend_kind kind = granted
				? jit_backend_kind::apple_map_jit
				: jit_backend_kind::write_alias;
#else
			// macOS is pinned to the device's write_alias too, so the macOS build exercises the same JIT path as the phone.
			const jit_backend_kind kind = jit_backend_kind::write_alias;

			if (!granted)
			{
				utils::stderr_witness_printf("ARMSX3: mmap(MAP_JIT) was refused on a non-iOS Apple host. "
					"That is not what selects the rung here: this platform is pinned to "
					"'write_alias' so that the host leg runs the device's mechanism.\n");
			}
#endif

			utils::stderr_witness_printf("ARMSX3: jit backend '%s' selected (MAP_JIT %s).\n",
				jit_backend_name(kind), granted ? "granted" : "refused");
			return kind;
		}();

		return s_kind;
	}

	int jit_backend_reservation_prot(bool is_memory_mapping, bool can_be_jit)
	{
		// Reserve unreadable, so a guest pointer running off mapped memory faults where it happened.
		if (is_memory_mapping || !can_be_jit)
		{
#if defined(__aarch64__) && !TARGET_OS_IPHONE
			// macOS ARM64 reserves read-write like upstream, which removed a guest null-store crash there; iOS stays unreadable.
			return PROT_READ | PROT_WRITE;
#else
			return PROT_NONE;
#endif
		}

		switch (jit_backend())
		{
		case jit_backend_kind::apple_map_jit:
#if defined(__aarch64__)
			// Upstream's protection for a JIT-capable reservation on Apple ARM64, unchanged.
			return PROT_READ | PROT_WRITE;
#else
			// Non-ARM64 Apple already reserves unreadable, as upstream does.
			return PROT_NONE;
#endif

		case jit_backend_kind::write_alias:
			// A general reservation stays unreadable; the dual-map block is mapped read-execute in its own file.
			return PROT_NONE;

		case jit_backend_kind::plain_exec:
			return PROT_NONE;
		}

		return PROT_NONE;
	}

	int jit_backend_reservation_flags(bool is_memory_mapping, bool can_be_jit)
	{
		int flags = 0;

		// Upstream's predicate, moved here: only non-mapping JIT regions get MAP_JIT.
		if (!is_memory_mapping && can_be_jit && jit_backend() == jit_backend_kind::apple_map_jit)
		{
			flags = MAP_JIT;
		}

		assert_no_jit_flag_on_a_mapping_region(is_memory_mapping, flags);
		return flags;
	}
}

#endif
