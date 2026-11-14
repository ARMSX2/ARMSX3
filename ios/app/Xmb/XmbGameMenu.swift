// SPDX-License-Identifier: GPL-2.0-only
// The XMB over a paused game, in place of the quick menu while XMB mode is on, after XmbInGame.kt and XmbNotice.kt.

import SwiftUI

/// The in-game XMB's own state: one XMB per game, so each starts on its Game column and keeps its place while that game
/// is the one played, and what the game screen hands it for the rows to call.
@MainActor @Observable final class XmbInGame {
    static let shared = XmbInGame()

    /// What the game screen does for a row.
    struct Calls {
        var actions: QuickMenuView.Actions
        var restart: () -> Void
        /// A file slot: 0 to 9 numbered.
        var saveSlot: (Int) -> Void
        var loadState: (URL) -> Void
        var declined: (UInt64) -> Void
    }

    @ObservationIgnored var calls: Calls?
    /// The disc Change Disc starts once this game has closed, by boot path.
    var nextDisc: String?
    /// A game screen is up, wherever it was opened from.
    var screenUp = false
    /// The core halted, so no exit save can run and Turn Off System closes the app at once.
    @ObservationIgnored var halted = false
    /// Turn Off System was picked over the game, so the app quits once the game has stopped.
    @ObservationIgnored var turnOffAfterExit = false
    private(set) var game: Rpcs3GameEntry?
    var discSwap = false
    var recording = false
    /// The touch pad is up, so its layout can be edited.
    var touchLayout = false
    var invites: [RpcnSession.Invite] = []
    /// The core's live values by Rpcs3LiveSetting raw value, read as the menu opens.
    private(set) var live: [Int: Int] = [:]
    /// The running game's save states; nil until read.
    private(set) var states: [XmbSavedState]?
    /// Why the game stopped, waiting for the XMB to say so.
    private(set) var error: String?
    @ObservationIgnored private var host: XmbHost?

    /// The XMB for `game`: made again for each game, so each starts on its own Game column.
    func host(for game: Rpcs3GameEntry, controllerInput: MenuControllerInputRouter?) -> XmbHost {
        if let host, self.game?.bootPath == game.bootPath { return host }
        let settings = SettingsScreenModel(serial: game.serial, gameTitle: game.title, scope: .game, fromGameHost: true, gameRunning: true)
        let made = XmbHost(library: LibraryModel(), controllerInput: controllerInput, settingsModel: settings)
        host = made
        self.game = game
        states = nil
        live = [:]
        return made
    }

    /// The game has ended: its XMB and what the game screen handed it go with it.
    func end() {
        halted = false
        host?.leave()
        host = nil
        game = nil
        calls = nil
        states = nil
        live = [:]
        invites = []
        error = nil
    }

    func takeNextDisc() -> String? {
        defer { nextDisc = nil }
        return nextDisc
    }

    func readLive() async {
        for which in QuickMenuView.liveSettings { live[which.rawValue] = await EmulatorSession.shared.liveValue(which) }
    }

    func setLive(_ which: Rpcs3LiveSetting, _ value: Int) {
        Task {
            if await EmulatorSession.shared.setLive(which, value) == 0 { live[which.rawValue] = value }
        }
    }

    func readStates(settings: SettingsStore) async {
        guard let game, let directory = await EmulatorSession.shared.saveStateDirectory(serial: game.serial) else {
            states = []
            return
        }
        states = await XmbSavedState.read([directory], settings: settings)
    }

    func answerInvite(_ invite: RpcnSession.Invite, join: Bool, host: XmbHost) {
        Task {
            if join {
                guard await FriendsPanel.accept(invite.id) else { return host.notice(String(localized: "That invite can't be used any more.")) }
                calls?.actions.resume()
                return
            }
            let code = await withCheckedContinuation { continuation in
                Rpcs3Bridge.rpcnDeclineInvite(invite.id) { continuation.resume(returning: $0) }
            }
            // Gone either way: declined, or no longer one the game can use. The next read puts back one that is.
            calls?.declined(invite.id)
            host.notice(code == 0 ? String(localized: "Declined the invite from \(invite.from).")
                : String(localized: "That invite can't be used any more."))
        }
    }

    /// A halt, said as the console's error dialog in the XMB; false for a status that is no halt.
    func gameError(_ status: Int, jitReason: String) -> Bool {
        let reason: String
        switch status {
        case EmulatorSession.gameFatal:
            reason = String(localized: "The game stopped because of an error. Close ARMSX3 and open it again to keep playing.")
        case EmulatorSession.stopHung:
            reason = String(localized: "The game isn't responding, so it can't be stopped. Close ARMSX3 and open it again to keep playing.")
        case EmulatorSession.rendererDied:
            reason = String(localized: "The game's graphics crashed, so it can't be stopped safely. Close ARMSX3 and open it again to keep playing.")
        case EmulatorSession.jitExhausted where jitReason != "helper-ok":
            reason = String(localized: "The game ran out of JIT memory. Built-in JIT prepares more. Set it up in Settings › JIT and Device, then open ARMSX3 again.")
        case EmulatorSession.jitExhausted:
            reason = String(localized: "The game ran out of JIT memory. Close ARMSX3 and open it again.")
        default:
            return false
        }
        error = String(localized: "The game stopped\n\(reason)")
        halted = true
        return true
    }

    /// The error for the XMB to show, once.
    func takeError() -> String? {
        defer { error = nil }
        return error
    }
}

struct XmbGameMenu: View {
    let game: Rpcs3GameEntry
    /// A save, a load or a stop is in flight.
    let busy: Bool
    var controllerInput: MenuControllerInputRouter?
    var discSwap = false
    var recording = false
    var invites: [RpcnSession.Invite] = []
    /// A sheet the game screen opened over the menu, such as Report a Problem or the disc picker, has the pad.
    var sheetUp = false

    @State private var appear = 0.0
    private let host: XmbHost
    private var store: XmbInGame { .shared }

    init(game: Rpcs3GameEntry, busy: Bool, calls: XmbInGame.Calls, controllerInput: MenuControllerInputRouter?, discSwap: Bool = false,
         recording: Bool = false, invites: [RpcnSession.Invite] = [], sheetUp: Bool = false) {
        self.game = game
        self.sheetUp = sheetUp
        self.busy = busy
        self.controllerInput = controllerInput
        self.discSwap = discSwap
        self.recording = recording
        self.invites = invites
        XmbInGame.shared.calls = calls
        host = XmbInGame.shared.host(for: game, controllerInput: controllerInput)
    }

    var body: some View {
        @Bindable var host = host
        ZStack {
            // The paused frame stays in view, dimmed, as the console dims the game under its menu.
            Color.black.opacity(0.6).ignoresSafeArea()
            XmbPicker(host: host)
            XmbView(state: host.state, firmware: host.firmware, avatar: host.avatar,
                    rootHint: host.firmware.text(XmbRco.ingame, "msg_help_back_to_game", String(localized: ": Back to Game Screen")), act: act)
                .accessibilityHidden(host.columnsHidden)
            XmbOverlays(host: host, overGame: true)
        }
        // The game dims behind and the XMB fades in over it.
        .opacity(appear)
        .onAppear {
            // The in-game XMB never opens on a picture or a page, and one left open in the home would draw over the game.
            XmbPhotoViewing.shared.close()
            XmbReading.shared.close()
            withAnimation(.xmb(0.22)) { appear = 1 }
            // A sheet that opened with the menu keeps the pad until it closes.
            controllerInput?.setNavigationCaptured(EmulatorSession.shared.quickMenuOpen && !sheetUp && host.presented == nil,
                                                   owner: QuickMenuView.controllerScope,
                                                   priority: 300, padFirst: true)
        }
        .onChange(of: discSwap, initial: true) { _, swap in store.discSwap = swap }
        .onChange(of: recording, initial: true) { _, on in store.recording = on }
        .onChange(of: invites, initial: true) { _, now in store.invites = now }
        .onChange(of: store.calls?.actions.touchLayout != nil, initial: true) { _, up in store.touchLayout = up }
        .onChange(of: controllerInput?.latestEvent) { _, event in
            guard let event, event.captureOwner == QuickMenuView.controllerScope else { return }
            press(event.command)
        }
        // Friends are read while their column is in view.
        .onChange(of: host.state.current?.id == "rpcn", initial: true) { _, on in XmbFriendsPolling.shared.setShown(on) }
        .onChange(of: store.error) { _, _ in showError() }
        .onChange(of: host.rpcn.currentAvatar) { _, _ in Task { await host.readAvatar() } }
        // The quick menu's capture takes every press first, so a settings sheet's own navigation gets them only while it is let go.
        .onChange(of: host.presented != nil || sheetUp) { _, sheet in
            controllerInput?.setNavigationCaptured(!sheet, owner: QuickMenuView.controllerScope, priority: 300, padFirst: true)
        }
        .onDisappear {
            if EmulatorSession.shared.quickMenuOpen {
                controllerInput?.setNavigationCaptured(true, owner: QuickMenuView.controllerScope, priority: 300)
            }
            XmbFriendsPolling.shared.setShown(false)
            XmbAvatarPictures.shared.stop(host)
            // Music picked here plays while the game waits, and stops as it carries on. A game that quits with the menu still
            // open leaves after its screen, whose cover has already handed the music back to the home, so it is left alone.
            if XmbInGame.shared.screenUp { host.media.stop() }
            // The XMB can close by the menu button, under a panel, picture or page that would otherwise wait for the next XMB.
            host.media.nowPlaying = nil
            XmbPhotoViewing.shared.close()
            XmbReading.shared.close()
        }
        .task {
            await host.startInGame { $0.inGameSections() }
            XmbAvatarPictures.shared.resume(host)
            // A screenshot taken since the last opening shows in the Photo column.
            XmbPhotos.shared.refresh()
            showError()
            // The place is kept between openings, so a list left open is read again for what changed while the game ran.
            if host.isOpen("ingame.save") || host.isOpen("ingame.load") { await store.readStates(settings: host.settings) }
            if host.isOpen("ingame.portal") { await XmbPortals.shared.read() }
            if host.isOpen("ingame.trophies") {
                await XmbToolsStore.shared.loadTrophies(titleDir: URL(fileURLWithPath: game.bootPath, isDirectory: true), refresh: true)
            }
            await store.readLive()
        }
        // The home's sheetClosed would scan the library, which reaches the core the game is using; settings are all a sheet here changes.
        .sheet(item: $host.presented, onDismiss: {
            Task {
                await host.settingsModel.load()
                // Edit Layout… remaps through the Controllers pane's own model.
                await XmbControlsStore.shared.model.load()
            }
        }) { presented in
            switch presented {
            case .pane(let path):
                XmbSettingsSheet(model: host.settingsModel, path: path, controllerInput: controllerInput)
            case .library(.patches(let serial, let title)):
                PatchesView(serial: serial, title: title)
            case .library:
                EmptyView()
            }
        }
    }

    private func showError() {
        guard !host.state.columns.isEmpty, let error = store.takeError() else { return }
        host.notice(error)
    }

    /// Something of the XMB's own over it: the keyboard, Now Playing, a picture or text to read. A video the home left
    /// paused waits for the home.
    private var overlayOpen: Bool {
        host.keyboard != nil || host.media.nowPlaying != nil || XmbPhotoViewing.shared.show != nil
            || XmbReading.shared.page != nil
    }

    private func press(_ command: MenuControllerCommand) {
        // A sheet or a picker over the XMB takes the pad.
        guard !host.covered, !sheetUp else { return }
        if overlayOpen {
            guard !busy || command == .back else { return }
            return host.press(command)
        }
        act(XmbAction(command))
    }

    /// What the home does with a press, and what the game adds: Circle at the top resumes, Cross on the game resumes and
    /// Square on it opens the Settings column.
    private func act(_ action: XmbAction) {
        // A save, a load or a stop in flight finishes before anything else is asked of the game.
        guard !busy || action == .back else { return }
        let state = host.state
        let free = !state.busy && !overlayOpen
        let onGame: Bool
        switch action {
        case .confirm:
            onGame = state.focused?.id == XmbHost.runningGameID
        case .focusAndConfirm(let index):
            let items = state.items(atDepth: state.depth) ?? []
            onGame = items.indices.contains(index) && items[index].id == XmbHost.runningGameID
        case .square:
            guard free, state.focused?.id == XmbHost.runningGameID,
                  let settings = state.columns.firstIndex(where: { $0.id == "settings" }) else { return }
            return host.perform(.column(settings))
        default:
            onGame = false
        }
        let atRoot = action == .back && free && state.depth == 0
        host.perform(action)
        if (free && onGame) || atRoot { store.calls?.actions.resume() }
    }
}

/// The XMB's notices over the game while XMB mode is on, whether or not the XMB is up, as Android's are.
struct XmbGameNotices: View {
    var body: some View {
        GeometryReader { geometry in
            XmbNoticeStack(firmware: XmbFirmware.shared, m: XmbMetrics(width: geometry.size.width, height: geometry.size.height))
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}
