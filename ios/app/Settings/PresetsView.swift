// SPDX-License-Identifier: GPL-2.0-only
// The Presets pane. Store and file work run detached, and Apply always says how many settings change first.

import SwiftUI
import UniformTypeIdentifiers

// Apart from the view, so the smoke scenes drive the same calls the screen makes.
@MainActor @Observable final class PresetsModel {
    let store: PresetStore
    private(set) var entries: [PresetEntry] = []
    // So the pane shows progress rather than a flash of "No presets yet".
    private(set) var loaded = false
    private(set) var working = false
    var notice: String?
    var importRefused = false

    init(store: PresetStore = PresetStore(directory: PresetStore.defaultDirectory)) {
        self.store = store
    }

    func reload() async {
        let store = store
        entries = await Task.detached(priority: .userInitiated) { store.list() }.value
        loaded = true
    }

    // `serial` nil means All games; a same-name save replaces.
    func save(name: String, serial: String?, count: Int) async -> PresetEntry? {
        working = true
        defer { working = false }
        let (yaml, status) = await EmulatorSession.shared.exportSettings(serial: serial)
        var entry: PresetEntry?
        if status == 0 {
            let store = store
            entry = await Task.detached(priority: .userInitiated) { store.save(name: name, yaml: Data(yaml.utf8), count: count) }.value
        }
        if entry == nil { notice = String(localized: "Couldn't save this preset") }
        await reload()
        return entry
    }

    // `serial` nil means All games.
    func apply(_ entry: PresetEntry, serial: String?) async -> Int {
        working = true
        defer { working = false }
        let store = store
        guard let data = await Task.detached(priority: .userInitiated, operation: { store.data(for: entry) }).value else {
            notice = String(localized: "Couldn't apply “\(entry.name)” (status -1)")
            return -1
        }
        let (_, _, status) = await EmulatorSession.shared.applySettings(String(decoding: data, as: UTF8.self), serial: serial)
        if status != 0 { notice = String(localized: "Couldn't apply “\(entry.name)” (status \(status))") }
        return status
    }

    // The scope is held only around the read.
    func importFile(_ url: URL) async -> (entry: PresetEntry?, counts: SettingsImportCounts?) {
        working = true
        defer { working = false }
        let started = url.startAccessingSecurityScopedResource()
        let text = await Task.detached(priority: .userInitiated) { () -> String? in
            guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, PresetStore.importable(size: size),
                  let data = try? Data(contentsOf: url) else { return nil }
            return String(data: data, encoding: .utf8)
        }.value
        if started { url.stopAccessingSecurityScopedResource() }
        let (yaml, counts, status) = text != nil ? await EmulatorSession.shared.normaliseSettings(text!)
            : ("", SettingsImportCounts(kept: 0, unknown: 0, defaulted: 0, locked: 0), -22)
        guard status == 0 else {
            importRefused = true
            return (nil, nil)
        }
        let name = url.deletingPathExtension().lastPathComponent
        guard counts.kept > 0 else {
            notice = String(localized: "“\(name)” has no settings that differ from the defaults, so no preset was made.")
            return (nil, counts)
        }
        let store = store
        let entry = await Task.detached(priority: .userInitiated) { store.save(name: name, yaml: Data(yaml.utf8), count: counts.kept) }.value
        notice = entry.map { PresetStore.importNotice(name: $0.name, counts: counts) } ?? String(localized: "Couldn't save this preset")
        await reload()
        return (entry, counts)
    }

    func exportFile(_ entry: PresetEntry) async -> URL? {
        working = true
        defer { working = false }
        let store = store
        let url = await Task.detached(priority: .userInitiated) { () -> URL? in
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let file = folder.appendingPathComponent(entry.file)
            guard let data = store.data(for: entry),
                  (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil,
                  (try? data.write(to: file)) != nil else { return nil }
            return file
        }.value
        if url == nil { notice = String(localized: "Couldn't export “\(entry.name)”") }
        return url
    }

    func delete(_ entry: PresetEntry) async {
        working = true
        defer { working = false }
        let store = store
        let deleted = await Task.detached(priority: .userInitiated) { store.delete(entry) }.value
        if !deleted { notice = String(localized: "Couldn't delete “\(entry.name)”") }
        await reload()
    }
}

struct PresetsView: View {
    let settings: SettingsScreenModel
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var presets: PresetsModel
    @State private var naming = false
    @State private var name = ""
    @State private var picking = false
    @State private var chosen: PresetEntry?
    @State private var applying: PresetEntry?
    @State private var deleting: PresetEntry?
    @State private var sharing: SharedSettingsFile?

    init(settings: SettingsScreenModel, path: Binding<[SettingsPane]>, controller: SettingsControllerContext = SettingsControllerContext(),
         presets: PresetsModel? = nil) {
        self.settings = settings
        _path = path
        self.controller = controller
        _presets = State(initialValue: presets ?? PresetsModel())
    }

    private var serial: String? { settings.scope == .game ? settings.serial : nil }
    private var target: String { settings.scope == .game ? settings.gameTitle : String(localized: "All Games") }
    private var changed: Int { settings.dump.map { SettingsModel.changedCount($0.leaves, scope: settings.scope) } ?? 0 }

    var body: some View {
        SettingsPage {
            VStack(spacing: 8) {
                Button("Save as Preset") { startSave() }
                    .buttonStyle(.borderedProminent)
                    .disabled(changed == 0 || presets.working)
                    .controllerAccessibilityActionTarget(id: "settings.preset.save", label: String(localized: "Save as Preset")) { startSave() }
                if changed == 0 {
                    Text("Change at least one setting first.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            if !presets.loaded {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
            } else if presets.entries.isEmpty {
                SettingsMessage(heading: "No presets yet",
                                message: "Change some settings, then tap Save as Preset to keep them. You can also import a preset from Files.")
            } else {
                OverlaySectionCard {
                    ForEach(presets.entries) { entry in
                        OverlayActionRow(controllerNavigationID: Self.navigationID(entry), label: entry.name, systemImage: "square.stack",
                                         trailingValue: String(localized: "\(entry.count) settings"), keepsTrailingValue: true) {
                            if !presets.working { chosen = entry }
                        }
                        .disabled(presets.working)
                    }
                }
            }
        }
        .navigationTitle("Presets")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Import", systemImage: "square.and.arrow.down") { picking = true }
                    .disabled(presets.working)
            }
        }
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.presets",
                                           onBack: { pop() }, onContextMenu: { id in openDialog(navigationID: id) },
                                           onPreviousTab: controller.onPreviousTab, onNextTab: controller.onNextTab,
                                           onBoundary: controller.onBoundary)
        .alert("Name this preset", isPresented: $naming) {
            TextField("Preset name", text: $name)
            Button("Save Preset") { Task { await presets.save(name: name, serial: serial, count: changed) } }
                .disabled(PresetStore.fileStem(for: name) == nil)
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(chosen?.name ?? "", isPresented: shown($chosen), titleVisibility: .visible, presenting: chosen) { entry in
            Button("Apply to \(target)") { applying = entry }
            Button("Export") { Task { if let url = await presets.exportFile(entry) { sharing = SharedSettingsFile(url: url) } } }
            Button("Delete", role: .destructive) { deleting = entry }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Apply “\(applying?.name ?? "")” to \(target)?", isPresented: shown($applying), presenting: applying) { entry in
            Button("Apply") { Task { if await presets.apply(entry, serial: serial) == 0 { await settings.load() } } }
            Button("Cancel", role: .cancel) {}
        } message: { entry in
            Text("\(entry.count) settings change. Settings the preset doesn't include keep their values.")
        }
        .alert("Delete “\(deleting?.name ?? "")”?", isPresented: shown($deleting), presenting: deleting) { entry in
            Button("Delete", role: .destructive) { Task { await presets.delete(entry) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("This removes the preset from this device. Settings already applied from it stay.")
        }
        .alert("Couldn't import this file", isPresented: Binding(get: { presets.importRefused }, set: { presets.importRefused = $0 })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("It isn't a settings file ARMSX3 can read. Pick a .yml settings file from RPCS3 or ARMSX3.")
        }
        .alert(presets.notice ?? "", isPresented: Binding(get: { presets.notice != nil }, set: { if !$0 { presets.notice = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .sheet(isPresented: $picking) {
            SavePicker(types: [UTType(filenameExtension: "yml") ?? .data, UTType(filenameExtension: "yaml") ?? .data]) { url in
                picking = false
                if let url { Task { _ = await presets.importFile(url) } }
            }
        }
        .sheet(item: $sharing) { shared in
            ShareSheet(url: shared.url) {
                try? FileManager.default.removeItem(at: shared.url.deletingLastPathComponent())
                sharing = nil
            }
        }
        .task { await presets.reload() }
    }

    static func navigationID(_ entry: PresetEntry) -> String { "settings.preset." + entry.name }

    private func startSave() {
        guard changed > 0, !presets.working else { return }
        name = ""
        naming = true
    }

    private func openDialog(navigationID: String) -> Bool {
        guard !presets.working, let entry = presets.entries.first(where: { Self.navigationID($0) == navigationID }) else { return false }
        chosen = entry
        return true
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }

    private func shown<T>(_ item: Binding<T?>) -> Binding<Bool> {
        Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } })
    }
}
