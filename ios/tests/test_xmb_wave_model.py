#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbWaveModel.swift and XmbOriginalColors.swift compiled on the Mac host: the springs are deterministic from a
state and stay finite and in view, the console's noise, the .mnu and strand readers on made-up files, the night
curve, a month's gradient, the cycle's blend and the gradient's direction."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
WAVE = IOS / "app" / "Xmb" / "Wave"
SOURCES = [WAVE / "XmbWaveModel.swift", WAVE / "XmbOriginalColors.swift"]

DRIVER = r"""
import Foundation

func f(_ x: Float) -> String { String(format: "%.4f", x) }
func v(_ x: SIMD3<Float>) -> String { "\(f(x.x)),\(f(x.y)),\(f(x.z))" }

// Straight lines at rest, as bytes: the positions, then the velocities.
var state: [UInt8] = []
func put(_ x: Float) { withUnsafeBytes(of: x.bitPattern.littleEndian) { state.append(contentsOf: $0) } }
for row in 0 ..< 19 { for column in 0 ..< 19 { put(Float(18 - column) * 0.33); put(Float(row) * 0.02); put(0.1) } }
for _ in 0 ..< 361 * 3 { put(0) }

func run(_ model: XmbWaveModel, frames: Int) -> (points: [Float], ok: Bool) {
    var points = [Float](repeating: 0, count: XmbWaveModel.count * 4)
    var ok = true
    for _ in 0 ..< frames {
        model.update(dt: 1 / 30, aspect: 16 / 9, into: &points)
        for i in 0 ..< XmbWaveModel.count {
            let x = points[i * 4], y = points[i * 4 + 1], z = points[i * 4 + 2], w = points[i * 4 + 3]
            // In front of the camera, between its near and far planes, and no further off screen than the band is long.
            if !(x.isFinite && y.isFinite && z.isFinite && w > 1 && w < 30 && abs(z) < w && abs(x / w) < 6 && abs(y / w) < 3) { ok = false }
        }
    }
    return (points, ok)
}
let a = run(XmbWaveModel(state: state, seed: 7), frames: 300)
let b = run(XmbWaveModel(state: state, seed: 7), frames: 300)
let c = run(XmbWaveModel(state: state, seed: 8), frames: 300)
let once = run(XmbWaveModel(state: state, seed: 7), frames: 1)
print("W1 same=\(a.points == b.points) seeded=\(a.points != c.points) moves=\(a.points != once.points) ok=\(a.ok && c.ok)")
// A state that is too short, or holds a value that is no number, counts as none: the lines settle from straight.
var damaged = state
damaged.replaceSubrange(40 ..< 44, with: [0, 0, 0xC0, 0x7F])
print("W2 short=\(run(XmbWaveModel(state: [1, 2, 3], seed: 7), frames: 60).ok) damaged=\(run(XmbWaveModel(state: damaged, seed: 7), frames: 60).ok) none=\(run(XmbWaveModel(state: nil, seed: 7), frames: 60).ok)")
print("W3 \(XmbWaveModel.noise(1)) \(XmbWaveModel.noise(2)) \(XmbWaveModel.noise(123456789)) \(XmbWaveModel.noise(-5))")
let strands = XmbWaveModel.fallbackStrands()
print("W4 \(strands.count) \(f(strands[0])) \(f(strands[29])) \(f(strands[511]))")

let mnu = "# made up\r\nDAMPING:float:0.5\nTIMESTEP:int:3\n  POS X:float:-1.25  \nNAME:string:abc\nBAD:float:xyz\nNAN:float:nan\n"
    + "TOO:many:float:1\nFFD SCALE2 Z:float:2\nDAMPING:float:0.25\n"
let parsed = XmbWaveFirmware.parseMnu(Array(mnu.utf8))
print("F1 \(parsed.keys.sorted()) \(parsed["DAMPING"]!) \(parsed["TIMESTEP"]!) \(parsed["POS X"]!)")
// A 4x1 picture, three bytes a texel in blue, green, red order, after a 2-byte id.
var tga = [UInt8](repeating: 0, count: 18)
tga[0] = 2; tga[12] = 4; tga[14] = 1; tga[16] = 24
tga += [9, 9] + [1, 2, 0, 1, 2, 51, 1, 2, 102, 1, 2, 255]
let firmware = XmbWaveFirmware(files: ["LINE1.mnu": Array(mnu.utf8), "BACKGROUND.mnu": Array("3 RED:float:0.1\n4 BLUE:float:0.2\n".utf8),
                                       "textures/TGA/freslut1.tga": tga])
print("F2 \(firmware.line.damping) \(firmware.line.timestep) \(firmware.line.pos.x) \(firmware.line.pos.y) \(firmware.line.scale2.z) \(firmware.line.length)")
print("F3 \(v(firmware.colour3)) \(v(firmware.colour4))")
print("F4 \(firmware.strands.count) \(f(firmware.strands[0])) \(f(firmware.strands[1])) \(f(firmware.strands[2])) \(f(firmware.strands[3])) \(f(firmware.strands[511]))")
tga.removeLast()
let none = XmbWaveFirmware(files: ["textures/TGA/freslut1.tga": tga])
print("F5 \(none.strands == strands) \(none.line == XmbWaveModel.Line()) \(v(none.colour3))")

print("N1 \(f(XmbOriginalColors.night(3))) \(f(XmbOriginalColors.night(5.25))) \(f(XmbOriginalColors.night(12))) \(f(XmbOriginalColors.night(23.5)))")
let april = XmbOriginalColors.at(month: 3, hour: 12)
let small = XmbOriginalColors.at(month: 3, hour: 3)
print("C1 \(april.angleDeg) \(v(april.start)) \(v(april.end)) night \(v(small.start)) \(v(small.end))")
var utc = Calendar(identifier: .gregorian)
utc.timeZone = TimeZone(identifier: "UTC")!
// 12:01 on the 15th of October 2026, 77.5 s into a cycle: half way through easing from April into May.
let date = Date(timeIntervalSince1970: 1_792_065_677.5)
let follows = XmbOriginalColors.at(date, themeColor: -1, calendar: utc)
let pinned = XmbOriginalColors.at(date, themeColor: 0, calendar: utc)
let cycle = XmbOriginalColors.at(date, themeColor: XmbOriginalColors.cycle, calendar: utc)
print("C2 \(follows.angleDeg) \(v(follows.start)) \(pinned.angleDeg) \(v(pinned.end))")
print("C3 \(cycle.angleDeg) \(v(cycle.start)) \(v(cycle.end))")
let held = XmbOriginalColors.at(Date(timeIntervalSince1970: 1_792_065_650), themeColor: 12, calendar: utc)
print("C4 \(held == XmbOriginalColors.at(month: 2, hour: 12))")
let right = XmbOriginalColors.shaderDirection(angleDeg: 90, width: 1920, height: 1080)
let down = XmbOriginalColors.shaderDirection(angleDeg: 180, width: 1920, height: 1080)
let slant = XmbOriginalColors.shaderDirection(angleDeg: 45, width: 1000, height: 1000)
print("D1 \(f(right.dir.x)),\(f(right.dir.y)),\(f(right.tMin)) \(f(down.dir.x)),\(f(down.dir.y)),\(f(down.tMin)) \(f(slant.dir.x)),\(f(slant.dir.y)),\(f(slant.tMin))")

// The song's push moves the lattice and keeps the wave in view; a push of 0 leaves it as the XMB's own.
let pushed = XmbWaveModel(state: state, seed: 7)
pushed.drive = 1
let still = XmbWaveModel(state: state, seed: 7)
still.drive = 0
let p1 = run(pushed, frames: 300), p0 = run(still, frames: 300)
print("W5 differs=\(p1.points != p0.points) ok=\(p1.ok) rest=\(p0.points == a.points)")
let moodFiles: [String: [UInt8]] = [
    "LINE1.mnu": Array("TIMESTEP:float:4\nLENGTH:float:0.5\n".utf8),
    "override/music_1/LINE1.mnu": Array("TIMESTEP:float:3.5\n".utf8),
    "BACKGROUND.mnu": Array("1 RED:float:0.2\n".utf8),
    "override/music_1/BACKGROUND.mnu": Array("COLOUR SHADER:int:1\n2 BLUE:float:0.3\n".utf8),
    "override/music_1/HDR.mnu": Array("EXPOSURE:float:1.5\nGLARE SUM POW:float:0.7\n".utf8),
    "override/black/HDR.mnu": Array("EXPOSURE:float:9\n".utf8),
]
let mood = XmbWaveFirmware.mood("music_1", files: moodFiles)
print("M1 \(mood.line.timestep) \(mood.line.length) \(f(mood.colours[0].x)) \(f(mood.colours[1].z)) \(f(mood.colours[3].y)) \(mood.colourShader) \(mood.hdr.exposure) \(mood.hdr.white) \(mood.hdr.glareSumPow)")
let plain = XmbWaveFirmware.mood("music_1", files: [:])
print("M2 \(plain.line == XmbWaveModel.Line()) \(plain.colourShader) \(plain.hdr == XmbWaveFirmware.Hdr()) \(v(plain.colours[2]))")
"""


def noise(n0):
    """The Kotlin's 32-bit arithmetic, for the expected values."""
    n0 &= 0xFFFFFFFF
    n = ((n0 << 13) ^ n0) & 0xFFFFFFFF
    t = (n * ((n * n * 15731 + 789221) & 0xFFFFFFFF) + 1376312589) & 0x7FFFFFFF
    return 1.0 - t / 1073741824.0


EXPECTED = [
    "W1 same=true seeded=true moves=true ok=true",
    "W2 short=true damaged=true none=true",
    "F1 [\"DAMPING\", \"FFD SCALE2 Z\", \"POS X\", \"TIMESTEP\"] 0.25 3.0 -1.25",
    "F2 0.25 3.0 -1.25 -1.08844 2.0 0.306001",
    "F3 0.1000,0.8462,0.8464 0.9249,0.9226,0.2000",
    "F4 512 0.0000 0.2000 0.4000 1.0000 1.0000",
    "F5 true true 0.8473,0.8462,0.8464",
    "N1 1.0000 0.5000 0.0000 0.5000",
    "C1 136.75 0.8471,0.7137,0.7137 0.9059,0.2588,0.4588 night 0.0847,0.0714,0.0714 0.2718,0.0776,0.1376",
    "C2 160.0 0.6784,0.7020,0.1529 90.25 0.7882,0.7882,0.7882",
    "C3 69.125 0.4608,0.5686,0.3941 0.5000,0.4353,0.2765",
    "C4 true",
    "D1 1.0000,-0.0000,0.0000 0.0000,1.0000,0.0000 0.5000,-0.5000,-0.5000",
    "W5 differs=true ok=true rest=true",
    "M1 3.5 0.5 0.2000 0.3000 1.0000 1 1.5 0.899181 0.7",
    "M2 true 0 true 1.0000,1.0000,1.0000",
]


class XmbWaveModelHost(unittest.TestCase):
    def test_wave_and_colours(self):
        with tempfile.TemporaryDirectory() as tmp:
            lines = compile_and_run(SOURCES, DRIVER, Path(tmp)).strip().splitlines()
        noises = [line for line in lines if line.startswith("W3 ")]
        self.assertEqual(len(noises), 1)
        for got, n in zip(noises[0].split()[1:], [1, 2, 123456789, -5]):
            self.assertAlmostEqual(float(got), noise(n), places=6)
        strands = [line for line in lines if line.startswith("W4 ")]
        self.assertEqual(strands, ["W4 512 0.0000 0.9880 0.0000"])
        self.assertEqual([line for line in lines if line[:2] not in ("W3", "W4")], EXPECTED)


if __name__ == "__main__":
    unittest.main()
