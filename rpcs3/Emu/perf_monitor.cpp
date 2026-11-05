#include "stdafx.h"
#include "perf_monitor.hpp"

#include "Emu/System.h"
#include "Emu/Cell/timers.hpp"
#include "util/cpu_stats.hpp"
#include "util/sysinfo.hpp"
#include "Utilities/Thread.h"

LOG_CHANNEL(perf_log, "PERF");

#ifdef ANDROID
#include "Emu/system_config.h"

#include <dirent.h>
#include <fcntl.h>
#include <sched.h>
#include <unistd.h>
#include <map>
#include <set>

// The emulator's busy threads get every core back whenever something outside narrows them.
//
// A phone's game mode can pin a game's busy threads to the cores it thinks matter. OnePlus Game
// Space puts every busy thread of ours on cpu2-4 and cpu7 of a Snapdragon 8 Gen 3 (and the UI
// threads on cpu2-6), leaving cpu5-6 to everything else. Tuned for a game with two or three busy
// threads, it packs six SPU threads, the main PPU and the RSX thread onto four cores: they waited
// for a core 27-46% of the time, against 6-18% with Game Space off, where they also use cpu5-6, and
// LittleBigPlanet 2 ran at 18 fps instead of 30. Pinning them elsewhere ourselves did not hold: it
// pins them again within seconds. So every half second, a PPU, SPU or RSX thread allowed fewer
// than all the cores is given all of them again, and the scheduler places it as it does with Game
// Space off.
//
// Only under Thread Scheduler Mode "OS", the default, which leaves these threads unpinned: the
// other modes pin them on purpose (thread_ctrl::get_affinity_mask). Helper threads that ARMSX3 pins
// itself (RSX.W*, SPU Worker, PPU Loader) do not match the names below.
namespace
{
	struct armsx3_affinity_guard
	{
		std::map<u32, std::string> ours; // tid -> name, for the threads it looks after
		std::set<u32> others;            // tids already seen not to be one of them
		u32 restored = 0;
		std::string last;
		u64 last_report = 0;
	};

	armsx3_affinity_guard s_affinity_guard;

	std::string armsx3_read_comm(const std::string& path)
	{
		std::string out(32, '\0');
		const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);

		if (fd < 0)
		{
			return {};
		}

		const ssize_t n = ::read(fd, out.data(), out.size());
		::close(fd);
		out.resize(n > 0 ? static_cast<usz>(n) : 0);

		while (!out.empty() && (out.back() == '\n' || out.back() == ' '))
		{
			out.pop_back();
		}

		return out;
	}

	std::string armsx3_cpu_list(const cpu_set_t& set)
	{
		std::string out;

		for (int cpu = 0; cpu < CPU_SETSIZE; cpu++)
		{
			if (!CPU_ISSET(cpu, &set))
			{
				continue;
			}

			int last = cpu;

			while (last + 1 < CPU_SETSIZE && CPU_ISSET(last + 1, &set))
			{
				last++;
			}

			fmt::append(out, "%s%d", out.empty() ? "" : ",", cpu);

			if (last > cpu)
			{
				fmt::append(out, "-%d", last);
			}

			cpu = last;
		}

		return out;
	}

	void armsx3_restore_affinity(u64 now_us)
	{
		if (g_cfg.core.thread_scheduler != thread_scheduler_mode::os)
		{
			return;
		}

		// Only in the foreground. A background cpuset narrows every thread on purpose, and Game
		// Space leaves the app in top-app while it pins the threads.
		if (!armsx3_read_comm("/proc/self/cpuset").ends_with("top-app"))
		{
			return;
		}

		static const cpu_set_t s_all = []
		{
			cpu_set_t set;
			CPU_ZERO(&set);

			const long count = ::sysconf(_SC_NPROCESSORS_CONF);

			for (long cpu = 0; cpu < count && cpu < CPU_SETSIZE; cpu++)
			{
				CPU_SET(static_cast<int>(cpu), &set);
			}

			return set;
		}();

		auto& g = s_affinity_guard;
		DIR* d = ::opendir("/proc/self/task");

		if (!d)
		{
			return;
		}

		std::set<u32> alive;

		while (const dirent* e = ::readdir(d))
		{
			if (e->d_name[0] == '.')
			{
				continue;
			}

			const u32 tid = static_cast<u32>(std::strtoul(e->d_name, nullptr, 10));
			alive.insert(tid);

			if (g.others.contains(tid))
			{
				continue;
			}

			auto found = g.ours.find(tid);

			if (found == g.ours.end())
			{
				std::string comm = armsx3_read_comm(fmt::format("/proc/self/task/%u/comm", tid));
				const std::string_view name = comm;

				if (!name.starts_with("PPU[") && !name.starts_with("SPU[") && name != "rsx::thread")
				{
					g.others.insert(tid);
					continue;
				}

				found = g.ours.emplace(tid, std::move(comm)).first;
			}

			cpu_set_t have;
			CPU_ZERO(&have);

			if (::sched_getaffinity(static_cast<pid_t>(tid), sizeof(have), &have) != 0 || CPU_EQUAL(&have, &s_all))
			{
				continue;
			}

			if (::sched_setaffinity(static_cast<pid_t>(tid), sizeof(s_all), &s_all) == 0)
			{
				g.restored++;
				g.last = fmt::format("%s was on %s", found->second, armsx3_cpu_list(have));
			}
		}

		::closedir(d);

		std::erase_if(g.ours, [&](const auto& entry) { return !alive.contains(entry.first); });
		std::erase_if(g.others, [&](u32 tid) { return !alive.contains(tid); });

		if (g.restored && now_us - g.last_report >= 10'000'000)
		{
			perf_log.notice("Affinity: gave %u narrowed thread(s) back every core since the last report (last: %s)", g.restored, g.last);
			g.restored = 0;
			g.last_report = now_us;
		}
	}
}
#endif

void perf_monitor::operator()()
{
	constexpr u64 update_interval_us = 500000; // Update every half second
	constexpr u64 log_interval_us_max = 10000000; // Log at minimum every 10 seconds
	constexpr u64 log_interval_us_min = 500000;  // Log at maximum every half a second (catching possible memory leak)
	constexpr u64 log_mem_increase = 50 * (1024 * 1024);  // Log when memory usage increased by this amount

	u64 elapsed_us = 0;

	utils::cpu_stats stats;
	stats.init_cpu_query();

	u32 logged_pause = 0;
	u64 last_pause_time = umax;
	u64 max_memory_usage = 0;

	std::vector<double> per_core_usage;
	std::string msg;

	for (u64 sleep_until = get_system_time();;)
	{
		thread_ctrl::wait_until(&sleep_until, update_interval_us);
		elapsed_us += update_interval_us;

#ifdef ANDROID
		// See armsx3_restore_affinity.
		armsx3_restore_affinity(get_system_time());
#endif

		double total_usage = 0.0;

		stats.get_per_core_usage(per_core_usage, total_usage);

		// OUR resident set, not the machine's. This used to report
		// utils::get_memory_usage().second -- MemTotal minus MemAvailable from /proc/meminfo,
		// which counts every process on the device plus page cache -- under the label "RAM
		// Usage", directly beside this emulator's own CPU figures. On a 7.4 GB phone it read
		// ~5.6 GB while the emulator itself held ~3.3 GB, so three separate investigations into
		// "the emulator is using 5.6 GB" were chasing the device's number. The process RSS is
		// also the figure Android's low-memory killer actually decides on.
		const u64 current_mem_use = utils::get_process_memory_usage();
		const u64 mem_use_increase = current_mem_use >= max_memory_usage ? current_mem_use - max_memory_usage : 0;

		const auto [system_total, system_used] = utils::get_memory_usage();

		const u64 log_interval = (mem_use_increase >= log_mem_increase ? log_interval_us_min : log_interval_us_max);

		if (elapsed_us >= log_interval || thread_ctrl::state() == thread_state::aborting)
		{
			max_memory_usage = std::max<u64>(current_mem_use, max_memory_usage);
			elapsed_us = 0;

			const bool is_paused = Emu.IsPaused();
			const u64 pause_time = Emu.GetPauseTime();

			if (!is_paused || last_pause_time != pause_time)
			{
				// Resumed or not paused since last check
				logged_pause = 0;
				last_pause_time = pause_time;
			}

			if (is_paused)
			{
				if (logged_pause >= 2)
				{
					// Let's not spam the log when emulation is paused
					// But still emit the message two times so even paused state can be debugged and inspected
					continue;
				}

				logged_pause++;
			}

			msg.clear();
			fmt::append(msg, "CPU Usage: Total: %.1f%%", total_usage);

			if (!per_core_usage.empty())
			{
				fmt::append(msg, ", Cores:");
			}

			for (usz i = 0; i < per_core_usage.size(); i++)
			{
				fmt::append(msg, "%s %.1f%%", i > 0 ? "," : "", per_core_usage[i]);
			}

			if (max_memory_usage)
			{
				// Both numbers, each named for what it is. The headroom is what a report about
				// being killed needs: our own total says nothing about how close the device is.
				fmt::append(msg, ", Process RSS: %dMB (Peak: %dMB)",
					current_mem_use / (1024 * 1024), max_memory_usage / (1024 * 1024));
			}

			if (system_total)
			{
				fmt::append(msg, ", System: %dMB/%dMB used (%dMB free)",
					system_used / (1024 * 1024), system_total / (1024 * 1024),
					(system_total - system_used) / (1024 * 1024));
			}

			perf_log.notice("%s", msg);

			if (thread_ctrl::state() == thread_state::aborting)
			{
				// Log once before terminating
				break;
			}
		}
	}
}

perf_monitor::~perf_monitor()
{
}
