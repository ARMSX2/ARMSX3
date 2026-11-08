// SPDX-License-Identifier: GPL-2.0-only
// Save-state progress parked by the Stop Watchdog's callback, which must not block; the core's message text is never kept.
#pragma once

#include "util/atomic.hpp"
#include "util/init_mutex.hpp"
#include "util/serialization.hpp"
#include "util/shared_ptr.hpp"

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

namespace armsx3::bridge
{
	struct save_progress_handles
	{
		std::shared_ptr<atomic_t<bool>> closed;
		stx::shared_ptr<utils::serial> ar;
		stx::atomic_ptr<std::string>* where = nullptr;
		std::shared_ptr<void> init;
	};

	inline std::mutex& save_progress_mutex()
	{
		static std::mutex mutex;
		return mutex;
	}

	inline save_progress_handles& parked_save_progress()
	{
		static save_progress_handles handles;
		return handles;
	}

	inline void park_save_progress(std::shared_ptr<atomic_t<bool>> closed, stx::shared_ptr<utils::serial> ar,
		stx::atomic_ptr<std::string>* where, std::shared_ptr<void> init)
	{
		std::lock_guard lock(save_progress_mutex());
		parked_save_progress() = {std::move(closed), std::move(ar), where, std::move(init)};
	}

	// 0 with `bytes` and `finalizing` written, or -9 when nothing is parked or the save's termination is done.
	inline int read_save_progress(std::uint64_t& bytes, bool& finalizing)
	{
		save_progress_handles handles;
		{
			std::lock_guard lock(save_progress_mutex());
			handles = parked_save_progress();
		}

		if (!handles.init)
		{
			return -9;
		}

		const auto mtx = static_cast<stx::init_mutex*>(handles.init.get());
		while (true)
		{
			auto init = mtx->access();
			if (!init)
			{
				// Wait for the core's own reset to finish, as desktop does.
				auto fake_reset = mtx->reset();
				if (!fake_reset)
				{
					// Termination done: the serial and the message may be gone, so they are never read again.
					std::lock_guard lock(save_progress_mutex());
					if (parked_save_progress().init == handles.init)
					{
						parked_save_progress() = {};
					}
					return -9;
				}

				fake_reset.set_init();
				continue;
			}

			const auto message = handles.where->load();
			finalizing = message && message->find("Finalizing") != std::string::npos;
			bytes = handles.ar->is_writing() ? handles.ar->get_size() : 0;
			return 0;
		}
	}
}
