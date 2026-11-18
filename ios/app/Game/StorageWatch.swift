// SPDX-License-Identifier: GPL-2.0-only
// Free space on the volume that holds the app's data. The core treats a failed write as fatal, so a full phone ends a game with no message.

import Foundation

enum StorageWatch {
    // Below this a write can fail at any moment.
    static let lowBytes: Int64 = 500 << 20

    /// Raw free space of the volume holding `url`, or nil when it cannot be read.
    static func availableBytes(at url: URL) -> Int64? {
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityKey])
        return values?.volumeAvailableCapacity.map { Int64($0) }
    }

    static func isLow(_ bytes: Int64?) -> Bool {
        guard let bytes else { return false }
        return bytes < lowBytes
    }
}
