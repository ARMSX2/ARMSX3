// SPDX-License-Identifier: GPL-2.0-only
// Uninstall by serial; the core builds the folder path itself. Saves are backed up before they are deleted.

import SwiftUI

struct UninstallSheet: View {
    let serial: String
    let gameTitle: String
    let onUninstalled: () async -> Void

    // Off by default; the smoke scene reports it from here.
    static let savesDefault = false

    @State private var alsoSaves = UninstallSheet.savesDefault
    @State private var working = false
    @State private var finished = false
    @State private var failure: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("The game, its updates and DLC are removed. Licences and trophies stay.")
                    Toggle("Also delete save data and save states", isOn: $alsoSaves)
                        .disabled(working || finished)
                    if alsoSaves {
                        Text("Save data is backed up to Files › ARMSX3 › Save Backups first. Save states are deleted.")
                            .foregroundStyle(.secondary)
                    }
                    if let failure {
                        Text(failure).foregroundStyle(.orange)
                    }
                }
                Section {
                    Button("Uninstall", role: .destructive) { Task { await confirm() } }
                        .disabled(working || finished)
                    Button(finished ? "Done" : "Cancel", role: .cancel) { dismiss() }
                }
            }
            // The app's dark background, as Settings, Install and Trophies draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Uninstall \(gameTitle)?")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }

    // `savesKept` is true when the box was ticked and a save could not be backed up, so it and everything after it stayed.
    static func uninstall(serial: String, alsoSaves: Bool) async -> (status: Int, savesKept: Bool) {
        let status = await Rpcs3Bridge.uninstallGame(withSerial: serial)
        if status == 0, let id = GameUpdates.titleID(serial) { await UpdateStore.shared.forgetAfterUninstall(id) }
        guard status == 0, alsoSaves else { return (status, false) }
        return (status, !(await deleteSaves(serial: serial)))
    }

    private func confirm() async {
        working = true
        defer { working = false }
        let result = await Self.uninstall(serial: serial, alsoSaves: alsoSaves)
        guard result.status == 0 else {
            failure = Self.describe(result.status)
            return
        }
        await onUninstalled()
        guard !result.savesKept else {
            finished = true
            failure = String(localized: "The game was uninstalled, but a save couldn't be backed up, so the saves and save states were kept.")
            return
        }
        dismiss()
    }

    // The states are deleted only when every save went.
    static func deleteSaves(serial: String) async -> Bool {
        let user = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            Rpcs3Bridge.userDirectory { path, status in continuation.resume(returning: status == 0 ? path : "") }
        }
        let states = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            Rpcs3Bridge.saveStateDirectory(forSerial: serial) { path, status in continuation.resume(returning: status == 0 ? path : "") }
        }
        // The backups and the state removal, often hundreds of megabytes, run off the main actor.
        let (_, _, _, status) = await Task.detached(priority: .userInitiated) { () -> (Int, Int, Bool, Int) in
            var deleted = 0
            var backups = 0
            var status = user.isEmpty ? -1 : 0
            if status == 0 {
                let store = SaveDataStore(userDir: URL(fileURLWithPath: user, isDirectory: true))
                for save in store.saves(forSerial: serial) {
                    do {
                        try store.delete(dirName: save.dirName)
                        deleted += 1
                        backups += 1
                    } catch {
                        backups += (error as? SaveImportError) == .writeFailed ? 1 : 0
                        status = -1
                        break
                    }
                }
            }
            var statesRemoved = false
            if status == 0, !states.isEmpty, FileManager.default.fileExists(atPath: states) {
                do { try FileManager.default.removeItem(atPath: states); statesRemoved = true } catch { status = -1 }
            }
            return (deleted, backups, statesRemoved, status)
        }.value
        return status == 0
    }

    static func describe(_ status: Int) -> String {
        switch status {
        case -3: return String(localized: "This game is no longer installed")
        case -9: return String(localized: "Stop the game first")
        case -16: return String(localized: "Couldn't remove every file; try again")
        default: return String(localized: "The game wasn't uninstalled (status \(status))")
        }
    }
}
