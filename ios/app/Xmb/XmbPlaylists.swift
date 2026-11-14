// SPDX-License-Identifier: GPL-2.0-only
// The XMB's playlists and the parts of its music that need no audio: the playing order, the albums and the console's lengths, after XmbPlaylists.kt, LibraryMusic.kt and XmbMusicLibrary.kt.

import Foundation

/// Named lists of songs in the order they were added, as the console's Music column keeps them. A song is its id.
struct XmbPlaylists: Equatable {
    struct List: Codable, Equatable {
        var name: String
        var songs: [String]
    }

    private(set) var lists: [List] = []

    init() {}

    /// What `json` wrote; anything else is no playlists.
    init(json: Data) {
        lists = (try? JSONDecoder().decode([List].self, from: json)) ?? []
    }

    var json: Data { (try? JSONEncoder().encode(lists)) ?? Data() }
    var names: [String] { lists.map(\.name) }

    func songs(_ name: String) -> [String] { lists.first { $0.name == name }?.songs ?? [] }

    /// Adds `song` to `name`, making the playlist when it is new; false when it was in it already.
    @discardableResult mutating func add(_ song: String, to name: String) -> Bool {
        guard let at = lists.firstIndex(where: { $0.name == name }) else {
            lists.append(List(name: name, songs: [song]))
            return true
        }
        guard !lists[at].songs.contains(song) else { return false }
        lists[at].songs.append(song)
        return true
    }

    /// A new, empty playlist; nothing when there is one of that name already.
    mutating func create(_ name: String) {
        if !names.contains(name) { lists.append(List(name: name, songs: [])) }
    }

    mutating func remove(_ song: String, from name: String) {
        guard let at = lists.firstIndex(where: { $0.name == name }) else { return }
        lists[at].songs.removeAll { $0 == song }
    }

    mutating func delete(_ name: String) { lists.removeAll { $0.name == name } }
}

/// How a queue goes on at its last song: stops, goes round, or plays the one song again.
enum XmbRepeat: Int, CaseIterable, Sendable {
    case off, all, one
}

/// The order a queue plays in, as queue indices: the queue's own, or shuffled with the song playing first, so turning
/// Shuffle on never changes the song.
struct XmbPlayOrder: Equatable {
    enum Step: Equatable {
        /// No song that way: either end of the queue with Repeat off.
        case none
        /// The same song again from its start.
        case restart
        case go(Int)
    }

    private(set) var count = 0
    /// The queue index of the song that is on.
    private(set) var index = 0
    private(set) var order: [Int] = []
    private(set) var shuffle = false
    var repeatMode = XmbRepeat.all

    /// One song going round: no queue, Repeat One, or a queue of one on Repeat All.
    var loops: Bool { count == 0 || repeatMode == .one || (count == 1 && repeatMode == .all) }

    mutating func set(count: Int, index: Int, shuffled: ([Int]) -> [Int] = { $0.shuffled() }) {
        self.count = max(count, 0)
        self.index = min(max(index, 0), max(self.count - 1, 0))
        reorder(shuffled)
    }

    mutating func setShuffle(_ on: Bool, shuffled: ([Int]) -> [Int] = { $0.shuffled() }) {
        shuffle = on
        reorder(shuffled)
    }

    private mutating func reorder(_ shuffled: ([Int]) -> [Int]) {
        order = shuffle && count > 1 ? [index] + shuffled((0 ..< count).filter { $0 != index }) : Array(0 ..< count)
    }

    /// Next (1) or Previous (-1) in the playing order. Previous more than three seconds into a song starts it again,
    /// as players do.
    mutating func skip(_ step: Int, positionMs: Int, shuffled: ([Int]) -> [Int] = { $0.shuffled() }) -> Step {
        guard count > 0 else { return .none }
        if step < 0, positionMs > 3000 { return .restart }
        var at = (order.firstIndex(of: index) ?? 0) + step
        if !order.indices.contains(at) {
            if repeatMode == .off { return .none }
            // Round again; a shuffled queue is shuffled afresh for its next time through.
            if shuffle, step > 0 { order = shuffled(Array(0 ..< count)) }
            at = (at + order.count) % order.count
        }
        index = order[at]
        return .go(index)
    }

    /// The last song ended with Repeat off: back to the first, which waits there paused.
    mutating func rewind() {
        index = order.first ?? 0
    }
}

enum XmbTime {
    /// A length the console's way: 3'32'', or 1:02'05'' past the hour; nothing for 0. The formats are the firmware's.
    static func length(_ ms: Int, hours: String = "%1:%2'%3''", minutes: String = "%1'%2''") -> String {
        guard ms > 0 else { return "" }
        let total = ms / 1000
        let h = String(total / 3600), m = total % 3600 / 60, s = two(total % 60)
        if total >= 3600 {
            return hours.replacingOccurrences(of: "%1", with: h).replacingOccurrences(of: "%2", with: two(m))
                .replacingOccurrences(of: "%3", with: s)
        }
        return minutes.replacingOccurrences(of: "%1", with: String(m)).replacingOccurrences(of: "%2", with: s)
    }

    /// Where a player stands, as VoiceOver says it: 2:47 of 4:10.
    static func spoken(_ ms: Int, of duration: Int) -> String {
        String(localized: "\(clock(ms)) of \(clock(duration))")
    }

    /// A time on a player's clock: 2:47, or 1:02:05 past the hour.
    static func clock(_ ms: Int) -> String {
        let total = max(ms / 1000, 0)
        let m = total % 3600 / 60, s = two(total % 60)
        return total >= 3600 ? "\(total / 3600):\(two(m)):\(s)" : "\(m):\(s)"
    }

    private static func two(_ value: Int) -> String { value < 10 ? "0\(value)" : String(value) }
}

struct XmbTrack: Codable, Equatable, Sendable {
    /// Where the song is: its path under Documents, or its whole path in a folder added with Add Folder.
    let id: String
    /// Its file name.
    let name: String
    /// The folder it is in, written as `id` is; nil loose in the music folder itself.
    let folder: String?
    let size: Int64
    let modified: Double
    var title: String
    var artist: String?
    var album: String?
    var albumArtist: String?
    var number = 0
    var disc = 0
    var durationMs = 0
    /// A picture inside the file.
    var picture = false
}

/// `artist` is nil for an album of several artists' songs (the console's Various Artists).
struct XmbAlbum: Equatable, Sendable {
    let key: String
    let title: String
    let artist: String?
    let tracks: [XmbTrack]
    var cover: String?

    /// What Change Cover changes for the album, and so for all its songs.
    var coverKey: String { "album:" + key }
}

/// The songs gathered as the console lists them. Songs with an album tag are one album per album name and album
/// artist, or per folder when there is no album artist, so two albums of one name by different people stay apart.
/// Songs in a folder of their own with no album tag are an album named after the folder. The rest are listed loose.
struct XmbMusicLibrary: Equatable, Sendable {
    // Android's ogg, oga, opus, amr, awb, mka, weba, wma and MIDI and ringtone formats are left out: AVFoundation does
    // not decode them, and the app has no FFmpeg path for music.
    static let audio: Set<String> = ["mp3", "m4a", "m4b", "aac", "3gp", "flac", "wav", "aif", "aiff", "aifc", "caf", "alac"]
    static let images: Set<String> = ["jpg", "jpeg", "png", "webp"]
    /// The names a cover goes by beside the songs, best first.
    private static let covers = ["cover", "folder", "front", "album", "albumart", "albumartsmall"]

    private(set) var albums: [XmbAlbum] = []
    private(set) var loose: [XmbTrack] = []
    private(set) var byId: [String: XmbTrack] = [:]
    private var albumOf: [String: Int] = [:]
    private var looseCovers: [String: String] = [:]
    private var chosen: Set<String> = []

    init() {}

    /// `looseCovers` are the covers of the loose songs that have one, by song; `chosen` the cover keys whose cover
    /// was given with Change Cover.
    init(albums: [XmbAlbum], loose: [XmbTrack], looseCovers: [String: String], chosen: Set<String>) {
        self.albums = albums
        self.loose = loose
        self.looseCovers = looseCovers
        self.chosen = chosen
        for (index, album) in albums.enumerated() {
            for track in album.tracks {
                albumOf[track.id] = index
                byId[track.id] = track
            }
        }
        for track in loose { byId[track.id] = track }
    }

    var all: [XmbTrack] { albums.flatMap(\.tracks) + loose }

    func album(of id: String) -> XmbAlbum? { albumOf[id].map { albums[$0] } }

    /// The cover to show for a song: its album's, else its own picture.
    func cover(of id: String) -> String? { album(of: id)?.cover ?? looseCovers[id] }

    /// Whose cover Change Cover changes for a song: its album's, else the song's own.
    func coverKey(_ id: String) -> String { album(of: id)?.coverKey ?? "song:" + id }

    func isChosen(_ coverKey: String) -> Bool { chosen.contains(coverKey) }

    /// The songs of the added folder at `path`, album by album.
    func inFolder(_ path: String) -> [XmbTrack] { all.filter { $0.id.hasPrefix(path + "/") } }

    /// `tracks`, in the order they were found, as albums without their covers yet (by title) and loose songs (by title).
    static func gather(_ tracks: [XmbTrack]) -> (albums: [XmbAlbum], loose: [XmbTrack]) {
        var keys: [String] = []
        var groups: [String: [XmbTrack]] = [:]
        var loose: [XmbTrack] = []
        for track in tracks {
            let key: String
            if let album = track.album, !album.trimmingCharacters(in: .whitespaces).isEmpty {
                key = "tag:" + album.lowercased() + "\u{0}" + (track.albumArtist?.lowercased() ?? track.folder ?? "")
            } else if let folder = track.folder {
                key = "dir:" + folder
            } else {
                loose.append(track)
                continue
            }
            if groups[key] == nil { keys.append(key) }
            groups[key, default: []].append(track)
        }
        let albums = keys.map { key -> XmbAlbum in
            // The found order settles ties, as a stable sort would.
            let songs = (groups[key] ?? []).enumerated().sorted {
                ($0.element.disc, $0.element.number, $0.element.title.lowercased(), $0.offset)
                    < ($1.element.disc, $1.element.number, $1.element.title.lowercased(), $1.offset)
            }.map(\.element)
            let artists = songs.compactMap(\.artist).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            return XmbAlbum(key: key, title: songs.lazy.compactMap(\.album).first ?? folderName(songs[0]),
                            artist: songs.lazy.compactMap(\.albumArtist).first ?? (artists.count == 1 ? artists[0] : nil),
                            tracks: songs, cover: nil)
        }
        return (byTitle(albums, \.title), byTitle(loose, \.title))
    }

    private static func byTitle<T>(_ items: [T], _ title: KeyPath<T, String>) -> [T] {
        items.enumerated().sorted {
            ($0.element[keyPath: title].lowercased(), $0.offset) < ($1.element[keyPath: title].lowercased(), $1.offset)
        }.map(\.element)
    }

    /// An untagged album's name: its folder's.
    private static func folderName(_ track: XmbTrack) -> String {
        track.folder.map { ($0 as NSString).lastPathComponent } ?? track.title
    }

    /// The cover among a folder's pictures: by name, then Windows Media Player's AlbumArt_{GUID}_Large, then the biggest.
    static func pickCover<T>(_ images: [T], name: (T) -> String, size: (T) -> Int64) -> T? {
        func base(_ image: T) -> String { (name(image) as NSString).deletingPathExtension.lowercased() }
        for cover in covers {
            if let hit = images.first(where: { base($0) == cover }) { return hit }
        }
        // Any name will do: of a cover, a booklet page and a disc scan, the front is the likeliest.
        return images.first { base($0).hasPrefix("albumart") } ?? images.max { size($0) < size($1) }
    }

    /// A zip entry's folders and name under the album; nil for one left out: outside it, hidden, or neither a song
    /// nor a picture.
    static func zipPath(_ entry: String) -> [String]? {
        let parts = entry.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init).filter { $0 != "." }
        guard !parts.isEmpty, parts.count <= 5, !parts.contains(where: { $0 == ".." || $0.hasPrefix(".") || $0 == "__MACOSX" })
        else { return nil }
        let format = (parts[parts.count - 1] as NSString).pathExtension.lowercased()
        return audio.contains(format) || images.contains(format) ? parts : nil
    }

    /// `name`, or `name (1)`, `name (2)` and so on before its extension, whichever `taken` does not have yet.
    static func freeName(_ name: String, isFolder: Bool = false, taken: (String) -> Bool) -> String {
        guard taken(name) else { return name }
        let format = isFolder ? "" : (name as NSString).pathExtension
        let stem = format.isEmpty ? name : (name as NSString).deletingPathExtension
        var n = 1
        while true {
            let next = "\(stem) (\(n))" + (format.isEmpty ? "" : "." + format)
            if !taken(next) { return next }
            n += 1
        }
    }

    /// The console's icon for a song with no cover, by its format.
    static func formatIcon(_ name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "mp3": return "music_tex_mp3"
        case "m4a", "aac", "3gp": return "music_tex_aac"
        case "wav": return "music_tex_wav"
        default: return "music_tex_default"
        }
    }
}
