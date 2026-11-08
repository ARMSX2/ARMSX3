// SPDX-License-Identifier: GPL-2.0-only
// The PS3 touch pad. One UIKit multi-touch view takes every finger, because a SwiftUI gesture cannot slide.

import SwiftUI
import UIKit

struct PadControl {
    let button: Rpcs3PadButton
    // The word ARMSX3_SMOKE_PRESS uses for this button in the simulator smoke scene.
    let name: String
    // Drawn when the image is missing.
    let label: String
    // Empty for PS, which the skin does not draw.
    let art: String
}

// Each image falls back to the bundled default's (SkinStore.image(named:)).
enum PadArt {
    @MainActor static func image(_ name: String) -> UIImage? {
        SkinStore.shared.image(named: name)
    }
}

struct VirtualControllerView: View {
    static let table: [PadControl] = [
        PadControl(button: .up, name: "up", label: "▲", art: "ic_controller_up_button"),
        PadControl(button: .down, name: "down", label: "▼", art: "ic_controller_down_button"),
        PadControl(button: .left, name: "left", label: "◀", art: "ic_controller_left_button"),
        PadControl(button: .right, name: "right", label: "▶", art: "ic_controller_right_button"),
        PadControl(button: .cross, name: "cross", label: "✕", art: "ic_controller_cross_button"),
        PadControl(button: .circle, name: "circle", label: "○", art: "ic_controller_circle_button"),
        PadControl(button: .square, name: "square", label: "□", art: "ic_controller_square_button"),
        PadControl(button: .triangle, name: "triangle", label: "△", art: "ic_controller_triangle_button"),
        PadControl(button: .L1, name: "l1", label: "L1", art: "ic_controller_l1_button"),
        PadControl(button: .R1, name: "r1", label: "R1", art: "ic_controller_r1_button"),
        PadControl(button: .L2, name: "l2", label: "L2", art: "ic_controller_l2_button"),
        PadControl(button: .R2, name: "r2", label: "R2", art: "ic_controller_r2_button"),
        PadControl(button: .start, name: "start", label: "START", art: "ic_controller_start_button"),
        PadControl(button: .select, name: "select", label: "SELECT", art: "ic_controller_select_button"),
        PadControl(button: .L3, name: "l3", label: "L3", art: "ic_controller_l3_button"),
        PadControl(button: .R3, name: "r3", label: "R3", art: "ic_controller_r3_button"),
        PadControl(button: .PS, name: "ps", label: "PS", art: ""),
    ]

    // The sticks' art, which `table` does not carry; SkinStore.imageNames reads both.
    static let stickBaseArt = "ic_controller_analog_base"
    static let stickKnobArt = "ic_controller_analog_stick"

    static func control(_ button: Rpcs3PadButton) -> PadControl {
        table.first { $0.button == button }!
    }

    static func button(named name: String) -> Rpcs3PadButton? {
        table.first { $0.name == name }?.button
    }

    // A touch and the smoke hook both land here.
    static func setButton(_ button: Rpcs3PadButton, pressed: Bool) {
        PadSnapshot.shared.set(button, pressed: pressed)
        PadSnapshot.shared.send(source: .touch)
    }

    // A tap: down, 150 ms, up. The stick click and the smoke scene's scripted press use it.
    static func press(_ button: Rpcs3PadButton) {
        setButton(button, pressed: true)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 150_000_000)
            setButton(button, pressed: false)
        }
    }

    static let clusters: [(rule: PadHitRule, buttons: [Rpcs3PadButton])] = [
        (.eightWay, [.up, .down, .left, .right]),
        (.chord, [.cross, .circle, .square, .triangle]),
        (.nearest, [.L1, .L2, .R1, .R2]),
        (.nearest, [.select, .PS, .start]),
    ]

    // PadTouchSurface fills this coordinate space exactly.
    static let space = "armsx3.pad"

    @Environment(\.scenePhase) private var scenePhase

    private static let face: CGFloat = 50
    // All four shoulders one size; a skin's shoulder art fills that frame, since Graphite's L1 and L2 differ in shape.
    private static let shoulder = CGSize(width: 80, height: 28)
    private static let small = CGSize(width: 58, height: 26)
    // The halt sentence keeps inside this in landscape, so it wraps instead of crossing L1 or R1.
    static let shoulderReach: CGFloat = 16 + shoulder.width * 2 + 10
    @State private var bookFold: ClosedRange<CGFloat>?
    @State private var padWidth: CGFloat = 0

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            HStack(spacing: 10) {
                PadButton(control: Self.control(.L2), size: Self.shoulder)
                PadButton(control: Self.control(.L1), size: Self.shoulder)
                Spacer()
                PadButton(control: Self.control(.R1), size: Self.shoulder)
                PadButton(control: Self.control(.R2), size: Self.shoulder)
            }
            HStack {
                diamond(top: .up, left: .left, right: .right, bottom: .down)
                Spacer()
                diamond(top: .triangle, left: .square, right: .circle, bottom: .cross)
            }
            if let fold = bookFold {
                // In book pose PS and Start go by the right stick, so nothing sits on the fold; 16 is the padding.
                HStack(alignment: .bottom, spacing: 0) {
                    HStack(alignment: .bottom) {
                        PadStick(left: true, click: .L3)
                        Spacer(minLength: 4)
                        PadButton(control: Self.control(.select), size: Self.small)
                    }
                    .frame(width: max(0, fold.lowerBound - 16 - 12))
                    Spacer(minLength: 0)
                    HStack(alignment: .bottom, spacing: 8) {
                        PadButton(control: Self.control(.PS), size: CGSize(width: 40, height: 40))
                        PadButton(control: Self.control(.start), size: Self.small)
                        Spacer(minLength: 4)
                        PadStick(left: false, click: .R3)
                    }
                    .frame(width: max(0, padWidth - fold.upperBound - 16 - 12))
                }
            } else {
                HStack(alignment: .bottom) {
                    PadStick(left: true, click: .L3)
                    Spacer(minLength: 4)
                    HStack(spacing: 8) {
                        PadButton(control: Self.control(.select), size: Self.small)
                        PadButton(control: Self.control(.PS), size: CGSize(width: 40, height: 40))
                        PadButton(control: Self.control(.start), size: Self.small)
                    }
                    Spacer(minLength: 4)
                    PadStick(left: false, click: .R3)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 20)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { padWidth = $0 }
        .onBookFoldChange { bookFold = $0 }
        .coordinateSpace(.named(Self.space))
        .background { PadTouchSurface() }
        .onPreferenceChange(PadArtFrames.self) { frames in
            MainActor.assumeIsolated { PadTouches.shared.layout(frames) }
        }
        // Leaving the active state or hiding the pad lifts every finger, so nothing stays held.
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { PadTouches.shared.liftAll() }
        }
        .onDisappear { PadTouches.shared.liftAll() }
    }

    private func diamond(top: Rpcs3PadButton, left: Rpcs3PadButton, right: Rpcs3PadButton, bottom: Rpcs3PadButton) -> some View {
        let size = CGSize(width: Self.face, height: Self.face)
        // One cross behind four arrows reads as a d-pad and shows a diagonal works; each arrow keeps its own touch frame.
        let cross = top == .up && PadArt.image(Self.control(.up).art) == nil
        return VStack(spacing: 0) {
            PadButton(control: Self.control(top), size: size)
            HStack(spacing: Self.face) {
                PadButton(control: Self.control(left), size: size)
                PadButton(control: Self.control(right), size: size)
            }
            PadButton(control: Self.control(bottom), size: size)
        }
        .background {
            if cross {
                DpadCross().fill(.black.opacity(0.45))
                    .overlay(DpadCross().stroke(.white.opacity(0.7), lineWidth: 1.5))
                    .opacity(SettingsStore.shared.padOpacity)
            }
        }
    }
}

// One instance, because one pad is on screen at a time.
@MainActor @Observable final class PadTouches {
    static let shared = PadTouches()

    // What the press highlight draws.
    private(set) var held: Set<Rpcs3PadButton> = []
    @ObservationIgnored private(set) var frames: [Rpcs3PadButton: CGRect] = [:]
    @ObservationIgnored private var tracker = PadTouchTracker<ObjectIdentifier, Rpcs3PadButton>()
    // shortcut: always on; add a "Touch pad vibration" toggle when settings gain a pad section.
    @ObservationIgnored private let haptic = UIImpactFeedbackGenerator(style: .light)
    @ObservationIgnored private var aiming = Set<ObjectIdentifier>()

    private init() {}

    func layout(_ frames: [Rpcs3PadButton: CGRect]) {
        self.frames = frames
        tracker.clusters = VirtualControllerView.clusters.map { cluster in
            PadCluster(rule: cluster.rule, art: frames.filter { cluster.buttons.contains($0.key) })
        }
    }

    // The generator is readied before the first press.
    func prepare() {
        haptic.prepare()
    }

    // Applied together and sent as one change.
    func touch(began: [(ObjectIdentifier, CGPoint)] = [], moved: [(ObjectIdentifier, CGPoint)] = [],
               lifted: [ObjectIdentifier] = []) {
        let before = tracker.held
        for (finger, point) in began { tracker.down(finger, at: point) }
        for (finger, point) in moved { tracker.move(finger, to: point) }
        for finger in lifted { tracker.lift(finger) }
        aiming.subtract(lifted)
        // The uDraw pen lifts with the last aiming finger.
        if aiming.isEmpty { AimFeed.shared.pen(false, from: .pad) }
        apply(PadHit.change(from: before, to: tracker.held))
    }

    // Only a finger the pad's hit test does not claim aims, from touch-down to lift.
    func aim(_ fingers: [(finger: ObjectIdentifier, pad: CGPoint, window: CGPoint)], began: Bool) {
        guard AimFeed.shared.active else { return }
        for (finger, pad, window) in fingers {
            if began, !claims(pad) { aiming.insert(finger) }
            // A finger that lands on the picture also holds the uDraw pen down.
            if aiming.contains(finger), AimFeed.shared.touch(at: window, imageRect: AimFeed.shared.imageRect) {
                AimFeed.shared.pen(true, from: .pad)
            }
        }
    }

    private func claims(_ point: CGPoint) -> Bool {
        tracker.clusters.contains { PadHit.covers($0, point) }
            || [Rpcs3PadButton.L3, .R3].contains { frames[$0]?.contains(point) ?? false }
    }

    func liftAll() {
        let before = tracker.held
        tracker.liftAll()
        aiming = []
        // And the uDraw pen with them.
        AimFeed.shared.pen(false, from: .pad)
        apply(PadHit.change(from: before, to: tracker.held))
    }

    private func apply(_ change: PadChange<Rpcs3PadButton>) {
        guard !change.isEmpty else { return }
        for button in change.released { PadSnapshot.shared.set(button, pressed: false) }
        for button in change.pressed { PadSnapshot.shared.set(button, pressed: true) }
        PadSnapshot.shared.send(source: .touch)
        held = tracker.held
        if !change.pressed.isEmpty {
            haptic.impactOccurred()
            haptic.prepare()
        }
    }
}

private struct PadArtFrames: PreferenceKey {
    static let defaultValue: [Rpcs3PadButton: CGRect] = [:]

    static func reduce(value: inout [Rpcs3PadButton: CGRect], nextValue: () -> [Rpcs3PadButton: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

private struct PadTouchSurface: UIViewRepresentable {
    func makeUIView(context: Context) -> PadTouchView {
        PadTouchView()
    }

    func updateUIView(_ view: PadTouchView, context: Context) {}
}

final class PadTouchView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isMultipleTouchEnabled = true
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        PadTouches.shared.prepare()
        PadTouches.shared.touch(began: touches.map { (ObjectIdentifier($0), $0.location(in: self)) })
        PadTouches.shared.aim(aimed(touches), began: true)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        PadTouches.shared.touch(moved: touches.map { (ObjectIdentifier($0), $0.location(in: self)) })
        PadTouches.shared.aim(aimed(touches), began: false)
    }

    private func aimed(_ touches: Set<UITouch>) -> [(finger: ObjectIdentifier, pad: CGPoint, window: CGPoint)] {
        touches.map { (ObjectIdentifier($0), $0.location(in: self), $0.location(in: nil)) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        PadTouches.shared.touch(lifted: touches.map { ObjectIdentifier($0) })
    }

    // A cancelled touch is released exactly like a lift, so it cannot leave its button held.
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        PadTouches.shared.touch(lifted: touches.map { ObjectIdentifier($0) })
    }
}

// It takes no touch of its own: PadTouchSurface does.
private struct PadButton: View {
    let control: PadControl
    let size: CGSize

    var body: some View {
        let pressed = PadTouches.shared.held.contains(control.button)
        PadFace(control: control, size: size)
            .frame(width: size.width, height: size.height)
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: PadArtFrames.self,
                                           value: [control.button: geometry.frame(in: .named(VirtualControllerView.space))])
                }
            }
            .opacity(pressed ? 1 : SettingsStore.shared.padOpacity)
            .scaleEffect(pressed ? 0.9 : 1)
            .brightness(pressed ? 0.2 : 0)
            .shadow(color: .white.opacity(pressed ? 0.9 : 0), radius: 10)
            .allowsHitTesting(false)
            .accessibilityLabel(control.name)
            .accessibilityAddTraits(pressed ? .isSelected : [])
    }
}

private struct PadFace: View {
    let control: PadControl
    let size: CGSize

    var body: some View { face }

    @ViewBuilder private var face: some View {
        if let image = PadArt.image(control.art) {
            if Self.shoulders.contains(control.button) {
                // Fills the one shoulder frame, so L1 and L2 match whatever shape the skin drew them.
                Image(uiImage: image).resizable()
            } else {
                Image(uiImage: image).resizable().scaledToFit()
            }
        } else if Self.round.contains(control.button) {
            drawn(Circle())
        } else if Self.dpad.contains(control.button) {
            glyph.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
        } else {
            drawn(Capsule())
        }
    }

    private static let round: Set<Rpcs3PadButton> = [.cross, .circle, .square, .triangle, .PS]
    private static let dpad: Set<Rpcs3PadButton> = [.up, .down, .left, .right]
    private static let shoulders: Set<Rpcs3PadButton> = [.L1, .L2, .R1, .R2]

    // Near-black and see-through rather than a Material, which washes out to flat grey over a game frame.
    private func drawn<S: Shape>(_ shape: S) -> some View {
        shape.fill(.black.opacity(0.45))
            .overlay(shape.stroke(.white.opacity(0.7), lineWidth: 1.5))
            .overlay(glyph)
    }

    @ViewBuilder private var glyph: some View {
        // No PlayStation logo anywhere: the PS button is a plain Home button.
        if let symbol = Self.symbol(control.button) {
            Image(systemName: symbol)
                .font(.system(size: Self.dpad.contains(control.button) || control.button == .PS ? 16 : 20, weight: .bold))
                .foregroundStyle(Self.colour(control.button))
        } else {
            Text(control.label)
                .font(.system(size: control.label.count > 2 ? 9 : 13, weight: .bold))
                .tracking(control.label.count > 2 ? 0.5 : 0)
                .foregroundStyle(.white)
        }
    }

    // Lightened to read on the dark fill.
    private static func colour(_ button: Rpcs3PadButton) -> Color {
        switch button {
        case .triangle: return Color(red: 0.3, green: 0.85, blue: 0.72)
        case .circle: return Color(red: 1, green: 0.42, blue: 0.42)
        case .cross: return Color(red: 0.55, green: 0.68, blue: 1)
        case .square: return Color(red: 1, green: 0.56, blue: 0.86)
        default: return .white
        }
    }

    private static func symbol(_ button: Rpcs3PadButton) -> String? {
        switch button {
        case .up: return "arrowtriangle.up.fill"
        case .down: return "arrowtriangle.down.fill"
        case .left: return "arrowtriangle.left.fill"
        case .right: return "arrowtriangle.right.fill"
        case .triangle: return "triangle"
        case .circle: return "circle"
        case .cross: return "xmark"
        case .square: return "square"
        case .PS: return "house.fill"
        default: return nil
        }
    }
}

private struct DpadCross: Shape {
    func path(in rect: CGRect) -> Path {
        let arm = rect.width / 3
        let upright = CGPath(roundedRect: CGRect(x: arm, y: 0, width: arm, height: rect.height), cornerWidth: 12, cornerHeight: 12,
                             transform: nil)
        let across = CGPath(roundedRect: CGRect(x: 0, y: arm, width: rect.width, height: arm), cornerWidth: 12, cornerHeight: 12,
                            transform: nil)
        return Path(upright.union(across))
    }
}

// 0-255 per axis with 128 centred and up as 0. A tap that barely moves is the stick's click (L3 / R3).
private struct PadStick: View {
    let left: Bool
    let click: Rpcs3PadButton
    private let radius: CGFloat = 52
    private let knob: CGFloat = 52
    @State private var offset: CGSize = .zero
    @State private var travelled: CGFloat = 0
    // SwiftUI resets this on end and on cancellation, which re-centres a stick onEnded alone missed.
    @GestureState private var touching = false

    var body: some View {
        ZStack {
            art(VirtualControllerView.stickBaseArt) {
                Circle().fill(.black.opacity(0.3))
                    .overlay(Circle().stroke(.white.opacity(0.5), lineWidth: 1.5))
            }
            art(VirtualControllerView.stickKnobArt) {
                Circle().fill(.black.opacity(0.45))
                    .overlay(Circle().stroke(.white.opacity(0.8), lineWidth: 1.5))
            }
                .frame(width: knob, height: knob)
                .offset(offset)
        }
        .frame(width: radius * 2, height: radius * 2)
        .opacity(touching ? 1 : SettingsStore.shared.padOpacity)
        // The stick's frame, so a finger on it never aims. No cluster names L3 or R3.
        .background {
            GeometryReader { geometry in
                Color.clear.preference(key: PadArtFrames.self, value: [click: geometry.frame(in: .named(VirtualControllerView.space))])
            }
        }
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .updating($touching) { _, touching, _ in touching = true }
                .onChanged { value in
                    let length = hypot(value.translation.width, value.translation.height)
                    travelled = max(travelled, length)
                    let scale = length > radius ? radius / length : 1
                    offset = CGSize(width: value.translation.width * scale, height: value.translation.height * scale)
                    send(offset)
                }
                .onEnded { _ in
                    offset = .zero
                    send(.zero)
                    if travelled < 8 { VirtualControllerView.press(click) }
                    travelled = 0
                }
        )
        .onChange(of: touching) { _, down in
            // A new touch starts its own click measure; a reset (end or cancel) centres the stick.
            if down {
                travelled = 0
            } else {
                offset = .zero
                send(.zero)
            }
        }
        .accessibilityLabel(left ? "left stick" : "right stick")
    }

    @ViewBuilder private func art<Fallback: View>(_ name: String, @ViewBuilder fallback: () -> Fallback) -> some View {
        if let image = PadArt.image(name) {
            Image(uiImage: image).resizable().scaledToFit()
        } else {
            fallback()
        }
    }

    private func axis(_ value: CGFloat) -> UInt8 {
        UInt8(clamping: Int((CGFloat(PadSnapshot.centre) + value / radius * 127).rounded()))
    }

    private func send(_ offset: CGSize) {
        let x = axis(offset.width)
        let y = axis(offset.height)
        let pad = PadSnapshot.shared
        let unchanged = left ? (pad.lx == x && pad.ly == y) : (pad.rx == x && pad.ry == y)
        guard !unchanged else { return }
        pad.setStick(left: left, x: x, y: y)
        pad.send(source: .touch)
    }
}
