# SPDX-License-Identifier: GPL-2.0-only
"""Uninstall removes one title folder and nothing outside it: checks over the ABI, the bridge and the Swift sheet.
Each mutation edits the real text once and shows the matching check fail.
"""

import re
import tempfile
import unittest
from pathlib import Path

from source_helpers import abi_text, facade_text


ROOT = Path(__file__).resolve().parents[2]

ABI_H = ROOT / "ios" / "bridge" / "rpcs3_ios_abi.h"

ABI_DIR = ROOT / "ios" / "bridge"

BRIDGE_DIR = ROOT / "ios" / "app" / "Bridge"

LIBRARY = ROOT / "ios" / "app" / "Library" / "GameListView.swift"

SHEET = ROOT / "ios" / "app" / "Install" / "UninstallSheet.swift"

STORE = ROOT / "ios" / "app" / "Saves" / "SaveDataStore.swift"


# Uninstall backs up save data and deletes save states outright, pinned here as data.
DECISION = "backup-saves"


def read(path):
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise ValueError("%s is empty" % path)
    return text


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def body_of(code, signature):
    """The brace-matched body following the first `signature(` in comment-stripped code, or None."""
    start = code.find(signature + "(")
    if start < 0:
        return None
    brace = code.find("{", code.find(")", start))
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace + 1:i]
    return None


LOG_CALL = re.compile(r"(?:\b\w+_log\.\w+|\bLOG_\w+|\bf?printf|\bsnprintf|\b(?:armsx3_)?stderr_witness_printf)\s*\((.*?)\);", re.S)

LOG_LEAK = re.compile(r"%s|c_str|\b(?:path|dir|serial)\b")

UNINSTALL = "Rpcs3IOS_Status Rpcs3IOS_UninstallGame"

HDD0 = "Rpcs3IOS_Status Rpcs3IOS_GetHdd0Dir"

GAME_DIR = "std::string game_dir"

UNINSTALL_FACADE = "+ (void)uninstallGameWithSerial:"

HDD0_FACADE = "+ (void)hdd0DirectoryWithCompletion:"

GATE = r"\s*if \(pthread_main_np\(\) != 0\)\s*\{\s*return refuse_main_thread\(\"%s\"\);"

STOPPED = re.compile(r"Emu\.GetStatus\(false\) != system_state::stopped\)\s*\{\s*return RPCS3_IOS_STATUS_WRONG_STATE;")

UNINSTALL_LOG = 'utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_UninstallGame finished with status %d.\\n", static_cast<int>(status));'


def source_problems(header, source, bridge):
    problems = []
    head = strip_comments(header)
    for decl in ("Rpcs3IOS_Status Rpcs3IOS_UninstallGame(const char* serial);",
                 "Rpcs3IOS_Status Rpcs3IOS_GetHdd0Dir(char* out, uint32_t cap);"):
        if decl not in head:
            problems.append("UNI-DECL: the header does not declare " + decl)

    code = strip_comments(source)
    bodies = {name: body_of(code, name) for name in (UNINSTALL, HDD0, GAME_DIR)}
    missing = [name for name, body in bodies.items() if body is None]
    if missing:
        return problems + ["UNI-MISSING: no definition of " + ", ".join(missing)]
    uninstall, hdd0, game_dir = (bodies[n] for n in (UNINSTALL, HDD0, GAME_DIR))

    if not re.match(GATE % "Rpcs3IOS_UninstallGame", uninstall) or not re.match(GATE % "Rpcs3IOS_GetHdd0Dir", hdd0):
        problems.append("UNI-GATE: the main-thread gate is not each entry's first statement")
    component = uninstall.find("!serial_component_ok(serial)")
    built = uninstall.find("game_dir(serial)")
    if component < 0 or built < 0 or component > built:
        problems.append("UNI-COMPONENT: the uninstall does not check serial_component_ok before it builds the folder")
    if (built < 0 or "const std::string dir = game_dir(serial);" not in uninstall
            or " ".join(game_dir.split()) != 'return rpcs3::utils::get_hdd0_dir() + "game/" + serial;'):
        problems.append("UNI-PATH: the folder is not game_dir(serial) = get_hdd0_dir() + \"game/\" + serial")
    if "starts_with" in uninstall:
        problems.append("UNI-PREFIX: the uninstall uses a prefix test")
    if not STOPPED.search(uninstall):
        problems.append("UNI-STOPPED: the uninstall does not refuse a non-stopped emulator with WRONG_STATE")
    removed = uninstall.find("fs::remove_all(")
    sfo = uninstall.find('fs::is_file(dir + "/PARAM.SFO")')
    if sfo < 0 or removed < 0 or sfo > removed:
        problems.append("UNI-SFO: a folder without PARAM.SFO is not refused before the removal")
    link = uninstall.find("is_symlink(")
    if link < 0 or removed < 0 or link > removed:
        problems.append("UNI-LINK: a link in the folder is not looked for before the removal")
    if uninstall.count("remove_all(") != 1 or "fs::remove_all(dir)" not in uninstall:
        problems.append("UNI-REMOVE: fs::remove_all does not appear exactly once, on dir")
    if UNINSTALL_LOG not in uninstall:
        problems.append("UNI-LOG: the uninstall does not log its status line")
    calls = list(LOG_CALL.finditer(uninstall))
    if any(LOG_LEAK.search(c.group(1)) for c in calls if not c.group(0).startswith("snprintf")):
        problems.append("UNI-LOG: a log call in the uninstall names a path or a serial")
    if any(not c.group(0).startswith("snprintf") for c in LOG_CALL.finditer(hdd0)):
        problems.append("UNI-LOG: the hdd0 getter logs")

    facade = strip_comments(bridge)
    for signature, entry in ((UNINSTALL_FACADE, "Rpcs3IOS_UninstallGame("), (HDD0_FACADE, "Rpcs3IOS_GetHdd0Dir(")):
        body = body_of(facade, signature)
        if body is None or not re.search(r"dispatch_async\(rpcs3_bridge_queue\(\), \^\{.*" + re.escape(entry), body, re.S):
            problems.append("UNI-FACADE: %s does not call %s on the façade queue" % (signature, entry[:-1]))
    return problems


def swift_problems(library, sheet):
    problems = []
    lib = strip_comments(library)
    items = body_of(lib, "func contextItems") or ""
    gate = re.search(r'if !entry\.fromGamesYml && entry\.category == "HG" \{\s*items\.append\(GameLibraryControllerMenuItem\('
                     r'action: \.deleteGame, title: "Uninstall", systemImage: "trash"\)\)\s*\}', items)
    perform = body_of(lib, "func performContext") or ""
    arm = re.search(r"case\s+\.deleteGame\s*:(.*?)(?=\bcase\b|\bdefault\b)", perform, re.S)
    if gate is None or items.count(".deleteGame") != 1 or arm is None or ".uninstall(" not in arm.group(1):
        problems.append("UNI-MENU: Uninstall is not offered for an installed HG title only, or does not open the sheet")

    code = strip_comments(sheet)
    uninstall = body_of(code, "static func uninstall") or ""
    confirm = body_of(code, "func confirm") or ""
    called = confirm.find("Self.uninstall(serial: serial, alsoSaves: alsoSaves)")
    if "Rpcs3Bridge.uninstallGame(withSerial:" not in uninstall or called < 0 or confirm.find("await onUninstalled()") < called:
        problems.append("UNI-SHEET: the sheet does not uninstall through the façade and then refresh the library")
    return problems


def saves_problems(sheet, store):
    problems = []
    code = strip_comments(sheet)
    if ("static let savesDefault = false" not in code
            or "@State private var alsoSaves = UninstallSheet.savesDefault" not in code
            or 'Toggle("Also delete save data and save states", isOn: $alsoSaves)' not in code):
        problems.append("UNI-OFF: the save-deleting box does not start unticked")
    uninstall = body_of(code, "static func uninstall") or ""
    gate = uninstall.find("guard status == 0, alsoSaves else")
    if gate < 0 or uninstall.find("deleteSaves(") < gate:
        problems.append("UNI-GATED: the saves are not deleted only for a ticked box after a successful uninstall")
    delete = body_of(code, "static func deleteSaves") or ""
    if (".saves(forSerial: serial)" not in delete or "Rpcs3Bridge.saveStateDirectory(forSerial: serial" not in delete
            or re.search(r"contentsOfDirectory|enumerator\(|subpathsOfDirectory", code)):
        problems.append("UNI-LISTS: the saves and states are not found through saves(forSerial:) and saveStateDirectory(forSerial:) only")
    if re.search(r"trophy|exdata", code, re.I):
        problems.append("UNI-TROPHY: the sheet names a trophy or exdata path")
    if DECISION == "backup-saves" and (
            not re.search(r"for save in store\.saves\(forSerial: serial\) \{.*?try store\.delete\(dirName: save\.dirName\)", delete, re.S)
            or "removeItem(atPath: states)" not in delete
            or "zipDirectory" in code or "deleteWithoutBackup" in code or "deleteWithoutBackup" in strip_comments(store)):
        problems.append("UNI-BACKUP: the saves are not each deleted through the backing-up SaveDataStore.delete, "
                        "or the states are backed up, or a no-backup delete exists")
    if (not re.search(r"catch \{[^{}]*status = -1[^{}]*break\s*\}", delete)
            or not re.search(r"if status == 0, [^{]*\{[^}]*removeItem\(atPath: states\)", delete)):
        problems.append("UNI-STOP: a failed save does not stop the deletion before the states")
    detached = [m.end() - 1 for m in re.finditer(r"Task\.detached\s*\([^)]*\)\s*\{", code)]
    spans = []
    for brace in detached:
        depth = 0
        for i in range(brace, len(code)):
            depth += {"{": 1, "}": -1}.get(code[i], 0)
            if depth == 0:
                spans.append((brace, i))
                break
    for token in ("store.delete(", ".saves(forSerial:", "removeItem(atPath:"):
        sites = [m.start() for m in re.finditer(re.escape(token), code)]
        if not sites or any(not any(a < s < b for a, b in spans) for s in sites):
            problems.append("UNI-OFFMAIN: offmain: `{0}` is missing or outside a Task.detached block".format(token))
    return problems


class TheSourceKeepsItsPromises(unittest.TestCase):
    def test_the_real_files_have_no_problems(self):
        self.assertEqual([], source_problems(read(ABI_H), abi_text(ABI_DIR), facade_text(BRIDGE_DIR)))

    def test_the_swift_files_have_no_problems(self):
        self.assertEqual([], swift_problems(read(LIBRARY), read(SHEET)))

    def test_the_save_deletion_has_no_problems(self):
        self.assertEqual([], saves_problems(read(SHEET), read(STORE)))

    def test_an_empty_input_raises(self):
        with tempfile.NamedTemporaryFile("w", suffix=".h") as empty:
            with self.assertRaises(ValueError):
                read(empty.name)


class EachSourceCheckCanFail(unittest.TestCase):
    def setUp(self):
        self.header = read(ABI_H)
        self.text = abi_text(ABI_DIR)
        self.bridge = facade_text(BRIDGE_DIR)
        self.library = read(LIBRARY)
        self.sheet = read(SHEET)
        self.store = read(STORE)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone: " + old)
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def fails_with(self, prefix, header=None, text=None, bridge=None):
        found = source_problems(self.header if header is None else header, self.text if text is None else text,
                                self.bridge if bridge is None else bridge)
        self.assertTrue(any(p.startswith(prefix) for p in found), (prefix, found))

    def swift_fails_with(self, prefix, library=None, sheet=None):
        found = swift_problems(self.library if library is None else library, self.sheet if sheet is None else sheet)
        self.assertTrue(any(p.startswith(prefix) for p in found), (prefix, found))

    def saves_fail_with(self, prefix, sheet=None, store=None):
        found = saves_problems(self.sheet if sheet is None else sheet, self.store if store is None else store)
        self.assertTrue(any(p.startswith(prefix) for p in found), (prefix, found))

    def after(self, marker):
        start = self.text.index(marker)
        return self.text[:start], self.text[start:]

    def test_a_changed_declaration_fails(self):
        self.fails_with("UNI-DECL", header=self.edited(
            self.header, "Rpcs3IOS_UninstallGame(const char* serial);", "Rpcs3IOS_UninstallGame(const char* path);"))

    def test_a_removed_gate_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-GATE", text=head + self.edited(tail, "pthread_main_np() != 0", "false"))

    def test_an_unchecked_serial_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-COMPONENT", text=head + self.edited(tail, "!serial_component_ok(serial)", "serial[0] == '\\0'"))

    def test_a_folder_built_elsewhere_fails(self):
        head, tail = self.after(GAME_DIR + "(")
        self.fails_with("UNI-PATH", text=head + self.edited(tail, '+ "game/" + serial', '+ serial'))

    def test_a_prefix_test_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-PREFIX", text=head + self.edited(
            tail, "const std::string dir = game_dir(serial);",
            "const std::string dir = game_dir(serial);\n\tif (!dir.starts_with(\"/\")) return RPCS3_IOS_STATUS_PATH_REJECTED;"))

    def test_an_uninstall_while_running_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-STOPPED", text=head + self.edited(tail, "!= system_state::stopped", "== system_state::running"))

    def test_a_folder_without_param_sfo_removed_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-SFO", text=head + self.edited(tail, 'fs::is_file(dir + "/PARAM.SFO")', "fs::is_dir(dir)"))

    def test_a_folder_holding_a_link_removed_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.assertIn("is_symlink(", tail)
        self.fails_with("UNI-LINK", text=head + tail.replace("is_symlink(", "is_regular_file("))

    def test_a_second_removal_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-REMOVE", text=head + self.edited(tail, "fs::remove_all(dir)", "fs::remove_all(dir + \"/..\")"))

    def test_a_logged_serial_fails(self):
        head, tail = self.after(UNINSTALL + "(")
        self.fails_with("UNI-LOG", text=head + self.edited(
            tail, "const std::string dir = game_dir(serial);",
            "const std::string dir = game_dir(serial);\n\tstd::fprintf(stderr, \"%s\\n\", serial);"))

    def test_a_facade_off_the_queue_fails(self):
        self.fails_with("UNI-FACADE", bridge=self.edited(self.bridge, "Rpcs3IOS_UninstallGame(", "Rpcs3IOS_GetState("))

    def test_uninstall_offered_for_a_games_yml_title_fails(self):
        self.swift_fails_with("UNI-MENU", library=self.edited(
            self.library, 'if !entry.fromGamesYml && entry.category == "HG" {', "if true {"))

    def test_a_library_not_refreshed_fails(self):
        self.swift_fails_with("UNI-SHEET", sheet=self.edited(self.sheet, "await onUninstalled()", "await Task.yield()"))

    def test_a_box_ticked_by_default_fails(self):
        self.saves_fail_with("UNI-OFF", sheet=self.edited(self.sheet, "static let savesDefault = false", "static let savesDefault = true"))

    def test_saves_deleted_with_the_box_off_fails(self):
        self.saves_fail_with("UNI-GATED", sheet=self.edited(self.sheet, "guard status == 0, alsoSaves else", "guard status == 0 else"))

    def test_a_folder_listed_by_hand_fails(self):
        self.saves_fail_with("UNI-LISTS", sheet=self.edited(
            self.sheet, "var deleted = 0", "_ = try? FileManager.default.contentsOfDirectory(atPath: user)\n        var deleted = 0"))

    def test_a_trophy_path_fails(self):
        self.saves_fail_with("UNI-TROPHY", sheet=self.edited(self.sheet, "var deleted = 0", 'let unused = "trophy/"\n        var deleted = 0'))

    def test_a_save_deleted_without_its_backup_fails(self):
        self.saves_fail_with("UNI-BACKUP", sheet=self.edited(
            self.sheet, "try store.delete(dirName: save.dirName)", "try FileManager.default.removeItem(atPath: save.dirName)"))

    def test_a_failed_backup_that_carries_on_fails(self):
        self.saves_fail_with("UNI-STOP", sheet=self.edited(self.sheet, "status = -1\n                        break", "status = -1"))

    def test_save_work_back_on_main_fails(self):
        # The detached block becomes a plain Task, which runs on the main actor.
        self.saves_fail_with("UNI-OFFMAIN", sheet=self.edited(
            self.sheet, "await Task.detached(priority: .userInitiated)", "await Task(priority: .userInitiated)"))


if __name__ == "__main__":
    unittest.main()
