// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import CoreGraphics
import Observation
import UIKit

// A snap line the dragged piece meets, in pad points.
struct PadGuide: Hashable {
    enum Axis { case x, y }
    let axis: Axis
    let at: CGFloat
}

// The layout editor's state: a draft of the whole layout, undo, and the gesture in flight.
@MainActor @Observable final class PadEditor {
    let serial: String?
    // The layout the draft was opened from, and goes back into on Done.
    var choice: PadLayoutChoice
    // What the game used when the editor opened, so Done with nothing changed has nothing to ask.
    let openedChoice: PadLayoutChoice
    // False when opened from the Layouts list, where saving edits a layout without switching every game to it.
    var makesShared = true
    var draft: PadLayout
    private(set) var opened: PadLayout

    var slot: PadSlot = .portrait
    var area: CGSize = .zero
    var fold: ClosedRange<CGFloat>?
    var frames: [PadPiece: CGRect] = [:]
    var selected: PadPiece?
    var mirror = true
    var snap = true
    var grid = false
    var showTouch = false
    var trying = false {
        didSet {
            PadTouches.shared.liftAll()
            PadTouches.shared.heldSticks = []
            PadTouches.shared.muted = trying
            if trying { selected = nil }
        }
    }
    private(set) var guides: Set<PadGuide> = []
    // Per slot, so Undo never changes an orientation that is not on screen.
    private var undoStacks: [PadSlot: [PadSlotLayout]] = [:]
    private var redoStacks: [PadSlot: [PadSlotLayout]] = [:]

    // A gesture records its undo step on its first real change, so a plain tap only selects.
    @ObservationIgnored private var gesture: (piece: PadPiece, frame: CGRect, size: CGFloat, recorded: Bool)?
    // Steps on the same setting of the same piece in quick succession, such as a held + button, undo as one.
    @ObservationIgnored private var lastStep: (piece: PadPiece, setting: Int, at: Date)?
    private(set) var dragging = false
    private(set) var resizing = false
    @ObservationIgnored private let tick = UISelectionFeedbackGenerator()

    static let snapReach: CGFloat = 6
    static let margin: CGFloat = 16
    static let undoDepth = 100

    init(serial: String?, library: PadLayoutLibrary) {
        // A game with no serial cannot keep a layout of its own, so it edits like Settings does.
        self.serial = PadLayoutLibrary.key(serial) == nil ? nil : serial
        let layout = library.layout(for: serial)
        let startChoice = library.choice(for: serial)
        choice = startChoice
        openedChoice = startChoice
        draft = layout
        opened = layout
    }

    var changed: Bool { draft != opened }

    var current: PadSlotLayout {
        get { draft[slot] }
        set { draft[slot] = newValue }
    }

    // Saved under another name: the draft now matches it, and the history stays.
    func saved(as choice: PadLayoutChoice) {
        self.choice = choice
        opened = draft
    }

    // Opens another layout in the draft; the history starts again.
    func load(_ choice: PadLayoutChoice, from library: PadLayoutLibrary) {
        self.choice = choice
        draft = library.layout(of: choice, serial: serial)
        opened = draft
        selected = nil
        undoStacks = [:]
        redoStacks = [:]
    }

    // MARK: Undo

    var canUndo: Bool { !(undoStacks[slot] ?? []).isEmpty }
    var canRedo: Bool { !(redoStacks[slot] ?? []).isEmpty }

    private func record(_ slot: PadSlot? = nil) {
        let slot = slot ?? self.slot
        push(draft[slot], for: slot)
    }

    private func push(_ snapshot: PadSlotLayout, for slot: PadSlot) {
        lastStep = nil
        undoStacks[slot, default: []].append(snapshot)
        if undoStacks[slot]!.count > Self.undoDepth { undoStacks[slot]!.removeFirst() }
        redoStacks[slot] = []
    }

    func undo() {
        guard let last = undoStacks[slot]?.popLast() else { return }
        redoStacks[slot, default: []].append(current)
        current = last
        selected = nil
    }

    func redo() {
        guard let next = redoStacks[slot]?.popLast() else { return }
        undoStacks[slot, default: []].append(current)
        current = next
        selected = nil
    }

    // One undo step for a change that is not a gesture.
    func edit(_ change: (inout PadSlotLayout) -> Void) {
        var next = current
        change(&next)
        guard next != current else { return }
        record()
        current = next
    }


    // MARK: Moving and resizing

    // A second finger on another piece is ignored until the first lets go, as both would share one start.
    func drag(_ piece: PadPiece, by translation: CGSize) {
        guard area.width > 0, area.height > 0 else { return }
        if gesture == nil {
            guard let frame = frames[piece] else { return }
            selected = piece
            gesture = (piece, frame, current[piece].size, false)
            tick.prepare()
        }
        // A tap's few points of wobble only select, and never snap the piece.
        guard let held = gesture, held.piece == piece,
              held.recorded || hypot(translation.width, translation.height) >= 4 else { return }
        let start = held.frame
        begin()
        dragging = true
        var frame = start.offsetBy(dx: translation.width, dy: translation.height)
        frame = keepInside(frame)
        let found = snap ? snapped(frame, moving: piece) : (frame, Set<PadGuide>())
        if found.1 != guides, !found.1.isEmpty { tick.selectionChanged() }
        guides = found.1
        frame = found.0
        place(piece, centre: CGPoint(x: frame.midX, y: frame.midY))
    }

    // `factor` multiplies the size the gesture started from.
    func resize(_ piece: PadPiece, by factor: CGFloat) {
        if gesture == nil {
            guard let frame = frames[piece] else { return }
            selected = piece
            gesture = (piece, frame, current[piece].size, false)
        }
        guard let held = gesture, held.piece == piece, factor != 1 else { return }
        let start = held.size
        begin()
        resizing = true
        setSize(piece, start * factor)
    }

    private func begin() {
        guard gesture?.recorded == false else { return }
        record()
        gesture?.recorded = true
    }

    // `piece` is the one whose gesture ended; nil ends whatever is in flight, as on a rotation.
    func endGesture(_ piece: PadPiece? = nil) {
        if let piece, gesture.map({ $0.piece != piece }) ?? false { return }
        gesture = nil
        guides = []
        dragging = false
        resizing = false
    }

    // A panel stepper, L1 or R1, or a VoiceOver action; a step at a limit leaves no undo step.
    func step(_ piece: PadPiece, by delta: CGFloat) {
        stepping(piece, setting: 0) { setSize(piece, current[piece].size + delta) }
    }

    func stepTouch(_ piece: PadPiece, by delta: CGFloat) {
        let touch = PadPlacement.stepped(current[piece].touch + delta, in: PadPlacement.touches)
        stepping(piece, setting: 1) {
            current[piece].touch = touch
            if mirror, let partner = mirrorPartner(piece) { current[partner].touch = touch }
        }
    }

    func stepOpacity(_ piece: PadPiece, by delta: CGFloat) {
        let opacity = PadPlacement.stepped(current[piece].opacity + delta, in: PadPlacement.opacities)
        stepping(piece, setting: 2) { current[piece].opacity = opacity }
    }

    private func stepping(_ piece: PadPiece, setting: Int, _ apply: () -> Void) {
        let before = current
        apply()
        guard current != before else { return }
        let now = Date()
        if let last = lastStep, last.piece == piece, last.setting == setting, now.timeIntervalSince(last.at) < 1 {
            lastStep?.at = now
            return
        }
        push(before, for: slot)
        lastStep = (piece, setting, now)
    }

    private func place(_ piece: PadPiece, centre: CGPoint) {
        let share = CGPoint(x: centre.x / area.width, y: centre.y / area.height)
        var slotLayout = current
        slotLayout[piece].centre = share
        if mirror, let partner = mirrorPartner(piece) {
            slotLayout[partner].centre = CGPoint(x: 1 - share.x, y: share.y)
        }
        current = slotLayout
    }

    func setSize(_ piece: PadPiece, _ size: CGFloat) {
        let stepped = PadPlacement.stepped(size, in: PadPlacement.sizes)
        var slotLayout = current
        for target in [piece] + (mirror ? [mirrorPartner(piece)].compactMap { $0 } : []) {
            let before = slotLayout[target].size
            slotLayout[target].size = stepped
            // Grown about its centre, then nudged back inside the pad if that pushed it over an edge.
            guard let frame = frames[target], before > 0, area.width > 0 else { continue }
            let scale = stepped / before
            let grown = CGRect(x: frame.midX - frame.width * scale / 2, y: frame.midY - frame.height * scale / 2,
                               width: frame.width * scale, height: frame.height * scale)
            let inside = keepInside(grown)
            if inside.origin != grown.origin {
                slotLayout[target].centre = CGPoint(x: inside.midX / area.width, y: inside.midY / area.height)
            }
        }
        current = slotLayout
    }

    // A partner is mirrored only while it moves on its own too.
    func mirrorPartner(_ piece: PadPiece) -> PadPiece? {
        guard let partner = piece.partner, current.movable.contains(partner), current.movable.contains(piece) else { return nil }
        return partner
    }

    private func keepInside(_ frame: CGRect) -> CGRect {
        var frame = frame
        frame.origin.x = min(max(frame.minX, 0), max(0, area.width - frame.width))
        frame.origin.y = min(max(frame.minY, 0), max(0, area.height - frame.height))
        return frame
    }

    // The pad's margins and middle, the fold, and every other piece's edges and centre.
    private func targets(moving piece: PadPiece) -> (x: [CGFloat], y: [CGFloat]) {
        var x: [CGFloat] = [Self.margin, area.width / 2, area.width - Self.margin]
        var y: [CGFloat] = [area.height - Self.margin - 4, area.height / 2]
        if let fold { x += [fold.lowerBound - 12, fold.upperBound + 12] }
        let others = current.movable.filter { $0 != piece && $0 != mirrorPartner(piece) && !current[$0].hidden }
        for other in others {
            guard let frame = frames[other] else { continue }
            x += [frame.minX, frame.midX, frame.maxX]
            y += [frame.minY, frame.midY, frame.maxY]
        }
        return (x, y)
    }

    private func snapped(_ frame: CGRect, moving piece: PadPiece) -> (CGRect, Set<PadGuide>) {
        let (xs, ys) = targets(moving: piece)
        var frame = frame
        var found = Set<PadGuide>()
        func nearest(_ edges: [CGFloat], _ lines: [CGFloat]) -> (shift: CGFloat, line: CGFloat)? {
            var best: (CGFloat, CGFloat)?
            for edge in edges {
                for line in lines where abs(line - edge) <= Self.snapReach {
                    if best == nil || abs(line - edge) < abs(best!.0) { best = (line - edge, line) }
                }
            }
            return best
        }
        if let hit = nearest([frame.minX, frame.midX, frame.maxX], xs) {
            frame.origin.x += hit.shift
            found.insert(PadGuide(axis: .x, at: hit.line))
        }
        if let hit = nearest([frame.minY, frame.midY, frame.maxY], ys) {
            frame.origin.y += hit.shift
            found.insert(PadGuide(axis: .y, at: hit.line))
        }
        return (frame, found)
    }

    // A controller's d-pad step or a VoiceOver move, as its own undo step.
    func nudge(_ piece: PadPiece, dx: CGFloat, dy: CGFloat) {
        guard let frame = frames[piece] else { return }
        let moved = keepInside(frame.offsetBy(dx: dx, dy: dy))
        guard moved != frame else { return }
        record()
        place(piece, centre: CGPoint(x: moved.midX, y: moved.midY))
    }

    // The piece a controller's direction picks: the nearest one that way from `piece`, or the one nearest the middle.
    func neighbour(of piece: PadPiece?, dx: CGFloat, dy: CGFloat) -> PadPiece? {
        let shown = current.movable.compactMap { candidate in frames[candidate].map { (candidate, $0) } }
        let origin = piece.flatMap { frames[$0] }.map { CGPoint(x: $0.midX, y: $0.midY) }
            ?? CGPoint(x: area.width / 2, y: area.height / 2)
        let ahead = shown.filter { candidate, frame in
            guard candidate != piece else { return false }
            if dx == 0, dy == 0 { return true }
            return (frame.midX - origin.x) * dx + (frame.midY - origin.y) * dy > 0
        }
        return ahead.min { a, b in
            hypot(a.1.midX - origin.x, a.1.midY - origin.y) < hypot(b.1.midX - origin.x, b.1.midY - origin.y)
        }?.0 ?? piece
    }

    // MARK: Overlap

    // Which hit cluster a piece's touches go to; overlaps inside one cluster are settled by nearness.
    private static func hitGroup(_ piece: PadPiece) -> Int {
        switch piece {
        case .dpad, .up, .down, .left, .right: return 0
        case .face, .triangle, .circle, .square, .cross: return 1
        case .l1, .l2, .r1, .r2: return 2
        case .select, .ps, .start: return 3
        case .l3, .r3: return 4
        case .lstick: return 5
        case .rstick: return 6
        }
    }

    // What the hit test gives it: a cluster's is the union of its buttons' regions.
    func touchRegion(_ piece: PadPiece) -> CGRect? {
        guard let frame = frames[piece] else { return nil }
        if piece.isStick { return frame }
        let touch = current[piece].touch
        if !piece.members.isEmpty {
            let regions = piece.members.compactMap { frames[$0] }.map { member in
                PadHit.region(member.insetBy(dx: member.width * (1 - touch) / 2, dy: member.height * (1 - touch) / 2))
            }
            return regions.isEmpty ? nil : regions.reduce(CGRect.null) { $0.union($1) }
        }
        return PadHit.region(frame.insetBy(dx: frame.width * (1 - touch) / 2, dy: frame.height * (1 - touch) / 2))
    }

    // A sliver is settled by which button centre is nearer, as the default pad's own few points of overlap are.
    private static func clash(_ a: CGRect, _ b: CGRect) -> Bool {
        let shared = a.intersection(b)
        return !shared.isNull && shared.width > 4 && shared.height > 4
    }

    // A stick takes touches only inside its circle, so a button tucked into its frame's corner is clear of it.
    private func clash(_ a: (PadPiece, CGRect), _ b: (PadPiece, CGRect)) -> Bool {
        guard a.0.isStick || b.0.isStick else { return Self.clash(a.1, b.1) }
        let (stick, other) = a.0.isStick ? (a.1, b.1) : (b.1, a.1)
        let centre = CGPoint(x: stick.midX, y: stick.midY)
        let nearest = CGPoint(x: min(max(centre.x, other.minX), other.maxX), y: min(max(centre.y, other.minY), other.maxY))
        return hypot(nearest.x - centre.x, nearest.y - centre.y) < stick.width / 2 - 4
    }

    func overlaps(of piece: PadPiece) -> [PadPiece] {
        guard let mine = touchRegion(piece), !current[piece].hidden else { return [] }
        return current.movable.filter { other in
            other != piece && !current[other].hidden && Self.hitGroup(other) != Self.hitGroup(piece)
                && (touchRegion(other).map { clash((other, $0), (piece, mine)) } ?? false)
        }
    }

    // Pieces whose touch areas cross a piece of another cluster, which would take its presses.
    var overlapping: Set<PadPiece> {
        let shown = current.movable.filter { !current[$0].hidden }
        let regions = shown.compactMap { piece in touchRegion(piece).map { (piece, $0) } }
        var found = Set<PadPiece>()
        for (index, a) in regions.enumerated() {
            for b in regions[(index + 1)...] where Self.hitGroup(a.0) != Self.hitGroup(b.0) && clash(a, b) {
                found.insert(a.0)
                found.insert(b.0)
            }
        }
        return found
    }

    // MARK: Piece options

    func toggleHidden(_ piece: PadPiece) {
        edit { $0[piece].hidden.toggle() }
    }

    // Pieces the player hid; L3 and R3 start hidden and stay that way until shown one by one.
    var playerHidden: [PadPiece] {
        PadPiece.allCases.filter { current[$0].hidden && !PadPlacement.standard($0).hidden }
    }

    func showAll() {
        edit { slotLayout in
            for piece in playerHidden { slotLayout[piece].hidden = false }
        }
    }

    func resetPiece(_ piece: PadPiece) {
        edit { slotLayout in
            slotLayout[piece] = .standard(piece)
            slotLayout.split.remove(piece)
            for member in piece.members { slotLayout[member] = .standard(member) }
            slotLayout.floating.remove(piece)
            slotLayout.zones[piece] = nil
        }
    }

    // Any orientation, not only the one on screen, so a locked rotation does not trap a layout.
    func resetSlot(_ which: PadSlot? = nil) {
        let which = which ?? slot
        guard draft[which] != PadSlotLayout() else { return }
        record(which)
        draft[which] = PadSlotLayout()
        selected = nil
    }

    func resetAll() {
        for slot in PadSlot.allCases where draft[slot] != PadSlotLayout() {
            record(slot)
            draft[slot] = PadSlotLayout()
        }
        selected = nil
    }

    func copy(from other: PadSlot) {
        let source = draft[other]
        edit { $0 = source }
    }

    // Each button starts where it is drawn now, at the cluster's size.
    func split(_ cluster: PadPiece) {
        guard area.width > 0 else { return }
        edit { slotLayout in
            let whole = slotLayout[cluster]
            slotLayout.split.insert(cluster)
            for member in cluster.members {
                guard let frame = frames[member] else { continue }
                var placement = whole
                placement.centre = CGPoint(x: frame.midX / area.width, y: frame.midY / area.height)
                slotLayout[member] = placement
            }
        }
        selected = cluster.members.first
    }

    // Back into the default diamond, where the cluster was.
    func join(_ cluster: PadPiece) {
        edit { slotLayout in
            slotLayout.split.remove(cluster)
            for member in cluster.members { slotLayout[member] = .standard(member) }
        }
        selected = cluster
    }

    // The zone's corner nearest the pad's middle follows the finger, and the stick's home stays inside it.
    func moveZoneCorner(_ stick: PadPiece, to point: CGPoint, first: Bool) {
        guard area.width > 0, area.height > 0, let home = frames[stick] else { return }
        if first { record() }
        let left = stick == .lstick
        let x = min(max(point.x / area.width, 0), 1), y = min(max(point.y / area.height, 0), 1)
        let homeX = (left ? home.maxX : home.minX) / area.width, homeY = home.minY / area.height
        let edge = left ? max(x, homeX) : min(x, homeX)
        let top = min(y, homeY)
        var slotLayout = current
        slotLayout.zones[stick] = CGRect(x: left ? 0 : edge, y: top, width: left ? edge : 1 - edge, height: 1 - top)
        current = slotLayout
    }

    func setFloating(_ stick: PadPiece, _ on: Bool) {
        edit { slotLayout in
            if on { slotLayout.floating.insert(stick) } else { slotLayout.floating.remove(stick) }
        }
    }
}
