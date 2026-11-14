// SPDX-License-Identifier: GPL-2.0-only
// Controller Mapping as XMB lists over the app's ControllersModel and HotkeyTable, ported from Android's XmbControls.kt.

import SwiftUI

/// The Controllers screen's model for the XMB's lists, and the hotkey listening for a chord.
@MainActor @Observable final class XmbControlsStore {
    static let shared = XmbControlsStore()

    /// All games' mapping, as the Settings screen edits it, so it never asks this game or all.
    let model = ControllersModel()
    private(set) var hotkeyCapture: String?
    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var hotkeyTimeout: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private init() {
        // Pads come and go while the lists are up, so who holds each player is read again.
        observers = GamepadPoller.controllerChanges.map { name in
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { XmbControlsStore.shared.model.refreshController() }
            }
        }
    }

    func loadOnce() {
        guard !loaded else { return }
        loaded = true
        Task {
            model.refreshController()
            await model.load()
        }
    }

    /// As the Hotkeys screen listens: the router cancels any capture already running, and 10 s with no chord cancels.
    func captureHotkey(_ action: String, host: XmbHost) {
        guard GamepadPoller.readsAController, let router = host.controllerInput else {
            host.notice(String(localized: "Connect a controller to change hotkeys."))
            return
        }
        router.beginHotkeyCapture { [weak self, weak host] chord in self?.captured(chord, for: action, host: host) }
        hotkeyCapture = action
        hotkeyTimeout?.cancel()
        hotkeyTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled, self?.hotkeyCapture == action else { return }
            router.cancelHotkeyCapture()
        }
    }

    /// HotkeysModel.captured, with its two alerts asked in the XMB's dialog.
    private func captured(_ chord: String?, for action: String, host: XmbHost?) {
        hotkeyCapture = nil
        hotkeyTimeout?.cancel()
        guard let chord, let host else { return }
        let settings = SettingsStore.shared
        switch settings.hotkeys.assigning(chord, to: action, replacing: false) {
        case .assigned(let next):
            settings.hotkeys = next
        case .conflict(let holder):
            let holderTitle = HotkeyTable.actionTitle(holder)
            host.ask(String(localized: "\(HotkeyTable.title(chord)) already does \(holderTitle)") + " "
                         + String(localized: "Use it for \(HotkeyTable.actionTitle(action)) instead? \(holderTitle) will have no hotkey."),
                     buttons: [String(localized: "Use It"), String(localized: "Cancel")], focus: 1) { button in
                guard button == 0, case .assigned(let next) = settings.hotkeys.assigning(chord, to: action, replacing: true) else { return }
                settings.hotkeys = next
            }
        case .refused:
            host.ask(String(localized: "Quick Menu needs this hotkey") + " " + String(localized: "Pick a different pair of buttons."),
                     buttons: [String(localized: "Pick Other Buttons"), String(localized: "Cancel")]) { [weak self, weak host] button in
                guard button == 0, let host else { return }
                self?.captureHotkey(action, host: host)
            }
        }
    }
}

extension XmbHost {
    private static let padIcon = XmbIcon.firmware(rco: XmbRco.sysconf, name: "tex_pd", symbol: "gamecontroller")

    /// Controller Mapping: whose controls are being set, then their buttons, sticks, tilt, hotkeys, rumble and pads.
    func controlsNode(in section: inout XmbSection) -> XmbNode {
        let store = XmbControlsStore.shared
        store.loadOnce()
        let model = store.model
        let icon = Self.padIcon
        let player = model.player
        let mapping = model.mappings[player]
        let players = (0 ..< PlayerSlots.count).map { String(localized: "Player \($0 + 1)") }

        var rows = [
            consoleChoice(&section, "settings.controls.player", String(localized: "Editing"), icon: icon, choices: players,
                          selected: player,
                          help: [model.holder(player), String(localized: "Which player's buttons, sticks and tilt you are editing.")]
                              .joined(separator: "   ")) { choice in
                model.player = choice
            },
            XmbNode(id: "settings.controls.buttons", title: String(localized: "Button Mapping"),
                    subtitle: String(localized: "Tap an action, then press a physical controller button."), icon: icon,
                    kind: .folder(buttonRows(&section, model))),
            XmbNode(id: "settings.controls.sticks", title: String(localized: "Sticks"),
                    subtitle: String(localized: "L2 and R2 press at RPCS3's default point."), icon: icon,
                    kind: .folder(stickRows(&section, model, mapping))),
            XmbNode(id: "settings.controls.tilt", title: String(localized: "Tilt"), subtitle: nil, icon: icon,
                    kind: .folder(tiltRows(&section, player))),
            XmbNode(id: "settings.controls.hotkeys", title: String(localized: "Hotkeys"),
                    subtitle: String(localized: "Bind physical buttons (back paddles work too) to in-game actions. Tap an action, then press the button."),
                    icon: icon, kind: .folder(hotkeyRows(&section, prefix: "settings.controls.hotkey", icon: icon))),
        ]
        rows += rumbleRows(&section, player)
        if model.held.contains(true) {
            rows.append(XmbNode(id: "settings.controls.pads", title: String(localized: "Controllers"),
                                subtitle: String(localized: "Assign each controller to a player. An assignment is remembered per controller and kept when it reconnects."),
                                icon: icon, kind: .folder(padRows(&section, model, players))))
        }
        rows.append(consoleToggle(&section, "settings.controls.keyboard", String(localized: "Use Keyboard as Player \(player + 1)"),
                                  icon: icon, isOn: settings.keyboardSlot == player) { on in
            model.useKeyboard(as: on ? player : -1)
        })
        let reset = String(localized: "Reset")
        rows.append(section.action(XmbNode(id: "settings.controls.reset", title: "\(reset) · \(players[player])", subtitle: nil,
                                           icon: icon, kind: .action)) { [weak self] in
            self?.ask(String(localized: "Reset Player \(player + 1) to the default buttons?") + " "
                          + String(localized: "Its buttons, deadzones and sensitivities go back to the defaults."),
                      buttons: [reset, String(localized: "Cancel")], focus: 1) { button in
                if button == 0 { model.reset() }
            }
        })
        return XmbNode(id: "settings.controls.mapping", title: String(localized: "Controller mapping"),
                       subtitle: String(localized: "Tap an action, then press a physical controller button."), icon: icon,
                       kind: .folder(rows))
    }

    /// Each PS3 button: Cross listens for the press to use, Triangle puts back its default (iOS has no unbound button).
    private func buttonRows(_ section: inout XmbSection, _ model: ControllersModel) -> [XmbNode] {
        let mapping = model.mappings[model.player]
        let keyboard = model.keyboardPlayer
        return ControllersPaneView.rowKeys.map { key in
            let id = "settings.controls.bind." + key
            let standard = (keyboard ? PlayerMapping.keyboardDefaults : PlayerMapping.defaults)[key] ?? ""
            var options: [XmbNode.Option] = []
            if mapping.source(key, keyboard: keyboard) != standard {
                options = [.init(id: "default", title: String(localized: "Reset to Default"))]
                section.options[id] = { _ in model.captured(standard, for: key) }
            }
            let node = XmbNode(id: id, title: key, subtitle: model.trailing(key), icon: Self.padIcon, kind: .action, options: options)
            return section.action(node) { [weak self] in
                model.refreshController()
                guard model.controllerConnected else {
                    self?.notice(String(localized: "Connect a controller to change buttons."))
                    return
                }
                // The router feeds a capture before navigation, so the XMB's own hold on the pad does not need releasing.
                model.startCapture(key, router: self?.controllerInput)
            }
        }
    }

    /// The Controllers screen's Sticks card, which Android keeps on its Controls screen.
    private func stickRows(_ section: inout XmbSection, _ model: ControllersModel, _ mapping: PlayerMapping) -> [XmbNode] {
        let icon = Self.padIcon
        func stick(_ id: String, _ title: String, _ field: WritableKeyPath<PlayerMapping, Int>, range: ClosedRange<Int>, step: Int,
                   unit: String, standard: Int) -> XmbNode {
            let value = mapping[keyPath: field]
            return consoleRange(&section, "settings.controls.stick." + id, title, icon: icon, value: value, range: range, step: step,
                                unit: unit, changed: value != standard) { target in
                model.step(field, by: target - model.mappings[model.player][keyPath: field])
            }
        }
        func invert(_ id: String, _ title: String, _ axis: PlayerMapping.Axis) -> XmbNode {
            let on = mapping.inverted(axis)
            return consoleToggle(&section, "settings.controls.stick." + id, title, icon: icon, isOn: on, changed: on) { want in
                if want != model.mappings[model.player].inverted(axis) { model.setInvert(axis, want) }
            }
        }
        let swapped = mapping.sticksSwapped
        return [
            stick("leftdeadzone", String(localized: "Left Stick Deadzone"), \.leftDeadzone, range: 0 ... 255, step: 8, unit: "", standard: 0),
            stick("leftsensitivity", String(localized: "Left Stick Sensitivity"), \.leftMultiplier, range: 0 ... 200, step: 10, unit: "%",
                  standard: 100),
            stick("rightdeadzone", String(localized: "Right Stick Deadzone"), \.rightDeadzone, range: 0 ... 255, step: 8, unit: "",
                  standard: 0),
            stick("rightsensitivity", String(localized: "Right Stick Sensitivity"), \.rightMultiplier, range: 0 ... 200, step: 10,
                  unit: "%", standard: 100),
            consoleToggle(&section, "settings.controls.stick.swap", String(localized: "Swap Sticks"), icon: icon, isOn: swapped,
                          changed: swapped) { want in
                if want != model.mappings[model.player].sticksSwapped { model.swapSticks() }
            },
            invert("invertleftx", String(localized: "Invert Left Stick X"), .leftX),
            invert("invertlefty", String(localized: "Invert Left Stick Y"), .leftY),
            invert("invertrightx", String(localized: "Invert Right Stick X"), .rightX),
            invert("invertrighty", String(localized: "Invert Right Stick Y"), .rightY),
        ]
    }

    /// The player's tilt, where Android maps buttons to SIXAXIS motions: iOS reads the controller's own motion.
    private func tiltRows(_ section: inout XmbSection, _ player: Int) -> [XmbNode] {
        let icon = Self.padIcon
        let on = !settings.controllerTiltOff.contains(player)
        let key = String(player)
        let bits = settings.controllerTiltInvert[key] ?? 0
        func invert(_ id: String, _ title: String, _ bit: Int) -> XmbNode {
            consoleToggle(&section, "settings.controls.tilt." + id, title, icon: icon, isOn: bits & bit != 0,
                          changed: bits & bit != 0) { [weak self] want in
                guard let self else { return }
                let now = settings.controllerTiltInvert[key] ?? 0
                settings.controllerTiltInvert[key] = want ? now | bit : now & ~bit
            }
        }
        return [
            consoleToggle(&section, "settings.controls.tilt.on", String(localized: "Tilt"), icon: icon, isOn: on, changed: !on) { [weak self] want in
                guard let self else { return }
                settings.controllerTiltOff.removeAll { $0 == player }
                if !want { settings.controllerTiltOff.append(player) }
            },
            invert("x", String(localized: "Invert Tilt X"), 1),
            invert("y", String(localized: "Invert Tilt Y"), 2),
        ]
    }

    /// Rumble per player on iOS, under the keys the Controllers screen keeps.
    private func rumbleRows(_ section: inout XmbSection, _ player: Int) -> [XmbNode] {
        let icon = Self.padIcon
        let store = XmbConsoleSettingsStore.shared
        let enabledKey = RumbleFeed.enabledKey(player)
        let strengthKey = RumbleFeed.strengthKey(player)
        let on = store.bool(enabledKey, true)
        let strength = store.int(strengthKey, 100)
        return [
            consoleToggle(&section, "settings.controls.rumble", String(localized: "Rumble"), icon: icon, isOn: on,
                          help: String(localized: "Player 1 vibrates the phone when it plays on the touch pad or its controller has no rumble."),
                          changed: !on) { store.write($0, enabledKey) },
            consoleRange(&section, "settings.controls.rumble.strength", String(localized: "Rumble Strength"), icon: icon, value: strength,
                         range: 0 ... 100, step: 5, unit: "%", changed: strength != 100) { store.write($0, strengthKey) },
            section.action(XmbNode(id: "settings.controls.rumble.test", title: String(localized: "Test Rumble"), subtitle: nil, icon: icon,
                                   kind: .action)) {
                RumbleFeed.shared.test(slot: player)
            },
        ]
    }

    /// Which player each connected pad is: picking another player swaps the two, as Pin to Player does.
    private func padRows(_ section: inout XmbSection, _ model: ControllersModel, _ players: [String]) -> [XmbNode] {
        model.held.indices.filter { model.held[$0] }.map { slot in
            let targets = model.pinTargets(slot)
            guard !targets.isEmpty else {
                // The keyboard takes its slot back on the poller's next tick, so it is not moved here.
                return XmbNode(id: "settings.controls.pad.\(slot)", title: model.holder(slot), subtitle: players[slot], icon: Self.padIcon,
                               kind: .info)
            }
            return consoleChoice(&section, "settings.controls.pad.\(slot)", model.holder(slot), icon: Self.padIcon, choices: players,
                                 selected: slot) { choice in
                if targets.contains(choice) { model.pin(slot, to: choice) }
            }
        }
    }

    /// The hotkeys: Cross listens for the chord, Triangle clears it; `prefix` keeps each list's rows apart.
    func hotkeyRows(_ section: inout XmbSection, prefix: String, icon: XmbIcon) -> [XmbNode] {
        let store = XmbControlsStore.shared
        let table = settings.hotkeys
        return HotkeyTable.actions.map { action in
            let id = prefix + "." + action
            let chord = table.chord(for: action)
            var options: [XmbNode.Option] = []
            if action != HotkeyTable.quickMenu, chord != nil {
                options = [.init(id: "clear", title: String(localized: "Clear Hotkey"))]
                section.options[id] = { [weak self] _ in
                    guard let self, let next = settings.hotkeys.clearing(action) else { return }
                    settings.hotkeys = next
                }
            }
            let value = store.hotkeyCapture == action ? String(localized: "Press the new hotkey…") : HotkeyTable.title(chord)
            let node = XmbNode(id: id, title: HotkeyTable.actionTitle(action),
                               subtitle: [value, HotkeyTable.caption(action)].compactMap { $0 }.joined(separator: "   "),
                               icon: icon, kind: .action, options: options)
            return section.action(node) { [weak self] in
                guard let self else { return }
                store.captureHotkey(action, host: self)
            }
        }
    }
}
