// SPDX-License-Identifier: GPL-2.0-only
// The XMB's state and navigation, after Android's XmbModel.kt. No UI here, so the host suite can drive it.

import Foundation

enum XmbIcon: Equatable {
    /// An image from the firmware's `rco` (no extension), with an SF Symbol for when there is no firmware.
    case firmware(rco: String, name: String, symbol: String)
    /// A game's ICON0, or the disc symbol without one.
    case game(path: String?)
    case symbol(String)
    /// A template image in the app's asset catalog, drawn as a symbol is.
    case asset(String)
    /// The ARMSX3 mark, for the app's own items.
    case mark
    /// The mark on its purple disc, where the console had its PlayStation Network badge.
    case badge
    /// A picture from the web, a GitHub avatar say, with the mark until it arrives or when it cannot.
    case remote(URL)
    case none
}

struct XmbNode: Identifiable, Equatable {
    enum Kind: Equatable {
        /// Runs something the home decides on.
        case action
        /// Opens its children beside it.
        case folder([XmbNode])
        /// Launches.
        case game
        /// Edits a value in the side panel; `selected` indexes `choices`, nil when the value is none of them.
        case setting(choices: [String], selected: Int?)
        /// Shows its subtitle and nothing more.
        case info
        /// A number edited with a slider in the side panel, applied as it moves.
        case range(XmbRange)
    }

    struct Option: Equatable {
        let id: String
        let title: String
    }

    let id: String
    var title: String
    var subtitle: String?
    var icon: XmbIcon
    var kind: Kind
    var options: [Option] = []
    /// A game's PIC1, shown behind the XMB while the game is focused.
    var backdrop: String?
    /// A setting's full explanation, shown after its value on the focused row's second line.
    var description: String? = nil
    /// A setting changed from its default, tagged after its name.
    var changed = false
    /// A level drawn as a bar under the focused row, 0 to 1: a microphone's, say.
    var meter: Double? = nil
    /// A picture shown beside the open list while this row, or the folder it opens, is focused or open.
    var aside: XmbIcon? = nil
    /// A colour beside each of a setting's choices, red, green and blue from 0 to 1.
    var swatches: [SIMD3<Float>]? = nil

    var isGame: Bool { kind == .game }
    var children: [XmbNode]? {
        if case .folder(let items) = kind { return items }
        return nil
    }
}

struct XmbColumn: Identifiable, Equatable {
    let id: String
    var title: String
    var icon: XmbIcon
    var items: [XmbNode]
    /// The item focused on the first visit.
    var defaultFocus = 0
    /// Folders opened by a call (a game's settings, a search's matches), never drawn as rows.
    var hidden: [XmbNode] = []
}

/// A number's bounds, step and value, and the unit written after it.
struct XmbRange: Equatable {
    var min: Double
    var max: Double
    var step: Double
    var value: Double
    var unit = ""

    var text: String {
        (value.rounded() == value ? String(Int(value)) : String(value)) + unit
    }

    /// Where the value sits between the bounds, 0 to 1.
    var fraction: Double { max > min ? Swift.min(Swift.max((value - min) / (max - min), 0), 1) : 0 }

    func clamped(_ value: Double) -> Double { Swift.min(Swift.max(value, min), max) }
}

/// What a press asks the home to do. Navigation inside the XMB is handled here and reported as `.moved`.
enum XmbOutcome: Equatable {
    case none
    case moved
    case opened
    case closed
    /// Nothing to go to, or nothing to open.
    case refused
    case run(String)
    case launch(String)
    case option(node: String, option: String)
    case pick(node: String, choice: Int)
    case answer(dialog: String, button: Int)
    /// Circle at the top level with nothing open.
    case atRoot
    /// Square on a game: its own settings.
    case gameSettings(String)
    /// A range's new value, to apply now: it moves live.
    case adjusted(node: String, value: Double)
}

/// What a press or a touch asks for.
enum XmbAction: Equatable {
    case move(dx: Int, dy: Int)
    case page(Int)
    case confirm
    case back
    case options
    case column(Int)
    case focus(Int)
    case focusAndConfirm(Int)
    case focusAndOptions(Int)
    case panelRow(Int)
    case square
    /// A tap on the range editor's bar, 0 at its left end and 1 at its right.
    case rangeAt(Double)
}

/// The sound a press makes, by the firmware's name for it.
enum XmbSound: String, CaseIterable, Sendable {
    case cursor = "snd_cursor"
    case decide = "snd_decide"
    case cancel = "snd_cancel"
    case option = "snd_option"
    case error = "snd_error"
    case systemOK = "snd_system_ok"
}

struct XmbDialog: Equatable {
    let id: String
    let message: String
    let buttons: [String]
    var focus: Int
}

struct XmbState: Equatable {
    enum Panel: Equatable {
        case options(node: String, rows: [XmbNode.Option], focus: Int)
        case editor(node: String, choices: [String], focus: Int)
        case range(node: String, XmbRange)

        var node: String {
            switch self {
            case .options(let node, _, _), .editor(let node, _, _), .range(let node, _): return node
            }
        }

        var rowCount: Int {
            switch self {
            case .options(_, let rows, _): return rows.count
            case .editor(_, let choices, _): return choices.count
            case .range: return 0
            }
        }

        var focus: Int {
            switch self {
            case .options(_, _, let focus), .editor(_, _, let focus): return focus
            case .range: return 0
            }
        }

        func moved(to focus: Int) -> Panel {
            switch self {
            case .options(let node, let rows, _): return .options(node: node, rows: rows, focus: focus)
            case .editor(let node, let choices, _): return .editor(node: node, choices: choices, focus: focus)
            case .range: return self
            }
        }
    }

    /// Where the XMB was, to be saved and given back to `restore` on the next launch.
    struct Place: Codable, Equatable {
        var column: String?
        var focus: [String: Int]
    }

    /// A folder opened from the column or from another folder, found again by its id whenever the columns change.
    struct Level: Equatable {
        let node: String
        var focus: Int
    }

    private(set) var columns: [XmbColumn] = []
    private(set) var column = 0
    /// The focused item per column id; a column never visited starts on its first item.
    private(set) var focus: [String: Int] = [:]
    private(set) var levels: [Level] = []
    private(set) var panel: Panel?
    private(set) var dialog: XmbDialog?
    /// How many single moves the last L1 or R1 made, each with its tick.
    private(set) var pageMoves = 0

    static let pageStep = 5

    init(columns: [XmbColumn] = [], column: Int = 0) {
        self.columns = columns
        self.column = columns.indices.contains(column) ? column : 0
    }

    var current: XmbColumn? { columns.indices.contains(column) ? columns[column] : nil }
    var depth: Int { levels.count }
    var busy: Bool { panel != nil || dialog != nil }

    func focus(of column: XmbColumn) -> Int {
        min(max(focus[column.id] ?? column.defaultFocus, 0), max(column.items.count - 1, 0))
    }

    var place: Place { Place(column: current?.id, focus: focus) }

    /// Puts back a saved place; a column that is no longer there is skipped, and the focus is clamped as it is read.
    mutating func restore(_ place: Place) {
        for (id, index) in place.focus { focus[id] = index }
        if let id = place.column, let index = columns.firstIndex(where: { $0.id == id }) { column = index }
    }

    /// The items at `depth` (0 the column, n the n-th open folder), or nil once a folder is gone.
    func items(atDepth depth: Int) -> [XmbNode]? {
        guard var items = current?.items else { return nil }
        let hidden = current?.hidden ?? []
        for level in levels.prefix(depth) {
            guard let node = (items + hidden).first(where: { $0.id == level.node }), let children = node.children else { return nil }
            items = children
        }
        return items
    }

    func focusIndex(atDepth depth: Int) -> Int {
        guard let current else { return 0 }
        return depth == 0 ? focus(of: current) : levels[depth - 1].focus
    }

    /// The focused node of the innermost list.
    var focused: XmbNode? {
        guard let items = items(atDepth: depth), items.indices.contains(focusIndex(atDepth: depth)) else { return nil }
        return items[focusIndex(atDepth: depth)]
    }

    func node(_ id: String) -> XmbNode? {
        func find(_ items: [XmbNode]) -> XmbNode? {
            for item in items {
                if item.id == id { return item }
                if let children = item.children, let hit = find(children) { return hit }
            }
            return nil
        }
        for column in columns { if let hit = find(column.items + column.hidden) { return hit } }
        return nil
    }

    // MARK: - Keeping state across a rebuild

    /// New columns, with the focus kept on the same item ids where they still exist and open folders closed when gone.
    mutating func attach(_ columns: [XmbColumn]) {
        let old = self
        self.columns = columns
        if let id = old.current?.id, let index = columns.firstIndex(where: { $0.id == id }) {
            column = index
        } else {
            column = min(column, max(columns.count - 1, 0))
        }
        for newColumn in columns {
            guard let oldColumn = old.columns.first(where: { $0.id == newColumn.id }) else { continue }
            let at = old.focus(of: oldColumn)
            if oldColumn.items.indices.contains(at),
               let kept = newColumn.items.firstIndex(where: { $0.id == oldColumn.items[at].id }) {
                focus[newColumn.id] = kept
            }
        }
        var kept: [Level] = []
        for (depth, level) in levels.enumerated() {
            // A folder opened by a call lives in `hidden`, as items(atDepth:) reads it.
            guard let items = items(atDepth: depth), let node = (items + (current?.hidden ?? [])).first(where: { $0.id == level.node }),
                  let children = node.children, !children.isEmpty else { break }
            var level = level
            if let oldItems = old.items(atDepth: depth + 1), oldItems.indices.contains(level.focus),
               let same = children.firstIndex(where: { $0.id == oldItems[level.focus].id }) {
                level.focus = same
            }
            level.focus = min(level.focus, children.count - 1)
            kept.append(level)
        }
        levels = kept
        if let open = panel?.node, node(open) == nil { panel = nil }
    }

    // MARK: - Pad

    /// D-pad: across changes column at the top level and backs out of a folder to the left; up and down move focus.
    /// A corner sends both, and as on Android the column change or the way out comes before the focus move.
    mutating func move(dx: Int, dy: Int) -> XmbOutcome {
        if var dialog {
            let step = dx != 0 ? dx : dy
            let next = min(max(dialog.focus + step, 0), dialog.buttons.count - 1)
            guard next != dialog.focus else { return .refused }
            dialog.focus = next
            self.dialog = dialog
            return .moved
        }
        if case .range(let node, var range)? = panel {
            let delta = dx != 0 ? dx : -dy
            let next = range.clamped(range.value + Double(delta) * range.step)
            guard delta != 0, next != range.value else { return .refused }
            range.value = next
            panel = .range(node: node, range)
            return .adjusted(node: node, value: next)
        }
        if let panel {
            guard dy != 0 else { return .none }
            let next = min(max(panel.focus + dy, 0), panel.rowCount - 1)
            guard next != panel.focus else { return .refused }
            self.panel = panel.moved(to: next)
            return .moved
        }
        if depth > 0 {
            if dx < 0 { return back() }
            return dy != 0 ? setFocus(focusIndex(atDepth: depth) + dy) : .none
        }
        if dx != 0 { return selectColumn(column + dx) }
        if dy != 0 { return setFocus(focusIndex(atDepth: depth) + dy) }
        return .none
    }

    /// L1 and R1: five single moves, stopping at either end.
    mutating func page(_ direction: Int) -> XmbOutcome {
        pageMoves = 0
        guard !busy else { return .none }
        for _ in 0 ..< Self.pageStep {
            if setFocus(focusIndex(atDepth: depth) + direction) == .moved { pageMoves += 1 }
        }
        return pageMoves > 0 ? .moved : .refused
    }

    /// Another column; from inside a folder its lists close first, quietly when it is the same column.
    mutating func selectColumn(_ index: Int) -> XmbOutcome {
        guard columns.indices.contains(index), !busy else { return .refused }
        let wasOpen = depth > 0
        levels = []
        guard index != column else { return wasOpen ? .none : .refused }
        column = index
        return .moved
    }

    /// Focus on item `index` of the innermost list, clamped to it.
    mutating func setFocus(_ index: Int) -> XmbOutcome {
        guard let items = items(atDepth: depth), !items.isEmpty else { return .refused }
        let next = min(max(index, 0), items.count - 1)
        guard next != focusIndex(atDepth: depth) else { return .refused }
        if depth == 0, let current {
            focus[current.id] = next
        } else {
            levels[depth - 1].focus = next
        }
        return .moved
    }

    /// Focus on row `index` of the dialog or the panel, whichever is in front.
    mutating func setRowFocus(_ index: Int) {
        if var dialog {
            guard dialog.buttons.indices.contains(index) else { return }
            dialog.focus = index
            self.dialog = dialog
        } else if let panel, (0 ..< panel.rowCount).contains(index) {
            self.panel = panel.moved(to: index)
        }
    }

    /// Cross.
    mutating func confirm() -> XmbOutcome {
        if let dialog {
            self.dialog = nil
            return .answer(dialog: dialog.id, button: dialog.focus)
        }
        if let panel {
            self.panel = nil
            switch panel {
            case .options(let node, let rows, let focus): return .option(node: node, option: rows[focus].id)
            case .editor(let node, _, let focus): return .pick(node: node, choice: focus)
            case .range(let node, let range): return .adjusted(node: node, value: range.value)
            }
        }
        guard let node = focused else { return .refused }
        switch node.kind {
        // An inert row takes Cross with the decide sound and does nothing, as Android's empty actions do.
        case .action, .info: return .run(node.id)
        case .game: return .launch(node.id)
        case .range(let range):
            panel = .range(node: node.id, range)
            return .opened
        case .folder(let children):
            guard !children.isEmpty else { return .refused }
            levels.append(Level(node: node.id, focus: 0))
            return .opened
        case .setting(let choices, let selected):
            guard !choices.isEmpty else { return .refused }
            panel = .editor(node: node.id, choices: choices, focus: selected ?? 0)
            return .opened
        }
    }

    /// Circle: the dialog, then the panel, then the innermost folder.
    mutating func back() -> XmbOutcome {
        if let dialog {
            self.dialog = nil
            // The last button is the one that changes nothing.
            return .answer(dialog: dialog.id, button: dialog.buttons.count - 1)
        }
        if panel != nil {
            // A range was applied as it moved, so backing out keeps where it was left.
            panel = nil
            return .closed
        }
        if !levels.isEmpty {
            levels.removeLast()
            return .closed
        }
        return .atRoot
    }

    /// Triangle; silent while something is open, as on Android.
    mutating func openOptions() -> XmbOutcome {
        guard !busy else { return .none }
        guard let node = focused, !node.options.isEmpty else { return .refused }
        panel = .options(node: node.id, rows: node.options, focus: 0)
        return .opened
    }

    /// Square: the focused game's own settings; silent on anything else.
    mutating func square() -> XmbOutcome {
        guard !busy, let node = focused, node.isGame else { return .none }
        return .gameSettings(node.id)
    }

    /// A tap on the range editor's bar sets the value there, on a step.
    mutating func setRange(at fraction: Double) -> XmbOutcome {
        guard case .range(let node, var range)? = panel else { return .none }
        let raw = range.min + min(max(fraction, 0), 1) * (range.max - range.min)
        range.value = range.clamped((raw / range.step).rounded() * range.step)
        panel = .range(node: node, range)
        return .adjusted(node: node, value: range.value)
    }

    mutating func apply(_ action: XmbAction) -> XmbOutcome {
        switch action {
        case .move(let dx, let dy): return move(dx: dx, dy: dy)
        case .page(let direction): return page(direction)
        case .confirm: return confirm()
        case .back: return back()
        case .options: return openOptions()
        case .column(let index): return selectColumn(index)
        case .focus(let index): return setFocus(index)
        case .focusAndConfirm(let index):
            _ = setFocus(index)
            return confirm()
        case .focusAndOptions(let index):
            _ = setFocus(index)
            return openOptions()
        case .panelRow(let row):
            setRowFocus(row)
            return confirm()
        case .square: return square()
        case .rangeAt(let fraction): return setRange(at: fraction)
        }
    }

    /// The console's sound for what `action` did, or nil for silence. Call it after `apply`.
    func sound(for outcome: XmbOutcome, after action: XmbAction) -> XmbSound? {
        switch outcome {
        case .none: return nil
        case .moved: return .cursor
        // Running into the end of a list is silent; asking for something that is not there is not.
        case .refused:
            switch action {
            case .confirm, .options, .focusAndConfirm, .focusAndOptions: return .error
            default: return nil
            }
        case .opened:
            if case .options? = panel { return .option }
            return .decide
        case .closed, .atRoot: return .cancel
        case .run, .launch, .option, .pick, .gameSettings: return .decide
        // Any button picked is a decision; backing out is the cancel.
        case .answer: return action == .back ? .cancel : .decide
        case .adjusted:
            switch action {
            case .confirm, .panelRow: return .decide
            case .rangeAt: return nil
            default: return .cursor
            }
        }
    }

    /// Opens `id`, a folder in the open list or one of the column's hidden ones, as Cross on it would.
    mutating func open(_ id: String) -> XmbOutcome {
        guard !busy, let items = items(atDepth: depth),
              let node = (items + (current?.hidden ?? [])).first(where: { $0.id == id }),
              let children = node.children, !children.isEmpty else { return .refused }
        levels.append(Level(node: id, focus: 0))
        return .opened
    }

    /// Every sound for what `action` did: L1 and R1 tick once per item moved, as Android's five single moves do.
    func sounds(for outcome: XmbOutcome, after action: XmbAction) -> [XmbSound] {
        guard let sound = sound(for: outcome, after: action) else { return [] }
        if case .page = action, outcome == .moved { return Array(repeating: sound, count: pageMoves) }
        return [sound]
    }

    /// Asks, focused on `focus`. The last button must be the one that changes nothing. Answers the sound it opens with.
    @discardableResult
    mutating func ask(_ id: String, message: String, buttons: [String], focus: Int = 0) -> XmbSound {
        // A panel stays under the dialog, as on Android, so a late notice does not close an editor in use.
        dialog = XmbDialog(id: id, message: message, buttons: buttons, focus: min(max(focus, 0), buttons.count - 1))
        return .option
    }

    /// Tells `message` with one button, opening with the error sound, or the system's OK one for good news.
    @discardableResult
    mutating func tell(_ id: String, message: String, ok: String, error: Bool = true) -> XmbSound {
        ask(id, message: message, buttons: [ok])
        return error ? .error : .systemOK
    }
}
