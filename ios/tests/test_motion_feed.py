#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The motion mapping Swift source compiled and run on the Mac host: tilt to stick values, recentring and
the touch-pad fold, checked against fixed expected lines.
"""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS = Path(__file__).resolve().parents[1]

APP = IOS / "app"

MOTION = APP / "Controllers" / "MotionMath.swift"

MAPPING = APP / "Controllers" / "GamepadMapping.swift"

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
func line(_ name: String, _ s: Sixaxis) {
    print("\(name)=\(s.x),\(s.y),\(s.z),\(s.g)")
}
line("S1 rest", MotionMath.sixaxis(ax: 0, ay: -1, az: 0, yaw: 0))
line("S2 high", MotionMath.sixaxis(ax: 10, ay: 10, az: 10, yaw: 10))
line("S3 low", MotionMath.sixaxis(ax: -10, ay: -10, az: -10, yaw: -10))
line("S4 tilt", MotionMath.sixaxis(ax: 1, ay: -1, az: 0, yaw: 0))
line("S5 nonfinite", MotionMath.sixaxis(ax: .nan, ay: .nan, az: .infinity, yaw: -.infinity))
let up = MotionMath.screenFrame(x: -1, y: 0, z: 0, orientation: .landscapeRight)
line("S6 upright", MotionMath.sixaxis(ax: up.0, ay: up.1, az: up.2, yaw: 0))
let right = MotionMath.screenFrame(x: -1, y: 0.25, z: 0, orientation: .landscapeRight)
let left = MotionMath.screenFrame(x: -1, y: 0.25, z: 0, orientation: .landscapeLeft)
print("S7 rotated=\(MotionMath.sixaxis(ax: right.0, ay: right.1, az: right.2, yaw: 0).x),\(MotionMath.sixaxis(ax: left.0, ay: left.1, az: left.2, yaw: 0).x)")
let flipped = MotionMath.sixaxis(ax: 1, ay: -1, az: 0, yaw: 0).applying(invertX: true, invertY: false)
print("S8 inverted=\(flipped.x),\(flipped.y)")

let centred: (UInt8, UInt8) = (128, 128)
let g1 = MotionMath.steer(stick: centred, rateX: 1, rateY: 1, setting: nil, previous: (0, 0)).stick
print("G1 off=\(g1.0),\(g1.1)")
let g2 = MotionMath.steer(stick: centred, rateX: 1, rateY: 0, setting: GyroStick(target: 1, sensitivity: 100), previous: (0, 0)).stick
print("G2 on=\(g2.0),\(g2.1)")
let g3 = MotionMath.steer(stick: (255, 128), rateX: 1, rateY: 0, setting: GyroStick(target: 0, sensitivity: 300), previous: (0, 0)).stick
print("G3 clamp=\(g3.0)")
let quiet = GamepadMapping.stick(0.1, 0, deadzone: 64, multiplier: 100)
let small = GyroStick(target: 0, sensitivity: 100)
let g4a = MotionMath.steer(stick: quiet, rateX: 0.2, rateY: 0, setting: small, previous: (0, 0)).stick
let g4b = MotionMath.steer(stick: centred, rateX: 0.2, rateY: 0, setting: small, previous: (0, 0)).stick
print("G4 order=\(g4a == g4b && g4b.0 != 128)")

var v = MovePointer()
print("V1 centre=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: 1, pitchRate: 0, dt: 0.5)
print("V2 right=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: 1, pitchRate: 0, dt: 10)
print("V3 clamp=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: -0.3, pitchRate: 0.7, dt: 1)
v.recentre()
print("V4 recentred=\(v.stick.0),\(v.stick.1)")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


class TheMotionHolds(unittest.TestCase):
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

    def test_s1_a_flat_ds3_at_rest(self):
        self.check("S1 rest", "S1 rest=512,399,512,512")

    def test_s2_values_beyond_the_top_clamp_to_1023(self):
        self.check("S2 high", "S2 high=1023,1023,1023,1023")

    def test_s3_values_beyond_the_bottom_clamp_to_0(self):
        self.check("S3 low", "S3 low=0,0,0,0")

    def test_s4_one_g_of_tilt_is_113(self):
        self.check("S4 tilt", "S4 tilt=625,399,512,512")

    def test_s5_non_finite_reads_as_rest(self):
        self.check("S5 nonfinite", "S5 nonfinite=512,399,512,512")

    def test_s6_upright_in_landscape_right_is_a_flat_ds3(self):
        self.check("S6 upright", "S6 upright=512,399,512,512")

    def test_s7_the_other_landscape_mirrors_x(self):
        self.check("S7 rotated", "S7 rotated=484,540")

    def test_s8_invert_x_mirrors_x_and_leaves_y(self):
        self.check("S8 inverted", "S8 inverted=399,399")

    def test_g1_no_title_entry_no_steering(self):
        self.check("G1 off", "G1 off=128,128")

    def test_g2_one_rad_a_second_steers_right_of_centre(self):
        self.check("G2 on", "G2 on=174,128")

    def test_g3_a_full_stick_plus_gyro_stays_at_255(self):
        self.check("G3 clamp", "G3 clamp=255")

    def test_g4_the_gyro_is_added_after_the_deadzone(self):
        self.check("G4 order", "G4 order=true")

    # The PS Move's pointer.
    def test_v1_a_fresh_pointer_is_centred(self):
        self.check("V1 centre", "V1 centre=128,128")

    def test_v2_a_turn_right_moves_the_pointer_right(self):
        self.check("V2 right", "V2 right=173,128")

    def test_v3_a_long_turn_clamps(self):
        self.check("V3 clamp", "V3 clamp=255,128")

    def test_v4_recentre_puts_it_back(self):
        self.check("V4 recentred", "V4 recentred=128,128")


if __name__ == "__main__":
    unittest.main(verbosity=2)
