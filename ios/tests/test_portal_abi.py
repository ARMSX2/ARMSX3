# SPDX-License-Identifier: GPL-2.0-only
"""PortalStore.swift, the portal figure table, the copy into the figures folder and the refusals, compiled with swiftc on the host.
The driver prints P-lines that are compared exactly.
"""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS_DIR = Path(__file__).resolve().parents[1]

STORE = IOS_DIR / "app" / "Controllers" / "PortalStore.swift"

HOST_LINES = ["P1 sizes=1024,320,180,320 slots=8,9,7,8", "P2 copied=1 renamed=1", "P3 wrongsize=0", "P4 own=1"]

HOST_DRIVER = """
import Foundation

let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
let documents = root.appendingPathComponent("Documents", isDirectory: true)
let picked = root.appendingPathComponent("Picked", isDirectory: true)
try! FileManager.default.createDirectory(at: picked, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root) }

let ps = PortalStore.portals
print("P1 sizes=" + ps.map { String($0.fileBytes) }.joined(separator: ",") + " slots=" + ps.map { String($0.slots) }.joined(separator: ","))

let sky = ps[0]
let folder = PortalStore.folder(sky, documents: documents)
let good = picked.appendingPathComponent("fig.bin")
try! Data(count: 1024).write(to: good)
let first = PortalStore.copyIn(good, portal: sky, documents: documents)
let second = PortalStore.copyIn(good, portal: sky, documents: documents)
let copied = first.map { FileManager.default.fileExists(atPath: $0.path) && $0.deletingLastPathComponent().path == folder.path } ?? false
let renamed = first != nil && second != nil && first!.lastPathComponent != second!.lastPathComponent
    && FileManager.default.fileExists(atPath: second!.path)
print("P2 copied=\\(copied ? 1 : 0) renamed=\\(renamed ? 1 : 0)")

let before = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? 0
let small = picked.appendingPathComponent("small.bin")
try! Data(count: 10).write(to: small)
let refused = PortalStore.copyIn(small, portal: sky, documents: documents)
let after = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? 0
print("P3 wrongsize=\\(refused == nil ? after - before : 1)")

let own = first.flatMap { PortalStore.copyIn($0, portal: sky, documents: documents) }
let unchanged = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? -1
print("P4 own=\\(own != nil && own!.path == first!.path && unchanged == after ? 1 : 0)")
"""


class ThePortalStoreHolds(unittest.TestCase):
    """PortalStore.swift compiled alone on the Mac host; not device evidence.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            cls.out = compile_and_run([STORE], HOST_DRIVER, tmp)
        cls.lines = [line for line in cls.out.splitlines() if re.match(r"P\d ", line)]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_table_the_copy_and_the_refusal(self):
        self.assertEqual(HOST_LINES, self.lines, self.out)


if __name__ == "__main__":
    unittest.main()
