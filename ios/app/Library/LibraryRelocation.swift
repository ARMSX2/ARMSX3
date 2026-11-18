// SPDX-License-Identifier: GPL-2.0-only
// A game folder picked from a temporary place (tmp, an Inbox) is renamed into Documents/games, since those vanish.

import Foundation

enum LibraryRelocation {
    struct Roots {
        let home: URL
        let temporary: URL
        let games: URL

        static var current: Roots {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return Roots(home: URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true),
                         temporary: FileManager.default.temporaryDirectory,
                         games: documents.appendingPathComponent("games", isDirectory: true))
        }
    }

    enum Outcome: Equatable { case kept, moved(URL), failed(Int32) }

    // realpath(3), or the parent's plus the last component when it does not resolve, so both sides spell /private/var.
    static func canonical(_ path: String) -> String {
        if let resolved = realpath(path, nil) {
            defer { free(resolved) }
            return String(cString: resolved)
        }
        let parent = (path as NSString).deletingLastPathComponent
        guard !parent.isEmpty, parent != path else { return path }
        return (canonical(parent) as NSString).appendingPathComponent((path as NSString).lastPathComponent)
    }

    private static func components(_ path: String) -> [String] { path.split(separator: "/").map(String.init) }

    // A `*-Inbox` folder counts only directly inside tmp, so a user's own "Mail-Inbox" in Files is never moved.
    private static func rootLength(_ parts: [String], roots: Roots) -> Int? {
        let home = components(canonical(roots.home.path))
        let strict = [components(canonical(roots.temporary.path)), home + ["tmp"]]
        var inclusive = [home + ["Inbox"], home + ["Documents", "Inbox"]]
        for i in parts.indices.dropLast() where parts[i] == "tmp" && parts[i + 1].hasSuffix("-Inbox") {
            inclusive.append(Array(parts[...(i + 1)]))
        }
        var best: Int?
        for root in strict where parts.count > root.count && Array(parts.prefix(root.count)) == root {
            best = max(best ?? 0, root.count)
        }
        for root in inclusive where parts.count >= root.count && Array(parts.prefix(root.count)) == root {
            best = max(best ?? 0, root.count)
        }
        return best
    }

    static func isTemporary(_ url: URL, roots: Roots = .current) -> Bool {
        rootLength(components(canonical(url.path)), roots: roots) != nil
    }

    static func item(containing path: String, roots: Roots = .current) -> URL? {
        let parts = components(canonical(path))
        guard let root = rootLength(parts, roots: roots), parts.count > root else { return nil }
        return URL(fileURLWithPath: "/" + parts[...root].joined(separator: "/"))
    }

    /// Nothing is copied, overwritten or deleted: a failed rename leaves the item where it is.
    static func relocate(_ url: URL, roots: Roots = .current,
                         renamer: (String, String) -> Int32 = posixRename) -> Outcome {
        let source = canonical(url.path)
        let parts = components(source)
        guard let root = rootLength(parts, roots: roots) else { return .kept }
        var isDirectory: ObjCBool = false
        let isFile = FileManager.default.fileExists(atPath: source, isDirectory: &isDirectory) && !isDirectory.boolValue

        do {
            try FileManager.default.createDirectory(at: roots.games, withIntermediateDirectories: true)
        } catch {
            return .failed(((error as NSError).userInfo[NSUnderlyingErrorKey] as? NSError).map { Int32($0.code) } ?? EIO)
        }
        let games = canonical(roots.games.path)
        let last = (source as NSString).lastPathComponent
        let base = isFile ? (last as NSString).deletingPathExtension : last
        let candidates = [base] + (2...99).map { "\(base) \($0)" }

        if isFile {
            // A lone image goes into a fresh wrapper folder, so the library registers a folder as it does for any game.
            for candidate in candidates {
                let wrapper = (games as NSString).appendingPathComponent(candidate)
                if mkdir(wrapper, 0o755) != 0 {
                    let code = errno
                    if code == EEXIST { continue }
                    return .failed(code)
                }
                let code = renamer(source, (wrapper as NSString).appendingPathComponent(last))
                if code == 0 { return .moved(URL(fileURLWithPath: wrapper, isDirectory: true)) }
                rmdir(wrapper)  // only ever the empty wrapper made just above
                return .failed(code)
            }
            return .failed(EEXIST)
        }

        for candidate in candidates {
            let target = (games as NSString).appendingPathComponent(candidate)
            var info = stat()
            if lstat(target, &info) == 0 { continue }  // an existing name is never overwritten
            let code = renamer(source, target)
            if code == 0 {
                // The Inbox root itself moved: the system's or LiveContainer's next picker copy expects it.
                if parts.count == root { mkdir(source, 0o755) }
                return .moved(URL(fileURLWithPath: target, isDirectory: true))
            }
            if code == EEXIST || code == ENOTEMPTY || code == ENOTDIR { continue }
            return .failed(code)
        }
        return .failed(EEXIST)
    }

    /// Never FileManager's move: it falls back to copying across volumes, a multi-GB copy.
    static func posixRename(_ from: String, _ to: String) -> Int32 {
        rename(from, to) == 0 ? 0 : errno
    }

    /// The refusal shown instead of booting an ISO whose image is no longer there; nil otherwise.
    static func goneMessage(bootPath: String, isISO: Bool) -> String? {
        guard isISO, !FileManager.default.fileExists(atPath: bootPath) else { return nil }
        let file = (bootPath as NSString).lastPathComponent
        return String(localized: "The disc image is gone. \(file) is no longer where it was added from. Add the game again with Add Game Folder.")
    }
}
