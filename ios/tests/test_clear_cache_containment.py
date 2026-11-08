# SPDX-License-Identifier: GPL-2.0-only
"""Clearing a title's compiled-code cache removes one folder and nothing outside it.
Rpcs3IOS_ClearGameCache takes a serial, checks it is one path component, builds cache/<serial> in C++, refuses a
running emulator, looks for the ppu- marker and for links, and removes once. Each check fails on an edited copy."""

import re
import tempfile
import unittest
from pathlib import Path

from source_helpers import abi_text, facade_text
from test_uninstall_containment import GATE, LOG_CALL, LOG_LEAK, STOPPED, body_of, read, strip_comments

ROOT = Path(__file__).resolve().parents[2]
ABI_H = ROOT / "ios" / "bridge" / "rpcs3_ios_abi.h"
ABI_DIR = ROOT / "ios" / "bridge"
BRIDGE_H = ROOT / "ios" / "app" / "Bridge" / "Rpcs3Bridge.h"
BRIDGE_DIR = ROOT / "ios" / "app" / "Bridge"

CLEAR = "Rpcs3IOS_Status Rpcs3IOS_ClearGameCache"
CLEAR_FACADE = "+ (void)clearGameCacheWithSerial:"
DIR_LINE = "const std::string dir = rpcs3::utils::get_cache_dir_by_serial(serial);"
CLEAR_LOG = 'utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_ClearGameCache finished with status %d.\\n", static_cast<int>(status));'


def source_problems(header, source, bridge_h, bridge_mm):
    problems = []
    if "Rpcs3IOS_Status Rpcs3IOS_ClearGameCache(const char* serial);" not in strip_comments(header):
        problems.append("CLR-DECL: the header does not declare Rpcs3IOS_ClearGameCache(const char* serial)")
    if CLEAR_FACADE not in strip_comments(bridge_h):
        problems.append("CLR-DECL: Rpcs3Bridge.h does not declare " + CLEAR_FACADE)

    body = body_of(strip_comments(source), CLEAR)
    if body is None:
        return ["CLR-MISSING: no definition of " + CLEAR]

    if not re.match(GATE % "Rpcs3IOS_ClearGameCache", body):
        problems.append("CLR-GATE: the main-thread gate is not the first statement")
    component = body.find("!serial_component_ok(serial)")
    built = body.find("get_cache_dir_by_serial(serial)")
    if component < 0 or built < 0 or component > built:
        problems.append("CLR-COMPONENT: serial_component_ok is not checked before the folder is built")
    if body.count(DIR_LINE) != 1 or len(re.findall(r"\bdir\s*=(?!=)", body)) != 1 or "starts_with" in body:
        problems.append("CLR-PATH: the folder is not get_cache_dir_by_serial(serial) alone, or a prefix test is used")
    if not STOPPED.search(body):
        problems.append("CLR-STOPPED: a non-stopped emulator is not refused with WRONG_STATE")
    if not re.search(r"\bkind\s*[!=]=\s*std::filesystem::file_type::not_found\b", body):
        problems.append("CLR-ABSENT: an absent folder is not told apart through file_type::not_found")
    removed = body.find("remove_all(")
    marker = body.find('"ppu-"')
    if marker < 0 or removed < 0 or marker > removed:
        problems.append("CLR-MARKER: the ppu- marker is not looked for before the removal")
    status_link = body.find("symlink_status(")
    walk_link = body.find("is_symlink(")
    if status_link < 0 or walk_link < 0 or removed < 0 or max(status_link, walk_link) > removed:
        problems.append("CLR-LINK: a link at or under the folder is not looked for before the removal")
    if body.count("remove_all(") != 1 or "fs::remove_all(dir)" not in body:
        problems.append("CLR-REMOVE: fs::remove_all does not appear exactly once, on dir")
    if CLEAR_LOG not in body or any(LOG_LEAK.search(c.group(1)) for c in LOG_CALL.finditer(body)):
        problems.append("CLR-LOG: the witness is not the status line alone, or a log call names a path or a serial")

    facade = body_of(strip_comments(bridge_mm), CLEAR_FACADE)
    if facade is None or not re.search(r"dispatch_async\(rpcs3_bridge_queue\(\), \^\{.*Rpcs3IOS_ClearGameCache\(", facade, re.S):
        problems.append("CLR-FACADE: " + CLEAR_FACADE + " does not call Rpcs3IOS_ClearGameCache on the façade queue")
    return problems


def real_inputs():
    return read(ABI_H), abi_text(ABI_DIR), read(BRIDGE_H), facade_text(BRIDGE_DIR)


class TheClearCacheKeepsItsPromises(unittest.TestCase):
    def test_the_real_files_have_no_problems(self):
        self.assertEqual([], source_problems(*real_inputs()))

    def test_an_empty_input_raises(self):
        with tempfile.NamedTemporaryFile("w", suffix=".h") as empty:
            with self.assertRaises(ValueError):
                read(empty.name)


class EachClearCacheCheckCanFail(unittest.TestCase):
    def setUp(self):
        self.header, self.text, self.bridge_h, self.bridge_mm = real_inputs()

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone: " + old)
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def after(self, marker):
        start = self.text.index(marker)
        return self.text[:start], self.text[start:]

    def in_clear(self, old, new):
        head, tail = self.after(CLEAR + "(")
        return head + self.edited(tail, old, new)

    def fails_with(self, prefix, **edits):
        inputs = dict(header=self.header, source=self.text, bridge_h=self.bridge_h, bridge_mm=self.bridge_mm)
        inputs.update(edits)
        found = source_problems(**inputs)
        self.assertTrue(any(p.startswith(prefix) for p in found), (prefix, found))

    def test_a_changed_declaration_fails(self):
        self.fails_with("CLR-DECL", header=self.edited(
            self.header, "Rpcs3IOS_ClearGameCache(const char* serial);", "Rpcs3IOS_ClearGameCache(const char* path);"))

    def test_a_removed_gate_fails(self):
        self.fails_with("CLR-GATE", source=self.in_clear("pthread_main_np() != 0", "false"))

    def test_an_unchecked_serial_fails(self):
        self.fails_with("CLR-COMPONENT", source=self.in_clear("!serial_component_ok(serial)", "serial[0] == '\\0'"))

    def test_a_folder_built_elsewhere_fails(self):
        self.fails_with("CLR-PATH", source=self.in_clear("get_cache_dir_by_serial(serial)", "get_cache_dir() + serial"))

    def test_a_prefix_test_fails(self):
        self.fails_with("CLR-PATH", source=self.in_clear(
            DIR_LINE, DIR_LINE + "\n\tif (!dir.starts_with(\"/\")) return RPCS3_IOS_STATUS_PATH_REJECTED;"))

    def test_a_clear_while_running_fails(self):
        self.fails_with("CLR-STOPPED", source=self.in_clear("!= system_state::stopped", "== system_state::running"))

    def test_an_absent_folder_not_told_apart_fails(self):
        self.fails_with("CLR-ABSENT", source=self.in_clear("file_type::not_found", "file_type::none"))

    def test_a_folder_without_the_marker_removed_fails(self):
        self.fails_with("CLR-MARKER", source=self.in_clear('"ppu-"', '"x-"'))

    def test_a_folder_holding_a_link_removed_fails(self):
        self.fails_with("CLR-LINK", source=self.in_clear("is_symlink(", "is_regular_file("))

    def test_a_second_removal_fails(self):
        self.fails_with("CLR-REMOVE", source=self.in_clear("fs::remove_all(dir)", "fs::remove_all(dir + \"/..\")"))

    def test_a_logged_serial_fails(self):
        self.fails_with("CLR-LOG", source=self.in_clear(DIR_LINE, DIR_LINE + "\n\tstd::fprintf(stderr, \"%s\\n\", serial);"))

    def test_a_facade_off_the_queue_fails(self):
        self.fails_with("CLR-FACADE", bridge_mm=self.edited(self.bridge_mm, "Rpcs3IOS_ClearGameCache(", "Rpcs3IOS_GetState("))


if __name__ == "__main__":
    unittest.main()
