// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Music column and its Music Settings folder, ported from Android's XmbMusic.kt.

import AVFoundation
import SwiftUI

/// What Music Settings keeps between builds that is not the music's own: whether the core's sounds folder has a trophy sound.
@MainActor @Observable final class XmbMusicColumn {
    static let shared = XmbMusicColumn()

    private(set) var trophySound = false

    @ObservationIgnored private var looked = false
    @ObservationIgnored private var test: AVAudioPlayer?

    /// Looks in the sounds folder the first time Music Settings is built; after that, when this column changes it.
    func lookOnce() {
        guard !looked else { return }
        looked = true
        Task { await look() }
    }

    func look() async {
        guard let sounds = await XmbMedia.soundsFolder() else { return }
        let trophy = FileManager.default.fileExists(atPath: sounds.appendingPathComponent(SoundRouting.trophyName + ".wav").path)
        if trophySound != trophy { trophySound = trophy }
    }

    /// The trophy sound once, at its volume, as Test plays it.
    func playTrophy() {
        Task {
            guard let sounds = await XmbMedia.soundsFolder(),
                  let player = try? AVAudioPlayer(contentsOf: sounds.appendingPathComponent(SoundRouting.trophyName + ".wav")) else { return }
            let percent = XmbConsoleSettingsStore.shared.int(SoundRouting.trophyVolumeKey, SoundRouting.defaultTrophyPercent)
            player.volume = Float(min(max(percent, 0), 100)) / 100
            test = player
            player.play()
        }
    }

    /// Remove: trophies unlock silently again.
    func removeTrophy() {
        Task {
            guard let sounds = await XmbMedia.soundsFolder() else { return }
            try? FileManager.default.removeItem(at: sounds.appendingPathComponent(SoundRouting.trophyName + ".wav"))
            await look()
        }
    }
}

extension XmbHost {
    /// New Playlist, the last option while the playlists are offered; the playlists are "offer." and their name, so no
    /// name can be taken for it.
    static let newPlaylistOption = "offer"

    /// The Music column as Android builds it: Now Playing while a song is on, Music Settings, the three ways to add
    /// music, the built-in track, the playlists, the added folders, the albums and the songs of no album.
    func musicSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "music", title: firmware.text(XmbRco.ingame, "msg_music", String(localized: "Music")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_music", symbol: "music.note"), items: []))
        let lib = media.library
        var items: [XmbNode] = []

        // Now Playing heads the column while a song of the player's is on, as the console's does.
        if media.enabled, let song = media.current {
            let track = lib.byId[song]
            let parts = [nowPlayingWord, track?.artist, playingNow ? nil : pausedWord].compactMap { $0 }
            items.append(section.action(XmbNode(
                id: "music.now", title: track?.title ?? Self.stem(song),
                subtitle: parts.joined(separator: "   "),
                icon: lib.cover(of: song).map { .game(path: $0) } ?? nowIcon, kind: .action)) { [weak self] in
                self?.openNowPlaying()
            })
        }
        items.append(musicSettingsNode(&section))
        items.append(section.action(XmbNode(id: "music.import", title: String(localized: "Import Music"),
                                            subtitle: String(localized: "Add songs to the music folder"),
                                            icon: trackIcon, kind: .action)) { [weak self] in self?.pick(.music(.songs)) })
        items.append(section.action(XmbNode(id: "music.importAlbum", title: String(localized: "Import Album"),
                                            subtitle: String(localized: "Add a folder or a .zip of songs, covers and all"),
                                            icon: albumIcon, kind: .action)) { [weak self] in self?.pick(.music(.album)) })
        items.append(section.action(XmbNode(id: "music.addFolder", title: String(localized: "Add Folder"),
                                            subtitle: String(localized: "Play the songs in a folder on this device or a drive, without copying them"),
                                            icon: folderIcon, kind: .action)) { [weak self] in self?.pick(.music(.folder)) })

        let builtinCurrent = media.customName == nil && media.queue.isEmpty
        // Android plays a track of its own here; a build without one in its bundle has nothing to offer.
        if XmbMedia.builtinTrack != nil { items.append(section.action(XmbNode(
            id: "music.builtin", title: String(localized: "Built-in Track"), subtitle: status(builtinCurrent),
            icon: builtinCurrent && playingNow ? trackIcon : albumIcon, kind: .action)) { [weak self] in
            guard let media = self?.media else { return }
            if builtinCurrent, !media.enabled {
                media.setEnabled(true)
            } else if builtinCurrent {
                media.playPause()
            } else {
                media.playBuiltin()
                if !media.enabled { media.setEnabled(true) }
            }
        }) }
        items.append(playlistsNode(&section))

        // Each added folder: its songs where they are, album by album, then Remove from Music.
        for path in media.folders {
            let id = "music.folder." + path
            let inFolder = lib.inFolder(path)
            var rows: [XmbNode]
            if isOpen(id) {
                let songs = inFolder.map(\.id)
                rows = inFolder.enumerated().map { i, track in
                    songNode(&section, prefix: id, title: track.title, subtitle: trackSub(track), song: track.id, list: songs, at: i)
                }
                rows.append(section.action(XmbNode(id: id + ".remove", title: String(localized: "Remove from Music"),
                                                   subtitle: nil, icon: folderIcon, kind: .action)) { [weak self] in
                    Task { await self?.media.removeFolder(path) }
                })
            } else {
                rows = [Self.closedRow(id)]
            }
            items.append(XmbNode(id: id, title: (path as NSString).lastPathComponent, subtitle: tracksWord(inFolder.count),
                                 icon: folderIcon, kind: .folder(rows)))
        }

        // Each album a folder under its cover: its songs in disc and track order.
        for album in lib.albums {
            let id = "music.album." + album.key
            var rows = [Self.closedRow(id)]
            if isOpen(id) {
                let songs = album.tracks.map(\.id)
                rows = album.tracks.enumerated().map { i, track in
                    // An album of one artist names the length; of several, whose song it is too.
                    let sub = album.artist != nil ? nonEmpty(musicLength(track.durationMs)) : trackSub(track)
                    return songNode(&section, prefix: id, title: track.title, subtitle: sub, song: track.id, list: songs, at: i)
                }
            }
            let options = coverOptions(album.coverKey)
            section.options[id] = { [weak self] option in self?.coverOption(option, key: album.coverKey) }
            items.append(XmbNode(id: id, title: album.title,
                                 subtitle: [album.artist ?? variousArtists, tracksWord(album.tracks.count)].joined(separator: "   "),
                                 icon: album.cover.map { .game(path: $0) } ?? albumIcon, kind: .folder(rows), options: options))
        }

        let loose = lib.loose.map(\.id)
        for (i, track) in lib.loose.enumerated() {
            items.append(songNode(&section, prefix: "music.track", title: track.title, subtitle: trackSub(track), song: track.id,
                                  list: loose, at: i))
        }
        section.column.items = items
        // The column opens on what is playing, or on the track that comes with the app.
        section.column.defaultFocus = items.firstIndex { $0.id == "music.now" } ?? items.firstIndex { $0.id == "music.builtin" } ?? 0
        return section
    }

    /// Now Playing over the XMB.
    func openNowPlaying() {
        media.nowPlaying = XmbNowPlayingPanel()
    }

    // MARK: - Words and icons

    private var trackIcon: XmbIcon { .firmware(rco: XmbRco.music, name: "music_tex_default", symbol: "music.note") }
    private var albumIcon: XmbIcon { .firmware(rco: XmbRco.music, name: "music_tex_album_default", symbol: "square.stack") }
    private var nowIcon: XmbIcon { .firmware(rco: XmbRco.music, name: "tex_now_playing", symbol: "play.circle") }
    private var listIcon: XmbIcon { .firmware(rco: XmbRco.music, name: "tex_playlist", symbol: "music.note.list") }
    private var folderIcon: XmbIcon { .firmware(rco: XmbRco.video, name: "item_tex_plain_folder", symbol: "folder") }
    private var nowPlayingWord: String { String(localized: "Now Playing") }
    private var playWord: String { firmware.text(XmbRco.full, "msg_play", String(localized: "Play")) }
    private var pausedWord: String { firmware.text(XmbRco.full, "msg_pause", String(localized: "Stopped")) }
    private var variousArtists: String { firmware.text(XmbRco.full, "msg_various_artists", String(localized: "Various Artists")) }

    private func tracksWord(_ count: Int) -> String {
        firmware.text(XmbRco.full, "msg_tracks_count_value", String(localized: "%1 Tracks")).replacingOccurrences(of: "%1", with: String(count))
    }

    /// A length the console's way, in the firmware's own words when it has them.
    func musicLength(_ ms: Int) -> String {
        XmbTime.length(ms, hours: firmware.text(XmbRco.full, "msg_time_num2", "%1:%2'%3''"),
                       minutes: firmware.text(XmbRco.full, "msg_time_num3", "%1'%2''"))
    }

    private func nonEmpty(_ text: String) -> String? { text.isEmpty ? nil : text }

    private func trackSub(_ track: XmbTrack) -> String? {
        nonEmpty([track.artist, nonEmpty(musicLength(track.durationMs))].compactMap { $0 }.joined(separator: "   "))
    }

    private static func stem(_ song: String) -> String { (XmbMedia.songName(song) as NSString).deletingPathExtension }

    /// A folder's one row while it is shut, so a large library is not built on every change.
    private static func closedRow(_ id: String) -> XmbNode { XmbNode(id: id + ".closed", title: "", subtitle: nil, icon: .none, kind: .info) }

    private var playingNow: Bool { media.enabled && !media.userPaused }

    private func status(_ isCurrent: Bool) -> String? {
        guard isCurrent else { return nil }
        return playingNow ? playWord : pausedWord
    }

    // MARK: - Songs

    /// A song: Cross plays it and on through `list` from it, or opens Now Playing on the one playing. Triangle has
    /// its playlist options, Delete for a song in the music folder, and its cover's.
    private func songNode(_ section: inout XmbSection, prefix: String, title: String, subtitle: String?, song: String,
                          list: [String], at index: Int, extra: [XmbNode.Option] = [], playlist: String? = nil) -> XmbNode {
        let name = XmbMedia.songName(song)
        let id = "\(prefix).\(index).\(name)"
        let current = media.enabled && media.current == song
        let coverKey = media.library.coverKey(song)
        var options: [XmbNode.Option]
        if playlistOffer == id {
            options = media.playlists.names.map { .init(id: "offer." + $0, title: $0) }
                + [.init(id: Self.newPlaylistOption, title: String(localized: "New Playlist"))]
        } else {
            options = extra + [.init(id: "playlist.add", title: String(localized: "Add to Playlist"))]
            if XmbMedia.owns(song) { options.append(.init(id: "delete", title: String(localized: "Delete"))) }
            options += coverOptions(coverKey)
        }
        section.options[id] = { [weak self] option in
            guard let self else { return }
            switch option {
            case "playlist.add":
                offerPlaylists(on: id)
            case Self.newPlaylistOption:
                type(String(localized: "Playlist Name")) { [weak self] typed in
                    let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !name.isEmpty { self?.addToPlaylist(song, name) }
                }
            case "playlist.remove":
                if let playlist { media.removeFromPlaylist(song, playlist) }
            case "delete":
                ask(String(localized: "Delete \(title)?"), buttons: [yes, no], focus: 1) { [weak self] button in
                    if button == 0 { Task { await self?.media.deleteSong(song) } }
                }
            default:
                if option.hasPrefix("offer.") {
                    addToPlaylist(song, String(option.dropFirst("offer.".count)))
                } else {
                    coverOption(option, key: coverKey)
                }
            }
        }
        let icon: XmbIcon = media.library.cover(of: song).map { .game(path: $0) }
            ?? (current && playingNow ? trackIcon
                : .firmware(rco: XmbRco.music, name: XmbMusicLibrary.formatIcon(name), symbol: "music.note"))
        return section.action(XmbNode(id: id, title: title, subtitle: status(current) ?? subtitle, icon: icon, kind: .action,
                                      options: options)) { [weak self] in
            if current { self?.openNowPlaying() } else { self?.media.playQueue(list, start: index) }
        }
    }

    /// Add to Playlist: `id` is built again with the playlists for its options, which open as a second panel once the
    /// rebuild is in, as Android's offer() opens one. The panel keeps the rows it opened with, so the usual options
    /// come back at once.
    func offerPlaylists(on id: String) {
        playlistOffer = id
        Task { @MainActor [weak self] in
            for _ in 0 ..< 20 {
                await Task.yield()
                guard let self else { break }
                if state.focused?.id == id, state.focused?.options.last?.id == Self.newPlaylistOption {
                    perform(.options)
                    break
                }
            }
            self?.playlistOffer = nil
        }
    }

    private func addToPlaylist(_ song: String, _ name: String) {
        media.addToPlaylist(song, name)
        notice(String(localized: "Added to \(name)."), error: false)
    }

    // MARK: - Covers

    private func coverOptions(_ key: String) -> [XmbNode.Option] {
        var options = [XmbNode.Option(id: "cover.change", title: String(localized: "Change Cover"))]
        if media.library.isChosen(key) { options.append(.init(id: "cover.original", title: String(localized: "Original Cover"))) }
        return options
    }

    private func coverOption(_ option: String, key: String) {
        switch option {
        case "cover.change":
            pick(.music(.cover(key)))
        case "cover.original":
            Task { await media.clearCover(key) }
        default:
            break
        }
    }

    // MARK: - Adding music

    /// What the Files picker brought for the Music column, said as Android says it.
    func musicImported(_ urls: [URL], _ pick: XmbMedia.Pick) {
        Task {
            let added = await media.add(urls, for: pick)
            switch pick {
            case .songs, .album:
                notice(String(localized: "Tracks imported: \(added)"), error: added == 0)
            case .folder:
                if added == 0 { notice(firmware.text(XmbRco.full, "msg_error_no_title", String(localized: "There are no titles."))) }
            case .cover:
                if added == 0 { firmware.play(.error) }
            case .track:
                break
            case .soundPack:
                await XmbMusicColumn.shared.look()
                notice(added > 0 ? String(localized: "Sound effects imported: \(added)")
                    : String(localized: "No menu sounds were found. Use RPCS3's names, such as snd_cursor.wav."), error: added == 0)
            case .trophySound:
                await XmbMusicColumn.shared.look()
                if added > 0 {
                    XmbMusicColumn.shared.playTrophy()
                } else {
                    notice(String(localized: "That file couldn't be used. Pick a .wav of 4 MB or less."))
                }
            }
        }
    }

    // MARK: - Playlists

    /// Playlists: New Playlist, then each one a list of its own: Play All, Add Songs (every song with a switch), its
    /// songs, and Delete Playlist.
    private func playlistsNode(_ section: inout XmbSection) -> XmbNode {
        var rows = [section.action(XmbNode(id: "music.playlists.new", title: String(localized: "New Playlist"), subtitle: nil,
                                           icon: listIcon, kind: .action)) { [weak self] in
            self?.type(String(localized: "Playlist Name")) { [weak self] typed in
                let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { self?.media.createPlaylist(name) }
            }
        }]
        for name in media.playlists.names {
            let id = "music.playlist." + name
            var children = [Self.closedRow(id)]
            if isOpen(id) {
                let songs = media.playlistSongs(name)
                children = []
                if songs.isEmpty {
                    children.append(XmbNode(id: id + ".none", title: String(localized: "This playlist is empty."), subtitle: nil,
                                            icon: listIcon, kind: .info))
                } else {
                    children.append(section.action(XmbNode(id: id + ".all", title: String(localized: "Play All"), subtitle: nil,
                                                           icon: trackIcon, kind: .action)) { [weak self] in
                        self?.media.playQueue(songs, start: 0)
                    })
                }
                children.append(addSongsNode(&section, playlist: name))
                for (i, song) in songs.enumerated() {
                    let track = media.library.byId[song]
                    children.append(songNode(&section, prefix: id, title: track?.title ?? Self.stem(song), subtitle: track.flatMap(trackSub),
                                             song: song, list: songs, at: i,
                                             extra: [.init(id: "playlist.remove", title: String(localized: "Remove from Playlist"))],
                                             playlist: name))
                }
                children.append(section.action(XmbNode(id: id + ".delete", title: String(localized: "Delete Playlist"), subtitle: nil,
                                                       icon: listIcon, kind: .action)) { [weak self] in
                    guard let self else { return }
                    ask(String(localized: "Delete \(name)?"), buttons: [yes, no], focus: 1) { [weak self] button in
                        if button == 0 { self?.media.deletePlaylist(name) }
                    }
                })
            }
            rows.append(XmbNode(id: id, title: name, subtitle: String(localized: "\(media.playlists.songs(name).count) tracks"),
                                icon: listIcon, kind: .folder(children)))
        }
        return XmbNode(id: "music.playlists", title: String(localized: "Playlists"), subtitle: String(localized: "Make a playlist, then add to it"),
                       icon: listIcon, kind: .folder(rows))
    }

    /// Every song of the library with a switch for whether it is in the playlist.
    private func addSongsNode(_ section: inout XmbSection, playlist: String) -> XmbNode {
        let id = "music.playlist." + playlist + ".add"
        var rows = [Self.closedRow(id)]
        if isOpen(id) {
            let inList = Set(media.playlists.songs(playlist))
            rows = media.library.all.map { track in
                let row = id + "." + track.id
                let added = inList.contains(track.id)
                section.picks[row] = { [weak self] choice in
                    if choice == 0 { self?.media.addToPlaylist(track.id, playlist) } else { self?.media.removeFromPlaylist(track.id, playlist) }
                }
                return XmbNode(id: row, title: track.title, subtitle: added ? on : off,
                               icon: media.library.cover(of: track.id).map { .game(path: $0) } ?? albumIcon,
                               kind: .setting(choices: [on, off], selected: added ? 0 : 1))
            }
        }
        return XmbNode(id: id, title: String(localized: "Add Songs"), subtitle: nil, icon: listIcon, kind: .folder(rows))
    }

    // MARK: - Music Settings

    /// Music Settings: the music behind the XMB, the menu sounds and the trophy sound, as Android's settings have them.
    /// The Settings column can show the same node.
    func musicSettingsNode(_ section: inout XmbSection) -> XmbNode {
        let icon = XmbIcon.firmware(rco: XmbRco.sysconf, name: "tex_music", symbol: "music.note")
        let column = XmbMusicColumn.shared
        column.lookOnce()
        let store = XmbConsoleSettingsStore.shared
        var rows = [consoleToggle(&section, "music.game", String(localized: "Game Music"), icon: icon, isOn: settings.xmbGameMusic,
                                  help: String(localized: "Play a game's music while it is selected.")) { [weak self] on in
            self?.settings.xmbGameMusic = on
        }]
        section.picks["music.library"] = { [weak self] choice in self?.media.setEnabled(choice == 0) }
        rows.append(XmbNode(
            id: "music.library", title: String(localized: "Library Music"),
            subtitle: [media.enabled ? on : off,
                       String(localized: "Play ambient music on the game library screen, like a console dashboard. Stops automatically when a game starts, and stays quiet if something else is already playing audio.")]
                .joined(separator: "   "),
            icon: icon, kind: .setting(choices: [on, off], selected: media.enabled ? 0 : 1)))
        if media.enabled {
            rows.append(volumeRow(&section, id: "music.library.volume", title: String(localized: "Library Music Volume"), icon: icon,
                                  percent: media.volumePercent) { [weak self] in self?.media.setVolume($0) })
            let name = media.customName
            rows.append(section.action(XmbNode(
                id: "music.library.choose", title: String(localized: "Choose Music File…"),
                subtitle: name.map { String(localized: "Playing: \($0)") }
                    ?? (XmbMedia.builtinTrack != nil ? String(localized: "Playing the built-in track.") : nil),
                icon: icon, kind: .action)) { [weak self] in self?.pick(.music(.track)) })
            if name != nil {
                rows.append(section.action(XmbNode(id: "music.library.reset", title: String(localized: "Reset to Default"), subtitle: nil,
                                                   icon: icon, kind: .action)) { [weak self] in self?.media.playBuiltin() })
            }
        }
        let menuPercent = store.int(SoundRouting.menuVolumeKey, SoundRouting.defaultMenuPercent)
        let trophyPercent = store.int(SoundRouting.trophyVolumeKey, SoundRouting.defaultTrophyPercent)
        rows.append(volumeRow(&section, id: "music.sfx.volume", title: String(localized: "Sound Effects Volume"), icon: icon,
                              percent: menuPercent) { store.write($0, SoundRouting.menuVolumeKey) })
        rows.append(section.action(XmbNode(
            id: "music.sfx.choose", title: String(localized: "Import Sound Pack…"),
            subtitle: String(localized: "Sounds play only from files you import. Use RPCS3's names (snd_cursor.wav and the others)."),
            icon: icon, kind: .action)) { [weak self] in self?.pick(.music(.soundPack)) })
        // The core keeps the trophy sound as snd_trophy.wav, whatever the picked file was called.
        rows.append(section.action(XmbNode(
            id: "music.trophy.choose", title: String(localized: "Trophy Sound: Choose Sound…"),
            subtitle: column.trophySound ? String(localized: "Using: \(SoundRouting.trophyName).wav")
                : String(localized: "No sound set, so trophies unlock silently."),
            icon: icon, kind: .action)) { [weak self] in self?.pick(.music(.trophySound)) })
        if column.trophySound {
            rows.append(volumeRow(&section, id: "music.trophy.volume", title: String(localized: "Trophy Sound Volume"), icon: icon,
                                  percent: trophyPercent) { store.write($0, SoundRouting.trophyVolumeKey) })
            rows.append(section.action(XmbNode(id: "music.trophy.test", title: String(localized: "Test"),
                                               subtitle: String(localized: "Played when you unlock a trophy."),
                                               icon: icon, kind: .action)) { column.playTrophy() })
            rows.append(section.action(XmbNode(id: "music.trophy.clear", title: String(localized: "Remove"), subtitle: nil,
                                               icon: icon, kind: .action)) { column.removeTrophy() })
        }
        let explanation = firmware.text(XmbRco.sysconf, "msg_music_setting_explanation", "")
        return XmbNode(id: "settings.music", title: firmware.text(XmbRco.sysconf, "msg_music_setting", String(localized: "Music Settings")),
                       subtitle: nonEmpty(explanation), icon: icon, kind: .folder(rows))
    }

    /// 0% to 100% in fives, as Android's slider steps.
    private func volumeRow(_ section: inout XmbSection, id: String, title: String, icon: XmbIcon, percent: Int,
                           set: @escaping @MainActor (Int) -> Void) -> XmbNode {
        let choices = stride(from: 0, through: 100, by: 5).map { "\($0)%" }
        section.picks[id] = { choice in set(choice * 5) }
        return XmbNode(id: id, title: title, subtitle: "\(percent)%", icon: icon,
                       kind: .setting(choices: choices, selected: min(max(Int((Double(percent) / 5).rounded()), 0), 20)))
    }
}
