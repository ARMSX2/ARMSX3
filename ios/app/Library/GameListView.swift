// SPDX-License-Identifier: GPL-2.0-only
// The PS3 library grid: shown from the cache at once, refreshed behind it. Reproduces ARMSX2 PR 757's look, no source.

import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct GameListView: View {
    @State private var model: LibraryModel
    @State private var settings = SettingsStore.shared
    @State private var playing: PlayRequest?
    @State private var sheet: LibrarySheet?
    @State private var pickingFolder = false
    @State private var gridWidth: CGFloat = 0
    // iPhone Duo half open like a book: the fold's edges in the grid's space, nil otherwise.
    @State private var gridFold: ClosedRange<CGFloat>?
    @State private var query = ""
    @AppStorage("ARMSX3LibrarySortRecent") private var sortRecent = false
    // The last title touched; the backdrop follows it, else the first title.
    @State private var selected: String?
    @State private var contextEntry: LibraryCache.Entry?
    @State private var contextSelection: GameLibraryControllerMenuAction?
    @State private var lockedEntry: LibraryCache.Entry?
    @State private var licenceEntry: LibraryCache.Entry?
    @State private var licenceNotice: String?
    @State private var coverEntry: LibraryCache.Entry?
    @State private var touchLayoutEntry: LibraryCache.Entry?
    @State private var pickingCoverPhoto = false
    @State private var pickingCoverFile = false
    @State private var coverPhoto: PhotosPickerItem?
    @State private var coverRevision = 0
    @State private var installs: InstallModel
    @State private var updates = UpdateStore.shared
    @Environment(\.menuTabIsActive) private var menuTabIsActive
    private let controllerInput: MenuControllerInputRouter?
    private let onPreviousTab: (@MainActor () -> Bool)?
    private let onNextTab: (@MainActor () -> Bool)?
    private let onBoundary: (@MainActor (MenuControllerCommand) -> Bool)?

    static let controllerScope = "menu.library"

    // Optional rather than a default argument: a default is evaluated outside the main actor.
    @MainActor init(
        model: LibraryModel? = nil,
        controllerInput: MenuControllerInputRouter? = nil,
        onPreviousTab: (@MainActor () -> Bool)? = nil,
        onNextTab: (@MainActor () -> Bool)? = nil,
        onBoundary: (@MainActor (MenuControllerCommand) -> Bool)? = nil
    ) {
        _model = State(initialValue: model ?? LibraryModel())
        _installs = State(initialValue: InstallModel())
        self.controllerInput = controllerInput
        self.onPreviousTab = onPreviousTab
        self.onNextTab = onNextTab
        self.onBoundary = onBoundary
    }

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .alert("This game needs a licence", isPresented: lockedShown, presenting: lockedEntry) { entry in
                    Button("Add licence…") { addLicence(for: entry) }
                    Button("Cancel", role: .cancel) {}
                } message: { _ in
                    Text("Pick the game's .rap licence file. It is matched to this game whatever the file is called.")
                }
                .background { backdrop }
                // The system presents this picker itself: embedded in a sheet, its Open button did nothing for a folder.
                .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
                    if case .success(let url) = result { Task { await model.addFolder(url) } }
                }
                // The stack's own opaque page colour would hide RootView's menu background too.
                .clearNavigationContainerBackground()
                .navigationTitle("Games")
                // The drawer keeps the search field off the app's tab bar.
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search games")
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        if updates.pillText != nil {
                            UpdatesPill(inTabs: menuTabIsActive, openSheet: { sheet = .install })
                        } else if model.phase == .loading && !model.entries.isEmpty {
                            ProgressView()
                        }
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Menu {
                            Picker("Sort", selection: $sortRecent) {
                                Text("Name").tag(false)
                                Text("Recently Played").tag(true)
                            }
                        } label: { Image(systemName: "arrow.up.arrow.down") }
                            .accessibilityLabel("Sort")
                        Button { sheet = .folders } label: { Image(systemName: "folder") }
                            .accessibilityLabel("Game Folders")
                        Button { pickingFolder = true } label: { Image(systemName: "plus") }
                            .accessibilityLabel("Add Game Folder")
                    }
                }
        }
        .preferredColorScheme(.dark)
        .task { await model.start() }
        // The cards live in this view's scope, so the frame's focus request becomes an entry into it.
        .onChange(of: controllerInput?.latestLibraryEntryRequest) { _, request in
            guard let request else { return }
            _ = controllerInput?.requestNavigationSessionEntry(
                preferLast: request.preferLast, matchingScopePrefix: Self.controllerScope)
        }
        .overlay {
            if let entry = contextEntry {
                ControllerGameContextMenu(
                    game: entry,
                    controllerInput: controllerInput,
                    sectionTitle: "",
                    items: Self.contextItems(entry, favourite: model.isFavourite(entry)),
                    selectedAction: contextSelection,
                    isGameRunning: false,
                    onPlayOrStop: { performContext(.playOrStop, on: entry) },
                    onSelect: { performContext($0, on: entry) },
                    onDismiss: closeContext
                )
            } else if let name = updates.waitingLaunch {
                ZStack {
                    Color.black.opacity(0.45).ignoresSafeArea()
                    UpdateWaitCard(name: name, onCancel: cancelLaunchWait).padding(24)
                }
            }
        }
        // The capture's events are the menu's; every other event belongs to the grid's scope.
        .onChange(of: controllerInput?.latestEvent) { _, event in
            guard let event, event.captureOwner == MenuControllerNavigationCaptureOwner.gameLibraryPresentation else { return }
            if let entry = contextEntry {
                handleContextCommand(event.command, on: entry)
            } else if updates.waitingLaunch != nil, event.command == .activate || event.command == .back {
                controllerInput?.playFeedback(event.command == .back ? .back : .activate)
                cancelLaunchWait()
            }
        }
        .alert(licenceNotice ?? "", isPresented: noticeShown) {
            Button("OK", role: .cancel) {}
        }
        .modifier(TouchLayoutChoice(entry: $touchLayoutEntry))
        .libraryMoveAsk(model)
        .photosPicker(isPresented: $pickingCoverPhoto, selection: $coverPhoto, matching: .images)
        .onChange(of: coverPhoto) { _, item in
            guard let item, let entry = coverEntry else { return }
            coverPhoto = nil
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                finishCover(CustomCovers.set(data ?? Data(), serial: entry.serial, fallback: entry.iconPath))
            }
        }
        .fileImporter(isPresented: $pickingCoverFile, allowedContentTypes: [.image]) { result in
            guard case .success(let url) = result, let entry = coverEntry else { return }
            let started = url.startAccessingSecurityScopedResource()
            let data = try? Data(contentsOf: url)
            if started { url.stopAccessingSecurityScopedResource() }
            finishCover(CustomCovers.set(data ?? Data(), serial: entry.serial, fallback: entry.iconPath))
        }
        // A boot that failed never changes the running state, so closing the screen lets the update queue go on.
        .fullScreenCover(item: $playing, onDismiss: { updates.gameStateChanged() }) { request in
            GameScreenView(game: request.game)
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .folders:
                FolderListSheet(model: model)
            case .install:
                InstallView(model: installs, library: model, controllerInput: controllerInput, onInstalled: { await model.refresh() })
                    .onAppear { _ = controllerInput?.requestNavigationSessionEntry(preferLast: false, matchingScopePrefix: "menu.bios.install") }
            case .licence:
                InstallPicker(multiple: false) { urls in
                    sheet = nil
                    guard let url = urls.first, let entry = licenceEntry else { return }
                    licenceEntry = nil
                    Task { await installLicence(url, for: entry) }
                }
            case .gameSettings(let serial, let title):
                GameSettingsSheet(serial: serial, title: title)
            case .patches(let serial, let title):
                PatchesView(serial: serial, title: title)
            case .updateVersions(let serial, let title):
                UpdateVersionSheet(serial: serial, name: title, controllerInput: controllerInput)
            case .saveData(let serial, let title):
                SaveDataView(serial: serial, gameTitle: title)
            case .trophies(let titleDir, let title):
                TrophiesView(titleDir: URL(fileURLWithPath: titleDir, isDirectory: true), gameTitle: title)
            case .uninstall(let serial, let title):
                UninstallSheet(serial: serial, gameTitle: title, onUninstalled: {
                    await installs.refreshAfterContentChange { await model.refresh() }
                })
            }
        }
    }

    @ViewBuilder private var content: some View {
        if case .error(let status) = model.phase {
            errorState(status).inLeftLeafInBookPose()
        } else if model.entries.isEmpty {
            // In book pose these sit on the left leaf, off the fold.
            Group { if model.phase == .ready { emptyState } else { loadingState } }
                .inLeftLeafInBookPose()
        } else {
            grid
        }
    }

    private var focused: LibraryCache.Entry? {
        model.entries.first { $0.bootPath == selected } ?? model.entries.first
    }

    @ViewBuilder private var backdrop: some View {
        if !settings.hasCustomBackground {
            ZStack {
                OverlayFrostBackground()
                if let path = focused?.backdropPath {
                    CoverImageView(path: path, showsFallback: false)
                        .opacity(0.35)
                        .id(path)
                }
            }
            .ignoresSafeArea()
        }
    }

    private var shown: [LibraryCache.Entry] {
        var list = model.entries
        if sortRecent {
            let played = PlayTime.shared
            list.sort { a, b in
                let fa = model.isFavourite(a), fb = model.isFavourite(b)
                if fa != fb { return fa }
                let da = played.lastPlayed(for: a.serial), db = played.lastPlayed(for: b.serial)
                if da != db { return (da ?? .distantPast) > (db ?? .distantPast) }
                return a.title.localizedStandardCompare(b.title) == .orderedAscending
            }
        }
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return list }
        return list.filter { $0.title.localizedCaseInsensitiveContains(needle) || $0.serial.localizedCaseInsensitiveContains(needle) }
    }

    // Never more columns than games, so a short library sits centred instead of hugging the left edge.
    private var gridColumns: [GridItem] {
        let item = GridItem(.flexible(minimum: 150, maximum: 240), spacing: 16)
        // In book pose each leaf gets its own columns and the gutter is as wide as the fold plus a margin, so no card crosses it.
        if let fold = gridFold {
            // A leaf less the outer padding and the gutter's margin (16 each), plus one spacing.
            let perLeaf = max(1, Int((min(fold.lowerBound, gridWidth - fold.upperBound) - 16) / 166))
            var columns = Array(repeating: item, count: perLeaf * 2)
            columns[perLeaf - 1].spacing = fold.upperBound - fold.lowerBound + 32
            return columns
        }
        let fit = max(1, Int((gridWidth - 16) / 166))  // width minus the grid's padding, plus one spacing
        return Array(repeating: item, count: min(fit, max(1, shown.count)))
    }

    /// In book pose the narrower leaf gets this much more room on its outer side, so the grid's middle meets the fold.
    private var foldBalance: (leading: CGFloat, trailing: CGFloat) {
        guard let fold = gridFold else { return (0, 0) }
        let left = fold.lowerBound, right = gridWidth - fold.upperBound
        return (max(0, left - right), max(0, right - left))
    }

    private var grid: some View {
        ScrollView {
            // Only while none is installed: without firmware games would not start, and testers could not tell.
            if installs.version == "" {
                FirmwareStatusCard(version: "", install: { sheet = .install })
                    .padding([.horizontal, .top])
                    .inLeftLeafInBookPose()
            }
            LazyVGrid(columns: gridColumns, spacing: 16) {
                ForEach(shown, id: \.bootPath) { entry in
                    card(entry)
                }
            }
            .padding()
            .padding(.leading, foldBalance.leading)
            .padding(.trailing, foldBalance.trailing)
            if shown.isEmpty { ContentUnavailableView.search(text: query).inLeftLeafInBookPose() }
        }
        .refreshable {
            await model.refresh()
            await installs.refreshVersion()
        }
        // On every return to the tab, so an install from the Install tab clears the banner.
        .onAppear { Task { await installs.refreshVersion() } }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { gridWidth = $0 }
        .onBookFoldChange { gridFold = $0 }
        .controllerAccessibilityNavigation(
            controllerInput: controllerInput,
            scopeKey: Self.controllerScope,
            onContextMenu: { id in
                guard let entry = model.entries.first(where: { Self.targetID($0) == id }) else { return false }
                showContext(entry)
                return true
            },
            onPreviousTab: onPreviousTab,
            onNextTab: onNextTab,
            onBoundary: onBoundary
        )
    }

    private func card(_ entry: LibraryCache.Entry) -> some View {
        Button { open(entry) } label: {
            VStack(alignment: .leading, spacing: 8) {
                // ICON0.PNG is 320 x 176.
                Color.clear
                    .aspectRatio(320.0 / 176.0, contentMode: .fit)
                    .overlay { CoverImageView(path: CustomCovers.path(for: entry.serial, fallback: entry.iconPath)).id(coverRevision) }
                    .overlay(alignment: .bottomLeading) {
                        if let chip = Self.chip(entry) {
                            Text(chip)
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(.black.opacity(0.6), in: Capsule())
                                .padding(6)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                HStack(alignment: .top, spacing: 6) {
                    Text(entry.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(OverlayTheme.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if entry.locked == true {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .accessibilityLabel("Needs a licence")
                    }
                    if model.isFavourite(entry) {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(OverlayTheme.warm)
                            .accessibilityLabel("Favourite")
                    }
                }
                // Always one line, so every card is the same height without reserving an empty second title line.
                Text(PlayTime.format(PlayTime.shared.seconds(for: entry.serial)) ?? String(localized: "Not played yet"))
                    .font(.caption)
                    .foregroundStyle(OverlayTheme.textSecondary)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(OverlayTheme.card.opacity(0.85), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(OverlayTheme.cardTopHighlight, lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(entry.title)
        .controllerAccessibilityActionTarget(
            id: Self.targetID(entry), label: entry.title, focusedNeonCornerRadius: 14
        ) { open(entry) }
        // The long press ends while the finger is down, so the tap on release finds the menu up and does not play.
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in showContext(entry) })
    }

    private var loadingState: some View {
        ProgressView("Reading the library…")
            .foregroundStyle(OverlayTheme.textSecondary)
    }

    private func errorState(_ status: Int) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 36))
                .foregroundStyle(OverlayTheme.warm)
            Text("Could not read the library (status \(status))")
                .font(.headline)
                .foregroundStyle(OverlayTheme.textPrimary)
                .multilineTextAlignment(.center)
            Button("Retry") { Task { await model.refresh() } }
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No games yet", systemImage: "gamecontroller")
        } description: {
            Text("Add a folder that holds your PS3 games. They stay where they are.")
        } actions: {
            Button("Add Game Folder") { pickingFolder = true }
                .buttonStyle(.borderedProminent)
            Button("Install…") { sheet = .install }
        }
    }

    private func open(_ entry: LibraryCache.Entry) {
        guard contextEntry == nil else { return }
        selected = entry.bootPath
        if entry.locked == true {
            lockedEntry = entry
            return
        }
        // A title the core can't read is not booted, and no licence is offered because one would not help.
        if entry.unreadable == true {
            licenceNotice = String(localized: "This game can't be read, and a licence won't help. It may be built for a PS3 running CFW or HEN.")
            return
        }
        // An ISO whose image is no longer there would boot into a core failure, so say so instead.
        if let gone = LibraryRelocation.goneMessage(bootPath: entry.bootPath, isISO: entry.isISO) {
            licenceNotice = gone
            return
        }
        guard let game = model.game(for: entry) else { return }
        updates.holdForLaunch()
        guard updates.installingSerial != nil else {
            playing = PlayRequest(game: game)
            return
        }
        // The core's one queue is busy installing; the game starts once that package is done.
        controllerInput?.setNavigationCaptured(
            true, owner: MenuControllerNavigationCaptureOwner.gameLibraryPresentation, priority: 200)
        Task {
            let started = await updates.waitForInstall()
            controllerInput?.setNavigationCaptured(
                false, owner: MenuControllerNavigationCaptureOwner.gameLibraryPresentation, priority: 200)
            if started { playing = PlayRequest(game: game) }
        }
    }

    private func cancelLaunchWait() {
        updates.cancelWait()
    }

    static func targetID(_ entry: LibraryCache.Entry) -> String { "library.card.\(entry.bootPath)" }

    static func contextItems(_ entry: LibraryCache.Entry, favourite: Bool) -> [GameLibraryControllerMenuItem] {
        var items = [GameLibraryControllerMenuItem(
            action: .favourite,
            title: favourite ? String(localized: "Remove from Favourites") : String(localized: "Add to Favourites"),
            systemImage: favourite ? "star.slash" : "star")]
        items.append(GameLibraryControllerMenuItem(action: .perGameSettings, title: "Game Settings", systemImage: "slider.horizontal.3"))
        // A layout is kept by serial, so a game without one has nothing to choose.
        if PadLayoutLibrary.key(entry.serial) != nil {
            items.append(GameLibraryControllerMenuItem(action: .touchLayout, title: "Touch Layout", systemImage: "hand.tap"))
        }
        items.append(GameLibraryControllerMenuItem(action: .patches, title: "Patches", systemImage: "bandage"))
        if GameUpdates.titleID(entry.serial) != nil {
            items.append(GameLibraryControllerMenuItem(action: .checkForUpdate, title: "Check for Update", systemImage: "arrow.down.app"))
        }
        items.append(GameLibraryControllerMenuItem(action: .gameData, title: "Save Data", systemImage: "externaldrive"))
        // An ISO's TROPDIR is inside the image, so an ISO has no Trophies row.
        if !entry.isISO {
            items.append(GameLibraryControllerMenuItem(action: .trophies, title: "Trophies", systemImage: "trophy"))
        }
        items.append(GameLibraryControllerMenuItem(action: .chooseCoverPhoto, title: "Cover from Photos", systemImage: "photo"))
        items.append(GameLibraryControllerMenuItem(action: .chooseCoverFile, title: "Cover from Files", systemImage: "folder"))
        items.append(GameLibraryControllerMenuItem(action: .downloadCover, title: "Download Cover", systemImage: "arrow.down.circle"))
        if CustomCovers.customPath(for: entry.serial) != nil {
            items.append(GameLibraryControllerMenuItem(action: .removeCover, title: "Remove Custom Cover", systemImage: "xmark.circle"))
        }
        items.append(GameLibraryControllerMenuItem(action: .copyLaunchLink, title: "Copy Launch Link", systemImage: "link"))
        if entry.locked == true {
            items.append(GameLibraryControllerMenuItem(action: .addLicence, title: "Add licence…", systemImage: "key"))
        }
        // Only a title installed into dev_hdd0 can be uninstalled; a disc folder or an ISO is the player's.
        if !entry.fromGamesYml && entry.category == "HG" {
            items.append(GameLibraryControllerMenuItem(action: .deleteGame, title: "Uninstall", systemImage: "trash"))
        }
        return items
    }

    private var lockedShown: Binding<Bool> {
        Binding(get: { lockedEntry != nil }, set: { if !$0 { lockedEntry = nil } })
    }

    private func finishCover(_ kept: Bool) {
        licenceNotice = kept ? String(localized: "Cover updated.") : String(localized: "That file isn't an image.")
        coverRevision += 1
    }

    private var noticeShown: Binding<Bool> {
        Binding(get: { licenceNotice != nil }, set: { if !$0 { licenceNotice = nil } })
    }

    private func addLicence(for entry: LibraryCache.Entry) {
        licenceEntry = entry
        sheet = .licence
    }

    // The serial routes the RAP by the game's own content id, whatever the file is called.
    private func installLicence(_ url: URL, for entry: LibraryCache.Entry) async {
        let started = url.startAccessingSecurityScopedResource()
        defer { if started { url.stopAccessingSecurityScopedResource() } }
        let status = await Rpcs3Bridge.installLicence(atPath: url.path, forSerial: entry.serial)
        await model.reprobe(serial: entry.serial)
        licenceNotice = Self.describe(licence: status)
        if status == 0 { await installs.refreshAfterContentChange { await model.refresh() } }
    }

    static func describe(licence status: Int) -> String {
        switch status {
        case 0: return String(localized: "Licence added")
        case -17: return String(localized: "That licence doesn't unlock this game")
        case -3: return String(localized: "Couldn't read that file, or the game is no longer installed")
        case -16: return String(localized: "Couldn't write the licence; check free space")
        case -9: return String(localized: "Stop the game first")
        default: return String(localized: "The licence wasn't added (status \(status))")
        }
    }

    private func showContext(_ entry: LibraryCache.Entry) {
        selected = entry.bootPath
        contextEntry = entry
        contextSelection = .playOrStop
        controllerInput?.setNavigationCaptured(
            true, owner: MenuControllerNavigationCaptureOwner.gameLibraryPresentation, priority: 200)
    }

    private func closeContext() {
        contextEntry = nil
        contextSelection = nil
        controllerInput?.setNavigationCaptured(
            false, owner: MenuControllerNavigationCaptureOwner.gameLibraryPresentation, priority: 200)
    }

    private func performContext(_ action: GameLibraryControllerMenuAction, on entry: LibraryCache.Entry) {
        closeContext()
        switch action {
        case .playOrStop:
            open(entry)
        case .favourite:
            model.toggleFavourite(entry)
            controllerInput?.playFeedback(.favorite(isFavorite: model.isFavourite(entry)))
        case .addLicence:
            addLicence(for: entry)
        case .perGameSettings:
            sheet = .gameSettings(serial: entry.serial, title: entry.title)
        case .touchLayout:
            touchLayoutEntry = entry
        case .patches:
            sheet = .patches(serial: entry.serial, title: entry.title)
        case .checkForUpdate:
            guard let id = GameUpdates.titleID(entry.serial) else { break }
            // The sheet asks Sony afresh for this game when it opens.
            sheet = .updateVersions(serial: id, title: entry.title)
        case .gameData:
            sheet = .saveData(serial: entry.serial, title: entry.title)
        case .trophies:
            sheet = .trophies(titleDir: entry.bootPath, title: entry.title)
        case .deleteGame:
            sheet = .uninstall(serial: entry.serial, title: entry.title)
        case .chooseCoverPhoto:
            coverEntry = entry
            pickingCoverPhoto = true
        case .chooseCoverFile:
            coverEntry = entry
            pickingCoverFile = true
        case .downloadCover:
            Task {
                licenceNotice = await CustomCovers.download(serial: entry.serial, fallback: entry.iconPath)
                coverRevision += 1
            }
        case .removeCover:
            CustomCovers.remove(serial: entry.serial, fallback: entry.iconPath)
            coverRevision += 1
        case .copyLaunchLink:
            UIPasteboard.general.string = DeepLink.copyLink(serial: entry.serial)
            licenceNotice = String(localized: "Launch link copied.")
        default:
            break
        }
    }

    private func handleContextCommand(_ command: MenuControllerCommand, on entry: LibraryCache.Entry) {
        let rows = [GameLibraryControllerMenuAction.playOrStop] + Self.contextItems(entry, favourite: model.isFavourite(entry)).map(\.action)
        let index = contextSelection.flatMap { rows.firstIndex(of: $0) } ?? 0
        switch command {
        case .up, .upLeft, .upRight, .left, .down, .downLeft, .downRight, .right:
            let step = [.up, .upLeft, .upRight, .left].contains(command) ? -1 : 1
            let next = index + step
            guard rows.indices.contains(next) else {
                controllerInput?.playFeedback(.boundary)
                return
            }
            contextSelection = rows[next]
            controllerInput?.playFeedback(.move(command))
        case .activate:
            controllerInput?.playFeedback(.activate)
            performContext(rows[index], on: entry)
        case .back:
            controllerInput?.playFeedback(.back)
            closeContext()
        default:
            controllerInput?.playFeedback(.boundary)
        }
    }

    static func chip(_ entry: LibraryCache.Entry) -> String? {
        if entry.isISO { return "ISO" }
        switch entry.category {
        case "DG": return String(localized: "Disc")
        case "HG": return "HDD"
        case "": return nil
        default: return entry.category
        }
    }
}

enum LibrarySheet: Identifiable {
    case folders, install, licence
    case gameSettings(serial: String, title: String)
    case patches(serial: String, title: String)
    case updateVersions(serial: String, title: String)
    case saveData(serial: String, title: String)
    case trophies(titleDir: String, title: String)
    case uninstall(serial: String, title: String)

    var id: String {
        switch self {
        case .folders: return "folders"
        case .install: return "install"
        case .licence: return "licence"
        case .gameSettings(let serial, _): return "gameSettings." + serial
        case .patches(let serial, _): return "patches." + serial
        case .updateVersions(let serial, _): return "updateVersions." + serial
        case .saveData(let serial, _): return "saveData." + serial
        case .trophies(let titleDir, _): return "trophies." + titleDir
        case .uninstall(let serial, _): return "uninstall." + serial
        }
    }
}

struct FolderListSheet: View {
    let model: LibraryModel
    @State private var picking = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.folders.isEmpty {
                        Text("No game folders yet.").foregroundStyle(.secondary)
                    }
                    ForEach(model.folders) { row in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.folder.displayName)
                                if !row.available {
                                    Text("unavailable").font(.caption).foregroundStyle(.orange)
                                }
                            }
                            Spacer()
                            Button("Remove", role: .destructive) { Task { await model.removeFolder(row.id) } }
                                .buttonStyle(.borderless)
                        }
                    }
                } footer: {
                    Text("The folder stays where it is and is not copied. Folders in iCloud Drive may need downloading first.")
                }
            }
            .navigationTitle("Game Folders")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) { Button("Add", systemImage: "plus") { picking = true } }
            }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
                if case .success(let url) = result { Task { await model.addFolder(url) } }
            }
        }
        .libraryMoveAsk(model)
        .presentationDetents([.medium, .large])
    }
}

// The alert reads the flag and never writes it, so a system dismissal runs the cancel button, which keeps.
extension View {
    func libraryMoveAsk(_ model: LibraryModel) -> some View {
        alert("This folder is in a temporary place", isPresented: .constant(model.askingMove)) {
            Button("Move to ARMSX3") { model.answerMove(true) }
            Button("Keep Where It Is", role: .cancel) { model.answerMove(false) }
        } message: {
            Text("iOS or LiveContainer may clear it at any time, even during a game. Moving it puts it in the ARMSX3 folder in Files, and your answer is used for later folders too.")
        }
    }
}

// fullScreenCover(item:) needs a stable identity per presentation.
struct PlayRequest: Identifiable {
    let id = UUID()
    let game: Rpcs3GameEntry
}

// Which touch layout a game uses, chosen before it starts.
private struct TouchLayoutChoice: ViewModifier {
    @Binding var entry: LibraryCache.Entry?

    func body(content: Content) -> some View {
        let library = PadLayoutStore.shared.library
        let serial = entry?.serial
        content.controllerPrompt(String(localized: "Touch Layout"),
                                 isPresented: Binding(get: { entry != nil }, set: { if !$0 { entry = nil } }),
                                 message: String(localized: "“\(entry?.title ?? "")” uses \(library.name(of: library.choice(for: serial)))."),
                                 actions: actions(library, serial: serial))
    }

    private func actions(_ library: PadLayoutLibrary, serial: String?) -> [ControllerPrompt.Action] {
        let choices = library.choices + (library.exists(.game, serial: serial) ? [.game] : [])
        let pin = { (choice: PadLayoutChoice?) in
            PadLayoutStore.shared.update { $0.pin(serial, to: choice) }
        }
        return [.init(title: String(localized: "Same as All Games"), run: { pin(nil) })]
            + choices.map { choice in .init(title: library.name(of: choice), run: { pin(choice) }) }
            + [.init(title: String(localized: "Cancel"), isCancel: true)]
    }
}
