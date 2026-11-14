// SPDX-License-Identifier: GPL-2.0-only
// The XMB's music library and its music player, read and played with AVFoundation, after XmbMusicLibrary.kt and LibraryMusic.kt.

import AVFoundation
import CryptoKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The music is the music folder (Documents/Music) and the folders added with Add Folder, whose songs are played
/// where they are. A song is known everywhere (the queue, the playlists, the index) by one string: its path under
/// Documents, which stays the same when iOS moves the app's container, or its whole path in an added folder.
///
/// Tags are read once per song and kept in an index in the cache, keyed by song, size and time. A cover is, in
/// order: one given with Change Cover, an image beside the songs in their folder, then the picture inside a song.
@MainActor @Observable final class XmbMedia {
    static let shared = XmbMedia()

    /// What the Files picker is open for in the Music column.
    enum Pick: Equatable {
        case songs
        case album
        case folder
        /// A picture for this cover key.
        case cover(String)
        /// The track behind the XMB, from Music Settings.
        case track
        /// A folder of menu sounds, or the trophy sound, from Music Settings: copied into the core's sounds folder.
        case soundPack
        case trophySound
    }

    private(set) var library = XmbMusicLibrary()
    /// The folders added with Add Folder, by path.
    private(set) var folders: [String] = []
    private(set) var playlists = XmbPlaylists()
    /// Now Playing while it is open.
    var nowPlaying: XmbNowPlayingPanel?

    /// The music switch: off, nothing plays until a song is picked.
    private(set) var enabled: Bool
    /// 0...100 for the music behind the XMB, and for the songs picked in the Music column.
    private(set) var volumePercent: Int
    private(set) var playerVolumePercent: Int
    /// The songs playing in order; empty while the one track behind the XMB goes round.
    private(set) var queue: [String] = []
    private(set) var order = XmbPlayOrder()
    /// Paused with the player's own Pause: nothing that starts the music by itself plays it again.
    private(set) var userPaused = false
    /// True once a song was picked this run: the player's music, at its volume, in place of the background's.
    private(set) var chosen = false
    /// The name of the track chosen in Music Settings, or nil for the built-in one.
    private(set) var customName: String?
    /// Goes up with each song that starts, for a screen following the song.
    private(set) var trackSerial = 0

    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var ended: PlayerEnd?
    // A game or a video has the sound, or the XMB is not up.
    @ObservationIgnored private var held = true
    /// A focused game's own music is playing in the track's place.
    @ObservationIgnored private var previewing = false
    @ObservationIgnored private var unplayable: Set<String> = []
    @ObservationIgnored private var added: [String: URL] = [:]
    @ObservationIgnored private var bookmarks: [String: Data] = [:]
    /// Saved folders that did not open this run, such as one on a drive not plugged in; kept for a later run.
    @ObservationIgnored private var unresolved: [Data] = []
    @ObservationIgnored private var scans = 0
    /// How much of the set volume plays, 0...1: below 1 while the boot sound hands over to the music, and at 0 the
    /// song waits paused where it was.
    @ObservationIgnored private var share: Float = 1
    @ObservationIgnored private var fading: Task<Void, Never>?

    private enum Keys {
        static let enabled = "ui.libraryMusic"
        static let volume = "ui.libraryMusic.volume"
        static let customName = "ui.libraryMusic.customName"
        static let queue = "ui.libraryMusic.queue"
        static let queueIndex = "ui.libraryMusic.queueIndex"
        static let repeatMode = "ui.libraryMusic.repeat"
        static let shuffle = "ui.libraryMusic.shuffle"
        static let playerVolume = "ui.libraryMusic.playerVolume"
        static let folders = "xmb.music.folders"
    }

    /// The app's own track, when the bundle carries one.
    nonisolated static let builtinTrack = Bundle.main.url(forResource: "library_music", withExtension: "m4a")

    nonisolated private static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    nonisolated private static var support: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] }
    nonisolated private static var caches: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0] }

    nonisolated static var musicFolder: URL { documents.appendingPathComponent("Music", isDirectory: true) }

    nonisolated static func url(for song: String) -> URL {
        song.hasPrefix("/") ? URL(fileURLWithPath: song) : documents.appendingPathComponent(song)
    }

    /// A song in the music folder itself, the app's own copy, as against one played where it is.
    nonisolated static func owns(_ song: String) -> Bool { !song.hasPrefix("/") }

    private init() {
        let defaults = UserDefaults.standard
        enabled = defaults.object(forKey: Keys.enabled) as? Bool ?? true
        // Quiet by default: music behind a menu sits well under it.
        volumePercent = defaults.object(forKey: Keys.volume) as? Int ?? 15
        playerVolumePercent = min(max(defaults.object(forKey: Keys.playerVolume) as? Int ?? 100, 0), 100)
        customName = defaults.string(forKey: Keys.customName)
        var resave = false
        for bookmark in defaults.array(forKey: Keys.folders) as? [Data] ?? [] {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else {
                unresolved.append(bookmark)
                continue
            }
            // The same folder added twice, once while its drive was out, is listed once.
            guard added[url.path] == nil else {
                resave = true
                continue
            }
            // Kept open for the run: its songs are played where they are.
            _ = url.startAccessingSecurityScopedResource()
            added[url.path] = url
            // A moved or renamed folder gets a fresh bookmark, as the library's folders do.
            let fresh = stale ? try? url.bookmarkData() : nil
            resave = resave || fresh != nil
            bookmarks[url.path] = fresh ?? bookmark
            folders.append(url.path)
        }
        if resave { defaults.set(unresolved + folders.compactMap { bookmarks[$0] }, forKey: Keys.folders) }
        let saved = defaults.stringArray(forKey: Keys.queue) ?? []
        let savedIndex = defaults.integer(forKey: Keys.queueIndex)
        queue = saved.filter { FileManager.default.fileExists(atPath: Self.url(for: $0).path) }
        order.repeatMode = XmbRepeat(rawValue: defaults.object(forKey: Keys.repeatMode) as? Int ?? XmbRepeat.all.rawValue) ?? .all
        order.setShuffle(defaults.bool(forKey: Keys.shuffle))
        // Songs deleted in Files leave the queue, so the saved song is found again by name; gone, the queue starts over.
        let resumed = saved.indices.contains(savedIndex) ? queue.firstIndex(of: saved[savedIndex]) : nil
        order.set(count: queue.count, index: resumed ?? 0)
        if resumed == nil, !queue.isEmpty { customName = Self.songName(queue[0]) }
        // The saved index is an index into this queue from now on, so the queue it indexes is saved with it.
        if queue != saved {
            defaults.set(queue, forKey: Keys.queue)
            defaults.set(order.index, forKey: Keys.queueIndex)
            defaults.set(customName, forKey: Keys.customName)
        }
        if let data = try? Data(contentsOf: Self.playlistsFile) { playlists = XmbPlaylists(json: data) }
        observe()
    }

    // MARK: - What the Files picker brings

    static func types(for pick: Pick) -> [UTType] {
        switch pick {
        case .songs: return [.audio, .zip]
        case .album: return [.folder, .zip]
        case .folder: return [.folder]
        case .cover: return [.image]
        case .track: return [.audio]
        case .soundPack: return [.folder]
        case .trophySound: return [.wav]
        }
    }

    /// Takes picked files in for `pick`. The answer is how many songs or sounds came in, or 1 for a cover, a track or a
    /// trophy sound that was taken.
    func add(_ urls: [URL], for pick: Pick) async -> Int {
        guard let first = urls.first else { return 0 }
        switch pick {
        case .songs:
            let songs = await Task.detached(priority: .utility) { Self.importFiles(urls) }.value
            await scanMusic()
            return songs
        case .album:
            let songs = await Task.detached(priority: .utility) { urls.reduce(0) { $0 + Self.importAlbum($1) } }.value
            await scanMusic()
            return songs
        case .folder:
            return await addFolder(first)
        case .cover(let key):
            let taken = await Task.detached(priority: .utility) { Self.setCover(key, from: first) }.value
            await scanMusic()
            return taken ? 1 : 0
        case .track:
            return setCustomTrack(first) ? 1 : 0
        case .soundPack:
            guard let sounds = await Self.soundsFolder() else { return 0 }
            return await Task.detached(priority: .utility) { Self.reading(first) { SoundRouting.importFolder(first, into: sounds).copied } }.value
        case .trophySound:
            guard let sounds = await Self.soundsFolder() else { return 0 }
            return await Task.detached(priority: .utility) { Self.reading(first) { SoundRouting.importTrophy(first, into: sounds) } }.value ? 1 : 0
        }
    }

    /// The core's sounds folder, where the menu sounds and the trophy sound are kept; nil when the bridge cannot say.
    static func soundsFolder() async -> URL? {
        let (config, status) = await Rpcs3Bridge.configDirectory()
        guard status == 0, !config.isEmpty else { return nil }
        return URL(fileURLWithPath: config, isDirectory: true).appendingPathComponent("sounds", isDirectory: true)
    }

    /// Picked files are the player's own and are only read, inside the access the picker gave.
    nonisolated private static func reading<T>(_ url: URL, _ body: () -> T) -> T {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return body()
    }

    nonisolated private static func size(_ url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
    }

    nonisolated private static func isFolder(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
    }

    nonisolated private static func free(_ name: String, in folder: URL, isFolder: Bool = false) -> URL {
        folder.appendingPathComponent(XmbMusicLibrary.freeName(name, isFolder: isFolder) {
            FileManager.default.fileExists(atPath: folder.appendingPathComponent($0).path)
        })
    }

    /// Copies picked songs into the music folder and unpacks a picked .zip there as an album; how many songs came in.
    nonisolated private static func importFiles(_ urls: [URL]) -> Int {
        let music = musicFolder
        try? FileManager.default.createDirectory(at: music, withIntermediateDirectories: true)
        return urls.reduce(0) { songs, url in
            songs + reading(url) {
                let format = url.pathExtension.lowercased()
                if format == "zip" { return importZip(url) }
                guard XmbMusicLibrary.audio.contains(format) else { return 0 }
                let target = free(url.lastPathComponent, in: music)
                if (try? FileManager.default.copyItem(at: url, to: target)) != nil, size(target) > 0 { return 1 }
                try? FileManager.default.removeItem(at: target)
                return 0
            }
        }
    }

    /// Import Album: a folder copied in as it is, or a .zip unpacked.
    nonisolated private static func importAlbum(_ url: URL) -> Int {
        reading(url) { isFolder(url) ? importFolder(url) : importZip(url) }
    }

    /// Unpacks a .zip of an album (or a few) into the music folder under the zip's name: its songs and pictures in
    /// their folders, a folder the whole zip sits in opened out. A zip that cannot be read whole brings nothing,
    /// rather than half an album.
    nonisolated private static func importZip(_ url: URL) -> Int {
        let music = musicFolder
        let stem = url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespaces)
        let target = free(stem.isEmpty ? "Album" : stem, in: music, isFolder: true)
        var songs = 0
        do {
            let zip = try ZipArchive(url: url)
            var limits = ZipLimits(entryBytes: 1 << 30, totalBytes: 4 << 30, entries: 20_000)
            for entry in zip.entries where !entry.isDirectory {
                guard let parts = XmbMusicLibrary.zipPath(entry.name) else { continue }
                let out = parts.reduce(target) { $0.appendingPathComponent($1) }
                try FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
                try zip.extract(entry, to: out, limits: &limits)
                if size(out) == 0 {
                    try? FileManager.default.removeItem(at: out)
                } else if XmbMusicLibrary.audio.contains(out.pathExtension.lowercased()) {
                    songs += 1
                }
            }
        } catch {
            songs = 0
        }
        guard songs > 0 else {
            try? FileManager.default.removeItem(at: target)
            return 0
        }
        // An album that sits in one folder of its own: that folder's contents moved up.
        if let only = (try? FileManager.default.contentsOfDirectory(at: target, includingPropertiesForKeys: nil)).flatMap({ $0.count == 1 ? $0[0] : nil }),
           isFolder(only) {
            let moved = target.appendingPathComponent(".unpacking")
            if (try? FileManager.default.moveItem(at: only, to: moved)) != nil {
                for file in (try? FileManager.default.contentsOfDirectory(at: moved, includingPropertiesForKeys: nil)) ?? [] {
                    try? FileManager.default.moveItem(at: file, to: target.appendingPathComponent(file.lastPathComponent))
                }
                try? FileManager.default.removeItem(at: moved)
            }
        }
        return songs
    }

    /// Copies a picked folder (an album, a disc's worth, or a folder of albums) into the music folder as it is: its
    /// folders, its songs and its cover images.
    nonisolated private static func importFolder(_ dir: URL) -> Int {
        let music = musicFolder.standardizedFileURL.path
        let from = dir.standardizedFileURL.path
        // Not the music folder into itself.
        guard music != from, !music.hasPrefix(from + "/") else { return 0 }
        let name = dir.lastPathComponent
        let target = free(name.isEmpty ? "Album" : name, in: musicFolder, isFolder: true)
        let songs = copyFolder(dir, to: target, depth: 0)
        if songs == 0 { try? FileManager.default.removeItem(at: target) }
        return songs
    }

    nonisolated private static func copyFolder(_ dir: URL, to target: URL, depth: Int) -> Int {
        guard depth <= 3 else { return 0 }
        var songs = 0
        for file in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey], options: .skipsHiddenFiles)) ?? [] {
            let out = target.appendingPathComponent(file.lastPathComponent)
            if isFolder(file) {
                songs += copyFolder(file, to: out, depth: depth + 1)
                continue
            }
            let format = file.pathExtension.lowercased()
            let song = XmbMusicLibrary.audio.contains(format)
            guard song || XmbMusicLibrary.images.contains(format) else { continue }
            try? FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            if (try? FileManager.default.copyItem(at: file, to: out)) != nil, size(out) > 0 {
                if song { songs += 1 }
            } else {
                try? FileManager.default.removeItem(at: out)
            }
        }
        return songs
    }

    /// Keeps a picked folder and plays its songs from where they are; how many songs it has.
    private func addFolder(_ url: URL) async -> Int {
        let path = url.path
        if added[path] == nil {
            let scoped = url.startAccessingSecurityScopedResource()
            guard let bookmark = try? url.bookmarkData() else {
                if scoped { url.stopAccessingSecurityScopedResource() }
                return 0
            }
            added[path] = url
            bookmarks[path] = bookmark
            folders.append(path)
            // A saved copy that did not open at launch, now plugged in, gives way to this one.
            unresolved.removeAll { data in
                var stale = false
                return (try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale))?.path == path
            }
            saveFolders()
        }
        // Counted from what this scan read, which a newer scan may already have replaced.
        return await scanMusic().inFolder(path).count
    }

    /// Stops listing a folder; its songs stay where they are.
    func removeFolder(_ path: String) async {
        added.removeValue(forKey: path)?.stopAccessingSecurityScopedResource()
        bookmarks[path] = nil
        folders.removeAll { $0 == path }
        saveFolders()
        await scanMusic()
    }

    private func saveFolders() {
        UserDefaults.standard.set(unresolved + folders.compactMap { bookmarks[$0] }, forKey: Keys.folders)
    }

    // MARK: - The music library

    private struct Found: Sendable {
        let id: String
        let name: String
        let folder: String?
        let url: URL
        let size: Int64
        let modified: Double
    }

    /// Reads the music again: tags from the index where a song has not changed, the rest afresh.
    @discardableResult func scanMusic() async -> XmbMusicLibrary {
        let folders = folders
        scans += 1
        let turn = scans
        let found = await Task.detached(priority: .utility) { await Self.readLibrary(added: folders) }.value
        // A later scan saw a later list, so an older one that finishes after it is dropped.
        guard turn == scans else { return found }
        if library != found { library = found }
        return found
    }

    /// The songs under `dir`, at most `depth` folders further down. `id` is how `dir` is written in a song's id;
    /// `loose` leaves the songs in `dir` itself without a folder.
    nonisolated private static func songs(in dir: URL, id: String, depth: Int, loose: Bool) -> [Found] {
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        var found: [Found] = []
        for entry in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys, options: .skipsHiddenFiles)) ?? [] {
            let values = try? entry.resourceValues(forKeys: Set(keys))
            let name = entry.lastPathComponent
            if values?.isDirectory == true {
                if depth > 0 { found += songs(in: entry, id: id + "/" + name, depth: depth - 1, loose: false) }
            } else if let size = values?.fileSize, size > 0, XmbMusicLibrary.audio.contains(entry.pathExtension.lowercased()) {
                found.append(Found(id: id + "/" + name, name: name, folder: loose ? nil : id, url: entry, size: Int64(size),
                                   modified: values?.contentModificationDate?.timeIntervalSince1970 ?? 0))
            }
        }
        return found
    }

    nonisolated private static func readLibrary(added: [String]) async -> XmbMusicLibrary {
        func byPath(_ songs: [Found]) -> [Found] { songs.sorted { $0.id.lowercased() < $1.id.lowercased() } }
        // As deep as an imported album lands, a zip's five path parts included, so Artist/Album/CD1 songs are listed too.
        var found = byPath(songs(in: musicFolder, id: "Music", depth: 5, loose: true))
        for path in added {
            found += byPath(songs(in: URL(fileURLWithPath: path, isDirectory: true), id: path, depth: 4, loose: false))
        }
        try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let indexFile = cache.appendingPathComponent("index.json")
        var index = (try? JSONDecoder().decode([String: XmbTrack].self, from: Data(contentsOf: indexFile))) ?? [:]
        var changed = false
        var seen: Set<String> = []
        var tracks: [XmbTrack] = []
        // A folder added inside another lists its songs once.
        for song in found where seen.insert(song.id).inserted {
            if let known = index[song.id], known.size == song.size, known.modified == song.modified {
                tracks.append(known)
            } else {
                let track = await read(song)
                index[song.id] = track
                tracks.append(track)
                changed = true
            }
        }
        // Songs that have gone leave the index too.
        for id in index.keys where !seen.contains(id) {
            index[id] = nil
            changed = true
        }
        if changed, let data = try? JSONEncoder().encode(index) { try? data.write(to: indexFile, options: .atomic) }

        let given = chosenCovers()
        var (albums, loose) = XmbMusicLibrary.gather(tracks)
        var chosen: Set<String> = []
        for at in albums.indices {
            if let own = given[hash(albums[at].coverKey)] {
                albums[at].cover = own
                chosen.insert(albums[at].coverKey)
            } else {
                albums[at].cover = await cover(for: albums[at].tracks)
            }
        }
        var looseCovers: [String: String] = [:]
        for track in loose {
            if let own = given[hash("song:" + track.id)] {
                looseCovers[track.id] = own
                chosen.insert("song:" + track.id)
            } else if track.picture {
                looseCovers[track.id] = await picture(in: track)
            }
        }
        return XmbMusicLibrary(albums: albums, loose: loose, looseCovers: looseCovers, chosen: chosen)
    }

    /// A song's tags. One that does not read as audio here has its name and nothing else.
    nonisolated private static func read(_ song: Found) async -> XmbTrack {
        var track = XmbTrack(id: song.id, name: song.name, folder: song.folder, size: song.size, modified: song.modified,
                             title: (song.name as NSString).deletingPathExtension)
        let asset = AVURLAsset(url: song.url)
        guard let duration = try? await asset.load(.duration), duration.seconds.isFinite else { return track }
        track.durationMs = Int(duration.seconds * 1000)
        func text(_ item: AVMetadataItem) async -> String? {
            guard let text = try? await item.load(.stringValue)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
            return text
        }
        for tag in (try? await asset.load(.commonMetadata)) ?? [] {
            switch tag.commonKey {
            case .commonKeyTitle?: if let title = await text(tag) { track.title = title }
            case .commonKeyArtist?: track.artist = await text(tag)
            case .commonKeyAlbumName?: track.album = await text(tag)
            case .commonKeyArtwork?: track.picture = true
            default: break
            }
        }
        // The album's artist and the song's place in it have no common key: iTunes and ID3 each name their own.
        let all = (try? await asset.load(.metadata)) ?? []
        func first(_ identifiers: [AVMetadataIdentifier]) -> AVMetadataItem? {
            identifiers.lazy.compactMap { AVMetadataItem.metadataItems(from: all, filteredByIdentifier: $0).first }.first
        }
        func number(_ item: AVMetadataItem?) async -> Int {
            guard let item else { return 0 }
            // ID3 writes "3/12"; iTunes packs the number into bytes 2 and 3.
            if let text = await text(item) { return Int(text.split(separator: "/").first ?? "") ?? 0 }
            if let data = try? await item.load(.dataValue), data.count >= 4 { return Int(data[data.startIndex + 2]) << 8 | Int(data[data.startIndex + 3]) }
            return (try? await item.load(.numberValue))?.intValue ?? 0
        }
        if let artist = first([.iTunesMetadataAlbumArtist, .id3MetadataBand]) { track.albumArtist = await text(artist) }
        track.number = await number(first([.iTunesMetadataTrackNumber, .id3MetadataTrackNumber]))
        track.disc = await number(first([.iTunesMetadataDiscNumber, .id3MetadataPartOfASet]))
        return track
    }

    // MARK: - Covers

    nonisolated private static var cache: URL { caches.appendingPathComponent("xmb-music", isDirectory: true) }

    /// Covers given with Change Cover, kept with the app's data and not in the cache: <key's hash>-<time>.jpg.
    nonisolated private static var chosenFolder: URL { support.appendingPathComponent("xmb-music-covers", isDirectory: true) }

    nonisolated private static func hash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    nonisolated private static func art(_ key: String) -> URL {
        let folder = cache.appendingPathComponent("art", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(hash(key) + ".jpg")
    }

    /// A picture as a JPEG no larger than `largest` on its longer side.
    nonisolated private static func write(_ image: UIImage, to url: URL, largest: CGFloat = 512, quality: CGFloat = 0.85) -> Bool {
        let side = min(max(image.size.width, image.size.height), largest)
        let scale = side / max(image.size.width, image.size.height, 1)
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = small.jpegData(compressionQuality: quality) else { return false }
        return (try? data.write(to: url, options: .atomic)) != nil
    }

    /// An album's cover: an image beside its songs, else the picture inside one of them.
    nonisolated private static func cover(for songs: [XmbTrack]) async -> String? {
        var folders: [String] = []
        for folder in songs.compactMap(\.folder) where !folders.contains(folder) { folders.append(folder) }
        for folder in folders {
            let files = ((try? FileManager.default.contentsOfDirectory(at: url(for: folder), includingPropertiesForKeys: [.fileSizeKey],
                                                                       options: .skipsHiddenFiles)) ?? [])
                .filter { XmbMusicLibrary.images.contains($0.pathExtension.lowercased()) && size($0) > 0 }
            guard let file = XmbMusicLibrary.pickCover(files, name: \.lastPathComponent, size: size) else { continue }
            // Saved small: a scan of a sleeve is far larger than the XMB draws it.
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate?.timeIntervalSince1970 ?? 0
            let out = art("\(file.path)|\(size(file))|\(modified)")
            if size(out) > 0 { return out.path }
            if let image = UIImage(contentsOfFile: file.path), write(image, to: out, largest: 640, quality: 0.88) { return out.path }
        }
        guard let song = songs.first(where: \.picture) else { return nil }
        return await picture(in: song)
    }

    /// The picture inside a song, saved once in the cache no larger than 640 px; nil when it has none.
    nonisolated private static func picture(in track: XmbTrack) async -> String? {
        let out = art("\(track.id)|\(track.size)|\(track.modified)")
        if size(out) > 0 { return out.path }
        for tag in (try? await AVURLAsset(url: url(for: track.id)).load(.commonMetadata)) ?? [] where tag.commonKey == .commonKeyArtwork {
            if let data = try? await tag.load(.dataValue), let image = UIImage(data: data), write(image, to: out, largest: 640, quality: 0.88) {
                return out.path
            }
        }
        return nil
    }

    /// The covers given with Change Cover, by their key's hash, the newest of each.
    nonisolated private static func chosenCovers() -> [String: String] {
        var newest: [String: (path: String, modified: Date)] = [:]
        for file in (try? FileManager.default.contentsOfDirectory(at: chosenFolder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        where file.pathExtension == "jpg" {
            let key = String(file.lastPathComponent.prefix { $0 != "-" })
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if newest[key].map({ $0.modified < modified }) ?? true { newest[key] = (file.path, modified) }
        }
        return newest.mapValues(\.path)
    }

    /// Makes a picked picture the cover for `key`, kept no larger than 1024 px. Each is a file of its own, so nothing
    /// goes on showing the one before.
    nonisolated private static func setCover(_ key: String, from url: URL) -> Bool {
        guard let image = reading(url, { UIImage(contentsOfFile: url.path) }) else { return false }
        try? FileManager.default.createDirectory(at: chosenFolder, withIntermediateDirectories: true)
        let out = chosenFolder.appendingPathComponent("\(hash(key))-\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        guard write(image, to: out, largest: 1024, quality: 0.88) else { return false }
        removeCovers(key, except: out)
        return true
    }

    nonisolated private static func removeCovers(_ key: String, except kept: URL? = nil) {
        for file in (try? FileManager.default.contentsOfDirectory(at: chosenFolder, includingPropertiesForKeys: nil)) ?? []
        where file.lastPathComponent.hasPrefix(hash(key) + "-") && file.lastPathComponent != kept?.lastPathComponent {
            try? FileManager.default.removeItem(at: file)
        }
    }

    /// Back to the cover the songs come with.
    func clearCover(_ key: String) async {
        Self.removeCovers(key)
        await scanMusic()
    }

    /// Deletes a song of the music folder. The one playing gives way to the built-in track.
    func deleteSong(_ song: String) async {
        guard Self.owns(song) else { return }
        try? FileManager.default.removeItem(at: Self.url(for: song))
        // Application Support is never purged, so the song's own cover goes with it.
        Self.removeCovers("song:" + song)
        if current == song { playBuiltin() }
        await scanMusic()
    }

    // MARK: - Playlists

    nonisolated private static var playlistsFile: URL { support.appendingPathComponent("xmb/music-playlists.json") }

    private func savePlaylists() {
        try? FileManager.default.createDirectory(at: Self.playlistsFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? playlists.json.write(to: Self.playlistsFile, options: .atomic)
    }

    func createPlaylist(_ name: String) {
        playlists.create(name)
        savePlaylists()
    }

    func addToPlaylist(_ song: String, _ name: String) {
        playlists.add(song, to: name)
        savePlaylists()
    }

    func removeFromPlaylist(_ song: String, _ name: String) {
        playlists.remove(song, from: name)
        savePlaylists()
    }

    func deletePlaylist(_ name: String) {
        playlists.delete(name)
        savePlaylists()
    }

    // MARK: - The player

    /// The song playing from the queue, or nil for the built-in track or the one chosen in Music Settings.
    var current: String? { queue.indices.contains(order.index) ? queue[order.index] : nil }
    var queueIndex: Int { order.index }
    var repeatMode: XmbRepeat { order.repeatMode }
    var shuffle: Bool { order.shuffle }

    var isPlaying: Bool { player?.isPlaying ?? false }
    /// Where the song is and how long it is, in ms; 0 with nothing loaded.
    var positionMs: Int { Int(max(player?.currentTime ?? 0, 0) * 1000) }
    var durationMs: Int { Int(max(player?.duration ?? 0, 0) * 1000) }

    private var gain: Float { Float(min(max(chosen ? playerVolumePercent : volumePercent, 0), 100)) / 100 * share }

    /// A file name for a song id.
    nonisolated static func songName(_ song: String) -> String { (song as NSString).lastPathComponent }

    /// The songs of a playlist that are still in the library, in order.
    func playlistSongs(_ name: String) -> [String] { playlists.songs(name).filter { library.byId[$0] != nil } }

    // MARK: - Handing over to the boot and shutdown sounds

    /// The music quiet until `fadeIn()`, as the boot sound plays.
    func hold() {
        fading?.cancel()
        setShare(0)
    }

    /// After 0.6 s of quiet, the music in over 2 s from wherever it was held, as after the boot sound.
    func fadeIn() {
        fading?.cancel()
        fading = Task { [weak self] in
            self?.setShare(0)
            try? await Task.sleep(for: .milliseconds(600))
            await self?.fade(over: 2) { max($0, 0.01) }
            self?.setShare(1)
        }
    }

    /// The music down to nothing over `seconds`, as the screen goes dark on leaving XMB mode.
    func fadeOut(over seconds: Double = 1.2) {
        fading?.cancel()
        fading = Task { [weak self] in
            await self?.fade(over: seconds) { max(1 - $0, 0.01) }
            self?.setShare(0)
        }
    }

    private func fade(over seconds: Double, _ level: (Float) -> Float) async {
        let start = CACurrentMediaTime()
        while !Task.isCancelled {
            let v = Float(min((CACurrentMediaTime() - start) / seconds, 1))
            setShare(level(v))
            if v >= 1 { return }
            try? await Task.sleep(for: .milliseconds(16))
        }
    }

    private func setShare(_ value: Float) {
        share = min(max(value, 0), 1)
        guard let player else { return }
        player.volume = gain
        if share <= 0 {
            player.pause()
        } else if !player.isPlaying {
            resumePlayer()
        }
    }

    nonisolated private static var customFolder: URL { support.appendingPathComponent("librarymusic", isDirectory: true) }

    private var customTrack: URL? {
        guard customName != nil else { return nil }
        return (try? FileManager.default.contentsOfDirectory(at: Self.customFolder, includingPropertiesForKeys: nil))?.first { Self.size($0) > 0 }
    }

    /// Plays `songs` in order from `start`: Cross on a song, with the rest of its album or list after it.
    func playQueue(_ songs: [String], start: Int) {
        guard !songs.isEmpty else { return }
        queue = songs
        order.set(count: songs.count, index: start)
        customName = Self.songName(songs[order.index])
        chosen = true
        userPaused = false
        UserDefaults.standard.set(songs, forKey: Keys.queue)
        UserDefaults.standard.set(order.index, forKey: Keys.queueIndex)
        UserDefaults.standard.set(customName, forKey: Keys.customName)
        if enabled { restart() } else { setEnabled(true) }
    }

    /// Next (1) or Previous (-1). False when there is no song that way.
    @discardableResult func skip(_ step: Int) -> Bool {
        switch order.skip(step, positionMs: positionMs) {
        case .none:
            return false
        case .restart:
            seek(to: 0)
        case .go(let index):
            customName = Self.songName(queue[index])
            UserDefaults.standard.set(index, forKey: Keys.queueIndex)
            UserDefaults.standard.set(customName, forKey: Keys.customName)
            restart()
        }
        return true
    }

    /// A song ended: the next one, or at the end with Repeat off, back to the first and paused there.
    private func songEnded() {
        if skip(1) { return }
        userPaused = true
        order.rewind()
        if queue.indices.contains(order.index) { customName = Self.songName(queue[order.index]) }
        UserDefaults.standard.set(order.index, forKey: Keys.queueIndex)
        UserDefaults.standard.set(customName, forKey: Keys.customName)
        restart()
    }

    /// The player's Play and Pause: held where it is, then on from there.
    func playPause() {
        if !userPaused, isPlaying {
            userPaused = true
            player?.pause()
            return
        }
        userPaused = false
        held = false
        if !enabled {
            setEnabled(true)
        } else if player == nil {
            start(force: true)
        } else {
            resumePlayer(force: true)
        }
    }

    /// The player's Stop: back to the start of the song, paused.
    func stopSong() {
        userPaused = true
        player?.pause()
        seek(to: 0)
    }

    func seek(to ms: Int) {
        player?.currentTime = Double(max(ms, 0)) / 1000
    }

    func setRepeat(_ mode: XmbRepeat) {
        order.repeatMode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: Keys.repeatMode)
        // One song going round, or on to the next when it ends: set on the player that is going now.
        player?.numberOfLoops = order.loops ? -1 : 0
    }

    func setShuffle(_ on: Bool) {
        order.setShuffle(on)
        UserDefaults.standard.set(on, forKey: Keys.shuffle)
    }

    func setVolume(_ percent: Int) {
        volumePercent = min(max(percent, 0), 100)
        UserDefaults.standard.set(volumePercent, forKey: Keys.volume)
        player?.volume = gain
    }

    func setPlayerVolume(_ percent: Int) {
        playerVolumePercent = min(max(percent, 0), 100)
        UserDefaults.standard.set(playerVolumePercent, forKey: Keys.playerVolume)
        player?.volume = gain
    }

    func setEnabled(_ on: Bool) {
        enabled = on
        UserDefaults.standard.set(on, forKey: Keys.enabled)
        if on {
            userPaused = false
            start(force: true)
        } else {
            release()
        }
    }

    private func clearQueue() {
        queue = []
        order.set(count: 0, index: 0)
        chosen = false
        userPaused = false
        UserDefaults.standard.removeObject(forKey: Keys.queue)
        UserDefaults.standard.removeObject(forKey: Keys.queueIndex)
    }

    /// Drops the queue and the track chosen in Music Settings, and goes back to the built-in one.
    func playBuiltin() {
        clearQueue()
        try? FileManager.default.removeItem(at: Self.customFolder)
        customName = nil
        UserDefaults.standard.removeObject(forKey: Keys.customName)
        restart()
    }

    /// Copies a picked file in as the track behind the XMB, so playing it never depends on the original staying put.
    private func setCustomTrack(_ url: URL) -> Bool {
        clearQueue()
        try? FileManager.default.removeItem(at: Self.customFolder)
        try? FileManager.default.createDirectory(at: Self.customFolder, withIntermediateDirectories: true)
        // The extension is kept: AVAudioPlayer goes by it for some formats.
        let target = Self.customFolder.appendingPathComponent("track." + url.pathExtension)
        let copied = Self.reading(url) { (try? FileManager.default.copyItem(at: url, to: target)) != nil } && Self.size(target) > 0
        customName = copied ? url.lastPathComponent : nil
        UserDefaults.standard.set(customName, forKey: Keys.customName)
        restart()
        return copied
    }

    /// Stops and starts again, so a change of song is heard now. Asked for by the player, so it is always heard.
    private func restart() {
        held = false
        release()
        start(force: true)
    }

    private func release() {
        player?.stop()
        player = nil
        ended = nil
    }

    /// Begins playing, if it should: not when the switch is off, a game or a video has the sound, or, unless
    /// `force`, another app is already playing something.
    private func start(force: Bool = false) {
        guard enabled, !held else { return }
        if player != nil { return resumePlayer(force: force) }
        let song = current
        guard let url = song.map(Self.url(for:)) ?? customTrack ?? Self.builtinTrack else { return }
        let session = AVAudioSession.sharedInstance()
        if !force, session.isOtherAudioPlaying { return }
        // Over a game the session is the game's, which already plays and may also be recording.
        if !EmulatorSession.shared.isRunning { try? session.setCategory(.playback, mode: .default) }
        try? session.setActive(true)
        guard let next = try? AVAudioPlayer(contentsOf: url) else {
            // One that will not play is passed over; with none that will, quiet.
            if let song {
                unplayable.insert(song)
                if queue.contains(where: { !unplayable.contains($0) }), skip(1) { return }
            }
            return
        }
        let ended = PlayerEnd { [weak self] in self?.songEnded() }
        next.delegate = ended
        self.ended = ended
        next.numberOfLoops = order.loops ? -1 : 0
        next.volume = gain
        next.prepareToPlay()
        player = next
        trackSerial += 1
        // Handed over to the boot sound, or paused in the player: ready, but quiet until it is handed back or played.
        if share > 0, !userPaused, !previewing { next.play() }
    }

    /// Goes on where it was. Only the player's own Play (`force`) takes the sound from another app that is playing.
    private func resumePlayer(force: Bool = false) {
        // Only while active: Control Center or the app switcher make the app inactive, and didBecomeActive resumes it.
        guard enabled, !held, !userPaused, !previewing, share > 0, UIApplication.shared.applicationState == .active else { return }
        let session = AVAudioSession.sharedInstance()
        if force {
            if !EmulatorSession.shared.isRunning { try? session.setCategory(.playback, mode: .default) }
            try? session.setActive(true)
        } else {
            guard !session.isOtherAudioPlaying else { return }
            // Back on .playback, which an earlier activation may have left on .ambient.
            shareSession()
        }
        player?.play()
    }

    /// The XMB's own sounds mix with another app's audio, as the music waits for it, and follow the Ring/Silent switch
    /// while they do. Over a game the session is the game's.
    func shareSession() {
        guard !EmulatorSession.shared.isRunning else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(session.isOtherAudioPlaying ? .ambient : .playback, mode: .default)
    }

    /// A game's music starts in the track's place: the track waits where it is until `endPreview()`.
    func startPreview() {
        previewing = true
        player?.pause()
    }

    /// The game's music has stopped: the track goes on, if nothing else holds it.
    func endPreview() {
        previewing = false
        resumePlayer()
    }

    /// A game or a video takes the sound, or the XMB is left: the song waits where it is.
    func stop() {
        held = true
        player?.pause()
        XmbGaiaRenderer.forgetEarth()
    }

    /// The XMB is up again after `stop()`, or for the first time: the music goes on, if nothing else holds it.
    func resume() {
        held = false
        shareSession()
        start()
    }

    private func observe() {
        let centre = NotificationCenter.default
        // A call or another app's sound pauses the player by itself; it goes on when the system says it may.
        centre.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let info = note.userInfo
            guard info?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.ended.rawValue,
                  AVAudioSession.InterruptionOptions(rawValue: info?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0).contains(.shouldResume)
            else { return }
            Task { @MainActor in self?.resumePlayer() }
        }
        // Headphones taken off: the music pauses instead of moving to the speaker, also while a game's music or the boot
        // sound holds it, and the game's music stops.
        centre.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            else { return }
            Task { @MainActor in
                guard let self else { return }
                // A held song is silent already, so it plays again when the game or video closes.
                if self.player != nil, !self.held {
                    self.userPaused = true
                    self.player?.pause()
                }
                XmbGameMedia.shared.stopMusic()
            }
        }
        centre.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.player?.pause() }
        }
        // Back in the app, also from Control Center or a banner: the song goes on, unless another app's sound started.
        centre.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.held else { return }
                self.shareSession()
                self.resumePlayer()
            }
        }
        // Back from the background, songs put in the music folder from Files show.
        centre.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.held else { return }
                await self.scanMusic()
            }
        }
    }
}

private final class PlayerEnd: NSObject, AVAudioPlayerDelegate {
    let finished: @MainActor () -> Void

    init(_ finished: @escaping @MainActor () -> Void) { self.finished = finished }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in finished() }
    }
}
