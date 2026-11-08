// SPDX-License-Identifier: GPL-2.0-only
// Figure folders. The core writes into a loaded figure, so a pick is copied in first and the copy is loaded.

import Foundation

enum PortalStore {
    struct Portal: Identifiable, Equatable {
        let id: Int
        let name: String
        let folderName: String
        let fileBytes: Int
        let slots: Int
    }

    // The core's portal numbers (the façade's +portalLoad), desktop's dialogs' slot counts, and the files' exact sizes.
    static let portals = [
        Portal(id: 0, name: "Skylanders", folderName: "Skylanders", fileBytes: 1024, slots: 8),
        Portal(id: 1, name: "Disney Infinity", folderName: "Infinity", fileBytes: 320, slots: 9),
        Portal(id: 2, name: "Lego Dimensions", folderName: "Dimensions", fileBytes: 180, slots: 7),
        Portal(id: 3, name: "Kamen Rider", folderName: "KamenRider", fileBytes: 320, slots: 8),
    ]

    static func folder(_ p: Portal, documents: URL) -> URL {
        documents.appendingPathComponent("Figures", isDirectory: true)
            .appendingPathComponent(p.folderName, isDirectory: true)
    }

    // A file already in the portal's folder is the app's own copy and is handed back as it is, so it adds no duplicate.
    static func copyIn(_ source: URL, portal: Portal, documents: URL) -> URL? {
        let manager = FileManager.default
        guard let values = try? source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, values.fileSize == portal.fileBytes else { return nil }
        let folder = folder(portal, documents: documents)
        if source.resolvingSymlinksInPath().deletingLastPathComponent().path == folder.resolvingSymlinksInPath().path {
            return source
        }
        let ext = source.pathExtension.isEmpty ? "bin" : source.pathExtension
        guard let target = freeFile(source.deletingPathExtension().lastPathComponent, ext: ext, portal: portal,
                                    documents: documents) else { return nil }
        return (try? manager.copyItem(at: source, to: target)) != nil ? target : nil
    }

    // `/` and `:` in `base` become `-`, so a name is always one path component. Nothing is written to the file.
    static func freeFile(_ base: String, ext: String, portal: Portal, documents: URL) -> URL? {
        let manager = FileManager.default
        let folder = folder(portal, documents: documents)
        guard (try? manager.createDirectory(at: folder, withIntermediateDirectories: true)) != nil else { return nil }
        let cleaned = base.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
        let stem = cleaned.isEmpty ? "figure" : cleaned
        var target = folder.appendingPathComponent(stem).appendingPathExtension(ext)
        var number = 1
        while manager.fileExists(atPath: target.path) {
            number += 1
            target = folder.appendingPathComponent("\(stem) \(number)").appendingPathExtension(ext)
        }
        return target
    }
}
