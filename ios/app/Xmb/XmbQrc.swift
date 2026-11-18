// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's XmbQrc: the firmware's qgl resource containers, which hold the wave's and sparkles' data.

import Foundation

/// "QRCC", the unpacked size, then one zlib stream holding a "QRCF": a tree at +8, the names at +16 (size at +20)
/// and the data at +48 (size at +52), all big-endian. Each name is a tree offset and a C string; its tree entry has
/// the file's offset at +36 and length at +40.
enum XmbQrc {
    private static let maxFile = 32 << 20
    private static let maxUnpacked = 64 << 20

    /// The files in the container at `url` by name, or nil when it is missing or not one this can read.
    static func open(_ url: URL) -> [String: [UInt8]]? {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size >= 8, size <= maxFile,
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return files([UInt8](data))
    }

    static func files(_ raw: [UInt8]) -> [String: [UInt8]]? {
        let packed = ByteReader(raw)
        guard raw.count >= 8, raw[0 ..< 4].elementsEqual("QRCC".utf8), let size = try? packed.be32(4), size >= 56,
              size <= maxUnpacked, let unpacked = try? ThemeZlib.inflate(Array(raw[8...]), cap: size) else { return nil }
        let u = ByteReader(unpacked)
        guard unpacked.count >= 56, unpacked[0 ..< 4].elementsEqual("QRCF".utf8),
              let tree = try? u.be32(8), let names = try? u.be32(16), let namesSize = try? u.be32(20),
              let dataAt = try? u.be32(48), let dataSize = try? u.be32(52) else { return nil }
        var out: [String: [UInt8]] = [:]
        var pos = names
        let end = min(names + namesSize, unpacked.count)
        while pos + 4 < end {
            guard let entryOffset = try? u.be32(pos), let name = try? u.cString(at: pos + 4, limit: end - pos - 4) else { break }
            pos += 4 + name.unicodeScalars.count + 1
            let entry = tree + entryOffset
            guard !name.isEmpty, let offset = try? u.be32(entry + 36), let length = try? u.be32(entry + 40), length > 0,
                  offset + length <= dataSize, let bytes = try? u.slice(dataAt + offset, length) else { continue }
            out[name] = bytes
        }
        return out
    }
}
