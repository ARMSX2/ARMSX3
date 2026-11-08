#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""P3tAnimation.swift, run on the Mac host over built scenes, unpacks a packed scene under its cap, decodes GTF and DXT
textures and scene timing as Android's P3tAnimation.kt does, and refuses truncated or flipped input without a trap.
It says nothing about real themes, JPEG or PNG decoding, or a device."""

import json
import math
import re
import tempfile
import unittest
import zlib
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import (RafBuilder, VsmxAsm, dxt1, dxt5, gtf, jpeg, kotlin_hex, raf_entry, raw_deflate,
                            slideshow_script, valid_flg, zlib_with_header, zoom_script)

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
BYTES = APP / "Themes" / "ThemeBytes.swift"
RAF = APP / "Themes" / "RafScene.swift"
P3T = APP / "Themes" / "P3tTheme.swift"
ANIM = APP / "Themes" / "P3tAnimation.swift"
EDGE = APP / "Themes" / "EdgeAnim.swift"
EDGE_SAMPLES = (IOS.parent / "android" / "armsx3-ui" / "app" / "src" / "test" / "java" / "com" / "armsx2" / "ui"
                / "home" / "EdgeSamples.kt")

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
SUBSCRIPT = re.compile(r"\b(b|bytes|data|head|header|raw|block|texels)\[")
CAPS = ("16 << 20", "1 << 20", "4 << 20", "4096", "256 << 10")
FORMATS = ("0x86", "0x87", "0x88", "0x60")

RED, BLUE, WHITE = 0xF800, 0x001F, 0xFFFF


def block_cases():
    """{key: (bytes, format, width, height, pitch)} for decodeBlocks, as P3tAnimationTest.kt's `texture` builds."""
    def texture(fmt, width, height, block):
        return fmt, width, height, (width + 3) // 4 * block
    return {
        "dxt1FourColour": (dxt1(RED, BLUE, 0 | (1 << 2) | (2 << 4) | (3 << 6)),) + texture(0xA6, 4, 4, 8),
        "dxt1ThreeColour": (dxt1(BLUE, RED, (2 << 0) | (3 << 2)),) + texture(0x86, 4, 4, 8),
        "dxt5Eight": (dxt5(255, 0, 0 | (1 << 3) | (2 << 6) | (7 << 9), dxt1(WHITE, 0, 0)),) + texture(0xA8, 4, 4, 16),
        "dxt5Six": (dxt5(0, 255, 2 | (6 << 3) | (7 << 6), dxt1(WHITE, 0, 0)),) + texture(0xA8, 4, 4, 16),
        "dxt3": (bytes([0x0F, 0xF8]) + b"\xFF" * 6 + dxt1(WHITE, 0, 0),) + texture(0x87, 4, 4, 16),
        "cropped": (dxt1(RED, RED, 0) + dxt1(BLUE, BLUE, 0) + dxt1(WHITE, WHITE, 0) + dxt1(RED, RED, 0), 0xA6, 6, 5, 16),
        "shortData": (bytes(7),) + texture(0xA6, 4, 4, 8),
    }


def unpack_cases():
    """{key: (bytes, offset, size, cap, expected ok, expected output or None)}."""
    scene = bytes(i % 251 for i in range(300000))
    packed = raf_entry(scene)
    small = raf_entry(bytes(1000))
    not_raf = b"X" + small[1:]
    plain = bytes(range(256)) * 4
    fdict = (b"_RAF" + len(plain).to_bytes(4, "big")
             + zlib_with_header(0x78, valid_flg(0x78, fdict=True), raw_deflate(plain) + zlib.adler32(plain).to_bytes(4, "big")))
    return {
        "roundTrip": (packed, 0, len(packed), 1 << 20, True, scene),
        "notRaf": (not_raf, 0, len(not_raf), 1 << 20, False, None),
        "cutShort": (small, 0, len(small) - 5, 1 << 20, False, None),
        "overCap": (small, 0, len(small), 100, False, None),
        "pastTheEnd": (small, 10, len(small), 1 << 20, False, None),
        "swiftAtCap": (small, 0, len(small), 1000, True, bytes(1000)),
        "swiftOneOverCap": (small, 0, len(small), 999, False, None),
        "swiftCutInHalf": (small, 0, len(small) // 2, 1 << 20, False, None),
        "swiftFdict": (fdict, 0, len(fdict), 1 << 20, False, None),
        "swiftWindowPastEnd": (small, 0, len(small) + 1, 1 << 20, False, None),
        "swiftUnderHeader": (small, 0, 7, 1 << 20, False, None),
        "swiftTrailerCut": (small, 0, len(small) - 4, 1 << 20, True, bytes(1000)),
    }


def texture_cases():
    """{key: GTF or picture file bytes} for texture(_:offset:size:) then decode."""
    red = dxt1(RED, RED, 0)
    return {
        "hugePitchOneRow": gtf(0x86, 8, 4, red, pitch=0xFFFFFFF0),
        "hugePitchTwoRows": gtf(0x86, 8, 8, red, pitch=0xFFFFFFF0),
        "wide4096": gtf(0x86, 4096, 4, red),
        "wide4097": gtf(0x86, 4097, 4, red),
        "jpegPicture": jpeg(1280, 720) + bytes(16),
    }


def fuzz_inputs():
    """{key: bytes}: the GTFs go through texture and decode, the entry through unpack."""
    return {
        "gtf1": gtf(0x86, 8, 8, dxt1(RED, BLUE, 0x1B1B1B1B)),
        "gtf3": gtf(0x87, 8, 8, bytes([0x0F, 0xF8, 0x33, 0x77, 0x00, 0xFF, 0x12, 0x34]) + dxt1(WHITE, BLUE, 0xE4E4E4E4)),
        "gtf5": gtf(0x88, 8, 8, dxt5(200, 20, 0x123456789ABC, dxt1(RED, WHITE, 0x39393939))),
        "raf": raf_entry(bytes((i * 7) % 256 for i in range(2000))),
    }


SCREEN_W, SCREEN_H = 960, 540
NAN, INF, NEG_INF = 0x7FC00000, 0x7F800000, 0xFF800000


def slideshow_scene(script=None):
    """P3tAnimationTest.kt's slideshowScene: two DXT1 slides, a see-through DXT5 wave between them, a badge that is
    not full-screen and an ARGB layer that is not decoded here."""
    s = RafBuilder()
    root = s.element("raf", -1)
    scene = s.element("scene", root)
    s.layer(scene, root, "bg01", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(RED, RED, 0)))
    s.layer(scene, root, "badge", gtf(0xA8, 256, 64, dxt5(255, 255, 0, dxt1(WHITE, 0, 0))))
    s.layer(scene, root, "wave", gtf(0xA8, SCREEN_W, SCREEN_H, dxt5(128, 128, 0, dxt1(WHITE, 0, 0))))
    s.layer(scene, root, "bg02", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(BLUE, BLUE, 0)))
    s.layer(scene, root, "odd", gtf(0x85, SCREEN_W, SCREEN_H, bytes(16)))
    if script is not None:
        s.script(root, script)
    return s


def scene_cases():
    """{key: scene bytes} for scene(_:)."""
    cases = {
        "listsLayers": slideshow_scene().bytes(),
        "timingFromScript": slideshow_scene(slideshow_script()).bytes(),
    }
    s = RafBuilder()
    root = s.element("raf", -1)
    scene = s.element("scene", root)
    s.layer(scene, root, "badge", gtf(0xA8, 256, 64, dxt5(255, 255, 0, dxt1(WHITE, 0, 0))))
    cases["noFullScreen"] = s.bytes()

    good = slideshow_scene().bytes()
    cases["damagedCut40"] = good[:40]
    cases["damagedMagic"] = b"X" + good[1:]
    cases["damagedCount"] = slideshow_scene().bytes(element_count_override=0x7FFFFFFF)
    cases["damagedFiles"] = slideshow_scene().bytes(files_offset_override=0x7FFFFFF0)

    sheet = kotlin_hex(EDGE_SAMPLES, "smokeSheet")
    collapsed = sheet[:0x100] + bytes(0x40) + sheet[0x140:]
    s = RafBuilder()
    root = s.element("raf", -1)
    scene = s.element("scene", root)
    s.layer(scene, root, "bg01", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(RED, RED, 0)), mesh=sheet)
    s.layer(scene, root, "rocks", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(BLUE, BLUE, 0)), mesh=collapsed)
    s.layer(scene, root, "bg02", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(WHITE, WHITE, 0)))
    cases["collapsedHidden"] = s.bytes()

    s = RafBuilder()
    root = s.element("raf", -1)
    scene = s.element("scene", root)
    s.layer(scene, root, "bg01", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(RED, RED, 0)))
    s.layer(scene, root, "bg02", gtf(0xA6, SCREEN_W, SCREEN_H, dxt1(BLUE, BLUE, 0)))
    cut = bytearray(s.bytes()[:-1000])
    cut[52:56] = (int.from_bytes(cut[52:56], "big") - 1000).to_bytes(4, "big")
    cases["pixelsCutOff"] = bytes(cut)

    s = slideshow_scene()
    s.element("actor", 1, (s.name("id"), 7, 0x7FFFFF00, 0), s.ref("material", "mtrl_bg01"))
    cases["swiftBadActorId"] = s.bytes()
    return cases


def alpha_slideshow_script():
    """Persona 5 Slideshow's AlphaSlideshow.jsx: `duration = 8; fadeduration = 2.3;` and a timer whose callback
    calls slide.setColor(first, fadeduration) with just two arguments."""
    a = VsmxAsm()
    a.assign("duration", lambda: a.float_(8.0))
    a.assign("fadeduration", lambda: a.float_(2.3))
    a.function("bgchange", 0, 0, lambda: a.method_call(
        "slide", "setColor", lambda: a.global_("first"), lambda: a.global_("fadeduration")))
    a.timer(lambda: a.global_("duration"), "bgchange")
    return a.assemble()


def bad_index_script():
    """A script whose property, global and function indexes are negative or absurd: nothing may trap on them."""
    a = VsmxAsm()
    a.assign("duration", lambda: a.float_(8.0))
    a.op(0x2E, -7)
    a.op(0x2E, 0x7FFFFFFF)
    a.global_("bad")
    a.op(0x2A, -5)
    a.op(0x01)
    a.pop()
    a.op(0x39, -1)

    def body():
        a.global_("slide")
        a.op(0x30, -1)
        a.global_("first")
        a.global_("duration")
        a.call_method(2)
        a.pop()
    a.function("bgchange", 0, 0, body)
    a.timer(lambda: a.global_("duration"), "bgchange")
    a.timer(lambda: a.global_("duration"), "bad")
    return a.assemble()


def timing_cases():
    """{key: (script bytes or None, planeZ)} for timing(_:planeZ:)."""
    return {
        "zoomFromCamera": (zoom_script(), -7.995),
        "alphaSlideshow": (alpha_slideshow_script(), -8.0),
        "unreadableNull": (None, -8.0),
        "unreadableEmpty": (b"", -8.0),
        "unreadable55": (b"\x55" * 64, -8.0),
        "unreadableCut60": (slideshow_script()[:60], -8.0),
        "swiftEmptyName": (slideshow_script(6.0, 2.0, names=["duration", "", "fade"]), -8.0),
        "swiftNanInterval": (slideshow_script(NAN, INF), -8.0),
        "swiftInfInterval": (slideshow_script(INF, NEG_INF), -8.0),
        "swiftNanZoomZ": (zoom_script(end_z_bits=NAN), -7.995),
        "swiftInfZoomZ": (zoom_script(end_z_bits=INF), -7.995),
        "swiftNanMove": (zoom_script(move_bits=NAN), -7.995),
        "swiftBadIndexes": (bad_index_script(), -8.0),
    }


DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }
func px(_ v: [UInt32]?) -> String { v.map { $0.map { String(format: "%08x", $0) }.joined(separator: ",") } ?? "nil" }

for (key, format, width, height, pitch) in @@BLOCKS@@ {
    let t = P3tAnimation.Texture(format: format, width: width, height: height, pitch: pitch, offset: 0, size: 0)
    print("DEC \(key) " + px(P3tAnimation.decodeBlocks(load(key + ".blk"), t)))
}

func tex(_ format: Int) -> P3tAnimation.Texture {
    P3tAnimation.Texture(format: format, width: 10, height: 10, pitch: 0, offset: 0, size: 0)
}
let opaque: UInt32 = 0xFFFF_FFFF
let someBlack = (0 ..< 100).map { $0 < 4 ? UInt32(0) : opaque }
print("CLEAR zero \(P3tAnimation.clearShare([UInt32](repeating: opaque, count: 10)))")
print("CLEAR half \(P3tAnimation.clearShare([0x80FF_FFFF, 0x80FF_FFFF, opaque, opaque]))")
print("CLEAR empty \(P3tAnimation.clearShare([]))")
print("SLIDE dxt1SomeBlack \(P3tAnimation.isSlide(tex(0xA6), someBlack))")
print("SLIDE dxt5SomeBlack \(P3tAnimation.isSlide(tex(0xA8), someBlack))")
print("SLIDE dxt5Opaque \(P3tAnimation.isSlide(tex(0xA8), [UInt32](repeating: opaque, count: 100)))")
print("SLIDE dxt5OnePercent \(P3tAnimation.isSlide(tex(0xA8), (0 ..< 100).map { $0 < 1 ? UInt32(0x00FF_FFFF) : opaque }))")
print("SLIDE dxt5TwoPercent \(P3tAnimation.isSlide(tex(0xA8), (0 ..< 100).map { $0 < 2 ? UInt32(0x00FF_FFFF) : opaque }))")
print("SLIDE dxt1AllClear \(P3tAnimation.isSlide(tex(0x86), [UInt32](repeating: 0, count: 100)))")

for (key, offset, size, cap) in @@UNPACK@@ {
    var out: [UInt8] = []
    let ok = P3tAnimation.unpack(ArrayByteSource(load(key + ".raf")), offset: offset, size: size, cap: cap) { out += $0 }
    if ok { try! Data(out).write(to: dir.appendingPathComponent(key + ".out")) }
    print("UNPACK \(key) ok=\(ok) count=\(out.count)")
}

for key in @@TEXTURES@@ {
    let file = load(key + ".tex")
    let src = ArrayByteSource(file)
    if let t = P3tAnimation.texture(src, offset: 0, size: file.count) {
        let pixels = P3tAnimation.decode(src, t)
        print("TEX \(key) format=\(t.format) width=\(t.width) height=\(t.height) pitch=\(t.pitch) offset=\(t.offset) size=\(t.size) picture=\(P3tAnimation.isPicture(t)) dxt=\(P3tAnimation.dxt(t)) pixels=\(pixels.map { String($0.count) } ?? "nil") first=\(pixels.flatMap { $0.first }.map { String(format: "%08x", $0) } ?? "nil")")
    } else {
        print("TEX \(key) nil")
    }
}

var state: UInt64 = 0x16_02_0002
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
func throughGtf(_ b: [UInt8]) {
    let src = ArrayByteSource(b)
    if let t = P3tAnimation.texture(src, offset: 0, size: b.count) { _ = P3tAnimation.decode(src, t) }
}
func throughUnpack(_ b: [UInt8]) {
    _ = P3tAnimation.unpack(ArrayByteSource(b), offset: 0, size: b.count, cap: 1 << 20) { _ in }
}
for key in ["gtf1", "gtf3", "gtf5", "raf"] {
    let whole = load(key + ".fuzz")
    let run = key == "raf" ? throughUnpack : throughGtf
    var truncations = 0
    for n in 0 ... whole.count {
        run(Array(whole[0 ..< n]))
        truncations += 1
    }
    var flips = 0
    for _ in 0 ..< 3000 {
        var copy = whole
        let at = next() % copy.count
        copy[at] ^= UInt8(1 + next() % 255)
        run(copy)
        flips += 1
    }
    print("FUZZ \(key) truncations=\(truncations) flips=\(flips) done=1")
}

func timingLine(_ t: P3tAnimation.Timing) -> String {
    [t.interval, t.fade, t.zoom, t.zoomInterval, t.zoomMove].map { "\($0)" }.joined(separator: ",")
}
for key in @@SCENES@@ {
    let raf = ArrayByteSource(load(key + ".scene"))
    guard let s = P3tAnimation.scene(raf) else {
        print("SCENE \(key) nil")
        continue
    }
    var parts = ["layers=" + s.layers.map { $0.name }.joined(separator: ","), "width0=\(s.layers[0].texture.width)",
                 "z0=" + (s.layers[0].z.map { "\($0)" } ?? "nil"), "timing=" + timingLine(s.timing)]
    parts.append("px00=" + (P3tAnimation.decode(raf, s.layers[0].texture)?.first.map { String(format: "%08x", $0) } ?? "nil"))
    if s.layers.count > 1 {
        parts.append("a10=" + (P3tAnimation.decode(raf, s.layers[1].texture)?.first.map { String($0 >> 24) } ?? "nil"))
    }
    print("SCENE \(key) " + parts.joined(separator: " "))
}
for (key, hasScript, planeZ) in @@TIMINGS@@ {
    let script: [UInt8]? = hasScript ? load(key + ".vsmx") : nil
    print("TIMING \(key) " + timingLine(P3tAnimation.timing(script, planeZ: Float(planeZ))))
}

// A prefix of the scene without copying it, so cutting a 1.5 MB scene at every length stays fast.
struct Prefix: ThemeByteSource {
    let base: [UInt8]
    let size: Int
    func read(_ offset: Int, _ length: Int) throws -> [UInt8] {
        guard themeRangeOK(offset, length, size: size) else { throw ThemeDamaged() }
        return Array(base[offset ..< offset + length])
    }
}
var sceneBytes = load("fuzzScene.scene")
let filesAt = Int(sceneBytes[48]) << 24 | Int(sceneBytes[49]) << 16 | Int(sceneBytes[50]) << 8 | Int(sceneBytes[51])
print("FUZZWHOLE scene layers=\(P3tAnimation.scene(ArrayByteSource(sceneBytes))?.layers.count ?? -1)")
var sceneCuts = 0
for n in 0 ... sceneBytes.count {
    _ = P3tAnimation.scene(Prefix(base: sceneBytes, size: n))
    sceneCuts += 1
}
var sceneFlips = 0
for k in 0 ..< 3000 {
    let at = next() % (k % 2 == 0 ? sceneBytes.count : max(filesAt, 1))
    let x = UInt8(1 + next() % 255)
    sceneBytes[at] ^= x
    _ = P3tAnimation.scene(ArrayByteSource(sceneBytes))
    sceneBytes[at] ^= x
    sceneFlips += 1
}
print("FUZZ scene truncations=\(sceneCuts) flips=\(sceneFlips) done=1")

let zoomBytes = load("fuzzZoom.vsmx")
var zoomCuts = 0
for n in 0 ... zoomBytes.count {
    _ = P3tAnimation.timing(Array(zoomBytes[0 ..< n]), planeZ: -8)
    zoomCuts += 1
}
var zoomFlips = 0
for _ in 0 ..< 3000 {
    var copy = zoomBytes
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    let t = P3tAnimation.timing(copy, planeZ: -8)
    if ![t.interval, t.fade, t.zoom, t.zoomInterval, t.zoomMove].allSatisfy({ $0.isFinite }) { print("NONFINITE") }
    zoomFlips += 1
}
print("FUZZ timing truncations=\(zoomCuts) flips=\(zoomFlips) done=1")
"""


def animation_problems(code_raw):
    """code_raw: P3tAnimation.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: P3tAnimation.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: P3tAnimation.swift logs")
    for match in SUBSCRIPT.finditer(code):
        problems.append("subscript: P3tAnimation.swift subscripts `{0}[` directly; read through ThemeBytes.swift".format(match.group(1)))
    for cap in CAPS:
        if cap not in code:
            problems.append("caps: P3tAnimation.swift does not carry the Kotlin cap {0}".format(cap))
    for word in FORMATS:
        if not re.search(r"\b" + word + r"\b", code):
            problems.append("formats: P3tAnimation.swift does not carry the GCM format word {0}".format(word))
    return problems


class TheAnimationHolds(unittest.TestCase):
    """The ported Kotlin cases, the Swift-only cases and the fuzz; one line per case."""

    @classmethod
    def setUpClass(cls):
        for source in (BYTES, RAF, P3T, ANIM):
            read(source)
        cls.blocks = block_cases()
        cls.unpacks = unpack_cases()
        cls.textures = texture_cases()
        cls.fuzz = fuzz_inputs()
        cls.scenes = scene_cases()
        cls.timings = timing_cases()
        cls.fuzz_scene = slideshow_scene(slideshow_script()).bytes()
        cls.fuzz_zoom = zoom_script()
        cls.outputs = {}
        with tempfile.TemporaryDirectory() as tmp:
            for key, case in cls.blocks.items():
                (Path(tmp) / (key + ".blk")).write_bytes(case[0])
            for key, case in cls.unpacks.items():
                (Path(tmp) / (key + ".raf")).write_bytes(case[0])
            for key, data in cls.textures.items():
                (Path(tmp) / (key + ".tex")).write_bytes(data)
            for key, data in cls.fuzz.items():
                (Path(tmp) / (key + ".fuzz")).write_bytes(data)
            for key, data in cls.scenes.items():
                (Path(tmp) / (key + ".scene")).write_bytes(data)
            for key, (script, _) in cls.timings.items():
                if script is not None:
                    (Path(tmp) / (key + ".vsmx")).write_bytes(script)
            (Path(tmp) / "fuzzScene.scene").write_bytes(cls.fuzz_scene)
            (Path(tmp) / "fuzzZoom.vsmx").write_bytes(cls.fuzz_zoom)
            timings = "[" + ", ".join('("{0}", {1}, {2!r})'.format(k, "true" if s is not None else "false", z)
                                      for k, (s, z) in sorted(cls.timings.items())) + "]"
            blocks = "[" + ", ".join('("{0}", {1}, {2}, {3}, {4})'.format(k, *c[1:])
                                     for k, c in sorted(cls.blocks.items())) + "]"
            unpacks = "[" + ", ".join('("{0}", {1}, {2}, {3})'.format(k, *c[1:4])
                                      for k, c in sorted(cls.unpacks.items())) + "]"
            driver = (DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@BLOCKS@@", blocks)
                      .replace("@@UNPACK@@", unpacks).replace("@@TEXTURES@@", json.dumps(sorted(cls.textures)))
                      .replace("@@SCENES@@", json.dumps(sorted(cls.scenes))).replace("@@TIMINGS@@", timings))
            out = compile_and_run([BYTES, RAF, P3T, ANIM, EDGE], driver, tmp)
            for key in cls.unpacks:
                made = Path(tmp) / (key + ".out")
                if made.is_file():
                    cls.outputs[key] = made.read_bytes()
        cls.out = out
        cls.lines = {}
        for text in out.splitlines():
            parts = text.split(" ", 2)
            if len(parts) == 3:
                cls.lines[parts[0] + " " + parts[1]] = parts[2]

    def scene(self, key):
        got = self.lines.get("SCENE " + key)
        self.assertIsNotNone(got, key)
        return None if got == "nil" else dict(part.split("=", 1) for part in got.split(" "))

    def timing(self, key):
        got = self.lines.get("TIMING " + key)
        self.assertIsNotNone(got, key)
        return [float(v) for v in got.split(",")]

    def assert_timing(self, expected, got, places=1e-4):
        self.assertEqual(5, len(got))
        for want, have in zip(expected, got):
            self.assertAlmostEqual(want, have, delta=places)

    def pixels(self, key):
        got = self.lines.get("DEC " + key)
        self.assertIsNotNone(got, key)
        return None if got == "nil" else [int(v, 16) for v in got.split(",")]

    def texture(self, key):
        got = self.lines.get("TEX " + key)
        self.assertIsNotNone(got, key)
        return None if got == "nil" else dict(part.split("=", 1) for part in got.split(" "))

    def unpacked(self, key):
        got = self.lines.get("UNPACK " + key)
        self.assertIsNotNone(got, key)
        _, _, expect_ok, expect_out = self.unpacks[key][2:]
        self.assertEqual("ok={0}".format(str(expect_ok).lower()), got.split(" ")[0], key)
        if expect_out is not None:
            self.assertEqual(expect_out, self.outputs.get(key), key)

    def test_dxt1_four_colour_block(self):
        px = self.pixels("dxt1FourColour")
        self.assertEqual([0xFFFF0000, 0xFF0000FF, 0xFFAA0055, 0xFF5500AA], px[:4])
        self.assertEqual(0xFFFF0000, px[15])

    def test_dxt1_three_colour_block_has_a_see_through_fourth(self):
        self.assertEqual([0xFF7F007F, 0, 0xFF0000FF], self.pixels("dxt1ThreeColour")[:3])

    def test_dxt5_alpha_both_modes(self):
        eight = self.pixels("dxt5Eight")
        self.assertEqual([255, 0, 218, 36], [p >> 24 for p in eight[:4]])
        self.assertEqual(0xFFFFFF, eight[0] & 0xFFFFFF)
        self.assertEqual([51, 0, 255], [p >> 24 for p in self.pixels("dxt5Six")[:3]])

    def test_dxt3_alpha_is_stored_per_pixel(self):
        self.assertEqual([255, 0, 136, 255, 255], [p >> 24 for p in self.pixels("dxt3")[:5]])

    def test_edge_blocks_are_cropped_to_the_texture(self):
        px = self.pixels("cropped")
        self.assertEqual(30, len(px))
        self.assertEqual(0xFF0000FF, px[5])
        self.assertEqual(0xFFFFFFFF, px[4 * 6])
        self.assertEqual(0xFFFF0000, px[4 * 6 + 5])

    def test_short_data_is_not_decoded(self):
        self.assertIsNone(self.pixels("shortData"))

    def test_clear_share_counts_see_through_pixels(self):
        self.assertEqual("0.0", self.lines.get("CLEAR zero"))
        self.assertEqual("0.5", self.lines.get("CLEAR half"))
        self.assertEqual("1.0", self.lines.get("CLEAR empty"))

    def test_dxt1_is_always_a_slide_alpha_formats_go_by_how_clear_they_are(self):
        self.assertEqual("true", self.lines.get("SLIDE dxt1SomeBlack"))
        self.assertEqual("false", self.lines.get("SLIDE dxt5SomeBlack"))
        self.assertEqual("true", self.lines.get("SLIDE dxt5Opaque"))

    def test_unpack_round_trips(self):
        self.unpacked("roundTrip")

    def test_unpack_refuses_what_is_not_a_scene(self):
        for key in ("notRaf", "cutShort", "overCap", "pastTheEnd"):
            with self.subTest(key):
                self.unpacked(key)

    def test_swift_unpack_cap_is_counted_on_bytes_written(self):
        self.unpacked("swiftAtCap")
        self.unpacked("swiftOneOverCap")

    def test_swift_unpack_refuses_short_dictionary_and_out_of_range_windows(self):
        for key in ("swiftCutInHalf", "swiftFdict", "swiftWindowPastEnd", "swiftUnderHeader"):
            with self.subTest(key):
                self.unpacked(key)

    def test_swift_unpack_trailer_cut_is_accepted(self):
        # Apple's zlib decoder hides the Adler-32 trailer, so losing only those four bytes still unpacks; a trailer check would show here.
        self.unpacked("swiftTrailerCut")

    def test_swift_huge_pitch_one_block_high_reads_one_row(self):
        t = self.texture("hugePitchOneRow")
        self.assertIsNotNone(t)
        self.assertEqual(str(0xFFFFFFF0), t["pitch"])
        self.assertEqual("16", t["size"])
        self.assertEqual(str(8 * 4), t["pixels"])
        self.assertEqual("ffff0000", t["first"])
        self.assertIsNone(self.texture("hugePitchTwoRows"))

    def test_swift_texture_wider_than_4096_is_refused(self):
        self.assertEqual("4096", self.texture("wide4096")["width"])
        self.assertIsNone(self.texture("wide4097"))

    def test_swift_picture_texture_is_left_to_the_platform(self):
        t = self.texture("jpegPicture")
        self.assertEqual(("4096", "true", "1280", "720", "nil", "0"),
                         (t["format"], t["picture"], t["width"], t["height"], t["pixels"], t["dxt"]))

    def test_swift_is_slide_at_one_per_cent(self):
        self.assertEqual("true", self.lines.get("SLIDE dxt5OnePercent"))
        self.assertEqual("false", self.lines.get("SLIDE dxt5TwoPercent"))
        self.assertEqual("true", self.lines.get("SLIDE dxt1AllClear"))

    def test_scene_lists_full_screen_layers_in_draw_order(self):
        s = self.scene("listsLayers")
        self.assertEqual("bg01,wave,bg02", s["layers"])
        self.assertEqual(str(SCREEN_W), s["width0"])
        self.assertEqual("-8.0", s["z0"])
        self.assertEqual("ffff0000", s["px00"])
        self.assertEqual("128", s["a10"])
        self.assert_timing([10, 1.5, 0, 0, 0], [float(v) for v in s["timing"].split(",")], 0)

    def test_timing_comes_from_the_script(self):
        self.assert_timing([10, 1.5, 0, 0, 0], [float(v) for v in self.scene("timingFromScript")["timing"].split(",")], 0)

    def test_zoom_comes_from_the_camera_timer(self):
        got = self.timing("zoomFromCamera")
        self.assertEqual([7.0, 1.0], got[:2])
        self.assertAlmostEqual(55.995 / 52.995 - 1, got[2], delta=1e-4)
        self.assertEqual([5.0, 4.0], got[3:])

    def test_alpha_slideshow_template_fades_without_an_interpolation(self):
        got = self.timing("alphaSlideshow")
        self.assertEqual(8.0, got[0])
        self.assertAlmostEqual(2.3, got[1], delta=1e-6)
        self.assertEqual(0.0, got[2])

    def test_unreadable_scripts_keep_the_defaults(self):
        for key in ("unreadableNull", "unreadableEmpty", "unreadable55", "unreadableCut60"):
            with self.subTest(key):
                self.assert_timing([10, 1.5, 0, 0, 0], self.timing(key), 0)

    def test_scene_without_full_screen_layers_is_no_slideshow(self):
        self.assertIsNone(self.scene("noFullScreen"))

    def test_damaged_scenes_are_refused(self):
        for key in ("damagedCut40", "damagedMagic", "damagedCount", "damagedFiles"):
            with self.subTest(key):
                self.assertIsNone(self.scene(key))

    def test_layers_hidden_by_a_collapsed_mesh_are_left_out(self):
        self.assertEqual("bg01,bg02", self.scene("collapsedHidden")["layers"])

    def test_layer_whose_pixels_are_cut_off_is_dropped(self):
        self.assertEqual("bg01", self.scene("pixelsCutOff")["layers"])

    def test_swift_empty_name_keeps_every_later_index(self):
        self.assert_timing([6, 2, 0, 0, 0], self.timing("swiftEmptyName"), 0)

    def test_swift_non_finite_floats_give_a_finite_timing(self):
        for key in ("swiftNanInterval", "swiftInfInterval", "swiftNanZoomZ", "swiftInfZoomZ", "swiftNanMove"):
            with self.subTest(key):
                got = self.timing(key)
                self.assertTrue(all(math.isfinite(v) for v in got), got)
        self.assert_timing([10, 1.5, 0, 0, 0], self.timing("swiftNanInterval"), 0)
        self.assert_timing([120, 0, 0, 0, 0], self.timing("swiftInfInterval"), 0)
        self.assert_timing([7, 1, 0.25, 5, 4], self.timing("swiftInfZoomZ"), 0)

    def test_swift_bad_indexes_are_skipped(self):
        self.assert_timing([10, 1.5, 0, 0, 0], self.timing("swiftBadIndexes"), 0)

    def test_swift_bad_actor_id_refuses_the_scene(self):
        self.assertIsNone(self.scene("swiftBadActorId"))

    def test_fuzz_scene_and_timing_never_trap(self):
        self.assertEqual("layers=3", self.lines.get("FUZZWHOLE scene"))
        self.assertEqual("truncations={0} flips=3000 done=1".format(len(self.fuzz_scene) + 1), self.lines.get("FUZZ scene"))
        self.assertEqual("truncations={0} flips=3000 done=1".format(len(self.fuzz_zoom) + 1), self.lines.get("FUZZ timing"))
        self.assertNotIn("NONFINITE", self.out)

    def test_fuzz_truncations_and_flips_never_trap(self):
        for key, data in self.fuzz.items():
            with self.subTest(key):
                self.assertEqual("truncations={0} flips=3000 done=1".format(len(data) + 1), self.lines.get("FUZZ " + key))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], animation_problems(read(ANIM)))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(ANIM)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = animation_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_subscript(self):
        self.flagged("subscript", "let r = ByteReader(data)", "let r = ByteReader(data); _ = data[0]")

    def test_mutation_cap_raised(self):
        self.flagged("caps", "CHUNK_BYTES = 256 << 10", "CHUNK_BYTES = 512 << 10")

    def test_mutation_format_word(self):
        self.flagged("formats", "DXT3 = 0x87", "DXT3 = 0x89")


if __name__ == "__main__":
    unittest.main()
