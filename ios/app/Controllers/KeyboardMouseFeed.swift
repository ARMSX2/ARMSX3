// SPDX-License-Identifier: GPL-2.0-only
// A hardware keyboard and mouse as the PS3's USB ones, or the keyboard as a player's pad. No key or pointer is logged.

import GameController
import UIKit

@MainActor final class KeyboardMouseFeed {
    static let shared = KeyboardMouseFeed()

    private var observers: [NSObjectProtocol] = []
    private var down = Set<Int>()
    private var presence: Task<Void, Never>?
    // The Controllers pane's capture, answered by the next key press; nil when none runs.
    private var capture: (@MainActor (String) -> Void)?
    private var mouseDown = Set<Int>()
    private var pendingX: Float = 0
    private var pendingY: Float = 0
    private var moveScheduled = false
    // The lock answer UIKit was last asked to read.
    private var lockAsked = false

    // Also false while anything is presented over the game screen.
    static var wantsPointerLock: Bool { !shared.observers.isEmpty && shared.gameOwnsInput && GCMouse.current != nil && !(GamePointerLockController.current?.covered ?? false) }

    private var gameOwnsInput: Bool { if case .game = EmulatorSession.shared.inputOwner { return true }; return false }

    func start() {
        guard observers.isEmpty else { return }
        for name in [Notification.Name.GCKeyboardDidConnect, .GCKeyboardDidDisconnect] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { KeyboardMouseFeed.shared.install() }
            })
        }
        install()
        for name in [Notification.Name.GCMouseDidConnect, .GCMouseDidDisconnect] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { KeyboardMouseFeed.shared.installMouse() }
            })
        }
        installMouse()
        // shortcut: a 1 Hz presence resend, because a game's cellKbInit resets cellKb's status; a core hook would remove it.
        presence = Task { @MainActor in
            while !Task.isCancelled {
                if case .game = EmulatorSession.shared.inputOwner {
                    KeyboardMouseFeed.shared.sendPresence()
                    KeyboardMouseFeed.shared.sendMousePresence()
                }
                KeyboardMouseFeed.shared.refreshPointerLock()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func stop() {
        presence?.cancel()
        presence = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = nil
        releaseHeld()
        for mouse in GCMouse.mice() { clearMouseHandlers(mouse) }
        for button in mouseDown { Rpcs3Bridge.mouseButton(button, pressed: false, completion: nil) }
        mouseDown = []
        pendingX = 0
        pendingY = 0
        refreshPointerLock()
    }

    // Every key held in the mode being left is released first, so no key stays down in the game.
    func setKeyboardSlot(_ slot: Int) {
        guard slot != SettingsStore.shared.keyboardSlot, (-1...3).contains(slot) else { return }
        releaseHeld()
        SettingsStore.shared.keyboardSlot = slot
        sendPresence()
    }

    // Outside a game the handler is installed for the capture alone and endCapture() removes it again.
    func beginCapture(_ completion: @escaping @MainActor (String) -> Void) {
        capture = completion
        setHandler()
    }

    func endCapture() {
        capture = nil
        if observers.isEmpty { GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = nil }
    }

    private func install() {
        setHandler()
        sendPresence()
    }

    private func setHandler() {
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = { _, _, keyCode, pressed in
            MainActor.assumeIsolated { KeyboardMouseFeed.shared.key(Int(keyCode.rawValue), pressed: pressed) }
        }
    }

    // A keyboard that is a player's pad is no USB keyboard.
    private func sendPresence() {
        Rpcs3Bridge.setKeyboardConnected(GCKeyboard.coalesced != nil && SettingsStore.shared.keyboardSlot < 0, completion: nil)
    }

    private func releaseHeld() {
        for usage in down { Rpcs3Bridge.keyboardKey(usage, pressed: false, completion: nil) }
        down = []
        GamepadPoller.shared.keyboardKeys = []
    }

    // The other mice are cleared, so one feeds.
    private func installMouse() {
        for mouse in GCMouse.mice() { clearMouseHandlers(mouse) }
        // Whoever owns input, so a mouse that goes releases its buttons at once.
        sendMousePresence()
        refreshPointerLock()
        // Mouse 0 may stay connected for a gun, so the buttons of a mouse that went are let go.
        if GCMouse.current == nil {
            for button in mouseDown { Rpcs3Bridge.mouseButton(button, pressed: false, completion: nil) }
            mouseDown = []
        }
        guard let input = GCMouse.current?.mouseInput else { return }
        input.mouseMovedHandler = { _, dx, dy in
            MainActor.assumeIsolated { KeyboardMouseFeed.shared.mouseMoved(dx, dy) }
        }
        let buttons: [(GCControllerButtonInput?, Int)] = [(input.leftButton, 1), (input.rightButton, 2), (input.middleButton, 3)]
            + (input.auxiliaryButtons ?? []).prefix(5).enumerated().map { ($0.element, 4 + $0.offset) }
        for (control, number) in buttons {
            control?.pressedChangedHandler = { _, _, pressed in
                MainActor.assumeIsolated { KeyboardMouseFeed.shared.mouseButton(number, pressed: pressed) }
            }
        }
        // test_gamepad_map.py keeps value handlers out of the app, so the scroll pad is read through its direction buttons.
        for (control, tilt, wheel) in [(input.scroll.up, 0, 1), (input.scroll.down, 0, -1),
                                       (input.scroll.left, -1, 0), (input.scroll.right, 1, 0)] {
            control.pressedChangedHandler = { _, _, pressed in
                guard pressed else { return }
                MainActor.assumeIsolated { KeyboardMouseFeed.shared.mouseScrolled(tilt, wheel) }
            }
        }
    }

    // The core drops every aim sent to a disconnected mouse, so a gun keeps mouse 0 connected.
    static var mousePresent: Bool { GCMouse.current != nil || AimFeed.shared.active }

    // On iPad without pointer lock every click arrives as a touch, so the aim follows the pointer and deltas are dropped.
    static var deltasAim: Bool {
        UIDevice.current.userInterfaceIdiom != .pad || UIApplication.shared.appWindowScene?.pointerLockState?.isLocked == true
    }

    private func sendMousePresence() {
        Rpcs3Bridge.setMouseConnected(Self.mousePresent, completion: nil)
    }

    // UIKit reads prefersPointerLocked from the window's controllers, so each root and its presented chain is asked again.
    func refreshPointerLock() {
        let wants = Self.wantsPointerLock
        guard wants != lockAsked else { return }
        lockAsked = wants
        for window in UIApplication.shared.appWindowScene?.windows ?? [] {
            var controller = window.rootViewController
            while let current = controller {
                current.setNeedsUpdateOfPrefersPointerLocked()
                controller = current.presentedViewController
            }
        }
    }

    private func clearMouseHandlers(_ mouse: GCMouse) {
        guard let input = mouse.mouseInput else { return }
        input.mouseMovedHandler = nil
        input.leftButton.pressedChangedHandler = nil
        input.rightButton?.pressedChangedHandler = nil
        input.middleButton?.pressedChangedHandler = nil
        input.auxiliaryButtons?.forEach { $0.pressedChangedHandler = nil }
        for control in [input.scroll.up, input.scroll.down, input.scroll.left, input.scroll.right] {
            control.pressedChangedHandler = nil
        }
    }

    private func mouseMoved(_ dx: Float, _ dy: Float) {
        guard gameOwnsInput else { pendingX = 0; pendingY = 0; return }
        pendingX += dx
        pendingY += dy
        guard !moveScheduled else { return }
        moveScheduled = true
        // shortcut: one send per 60 Hz frame, so a fast mouse does not flood the input queue; a display link would follow 120 Hz.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 16_666_667)
            KeyboardMouseFeed.shared.sendMove()
        }
    }

    private func sendMove() {
        moveScheduled = false
        let x = pendingX.rounded(), y = pendingY.rounded()
        // The rounding remainder carries to the next frame; what the clamp cuts off is dropped.
        pendingX -= x
        pendingY -= y
        guard gameOwnsInput, x != 0 || y != 0 else { return }
        // An unlocked iPad pointer aims by its position, so its deltas aim nothing.
        if AimFeed.shared.active, !Self.deltasAim { return }
        // While a gun aims, the delta moves the absolute aim cursor and no relative move goes out.
        if AimFeed.shared.active {
            AimFeed.shared.mouseDelta(dx: Int(min(max(x, -1280), 1280)), dy: Int(min(max(-y, -720), 720)))
            return
        }
        // GameController's Y grows upward; the PS3's grows downward.
        Rpcs3Bridge.mouseMoveX(Int(min(max(x, -127), 128)), y: Int(min(max(-y, -127), 128)), completion: nil)
    }

    private func mouseButton(_ number: Int, pressed: Bool) {
        if pressed {
            guard gameOwnsInput, mouseDown.insert(number).inserted else { return }
        } else {
            guard mouseDown.remove(number) != nil else { return }
        }
        Rpcs3Bridge.mouseButton(number, pressed: pressed, completion: nil)
    }

    private func mouseScrolled(_ tilt: Int, _ wheel: Int) {
        guard gameOwnsInput else { return }
        Rpcs3Bridge.mouseScrollX(tilt, y: wheel, completion: nil)
    }

    private func key(_ usage: Int, pressed: Bool) {
        // A press while the pane captures completes the capture and reaches no game.
        if pressed, let capture {
            self.capture = nil
            capture("Keyboard \(usage)")
            return
        }
        // A player's pad, never also the USB keyboard. The poller sends it while the game owns input.
        guard SettingsStore.shared.keyboardSlot < 0 else {
            if pressed {
                GamepadPoller.shared.keyboardKeys.insert("Keyboard \(usage)")
            } else {
                GamepadPoller.shared.keyboardKeys.remove("Keyboard \(usage)")
            }
            return
        }
        if pressed {
            guard EmulatorSession.shared.inputOwner == .game, down.insert(usage).inserted else { return }
        } else {
            guard down.remove(usage) != nil else { return }
        }
        Rpcs3Bridge.keyboardKey(usage, pressed: pressed, completion: nil)
    }
}
