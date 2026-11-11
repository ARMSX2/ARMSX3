#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The low memory clamp header compiled with c++ and run on the Mac host: the decision table prints
the expected line for each device memory size.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

BRIDGE = ROOT / "ios" / "bridge"

DRIVER = r'''
#include "low_memory.h"
#include <cstdio>
using armsx3::bridge::low_memory::llvm_threads_clamp;
using armsx3::bridge::low_memory::shader_interpreter_off;
using armsx3::bridge::low_memory::early_cse_off;
int main()
{
	const unsigned long long gib = 1ull << 30;
	std::printf("unknown=%u big=%u edge=%u tester_auto=%u tester_six=%u tester_one=%u two_gib_auto=%u\n",
		llvm_threads_clamp(0, 0), llvm_threads_clamp(6 * gib, 0), llvm_threads_clamp(3 * gib, 0),
		llvm_threads_clamp(2462056448ull, 0), llvm_threads_clamp(2462056448ull, 6), llvm_threads_clamp(2462056448ull, 1),
		llvm_threads_clamp(2 * gib, 0));
	std::printf("interp unknown=%d big=%d tester=%d tester_other_mode=%d\n", shader_interpreter_off(0, true),
		shader_interpreter_off(6 * gib, true), shader_interpreter_off(2462056448ull, true), shader_interpreter_off(2462056448ull, false));
	std::printf("cse unknown=%d big=%d tester=%d tester_off=%d\n", early_cse_off(0, true), early_cse_off(6 * gib, true),
		early_cse_off(2462056448ull, true), early_cse_off(2462056448ull, false));
	return 0;
}
'''

EXPECTED = ("unknown=0 big=0 edge=0 tester_auto=2 tester_six=2 tester_one=0 two_gib_auto=2\n"
            "interp unknown=0 big=0 tester=1 tester_other_mode=0\n"
            "cse unknown=0 big=0 tester=1 tester_off=0")


class TheClampDecides(unittest.TestCase):
    def test_decision_table(self):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp, "main.cpp")
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver), "-o",
                                    str(Path(tmp, "d"))], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   universal_newlines=True)
            self.assertEqual(0, built.returncode, built.stdout)
            ran = subprocess.run([str(Path(tmp, "d"))], stdout=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(EXPECTED, ran.stdout.strip())


if __name__ == "__main__":
    unittest.main()
