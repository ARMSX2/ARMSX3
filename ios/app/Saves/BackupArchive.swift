// SPDX-License-Identifier: GPL-2.0-only
// Backup and restore as one Android-shaped zip, from an allow-list. The layout is one-way: later versions must keep reading it.

import Foundation

struct BackupArchive {
    struct Roots {
        let config: URL
        let hdd0: URL
        let app: URL
    }

    struct Restored: Equatable {
        let written: Int
        let skipped: Int
        let prefs: Int
    }

    // Every refusal, with the sentence the Backup & Restore pane shows.
    enum Failure: Error, Equatable {
        case notABackup, newerVersion, unsafeEntry, tooLarge, running

        var message: String {
            switch self {
            case .notABackup: return String(localized: "That file isn't an ARMSX3 backup.")
            case .newerVersion: return String(localized: "This backup was made by a newer version of ARMSX3.")
            case .unsafeEntry: return String(localized: "That backup contains a file path ARMSX3 won't write; nothing was restored.")
            case .tooLarge: return String(localized: "The backup is larger than 4 GB. Turn off Include save states and try again.")
            case .running: return String(localized: "Stop the game before restoring.")
            }
        }
    }

    // A zip bomb stops here, counted on the bytes actually written.
    static func restoreLimits() -> ZipLimits { ZipLimits(entryBytes: 2 << 30, totalBytes: 8 << 30, entries: 65_535) }

    static let manifestName = "armsx3-backup.json"
    static let schemaVersion = 1
    static let topFolder = "ARMSX3-Backup"
    static let prefsEntry = "prefs/armsx3.plist"
    // An allow-list used both ways; an entry ending in `.` is a prefix, and the savestate epoch date travels too.
    static let prefsAllowed = ["armsx3.settings.", "armsx3.skins.active", "armsx3.library.favourites", "armsx3.backup.",
                               SaveStateFiles.epochSinceKey]
    static let prefsExcluded: Set<String> = ["armsx3.settings.backgroundPrimaryAsset",
                                             "armsx3.settings.backgroundLandscapeAsset"]
    static func isBackedUp(_ key: String) -> Bool {
        !prefsExcluded.contains(key) && prefsAllowed.contains { $0.hasSuffix(".") ? key.hasPrefix($0) : key == $0 }
    }
    static let savestates = "savestates/"

    // An item ending in `/` is a folder, taken whole; any other item is one file.
    enum Root { case config, hdd0, app }
    struct Area {
        let root: Root
        let prefix: String
        let items: [String]
    }

    // The RPCN login never enters a backup: after a restore the player signs in again.
    static let areas: [Area] = [
        Area(root: .config, prefix: "files/config/",
             items: ["config.yml", "patch_config.yml", "custom_configs/", "patches/", savestates, "input_configs/"]),
        Area(root: .hdd0, prefix: "files/config/dev_hdd0/", items: ["home/"]),
        Area(root: .app, prefix: "files/armsx3/", items: ["presets/", "skins/", "playtime.json"]),
    ]

    static func url(_ root: Root, in roots: Roots) -> URL {
        switch root {
        case .config: return roots.config
        case .hdd0: return roots.hdd0
        case .app: return roots.app
        }
    }

    // MARK: Stage and export

    // Hard-linked, so staging copies nothing on one volume.
    static func stage(roots: Roots, defaults: UserDefaults, includeSavestates: Bool, into: URL) throws {
        let files = FileManager.default
        let top = into.appendingPathComponent(topFolder, isDirectory: true)
        try? files.removeItem(at: top)
        try files.createDirectory(at: top, withIntermediateDirectories: true)

        let info = Bundle.main.infoDictionary
        let manifest: [String: Any] = [
            "schemaVersion": schemaVersion,
            "package": Bundle.main.bundleIdentifier ?? "",
            "versionName": info?["CFBundleShortVersionString"] as? String ?? "",
            "platform": "ios",
        ]
        try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
            .write(to: top.appendingPathComponent(manifestName))

        for area in areas {
            let base = url(area.root, in: roots)
            for item in area.items where includeSavestates || item != savestates {
                let source = base.appendingPathComponent(item)
                let target = top.appendingPathComponent(area.prefix + item)
                if item.hasSuffix("/") {
                    try stageFolder(source, to: target)
                } else if isPlainFile(source) {
                    try link(source, to: target)
                }
            }
        }

        let prefs = defaults.dictionaryRepresentation().filter { isBackedUp($0.key) }
        let prefsURL = top.appendingPathComponent(prefsEntry)
        try files.createDirectory(at: prefsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try PropertyListSerialization.data(fromPropertyList: prefs, format: .binary, options: 0).write(to: prefsURL)
    }

    // Links are never followed or taken.
    private static func stageFolder(_ source: URL, to target: URL) throws {
        guard let walk = FileManager.default.enumerator(atPath: source.path) else { return }
        for case let relative as String in walk where isPlainFile(source.appendingPathComponent(relative)) {
            try link(source.appendingPathComponent(relative), to: target.appendingPathComponent(relative))
        }
    }

    private static func isPlainFile(_ url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return values?.isRegularFile == true && values?.isSymbolicLink != true
    }

    private static func link(_ source: URL, to target: URL) throws {
        let files = FileManager.default
        try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        do {
            try files.linkItem(at: source, to: target)
        } catch {
            try files.copyItem(at: source, to: target)
        }
    }

    // A zip the restore could not read is deleted, never offered.
    static func export(roots: Roots, defaults: UserDefaults, includeSavestates: Bool, io: SaveDataIO,
                       to folder: URL) throws -> URL {
        let files = FileManager.default
        let work = files.temporaryDirectory.appendingPathComponent("armsx3-backup-" + UUID().uuidString, isDirectory: true)
        defer { try? files.removeItem(at: work) }
        try stage(roots: roots, defaults: defaults, includeSavestates: includeSavestates, into: work)
        let zip = work.appendingPathComponent("backup.zip")
        try io.zipDirectory(work.appendingPathComponent(topFolder, isDirectory: true), to: zip)

        // shortcut: ZipArchive reads no ZIP64, so a backup over 4 GiB is refused; ZIP64 reading would lift the ceiling.
        do {
            _ = try ZipArchive(url: zip)
        } catch ZipError.zip64 {
            throw Failure.tooLarge
        }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        try files.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent("ARMSX3-Backup-" + formatter.string(from: Date()) + ".zip")
        try? files.removeItem(at: target)
        try files.moveItem(at: zip, to: target)
        return target
    }

    // MARK: Restore

    // Nil when no allow-listed item holds the name.
    static func destination(_ name: String, roots: Roots) -> (root: URL, relative: String)? {
        for area in areas where name.hasPrefix(area.prefix) {
            let rest = String(name.dropFirst(area.prefix.count))
            if area.items.contains(where: { $0.hasSuffix("/") ? rest.hasPrefix($0) : rest == $0 }) {
                return (url(area.root, in: roots), rest)
            }
        }
        return nil
    }

    // shortcut: everything is staged before a live file moves, but a kill mid-swap can leave it half-done; add a journal.
    static func restore(from zip: URL, roots: Roots, defaults: UserDefaults) throws -> Restored {
        let archive: ZipArchive
        do {
            archive = try ZipArchive(url: zip)
        } catch ZipError.unsafeName, ZipError.symlink {
            throw Failure.unsafeEntry
        } catch ZipError.zip64 {
            throw Failure.tooLarge
        } catch {
            throw Failure.notABackup
        }
        let names = archive.entries.map(\.name)
        let base: String
        if names.contains(manifestName) {
            base = ""
        } else if let nested = names.first(where: {
            let parts = $0.split(separator: "/", omittingEmptySubsequences: false)
            return parts.count == 2 && parts[1] == Substring(manifestName)
        }) {
            base = String(nested.dropLast(manifestName.count))
        } else {
            throw Failure.notABackup
        }
        guard let manifest = try? read(base + manifestName, in: archive) else { throw Failure.notABackup }
        guard let object = try? JSONSerialization.jsonObject(with: manifest) as? [String: Any],
              let version = object["schemaVersion"] as? Int, version >= 1 else { throw Failure.notABackup }
        if version > schemaVersion { throw Failure.newerVersion }

        var skipped = 0
        func map(_ name: String) -> (root: URL, relative: String)? {
            guard name.hasPrefix(base) else { return nil }
            return destination(String(name.dropFirst(base.count)), roots: roots)
        }
        var limits = restoreLimits()
        var declared: Int64 = 0, count = 0
        for entry in archive.entries {
            guard let destination = map(entry.name) else {
                let ours = entry.name == base + manifestName || entry.name == base + prefsEntry
                if !entry.isDirectory && !ours { skipped += 1 }
                continue
            }
            guard let relative = SaveDataStore.safeFullRelativePath(destination.relative) else { throw Failure.unsafeEntry }
            if relative.isEmpty { continue }
            guard SaveDataStore.isInside(destination.root.appendingPathComponent(relative), destination.root) else {
                throw Failure.unsafeEntry
            }
            if entry.isDirectory { continue }
            declared += entry.size
            count += 1
            if entry.size > limits.entryBytes || declared > limits.totalBytes || count > limits.entries {
                throw Failure.tooLarge
            }
        }

        let files = FileManager.default
        let token = ".restore-" + UUID().uuidString
        let live = [roots.config, roots.hdd0, roots.app]
        func staging(_ root: URL) -> URL { root.appendingPathComponent(token, isDirectory: true) }
        func aside(_ root: URL) -> URL { root.appendingPathComponent(token + "-aside", isDirectory: true) }
        defer {
            for root in live {
                try? files.removeItem(at: staging(root))
                try? files.removeItem(at: aside(root))
            }
        }

        let written: Int
        do {
            written = try SaveDataStore.extractTree(archive, limits: &limits) { name in
                map(name).map { (root: staging($0.root), relative: $0.relative) }
            }.written
        } catch SaveImportError.unsafeEntry {
            throw Failure.unsafeEntry
        } catch ZipError.tooLarge, ZipError.tooMany {
            throw Failure.tooLarge
        }
        let prefs = try readPrefs(base, archive: archive)
        try swapIn(live, staging: staging, aside: aside)
        return Restored(written: written, skipped: skipped, prefs: importPrefs(prefs, defaults: defaults))
    }

    // On any throw every swap already made is undone in reverse, so the live roots end as they began.
    private static func swapIn(_ roots: [URL], staging: (URL) -> URL, aside: (URL) -> URL) throws {
        let files = FileManager.default
        var done: [(target: URL, kept: URL?)] = []
        do {
            for root in roots {
                let stage = staging(root)
                let names = ((files.enumerator(atPath: stage.path)?.allObjects as? [String]) ?? []).sorted()
                for name in names {
                    let staged = stage.appendingPathComponent(name), target = root.appendingPathComponent(name)
                    guard isPlainFile(staged) else {
                        try files.createDirectory(at: target, withIntermediateDirectories: true)
                        continue
                    }
                    try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                    var kept: URL?
                    if files.fileExists(atPath: target.path) {
                        let away = aside(root).appendingPathComponent(name)
                        try files.createDirectory(at: away.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try files.moveItem(at: target, to: away)
                        kept = away
                    }
                    done.append((target, kept))
                    try files.moveItem(at: staged, to: target)
                }
            }
        } catch {
            for (target, kept) in done.reversed() {
                try? files.removeItem(at: target)
                if let kept { try? files.moveItem(at: kept, to: target) }
            }
            throw error
        }
    }

    private static func read(_ name: String, in archive: ZipArchive) throws -> Data {
        guard let entry = archive.entries.first(where: { $0.name == name }) else { throw Failure.notABackup }
        let files = FileManager.default
        let file = files.temporaryDirectory.appendingPathComponent("armsx3-backup-" + UUID().uuidString)
        defer { try? files.removeItem(at: file) }
        var limits = ZipLimits(entryBytes: 4 << 20, totalBytes: 4 << 20, entries: 1)
        try archive.extract(entry, to: file, limits: &limits)
        return try Data(contentsOf: file)
    }

    // Read and parsed before any live file moves, so a read failure refuses the restore.
    private static func readPrefs(_ base: String, archive: ZipArchive) throws -> [String: Any] {
        guard archive.entries.contains(where: { $0.name == base + prefsEntry }) else { return [:] }
        let data = try read(base + prefsEntry, in: archive)
        return (try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]) ?? [:]
    }

    // Only allow-listed keys are imported, and the earlier epoch date is kept so a restore never makes a state read as older.
    private static func importPrefs(_ prefs: [String: Any], defaults: UserDefaults) -> Int {
        var imported = 0
        for (key, value) in prefs where isBackedUp(key) {
            if key == SaveStateFiles.epochSinceKey {
                guard let restored = value as? Date else { continue }
                let current = defaults.object(forKey: key) as? Date
                defaults.set(current.map { min($0, restored) } ?? restored, forKey: key)
            } else {
                defaults.set(value, forKey: key)
            }
            imported += 1
        }
        return imported
    }
}
