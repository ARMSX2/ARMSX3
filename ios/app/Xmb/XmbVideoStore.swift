// SPDX-License-Identifier: GPL-2.0-only
// The Video column's videos: where they are, their frames and lengths, and where each was left, ported from XmbVideos in Android's XmbVideo.kt.

import AVFoundation
import CryptoKit
import Observation
import UIKit

/// The videos come from ARMSX3's video folder (Documents/Videos, filled by Import Video) and from folders added with
/// Add Folder, kept by bookmark so no permission is asked again. A file is known everywhere by its key: its path under
/// the video folder, or the added folder's id and its path under it, which stay the same when iOS moves the container.
@MainActor @Observable final class XmbVideos {
    static let shared = XmbVideos()

    /// A file or a folder in one of the listed places.
    struct Entry: Identifiable, Equatable, Sendable {
        let name: String
        let url: URL
        let key: String
        let dir: Bool
        let modified: Date
        /// In ARMSX3's own folder, so Delete is offered.
        let own: Bool

        var id: String { key }
        var title: String { dir ? name : (name as NSString).deletingPathExtension }
    }

    /// A video's length and a frame of it, once read.
    struct Meta: Equatable, Sendable {
        let durationMs: Int
        let thumb: String?
    }

    /// A folder added with Add Folder.
    struct Folder: Codable, Equatable, Identifiable {
        let id: String
        let name: String
        var bookmark: Data
    }

    /// What the Files picker is open for.
    enum Pick: Equatable { case videos, folder }

    private(set) var folders: [Folder] = []
    private(set) var listings: [String: [Entry]] = [:]
    private(set) var metas: [String: Meta] = [:]
    /// Where each video was left, in ms.
    private(set) var resume: [String: Int] = [:]
    /// Goes up when every folder is to be read again.
    private(set) var generation = 0

    @ObservationIgnored private var resumeOrder: [String] = []
    @ObservationIgnored private var loading: Set<String> = []
    @ObservationIgnored private var stale: Set<String> = []
    @ObservationIgnored private var opened: [String: URL] = [:]

    private enum Keys {
        static let folders = "xmb.video.folders"
        static let resume = "xmb.video.resume"
        static let screenMode = "xmb.video.screen"
        static let aspect = "xmb.video.aspect"
    }

    /// The ones of Android's list that AVFoundation opens. Android's mkv, webm, ts, m2ts, mts, mpg, mpeg, avi, divx, xvid and
    /// vob are left out, as the player would only refuse them.
    nonisolated static let formats: Set<String> = ["mp4", "m4v", "mov", "3gp", "3g2"]

    nonisolated static var ownFolder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Videos", isDirectory: true)
    }

    nonisolated private static var thumbs: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("xmb-video", isDirectory: true)
    }

    private init() {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: Keys.folders), let saved = try? JSONDecoder().decode([Folder].self, from: data) {
            folders = saved
        }
        for folder in folders { open(folder) }
        if let data = defaults.data(forKey: Keys.resume), let marks = try? JSONDecoder().decode([[String]].self, from: data) {
            for mark in marks where mark.count == 2 {
                guard let ms = Int(mark[1]) else { continue }
                resume[mark[0]] = ms
                resumeOrder.append(mark[0])
            }
        }
    }

    // MARK: - Places

    /// The listing key of ARMSX3's own folder, or a folder in it.
    nonisolated static func ownKey(_ relative: String = "") -> String { "own:" + relative }

    nonisolated static func folderKey(_ folder: Folder, _ relative: String = "") -> String { "folder:\(folder.id):" + relative }

    /// The added folder's place on disk for this run, opened through its bookmark.
    func url(of folder: Folder) -> URL? { opened[folder.id] }

    @discardableResult private func open(_ folder: Folder) -> URL? {
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else { return nil }
        // Kept open for the run: its videos play where they are.
        _ = url.startAccessingSecurityScopedResource()
        opened[folder.id] = url
        // A moved or renamed folder gets a fresh bookmark, as the library's folders do.
        if stale, let fresh = try? url.bookmarkData(), let index = folders.firstIndex(where: { $0.id == folder.id }) {
            folders[index].bookmark = fresh
            saveFolders()
        }
        return url
    }

    /// Keeps a folder picked in Files and lists it.
    func addFolder(_ url: URL) {
        // A saved folder whose drive was out at launch may open now, so it is found as the same folder.
        for folder in folders where opened[folder.id] == nil { open(folder) }
        let scoped = url.startAccessingSecurityScopedResource()
        guard !opened.values.contains(where: { $0.standardizedFileURL == url.standardizedFileURL }),
              let bookmark = try? url.bookmarkData() else {
            if scoped { url.stopAccessingSecurityScopedResource() }
            return
        }
        let folder = Folder(id: UUID().uuidString, name: url.lastPathComponent, bookmark: bookmark)
        opened[folder.id] = url
        folders.append(folder)
        saveFolders()
    }

    func removeFolder(_ folder: Folder) {
        opened.removeValue(forKey: folder.id)?.stopAccessingSecurityScopedResource()
        folders.removeAll { $0.id == folder.id }
        saveFolders()
        let prefix = Self.folderKey(folder)
        for key in listings.keys where key.hasPrefix(prefix) { listings[key] = nil }
    }

    private func saveFolders() {
        UserDefaults.standard.set(try? JSONEncoder().encode(folders), forKey: Keys.folders)
    }

    // MARK: - Listings

    /// A folder's folders and videos, when read; nil until then (reading starts here). `key` is the folder's listing key,
    /// which its entries' keys start with. Safe to call while a column is built: what it changes is set later, from a task.
    func listing(_ key: String, in dir: URL, own: Bool) -> [Entry]? {
        _ = generation
        let known = listings[key]
        let again = known != nil && stale.remove(key) != nil
        if known != nil && !again { return known }
        guard loading.insert(key).inserted else { return known }
        Task {
            let entries = await Task.detached(priority: .utility) { Self.entries(of: dir, keyBase: key, own: own) }.value
            listings[key] = entries
            loading.remove(key)
            // Each video's frame and length, one at a time, as the console fills its icons in.
            for entry in entries where !entry.dir && metas[entry.key] == nil {
                metas[entry.key] = await Task.detached(priority: .utility) { await Self.readMeta(entry) }.value
            }
        }
        return known
    }

    /// Read a folder again the next time it is shown.
    func forget(_ key: String) {
        listings[key] = nil
    }

    /// Every folder read so far is read again as it is next shown: call it as the column comes into view.
    func refreshAll() {
        stale.formUnion(listings.keys)
        generation += 1
    }

    func meta(_ entry: Entry) -> Meta? { metas[entry.key] }

    nonisolated private static func entries(of dir: URL, keyBase: String, own: Bool) -> [Entry] {
        let keys: [URLResourceKey] = [.isDirectoryKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        return files.compactMap { file -> Entry? in
            let name = file.lastPathComponent
            let values = try? file.resourceValues(forKeys: Set(keys))
            let isDir = values?.isDirectory ?? false
            guard !name.hasPrefix("."), isDir || formats.contains(file.pathExtension.lowercased()) else { return nil }
            return Entry(name: name, url: file, key: keyBase + name, dir: isDir, modified: values?.contentModificationDate ?? .distantPast, own: own)
        }
        .sorted { ($0.dir ? 0 : 1, $0.name.lowercased()) < ($1.dir ? 0 : 1, $1.name.lowercased()) }
    }

    nonisolated private static func readMeta(_ entry: Entry) async -> Meta {
        let asset = AVURLAsset(url: entry.url)
        guard let time = try? await asset.load(.duration), time.seconds.isFinite else { return Meta(durationMs: 0, thumb: nil) }
        let duration = Int(time.seconds * 1000)
        try? FileManager.default.createDirectory(at: thumbs, withIntermediateDirectories: true)
        let cache = thumbs.appendingPathComponent(sha1("\(entry.key)|\(Int(entry.modified.timeIntervalSince1970 * 1000))") + ".jpg")
        if Self.size(of: cache) > 0 { return Meta(durationMs: duration, thumb: cache.path) }
        // A frame a tenth of the way in, past a black opening, at the size of an icon.
        let frames = AVAssetImageGenerator(asset: asset)
        frames.appliesPreferredTrackTransform = true
        frames.maximumSize = CGSize(width: 320, height: 180)
        let at = CMTime(value: CMTimeValue(min(max(duration * 100, 0), 30_000_000)), timescale: 1_000_000)
        guard let frame = try? await frames.image(at: at).image,
              let jpeg = UIImage(cgImage: frame).jpegData(compressionQuality: 0.85),
              (try? jpeg.write(to: cache, options: .atomic)) != nil else { return Meta(durationMs: duration, thumb: nil) }
        return Meta(durationMs: duration, thumb: cache.path)
    }

    nonisolated private static func size(of file: URL) -> Int {
        ((try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize) ?? 0
    }

    nonisolated private static func sha1(_ text: String) -> String {
        String(Insecure.SHA1.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined().prefix(24))
    }

    // MARK: - Where each was left

    /// Where `entry` was left, in ms; 0 for the start.
    func resumeAt(_ entry: Entry) -> Int { resume[entry.key] ?? 0 }

    /// Remembers where `key` was left; near either end it starts from the beginning next time.
    func saveResume(_ key: String, positionMs: Int, durationMs: Int) {
        XmbVideoRules.saveResume(key, positionMs: positionMs, durationMs: durationMs, marks: &resume, order: &resumeOrder)
        UserDefaults.standard.set(try? JSONEncoder().encode(resumeOrder.map { [$0, String(resume[$0] ?? 0)] }), forKey: Keys.resume)
    }

    // MARK: - Screen Mode and Aspect Ratio

    /// The console's Screen Mode, kept for every video: 0 Normal, 1 Full Screen (until one is picked), 2 Zoom, 3 Original.
    var screenMode: Int {
        get { min(max(UserDefaults.standard.object(forKey: Keys.screenMode) as? Int ?? 1, 0), 3) }
        set { UserDefaults.standard.set(newValue, forKey: Keys.screenMode) }
    }

    /// A video's own Aspect Ratio: 0 its own (Auto), 1 4:3, 2 16:9, 3 21:9.
    func aspectMode(_ key: String) -> Int {
        let saved = UserDefaults.standard.dictionary(forKey: Keys.aspect)?[key] as? Int ?? 0
        return min(max(saved, 0), 3)
    }

    func setAspectMode(_ key: String, _ mode: Int) {
        var saved = UserDefaults.standard.dictionary(forKey: Keys.aspect) ?? [:]
        saved[key] = mode == 0 ? nil : mode
        UserDefaults.standard.set(saved, forKey: Keys.aspect)
    }

    // MARK: - Import and delete

    /// Copies picked videos into the video folder; how many came in.
    func importVideos(_ urls: [URL]) async -> Int {
        let count = await Task.detached(priority: .utility) { urls.filter(Self.copyIn).count }.value
        forget(Self.ownKey())
        return count
    }

    nonisolated private static func copyIn(_ url: URL) -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let folder = ownFolder
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let picked = url.lastPathComponent.replacingOccurrences(of: "/", with: "_")
        let name = picked.trimmingCharacters(in: .whitespaces).isEmpty ? "video-\(Int(Date().timeIntervalSince1970 * 1000)).mp4" : picked
        let stem = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension.isEmpty ? "mp4" : (name as NSString).pathExtension
        var target = folder.appendingPathComponent(name)
        var n = 1
        while FileManager.default.fileExists(atPath: target.path) {
            target = folder.appendingPathComponent("\(stem) (\(n)).\(ext)")
            n += 1
        }
        let copied = (try? FileManager.default.copyItem(at: url, to: target)) != nil
            && size(of: target) > 0
        if !copied { try? FileManager.default.removeItem(at: target) }
        return copied
    }

    /// Deletes a video of ARMSX3's own folder, and reads its folder again.
    func delete(_ entry: Entry, listing key: String) {
        guard entry.own else { return }
        try? FileManager.default.removeItem(at: entry.url)
        metas[entry.key] = nil
        forget(key)
    }
}

/// The parts of the store with no I/O, so they can be checked on their own.
enum XmbVideoRules {
    /// Android keeps the last 200 places, dropping the oldest, and none within 10 s of the start or 15 s of the end.
    static func saveResume(_ key: String, positionMs: Int, durationMs: Int, marks: inout [String: Int], order: inout [String]) {
        if positionMs < 10_000 || (durationMs > 0 && positionMs > durationMs - 15_000) {
            marks[key] = nil
            order.removeAll { $0 == key }
        } else {
            if marks[key] == nil { order.append(key) }
            marks[key] = positionMs
        }
        while order.count > 200 { marks[order.removeFirst()] = nil }
    }

    /// The picture's size for a Screen Mode in a `screen` of points. Normal: the whole picture, bars where it is narrower
    /// or wider. Full Screen: stretched to the screen. Zoom: the screen filled, its shape kept, the rest cut off.
    /// Original: its own pixels, one to one, in the middle.
    static func pictureSize(mode: Int, aspect: Double, screen: CGSize, videoHeight: Double, scale: Double) -> CGSize {
        let w = Double(screen.width), h = Double(screen.height)
        let ratio = w / h
        switch mode {
        case 1: return CGSize(width: w, height: h)
        case 2: return aspect >= ratio ? CGSize(width: h * aspect, height: h) : CGSize(width: w, height: w / aspect)
        case 3: return CGSize(width: videoHeight / scale * aspect, height: videoHeight / scale)
        default: return aspect >= ratio ? CGSize(width: w, height: w / aspect) : CGSize(width: h * aspect, height: h)
        }
    }
}
