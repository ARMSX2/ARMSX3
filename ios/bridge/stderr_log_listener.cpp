// SPDX-License-Identifier: GPL-2.0-only
// Builds each line itself and hands it to the stderr witness, because a blocked stdio write stalled every logger.
#include "stderr_log_listener.h"

#include "ios/core/ios_stderr_witness.h"
#include "util/logs.hpp"
#include "util/atomic.hpp"

#include <string>
#include <string_view>

namespace armsx3::bridge
{
	namespace
	{
		// Upstream's file-listener shape minus the timestamp, because the console relay already stamps lines.
		struct stderr_listener final : logs::listener
		{
			void log(u64 /*stamp*/, const logs::message& msg, std::string_view prefix,
				std::string_view text) override
			{
				const char* mark = "?";

				switch (static_cast<logs::level>(msg))
				{
				case logs::level::always:  mark = "."; break;
				case logs::level::fatal:   mark = "F"; break;
				case logs::level::error:   mark = "E"; break;
				case logs::level::todo:    mark = "U"; break;
				case logs::level::success: mark = "S"; break;
				case logs::level::warning: mark = "W"; break;
				case logs::level::notice:  mark = "!"; break;
				case logs::level::trace:   mark = "T"; break;
				}

				// The old fprintf's bytes, built whole and untruncated.
				const std::string_view name = msg->name ? std::string_view(msg->name) : std::string_view("(null)");
				std::string line;
				line.reserve(10 + 1 + 1 + prefix.size() + 1 + name.size() + 2 + text.size() + 1);
				line.append("@@RPCS3@@ ");
				line.append(mark);
				line.push_back(' ');
				if (!prefix.empty())
				{
					line.append(prefix.data(), prefix.size());
					line.push_back(' ');
				}
				if (!name.empty())
					line.append(name.data(), name.size());
				line.append(": ");
				if (!text.empty())
					line.append(text.data(), text.size());
				line.push_back('\n');

				utils::stderr_witness(line.data(), line.size());
			}

			// No sync() override: there is no stdio buffer left to flush.
		};

		atomic_t<bool> s_listener_installed{false};
	}

	void install_stderr_log_listener()
	{
		if (s_listener_installed.exchange(true))
		{
			return;
		}

		// fd 2 first, so no writer of it can block from here on.
		utils::stderr_witness_install_drain();

		static stderr_listener s_listener;
		logs::listener::add(&s_listener);
	}
}
