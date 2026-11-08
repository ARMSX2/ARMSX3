# SPDX-License-Identifier: GPL-2.0-only
"""AimMath.swift compiled on its own and run on the Mac host: the touch, stick and mouse mapping,
the clamp one unit inside the bound, and the mouse buttons each aim device sends."""

import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS_DIR = Path(__file__).resolve().parents[1]

AIM = IOS_DIR / "app" / "Controllers" / "AimMath.swift"

DRIVER = r"""
import Foundation

let rect = (x: 0.0, y: 87.5, w: 400.0, h: 225.0)
let centre = AimMath.Cursor(x: AimMath.logicalWidth / 2, y: AimMath.logicalHeight / 2)
func show(_ c: AimMath.Cursor?) -> String { c.map { "\($0.x),\($0.y)" } ?? "nil" }

print("A1 centre=\(show(AimMath.touch((x: 200, y: 200), imageRect: rect)))")
print("A2 bar=\(show(AimMath.touch((x: 200, y: 20), imageRect: rect)))")
print("A3 stick=\(show(AimMath.stick(centre, x: 1, y: 0, unitsPerSecond: 1000, dt: 2)))")
print("A4 mouse=\(show(AimMath.mouse(centre, dx: 10, dy: -5)))")
let nanStick = AimMath.stick(centre, x: .nan, y: 0, unitsPerSecond: 1000, dt: 2)
let infTouch = AimMath.touch((x: .infinity, y: 200), imageRect: rect)
print("AN stick=\(show(nanStick)) touch=\(show(infTouch))")
print("A5 corner=\(show(AimMath.touch((x: 400, y: 312.5), imageRect: rect)))")
let r2 = AimMath.topShotTrigger, l2 = AimMath.topShotSecond
func mb(_ d: Int, _ m: UInt32, _ p: Bool) -> Int { AimMath.mouseButtons(device: d, padMask: m, pen: p) }
print("A6 elite_r2=\(mb(12, r2, false)) elite_l2=\(mb(12, l2, false)) elite_both=\(mb(12, r2 | l2, false)) "
      + "fear_both=\(mb(13, r2 | l2, false)) guncon=\(mb(11, r2 | l2, true)) pen=\(mb(14, 0, true)) "
      + "penup=\(mb(14, 0, false)) tablet_r2=\(mb(14, r2, false))")
"""

# The edge is 1279: x == 1280 wraps GunCon 3's int16 to the far left.
HOST_LINES = ["A1 centre=640,360", "A2 bar=nil", "A3 stick=1279,360", "A4 mouse=650,355", "AN stick=640,360 touch=nil",
              "A5 corner=1279,719",
              "A6 elite_r2=1 elite_l2=2 elite_both=3 fear_both=3 guncon=0 pen=1 penup=0 tablet_r2=0"]


class TheAimHolds(unittest.TestCase):
    """AimMath.swift alone, compiled and run on the Mac host.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            cls.out = compile_and_run([AIM], DRIVER, tmp)
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("A")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_touch_stick_and_mouse_aim(self):
        self.assertEqual(HOST_LINES, self.lines, self.out)


if __name__ == "__main__":
    unittest.main()
