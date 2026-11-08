#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""TrophyReader.swift, run on the Mac host over built TROPCONF.SFM and TROPUSR.DAT files, reads a title's trophies with
exact unlock times, refuses damaged files and writes nothing; static checks keep secret trophies hidden in the view.
It says nothing about a real console set or a device."""

import calendar
import hashlib
import json
import re
import struct
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
READER = APP / "Online" / "TrophyReader.swift"
VIEW = APP / "Online" / "TrophiesView.swift"
LIBRARY = APP / "Library" / "GameListView.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
WRITES = re.compile(r"\bwrite\s*\(|FileHandle\s*\(\s*forWriting|\bcreateFile\b|\bremoveItem\b|\bmoveItem\b")

SET = "NPWR99999_00"
MAGIC = 0x818F54AD
RTC_EPOCH_US = 62135596800 * 1000000


def tick(year, month, day, hour=0, minute=0, second=0):
    return (calendar.timegm((year, month, day, hour, minute, second)) * 1000000) + RTC_EPOCH_US


T0 = tick(2026, 10, 2, 14, 30, 0)
T2 = tick(2009, 2, 5, 9, 0, 0)


def tropconf(xml_tail=None):
    body = xml_tail if xml_tail is not None else (
        '<trophy id="000" hidden="no" ttype="P" pid="-1"><name>Platinum Prize</name><detail>All of them</detail></trophy>\n'
        '<trophy id="001" hidden="yes" ttype="G" pid="000"><name>Secret Gold</name><detail>A secret</detail></trophy>\n'
        '<trophy id="002" hidden="no" ttype="B" pid="000"><name>Bronze &amp; Proud</name><detail>First step</detail></trophy>\n'
        '<trophy id="003" hidden="no" ttype="B" pid="000"><name>Second Step</name><detail>Another</detail></trophy>\n'
        '</trophyconf>\n')
    return ('<!--Sce-Np-Trophy-Signature: synthetic-->\n'
            '<trophyconf version="1.0" platform="ps3">\n'
            '<npcommid>' + SET + '</npcommid>\n'
            '<title-name>Synthetic Trophies</title-name>\n'
            '<title-detail>Synthetic detail</title-detail>\n' + body).encode("utf-8")


def tropusr(states, entry6_size=0x60, entry4_size=0x50):
    """states: [(trophy id, state, tick)]. Table 4 grades follow the TROPCONF fixture."""
    grades = [(0, 1), (1, 2), (2, 4), (3, 4)]
    body4 = b"".join(
        (struct.pack(">IIII", 4, entry4_size, i, 0) + struct.pack(">III", tid, grade, 0xFFFFFFFF)).ljust(16 + entry4_size, b"\0")
        for i, (tid, grade) in enumerate(grades))
    body6 = b"".join(
        (struct.pack(">IIII", 6, entry6_size, i, 0) + struct.pack(">IIIIQQ", tid, state, 0, 0, at, at)).ljust(16 + entry6_size, b"\0")
        for i, (tid, state, at) in enumerate(states))
    off4 = 0x30 + 2 * 32
    off6 = off4 + len(body4)
    header = struct.pack(">IIII", MAGIC, 0, 2, 0) + b"\0" * 32
    tables = (struct.pack(">IIIIQQ", 4, entry4_size, 1, len(grades), off4, 0)
              + struct.pack(">IIIIQQ", 6, entry6_size, 1, len(states), off6, 0))
    return header + tables + body4 + body6


STATES = [(0, 1, T0), (1, 0, 0), (2, 1, T2), (3, 0, 0)]


def write_fixtures(tmp):
    tmp = Path(tmp)

    def set_dir(name, conf, usr):
        folder = tmp / "sets" / name
        folder.mkdir(parents=True)
        if conf is not None:
            (folder / "TROPCONF.SFM").write_bytes(conf)
        if usr is not None:
            (folder / "TROPUSR.DAT").write_bytes(usr)
        return folder

    good = tropusr(STATES)
    user_set = tmp / "user" / "trophy" / SET
    user_set.mkdir(parents=True)
    (user_set / "TROPCONF.SFM").write_bytes(tropconf())
    (user_set / "TROPUSR.DAT").write_bytes(good)
    (user_set / "ICON0.PNG").write_bytes(b"\x89PNG synthetic")
    (user_set / "TROP000.PNG").write_bytes(b"\x89PNG synthetic")

    set_dir("stride", tropconf(), tropusr(STATES, entry6_size=0x70))
    set_dir("badmagic", tropconf(), b"\x00\x00\x00\x00" + good[4:])
    past = bytearray(good)
    struct.pack_into(">Q", past, 0x30 + 32 + 16, len(good) + 1000)
    set_dir("offset", tropconf(), bytes(past))
    set_dir("truncated", tropconf(), good[:-10])
    set_dir("short", tropconf(), tropusr(STATES, entry6_size=0x10))
    set_dir("xml", tropconf('<trophy id="0"><name>Broken</trophy>\n'), good)
    set_dir("nousr", tropconf(), None)

    title = tmp / "title"
    (title / "TROPDIR" / SET).mkdir(parents=True)
    (title / "TROPDIR" / "NPWR88888_00").mkdir()
    (title / "TROPDIR" / "README").write_bytes(b"not a set")
    (tmp / "bare").mkdir()


def snapshot(tmp):
    tmp = Path(tmp)
    return sorted((str(p.relative_to(tmp)), hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else "dir")
                  for p in tmp.rglob("*") if p.name not in ("main.swift", "driver"))


DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let iso = ISO8601DateFormatter()
func dir(_ path: String) -> URL { tmp.appendingPathComponent(path, isDirectory: true) }
func letter(_ grade: TrophyGrade?) -> String {
    let letters: [TrophyGrade: String] = [.platinum: "P", .gold: "G", .silver: "S", .bronze: "B"]
    return grade.flatMap { letters[$0] } ?? "-"
}
func when(_ date: Date?) -> String { date.map { iso.string(from: $0) } ?? "nil" }
func counts(_ set: TrophySet?) -> String {
    guard let set else { return "nil" }
    let t = set.trophies
    return "total=\(t.count) unlocked=\(t.filter(\.unlocked).count) dated=\(t.filter { $0.unlockedAt != nil }.count) hidden=\(t.filter { $0.isSecret && !$0.unlocked }.count)"
}

let good = TrophyReader.read(setDir: dir("user/trophy/@@SET@@"))!
print("C1 id=\(good.id) title=\(good.title) detail=\(good.detail) \(counts(good)) grades=\(good.trophies.map { letter($0.grade) }.joined(separator: ",")) names=\(good.trophies.map(\.name).joined(separator: "|")) icons=\(good.iconURL != nil),\(good.trophies.map { $0.iconURL != nil ? "1" : "0" }.joined())")
print("C2 id=\(good.trophies[0].id) at=\(when(good.trophies[0].unlockedAt))")
print("C3 id=\(good.trophies[2].id) at=\(when(good.trophies[2].unlockedAt))")
let epoch: UInt64 = 62_135_596_800 * 1_000_000
print("C4 zero=\(when(TrophyReader.unlockDate(tick: 0))) pre1980=\(when(TrophyReader.unlockDate(tick: epoch + 315_532_799 * 1_000_000))) post2100=\(when(TrophyReader.unlockDate(tick: epoch + 4_102_444_801 * 1_000_000))) in1980=\(when(TrophyReader.unlockDate(tick: epoch + 315_532_801 * 1_000_000)))")
let stride = TrophyReader.read(setDir: dir("sets/stride"))
print("C5 \(counts(stride)) at=\(when(stride?.trophies[0].unlockedAt)),\(when(stride?.trophies[2].unlockedAt))")
print("C6 magic=\(TrophyReader.read(setDir: dir("sets/badmagic")) == nil) offset=\(TrophyReader.read(setDir: dir("sets/offset")) == nil) truncated=\(TrophyReader.read(setDir: dir("sets/truncated")) == nil) short=\(TrophyReader.read(setDir: dir("sets/short")) == nil) xml=\(TrophyReader.read(setDir: dir("sets/xml")) == nil)")
print("C7 \(counts(TrophyReader.read(setDir: dir("sets/nousr"))))")
print("C8 sets=\(TrophyReader.sets(titleDir: dir("title"), userDir: dir("user")).joined(separator: ",")) none=\(TrophyReader.sets(titleDir: dir("bare"), userDir: dir("user")).count)")
let secret = good.trophies[1]
let earned = Trophy(id: 9, name: "n", detail: "d", grade: .gold, isSecret: true, unlocked: true, unlockedAt: nil, iconURL: nil)
print("C9 secret-locked=\(secret.isRevealed(showHidden: false)) secret-locked-shown=\(secret.isRevealed(showHidden: true)) secret-earned=\(earned.isRevealed(showHidden: false)) plain=\(good.trophies[3].isRevealed(showHidden: false))")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def header_problems(name, raw):
    problems = []
    if "SPDX-License-Identifier: GPL-2.0-only" not in "\n".join(raw.split("\n")[:2]):
        problems.append("header: {0} has no GPL-2.0-only SPDX line in its first two lines".format(name))
    if "Adapted from ARMSX2" in raw:
        problems.append("header: {0} carries the lifted-file header".format(name))
    return problems


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def view_problems(raw):
    """The view checks over TrophiesView.swift and GameListView.swift."""
    problems = header_problems("TrophiesView.swift", raw["TrophiesView.swift"])
    view = without_comments(raw["TrophiesView.swift"])
    if (not any(a in view for a in ('revealed ? trophy.name : "Hidden trophy"',
                                    'revealed ? trophy.name : String(localized: "Hidden trophy")'))
            or not any(a in view for a in ('revealed ? trophy.detail : "Keep playing to reveal it"',
                                           'revealed ? trophy.detail : String(localized: "Keep playing to reveal it")'))
            or "isRevealed(showHidden: showHidden)" not in view):
        problems.append("hidden: a secret trophy's name and description are not gated on isRevealed(showHidden: showHidden)")
    if view.count("trophy.name") != 1 or view.count("trophy.detail") != 1:
        problems.append("leak: trophy.name or trophy.detail is used outside the revealed ternary")
    if "@State private var showHidden = false" not in view or 'Toggle("Show hidden", isOn: $showHidden)' not in view:
        problems.append("switch: Show hidden does not start off, or is not a Toggle bound to showHidden")
    earned = re.search(r'"Earned \\\(\w+\.formatted\(\.dateTime[^"]*"', view)
    if ("unlockedAt" not in view or earned is None
            or ".hour()" not in earned.group(0) or ".minute()" not in earned.group(0)):
        problems.append("date: an earned trophy is not shown with its date and time (.dateTime with .hour() and .minute())")
    for needle in ("TrophyReader.sets(titleDir:", "TrophyReader.read(setDir:", "Rpcs3Bridge.userDirectory",
                   '"This game has no trophies here yet"'):
        if needle not in view:
            problems.append("view: TrophiesView.swift lacks {0}".format(needle))

    library = without_comments(raw["GameListView.swift"])
    items = body_of(library, r"func\s+contextItems\s*\(") or ""
    row = items.find('action: .trophies, title: "Trophies"')
    guarded = False
    for match in re.finditer(r"\bif\s+!entry\.isISO\s*\{", items):
        start, end = brace_span(items, match.end() - 1)
        guarded = guarded or start <= row < end
    if row == -1 or not guarded:
        problems.append('iso: contextItems does not offer the Trophies row only inside `if !entry.isISO`')
    perform = body_of(library, r"func\s+performContext\s*\(") or ""
    arm = re.search(r"case\s+\.trophies\s*:(.*?)(?=\bcase\b|\bdefault\b)", perform, re.S)
    presents = re.search(r"case\s+\.trophies\s*\((.*?)(?=\bcase\b|\Z)", library, re.S)
    if arm is None or ".trophies(" not in arm.group(1) or presents is None or "TrophiesView(" not in presents.group(1):
        problems.append("sheet: .trophies is not mapped to a LibrarySheet arm that presents TrophiesView(")
    return problems


def trophy_problems(raw):
    """raw: {file name: raw text} for TrophyReader.swift, TrophiesView.swift and GameListView.swift."""
    problems = []
    code = without_comments(raw["TrophyReader.swift"])
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: TrophyReader.swift imports {0}; it must import Foundation only".format(imports))
    if WRITES.search(code):
        problems.append("write: TrophyReader.swift names a file-writing call; the reader must be read-only")
    if LOGGING.search(code):
        problems.append("log: TrophyReader.swift logs; no trophy name or path may be logged")
    if "0x818F54AD" not in code:
        problems.append("magic: TrophyReader.swift does not name the TROPUSR magic 0x818F54AD")
    problems += header_problems("TrophyReader.swift", raw["TrophyReader.swift"])
    problems += view_problems(raw)
    return sorted(problems)


def real_inputs():
    return {path.name: read(path) for path in (READER, VIEW, LIBRARY)}


class TheReaderHolds(unittest.TestCase):
    lines = None
    unchanged = None

    @classmethod
    def setUpClass(cls):
        read(READER)
        with tempfile.TemporaryDirectory() as tmp:
            write_fixtures(tmp)
            before = snapshot(tmp)
            out = compile_and_run([READER], DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@SET@@", SET), tmp)
            cls.unchanged = before == snapshot(tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_c1_the_set_reads_whole(self):
        self.assertEqual(
            "C1 id=NPWR99999_00 title=Synthetic Trophies detail=Synthetic detail total=4 unlocked=2 dated=2 hidden=1 "
            "grades=P,G,B,B names=Platinum Prize|Secret Gold|Bronze & Proud|Second Step icons=true,1000", self.line("C1"))

    def test_c2_a_2026_unlock_to_the_second(self):
        self.assertEqual("C2 id=0 at=2026-10-02T14:30:00Z", self.line("C2"))

    def test_c3_a_2009_unlock_to_the_second(self):
        self.assertEqual("C3 id=2 at=2009-02-05T09:00:00Z", self.line("C3"))

    def test_c4_implausible_ticks_give_no_date(self):
        self.assertEqual("C4 zero=nil pre1980=nil post2100=nil in1980=1980-01-01T00:00:01Z", self.line("C4"))

    def test_c5_the_stride_is_the_tables_own(self):
        self.assertEqual("C5 total=4 unlocked=2 dated=2 hidden=1 at=2026-10-02T14:30:00Z,2009-02-05T09:00:00Z", self.line("C5"))

    def test_c6_malformed_files_are_nil(self):
        self.assertEqual("C6 magic=true offset=true truncated=true short=true xml=true", self.line("C6"))

    def test_c7_no_tropusr_reads_all_locked(self):
        self.assertEqual("C7 total=4 unlocked=0 dated=0 hidden=1", self.line("C7"))

    def test_c8_sets_come_from_tropdir(self):
        self.assertEqual("C8 sets=NPWR99999_00 none=0", self.line("C8"))

    def test_c9_a_secret_stays_hidden_until_earned_or_shown(self):
        self.assertEqual("C9 secret-locked=false secret-locked-shown=true secret-earned=true plain=true", self.line("C9"))

    def test_reading_wrote_nothing(self):
        self.assertTrue(self.unchanged, "the fixture tree changed while the reader ran")


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], trophy_problems(real_inputs()))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.raw = real_inputs()

    def edited(self, name, old, new):
        text = self.raw[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        raw = dict(self.raw)
        raw[name] = mutated
        return raw

    def assertFlagged(self, raw, prefix):
        problems = trophy_problems(raw)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.assertFlagged(self.edited("TrophyReader.swift", "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_write_the_usr_file(self):
        self.assertFlagged(self.edited("TrophyReader.swift", "import Foundation",
                                       "import Foundation\nfunc touch(_ d: Data, _ u: URL) { try? d.write(to: u) }"), "write")

    def test_mutation_log_a_name(self):
        self.assertFlagged(self.edited("TrophyReader.swift", "import Foundation",
                                       "import Foundation\nfunc probe(_ s: String) { print(s) }"), "log")

    def test_mutation_magic_changed(self):
        self.assertFlagged(self.edited("TrophyReader.swift", "0x818F54AD", "0x818F54AE"), "magic")

    def test_mutation_header_not_newly_authored(self):
        self.assertFlagged(self.edited("TrophyReader.swift", "// SPDX-License-Identifier: GPL-2.0-only\n", "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n"), "header")

    def test_mutation_hidden_switch_ignored(self):
        self.assertFlagged(self.edited("TrophiesView.swift", "isRevealed(showHidden: showHidden)", "isRevealed(showHidden: true)"), "hidden")

    def test_mutation_leak_a_secret_name(self):
        self.assertFlagged(self.edited("TrophiesView.swift", 'revealed ? trophy.name : String(localized: "Hidden trophy"))',
                                       'revealed ? trophy.name : String(localized: "Hidden trophy")).accessibilityHint(trophy.name)'), "leak")

    def test_mutation_switch_starts_on(self):
        self.assertFlagged(self.edited("TrophiesView.swift", "@State private var showHidden = false",
                                       "@State private var showHidden = true"), "switch")

    def test_mutation_date_without_time(self):
        self.assertFlagged(self.edited("TrophiesView.swift", ".hour().minute()", ""), "date")

    def test_mutation_view_reads_nothing(self):
        self.assertFlagged(self.edited("TrophiesView.swift", "TrophyReader.read(setDir:", "TrophyReader.readSet(at:"), "view")

    def test_mutation_iso_gets_the_row(self):
        self.assertFlagged(self.edited("GameListView.swift", "if !entry.isISO {", "if true {"), "iso")

    def test_mutation_sheet_row_does_nothing(self):
        self.assertFlagged(self.edited("GameListView.swift", "case .trophies:", "case .trophies: break\n        case .rename:"), "sheet")

    def test_mutation_view_header_not_newly_authored(self):
        self.assertFlagged(self.edited("TrophiesView.swift", "// SPDX-License-Identifier: GPL-2.0-only\n", "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n"), "header")


if __name__ == "__main__":
    unittest.main(verbosity=2)
