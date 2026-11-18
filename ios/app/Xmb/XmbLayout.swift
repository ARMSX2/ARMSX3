// SPDX-License-Identifier: GPL-2.0-only
// The XMB's geometry and timings, Android's XmbView.kt numbers: the console's, measured at 1280x720.

import CoreGraphics
import Foundation

struct XmbMetrics: Equatable {
    let width: CGFloat
    let height: CGFloat
    /// One 1280x720 unit in points. Landscape fits the tighter axis; portrait keeps Android's width / 900.
    let u: CGFloat
    /// How far the Dynamic Island or the rounded corners reach in from each side.
    let leftInset: CGFloat
    let rightInset: CGFloat

    init(width: CGFloat, height: CGFloat, leftInset: CGFloat = 0, rightInset: CGFloat = 0) {
        self.width = width
        self.height = height
        self.leftInset = leftInset
        self.rightInset = rightInset
        u = width >= height ? min(width / 1280, height / 720) : width / 900
    }

    var catY: CGFloat { 193 * u }
    var catIcon: CGFloat { 80 * u }
    var catSpacing: CGFloat { 137 * u }
    /// The focused column's x; nothing is centred, so a wide screen keeps it near the left.
    var focusX: CGFloat { min(376 * u, 0.3 * width) }
    var labelY: CGFloat { catY + 41 * u }
    var labelFont: CGFloat { 17.5 * u }
    var focusedY: CGFloat { catY + 140 * u }
    var belowY: CGFloat { focusedY + 120 * u }
    var aboveY: CGFloat { catY - 113 * u }
    var itemStep: CGFloat { 53 * u }
    var gameIcon: CGSize { CGSize(width: 207 * u, height: 113 * u) }
    var gameIconSmall: CGFloat { 97.0 / 207.0 }
    var squareIcon: CGFloat { 84 * u }
    var squareIconSmall: CGFloat { 52.0 / 84.0 }
    var titleFont: CGFloat { 23 * u }
    var subtitleFont: CGFloat { 14.5 * u }
    var smallTitleFont: CGFloat { 19 * u }
    var textGap: CGFloat { 28 * u }
    var levelShift: CGFloat { 168 * u }
    var panelLeft: CGFloat { 0.69 * width }
    var panelRow: CGFloat { 30 * u }
    var childIcon: CGFloat { 26 * u }
    var childTextX: CGFloat { focusX + 34 * u }
    var textMaxWidth: CGFloat { max(width - focusX - 160 * u, 120) }

    func columnX(_ index: Int, colAnim: Double) -> CGFloat {
        focusX + CGFloat(Double(index) - colAnim) * catSpacing
    }

    /// The centre y of an item `d` places after the (fractional) focus: 120 down to the first below, 253 up to the
    /// first above, then a 53 step either way.
    func itemY(_ d: Double) -> CGFloat {
        let d = CGFloat(d)
        if d >= 1 { return belowY + (d - 1) * itemStep }
        if d >= 0 { return focusedY + d * (belowY - focusedY) }
        if d >= -1 { return focusedY + d * (focusedY - aboveY) }
        return aboveY + (d + 1) * itemStep
    }

    /// 1 on the focus, falling to 0 one item away.
    static func near(_ d: Double) -> Double { min(max(1 - abs(d), 0), 1) }

    /// Items above the focus fade out over the next two.
    static func far(_ d: Double) -> Double { d < -1 ? min(max(1 + (d + 1) * 0.5, 0), 1) : 1 }

    /// Items drawn below the focus: Android's count, or as many as reach the bottom of a taller screen.
    func rowsBelow(_ minimum: Int) -> Int {
        max(minimum, Int(((height - belowY) / itemStep).rounded(.up)) + 2)
    }

    /// The index nearest `y` in a list focused on `focus`, if `y` is close enough to it to count as a tap on it. The
    /// focused item of a column reaches as far as a game's icon whatever it is, as on Android.
    func hitItem(y: CGFloat, count: Int, focus: Int, inList: Bool) -> Int? {
        guard count > 0 else { return nil }
        var best: (index: Int, distance: CGFloat)?
        for i in 0 ..< count {
            let distance = abs(itemY(Double(i - focus)) - y)
            if best == nil || distance < best!.distance { best = (i, distance) }
        }
        guard let best else { return nil }
        let reach: CGFloat
        if best.index == focus {
            reach = 0.6 * (inList ? squareIcon : gameIcon.height)
        } else {
            reach = 0.55 * itemStep
        }
        return best.distance < reach ? best.index : nil
    }

    /// The panel row under `y` for a panel focused on `focus`.
    func panelRow(y: CGFloat, focus: Int) -> Int {
        focus + Int(((y - focusedY) / panelRow).rounded())
    }

    /// The bottom-right region that answers as the corner hint does, shown or not.
    func inCorner(_ point: CGPoint) -> Bool {
        point.x > width - 230 * u && point.y > height - 110 * u
    }

    /// Where along the range editor's bar `x` falls, 0 to 1.
    func rangeFraction(x: CGFloat) -> Double {
        let left = panelLeft + 28 * u, right = rangeRight
        return Double(min(max((x - left) / (right - left), 0), 1))
    }

    /// Where the range editor's bar ends, short of the island.
    var rangeRight: CGFloat { width - max(36 * u, rightInset) }

    /// Where the side panel's rows end, short of the island.
    var panelRowRight: CGFloat { width - max(16 * u, rightInset) }

    /// The box beside an open list where its preview shows.
    var asideFrame: CGRect {
        CGRect(x: 0.58 * width, y: catY + 64 * u, width: 0.38 * width, height: max(height - catY - 120 * u, 80))
    }
}

enum XmbTiming {
    static let column = 0.22
    static let focus = 0.17
    /// The labels, the other items' names and the dimming as a folder opens, on a tween of their own.
    static let levelFade = 0.18
    static let level = 0.24
    /// The other rows' names going as a choice panel opens.
    static let quiet = 0.16
    static let aside = 0.26
    static let childFocus = 0.15
    static let childAppear = 0.22
    static let panel = 0.2
    static let panelFocus = 0.12
    static let dialog = 0.16
    /// How long the focus must rest on a game before its PIC1 comes in.
    static let backdropDelay = 0.65
    static let backdropFade = 0.7
    static let clock: TimeInterval = 10
    static let battery: TimeInterval = 30
}
