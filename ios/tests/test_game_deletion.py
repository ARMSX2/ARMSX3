#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""GameDeletion.swift compiled and run on the Mac host against a temporary tree: only a folder or image inside the
games folder can be deleted, a link or an outside path never can, and a disc image's emptied wrapper goes with it."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Library" / "GameDeletion.swift", IOS / "app" / "Library" / "LibraryRelocation.swift"]

DRIVER = r"""
import Foundation

let fm = FileManager.default
let root = @@TMP@@
func p(_ s: String) -> String { root + "/" + s }
func mk(_ s: String) { try! fm.createDirectory(atPath: p(s), withIntermediateDirectories: true) }
func put(_ s: String, _ n: Int) { try! Data(count: n).write(to: URL(fileURLWithPath: p(s))) }
func has(_ s: String) -> Bool { fm.fileExists(atPath: p(s)) }
func b(_ v: Bool) -> Int { v ? 1 : 0 }
let games = URL(fileURLWithPath: p("Documents/games"))
func item(_ s: String, iso: Bool = false) -> String {
    GameDeletion.removable(bootPath: p(s), isISO: iso, games: games).map { $0.lastPathComponent } ?? "nil"
}

mk("Documents/games/Disc/PS3_GAME/USRDIR"); put("Documents/games/Disc/PS3_GAME/USRDIR/EBOOT.BIN", 1000); put("Documents/games/Disc/PS3_GAME/PARAM.SFO", 24)
mk("Documents/games/Image"); put("Documents/games/Image/Image.iso", 5000)
mk("Documents/games/Pack/One/PS3_GAME"); mk("Documents/games/Pack/Two/PS3_GAME"); put("Documents/games/Pack/Two/PS3_GAME/PARAM.SFO", 8)
mk("Outside/Game/PS3_GAME"); mk("Documents/games/Linked")
try! fm.createSymbolicLink(atPath: p("Documents/games/Linked/PS3_GAME"), withDestinationPath: p("Outside/Game/PS3_GAME"))
try! fm.createSymbolicLink(atPath: p("Documents/games/Alias"), withDestinationPath: p("Outside/Game"))

print("A1 disc=\(item("Documents/games/Disc/PS3_GAME")) image=\(item("Documents/games/Image/Image.iso", iso: true)) pack=\(item("Documents/games/Pack/Two/PS3_GAME"))")
print("A2 outside=\(item("Outside/Game/PS3_GAME")) root=\(item("Documents/games")) rootGame=\(item("Documents/games/PS3_GAME")) linked=\(item("Documents/games/Linked/PS3_GAME")) alias=\(item("Documents/games/Alias")) missing=\(item("Documents/games/Nope/PS3_GAME"))")
print("A3 size=\(GameDeletion.size(of: GameDeletion.removable(bootPath: p("Documents/games/Disc/PS3_GAME"), isISO: false, games: games)!)) isoSize=\(GameDeletion.size(of: GameDeletion.removable(bootPath: p("Documents/games/Image/Image.iso"), isISO: true, games: games)!))")

try! GameDeletion.remove(GameDeletion.removable(bootPath: p("Documents/games/Disc/PS3_GAME"), isISO: false, games: games)!, games: games)
print("D1 disc=\(b(has("Documents/games/Disc"))) games=\(b(has("Documents/games"))) outside=\(b(has("Outside/Game")))")
try! GameDeletion.remove(GameDeletion.removable(bootPath: p("Documents/games/Image/Image.iso"), isISO: true, games: games)!, games: games)
print("D2 iso=\(b(has("Documents/games/Image/Image.iso"))) wrapper=\(b(has("Documents/games/Image")))")
try! GameDeletion.remove(GameDeletion.removable(bootPath: p("Documents/games/Pack/Two/PS3_GAME"), isISO: false, games: games)!, games: games)
try! GameDeletion.remove(GameDeletion.removable(bootPath: p("Documents/games/Linked/PS3_GAME"), isISO: false, games: games)!, games: games)
print("D4 linked=\(b(has("Documents/games/Linked"))) target=\(b(has("Outside/Game/PS3_GAME")))")
print("D3 two=\(b(has("Documents/games/Pack/Two"))) one=\(b(has("Documents/games/Pack/One"))) pack=\(b(has("Documents/games/Pack")))")
"""

EXPECTED = [
    "A1 disc=Disc image=Image.iso pack=Two",
    "A2 outside=nil root=nil rootGame=nil linked=Linked alias=nil missing=nil",
    "A3 size=1024 isoSize=5000",
    "D1 disc=0 games=1 outside=1",
    "D2 iso=0 wrapper=0",
    "D4 linked=0 target=1",
    "D3 two=0 one=1 pack=1",
]


class GameDeletionHost(unittest.TestCase):
    def test_removal_stays_inside_the_games_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            work = root / "build"
            work.mkdir()
            output = compile_and_run(SOURCES, DRIVER.replace("@@TMP@@", '"{0}"'.format(root)), work)
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
