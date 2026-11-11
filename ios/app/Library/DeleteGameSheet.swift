// SPDX-License-Identifier: GPL-2.0-only
// Delete Game for a title that is not installed into dev_hdd0: its files go, or it only leaves the library.

import SwiftUI

struct DeleteGameSheet: View {
    let entry: LibraryCache.Entry
    // Settled when the sheet is chosen: once the files are gone the path no longer says whether the app owned them.
    let item: URL?
    // Called once the files are gone or the game is hidden; the library reads the disk again after it.
    let onRemoved: (_ filesDeleted: Bool) async -> Void

    @State private var alsoUpdates = true
    @State private var alsoCaches = true
    @State private var alsoSaves = UninstallSheet.savesDefault
    @State private var size: Int64?
    @State private var working = false
    @State private var finished = false
    @State private var failure: String?
    @Environment(\.dismiss) private var dismiss

    private let games = LibraryRelocation.Roots.current.games

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if item != nil {
                        LabeledContent("Size", value: size.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "…")
                        Text("The game's files are deleted from this app's games folder. This can't be undone.")
                    } else {
                        Text("This game is in a folder the app doesn't own, so its files stay where they are. It only leaves the library; Game Folders can show it again.")
                    }
                }
                Section {
                    Toggle("Also remove its update and DLC", isOn: $alsoUpdates)
                    Toggle("Also clear its shader and SPU caches", isOn: $alsoCaches)
                    Toggle("Also delete save data and save states", isOn: $alsoSaves)
                    if alsoSaves {
                        Text("Save data is backed up to Files › ARMSX3 › Save Backups first. Save states are deleted.")
                            .foregroundStyle(.secondary)
                    }
                    if let failure {
                        Text(failure).foregroundStyle(.orange)
                    }
                }
                .disabled(working || finished)
                Section {
                    Button(item != nil ? "Delete Game" : "Remove from Library", role: .destructive) { Task { await confirm() } }
                        .disabled(working || finished)
                    Button(finished ? "Done" : "Cancel", role: .cancel) { dismiss() }
                }
            }
            // The app's dark background, as Settings, Install and Trophies draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle(item != nil ? "Delete \(entry.title)?" : "Remove \(entry.title)?")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
        .task {
            guard let item else { return }
            size = await Task.detached(priority: .utility) { GameDeletion.size(of: item) }.value
        }
    }

    private func confirm() async {
        working = true
        defer { working = false }
        var kept: [String] = []
        if let item {
            do { try GameDeletion.remove(item, games: games) } catch {
                failure = String(localized: "The game's files couldn't be deleted.")
                return
            }
        }
        // Past this point the game is gone from the library, so a failed extra only says what was left behind.
        if alsoUpdates {
            let status = await Rpcs3Bridge.uninstallGame(withSerial: entry.serial)
            // -3: nothing is installed under this serial, which is the usual case for a disc game.
            if status == 0, let id = GameUpdates.titleID(entry.serial) { await UpdateStore.shared.forgetAfterUninstall(id) }
            if status != 0 && status != -3 { kept.append(String(localized: "its update and DLC")) }
        }
        if alsoCaches, await Rpcs3Bridge.clearGameCache(withSerial: entry.serial) != 0 {
            kept.append(String(localized: "its caches"))
        }
        if alsoSaves, !(await UninstallSheet.deleteSaves(serial: entry.serial)) {
            kept.append(String(localized: "its saves and save states"))
        }
        await onRemoved(item != nil)
        guard kept.isEmpty else {
            finished = true
            failure = String(localized: "The game was removed, but \(kept.formatted(.list(type: .and))) couldn't be.")
            return
        }
        dismiss()
    }
}
