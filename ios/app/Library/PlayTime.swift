// SPDX-License-Identifier: GPL-2.0-only
// Play time per title, kept in playtime.json. Nothing here prints; a corrupt file reads as empty.

import Foundation

/// Only the gap between two RUNNING reads of the same serial counts, capped at `maxGap`.
struct PlayTimeClock {
    static let maxGap: TimeInterval = 15

    private var last: (serial: String, at: Date)?

    // shortcut: a pause under-counts by at most one state-read tick (5 s); add pause/resume events if that is disputed.
    mutating func observe(serial: String?, running: Bool, at: Date) -> (serial: String, seconds: TimeInterval)? {
        guard let serial, running else {
            last = nil
            return nil
        }
        defer { last = (serial, at) }
        guard let previous = last, previous.serial == serial else { return nil }
        let gap = at.timeIntervalSince(previous.at)
        guard gap > 0 else { return nil }
        return (serial, min(gap, Self.maxGap))
    }
}

@MainActor final class PlayTime {
    static let shared = PlayTime(directory:
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true))

    private struct Title: Codable {
        var seconds: Int
        var lastPlayed: Date
    }

    private struct File: Codable {
        var version = 1
        var titles: [String: Title] = [:]
    }

    private static let saveInterval: TimeInterval = 60

    private let fileURL: URL
    private var file: File
    private var clock = PlayTimeClock()
    // Whole seconds are stored; the fraction waits here so 5 s ticks never round away.
    private var pending: [String: TimeInterval] = [:]
    private var dirty = false
    private var lastSave: Date?

    init(directory: URL) {
        fileURL = directory.appendingPathComponent("playtime.json")
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let data = try? Data(contentsOf: fileURL), let read = try? decoder.decode(File.self, from: data), read.version == 1 {
            file = read
        } else {
            file = File()
        }
    }

    func seconds(for serial: String) -> TimeInterval {
        TimeInterval(file.titles[serial]?.seconds ?? 0)
    }

    func lastPlayed(for serial: String) -> Date? {
        file.titles[serial]?.lastPlayed
    }

    /// Saves on every non-running read that follows a change, and at most once a minute while running.
    func observe(serial: String?, running: Bool, at: Date) {
        if let (serial, seconds) = clock.observe(serial: serial, running: running, at: at) {
            let whole = (pending[serial, default: 0] + seconds).rounded(.down)
            pending[serial, default: 0] += seconds - whole
            var title = file.titles[serial] ?? Title(seconds: 0, lastPlayed: at)
            title.seconds += Int(whole)
            title.lastPlayed = at
            file.titles[serial] = title
            dirty = true
        }
        guard dirty else { return }
        if !running || lastSave.map({ at.timeIntervalSince($0) >= Self.saveInterval }) ?? true {
            save(at: at)
        }
    }

    private func save(at: Date) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(file) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if (try? data.write(to: fileURL, options: .atomic)) != nil {
            dirty = false
            lastSave = at
        }
    }

    static func format(_ seconds: TimeInterval) -> String? {
        let total = Int(seconds)
        guard total >= 60 else { return nil }
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        return hours > 0
            ? String(localized: "Played \(hours) h \(minutes) min")
            : String(localized: "Played \(minutes) min")
    }
}
