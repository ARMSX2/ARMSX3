# SPDX-License-Identifier: GPL-2.0-only
"""ios_keymap.h compiled with c++ and run on the Mac host: letters and shift, digits, function keys,
enter and keypad enter, shift and gui keys with their native codes, and unknown or out-of-range codes.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


IOS = Path(__file__).resolve().parents[1]

BRIDGE = IOS / "bridge"

COMPILE_TIMEOUT_SECONDS = 120

RUN_TIMEOUT_SECONDS = 30

KEYMAP_LINES = [
    "K1 a=0x41 lower=a upper=A",
    "K2 one=0x31 shifted=!",
    "K3 f1=0x1000030 f12=0x100003b",
    "K4 enter=0x1000004 kpenter=0x1000005",
    "K5 lshift=0x1000020 native=0x38 rshift native=0x3c",
    "K6 lgui=0x1000022 native=0x37",
    "K7 unknown=0 zero=0 over=0",
]


def driver_source():
    return "\n".join([
        '#include "ios_keymap.h"',
        "#include <cstdio>",
        "using namespace armsx3::ios_keymap;",
        "int main()",
        "{",
        '    std::printf("K1 a=0x%x lower=%c upper=%c\\n", qt_code(4), static_cast<char>(character(4, false)),'
        " static_cast<char>(character(4, true)));",
        '    std::printf("K2 one=0x%x shifted=%c\\n", qt_code(30), static_cast<char>(character(30, true)));',
        '    std::printf("K3 f1=0x%x f12=0x%x\\n", qt_code(58), qt_code(69));',
        '    std::printf("K4 enter=0x%x kpenter=0x%x\\n", qt_code(40), qt_code(88));',
        '    std::printf("K5 lshift=0x%x native=0x%x rshift native=0x%x\\n", qt_code(225), native_code(225),'
        " native_code(229));",
        '    std::printf("K6 lgui=0x%x native=0x%x\\n", qt_code(227), native_code(227));',
        '    std::printf("K7 unknown=%u zero=%u over=%u\\n", qt_code(100), qt_code(0), qt_code(232));',
        "    return 0;",
        "}",
        "",
    ])


class TheKeymapHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / "driver"
            driver.write_text(driver_source(), encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver), "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            cls.compile_output = built.stdout
            if built.returncode != 0:
                return
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True)
            cls.run_code, cls.lines = ran.returncode, ran.stdout.splitlines()
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_seven_keymap_lines(self):
        self.assertIsNotNone(self.lines, "the driver did not compile against ios/bridge/ios_keymap.h:\n"
                             + (self.compile_output or ""))
        self.assertEqual(0, self.run_code)
        self.assertEqual(KEYMAP_LINES, self.lines)


if __name__ == "__main__":
    unittest.main()
