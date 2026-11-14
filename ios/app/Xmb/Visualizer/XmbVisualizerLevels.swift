// SPDX-License-Identifier: GPL-2.0-only
// A song's levels for the music visualizers, worked out ahead of time, and the pure parts they share; ported from Android's XmbVisualizer.kt.

import Foundation
import simd

/// How loud a song is, band by band, thirty times a second, and the console's own spectrum sixty times a second, read
/// from the decoded song once rather than listened to as it plays. The last 1024 samples at each thirtieth of a second
/// go through an FFT into `bands` bands from 50 Hz up; the console's spectrum is worked out as its music player does
/// for the visualizers (soundvisualizer_plugin). The drawing follows the player's position through both.
struct XmbVisualizerLevels: Sendable, Equatable {
    static let fps = 30
    static let bands = 16
    /// The console's spectrum, frames a second: one a frame, as its music player hands it over.
    static let spectrumFPS = 60
    /// Its points a frame: the 64 of each channel that make one of Valley's rows.
    static let columns = 128
    /// Two hours a song; past that the bands rest.
    static let maxFrames = fps * 60 * 120
    /// Twenty minutes a song; past that, Valley rests.
    static let maxSpectrumFrames = spectrumFPS * 60 * 20

    private static let magic = 0x5856_4953 // "XVIS"
    private static let version = 2

    let frames: Int
    let data: [UInt8]
    let spectrumFrames: Int
    let spectrum: [UInt8]

    /// Band `band` at `frame`, 0...1.
    func band(_ frame: Int, _ band: Int) -> Float {
        guard frame >= 0, frame < frames else { return 0 }
        return Float(data[frame * Self.bands + band]) / 255
    }

    /// The console's spectrum at `frame` (`spectrumFPS` a second), point `c` of `columns`: 0...3, as its music
    /// player gives it. Points 0...63 are the left channel from the lows up, 64...127 the right from the highs down.
    func column(_ frame: Int, _ c: Int) -> Float {
        guard frame >= 0, frame < spectrumFrames else { return 0 }
        return Float(spectrum[frame * Self.columns + c]) * (3 / 255)
    }

    // MARK: - The cache file

    /// As Android's DataOutputStream writes it: big-endian ints, so a cache moved between the two still reads.
    func encoded() -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(36 + data.count + spectrum.count)
        func int(_ v: Int) { out += [UInt8(truncatingIfNeeded: v >> 24), UInt8(truncatingIfNeeded: v >> 16), UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v)] }
        int(Self.magic)
        int(Self.version)
        int(Self.bands)
        int(frames)
        out += data
        int(Self.spectrumFPS)
        int(Self.columns)
        int(spectrumFrames)
        out += spectrum
        return out
    }

    /// A cache file read back, or nil for anything that is not one of ours whole.
    init?(cache bytes: [UInt8]) {
        var at = 0
        func int() -> Int? {
            guard at + 4 <= bytes.count else { return nil }
            defer { at += 4 }
            return Int(Int32(bitPattern: UInt32(bytes[at]) << 24 | UInt32(bytes[at + 1]) << 16 | UInt32(bytes[at + 2]) << 8 | UInt32(bytes[at + 3])))
        }
        func take(_ n: Int) -> [UInt8]? {
            guard n >= 0, n <= bytes.count - at else { return nil }
            defer { at += n }
            return Array(bytes[at ..< at + n])
        }
        guard int() == Self.magic, int() == Self.version, int() == Self.bands, let frames = int(), frames > 0,
              frames <= Self.maxFrames, let data = take(frames * Self.bands),
              int() == Self.spectrumFPS, int() == Self.columns, let spectrumFrames = int(), spectrumFrames >= 0,
              spectrumFrames <= Self.maxSpectrumFrames, let spectrum = take(spectrumFrames * Self.columns) else { return nil }
        self.init(frames: frames, data: data, spectrumFrames: spectrumFrames, spectrum: spectrum)
    }

    init(frames: Int, data: [UInt8], spectrumFrames: Int, spectrum: [UInt8]) {
        self.frames = frames
        self.data = data
        self.spectrumFrames = spectrumFrames
        self.spectrum = spectrum
    }
}

/// Interleaved float samples in, a frame of band energies (dB) out every 1/`fps` s from the last 1024 samples, and the
/// console's spectrum beside it.
final class XmbVisualizerAnalyzer {
    private static let n = 1024
    private let rate: Int
    private let channels: Int
    private var ring = [Float](repeating: 0, count: n)
    private var at = 0
    private var filled = 0
    private var sinceFrame = 0
    private let hop: Int
    private var out: [Float] = []
    private(set) var frames = 0
    private var re = [Float](repeating: 0, count: n)
    private var im = [Float](repeating: 0, count: n)
    private let window = (0 ..< n).map { Float(0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1))) }
    private let cosT = (0 ..< n / 2).map { Float(cos(2 * Double.pi * Double($0) / Double(n))) }
    private let sinT = (0 ..< n / 2).map { Float(sin(2 * Double.pi * Double($0) / Double(n))) }
    private let rev = (0 ..< n).map { xmbBitReverse($0, bits: 10) }
    private let edges: [Int]
    private let console: XmbConsoleSpectrum

    init(rate: Int, channels: Int) {
        let rate = max(rate, 1)
        self.rate = rate
        self.channels = max(channels, 1)
        hop = max(rate / XmbVisualizerLevels.fps, 1)
        // FFT bins bounding each band, log-spaced from 50 Hz to 16 kHz (or half the rate).
        let top = min(16_000.0, Double(rate) / 2 - 1)
        let bands = XmbVisualizerLevels.bands
        edges = (0 ... bands).map { b in
            let hz = 50 * pow(top / 50, Double(b) / Double(bands))
            return min(max(Int(hz * Double(Self.n) / Double(rate)), 1), Self.n / 2 - 1)
        }
        console = XmbConsoleSpectrum(rate: rate)
    }

    /// Samples as the decoder gives them, `channels` to a frame.
    func feed(_ samples: UnsafeBufferPointer<Float>) {
        let c = channels
        var i = 0
        while i + c <= samples.count {
            var s: Float = 0
            for k in 0 ..< c { s += samples[i + k] }
            let l = samples[i]
            push(s / Float(c))
            console.push(l, c > 1 ? samples[i + 1] : l)
            i += c
        }
    }

    private func push(_ sample: Float) {
        ring[at] = sample
        at = (at + 1) % Self.n
        if filled < Self.n { filled += 1 }
        sinceFrame += 1
        if sinceFrame >= hop {
            sinceFrame = 0
            frame()
        }
    }

    private func frame() {
        let n = Self.n
        for i in 0 ..< n {
            let s = i < n - filled ? 0 : ring[(at + i) % n]
            re[rev[i]] = s * window[i]
            im[rev[i]] = 0
        }
        xmbFFT(&re, &im, n, cos: cosT, sin: sinT)
        let bands = XmbVisualizerLevels.bands
        for b in 0 ..< bands {
            let lo = edges[b]
            let hi = max(edges[b + 1], lo + 1)
            var e: Float = 0
            for k in lo ..< hi { e += re[k] * re[k] + im[k] * im[k] }
            out.append(Float(10 * log10(Double(e / Float(hi - lo)) + 1e-12)))
        }
        frames += 1
    }

    /// 0...1 per band: each band against its own loud end (its 97th percentile), across 40 dB, so the quiet top
    /// bands move as much as the bass does. Nil with no frame at all.
    func levels() -> XmbVisualizerLevels? {
        // The read stops after the buffer that crossed the limit, and a longer file would not read back from the cache.
        let frames = min(self.frames, XmbVisualizerLevels.maxFrames)
        guard frames > 0 else { return nil }
        let bands = XmbVisualizerLevels.bands
        var data = [UInt8](repeating: 0, count: frames * bands)
        for b in 0 ..< bands {
            let column = (0 ..< frames).map { out[$0 * bands + b] }
            let sorted = column.sorted()
            let ceiling = sorted[Int(Float(frames - 1) * 0.97)]
            let floor = ceiling - 40
            for f in 0 ..< frames {
                let v = min(max((column[f] - floor) / (ceiling - floor), 0), 1)
                data[f * bands + b] = UInt8(Int(v * 255))
            }
        }
        return XmbVisualizerLevels(frames: frames, data: data, spectrumFrames: console.frames, spectrum: console.bytes())
    }
}

/// `i`'s lowest `bits` bits in reverse order, where the FFT puts sample i.
private func xmbBitReverse(_ i: Int, bits: Int) -> Int {
    var r = 0
    for b in 0 ..< bits where i & (1 << b) != 0 { r |= 1 << (bits - 1 - b) }
    return r
}

/// An iterative radix-2 FFT of `n` points, in place, on bit-reversed input.
// Pointers and while loops, as a Debug build runs array subscripts and ranges here several times slower.
private func xmbFFT(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>, _ n: Int, cos cosT: UnsafePointer<Float>,
                    sin sinT: UnsafePointer<Float>) {
    var size = 2
    while size <= n {
        let half = size / 2
        let step = n / size
        var start = 0
        while start < n {
            var k = 0
            while k < half {
                let wr = cosT[k * step]
                let wi = -sinT[k * step]
                let a = start + k
                let b = a + half
                let tr = re[b] * wr - im[b] * wi
                let ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr
                im[b] = im[a] - ti
                re[a] += tr
                im[a] += ti
                k += 1
            }
            start += size
        }
        size *= 2
    }
}

/// The console's own spectrum, worked out as its music player does for the visualizers: each channel's last 512
/// samples through a Gaussian window and an FFT, the magnitudes smoothed across 17 bins, read along a log-frequency
/// scale of 64 steps an octave, and pressed into 3 ln(1 + 512 m) / ln 513. The console runs at 48 kHz, so the scale is
/// read at the same frequencies for a song at any other rate.
final class XmbConsoleSpectrum {
    private static let size = 512
    private static let ln513 = log(Float(513))

    // The console's window: a Gaussian across 2n + 1 points, x from -2.355 to 2.355. Its first point ends up 0: the
    // firmware subtracts the first point from each in turn, starting with itself.
    static func gaussian(_ n: Int) -> [Float] {
        var w = (0 ... 2 * n).map { i -> Float in
            let x = Float(2.355 * Double(i - n) / Double(n))
            return Float(pow(2.7, Double(-(x * x) / 0.56)) * 2.5 / Double(Float(6.36159).squareRoot()))
        }
        w[0] = 0
        return w
    }

    private static let window = gaussian(size / 2)
    private static let kernel = gaussian(8)
    private static let kernelSum = kernel.reduce(0, +)
    private static let cosT = (0 ..< size / 2).map { Float(cos(2 * Double.pi * Double($0) / Double(size))) }
    private static let sinT = (0 ..< size / 2).map { Float(sin(2 * Double.pi * Double($0) / Double(size))) }
    private static let rev = (0 ..< size).map { xmbBitReverse($0, bits: 9) }

    private let rate: Int
    private var ringL = [Float](repeating: 0, count: size)
    private var ringR = [Float](repeating: 0, count: size)
    private var at = 0
    private var count = 0
    private var due = size / 2
    private(set) var frames = 0
    private var out: [UInt8] = []
    private var re = [Float](repeating: 0, count: size)
    private var im = [Float](repeating: 0, count: size)
    private var smooth = [Float](repeating: 0, count: size / 2)
    private var position = [Float](repeating: 0, count: XmbVisualizerLevels.columns)

    init(rate: Int) {
        let rate = max(rate, 1)
        self.rate = rate
        // Where each point reads the spectrum, in this song's bins: the console's log scale (64 steps an octave over
        // its 256 bins) at the console's 48 kHz.
        let top = pow(2.0, 255.015625 / 64) - 1
        func at(_ j: Int) -> Float { min(Float(255 * (pow(2.0, (Double(j) + 1.0 / 64) / 64) - 1) / top * 48_000 / Double(rate)), 255) }
        for i in 0 ..< 64 {
            let j = i * 255 / 64
            position[i] = at(j)
            position[64 + i] = at(255 - j)
        }
    }

    func push(_ l: Float, _ r: Float) {
        ringL[at] = l
        ringR[at] = r
        at = (at + 1) & (Self.size - 1)
        count += 1
        if count < due { return }
        // Centred on its moment: frame f covers the samples either side of f / spectrumFPS s.
        if frames < XmbVisualizerLevels.maxSpectrumFrames { frame() }
        due = Self.size / 2 + frames * rate / XmbVisualizerLevels.spectrumFPS
    }

    func bytes() -> [UInt8] { Array(out.prefix(frames * XmbVisualizerLevels.columns)) }

    private func frame() {
        channel(ringL)
        for i in 0 ..< 64 { out.append(level(position[i])) }
        channel(ringR)
        for i in 64 ..< XmbVisualizerLevels.columns { out.append(level(position[i])) }
        frames += 1
    }

    private func channel(_ ring: [Float]) {
        let size = Self.size
        for i in 0 ..< size {
            re[Self.rev[i]] = ring[(at + i) & (size - 1)] * Self.window[i]
            im[Self.rev[i]] = 0
        }
        xmbFFT(&re, &im, size, cos: Self.cosT, sin: Self.sinT)
        // Magnitudes over N, then spread across the kernel's 17 bins.
        let kernel = Self.kernel, kernelSum = Self.kernelSum
        smooth.withUnsafeMutableBufferPointer { smooth in
            smooth.update(repeating: 0)
            for j in 0 ..< size / 2 {
                let m = (re[j] * re[j] + im[j] * im[j]).squareRoot() / Float(size) / kernelSum
                // Only the taps that land inside the spectrum.
                var t = max(0, 8 - j)
                let end = min(kernel.count, size / 2 + 8 - j)
                while t < end {
                    smooth[j - 8 + t] += m * kernel[t]
                    t += 1
                }
            }
        }
    }

    private func level(_ p: Float) -> UInt8 {
        let i0 = Int(p.rounded(.down))
        let f = p - Float(i0)
        let m = f > 0 && i0 + 1 < Self.size / 2 ? smooth[i0] * (1 - f) + smooth[i0 + 1] * f : smooth[i0]
        let s = 3 * log(1 + 512 * m) / Self.ln513
        return UInt8(min(max(Int(s / 3 * 255 + 0.5), 0), 255))
    }
}

/// How hard the song pushes now, 0...1, from its levels at the player's position: mostly the bass, with a kick on each
/// sudden rise over its recent average (a beat), quick to rise and slow to fall as a meter is. Asked once a frame.
struct XmbMusicDrive {
    private var level: Float = 0
    private var average: Float = 0

    mutating func next(_ levels: XmbVisualizerLevels?, positionMs: Int, playing: Bool) -> Float {
        guard let levels, playing else {
            level *= 0.95
            return level
        }
        let frame = positionMs * XmbVisualizerLevels.fps / 1000
        var bass: Float = 0
        for b in 0 ..< 4 { bass += levels.band(frame, b) }
        var mid: Float = 0
        for b in 4 ..< 10 { mid += levels.band(frame, b) }
        let energy = bass / 4 * 0.7 + mid / 6 * 0.3
        average += (energy - average) * 0.03
        let beat = max(energy - average, 0) * 2.5
        let target = min(max(energy * 0.75 + beat, 0), 1)
        level += (target - level) * (target > level ? 0.4 : 0.07)
        return level
    }
}

/// The console's views are made for its 16:9 screen. A narrower one sees as much across as that and more above and
/// below, rather than losing the sides: `fovy` (degrees, for 16:9) as it needs to be at `aspect`.
func xmbFovy(_ fovy: Float, aspect: Float) -> Float {
    if aspect >= 16 / 9 { return fovy }
    let half = Double(fovy / 2) * Double.pi / 180
    return min(Float(2 * atan(tan(half) * (16.0 / 9.0) / Double(aspect)) * 180 / Double.pi), 150)
}

/// The camera matrices Android's visualizers make with android.opengl.Matrix, the projection with Metal's depth of 0
/// to 1 where GL's runs -1 to 1.
enum XmbVisualizerMatrix {
    static func lookAt(_ eye: SIMD3<Float>, _ center: SIMD3<Float>, _ up: SIMD3<Float>) -> simd_float4x4 {
        let f = simd_normalize(center - eye)
        let s = simd_normalize(simd_cross(f, up))
        let u = simd_cross(s, f)
        return simd_float4x4(columns: (SIMD4(s.x, u.x, -f.x, 0), SIMD4(s.y, u.y, -f.y, 0), SIMD4(s.z, u.z, -f.z, 0),
                                       SIMD4(-simd_dot(s, eye), -simd_dot(u, eye), simd_dot(f, eye), 1)))
    }

    static func perspective(fovy: Float, aspect: Float, near: Float, far: Float) -> simd_float4x4 {
        let f = 1 / tan(fovy * Float(Double.pi / 360))
        let range = 1 / (near - far)
        return simd_float4x4(columns: (SIMD4(f / aspect, 0, 0, 0), SIMD4(0, f, 0, 0), SIMD4(0, 0, far * range, -1),
                                       SIMD4(0, 0, near * far * range, 0)))
    }
}

/// The visualizers to choose between in Now Playing: the console's, then the PSP's lines. The raw value is what
/// Android keeps under the same preference key.
enum XmbVisualizerKind: String, CaseIterable, Sendable {
    case waves, valley, gaia, lines

    static let preferenceKey = "xmb.music.visualizer"

    /// `step` along the list, round from either end.
    func stepped(_ step: Int) -> XmbVisualizerKind {
        let all = Self.allCases
        let i = all.firstIndex(of: self) ?? 0
        return all[((i + step) % all.count + all.count) % all.count]
    }
}

/// The console's glare weights (glutils): a gaussian of `radius` texels for each colour, floor(3 radius) - 1 texels to a
/// side, as the middle and seven to each side; and each size's share, pow(GLARE SUM POW, n) over all of them, times
/// GLARE LEVEL, n = 0 the largest.
enum XmbGlare {
    static func gaussian(_ radius: SIMD3<Float>) -> [SIMD3<Float>] {
        var out = [SIMD3<Float>](repeating: .zero, count: 8)
        for ch in 0 ..< 3 {
            let r = max(radius[ch], 0.01)
            let side = min(max(Int((3 * r).rounded(.down)) - 1, 0), 7)
            let g = (0 ..< 8).map { k -> Float in k <= side ? exp(-Float(k * k) / (2 * r * r)) : 0 }
            let total = g[0] + 2 * Float((1 ... 7).reduce(0.0) { $0 + Double(g[$1]) })
            for k in 0 ..< 8 { out[k][ch] = g[k] / total }
        }
        return out
    }

    static func levelWeights(sumPow: Float, level: Float, count: Int) -> [Float] {
        let p = Double(sumPow)
        let total = (0 ..< count).reduce(0.0) { $0 + pow(p, Double($1)) }
        return (0 ..< count).map { Float(pow(p, Double($0)) / total * Double(level)) }
    }
}

/// java.util.Random, for the noise and stars Android makes with it, so they come out the same.
struct XmbJavaRandom {
    private var seed: Int64

    init(_ seed: Int64) { self.seed = (seed ^ 0x5DEE_CE66D) & ((1 << 48) - 1) }

    mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* 0x5DEE_CE66D &+ 0xB) & ((1 << 48) - 1)
        return Int32(truncatingIfNeeded: seed >> (48 - bits))
    }

    mutating func nextInt(_ bound: Int32) -> Int32 {
        if bound & -bound == bound { return Int32(truncatingIfNeeded: (Int64(bound) * Int64(next(31))) >> 31) }
        var bits: Int32, value: Int32
        repeat {
            bits = next(31)
            value = bits % bound
        } while bits &- value &+ (bound - 1) < 0
        return value
    }

    mutating func nextDouble() -> Double { Double(Int64(next(26)) << 27 + Int64(next(27))) * 0x1.0p-53 }

    mutating func nextFloat() -> Float { Float(next(24)) / Float(1 << 24) }
}

/// The firmware's integer hash noise as Valley and Gaia step it, 0...1.
func xmbHash01(_ seed: Int32) -> Float {
    let n = (seed &<< 13) ^ seed
    let v = (n &* (n &* n &* 15731 &+ 789221) &+ 1376312589) & 0x7fffffff
    return Float(v) / 2147483648
}
