#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""EdgeAnim.swift, run on the Mac host over built rigs, decodes skeletons and clips and samples poses as Android's
EdgeAnim.kt does, and refuses truncated or flipped rig bytes without a trap.
It says nothing about real Sony rigs or a device."""

import json
import math
import re
import struct
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import ea03_bend, ea03_empty, es01

IOS = Path(__file__).resolve().parents[1]
BYTES = IOS / "app" / "Themes" / "ThemeBytes.swift"
EDGE = IOS / "app" / "Themes" / "EdgeAnim.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
SUBSCRIPT = re.compile(r"\b(b|bytes|data|raw)\[")
CAPS = (r"\bMAX_JOINTS\s*=\s*256\b", r"\bMAX_FRAMES\s*=\s*1\s*<<\s*16\b", r"\bMAX_CHANNELS\s*=\s*1024\b")

REST3 = [0, 0, 0, 1, 0, 0, 0, 1, 1, 1,
         0, 0, 0, 1, 2, 0, 0, 1, 1, 1,
         0, 0, 0.6, 0.8, 0, 3, 0, 2, 2, 2]

# (key, sign bits, exponent bits, mantissa bits, stored bits): EdgeAnimTest.kt's `read` calls, then Swift-only.
COMPONENTS = [
    ("u1on", 0, 0, 1, "1"), ("u1off", 0, 0, 1, "0"), ("u10one", 0, 0, 10, "1111111111"),
    ("u10half", 0, 0, 10, "1000000000"),
    ("s10half", 1, 0, 10, "01000000000"), ("s10min", 1, 0, 10, "10000000000"), ("s10tiny", 1, 0, 10, "11111111111"),
    ("s0zero", 1, 0, 0, "0"), ("s0one", 1, 0, 0, "1"),
    ("f25", 0, 3, 14, "111" + "10010000000000"), ("f1", 0, 1, 10, "0" + "0000000000"),
    ("f2", 0, 1, 10, "1" + "0000000000"), ("fneg2", 1, 5, 14, "1" + "10000" + "00000000000000"),
    ("none", 0, 0, 0, ""),
    ("swiftS31", 1, 0, 31, "1" * 32), ("swiftU31", 0, 0, 31, "1" * 31),
]


def inputs():
    skeleton = es01([-1, 0, 1], REST3)
    nan_rest = list(REST3)
    nan_rest[14] = float("nan")
    many = 257
    return {
        "skel3": skeleton,
        "skel257": es01([-1] * many, [0, 0, 0, 1, 0, 0, 0, 1, 1, 1] * many),
        "skelParentAfter": es01([-1, 1, 0], REST3),
        "skelNaN": es01([-1, 0, 1], nan_rest),
        "zeros40": bytes(0x40),
        "zeros80": bytes(0x80),
        "empty": ea03_empty(30.0),
        "bend": ea03_bend(),
        "inv47": bytes(47),
        "inv48": struct.pack(">12f", 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0),
    }


DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }
func fl(_ v: [Float]) -> String { v.map { $0.isNaN ? "nan" : String(describing: $0) }.joined(separator: ",") }

func bitsOf(_ s: String) -> EdgeAnim.Bits {
    var out = [UInt8](repeating: 0, count: (s.count + 7) / 8 + 1)
    for (i, c) in s.enumerated() where c == "1" { out[i / 8] |= UInt8(0x80 >> (i % 8)) }
    return EdgeAnim.Bits(out, 0)
}

for (key, s, e, m, stored) in @@COMPONENTS@@ {
    var bits = bitsOf(stored)
    let v = (try? EdgeAnim.readComponent(&bits, field: s << 9 | e << 5 | m)).map { fl([$0]) } ?? "threw"
    print("RC \(key) \(v)")
}

let arm = EdgeAnim.Skeleton(parents: [-1, 0], rest: [0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 2, 0, 0, 1, 1, 1])
func turn(_ angle: Double) -> [Float] { [0, 0, Float(sin(angle / 2)), Float(cos(angle / 2))] }
let bend = EdgeAnim.Clip(duration: 1, frameRate: 10, frames: 11, channels: [
    EdgeAnim.Channel(joint: 1, kind: EdgeAnim.ROTATION, frames: [0, 10], values: turn(0) + turn(.pi / 2)),
])

var pose = arm.rest
EdgeAnim.sample(bend, frame: 5, pose: &pose)
print("VEC sampled " + fl(Array(pose[10 ..< 14])))

pose = arm.rest
EdgeAnim.sample(bend, frame: 10, pose: &pose)
pose[5] = 1
let w = EdgeAnim.worlds(arm, pose: pose)
print("VEC worlds " + fl([w[12 + 3], w[12 + 7], w[12 + 0], w[12 + 4]]))

let inverse: [Float] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, -2, 0, 1, 0, 0, 0, 0, 1, 0]
print("VEC skin " + fl(EdgeAnim.skinMatrices(EdgeAnim.worlds(arm, pose: arm.rest), inverse)))

pose = arm.rest
EdgeAnim.sample(bend, frame: 10, pose: &pose, weight: 0.5)
print("VEC partial " + fl(Array(pose[10 ..< 14])))

pose = arm.rest
EdgeAnim.sample(bend, frame: .nan, pose: &pose)
print("VEC nanFrame " + fl(Array(pose[10 ..< 14])))

for key in ["skel3", "skel257", "skelParentAfter", "skelNaN", "zeros40"] {
    if let s = EdgeAnim.skeleton(load(key)) {
        print("SKEL \(key) parents=" + s.parents.map(String.init).joined(separator: ",") + " rest=" + fl(s.rest))
    } else {
        print("SKEL \(key) nil")
    }
}

for (key, joints) in [("inv47", 1), ("inv48", 1), ("inv48", 2), ("inv48", -1)] {
    let v = EdgeAnim.inverseBinds(load(key), joints: joints).map(fl) ?? "nil"
    print("INV \(key)x\(joints) \(v)")
}

for (key, joints) in [("empty", 1), ("zeros80", 4), ("bend", 2), ("bend", 1)] {
    if let c = EdgeAnim.clip(load(key), joints: joints) {
        var parts = ["duration=\(c.duration)", "rate=\(c.frameRate)", "frames=\(c.frames)", "channels=\(c.channels.count)"]
        for (i, ch) in c.channels.enumerated() {
            parts.append("ch\(i)=\(ch.joint)/\(ch.kind)/" + fl(ch.frames) + "/" + fl(ch.values))
        }
        print("CLIP \(key)x\(joints) " + parts.joined(separator: " "))
    } else {
        print("CLIP \(key)x\(joints) nil")
    }
}

func exercise(_ input: [UInt8]) {
    if let s = EdgeAnim.skeleton(input) {
        _ = EdgeAnim.skinMatrices(EdgeAnim.worlds(s, pose: s.rest), s.rest)
    }
    if let c = EdgeAnim.clip(input, joints: 2) {
        var p = arm.rest
        EdgeAnim.sample(c, frame: 3.5, pose: &p, weight: 0.5)
        _ = EdgeAnim.worlds(arm, pose: p)
    }
}

var state: UInt64 = 0x16_03_2026
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
var truncations = 0
var flips = 0
for key in ["skel3", "empty", "bend"] {
    let whole = load(key)
    for n in 0 ... whole.count {
        exercise(Array(whole[0 ..< n]))
        truncations += 1
    }
    for _ in 0 ..< 3000 {
        var copy = whole
        let at = next() % copy.count
        copy[at] ^= UInt8(1 + next() % 255)
        exercise(copy)
        flips += 1
    }
}
print("FUZZ truncations=\(truncations) flips=\(flips) done=1")
"""


def edge_problems(code_raw):
    """code_raw: EdgeAnim.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: EdgeAnim.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: EdgeAnim.swift logs")
    for match in SUBSCRIPT.finditer(code):
        problems.append("subscript: EdgeAnim.swift subscripts `{0}[` directly; read through ThemeBytes.swift".format(
            match.group(1)))
    for cap in CAPS:
        if not re.search(cap, code):
            problems.append("caps: EdgeAnim.swift does not keep the Kotlin cap `{0}`".format(cap))
    return problems


def floats(text):
    return [float(x) for x in text.split(",")]


def f32(value):
    return struct.unpack(">f", struct.pack(">f", value))[0]


def turn(angle):
    return [0.0, 0.0, math.sin(angle / 2), math.cos(angle / 2)]


class TheRigsHold(unittest.TestCase):
    """The ported Kotlin cases, the Swift-only cases and the fuzz; one line per case."""

    @classmethod
    def setUpClass(cls):
        read(BYTES)
        read(EDGE)
        cls.inputs = inputs()
        cls.sizes = {k: len(v) for k, v in cls.inputs.items()}
        with tempfile.TemporaryDirectory() as tmp:
            for key, data in cls.inputs.items():
                (Path(tmp) / key).write_bytes(data)
            components = "[" + ", ".join('("{0}", {1}, {2}, {3}, "{4}")'.format(*c) for c in COMPONENTS) + "]"
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@COMPONENTS@@", components)
            out = compile_and_run([BYTES, EDGE], driver, tmp)
        cls.lines = {}
        for text in out.splitlines():
            parts = text.split(" ", 2)
            if parts[0] in ("RC", "VEC", "SKEL", "INV", "CLIP") and len(parts) == 3:
                cls.lines[parts[0] + " " + parts[1]] = parts[2]
            elif parts[0] == "FUZZ":
                cls.lines["FUZZ"] = text[5:]

    def line(self, key):
        got = self.lines.get(key)
        self.assertIsNotNone(got, key)
        return got

    def component(self, key):
        got = self.line("RC " + key)
        self.assertNotEqual("threw", got, key)
        return float(got)

    def close(self, expected, got, delta):
        self.assertEqual(len(expected), len(got), got)
        for e, g in zip(expected, got):
            self.assertAlmostEqual(e, g, delta=delta)

    def clip(self, key):
        got = self.line("CLIP " + key)
        return None if got == "nil" else dict(part.split("=", 1) for part in got.split(" "))

    def test_unsigned_fixed_point_reaches_one(self):
        self.assertEqual(1.0, self.component("u1on"))
        self.assertEqual(0.0, self.component("u1off"))
        self.assertEqual(1.0, self.component("u10one"))
        self.assertAlmostEqual(0.5, self.component("u10half"), delta=1e-3)

    def test_signed_fixed_point_is_twos_complement(self):
        self.assertEqual(0.5, self.component("s10half"))
        self.assertEqual(-1.0, self.component("s10min"))
        self.assertEqual(-1.0 / 1024, self.component("s10tiny"))
        self.assertEqual(0.0, self.component("s0zero"))
        self.assertEqual(-1.0, self.component("s0one"))

    def test_floats_have_no_denormals(self):
        self.assertEqual(25.0, self.component("f25"))
        self.assertEqual(1.0, self.component("f1"))
        self.assertEqual(2.0, self.component("f2"))
        self.assertEqual(-2.0, self.component("fneg2"))

    def test_nothing_stored_is_nan(self):
        self.assertTrue(math.isnan(self.component("none")))

    def test_sampled_rotations_interpolate(self):
        self.close(turn(math.pi / 4), floats(self.line("VEC sampled")), 1e-4)

    def test_world_matrices_follow_the_parent(self):
        self.close([2.0, 1.0, 0.0, 1.0], floats(self.line("VEC worlds")), 1e-5)

    def test_skinning_the_rest_pose_leaves_the_mesh_alone(self):
        identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0]
        self.close(identity * 2, floats(self.line("VEC skin")), 1e-5)

    def test_partial_weights_blend_toward_the_clip(self):
        self.close(turn(math.pi / 4), floats(self.line("VEC partial")), 1e-4)

    def test_a_clip_with_no_channels_holds_the_rest_pose(self):
        clip = self.clip("emptyx1")
        self.assertIsNotNone(clip)
        self.assertEqual("0", clip["channels"])

    def test_not_a_clip_is_refused(self):
        self.assertIsNone(self.clip("zeros80x4"))
        self.assertEqual("nil", self.line("SKEL zeros40"))

    def test_swift_mantissa_field_of_31_does_not_trap(self):
        # Kotlin's Int: (1 shl 31) or 0x7FFFFFFF is -1, minus 1 shl 32 masked to 1 is -2, over 1 shl 31; compared as Float.
        self.assertEqual(f32(-2.0 / -2147483648.0), f32(self.component("swiftS31")))
        # 0x7FFFFFFF over (1 shl 31) - 1, which wraps to Int.MAX_VALUE.
        self.assertEqual(1.0, self.component("swiftU31"))

    def test_swift_nan_frame_takes_key_zero(self):
        self.close(turn(0.0), floats(self.line("VEC nanFrame")), 1e-6)

    def test_swift_skeletons_decode_and_bad_ones_are_refused(self):
        got = self.line("SKEL skel3")
        self.assertNotEqual("nil", got)
        parts = dict(part.split("=", 1) for part in got.split(" "))
        self.assertEqual("-1,0,1", parts["parents"])
        self.close(REST3, floats(parts["rest"]), 1e-6)
        for key in ("skel257", "skelParentAfter", "skelNaN"):
            with self.subTest(key):
                self.assertEqual("nil", self.line("SKEL " + key))

    def test_swift_short_inverse_binds_are_nil(self):
        self.assertEqual("nil", self.line("INV inv47x1"))
        self.close([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0], floats(self.line("INV inv48x1")), 0)
        self.assertEqual("nil", self.line("INV inv48x2"))
        self.assertEqual("nil", self.line("INV inv48x-1"))

    def test_swift_a_clip_with_channels_decodes(self):
        clip = self.clip("bendx2")
        self.assertIsNotNone(clip)
        self.assertEqual(("1.0", "10.0", "11", "2"), (clip["duration"], clip["rate"], clip["frames"], clip["channels"]))
        joint, kind, frames, values = clip["ch0"].split("/")
        self.assertEqual(("1", "1", "0.0"), (joint, kind, frames))
        self.assertEqual("1.0", values.split(",")[0])
        self.assertTrue(all(v == "nan" for v in values.split(",")[1:]), values)
        joint, kind, frames, values = clip["ch1"].split("/")
        self.assertEqual(("1", "0"), (joint, kind))
        self.assertEqual([0.0, 5.0, 9.0], floats(frames))
        self.close([0, 0, 0, 1, 0, 0, 0.5, math.sqrt(0.75), 0, 0, 0.25, math.sqrt(1 - 0.0625)], floats(values), 1e-6)
        dropped = self.clip("bendx1")
        self.assertIsNotNone(dropped)
        self.assertEqual("0", dropped["channels"])

    def test_fuzz_truncations_and_flips_never_trap(self):
        cuts = sum(self.sizes[k] + 1 for k in ("skel3", "empty", "bend"))
        self.assertEqual("truncations={0} flips=9000 done=1".format(cuts), self.lines.get("FUZZ"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], edge_problems(read(EDGE)))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(EDGE)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = edge_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_subscript(self):
        self.flagged("subscript", "let r = ByteReader(b)", "let r = ByteReader(b); _ = b[0]")

    def test_mutation_caps(self):
        self.flagged("caps", "MAX_JOINTS = 256", "MAX_JOINTS = 4096")


if __name__ == "__main__":
    unittest.main()
