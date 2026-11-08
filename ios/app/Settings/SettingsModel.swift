// SPDX-License-Identifier: GPL-2.0-only
// The core's settings tree as the bridge dumps it; no key is written here, so a new core leaf needs no Swift edit.

import Foundation

struct SettingLeaf: Codable, Equatable, Identifiable {
    var path: [String]
    var type: String
    var dynamic: Bool
    var defaultValue: String
    var global: String
    var db: String?
    var game: String?
    var forced: String?
    var variants: [String]
    var min: String
    var max: String
    var randomDefault: Bool
    var fix: Bool
    var liveId: Int?

    // `Input/Output` is one node name, so paths are joined with `@@`, never `/`.
    var id: String { path.joined(separator: SettingsModel.pathSeparator) }

    enum CodingKeys: String, CodingKey {
        case path, type, dynamic, global, db, game, forced, variants, min, max, randomDefault, fix, liveId
        case defaultValue = "default"
    }
}

struct SettingsDump: Codable {
    var version: Int
    var leaves: [SettingLeaf]
}

// Plain counts the bridge hands over; nothing is decoded into this.
struct SettingsImportCounts: Equatable {
    var kept, unknown, defaulted, locked: Int
}

// A setting the help join did not reach returns nil, never "", so the row shows its name alone.
struct SettingsHelp {
    private let texts: [String: String]

    // The port's iOS wording wins over RPCS3's desktop tooltip; the generated file stays upstream's byte for byte.
    init?(url: URL, overlay: URL? = nil) {
        guard let base = Self.read(url) else { return nil }
        texts = base.merging(overlay.flatMap(Self.read) ?? [:]) { $1 }
    }

    private static func read(_ url: URL) -> [String: String]? {
        guard let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data), file.version == 1 else { return nil }
        return file.help
    }

    func text(for path: [String]) -> String? {
        texts[path.joined(separator: SettingsModel.pathSeparator)]
    }

    private struct File: Decodable {
        var version: Int
        var help: [String: String]
    }
}

enum SettingsModel {
    static let pathSeparator = "@@"

    static func decode(_ data: Data) -> SettingsDump? {
        guard let dump = try? JSONDecoder().decode(SettingsDump.self, from: data), dump.version == 1 else { return nil }
        return dump
    }
}

enum SettingsScope {
    case allGames, game
}

// A pane is a rule over node paths, so a leaf the core adds lands in its pane by prefix.
enum SettingsPaneKind: Hashable {
    case renderer, cpu, audio, sounds, performance, fixes, network, rpcn, all, controllers, hotkeys, presets, shaders, skins, background, backup, jitDevice, diagnostics, about, choices([String])

    // `Renderer` is also a leaf's name; here it is only a title.
    var title: String {
        switch self {
        case .renderer: return String(localized: "Renderer")
        case .cpu: return String(localized: "CPU/SPU")
        case .audio: return String(localized: "Audio")
        case .sounds: return String(localized: "System Sounds")
        case .performance: return String(localized: "Performance")
        case .fixes: return String(localized: "Fixes")
        case .network: return String(localized: "Network")
        case .rpcn: return String(localized: "RPCN")
        case .all: return String(localized: "All Settings")
        case .controllers: return String(localized: "Controllers")
        case .hotkeys: return String(localized: "Hotkeys")
        case .presets: return String(localized: "Presets")
        case .shaders: return String(localized: "Shaders")
        case .skins: return String(localized: "Controller Skins")
        case .background: return String(localized: "Background")
        case .backup: return String(localized: "Backup & Restore")
        case .jitDevice: return String(localized: "JIT and Device")
        case .diagnostics: return String(localized: "Diagnostics")
        case .about: return String(localized: "About")
        case .choices(let path): return path.last ?? ""
        }
    }

    var systemImage: String {
        switch self {
        case .renderer: return "tv"
        case .cpu: return "cpu"
        case .audio: return "speaker.wave.2"
        case .sounds: return "music.note"
        case .performance: return "speedometer"
        case .fixes: return "wrench.and.screwdriver"
        case .network: return "network"
        case .rpcn: return "person.2.wave.2"
        case .all: return "list.bullet"
        case .controllers: return "dpad"
        case .hotkeys: return "gamecontroller"
        case .presets: return "square.stack"
        case .shaders: return "camera.filters"
        case .skins: return "paintpalette"
        case .background: return "photo.on.rectangle"
        case .backup: return "externaldrive.badge.timemachine"
        case .jitDevice: return "memorychip"
        case .diagnostics: return "stethoscope"
        case .about: return "info.circle"
        case .choices: return "list.bullet"
        }
    }
}

enum SettingMarker: Equatable {
    case changed, thisGame, fromDatabase, gameUses(String), required, nextBoot
}

enum SettingControl: Equatable {
    case toggle, choice, stepper(ClosedRange<Int>), text, locked
}

struct SettingsSection: Identifiable {
    var parent: [String]
    var leaves: [SettingLeaf]
    var title: String { parent.last ?? "" }
    var id: String { parent.joined(separator: SettingsModel.pathSeparator) }
}

extension SettingsModel {
    // Node names, not leaf names: the only cfg strings this file holds are the sections a pane starts from.
    private static let videoNode = "Video"
    private static let coreNode = "Core"
    private static let audioNode = "Audio"
    private static let netNode = "Net"
    private static let overlayNode = "Performance Overlay"

    // CPU/SPU takes a `Core` leaf whose name carries one of these as a word.
    private static let cpuWords = "\\b(PPU|SPU|MFC|LLVM|SPURS)\\b"
    // `park` keeps the per-title poll-park switch out of the default view, since no Swift literal may name a leaf.
    private static let advancedWords = "\\b(debug|profiler|log|renderdoc|gdb|dump|analyser|inspection|host_root|park)s?\\b"

    private static func has(_ pattern: String, _ text: String) -> Bool {
        text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }

    private static func inOverlay(_ leaf: SettingLeaf) -> Bool {
        leaf.path.count > 2 && leaf.path[0] == videoNode && leaf.path[1] == overlayNode
    }

    private static func isCPU(_ leaf: SettingLeaf) -> Bool {
        leaf.path.first == coreNode && has(cpuWords, leaf.path.last ?? "")
    }

    static func isAdvanced(_ leaf: SettingLeaf) -> Bool {
        leaf.path.contains { has(advancedWords, $0) }
    }

    static func belongs(_ leaf: SettingLeaf, to pane: SettingsPaneKind) -> Bool {
        switch pane {
        case .renderer: return leaf.path.first == videoNode && !inOverlay(leaf)
        case .cpu: return isCPU(leaf)
        case .performance: return inOverlay(leaf) || (leaf.path.first == coreNode && !isCPU(leaf))
        case .audio: return leaf.path.first == audioNode
        case .network: return leaf.path.first == netNode
        case .fixes: return leaf.fix
        case .all: return true
        case .sounds, .rpcn, .controllers, .hotkeys, .presets, .shaders, .skins, .background, .backup, .jitDevice, .diagnostics,
             .about, .choices: return false
        }
    }

    static func leaves(_ pane: SettingsPaneKind, in dump: SettingsDump, showAdvanced: Bool) -> [SettingLeaf] {
        dump.leaves.filter { belongs($0, to: pane) && (showAdvanced || !isAdvanced($0)) }
    }

    static func sections(_ leaves: [SettingLeaf]) -> [SettingsSection] {
        var out: [SettingsSection] = []
        for leaf in leaves {
            let parent = Array(leaf.path.dropLast())
            if out.last?.parent == parent {
                out[out.count - 1].leaves.append(leaf)
            } else {
                out.append(SettingsSection(parent: parent, leaves: [leaf]))
            }
        }
        return out
    }

    // What the core would run with in that scope.
    static func effective(_ leaf: SettingLeaf, scope: SettingsScope) -> String {
        switch scope {
        case .game: return leaf.forced ?? leaf.game ?? leaf.db ?? leaf.global
        case .allGames: return leaf.forced ?? leaf.global
        }
    }

    private static func isChanged(_ leaf: SettingLeaf) -> Bool {
        !leaf.randomDefault && leaf.global != leaf.defaultValue
    }

    // A forced row cannot be changed, so no other marker applies.
    static func markers(_ leaf: SettingLeaf, scope: SettingsScope, gameRunning: Bool, fromGameHost: Bool) -> [SettingMarker] {
        if leaf.forced != nil { return [.required] }
        var out: [SettingMarker] = []
        switch scope {
        case .allGames:
            if isChanged(leaf) { out.append(.changed) }
            if fromGameHost, let game = leaf.game { out.append(.gameUses(game)) }
        case .game:
            if leaf.game != nil { out.append(.thisGame) } else if leaf.db != nil { out.append(.fromDatabase) }
        }
        if gameRunning && !leaf.dynamic { out.append(.nextBoot) }
        return out
    }

    static func resettable(_ leaf: SettingLeaf, scope: SettingsScope) -> Bool {
        guard leaf.forced == nil else { return false }
        switch scope {
        case .allGames: return isChanged(leaf)
        case .game: return leaf.game != nil
        }
    }

    static func changedCount(_ leaves: [SettingLeaf], scope: SettingsScope) -> Int {
        leaves.filter { resettable($0, scope: scope) }.count
    }

    static func control(for leaf: SettingLeaf) -> SettingControl {
        if leaf.forced != nil { return .locked }
        if leaf.type == "bool" { return .toggle }
        if !leaf.variants.isEmpty { return .choice }
        if leaf.type == "int" || leaf.type == "uint", let lo = Int(leaf.min), let hi = Int(leaf.max) {
            let (span, overflow) = hi.subtractingReportingOverflow(lo)
            if !overflow && span >= 0 && span <= 100 { return .stepper(lo...hi) }
        }
        return .text
    }

    // No index: search reads the dump on screen. Hidden advanced matches are counted so the empty state can say so.
    static func search(_ dump: SettingsDump, query: String, help: SettingsHelp?, showAdvanced: Bool) -> (results: [SettingLeaf], hiddenAdvanced: Int) {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return ([], 0) }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        var results: [SettingLeaf] = []
        var hidden = 0
        for leaf in dump.leaves {
            let texts = leaf.path + [help?.text(for: leaf.path) ?? ""]
            guard texts.contains(where: { $0.range(of: needle, options: options) != nil }) else { continue }
            if showAdvanced || !isAdvanced(leaf) {
                results.append(leaf)
            } else {
                hidden += 1
            }
        }
        return (results, hidden)
    }
}
