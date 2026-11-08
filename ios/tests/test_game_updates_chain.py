#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""GameUpdates.swift and InstalledContent.swift, run on the Mac host over built version lists, compare PS3 versions as
numbers, build the exact install chain for a pick, and read and remove only a game's own GD update folder.
It says nothing about Sony's live lists, installing the chain, or a device."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments
from update_fixtures import disc_sfo, exdata, game_folder, package, title_sfo, ver_xml

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
UPDATES = APP / "Updates" / "GameUpdates.swift"
CONTENT = APP / "Install" / "InstalledContent.swift"
SFO = APP / "Library" / "ParamSfo.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
PLAN_ID = re.compile(r"D-17-|GUPD-|17-0")
TOP_TYPE = re.compile(r"^(?:(?:public|private|fileprivate|internal|final|@\w+)\s+)*(?:struct|enum|class|actor|protocol)\s+(\w+)",
                      re.M)

ID = "ABCD12345"
SHA = "ab" * 20

# Out of order, with a later duplicate of 01.10 whose size must not count.
C1_ORDER = [("01.50", 400000005), ("01.01", 400000001), ("01.84", 400000007), ("01.10", 400000003),
            ("01.03", 400000002), ("01.70", 400000006), ("01.20", 400000004), ("01.10", 999)]
C1_SIZES = {}
for _version, _size in C1_ORDER:
    C1_SIZES.setdefault(_version, _size)
C1_BYTES = sum(C1_SIZES.values())

FIVE = ["01.01", "01.02", "01.03", "01.04", "01.05"]
GT_TOTAL = 11222479504
GT_SIZES = [GT_TOTAL // 21] * 20 + [GT_TOTAL - 20 * (GT_TOTAL // 21)]


def lists():
    """{file name: document bytes}, every one synthetic."""
    return {
        "c1.xml": ver_xml(ID, [package(v, s, SHA, firmware="04.6000") for v, s in C1_ORDER], title="Synthetic Title"),
        "five.xml": ver_xml(ID, [package(v, 1000 + i, SHA) for i, v in enumerate(FIVE)], self_closing=True),
        "empty.xml": ver_xml(ID, []),
        "gt.xml": ver_xml(ID, [package("01.{0:02d}".format(i + 1), s, SHA) for i, s in enumerate(GT_SIZES)]),
    }


def folders(tmp):
    """One hdd0-shaped root per installed-state case, every one synthetic."""
    tmp = Path(tmp)
    game_folder(tmp / "u1", ID, title_sfo(ID, "GD", "01.04"))
    disc_sfo(tmp / "u1", "01.00")
    game_folder(tmp / "u2", ID, title_sfo(ID, "HG", "01.30"))
    game_folder(tmp / "other", ID, title_sfo("WXYZ99999", "GD", "01.04"))
    game_folder(tmp / "corrupt", ID, b"\0PSX" + title_sfo(ID, "GD", "01.04")[4:])
    game_folder(tmp / "nosfo", ID, None)
    game_folder(tmp / "lower", ID, title_sfo(ID, "gd", "01.04"))
    game_folder(tmp / "gdx", ID, title_sfo(ID, " GDX", "01.04"))
    game_folder(tmp / "spaced", ID, title_sfo(ID, " GD ", "01.04"))
    disc_sfo(tmp / "u4", "01.00")
    (tmp / "iso").mkdir()
    exdata(tmp / "u5", ["EP0000-ABCD12345_00-0000000000000001.rap", "EP0000-ABCD12345_00-0000000000000002.edat",
                        "EP0000-WXYZ99999_00-0000000000000003.rap", "EP0000-ABCD12345_00-0000000000000004.txt"])
    game_folder(tmp / "u6", ID, title_sfo(ID, "GD", "01.04"))
    (tmp / "u6" / "game" / "inner").mkdir()


DRIVER = r"""
import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func list(_ name: String) -> [TitlePackage] {
    VerXml.parse(try! Data(contentsOf: dir.appendingPathComponent(name)), titleID: "ABCD12345")!.packages
}
func describe(_ chain: UpdateChain?) -> String {
    guard let chain = chain else { return "nil" }
    return "versions=\(chain.packages.map(\.version).joined(separator: ",")) removes=\(chain.removesFirst) bytes=\(chain.bytes)"
}
func chain(_ name: String, _ installed: String?, _ base: String?, _ target: String) -> String {
    describe(GameUpdates.chain(published: list(name), installed: installed, base: base, target: target))
}

let pairs = [("1.10", "1.9"), ("01.04", "1.04"), ("1.04", "1.05"), ("2", "1.99"), ("1.0", "1")]
print("V1 " + pairs.map { "\($0.0)/\($0.1)=\(GameUpdates.compare($0.0, $0.1))" }.joined(separator: " "))
print("C1 " + chain("c1.xml", "01.00", nil, "1.84"))
print("C2 " + chain("five.xml", "01.04", nil, "01.05"))
print("C3 " + chain("five.xml", "01.05", nil, "01.05"))
print("C4 " + chain("five.xml", "01.05", "01.00", "01.03"))
print("C5 none=" + chain("five.xml", nil, nil, "01.03") + " disc=" + chain("five.xml", nil, "01.02", "01.03"))
print("C6 missing=" + chain("five.xml", "01.01", nil, "01.09") + " empty=" + chain("empty.xml", nil, nil, "01.01"))
print("C7 " + chain("gt.xml", nil, nil, "01.21"))
print("S1 " + ["01.05", "1.05", "10.00", ""].map { "[\(InstalledTitle.shortVersion($0))]" }.joined())
print("N1 " + GameUpdates.cacheName(titleID: "ABCD12345", version: "01.05"))

func state(_ root: String, disc: Bool = false, serial: String = "ABCD12345", game: String = "game") -> InstalledUpdate {
    let r = dir.appendingPathComponent(root)
    return InstalledContent.update(gameDir: r.appendingPathComponent(game), exdataDir: r.appendingPathComponent("exdata"),
                                   serial: serial, discSFO: disc ? r.appendingPathComponent("disc/PS3_GAME/PARAM.SFO") : nil)
}
func removable(_ s: InstalledUpdate) -> Bool { InstalledContent.canRemoveUpdate(s, serial: "ABCD12345") }
func show(_ s: InstalledUpdate) -> String {
    "installed=\(s.installedVersion ?? "nil") update=\(s.hasUpdate) psn=\(s.isPSN) remove=\(removable(s)) disc=\(s.discVersion ?? "nil") dlc=\(s.dlcCount)"
}
print("U1 " + show(state("u1", disc: true)))
print("U2 " + show(state("u2")))
print("U3 " + ["other", "corrupt", "nosfo", "lower", "gdx", "spaced"].map { "\($0)=\(removable(state($0)))" }.joined(separator: " "))
print("U4 disc=" + show(state("u4", disc: true)) + " iso=" + show(state("iso")))
print("U5 " + show(state("u5")))
let escaped = state("u6", serial: "../ABCD12345", game: "game/inner")
print("U6 folder=\(escaped.folder) remove=\(InstalledContent.canRemoveUpdate(escaped, serial: "../ABCD12345"))")
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


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def other_types():
    """Top-level type names declared in every ios/app file but GameUpdates.swift."""
    names = set()
    for path in sorted(APP.rglob("*.swift")):
        if path.name != UPDATES.name:
            names.update(TOP_TYPE.findall(without_comments(read(path))))
    return names


def chain_problems(raw, foreign):
    """raw: {file name: raw text} for GameUpdates.swift and InstalledContent.swift; foreign: type names."""
    updates, content = raw["GameUpdates.swift"], raw["InstalledContent.swift"]
    updates_code, content_code = without_comments(updates), without_comments(content)
    problems = []
    if IMPORT.findall(updates_code) != ["Foundation"]:
        problems.append("imports: GameUpdates.swift imports {0}, not only Foundation".format(IMPORT.findall(updates_code)))
    own = set(TOP_TYPE.findall(updates_code))
    named = sorted(name for name in foreign - own if re.search(r"\b" + name + r"\b", updates_code))
    if named:
        problems.append("apptype: GameUpdates.swift names {0}".format(named))
    detail = body_of(content_code, r"var\s+detail\s*:\s*String\s*") or ""
    if "shortVersion(" not in detail or "dropFirst()" in detail:
        problems.append("short: InstalledTitle.detail does not take its version through shortVersion(")
    for name, code in (("GameUpdates.swift", updates_code), ("InstalledContent.swift", content_code)):
        if LOGGING.search(code):
            problems.append("log: {0} logs".format(name))
    for name, text in (("GameUpdates.swift", updates), ("InstalledContent.swift", content)):
        if any(PLAN_ID.search(line) for line in comment_lines(text)):
            problems.append("comment: {0} names a plan ID in a comment".format(name))
    if IMPORT.findall(content_code) != ["Foundation"]:
        problems.append("ihost: InstalledContent.swift imports {0}, not only Foundation".format(IMPORT.findall(content_code)))
    for name in ("scan", "update"):
        if "dlcCount(" not in (body_of(content_code, r"static\s+func\s+" + name + r"\s*\(") or ""):
            problems.append("dlc: InstalledContent.{0} does not count DLC through dlcCount(".format(name))
    remove = body_of(content_code, r"static\s+func\s+canRemoveUpdate\s*\(") or ""
    if not re.search(r'\.category\.trimmingCharacters\(in:\s*\.whitespaces\)\s*==\s*"GD"', remove):
        problems.append("gd: canRemoveUpdate does not compare the trimmed category with \"GD\" exactly")
    if not re.search(r"\.titleID\s*==\s*serial\b", remove):
        problems.append("titleid: canRemoveUpdate does not compare the TITLE_ID with the serial")
    return sorted(problems)


def real_inputs():
    return {"GameUpdates.swift": read(UPDATES), "InstalledContent.swift": read(CONTENT)}


class TheChainHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (UPDATES, CONTENT, SFO):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            for name, body in lists().items():
                (Path(tmp) / name).write_bytes(body)
            folders(tmp)
            out = compile_and_run([UPDATES, CONTENT, SFO], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_v1_versions_compare_as_numbers(self):
        self.assertEqual("V1 1.10/1.9=1 01.04/1.04=0 1.04/1.05=-1 2/1.99=1 1.0/1=0", self.line("V1"))

    def test_c1_a_long_incremental_chain_in_order_with_exact_bytes(self):
        self.assertGreater(C1_BYTES, 2 ** 31)
        self.assertEqual("C1 versions=01.01,01.03,01.10,01.20,01.50,01.70,01.84 removes=false bytes={0}".format(C1_BYTES),
                         self.line("C1"))

    def test_c2_one_step_up_is_one_package(self):
        self.assertEqual("C2 versions=01.05 removes=false bytes=1004", self.line("C2"))

    def test_c3_the_installed_version_is_current(self):
        self.assertEqual("C3 nil", self.line("C3"))

    def test_c4_an_older_target_removes_first(self):
        self.assertEqual("C4 versions=01.01,01.02,01.03 removes=true bytes=3003", self.line("C4"))

    def test_c5_nothing_installed_starts_from_the_first_or_above_the_disc(self):
        self.assertEqual("C5 none=versions=01.01,01.02,01.03 removes=false bytes=3003 "
                         "disc=versions=01.03 removes=false bytes=1002", self.line("C5"))

    def test_c6_no_chain_without_the_target(self):
        self.assertEqual("C6 missing=nil empty=nil", self.line("C6"))

    def test_c7_a_ten_gigabyte_chain_sums_exactly(self):
        self.assertEqual(GT_TOTAL, sum(GT_SIZES))
        self.assertEqual("C7 versions={0} removes=false bytes={1}".format(
            ",".join("01.{0:02d}".format(i + 1) for i in range(21)), GT_TOTAL), self.line("C7"))

    def test_s1_short_version(self):
        self.assertEqual("S1 [1.05][1.05][10.00][]", self.line("S1"))

    def test_n1_cache_name(self):
        self.assertEqual("N1 ABCD12345-01.05.pkg", self.line("N1"))

    def test_u1_a_disc_game_with_an_update(self):
        self.assertEqual("U1 installed=01.04 update=true psn=false remove=true disc=01.00 dlc=0", self.line("U1"))

    def test_u2_a_bought_game_is_never_removable(self):
        self.assertEqual("U2 installed=01.30 update=false psn=true remove=false disc=nil dlc=0", self.line("U2"))

    def test_u3_only_an_exact_gd_folder_for_this_title_is_removable(self):
        self.assertEqual("U3 other=false corrupt=false nosfo=false lower=false gdx=false spaced=true", self.line("U3"))

    def test_u4_no_folder_reads_the_disc_or_nothing(self):
        self.assertEqual("U4 disc=installed=01.00 update=false psn=false remove=false disc=01.00 dlc=0 "
                         "iso=installed=nil update=false psn=false remove=false disc=nil dlc=0", self.line("U4"))

    def test_u5_dlc_count_uses_the_shared_filter(self):
        self.assertEqual("U5 installed=nil update=false psn=false remove=false disc=nil dlc=2", self.line("U5"))

    def test_u6_a_serial_cannot_walk_out_of_the_game_folder(self):
        self.assertEqual("U6 folder=false remove=false", self.line("U6"))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], chain_problems(real_inputs(), other_types()))

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
        cls.foreign = other_types()

    def assertFlagged(self, name, old, new, prefix):
        text = self.raw[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        raw = dict(self.raw)
        raw[name] = mutated
        problems = chain_problems(raw, self.foreign)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_imports_uikit(self):
        self.assertFlagged("GameUpdates.swift", "import Foundation", "import Foundation\nimport UIKit", "imports")

    def test_mutation_names_another_app_type(self):
        self.assertFlagged("GameUpdates.swift", "import Foundation",
                           "import Foundation\nlet probe = InstalledTitle.self", "apptype")

    def test_mutation_detail_inlines_the_rule(self):
        self.assertFlagged("InstalledContent.swift", "Self.shortVersion(version)",
                           "version.dropFirst().isEmpty ? version : version", "short")

    def test_mutation_log(self):
        self.assertFlagged("GameUpdates.swift", "import Foundation",
                           "import Foundation\nfunc probe(_ s: String) { print(s) }", "log")

    def test_mutation_plan_id_in_a_comment(self):
        self.assertFlagged("InstalledContent.swift", "import Foundation", "// GUPD-02.\nimport Foundation", "comment")

    def test_mutation_installed_content_imports_uikit(self):
        self.assertFlagged("InstalledContent.swift", "import Foundation", "import Foundation\nimport UIKit", "ihost")

    def test_mutation_scan_inlines_its_dlc_filter(self):
        self.assertFlagged("InstalledContent.swift", "let dlc = dlcCount(licenceNames:", "let dlc = inlineCount(licenceNames:",
                           "dlc")

    def test_mutation_category_loosened_to_a_prefix(self):
        self.assertFlagged("InstalledContent.swift", '.trimmingCharacters(in: .whitespaces) == "GD" && state.titleID',
                           '.trimmingCharacters(in: .whitespaces).hasPrefix("GD") && state.titleID', "gd")

    def test_mutation_title_id_test_dropped(self):
        self.assertFlagged("InstalledContent.swift", " && state.titleID == serial", "", "titleid")


if __name__ == "__main__":
    unittest.main(verbosity=2)
