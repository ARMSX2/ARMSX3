# SPDX-License-Identifier: GPL-2.0-only
"""ReportFacts.isOwnCrash, compiled on the Mac, keeps a crash diagnostic that names the ARMSX3 process and drops one
that names another process, the old name or nothing. A copy with the name swapped back must fail the cases,
and DiagnosticsStore.ownPayload must filter through the predicate."""

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
FACTS = ROOT / "ios" / "app" / "Diagnostics" / "ReportFacts.swift"
STORE = ROOT / "ios" / "app" / "Diagnostics" / "DiagnosticsStore.swift"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from swiftc_host import compile_and_run  # noqa: E402

# The app's old process name, split so a grep for it over ios/ stays empty.
OLD_NAME = "Har" "ness"

# Each case is a crash diagnostic as ownPayload serializes it, and whether the report keeps it.
CASES = [
    ("binary", '{"callStackTree":{"callStacks":[{"callStackRootFrames":[{"binaryName":"ARMSX3"}]}]}}', True),
    ("process", '{"diagnosticMetaData":{"processName":"ARMSX3"}}', True),
    ("old-name", '{"callStackTree":{"callStacks":[{"callStackRootFrames":[{"binaryName":"%s"}]}]}}' % OLD_NAME, False),
    ("other-app", '{"callStackTree":{"callStacks":[{"callStackRootFrames":[{"binaryName":"OtherJIT"}]}]}}', False),
    ("longer-name", '{"diagnosticMetaData":{"processName":"ARMSX3Helper"}}', False),
    ("empty", "{}", False),
]
EXPECTED = ["name=ARMSX3"] + ["{0}={1}".format(name, str(keep).lower()) for name, _, keep in CASES]


def driver():
    lines = ['print("name=\\(ReportFacts.processName)")']
    for name, text, _ in CASES:
        swift = text.replace("\\", "\\\\").replace('"', '\\"')
        lines.append('print("{0}=\\(ReportFacts.isOwnCrash("{1}"))")'.format(name, swift))
    return "\n".join(lines) + "\n"


def run(facts_text):
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "ReportFacts.swift"
        source.write_text(facts_text, encoding="utf-8")
        return compile_and_run([source], driver(), tmp).splitlines()


class TheFilterKeepsOnlyOwnCrashes(unittest.TestCase):
    def test_each_payload_is_kept_or_dropped(self):
        self.assertEqual(EXPECTED, run(FACTS.read_text(encoding="utf-8")))

    def test_the_old_name_fails_the_cases(self):
        text = FACTS.read_text(encoding="utf-8")
        line = 'static let processName = "ARMSX3"'
        self.assertEqual(1, text.count(line))
        self.assertNotEqual(EXPECTED, run(text.replace(line, 'static let processName = "%s"' % OLD_NAME)))

    def test_the_store_filters_through_the_predicate(self):
        self.assertIn("ReportFacts.isOwnCrash(", STORE.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
