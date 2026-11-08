#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The gamepad mapping Swift sources compiled and run on the Mac host: button, stick and trigger
mapping, dead zones and the per-slot state, checked against fixed expected lines.
"""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS = Path(__file__).resolve().parents[1]

APP = IOS / "app"

MAPPING = APP / "Controllers" / "GamepadMapping.swift"

FIXTURE = IOS / "tests" / "fixtures" / "input-config-dump.json"

# Written into a temporary directory at run time; @FIXTURE@ becomes the input-config dump fixture as a Swift raw string.
DRIVER = r"""
func line(_ name: String, _ r: [String: Float], _ m: PlayerMapping = PlayerMapping()) {
    let s = GamepadMapping.snapshot(reading: r, mapping: m)
    print("\(name) mask=\(s.mask) lx=\(s.lx) ly=\(s.ly) rx=\(s.rx) ry=\(s.ry) l2=\(s.l2) r2=\(s.r2)")
}
line("M1", [:])
line("M2a", ["Button A": 1])
line("M2home", ["Button Home": 1])
line("M2menu", ["Button Menu": 1])
line("M2options", ["Button Options": 1])
line("M3up", ["Left Thumbstick Right": 1, "Left Thumbstick Up": 1])
line("M3down", ["Left Thumbstick Left": 1, "Left Thumbstick Down": 1])
line("M4half", ["Left Trigger": 0.5])
line("M4zero", ["Left Trigger": 0])
let p: [String: Float] = ["Right Thumbstick Right": 0.5, "Right Thumbstick Down": 0.25]
let q: [String: Float] = ["Right Thumbstick Right": 0.501, "Right Thumbstick Down": 0.249]
print("M5 same=\(GamepadMapping.snapshot(reading: p, mapping: PlayerMapping()) == GamepadMapping.snapshot(reading: q, mapping: PlayerMapping())) differ=\(p != q)")
line("E", ["Left Thumbstick Right": .nan, "Right Thumbstick Up": .infinity])

var k1 = PlayerMapping()
k1.bindings["Cross"] = "Button B"
k1.bindings["Circle"] = "Button A"
print("K1 cross=\(GamepadMapping.snapshot(reading: ["Button B": 1], mapping: k1).mask)")

let fixture: String = #"@FIXTURE@"#
let notJSON: String = "not json"
let decoded = PlayerMapping.decode(fixture.data(using: .utf8))
let garbage = PlayerMapping.decode(notJSON.data(using: .utf8))
let k11 = decoded.count == 4 && decoded[0].source("Cross") == "Button B" ? 1 : 0
print("K11 decoded=\(k11) garbage=\(garbage.filter { $0 == PlayerMapping() }.count)")

let k2 = PlayerMapping().binding("Cross", to: "Button B")
print("K2 cross=\(k2.source("Cross")) circle=\(k2.source("Circle"))")
let k3 = PlayerMapping().setting(invert: .leftY, true)
print("K3 ly=\(GamepadMapping.snapshot(reading: ["Left Thumbstick Up": 1], mapping: k3).ly)")
let k4 = GamepadMapping.snapshot(reading: ["Left Thumbstick Right": 1], mapping: PlayerMapping().swappingSticks())
print("K4 rx=\(k4.rx) lx=\(k4.lx)")
var k5 = PlayerMapping()
k5.leftDeadzone = 64
let k5at = GamepadMapping.snapshot(reading: ["Left Thumbstick Right": Float(64) / 127.5], mapping: k5).lx
let k5above = GamepadMapping.snapshot(reading: ["Left Thumbstick Right": Float(65) / 127.5], mapping: k5).lx
print("K5 at=\(k5at) above=\(k5above)")
var k6 = PlayerMapping()
k6.leftMultiplier = 50
print("K6 lx=\(GamepadMapping.snapshot(reading: ["Left Thumbstick Right": 1], mapping: k6).lx)")
var k7stored = PlayerMapping()
k7stored.leftMultiplier = 900
var k7limit = PlayerMapping()
k7limit.leftMultiplier = 200
let r7: [String: Float] = ["Left Thumbstick Right": 0.3]
let k7same = GamepadMapping.snapshot(reading: r7, mapping: k7stored) == GamepadMapping.snapshot(reading: r7, mapping: k7limit)
print("K7 clamp dz=\(PlayerMapping.clamped(deadzone: 400)) mul=\(k7same ? PlayerMapping.clamped(multiplier: 900) : -1)")
let k8a = PlayerMapping().swappingSticks().setting(invert: .leftY, true)
let k8b = PlayerMapping().setting(invert: .leftY, true).swappingSticks()
print("K8 commute=\(k8a == k8b && k8a.invertLeftY && k8a.sticksSwapped)")
let k9 = PlayerMapping().binding("Cross", to: "Button Menu")
let r9: [String: Float] = ["Button Menu": 1, "Right Trigger": 1]
let held = GamepadMapping.withholding(PlayerMapping.sources(forMask: 4096 | 2048), from: r9)
print("K9 unmasked=\(GamepadMapping.snapshot(reading: r9, mapping: k9).mask) masked=\(GamepadMapping.snapshot(reading: held, mapping: k9).mask)")
var k10 = PlayerMapping()
for key in PlayerMapping.bindingKeys { k10.bindings[key] = "" }
print("K10 reset=\(PlayerMapping() == k10 && PlayerMapping().bindings.isEmpty && k2 != PlayerMapping())")
let k12key = PlayerMapping.keyboardDefaults["Cross"] ?? ""
print("K12 kb=\(GamepadMapping.snapshot(reading: [k12key: 1], mapping: PlayerMapping(), keyboard: true).mask)")
var k13 = PlayerMapping()
k13.bindings["Cross"] = "Keyboard 4"
print("K13 kbsource=\(k13.source("Cross", keyboard: true))")
let k14all = Set(PlayerMapping.keyboardDefaults.keys) == Set(PlayerMapping.bindingKeys) ? 1 : 0
print("K14 kbfallback=\(k1.source("Cross", keyboard: true)) all=\(k14all)")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


class TheMappingHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(MAPPING)
        fixture = read(FIXTURE).strip()
        if '"#' in fixture:
            raise AssertionError("the fixture cannot be embedded as a Swift raw string")
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([MAPPING], DRIVER.replace("@FIXTURE@", fixture), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_m1_all_released_is_mask_0_sticks_centred(self):
        self.check("M1", "M1 mask=0 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")

    def test_m2_a_home_menu_options_are_cross_ps_start_select(self):
        self.check("M2a", "M2a mask=16 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")
        self.check("M2home", "M2home mask=65536 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")
        self.check("M2menu", "M2menu mask=4096 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")
        self.check("M2options", "M2options mask=8192 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")

    def test_m3_left_stick_full_range_with_y_inverted(self):
        self.check("M3up", "M3up mask=0 lx=255 ly=0 rx=128 ry=128 l2=0 r2=0")
        self.check("M3down", "M3down mask=0 lx=0 ly=255 rx=128 ry=128 l2=0 r2=0")

    def test_m4_trigger_value_sets_l2_bit_and_pressure(self):
        self.check("M4half", "M4half mask=1024 lx=128 ly=128 rx=128 ry=128 l2=128 r2=0")
        self.check("M4zero", "M4zero mask=0 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")

    def test_m5_less_than_one_byte_step_is_the_same_snapshot(self):
        self.check("M5", "M5 same=true differ=true")

    def test_edge_a_non_finite_axis_reads_as_centred(self):
        self.check("E", "E mask=0 lx=128 ly=128 rx=128 ry=128 l2=0 r2=0")

    def test_k1_a_stored_binding_moves_cross(self):
        self.check("K1", "K1 cross=16")

    def test_k11_the_fixture_decodes_and_garbage_gives_defaults(self):
        self.check("K11", "K11 decoded=1 garbage=4")

    def test_k2_a_rebind_swaps_with_the_displaced_key(self):
        self.check("K2", "K2 cross=Button B circle=Button A")

    def test_k3_an_inverted_y_reads_up_as_down(self):
        self.check("K3", "K3 ly=255")

    def test_k4_swapped_sticks_drive_the_other_stick(self):
        self.check("K4", "K4 rx=255 lx=128")

    def test_k5_at_the_deadzone_is_centred_one_step_above_is_not(self):
        line = self.lines.get("K5") or ""
        self.assertTrue(line.startswith("K5 at=128 above="), line)
        self.assertNotEqual("128", line.rsplit("=", 1)[-1], line)

    def test_k6_sensitivity_scales_the_stick(self):
        self.check("K6", "K6 lx=191")

    def test_k7_stored_numbers_clamp(self):
        self.check("K7", "K7 clamp dz=255 mul=200")

    def test_k8_swap_and_invert_commute(self):
        self.check("K8", "K8 commute=true")

    def test_k9_a_chord_ignores_the_remap(self):
        self.check("K9", "K9 unmasked=2064 masked=0")

    def test_k10_the_default_mapping_is_the_reset(self):
        self.check("K10", "K10 reset=true")

    def test_k12_a_keyboard_default_key_presses_cross(self):
        self.check("K12", "K12 kb=16")

    def test_k13_a_stored_key_wins_over_the_keyboard_default(self):
        self.check("K13", "K13 kbsource=Keyboard 4")

    def test_k14_a_stored_element_is_no_key_and_the_defaults_cover_every_key(self):
        self.check("K14", "K14 kbfallback=Keyboard 27 all=1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
