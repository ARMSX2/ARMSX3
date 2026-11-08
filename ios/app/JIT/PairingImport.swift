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

    static func install(_ picked: URL) -> Outcome {
        let files = FileManager.default
        defer { try? files.removeItem(at: picked) }
        var outcome = Outcome.failed
        if isNonEmptyRegularFile(picked), let target = destination {
            // The one read: the bytes go straight to the shape check and are dropped with this scope.
            switch (try? Data(contentsOf: picked)).map(PairingFileCheck.classify) {
            case .legacyLockdown?:
                outcome = .legacyLockdown
            case .invalid?:
                outcome = .invalid
            case nil:
                outcome = .failed
            case .valid?:
                let incoming = target.deletingLastPathComponent().appendingPathComponent(".incoming-" + name, isDirectory: false)
                var ok = false
                do {
                    try? files.removeItem(at: incoming)
                    try files.moveItem(at: picked, to: incoming)
                    if files.fileExists(atPath: target.path) {
                        _ = try files.replaceItemAt(target, withItemAt: incoming)
                    } else {
                        try files.moveItem(at: incoming, to: target)
                    }
                    ok = isNonEmptyRegularFile(target)
                } catch {
                    ok = false
                }
                try? files.removeItem(at: incoming)
                if ok {
                    var placed = target
                    excludeFromBackup(&placed)
                    if let old = legacy { try? files.removeItem(at: old) }
                }
                outcome = ok ? .imported : .failed
            }
        }
        return outcome
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
            FirmwarePicker(types: [.propertyList]) { url in onFinish(url.map(PairingImport.install)) }
        }
    }
}
