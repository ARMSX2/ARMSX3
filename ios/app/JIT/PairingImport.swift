// SPDX-License-Identifier: GPL-2.0-only
// Imports the pairing file, a secret: read once for its shape, never logged, swapped in so a failure keeps the old one.

import SwiftUI
import UniformTypeIdentifiers

enum PairingImport {
    static let subdirectory = "StikJIT"
    static let name = "pairingFile.plist"

    // Not Documents, which file sharing exposes and backups include; the folder is created and excluded from backup.
    static var destination: URL? {
        guard var folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent(subdirectory, isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        excludeFromBackup(&folder)
        return folder.appendingPathComponent(name, isDirectory: false)
    }

    // The old Documents home; it is only ever deleted here.
    static var legacy: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent(subdirectory, isDirectory: true)
            .appendingPathComponent(name, isDirectory: false)
    }

    static func excludeFromBackup(_ url: inout URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    // The raw value is the witness line's status.
    enum Outcome: Int {
        case imported = 0, failed = 1, legacyLockdown = 2, invalid = 3
    }

    static let maxSharedBytes = 65_536

    static func install(_ picked: URL) -> Outcome {
        let files = FileManager.default
        defer { try? files.removeItem(at: picked) }
        guard isNonEmptyRegularFile(picked), let data = try? Data(contentsOf: picked) else { return .failed }
        return install(data: data, origin: .imported).outcome
    }

    static func install(data: Data, origin: PairingSource.Origin) -> (outcome: Outcome, replaced: Bool) {
        switch PairingFileCheck.classify(data) {
        case .legacyLockdown: return (.legacyLockdown, false)
        case .invalid: return (.invalid, false)
        case .valid: break
        }
        guard let target = destination else { return (.failed, false) }
        let files = FileManager.default
        let folder = target.deletingLastPathComponent()
        let incoming = folder.appendingPathComponent(".incoming-" + name, isDirectory: false)
        let replaced = files.fileExists(atPath: target.path)
        var ok = false
        do {
            try data.write(to: incoming, options: .atomic)
            if replaced {
                _ = try files.replaceItemAt(target, withItemAt: incoming)
            } else {
                try files.moveItem(at: incoming, to: target)
            }
            ok = isNonEmptyRegularFile(target)
        } catch {
            ok = false
        }
        try? files.removeItem(at: incoming)
        guard ok else { return (.failed, false) }
        var placed = target
        excludeFromBackup(&placed)
        // A missing or unreadable sidecar reads as a hand-picked file, so a failed write must not leave the last one.
        let sidecar = folder.appendingPathComponent(PairingSource.originName, isDirectory: false)
        if (try? Data(origin.rawValue.utf8).write(to: sidecar, options: .atomic)) == nil { try? files.removeItem(at: sidecar) }
        if let old = legacy { try? files.removeItem(at: old) }
        return (.imported, replaced)
    }

    // Open in can hand over the sender's own file, so it is only read here.
    static func readShared(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let size = values.fileSize, size > 0, size <= maxSharedBytes else { return nil }
        return try? Data(contentsOf: url)
    }

    // File-system metadata only; the contents are never opened.
    static func isNonEmptyRegularFile(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]) else { return false }
        return values.isRegularFile == true && (values.fileSize ?? 0) > 0
    }

    // `onFinish` gets nil on cancel, else the import's outcome.
    struct Picker: View {
        let onFinish: (Outcome?) -> Void

        var body: some View {
            FirmwarePicker(types: [.propertyList, UTType(importedAs: "org.sidestore.mobiledevicepairing")]) { url in onFinish(url.map(PairingImport.install)) }
        }
    }
}
