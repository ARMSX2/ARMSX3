#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""LibraryRelocation.swift compiled and run on the Mac host against a temporary tree: a folder picked from a
temporary place moves into Documents/games by rename, a user folder stays, a failed move leaves the folder,
and a clash lands in a numbered name."""

import json
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import read_library_view as read


IOS = Path(__file__).resolve().parents[1]

APP = IOS / "app"

HELPER = APP / "Library" / "LibraryRelocation.swift"

DRIVER = r"""
import Foundation

let fm = FileManager.default
let root = @@TMP@@
func p(_ s: String) -> String { root + "/" + s }
func mk(_ s: String) { try! fm.createDirectory(atPath: p(s), withIntermediateDirectories: true) }
func put(_ s: String, _ b: String) { try! Data(b.utf8).write(to: URL(fileURLWithPath: p(s))) }
func has(_ s: String) -> Bool { fm.fileExists(atPath: p(s)) }
func bytes(_ s: String) -> String { String(decoding: try! Data(contentsOf: URL(fileURLWithPath: p(s))), as: UTF8.self) }
func inode(_ s: String) -> UInt64 { ((try! fm.attributesOfItem(atPath: p(s)))[.systemFileNumber] as! NSNumber).uint64Value }
func list(_ s: String) -> String { (try! fm.contentsOfDirectory(atPath: p(s))).sorted().joined(separator: ",") }
func b(_ v: Bool) -> Int { v ? 1 : 0 }

let roots = LibraryRelocation.Roots(home: URL(fileURLWithPath: p("home")),
                                    temporary: URL(fileURLWithPath: p("c/tmp")),
                                    games: URL(fileURLWithPath: p("home/Documents/games")))
func temp(_ s: String) -> Bool { LibraryRelocation.isTemporary(URL(fileURLWithPath: p(s)), roots: roots) }
func move(_ s: String, renamer: (String, String) -> Int32 = LibraryRelocation.posixRename) -> LibraryRelocation.Outcome {
    LibraryRelocation.relocate(URL(fileURLWithPath: p(s)), roots: roots, renamer: renamer)
}
func kind(_ o: LibraryRelocation.Outcome) -> String {
    switch o { case .kept: return "kept"; case .moved: return "moved"; case .failed: return "failed" }
}
func dest(_ o: LibraryRelocation.Outcome) -> String {
    if case .moved(let u) = o { return u.lastPathComponent }; return "-"
}
func code(_ o: LibraryRelocation.Outcome) -> Int32 {
    if case .failed(let e) = o { return e }; return 0
}

mk("home/Inbox/F"); put("home/Inbox/F/game.iso", "ISO1"); mk("c/tmp")
try! fm.createSymbolicLink(atPath: p("c/tmp/com.example.app-Inbox"), withDestinationPath: p("home/Inbox"))
let i1 = inode("home/Inbox/F/game.iso")
let t1 = temp("c/tmp/com.example.app-Inbox/F")
let o1 = move("c/tmp/com.example.app-Inbox/F")
let link = (try? fm.destinationOfSymbolicLink(atPath: p("c/tmp/com.example.app-Inbox"))) == p("home/Inbox")
print("R1 temporary=\(b(t1)) outcome=\(kind(o1)) dest=\(dest(o1)) gone=\(b(!has("home/Inbox/F"))) link=\(b(link)) bytes=\(bytes("home/Documents/games/F/game.iso")) sameinode=\(b(inode("home/Documents/games/F/game.iso") == i1))")

put("c/tmp/loose.iso", "ISO2")
let i2 = inode("c/tmp/loose.iso")
let item2 = LibraryRelocation.item(containing: p("c/tmp/loose.iso"), roots: roots)
let o2 = LibraryRelocation.relocate(item2!, roots: roots)
print("R2 item=\(item2?.lastPathComponent ?? "nil") outcome=\(kind(o2)) dest=\(dest(o2)) file=\(dest(o2))/\(list("home/Documents/games/" + dest(o2))) sameinode=\(b(inode("home/Documents/games/loose/loose.iso") == i2))")

mk("user/G"); put("user/G/game.iso", "USR1")
let t3 = temp("user/G")
let o3 = move("user/G")
print("R3 temporary=\(b(t3)) outcome=\(kind(o3)) present=\(b(has("user/G/game.iso")))")

mk("home/Inbox/H"); put("home/Inbox/H/game.iso", "ISO4")
let o4 = move("home/Inbox/H", renamer: { _, _ in EXDEV })
print("R4 outcome=\(kind(o4)) errno=\(code(o4)) present=\(b(has("home/Inbox/H/game.iso")))")

mk("home/Inbox/F"); put("home/Inbox/F/game.iso", "ISO3")
let o5 = move("home/Inbox/F")
print("R5 dest=\(dest(o5)) first=\(bytes("home/Documents/games/F/game.iso"))")

try! fm.createSymbolicLink(atPath: p("c/tmp/com.other-Inbox"), withDestinationPath: p("nowhere"))
let t6 = temp("c/tmp/com.other-Inbox/F")
let o6 = move("c/tmp/com.other-Inbox/F")
print("R6 temporary=\(b(t6)) outcome=\(kind(o6)) errno=\(code(o6))")

mk("user/Mail-Inbox/G")
print("R7 temporary=\(b(temp("user/Mail-Inbox/G")))")

let o8 = move("c/tmp/com.example.app-Inbox")
var isDir: ObjCBool = false
let recreated = fm.fileExists(atPath: p("home/Inbox"), isDirectory: &isDir) && isDir.boolValue
print("R8 outcome=\(kind(o8)) dest=\(dest(o8)) recreated=\(b(recreated)) empty=\(b(list("home/Inbox").isEmpty)) held=\(list("home/Documents/games/Inbox"))")

let gone = LibraryRelocation.goneMessage(bootPath: p("gone/Lost Game.iso"), isISO: true)
let present = LibraryRelocation.goneMessage(bootPath: p("home/Documents/games/F/game.iso"), isISO: true)
let folder = LibraryRelocation.goneMessage(bootPath: p("gone/Lost Game.iso"), isISO: false)
print("G1 missing=\(b(gone?.hasPrefix("The disc image is gone.") == true)) name=\(b(gone?.contains("Lost Game.iso") == true)) slash=\(b(gone?.contains("/") ?? true)) present=\(present == nil ? "nil" : "text") folder=\(folder == nil ? "nil" : "text")")

"""

EXPECTED = [
    "R1 temporary=1 outcome=moved dest=F gone=1 link=1 bytes=ISO1 sameinode=1",
    "R2 item=loose.iso outcome=moved dest=loose file=loose/loose.iso sameinode=1",
    "R3 temporary=0 outcome=kept present=1",
    "R4 outcome=failed errno=18 present=1",
    "R5 dest=F 2 first=ISO1",
    "R6 temporary=1 outcome=failed errno=2",
    "R7 temporary=0",
    "R8 outcome=moved dest=Inbox recreated=1 empty=1 held=H",
    "G1 missing=1 name=1 slash=0 present=nil folder=nil",
]


class TheHelperHolds(unittest.TestCase):
    def test_host_cases(self):
        read(HELPER)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([HELPER], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        self.assertEqual(EXPECTED, [line for line in out.splitlines() if line.strip()])


if __name__ == "__main__":
    unittest.main(verbosity=2)
