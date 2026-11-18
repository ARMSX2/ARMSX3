#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""Checks, by reading source and Info.plist, how a pairing file reaches ARMSX3 from outside the pane: the shared file is
read under scoped access and never deleted or moved, the origin is written beside the installed file, the root routes the
SideStore link and shared files before DeepLink.parse without ever naming the URL in a message, and Info.plist claims the
pairing types as an alternate handler. It does not run the app."""

import plistlib
import re
import unittest
from pathlib import Path

from source_helpers import brace_body, without_comments

IOS = Path(__file__).resolve().parents[1]
IMPORT = IOS / "app" / "JIT" / "PairingImport.swift"
ROOT = IOS / "app" / "Shell" / "ARMSX3FrontendRoot.swift"
PLIST = IOS / "app" / "Resources" / "Info.plist"

PAIRING_TYPE = "org.sidestore.mobiledevicepairing"
PAD_TYPE = "com.armsx3.padlayout"
PAD_EXPORT = {
    "UTTypeIdentifier": PAD_TYPE,
    "UTTypeDescription": "ARMSX3 Touch Layout",
    "UTTypeConformsTo": ["public.json"],
    "UTTypeTagSpecification": {"public.filename-extension": ["armsx3pad"]},
}
PAD_CLAIM = {
    "CFBundleTypeName": "ARMSX3 Touch Layout",
    "CFBundleTypeRole": "Viewer",
    "LSHandlerRank": "Owner",
    "LSItemContentTypes": [PAD_TYPE],
}
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def function_body(code, signature):
    at = code.find(signature)
    return brace_body(code, at) if at >= 0 else ""


def import_problems(text):
    code = without_comments(text)
    problems = []
    shared = function_body(code, "static func readShared")
    if "startAccessingSecurityScopedResource" not in shared:
        problems.append("scope: readShared does not start security-scoped access")
    if re.search(r"removeItem|moveItem|trashItem", shared):
        problems.append("delete: readShared deletes or moves the sender's file")
    if "65_536" not in shared and "65536" not in shared and "maxSharedBytes" not in shared:
        problems.append("cap: readShared has no size cap")
    install = function_body(code, "static func install(data:")
    if "PairingSource.originName" not in install:
        problems.append("origin: install(data:) does not write PairingSource.originName")
    if "org.sidestore.mobiledevicepairing" not in code:
        problems.append("picker: the picker does not accept .mobiledevicepairing files")
    return problems


def root_problems(text):
    code = without_comments(text)
    receive = function_body(code, "private func receive")
    problems = []
    parse_at = receive.find("DeepLink.parse")
    link_at = receive.find("PairingLink.isPairingLink")
    take_at = receive.find("PairingLink.take")
    file_at = receive.find("readShared")
    if parse_at < 0 or link_at < 0 or take_at < 0 or file_at < 0:
        problems.append("order: receive lacks the link branch, take, the file branch or DeepLink.parse")
        return problems
    if not link_at < take_at < parse_at:
        problems.append("order: the pairing link is not checked and taken before DeepLink.parse")
    if file_at > parse_at:
        problems.append("order: the shared file branch comes after DeepLink.parse")
    first = re.search(r"\{\s*(\S[^\n]*)", receive)
    if not first or "PairingLink.isPairingLink" not in first.group(1):
        problems.append("first: the pairing link is not the first branch of receive")
    if LOGGING.search(receive) or re.search(r"\\\(\s*url\b", receive) or "url.absoluteString" in receive:
        problems.append("silent: receive logs or interpolates the URL")
    if "install(data:" not in code:
        problems.append("install: the root never calls the bytes-based install")
    return problems


def plist_problems(data):
    problems = []
    schemes = data.get("LSApplicationQueriesSchemes", [])
    if "stikdebug" not in schemes or "sidestore" not in schemes:
        problems.append("schemes: LSApplicationQueriesSchemes must hold stikdebug and sidestore")
    imported = [t for t in data.get("UTImportedTypeDeclarations", []) if t.get("UTTypeIdentifier") == PAIRING_TYPE]
    if len(imported) != 1:
        problems.append("imported: {0} is not declared exactly once".format(PAIRING_TYPE))
    else:
        declared = imported[0]
        extensions = declared.get("UTTypeTagSpecification", {}).get("public.filename-extension", [])
        if "com.apple.property-list" not in declared.get("UTTypeConformsTo", []):
            problems.append("imported: the pairing type does not conform to com.apple.property-list")
        if sorted(extensions) != ["mobiledevicepair", "mobiledevicepairing"]:
            problems.append("imported: the pairing type must list mobiledevicepairing and mobiledevicepair")
    claims = [d for d in data.get("CFBundleDocumentTypes", []) if PAIRING_TYPE in d.get("LSItemContentTypes", [])]
    if len(claims) != 1:
        problems.append("claim: one document type must list the pairing type")
    else:
        if claims[0].get("LSHandlerRank") != "Alternate":
            problems.append("rank: the pairing document type must be Alternate, never Owner")
        if "com.apple.property-list" not in claims[0].get("LSItemContentTypes", []):
            problems.append("claim: the pairing document type must list com.apple.property-list")
    pads = [d for d in data.get("CFBundleDocumentTypes", []) if PAD_TYPE in d.get("LSItemContentTypes", [])]
    if pads != [PAD_CLAIM]:
        problems.append("pad: the touch layout document type changed")
    exported = data.get("UTExportedTypeDeclarations", [])
    if exported != [PAD_EXPORT]:
        problems.append("pad: the touch layout exported type changed")
    return problems


def names(problems, check):
    return any(p.startswith(check + ":") for p in problems)


class ThePairingImporterIsSafe(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], import_problems(read(IMPORT)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(IMPORT.with_name("NoSuchFile.swift"))


class TheRootRoutesArrivalsBeforeDeepLinks(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], root_problems(read(ROOT)))


class ThePlistClaimsThePairingTypes(unittest.TestCase):
    def test_the_real_plist_has_no_problem(self):
        with PLIST.open("rb") as handle:
            self.assertEqual([], plist_problems(plistlib.load(handle)))


class TheGuardsFailOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.importer = read(IMPORT)
        cls.root = read(ROOT)
        with PLIST.open("rb") as handle:
            cls.plist = plistlib.load(handle)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def test_mutation_shared_read_without_scope(self):
        text = self.edited(self.importer, "url.startAccessingSecurityScopedResource()", "false")
        self.assertTrue(names(import_problems(text), "scope"))

    def test_mutation_shared_read_deletes_the_senders_file(self):
        text = self.edited(self.importer, "return try? Data(contentsOf: url)", "defer { try? FileManager.default.removeItem(at: url) }\n        return try? Data(contentsOf: url)")
        self.assertTrue(names(import_problems(text), "delete"))

    def test_mutation_shared_read_moves_the_senders_file(self):
        text = self.edited(self.importer, "return try? Data(contentsOf: url)", "try? FileManager.default.moveItem(at: url, to: url)\n        return try? Data(contentsOf: url)")
        self.assertTrue(names(import_problems(text), "delete"))

    def test_mutation_install_forgets_the_origin(self):
        text = self.edited(self.importer, "PairingSource.originName", "\"pairingFile.origin\"")
        self.assertTrue(names(import_problems(text), "origin"))

    def test_mutation_picker_drops_the_pairing_type(self):
        text = self.edited(self.importer, PAIRING_TYPE, "public.data")
        self.assertTrue(names(import_problems(text), "picker"))

    def test_mutation_file_branch_after_deep_link_parse(self):
        text = self.edited(self.root, "PairingImport.readShared", "DeepLink.parse(url) == nil ? nil : PairingImport.readShared")
        self.assertTrue(names(root_problems(text), "order"))

    def test_mutation_link_branch_not_first(self):
        text = self.edited(self.root, "private func receive(_ url: URL) {", "private func receive(_ url: URL) {\n        if url.isFileURL && url.pathExtension == \"x\" { return }")
        self.assertTrue(names(root_problems(text), "first"))

    def test_mutation_receive_logs_the_url(self):
        text = self.edited(self.root, "private func receive(_ url: URL) {", "private func receive(_ url: URL) {\n        print(url)")
        self.assertTrue(names(root_problems(text), "silent"))

    def test_mutation_receive_interpolates_the_url(self):
        text = self.edited(self.root, "private func receive(_ url: URL) {", "private func receive(_ url: URL) {\n        linkMessage = \"\\(url)\"")
        self.assertTrue(names(root_problems(text), "silent"))

    def test_mutation_plist_drops_the_sidestore_scheme(self):
        data = dict(self.plist, LSApplicationQueriesSchemes=["stikdebug"])
        self.assertTrue(names(plist_problems(data), "schemes"))

    def test_mutation_plist_owns_the_pairing_type(self):
        claims = [dict(d, LSHandlerRank="Owner") if PAIRING_TYPE in d.get("LSItemContentTypes", []) else d
                  for d in self.plist["CFBundleDocumentTypes"]]
        self.assertTrue(names(plist_problems(dict(self.plist, CFBundleDocumentTypes=claims)), "rank"))

    def test_mutation_plist_drops_the_imported_type(self):
        data = {k: v for k, v in self.plist.items() if k != "UTImportedTypeDeclarations"}
        self.assertTrue(names(plist_problems(data), "imported"))

    def test_mutation_plist_changes_the_pad_layout_entry(self):
        exported = [dict(self.plist["UTExportedTypeDeclarations"][0], UTTypeIdentifier="com.armsx3.other")]
        self.assertTrue(names(plist_problems(dict(self.plist, UTExportedTypeDeclarations=exported)), "pad"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
