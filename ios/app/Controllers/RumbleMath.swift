// SPDX-License-Identifier: GPL-2.0-only
// Rumble bytes to haptic intensities; the core's pad config already applied the curve. Foundation only, for the host test.

import Foundation

enum RumbleTarget: Equatable {
    // `accessory`: a controller whose motors are reached over its External Accessory session, not CoreHaptics.
    case controller, accessory, phone, none
}

enum RumbleMath {
    static let gameSirStopPacket: [UInt8] = [0x04, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]

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

    static func gameSirPacket(left: Float, right: Float) -> [UInt8] {
        [
            0x04,
            gameSirAmplitude(left),
            0x01,
            gameSirAmplitude(right),
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
        ]
    }

    private static func gameSirAmplitude(_ value: Float) -> UInt8 {
        let finite = value.isFinite ? value : 0
        return UInt8((min(1, max(0, finite)) * 255).rounded())
    }

    // Players 2-4 never get a phone stand-in; any slot outside 0-3 reaches nothing. A controller with both an
    // accessory channel and its own haptics rumbles on the accessory. A spare accessory controller (one not paired
    // with the accessory) keeps its own haptics, which are its own device, but never gets the phone, which may be
    // in another player's hands.
    static func target(slot: Int, hasController: Bool, controllerHasHaptics: Bool, controllerHasAccessory: Bool = false,
                       controllerIsSpareAccessory: Bool = false, phoneHasHaptics: Bool) -> RumbleTarget {
        guard (0..<4).contains(slot) else { return .none }
        if hasController && controllerIsSpareAccessory { return controllerHasHaptics ? .controller : .none }
        if hasController && controllerHasAccessory { return .accessory }
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
