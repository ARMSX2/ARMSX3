#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""DocumentsTrash.swift compiled and run on the Mac host against a temporary tree: everything inside Documents/.Trash
goes and its bytes are counted, a link inside it is removed without touching its target, and a .Trash that is itself
a link is left alone."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Library" / "DocumentsTrash.swift"]

DRIVER = r"""
import Foundation

let fm = FileManager.default
let root = @@TMP@@
func p(_ s: String) -> String { root + "/" + s }
func mk(_ s: String) { try! fm.createDirectory(atPath: p(s), withIntermediateDirectories: true) }
func put(_ s: String, _ n: Int) { try! Data(count: n).write(to: URL(fileURLWithPath: p(s))) }
func has(_ s: String) -> Bool { fm.fileExists(atPath: p(s)) }
func b(_ v: Bool) -> Int { v ? 1 : 0 }

mk("A/Documents/.Trash/Game/PS3_GAME/USRDIR"); put("A/Documents/.Trash/Game/PS3_GAME/USRDIR/EBOOT.BIN", 1000)
put("A/Documents/.Trash/Game/PS3_GAME/PARAM.SFO", 24); put("A/Documents/.Trash/loose.iso", 300)
mk("A/Documents/games/Kept"); put("A/Documents/games/Kept/file", 7)
mk("Outside/Big"); put("Outside/Big/data", 5000)
try! fm.createSymbolicLink(atPath: p("A/Documents/.Trash/Link"), withDestinationPath: p("Outside/Big"))
print("E1 freed=\(DocumentsTrash.empty(documents: URL(fileURLWithPath: p("A/Documents"))))")
print("E2 trash=\(b(has("A/Documents/.Trash"))) left=\((try? fm.contentsOfDirectory(atPath: p("A/Documents/.Trash")))?.count ?? -1) kept=\(b(has("A/Documents/games/Kept/file"))) target=\(b(has("Outside/Big/data")))")
print("E3 again=\(DocumentsTrash.empty(documents: URL(fileURLWithPath: p("A/Documents"))))")

mk("B/Documents")
try! fm.createSymbolicLink(atPath: p("B/Documents/.Trash"), withDestinationPath: p("Outside"))
print("L1 freed=\(DocumentsTrash.empty(documents: URL(fileURLWithPath: p("B/Documents")))) target=\(b(has("Outside/Big/data")))")
print("N1 freed=\(DocumentsTrash.empty(documents: URL(fileURLWithPath: p("C/Documents"))))")
"""

EXPECTED = [
    "E1 freed=1324",
    "E2 trash=1 left=0 kept=1 target=1",
    "E3 again=0",
    "L1 freed=0 target=1",
    "N1 freed=0",
]


class DocumentsTrashHost(unittest.TestCase):
    def test_empties_only_inside_the_trash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            work = root / "build"
            work.mkdir()
            output = compile_and_run(SOURCES, DRIVER.replace("@@TMP@@", '"{0}"'.format(root)), work)
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
