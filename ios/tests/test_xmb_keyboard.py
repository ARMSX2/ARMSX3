#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbKeyboard.swift's model compiled on the Mac host: the key grid and its edges, typing a word with the pad,
shift, backspace, clear, paste, the field's typing, touch, done and cancel."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "XmbModel.swift", IOS / "app" / "Xmb" / "XmbKeyboard.swift"]

DRIVER = r"""
import Foundation

var k = XmbKeyboard(title: "Name")
func at(_ k: XmbKeyboard) -> String { "\(k.row),\(k.column):\(k.focused)" }
func pad(_ actions: XmbAction...) -> String { actions.map { "\(k.apply($0))" }.joined(separator: " ") }
let up = XmbAction.move(dx: 0, dy: -1), down = XmbAction.move(dx: 0, dy: 1)
let left = XmbAction.move(dx: -1, dy: 0), right = XmbAction.move(dx: 1, dy: 0)

print("G1 rows=\(XmbKeyboard.rows.count) keys=\(XmbKeyboard.rows.map { $0.count }) last=\(XmbKeyboard.rows.map { $0.last! })")
print("E1 \(pad(up, left)) \(at(k))")
print("W1 \(pad(down, down, right, right, right, right, right, .confirm)) \(at(k))")
print("W2 \(pad(up, right, right, .confirm)) \(at(k)) text=\(k.text)")
print("S1 \(pad(down, right, right, right, right)) \(at(k)) \(pad(.confirm)) shifted=\(k.shifted)")
print("S2 \(k.apply(.key(row: 1, column: 0))) \(at(k)) text=\(k.text) \(k.typed("a")) \(k.typed("1"))")
print("S3 \(k.apply(.press(.shift))) shifted=\(k.shifted) \(k.typed("a"))")
print("B1 \(pad(.options)) text=\(k.text) \(k.apply(.press(.space))) \(k.apply(.key(row: 4, column: 0))) text=[\(k.text)]")
print("B2 \(k.apply(.press(.clear))) text=[\(k.text)] \(k.apply(.press(.backspace))) text=[\(k.text)]")
print("P1 \(k.apply(.paste(" AB\r\n  12 \n"))) text=[\(k.text)] \(k.apply(.field("x\ny"))) text=[\(k.text)]")
print("E2 \(k.apply(.key(row: 4, column: 10))) \(at(k)) \(pad(down, right, .move(dx: 1, dy: 1)))")
print("D1 \(pad(.confirm)) \(pad(.back)) \(k.apply(.cancel)) text=\(k.text)")
print("R1 \(pad(.page(1), .page(-1), .column(2), .focus(1), .panelRow(0))) \(k.apply(.key(row: 5, column: 0))) \(k.apply(.key(row: 0, column: 11)))")

var secret = XmbKeyboard(title: "Password", text: "abc", secure: true)
print("I1 \(secret.title) \(secret.secure) \(at(secret)) \(secret.apply(XmbAction.options)) \(secret.apply(.press(.done)))")
"""

EXPECTED = [
    "G1 rows=5 keys=[11, 11, 11, 11, 11] last=[XmbKeyboard.Key.backspace, XmbKeyboard.Key.space, "
    "XmbKeyboard.Key.shift, XmbKeyboard.Key.clear, XmbKeyboard.Key.done]",
    'E1 refused refused 0,0:character("1")',
    'W1 moved moved moved moved moved moved moved typed 2,5:character("h")',
    'W2 moved moved moved typed 1,7:character("i") text=hi',
    "S1 moved moved moved moved refused 2,10:shift typed shifted=true",
    'S2 typed 1,0:character("q") text=hiQ A 1',
    "S3 typed shifted=false a",
    "B1 typed text=hi typed typed text=[hi @]",
    "B2 typed text=[] typed text=[]",
    "P1 typed text=[AB 12] edited text=[xy]",
    "E2 done(\"xy\") 4,10:done refused refused refused",
    'D1 done("xy") cancelled cancelled text=xy',
    "R1 refused refused refused refused refused refused refused",
    'I1 Password true 0,0:character("1") typed done("ab")',
]


class XmbKeyboardHost(unittest.TestCase):
    def test_typing(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = compile_and_run(SOURCES, DRIVER, Path(tmp))
        self.assertEqual(output.replace("driver.", "").strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
