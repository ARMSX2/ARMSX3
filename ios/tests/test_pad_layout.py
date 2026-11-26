#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PadLayout.swift, run alone on the Mac host: an untouched layout is the default pad, motion pieces start hidden,
Default is never written, saving for one game or for all games lands where the editor said, presets and pins follow
renames and deletes, and a .armsx3pad file survives a round trip and is clamped on the way in. L3 and R3 sit beside
their stick until moved, and saved layouts that carry them read as before. It does not draw the pad."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
LAYOUT = IOS / "app" / "Controllers" / "PadLayout.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)

DRIVER = r"""
import CoreGraphics
import Foundation

func check(_ name: String, _ ok: Bool) { print("\(name) \(ok ? 1 : 0)") }

// An untouched layout is the default pad; L3 and R3 start hidden.
let empty = PadSlotLayout()
check("default-l3-hidden", empty[.l3].hidden && empty[.r3].hidden)
check("default-l1-shown", !empty[.l1].hidden && empty[.l1].centre == nil && empty[.l1].size == 1)
// Motion pieces start hidden; tilt and turn swap sides under Mirror, the rest mirror in place.
let motionPieces: [PadPiece] = [.flickUp, .flickDown, .shake, .tiltLeft, .tiltRight, .tiltForward, .tiltBack, .turnLeft, .turnRight]
check("default-motion-hidden", motionPieces.allSatisfy { empty[$0].hidden && $0.isMotion } && !PadPiece.cross.isMotion)
check("motion-partners", PadPiece.tiltLeft.partner == .tiltRight && PadPiece.turnRight.partner == .turnLeft
      && PadPiece.shake.partner == nil && PadPiece.tiltForward.partner == nil)
var touched = PadSlotLayout()
touched[.l1] = PadPlacement.standard(.l1)
check("standard-not-stored", touched.pieces.isEmpty)

// A split cluster gives way to its buttons.
var split = PadSlotLayout()
split.split.insert(.dpad)
check("split-movable", split.movable.contains(.up) && !split.movable.contains(.dpad) && split.movable.contains(.face))

// Saving for all games: Default is never written, the change goes to My Layout and becomes shared.
var moved = PadLayout()
moved.portrait[.l2] = PadPlacement(centre: CGPoint(x: 0.2, y: 0.3))
var library = PadLayoutLibrary()
library.save(moved, edited: .standard, changed: true, serial: nil, onlyThisGame: false)
check("mine-written", library.mine == moved && library.sharedChoice == .mine)
check("default-untouched", library.layout(of: .standard, serial: nil) == PadLayout())
check("game-follows-shared", library.layout(for: "BLUS30001") == moved)

// Saving for one game keeps everyone else on the shared layout.
var own = PadLayout()
own.landscape[.face] = PadPlacement(size: 1.5)
library.save(own, edited: .mine, changed: true, serial: "blus_30001", onlyThisGame: true)
check("game-own", library.choice(for: "BLUS-30001") == .game && library.layout(for: "BLUS30001 ".replacingOccurrences(of: " ", with: "")) == moved)
check("game-own-key", library.layout(for: "blus-30001") == own)
check("others-shared", library.layout(for: "BCES00001") == moved)

// An unchanged layout saved for one game pins it instead of copying.
library.save(PadLayout(), edited: .standard, changed: false, serial: "BCES00001", onlyThisGame: true)
check("pinned-default", library.choice(for: "BCES00001") == .standard && library.games["BCES00001"] == nil)

// Picking another layout for a game keeps its own, so it can be picked again.
library.pin("blus-30001", to: .standard)
check("pin-keeps-own", library.choice(for: "blus-30001") == .standard && library.games["BLUS-30001"] == own)
library.pin("blus-30001", to: .game)
check("pin-back-to-own", library.layout(for: "blus-30001") == own)
library.pin("blus-30001", to: nil)
check("pin-follows-shared", library.layout(for: "blus-30001") == moved && library.exists(.game, serial: "blus-30001"))
library.pin("blus-30001", to: .game)
library.save(moved, edited: .game, changed: false, serial: "blus-30001", onlyThisGame: false)
check("all-games-keeps-own", library.choice(for: "blus-30001") == library.sharedChoice && library.exists(.game, serial: "blus-30001"))

// Done for all games after editing Default says it writes over My Layout.
check("save-target", library.saveTarget(edited: .standard, changed: true) == .mine
      && library.saveTarget(edited: .standard, changed: false) == nil && library.saveTarget(edited: .mine, changed: true) == nil)

// Presets: names stay unique and never read like Default or My Layout; a deleted preset frees its games.
let racing = library.addPreset(named: "Racing", layout: own)
let racing2 = library.addPreset(named: "racing", layout: own)
let def = library.addPreset(named: "Default", layout: own)
check("unique-name", racing2.name == "racing 2" && def.name == "Default 2")
library.pin("BLES00002", to: .preset(racing.id))
library.shared = .preset(racing.id)
library.delete(racing.id)
check("delete-unpins", library.choice(for: "BLES00002") == .standard && library.sharedChoice == .standard)
library.rename(racing2.id, to: "  Souls  ")
check("rename-trims", library.presets.first { $0.id == racing2.id }?.name == "Souls")
check("choices-order", library.choices == [.standard, .mine, .preset(def.id), .preset(racing2.id)])

// Editing from the Layouts list does not switch every game.
library.shared = .mine
library.save(moved, edited: .preset(racing2.id), changed: true, serial: nil, onlyThisGame: false, makeShared: false)
check("layouts-edit-keeps-shared", library.sharedChoice == .mine)

// The file round-trips and is clamped on the way in.
var wild = PadLayout()
wild.portrait[.cross] = PadPlacement(centre: CGPoint(x: 3, y: -1), size: 99, touch: 0.01, opacity: 7, hidden: true)
let data = try! PadLayoutFile.encode(name: "Wild", layout: wild)
let back = try! PadLayoutFile.decode(data, fallbackName: "x.armsx3pad")
let cross = back.layout.portrait[.cross]
check("file-name", back.name == "Wild")
check("file-clamped", cross.centre == CGPoint(x: 1, y: 0) && cross.size == 4 && cross.touch == 0.5 && cross.opacity == 1 && cross.hidden)
let foreign = #"{"format":"other","version":1,"name":"x","layout":{}}"#.data(using: .utf8)!
check("file-refuses-foreign", (try? PadLayoutFile.decode(foreign, fallbackName: nil)) == nil)
check("file-name-safe", PadLayoutFile.fileName(for: "a/b:c") == "a b c.armsx3pad")

// A file from a later build, with fields missing and names this build does not know, loses only those values.
let later = #"{"format":"armsx3pad","version":1,"name":"Later","layout":{"portrait":{"pieces":{"cross":{"size":2},"wing":{"size":3}},"split":["dpad","tail"],"glow":true}}}"#
let partial = try? PadLayoutFile.decode(later.data(using: .utf8)!, fallbackName: nil)
check("file-partial", partial?.layout.portrait[.cross].size == 2 && partial?.layout.portrait[.cross].touch == 1
      && partial?.layout.portrait.split == [.dpad] && partial?.layout.landscape == PadSlotLayout())
let oldLibrary = #"{"shared":"preset:gone","pinned":{"A":"mine","B":"someday"},"mine":{}}"#
let read = try? JSONDecoder().decode(PadLayoutLibrary.self, from: oldLibrary.data(using: .utf8)!).clamped()
check("library-partial", read?.mine == PadLayout() && read?.pinned == ["A": .mine] && read?.sharedChoice == .standard)

// A skin updates the preset it added, and never a player's preset of the same name.
var skins = PadLayoutLibrary()
let players = skins.addPreset(named: "Retro", layout: PadLayout())
var skinLayout = PadLayout()
skinLayout.portrait[.l1] = PadPlacement(size: 2)
let fromSkin = skins.installSkinLayout("Retro", layout: skinLayout)
skins.rename(fromSkin.id, to: "My Retro")
let again = skins.installSkinLayout("Retro", layout: skinLayout)
check("skin-preset", again.id == fromSkin.id && skins.presets.count == 2
      && skins.presets.first { $0.id == players.id }?.layout == PadLayout())

// L3 and R3 sit on their stick's inner side, level with its middle, and follow it.
let leftStick = CGRect(x: 16, y: 600, width: 104, height: 104)
let rightStick = CGRect(x: 270, y: 600, width: 104, height: 104)
let button = CGSize(width: 40, height: 40)
check("click-spot-left", PadPiece.l3.clickSpot(beside: leftStick, size: button, gap: 6) == CGPoint(x: 146, y: 652))
check("click-spot-right", PadPiece.r3.clickSpot(beside: rightStick, size: button, gap: 6) == CGPoint(x: 244, y: 652))
check("click-spot-none", PadPiece.cross.clickSpot(beside: leftStick, size: button, gap: 6) == nil
      && PadPiece.lstick.clickSpot(beside: leftStick, size: button, gap: 6) == nil)
let raised = leftStick.offsetBy(dx: 0, dy: -100)
check("click-spot-follows", PadPiece.l3.clickSpot(beside: raised, size: button, gap: 6) == CGPoint(x: 146, y: 552))

// Saved layouts that carry L3 or R3 read as before.
func slot(_ json: String) -> PadSlotLayout { try! JSONDecoder().decode(PadSlotLayout.self, from: json.data(using: .utf8)!) }
check("saved-l3-hidden", slot("{}")[.l3].hidden && slot(#"{"pieces":{"l3":{"hidden":true}}}"#)[.l3].hidden)
check("saved-r3-shown", !slot(#"{"pieces":{"r3":{"hidden":false}}}"#)[.r3].hidden)
var shownL3 = PadSlotLayout()
shownL3[.l3] = PadPlacement(centre: CGPoint(x: 0.4, y: 0.7), hidden: false)
let shownBack = try! JSONDecoder().decode(PadSlotLayout.self, from: JSONEncoder().encode(shownL3))
check("saved-l3-roundtrip", shownBack == shownL3 && !shownBack[.l3].hidden && shownBack[.l3].centre == CGPoint(x: 0.4, y: 0.7))
shownL3[.l3] = PadPlacement.standard(.l3)
check("reset-l3-hidden-again", shownL3.pieces[.l3] == nil && shownL3[.l3].hidden)
check("l3-r3-partners", PadPiece.l3.partner == .r3 && PadPiece.r3.partner == .l3 && PadSlotLayout().movable.contains(.l3)
      && PadSlotLayout().movable.contains(.r3))

// The library itself round-trips through JSON.
let encoded = try! JSONEncoder().encode(library)
let decoded = try! JSONDecoder().decode(PadLayoutLibrary.self, from: encoded)
check("library-roundtrip", decoded == library)
"""

EXPECTED = ["default-l3-hidden", "default-l1-shown", "default-motion-hidden", "motion-partners", "standard-not-stored", "split-movable", "mine-written",
            "default-untouched", "game-follows-shared", "game-own", "game-own-key", "others-shared", "pinned-default",
            "pin-keeps-own", "pin-back-to-own", "pin-follows-shared", "all-games-keeps-own", "save-target",
            "unique-name", "delete-unpins", "rename-trims", "choices-order", "layouts-edit-keeps-shared", "file-name", "file-clamped",
            "file-refuses-foreign", "file-name-safe", "file-partial", "library-partial", "skin-preset",
            "library-roundtrip", "click-spot-left", "click-spot-right", "click-spot-none", "click-spot-follows", "saved-l3-hidden",
            "saved-r3-shown", "saved-l3-roundtrip", "reset-l3-hidden-again", "l3-r3-partners"]


class PadLayoutTest(unittest.TestCase):
    def test_library_rules_on_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([LAYOUT], DRIVER, tmp)
        results = dict(line.split(" ", 1) for line in out.splitlines() if line.strip())
        failed = [name for name in EXPECTED if results.get(name) != "1"]
        self.assertEqual(failed, [], "\n" + out)

    def test_foundation_only(self):
        imports = set(IMPORT.findall(without_comments(LAYOUT.read_text(encoding="utf-8"))))
        self.assertTrue(imports <= {"Foundation", "CoreGraphics"}, imports)


if __name__ == "__main__":
    unittest.main()
