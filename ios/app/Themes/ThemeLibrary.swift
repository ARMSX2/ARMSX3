// SPDX-License-Identifier: GPL-2.0-only
// Imported themes on disk, one folder each; every later build must read this layout. Foundation only, for the host test.

import Foundation

struct ThemeMeta: Codable, Equatable {
    enum Source: String, Codable {
        case hd, sd, preview
        case slides
        case live
    }

    static let currentVersion = 1
    /// 1 still only, 2 slideshows, 3 live scenes.
    static let importerLevel = 3

    var version: Int
    var name: String
    var added: Date
    var importer: Int
    var digest: String
    var source: Source
    var still: String
    /// The still is the first.
    var slides: [String]?
    var timing: ThemeTiming?
    var scene: String?

    var kindWord: String {
        switch source {
        case .hd, .sd: return String(localized: "Picture")
        case .preview: return String(localized: "Preview picture")
        case .slides: return isSlideshow ? String(localized: "Slideshow") : String(localized: "Picture")
        case .live: return String(localized: "Live")
        }
    }

    var witnessKind: String {
        switch source {
        case .hd, .sd: return "picture"
        case .preview: return "preview"
        case .slides: return isSlideshow ? "slideshow" : "picture"
        case .live: return "live"
        }
    }

    /// One baked slide is a plain picture.
    private var isSlideshow: Bool { (slides?.count ?? 0) >= 2 }
}

struct ThemeTiming: Codable, Equatable {
    var interval: Double
    var fade: Double
    var zoom: Double
    var zoomInterval: Double
    var zoomMove: Double

    var isFinite: Bool {
        interval.isFinite && fade.isFinite && zoom.isFinite && zoomInterval.isFinite && zoomMove.isFinite
    }
}

enum ThemePlayMode: Equatable { case still, slideshow, live }

struct ThemeEntry: Identifiable, Equatable {
    let folderName: String
    let folder: URL
    let meta: ThemeMeta
    var id: String { folderName }
    var stillURL: URL { folder.appendingPathComponent(meta.still, isDirectory: false) }

    /// Plain names of regular files present in the folder.
    var slideURLs: [URL] {
        (meta.slides ?? []).filter(ThemeLibrary.isPlainName).map {
            folder.appendingPathComponent($0, isDirectory: false)
        }.filter(Self.isFile)
    }

    private static func isFile(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && !isDir.boolValue
    }

    /// A slideshow or live theme whose files are missing, unsafe or badly timed plays its still, `reason` `failed`.
    func playback() -> (mode: ThemePlayMode, reason: String) {
        if meta.source == .live {
            guard let scene = meta.scene, ThemeLibrary.isPlainName(scene), ThemeLibrary.isPlainName(meta.still),
                  Self.isFile(folder.appendingPathComponent(scene, isDirectory: false)), Self.isFile(stillURL)
            else { return (.still, "failed") }
            return (.live, "none")
        }
        guard meta.source == .slides, let names = meta.slides, names.count >= 2 else { return (.still, "none") }
        guard names.allSatisfy(ThemeLibrary.isPlainName), meta.timing?.isFinite == true,
              slideURLs.count >= 2 else { return (.still, "failed") }
        return (.slideshow, "none")
    }
}

enum ThemeImportFailure: String, Error {
    case notTheme = "not-theme"
    case damaged = "damaged"
    case noPicture = "no-picture"
    case dynamicOnly = "dynamic-only"
    case readFailed = "read-failed"
    case writeFailed = "write-failed"

    var message: String {
        switch self {
        case .notTheme: return String(localized: "That file isn't a PS3 theme. Pick a .p3t file.")
        case .damaged: return String(localized: "This PS3 theme is damaged and couldn't be read. Try another copy of it.")
        case .noPicture: return String(localized: "This PS3 theme has no background picture. Pick another theme.")
        case .dynamicOnly:
            return String(localized: "ARMSX3 can't play this dynamic theme, and it has no still picture to show instead. Pick another theme.")
        case .readFailed:
            return String(localized: "That file couldn't be read. If it is in iCloud Drive, download it first, then try again.")
        case .writeFailed: return String(localized: "The theme couldn't be saved. Check free space, then try again.")
        }
    }

    static func from(_ failure: P3tTheme.Failure) -> ThemeImportFailure {
        switch failure {
        case .notATheme: return .notTheme
        case .damaged: return .damaged
        case .noPicture: return .noPicture
        case .dynamicOnly: return .dynamicOnly
        }
    }
}

struct ThemeLibrary {
    let root: URL

    static let metaName = "theme.json"
    static let folderPrefix = "theme-"
    static let stagingPrefix = ".import-"
    static let backupPrefix = ".old-theme-"
    static let armedName = ".live-armed"
    static let blockedName = ".live-blocked"
    static let maxNameBytes = 256
    static let removeFailedMessage = String(localized: "The theme's files couldn't be deleted. Try again.")

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    /// Oldest first, ties by folder name.
    func list() -> [ThemeEntry] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: root.path) else { return [] }
        return names.compactMap(entry(named:)).sorted {
            ($0.meta.added, $0.folderName) < ($1.meta.added, $1.folderName)
        }
    }

    /// Nil when the name is not theme-<UUID> or its metadata or still is unreadable.
    func entry(named name: String) -> ThemeEntry? {
        guard Self.isThemeFolderName(name) else { return nil }
        let folder = root.appendingPathComponent(name, isDirectory: true)
        guard Self.isDirectory(folder),
              let data = try? Data(contentsOf: folder.appendingPathComponent(Self.metaName, isDirectory: false)),
              let meta = try? Self.decoder.decode(ThemeMeta.self, from: data),
              (1 ... ThemeMeta.currentVersion).contains(meta.version),
              Self.isPlainName(meta.still) else { return nil }
        var isDir: ObjCBool = false
        let still = folder.appendingPathComponent(meta.still, isDirectory: false)
        guard FileManager.default.fileExists(atPath: still.path, isDirectory: &isDir), !isDir.boolValue else { return nil }
        return ThemeEntry(folderName: name, folder: folder, meta: meta)
    }

    func makeStaging() throws -> URL {
        let staging = root.appendingPathComponent(Self.stagingPrefix + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        return staging
    }

    /// A digest already listed updates that entry in place, keeping its folder and `added`.
    func install(staging: URL, meta: ThemeMeta) throws -> ThemeEntry {
        let fm = FileManager.default
        guard Self.isDirectory(staging), Self.isPlainName(meta.still) else { throw CocoaError(.fileNoSuchFile) }
        let existing = list().first { $0.meta.digest == meta.digest }
        var stored = meta
        if let existing = existing { stored.added = existing.meta.added }
        try Self.encoder.encode(stored).write(to: staging.appendingPathComponent(Self.metaName, isDirectory: false),
                                              options: .atomic)
        let name: String
        if let existing = existing {
            name = existing.folderName
            let backup = root.appendingPathComponent(
                Self.backupPrefix + String(name.dropFirst(Self.folderPrefix.count)), isDirectory: true)
            try fm.moveItem(at: existing.folder, to: backup)
            do {
                try fm.moveItem(at: staging, to: existing.folder)
            } catch {
                try? fm.moveItem(at: backup, to: existing.folder)
                throw error
            }
            try? fm.removeItem(at: backup)
        } else {
            name = Self.folderPrefix + UUID().uuidString
            try fm.moveItem(at: staging, to: root.appendingPathComponent(name, isDirectory: true))
        }
        guard let entry = entry(named: name) else { throw CocoaError(.fileReadCorruptFile) }
        return entry
    }

    /// Refuses any name that is not theme-<UUID>.
    func remove(named name: String) throws {
        guard Self.isThemeFolderName(name) else { throw CocoaError(.fileNoSuchFile) }
        try FileManager.default.removeItem(at: root.appendingPathComponent(name, isDirectory: true))
    }

    /// An `.old-theme-` folder is deleted when its theme exists, else renamed back. No other name is touched.
    func sweep() {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: root.path) else { return }
        for name in names {
            let url = root.appendingPathComponent(name, isDirectory: true)
            if name.hasPrefix(Self.stagingPrefix),
               Self.isCanonicalUUID(String(name.dropFirst(Self.stagingPrefix.count))) {
                try? fm.removeItem(at: url)
            } else if name.hasPrefix(Self.backupPrefix) {
                let live = Self.folderPrefix + String(name.dropFirst(Self.backupPrefix.count))
                guard Self.isThemeFolderName(live) else { continue }
                let liveURL = root.appendingPathComponent(live, isDirectory: true)
                if fm.fileExists(atPath: liveURL.path) {
                    try? fm.removeItem(at: url)
                } else {
                    try? fm.moveItem(at: url, to: liveURL)
                }
            }
        }
    }

    // MARK: - The crash-loop breaker

    /// Written synchronously before the first frame, so it survives the process dying in the live path.
    func armLive(_ name: String) {
        guard Self.isThemeFolderName(name) else { return }
        try? Data(name.utf8).write(to: root.appendingPathComponent(Self.armedName, isDirectory: false))
    }

    func disarmLive() {
        removeArmedMarker()
    }

    /// The marker is removed either way.
    func takeArmedOnLaunch() -> String? {
        let url = root.appendingPathComponent(Self.armedName, isDirectory: false)
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        removeArmedMarker()
        let name = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isThemeFolderName(name) else { return nil }
        var blocked = blockedNames()
        if !blocked.contains(name) {
            blocked.append(name)
            writeBlocked(blocked)
        }
        return name
    }

    func isLiveBlocked(_ name: String) -> Bool {
        Self.isThemeFolderName(name) && blockedNames().contains(name)
    }

    func clearLiveBlock(_ name: String) {
        let blocked = blockedNames()
        if blocked.contains(name) { writeBlocked(blocked.filter { $0 != name }) }
    }

    /// Any other line is ignored.
    private func blockedNames() -> [String] {
        let url = root.appendingPathComponent(Self.blockedName, isDirectory: false)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").map(String.init).filter(Self.isThemeFolderName)
    }

    private func writeBlocked(_ names: [String]) {
        try? Data(names.map { $0 + "\n" }.joined().utf8)
            .write(to: root.appendingPathComponent(Self.blockedName, isDirectory: false), options: .atomic)
    }

    /// The one removeItem outside the folder functions.
    private func removeArmedMarker() {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(Self.armedName, isDirectory: false))
    }

    /// Upper case, as UUID().uuidString writes it.
    static func isThemeFolderName(_ name: String) -> Bool {
        name.hasPrefix(folderPrefix) && isCanonicalUUID(String(name.dropFirst(folderPrefix.count)))
    }

    /// BackgroundAsset's rule for a name joined to a folder.
    static func isPlainName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("\0") && !name.contains("/")
    }

    /// Control and bidi characters are removed and the result is at most 256 UTF-8 bytes, cut on a character boundary.
    static func displayName(theme: String?, fileName: String) -> String {
        if let theme = theme, let cleaned = cleanName(theme) { return cleaned }
        var base = fileName
        if base.lowercased().hasSuffix(".p3t") { base = String(base.dropLast(4)) }
        return cleanName(base) ?? String(localized: "PS3 theme")
    }

    private static func cleanName(_ text: String) -> String? {
        // CharacterSet.controlCharacters is the Cc and Cf categories, which hold NUL and the bidi controls.
        let scalars = text.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        let visible = String(String.UnicodeScalarView(scalars)).trimmingCharacters(in: .whitespacesAndNewlines)
        var capped = ""
        var bytes = 0
        for character in visible {
            let size = character.utf8.count
            if bytes + size > maxNameBytes { break }
            capped.append(character)
            bytes += size
        }
        let result = capped.trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty ? nil : result
    }

    private static func isCanonicalUUID(_ text: String) -> Bool {
        UUID(uuidString: text)?.uuidString == text
    }

    private static func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue
    }
}
