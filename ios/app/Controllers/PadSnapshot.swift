// SPDX-License-Identifier: GPL-2.0-only
// One whole pad per port, sent in one façade call. The touch pad only ever touches `shared`, player 1.

import Foundation

@MainActor final class PadSnapshot {
    static let shared = PadSnapshot(player: 0)
    static let others = [PadSnapshot(player: 1), PadSnapshot(player: 2), PadSnapshot(player: 3)]
    // Port 6, Move 1's pad.
    static let move = PadSnapshot(player: 6)

    // Player n + 1's snapshot for n 0...3, or 6, the Move's port.
    static func slot(_ n: Int) -> PadSnapshot {
        n == 0 ? shared : n == 6 ? move : others[n - 1]
    }

    static let centre: UInt8 = 128

    private(set) var mask: UInt32 = 0
    private(set) var lx = PadSnapshot.centre
    private(set) var ly = PadSnapshot.centre
    private(set) var rx = PadSnapshot.centre
    private(set) var ry = PadSnapshot.centre
    private(set) var l2: UInt8 = 0
    private(set) var r2: UInt8 = 0
    // The stick it steers (0 left, 1 right) and its offset; nil while not steered.
    private(set) var gyro: (target: Int, x: Double, y: Double)? = nil

    let player: Int

    private init(player: Int) {
        self.player = player
    }

    func set(_ button: Rpcs3PadButton, pressed: Bool) {
        let bit: UInt32 = 1 << UInt32(button.rawValue)
        mask = pressed ? mask | bit : mask & ~bit
        if button == .L2 { l2 = pressed ? 255 : 0 }
        if button == .R2 { r2 = pressed ? 255 : 0 }
    }

    func setStick(left: Bool, x: UInt8, y: UInt8) {
        if left {
            lx = x
            ly = y
        } else {
            rx = x
            ry = y
        }
    }

    // The poller compares with this, so a late touch-pad release cannot leave a controller's press released.
    var current: GamepadSnapshot {
        GamepadSnapshot(mask: mask, lx: lx, ly: ly, rx: rx, ry: ry, l2: l2, r2: r2)
    }

    // GamepadMapping has already made every value, trigger pressure included.
    func replace(with s: GamepadSnapshot) {
        mask = s.mask
        lx = s.lx
        ly = s.ly
        rx = s.rx
        ry = s.ry
        l2 = s.l2
        r2 = s.r2
    }

    // Says whether that changed the sticks a send carries; the caller sends.
    func setGyro(_ g: (target: Int, x: Double, y: Double)?) -> Bool {
        let before = sticks()
        gyro = g
        return sticks() != before
    }

    // The gyro is folded into a copy, never the stored sticks, so the touch pad and the poller never overwrite each other.
    private func sticks() -> [UInt8] {
        var s = [lx, ly, rx, ry]
        if let gyro {
            let i = gyro.target == 0 ? 0 : 2
            (s[i], s[i + 1]) = MotionMath.fold(stick: (s[i], s[i + 1]), offset: (gyro.x, gyro.y))
        }
        return s
    }

    func send(source: SessionRecord.PadSource) {
        // The façade calls completions on main.
        let count: (Int) -> Void = { status in
            MainActor.assumeIsolated { SessionRecord.shared.countPad(source: source, status: status) }
        }
        let s = sticks()
        if player == 0 {
            Rpcs3Bridge.setPadStateButtons(mask, leftX: s[0], leftY: s[1], rightX: s[2], rightY: s[3], l2: l2, r2: r2, completion: count)
        } else {
            Rpcs3Bridge.setPlayer(Int(player), padStateButtons: mask, leftX: s[0], leftY: s[1], rightX: s[2], rightY: s[3], l2: l2, r2: r2,
                                  completion: count)
        }
    }

    // On the façade's input queue, so it stays ordered with this port's pad sends.
    func setConnected(_ connected: Bool) {
        Rpcs3Bridge.setPlayer(Int(player), connected: connected, completion: nil)
    }

    // On the façade's input queue, so it stays ordered with this port's pad sends; not counted in the session record.
    func sendMotion(_ value: Sixaxis) {
        Rpcs3Bridge.setPlayer(Int(player), motionX: value.x, y: value.y, z: value.z, g: value.g, completion: nil)
    }

    // nil when the game holds none. `done` runs on main.
    func sphereColor(_ done: @escaping @MainActor ((Float, Float, Float)?) -> Void) {
        Rpcs3Bridge.moveSphereColor(6 - player) { r, g, b, status in
            MainActor.assumeIsolated { done(status == 0 ? (r, g, b) : nil) }
        }
    }

    // Used when the touch pad hides, so a button held at that moment is not left down in the guest.
    func reset(source: SessionRecord.PadSource = .touch) {
        mask = 0
        lx = Self.centre
        ly = Self.centre
        rx = Self.centre
        ry = Self.centre
        l2 = 0
        r2 = 0
        // No gyro offset outlives the pad it steered.
        gyro = nil
        send(source: source)
    }
}
