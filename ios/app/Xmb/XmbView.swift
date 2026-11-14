// SPDX-License-Identifier: GPL-2.0-only
// The XMB drawn and touched, with the geometry and timings of Android's XmbView.kt.

import ImageIO
import SwiftUI
import UIKit

extension Animation {
    /// Android's FastOutSlowIn, which every XMB tween uses.
    static func xmb(_ duration: Double) -> Animation { .timingCurve(0.4, 0, 0.2, 1, duration: duration) }
}

struct XmbView: View {
    let state: XmbState
    let firmware: XmbFirmware
    /// The player's RPCN avatar, shown beside the clock.
    var avatar: UIImage?
    /// What Circle does with nothing open, for a hint when the focused item has no options; nil for no hint.
    var rootHint: String?
    let act: (XmbAction) -> Void

    @State private var touch: TouchTrack?
    /// Until when a change of place is said aloud. VoiceOver reads the value after a swipe up or down by itself, but
    /// not after a named action or a double tap.
    @State private var sayUntil = Date.distantPast

    private struct TouchTrack {
        var start: CGPoint
        var axis: Axis?
        var steps = 0
        var longPressed = false
    }

    var body: some View {
        // The outer reader keeps the safe area the inner one gives up, so the side panel can stop short of the island.
        GeometryReader { safe in
            GeometryReader { geometry in
                let m = XmbMetrics(width: geometry.size.width, height: geometry.size.height,
                                   leftInset: safe.safeAreaInsets.leading, rightInset: safe.safeAreaInsets.trailing)
                ZStack(alignment: .topLeading) {
                    // The slide and the fade of a folder opening run on clocks of their own, as on Android.
                    XmbTween(value: state.depth > 0 ? 1 : 0) { fade in
                        XmbStage(state: state, firmware: firmware, m: m, colAnim: Double(state.column),
                                 focusAnim: Double(state.focusIndex(atDepth: 0)), level: state.depth > 0 ? 1 : 0, fade: fade)
                            .animation(.xmb(XmbTiming.column), value: state.column)
                            .animation(.xmb(XmbTiming.focus), value: state.depth == 0 ? state.focusIndex(atDepth: 0) : -1)
                            .animation(.xmb(XmbTiming.level), value: state.depth)
                    }
                    .animation(.xmb(XmbTiming.levelFade), value: state.depth)
                    if state.depth > 0, let items = state.items(atDepth: state.depth) {
                        XmbSubList(items: items, firmware: firmware, m: m, focusAnim: Double(state.focusIndex(atDepth: state.depth)),
                                   quiet: picking ? 1 : 0)
                            .animation(.xmb(XmbTiming.childFocus), value: state.focusIndex(atDepth: state.depth))
                            .animation(.xmb(XmbTiming.quiet), value: picking)
                            .id(state.levels.last?.node)
                            .transition(.opacity.combined(with: .offset(x: 30 * m.u)))
                    }
                    ZStack(alignment: .topLeading) {
                        if let aside {
                            Group {
                                // Drawn far larger than an icon, so the picture is decoded whole.
                                if case .game(let path?) = aside {
                                    XmbPicture(path: path, fit: true) { XmbIconView(icon: aside, firmware: firmware) }
                                } else {
                                    XmbIconView(icon: aside, firmware: firmware)
                                }
                            }
                                .frame(width: m.asideFrame.width, height: m.asideFrame.height)
                                .offset(x: m.asideFrame.minX, y: m.asideFrame.minY)
                                .transition(.opacity)
                        }
                    }
                    .animation(.xmb(XmbTiming.aside), value: aside == nil)
                    XmbClock(firmware: firmware, m: m, avatar: avatar)
                    hint(m)
                    if let panel = state.panel {
                        XmbSidePanel(panel: panel, swatches: state.node(panel.node)?.swatches, firmware: firmware, m: m,
                                     focusAnim: Double(panel.focus))
                            .animation(.xmb(XmbTiming.panelFocus), value: panel.focus)
                            .transition(.move(edge: .trailing))
                    }
                    if let dialog = state.dialog {
                        XmbDialogView(dialog: dialog, firmware: firmware, m: m, act: act)
                            .transition(.opacity)
                    }
                }
                .animation(.xmb(XmbTiming.childAppear), value: state.levels.last?.node)
                .animation(.xmb(XmbTiming.panel), value: state.panel == nil)
                .animation(.xmb(XmbTiming.dialog), value: state.dialog == nil)
                .contentShape(Rectangle())
                .gesture(drag(m))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: state.current?.title ?? ""))
                .accessibilityValue(Text(verbatim: accessibilityPlace))
                .accessibilityAdjustableAction { direction in
                    let step = direction == .increment ? 1 : -1
                    // A range goes up with increment; a list goes on down, as a swipe up moves it.
                    if case .range? = state.panel { act(.move(dx: step, dy: 0)) } else { act(.move(dx: 0, dy: step)) }
                }
                .accessibilityAction { say(.confirm) }
                .accessibilityAction(named: Text("Next Category")) { say(.column(state.column + 1)) }
                .accessibilityAction(named: Text("Previous Category")) { say(.column(state.column - 1)) }
                .accessibilityAction(named: Text("Back")) { say(.back) }
                .accessibilityAction(named: Text("Options")) { say(.options) }
                .onChange(of: [state.current?.title ?? "", accessibilityPlace, state.dialog?.message ?? ""]) { old, new in
                    // A question or a result that just came up is read at once, as a notice is.
                    if !new[2].isEmpty, new[2] != old[2] {
                        AccessibilityNotification.Announcement(new[1]).post()
                    } else if Date.now < sayUntil {
                        sayUntil = .distantPast
                        let words = new[0] == old[0] ? [new[1]] : [new[0], new[1]]
                        AccessibilityNotification.Announcement(words.filter { !$0.isEmpty }.joined(separator: ", ")).post()
                    }
                }
            }
            .ignoresSafeArea()
        }
    }

    private func say(_ action: XmbAction) {
        sayUntil = .now + 0.5
        act(action)
    }

    /// A setting's choices or range are open, so the other rows give up their names.
    private var picking: Bool {
        switch state.panel {
        case .editor?, .range?: return true
        case .options?, nil: return false
        }
    }

    /// The preview beside an open list: the focused row's, else the innermost open folder's. A picker covers it.
    private var aside: XmbIcon? {
        guard state.depth > 0, state.panel == nil else { return nil }
        if let icon = state.focused?.aside { return icon }
        return state.levels.reversed().lazy.compactMap { state.node($0.node)?.aside }.first
    }

    /// Where VoiceOver is in the column: the focused item, the open panel's row, or the dialog.
    private var accessibilityPlace: String {
        if let dialog = state.dialog { return dialog.message + ", " + dialog.buttons[dialog.focus] }
        var parts: [String] = []
        if let node = state.focused {
            parts.append(node.title)
            if let subtitle = node.subtitle { parts.append(subtitle) }
        }
        if case .options(_, let rows, let focus)? = state.panel { parts.append(rows[focus].title) }
        if case .editor(_, let choices, let focus)? = state.panel { parts.append(choices[focus]) }
        if case .range(_, let range)? = state.panel { parts.append(range.text) }
        return parts.joined(separator: ", ")
    }

    // MARK: - The button hint

    /// The corner's word: Back with something open or over a game's root, else the options when the row has some.
    private var cornerBack: Bool {
        state.depth > 0 || state.busy || (state.focused?.options.isEmpty ?? true) && rootHint != nil
    }

    @ViewBuilder private func hint(_ m: XmbMetrics) -> some View {
        let open = state.depth > 0 || state.busy
        let options = !open && !(state.focused?.options.isEmpty ?? true)
        let back = cornerBack
        if back || options {
            let label = options ? firmware.text(XmbRco.full, "msg_option_with_icon", String(localized: ": Options"))
                : open ? firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")) : rootHint ?? ""
            XmbHintChip(circle: back, label: label, firmware: firmware, m: m) { act(back ? .back : .options) }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .padding(.trailing, 70 * m.u)
            .padding(.bottom, 44 * m.u)
            .accessibilityHidden(true)
            // The drag's own corner tap answers for it, so a swipe that starts on the chip still moves the columns.
            .allowsHitTesting(false)
        }
    }

    // MARK: - Touch

    /// One gesture for everything, so a tap, a long press and a swipe never compete: the axis locks on the first
    /// movement, a swipe steps once per 0.8 column or 1.1 items crossed, and a still touch is a tap or, held, options.
    private func drag(_ m: XmbMetrics) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                if touch == nil {
                    touch = TouchTrack(start: value.startLocation)
                    let start = value.startLocation
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(400))
                        // A touch held on the corner ends as the corner's tap, not as the row behind it.
                        guard var track = touch, track.start == start, track.axis == nil, !track.longPressed, !m.inCorner(start)
                        else { return }
                        track.longPressed = true
                        touch = track
                        longPress(at: start, m)
                    }
                }
                guard var track = touch, !track.longPressed else { return }
                let dx = value.translation.width, dy = value.translation.height
                if track.axis == nil, max(abs(dx), abs(dy)) > 10 {
                    track.axis = abs(dx) > abs(dy) ? .horizontal : .vertical
                }
                guard let axis = track.axis else { return }
                let step = axis == .horizontal ? m.catSpacing * 0.8 : m.itemStep * 1.1
                let travelled = axis == .horizontal ? dx : dy
                let steps = Int(travelled / step)
                while track.steps != steps {
                    let forward = steps < track.steps
                    track.steps += forward ? -1 : 1
                    // Dragging left or up moves on, as a finger pulls the next item in.
                    if axis == .horizontal {
                        act(.move(dx: forward ? 1 : -1, dy: 0))
                    } else {
                        act(.move(dx: 0, dy: forward ? 1 : -1))
                    }
                }
                touch = track
            }
            .onEnded { value in
                defer { touch = nil }
                guard let track = touch, track.axis == nil, !track.longPressed else { return }
                tap(at: value.location, m)
            }
    }

    private func longPress(at point: CGPoint, _ m: XmbMetrics) {
        guard state.depth == 0, !state.busy, let column = state.current,
              let index = m.hitItem(y: point.y, count: column.items.count, focus: state.focus(of: column), inList: false) else { return }
        act(.focusAndOptions(index))
    }

    private func tap(at point: CGPoint, _ m: XmbMetrics) {
        if state.dialog != nil { return }
        // The corner answers as its hint would, shown or not: Back with something open, else the options.
        if m.inCorner(point) { return act(cornerBack ? .back : .options) }
        if let panel = state.panel {
            guard point.x >= m.panelLeft else { return act(.back) }
            if case .range = panel { return act(.rangeAt(m.rangeFraction(x: point.x))) }
            let row = m.panelRow(y: point.y, focus: panel.focus)
            if (0 ..< panel.rowCount).contains(row) { act(.panelRow(row)) }
            return
        }
        // The category bar answers at any depth; a folder open there closes. It is drawn shifted while one is open.
        if abs(point.y - m.catY) < 0.7 * m.catIcon {
            let shift = state.depth > 0 ? m.levelShift : 0
            let index = Int(((point.x + shift - m.focusX) / m.catSpacing + Double(state.column)).rounded())
            if state.columns.indices.contains(index) { act(.column(index)) }
            return
        }
        if state.depth > 0 {
            guard point.x >= m.focusX - 40 * m.u else { return act(.back) }
        }
        guard let items = state.items(atDepth: state.depth) else { return }
        let focus = state.focusIndex(atDepth: state.depth)
        guard let index = m.hitItem(y: point.y, count: items.count, focus: focus, inList: state.depth > 0) else { return }
        act(index == focus ? .confirm : .focus(index))
    }
}

extension XmbAction {
    /// The pad as the console reads it. Triangle is the router's favourite button and opens options; Square is its
    /// menu button and opens a game's settings. L1 and R1 page. A D-pad corner sends both axes, as Android's does, and
    /// the model picks which one counts. Start and Select are the keyboard's alone, and move nothing here.
    init(_ command: MenuControllerCommand) {
        switch command {
        case .up: self = .move(dx: 0, dy: -1)
        case .down: self = .move(dx: 0, dy: 1)
        case .upLeft: self = .move(dx: -1, dy: -1)
        case .upRight: self = .move(dx: 1, dy: -1)
        case .downLeft: self = .move(dx: -1, dy: 1)
        case .downRight: self = .move(dx: 1, dy: 1)
        case .start, .select: self = .move(dx: 0, dy: 0)
        case .left: self = .move(dx: -1, dy: 0)
        case .right: self = .move(dx: 1, dy: 0)
        case .activate: self = .confirm
        case .back: self = .back
        case .toggleFavorite: self = .options
        case .showContextMenu: self = .square
        case .previousTab: self = .page(-1)
        case .nextTab: self = .page(1)
        }
    }
}

extension View {
    /// The XMB's only text effect: a soft black drop shadow.
    func xmbShadow() -> some View { modifier(XmbShadow()) }

    /// A touch target 30 points taller than what is drawn, which leaves the layout as it was.
    func xmbTouchTarget(above: CGFloat = 15, below: CGFloat = 15) -> some View {
        padding(.top, above).padding(.bottom, below).contentShape(Rectangle()).padding(.top, -above).padding(.bottom, -below)
    }
}

extension String {
    /// A hint's word for VoiceOver, without the pad glyph or the firmware's leading colon.
    var xmbHintWord: String { String(drop { !$0.isLetter }) }
}

/// The dark glass under the text panel and the photo viewer, solid when less transparency or more contrast is asked for.
struct XmbPanelGround: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        Color(red: 0.016, green: 0.016, blue: 0.031, opacity: reduceTransparency || contrast == .increased ? 1 : 0.72)
    }
}

/// Android's text shadow is 2 pixels down with a 6 pixel blur, in pixels rather than points.
private struct XmbShadow: ViewModifier {
    @Environment(\.displayScale) private var scale

    func body(content: Content) -> some View {
        content.shadow(color: .black.opacity(0.6), radius: 6 / scale, x: 0, y: 2 / scale)
    }
}

/// One value tweened on its own, for a fade that runs on a different clock from what it fades.
private struct XmbTween<Content: View>: View, Animatable {
    var value: Double
    @ViewBuilder let content: (Double) -> Content

    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View { content(value) }
}

/// A button hint, the glyph in its colour and the word after it, on a dark chip.
struct XmbHintChip: View {
    /// Circle in red, else Triangle in green.
    let circle: Bool
    let label: String
    let firmware: XmbFirmware
    let m: XmbMetrics
    let tap: () -> Void

    var body: some View {
        Button(action: tap) {
            HStack(spacing: 0) {
                Text(verbatim: circle ? "\u{25CB}" : "\u{25B3}")
                    .font(.system(size: 15.95 * m.u))
                    .foregroundStyle(circle ? Color(red: 0.91, green: 0.36, blue: 0.36) : Color(red: 0.36, green: 0.84, blue: 0.54))
                Text(verbatim: " " + label)
                    .font(firmware.font(size: 16.675 * m.u))
                    .foregroundStyle(.white)
                    .xmbShadow()
            }
            .lineLimit(1)
            .padding(.horizontal, 10 * m.u)
            .padding(.vertical, 4 * m.u)
            .background(.black.opacity(0.32), in: RoundedRectangle(cornerRadius: 3 * m.u))
            .overlay(RoundedRectangle(cornerRadius: 3 * m.u).strokeBorder(.white.opacity(0.16), lineWidth: 1))
            .xmbTouchTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label.xmbHintWord))
    }
}

// MARK: - The category bar and the columns

/// Everything that moves with a column change, a focus move or a folder opening, drawn from one interpolated value
/// each, as Android draws it: no item is animated on its own.
private struct XmbStage: View, Animatable {
    let state: XmbState
    let firmware: XmbFirmware
    let m: XmbMetrics
    var colAnim: Double
    var focusAnim: Double
    var level: Double
    /// 1 inside a folder: the labels and the other items' names go, and the column dims, on the 180 ms tween.
    let fade: Double

    var animatableData: AnimatablePair<Double, AnimatablePair<Double, Double>> {
        get { AnimatablePair(colAnim, AnimatablePair(focusAnim, level)) }
        set {
            colAnim = newValue.first
            focusAnim = newValue.second.first
            level = newValue.second.second
        }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(state.columns.enumerated()), id: \.element.id) { index, column in
                category(column, index)
            }
            ForEach(Array(state.columns.enumerated()), id: \.element.id) { index, column in
                if abs(Double(index) - colAnim) < 1 {
                    items(column, index)
                }
            }
        }
        .offset(x: -m.levelShift * level)
        .frame(width: m.width, height: m.height, alignment: .topLeading)
    }

    private func category(_ column: XmbColumn, _ index: Int) -> some View {
        let x = m.columnX(index, colAnim: colAnim)
        let near = XmbMetrics.near(Double(index) - colAnim)
        let scale = (50 + 30 * near) / 80
        let alpha = (0.78 + 0.22 * near) * (1 - level * (1 - near))
        let labelAlpha = min(max(1 - 2 * abs(Double(index) - colAnim), 0), 1) * (1 - fade)
        return ZStack(alignment: .topLeading) {
            XmbIconView(icon: column.icon, firmware: firmware, solid: near)
                .frame(width: m.catIcon, height: m.catIcon)
                .scaleEffect(scale)
                .opacity(alpha)
                .position(x: x, y: m.catY)
            if labelAlpha > 0 {
                Text(column.title)
                    .font(firmware.font(size: m.labelFont))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .xmbShadow()
                    .frame(width: 240 * m.u)
                    .offset(x: x - 120 * m.u, y: m.labelY - 0.7 * m.labelFont)
                    .opacity(labelAlpha)
            }
        }
    }

    @ViewBuilder private func items(_ column: XmbColumn, _ index: Int) -> some View {
        let isCurrent = index == state.column
        let focusInt = state.focus(of: column)
        let f = isCurrent ? focusAnim : Double(focusInt)
        let x = m.columnX(index, colAnim: colAnim)
        let columnAlpha = XmbMetrics.near(Double(index) - colAnim)
        let lower = max(0, Int(f.rounded(.down)) - 4), upper = min(column.items.count - 1, Int(f.rounded(.up)) + m.rowsBelow(12))
        if lower <= upper {
            ForEach(lower ... upper, id: \.self) { i in
                item(column.items[i], d: Double(i) - f, x: x, focused: i == focusInt && isCurrent, columnAlpha: columnAlpha)
            }
        }
    }

    private func item(_ node: XmbNode, d: Double, x: CGFloat, focused: Bool, columnAlpha: Double) -> some View {
        let near = XmbMetrics.near(d)
        let box = node.isGame ? m.gameIcon : CGSize(width: m.squareIcon, height: m.squareIcon)
        let small = node.isGame ? m.gameIconSmall : m.squareIconSmall
        let scale = small + (1 - small) * near
        let depthAlpha = focused ? 1 : 1 - 0.55 * fade
        let alpha = columnAlpha * depthAlpha * XmbMetrics.far(d)
        let y = m.itemY(d)
        let showsText = focused || !node.isGame
        let font = focused ? m.titleFont : m.smallTitleFont
        let lineHeight = 1.25 * font
        let textX = x + box.width * scale / 2 + m.textGap
        return ZStack(alignment: .topLeading) {
            XmbGameIcon(node: node, firmware: firmware, solid: near)
                .frame(width: box.width, height: box.height)
                .scaleEffect(scale)
                .position(x: x, y: y)
                .opacity(alpha)
            if showsText {
                VStack(alignment: .leading, spacing: 0) {
                    Text(node.title)
                        .font(firmware.font(size: font))
                        .foregroundStyle(.white)
                    if focused, let subtitle = node.subtitle {
                        Text(subtitle)
                            .font(firmware.font(size: m.subtitleFont))
                            .foregroundStyle(.white.opacity(0.92))
                    }
                }
                .lineLimit(1)
                .xmbShadow()
                .frame(width: m.textMaxWidth, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .offset(x: textX, y: y - 0.6 * lineHeight)
                .opacity(alpha * (1 - fade) * (focused ? 1 : 0.82))
            }
            if focused, node.isGame, let badge = firmware.image(XmbRco.game, "item_tex_ps3format") {
                Image(uiImage: badge)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 52 * m.u, height: 14 * m.u)
                    .position(x: m.focusX + 411 * m.u + 26 * m.u, y: m.focusedY + 6 * m.u + 7 * m.u)
                    .opacity(columnAlpha * (1 - fade))
            }
        }
    }
}

// MARK: - A folder's list

private struct XmbSubList: View, Animatable {
    /// The settings screens' red, lightened to read on the XMB's colours.
    static let notDefaultRed = Color(red: 1, green: 0x8A / 255, blue: 0x80 / 255)

    let items: [XmbNode]
    let firmware: XmbFirmware
    let m: XmbMetrics
    var focusAnim: Double
    /// 1 while a choice panel is open: only the focused row keeps its name.
    var quiet: Double

    var animatableData: AnimatablePair<Double, Double> {
        get { AnimatablePair(focusAnim, quiet) }
        set {
            focusAnim = newValue.first
            quiet = newValue.second
        }
    }

    var body: some View {
        let focusInt = Int(focusAnim.rounded())
        let lower = max(0, Int(focusAnim.rounded(.down)) - 6), upper = min(items.count - 1, Int(focusAnim.rounded(.up)) + m.rowsBelow(10))
        ZStack(alignment: .topLeading) {
            Text(verbatim: "\u{25C2}")
                .font(firmware.font(size: m.smallTitleFont))
                .foregroundStyle(.white.opacity(0.85))
                .xmbShadow()
                .offset(x: m.focusX - 58 * m.u, y: m.focusedY - 0.7 * 19 * m.u)
            if lower <= upper {
                ForEach(lower ... upper, id: \.self) { i in
                    row(items[i], d: Double(i) - focusAnim, focused: i == focusInt)
                }
            }
        }
        .frame(width: m.width, height: m.height, alignment: .topLeading)
    }

    private func row(_ node: XmbNode, d: Double, focused: Bool) -> some View {
        let near = XmbMetrics.near(d)
        let y = m.itemY(d)
        let font = focused ? m.titleFont : m.smallTitleFont
        let textAlpha = focused ? 1 : 0.75 * (1 - quiet)
        return ZStack(alignment: .topLeading) {
            XmbIconView(icon: node.icon, firmware: firmware, solid: near)
                .frame(width: m.childIcon, height: m.childIcon)
                .scaleEffect(1 + 0.35 * near)
                .position(x: m.focusX, y: y)
                .opacity(XmbMetrics.far(d) * (0.7 + 0.3 * near))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    Text(node.title)
                        .font(firmware.font(size: font))
                        .foregroundStyle(.white)
                        .layoutPriority(1)
                    // Changed from its default, marked as the settings screens mark it.
                    if node.changed {
                        Text(verbatim: "  " + String(localized: "Not default"))
                            .font(firmware.font(size: 0.85 * m.subtitleFont))
                            .fontWeight(.bold)
                            .foregroundStyle(Self.notDefaultRed)
                    }
                }
                .lineLimit(1)
                .xmbShadow()
                if focused {
                    // A setting's value, then its full explanation.
                    let line = [node.subtitle, node.description].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "   ")
                    if !line.isEmpty {
                        Text(line)
                            .font(firmware.font(size: m.subtitleFont))
                            .foregroundStyle(.white.opacity(0.92))
                            .lineLimit(2)
                            .xmbShadow()
                    }
                    if let meter = node.meter {
                        Capsule()
                            .fill(.white.opacity(0.22))
                            .overlay(alignment: .leading) {
                                GeometryReader { geometry in
                                    Capsule().fill(.white).frame(width: geometry.size.width * min(max(meter, 0), 1))
                                }
                            }
                            .frame(width: 240 * m.u, height: 6 * m.u)
                            .padding(.top, 6 * m.u)
                    }
                }
            }
            .frame(width: max(m.panelLeft - m.childTextX - 20 * m.u, 120), alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .offset(x: m.childTextX, y: y - 0.6 * 1.25 * font)
            .opacity(XmbMetrics.far(d) * textAlpha)
        }
    }
}

// MARK: - The side panel

private struct XmbSidePanel: View, Animatable {
    let panel: XmbState.Panel
    /// A colour beside each choice, as Theme Settings' Color shows its colours.
    let swatches: [SIMD3<Float>]?
    let firmware: XmbFirmware
    let m: XmbMetrics
    var focusAnim: Double

    var animatableData: Double {
        get { focusAnim }
        set { focusAnim = newValue }
    }

    private var rows: [String] {
        switch panel {
        case .options(_, let rows, _): return rows.map(\.title)
        case .editor(_, let choices, _): return choices
        case .range: return []
        }
    }

    var body: some View {
        let width = m.width - m.panelLeft
        ZStack(alignment: .topLeading) {
            LinearGradient(stops: [.init(color: .white.opacity(0.05), location: 0), .init(color: .white.opacity(0.16), location: 0.5),
                                   .init(color: .white.opacity(0.10), location: 1)],
                           startPoint: .leading, endPoint: .trailing)
            Rectangle().fill(.white.opacity(0.28)).frame(width: 1)
            if case .range(_, let range) = panel {
                VStack(alignment: .leading, spacing: 10 * m.u) {
                    Text(verbatim: range.text)
                        .font(firmware.font(size: 0.9 * m.titleFont))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .xmbShadow()
                    Capsule()
                        .fill(.white.opacity(0.25))
                        .overlay(alignment: .leading) {
                            GeometryReader { geometry in
                                Capsule().fill(.white).frame(width: geometry.size.width * range.fraction)
                            }
                        }
                        .frame(height: 5 * m.u)
                }
                .frame(width: max(m.rangeRight - m.panelLeft - 28 * m.u, 60), alignment: .leading)
                .offset(x: 28 * m.u, y: m.focusedY - 34 * m.u)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { k, title in
                let y = m.focusedY + CGFloat(Double(k) - focusAnim) * m.panelRow
                let focused = k == panel.focus
                if y >= m.catY + m.catIcon, y <= m.height - m.panelRow {
                    HStack(spacing: 10 * m.u) {
                        if let swatches, swatches.indices.contains(k) {
                            let swatch = swatches[k]
                            RoundedRectangle(cornerRadius: 2 * m.u)
                                .fill(Color(red: Double(swatch.x), green: Double(swatch.y), blue: Double(swatch.z)))
                                .overlay(RoundedRectangle(cornerRadius: 2 * m.u).strokeBorder(.white.opacity(focused ? 0.9 : 0.35), lineWidth: 1))
                                .frame(width: 16 * m.u, height: 16 * m.u)
                        }
                        Text(title)
                            .font(firmware.font(size: 19 * m.u))
                            .foregroundStyle(.white.opacity(focused ? 1 : 0.62))
                            .lineLimit(1)
                            .xmbShadow()
                    }
                    .padding(.horizontal, 8 * m.u)
                    .frame(width: max(m.panelRowRight - m.panelLeft - 24 * m.u, 60), height: m.panelRow, alignment: .leading)
                    .background(focused ? Color.white.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 3 * m.u))
                    .offset(x: 24 * m.u, y: y - m.panelRow / 2)
                }
            }
        }
        .frame(width: width, height: m.height, alignment: .topLeading)
        .offset(x: m.panelLeft)
    }
}

// MARK: - The dialog

private struct XmbDialogView: View {
    let dialog: XmbDialog
    let firmware: XmbFirmware
    let m: XmbMetrics
    let act: (XmbAction) -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.3)
                .contentShape(Rectangle())
                .onTapGesture { act(.back) }
            VStack(spacing: 30 * m.u) {
                Text(dialog.message)
                    .font(firmware.font(size: 18.9 * m.u))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .xmbShadow()
                HStack(spacing: 36 * m.u) {
                    ForEach(Array(dialog.buttons.enumerated()), id: \.offset) { index, title in
                        let focused = index == dialog.focus
                        Button { act(.panelRow(index)) } label: {
                            Text(title)
                                .font(firmware.font(size: 19 * m.u))
                                .foregroundStyle(.white.opacity(focused ? 1 : 0.75))
                                .padding(.horizontal, 26 * m.u)
                                .padding(.vertical, 9 * m.u)
                                .frame(minWidth: 160 * m.u)
                                .background(.white.opacity(focused ? 0.24 : 0.05), in: RoundedRectangle(cornerRadius: 4 * m.u))
                                .overlay(RoundedRectangle(cornerRadius: 4 * m.u).strokeBorder(.white.opacity(focused ? 0.7 : 0.18), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.vertical, 34 * m.u)
            .padding(.horizontal, 80 * m.u)
            .frame(maxWidth: .infinity)
            .background {
                LinearGradient(colors: [Color(white: 0.063).opacity(0.9), Color(white: 0.024).opacity(0.95), Color(white: 0.063).opacity(0.9)],
                               startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .top) { Rectangle().fill(.white.opacity(0.32)).frame(height: 1) }
            .overlay(alignment: .bottom) { Rectangle().fill(.white.opacity(0.32)).frame(height: 1) }
        }
    }
}

// MARK: - The clock

/// A row's date and time as Android writes it: the date in the device's order, a space, then H:mm or h:mm a.
@MainActor enum XmbStamp {
    // A rebuild formats every played game, so the formatters are kept, and made again when the region or 24-Hour Time
    // changes so the change shows without a restart.
    private static var formatters: (date: DateFormatter, time: DateFormatter)?
    private static let localeWatch = NotificationCenter.default.addObserver(
        forName: NSLocale.currentLocaleDidChangeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { formatters = nil }
        }

    static func string(_ value: Date) -> String {
        _ = localeWatch
        let made = formatters ?? {
            let date = DateFormatter()
            date.setLocalizedDateFormatFromTemplate("yMd")
            let time = DateFormatter()
            let hours24 = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current)?.contains("H") ?? false
            time.dateFormat = hours24 ? "H:mm" : "h:mm a"
            return (date, time)
        }()
        formatters = made
        return made.date.string(from: value) + " " + made.time.string(from: value)
    }
}

private struct XmbClock: View {
    let firmware: XmbFirmware
    let m: XmbMetrics
    let avatar: UIImage?
    @State private var battery: (level: Double, charging: Bool)?

    var body: some View {
        let left = min(887 * m.u, m.width - 300 * m.u)
        let shape = UnevenRoundedRectangle(topLeadingRadius: 4 * m.u, bottomLeadingRadius: 4 * m.u)
        TimelineView(.periodic(from: .now, by: XmbTiming.clock)) { context in
            HStack(spacing: 16 * m.u) {
                if let avatar {
                    Image(uiImage: avatar)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 27 * m.u, height: 27 * m.u)
                        .clipShape(RoundedRectangle(cornerRadius: 4 * m.u))
                        .overlay(RoundedRectangle(cornerRadius: 4 * m.u).strokeBorder(.white.opacity(0.6), lineWidth: 1))
                        .padding(.trailing, -4 * m.u)
                }
                Text(Self.format(context.date))
                    .font(firmware.font(size: 21 * m.u))
                if let battery {
                    HStack(spacing: 6 * m.u) {
                        XmbBatteryGlyph(level: battery.level, charging: battery.charging)
                            .frame(width: 28 * m.u, height: 14 * m.u)
                        Text(verbatim: "\(Int((battery.level * 100).rounded()))%")
                            .font(firmware.font(size: 18.4 * m.u))
                    }
                }
            }
            .foregroundStyle(.white)
            .lineLimit(1)
            .xmbShadow()
            .padding(.trailing, 60 * m.u)
            .frame(width: m.width - left, height: 37 * m.u, alignment: .trailing)
            .background(LinearGradient(colors: [.white.opacity(0.04), .white.opacity(0.08)], startPoint: .leading, endPoint: .trailing),
                        in: shape)
            .overlay(shape.strokeBorder(.white.opacity(0.34), lineWidth: 1))
        }
        .offset(x: left, y: 50 * m.u)
        .accessibilityHidden(true)
        .task {
            while !Task.isCancelled {
                battery = Self.battery()
                try? await Task.sleep(for: .seconds(XmbTiming.battery))
            }
        }
    }

    /// The day and month in the device's order, then the time in its 12 or 24-hour style.
    static func format(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("dM")
        return formatter.string(from: date) + " " + date.formatted(date: .omitted, time: .shortened)
    }

    @MainActor static func battery() -> (level: Double, charging: Bool)? {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        guard device.batteryLevel >= 0 else { return nil }
        return (Double(device.batteryLevel), device.batteryState == .charging || device.batteryState == .full)
    }
}

/// The battery as Android draws it: an outline filled to its level, and a dark bolt with a white edge while charging.
private struct XmbBatteryGlyph: View {
    let level: Double
    let charging: Bool

    var body: some View {
        Canvas { context, size in
            let nub = size.width * 0.09
            let body = CGSize(width: size.width - nub, height: size.height)
            let stroke = size.height * 0.12
            context.stroke(Path(roundedRect: CGRect(origin: .zero, size: body), cornerRadius: size.height * 0.22),
                           with: .color(.white), lineWidth: stroke)
            context.fill(Path(CGRect(x: body.width, y: size.height * 0.3, width: nub, height: size.height * 0.4)), with: .color(.white))
            let inset = stroke * 1.8
            let fill = min(max(level, 0), 1) * (body.width - inset * 2)
            let low = level <= 0.15 && !charging
            context.fill(Path(roundedRect: CGRect(x: inset, y: inset, width: max(fill, 0), height: body.height - inset * 2),
                              cornerRadius: size.height * 0.08),
                         with: .color(low ? Color(red: 1, green: 0x6B / 255, blue: 0x5E / 255) : .white))
            if charging {
                let w = body.width, h = body.height
                var bolt = Path()
                bolt.move(to: CGPoint(x: w * 0.55, y: h * 0.08))
                bolt.addLine(to: CGPoint(x: w * 0.32, y: h * 0.56))
                bolt.addLine(to: CGPoint(x: w * 0.5, y: h * 0.56))
                bolt.addLine(to: CGPoint(x: w * 0.42, y: h * 0.92))
                bolt.addLine(to: CGPoint(x: w * 0.7, y: h * 0.42))
                bolt.addLine(to: CGPoint(x: w * 0.52, y: h * 0.42))
                bolt.closeSubpath()
                context.fill(bolt, with: .color(Color(white: 0x2A / 255)))
                context.stroke(bolt, with: .color(.white), lineWidth: size.height * 0.06)
            }
        }
    }
}

// MARK: - Icons and pictures

/// A column item's icon: a game's moving ICON1 while the focus rests on it, otherwise the icon it has.
private struct XmbGameIcon: View {
    let node: XmbNode
    let firmware: XmbFirmware
    let solid: Double

    var body: some View {
        if node.isGame, let frame = XmbGameMedia.shared.animatedIcon(node.id) {
            Image(decorative: frame, scale: 1).resizable().aspectRatio(contentMode: .fit)
        } else {
            XmbIconView(icon: node.icon, firmware: firmware, solid: solid)
        }
    }
}

struct XmbIconView: View {
    let icon: XmbIcon
    let firmware: XmbFirmware
    /// 1 on the focus: the firmware's icons are translucent, and the focused one is drawn a second time on top.
    var solid: Double = 0

    var body: some View {
        switch icon {
        case .firmware(let rco, let name, let symbol):
            if let image = firmware.image(rco, name) {
                ZStack {
                    Image(uiImage: image).resizable().scaledToFit()
                    Image(uiImage: image).resizable().scaledToFit().opacity(solid)
                }
                .shadow(color: .black.opacity(0.39), radius: 2, x: 0, y: 1)
            } else {
                symbolView(Image(systemName: symbol))
            }
        case .game(let path):
            if let path {
                XmbPicture(path: path, fit: true, icon: true) { defaultGame }
            } else {
                defaultGame
            }
        case .symbol(let symbol):
            symbolView(Image(systemName: symbol))
        case .asset(let name):
            symbolView(Image(name))
        case .badge:
            XmbBadge()
        case .mark:
            mark
        case .remote(let url):
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFit()
                } else {
                    mark
                }
            }
        case .none:
            Color.clear
        }
    }

    private var mark: some View {
        GeometryReader { geometry in
            let height = geometry.size.height * 0.56
            XmbMark()
                .fill(LinearGradient(colors: [.white, Color(white: 0.85)], startPoint: .top, endPoint: .bottom))
                .frame(width: XmbMark.width * height, height: height)
                .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .xmbShadow()
    }

    @ViewBuilder private var defaultGame: some View {
        if let image = firmware.image(XmbRco.game, "game_tex_default_ps3") {
            Image(uiImage: image).resizable().scaledToFit()
        } else {
            symbolView(Image(systemName: "opticaldisc"))
        }
    }

    private func symbolView(_ image: Image) -> some View {
        GeometryReader { geometry in
            image
                .resizable()
                .scaledToFit()
                .fontWeight(.light)
                .foregroundStyle(.white.opacity(0.78 + 0.22 * solid))
                .padding(geometry.size.height * 0.16)
                .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .xmbShadow()
    }
}

/// The ARMSX3 logo's "3", as Android's XmbWordmark.kt draws it: three bars into an upright with two rounded bowls
/// and a notch where they meet. One unit is the mark's height.
struct XmbMark: Shape {
    static let width: CGFloat = 1.5

    func path(in rect: CGRect) -> Path {
        let h = rect.height, w = Self.width
        let t: CGFloat = 0.25, ro: CGFloat = 0.38, notch: CGFloat = 0.10, ri: CGFloat = 0.035
        let rc = min(ro - t, 0.5 - 1.5 * t - ri)
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * h, y: rect.minY + y * h) }
        var path = Path()
        // Degrees with 90 pointing down; a positive sweep runs clockwise on screen.
        func arc(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ start: Double, _ sweep: Double) {
            path.addArc(center: point(cx, cy), radius: r * h, startAngle: .degrees(start), endAngle: .degrees(start + sweep),
                        clockwise: sweep < 0)
        }
        path.move(to: point(0, 0))
        path.addLine(to: point(w - ro, 0))
        arc(w - ro, ro, ro, 270, 90)
        path.addLine(to: point(w, 0.5 - notch))
        arc(w - notch, 0.5 - notch, notch, 0, 90)
        arc(w - notch, 0.5 + notch, notch, 270, 90)
        path.addLine(to: point(w, 1 - ro))
        arc(w - ro, 1 - ro, ro, 0, 90)
        path.addLine(to: point(0, 1))
        path.addLine(to: point(0, 1 - t))
        path.addLine(to: point(w - t - rc, 1 - t))
        arc(w - t - rc, 1 - t - rc, rc, 90, -90)
        path.addLine(to: point(w - t, 0.5 + t / 2 + ri))
        arc(w - t - ri, 0.5 + t / 2 + ri, ri, 0, -90)
        path.addLine(to: point(0, 0.5 + t / 2))
        path.addLine(to: point(0, 0.5 - t / 2))
        path.addLine(to: point(w - t - ri, 0.5 - t / 2))
        arc(w - t - ri, 0.5 - t / 2 - ri, ri, 90, -90)
        path.addLine(to: point(w - t, t + rc))
        arc(w - t - rc, t + rc, rc, 0, -90)
        path.addLine(to: point(0, t))
        path.closeSubpath()
        return path
    }
}

// Held by decoded size too: a PIC1 is 8 MiB, and the cache lives on into a game.
private let pictureCache: NSCache<NSString, UIImage> = {
    let cache = NSCache<NSString, UIImage>()
    cache.countLimit = 120
    cache.totalCostLimit = 64 << 20
    return cache
}()

/// A picture file at no more than `side` pixels on its longer side, for a row's icon.
private func xmbThumbnail(_ path: String, side: Int) -> UIImage? {
    let options = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                   kCGImageSourceThumbnailMaxPixelSize: side] as CFDictionary
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
    return UIImage(cgImage: image)
}

/// A game's ICON0 (fitted) or PIC1 (filled) from a local file, decoded off the main thread and cached; `missing`
/// stands in when the file is not there or is not a picture. An icon decodes small, so a screenshot in a row is not
/// held at its full size.
struct XmbPicture<Missing: View>: View {
    let path: String
    let fit: Bool
    var icon = false
    @ViewBuilder var missing: () -> Missing
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                if fit {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    GeometryReader { geometry in
                        Image(uiImage: image).resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                            .clipped()
                    }
                }
            } else if failed {
                missing()
            }
        }
        .task(id: path) {
            failed = false
            let key = ((icon ? "icon:" : "") + path) as NSString
            if let hit = pictureCache.object(forKey: key) {
                image = hit
                return
            }
            let target = path
            let decoded = await Task.detached(priority: .utility) { [icon] in
                // ICON0 is 320 wide, so 512 keeps it whole.
                icon ? xmbThumbnail(target, side: 512) : UIImage(contentsOfFile: target)?.preparingForDisplay()
            }.value
            if let decoded {
                pictureCache.setObject(decoded, forKey: key, cost: decoded.cgImage.map { $0.bytesPerRow * $0.height } ?? 0)
            }
            image = decoded
            failed = decoded == nil
        }
    }
}

/// The ARMSX3 mark on a glossy purple disc, made the way the console's round PlayStation Network badge is.
struct XmbBadge: View {
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let r = side * 0.46
            let height = r * 0.95
            ZStack {
                Circle()
                    .fill(RadialGradient(stops: [.init(color: Color(red: 0.70, green: 0.55, blue: 1), location: 0),
                                                 .init(color: Color(red: 0.42, green: 0.18, blue: 0.84), location: 0.55),
                                                 .init(color: Color(red: 0.23, green: 0.06, blue: 0.53), location: 1)],
                                         center: UnitPoint(x: 0.5, y: 0.5 - 0.35 * 0.46), startRadius: 0, endRadius: r * 1.25))
                Ellipse()
                    .fill(LinearGradient(colors: [.white.opacity(0.44), .white.opacity(0)], startPoint: .top, endPoint: .bottom))
                    .frame(width: r * 1.64, height: r * 1.04)
                    .offset(y: -r * 0.42)
                Circle().strokeBorder(.white.opacity(0.56), lineWidth: side * 0.012)
                XmbMark()
                    .fill(LinearGradient(colors: [.white, Color(red: 0.90, green: 0.88, blue: 0.96)], startPoint: .top, endPoint: .bottom))
                    .frame(width: XmbMark.width * height, height: height)
            }
            .frame(width: r * 2, height: r * 2)
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
    }
}
