#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""build-ios-ipa.sh compares the binary's armsx3_jit_enabler_* export count with the number of
functions the launcher header declares, and its comment names all of them."""

import re
import unittest
from pathlib import Path


IOS_DIR = Path(__file__).resolve().parent.parent

HEADER = IOS_DIR / "jit-enabler" / "StikJITExtensionLauncher.h"
SCRIPT = IOS_DIR / "scripts" / "build-ios-ipa.sh"

NUMBER_WORDS = {1: "One", 2: "Two", 3: "Three", 4: "Four", 5: "Five", 6: "Six", 7: "Seven", 8: "Eight"}


def declared_functions():
    text = HEADER.read_text(encoding="utf-8")
    return set(re.findall(r"^\s*int\s+armsx3_jit_enabler_(\w+)\s*\(", text, re.MULTILINE))


def check_problems(script_text, functions):
    """Everything wrong with the export-count check in script_text, as a list of messages."""
    problems = []
    compared = re.search(r'\[\[ "\$\{EXPORTS\}" != (\d+) \]\]', script_text)
    if compared is None:
        return ["the EXPORTS comparison is missing"]
    if int(compared.group(1)) != len(functions):
        problems.append("compares against {0}, the header declares {1}".format(compared.group(1), len(functions)))
    if "expected {0}.".format(compared.group(1)) not in script_text:
        problems.append("the failure message does not repeat the compared number")
    comment = re.search(r"^# (\w+) names, resolved by dlsym.*$", script_text, re.MULTILINE)
    if comment is None:
        problems.append("the comment above the check is missing")
    else:
        if comment.group(1) != NUMBER_WORDS.get(len(functions)):
            problems.append("the comment says {0} names".format(comment.group(1)))
        for name in sorted(functions):
            if name not in comment.group(0):
                problems.append("the comment does not name {0}".format(name))
    return problems


class TheIpaExportCheckMatchesTheHeader(unittest.TestCase):
    def test_the_header_declares_functions(self):
        self.assertTrue(declared_functions(), "no armsx3_jit_enabler_* function found in {0}".format(HEADER))

    def test_the_script_expects_every_declared_function(self):
        self.assertEqual([], check_problems(SCRIPT.read_text(encoding="utf-8"), declared_functions()))

    def test_a_script_that_still_expects_four_fails(self):
        text = SCRIPT.read_text(encoding="utf-8")
        stale = re.sub(r'("\$\{EXPORTS\}" != )\d+', r"\g<1>4", text)
        self.assertNotEqual(text, stale)
        self.assertNotEqual([], check_problems(stale, declared_functions()))


if __name__ == "__main__":
    unittest.main()
