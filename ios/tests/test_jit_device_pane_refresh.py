# SPDX-License-Identifier: GPL-2.0-only
"""JitDeviceModel.refresh() reads what it needs from the model before it updates the device report, because a
property that reads `report` inside `report?.field = ...` is a simultaneous access that traps at run time. It reads
comment-stripped source text and proves nothing about iOS."""

import re
import unittest
from pathlib import Path

from source_helpers import brace_span, read, without_comments

PANE = Path(__file__).resolve().parents[1] / "app" / "JIT" / "JitDevicePane.swift"

# Names that read `report`, directly or through a computed property of the model.
READS_REPORT = re.compile(r"\b(report|liveContainer|jitOnThroughBuiltin)\b")
OPTIONAL_ASSIGN = re.compile(r"^[ \t]*report\?\.\w+\s*=(?!=)(.*)$", re.M)

OLD_VERSION = "report?.liveContainerVersion = liveContainer ? probe.version : nil"
OLD_OTHER = "report?.helperFromOtherInstall = liveContainer && jitOnThroughBuiltin && probe.hooksActive == false"


def refresh_problems(code):
    match = re.search(r"func\s+refresh\s*\(\s*\)", code)
    if match is None:
        return ["refresh: JitDeviceModel.refresh() is missing"]
    body = code[slice(*brace_span(code, match.end()))]
    assigned = OPTIONAL_ASSIGN.findall(body)
    if not assigned:
        return ["refresh: no report?.field assignment found to check"]
    return ["refresh: a report?.field assignment reads {0}".format(READS_REPORT.search(rhs).group(1))
            for rhs in assigned if READS_REPORT.search(rhs)]


class RefreshReportAccess(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = without_comments(read(PANE))

    def reintroduced(self, field, old):
        found = re.search(r"report\?\.{0} = [^\n]*".format(field), self.code)
        self.assertIsNotNone(found)
        return refresh_problems(self.code.replace(found.group(0), old, 1))

    def test_source_reads_before_it_assigns(self):
        self.assertEqual([], refresh_problems(self.code))

    def test_mutation_version_assigned_from_a_read(self):
        self.assertTrue(any("liveContainer" in p for p in self.reintroduced("liveContainerVersion", OLD_VERSION)))

    def test_mutation_other_install_assigned_from_a_read(self):
        self.assertTrue(any("liveContainer" in p for p in self.reintroduced("helperFromOtherInstall", OLD_OTHER)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
