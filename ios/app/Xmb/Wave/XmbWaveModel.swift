// SPDX-License-Identifier: GPL-2.0-only
// The console's XMB wave on the CPU and its settings from the firmware, ported from Android's XmbWaveModel.kt.

import Foundation

/// The firmware keeps a grid of springs, 19 lines of 19 points rooted on a small ring that wanders, pulled out at
/// their free ends and shaken by noise (LINE1.mnu). The grid is narrowed and widened along its length, bent through
/// a lattice of 11x7x7 points that moves with time, and placed in the world. `update` does all of that and fills
/// the 19x19 control points, in clip space, that XmbWave.metal tessellates and lights. The camera and the two
/// lattice gains were measured from a capture of the console's XMB.
final class XmbWaveModel {
    /// LINE1.mnu, with the firmware's own defaults.
    struct Line: Equatable, Sendable {
        var damping: Float = 0.0001
        var length: Float = 0.306001
        var tension: Float = 0.25
        var brightness: Float = 0.701917
        var mipmapBias: Float = 1.86707
        var fresnel: Float = 0.638971
        var timestep: Float = 4
        var perturbation: Float = 0.0998587
        var pos = SIMD3<Float>(-7.67933, -1.08844, -6.40287)
        var axis = SIMD3<Float>(-0.994157, 0.0867576, 0.065364)
        var rotDeg: Float = 18.1208
        var end = SIMD3<Float>(3, 0.4, 0.2)
        var scale1 = SIMD3<Float>(5.67726, 1.00077, 1)
        var scale2 = SIMD3<Float>(2.82755, 1.27579, 2.88782)
        var offset = SIMD3<Float>(0, -0.469999, 0)
        var param1: Float = -1.33509
    }

    static let rows = 19
    static let count = rows * rows
    private static let latticeX = 11
    private static let latticeY = 7
    private static let latticeZ = 7

    /// The XMB camera: 53 degrees tall, near 0.1, far 1000.
    static let projectionHeight: Float = 2.0057
    private static let eye = SIMD3<Float>(0.39, 0.63, 1.87)

    /// The lattice's lengthwise stretch and the strength of its twist across the band.
    private static let stretch: Float = 6.4
    private static let twist: Float = 0.15

    // Float.pi rounds down, and the Kotlin's PI.toFloat() rounds to nearest.
    private static let pi = Float(Double.pi)

    let line: Line

    /// How hard the song pushes the wave now (the Waves visualizer), 0 at rest (the XMB itself) and about 1 at its
    /// loudest. It moves the lattice, quicker and higher, so the wave billows with the music and settles as soon as it
    /// goes quiet. The springs are left as the console keeps them: shaken harder they heat up, and a hot chain draws
    /// in toward its roots, taking the wave off the left of the screen for a minute after every loud part.
    var drive: Float = 0

    // The spring grid: rows are lines, column 18 their roots.
    private var pos: [Float]
    private var vel: [Float]
    private var prev: [Float]
    private var clock: Float = 0 // the noise clock
    private var latticeTime: Float = 0 // ten times the clock at rest, quicker while the song pushes
    private var billow: Float = 0 // the song's push, eased in and out
    private var smooth: Float = 0 // the roots' ring follows this, eased toward the clock
    private var counter: Int32 = 0 // the noise sequence
    private var acc: Float = 0 // fraction of a 60 Hz step not taken yet

    private var lattice = [Float](repeating: 0, count: latticeZ * latticeY * latticeX * 2)
    private let rot: [Float]

    /// `state` is wave_state.bin: the grid's positions, then its velocities, as little-endian floats. Without it the
    /// lines start straight and a few seconds of the springs settle them, which takes a moment.
    init(state: [UInt8]?, line: Line = Line(),
         seed: Int32 = Int32(truncatingIfNeeded: DispatchTime.now().uptimeNanoseconds & 0xffffff)) {
        self.line = line
        let n = Self.count, r = Self.rows

        var a = line.axis
        let length = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        a = SIMD3(a.x / length, a.y / length, a.z / length)
        let angle = line.rotDeg * Self.pi / 180
        let c = cos(angle), s = sin(angle), ic = 1 - c
        rot = [c + a.x * a.x * ic, a.x * a.y * ic - a.z * s, a.x * a.z * ic + a.y * s,
               a.y * a.x * ic + a.z * s, c + a.y * a.y * ic, a.y * a.z * ic - a.x * s,
               a.z * a.x * ic - a.y * s, a.z * a.y * ic + a.x * s, c + a.z * a.z * ic]

        pos = [Float](repeating: 0, count: n * 3)
        vel = pos
        prev = pos
        if let floats = state.flatMap(Self.floats), floats.count >= n * 6 {
            pos = Array(floats[0 ..< n * 3])
            vel = Array(floats[n * 3 ..< n * 6])
        } else {
            for row in 0 ..< r {
                for column in 0 ..< r {
                    let i = (row * r + column) * 3
                    let ph = Float(row) / Float(r)
                    pos[i] = Float(r - 1 - column) * 0.33
                    pos[i + 1] = (sin(11 * ph) * 0.5 + 0.5) * line.end.y
                    pos[i + 2] = (cos(15 * ph) + 1) * 0.5 * line.end.z
                }
            }
            for _ in 0 ..< 12000 { step() }
        }
        prev = pos
        counter = seed
        latticeTime = clock * 10
    }

    /// Nil when a value is not a number, so a damaged state counts as none.
    private static func floats(_ bytes: [UInt8]) -> [Float]? {
        var out = [Float](repeating: 0, count: bytes.count / 4)
        for i in out.indices {
            let bits = UInt32(bytes[i * 4]) | UInt32(bytes[i * 4 + 1]) << 8 | UInt32(bytes[i * 4 + 2]) << 16
                | UInt32(bytes[i * 4 + 3]) << 24
            out[i] = Float(bitPattern: bits)
            if !out[i].isFinite { return nil }
        }
        return out
    }

    /// The console's integer noise, -1...1.
    static func noise(_ n0: Int32) -> Float {
        let n = (n0 &<< 13) ^ n0
        let t = (n &* (n &* n &* 15731 &+ 789221) &+ 1376312589) & 0x7fffffff
        return 1 - Float(t) / 1073741824
    }

    /// The strand lookup without firmware: the shape of freslut1, a spike at 0.057 with a long tail.
    static func fallbackStrands() -> [Float] {
        (0 ..< 512).map { i in
            let x = Float(i)
            if x < 29 {
                let d = (x - 29) / 7.8
                return 0.988 * exp(-(d * d))
            }
            return 0.988 * exp(-(x - 29) / 21.5)
        }
    }

    /// One 60 Hz step of the springs, then the roots.
    private func step() {
        prev = pos
        let r = Self.rows
        let ts = line.timestep
        clock += ts * 1e-4
        if clock > 10 { clock = 0 }
        let l = line.length
        let k = line.tension
        let p = line.perturbation
        for row in 0 ..< r {
            for column in 0 ..< r {
                let i = row * r + column
                if column > 0 { spring(i, i - 1, l, k) }
                if column > 1 { spring(i, i - 2, 2 * l, k * 10) }
                if row > 0 { spring(i, i - r, l, k) }
                if row > 1 { spring(i, i - 2 * r, 2 * l, k * 10) }
                counter &+= 1
                vel[i * 3] += Self.noise(counter) * p
                counter &+= 1
                vel[i * 3 + 1] += Self.noise(counter) * p
                counter &+= 1
                vel[i * 3 + 2] += Self.noise(counter) * p
            }
        }
        let dt = ts * 1e-4
        let keep = 1 - line.damping * ts
        for j in 0 ..< Self.count * 3 {
            pos[j] += vel[j] * dt
            vel[j] *= keep
        }
        smooth += (clock - smooth) * 0.1
        for row in 0 ..< r {
            vel[(row * r) * 3] += ts * 0.02
            let ph = Float(row) / Float(r) + smooth
            let i = (row * r + r - 1) * 3
            pos[i] = 0
            pos[i + 1] = (sin(ph * 11) * 0.5 + 0.5) * line.end.y
            pos[i + 2] = (cos(ph * 15) + 1) * 0.5 * line.end.z
            vel[i] = 0
            vel[i + 1] = 0
            vel[i + 2] = 0
        }
    }

    private func spring(_ a: Int, _ b: Int, _ rest: Float, _ k: Float) {
        let dx = pos[b * 3] - pos[a * 3]
        let dy = pos[b * 3 + 1] - pos[a * 3 + 1]
        let dz = pos[b * 3 + 2] - pos[a * 3 + 2]
        let len = sqrt(dx * dx + dy * dy + dz * dz)
        if len < 1e-12 { return }
        let f = (len - rest) * k / len
        vel[a * 3] += dx * f
        vel[a * 3 + 1] += dy * f
        vel[a * 3 + 2] += dz * f
        vel[b * 3] -= dx * f
        vel[b * 3 + 1] -= dy * f
        vel[b * 3 + 2] -= dz * f
    }

    /// The lattice at its 8x4x4 texels, edged out to 11x7x7.
    private func fillLattice(_ t: Float, _ push: Float) {
        let c = (sin(0.1 * t) + 0.2) / 1.2
        let amp = (sin(2 * t) + 3) * exp(-1e-4 * t) * (1 + push * 0.6)
        let bob = sin(0.25 * t)
        for kk in 0 ..< Self.latticeZ {
            for jj in 0 ..< Self.latticeY {
                for ii in 0 ..< Self.latticeX {
                    let u = Float(min(max(ii - 1, 0), 7)) / 8
                    let w = Float(min(max(kk - 1, 0), 3)) / 4
                    let dx = 0.2 * u + 1.3 * u * u - 0.15
                    let dy = 0.24 * (tanh((u - 0.5) / 2) + 1) * amp * sin(7.85 * u - 2.5 * t - 1.25)
                        + bob + exp(-50 * (u - c) * (u - c)) + Self.twist * sin(t - 2 * Self.pi * w)
                    let o = ((kk * Self.latticeY + jj) * Self.latticeX + ii) * 2
                    lattice[o] = Self.stretch * dx
                    lattice[o + 1] = dy
                }
            }
        }
    }

    private static func weights(_ t: Float) -> SIMD4<Float> {
        let t2 = t * t, t3 = t2 * t, m = 1 - t
        return SIMD4(m * m * m / 6, (3 * t3 - 6 * t2 + 4) / 6, (-3 * t3 + 3 * t2 + 3 * t + 1) / 6, t3 / 6)
    }

    // Never a NaN, which Int() would trap on.
    private static func unit(_ x: Float) -> Float { x > 0 ? min(x, 0.999) : 0 }

    /// Advance by `dt` seconds and fill `out` (19x19x4, row-major, rows are lines) with the control points in clip
    /// space for a screen `aspect` wide. The console's camera keeps its height and shows more or less to the sides.
    func update(dt dtSec: Float, aspect: Float, into out: inout [Float]) {
        guard out.count >= Self.count * 4 else { return }
        let dt = min(max(dtSec, 0), 0.25)
        acc += dt * 60
        var steps = 0
        while acc > 1 && steps < 8 {
            step()
            acc -= 1
            steps += 1
        }
        if acc > 1 { acc = 1 }
        // The lattice keeps no state and moves on with time itself, so it glides at any refresh rate; the song's push
        // is eased over a third of a second, so the wave swells with the song instead of jumping on each beat.
        billow += (drive - billow) * min(1, dt * 3)
        latticeTime += dt * 60 * line.timestep * 1e-3 * (1 + billow * 1.5)
        if latticeTime > 100 { latticeTime = 0 }
        fillLattice(latticeTime, billow)
        // Held upright, as much across as on 16:9 and more up and down.
        let sx = Self.projectionHeight / max(aspect, 16 / 9)
        let sy = sx * aspect
        let lx = Self.latticeX, ly = Self.latticeY
        for i in 0 ..< Self.count {
            // The grid as the frame shows it, between two steps, then narrowed and widened along its length.
            let px = prev[i * 3] + (pos[i * 3] - prev[i * 3]) * acc
            var py = prev[i * 3 + 1] + (pos[i * 3 + 1] - prev[i * 3 + 1]) * acc
            var pz = prev[i * 3 + 2] + (pos[i * 3 + 2] - prev[i * 3 + 2]) * acc
            let d = px - line.param1
            let s: Float
            if d < 0 {
                s = 0.3
            } else {
                let m = min(max(d / 5, 0), 1)
                s = 1.3 - cos(d * Self.pi / 2) * (1 - m) * (1 - m) * (1 + 2 * m)
            }
            py *= s
            pz *= s
            // Into the lattice's box, through the lattice, and back out.
            let qx = (px - line.offset.x) / line.scale1.x
            let qy = (py - line.offset.y) / line.scale1.y
            let qz = (pz - line.offset.z) / line.scale1.z
            let sxl = Self.unit(qx) * 8
            let syl = Self.unit(qy) * 4
            let szl = Self.unit(qz) * 4
            let cx = Int(sxl), cy = Int(syl), cz = Int(szl)
            let bx = Self.weights(sxl - Float(cx)), by = Self.weights(syl - Float(cy)), bz = Self.weights(szl - Float(cz))
            var ddx: Float = 0
            var ddy: Float = 0
            for c in 0 ..< 4 {
                for b in 0 ..< 4 {
                    let wzy = bz[c] * by[b]
                    let row = (((cz + c) * ly + cy + b) * lx + cx) * 2
                    for a in 0 ..< 4 {
                        let wgt = wzy * bx[a]
                        ddx += wgt * lattice[row + a * 2]
                        ddy += wgt * lattice[row + a * 2 + 1]
                    }
                }
            }
            let ox = (qx + ddx) * line.scale2.x
            let oy = (qy + ddy) * line.scale2.y
            let oz = qz * line.scale2.z
            // The line object's place in the world, then the eye.
            let wx = rot[0] * ox + rot[1] * oy + rot[2] * oz + line.pos.x - Self.eye.x
            let wy = rot[3] * ox + rot[4] * oy + rot[5] * oz + line.pos.y - Self.eye.y
            let wz = rot[6] * ox + rot[7] * oy + rot[8] * oz + line.pos.z - Self.eye.z
            let w = -wz
            out[i * 4] = sx * wx
            out[i * 4 + 1] = sy * wy
            out[i * 4 + 2] = 1.0002 * w - 0.2
            out[i * 4 + 3] = w
        }
    }
}

/// The wave's settings and strand lookup from the files of the player's lines.qrc (dev_flash/vsh/resource/qgl); the
/// firmware's defaults for whatever is not there.
struct XmbWaveFirmware: Sendable {
    var line = XmbWaveModel.Line()
    /// The line colour across the screen (BACKGROUND.mnu colours 3 and 4).
    var colour3 = SIMD3<Float>(0.847347, 0.846155, 0.846448)
    var colour4 = SIMD3<Float>(0.92487, 0.922603, 0.922933)
    /// 512 texels: how bright the band is by how nearly it is seen edge on.
    var strands = XmbWaveModel.fallbackStrands()

    init() {}

    init(files: [String: [UInt8]]) {
        line = Self.line(files["LINE1.mnu"].map(Self.parseMnu) ?? [:])

        let bg = files["BACKGROUND.mnu"].map(Self.parseMnu) ?? [:]
        colour3 = SIMD3(bg["3 RED"] ?? colour3.x, bg["3 GREEN"] ?? colour3.y, bg["3 BLUE"] ?? colour3.z)
        colour4 = SIMD3(bg["4 RED"] ?? colour4.x, bg["4 GREEN"] ?? colour4.y, bg["4 BLUE"] ?? colour4.z)

        // freslut1.tga: 512 texels, BGR; the strands are its red.
        if let tga = files["textures/TGA/freslut1.tga"], tga.count >= 18 {
            let start = 18 + Int(tga[0])
            let w = Int(tga[12]) | Int(tga[13]) << 8
            let bpp = Int(tga[16]) / 8
            if w >= 1, bpp >= 3, start + w * bpp <= tga.count {
                strands = (0 ..< 512).map { Float(tga[start + min($0, w - 1) * bpp + 2]) / 255 }
            }
        }
    }

    /// LINE1.mnu's settings, the firmware's defaults for what it leaves out.
    static func line(_ mnu: [String: Float]) -> XmbWaveModel.Line {
        func f(_ key: String, _ d: Float) -> Float { mnu[key] ?? d }
        func v(_ key: String, _ d: SIMD3<Float>) -> SIMD3<Float> {
            SIMD3(f(key + " X", d.x), f(key + " Y", d.y), f(key + " Z", d.z))
        }
        let d = XmbWaveModel.Line()
        return XmbWaveModel.Line(
            damping: f("DAMPING", d.damping), length: f("LENGTH", d.length), tension: f("TENSION", d.tension),
            brightness: f("BRIGHTNESS", d.brightness), mipmapBias: f("MIPMAP BIAS", d.mipmapBias),
            fresnel: f("FRESNEL", d.fresnel), timestep: f("TIMESTEP", d.timestep),
            perturbation: f("PERTURBATION", d.perturbation), pos: v("POS", d.pos), axis: v("ANG", d.axis),
            rotDeg: f("ANG ROT", d.rotDeg), end: v("END", d.end), scale1: v("FFD SCALE1", d.scale1),
            scale2: v("FFD SCALE2", d.scale2), offset: v("FFD OFFSET", d.offset), param1: f("FFD PARAM 1", d.param1))
    }

    /// One of lines.qrc's moods (override/<name>): the console's own changes to the wave for a scene, over the base
    /// settings. "music_1" is the music player's Waves visualizer.
    struct Mood: Sendable, Equatable {
        var line: XmbWaveModel.Line
        /// BACKGROUND.mnu: 1 at the bottom of the screen to 2 at the top, 3 at the left to 4 at the right.
        var colours: [SIMD3<Float>]
        /// BACKGROUND.mnu COLOUR SHADER: which lib/moyou/back_coloursN draws the sky.
        var colourShader: Int
        var hdr: Hdr
    }

    /// HDR.mnu: the exposure and the glare around what is bright.
    struct Hdr: Sendable, Equatable {
        var exposure: Float = 1.05
        var white: Float = 0.899181
        var glareLevel: Float = 1.10245
        var glareThreshold: Float = 0.738857
        var radius = SIMD3<Float>(1.23888, 1.43176, 1.55787)
        var glareSumPow: Float = 0.557478
    }

    /// Mood `name` from lines.qrc's files: each menu the firmware's own with the mood's on top.
    static func mood(_ name: String, files: [String: [UInt8]]) -> Mood {
        func mnu(_ file: String) -> [String: Float] {
            var m = files[file].map(parseMnu) ?? [:]
            if let own = files["override/\(name)/" + file] { m.merge(parseMnu(own)) { $1 } }
            return m
        }
        let bg = mnu("BACKGROUND.mnu")
        let hdr = mnu("HDR.mnu")
        let d = Hdr()
        func h(_ key: String, _ value: Float) -> Float { hdr[key] ?? value }
        return Mood(
            line: line(mnu("LINE1.mnu")),
            colours: (1 ... 4).map { n in SIMD3(bg["\(n) RED"] ?? 1, bg["\(n) GREEN"] ?? 1, bg["\(n) BLUE"] ?? 1) },
            // Held in range first: Int() traps on a number too large for it.
            colourShader: Int(min(max(bg["COLOUR SHADER"] ?? 0, -1), 9)),
            hdr: Hdr(exposure: h("EXPOSURE", d.exposure), white: h("WHITE LEVEL", d.white), glareLevel: h("GLARE LEVEL", d.glareLevel),
                     glareThreshold: h("GLARE THRESH", d.glareThreshold),
                     radius: SIMD3(h("GAUSSIAN RAD R", d.radius.x), h("GAUSSIAN RAD G", d.radius.y), h("GAUSSIAN RAD B", d.radius.z)),
                     glareSumPow: h("GLARE SUM POW", d.glareSumPow)))
    }

    /// The `NAME:float:1.5` and `NAME:int:3` lines of a .mnu settings file, by name.
    static func parseMnu(_ bytes: [UInt8]) -> [String: Float] {
        var out: [String: Float] = [:]
        for line in (String(bytes: bytes, encoding: .isoLatin1) ?? "").split(whereSeparator: \.isNewline) {
            let parts = line.trimmingCharacters(in: .whitespacesAndNewlines)
                .split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 3, parts[1] == "float" || parts[1] == "int",
                  let value = Float(parts[2].trimmingCharacters(in: .whitespaces)), value.isFinite else { continue }
            out[String(parts[0])] = value
        }
        return out
    }
}
