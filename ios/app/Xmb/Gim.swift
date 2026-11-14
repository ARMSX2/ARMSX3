// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's Gim.kt: the GIM images the firmware keeps its XMB icons in, to straight RGBA8.

import Foundation

/// ".GIM1.00" (PS3, big-endian) or "MIG.00.1" (PSP, little-endian), then blocks of an id, a size, the offset of the
/// next block and of the block's data. The root (2) holds pictures (3), which hold the image (4); its header has the
/// format at +4, the pixel order at +6, the size at +8 and +10, the pitch alignment at +14 and the pixels at +0x1C.
/// Plain (not swizzled) order only, the only order the firmware's icons use: format 3 is RGBA8 and 8, 9 and 10 are
/// DXT1, DXT3 and DXT5, whose blocks are little-endian even inside a big-endian GIM.
struct GimImage {
    let width: Int
    let height: Int
    /// Straight (not premultiplied) RGBA, row after row.
    let rgba: [UInt8]

    private static let maxSide = 4096

    init?(_ gim: [UInt8]) {
        guard gim.count >= 16 else { return nil }
        let magic = String(decoding: gim[0 ..< 8], as: UTF8.self)
        let big: Bool
        switch magic {
        case ".GIM1.00": big = true
        case "MIG.00.1": big = false
        default: return nil
        }
        func u16(_ at: Int) -> Int {
            guard at >= 0, at + 2 <= gim.count else { return -1 }
            let a = Int(gim[at]), b = Int(gim[at + 1])
            return big ? a << 8 | b : b << 8 | a
        }
        func u32(_ at: Int) -> Int {
            guard at >= 0, at + 4 <= gim.count else { return -1 }
            let b = (0 ..< 4).map { Int(gim[at + $0]) }
            return big ? b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3] : b[3] << 24 | b[2] << 16 | b[1] << 8 | b[0]
        }
        func findImage(_ start: Int, _ end: Int, _ depth: Int) -> Int? {
            var off = start
            while depth < 4 && off + 16 <= end {
                let id = u16(off), size = u32(off + 4), next = u32(off + 8), data = u32(off + 12)
                if id < 0 || size < 16 || size > end - off || data < 16 || data > size { return nil }
                if id == 4 { return off + data }
                if id == 2 || id == 3, let found = findImage(off + data, off + size, depth + 1) { return found }
                if next <= 0 { return nil }
                off += next
            }
            return nil
        }
        guard let image = findImage(16, gim.count, 0) else { return nil }
        let format = u16(image + 4), order = u16(image + 6)
        let width = u16(image + 8), height = u16(image + 10)
        let pitchAlign = max(u16(image + 14), 1)
        let pixels = u32(image + 0x1C)
        guard order == 0, (1 ... Self.maxSide).contains(width), (1 ... Self.maxSide).contains(height), pixels >= 0 else {
            return nil
        }
        let first = image + pixels
        var out = [UInt8](repeating: 0, count: width * height * 4)
        switch format {
        case 3:
            let row = width * 4
            let pitch = (row + pitchAlign - 1) / pitchAlign * pitchAlign
            guard first + pitch * (height - 1) + row <= gim.count else { return nil }
            for y in 0 ..< height {
                let from = first + pitch * y
                out.replaceSubrange(y * row ..< (y + 1) * row, with: gim[from ..< from + row])
            }
        case 8, 9, 10:
            let blockBytes = format == 8 ? 8 : 16
            guard first + (width + 3) / 4 * ((height + 3) / 4) * blockBytes <= gim.count else { return nil }
            Self.decodeDxt(gim, at: first, width: width, height: height, format: format, into: &out)
        default:
            return nil
        }
        self.width = width
        self.height = height
        self.rgba = out
    }

    /// DXT1 (8), DXT3 (9) or DXT5 (10) blocks at `at` into `out`, row-major 4x4 blocks. The caller checked the size.
    static func decodeDxt(_ src: [UInt8], at: Int, width: Int, height: Int, format: Int, into out: inout [UInt8]) {
        let blockBytes = format == 8 ? 8 : 16
        func le16(_ i: Int) -> Int { Int(src[i]) | Int(src[i + 1]) << 8 }
        func le32(_ i: Int) -> Int { le16(i) | le16(i + 2) << 16 }
        func rgb565(_ c: Int) -> (Int, Int, Int) {
            let r = c >> 11 & 31, g = c >> 5 & 63, b = c & 31
            return (r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2)
        }
        func mix(_ a: (Int, Int, Int), _ b: (Int, Int, Int), _ wa: Int, _ wb: Int, _ d: Int) -> (Int, Int, Int) {
            ((a.0 * wa + b.0 * wb) / d, (a.1 * wa + b.1 * wb) / d, (a.2 * wa + b.2 * wb) / d)
        }
        var colors = [(Int, Int, Int)](repeating: (0, 0, 0), count: 4)
        var alphas = [Int](repeating: 0, count: 8)
        var block = at
        for by in 0 ..< (height + 3) / 4 {
            for bx in 0 ..< (width + 3) / 4 {
                let colorAt = block + blockBytes - 8
                let c0 = le16(colorAt), c1 = le16(colorAt + 2)
                colors[0] = rgb565(c0)
                colors[1] = rgb565(c1)
                // DXT1 with c0 <= c1 has a third colour halfway and a transparent black fourth.
                let oneBitAlpha = format == 8 && c0 <= c1
                if oneBitAlpha {
                    colors[2] = mix(colors[0], colors[1], 1, 1, 2)
                    colors[3] = (0, 0, 0)
                } else {
                    colors[2] = mix(colors[0], colors[1], 2, 1, 3)
                    colors[3] = mix(colors[0], colors[1], 1, 2, 3)
                }
                let indices = le32(colorAt + 4)
                var alphaBits: UInt64 = 0
                if format == 10 {
                    let a0 = Int(src[block]), a1 = Int(src[block + 1])
                    alphas[0] = a0
                    alphas[1] = a1
                    if a0 > a1 {
                        for i in 1 ... 6 { alphas[i + 1] = ((7 - i) * a0 + i * a1) / 7 }
                    } else {
                        for i in 1 ... 4 { alphas[i + 1] = ((5 - i) * a0 + i * a1) / 5 }
                        alphas[6] = 0
                        alphas[7] = 255
                    }
                    for i in 0 ..< 6 { alphaBits |= UInt64(src[block + 2 + i]) << (8 * i) }
                } else if format == 9 {
                    for i in 0 ..< 8 { alphaBits |= UInt64(src[block + i]) << (8 * i) }
                }
                for i in 0 ..< 16 {
                    let x = bx * 4 + (i & 3), y = by * 4 + (i >> 2)
                    if x >= width || y >= height { continue }
                    let index = indices >> (2 * i) & 3
                    let rgb = colors[index]
                    let alpha: Int
                    switch format {
                    case 10: alpha = alphas[Int(alphaBits >> UInt64(3 * i) & 7)]
                    case 9: alpha = Int(alphaBits >> UInt64(4 * i) & 15) * 17
                    default: alpha = oneBitAlpha && index == 3 ? 0 : 255
                    }
                    let o = (y * width + x) * 4
                    out[o] = UInt8(rgb.0)
                    out[o + 1] = UInt8(rgb.1)
                    out[o + 2] = UInt8(rgb.2)
                    out[o + 3] = UInt8(alpha)
                }
                block += blockBytes
            }
        }
    }
}
