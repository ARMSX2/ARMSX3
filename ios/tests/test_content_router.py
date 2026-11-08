#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ContentRouter.swift, run on the Mac host over built fixture files, classifies picked files by their header bytes,
orders names as Android does and plans installs; static checks keep it path-free and silent.
It does not show the core opening a split set's other parts."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
ROUTER = IOS / "app" / "Library" / "ContentRouter.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
PATHS = re.compile(r"\bURL\b|\.path\b")

# name -> bytes, written by Python into the temporary directory before the driver runs.
FIXTURES = {
    "PS3UPDAT.PUP": b"SCEUF\x00\x00\x00" + bytes(56),
    "game.rap": b"\x7fPKG" + bytes(60),
    "content.edat": b"NPD\x00" + bytes(60),
    "lic16.rap": bytes(range(1, 17)),
    "name_03.pkg": bytes(range(7, 71)) * 4,
    "game.pkg": bytes(range(9, 73)) * 4,
    "licence.rap": bytes(range(1, 33)),
    "name_00.pkg": bytes(range(11, 75)) * 4,
}

DRIVER = r"""
import Foundation

let dir = @@TMP@@
func kind(_ name: String) -> ContentKind {
    let data = FileManager.default.contents(atPath: dir + "/" + name)!
    return ContentRouter.sniff(header: data.prefix(64), fileName: name, fileSize: Int64(data.count))
}
func show(_ k: ContentKind) -> String {
    switch k {
    case .pup: return "pup"
    case .pkg: return "pkg"
    case .pkgPart(let base, let index): return "part(\(base),\(index))"
    case .rap: return "rap"
    case .edat: return "edat"
    case .unknown: return "unknown"
    }
}
let names = ["PS3UPDAT.PUP", "game.rap", "content.edat", "lic16.rap", "name_03.pkg", "game.pkg", "licence.rap", "name_00.pkg"]
print("C1 " + names.map { show(kind($0)) }.joined(separator: " "))

let nines = String(repeating: "9", count: 40)
let big = "1" + String(repeating: "0", count: 40)
let l = ContentRouter.naturalLess
print("C2 \(l("part2", "part10")) \(l("part10", "part2")) \(l("a_01", "a_2")) \(l("game_a", "GAME_B")) \(l("x007", "x7")) \(l("x7", "x007")) \(l("a" + nines, "a" + big)) \(l("a" + big, "a" + nines))")

func line(_ tag: String, _ items: [ContentRouter.Item]) {
    let p = ContentRouter.plan(items)
    let errors = p.errors.map { "\($0.index):\($0.reason)" }.joined(separator: ";")
    print("\(tag) firmware=\(p.firmware) packages=\(p.packages) covered=\(p.coveredParts) licences=\(p.licences) errors=[\(errors)]")
}
typealias I = ContentRouter.Item
line("C3", [I(index: 0, name: "lic.rap", kind: .rap), I(index: 1, name: "game_update.pkg", kind: .pkg),
            I(index: 2, name: "PS3UPDAT.PUP", kind: .pup), I(index: 3, name: "game.pkg", kind: .pkg),
            I(index: 4, name: "c.edat", kind: .edat)])
line("C4", [I(index: 0, name: "x_02.pkg", kind: .pkgPart(base: "x", index: 2)), I(index: 1, name: "x_00.pkg", kind: .pkg),
            I(index: 2, name: "x_01.pkg", kind: .pkgPart(base: "x", index: 1))])
line("C5", [I(index: 0, name: "x_01.pkg", kind: .pkgPart(base: "x", index: 1)), I(index: 1, name: "x_02.pkg", kind: .pkgPart(base: "x", index: 2))])
line("C6", [I(index: 0, name: "x_00.pkg", kind: .pkg), I(index: 1, name: "x_02.pkg", kind: .pkgPart(base: "x", index: 2))])
line("C7", [I(index: 0, name: "notes.txt", kind: .unknown)])
"""

EXPECTED = {
    "C1": "C1 pup pkg edat rap part(name,3) unknown unknown unknown",
    "C2": "C2 true false true true false false true false",
    "C3": "C3 firmware=[2] packages=[3, 1] covered=[] licences=[4, 0] errors=[]",
    "C4": "C4 firmware=[] packages=[1] covered=[2, 0] licences=[] errors=[]",
    "C5": "C5 firmware=[] packages=[] covered=[] licences=[] errors=[0:missing part 00]",
    "C6": "C6 firmware=[] packages=[] covered=[] licences=[] errors=[0:missing part 01]",
    "C7": "C7 firmware=[] packages=[] covered=[] licences=[] errors=[0:not a PS3 file]",
}


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def router_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: ContentRouter.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: ContentRouter.swift logs; no path or title may be logged")
    if PATHS.search(code):
        problems.append("path: ContentRouter.swift reads a URL or a path; it sees names and header bytes only")
    return problems


class TheRouterRoutes(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(ROUTER)
        with tempfile.TemporaryDirectory() as tmp:
            for name, data in FIXTURES.items():
                Path(tmp, name).write_bytes(data)
            out = compile_and_run([ROUTER], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key):
        self.assertEqual(EXPECTED[key], self.lines.get(key), key)

    def test_c1_the_bytes_decide_the_kind(self):
        self.check("C1")

    def test_c2_natural_order(self):
        self.check("C2")

    def test_c3_firmware_then_packages_then_licences(self):
        self.check("C3")

    def test_c4_an_out_of_order_split_set_installs_from_part_00(self):
        self.check("C4")

    def test_c5_a_missing_part_00_is_named(self):
        self.check("C5")

    def test_c6_a_missing_middle_part_is_named(self):
        self.check("C6")

    def test_c7_an_unknown_file_is_an_error(self):
        self.check("C7")


class TheRouterFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], router_problems(read(ROUTER)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(ROUTER.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class TheRouterGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(ROUTER)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = router_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_a_name(self):
        self.assert_names(self.text + "\nfunc probe(_ s: String) { print(s) }\n", "log")

    def test_mutation_read_a_path(self):
        self.assert_names(self.text + "\nfunc probe(_ u: URL) -> String { u.lastPathComponent }\n", "path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
