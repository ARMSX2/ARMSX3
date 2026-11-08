// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI

extension PadPiece {
    var title: String {
        switch self {
        case .dpad: return String(localized: "D-Pad")
        case .face: return String(localized: "Face Buttons")
        case .up: return String(localized: "D-Pad Up")
        case .down: return String(localized: "D-Pad Down")
        case .left: return String(localized: "D-Pad Left")
        case .right: return String(localized: "D-Pad Right")
        case .triangle: return String(localized: "Triangle")
        case .circle: return String(localized: "Circle")
        case .square: return String(localized: "Square")
        case .cross: return String(localized: "Cross")
        case .l1: return "L1"
        case .l2: return "L2"
        case .r1: return "R1"
        case .r2: return "R2"
        case .lstick: return String(localized: "Left Stick")
        case .rstick: return String(localized: "Right Stick")
        case .l3: return "L3"
        case .r3: return "R3"
        case .select: return String(localized: "Select")
        case .ps: return "PS"
        case .start: return String(localized: "Start")
        }
    }
}

extension PadSlot {
    var title: String {
        switch self {
        case .portrait: return String(localized: "Portrait")
        case .landscape: return String(localized: "Landscape")
        case .book: return String(localized: "Book")
        }
    }
}

// The full-screen editor. In a game it lies over the paused frame; from Settings over a plain backdrop.
struct PadLayoutEditorView: View {
    @Bindable var editor: PadEditor
    var overGame = false
    let onClose: () -> Void

    @AppStorage("armsx3.padEditor.hintShown") private var hintShown = false
    @Environment(\.uiAccentColour) private var accent
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @State private var drop: Drop?
    @State private var onlyThisGame = false
    @State private var question: Question?
    @State private var naming = false
    @State private var name = ""
    @State private var barFrame: CGRect = .zero
    @State private var showHint = false
    @GestureState private var canvasPinch = false
    @Environment(\.menuControllerInputRouter) private var router
    @State private var controllerSession = UUID()
    // Cross on a selected piece hands the controller to its panel; Circle hands it back.
    @State private var panelFocused = false
    @State private var lastPiece: PadPiece?
    @State private var panelSize: CGSize = .zero

    private enum Drop { case more, picker, save }

    static let space = "armsx3.padEditor"

    private enum Question: Identifiable {
        case discard, resetSlot(PadSlot), resetAll, copy, clash(String), switchTo(PadLayoutChoice), saveFailed, replaceMine
        var id: String { "\(self)" }
    }

    var body: some View {
        GeometryReader { area in
            let inset = area.safeAreaInsets, even = min(inset.leading, inset.trailing)
            let top = area.tabletopFold()?.maxY ?? 0
            ZStack {
                canvas
                VirtualControllerView(serial: editor.serial)
                PadEditorMarks(editor: editor, accent: accent)
                    .allowsHitTesting(false)
                if let stick = editor.selected, stick.isStick, editor.current.floating.contains(stick), !editor.trying {
                    PadZoneHandle(editor: editor, stick: stick)
                }
                readout
                hint
                if drop != nil {
                    Color.clear.contentShape(Rectangle()).onTapGesture { drop = nil }
                }
                panel
            }
            .overlay(alignment: .top) { barStack }
            // The pad's own space has the same origin, but the bar is outside it.
            .coordinateSpace(.named(Self.space))
            .environment(\.padEditor, editor)
            .padding(.leading, even - inset.leading)
            .padding(.trailing, even - inset.trailing)
            .padding(.top, top)
            .frame(width: area.size.width, height: area.size.height)
            .background { shading(area: area, even: even, top: top).ignoresSafeArea() }
        }
        .background { if !overGame { OverlayTheme.scrimBase.ignoresSafeArea() } }
        .preferredColorScheme(.dark)
        .onAppear {
            // A game already on a layout of its own choosing keeps it unless the player says otherwise.
            onlyThisGame = editor.choice == .game
                || PadLayoutLibrary.key(editor.serial).map { PadLayoutStore.shared.library.pinned[$0] != nil } ?? false
            if !hintShown { showHint = true }
        }
        // Torn down without Done or Cancel (the game stopped), try mode must not leave the pad muted.
        .onDisappear {
            editor.trying = false
            router?.unregisterNavigationSession(id: controllerSession)
        }
        .task {
            router?.registerNavigationSession(id: controllerSession, scopeKey: "game.touchlayout", priority: 450, isReady: true) {
                handle($0)
            }
        }
        .onChange(of: editor.selected) { _, piece in
            if piece == nil { panelFocused = false } else { dismissHint() }
        }
        .onChange(of: editor.slot) { _, _ in
            editor.endGesture()
            editor.selected = nil
            drop = nil
        }
        .controllerPrompt(questionTitle, isPresented: Binding(get: { question != nil }, set: { if !$0 { question = nil } }),
                          message: questionMessage, actions: questionActions)
        .alert("Name this layout", isPresented: $naming) {
            TextField("Layout name", text: $name)
            Button("Save") { savePreset() }
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Cancel", role: .cancel) {}
        }
    }

    // Empty canvas: a tap clears the selection, a pinch resizes the selected piece.
    private var canvas: some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture {
                dismissHint()
                editor.selected = nil
            }
            .gesture(
                MagnifyGesture()
                    .updating($canvasPinch) { _, pinching, _ in pinching = true }
                    .onChanged { value in
                        if let piece = editor.selected { editor.resize(piece, by: value.magnification) }
                    }
            )
            .onChange(of: canvasPinch) { _, pinching in
                if !pinching { editor.endGesture(editor.selected) }
            }
    }

    // MARK: Bar

    private var barStack: some View {
        VStack(spacing: 8) {
            PadEditorBar(editor: editor, title: layoutName, onCancel: cancel, onDone: done,
                         onTitle: { toggle(.picker) }, onMore: { toggle(.more) })
                .frame(maxWidth: barRoom)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: { barFrame = $0 }
            Group {
                switch drop {
                case .more?: moreCard
                case .picker?: pickerCard
                case .save?: saveCard
                case nil: EmptyView()
                }
            }
            .controllerAccessibilityNavigation(controllerInput: router, isActive: drop != nil, scopeKey: "game.touchlayout.drop",
                                               priority: 500, onBack: { drop = nil; return true })
        }
        .frame(maxWidth: .infinity, alignment: barAlignment)
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .animation(.easeInOut(duration: 0.2), value: barAlignment)
    }

    // The bar steps aside, to the corner away from the piece, while a dragged piece comes near it.
    private var barAlignment: Alignment {
        guard editor.dragging, let piece = editor.selected, let frame = editor.frames[piece],
              frame.insetBy(dx: -8, dy: -8).intersects(barFrame) else { return .center }
        return frame.midX > editor.area.width / 2 ? .leading : .trailing
    }

    // The gap between the pieces along the top edge, such as the shoulders in landscape, so the bar sits clear of them.
    private var barRoom: CGFloat {
        let band = editor.current.movable.filter { !editor.current[$0].hidden }.compactMap { editor.frames[$0] }
            .filter { $0.minY < 72 }
        let middle = editor.area.width / 2
        let left = band.filter { $0.midX < middle }.map(\.maxX).max() ?? 0
        let right = band.filter { $0.midX >= middle }.map(\.minX).min() ?? editor.area.width
        let room = right - left - 16
        return room >= 300 ? room : editor.area.width - 24
    }

    // The orientations this device can show; Book only on a Duo.
    private var slots: [PadSlot] {
        PadSlot.allCases.filter { $0 != .book || DuoHinge.shared.present }
    }

    // What is left under the bar, so a long card scrolls instead of running off a phone held sideways.
    private var dropRoom: CGFloat {
        max(160, editor.area.height - barFrame.maxY - 20)
    }

    // Default never changes, so once it is edited the title names where the edits will go.
    private var layoutName: String {
        let library = PadLayoutStore.shared.library
        guard editor.choice == .standard, editor.changed else { return library.name(of: editor.choice) }
        return library.name(of: editor.serial != nil && onlyThisGame ? .game : .mine)
    }

    private func toggle(_ next: Drop) {
        drop = drop == next ? nil : next
    }

    // MARK: Drop-down cards

    private var moreCard: some View {
        PadEditorDropCard(maxHeight: dropRoom) {
            OverlaySectionCard {
                OverlayToggleRow(label: "Snap", systemImage: "ruler", isOn: $editor.snap)
                OverlayToggleRow(label: "Show Grid", systemImage: "grid", isOn: $editor.grid)
                OverlayToggleRow(label: "Show Touch Areas", systemImage: "square.dashed", isOn: $editor.showTouch)
                OverlayToggleRow(label: "Try Buttons", systemImage: "hand.point.up.left",
                                 isOn: Binding(get: { editor.trying }, set: { editor.trying = $0; drop = nil }))
                OverlayActionRow(label: "Show Hidden Buttons", systemImage: "eye") {
                    editor.showAll()
                    drop = nil
                }
                .disabled(editor.playerHidden.isEmpty)
            }
            OverlaySectionCard {
                OverlayActionRow(label: "Save as Preset…", systemImage: "square.stack") {
                    drop = nil
                    name = editor.choice.isPreset ? layoutName : ""
                    naming = true
                }
                OverlayActionRow(label: "Copy Another Orientation…", systemImage: "doc.on.doc") {
                    drop = nil
                    question = .copy
                }
            }
            OverlaySectionCard {
                ForEach(slots, id: \.self) { slot in
                    OverlayActionRow(label: String(localized: "Reset \(slot.title)"), systemImage: "arrow.counterclockwise",
                                     isDestructive: true) {
                        drop = nil
                        question = .resetSlot(slot)
                    }
                    .disabled(editor.draft[slot] == PadSlotLayout())
                }
                OverlayActionRow(label: "Reset Whole Layout", systemImage: "arrow.counterclockwise", isDestructive: true) {
                    drop = nil
                    question = .resetAll
                }
                Text("Each orientation keeps its own layout. Turn the device to edit another.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var pickerCard: some View {
        let library = PadLayoutStore.shared.library
        return PadEditorDropCard(maxHeight: dropRoom) {
            OverlaySectionCard {
                ForEach(library.choices, id: \.self) { choice in
                    OverlayActionRow(label: library.name(of: choice), trailingValue: editor.choice == choice ? "✓" : nil,
                                     keepsTrailingValue: true) { pick(choice) }
                }
            }
            if editor.serial != nil {
                OverlaySectionCard {
                    OverlayActionRow(label: "This Game's Layout", trailingValue: editor.choice == .game ? "✓" : nil,
                                     keepsTrailingValue: true) { pick(.game) }
                }
            }
        }
    }

    private var saveCard: some View {
        PadEditorDropCard(maxHeight: dropRoom) {
            OverlaySectionCard(title: "Save For") {
                // A segmented control, as Settings' own scope question, so both choices show before the tap.
                Picker("Save For", selection: $onlyThisGame) {
                    Text("All Games").tag(false)
                    Text("This Game Only").tag(true)
                }
                .pickerStyle(.segmented)
                .controllerAccessibilityActionTarget(id: "game.touchlayout.scope", label: String(localized: "Save For")) {
                    onlyThisGame.toggle()
                }
                Text(onlyThisGame
                     ? String(localized: "Only this game uses this. Other games keep \(PadLayoutStore.shared.library.name(of: PadLayoutStore.shared.library.sharedChoice)).")
                     : replacesMine ? String(localized: "Every game without its own layout uses this. It replaces My Layout.")
                     : String(localized: "Every game without its own layout uses this."))
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 8) {
                Button { drop = nil } label: {
                    Text("Back").font(.body).foregroundStyle(accent)
                        .padding(.horizontal, 16).frame(height: 44)
                        .background(accent.opacity(0.16), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                Button { save() } label: {
                    Text("Save").font(.headline).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).frame(height: 44)
                        .background(accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Panel

    @ViewBuilder private var panel: some View {
        if let piece = editor.selected, !editor.dragging, !editor.trying, drop == nil, editor.frames[piece] != nil {
            PadPiecePanel(editor: editor, piece: piece)
                .controllerAccessibilityNavigation(controllerInput: router, isActive: panelFocused,
                                                   scopeKey: "game.touchlayout.panel", priority: 500,
                                                   onBack: { panelFocused = false; return true })
                .fixedSize()
                .onGeometryChange(for: CGSize.self) { $0.size } action: { panelSize = $0 }
                .position(dock(for: piece))
                .transition(.opacity)
        }
    }

    // The spot covering least of the pad, never the selected piece; nearer spots win a tie.
    private func dock(for piece: PadPiece) -> CGPoint {
        let area = editor.area
        let size = panelSize == .zero ? CGSize(width: 360, height: 180) : panelSize
        guard let selected = editor.frames[piece], area.width > size.width, area.height > size.height else {
            return CGPoint(x: area.width / 2, y: area.height / 2)
        }
        let others = editor.current.movable.filter { $0 != piece && !editor.current[$0].hidden }.compactMap { editor.frames[$0] }
        let top = barFrame.maxY + 8, bottom = area.height - size.height - 8
        var best = (cost: CGFloat.infinity, centre: CGPoint(x: area.width / 2, y: top + size.height / 2))
        for y in stride(from: top, through: max(top, bottom), by: 8) {
            for x in stride(from: 12, through: max(12, area.width - size.width - 12), by: 8) {
                let card = CGRect(x: x, y: y, width: size.width, height: size.height)
                var cost = others.reduce(CGFloat(0)) { total, frame in
                    let shared = card.intersection(frame)
                    return shared.isNull ? total : total + shared.width * shared.height
                }
                if card.insetBy(dx: -8, dy: -8).intersects(selected) { cost += 1_000_000 }
                if let fold = editor.fold, card.minX < fold.upperBound, card.maxX > fold.lowerBound { cost += 1_000_000 }
                cost += hypot(card.midX - selected.midX, card.midY - selected.midY) / 100
                if cost < best.cost { best = (cost, CGPoint(x: card.midX, y: card.midY)) }
            }
        }
        return best.centre
    }

    // MARK: Readout and hint

    @ViewBuilder private var readout: some View {
        if editor.resizing, let piece = editor.selected, let frame = editor.frames[piece] {
            Text(verbatim: "\(Int((editor.current[piece].size * 100).rounded()))%")
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(OverlayTheme.textPrimary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(OverlayFrostBackground().clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous)))
                .position(x: frame.midX, y: max(12, frame.minY - 4 - 8 - 10))
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder private var hint: some View {
        if showHint, let highest = editor.frames.values.map(\.minY).min() {
            Text("Drag to move. Pinch or drag the corner to resize.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(OverlayTheme.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(OverlayFrostBackground().clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)))
                .frame(maxWidth: 360)
                .position(x: editor.area.width / 2, y: max(barFrame.maxY + 32, highest - 32))
                .allowsHitTesting(false)
                .transition(.opacity)
        }
    }

    private func dismissHint() {
        guard showHint else { return }
        withAnimation(.easeOut(duration: 0.3)) { showHint = false }
        hintShown = true
    }

    // Everything outside the pad area, and the Duo's fold, is shaded.
    private func shading(area: GeometryProxy, even: CGFloat, top: CGFloat) -> some View {
        let inset = area.safeAreaInsets
        let full = CGRect(x: 0, y: 0, width: area.size.width + inset.leading + inset.trailing,
                          height: area.size.height + inset.top + inset.bottom)
        let allowed = CGRect(x: even, y: inset.top + top, width: full.width - even * 2,
                             height: area.size.height - top)
        var path = Path(full)
        path.addRect(allowed)
        if let fold = editor.fold {
            path.addRect(CGRect(x: allowed.minX + fold.lowerBound, y: allowed.minY, width: fold.upperBound - fold.lowerBound,
                                height: allowed.height))
        }
        return path.fill(OverlayTheme.scrimBase.opacity(reduceTransparency ? 0.8 : 0.6), style: FillStyle(eoFill: true))
            .accessibilityHidden(true)
    }

    // MARK: Controller

    // The pad's own commands while no card or panel holds the controller.
    private func handle(_ command: MenuControllerCommand) -> Bool {
        guard question == nil, !naming else { return false }
        switch command {
        case .back:
            if editor.trying { editor.trying = false } else if editor.selected != nil { editor.selected = nil } else { cancel() }
        case .showContextMenu:
            toggle(.more)
        case .toggleFavorite:
            done()
        case .previousTab, .nextTab:
            if let piece = editor.selected { editor.step(piece, by: command == .nextTab ? PadPlacement.step : -PadPlacement.step) }
        case .activate:
            if editor.selected != nil { panelFocused = true } else { editor.selected = editor.neighbour(of: nil, dx: 0, dy: 0) }
        default:
            let (dx, dy) = Self.direction(command)
            if let piece = editor.selected {
                let stepSize: CGFloat = editor.snap ? 8 : 1
                editor.nudge(piece, dx: dx * stepSize, dy: dy * stepSize)
            } else {
                editor.selected = editor.neighbour(of: lastPiece, dx: dx, dy: dy)
            }
        }
        if let piece = editor.selected { lastPiece = piece }
        return true
    }

    private static func direction(_ command: MenuControllerCommand) -> (CGFloat, CGFloat) {
        switch command {
        case .up: return (0, -1)
        case .down: return (0, 1)
        case .left: return (-1, 0)
        case .right: return (1, 0)
        case .upLeft: return (-1, -1)
        case .upRight: return (1, -1)
        case .downLeft: return (-1, 1)
        case .downRight: return (1, 1)
        default: return (0, 0)
        }
    }

    // MARK: Actions

    private func cancel() {
        if editor.trying { editor.trying = false; return }
        if editor.changed { question = .discard } else { close() }
    }

    private func done() {
        if editor.trying { editor.trying = false }
        if editor.serial != nil {
            if editor.changed || editor.choice != editor.openedChoice { toggle(.save) } else { close() }
            return
        }
        if replacesMine { question = .replaceMine } else { save() }
    }

    // Done for all games would write over a My Layout the player did not open.
    private var replacesMine: Bool {
        let library = PadLayoutStore.shared.library
        return library.mine != nil && library.saveTarget(edited: editor.choice, changed: editor.changed) == .mine
    }

    private func save() {
        let saved = PadLayoutStore.shared.update { library in
            library.save(editor.draft, edited: editor.choice, changed: editor.changed, serial: editor.serial,
                         onlyThisGame: editor.serial != nil && onlyThisGame, makeShared: editor.makesShared)
        }
        if saved { close() } else { question = .saveFailed }
    }

    private func close() {
        editor.trying = false
        onClose()
    }

    private func pick(_ choice: PadLayoutChoice) {
        drop = nil
        guard choice != editor.choice else { return }
        if editor.changed { question = .switchTo(choice) } else { load(choice) }
    }

    // A game with no layout of its own yet starts one from what is on screen.
    private func load(_ choice: PadLayoutChoice) {
        let library = PadLayoutStore.shared.library
        if choice == .game, !library.exists(.game, serial: editor.serial) {
            editor.choice = .game
        } else {
            editor.load(choice, from: library)
        }
        onlyThisGame = choice == .game
    }

    private func savePreset(replacing: Bool? = nil) {
        let library = PadLayoutStore.shared.library
        if replacing == nil, library.preset(named: name) != nil { question = .clash(name); return }
        var preset: PadLayoutPreset?
        PadLayoutStore.shared.update { library in
            preset = replacing == true ? library.replacePreset(named: name, with: editor.draft)
                : library.addPreset(named: name, layout: editor.draft)
        }
        if let preset { editor.saved(as: .preset(preset.id)) }
    }

    // MARK: Questions

    private var questionTitle: String {
        switch question {
        case .discard?: return String(localized: "Discard your changes?")
        case .resetSlot(let slot)?: return String(localized: "Reset the \(slot.title) layout?")
        case .resetAll?: return String(localized: "Reset the whole layout?")
        case .copy?: return String(localized: "Copy into \(editor.slot.title)")
        case .clash(let name)?: return String(localized: "A layout called “\(name)” already exists.")
        case .switchTo(let choice)?: return String(localized: "Switch to “\(PadLayoutStore.shared.library.name(of: choice))”?")
        case .saveFailed?: return String(localized: "Couldn't save the layout")
        case .replaceMine?: return String(localized: "Replace My Layout?")
        case nil: return ""
        }
    }

    private var questionMessage: String {
        switch question {
        case .discard?: return String(localized: "The layout goes back to how it was when you opened the editor.")
        case .resetSlot(let slot)?:
            return slot == editor.slot
                ? String(localized: "Every button in \(slot.title) goes back to its default spot, size and look. Undo brings it back.")
                : String(localized: "Every button in \(slot.title) goes back to its default spot, size and look. Undo brings it back while \(slot.title) is on screen.")
        case .resetAll?: return String(localized: "Every orientation goes back to the default pad. Undo brings each one back while it's on screen.")
        case .copy?: return String(localized: "This replaces the \(editor.slot.title) layout. Undo brings it back.")
        case .clash?: return String(localized: "Replace it, or keep both and save this one with a number.")
        case .switchTo?: return String(localized: "Your changes to \(layoutName) aren't saved.")
        case .saveFailed?: return String(localized: "Check that the device has free space, then tap Done again.")
        case .replaceMine?: return String(localized: "Your changes go into My Layout. To keep it as it is, save them as a preset instead.")
        case nil: return ""
        }
    }

    private var questionActions: [ControllerPrompt.Action] {
        switch question {
        case .discard?:
            return [.init(title: String(localized: "Discard"), isDestructive: true, run: close),
                    .init(title: String(localized: "Keep Editing"), isCancel: true)]
        case .resetSlot(let slot)?:
            return [.init(title: String(localized: "Reset"), isDestructive: true, run: { editor.resetSlot(slot) }),
                    .init(title: String(localized: "Cancel"), isCancel: true)]
        case .resetAll?:
            return [.init(title: String(localized: "Reset"), isDestructive: true, run: { editor.resetAll() }),
                    .init(title: String(localized: "Cancel"), isCancel: true)]
        case .copy?:
            let others = slots.filter { $0 != editor.slot }
            return others.map { slot in .init(title: slot.title, run: { editor.copy(from: slot) }) }
                + [.init(title: String(localized: "Cancel"), isCancel: true)]
        case .clash?:
            return [.init(title: String(localized: "Replace"), isDestructive: true, run: { savePreset(replacing: true) }),
                    .init(title: String(localized: "Keep Both"), run: { savePreset(replacing: false) }),
                    .init(title: String(localized: "Cancel"), isCancel: true)]
        case .switchTo(let choice)?:
            return [.init(title: String(localized: "Discard Changes"), isDestructive: true, run: { load(choice) }),
                    .init(title: String(localized: "Keep Editing"), isCancel: true)]
        case .saveFailed?:
            return [.init(title: String(localized: "OK"), isCancel: true)]
        case .replaceMine?:
            return [.init(title: String(localized: "Replace"), isDestructive: true, run: save),
                    .init(title: String(localized: "Save as Preset…"), run: { name = ""; naming = true }),
                    .init(title: String(localized: "Cancel"), isCancel: true)]
        case nil:
            return []
        }
    }
}

extension PadLayoutChoice {
    var isPreset: Bool {
        if case .preset = self { return true }
        return false
    }
}

// A card that drops under the bar, so a controller can reach every row (a system Menu it cannot).
private struct PadEditorDropCard<Content: View>: View {
    var maxHeight: CGFloat = .infinity
    @ViewBuilder let content: Content
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        ViewThatFits(in: .vertical) {
            stack
            ScrollView { stack }
        }
        .frame(maxHeight: maxHeight)
        .padEditorSurface()
        .frame(width: sizeClass == .regular ? 320 : 300)
        .shadow(color: .black.opacity(0.28), radius: 22, y: 12)
    }

    private var stack: some View {
        VStack(spacing: 12) { content }.padding(12)
    }
}

// MARK: - Bar

private struct PadEditorBar: View {
    @Bindable var editor: PadEditor
    let title: String
    let onCancel: () -> Void
    let onDone: () -> Void
    let onTitle: () -> Void
    let onMore: () -> Void
    @Environment(\.uiAccentColour) private var accent

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 4) {
                cancel
                undoRedo
                heading.frame(maxWidth: 220)
                mirror
                more
                done
            }
            VStack(spacing: 4) {
                heading.frame(maxWidth: .infinity).frame(height: 44)
                Rectangle().fill(OverlayTheme.separator).frame(height: 0.5)
                HStack(spacing: 4) {
                    cancel
                    Spacer(minLength: 0)
                    undoRedo
                    mirror
                    more
                    Spacer(minLength: 0)
                    done
                }
            }
            .frame(maxWidth: 420)
        }
        .padding(4)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .background(OverlayFrostBackground().clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(OverlayTheme.cardTopHighlight, lineWidth: 1))
        .shadow(color: OverlayTheme.cardShadow, radius: 4, y: 2)
        .buttonStyle(.plain)
    }

    private var cancel: some View {
        Button(action: onCancel) {
            Text("Cancel").font(.body).foregroundStyle(OverlayTheme.textPrimary)
                .padding(.horizontal, 12).frame(height: 44).contentShape(Rectangle())
        }
    }

    @ViewBuilder private var undoRedo: some View {
        glyph("arrow.uturn.backward", label: String(localized: "Undo")) { editor.undo() }
            .disabled(!editor.canUndo || editor.trying)
        glyph("arrow.uturn.forward", label: String(localized: "Redo")) { editor.redo() }
            .disabled(!editor.canRedo || editor.trying)
    }

    @ViewBuilder private var heading: some View {
        if editor.trying {
            HStack(spacing: 8) {
                Text(heldLine)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .accessibilityAddTraits(.updatesFrequently)
                Button { editor.trying = false } label: {
                    Text("Stop").font(.body).foregroundStyle(accent).frame(height: 44).contentShape(Rectangle())
                }
            }
            .padding(.horizontal, 8)
        } else {
            // Only the name opens the layout list; the orientation follows the device and is not a choice here.
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Button(action: onTitle) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(title).font(.headline).foregroundStyle(OverlayTheme.textPrimary)
                            .lineLimit(1).truncationMode(.middle)
                        Image(systemName: "chevron.down").font(.caption).foregroundStyle(OverlayTheme.textSecondary)
                    }
                    .frame(height: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel(Text("Layout"))
                .accessibilityValue(Text(title))
                Text(editor.slot.title).font(.subheadline).foregroundStyle(OverlayTheme.textSecondary).lineLimit(1)
                    .contentTransition(.opacity)
            }
            .padding(.horizontal, 8)
        }
    }

    private var mirror: some View {
        Button { editor.mirror.toggle() } label: {
            Image(systemName: "arrow.left.and.right.righttriangle.left.righttriangle.right")
                .font(.body)
                .foregroundStyle(editor.mirror ? accent : OverlayTheme.textSecondary)
                .frame(width: 44, height: 44)
                .background(editor.mirror ? accent.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .contentShape(Rectangle())
        }
        .disabled(editor.trying)
        .accessibilityLabel(Text("Mirror"))
        .accessibilityValue(Text(editor.mirror ? "On" : "Off"))
    }

    private var more: some View {
        glyph("ellipsis", label: String(localized: "More"), action: onMore).disabled(editor.trying)
    }

    private var done: some View {
        Button(action: onDone) {
            Text("Done").font(.headline).foregroundStyle(.white)
                .padding(.horizontal, 12).frame(height: 44)
                .background(accent, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    private func glyph(_ name: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.body).foregroundStyle(OverlayTheme.textPrimary)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .accessibilityLabel(Text(label))
    }

    // In the pad table's order, then the sticks.
    private var heldLine: String {
        let held = PadTouches.shared.held
        var names = VirtualControllerView.table.filter { held.contains($0.button) }.map { control in
            PadPiece(control.button)?.title ?? DeviceTables.buttonName(control.name == "ps" ? "ps" : control.label)
        }
        if PadTouches.shared.heldSticks.contains(true) { names.append(PadPiece.lstick.title) }
        if PadTouches.shared.heldSticks.contains(false) { names.append(PadPiece.rstick.title) }
        return names.isEmpty ? String(localized: "Press a button to try it.")
            : String(localized: "Held: \(names.joined(separator: ", "))")
    }
}

// MARK: - Piece panel

// A slim card of two rows, so it fits the pad's empty space and leaves every other piece in reach.
private struct PadPiecePanel: View {
    @Bindable var editor: PadEditor
    let piece: PadPiece
    @Environment(\.uiAccentColour) private var accent

    var body: some View {
        let placement = editor.current[piece]
        let floating = editor.current.floating.contains(piece)
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(piece.title)
                    .font(.headline)
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                // A tap on empty canvas closes it too, but nothing on screen says so.
                Button { editor.selected = nil } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(width: 44, height: 44, alignment: .trailing)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(Text("Close"))
            }
            .padding(.leading, 4)
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow {
                    stepper("Size", placement.size) { editor.step(piece, by: $0 * PadPlacement.step) }
                    if piece.isStick {
                        stepper("Opacity", placement.opacity) { editor.stepOpacity(piece, by: $0 * 0.1) }
                    } else {
                        stepper("Touch Area", placement.touch) { editor.stepTouch(piece, by: $0 * 0.1) }
                    }
                }
                GridRow {
                    if piece.isStick {
                        tile("Floating", spoken: "Floating Stick", piece == .lstick ? "l.joystick" : "r.joystick", on: floating) {
                            editor.setFloating(piece, !floating)
                        }
                        .accessibilityHint(Text("The stick appears where your thumb lands in its zone and goes back home when you let go."))
                    } else {
                        stepper("Opacity", placement.opacity) { editor.stepOpacity(piece, by: $0 * 0.1) }
                    }
                    HStack(spacing: 4) {
                        if placement.hidden {
                            tile("Show", "eye.slash", on: true) { editor.toggleHidden(piece) }
                        } else {
                            tile("Hide", "eye") { editor.toggleHidden(piece) }
                        }
                        if !piece.members.isEmpty {
                            tile("Split", "square.split.2x2") { editor.split(piece) }
                        } else if let cluster = piece.cluster {
                            tile("Join", "arrow.down.forward.and.arrow.up.backward") { editor.join(cluster) }
                        }
                        tile("Reset", spoken: "Reset This Piece", "arrow.counterclockwise") { editor.resetPiece(piece) }
                    }
                }
            }
            overlapNote
        }
        .padding(12)
        .buttonStyle(.plain)
        .padEditorSurface()
        .shadow(color: .black.opacity(0.28), radius: 22, y: 12)
    }

    @ViewBuilder private var overlapNote: some View {
        let others = editor.overlaps(of: piece)
        if editor.current[piece].hidden || !others.isEmpty {
            Text(editor.current[piece].hidden ? String(localized: "Hidden while you play. Tap Show to use it.")
                 : others.count == 1 ? String(localized: "Its touch area overlaps \(others[0].title).")
                 : String(localized: "Its touch area overlaps \(others.count) other buttons."))
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .padding(.horizontal, 4)
                .frame(width: 320, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // A caption over the value and the quick menu's −/+ glyphs, which repeat while held.
    private func stepper(_ label: LocalizedStringResource, _ value: CGFloat, step: @escaping (CGFloat) -> Void) -> some View {
        let name = String(localized: label)
        let percent = "\(Int((value * 100).rounded()))%"
        return VStack(alignment: .leading, spacing: 0) {
            Text(name)
                .overlayCaption()
                .lineLimit(1)
                .padding(.leading, 4)
                .accessibilityHidden(true)
            HStack(spacing: 2) {
                Text(verbatim: percent)
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .monospacedDigit()
                    .frame(minWidth: 52, alignment: .leading)
                    .padding(.leading, 4)
                    .accessibilityHidden(true)
                glyph("minus.circle.fill", String(localized: "Lower \(name)")) { step(-1) }
                glyph("plus.circle.fill", String(localized: "Raise \(name)")) { step(1) }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: "\(name), \(percent)"))
        .controllerAccessibilityAdjustableTarget(label: name, value: percent, onActivate: {},
                                                 onIncrement: { step(1) }, onDecrement: { step(-1) })
    }

    private func glyph(_ symbol: String, _ name: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(accent)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonRepeatBehavior(.enabled)
        .accessibilityLabel(Text(name))
    }

    // An icon over a short word. With `on`, a toggle in the bar's Mirror look: accent on a faint accent fill.
    private func tile(_ label: LocalizedStringResource, spoken: LocalizedStringResource? = nil, _ symbol: String, on: Bool? = nil,
                      run: @escaping () -> Void) -> some View {
        let lit = on ?? false
        return Button(action: run) {
            VStack(spacing: 2) {
                Image(systemName: symbol).font(.body).frame(height: 22)
                Text(label).font(.caption2).lineLimit(1)
            }
            .foregroundStyle(lit ? accent : OverlayTheme.textPrimary)
            .frame(minWidth: 52, minHeight: 48)
            .background(lit ? accent.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .accessibilityLabel(Text(spoken ?? label))
        .accessibilityValue(on.map { Text($0 ? "On" : "Off") } ?? Text(verbatim: ""))
        .controllerAccessibilityActionTarget(label: String(localized: spoken ?? label), action: run)
    }
}

// MARK: - Marks on the canvas

// Touch areas, overlaps, guides, the grid and floating-stick zones; none of them takes a touch.
private struct PadEditorMarks: View {
    let editor: PadEditor
    let accent: Color
    @Environment(\.uiCriticalTextColour) private var critical

    var body: some View {
        if !editor.trying {
            ZStack(alignment: .topLeading) {
                if editor.grid { grid }
                zones
                touchAreas
                guides
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .accessibilityHidden(true)
        }
    }

    private var grid: some View {
        Path { path in
            let size = editor.area
            for x in stride(from: CGFloat(0), through: size.width, by: 8) {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: CGFloat(0), through: size.height, by: 8) {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
            }
        }
        .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
    }

    private var zones: some View {
        ForEach(Array(editor.current.floating).sorted { $0.rawValue < $1.rawValue }, id: \.self) { stick in
            let share = editor.current.zone(stick)
            let rect = CGRect(x: share.minX * editor.area.width, y: share.minY * editor.area.height,
                              width: share.width * editor.area.width, height: share.height * editor.area.height)
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(accent.opacity(0.06))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(accent.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [6, 4])))
                .overlay(alignment: stick == .lstick ? .topLeading : .topTrailing) {
                    Text(stick == .lstick ? "Left Stick Zone" : "Right Stick Zone").overlayCaption().padding(8)
                }
                .frame(width: rect.width, height: rect.height)
                .position(x: rect.midX, y: rect.midY)
        }
    }

    @ViewBuilder private var touchAreas: some View {
        let overlapping = editor.overlapping
        // The selected piece's area shows when it differs from its outline: grown, shrunk, or overlapping.
        let shown = editor.current.movable.filter { piece in
            !editor.current[piece].hidden && (editor.showTouch || piece == editor.selected
                && (editor.current[piece].touch != 1 || overlapping.contains(piece)))
        }
        ForEach(shown, id: \.self) { piece in
            if let region = editor.touchRegion(piece) {
                let red = overlapping.contains(piece)
                RoundedRectangle(cornerRadius: 8)
                    .stroke(red ? critical : accent.opacity(piece == editor.selected ? 1 : 0.5),
                            style: StrokeStyle(lineWidth: red ? 1.5 : 1, dash: [6, 4]))
                    .frame(width: region.width, height: region.height)
                    .position(x: region.midX, y: region.midY)
                // Each shared patch once.
                ForEach(editor.overlaps(of: piece).filter { !shown.contains($0) || piece.rawValue < $0.rawValue }, id: \.self) { other in
                    if let shared = editor.touchRegion(other)?.intersection(region), !shared.isNull {
                        Rectangle().fill(critical.opacity(0.2))
                            .frame(width: shared.width, height: shared.height)
                            .position(x: shared.midX, y: shared.midY)
                    }
                }
            }
        }
    }

    private var guides: some View {
        ForEach(Array(editor.guides), id: \.self) { guide in
            Rectangle()
                .fill(accent)
                .frame(width: guide.axis == .x ? 1 : editor.area.width, height: guide.axis == .y ? 1 : editor.area.height)
                .position(x: guide.axis == .x ? guide.at : editor.area.width / 2,
                          y: guide.axis == .y ? guide.at : editor.area.height / 2)
        }
    }
}

// MARK: - One piece in the editor

// Laid over a piece's drawn frame: its grab area, outlines and corner handle.
struct PadPieceEditOverlay: View {
    let piece: PadPiece
    let placement: PadPlacement
    @Environment(\.padEditor) private var editor
    @Environment(\.padSlot) private var slot
    @Environment(\.uiAccentColour) private var accent
    // SwiftUI resets these on end and on cancellation, and onEnded alone misses a cancel.
    @GestureState private var holding = false
    @GestureState private var pinching = false
    @GestureState private var stretching = false

    var body: some View {
        if let editor, slot.movable.contains(piece) {
            let selected = editor.selected == piece
            let partner = editor.mirror && editor.selected.flatMap { editor.mirrorPartner($0) } == piece
            ZStack {
                if placement.hidden {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(Color.white.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
                if selected || partner {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(accent.opacity(partner ? 0.5 : 1), style: StrokeStyle(lineWidth: 1, dash: partner ? [4, 4] : []))
                        .padding(-4)
                }
                // At least 44 pt to grab, however small the piece.
                Color.clear
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .named(VirtualControllerView.space))
                            .updating($holding) { _, holding, _ in holding = true }
                            .onChanged { editor.drag(piece, by: $0.translation) }
                    )
                    .simultaneousGesture(
                        MagnifyGesture()
                            .updating($pinching) { _, pinching, _ in pinching = true }
                            .onChanged { editor.resize(piece, by: $0.magnification) }
                    )
                if selected { handle(editor) }
            }
            .onChange(of: holding || pinching || stretching) { _, active in
                if !active { editor.endGesture(piece) }
            }
            .accessibilityElement()
            .accessibilityLabel(Text(placement.hidden ? String(localized: "\(piece.title), hidden") : piece.title))
            .accessibilityValue(Text(spoken(editor)))
            .accessibilityHint(Text("Double-tap to select."))
            .accessibilityAction { editor.selected = piece }
            .accessibilityAction(named: Text("Move Up")) { editor.nudge(piece, dx: 0, dy: -8) }
            .accessibilityAction(named: Text("Move Down")) { editor.nudge(piece, dx: 0, dy: 8) }
            .accessibilityAction(named: Text("Move Left")) { editor.nudge(piece, dx: -8, dy: 0) }
            .accessibilityAction(named: Text("Move Right")) { editor.nudge(piece, dx: 8, dy: 0) }
            .accessibilityAction(named: Text("Make Bigger")) { editor.step(piece, by: PadPlacement.step) }
            .accessibilityAction(named: Text("Make Smaller")) { editor.step(piece, by: -PadPlacement.step) }
        }
    }

    // The bottom-trailing corner, or the one facing the pad's middle when that corner is near an edge.
    private func handle(_ editor: PadEditor) -> some View {
        GeometryReader { geometry in
            let frame = geometry.frame(in: .named(VirtualControllerView.space))
            let nearRight = frame.maxX + 4 > editor.area.width - 44
            let nearBottom = frame.maxY + 4 > editor.area.height - 44
            let corner = CGPoint(x: nearRight ? -4 : geometry.size.width + 4, y: nearBottom ? -4 : geometry.size.height + 4)
            Circle()
                .fill(accent)
                .overlay(Circle().stroke(OverlayTheme.shell, lineWidth: 2))
                .frame(width: 14, height: 14)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .position(corner)
                .gesture(
                    DragGesture(minimumDistance: 0, coordinateSpace: .named(VirtualControllerView.space))
                        .updating($stretching) { _, stretching, _ in stretching = true }
                        .onChanged { value in
                            let centre = CGPoint(x: frame.midX, y: frame.midY)
                            let start = hypot(value.startLocation.x - centre.x, value.startLocation.y - centre.y)
                            let now = hypot(value.location.x - centre.x, value.location.y - centre.y)
                            if start > 1 { editor.resize(piece, by: now / start) }
                        }
                )
                .accessibilityHidden(true)
        }
    }

    private func spoken(_ editor: PadEditor) -> String {
        guard let frame = editor.frames[piece], editor.area.width > 0 else { return "" }
        let column = min(2, Int(frame.midX / editor.area.width * 3)), row = min(2, Int(frame.midY / editor.area.height * 3))
        let spots = [[String(localized: "top left"), String(localized: "top"), String(localized: "top right")],
                     [String(localized: "left"), String(localized: "centre"), String(localized: "right")],
                     [String(localized: "bottom left"), String(localized: "bottom"), String(localized: "bottom right")]]
        return "\(spots[row][column]), \(Int((placement.size * 100).rounded()))%"
    }
}

// The selected floating stick's zone resizes from its corner nearest the pad's middle.
private struct PadZoneHandle: View {
    let editor: PadEditor
    let stick: PadPiece
    @Environment(\.uiAccentColour) private var accent
    @GestureState private var dragging = false
    @State private var started = false

    var body: some View {
        let share = editor.current.zone(stick)
        let corner = CGPoint(x: (stick == .lstick ? share.maxX : share.minX) * editor.area.width, y: share.minY * editor.area.height)
        Circle()
            .fill(accent)
            .overlay(Circle().stroke(OverlayTheme.shell, lineWidth: 2))
            .frame(width: 14, height: 14)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .position(corner)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named(PadLayoutEditorView.space))
                    .updating($dragging) { _, dragging, _ in dragging = true }
                    .onChanged { value in
                        editor.moveZoneCorner(stick, to: value.location, first: !started)
                        started = true
                    }
            )
            .onChange(of: dragging) { _, down in if !down { started = false } }
            .accessibilityHidden(true)
    }
}

// Opaque, the overlay theme's Reduce Transparency surface: glass would blur the pieces under it into smudges.
private struct PadEditorSurface: ViewModifier {
    @Environment(\.uiAccentColour) private var accent

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 26, style: .continuous)
        content
            .dynamicTypeSize(...DynamicTypeSize.accessibility3)
            .tint(accent)
            .background(LinearGradient(colors: [OverlayTheme.shellGradientTop, OverlayTheme.shell], startPoint: .top,
                                       endPoint: .bottom), in: shape)
            .overlay(shape.stroke(OverlayTheme.cardTopHighlight, lineWidth: 1))
            .clipShape(shape)
    }
}

private extension View {
    func padEditorSurface() -> some View { modifier(PadEditorSurface()) }
}
