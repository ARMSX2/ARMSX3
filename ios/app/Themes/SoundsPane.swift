// SPDX-License-Identifier: GPL-2.0-only
// Settings' System Sounds pane. Files cannot reach the core's sounds folder, so the pane copies what the player picks.

import SwiftUI
import UniformTypeIdentifiers

struct SoundsPane: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @AppStorage(SoundRouting.menuVolumeKey) private var menuVolume = Double(SoundRouting.defaultMenuPercent)
    @AppStorage(SoundRouting.trophyVolumeKey) private var trophyVolume = Double(SoundRouting.defaultTrophyPercent)
    @State private var picking = Pick.folder
    @State private var importing = false
    @State private var menuPresent = 0
    @State private var trophyPresent = false
    @State private var message: String?

    enum Pick { case folder, trophy }

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Menu Sounds")) {
                OverlayActionRow(controllerNavigationID: "settings.sounds.folder", label: String(localized: "Import Menu Sounds Folder"),
                                 systemImage: "folder") { pick(.folder) }
                volumeRow(id: "settings.sounds.menuvolume", label: String(localized: "Menu Volume"), value: $menuVolume)
            }
            OverlaySectionCard(title: String(localized: "Trophy Sound")) {
                OverlayActionRow(controllerNavigationID: "settings.sounds.trophy", label: String(localized: "Import Trophy Sound"),
                                 systemImage: "trophy") { pick(.trophy) }
                volumeRow(id: "settings.sounds.trophyvolume", label: String(localized: "Trophy Volume"), value: $trophyVolume)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("\(menuPresent) of 7 menu sounds imported. Trophy sound: \(trophyPresent ? String(localized: "imported") : String(localized: "none")).")
                Text("Sounds play only from files you import. Use RPCS3's names (snd_cursor.wav and the others).")
            }
            .font(.subheadline)
            .foregroundStyle(OverlayTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(SettingsPane.sounds.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.sounds.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .task { await refresh() }
        .fileImporter(isPresented: $importing, allowedContentTypes: picking == .folder ? [.folder] : [.wav]) { result in
            guard case .success(let url) = result else { return }
            let kind = picking
            Task { await importPicked(url, kind) }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func volumeRow(id: String, label: String, value: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label).foregroundStyle(OverlayTheme.textPrimary)
                Spacer(minLength: 0)
                Text(verbatim: "\(Int(value.wrappedValue))%").foregroundStyle(OverlayTheme.textSecondary).monospacedDigit()
            }
            Slider(value: value, in: 0...100, step: 1)
        }
        .frame(minHeight: 44)
        .controllerAccessibilityAdjustableTarget(id: id, label: label, value: "\(Int(value.wrappedValue))%",
                                                 onActivate: {},
                                                 onIncrement: { value.wrappedValue = min(100, value.wrappedValue + 5) },
                                                 onDecrement: { value.wrappedValue = max(0, value.wrappedValue - 5) })
    }

    private func pick(_ kind: Pick) {
        picking = kind
        importing = true
    }

    // Nil when the bridge cannot say.
    private func soundsFolder() async -> URL? {
        let (config, status) = await Rpcs3Bridge.configDirectory()
        guard status == 0, !config.isEmpty else { return nil }
        return URL(fileURLWithPath: config, isDirectory: true).appendingPathComponent("sounds", isDirectory: true)
    }

    private func refresh() async {
        guard let sounds = await soundsFolder() else { return }
        let manager = FileManager.default
        menuPresent = SoundRouting.menuNames.filter { manager.fileExists(atPath: sounds.appendingPathComponent($0 + ".wav").path) }.count
        trophyPresent = manager.fileExists(atPath: sounds.appendingPathComponent(SoundRouting.trophyName + ".wav").path)
    }

    // The pick is outside the container, so its security scope is open only around the copy.
    private func importPicked(_ url: URL, _ kind: Pick) async {
        guard let sounds = await soundsFolder() else {
            message = String(localized: "The sounds folder isn't available right now. Try again.")
            return
        }
        let started = url.startAccessingSecurityScopedResource()
        switch kind {
        case .folder:
            let result = SoundRouting.importFolder(url, into: sounds)
            if result.copied == 0 {
                message = String(localized: "No menu sounds were found. Use RPCS3's names, such as snd_cursor.wav.")
            }
        case .trophy:
            let ok = SoundRouting.importTrophy(url, into: sounds)
            if !ok {
                message = String(localized: "That file couldn't be used. Pick a .wav of 4 MB or less.")
            }
        }
        if started { url.stopAccessingSecurityScopedResource() }
        await refresh()
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}
