// SPDX-License-Identifier: GPL-2.0-only
// The last scan on disk. Bump `schema` whenever the scan reports a new field, or old caches answer without it.

import Foundation

struct LibraryCache {
    static let schema = 3

    struct Entry: Codable, Equatable {
        var serial: String
        var title: String
        var category: String
        var bootPath: String
        var iconPath: String?
        var backdropPath: String?
        var isISO: Bool
        var fromGamesYml: Bool
        // true when the EBOOT needs a licence a RAP would unlock; nil when never probed.
        var locked: Bool?
        // true when the EBOOT does not decrypt and no licence would help; nil when never probed.
        var unreadable: Bool?
    }

    private struct File: Codable {
        var schema: Int
        var key: String
        var entries: [Entry]
    }

    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    var fileURL: URL { directory.appendingPathComponent("library-cache.json") }

    static func key(folderIDs: [UUID]) -> String {
        "v\(schema)|" + folderIDs.map(\.uuidString).sorted().joined(separator: "|")
    }

    func load(expectedKey: String) -> [Entry]? {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(File.self, from: data),
              file.schema == Self.schema, file.key == expectedKey else { return nil }
        return file.entries
    }

    func save(key: String, entries: [Entry]) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(File(schema: Self.schema, key: key, entries: entries))
        try data.write(to: fileURL, options: .atomic)
    }
}
