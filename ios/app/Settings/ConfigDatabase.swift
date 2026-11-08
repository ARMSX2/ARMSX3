// SPDX-License-Identifier: GPL-2.0-only
// RPCS3's per-title config database, fetched at most weekly off the main thread; a failure keeps the last good copy.

import Foundation

enum ConfigDatabase {
    static let source = URL(string: "https://api.rpcs3.net/config/?api=v1")!
    static let maxAge: TimeInterval = 7 * 24 * 60 * 60

    // The path the bridge derives from HOME, so the two must agree.
    static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("rpcs3-config-db.json", isDirectory: false)
    }

    /// Returns at once; the fetch and the write run off the main thread.
    static func refreshIfStale() {
        Task.detached(priority: .utility) {
            _ = await ConfigDatabase.refresh()
        }
    }

    static func refresh(from source: URL = ConfigDatabase.source, to file: URL = ConfigDatabase.file,
                        now: Date = Date()) async -> String {
        let files = FileManager.default
        let modified = (try? files.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date
        if let modified, now.timeIntervalSince(modified) < maxAge {
            return "cached"
        }
        let kept = modified == nil ? "none" : "failed"

        var request = URLRequest(url: source, timeoutInterval: 30)
        // The API rejects requests without one.
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (body, response) = try? await URLSession.shared.data(for: request) else { return kept }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return kept }
        guard isDatabase(body) else { return kept }

        do {
            try files.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try body.write(to: file, options: .atomic)
        } catch {
            return kept
        }
        // After the write: the atomic rename replaces the file, and with it any earlier flag.
        var written = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? written.setResourceValues(values)
        return "fetched"
    }

    // The shape title_config.h reads: a non-negative return_code and a `games` object.
    static func isDatabase(_ body: Data) -> Bool {
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return false }
        return ((json["return_code"] as? Int) ?? -1) >= 0 && json["games"] is [String: Any]
    }
}
