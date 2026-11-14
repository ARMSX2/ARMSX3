// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Photo column, its playlists and its picture viewer, ported from xmbPhotoColumn, XmbPhotoViewer and XmbPhotos in Android's XmbHome.kt and the photo half of XmbPlaylists.kt.

import Observation
import SwiftUI

/// ARMSX3's pictures (its screenshots, newest first) and the Photo column's playlists: named lists of pictures in the
/// order they were added, kept by their path under Documents so they survive iOS moving the container.
@MainActor @Observable final class XmbPhotos {
    static let shared = XmbPhotos()

    struct Picture: Equatable, Sendable {
        let url: URL
        let modified: Date
    }

    struct Playlist: Codable, Equatable {
        var name: String
        var pictures: [String]
    }

    private(set) var pictures: [Picture] = []
    private(set) var playlists: [Playlist] = []

    @ObservationIgnored private var loaded = false

    nonisolated private static let images: Set<String> = ["png", "jpg", "jpeg", "webp"]
    nonisolated private static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    nonisolated private static var playlistsFile: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("xmb/photo-playlists.json")
    }

    private init() {
        if let data = try? Data(contentsOf: Self.playlistsFile), let saved = try? JSONDecoder().decode([Playlist].self, from: data) {
            playlists = saved
        }
    }

    /// Reads the pictures the first time the column is built; what it changes is set later, from a task.
    func loadOnce() {
        guard !loaded else { return }
        loaded = true
        refresh()
    }

    /// Reads the screenshot folder again.
    func refresh() {
        Task {
            let found = await Task.detached(priority: .utility) { Self.read(ScreenshotStore.folder) }.value
            if pictures != found { pictures = found }
        }
    }

    nonisolated private static func read(_ folder: URL) -> [Picture] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys)) ?? []
        return files.compactMap { file -> Picture? in
            guard images.contains(file.pathExtension.lowercased()), let values = try? file.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true, (values.fileSize ?? 0) > 0 else { return nil }
            return Picture(url: file, modified: values.contentModificationDate ?? .distantPast)
        }
        .sorted { $0.modified > $1.modified }
    }

    // MARK: - Playlists

    nonisolated static func stored(_ url: URL) -> String {
        let root = documents.standardizedFileURL.path + "/"
        let path = url.standardizedFileURL.path
        return path.hasPrefix(root) ? String(path.dropFirst(root.count)) : path
    }

    nonisolated static func url(_ stored: String) -> URL {
        stored.hasPrefix("/") ? URL(fileURLWithPath: stored) : documents.appendingPathComponent(stored)
    }

    /// The pictures of `name` that are still there, in order.
    func files(_ name: String) -> [URL] {
        (playlists.first { $0.name == name }?.pictures ?? []).map(Self.url).filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    func contains(_ name: String, _ url: URL) -> Bool {
        playlists.first { $0.name == name }?.pictures.contains(Self.stored(url)) ?? false
    }

    /// A new, empty playlist; nothing when there is one of that name already.
    func create(_ name: String) {
        guard !playlists.contains(where: { $0.name == name }) else { return }
        playlists.append(Playlist(name: name, pictures: []))
        save()
    }

    /// Adds a picture, making the playlist when it is new.
    func add(_ url: URL, to name: String) {
        let path = Self.stored(url)
        if let at = playlists.firstIndex(where: { $0.name == name }) {
            guard !playlists[at].pictures.contains(path) else { return }
            playlists[at].pictures.append(path)
        } else {
            playlists.append(Playlist(name: name, pictures: [path]))
        }
        save()
    }

    func remove(_ url: URL, from name: String) {
        guard let at = playlists.firstIndex(where: { $0.name == name }) else { return }
        playlists[at].pictures.removeAll { $0 == Self.stored(url) }
        save()
    }

    func delete(_ name: String) {
        playlists.removeAll { $0.name == name }
        save()
    }

    private func save() {
        try? FileManager.default.createDirectory(at: Self.playlistsFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(playlists).write(to: Self.playlistsFile, options: .atomic)
    }
}

private enum PhotoIcons {
    static let album = XmbIcon.firmware(rco: XmbRco.photo, name: "photo_tex_album_default", symbol: "photo.on.rectangle")
    static let list = XmbIcon.firmware(rco: XmbRco.photo, name: "tex_playlist", symbol: "list.bullet.rectangle")
    static let picture = XmbIcon.firmware(rco: XmbRco.photo, name: "photo_tex_default", symbol: "photo")
}

extension XmbHost {
    /// Playlists first, then the screenshots taken in ARMSX3 in an album, newest first. Each picture opens in the
    /// viewer, where Left and Right go through its album or playlist. The console's "There are no images." without any.
    func photoSection() -> XmbSection {
        let photos = XmbPhotos.shared
        photos.loadOnce()
        var section = XmbSection(column: XmbColumn(
            id: "photo", title: firmware.text(XmbRco.ingame, "msg_photo", String(localized: "Photo")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_photo", symbol: "camera"), items: []))
        let newList = section.action(XmbNode(id: "photo.playlists.new", title: String(localized: "New Playlist"), subtitle: nil,
                                             icon: PhotoIcons.list, kind: .action)) { [weak self] in
            self?.type(String(localized: "Playlist Name")) { typed in
                let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { photos.create(name) }
            }
        }
        let lists = photos.playlists.map { photoPlaylist(&section, $0) }
        var items = [XmbNode(id: "photo.playlists", title: String(localized: "Playlists"),
                             subtitle: String(localized: "Make a playlist, then add to it"), icon: PhotoIcons.list,
                             kind: .folder([newList] + lists))]
        let all = photos.pictures.map(\.url)
        if let newest = all.first {
            let title = String(localized: "Screenshots")
            var rows: [XmbNode] = []
            for (index, picture) in photos.pictures.enumerated() {
                let id = "photo.album.screenshots." + picture.url.lastPathComponent
                let options = addOptions(&section, picture.url, id: id)
                rows.append(pictureRow(&section, id: id, picture.url, modified: picture.modified, options: options) {
                    XmbPhotoViewing.shared.open(title: title, icon: PhotoIcons.album, files: all, at: index, slideshow: false)
                })
            }
            items.append(XmbNode(id: "photo.album.screenshots", title: title, subtitle: String(localized: "\(all.count) pictures"),
                                 icon: .game(path: newest.path), kind: .folder(rows)))
        } else {
            items.append(XmbNode(id: "photo.none", title: firmware.text(XmbRco.full, "msg_error_no_image",
                                                                        String(localized: "There are no images.")),
                                 subtitle: nil, icon: PhotoIcons.picture, kind: .info))
        }
        section.column.items = items
        return section
    }

    /// A playlist: Slideshow, Add Pictures (every picture with a switch), its pictures, and Delete Playlist.
    private func photoPlaylist(_ section: inout XmbSection, _ list: XmbPhotos.Playlist) -> XmbNode {
        let photos = XmbPhotos.shared
        let name = list.name
        let id = "photo.playlist." + name
        let files = photos.files(name)
        var rows: [XmbNode] = []
        if files.isEmpty {
            rows.append(XmbNode(id: id + ".none", title: String(localized: "This playlist is empty."), subtitle: nil, icon: PhotoIcons.list,
                                kind: .info))
        } else {
            rows.append(section.action(XmbNode(id: id + ".show", title: String(localized: "Slideshow"), subtitle: nil, icon: PhotoIcons.list,
                                               kind: .action)) {
                XmbPhotoViewing.shared.open(title: name, icon: PhotoIcons.list, files: files, at: 0, slideshow: true)
            })
        }
        let switches = photos.pictures.map { picture -> XmbNode in
            let row = id + ".add." + picture.url.lastPathComponent
            section.picks[row] = { choice in
                if choice == 0 { photos.add(picture.url, to: name) } else { photos.remove(picture.url, from: name) }
            }
            return XmbNode(id: row, title: picture.url.deletingPathExtension().lastPathComponent, subtitle: nil,
                           icon: .game(path: picture.url.path),
                           kind: .setting(choices: [on, off], selected: photos.contains(name, picture.url) ? 0 : 1))
        }
        rows.append(XmbNode(id: id + ".add", title: String(localized: "Add Pictures"), subtitle: nil, icon: PhotoIcons.list,
                            kind: .folder(switches)))
        for (index, file) in files.enumerated() {
            let row = "\(id).\(index).\(file.lastPathComponent)"
            section.options[row] = { _ in photos.remove(file, from: name) }
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            rows.append(pictureRow(&section, id: row, file, modified: modified,
                                   options: [.init(id: "remove", title: String(localized: "Remove from Playlist"))]) {
                XmbPhotoViewing.shared.open(title: name, icon: PhotoIcons.list, files: files, at: index, slideshow: false)
            })
        }
        rows.append(section.action(XmbNode(id: id + ".delete", title: String(localized: "Delete Playlist"), subtitle: nil,
                                           icon: PhotoIcons.list, kind: .action)) { [weak self] in
            guard let self else { return }
            ask(String(localized: "Delete \(name)?"), buttons: [yes, no], focus: 1) { button in
                if button == 0 { photos.delete(name) }
            }
        })
        return XmbNode(id: id, title: name, subtitle: String(localized: "\(list.pictures.count) pictures"), icon: PhotoIcons.list,
                       kind: .folder(rows))
    }

    /// A picture: its name, and when it was taken.
    private func pictureRow(_ section: inout XmbSection, id: String, _ file: URL, modified: Date?, options: [XmbNode.Option],
                            open: @escaping @MainActor () -> Void) -> XmbNode {
        section.action(XmbNode(id: id, title: file.deletingPathExtension().lastPathComponent,
                               subtitle: modified.map(XmbStamp.string), icon: .game(path: file.path),
                               kind: .action, options: options), open)
    }

    /// Triangle's Add to Playlist, and once picked, the playlists there are and New Playlist in its place.
    private func addOptions(_ section: inout XmbSection, _ file: URL, id: String) -> [XmbNode.Option] {
        let photos = XmbPhotos.shared
        section.options[id] = { [weak self] option in
            guard let self else { return }
            if option == "add" {
                offerPlaylists(on: id)
            } else if option == Self.newPlaylistOption {
                type(String(localized: "Playlist Name")) { [weak self] typed in
                    let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty else { return }
                    photos.add(file, to: name)
                    self?.notice(String(localized: "Added to \(name)."), error: false)
                }
            } else if option.hasPrefix("offer.") {
                let name = String(option.dropFirst("offer.".count))
                photos.add(file, to: name)
                notice(String(localized: "Added to \(name)."), error: false)
            }
        }
        guard playlistOffer == id else { return [.init(id: "add", title: String(localized: "Add to Playlist"))] }
        return photos.playlists.map { .init(id: "offer." + $0.name, title: $0.name) }
            + [.init(id: Self.newPlaylistOption, title: String(localized: "New Playlist"))]
    }
}

// MARK: - The viewer

/// The pictures of an album or a playlist, one at a time as large as the panel allows, on black; Left and Right page,
/// and a slideshow goes on by itself, a picture every five seconds, round again at the end. Circle closes.
@MainActor @Observable final class XmbPhotoViewing {
    static let shared = XmbPhotoViewing()

    struct Show: Equatable {
        let title: String
        let icon: XmbIcon
        let files: [URL]
        let slideshow: Bool
    }

    private(set) var show: Show?
    var index = 0

    @ObservationIgnored private var slides: Task<Void, Never>?

    func open(title: String, icon: XmbIcon, files: [URL], at index: Int, slideshow: Bool) {
        guard files.indices.contains(index) else { return }
        self.index = index
        show = Show(title: title, icon: icon, files: files, slideshow: slideshow)
        slides?.cancel()
        guard slideshow, files.count > 1 else { return }
        slides = Task { [weak self] in
            while true {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, let self, let show = self.show else { return }
                withAnimation { self.index = (self.index + 1) % show.files.count }
            }
        }
    }

    func close() {
        slides?.cancel()
        show = nil
    }

    /// A press while the viewer is up. Answers the console's sound for it, or nil for silence.
    func apply(_ command: MenuControllerCommand) -> XmbSound? {
        guard let show else { return nil }
        switch command {
        case .left, .right:
            let next = min(max(index + (command == .left ? -1 : 1), 0), show.files.count - 1)
            if next != index { withAnimation { index = next } }
            return nil
        case .back:
            close()
            return .cancel
        default:
            return nil
        }
    }
}

/// The viewer as an XMB panel: the album's icon and title, Back in the corner, and the pictures under them.
struct XmbPhotoViewer: View {
    let firmware: XmbFirmware
    let m: XmbMetrics

    var body: some View {
        @Bindable var viewing = XmbPhotoViewing.shared
        if let show = viewing.show {
            VStack(spacing: 0) {
                HStack(spacing: 16 * m.u) {
                    XmbIconView(icon: show.icon, firmware: firmware)
                        .frame(width: 44 * m.u, height: 44 * m.u)
                    Text(verbatim: show.title)
                        .font(firmware.font(size: m.titleFont))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .xmbShadow()
                        .frame(maxWidth: .infinity, alignment: .leading)
                    XmbHintChip(circle: true, label: firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")),
                                firmware: firmware, m: m) {
                        viewing.close()
                        firmware.play(.cancel)
                    }
                }
                .padding(.leading, 40 * m.u)
                .padding(.trailing, 24 * m.u)
                .padding(.top, 18 * m.u)
                .padding(.bottom, 8 * m.u)
                TabView(selection: $viewing.index) {
                    ForEach(show.files.indices, id: \.self) { index in
                        XmbPicture(path: show.files[index].path, fit: true) { EmptyView() }
                            .tag(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .background(Color.black)
            }
            .background(XmbPanelGround().ignoresSafeArea())
            .transition(.opacity.animation(.xmb(0.2)))
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { viewing.close() }
        }
    }
}
