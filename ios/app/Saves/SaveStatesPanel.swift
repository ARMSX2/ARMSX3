// SPDX-License-Identifier: GPL-2.0-only
// The save-state slot panel. A load asks first because there is no Undo; a save does not, since the core keeps history.

import SwiftUI

struct SaveStatesPanel: View {
    enum Mode { case save, load }

    let mode: Mode
    let gameTitle: String
    let directory: URL
    // A file slot: 0-9 numbered, 11 quick.
    let onSave: (Int) -> Void
    let onLoad: (URL) -> Void
    let onBack: () -> Void
    var controllerInput: MenuControllerInputRouter? = nil

    static let controllerScope = "game.savestates"

    @State private var files: [SaveStateFile] = []
    @State private var listing = SaveStateFiles.Listing()
    @State private var pendingLoad: URL?
    @State private var renaming: SaveStateFile?
    @State private var nameText = ""
    // Bumped after a rename, so the rows read the metadata store again.
    @State private var renamed = 0
    private var settings: SettingsStore { SettingsStore.shared }

    private var rows: [SaveStateSlot] {
        _ = renamed
        return SaveStateSlot.rows(from: files, load: mode == .load) { SaveStateMetadataStore.shared.metadata(for: $0) }
    }

    var body: some View {
        OverlayPanelScaffold {
            VStack(spacing: 12) {
                OverlayHeader(systemImage: mode == .load ? "clock.arrow.circlepath" : "square.and.arrow.down",
                              title: mode == .load ? String(localized: "Load State") : String(localized: "Save State"), subtitle: gameTitle, compact: false)
                if SaveProgress.shared.active && SaveProgress.shared.fromPanel {
                    SaveProgressBar(inPanel: true)
                }
                ScrollView {
                    VStack(spacing: 12) {
                        OverlaySectionCard {
                            ForEach(rows) { row in slotRow(row) }
                        }
                        if mode == .load {
                            OverlaySectionCard(title: "Saved from PS menu") {
                                if listing.psMenu.isEmpty {
                                    Text("None")
                                        .foregroundStyle(OverlayTheme.textSecondary)
                                        .frame(minHeight: 44)
                                }
                                ForEach(listing.psMenu, id: \.url) { entry in
                                    StateRow(id: "savestates.psmenu." + entry.url.lastPathComponent, title: String(localized: "PS menu save"),
                                             symbol: "house", detail: detail(entry.modified), enabled: true) {  // no PlayStation logo
                                        pendingLoad = entry.url
                                    }
                                }
                            }
                        }
                    }
                }
                OverlaySectionCard {
                    OverlayActionRow(controllerNavigationID: "savestates.back", label: "Back", systemImage: "chevron.backward",
                                     action: onBack)
                }
            }
            .padding()
        }
        .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: Self.controllerScope,
                                           onBack: { onBack(); return true },
                                           onContextMenu: { id in beginRename(navigationID: id) })
        .task(id: directory) { await reload() }
        .alert("Load this state?", isPresented: Binding(get: { pendingLoad != nil }, set: { if !$0 { pendingLoad = nil } }),
               presenting: pendingLoad) { url in
            Button("Load", role: .destructive) { onLoad(url) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Unsaved progress is lost.")
        }
        .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
               presenting: renaming) { file in
            TextField("Name", text: $nameText)
            Button("Save") { rename(file) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Up to \(SaveStateSlot.nameLimit) characters.")
        }
    }

    private func slotRow(_ row: SaveStateSlot) -> some View {
        let fileSlot = SaveStateFiles.fileSlot(forUISlot: row.slot) ?? -1
        return StateRow(id: Self.navigationID(fileSlot: fileSlot), title: row.title(settings), symbol: row.symbol,
                        picture: Self.picture(row.file.preview), detail: row.modifiedDate.map(detail) ?? String(localized: "Empty"), enabled: mode == .save || row.occupied) {
            if mode == .save {
                onSave(fileSlot)
            } else if row.occupied, let url = listing.slots[fileSlot]?.url {
                pendingLoad = url
            }
        }
        .contextMenu {
            if row.isNameable && row.occupied {
                Button("Rename", systemImage: "pencil") { beginRename(row.file) }
            }
        }
    }

    static func picture(_ data: Data?) -> CGImage? {
        guard let data, let thumb = SaveStateFiles.decodeThumbnail(data),
              let provider = CGDataProvider(data: thumb.rgba as CFData) else { return nil }
        return CGImage(width: thumb.width, height: thumb.height, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: thumb.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    static func navigationID(fileSlot: Int) -> String { "savestates.slot\(fileSlot)" }

    // "Latest" marks the newest state the panel shows.
    private func detail(_ date: Date) -> String {
        let text = SaveStateFormat.savedAt(date)
        return date == latest ? String(localized: "Latest · \(text)") : text
    }

    private var latest: Date? {
        (rows.compactMap(\.modifiedDate) + (mode == .load ? listing.psMenu.map(\.modified) : [])).max()
    }

    private func reload() async {
        listing = await Self.scan(directory)
        files = await SaveStateFile.current(directory: directory)
    }

    // False leaves the press to the router.
    private func beginRename(navigationID: String) -> Bool {
        guard let row = rows.first(where: { Self.navigationID(fileSlot: SaveStateFiles.fileSlot(forUISlot: $0.slot) ?? -1) == navigationID }),
              row.isNameable, row.occupied else { return false }
        beginRename(row.file)
        return true
    }

    private func beginRename(_ file: SaveStateFile) {
        nameText = SaveStateMetadataStore.shared.metadata(for: file)?.name ?? ""
        renaming = file
    }

    private func rename(_ file: SaveStateFile) {
        let name = SaveStateSlot.cleanName(nameText)
        SaveStateMetadataStore.shared.update(file) { $0.name = name.isEmpty ? nil : name }
        renamed += 1
    }

    static func scan(_ directory: URL) async -> SaveStateFiles.Listing {
        await Task.detached(priority: .userInitiated) {
            SaveStateFiles.scan(directory: directory,
                                appHistory: SaveStateFiles.appHistory(in: directory.appendingPathComponent(SaveStateFiles.slotsFolder)))
        }.value
    }
}

private struct StateRow: View {
    let id: String
    let title: String
    let symbol: String?
    var picture: CGImage? = nil
    let detail: String
    let enabled: Bool
    let action: () -> Void
    @Environment(\.uiAccentColour) private var accentColour

    var body: some View {
        Button(action: action) {
            HStack(spacing: OverlayTheme.rowIconSpacing) {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(OverlayTheme.shell)
                    .frame(width: 64, height: 36)
                    .overlay {
                        if let picture {
                            Image(decorative: picture, scale: 1)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        } else {
                            Image(systemName: symbol ?? "opticaldisc")
                                .foregroundStyle(accentColour)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(OverlayTheme.textPrimary)
                        .lineLimit(1)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.45)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .controllerAccessibilityActionTarget(id: id, label: title, action: action)
    }
}
