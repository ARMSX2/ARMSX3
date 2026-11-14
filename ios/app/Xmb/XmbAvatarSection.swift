// SPDX-License-Identifier: GPL-2.0-only
// The RPCN avatar as XMB lists in Account Management, ported from xmbAvatarNode in Android's RpcnAvatar.kt.

import CryptoKit
import SwiftUI

/// The avatars as picture files the XMB's icons can show, each named after the avatar it is.
@MainActor @Observable final class XmbAvatarPictures {
    static let shared = XmbAvatarPictures()

    /// The avatars whose picture file is on disk, by avatar path.
    private(set) var written: Set<String> = []
    @ObservationIgnored private var filling: Task<Void, Never>?
    @ObservationIgnored private weak var fillingHost: XmbHost?
    /// Avatars that gave no picture this time the list was filled, so they are not asked for again every pass.
    @ObservationIgnored private var failed: Set<String> = []

    nonisolated static func file(_ path: String) -> URL {
        let name = SHA256.hash(data: Data(path.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("xmb-avatars", isDirectory: true).appendingPathComponent(name + ".png")
    }

    /// The avatar's picture file, written the first time; nil when the avatar cannot be read.
    func file(for path: String) async -> String? {
        if !written.contains(path) {
            guard await write(path) else { return nil }
            written.insert(path)
        }
        return Self.file(path).path
    }

    /// An avatar's path names one picture for good: a picture of the player's own is named after its contents.
    private func write(_ path: String) async -> Bool {
        let url = Self.file(path)
        if FileManager.default.fileExists(atPath: url.path) { return true }
        guard let png = await RpcnModel.shared.avatarImage(path)?.pngData() else { return false }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        return (try? png.write(to: url, options: .atomic)) != nil
    }

    /// While the avatar folder is open, the pictures near the focus of the list open in it, as Android reads them as the
    /// list reaches them: there can be thousands.
    func fill(_ host: XmbHost) {
        guard filling == nil || fillingHost !== host else { return }
        filling?.cancel()
        fillingHost = host
        failed = []
        filling = Task { [weak self, weak host] in
            while !Task.isCancelled {
                guard let self, let host, host.isOpen(XmbHost.avatarFolderID) else { break }
                let level = host.state.levels.last
                let rpcn = RpcnModel.shared
                let shown = level?.node == "rpcn.avatar.all" ? rpcn.avatars
                    : level?.node == "rpcn.avatar.favorites" ? rpcn.favoriteAvatars : []
                let focus = level?.focus ?? 0
                let low = min(max(focus - 8, 0), shown.count)
                var done: Set<String> = []
                for item in shown[low ..< max(min(focus + 13, shown.count), low)]
                    where !written.contains(item.path) && !failed.contains(item.path) {
                    if await write(item.path) { done.insert(item.path) } else { failed.insert(item.path) }
                }
                // One rebuild for the lot, as the list holds every avatar.
                if !done.isEmpty { written.formUnion(done) }
                try? await Task.sleep(for: .milliseconds(150))
            }
            // A cancelled one was replaced by a newer one.
            if !Task.isCancelled { self?.filling = nil }
        }
    }

    /// The host no longer shows its list; `fill` starts again when it does.
    func stop(_ host: XmbHost) {
        guard fillingHost === host else { return }
        filling?.cancel()
        filling = nil
    }

    /// The list shows again, with the folder perhaps still open.
    func resume(_ host: XmbHost) {
        if host.isOpen(XmbHost.avatarFolderID) { fill(host) }
    }
}

extension XmbHost {
    static let avatarFolderID = "rpcn.account.avatar"

    /// The default, a picture, avatars to install, then Favorites and All, every avatar a row with its picture.
    func avatarFolder(_ section: inout XmbSection, icon: XmbIcon) -> XmbNode {
        let id = Self.avatarFolderID
        // The home and the in-game XMB both build this column, so each keeps its own open state.
        if XmbToolsStore.shared.justOpened((overGame ? "ingame." : "") + id, isOpen(id)) { Task { await rpcn.loadAvatars() } }
        if isOpen(id) { XmbAvatarPictures.shared.fill(self) }
        let pictureIcon = XmbIcon.firmware(rco: XmbRco.photo, name: "photo_tex_default", symbol: "photo")
        let packageIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_disc_install", symbol: "shippingbox")
        let drive = XmbIcon.firmware(rco: XmbRco.photo, name: "item_tex_usb_connection", symbol: "externaldrive")
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let device = String(localized: "On My \(UIDevice.current.localizedModel)")
        let choose = String(localized: "Choose from Files")

        var rows = [section.action(XmbNode(id: "rpcn.avatar.default", title: String(localized: "Use default"), subtitle: nil, icon: icon,
                                           kind: .action)) { [weak self] in self?.chooseAvatar(nil) }]
        let pictures = toolsFileFolder(&section, id: "rpcn.avatar.picture.device", title: device, dir: documents, icon: drive,
                                       extensions: ["png", "jpg", "jpeg", "webp"]) { [weak self] url in self?.useAvatarPicture(url) }
        let pictureFiles = section.action(XmbNode(id: "rpcn.avatar.picture.files", title: choose, subtitle: nil, icon: drive,
                                                  kind: .action)) { [weak self] in self?.pick(.avatarPicture) }
        rows.append(XmbNode(id: "rpcn.avatar.picture", title: String(localized: "Use a picture"), subtitle: nil, icon: pictureIcon,
                            kind: .folder([pictures, pictureFiles])))
        // Installing reaches the core, which a running game is using.
        if !overGame {
            let packages = toolsFileFolder(&section, id: "rpcn.avatar.install.device", title: device, dir: documents, icon: drive,
                                           extensions: ["pkg", "rap", "edat"])
            let packageFiles = section.action(XmbNode(id: "rpcn.avatar.install.files", title: choose, subtitle: nil, icon: drive,
                                                      kind: .action)) { [weak self] in self?.pick(.tools(.packages)) }
            rows.append(XmbNode(id: "rpcn.avatar.install", title: String(localized: "Install avatars"), subtitle: nil, icon: packageIcon,
                                kind: .folder([packages, packageFiles])))
        }

        let favorites = rpcn.favoriteAvatars.map { avatarRow(&section, "rpcn.avatar.fav.", $0) }
        rows.append(XmbNode(id: "rpcn.avatar.favorites", title: String(localized: "Favorites (\(favorites.count))"), subtitle: nil, icon: icon,
                            kind: .folder(favorites.isEmpty ? [XmbNode(
                                id: "rpcn.avatar.favorites.none",
                                title: String(localized: "No favorites yet. Press Y (Triangle) on an avatar, or hold it, to add it here."),
                                subtitle: nil, icon: icon, kind: .info)] : favorites)))
        // Every avatar's row only while the list is open: there can be thousands.
        let count = rpcn.avatars.count
        let all = isOpen("rpcn.avatar.all") ? rpcn.avatars.map { avatarRow(&section, "rpcn.avatar.one.", $0) }
            : count > 0 ? [toolsClosed("rpcn.avatar.all")] : []
        let none = String(localized: "No avatars yet. The PS3 firmware brings its own user icons, and Install avatars adds PSN avatars you own, with their licences.")
        rows.append(XmbNode(id: "rpcn.avatar.all", title: String(localized: "All (\(count))"), subtitle: count == 0 ? none : nil, icon: icon,
                            kind: .folder(all)))

        return XmbNode(id: id, title: String(localized: "Avatar"), subtitle: String(localized: "Select your picture to change it"),
                       icon: avatarFile.map { .game(path: $0) } ?? icon, kind: .folder(rows))
    }

    /// An avatar named after its file, with its picture once written; Cross uses it, Triangle offers that and Favorites.
    private func avatarRow(_ section: inout XmbSection, _ prefix: String, _ item: RpcnState.AvatarItem) -> XmbNode {
        let id = prefix + item.path
        let useThis = String(localized: "Use This Avatar")
        let starred = rpcn.isFavoriteAvatar(item)
        let selected = rpcn.currentAvatar?.path == item.path
        section.options[id] = { [weak self] option in
            guard let self else { return }
            if option == "use" { return chooseAvatar(item) }
            rpcn.toggleFavorite(item)
            firmware.play(.decide)
        }
        let picture: XmbIcon = XmbAvatarPictures.shared.written.contains(item.path)
            ? .game(path: XmbAvatarPictures.file(item.path).path) : .firmware(rco: XmbRco.photo, name: "photo_tex_default", symbol: "photo")
        let subtitle = [selected ? useThis : nil, starred ? "\u{2605}" : nil].compactMap { $0 }.joined(separator: "   ")
        let node = XmbNode(id: id, title: (item.file as NSString).deletingPathExtension, subtitle: subtitle.isEmpty ? nil : subtitle,
                           icon: picture, kind: .action,
                           options: [.init(id: "use", title: useThis),
                                     .init(id: "favorite", title: starred ? String(localized: "Remove from Favorites")
                                         : String(localized: "Add to Favorites"))])
        return section.action(node) { [weak self] in self?.chooseAvatar(item) }
    }

    /// nil is the default avatar. A refusal is told in the XMB's dialog.
    private func chooseAvatar(_ item: RpcnState.AvatarItem?) {
        Task {
            if let item { await rpcn.select(item) } else { await rpcn.useDefault() }
            tellAvatarNotice()
        }
    }

    /// A picture of the player's own, cut square as the app's avatar screen cuts it.
    func useAvatarPicture(_ url: URL) {
        Task {
            let started = url.startAccessingSecurityScopedResource()
            // Android's limit, so a huge file is never read whole.
            let small = ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) <= 32 << 20
            let data = small ? try? Data(contentsOf: url) : nil
            if started { url.stopAccessingSecurityScopedResource() }
            await rpcn.usePicture(data.flatMap(AvatarPane.squarePNG))
            tellAvatarNotice()
        }
    }

    private func tellAvatarNotice() {
        guard let failed = rpcn.avatarNotice else { return }
        rpcn.avatarNotice = nil
        notice(failed)
    }
}
