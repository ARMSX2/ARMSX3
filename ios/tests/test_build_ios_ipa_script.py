#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""build-ios-ipa.sh is executable, and its refusal paths (--help, a bad configuration,
a missing archive) exit with their codes and call no external tool: every tool is a
shim that fails the test if it is run."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


IOS_DIR = Path(__file__).resolve().parent.parent

SCRIPT = IOS_DIR / "scripts" / "build-ios-ipa.sh"

SHIMMED = ("xcrun", "xcodebuild", "xcodegen", "codesign", "security", "devicectl", "ditto", "zip", "openssl", "plutil")

# The script's own inputs, removed from the dry paths' environment so "defaults" means defaults.
SCRIPT_VARS = ("SIGN", "CONFIGURATION", "ARM", "ARMSX3_CMAKE_CONFIG", "TEAM_ID", "OUT_DIR", "ARM_ENTITLEMENTS")


def read_script():
    text = SCRIPT.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(SCRIPT))
    return text


class TheScriptIsShapedAsSpecified(unittest.TestCase):
    maxDiff = None  # every shape problem is printed, not the first one

    def test_the_script_is_executable(self):
        read_script()
        self.assertTrue(os.access(str(SCRIPT), os.X_OK), "{0} is not executable".format(SCRIPT))


class TheRefusalPathsCallNoTool(unittest.TestCase):
    def setUp(self):
        read_script()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.shim = Path(self.tmp.name) / "shim"
        self.shim.mkdir()
        self.log = self.shim / "calls.log"
        for name in SHIMMED:
            path = self.shim / name
            path.write_text('#!/bin/sh\necho "{0} $*" >> "{1}"\nexit 97\n'.format(name, self.log))
            path.chmod(0o755)
        # Positive control: the shim is first on PATH and records a call.
        control = self.run_with_shim(["xcrun", "shim-probe"])
        self.assertEqual(97, control.returncode)
        self.assertEqual("xcrun shim-probe\n", self.log.read_text())
        self.log.unlink()

    def run_with_shim(self, argv, **extra):
        env = {k: v for k, v in os.environ.items() if k not in SCRIPT_VARS}
        env["PATH"] = str(self.shim) + ":" + os.environ["PATH"]
        env.update(extra)
        return subprocess.run(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)

    def assert_no_tool_called(self):
        calls = self.log.read_text() if self.log.exists() else ""
        self.assertEqual("", calls, "a refusal path called an external tool")

    def test_help_exits_0_and_calls_nothing(self):
        result = self.run_with_shim(["bash", str(SCRIPT), "--help"])
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("Environment:", result.stdout)
        self.assert_no_tool_called()

    def test_an_invalid_configuration_exits_2_and_calls_nothing(self):
        result = self.run_with_shim(["bash", str(SCRIPT)], CONFIGURATION="Bogus")
        self.assertEqual(2, result.returncode, result.stdout)
        self.assert_no_tool_called()

    def test_a_missing_archive_exits_3_and_calls_nothing(self):
        # The scratch copy has no ios/build/ beside it, and SIGN=1 by default, so a refusal must come before any tool.
        scripts = Path(self.tmp.name) / "ios" / "scripts"
        scripts.mkdir(parents=True)
        copy = scripts / SCRIPT.name
        shutil.copy2(str(SCRIPT), str(copy))
        result = self.run_with_shim(["bash", str(copy)])
        self.assertEqual(3, result.returncode, result.stdout)
        self.assertIn("BLOCKED", result.stdout)
        self.assert_no_tool_called()


if __name__ == "__main__":
    unittest.main()
