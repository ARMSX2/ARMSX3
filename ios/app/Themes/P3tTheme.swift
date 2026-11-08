// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's P3tTheme.kt: takes a picture out of a .p3t. Every offset is hostile and checked in 64-bit Int.

import Foundation

enum P3tTheme {
    enum Source { case hd, sd, preview }

    struct Picture {
        let source: Source
        let jpeg: [UInt8]?
        let rgba: [UInt8]?
        let width: Int
        let height: Int
    }

    enum Failure { case notATheme, damaged, noPicture, dynamicOnly }

    struct Anim {
        let offset: Int
        let size: Int
    }

    /// `picture` is nil exactly when `failure` is set; `anim` is set only when its reference is sound.
    struct Result {
        let picture: Picture?
        let dynamic: Bool
        let failure: Failure?
        var anim: Anim? = nil
        var name: String? = nil
    }

    private static let MAGIC = 0x50335446 // "P3TF"
    private static let HEADER_BYTES = 56
    private static let ELEMENT_BYTES = 28
    private static let ATTRIBUTE_BYTES = 16
    private static let TYPE_STRING = 3
    private static let TYPE_FILE = 6

    // So a crafted size cannot make us allocate without limit.
    private static let MAX_TABLE_BYTES = 16 << 20
    private static let MAX_PICTURE_BYTES = 32 << 20
    private static let MAX_PACKED_PREVIEW_BYTES = 16 << 20
    private static let MAX_PREVIEW_BYTES = 64 << 20
    private static let MAX_PREVIEW_SIDE = 4096
    private static let MAX_NAME_BYTES = 256
    private static let PROBE_BYTES = 256 << 10

    static func isTheme(_ head: [UInt8]) -> Bool {
        (try? ByteReader(head).be32(0)) == MAGIC
    }

    static func read(_ src: ThemeByteSource) -> Result {
        do {
            return try parse(src)
        } catch {
            // Swift: every read throws ThemeDamaged from its range check, so a bad theme reports damaged and never traps.
            return Result(picture: nil, dynamic: false, failure: .damaged)
        }
    }

    private static func parse(_ src: ThemeByteSource) throws -> Result {
        if src.size < HEADER_BYTES {
            let magic = try src.size >= 4 && isTheme(src.read(0, 4))
            return Result(picture: nil, dynamic: false, failure: magic ? .damaged : .notATheme)
        }
        let header = ByteReader(try src.read(0, HEADER_BYTES))
        if try header.be32(0) != MAGIC { return Result(picture: nil, dynamic: false, failure: .notATheme) }

        var tables: [(offset: Int, size: Int)] = []
        for i in 0 ..< 6 {
            let offset = try header.be32(8 + i * 8)
            let size = try header.be32(12 + i * 8)
            if offset > src.size || size > src.size - offset { throw ThemeDamaged() }
            tables.append((offset, size))
        }
        let elementTable = tables[0]
        let stringTable = tables[2]
        let fileTable = tables[5]
        if elementTable.size > MAX_TABLE_BYTES || stringTable.size > MAX_TABLE_BYTES { throw ThemeDamaged() }
        let elements = ByteReader(try src.read(elementTable.offset, elementTable.size))
        let strings = ByteReader(try src.read(stringTable.offset, stringTable.size))

        // Nil when the reference points outside the file table.
        func file(_ offset: Int, _ size: Int, _ cap: Int) -> (offset: Int, size: Int)? {
            if offset > fileTable.size || size > fileTable.size - offset || size > cap || size < 4 { return nil }
            return (fileTable.offset + offset, size)
        }

        // Only the chosen candidate is read whole, so a theme with many backgrounds costs one picture's memory.
        struct Candidate {
            let source: Source
            let offset: Int
            let size: Int
            let width: Int
            let height: Int
        }
        var hd: [Candidate] = []
        var sd: [Candidate] = []
        var preview: (offset: Int, size: Int)?
        var dynamic = false
        var anim: Anim?
        var title: String?
        // The scene is streamed out by the importer, never read whole here, so only its bounds matter.
        func scene(_ offset: Int, _ size: Int) -> Anim? {
            if offset > fileTable.size || size > fileTable.size - offset || size < 8 { return nil }
            return Anim(offset: fileTable.offset + offset, size: size)
        }

        var pos = 0
        while pos + ELEMENT_BYTES <= elements.count {
            let name = try cString(strings, try elements.be32(pos))
            let count = try elements.be32(pos + 4)
            if count > (elements.count - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES { throw ThemeDamaged() }
            let wanted = name == "bgimage" || name == "info"
            if wanted {
                for i in 0 ..< count {
                    let at = pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES
                    // UTF-8 at (offset, length) in the string table.
                    if name == "info", try elements.be32(at + 4) == TYPE_STRING,
                       try cString(strings, try elements.be32(at)) == "name" {
                        let offset = try elements.be32(at + 8)
                        let length = try elements.be32(at + 12)
                        if offset <= strings.count && length <= min(strings.count - offset, MAX_NAME_BYTES) {
                            title = trimmedTitle(String(decoding: try strings.slice(offset, length), as: UTF8.self))
                        }
                        continue
                    }
                    if try elements.be32(at + 4) != TYPE_FILE { continue }
                    let attribute = try cString(strings, try elements.be32(at))
                    let offset = try elements.be32(at + 8)
                    let size = try elements.be32(at + 12)
                    if name == "info" && attribute == "preview" {
                        preview = file(offset, size, MAX_PACKED_PREVIEW_BYTES)
                    } else if name == "bgimage" && attribute == "anim" {
                        dynamic = true
                        if anim == nil { anim = scene(offset, size) }
                    } else if name == "bgimage" && (attribute == "hd" || attribute == "sd") {
                        guard let ref = file(offset, size, MAX_PICTURE_BYTES) else { continue }
                        // The frame header sits near the start; the whole file only if it does not.
                        let probe = try src.read(ref.offset, min(ref.size, PROBE_BYTES))
                        if isRaf(probe) {
                            dynamic = true
                            if anim == nil { anim = scene(offset, size) }
                            continue
                        }
                        var dims = jpegSize(probe)
                        if dims == nil && ref.size > PROBE_BYTES { dims = jpegSize(try src.read(ref.offset, ref.size)) }
                        guard let found = dims else { continue }
                        let source: Source = attribute == "hd" ? .hd : .sd
                        let candidate = Candidate(source: source, offset: ref.offset, size: ref.size,
                                                  width: found.width, height: found.height)
                        if source == .hd { hd.append(candidate) } else { sd.append(candidate) }
                    }
                }
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        // HD over SD, then the preview; max(by:) keeps the first of equal maxima, as Kotlin's maxByOrNull does.
        let area = { (a: Candidate, b: Candidate) in a.width * a.height < b.width * b.height }
        if let best = hd.max(by: area) ?? sd.max(by: area) {
            let jpeg = try src.read(best.offset, best.size)
            return Result(picture: Picture(source: best.source, jpeg: jpeg, rgba: nil, width: best.width, height: best.height),
                          dynamic: dynamic, failure: nil, anim: anim, name: title)
        }
        if let ref = preview, let fallback = decodePreview(try src.read(ref.offset, ref.size)) {
            return Result(picture: fallback, dynamic: dynamic, failure: nil, anim: anim, name: title)
        }
        return Result(picture: nil, dynamic: dynamic, failure: dynamic ? .dynamicOnly : .noPicture, anim: anim, name: title)
    }

    // Kotlin: trimEnd('\u0000').trim().ifEmpty { null }.
    private static func trimmedTitle(_ text: String) -> String? {
        var s = Substring(text)
        while s.last == "\u{0}" { s = s.dropLast() }
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func isRaf(_ b: [UInt8]) -> Bool {
        guard let head = try? ByteReader(b).latin1(at: 0, count: 4) else { return false }
        return head == "_RAF" || head == "RAFO"
    }

    static func jpegSize(_ bytes: [UInt8]) -> (width: Int, height: Int)? {
        (try? jpegFrame(ByteReader(bytes))) ?? nil
    }

    // Swift: Kotlin's be16 here was unchecked and relied on the loop bound; ByteReader checks every read.
    private static func jpegFrame(_ b: ByteReader) throws -> (width: Int, height: Int)? {
        if try b.count < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8 { return nil }
        var i = 2
        while i + 4 <= b.count {
            if try b.u8(i) != 0xFF { return nil }
            let marker = try b.u8(i + 1)
            if marker == 0xFF { i += 1; continue }                                   // fill byte
            if marker == 0x01 || (0xD0 ... 0xD8).contains(marker) { i += 2; continue } // no length
            if marker == 0xD9 || marker == 0xDA { return nil }                       // end, or scan before a frame
            let length = try b.be16(i + 2)
            if length < 2 { return nil }
            let isFrame = (0xC0 ... 0xCF).contains(marker) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if isFrame {
                if i + 9 > b.count { return nil }
                let height = try b.be16(i + 5)
                let width = try b.be16(i + 7)
                return width > 0 && height > 0 ? (width, height) : nil
            }
            i += 2 + length
        }
        return nil
    }

    /// Only plain 32-bit RGBA in PS3 or PSP byte order; any other GIM is no fallback rather than a wrong picture.
    static func decodePreview(_ packed: [UInt8]) -> Picture? {
        guard let inflated = try? ThemeZlib.inflate(packed, cap: MAX_PREVIEW_BYTES) else { return nil }
        let gim = ByteReader(inflated)
        if gim.count < 16 { return nil }
        let big: Bool
        switch try? gim.latin1(at: 0, count: 8) {
        case ".GIM1.00": big = true
        case "MIG.00.1": big = false
        default: return nil
        }
        // Swift: every read is checked, and out of range reads as -1 as Kotlin's lambdas return.
        let u16 = { (at: Int) -> Int in (try? (big ? gim.be16(at) : gim.le16(at))) ?? -1 }
        let u32 = { (at: Int) -> Int in (try? (big ? gim.be32(at) : gim.le32(at))) ?? -1 }

        // Blocks: id u16, unused u16, size u32, next u32, data u32; the root (2) holds pictures (3), which hold the image (4).
        func findImage(_ start: Int, _ end: Int, _ depth: Int) -> Int? {
            var off = start
            while depth < 4 && off + 16 <= end {
                let id = u16(off)
                let size = u32(off + 4)
                let next = u32(off + 8)
                let data = u32(off + 12)
                if id < 0 || size < 16 || size > end - off || data < 16 || data > size { return nil }
                if id == 4 { return off + data }
                if id == 2 || id == 3, let found = findImage(off + data, off + size, depth + 1) { return found }
                if next <= 0 { return nil }
                off += next
            }
            return nil
        }
        guard let image = findImage(16, gim.count, 0) else { return nil }

        let format = u16(image + 4)
        let order = u16(image + 6)
        let width = u16(image + 8)
        let height = u16(image + 10)
        let bpp = u16(image + 12)
        let pitchAlign = max(u16(image + 14), 1)
        let pixels = u32(image + 0x1C)
        if format != 3 || order != 0 || bpp != 32 { return nil }
        if !(1 ... MAX_PREVIEW_SIDE).contains(width) || !(1 ... MAX_PREVIEW_SIDE).contains(height) || pixels < 0 { return nil }
        let row = width * 4
        let pitch = (row + pitchAlign - 1) / pitchAlign * pitchAlign
        let first = image + pixels
        if first < 0 || first + pitch * (height - 1) + row > gim.count { return nil }

        var rgba: [UInt8] = []
        rgba.reserveCapacity(row * height)
        for y in 0 ..< height {
            guard let line = try? gim.slice(first + pitch * y, row) else { return nil }
            rgba += line
        }
        return Picture(source: .preview, jpeg: nil, rgba: rgba, width: width, height: height)
    }

    private static func cString(_ table: ByteReader, _ offset: Int) throws -> String {
        try table.cString(at: offset, limit: MAX_NAME_BYTES)
    }
}
