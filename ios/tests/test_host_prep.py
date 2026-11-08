#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The render host header compiles as C11.
"""

import shutil
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

HOST_H = ROOT / "ios" / "core" / "render" / "ios_host.h"

C11_COMPILE = ("cc", "-fsyntax-only", "-x", "c", "-std=c11")


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


class TheHostHeaderCompilesAsC11(unittest.TestCase):

    def test_the_header_compiles_as_c11(self):
        found = shutil.which(C11_COMPILE[0])
        if found is None:
            self.skipTest("no `cc` on PATH, so the C11 compile of {0} did not run. THIS SKIP IS NOT A PASS.".format(HOST_H))
        read(HOST_H)
        done = subprocess.run([found] + list(C11_COMPILE[1:]) + [str(HOST_H)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        self.assertEqual(0, done.returncode, done.stdout.decode("utf-8", "replace"))


if __name__ == "__main__":
    unittest.main()
