// SPDX-License-Identifier: GPL-2.0-only
// The one aim cursor for GunCon 3, both Top Shots and uDraw, sent as an absolute position on mouse 0. Never logged.

import CoreGraphics
import Foundation

@MainActor final class AimFeed {
    static let shared = AimFeed()

    // input::product_type's GunCon 3, Top Shot Elite, Top Shot Fearmaster and uDraw.
    static let gunOrdinals = 11...14
    // Logical units a second at full right-stick tilt: across the image in about 1.3 s.
    static let stickSpeed = 960.0

    enum PenSource {
        case pad, screen
    }

    private(set) var active = false
    private(set) var gunPlayer: Int?
    private(set) var cursor = AimFeed.centre
    // The game image's rectangle in window coordinates, as the game screen last laid it out. Zero aims nowhere.
    var imageRect = CGRect.zero
    private var device = 0
    private var padMask: UInt32 = 0
    private var pens = Set<PenSource>()
    private var held = 0

    private static let centre = AimMath.Cursor(x: AimMath.logicalWidth / 2, y: AimMath.logicalHeight / 2)

    private init() {}

    // Any button the last device held is released first; nil stops aiming.
    func activate(gunPlayer: Int?, device: Int = 0) {
        padMask = 0
        pens = []
        sendButtons()
        self.gunPlayer = gunPlayer
        self.device = device
        active = gunPlayer != nil
        cursor = Self.centre
        guard active else { return }
        Rpcs3Bridge.setMouseConnected(true, completion: nil)
        send()
    }

    // False when the point is outside the image (a letterbox bar) or nothing aims.
    @discardableResult func touch(at point: CGPoint, imageRect: CGRect) -> Bool {
        guard active, let next = AimMath.touch((x: Double(point.x), y: Double(point.y)),
                                               imageRect: (x: Double(imageRect.minX), y: Double(imageRect.minY),
                                                           w: Double(imageRect.width), h: Double(imageRect.height)))
        else { return false }
        move(to: next)
        return true
    }

    // Each axis -1...1 with y growing down, held for `dt` seconds.
    func stick(x: Double, y: Double, dt: Double) {
        guard active else { return }
        move(to: AimMath.stick(cursor, x: x, y: y, unitsPerSecond: Self.stickSpeed, dt: dt))
    }

    // A hardware mouse's delta in logical units, y growing down.
    func mouseDelta(dx: Int, dy: Int) {
        guard active else { return }
        move(to: AimMath.mouse(cursor, dx: dx, dy: dy))
    }

    // Rpcs3PadButton bits; 0 releases them.
    func pad(mask: UInt32) {
        padMask = mask
        sendButtons()
    }

    // The pen is down while either source has a finger aiming on the picture.
    func pen(_ down: Bool, from source: PenSource) {
        if down { pens.insert(source) } else { pens.remove(source) }
        sendButtons()
    }

    // shortcut: assumes the core's default 16:9 aspect with Stretch To Display Area off; read both if aim lands off.
    static func imageRect(in frame: CGRect) -> CGRect {
        let scale = min(frame.width / 16, frame.height / 9)
        guard scale.isFinite, scale > 0 else { return .zero }
        return CGRect(x: frame.midX - 8 * scale, y: frame.midY - 4.5 * scale, width: 16 * scale, height: 9 * scale)
    }

    // Sent only when the cursor changed, so at most once per touch event, frame or mouse send.
    private func move(to next: AimMath.Cursor) {
        guard next != cursor else { return }
        cursor = next
        send()
    }

    private func send() {
        Rpcs3Bridge.mouseAimX(cursor.x, y: cursor.y, maxX: AimMath.logicalWidth, maxY: AimMath.logicalHeight, completion: nil)
    }

    // A mouse button is sent only when it changed, so a held trigger is one press.
    private func sendButtons() {
        let next = active ? AimMath.mouseButtons(device: device, padMask: padMask, pen: !pens.isEmpty) : 0
        for (bit, button) in [(1, 1), (2, 2)] where next & bit != held & bit {
            Rpcs3Bridge.mouseAimButton(button, pressed: next & bit != 0, completion: nil)
        }
        held = next
    }
}
