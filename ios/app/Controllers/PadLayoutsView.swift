// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UniformTypeIdentifiers

extension PadEditor: Identifiable {}

// Settings › Controllers' card for the on-screen pad.
struct TouchPadCard: View {
    // nil hides Edit Layout…, as in a game's own Controllers sheet, where the quick menu has the row.
    var onEdit: (() -> Void)?
    @State private var showingLayouts = false

    var body: some View {
        let library = PadLayoutStore.shared.library
        OverlaySectionCard(title: String(localized: "Touch Pad")) {
            if let onEdit {
                OverlayActionRow(controllerNavigationID: "settings.controllers.touch.edit", label: "Edit Layout…",
                                 systemImage: "hand.tap", action: onEdit)
            }
            OverlayActionRow(controllerNavigationID: "settings.controllers.touch.layouts", label: "Layouts", systemImage: "square.stack",
                             trailingValue: library.name(of: library.sharedChoice), keepsTrailingValue: true, showsChevron: true) {
                showingLayouts = true
            }
            OverlayToggleRow(controllerNavigationID: "settings.controllers.touch.vibrate", label: "Vibrate on Press",
                             systemImage: "iphone.radiowaves.left.and.right", isOn: Bindable(SettingsStore.shared).padVibrates)
            Text("Games with their own layout, set from inside a game, aren't changed here.")
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let kept = PadLayoutStore.shared.setAside {
                Text("Your saved layouts couldn't be read, so they were kept as “\(kept)” and the pad started fresh.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .navigationDestination(isPresented: $showingLayouts) { PadLayoutsPage() }
    }
}

// Default, My Layout and the presets. A tap makes one the layout every game without its own uses.
struct PadLayoutsPage: View {
    private var store: PadLayoutStore { PadLayoutStore.shared }
    @State private var chosen: PadLayoutChoice?
    @State private var renaming: PadLayoutPreset?
    @State private var deleting: PadLayoutChoice?
    @State private var name = ""
    @State private var picking = false
    @State private var sharing: SharedSettingsFile?
    @State private var notice: String?
    @State private var editing: PadEditor?
    @Environment(\.menuControllerInputRouter) private var router
    @Environment(\.dismiss) private var dismiss

    private var choices: [PadLayoutChoice] { store.library.choices }

    var body: some View {
        let library = store.library
        SettingsPage {
            OverlaySectionCard {
                ForEach(choices, id: \.self) { choice in
                    OverlayActionRow(controllerNavigationID: "settings.touchlayouts." + choice.rawValue, label: library.name(of: choice),
                                     trailingValue: library.sharedChoice == choice ? "✓" : nil, keepsTrailingValue: true) {
                        store.update { $0.shared = choice }
                    }
                    .contextMenu {
                        ForEach(actions(for: choice), id: \.title) { action in
                            Button(action.title, role: action.isDestructive ? .destructive : nil, action: action.run)
                        }
                    }
                }
                Text("Tap a layout to use it for every game. Touch and hold one for more.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if library.presets.isEmpty {
                    Text("Save a layout as a preset from the editor's More menu, or import a .armsx3pad file.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .navigationTitle("Layouts")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Import", systemImage: "square.and.arrow.down") { picking = true }
            }
        }
        .controllerAccessibilityNavigation(controllerInput: router, scopeKey: "settings.pane.touchlayouts",
                                           onBack: { dismiss(); return true },
                                           onContextMenu: { id in
                                               chosen = choices.first { "settings.touchlayouts." + $0.rawValue == id }
                                               return chosen != nil
                                           })
        .controllerPrompt(chosen.map { library.name(of: $0) } ?? "", isPresented: shown($chosen),
                          actions: chosen.map { actions(for: $0) + [.init(title: String(localized: "Cancel"), isCancel: true)] } ?? [])
        .controllerPrompt(String(localized: "Delete “\(deleting.map { library.name(of: $0) } ?? "")”?"), isPresented: shown($deleting),
                          message: deleting == library.sharedChoice
                              ? String(localized: "Every game goes back to Default, except games with their own layout.")
                              : String(localized: "Games that use it switch to \(library.name(of: library.sharedChoice))."),
                          actions: [.init(title: String(localized: "Delete"), isDestructive: true, run: deleteChosen),
                                    .init(title: String(localized: "Cancel"), isCancel: true)])
        .alert(String(localized: "Rename “\(renaming?.name ?? "")”"), isPresented: shown($renaming)) {
            TextField("Layout name", text: $name)
            Button("Rename") {
                if let id = renaming?.id { store.update { $0.rename(id, to: name) } }
            }
            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name == renaming?.name)
            Button("Cancel", role: .cancel) {}
        }
        .alert(notice ?? "", isPresented: shown($notice)) {
            Button("OK", role: .cancel) {}
        }
        .sheet(isPresented: $picking) {
            SavePicker(types: [UTType("com.armsx3.padlayout") ?? .json, .json]) { url in
                picking = false
                if let url { notice = store.importMessage(url) }
            }
        }
        .sheet(item: $sharing) { shared in
            ShareSheet(url: shared.url) {
                try? FileManager.default.removeItem(at: shared.url)
                sharing = nil
            }
        }
        .fullScreenCover(item: $editing) { editor in
            PadLayoutEditorView(editor: editor, onClose: { editing = nil })
        }
    }

    // Default is the stock pad, so it can be edited into My Layout, copied and shared, but never renamed or deleted.
    private func actions(for choice: PadLayoutChoice) -> [ControllerPrompt.Action] {
        var list: [ControllerPrompt.Action] = [.init(title: String(localized: "Edit"), run: { edit(choice) })]
        if case .preset(let id) = choice, let preset = store.library.presets.first(where: { $0.id == id }) {
            list.append(.init(title: String(localized: "Rename…"), run: { name = preset.name; renaming = preset }))
        }
        list.append(.init(title: String(localized: "Duplicate"), run: { store.update { $0.duplicate(choice) } }))
        list.append(.init(title: String(localized: "Export"), run: { export(choice) }))
        if choice != .standard {
            list.append(.init(title: String(localized: "Delete"), isDestructive: true, run: { deleting = choice }))
        }
        return list
    }

    private func edit(_ choice: PadLayoutChoice) {
        let editor = PadEditor(serial: nil, library: store.library)
        editor.load(choice, from: store.library)
        editor.makesShared = false
        editing = editor
    }

    private func export(_ choice: PadLayoutChoice) {
        let library = store.library
        let name = library.name(of: choice)
        if let url = try? store.exportFile(name: name, layout: library.layout(of: choice, serial: nil)) {
            sharing = SharedSettingsFile(url: url)
        } else {
            notice = String(localized: "Couldn't export “\(name)”")
        }
    }

    private func deleteChosen() {
        guard let deleting else { return }
        store.update { library in
            switch deleting {
            case .preset(let id): library.delete(id)
            case .mine: library.deleteMine()
            case .standard, .game: break
            }
        }
    }

    private func shown<T>(_ value: Binding<T?>) -> Binding<Bool> {
        Binding(get: { value.wrappedValue != nil }, set: { if !$0 { value.wrappedValue = nil } })
    }
}
