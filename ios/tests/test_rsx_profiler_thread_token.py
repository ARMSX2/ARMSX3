#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""rsx::prof::current_thread_token() names one thread on Apple arm64: every live thread gets its own token and
a thread keeps the same token while it sleeps and spins. Built from the real header and run on this Mac,
so it says nothing about an iPhone's kernel beyond sharing XNU."""

import platform
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

PROBE = r"""
#include "Emu/RSX/rsx_profiler.h"
#include <chrono>
#include <cstdio>
#include <set>
#include <thread>
#include <vector>

int main()
{
	constexpr int threads = 16;
	std::vector<const void*> first(threads);
	std::vector<int> changed(threads);
	std::vector<std::thread> pool;

	for (int i = 0; i < threads; i++)
	{
		pool.emplace_back([&, i]
		{
			first[i] = rsx::prof::current_thread_token();
			for (int n = 0; n < 2000; n++)
			{
				if (n % 3 == 0)
					std::this_thread::sleep_for(std::chrono::microseconds(100));
				else
					for (volatile int k = 0; k < 20000; k++);
				changed[i] += rsx::prof::current_thread_token() != first[i];
			}
		});
	}

	for (auto& t : pool)
		t.join();

	int moved = 0;
	for (int c : changed)
		moved += c;

	const std::set<const void*> distinct(first.begin(), first.end());
	std::printf("distinct=%zu of %d changed=%d\n", distinct.size(), threads, moved);
	return distinct.size() == threads && moved == 0 ? 0 : 1;
}
"""


@unittest.skipUnless(sys.platform == "darwin" and platform.machine() == "arm64", "needs an Apple arm64 host")
@unittest.skipUnless(shutil.which("xcrun"), "needs xcrun clang++")
class ThreadToken(unittest.TestCase):
    def test_one_token_per_thread(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "probe.cpp"
            exe = Path(tmp) / "probe"
            src.write_text(PROBE, encoding="utf-8")
            subprocess.run(
                ["xcrun", "clang++", "-std=c++20", "-O1", "-I", str(ROOT / "rpcs3"), "-I", str(ROOT),
                 str(src), "-o", str(exe)],
                check=True, capture_output=True, text=True)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=120)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
