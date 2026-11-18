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

#ifdef __ANDROID__
#include <sys/resource.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace utils
{
	// rpcs3/util/vm_native.cpp: the process's memory mappings against the kernel's cap
	std::string memory_map_census();
}

namespace
{
	// ARMSX3: Mesa's shader disk cache (on since d3a5395ad) writes each shader the driver compiles
	// from a queue of its own, whose threads ("disk$0" and up) Mesa puts on SCHED_IDLE: they only
	// run on a core no other thread wants. Until a shader is written, the queue holds a serialized
	// copy of it, and a full queue grows instead of waiting.
	//
	// ARMSX3.old-8.log reads as those copies never being written while The Last of Us kept every
	// core busy. Once the tester reached areas the cache had never seen (50 minutes in), each newly
	// compiled program left about 16 mappings behind: about 8 blocks over 64 KB, each its own
	// mapping under Scudo, plus their neighbours. Before that, with the shaders already on disk and
	// so nothing to write, programs left about 2, though the session's pipeline cache started empty.
	// 3 h 27 m in, the table hit Android's 65,530-mapping cap and the RSX thread's mprotect failed
	// with 11 GB of RAM free.
	//
	// So each census moves the writers to the normal policy at nice 10, below the emulator's own
	// threads but no longer starved, and reports them with the CPU time they have used: that says
	// whether they run, and the mapping count next to it whether it was the cause.
	std::string mesa_disk_cache_writers()
	{
		static std::set<int> s_moved;
		static bool s_failure_reported = false;

		u32 count = 0;
		u32 moved_now = 0;
		u64 cpu_ticks = 0;

		DIR* dir = ::opendir("/proc/self/task");

		if (!dir)
		{
			return {};
		}

		while (const dirent* ent = ::readdir(dir))
		{
			if (ent->d_name[0] < '0' || ent->d_name[0] > '9')
			{
				continue;
			}

			const int tid = std::atoi(ent->d_name);
			char path[64];
			char comm[32]{};

			std::snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);

			if (const int fd = ::open(path, O_RDONLY | O_CLOEXEC); fd >= 0)
			{
				const ssize_t n = ::read(fd, comm, sizeof(comm) - 1);
				::close(fd);
				comm[n > 0 ? n : 0] = 0;
			}

			if (std::strncmp(comm, "disk$", 5) != 0)
			{
				// Any other thread left on SCHED_IDLE is named once, in case a driver names its queue differently
				static std::set<std::string> s_idle_named;

				if (s_idle_named.size() < 16 && ::sched_getscheduler(tid) == SCHED_IDLE)
				{
					if (char* nl = std::strchr(comm, '\n'))
					{
						*nl = 0;
					}

					if (s_idle_named.emplace(comm).second)
					{
						perf_log.notice("Thread %d (%s) runs on SCHED_IDLE", tid, comm);
					}
				}

				continue;
			}

			count++;

			if (::sched_getscheduler(tid) == SCHED_IDLE)
			{
				sched_param param{};

				if (::sched_setscheduler(tid, SCHED_OTHER, &param) == 0)
				{
					::setpriority(PRIO_PROCESS, tid, 10);
					s_moved.insert(tid);
					moved_now++;
				}
				else if (!s_failure_reported)
				{
					s_failure_reported = true;
					perf_log.warning("Mesa disk cache writer %d stays on SCHED_IDLE: %s", tid, std::strerror(errno));
				}
			}

			std::snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);

			if (const int fd = ::open(path, O_RDONLY | O_CLOEXEC); fd >= 0)
			{
				char buf[512];
				const ssize_t n = ::read(fd, buf, sizeof(buf) - 1);
				::close(fd);

				if (n > 0)
				{
					buf[n] = 0;

					// utime and stime are fields 14 and 15; the name before them may hold spaces
					if (const char* p = std::strrchr(buf, ')'))
					{
						unsigned long long utime = 0;
						unsigned long long stime = 0;

						if (std::sscanf(p + 1, " %*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &utime, &stime) == 2)
						{
							cpu_ticks += utime + stime;
						}
					}
				}
			}
		}

		::closedir(dir);

		if (!count)
		{
			return {};
		}

		const long hz = ::sysconf(_SC_CLK_TCK);

		return fmt::format("%u (%u moved off SCHED_IDLE now, %u in all), %u ms CPU", count, moved_now, ::size32(s_moved), cpu_ticks * 1000 / (hz > 0 ? hz : 100));
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
#ifdef __ANDROID__
	u64 last_map_census = 0;
#endif

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

#ifdef __ANDROID__
			// ARMSX3: the process's memory mappings, at most every 30 seconds: reading the table
			// costs the kernel time in proportion to its size. Past the cap, mprotect and thread
			// creation fail while RAM is free (see utils::memory_map_census).
			if (const u64 now = get_system_time(); now - last_map_census >= 30'000'000 || thread_ctrl::state() == thread_state::aborting)
			{
				last_map_census = now;
				fmt::append(msg, ", Maps: %s", utils::memory_map_census());

				if (const std::string writers = mesa_disk_cache_writers(); !writers.empty())
				{
					fmt::append(msg, ", Mesa disk cache writers: %s", writers);
				}
			}
#endif

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
