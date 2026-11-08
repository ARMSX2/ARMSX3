// SPDX-License-Identifier: GPL-2.0-only
// Hotkey chords by raw value, mirroring the router's types. Foundation only, for the host test; nothing is a core setting.

import Foundation

struct HotkeyTable: Equatable {
    /// Action raw value to raw chord. Missing: the default. Empty: no hotkey.
    var stored: [String: String]

    enum AssignResult: Equatable {
        case assigned(HotkeyTable)
        /// The action that holds the chord now; the player confirms before it loses it.
        case conflict(String)
        /// Quick Menu's chord, a malformed chord or an unknown action.
        case refused
    }

    static let quickMenu = "quickMenu"

    static let actions = [
        "quickMenu", "saveGameState", "loadGameState", "cycleSaveSlot", "saveStateAndExit",
        "togglePerformanceOverlay", "resolutionUp", "resolutionDown", "closeGame", "resetGame", "pressureModifier",
        "screenshot", "toggleRecording",
    ]

    /// Every newer action ships unbound, so no game button is taken over.
    static let defaults = ["quickMenu": "select+start", "saveGameState": "start+r2", "loadGameState": "start+l2"]

    /// Pause and Start are one physical button, so `pause` is read as `start`.
    static let buttons = [
        "select", "start", "l3", "r3", "l1", "r1", "l2", "r2", "cross", "circle", "square", "triangle",
        "dpadUp", "dpadDown", "dpadLeft", "dpadRight",
    ]

    private static let buttonTitles = [
        "select": String(localized: "Select"), "start": String(localized: "Start"), "l3": "L3", "r3": "R3", "l1": "L1", "r1": "R1", "l2": "L2", "r2": "R2",
        "cross": String(localized: "Cross"), "circle": String(localized: "Circle"), "square": String(localized: "Square"), "triangle": String(localized: "Triangle"),
        "dpadUp": String(localized: "Up"), "dpadDown": String(localized: "Down"), "dpadLeft": String(localized: "Left"), "dpadRight": String(localized: "Right"),
    ]

    private static let actionTitles = [
        "quickMenu": String(localized: "Quick Menu"), "saveGameState": String(localized: "Quick Save"), "loadGameState": String(localized: "Quick Load"),
        "cycleSaveSlot": String(localized: "Cycle Save Slot"), "saveStateAndExit": String(localized: "Save State and Exit"),
        "togglePerformanceOverlay": String(localized: "Performance Overlay"), "resolutionUp": String(localized: "Resolution Up"),
        "resolutionDown": String(localized: "Resolution Down"), "closeGame": String(localized: "Close Game"), "resetGame": String(localized: "Reset Game"),
        "pressureModifier": String(localized: "Pressure Modifier"), "screenshot": String(localized: "Screenshot"),
        "toggleRecording": String(localized: "Record Video"),
    ]

    static let resetTable = HotkeyTable(stored: [:])

    /// Exactly two distinct known buttons, `pause` read as `start`; anything else is nil.
    static func normalised(_ chord: String) -> String? {
        let parts = chord.split(separator: "+", omittingEmptySubsequences: false).map { $0 == "pause" ? "start" : String($0) }
        guard parts.count == 2, parts[0] != parts[1], parts.allSatisfy(buttons.contains) else { return nil }
        return parts.joined(separator: "+")
    }

    /// "Select + Start" in PS3 button names, or "None".
    static func title(_ chord: String?) -> String {
        guard let chord, let normal = normalised(chord) else { return String(localized: "None") }
        return normal.split(separator: "+").compactMap { buttonTitles[String($0)] }.joined(separator: " + ")
    }

    static func actionTitle(_ action: String) -> String { actionTitles[action] ?? action }

    static func caption(_ action: String) -> String? {
        switch action {
        case "loadGameState": String(localized: "Asks before loading.")
        case "pressureModifier": String(localized: "Works while held.")
        default: nil
        }
    }

    /// Quick Menu always has one: an empty or malformed stored value reads as its default.
    func chord(for action: String) -> String? {
        let chord = stored[action].map { Self.normalised($0) } ?? Self.defaults[action]
        return action == Self.quickMenu ? chord ?? Self.defaults[action] : chord
    }

    /// The other action bound to this chord, in either button order.
    func holder(of chord: String, except action: String) -> String? {
        guard let normal = Self.normalised(chord) else { return nil }
        let pair = Set(normal.split(separator: "+"))
        return Self.actions.first { other in
            other != action && self.chord(for: other).map { Set($0.split(separator: "+")) == pair } == true
        }
    }

    func assigning(_ chord: String, to action: String, replacing: Bool) -> AssignResult {
        guard Self.actions.contains(action), let normal = Self.normalised(chord) else { return .refused }
        var next = self
        if let holder = holder(of: normal, except: action) {
            if holder == Self.quickMenu { return .refused }
            guard replacing else { return .conflict(holder) }
            next.stored[holder] = ""
        }
        next.stored[action] = normal
        return .assigned(next)
    }

    /// Nil for Quick Menu, which is never unbound.
    func clearing(_ action: String) -> HotkeyTable? {
        guard action != Self.quickMenu, Self.actions.contains(action) else { return nil }
        var next = self
        next.stored[action] = ""
        return next
    }

    /// The quick slot (11) goes to 0, slots 0-8 step up, and 9 or anything else returns to the quick slot.
    static func nextSaveSlot(after slot: Int) -> Int {
        if slot == 11 { return 0 }
        return (0...8).contains(slot) ? slot + 1 : 11
    }

    /// The scale plus a step, clamped into 25...800 (percent).
    static func steppedScale(_ value: Int, by step: Int) -> Int {
        min(max(value + step, 25), 800)
    }
}
