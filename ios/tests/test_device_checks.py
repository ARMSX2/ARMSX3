#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""DeviceChecks.swift, run alone on the Mac host, builds the Device card's rows, values and fix sentences in the fixed
order, refuses an incomplete checks dictionary and formats memory sizes; it imports Foundation only.
It says nothing about what csops, vminfo or IORegistry answer on a device."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCE = IOS / "app" / "JIT" / "DeviceChecks.swift"

KEYS = ("getTaskAllow", "increasedMemoryLimit", "extendedVirtualAddressing", "txm", "memoryLimitBytes", "liveContainer")
BASE = {"getTaskAllow": 1, "increasedMemoryLimit": 1, "extendedVirtualAddressing": 1, "txm": 1,
        "memoryLimitBytes": 6442450944, "liveContainer": 0}
MISSING = object()


def case(**changes):
    checks = dict(BASE)
    for key, value in changes.items():
        if value is MISSING:
            del checks[key]
        else:
            checks[key] = value
    return checks


CASES = {
    "all": case(),
    "lc": case(liveContainer=1),
    "gta0": case(getTaskAllow=0),
    "gtau": case(getTaskAllow=-1),
    "gta0lc": case(getTaskAllow=0, liveContainer=1),
    "iml0": case(increasedMemoryLimit=0),
    "imlu": case(increasedMemoryLimit=-1),
    "iml0lc": case(increasedMemoryLimit=0, liveContainer=1),
    "txm0": case(txm=0),
    "txmu": case(txm=-1),
    "eva0": case(extendedVirtualAddressing=0),
    "evau": case(extendedVirtualAddressing=-1),
    "memu": case(memoryLimitBytes=-1),
    "notxm": case(txm=MISSING),
    "nolc": case(liveContainer=MISSING),
    "empty": {},
}

# Cases also run with a Pairing File row set: one without the LiveContainer row and one with it.
PAIRING_CASES = ("all", "lc")

GTA_FIX ="No JIT method works without it. Sign ARMSX3 again with get-task-allow turned on, then reinstall it."
IML_FIX = "Games are more likely to close when memory runs low. Sign ARMSX3 again with the increased-memory-limit entitlement."
IML_FIX_LC = ("Games are more likely to close when memory runs low. "
              "Sign LiveContainer again with the increased-memory-limit entitlement.")

MEMORY = {
    -1: "Unknown",
    0: "Unknown",
    1: "0.0 GB",
    536870912: "0.5 GB",
    1073741823: "1.0 GB",
    6442450944: "6.0 GB",
    7516192768: "7.0 GB",
}


def word(value, present, missing):
    return {1: present, 0: missing}.get(value, "Unknown")


def expected_rows(checks):
    """The Device card's rows as `label|value|problem|fix`, or None when a key is absent."""
    if any(key not in checks for key in KEYS):
        return None
    gta, iml, lc = checks["getTaskAllow"], checks["increasedMemoryLimit"], checks["liveContainer"] == 1
    memory = checks["memoryLimitBytes"]
    rows = [
        ("Debugging Entitlement", word(gta, "Present", "Missing"), gta == 0, GTA_FIX if gta == 0 else None),
        ("Increased Memory Limit", word(iml, "Present", "Missing"), iml == 0,
         (IML_FIX_LC if lc else IML_FIX) if iml == 0 else None),
        ("Memory Limit", MEMORY.get(memory, "%.1f GB" % (memory / 1073741824)) if memory >= 1 else "Unknown", False, None),
        ("TXM", word(checks["txm"], "Present", "Not present"), False, None),
        ("Address Space", word(checks["extendedVirtualAddressing"], "Extended", "Standard"), False, None),
    ]
    if lc:
        rows.append(("LiveContainer", "Detected", False, None))
    return ["{0}|{1}|{2}|{3}".format(label, value, 1 if problem else 0, fix or "none") for label, value, problem, fix in rows]


def driver():
    lines = [
        "import Foundation",
        "func show(_ name: String, _ checks: [String: NSNumber]) {",
        "    guard let report = DeviceReport(checks) else { print(\"case \\(name) unreadable\"); return }",
        "    for row in report.rows {",
        "        print(\"row \\(name) \\(row.label)|\\(row.value)|\\(row.isProblem ? 1 : 0)|\\(row.fix ?? \"none\")\")",
        "    }",
        "}",
        "func pairing(_ name: String, _ checks: [String: NSNumber], set: Bool) {",
        "    guard var report = DeviceReport(checks) else { return }",
        "    if set { report.pairingRow = DeviceRow(label: \"Pairing File\", value: \"Own\", isProblem: false, fix: nil) }",
        "    print(\"pair \\(name) \\(report.rows.map { $0.label }.joined(separator: \"|\"))\")",
        "}",
    ]
    for name, checks in sorted(CASES.items()):
        body = ", ".join('"{0}": NSNumber(value: Int64({1}))'.format(k, v) for k, v in sorted(checks.items()))
        lines.append("show(\"{0}\", [{1}])".format(name, body or ":"))
        if name in PAIRING_CASES:
            for state in ("nil", "set"):
                lines.append("pairing(\"{0}-{1}\", [{2}], set: {3})".format(name, state, body, str(state == "set").lower()))
    for value in sorted(MEMORY):
        lines.append("print(\"mem {0} \\(DeviceReport.memoryText(Int64({0})))\")".format(value))
    return "\n".join(lines) + "\n"


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def device_checks_problems(out):
    rows, unreadable, memory, labels = {}, set(), {}, {}
    for line in out.splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3 and parts[0] == "pair":
            labels[parts[1]] = parts[2].split("|")
        elif len(parts) == 3 and parts[0] == "row":
            rows.setdefault(parts[1], []).append(parts[2])
        elif len(parts) == 3 and parts[0] == "case" and parts[2] == "unreadable":
            unreadable.add(parts[1])
        elif len(parts) == 3 and parts[0] == "mem":
            memory[int(parts[1])] = parts[2]
    problems = []
    for name, checks in sorted(CASES.items()):
        want = expected_rows(checks)
        if want is None:
            if name not in unreadable or name in rows:
                problems.append("unreadable: {0} gave {1}, wanted no report".format(name, rows.get(name)))
        elif name in unreadable:
            problems.append("unreadable: {0} gave no report, wanted {1}".format(name, want))
        elif rows.get(name) != want:
            problems.append("rows: {0} gave {1}, wanted {2}".format(name, rows.get(name), want))
    for name in PAIRING_CASES:
        base = [row.split("|")[0] for row in expected_rows(CASES[name])]
        if labels.get(name + "-nil") != base:
            problems.append("pairing: {0} without the row gave {1}, wanted {2}".format(name, labels.get(name + "-nil"), base))
        if labels.get(name + "-set") != base + ["Pairing File"]:
            problems.append("pairing: {0} with the row gave {1}, wanted it last after {2}".format(name, labels.get(name + "-set"), base))
    for value, text in sorted(MEMORY.items()):
        if memory.get(value) != text:
            problems.append("memory: {0} gave {1!r}, wanted {2!r}".format(value, memory.get(value), text))
    return problems


def source_problems(text):
    problems = []
    lines = text.splitlines()
    if not lines or "SPDX-License-Identifier: GPL-2.0-only" not in lines[0]:
        problems.append("source: line 1 is not the SPDX GPL-2.0-only line")
    if "Adapted from ARMSX2" in text:
        problems.append("source: the file carries the lifted-file header")
    imports = set(re.findall(r"^\s*import\s+(\w+)", text, flags=re.MULTILINE))
    if imports != {"Foundation"}:
        problems.append("source: imports {0}, wanted Foundation only".format(sorted(imports)))
    return problems


def run(source_text):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "DeviceChecks.swift"
        path.write_text(source_text, encoding="utf-8")
        return compile_and_run([path], driver(), tmp)


class RealFiles(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        text = read(SOURCE)
        self.assertEqual(source_problems(text), [])
        out = run(text)
        for line in out.splitlines():
            if line.startswith("mem "):
                sys.stderr.write("memoryText " + line[4:] + "\n")
        self.assertEqual(device_checks_problems(out), [])

    def test_control_the_expected_table_matches_the_ui_spec(self):
        self.assertEqual(expected_rows(CASES["gta0"])[0], "Debugging Entitlement|Missing|1|" + GTA_FIX)
        self.assertEqual(expected_rows(CASES["iml0lc"])[1], "Increased Memory Limit|Missing|1|" + IML_FIX_LC)
        self.assertEqual(expected_rows(CASES["iml0lc"])[5], "LiveContainer|Detected|0|none")
        self.assertEqual(len(expected_rows(CASES["all"])), 5)
        self.assertIsNone(expected_rows(CASES["empty"]))

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
        return text.replace(old, new)

    def assert_caught(self, old, new, prefix):
        problems = device_checks_problems(run(self.edited(self.text, old, new)))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_the_livecontainer_fix_names_armsx3(self):
        self.assert_caught("Sign LiveContainer again", "Sign ARMSX3 again", "rows:")

    def test_mutation_txm_not_present_marked_as_a_problem(self):
        self.assert_caught("value: txmWord, isProblem: false", "value: txmWord, isProblem: txm == .missing", "rows:")

    def test_mutation_the_memory_divisor_is_decimal(self):
        self.assert_caught("/ 1073741824", "/ 1e9", "memory:")

    def test_mutation_a_missing_key_still_makes_a_report(self):
        self.assert_caught("guard Self.keys.allSatisfy({ checks[$0] != nil }) else { return nil }", "", "unreadable:")

    def test_mutation_the_pairing_row_listed_first(self):
        self.assert_caught("if let pairingRow { rows.append(pairingRow) }", "if let pairingRow { rows.insert(pairingRow, at: 0) }", "pairing:")

    def test_mutation_the_spdx_line_dropped(self):
        text = self.edited(self.text, "SPDX-License-Identifier: GPL-2.0-only", "License: see COPYING")
        self.assertTrue(any(p.startswith("source:") for p in source_problems(text)))

    def test_mutation_the_lifted_header_added(self):
        text = self.edited(self.text, "// SPDX-License-Identifier: GPL-2.0-only\n",
                           "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n")
        self.assertTrue(any(p.startswith("source:") for p in source_problems(text)))


if __name__ == "__main__":
    unittest.main()
