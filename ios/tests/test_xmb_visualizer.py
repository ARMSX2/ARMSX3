#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The music visualizers' pure parts compiled on the Mac host: a song's levels and the console's spectrum from a made-up
tone, the cache file, the music's push, the fovy for a narrow screen, the chooser's order, java.util.Random, the glare
weights, Valley's pack, presets and terrain, and Gaia's camera paths, presets, sun picture, tour and air table, all on
made-up files."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
VIS = APP / "Xmb" / "Visualizer"
SOURCES = [VIS / "XmbVisualizerLevels.swift", VIS / "XmbValley.swift", VIS / "XmbGaia.swift",
           APP / "Xmb" / "Wave" / "XmbWaveModel.swift"]

DRIVER = r"""
import Foundation

func f(_ x: Float) -> String { String(format: "%.4f", x) }
func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }

// Two seconds at 48 kHz: a 1 kHz tone on the left for the second one only, 6 kHz on the right all through.
let rate = 48_000
var samples: [Float] = []
for i in 0 ..< rate * 2 {
    let t = Double(i) / Double(rate)
    samples.append(Float(sin(2 * Double.pi * 1000 * t) * 0.5 * (t >= 1 ? 1 : 0)))
    samples.append(Float(sin(2 * Double.pi * 6000 * t) * 0.5))
}
let analyzer = XmbVisualizerAnalyzer(rate: rate, channels: 2)
samples.withUnsafeBufferPointer { analyzer.feed($0) }
let levels = analyzer.levels()!
let mid = levels.frames / 2
let left = (0 ..< 64).max { levels.column(mid * 2, $0) < levels.column(mid * 2, $1) }!
let right = (64 ..< 128).max { levels.column(mid * 2, $0) < levels.column(mid * 2, $1) }!
print("L1 frames=\(levels.frames) spectrum=\(levels.spectrumFrames) b8=\(Int(levels.band(10, 8) * 255)),\(Int(levels.band(50, 8) * 255)) b13=\(Int(levels.band(10, 13) * 255)),\(Int(levels.band(50, 13) * 255)) b2=\(Int(levels.band(50, 2) * 255)) left=\(left) right=\(right) top=\(f(levels.column(mid * 2, left)))")
print("L2 \(levels.band(-1, 0)) \(levels.band(levels.frames, 0)) \(levels.column(levels.spectrumFrames, 3)) \(XmbVisualizerAnalyzer(rate: rate, channels: 1).levels() == nil)")
let file = levels.encoded()
var damaged = file
damaged[0] = 0
print("L3 \(file.count) same=\(XmbVisualizerLevels(cache: file) == levels) short=\(XmbVisualizerLevels(cache: Array(file.dropLast())) == nil) magic=\(XmbVisualizerLevels(cache: damaged) == nil) \(file[0 ..< 8].map { String($0) }.joined(separator: ","))")

// The push: quiet, then the bass up, then the song paused.
var data = [UInt8](repeating: 0, count: 90 * 16)
for frame in 30 ..< 60 { for b in 0 ..< 4 { data[frame * 16 + b] = 255 } }
let song = XmbVisualizerLevels(frames: 90, data: data, spectrumFrames: 0, spectrum: [])
var drive = XmbMusicDrive()
var trace: [String] = []
for ms in stride(from: 0, to: 3000, by: 100) { trace.append(f(drive.next(song, positionMs: ms, playing: true))) }
let paused = drive.next(song, positionMs: 1500, playing: false)
var none = XmbMusicDrive()
print("D1 \(trace[0]) \(trace[10]) \(trace[11]) \(trace[19]) \(trace[29]) \(f(paused)) \(f(none.next(nil, positionMs: 0, playing: true)))")

print("F1 \(f(xmbFovy(65, aspect: 16 / 9))) \(f(xmbFovy(65, aspect: 2.2))) \(f(xmbFovy(65, aspect: 4 / 3))) \(f(xmbFovy(120, aspect: 0.3)))")
print("K1 \(XmbVisualizerKind.allCases.map(\.rawValue)) \(XmbVisualizerKind.waves.stepped(-1)) \(XmbVisualizerKind.lines.stepped(1)) \(XmbVisualizerKind.valley.stepped(1)) \(XmbVisualizerKind.preferenceKey)")

var r42 = XmbJavaRandom(42)
var r0 = XmbJavaRandom(0)
var r1 = XmbJavaRandom(1)
print("R1 \(r42.next(32)) \(r0.next(32)) \(r1.nextInt(256)) \(r1.nextInt(256)) \(r1.nextInt(100))")

let weights = XmbGlare.gaussian(SIMD3(2, 2.2, 0.2))
let shares = XmbGlare.levelWeights(sumPow: 0.738001, level: 2.46, count: 6)
let total = weights[0] + 2 * weights[1 ... 7].reduce(SIMD3<Float>.zero, +)
print("G1 \(f(total.x)) \(f(total.y)) \(f(total.z)) \(f(weights[0].x)) \(f(weights[5].x)) \(f(weights[6].x)) \(f(weights[1].z)) \(f(shares.reduce(0, +))) \(f(shares[0]))")

// Valley's pack: two Blue presets and a Volcano, base menus with the presets' own on top, a 2x2 normal map with a
// 1x1 level after it.
var dds = [UInt8](repeating: 0, count: 128)
func put(_ at: Int, _ v: Int) { for k in 0 ..< 4 { dds[at + k] = UInt8((v >> (8 * k)) & 0xff) } }
dds.replaceSubrange(0 ..< 4, with: bytes("DDS "))
put(12, 2); put(16, 2); put(28, 2); put(88, 32); put(92, 0xff0000); put(96, 0xff00); put(100, 0xff)
dds += [1, 2, 3, 4] + [5, 6, 7, 8] + [9, 10, 11, 12] + [13, 14, 15, 16] + [17, 18, 19, 20]
var canyon: [String: [UInt8]] = [
    "canyon/CANYON_CAMERA.mnu": bytes("POS SPEED:float:30\nZOOM:float:70\n"),
    "canyon/CANYON_COLOUR.mnu": bytes("COLOUR R:float:2\nFADE COLOUR:float:1\nCOLOUR SCALE:float:1\n"),
    "override/Blue01/canyon/CANYON_COLOUR.mnu": bytes("COLOUR R:float:3\n"),
    "override/Blue02/canyon/CANYON_CAMERA.mnu": bytes("ZOOM:float:50\n"),
    "override/Volcano03/canyon/CANYON_FOG.mnu": bytes("FOG R:float:0.5\n"),
    "override/Bir_A01/canyon/CANYON_FOG.mnu": bytes("FOG R:float:0.9\n"),
    "override/stray": bytes("x"),
    "canyon/textures/DDS/canyon_normalmap.dds": dds,
]
let pack = XmbValleyFirmware.pack(files: canyon)!
let blue1 = pack.params["Blue01"]!, blue2 = pack.params["Blue02"]!
print("V1 \(pack.groups.map { "\($0.name):\($0.presets.joined(separator: "+")):\($0.stay)" }) \(pack.params.keys.sorted())")
print("V2 \(blue1.colour.x) \(blue2.colour.x) \(blue1.zoom) \(blue2.zoom) \(blue1.posSpeed) \(pack.params["Volcano03"]!.fog.x) \(blue1.lineAhead) \(blue1.targetZ)")
print("V3 \(pack.normalMap.map { "\($0.width)x\($0.height)" }) \(pack.normalMap[0].rgba[0 ..< 8].map { String($0) }.joined(separator: ",")) \(pack.normalMap[1].rgba.map { String($0) }.joined(separator: ","))")
var bad = dds
// The red mask is 0xff0000, so its third byte.
bad[94] = 0
canyon["canyon/textures/DDS/canyon_normalmap.dds"] = bad
print("V4 \(XmbValleyFirmware.pack(files: canyon)!.normalMap.isEmpty) \(XmbValleyFirmware.dds(Array(dds.prefix(140))).count) \(XmbValleyFirmware.pack(files: ["override/Bir_A01/canyon/CANYON_FOG.mnu": bytes("FOG R:float:1\n")]) == nil)")
let lut = XmbValleyTour.fresLut(blue1)
print("V5 \(lut.count) \(f(lut[0])) \(f(lut[3])) \(f(lut[256 * 4])) \(f(lut[511 * 4]))")

// The tour: Blue's two presets take turns as each one's time runs out, a flick up or down goes to the other group.
let tour = XmbValleyTour(pack: pack, group: 9, preset: 0)
let first = (tour.group, tour.preset, tour.started)
var input = [Float](repeating: 0, count: 128)
for c in 0 ..< 128 { input[c] = Float(c % 7) / 2 }
for _ in 0 ..< 60 * 21 { tour.tick(input, flick: 0, step: 1 / 60) }
let timedOut = (tour.group, tour.preset, tour.started)
tour.tick(input, flick: 2, step: 1 / 60)
let flicked = (tour.group, tour.preset, tour.started)
tour.tick(input, flick: 4, step: 1 / 60)
let grouped = (tour.group, tour.preset, tour.started)
for _ in 0 ..< 600 { tour.tick(input, flick: 0, step: 1 / 60) }
let scene = tour.scene
print("V6 \(first) \(timedOut) \(flicked) \(grouped) finite=\(scene.vertices.allSatisfy(\.isFinite)) travelled=\(f(scene.travelled)) newest=\(scene.newest)")
let fresh = XmbValleyScene()
fresh.start(blue1)
print("V7 \(f(fresh.vertices[0])) \(f(fresh.vertices[9])) \(f(fresh.vertices[(5 * 128 + 64) * 11 + 9])) \(f(fresh.vertices[5])) \(f(fresh.vertices[6])) \(f(XmbWaveModel.noise(1) * 500)) \(f(fresh.vertices[10]))")

// Gaia: a path with a line to skip, a key without up, and its fovy carried on; keys put in time order.
let path = bytes("time=10 eye 1 2 3 center 0 0 0 up 0 1 0 fovy=40\nnonsense line\ntime=0 eye 4 5 6 center 1 1 1\n"
    + "time=20  eye 7 8 9\tcenter 2 2 2 up 1 0 0\r\ntime=30 eye 7 8 nan center 2 2 2\n")
let keys = XmbGaiaFirmware.parsePath(path)
print("P1 \(keys.map { "\($0.t):\($0.eye.x):\($0.up.x):\($0.fovy)" })")
let earth: [String: [UInt8]] = [
    "earth/SUN.mnu": bytes("NUM:int:4\nANGLE JIT:float:0\n"),
    "earth/EARTH.mnu": bytes("ANIM SPEED:float:2\n"),
    "earth/presets/b/earth/camera.path": path,
    "earth/presets/b/earth/SUN.mnu": bytes("NUM:int:6\n"),
    "earth/presets/a/earth/camera.path": path,
    "earth/presets/c/earth/camera.path": bytes("time=0 eye 1 1 1 center 0 0 0\n"),
    "earth/presets/tz/RESOLUTION.mnu": bytes("X:int:1\n"),
]
let presets = XmbGaiaFirmware.presets(files: earth)
print("P2 \(presets.map(\.name)) \(presets[0].f("SUN.mnu", "NUM", -1)) \(presets[1].f("SUN.mnu", "NUM", -1)) \(presets[1].f("EARTH.mnu", "ANIM SPEED", -1)) \(presets[0].f("HDR.mnu", "EXPOSURE", 0.8)) \(XmbGaiaFirmware.pack(files: earth) == nil)")
let camera = XmbGaiaTour.camera(keys, 10)
let between = XmbGaiaTour.camera(keys, 5)
print("P3 \(camera.eye) \(camera.fovy) \(f(between.eye.x)) \(f(between.fovy)) \(XmbGaiaTour.camera(keys, 99).eye)")

// A 4x4 DXT1 block: colour 0 red, colour 1 blue, texels 0, 1, 2, 3 along the first row and the rest 0.
var block = [UInt8](repeating: 0, count: 128)
block.replaceSubrange(0 ..< 4, with: bytes("DDS "))
block[12] = 4; block[16] = 4
block.replaceSubrange(84 ..< 88, with: bytes("DXT1"))
block += [0x00, 0xF8, 0x1F, 0x00, 0xE4, 0x00, 0x00, 0x00]
let disk = XmbGaiaFirmware.dxt1(block)!
print("X1 \(disk.width)x\(disk.height) \((0 ..< 4).map { disk.rgba[$0 * 4 ..< $0 * 4 + 4].map { String($0) }.joined(separator: ",") }) \(XmbGaiaFirmware.dxt1(Array(block.dropLast())) == nil)")
var transparent = block
transparent.replaceSubrange(128 ..< 132, with: [0x1F, 0x00, 0x00, 0xF8])
let clear = XmbGaiaFirmware.dxt1(transparent)!
print("X2 \(clear.rgba[8 ..< 16].map { String($0) }.joined(separator: ",")) \(XmbGaiaFirmware.fallbackDisk().rgba[(32 * 64 + 32) * 4])")

// The tour: the first frame picks a preset, ANIM SPEED moves it along, a flick picks another.
let gaia = XmbGaiaTour(presets: presets, index: -1, seed: 5)
gaia.tick(0.1, flick: false)
let picked = gaia.index
gaia.tick(0.5, flick: false)
let along = gaia.pathTime
gaia.tick(0.1, flick: true)
print("T1 picked=\(picked) along=\(f(along)) fade=\(f(gaia.fade)) next=\(gaia.index != picked) time=\(gaia.pathTime) started=\(gaia.started) spikes=\(gaia.spikeLengths.filter { $0 > 0 }.count) \(f(gaia.spikeAngles[1])) \(f(gaia.levelWeights.reduce(0, +)))")
print("T2 \(f(xmbHash01(1))) \(f(xmbHash01(2)))")

let stars = XmbGaiaFirmware.stars()
let lit = stars.map { face in stride(from: 0, to: face.count, by: 4).filter { face[$0] > 0 }.count }
print("S1 \(stars.count) \(stars[0].count) \(lit) \(stars == XmbGaiaFirmware.stars())")
let od = XmbGaiaFirmware.opticalDepth()
print("O1 \(od.count) \(od.allSatisfy { $0.isFinite && $0 >= 0 }) \(f(od[255])) \(f(od[0])) \(f(od[63 * 256 + 255])) \(f(od[63 * 256]))")
"""

EXPECTED = [
    "L1 frames=60 spectrum=120 b8=0,255 b13=255,255 b2=255 left=11 right=92 top=1.3176",
    "L2 0.0 0.0 0.0 true",
    "L3 16348 same=true short=true magic=true 88,86,73,83,0,0,0,2",
    "D1 0.0000 0.4000 0.6400 0.9940 0.4811 0.4570 0.0000",
    "F1 65.0000 65.0000 80.6909 150.0000",
    "K1 [\"waves\", \"valley\", \"gaia\", \"lines\"] lines waves gaia xmb.music.visualizer",
    "R1 -1170105035 -1155484576 187 25 47",
    "G1 1.0000 1.0000 1.0000 0.2006 0.0088 0.0000 0.0000 2.4600 0.7687",
    "V1 [\"Volcano:Volcano03:20.0\", \"Blue:Blue01+Blue02:20.0\"] [\"Blue01\", \"Blue02\", \"Volcano03\"]",
    "V2 3.0 2.0 70.0 50.0 30.0 0.5 127.0 156.0",
    "V3 [\"2x2\", \"1x1\"] 3,2,1,4,7,6,5,8 19,18,17,20",
    "V4 true 0 true",
    "V5 2048 1.0000 1.0000 0.1243 0.0000",
    "V6 (1, 0, 1) (1, 1, 2) (1, 0, 3) (0, 0, 4) finite=true travelled=91.5000 newest=34",
    "V7 -64.0000 -50.0000 5.0000 1.0000 0.0000 -113.1865 -113.1865",
    "P1 [\"0.0:4.0:0.0:40.0\", \"10.0:1.0:0.0:40.0\", \"20.0:7.0:1.0:40.0\"]",
    "P2 [\"a\", \"b\"] 4.0 6.0 2.0 0.8 true",
    "P3 SIMD3<Float>(1.0, 2.0, 3.0) 40.0 2.1250 40.0000 SIMD3<Float>(7.0, 8.0, 9.0)",
    "X1 4x4 [\"255,0,0,255\", \"0,0,255,255\", \"170,0,85,255\", \"85,0,170,255\"] true",
    "X2 127,0,127,255,0,0,0,0 254",
    "T1 picked=0 along=1.0000 fade=0.0000 next=true time=0.0 started=2 spikes=6 1.0472 1.3000",
    "T2 0.6132 0.3532",
    "S1 6 1048576 [1398, 1397, 1398, 1397, 1396, 1395] true",
    "O1 16384 true 0.2454 3.9537 0.0000 7.9074",
]


class XmbVisualizerHost(unittest.TestCase):
    def test_visualizer_parts(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run(SOURCES, DRIVER, Path(tmp)).strip().splitlines()
        self.assertEqual(out, EXPECTED)


if __name__ == "__main__":
    unittest.main()
