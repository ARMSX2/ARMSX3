// SPDX-License-Identifier: GPL-2.0-only
// Valley, the console's canyon visualizer: canyon.qrc's settings, presets and normal map, and the terrain on the CPU; ported from Android's XmbValley.kt.

import Foundation

/// Read from the player's own firmware (canyon.qrc) and drawn the way the firmware draws it (XmbValleyView).
///
/// A ring of 128 rows of 128 points runs away from the camera. Each new row comes from the music: the console's
/// spectrum (lows at the edges, highs in the middle), weighted toward the middle (TERRAIN WIDTH), on the valley's own
/// profile (VALLEY HEIGHT, CENTRE, WIDTH, SHARP), eased in on a spring from the row before and smoothed across. The
/// presets come in groups, as on the console: one gives way to another of its group every twenty seconds or so, and
/// the right stick (or a swipe) changes them: left and right another of the group, up and down another group.
enum XmbValleyFirmware {
    /// One preset's settings: the firmware's defaults with the preset's own on top.
    struct Params: Sendable, Equatable {
        let lineAhead, lineWidth, lineHeight, lineLength, lineBias: Float
        let terrainHeight, terrainWidth, terrainSlide, scaleX, textureScale, bumpScale: Float
        let valleyHeight, valleyCentre, valleyWidth, valleySharp: Float
        let focalDepth, focalVariance, lod, feedback, feedbackX, feedbackY, feedbackRot, skyBlur: Float
        let posX, posY, posZ, targetX, targetY, targetZ, posSpeed, timeAhead, zoom: Float
        let fade: Float
        let colour: SIMD3<Float>
        let colourScale, colourBias: Float
        let line: SIMD3<Float>
        let fogMin, fogMax: Float
        let fog: SIMD3<Float>

        init(_ m: [String: Float]) {
            func f(_ key: String, _ d: Float) -> Float { m[key] ?? d }
            lineAhead = f("LINE AHEAD", 127)
            lineWidth = f("LINE WIDTH", 5)
            lineHeight = f("LINE HEIGHT", 0.01)
            lineLength = f("LINE LENGTH", 5)
            lineBias = f("LINE BIAS", 0)
            terrainHeight = f("TERRAIN HEIGHT", 1)
            terrainWidth = f("TERRAIN WIDTH", 1)
            terrainSlide = f("TERRAIN SLIDE", 1)
            scaleX = f("SCALE X", 1)
            textureScale = f("TEXTURE SCALE", 10)
            bumpScale = f("BUMP SCALE", 0)
            valleyHeight = f("VALLEY HEIGHT", 0)
            valleyCentre = f("VALLEY CENTRE", 0)
            valleyWidth = f("VALLEY WIDTH", 0)
            valleySharp = f("VALLEY SHARP", 0)
            focalDepth = f("FOCAL DEPTH", 50)
            focalVariance = f("FOCAL VARIANCE", 50)
            lod = f("LOD", 0)
            feedback = f("FEEDBACK", 0.9)
            feedbackX = f("FEEDBACK X", 1)
            feedbackY = f("FEEDBACK Y", 1)
            feedbackRot = f("FEEDBACK ROT", 0)
            skyBlur = f("SKY BLUR", 0)
            posX = f("POS X", 0)
            posY = f("POS Y", 8)
            posZ = f("POS Z", 0)
            targetX = f("TARGET X", 0)
            targetY = f("TARGET Y", -3)
            targetZ = f("TARGET Z", 156)
            posSpeed = f("POS SPEED", 20)
            timeAhead = f("TIME AHEAD", 159)
            zoom = f("ZOOM", 65)
            fade = f("FADE COLOUR", 0)
            colour = SIMD3(f("COLOUR R", 10), f("COLOUR G", 10), f("COLOUR B", 10))
            colourScale = f("COLOUR SCALE", 0)
            colourBias = f("COLOUR BIAS", 0)
            line = SIMD3(f("LINE R", 0), f("LINE G", 0), f("LINE B", 0))
            fogMin = f("FOG MIN", 30)
            fogMax = f("FOG MAX", 70)
            fog = SIMD3(f("FOG R", 0), f("FOG G", 0), f("FOG B", 0))
        }
    }

    /// A group of presets, as the console's player keeps them, and how long each one stays.
    struct Group: Sendable, Equatable {
        let name: String
        let presets: [String]
        let stay: Float
    }

    struct Level: Sendable {
        let width: Int
        let height: Int
        let rgba: [UInt8]
    }

    struct Pack: Sendable {
        let groups: [Group]
        /// Every preset's settings, read once.
        let params: [String: Params]
        /// The normal map's levels, largest first, as RGBA.
        let normalMap: [Level]
    }

    private static let menus = ["CANYON_LINE.mnu", "CANYON_TERRAIN.mnu", "CANYON_BLUR.mnu", "CANYON_CAMERA.mnu",
                                "CANYON_COLOUR.mnu", "CANYON_FOG.mnu"]

    // The player's groups, in its order, with the presets toured of each and how many seconds each stays: those that
    // draw the land. The player's own list also has Bir_A to C, Volvic, Voyage01 and Voyage04, whose TIME AHEAD sits
    // short of the rows coming in, so the rise throws every new row into spikes; and the pack holds a Voyage11 the
    // player never shows.
    private static let tour: [(String, [Int], Float)] = [
        ("Voyage", [2, 3, 5, 6, 7, 8, 9, 10], 16),
        ("Volcano", [1, 2, 3, 4], 20),
        ("Blue", [1, 2, 3, 4], 20),
    ]

    /// The group the player starts on: Blue, as the console's does.
    static let firstGroup = 2

    /// canyon.qrc's files read into a pack, or nil when none of the toured presets is there.
    static func pack(files: [String: [UInt8]]) -> Pack? {
        var names = Set<String>()
        for key in files.keys where key.hasPrefix("override/") {
            let rest = key.dropFirst("override/".count)
            if let slash = rest.firstIndex(of: "/"), slash != rest.startIndex { names.insert(String(rest[..<slash])) }
        }
        let groups = tour.compactMap { prefix, numbers, stay -> Group? in
            let list = numbers.map { prefix + ($0 < 10 ? "0" : "") + String($0) }.filter(names.contains)
            return list.isEmpty ? nil : Group(name: prefix, presets: list, stay: stay)
        }
        guard !groups.isEmpty else { return nil }
        var params: [String: Params] = [:]
        for preset in groups.flatMap(\.presets) {
            var m: [String: Float] = [:]
            for menu in menus {
                if let base = files["canyon/" + menu] { m.merge(XmbWaveFirmware.parseMnu(base)) { $1 } }
                if let own = files["override/\(preset)/canyon/" + menu] { m.merge(XmbWaveFirmware.parseMnu(own)) { $1 } }
            }
            params[preset] = Params(m)
        }
        let normal = files["canyon/textures/DDS/canyon_normalmap.dds"].map(dds) ?? []
        return Pack(groups: groups, params: params, normalMap: normal)
    }

    /// An uncompressed 32-bit DDS (A8R8G8B8, as the firmware's normal map is), every level, as RGBA.
    static func dds(_ b: [UInt8]) -> [Level] {
        guard b.count >= 128, b[0 ..< 4].elementsEqual("DDS ".utf8) else { return [] }
        func le(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24 }
        let h = le(12), w = le(16)
        // A sane size, so a damaged header cannot ask for gigabytes.
        guard w > 0, h > 0, w <= 4096, h <= 4096 else { return [] }
        let mips = min(max(1, le(28)), 16)
        guard le(88) == 32, le(92) == 0xff0000, le(96) == 0xff00, le(100) == 0xff else { return [] }
        var out: [Level] = []
        var o = 128, lw = w, lh = h
        for _ in 0 ..< mips {
            let n = lw * lh * 4
            if o + n > b.count { return out }
            var rgba = [UInt8](repeating: 0, count: n)
            for i in 0 ..< lw * lh {
                rgba[i * 4] = b[o + i * 4 + 2]
                rgba[i * 4 + 1] = b[o + i * 4 + 1]
                rgba[i * 4 + 2] = b[o + i * 4]
                rgba[i * 4 + 3] = b[o + i * 4 + 3]
            }
            out.append(Level(width: lw, height: lh, rgba: rgba))
            o += n
            lw = max(1, lw / 2)
            lh = max(1, lh / 2)
        }
        return out
    }
}

/// Where Valley is in its presets, kept while the app runs so it comes back where it was.
@MainActor enum XmbValleyPresets {
    static var group = XmbValleyFirmware.firstGroup
    static var preset = 0
    /// Right stick or swipe, one step, picked up on the next tick: 1 and 2 another preset, 3 and 4 another group.
    static var flicks = 0

    /// x -1 or 1 another preset of the group, y -1 or 1 another group.
    static func flick(_ dx: Int, _ dy: Int) {
        if dx < 0 { flicks = 1 } else if dx > 0 { flicks = 2 } else if dy < 0 { flicks = 3 } else if dy > 0 { flicks = 4 }
    }
}

/// The terrain, worked out on the CPU frame by frame as the firmware does it, sixty times a second. `vertices` holds
/// the ring (128 rows of 128 points: position, normal, texture coordinates).
final class XmbValleyScene {
    static let rows = 128
    static let cols = 128
    /// Floats a point: position (x, y, z, the row's own height), normal, texture coordinates.
    static let stride = 11

    private(set) var params: XmbValleyFirmware.Params?
    private(set) var vertices = [Float](repeating: 0, count: rows * cols * stride)
    private var z = [Float](repeating: 0, count: rows)
    private var slot = 0
    private(set) var rowZ: Float = 0
    private(set) var travelled: Float = -10
    private var prevY = [Float](repeating: 0, count: cols)
    private var vel = [Float](repeating: 0, count: cols)
    private var newY = [Float](repeating: 0, count: cols)
    private var target = [Float](repeating: 0, count: cols)
    private var spectrum = [Float](repeating: 0, count: cols)
    private var seed: Int32 = 0

    /// The newest row's slot: the strip from it to the oldest is the ring's seam, not drawn.
    var newest: Int { (slot + Self.rows - 1) % Self.rows }

    /// The engine's own random numbers, -1...1: its integer hash noise.
    func rand() -> Float {
        seed &+= 1
        return XmbWaveModel.noise(seed)
    }

    /// A new preset: its settings, and the terrain started over flat, rows rising into place.
    func start(_ p: XmbValleyFirmware.Params) {
        params = p
        travelled = -10
        rowZ = 0
        slot = 0
        for c in 0 ..< Self.cols {
            prevY[c] = 0
            vel[c] = 0
        }
        for r in 0 ..< Self.rows {
            z[r] = Float(r)
            for c in 0 ..< Self.cols {
                let i = (r * Self.cols + c) * Self.stride
                let e = 2 * abs(0.5 - Float(c) / 128)
                vertices[i] = Float(c) - 64
                vertices[i + 1] = 0
                vertices[i + 2] = Float(r)
                vertices[i + 3] = 0
                vertices[i + 4] = 0
                vertices[i + 5] = 1
                vertices[i + 6] = 0
                vertices[i + 7] = Float(c) / 127
                vertices[i + 8] = Float(r) / 127
                vertices[i + 9] = Float(r) - 50 * e * e
                vertices[i + 10] = rand() * 500
            }
        }
    }

    /// One sixtieth of a second: the music's row of `input` (`XmbVisualizerLevels.columns` points, 0...3) weighted
    /// and scaled as the firmware does, the camera moved on, and a new row when it is due.
    func step(_ dt: Float, _ input: [Float]) {
        guard let p = params else { return }
        var top: Float = 1
        for c in 0 ..< Self.cols {
            let v = input[c] * exp(p.terrainWidth * (1 - 2 * abs(0.5 - Float(c) / 128)))
            spectrum[c] = v
            if v > top { top = v }
        }
        for c in 0 ..< Self.cols { spectrum[c] /= top }
        travelled += dt * p.posSpeed
        if rowZ < travelled {
            rowZ += 1
            row(p)
        }
        if travelled > 99 { rebase() }
    }

    private func row(_ p: XmbValleyFirmware.Params) {
        let cols = Self.cols
        let x0 = -64 - 0.25 * p.terrainSlide
        for c in 0 ..< cols {
            let u = Float(c) / 128 - 0.5
            let s = min(max((abs(2 * u - p.valleyCentre) - p.valleyWidth) * p.valleySharp, 0), 1)
            let t = s * s * (3 - 2 * s) * p.valleyHeight + spectrum[c] + (rand() * 0.5 + 0.5) * 0.02
            target[c] = t
            let d = 0.7 + 0.295 * abs(2 * u)
            let v = vel[c] + (t - prevY[c]) * (1 - d)
            newY[c] = prevY[c] + v
            vel[c] = v * (d * 0.5)
        }
        // Five passes of smoothing across the row, in place, the edges against themselves.
        for _ in 0 ..< 5 {
            for c in 0 ..< cols {
                let l = newY[max(c - 1, 0)]
                let r = newY[min(c + 1, cols - 1)]
                newY[c] += 0.25 * (l + r - 2 * newY[c])
            }
        }
        let s = slot
        let zz = rowZ + 127
        z[s] = zz
        for c in 0 ..< cols {
            // The normal: across the row, and from the row before (one unit back).
            let l = max(c - 1, 0)
            let r = min(c + 1, cols - 1)
            let ax = Float(l - r)
            let ay = newY[l] - newY[r]
            let by = newY[c] - prevY[c]
            // cross((ax, ay, 0), (0, by, 1))
            var nx = ay
            var ny = -ax
            var nz = ax * by
            var len = (nx * nx + ny * ny + nz * nz).squareRoot()
            if !(len > 0) { len = 1 }
            nx /= len
            ny /= len
            nz /= len
            let e = 2 * abs(0.5 - Float(c) / 128)
            let i = (s * cols + c) * Self.stride
            vertices[i] = x0 + Float(c)
            vertices[i + 1] = newY[c]
            vertices[i + 2] = zz
            vertices[i + 3] = target[c]
            vertices[i + 4] = nx
            vertices[i + 5] = ny
            vertices[i + 6] = nz
            vertices[i + 9] = zz + 1 - 50 * e * e
            vertices[i + 10] = rand() * 500
        }
        prevY = newY
        slot = (slot + 1) % Self.rows
    }

    // Brought back toward 0 now and then, as the firmware does, so the numbers stay small.
    private func rebase() {
        let shift = travelled
        travelled -= shift
        rowZ -= shift
        for r in 0 ..< Self.rows {
            z[r] -= shift
            for c in 0 ..< Self.cols {
                let i = (r * Self.cols + c) * Self.stride
                let e = 2 * abs(0.5 - Float(c) / 128)
                vertices[i + 2] = z[r]
                vertices[i + 9] = z[r] + 1 - 50 * e * e
            }
        }
    }
}

/// Valley's tour of its presets, a sixtieth of a second at a time: the pack's groups, the scene, and how long the
/// preset on screen has left.
final class XmbValleyTour {
    let pack: XmbValleyFirmware.Pack
    let scene = XmbValleyScene()
    private(set) var group: Int
    private(set) var preset: Int
    private var presetLeft: Float = 0
    /// Counts each new preset, so the drawing knows to make its colours again and start its feedback over.
    private(set) var started = 0

    var params: XmbValleyFirmware.Params? { scene.params }

    init(pack: XmbValleyFirmware.Pack, group: Int, preset: Int) {
        self.pack = pack
        self.group = min(max(group, 0), pack.groups.count - 1)
        self.preset = preset
        startPreset()
    }

    /// One step: `flick` is the stick's last push (0 none, 1 and 2 another preset, 3 and 4 another group), `input`
    /// the console's spectrum now.
    func tick(_ input: [Float], flick: Int, step: Float) {
        switch flick {
        case 1, 2: otherPreset()
        case 3, 4: otherGroup()
        default: break
        }
        presetLeft -= step
        if presetLeft <= 0 { otherPreset() }
        scene.step(step, input)
    }

    private func otherPreset() {
        let g = pack.groups[min(max(group, 0), pack.groups.count - 1)]
        if g.presets.count > 1 {
            let n = g.presets.count
            var i = min(max(Int((scene.rand() * 0.5 + 0.5) * Float(n - 1)), 0), n - 2)
            if i >= preset { i += 1 }
            preset = i
        }
        startPreset()
    }

    private func otherGroup() {
        let n = pack.groups.count
        if n > 1 {
            var g = min(max(Int((scene.rand() * 0.5 + 0.5) * Float(n - 1)), 0), n - 2)
            if g >= group { g += 1 }
            group = g
        }
        preset = 0
        startPreset()
    }

    private func startPreset() {
        let g = pack.groups[min(max(group, 0), pack.groups.count - 1)]
        let name = g.presets[min(max(preset, 0), g.presets.count - 1)]
        guard let p = pack.params[name] else { return }
        scene.start(p)
        presetLeft = g.stay
        started += 1
    }

    /// The colour by angle: 1 - i/511 to the power of COLOUR R/G/B, scaled and biased, faded; 512 RGBA texels.
    static func fresLut(_ p: XmbValleyFirmware.Params) -> [Float] {
        let fade = p.fade * p.fade
        var data: [Float] = []
        data.reserveCapacity(512 * 4)
        for i in 0 ..< 512 {
            let t = 1 - Float(i) / 511
            for k in 0 ..< 3 { data.append((Float(pow(Double(t), Double(p.colour[k]))) * p.colourScale + p.colourBias) * fade) }
            data.append(1)
        }
        return data
    }
}
