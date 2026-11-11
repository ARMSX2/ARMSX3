#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""StorageWatch.swift compiled and run on the Mac host: the low threshold, an unreadable volume that is never reported as
low, and a real folder whose free space can be read. A source check keeps the game screen's watcher in place."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Game" / "StorageWatch.swift"]
SCREEN = IOS / "app" / "Game" / "GameScreenView.swift"

DRIVER = r"""
import Foundation
let mib: Int64 = 1 << 20
print("T1 \(StorageWatch.isLow(499 * mib)) \(StorageWatch.isLow(500 * mib)) \(StorageWatch.isLow(0))")
print("T2 \(StorageWatch.isLow(nil))")
let free = StorageWatch.availableBytes(at: URL(fileURLWithPath: NSTemporaryDirectory()))
print("T3 \((free ?? 0) > 0)")
print("T4 \(StorageWatch.availableBytes(at: URL(fileURLWithPath: "/nonexistent/folder")) == nil)")
"""

EXPECTED = ["T1 true false true", "T2 false", "T3 true", "T4 true"]


class StorageWatchHost(unittest.TestCase):
    def test_thresholds_and_reading(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            output = compile_and_run(SOURCES, DRIVER, work)
        self.assertEqual(output.strip().splitlines(), EXPECTED)


class GameScreenWatchesStorage(unittest.TestCase):
    def test_watcher_runs_while_the_screen_is_up(self):
        code = SCREEN.read_text()
        self.assertIsNotNone(re.search(r"\.task \{ await watchStorage\(\) \}", code))
        body = re.search(r"private func watchStorage\(\) async \{(.*?)\n    \}\n", code, re.S)
        self.assertIsNotNone(body)
        self.assertIn("StorageWatch.isLow(StorageWatch.availableBytes(at: documents))", body.group(1))
        self.assertIn("shotBanner.present(", body.group(1))


if __name__ == "__main__":
    unittest.main()
