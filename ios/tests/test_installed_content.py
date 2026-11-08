#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""InstalledContent.swift, run on the Mac host over built folders, lists only titles with a parsing PARAM.SFO, with
size, DLC count, icon and detail line; static checks cover the Install screen's rows and its one shared refresh.
It says nothing about the list on a phone."""

import json
import os
import re
import stat
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import INTEGER, STRING, build_sfo
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SFO = APP / "Library" / "ParamSfo.swift"
CONTENT = APP / "Install" / "InstalledContent.swift"
INSTALL = APP / "Install" / "InstallView.swift"
LIBRARY = APP / "Library" / "GameListView.swift"
SHEET = APP / "Install" / "UninstallSheet.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")


def title_sfo(serial, title, category, app_ver=None):
    entries = [("CATEGORY", STRING, category.encode() + b"\0"),
               ("TITLE", STRING, title.encode() + b"\0"),
               ("TITLE_ID", STRING, serial.encode() + b"\0"),
               ("ATTRIBUTE", INTEGER, 0)]
    if app_ver is not None:
        entries.insert(0, ("APP_VER", STRING, app_ver.encode() + b"\0"))
    return build_sfo(entries)


def regular_size(folder):
    """The sum of the regular files under folder, links not followed."""
    total = 0
    for root, _, files in os.walk(folder):
        for name in files:
            info = os.lstat(os.path.join(root, name))
            if stat.S_ISREG(info.st_mode):
                total += info.st_size
    return total


def write_fixtures(tmp):
    """Every fixture, synthetic, into tmp. Returns {serial: size}."""
    tmp = Path(tmp)
    game = tmp / "game"

    def folder(name, sfo, files):
        path = game / name
        path.mkdir(parents=True)
        if sfo is not None:
            (path / "PARAM.SFO").write_bytes(sfo)
        for rel, size in files:
            (path / rel).parent.mkdir(parents=True, exist_ok=True)
            (path / rel).write_bytes(bytes((i * 5) & 0xFF for i in range(size)))
        return path

    one = folder("ABCD12345", title_sfo("ABCD12345", "One", "HG", "01.02"),
                 [("ICON0.PNG", 10), ("USRDIR/EBOOT.BIN", 1000), ("USRDIR/DATA.BIN", 300)])
    (tmp / "outside.bin").write_bytes(b"x" * 5000)
    os.symlink(str(tmp / "outside.bin"), str(one / "USRDIR" / "link.bin"))
    folder("ABCD12346", title_sfo("ABCD12346", "Another", "GD", "01.04"), [("USRDIR/EBOOT.BIN", 700)])
    folder("ABCD12347", None, [("USRDIR/EBOOT.BIN", 10)])
    folder("ABCD12348", b"\0PSX" + title_sfo("ABCD12348", "Bad", "HG")[4:], [])
    folder(".ABCD12349", title_sfo("ABCD12349", "Staged", "HG"), [])
    (game / "ABCD12350").write_bytes(b"not a folder")

    exdata = tmp / "exdata"
    exdata.mkdir()
    for name in ("UP0000-ABCD12345_00-AAAA.rap", "UP0000-ABCD12345_00-BBBB.edat",
                 "UP0000-ABCD12345_00-GAME.rap", "UP0000-WXYZ99999_00-CCCC.rap",
                 "UP0000-ABCD12346_00-DDDD.edat", "UP0000-ABCD12345_00-EEEE.txt"):
        (exdata / name).write_bytes(b"\0" * 16)
    return {"ABCD12345": regular_size(one), "ABCD12346": regular_size(game / "ABCD12346")}


DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let titles = InstalledContent.scan(gameDir: tmp.appendingPathComponent("game"),
                                   exdataDir: tmp.appendingPathComponent("exdata"),
                                   ownContentIds: ["ABCD12345": "UP0000-ABCD12345_00-GAME"])
print("N1 serials=\(titles.map(\.serial).joined(separator: ","))")
for t in titles {
    print("T serial=\(t.serial) title=\(t.title) category=\(t.category) version=\(t.appVersion ?? "-") size=\(t.size) dlc=\(t.dlcCount) icon=\(t.iconURL != nil) detail=\(t.detail)")
}
let none = InstalledContent.scan(gameDir: tmp.appendingPathComponent("nogame"), exdataDir: tmp.appendingPathComponent("exdata"), ownContentIds: [:])
let noex = InstalledContent.scan(gameDir: tmp.appendingPathComponent("game"), exdataDir: tmp.appendingPathComponent("noexdata"), ownContentIds: [:])
print("M1 nogame=\(none.count) noexdata=\(noex.map { String($0.dlcCount) }.joined(separator: ","))")
func line(_ category: String, _ version: String?, _ dlc: Int) -> String {
    InstalledTitle(serial: "X", title: "X", category: category, appVersion: version, size: 0, dlcCount: dlc, iconURL: nil).detail
}
print("L1 both=\(line("GD", "01.04", 2))|neither=\(line("GD", nil, 0))|hg=\(line("HG", "01.00", 1))")
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


def sheet_closure(text):
    """The `onUninstalled: { ... }` closure of the UninstallSheet( text constructs, or None."""
    at = text.find("UninstallSheet(")
    close = text.find("onUninstalled:", at) if at != -1 else -1
    if close == -1:
        return None
    start, end = brace_span(text, close)
    return text[start:end]


def refresh_problems(raw):
    """raw: {file name: raw text} for InstallView, GameListView, UninstallSheet, InstalledContent."""
    problems = []
    view = without_comments(raw["InstallView.swift"])
    shared = body_of(view, r"func\s+refreshAfterContentChange\s*\(") or ""
    scan = shared.find("rescanInstalled()")
    if scan == -1 or shared.find("refreshLibrary()") < scan:
        problems.append("refresh: refreshAfterContentChange does not rescan the installed list and then refresh the library")
    if not re.search(r"if\s+await\s+model\.install\(urls:\s*urls\)\s*\{\s*await\s+model\.refreshAfterContentChange\(onInstalled\)\s*\}", view):
        problems.append("install: a successful install does not end with model.refreshAfterContentChange(onInstalled)")
    if re.search(r"\bonInstalled\(\)", view):
        problems.append("install: InstallView.swift calls onInstalled() outside the shared refresh")

    library = without_comments(raw["GameListView.swift"])
    for name, text in (("InstallView.swift", view), ("GameListView.swift", library)):
        closure = sheet_closure(text)
        if closure is None or closure.count("refreshAfterContentChange") != 1 or ".refresh()" in closure.replace(
                "model.refresh()", "", 1 if name == "GameListView.swift" else 0):
            problems.append("remove: {0}'s UninstallSheet does not refresh through ONE refreshAfterContentChange".format(name))
    if re.search(r"\.refresh\(\)", without_comments(raw["UninstallSheet.swift"])):
        problems.append("remove: UninstallSheet.swift refreshes the library itself")

    licence = body_of(library, r"func\s+installLicence\s*\(") or ""
    ok = licence.find("status == 0")
    if ok == -1 or licence.count("installs.refreshAfterContentChange") != 1 or licence.find("installs.refreshAfterContentChange") < ok:
        problems.append("licence: a successful licence does not end with ONE installs.refreshAfterContentChange")
    if "InstallView(model: installs" not in library:
        problems.append("licence: the library's Install sheet does not share its content model")

    for name in ("InstallView.swift", "InstalledContent.swift"):
        if "LibraryCache" in without_comments(raw[name]):
            problems.append("cache: {0} names LibraryCache".format(name))
    return sorted(problems)


def content_problems(raw):
    """raw: {file name: raw text} for InstalledContent.swift and InstallView.swift."""
    problems = []
    source = raw["InstalledContent.swift"]
    code = without_comments(source)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: InstalledContent.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: InstalledContent.swift logs; no serial or path may be logged")
    if "SPDX-License-Identifier: GPL-2.0-only" not in "\n".join(source.split("\n")[:2]):
        problems.append("header: InstalledContent.swift has no GPL-2.0-only SPDX line in its first two lines")
    if "Adapted from ARMSX2" in source:
        problems.append("header: InstalledContent.swift carries the lifted-file header")
    if '"PARAM.SFO"' not in code or "ParamSfo(data:" not in code:
        problems.append("sfo: InstalledContent.swift does not read PARAM.SFO through ParamSfo(data:")

    view = without_comments(raw["InstallView.swift"])
    for token in ("InstalledContent.scan(", "Rpcs3Bridge.hdd0Directory", "Rpcs3Bridge.userDirectory"):
        if token not in view:
            problems.append("scan: InstallView.swift does not use {0}".format(token))
    if "probeLicence(forSerial:" not in view:
        problems.append("own: InstallView.swift does not take the own content id from probeLicence(forSerial:")
    for token in ("ByteCountFormatter", ".detail", "UninstallSheet("):
        if token not in view:
            problems.append("row: InstallView.swift does not use {0}".format(token))
    return sorted(problems)


def real_inputs():
    return {path.name: read(path) for path in (CONTENT, INSTALL, LIBRARY, SHEET)}


class TheScanHolds(unittest.TestCase):
    lines = None
    sizes = None

    @classmethod
    def setUpClass(cls):
        read(SFO)
        read(CONTENT)
        with tempfile.TemporaryDirectory() as tmp:
            cls.sizes = write_fixtures(tmp)
            out = compile_and_run([SFO, CONTENT], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_n1_only_titles_with_a_parsing_sfo_sorted_by_title(self):
        self.assertEqual("N1 serials=ABCD12346,ABCD12345", self.line("N1"))

    def test_t_each_title_reads_its_own_folder(self):
        self.assertEqual([
            "T serial=ABCD12346 title=Another category=GD version=01.04 size={0} dlc=1 icon=false detail=Update 1.04 · 1 DLC".format(
                self.sizes["ABCD12346"]),
            "T serial=ABCD12345 title=One category=HG version=01.02 size={0} dlc=2 icon=true detail=2 DLC".format(
                self.sizes["ABCD12345"]),
        ], [line for line in self.lines if line.startswith("T ")])

    def test_m1_missing_folders_list_and_count_nothing(self):
        self.assertEqual("M1 nogame=0 noexdata=0,0", self.line("M1"))

    def test_l1_the_detail_line(self):
        self.assertEqual("L1 both=Update 1.04 · 2 DLC|neither=|hg=1 DLC", self.line("L1"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], content_problems(real_inputs()))

    def test_real_files_refresh_once(self):
        self.assertEqual([], refresh_problems(real_inputs()))

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
        problems = content_problems(raw) + refresh_problems(raw)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.assertFlagged(self.edited("InstalledContent.swift", "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_a_serial(self):
        self.assertFlagged(self.edited("InstalledContent.swift", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }"), "log")

    def test_mutation_header_not_newly_authored(self):
        self.assertFlagged(self.edited("InstalledContent.swift", "// SPDX-License-Identifier: GPL-2.0-only\n", "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n"), "header")

    def test_mutation_sfo_not_read(self):
        self.assertFlagged(self.edited("InstalledContent.swift", "ParamSfo(data:", "nilSfo("), "sfo")

    def test_mutation_scan_not_used(self):
        self.assertFlagged(self.edited("InstallView.swift", "InstalledContent.scan(", "InstalledContent.cached("), "scan")

    def test_mutation_own_not_probed(self):
        self.assertFlagged(self.edited("InstallView.swift", "probeLicence(forSerial:", "probeNothing(forSerial:"), "own")

    def test_mutation_row_has_no_uninstall(self):
        self.assertFlagged(self.edited("InstallView.swift", "UninstallSheet(", "EmptyView("), "row")

    def test_mutation_refresh_skips_the_rescan(self):
        self.assertFlagged(self.edited("InstallView.swift", "await rescanInstalled()\n        await refreshLibrary()",
                                       "await refreshLibrary()"), "refresh")

    def test_mutation_install_refreshes_the_library_directly(self):
        self.assertFlagged(self.edited("InstallView.swift", "{ await model.refreshAfterContentChange(onInstalled) }",
                                       "{ await onInstalled() }"), "install")

    def test_mutation_remove_refreshes_twice(self):
        self.assertFlagged(self.edited("GameListView.swift", "onUninstalled: {",
                                       "onUninstalled: { await installs.refreshAfterContentChange { await model.refresh() }"), "remove")

    def test_mutation_remove_sheet_refreshes_itself(self):
        self.assertFlagged(self.edited("UninstallSheet.swift", "await onUninstalled()",
                                       "await onUninstalled()\n        await library.refresh()"), "remove")

    def test_mutation_licence_never_refreshes(self):
        self.assertFlagged(self.edited("GameListView.swift", "if status == 0 { await installs.refreshAfterContentChange",
                                       "if status == 0 { await installs.rescanInstalled(); _ = { await installs.refreshNothing"), "licence")

    def test_mutation_cache_read(self):
        self.assertFlagged(self.edited("InstalledContent.swift", "import Foundation",
                                       "import Foundation\nlet cached = LibraryCache.self"), "cache")


if __name__ == "__main__":
    unittest.main(verbosity=2)
