// SPDX-License-Identifier: GPL-2.0-only
// XMB mode's state and what its items do: the columns are rebuilt from the app's models whenever one of them changes.

import Observation
import SwiftUI

/// One column and what its nodes do, by node id.
struct XmbSection {
    var column: XmbColumn
    /// Cross on an action node.
    var actions: [String: @MainActor () -> Void] = [:]
    /// A choice picked for a setting node.
    var picks: [String: @MainActor (Int) -> Void] = [:]
    /// An option picked for a node, by the option's id.
    var options: [String: @MainActor (String) -> Void] = [:]
    /// A range's value as it moves.
    var ranges: [String: @MainActor (Double) -> Void] = [:]

    mutating func action(_ node: XmbNode, _ run: @escaping @MainActor () -> Void) -> XmbNode {
        actions[node.id] = run
        return node
    }
}

/// What the home presents over the XMB.
enum XmbPresented: Identifiable {
    case library(LibrarySheet)
    case pane([SettingsPane])

    var id: String {
        switch self {
        case .library(let sheet): return "library." + sheet.id
        case .pane(let path): return "pane." + path.map(\.navigationKey).joined(separator: ".")
        }
    }
}

/// Which Files picker is up over the XMB, and what its files are for.
enum XmbPicking: Equatable {
    case music(XmbMedia.Pick)
    case videos(XmbVideos.Pick)
    case tools(XmbToolsStore.Pick)
    case theme
    case figures(portal: Int)
    case avatarPicture
}

/// The library grid has a sheet, a picker or a game up. Switching XMB mode on then, or an upright iPad, keeps the library
/// until it is done, since taking it away would close what it presents.
@MainActor @Observable final class XmbLibraryCover {
    static let shared = XmbLibraryCover()
    var presenting = false
    /// Setup covers the whole app, so the XMB under it lets the pad and its sound go.
    var setupUp = false
    /// The root's crash question, its report or a link's message is up, and the pad is theirs.
    var rootModal = false
}

@MainActor @Observable final class XmbHost {
    let library: LibraryModel
    let controllerInput: MenuControllerInputRouter?
    let settings = SettingsStore.shared
    let settingsModel: SettingsScreenModel
    let installs = InstallModel()
    let updates = UpdateStore.shared
    let firmware = XmbFirmware.shared
    let rpcn = RpcnModel.shared
    let media = XmbMedia.shared

    private(set) var state = XmbState()
    var presented: XmbPresented?
    var playing: PlayRequest?
    /// The game screen this XMB opened is on screen, from its appearing until it has gone, slide out included.
    var gameUp = false
    /// Change Disc's next disc is waiting for an update to install, so the screen keeps the game's way up meanwhile.
    private(set) var changingDisc = false
    /// The Files picker that is up, if any.
    private(set) var picking: XmbPicking?
    /// What the last picker was for: it clears `picking` as it closes, before its files come in.
    @ObservationIgnored private(set) var picked: XmbPicking?
    /// The last search typed in each settings list, by the list's node id.
    var searches: [String: String] = [:]
    /// The node whose options are the playlists for a moment, after Add to Playlist in the Music and Photo columns.
    var playlistOffer: String?
    /// Whether System Update was open at the last build, so the firmware version is read again as it opens.
    @ObservationIgnored var updateWasOpen = false
    /// The XMB's keyboard, while a line of text is being asked for.
    private(set) var keyboard: XmbKeyboard?
    /// Folders whose rows are only built while they are open, by node id.
    private(set) var openFolders: Set<String> = []
    private(set) var avatar: UIImage?
    /// The avatar as a picture file for the XMB's icons.
    private(set) var avatarFile: String?
    var licenceEntry: LibraryCache.Entry?
    /// False while the library stands in for the XMB.
    var shown = true
    var booted = false

    @ObservationIgnored private var sections: [XmbSection] = []
    @ObservationIgnored private var answer: (@MainActor (Int) -> Void)?
    @ObservationIgnored private var waiting: [Question] = []
    @ObservationIgnored private var typed: (@MainActor (String) -> Void)?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var saved = XmbState.Place(column: nil, focus: [:])
    /// Set for the XMB over a running game: its columns in place of the home's, and its place kept with it, never saved.
    @ObservationIgnored private var gameColumns: (@MainActor (XmbHost) -> [XmbSection])?

    @ObservationIgnored private var trackings = 0

    static let captureOwner = "xmb.home"
    /// The launch's notices wait for the XMB to be seen.
    var launchNoticesDue = false
    /// The home has a sheet up, so the root waits for it to close before it presents setup.
    static var sheetUp = false

    init(library: LibraryModel, controllerInput: MenuControllerInputRouter?, settingsModel: SettingsScreenModel? = nil) {
        self.library = library
        self.controllerInput = controllerInput
        self.settingsModel = settingsModel ?? SettingsScreenModel()
    }

    /// The XMB over a running game.
    var overGame: Bool { gameColumns != nil }

    /// While a sheet or the game is up, the pad belongs to it.
    var covered: Bool {
        presented != nil || playing != nil || picking != nil
    }

    /// Now Playing, a video or a photo fills the screen, so the wave and the focused game's icon can rest.
    var overlaid: Bool {
        media.nowPlaying != nil || XmbVideoPlayback.shared.isOpen || XmbPhotoViewing.shared.show != nil
    }

    /// The keyboard or a panel over the whole XMB: VoiceOver skips the columns under it, and nothing acts on them.
    var columnsHidden: Bool {
        keyboard != nil || media.nowPlaying != nil || XmbPhotoViewing.shared.show != nil
            || (!overGame && XmbVideoPlayback.shared.isOpen)
    }

    func capture() {
        // The root's alerts and report sheet are not this XMB's, so it lets the pad go to them too.
        let wanted = shown && !covered && !XmbLibraryCover.shared.rootModal && (controllerInput?.isMenuActive ?? false)
        controllerInput?.setNavigationCaptured(wanted, owner: Self.captureOwner, priority: 100, padFirst: true)
    }

    func releaseCapture() {
        controllerInput?.setNavigationCaptured(false, owner: Self.captureOwner, priority: 100)
    }

    // MARK: - Loading

    func start() async {
        guard !started else { return }
        started = true
        rebuild()
        async let games: Void = library.start()
        async let rows: Void = settingsModel.load()
        async let assets: Void = firmware.load()
        async let titles: Void = installs.rescanInstalled()
        async let account: Void = rpcn.refresh()
        async let files = media.scanMusic()
        _ = await (games, rows, assets, titles, account, files)
        // An install the app was closed during leaves a half-written game, which the library's Install sheet would say.
        installs.noteInterruptedInstall()
        if let message = installs.message { notice(message) }
        XmbPhotos.shared.refresh()
        // The library's Sounds pane may have changed the trophy sound since the XMB last looked.
        await XmbMusicColumn.shared.look()
        await loadAvatar()
        launchNoticesDue = true
    }

    /// The cards the library shows at launch, said in the XMB once it is on screen and its boot is over: why JIT fell back,
    /// with its setup, and a missing firmware. While the library stands in, its own card says them.
    func sayLaunchNotices() async {
        // A notice the library's card has shown (its marks are taken then) is not told again.
        if let jit = JitNoticeModel.shared.current, !JitNoticeModel.shared.marks.isEmpty {
            JitNoticeModel.shared.appeared()
            JitNoticeModel.shared.dismiss()
            if jit.action != nil {
                ask(jit.title + "\n" + jit.body, buttons: [String(localized: "Set Up Built-in JIT"), no]) { [weak self] button in
                    if button == 0 { self?.presented = .pane([.jitDevice]) }
                }
            } else {
                notice(jit.title + "\n" + jit.body, error: jit.symbol != "info.circle.fill")
            }
        }
        await installs.refreshVersion()
        if installs.version == "" {
            notice(String(localized: "No PS3 firmware is installed, so games can't start. Install it in Settings › System Update."))
        }
    }

    /// The XMB over a running game. The library is left alone: scanning it would reach the core the game is using.
    func startInGame(_ sections: @escaping @MainActor (XmbHost) -> [XmbSection]) async {
        guard !started else { return }
        started = true
        gameColumns = sections
        rebuild()
        async let rows: Void = settingsModel.load()
        async let assets: Void = firmware.load()
        async let account: Void = rpcn.refresh()
        async let files = media.scanMusic()
        _ = await (rows, assets, account, files)
        XmbPhotos.shared.refresh()
        await loadAvatar()
    }

    /// Nothing shows this XMB any more: its columns stop following the models until it starts again.
    func leave() {
        started = false
        launchNoticesDue = false
    }

    /// The columns again whenever anything they were built from changes.
    private func rebuild() {
        guard started else { return }
        // open() rebuilds while a tracking is still live, so only the newest one rebuilds again.
        trackings += 1
        let current = trackings
        let built = withObservationTracking { buildSections() } onChange: { [weak self] in
            Task { @MainActor in if self?.trackings == current { self?.rebuild() } }
        }
        sections = built
        let columns = built.map(\.column)
        if state.columns.isEmpty {
            // Where the player was last time, or the Game column, as the console starts.
            state = XmbState(columns: columns, column: columns.firstIndex { $0.id == "game" } ?? 0)
            if gameColumns == nil { state.restore(Self.savedPlace()) }
            saved = state.place
        } else if state.columns != columns {
            state.attach(columns)
            syncOpenFolders()
        }
    }

    private func buildSections() -> [XmbSection] {
        if let gameColumns { return gameColumns(self) }
        return [usersSection(), settingsSection(), photoSection(), musicSection(), videoSection(),
         toolsSection(), gameSection(), networkSection(), armsx3Section(), friendsSection()]
    }

    private func syncOpenFolders() {
        let open = Set(state.levels.map(\.node))
        if open != openFolders { openFolders = open }
    }

    func isOpen(_ folder: String) -> Bool { openFolders.contains(folder) }

    /// Opens a folder by id, as Cross on it would: one in the open list, or one of the column's hidden ones.
    func open(_ folderID: String) {
        // Built now, so a folder that a model change has just made is there to open.
        rebuild()
        let outcome = state.open(folderID)
        syncOpenFolders()
        if outcome == .opened { firmware.play(.decide) }
    }

    private func loadAvatar() async {
        await rpcn.loadAvatars()
        await readAvatar()
    }

    /// The avatar's picture again, as it changes.
    func readAvatar() async {
        guard let current = rpcn.currentAvatar else { return }
        avatar = await rpcn.avatarImage(current.path)
        avatarFile = await XmbAvatarPictures.shared.file(for: current.path)
    }

    /// A question or a notice for the dark band. `error` is nil for a question, else the notice's tone.
    private struct Question {
        var id = "ask"
        let message: String
        let buttons: [String]
        let focus: Int
        let error: Bool?
        let answer: (@MainActor (Int) -> Void)?
    }

    /// The game whose own music and moving icon may play, whether its music must keep quiet, and whether its icon must stay still.
    struct GameFocus: Equatable {
        var node: String?
        var quiet: Bool
        var still: Bool
    }

    func gameFocus(booting: Bool) -> GameFocus {
        // A running game has the sound and the screen: no game is in focus for as long as it runs.
        let node = shown && playing == nil && state.depth == 0 ? state.focused : nil
        // Music the player chose to play is not talked over.
        let ownMusic = media.chosen && media.enabled && !media.queue.isEmpty && !media.userPaused
        // A sheet or a full-screen layer hides the column, so its icon does not decode unseen; its PIC1 stays.
        let hidden = covered || overlaid
        return GameFocus(node: node?.isGame == true ? node?.id : nil,
                         quiet: booting || hidden || keyboard != nil || state.busy || ownMusic, still: hidden)
    }

    // MARK: - Input

    /// A press on the pad. The keyboard tells Triangle from Square, so it takes the command itself.
    func press(_ command: MenuControllerCommand) {
        // A video or a picture has the screen, and its own use for every button; the in-game XMB draws no video.
        if !overGame, XmbVideoPlayback.shared.isOpen {
            if let sound = XmbVideoPlayback.shared.apply(command) { firmware.play(sound) }
            return
        }
        if XmbPhotoViewing.shared.show != nil {
            if let sound = XmbPhotoViewing.shared.apply(command) { firmware.play(sound) }
            return
        }
        guard var keys = keyboard else {
            // Now Playing has its own use for every button.
            if let panel = media.nowPlaying {
                if let sound = panel.apply(command) { firmware.play(sound) }
                return
            }
            return perform(XmbAction(command))
        }
        let result = keys.apply(command)
        keyboard = keys
        finish(result)
    }

    func touch(_ touch: XmbKeyboard.Touch) {
        guard var keys = keyboard else { return }
        let result = keys.apply(touch)
        keyboard = keys
        finish(result)
    }

    private func finish(_ result: XmbKeyboard.Result) {
        switch result {
        case .moved, .typed:
            firmware.play(.cursor)
        case .done(let text):
            firmware.play(.decide)
            let done = typed
            keyboard = nil
            typed = nil
            done?(text)
        case .cancelled:
            // Android hands over what was typed however the keyboard closes.
            firmware.play(.cancel)
            let done = typed
            let text = keyboard?.text ?? ""
            keyboard = nil
            typed = nil
            done?(text)
        case .edited, .refused:
            break
        }
    }

    func perform(_ action: XmbAction) {
        // Text open to read takes the pad until it is closed.
        if XmbReading.shared.page != nil {
            if let sound = XmbReading.shared.apply(action) { firmware.play(sound) }
            return
        }
        guard !columnsHidden else { return }
        let outcome = state.apply(action)
        syncOpenFolders()
        state.sounds(for: outcome, after: action).forEach(firmware.play)
        savePlace()
        switch outcome {
        case .moved:
            controllerInput?.playTouchHaptics(.move(.down))
        case .run(let id):
            sections.lazy.compactMap { $0.actions[id] }.first?()
        case .launch(let id):
            if let entry = entry(id) { launch(entry) }
        case .option(let node, let option):
            sections.lazy.compactMap { $0.options[node] }.first?(option)
        case .pick(let node, let choice):
            sections.lazy.compactMap { $0.picks[node] }.first?(choice)
        case .adjusted(let node, let value):
            sections.lazy.compactMap { $0.ranges[node] }.first?(value)
        case .gameSettings(let id):
            if let entry = entry(id) { openGameSettings(entry) }
        case .answer(let dialog, let button):
            let done = answer
            answer = nil
            // Circle closes a question without answering it, except the launch wait, which it cancels as the library's does.
            if action != .back || dialog == Self.launchWaitID { done?(button) }
            // What was asked while this one was up comes next.
            if state.dialog == nil, !waiting.isEmpty { show(waiting.removeFirst()) }
        case .none, .refused, .opened, .closed, .atRoot:
            break
        }
    }

    // MARK: - What items use

    /// A question in the XMB's dark band. The last button must be the one that changes nothing.
    func ask(_ message: String, buttons: [String], focus: Int = 0, answer: @escaping @MainActor (Int) -> Void) {
        put(Question(message: message, buttons: buttons, focus: focus, error: nil, answer: answer))
    }

    /// Asks for a line of text. `done` gets what was typed when the keyboard closes, by Done or by Circle, as on Android.
    func type(_ title: String, initial: String = "", secure: Bool = false, done: @escaping @MainActor (String) -> Void) {
        typed = done
        keyboard = XmbKeyboard(title: title, text: initial, secure: secure)
    }

    /// Opens the Files picker; `filesPicked` sends what it brings to the row that asked.
    func pick(_ what: XmbPicking) {
        picked = what
        picking = what
    }

    func pickerClosed() { picking = nil }

    func filesPicked(_ urls: [URL]) {
        switch picked {
        case .music(let pick)?:
            musicImported(urls, pick)
        case .videos(let pick)?:
            videosPicked(urls, for: pick)
        case .tools(.packages)?:
            if !urls.isEmpty { installPackages(urls) }
        case .tools(.patch)?:
            if let url = urls.first { importPatches(url) }
        case .theme?:
            if let url = urls.first { themeImported(url) }
        case .figures(let portal)?:
            figuresPicked(urls, portal: portal)
        case .avatarPicture?:
            if let url = urls.first { useAvatarPicture(url) }
        case nil:
            break
        }
    }

    /// Something to tell the player. `error` picks the console's sound: its error tone, or its chime for good news.
    func notice(_ message: String, error: Bool = true) {
        put(Question(message: message, buttons: [String(localized: "OK")], focus: 0, error: error, answer: nil))
    }

    /// One dialog at a time: a result that comes in while a question is up waits for its answer.
    private func put(_ question: Question) {
        if state.dialog == nil { show(question) } else { waiting.append(question) }
    }

    private func show(_ question: Question) {
        answer = question.answer
        if let error = question.error {
            firmware.play(state.tell("notice", message: question.message, ok: question.buttons[0], error: error))
        } else {
            firmware.play(state.ask(question.id, message: question.message, buttons: question.buttons, focus: question.focus))
        }
    }

    // MARK: - The place across launches

    private static let columnKey = "xmb.home.column"
    private static let focusKey = "xmb.home.focus"

    private static func savedPlace() -> XmbState.Place {
        let defaults = UserDefaults.standard
        let focus = defaults.string(forKey: focusKey).flatMap { try? JSONDecoder().decode([String: Int].self, from: Data($0.utf8)) }
        return XmbState.Place(column: defaults.string(forKey: columnKey), focus: focus ?? [:])
    }

    private func savePlace() {
        let place = state.place
        guard gameColumns == nil, place != saved else { return }
        saved = place
        let defaults = UserDefaults.standard
        defaults.set(place.column, forKey: Self.columnKey)
        if let focus = try? JSONEncoder().encode(place.focus) {
            defaults.set(String(decoding: focus, as: UTF8.self), forKey: Self.focusKey)
        }
    }

    var yes: String { firmware.text(XmbRco.full, "msg_yes", String(localized: "Yes")) }
    var no: String { firmware.text(XmbRco.full, "msg_no", String(localized: "No")) }
    var on: String { firmware.text(XmbRco.full, "msg_on", String(localized: "On")) }
    var off: String { firmware.text(XmbRco.full, "msg_off", String(localized: "Off")) }

    func entry(_ nodeID: String) -> LibraryCache.Entry? {
        library.entries.first { "game." + $0.bootPath == nodeID }
    }

    /// The library grid's launch rules: a locked, unreadable or moved game is explained instead of booted.
    func launch(_ entry: LibraryCache.Entry, changingDisc: Bool = false) {
        switch GameListView.launchBlock(entry) {
        case .locked?:
            // Hidden behind an upright iPad's library, the picker's sheet brings the XMB back; a question would not.
            guard shown else {
                licenceEntry = entry
                presented = .library(.licence)
                return
            }
            // Said first, as the library does, so the Files picker that follows has a reason.
            ask(String(localized: "This game needs a licence") + "\n"
                + String(localized: "Pick the game's .rap licence file. It is matched to this game whatever the file is called."),
                buttons: [String(localized: "Add licence…"), no]) { [weak self] button in
                guard button == 0, let self else { return }
                licenceEntry = entry
                presented = .library(.licence)
            }
            return
        case .explained(let why)?:
            notice(why)
            return
        case nil:
            break
        }
        guard let game = library.game(for: entry) else {
            // The cached list shows before the first scan has made the games bootable; a failed scan is read again.
            if library.phase == .loading { notice(String(localized: "Looking for games…"), error: false) }
            if case .error(let status) = library.phase {
                notice(String(localized: "Could not read the library (status \(status))"))
                Task { await library.refresh() }
            }
            return
        }
        // A package or firmware install holds the core's one queue, and the library is scanned again after it. The
        // updater shares the flag, but its packages have the wait below.
        if installs.busy || (InstallModel.installInFlight && updates.installingSerial == nil) {
            return notice(String(localized: "Another install is running. Wait for it to finish."))
        }
        // The game takes the audio session.
        XmbGameMedia.shared.focus(nil, quiet: true, still: true)
        media.stop()
        updates.holdForLaunch()
        guard updates.installingSerial != nil else {
            playing = PlayRequest(game: game)
            return
        }
        // The core's one queue is busy installing; the game starts once that package is done, unless a sheet is up by then.
        self.changingDisc = changingDisc
        Task {
            let started = await updates.waitForInstall()
            self.changingDisc = false
            if started, presented == nil, picking == nil {
                playing = PlayRequest(game: game)
            } else {
                // A save state picked for this launch must not start the game's next boot unasked.
                XmbToolsStore.shared.pendingState = nil
                // A Change Disc that waited and starts nothing leaves no game up for the XMB to sleep behind.
                gameUp = false
                sheetClosed()
                if shown { media.resume() }
            }
        }
    }

    private static let launchWaitID = "launchWait"

    /// The library's wait card as a question in the dark band, while a launch waits for an update to install.
    func launchWaitChanged(_ name: String?) {
        guard let name else {
            waiting.removeAll { $0.id == Self.launchWaitID }
            guard state.dialog?.id == Self.launchWaitID else { return }
            _ = state.back()
            answer = nil
            if !waiting.isEmpty { show(waiting.removeFirst()) }
            return
        }
        put(Question(id: Self.launchWaitID, message: String(localized: "Finishing the \(name) update…"),
                     buttons: [String(localized: "Don't Start Game")], focus: 0, error: nil) { [weak self] _ in
            self?.updates.cancelWait()
        })
    }

    func installLicence(_ url: URL) async {
        guard let entry = licenceEntry else { return }
        licenceEntry = nil
        let started = url.startAccessingSecurityScopedResource()
        defer { if started { url.stopAccessingSecurityScopedResource() } }
        let status = await Rpcs3Bridge.installLicence(atPath: url.path, forSerial: entry.serial)
        await library.reprobe(serial: entry.serial)
        // An upright iPad's library stands in again once the picker has gone, so it is said where that library shows it.
        if shown {
            notice(GameListView.describe(licence: status), error: status != 0)
        } else {
            ControllerPrompt.shared.ask(GameListView.describe(licence: status), message: "", actions: [.ok])
        }
    }


    /// After a game, what a game can change: installs, save states and screenshots, and the Game column's last-played
    /// lines and order. The library's full scan is left to the sheets that add or remove games.
    func gameClosed() {
        Task {
            await installs.rescanInstalled()
            if isOpen("tools.saves") { await XmbToolsStore.shared.loadStates(settings: settings) }
        }
        XmbPhotos.shared.refresh()
        rebuild()
    }

    /// After a sheet closes, what it may have changed is read again.
    func sheetClosed() {
        Task {
            // The settings sheet's Sounds pane can import or remove the trophy sound, and its Controllers pane remap.
            await XmbMusicColumn.shared.look()
            await XmbControlsStore.shared.model.load()
            await library.refresh()
            await installs.rescanInstalled()
            await settingsModel.load()
            // A game launched from Load / Manage Saves leaves it open, and the game wrote new states meanwhile.
            if isOpen("tools.saves") { await XmbToolsStore.shared.loadStates(settings: settings) }
        }
        XmbPhotos.shared.refresh()
    }
}
