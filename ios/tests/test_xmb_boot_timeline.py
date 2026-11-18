#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbBootTimeline.swift compiled on the Mac host: the boot's levels at the start, the middle of each ramp and after
the end, the light that sweeps the logo, and the levels of leaving XMB mode."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "XmbBootTimeline.swift"]

DRIVER = r"""
import Foundation

func f(_ x: Double) -> String { String(format: "%.4f", x) }
typealias B = XmbBootTimeline
typealias S = XmbShutdownTimeline

for t in [0.0, 0.625, 1.25, 2.375, 4.5, 5.0, 6.05, 6.5, 9.0] {
    print("B t=\(t) black=\(f(B.black(t))) wave=\(f(B.wave(t))) color=\(f(B.color(t))) sparkles=\(f(B.sparkles(t))) "
        + "logo=\(f(B.logoAlpha(t))) reveal=\(f(B.reveal(t)))")
}
for t in [1.9, 2.375, 3.0, 5.0, 5.3] {
    guard let light = B.light(t) else { print("L t=\(t) dark"); continue }
    print("L t=\(t) x=\(f(light.x)) y=\(f(light.y)) reach=\(f(light.reach)) "
        + "alphas=\(f(light.alphas[0])),\(f(light.alphas[4])),\(f(light.alphas[8]))")
}
print("K end=\(B.end) sound=\(B.soundStart) skip=\(B.skipTo) rise=\(B.revealRise) plane=\(B.logoPlaneWidth)")
for t in [0.0, 0.6, 0.65, 1.2, 1.3, 2.0] {
    print("S t=\(t) sink=\(f(S.sink(t))) dark=\(f(S.dark(t)))")
}
print("S lift=\(f(S.lift(0))),\(f(S.lift(0.35))),\(f(S.lift(0.7))) "
    + "exit=\(S.exitAt) intro=\(S.introAt) limit=\(S.introLimit)")
print("E \(f(S.fastOutSlowIn(0.25))) \(f(S.fastOutSlowIn(0.5))) \(f(S.fastOutSlowIn(0.75)))")
"""

EXPECTED = [
    "B t=0.0 black=1.0000 wave=0.0000 color=0.0000 sparkles=0.0000 logo=0.0000 reveal=0.0000",
    "B t=0.625 black=0.5000 wave=0.0197 color=0.0000 sparkles=0.0000 logo=0.0000 reveal=0.0000",
    "B t=1.25 black=0.0000 wave=0.5000 color=0.0000 sparkles=0.0000 logo=0.0000 reveal=0.0000",
    "B t=2.375 black=0.0000 wave=1.0000 color=0.0000 sparkles=0.0000 logo=1.0000 reveal=0.0000",
    "B t=4.5 black=0.0000 wave=1.0000 color=0.5000 sparkles=0.0000 logo=1.0000 reveal=0.0000",
    "B t=5.0 black=0.0000 wave=1.0000 color=0.6836 sparkles=0.0000 logo=0.5000 reveal=0.0000",
    "B t=6.05 black=0.0000 wave=1.0000 color=0.9649 sparkles=0.5000 logo=0.0000 reveal=0.5000",
    "B t=6.5 black=0.0000 wave=1.0000 color=1.0000 sparkles=1.0000 logo=0.0000 reveal=1.0000",
    "B t=9.0 black=0.0000 wave=1.0000 color=1.0000 sparkles=1.0000 logo=0.0000 reveal=1.0000",
    "L t=1.9 dark",
    "L t=2.375 x=-1.5000 y=0.5000 reach=4.8039 alphas=1.0000,0.0379,0.0100",
    "L t=3.0 x=0.0000 y=0.5000 reach=31.1109 alphas=1.0000,0.0387,0.0100",
    "L t=5.0 x=1.1250 y=1.2500 reach=4.8039 alphas=1.0000,0.0379,0.0100",
    "L t=5.3 dark",
    "K end=6.5 sound=0.0 skip=5.25 rise=24.0 plane=2.56",
    "S t=0.0 sink=1.0000 dark=0.0000",
    "S t=0.6 sink=0.5000 dark=0.7223",
    "S t=0.65 sink=0.4583 dark=0.7756",
    "S t=1.2 sink=0.0000 dark=0.9967",
    "S t=1.3 sink=0.0000 dark=1.0000",
    "S t=2.0 sink=0.0000 dark=1.0000",
    "S lift=1.0000,0.2244,0.0000 exit=1.3 intro=2.0 limit=12.0",
    "E 0.2366 0.7756 0.9594",
]


class XmbBootTimelineHost(unittest.TestCase):
    def test_levels(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = compile_and_run(SOURCES, DRIVER, Path(tmp))
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
