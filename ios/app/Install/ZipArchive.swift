// SPDX-License-Identifier: GPL-2.0-only
// A zip reader for save imports. Limits count bytes written, never header claims, so a bomb stops at the limit.

import Compression
import Foundation

enum ZipError: Error {
    case notAZip, zip64, encrypted, unsupportedMethod, unsafeName, symlink, tooLarge, tooMany, corrupt
}

// Defaults are Android's (SaveDataImporter.kt).
struct ZipLimits {
    var entryBytes: Int64 = 256 * 1024 * 1024
    var totalBytes: Int64 = 1024 * 1024 * 1024
    var entries = 20_000
    private(set) var written: Int64 = 0
    private(set) var count = 0

    init() {}

    init(entryBytes: Int64, totalBytes: Int64, entries: Int) {
        self.entryBytes = entryBytes
        self.totalBytes = totalBytes
        self.entries = entries
    }

    mutating func admitEntry() throws {
        count += 1
        if count > entries { throw ZipError.tooMany }
    }

    // `entrySoFar` is what this entry has written before these `bytes`.
    mutating func admit(_ bytes: Int64, entrySoFar: Int64) throws {
        written += bytes
        if entrySoFar + bytes > entryBytes || written > totalBytes { throw ZipError.tooLarge }
    }
}

struct ZipArchive {
    struct Entry {
        let name: String
        let method: Int
        let compressedSize: Int64
        let size: Int64
        let localOffset: UInt64
        let modified: Date?
        var isDirectory: Bool { name.hasSuffix("/") }
    }

    let url: URL
    let entries: [Entry]

    init(url: URL) throws {
        self.url = url
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let length = try handle.seekToEnd()
        let tailLength = min(length, 22 + 65_535 + 20)
        try handle.seek(toOffset: length - tailLength)
        let tail = [UInt8](try handle.read(upToCount: Int(tailLength)) ?? Data())

        var end = -1
        var at = tail.count - 22
        while at >= 0 {
            if Self.u32(tail, at) == 0x0605_4b50 { end = at; break }
            at -= 1
        }
        guard end >= 0 else { throw ZipError.notAZip }
        if end >= 20, Self.u32(tail, end - 20) == 0x0706_4b50 { throw ZipError.zip64 }
        let count = Self.u16(tail, end + 10)
        let directorySize = Self.u32(tail, end + 12), directoryOffset = Self.u32(tail, end + 16)
        if Self.u16(tail, end + 8) == 0xFFFF || count == 0xFFFF || directorySize == 0xFFFF_FFFF
            || directoryOffset == 0xFFFF_FFFF { throw ZipError.zip64 }
        guard UInt64(directoryOffset) + UInt64(directorySize) <= length else { throw ZipError.corrupt }

        try handle.seek(toOffset: UInt64(directoryOffset))
        let directory = [UInt8](try handle.read(upToCount: directorySize) ?? Data())
        guard directory.count == directorySize else { throw ZipError.corrupt }

        var entries: [Entry] = []
        var cursor = 0
        for _ in 0..<count {
            guard cursor + 46 <= directory.count, Self.u32(directory, cursor) == 0x0201_4b50 else { throw ZipError.corrupt }
            let flags = Self.u16(directory, cursor + 8), method = Self.u16(directory, cursor + 10)
            let time = Self.u16(directory, cursor + 12), date = Self.u16(directory, cursor + 14)
            let compressed = Self.u32(directory, cursor + 20), size = Self.u32(directory, cursor + 24)
            let nameLength = Self.u16(directory, cursor + 28)
            let extraLength = Self.u16(directory, cursor + 30), commentLength = Self.u16(directory, cursor + 32)
            let external = Self.u32(directory, cursor + 38), offset = Self.u32(directory, cursor + 42)
            guard cursor + 46 + nameLength <= directory.count else { throw ZipError.corrupt }
            let name = String(decoding: directory[(cursor + 46)..<(cursor + 46 + nameLength)], as: UTF8.self)

            if compressed == 0xFFFF_FFFF || size == 0xFFFF_FFFF || offset == 0xFFFF_FFFF { throw ZipError.zip64 }
            if flags & 1 != 0 { throw ZipError.encrypted }
            if method != 0 && method != 8 { throw ZipError.unsupportedMethod }
            if (external >> 16) & 0o170000 == 0o120000 { throw ZipError.symlink }
            // Control characters go before the `..` test, and scalars are compared, so no combining mark hides a separator.
            let scalars = name.unicodeScalars
            let parts = scalars.filter { $0.value >= 0x20 }.split(omittingEmptySubsequences: false) { $0 == "/" || $0 == "\\" }
                .map { String(String.UnicodeScalarView($0)) }
            if scalars.first == "/" || scalars.first == "\\" || scalars.contains(":") || parts.contains("..") {
                throw ZipError.unsafeName
            }

            entries.append(Entry(name: name, method: method, compressedSize: Int64(compressed), size: Int64(size),
                                 localOffset: UInt64(offset), modified: Self.dosDate(date: date, time: time)))
            cursor += 46 + nameLength + extraLength + commentLength
        }
        self.entries = entries
    }

    // shortcut: no CRC check, the game's own PFD/PARAM checks catch a corrupt save; add CRC32 if a corrupt import is reported.
    func extract(_ entry: Entry, to destination: URL, limits: inout ZipLimits) throws {
        try limits.admitEntry()
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: entry.localOffset)
        let local = [UInt8](try handle.read(upToCount: 30) ?? Data())
        guard local.count == 30, Self.u32(local, 0) == 0x0403_4b50 else { throw ZipError.corrupt }
        try handle.seek(toOffset: entry.localOffset + 30 + UInt64(Self.u16(local, 26)) + UInt64(Self.u16(local, 28)))

        guard FileManager.default.createFile(atPath: destination.path, contents: nil),
              let out = try? FileHandle(forWritingTo: destination) else { throw ZipError.corrupt }
        defer { try? out.close() }

        var remaining = entry.compressedSize
        var written: Int64 = 0
        func emit(_ data: Data) throws {
            try limits.admit(Int64(data.count), entrySoFar: written)
            written += Int64(data.count)
            try out.write(contentsOf: data)
        }

        if entry.method == 0 {
            while remaining > 0 {
                let chunk = try handle.read(upToCount: Int(min(remaining, 65_536))) ?? Data()
                guard !chunk.isEmpty else { throw ZipError.corrupt }
                remaining -= Int64(chunk.count)
                try emit(chunk)
            }
        } else {
            try inflate(from: handle, compressed: entry.compressedSize, emit: emit)
        }
        guard written == entry.size else { throw ZipError.corrupt }
        if let modified = entry.modified {
            try? FileManager.default.setAttributes([.modificationDate: modified], ofItemAtPath: destination.path)
        }
    }

    // Raw DEFLATE (COMPRESSION_ZLIB decodes RFC 1951 without a zlib header), in 64 KiB steps.
    private func inflate(from handle: FileHandle, compressed: Int64, emit: (Data) throws -> Void) throws {
        guard compressed > 0 else { throw ZipError.corrupt }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ZipError.corrupt
        }
        defer { compression_stream_destroy(stream) }

        let capacity = 65_536
        let output = UnsafeMutablePointer<UInt8>.allocate(capacity: capacity)
        defer { output.deallocate() }
        var remaining = compressed
        var finished = false
        while !finished && remaining > 0 {
            let chunk = try handle.read(upToCount: Int(min(remaining, 65_536))) ?? Data()
            guard !chunk.isEmpty else { throw ZipError.corrupt }
            remaining -= Int64(chunk.count)
            let flags = remaining == 0 ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
            try chunk.withUnsafeBytes { (input: UnsafeRawBufferPointer) in
                stream.pointee.src_ptr = input.bindMemory(to: UInt8.self).baseAddress!
                stream.pointee.src_size = input.count
                while true {
                    stream.pointee.dst_ptr = output
                    stream.pointee.dst_size = capacity
                    let status = compression_stream_process(stream, flags)
                    let produced = capacity - stream.pointee.dst_size
                    if produced > 0 { try emit(Data(bytes: output, count: produced)) }
                    if status == COMPRESSION_STATUS_END { finished = true; return }
                    guard status == COMPRESSION_STATUS_OK else { throw ZipError.corrupt }
                    if stream.pointee.src_size == 0 && stream.pointee.dst_size > 0 {
                        // All input taken and room left: wait for more, or, finalizing, the stream is cut short.
                        if flags == 0 { return }
                        throw ZipError.corrupt
                    }
                }
            }
        }
        guard finished else { throw ZipError.corrupt }
    }

    private static func u16(_ bytes: [UInt8], _ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
    private static func u32(_ bytes: [UInt8], _ at: Int) -> Int { u16(bytes, at) | u16(bytes, at + 2) << 16 }

    // An MS-DOS date and time, local time, as zips store them; nil when it is not a real date.
    private static func dosDate(date: Int, time: Int) -> Date? {
        var parts = DateComponents()
        parts.year = 1980 + (date >> 9)
        parts.month = (date >> 5) & 0xF
        parts.day = date & 0x1F
        parts.hour = time >> 11
        parts.minute = (time >> 5) & 0x3F
        parts.second = (time & 0x1F) * 2
        guard parts.isValidDate(in: Calendar(identifier: .gregorian)) else { return nil }
        return Calendar(identifier: .gregorian).date(from: parts)
    }
}
