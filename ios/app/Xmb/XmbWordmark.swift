// SPDX-License-Identifier: GPL-2.0-only
// The ARMSX3 boot logo, the word ARMSX3 where the console said PS3, drawn in code after Android's XmbWordmark.kt.

import SwiftUI

/// The word's outline. One unit is the letter height: one stroke weight throughout, letters half again as wide as
/// they are high and set well apart, large rounded outer corners with the inner corners inside them round to match,
/// square ends, and the PS3 logo's S.
struct XmbWordmarkShape: Shape {
    private static let stroke: CGFloat = 0.21
    private static let outer: CGFloat = 0.34
    // An inner corner inside an outer one, so the stroke keeps its weight round it.
    private static let inner = outer - stroke
    private static let small: CGFloat = 0.035
    private static let midTop = 0.5 - stroke / 2
    private static let midBottom = 0.5 + stroke / 2
    private static let gap: CGFloat = 0.42

    private static let glyphs: [(width: CGFloat, draw: (inout Outline, CGFloat) -> Void)] = [
        (1.50, letterA), (1.50, letterR), (1.80, letterM), (1.55, letterS), (1.50, letterX),
        (1.50, { three(&$0, $1, stroke: stroke, outer: outer, notch: 0.09) }),
    ]

    /// The word's width in letter heights.
    static let width = glyphs.reduce(0) { $0 + $1.width } + gap * CGFloat(glyphs.count - 1)

    func path(in rect: CGRect) -> Path {
        var outline = Outline(origin: rect.origin, unit: rect.height)
        for glyph in Self.glyphs {
            glyph.draw(&outline, glyph.width)
            outline.x0 += glyph.width + Self.gap
        }
        return outline.path
    }

    /// Outline building in letter units, y down, scaled to points as it goes.
    private struct Outline {
        var path = Path()
        let origin: CGPoint
        let unit: CGFloat
        var x0: CGFloat = 0

        private func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: origin.x + (x0 + x) * unit, y: origin.y + y * unit)
        }

        mutating func move(_ x: CGFloat, _ y: CGFloat) { path.move(to: point(x, y)) }
        mutating func line(_ x: CGFloat, _ y: CGFloat) { path.addLine(to: point(x, y)) }
        mutating func close() { path.closeSubpath() }

        /// An arc of radius `r` from `start` degrees through `sweep` (0 is right, 90 is down).
        mutating func arc(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ start: Double, _ sweep: Double) {
            path.addArc(center: point(cx, cy), radius: r * unit, startAngle: .degrees(start),
                        endAngle: .degrees(start + sweep), clockwise: sweep < 0)
        }

        /// A counter, with its corners' radii clockwise from the top left. It winds against the letter's outline, so
        /// one nonzero fill leaves it open and still fills where the X's strokes cross.
        mutating func hole(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat,
                           _ tl: CGFloat, _ tr: CGFloat, _ br: CGFloat, _ bl: CGFloat) {
            move(l, t + tl)
            line(l, b - bl)
            arc(l + bl, b - bl, bl, 180, -90)
            line(r - br, b)
            arc(r - br, b - br, br, 90, -90)
            line(r, t + tr)
            arc(r - tr, t + tr, tr, 0, -90)
            line(l + tl, t)
            arc(l + tl, t + tl, tl, 270, -90)
            close()
        }
    }

    /// An arch with a bar across its middle.
    private static func letterA(_ o: inout Outline, _ w: CGFloat) {
        o.move(0, 1)
        o.line(0, outer)
        o.arc(outer, outer, outer, 180, 90)
        o.line(w - outer, 0)
        o.arc(w - outer, outer, outer, 270, 90)
        o.line(w, 1)
        o.line(w - stroke, 1)
        o.line(w - stroke, midBottom + small)
        o.arc(w - stroke - small, midBottom + small, small, 0, -90)
        o.line(stroke + small, midBottom)
        o.arc(stroke + small, midBottom + small, small, 270, -90)
        o.line(stroke, 1)
        o.close()
        o.hole(stroke, stroke, w - stroke, midTop, inner, inner, small, small)
    }

    /// A bowl over a full upright, and a leg out from under the bowl.
    private static func letterR(_ o: inout Outline, _ w: CGFloat) {
        let bowl: CGFloat = 0.26
        let legWidth: CGFloat = 0.34
        let legX: CGFloat = 0.62
        o.move(0, 1)
        o.line(0, outer)
        o.arc(outer, outer, outer, 180, 90)
        o.line(w - outer, 0)
        o.arc(w - outer, outer, outer, 270, 90)
        o.line(w, midBottom - bowl)
        o.arc(w - bowl, midBottom - bowl, bowl, 0, 90)
        o.line(legX + legWidth, midBottom)
        o.line(w, 1)
        o.line(w - legWidth, 1)
        o.line(legX, midBottom)
        o.line(stroke + small, midBottom)
        o.arc(stroke + small, midBottom + small, small, 270, -90)
        o.line(stroke, 1)
        o.close()
        o.hole(stroke, stroke, w - stroke, midTop, inner, inner, max(bowl - stroke, small), small)
    }

    /// Two arches side by side.
    private static func letterM(_ o: inout Outline, _ w: CGFloat) {
        let cx = w / 2
        o.move(0, 1)
        o.line(0, outer)
        o.arc(outer, outer, outer, 180, 90)
        o.line(w - outer, 0)
        o.arc(w - outer, outer, outer, 270, 90)
        o.line(w, 1)
        o.line(w - stroke, 1)
        o.line(w - stroke, stroke + inner)
        o.arc(w - stroke - inner, stroke + inner, inner, 0, -90)
        o.line(cx + stroke / 2 + small, stroke)
        o.arc(cx + stroke / 2 + small, stroke + small, small, 270, -90)
        o.line(cx + stroke / 2, 1)
        o.line(cx - stroke / 2, 1)
        o.line(cx - stroke / 2, stroke + small)
        o.arc(cx - stroke / 2 - small, stroke + small, small, 0, -90)
        o.line(stroke + inner, stroke)
        o.arc(stroke + inner, stroke + inner, inner, 270, -90)
        o.line(stroke, 1)
        o.close()
    }

    /// The PS3 S: an upright down the middle with a bar out to the right at its head and to the left at its foot.
    private static func letterS(_ o: inout Outline, _ w: CGFloat) {
        let sx = (w - stroke) / 2
        o.move(sx, outer)
        o.arc(sx + outer, outer, outer, 180, 90)
        o.line(w, 0)
        o.line(w, stroke)
        o.line(sx + stroke + inner, stroke)
        o.arc(sx + stroke + inner, stroke + inner, inner, 270, -90)
        o.line(sx + stroke, 1 - outer)
        o.arc(sx + stroke - outer, 1 - outer, outer, 0, 90)
        o.line(0, 1)
        o.line(0, 1 - stroke)
        o.line(sx - inner, 1 - stroke)
        o.arc(sx - inner, 1 - stroke - inner, inner, 90, -90)
        o.line(sx, outer)
        o.close()
    }

    /// Two strokes crossing corner to corner, cut flat at the top and the foot.
    private static func letterX(_ o: inout Outline, _ w: CGFloat) {
        // How wide a stroke of this weight is, measured across, at this slant.
        var k = stroke
        for _ in 0 ..< 20 { k = stroke * ((w - k) * (w - k) + 1).squareRoot() }
        o.move(0, 0)
        o.line(k, 0)
        o.line(w, 1)
        o.line(w - k, 1)
        o.close()
        o.move(w - k, 0)
        o.line(w, 0)
        o.line(k, 1)
        o.line(0, 1)
        o.close()
    }

    /// The 3: three bars into an upright with two rounded bowls, a notch where they meet.
    private static func three(_ o: inout Outline, _ w: CGFloat, stroke t: CGFloat, outer ro: CGFloat, notch: CGFloat) {
        // The counters' corners under the outer ones, no rounder than a counter leaves room for.
        let rc = min(ro - t, 0.5 - 1.5 * t - small)
        o.move(0, 0)
        o.line(w - ro, 0)
        o.arc(w - ro, ro, ro, 270, 90)
        o.line(w, 0.5 - notch)
        o.arc(w - notch, 0.5 - notch, notch, 0, 90)
        o.arc(w - notch, 0.5 + notch, notch, 270, 90)
        o.line(w, 1 - ro)
        o.arc(w - ro, 1 - ro, ro, 0, 90)
        o.line(0, 1)
        o.line(0, 1 - t)
        o.line(w - t - rc, 1 - t)
        o.arc(w - t - rc, 1 - t - rc, rc, 90, -90)
        o.line(w - t, 0.5 + t / 2 + small)
        o.arc(w - t - small, 0.5 + t / 2 + small, small, 0, -90)
        o.line(0, 0.5 + t / 2)
        o.line(0, 0.5 - t / 2)
        o.line(w - t - small, 0.5 - t / 2)
        o.arc(w - t - small, 0.5 - t / 2 - small, small, 90, -90)
        o.line(w - t, t + rc)
        o.arc(w - t - rc, t + rc, rc, 0, -90)
        o.line(0, t)
        o.close()
    }
}

/// The word drawn white over a light grey at the foot, with a short hard shadow down and to the right. It sits in
/// a box `unitsWide` by `unitsHigh` letter heights, `pad` in from the top left, which leaves room for the shadow.
struct XmbWordmark: View {
    let letterHeight: CGFloat

    static let pad: CGFloat = 0.15
    static let unitsWide = pad + XmbWordmarkShape.width + pad
    static let unitsHigh = pad + 1 + pad + 0.08

    var body: some View {
        XmbWordmarkShape()
            .fill(LinearGradient(colors: [.white, Color(white: 210.0 / 255)], startPoint: .top, endPoint: .bottom))
            .frame(width: XmbWordmarkShape.width * letterHeight, height: letterHeight)
            .shadow(color: .black.opacity(115.0 / 255), radius: letterHeight * 0.012, x: letterHeight * 0.045,
                    y: letterHeight * 0.06)
            .padding(.leading, Self.pad * letterHeight)
            .padding(.top, Self.pad * letterHeight)
            .frame(width: Self.unitsWide * letterHeight, height: Self.unitsHigh * letterHeight, alignment: .topLeading)
    }
}
