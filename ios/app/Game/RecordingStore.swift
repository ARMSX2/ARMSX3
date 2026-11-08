// SPDX-License-Identifier: GPL-2.0-only
// Gameplay recording into Documents/Recordings, one at a time. Photos access is add-only and asked only on a tap.

import Foundation
import Observation
import Photos

@MainActor @Observable final class RecordingStore {
    static let shared = RecordingStore()

    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Recordings", isDirectory: true)
    }

    // ScreenshotStore's `<serial>_<yyyyMMdd-HHmmss>` with its serial rule, as `.mp4`.
    static func fileName(serial: String, at date: Date) -> String {
        String(ScreenshotStore.fileName(serial: serial, at: date).dropLast(".png".count)) + ".mp4"
    }

    // True from a start that answered 0 until its stop has finished the file; the red dot shows while it is.
    private(set) var active = false
    private(set) var current: URL?
    // Set by the red dot itself as it appears, and cleared by stop(), so the smoke scene can tell it was drawn.
    var dotDrawn = false
    // A later call joins the one in flight, so a stop during a start still stops what it started.
    private var starting: Task<Int, Never>?
    private var stopping: Task<URL?, Never>?

    // 0 recording, -9 already recording, a stop in flight or nothing to record, -3 a path it refused.
    func start(serial: String) async -> Int {
        if let starting { return await starting.value }
        guard !active, stopping == nil else { return EmulatorSession.wrongState }
        let file = Self.folder.appendingPathComponent(Self.fileName(serial: serial, at: Date()), isDirectory: false)
        let task = Task { () -> Int in
            try? FileManager.default.createDirectory(at: Self.folder, withIntermediateDirectories: true)
            let status = await EmulatorSession.shared.startRecording(path: file.path)
            if status == 0 {
                active = true
                current = file
            }
            starting = nil
            return status
        }
        starting = task
        return await task.value
    }

    // nil unless the bridge reports the file completed and it is non-empty; only such a file is offered to Photos.
    func stop() async -> URL? {
        if let starting { _ = await starting.value }
        if let stopping { return await stopping.value }
        guard active, let file = current else { return nil }
        let task = Task { () -> URL? in
            let status = await EmulatorSession.shared.stopRecording()
            active = false
            current = nil
            dotDrawn = false
            stopping = nil
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return status == 0 && size > 0 ? file : nil
        }
        stopping = task
        return await task.value
    }

    // Add-only, on the player's tap only.
    static func saveToPhotos(_ file: URL) async -> Bool {
        await ScreenshotStore.saveToPhotos(file, type: .video)
    }
}
