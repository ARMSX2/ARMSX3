# SPDX-License-Identifier: GPL-2.0-only
"""The input config JSON fixture parses: perGame is 0 or 1 and there are four players
whose keys are the pad config keys in order.
"""

import json
import unittest
from pathlib import Path

from source_helpers import read


IOS_DIR = Path(__file__).resolve().parents[1]

FIXTURE = IOS_DIR / "tests" / "fixtures" / "input-config-dump.json"

# rpcs3/Emu/Io/pad_config.h `cfg_pad`: the binding strings, in declaration order, then the four stick numbers.
BINDINGS = ("Left Stick Left", "Left Stick Down", "Left Stick Right", "Left Stick Up", "Right Stick Left",
            "Right Stick Down", "Right Stick Right", "Right Stick Up", "Start", "Select", "PS Button", "Square",
            "Cross", "Circle", "Triangle", "Left", "Down", "Right", "Up", "R1", "R2", "R3", "L1", "L2", "L3")

NUMBERS = ("Left Stick Deadzone", "Right Stick Deadzone", "Left Stick Multiplier", "Right Stick Multiplier")

# A player's device, the core's own cfg_pad keys.
DEVICE = ("Device Class Type", "Vendor ID", "Product ID")

KEYS = BINDINGS + NUMBERS + DEVICE


def fixture_problems(fixture):
    try:
        data = json.loads(fixture)
    except ValueError as error:
        return ["fixture: not JSON ({0})".format(error)]
    problems = []
    if data.get("perGame") not in (0, 1):
        problems.append("fixture: perGame is not 0 or 1")
    players = data.get("players")
    if not isinstance(players, list) or len(players) != 4:
        problems.append("fixture: players is not a list of four")
        players = []
    for index, player in enumerate(players):
        if tuple(player) != KEYS:
            problems.append("fixture: player {0}'s keys are not KEYS in order".format(index))
    return problems


class TheRealFilesKeepTheContract(unittest.TestCase):
    def test_fixture(self):
        self.assertEqual([], fixture_problems(read(FIXTURE)))


if __name__ == "__main__":
    unittest.main()
