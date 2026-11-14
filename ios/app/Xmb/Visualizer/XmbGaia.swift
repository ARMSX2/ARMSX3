// SPDX-License-Identifier: GPL-2.0-only
// Gaia, the console's Earth visualizer: earth.qrc's pictures, presets and camera flights, and the tour of them; ported from Android's XmbGaia.kt.

import CoreGraphics
import Foundation
import ImageIO

/// From the player's own firmware (earth.qrc): the Earth's own pictures (ground, clouds and the sea's mask, six faces of
/// a cube), its presets, and the camera flight each preset carries. XmbGaiaView draws it as the firmware's
/// CubeEarthLayer does. Each preset's camera flies its path (keys every ten seconds) once, then another preset takes
/// over; the right stick or a swipe moves on to another one at once.
enum XmbGaiaFirmware {
    struct Key: Sendable, Equatable {
        let t: Float
        let eye: SIMD3<Float>
        let center: SIMD3<Float>
        let up: SIMD3<Float>
        let fovy: Float
    }

    /// A preset: its settings menu by menu (the firmware's own with the preset's on top) and its flight.
    struct Preset: Sendable {
        let name: String
        let menus: [String: [String: Float]]
        let path: [Key]

        func f(_ menu: String, _ key: String, _ d: Float) -> Float { menus[menu]?[key] ?? d }
    }

    /// One of the Earth's layers as cube faces (+X, -X, +Y, -Y, +Z, -Z), `size` square, `bytes` a texel (4 for RGBA,
    /// 1 for a single channel).
    struct Cube: Sendable {
        let size: Int
        let faces: [[UInt8]]
        let bytes: Int
    }

    struct Picture: Sendable {
        let width: Int
        let height: Int
        let rgba: [UInt8]
    }

    struct Pack: Sendable {
        let presets: [Preset]
        let ground: Cube
        let clouds: Cube
        let specular: Cube
        let disk: Picture?
    }

    static let menuNames = ["ATM.mnu", "CLOUDS.mnu", "EARTH.mnu", "HDR.mnu", "INTERACT.mnu", "SUN.mnu", "SimpleAutoExp.mnu"]
    static let file = "vsh/resource/qgl/earth.qrc"

    private static let tile = 512
    private static let source = 1024
    static let faceSize = 1024

    /// The presets of earth.qrc's files that have a flight of two keys or more, by name.
    static func presets(files: [String: [UInt8]]) -> [Preset] {
        let names = files.keys.compactMap { key -> String? in
            guard key.hasPrefix("earth/presets/"), key.hasSuffix("/earth/camera.path") else { return nil }
            let rest = key.dropFirst("earth/presets/".count)
            return rest.firstIndex(of: "/").map { String(rest[..<$0]) }
        }.sorted()
        return names.compactMap { p in
            guard let bytes = files["earth/presets/\(p)/earth/camera.path"] else { return nil }
            let path = parsePath(bytes)
            guard path.count >= 2 else { return nil }
            var menus: [String: [String: Float]] = [:]
            for menu in menuNames {
                var m = files["earth/" + menu].map(XmbWaveFirmware.parseMnu) ?? [:]
                if let own = files["earth/presets/\(p)/earth/" + menu] { m.merge(XmbWaveFirmware.parseMnu(own)) { $1 } }
                menus[menu] = m
            }
            return Preset(name: p, menus: menus, path: path)
        }
    }

    /// The whole pack, or nil when a preset or any of the Earth's pictures is missing or does not decode.
    static func pack(files: [String: [UInt8]]) -> Pack? {
        let presets = presets(files: files)
        guard !presets.isEmpty, let ground = cube(files, "ground_cm", bytes: 4), let clouds = cube(files, "clouds", bytes: 1),
              let specular = cube(files, "specular", bytes: 1) else { return nil }
        return Pack(presets: presets, ground: ground, clouds: clouds, specular: specular,
                    disk: files["earth/flashrom/disk.dds"].flatMap(dxt1))
    }

    /// camera.path: "time=T eye x y z center x y z up x y z fovy=F", a key a line.
    static func parsePath(_ bytes: [UInt8]) -> [Key] {
        var keys: [Key] = []
        var fovy: Float = 30
        for line in (String(bytes: bytes, encoding: .isoLatin1) ?? "").split(whereSeparator: \.isNewline) {
            let w = line.split(whereSeparator: \.isWhitespace).map(String.init)
            guard let t = w.first(where: { $0.hasPrefix("time=") }).flatMap({ Float($0.dropFirst(5)) }), t.isFinite else { continue }
            func v(_ name: String) -> SIMD3<Float>? {
                guard let i = w.firstIndex(of: name), i + 3 < w.count,
                      let x = Float(w[i + 1]), let y = Float(w[i + 2]), let z = Float(w[i + 3]),
                      x.isFinite, y.isFinite, z.isFinite else { return nil }
                return SIMD3(x, y, z)
            }
            if let f = w.first(where: { $0.hasPrefix("fovy=") }).flatMap({ Float($0.dropFirst(5)) }), f.isFinite { fovy = f }
            guard let eye = v("eye"), let center = v("center") else { continue }
            keys.append(Key(t: t, eye: eye, center: center, up: v("up") ?? SIMD3(0, 1, 0), fovy: fovy))
        }
        return keys.enumerated().sorted { $0.element.t < $1.element.t || ($0.element.t == $1.element.t && $0.offset < $1.offset) }.map(\.element)
    }

    /// A layer's six faces, each four 512 pictures (<row>_<col>), turned into the cube faces Metal and GL share. The
    /// firmware's world has north up (+Y) and longitude lon at azimuth lon - 45 deg from +Z toward +X (its sun is
    /// placed that way); the pictures' faces sit a quarter turn round from the cube's.
    static func cube(_ files: [String: [UInt8]], _ layer: String, bytes: Int) -> Cube? {
        let src = source
        let faceBytes = src * src * 4
        // The six faces side by side, each tile decoded straight into its place.
        var faces = [UInt8](repeating: 0, count: 6 * faceBytes)
        let decoded = faces.withUnsafeMutableBytes { raw -> Bool in
            for f in 0 ..< 6 {
                for r in 0 ..< 2 {
                    for c in 0 ..< 2 {
                        let name = String(format: "earth/flashrom/%@/%d/1/%03d_%03d.jpg", layer, f, r, c)
                        let at = f * faceBytes + ((r * tile) * src + c * tile) * 4
                        guard let jpg = files[name], decodeTile(jpg, into: raw.baseAddress! + at, bytesPerRow: src * 4) else { return false }
                    }
                }
            }
            return true
        }
        guard decoded else { return nil }
        let n = faceSize
        var out = [[UInt8]](repeating: [], count: 6)
        faces.withUnsafeBufferPointer { from in
            for g in 0 ..< 6 {
                var face = [UInt8](repeating: bytes == 4 ? 255 : 0, count: n * n * bytes)
                face.withUnsafeMutableBufferPointer { dst in
                    for j in 0 ..< n {
                        let tc = (Float(j) + 0.5) / Float(n) * 2 - 1
                        for i in 0 ..< n {
                            let sc = (Float(i) + 0.5) / Float(n) * 2 - 1
                            // The cube's direction for this texel of face g.
                            let xw: Float, yw: Float, zw: Float
                            switch g {
                            case 0: (xw, yw, zw) = (1, -tc, -sc)
                            case 1: (xw, yw, zw) = (-1, -tc, sc)
                            case 2: (xw, yw, zw) = (sc, 1, tc)
                            case 3: (xw, yw, zw) = (sc, -1, -tc)
                            case 4: (xw, yw, zw) = (sc, -tc, 1)
                            default: (xw, yw, zw) = (-sc, -tc, -1)
                            }
                            let (face, texel) = consoleTexel(zw, yw, -xw)
                            let at = face * faceBytes + texel
                            let o = (j * n + i) * bytes
                            if bytes == 4 {
                                dst[o] = from[at]
                                dst[o + 1] = from[at + 1]
                                dst[o + 2] = from[at + 2]
                            } else {
                                dst[o] = from[at + 1]
                            }
                        }
                    }
                }
                out[g] = face
            }
        }
        return Cube(size: n, faces: out, bytes: bytes)
    }

    // The firmware's face for a direction, and the byte of its texel (u right, v down in its pictures).
    private static func consoleTexel(_ x: Float, _ y: Float, _ z: Float) -> (Int, Int) {
        let ax = abs(x), ay = abs(y), az = abs(z)
        let f: Int, u: Float, v: Float
        if ax >= ay && ax >= az {
            if x > 0 { (f, u, v) = (2, (z / ax + 1) / 2, (y / ax + 1) / 2) } else { (f, u, v) = (0, (1 - z / ax) / 2, (y / ax + 1) / 2) }
        } else if az >= ay {
            if z > 0 { (f, u, v) = (1, (1 - x / az) / 2, (y / az + 1) / 2) } else { (f, u, v) = (3, (x / az + 1) / 2, (y / az + 1) / 2) }
        } else {
            if y > 0 { (f, u, v) = (5, (z / ay + 1) / 2, (1 - x / ay) / 2) } else { (f, u, v) = (4, (z / ay + 1) / 2, (x / ay + 1) / 2) }
        }
        let px = min(max(Int(u * Float(source)), 0), source - 1)
        let py = min(max(Int(v * Float(source)), 0), source - 1)
        return (f, (py * source + px) * 4)
    }

    /// A JPEG into 512x512 RGBA at `pixels`, `bytesPerRow` apart, from its top left, the rest left as it was (black), as
    /// Android's getPixels leaves it.
    private static func decodeTile(_ jpg: [UInt8], into pixels: UnsafeMutableRawPointer, bytesPerRow: Int) -> Bool {
        guard let source = CGImageSourceCreateWithData(Data(jpg) as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              let context = CGContext(data: pixels, width: tile, height: tile, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return false }
        context.draw(image, in: CGRect(x: 0, y: tile - image.height, width: image.width, height: image.height))
        return true
    }

    /// A DXT1 picture's largest level, as RGBA.
    static func dxt1(_ b: [UInt8]) -> Picture? {
        guard b.count >= 128, b[0 ..< 4].elementsEqual("DDS ".utf8), b[84 ..< 88].elementsEqual("DXT1".utf8) else { return nil }
        func le(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24 }
        let h = le(12), w = le(16)
        guard w > 0, h > 0, w <= 4096, h <= 4096, 128 + ((w + 3) / 4) * ((h + 3) / 4) * 8 <= b.count else { return nil }
        var out = [UInt8](repeating: 0, count: w * h * 4)
        var o = 128
        for by in 0 ..< (h + 3) / 4 {
            for bx in 0 ..< (w + 3) / 4 {
                let c0 = Int(b[o]) | Int(b[o + 1]) << 8
                let c1 = Int(b[o + 2]) | Int(b[o + 3]) << 8
                func rgb(_ c: Int) -> [Int] { [((c >> 11) & 31) * 255 / 31, ((c >> 5) & 63) * 255 / 63, (c & 31) * 255 / 31] }
                let a = rgb(c0), bb = rgb(c1)
                var p = [a, bb, [0, 0, 0], [0, 0, 0]]
                for k in 0 ..< 3 {
                    if c0 > c1 {
                        p[2][k] = (2 * a[k] + bb[k]) / 3
                        p[3][k] = (a[k] + 2 * bb[k]) / 3
                    } else {
                        p[2][k] = (a[k] + bb[k]) / 2
                        p[3][k] = 0
                    }
                }
                let bits = le(o + 4)
                for py in 0 ..< 4 {
                    for px in 0 ..< 4 {
                        let x = bx * 4 + px, y = by * 4 + py
                        if x >= w || y >= h { continue }
                        let idx = (bits >> (2 * (py * 4 + px))) & 3
                        let d = (y * w + x) * 4
                        out[d] = UInt8(p[idx][0])
                        out[d + 1] = UInt8(p[idx][1])
                        out[d + 2] = UInt8(p[idx][2])
                        out[d + 3] = c0 <= c1 && idx == 3 ? 0 : 255
                    }
                }
                o += 8
            }
        }
        return Picture(width: w, height: h, rgba: out)
    }

    /// A soft round glow when the firmware's sun picture is not there.
    static func fallbackDisk() -> Picture {
        let n = 64
        var out = [UInt8](repeating: 255, count: n * n * 4)
        for y in 0 ..< n {
            for x in 0 ..< n {
                let dx = (Float(x - n / 2) + 0.5) / Float(n / 2), dy = (Float(y - n / 2) + 0.5) / Float(n / 2)
                let d = (dx * dx + dy * dy).squareRoot()
                let v = UInt8(min(max(Int(exp(-d * d * 6) * 255), 0), 255))
                let o = (y * n + x) * 4
                out[o] = v
                out[o + 1] = v
                out[o + 2] = v
            }
        }
        return Picture(width: n, height: n, rgba: out)
    }

    /// The stars: the firmware's star picture is not in its pack, so a sky of them is made, brightness spread as the
    /// stars' is (many faint, a few bright); TiledStars' curve lifts them. Six 512 faces of RGBA.
    static func stars() -> [[UInt8]] {
        let n = 512
        var random = XmbJavaRandom(0x51A75)
        return (0 ..< 6).map { _ in
            var data = [UInt8](repeating: 0, count: n * n * 4)
            for i in 0 ..< n * n { data[i * 4 + 3] = 255 }
            for _ in 0 ..< 1400 {
                let x = Int(random.nextInt(Int32(n))), y = Int(random.nextInt(Int32(n)))
                let v = min(0.35 + 0.65 * pow(random.nextDouble(), 6), 1)
                let tint = Double(random.nextFloat())
                let r = v * (0.85 + 0.15 * tint), g = v * 0.95, b = v * (1 - 0.15 * tint)
                let o = (y * n + x) * 4
                data[o] = UInt8(truncatingIfNeeded: Int(r * 255))
                data[o + 1] = UInt8(truncatingIfNeeded: Int(g * 255))
                data[o + 2] = UInt8(truncatingIfNeeded: Int(b * 255))
            }
            return data
        }
    }

    /// The air's outer edge, in the Earth's radius.
    static let atmosphere: Float = 1.025
    static let opticalDepthWidth = 256
    static let opticalDepthHeight = 64

    /// The air's optical depth, worked out once: from a point at height r (the Earth's radius to the air's edge, down
    /// the table) along a direction above that height's horizon (across, finer toward the horizon), the density
    /// exp(-(h - 1) / H) summed to the edge of the air, in the air's thickness.
    static func opticalDepth() -> [Float] {
        let w = opticalDepthWidth, h = opticalDepthHeight, atm = atmosphere
        let thick = atm - 1
        let scaleH = 0.25 * thick
        var data = [Float](repeating: 0, count: w * h)
        for j in 0 ..< h {
            let r = 1 + thick * Float(j) / Float(h - 1)
            let muH = -max(0, 1 - 1 / (r * r)).squareRoot()
            for i in 0 ..< w {
                let x = Float(i) / (Float(w) - 1)
                let mu = muH + (1 - muH) * x * x
                let b = r * mu
                let tOut = -b + max(0, b * b - (r * r - atm * atm)).squareRoot()
                let steps = 64
                let ds = tOut / Float(steps)
                var sum: Float = 0
                for s in 0 ..< steps {
                    let tt = (Float(s) + 0.5) * ds
                    let px = max(0, 1 - mu * mu).squareRoot() * tt
                    let py = r + mu * tt
                    let hh = max(1, (px * px + py * py).squareRoot())
                    sum += exp(-(hh - 1) / scaleH) * ds
                }
                data[j * w + i] = sum / thick
            }
        }
        return data
    }
}

/// Gaia's preset changes, from the right stick or a swipe, picked up on its next frame.
@MainActor enum XmbGaiaPresets {
    static var preset = -1
    static var flicks = false

    static func flick() { flicks = true }
}

/// Gaia's tour of its presets: which one flies, how far along its path, how far it has faded in, and what it sets
/// for the sun's spikes and the glare.
final class XmbGaiaTour {
    static let maxSpikes = 24
    static let glareLevels = 8

    let presets: [XmbGaiaFirmware.Preset]
    private(set) var preset: XmbGaiaFirmware.Preset?
    private(set) var index: Int
    private(set) var pathTime: Float = 0
    private(set) var fade: Float = 0
    private var seed: Int32
    private(set) var spikeAngles = [Float](repeating: 0, count: maxSpikes)
    private(set) var spikeLengths = [Float](repeating: 0, count: maxSpikes)
    private(set) var blurWeights = [SIMD3<Float>](repeating: .zero, count: 8)
    private(set) var levelWeights = [Float](repeating: 0, count: glareLevels)
    /// Counts each new preset, so the drawing starts its exposure over.
    private(set) var started = 0

    init(presets: [XmbGaiaFirmware.Preset], index: Int, seed: Int32) {
        self.presets = presets
        self.index = index
        self.seed = seed
    }

    func rand01() -> Float {
        seed &+= 1
        return xmbHash01(seed)
    }

    func tick(_ dt: Float, flick: Bool) {
        guard !presets.isEmpty else { return }
        guard let p = preset, !flick, pathTime <= p.path[p.path.count - 1].t else {
            nextPreset()
            return
        }
        pathTime += dt * min(max(p.f("EARTH.mnu", "ANIM SPEED", 1), 0.05), 4)
        fade = min(1, fade + dt / 1.5)
    }

    private func nextPreset() {
        let n = presets.count
        var i = min(max(Int(rand01() * Float(n)), 0), n - 1)
        if n > 1 && i == index { i = (i + 1) % n }
        index = i
        let p = presets[i]
        preset = p
        pathTime = p.path[0].t
        fade = 0
        // The sun's spikes: NUM of them, round the sun at jittered angles.
        let num = Int(min(max(p.f("SUN.mnu", "NUM", 15), 0), Float(Self.maxSpikes)))
        let jit = p.f("SUN.mnu", "ANGLE JIT", 1.4)
        for k in 0 ..< Self.maxSpikes {
            spikeAngles[k] = k < num ? Float(k) / Float(num) * 2 * Float(Double.pi) + (rand01() - 0.5) * jit : 0
            spikeLengths[k] = k < num ? 0.6 + rand01() * 0.8 : 0
        }
        blurWeights = XmbGlare.gaussian(SIMD3(p.f("HDR.mnu", "GAUSSIAN RAD R", 1.4), p.f("HDR.mnu", "GAUSSIAN RAD G", 1.45),
                                              p.f("HDR.mnu", "GAUSSIAN RAD B", 1.42)))
        levelWeights = XmbGlare.levelWeights(sumPow: p.f("HDR.mnu", "GLARE SUM POW", 2), level: p.f("HDR.mnu", "GLARE LEVEL", 1.3),
                                             count: Self.glareLevels)
        started += 1
    }

    /// The camera along the preset's path at `t`, a Catmull-Rom curve through its keys, and its fovy.
    static func camera(_ k: [XmbGaiaFirmware.Key], _ t: Float) -> (eye: SIMD3<Float>, center: SIMD3<Float>, up: SIMD3<Float>, fovy: Float) {
        var i = 0
        while i < k.count - 2 && k[i + 1].t <= t { i += 1 }
        let a = k[max(i - 1, 0)], b = k[i], c = k[min(i + 1, k.count - 1)], d = k[min(i + 2, k.count - 1)]
        let s = c.t > b.t ? min(max((t - b.t) / (c.t - b.t), 0), 1) : 0
        func cr(_ p0: SIMD3<Float>, _ p1: SIMD3<Float>, _ p2: SIMD3<Float>, _ p3: SIMD3<Float>) -> SIMD3<Float> {
            let s2 = s * s, s3 = s2 * s
            var r = 2 * p1
            r += (p2 - p0) * s
            r += (2 * p0 - 5 * p1 + 4 * p2 - p3) * s2
            r += (3 * p1 - p0 - 3 * p2 + p3) * s3
            return 0.5 * r
        }
        return (cr(a.eye, b.eye, c.eye, d.eye), cr(a.center, b.center, c.center, d.center), cr(a.up, b.up, c.up, d.up),
                b.fovy + (c.fovy - b.fovy) * s)
    }
}
