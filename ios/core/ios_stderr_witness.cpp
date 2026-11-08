// SPDX-License-Identifier: GPL-2.0-only
// Runs during static initialisation, so all state is constant-initialised and nothing allocates; not for signal handlers.
#include "ios_stderr_witness.h"

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#if !defined(_WIN32)
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <poll.h>
#include <unistd.h>
#endif

namespace
{
	// Bounded so concurrent loggers on a healthy pipe wait their turn instead of dropping each other's lines.
	constexpr int k_lock_attempts = 200;
	constexpr size_t k_printf_buffer = 2048;

	std::mutex s_mutex;
	std::atomic<uint64_t> s_dropped{0};
	uint64_t s_reported = 0; // only the mutex holder touches it

	// The fd 2 drain; its buffer is touched by the drain thread only.
	std::atomic<bool> s_drain_claimed{false};
	std::atomic<bool> s_drain_installed{false};
	int s_drain_read = -1;     // the owned pipe's read end
	int s_drain_original = -1; // the host's fd 2, saved at 3 or above
	char s_drain_buffer[16384];

#if !defined(_WIN32)
	// Writable only when POLLOUT is set and POLLERR, POLLHUP, POLLNVAL are not (/dev/null answers POLLNVAL on macOS).
	bool writable()
	{
		pollfd p{STDERR_FILENO, POLLOUT, 0};

		for (;;)
		{
			p.revents = 0;
			const int r = ::poll(&p, 1, 0);

			if (r < 0 && errno == EINTR)
				continue;

			return r == 1 && (p.revents & POLLOUT) != 0 && (p.revents & (POLLERR | POLLHUP | POLLNVAL)) == 0;
		}
	}

	// False when the gate closed or write(2) failed before every byte went out.
	bool write_chunks(const char* data, size_t len)
	{
		while (len > 0)
		{
			if (!writable())
				return false;

			const size_t chunk = len < PIPE_BUF ? len : static_cast<size_t>(PIPE_BUF);
			const ssize_t n = ::write(STDERR_FILENO, data, chunk);

			if (n < 0)
			{
				if (errno == EINTR)
					continue;
				return false;
			}

			data += n;
			len -= static_cast<size_t>(n);
		}

		return true;
	}
#endif

#if defined(__APPLE__)
	// Bytes the original will not take are counted by their newlines, whoever wrote them.
	void drain_discard(const char* data, size_t len)
	{
		uint64_t lines = 0;

		for (size_t i = 0; i < len; i++)
			lines += data[i] == '\n';

		s_dropped.fetch_add(lines);
	}

	// shortcut: a tty or pty original can still block the drain, since POLLOUT promises no PIPE_BUF there; fix if a host hands us one.
	void* drain_main(void*)
	{
		pthread_setname_np("armsx3-stderr-drain");

		bool plain = false; // the original answered POLLNVAL (/dev/null on Apple): write without polling
		int wait_ms = 250;  // 0 right after a discard, so a stuck host costs one chunk per read, not 250 ms

		for (;;)
		{
			const ssize_t got = ::read(s_drain_read, s_drain_buffer, sizeof(s_drain_buffer));

			if (got < 0 && errno == EINTR)
				continue;

			if (got <= 0)
			{
				::close(s_drain_read);
				::close(s_drain_original);
				return nullptr;
			}

			const char* data = s_drain_buffer;
			size_t len = static_cast<size_t>(got);
			int again = 0;

			while (len > 0)
			{
				if (!plain)
				{
					pollfd p{s_drain_original, POLLOUT, 0};
					int r;

					do
					{
						p.revents = 0;
						r = ::poll(&p, 1, wait_ms);
					} while (r < 0 && errno == EINTR);

					if (r == 1 && (p.revents & POLLNVAL) != 0)
						plain = true;
					else if (r != 1 || (p.revents & POLLOUT) == 0 || (p.revents & (POLLERR | POLLHUP)) != 0)
					{
						drain_discard(data, len);
						wait_ms = 0;
						break;
					}
				}

				const size_t piece = len < PIPE_BUF ? len : static_cast<size_t>(PIPE_BUF);
				const ssize_t n = ::write(s_drain_original, data, piece);

				if (n < 0)
				{
					// EAGAIN goes back to poll, a few times at most so a lying POLLOUT cannot spin the drain.
					if (errno == EINTR || (errno == EAGAIN && !plain && ++again < 4))
						continue;

					drain_discard(data, len);
					wait_ms = 0;
					break;
				}

				data += n;
				len -= static_cast<size_t>(n);
				wait_ms = 250;
				again = 0;
			}
		}
	}
#endif

	ARMSX3_WITNESS_PRINTF(1, 0) void vwitness(const char* fmt, va_list ap)
	{
		if (fmt == nullptr)
			return;

		char buf[k_printf_buffer];
		const int n = std::vsnprintf(buf, sizeof(buf), fmt, ap);

		if (n <= 0)
			return;

		size_t len = static_cast<size_t>(n);

		if (len >= sizeof(buf))
		{
			len = sizeof(buf) - 1;
			const size_t flen = std::strlen(fmt);

			if (flen != 0 && fmt[flen - 1] == '\n')
				buf[len - 1] = '\n';
		}

		utils::stderr_witness(buf, len);
	}
}

namespace utils
{
	void stderr_witness(const char* data, size_t len) noexcept
	{
		if (data == nullptr || len == 0)
			return;

#if !defined(_WIN32)
		bool owned = false;

		for (int i = 0; i < k_lock_attempts && !owned; i++)
		{
			owned = s_mutex.try_lock();

			if (!owned)
				std::this_thread::yield();
		}

		if (!owned)
		{
			s_dropped.fetch_add(1);
			return;
		}

		if (!writable())
		{
			s_dropped.fetch_add(1);
			s_mutex.unlock();
			return;
		}

		const uint64_t total = s_dropped.load();

		if (total > s_reported)
		{
			char note[96];
			const int n = std::snprintf(note, sizeof(note), "@@WITNESS@@ dropped %llu line(s) while stderr was not writable\n",
				static_cast<unsigned long long>(total - s_reported));

			if (n <= 0 || !write_chunks(note, static_cast<size_t>(n)))
			{
				s_dropped.fetch_add(1);
				s_mutex.unlock();
				return;
			}

			s_reported = total;
		}

		// A line cut off part way still counts as one drop.
		if (!write_chunks(data, len))
			s_dropped.fetch_add(1);

		s_mutex.unlock();
#else
		// Windows: the port never runs there; the symbol links and the line is counted as dropped.
		s_dropped.fetch_add(1);
#endif
	}

	// vsnprintf is not async-signal-safe, exactly as fprintf was not; the rest of the path is poll, try_lock and write.
	void stderr_witness_printf(const char* fmt, ...) noexcept
	{
		va_list ap;
		va_start(ap, fmt);
		vwitness(fmt, ap);
		va_end(ap);
	}

	uint64_t stderr_witness_dropped() noexcept
	{
		return s_dropped.load();
	}

	bool stderr_witness_install_drain() noexcept
	{
#if defined(__APPLE__)
		if (s_drain_claimed.exchange(true))
			return s_drain_installed.load();

		// A closed fd 2 fails every write with EBADF and cannot block; leave it alone.
		if (::fcntl(STDERR_FILENO, F_GETFD) < 0)
			return false;

		const int original = ::fcntl(STDERR_FILENO, F_DUPFD_CLOEXEC, 3);

		if (original < 0)
			return false;

		int p[2];

		if (::pipe(p) != 0)
		{
			::close(original);
			return false;
		}

		const int flags = ::fcntl(p[1], F_GETFL);

		if (::fcntl(p[0], F_SETFD, FD_CLOEXEC) < 0 || ::fcntl(p[1], F_SETFD, FD_CLOEXEC) < 0 || flags < 0
			|| ::fcntl(p[1], F_SETFL, flags | O_NONBLOCK) < 0)
		{
			::close(p[0]);
			::close(p[1]);
			::close(original);
			return false;
		}

		s_drain_read = p[0];
		s_drain_original = original;

		pthread_attr_t attr;
		pthread_t thread;
		pthread_attr_init(&attr);
		pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
		const int made = pthread_create(&thread, &attr, drain_main, nullptr);
		pthread_attr_destroy(&attr);

		if (made != 0)
		{
			::close(p[0]);
			::close(p[1]);
			::close(original);
			return false;
		}

		int r;

		do
		{
			r = ::dup2(p[1], STDERR_FILENO);
		} while (r < 0 && errno == EINTR);

		// After a failed dup2 this was the last write end: the drain reads end-of-file and closes its own fds.
		::close(p[1]);

		if (r < 0)
			return false;

		s_drain_installed = true;

		static constexpr char note[] = "@@WITNESS@@ fd 2 drain installed\n";
		stderr_witness(note, sizeof(note) - 1);
		return true;
#else
		return false;
#endif
	}
}

extern "C" void armsx3_stderr_witness(const char* data, size_t len)
{
	utils::stderr_witness(data, len);
}

extern "C" void armsx3_stderr_witness_printf(const char* fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vwitness(fmt, ap);
	va_end(ap);
}

extern "C" uint64_t armsx3_stderr_witness_dropped(void)
{
	return utils::stderr_witness_dropped();
}

extern "C" int armsx3_stderr_witness_install_drain(void)
{
	return utils::stderr_witness_install_drain() ? 1 : 0;
}
