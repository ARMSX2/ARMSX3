# SPDX-License-Identifier: GPL-2.0-only
"""The camera frame converter header compiled with c++ and run on the Mac host:
a BGRA pixel swizzled to RGBA, a mirrored row, a scaled source and a same-size copy.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

BRIDGE = ROOT / "ios" / "bridge"

COMPILE_TIMEOUT_SECONDS = 120

RUN_TIMEOUT_SECONDS = 30

CONVERSION_LINES = ["C1 rgba=3,2,1,4", "C2 mirror=40", "C3 scale=10,30", "C4 same=1"]


def driver_source():
    return r"""
#include "ios_camera_convert.h"
#include <cstdio>
#include <cstring>

using armsx3::camera_convert::bgra_to_rgba;

int main()
{
	const std::uint8_t one[4] = {1, 2, 3, 4};
	std::uint8_t out1[4] = {};
	bgra_to_rgba(one, 1, 1, 4, out1, 1, 1, false);
	std::printf("C1 rgba=%d,%d,%d,%d\n", out1[0], out1[1], out1[2], out1[3]);

	// 4x2 BGRA source, red = 10 * (x + 1) + 100 * y.
	std::uint8_t src[4 * 2 * 4] = {};
	std::uint8_t swizzled[4 * 2 * 4] = {};
	for (unsigned y = 0; y < 2; y++)
		for (unsigned x = 0; x < 4; x++)
		{
			std::uint8_t* p = src + (y * 4 + x) * 4;
			p[0] = static_cast<std::uint8_t>(x);
			p[1] = static_cast<std::uint8_t>(y);
			p[2] = static_cast<std::uint8_t>(10 * (x + 1) + 100 * y);
			p[3] = 255;
			std::uint8_t* q = swizzled + (y * 4 + x) * 4;
			q[0] = p[2]; q[1] = p[1]; q[2] = p[0]; q[3] = p[3];
		}

	std::uint8_t out[4 * 2 * 4] = {};
	bgra_to_rgba(src, 4, 1, 16, out, 4, 1, true);
	std::printf("C2 mirror=%d\n", out[0]);

	bgra_to_rgba(src, 4, 2, 16, out, 2, 1, false);
	std::printf("C3 scale=%d,%d\n", out[0], out[4]);

	bgra_to_rgba(src, 4, 2, 16, out, 4, 2, false);
	std::printf("C4 same=%d\n", std::memcmp(out, swizzled, sizeof(out)) == 0 ? 1 : 0);
	return 0;
}
"""


class TheConversionHolds(unittest.TestCase):
    """HOST C++ against the pure header. No docstrings on the methods, so `-v` prints `... ok` here."""

    lines = None

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
            driver.write_text(driver_source(), encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver), "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
            )
            cls.compile_output = built.stdout
            if built.returncode != 0:
                return
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True)
            cls.run_code = ran.returncode
            cls.lines = ran.stdout.splitlines()
            sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_four_conversion_lines(self):
        self.assertIsNotNone(self.lines, "the driver did not compile against ios/bridge/ios_camera_convert.h:\n"
                             + (getattr(self, "compile_output", "") or ""))
        self.assertEqual(0, self.run_code)
        self.assertEqual(CONVERSION_LINES, self.lines)


if __name__ == "__main__":
    unittest.main()
