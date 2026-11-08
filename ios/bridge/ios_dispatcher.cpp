// SPDX-License-Identifier: GPL-2.0-only
// The main-thread dispatcher: plain C++, since blocks and libdispatch are C; compiled on both hosts, installed on iOS.

#include "ios_dispatcher.h"

#include "host_services.h"

#include "util/logs.hpp"

#include <dispatch/dispatch.h>

#include <chrono>
#include <cstdint>
#include <exception>
#include <functional>
#include <memory>
#include <utility>

// -fno-exceptions reaches rpcs3_emu but not this library; this stops the build if that changes and kills the catch below.
#if !defined(__cpp_exceptions)
#	error "ios_dispatcher.cpp must be compiled WITH exceptions. The per-callable catch below is the whole point of this file; -fno-exceptions makes it dead code and turns a contained throw into a process termination."
#endif

LOG_CHANNEL(ios_dispatcher_log, "IOSDISP");

namespace armsx3::bridge::ios_dispatcher
{
	atomic_t<std::uint64_t> g_posted{0};
	atomic_t<std::uint64_t> g_drained{0};
	atomic_t<std::uint64_t> g_max_queue_depth{0};
	atomic_t<std::uint64_t> g_blocking_inline{0};
	atomic_t<std::uint64_t> g_max_blocking_latency_ns{0};
	atomic_t<std::uint64_t> g_contained{0};

	namespace
	{
		// Any thread can call this, so it cannot be a read-then-write.
		void raise_max(atomic_t<std::uint64_t>& target, std::uint64_t candidate)
		{
			std::uint64_t seen = target.load();

			while (seen < candidate && !target.compare_exchange(seen, candidate))
			{
				// compare_exchange refreshed `seen`; re-test the condition.
			}
		}
	}

	// Relaxed loads only: a single-call read, not a consistent snapshot.
	observation observe()
	{
		observation out;
		out.posted = g_posted.load();
		out.drained = g_drained.load();
		out.max_queue_depth = g_max_queue_depth.load();
		out.blocking_inline = g_blocking_inline.load();
		out.max_blocking_latency_ns = g_max_blocking_latency_ns.load();
		out.contained = g_contained.load();
		return out;
	}

	std::uint64_t read_drained()
	{
		return g_drained.load();
	}

	std::uint64_t read_max_queue_depth()
	{
		return g_max_queue_depth.load();
	}

	std::uint64_t read_max_blocking_latency_ns()
	{
		return g_max_blocking_latency_ns.load();
	}

	void install(host_services& services)
	{
		// Assigned, not wrapped: the member's type is the emu_callbacks field's own.
		services.call_from_main_thread = [](std::function<void()> cb, atomic_t<std::uint32_t>* wake_up)
		{
			// The caller is blocking, so run inline whatever the thread (posting deadlocks a main-thread caller), then notify its wait.
			if (wake_up)
			{
				const auto started = std::chrono::steady_clock::now();

				if (cb)
				{
					cb();
				}

				const auto elapsed = std::chrono::steady_clock::now() - started;
				raise_max(g_max_blocking_latency_ns, static_cast<std::uint64_t>(
					std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count()));
				g_blocking_inline++;

				*wake_up = true;
				wake_up->notify_one();
				return;
			}

			// Moved to the heap and adopted inside the block, so the callable cannot dangle across the hop.
			auto* boxed = new std::function<void()>(std::move(cb));

			const std::uint64_t posted = g_posted.fetch_add(1) + 1;
			raise_max(g_max_queue_depth, posted - g_drained.load());

			dispatch_async(dispatch_get_main_queue(), ^{
				std::unique_ptr<std::function<void()>> owned(boxed);

				g_drained++;

				// The catch must stay the callable's immediate invoker; core throws reach it only because System.cpp is built with -fexceptions.
				try
				{
					if (*owned)
					{
						(*owned)();
					}
				}
				catch (const std::exception& e)
				{
					g_contained++;
					ios_dispatcher_log.error("a callable posted to the main queue threw: %s", e.what());
				}
				catch (...)
				{
					g_contained++;
					ios_dispatcher_log.error("a callable posted to the main queue threw a non-std exception");
				}
			});
		};
	}
}
