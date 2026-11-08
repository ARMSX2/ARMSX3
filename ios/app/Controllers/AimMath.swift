// SPDX-License-Identifier: GPL-2.0-only
// Light-gun and tablet aim in a fixed 1280x720 space, y down. Foundation only, for the host test.

import Foundation

enum AimMath {
    static let logicalWidth = 1280
    static let logicalHeight = 720

    struct Cursor: Equatable {
        var x: Int
        var y: Int
    }

    /// Nil outside `imageRect`, so a touch in a letterbox bar aims nowhere.
    static func touch(_ p: (x: Double, y: Double), imageRect: (x: Double, y: Double, w: Double, h: Double)) -> Cursor? {
        let r = imageRect
        guard [p.x, p.y, r.x, r.y, r.w, r.h].allSatisfy({ $0.isFinite }), r.w > 0, r.h > 0 else { return nil }
        let u = (p.x - r.x) / r.w, v = (p.y - r.y) / r.h
        guard (0...1).contains(u), (0...1).contains(v) else { return nil }
        // GunCon 3 turns x == x_max into 32768, which wraps its int16 to the far left, so the last column lands on 1279.
        return Cursor(x: Int((u * Double(logicalWidth - 1)).rounded()), y: Int((v * Double(logicalHeight - 1)).rounded()))
    }

    /// Clamped to the edges.
    static func stick(_ c: Cursor, x: Double, y: Double, unitsPerSecond: Double, dt: Double) -> Cursor {
        let step = unitsPerSecond * dt
        guard [x, y, step].allSatisfy({ $0.isFinite }), dt > 0 else { return c }
        return Cursor(x: clamped(Double(c.x) + min(max(x, -1), 1) * step, logicalWidth),
                      y: clamped(Double(c.y) + min(max(y, -1), 1) * step, logicalHeight))
    }

    static func mouse(_ c: Cursor, dx: Int, dy: Int) -> Cursor {
        // Each delta is bounded first, so no sum can overflow and trap.
        Cursor(x: clamped(Double(c.x) + Double(min(max(dx, -logicalWidth), logicalWidth)), logicalWidth),
               y: clamped(Double(c.y) + Double(min(max(dy, -logicalHeight), logicalHeight)), logicalHeight))
    }

    // Desktop's defaults bind neither gun's other buttons to R2 or L2, so neither press doubles as one.
    static let topShotTrigger: UInt32 = 1 << 11
    static let topShotSecond: UInt32 = 1 << 10

    /// Bit 0 is button 1 and bit 1 button 2. GunCon 3 (11) fires from its pad mapping and holds none.
    static func mouseButtons(device: Int, padMask: UInt32, pen: Bool) -> Int {
        switch device {
        case 12, 13: return (padMask & topShotTrigger != 0 ? 1 : 0) | (padMask & topShotSecond != 0 ? 2 : 0)
        case 14: return pen ? 1 : 0
        default: return 0
        }
    }

    private static func clamped(_ value: Double, _ limit: Int) -> Int {
        // Strictly below the bound.
        Int(min(max(value, 0), Double(limit - 1)).rounded())
    }
}
