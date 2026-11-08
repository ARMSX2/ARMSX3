// SPDX-License-Identifier: GPL-2.0-only
// Settings' RPCN avatar grid, after Android's RpcnAvatar.kt. Avatar paths and titles never reach a log line.

import PhotosUI
import SwiftUI
import UIKit

/// With no action it is the picture alone, which the account card's header uses.
struct AvatarTile: View {
    let path: String?
    var size: CGFloat = 64
    var isCurrent = false
    var action: (() -> Void)?
    @State private var image: UIImage?
    @Environment(\.uiAccentColour) private var accentColour

    var body: some View {
        if let action {
            Button(action: action) { picture }.buttonStyle(.plain)
        } else {
            picture
        }
    }

    private var picture: some View {
        let shape = RoundedRectangle(cornerRadius: 8, style: .continuous)
        return ZStack {
            shape.fill(OverlayTheme.card)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .overlay { if isCurrent { shape.strokeBorder(accentColour, lineWidth: 2) } }
        // Decoded on demand, as the grid brings the tile on screen.
        .task(id: path) {
            image = nil
            if let path { image = await RpcnModel.shared.avatarImage(path) }
        }
    }
}

/// Choosing a tile sets it at once and the pane stays, so the player can compare.
struct AvatarPane: View {
    var controller = SettingsControllerContext()
    @State private var model = RpcnModel.shared
    /// The library's own installer, never a second one.
    @State private var installs = InstallModel()
    @State private var picking = false
    @State private var installing = false
    @State private var photo: PhotosPickerItem?

    var body: some View {
        SettingsPage {
            rpcnCaption(Self.caption(firmware: model.avatarFirmware))
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.rpcn.avatar.picture", label: "Use a Picture",
                                 systemImage: nil) { picking = true }
                OverlayActionRow(controllerNavigationID: "settings.rpcn.avatar.default", label: "Use Default",
                                 systemImage: nil) { Task { await model.useDefault() } }
                OverlayActionRow(controllerNavigationID: "settings.rpcn.avatar.install", label: "Install Avatars",
                                 systemImage: nil) { installing = true }
                    .disabled(installs.busy)
                rpcnNoticeLine(model.avatarNotice, busy: false)
            }
            if !model.favoriteAvatars.isEmpty {
                OverlaySectionCard(title: String(localized: "Favorites")) {
                    grid(model.favoriteAvatars, prefix: Self.favoritePrefix)
                }
            }
            if model.avatarsLoaded {
                OverlaySectionCard(title: String(localized: "All Avatars")) {
                    if model.avatars.isEmpty {
                        rpcnCaption(String(localized: "No avatars yet. Install PS3 firmware for its user icons, or use Install Avatars for PSN avatars you own."))
                    } else {
                        rpcnCaption(RpcnState.avatarCountLine(n: model.avatars.count, m: model.avatarUnusable))
                            .monospacedDigit()
                        grid(model.avatars, prefix: Self.tilePrefix)
                    }
                }
            }
        }
        .navigationTitle(String(localized: "Avatar"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.avatar",
                                           onBack: { model.form = nil; return true },
                                           onContextMenu: { id in model.toggleFavorite(target: id) },
                                           onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: Self.focusTarget(model.avatars, current: model.currentAvatar))
        .task { await model.loadAvatars() }
        .onChange(of: model.avatarNotice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
        // The picture is cut square and scaled here, then checked again by the bridge before anything is written.
        .photosPicker(isPresented: $picking, selection: $photo, matching: .images)
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await model.usePicture(data.flatMap(Self.squarePNG))
            }
        }
        .sheet(isPresented: $installing) {
            InstallPicker { urls in
                installing = false
                guard !urls.isEmpty else { return }
                Task { await Self.installAvatars(urls, installs: installs) }
            }
        }
    }

    private func grid(_ items: [RpcnState.AvatarItem], prefix: String) -> some View {
        // The columns start at the card's leading edge, under the count line, rather than centred.
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 64, maximum: 64), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.path) { item in
                let label = Self.describe(item, in: model.avatars)
                let current = Self.isCurrent(item, current: model.currentAvatar)
                let favorite = model.isFavoriteAvatar(item)
                let toggle = favorite ? String(localized: "Remove from Favorites") : String(localized: "Add to Favorites")
                AvatarTile(path: item.path, isCurrent: current) { Task { await model.select(item) } }
                    .controllerAccessibilityActionTarget(id: prefix + item.file, label: label,
                                                         action: { Task { await model.select(item) } })
                    .accessibilityLabel(Text(verbatim: label))
                    .accessibilityValue(favorite ? Text("Favorite") : Text(verbatim: ""))
                    .accessibilityAddTraits(current ? .isSelected : [])
                    .accessibilityHint(Text("Uses this avatar."))
                    .accessibilityActions { Button(toggle) { model.toggleFavorite(item) } }
                    .contextMenu { Button(toggle) { model.toggleFavorite(item) } }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    static func target(_ item: RpcnState.AvatarItem) -> String { tilePrefix + item.file }

    /// A favourite's copy in FAVORITES needs a target of its own, since two controls cannot share one.
    static let tilePrefix = "settings.rpcn.avatar.tile."
    static let favoritePrefix = "settings.rpcn.avatar.favorite."

    static func caption(firmware: Bool) -> String {
        let caption = String(localized: "Games that show your picture use this one.")
        return firmware ? caption : caption + " " + String(localized: "The default avatar comes with PS3 firmware, which isn't installed yet.")
    }

    /// Scaled to the core's large avatar size as a PNG, the one format the core reads; nil when it cannot be decoded.
    static func squarePNG(_ data: Data) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let side = min(image.size.width, image.size.height)
        let out = min(240, (side * image.scale).rounded(.down))
        guard out >= 1 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let drawn = CGSize(width: image.size.width * out / side, height: image.size.height * out / side)
        return UIGraphicsImageRenderer(size: CGSize(width: out, height: out), format: format).pngData { _ in
            image.draw(in: CGRect(x: (out - drawn.width) / 2, y: (out - drawn.height) / 2, width: drawn.width, height: drawn.height))
        }
    }

    static func installAvatars(_ urls: [URL], installs: InstallModel) async {
        await installs.install(urls: urls)
        // shortcut: the delete-package alert is skipped, since a controller cannot answer it; upgrade with .controllerPrompt.
        installs.deletePrompt = nil
        let results = installs.message ?? installs.rows.compactMap(\.result).joined(separator: "\n")
        RpcnModel.shared.avatarNotice = results.isEmpty ? nil : results
        await RpcnModel.shared.loadAvatars()
    }

    /// With none chosen the current one is the firmware's default file, as on Android.
    static func isCurrent(_ item: RpcnState.AvatarItem, current: RpcnState.AvatarItem?) -> Bool {
        current.map { item.path == $0.path } ?? false
    }

    static func focusTarget(_ items: [RpcnState.AvatarItem], current: RpcnState.AvatarItem?) -> String {
        items.first { isCurrent($0, current: current) }.map(target) ?? "settings.rpcn.avatar.picture"
    }

    static func describe(_ item: RpcnState.AvatarItem, in items: [RpcnState.AvatarItem]) -> String {
        switch item.kind {
        case "builtin":
            let number = (items.filter { $0.kind == "builtin" }.firstIndex { $0.path == item.path } ?? 0) + 1
            return String(localized: "PS3 user icon \(number)")
        case "psn": return String(localized: "PSN avatar, \(item.title)")
        default: return String(localized: "Your picture")
        }
    }
}
