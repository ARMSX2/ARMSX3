#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PlayTime.swift, run alone on the Mac host, counts play time only between consecutive running observations of one
serial, persists it to playtime.json in Application Support and formats it for the library card.
It never runs the app or the emulator."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
PLAYTIME = IOS / "app" / "Library" / "PlayTime.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
DOCUMENTS = re.compile(r"documentDirectory|\bDocuments\b|session\.json")

DRIVER = r"""
import Foundation

let t0 = Date(timeIntervalSince1970: 1_000_000)
func at(_ s: Double) -> Date { t0.addingTimeInterval(s) }

var clock = PlayTimeClock()
var totals: [String: Double] = [:]
func feed(_ serial: String?, _ running: Bool, _ s: Double) {
    if let r = clock.observe(serial: serial, running: running, at: at(s)) { totals[r.serial, default: 0] += r.seconds }
}
func total(_ serial: String) -> String { String(Int(totals[serial] ?? 0)) }

var p1: [String] = []
feed("ABCD12345", true, 0); p1.append(total("ABCD12345"))
feed("ABCD12345", true, 5); p1.append(total("ABCD12345"))
feed("ABCD12345", false, 10); p1.append(total("ABCD12345"))
feed("ABCD12345", true, 12); p1.append(total("ABCD12345"))
feed("ABCD12345", true, 17); p1.append(total("ABCD12345"))
print("P1 " + p1.joined(separator: " "))

clock = PlayTimeClock(); totals = [:]
feed("ABCD12345", true, 0); feed("ABCD12345", true, 40)
print("P2 \(total("ABCD12345")) max=\(Int(PlayTimeClock.maxGap))")

clock = PlayTimeClock(); totals = [:]
feed("ABCD12345", true, 0); feed("WXYZ67890", true, 5)
let switched = "\(total("ABCD12345")) \(total("WXYZ67890"))"
feed("WXYZ67890", true, 10)
let after = total("WXYZ67890")
feed(nil, true, 15); feed(nil, true, 20)
feed("WXYZ67890", true, 25); feed("WXYZ67890", true, 22)
print("P3 switch=\(switched) then=\(after) titles=\(totals.count) back=\(total("WXYZ67890"))")

let dir = URL(fileURLWithPath: @@TMP@@)
let file = dir.appendingPathComponent("playtime.json")
MainActor.assumeIsolated {
    let store = PlayTime(directory: dir)
    store.observe(serial: "ABCD12345", running: true, at: at(0))
    store.observe(serial: "ABCD12345", running: true, at: at(5))
    store.observe(serial: "ABCD12345", running: false, at: at(6))
    let first = Int(store.seconds(for: "ABCD12345"))
    let fresh = Int(PlayTime(directory: dir).seconds(for: "ABCD12345"))
    var shape = "bad"
    if let data = try? Data(contentsOf: file),
       let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
       let titles = object["titles"] as? [String: Any],
       let title = titles["ABCD12345"] as? [String: Any],
       let last = title["lastPlayed"] as? String {
        let iso = ISO8601DateFormatter().date(from: last) != nil
        shape = "version=\(object["version"] as? Int ?? -1) seconds=\(title["seconds"] as? Int ?? -1) iso=\(iso ? 1 : 0)"
    }
    try? Data("{not json".utf8).write(to: file)
    let corrupt = PlayTime(directory: dir)
    let empty = Int(corrupt.seconds(for: "ABCD12345"))
    corrupt.observe(serial: "WXYZ67890", running: true, at: at(100))
    corrupt.observe(serial: "WXYZ67890", running: true, at: at(105))
    corrupt.observe(serial: "WXYZ67890", running: false, at: at(110))
    let replaced = Int(PlayTime(directory: dir).seconds(for: "WXYZ67890"))
    print("P4 saved=\(first) fresh=\(fresh) \(shape) corrupt=\(empty) replaced=\(replaced)")
    let f = { (s: Double) in PlayTime.format(s) ?? "nil" }
    print("P5 \(f(59))|\(f(60))|\(f(3725))")
}
"""

EXPECTED = {
    "P1": "P1 0 5 5 5 10",
    "P2": "P2 15 max=15",
    "P3": "P3 switch=0 0 then=5 titles=1 back=5",
    "P4": "P4 saved=5 fresh=5 version=1 seconds=5 iso=1 corrupt=0 replaced=5",
    "P5": "P5 nil|Played 1 min|Played 1 h 2 min",
}


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def playtime_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: PlayTime.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: PlayTime.swift logs; a serial is never logged")
    if DOCUMENTS.search(code):
        problems.append("docs: PlayTime.swift names Documents or session.json; play time stays in Application Support")
    return problems


class ThePlayTimeClockCounts(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(PLAYTIME)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([PLAYTIME], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key):
        self.assertEqual(EXPECTED[key], self.lines.get(key), key)

    def test_p1_paused_time_is_excluded(self):
        self.check("P1")

    def test_p2_a_long_gap_is_capped(self):
        self.check("P2")

    def test_p3_another_serial_nil_or_a_backward_clock_adds_nothing(self):
        self.check("P3")

    def test_p4_it_persists_and_a_corrupt_file_reads_empty(self):
        self.check("P4")

    def test_p5_format(self):
        self.check("P5")


class ThePlayTimeFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], playtime_problems(read(PLAYTIME)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(PLAYTIME.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class ThePlayTimeGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(PLAYTIME)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = playtime_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_a_serial(self):
        self.assert_names(self.text + "\nfunc probe(_ s: String) { print(s) }\n", "log")

    def test_mutation_write_to_documents(self):
        self.assert_names(self.edited(self.text, ".applicationSupportDirectory", ".documentDirectory"), "docs")


if __name__ == "__main__":
    unittest.main(verbosity=2)
