#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The JIT enabler extension's Info.plist parses and keeps the com.apple.ar.viewer extension point
with the FALSEPREDICATE activation rule, so no share sheet ever offers it.
"""

import plistlib
import unittest
from pathlib import Path

IOS_DIR = Path(__file__).resolve().parents[1]
INFO_PLIST = IOS_DIR / "jit-enabler" / "JITEnablerExtension" / "Info.plist"


class TheInfoPlistKeepsTheArViewerPoint(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with INFO_PLIST.open("rb") as handle:
            cls.extension = plistlib.load(handle)["NSExtension"]

    def test_the_extension_point_is_ar_viewer(self):
        self.assertEqual("com.apple.ar.viewer", self.extension["NSExtensionPointIdentifier"])

    def test_nothing_activates_it(self):
        self.assertEqual("FALSEPREDICATE", self.extension["NSExtensionAttributes"]["NSExtensionActivationRule"])


if __name__ == "__main__":
    unittest.main()
