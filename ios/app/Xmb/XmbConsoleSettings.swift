// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Settings column with every setting edited in the XMB itself, ported from Android's XmbSettings.kt, XmbCollect.kt and XmbMode.kt.

import SwiftUI

/// The Settings column's own state.
@MainActor @Observable final class XmbConsoleSettingsStore {
    static let shared = XmbConsoleSettingsStore()

    /// The installed firmware's version, "" with none, nil until read.
    var firmwareVersion: String?
    /// Moves on with each write to a preference other screens keep in UserDefaults, so the column reads it again.
    private(set) var revision = 0
    @ObservationIgnored var readingFirmware = false

    func int(_ key: String, _ fallback: Int) -> Int {
        _ = revision
        return (UserDefaults.standard.object(forKey: key) as? NSNumber)?.intValue ?? fallback
    }

    func bool(_ key: String, _ fallback: Bool) -> Bool {
        _ = revision
        return UserDefaults.standard.object(forKey: key) as? Bool ?? fallback
    }

    func write(_ value: Any, _ key: String) {
        UserDefaults.standard.set(value, forKey: key)
        revision += 1
    }
}

/// The step a press moves a range of whole numbers, by Android's two rules.
enum XmbRangeSteps {
    /// A settings list's whole numbers: one, or about a fortieth of a wide range (XmbCollect.kt xmbIntStep).
    static func listStep(min: Int, max: Int) -> Int {
        let span = Swift.max(max - min, 1)
        return span <= 40 ? 1 : Swift.max(Int((Double(span) / 40).rounded()), 1)
    }

    /// All Core Settings: about a hundredth of a range wider than 200, else one.
    static func coreStep(min: Int, max: Int) -> Int {
        let span = max - min
        return span > 200 ? Swift.max(1, Int((Double(span) / 100).rounded())) : 1
    }
}

/// Where a settings list's edits go: everyone's settings, or one game's, whose rows carry the game's prefix in their ids.
struct XmbSettingsScope {
    let model: SettingsScreenModel
    let prefix: String

    @MainActor var forGame: Bool { model.scope == .game }

    func id(_ id: String) -> String { prefix + id }
}

extension XmbHost {
    /// The Settings column, group for group in the console's order.
    func settingsSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "settings", title: firmware.text(XmbRco.ingame, "msg_setting", String(localized: "Settings")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_sysconf", symbol: "gearshape"), items: []))
        let scope = XmbSettingsScope(model: settingsModel, prefix: "")
        section.column.items = [
            systemUpdateGroup(&section),
            gameSettingsGroup(&section, scope),
            allCoreSettingsGroup(&section, scope),
            musicSettingsNode(&section),
            systemSettingsGroup(&section),
            themeSettingsGroup(&section),
            accessorySettingsGroup(&section),
            displayGroup(&section, scope),
            soundGroup(&section, scope),
            networkGroup(&section, scope),
            debugSettingsGroup(&section, scope),
        ]
        return section
    }

    /// A game's own settings, in Android's order for a game: the app's own and the firmware are for the library.
    func gameSettingsGroups(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> [XmbNode] {
        [gameSettingsGroup(&section, scope), allCoreSettingsGroup(&section, scope), displayGroup(&section, scope), soundGroup(&section, scope),
         accessorySettingsGroup(&section), musicSettingsNode(&section), networkGroup(&section, scope), debugSettingsGroup(&section, scope)]
    }

    // The sheets beside a tab's rows edit everyone's settings, so a game's list leaves them out.
    private func displayGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        tabGroup(&section, scope, "display", word: "msg_display_setting", fallback: String(localized: "Display Settings"),
                 texture: "tex_display", symbol: "tv", panes: [.renderer], screens: scope.forGame ? [] : [.shaders])
    }

    private func soundGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        tabGroup(&section, scope, "sound", word: "msg_sound_setting", fallback: String(localized: "Sound Settings"),
                 texture: "tex_sound", symbol: "speaker.wave.2", panes: [.audio], screens: [])
    }

    private func networkGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        tabGroup(&section, scope, "network", word: "msg_net_setting", fallback: String(localized: "Network Settings"),
                 texture: "tex_network", symbol: "network", panes: [.network], screens: scope.forGame ? [] : [.rpcn])
    }

    // MARK: - Words and icons

    private func sysconfIcon(_ texture: String, _ symbol: String) -> XmbIcon {
        .firmware(rco: XmbRco.sysconf, name: texture, symbol: symbol)
    }

    /// The console's line under a group's name; only the firmware has it.
    private func sysconfExplanation(_ word: String) -> String? {
        let text = firmware.text(XmbRco.sysconf, word + "_explanation", "")
        return text.isEmpty ? nil : text
    }

    // MARK: - Rows

    func consoleToggle(_ section: inout XmbSection, _ id: String, _ title: String, icon: XmbIcon, isOn: Bool, help: String? = nil,
                       changed: Bool = false, set: @escaping @MainActor (Bool) -> Void) -> XmbNode {
        section.picks[id] = { choice in set(choice == 0) }
        return XmbNode(id: id, title: title, subtitle: isOn ? on : off, icon: icon,
                       kind: .setting(choices: [on, off], selected: isOn ? 0 : 1), description: help, changed: changed)
    }

    func consoleChoice(_ section: inout XmbSection, _ id: String, _ title: String, icon: XmbIcon, choices: [String], selected: Int?,
                       help: String? = nil, changed: Bool = false, swatches: [SIMD3<Float>]? = nil,
                       pick: @escaping @MainActor (Int) -> Void) -> XmbNode {
        section.picks[id] = { choice in pick(choice) }
        let value = selected.flatMap { choices.indices.contains($0) ? choices[$0] : nil }
        return XmbNode(id: id, title: title, subtitle: value, icon: icon, kind: .setting(choices: choices, selected: selected),
                       description: help, changed: changed, swatches: swatches)
    }

    /// A whole number moved by `step` and applied as it moves, as Android's range editor; `unit` follows it.
    func consoleRange(_ section: inout XmbSection, _ id: String, _ title: String, icon: XmbIcon, value: Int, range: ClosedRange<Int>,
                      step: Int, unit: String = "", help: String? = nil, changed: Bool = false,
                      set: @escaping @MainActor (Int) -> Void) -> XmbNode {
        section.ranges[id] = { moved in set(Int(moved.rounded())) }
        let editor = XmbRange(min: Double(range.lowerBound), max: Double(range.upperBound), step: Double(max(step, 1)),
                              value: Double(value), unit: unit)
        return XmbNode(id: id, title: title, subtitle: editor.text, icon: icon, kind: .range(editor), description: help, changed: changed)
    }

    /// One of the app's screens that is an editor of its own, opened as a sheet over the XMB.
    func consoleScreen(_ section: inout XmbSection, _ id: String, _ pane: SettingsPane, icon: XmbIcon, subtitle: String? = nil) -> XmbNode {
        section.action(XmbNode(id: id, title: pane.title, subtitle: subtitle, icon: icon, kind: .action)) { [weak self] in
            self?.presented = .pane([pane])
        }
    }

    // MARK: - Core settings

    /// A core setting as a row edited in the side panel; `list` keeps the same setting apart in each list it is in.
    private func coreRow(_ section: inout XmbSection, _ scope: XmbSettingsScope, _ leaf: SettingLeaf, icon: XmbIcon, list: String,
                         coreSteps: Bool) -> XmbNode {
        let model = scope.model
        let id = scope.id("settings.\(list).\(leaf.id)")
        let title = leaf.path.last ?? ""
        let value = model.value(leaf)
        // Over a game a setting the core reads only at boot says so, as the app's own rows mark it.
        let nextBoot = model.gameRunning && !leaf.dynamic ? String(localized: "Applies next boot") : nil
        let lines = [nextBoot, model.help?.text(for: leaf.path)].compactMap { $0 }
        let help = lines.isEmpty ? nil : lines.joined(separator: "   ")
        let changed = SettingsModel.resettable(leaf, scope: model.scope)
        var options: [XmbNode.Option] = []
        if changed {
            options = [.init(id: "reset", title: String(localized: "Reset to Default"))]
            section.options[id] = { [weak self] _ in self?.resetCore(leaf, model) }
        }
        let kind: XmbNode.Kind
        var shown = value
        var values: [String] = []
        if leaf.forced != nil {
            return XmbNode(id: id, title: title, subtitle: value, icon: icon, kind: .info,
                           description: [String(localized: "Required on iOS"), help].compactMap { $0 }.joined(separator: "   "))
        } else if leaf.type == "bool" {
            shown = value == "true" ? on : off
            values = ["true", "false"]
            kind = .setting(choices: [on, off], selected: value == "true" ? 0 : 1)
        } else if !leaf.variants.isEmpty {
            values = leaf.variants
            kind = .setting(choices: leaf.variants, selected: leaf.variants.firstIndex(of: value))
        } else if leaf.type == "int" || leaf.type == "uint", let lo = Int(leaf.min), let hi = Int(leaf.max), hi > lo,
                  !hi.subtractingReportingOverflow(lo).overflow {
            let step = coreSteps ? XmbRangeSteps.coreStep(min: lo, max: hi) : XmbRangeSteps.listStep(min: lo, max: hi)
            section.ranges[id] = { [weak self] moved in self?.setCore(leaf, String(Int(moved.rounded())), model) }
            kind = .range(XmbRange(min: Double(lo), max: Double(hi), step: Double(step), value: Double(Int(value) ?? lo)))
        } else {
            // Text, floats and numbers with no range are typed, where Android opened the full settings screen.
            let node = XmbNode(id: id, title: title, subtitle: String(value.prefix(48)), icon: icon, kind: .action, options: options,
                               description: help, changed: changed)
            return section.action(node) { [weak self] in
                self?.type(title, initial: value) { typed in self?.setCore(leaf, typed, model) }
            }
        }
        if !values.isEmpty {
            section.picks[id] = { [weak self] choice in
                if values.indices.contains(choice) { self?.setCore(leaf, values[choice], model) }
            }
        }
        return XmbNode(id: id, title: title, subtitle: shown, icon: icon, kind: kind, options: options, description: help, changed: changed)
    }

    private func setCore(_ leaf: SettingLeaf, _ value: String, _ model: SettingsScreenModel) {
        guard value != model.value(leaf) else { return }
        Task {
            if await model.set(leaf, value: value) != 0 { showSettingsFailure(model) }
        }
    }

    private func resetCore(_ leaf: SettingLeaf, _ model: SettingsScreenModel) {
        Task {
            if await model.reset(leaf) != 0 { showSettingsFailure(model) }
        }
    }

    private func showSettingsFailure(_ model: SettingsScreenModel) {
        guard let failure = model.failure else { return }
        model.failure = nil
        notice(failure.title + " " + failure.message)
    }

    // MARK: - Search

    private var searchIcon: XmbIcon { sysconfIcon("tex_sett", "magnifyingglass") }

    /// Search at the head of a list; its matches are a folder of their own, opened over the list (XmbSettings.kt xmbSearchNode).
    private func searchable(_ section: inout XmbSection, list: String, _ rows: [XmbNode]) -> [XmbNode] {
        guard !rows.isEmpty else { return rows }
        let title = String(localized: "Search")
        let search = section.action(XmbNode(id: list + ".search", title: title, subtitle: String(localized: "Find a setting in this list"),
                                            icon: searchIcon, kind: .action)) { [weak self] in
            self?.type(title) { typed in
                self?.runSearch(list: list, typed) { words in Self.matches(rows, words).count }
            }
        }
        if let query = searches[list] {
            let hits = Self.matches(rows, Self.words(query))
            if !hits.isEmpty { section.column.hidden.append(resultsNode(list, query, hits)) }
        }
        return [search] + rows
    }

    private static func words(_ query: String) -> [String] {
        query.lowercased().split(separator: " ").map(String.init)
    }

    /// Every row in the list and the lists inside it whose name or line holds all the words.
    private static func matches(_ rows: [XmbNode], _ words: [String]) -> [XmbNode] {
        rows.flatMap { node -> [XmbNode] in
            if let children = node.children { return matches(children, words) }
            if node.id.hasSuffix(".search") || node.id.hasSuffix(".results") { return [] }
            // A setting's value is left out, as Android does, so changing it from the results keeps the row there.
            let value: String? = switch node.kind {
            case .setting, .range: nil
            default: node.subtitle
            }
            let hay = [node.title, value ?? "", node.description ?? ""].joined(separator: " ").lowercased()
            return words.allSatisfy { hay.contains($0) } ? [node] : []
        }
    }

    private func resultsNode(_ list: String, _ query: String, _ hits: [XmbNode]) -> XmbNode {
        XmbNode(id: list + ".results", title: "\u{201C}\(query)\u{201D}", subtitle: "\(hits.count)", icon: searchIcon, kind: .folder(hits))
    }

    /// Keeps the search when it finds something and opens its matches; says so when it finds nothing.
    private func runSearch(list: String, _ typed: String, count: ([String]) -> Int) {
        let query = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        guard count(Self.words(query)) > 0 else {
            notice(String(localized: "No settings match \u{201C}\(query)\u{201D}."))
            return
        }
        searches[list] = query
        open(list + ".results")
    }

    // MARK: - System Update

    private func systemUpdateGroup(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_update", "arrow.down.circle")
        let store = XmbConsoleSettingsStore.shared
        let open = isOpen("settings.update")
        // Read once, and again each time the list opens, since an install may have changed it.
        if (store.firmwareVersion == nil || (open && !updateWasOpen)) && !store.readingFirmware {
            store.readingFirmware = true
            Task {
                let version = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.firmwareVersion { version, status in continuation.resume(returning: status == 0 ? version : "") }
                }
                store.firmwareVersion = version
                store.readingFirmware = false
            }
        }
        updateWasOpen = open
        // The installed version, then the firmware files on this device and the Files picker, as Android's System Update.
        let version = store.firmwareVersion.map { $0.isEmpty ? String(localized: "No firmware installed") : $0 } ?? "…"
        var rows = [XmbNode(id: "settings.update.version", title: version, subtitle: nil, icon: icon, kind: .info,
                            description: String(localized: "Select the official PS3UPDAT.PUP firmware file. It is installed into dev_flash, which games need in order to boot. Download it from Sony's support site; it is not included."))]
        if !installs.busy {
            let drive = XmbIcon.firmware(rco: XmbRco.photo, name: "item_tex_usb_connection", symbol: "externaldrive")
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let device = toolsFileFolder(&section, id: "settings.update.device", title: String(localized: "On My \(UIDevice.current.localizedModel)"),
                                         dir: documents, icon: drive, extensions: ["pup"])
            rows.append(XmbNode(id: "settings.update.storage", title: String(localized: "Update via Storage Media"), subtitle: nil, icon: icon,
                                kind: .folder([device])))
            rows.append(section.action(XmbNode(id: "settings.update.external", title: String(localized: "Choose from Files"), subtitle: nil,
                                               icon: drive, kind: .action)) { [weak self] in self?.pick(.tools(.packages)) })
        }
        return XmbNode(id: "settings.update", title: firmware.text(XmbRco.sysconf, "msg_update", String(localized: "System Update")),
                       subtitle: sysconfExplanation("msg_update"), icon: icon, kind: .folder(rows))
    }

    // MARK: - Game Settings

    /// How games run, with their patches as Android's Game Settings has them. A game's own list has that game's patches.
    private func gameSettingsGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        var rows = [
            paneFolder(&section, scope, .cpu, icon: sysconfIcon("tex_game", "cpu")),
            paneFolder(&section, scope, .performance, icon: sysconfIcon("tex_game", "speedometer")),
        ]
        let model = scope.model
        if scope.forGame, let serial = model.serial {
            rows.append(section.action(XmbNode(id: scope.id("settings.game.patches"), title: String(localized: "Patches"), subtitle: nil,
                                               icon: sysconfIcon("tex_update", "bandage"), kind: .action)) { [weak self] in
                self?.presented = .library(.patches(serial: serial, title: model.gameTitle))
            })
        } else {
            rows.append(toolsPatches(&section))
        }
        rows.append(paneFolder(&section, scope, .fixes, icon: sysconfIcon("tex_update", "wrench.and.screwdriver")))
        if !scope.forGame {
            rows.append(consoleScreen(&section, "settings.game.presets", .presets, icon: sysconfIcon("tex_sett", "square.stack")))
        }
        return XmbNode(id: scope.id("settings.game"), title: firmware.text(XmbRco.sysconf, "msg_game_setting", String(localized: "Game Settings")),
                       subtitle: sysconfExplanation("msg_game_setting"), icon: sysconfIcon("tex_game", "gamecontroller"), kind: .folder(rows))
    }

    /// One of the app's settings panes as a list of its own rows, Search first, as Android's settings tabs open.
    func paneFolder(_ section: inout XmbSection, _ scope: XmbSettingsScope, _ pane: SettingsPane, icon: XmbIcon) -> XmbNode {
        let id = scope.id("settings.tab." + pane.navigationKey)
        let rows = scope.model.leaves(pane).map { coreRow(&section, scope, $0, icon: icon, list: pane.navigationKey, coreSteps: false) }
        return XmbNode(id: id, title: pane.title, subtitle: nil, icon: icon, kind: .folder(searchable(&section, list: id, rows)))
    }

    // MARK: - All Core Settings

    func allCoreSettingsGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        let icon = sysconfIcon("tex_sett", "list.bullet")
        let model = scope.model
        let all = model.leaves(.all)
        let core = scope.id("settings.core")

        let searchTitle = String(localized: "Search")
        let search = section.action(XmbNode(id: core + ".search", title: searchTitle,
                                            subtitle: String(localized: "Find a setting by name"), icon: searchIcon,
                                            kind: .action)) { [weak self] in
            self?.type(searchTitle) { typed in
                self?.runSearch(list: core, typed) { words in Self.coreMatches(all, words).count }
            }
        }
        var rows = [search]
        if let query = searches[core] {
            let hits = Self.coreMatches(all, Self.words(query))
                .map { coreRow(&section, scope, $0, icon: .none, list: "core.results", coreSteps: true) }
            if !hits.isEmpty { section.column.hidden.append(resultsNode(core, query, hits)) }
        }

        // What differs from the default on its own, as Android's remembered list, each with Reset and all at once.
        let changed = all.filter { SettingsModel.resettable($0, scope: model.scope) }
        var remembered: [XmbNode]
        if changed.isEmpty {
            remembered = [XmbNode(id: core + ".remembered.none", title: String(localized: "Nothing is remembered here."),
                                  subtitle: nil, icon: icon, kind: .info)]
        } else {
            let forgetAll = String(localized: "Forget all")
            remembered = [section.action(XmbNode(id: core + ".remembered.all", title: forgetAll, subtitle: "\(changed.count)",
                                                 icon: icon, kind: .action)) { [weak self] in
                guard let self else { return }
                ask(String(localized: "Forget every setting remembered here? Each one goes back to its default."),
                    buttons: [forgetAll, String(localized: "Cancel")], focus: 1) { [weak self] button in
                    guard button == 0, let self else { return }
                    Task { if await model.resetPane(.all) != 0 { self.showSettingsFailure(model) } }
                }
            }]
            remembered += changed.map { coreRow(&section, scope, $0, icon: .none, list: "core.remembered", coreSteps: true) }
        }
        let tier = scope.forGame ? String(localized: "Changes are remembered for this game only")
            : String(localized: "Changes are remembered for every game")
        rows.append(XmbNode(id: core + ".remembered", title: String(localized: "Settings remembered here"),
                            subtitle: tier, icon: icon, kind: .folder(remembered)))

        var order: [String] = []
        var bySection: [String: [SettingLeaf]] = [:]
        for leaf in all {
            let name = leaf.path.first ?? ""
            if bySection[name] == nil { order.append(name) }
            bySection[name, default: []].append(leaf)
        }
        for name in order {
            let leaves = bySection[name] ?? []
            let id = core + ".section." + name
            let texture = Self.coreSectionTexture(name)
            // Only an open section builds its rows, so the column is not rebuilt with every core setting each time.
            let children = isOpen(id) ? leaves.map { coreRow(&section, scope, $0, icon: .none, list: "core", coreSteps: true) }
                : [XmbNode(id: id + ".closed", title: "", subtitle: nil, icon: .none, kind: .info)]
            rows.append(XmbNode(id: id, title: name, subtitle: String(localized: "\(leaves.count) settings"),
                                icon: sysconfIcon(texture, "gearshape"), kind: .folder(children)))
        }
        return XmbNode(id: core, title: String(localized: "All Core Settings"),
                       subtitle: String(localized: "Every core setting, section by section"), icon: icon, kind: .folder(rows))
    }

    /// Android matches a core setting by its name, path and section.
    private static func coreMatches(_ leaves: [SettingLeaf], _ words: [String]) -> [SettingLeaf] {
        leaves.filter { leaf in
            let hay = leaf.path.joined(separator: " ").lowercased()
            return words.allSatisfy { hay.contains($0) }
        }
    }

    private static func coreSectionTexture(_ section: String) -> String {
        switch section.lowercased() {
        case "core": return "tex_game"
        case "video": return "tex_display"
        case "audio": return "tex_sound"
        case "input/output": return "tex_pd"
        case "system": return "tex_console"
        case "net": return "tex_network"
        case "savestate": return "tex_power"
        default: return "tex_security"
        }
    }

    // MARK: - System Settings

    /// The app's own settings, each row with an icon for what it is, as Android's System Settings.
    private func systemSettingsGroup(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_console", "cube")
        let folders = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_plain_folder", symbol: "folder")
        let rows = [
            consoleScreen(&section, "settings.system.jit", .jitDevice, icon: sysconfIcon("tex_security", "memorychip")),
            section.action(XmbNode(id: "settings.system.folders", title: String(localized: "Game Folders"),
                                   subtitle: String(localized: "The folders the library reads games from"), icon: folders,
                                   kind: .action)) { [weak self] in
                self?.presented = .library(.folders)
            },
            consoleScreen(&section, "settings.system.backup", .backup,
                          icon: .firmware(rco: XmbRco.photo, name: "ps3sd_tex_default", symbol: "externaldrive")),
            consoleScreen(&section, "settings.system.about", .about, icon: icon),
        ]
        return XmbNode(id: "settings.system", title: firmware.text(XmbRco.sysconf, "msg_console_setting", String(localized: "System Settings")),
                       subtitle: sysconfExplanation("msg_console_setting"), icon: icon,
                       kind: .folder(searchable(&section, list: "settings.system", rows)))
    }

    // MARK: - Theme Settings

    /// Theme Settings > Color's swatches: silver for Default, each month's colour, near white for the cycle.
    private static var colourSwatches: [SIMD3<Float>] {
        [SIMD3(repeating: 0.753)] + (0 ..< 12).map(XmbOriginalColors.swatch(month:)) + [SIMD3(repeating: 0.949)]
    }

    private func themeSettingsGroup(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_thema", "paintpalette")
        let themes = ThemeLibraryModel.shared.entries
        let labels = [String(localized: "Original")] + themes.map(\.meta.name)
        // The XMB's own theme; one that is gone reads as Original, as on Android.
        let selected = (themes.firstIndex { $0.folderName == settings.xmbTheme } ?? -1) + 1
        // What the theme in use brought for the XMB, as Android words it under the description.
        let noAssets = String(localized: "Added before its icons and sounds were read: install the theme again to use them.")
        let inUse = selected > 0 ? themes[selected - 1] : nil
        var assetsLine: String?
        if let inUse {
            if XmbTheme.shared.lacksAssets(inUse) {
                assetsLine = noAssets
            } else if let counts = XmbTheme.shared.assetCounts(inUse) {
                assetsLine = String(format: String(localized: "Its icons: %d   Its sounds: %d"), counts.icons, counts.sounds)
            }
        }
        let help = [assetsLine, String(localized: "Original, or a PS3 theme with its own background, icons and sounds.")]
            .compactMap { $0 }.joined(separator: "\n")
        let themeIcon = inUse.flatMap { XmbTheme.shared.iconOf($0) }.map { XmbIcon.game(path: $0.path) } ?? icon
        let theme = consoleChoice(&section, "settings.theme.theme", String(localized: "Theme"), icon: themeIcon, choices: labels,
                                  selected: selected, help: help) { [weak self] choice in
            guard let self else { return }
            let picked = themes.indices.contains(choice - 1) ? themes[choice - 1] : nil
            settings.xmbTheme = picked?.folderName
            // Picking it again lets a theme that was stopped by a crash play live again, as the library's Background pane does.
            if let picked { ThemeLibraryModel.shared.library.clearLiveBlock(picked.folderName) }
            // Said as it is picked: a theme added before its icons were read otherwise looks like one whose icons do not work.
            if let picked, XmbTheme.shared.lacksAssets(picked) { notice(noAssets, error: false) }
        }
        // The app's own folder first, which a pad can reach, then the Files picker, as Android's browser and its fallback.
        let drive = XmbIcon.firmware(rco: XmbRco.photo, name: "item_tex_usb_connection", symbol: "externaldrive")
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let device = toolsFileFolder(&section, id: "settings.theme.device", title: String(localized: "On My \(UIDevice.current.localizedModel)"),
                                     dir: documents, icon: drive, extensions: ["p3t"]) { [weak self] url in self?.themeImported(url) }
        let files = section.action(XmbNode(id: "settings.theme.external", title: String(localized: "Choose from Files"), subtitle: nil,
                                           icon: drive, kind: .action)) { [weak self] in self?.pick(.theme) }
        let importTheme = XmbNode(id: "settings.theme.import", title: String(localized: "Import Theme…"),
                                  subtitle: String(localized: "Add a PS3 theme (.p3t) from this device."), icon: icon,
                                  kind: .folder([device, files]))

        let months = Calendar.current.standaloneMonthSymbols.map { $0.prefix(1).uppercased() + $0.dropFirst() }
        let colours = [String(localized: "Default")] + months + [String(localized: "Cycle Through Months")]
        let colour = consoleChoice(&section, "settings.theme.color", String(localized: "Color"), icon: icon, choices: colours,
                                   selected: min(max(settings.xmbThemeColor + 1, 0), colours.count - 1),
                                   help: String(localized: "Default changes with the month, as on the console."),
                                   swatches: Self.colourSwatches) { [weak self] choice in
            self?.settings.xmbThemeColor = choice - 1
        }
        let rows = [
            theme,
            importTheme,
            colour,
            consoleToggle(&section, "settings.theme.backgrounds", String(localized: "Game Backgrounds"), icon: icon,
                          isOn: settings.xmbGameBackgrounds,
                          help: String(localized: "Show a game's own background behind the XMB while it is selected.")) { [weak self] on in
                self?.settings.xmbGameBackgrounds = on
            },
            consoleToggle(&section, "settings.theme.icons", String(localized: "Animated Icons"), icon: icon, isOn: settings.xmbAnimatedIcons,
                          help: String(localized: "Play a game's animated icon while it is selected.")) { [weak self] on in
                self?.settings.xmbAnimatedIcons = on
            },
        ]
        return XmbNode(id: "settings.theme", title: firmware.text(XmbRco.sysconf, "msg_theme_settings", String(localized: "Theme Settings")),
                       subtitle: sysconfExplanation("msg_theme_settings"), icon: icon, kind: .folder(rows))
    }

    // MARK: - Accessory Settings

    private func accessorySettingsGroup(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_pd", "dpad")
        let rows = [
            controlsNode(in: &section),
            devicesFolder(&section),
            hotkeysFolder(&section),
            onScreenFolder(&section),
            consoleScreen(&section, "settings.accessory.skins", .skins, icon: sysconfIcon("tex_thema", "paintpalette")),
        ]
        return XmbNode(id: "settings.accessory", title: firmware.text(XmbRco.sysconf, "msg_pd_setting", String(localized: "Accessory Settings")),
                       subtitle: sysconfExplanation("msg_pd_setting"), icon: icon, kind: .folder(rows))
    }

    /// Android's Controls tab: on iOS, the devices a game can use beside the pad.
    private func devicesFolder(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_pd", "dpad")
        let model = XmbControlsStore.shared.model
        let facing = [String(localized: "Front"), String(localized: "Back")]
        let rows = [
            consoleChoice(&section, "settings.devices.camera.facing", String(localized: "PS Eye Camera"), icon: icon, choices: facing,
                          selected: model.cameraBack ? 1 : 0) { choice in
                if (choice == 1) != model.cameraBack { model.setCameraBack(choice == 1) }
            },
            // Off makes the keyboard Player 1's pad, as the Controllers screen does.
            consoleToggle(&section, "settings.devices.keyboard", String(localized: "Keyboard as USB keyboard"), icon: icon,
                          isOn: settings.keyboardSlot < 0) { on in model.useKeyboard(as: on ? -1 : 0) },
            XmbNode(id: "settings.devices.microphone", title: String(localized: "Microphone"), subtitle: ControllersModel.word(model.mic),
                    icon: icon, kind: .info),
            XmbNode(id: "settings.devices.camera", title: String(localized: "Camera"), subtitle: ControllersModel.word(model.camera),
                    icon: icon, kind: .info),
        ]
        return XmbNode(id: "settings.devices", title: String(localized: "Controls"), subtitle: nil, icon: icon,
                       kind: .folder(searchable(&section, list: "settings.devices", rows)))
    }

    private func hotkeysFolder(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_remote", "gamecontroller")
        var rows = hotkeyRows(&section, prefix: "settings.hotkeys", icon: icon)
        rows.append(section.action(XmbNode(id: "settings.hotkeys.reset", title: String(localized: "Reset Hotkeys"), subtitle: nil,
                                           icon: icon, kind: .action)) { [weak self] in
            guard let self else { return }
            ask(String(localized: "Reset all hotkeys?") + " "
                    + String(localized: "Only Quick Menu, Quick Save and Quick Load keep a hotkey, with their original buttons."),
                buttons: [String(localized: "Reset"), String(localized: "Cancel")], focus: 1) { [weak self] button in
                if button == 0 { self?.settings.hotkeys = HotkeyTable.resetTable }
            }
        })
        return XmbNode(id: "settings.hotkeys", title: String(localized: "Hotkeys"), subtitle: nil, icon: icon,
                       kind: .folder(searchable(&section, list: "settings.hotkeys", rows)))
    }

    /// Android's On-Screen tab: the touch pad.
    private func onScreenFolder(_ section: inout XmbSection) -> XmbNode {
        let icon = sysconfIcon("tex_display", "iphone")
        let opacity = Int((settings.padOpacity * 100).rounded())
        let rows = [
            consoleToggle(&section, "settings.onscreen.vibrate", String(localized: "Vibrate on Press"), icon: icon,
                          isOn: settings.padVibrates, changed: !settings.padVibrates) { [weak self] on in
                self?.settings.padVibrates = on
            },
            consoleRange(&section, "settings.onscreen.opacity", String(localized: "Button Opacity"), icon: icon, value: opacity,
                         range: 20 ... 100, step: 5, unit: "%", changed: opacity != 75) { [weak self] percent in
                self?.settings.padOpacity = Double(percent) / 100
            },
            // The layout editor is a canvas of its own, reached from the Controllers screen.
            section.action(XmbNode(id: "settings.onscreen.layout", title: String(localized: "Edit Layout…"), subtitle: nil, icon: icon,
                                   kind: .action)) { [weak self] in
                self?.presented = .pane([.controllers])
            },
        ]
        return XmbNode(id: "settings.onscreen", title: String(localized: "On-Screen"), subtitle: nil, icon: icon,
                       kind: .folder(searchable(&section, list: "settings.onscreen", rows)))
    }

    // MARK: - Display, Sound, Network

    /// A group that is one of Android's settings tabs: Search, the panes' settings, then the app's screens that belong with them.
    private func tabGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope, _ id: String, word: String, fallback: String,
                          texture: String, symbol: String, panes: [SettingsPane], screens: [SettingsPane]) -> XmbNode {
        let icon = sysconfIcon(texture, symbol)
        var rows = panes.flatMap { pane in
            scope.model.leaves(pane).map { coreRow(&section, scope, $0, icon: icon, list: id, coreSteps: false) }
        }
        rows += screens.map { consoleScreen(&section, "settings.\(id).\($0.navigationKey)", $0, icon: icon) }
        let groupID = scope.id("settings." + id)
        return XmbNode(id: groupID, title: firmware.text(XmbRco.sysconf, word, fallback), subtitle: sysconfExplanation(word),
                       icon: icon, kind: .folder(searchable(&section, list: groupID, rows)))
    }

    // MARK: - Debug Settings

    /// Android's Advanced tab: the developer switches, then the debug settings themselves while they are shown.
    private func debugSettingsGroup(_ section: inout XmbSection, _ scope: XmbSettingsScope) -> XmbNode {
        let icon = sysconfIcon("tex_security", "hammer")
        let model = scope.model
        let advanced = model.showAdvanced
        var rows = [
            consoleToggle(&section, scope.id("settings.debug.advanced"), String(localized: "Show advanced settings"), icon: icon, isOn: advanced,
                          help: String(localized: "Debug and developer settings. Changing them can stop games from working."),
                          changed: advanced) { on in
                model.showAdvanced = on
            },
            consoleToggle(&section, scope.id("settings.debug.verbose"), String(localized: "Verbose logging"), icon: icon,
                          isOn: settings.verboseLogging, changed: settings.verboseLogging) { [weak self] on in
                self?.settings.verboseLogging = on
                Task { _ = await Rpcs3Bridge.setVerboseLogging(on) }
            },
        ]
        if !scope.forGame { rows.append(consoleScreen(&section, "settings.debug.diagnostics", .diagnostics, icon: icon)) }
        rows += model.leaves(.all).filter(SettingsModel.isAdvanced)
            .map { coreRow(&section, scope, $0, icon: icon, list: "debug", coreSteps: false) }
        let id = scope.id("settings.debug")
        return XmbNode(id: id, title: firmware.text(XmbRco.sysconf, "msg_tool_debug_settings", String(localized: "Debug Settings")),
                       subtitle: String(localized: "Advanced"), icon: icon, kind: .folder(searchable(&section, list: id, rows)))
    }
}

extension XmbHost {
    /// A theme picked in Theme Settings > Import Theme. As on Android it becomes the XMB's theme, and the library's
    /// background is left as it is.
    func themeImported(_ url: URL) {
        Task {
            let themes = ThemeLibraryModel.shared
            let library = themes.library
            let outcome = await Task.detached(priority: .userInitiated) { ThemeImporter.importTheme(from: url, into: library) }.value
            switch outcome {
            case .success(let entry):
                themes.reload()
                settings.xmbTheme = entry.folderName
            case .failure(let failure):
                ControllerPrompt.shared.ask(failure.message, message: "", actions: [.ok])
            }
        }
    }
}
