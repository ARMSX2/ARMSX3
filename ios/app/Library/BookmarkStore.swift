// SPDX-License-Identifier: GPL-2.0-only
// Game folders as security-scoped bookmarks, held open for the process because RPCS3 reads by POSIX path all session.

import Foundation

protocol BookmarkIO {
    func makeBookmark(_ url: URL) throws -> Data
    func resolve(_ data: Data) throws -> (url: URL, stale: Bool)
    func startAccess(_ url: URL) -> Bool
    func stopAccess(_ url: URL)
    func isReadable(_ url: URL) -> Bool
}

struct FoundationBookmarkIO: BookmarkIO {
    func makeBookmark(_ url: URL) throws -> Data {
        try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    func resolve(_ data: Data) throws -> (url: URL, stale: Bool) {
        var stale = false
        let url = try URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        return (url, stale)
    }

    func startAccess(_ url: URL) -> Bool { url.startAccessingSecurityScopedResource() }

    func stopAccess(_ url: URL) { url.stopAccessingSecurityScopedResource() }

    func isReadable(_ url: URL) -> Bool { FileManager.default.isReadableFile(atPath: url.path) }
}

@MainActor
final class BookmarkStore {
    struct Folder: Codable, Identifiable {
        var id: UUID
        var displayName: String
        var bookmark: Data
    }

    private(set) var folders: [Folder]
    private let fileURL: URL
    private let directory: URL
    private let io: BookmarkIO
    /// Folders whose access this process started. A false startAccess on a readable path needs no access at all.
    private var held: [UUID: URL] = [:]

    init(directory: URL, io: BookmarkIO = FoundationBookmarkIO()) {
        self.directory = directory
        self.fileURL = directory.appendingPathComponent("folders.plist")
        self.io = io
        // An unreadable file reads as no folders: the user picks again, nothing is half-trusted.
        let data = try? Data(contentsOf: fileURL)
        folders = data.flatMap { try? PropertyListDecoder().decode([Folder].self, from: $0) } ?? []
    }

    func add(_ url: URL) throws -> Folder {
        let started = io.startAccess(url)
        let folder: Folder
        do {
            folder = Folder(id: UUID(), displayName: url.lastPathComponent, bookmark: try io.makeBookmark(url))
            folders.append(folder)
            do { try persist() } catch { folders.removeLast(); throw error }
        } catch {
            // Nothing was remembered, so nothing may stay open.
            if started { io.stopAccess(url) }
            throw error
        }
        if started { held[folder.id] = url }
        return folder
    }

    /// nil means unavailable (moved, deleted, the drive unplugged). One failure never stops the others resolving.
    func resolveAll() -> [(Folder, URL?)] {
        var changed = false
        let result: [(Folder, URL?)] = folders.indices.map { index in
            let folder = folders[index]
            if let url = held[folder.id] { return (folder, url) }
            guard let (url, stale) = try? io.resolve(folder.bookmark) else { return (folder, nil) }
            let started = io.startAccess(url)
            guard started || io.isReadable(url) else { return (folder, nil) }
            if started { held[folder.id] = url }
            if stale, let fresh = try? io.makeBookmark(url) {
                folders[index].bookmark = fresh
                changed = true
            }
            return (folders[index], url)
        }
        // shortcut: a failed re-save keeps the stale bytes, which still resolve; retried next launch.
        if changed { try? persist() }
        return result
    }

    func remove(_ id: UUID) {
        if let url = held.removeValue(forKey: id) { io.stopAccess(url) }
        folders.removeAll { $0.id == id }
        try? persist()
    }

    private func persist() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try PropertyListEncoder().encode(folders).write(to: fileURL, options: .atomic)
    }
}
