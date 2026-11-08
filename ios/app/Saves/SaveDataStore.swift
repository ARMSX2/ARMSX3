// SPDX-License-Identifier: GPL-2.0-only
// A title's save data on disk, with import, export and delete. Foundation only, for the host test; nothing here logs a name.

import Foundation

struct SaveEntry: Identifiable, Equatable {
    let dirName: String
    let title: String
    let subtitle: String
    let detail: String
    let modified: Date
    let size: Int64
    let iconURL: URL?

    var id: String { dirName }
}

// The platform call a host test cannot make.
protocol SaveDataIO {
    func zipDirectory(_ dir: URL, to zip: URL) throws
}

// The `.forUploading` zip lives only inside the block, so it is copied out there.
struct FoundationSaveDataIO: SaveDataIO {
    func zipDirectory(_ dir: URL, to zip: URL) throws {
        var coordination: NSError?
        var copy: Error?
        NSFileCoordinator().coordinate(readingItemAt: dir, options: .forUploading, error: &coordination) { produced in
            do {
                try? FileManager.default.removeItem(at: zip)
                try FileManager.default.copyItem(at: produced, to: zip)
            } catch {
                copy = error
            }
        }
        if let error = coordination ?? copy { throw error }
    }
}

// The plain sentence each refusal shows, and the word the witness line carries.
enum SaveImportError: Error, Equatable {
    case noSave, unreadableSfo, badName, encrypted, unsafeEntry, symlink, zipEncrypted, zip64, zipMethod
    case tooLarge, tooMany, unreadable, exists, backupFailed, writeFailed

    var word: String {
        switch self {
        case .noSave: return "no-save"
        case .unreadableSfo: return "bad-sfo"
        case .badName: return "bad-name"
        case .encrypted: return "encrypted"
        case .unsafeEntry: return "unsafe-entry"
        case .symlink: return "symlink"
        case .zipEncrypted: return "zip-encrypted"
        case .zip64: return "zip64"
        case .zipMethod: return "zip-method"
        case .tooLarge: return "too-large"
        case .tooMany: return "too-many"
        case .unreadable: return "unreadable"
        case .exists: return "exists"
        case .backupFailed: return "backup-failed"
        case .writeFailed: return "write-failed"
        }
    }

    var message: String {
        switch self {
        case .noSave: return String(localized: "There's no PS3 save in there (no PARAM.SFO)")
        case .unreadableSfo: return String(localized: "The save's PARAM.SFO can't be read")
        case .badName: return String(localized: "The game wouldn't find a save named like this")
        case .encrypted: return String(localized: "This save is still encrypted from a PS3 and can't be used here")
        case .unsafeEntry: return String(localized: "The zip names a file outside its own folder, so it was refused")
        case .symlink: return String(localized: "The folder contains a link to somewhere else, so it was refused")
        case .zipEncrypted: return String(localized: "The zip is password-protected")
        case .zip64, .zipMethod: return String(localized: "This kind of zip isn't supported; zip the save again with standard settings")
        case .tooLarge: return String(localized: "The save is far bigger than any PS3 save, so it was refused")
        case .tooMany: return String(localized: "The save has far more files than any PS3 save, so it was refused")
        case .unreadable: return String(localized: "The save couldn't be read")
        case .exists: return String(localized: "A save with this name is already here")
        case .backupFailed: return String(localized: "The current save couldn't be backed up, so nothing was replaced")
        case .writeFailed: return String(localized: "The save couldn't be written; the old one is unchanged")
        }
    }

    init(_ error: ZipError) {
        switch error {
        case .notAZip, .corrupt: self = .unreadable
        case .zip64: self = .zip64
        case .encrypted: self = .zipEncrypted
        case .unsupportedMethod: self = .zipMethod
        case .unsafeName: self = .unsafeEntry
        case .symlink: self = .symlink
        case .tooLarge: self = .tooLarge
        case .tooMany: self = .tooMany
        }
    }
}

struct StagedImport {
    let dirName: String
    let folder: URL
    let incomingDate: Date?
    let existingDate: Date?
}

struct SaveDataStore {
    let userDir: URL
    let backupDir: URL
    let exportDir: URL
    let io: SaveDataIO
    let limits: ZipLimits

    var savedataDir: URL { userDir.appendingPathComponent("savedata", isDirectory: true) }
    private var stagingDir: URL { savedataDir.appendingPathComponent(".import-tmp", isDirectory: true) }

    // Files shows this folder under the app, so the player can recover from it.
    init(userDir: URL, backupDir: URL? = nil, exportDir: URL? = nil, io: SaveDataIO = FoundationSaveDataIO(),
         limits: ZipLimits = ZipLimits()) {
        self.userDir = userDir
        self.backupDir = backupDir ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Save Backups", isDirectory: true)
        self.exportDir = exportDir ?? FileManager.default.temporaryDirectory
        self.io = io
        self.limits = limits
    }

    func saves(forSerial serial: String) -> [SaveEntry] {
        let files = FileManager.default
        guard !serial.isEmpty, let names = try? files.contentsOfDirectory(atPath: savedataDir.path) else { return [] }
        return names
            .filter { $0.hasPrefix(serial) && !$0.hasPrefix(".") }
            .compactMap { entry(named: $0) }
            .sorted { $0.modified > $1.modified }
    }

    private func entry(named name: String) -> SaveEntry? {
        let dir = savedataDir.appendingPathComponent(name, isDirectory: true)
        let sfoURL = dir.appendingPathComponent("PARAM.SFO")
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue,
              let data = try? Data(contentsOf: sfoURL), let sfo = ParamSfo(data: data) else { return nil }
        let modified = (try? FileManager.default.attributesOfItem(atPath: sfoURL.path))?[.modificationDate] as? Date
        let icon = dir.appendingPathComponent("ICON0.PNG")
        return SaveEntry(
            dirName: name,
            title: sfo.title ?? name,
            subtitle: sfo.subtitle ?? "",
            detail: sfo.detail ?? "",
            modified: modified ?? .distantPast,
            size: Self.size(of: dir),
            iconURL: FileManager.default.fileExists(atPath: icon.path) ? icon : nil)
    }

    static func size(of dir: URL) -> Int64 {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let walk = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: keys) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in walk {
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    // MARK: delete and export

    // A failed backup deletes nothing.
    func delete(dirName: String) throws {
        let dir = savedataDir.appendingPathComponent(dirName)
        guard Self.isValidSaveName(dirName), FileManager.default.fileExists(atPath: dir.path) else {
            throw SaveImportError.noSave
        }
        do { try io.zipDirectory(dir, to: try backupURL(for: dirName)) } catch { throw SaveImportError.backupFailed }
        do { try FileManager.default.removeItem(at: dir) } catch { throw SaveImportError.writeFailed }
    }

    // A fresh folder of its own, so two exports never collide; the caller removes it after sharing.
    func export(dirName: String, gameTitle: String) throws -> URL {
        let save = savedataDir.appendingPathComponent(dirName)
        guard Self.isValidSaveName(dirName), FileManager.default.fileExists(atPath: save.path) else {
            throw SaveImportError.noSave
        }
        let sfo = (try? Data(contentsOf: save.appendingPathComponent("PARAM.SFO"))).flatMap(ParamSfo.init(data:))
        let part = sfo?.subtitle.flatMap { $0.isEmpty ? nil : $0 } ?? dirName
        let name = Self.fileSafe("\(gameTitle) - \(part)") + ".zip"
        let folder = exportDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try io.zipDirectory(save, to: folder.appendingPathComponent(name))
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw SaveImportError.writeFailed
        }
        return folder.appendingPathComponent(name)
    }

    static func fileSafe(_ name: String) -> String {
        String(name.map { "/\\:*?\"<>|".contains($0) ? "_" : $0 })
    }

    // MARK: import

    func prepareImport(from source: URL) throws -> StagedImport {
        let files = FileManager.default
        do {
            try files.createDirectory(at: savedataDir, withIntermediateDirectories: true)
            clearLeftovers()
            try files.createDirectory(at: stagingDir, withIntermediateDirectories: true)
        } catch {
            throw SaveImportError.writeFailed
        }
        do {
            var budget = limits
            if (try? source.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                try copyTree(source, to: stagingDir.appendingPathComponent(source.lastPathComponent), limits: &budget)
            } else {
                try extractZip(source, limits: &budget)
            }
            guard let folder = findSave(in: stagingDir, depth: 0) else { throw SaveImportError.noSave }
            guard let data = try? Data(contentsOf: folder.appendingPathComponent("PARAM.SFO")),
                  let sfo = ParamSfo(data: data) else { throw SaveImportError.unreadableSfo }
            let name = sfo.savedataDirectory.flatMap { $0.isEmpty ? nil : $0 } ?? folder.lastPathComponent
            guard Self.isValidSaveName(name) else { throw SaveImportError.badName }
            guard !Self.looksEncrypted(folder) else { throw SaveImportError.encrypted }
            let existing = savedataDir.appendingPathComponent(name)
            return StagedImport(
                dirName: name,
                folder: folder,
                incomingDate: Self.sfoDate(folder),
                existingDate: files.fileExists(atPath: existing.path) ? Self.sfoDate(existing) ?? .distantPast : nil)
        } catch {
            try? files.removeItem(at: stagingDir)
            if let refusal = error as? SaveImportError { throw refusal }
            if let zip = error as? ZipError { throw SaveImportError(zip) }
            throw SaveImportError.unreadable
        }
    }

    // Not replacing an existing save throws `.exists` and keeps the staging, so the caller can ask and commit again.
    @discardableResult
    func commit(_ staged: StagedImport, replacing: Bool) throws -> Bool {
        let files = FileManager.default
        let target = savedataDir.appendingPathComponent(staged.dirName)
        guard files.fileExists(atPath: target.path) else {
            do { try files.moveItem(at: staged.folder, to: target) } catch { throw SaveImportError.writeFailed }
            try? files.removeItem(at: stagingDir)
            return false
        }
        guard replacing else { throw SaveImportError.exists }
        do { try io.zipDirectory(target, to: try backupURL(for: staged.dirName)) } catch { throw SaveImportError.backupFailed }
        let old = savedataDir.appendingPathComponent(".\(staged.dirName).old")
        try? files.removeItem(at: old)
        do { try files.moveItem(at: target, to: old) } catch { throw SaveImportError.writeFailed }
        do {
            try files.moveItem(at: staged.folder, to: target)
        } catch {
            try? files.moveItem(at: old, to: target)
            throw SaveImportError.writeFailed
        }
        try? files.removeItem(at: old)
        try? files.removeItem(at: stagingDir)
        return true
    }

    func discard() {
        try? FileManager.default.removeItem(at: stagingDir)
    }

    private func backupURL(for name: String) throws -> URL {
        let stamp = DateFormatter()
        stamp.locale = Locale(identifier: "en_US_POSIX")
        stamp.dateFormat = "yyyy-MM-dd HHmmss"
        try FileManager.default.createDirectory(at: backupDir, withIntermediateDirectories: true)
        return backupDir.appendingPathComponent("\(name) \(stamp.string(from: Date())).zip")
    }

    // `.<name>.old` is put back when `<name>` is missing, because the kill fell between the two renames.
    private func clearLeftovers() {
        let files = FileManager.default
        try? files.removeItem(at: stagingDir)
        for name in (try? files.contentsOfDirectory(atPath: savedataDir.path)) ?? []
        where name.hasPrefix(".") && name.hasSuffix(".old") && name.count > 5 {
            let live = savedataDir.appendingPathComponent(String(name.dropFirst().dropLast(4)))
            let old = savedataDir.appendingPathComponent(name)
            if files.fileExists(atPath: live.path) {
                try? files.removeItem(at: old)
            } else {
                try? files.moveItem(at: old, to: live)
            }
        }
    }

    // A symlink anywhere refuses the whole import.
    private func copyTree(_ source: URL, to destination: URL, limits: inout ZipLimits) throws {
        let files = FileManager.default
        let keys: Set<URLResourceKey> = [.isSymbolicLinkKey, .isDirectoryKey, .fileSizeKey]
        let values = try source.resourceValues(forKeys: keys)
        if values.isSymbolicLink == true { throw SaveImportError.symlink }
        if values.isDirectory == true {
            try files.createDirectory(at: destination, withIntermediateDirectories: true)
            for child in try files.contentsOfDirectory(at: source, includingPropertiesForKeys: Array(keys)) {
                try copyTree(child, to: destination.appendingPathComponent(child.lastPathComponent), limits: &limits)
            }
        } else {
            try limits.admitEntry()
            try limits.admit(Int64(values.fileSize ?? 0), entrySoFar: 0)
            try files.copyItem(at: source, to: destination)
        }
    }

    private func extractZip(_ source: URL, limits: inout ZipLimits) throws {
        let archive = try ZipArchive(url: source)
        for entry in archive.entries {
            guard let relative = Self.safeRelativePath(entry.name) else { throw SaveImportError.unsafeEntry }
            if relative.isEmpty { continue }
            let target = stagingDir.appendingPathComponent(relative)
            // Whatever the name held, the joined path must still be inside staging.
            guard Self.isInside(target, stagingDir) else {
                throw SaveImportError.unsafeEntry
            }
            if entry.isDirectory {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            } else {
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try archive.extract(entry, to: target, limits: &limits)
            }
        }
    }

    // Android's safeRelativePath. Nil means refuse; "" means nothing to write.
    static func safeRelativePath(_ name: String) -> String? {
        // Control characters go first so `..\u{1}` is seen as `..`; a scalar split stops a combining mark hiding a `..`.
        let parts = name.unicodeScalars.filter { $0.value >= 0x20 }.split { $0 == "/" || $0 == "\\" }
            .map { String(String.UnicodeScalarView($0)).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && $0 != "." }
        if parts.contains("..") { return nil }
        let kept = parts.suffix(3)
        if kept.contains(where: \.isEmpty) { return nil }
        return kept.joined(separator: "/")
    }

    // Lexical on both sides, because Foundation drops /private only from a path that exists; staging holds no symlinks.
    static func isInside(_ target: URL, _ base: URL) -> Bool {
        // `..` past the root removes the root, so it never matches.
        func resolved(_ url: URL) -> [String] {
            var kept: [String] = []
            for part in url.pathComponents where part != "." {
                if part == ".." { _ = kept.popLast() } else { kept.append(part) }
            }
            return kept
        }
        let inner = resolved(target), outer = resolved(base)
        return inner.count > outer.count && Array(inner.prefix(outer.count)) == outer
    }

    // Nil when the path is empty, has the wrong suffix or would land outside `root`.
    static func containedFile(_ relative: String, in root: URL, suffix: String) -> URL? {
        guard let clean = safeFullRelativePath(relative), clean.lowercased().hasSuffix(suffix) else { return nil }
        let url = root.appendingPathComponent(clean)
        return isInside(url, root) ? url : nil
    }

    static func safeFullRelativePath(_ name: String) -> String? {
        let parts = name.unicodeScalars.filter { $0.value >= 0x20 }.split { $0 == "/" || $0 == "\\" }
            .map { String(String.UnicodeScalarView($0)).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && $0 != "." }
        if parts.contains("..") { return nil }
        return parts.joined(separator: "/")
    }

    // Each entry's path is cleaned and must land inside its folder, or the whole extraction throws.
    static func extractTree(_ archive: ZipArchive, limits: inout ZipLimits,
                            map: (String) -> (root: URL, relative: String)?) throws -> (written: Int, skipped: Int) {
        var written = 0, skipped = 0
        for entry in archive.entries {
            guard let destination = map(entry.name) else {
                skipped += 1
                continue
            }
            guard let relative = safeFullRelativePath(destination.relative) else { throw SaveImportError.unsafeEntry }
            if relative.isEmpty { continue }
            let target = destination.root.appendingPathComponent(relative)
            guard isInside(target, destination.root) else { throw SaveImportError.unsafeEntry }
            if entry.isDirectory {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            } else {
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try archive.extract(entry, to: target, limits: &limits)
                written += 1
            }
        }
        return (written, skipped)
    }

    // A save has no nested saves.
    private func findSave(in dir: URL, depth: Int) -> URL? {
        guard depth <= 6 else { return nil }
        let files = FileManager.default
        if files.fileExists(atPath: dir.appendingPathComponent("PARAM.SFO").path) { return dir }
        let children = ((try? files.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey])) ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        for child in children {
            if let found = findSave(in: child, depth: depth + 1) { return found }
        }
        return nil
    }

    // The core's rule, sysutil_check_name_string with CELL_SAVEDATA_DIRNAME_SIZE.
    static func isValidSaveName(_ name: String) -> Bool {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
        return (1...31).contains(name.count) && !name.hasPrefix("_") && name.allSatisfy { allowed.contains($0) }
    }

    // Both of Android's signals are required: a decrypted save keeps its PFD, and compressed data is high-entropy too.
    static func looksEncrypted(_ dir: URL) -> Bool {
        let files = FileManager.default
        guard files.fileExists(atPath: dir.appendingPathComponent("PARAM.PFD").path) else { return false }
        let skip: Set<String> = ["ICON0.PNG", "ICON1.PAM", "PIC1.PNG", "SND0.AT3", "PARAM.SFO", "PARAM.PFD"]
        let candidates = ((try? files.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? [])
            .filter { !skip.contains($0.lastPathComponent.uppercased()) }
        return candidates.contains { url in
            guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
            defer { try? handle.close() }
            guard let head = try? handle.read(upToCount: 4096), head.count == 4096 else { return false }
            var seen = Set<UInt8>()
            var run = 0, longest = 0
            var previous: UInt8?
            for byte in head {
                seen.insert(byte)
                run = byte == previous ? run + 1 : 1
                longest = max(longest, run)
                previous = byte
            }
            return seen.count >= 250 && longest < 5
        }
    }

    private static func sfoDate(_ dir: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: dir.appendingPathComponent("PARAM.SFO").path))?[.modificationDate] as? Date
    }
}
