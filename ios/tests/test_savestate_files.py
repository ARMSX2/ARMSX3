#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""SaveStateFiles.swift, run on the Mac host over built folders, maps UI slots to file slots, lists a title's slots and
PS-menu states without the app's own history, decodes AX3T thumbnails, and reads a state's format epoch only from a
record that vouches for its file. It says nothing about what the core writes on a phone."""

import json
import os
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
FILES = IOS / "app" / "Saves" / "SaveStateFiles.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
# The record is trusted only with three fields, the size and the modification second matching.
VOUCH_CHECK = re.compile(r"fields\.count\s*==\s*3\s*,\s*let\s+value\s*=\s*fields\[0\]\s*,\s*fields\[1\]\s*==\s*size\s*,"
                         r"\s*fields\[2\]\s*==\s*wholeSeconds\(modified\)")

BASE_TIME = 1700000000
# The moment this build first launched, for the E cases.
SINCE = BASE_TIME + 1000
# When the newest slot-3 file in folder W was written, before `since`.
W_TIME = BASE_TIME + 7

# Written at run time into a temporary directory, never committed. @@TMP@@ becomes that directory.
DRIVER = r"""
import Foundation

let root = URL(fileURLWithPath: @@TMP@@, isDirectory: true)
func show(_ value: Int?) -> String { value.map(String.init) ?? "nil" }

let ui = [0, -2, 1, 10].map { show(SaveStateFiles.fileSlot(forUISlot: $0)) }.joined(separator: ",")
let uiBad = [-1, 11, 99].map { show(SaveStateFiles.fileSlot(forUISlot: $0)) }.joined(separator: ",")
let file = [11, 10, 0, 9].map { show(SaveStateFiles.uiSlot(forFileSlot: $0)) }.joined(separator: ",")
let fileBad = [-1, 12, 99].map { show(SaveStateFiles.uiSlot(forFileSlot: $0)) }.joined(separator: ",")
print("C1 ui=\(ui) uibad=\(uiBad) file=\(file) filebad=\(fileBad)")

let t = root.appendingPathComponent("T", isDirectory: true)
let listing = SaveStateFiles.scan(directory: t, appHistory: SaveStateFiles.appHistory(in: t.appendingPathComponent("armsx3_slots")))
let slots = listing.slots.keys.sorted()
func entryLine(_ n: Int) -> String {
    guard let e = listing.slots[n] else { return "\(n):none" }
    return "\(n):\(e.url.lastPathComponent):\(e.size):\(Int(e.modified.timeIntervalSince1970)):\(e.fileSlot.map(String.init) ?? "nil"):\(e.thumbnail?.lastPathComponent ?? "nil")"
}
print("C2 slots=\(slots.map(String.init).joined(separator: ",")) \(slots.map(entryLine).joined(separator: " ")) psmenu=\(listing.psMenu.map { $0.url.lastPathComponent + ":" + ($0.fileSlot.map(String.init) ?? "nil") }.joined(separator: ","))")

let u = SaveStateFiles.scan(directory: root.appendingPathComponent("U", isDirectory: true), appHistory: [])
print("C3 slots=\(u.slots.keys.sorted().map(String.init).joined(separator: ",")) s3=\(u.slots[3]?.url.lastPathComponent ?? "nil") s4=\(u.slots[4]?.url.lastPathComponent ?? "nil")")

let h = root.appendingPathComponent("H/armsx3_slots", isDirectory: true)
try! SaveStateFiles.recordAppHistory(name: "A_1.SAVESTAT.zst", in: h)
try! SaveStateFiles.recordAppHistory(name: "B_2.SAVESTAT", in: h)
let before = try! Data(contentsOf: h.appendingPathComponent("app-history.json"))
try! SaveStateFiles.recordAppHistory(name: "A_1.SAVESTAT.zst", in: h)
try! SaveStateFiles.recordAppHistory(name: "", in: h)
let after = try! Data(contentsOf: h.appendingPathComponent("app-history.json"))
let names = SaveStateFiles.appHistory(in: h).sorted().joined(separator: ",")
let left = (try! FileManager.default.contentsOfDirectory(atPath: h.path)).sorted().joined(separator: ",")
print("C4 names=\(names) same=\(before == after ? 1 : 0) files=\(left)")

func thumb(_ magic: String, _ w: UInt32, _ h: UInt32, body: Int) -> Data {
    var d = Data(magic.utf8)
    var lw = w.littleEndian, lh = h.littleEndian
    d.append(Data(bytes: &lw, count: 4))
    d.append(Data(bytes: &lh, count: 4))
    d.append(Data(repeating: 7, count: body))
    return d
}
func decoded(_ d: Data) -> String {
    guard let r = SaveStateFiles.decodeThumbnail(d) else { return "nil" }
    return "\(r.width)x\(r.height):\(r.rgba.count)"
}
print("C5 good=\(decoded(thumb("AX3T", 2, 3, body: 24))) max=\(decoded(thumb("AX3T", 320, 1, body: 1280))) magic=\(decoded(thumb("AX3X", 2, 3, body: 24))) short=\(decoded(thumb("AX3T", 2, 3, body: 23))) long=\(decoded(thumb("AX3T", 2, 3, body: 25))) edge=\(decoded(thumb("AX3T", 321, 1, body: 1284))) zero=\(decoded(thumb("AX3T", 0, 3, body: 0))) tiny=\(decoded(Data("AX3T".utf8)))")

let missing = SaveStateFiles.scan(directory: root.appendingPathComponent("nowhere", isDirectory: true), appHistory: [])
print("C6 slots=\(missing.slots.count) psmenu=\(missing.psMenu.count)")

// The format epoch.
let e = root.appendingPathComponent("E/armsx3_slots", isDirectory: true)
let since = Date(timeIntervalSince1970: @@SINCE@@)
func epochOf(_ name: String) -> Int { SaveStateFiles.epoch(ofStateAt: e.appendingPathComponent(name), since: since) }
print("E1 recorded=\(epochOf("slot3.SAVESTAT.gz"))")
print("E2 old=\(epochOf("slot4.SAVESTAT.gz"))")
print("E3 new=\(epochOf("slot5.SAVESTAT.gz"))")
print("E4 garbage=\(epochOf("slot6.SAVESTAT.gz"))")
// The record is written for the newest file and vouches for no other.
let w = root.appendingPathComponent("W/armsx3_slots", isDirectory: true)
let noFile = (try? SaveStateFiles.recordEpoch(fileSlot: 4, in: w)) == nil ? "threw" : "wrote"
try! SaveStateFiles.recordEpoch(fileSlot: 3, in: w)
let wrote = (try? String(contentsOf: w.appendingPathComponent("slot3.epoch"), encoding: .utf8)) ?? "nil"
let wroteFiles = (try! FileManager.default.contentsOfDirectory(atPath: w.path)).filter { !$0.contains("SAVESTAT") }.sorted()
func wEpoch(_ name: String) -> Int { SaveStateFiles.epoch(ofStateAt: w.appendingPathComponent(name), since: since) }
print("E5 wrote=\(wroteFiles == ["slot3.epoch"] ? wrote : "files:" + wroteFiles.joined(separator: ",")) newest=\(wEpoch("slot3.SAVESTAT.gz")) older=\(wEpoch("slot3.SAVESTAT")) missing=\(noFile)")
print("E6 othersize=\(epochOf("slot7.SAVESTAT.gz"))")
print("E7 bare=\(epochOf("slot8.SAVESTAT.gz"))")
print("E8 othertime=\(epochOf("slot9.SAVESTAT.gz"))")
"""


def write(path, size, mtime):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes([0x5A]) * size)
    os.utime(str(path), (mtime, mtime))


def build_fixtures(tmp):
    """Synthetic folders: T for C2, U for C3. Every name and byte here is made up."""
    t = Path(tmp) / "T"
    s = t / "armsx3_slots"
    write(s / "slot11.SAVESTAT.zst", 5, BASE_TIME + 11)
    write(s / "slot0.SAVESTAT", 7, BASE_TIME)
    write(s / "slot0.thumb", 12 + 2 * 2 * 4, BASE_TIME)
    write(s / "slot10.SAVESTAT.gz", 9, BASE_TIME + 10)
    write(s / ".partial-slot3", 3, BASE_TIME + 3)
    write(s / "notes.txt", 2, BASE_TIME)
    write(t / "T_1.SAVESTAT.zst", 4, BASE_TIME + 1)
    write(t / "T_2.SAVESTAT.zst", 4, BASE_TIME + 2)
    write(t / "T_x.SAVESTAT", 4, BASE_TIME + 4)
    write(t / "Q_3.SAVESTAT", 4, BASE_TIME + 5)
    (s / "app-history.json").write_text(json.dumps(["T_1.SAVESTAT.zst"]), encoding="utf-8")
    u = Path(tmp) / "U" / "armsx3_slots"
    write(u / "slot3.SAVESTAT", 6, BASE_TIME + 100)
    write(u / "slot3.SAVESTAT.zst", 6, BASE_TIME + 200)
    write(u / "slot4.SAVESTAT", 6, BASE_TIME + 300)
    write(u / "slot4.SAVESTAT.gz", 6, BASE_TIME + 200)
    # The E cases: slots 3 to 6 by record and date, and slots 7 to 9 old files under records that vouch for another file.
    e = Path(tmp) / "E" / "armsx3_slots"
    write(e / "slot3.SAVESTAT.gz", 6, BASE_TIME)
    (e / "slot3.epoch").write_text("2 6 {0}".format(BASE_TIME), encoding="utf-8")
    write(e / "slot7.SAVESTAT.gz", 6, BASE_TIME)
    (e / "slot7.epoch").write_text("2 9 {0}".format(BASE_TIME), encoding="utf-8")
    write(e / "slot8.SAVESTAT.gz", 6, BASE_TIME)
    (e / "slot8.epoch").write_text("2", encoding="utf-8")
    write(e / "slot9.SAVESTAT.gz", 6, BASE_TIME)
    (e / "slot9.epoch").write_text("2 6 {0}".format(BASE_TIME + 500), encoding="utf-8")
    w = Path(tmp) / "W" / "armsx3_slots"
    write(w / "slot3.SAVESTAT", 4, BASE_TIME + 1)
    write(w / "slot3.SAVESTAT.gz", 6, W_TIME)
    write(e / "slot4.SAVESTAT.gz", 6, BASE_TIME)
    write(e / "slot5.SAVESTAT.gz", 6, SINCE)
    write(e / "slot6.SAVESTAT.gz", 6, SINCE + 50)
    (e / "slot6.epoch").write_text("zz", encoding="utf-8")


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def files_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: SaveStateFiles.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: SaveStateFiles.swift logs; no path or serial may be logged")
    if ".atomic" not in code:
        problems.append("atomic: SaveStateFiles.swift does not write with .atomic")
    if '"armsx3_slots"' not in code or '"app-history.json"' not in code:
        problems.append("file: SaveStateFiles.swift does not name armsx3_slots and app-history.json")
    if ("static let formatEpoch = 2" not in code or '"armsx3.savestate.epoch2Since"' not in code
            or '.epoch"' not in code or "atomically: true" not in code):
        problems.append("epoch: SaveStateFiles.swift does not declare formatEpoch = 2 and the epoch2Since key, "
                        "or does not write the .epoch record atomically")
    if (not VOUCH_CHECK.search(code)
            or "\\(formatEpoch) \\(state.size) \\(wholeSeconds(state.modified))" not in code):
        problems.append("vouch: SaveStateFiles.swift trusts an epoch record without matching its size and modification "
                        "second to the file, or does not write them")
    return problems


class TheFilesAreRead(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(FILES)
        with tempfile.TemporaryDirectory() as tmp:
            build_fixtures(tmp)
            out = compile_and_run([FILES], DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@SINCE@@", str(SINCE)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_c1_ui_and_file_slots_map_both_ways(self):
        self.check("C1", "C1 ui=11,10,0,9 uibad=nil,nil,nil file=0,-2,1,10 filebad=nil,nil,nil")

    def test_c2_slots_and_the_ps_menu_states(self):
        self.check("C2", "C2 slots=0,10,11"
                         " 0:slot0.SAVESTAT:7:{0}:0:slot0.thumb"
                         " 10:slot10.SAVESTAT.gz:9:{1}:10:nil"
                         " 11:slot11.SAVESTAT.zst:5:{2}:11:nil"
                         " psmenu=T_2.SAVESTAT.zst:nil".format(BASE_TIME, BASE_TIME + 10, BASE_TIME + 11))

    def test_c3_two_spellings_resolve_to_the_newer(self):
        self.check("C3", "C3 slots=3,4 s3=slot3.SAVESTAT.zst s4=slot4.SAVESTAT")

    def test_c4_history_appends_once_and_atomically(self):
        self.check("C4", "C4 names=A_1.SAVESTAT.zst,B_2.SAVESTAT same=1 files=app-history.json")

    def test_c5_thumbnails_decode_only_when_well_formed(self):
        self.check("C5", "C5 good=2x3:24 max=320x1:1280 magic=nil short=nil long=nil edge=nil zero=nil tiny=nil")

    def test_c6_a_missing_folder_is_empty(self):
        self.check("C6", "C6 slots=0 psmenu=0")

    def test_e1_a_record_wins_over_the_date(self):
        self.check("E1", "E1 recorded=2")

    def test_e2_no_record_before_since_is_old(self):
        self.check("E2", "E2 old=1")

    def test_e3_no_record_at_since_is_new(self):
        self.check("E3", "E3 new=2")

    def test_e4_an_unreadable_record_falls_back_to_the_date(self):
        self.check("E4", "E4 garbage=2")

    def test_e5_the_record_is_written_whole_for_the_newest_file(self):
        self.check("E5", "E5 wrote=2 6 {0} newest=2 older=1 missing=threw".format(W_TIME))

    def test_e6_a_record_for_another_size_is_ignored(self):
        self.check("E6", "E6 othersize=1")

    def test_e7_a_bare_record_is_ignored(self):
        self.check("E7", "E7 bare=1")

    def test_e8_a_record_for_another_second_is_ignored(self):
        self.check("E8", "E8 othertime=1")


class TheFilesSourceIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], files_problems(read(FILES)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(FILES.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class TheFilesGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(FILES)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = files_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_a_path(self):
        self.assert_names(self.text + "\nfunc probe(_ u: URL) { print(u.path) }\n", "log")

    def test_mutation_atomic_dropped(self):
        self.assert_names(self.edited(self.text, "options: .atomic", "options: .withoutOverwriting"), "atomic")

    def test_mutation_file_renamed(self):
        self.assert_names(self.edited(self.text, '"app-history.json"', '"history.json"'), "file")

    def test_mutation_epoch_not_bumped(self):
        self.assert_names(self.edited(self.text, "static let formatEpoch = 2", "static let formatEpoch = 1"), "epoch")

    def test_mutation_vouch_record_trusted_whatever_its_size(self):
        self.assert_names(self.edited(self.text, "fields[1] == size", "fields[1] != nil"), "vouch")


if __name__ == "__main__":
    unittest.main(verbosity=2)
