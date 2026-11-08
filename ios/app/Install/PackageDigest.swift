// SPDX-License-Identifier: GPL-2.0-only
// A downloaded update package checked against the size and SHA-1 Sony publishes.

import CryptoKit
import Foundation

enum PackageCheck: Equatable, Sendable {
    case ok, missing, wrongSize, mismatch
}

enum PackageDigest {
    static let trailerLength: Int64 = 0x20
    static let chunk = 4 << 20

    // A package ends with the SHA-1 of everything before it, then 12 zero bytes.
    static func verify(at url: URL, size: Int64, sha1: String) -> PackageCheck {
        var isFolder: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isFolder), !isFolder.boolValue,
              let handle = try? FileHandle(forReadingFrom: url) else { return .missing }
        defer { try? handle.close() }
        guard size > trailerLength, (try? handle.seekToEnd()) == UInt64(size),
              (try? handle.seek(toOffset: 0)) != nil else { return .wrongSize }
        var hasher = Insecure.SHA1()
        var left = size - trailerLength
        while left > 0 {
            let count = Int(min(left, Int64(chunk)))
            guard let data = autoreleasepool(invoking: { try? handle.read(upToCount: count) }), !data.isEmpty else {
                return .mismatch
            }
            hasher.update(data: data)
            left -= Int64(data.count)
        }
        let digest = Data(hasher.finalize())
        guard let trailer = try? handle.read(upToCount: Int(trailerLength)), trailer.count == Int(trailerLength) else {
            return .mismatch
        }
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        let matches = hex == sha1.lowercased() && trailer.prefix(20) == digest && trailer.suffix(12).allSatisfy { $0 == 0 }
        return matches ? .ok : .mismatch
    }
}
