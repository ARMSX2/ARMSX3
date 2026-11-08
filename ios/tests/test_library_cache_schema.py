#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""LibraryCache.swift, run on the Mac host, round-trips the library cache and discards one that is stale, foreign,
damaged or from an older schema; static checks pin schema 3, its optional fields, the file name and atomic writes.
It says nothing about the library screen or a device."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
CACHE = IOS / "app" / "Library" / "LibraryCache.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
SCHEMA = re.compile(r"\bstatic\s+let\s+schema\s*=\s*(\d+)\b")
LOCKED = re.compile(r"\bvar\s+locked\s*:\s*Bool\?")
UNREADABLE = re.compile(r"\bvar\s+unreadable\s*:\s*Bool\?")
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")

# Written into a temporary directory at run time; C2 swaps "schema" in the saved JSON to stand for another build.
DRIVER = r"""
import Foundation

let dir = URL(fileURLWithPath: @@TMP@@).appendingPathComponent("armsx3", isDirectory: true)
let cache = LibraryCache(directory: dir)
let a = UUID(), b = UUID(), c = UUID()
let key = LibraryCache.key(folderIDs: [a, b])
let entries = [
    LibraryCache.Entry(serial: "NPEA00021", title: "flOw", category: "HG", bootPath: "/g/flow",
                       iconPath: "/g/flow/ICON0.PNG", backdropPath: nil, isISO: false, fromGamesYml: false),
    LibraryCache.Entry(serial: "BLUS30443", title: "Demon's Souls", category: "DG", bootPath: "/d/ds.iso",
                       iconPath: nil, backdropPath: "/d/PIC1.PNG", isISO: true, fromGamesYml: true),
]
let file = dir.appendingPathComponent("library-cache.json")

try! cache.save(key: key, entries: entries)
let back = cache.load(expectedKey: key)
print("C1 same=\(back == entries) file=\(FileManager.default.fileExists(atPath: file.path))")

func rewritten(schema: Int) -> [LibraryCache.Entry]? {
    try! cache.save(key: key, entries: entries)
    var json = try! JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
    json["schema"] = schema
    try! JSONSerialization.data(withJSONObject: json).write(to: file)
    return cache.load(expectedKey: key)
}
print("C2 older=\(rewritten(schema: LibraryCache.schema - 1) == nil) newer=\(rewritten(schema: LibraryCache.schema + 1) == nil) current=\(rewritten(schema: LibraryCache.schema) != nil)")

try! cache.save(key: key, entries: entries)
print("C3 added=\(cache.load(expectedKey: LibraryCache.key(folderIDs: [a, b, c])) == nil) removed=\(cache.load(expectedKey: LibraryCache.key(folderIDs: [a])) == nil)")

let whole = try! Data(contentsOf: file)
try! whole.prefix(whole.count / 2).write(to: file)
let truncated = cache.load(expectedKey: key) == nil
try! Data("not json at all".utf8).write(to: file)
let garbage = cache.load(expectedKey: key) == nil
try! FileManager.default.removeItem(at: file)
let missing = cache.load(expectedKey: key) == nil
print("C4 truncated=\(truncated) garbage=\(garbage) missing=\(missing)")

try! cache.save(key: key, entries: entries)
var old = try! JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
old["schema"] = 1
old["key"] = "v1|" + [a, b].map(\.uuidString).sorted().joined(separator: "|")
old["entries"] = (old["entries"] as! [[String: Any]]).map { var e = $0; e.removeValue(forKey: "locked"); return e }
try! JSONSerialization.data(withJSONObject: old).write(to: file)
let staleKey = cache.load(expectedKey: key) == nil
let staleOwnKey = cache.load(expectedKey: old["key"] as! String) == nil
print("C6 v1=\(staleKey) v1key=\(staleOwnKey) schema=\(LibraryCache.schema)")

var marked = entries
marked[0].locked = true
marked[1].locked = false
marked[1].unreadable = true
let unprobed = LibraryCache.Entry(serial: "ABCD12345", title: "Synthetic", category: "HG", bootPath: "/g/abcd",
                                  iconPath: nil, backdropPath: nil, isISO: false, fromGamesYml: false)
marked.append(unprobed)
try! cache.save(key: key, entries: marked)
let lockedBack = cache.load(expectedKey: key)
print("C7 same=\(lockedBack == marked) locked=\(lockedBack?.map { $0.locked.map { $0 ? "1" : "0" } ?? "nil" }.joined(separator: ",") ?? "none") unreadable=\(lockedBack?.map { $0.unreadable.map { $0 ? "1" : "0" } ?? "nil" }.joined(separator: ",") ?? "none")")

try! cache.save(key: key, entries: marked)
var v2 = try! JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
v2["schema"] = 2
v2["key"] = "v2|" + [a, b].map(\.uuidString).sorted().joined(separator: "|")
v2["entries"] = (v2["entries"] as! [[String: Any]]).map { var e = $0; e.removeValue(forKey: "unreadable"); return e }
try! JSONSerialization.data(withJSONObject: v2).write(to: file)
print("C8 v2=\(cache.load(expectedKey: key) == nil) v2key=\(cache.load(expectedKey: v2["key"] as! String) == nil)")

print("C5 sorted=\(LibraryCache.key(folderIDs: [a, b]) == LibraryCache.key(folderIDs: [b, a])) prefix=\(key.hasPrefix("v\(LibraryCache.schema)|")) differs=\(LibraryCache.key(folderIDs: [a]) != key)")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def cache_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: LibraryCache.swift imports {0}; it must import Foundation only".format(imports))
    schema = SCHEMA.search(code)
    if not schema:
        problems.append("schema: LibraryCache.swift declares no `static let schema = <integer>`")
    elif schema.group(1) != "3":
        problems.append("schema: LibraryCache.schema is {0}, not 3".format(schema.group(1)))
    if not LOCKED.search(code):
        problems.append("locked: LibraryCache.Entry declares no `var locked: Bool?`")
    if not UNREADABLE.search(code):
        problems.append("unreadable: LibraryCache.Entry declares no `var unreadable: Bool?`")
    if '"library-cache.json"' not in code:
        problems.append("file: LibraryCache.swift does not name library-cache.json")
    if ".atomic" not in code:
        problems.append("atomic: LibraryCache.swift does not write with .atomic")
    if LOGGING.search(code):
        problems.append("log: LibraryCache.swift logs; no path or title may be logged")
    return problems


class TheCacheHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(CACHE)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([CACHE], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_c1_save_then_load_round_trips_in_order(self):
        self.check("C1", "C1 same=true file=true")

    def test_c2_a_different_schema_loads_as_nil(self):
        self.check("C2", "C2 older=true newer=true current=true")

    def test_c3_a_different_key_loads_as_nil(self):
        self.check("C3", "C3 added=true removed=true")

    def test_c4_truncated_garbage_or_missing_loads_as_nil(self):
        self.check("C4", "C4 truncated=true garbage=true missing=true")

    def test_c5_the_key_is_sorted_and_versioned(self):
        self.check("C5", "C5 sorted=true prefix=true differs=true")

    def test_c6_a_schema_1_cache_is_rejected(self):
        self.check("C6", "C6 v1=true v1key=true schema=3")

    def test_c7_locked_and_unreadable_round_trip(self):
        self.check("C7", "C7 same=true locked=1,0,nil unreadable=nil,1,nil")

    def test_c8_a_schema_2_cache_is_rejected(self):
        self.check("C8", "C8 v2=true v2key=true")


class TheCacheFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], cache_problems(read(CACHE)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(CACHE.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class TheCacheGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(CACHE)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = cache_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_schema_constant_removed(self):
        self.assert_names(self.edited(self.text, "static let schema = 3", "static var schemaVersion = 3"), "schema")

    def test_mutation_schema_left_at_2(self):
        self.assert_names(self.edited(self.text, "static let schema = 3", "static let schema = 2"), "schema")

    def test_mutation_locked_dropped(self):
        self.assert_names(self.edited(self.text, "var locked: Bool?", "var lockedState: Int?"), "locked")

    def test_mutation_unreadable_dropped(self):
        self.assert_names(self.edited(self.text, "var unreadable: Bool?", "var unreadableState: Int?"), "unreadable")

    def test_mutation_file_renamed(self):
        self.assert_names(self.edited(self.text, '"library-cache.json"', '"cache.json"'), "file")

    def test_mutation_atomic_dropped(self):
        self.assert_names(self.edited(self.text, "options: .atomic", "options: .withoutOverwriting"), "atomic")

    def test_mutation_log_a_path(self):
        self.assert_names(self.text + "\nfunc probe(_ u: URL) { print(u.path) }\n", "log")


if __name__ == "__main__":
    unittest.main(verbosity=2)
