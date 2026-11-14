// SPDX-License-Identifier: GPL-2.0-only
// The Controllers pane. One mapping per player slot, never per model; in a game the first change asks this game or all.

import AVFAudio
import AVFoundation
import Combine
import GameController
import SwiftUI
import UIKit

enum MicState { case allowed, notAsked, off }

@MainActor @Observable final class ControllersModel {
    let serial: String?
    let scope: SettingsScope
    // Players 1-4's mappings as the core last answered, updated here as each change is written.
    var mappings = Array(repeating: PlayerMapping(), count: PlayerSlots.count)
    // Whether the dump came from the title's own file.
    var perGame = false
    // Nil until answered; the Settings pane (scope .allGames) never asks.
    private(set) var scopeAnswer: SettingsScope?
    enum Question { case scope }
    var alert: Question?
    @ObservationIgnored private var pending: (next: PlayerMapping, player: Int, op: String)?
    // The player whose page is open, or was open last.
    var player = 0
    var showsPlayer = false
    // The PS3 button whose row is capturing a press, or nil.
    private(set) var capturing: String?
    private(set) var controllerConnected: Bool
    private(set) var held = Array(repeating: false, count: PlayerSlots.count)
    // The pad's own name per slot (GCController's vendor name, else its category), "" when free.
    private(set) var heldNames = Array(repeating: "", count: PlayerSlots.count)
    // The slot the keyboard holds as a player's pad, nil when it holds none.
    private(set) var keyboardHeld: Int?
    // So a caller can wait for the last change's writes (the smoke scene does).
    @ObservationIgnored private(set) var writing: Task<Void, Never>?
    @ObservationIgnored private(set) var lastSets = 0
    @ObservationIgnored private let readsController: @MainActor () -> Bool
    @ObservationIgnored private var timeout: Task<Void, Never>?
    // iOS ends the app when the player changes the permission in Settings, so a read at appear is current.
    private(set) var mic: MicState
    @ObservationIgnored private let micState: @MainActor () -> MicState
    private(set) var camera: MicState
    @ObservationIgnored private let cameraState: @MainActor () -> MicState
    private(set) var cameraBack = false
    // The device each player presents in this title (DeviceTables' ordinal).
    private(set) var devices = Array(repeating: 0, count: PlayerSlots.count)
    var choosingDevice: Int?
    var arcadeOpen = false
    var arcadeShown = false
    private(set) var gameSettings: [String: String] = [:]
    static let mousePath = "Input/Output@@Mouse"

    init(serial: String? = nil, scope: SettingsScope = .allGames,
         readsController: @escaping @MainActor () -> Bool = { GamepadPoller.readsAController },
         micState: @escaping @MainActor () -> MicState = { ControllersModel.recordPermission() },
         cameraState: @escaping @MainActor () -> MicState = { ControllersModel.cameraPermission() }) {
        self.serial = serial
        self.scope = scope
        self.readsController = readsController
        self.micState = micState
        self.cameraState = cameraState
        controllerConnected = readsController()
        mic = micState()
        camera = cameraState()
    }

    // Never requests it. Restricted counts as Off, as the capture source's camera_denied() counts it.
    static func cameraPermission() -> MicState {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .allowed
        case .notDetermined: return .notAsked
        default: return .off
        }
    }

    // Reads the record permission, never requests it.
    static func recordPermission() -> MicState {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return .allowed
        case .denied: return .off
        default: return .notAsked
        }
    }

    static func word(_ state: MicState) -> String {
        switch state {
        case .allowed: return String(localized: "Allowed")
        case .notAsked: return String(localized: "Not asked yet")
        case .off: return String(localized: "Off")
        }
    }

    // A title with its own mapping is always edited as This Game, because the running game reads only its own file.
    private var target: String? { scope == .game && (perGame || scopeAnswer != .allGames) ? serial : nil }

    var ownsMapping: Bool { scope == .game && perGame }

    func refreshController() {
        controllerConnected = readsController() || GCKeyboard.coalesced != nil
        mic = micState()
        camera = cameraState()
        held = GamepadPoller.shared.slots.holders.map { $0 != nil }
        heldNames = GamepadPoller.shared.slots.holders.map { $0.map { $0.vendor.isEmpty ? $0.category : $0.vendor } ?? "" }
        keyboardHeld = GamepadPoller.shared.slots.slot(of: GamepadPoller.keyboardKey)
    }

    var keyboardPlayer: Bool { SettingsStore.shared.keyboardSlot == player }

    func useKeyboard(as n: Int) {
        KeyboardMouseFeed.shared.setKeyboardSlot(n)
    }

    func openPlayer(_ n: Int) {
        player = n
        showsPlayer = true
    }

    // What holds player `n`: a controller, the touch pad (player 1 with no controller), or nobody.
    func holder(_ n: Int) -> String {
        if keyboardHeld == n { return String(localized: "Keyboard") }
        if held[n] { return heldNames[n].isEmpty ? String(localized: "Controller") : heldNames[n] }
        return n == 0 ? String(localized: "Touch") : String(localized: "Empty")
    }

    func trailing(_ key: String) -> String {
        capturing == key ? String(localized: "Press a button…") : mappings[player].source(key, keyboard: keyboardPlayer)
    }

    // A failed or garbled dump shows the defaults.
    func load() async {
        let (data, status) = await EmulatorSession.shared.inputConfigDump(serial: target)
        mappings = PlayerMapping.decode(status == 0 ? data : nil)
        let root = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        perGame = status == 0 && root?["perGame"] as? Int == 1
        // The camera choice is global, so it is read from the All-games settings.
        let (settings, settingsStatus) = await EmulatorSession.shared.settingsDump(serial: nil)
        let cameraID = settingsStatus == 0 ? settings.flatMap(SettingsModel.decode)?.leaves
            .first { $0.path == ["Input/Output", "Camera ID"] }?.global : nil
        cameraBack = cameraID == "Back"
        if scope == .game, let serial, !serial.isEmpty {
            for n in devices.indices {
                devices[n] = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.device(forPlayer: n, serial: serial) { status, type in
                        continuation.resume(returning: status == 0 ? type : 0)
                    }
                }
            }
            let (game, gameStatus) = await EmulatorSession.shared.settingsDump(serial: serial)
            let leaves = gameStatus == 0 ? game.flatMap(SettingsModel.decode)?.leaves ?? [] : []
            for usb in DeviceTables.usbDevices {
                if let leaf = leaves.first(where: { $0.id == usb.path }) { gameSettings[usb.path] = leaf.game ?? leaf.global }
            }
        }
    }

    // Steps Off, 1, 2 and round again.
    func stepUSB(_ path: String) {
        guard let serial, !serial.isEmpty else { return }
        let counts = DeviceTables.usbCounts
        let next = counts[((counts.firstIndex(of: gameSettings[path] ?? counts[0]) ?? 0) + 1) % counts.count]
        let previous = writing
        writing = Task {
            await previous?.value
            _ = await setGameSetting(path, next, serial)
        }
    }

    // This title only. A gun also sets the title's mouse handler to Basic, since with Mouse: Null it aims nowhere.
    func chooseDevice(_ ordinal: Int, for n: Int) {
        choosingDevice = nil
        guard let serial, !serial.isEmpty, DeviceTables.devices.indices.contains(ordinal), devices.indices.contains(n) else {
            return
        }
        let gun = DeviceTables.devices[ordinal].isGun
        let previous = writing
        writing = Task {
            await previous?.value
            // The mouse write goes first and the device only after it took, so a refused write never leaves a gun that cannot aim.
            var status = 0
            if gun { status = await setGameSetting(Self.mousePath, "Basic", serial) }
            if status == 0 {
                status = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.setPlayer(n, device: ordinal, serial: serial) { continuation.resume(returning: $0) }
                }
            }
            // The device write created the title's own mapping file, so the next change edits This Game without asking.
            if status == 0 {
                devices[n] = ordinal
                perGame = true
            }
        }
    }

    // The value is kept only when the core took it.
    private func setGameSetting(_ path: String, _ value: String, _ serial: String) async -> Int {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.setSetting(path, value: value, serial: serial) { status, _ in continuation.resume(returning: status) }
        }
        if status == 0 { gameSettings[path] = value }
        return status
    }

    // A refused write keeps the old choice on screen.
    func setCameraBack(_ back: Bool) {
        let before = cameraBack
        cameraBack = back
        Rpcs3Bridge.setSetting("Input/Output@@Camera ID", value: back ? "Back" : "Default", serial: nil) { [weak self] status, _ in
            Task { @MainActor in
                if status != 0 { self?.cameraBack = before }
            }
        }
    }

    // The players a controller holds (never the keyboard's), the ones that can be the Move.
    var controllerSlots: [Int] { held.indices.filter { held[$0] && keyboardHeld != $0 } }

    // Never the keyboard's player, which takes its slot back on the poller's next tick.
    func pinTargets(_ n: Int) -> [Int] {
        guard held[n] && keyboardHeld != n else { return [] }
        return held.indices.filter { $0 != n && $0 != keyboardHeld }
    }

    func setMove(_ on: Bool) {
        guard let serial else { return }
        let before = (SettingsStore.shared.moveSlot[serial], SettingsStore.shared.movePointer[serial])
        SettingsStore.shared.moveSlot[serial] = on ? controllerSlots.first ?? player : nil
        SettingsStore.shared.movePointer[serial] = on ? 0 : nil
        let done: @Sendable (Int, Bool) -> Void = { status, _ in
            Task { @MainActor in
                if status != 0 {
                    SettingsStore.shared.moveSlot[serial] = before.0
                    SettingsStore.shared.movePointer[serial] = before.1
                }
            }
        }
        if on {
            Rpcs3Bridge.setSetting("Input/Output@@Move", value: "Fake", serial: serial, completion: done)
        } else {
            Rpcs3Bridge.resetSettings(["Input/Output@@Move"], serial: serial, completion: done)
        }
    }

    func nextMoveSlot() {
        guard let serial, let slot = SettingsStore.shared.moveSlot[serial] else { return }
        let slots = controllerSlots
        guard let next = slots.first(where: { $0 > slot }) ?? slots.first else { return }
        SettingsStore.shared.moveSlot[serial] = next
    }

    // The router cancels any capture already running; 10 s with no press cancels.
    func startCapture(_ key: String, router: MenuControllerInputRouter?) {
        let pad = readsController() && router != nil
        let keyboard = GCKeyboard.coalesced != nil
        guard pad || keyboard else { return }
        let player = self.player
        if pad {
            router?.beginButtonCapture { [weak self] source in self?.captured(source, for: key, player: player) }
        } else {
            router?.cancelButtonCapture() // a controller capture still running answers its own row first
        }
        if keyboard {
            KeyboardMouseFeed.shared.beginCapture { [weak self] source in
                self?.captured(source, for: key, player: player)
                router?.cancelButtonCapture()
            }
        }
        capturing = key
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled, self?.capturing == key else { return }
            router?.cancelButtonCapture()
            self?.captured(nil, for: key)
        }
    }

    // `player` is the one selected when the capture began (the selected one when nil).
    func captured(_ source: String?, for key: String, player: Int? = nil) {
        guard let source else {
            if capturing == key {
                capturing = nil
                timeout?.cancel()
                KeyboardMouseFeed.shared.endCapture()
            }
            return
        }
        capturing = nil
        timeout?.cancel()
        KeyboardMouseFeed.shared.endCapture()
        let n = player ?? self.player
        // A key swaps among keys.
        commit(mappings[n].binding(key, to: source, keyboard: source.hasPrefix("Keyboard ")), player: n, op: "capture")
    }

    // The binding strings, then the four stick numbers.
    static func values(_ m: PlayerMapping) -> [(key: String, value: String)] {
        PlayerMapping.bindingKeys.map { (key: $0, value: m.source($0)) } + [
            (key: "Left Stick Deadzone", value: String(m.leftDeadzone)),
            (key: "Right Stick Deadzone", value: String(m.rightDeadzone)),
            (key: "Left Stick Multiplier", value: String(m.leftMultiplier)),
            (key: "Right Stick Multiplier", value: String(m.rightMultiplier)),
        ]
    }

    // A refused write re-reads the core, so the pane shows what it holds.
    private func commit(_ next: PlayerMapping, player n: Int, op: String) {
        // Not when the title has its own mapping: the poller loads that file, so a write for all games never reaches it.
        if scope == .game && scopeAnswer == nil && !perGame {
            pending = (next, n, op)
            alert = .scope
            return
        }
        let changes = zip(Self.values(mappings[n]), Self.values(next)).filter { $0.0.value != $0.1.value }.map { $0.1 }
        mappings[n] = next
        let serial = target
        let previous = writing
        writing = Task {
            await previous?.value
            var sets = 0
            var failed = false
            for change in changes {
                let status = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.setInputBinding(change.key, value: change.value, player: n, serial: serial) {
                        continuation.resume(returning: $0)
                    }
                }
                if status == 0 { sets += 1 } else { failed = true }
            }
            if failed { await load() }
            // A title write that took leaves the title with its own mapping, so Use All-Games Mapping shows.
            if serial != nil && sets > 0 { perGame = true }
            GamepadPoller.shared.loadMappings(serial: GamepadPoller.shared.serial)
            lastSets = sets
        }
    }

    func answerScope(_ answer: SettingsScope) {
        scopeAnswer = answer
        alert = nil
        guard let change = pending else { return }
        pending = nil
        commit(change.next, player: change.player, op: change.op)
    }

    // Cancel on the scope question: the change is dropped, nothing is written, and the next change asks again.
    func dropScopeChange() {
        pending = nil
        alert = nil
    }

    func pin(_ n: Int, to slot: Int) {
        GamepadPoller.shared.pin(slot: n, to: slot)
        refreshController()
    }

    // Deadzones clamp to 0...255, sensitivities to 0...200 percent.
    func step(_ field: WritableKeyPath<PlayerMapping, Int>, by delta: Int) {
        var next = mappings[player]
        let moved = next[keyPath: field] + delta
        let deadzone = field == \PlayerMapping.leftDeadzone || field == \PlayerMapping.rightDeadzone
        next[keyPath: field] = deadzone ? PlayerMapping.clamped(deadzone: moved) : PlayerMapping.clamped(multiplier: moved)
        commit(next, player: player, op: "stick")
    }

    func swapSticks() {
        commit(mappings[player].swappingSticks(), player: player, op: "stick")
    }

    func setInvert(_ axis: PlayerMapping.Axis, _ on: Bool) {
        commit(mappings[player].setting(invert: axis, on), player: player, op: "stick")
    }

    func reset() {
        let n = player
        let serial = target
        let previous = writing
        writing = Task {
            await previous?.value
            _ = await withCheckedContinuation { continuation in
                Rpcs3Bridge.resetInputPlayer(n, serial: serial) { continuation.resume(returning: $0) }
            }
            await load()
            GamepadPoller.shared.loadMappings(serial: GamepadPoller.shared.serial)
        }
    }

    // The engine deletes the title's file (player -1); the pane and the poller re-read.
    func useAllGamesMapping() {
        guard let serial else { return }
        let previous = writing
        writing = Task {
            await previous?.value
            _ = await withCheckedContinuation { continuation in
                Rpcs3Bridge.resetInputPlayer(-1, serial: serial) { continuation.resume(returning: $0) }
            }
            await load()
            GamepadPoller.shared.loadMappings(serial: GamepadPoller.shared.serial)
        }
    }
}

// The title's serial is its identity, so the sheet stays stable for one title.
struct ControllersSheetRequest: Identifiable {
    let serial: String
    var id: String { serial }
}

// Declared first so its reset alert is the file's first alert; the scope and reset alerts live on the view on screen.
private struct ControllersPlayerPage: View {
    let model: ControllersModel
    let controller: SettingsControllerContext
    let router: MenuControllerInputRouter?
    @State private var confirmingReset = false
    // At accessibility text sizes a button row shows its source as text, not a glyph over its end.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Buttons")) {
                if !model.controllerConnected {
                    Text("Connect a controller to change buttons.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                // In a game whose title has its own mapping every change goes to that title unasked, so the page says so.
                if model.ownsMapping {
                    Text("This game has its own mapping, so changes here apply to this game only. Use All-Games Mapping, under This Game, goes back to the shared one.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if model.scope == .allGames {
                    Text("Games with their own mapping, set from inside a game, aren't changed here.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                ForEach(ControllersPaneView.rowKeys, id: \.self) { key in
                    let warm = model.capturing == key
                    OverlayActionRow(controllerNavigationID: ControllersPaneView.navigationID(key), label: key,
                                     trailingValue: warm || dynamicTypeSize.isAccessibilitySize ? model.trailing(key) : nil,
                                     keepsTrailingValue: true) {
                        model.startCapture(key, router: router)
                    }
                    .transformEnvironment(\.controllerTextAppearance) { if warm { $0.secondaryColor = OverlayTheme.warm } }
                    .overlay(alignment: .trailing) {
                        // No glyph over a long label at accessibility sizes.
                        if !warm && !dynamicTypeSize.isAccessibilitySize {
                            ControllerElementGlyph(name: model.mappings[model.player].source(key, keyboard: model.keyboardPlayer),
                                                   controller: playerController)
                                .allowsHitTesting(false)
                        }
                    }
                }
            }
            OverlaySectionCard(title: String(localized: "Sticks")) {
                stepRow("leftdeadzone", "Left Stick Deadzone", "circle.dashed", \.leftDeadzone, step: 8, percent: false)
                stepRow("leftsensitivity", "Left Stick Sensitivity", "dial.medium", \.leftMultiplier, step: 10, percent: true)
                stepRow("rightdeadzone", "Right Stick Deadzone", "circle.dashed", \.rightDeadzone, step: 8, percent: false)
                stepRow("rightsensitivity", "Right Stick Sensitivity", "dial.medium", \.rightMultiplier, step: 10, percent: true)
                OverlayToggleRow(controllerNavigationID: "settings.controllers.swapsticks", label: "Swap Sticks",
                                 systemImage: "arrow.left.arrow.right",
                                 isOn: Binding(get: { model.mappings[model.player].sticksSwapped }, set: { _ in model.swapSticks() }))
                OverlayToggleRow(controllerNavigationID: "settings.controllers.invertleftx", label: "Invert Left Stick X",
                                 systemImage: "arrow.left.and.right", isOn: invert(.leftX))
                OverlayToggleRow(controllerNavigationID: "settings.controllers.invertlefty", label: "Invert Left Stick Y",
                                 systemImage: "arrow.up.and.down", isOn: invert(.leftY))
                OverlayToggleRow(controllerNavigationID: "settings.controllers.invertrightx", label: "Invert Right Stick X",
                                 systemImage: "arrow.left.and.right", isOn: invert(.rightX))
                OverlayToggleRow(controllerNavigationID: "settings.controllers.invertrighty", label: "Invert Right Stick Y",
                                 systemImage: "arrow.up.and.down", isOn: invert(.rightY))
                Text("L2 and R2 press at RPCS3's default point.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OverlaySectionCard(title: String(localized: "Tilt")) {
                OverlayToggleRow(controllerNavigationID: "settings.controllers.tilt", label: "Tilt", systemImage: "gyroscope",
                                 isOn: tilt)
                OverlayToggleRow(controllerNavigationID: "settings.controllers.tiltinvertx", label: "Invert Tilt X",
                                 systemImage: "arrow.left.and.right", isOn: tiltInvert(1))
                OverlayToggleRow(controllerNavigationID: "settings.controllers.tiltinverty", label: "Invert Tilt Y",
                                 systemImage: "arrow.up.and.down", isOn: tiltInvert(2))
            }
            OverlaySectionCard(title: String(localized: "Rumble")) {
                ControllersRumbleRows(slot: model.player)
                    .id(model.player)
            }
            OverlaySectionCard {
                ForEach(model.pinTargets(model.player), id: \.self) { slot in
                    OverlayActionRow(controllerNavigationID: "settings.controllers.pin\(slot + 1)",
                                     label: String(localized: "Pin to Player \(slot + 1)"), systemImage: "pin") {
                        model.pin(model.player, to: slot)
                    }
                }
                OverlayActionRow(controllerNavigationID: "settings.controllers.keyboardplayer",
                                 label: String(localized: "Use Keyboard as Player \(model.player + 1)"),
                                 systemImage: SettingsStore.shared.keyboardSlot == model.player ? "checkmark.circle.fill" : "keyboard") {
                    model.useKeyboard(as: model.player)
                }
            }
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.controllers.reset", label: "Reset to Default",
                                 systemImage: "arrow.counterclockwise", isDestructive: true) {
                    confirmingReset = true
                }
            }
        }
        // A long label shrinks before it truncates.
        .minimumScaleFactor(0.5)
        .navigationTitle(String(localized: "Player \(model.player + 1)"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input,
                                           scopeKey: "settings.pane." + SettingsPane.controllers.navigationKey + ".player",
                                           onBack: { model.showsPlayer = false; return true }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .onReceive(Publishers.MergeMany(GamepadPoller.controllerChanges.map { NotificationCenter.default.publisher(for: $0) })) { _ in
            model.refreshController()
        }
        .onAppear { model.refreshController() }
        // Leaving the page ends a capture, as leaving the pane did.
        .onDisappear {
            router?.cancelButtonCapture()
            KeyboardMouseFeed.shared.endCapture()
        }
        .alert(String(localized: "Reset Player \(model.player + 1) to the default buttons?"), isPresented: $confirmingReset) {
            Button("Reset", role: .destructive) { model.reset() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its buttons, deadzones and sensitivities go back to the defaults.")
        }
        // Asked once, at the first change made from inside a game.
        .alert(String(localized: "Change buttons for"), isPresented: Binding(get: { model.alert == .scope },
                                                                          set: { if !$0 { model.alert = nil } })) {
            Button("This Game Only") { model.answerScope(.game) }
            Button("All Games") { model.answerScope(.allGames) }
            Button("Cancel", role: .cancel) { model.dropScopeChange() }
        } message: {
            Text("This Game Only gives this game its own mapping. All Games changes the mapping every game shares.")
        }
    }

    // nil when no controller holds the selected player.
    private var playerController: GCController? {
        GamepadPoller.shared.controllersProvider().first { GamepadPoller.shared.slot(of: $0) == model.player }
    }

    private func invert(_ axis: PlayerMapping.Axis) -> Binding<Bool> {
        Binding(get: { model.mappings[model.player].inverted(axis) }, set: { model.setInvert(axis, $0) })
    }

    private var tilt: Binding<Bool> {
        let n = model.player
        return Binding(get: { !SettingsStore.shared.controllerTiltOff.contains(n) }, set: { on in
            SettingsStore.shared.controllerTiltOff.removeAll { $0 == n }
            if !on { SettingsStore.shared.controllerTiltOff.append(n) }
        })
    }

    // `bit` 1 is Invert Tilt X and 2 Invert Tilt Y of the selected player.
    private func tiltInvert(_ bit: Int) -> Binding<Bool> {
        let key = String(model.player)
        return Binding(get: { (SettingsStore.shared.controllerTiltInvert[key] ?? 0) & bit != 0 }, set: { on in
            let bits = SettingsStore.shared.controllerTiltInvert[key] ?? 0
            SettingsStore.shared.controllerTiltInvert[key] = on ? bits | bit : bits & ~bit
        })
    }

    private func stepRow(_ id: String, _ label: LocalizedStringResource, _ image: String,
                         _ field: WritableKeyPath<PlayerMapping, Int>, step: Int, percent: Bool) -> some View {
        let value = model.mappings[model.player][keyPath: field]
        return LiveStepRow(navigationID: "settings.controllers." + id, label: label, systemImage: image,
                           value: percent ? "\(value)%" : "\(value)",
                           down: { model.step(field, by: -step) }, up: { model.step(field, by: step) })
    }
}

struct ControllersRumbleRows: View {
    static let ids = ["settings.controllers.rumble", "settings.controllers.rumblestrength", "settings.controllers.rumbletest"]
    let slot: Int
    @AppStorage private var enabled: Bool
    @AppStorage private var strength: Int

    init(slot: Int) {
        self.slot = slot
        _enabled = AppStorage(wrappedValue: true, RumbleFeed.enabledKey(slot))
        _strength = AppStorage(wrappedValue: 100, RumbleFeed.strengthKey(slot))
    }

    var body: some View {
        OverlayToggleRow(controllerNavigationID: Self.ids[0], label: "Rumble", systemImage: "waveform.path", isOn: $enabled)
        // A controller step of 5, as the System Sounds pane's volume rows.
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Rumble Strength").foregroundStyle(OverlayTheme.textPrimary)
                Spacer(minLength: 0)
                Text(verbatim: "\(strength)%").foregroundStyle(OverlayTheme.textSecondary).monospacedDigit()
            }
            Slider(value: Binding(get: { Double(strength) }, set: { strength = Int($0) }), in: 0...100, step: 1)
        }
        .frame(minHeight: 44)
        .controllerAccessibilityAdjustableTarget(id: Self.ids[1], label: String(localized: "Rumble Strength"),
                                                 value: "\(strength)%", onActivate: {},
                                                 onIncrement: { strength = min(100, strength + 5) },
                                                 onDecrement: { strength = max(0, strength - 5) })
        OverlayActionRow(controllerNavigationID: Self.ids[2], label: "Test Rumble", systemImage: "waveform") {
            RumbleFeed.shared.test(slot: slot)
        }
        Text("Player 1 vibrates the phone when it plays on the touch pad or its controller has no rumble.")
            .font(.subheadline)
            .foregroundStyle(OverlayTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ControllersPaneView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var model: ControllersModel
    // Use All-Games Mapping deletes the title's own mapping, so it asks first.
    @State private var confirmingFollow = false
    @State private var layoutEditor: PadEditor?
    @Environment(\.menuControllerInputRouter) private var environmentRouter
    @Environment(\.openURL) private var openURL // the app's Settings page

    // The host's router, or the one RootView puts in the environment (the library's sheet passes none).
    private var router: MenuControllerInputRouter? { controller.input ?? environmentRouter }

    init(path: Binding<[SettingsPane]>, controller: SettingsControllerContext = SettingsControllerContext(),
         model: ControllersModel? = nil) {
        _path = path
        self.controller = controller
        _model = State(initialValue: model ?? ControllersModel())
    }

    // The buttons first, then the eight stick directions (the core's order puts those first).
    static let rowKeys = Array(PlayerMapping.bindingKeys.dropFirst(PlayerMapping.stickKeys.count)) + PlayerMapping.stickKeys

    static func navigationID(_ key: String) -> String { "settings.controllers.key." + key.filter { !$0.isWhitespace } }

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Players")) {
                ForEach(0..<PlayerSlots.count, id: \.self) { n in
                    OverlayActionRow(controllerNavigationID: "settings.controllers.player\(n + 1)",
                                     label: String(localized: "Player \(n + 1)"),
                                     systemImage: "person.fill",
                                     trailingValue: model.holder(n), keepsTrailingValue: true, showsChevron: true) {
                        model.openPlayer(n)
                    }
                    .accessibilityLabel(Text("Player \(n + 1)"))
                    .accessibilityValue(Text(model.holder(n)))
                    .accessibilityHint(Text("Opens this player's buttons, sticks and tilt."))
                    .contextMenu {
                        ForEach(model.pinTargets(n), id: \.self) { slot in
                            Button(String(localized: "Pin to Player \(slot + 1)")) { model.pin(n, to: slot) }
                        }
                    }
                }
            }
            // In a game the quick menu opens the editor over the paused frame instead.
            TouchPadCard(onEdit: model.scope == .game ? nil : {
                layoutEditor = PadEditor(serial: nil, library: PadLayoutStore.shared.library)
            })
            OverlaySectionCard(title: String(localized: "Devices")) {
                OverlayActionRow(controllerNavigationID: "settings.controllers.microphone", label: "Microphone",
                                 systemImage: model.mic == .off ? "mic.slash" : "mic",
                                 trailingValue: ControllersModel.word(model.mic), keepsTrailingValue: true) {
                    model.refreshController()
                }
                OverlayActionRow(controllerNavigationID: "settings.controllers.camera", label: "Camera",
                                 systemImage: model.camera == .off ? "video.slash" : "video",
                                 trailingValue: ControllersModel.word(model.camera), keepsTrailingValue: true) {
                    model.refreshController()
                }
                OverlayActionRow(controllerNavigationID: "settings.controllers.camerafacing", label: "PS Eye Camera",
                                 systemImage: "arrow.triangle.2.circlepath.camera",
                                 trailingValue: model.cameraBack ? "Back" : "Front", keepsTrailingValue: true) {
                    model.setCameraBack(!model.cameraBack)
                }
                // Off makes the keyboard Player 1's pad; each player's page still offers its own.
                OverlayToggleRow(controllerNavigationID: "settings.controllers.keyboardusb", label: "Keyboard as USB keyboard",
                                 systemImage: "keyboard",
                                 isOn: Binding(get: { SettingsStore.shared.keyboardSlot < 0 },
                                               set: { model.useKeyboard(as: $0 ? -1 : 0) }))
                if model.mic == .off {
                    Text("Microphone access is off. Turn it on in Settings › Privacy & Security › Microphone › ARMSX3.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if model.camera == .off {
                    Text("Camera access is off. Turn it on in Settings › Privacy & Security › Camera › ARMSX3.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if model.mic == .off || model.camera == .off {
                    OverlayActionRow(controllerNavigationID: "settings.controllers.microphonesettings", label: "Open Settings",
                                     systemImage: "gear") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                }
            }
            // Gyro steering is per title, not per player, so it sits here and not on a player's page.
            if model.scope == .game, let serial = model.serial {
                OverlaySectionCard(title: String(localized: "This Game")) {
                    OverlayToggleRow(controllerNavigationID: "settings.controllers.gyro", label: "Gyro Steering",
                                     systemImage: "gyroscope", isOn: gyroSteering(serial))
                    if let target = SettingsStore.shared.gyroStickTarget[serial] {
                        OverlayActionRow(controllerNavigationID: "settings.controllers.gyrostick", label: "Steered Stick",
                                         systemImage: "l.joystick.tilt.right", trailingValue: target == 0 ? "Left" : "Right",
                                         keepsTrailingValue: true) {
                            SettingsStore.shared.gyroStickTarget[serial] = target == 0 ? 1 : 0
                        }
                        let sensitivity = SettingsStore.shared.gyroStickSensitivity[serial] ?? 100
                        LiveStepRow(navigationID: "settings.controllers.gyrosensitivity", label: "Gyro Sensitivity",
                                    systemImage: "dial.medium", value: "\(sensitivity)%",
                                    down: { SettingsStore.shared.gyroStickSensitivity[serial] = max(0, sensitivity - 25) },
                                    up: { SettingsStore.shared.gyroStickSensitivity[serial] = min(300, sensitivity + 25) })
                    }
                    OverlayToggleRow(controllerNavigationID: "settings.controllers.move", label: "Use a Controller as PS Move",
                                     systemImage: "wand.and.rays", isOn: move(serial))
                    if let slot = SettingsStore.shared.moveSlot[serial] {
                        OverlayActionRow(controllerNavigationID: "settings.controllers.moveplayer", label: "Move Player",
                                         systemImage: "person.fill", trailingValue: String(localized: "Player \(slot + 1)"),
                                         keepsTrailingValue: true) {
                            model.nextMoveSlot()
                        }
                        let touch = SettingsStore.shared.movePointer[serial] == 1
                        OverlayActionRow(controllerNavigationID: "settings.controllers.movepointer", label: "Pointer",
                                         systemImage: touch ? "hand.point.up.left" : "scope",
                                         trailingValue: touch ? "Touch" : "Gyro", keepsTrailingValue: true) {
                            SettingsStore.shared.movePointer[serial] = touch ? 0 : 1
                        }
                        Text("With PS Move on, up to three players can play.")
                            .font(.subheadline)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if model.camera == .off {
                            Text("PS Move games need the camera. Turn it on in Settings › Privacy & Security › Camera › ARMSX3.")
                                .font(.subheadline)
                                .foregroundStyle(OverlayTheme.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    if model.perGame {
                        OverlayActionRow(controllerNavigationID: "settings.controllers.follow", label: "Use All-Games Mapping",
                                         systemImage: "globe") {
                            confirmingFollow = true
                        }
                    }
                    ForEach(0..<PlayerSlots.count, id: \.self) { n in
                        OverlayActionRow(controllerNavigationID: "settings.controllers.device\(n + 1)",
                                         label: String(localized: "Player \(n + 1) Device"), systemImage: "gamecontroller",
                                         trailingValue: DeviceTables.name(model.devices[n]), keepsTrailingValue: true) {
                            model.choosingDevice = model.choosingDevice == n ? nil : n
                        }
                        if model.choosingDevice == n {
                            ForEach(DeviceTables.devices, id: \.ordinal) { device in
                                OverlayActionRow(controllerNavigationID: "settings.controllers.device\(n + 1).choice\(device.ordinal)",
                                                 label: device.name,
                                                 systemImage: model.devices[n] == device.ordinal ? "checkmark.circle.fill" : "circle") {
                                    model.chooseDevice(device.ordinal, for: n)
                                }
                            }
                        }
                        ControllersDeviceLegend(rows: DeviceTables.legend(model.devices[n]))
                    }
                    ForEach(DeviceTables.usbDevices, id: \.key) { usb in
                        let count = DeviceTables.usbCounts.firstIndex(of: model.gameSettings[usb.path] ?? "") ?? 0
                        OverlayActionRow(controllerNavigationID: "settings.controllers.usb.\(usb.key)", label: usb.label,
                                         systemImage: "cable.connector", trailingValue: DeviceTables.usbCountWords[count],
                                         keepsTrailingValue: true) {
                            model.stepUSB(usb.path)
                        }
                        ControllersDeviceLegend(rows: DeviceTables.usbLegend(usb.key))
                    }
                    OverlayActionRow(controllerNavigationID: "settings.controllers.usio", label: "Arcade Board (usio)",
                                     systemImage: "arcade.stick", trailingValue: model.arcadeOpen ? "Hide" : "Show",
                                     keepsTrailingValue: true) { model.arcadeOpen.toggle() }
                    if model.arcadeOpen {
                        Text("Used only by PS3 arcade games; always attached.")
                            .font(.subheadline)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ControllersDeviceLegend(rows: DeviceTables.usbLegend("usio"))
                            .onAppear { model.arcadeShown = true }
                    }
                    Text("Takes effect when the game next starts.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Your controller's buttons reach these through the player's mapping.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        // A long label shrinks before it truncates.
        .minimumScaleFactor(0.5)
        .navigationTitle(SettingsPane.controllers.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: Binding(get: { model.showsPlayer }, set: { model.showsPlayer = $0 })) {
            ControllersPlayerPage(model: model, controller: controller, router: router)
        }
        .fullScreenCover(item: $layoutEditor) { editor in
            PadLayoutEditorView(editor: editor, onClose: { layoutEditor = nil })
        }
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.controllers.navigationKey,
                                           // At the root, Back closes the in-game sheet.
                                           onBack: { pop() || controller.onCloseHost?() == true }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .onReceive(Publishers.MergeMany(GamepadPoller.controllerChanges.map { NotificationCenter.default.publisher(for: $0) })) { _ in
            model.refreshController()
        }
        .onAppear { model.refreshController() }
        // Waiting for the page's writes keeps the re-read from showing a mapping the core has not taken yet.
        .task { await model.writing?.value; await model.load() }
        .onDisappear {
            router?.cancelButtonCapture()
            KeyboardMouseFeed.shared.endCapture()
        }
        .alert(String(localized: "Use the All-Games mapping for this game?"), isPresented: $confirmingFollow) {
            Button("Use All-Games Mapping", role: .destructive) { model.useAllGamesMapping() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This game's own buttons and stick settings are deleted.")
        }
    }

    // On stores the right stick, off removes the entry.
    private func gyroSteering(_ serial: String) -> Binding<Bool> {
        Binding(get: { SettingsStore.shared.gyroStickTarget[serial] != nil }, set: { on in
            SettingsStore.shared.gyroStickTarget[serial] = on ? 1 : nil
        })
    }

    private func move(_ serial: String) -> Binding<Bool> {
        Binding(get: { SettingsStore.shared.moveSlot[serial] != nil }, set: { model.setMove($0) })
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}

// Read only: there is no per-device remap screen.
struct ControllersDeviceLegend: View {
    let rows: [(ps3: String, does: String)]

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(rows.indices, id: \.self) { i in
                    let row = rows[i]
                    HStack {
                        Text(verbatim: DeviceTables.buttonName(row.ps3))
                        Spacer(minLength: 8)
                        Text(verbatim: row.does)
                    }
                }
            }
            .font(.footnote)
            .foregroundStyle(OverlayTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

// Its SF Symbol, else its localised name, else the name.
struct ControllerElementGlyph: View {
    let name: String
    var controller: GCController?

    var body: some View {
        let owner = controller?.physicalInputProfile.elements[name] != nil ? controller
            : GamepadPoller.shared.controllersProvider().first { $0.physicalInputProfile.elements[name] != nil }
        let element = owner?.physicalInputProfile.elements[name]
        Group {
            if let symbol = element?.sfSymbolsName, !symbol.isEmpty {
                Image(systemName: symbol)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityLabel(element?.localizedName ?? name)
            } else {
                Text(element?.localizedName ?? name)
                    .lineLimit(1)
            }
        }
        .foregroundStyle(OverlayTheme.textSecondary)
    }
}
