#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ThemeSlideshowClock.swift, run alone on the Mac host, holds slides, picks the first and next slide and computes the
camera zoom as Android's ThemeSlideshow.kt does, with finite answers for NaN, infinite or negative inputs.
It says nothing about the view, the pictures or a device."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
CLOCK = IOS / "app" / "Themes" / "ThemeSlideshowClock.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")

EXPECTED = {
    "C1": ("hold=8.5 fade=1.5 fadelong=0.0 nan=10.0/1.5/0.0/0.0/0.0/8.5 inf=10.0/1.5/0.0/0.0/0.0/8.5 "
           "neg=10.0/1.5/0.0/0.0/0.0/8.5 count=15/0"),
    "C2": "first=0,7,14 high=14,14,0 next=1,0 plays=true/false/false empty=0/0",
    "C3": ("t0=1.2000 t1=1.2000 t4=1.1000 t6=1.0000 t8=1.0000 t10=1.1000 t12=1.2000 down=true up=true "
           "bounded=true reduce=1.0000 nozoom=1.0000 nomove=1.0000 odd=true"),
}

DRIVER = r"""import Foundation

func f(_ v: Double) -> String { String(format: "%.4f", v) }
func all(_ c: ThemeSlideshowClock) -> String {
    "\(c.interval)/\(c.fadeSeconds)/\(c.zoom)/\(c.zoomInterval)/\(c.zoomMove)/\(c.holdSeconds)"
}

// C1
let c = ThemeSlideshowClock(count: 15, interval: 10, fade: 1.5, zoom: 0, zoomInterval: 0, zoomMove: 0)
let long = ThemeSlideshowClock(count: 15, interval: 10, fade: 12, zoom: 0, zoomInterval: 0, zoomMove: 0)
let nan = ThemeSlideshowClock(count: 2, interval: .nan, fade: .nan, zoom: .nan, zoomInterval: .nan, zoomMove: .nan)
let inf = ThemeSlideshowClock(count: 2, interval: .infinity, fade: -.infinity, zoom: .infinity,
                              zoomInterval: .infinity, zoomMove: -.infinity)
let neg = ThemeSlideshowClock(count: -3, interval: -1, fade: -1, zoom: -1, zoomInterval: -1, zoomMove: -1)
print("C1 hold=\(c.holdSeconds) fade=\(c.fadeSeconds) fadelong=\(long.holdSeconds) nan=\(all(nan)) inf=\(all(inf)) neg=\(all(neg)) count=\(c.count)/\(neg.count)")

// C2
let firsts = [0, 0.5, 0.999].map { String(c.firstIndex(random: $0)) }.joined(separator: ",")
let highs = [1.0, 5.0, Double.nan].map { String(c.firstIndex(random: $0)) }.joined(separator: ",")
let one = ThemeSlideshowClock(count: 1, interval: 10, fade: 1.5, zoom: 0, zoomInterval: 0, zoomMove: 0)
let none = ThemeSlideshowClock(count: 0, interval: 10, fade: 1.5, zoom: 0, zoomInterval: 0, zoomMove: 0)
print("C2 first=\(firsts) high=\(highs) next=\(c.next(0)),\(c.next(14)) plays=\(c.plays)/\(one.plays)/\(none.plays) empty=\(none.firstIndex(random: 0.5))/\(none.next(3))")

// C3
let z = ThemeSlideshowClock(count: 15, interval: 10, fade: 1.5, zoom: 0.2, zoomInterval: 6, zoomMove: 4)
func s(_ t: Double) -> Double { z.zoomScale(at: t, reduceMotion: false) }
let samples = stride(from: 0.0, through: 24.0, by: 0.01).map(s)
var down = true
var t = 2.0
while t < 6.0 { if s(t + 0.01) > s(t) + 1e-12 { down = false }; t += 0.01 }
var up = true
t = 8.0
while t < 12.0 { if s(t + 0.01) < s(t) - 1e-12 { up = false }; t += 0.01 }
let bounded = samples.allSatisfy { $0 >= 1 - 1e-12 && $0 <= 1.2 + 1e-12 }
let noZoom = ThemeSlideshowClock(count: 15, interval: 10, fade: 1.5, zoom: 0, zoomInterval: 6, zoomMove: 4)
let noMove = ThemeSlideshowClock(count: 15, interval: 10, fade: 1.5, zoom: 0.2, zoomInterval: 6, zoomMove: 0)
let odd = [Double.nan, .infinity, -.infinity, -5, 1e300, Double.greatestFiniteMagnitude].allSatisfy { s($0).isFinite }
print("C3 t0=\(f(s(0))) t1=\(f(s(1))) t4=\(f(s(4))) t6=\(f(s(6))) t8=\(f(s(8))) t10=\(f(s(10))) t12=\(f(s(12))) down=\(down) up=\(up) bounded=\(bounded) reduce=\(f(z.zoomScale(at: 1, reduceMotion: true))) nozoom=\(f(noZoom.zoomScale(at: 1, reduceMotion: false))) nomove=\(f(noMove.zoomScale(at: 1, reduceMotion: false))) odd=\(odd)")
"""


def slideshow_problems(code_raw):
    """code_raw: ThemeSlideshowClock.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: ThemeSlideshowClock.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: ThemeSlideshowClock.swift logs")
    return problems


class TheClockHolds(unittest.TestCase):
    """C1-C3, one exact line per case."""

    @classmethod
    def setUpClass(cls):
        read(CLOCK)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([CLOCK], DRIVER, tmp)
        cls.lines = {}
        for text in out.splitlines():
            if text.strip():
                key, _, rest = text.partition(" ")
                cls.lines[key] = rest

    def check(self, key):
        self.assertEqual(EXPECTED[key], self.lines.get(key))

    def test_c1_hold_and_sanitised_inputs(self):
        self.check("C1")

    def test_c2_first_and_next(self):
        self.check("C2")

    def test_c3_zoom(self):
        self.check("C3")


class TheRealFiles(unittest.TestCase):
    def test_real_file_has_no_problems(self):
        self.assertEqual([], slideshow_problems(read(CLOCK)))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(CLOCK.parent / "NoSuchClock.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(CLOCK)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = slideshow_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_swiftui(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport SwiftUI")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")


if __name__ == "__main__":
    unittest.main()
