#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""SettingsModel.swift and PresetStore.swift compiled with swiftc on the host and run over the settings dump fixtures.
The driver output lines are compared with values Python derives from the same fixtures and the help JSON.
"""

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS = Path(__file__).resolve().parents[1]

MODEL = IOS / "app" / "Settings" / "SettingsModel.swift"

FIXTURE_ALL = IOS / "tests" / "fixtures" / "settings-dump-all.json"

FIXTURE_GAME = IOS / "tests" / "fixtures" / "settings-dump-game.json"

HELP = IOS / "app" / "Resources" / "settings-help.json"

CPU_WORDS = re.compile(r"\b(PPU|SPU|MFC|LLVM|SPURS)\b", re.I)

ADVANCED_WORDS = re.compile(r"\b(debug|profiler|log|renderdoc|gdb|dump|analyser|inspection|host_root|park)s?\b", re.I)

ADDED = ["Video", "Brand New Upstream Setting"]

HELP_ONLY = "atomic FIFO buffer"

# Written into a temporary directory at run time; @@ALL@@ and @@GAME@@ become the fixtures' paths.
DRIVER = r"""
import Foundation

let all = try! Data(contentsOf: URL(fileURLWithPath: @@ALL@@))
if let dump = SettingsModel.decode(all) {
    let ids = Set(dump.leaves.map { $0.id })
    print("D1 leaves=\(dump.leaves.count) unique=\(ids.count == dump.leaves.count ? 1 : 0)")
} else {
    print("D1 nil")
}

var object = try! JSONSerialization.jsonObject(with: all) as! [String: Any]
object["version"] = 2
let bumped = try! JSONSerialization.data(withJSONObject: object)
print("D0 version2=\(SettingsModel.decode(bumped) == nil ? "nil" : "decoded")")

if let game = SettingsModel.decode(try! Data(contentsOf: URL(fileURLWithPath: @@GAME@@))) {
    let leaves = game.leaves
    print("D2 leaves=\(leaves.count) db=\(leaves.filter { $0.db != nil }.count) game=\(leaves.filter { $0.game != nil }.count) forced=\(leaves.filter { $0.forced != nil }.count)")
} else {
    print("D2 nil")
}

let counts = SettingsImportCounts(kept: @@KEPT@@, unknown: @@UNKNOWN@@, defaulted: @@DEFAULTED@@, locked: @@LOCKED@@)
print("I1 total=\(counts.kept + counts.unknown + counts.defaulted + counts.locked)")

if let help = SettingsHelp(url: URL(fileURLWithPath: @@HELP@@)) {
    let text = help.text(for: @@HELPKEY@@)
    print("H1 found=\(text == nil ? 0 : 1) lines=\(text?.components(separatedBy: "\n").count ?? 0)")
    print("H2 found=\(help.text(for: ["No Such Section", "No Such Setting"]) == nil ? 0 : 1)")
} else {
    print("H nil")
}

// @@MUTATED@@ and @@RENAMED@@ are filled in at run time.
let gameDump = SettingsModel.decode(try! Data(contentsOf: URL(fileURLWithPath: @@GAME@@)))!
let helpText = SettingsHelp(url: URL(fileURLWithPath: @@HELP@@))
func ids(_ leaves: [SettingLeaf]) -> Set<String> { Set(leaves.map { $0.id }) }
func pane(_ kind: SettingsPaneKind, _ dump: SettingsDump = gameDump, advanced: Bool = true) -> [SettingLeaf] {
    SettingsModel.leaves(kind, in: dump, showAdvanced: advanced)
}
let rIds = ids(pane(.renderer)), cIds = ids(pane(.cpu)), pIds = ids(pane(.performance))
print("M1 all=\(pane(.all).count) renderer=\(rIds.count) cpu=\(cIds.count) performance=\(pIds.count) audio=\(pane(.audio).count) network=\(pane(.network).count) fixes=\(pane(.fixes).count) rp=\(rIds.intersection(pIds).count) cp=\(cIds.intersection(pIds).count)")
print("M1 hidden all=\(pane(.all, advanced: false).count) sections=\(SettingsModel.sections(pane(.renderer)).map { $0.title }.joined(separator: "|"))")

let mutated = SettingsModel.decode(try! Data(contentsOf: URL(fileURLWithPath: @@MUTATED@@)))!
let renamedId = @@RENAMED@@.joined(separator: SettingsModel.pathSeparator)
let addedId = ["Video", "Brand New Upstream Setting"].joined(separator: SettingsModel.pathSeparator)
for (label, id) in [("renamed", renamedId), ("added", addedId)] {
    let all = ids(pane(.all, mutated, advanced: false)).contains(id) ? 1 : 0
    let renderer = ids(pane(.renderer, mutated, advanced: false)).contains(id) ? 1 : 0
    print("M2 \(label) all=\(all) renderer=\(renderer)")
}
let brandNew = SettingsModel.search(mutated, query: "brand new", help: helpText, showAdvanced: false)
print("M2 search results=\(brandNew.results.count) added=\(brandNew.results.contains { $0.id == addedId } ? 1 : 0)")

func mk(_ def: String, _ global: String, db: String? = nil, game: String? = nil, forced: String? = nil,
        dynamic: Bool = false, random: Bool = false) -> SettingLeaf {
    SettingLeaf(path: ["Crafted", "Leaf"], type: "bool", dynamic: dynamic, defaultValue: def, global: global, db: db,
                game: game, forced: forced, variants: [], min: "", max: "", randomDefault: random, fix: false, liveId: nil)
}
func show(_ marks: [SettingMarker]) -> String {
    marks.isEmpty ? "none" : marks.map { mark -> String in
        switch mark {
        case .changed: return "changed"
        case .thisGame: return "thisGame"
        case .fromDatabase: return "fromDatabase"
        case .gameUses(let value): return "gameUses(\(value))"
        case .required: return "required"
        case .nextBoot: return "nextBoot"
        }
    }.joined(separator: ",")
}
let crafted: [(String, SettingLeaf, SettingsScope, Bool, Bool)] = [
    ("changed", mk("false", "true"), .allGames, false, false),
    ("default", mk("false", "false"), .allGames, false, false),
    ("random", mk("a", "b", random: true), .allGames, false, false),
    ("thisgame", mk("1", "1", db: "2", game: "3"), .game, false, false),
    ("database", mk("1", "1", db: "2"), .game, false, false),
    ("gameuses", mk("1", "1", game: "3"), .allGames, false, true),
    ("nothost", mk("1", "1", game: "3"), .allGames, false, false),
    ("forcedgame", mk("1", "2", db: "2", game: "3", forced: "4"), .game, true, true),
    ("forcedall", mk("1", "2", game: "3", forced: "4"), .allGames, true, true),
    ("nextboot", mk("1", "1"), .allGames, true, false),
    ("dynamic", mk("1", "1", dynamic: true), .allGames, true, false),
    ("stopped", mk("1", "1"), .game, false, false),
    ("order", mk("1", "2", game: "3"), .allGames, true, true),
]
for (label, leaf, scope, running, host) in crafted {
    print("M3 \(label) \(show(SettingsModel.markers(leaf, scope: scope, gameRunning: running, fromGameHost: host)))")
}
let effective = [
    SettingsModel.effective(mk("1", "2", db: "3", game: "4"), scope: .game),
    SettingsModel.effective(mk("1", "2", db: "3"), scope: .game),
    SettingsModel.effective(mk("1", "2"), scope: .game),
    SettingsModel.effective(mk("1", "2", game: "4", forced: "5"), scope: .game),
    SettingsModel.effective(mk("1", "2", game: "4", forced: "5"), scope: .allGames),
    SettingsModel.effective(mk("1", "2", db: "3", game: "4"), scope: .allGames),
]
print("M3 effective \(effective.joined(separator: ","))")
print("M3 changedCount all=\(SettingsModel.changedCount(gameDump.leaves, scope: .allGames)) game=\(SettingsModel.changedCount(gameDump.leaves, scope: .game))")

func find(_ query: String, _ advanced: Bool) -> (results: [SettingLeaf], hiddenAdvanced: Int) {
    SettingsModel.search(gameDump, query: query, help: helpText, showAdvanced: advanced)
}
print("M4 diacritic results=\(find("SPÜ LOOP", true).results.count)")
print("M4 section results=\(find("vulkan", true).results.count)")
print("M4 help results=\(find("atomic FIFO buffer", true).results.count)")
let order = find("spu", true).results.map { leaf in gameDump.leaves.firstIndex { $0.id == leaf.id }! }
print("M4 order=\(order == order.sorted() && order.count > 1 ? 1 : 0)")
let gdbOff = find("gdb", false), gdbOn = find("gdb", true)
print("M4 advanced off results=\(gdbOff.results.count) hidden=\(gdbOff.hiddenAdvanced) on results=\(gdbOn.results.count) hidden=\(gdbOn.hiddenAdvanced)")

var kinds = ["toggle": 0, "choice": 0, "stepper": 0, "text": 0, "locked": 0]
var firstStepper = "none"
for leaf in gameDump.leaves {
    switch SettingsModel.control(for: leaf) {
    case .toggle: kinds["toggle"]! += 1
    case .choice: kinds["choice"]! += 1
    case .stepper(let range):
        kinds["stepper"]! += 1
        if firstStepper == "none" { firstStepper = "\(range.lowerBound)...\(range.upperBound)" }
    case .text: kinds["text"]! += 1
    case .locked: kinds["locked"]! += 1
    }
}
print("M5 toggle=\(kinds["toggle"]!) choice=\(kinds["choice"]!) stepper=\(kinds["stepper"]!) text=\(kinds["text"]!) locked=\(kinds["locked"]!) first-stepper=\(firstStepper)")
"""

STORE = IOS / "app" / "Settings" / "PresetStore.swift"

P1_NAME = "../Fast/Preset " + "a" * 65

# Written into a temporary directory at run time; @@TMP@@ becomes that directory.
PRESET_DRIVER = r"""
import Foundation

let root = URL(fileURLWithPath: @@TMP@@, isDirectory: true)
func ymls(_ dir: URL) -> Int {
    ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".yml") }.count
}
func stem(_ entry: PresetEntry?) -> String { entry.map { ($0.file as NSString).deletingPathExtension } ?? "nil" }
let bytes = { (text: String) in Data(text.utf8) }

let s1 = PresetStore(directory: root.appendingPathComponent("p1"))
let e1 = s1.save(name: @@P1NAME@@, yaml: bytes("Video:\n  Frame limit: 30\n"), count: 3)
print("P1 files=\(ymls(s1.directory)) entries=\(s1.list().count) stem=\(stem(e1)) name=\(e1?.name ?? "nil")")

let s2 = PresetStore(directory: root.appendingPathComponent("p2"))
_ = s2.save(name: "Fast", yaml: bytes("old\n"), count: 3)
_ = s2.save(name: "Fast", yaml: bytes("new\n"), count: 5)
let l2 = s2.list()
print("P2 files=\(ymls(s2.directory)) entries=\(l2.count) count=\(l2.first?.count ?? -1) bytes=\(l2.first.flatMap { s2.data(for: $0) }.map { String(decoding: $0, as: UTF8.self) } == "new\n" ? "new" : "old")")

let s3 = PresetStore(directory: root.appendingPathComponent("p3"))
for name in ["Alpha", "Beta", "Gamma"] { _ = s3.save(name: name, yaml: bytes("x\n"), count: 1) }
let before = s3.list().map(\.name).joined(separator: "|")
try? FileManager.default.removeItem(at: s3.directory.appendingPathComponent("Beta.yml"))
print("P3 before=\(before) after=\(s3.list().map(\.name).joined(separator: "|"))")

let s4 = PresetStore(directory: root.appendingPathComponent("p4"))
let one = s4.save(name: "One", yaml: bytes("x\n"), count: 1)!
_ = s4.save(name: "Two", yaml: bytes("y\n"), count: 2)
let deleted = s4.delete(one)
print("P4 deleted=\(deleted ? 1 : 0) files=\(ymls(s4.directory)) entries=\(s4.list().count) left=\(s4.list().map(\.name).joined(separator: "|"))")

for (kept, unknown, defaulted, locked) in [(3, 0, 0, 0), (3, 2, 0, 0), (3, 0, 1, 0), (3, 0, 0, 4), (3, 2, 1, 4)] {
    let counts = SettingsImportCounts(kept: kept, unknown: unknown, defaulted: defaulted, locked: locked)
    print("P6 \(PresetStore.importNotice(name: "Desk", counts: counts))")
}
print("P7 cap=\(PresetStore.importable(size: 256 * 1024) ? 1 : 0) over=\(PresetStore.importable(size: 256 * 1024 + 1) ? 1 : 0)")

let s5 = PresetStore(directory: root.appendingPathComponent("p5"))
let refused = [s5.save(name: "../", yaml: bytes("x\n"), count: 1), s5.save(name: " /.. ", yaml: bytes("x\n"), count: 1)]
print("P5 refused=\(refused.allSatisfy { $0 == nil } ? 1 : 0) folder=\(FileManager.default.fileExists(atPath: s5.directory.path) ? 1 : 0)")
"""


def preset_stem(name):
    """This file's own copy of the name filter, for ASCII inputs."""
    kept = "".join(c for c in name if (c.isascii() and c.isalnum()) or c in " -_").strip()[:64].strip()
    return None if kept in ("", ".", "..") else kept


# The notice sentences for an unknown, a refused and a failed write.
NOTICE = ("{n} settings this version doesn't have were left out.",
          "{n} settings matched their defaults and were left out, so database fixes still apply.",
          "{n} settings this device keeps for itself were left out.")


def notice(name, unknown, defaulted, locked):
    parts = ["Imported \u201c%s\u201d." % name]
    parts += [text.format(n=n) for text, n in zip(NOTICE, (unknown, defaulted, locked)) if n]
    return " ".join(parts)


def preset_lines():
    stem = preset_stem(P1_NAME)
    return ["P1 files=1 entries=1 stem=%s name=%s" % (stem, stem),
            "P2 files=1 entries=1 count=5 bytes=new",
            "P3 before=Alpha|Beta|Gamma after=Alpha|Gamma",
            "P4 deleted=1 files=1 entries=1 left=Two"] + [
            "P6 " + notice("Desk", *counts) for counts in ((0, 0, 0), (2, 0, 0), (0, 1, 0), (0, 0, 4), (2, 1, 4))] + [
            "P7 cap=1 over=0",
            "P5 refused=1 folder=0"]


# The import counts literal the driver builds (kept, unknown, defaulted, locked): the SPN fixture's counts.
COUNTS = (3, 2, 1, 2)


def read(path):
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise ValueError("%s is empty" % path)
    return text


def first_help(help_file):
    """The first key of the help JSON in sorted order, as a path, and its text."""
    texts = json.loads(read(help_file))["help"]
    key = sorted(texts)[0]
    return key.split("@@"), texts[key]


def in_overlay(leaf):
    return len(leaf["path"]) > 2 and leaf["path"][0] == "Video" and leaf["path"][1] == "Performance Overlay"


def is_cpu(leaf):
    return leaf["path"][0] == "Core" and CPU_WORDS.search(leaf["path"][-1]) is not None


def is_advanced(leaf):
    return any(ADVANCED_WORDS.search(name) for name in leaf["path"])


PANES = {
    "all": lambda leaf: True,
    "renderer": lambda leaf: leaf["path"][0] == "Video" and not in_overlay(leaf),
    "cpu": is_cpu,
    "performance": lambda leaf: in_overlay(leaf) or (leaf["path"][0] == "Core" and not is_cpu(leaf)),
    "audio": lambda leaf: leaf["path"][0] == "Audio",
    "network": lambda leaf: leaf["path"][0] == "Net",
    "fixes": lambda leaf: leaf["fix"],
}


def pane_ids(leaves, name, advanced=True):
    return ["@@".join(leaf["path"]) for leaf in leaves if PANES[name](leaf) and (advanced or not is_advanced(leaf))]


def section_titles(leaves):
    titles, last = [], None
    for leaf in leaves:
        if leaf["path"][:-1] != last:
            last = leaf["path"][:-1]
            titles.append(last[-1])
    return titles


def mutated_fixture(game_fixture):
    """The game fixture with its first direct, non-advanced `Video` leaf renamed and one leaf added."""
    data = json.loads(read(game_fixture))
    target = next(leaf for leaf in data["leaves"]
                  if len(leaf["path"]) == 2 and leaf["path"][0] == "Video" and not is_advanced(leaf))
    target["path"] = ["Video", target["path"][1] + " Renamed Upstream"]
    added = dict(target, path=list(ADDED))
    data["leaves"].insert(data["leaves"].index(target) + 1, added)
    return data, target["path"]


def matches(leaf, query, help_texts):
    texts = leaf["path"] + [help_texts.get("@@".join(leaf["path"]), "")]
    return any(query.lower() in text.lower() for text in texts)


def control(leaf):
    if leaf["forced"] is not None:
        return "locked"
    if leaf["type"] == "bool":
        return "toggle"
    if leaf["variants"]:
        return "choice"
    if leaf["type"] in ("int", "uint"):
        try:
            lo, hi = int(leaf["min"]), int(leaf["max"])
        except ValueError:
            return "text"
        if -2 ** 63 <= lo < 2 ** 63 and -2 ** 63 <= hi < 2 ** 63 and 0 <= hi - lo <= 100:
            return "stepper"
    return "text"


def model_lines(game_fixture, help_file):
    """M1-M5, each derived from the fixtures and the help JSON by this file's own copy of the rules."""
    leaves = json.loads(read(game_fixture))["leaves"]
    texts = json.loads(read(help_file))["help"]
    ids = {name: set(pane_ids(leaves, name)) for name in PANES}
    lines = ["M1 all=%d renderer=%d cpu=%d performance=%d audio=%d network=%d fixes=%d rp=%d cp=%d" % (
        len(ids["all"]), len(ids["renderer"]), len(ids["cpu"]), len(ids["performance"]), len(ids["audio"]),
        len(ids["network"]), len(ids["fixes"]), len(ids["renderer"] & ids["performance"]),
        len(ids["cpu"] & ids["performance"])),
        "M1 hidden all=%d sections=%s" % (len(pane_ids(leaves, "all", False)), "|".join(
            section_titles([leaf for leaf in leaves if PANES["renderer"](leaf)])))]

    mutated, renamed = mutated_fixture(game_fixture)
    for label, path in (("renamed", renamed), ("added", ADDED)):
        lines.append("M2 %s all=%d renderer=%d" % (label, int("@@".join(path) in pane_ids(mutated["leaves"], "all", False)),
                                                    int("@@".join(path) in pane_ids(mutated["leaves"], "renderer", False))))
    brand = [leaf for leaf in mutated["leaves"] if matches(leaf, "brand new", texts) and not is_advanced(leaf)]
    lines.append("M2 search results=%d added=%d" % (len(brand), int(any(leaf["path"] == ADDED for leaf in brand))))

    lines += ["M3 changed changed", "M3 default none", "M3 random none", "M3 thisgame thisGame",
              "M3 database fromDatabase", "M3 gameuses gameUses(3)", "M3 nothost none", "M3 forcedgame required",
              "M3 forcedall required", "M3 nextboot nextBoot", "M3 dynamic none", "M3 stopped none",
              "M3 order changed,gameUses(3),nextBoot", "M3 effective 4,3,2,5,5,2"]
    free = [leaf for leaf in leaves if leaf["forced"] is None]
    lines.append("M3 changedCount all=%d game=%d" % (
        sum(1 for leaf in free if not leaf["randomDefault"] and leaf["global"] != leaf["default"]),
        sum(1 for leaf in free if leaf["game"] is not None)))

    found = lambda query: [leaf for leaf in leaves if matches(leaf, query, texts)]
    gdb = found("gdb")
    lines += ["M4 diacritic results=%d" % len(found("spu loop")),
              "M4 section results=%d" % len(found("vulkan")),
              "M4 help results=%d" % len(found(HELP_ONLY)),
              "M4 order=1",
              "M4 advanced off results=%d hidden=%d on results=%d hidden=0" % (
                  sum(1 for leaf in gdb if not is_advanced(leaf)), sum(1 for leaf in gdb if is_advanced(leaf)), len(gdb))]

    kinds = [control(leaf) for leaf in leaves]
    first = next(leaf for leaf in leaves if control(leaf) == "stepper")
    lines.append("M5 toggle=%d choice=%d stepper=%d text=%d locked=%d first-stepper=%s...%s" % (
        kinds.count("toggle"), kinds.count("choice"), kinds.count("stepper"), kinds.count("text"),
        kinds.count("locked"), first["min"], first["max"]))
    return lines


def expected_lines(fixture, game_fixture):
    leaves = json.loads(read(fixture))["leaves"]
    game = json.loads(read(game_fixture))["leaves"]
    count = lambda key: sum(1 for leaf in game if leaf[key] is not None)
    return ["D1 leaves=%d unique=1" % len(leaves), "D0 version2=nil",
            "D2 leaves=%d db=%d game=%d forced=%d" % (len(game), count("db"), count("game"), count("forced")),
            "I1 total=%d" % sum(COUNTS),
            "H1 found=1 lines=%d" % len(first_help(HELP)[1].split("\n")), "H2 found=0"] + model_lines(game_fixture, HELP)


class TheModelDecodes(unittest.TestCase):
    def test_the_fixture_decodes(self):
        with tempfile.TemporaryDirectory() as tmp:
            driver = DRIVER.replace("@@ALL@@", json.dumps(str(FIXTURE_ALL))).replace("@@GAME@@", json.dumps(str(FIXTURE_GAME)))
            for name, value in zip(("@@KEPT@@", "@@UNKNOWN@@", "@@DEFAULTED@@", "@@LOCKED@@"), COUNTS):
                driver = driver.replace(name, str(value))
            driver = driver.replace("@@HELP@@", json.dumps(str(HELP))).replace(
                "@@HELPKEY@@", json.dumps(first_help(HELP)[0], ensure_ascii=False))
            mutated, renamed = mutated_fixture(FIXTURE_GAME)
            mutated_path = Path(tmp) / "settings-dump-mutated.json"
            mutated_path.write_text(json.dumps(mutated), encoding="utf-8")
            driver = driver.replace("@@MUTATED@@", json.dumps(str(mutated_path))).replace(
                "@@RENAMED@@", json.dumps(renamed, ensure_ascii=False))
            out = compile_and_run([MODEL], driver, tmp)
        sys.stderr.write("\n" + out.strip() + "\n")
        self.assertEqual(expected_lines(FIXTURE_ALL, FIXTURE_GAME), out.splitlines())


class ThePresetStoreKeepsItsFiles(unittest.TestCase):
    """P1-P5 on the Mac host, over a temporary directory."""

    def test_P1_to_P5(self):
        self.assertEqual(80, len(P1_NAME))
        with tempfile.TemporaryDirectory() as tmp:
            driver = PRESET_DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@P1NAME@@", json.dumps(P1_NAME))
            out = compile_and_run([MODEL, STORE], driver, tmp)
        sys.stderr.write("\n" + out.strip() + "\n")
        self.assertEqual(preset_lines(), out.splitlines())


class TheRulesHoldOverTheFixtures(unittest.TestCase):
    """What the M lines claim, asserted on Python's own derivation, so a fixture that makes a claim vacuous fails."""

    def setUp(self):
        self.leaves = json.loads(read(FIXTURE_GAME))["leaves"]
        self.texts = json.loads(read(HELP))["help"]

    def test_M1_every_tree_pane_has_leaves_and_none_overlap(self):
        ids = {name: set(pane_ids(self.leaves, name)) for name in PANES}
        self.assertEqual(len(ids["all"]), len(self.leaves))
        for name in ("renderer", "cpu", "performance", "audio", "network", "fixes"):
            self.assertTrue(ids[name], name)
        self.assertFalse(ids["renderer"] & ids["performance"])
        self.assertFalse(ids["cpu"] & ids["performance"])

    def test_M4_queries_are_not_vacuous(self):
        names_only = [leaf for leaf in self.leaves if matches(leaf, HELP_ONLY, {})]
        self.assertEqual([], names_only, "the help-only query must match no name")
        self.assertTrue([leaf for leaf in self.leaves if matches(leaf, HELP_ONLY, self.texts)])
        gdb = [leaf for leaf in self.leaves if matches(leaf, "gdb", self.texts)]
        self.assertEqual([True], [is_advanced(leaf) for leaf in gdb], "gdb must match one advanced leaf only")

    def test_M5_every_control_kind_occurs(self):
        self.assertEqual({"toggle", "choice", "stepper", "text", "locked"}, {control(leaf) for leaf in self.leaves})


if __name__ == "__main__":
    unittest.main()
