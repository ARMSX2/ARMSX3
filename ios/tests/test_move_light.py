# SPDX-License-Identifier: GPL-2.0-only
"""The Move sphere colour map in MotionMath.swift and GamepadMapping.swift, compiled with swiftc on the host.
It asserts the default, clamp, NaN, black, dim and under-threshold cases as exact output lines.
"""

import tempfile
import unittest
from pathlib import Path

from source_helpers import read
from swiftc_host import compile_and_run


IOS = Path(__file__).resolve().parents[1]

APP = IOS / "app"

MOTION = APP / "Controllers" / "MotionMath.swift"

MAPPING = APP / "Controllers" / "GamepadMapping.swift"

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
func show(_ name: String, _ c: (r: Float, g: Float, b: Float)?) {
    if let c { print("\(name)=\(c.r),\(c.g),\(c.b)") } else { print("\(name)=nil") }
}
show("L1 green", MotionMath.sphereColor(r: 0, g: 1, b: 0))
show("L2 clamp", MotionMath.sphereColor(r: 1.5, g: -0.5, b: 0.25))
show("L3 nan", MotionMath.sphereColor(r: .nan, g: 0, b: 0))
show("L4 black", MotionMath.sphereColor(r: 0, g: 0, b: 0))
show("L5 dim", MotionMath.sphereColor(r: 0.01, g: 0, b: 0))
show("L6 under", MotionMath.sphereColor(r: 0.003, g: 0.002, b: 0.001))
"""


class TheSphereColourMaps(unittest.TestCase):
    """A macOS host run of pure Swift, never device evidence.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    lines = None

    @classmethod
    def setUpClass(cls):
        read(MOTION)
        read(MAPPING)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([MOTION, MAPPING], DRIVER, tmp)
        cls.lines = {line.split("=", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_l1_the_default_green_passes(self):
        self.check("L1 green", "L1 green=0.0,1.0,0.0")

    def test_l2_each_channel_is_clamped(self):
        self.check("L2 clamp", "L2 clamp=1.0,0.0,0.25")

    def test_l3_a_nan_shows_the_slot_colour(self):
        self.check("L3 nan", "L3 nan=nil")

    def test_l4_black_shows_the_slot_colour(self):
        self.check("L4 black", "L4 black=nil")

    def test_l5_a_dim_colour_is_kept(self):
        self.check("L5 dim", "L5 dim=0.01,0.0,0.0")

    def test_l6_under_the_threshold_shows_the_slot_colour(self):
        self.check("L6 under", "L6 under=nil")


if __name__ == "__main__":
    unittest.main(verbosity=2)
