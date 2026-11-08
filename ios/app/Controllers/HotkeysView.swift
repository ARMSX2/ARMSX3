// SPDX-License-Identifier: GPL-2.0-only
// The Hotkeys pane over HotkeyTable's rules. One global binding set; it never writes a core setting.

import Combine
import SwiftUI

@MainActor @Observable final class HotkeysModel {
    enum Alert: Equatable {
        // `chord` (raw) already belongs to `holder`; the player asked for it on `action`.
        case conflict(chord: String, holder: String, action: String)
        // The chord is Quick Menu's, which never loses it; `action` is the row being captured.
        case refused(action: String)
    }

    private(set) var capturing: String?
    var alert: Alert?
    // The no-controller smoke scene injects `readsController`, since its simulator gets the Mac's controller.
    private(set) var controllerConnected: Bool
    @ObservationIgnored private let readsController: @MainActor () -> Bool
    @ObservationIgnored private var timeout: Task<Void, Never>?

    init(readsController: @escaping @MainActor () -> Bool = { GamepadPoller.readsAController }) {
        self.readsController = readsController
        controllerConnected = readsController()
    }

    func refreshController() {
        controllerConnected = readsController()
    }

    var table: HotkeyTable { SettingsStore.shared.hotkeys }
    var rows: [String] { HotkeyTable.actions }

    func trailing(_ action: String) -> String {
        capturing == action ? String(localized: "Press the new hotkey…") : HotkeyTable.title(table.chord(for: action))
    }

    // The router cancels any capture already running before this one starts, and 10 s with no chord cancels.
    func startCapture(_ action: String, router: MenuControllerInputRouter?) {
        guard readsController(), let router else { return }
        router.beginHotkeyCapture { [weak self] chord in self?.captured(chord, for: action) }
        capturing = action
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled, self?.capturing == action else { return }
            router.cancelHotkeyCapture()
        }
    }

    // Quick Menu is never left without a chord, so it has no Clear Hotkey.
    func canClear(_ action: String) -> Bool { action != HotkeyTable.quickMenu }

    func clear(_ action: String) {
        guard canClear(action), let next = table.clearing(action) else { return }
        SettingsStore.shared.hotkeys = next
    }

    // Only the three defaults keep a chord.
    func reset() {
        SettingsStore.shared.hotkeys = HotkeyTable.resetTable
    }

    // nil when the capture ended with none.
    func captured(_ chord: String?, for action: String) {
        guard let chord else {
            if capturing == action {
                capturing = nil
                timeout?.cancel()
            }
            return
        }
        capturing = nil
        timeout?.cancel()
        switch table.assigning(chord, to: action, replacing: false) {
        case .assigned(let next):
            SettingsStore.shared.hotkeys = next
        case .conflict(let holder):
            alert = .conflict(chord: chord, holder: holder, action: action)
        case .refused:
            alert = .refused(action: action)
        }
    }

    // The chord moves, and its old holder is left with none.
    func useIt() {
        guard case .conflict(let chord, _, let action) = alert else { return }
        alert = nil
        if case .assigned(let next) = table.assigning(chord, to: action, replacing: true) {
            SettingsStore.shared.hotkeys = next
        }
    }
}

struct HotkeysView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var model: HotkeysModel
    @State private var confirmingReset = false
    @State private var clearingAction: String?
    @Environment(\.menuControllerInputRouter) private var environmentRouter

    // The host's router, or the one RootView puts in the environment (the library's sheet passes none).
    private var router: MenuControllerInputRouter? { controller.input ?? environmentRouter }

    init(path: Binding<[SettingsPane]>, controller: SettingsControllerContext = SettingsControllerContext(),
         model: HotkeysModel? = nil) {
        _path = path
        self.controller = controller
        _model = State(initialValue: model ?? HotkeysModel())
    }

    static func navigationID(_ action: String) -> String { "settings.hotkey." + action }

    var body: some View {
        SettingsPage {
            OverlaySectionCard {
                if !model.controllerConnected {
                    Text("Connect a controller to change hotkeys.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                ForEach(model.rows, id: \.self) { action in
                    let warm = model.capturing == action
                    VStack(alignment: .leading, spacing: 4) {
                        OverlayActionRow(controllerNavigationID: Self.navigationID(action), label: HotkeyTable.actionTitle(action),
                                         trailingValue: model.trailing(action), keepsTrailingValue: true) {
                            model.startCapture(action, router: router)
                        }
                        .transformEnvironment(\.controllerTextAppearance) { if warm { $0.secondaryColor = OverlayTheme.warm } }
                        .contextMenu {
                            if model.canClear(action) {
                                Button("Clear Hotkey", role: .destructive) { model.clear(action) }
                            }
                        }
                        if let caption = HotkeyTable.caption(action) {
                            Text(caption)
                                .font(.subheadline)
                                .foregroundStyle(OverlayTheme.textSecondary)
                        }
                    }
                }
            }
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.hotkeys.reset", label: "Reset Hotkeys",
                                 systemImage: "arrow.counterclockwise", isDestructive: true) {
                    confirmingReset = true
                }
            }
            .padding(.top, 16)
        }
        .navigationTitle(SettingsPane.hotkeys.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.hotkeys.navigationKey,
                                           onBack: { pop() }, onContextMenu: { id in offerClear(navigationID: id) },
                                           onPreviousTab: controller.onPreviousTab, onNextTab: controller.onNextTab,
                                           onBoundary: controller.onBoundary)
        .onReceive(Publishers.MergeMany(GamepadPoller.controllerChanges.map { NotificationCenter.default.publisher(for: $0) })) { _ in
            model.refreshController()
        }
        .onAppear { model.refreshController() }
        .onDisappear { router?.cancelHotkeyCapture() }
        .alert(conflictTitle, isPresented: shown(conflict != nil)) {
            Button("Use It") { model.useIt() }
            Button("Cancel", role: .cancel) { model.alert = nil }
        } message: {
            Text(conflictMessage)
        }
        .alert("Quick Menu needs this hotkey", isPresented: shown(refusedAction != nil)) {
            Button("Pick Other Buttons") {
                let action = refusedAction
                model.alert = nil
                if let action { model.startCapture(action, router: router) }
            }
        } message: {
            Text("Pick a different pair of buttons.")
        }
        .confirmationDialog(clearingAction.map(HotkeyTable.actionTitle) ?? "", isPresented: Binding(
            get: { clearingAction != nil }, set: { if !$0 { clearingAction = nil } }), titleVisibility: .visible,
                            presenting: clearingAction) { action in
            Button("Clear Hotkey", role: .destructive) { model.clear(action) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Reset all hotkeys?", isPresented: $confirmingReset) {
            Button("Reset", role: .destructive) { model.reset() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Only Quick Menu, Quick Save and Quick Load keep a hotkey, with their original buttons.")
        }
    }

    private var conflict: (chord: String, holder: String, action: String)? {
        if case .conflict(let chord, let holder, let action) = model.alert { return (chord, holder, action) }
        return nil
    }

    private var refusedAction: String? {
        if case .refused(let action) = model.alert { return action }
        return nil
    }

    private var conflictTitle: String {
        guard let conflict else { return "" }
        return String(localized: "\(HotkeyTable.title(conflict.chord)) already does \(HotkeyTable.actionTitle(conflict.holder))")
    }

    private var conflictMessage: String {
        guard let conflict else { return "" }
        let holder = HotkeyTable.actionTitle(conflict.holder)
        return String(localized: "Use it for \(HotkeyTable.actionTitle(conflict.action)) instead? \(holder) will have no hotkey.")
    }

    // Dismissing either alert by any route clears it.
    private func shown(_ presented: Bool) -> Binding<Bool> {
        Binding(get: { presented }, set: { if !$0 { model.alert = nil } })
    }

    private func offerClear(navigationID: String) -> Bool {
        guard let action = model.rows.first(where: { Self.navigationID($0) == navigationID }), model.canClear(action) else { return false }
        clearingAction = action
        return true
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}
