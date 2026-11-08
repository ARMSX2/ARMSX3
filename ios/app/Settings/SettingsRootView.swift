// SPDX-License-Identifier: GPL-2.0-only
// The Settings screen and its hosts. No leaf is named here: a host test reads this file's literals to hold that.

import SwiftUI

// The lifted RootView's navigation path element.
typealias SettingsPane = SettingsPaneKind

extension SettingsPaneKind {
    // Derived from the title, never written down.
    var navigationKey: String { title.lowercased().filter { $0.isLetter || $0.isNumber } }

    static let emulationPanes: [SettingsPaneKind] = [.renderer, .shaders, .cpu, .audio, .sounds, .performance, .fixes, .network, .rpcn]
    static let controlPanes: [SettingsPaneKind] = [.controllers, .hotkeys, .skins, .background]
    static let managePanes: [SettingsPaneKind] = [.presets, .all, .backup, .jitDevice, .diagnostics, .about]
    static let rootPanes = emulationPanes + controlPanes + managePanes
}

@MainActor @Observable final class SettingsScreenModel {
    let serial: String?
    let gameTitle: String
    var scope: SettingsScope
    let fromGameHost: Bool
    var gameRunning: Bool
    let help: SettingsHelp?
    private(set) var dump: SettingsDump?
    private(set) var loading = false
    // Nil while the tree is good.
    private(set) var failureStatus: Int?
    // The hosts show it as an alert.
    var failure: SettingsFailure?

    // A UI preference in SettingsStore, never a core key.
    var showAdvanced: Bool {
        get { SettingsStore.shared.showAdvancedSettings }
        set { SettingsStore.shared.showAdvancedSettings = newValue }
    }

    static let bundledHelp = Bundle.main.url(forResource: "settings-help", withExtension: "json").flatMap {
        SettingsHelp(url: $0, overlay: Bundle.main.url(forResource: "settings-help-ios", withExtension: "json"))
    }

    init(serial: String? = nil, gameTitle: String = "", scope: SettingsScope = .allGames, fromGameHost: Bool = false,
         gameRunning: Bool = false, help: SettingsHelp? = SettingsScreenModel.bundledHelp) {
        self.serial = serial
        self.gameTitle = gameTitle
        self.scope = scope
        self.fromGameHost = fromGameHost
        self.gameRunning = gameRunning
        self.help = help
    }

    private var scopedSerial: String? { scope == .game ? serial : nil }

    func load() async {
        loading = true
        let (data, status) = await EmulatorSession.shared.settingsDump(serial: serial)
        let decoded = status == 0 ? data.flatMap(SettingsModel.decode) : nil
        dump = decoded
        failureStatus = decoded == nil ? status : nil
        loading = false
    }

    func leaves(_ pane: SettingsPane) -> [SettingLeaf] {
        guard let dump else { return [] }
        return SettingsModel.leaves(pane, in: dump, showAdvanced: showAdvanced)
    }

    func leaf(_ path: [String]) -> SettingLeaf? {
        dump?.leaves.first { $0.path == path }
    }

    func value(_ leaf: SettingLeaf) -> String { SettingsModel.effective(leaf, scope: scope) }

    func markers(_ leaf: SettingLeaf) -> [SettingMarker] {
        SettingsModel.markers(leaf, scope: scope, gameRunning: gameRunning, fromGameHost: fromGameHost)
    }

    func changedCount(_ pane: SettingsPane) -> Int { SettingsModel.changedCount(leaves(pane), scope: scope) }

    // On 0 the row shows the new value; otherwise it keeps the core's old one.
    @discardableResult
    func set(_ leaf: SettingLeaf, value: String) async -> Int {
        let (status, _) = await EmulatorSession.shared.setSetting(leaf.path, value: value, serial: scopedSerial,
                                                                  liveIds: leaf.liveId.map { [$0] } ?? [])
        if status == 0 {
            update(leaf.path) { scope == .game ? ($0.game = value) : ($0.global = value) }
        } else {
            failure = SettingsFailure(status: status, setting: leaf.path.last ?? "", value: value, scope: scope)
        }
        return status
    }

    @discardableResult
    func reset(_ leaf: SettingLeaf) async -> Int {
        await reset([leaf])
    }

    // Nothing to reset sends nothing, because a per-game reset with no paths deletes the whole file.
    @discardableResult
    func resetPane(_ pane: SettingsPane) async -> Int {
        await reset(leaves(pane).filter { SettingsModel.resettable($0, scope: scope) })
    }

    private func reset(_ targets: [SettingLeaf]) async -> Int {
        guard !targets.isEmpty else { return 0 }
        let (status, _) = await EmulatorSession.shared.resetSettings(targets.map(\.path), serial: scopedSerial,
                                                                     liveIds: targets.compactMap(\.liveId))
        if status == 0 {
            for leaf in targets {
                update(leaf.path) { scope == .game ? ($0.game = nil) : ($0.global = $0.defaultValue) }
            }
        } else {
            failure = SettingsFailure(status: status, setting: targets.count == 1 ? targets[0].path.last ?? "" : String(localized: "these settings"),
                                      value: nil, scope: scope)
        }
        return status
    }

    // A per-game reset with no paths removes the whole override file.
    @discardableResult
    func resetGame() async -> Int {
        guard let serial else { return 0 }
        let overridden = dump?.leaves.filter { $0.game != nil } ?? []
        let (status, _) = await EmulatorSession.shared.resetSettings([], serial: serial, liveIds: overridden.compactMap(\.liveId))
        if status == 0 {
            for leaf in overridden { update(leaf.path) { $0.game = nil } }
        } else {
            failure = SettingsFailure(status: status, setting: String(localized: "this game's settings"), value: nil, scope: .game)
        }
        return status
    }

    // The caller removes the folder when the sheet closes. Nil on failure.
    func exportGame() async -> URL? {
        guard let serial else { return nil }
        let (yaml, status) = await EmulatorSession.shared.exportSettings(serial: serial)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let file = folder.appendingPathComponent(serial + ".yml")
        if status == 0, (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil,
           (try? Data(yaml.utf8).write(to: file)) != nil {
            return file
        }
        failure = SettingsFailure(status: status == 0 ? -1 : status, setting: "", value: nil, scope: .game, exporting: true)
        return nil
    }

    func resetRow(navigationID: String) -> Bool {
        guard let leaf = dump?.leaves.first(where: {
            let id = SettingRow.navigationID($0)
            return navigationID == id || navigationID == id + ".down" || navigationID == id + ".up"
        }), SettingsModel.resettable(leaf, scope: scope) else { return false }
        Task { await reset(leaf) }
        return true
    }

    // Nil while there is no tree.
    func search(_ query: String) -> (results: [SettingLeaf], hiddenAdvanced: Int)? {
        guard let dump else { return nil }
        return SettingsModel.search(dump, query: query, help: help, showAdvanced: showAdvanced)
    }

    private func update(_ path: [String], _ change: (inout SettingLeaf) -> Void) {
        guard let index = dump?.leaves.firstIndex(where: { $0.path == path }) else { return }
        change(&dump!.leaves[index])
    }
}

// -19 the setting refused the value, -20 the file was not written, anything else names its status.
struct SettingsFailure: Identifiable {
    let id = UUID()
    let status: Int
    let setting: String
    let value: String?
    let scope: SettingsScope
    var exporting = false

    var title: String {
        if exporting { return String(localized: "Couldn't export this game's settings (status \(status))") }
        switch status {
        case -20: return scope == .game ? String(localized: "Couldn't save this game's settings (status \(status))")
                                        : String(localized: "Couldn't save this setting (status \(status))")
        case -19: return String(localized: "Couldn't change \(setting)")
        default: return String(localized: "Couldn't change \(setting) (status \(status))")
        }
    }

    var message: String {
        if exporting { return String(localized: "Nothing was shared. Try again.") }
        switch status {
        case -20: return String(localized: "Your change wasn't kept. Try again; if it keeps happening, check that the device has free space.")
        case -19:
            return String(localized: "The emulator didn't accept “\(value ?? "")”, so the setting keeps its old value. Try a different value.")
        default: return String(localized: "The setting keeps its old value.")
        }
    }
}

extension View {
    // On the NavigationStack, so it shows over whichever page is on top.
    func settingsFailureAlert(_ model: SettingsScreenModel) -> some View {
        alert(model.failure?.title ?? "", isPresented: Binding(get: { model.failure != nil }, set: { if !$0 { model.failure = nil } }),
              presenting: model.failure) { _ in
            Button("OK", role: .cancel) {}
        } message: { failure in
            Text(failure.message)
        }
    }
}

struct SettingsControllerContext {
    var input: MenuControllerInputRouter?
    var onPreviousTab: (@MainActor () -> Bool)?
    var onNextTab: (@MainActor () -> Bool)?
    var onBoundary: (@MainActor (MenuControllerCommand) -> Bool)?
    // Nil leaves Back to the frame.
    var onCloseHost: (@MainActor () -> Bool)?
}

struct SettingsPage<Content: View>: View {
    @ViewBuilder let content: Content
    @Environment(\.uiAccentColour) private var accentColour

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                content
            }
            .padding(.horizontal)
            .padding(.vertical, 24)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(OverlayFrostBackground().ignoresSafeArea())
        .tint(accentColour)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)
    }
}

struct SettingsScreen: View {
    let model: SettingsScreenModel
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State var query = ""
    @State private var confirmingGameReset = false
    @State private var sharing: SharedSettingsFile?
    @Environment(\.inGameScopeBar) private var inGameScopeBar
    @Environment(\.inGameScopeCompact) private var inGameScopeCompact

    var body: some View {
        SettingsPage {
            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                rootList
            } else {
                searchResults
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search settings")
        .navigationDestination(for: SettingsPane.self) { pane in
            Group {
                if pane == .presets {
                    PresetsView(settings: model, path: $path, controller: controller)
                } else if pane == .controllers {
                    ControllersPaneView(path: $path, controller: controller)
                } else if pane == .hotkeys {
                    HotkeysView(path: $path, controller: controller)
                } else if pane == .shaders {
                    ShadersPaneView(path: $path, controller: controller)
                } else if pane == .skins {
                    SkinsPaneView(path: $path, controller: controller)
                } else if pane == .sounds {
                    SoundsPane(path: $path, controller: controller)
                } else if pane == .background {
                    BackgroundPaneView(path: $path, controller: controller)
                } else if pane == .backup {
                    BackupPaneView(path: $path, controller: controller)
                } else if pane == .jitDevice {
                    JitDevicePane(host: .settings, controller: controller,
                                  onBack: { if !path.isEmpty { path.removeLast() }; return true })
                } else if pane == .rpcn {
                    RpcnPane(controller: controller, onBack: { if !path.isEmpty { path.removeLast() }; return true })
                } else if pane == .diagnostics {
                    DiagnosticsPane(path: $path, controller: controller)
                } else if pane == .about {
                    AboutView(path: $path, controller: controller)
                } else {
                    SettingsPaneView(pane: pane, model: model, path: $path, controller: controller)
                }
            }
            .toolbar {
                if inGameScopeBar {
                    ToolbarItem(placement: .topBarTrailing) { SettingsScopePicker(model: model, compact: inGameScopeCompact).fixedSize() }
                }
            }
        }
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.root",
                                           onBack: controller.onCloseHost,
                                           onContextMenu: { id in model.resetRow(navigationID: id) },
                                           onPreviousTab: controller.onPreviousTab, onNextTab: controller.onNextTab,
                                           onBoundary: controller.onBoundary)
        .alert("Remove all of this game's settings?", isPresented: $confirmingGameReset) {
            Button("Remove", role: .destructive) { Task { await model.resetGame() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(model.gameTitle) goes back to the All games settings. Fixes from the online database still apply.")
        }
        .sheet(item: $sharing) { shared in
            ShareSheet(url: shared.url) {
                try? FileManager.default.removeItem(at: shared.url.deletingLastPathComponent())
                sharing = nil
            }
        }
    }

    // The fixed pane list: it never renders empty, only its trailing counts vary.
    @ViewBuilder private var rootList: some View {
        // RPCN is not in a game's host: the account is global, and a sign-in mid-game would race the game's own session.
        paneCard(model.fromGameHost ? SettingsPane.emulationPanes.filter { $0 != .rpcn } : SettingsPane.emulationPanes,
                 title: String(localized: "Emulation"))
        paneCard(SettingsPane.controlPanes, title: String(localized: "Controls"))
        // JIT and Device is not in a game's host, where nothing in it can change.
        paneCard(model.fromGameHost ? SettingsPane.managePanes.filter { $0 != .jitDevice } : SettingsPane.managePanes,
                 title: String(localized: "Manage"))
        OverlaySectionCard {
            OverlayToggleRow(controllerNavigationID: "settings.advanced", label: "Show advanced settings",
                             systemImage: "hammer", isOn: Binding(get: { model.showAdvanced }, set: { model.showAdvanced = $0 }))
            Text("Debug and developer settings. Changing them can stop games from working.")
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                // The caption starts where the switch's label starts at every text size.
                .overlayRowLabelInset()
        }
        if model.fromGameHost && model.scope == .game {
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.game.export", label: "Export This Game's Settings",
                                 systemImage: "square.and.arrow.up") {
                    Task { if let url = await model.exportGame() { sharing = SharedSettingsFile(url: url) } }
                }
                OverlayActionRow(controllerNavigationID: "settings.game.reset", label: "Reset This Game's Settings",
                                 systemImage: "arrow.counterclockwise", isDestructive: true) {
                    confirmingGameReset = true
                }
            }
        }
    }

    // Grouped by path, one card each, because a path between rows read as the last line of the row above.
    @ViewBuilder private var searchResults: some View {
        if let state = SettingsTreeState(model) {
            state
        } else if let found = model.search(query) {
            if found.results.isEmpty {
                SettingsMessage(heading: String(localized: "No settings match “\(query.trimmingCharacters(in: .whitespacesAndNewlines))”"),
                                message: Self.emptyMessage(hiddenAdvanced: found.hiddenAdvanced, showAdvanced: model.showAdvanced))
            } else {
                ForEach(SearchGroup.groups(found.results)) { group in
                    OverlaySectionCard(title: group.id) {
                        ForEach(group.leaves) { leaf in
                            SettingRow(leaf: leaf, model: model, path: $path)
                        }
                    }
                }
            }
        }
    }

    static func emptyMessage(hiddenAdvanced: Int, showAdvanced: Bool) -> String {
        guard hiddenAdvanced > 0 && !showAdvanced else { return String(localized: "Try a shorter word.") }
        return String(localized: "Try a shorter word. \(hiddenAdvanced) more match among advanced settings. Turn on Show advanced settings to see them.")
    }

    private func paneCard(_ panes: [SettingsPane], title: String) -> some View {
        OverlaySectionCard(title: title) {
            ForEach(panes, id: \.self) { pane in
                let changed = model.loading ? 0 : model.changedCount(pane)
                OverlayActionRow(controllerNavigationID: "settings.pane." + pane.navigationKey, label: pane.title,
                                 systemImage: pane.systemImage, trailingValue: changed > 0 ? String(localized: "\(changed) changed") : nil) {
                    path.append(pane)
                }
            }
        }
    }
}

// Consecutive, since the tree's order keeps a pane's leaves together.
struct SearchGroup: Identifiable {
    let id: String
    var leaves: [SettingLeaf]

    static func groups(_ leaves: [SettingLeaf]) -> [SearchGroup] {
        var out: [SearchGroup] = []
        for leaf in leaves {
            let path = leaf.path.dropLast().joined(separator: " › ")
            if out.last?.id == path { out[out.count - 1].leaves.append(leaf) } else { out.append(SearchGroup(id: path, leaves: [leaf])) }
        }
        return out
    }
}

struct SharedSettingsFile: Identifiable {
    let id = UUID()
    let url: URL
}

struct SettingsRootView: View {
    @Binding var navigationPath: [SettingsPane]
    let resetToRootRequest: Int
    let onNavigationPathActivityChanged: (Bool) -> Void
    let controllerInput: MenuControllerInputRouter?
    let onPreviousControllerTab: @MainActor () -> Bool
    let onNextControllerTab: @MainActor () -> Bool
    let onControllerBoundary: @MainActor (MenuControllerCommand) -> Bool
    @State private var model = SettingsScreenModel()

    init(navigationPath: Binding<[SettingsPane]>, resetToRootRequest: Int,
         onNavigationPathActivityChanged: @escaping (Bool) -> Void, controllerInput: MenuControllerInputRouter?,
         onPreviousControllerTab: @escaping @MainActor () -> Bool, onNextControllerTab: @escaping @MainActor () -> Bool,
         onControllerBoundary: @escaping @MainActor (MenuControllerCommand) -> Bool) {
        _navigationPath = navigationPath
        self.resetToRootRequest = resetToRootRequest
        self.onNavigationPathActivityChanged = onNavigationPathActivityChanged
        self.controllerInput = controllerInput
        self.onPreviousControllerTab = onPreviousControllerTab
        self.onNextControllerTab = onNextControllerTab
        self.onControllerBoundary = onControllerBoundary
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            SettingsScreen(model: model, path: $navigationPath,
                           controller: SettingsControllerContext(input: controllerInput, onPreviousTab: onPreviousControllerTab,
                                                                 onNextTab: onNextControllerTab, onBoundary: onControllerBoundary))
                .navigationTitle("Settings")
                .navigationBarTitleDisplayMode(.inline)
        }
        .settingsFailureAlert(model)
        .preferredColorScheme(.dark)
        .task { await model.load() }
        .onChange(of: resetToRootRequest) { navigationPath = [] }
        .onChange(of: navigationPath.isEmpty, initial: true) { _, empty in onNavigationPathActivityChanged(!empty) }
    }
}

// Both scopes read the same dump, so switching sends nothing to the bridge.
struct GameSettingsChrome: View {
    let model: SettingsScreenModel
    // A phone in landscape is short, so the header and the switch sit side by side and compact there.
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        let short = verticalSizeClass == .compact
        let layout = short ? AnyLayout(HStackLayout(spacing: 12)) : AnyLayout(VStackLayout(spacing: 12))
        layout {
            OverlayHeader(systemImage: "slider.horizontal.3", title: "Game Settings", subtitle: model.gameTitle, compact: short)
                .frame(maxWidth: .infinity, alignment: .leading)
            SettingsScopePicker(model: model)
        }
        .frame(maxWidth: 720)
        .padding(.horizontal)
        .padding(.top, short ? 0 : 16)
    }
}

// `compact` draws a menu for a bar too narrow for both segments beside the page's name.
struct SettingsScopePicker: View {
    let model: SettingsScreenModel
    var compact = false

    var body: some View {
        let picker = Picker("Scope", selection: Binding(get: { model.scope }, set: { model.scope = $0 })) {
            Text("This game").tag(SettingsScope.game)
            Text("All games").tag(SettingsScope.allGames)
        }
        Group {
            if compact {
                picker.pickerStyle(.menu)
            } else {
                picker.pickerStyle(.segmented)
            }
        }
        .controllerAccessibilityActionTarget(id: "settings.scope", label: String(localized: "Scope")) {
            model.scope = model.scope == .game ? .allGames : .game
        }
    }
}

// A menu while the card is narrow, so the page's name keeps its room.
private struct InGameScopeBarKey: EnvironmentKey {
    static let defaultValue = false
}

private struct InGameScopeCompactKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var inGameScopeBar: Bool {
        get { self[InGameScopeBarKey.self] }
        set { self[InGameScopeBarKey.self] = newValue }
    }

    var inGameScopeCompact: Bool {
        get { self[InGameScopeCompactKey.self] }
        set { self[InGameScopeCompactKey.self] = newValue }
    }
}

// `model` and `path` are injectable for the smoke scene; the library passes neither.
struct GameSettingsSheet: View {
    @State private var model: SettingsScreenModel
    @State private var path: [SettingsPane]
    @Environment(\.dismiss) private var dismiss

    init(serial: String, title: String, model: SettingsScreenModel? = nil, path: [SettingsPane] = []) {
        _model = State(initialValue: model ?? SettingsScreenModel(serial: serial, gameTitle: title, scope: .game, fromGameHost: true))
        _path = State(initialValue: path)
    }

    var body: some View {
        VStack(spacing: 0) {
            GameSettingsChrome(model: model)
            NavigationStack(path: $path) {
                SettingsScreen(model: model, path: $path,
                               controller: SettingsControllerContext(onCloseHost: { dismiss(); return true }))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
                    }
            }
            .settingsFailureAlert(model)
        }
        .background(OverlayFrostBackground().ignoresSafeArea())
        .presentationDetents([.large])
        .preferredColorScheme(.dark)
        .task { await model.load() }
    }
}

// `model` and `path` are injectable for the smoke scene; GameScreenView passes neither.
struct InGameSettingsPanel: View {
    let onBack: () -> Void
    var controllerInput: MenuControllerInputRouter? = nil
    @State private var model: SettingsScreenModel
    @State private var path: [SettingsPane]
    // Below about 360 points the bar has no room for both segments beside the page's name.
    @State private var narrow = false

    init(game: Rpcs3GameEntry, onBack: @escaping () -> Void, controllerInput: MenuControllerInputRouter? = nil,
         model: SettingsScreenModel? = nil, path: [SettingsPane] = []) {
        _path = State(initialValue: path)
        self.onBack = onBack
        self.controllerInput = controllerInput
        _model = State(initialValue: model ?? SettingsScreenModel(serial: game.serial, gameTitle: game.title, scope: .game,
                                                                  fromGameHost: true, gameRunning: true))
    }

    var body: some View {
        OverlayPanelScaffold {
            // One header: the stack's bar holds Back, the page's name and the scope switch, so landscape keeps rows in view.
            NavigationStack(path: $path) {
                SettingsScreen(model: model, path: $path,
                               controller: SettingsControllerContext(input: controllerInput,
                                                                     onCloseHost: { onBack(); return true }))
                    // "Settings", not "Game Settings": the switch beside it says whose, and the longer title squeezed it.
                    .navigationTitle("Settings")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Back", systemImage: "chevron.backward", action: onBack)
                                .controllerAccessibilityActionTarget(id: "settings.back", label: String(localized: "Back"),
                                                                     action: onBack)
                        }
                        ToolbarItem(placement: .topBarTrailing) { SettingsScopePicker(model: model, compact: narrow).fixedSize() }
                    }
            }
            .onGeometryChange(for: Bool.self) { $0.size.width < 360 } action: { narrow = $0 }
            .environment(\.inGameScopeBar, true)
            .environment(\.inGameScopeCompact, narrow)
            .settingsFailureAlert(model)
            // The overlay sets no colour scheme of its own; the switch and the stack's bar follow the dark kit.
            .environment(\.colorScheme, .dark)
        }
        .task { await model.load() }
    }
}
