// SPDX-License-Identifier: GPL-2.0-only
// Settings' Backup & Restore pane. Neither a backup nor a restore starts while a game runs.

import SwiftUI
import UniformTypeIdentifiers

struct BackupPaneView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var busy = false
    @State private var picking = false
    @State private var pending: URL?
    @State private var sharing: BackupShare?
    @State private var status: String?
    @State private var message: String?
    // Off keeps a backup with many save states under ZIP64's 4 GB line.
    @AppStorage("armsx3.backup.includeSavestates") private var includeSavestates = true

    static var backupsFolder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Backups", isDirectory: true)
    }

    static var appFolder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
    }

    // Nil when the core cannot name its folders yet.
    static func roots() async -> BackupArchive.Roots? {
        let (config, configStatus) = await Rpcs3Bridge.configDirectory()
        let (hdd0, hdd0Status) = await Rpcs3Bridge.hdd0Directory()
        guard configStatus == 0, hdd0Status == 0, !config.isEmpty, !hdd0.isEmpty else { return nil }
        return BackupArchive.Roots(config: URL(fileURLWithPath: config, isDirectory: true),
                                   hdd0: URL(fileURLWithPath: hdd0, isDirectory: true), app: appFolder)
    }

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Back Up")) {
                OverlayActionRow(controllerNavigationID: "settings.backup.export", label: String(localized: "Back Up Now"),
                                 systemImage: "externaldrive.badge.plus") { Task { await backUp() } }
                    .disabled(busy)
                OverlayToggleRow(controllerNavigationID: "settings.backup.savestates", label: String(localized: "Include save states"),
                                 systemImage: "clock.arrow.circlepath", isOn: $includeSavestates)
                Text("Saves, trophies, save states, settings, per-game settings, patches, controller profiles, skins, presets and play time, in one zip. Installed games and firmware are not included.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let status {
                    Text(verbatim: status)
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            OverlaySectionCard(title: String(localized: "Restore")) {
                OverlayActionRow(controllerNavigationID: "settings.backup.restore", label: String(localized: "Restore from Backup…"),
                                 systemImage: "arrow.counterclockwise") { picking = true }
                    .disabled(busy)
                Text("Restoring after reinstalling ARMSX3 brings your saves and settings back. Install your games and firmware again separately.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if busy {
                ProgressView()
            }
        }
        .navigationTitle(SettingsPane.backup.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.backup.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.zip]) { result in
            if case .success(let url) = result { pending = url }
        }
        .alert("Restore this backup?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
               presenting: pending) { url in
            Button("Restore", role: .destructive) { Task { await restore(url) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Files with the same names are replaced.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .sheet(item: $sharing) { share in
            ShareSheet(url: share.url) { sharing = nil }
        }
    }

    private func backUp() async {
        if EmulatorSession.shared.isRunning {
            message = BackupArchive.Failure.running.message
            return
        }
        busy = true
        defer { busy = false }
        guard let roots = await Self.roots() else {
            message = String(localized: "The backup couldn't be made.")
            return
        }
        let folder = Self.backupsFolder
        let savestates = includeSavestates
        let made = await Task.detached(priority: .userInitiated) {
            Result {
                try BackupArchive.export(roots: roots, defaults: .standard, includeSavestates: savestates,
                                         io: FoundationSaveDataIO(), to: folder)
            }
        }.value
        switch made {
        case .success(let zip):
            status = String(localized: "Backup saved to Files › ARMSX3 › Backups.")
            sharing = BackupShare(url: zip)
        case .failure(let error):
            message = (error as? BackupArchive.Failure)?.message ?? String(localized: "The backup couldn't be made.")
        }
    }

    private func restore(_ zip: URL) async {
        if EmulatorSession.shared.isRunning {
            message = BackupArchive.Failure.running.message
            return
        }
        // Lifted only by a failure, since a success leaves the app showing just the relaunch request.
        guard EmulatorSession.shared.beginRestore() else {
            message = BackupArchive.Failure.running.message
            return
        }
        var succeeded = false
        defer { EmulatorSession.shared.endRestore(succeeded: succeeded) }
        busy = true
        defer { busy = false }
        guard let roots = await Self.roots() else {
            message = String(localized: "That backup couldn't be restored.")
            return
        }
        let done = await Task.detached(priority: .userInitiated) {
            Result { () throws -> BackupArchive.Restored in
                let scoped = zip.startAccessingSecurityScopedResource()
                defer { if scoped { zip.stopAccessingSecurityScopedResource() } }
                return try BackupArchive.restore(from: zip, roots: roots, defaults: .standard)
            }
        }.value
        switch done {
        case .success(let restored):
            succeeded = true
            message = String(localized: "Restored \(restored.written) files. Close and reopen ARMSX3 to finish.")
        case .failure(let error):
            message = (error as? BackupArchive.Failure)?.message ?? String(localized: "That backup couldn't be restored.")
        }
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}

private struct BackupShare: Identifiable {
    let id = UUID()
    let url: URL
}
