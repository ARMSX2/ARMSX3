#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ThemeTexturePlan.make in ThemeTextureBudget.swift, run on the Mac host, picks BC, RGBA or shader-decoded blocks and the
side for each live theme texture under the 96 MiB budget, with ThemeSceneRenderer.kt's ported helpers.
It does not upload or draw a texture or read a device's BC support."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
BYTES = APP / "Themes" / "ThemeBytes.swift"
P3T = APP / "Themes" / "P3tTheme.swift"
ANIM = APP / "Themes" / "P3tAnimation.swift"
EDGE = APP / "Themes" / "EdgeAnim.swift"
RAF = APP / "Themes" / "RafScene.swift"
BUDGET = APP / "Themes" / "ThemeTextureBudget.swift"
SOURCES = (BYTES, P3T, ANIM, EDGE, RAF, BUDGET)

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
CHECKS = (("budget", r"\bbudget\s*=\s*96\s*<<\s*20\b"), ("maxside", r"\bmaxSide\s*=\s*2048\b"),
          ("minside", r"\bminSide\s*=\s*256\b"))

DRIVER = r"""
import Foundation

typealias T = P3tAnimation.Texture
var next = 0
func tex(_ format: Int, _ w: Int, _ h: Int) -> T {
    next += 4096
    return T(format: format, width: w, height: h, pitch: (w + 3) / 4 * 8, offset: next, size: 16)
}
let DXT1 = 0x86, DXT3 = 0x87, DXT5 = 0x88, PIC = P3tAnimation.picture
func paths(_ p: ThemeTexturePlan, _ ts: [T]) -> String { ts.map { p.paths[$0.offset]?.rawValue ?? "-" }.joined(separator: ",") }
func hex(_ a: [UInt32]) -> String { a.map { String($0, radix: 16) }.joined(separator: ",") }

print("CASE p1|\(ThemeTextureBudget.bytesAt(width: 1920, height: 1080, side: 2048))|\(ThemeTextureBudget.bytesAt(width: 1920, height: 1080, side: 1024))")

var px: [UInt32] = [0xFF123456, 0x80FF8040]
ThemeTextureBudget.premultiply(&px)
print("CASE pre|\(hex(px))")
print("CASE halve2|\(hex(ThemeTextureBudget.halve([0xFF000000, 0xFF030303, 0x01020304, 0x00FFFFFF], width: 2, height: 2)))")
print("CASE halve3|\(hex(ThemeTextureBudget.halve([0x10203040, 0x20304050, 0xFFFFFFFF], width: 3, height: 1)))")
print("CASE halve1|\(hex(ThemeTextureBudget.halve([0x12345678], width: 1, height: 1)))")
print("CASE halveShort|\(ThemeTextureBudget.halve([1, 2], width: 4, height: 4).count)")

let mixed = [tex(DXT1, 512, 512), tex(DXT3, 256, 256), tex(DXT5 | 0x20, 128, 64), tex(PIC, 640, 480)]
let p3 = ThemeTexturePlan.make(textures: mixed, supportsBC: true, maxTextureSide: 16384, forced: nil)
print("CASE p3|\(paths(p3, mixed))|\(p3.dxtPath.rawValue)")

for limit in [16384, 1024, 100] {
    let p = ThemeTexturePlan.make(textures: mixed, supportsBC: false, maxTextureSide: limit, forced: nil)
    print("CASE p4_\(limit)|\(paths(p, mixed))|\(p.side)")
}

let frames = (0 ..< 40).map { _ in tex(DXT1, 1920, 1080) }
let wide = tex(DXT5, 70000, 8)
let one = [tex(PIC, 1920, 1080)]
let p5 = ThemeTexturePlan.make(textures: frames + [wide] + one, supportsBC: false, maxTextureSide: 16384, forced: nil)
print("CASE p5|\(Set(frames.map { p5.paths[$0.offset]!.rawValue }).sorted().joined(separator: ","))|\(p5.paths[wide.offset]!.rawValue)|\(p5.paths[one[0].offset]!.rawValue)|\(p5.side)|\(p5.dxtPath.rawValue)")
let pics10 = (0 ..< 10).map { _ in tex(PIC, 1920, 1080) }
let p5b = ThemeTexturePlan.make(textures: frames + pics10, supportsBC: false, maxTextureSide: 16384, forced: nil)
print("CASE p5_halve|\(Set(pics10.map { p5b.paths[$0.offset]!.rawValue }).sorted().joined(separator: ","))|\(p5b.side)")
let pics400 = (0 ..< 400).map { _ in tex(PIC, 4096, 4096) }
let p5c = ThemeTexturePlan.make(textures: pics400, supportsBC: false, maxTextureSide: 16384, forced: nil)
print("CASE p5_floor|\(p5c.side)")
// The same texture listed twice is one texture.
let p5d = ThemeTexturePlan.make(textures: mixed + mixed, supportsBC: true, maxTextureSide: 16384, forced: nil)
print("CASE p5_dupes|\(p5d.paths.count)")

let all = frames + [wide] + one + mixed
for bc in [true, false] {
    let r = ThemeTexturePlan.make(textures: all, supportsBC: bc, maxTextureSide: 16384, forced: .rgba)
    print("CASE p6_rgba_\(bc)|\(Set(r.paths.values.map { $0.rawValue }).sorted().joined(separator: ","))")
    let b = ThemeTexturePlan.make(textures: all, supportsBC: bc, maxTextureSide: 16384, forced: .blocks)
    let dxt = all.filter { P3tAnimation.dxt($0) != 0 && $0.width <= 16384 * 4 }
    print("CASE p6_blocks_\(bc)|\(Set(dxt.map { b.paths[$0.offset]!.rawValue }).sorted().joined(separator: ","))|\(Set(all.filter { P3tAnimation.isPicture($0) }.map { b.paths[$0.offset]!.rawValue }).sorted().joined(separator: ","))")
}
let forcedBC = ThemeTexturePlan.make(textures: all, supportsBC: false, maxTextureSide: 16384, forced: .bc)
let unforced = ThemeTexturePlan.make(textures: all, supportsBC: false, maxTextureSide: 16384, forced: nil)
print("CASE p6_bc|\(forcedBC.paths == unforced.paths && forcedBC.side == unforced.side && forcedBC.dxtPath == unforced.dxtPath)|\(Set(forcedBC.paths.values.map { $0.rawValue }).sorted().joined(separator: ","))")
print("DONE")
"""


def texture_plan_problems(code_raw):
    """code_raw: ThemeTextureBudget.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: ThemeTextureBudget.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    for prefix, pattern in CHECKS:
        if not re.search(pattern, code):
            problems.append("{0}: ThemeTextureBudget.swift does not keep `{1}`".format(prefix, pattern))
    return problems


def halve_channel(pixels, shift):
    return (sum((p >> shift) & 0xFF for p in pixels) + 2) // 4


def halve_one(pixels):
    return sum(halve_channel(pixels, s) << s for s in (0, 8, 16, 24))


class ThePlan(unittest.TestCase):
    """P1 to P6."""

    @classmethod
    def setUpClass(cls):
        for source in SOURCES:
            read(source)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run(list(SOURCES), DRIVER, tmp)
        cls.done = "DONE" in out.splitlines()
        cls.lines = {}
        for text in out.splitlines():
            if text.startswith("CASE "):
                key, _, rest = text[5:].partition("|")
                cls.lines[key] = rest.split("|")

    def case(self, key):
        self.assertTrue(self.done, "the driver did not finish")
        self.assertIn(key, self.lines)
        return self.lines[key]

    def test_p1_bytes_at_a_side(self):
        self.assertEqual([str(1920 * 1080 * 4 * 4 // 3), str(960 * 540 * 4 * 4 // 3)], self.case("p1"))

    def test_p2_premultiply_and_halve(self):
        self.assertEqual(["ff123456,80804020"], self.case("pre"))
        two = [0xFF000000, 0xFF030303, 0x01020304, 0x00FFFFFF]
        self.assertEqual(["{0:x}".format(halve_one(two))], self.case("halve2"))
        three = [0x10203040, 0x20304050]
        self.assertEqual(["{0:x}".format(halve_one(three + three))], self.case("halve3"))
        self.assertEqual(["12345678"], self.case("halve1"))
        self.assertEqual(["0"], self.case("halveShort"))

    def test_p3_bc_where_supported(self):
        self.assertEqual(["bc,bc,bc,rgba", "bc"], self.case("p3"))

    def test_p4_rgba_under_budget(self):
        self.assertEqual(["rgba,rgba,rgba,rgba", "2048"], self.case("p4_16384"))
        self.assertEqual(["rgba,rgba,rgba,rgba", "1024"], self.case("p4_1024"))
        self.assertEqual(["rgba,rgba,rgba,rgba", "256"], self.case("p4_100"))

    def test_p5_over_budget_keeps_dxt_as_blocks(self):
        self.assertEqual(["blocks", "rgba", "rgba", "2048", "blocks"], self.case("p5"))
        self.assertEqual(["rgba", "1024"], self.case("p5_halve"))
        self.assertEqual(["256"], self.case("p5_floor"))
        self.assertEqual(["4"], self.case("p5_dupes"))

    def test_p6_forced_paths(self):
        for bc in ("true", "false"):
            self.assertEqual(["rgba"], self.case("p6_rgba_" + bc))
            self.assertEqual(["blocks", "rgba"], self.case("p6_blocks_" + bc))
        self.assertEqual(["true", "blocks,rgba"], self.case("p6_bc"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], texture_plan_problems(read(BUDGET)))

    def test_budget_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(BUDGET)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = texture_plan_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_metal(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport Metal")

    def test_mutation_budget(self):
        self.flagged("budget", "budget = 96 << 20", "budget = 960 << 20")

    def test_mutation_maxside(self):
        self.flagged("maxside", "maxSide = 2048", "maxSide = 4096")

    def test_mutation_minside(self):
        self.flagged("minside", "minSide = 256", "minSide = 16")


if __name__ == "__main__":
    unittest.main()
