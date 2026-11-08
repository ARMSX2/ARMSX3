// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import Foundation
import Observation

// The touch pad's layouts on disk, beside the skins.
@MainActor @Observable final class PadLayoutStore {
    static let shared = PadLayoutStore()

    private(set) var library: PadLayoutLibrary
    @ObservationIgnored private let url: URL
    // The name an unreadable layouts file was kept under at launch, for Settings to mention.
    private(set) var setAside: String?
    // A change not yet on disk, so Done after a failed write tries again.
    @ObservationIgnored private var unsaved = false

    init(url: URL = PadLayoutStore.defaultURL) {
        self.url = url
        guard let data = try? Data(contentsOf: url) else {
            library = PadLayoutLibrary()
            return
        }
        if let decoded = try? JSONDecoder().decode(PadLayoutLibrary.self, from: data) {
            library = decoded.clamped()
        } else {
            // Set aside rather than saved over, so the player's layouts can still be recovered.
            library = PadLayoutLibrary()
            let aside = url.deletingPathExtension().appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970)).json")
            if (try? FileManager.default.moveItem(at: url, to: aside)) != nil { setAside = aside.lastPathComponent }
        }
    }

    nonisolated static var defaultURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("pad-layouts.json")
    }

    // False when the file could not be written; the change is kept for this launch either way.
    @discardableResult
    func update(_ change: (inout PadLayoutLibrary) -> Void) -> Bool {
        var next = library
        change(&next)
        if next != library {
            library = next
            unsaved = true
        }
        return unsaved ? persist() : true
    }

    func importFile(_ data: Data, fallbackName: String?) throws -> PadLayoutPreset {
        let file = try PadLayoutFile.decode(data, fallbackName: fallbackName)
        var preset: PadLayoutPreset?
        update { preset = $0.addPreset(named: file.name, layout: file.layout) }
        return preset!
    }

    // A file picked in Files or opened from another app; the notice to show either way.
    func importMessage(_ url: URL) -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size <= 1 << 20, let data = try? Data(contentsOf: url), let preset = try? importFile(data, fallbackName: url.lastPathComponent) else {
            return String(localized: "Couldn't import this file. It isn't a touch layout ARMSX3 can read. Pick a .armsx3pad file.")
        }
        return String(localized: "Added “\(preset.name)” to Layouts.")
    }

    // A temporary file the share sheet hands on.
    func exportFile(name: String, layout: PadLayout) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("pad-layouts", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(PadLayoutFile.fileName(for: name))
        try PadLayoutFile.encode(name: name, layout: layout).write(to: target, options: .atomic)
        return target
    }

    private func persist() -> Bool {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(library).write(to: url, options: .atomic)
            unsaved = false
            return true
        } catch {
            return false
        }
    }
}
