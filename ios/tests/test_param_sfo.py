#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ParamSfo.swift and SaveDataStore.swift, run on the Mac host over built bytes, parse PARAM.SFO as the core does,
refuse damaged ones, and list one title's saves newest first; static checks cover the Save Data row, sheet and view.
It says nothing about a real console save or a game finding one."""

import json
import os
import re
import struct
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SFO = APP / "Library" / "ParamSfo.swift"
STORE = APP / "Saves" / "SaveDataStore.swift"
ZIP = APP / "Install" / "ZipArchive.swift"
VIEW = APP / "Saves" / "SaveDataView.swift"
LIBRARY = APP / "Library" / "GameListView.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
IF_BLOCK = re.compile(r"\bif\b[^{\n]*\{")

STRING, SPECIAL, INTEGER = 0x0204, 0x0004, 0x0404


def build_sfo(entries):
    """entries: [(key, fmt, value)] with value bytes for strings, int for u32. Returns SFO bytes."""
    keys, data, index = b"", b"", b""
    for key, fmt, value in entries:
        if fmt == INTEGER:
            raw, length, maximum = struct.pack("<I", value), 4, 4
        else:
            raw = value
            length = len(raw)
            maximum = (length + 3) & ~3
        index += struct.pack("<HHIII", len(keys), fmt, length, maximum, len(data))
        keys += key.encode("ascii") + b"\0"
        data += raw + b"\0" * (maximum - len(raw))
    keys += b"\0" * (-len(keys) % 4)
    key_table = 20 + len(index)
    header = struct.pack("<4sIIII", b"\0PSF", 0x101, key_table, key_table + len(keys), len(entries))
    return header + index + keys + data


def save_sfo(dir_name, title, subtitle, detail):
    return build_sfo([
        ("DETAIL", STRING, detail.encode() + b"\0"),
        ("SAVEDATA_DIRECTORY", STRING, dir_name.encode() + b"\0"),
        ("SUB_TITLE", STRING, subtitle.encode() + b"\0"),
        ("TITLE", STRING, title.encode() + b"\0"),
        ("ATTRIBUTE", INTEGER, 7),
    ])


def write_fixtures(tmp):
    """Every fixture, synthetic, into tmp. Returns {save dir name: total byte size}."""
    tmp = Path(tmp)
    good = save_sfo("ABCD12345-SAVE01", "Synthetic Title", "Chapter One", "A synthetic save")
    (tmp / "good.sfo").write_bytes(good)
    (tmp / "badmagic.sfo").write_bytes(b"\0PSX" + good[4:])
    # The first index entry's key_off, pointed past the key table.
    keypast = bytearray(good)
    struct.pack_into("<H", keypast, 20, 0x7FFF)
    (tmp / "keypast.sfo").write_bytes(bytes(keypast))
    # An entry count whose index runs through the key table and past the end.
    overrun = bytearray(good)
    struct.pack_into("<I", overrun, 16, 1000)
    (tmp / "overrun.sfo").write_bytes(bytes(overrun))
    # The last entry is the u32; cutting two bytes leaves it short.
    (tmp / "truncated.sfo").write_bytes(good[:-2])
    (tmp / "special.sfo").write_bytes(build_sfo([("PARAMS", SPECIAL, b"AB\0CD")]))

    savedata = tmp / "user" / "savedata"
    sizes = {}

    def save(name, sfo_bytes, files, mtime=None):
        folder = savedata / name
        folder.mkdir(parents=True)
        if sfo_bytes is not None:
            (folder / "PARAM.SFO").write_bytes(sfo_bytes)
        for file_name, size in files:
            (folder / file_name).write_bytes(bytes((i * 7) & 0xFF for i in range(size)))
        if mtime is not None:
            os.utime(folder / "PARAM.SFO", (mtime, mtime))
        sizes[name] = sum(p.stat().st_size for p in folder.rglob("*") if p.is_file())

    save("ABCD12345-SAVE01", save_sfo("ABCD12345-SAVE01", "Synthetic Title", "Chapter One", "First"),
         [("DATA.BIN", 1000), ("ICON0.PNG", 10)], mtime=1700000000)
    save("ABCD12345-SAVE02", save_sfo("ABCD12345-SAVE02", "Synthetic Title", "Chapter Two", "Second"),
         [("DATA.BIN", 3000)], mtime=1700100000)
    save("ABCD12345-NOSFO", None, [("DATA.BIN", 10)])
    save("ABCD12345-BADSFO", b"\0PSX" + good[4:], [("DATA.BIN", 10)])
    save(".ABCD12345-STAGE", save_sfo(".ABCD12345-STAGE", "Staged", "Staged", "Staged"), [])
    save("WXYZ99999-OTHER", save_sfo("WXYZ99999-OTHER", "Other", "Other", "Other"), [])
    (savedata / "ABCD12345-FILE").write_bytes(b"not a folder")
    (tmp / "nouser").mkdir()
    return sizes


DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> ParamSfo? { ParamSfo(data: try! Data(contentsOf: tmp.appendingPathComponent(name))) }

let good = load("good.sfo")!
print("S1 title=\(good.strings["TITLE"] ?? "-") sub=\(good.strings["SUB_TITLE"] ?? "-") dir=\(good.savedataDirectory ?? "-") u32=\(good.integers["ATTRIBUTE"].map { String($0) } ?? "-")")
print("S2 magic=\(load("badmagic.sfo") == nil) keypast=\(load("keypast.sfo") == nil) overrun=\(load("overrun.sfo") == nil) truncated=\(load("truncated.sfo") == nil) empty=\(ParamSfo(data: Data()) == nil)")
print("S3 special=\(load("special.sfo")?.strings["PARAMS"] ?? "-")")

let store = SaveDataStore(userDir: tmp.appendingPathComponent("user"))
let list = store.saves(forSerial: "ABCD12345")
print("L1 dirs=\(list.map(\.dirName).joined(separator: ","))")
for entry in list {
    print("L2 dir=\(entry.dirName) title=\(entry.title) sub=\(entry.subtitle) detail=\(entry.detail) size=\(entry.size) at=\(Int(entry.modified.timeIntervalSince1970)) icon=\(entry.iconURL != nil)")
}
let missing = SaveDataStore(userDir: tmp.appendingPathComponent("nouser")).saves(forSerial: "ABCD12345")
print("L3 other=\(store.saves(forSerial: "QQQQ00000").count) nouser=\(missing.count)")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def header_problems(name, raw):
    problems = []
    if "SPDX-License-Identifier: GPL-2.0-only" not in "\n".join(raw.split("\n")[:2]):
        problems.append("header: {0} has no GPL-2.0-only SPDX line in its first two lines".format(name))
    if "Adapted from ARMSX2" in raw:
        problems.append("header: {0} carries the lifted-file header".format(name))
    return problems


def sfo_problems(raw):
    """raw: {file name: raw text} for ParamSfo, SaveDataStore, SaveDataView and GameListView."""
    problems = []
    for name in ("ParamSfo.swift", "SaveDataStore.swift"):
        code = without_comments(raw[name])
        imports = IMPORT.findall(code)
        if imports != ["Foundation"]:
            problems.append("host: {0} imports {1}; it must import Foundation only".format(name, imports))
        if LOGGING.search(code):
            problems.append("log: {0} logs; no save name or path may be logged".format(name))
    if '"SAVEDATA_DIRECTORY"' not in without_comments(raw["ParamSfo.swift"]):
        problems.append("key: ParamSfo.swift does not name SAVEDATA_DIRECTORY")
    for name in ("ParamSfo.swift", "SaveDataStore.swift", "SaveDataView.swift"):
        problems += header_problems(name, raw[name])

    library = without_comments(raw["GameListView.swift"])
    items = body_of(library, r"func\s+contextItems\s*\(") or ""
    row = items.find('action: .gameData, title: "Save Data"')
    if row == -1:
        problems.append('menu: contextItems does not offer `action: .gameData, title: "Save Data"`')
    else:
        for match in IF_BLOCK.finditer(items):
            start, end = brace_span(items, match.end() - 1)
            if start <= row < end:
                problems.append("menu: the Save Data row is offered only inside an `if` block")
    perform = body_of(library, r"func\s+performContext\s*\(") or ""
    arm = re.search(r"case\s+\.gameData\s*:(.*?)(?=\bcase\b|\bdefault\b)", perform, re.S)
    if arm is None or ".saveData(" not in arm.group(1):
        problems.append("sheet: performContext does not map .gameData to .saveData(")
    presents = re.search(r"case\s+\.saveData\s*\((.*?)(?=\bcase\b|\Z)", library, re.S)
    if presents is None or "SaveDataView(" not in presents.group(1):
        problems.append("sheet: the sheet switch does not present SaveDataView( for .saveData(")

    view = without_comments(raw["SaveDataView.swift"])
    if "Rpcs3Bridge.userDirectory" not in view:
        problems.append("view: SaveDataView.swift does not ask the façade for the user folder")
    if "saves(forSerial:" not in view:
        problems.append("view: SaveDataView.swift does not list through saves(forSerial:")
    if '"No saves yet for this game"' not in view:
        problems.append("view: SaveDataView.swift has no empty state")
    return sorted(problems)


def real_inputs():
    return {path.name: read(path) for path in (SFO, STORE, VIEW, LIBRARY)}


class TheReaderAndTheListHold(unittest.TestCase):
    lines = None
    sizes = None

    @classmethod
    def setUpClass(cls):
        read(SFO)
        read(STORE)
        sources = [SFO, STORE] + ([ZIP] if ZIP.is_file() else [])
        with tempfile.TemporaryDirectory() as tmp:
            cls.sizes = write_fixtures(tmp)
            out = compile_and_run(sources, DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_s1_strings_and_an_integer_parse(self):
        self.assertEqual("S1 title=Synthetic Title sub=Chapter One dir=ABCD12345-SAVE01 u32=7", self.line("S1"))

    def test_s2_malformed_sfos_are_nil(self):
        self.assertEqual("S2 magic=true keypast=true overrun=true truncated=true empty=true", self.line("S2"))

    def test_s3_a_special_string_stops_at_its_nul(self):
        self.assertEqual("S3 special=AB", self.line("S3"))

    def test_l1_only_the_titles_readable_saves_newest_first(self):
        self.assertEqual("L1 dirs=ABCD12345-SAVE02,ABCD12345-SAVE01", self.line("L1"))

    def test_l2_each_save_reads_its_own_sfo(self):
        found = [line for line in self.lines if line.startswith("L2 ")]
        self.assertEqual([
            "L2 dir=ABCD12345-SAVE02 title=Synthetic Title sub=Chapter Two detail=Second size={0} at=1700100000 icon=false".format(
                self.sizes["ABCD12345-SAVE02"]),
            "L2 dir=ABCD12345-SAVE01 title=Synthetic Title sub=Chapter One detail=First size={0} at=1700000000 icon=true".format(
                self.sizes["ABCD12345-SAVE01"]),
        ], found)

    def test_l3_nothing_for_another_serial_or_no_savedata(self):
        self.assertEqual("L3 other=0 nouser=0", self.line("L3"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], sfo_problems(real_inputs()))

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
        problems = sfo_problems(raw)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_store_imports_uikit(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_a_save_name(self):
        self.assertFlagged(self.edited("ParamSfo.swift", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }"), "log")

    def test_mutation_key_renamed(self):
        self.assertFlagged(self.edited("ParamSfo.swift", '"SAVEDATA_DIRECTORY"', '"SAVEDATA_DIR"'), "key")

    def test_mutation_header_not_newly_authored(self):
        self.assertFlagged(self.edited("SaveDataView.swift", "// SPDX-License-Identifier: GPL-2.0-only\n", "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n"), "header")

    def test_mutation_menu_row_only_for_some_titles(self):
        raw = self.edited("GameListView.swift", 'items.append(GameLibraryControllerMenuItem(action: .gameData, title: "Save Data"',
                          'if entry.locked == false { items.append(GameLibraryControllerMenuItem(action: .gameData, title: "Save Data"')
        raw["GameListView.swift"] = raw["GameListView.swift"].replace(
            'systemImage: "externaldrive"))', 'systemImage: "externaldrive")) }', 1)
        self.assertFlagged(raw, "menu")

    def test_mutation_sheet_row_does_nothing(self):
        self.assertFlagged(self.edited("GameListView.swift", "case .gameData:", "case .gameData: break\n        case .rename:"), "sheet")

    def test_mutation_view_lists_nothing(self):
        self.assertFlagged(self.edited("SaveDataView.swift", "saves(forSerial:", "savesFor("), "view")


if __name__ == "__main__":
    unittest.main(verbosity=2)
