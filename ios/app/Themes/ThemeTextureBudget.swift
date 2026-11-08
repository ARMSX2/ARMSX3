// SPDX-License-Identifier: GPL-2.0-only
// Which GPU path each live theme texture takes and at what size, ported from Android's budget helpers. Foundation only.

import Foundation

/// The raw values are the witness words.
enum ThemeTexturePath: String {
    case bc
    case rgba
    /// One texel a block, decoded and filtered in the fragment shader.
    case blocks
}

struct ThemeTexturePlan {
    /// Halved from ThemeTextureBudget.maxSide while over budget.
    let side: Int
    /// Keyed by `P3tAnimation.Texture.offset`, which tells distinct textures apart.
    let paths: [Int: ThemeTexturePath]
    /// `.rgba` when there are no DXT textures.
    let dxtPath: ThemeTexturePath

    /// Without BC, RGBA at the largest side that fits, else DXT kept as blocks; `.bc` without BC support is no force.
    static func make(textures: [P3tAnimation.Texture], supportsBC: Bool, maxTextureSide: Int,
                     forced: ThemeTexturePath?) -> ThemeTexturePlan {
        var seen = Set<Int>()
        let unique = textures.filter { seen.insert($0.offset).inserted }
        // Swift: no GPU has a side near a million; the clamp keeps `limit * 4` from overflowing.
        let limit = min(maxTextureSide, 1 << 20)
        let isDxt = { (t: P3tAnimation.Texture) in P3tAnimation.dxt(t) != 0 }
        // A block texture is a quarter the side; one wider than the GPU's limit times four cannot be made.
        let blockable = { (t: P3tAnimation.Texture) in isDxt(t) && t.width <= limit * 4 && t.height <= limit * 4 }
        var side = min(ThemeTextureBudget.maxSide, max(limit, ThemeTextureBudget.minSide))
        let bytes = { (ts: [P3tAnimation.Texture]) in
            ts.reduce(0) { $0 + ThemeTextureBudget.bytesAt(width: $1.width, height: $1.height, side: side) }
        }

        var paths: [Int: ThemeTexturePath] = [:]
        // `.bc` is what no force already gives where BC exists, and means nothing where it does not.
        let force = forced == .bc ? nil : forced
        let bc = supportsBC && force == nil
        for t in unique {
            if bc && isDxt(t) {
                paths[t.offset] = .bc
            } else if force == .blocks && blockable(t) {
                paths[t.offset] = .blocks
            } else {
                paths[t.offset] = .rgba
            }
        }
        // Too much as RGBA: the DXT textures stay as they are.
        if !bc && force == nil && bytes(unique) > ThemeTextureBudget.budget {
            for t in unique where blockable(t) { paths[t.offset] = .blocks }
        }
        let rgba = unique.filter { paths[$0.offset] == .rgba }
        while side > ThemeTextureBudget.minSide && bytes(rgba) > ThemeTextureBudget.budget { side /= 2 }

        var counts: [ThemeTexturePath: Int] = [:]
        for t in unique where isDxt(t) { counts[paths[t.offset] ?? .rgba, default: 0] += 1 }
        let dxtPath = [ThemeTexturePath.bc, .blocks, .rgba].max { counts[$0, default: 0] < counts[$1, default: 0] }
        return ThemeTexturePlan(side: side, paths: paths, dxtPath: counts.isEmpty ? .rgba : dxtPath ?? .rgba)
    }
}

enum ThemeTextureBudget {
    static let maxSide = 2048
    static let minSide = 256
    static let budget = 96 << 20

    static func bytesAt(width: Int, height: Int, side: Int) -> Int {
        // Swift: the side is clamped so the product below cannot overflow, and a side under 1 cannot loop forever.
        let side = min(max(side, 1), 1 << 16)
        var w = max(width, 1)
        var h = max(height, 1)
        while w > side || h > side {
            w = max(w / 2, 1)
            h = max(h / 2, 1)
        }
        return w * h * 4 * 4 / 3
    }

    static func premultiply(_ argb: inout [UInt32]) {
        for i in argb.indices {
            let p = argb[i]
            let a = p >> 24
            if a == 255 { continue }
            let r = ((p >> 16) & 0xFF) * a / 255
            let g = ((p >> 8) & 0xFF) * a / 255
            let b = (p & 0xFF) * a / 255
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b
        }
    }

    /// Each pixel the rounded average of the 2x2 it covers.
    static func halve(_ argb: [UInt32], width w: Int, height h: Int) -> [UInt32] {
        // Swift: too few pixels for the size given is no picture, where the Kotlin would have thrown.
        guard w >= 1, h >= 1, w <= 1 << 16, h <= 1 << 16, argb.count >= w * h else { return [] }
        let w2 = max(w / 2, 1)
        let h2 = max(h / 2, 1)
        var out = [UInt32](repeating: 0, count: w2 * h2)
        for y in 0 ..< h2 {
            for x in 0 ..< w2 {
                let x0 = min(2 * x, w - 1), x1 = min(2 * x + 1, w - 1)
                let y0 = min(2 * y, h - 1), y1 = min(2 * y + 1, h - 1)
                let a = argb[y0 * w + x0], b = argb[y0 * w + x1]
                let c = argb[y1 * w + x0], d = argb[y1 * w + x1]
                var pixel: UInt32 = 0
                for shift in stride(from: UInt32(0), to: 32, by: 8) {
                    let sum = ((a >> shift) & 0xFF) + ((b >> shift) & 0xFF) + ((c >> shift) & 0xFF) + ((d >> shift) & 0xFF)
                    pixel |= ((sum + 2) / 4) << shift
                }
                out[y * w2 + x] = pixel
            }
        }
        return out
    }
}
