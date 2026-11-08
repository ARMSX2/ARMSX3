// SPDX-License-Identifier: GPL-2.0-only
// Fills call_from_main_thread on iOS by posting to the OS main queue; an owned queue would have nobody to drain it.
#pragma once

// atomic_t cannot be forward-declared: a class template's default argument may be given by only one declaration.
#include "util/atomic.hpp"

#include <cstdint>

namespace armsx3::bridge
{
	struct host_services;

	namespace ios_dispatcher
	{
		// Counters and maxima rather than a log, so the post path can update them without becoming what it measures.

		extern atomic_t<std::uint64_t> g_posted;

		// `g_posted - g_drained` is the outstanding depth.
		extern atomic_t<std::uint64_t> g_drained;

		// The largest outstanding depth ever observed at post time.
		extern atomic_t<std::uint64_t> g_max_queue_depth;

		// Counted apart from g_drained, because a blocking call is not a drain.
		extern atomic_t<std::uint64_t> g_blocking_inline;

		// In nanoseconds: whether the blocking branch is holding an emulator thread up.
		extern atomic_t<std::uint64_t> g_max_blocking_latency_ns;

		// For callables that come through the core's own wrapper, this moves only because System.cpp is built with -fexceptions.
		extern atomic_t<std::uint64_t> g_contained;

		// A single-call read, not a consistent snapshot: six relaxed loads from one place, with no lock.
		struct observation
		{
			std::uint64_t posted;
			std::uint64_t drained;
			std::uint64_t max_queue_depth;
			std::uint64_t blocking_inline;
			std::uint64_t max_blocking_latency_ns;
			std::uint64_t contained;
		};

		// The one a record writer uses.
		observation observe();

		// The same counters observe() reads.
		std::uint64_t read_drained();
		std::uint64_t read_max_queue_depth();
		std::uint64_t read_max_blocking_latency_ns();

		// Assigns the body to the member and constructs nothing around it.
		void install(host_services& services);
	}
}
