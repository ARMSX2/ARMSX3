# SPDX-License-Identifier: GPL-2.0-only
"""The game screen keeps the screen awake only while the game is booted, not stopping, not paused by the menu and
the app is active, and gives the idle timer back when it goes away."""

import re
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app" / "Game" / "GameScreenView.swift"
WHEN = "bootStatus == 0 && !stopping && !menuOpen && scenePhase == .active"


def problems(source):
    found = []
    change = re.search(r"\.onChange\(of: ([^{]*?), initial: true\) \{ _, awake in\s*UIApplication\.shared\.isIdleTimerDisabled = awake\s*\}", source)
    if not change or change.group(1) != WHEN:
        found.append("the idle timer is not driven by " + WHEN)
    disappear = re.search(r"\.onDisappear \{(.*?)\n            \}", source, re.S)
    if not disappear or "UIApplication.shared.isIdleTimerDisabled = false" not in disappear.group(1):
        found.append("onDisappear does not give the idle timer back")
    return found


class GameIdleTimer(unittest.TestCase):
    def test_source_keeps_its_promises(self):
        self.assertEqual(problems(SOURCE.read_text()), [])

    def test_checker_flags_a_missing_release(self):
        self.assertTrue(problems(".onChange(of: x, initial: true) { _, awake in\n UIApplication.shared.isIdleTimerDisabled = awake\n }"))


if __name__ == "__main__":
    unittest.main()
