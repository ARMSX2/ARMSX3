// SPDX-License-Identifier: GPL-2.0-only
// A game's own settings as XMB lists, the Settings column's groups scoped to that game, ported from openSettings in Android's XmbHome.kt.

import SwiftUI

/// The game whose settings were last asked for, with its settings model.
@MainActor @Observable final class XmbGameSettings {
    static let shared = XmbGameSettings()

    private(set) var game: LibraryCache.Entry?
    private(set) var model: SettingsScreenModel?

    /// Read afresh each time, so what was changed in the library's sheet or in game is shown.
    func show(_ entry: LibraryCache.Entry) {
        let model = SettingsScreenModel(serial: entry.serial, gameTitle: entry.title, scope: .game, fromGameHost: true)
        game = entry
        self.model = model
        Task { await model.load() }
    }
}

extension XmbHost {
    private static func gameSettingsID(_ entry: LibraryCache.Entry) -> String { "game.settings." + entry.bootPath }

    /// Triangle > Settings, or Square, on a game: its settings as a folder over the Game column.
    func openGameSettings(_ entry: LibraryCache.Entry) {
        XmbGameSettings.shared.show(entry)
        open(Self.gameSettingsID(entry))
    }

    /// The folder `openGameSettings` opens, kept in the Game column out of sight.
    func gameSettingsFolder(_ section: inout XmbSection) -> XmbNode? {
        let store = XmbGameSettings.shared
        guard let entry = store.game, let model = store.model else { return nil }
        let id = Self.gameSettingsID(entry)
        let scope = XmbSettingsScope(model: model, prefix: id + ".")
        return XmbNode(id: id, title: entry.title, subtitle: nil, icon: .firmware(rco: XmbRco.sysconf, name: "tex_sett", symbol: "gearshape"),
                       kind: .folder(gameSettingsGroups(&section, scope)))
    }
}
