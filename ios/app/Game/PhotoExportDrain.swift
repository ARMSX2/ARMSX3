// SPDX-License-Identifier: GPL-2.0-only
// A game's exported photos into Photos, add-only. The guest copies a file after naming it, so each one is waited for.

import Foundation
import UIKit

@MainActor
final class PhotoExportDrain {
    static let shared = PhotoExportDrain()

    static let tries = 5

    // Replaceable for the smoke scene, which counts instead of asking Photos.
    var save: (URL) async -> Bool = { await ScreenshotStore.saveToPhotos($0, type: .photo) }

    // The wait between decode tries; the smoke scene shortens it.
    var retryDelay: UInt64 = 1_000_000_000

    // The tries run in a task of their own, so the game screen closing cannot cancel them and drop a photo.
    @discardableResult
    func drainOnce() async -> (handed: Int, unreadable: Int) {
        let paths: [String] = await withCheckedContinuation { done in
            Rpcs3Bridge.takeExportedPhotos { _, paths in done.resume(returning: paths) }
        }
        return await Task { await self.handOver(paths) }.value
    }

    private func handOver(_ paths: [String]) async -> (handed: Int, unreadable: Int) {
        var unreadable = 0
        for path in paths {
            var decoded = false
            // shortcut: a copy stalled for over a second mid-file can still pass; a format trailer check would close it.
            var lastSize: Int64?
            for attempt in 1...Self.tries {
                let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value
                if let size, size == lastSize, UIImage(contentsOfFile: path) != nil {
                    decoded = true
                    break
                }
                lastSize = size
                if attempt < Self.tries { try? await Task.sleep(nanoseconds: retryDelay) }
            }
            if decoded {
                _ = await save(URL(fileURLWithPath: path))
            } else {
                unreadable += 1
            }
        }
        return (paths.count, unreadable)
    }
}
