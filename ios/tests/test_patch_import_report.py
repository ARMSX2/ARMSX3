#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""Importing a patch file reports how many patches went in and can replace or remove the stored ones:
checks over the ABI, the facade, the report wording, the strings and the problem report staging."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import abi_text, brace_body, facade_text, function_body, without_comments


ROOT = Path(__file__).resolve().parents[2]

ABI_H = ROOT / "ios" / "bridge" / "rpcs3_ios_abi.h"

ABI_DIR = ROOT / "ios" / "bridge"

BRIDGE_DIR = ROOT / "ios" / "app" / "Bridge"

BRIDGE_H = BRIDGE_DIR / "Rpcs3Bridge.h"

IMPORT = "Rpcs3IOS_PatchesImport"

REMOVE = "Rpcs3IOS_PatchesRemoveImported"

SETTINGS = ROOT / "ios" / "app" / "Settings"

REPORT = SETTINGS / "PatchImportReport.swift"

SHEET = SETTINGS / "PatchesView.swift"

XMB = ROOT / "ios" / "app" / "Xmb" / "XmbUtilitiesLists.swift"

CATALOG = ROOT / "ios" / "app" / "Resources" / "Localizable.xcstrings"

DIAGNOSTICS = ROOT / "ios" / "app" / "Diagnostics" / "DiagnosticsStore.swift"


def read(path):
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise ValueError("%s is empty" % path)
    return text


def squash(text):
    return " ".join(text.split())


def gate(name):
    return re.compile(r"\s*if \(pthread_main_np\(\) != 0\)\s*\{\s*return refuse_main_thread\(\"%s\"\);\s*\}" % name)


class AbiSource(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header = squash(without_comments(read(ABI_H)))
        code = without_comments(abi_text(ABI_DIR))
        cls.imported = function_body(code, "Rpcs3IOS_Status " + IMPORT)
        cls.removed = function_body(code, "Rpcs3IOS_Status " + REMOVE)

    def test_header_declares_both_calls(self):
        self.assertIn("Rpcs3IOS_Status Rpcs3IOS_PatchesImport(const char* yaml, int32_t replace, uint32_t* imported, uint32_t* total);",
                      self.header)
        self.assertIn("Rpcs3IOS_Status Rpcs3IOS_PatchesRemoveImported(void);", self.header)

    def test_both_calls_refuse_the_main_thread_first(self):
        self.assertIsNotNone(self.imported, "no definition of " + IMPORT)
        self.assertIsNotNone(self.removed, "no definition of " + REMOVE)
        self.assertTrue(gate(IMPORT).match(self.imported), "the import's first statement is not the gate")
        self.assertTrue(gate(REMOVE).match(self.removed), "the remove's first statement is not the gate")

    def test_import_zeroes_counts_and_replaces_in_one_save(self):
        body = self.imported
        self.assertIsNotNone(body, "no definition of " + IMPORT)
        parse = body.find("parse_patches(")
        for clear in ("*imported = 0", "*total = 0"):
            at = body.find(clear)
            self.assertTrue(0 <= at < parse, clear + " does not come before parse_patches")
        self.assertIn("RPCS3_IOS_STATUS_NULL_ARGUMENT", body)
        self.assertIn("imported == nullptr", body)
        self.assertIn("total == nullptr", body)
        self.assertIn("import_patches(", body)
        self.assertIn("patch_engine::load(", body)
        self.assertEqual(body.count("save_patches("), 1)

    def test_remove_touches_only_the_imported_file(self):
        body = self.removed
        self.assertIsNotNone(body, "no definition of " + REMOVE)
        self.assertIn("get_imported_patch_path()", body)
        self.assertIn("remove_file(", body)
        for other in ("patch_config", "get_patches_path", "get_patch_config_path", '"patch.yml"', "artemis",
                      "armsx3_patch", "remove_all"):
            self.assertNotIn(other, body)

    def test_every_patch_write_invalidates_the_cached_list(self):
        code = without_comments(abi_text(ABI_DIR))
        cache = function_body(code, "patch_engine::patch_map cached_patch_db")
        self.assertIsNotNone(cache, "no definition of cached_patch_db")
        self.assertIn("patch_writes.load()", cache)
        self.assertIn("writes != cached_writes", cache)
        for name in ("Rpcs3IOS_PatchesWrite", IMPORT, REMOVE):
            body = function_body(code, "Rpcs3IOS_Status " + name)
            self.assertIsNotNone(body, "no definition of " + name)
            ok = body.rfind('finished_status("%s", RPCS3_IOS_STATUS_OK)' % name)
            bump = body.rfind("patch_writes++")
            self.assertTrue(0 <= bump < ok, name + " does not bump patch_writes before it returns OK")


class FacadeSource(unittest.TestCase):
    def test_header_declares_the_facade_methods(self):
        header = squash(without_comments(read(BRIDGE_H)))
        self.assertIn("+ (void)importPatches:(NSString*)yaml replace:(BOOL)replace "
                      "completion:(void (^)(NSInteger status, NSInteger imported, NSInteger total))completion;", header)
        self.assertIn("+ (void)removeImportedPatchesWithCompletion:(void (^)(NSInteger status))completion;", header)

    def test_implementation_runs_on_the_bridge_queue_then_the_main_queue(self):
        code = without_comments(facade_text(BRIDGE_DIR))
        imported = brace_body(code, code.find("+ (void)importPatches:(NSString*)yaml replace:"))
        removed = brace_body(code, code.find("+ (void)removeImportedPatchesWithCompletion:"))
        for name, body in (("import", imported), ("remove", removed)):
            self.assertTrue(body, "no facade definition of the " + name)
            self.assertTrue(0 <= body.find("rpcs3_bridge_queue()") < body.find("dispatch_get_main_queue()"), name)
        self.assertRegex(imported, r"Rpcs3IOS_PatchesImport\([^;]*&imported, &total\)")
        self.assertIn("Rpcs3IOS_PatchesRemoveImported()", removed)


CASES = [
    ((0, 12, 12), "Patches imported: 12 of 12.|false|false"),
    ((0, 3, 12), "Patches imported: 3 of 12.|true|false"),
    ((0, 0, 12), "None of the 12 patches in this file were imported.|true|true"),
    ((0, 0, 0), "That file has no patches in it; nothing changed.|false|true"),
    ((-24, 0, 0), "That file isn't a patch file this version can read; nothing changed.|false|true"),
    ((-25, 0, 0), "The imported patches couldn't be updated; nothing changed.|false|true"),
]

QUESTION = "Patches with the same names are already stored. Replace them with the versions in this file?"

NEW_KEYS = [
    "That file has no patches in it; nothing changed.",
    "The imported patches couldn't be updated; nothing changed.",
    "Patches imported: %lld of %lld.",
    "None of the %lld patches in this file were imported.",
    QUESTION,
    "Replace them",
    "Remove imported patches",
    "Remove the imported patches?",
    "This deletes the patches you imported from files, for every game. Downloaded patches and which patches are switched on stay.",
    "Imported patches removed.",
    "The imported patches couldn't be removed.",
]


class ReportWording(unittest.TestCase):
    def test_the_report_maps_each_outcome(self):
        driver = "import Foundation\n"
        for (status, imported, total), _ in CASES:
            driver += ("do { let r = PatchImportReport.result(status: %d, imported: %d, total: %d)\n"
                       "print(\"\\(r.message)|\\(r.canReplace)|\\(r.failed)\") }\n") % (status, imported, total)
        driver += "print(PatchImportReport.replaceQuestion)\n"
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([REPORT], driver, tmp).splitlines()
        self.assertEqual(out, [line for _, line in CASES] + [QUESTION])


class Strings(unittest.TestCase):
    def test_the_catalog_has_the_new_keys_and_not_the_old_one(self):
        strings = json.loads(read(CATALOG))["strings"]
        for key in NEW_KEYS:
            self.assertIn(key, strings)
        plural = strings["None of the %lld patches in this file were imported."]["localizations"]["en"]["variations"]["plural"]
        self.assertEqual(sorted(plural), ["one", "other"])
        self.assertNotIn("Patches imported.", strings)


class Callers(unittest.TestCase):
    def test_every_import_call_names_replace(self):
        for path in (ROOT / "ios" / "app").rglob("*.swift"):
            for call in re.findall(r"Rpcs3Bridge\.importPatches\([^)]*\)", read(path)):
                self.assertIn("replace:", call, "%s calls importPatches without replace:" % path.name)

    def test_both_import_paths_use_the_report(self):
        self.assertIn("PatchImportReport", read(SHEET))
        self.assertIn("PatchImportReport", read(XMB))


class ReportStaging(unittest.TestCase):
    def test_the_report_stages_both_patch_files_from_the_core_folder(self):
        code = without_comments(read(DIAGNOSTICS))
        for name in ("patches/imported_patch.yml", "patch_config.yml"):
            self.assertRegex(code, r'stage\(rpcs3Dir\.appendingPathComponent\("%s"\), as: "%s", in: staging\)' % (name, name))
        self.assertNotIn("copyItem(", code)


if __name__ == "__main__":
    unittest.main()
