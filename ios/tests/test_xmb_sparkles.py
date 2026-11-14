#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbSparkleField.swift compiled on the Mac host: the random stream against Kotlin's xorshift worked here, a field
that is the same for the same seed, its size and bounds after 300 steps on a flat wave, no field without a wave,
what wind and a shake do to it, the motion response and the wind's numbers, and the PARTICLES.mnu and swirl
readers on made-up bytes."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "Wave" / "XmbSparkleField.swift"]

DRIVER = r"""
import Foundation

func f(_ v: Float) -> String { String(format: "%.4f", v) }

var random = XmbSparkleRandom()
print("R \((0 ..< 4).map { _ in String(Int(random.next() * Float(1 << 24))) }.joined(separator: " "))")

// A flat wave at z = -6.6, x -4 to 4 and y -0.95 to -0.15, in clip space as the wave model writes it.
let scale = XmbSparkleField.scale(width: 1920, height: 1080)
var wave = [Float]()
for iu in 0 ..< 19 {
    for iv in 0 ..< 19 {
        let x = -4.5 + 0.5 * Float(iu), y = -1 + 0.05 * Float(iv), w: Float = 8.6
        wave += [scale.x * x, scale.y * y, 1.0002 * w - 0.2, w]
    }
}
func field(seed: UInt64 = 0x2545_F491, steps: Int = 300, windX: Float = 0, scatter: Float = 0) -> [Float] {
    var field = XmbSparkleField(seed: seed)
    field.setWave(wave, scaleX: scale.x, scaleY: scale.y)
    for _ in 0 ..< steps / 6 { field.step(0.1, windX: 0, windY: 0, scatter: 0) }
    field.step(0.1, windX: windX, windY: 0, scatter: scatter)
    var out = [Float](repeating: 0, count: XmbSparkleField.capacity * XmbSparkleField.floatsPerFlake)
    let written = out.withUnsafeMutableBufferPointer { field.writeInstances(to: $0) }
    return Array(out[..<(written * XmbSparkleField.floatsPerFlake)])
}
func flakes(_ a: [Float]) -> [ArraySlice<Float>] { stride(from: 0, to: a.count, by: 8).map { a[$0 ..< $0 + 8] } }
/// The mean distance in x and in y between the same flakes of two fields.
func apart(_ a: [Float], _ b: [Float]) -> (Float, Float) {
    let n = Float(a.count / 8)
    var x: Float = 0, y: Float = 0
    for i in stride(from: 0, to: a.count, by: 8) { x += b[i] - a[i]; y += abs(b[i + 1] - a[i + 1]) }
    return (x / n, y / n)
}

let still = field()
print("S1 scale=\(f(scale.x)),\(f(scale.y)) same=\(still == field()) other=\(still == field(seed: 7))")
let inside = flakes(still).allSatisfy { p in
    let i = p.startIndex
    let turn = (p[i + 4] * p[i + 4] + p[i + 5] * p[i + 5] + p[i + 6] * p[i + 6] + p[i + 7] * p[i + 7]).squareRoot()
    return abs(p[i]) < 6 && p[i + 1] > -3.5 && p[i + 1] < 2.5 && p[i + 2] > -9 && p[i + 2] < -4
        && p[i + 3] >= 0 && p[i + 3] <= 1 && abs(turn - 1) < 1e-4
}
print("S2 count=\(still.count / 8) inside=\(inside)")

var none = XmbSparkleField()
none.step(1, windX: 0, windY: 0, scatter: 0)
none.setWave([0, 0, 0], scaleX: 1, scaleY: 1)
none.setWave([Float](repeating: 0, count: 19 * 19 * 4), scaleX: 1, scaleY: 1)
none.step(1, windX: 0, windY: 0, scatter: 0)
print("S3 count=\(none.count)")

// Six steps of a wind of 1 a second carry every flake a tenth; a shake leaves them where they were, jolted.
let blown = apart(still, field(windX: 1))
let shaken = apart(still, field(scatter: 1))
print("W1 blown=\(f(blown.0)),\(f(blown.1)) shaken=\(abs(shaken.0) < 0.001),\(shaken.1 > 0.005 && shaken.1 < 0.05)")

var wind = XmbSparkleWind()
wind.step(1.0 / 30, push: 1, shake: 0.5)
let first = (wind.velocity, wind.scatter)
wind.step(1, push: 0, shake: 0)
let later = (wind.velocity, wind.scatter)
for _ in 0 ..< 60 { wind.step(0.1, push: -3, shake: 0) }
print("W2 \(f(first.0)) \(f(first.1)) \(f(later.0)) \(f(later.1)) \(f(wind.velocity))")

var source = XmbSparkleMotionSource()
source.fused(gravityX: 0, linearX: 0.55, linearMagnitude: 0.55)
let swing = (source.push, source.shake)
source.fused(gravityX: 0.3, linearX: -0.04, linearMagnitude: 1.12)
let tip = (source.push, source.shake)
source = XmbSparkleMotionSource()
source.raw(x: 0.3, magnitude: 1, time: 10)
let rest = source.push
source.raw(x: 1.3, magnitude: 1.64, time: 10.02)
print("M1 \(f(swing.0)) \(f(swing.1)) \(f(tip.0)) \(f(tip.1)) \(f(rest)) \(f(source.push)) \(f(source.shake))")

let mnu = "#MNU_1.0\r\nemit per frame:float:4\r\ngravity:float:-6.8e-05\nfriction:int:1\nemit prob:text:9\n"
    + "aging speed:float:fast\nbrownian scale:float\n spin time scale:float:3.5 \nglare:float:0.2\ndelta time:float:inf\n"
let read = XmbSparkleField.Settings(mnu: Array(mnu.utf8))
let stock = XmbSparkleField.Settings()
print("P1 \(read.emitPerFrame) \(read.gravity) \(read.friction) \(read.spinTimeScale) \(read.emitProb == stock.emitProb) "
    + "\(read.agingSpeed == stock.agingSpeed) \(read.brownian == stock.brownian) \(read.deltaTime == stock.deltaTime) "
    + "\(XmbSparkleField.Settings(mnu: [0xff, 0x00, 0x3a]) == stock)")

func tga(side: Int, bits: Int, id: Int = 0, type: UInt8 = 2) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 18 + id)
    bytes[0] = UInt8(id); bytes[2] = type; bytes[16] = UInt8(bits)
    bytes[12] = UInt8(side & 0xff); bytes[13] = UInt8(side >> 8); bytes[14] = bytes[12]; bytes[15] = bytes[13]
    for texel in 0 ..< side * side { bytes += [UInt8(texel & 0xff), 2, 3, 4].prefix(bits / 8) }
    return bytes
}
let swirl = XmbSparkleSwirl.rgba(tga: tga(side: 128, bits: 24, id: 3))
let withAlpha = XmbSparkleSwirl.rgba(tga: tga(side: 128, bits: 32))
print("T1 \(swirl?.count ?? -1) \(swirl.map { Array($0[20 ..< 24]) } ?? []) \(withAlpha.map { Array($0[20 ..< 24]) } ?? []) "
    + "\(XmbSparkleSwirl.rgba(tga: tga(side: 64, bits: 24)) == nil) \(XmbSparkleSwirl.rgba(tga: tga(side: 128, bits: 24, type: 10)) == nil) "
    + "\(XmbSparkleSwirl.rgba(tga: Array(tga(side: 128, bits: 24).dropLast())) == nil) \(XmbSparkleSwirl.rgba(tga: [0, 0, 2]) == nil)")
"""


def kotlin_random(count):
    """XmbParticles.kt's rand(): a 64-bit xorshift from its seed, the top 24 bits of each state."""
    state, mask, out = 0x2545F491, (1 << 64) - 1, []
    for _ in range(count):
        state ^= (state << 13) & mask
        state ^= state >> 7
        state ^= (state << 17) & mask
        out.append(str(state >> 40))
    return " ".join(out)


EXPECTED = [
    "R " + kotlin_random(4),
    "S1 scale=1.1282,2.0057 same=true other=false",
    "S2 count=2042 inside=true",
    "S3 count=0",
    "W1 blown=0.0994,0.0000 shaken=true,true",
    "W2 0.0389 0.5000 0.0175 0.0410 -0.2000",
    "M1 0.5000 0.0000 -0.1000 0.5000 -0.1000 0.7347 0.2539",
    "P1 4.0 -6.8e-05 1.0 3.5 true true true true true",
    "T1 65536 [3, 2, 5, 255] [3, 2, 5, 255] true true true true",
]


class XmbSparklesHost(unittest.TestCase):
    def test_field_motion_and_readers(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = compile_and_run(SOURCES, DRIVER, Path(tmp))
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
