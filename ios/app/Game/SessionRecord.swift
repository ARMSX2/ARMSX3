// SPDX-License-Identifier: GPL-2.0-only
// Documents/armsx3/session.json. Finder can see Documents, so it never names a serial, a title, a path or the device.

import Darwin
import Foundation
import GameController

@MainActor final class SessionRecord {
    static let shared = SessionRecord()

    enum PadSource { case touch, controller }

    static let updateSeconds = 5.0

    private var active = false
    private var utcStart = ""
    private var category = ""
    private var source = ""
    private var isISO = false
    private var mode = ""
    private var bootStatus = 0
    private var guestFlips: UInt64 = 0
    private var guestVblanks: UInt64 = 0
    private var fpsWindow = 0.0
    private var padTouchOK = 0
    private var padControllerOK = 0
    private var padFailed = 0
    private var stopStatus: Int?

    private init() {}

    // Called by the touch pad and the controller poller with each call's status.
    func countPad(source: PadSource, status: Int) {
        guard active else { return }
        if status != 0 {
            padFailed += 1
        } else if source == .touch {
            padTouchOK += 1
        } else {
            padControllerOK += 1
        }
    }

    // Counters reset, and the previous session's record is rotated aside.
    func start(game: Rpcs3GameEntry, bootStatus: Int) async {
        active = true
        utcStart = Self.utcNow()
        category = game.category
        source = game.fromGamesYml ? "yml" : "hdd0"
        isISO = game.isISO
        mode = "jit"
        self.bootStatus = bootStatus
        guestFlips = 0
        guestVblanks = 0
        fpsWindow = 0
        padTouchOK = 0
        padControllerOK = 0
        padFailed = 0
        stopStatus = nil
        await sampleFrames()
        write(rotate: true)
    }

    func update() async {
        guard active else { return }
        await sampleFrames(windowed: true)
        write(rotate: false)
    }

    func finish(stopStatus: Int) async {
        guard active else { return }
        await sampleFrames()
        self.stopStatus = stopStatus
        write(rotate: false)
        active = false
    }

    // Only the 5 s ticks recompute fps_window; the boot and stop samples leave it as it was.
    private func sampleFrames(windowed: Bool = false) async {
        let (vblanks, flips, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.frameCounters { vblanks, flips, status in continuation.resume(returning: (vblanks, flips, status)) }
        }
        guard status == 0 else { return }
        if windowed {
            let delta = flips >= guestFlips ? flips - guestFlips : 0
            fpsWindow = (Double(delta) / Self.updateSeconds * 10).rounded() / 10
        }
        guestFlips = flips
        guestVblanks = vblanks
    }

    private func fields() -> [String: Any] {
        let metrics = Rpcs3Bridge.frameMetrics()
        #if DEBUG
        let configuration = "debug"
        #else
        let configuration = "release"
        #endif
        return [
            "schema": "armsx3-session-record/1",
            "utc_start": utcStart,
            "utc_last": Self.utcNow(),
            "configuration": configuration,
            "category": category,
            "source": source,
            "is_iso": isISO,
            "mode": mode,
            "boot_status": bootStatus,
            "guest_flips": guestFlips,
            "guest_vblanks": guestVblanks,
            "fps_window": fpsWindow,
            "points_w": metrics.pointsWidth,
            "points_h": metrics.pointsHeight,
            "scale": metrics.scale,
            "pixel_w": metrics.pixelWidth,
            "pixel_h": metrics.pixelHeight,
            "pad_touch_ok": padTouchOK,
            "pad_controller_ok": padControllerOK,
            "pad_failed": padFailed,
            "controller_connected": !GCController.controllers().isEmpty,
            "audio_session_active": Rpcs3Bridge.audioSessionActive(),
            "stop_status": stopStatus.map { $0 as Any } ?? NSNull(),
        ]
    }

    private func write(rotate: Bool) {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        let dir = documents.appendingPathComponent("armsx3", isDirectory: true)
        let current = dir.appendingPathComponent("session.json").path
        let partial = dir.appendingPathComponent("session.json.partial").path
        let previous = dir.appendingPathComponent("session-previous.json").path
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: fields(), options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
            try data.write(to: URL(fileURLWithPath: partial))
        } catch {
            unlink(partial)
            return
        }
        if rotate { _ = rename(current, previous) }
        if rename(partial, current) != 0 {
            unlink(partial)
        }
    }

    private static func utcNow() -> String {
        ISO8601DateFormatter().string(from: Date())
    }
}
