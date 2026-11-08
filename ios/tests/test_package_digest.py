#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PackageDigest.swift, run on the Mac host over built files, accepts a package only when its size, SHA-1 and trailer
match the published values, reading in 4 MiB chunks; static checks pin its imports, streaming and silence.
It says nothing about Sony's real packages or a phone's hashing speed."""

import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
DIGEST = IOS / "app" / "Install" / "PackageDigest.swift"
HELPER = IOS / "tests" / "swiftc_host.py"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
PLAN_ID = re.compile(r"D-17-|GUPD-|17-0")
MIB = 1 << 20


def body_bytes(length):
    return (bytes(range(256)) * (length // 256 + 1))[:length]


def packaged(body):
    """The body followed by its trailer: the SHA-1 of the body, then 12 zero bytes."""
    return body + hashlib.sha1(body).digest() + bytes(12)


def flipped(data, at):
    return data[:at] + bytes([data[at] ^ 0x01]) + data[at + 1:]


def cases(tmp):
    """[[key, file name, published size, published hex]], writing every file into `tmp`."""
    tmp = Path(tmp)
    body = body_bytes(MIB + 7)
    good = packaged(body)
    hexed = hashlib.sha1(body).hexdigest()
    files = {"good.pkg": good, "short.pkg": good[:-1], "long.pkg": good + b"\0",
             "tiny.pkg": packaged(b""), "empty.pkg": b"", "big.pkg": packaged(body_bytes(9 * MIB - 32))}
    for name, at in (("f0", 0), ("fmid", len(body) // 2), ("flast", len(body) - 1),
                     ("ftrail", len(body) + 5), ("fzero", len(body) + 25)):
        files[name + ".pkg"] = flipped(good, at)
    for name, data in files.items():
        (tmp / name).write_bytes(data)
    (tmp / "folder.pkg").mkdir()
    other = hashlib.sha1(b"another body").hexdigest()
    big = files["big.pkg"]
    return [
        ["D1", "good.pkg", len(good), hexed],
        ["D2.0", "f0.pkg", len(good), hexed],
        ["D2.mid", "fmid.pkg", len(good), hexed],
        ["D2.last", "flast.pkg", len(good), hexed],
        ["D2.trail", "ftrail.pkg", len(good), hexed],
        ["D2.zero", "fzero.pkg", len(good), hexed],
        ["D2.other", "good.pkg", len(good), other],
        ["D3.short", "short.pkg", len(good), hexed],
        ["D3.long", "long.pkg", len(good), hexed],
        ["D3.32", "tiny.pkg", 32, hashlib.sha1(b"").hexdigest()],
        ["D3.0", "empty.pkg", 0, hashlib.sha1(b"").hexdigest()],
        ["D4.absent", "no-such.pkg", len(good), hexed],
        ["D4.folder", "folder.pkg", len(good), hexed],
        ["D5", "good.pkg", len(good), hexed.upper()],
        ["D6", "big.pkg", len(big), hashlib.sha1(big[:-32]).hexdigest()],
    ]


DRIVER = r"""
import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
let rows = try! JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("cases.json"))) as! [[Any]]
for row in rows {
    let key = row[0] as! String, name = row[1] as! String, size = (row[2] as! NSNumber).int64Value, sha1 = row[3] as! String
    print("\(key) \(PackageDigest.verify(at: dir.appendingPathComponent(name), size: size, sha1: sha1))")
}
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def comment_lines(text):
    return [line for line in text.splitlines() if line.strip().startswith("//")]


def pool_bodies(code):
    bodies = []
    for match in re.finditer(r"\bautoreleasepool\b", code):
        start, end = brace_span(code, match.end())
        bodies.append(code[start:end])
    return bodies


def digest_problems(raw, helper):
    """raw: PackageDigest.swift as read; helper: swiftc_host.py as read."""
    code = without_comments(raw)
    problems = []
    if sorted(IMPORT.findall(code)) != ["CryptoKit", "Foundation"]:
        problems.append("imports: PackageDigest.swift imports {0}; exactly CryptoKit and Foundation".format(
            IMPORT.findall(code)))
    if "FileHandle" not in code or not any("read(upToCount:" in body for body in pool_bodies(code)):
        problems.append("stream: PackageDigest.swift does not read through FileHandle inside autoreleasepool")
    if not re.search(r"\bchunk\s*=\s*4\s*<<\s*20\b", code):
        problems.append("chunk: PackageDigest.swift does not read in 4 MiB chunks")
    if "Data(contentsOf:" in code:
        problems.append("whole: PackageDigest.swift reads a whole file")
    if LOGGING.search(code):
        problems.append("log: PackageDigest.swift logs")
    if any(PLAN_ID.search(line) for line in comment_lines(raw)):
        problems.append("comment: PackageDigest.swift names a plan ID in a comment")
    if "PackageDigest.swift" not in helper or "CryptoKit" not in helper:
        problems.append("helper: swiftc_host.py's scope sentence does not name PackageDigest.swift and CryptoKit")
    return sorted(problems)


class TheDigestHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(DIGEST)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "cases.json").write_text(json.dumps(cases(tmp)), encoding="utf-8")
            out = compile_and_run([DIGEST], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def results(self, prefix):
        found = {}
        for line in self.lines:
            key, result = line.split(" ", 1)
            if key == prefix or key.startswith(prefix + "."):
                found[key] = result
        self.assertTrue(found, "{0} in {1}".format(prefix, self.lines))
        return found

    def test_d1_a_whole_package_passes(self):
        self.assertEqual({"D1": "ok"}, self.results("D1"))

    def test_d2_a_flipped_byte_anywhere_fails(self):
        self.assertEqual({"D2.0": "mismatch", "D2.mid": "mismatch", "D2.last": "mismatch", "D2.trail": "mismatch",
                          "D2.zero": "mismatch", "D2.other": "mismatch"}, self.results("D2"))

    def test_d3_a_wrong_length_fails_as_wrong_size(self):
        self.assertEqual({"D3.short": "wrongSize", "D3.long": "wrongSize", "D3.32": "wrongSize", "D3.0": "wrongSize"},
                         self.results("D3"))

    def test_d4_no_file_is_missing(self):
        self.assertEqual({"D4.absent": "missing", "D4.folder": "missing"}, self.results("D4"))

    def test_d5_upper_case_hex_passes(self):
        self.assertEqual({"D5": "ok"}, self.results("D5"))

    def test_d6_a_file_over_several_chunks_passes(self):
        self.assertEqual({"D6": "ok"}, self.results("D6"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], digest_problems(read(DIGEST), read(HELPER)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(DIGEST.parent / "NoSuchFile.swift")
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.raw = read(DIGEST)
        cls.helper = read(HELPER)

    def flagged(self, prefix, raw=None, helper=None):
        problems = digest_problems(self.raw if raw is None else raw, self.helper if helper is None else helper)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def edited(self, old, new):
        self.assertIn(old, self.raw)
        mutated = self.raw.replace(old, new, 1)
        self.assertNotEqual(self.raw, mutated)
        return mutated

    def test_mutation_imports_uikit(self):
        self.flagged("imports", raw=self.edited("import Foundation", "import Foundation\nimport UIKit"))

    def test_mutation_reads_outside_the_pool(self):
        self.flagged("stream", raw=self.edited("autoreleasepool", "withoutPool"))

    def test_mutation_chunk_grows(self):
        self.flagged("chunk", raw=self.edited("4 << 20", "64 << 20"))

    def test_mutation_reads_the_whole_file(self):
        self.flagged("whole", raw=self.edited("import Foundation",
                                               "import Foundation\nlet probe = try? Data(contentsOf: URL(fileURLWithPath: \"/\"))"))

    def test_mutation_log(self):
        self.flagged("log", raw=self.edited("import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }"))

    def test_mutation_plan_id_in_a_comment(self):
        self.flagged("comment", raw=self.edited("import Foundation", "// GUPD-05.\nimport Foundation"))

    def test_mutation_helper_scope_not_widened(self):
        self.assertIn("PackageDigest.swift", self.helper)
        self.flagged("helper", helper=self.helper.replace("PackageDigest.swift", "Other.swift"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
