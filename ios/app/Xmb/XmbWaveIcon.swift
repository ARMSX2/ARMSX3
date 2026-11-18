// SPDX-License-Identifier: GPL-2.0-only
// The XMB wave as a small picture, ported from Android's XmbWaveIcon.kt, with a slow swell added.

import SwiftUI

/// The wave in its purple tile. `animated` lets it swell slowly; a still copy draws the resting frame.
struct XmbWaveIcon: View {
    var animated = true
    var cornerFraction: CGFloat = 0.24
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        // A few frames a second is enough for a slow swell and costs next to nothing.
        TimelineView(.animation(minimumInterval: 1.0 / 15, paused: !animated || reduceMotion || scenePhase != .active)) { context in
            Canvas { canvas, size in
                Self.draw(in: &canvas, size: size, time: context.date.timeIntervalSinceReferenceDate, corner: cornerFraction)
            }
        }
        .accessibilityHidden(true)
    }

    private static let sparkles: [(x: Double, y: Double, r: Double)] =
        [(0.16, 0.60, 0.022), (0.30, 0.66, 0.016), (0.55, 0.55, 0.020), (0.72, 0.50, 0.026), (0.86, 0.47, 0.016)]

    static func draw(in canvas: inout GraphicsContext, size: CGSize, time: Double, corner: CGFloat) {
        let w = size.width, h = size.height
        canvas.clip(to: Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: min(w, h) * corner))
        canvas.fill(Path(CGRect(origin: .zero, size: size)),
                    with: .linearGradient(Gradient(stops: [.init(color: Color(red: 0.110, green: 0.043, blue: 0.243), location: 0),
                                                           .init(color: Color(red: 0.290, green: 0.122, blue: 0.659), location: 0.55),
                                                           .init(color: Color(red: 0.569, green: 0.314, blue: 0.949), location: 1)]),
                                          startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
        // Android's curve, its two ends rising and falling out of step.
        let swell = sin(time * 0.9) * 0.025, sway = sin(time * 0.6 + 1.3) * 0.025
        func wave(_ y0: Double, _ y1: Double, _ lift: Double) -> Path {
            var path = Path()
            path.move(to: CGPoint(x: -0.05 * w, y: (y0 + swell) * h))
            path.addCurve(to: CGPoint(x: 1.05 * w, y: (y1 + sway) * h),
                          control1: CGPoint(x: 0.3 * w, y: (y0 + lift + swell) * h),
                          control2: CGPoint(x: 0.6 * w, y: (y1 - lift + sway) * h))
            return path
        }
        var under = wave(0.70, 0.56, 0.06)
        under.addLine(to: CGPoint(x: 1.05 * w, y: 1.05 * h))
        under.addLine(to: CGPoint(x: -0.05 * w, y: 1.05 * h))
        under.closeSubpath()
        canvas.fill(under, with: .color(.white.opacity(0.10)))
        canvas.stroke(wave(0.84, 0.70, 0.05), with: .color(.white.opacity(0.30)), style: StrokeStyle(lineWidth: w * 0.03, lineCap: .round))
        let crest = wave(0.70, 0.56, 0.06)
        canvas.stroke(crest, with: .color(.white.opacity(0.22)), style: StrokeStyle(lineWidth: w * 0.13, lineCap: .round))
        canvas.stroke(crest, with: .color(.white.opacity(0.95)), style: StrokeStyle(lineWidth: w * 0.04, lineCap: .round))
        for (index, sparkle) in sparkles.enumerated() {
            // Each sparkle rides the swell and twinkles on its own beat.
            let y = sparkle.y + swell + (sway - swell) * sparkle.x
            let alpha = 0.55 + 0.30 * sin(time * 1.7 + Double(index) * 1.9)
            let r = sparkle.r * w
            canvas.fill(Path(ellipseIn: CGRect(x: sparkle.x * w - r, y: y * h - r, width: 2 * r, height: 2 * r)),
                        with: .color(.white.opacity(alpha)))
        }
    }
}

/// The wave as a line glyph in the bar's own colour, drawn to sit beside the system icons.
struct XmbWaveGlyph: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    // It stays on screen as long as the library does, so it holds still when the phone is saving power, and under a game.
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        let paused = reduceMotion || lowPower || scenePhase != .active || XmbInGame.shared.screenUp
        TimelineView(.animation(minimumInterval: 1.0 / 15, paused: paused)) { context in
            Canvas { canvas, size in
                let w = size.width, h = size.height, t = context.date.timeIntervalSinceReferenceDate
                let swell = sin(t * 0.9) * 0.06, sway = sin(t * 0.6 + 1.3) * 0.06
                func wave(_ y0: Double, _ y1: Double, _ lift: Double) -> Path {
                    var path = Path()
                    path.move(to: CGPoint(x: 0.04 * w, y: (y0 + swell) * h))
                    path.addCurve(to: CGPoint(x: 0.96 * w, y: (y1 + sway) * h),
                                  control1: CGPoint(x: 0.35 * w, y: (y0 + lift + swell) * h),
                                  control2: CGPoint(x: 0.65 * w, y: (y1 - lift + sway) * h))
                    return path
                }
                canvas.stroke(wave(0.62, 0.40, 0.22), with: .foreground, style: StrokeStyle(lineWidth: w * 0.115, lineCap: .round))
                canvas.stroke(wave(0.82, 0.62, 0.18), with: .style(.foreground.opacity(0.45)),
                              style: StrokeStyle(lineWidth: w * 0.085, lineCap: .round))
                for (x, y, r) in [(0.30, 0.28, 0.060), (0.60, 0.20, 0.050), (0.82, 0.14, 0.065)] {
                    let alpha = 0.5 + 0.4 * sin(t * 1.7 + x * 9)
                    canvas.fill(Path(ellipseIn: CGRect(x: x * w - r * w, y: (y + swell) * h - r * w, width: 2 * r * w, height: 2 * r * w)),
                                with: .style(.foreground.opacity(alpha)))
                }
            }
        }
        .accessibilityHidden(true)
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: DispatchQueue.main)) { _ in
            lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        }
    }
}
