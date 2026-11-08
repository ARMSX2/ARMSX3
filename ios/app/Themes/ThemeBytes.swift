// SPDX-License-Identifier: GPL-2.0-only
// The checked byte reader and capped zlib inflater for theme parsers: Swift traps where the JVM threw, so every read checks.

import Compression
import Foundation

struct ThemeDamaged: Error {}

/// So a large theme is never read whole.
protocol ThemeByteSource {
    var size: Int { get }
    func read(_ offset: Int, _ length: Int) throws -> [UInt8]
}

/// Written so no sum can overflow.
func themeRangeOK(_ offset: Int, _ length: Int, size: Int) -> Bool {
    offset >= 0 && length >= 0 && offset <= size && length <= size - offset
}

struct ArrayByteSource: ThemeByteSource {
    let bytes: [UInt8]
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    var size: Int { bytes.count }
    func read(_ offset: Int, _ length: Int) throws -> [UInt8] {
        guard themeRangeOK(offset, length, size: bytes.count) else { throw ThemeDamaged() }
        return Array(bytes[offset ..< offset + length])
    }
}

struct DataByteSource: ThemeByteSource {
    let data: Data
    init(_ data: Data) { self.data = data }
    var size: Int { data.count }
    func read(_ offset: Int, _ length: Int) throws -> [UInt8] {
        guard themeRangeOK(offset, length, size: data.count) else { throw ThemeDamaged() }
        // A Data slice keeps its parent's indices, so count from startIndex, never from 0.
        let start = data.startIndex + offset
        return [UInt8](data[start ..< start + length])
    }
}

/// Multi-byte values are unsigned, widened to Int.
struct ByteReader {
    let bytes: [UInt8]
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    var count: Int { bytes.count }

    private func check(_ at: Int, _ length: Int) throws {
        guard themeRangeOK(at, length, size: bytes.count) else { throw ThemeDamaged() }
    }

    func u8(_ at: Int) throws -> Int {
        try check(at, 1)
        return Int(bytes[at])
    }

    func be16(_ at: Int) throws -> Int {
        try check(at, 2)
        return Int(bytes[at]) << 8 | Int(bytes[at + 1])
    }

    func le16(_ at: Int) throws -> Int {
        try check(at, 2)
        return Int(bytes[at]) | Int(bytes[at + 1]) << 8
    }

    func be32(_ at: Int) throws -> Int {
        try check(at, 4)
        return Int(bytes[at]) << 24 | Int(bytes[at + 1]) << 16 | Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
    }

    func le32(_ at: Int) throws -> Int {
        try check(at, 4)
        return Int(bytes[at]) | Int(bytes[at + 1]) << 8 | Int(bytes[at + 2]) << 16 | Int(bytes[at + 3]) << 24
    }

    func slice(_ at: Int, _ length: Int) throws -> [UInt8] {
        try check(at, length)
        return Array(bytes[at ..< at + length])
    }

    func cString(at offset: Int, limit: Int = Int.max) throws -> String {
        guard offset >= 0, offset < bytes.count else { throw ThemeDamaged() }
        var end = offset
        while end < bytes.count && end - offset < limit && bytes[end] != 0 { end += 1 }
        return Self.latin1(bytes[offset ..< end])
    }

    /// Latin-1 maps every byte to the code point of the same value, so it never fails.
    func latin1(at offset: Int, count: Int) throws -> String {
        try check(offset, count)
        return Self.latin1(bytes[offset ..< offset + count])
    }

    /// A bad surrogate or an odd last byte becomes U+FFFD, as Java does.
    func utf16le(at offset: Int, count: Int) throws -> String {
        try check(offset, count)
        var units: [UInt16] = []
        units.reserveCapacity(count / 2 + 1)
        var at = offset
        while at + 1 < offset + count {
            units.append(UInt16(bytes[at]) | UInt16(bytes[at + 1]) << 8)
            at += 2
        }
        if count % 2 == 1 { units.append(0xFFFD) }
        return String(decoding: units, as: UTF16.self)
    }

    private static func latin1(_ slice: ArraySlice<UInt8>) -> String {
        String(decoding: slice.map { UInt16($0) }, as: UTF16.self)
    }
}

/// Kotlin's Double.toInt(): NaN is 0 and the result saturates. Swift's Int(_:) traps on both.
func saturatingInt(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    if d >= Double(Int.max) { return .max }
    if d <= Double(Int.min) { return .min }
    return Int(d)
}

/// Kotlin's `split('\u0000').take(count)`; Swift's split drops empty pieces, which shifts every later index.
func nulSplit(_ text: String, count: Int) -> [String] {
    text.split(separator: "\u{0}", omittingEmptySubsequences: false).prefix(max(count, 0)).map(String.init)
}

enum ThemeZlib {
    static let chunkBytes = 256 << 10
    private static let outputBytes = 64 << 10

    // shortcut: no Adler-32 check, since the decoder hides the trailer; check the last four bytes if a wrong picture shows.
    static func inflate(_ source: ThemeByteSource, offset: Int, size: Int, cap: Int,
                        emit: ([UInt8]) throws -> Void) throws {
        guard themeRangeOK(offset, size, size: source.size), size >= 2, cap >= 0 else { throw ThemeDamaged() }
        let header = ByteReader(try source.read(offset, 2))
        let cmf = try header.u8(0), flg = try header.u8(1)
        // RFC 1950: CM 8 (deflate), CINFO at most 7, (CMF * 256 + FLG) a multiple of 31, FDICT clear.
        guard cmf & 0x0F == 8, cmf >> 4 <= 7, (cmf << 8 | flg) % 31 == 0, flg & 0x20 == 0 else {
            throw ThemeDamaged()
        }

        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ThemeDamaged()
        }
        defer { compression_stream_destroy(stream) }
        let output = UnsafeMutablePointer<UInt8>.allocate(capacity: outputBytes)
        defer { output.deallocate() }

        let body = offset + 2, bodySize = size - 2
        var fed = 0
        var written = 0
        var finished = false
        while !finished && fed < bodySize {
            let chunk = try source.read(body + fed, min(chunkBytes, bodySize - fed))
            fed += chunk.count
            let flags = fed == bodySize ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
            try chunk.withUnsafeBufferPointer { input in
                stream.pointee.src_ptr = input.baseAddress!
                stream.pointee.src_size = input.count
                while true {
                    stream.pointee.dst_ptr = output
                    stream.pointee.dst_size = outputBytes
                    let status = compression_stream_process(stream, flags)
                    let produced = outputBytes - stream.pointee.dst_size
                    if produced > 0 {
                        written += produced
                        guard written <= cap else { throw ThemeDamaged() }
                        let piece = Array(UnsafeBufferPointer(start: output, count: produced))
                        try emit(piece)
                    }
                    if status == COMPRESSION_STATUS_END {
                        finished = true
                        return
                    }
                    guard status == COMPRESSION_STATUS_OK else { throw ThemeDamaged() }
                    if stream.pointee.src_size == 0 && stream.pointee.dst_size > 0 {
                        // All input taken and room left: wait for more, or, finalizing, the stream is cut short.
                        if flags == 0 { return }
                        throw ThemeDamaged()
                    }
                }
            }
        }
        guard finished else { throw ThemeDamaged() }
    }

    static func inflate(_ bytes: [UInt8], cap: Int) throws -> [UInt8] {
        var out: [UInt8] = []
        try inflate(ArrayByteSource(bytes), offset: 0, size: bytes.count, cap: cap) { out += $0 }
        return out
    }

    static func adler32(_ bytes: [UInt8], seed: UInt32 = 1) -> UInt32 {
        var a = seed & 0xFFFF, b = seed >> 16
        var i = 0
        while i < bytes.count {
            // 5552 is the most bytes that can be summed before the 32-bit sums could overflow.
            let end = min(i + 5552, bytes.count)
            while i < end {
                a &+= UInt32(bytes[i])
                b &+= a
                i += 1
            }
            a %= 65521
            b %= 65521
        }
        return b << 16 | a
    }
}
