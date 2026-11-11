// SPDX-License-Identifier: GPL-2.0-only
// The Files app moves what a player deletes into Documents/.Trash, where it keeps its space until the app clears it.

import Foundation

enum DocumentsTrash {
    /// Removes everything inside `documents/.Trash` and returns the bytes freed. A link is removed, never followed.
    static func empty(documents: URL) -> Int64 {
        let trash = documents.appendingPathComponent(".Trash", isDirectory: true)
        var info = stat()
        guard lstat(trash.path, &info) == 0, (info.st_mode & S_IFMT) == S_IFDIR,
              let children = try? FileManager.default.contentsOfDirectory(atPath: trash.path) else { return 0 }
        var freed: Int64 = 0
        for name in children {
            let child = trash.appendingPathComponent(name)
            let size = bytes(at: child.path)
            if (try? FileManager.default.removeItem(at: child)) != nil { freed += size }
        }
        return freed
    }

    /// Sizes of the regular files at or under `path`, without following links.
    private static func bytes(at path: String) -> Int64 {
        var info = stat()
        guard lstat(path, &info) == 0 else { return 0 }
        switch info.st_mode & S_IFMT {
        case S_IFREG:
            return Int64(info.st_size)
        case S_IFDIR:
            let names = (try? FileManager.default.contentsOfDirectory(atPath: path)) ?? []
            return names.reduce(0) { $0 + bytes(at: (path as NSString).appendingPathComponent($1)) }
        default:
            return 0
        }
    }
}
