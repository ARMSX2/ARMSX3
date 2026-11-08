// SPDX-License-Identifier: GPL-2.0-only
// Shared-memory backends for utils::shm: the iOS sandbox refuses shm_open, so each rung is probed for real aliasing first.

#pragma once

#include <string>

namespace utils
{
	// In ladder order; mach_memory_entry is named but never selected, since it yields a Mach port rather than a descriptor.
	enum class shm_backend_kind
	{
		posix_shm,
		container_file,
		mach_memory_entry,
	};

	// Chosen once on first call, in a function-local static so shm objects built at static init see it ready.
	shm_backend_kind shm_backend();

	// The rung's name, for logs and for callers that report it. Never nullptr.
	const char* shm_backend_name(shm_backend_kind kind);

	// Never a cache directory: iOS purges Library/Caches while the app runs, which would corrupt guest memory.
	const std::string& shm_container_dir();
}
