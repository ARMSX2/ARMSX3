// SPDX-License-Identifier: GPL-2.0-only
// Rumble bytes to haptic intensities; the core's pad config already applied the curve. Foundation only, for the host test.

import Foundation

enum RumbleTarget: Equatable {
    case controller, phone, none
}

enum RumbleMath {

    struct Motors: Equatable {
        var large: Float
        var small: Float

        static let off = Motors(large: 0, small: 0)
    }

    static func motors(large: UInt8, small: UInt8, strengthPercent: Int, enabled: Bool) -> Motors {
        guard enabled else { return .off }
        let strength = Float(min(max(strengthPercent, 0), 100)) / 100
        return Motors(large: Float(large) / 255 * strength, small: Float(small) / 255 * strength)
    }

    static func single(_ m: Motors) -> Float {
        max(m.large, m.small)
    }

    // Players 2-4 never get a phone stand-in; any slot outside 0-3 reaches nothing.
    static func target(slot: Int, hasController: Bool, controllerHasHaptics: Bool, phoneHasHaptics: Bool) -> RumbleTarget {
        guard (0..<4).contains(slot) else { return .none }
        if hasController && controllerHasHaptics { return .controller }
        return slot == 0 && phoneHasHaptics ? .phone : .none
    }

    struct ChangeGate {
        // A fresh gate counts as stopped, so it never sends a zero.
        private var last = Motors.off

        mutating func update(_ m: Motors) -> Bool {
            guard m != last else { return false }
            last = m
            return true
        }
    }
}
