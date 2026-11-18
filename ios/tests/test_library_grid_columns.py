# SPDX-License-Identifier: GPL-2.0-only
"""The library grid's columns have no minimum width.
A minimum makes the grid as wide as its columns, the scroll view reports that width back as gridWidth, and a column
count worked out in landscape then never shrinks when the phone turns upright."""

import re
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app" / "Library" / "GameListView.swift"


def column_minimums(source):
    return [int(m) for m in re.findall(r"GridItem\(\.flexible\(minimum:\s*(\d+)", source)]


class LibraryGridColumns(unittest.TestCase):
    def test_columns_have_no_minimum(self):
        minimums = column_minimums(SOURCE.read_text())
        self.assertTrue(minimums, "no flexible GridItem found in GameListView.swift")
        self.assertEqual(set(minimums), {0})

    def test_checker_flags_a_minimum(self):
        self.assertEqual(column_minimums("GridItem(.flexible(minimum: 150, maximum: 240))"), [150])


if __name__ == "__main__":
    unittest.main()
