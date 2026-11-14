// SPDX-License-Identifier: GPL-2.0-only
// The console's "Original" background colour by month and hour, ported from Android's XmbOriginalColors.kt.

import Foundation

/// A gradient that changes with the month and dims in the small hours, as the console's Theme Settings > Color >
/// Original does. The twelve are linkev's measurements of the console's daytime colours
/// (github.com/linkev/PlayStation-3-XMB, MIT), with October from a capture of a real console. Each is a CSS-style
/// linear gradient: an angle (0 points up, 90 to the right) from `start` to `end`.
///
/// The night darkens the same colour: full until 23:00, down to the night's level by midnight, the top much darker
/// than the foot, and back up from 04:00 to full by 06:30.
enum XmbOriginalColors {
    /// 0...1 RGB stops, blended as they are, and the angle in degrees.
    struct Gradient: Equatable, Sendable {
        var angleDeg: Float
        var start: SIMD3<Float>
        var end: SIMD3<Float>
    }

    /// The theme colour setting that goes through every month in turn.
    static let cycle = 12

    private static func g(_ angle: Float, _ s: UInt32, _ e: UInt32) -> Gradient {
        Gradient(angleDeg: angle, start: rgb(s), end: rgb(e))
    }

    private static func rgb(_ c: UInt32) -> SIMD3<Float> {
        SIMD3(Float(c >> 16 & 0xFF) / 255, Float(c >> 8 & 0xFF) / 255, Float(c & 0xFF) / 255)
    }

    private static let day = [
        g(90.25, 0xC5C5C5, 0xC9C9C9),
        g(67, 0xCB9E0D, 0xDBD629),
        g(106, 0x8EBE28, 0x68A816),
        g(136.75, 0xD8B6B6, 0xE74275),
        g(1.5, 0x136C13, 0x189C18),
        g(148.75, 0xC678EE, 0x674DA1),
        g(26.5, 0x00A792, 0x0AF0EF),
        g(62.5, 0x00005F, 0x21D9FF),
        g(148.5, 0x922C9B, 0xD962EC),
        g(160, 0xADB327, 0xD0BF3C),
        g(90, 0x734414, 0x9A762F),
        g(170.5, 0xEC442D, 0xD63F2B),
    ]

    /// `month`'s colour in full day, for a swatch: the middle of its gradient.
    static func swatch(month: Int) -> SIMD3<Float> {
        let full = day[min(max(month, 0), 11)]
        return (full.start + full.end) / 2
    }

    /// How bright the gradient's start (the top) and its end (the foot) are in the small hours.
    private static let nightStart: Float = 0.10
    private static let nightEnd: Float = 0.30

    /// How long the cycle stays on each month before easing into the next.
    private static let cycleMonthMs: Int64 = 20000

    private static func ease(_ x: Float) -> Float {
        let t = min(max(x, 0), 1)
        return t * t * (3 - 2 * t)
    }

    /// How far into the night it is at `hour` (0..<24, fractional): 0 by day, 1 in the small hours.
    static func night(_ hour: Float) -> Float {
        if hour < 4 { return 1 }
        if hour < 6.5 { return 1 - ease((hour - 4) / 2.5) }
        if hour < 23 { return 0 }
        return ease(hour - 23)
    }

    /// The gradient for `month` (0 is January) at `hour`.
    static func at(month: Int, hour: Float) -> Gradient {
        let full = day[min(max(month, 0), 11)]
        let n = night(hour)
        return Gradient(angleDeg: full.angleDeg, start: full.start * (1 + (nightStart - 1) * n),
                        end: full.end * (1 + (nightEnd - 1) * n))
    }

    /// The gradient at `date` for a theme colour setting: -1 follows the month, 0...11 keeps that month's, and
    /// `cycle` shows each for twenty seconds, the last quarter easing into the next.
    static func at(_ date: Date, themeColor: Int, calendar: Calendar = .current) -> Gradient {
        let parts = calendar.dateComponents([.month, .hour, .minute], from: date)
        let hour = Float(parts.hour ?? 12) + Float(parts.minute ?? 0) / 60
        guard themeColor == cycle else {
            return at(month: (0 ... 11).contains(themeColor) ? themeColor : (parts.month ?? 1) - 1, hour: hour)
        }
        let ms = Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
        let t = Float(ms % (12 * cycleMonthMs)) / Float(cycleMonthMs)
        let month = Int(t) % 12
        let f = ease((t - Float(month) - 0.75) / 0.25)
        let a = at(month: month, hour: hour)
        let b = at(month: (month + 1) % 12, hour: hour)
        return Gradient(angleDeg: a.angleDeg + (b.angleDeg - a.angleDeg) * f, start: a.start + (b.start - a.start) * f,
                        end: a.end + (b.end - a.end) * f)
    }

    /// The gradient's direction for a `width` x `height` surface, as the shader takes it: t = dot(uv, dir) with uv
    /// in 0...1 and y pointing down, then u = t - tMin. A CSS gradient spans the projection of the whole box onto
    /// its direction, so the two corners along it land on 0 and 1.
    static func shaderDirection(angleDeg: Float, width: Int, height: Int) -> (dir: SIMD2<Float>, tMin: Float) {
        let a = Double(angleDeg) * .pi / 180
        let w = Double(max(width, 1))
        let h = Double(max(height, 1))
        let dx = w * sin(a)
        let dy = -h * cos(a)
        let length = max(abs(w * sin(a)) + abs(h * cos(a)), 1e-6)
        let ux = Float(dx / length)
        let uy = Float(dy / length)
        // dot((0.5, 0.5), dir) is the centre, which must land on 0.5.
        return (SIMD2(ux, uy), 0.5 * (ux + uy) - 0.5)
    }
}
