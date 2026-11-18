// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's Vag.kt: the PS3's ADPCM sound clips, which the firmware's XMB sounds are, to a WAV file.

import Foundation

/// "VAGp", a version, a word, then the data size at 0x0C and the sample rate at 0x10, big-endian, in a 48-byte
/// header. The data is 16-byte frames of 28 samples: a byte of predictor (high nibble) and shift (low nibble), a
/// flags byte (7 ends the clip), then 14 bytes of 4-bit samples, low nibble first.
enum Vag {
    struct Pcm {
        let samples: [Int16]
        let rate: Int
    }

    private static let coef0 = [0, 60, 115, 98, 122]
    private static let coef1 = [0, 0, -52, -55, -60]
    private static let maxBytes = 8 << 20

    static func decode(_ vag: [UInt8]) -> Pcm? {
        guard vag.count >= 48, vag[0 ..< 4].elementsEqual("VAGp".utf8) else { return nil }
        func be32(_ at: Int) -> Int { Int(vag[at]) << 24 | Int(vag[at + 1]) << 16 | Int(vag[at + 2]) << 8 | Int(vag[at + 3]) }
        let size = min(max(be32(12), 0), min(vag.count - 48, maxBytes))
        let rate = be32(16)
        guard (8000 ... 96000).contains(rate) else { return nil }
        var out: [Int16] = []
        out.reserveCapacity(size / 16 * 28)
        var h1 = 0, h2 = 0
        var frame = 48
        while frame + 16 <= 48 + size {
            let pred = Int(vag[frame]) >> 4 & 0x0F
            let shift = Int(vag[frame]) & 0x0F
            if vag[frame + 1] == 7 { break }
            let c0 = pred < 5 ? coef0[pred] : 0
            let c1 = pred < 5 ? coef1[pred] : 0
            for i in 0 ..< 28 {
                let nibble = Int(vag[frame + 2 + i / 2]) >> (4 * (i & 1)) & 0x0F
                var s = (nibble >= 8 ? nibble - 16 : nibble) << 12
                s >>= shift
                s += (h1 * c0 + h2 * c1 + 32) >> 6
                s = min(max(s, -32768), 32767)
                out.append(Int16(s))
                h2 = h1
                h1 = s
            }
            frame += 16
        }
        return Pcm(samples: out, rate: rate)
    }

    /// One or two channels, left first, as a 16-bit WAV file's bytes.
    static func wav(_ channels: [Pcm]) -> Data? {
        guard let rate = channels.first?.rate, let frames = channels.map(\.samples.count).min() else { return nil }
        let count = channels.count
        var data = Data()
        func put32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { data.append(contentsOf: $0) } }
        func put16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { data.append(contentsOf: $0) } }
        let bytes = frames * count * 2
        data.append(contentsOf: "RIFF".utf8); put32(36 + bytes); data.append(contentsOf: "WAVE".utf8)
        data.append(contentsOf: "fmt ".utf8); put32(16); put16(1); put16(count)
        put32(rate); put32(rate * count * 2); put16(count * 2); put16(16)
        data.append(contentsOf: "data".utf8); put32(bytes)
        data.reserveCapacity(44 + bytes)
        for i in 0 ..< frames {
            for c in 0 ..< count { put16(Int(UInt16(bitPattern: channels[c].samples[i]))) }
        }
        return data
    }
}
