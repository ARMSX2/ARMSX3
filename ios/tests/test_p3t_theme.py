#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""P3tTheme.swift, run on the Mac host over built themes, picks the theme's picture, name, preview and scene reference
as Android's P3tTheme.kt does, and refuses truncated or flipped files as damaged without a trap.
It says nothing about real themes, JPEG decoding, or a device."""

import json
import re
import tempfile
import unittest
import zlib
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import Theme, gim_rgba, jpeg, packed_preview, raw_deflate, valid_flg, zlib_with_header

IOS = Path(__file__).resolve().parents[1]
BYTES = IOS / "app" / "Themes" / "ThemeBytes.swift"
P3T = IOS / "app" / "Themes" / "P3tTheme.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
SUBSCRIPT = re.compile(r"\b(b|bytes|data|head|header|table|elements|strings|packed|probe)\[")
CAPS = ("0x50335446", "16 << 20", "32 << 20", "64 << 20", "4096", "256 << 10")

RAF = b"_RAF" + bytes(64)


def file_base(theme):
    """Where the file table starts in theme.bytes(): after the 64-byte header, the elements and the strings."""
    return 64 + len(theme.elements) + len(theme.strings)


def line(failure="none", picture=None, dynamic=False, anim=None, name=None):
    """The driver's summary line. picture: (source, width, height, jpeg bytes or None, rgba bytes or None)."""
    parts = ["failure=" + failure]
    if picture is not None:
        source, width, height, jpeg_bytes, rgba = picture
        parts += ["source=" + source, "size={0}x{1}".format(width, height),
                  "jpeg=" + (jpeg_bytes.hex() if jpeg_bytes is not None else "-"),
                  "rgba=" + (rgba.hex() if rgba is not None else "-")]
    parts += ["dynamic=" + ("true" if dynamic else "false"),
              "anim=" + ("none" if anim is None else "{0}:{1}".format(*anim)),
              "name=" + ("none" if name is None else name.encode("utf-8").hex())]
    return " ".join(parts)


def build_cases():
    """{key: (theme bytes, expected summary line)}."""
    cases = {}

    t = Theme()
    small, big = jpeg(1280, 720), jpeg(1920, 1080)
    t.element("theme")
    t.element("bgimagetable")
    t.element("bgimage", t.file_attr("hd", small), t.file_attr("sd", jpeg(720, 480)))
    t.element("bgimage", t.file_attr("sd", jpeg(854, 480)), t.file_attr("hd", big))
    cases["staticThemePrefersTheLargestHdPicture"] = (t.bytes(), line(picture=("hd", 1920, 1080, big, None)))

    title = "PlayStation®Move Ape Escape™　Theme:01"
    t = Theme()
    t.element("theme")
    t.element("info", t.string_attr("comment", "a theme"), t.string_attr("name", title),
              t.string_attr("author", "Sony Computer Entertainment Inc."))
    t.element("bgimagetable")
    hd = jpeg(1920, 1080)
    t.element("bgimage", t.file_attr("hd", hd))
    cases["themeNameIsRead"] = (t.bytes(), line(picture=("hd", 1920, 1080, hd, None), name=title))

    t = Theme()
    sd = jpeg(720, 480)
    t.element("bgimage", t.file_attr("sd", sd))
    cases["sdIsUsedWhenThereIsNoHd"] = (t.bytes(), line(picture=("sd", 720, 480, sd, None)))

    pixels = bytes((i * 7) & 0xFF for i in range(3 * 2 * 4))
    t = Theme()
    anim_at, _ = t.file(b"")
    t.element("bgimage", t.file_attr("anim", RAF))
    t.element("info", t.file_attr("preview", packed_preview(3, 2, pixels)))
    cases["dynamicThemeFallsBackToItsPreview"] = (
        t.bytes(), line(picture=("preview", 3, 2, None, pixels), dynamic=True, anim=(file_base(t) + anim_at, 68)))

    t = Theme()
    t.element("bgimage", t.file_attr("anim", RAF))
    cases["dynamicThemeWithoutAPreviewSaysSo"] = (
        t.bytes(), line(failure="dynamicOnly", dynamic=True, anim=(file_base(t), 68)))

    t = Theme()
    t.element("theme")
    t.element("icontable")
    cases["themeWithNoPictureAtAll"] = (t.bytes(), line(failure="noPicture"))

    cases["otherFilesAreNotThemes-jpeg"] = (jpeg(10, 10) + bytes(100), line(failure="notATheme"))
    cases["otherFilesAreNotThemes-short"] = (bytes(3), line(failure="notATheme"))

    t = Theme()
    t.element("bgimage", t.file_attr("hd", jpeg(1920, 1080)))
    whole = t.bytes()
    cases["truncatedThemeIsDamaged-40"] = (whole[:40], line(failure="damaged"))
    cases["truncatedThemeIsDamaged-minus5"] = (whole[:-5], line(failure="damaged"))
    cases["tablePointingPastTheEndIsDamaged"] = (t.bytes(string_offset_override=0x7FFFFFF0), line(failure="damaged"))

    t = Theme()
    t.element("bgimage", count=0x7FFFFFFF)
    cases["absurdAttributeCountIsDamaged"] = (t.bytes(), line(failure="damaged"))

    t = Theme()
    t.element("bgimage", ("hd", 6, 0x7FFF0000, 1000))
    cases["pictureReferenceOutsideTheFileIsSkipped"] = (t.bytes(), line(failure="noPicture"))

    t = Theme()
    t.element("bgimage", t.file_attr("anim", b"_RAF" + bytes(8)))
    t.element("info", t.file_attr("preview", zlib.compress(bytes(80 << 20))))
    cases["previewThatInflatesWithoutLimitIsRefused"] = (
        t.bytes(), line(failure="dynamicOnly", dynamic=True, anim=(file_base(t), 12)))

    # Swift-only.
    first, second = jpeg(1920, 1080) + b"\x01", jpeg(1920, 1080) + b"\x02"
    t = Theme()
    t.element("bgimage", t.file_attr("hd", first), t.file_attr("hd", second))
    cases["equalHdPicksTheFirst"] = (t.bytes(), line(picture=("hd", 1920, 1080, first, None)))

    gim = gim_rgba(3, 2, pixels)
    fdict = zlib_with_header(0x78, valid_flg(0x78, fdict=True), raw_deflate(gim) + zlib.adler32(gim).to_bytes(4, "big"))
    badhead = zlib_with_header(0x78, (valid_flg(0x78) + 1) & 0xFF, raw_deflate(gim) + zlib.adler32(gim).to_bytes(4, "big"))
    for word, stream in (("fdict", fdict), ("badHeader", badhead)):
        t = Theme()
        t.element("info", t.file_attr("preview", stream))
        cases["preview-" + word + "-static"] = (t.bytes(), line(failure="noPicture"))
        t = Theme()
        t.element("bgimage", t.file_attr("anim", RAF))
        t.element("info", t.file_attr("preview", stream))
        cases["preview-" + word + "-dynamic"] = (t.bytes(), line(failure="dynamicOnly", dynamic=True, anim=(file_base(t), 68)))

    wide = bytes((i * 11) & 0xFF for i in range(4 * 2 * 4))
    t = Theme()
    t.element("info", t.file_attr("preview", packed_preview(4, 2, wide, pitch_align=0)))
    cases["pitchAlignZeroUsesOne"] = (t.bytes(), line(picture=("preview", 4, 2, None, wide)))

    t = Theme()
    t.element("bgimage", t.file_attr("sd", jpeg(720, 480) + bytes(8)), t.file_attr("hd", RAF))
    raf_at = len(jpeg(720, 480)) + 8
    sd_pad = jpeg(720, 480) + bytes(8)
    cases["hdThatIsRafIsDynamic"] = (
        t.bytes(), line(picture=("sd", 720, 480, sd_pad, None), dynamic=True, anim=(file_base(t) + raf_at, 68)))

    raw_name = "Odd\u0001Name‮Theme"
    t = Theme()
    t.element("info", t.string_attr("name", raw_name))
    hd = jpeg(1280, 720)
    t.element("bgimage", t.file_attr("hd", hd))
    cases["nameKeepsItsCharacters"] = (t.bytes(), line(picture=("hd", 1280, 720, hd, None), name=raw_name))
    return cases


def fuzz_theme():
    """About 3 KB: two HD and one SD picture (padded), a preview, a name and an anim reference."""
    t = Theme()
    t.element("theme")
    t.element("info", t.string_attr("name", "Fuzz Theme"),
              t.file_attr("preview", packed_preview(8, 8, bytes((i * 13) & 0xFF for i in range(8 * 8 * 4)))))
    t.element("bgimagetable")
    t.element("bgimage", t.file_attr("hd", jpeg(1280, 720) + bytes(700)), t.file_attr("sd", jpeg(720, 480) + bytes(400)))
    t.element("bgimage", t.file_attr("hd", jpeg(1920, 1080) + bytes(700)), t.file_attr("anim", RAF))
    return t.bytes()


DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }
func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
func summary(_ r: P3tTheme.Result) -> String {
    var parts = ["failure=" + (r.failure.map { "\($0)" } ?? "none")]
    if let p = r.picture {
        parts += ["source=\(p.source)", "size=\(p.width)x\(p.height)", "jpeg=" + (p.jpeg.map(hex) ?? "-"),
                  "rgba=" + (p.rgba.map(hex) ?? "-")]
    }
    parts += ["dynamic=\(r.dynamic)", "anim=" + (r.anim.map { "\($0.offset):\($0.size)" } ?? "none"),
              "name=" + (r.name.map { hex(Array($0.utf8)) } ?? "none")]
    return parts.joined(separator: " ")
}

for key in @@KEYS@@ {
    print("\(key) \(summary(P3tTheme.read(ArrayByteSource(load(key + ".p3t")))))")
}

let fuzz = load("fuzz.p3t")
print("FUZZWHOLE \(P3tTheme.read(ArrayByteSource(fuzz)).picture?.width ?? -1)")
var truncations = 0
for n in 0 ... fuzz.count {
    _ = P3tTheme.read(ArrayByteSource(Array(fuzz[0 ..< n])))
    truncations += 1
}
var state: UInt64 = 0x16_01_2026
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
var flips = 0
for _ in 0 ..< 3000 {
    var copy = fuzz
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    _ = P3tTheme.read(ArrayByteSource(copy))
    flips += 1
}
print("FUZZ truncations=\(truncations) flips=\(flips) done=1")
"""


def p3t_problems(code_raw):
    """code_raw: P3tTheme.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: P3tTheme.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: P3tTheme.swift logs")
    for match in SUBSCRIPT.finditer(code):
        problems.append("subscript: P3tTheme.swift subscripts `{0}[` directly; read through ThemeBytes.swift".format(match.group(1)))
    for cap in CAPS:
        if cap not in code:
            problems.append("caps: P3tTheme.swift does not carry the Kotlin cap {0}".format(cap))
    return problems


class TheReaderHolds(unittest.TestCase):
    """The ported Kotlin cases, the Swift-only cases and the fuzz; one exact line per case."""

    @classmethod
    def setUpClass(cls):
        read(BYTES)
        read(P3T)
        cls.cases = build_cases()
        cls.fuzz_size = len(fuzz_theme())
        with tempfile.TemporaryDirectory() as tmp:
            for key, (data, _) in cls.cases.items():
                (Path(tmp) / (key + ".p3t")).write_bytes(data)
            (Path(tmp) / "fuzz.p3t").write_bytes(fuzz_theme())
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@KEYS@@", json.dumps(sorted(cls.cases)))
            out = compile_and_run([BYTES, P3T], driver, tmp)
        cls.lines = {}
        for text in out.splitlines():
            if text.strip():
                key, _, rest = text.partition(" ")
                cls.lines[key] = rest

    def check(self, *keys):
        for key in keys:
            with self.subTest(key):
                self.assertEqual(self.cases[key][1], self.lines.get(key))

    def test_static_theme_prefers_the_largest_hd_picture(self):
        self.check("staticThemePrefersTheLargestHdPicture")

    def test_theme_name_is_read(self):
        self.check("themeNameIsRead")

    def test_sd_is_used_when_there_is_no_hd(self):
        self.check("sdIsUsedWhenThereIsNoHd")

    def test_dynamic_theme_falls_back_to_its_preview(self):
        self.check("dynamicThemeFallsBackToItsPreview")

    def test_dynamic_theme_without_a_preview_says_so(self):
        self.check("dynamicThemeWithoutAPreviewSaysSo")

    def test_theme_with_no_picture_at_all(self):
        self.check("themeWithNoPictureAtAll")

    def test_other_files_are_not_themes(self):
        self.check("otherFilesAreNotThemes-jpeg", "otherFilesAreNotThemes-short")

    def test_truncated_theme_is_damaged(self):
        self.check("truncatedThemeIsDamaged-40", "truncatedThemeIsDamaged-minus5")

    def test_table_pointing_past_the_end_is_damaged(self):
        self.check("tablePointingPastTheEndIsDamaged")

    def test_absurd_attribute_count_is_damaged(self):
        self.check("absurdAttributeCountIsDamaged")

    def test_picture_reference_outside_the_file_is_skipped(self):
        self.check("pictureReferenceOutsideTheFileIsSkipped")

    def test_preview_that_inflates_without_limit_is_refused(self):
        self.check("previewThatInflatesWithoutLimitIsRefused")

    def test_swift_equal_hd_picks_the_first(self):
        self.check("equalHdPicksTheFirst")

    def test_swift_bad_preview_stream_gives_no_preview(self):
        self.check("preview-fdict-static", "preview-fdict-dynamic", "preview-badHeader-static", "preview-badHeader-dynamic")

    def test_swift_pitch_align_zero_uses_one(self):
        self.check("pitchAlignZeroUsesOne")

    def test_swift_hd_that_is_raf_is_dynamic(self):
        self.check("hdThatIsRafIsDynamic")

    def test_swift_name_keeps_its_characters(self):
        self.check("nameKeepsItsCharacters")

    def test_fuzz_truncations_and_flips_never_trap(self):
        self.assertEqual("1920", self.lines.get("FUZZWHOLE"))
        self.assertEqual("truncations={0} flips=3000 done=1".format(self.fuzz_size + 1), self.lines.get("FUZZ"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], p3t_problems(read(P3T)))

    def test_fuzz_theme_is_about_3_kb(self):
        self.assertTrue(2500 <= len(fuzz_theme()) <= 4000, len(fuzz_theme()))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(P3T)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = p3t_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_subscript(self):
        self.flagged("subscript", "let probe = try src.read(ref.offset, min(ref.size, PROBE_BYTES))",
                     "let probe = try src.read(ref.offset, min(ref.size, PROBE_BYTES)); _ = probe[0]")

    def test_mutation_cap_raised(self):
        self.flagged("caps", "MAX_PREVIEW_BYTES = 64 << 20", "MAX_PREVIEW_BYTES = 128 << 20")


if __name__ == "__main__":
    unittest.main()
