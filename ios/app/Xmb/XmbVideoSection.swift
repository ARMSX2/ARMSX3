// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Video column, its folders, videos and their options, ported from xmbVideoColumn in Android's XmbVideo.kt.

import SwiftUI

private enum VideoIcons {
    static let film = XmbIcon.firmware(rco: XmbRco.video, name: "video_tex_default", symbol: "film")
    static let folder = XmbIcon.firmware(rco: XmbRco.video, name: "item_tex_plain_folder", symbol: "folder")
    static let album = XmbIcon.firmware(rco: XmbRco.video, name: "video_tex_album_default", symbol: "rectangle.stack")
    static let loading = XmbIcon.firmware(rco: XmbRco.video, name: "tex_loading_icon", symbol: "hourglass")
}

extension XmbHost {
    /// Import Video and Add Folder, then each added folder, then the folders and videos in ARMSX3's video folder, straight
    /// in the column. A video shows a frame and its length (and where it was left); Cross plays it from there, and
    /// Triangle has Play from Beginning, and Delete for one in ARMSX3's folder.
    func videoSection() -> XmbSection {
        let videos = XmbVideos.shared
        var section = XmbSection(column: XmbColumn(
            id: "video", title: firmware.text(XmbRco.ingame, "msg_video", String(localized: "Video")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_video", symbol: "film"), items: []))
        var items = [
            section.action(XmbNode(id: "video.import", title: String(localized: "Import Video"),
                                   subtitle: String(localized: "Copy videos into ARMSX3's video folder"),
                                   icon: VideoIcons.film, kind: .action)) { [weak self] in self?.pick(.videos(.videos)) },
            section.action(XmbNode(id: "video.addFolder", title: String(localized: "Add Folder"),
                                   subtitle: String(localized: "Show the videos in a folder on this device or a drive"),
                                   icon: VideoIcons.folder, kind: .action)) { [weak self] in self?.pick(.videos(.folder)) },
        ]
        // Added folders, each ending in Remove from Video.
        for folder in videos.folders {
            let remove = section.action(XmbNode(id: "video.remove." + folder.id, title: String(localized: "Remove from Video"),
                                                subtitle: nil, icon: VideoIcons.folder, kind: .action)) { [weak self] in
                videos.removeFolder(folder)
                self?.perform(.back)
            }
            items.append(videoFolder(&section, key: XmbVideos.folderKey(folder), title: folder.name, icon: VideoIcons.album,
                                     dir: videos.url(of: folder), own: false, extra: [remove]))
        }
        // ARMSX3's own folder: its folders, then its videos.
        let ownKey = XmbVideos.ownKey()
        let ownEntries = videos.listing(ownKey, in: XmbVideos.ownFolder, own: true)
        items += videoRows(&section, ownEntries ?? [], listing: ownKey)
        if ownEntries?.isEmpty == true, videos.folders.isEmpty {
            items.append(noTitles("video.none"))
        }
        section.column.items = items
        // With videos of its own, the column opens on the first entry of ARMSX3's folder, past the rows above it.
        section.column.defaultFocus = ownEntries?.contains { !$0.dir } == true ? 2 + videos.folders.count : 0
        return section
    }

    /// What the Files picker brought for Import Video or Add Folder.
    func videosPicked(_ urls: [URL], for pick: XmbVideos.Pick) {
        let videos = XmbVideos.shared
        switch pick {
        case .videos:
            guard !urls.isEmpty else { return }
            Task {
                let count = await videos.importVideos(urls)
                notice(String(localized: "Videos imported: \(count)"), error: count == 0)
            }
        case .folder:
            if let url = urls.first { videos.addFolder(url) }
        }
    }

    private func noTitles(_ id: String) -> XmbNode {
        XmbNode(id: id, title: firmware.text(XmbRco.full, "msg_error_no_title", String(localized: "There are no titles.")),
                subtitle: nil, icon: VideoIcons.film, kind: .info)
    }

    /// A folder's rows: its folders, then its videos.
    private func videoRows(_ section: inout XmbSection, _ entries: [XmbVideos.Entry], listing key: String) -> [XmbNode] {
        let clips = entries.filter { !$0.dir }
        return entries.map { entry in
            if entry.dir {
                return videoFolder(&section, key: entry.key + "/", title: entry.name, icon: VideoIcons.folder, dir: entry.url,
                                   own: entry.own)
            }
            return videoRow(&section, clips, clips.firstIndex(of: entry) ?? 0, listing: key)
        }
    }

    /// A folder, read while it is open: its folders, then its videos, then `extra`.
    private func videoFolder(_ section: inout XmbSection, key: String, title: String, icon: XmbIcon, dir: URL?, own: Bool,
                             extra: [XmbNode] = []) -> XmbNode {
        let id = "video.dir." + key
        let loading = XmbNode(id: id + ".loading", title: String(localized: "Loading…"), subtitle: nil, icon: VideoIcons.loading, kind: .info)
        var children = [loading]
        if isOpen(id), let dir {
            if let entries = XmbVideos.shared.listing(key, in: dir, own: own) {
                children = entries.isEmpty ? [noTitles(id + ".none")] : videoRows(&section, entries, listing: key)
            }
        } else if isOpen(id) {
            // The folder's bookmark no longer opens: it reads as empty, and can still be removed.
            children = [noTitles(id + ".none")]
        }
        return XmbNode(id: id, title: title, subtitle: nil, icon: icon, kind: .folder(children + extra))
    }

    /// One video: a frame of it for an icon once read, its length, and where it was left.
    private func videoRow(_ section: inout XmbSection, _ list: [XmbVideos.Entry], _ index: Int, listing key: String) -> XmbNode {
        let videos = XmbVideos.shared
        let entry = list[index]
        let meta = videos.meta(entry)
        let left = videos.resumeAt(entry)
        var parts: [String] = []
        if let duration = meta?.durationMs, duration > 0 {
            parts.append(XmbTime.length(duration, hours: firmware.text(XmbRco.full, "msg_time_num2", "%1:%2'%3''"),
                                         minutes: firmware.text(XmbRco.full, "msg_time_num3", "%1'%2''")))
        }
        if left > 0 {
            parts.append(firmware.text(XmbRco.full, "msg_resume", String(localized: "Resume")) + " " + XmbTime.clock(left))
        }
        var options: [XmbNode.Option] = []
        if left > 0 { options.append(.init(id: "begin", title: firmware.text(XmbRco.full, "msg_play_begin", String(localized: "Play from Beginning")))) }
        if entry.own { options.append(.init(id: "delete", title: String(localized: "Delete"))) }
        let id = "video." + entry.key
        section.options[id] = { [weak self] option in
            guard let self else { return }
            if option == "begin" {
                XmbVideoPlayback.shared.open(list, at: index, fromBeginning: true)
            } else {
                ask(String(localized: "Delete \(entry.title)?"), buttons: [yes, no], focus: 1) { button in
                    if button == 0 { videos.delete(entry, listing: key) }
                }
            }
        }
        let node = XmbNode(id: id, title: entry.title, subtitle: parts.isEmpty ? nil : parts.joined(separator: "   "),
                           icon: meta?.thumb.map { .game(path: $0) } ?? VideoIcons.film, kind: .action, options: options)
        return section.action(node) { XmbVideoPlayback.shared.open(list, at: index, fromBeginning: false) }
    }
}
