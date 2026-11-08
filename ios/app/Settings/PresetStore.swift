// SPDX-License-Identifier: GPL-2.0-only
// Settings presets on disk, out of Documents. Names are one path component, and every write is atomic. Foundation only.

import Foundation

struct PresetEntry: Codable, Equatable, Identifiable {
    var name: String
    var file: String
    var count: Int

    var id: String { file }
}

final class PresetStore: Sendable {
    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("presets", isDirectory: true)
    }

    private static let allowed = CharacterSet.letters.union(.decimalDigits).union(CharacterSet(charactersIn: " -_"))

    // shortcut: 64 scalars can still exceed NAME_MAX, so that save fails and says so; count bytes if it matters.
    static func fileStem(for name: String) -> String? {
        let kept = String(String.UnicodeScalarView(name.unicodeScalars.filter { allowed.contains($0) }))
            .trimmingCharacters(in: .whitespaces)
        let stem = String(String.UnicodeScalarView(kept.unicodeScalars.prefix(64))).trimmingCharacters(in: .whitespaces)
        return stem.isEmpty || stem == "." || stem == ".." ? nil : stem
    }

    // The bridge refuses a bigger text, so a picked file over the cap is refused before it is read.
    static let importCap = 256 * 1024

    static func importable(size: Int) -> Bool { size <= importCap }

    // Nothing normalise leaves out is dropped silently.
    static func importNotice(name: String, counts: SettingsImportCounts) -> String {
        var parts = [String(localized: "Imported “\(name)”.")]
        if counts.unknown > 0 { parts.append(String(localized: "\(counts.unknown) settings this version doesn't have were left out.")) }
        if counts.defaulted > 0 {
            parts.append(String(localized: "\(counts.defaulted) settings matched their defaults and were left out, so database fixes still apply."))
        }
        if counts.locked > 0 { parts.append(String(localized: "\(counts.locked) settings this device keeps for itself were left out.")) }
        return parts.joined(separator: " ")
    }

    private var indexURL: URL { directory.appendingPathComponent("index.json") }

    private struct Index: Codable {
        var version: Int
        var presets: [PresetEntry]
    }

    private func readIndex() -> [PresetEntry] {
        guard let data = try? Data(contentsOf: indexURL),
              let index = try? JSONDecoder().decode(Index.self, from: data), index.version == 1 else { return [] }
        return index.presets
    }

    func list() -> [PresetEntry] {
        readIndex().filter { entry in
            Self.fileStem(for: entry.name).map { $0 + ".yml" } == entry.file
                && FileManager.default.fileExists(atPath: directory.appendingPathComponent(entry.file).path)
        }
    }

    func save(name: String, yaml: Data, count: Int) -> PresetEntry? {
        guard let stem = Self.fileStem(for: name) else { return nil }
        let entry = PresetEntry(name: stem, file: stem + ".yml", count: count)
        var entries = list()
        let same = entries.firstIndex { $0.file.lowercased() == entry.file.lowercased() }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            // On a case-insensitive disk the replaced file goes first, so the new one is not removed after it is written.
            if let same, entries[same].file != entry.file {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(entries[same].file))
            }
            try yaml.write(to: directory.appendingPathComponent(entry.file), options: .atomic)
            if let same {
                entries[same] = entry
            } else {
                entries.append(entry)
            }
            try JSONEncoder().encode(Index(version: 1, presets: entries)).write(to: indexURL, options: .atomic)
        } catch {
            return nil
        }
        return entry
    }

    func data(for entry: PresetEntry) -> Data? {
        guard list().contains(entry) else { return nil }
        return try? Data(contentsOf: directory.appendingPathComponent(entry.file))
    }

    func delete(_ entry: PresetEntry) -> Bool {
        var entries = list()
        guard let index = entries.firstIndex(of: entry) else { return false }
        entries.remove(at: index)
        do {
            try JSONEncoder().encode(Index(version: 1, presets: entries)).write(to: indexURL, options: .atomic)
        } catch {
            return false
        }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(entry.file))
        return true
    }
}
