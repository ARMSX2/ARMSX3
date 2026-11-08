#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ThemeBytes.swift, run on the Mac host, reads untrusted theme bytes through range-checked readers that throw instead
of trapping, and inflates capped zlib with the RFC 1950 checks; Compression hides the Adler-32 trailer, so trailer-only
cuts and flips are accepted and a `shortcut:` comment says so. It says nothing about real themes or a device."""

import json
import re
import tempfile
import unittest
import zlib
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import raw_deflate, valid_flg, zlib_stream, zlib_with_header

IOS = Path(__file__).resolve().parents[1]
BYTES = IOS / "app" / "Themes" / "ThemeBytes.swift"
HELPER = IOS / "tests" / "swiftc_host.py"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")


def payload():
    """About 600 KB: an LCG's bytes (barely compressible, so the packed form passes 256 KiB) and a text run."""
    state = 12345
    noise = bytearray()
    for _ in range(400000):
        state = (state * 1103515245 + 12345) & 0x7FFFFFFF
        noise.append(state >> 23)
    return bytes(noise) + b"ARMSX3 theme bytes " * 10000


SMALL = b"a small stream that is cut short at every length " * 40
SMALL_Z = len(zlib_stream(SMALL, 6))


def write_fixtures(tmp):
    tmp = Path(tmp)
    data = payload()
    (tmp / "payload.raw").write_bytes(data)
    for level in (1, 6, 9):
        (tmp / "z1-{0}.z".format(level)).write_bytes(zlib_stream(data, level))
    small = SMALL
    (tmp / "small.raw").write_bytes(small)
    (tmp / "small.z").write_bytes(zlib_stream(small, 6))
    raw = raw_deflate(small) + zlib.adler32(small).to_bytes(4, "big")
    (tmp / "cm7.z").write_bytes(zlib_with_header(0x77, valid_flg(0x77), raw))
    (tmp / "cinfo8.z").write_bytes(zlib_with_header(0x88, valid_flg(0x88), raw))
    (tmp / "badcheck.z").write_bytes(zlib_with_header(0x78, (valid_flg(0x78) + 1) & 0xFF, raw))
    (tmp / "fdict.z").write_bytes(zlib_with_header(0x78, valid_flg(0x78, fdict=True), raw))
    (tmp / "good.z").write_bytes(zlib_with_header(0x78, valid_flg(0x78), raw))


EXPECTED = {
    "B1": "B1 u8=1 be16=258 le16=513 be32=16909060 le32=67305985 refused=11/11",
    "B2": "B2 empty=0,0 refused=4/4",
    "B3": "B3 same=true slice=4,5,6 past=refused",
    "B4": "B4 nan=0 inf=max ninf=min big=max nbig=min pos=3 neg=-3",
    "B5": "B5 three=[a||b] two=[a|]",
    "B6": "B6 latin1=41,E9,FF odd=41,FFFD surrogate=FFFD,41",
    "Z1-1": "Z1-1 header=7801 same=true",
    "Z1-6": "Z1-6 header=789c same=true",
    "Z1-9": "Z1-9 header=78da same=true",
    "Z2": "Z2 cm7=refused cinfo8=refused badcheck=refused fdict=refused good=ok",
    "Z2t": "Z2t accepted=" + ",".join(str(n) for n in range(SMALL_Z - 4, SMALL_Z)),
    "Z2c": "Z2c over=refused equal=ok",
    "Z2f": "Z2f flipped=accepted,accepted,accepted,accepted",
    "Z3": "Z3 same=true pieces=many past=refused",
    "Z4": "Z4 wikipedia=11E60398 empty=1",
}

DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }
func refused(_ body: () throws -> Void) -> Bool {
    do { try body(); return false } catch is ThemeDamaged { return true } catch { return false }
}
func hex(_ s: String) -> String { s.unicodeScalars.map { String(format: "%X", $0.value) }.joined(separator: ",") }

// B1
let r = ByteReader([1, 2, 3, 4, 5])
let bad: [() throws -> Void] = [
    { _ = try r.u8(-1) }, { _ = try r.u8(r.count) },
    { _ = try r.be16(-1) }, { _ = try r.be16(r.count - 1) }, { _ = try r.le16(r.count - 1) },
    { _ = try r.be32(-1) }, { _ = try r.be32(r.count - 3) }, { _ = try r.le32(r.count - 3) },
    { _ = try r.be32(Int.max - 3) }, { _ = try r.le16(Int.max - 3) }, { _ = try r.slice(Int.max - 3, 4) },
]
print("B1 u8=\(try! r.u8(0)) be16=\(try! r.be16(0)) le16=\(try! r.le16(0)) be32=\(try! r.be32(0)) le32=\(try! r.le32(0)) refused=\(bad.filter { refused($0) }.count)/\(bad.count)")

// B2
let a = ArrayByteSource([1, 2, 3])
let empties = "\(try! a.read(0, 0).count),\(try! a.read(a.size, 0).count)"
let bad2: [() throws -> Void] = [{ _ = try a.read(a.size, 1) }, { _ = try a.read(-1, 1) }, { _ = try a.read(0, -1) }, { _ = try a.read(1, Int.max) }]
print("B2 empty=\(empties) refused=\(bad2.filter { refused($0) }.count)/\(bad2.count)")

// B3
let parent = Data([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
let slice = parent[3 ..< 8]
let ds = DataByteSource(slice)
let window = try! ds.read(1, 3)
print("B3 same=\(window == (try! ArrayByteSource([3, 4, 5, 6, 7]).read(1, 3))) slice=\(window.map(String.init).joined(separator: ",")) past=\(refused { _ = try ds.read(3, 3) } ? "refused" : "read")")

// B4
func word(_ n: Int) -> String { n == .max ? "max" : n == .min ? "min" : String(n) }
print("B4 nan=\(word(saturatingInt(.nan))) inf=\(word(saturatingInt(.infinity))) ninf=\(word(saturatingInt(-.infinity))) big=\(word(saturatingInt(1e300))) nbig=\(word(saturatingInt(-1e300))) pos=\(word(saturatingInt(3.9))) neg=\(word(saturatingInt(-3.9)))")

// B5
print("B5 three=[\(nulSplit("a\u{0}\u{0}b", count: 3).joined(separator: "|"))] two=[\(nulSplit("a\u{0}\u{0}b", count: 2).joined(separator: "|"))]")

// B6
let odd = ByteReader([0x41, 0x00, 0x42])
let sur = ByteReader([0x00, 0xD8, 0x41, 0x00])
print("B6 latin1=\(hex(try! ByteReader([0x41, 0xE9, 0xFF]).latin1(at: 0, count: 3))) odd=\(hex(try! odd.utf16le(at: 0, count: 3))) surrogate=\(hex(try! sur.utf16le(at: 0, count: 4)))")

// Z1
let raw = load("payload.raw")
for level in [1, 6, 9] {
    let z = load("z1-\(level).z")
    let out = try? ThemeZlib.inflate(z, cap: 64 << 20)
    print("Z1-\(level) header=\(String(format: "%02x%02x", z[0], z[1])) same=\(out == raw)")
}

// Z2
func word2(_ name: String) -> String { refused { _ = try ThemeZlib.inflate(load(name), cap: 1 << 20) } ? "refused" : "ok" }
print("Z2 cm7=\(word2("cm7.z")) cinfo8=\(word2("cinfo8.z")) badcheck=\(word2("badcheck.z")) fdict=\(word2("fdict.z")) good=\(word2("good.z"))")
let small = load("small.z"), smallRaw = load("small.raw")
var accepted: [Int] = []
for n in 0 ..< small.count where !refused({ _ = try ThemeZlib.inflate(Array(small[0 ..< n]), cap: 1 << 20) }) { accepted.append(n) }
print("Z2t accepted=\(accepted.isEmpty ? "none" : accepted.map(String.init).joined(separator: ","))")
let over = refused { _ = try ThemeZlib.inflate(small, cap: smallRaw.count - 1) }
let equal = (try? ThemeZlib.inflate(small, cap: smallRaw.count)) == smallRaw
print("Z2c over=\(over ? "refused" : "ok") equal=\(equal ? "ok" : "refused")")

// Z2f
var flips: [String] = []
for back in 1 ... 4 {
    var f = small
    f[f.count - back] ^= 0x01
    flips.append(refused { _ = try ThemeZlib.inflate(f, cap: 1 << 20) } ? "refused" : "accepted")
}
print("Z2f flipped=\(flips.joined(separator: ","))")

// Z3
let z6 = load("z1-6.z")
let big = ArrayByteSource([UInt8](repeating: 0xAB, count: 1000) + z6 + [UInt8](repeating: 0xCD, count: 500))
var streamed: [UInt8] = []
var pieces = 0
let ok3 = !refused { try ThemeZlib.inflate(big, offset: 1000, size: z6.count, cap: 64 << 20) { streamed += $0; pieces += 1 } }
let past = refused { try ThemeZlib.inflate(big, offset: 1000, size: z6.count + 1000, cap: 64 << 20) { _ in } }
print("Z3 same=\(ok3 && streamed == raw) pieces=\(pieces > 1 ? "many" : String(pieces)) past=\(past ? "refused" : "read")")

// Z4
print("Z4 wikipedia=\(String(format: "%08X", ThemeZlib.adler32(Array("Wikipedia".utf8)))) empty=\(ThemeZlib.adler32([]))")
"""


def bytes_problems(code_raw, helper):
    """code_raw: ThemeBytes.swift as read; helper: swiftc_host.py as read."""
    problems = []
    code = without_comments(code_raw)
    if sorted(IMPORT.findall(code)) != ["Compression", "Foundation"]:
        problems.append("host: ThemeBytes.swift imports {0}; exactly Compression and Foundation".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: ThemeBytes.swift logs")
    if "COMPRESSION_ZLIB" not in code or "compression_stream_process(" not in code or "0x20" not in code:
        problems.append("zlib: ThemeBytes.swift does not inflate with COMPRESSION_ZLIB and refuse FDICT (0x20)")
    if "ThemeBytes.swift" not in helper or "Compression" not in helper:
        problems.append("helper: swiftc_host.py's scope sentence does not name ThemeBytes.swift and Compression")
    if not re.search(r"shortcut:[^\n]*Adler-32", code_raw):
        problems.append("a3: ThemeBytes.swift has no `shortcut:` comment naming the skipped Adler-32 check")
    return problems


class TheReaderHolds(unittest.TestCase):
    """B1-B6 the checked reader, Z1-Z4 the zlib wrapper; one exact line per case."""

    @classmethod
    def setUpClass(cls):
        read(BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            write_fixtures(tmp)
            out = compile_and_run([BYTES], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key):
        self.assertEqual(EXPECTED[key], self.lines.get(key))

    def test_b1_reads_and_refuses(self):
        self.check("B1")

    def test_b2_array_source_ranges(self):
        self.check("B2")

    def test_b3_data_slice_indexes_from_start(self):
        self.check("B3")

    def test_b4_saturating_int(self):
        self.check("B4")

    def test_b5_nul_split_keeps_empty_pieces(self):
        self.check("B5")

    def test_b6_latin1_and_utf16(self):
        self.check("B6")

    def test_z1_round_trips_three_levels(self):
        for key in ("Z1-1", "Z1-6", "Z1-9"):
            with self.subTest(key):
                self.check(key)

    def test_z2_bad_headers_refused(self):
        self.check("Z2")

    def test_z2_every_cut_into_deflate_refused(self):
        self.check("Z2t")

    def test_z2_cap_counted_on_output(self):
        self.check("Z2c")

    def test_z2f_adler_trailer_skipped_a3(self):
        self.check("Z2f")

    def test_z3_streamed_window(self):
        self.check("Z3")

    def test_z4_adler32(self):
        self.check("Z4")


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], bytes_problems(read(BYTES), read(HELPER)))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(BYTES)
        cls.helper = read(HELPER)

    def flagged(self, prefix, code=None, helper=None):
        problems = bytes_problems(self.code if code is None else code, self.helper if helper is None else helper)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def edited(self, old, new):
        self.assertIn(old, self.code)
        return self.code.replace(old, new, 1)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", code=self.edited("import Foundation", "import Foundation\nimport UIKit"))

    def test_mutation_log(self):
        self.flagged("log", code=self.edited("import Foundation", "import Foundation\nfunc probe(_ s: String) { NSLog(s) }"))

    def test_mutation_zlib_swapped(self):
        self.flagged("zlib", code=self.edited("COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB",
                                              "COMPRESSION_STREAM_DECODE, COMPRESSION_LZFSE"))

    def test_mutation_a3_shortcut_removed(self):
        self.flagged("a3", code=self.edited("// shortcut: no Adler-32 check", "// no Adler-32 check"))

    def test_mutation_helper_scope_not_widened(self):
        self.assertIn("ThemeBytes.swift", self.helper)
        self.flagged("helper", helper=self.helper.replace("ThemeBytes.swift", "Other.swift"))


if __name__ == "__main__":
    unittest.main()
