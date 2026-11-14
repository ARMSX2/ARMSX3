// SPDX-License-Identifier: GPL-2.0-only
// The in-game XMB's six columns and the Game column's lists, ported from Android's XmbInGame.kt and XmbStates.kt.

import SwiftUI

extension XmbHost {
    static let runningGameID = "ingame.game"

    /// Android's in-game columns, in its order.
    func inGameSections() -> [XmbSection] {
        [inGameUsersSection(), inGameSettingsSection(), photoSection(), inGameMusicSection(), inGameGameSection(),
         inGameFriendsSection()]
    }

    // MARK: - Users, Settings, Music, RPCN

    private func inGameUsersSection() -> XmbSection {
        var section = usersSection()
        // Over a game the column holds Turn Off System alone.
        section.column.items.removeAll { $0.id != "users.off" }
        section.column.defaultFocus = 0
        // The game stops through the exit autosave first, as Android's exitApp stops the VM before it quits.
        section.actions["users.off"] = { [weak self] in
            guard let self else { return }
            ask(firmware.text(XmbRco.full, "msg_turnoff_system_ask", String(localized: "Do you want to turn off the system?")),
                buttons: [yes, no]) { button in
                guard button == 0, let calls = XmbInGame.shared.calls else { return }
                // A halted core cannot save or stop, so the app closes as the halt's own Close ARMSX3 does.
                if XmbInGame.shared.halted { _exit(0) }
                XmbInGame.shared.turnOffAfterExit = true
                calls.actions.exit()
            }
        }
        return section
    }

    /// The running title's settings, in Android's in-game order: the firmware, the app and the theme stay the home's.
    private func inGameSettingsSection() -> XmbSection {
        var section = settingsSection()
        let order = ["settings.game", "settings.core", "settings.display", "settings.sound", "settings.accessory", "settings.music",
                     "settings.network", "settings.debug"]
        let items = section.column.items
        section.column.items = order.compactMap { id in items.first { $0.id == id } }
        // The pause menu's live volume, first in Sound Settings, as the quick menu has it.
        if let index = section.column.items.firstIndex(where: { $0.id == "settings.sound" }) {
            let sound = section.column.items[index]
            if let volume = liveRange(&section, .volume, "ingame.volume", String(localized: "Volume"), icon: sound.icon, range: 0 ... 200, step: 10) {
                section.column.items[index].kind = .folder([volume] + (sound.children ?? []))
            }
        }
        return section
    }

    private func inGameMusicSection() -> XmbSection {
        var section = musicSection()
        // Android leaves Add Folder and the added folders to the home.
        section.column.items.removeAll { $0.id == "music.addFolder" || $0.id.hasPrefix("music.folder.") }
        let items = section.column.items
        section.column.defaultFocus = items.firstIndex { $0.id == "music.now" } ?? items.firstIndex { $0.id == "music.builtin" } ?? 0
        return section
    }

    /// RPCN with the running game's invites after Add a Friend, as Android lists them. Cross offers Join or Decline.
    private func inGameFriendsSection() -> XmbSection {
        var section = friendsSection()
        let icon = XmbIcon.firmware(rco: XmbRco.friend, name: "item_tex_Messages", symbol: "envelope")
        let invites = XmbInGame.shared.invites.map { invite in
            let id = "rpcn.invite.\(invite.id)"
            section.options[id] = { [weak self] option in
                if let self { XmbInGame.shared.answerInvite(invite, join: option == "join", host: self) }
            }
            let from = String(localized: "From \(invite.from)")
            let node = XmbNode(id: id, title: from,
                               subtitle: invite.subject.isEmpty ? invite.body : invite.subject, icon: icon, kind: .action,
                               options: [.init(id: "join", title: String(localized: "Join Game")),
                                         .init(id: "decline", title: String(localized: "Decline Invite"))])
            // Cross asks in the dialog band with the whole invite, as Android does; Triangle keeps the side panel.
            return section.action(node) { [weak self] in
                guard let self else { return }
                let message = [from, invite.subject, invite.body].filter { !$0.isEmpty }.joined(separator: "\n")
                ask(message, buttons: [String(localized: "Join"), String(localized: "Decline")]) { [weak self] answer in
                    if let self { XmbInGame.shared.answerInvite(invite, join: answer == 0, host: self) }
                }
            }
        }
        let at = (section.column.items.firstIndex { $0.id == "rpcn.add" }).map { $0 + 1 } ?? section.column.items.count
        section.column.items.insert(contentsOf: invites, at: at)
        return section
    }

    // MARK: - Game

    private func inGameGameSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "game", title: firmware.text(XmbRco.ingame, "msg_game", String(localized: "Game")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_game", symbol: "gamecontroller"), items: []))
        let store = XmbInGame.shared
        guard let game = store.game else { return section }
        let resume = String(localized: "Resume")
        let restart = String(localized: "Restart")
        let quit = firmware.text("impose_plugin", "msg_game_quit2", String(localized: "Quit Game"))
        let sysconf = { (texture: String, symbol: String) in XmbIcon.firmware(rco: XmbRco.sysconf, name: texture, symbol: symbol) }

        section.options[Self.runningGameID] = { [weak self] option in
            switch option {
            case "restart": self?.askRestart()
            case "quit": self?.askQuit()
            default: store.calls?.actions.resume()
            }
        }
        var items = [XmbNode(id: Self.runningGameID, title: game.title, subtitle: resume,
                             icon: .game(path: CustomCovers.path(for: game.serial, fallback: game.iconPath)), kind: .game,
                             options: [.init(id: "resume", title: resume), .init(id: "restart", title: restart), .init(id: "quit", title: quit)])]
        // Restart has no firmware icon of its own; XmbFirmware draws it from Quit's.
        items.append(section.action(XmbNode(id: "ingame.restart", title: restart, subtitle: nil,
                                            icon: .firmware(rco: XmbRco.game, name: "tex_quit#restart", symbol: "arrow.counterclockwise"),
                                            kind: .action)) { [weak self] in self?.askRestart() })
        items.append(discNode(&section, swap: false))
        if store.discSwap { items.append(discNode(&section, swap: true)) }
        items.append(portalNode(&section))
        items.append(section.action(XmbNode(id: "ingame.quit", title: quit, subtitle: nil,
                                            icon: .firmware(rco: XmbRco.game, name: "tex_quit", symbol: "xmark.circle"),
                                            kind: .action)) { [weak self] in self?.askQuit() })
        items.append(onScreenNode(&section, icon: sysconf("tex_display", "rectangle.on.rectangle")))
        items.append(section.action(XmbNode(id: "ingame.screenshot", title: String(localized: "Take Screenshot"),
                                            subtitle: String(localized: "Saves a picture of the game to Photo"),
                                            icon: .firmware(rco: XmbRco.photo, name: "tex_screenshot", symbol: "camera"),
                                            kind: .action)) { store.calls?.actions.screenshot() })
        items.append(section.action(XmbNode(id: "ingame.record",
                                            title: store.recording ? String(localized: "Stop Recording") : String(localized: "Record"),
                                            subtitle: nil, icon: .firmware(rco: XmbRco.video, name: "video_tex_default",
                                                                           symbol: "record.circle"),
                                            kind: .action)) { store.calls?.actions.record() })
        // The pause menu's other tabs as lists, as Android has them after the screenshot, then every core setting, all for this game.
        let scope = XmbSettingsScope(model: settingsModel, prefix: "ingame.")
        let display = sysconf("tex_display", "tv")
        let scale = liveRange(&section, .resolutionScale, "ingame.scale", String(localized: "Resolution Scale"), icon: display,
                              range: 25 ... 800, step: 25)
        items.append(pauseTab(&section, scope, .renderer, String(localized: "Renderer"), icon: display, live: scale))
        let power = sysconf("tex_power", "speedometer")
        let frameLimit = frameLimitRow(&section, icon: power)
        items.append(pauseTab(&section, scope, .performance, String(localized: "Performance"), icon: power, live: frameLimit))
        items.append(pauseTab(&section, scope, .fixes, String(localized: "Advanced"), icon: sysconf("tex_security", "wrench.and.screwdriver"),
                              live: nil))
        var controls = controlsNode(in: &section)
        controls.title = String(localized: "Controls")
        items.append(controls)
        items.append(allCoreSettingsGroup(&section, scope))
        items.append(toolsTrophies(id: "ingame.trophies", titleDir: URL(fileURLWithPath: game.bootPath, isDirectory: true)))
        items += stateNodes(&section)
        items.append(section.action(XmbNode(id: "ingame.report", title: String(localized: "Report a Problem"), subtitle: nil,
                                            icon: .symbol("exclamationmark.bubble"), kind: .action)) { store.calls?.actions.report() })
        section.column.items = items
        return section
    }

    private func askRestart() {
        ask(String(localized: "Do you want to restart the game?"), buttons: [yes, no]) { button in
            if button == 0 { XmbInGame.shared.calls?.restart() }
        }
    }

    private func askQuit() {
        ask(firmware.text("impose_plugin", "msg_game_quit_ask", String(localized: "Do you want to quit the game?")), buttons: [yes, no]) { button in
            if button == 0 { XmbInGame.shared.calls?.actions.exit() }
        }
    }

    /// Change Disc closes this game and starts another disc; Swap Disc, while the game takes one, changes it without closing.
    /// iOS reads no disc playlist, so each list is the file to choose.
    private func discNode(_ section: inout XmbSection, swap: Bool) -> XmbNode {
        let icon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_disc_bd", symbol: "opticaldisc")
        let id = swap ? "ingame.swapDisc" : "ingame.changeDisc"
        let pick = section.action(XmbNode(id: id + ".pick", title: String(localized: "Choose a Disc File…"),
                                          subtitle: String(localized: "An ISO, or any file inside a disc folder"), icon: icon,
                                          kind: .action)) {
            let actions = XmbInGame.shared.calls?.actions
            if swap { actions?.changeDisc() } else { actions?.bootDisc() }
        }
        return XmbNode(id: id, title: swap ? String(localized: "Swap Disc") : String(localized: "Change Disc"),
                       subtitle: swap ? String(localized: "Change disc without closing") : String(localized: "Close this game, start another disc"),
                       icon: icon, kind: .folder([pick]))
    }

    /// One of the pause menu's tabs as a list: its live row first, then the pane's settings for this game.
    private func pauseTab(_ section: inout XmbSection, _ scope: XmbSettingsScope, _ pane: SettingsPane, _ title: String, icon: XmbIcon,
                          live: XmbNode?) -> XmbNode {
        var node = paneFolder(&section, scope, pane, icon: icon)
        node.title = title
        node.kind = .folder([live].compactMap { $0 } + (node.children ?? []))
        return node
    }

    /// On-Screen: the performance overlay and, while the touch pad is up, its layout.
    private func onScreenNode(_ section: inout XmbSection, icon: XmbIcon) -> XmbNode {
        let store = XmbInGame.shared
        var rows: [XmbNode] = []
        if let on = store.live[Rpcs3LiveSetting.perfOverlay.rawValue] {
            rows.append(consoleToggle(&section, "ingame.osd.overlay", String(localized: "Performance Overlay"), icon: icon, isOn: on == 1) { on in
                store.setLive(.perfOverlay, on ? 1 : 0)
            })
        }
        if store.touchLayout {
            rows.append(section.action(XmbNode(id: "ingame.osd.layout", title: String(localized: "Edit Touch Layout"), subtitle: nil, icon: icon,
                                               kind: .action)) { store.calls?.actions.touchLayout?() })
        }
        return XmbNode(id: "ingame.osd", title: String(localized: "On-Screen"), subtitle: nil, icon: icon, kind: .folder(rows))
    }

    /// One of the core's live values as a range, once it has been read.
    private func liveRange(_ section: inout XmbSection, _ which: Rpcs3LiveSetting, _ id: String, _ title: String, icon: XmbIcon,
                           range: ClosedRange<Int>, step: Int) -> XmbNode? {
        guard let value = XmbInGame.shared.live[which.rawValue] else { return nil }
        return consoleRange(&section, id, title, icon: icon, value: value, range: range, step: step, unit: "%") { value in
            XmbInGame.shared.setLive(which, value)
        }
    }

    /// The quick menu's frame limits, in its order.
    private func frameLimitRow(_ section: inout XmbSection, icon: XmbIcon) -> XmbNode? {
        guard let current = XmbInGame.shared.live[Rpcs3LiveSetting.frameLimit.rawValue] else { return nil }
        let limits = QuickMenuView.frameLimits
        let names = limits.map { limit in
            switch limit {
            case 0: String(localized: "Auto")
            case 1: String(localized: "Display rate")
            default: "\(limit)"
            }
        }
        return consoleChoice(&section, "ingame.framelimit", String(localized: "Frame Limit"), icon: icon, choices: names,
                             selected: limits.firstIndex(of: current)) { choice in
            XmbInGame.shared.setLive(.frameLimit, limits[choice])
        }
    }

    // MARK: - Save State and Load State

    /// Save State lists the ten slots, Load State the ones that hold a state with the exit autosave first, each with its
    /// picture beside the list. Cross saves or loads and goes back to the game; Triangle deletes.
    private func stateNodes(_ section: inout XmbSection) -> [XmbNode] {
        let store = XmbInGame.shared
        let tools = XmbToolsStore.shared
        // Read again each time either list opens, so what was saved shows straight away.
        let saveOpen = tools.justOpened("ingame.save", isOpen("ingame.save"))
        let loadOpen = tools.justOpened("ingame.load", isOpen("ingame.load"))
        if saveOpen || loadOpen { Task { await store.readStates(settings: settings) } }
        let saveIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "square.and.arrow.down")
        let loadIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "square.and.arrow.up")
        let empty = String(localized: "Empty")
        let states = store.states ?? []

        var saves: [XmbNode] = []
        if store.states != nil {
            for slot in 1 ... 10 {
                guard let fileSlot = SaveStateFiles.fileSlot(forUISlot: slot) else { continue }
                let state = states.first { $0.uiSlot == slot }
                let id = "ingame.save.slot.\(slot)"
                let picture: XmbIcon = state?.picture.map { .game(path: $0) } ?? saveIcon
                var node = XmbNode(id: id, title: state?.title ?? String(localized: "Slot \(slot)"),
                                   subtitle: state.map { SaveStateFormat.savedAt($0.modified) } ?? empty, icon: picture, kind: .action, aside: picture)
                if let state {
                    node.options = [.init(id: "delete", title: String(localized: "Delete"))]
                    section.options[id] = { [weak self] _ in self?.deleteState(state) }
                }
                saves.append(section.action(node) { [weak self] in
                    guard let self else { return }
                    guard state != nil else {
                        store.calls?.saveSlot(fileSlot)
                        return
                    }
                    ask(String(localized: "Overwrite the state saved in this slot?"), buttons: [yes, no], focus: 1) { button in
                        if button == 0 { store.calls?.saveSlot(fileSlot) }
                    }
                })
            }
        }

        var loads: [XmbNode] = []
        if store.states != nil {
            // The exit autosave first, then the slots, then the quick save the hotkey makes.
            let order = states.filter { $0.uiSlot == SaveStateSlot.autoSlot } + states.filter { (1 ... 10).contains($0.uiSlot ?? 0) }.sorted { $0.uiSlot ?? 0 < $1.uiSlot ?? 0 }
                + states.filter { $0.uiSlot == SaveStateSlot.quickSlot }
            for state in order {
                let id = "ingame.load.slot.\(state.uiSlot ?? 0)"
                let picture: XmbIcon = state.picture.map { .game(path: $0) } ?? loadIcon
                let when = SaveStateFormat.savedAt(state.modified)
                let subtitle = state.uiSlot == SaveStateSlot.autoSlot ? when + " " + String(localized: "(saved on exit)") : when
                section.options[id] = { [weak self] _ in self?.deleteState(state) }
                loads.append(section.action(XmbNode(id: id, title: state.title, subtitle: subtitle, icon: picture, kind: .action,
                                                    options: [.init(id: "delete", title: String(localized: "Delete"))], aside: picture)) {
                    store.calls?.loadState(state.url)
                })
            }
            if loads.isEmpty {
                loads = [XmbNode(id: "ingame.load.none", title: String(localized: "No save states yet"), subtitle: nil, icon: loadIcon, kind: .info)]
            }
        }
        // A list not read yet shows a row, since an empty folder does not open.
        let reading = XmbNode(id: "ingame.states.reading", title: "…", subtitle: nil, icon: saveIcon, kind: .info)
        return [XmbNode(id: "ingame.save", title: String(localized: "Save State"), subtitle: nil, icon: saveIcon,
                        kind: .folder(saves.isEmpty ? [reading] : saves)),
                XmbNode(id: "ingame.load", title: String(localized: "Load State"), subtitle: nil, icon: loadIcon,
                        kind: .folder(loads.isEmpty ? [reading] : loads))]
    }

    private func deleteState(_ state: XmbSavedState) {
        ask(state.deleteQuestion, buttons: [yes, no], focus: 1) { [weak self] button in
            guard button == 0, let self else { return }
            guard state.delete() else { return notice(String(localized: "Couldn't delete that save state.")) }
            Task { await XmbInGame.shared.readStates(settings: self.settings) }
        }
    }
}
