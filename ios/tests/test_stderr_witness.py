# SPDX-License-Identifier: GPL-2.0-only
"""The stderr witness and the stderr drain compiled with the host compiler and run against a pipe nobody reads, a read pipe,
/dev/null and a closed fd 2. The header is also compiled as C.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(os.environ.get("ARMSX3_TREE_ROOT", str(Path(__file__).resolve().parents[2])))

CODE = {"witness": ROOT / "ios" / "core" / "ios_stderr_witness.cpp"}

DRIVER = r"""
#include "ios_stderr_witness.h"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

static std::atomic<long long> slowest_us{0};

static void timed(const char* data, size_t len)
{
	const auto t0 = std::chrono::steady_clock::now();
	utils::stderr_witness(data, len);
	const long long us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
	for (long long seen = slowest_us.load(); us > seen && !slowest_us.compare_exchange_weak(seen, us);) {}
}

static std::string drain(int fd)
{
	std::string out;
	char buf[4096];
	for (;;)
	{
		const ssize_t n = read(fd, buf, sizeof(buf));
		if (n <= 0)
			return out;
		out.append(buf, static_cast<size_t>(n));
	}
}

int main()
{
	int p[2];
	if (pipe(p) != 0 || dup2(p[1], 2) < 0)
		return 2;
	char line[200];
	std::memset(line, 'x', sizeof(line) - 1);
	line[sizeof(line) - 1] = '\n';
	int calls = 0;
	while (utils::stderr_witness_dropped() == 0 && calls < 20000)
	{
		timed(line, sizeof(line));
		calls++;
	}
	if (utils::stderr_witness_dropped() == 0)
	{
		std::printf("WITNESS-FAIL never dropped after %d calls\n", calls);
		return 1;
	}
	std::vector<std::thread> threads;
	for (int t = 0; t < 4; t++)
		threads.emplace_back([&] { for (int i = 0; i < 2000; i++) timed(line, sizeof(line)); });
	for (auto& t : threads)
		t.join();
	const unsigned long long total = utils::stderr_witness_dropped();
	fcntl(p[0], F_SETFL, fcntl(p[0], F_GETFL) | O_NONBLOCK);
	const size_t filled = drain(p[0]).size();
	utils::stderr_witness("KNOWN-LINE\n", 11);
	const std::string tail = drain(p[0]);
	const std::string want = "@@WITNESS@@ dropped " + std::to_string(total) + " line(s) while stderr was not writable\nKNOWN-LINE\n";
	if (tail != want)
	{
		std::printf("WITNESS-FAIL tail=[%s] want=[%s]\n", tail.c_str(), want.c_str());
		return 1;
	}
	std::printf("WITNESS-OK fill_calls=%d filled_bytes=%zu dropped=%llu slowest_us=%lld\n", calls, filled, total, slowest_us.load());
	return slowest_us.load() < 200000 ? 0 : 1;
}
"""


class WitnessNeverBlocks(unittest.TestCase):
    """BEHAVIOURAL, on the Mac host: the TU against a pipe nobody reads. Not device evidence."""

    def tool(self, name):
        found = shutil.which(name)
        self.assertIsNotNone(found, "no `{0}` on PATH; this test FAILS rather than skips without it".format(name))
        return found

    def test_never_blocks_counts_and_announces(self):
        cxx = self.tool("c++")
        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run([cxx, "-std=c++20", "-O1", "-pthread", "-I", str(ROOT / "ios" / "core"),
                                    str(CODE["witness"]), str(driver), "-o", str(binary)],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=300)
            self.assertEqual(0, built.returncode, "{0} failed:\n{1}".format(cxx, built.stdout))
            try:
                ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     universal_newlines=True, timeout=60)
            except subprocess.TimeoutExpired:
                self.fail("the witness BLOCKED: the driver did not finish within 60 s against a pipe nobody reads")
            self.assertEqual(0, ran.returncode, ran.stdout)
            self.assertIn("WITNESS-OK", ran.stdout)
            sys.stderr.write("\n" + ran.stdout.strip() + "\n")

    def test_header_is_c_includable(self):
        cc = self.tool("cc")
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / "probe.c"
            probe.write_text('#include "ios_stderr_witness.h"\n'
                             'void probe(void) { armsx3_stderr_witness_printf("%d\\n", 1); }\n'
                             'uint64_t probe2(void) { return armsx3_stderr_witness_dropped(); }\n', encoding="utf-8")
            ran = subprocess.run([cc, "-std=c11", "-Wall", "-Werror", "-fsyntax-only", "-I", str(ROOT / "ios" / "core"),
                                  "-x", "c", str(probe)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 universal_newlines=True, timeout=120)
            self.assertEqual(0, ran.returncode, ran.stdout)


DRAIN_DRIVER = r"""
#include "ios_stderr_witness.h"
#import <Foundation/Foundation.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

static long long slowest_us = 0;

template <typename F> static void timed(F f)
{
	const auto t0 = std::chrono::steady_clock::now();
	f();
	const long long us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
	if (us > slowest_us)
		slowest_us = us;
}

static int done(int rc)
{
	std::fflush(stdout);
	_exit(rc);
}

static std::mutex got_mutex;
static std::string got;

static bool got_has(const char* a, const char* b)
{
	std::lock_guard<std::mutex> lock(got_mutex);
	return got.find(a) != std::string::npos && got.find(b) != std::string::npos;
}

int main(int argc, char** argv)
{
	if (argc != 2)
		return 2;
	const std::string mode = argv[1];

	if (mode == "closed")
	{
		close(2);
		if (utils::stderr_witness_install_drain())
			return std::printf("DRAIN-FAIL closed install returned true\n"), done(1);
		if (fcntl(2, F_GETFD) >= 0)
			return std::printf("DRAIN-FAIL closed fd 2 was opened by the install\n"), done(1);
		timed([] { utils::stderr_witness("x\n", 2); });
		std::printf("DRAIN-OK closed slowest_us=%lld\n", slowest_us);
		return done(slowest_us < 200000 ? 0 : 1);
	}

	if (mode == "devnull")
	{
		const int fd = open("/dev/null", O_WRONLY);
		if (fd < 0 || dup2(fd, 2) < 0)
			return done(2);
		close(fd);
		if (!utils::stderr_witness_install_drain())
			return std::printf("DRAIN-FAIL devnull install returned false\n"), done(1);
		for (int i = 0; i < 1000; i++)
			utils::stderr_witness("short line\n", 11);
		std::this_thread::sleep_for(std::chrono::milliseconds(500));
		const unsigned long long dropped = utils::stderr_witness_dropped();
		std::printf("%s devnull dropped=%llu\n", dropped == 0 ? "DRAIN-OK" : "DRAIN-FAIL", dropped);
		return done(dropped == 0 ? 0 : 1);
	}

	int p[2];
	if (pipe(p) != 0 || dup2(p[1], 2) < 0)
		return done(2);
	close(p[1]);

	if (mode == "read")
	{
		const int rd = p[0];
		std::thread([rd] {
			char buf[4096];
			for (;;)
			{
				const ssize_t n = read(rd, buf, sizeof(buf));
				if (n <= 0)
					return;
				std::lock_guard<std::mutex> lock(got_mutex);
				got.append(buf, static_cast<size_t>(n));
			}
		}).detach();
		if (!utils::stderr_witness_install_drain())
			return std::printf("DRAIN-FAIL read install returned false\n"), done(1);
		std::string want;
		for (int i = 0; i < 1000; i++)
		{
			char line[16];
			const int n = std::snprintf(line, sizeof(line), "L%04d\n", i);
			want.append(line, static_cast<size_t>(n));
			utils::stderr_witness(line, static_cast<size_t>(n));
		}
		NSLog(@"NSLOG-PROBE");
		const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
		while (!got_has("L0999\n", "NSLOG-PROBE") && std::chrono::steady_clock::now() < deadline)
			std::this_thread::sleep_for(std::chrono::milliseconds(10));
		std::lock_guard<std::mutex> lock(got_mutex);
		const unsigned long long dropped = utils::stderr_witness_dropped();
		const bool ok = got.find(want) != std::string::npos && got.find("NSLOG-PROBE") != std::string::npos && dropped == 0;
		std::printf("%s read bytes=%zu dropped=%llu\n", ok ? "DRAIN-OK" : "DRAIN-FAIL", got.size(), dropped);
		return done(ok ? 0 : 1);
	}

	if (mode == "unread")
	{
		if (!utils::stderr_witness_install_drain())
			return std::printf("DRAIN-FAIL unread install returned false\n"), done(1);
		utils::stderr_witness("FIRST-LINE\n", 11);
		static char big[4096];
		std::memset(big, 'r', sizeof(big));
		big[sizeof(big) - 1] = '\n';
		for (int i = 0; i < 64; i++)
			timed([] { (void)write(2, big, sizeof(big)); });
		char line[200];
		std::memset(line, 'x', sizeof(line) - 1);
		line[sizeof(line) - 1] = '\n';
		NSString* text = [[NSString alloc] initWithBytes:line length:sizeof(line) - 1 encoding:NSASCIIStringEncoding];
		for (int i = 0; i < 2000; i++)
			timed([&] { NSLog(@"%@", text); });
		for (int i = 0; i < 2000; i++)
			timed([&] { utils::stderr_witness(line, sizeof(line)); });
		const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
		while (utils::stderr_witness_dropped() == 0 && std::chrono::steady_clock::now() < deadline)
			std::this_thread::sleep_for(std::chrono::milliseconds(10));
		const unsigned long long dropped = utils::stderr_witness_dropped();
		fcntl(p[0], F_SETFL, fcntl(p[0], F_GETFL) | O_NONBLOCK);
		char head[256] = {};
		const ssize_t n = read(p[0], head, sizeof(head) - 1);
		const char* want = "@@WITNESS@@ fd 2 drain installed\nFIRST-LINE\n";
		const bool first = n > 0 && std::strncmp(head, want, std::strlen(want)) == 0;
		const bool ok = first && dropped > 0 && slowest_us < 200000;
		std::printf("%s unread slowest_us=%lld dropped=%llu first_bytes_ok=%d\n", ok ? "DRAIN-OK" : "DRAIN-FAIL",
			slowest_us, dropped, first ? 1 : 0);
		return done(ok ? 0 : 1);
	}

	return done(2);
}
"""


class DrainNeverBlocks(unittest.TestCase):
    """BEHAVIOURAL, on the Mac host: the drain against four shapes of fd 2. Not device evidence."""

    @classmethod
    def setUpClass(cls):
        cxx = shutil.which("c++")
        if cxx is None:
            raise AssertionError("no `c++` on PATH; DrainNeverBlocks FAILS rather than skips without it")
        cls.tmp = tempfile.TemporaryDirectory()
        driver = Path(cls.tmp.name) / "driver.mm"
        cls.binary = Path(cls.tmp.name) / "driver"
        driver.write_text(DRAIN_DRIVER, encoding="utf-8")
        built = subprocess.run([cxx, "-std=c++20", "-O1", "-pthread", "-I", str(ROOT / "ios" / "core"),
                                str(CODE["witness"]), str(driver), "-framework", "Foundation", "-o", str(cls.binary)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=300)
        if built.returncode != 0:
            raise AssertionError("{0} failed:\n{1}".format(cxx, built.stdout))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_mode(self, mode):
        try:
            ran = subprocess.run([str(self.binary), mode], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 universal_newlines=True, timeout=60)
        except subprocess.TimeoutExpired:
            self.fail("BLOCKED: the drain driver ({0}) did not finish within 60 s".format(mode))
        self.assertEqual(0, ran.returncode, ran.stdout)
        self.assertIn("DRAIN-OK " + mode, ran.stdout)
        sys.stderr.write("\n" + ran.stdout.strip() + "\n")

    def test_unread_pipe(self):
        self.run_mode("unread")

    def test_read_pipe(self):
        self.run_mode("read")

    def test_dev_null(self):
        self.run_mode("devnull")

    def test_closed_fd2(self):
        self.run_mode("closed")


if __name__ == "__main__":
    unittest.main()
