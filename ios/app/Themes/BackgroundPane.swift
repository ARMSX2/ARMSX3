// SPDX-License-Identifier: GPL-2.0-only
// Settings' Background pane: it writes only what the lifted background reads, and only Remove Theme deletes a theme.

import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct BackgroundPaneView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var settings = SettingsStore.shared
    @State private var model = ThemeLibraryModel.shared
    @State private var slot: Slot = .primary
    @State private var picking: Picking = .media
    @State private var photosShown = false
    @State private var filesShown = false
    @State private var photo: PhotosPickerItem?
    @State private var editingPalette = false
    @State private var paletteTarget = ThemePaletteTarget.shared
    @State private var paletteBackgroundOnly = false
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    // Only the Form's rows can read the session, so focus can move on after a theme is removed.
    @State private var navigation: ControllerAccessibilityNavigationSession?

    enum Slot { case primary, landscape }

    enum Picking { case media, theme }

    enum Pending {
        case data(Data, String, Slot)
        case file(URL, Slot)
        case theme(URL, Slot)
    }

    private static let themeIDPrefix = "settings.background.theme."

    var body: some View {
        // The two switches stay first whichever way they are set, and only the rows beneath them change.
        Form {
            Section {
                // Switching mid-game would take away the screen the running game belongs to.
                Toggle("XMB Mode", isOn: $settings.xmbMode)
                    .disabled(EmulatorSession.shared.isRunning)
                    .controllerAccessibilityTargetID("settings.appearance.xmb-mode")
            } footer: {
                Text(EmulatorSession.shared.isRunning
                     ? "Quit the game to switch XMB Mode."
                     : "The console's menu in place of the library. Leave it from Users, Exit XMB Mode.")
            }
            Section {
                Toggle("Moving background", isOn: $settings.dynamicBackgroundsEnabled)
                    .controllerAccessibilityTargetID("settings.appearance.dynamic-background")
                    .background { NavigationSessionReader { navigation = $0 } }
            }
            if settings.dynamicBackgroundsEnabled {
                DynamicBackgroundAppearanceSections(preferences: $settings.dynamicAppearancePreferences,
                                                    showPaletteEditor: { editingPalette = true })
            } else {
                pickSection(.primary)
                pickSection(.landscape)
                themesSection
                if settings.backgroundPrimaryAsset?.kind == .video || settings.backgroundLandscapeAsset?.kind == .video {
                    Section { Toggle("Mute Video", isOn: $settings.backgroundVideoMuted) }
                }
            }
            Section {
                Slider(value: $settings.backgroundDim, in: 0...1) { Text("Dim") }
            } header: {
                Text("Dim")
            }
        }
        .scrollContentBackground(.hidden)
        .background(OverlayFrostBackground().ignoresSafeArea())
        .navigationTitle(SettingsPane.background.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.background.navigationKey,
                                           onBack: { pop() }, onContextMenu: { id in offerRemove(navigationID: id) },
                                           onPreviousTab: controller.onPreviousTab, onNextTab: controller.onNextTab,
                                           onBoundary: controller.onBoundary)
        .photosPicker(isPresented: $photosShown, selection: $photo, matching: .any(of: [.images, .videos]))
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            let target = slot
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else {
                    say(String(localized: "That file couldn't be used as a background."))
                    return
                }
                let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                offer(.data(data, ext, target), large: BackgroundStorage.isLarge(data))
            }
        }
        .fileImporter(isPresented: $filesShown,
                      allowedContentTypes: picking == .theme ? [UTType(filenameExtension: "p3t")].compactMap { $0 } : [.image, .movie]) { result in
            guard case .success(let url) = result else { return }
            let kind = picking
            let started = url.startAccessingSecurityScopedResource()
            let large = BackgroundStorage.isLarge(url)
            if started { url.stopAccessingSecurityScopedResource() }
            offer(kind == .theme ? .theme(url, slot) : .file(url, slot), large: large)
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)
            .receive(on: RunLoop.main)) { _ in
            lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        }
        .sheet(isPresented: $editingPalette) {
            ThemePaletteEditor(target: $paletteTarget, preferences: $settings.dynamicAppearancePreferences,
                               isShowingBackgroundOnly: $paletteBackgroundOnly,
                               dynamicBackground: settings.dynamicAppearancePreferences.dynamicBackground,
                               onSaveAppearance: {})
        }
    }

    @ViewBuilder private func pickSection(_ which: Slot) -> some View {
        let asset = which == .primary ? settings.backgroundPrimaryAsset : settings.backgroundLandscapeAsset
        let key = ThemeLibraryModel.word(which)
        let busy = model.importing != nil
        Section {
            if let asset, asset.kind == .theme, let entry = model.entry(named: asset.filename) {
                LabeledContent("Theme") {
                    Text(entry.meta.name).lineLimit(1).truncationMode(.tail)
                }
            }
            Button("Choose from Photos", systemImage: "photo") { slot = which; photosShown = true }
                .disabled(busy)
                .controllerAccessibilityTargetID("settings.background.\(key).photos")
            Button("Choose from Files", systemImage: "folder") { slot = which; picking = .media; filesShown = true }
                .disabled(busy)
                .controllerAccessibilityTargetID("settings.background.\(key).files")
            Button { slot = which; picking = .theme; filesShown = true } label: {
                if model.importing == which {
                    Label { Text("Importing Theme…") } icon: { ProgressView() }
                } else {
                    Label("Choose Theme File", systemImage: "photo.stack")
                }
            }
            .disabled(busy)
            .accessibilityLabel(model.importing == which ? Text("Importing theme") : Text("Choose Theme File"))
            // While it imports the row has no hint at all, not an empty one.
            .modifier(ThemeFileHint(slot: which, importing: model.importing == which))
            .controllerAccessibilityTargetID("settings.background.\(key).theme-file")
            Picker("Fit", selection: which == .primary ? $settings.backgroundFitMode : $settings.backgroundLandscapeFitMode) {
                ForEach(BackgroundFitMode.allCases) { mode in
                    fitTitle(mode).tag(mode)
                }
            }
            if asset != nil {
                Button("Remove", systemImage: "trash", role: .destructive) { set(nil, which) }
                    // The role reddens only the words in a Form, and the icon kept the accent.
                    .foregroundStyle(.red)
                    .controllerAccessibilityTargetID("settings.background.\(key).remove")
            }
        } header: {
            Text(which == .primary ? "Primary" : "Landscape")
        } footer: {
            let note = liveNote(asset)
            if which == .landscape || note != nil {
                VStack(alignment: .leading, spacing: 4) {
                    if which == .landscape { Text("Shown in landscape; the primary background is used when none is set.") }
                    if let note { note }
                }
            }
        }
    }

    /// Reduce Motion's sentence wins over Low Power Mode's.
    private func liveNote(_ asset: BackgroundAsset?) -> Text? {
        guard let asset, asset.kind == .theme, model.entry(named: asset.filename)?.meta.source == .live else { return nil }
        if reduceMotion { return Text("Reduce Motion is on, so live themes show a still picture.") }
        if lowPower { return Text("Low Power Mode is on, so live themes show a still picture.") }
        return nil
    }

    @ViewBuilder private var themesSection: some View {
        if !model.entries.isEmpty {
            Section {
                ForEach(model.entries) { entry in
                    ThemeRow(entry: entry, value: themeValue(entry), choose: { offerTheme(entry) },
                             remove: { confirmRemove(entry) })
                        .disabled(model.importing != nil)
                        .controllerAccessibilityTargetID(Self.themeIDPrefix + entry.folderName)
                }
            } header: {
                Text("Themes")
            } footer: {
                Text("Only a theme's background is used. Its icons and sounds are not.")
            }
        }
    }

    private func fitTitle(_ mode: BackgroundFitMode) -> Text {
        switch mode {
        case .fill: return Text("Fill")
        case .fit: return Text("Fit")
        case .stretch: return Text("Stretch")
        }
    }

    private func themeValue(_ entry: ThemeEntry) -> String {
        let slots = model.slots(using: entry)
        let used: String?
        switch (slots.contains(.primary), slots.contains(.landscape)) {
        case (true, true): used = String(localized: "Used for Primary and Landscape.")
        case (true, false): used = String(localized: "Used for Primary.")
        case (false, true): used = String(localized: "Used for Landscape.")
        case (false, false): used = nil
        }
        // Two sentences, so VoiceOver reads "Live. Used for Primary." rather than "Live, Used for Primary."
        return [entry.meta.kindWord, used].compactMap { $0 }.joined(separator: ". ")
    }

    private func offerTheme(_ entry: ThemeEntry) {
        let asset = BackgroundAsset(kind: .theme, filename: entry.folderName)
        ControllerPrompt.shared.ask(entry.meta.name, message: "", actions: [
            .init(title: String(localized: "Use for Primary"), run: { model.setSlot(asset, .primary) }),
            .init(title: String(localized: "Use for Landscape"), run: { model.setSlot(asset, .landscape) }),
            .init(title: String(localized: "Remove Theme"), isDestructive: true, run: { confirmRemove(entry) }),
            .cancel,
        ])
    }

    private func confirmRemove(_ entry: ThemeEntry) {
        let slots = model.slots(using: entry)
        let message: String
        switch (slots.contains(.primary), slots.contains(.landscape)) {
        case (true, true):
            message = String(localized: "Its files are deleted from ARMSX3, and it is taken out of Primary and Landscape. To use it again, choose the theme file again.")
        case (true, false):
            message = String(localized: "Its files are deleted from ARMSX3, and it is taken out of Primary. To use it again, choose the theme file again.")
        case (false, true):
            message = String(localized: "Its files are deleted from ARMSX3, and it is taken out of Landscape. To use it again, choose the theme file again.")
        case (false, false):
            message = String(localized: "Its files are deleted from ARMSX3. To use it again, choose the theme file again.")
        }
        ControllerPrompt.shared.ask(String(localized: "Remove “\(entry.meta.name)”?"), message: message, actions: [
            .init(title: String(localized: "Remove"), isDestructive: true, run: { remove(entry) }),
            .cancel,
        ])
    }

    /// A controller on the removed row lands on the next theme row, else the previous, else the last Landscape row.
    private func remove(_ entry: ThemeEntry) {
        let index = model.entries.firstIndex { $0.folderName == entry.folderName } ?? 0
        guard model.remove(entry) else { return }
        let rest = model.entries
        let landscape = "settings.background.\(ThemeLibraryModel.word(.landscape))."
        let next = index < rest.count ? Self.themeIDPrefix + rest[index].folderName
            : rest.last.map { Self.themeIDPrefix + $0.folderName }
            ?? landscape + (settings.backgroundLandscapeAsset != nil ? "remove" : "theme-file")
        navigation?.moveFocus(afterRemoving: Self.themeIDPrefix + entry.folderName, to: next)
    }

    private func offerRemove(navigationID id: String) -> Bool {
        guard id.hasPrefix(Self.themeIDPrefix), model.importing == nil,
              let entry = model.entry(named: String(id.dropFirst(Self.themeIDPrefix.count))) else { return false }
        confirmRemove(entry)
        return true
    }

    private func say(_ sentence: String) {
        ControllerPrompt.shared.ask(sentence, message: "", actions: [.ok])
    }

    private func offer(_ pending: Pending, large: Bool) {
        guard large else { return keep(pending) }
        ControllerPrompt.shared.ask(String(localized: "This file is large and may use a lot of storage."), message: "", actions: [
            .init(title: String(localized: "Keep"), run: { keep(pending) }),
            .cancel,
        ])
    }

    private func keep(_ pending: Pending) {
        do {
            switch pending {
            case .data(let data, let ext, let which):
                set(try BackgroundStorage.importData(data, fileExtension: ext, replacing: nil), which)
            case .file(let url, let which):
                let started = url.startAccessingSecurityScopedResource()
                defer { if started { url.stopAccessingSecurityScopedResource() } }
                set(try BackgroundStorage.importFile(from: url, replacing: nil), which)
            case .theme(let url, let which):
                // The model owns the import, so leaving the pane does not cancel it; it writes its own witness lines.
                Task { await model.importTheme(url, into: which) }
                return
            }
        } catch {
            say(String(localized: "That file couldn't be used as a background."))
        }
    }

    // The old file goes once the new one is in place, so a failed pick keeps the old background.
    private func set(_ asset: BackgroundAsset?, _ which: Slot) {
        model.setSlot(asset, which)
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}

private struct ThemeFileHint: ViewModifier {
    let slot: BackgroundPaneView.Slot
    let importing: Bool

    func body(content: Content) -> some View {
        if importing {
            content
        } else {
            content.accessibilityHint(slot == .primary ? Text("Imports a PS3 theme file for the Primary background.")
                                      : Text("Imports a PS3 theme file for the Landscape background."))
        }
    }
}

private struct NavigationSessionReader: View {
    @Environment(\.controllerAccessibilityNavigationSession) private var session
    let found: (ControllerAccessibilityNavigationSession?) -> Void

    var body: some View {
        Color.clear.onAppear { found(session) }
    }
}

/// Nothing marks the theme in use.
private struct ThemeRow: View {
    let entry: ThemeEntry
    let value: String
    let choose: () -> Void
    let remove: () -> Void

    var body: some View {
        Button(action: choose) {
            HStack(spacing: 16) {
                ZStack {
                    Color(.tertiarySystemFill)
                    StaticLibraryBackgroundImageView(url: entry.stillURL, fitMode: .fill, size: CGSize(width: 64, height: 36))
                }
                .frame(width: 64, height: 36)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.meta.name)
                        .font(.body)
                        // Color, not the hierarchical style, which a Form button resolves to its tint.
                        .foregroundStyle(Color.primary)
                        .lineLimit(2)
                        .truncationMode(.tail)
                    Text(entry.meta.kindWord)
                        .font(.subheadline)
                        .foregroundStyle(Color.secondary)
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button("Remove", role: .destructive, action: remove)
        }
        .contextMenu {
            Button("Remove Theme", systemImage: "trash", role: .destructive, action: remove)
        }
        .accessibilityLabel(Text(verbatim: entry.meta.name))
        .accessibilityValue(Text(verbatim: value))
        .accessibilityHint(Text("Choose where to use this theme, or remove it."))
        .accessibilityAction(named: Text("Remove Theme"), remove)
    }
}
