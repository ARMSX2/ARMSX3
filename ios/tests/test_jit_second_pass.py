#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ios_jit_split.h compiled as C11 and run on the Mac host against a model of the second-pass range rule
on a grid and six pinned rows, and JitSecondPassLink.url compiled with swiftc and run:
the StikDebug link, and nil for an empty bundle id or a pid that is not positive."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


ROOT = Path(os.environ.get("ARMSX3_TREE_ROOT") or Path(__file__).resolve().parents[2])

SPLIT = ROOT / "ios" / "core" / "ios_jit_split.h"

LINK = ROOT / "ios" / "app" / "JIT" / "JitSecondPass.swift"

MIB = 0x100000

ROUND = 0x200000

PAGE = 0x4000

BASE3 = 0x41000000

MAX2 = 0x40000000

MAX3 = (0x7FFF8000 - BASE3) // ROUND * ROUND

NONE = (1 << 64) - 1


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def step(cap, top):
    grow = max(cap // 2, 64 * MIB)
    grow = (grow + ROUND - 1) // ROUND * ROUND
    return 0 if cap >= top else min(grow, top - cap)


def model_walk(first, cap2, cap3, allow):
    s2, s3 = step(cap2, MAX2), step(cap3, MAX3)
    if allow:
        allow = allow // ROUND * ROUND
        if first == 2:
            s2 = min(s2, allow)
            s3 = min(s3, allow - s2)
        else:
            s3 = min(s3, allow)
            s2 = min(s2, allow - s3)
    r2, r3 = [cap2, cap2 + s2], [BASE3 + cap3, BASE3 + cap3 + s3]
    return (r2 + r3 if first == 2 else r3 + r2) + [0] * 8


def model_extents(w, pages, stop, cap2, cap3):
    end2, end3 = cap2, BASE3 + cap3
    for i in range(0, 12, 2):
        a = w[i]
        while a < w[i + 1] and pages:
            if a == stop:
                pages = 0
                break
            pages -= 1
            if a == end2 and a < BASE3:
                end2 = a + PAGE
            elif a == end3:
                end3 = a + PAGE
            a += PAGE
    return end2, (end3 - BASE3) // ROUND * ROUND


GRID = [(first, cap2 * MIB, cap3 * MIB, allow * MIB)
        for first in (2, 3)
        for cap2 in (14, 64, 152, 1022, 1024)
        for cap3 in (128, 364, 1004, MAX3 // MIB)
        for allow in (0, 1, 100, 200, 4096)]

# (first, cap2 MiB, cap3 MiB, allow MiB, pages, stop) -> (walk[0:4] in MiB offsets, new2 MiB, new3 MiB)
PINNED = [
    ((3, 152, 364, 0, NONE, NONE), ((1404, 1586, 152, 228), 228, 546)),
    ((2, 152, 364, 0, NONE, NONE), ((152, 228, 1404, 1586), 228, 546)),
    ((2, 152, 364, 200, NONE, NONE), ((152, 228, 1404, 1528), 228, 488)),
    ((3, 152, 364, 100, NONE, NONE), ((1404, 1504, 152, 152), 152, 464)),
    ((3, 152, 364, 0, 100 * MIB // PAGE + 1, NONE), ((1404, 1586, 152, 228), 152, 464)),
    ((3, 152, MAX3 // MIB, 0, NONE, NONE), ((1040 + MAX3 // MIB, 1040 + MAX3 // MIB, 152, 228), 228, MAX3 // MIB)),
]


def driver_source(rows):
    lines = []
    for first, cap2, cap3, allow, pages, stop in rows:
        lines.append('\trow(%d, %dull, %dull, %dull, %dull, %dull);' % (first, cap2, cap3, allow, pages, stop))
    return ("#include <stdio.h>\n#include \"ios_jit_split.h\"\n"
            "static void row(int first, unsigned long long cap2, unsigned long long cap3, unsigned long long allow,\n"
            "\tunsigned long long pages, unsigned long long stop)\n{\n"
            "\tunsigned long long w[ARMSX3_JIT_SECOND_WORDS], n2 = 0, n3 = 0;\n\tint i;\n"
            "\tarmsx3_jit_second_walk(first, cap2, %dull, %dull, cap3, %dull, allow, w);\n"
            "\tarmsx3_jit_second_extents(w, pages, stop, cap2, %dull, cap3, &n2, &n3);\n"
            "\tfor (i = 0; i < ARMSX3_JIT_SECOND_WORDS; i++) printf(\"%%llu \", w[i]);\n"
            "\tprintf(\"%%llu %%llu\\n\", n2, n3);\n}\n"
            "int main(void)\n{\n%s\n\treturn 0;\n}\n") % (MAX2, BASE3, MAX3, BASE3, "\n".join(lines))


def run_driver(header_text, rows):
    compiler = shutil.which("cc")
    if compiler is None:
        raise unittest.SkipTest("no `cc` on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "ios_jit_split.h").write_text(header_text, encoding="utf-8")
        (Path(tmp) / "driver.c").write_text(driver_source(rows), encoding="utf-8")
        built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", tmp, str(Path(tmp) / "driver.c"),
                                "-o", str(Path(tmp) / "driver")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               universal_newlines=True, timeout=120)
        if built.returncode != 0:
            return None, "walk: the driver does not compile: " + built.stdout[-800:]
        ran = subprocess.run([str(Path(tmp) / "driver")], stdout=subprocess.PIPE, universal_newlines=True, timeout=30)
    return [[int(v) for v in line.split()] for line in ran.stdout.splitlines()], None


def walk_problems(header_text):
    rows = [(f, c2, c3, a, NONE, NONE) for f, c2, c3, a in GRID]
    rows += [(f, c2 * MIB, c3 * MIB, a * MIB, p, s) for (f, c2, c3, a, p, s), _ in PINNED]
    got, error = run_driver(header_text, rows)
    if error:
        return [error]
    out = []
    for (first, cap2, cap3, allow, pages, stop), line in zip(rows, got):
        w = model_walk(first, cap2, cap3, allow)
        want = w + list(model_extents(w, pages, stop, cap2, cap3))
        if line != want:
            out.append("walk: row {0} gives {1}, the rule gives {2}".format((first, cap2, cap3, allow), line, want))
    for ((first, cap2, cap3, allow, pages, stop), (head, new2, new3)), line in zip(PINNED, got[len(GRID):]):
        if tuple(v // MIB for v in line[:4]) != head or (line[12] // MIB, line[13] // MIB) != (new2, new3):
            out.append("walk: pinned row {0} gives {1}".format((first, cap2, cap3, allow), line))
    return out[:6]


LINK_DRIVER = """import Foundation
func show(_ u: URL?) -> String { return u?.absoluteString ?? "nil" }
print(show(JitSecondPassLink.url(bundleID: "com.armsx3.ARMSX3", pid: 4242)))
print(show(JitSecondPassLink.url(bundleID: "a b&c", pid: 7)))
print(show(JitSecondPassLink.url(bundleID: "", pid: 7)))
print(show(JitSecondPassLink.url(bundleID: "com.armsx3.ARMSX3", pid: 0)))
"""

LINK_WANT = [
    "stikdebug://enable-jit?bundle-id=com.armsx3.ARMSX3&pid=4242&script-name=universal.js",
    "stikdebug://enable-jit?bundle-id=a%20b%26c&pid=7&script-name=universal.js",
    "nil",
    "nil",
]


def link_problems(swift_text):
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "JitSecondPass.swift"
        src.write_text(swift_text, encoding="utf-8")
        try:
            got = compile_and_run([src], LINK_DRIVER, tmp).splitlines()
        except AssertionError as error:
            return ["link: the link does not compile or run: {0}".format(str(error)[-400:])]
    return [] if got == LINK_WANT else ["link: JitSecondPassLink.url gives {0}, expected {1}".format(got, LINK_WANT)]


class TheSecondPassHolds(unittest.TestCase):
    def test_walk_matches_the_rule(self):
        self.assertEqual([], walk_problems(read(SPLIT)))

    def test_link_names_this_pid(self):
        self.assertEqual([], link_problems(read(LINK)))


class TheGuardCanFail(unittest.TestCase):
    def test_walk_rule_changed(self):
        header = read(SPLIT).replace("const unsigned long long half = cap / 2;", "const unsigned long long half = cap;", 1)
        self.assertNotEqual(header, read(SPLIT), "the mutation's anchor moved")
        self.assertTrue(walk_problems(header))

    def test_link_by_bundle_id_only(self):
        swift = read(LINK).replace('URLQueryItem(name: "pid", value: String(pid)),\n', "", 1)
        self.assertNotEqual(swift, read(LINK), "the mutation's anchor moved")
        self.assertTrue(link_problems(swift))


if __name__ == "__main__":
    unittest.main()
