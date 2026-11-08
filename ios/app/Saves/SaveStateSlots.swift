// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UIKit

/// What the bridge knows about one state file, read off the main thread: it opens every zip.
struct SaveStateFile: Sendable {
    let slot: Int
    let occupied: Bool
    let fileName: String
    let modifiedDate: Date?
    let preview: Data?

    // The file name carries the title's folder, so one title's slot never takes another's details.
    init(slot: Int, entry: SaveStateFiles.Entry?, directory: URL) {
        self.slot = slot
        occupied = entry != nil
        fileName = directory.lastPathComponent + "/" + (entry?.url.lastPathComponent ?? "slot\(slot)")
        modifiedDate = entry?.modified
        preview = entry?.thumbnail.flatMap { try? Data(contentsOf: $0) }
    }

    // Scans the title's folder, which the caller passes, since there is no global bridge call; one file per UI slot.
    static func current(directory: URL) async -> [SaveStateFile] {
        await Task.detached(priority: .userInitiated) {
            let listing = SaveStateFiles.scan(directory: directory, appHistory: [])
            return ([SaveStateSlot.quickSlot, SaveStateSlot.autoSlot] + Array(1...10)).map { slot in
                SaveStateFile(slot: slot, entry: SaveStateFiles.fileSlot(forUISlot: slot).flatMap { listing.slots[$0] },
                              directory: directory)
            }
        }.value
    }
}

/// Kept per state file, keyed by its file name. An entry only counts while `savedAt` still
/// matches the file, so a state replaced from the Files app never shows another state's details.
struct SaveStateMetadata: Codable, Equatable {
    var name: String?
    var locked: Bool?
    var playedSeconds: Double?
    var savedAt: Date?

    func matches(_ modified: Date?) -> Bool {
        guard let savedAt, let modified else { return false }
        return abs(savedAt.timeIntervalSince(modified)) < 1
    }
}

@MainActor
final class SaveStateMetadataStore {
    static let shared = SaveStateMetadataStore()

    private struct File: Codable {
        var schemaVersion = 1
        var states: [String: SaveStateMetadata] = [:]
    }

    private var file: File
    private let url: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        // The app's own Application Support folder, the one LibraryCache uses.
        let directory = base.appendingPathComponent("armsx3", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("SaveStateSlots.json")
        file = File()
        guard let data = try? Data(contentsOf: url) else { return }
        if let decoded = try? JSONDecoder().decode(File.self, from: data) {
            file = decoded
        } else {
            // Keep the unreadable file for recovery instead of writing over it.
            let corrupt = directory.appendingPathComponent("SaveStateSlots.corrupt.json")
            try? FileManager.default.removeItem(at: corrupt)
            try? FileManager.default.moveItem(at: url, to: corrupt)
        }
    }

    func metadata(for state: SaveStateFile) -> SaveStateMetadata? {
        guard state.occupied, let entry = file.states[state.fileName],
              entry.matches(state.modifiedDate) else { return nil }
        return entry
    }

    /// Records the play time for a state that was just written. Saving over a state keeps its
    /// name and lock; a state written into an empty slot starts without either.
    func recordSave(of state: SaveStateFile, playedSeconds: Double, fresh: Bool) {
        guard state.occupied, let modified = state.modifiedDate else { return }
        var entry = fresh ? SaveStateMetadata() : file.states[state.fileName] ?? SaveStateMetadata()
        entry.playedSeconds = playedSeconds
        entry.savedAt = modified
        file.states[state.fileName] = entry
        write()
    }

    func update(_ state: SaveStateFile, _ change: (inout SaveStateMetadata) -> Void) {
        guard state.occupied, let modified = state.modifiedDate else { return }
        var entry = metadata(for: state) ?? SaveStateMetadata(savedAt: modified)
        change(&entry)
        file.states[state.fileName] = entry
        write()
    }

    private func write() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        // No log line, since the error's text can name a path; a failed write keeps the old file.
        try? encoder.encode(file).write(to: url, options: .atomic)
    }
}

/// One row of the Save States panel.
struct SaveStateSlot: Identifiable {
    enum Kind {
        case manual
        /// A manual save in slot 9 or 10 from before those rows became Auto-save and Quick Save.
        case older
        case auto
        case quick
    }

    static let autoSlot = -2
    static let quickSlot = 0

    let file: SaveStateFile
    let kind: Kind
    let metadata: SaveStateMetadata?

    var id: Int { file.slot }
    var slot: Int { file.slot }
    var occupied: Bool { file.occupied }
    var modifiedDate: Date? { file.modifiedDate }

    /// Auto-save and Quick Save show a symbol where the other rows show their slot number.
    var symbol: String? {
        switch kind {
        case .auto: "cloud.fill"
        case .quick: "bolt.fill"
        case .manual, .older: nil
        }
    }

    @MainActor
    func title(_ settings: SettingsStore) -> String {
        if isNameable, let name = metadata?.name, !name.isEmpty {
            return name
        }
        return defaultTitle(settings)
    }

    @MainActor
    func defaultTitle(_ settings: SettingsStore) -> String {
        switch kind {
        // Typed String(localized:) interpolation, not String(format:) over looked-up copy.
        case .manual: String(localized: "Slot \(slot)")
        // Typed String(localized:) interpolation, not String(format:) over looked-up copy.
        case .older: String(localized: "Older Slot \(slot)")
        case .auto: settings.localized("Auto-save")
        case .quick: settings.localized("Quick Save")
        }
    }

    var isNameable: Bool { kind == .manual || kind == .older }

    static let nameLimit = 32

    /// One line, trimmed, at most `nameLimit` characters.
    static func cleanName(_ text: String) -> String {
        let line = text.replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(line.prefix(nameLimit))
    }

    // No "Older" rows: a PS3 slot file was never anything but its own slot.
    static func rows(
        from files: [SaveStateFile],
        load: Bool,
        metadata: (SaveStateFile) -> SaveStateMetadata?
    ) -> [SaveStateSlot] {
        func row(_ file: SaveStateFile, _ kind: Kind) -> SaveStateSlot {
            SaveStateSlot(file: file, kind: kind, metadata: metadata(file))
        }
        let bySlot = Dictionary(files.map { ($0.slot, $0) }, uniquingKeysWith: { first, _ in first })
        var rows = bySlot[quickSlot].map { [row($0, .quick)] } ?? []
        rows += (1...10).compactMap { bySlot[$0].map { row($0, .manual) } }
        if load, let auto = bySlot[autoSlot] { rows.append(row(auto, .auto)) }
        return rows
    }
}

@MainActor
enum SaveStateFormat {
    /// The app language with the system region, so a language picked in the app keeps the
    /// user's 24-hour clock and date order.
    static func locale() -> Locale {
        // The system locale: the app has no language setting of its own.
        .autoupdatingCurrent
    }

    /// "Today 18:13", "Yesterday 21:40" or "26 Sep 14:02".
    static func savedAt(_ date: Date) -> String {
        let locale = locale()
        let time = date.formatted(.dateTime.hour().minute().locale(locale))
        if Calendar.current.isDateInToday(date) {
            // Typed String(localized:) interpolation, not String(format:) over looked-up copy.
            return String(localized: "Today \(time)")
        }
        if Calendar.current.isDateInYesterday(date) {
            // Typed String(localized:) interpolation, not String(format:) over looked-up copy.
            return String(localized: "Yesterday \(time)")
        }
        return date.formatted(.dateTime.day().month(.abbreviated).hour().minute().locale(locale))
    }
}

// No Undo model: an Undo would boot the game a second time in RPCS3.

/// Auto-save's play clock, and the save itself: on a timer during play and when the game closes.
@MainActor
final class SaveStateAutoSave {
    static let shared = SaveStateAutoSave()
    /// A save this soon after a boot or a load would replace the Auto-save with a quick look.
    static let settleSeconds: Double = 120

    // Internal, so a smoke scene can age the clock past the settle.
    var restartedAt = Date()
    private var saving = false

    /// A boot or a load starts both clocks again.
    func restart() {
        restartedAt = Date()
    }

    // Seconds since the last boot or load; the settle check and the recorded play time read it.
    var secondsSinceRestart: Double { Date().timeIntervalSince(restartedAt) }

    /// Reports false without writing while an undo is pending or the game has only just started.
    // Reports the ABI status, 0 when written; a nil folder only skips the history and the details.
    func save(leaving: Bool, directory: URL?, completion: @escaping @MainActor (Int) -> Void) {
        guard !saving, secondsSinceRestart >= Self.settleSeconds else {
            return completion(EmulatorSession.wrongState)
        }
        saving = true
        let played = secondsSinceRestart
        Task { @MainActor in
            let (status, historyName) = await EmulatorSession.shared.saveState(fileSlot: EmulatorSession.autoFileSlot,
                                                                               resume: !leaving)
            self.saving = false
            if status == 0, let directory {
                try? SaveStateFiles.recordAppHistory(name: historyName,
                                                     in: directory.appendingPathComponent(SaveStateFiles.slotsFolder))
                if let file = await SaveStateFile.current(directory: directory).first(where: { $0.slot == SaveStateSlot.autoSlot }) {
                    SaveStateMetadataStore.shared.recordSave(of: file, playedSeconds: played, fresh: true)
                }
            }
            completion(status)
        }
    }
}
