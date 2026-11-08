// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's EdgeAnim.kt: Sony Edge skeletons and clips. A theme is hostile input, read only through ByteReader.

import Foundation

enum EdgeAnim {
    final class Skeleton {
        let parents: [Int]
        /// 10 floats a joint: rotation x y z w, translation x y z, scale x y z.
        let rest: [Float]
        var joints: Int { parents.count }

        init(parents: [Int], rest: [Float]) {
            self.parents = parents
            self.rest = rest
        }
    }

    /// 4 floats a key for rotations, 3 otherwise.
    struct Channel {
        let joint: Int
        let kind: Int
        let frames: [Float]
        let values: [Float]
        var width: Int { kind == ROTATION ? 4 : 3 }
    }

    final class Clip {
        let duration: Float
        let frameRate: Float
        let frames: Int
        let channels: [Channel]

        init(duration: Float, frameRate: Float, frames: Int, channels: [Channel]) {
            self.duration = duration
            self.frameRate = frameRate
            self.frames = frames
            self.channels = channels
        }
    }

    // EdgeAnim.kt 51-59.
    static let ROTATION = 0
    static let TRANSLATION = 1

    private static let SKELETON_TAG = 0x45533031 // "ES01"
    private static let CLIP_TAG = 0x45413033 // "EA03"
    static let MAX_JOINTS = 256
    static let MAX_FRAMES = 1 << 16
    static let MAX_CHANNELS = 1024

    static func skeleton(_ b: [UInt8]) -> Skeleton? {
        let r = ByteReader(b)
        // Swift: one do/catch stands for the Kotlin's Damaged and RuntimeException catches.
        do {
            if r.count < 0x30 { return nil }
            if try r.be32(0) != SKELETON_TAG { return nil }
            let n = try r.be16(0x0C)
            if n > MAX_JOINTS { throw ThemeDamaged() }
            let restAt = try rel(r, 0x14)
            let parentsAt = try rel(r, 0x18)
            var rest = [Float](repeating: 0, count: n * 10)
            var parents = [Int](repeating: 0, count: n)
            for j in 0 ..< n {
                let at = restAt + 48 * j
                for k in 0 ..< 4 { rest[10 * j + k] = try f32(r, at + 4 * k) }
                for k in 0 ..< 3 { rest[10 * j + 4 + k] = try f32(r, at + 16 + 4 * k) }
                for k in 0 ..< 3 { rest[10 * j + 7 + k] = try f32(r, at + 32 + 4 * k) }
                // Swift: `be16(...).toShort().toInt()` is Int(Int16(truncatingIfNeeded:)).
                let p = Int(Int16(truncatingIfNeeded: try r.be16(parentsAt + 2 * j)))
                if p >= j { throw ThemeDamaged() } // parents come first
                parents[j] = p
            }
            return rest.allSatisfy { $0.isFinite } ? Skeleton(parents: parents, rest: rest) : nil
        } catch {
            return nil
        }
    }

    /// 12 floats a joint: a 3x4 matrix, row-major. Nil when there are not `joints` of them.
    static func inverseBinds(_ b: [UInt8], joints: Int) -> [Float]? {
        // Swift: a negative joint count is refused, where Kotlin's FloatArray would have thrown uncaught.
        guard joints >= 0, b.count / 48 >= joints else { return nil }
        let r = ByteReader(b)
        var out = [Float](repeating: 0, count: 12 * joints)
        do {
            for i in 0 ..< out.count { out[i] = try f32(r, 4 * i) }
        } catch {
            return nil
        }
        return out.allSatisfy { $0.isFinite } ? out : nil
    }

    static func clip(_ b: [UInt8], joints: Int) -> Clip? {
        // Swift: one do/catch stands for the Kotlin's Damaged and RuntimeException catches.
        do {
            return try decodeClip(ByteReader(b), joints: joints)
        } catch {
            return nil
        }
    }

    private static func decodeClip(_ r: ByteReader, joints: Int) throws -> Clip? {
        if r.count < 0x60 { return nil }
        if try r.be32(0) != CLIP_TAG { return nil }
        let duration = try f32(r, 4)
        let rate = try f32(r, 8)
        let clipJoints = try r.be16(0x0E)
        let frames = try r.be16(0x10)
        let sets = try r.be16(0x12)
        if !(rate > 0) || !(1 ... MAX_FRAMES).contains(frames) || !(1 ... frames).contains(sets) { return nil }
        var counts = [Int](repeating: 0, count: 8) // const R T S U, anim R T S U
        for i in 0 ..< 8 { counts[i] = try r.be16(0x16 + 2 * i) }
        // A clip that holds the rest pose: no channels, one frame, no length.
        if counts.allSatisfy({ $0 == 0 }) { return Clip(duration: 0, frameRate: rate, frames: frames, channels: []) }
        if !(duration > 0) { return nil }
        if counts.reduce(0, +) > MAX_CHANNELS { throw ThemeDamaged() }
        let dma = try rel(r, 0x34)
        let info = try rel(r, 0x38)
        var constAt = [Int](repeating: 0, count: 3)
        for i in 0 ..< 3 { constAt[i] = try rel(r, 0x3C + 4 * i) }
        let specAt = try rel(r, 0x4C)

        // Padding entries name clipJoints.
        var at = 0x60
        var tables: [[Int]] = []
        for t in 0 ..< 8 {
            let n = counts[t]
            var table = [Int](repeating: 0, count: n)
            for i in 0 ..< n { table[i] = try r.be16(at + 2 * i) }
            at += 2 * (t == 0 ? (n + 7) & ~7 : (n + 3) & ~3)
            tables.append(table)
        }
        // Specs: const R, anim R..., const T, anim T..., const S, anim S..., const U.
        var constSpec = [Int](repeating: 0, count: 3)
        var animSpec: [[Int]] = []
        var w = specAt
        for kind in 0 ..< 3 {
            // Swift: a spec stays unsigned and is taken apart with shifts and masks, so the Kotlin's sign does not matter.
            constSpec[kind] = try r.be32(w)
            w += 4
            let n = counts[4 + kind]
            var spec = [Int](repeating: 0, count: n)
            for i in 0 ..< n { spec[i] = try r.be32(w + 4 * i) }
            animSpec.append(spec)
            w += 4 * n
        }

        var channels: [Channel] = []
        for kind in 0 ..< 3 {
            var bits = Bits(r, constAt[kind])
            for j in tables[kind] {
                let v = try readValue(&bits, spec: constSpec[kind], kind: kind)
                if j < joints && j < clipJoints { channels.append(Channel(joint: j, kind: kind, frames: [0], values: v)) }
            }
        }

        let animated = counts[4] + counts[5] + counts[6] + counts[7]
        var keyFrames = [[Float]](repeating: [], count: animated)
        var keyValues = [[Float]](repeating: [], count: animated)
        var kindOf = [Int](repeating: 0, count: animated)
        var specOf = [Int](repeating: 0, count: animated)
        for c in 0 ..< animated {
            kindOf[c] = c < counts[4] ? 0 : c < counts[4] + counts[5] ? 1 : c < counts[4] + counts[5] + counts[6] ? 2 : 3
            specOf[c] = kindOf[c] == 3 ? 0 : animSpec[kindOf[c]][c - kindStart(counts, kindOf[c])]
        }
        for s in 0 ..< sets {
            let offset = try r.be32(dma + 8 * s + 4)
            let first = try r.be16(info + 4 * s)
            let between = try r.be16(info + 4 * s + 2)
            if offset + 16 > r.count || first + between >= frames { throw ThemeDamaged() }
            var keyBytes = [Int](repeating: 0, count: 4)
            for i in 0 ..< 4 { keyBytes[i] = try r.be16(offset + 2 * i) }
            var dataBytes = [Int](repeating: 0, count: 3)
            for i in 0 ..< 3 { dataBytes[i] = try r.be16(offset + 8 + 2 * i) }
            var section = offset + 16
            for kind in 0 ..< 3 {
                var bits = Bits(r, section)
                for c in 0 ..< animated where kindOf[c] == kind {
                    keyFrames[c].append(Float(first))
                    keyValues[c] += try readValue(&bits, spec: specOf[c], kind: kind)
                }
                section += keyBytes[kind]
            }
            if between == 0 { continue }
            let masksAt = offset + 16 + keyBytes.reduce(0, +)
            var maskBits = Bits(r, masksAt)
            var masks: [[Bool]] = []
            masks.reserveCapacity(animated)
            for _ in 0 ..< animated {
                var row = [Bool](repeating: false, count: between)
                for f in 0 ..< between { row[f] = try maskBits.read(1) == 1 }
                masks.append(row)
            }
            var dataAt = masksAt + (animated * between + 7) / 8
            for kind in 0 ..< 3 {
                var bits = Bits(r, dataAt)
                for c in 0 ..< animated where kindOf[c] == kind {
                    for f in 0 ..< between where masks[c][f] {
                        keyFrames[c].append(Float(first + 1 + f))
                        keyValues[c] += try readValue(&bits, spec: specOf[c], kind: kind)
                    }
                }
                if bits.position > (dataAt + dataBytes[kind]) * 8 { throw ThemeDamaged() }
                dataAt += dataBytes[kind]
            }
        }
        for c in 0 ..< animated {
            let kind = kindOf[c]
            if kind == 3 { continue } // user channels drive nothing drawn
            let j = tables[4 + kind][c - kindStart(counts, kind)]
            if j >= joints || j >= clipJoints || keyFrames[c].isEmpty { continue }
            channels.append(Channel(joint: j, kind: kind, frames: keyFrames[c], values: keyValues[c]))
        }
        return Clip(duration: duration, frameRate: rate, frames: frames, channels: channels)
    }

    private static func kindStart(_ counts: [Int], _ kind: Int) -> Int {
        switch kind {
        case 0: return 0
        case 1: return counts[4]
        case 2: return counts[4] + counts[5]
        default: return counts[4] + counts[5] + counts[6]
        }
    }

    /// NaN for a component the spec does not store, so it keeps the rest pose.
    private static func readValue(_ bits: inout Bits, spec: Int, kind: Int) throws -> [Float] {
        var stored = [Float](repeating: 0, count: 3)
        for i in 0 ..< 3 { stored[i] = try readComponent(&bits, field: (spec >> (22 - 10 * i)) & 0x3FF) }
        if kind != ROTATION { return stored }
        let left = spec & 3
        var q = [Float](repeating: 0, count: 4)
        var k = 0
        var sum: Float = 0
        for i in 0 ..< 4 where i != left {
            let v = stored[k].isNaN ? 0 : stored[k]
            q[i] = v
            sum += v * v
            k += 1
        }
        q[left] = max(0, 1 - sum).squareRoot()
        return q
    }

    /// Fixed point with no exponent bits, else a small float with the IEEE bias and no denormals; NaN when nothing is stored.
    static func readComponent(_ bits: inout Bits, field: Int) throws -> Float {
        let s = (field >> 9) & 1
        let e = (field >> 5) & 0xF
        let m = field & 0x1F
        if s + e + m == 0 { return .nan }
        let sign: Int32 = try s == 1 ? bits.read(1) : 0
        let exponent: Int32 = try e > 0 ? bits.read(e) : 0
        let mantissa: Int32 = try m > 0 ? bits.read(m) : 0
        // Swift: Int32 with masked shifts and wrapping minus, so a 31-bit mantissa matches Kotlin instead of trapping.
        let one: Int32 = 1
        if e == 0 {
            if s == 1 {
                var n = (sign &<< m) | mantissa
                if sign == 1 { n = n &- (one &<< (m + 1)) }
                return Float(n) / Float(one &<< m)
            }
            return m == 0 ? 0 : Float(mantissa) / Float((one &<< m) &- 1)
        }
        let bias = (1 << (e - 1)) - 1
        // Swift: `Math.scalb` is Float(sign:exponent:significand:), which also gives infinity or 0 out of range.
        let v = Float(sign: .plus, exponent: Int(exponent) - bias, significand: 1 + Float(mantissa) / Float(one &<< m))
        return sign == 1 ? -v : v
    }

    /// `frame` is fractional and the clip loops; a `weight` below 1 blends toward the clip.
    static func sample(_ clip: Clip, frame: Float, pose: inout [Float], weight: Float = 1) {
        var tmp = [Float](repeating: 0, count: 4)
        for ch in clip.channels {
            let w = ch.width
            let n = ch.frames.count
            let base = 10 * ch.joint + (ch.kind == ROTATION ? 0 : ch.kind == TRANSLATION ? 4 : 7)
            // Swift: a channel that does not fit its pose or its own keys is skipped; Kotlin would have thrown.
            if n == 0 || ch.joint < 0 || base + w > pose.count || ch.values.count < w * n { continue }
            var lo = 0
            var hi = n - 1
            while lo < hi {
                let mid = (lo + hi + 1) >> 1
                if ch.frames[mid] <= frame { lo = mid } else { hi = mid - 1 }
            }
            let a = lo
            let bIdx = a + 1 < n ? a + 1 : a
            let span = ch.frames[bIdx] - ch.frames[a]
            var t: Float = span > 0 ? min(max((frame - ch.frames[a]) / span, 0), 1) : 0
            // Swift: a NaN frame takes key 0 as it is (Kotlin's coerceIn would have passed NaN through).
            if t.isNaN { t = 0 }
            if ch.kind == ROTATION {
                var dot: Float = 0
                for k in 0 ..< 4 { dot += ch.values[4 * a + k] * ch.values[4 * bIdx + k] }
                let flip: Float = dot < 0 ? -1 : 1
                for k in 0 ..< 4 {
                    tmp[k] = ch.values[4 * a + k] + (flip * ch.values[4 * bIdx + k] - ch.values[4 * a + k]) * t
                }
                blendRotation(&pose, base, tmp, weight)
            } else {
                for k in 0 ..< 3 {
                    let va = ch.values[w * a + k]
                    if va.isNaN { continue }
                    let v = va + (ch.values[w * bIdx + k] - va) * t
                    pose[base + k] = pose[base + k] + (v - pose[base + k]) * weight
                }
            }
        }
    }

    private static func blendRotation(_ pose: inout [Float], _ at: Int, _ q: [Float], _ weight: Float) {
        var dot: Float = 0
        for k in 0 ..< 4 { dot += pose[at + k] * q[k] }
        let flip: Float = dot < 0 ? -1 : 1
        var len: Float = 0
        for k in 0 ..< 4 {
            let v = pose[at + k] + (flip * q[k] - pose[at + k]) * weight
            pose[at + k] = v
            len += v * v
        }
        let inv: Float = len > 1e-12 ? 1 / len.squareRoot() : 0
        for k in 0 ..< 4 { pose[at + k] *= inv }
    }

    /// 12 floats a joint, parent x local, where local = translate x rotate x scale.
    static func worlds(_ skeleton: Skeleton, pose: [Float]) -> [Float] {
        let joints = skeleton.joints
        // Swift: a pose too short for the skeleton gives no matrices, where Kotlin would have thrown.
        guard pose.count >= 10 * joints else { return [] }
        var out = [Float](repeating: 0, count: 12 * joints)
        var m = [Float](repeating: 0, count: 12)
        for j in 0 ..< joints {
            localMatrix(pose, 10 * j, &m)
            let p = skeleton.parents[j]
            // Swift: a parent not before its joint (only a hand-built Skeleton can have one) is taken as a root.
            if p < 0 || p >= j {
                for k in 0 ..< 12 { out[12 * j + k] = m[k] }
            } else {
                let world = multiply34(out, 12 * p, m, 0)
                for k in 0 ..< 12 { out[12 * j + k] = world[k] }
            }
        }
        return out
    }

    static func skinMatrices(_ worlds: [Float], _ inverseBinds: [Float]) -> [Float] {
        var out = [Float](repeating: 0, count: worlds.count)
        for j in 0 ..< min(worlds.count, inverseBinds.count) / 12 {
            let skin = multiply34(worlds, 12 * j, inverseBinds, 12 * j)
            for k in 0 ..< 12 { out[12 * j + k] = skin[k] }
        }
        return out
    }

    private static func localMatrix(_ pose: [Float], _ at: Int, _ m: inout [Float]) {
        let x = pose[at], y = pose[at + 1], z = pose[at + 2], w = pose[at + 3]
        let n = x * x + y * y + z * z + w * w
        let s: Float = n > 1e-12 ? 2 / n : 0
        let sx = pose[at + 7], sy = pose[at + 8], sz = pose[at + 9]
        m[0] = (1 - s * (y * y + z * z)) * sx; m[1] = s * (x * y - z * w) * sy; m[2] = s * (x * z + y * w) * sz
        m[3] = pose[at + 4]
        m[4] = s * (x * y + z * w) * sx; m[5] = (1 - s * (x * x + z * z)) * sy; m[6] = s * (y * z - x * w) * sz
        m[7] = pose[at + 5]
        m[8] = s * (x * z - y * w) * sx; m[9] = s * (y * z + x * w) * sy; m[10] = (1 - s * (x * x + y * y)) * sz
        m[11] = pose[at + 6]
    }

    // Swift: returns the product instead of writing through a scratch array, since Swift arrays are values.
    private static func multiply34(_ a: [Float], _ ai: Int, _ bm: [Float], _ bi: Int) -> [Float] {
        var r = [Float](repeating: 0, count: 12)
        for row in 0 ..< 3 {
            let a0 = a[ai + 4 * row], a1 = a[ai + 4 * row + 1], a2 = a[ai + 4 * row + 2], a3 = a[ai + 4 * row + 3]
            for col in 0 ..< 4 {
                var v = a0 * bm[bi + col] + a1 * bm[bi + 4 + col] + a2 * bm[bi + 8 + col]
                if col == 3 { v += a3 }
                r[4 * row + col] = v
            }
        }
        return r
    }

    /// MSB first, throwing at the end of its data, which also bounds the key masks a crafted clip can ask for.
    struct Bits {
        private let reader: ByteReader
        private(set) var position: Int

        init(_ reader: ByteReader, _ byteStart: Int) {
            self.reader = reader
            position = byteStart * 8
        }

        init(_ bytes: [UInt8], _ byteStart: Int) {
            self.init(ByteReader(bytes), byteStart)
        }

        // Swift: Kotlin's Int is Int32 here, shifted with `&<<` so a 31- or 32-bit read wraps as it does there.
        mutating func read(_ n: Int) throws -> Int32 {
            var v: Int32 = 0
            for _ in 0 ..< max(n, 0) {
                let byte = try reader.u8(position >> 3)
                v = (v &<< 1) | Int32((byte >> (7 - (position & 7))) & 1)
                position += 1
            }
            return v
        }
    }

    private static func rel(_ r: ByteReader, _ at: Int) throws -> Int {
        let v = try r.be32(at)
        let target = at + v
        if v == 0 || target > r.count { throw ThemeDamaged() }
        return target
    }

    // Swift: `Float.fromBits` is Float(bitPattern:).
    private static func f32(_ r: ByteReader, _ at: Int) throws -> Float {
        Float(bitPattern: UInt32(truncatingIfNeeded: try r.be32(at)))
    }
}
