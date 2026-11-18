// SPDX-License-Identifier: GPL-2.0-only
// What Delete Game removes: a game's folder or disc image inside the app's own games folder, and nothing else.

import Foundation

enum GameDeletion {
    /// The folder or image to delete, or nil when the game lives outside `games` or the path is a link.
    static func removable(bootPath: String, isISO: Bool, games: URL) -> URL? {
        let root = LibraryRelocation.canonical(games.path)
        var path = bootPath
        // A disc folder is booted from its PS3_GAME folder; the game's folder is the one around it.
        if !isISO, (path as NSString).lastPathComponent == "PS3_GAME" { path = (path as NSString).deletingLastPathComponent }
        var info = stat()
        guard lstat(path, &info) == 0, (info.st_mode & S_IFMT) != S_IFLNK else { return nil }
        let resolved = LibraryRelocation.canonical(path)
        guard resolved.hasPrefix(root + "/"), resolved.count > root.count + 1 else { return nil }
        return URL(fileURLWithPath: resolved, isDirectory: !isISO)
    }

    static func size(of item: URL) -> Int64 {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        if item.hasDirectoryPath == false {
            return Int64((try? item.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        guard let walk = FileManager.default.enumerator(at: item, includingPropertiesForKeys: keys) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in walk {
            guard let values = try? file.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    /// Removes the item; a disc image's wrapper folder goes too once nothing else is in it.
    static func remove(_ item: URL, games: URL) throws {
        try FileManager.default.removeItem(at: item)
        let wrapper = item.deletingLastPathComponent()
        let root = LibraryRelocation.canonical(games.path)
        guard LibraryRelocation.canonical(wrapper.path) != root,
              (try? FileManager.default.contentsOfDirectory(atPath: wrapper.path))?.isEmpty == true else { return }
        try? FileManager.default.removeItem(at: wrapper)
    }
}
