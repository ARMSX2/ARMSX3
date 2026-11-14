// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Game column, built from the library, after XmbHome.kt.

import SwiftUI

extension XmbHost {
    /// The games in the library's order.
    private var shownGames: [LibraryCache.Entry] {
        guard UserDefaults.standard.bool(forKey: GameListView.sortRecentKey) else { return library.entries }
        return GameListView.recentFirst(library.entries, favourite: library.isFavourite)
    }

    func gameSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "game", title: firmware.text(XmbRco.ingame, "msg_game", String(localized: "Game")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_game", symbol: "gamecontroller"), items: []))
        let covers = CustomCovers.names()
        section.column.items = shownGames.map { entry in
            let id = "game." + entry.bootPath
            section.options[id] = { [weak self] option in self?.gameOption(option, entry) }
            return XmbNode(id: id, title: entry.title, subtitle: Self.subtitle(entry),
                           icon: .game(path: CustomCovers.path(for: entry.serial, fallback: entry.iconPath, in: covers)), kind: .game,
                           options: gameOptions(entry), backdrop: settings.xmbGameBackgrounds ? entry.backdropPath : nil)
        }
        if section.column.items.isEmpty, case .error(let status) = library.phase {
            // A failed scan is not an empty library; Cross reads it again, as the library's Retry does.
            section.column.items = [section.action(XmbNode(
                id: "game.error", title: String(localized: "Could not read the library (status \(status))"),
                subtitle: String(localized: "Retry"), icon: .firmware(rco: XmbRco.game, name: "item_tex_ps3game_folder", symbol: "folder"),
                kind: .action)) { [weak self] in Task { await self?.library.refresh() } }]
        } else if section.column.items.isEmpty {
            section.column.items = [XmbNode(
                id: "game.none", title: library.phase == .loading ? String(localized: "Looking for games…") : String(localized: "No games yet"),
                subtitle: String(localized: "Add a game folder in System Settings, or install a package from Utilities."),
                icon: .firmware(rco: XmbRco.game, name: "item_tex_ps3game_folder", symbol: "folder"), kind: .info)]
        }
        if let settings = gameSettingsFolder(&section) { section.column.hidden.append(settings) }
        return section
    }

    /// When it was last played, in the device's short date and time, or the serial for a game never played.
    static func subtitle(_ entry: LibraryCache.Entry) -> String? {
        if let played = PlayTime.shared.lastPlayed(for: entry.serial) {
            return XmbStamp.string(played)
        }
        return entry.serial.isEmpty ? nil : entry.serial
    }

    private func gameOptions(_ entry: LibraryCache.Entry) -> [XmbNode.Option] {
        var options = [XmbNode.Option(id: "play", title: String(localized: "Play")),
                       XmbNode.Option(id: "settings", title: String(localized: "Settings")),
                       XmbNode.Option(id: "patches", title: String(localized: "Patches"))]
        if GameUpdates.titleID(entry.serial) != nil {
            options.append(XmbNode.Option(id: "update", title: String(localized: "Check for Update")))
        }
        options.append(XmbNode.Option(id: "saves", title: String(localized: "Save Data")))
        // An ISO's trophies are inside the image.
        if !entry.isISO { options.append(XmbNode.Option(id: "trophies", title: String(localized: "Trophies"))) }
        if entry.locked == true { options.append(XmbNode.Option(id: "licence", title: String(localized: "Add licence…"))) }
        options.append(XmbNode.Option(id: "cache", title: String(localized: "Clear PPU/SPU/shader cache")))
        return options
    }

    private func gameOption(_ option: String, _ entry: LibraryCache.Entry) {
        switch option {
        case "play": launch(entry)
        case "settings": openGameSettings(entry)
        case "patches": presented = .library(.patches(serial: entry.serial, title: entry.title))
        case "update":
            if let id = GameUpdates.titleID(entry.serial) { presented = .library(.updateVersions(serial: id, title: entry.title)) }
        case "saves": presented = .library(.saveData(serial: entry.serial, title: entry.title))
        case "trophies": presented = .library(.trophies(titleDir: entry.bootPath, title: entry.title))
        case "licence":
            licenceEntry = entry
            presented = .library(.licence)
        case "cache":
            ask(String(localized: "Delete this game's compiled PPU modules, with the SPU and shader caches stored inside them? Its next boot recompiles from scratch and takes a while."),
                buttons: [String(localized: "Delete"), String(localized: "Cancel")], focus: 1) { [weak self] button in
                guard button == 0 else { return }
                Task {
                    let status = await Rpcs3Bridge.clearGameCache(withSerial: entry.serial)
                    self?.notice(GameListView.describe(clearedCache: status), error: false)
                }
            }
        default: break
        }
    }
}
