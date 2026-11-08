// SPDX-License-Identifier: GPL-2.0-only
// A controller reading and a player's mapping to one PS3 pad snapshot. Foundation only, for the host test.

import Foundation

struct GamepadSnapshot: Equatable {
    var mask: UInt32
    var lx: UInt8
    var ly: UInt8
    var rx: UInt8
    var ry: UInt8
    var l2: UInt8
    var r2: UInt8
}

struct GamepadBit {
    let button: String
    let bit: UInt32
}

struct PlayerMapping: Equatable {
    // The applier walks this order, never a dictionary's.
    static let bindingKeys = [
        "Left Stick Left", "Left Stick Down", "Left Stick Right", "Left Stick Up",
        "Right Stick Left", "Right Stick Down", "Right Stick Right", "Right Stick Up",
        "Start", "Select", "PS Button", "Square", "Cross", "Circle", "Triangle",
        "Left", "Down", "Right", "Up", "R1", "R2", "R3", "L1", "L2", "L3",
    ]

    // Every name here is one a GCController.withExtendedGamepad() snapshot lists in physicalInputProfile.buttons.
    static let defaults: [String: String] = [
        "Left Stick Left": "Left Thumbstick Left", "Left Stick Down": "Left Thumbstick Down",
        "Left Stick Right": "Left Thumbstick Right", "Left Stick Up": "Left Thumbstick Up",
        "Right Stick Left": "Right Thumbstick Left", "Right Stick Down": "Right Thumbstick Down",
        "Right Stick Right": "Right Thumbstick Right", "Right Stick Up": "Right Thumbstick Up",
        "Start": "Button Menu", "Select": "Button Options", "PS Button": "Button Home",
        "Square": "Button X", "Cross": "Button A", "Circle": "Button B", "Triangle": "Button Y",
        "Left": "Direction Pad Left", "Down": "Direction Pad Down", "Right": "Direction Pad Right", "Up": "Direction Pad Up",
        "R1": "Right Shoulder", "R2": "Right Trigger", "R3": "Right Thumbstick Button",
        "L1": "Left Shoulder", "L2": "Left Trigger", "L3": "Left Thumbstick Button",
    ]

    // RPCS3's keyboard pad defaults (keyboard_pad_handler::init_config), as HID usages.
    static let keyboardDefaults: [String: String] = [
        "Left Stick Left": "Keyboard 4", "Left Stick Down": "Keyboard 22",
        "Left Stick Right": "Keyboard 7", "Left Stick Up": "Keyboard 26",
        "Right Stick Left": "Keyboard 76", "Right Stick Down": "Keyboard 77",
        "Right Stick Right": "Keyboard 78", "Right Stick Up": "Keyboard 74",
        "Start": "Keyboard 40", "Select": "Keyboard 44", "PS Button": "Keyboard 42",
        "Square": "Keyboard 29", "Cross": "Keyboard 27", "Circle": "Keyboard 6", "Triangle": "Keyboard 25",
        "Left": "Keyboard 80", "Down": "Keyboard 81", "Right": "Keyboard 79", "Up": "Keyboard 82",
        "R1": "Keyboard 8", "R2": "Keyboard 23", "R3": "Keyboard 10",
        "L1": "Keyboard 20", "L2": "Keyboard 21", "L3": "Keyboard 9",
    ]

    // A missing or empty binding means the key's default; there is no unbound state.
    var bindings: [String: String] = [:]
    // Deadzone in the 0...255 byte scale, sensitivity in percent. Clamped when applied.
    var leftDeadzone = 0
    var rightDeadzone = 0
    var leftMultiplier = 100
    var rightMultiplier = 100

    enum Axis { case leftX, leftY, rightX, rightY }

    // Left stick first, each stick in the order left, down, right, up.
    static let stickKeys = Array(bindingKeys.prefix(8))

    // The half that reads negative, then the positive one.
    static func halves(_ axis: Axis) -> (String, String) {
        switch axis {
        case .leftX: return ("Left Thumbstick Left", "Left Thumbstick Right")
        case .leftY: return ("Left Thumbstick Up", "Left Thumbstick Down")
        case .rightX: return ("Right Thumbstick Left", "Right Thumbstick Right")
        case .rightY: return ("Right Thumbstick Up", "Right Thumbstick Down")
        }
    }

    func source(_ key: String) -> String {
        if let stored = bindings[key], !stored.isEmpty { return stored }
        return Self.defaults[key] ?? ""
    }

    // A stored controller element, which no key can press, reads the keyboard default instead.
    func source(_ key: String, keyboard: Bool) -> String {
        guard keyboard else { return source(key) }
        if let stored = bindings[key], stored.hasPrefix("Keyboard ") { return stored }
        return Self.keyboardDefaults[key] ?? ""
    }

    // The key that read `source` takes `key`'s previous element. An unknown key or an empty source changes nothing.
    func binding(_ key: String, to source: String, keyboard: Bool = false) -> PlayerMapping {
        guard Self.bindingKeys.contains(key), !source.isEmpty else { return self }
        var next = self
        if let other = Self.bindingKeys.first(where: { $0 != key && self.source($0, keyboard: keyboard) == source }) {
            next.bindings[other] = self.source(key, keyboard: keyboard)
        }
        next.bindings[key] = source
        return next
    }

    // The stick key that reads the axis's negative half points the other way.
    func inverted(_ axis: Axis) -> Bool {
        let negative = Self.halves(axis).0
        guard let key = Self.stickKeys.first(where: { source($0) == negative }) else { return false }
        return key.split(separator: " ").last != negative.split(separator: " ").last
    }

    var invertLeftY: Bool { inverted(.leftY) }

    func setting(invert axis: Axis, _ on: Bool) -> PlayerMapping {
        guard inverted(axis) != on else { return self }
        let (negative, positive) = Self.halves(axis)
        guard let a = Self.stickKeys.first(where: { source($0) == negative }),
              let b = Self.stickKeys.first(where: { source($0) == positive }) else { return self }
        var next = self
        next.bindings[a] = positive
        next.bindings[b] = negative
        return next
    }

    var sticksSwapped: Bool { Self.stickKeys.prefix(4).allSatisfy { source($0).hasPrefix("Right Thumbstick") } }

    // Written out explicitly.
    func swappingSticks() -> PlayerMapping {
        var next = self
        for (left, right) in zip(Self.stickKeys.prefix(4), Self.stickKeys.suffix(4)) {
            next.bindings[left] = source(right)
            next.bindings[right] = source(left)
        }
        return next
    }

    static func clamped(deadzone: Int) -> Int { min(max(deadzone, 0), 255) }
    static func clamped(multiplier: Int) -> Int { min(max(multiplier, 0), 200) }

    // Chords are found on default physical buttons, so a remap never carries one into the game.
    static func sources(forMask mask: UInt32) -> Set<String> {
        Set(GamepadMapping.table.filter { mask & (1 << $0.bit) != 0 }
            .compactMap { GamepadMapping.keyForButton[$0.button].flatMap { defaults[$0] } })
    }

    // An empty binding and its default spelled out are the same mapping.
    static func == (a: PlayerMapping, b: PlayerMapping) -> Bool {
        bindingKeys.allSatisfy { a.source($0) == b.source($0) }
            && a.leftDeadzone == b.leftDeadzone && a.rightDeadzone == b.rightDeadzone
            && a.leftMultiplier == b.leftMultiplier && a.rightMultiplier == b.rightMultiplier
    }

    // Anything but a `players` array of exactly four objects gives four defaults; a wrongly typed value keeps its default.
    static func decode(_ data: Data?) -> [PlayerMapping] {
        let fallback = Array(repeating: PlayerMapping(), count: 4)
        guard let data, let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let players = root["players"] as? [[String: Any]], players.count == 4 else { return fallback }
        return players.map { player in
            var mapping = PlayerMapping()
            for key in bindingKeys {
                if let value = player[key] as? String, !value.isEmpty { mapping.bindings[key] = value }
            }
            mapping.leftDeadzone = player["Left Stick Deadzone"] as? Int ?? mapping.leftDeadzone
            mapping.rightDeadzone = player["Right Stick Deadzone"] as? Int ?? mapping.rightDeadzone
            mapping.leftMultiplier = player["Left Stick Multiplier"] as? Int ?? mapping.leftMultiplier
            mapping.rightMultiplier = player["Right Stick Multiplier"] as? Int ?? mapping.rightMultiplier
            return mapping
        }
    }
}

enum GamepadMapping {
    static let table: [GamepadBit] = [
        GamepadBit(button: "up", bit: 0),
        GamepadBit(button: "down", bit: 1),
        GamepadBit(button: "left", bit: 2),
        GamepadBit(button: "right", bit: 3),
        GamepadBit(button: "cross", bit: 4),
        GamepadBit(button: "circle", bit: 5),
        GamepadBit(button: "square", bit: 6),
        GamepadBit(button: "triangle", bit: 7),
        GamepadBit(button: "L1", bit: 8),
        GamepadBit(button: "R1", bit: 9),
        GamepadBit(button: "L2", bit: 10),
        GamepadBit(button: "R2", bit: 11),
        GamepadBit(button: "start", bit: 12),
        GamepadBit(button: "select", bit: 13),
        GamepadBit(button: "L3", bit: 14),
        GamepadBit(button: "R3", bit: 15),
        GamepadBit(button: "PS", bit: 16),
    ]

    static let keyForButton: [String: String] = [
        "up": "Up", "down": "Down", "left": "Left", "right": "Right",
        "cross": "Cross", "circle": "Circle", "square": "Square", "triangle": "Triangle",
        "L1": "L1", "R1": "R1", "L2": "L2", "R2": "R2", "start": "Start", "select": "Select",
        "L3": "L3", "R3": "R3", "PS": "PS Button",
    ]

    static func snapshot(reading: [String: Float], mapping: PlayerMapping, keyboard: Bool = false) -> GamepadSnapshot {
        func value(_ key: String) -> Float {
            let v = reading[mapping.source(key, keyboard: keyboard)] ?? 0
            return v.isFinite ? v : 0
        }
        let l2 = trigger(value("L2"))
        let r2 = trigger(value("R2"))
        var mask: UInt32 = 0
        for entry in table {
            let key = keyForButton[entry.button] ?? ""
            let pressed = key == "L2" ? l2 > 0 : key == "R2" ? r2 > 0 : value(key) >= 0.5
            if pressed { mask |= 1 << entry.bit }
        }
        let (lx, ly) = stick(value("Left Stick Right") - value("Left Stick Left"), value("Left Stick Down") - value("Left Stick Up"),
                             deadzone: mapping.leftDeadzone, multiplier: mapping.leftMultiplier)
        let (rx, ry) = stick(value("Right Stick Right") - value("Right Stick Left"), value("Right Stick Down") - value("Right Stick Up"),
                             deadzone: mapping.rightDeadzone, multiplier: mapping.rightMultiplier)
        return GamepadSnapshot(mask: mask, lx: lx, ly: ly, rx: rx, ry: ry, l2: l2, r2: r2)
    }

    // shortcut: the deadzone is radial; a per-axis one would be two comparisons here if a player asks.
    static func stick(_ x: Float, _ y: Float, deadzone: Int, multiplier: Int) -> (UInt8, UInt8) {
        let dz = Float(PlayerMapping.clamped(deadzone: deadzone)) / 127.5
        let gain = Float(PlayerMapping.clamped(multiplier: multiplier)) / 100
        let magnitude = (x * x + y * y).squareRoot()
        guard magnitude > dz else { return (128, 128) }
        let scale = dz == 0 ? gain : gain * (dz < 1 ? (magnitude - dz) / (1 - dz) : 1) / magnitude
        return (axis(x * scale), axis(y * scale))
    }

    static func withholding(_ names: Set<String>, from reading: [String: Float]) -> [String: Float] {
        var held = reading
        for name in names where held[name] != nil { held[name] = 0 }
        return held
    }

    // 0 is 127.5, which rounds to 128, the façade's centre.
    static func axis(_ v: Float) -> UInt8 {
        guard v.isFinite else { return 128 }
        return UInt8(((min(max(v, -1), 1) + 1) * 127.5).rounded())
    }

    static func trigger(_ v: Float) -> UInt8 {
        guard v.isFinite else { return 0 }
        return UInt8((min(max(v, 0), 1) * 255).rounded())
    }
}
