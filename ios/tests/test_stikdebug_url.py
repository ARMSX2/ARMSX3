#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""StikDebugLink in JitNextStep.swift, run on the Mac host, builds the StikDebug relaunch link with the universal script
except when TXM is missing, encodes the bundle ID and names no pid; JitMethod reads stored methods by the pre-main
whitelist. It does not show StikDebug accepting the link."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCE = IOS / "app" / "JIT" / "JitNextStep.swift"
PRESENCE = IOS / "app" / "JIT" / "DeviceChecks.swift"

BUNDLE = "com.armsx3.ARMSX3"
BASE_URL = "stikdebug://enable-jit?bundle-id=" + BUNDLE
SCRIPT = "&script-name=universal.js"
TXMS = ("present", "missing", "unknown")
STORED = (("nil", None, "builtin"), ("empty", "", "builtin"), ("builtin", "builtin", "builtin"),
          ("stikdebug", "stikdebug", "stikdebug"), ("wait", "wait", "wait"), ("Wait", "Wait", "builtin"),
          ("x", "x", "builtin"))


def expected_url(txm):
    return BASE_URL + (SCRIPT if txm != "missing" else "")


def driver():
    lines = ["import Foundation"]
    for txm in TXMS:
        lines.append('print("url {0} \\(StikDebugLink.url(bundleID: "{1}", txm: .{0})?.absoluteString ?? "nil")")'.format(txm, BUNDLE))
    lines.append('print("encoded \\(StikDebugLink.url(bundleID: "a b&c", txm: .missing)?.absoluteString ?? "nil")")')
    for name, raw, _ in STORED:
        value = "nil" if raw is None else '"{0}"'.format(raw)
        lines.append('print("stored {0} \\(JitMethod.stored({1}).rawValue)")'.format(name, value))
    return "\n".join(lines) + "\n"


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def stikdebug_url_problems(out):
    got = {}
    for line in out.splitlines():
        parts = line.split(" ")
        got[" ".join(parts[:-1])] = parts[-1]
    problems = []
    for txm in TXMS:
        key = "url " + txm
        if got.get(key) != expected_url(txm):
            problems.append("url: {0} gave {1!r}, wanted {2!r}".format(key, got.get(key), expected_url(txm)))
        if "pid" in (got.get(key) or ""):
            problems.append("url: {0} names pid (Route R sends none)".format(key))
    if got.get("encoded") != "stikdebug://enable-jit?bundle-id=a%20b%26c":
        problems.append("encoded: bundle `a b&c` gave {0!r}, wanted bundle-id=a%20b%26c".format(got.get("encoded")))
    for name, _, want in STORED:
        if got.get("stored " + name) != want:
            problems.append("stored: {0} gave {1!r}, wanted {2!r}".format(name, got.get("stored " + name), want))
    return problems


def source_problems(text):
    problems = []
    lines = text.splitlines()
    if not lines or "SPDX-License-Identifier: GPL-2.0-only" not in lines[0]:
        problems.append("source: line 1 is not the SPDX GPL-2.0-only line")
    if "Adapted from ARMSX2" in text:
        problems.append("source: the file carries the lifted-file header")
    if "forceScript" in text:
        problems.append("source: the force-script parameter is back")
    imports = set(re.findall(r"^\s*import\s+(\w+)", text, flags=re.MULTILINE))
    if imports != {"Foundation"}:
        problems.append("source: imports {0}, wanted Foundation only".format(sorted(imports)))
    for number, line in enumerate(lines, 1):
        if "pid" in line and "//" not in line:
            problems.append("source: line {0} names pid outside a comment".format(number))
    return problems


def run(source_text):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "JitNextStep.swift"
        path.write_text(source_text, encoding="utf-8")
        return compile_and_run([path, PRESENCE], driver(), tmp)


class RealFiles(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        text = read(SOURCE)
        self.assertEqual(source_problems(text), [])
        out = run(text)
        sys.stderr.write("\n" + out)  # stderr, so the six URLs land before unittest's summary
        self.assertEqual(stikdebug_url_problems(out), [])

    def test_control_the_expected_table(self):
        self.assertEqual([expected_url(t) for t in TXMS].count(BASE_URL), 1)
        self.assertEqual(expected_url("missing"), BASE_URL)

    def test_edge_missing_or_empty_input_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(FileNotFoundError):
                read(Path(tmp) / "absent.swift")


class Mutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(SOURCE)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor moved")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def assert_caught(self, old, new, prefix):
        problems = stikdebug_url_problems(run(self.edited(self.text, old, new)))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_pid_added_to_the_query(self):
        self.assert_caught('URLQueryItem(name: "bundle-id", value: bundleID)',
                           'URLQueryItem(name: "bundle-id", value: bundleID), URLQueryItem(name: "p" + "id", value: "1")', "url:")

    def test_mutation_the_script_omitted_for_unknown(self):
        self.assert_caught("if txm != .missing {", "if txm == .present {", "url:")

    def test_mutation_query_hand_concatenated(self):
        self.assert_caught("return components.url",
                           'return URL(string: "stikdebug://enable-jit?" + items.map { "\\($0.name)=\\($0.value ?? "")" }.joined(separator: "&"))',
                           "encoded:")

    def test_mutation_the_stored_reader_is_case_insensitive(self):
        self.assert_caught("JitMethod(rawValue: raw ?? \"\")", "JitMethod(rawValue: (raw ?? \"\").lowercased())", "stored:")

    def test_mutation_pid_named_in_code(self):
        text = self.edited(self.text, "enum StikDebugLink {", "enum StikDebugLink {\n    static let pid = 0")
        self.assertTrue(any(p.startswith("source:") for p in source_problems(text)))

    def test_mutation_the_force_script_parameter_back(self):
        text = self.edited(self.text, "txm: Presence) -> URL? {", "txm: Presence, forceScript: Bool = false) -> URL? {")
        self.assertTrue(any(p.startswith("source:") for p in source_problems(text)))

    def test_mutation_the_lifted_header_added(self):
        text = self.edited(self.text, "// SPDX-License-Identifier: GPL-2.0-only\n",
                           "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n")
        self.assertTrue(any(p.startswith("source:") for p in source_problems(text)))


if __name__ == "__main__":
    unittest.main()
