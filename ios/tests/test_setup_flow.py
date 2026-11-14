#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""SetupRules.swift, run alone on the Mac host, decides who sees first-run setup and where it resumes, and gives each
step's values and primary; static checks pin how the root presents it and that JIT-off steps touch no emulator.
It says nothing about what a phone shows."""

import itertools
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import braced, scan

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
RULES = APP / "Shell" / "SetupRules.swift"
FLOW = APP / "Shell" / "SetupFlow.swift"
FRONTEND_ROOT = APP / "Shell" / "ARMSX3FrontendRoot.swift"
DIAGNOSTICS = ROOT / "ios" / "app" / "Diagnostics" / "DiagnosticsStore.swift"
VFS_CONFIG = ROOT / "rpcs3" / "Emu" / "vfs_config.h"
SYSINFO = ROOT / "rpcs3" / "util" / "sysinfo.cpp"

SAVED = (-3, 0, 1, 3, 6, 7, 8, 9)
LAUNCH_CASES = list(itertools.product((False, True), SAVED, (False, True), (False, True)))
STEPS = range(-1, 9)
SUPPORT = "/AppSupport"
TITLES = {"continueStep": "Continue", "installFirmware": "Install Firmware", "addGameFolder": "Add Game Folder",
          "finishSetup": "Finish Setup"}
ROUTER = ".environment(\\.menuControllerInputRouter, controllerInput)"
LIBRARY = APP / "Library" / "LibraryModel.swift"

# (folders, games or None, jitOn) -> the Game Folders value for step 5.
FOLDER_CASES = {
    (0, None, False): "None", (1, None, False): "1 folder", (2, None, False): "2 folders",
    (0, 0, True): "None", (1, 1, True): "1 folder, 1 game", (2, 3, True): "2 folders, 3 games",
    (1, 0, True): "1 folder, 0 games",
}
GAMES_PRIMARY = {0: "addGameFolder", 1: "continueStep", 3: "continueStep"}

# (jitOn, installed) -> step 4's primary; with JIT off there is none.
FIRMWARE_PRIMARY = {(True, False): "installFirmware", (True, True): "continueStep", (False, False): "none",
                    (False, True): "none"}
# (jitOn, version, installing, reading) -> step 4's Firmware value.
FIRMWARE_VALUES = [
    ((False, "", False, False), "Needs JIT"), ((False, "4.92", True, True), "Needs JIT"),
    ((True, "", True, False), "Installing\u2026"), ((True, "4.92", True, True), "Installing\u2026"),
    ((True, "", False, True), "Reading\u2026"), ((True, "", False, False), "Not installed"),
    ((True, "4.92", False, False), "4.92"),
]


def expected_launch(finished, saved, folders, firmware):
    """The first rule that holds: (present, markFinished)."""
    if 1 <= saved <= 7:
        return saved, False
    if finished:
        return None, False
    if not folders and not firmware:
        return 1, False
    return None, True


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def flag(value):
    return "true" if value else "false"


def driver():
    lines = [
        "import Foundation",
        "func show(_ n: Int?) -> String { n.map(String.init) ?? \"none\" }",
        "print(\"count \\(SetupRules.stepCount)\")",
    ]
    for finished, saved, folders, firmware in LAUNCH_CASES:
        lines.append(
            "do {{ let r = SetupRules.launchStep(finished: {0}, savedStep: {1}, hasFolders: {2}, hasFirmware: {3}); "
            "print(\"launch {4} {1} {5} {6} \\(show(r.present)) \\(r.markFinished ? 1 : 0)\") }}".format(
                flag(finished), saved, flag(folders), flag(firmware), int(finished), int(folders), int(firmware)))
    for n in STEPS:
        lines.append("print(\"next {0} \\(show(SetupRules.next({0})))\")".format(n))
        lines.append("print(\"prev {0} \\(show(SetupRules.previous({0})))\")".format(n))
    lines.append("print(\"marker \\(SetupRules.firmwareMarker(applicationSupport: URL(fileURLWithPath: \"{0}\", "
                 "isDirectory: true)).path)\")".format(SUPPORT))
    for raw in sorted(TITLES):
        lines.append("print(\"title {0} \\(SetupPrimary.{0}.title)\")".format(raw))
    for (folders, games, on) in sorted(FOLDER_CASES, key=str):
        lines.append("print(\"folders {0} {1} {2} \\(SetupRules.folderText(folders: {0}, games: {3}, jitOn: {4}))\")".format(
            folders, "none" if games is None else games, int(on), "nil" if games is None else games, flag(on)))
    for count in sorted(GAMES_PRIMARY):
        lines.append("print(\"gprimary {0} \\(SetupRules.gamesPrimary(folderCount: {0}).rawValue)\")".format(count))
    for (on, installed) in sorted(FIRMWARE_PRIMARY):
        lines.append("print(\"fprimary {0} {1} \\(SetupRules.firmwarePrimary(jitOn: {2}, installed: {3})?.rawValue ?? \"none\")\")".format(
            int(on), int(installed), flag(on), flag(installed)))
    for at, ((on, version, installing, reading), _) in enumerate(FIRMWARE_VALUES):
        lines.append("print(\"fvalue {0} \\(SetupRules.firmwareValue(jitOn: {1}, version: \"{2}\", installing: {3}, reading: {4}))\")".format(
            at, flag(on), version, flag(installing), flag(reading)))
    return "\n".join(lines) + "\n"


def core_marker(diagnostics, vfs, sysinfo):
    """The firmware marker's path under Application Support, from the three files that define it."""
    problems = []
    config = re.search(r'rpcs3Dir: support\.appendingPathComponent\("([^"]+)"', diagnostics)
    dev_flash = re.search(r'cfg::string dev_flash\{ this, "/dev_flash/", "\$\(EmulatorDir\)([^"]+)" \};', vfs)
    version = re.search(r'g_cfg_vfs\.get_dev_flash\(\) \+ "([^"]+)"', sysinfo)
    if config is None:
        problems.append("core: DiagnosticsStore.swift names no rpcs3Dir under Application Support")
    if dev_flash is None:
        problems.append("core: rpcs3/Emu/vfs_config.h no longer defaults dev_flash to $(EmulatorDir)<folder>")
    if version is None:
        problems.append("core: rpcs3/util/sysinfo.cpp no longer reads get_dev_flash() + <version file>")
    if problems:
        return None, problems
    return "{0}/{1}/{2}{3}".format(SUPPORT, config.group(1), dev_flash.group(1), version.group(1)), []


def rules_problems(out, marker):
    problems = []
    launch, nexts, prevs, titles, seen_marker, count = {}, {}, {}, {}, None, None
    for line in out.splitlines():
        parts = line.split(" ")
        if parts[0] == "launch" and len(parts) == 7:
            launch[(parts[1] == "1", int(parts[2]), parts[3] == "1", parts[4] == "1")] = (
                None if parts[5] == "none" else int(parts[5]), parts[6] == "1")
        elif parts[0] == "next" and len(parts) == 3:
            nexts[int(parts[1])] = None if parts[2] == "none" else int(parts[2])
        elif parts[0] == "prev" and len(parts) == 3:
            prevs[int(parts[1])] = None if parts[2] == "none" else int(parts[2])
        elif parts[0] == "marker":
            seen_marker = line[len("marker "):]
        elif parts[0] == "title":
            titles[parts[1]] = line.split(" ", 2)[2] if len(parts) > 2 else ""
        elif parts[0] == "count" and len(parts) == 2:
            count = int(parts[1])
    for case in LAUNCH_CASES:
        want = expected_launch(*case)
        if launch.get(case) != want:
            problems.append("launch: finished={0} saved={1} folders={2} firmware={3} gave {4}, wanted {5}".format(
                case[0], case[1], case[2], case[3], launch.get(case), want))
    if count != 7:
        problems.append("order: stepCount is {0}, wanted 7".format(count))
    for n in STEPS:
        want_next = n + 1 if 1 <= n <= 6 else None
        want_prev = n - 1 if 2 <= n <= 7 else None
        if nexts.get(n, "absent") != want_next:
            problems.append("order: next({0}) gave {1}, wanted {2}".format(n, nexts.get(n, "absent"), want_next))
        if prevs.get(n, "absent") != want_prev:
            problems.append("order: previous({0}) gave {1}, wanted {2}".format(n, prevs.get(n, "absent"), want_prev))
    if marker is not None and seen_marker != marker:
        problems.append("marker: firmwareMarker gave {0!r}, wanted {1!r}".format(seen_marker, marker))
    for raw, title in sorted(TITLES.items()):
        if titles.get(raw) != title:
            problems.append("title: SetupPrimary.{0} reads {1!r}, wanted {2!r}".format(raw, titles.get(raw), title))
    return problems


def games_problems(out):
    """Step 5's value and primary from the driver's `folders` and `gprimary` lines."""
    problems = []
    texts, primaries = {}, {}
    for line in out.splitlines():
        parts = line.split(" ", 4)
        if parts[0] == "folders" and len(parts) >= 4:
            games = None if parts[2] == "none" else int(parts[2])
            texts[(int(parts[1]), games, parts[3] == "1")] = parts[4] if len(parts) == 5 else ""
        elif parts[0] == "gprimary" and len(parts) == 3:
            primaries[int(parts[1])] = parts[2]
    for case, want in sorted(FOLDER_CASES.items(), key=str):
        if texts.get(case) != want:
            problems.append("games: folderText(folders: {0}, games: {1}, jitOn: {2}) reads {3!r}, wanted {4!r}".format(
                case[0], case[1], case[2], texts.get(case), want))
    for count, want in sorted(GAMES_PRIMARY.items()):
        if primaries.get(count) != want:
            problems.append("games: gamesPrimary(folderCount: {0}) is {1}, wanted {2}".format(count, primaries.get(count), want))
    return problems


def games_wiring_problems(library, flow):
    """A JIT-off pick is a bookmark only, and the step's add branches on the JIT state."""
    problems = []
    library_code, _ = scan(library)
    flow_code, _ = scan(flow)
    if "func rememberFolder(" not in library_code:
        problems.append("games-wiring: LibraryModel declares no rememberFolder")
    else:
        body = braced(library_code, "func rememberFolder(")
        if "store.add(" not in body:
            problems.append("games-wiring: rememberFolder does not call store.add(")
        for word in ("Rpcs3Bridge", "refresh("):
            if word in body:
                problems.append("games-wiring: rememberFolder names {0}; with JIT off nothing may start the emulator".format(word))
    if "func add(_ url: URL)" not in flow_code:
        problems.append("games-wiring: SetupFlow declares no func add(_ url: URL)")
        return problems
    add = braced(flow_code, "func add(_ url: URL)")
    at = add.find("if jitOn {")
    if at < 0:
        problems.append("games-wiring: the step's add does not branch on if jitOn {")
        return problems
    on = braced(add[at:], "if jitOn {")
    rest = add[at + len(on):].lstrip()
    off = braced(rest, "else {") if rest.startswith("else {") else ""
    if not off:
        problems.append("games-wiring: the step's if jitOn { has no else branch")
    if "addFolder(" not in on or "rememberFolder(" in on:
        problems.append("games-wiring: with JIT on the step's add does not call addFolder( alone")
    if "rememberFolder(" not in off or "addFolder(" in off:
        problems.append("games-wiring: with JIT off the step's add does not call rememberFolder( alone")
    return problems


def firmware_problems(out):
    """Step 4's primary and value from the driver's `fprimary` and `fvalue` lines."""
    problems = []
    primaries, values = {}, {}
    for line in out.splitlines():
        parts = line.split(" ", 3)
        if parts[0] == "fprimary" and len(parts) == 4:
            primaries[(parts[1] == "1", parts[2] == "1")] = parts[3]
        elif parts[0] == "fvalue" and len(parts) >= 2:
            head = line.split(" ", 2)
            values[int(head[1])] = head[2] if len(head) == 3 else ""
    for case, want in sorted(FIRMWARE_PRIMARY.items()):
        if primaries.get(case) != want:
            problems.append("firmware: firmwarePrimary(jitOn: {0}, installed: {1}) is {2}, wanted {3}".format(
                case[0], case[1], primaries.get(case), want))
    for at, (case, want) in enumerate(FIRMWARE_VALUES):
        if values.get(at) != want:
            problems.append("firmware: firmwareValue{0} reads {1!r}, wanted {2!r}".format(case, values.get(at), want))
    return problems


def firmware_wiring_problems(flow):
    """With JIT off step 4 never reads the firmware version, and it installs through the library's route."""
    problems = []
    code, _ = scan(flow)
    call = "Rpcs3Bridge.firmwareVersion"
    tasks, at = [], code.find(".task {")
    while at >= 0:
        tasks.append(braced(code[at:], ".task {"))
        at = code.find(".task {", at + 1)
    reading = [block for block in tasks if call in block]
    if len(reading) != 1:
        problems.append("firmware-wiring: {0} .task blocks call {1}, wanted one".format(len(reading), call))
    else:
        if not reading[0][len(".task {"):].lstrip().startswith("guard jitOn else"):
            problems.append("firmware-wiring: the .task that reads the firmware version does not begin guard jitOn else")
        if code.count(call) != reading[0].count(call):
            problems.append("firmware-wiring: {0} is called outside the guarded .task".format(call))
    for needle in ("SetupRules.firmwarePrimary(", "InstallModel", "FirmwarePicker(", ".install(urls: ["):
        if needle not in code:
            problems.append("firmware-wiring: SetupFlow does not use {0}".format(needle))
    return problems


def primary_problems(flow):
    """Step 3 with JIT off takes the JIT-off screen's primary on every row, reopen rows included."""
    problems = []
    code, _ = scan(flow)
    if "static func primary(step: Int" not in code:
        return ["primary: SetupFlow declares no static func primary(step: Int"]
    body = braced(code, "static func primary(step: Int")
    if "model.step.primary.map(SetupStepPrimary.jit)" not in body:
        problems.append("primary: step 3 does not return model.step.primary.map(SetupStepPrimary.jit)")
    if "reopenRows" in body:
        problems.append("primary: step 3 drops the primary on reopen rows")
    return problems


def controller_problems(flow):
    """Step 6's live name and its announcement."""
    problems = []
    code, literals = scan(flow)
    for name in (".GCControllerDidConnect", ".GCControllerDidDisconnect"):
        if name not in code:
            problems.append("controller: step 6 does not observe {0}".format(name))
    if "AccessibilityNotification.Announcement(" not in code:
        problems.append("controller: SetupFlow posts no AccessibilityNotification.Announcement(")
    if not any(l.endswith(" connected.") for l in literals):
        problems.append("controller: no literal ends ' connected.'")
    return problems


def source_problems(rules):
    problems = []
    lines = rules.splitlines()
    if not lines or "SPDX-License-Identifier: GPL-2.0-only" not in lines[0]:
        problems.append("source: line 1 is not the SPDX GPL-2.0-only line")
    if "Adapted from ARMSX2" in rules:
        problems.append("source: SetupRules.swift carries the lifted-file header")
    imports = set(re.findall(r"^\s*import\s+(\w+)", rules, flags=re.MULTILINE))
    if imports != {"Foundation"}:
        problems.append("source: imports {0}, wanted Foundation only".format(sorted(imports)))
    code, _ = scan(rules)
    for word in ("Rpcs3Bridge", "firmwareVersion", "contentsOf", "FileHandle", "InputStream"):
        if word in code:
            problems.append("source: SetupRules.swift's code names {0}".format(word))
    return problems


def wiring_problems(root, flow):
    problems = []
    root_code, _ = scan(root)
    flow_code, _ = scan(flow)

    start = braced(root_code, "private func start() async {")
    verdict, call, prepare = start.find("checkJIT()"), start.find("presentSetupIfDue()"), start.find("Rpcs3Bridge.prepare()")
    if call < 0 or verdict < 0 or prepare < 0 or not verdict < call < prepare:
        problems.append("wiring: start() does not call presentSetupIfDue() after checkJIT() and before Rpcs3Bridge.prepare()")
    if "func presentSetupIfDue(" not in root_code:
        problems.append("wiring: the root declares no presentSetupIfDue")
    else:
        due = braced(root_code, "func presentSetupIfDue(")
        for needle in ("SetupRules.launchStep(", "SettingsStore.shared",
                       "BookmarkStore(directory: LibraryModel.defaultDirectory).folders.isEmpty",
                       "PairingImport.isNonEmptyRegularFile(SetupRules.firmwareMarker("):
            if needle not in due:
                problems.append("wiring: presentSetupIfDue does not use {0}".format(needle))
    at = root_code.find(".fullScreenCover(item: $setup)")
    if at < 0:
        problems.append("wiring: the root has no .fullScreenCover(item: $setup)")
    else:
        cover = braced(root_code[at:], ".fullScreenCover(item: $setup)")
        if "SetupFlow(" not in cover:
            problems.append("wiring: the setup cover does not build SetupFlow(")
        if ROUTER not in cover:
            problems.append("wiring: the setup cover does not pass the root's router through \\.menuControllerInputRouter")
    if "NotificationCenter.default.publisher(for: SetupFlow.runAgainNotification)" not in root_code:
        problems.append("wiring: the root does not receive SetupFlow.runAgainNotification")

    if "SettingsStore.shared.setupStep =" not in flow_code:
        problems.append("wiring: SetupFlow does not save the step in SettingsStore.shared.setupStep")
    if "setupFinished = true" not in flow_code:
        problems.append("wiring: SetupFlow never sets setupFinished = true")
    return problems


def run(rules_text):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "SetupRules.swift"
        path.write_text(rules_text, encoding="utf-8")
        return compile_and_run([path], driver(), tmp)


def real_marker():
    marker, problems = core_marker(read(DIAGNOSTICS), read(VFS_CONFIG), read(SYSINFO))
    if problems:
        raise AssertionError(problems)
    return marker


class Launch(unittest.TestCase):
    def test_the_launch_table_order_marker_and_titles_hold(self):
        self.assertEqual(source_problems(read(RULES)), [])
        out = run(read(RULES))
        rows = [line for line in out.splitlines() if line.startswith("launch ")]
        self.assertEqual(len(rows), len(LAUNCH_CASES), "the driver printed a launch line per case")
        self.assertEqual(rules_problems(out, real_marker()), [])

    def test_control_the_expected_table_is_d_15_08(self):
        self.assertEqual(expected_launch(True, 3, True, True), (3, False))
        self.assertEqual(expected_launch(True, 0, False, False), (None, False))
        self.assertEqual(expected_launch(False, 8, False, False), (1, False))
        self.assertEqual(expected_launch(False, 0, True, False), (None, True))
        self.assertEqual(expected_launch(False, -3, False, True), (None, True))


class Static(unittest.TestCase):
    def test_the_core_still_defines_the_marker(self):
        self.assertTrue(real_marker().endswith("/rpcs3/dev_flash/vsh/etc/version.txt"))

    def test_the_root_and_the_flow_are_wired(self):
        self.assertEqual(wiring_problems(read(FRONTEND_ROOT), read(FLOW)), [])

    def test_edge_missing_or_empty_input_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(FileNotFoundError):
                read(Path(tmp) / "absent.swift")


class Games(unittest.TestCase):
    def test_the_folder_text_and_the_primary_hold(self):
        self.assertEqual(games_problems(run(read(RULES))), [])

    def test_a_jit_off_pick_is_a_bookmark_and_the_add_branches_on_jit(self):
        self.assertEqual(games_wiring_problems(read(LIBRARY), read(FLOW)), [])

    def test_control_the_cases_cover_zero_one_many_in_both_states(self):
        self.assertEqual({case[0] for case in FOLDER_CASES}, {0, 1, 2})
        self.assertEqual({case[2] for case in FOLDER_CASES}, {False, True})
        self.assertTrue(all(case[1] is None for case in FOLDER_CASES if not case[2]))


class Firmware(unittest.TestCase):
    def test_the_firmware_primary_and_value_hold(self):
        self.assertEqual(firmware_problems(run(read(RULES))), [])

    def test_with_jit_off_the_version_is_never_read(self):
        self.assertEqual(firmware_wiring_problems(read(FLOW)), [])

    def test_step_6_names_the_controller_on_screen_only(self):
        self.assertEqual(controller_problems(read(FLOW)), [])


class Primary(unittest.TestCase):
    def test_step_3_takes_the_jit_off_screens_primary_on_every_row(self):
        self.assertEqual(primary_problems(read(FLOW)), [])


class Mutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rules = read(RULES)
        cls.root = read(FRONTEND_ROOT)
        cls.flow = read(FLOW)
        cls.library = read(LIBRARY)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor moved")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def caught(self, prefix, problems):
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_launch_the_resume_rule_below_the_finished_rule(self):
        resume = "        if steps.contains(savedStep) { return (savedStep, false) }\n"
        finished = "        if finished { return (nil, false) }\n"
        text = self.edited(self.rules, resume + finished, finished + resume)
        self.caught("launch:", rules_problems(run(text), real_marker()))

    def test_mutation_launch_folders_or_firmware_reads_as_fresh(self):
        text = self.edited(self.rules, "!hasFolders && !hasFirmware", "!hasFolders || !hasFirmware")
        self.caught("launch:", rules_problems(run(text), real_marker()))

    def test_mutation_order_six_has_a_next(self):
        text = self.edited(self.rules, "step < stepCount ? step + 1", "step <= stepCount ? step + 1")
        self.caught("order:", rules_problems(run(text), real_marker()))

    def test_mutation_marker_the_dev_flash_folder_renamed(self):
        text = self.edited(self.rules, '"dev_flash/vsh/etc/version.txt"', '"dev_flash2/vsh/etc/version.txt"')
        self.caught("marker:", rules_problems(run(text), real_marker()))

    def test_mutation_core_the_dev_flash_default_moved(self):
        vfs = self.edited(read(VFS_CONFIG), '"$(EmulatorDir)dev_flash/"', '"$(EmulatorDir)flash/"')
        marker, problems = core_marker(read(DIAGNOSTICS), vfs, read(SYSINFO))
        self.caught("marker:", rules_problems(run(self.rules), marker))
        self.assertEqual(problems, [])

    def test_mutation_core_the_version_file_read_moved(self):
        sysinfo = self.edited(read(SYSINFO), 'g_cfg_vfs.get_dev_flash() + "vsh/etc/version.txt"',
                              'g_cfg_vfs.get_dev_flash() + std::string("vsh/etc/version.txt")')
        self.caught("core:", core_marker(read(DIAGNOSTICS), read(VFS_CONFIG), sysinfo)[1])

    def test_mutation_title_finish_reworded(self):
        text = self.edited(self.rules, '"Finish Setup"', '"Done"')
        self.caught("title:", rules_problems(run(text), real_marker()))

    def test_mutation_source_the_rule_asks_the_core_for_firmware(self):
        text = self.edited(self.rules, "import Foundation\n", "import Foundation\nlet probe = Rpcs3Bridge.firmwareVersion\n")
        self.caught("source:", source_problems(text))

    def test_mutation_wiring_the_cover_without_the_router(self):
        at = self.root.index(".fullScreenCover(item: $setup)")
        tail = self.edited(self.root[at:], ROUTER, "")
        self.caught("wiring:", wiring_problems(self.root[:at] + tail, self.flow))

    def test_mutation_wiring_setup_decided_after_prepare(self):
        text = self.edited(self.root, "        presentSetupIfDue()\n", "")
        text = self.edited(text, "        await ShaderPacks.reapply()\n", "        presentSetupIfDue()\n        await ShaderPacks.reapply()\n")
        self.caught("wiring:", wiring_problems(text, self.flow))

    def test_mutation_wiring_the_step_never_saved(self):
        text = self.edited(self.flow, "SettingsStore.shared.setupStep = step", "_ = step")
        self.caught("wiring:", wiring_problems(self.root, text))

    def test_mutation_games_one_folder_reads_plural(self):
        text = self.edited(self.rules, "folders == 1 ?", "folders == 0 ?")
        self.caught("games:", games_problems(run(text)))

    def test_mutation_games_wiring_remember_refreshes(self):
        anchor = 'let url = await relocated(url)\n        _ = try store.add(url)'
        text = self.edited(self.library, anchor, anchor + "\n        Task { await refresh() }")
        self.caught("games-wiring:", games_wiring_problems(text, self.flow))

    def test_mutation_games_wiring_branches_swapped(self):
        text = self.edited(self.flow, "library.addFolder(url)", "SWAP")
        text = self.edited(text, "library.rememberFolder(url)", "library.addFolder(url)")
        text = self.edited(text, "SWAP", "library.rememberFolder(url)")
        self.caught("games-wiring:", games_wiring_problems(self.library, text))

    def test_mutation_firmware_offered_with_jit_off(self):
        text = self.edited(self.rules, "guard jitOn else { return nil }", "guard jitOn else { return .installFirmware }")
        self.caught("firmware:", firmware_problems(run(text)))

    def test_mutation_firmware_wiring_the_guard_removed(self):
        text = self.edited(self.flow, "guard jitOn else { return }\n            steps.reading = true", "steps.reading = true")
        self.caught("firmware-wiring:", firmware_wiring_problems(text))

    def test_mutation_primary_the_reopen_row_check_put_back(self):
        old = "            return model.step.primary.map(SetupStepPrimary.jit)"
        text = self.edited(self.flow, old, "            if JitCard.reopenRows.contains(model.step.row) { return nil }\n" + old)
        self.caught("primary:", primary_problems(text))

    def test_mutation_primary_step_3_returns_none(self):
        text = self.edited(self.flow, "return model.step.primary.map(SetupStepPrimary.jit)", "return nil")
        self.caught("primary:", primary_problems(text))

    def test_mutation_controller_disconnect_unobserved(self):
        text = self.edited(self.flow, ".GCControllerDidDisconnect", ".GCControllerDidBecomeCurrent")
        self.caught("controller:", controller_problems(text))


if __name__ == "__main__":
    unittest.main()
