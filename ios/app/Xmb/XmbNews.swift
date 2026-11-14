// SPDX-License-Identifier: GPL-2.0-only
// ARMSX3's release notes from GitHub for the XMB's What's new, ported from Android's News.kt and XmbNews in XmbInfo.kt.

import Foundation
import Observation

/// What's new: ARMSX3's releases, newest first. Nil until the first read, empty when nothing could be read.
@MainActor @Observable final class XmbNews {
    static let shared = XmbNews()

    struct Item: Equatable {
        let tag: String
        let title: String
        /// The notes in reading order, their pictures between the text.
        let blocks: [Block]
        /// yyyy-MM-dd.
        let published: String
        let prerelease: Bool
        let authorLogin: String
        let authorAvatar: URL?
        /// The GitHub logins the notes mention, which is who the release credits.
        let credited: [String]
    }

    enum Block: Equatable {
        case text(String)
        /// Width over height, from the size GitHub records, so the panel makes room before the picture arrives.
        case image(URL, alt: String, aspect: Double)
    }

    /// A GitHub user's picture, which github.com serves by login without an API call.
    nonisolated static func avatar(of login: String) -> URL? {
        URL(string: "https://github.com/\(login).png?size=96")
    }

    private(set) var items: [Item]?
    @ObservationIgnored private var wasOpen = false
    @ObservationIgnored private var fetching = false

    nonisolated static let releasesURL = URL(string: "https://api.github.com/repos/ARMSX2/ARMSX3/releases?per_page=20")!
    nonisolated static let cacheLifetime: TimeInterval = 6 * 60 * 60
    nonisolated static let maxBodyBytes = 512 * 1024

    /// Called with whether the folder is open. Each opening reads again while there is nothing, so a failed fetch
    /// is not kept for the run.
    func folderOpen(_ open: Bool) {
        defer { wasOpen = open }
        guard open, !wasOpen, !fetching, items?.isEmpty ?? true else { return }
        fetching = true
        Task {
            items = await Task.detached(priority: .utility) { await Self.fetch() }.value ?? []
            fetching = false
        }
    }

    // MARK: - Reading the feed

    nonisolated private static var cacheFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("news", isDirectory: true).appendingPathComponent("releases.json")
    }

    /// A fresh cache, else the network, else whatever cache there is: stale notes beat none. Nil with neither.
    nonisolated static func fetch() async -> [Item]? {
        let cache = cacheFile
        if let modified = (try? cache.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate,
           Date().timeIntervalSince(modified) < cacheLifetime, let items = parse(try? Data(contentsOf: cache)) {
            return items
        }
        if let body = await download(), let items = parse(body) {
            try? FileManager.default.createDirectory(at: cache.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? body.write(to: cache, options: .atomic)
            return items
        }
        return parse(try? Data(contentsOf: cache))
    }

    nonisolated private static func download() async -> Data? {
        var request = URLRequest(url: releasesURL, timeoutInterval: 20)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
        request.setValue("ARMSX3/" + version, forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200, data.count <= maxBodyBytes else { return nil }
        return data
    }

    /// Drafts and releases without a tag are skipped. Nil when the body is not a JSON array.
    nonisolated static func parse(_ body: Data?) -> [Item]? {
        guard let body, !body.isEmpty,
              let releases = (try? JSONSerialization.jsonObject(with: body)) as? [Any] else { return nil }
        return releases.compactMap { entry -> Item? in
            guard let release = entry as? [String: Any], release["draft"] as? Bool != true else { return nil }
            let text = { (key: String) in ((release[key] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
            let tag = text("tag_name")
            guard !tag.isEmpty else { return nil }
            let name = text("name")
            let notes = release["body"] as? String ?? ""
            let author = release["author"] as? [String: Any]
            return Item(tag: tag, title: name.isEmpty ? tag : name, blocks: blocks(notes),
                        published: String(((release["published_at"] as? String) ?? "").prefix(10)),
                        prerelease: release["prerelease"] as? Bool ?? false,
                        authorLogin: author?["login"] as? String ?? "", authorAvatar: https(author?["avatar_url"] as? String),
                        credited: credits(notes))
        }
    }

    /// The few markdown forms release notes use, flattened to text. Anything else is left as written.
    nonisolated static func tidy(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map { line in
                line.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
                    .replacingOccurrences(of: "^#{1,6}\\s*", with: "", options: .regularExpression)
                    .replacingOccurrences(of: "^\\s*[-*]\\s+", with: "• ", options: .regularExpression)
                    .replacingOccurrences(of: "\\*\\*(.+?)\\*\\*", with: "$1", options: .regularExpression)
                    .replacingOccurrences(of: "`([^`]+)`", with: "$1", options: .regularExpression)
            }
            .joined(separator: "\n")
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The notes as tidied text runs with each picture between them, as GitHub stores a pasted one (`<img>`) or markdown
    /// writes it (`![alt](url)`). A picture that is not https is dropped.
    nonisolated static func blocks(_ raw: String) -> [Block] {
        guard let picture = try? NSRegularExpression(pattern: "<img\\b[^>]*>|!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)",
                                                     options: .caseInsensitive) else { return [] }
        var blocks: [Block] = []
        var position = raw.startIndex
        func addText(_ upTo: String.Index) {
            let text = tidy(String(raw[position..<upTo]))
            if !text.isEmpty { blocks.append(.text(text)) }
        }
        for match in picture.matches(in: raw, range: NSRange(raw.startIndex..., in: raw)) {
            guard let range = Range(match.range, in: raw) else { continue }
            addText(range.lowerBound)
            position = range.upperBound
            let tag = String(raw[range])
            let group = { (n: Int) in Range(match.range(at: n), in: raw).map { String(raw[$0]) } }
            let markdown = tag.hasPrefix("!")
            guard let url = https(markdown ? group(2) : attribute("src", of: tag)) else { continue }
            let width = attribute("width", of: tag).flatMap(Double.init), height = attribute("height", of: tag).flatMap(Double.init)
            let aspect = if let width, let height, width > 0, height > 0 { width / height } else { 16.0 / 9 }
            blocks.append(.image(url, alt: (markdown ? group(1) : attribute("alt", of: tag)) ?? "", aspect: min(max(aspect, 0.25), 4)))
        }
        addText(raw.endIndex)
        return blocks
    }

    nonisolated private static func attribute(_ name: String, of tag: String) -> String? {
        guard let found = tag.range(of: "\\b\(name)\\s*=\\s*[\"'][^\"']*[\"']", options: [.regularExpression, .caseInsensitive])
        else { return nil }
        let value = tag[found].drop { $0 != "\"" && $0 != "'" }
        return String(value.dropFirst().dropLast())
    }

    /// Only https, which is all GitHub writes; nothing else in a release is fetched.
    nonisolated private static func https(_ text: String?) -> URL? {
        guard let text, text.lowercased().hasPrefix("https://") else { return nil }
        return URL(string: text)
    }

    /// GitHub logins mentioned with @, in order and once each. The leading class keeps emails and URLs out.
    nonisolated static func credits(_ notes: String) -> [String] {
        guard let mention = try? NSRegularExpression(pattern: "(^|[^\\w@/])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)") else { return [] }
        var seen: [String] = []
        for match in mention.matches(in: notes, range: NSRange(notes.startIndex..., in: notes)) {
            guard let range = Range(match.range(at: 2), in: notes) else { continue }
            let login = String(notes[range])
            if !seen.contains(login) { seen.append(login) }
        }
        return seen
    }
}
