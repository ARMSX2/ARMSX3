// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's Rco.kt: named images, sounds and text out of a firmware .rco. Every offset is checked.

import Foundation

/// A PS3 resource container (.rco), big-endian. "FRP\0", a version, a word and a compression word; with its
/// 0x10 bit set the tables after the 0xA4-byte header are one zlib stream (packed size, unpacked size and longest
/// text at 0xA4, the stream at 0xB0), and the header's table offsets point into the header plus that block.
/// Header words: 0x18 text table, 0x1C sounds, 0x24 images, 0x40 labels; the data areas 0x38 (text, length at
/// 0x3C), 0x80 (images) and 0x88 (sounds) are offsets in the file. A table entry is a 0x28-byte header (label at
/// +4, child count at +0x10, distance to the next sibling at +0x14) and then its own fields.
struct Rco {
    private struct ImageEntry { let compression: Int, packed: Int, offset: Int, size: Int }
    private struct SoundEntry { let format: Int, channels: [(offset: Int, size: Int)] }

    private let file: ByteReader
    private let table: ByteReader
    private let compressed: Bool
    private var images: [String: ImageEntry] = [:]
    private var sounds: [String: SoundEntry] = [:]

    var imageNames: Set<String> { Set(images.keys) }
    var soundNames: Set<String> { Set(sounds.keys) }

    static let maxFile = 32 << 20
    private static let none = 0xFFFF_FFFF
    private static let header = 0x28
    private static let maxTable = 16 << 20
    private static let maxImage = 8 << 20
    private static let maxSound = 4 << 20
    private static let maxText = 4 << 20
    private static let maxStrings = 8192
    private static let maxEntries = 4096
    private static let maxName = 256

    /// The container at `url`, or nil when it is missing or not one this can read.
    static func open(_ url: URL) -> Rco? {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size >= 0xB0, size <= maxFile,
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return Rco(bytes: [UInt8](data))
    }

    init?(bytes: [UInt8]) {
        let file = ByteReader(bytes)
        guard bytes.count >= 0xB0, (try? file.be32(0)) == 0x4652_5000 else { return nil }
        do {
            let compressed = try file.be32(0x0C) & 0xF0 == 0x10
            var table = file
            if compressed {
                let packed = try file.be32(0xA4), unpacked = try file.be32(0xA8)
                guard packed > 0, packed <= bytes.count - 0xB0, unpacked > 0, unpacked <= Self.maxTable else { return nil }
                let inflated = try ThemeZlib.inflate(Array(bytes[0xB0 ..< 0xB0 + packed]), cap: unpacked)
                guard inflated.count == unpacked else { return nil }
                table = ByteReader(Array(bytes[0 ..< 0xA4]) + inflated)
            }
            self.file = file
            self.table = table
            self.compressed = compressed
            try parse()
        } catch {
            return nil
        }
    }

    private func label(_ base: Int, _ offset: Int) throws -> String {
        try table.cString(at: base + offset, limit: Self.maxName)
    }

    private mutating func parse() throws {
        let labels = try file.be32(0x40)
        let imageTable = try file.be32(0x24)
        if imageTable != Self.none {
            var found: [String: ImageEntry] = [:]
            try walk(imageTable) { entry, name in
                let compression = try table.be16(entry + 0x2A)
                let packed = try table.be32(entry + 0x2C)
                let offset = try table.be32(entry + 0x30)
                let size = compression != 0 ? try table.be32(entry + 0x38) : packed
                if (1 ... Self.maxImage).contains(packed), (1 ... Self.maxImage).contains(size) {
                    found[try label(labels, name)] = ImageEntry(compression: compression, packed: packed, offset: offset, size: size)
                }
            }
            images = found
        }
        let soundTable = try file.be32(0x1C)
        if soundTable != Self.none {
            var found: [String: SoundEntry] = [:]
            try walk(soundTable) { entry, name in
                let format = try table.be16(entry + 0x28)
                let count = (try? table.u8(entry + 0x2A)) ?? 0
                guard (1 ... 2).contains(count) else { return }
                var channels: [(offset: Int, size: Int)] = []
                for c in 0 ..< count {
                    channels.append((try table.be32(entry + 0x38 + 8 * c), try table.be32(entry + 0x34 + 8 * c)))
                }
                if channels.allSatisfy({ (1 ... Self.maxSound).contains($0.size) }) {
                    found[try label(labels, name)] = SoundEntry(format: format, channels: channels)
                }
            }
            sounds = found
        }
    }

    /// Calls `each` with every child of the table at `at`: the entry's offset and its label offset.
    private func walk(_ at: Int, _ each: (_ entry: Int, _ label: Int) throws -> Void) throws {
        let count = try table.be32(at + 0x10)
        guard count <= Self.maxEntries else { throw ThemeDamaged() }
        var entry = at + Self.header
        for _ in 0 ..< count {
            guard entry + Self.header <= table.count else { throw ThemeDamaged() }
            try each(entry, try table.be32(entry + 4))
            let next = try table.be32(entry + 0x14)
            if next <= 0 || next > table.count { return }
            entry += next
        }
    }

    /// The image called `name`, unpacked (a GIM for every image the firmware's XMB uses).
    func image(_ name: String) -> [UInt8]? {
        guard let e = images[name], let data = try? file.be32(0x80), data != Self.none,
              let bytes = try? file.slice(data + e.offset, e.packed) else { return nil }
        guard e.compression == 1 else { return bytes }
        guard let out = try? ThemeZlib.inflate(bytes, cap: e.size), out.count == e.size else { return nil }
        return out
    }

    /// The sound called `name` as one VAG file per channel, left first.
    func sound(_ name: String) -> [[UInt8]]? {
        guard let e = sounds[name], e.format == 1, let data = try? file.be32(0x88), data != Self.none else { return nil }
        var out: [[UInt8]] = []
        for channel in e.channels {
            guard let bytes = try? file.slice(data + channel.offset, channel.size) else { return nil }
            out.append(bytes)
        }
        return out
    }

    /// Every string in `language` (the console's numbering: 0 Japanese, 1 English, 2 French ... 19 Turkish) by name.
    func strings(_ language: Int) -> [String: String] {
        guard compressed, let textTable = try? file.be32(0x18), textTable != Self.none else { return [:] }
        return (try? readStrings(textTable, language)) ?? [:]
    }

    private func readStrings(_ textTable: Int, _ language: Int) throws -> [String: String] {
        let dataStart = try file.be32(0x38)
        let dataEnd = dataStart + (try file.be32(0x3C))
        guard dataStart != Self.none, dataEnd <= file.count else { return [:] }
        // Each language's text is its own zlib block: u16 language, u16, u32 distance to the next, u32 packed, u32 unpacked.
        var at = dataStart
        var text: ByteReader?
        while at + 16 <= dataEnd {
            let next = try file.be32(at + 4)
            if try file.be16(at) == language {
                let packed = try file.be32(at + 8), unpacked = try file.be32(at + 12)
                guard packed > 0, at + 16 + packed <= dataEnd, unpacked > 0, unpacked <= Self.maxText else { return [:] }
                let out = try ThemeZlib.inflate(try file.slice(at + 16, packed), cap: unpacked)
                guard out.count == unpacked else { return [:] }
                text = ByteReader(out)
                break
            }
            if next <= 0 { break }
            at += next
        }
        guard let blob = text else { return [:] }
        let labels = try file.be32(0x40)
        var out: [String: String] = [:]
        try walk(textTable) { entry, _ in
            guard try table.be16(entry + 0x28) == language else { return }
            let encoding: String.Encoding
            switch try table.be16(entry + 0x2A) {
            case 0: encoding = .utf8
            case 2: encoding = .utf32BigEndian
            default: encoding = .utf16BigEndian
            }
            let count = try table.be32(entry + 0x2C)
            guard count <= Self.maxStrings else { throw ThemeDamaged() }
            for k in 0 ..< count {
                let index = entry + 0x30 + 12 * k
                let name = try table.be32(index), length = try table.be32(index + 4), offset = try table.be32(index + 8)
                guard name != Self.none, let bytes = try? blob.slice(offset, length),
                      let string = String(bytes: bytes, encoding: encoding) else { continue }
                out[try label(labels, name)] = string.trimmingCharacters(in: CharacterSet(charactersIn: "\u{0}"))
            }
        }
        return out
    }
}
