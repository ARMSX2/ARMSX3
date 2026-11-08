#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RafScene.swift, run on the Mac host over Android's committed mesh hex and built scenes, decodes Edge meshes, collapsed
layers, materials, actors, rigs, camera and lights as Android's RafScene.kt does, and refuses damaged input without a trap.
It says nothing about real theme files, real skinned meshes, or a device."""

import json
import re
import struct
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import RafBuilder, dxt1, ea03_bend, edge_block, es01, gtf, kotlin_hex

IOS = Path(__file__).resolve().parents[1]
REPO = IOS.parent
BYTES = IOS / "app" / "Themes" / "ThemeBytes.swift"
RAF = IOS / "app" / "Themes" / "RafScene.swift"
P3T = IOS / "app" / "Themes" / "P3tTheme.swift"
ANIM = IOS / "app" / "Themes" / "P3tAnimation.swift"
EDGE = IOS / "app" / "Themes" / "EdgeAnim.swift"
ANDROID_TESTS = REPO / "android" / "armsx3-ui" / "app" / "src" / "test" / "java" / "com" / "armsx2" / "ui" / "home"
RAF_TEST = ANDROID_TESTS / "RafSceneTest.kt"
EDGE_SAMPLES = ANDROID_TESTS / "EdgeSamples.kt"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
SUBSCRIPT = re.compile(r"\b(b|bytes|data|block|raw|stream)\[")
FINAL_CLASS = ("Mesh", "Segment")
SHARED_CLASS = ("Rig", "Actor")
EFFECTS = ("basic_lighting", "basic_lighting_alpha_add", "pure_texture_alpha_1_depth_0", "pure_texture",
           "pure_texture_alpha_0_depth_0", "pure_texture_alpha_1_depth_1")


def smoke_sheet():
    return kotlin_hex(EDGE_SAMPLES, "smokeSheet")


def zeroed(data, start, end, value=0):
    out = bytearray(data)
    for i in range(start, end):
        out[i] = value
    return bytes(out)


def many_segments(count_claimed, copies):
    """The smoke sheet with its one 0x80-byte segment header copied `copies` times onto the end of the file, the
    table pointed there, and the count set to `count_claimed`. Every copy points at the same vertex and index data,
    so each is a valid segment. Padding to room for 65 keeps the size check from refusing a claim of 65."""
    sheet = bytearray(smoke_sheet())
    header = bytes(sheet[0x10:0x90])
    at = len(sheet)
    sheet += header * copies + bytes(0x80 * (65 - copies))
    struct.pack_into(">II", sheet, 0, count_claimed, at)
    return bytes(sheet)


def index_cases():
    """{key: (block, indexCount, vertexCount)} for edgeIndices."""
    grid = kotlin_hex(RAF_TEST, "realGrid")
    return {
        "quadSharesAnEdge": (edge_block(0, 0, 1, 0, 0x00, 0xC0), 6, 4),
        "theOtherEdgeCodesKeepTheWinding": (edge_block(0, 0, 1, 0, 0x00, 0xD8), 9, 5),
        "reusedOne": (edge_block(1, 1, 1, 4, 0x10, 0xC0, 0x40), 6, 4),
        "reusedLanes": (edge_block(9, 0, 2, 4, 0xFF, 0x80, 0xFC, 0x12, 0x34, 0x56, 0x78, 0x90), 9, 11),
        "badNoTriangleBefore": (edge_block(0, 0, 1, 0, 0x00, 0x00), 3, 3),
        "badPastTheVertices": (edge_block(0, 0, 1, 0, 0x00, 0xC0), 6, 3),
        "badMoreReadsThanFlags": (edge_block(0, 0, 1, 0, 0x00, 0xFF, 0xF0), 18, 18),
        "badShortHeader": (bytes(5), 3, 3),
        "badMissingReusedValue": (edge_block(3, 0, 1, 16, 0xE0, 0xC0), 3, 3),
        "realGrid": (grid, 96, 25),
        "realBiasedMesh": (kotlin_hex(RAF_TEST, "realBiasedMesh"), 906, 495),
        "swiftOneByteShort": (grid[:-1], 96, 25),
    }


def mesh_cases():
    """{key: mesh bytes} for edgeMesh and collapsed."""
    sheet = smoke_sheet()
    flat = zeroed(sheet, 0x110, 0x140)
    cases = {
        "smokeSheet": sheet,
        "flat": flat,
        "gone": zeroed(flat, 0x100, 0x110),
        "rocks": zeroed(sheet, 0x100, 0x110),
        "damaged": zeroed(sheet, 0x100, 0x110, 0x5A),
        "segments64": many_segments(64, 64),
        "segments65": many_segments(65, 65),
    }
    for n in (0, 0x20, 0x44, 0x100, 0x120, len(sheet) - 60):
        cases["cut{0}".format(n)] = sheet[:n]
    return cases


def skinned_sheet():
    """The smoke sheet made skinned: its one segment (at 0x10) gets a skinning flavour, a palette of joints 0 and 1
    (one run of two 48-byte matrices at +0x34, sizes at +0x38) and 8 skin bytes a vertex (at +0x40, sizes at
    +0x3C), appended to the file: vertices 0 to 2 wholly on palette entry 0, vertex 3 on entry 1."""
    sheet = bytearray(smoke_sheet())
    s = 0x10
    skin_at = len(sheet)
    for v in range(4):
        sheet += bytes([255, 1 if v == 3 else 0, 0, 0, 0, 0, 0, 0])
    sheet[s + 6] = (sheet[s + 6] & 0xF0) | 1
    struct.pack_into(">HHHH", sheet, s + 0x34, 0, 0, 2 * 48, 0)
    struct.pack_into(">HH", sheet, s + 0x3C, 32, 0)
    struct.pack_into(">I", sheet, s + 0x40, skin_at)
    return bytes(sheet)


IDENTITY34 = struct.pack(">12f", 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0)
NAN = float("nan")
INF = float("inf")


def live_scene(camera=True):
    """A synthetic live scene: a textured rock model shared by two actors, a skinned ape model with a rig shared by
    two actors, two actors named alike, one with non-finite vectors, three lights, a camera and a script."""
    b = RafBuilder()
    root = b.element("root", -1)
    scene = b.element("scene", root)
    b.material(root, "mtrl_rock", "pure_texture", gtf(0x86, 8, 4, dxt1(0xF800, 0x001F, 0)))
    b.material(root, "mtrl_ape", "  basic_lighting ")
    b.model(root, "mdl_rock", geometry=smoke_sheet())
    b.model(root, "mdl_ape", geometry=skinned_sheet(),
            skeleton=es01([-1, 0], [0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 2, 0, 0, 1, 1, 1]),
            inverse_bind=IDENTITY34 * 2, animations=[("ape_walk", ea03_bend()), ("ape_broken", b"EA03" + bytes(4))])
    b.actor(scene, "rockA", b.vector("position", 1, 2, 3), b.vector("rotation", 0.5, 0.25, 0.125),
            b.vector("scale", 2, 2, 2), b.vector("color", 1, 0.5, 0.25, 0.75), b.vector("uv_offset", 0.5, 0.25),
            b.vector("uv_scale", 2, 4), material="mtrl_rock", model="mdl_rock")
    b.actor(scene, "rockB", b.vector("position", 7), material="mtrl_rock", model="mdl_rock")
    b.actor(scene, "ape", b.vector("anim_weight", 1, 0.5), b.vector("anim_speed", 1, 2), b.vector("anim_time", 0, 3),
            material="mtrl_ape", model="mdl_ape")
    b.actor(scene, "apeTwo", material="mtrl_ape", model="mdl_ape")
    b.actor(scene, "twin", b.vector("position", 1, 0, 0))
    b.actor(scene, "twin", b.vector("position", 2, 0, 0))
    b.actor(scene, "odd", b.vector("position", NAN, INF, -INF))
    b.light(scene, "sun", b.int("type", 1), b.vector("color", 1, 0.9, 0.8), b.vector("direction", 0, -1, 0))
    b.light(scene, "glow", b.int("type", 0), b.vector("position", 4, 5, 6))
    b.light(scene, "fill", b.int("type", 2), b.vector("color", 0.25, 0.25, 0.25))
    if camera:
        b.camera(scene, b.vector("position", 0, 1, 10), b.vector("direction", 0, -0.6, -0.8), b.vector("up", 0, 0.8, -0.6),
                 b.scalar("yfov", 0.75), b.scalar("znear", 0.5), b.vector("zfar", 900))
    b.script(root, b"VSMX" + bytes(12))
    return b.bytes()


def other_scenes():
    fallbacks = RafBuilder()
    root = fallbacks.element("root", -1)
    fallbacks.actor(root, "only")
    fallbacks.camera(root, fallbacks.scalar("yfov", 5.0), fallbacks.scalar("znear", 0.0), fallbacks.scalar("zfar", 0.5))
    bad = RafBuilder()
    root = bad.element("root", -1)
    bad.element("actor", root, (bad.name("id"), 7, 0xFFFFF, 0))
    bad.camera(root)
    many = RafBuilder()
    root = many.element("root", -1)
    for i in range(513):
        many.actor(root, "a{0}".format(i))
    many.camera(root)
    return {"fallbacks": fallbacks.bytes(), "badActorId": bad.bytes(), "actors513": many.bytes(),
            "noCamera": live_scene(camera=False)}


HALVES = {"3c00": 0x3F800000, "c000": 0xC0000000, "7c00": 0x7F800000, "0001": 0x33800000}

DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }
func f4(_ v: Float) -> String { String(format: "%.4f", Double(v)) }

for (key, indexCount, vertexCount) in @@INDEX@@ {
    let out = RafScene.edgeIndices(load(key + ".idx"), indexCount: indexCount, vertexCount: vertexCount)
    print("IDX \(key) " + (out.map { $0.map(String.init).joined(separator: ",") } ?? "nil"))
}

for key in @@MESH@@ {
    let b = load(key + ".edge")
    var parts = ["collapsed=\(RafScene.collapsed(b))"]
    if let m = RafScene.edgeMesh(b) {
        parts += ["vertices=\(m.vertexCount)", "skinned=\(m.skinned)", "segments=\(m.segments.count)",
                  "indices=" + m.indices.prefix(12).map(String.init).joined(separator: ","),
                  "uvs=" + m.uvs.prefix(8).map(f4).joined(separator: ","),
                  "p0=" + f4(m.positions[0]), "p8=" + f4(m.positions[8]), "area=" + String(format: "%.3f", m.area())]
    } else {
        parts.append("mesh=nil")
    }
    print("MESH \(key) " + parts.joined(separator: " "))
}

for h in [0x3C00, 0xC000, 0x7C00, 0x0001] {
    print("HALF " + String(format: "%04x", h) + " " + String(RafScene.halfToFloat(h).bitPattern, radix: 16))
}

let sheet = load("smokeSheet.edge")
var truncations = 0
for n in 0 ... sheet.count {
    let cut = Array(sheet[0 ..< n])
    _ = RafScene.edgeMesh(cut)
    _ = RafScene.collapsed(cut)
    truncations += 1
}
var state: UInt64 = 0x16_02_2026
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
var flips = 0
for _ in 0 ..< 3000 {
    var copy = sheet
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    _ = RafScene.edgeMesh(copy)
    _ = RafScene.collapsed(copy)
    flips += 1
}
print("FUZZ truncations=\(truncations) flips=\(flips) done=1")

func fl(_ v: [Float]) -> String { v.map { String(describing: $0) }.joined(separator: ",") }

for effect in @@EFFECTS@@ {
    let m = RafScene.Material(effect: effect, texture: nil)
    print("MAT \(effect) opaque=\(m.opaque) additive=\(m.additive) depthTest=\(m.depthTest) lit=\(m.lit) cutout=\(m.cutout)")
}

func describe(_ key: String) {
    guard let scene = RafScene.read(ArrayByteSource(load(key + ".raf"))) else {
        print("SCENE \(key) nil")
        return
    }
    let c = scene.camera
    print("SCENE \(key) actors=\(scene.actors.count) lights=\(scene.lights.count) script=\(scene.script?.count ?? -1) "
          + "camera=" + [fl(c.position), fl(c.direction), fl(c.up), fl([c.yfov, c.znear, c.zfar])].joined(separator: "|"))
    if scene.actors.count > 16 { return }
    var meshes: [RafScene.Mesh] = []
    var rigs: [RafScene.Rig] = []
    for (i, a) in scene.actors.enumerated() {
        var parts = ["name=\(a.name)"]
        if let m = a.mesh {
            if !meshes.contains(where: { $0 === m }) { meshes.append(m) }
            parts += ["mesh=\(meshes.firstIndex(where: { $0 === m })!)", "skinned=\(m.skinned)"]
        } else {
            parts.append("mesh=none")
        }
        if let mat = a.material {
            parts += ["effect=\(mat.effect)", "texture=" + (mat.texture.map { "\($0.width)x\($0.height)" } ?? "none")]
        } else {
            parts.append("effect=none")
        }
        parts += ["pos=" + fl(a.position), "rot=" + fl(a.rotation), "scale=" + fl(a.scale), "color=" + fl(a.color),
                  "uvo=" + fl(a.uvOffset), "uvs=" + fl(a.uvScale)]
        if let r = a.rig {
            if !rigs.contains(where: { $0 === r }) { rigs.append(r) }
            parts += ["rig=\(rigs.firstIndex(where: { $0 === r })!)", "joints=\(r.skeleton.joints)",
                      "inv=\(r.inverseBinds.count)",
                      "clips=" + r.clips.map { $0.map { "ok\($0.channels.count)" } ?? "nil" }.joined(separator: ","),
                      "names=" + r.names.map { $0 ?? "nil" }.joined(separator: ",")]
        } else {
            parts.append("rig=none")
        }
        parts += ["weights=" + fl(a.animWeights), "speeds=" + fl(a.animSpeeds), "times=" + fl(a.animTimes)]
        print("ACTOR \(key)#\(i) " + parts.joined(separator: " "))
    }
    for (i, l) in scene.lights.enumerated() {
        print("LIGHT \(key)#\(i) name=\(l.name) type=\(l.type) color=\(fl(l.color)) position=\(fl(l.position)) direction=\(fl(l.direction))")
    }
}
for key in @@SCENES@@ { describe(key) }

let live = load("live.raf")
var sceneCuts = 0
for n in 0 ... live.count {
    if let s = RafScene.read(ArrayByteSource(Array(live[0 ..< n]))) { _ = s.actors.map { $0.mesh?.vertexCount ?? 0 } }
    sceneCuts += 1
}
var sceneFlips = 0
for _ in 0 ..< 3000 {
    var copy = live
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    if let s = RafScene.read(ArrayByteSource(copy)) {
        for a in s.actors { _ = (a.rig?.clips.count ?? 0) + (a.mesh?.indices.count ?? 0) }
    }
    sceneFlips += 1
}
print("SFUZZ truncations=\(sceneCuts) flips=\(sceneFlips) done=1")
"""


def raf_scene_problems(code_raw):
    """code_raw: RafScene.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: RafScene.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: RafScene.swift logs")
    for match in SUBSCRIPT.finditer(code):
        problems.append("subscript: RafScene.swift subscripts `{0}[` directly; read through ThemeBytes.swift".format(match.group(1)))
    for name in FINAL_CLASS:
        if not re.search(r"\bfinal\s+class\s+" + name + r"\b", code):
            problems.append("identity: RafScene.swift does not declare `final class {0}`".format(name))
    for name in SHARED_CLASS:
        if not re.search(r"\bfinal\s+class\s+" + name + r"\b", code):
            problems.append("shared: RafScene.swift does not declare `final class {0}`".format(name))
    if not re.search(r"\bstruct\s+Material\b", code):
        problems.append("shared: RafScene.swift does not declare `struct Material`")
    return problems


class TheMeshesHold(unittest.TestCase):
    """The ported Kotlin cases, the Swift-only cases and the fuzz; one line per case."""

    @classmethod
    def setUpClass(cls):
        read(BYTES)
        read(RAF)
        cls.index = index_cases()
        cls.meshes = mesh_cases()
        cls.sheet_size = len(smoke_sheet())
        cls.scenes = other_scenes()
        cls.scenes["live"] = live_scene()
        cls.live_size = len(cls.scenes["live"])
        with tempfile.TemporaryDirectory() as tmp:
            for key, data in cls.scenes.items():
                (Path(tmp) / (key + ".raf")).write_bytes(data)
            for key, (block, _, _) in cls.index.items():
                (Path(tmp) / (key + ".idx")).write_bytes(block)
            for key, data in cls.meshes.items():
                (Path(tmp) / (key + ".edge")).write_bytes(data)
            index_literal = "[" + ", ".join('("{0}", {1}, {2})'.format(k, c, v)
                                            for k, (_, c, v) in sorted(cls.index.items())) + "]"
            driver = (DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@INDEX@@", index_literal)
                      .replace("@@MESH@@", json.dumps(sorted(cls.meshes))).replace("@@EFFECTS@@", json.dumps(EFFECTS))
                      .replace("@@SCENES@@", json.dumps(sorted(cls.scenes))))
            out = compile_and_run([BYTES, P3T, ANIM, EDGE, RAF], driver, tmp)
        cls.out = out
        cls.lines = {}
        for text in out.splitlines():
            parts = text.split(" ", 2)
            if parts[0] in ("IDX", "MESH", "HALF", "MAT", "SCENE", "ACTOR", "LIGHT") and len(parts) == 3:
                cls.lines[parts[0] + " " + parts[1]] = parts[2]
            elif parts[0] in ("FUZZ", "SFUZZ"):
                cls.lines[parts[0]] = parts[1] + " " + parts[2]

    def indices(self, key):
        got = self.lines.get("IDX " + key)
        self.assertIsNotNone(got, key)
        return None if got == "nil" else [int(x) for x in got.split(",")]

    def mesh(self, key):
        got = self.lines.get("MESH " + key)
        self.assertIsNotNone(got, key)
        return dict(part.split("=", 1) for part in got.split(" "))

    def test_quad_shares_an_edge(self):
        self.assertEqual([0, 1, 2, 0, 2, 3], self.indices("quadSharesAnEdge"))

    def test_the_other_edge_codes_keep_the_winding(self):
        self.assertEqual([0, 1, 2, 2, 1, 3, 1, 2, 4], self.indices("theOtherEdgeCodesKeepTheWinding"))

    def test_reused_vertices_come_from_their_lane(self):
        self.assertEqual([0, 1, 2, 0, 2, 3], self.indices("reusedOne"))
        self.assertEqual([1, 2, 3, 4, 5, 6, 7, 8, 10], self.indices("reusedLanes"))

    def test_bad_streams_are_refused(self):
        for key in ("badNoTriangleBefore", "badPastTheVertices", "badMoreReadsThanFlags", "badShortHeader",
                    "badMissingReusedValue"):
            with self.subTest(key):
                self.assertIsNone(self.indices(key))

    def test_real_grid(self):
        expected = [
            0, 1, 2, 2, 1, 3, 3, 1, 4, 2, 3, 5, 5, 3, 6, 6, 3, 7, 7, 3, 4, 7, 4, 8, 7, 8, 9, 7, 9, 10, 7, 10,
            6, 6, 10, 11, 6, 11, 12, 6, 12, 5, 12, 11, 13, 13, 11, 14, 14, 11, 15, 15, 11, 10, 15, 10, 16, 16,
            10, 9, 16, 9, 17, 17, 9, 18, 18, 9, 8, 17, 19, 16, 16, 19, 20, 16, 20, 15, 15, 20, 21, 15, 21, 14,
            20, 22, 21, 22, 20, 23, 23, 20, 19, 22, 23, 24,
        ]
        self.assertEqual(expected, self.indices("realGrid"))

    def test_real_biased_mesh(self):
        out = self.indices("realBiasedMesh")
        self.assertIsNotNone(out)
        self.assertEqual(906, len(out))
        self.assertEqual([0, 1, 2, 2, 1, 3, 3, 1, 4, 5, 6, 7, 7, 6, 8, 7, 8, 9, 10, 11, 12, 12, 11, 13], out[:24])
        self.assertEqual(259206, sum(out))
        self.assertEqual(495, len(set(out)))
        h = 0
        for i in out:
            h = (h * 31 + i) & 0xFFFFFFFF
        self.assertEqual(625667672, h)
        edges = {}
        for t in range(0, len(out), 3):
            for k in range(3):
                a, c = out[t + k], out[t + (k + 1) % 3]
                key = (min(a, c), max(a, c))
                edges[key] = edges.get(key, 0) + 1
        self.assertTrue(all(n <= 2 for n in edges.values()))

    def test_real_mesh_decodes(self):
        m = self.mesh("smokeSheet")
        self.assertEqual("4", m.get("vertices"))
        self.assertEqual("false", m.get("skinned"))
        self.assertEqual("0,1,2,0,2,3", m.get("indices"))
        self.assertEqual(",".join("{0:.4f}".format(v) for v in (0, 0, 1, 0, 1, 1, 0, 1)), m.get("uvs"))
        self.assertAlmostEqual(-25.6, float(m["p0"]), delta=1e-4)
        self.assertAlmostEqual(25.6, float(m["p8"]), delta=1e-4)
        self.assertAlmostEqual(51.2 * 51.2, float(m["area"]), delta=1e-2)
        self.assertEqual("false", m.get("collapsed"))

    def test_collapsed_meshes_are_found(self):
        self.assertEqual("true", self.mesh("flat").get("collapsed"))
        gone = self.mesh("gone")
        self.assertEqual("nil", gone.get("mesh"))
        self.assertEqual("true", gone.get("collapsed"))
        self.assertEqual("true", self.mesh("rocks").get("collapsed"))
        damaged = self.mesh("damaged")
        self.assertEqual("nil", damaged.get("mesh"))
        self.assertEqual("false", damaged.get("collapsed"))

    def test_truncated_meshes_are_refused(self):
        for n in (0, 0x20, 0x44, 0x100, 0x120, self.sheet_size - 60):
            with self.subTest(n):
                self.assertEqual("nil", self.mesh("cut{0}".format(n)).get("mesh"))

    def test_swift_index_block_one_byte_short_is_refused(self):
        self.assertIsNone(self.indices("swiftOneByteShort"))

    def test_swift_half_to_float(self):
        for half, bits in HALVES.items():
            with self.subTest(half):
                self.assertEqual("{0:x}".format(bits), self.lines.get("HALF " + half))

    def test_swift_more_than_64_segments_are_refused(self):
        many = self.mesh("segments64")
        self.assertEqual("64", many.get("segments"))
        self.assertEqual(str(64 * 4), many.get("vertices"))
        self.assertEqual("nil", self.mesh("segments65").get("mesh"))

    def test_fuzz_truncations_and_flips_never_trap(self):
        self.assertEqual("truncations={0} flips=3000 done=1".format(self.sheet_size + 1), self.lines.get("FUZZ"))

    # The scene half.

    def fields(self, kind, key):
        got = self.lines.get(kind + " " + key)
        self.assertIsNotNone(got, kind + " " + key)
        return dict(part.split("=", 1) for part in got.split(" "))

    def floats(self, text):
        return [float(x) for x in text.split(",")]

    def test_materials_read_their_effect(self):
        lit = self.fields("MAT", "basic_lighting")
        self.assertEqual(("true", "true", "false", "true"), (lit["opaque"], lit["depthTest"], lit["additive"], lit["lit"]))
        flare = self.fields("MAT", "basic_lighting_alpha_add")
        self.assertEqual(("false", "true", "true"), (flare["opaque"], flare["additive"], flare["lit"]))
        backdrop = self.fields("MAT", "pure_texture_alpha_1_depth_0")
        self.assertEqual(("false", "false"), (backdrop["opaque"], backdrop["depthTest"]))
        sprite = self.fields("MAT", "pure_texture")
        self.assertEqual(("false", "true", "true"), (sprite["opaque"], sprite["depthTest"], sprite["cutout"]))

    def test_material_rules_as_data(self):
        frame = self.fields("MAT", "pure_texture_alpha_0_depth_0")
        self.assertEqual(("true", "false", "false"), (frame["opaque"], frame["depthTest"], frame["cutout"]))
        blended = self.fields("MAT", "pure_texture_alpha_1_depth_1")
        self.assertEqual(("false", "false", "true", "false", "false"),
                         (blended["opaque"], blended["additive"], blended["depthTest"], blended["cutout"], blended["lit"]))

    def test_actors_sharing_a_model_share_one_mesh(self):
        a, b = self.fields("ACTOR", "live#0"), self.fields("ACTOR", "live#1")
        self.assertEqual(("rockA", "rockB"), (a["name"], b["name"]))
        self.assertEqual(a["mesh"], b["mesh"])
        self.assertEqual(("pure_texture", "8x4", "false"), (a["effect"], a["texture"], a["skinned"]))
        self.assertEqual([1, 2, 3], self.floats(a["pos"]))
        self.assertEqual([0.5, 0.25, 0.125], self.floats(a["rot"]))
        self.assertEqual([2, 2, 2], self.floats(a["scale"]))
        self.assertEqual([1, 0.5, 0.25, 0.75], self.floats(a["color"]))
        self.assertEqual(([0.5, 0.25], [2, 4]), (self.floats(a["uvo"]), self.floats(a["uvs"])))
        # rockB: a one-float position fills the rest from the fallback; everything else is the fallback.
        self.assertEqual([7, 0, 0], self.floats(b["pos"]))
        self.assertEqual(([0, 0, 0], [1, 1, 1], [1, 1, 1, 1]), (self.floats(b["rot"]), self.floats(b["scale"]),
                                                                  self.floats(b["color"])))
        self.assertEqual(([0, 0], [1, 1]), (self.floats(b["uvo"]), self.floats(b["uvs"])))

    def test_a_skinned_actor_gets_its_rig(self):
        ape, two = self.fields("ACTOR", "live#2"), self.fields("ACTOR", "live#3")
        self.assertEqual(("ape", "true", "basic_lighting", "none"), (ape["name"], ape["skinned"], ape["effect"], ape["texture"]))
        self.assertEqual(("2", "24", "ok2,nil", "ape_walk,ape_broken"), (ape["joints"], ape["inv"], ape["clips"], ape["names"]))
        self.assertEqual(([1, 0.5], [1, 2], [0, 3]), (self.floats(ape["weights"]), self.floats(ape["speeds"]),
                                                     self.floats(ape["times"])))
        self.assertEqual((ape["mesh"], ape["rig"]), (two["mesh"], two["rig"]))
        self.assertEqual("none", self.fields("ACTOR", "live#0")["rig"])

    def test_lights_keep_their_order(self):
        sun, glow, fill = (self.fields("LIGHT", "live#{0}".format(i)) for i in range(3))
        self.assertEqual([("sun", "1"), ("glow", "0"), ("fill", "2")],
                         [(l["name"], l["type"]) for l in (sun, glow, fill)])
        self.assertEqual([1, 0.9, 0.8], [round(v, 6) for v in self.floats(sun["color"])])
        self.assertEqual([0, -1, 0], self.floats(sun["direction"]))
        self.assertEqual(([4, 5, 6], [1, 1, 1], [0, 0, -1]), (self.floats(glow["position"]), self.floats(glow["color"]),
                                                             self.floats(glow["direction"])))
        self.assertEqual([0.25, 0.25, 0.25], self.floats(fill["color"]))

    def test_the_camera_and_script_are_read(self):
        scene = self.fields("SCENE", "live")
        self.assertEqual(("7", "3", "16"), (scene["actors"], scene["lights"], scene["script"]))
        position, direction, up, numbers = scene["camera"].split("|")
        self.assertEqual([0, 1, 10], self.floats(position))
        self.assertEqual([0, -0.6, -0.8], [round(v, 6) for v in self.floats(direction)])
        self.assertEqual([0, 0.8, -0.6], [round(v, 6) for v in self.floats(up)])
        self.assertEqual([0.75, 0.5, 900], self.floats(numbers))

    def test_the_camera_falls_back_as_android_does(self):
        scene = self.fields("SCENE", "fallbacks")
        self.assertEqual(("1", "0", "-1"), (scene["actors"], scene["lights"], scene["script"]))
        position, direction, up, numbers = scene["camera"].split("|")
        self.assertEqual(([0, 0, 0], [0, 0, -1], [0, 1, 0]), (self.floats(position), self.floats(direction),
                                                             self.floats(up)))
        yfov, znear, zfar = self.floats(numbers)
        self.assertAlmostEqual(0.9273, yfov, delta=1e-6)
        self.assertAlmostEqual(1e-4, znear, delta=1e-9)
        self.assertEqual(1.0, zfar)

    def test_swift_actors_named_alike_are_both_kept(self):
        self.assertEqual(("twin", "twin"), (self.fields("ACTOR", "live#4")["name"], self.fields("ACTOR", "live#5")["name"]))
        self.assertEqual(([1, 0, 0], [2, 0, 0]), (self.floats(self.fields("ACTOR", "live#4")["pos"]),
                                                  self.floats(self.fields("ACTOR", "live#5")["pos"])))

    def test_swift_actors_are_capped_at_512(self):
        self.assertEqual("512", self.fields("SCENE", "actors513")["actors"])

    def test_swift_non_finite_vectors_stay_floats(self):
        pos = self.fields("ACTOR", "live#6")["pos"].split(",")
        self.assertEqual(["nan", "inf", "-inf"], pos)

    def test_swift_a_bad_actor_id_or_no_camera_refuses_the_scene(self):
        self.assertEqual("nil", self.lines.get("SCENE badActorId"))
        self.assertEqual("nil", self.lines.get("SCENE noCamera"))

    def test_fuzz_scene_truncations_and_flips_never_trap(self):
        self.assertEqual("truncations={0} flips=3000 done=1".format(self.live_size + 1), self.lines.get("SFUZZ"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], raf_scene_problems(read(RAF)))

    def test_committed_samples_are_read_where_they_lie(self):
        self.assertEqual(0x1C4, len(smoke_sheet()))
        self.assertEqual(32, len(kotlin_hex(RAF_TEST, "realGrid")))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")
        with self.assertRaises(AssertionError):
            kotlin_hex(RAF_TEST, "noSuchSample")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(RAF)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = raf_scene_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_subscript(self):
        self.flagged("subscript", "let r = ByteReader(block)", "let r = ByteReader(block); _ = block[0]")

    def test_mutation_identity(self):
        self.flagged("identity", "final class Mesh", "class Mesh")

    def test_mutation_shared(self):
        self.flagged("shared", "final class Rig", "class Rig")


if __name__ == "__main__":
    unittest.main()
