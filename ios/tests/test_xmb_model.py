#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbModel.swift and XmbLayout.swift compiled on the Mac host: the pad's moves, folders, the options and choice
panels, the range editor, Square, D-pad corners, the dialog and its sounds, a rebuild that keeps the focus on the same
item and a hidden folder open, the saved place, and Android's geometry and scale."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "XmbModel.swift", IOS / "app" / "Xmb" / "XmbLayout.swift"]

DRIVER = r"""
import Foundation

func leaf(_ id: String, kind: XmbNode.Kind = .action, options: [XmbNode.Option] = []) -> XmbNode {
    XmbNode(id: id, title: id, subtitle: nil, icon: .symbol("x"), kind: kind, options: options)
}
func games(_ ids: [String]) -> [XmbNode] {
    ids.map { leaf($0, kind: .game, options: [.init(id: "play", title: "Play"), .init(id: "settings", title: "Settings")]) }
}
let setting = leaf("vsync", kind: .setting(choices: ["On", "Off"], selected: 1))
let video = leaf("video", kind: .folder([setting, leaf("scale", kind: .setting(choices: ["50%", "100%", "150%"], selected: nil))]))
func columns(_ gameIDs: [String]) -> [XmbColumn] {
    [XmbColumn(id: "users", title: "Users", icon: .symbol("person"), items: [leaf("exit")]),
     XmbColumn(id: "settings", title: "Settings", icon: .symbol("gear"), items: [video, leaf("empty", kind: .folder([]))]),
     XmbColumn(id: "game", title: "Game", icon: .symbol("pad"), items: games(gameIDs))]
}
var s = XmbState(columns: columns(["a", "b", "c", "d", "e", "f", "g"]), column: 2)
func where_(_ s: XmbState) -> String { "\(s.current!.id):\(s.focusIndex(atDepth: s.depth)):d\(s.depth):\(s.focused?.id ?? "-")" }

print("M1 \(s.move(dx: 0, dy: 1)) \(s.move(dx: 0, dy: -1)) \(s.move(dx: 0, dy: -1)) \(where_(s))")
print("M2 \(s.page(1)) \(where_(s)) \(s.page(1)) \(where_(s)) \(s.page(1))")
print("M3 \(s.move(dx: 1, dy: 0)) \(s.move(dx: -1, dy: 0)) \(where_(s)) \(s.move(dx: 1, dy: 0)) \(where_(s))")
print("M4 \(s.confirm()) \(s.openOptions()) \(String(describing: s.panel)) \(s.move(dx: 0, dy: 1)) \(s.confirm())")
_ = s.move(dx: -1, dy: 0)
print("M5 \(where_(s)) \(s.confirm()) \(where_(s)) \(s.move(dx: 1, dy: 0)) \(s.move(dx: 0, dy: 1)) \(where_(s))")
print("M6 \(s.confirm()) \(s.panel!.focus) \(s.confirm()) \(s.move(dx: 0, dy: -1)) \(s.confirm()) \(String(describing: s.panel))")
print("M7 \(s.back()) \(s.back()) \(where_(s)) \(s.move(dx: 0, dy: 1)) \(s.confirm()) \(s.back())")

_ = s.selectColumn(2)
_ = s.setFocus(3)
s.attach(columns(["new", "a", "b", "c", "d"]))
print("A1 \(where_(s))")
_ = s.selectColumn(1)
_ = s.setFocus(0)
_ = s.confirm()
_ = s.setFocus(1)
s.attach(columns(["new"]))
print("A2 \(where_(s))")
var noVideo = columns(["new"])
noVideo[1].items.removeFirst()
s.attach(noVideo)
print("A3 \(where_(s))")
func hiddenColumns(_ rows: [String]) -> [XmbColumn] {
    [XmbColumn(id: "game", title: "Game", icon: .symbol("pad"), items: [leaf("a")],
               hidden: [leaf("more", kind: .folder(rows.map { leaf($0) }))])]
}
var h = XmbState(columns: hiddenColumns(["p"]))
_ = h.open("more")
h.attach(hiddenColumns(["p", "q"]))
print("A4 \(where_(h))")

s.ask("exit", message: "Leave?", buttons: ["Yes", "No"])
print("D1 \(s.busy) \(s.move(dx: 1, dy: 0)) \(s.move(dx: 1, dy: 0)) \(s.selectColumn(0)) \(s.confirm()) \(s.busy)")
s.ask("exit", message: "Leave?", buttons: ["Yes", "No"])
print("D2 \(s.back()) \(s.back())")

var t = XmbState(columns: columns(["a", "b"]), column: 2)
func press(_ action: XmbAction) -> String {
    let outcome = t.apply(action)
    return "\(outcome)/\(t.sound(for: outcome, after: action).map { $0.rawValue } ?? "-")"
}
print("P1 \(press(.move(dx: 0, dy: 1))) \(press(.move(dx: 0, dy: 1))) \(press(.options)) \(press(.panelRow(1))) \(press(.back))")
print("P2 \(press(.column(1))) \(press(.focusAndConfirm(1))) \(press(.focusAndConfirm(0))) \(press(.back)) \(press(.focusAndOptions(0)))")

let m = XmbMetrics(width: 1280, height: 720)
print("L1 u=\(m.u) y0=\(m.itemY(0)) y1=\(m.itemY(1)) y2=\(m.itemY(2)) ym1=\(m.itemY(-1)) ym2=\(m.itemY(-2)) half=\(m.itemY(0.5))")
let phone = XmbMetrics(width: 844, height: 390)
let tall = XmbMetrics(width: 390, height: 844)
print("L2 phone u=\(String(format: "%.4f", phone.u)) focusX=\(String(format: "%.1f", phone.focusX)) tall u=\(String(format: "%.4f", tall.u)) focusX=\(String(format: "%.1f", tall.focusX)) panel=\(String(format: "%.1f", phone.panelLeft))")
print("L3 near=\(XmbMetrics.near(0.25)) far=\(XmbMetrics.far(-2)),\(XmbMetrics.far(-3)) col=\(m.columnX(3, colAnim: 2))")
print("L4 hit=\(m.hitItem(y: 340, count: 5, focus: 1, inList: false).map(String.init) ?? "nil") \(m.hitItem(y: 460, count: 5, focus: 1, inList: false).map(String.init) ?? "nil") \(m.hitItem(y: 400, count: 5, focus: 1, inList: false).map(String.init) ?? "nil") row=\(m.panelRow(y: 395, focus: 2))")
func hit(_ y: CGFloat, inList: Bool) -> String { m.hitItem(y: y, count: 5, focus: 1, inList: inList).map(String.init) ?? "nil" }
print("L5 reach \(hit(333 + 60, inList: false)) \(hit(333 + 60, inList: true)) corner \(m.inCorner(CGPoint(x: 1100, y: 700))) \(m.inCorner(CGPoint(x: 1000, y: 700))) bar \(String(format: "%.3f", m.rangeFraction(x: 911.2))) \(String(format: "%.3f", m.rangeFraction(x: 1100))) \(m.rangeFraction(x: 1244)) aside \(m.asideFrame)")

// L1 and R1: five single moves, one tick each.
var q = XmbState(columns: columns(["a", "b", "c", "d", "e", "f", "g"]), column: 2)
func pressQ(_ action: XmbAction) -> String {
    let outcome = q.apply(action)
    return "\(outcome)/\(q.sounds(for: outcome, after: action).map { $0.rawValue }.joined(separator: "+"))"
}
print("N1 \(pressQ(.page(1))) \(pressQ(.page(1))) \(pressQ(.page(1))) \(pressQ(.page(-1)))")
// Square: a game's settings with the decide sound, silent on anything else or while busy.
print("N2 \(pressQ(.square)) \(pressQ(.options)) \(pressQ(.square)) \(pressQ(.back)) \(pressQ(.column(0))) \(pressQ(.square))")
// Cross on an inert row decides; a dialog answered with any button decides and backing out cancels.
q = XmbState(columns: [XmbColumn(id: "c", title: "C", icon: .none, items: [leaf("friend", kind: .info)])])
print("N3 \(pressQ(.confirm)) ask=\(q.ask("q", message: "?", buttons: ["Yes", "No"]).rawValue) \(pressQ(.move(dx: 1, dy: 0))) \(pressQ(.confirm)) \(q.tell("t", message: "!", ok: "OK").rawValue) \(q.tell("t", message: "!", ok: "OK", error: false).rawValue) \(pressQ(.back)) \(q.ask("q", message: "?", buttons: ["Yes", "No"]).rawValue) \(pressQ(.panelRow(1)))")

// The range editor: steps both ways, clamps, a tap sets it silently, Cross keeps it, Circle keeps it too.
let volume = XmbRange(min: 0, max: 100, step: 5, value: 50, unit: "%")
let scale = XmbRange(min: 0.5, max: 2, step: 0.25, value: 1)
q = XmbState(columns: [XmbColumn(id: "c", title: "C", icon: .none,
                                 items: [leaf("volume", kind: .range(volume)), leaf("scale", kind: .range(scale))])])
print("R1 \(pressQ(.confirm)) \(pressQ(.move(dx: 1, dy: 0))) \(pressQ(.move(dx: 0, dy: 1))) \(pressQ(.move(dx: 0, dy: -1))) \(pressQ(.rangeAt(0.97))) \(pressQ(.move(dx: 1, dy: 0)))")
if case .range(_, let r)? = q.panel { print("R2 \(r.text) \(r.fraction)") }
print("R3 \(pressQ(.rangeAt(0.33))) \(pressQ(.panelRow(0))) \(q.panel == nil) \(pressQ(.confirm)) \(pressQ(.back)) \(q.panel == nil)")
print("R4 \(volume.text) \(scale.text) \(XmbRange(min: 0, max: 2, step: 0.25, value: 1.25).text)")

// A D-pad corner: the column changes at the top level; in a folder, left backs out and right moves down.
q = XmbState(columns: columns(["a", "b"]), column: 2)
print("K1 \(pressQ(.move(dx: -1, dy: 1))) \(where_(q)) \(pressQ(.confirm)) \(pressQ(.move(dx: 1, dy: 1))) \(where_(q)) \(pressQ(.move(dx: -1, dy: 1))) \(where_(q))")

// The category bar from inside a folder: closes it, quietly for the same column.
q = XmbState(columns: columns(["a"]), column: 1)
_ = q.confirm()
print("C1 d\(q.depth) \(pressQ(.column(1))) d\(q.depth) \(pressQ(.confirm)) \(pressQ(.column(0))) d\(q.depth) \(where_(q)) \(pressQ(.column(9)))")

// The place across launches, and a column's first focus.
var first = columns(["a", "b", "c"])
first[2].defaultFocus = 2
q = XmbState(columns: first)
print("S1 \(where_(q)) \(q.selectColumn(2)) \(where_(q))")
_ = q.setFocus(1)
let saved = try! JSONEncoder().encode(q.place)
var fresh = XmbState(columns: first)
fresh.restore(try! JSONDecoder().decode(XmbState.Place.self, from: saved))
print("S2 \(where_(fresh)) \(fresh.place == q.place)")
fresh.restore(XmbState.Place(column: "gone", focus: ["game": 40, "settings": -3]))
print("S3 \(where_(fresh)) \(fresh.focus(of: fresh.columns[1]))")
"""

EXPECTED = [
    "M1 moved moved refused game:0:d0:a",
    "M2 moved game:5:d0:f moved game:6:d0:g refused",
    "M3 refused moved settings:0:d0:video moved game:6:d0:g",
    'M4 launch("g") opened Optional(XmbState.Panel.options(node: "g", rows: [XmbNode.Option(id: "play", title: "Play"), '
    'XmbNode.Option(id: "settings", title: "Settings")], focus: 0)) moved option(node: "g", option: "settings")',
    "M5 settings:0:d0:video opened settings:0:d1:vsync none moved settings:1:d1:scale",
    'M6 opened 0 pick(node: "scale", choice: 0) moved opened Optional(XmbState.Panel.editor(node: "vsync", '
    'choices: ["On", "Off"], focus: 1))',
    "M7 closed closed settings:0:d0:video moved refused atRoot",
    "A1 game:4:d0:d",
    "A2 settings:1:d1:scale",
    "A3 settings:0:d0:empty",
    "A4 game:0:d1:p",
    'D1 true moved refused refused answer(dialog: "exit", button: 1) false',
    'D2 answer(dialog: "exit", button: 1) atRoot',
    'P1 moved/snd_cursor refused/- opened/snd_option option(node: "b", option: "settings")/snd_decide atRoot/snd_cancel',
    "P2 moved/snd_cursor refused/snd_error opened/snd_decide closed/snd_cancel refused/snd_error",
    "L1 u=1.0 y0=333.0 y1=453.0 y2=506.0 ym1=80.0 ym2=27.0 half=393.0",
    "L2 phone u=0.5417 focusX=203.7 tall u=0.4333 focusX=117.0 panel=582.4",
    "L3 near=0.75 far=0.5,0.0 col=513.0",
    "L4 hit=1 2 nil row=4",
    "L5 reach 1 nil corner true false bar 0.000 0.567 1.0 aside (742.4, 257.0, 486.4, 407.0)",
    "N1 moved/snd_cursor+snd_cursor+snd_cursor+snd_cursor+snd_cursor moved/snd_cursor refused/ "
    "moved/snd_cursor+snd_cursor+snd_cursor+snd_cursor+snd_cursor",
    'N2 gameSettings("b")/snd_decide opened/snd_option none/ closed/snd_cancel moved/snd_cursor none/',
    'N3 run("friend")/snd_decide ask=snd_option moved/snd_cursor answer(dialog: "q", button: 1)/snd_decide snd_error '
    'snd_system_ok answer(dialog: "t", button: 0)/snd_cancel snd_option answer(dialog: "q", button: 1)/snd_decide',
    'R1 opened/snd_decide adjusted(node: "volume", value: 55.0)/snd_cursor adjusted(node: "volume", value: 50.0)/snd_cursor '
    'adjusted(node: "volume", value: 55.0)/snd_cursor adjusted(node: "volume", value: 95.0)/ '
    'adjusted(node: "volume", value: 100.0)/snd_cursor',
    "R2 100% 1.0",
    'R3 adjusted(node: "volume", value: 35.0)/ adjusted(node: "volume", value: 35.0)/snd_decide true opened/snd_decide '
    "closed/snd_cancel true",
    "R4 50% 1 1.25",
    "K1 moved/snd_cursor settings:0:d0:video opened/snd_decide moved/snd_cursor settings:1:d1:scale closed/snd_cancel "
    "settings:0:d0:video",
    "C1 d1 none/ d0 opened/snd_decide moved/snd_cursor d0 users:0:d0:exit refused/",
    "S1 users:0:d0:exit moved game:2:d0:c",
    "S2 game:1:d0:b true",
    "S3 game:2:d0:c 0",
]


class XmbModelHost(unittest.TestCase):
    def test_navigation_and_geometry(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = compile_and_run(SOURCES, DRIVER, Path(tmp))
        self.assertEqual(output.replace("driver.", "").strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
