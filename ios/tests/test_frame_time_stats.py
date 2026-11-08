# SPDX-License-Identifier: GPL-2.0-only
"""The frame time statistics header compiled with clang++ and run on the Mac host:
six fixed series give the expected percentile and average lines.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

HEADER = ROOT / "ios" / "bridge" / "frame_time_stats.h"

COMPILE_TIMEOUT_SECONDS = 120

RUN_TIMEOUT_SECONDS = 30

EXPECTED = [
    "S1 n=0 low1=0.000000 p95=0.000000 p99=0.000000 gt100=0 gt250=0 gt500=0",
    "S2 n=100 low1=1.666667 p95=16.667000 p99=16.667000 gt100=1 gt250=1 gt500=1",
    "S3 n=200 low1=1.428570 p95=120.000000 p99=500.000000 gt100=13 gt250=6 gt500=2",
    "S4 n=1 low1=25.000000 p95=40.000000 p99=40.000000 gt100=0 gt250=0 gt500=0",
    "S5 n=30 low1=6.666667 p95=33.333000 p99=150.000000 gt100=1 gt250=0 gt500=0",
    "S6 n=1000 low1=6.896552 p95=16.667000 p99=16.667000 gt100=9 gt250=0 gt500=0",
]

DRIVER = r'''
#include "frame_time_stats.h"
#include <cstdio>

using v = std::vector<std::uint32_t>;

static void add(v& s, std::uint32_t value, int count)
{
	for (int i = 0; i < count; i++)
		s.push_back(value);
}

static void show(int k, v s)
{
	const auto r = armsx3::bridge::diag::compute_frame_time_stats(s);
	std::printf("S%d n=%u low1=%.6f p95=%.6f p99=%.6f gt100=%u gt250=%u gt500=%u\n",
		k, r.n, r.low1_fps, r.p95_ms, r.p99_ms, r.gt100, r.gt250, r.gt500);
}

int main()
{
	v s1;
	show(1, s1);

	v s2;
	add(s2, 16667, 50); add(s2, 600000, 1); add(s2, 16667, 49);
	show(2, s2);

	v s3;
	add(s3, 900000, 1); add(s3, 500001, 1); add(s3, 500000, 1); add(s3, 300000, 3); add(s3, 250000, 1);
	add(s3, 120000, 6); add(s3, 100000, 1); add(s3, 33333, 186);
	show(3, s3);

	show(4, v{40000});

	v s5;
	add(s5, 33333, 29); add(s5, 150000, 1);
	show(5, s5);

	v s6;
	for (int i = 0; i < 10; i++) add(s6, 100000 + 10000 * i, 1);
	add(s6, 16667, 990);
	show(6, s6);
	return 0;
}
'''


class TheStatsMatchTheDefinitions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, universal_newlines=True)
        if found.returncode != 0:
            raise unittest.SkipTest("xcrun finds no macOS `clang++`; a skip is not a pass")
        if not HEADER.is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(HEADER))

        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run(
                ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-I", str(HEADER.parent), "-o", str(binary),
                 str(driver)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            if built.returncode != 0:
                raise AssertionError("clang++ failed:\n" + built.stdout)
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
        cls.code, cls.out = ran.returncode, ran.stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("S")]

    def test_six_series_give_the_preregistered_lines(self):
        self.assertEqual(0, self.code, self.out[-4000:])
        self.assertEqual(EXPECTED, self.lines)


if __name__ == "__main__":
    unittest.main()
