#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The motion mapping Swift source compiled and run on the Mac host: tilt to stick values, recentring,
the touch-pad fold and phone tilt shaping, checked against fixed expected lines.
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
import Foundation

func line(_ name: String, _ s: Sixaxis) {
    print("\(name)=\(s.x),\(s.y),\(s.z),\(s.g)")
}
func line2(_ s: Sixaxis) -> String { "\(s.x),\(s.y),\(s.z)" }
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
let pitchedForward = MotionMath.sixaxis(ax: 0, ay: -1, az: 1, yaw: 0).applying(invertX: false, invertY: true)
print("S9 invertY=\(pitchedForward.x),\(pitchedForward.y),\(pitchedForward.z)")

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

func play(_ p: MotionPlayback, _ now: Double) -> String {
    let s = p.sample(at: now)
    let pad = MotionMath.sixaxis(ax: s.0, ay: s.1, az: s.2, yaw: s.3)
    return "\(pad.x),\(pad.y),\(pad.z),\(pad.g)"
}
var idle = MotionPlayback()
idle.update(pressed: [], at: 0)
print("M1 idle=\(idle.active(at: 0))")
var tilt = MotionPlayback()
tilt.update(pressed: [.tiltLeft], at: 0)
print("M2 tiltLeft=\(play(tilt, 0))")
tilt.update(pressed: [.tiltForward], at: 0)
print("M3 tiltForward=\(play(tilt, 0))")
var turn = MotionPlayback()
turn.update(pressed: [.turnLeft], at: 0)
print("M4 turnLeft=\(play(turn, 0))")
var flick = MotionPlayback()
flick.update(pressed: [.flickUp], at: 0)
flick.update(pressed: [], at: 0.016)
print("M5 flick=\(play(flick, 0.03))|\(play(flick, 0.1))|\(flick.active(at: 0.1)),\(flick.active(at: 0.2))")
var shake = MotionPlayback()
shake.update(pressed: [.shake], at: 1)
print("M6 shake=\(play(shake, 1.0125))")
let bound = MotionBinding.bind(.shake, to: "Button A", mapping: PlayerMapping(), motions: [:], keyboard: false)
print("M7 bind=\(bound.mapping.source("Cross")),\(bound.motions)")
let again = MotionBinding.bind(.shake, to: "Button A", mapping: bound.mapping, motions: bound.motions, keyboard: false)
print("M8 again=\(again.motions.isEmpty),\(again.mapping.source("Cross"))")
let moved = MotionBinding.bind(.tiltLeft, to: "Button A", mapping: bound.mapping, motions: bound.motions, keyboard: false)
print("M9 moved=\(moved.motions)")
let pressed = MotionBinding.pressed(reading: ["Button A": 0.6, "Right Trigger": 0.4],
                                    bindings: ["Shake": "Button A", "Turn Left": "Right Trigger"])
print("M10 pressed=\(pressed.map(\.rawValue).sorted())")
let store = ["0": ["Shake": "Button X"], "BLUS1/0": ["Turn Left": "Button Y"]]
let own = MotionBinding.resolve(store, serial: "BLUS1", perGame: false, player: 0)
let shared = MotionBinding.resolve(store, serial: "BLUS2", perGame: false, player: 0)
let fileOnly = MotionBinding.resolve(store, serial: "BLUS2", perGame: true, player: 0)
let global = MotionBinding.resolve(store, serial: nil, perGame: false, player: 0)
print("M11 resolve=\(own),\(shared),\(fileOnly),\(global)")
print("M12 keyboardNone=\(bound.mapping.source("Cross", keyboard: true))")

var v = MovePointer()
print("V1 centre=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: 1, pitchRate: 0, dt: 0.5)
print("V2 right=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: 1, pitchRate: 0, dt: 10)
print("V3 clamp=\(v.stick.0),\(v.stick.1)")
v.integrate(yawRate: -0.3, pitchRate: 0.7, dt: 1)
v.recentre()
print("V4 recentred=\(v.stick.0),\(v.stick.1)")

func held(_ degrees: Double) -> (Double, Double, Double) {
    let a = degrees * .pi / 180
    return (0, -cos(a), -sin(a))
}
func pad(_ v: (Double, Double, Double)) -> Sixaxis { MotionMath.sixaxis(ax: v.0, ay: v.1, az: v.2, yaw: 0) }
let still: (Double, Double, Double) = (0, 0, 0)
let grip = MotionMath.gripPitch(gravity: held(40))
print("T1 grip=\(Int((grip * 180 / .pi).rounded())),\(line2(pad(MotionMath.shaped(gravity: held(40), movement: still, grip: grip, sensitivity: 100, tremor: MotionMath.tremor))))")
print("T2 fromGrip=\(line2(pad(MotionMath.shaped(gravity: held(50), movement: still, grip: grip, sensitivity: 100, tremor: MotionMath.tremor))))")
print("T3 tremor=\(line2(pad(MotionMath.shaped(gravity: held(0), movement: (0.1, 0, 0), grip: 0, sensitivity: 100, tremor: MotionMath.tremor))))")
print("T4 shake=\(line2(pad(MotionMath.shaped(gravity: held(0), movement: (0.5, 0, 0), grip: 0, sensitivity: 100, tremor: MotionMath.tremor))))")
print("T5 controller=\(line2(pad(MotionMath.shaped(gravity: held(0), movement: (0.1, 0, 0), grip: 0, sensitivity: 100, tremor: 0))))")
print("T6 doubled=\(line2(pad(MotionMath.shaped(gravity: held(60), movement: still, grip: grip, sensitivity: 200, tremor: MotionMath.tremor))))")
print("T7 stopped=\(line2(pad(MotionMath.shaped(gravity: held(100), movement: still, grip: grip, sensitivity: 200, tremor: MotionMath.tremor))))")
let roll = 20.0 * .pi / 180
print("T8 roll=\(line2(pad(MotionMath.shaped(gravity: (sin(roll), -cos(roll), 0), movement: still, grip: 0, sensitivity: 200, tremor: MotionMath.tremor))))")
print("T9 unscaled=\(line2(pad(MotionMath.shaped(gravity: held(0), movement: (0.5, 0, 0), grip: 0, sensitivity: 200, tremor: MotionMath.tremor))))")
print("T10 identity=\(line2(pad(MotionMath.shaped(gravity: held(120), movement: still, grip: 0, sensitivity: 100, tremor: MotionMath.tremor))))")
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

    def test_s9_invert_y_mirrors_forward_and_back_and_leaves_up(self):
        self.check("S9 invertY", "S9 invertY=512,399,399")

    def test_g1_no_title_entry_no_steering(self):
        self.check("G1 off", "G1 off=128,128")

    def test_g2_one_rad_a_second_steers_right_of_centre(self):
        self.check("G2 on", "G2 on=174,128")

    def test_g3_a_full_stick_plus_gyro_stays_at_255(self):
        self.check("G3 clamp", "G3 clamp=255")

    def test_g4_the_gyro_is_added_after_the_deadzone(self):
        self.check("G4 order", "G4 order=true")

    # Motion buttons: Android's gestures, one action per element, and where the bindings are read from.
    def test_m1_nothing_held_plays_nothing(self):
        self.check("M1 idle", "M1 idle=false")

    def test_m2_tilt_left_rolls_50_degrees(self):
        self.check("M2 tiltLeft", "M2 tiltLeft=425,439,512,512")

    def test_m3_tilt_forward_pitches_50_degrees(self):
        self.check("M3 tiltForward", "M3 tiltForward=512,439,425,512")

    def test_m4_turn_left_is_3_rad_a_second(self):
        self.check("M4 turnLeft", "M4 turnLeft=512,399,512,277")

    def test_m5_a_flick_pushes_then_stops_and_ends_after_140_ms(self):
        self.check("M5 flick", "M5 flick=512,117,512,512|512,602,512,512|true,false")

    def test_m6_a_shake_wobbles_x_at_2_5_g(self):
        self.check("M6 shake", "M6 shake=312,399,512,512")

    def test_m7_a_motion_takes_its_element_from_a_button(self):
        self.check("M7 bind", 'M7 bind=None,["Shake": "Button A"]')

    def test_m8_binding_the_same_element_again_clears_it(self):
        self.check("M8 again", "M8 again=true,None")

    def test_m9_a_motion_takes_its_element_from_another_motion(self):
        self.check("M9 moved", 'M9 moved=["Tilt Left": "Button A"]')

    def test_m10_held_past_halfway_plays(self):
        self.check("M10 pressed", 'M10 pressed=["Shake"]')

    def test_m11_a_title_set_wins_and_a_title_file_hides_the_shared_set(self):
        self.check("M11 resolve", 'M11 resolve=["Turn Left": "Button Y"],["Shake": "Button X"],[:],["Shake": "Button X"]')

    def test_m12_none_holds_for_the_keyboard_too(self):
        self.check("M12 keyboardNone", "M12 keyboardNone=None")

    # The PS Move's pointer.
    def test_v1_a_fresh_pointer_is_centred(self):
        self.check("V1 centre", "V1 centre=128,128")

    def test_v2_a_turn_right_moves_the_pointer_right(self):
        self.check("V2 right", "V2 right=173,128")

    def test_v3_a_long_turn_clamps(self):
        self.check("V3 clamp", "V3 clamp=255,128")

    def test_v4_recentre_puts_it_back(self):
        self.check("V4 recentred", "V4 recentred=128,128")

    # Phone tilt: the grip, the tremor drop and sensitivity.
    def test_t1_the_grip_reads_as_a_flat_ds3(self):
        self.check("T1 grip", "T1 grip=40,512,399,512")

    def test_t2_tilt_counts_from_the_grip(self):
        self.check("T2 fromGrip", "T2 fromGrip=512,401,492")

    def test_t3_tremor_is_dropped(self):
        self.check("T3 tremor", "T3 tremor=512,399,512")

    def test_t4_a_shake_passes(self):
        self.check("T4 shake", "T4 shake=569,399,512")

    def test_t5_no_tremor_drop_keeps_small_movement(self):
        self.check("T5 controller", "T5 controller=523,399,512")

    def test_t6_double_sensitivity_doubles_the_pitch(self):
        self.check("T6 doubled", "T6 doubled=512,425,439")

    def test_t7_a_scaled_tilt_stops_at_90_degrees(self):
        self.check("T7 stopped", "T7 stopped=512,512,399")

    def test_t8_double_sensitivity_doubles_the_roll(self):
        self.check("T8 roll", "T8 roll=585,425,512")

    def test_t9_sensitivity_leaves_a_shake_alone(self):
        self.check("T9 unscaled", "T9 unscaled=569,399,512")

    def test_t10_full_sensitivity_changes_nothing_past_90_degrees(self):
        self.check("T10 identity", "T10 identity=512,569,414")


if __name__ == "__main__":
    unittest.main(verbosity=2)
