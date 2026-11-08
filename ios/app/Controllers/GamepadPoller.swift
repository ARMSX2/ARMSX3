// SPDX-License-Identifier: GPL-2.0-only
// Polls every controller once a frame; never sets a value-changed handler, because the menu router owns that one slot.

import CoreMotion
import GameController
import QuartzCore
import UIKit

@MainActor final class GamepadPoller: NSObject {
    static let shared = GamepadPoller()

    private var link: CADisplayLink?
    // The last snapshot this poller sent to each slot; nil while it is not driving that slot's pad.
    private var lastSent: [GamepadSnapshot?] = Array(repeating: nil, count: PlayerSlots.count)
    // A smoke scene injects GCController.withExtendedGamepad() snapshots here, which GCController.controllers() never lists.
    var controllersProvider: @MainActor () -> [GCController] = { GCController.controllers() }
    private(set) var slots = PlayerSlots()
    private var live: [ObjectIdentifier: ControllerKey] = [:]
    private var connectObserver: NSObjectProtocol?
    // Told the slot a controller held when it disconnected mid-game.
    var onPlayerLost: (@MainActor (Int) -> Void)?
    // The chord's physical elements, by default name, held at zero in every reading; empty when none.
    private var consumed: Set<String> = []
    var mappings = Array(repeating: PlayerMapping(), count: PlayerSlots.count)
    private(set) var serial: String?
    private var lastMotion: [Sixaxis?] = Array(repeating: nil, count: PlayerSlots.count)
    // stop() switches these sensors off again, for the battery.
    private var activated: [GCController] = []
    // The phone's motion, for a touch-pad player 1.
    private lazy var phone: CMMotionManager = {
        let manager = CMMotionManager()
        manager.deviceMotionUpdateInterval = 1.0 / 60
        return manager
    }()
    // Each slot's smoothed gyro offset, nil while that slot is not steered.
    private var steering: [(Double, Double)?] = Array(repeating: nil, count: PlayerSlots.count)
    static let keyboardKey = ControllerKey(category: "Keyboard", vendor: "", ordinal: 0)
    var keyboardKeys = Set<String>()
    var pointer = MovePointer()
    var moveTouch = MovePointer()
    // `.infinity` once this hold has recentred.
    private(set) var movePlugged: Int?
    private var moveLast: GamepadSnapshot?
    private var moveHeldSince: CFTimeInterval?
    private weak var moveController: GCController?
    private var moveLightAsked: CFTimeInterval = 0
    private var moveLightPending = false

    // Players 1-4's light colours in PlayStation order: blue, red, green, pink.
    static let slotColors = [
        GCColor(red: 0, green: 0, blue: 1), GCColor(red: 1, green: 0, blue: 0),
        GCColor(red: 0, green: 1, blue: 0), GCColor(red: 1, green: 0.4, blue: 0.7),
    ]

    private override init() {}

    // The touch pad does not hide on this; it hides on playerOneHasController.
    static var readsAController: Bool { shared.controllersProvider().contains { $0.extendedGamepad != nil } }

    // A controller that never pressed, or holds player 2, leaves the touch pad up.
    static var playerOneHasController: Bool { shared.slots.playerOneHasController }

    static let controllerChanges: [Notification.Name] = [
        .GCControllerDidConnect, .GCControllerDidDisconnect, .GCControllerDidBecomeCurrent, .GCControllerDidStopBeingCurrent,
        .armsx3PlayerSlotsChanged,
    ]

    func start() {
        guard link == nil else { return }
        // A controller that left between games frees its slot here, silently; no game was running.
        reconcile(controllersProvider().filter { $0.extendedGamepad != nil }, report: false)
        slots.loadPins(SettingsStore.shared.controllerPins)
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
        setSystemGestures(.disabled)
        connectObserver = NotificationCenter.default.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { GamepadPoller.shared.setSystemGestures(.disabled) }
        }
    }

    func stop() {
        link?.invalidate()
        link = nil
        if let connectObserver { NotificationCenter.default.removeObserver(connectObserver) }
        connectObserver = nil
        setSystemGestures(.enabled)
        releasePads()
        moveOff()
        // No motion sensor runs outside a game; the next game sends every slot's motion afresh.
        for controller in activated { controller.motion?.sensorsActive = false }
        activated = []
        phone.stopDeviceMotionUpdates()
        lastMotion = Array(repeating: nil, count: PlayerSlots.count)
        // A touch pad steered at the end of the game is let go (released controller slots already were).
        if steering[0] != nil, !slots.playerOneHasController { steerTouchPad(rateX: 0, rateY: 0, setting: nil) }
        steering = Array(repeating: nil, count: PlayerSlots.count)
        // The game is over, so no slot waits for a lost controller any more; holders keep theirs.
        slots.endSession()
        // No rumble outlives the game.
        RumbleFeed.shared.stopAll()
    }

    // Any status but 0, or a dump that does not decode, gives every slot the defaults.
    func loadMappings(serial: String?) {
        self.serial = serial
        Task {
            let (data, status) = await EmulatorSession.shared.inputConfigDump(serial: serial)
            guard self.serial == serial else { return }
            mappings = status == 0 ? PlayerMapping.decode(data) : Array(repeating: PlayerMapping(), count: PlayerSlots.count)
        }
    }

    // shortcut: a Start pressed a frame before its chord's second button can reach the game for that frame.
    func consume(mask: UInt32) {
        consumed.formUnion(PlayerMapping.sources(forMask: mask))
    }

    @objc private func tick() {
        let controllers = controllersProvider().filter { $0.extendedGamepad != nil }
        // Before the owner gate, so a controller that leaves while the menu is open is noticed too.
        reconcile(controllers, report: true)
        placeKeyboard()
        guard EmulatorSession.shared.inputOwner == .game else {
            releasePads()
            // Silenced, its engines kept for the resume.
            RumbleFeed.shared.pauseAll()
            // No gun trigger stays held under the menu.
            AimFeed.shared.pad(mask: 0)
            return
        }
        // shortcut: one chord state for all controllers, so a chord on one holds those elements at zero on the others.
        let raw = controllers.map { (controller: $0, reading: Self.reading($0)) }
        if !consumed.isEmpty,
           raw.allSatisfy({ r in consumed.allSatisfy { GamepadMapping.trigger(r.reading[$0] ?? 0) == 0 } }) {
            consumed = []
        }
        var readings: [(slot: Int, snapshot: GamepadSnapshot, controller: GCController)] = []
        for (controller, rawReading) in raw {
            let id = ObjectIdentifier(controller)
            let key = live[id] ?? PlayerSlots.key(category: controller.productCategory, vendor: controller.vendorName ?? "",
                                                   live: Array(live.values))
            live[id] = key
            // A controller with no slot claims one on a press the default mapping sees; chord elements are withheld first.
            let reading = GamepadMapping.withholding(consumed, from: rawReading)
            let pressed = GamepadMapping.snapshot(reading: reading, mapping: PlayerMapping()).mask != 0
            guard let slot = slots.slot(of: key) ?? (pressed ? claim(key, controller) : nil) else { continue }
            readings.append((slot, GamepadMapping.snapshot(reading: reading, mapping: mappings[slot]), controller))
        }
        let setting = gyroSetting()
        for (slot, snapshot, controller) in readings {
            let pad = PadSnapshot.slot(slot)
            let rate = controller.motion.map { activate($0, of: controller).rotationRate }
            let steered = pad.setGyro(steer(slot, rateX: -(rate?.y ?? 0), rateY: -(rate?.x ?? 0), setting: rate == nil ? nil : setting))
            guard steered || snapshot != lastSent[slot] || snapshot != pad.current else { continue }
            lastSent[slot] = snapshot
            pad.replace(with: snapshot)
            pad.send(source: .controller)
        }
        if let held = slots.slot(of: Self.keyboardKey) {
            let reading = Dictionary(uniqueKeysWithValues: keyboardKeys.map { ($0, Float(1)) })
            let snapshot = GamepadMapping.snapshot(reading: reading, mapping: mappings[held], keyboard: true)
            let pad = PadSnapshot.slot(held)
            if snapshot != lastSent[held] || snapshot != pad.current {
                lastSent[held] = snapshot
                pad.replace(with: snapshot)
                pad.send(source: .controller)
            }
        }
        moveRoute(readings)
        // The snapshot's bytes already grow down, so unlike GameController's raw y they are not negated.
        if let gun = AimFeed.shared.gunPlayer, let held = readings.first(where: { $0.slot == gun }), let link {
            AimFeed.shared.stick(x: (Double(held.snapshot.rx) - 128) / 127, y: (Double(held.snapshot.ry) - 128) / 127,
                                 dt: link.targetTimestamp - link.timestamp)
        }
        // The gun player's pad as just sent also presses a Top Shot's mouse buttons; AimFeed sends only a change.
        if let gun = AimFeed.shared.gunPlayer {
            AimFeed.shared.pad(mask: PadSnapshot.slot(gun).current.mask)
        }
        var motion = Array(repeating: Sixaxis.rest, count: PlayerSlots.count)
        for (controller, _) in raw {
            guard let slot = self.slot(of: controller), tiltOn(slot), let sensors = controller.motion else { continue }
            motion[slot] = inverted(sixaxis(sensors, of: controller), slot: slot)
        }
        // The phone feeds slot 0 only while no controller holds it, so the two never mix.
        let phoneWanted = !slots.playerOneHasController && (tiltOn(0) || setting != nil)
        if phoneWanted != phone.isDeviceMotionActive {
            if phoneWanted { phone.startDeviceMotionUpdates() } else { phone.stopDeviceMotionUpdates() }
        }
        if phoneWanted, tiltOn(0), let sample = phone.deviceMotion {
            motion[0] = inverted(Self.sixaxis(sample), slot: 0)
        }
        // A touch-pad player 1 is steered by the phone, handed only an offset.
        if !slots.playerOneHasController, setting != nil || steering[0] != nil {
            let rate = phone.deviceMotion.map(Self.steeringRates) ?? (0, 0)
            steerTouchPad(rateX: rate.0, rateY: rate.1, setting: phoneWanted ? setting : nil)
        }
        for slot in motion.indices {
            let value = motion[slot]
            guard value != lastMotion[slot] else { continue }
            lastMotion[slot] = value
            PadSnapshot.slot(slot).sendMotion(value)
        }
        var held: [ControllerKey: GCController] = [:]
        for (controller, _) in raw {
            if let key = live[ObjectIdentifier(controller)] { held[key] = controller }
        }
        RumbleFeed.shared.poll(slots: slots, controllers: held)
    }

    // shortcut: Move 1 only (port 6), and the pointer's signs are inference until a Move title is played.
    private func moveRoute(_ readings: [(slot: Int, snapshot: GamepadSnapshot, controller: GCController)]) {
        guard let serial, let slot = SettingsStore.shared.moveSlot[serial],
              let held = readings.first(where: { $0.slot == slot }) else {
            moveOff()
            return
        }
        let pad = PadSnapshot.slot(6)
        if movePlugged == nil {
            pad.setConnected(true)
        }
        movePlugged = slot
        lightMove(slot, held.controller)
        if (SettingsStore.shared.movePointer[serial] ?? 0) == 0, let sensors = held.controller.motion {
            let rate = activate(sensors, of: held.controller).rotationRate
            pointer.integrate(yawRate: -rate.y, pitchRate: -rate.x, dt: link?.duration ?? 1.0 / 60)
        }
        // A Touch pointer takes the drag's position after the recentre, so holding the Move button never moves it.
        let r1: UInt32 = 1 << UInt32(Rpcs3PadButton.R1.rawValue)
        let now = CACurrentMediaTime()
        if held.snapshot.mask & r1 == 0 {
            moveHeldSince = nil
        } else if let since = moveHeldSince {
            if now - since >= 1 {
                pointer.recentre()
                moveHeldSince = .infinity
            }
        } else {
            moveHeldSince = now
        }
        if SettingsStore.shared.movePointer[serial] == 1 { pointer = moveTouch }
        var moved = held.snapshot
        (moved.lx, moved.ly) = pointer.stick
        guard moved != moveLast else { return }
        moveLast = moved
        pad.replace(with: moved)
        pad.send(source: .controller)
    }

    private func moveOff() {
        guard movePlugged != nil else { return }
        movePlugged = nil
        moveLast = nil
        moveHeldSince = nil
        pointer.recentre()
        moveTouch.recentre()
        PadSnapshot.slot(6).reset(source: .controller)
        PadSnapshot.slot(6).setConnected(false)
        if let lit = moveController, let held = self.slot(of: lit) { showSlot(held, on: lit) }
        moveController = nil
    }

    // The core is asked at most every 0.25 s, one request at a time; an answer that lands after the Move moved on is dropped.
    private func lightMove(_ slot: Int, _ controller: GCController) {
        if let previous = moveController, previous !== controller {
            if let held = self.slot(of: previous) { showSlot(held, on: previous) }
        }
        moveController = controller
        let now = CACurrentMediaTime()
        guard !moveLightPending, now - moveLightAsked >= 0.25 else { return }
        moveLightAsked = now
        moveLightPending = true
        PadSnapshot.move.sphereColor { [weak self] color in
            guard let self else { return }
            self.moveLightPending = false
            guard self.movePlugged == slot, self.moveController === controller else { return }
            let sphere = color.flatMap { MotionMath.sphereColor(r: $0.0, g: $0.1, b: $0.2) }
            let wanted = sphere.map { GCColor(red: $0.r, green: $0.g, blue: $0.b) } ?? Self.slotColors[slot]
            if let light = controller.light, light.color.red != wanted.red || light.color.green != wanted.green
                || light.color.blue != wanted.blue {
                light.color = wanted
            }
        }
    }

    // The core makes ports 3-6 fake pads while Move is on, so only players 1-3 can be claimed.
    private var moveOn: Bool {
        serial.flatMap { SettingsStore.shared.moveSlot[$0] } != nil
    }

    // The touch pad's own buttons and sticks are never set or replaced here. `setting` nil lets go of the steering.
    func steerTouchPad(rateX: Double, rateY: Double, setting: GyroStick?) {
        if PadSnapshot.shared.setGyro(steer(0, rateX: rateX, rateY: rateY, setting: setting)) {
            PadSnapshot.shared.send(source: .touch)
        }
    }

    private func steer(_ slot: Int, rateX: Double, rateY: Double, setting: GyroStick?) -> (target: Int, x: Double, y: Double)? {
        guard let setting else {
            steering[slot] = nil
            return nil
        }
        let offset = MotionMath.gyroOffset(rateX: rateX, rateY: rateY, setting: setting, previous: steering[slot] ?? (0, 0))
        steering[slot] = offset
        return (setting.target, offset.0, offset.1)
    }

    private func gyroSetting() -> GyroStick? {
        guard let serial, let target = SettingsStore.shared.gyroStickTarget[serial] else { return nil }
        return GyroStick(target: target, sensitivity: SettingsStore.shared.gyroStickSensitivity[serial] ?? 100)
    }

    // shortcut: the signs are inference (turning right steers right); one negation here if a player reports them reversed.
    private static func steeringRates(_ sample: CMDeviceMotion) -> (Double, Double) {
        let g = sample.gravity
        let r = sample.rotationRate
        let length = (g.x * g.x + g.y * g.y + g.z * g.z).squareRoot()
        let yaw = length > 0 ? (r.x * g.x + r.y * g.y + r.z * g.z) / length : 0
        let pitch = MotionMath.screenFrame(x: r.x, y: r.y, z: r.z, orientation: orientation()).0
        return (yaw, -pitch)
    }

    private func tiltOn(_ slot: Int) -> Bool {
        !SettingsStore.shared.controllerTiltOff.contains(slot)
    }

    // Invert Tilt X is bit 1 and Invert Tilt Y bit 2.
    private func inverted(_ value: Sixaxis, slot: Int) -> Sixaxis {
        let bits = SettingsStore.shared.controllerTiltInvert[String(slot)] ?? 0
        return value.applying(invertX: bits & 1 != 0, invertY: bits & 2 != 0)
    }

    // Gravity plus user acceleration (a shake is signal), rotated into the screen frame.
    private static func sixaxis(_ sample: CMDeviceMotion) -> Sixaxis {
        let g = sample.gravity
        let a = MotionMath.screenFrame(x: g.x + sample.userAcceleration.x, y: g.y + sample.userAcceleration.y,
                                       z: g.z + sample.userAcceleration.z, orientation: orientation())
        let r = sample.rotationRate
        let length = (g.x * g.x + g.y * g.y + g.z * g.z).squareRoot()
        let yaw = length > 0 ? (r.x * g.x + r.y * g.y + r.z * g.z) / length : 0
        return MotionMath.sixaxis(ax: a.0, ay: a.1, az: a.2, yaw: yaw)
    }

    // Portrait when there is none.
    private static func orientation() -> ScreenOrientation {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        switch scene?.effectiveGeometry.interfaceOrientation {
        case .portraitUpsideDown: return .portraitUpsideDown
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        default: return .portrait
        }
    }

    // shortcut: the DS3 axes and the negated yaw follow RPCS3's DualSense handler; GCMotion's axes are not measured.
    private func sixaxis(_ sensors: GCMotion, of controller: GCController) -> Sixaxis {
        activate(sensors, of: controller)
        let a = sensors.hasGravityAndUserAcceleration
            ? GCAcceleration(x: sensors.gravity.x + sensors.userAcceleration.x, y: sensors.gravity.y + sensors.userAcceleration.y,
                             z: sensors.gravity.z + sensors.userAcceleration.z)
            : sensors.acceleration
        return MotionMath.sixaxis(ax: a.x, ay: a.y, az: a.z, yaw: -sensors.rotationRate.y)
    }

    // Remembered so stop() switches them off, for the battery.
    @discardableResult private func activate(_ sensors: GCMotion, of controller: GCController) -> GCMotion {
        if sensors.sensorsRequireManualActivation && !sensors.sensorsActive {
            sensors.sensorsActive = true
            activated.append(controller)
        }
        return sensors
    }

    func slot(of controller: GCController) -> Int? {
        live[ObjectIdentifier(controller)].flatMap { slots.slot(of: $0) }
    }

    // `op=back` when the slot was reserved for this controller.
    private func claim(_ key: ControllerKey, _ controller: GCController) -> Int? {
        guard let slot = moveOn ? slots.claim(key, limit: 3) : slots.claim(key) else { return nil }
        showSlot(slot, on: controller)
        if slot != 0 { PadSnapshot.slot(slot).setConnected(true) }
        NotificationCenter.default.post(name: .armsx3PlayerSlotsChanged, object: nil)
        return slot
    }

    // `light` is nil on a controller with none.
    private func showSlot(_ slot: Int, on controller: GCController) {
        controller.playerIndex = GCControllerPlayerIndex(rawValue: slot) ?? .indexUnset
        controller.light?.color = Self.slotColors[slot]
    }

    // Every slot whose holder changed has its pad released once, and players 2-4 are plugged in or out to match.
    func pin(slot: Int, to target: Int) {
        guard slots.holders.indices.contains(slot), let key = slots.holders[slot] else { return }
        let before = slots.holders
        guard slots.pin(key, to: target) else { return }
        SettingsStore.shared.controllerPins = slots.storedPins
        settle(from: before)
    }

    // With no free slot the keyboard holds none and tries again next tick.
    private func placeKeyboard() {
        let slot = SettingsStore.shared.keyboardSlot
        let want = GCKeyboard.coalesced != nil && (0..<(moveOn ? 3 : PlayerSlots.count)).contains(slot) ? slot : nil
        guard slots.slot(of: Self.keyboardKey) != want else { return }
        let before = slots.holders
        if slots.release(Self.keyboardKey) != nil { keyboardKeys = [] }
        if let want, slots.claim(Self.keyboardKey, limit: moveOn ? 3 : PlayerSlots.count) != nil {
            _ = slots.pin(Self.keyboardKey, to: want)
            slots.unpin(Self.keyboardKey)
        }
        if slots.holders != before { settle(from: before) }
    }

    private func settle(from before: [ControllerKey?]) {
        for n in before.indices where slots.holders[n] != before[n] {
            lastSent[n] = nil
            PadSnapshot.slot(n).reset(source: .controller)
            if n != 0 { PadSnapshot.slot(n).setConnected(slots.holders[n] != nil) }
        }
        for controller in controllersProvider() {
            if let n = self.slot(of: controller) { showSlot(n, on: controller) }
        }
        NotificationCenter.default.post(name: .armsx3PlayerSlotsChanged, object: nil)
    }

    // Mid-game (`report`) the slot waits for its controller and the screen is told; otherwise the slot is freed.
    private func reconcile(_ controllers: [GCController], report: Bool) {
        let present = Set(controllers.map(ObjectIdentifier.init))
        for (id, key) in live where !present.contains(id) {
            live[id] = nil
            if report { loseSlot(of: key) } else { releaseSlot(of: key) }
        }
    }

    // Slot 0 goes back to the touch pad and stays plugged in.
    private func loseSlot(of key: ControllerKey) {
        guard let slot = slots.lose(key) else { return }
        lastSent[slot] = nil
        PadSnapshot.slot(slot).reset(source: .controller)
        if slot != 0 { PadSnapshot.slot(slot).setConnected(false) }
        NotificationCenter.default.post(name: .armsx3PlayerSlotsChanged, object: nil)
        onPlayerLost?(slot)
    }

    private func releaseSlot(of key: ControllerKey) {
        guard let slot = slots.release(key) else { return }
        lastSent[slot] = nil
        PadSnapshot.slot(slot).reset(source: .controller)
        if slot != 0 { PadSnapshot.slot(slot).setConnected(false) }
        NotificationCenter.default.post(name: .armsx3PlayerSlotsChanged, object: nil)
    }

    private func setSystemGestures(_ state: GCControllerElement.SystemGestureState) {
        for controller in controllersProvider() {
            guard let pad = controller.extendedGamepad else { continue }
            for element in [pad.buttonHome, pad.buttonMenu, pad.buttonOptions] {
                element?.preferredSystemGestureState = state
            }
        }
    }

    // The slots stay claimed.
    private func releasePads() {
        for slot in lastSent.indices where lastSent[slot] != nil {
            lastSent[slot] = nil
            PadSnapshot.slot(slot).reset(source: .controller)
        }
        // Port 6's Move pad stays plugged in while the menu is open.
        if moveLast != nil {
            moveLast = nil
            PadSnapshot.slot(6).reset(source: .controller)
        }
    }

    // Every element of the physical input profile by name: buttons, the d-pad's and sticks' half-axes, the triggers.
    private static func reading(_ controller: GCController) -> [String: Float] {
        controller.physicalInputProfile.buttons.mapValues { $0.value }
    }
}

extension Notification.Name {
    // Posted whenever a controller claims or releases a player slot.
    static let armsx3PlayerSlotsChanged = Notification.Name("armsx3PlayerSlotsChanged")
}
