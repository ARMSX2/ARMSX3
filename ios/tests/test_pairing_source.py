#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PairingSource.swift, run on the Mac host with PairingFileCheck.swift and DeviceChecks.swift, turns the launcher's
report words into the Device card's Pairing File row, the pane's row value and the problem report's lines, names a
file's format and finds SideStore's file in place; it imports Foundation only and never logs. It does not run the launcher or read a real pairing file."""

import json
import os
import plistlib
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
SOURCE = IOS / "app" / "JIT" / "PairingSource.swift"
SOURCES = [SOURCE, IOS / "app" / "JIT" / "PairingFileCheck.swift", IOS / "app" / "JIT" / "DeviceChecks.swift"]

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")

OLD = "Your imported file was rejected, so this one was used."
REJECTED = "This device rejected it. Make a new pairing file on a computer and add it."

# name: (report, ownFileFound, origin, file date, expected "value|presence|problem|fix", or "nil")
ROWS = {
    "own-documents": ({"used": "own", "format": "rp", "result": "accepted"}, True, "documents", "2026-10-12",
                      "Accepted|present|false|Documents folder, Remote Pairing, created 12 Oct."),
    "side-rejected": ({"used": "sidestore-lc-rp", "format": "rp", "result": "rejected"}, False, "imported", "2026-10-12",
                      "Rejected|missing|false|SideStore's (LiveContainer), Remote Pairing, created 12 Oct. " + REJECTED),
    "ladder": ({"used": "sidestore-lc-alt", "format": "rp-lockdown", "result": "accepted", "rejected": "own", "tried": "2"},
               True, "imported", "2026-10-12",
               "Accepted|present|false|SideStore's (LiveContainer), Remote Pairing with lockdown keys, created 12 Oct. " + OLD),
    "own-rejected-alone": ({"used": "own", "format": "rp", "result": "rejected", "rejected": "own", "tried": "1"},
                           True, "shared", "", "Rejected|missing|false|Shared to ARMSX3, Remote Pairing. " + REJECTED),
    "empty-no-file": ({}, False, "imported", "2026-10-12", "nil"),
    "empty-own-file": ({}, True, "imported", "2026-10-12", "Not tried yet|none|false|Imported, created 12 Oct."),
    "other-year": ({"used": "own", "format": "rp", "result": "accepted"}, True, "sidestore-link", "2025-03-05",
                   "Accepted|present|false|From SideStore, Remote Pairing, created 5 Mar 2025."),
    "unknown-format": ({"used": "own", "format": "?", "result": "accepted"}, True, "imported", "",
                       "Accepted|present|false|Imported."),
}

# name: (ownFileFound, sideStoreInPlace, origin, awaiting, launch report, expected)
PANES = {
    "awaiting": (True, True, "imported", True, {}, "Waiting for SideStore…"),
    "imported": (True, False, "imported", False, {}, "Imported"),
    "shared": (True, False, "shared", False, {}, "Imported"),
    "link": (True, False, "sidestore-link", False, {}, "From SideStore"),
    "documents": (True, True, "documents", False, {}, "Documents folder"),
    "sidestore": (False, True, "imported", False, {}, "SideStore's"),
    "missing": (False, False, "imported", False, {}, "Missing"),
    "accepted": (True, False, "imported", False, {"used": "own", "result": "accepted"}, "Imported"),
    "rejected-own": (True, False, "imported", False, {"used": "own", "result": "rejected", "rejected": "own"}, "Rejected"),
    "rejected-sidestore": (False, True, "imported", False, {"used": "sidestore-lc-rp", "result": "rejected"}, "Rejected"),
    "rejected-no-file": (False, False, "imported", False, {"used": "own", "result": "rejected"}, "Missing"),
    "rejected-while-waiting": (True, False, "imported", True, {"used": "own", "result": "rejected"}, "Waiting for SideStore…"),
    "ladder-fell-through": (True, True, "imported", False, {"used": "sidestore-lc-rp", "result": "accepted", "rejected": "own", "tried": "2"},
                            "SideStore's"),
    "ladder-both-rejected": (True, True, "imported", False, {"used": "sidestore-lc-alt", "result": "rejected", "rejected": "own", "tried": "2"},
                             "Rejected"),
}

REPORTS = {
    "side-accepted": ({"used": "sidestore-lc-rp", "format": "rp", "result": "accepted"}, True, "imported", "2026-10-12",
                      "SideStore's (LiveContainer), Remote Pairing, created 12 Oct, accepted"),
    "own-other-year": ({"used": "own", "format": "rp-lockdown", "result": "rejected"}, True, "documents", "2025-03-05",
                       "documents folder, Remote Pairing with lockdown keys, created 5 Mar 2025, rejected"),
    "own-untried": ({}, True, "imported", "", "imported, not tried"),
    "none": ({}, False, "imported", "", "none"),
}

LADDERS = {
    "one": ({"used": "own", "result": "accepted", "tried": "1"}, "nil"),
    "none": ({}, "nil"),
    "two": ({"used": "sidestore-lc-rp", "result": "accepted", "rejected": "own", "tried": "2"},
            "imported rejected, SideStore's (LiveContainer) accepted"),
    "two-bad": ({"used": "sidestore-lc-alt", "result": "rejected", "rejected": "own", "tried": "2"},
                "imported rejected, SideStore's (LiveContainer) rejected"),
}

WORDS = {
    ("own", "imported"): "Imported",
    ("own", "shared"): "Shared to ARMSX3",
    ("own", "sidestore-link"): "From SideStore",
    ("own", "documents"): "Documents folder",
    ("sidestore-lc-rp", "imported"): "SideStore's (LiveContainer)",
    ("sidestore-lc-alt", "documents"): "SideStore's (LiveContainer)",
}

ORIGINS = [("documents", "documents"), (" shared\\n", "shared"), ("sidestore-link", "sidestore-link"),
           ("imported", "imported"), ("nonsense", "imported"), ("", "imported")]

FORMATS = {"rp": "Remote Pairing", "rp-lockdown": "Remote Pairing with lockdown keys", "": "nil", "x": "nil"}


def dictionary(report):
    return "[" + ", ".join('"{0}": "{1}"'.format(k, v) for k, v in report.items()) + "]" if report else "[:]"


def date(day):
    return 'ISO8601DateFormatter().date(from: "{0}T12:00:00Z")'.format(day) if day else "nil"


def origin_case(name):
    return ".sidestoreLink" if name == "sidestore-link" else "." + name


def flag(value):
    return "true" if value else "false"


def show(key, expression):
    return 'print("{0}\\t" + ({1}))'.format(key, expression)


def driver():
    lines = [
        "import Foundation",
        "var calendar = Calendar(identifier: .gregorian)",
        'calendar.timeZone = TimeZone(identifier: "UTC")!',
        'let now = ISO8601DateFormatter().date(from: "2026-11-10T12:00:00Z")!',
        'let gb = Locale(identifier: "en_GB")',
    ]
    for name, (report, own, origin, day, _) in ROWS.items():
        call = "PairingSource.deviceRow(report: {0}, ownFileFound: {1}, origin: {2}, fileDate: {3}, now: now, locale: gb, calendar: calendar)".format(
            dictionary(report), flag(own), origin_case(origin), date(day))
        lines.append(show("row " + name, '{0}.map {{ "\\($0.value)|\\($0.presence.map {{ "\\($0)" }} ?? "none")|\\($0.isProblem)|\\($0.fix ?? "nofix")" }} ?? "nil"'.format(call)))
    for name, (own, side, origin, waiting, report, _) in PANES.items():
        lines.append(show("pane " + name, "PairingSource.paneValue(ownFileFound: {0}, sideStoreInPlace: {1}, origin: {2}, awaitingSideStore: {3}, report: {4})".format(
            flag(own), flag(side), origin_case(origin), flag(waiting), dictionary(report))))
        lines.append(show("pane-rejected " + name, '"\\(PairingSource.paneRejected(report: {0}, fileFound: {1}))"'.format(
            dictionary(report), flag(own or side))))
    for name, (report, own, origin, day, _) in REPORTS.items():
        lines.append(show("report " + name, "PairingSource.reportLine(report: {0}, ownFileFound: {1}, origin: {2}, fileDate: {3}, now: now, calendar: calendar)".format(
            dictionary(report), flag(own), origin_case(origin), date(day))))
    for name, (report, _) in LADDERS.items():
        lines.append(show("ladder " + name, 'PairingSource.ladderLine(report: {0}) ?? "nil"'.format(dictionary(report))))
    for used, origin in WORDS:
        lines.append(show("word {0}/{1}".format(used, origin), 'PairingSource.sourceWord(used: "{0}", origin: {1})'.format(used, origin_case(origin))))
    for index, (text, _) in enumerate(ORIGINS):
        lines.append(show("origin {0}".format(index), 'PairingSource.origin("{0}").rawValue'.format(text)))
    lines.append(show("origin nil", "PairingSource.origin(nil).rawValue"))
    for text in FORMATS:
        lines.append(show("format " + text, 'PairingSource.formatWord("{0}") ?? "nil"'.format(text)))
    return "\n".join(lines) + "\n"


def expected():
    want = {}
    for name, entry in ROWS.items():
        want["row " + name] = entry[-1]
    for name, entry in PANES.items():
        want["pane " + name] = entry[-1]
        want["pane-rejected " + name] = "true" if entry[4].get("result") == "rejected" and (entry[0] or entry[1]) else "false"
    for name, entry in REPORTS.items():
        want["report " + name] = entry[-1]
    for name, entry in LADDERS.items():
        want["ladder " + name] = entry[-1]
    for (used, origin), text in WORDS.items():
        want["word {0}/{1}".format(used, origin)] = text
    for index, (_, origin) in enumerate(ORIGINS):
        want["origin {0}".format(index)] = origin
    want["origin nil"] = "imported"
    for text, word in FORMATS.items():
        want["format " + text] = word
    return want


def run(text=None):
    with tempfile.TemporaryDirectory() as tmp:
        sources = list(SOURCES)
        if text is not None:
            sources[0] = Path(tmp) / "PairingSource.swift"
            sources[0].write_text(text, encoding="utf-8")
        out = compile_and_run(sources, driver(), tmp)
    return dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)


def source_problems(text):
    problems = []
    code = without_comments(text)
    if "SPDX-License-Identifier: GPL-2.0-only" not in text.splitlines()[0]:
        problems.append("source: line 1 is not the SPDX GPL-2.0-only line")
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: PairingSource.swift imports {0}; it must import Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: PairingSource.swift logs; the pairing file is never written to a log")
    if code.count("String(localized:") < 12:
        problems.append("words: fewer than 12 localized literals")
    return problems


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


class TheWordsAndRows(unittest.TestCase):
    def test_every_case(self):
        got = run()
        for key, want in expected().items():
            self.assertEqual(want, got.get(key), key)


RP_NAME = "PairingFile_RemoteRP.plist"
ALT_NAME = "ALTPairingFile.mobiledevicepairing"


def rp(**extra):
    return plistlib.dumps(dict({"public_key": os.urandom(32), "private_key": os.urandom(32), "identifier": "synthetic-host"}, **extra))


LOCKDOWN_ONLY = plistlib.dumps({"HostCertificate": b"synthetic", "HostPrivateKey": b"synthetic", "SystemBUID": "SYNTHETIC-BUID"})

# name: (files in SideStore's folder, expected file name or "nil")
FINDER = {
    "both": ({RP_NAME: rp(), ALT_NAME: rp()}, RP_NAME),
    "merged-first": ({RP_NAME: rp(HostCertificate=b"synthetic"), ALT_NAME: rp()}, RP_NAME),
    "lockdown-only-skipped": ({RP_NAME: LOCKDOWN_ONLY, ALT_NAME: rp()}, ALT_NAME),
    "garbage-skipped": ({RP_NAME: os.urandom(40), ALT_NAME: rp()}, ALT_NAME),
    "only-alt": ({ALT_NAME: rp()}, ALT_NAME),
    "only-lockdown": ({RP_NAME: LOCKDOWN_ONLY}, "nil"),
    "empty-folder": ({}, "nil"),
}

FORMAT_OF = {"rp": (rp(), "rp"), "merged": (rp(SystemBUID="SYNTHETIC-BUID"), "rp-lockdown"),
             "lockdown": (LOCKDOWN_ONLY, ""), "garbage": (os.urandom(40), ""), "empty": (b"", "")}

FINDER_DRIVER = r'''
import Foundation
let root = URL(fileURLWithPath: "@@ROOT@@")
func show(_ key: String, _ value: String) { print(key + "\t" + value) }
for name in @@NAMES@@ {
    let home = root.appendingPathComponent(name).path
    let found = PairingSource.sideStoreFile(liveContainerHome: home, read: { try? Data(contentsOf: $0) })
    show("find " + name, found?.lastPathComponent ?? "nil")
}
show("find no home", PairingSource.sideStoreFile(liveContainerHome: nil, read: { _ in Data() })?.lastPathComponent ?? "nil")
show("find empty home", PairingSource.sideStoreFile(liveContainerHome: "", read: { _ in Data() })?.lastPathComponent ?? "nil")
for name in @@FORMATS@@ {
    let data = try! Data(contentsOf: root.appendingPathComponent("format-" + name))
    show("format-of " + name, PairingSource.format(of: data))
}
show("names", PairingSource.sideStoreNames.joined(separator: ","))
'''


class TheSideStoreFinderAndFormat(unittest.TestCase):
    def test_finder_and_format_of(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as fixtures:
            for name, (files, _) in FINDER.items():
                folder = Path(fixtures, name, "Documents", "SideStore", "Documents")
                folder.mkdir(parents=True)
                for file_name, data in files.items():
                    (folder / file_name).write_bytes(data)
            for name, (data, _) in FORMAT_OF.items():
                Path(fixtures, "format-" + name).write_bytes(data)
            swift = (FINDER_DRIVER.replace("@@ROOT@@", fixtures).replace("@@NAMES@@", json.dumps(list(FINDER)))
                     .replace("@@FORMATS@@", json.dumps(list(FORMAT_OF))))
            out = compile_and_run(SOURCES, swift, tmp)
        got = dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)
        for name, (_, want) in FINDER.items():
            self.assertEqual(want, got.get("find " + name), name)
        self.assertEqual("nil", got.get("find no home"))
        self.assertEqual("nil", got.get("find empty home"))
        for name, (_, want) in FORMAT_OF.items():
            self.assertEqual(want, got.get("format-of " + name), name)
        self.assertEqual(RP_NAME + "," + ALT_NAME, got.get("names"))


class TheFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], source_problems(read(SOURCE)))

    def test_mutation_logging_is_caught(self):
        text = read(SOURCE) + "\nfunc probe(_ s: String) { print(s) }\n"
        self.assertTrue(any(p.startswith("log:") for p in source_problems(text)))

    def test_mutation_uikit_is_caught(self):
        text = read(SOURCE).replace("import Foundation", "import UIKit", 1)
        self.assertTrue(any(p.startswith("host:") for p in source_problems(text)))

    def test_mutation_a_wrong_rejected_word_is_caught(self):
        anchor = 'String(localized: "Rejected")'
        text = read(SOURCE)
        self.assertIn(anchor, text)
        got = run(text.replace(anchor, 'String(localized: "Failed")'))
        self.assertNotEqual(expected()["row side-rejected"], got["row side-rejected"])


class ThePaneRowSaysRejected(unittest.TestCase):
    def test_mutation_a_rejected_flag_that_never_trips_is_caught(self):
        anchor = 'fileFound && report["result"] == "rejected"'
        text = read(SOURCE)
        self.assertIn(anchor, text)
        got = run(text.replace(anchor, "false", 1))
        self.assertNotEqual(expected()["pane-rejected rejected-own"], got["pane-rejected rejected-own"])
        self.assertNotEqual(expected()["pane rejected-own"], got["pane rejected-own"])

    def test_mutation_the_ladder_still_saying_imported_is_caught(self):
        anchor = 'report["rejected"] == "own", inSideStore(report["used"] ?? "")'
        text = read(SOURCE)
        self.assertIn(anchor, text)
        got = run(text.replace(anchor, "false", 1))
        self.assertNotEqual(expected()["pane ladder-fell-through"], got["pane ladder-fell-through"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
