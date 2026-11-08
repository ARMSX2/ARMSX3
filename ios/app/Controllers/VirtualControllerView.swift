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

    // A split d-pad's arrows can sit anywhere, so each one is pressed by nearness instead of by angle.
    static func clusters(_ layout: PadSlotLayout) -> [(rule: PadHitRule, buttons: [Rpcs3PadButton])] {
        [(layout.isSplit(.dpad) ? .nearest : .eightWay, [.up, .down, .left, .right]),
         (.chord, [.cross, .circle, .square, .triangle]),
         (.nearest, [.L1, .L2, .R1, .R2]),
         (.nearest, [.select, .PS, .start]),
         (.nearest, [.L3, .R3])]
    }

    // PadTouchSurface fills this coordinate space exactly.
    static let space = "armsx3.pad"

    // The game whose layout the pad draws; nil draws the shared one.
    var serial: String?

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.padEditor) private var editor

    static let face: CGFloat = 50
    // All four shoulders one size; a skin's shoulder art fills that frame, since Graphite's L1 and L2 differ in shape.
    static let shoulder = CGSize(width: 80, height: 28)
    static let small = CGSize(width: 58, height: 26)
    static let home = CGSize(width: 40, height: 40)
    static let stick = CGSize(width: 104, height: 104)
    // The halt sentence keeps inside this in landscape, so it wraps instead of crossing L1 or R1.
    static let shoulderReach: CGFloat = 16 + shoulder.width * 2 + 10
    @State private var bookFold: ClosedRange<CGFloat>?
    @State private var padSize: CGSize = .zero

    private var slot: PadSlot {
        if bookFold != nil { return .book }
        return padSize.width > padSize.height ? .landscape : .portrait
    }

    private var layout: PadSlotLayout {
        (editor?.draft ?? PadLayoutStore.shared.library.layout(for: serial))[slot]
    }

    var body: some View {
        let layout = layout
        VStack(spacing: 14) {
            Spacer()
            HStack(spacing: 10) {
                button(.l2, .L2, Self.shoulder)
                button(.l1, .L1, Self.shoulder)
                Spacer()
                button(.r1, .R1, Self.shoulder)
                button(.r2, .R2, Self.shoulder)
            }
            HStack {
                diamond(.dpad, top: .up, left: .left, right: .right, bottom: .down)
                Spacer()
                diamond(.face, top: .triangle, left: .square, right: .circle, bottom: .cross)
            }
            if let fold = bookFold {
                // In book pose PS and Start go by the right stick, so nothing sits on the fold; 16 is the padding.
                HStack(alignment: .bottom, spacing: 0) {
                    HStack(alignment: .bottom) {
                        stick(.lstick, click: .L3)
                        Spacer(minLength: 4)
                        button(.select, .select, Self.small)
                    }
                    .frame(width: max(0, fold.lowerBound - 16 - 12))
                    Spacer(minLength: 0)
                    HStack(alignment: .bottom, spacing: 8) {
                        button(.ps, .PS, Self.home)
                        button(.start, .start, Self.small)
                        Spacer(minLength: 4)
                        stick(.rstick, click: .R3)
                    }
                    .frame(width: max(0, padSize.width - fold.upperBound - 16 - 12))
                }
            } else {
                HStack(alignment: .bottom) {
                    stick(.lstick, click: .L3)
                    Spacer(minLength: 4)
                    HStack(spacing: 8) {
                        button(.select, .select, Self.small)
                        button(.ps, .PS, Self.home)
                        button(.start, .start, Self.small)
                    }
                    Spacer(minLength: 4)
                    stick(.rstick, click: .R3)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 20)
        .overlay { FloatingStickZones() }
        .environment(\.padSlot, layout)
        .environment(\.padArea, padSize)
        .onGeometryChange(for: CGSize.self) { $0.size } action: { padSize = $0 }
        .onBookFoldChange { bookFold = $0 }
        .coordinateSpace(.named(Self.space))
        .background { PadTouchSurface() }
        .onPreferenceChange(PadArtFrames.self) { frames in
            MainActor.assumeIsolated { PadTouches.shared.layout(frames, clusters: Self.clusters(layout)) }
        }
        .onPreferenceChange(PadStickFrames.self) { frames in
            MainActor.assumeIsolated { PadTouches.shared.sticks = frames }
        }
        .onChange(of: layout.split) { _, _ in
            PadTouches.shared.layout(PadTouches.shared.frames, clusters: Self.clusters(layout))
        }
        .onPreferenceChange(PadPieceFrames.self) { frames in
            MainActor.assumeIsolated { editor?.frames = frames }
        }
        .onChange(of: slot, initial: true) { _, slot in editor?.slot = slot }
        .onChange(of: padSize, initial: true) { _, size in editor?.area = size }
        .onChange(of: bookFold, initial: true) { _, fold in editor?.fold = fold }
        // Leaving the active state or hiding the pad lifts every finger, so nothing stays held.
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { PadTouches.shared.liftAll() }
        }
        .onDisappear { PadTouches.shared.liftAll() }
    }

    private func button(_ piece: PadPiece, _ button: Rpcs3PadButton, _ base: CGSize) -> some View {
        PadPieceView(piece: piece, base: base) { PadButton(control: Self.control(button), size: $0) }
    }

    private func stick(_ piece: PadPiece, click: Rpcs3PadButton) -> some View {
        PadPieceView(piece: piece, base: Self.stick) { PadStick(left: piece == .lstick, click: click, size: $0.width) }
            // L3 or R3, hidden at first, on the stick's inner side; an overlay, so it moves with the stick.
            .overlay(alignment: piece == .lstick ? .trailing : .leading) {
                button(piece == .lstick ? .l3 : .r3, click, Self.home)
                    .offset(x: (piece == .lstick ? 1 : -1) * (Self.home.width + 6))
            }
    }

    private func diamond(_ cluster: PadPiece, top: Rpcs3PadButton, left: Rpcs3PadButton, right: Rpcs3PadButton,
                         bottom: Rpcs3PadButton) -> some View {
        let split = layout.isSplit(cluster)
        // One cross behind four arrows reads as a d-pad and shows a diagonal works; each arrow keeps its own touch frame.
        let cross = cluster == .dpad && PadArt.image(Self.control(.up).art) == nil
        return PadPieceView(piece: cluster, base: CGSize(width: Self.face * 3, height: Self.face * 3)) { size in
            let face = size.width / 3
            VStack(spacing: 0) {
                member(top, face, alone: split && cross)
                HStack(spacing: face) {
                    member(left, face, alone: split && cross)
                    member(right, face, alone: split && cross)
                }
                member(bottom, face, alone: split && cross)
            }
            .background {
                if cross && !split { DpadBacking(shape: DpadCross()) }
            }
        }
    }

    // A split arrow with no art of its own gets a key of the cross's look, since a bare glyph does not read as a button.
    private func member(_ button: Rpcs3PadButton, _ face: CGFloat, alone: Bool) -> some View {
        let base = CGSize(width: face, height: face)
        return PadPieceView(piece: PadPiece(button)!, base: base) { size in
            PadButton(control: Self.control(button), size: size)
                .background {
                    if alone { DpadBacking(shape: RoundedRectangle(cornerRadius: 12)) }
                }
        }
    }
}

private struct DpadBacking<S: Shape>: View {
    let shape: S
    @Environment(\.padPieceOpacity) private var pieceOpacity

    var body: some View {
        shape.fill(.black.opacity(0.45))
            .overlay(shape.stroke(.white.opacity(0.7), lineWidth: 1.5))
            .opacity(SettingsStore.shared.padOpacity * pieceOpacity)
    }
}

extension PadPiece {
    init?(_ button: Rpcs3PadButton) {
        switch button {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .triangle: self = .triangle
        case .circle: self = .circle
        case .square: self = .square
        case .cross: self = .cross
        default: return nil
        }
    }
}

extension EnvironmentValues {
    @Entry var padSlot = PadSlotLayout()
    @Entry var padArea = CGSize.zero
    @Entry var padEditor: PadEditor?
    @Entry var padPieceOpacity: CGFloat = 1
    @Entry var padTouchScale: CGFloat = 1
}

// Draws one piece where its layout puts it. Its layout frame stays the default one, so the rest of the pad never moves.
private struct PadPieceView<Content: View>: View {
    let piece: PadPiece
    let base: CGSize
    @ViewBuilder let content: (CGSize) -> Content
    @Environment(\.padSlot) private var slot
    @Environment(\.padArea) private var area
    @Environment(\.padEditor) private var editor
    @Environment(\.padPieceOpacity) private var inheritedOpacity
    @Environment(\.padTouchScale) private var inheritedTouch
    @State private var home: CGRect = .zero

    // A cluster's own placement holds until it is split; then its buttons carry theirs.
    private var placement: PadPlacement {
        if !piece.members.isEmpty, slot.isSplit(piece) { return PadPlacement() }
        if let cluster = piece.cluster, !slot.isSplit(cluster) { return PadPlacement() }
        return slot[piece]
    }

    var body: some View {
        let placement = placement
        let drawn = CGSize(width: base.width * placement.size, height: base.height * placement.size)
        let editing = editor.map { !$0.trying } ?? false
        // Hidden until the default spot is known, so a moved piece never flashes there first.
        let waiting = placement.centre != nil && home == .zero
        content(drawn)
            .environment(\.padPieceOpacity, inheritedOpacity * placement.opacity)
            .environment(\.padTouchScale, inheritedTouch * placement.touch)
            // A cluster's split buttons are pieces of their own inside it, so they still need its touches.
            .allowsHitTesting(!placement.hidden && (!editing || !piece.members.isEmpty))
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: PadPieceFrames.self,
                                           value: [piece: geometry.frame(in: .named(VirtualControllerView.space))])
                }
            }
            .overlay {
                if editing { PadPieceEditOverlay(piece: piece, placement: placement) }
            }
            .frame(width: base.width, height: base.height)
            .opacity(waiting ? 0 : placement.hidden ? (editing ? 0.3 : 0) : 1)
            .transformPreference(PadArtFrames.self) { if placement.hidden { $0 = [:] } }
            .transformPreference(PadStickFrames.self) { if placement.hidden { $0 = [:] } }
            .offset(offset(placement))
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(VirtualControllerView.space)) } action: { home = $0 }
    }

    private func offset(_ placement: PadPlacement) -> CGSize {
        guard let centre = placement.centre, home != .zero else { return .zero }
        return CGSize(width: centre.x * area.width - home.midX, height: centre.y * area.height - home.midY)
    }
}

// One instance, because one pad is on screen at a time.
@MainActor @Observable final class PadTouches {
    static let shared = PadTouches()

    // What the press highlight draws.
    private(set) var held: Set<Rpcs3PadButton> = []
    // Observed, so a floating stick's zone redraws its cut-outs when a button moves.
    private(set) var frames: [Rpcs3PadButton: CGRect] = [:]
    @ObservationIgnored private var tracker = PadTouchTracker<ObjectIdentifier, Rpcs3PadButton>()
    @ObservationIgnored private let haptic = UIImpactFeedbackGenerator(style: .light)
    @ObservationIgnored private var aiming = Set<ObjectIdentifier>()

    // The sticks' frames, so a finger on one never aims.
    var sticks: [Bool: CGRect] = [:]
    // Try mode in the layout editor: presses light up and reach nothing.
    @ObservationIgnored var muted = false
    // Sticks held off centre in try mode, by left, for the editor's readout.
    var heldSticks: Set<Bool> = []

    private init() {}

    func layout(_ frames: [Rpcs3PadButton: CGRect], clusters: [(rule: PadHitRule, buttons: [Rpcs3PadButton])]) {
        self.frames = frames
        tracker.clusters = clusters.map { cluster in
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
            || sticks.values.contains { $0.contains(point) }
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
        if !muted {
            for button in change.released { PadSnapshot.shared.set(button, pressed: false) }
            for button in change.pressed { PadSnapshot.shared.set(button, pressed: true) }
            PadSnapshot.shared.send(source: .touch)
        }
        held = tracker.held
        if !change.pressed.isEmpty {
            if SettingsStore.shared.padVibrates { haptic.impactOccurred() }
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

// Keyed by left.
private struct PadStickFrames: PreferenceKey {
    static let defaultValue: [Bool: CGRect] = [:]

    static func reduce(value: inout [Bool: CGRect], nextValue: () -> [Bool: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

// Every piece's drawn frame, for the layout editor.
struct PadPieceFrames: PreferenceKey {
    static let defaultValue: [PadPiece: CGRect] = [:]

    static func reduce(value: inout [PadPiece: CGRect], nextValue: () -> [PadPiece: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

private struct PadTouchSurface: UIViewRepresentable {
    func makeUIView(context: Context) -> PadTouchView {
        PadTouchView()
    }

    // The editor's own gestures take the touches unless it is in try mode.
    func updateUIView(_ view: PadTouchView, context: Context) {
        view.isUserInteractionEnabled = context.environment.padEditor?.trying ?? true
    }
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
    @Environment(\.padPieceOpacity) private var pieceOpacity
    @Environment(\.padTouchScale) private var touchScale

    var body: some View {
        let pressed = PadTouches.shared.held.contains(control.button)
        PadFace(control: control, size: size)
            .frame(width: size.width, height: size.height)
            .background {
                GeometryReader { geometry in
                    let art = geometry.frame(in: .named(VirtualControllerView.space))
                    // A larger touch area is the art grown about its centre.
                    Color.clear.preference(key: PadArtFrames.self,
                                           value: [control.button: art.insetBy(dx: art.width * (1 - touchScale) / 2,
                                                                               dy: art.height * (1 - touchScale) / 2)])
                }
            }
            .opacity(pressed ? 1 : SettingsStore.shared.padOpacity * pieceOpacity)
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
    let size: CGFloat
    private var radius: CGFloat { size / 2 }
    private var knob: CGFloat { size / 2 }
    @Environment(\.padPieceOpacity) private var pieceOpacity
    @State private var offset: CGSize = .zero
    @State private var travelled: CGFloat = 0
    // SwiftUI resets this on end and on cancellation, which re-centres a stick onEnded alone missed.
    @GestureState private var touching = false

    var body: some View {
        let floating = FloatingSticks.shared.held[left]
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
                .offset(floating?.knob ?? offset)
        }
        .frame(width: radius * 2, height: radius * 2)
        // A floating stick is drawn where the thumb landed; the frame after this offset stays home.
        .offset(floating.flatMap { held in
            PadTouches.shared.sticks[left].map { CGSize(width: held.centre.x - $0.midX, height: held.centre.y - $0.midY) }
        } ?? .zero)
        .opacity(touching || floating != nil ? 1 : SettingsStore.shared.padOpacity * pieceOpacity)
        // The stick's frame, so a finger on it never aims.
        .background {
            GeometryReader { geometry in
                Color.clear.preference(key: PadStickFrames.self, value: [left: geometry.frame(in: .named(VirtualControllerView.space))])
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
                    if travelled < 8, !PadTouches.shared.muted { VirtualControllerView.press(click) }
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

    private func send(_ offset: CGSize) {
        Self.send(left: left, offset, radius: radius)
    }

    // The floating zone sends through here too.
    static func send(left: Bool, _ offset: CGSize, radius: CGFloat) {
        func axis(_ value: CGFloat) -> UInt8 {
            UInt8(clamping: Int((CGFloat(PadSnapshot.centre) + value / radius * 127).rounded()))
        }
        let x = axis(offset.width)
        let y = axis(offset.height)
        // Try mode sends nothing, so the real pad's centred axes say nothing about a lift.
        if PadTouches.shared.muted {
            if offset == .zero { PadTouches.shared.heldSticks.remove(left) } else { PadTouches.shared.heldSticks.insert(left) }
            return
        }
        let pad = PadSnapshot.shared
        let unchanged = left ? (pad.lx == x && pad.ly == y) : (pad.rx == x && pad.ry == y)
        guard !unchanged else { return }
        pad.setStick(left: left, x: x, y: y)
        pad.send(source: .touch)
    }
}

// Where a floating stick sits while a thumb holds it, and how far its knob is pushed, by left.
@MainActor @Observable final class FloatingSticks {
    struct Held {
        var centre: CGPoint
        var knob: CGSize
    }

    static let shared = FloatingSticks()
    var held: [Bool: Held] = [:]
}

// A floating stick's zone takes a thumb anywhere in it, except on a button, which keeps its own touches.
private struct FloatingStickZones: View {
    @Environment(\.padSlot) private var slot
    @Environment(\.padArea) private var area
    @Environment(\.padEditor) private var editor

    var body: some View {
        if editor.map(\.trying) ?? true {
            ZStack(alignment: .topLeading) {
                ForEach([PadPiece.lstick, .rstick].filter { slot.floating.contains($0) && !slot[$0].hidden }, id: \.self) { stick in
                    let share = slot.zone(stick)
                    FloatingStickZone(left: stick == .lstick,
                                      rect: CGRect(x: share.minX * area.width, y: share.minY * area.height,
                                                   width: share.width * area.width, height: share.height * area.height))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

private struct FloatingStickZone: View {
    let left: Bool
    let rect: CGRect
    @GestureState private var touching = false

    var body: some View {
        // The buttons' regions and the other stick are cut out of the zone.
        let blocked = PadTouches.shared.frames.values.map(PadHit.region) + PadTouches.shared.sticks.filter { $0.key != left }.map(\.value)
        let shape = blocked.reduce(Path(CGRect(origin: .zero, size: rect.size))) { zone, region in
            zone.subtracting(Path(region.offsetBy(dx: -rect.minX, dy: -rect.minY)))
        }
        Color.clear
            .frame(width: rect.width, height: rect.height)
            .contentShape(shape)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named(VirtualControllerView.space))
                    .updating($touching) { _, touching, _ in touching = true }
                    .onChanged { value in
                        let radius = (PadTouches.shared.sticks[left]?.width ?? VirtualControllerView.stick.width) / 2
                        let length = hypot(value.translation.width, value.translation.height)
                        let scale = length > radius ? radius / length : 1
                        let knob = CGSize(width: value.translation.width * scale, height: value.translation.height * scale)
                        FloatingSticks.shared.held[left] = .init(centre: value.startLocation, knob: knob)
                        PadStick.send(left: left, knob, radius: radius)
                    }
            )
            // A lift or a cancel sends the stick home and centres it.
            .onChange(of: touching) { _, down in
                guard !down else { return }
                FloatingSticks.shared.held[left] = nil
                PadStick.send(left: left, .zero, radius: (PadTouches.shared.sticks[left]?.width ?? VirtualControllerView.stick.width) / 2)
            }
            // The menu or a controller can take the pad away under a held thumb, and no lift follows.
            .onDisappear {
                guard FloatingSticks.shared.held[left] != nil else { return }
                FloatingSticks.shared.held[left] = nil
                PadStick.send(left: left, .zero, radius: (PadTouches.shared.sticks[left]?.width ?? VirtualControllerView.stick.width) / 2)
            }
            .position(x: rect.midX, y: rect.midY)
            .accessibilityHidden(true)
    }
}
