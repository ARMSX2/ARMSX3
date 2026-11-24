// SPDX-License-Identifier: GPL-2.0-only
// Polls the core's motors and plays each player's on its controller, or player 1's on the phone. Debug-only logging.

import CoreHaptics
import Foundation
import GameController

@MainActor final class RumbleFeed {
    static let shared = RumbleFeed()

    /// A slot's rumble switch and strength in UserDefaults, as the Controllers screen and the XMB keep them.
    nonisolated static func enabledKey(_ slot: Int) -> String { "armsx3.rumble.enabled.\(slot)" }
    nonisolated static func strengthKey(_ slot: Int) -> String { "armsx3.rumble.strength.\(slot)" }

    // One slot's haptic target: the controller it was built for, and one engine and one player per locality.
    private final class Target {
        // nil for the phone's target.
        let controller: ObjectIdentifier?
        var engines: [CHHapticEngine] = []
        var players: [CHHapticAdvancedPatternPlayer] = []
        var playing: [Bool] = []
        // Set by an engine's stopped or reset handler; the next poll drops and rebuilds the target.
        var stale = false

        init(controller: ObjectIdentifier?) {
            self.controller = controller
        }
    }

    private var targets: [Target?] = Array(repeating: nil, count: PlayerSlots.count)
    private var gates = Array(repeating: RumbleMath.ChangeGate(), count: PlayerSlots.count)
    // A change of route stops the old target first.
    private var routes = Array(repeating: RumbleTarget.none, count: PlayerSlots.count)
    // The smoke scenes set it true so their routes do not depend on the Simulator; the app never writes it.
    var phoneHasHaptics = CHHapticEngine.capabilitiesForHardware().supportsHaptics
    private var phone: CHHapticEngine?
    // Kept alive for its one second.
    private var testing: Target?
    // The slot whose Test Rumble is playing on the GameSir accessory.
    private var testingAccessory: Int?
    // Bumped by each test, so an earlier test's one-second timer leaves a later test playing.
    private var testRun = 0
    private var pending = false
    private var asked: TimeInterval = 0
    // Bumped by stopAll() and pauseAll(), so an answer asked for before it is dropped.
    private var generation = 0

    private init() {}

    // At most one read in flight and at most one every 1/30 s.
    func poll(slots: PlayerSlots, controllers: [ControllerKey: GCController]) {
        let now = ProcessInfo.processInfo.systemUptime
        guard !pending, now - asked >= 1.0 / 30 else { return }
        asked = now
        pending = true
        let asking = generation
        // A player whose read was not OK is already 0, 0 in `motors`, so the status adds nothing here.
        Rpcs3Bridge.readRumble { _, motors in
            MainActor.assumeIsolated {
                let feed = RumbleFeed.shared
                feed.pending = false
                guard feed.generation == asking else { return }
                feed.apply([UInt8](motors), slots: slots, controllers: controllers)
            }
        }
    }

    func stopAll() {
        generation &+= 1
        for slot in targets.indices { drop(slot) }
    }

    // Engines stay built, so the first rumble after a resume does not make and start them on the main thread.
    func pauseAll() {
        generation &+= 1
        for slot in targets.indices { silence(slot) }
    }

    private func silence(_ slot: Int) {
        // pauseAll() runs every frame under the menu, so a Test Rumble on the accessory would last one frame.
        if routes[slot] == .accessory, testingAccessory != slot { GameSirG8MFiRumble.shared.stop() }
        if let target = targets[slot] {
            for (index, player) in target.players.enumerated() where target.playing[index] {
                try? player.stop(atTime: CHHapticTimeImmediate)
                target.playing[index] = false
            }
        }
        gates[slot] = RumbleMath.ChangeGate()
    }

    private func markStale(_ engine: ObjectIdentifier) {
        for target in targets.compactMap({ $0 }) where target.engines.contains(where: { ObjectIdentifier($0) == engine }) {
            target.stale = true
        }
    }

    func target(slot: Int) -> RumbleTarget {
        target(slot, held(slot))
    }

    private func held(_ slot: Int) -> GCController? {
        let poller = GamepadPoller.shared
        return poller.controllersProvider().first { poller.slot(of: $0) == slot }
    }

    // A second test replaces the first; neither the player's switch nor its strength applies.
    @discardableResult func test(slot: Int) -> RumbleTarget {
        let controller = held(slot)
        let route = target(slot, controller)
        endTest()
        switch route {
        case .phone: testing = makePhoneTarget()
        case .controller: testing = controller.flatMap(Self.controllerTarget)
        case .accessory: testingAccessory = GameSirG8MFiRumble.shared.rumble(left: 1, right: 1) ? slot : nil
        case .none: break
        }
        if let started = testing {
            for (index, player) in started.players.enumerated() {
                started.playing[index] = (try? player.start(atTime: CHHapticTimeImmediate)) != nil
            }
        }
        guard testing != nil || testingAccessory != nil else { return route }
        testRun &+= 1
        let run = testRun
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            MainActor.assumeIsolated {
                if RumbleFeed.shared.testRun == run { RumbleFeed.shared.endTest() }
            }
        }
        return route
    }

    private func endTest() {
        if let slot = testingAccessory {
            GameSirG8MFiRumble.shared.stop()
            // The test played over the game's own rumble on the same accessory, so the next poll sends it again.
            gates[slot] = RumbleMath.ChangeGate()
        }
        testingAccessory = nil
        if let testing {
            for player in testing.players { try? player.stop(atTime: CHHapticTimeImmediate) }
            // Never the shared phone engine, which slot 0 may be playing on.
            for engine in testing.engines where engine !== phone { engine.stop(completionHandler: nil) }
        }
        testing = nil
    }

    // nil `controller`: no controller holds the slot.
    private func target(_ slot: Int, _ controller: GCController?) -> RumbleTarget {
        let haptics = controller?.haptics.map { !$0.supportedLocalities.isEmpty } ?? false
        let accessory = controller.map(GameSirG8MFiRumble.shared.drives) ?? false
        let spare = controller.map(GameSirG8MFiRumble.shared.isSpare) ?? false
        return RumbleMath.target(slot: slot, hasController: controller != nil, controllerHasHaptics: haptics,
                                 controllerHasAccessory: accessory, controllerIsSpareAccessory: spare,
                                 phoneHasHaptics: phoneHasHaptics)
    }

    private func apply(_ bytes: [UInt8], slots: PlayerSlots, controllers: [ControllerKey: GCController]) {
        let defaults = UserDefaults.standard
        for slot in 0..<PlayerSlots.count {
            // A slot whose controller left changes target, which stops and drops its engines before anything else plays.
            let controller = slots.holders[slot].flatMap { controllers[$0] }
            let route = target(slot, controller)
            if route != routes[slot] {
                drop(slot)
                routes[slot] = route
            }
            guard route != .none else { continue }
            let enabled = defaults.object(forKey: Self.enabledKey(slot)) as? Bool ?? true
            let strength = defaults.object(forKey: Self.strengthKey(slot)) as? Int ?? 100
            let large = bytes.count == 2 * PlayerSlots.count ? bytes[2 * slot] : 0
            let small = bytes.count == 2 * PlayerSlots.count ? bytes[2 * slot + 1] : 0
            let motors = RumbleMath.motors(large: large, small: small, strengthPercent: strength, enabled: enabled)
            let owner = route == .controller ? controller.map(ObjectIdentifier.init) : nil
            if let target = targets[slot], target.stale || target.controller != owner {
                drop(slot)
            }
            guard gates[slot].update(motors) else { continue }
            // A send that played nothing it should have resets the gate, so the next poll tries again.
            if !send(motors, slot: slot, route: route, controller: controller) { gates[slot] = RumbleMath.ChangeGate() }
        }
    }

    // False when a non-zero motor has no target, or a channel that should play did not start.
    private func send(_ motors: RumbleMath.Motors, slot: Int, route: RumbleTarget, controller: GCController?) -> Bool {
        // The accessory has no Target: its one session is shared app-wide and never torn down per slot.
        if route == .accessory {
            guard motors != .off else { GameSirG8MFiRumble.shared.stop(); return true }
            return GameSirG8MFiRumble.shared.rumble(left: motors.large, right: motors.small)
        }
        if targets[slot] == nil {
            guard motors != .off else { return true }
            switch route {
            case .phone: targets[slot] = makePhoneTarget()
            case .controller: targets[slot] = controller.flatMap(Self.controllerTarget)
            case .accessory, .none: return true
            }
        }
        guard let target = targets[slot] else { return false }
        var played = true
        let values = target.players.count == 2 ? [motors.large, motors.small] : [RumbleMath.single(motors)]
        for (index, value) in values.enumerated() where index < target.players.count {
            let player = target.players[index]
            if value <= 0 {
                if target.playing[index] { try? player.stop(atTime: CHHapticTimeImmediate) }
                target.playing[index] = false
                continue
            }
            let intensity = CHHapticDynamicParameter(parameterID: .hapticIntensityControl, value: value, relativeTime: 0)
            try? player.sendParameters([intensity], atTime: CHHapticTimeImmediate)
            if !target.playing[index] {
                target.playing[index] = (try? player.start(atTime: CHHapticTimeImmediate)) != nil
            }
            played = played && target.playing[index]
        }
        return played
    }

    // The next non-zero motor builds them again.
    private func drop(_ slot: Int) {
        // Called before `routes[slot]` takes a new route, so this still sees the slot leaving the accessory.
        if routes[slot] == .accessory { GameSirG8MFiRumble.shared.stop() }
        if let target = targets[slot] {
            for player in target.players { try? player.stop(atTime: CHHapticTimeImmediate) }
            // Never the shared phone engine; its asynchronous stop could also land after the rebuild's start.
            for engine in target.engines where engine !== phone { engine.stop(completionHandler: nil) }
        }
        targets[slot] = nil
        gates[slot] = RumbleMath.ChangeGate()
    }

    // Large motor left, small right (inference); nil when the controller has no haptics or an engine fails.
    private static func controllerTarget(_ controller: GCController) -> Target? {
        guard let haptics = controller.haptics, !haptics.supportedLocalities.isEmpty else { return nil }
        let localities = haptics.supportedLocalities
        let wanted: [GCHapticsLocality] = localities.contains(.leftHandle) && localities.contains(.rightHandle)
            ? [.leftHandle, .rightHandle] : [.default]
        var engines: [CHHapticEngine] = []
        for locality in wanted {
            guard let engine = haptics.createEngine(withLocality: locality) else { return nil }
            engines.append(engine)
        }
        return makeTarget(owner: ObjectIdentifier(controller), engines: engines)
    }

    // nil on an iPad or when the engine cannot be made or started.
    private func makePhoneTarget() -> Target? {
        guard phoneHasHaptics else { return nil }
        if phone == nil {
            phone = try? CHHapticEngine()
            // Nothing stops this shared engine, so it idles itself out instead.
            phone?.isAutoShutdownEnabled = true
        }
        guard let phone else { return nil }
        return Self.makeTarget(owner: nil, engines: [phone])
    }

    // `owner` is nil for the phone.
    private static func makeTarget(owner: ObjectIdentifier?, engines: [CHHapticEngine]) -> Target? {
        let target = Target(controller: owner)
        do {
            let event = CHHapticEvent(eventType: .hapticContinuous,
                                      parameters: [CHHapticEventParameter(parameterID: .hapticIntensity, value: 1)],
                                      relativeTime: 0, duration: TimeInterval(GCHapticDurationInfinite))
            let pattern = try CHHapticPattern(events: [event], parameters: [])
            for engine in engines {
                // The system stopping or resetting an engine marks its slot for a rebuild.
                let id = ObjectIdentifier(engine)
                engine.stoppedHandler = { _ in
                    Task { @MainActor in RumbleFeed.shared.markStale(id) }
                }
                engine.resetHandler = {
                    Task { @MainActor in RumbleFeed.shared.markStale(id) }
                }
                try engine.start()
                target.engines.append(engine)
                target.players.append(try engine.makeAdvancedPlayer(with: pattern))
                target.playing.append(false)
            }
        } catch {
            // The phone's engine is shared, so only a controller's engines are stopped here.
            if owner != nil { for engine in target.engines { engine.stop(completionHandler: nil) } }
            return nil
        }
        return target
    }
}
