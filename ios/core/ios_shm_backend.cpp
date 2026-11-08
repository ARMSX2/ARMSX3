// SPDX-License-Identifier: GPL-2.0-only
// Application Support holds the container rung's files; they are unlinked at once, so a backup never sees them.

#include "ios_shm_backend.h"
#include "ios_stderr_witness.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>
#endif

namespace utils
{
	const char* shm_backend_name(shm_backend_kind kind)
	{
		switch (kind)
		{
		case shm_backend_kind::posix_shm: return "posix_shm";
		case shm_backend_kind::container_file: return "container_file";
		case shm_backend_kind::mach_memory_entry: return "mach_memory_entry";
		}

		return "unknown";
	}
}

#if !defined(__APPLE__)

// Other platforms never consult the ladder; this answers with upstream's mechanism so vm_native.cpp links everywhere.
namespace utils
{
	shm_backend_kind shm_backend()
	{
		return shm_backend_kind::posix_shm;
	}

	const std::string& shm_container_dir()
	{
		static const std::string s_empty;
		return s_empty;
	}
}

#else

namespace
{
	// 64 KiB is utils::shm's granularity and big enough to tell an alias from a copy.
	constexpr std::size_t k_probe_size = 0x10000;

	// File.cpp's fs::get_cache_dir() names the same path, and a test fails if the two differ.
	constexpr const char* k_container_subdir = "/Library/Application Support";

	// Matches fs::get_cache_dir()'s trailing "/rpcs3/", so both return the same string.
	constexpr const char* k_container_leaf = "/rpcs3/";

	// Not thread-safe on its own: vm_native.cpp calls shm_backend() before shm_container_dir(), which keeps the writers apart.
	std::string g_last_reason;

	void set_reason(const char* what, int err)
	{
		char buf[256];
		std::snprintf(buf, sizeof(buf), "%s failed: %s (errno %d)", what, std::strerror(err), err);
		g_last_reason = buf;
	}

	// Map one descriptor twice, the second view fixed like map_critical(), and check writes cross both ways; a return code proves nothing.
	bool aliases_bidirectionally(int fd, std::size_t size)
	{
		void* first = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);

		if (first == MAP_FAILED)
		{
			set_reason("mmap of the first view", errno);
			return false;
		}

		// Reserve then MAP_FIXED, the way map_critical() places g_sudo_addr.
		void* hole = ::mmap(nullptr, size, PROT_NONE, MAP_PRIVATE | MAP_ANON, -1, 0);

		if (hole == MAP_FAILED)
		{
			set_reason("reservation for the second view", errno);
			::munmap(first, size);
			return false;
		}

		void* second = ::mmap(hole, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);

		if (second == MAP_FAILED)
		{
			set_reason("mmap of the second view at a fixed address", errno);
			::munmap(hole, size);
			::munmap(first, size);
			return false;
		}

		bool ok = second != first;

		if (!ok)
		{
			g_last_reason = "the two views landed at the same address, so nothing was aliased";
		}

		volatile unsigned char* a = static_cast<volatile unsigned char*>(first);
		volatile unsigned char* b = static_cast<volatile unsigned char*>(second);

		const std::size_t offsets[3] = {0, size / 2, size - 1};

		for (std::size_t i = 0; ok && i < 3; i++)
		{
			const unsigned char value = static_cast<unsigned char>(0xa5 ^ i);
			a[offsets[i]] = value;

			if (b[offsets[i]] != value)
			{
				g_last_reason = "a write through the first view was not visible through the second";
				ok = false;
			}
		}

		for (std::size_t i = 0; ok && i < 3; i++)
		{
			const unsigned char value = static_cast<unsigned char>(0x5a ^ i);
			b[offsets[i]] = value;

			if (a[offsets[i]] != value)
			{
				g_last_reason = "a write through the second view was not visible through the first";
				ok = false;
			}
		}

		::munmap(second, size);
		::munmap(first, size);
		return ok;
	}

	// Upstream's mechanism; probed on macOS too rather than assumed.
	bool probe_posix_shm()
	{
		const std::string name = "/rpcs3-shmprobe-" + std::to_string(static_cast<long long>(::getpid()));

		int fd = ::shm_open(name.c_str(), O_RDWR | O_CREAT | O_EXCL, S_IWUSR | S_IRUSR);

		if (fd == -1 && errno == EEXIST)
		{
			::shm_unlink(name.c_str());
			fd = ::shm_open(name.c_str(), O_RDWR | O_CREAT | O_EXCL, S_IWUSR | S_IRUSR);
		}

		if (fd == -1)
		{
			// iOS answers EPERM: the sandbox refuses the namespace, so a shorter name does not help.
			set_reason("shm_open in the POSIX shared-memory namespace", errno);
			return false;
		}

		::shm_unlink(name.c_str());

		if (::ftruncate(fd, static_cast<off_t>(k_probe_size)) != 0)
		{
			set_reason("ftruncate of the POSIX shared-memory object", errno);
			::close(fd);
			return false;
		}

		const bool ok = aliases_bidirectionally(fd, k_probe_size);
		::close(fd);
		return ok;
	}

	// Creates only the components below $HOME, and accepts any that is a directory afterwards whatever errno mkdir gave.
	bool make_container_dir(const std::string& dir)
	{
		const char* home = ::getenv("HOME");
		const std::string root = (home && *home) ? std::string(home) : std::string();

		// The root must end at a component boundary in dir, not just share its leading bytes.
		const bool bounded =
			!root.empty() &&
			dir.size() > root.size() &&
			dir.compare(0, root.size(), root) == 0 &&
			(dir[root.size()] == '/' || root.back() == '/');

		// Start just past the separator after the root, so the walk never tries to create $HOME itself.
		const std::size_t start = bounded ? root.size() + (root.back() == '/' ? 0u : 1u) : 1u;
		// Without a usable $HOME the walk starts at the top, which only happens off-device.

		for (std::size_t i = start; i < dir.size(); i++)
		{
			if (dir[i] != '/')
			{
				continue;
			}

			const std::string part = dir.substr(0, i);

			if (::mkdir(part.c_str(), 0700) == 0)
			{
				continue;
			}

			// errno first: `stat` below and the message after it both clobber it.
			const int err = errno;

			struct ::stat st{};

			if (::stat(part.c_str(), &st) == 0 && S_ISDIR(st.st_mode))
			{
				continue;
			}

			set_reason(("mkdir of " + part).c_str(), err);
			return false;
		}

		return true;
	}

	// An unlinked file, which is what utils::shm's own storage constructor does when overcommit is off.
	bool probe_container_file()
	{
		const std::string& dir = utils::shm_container_dir();

		if (dir.empty())
		{
			g_last_reason = "no container directory is available (HOME is unset)";
			return false;
		}

		if (!make_container_dir(dir))
		{
			return false;
		}

#if TARGET_OS_IPHONE
		// Belt-and-braces only; the unlink below is the guarantee. See the file header.
		const unsigned char excluded = 1;
		::setxattr(dir.c_str(), "com.apple.MobileBackup", &excluded, sizeof(excluded), 0, 0);
#endif

		const std::string path = dir + "rpcs3-shmprobe-" + std::to_string(static_cast<long long>(::getpid())) + ".tmp";

		const int fd = ::open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, S_IWUSR | S_IRUSR);

		if (fd == -1)
		{
			const int err = errno;
			set_reason(("open of " + path).c_str(), err);
			return false;
		}

		// Unlinked at once, so a crash leaves no probe file and backupd has no entry to walk.
		::unlink(path.c_str());

		if (::ftruncate(fd, static_cast<off_t>(k_probe_size)) != 0)
		{
			set_reason("ftruncate of the container file", errno);
			::close(fd);
			return false;
		}

		const bool ok = aliases_bidirectionally(fd, k_probe_size);
		::close(fd);
		return ok;
	}

	// Refused: utils::shm maps descriptors with mmap, and a Mach port is not one.
	bool probe_mach_memory_entry()
	{
		g_last_reason =
			"a Mach memory entry is a port, not a file descriptor, and utils::shm maps m_file "
			"with ::mmap in all five of its mapping methods -- selecting it would mean "
			"reimplementing them. Phase 5 owns that mechanism for RW/RX aliasing";
		return false;
	}

	struct rung
	{
		utils::shm_backend_kind kind;
		bool (*probe)();
	};

	// posix_shm stays first so macOS keeps upstream's choice.
	constexpr rung k_ladder[3] = {
		{utils::shm_backend_kind::posix_shm, &probe_posix_shm},
		{utils::shm_backend_kind::container_file, &probe_container_file},
		{utils::shm_backend_kind::mach_memory_entry, &probe_mach_memory_entry},
	};
}

namespace utils
{
	const std::string& shm_container_dir()
	{
		static const std::string s_dir = []() -> std::string {
			const char* home = ::getenv("HOME");

			if (!home || !*home)
			{
				return std::string();
			}

			const std::string dir = std::string(home) + k_container_subdir + k_container_leaf;

			// Created here too, since vm_native.cpp and fs::get_cache_dir() open paths inside it without creating it.
			if (!make_container_dir(dir))
			{
				// Not fatal: the ladder's own log line decides the rung, and this one names the cause.
				utils::stderr_witness_printf("ARMSX3: could not create the container directory %s: %s\n",
					dir.c_str(),
					g_last_reason.empty() ? "no reason recorded" : g_last_reason.c_str());
			}

			return dir;
		}();

		return s_dir;
	}

	shm_backend_kind shm_backend()
	{
		static const shm_backend_kind s_kind = []() -> shm_backend_kind {
			for (const rung& r : k_ladder)
			{
				g_last_reason.clear();

				if (r.probe())
				{
					utils::stderr_witness_printf("ARMSX3: shm backend '%s' selected.\n", shm_backend_name(r.kind));
					return r.kind;
				}

				// Always log the rung and reason before degrading, so a wrong backend never ships silently.
				utils::stderr_witness_printf("ARMSX3: shm backend '%s' unavailable: %s. Degrading.\n",
					shm_backend_name(r.kind),
					g_last_reason.empty() ? "no reason recorded" : g_last_reason.c_str());
			}

			// Every rung refused: return upstream's mechanism and let its own ensure() fail as before.
			utils::stderr_witness_printf("ARMSX3: no shm backend passed its probe. Falling back to 'posix_shm'; "
				"utils::shm construction is expected to fail.\n");
			return shm_backend_kind::posix_shm;
		}();

#if !TARGET_OS_IPHONE
		// Pin macOS to posix_shm, so an unrelated probe failure cannot move the Mac build to another backend.
		if (s_kind != shm_backend_kind::posix_shm)
		{
			utils::stderr_witness_printf("ARMSX3: non-iOS Apple build selected '%s'; pinning to 'posix_shm' "
				"to keep this platform's behaviour identical to upstream.\n", shm_backend_name(s_kind));
			return shm_backend_kind::posix_shm;
		}
#endif

		return s_kind;
	}
}

#endif
