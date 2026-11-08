// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import CoreGraphics
import Foundation

// One movable thing on the touch pad. A cluster moves as one until it is split into its four buttons.
enum PadPiece: String, Codable, CaseIterable, CodingKeyRepresentable {
    case dpad, face
    case up, down, left, right
    case triangle, circle, square, cross
    case l1, l2, r1, r2
    case lstick, rstick, l3, r3
    case select, ps, start

    static let dpadButtons: [PadPiece] = [.up, .down, .left, .right]
    static let faceButtons: [PadPiece] = [.triangle, .circle, .square, .cross]

    var members: [PadPiece] {
        switch self {
        case .dpad: return Self.dpadButtons
        case .face: return Self.faceButtons
        default: return []
        }
    }

    // The cluster a split button belongs to.
    var cluster: PadPiece? {
        if Self.dpadButtons.contains(self) { return .dpad }
        if Self.faceButtons.contains(self) { return .face }
        return nil
    }

    // Its mirror image across the pad's middle, for Mirror.
    var partner: PadPiece? {
        switch self {
        case .dpad: return .face
        case .face: return .dpad
        case .left: return .circle
        case .circle: return .left
        case .right: return .square
        case .square: return .right
        case .up: return .triangle
        case .triangle: return .up
        case .down: return .cross
        case .cross: return .down
        case .l1: return .r1
        case .r1: return .l1
        case .l2: return .r2
        case .r2: return .l2
        case .lstick: return .rstick
        case .rstick: return .lstick
        case .l3: return .r3
        case .r3: return .l3
        case .select: return .start
        case .start: return .select
        case .ps: return nil
        }
    }

    var isStick: Bool { self == .lstick || self == .rstick }
}

// How a piece differs from where the default pad puts it. A fresh value is the default pad.
struct PadPlacement: Codable, Equatable {
    // Its centre as a share of the pad's width and height; nil keeps the default spot.
    var centre: CGPoint?
    var size: CGFloat = 1
    var touch: CGFloat = 1
    // A share of the global Button Opacity.
    var opacity: CGFloat = 1
    var hidden = false

    static let sizes: ClosedRange<CGFloat> = 0.25...4
    static let touches: ClosedRange<CGFloat> = 0.5...3
    static let opacities: ClosedRange<CGFloat> = 0.1...1
    static let step: CGFloat = 0.05

    // Their own buttons, L3 and R3, start hidden: a tap on the stick already clicks it.
    static func standard(_ piece: PadPiece) -> PadPlacement {
        var placement = PadPlacement()
        placement.hidden = piece == .l3 || piece == .r3
        return placement
    }

    static func stepped(_ value: CGFloat, in range: ClosedRange<CGFloat>) -> CGFloat {
        guard value.isFinite else { return 1 }
        return min(max((value / step).rounded() * step, range.lowerBound), range.upperBound)
    }

    // Brings a value read from a file back inside what the editor can make.
    func clamped() -> PadPlacement {
        var copy = self
        if let centre {
            copy.centre = centre.x.isFinite && centre.y.isFinite
                ? CGPoint(x: min(max(centre.x, 0), 1), y: min(max(centre.y, 0), 1)) : nil
        }
        copy.size = Self.stepped(size, in: Self.sizes)
        copy.touch = Self.stepped(touch, in: Self.touches)
        copy.opacity = Self.stepped(opacity, in: Self.opacities)
        return copy
    }
}

enum PadSlot: String, Codable, CaseIterable {
    case portrait, landscape, book
}

// One orientation's arrangement.
struct PadSlotLayout: Codable, Equatable {
    var pieces: [PadPiece: PadPlacement] = [:]
    var split: Set<PadPiece> = []
    var floating: Set<PadPiece> = []
    // A floating stick's touch zone, as shares of the pad; nil is the stick's half of the lower pad.
    var zones: [PadPiece: CGRect] = [:]

    subscript(piece: PadPiece) -> PadPlacement {
        get { pieces[piece] ?? .standard(piece) }
        set { pieces[piece] = newValue == .standard(piece) ? nil : newValue }
    }

    func isSplit(_ cluster: PadPiece) -> Bool { split.contains(cluster) }

    // The pieces drawn and moved on their own now: a split cluster gives way to its buttons.
    var movable: [PadPiece] {
        PadPiece.allCases.filter { piece in
            if let cluster = piece.cluster { return split.contains(cluster) }
            if !piece.members.isEmpty { return !split.contains(piece) }
            return true
        }
    }

    static func defaultZone(_ stick: PadPiece) -> CGRect {
        stick == .lstick ? CGRect(x: 0, y: 0.5, width: 0.5, height: 0.5) : CGRect(x: 0.5, y: 0.5, width: 0.5, height: 0.5)
    }

    func zone(_ stick: PadPiece) -> CGRect { zones[stick] ?? Self.defaultZone(stick) }

    func clamped() -> PadSlotLayout {
        var copy = self
        copy.pieces = pieces.mapValues { $0.clamped() }
        copy.split = split.filter { !$0.members.isEmpty }
        copy.floating = floating.filter(\.isStick)
        copy.zones = zones.filter { $0.key.isStick }.compactMapValues { rect in
            let unit = CGRect(x: 0, y: 0, width: 1, height: 1)
            let inside = rect.standardized.intersection(unit)
            return inside.isNull || inside.width < 0.05 || inside.height < 0.05 ? nil : inside
        }
        return copy
    }
}

struct PadLayout: Codable, Equatable {
    var portrait = PadSlotLayout()
    var landscape = PadSlotLayout()
    var book = PadSlotLayout()

    subscript(slot: PadSlot) -> PadSlotLayout {
        get {
            switch slot {
            case .portrait: return portrait
            case .landscape: return landscape
            case .book: return book
            }
        }
        set {
            switch slot {
            case .portrait: portrait = newValue
            case .landscape: landscape = newValue
            case .book: book = newValue
            }
        }
    }

    func clamped() -> PadLayout {
        PadLayout(portrait: portrait.clamped(), landscape: landscape.clamped(), book: book.clamped())
    }
}

struct PadLayoutPreset: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var layout: PadLayout
    // The skin that added it, so reinstalling that skin updates it and never a preset of the player's.
    var skin: String?
}

// Which layout a game uses. Default is the stock pad and never changes; My Layout is the player's shared one.
enum PadLayoutChoice: Hashable, Codable, RawRepresentable {
    case standard
    case mine
    case preset(String)
    // This game's own layout.
    case game

    init?(rawValue: String) {
        switch rawValue {
        case "default": self = .standard
        case "mine": self = .mine
        case "game": self = .game
        default:
            guard rawValue.hasPrefix("preset:") else { return nil }
            self = .preset(String(rawValue.dropFirst("preset:".count)))
        }
    }

    var rawValue: String {
        switch self {
        case .standard: return "default"
        case .mine: return "mine"
        case .game: return "game"
        case .preset(let id): return "preset:" + id
        }
    }
}

// Everything the pad's layouts keep, saved as one JSON file.
struct PadLayoutLibrary: Codable, Equatable {
    static let version = 1

    var version = Self.version
    var mine: PadLayout?
    // What every game without a layout of its own uses: Default, My Layout or a preset.
    var shared = PadLayoutChoice.standard
    var presets: [PadLayoutPreset] = []
    // A game's own layout, by serial. It is kept while the game uses another, so it can be picked again.
    var games: [String: PadLayout] = [:]
    // What a game uses instead of the shared layout, by serial; `.game` is its own layout.
    var pinned: [String: PadLayoutChoice] = [:]

    static func key(_ serial: String?) -> String? {
        let cleaned = (serial ?? "").replacingOccurrences(of: "_", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return cleaned.isEmpty ? nil : cleaned
    }

    func exists(_ choice: PadLayoutChoice, serial: String? = nil) -> Bool {
        switch choice {
        case .standard: return true
        case .mine: return mine != nil
        case .preset(let id): return presets.contains { $0.id == id }
        case .game: return Self.key(serial).flatMap { games[$0] } != nil
        }
    }

    var sharedChoice: PadLayoutChoice { exists(shared) && shared != .game ? shared : .standard }

    func choice(for serial: String?) -> PadLayoutChoice {
        guard let key = Self.key(serial), let pin = pinned[key] else { return sharedChoice }
        if pin == .game { return games[key] != nil ? .game : sharedChoice }
        return exists(pin) ? pin : sharedChoice
    }

    func layout(for serial: String?) -> PadLayout {
        layout(of: choice(for: serial), serial: serial)
    }

    func layout(of choice: PadLayoutChoice, serial: String?) -> PadLayout {
        switch choice {
        case .standard: return PadLayout()
        case .mine: return mine ?? PadLayout()
        case .preset(let id): return presets.first { $0.id == id }?.layout ?? PadLayout()
        case .game: return Self.key(serial).flatMap { games[$0] } ?? layout(of: sharedChoice, serial: nil)
        }
    }

    func name(of choice: PadLayoutChoice) -> String {
        switch choice {
        case .standard: return String(localized: "Default")
        case .mine: return String(localized: "My Layout")
        case .game: return String(localized: "This Game's Layout")
        case .preset(let id): return presets.first { $0.id == id }?.name ?? String(localized: "Default")
        }
    }

    // What the player picks for a game outside the editor; nil follows the shared layout again.
    mutating func pin(_ serial: String?, to choice: PadLayoutChoice?) {
        guard let key = Self.key(serial) else { return }
        pinned[key] = choice == .game && games[key] == nil ? nil : choice
    }

    // Done in the editor, for the layout it opened. Default is never written; the Layouts list passes `makeShared` false.
    mutating func save(_ layout: PadLayout, edited: PadLayoutChoice, changed: Bool, serial: String?, onlyThisGame: Bool,
                       makeShared: Bool = true) {
        let key = Self.key(serial)
        if onlyThisGame, let key {
            if changed || edited == .game {
                games[key] = layout
                pinned[key] = .game
            } else {
                pinned[key] = edited
            }
            return
        }
        if let key { pinned[key] = nil }
        var target = edited
        switch edited {
        case .standard:
            if changed {
                mine = layout
                target = .mine
            }
        case .mine, .game:
            mine = layout
            target = .mine
        case .preset(let id):
            if let index = presets.firstIndex(where: { $0.id == id }) {
                presets[index].layout = layout
            } else {
                mine = layout
                target = .mine
            }
        }
        if makeShared { shared = target }
    }

    @discardableResult
    mutating func addPreset(named name: String, layout: PadLayout, id: String = UUID().uuidString) -> PadLayoutPreset {
        let preset = PadLayoutPreset(id: id, name: uniqueName(Self.cleanName(name, fallback: "Layout")), layout: layout.clamped())
        presets.append(preset)
        return preset
    }

    func preset(named name: String) -> PadLayoutPreset? {
        presets.first { $0.name.lowercased() == Self.cleanName(name, fallback: "").lowercased() }
    }

    // Replace in "Replace or Keep Both".
    mutating func replacePreset(named name: String, with layout: PadLayout) -> PadLayoutPreset? {
        guard let match = preset(named: name), let index = presets.firstIndex(where: { $0.id == match.id }) else { return nil }
        presets[index].layout = layout.clamped()
        return presets[index]
    }

    mutating func rename(_ id: String, to name: String) {
        guard let index = presets.firstIndex(where: { $0.id == id }) else { return }
        let cleaned = Self.cleanName(name, fallback: presets[index].name)
        if cleaned.lowercased() != presets[index].name.lowercased() { presets[index].name = uniqueName(cleaned) }
        else { presets[index].name = cleaned }
    }

    @discardableResult
    mutating func duplicate(_ choice: PadLayoutChoice) -> PadLayoutPreset {
        addPreset(named: name(of: choice), layout: layout(of: choice, serial: nil))
    }

    // Games that used it follow the shared layout again, and a shared preset hands over to Default.
    mutating func delete(_ id: String) {
        presets.removeAll { $0.id == id }
        pinned = pinned.filter { $0.value != .preset(id) }
        if shared == .preset(id) { shared = .standard }
    }

    mutating func deleteMine() {
        mine = nil
        pinned = pinned.filter { $0.value != .mine }
        if shared == .mine { shared = .standard }
    }

    // Default, My Layout once there is one, then the presets by name.
    var choices: [PadLayoutChoice] {
        [.standard] + (mine != nil ? [.mine] : [])
            + presets.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }.map { .preset($0.id) }
    }

    // What Done for all games writes over, when that is not the layout being edited.
    func saveTarget(edited: PadLayoutChoice, changed: Bool) -> PadLayoutChoice? {
        switch edited {
        case .standard: return changed ? .mine : nil
        case .mine: return nil
        case .game: return .mine
        case .preset: return exists(edited) ? nil : .mine
        }
    }

    // A skin's layout goes into the preset that skin added before, or a new one named after it.
    mutating func installSkinLayout(_ skin: String, layout: PadLayout) -> PadLayoutPreset {
        if let index = presets.firstIndex(where: { $0.skin == skin }) {
            presets[index].layout = layout.clamped()
            return presets[index]
        }
        addPreset(named: skin, layout: layout)
        presets[presets.count - 1].skin = skin
        return presets[presets.count - 1]
    }

    // Default and My Layout count as taken, so a preset never reads like one of them.
    func uniqueName(_ name: String) -> String {
        let taken = Set(presets.map { $0.name.lowercased() }
            + [String(localized: "Default").lowercased(), String(localized: "My Layout").lowercased()])
        guard taken.contains(name.lowercased()) else { return name }
        var number = 2
        while taken.contains("\(name) \(number)".lowercased()) { number += 1 }
        return "\(name) \(number)"
    }

    static func cleanName(_ name: String, fallback: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : String(trimmed.prefix(40))
    }

    // Unknown presets are dropped, so a hand-edited file cannot point at nothing.
    func clamped() -> PadLayoutLibrary {
        var copy = self
        copy.version = Self.version
        copy.mine = mine?.clamped()
        copy.presets = presets.map { PadLayoutPreset(id: $0.id, name: Self.cleanName($0.name, fallback: "Layout"), layout: $0.layout.clamped()) }
        copy.games = games.mapValues { $0.clamped() }
        copy.pinned = pinned.filter { $0.value == .game ? copy.games[$0.key] != nil : copy.exists($0.value) }
        copy.shared = copy.sharedChoice
        return copy
    }
}

// The shareable file: one layout and its name, nothing about skins.
enum PadLayoutFile {
    static let fileExtension = "armsx3pad"
    static let version = 1

    private struct Payload: Codable {
        var format = "armsx3pad"
        var version = PadLayoutFile.version
        var name: String
        var layout: PadLayout
    }

    struct Refusal: Error {}

    static func encode(name: String, layout: PadLayout) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(Payload(name: name, layout: layout))
    }

    // A newer version or another format is refused rather than half read.
    static func decode(_ data: Data, fallbackName: String?) throws -> (name: String, layout: PadLayout) {
        guard data.count <= 1 << 20, let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.format == "armsx3pad", payload.version <= version else { throw Refusal() }
        let stem = fallbackName.map { URL(fileURLWithPath: $0).deletingPathExtension().lastPathComponent }
        let name = PadLayoutLibrary.cleanName(payload.name, fallback: PadLayoutLibrary.cleanName(stem ?? "", fallback: "Imported Layout"))
        return (name, payload.layout.clamped())
    }

    static func fileName(for name: String) -> String {
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.newlines).union(.controlCharacters)
        let stem = name.components(separatedBy: invalid).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return (stem.isEmpty ? "Layout" : stem) + "." + fileExtension
    }
}

// Saved files are read field by field with defaults, so a field added later, or a piece or choice this build does
// not know, costs only that value and never the player's whole library or a shared file.
extension PadPlacement {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init()
        centre = try? values.decodeIfPresent(CGPoint.self, forKey: .centre)
        size = (try? values.decode(CGFloat.self, forKey: .size)) ?? 1
        touch = (try? values.decode(CGFloat.self, forKey: .touch)) ?? 1
        opacity = (try? values.decode(CGFloat.self, forKey: .opacity)) ?? 1
        hidden = (try? values.decode(Bool.self, forKey: .hidden)) ?? false
    }
}

extension PadSlotLayout {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init()
        pieces = Self.known((try? values.decode([String: PadPlacement].self, forKey: .pieces)) ?? [:])
        split = Set(((try? values.decode([String].self, forKey: .split)) ?? []).compactMap(PadPiece.init(rawValue:)))
        floating = Set(((try? values.decode([String].self, forKey: .floating)) ?? []).compactMap(PadPiece.init(rawValue:)))
        zones = Self.known((try? values.decode([String: CGRect].self, forKey: .zones)) ?? [:])
    }

    private static func known<Value>(_ raw: [String: Value]) -> [PadPiece: Value] {
        Dictionary(raw.compactMap { key, value in PadPiece(rawValue: key).map { ($0, value) } }, uniquingKeysWith: { first, _ in first })
    }
}

extension PadLayout {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init()
        portrait = (try? values.decode(PadSlotLayout.self, forKey: .portrait)) ?? PadSlotLayout()
        landscape = (try? values.decode(PadSlotLayout.self, forKey: .landscape)) ?? PadSlotLayout()
        book = (try? values.decode(PadSlotLayout.self, forKey: .book)) ?? PadSlotLayout()
    }
}

extension PadLayoutPreset {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: (try? values.decode(String.self, forKey: .id)) ?? UUID().uuidString,
                  name: (try? values.decode(String.self, forKey: .name)) ?? "Layout",
                  layout: (try? values.decode(PadLayout.self, forKey: .layout)) ?? PadLayout(),
                  skin: try? values.decodeIfPresent(String.self, forKey: .skin))
    }
}

extension PadLayoutLibrary {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init()
        version = (try? values.decode(Int.self, forKey: .version)) ?? Self.version
        mine = try? values.decodeIfPresent(PadLayout.self, forKey: .mine)
        shared = (try? values.decode(String.self, forKey: .shared)).flatMap(PadLayoutChoice.init(rawValue:)) ?? .standard
        presets = (try? values.decode([PadLayoutPreset].self, forKey: .presets)) ?? []
        games = (try? values.decode([String: PadLayout].self, forKey: .games)) ?? [:]
        pinned = ((try? values.decode([String: String].self, forKey: .pinned)) ?? [:]).compactMapValues(PadLayoutChoice.init(rawValue:))
    }
}
