// SPDX-License-Identifier: GPL-2.0-only
// XMB mode's home: the XMB over its background, with the app's own screens presented from it, after XmbHome.kt.

import SwiftUI

struct XmbHomeView: View {
    @State private var host: XmbHost
    @State private var boot = XmbBootClock.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var setupAfterSheet = false
    /// False while the library's grid or a game the XMB did not open stands in its place: nothing draws and the pad is let go.
    private let visible: Bool

    init(host: XmbHost, visible: Bool = true) {
        _host = State(initialValue: host)
        self.visible = visible
    }

    var body: some View {
        ZStack {
            XmbBackground(host: host, boot: boot, paused: !visible)
            XmbPicker(host: host)
            ZStack {
                XmbView(state: host.state, firmware: host.firmware, avatar: host.avatar, act: host.perform)
                    .accessibilityHidden(host.columnsHidden)
                XmbOverlays(host: host)
            }
                .opacity(boot.reveal)
                .offset(y: (1 - boot.reveal) * XmbBootTimeline.revealRise)
                .allowsHitTesting(!boot.isRunning && !boot.isShuttingDown)
            XmbBootView(clock: boot)
        }
            .statusBarHidden()
            .persistentSystemOverlays(.hidden)
            .preferredColorScheme(.dark)
            .onChange(of: visible, initial: true) { _, shown in
                host.shown = shown
                host.capture()
                // While the library stands in for the XMB, its music and a video it was playing wait where they were.
                if !shown {
                    XmbVideoPlayback.shared.appLeft()
                    host.media.stop()
                    // Its visualizer would keep drawing unseen.
                    host.media.nowPlaying = nil
                    XmbAvatarPictures.shared.stop(host)
                    // A boot link can hide the XMB during its startup sound.
                    if boot.isRunning { boot.skip() }
                    return
                }
                XmbAvatarPictures.shared.resume(host)
                // A game the XMB did not open may have played, saved and taken screenshots, as after its own games.
                if host.booted { host.gameClosed() } else { XmbPhotos.shared.refresh() }
                if !host.booted {
                    // The music and the bring-up start once, the first time the XMB is on screen.
                    host.booted = true
                    host.media.resume()
                    boot.start()
                } else if host.playing == nil, !XmbVideoPlayback.shared.isOpen {
                    host.media.resume()
                }
            }
            // Leaving XMB mode takes this view away: the music stops with it, and coming back replays the bring-up.
            .onDisappear {
                // Its sheet goes with it, and must not reopen by itself when XMB mode comes back.
                host.presented = nil
                // A sheet torn down with its owner may never see onDismiss.
                XmbHost.sheetUp = false
                host.releaseCapture()
                host.booted = false
                host.media.stop()
                host.leave()
            }
            // One sound at a time: the music waits while a video plays.
            .onChange(of: XmbVideoPlayback.shared.isOpen) { _, open in
                if open { host.media.stop() } else if host.shown { host.media.resume() }
            }
            .onChange(of: host.shown && host.state.current?.id == "video") { _, on in
                if on { XmbVideos.shared.refreshAll() }
            }
            .onChange(of: host.updates.waitingLaunch) { _, name in host.launchWaitChanged(name) }
            // The clock's picture follows the avatar wherever it was changed.
            .onChange(of: host.rpcn.currentAvatar) { _, _ in Task { await host.readAvatar() } }
            .onChange(of: host.controllerInput?.isMenuActive) { _, _ in host.capture() }
            .onChange(of: host.covered || XmbLibraryCover.shared.rootModal) { _, _ in host.capture() }
            .onChange(of: host.controllerInput?.latestEvent) { _, event in
                guard let event, event.captureOwner == XmbHost.captureOwner, !host.covered, !boot.isShuttingDown else { return }
                // During the boot a press only skips it.
                if boot.isRunning {
                    if event.command == .activate || event.command == .back { boot.skip() }
                    return
                }
                host.press(event.command)
            }
            .onChange(of: host.gameFocus(booting: boot.isRunning || boot.isShuttingDown), initial: true) { _, focus in
                XmbGameMedia.shared.focus(focus.node.flatMap(host.entry), quiet: focus.quiet, still: focus.still)
            }
            // Friends are read while their column is in view. A sheet over it does not stop that.
            .onChange(of: host.shown && host.state.current?.id == "rpcn" && !boot.isRunning, initial: true) { _, on in
                XmbFriendsPolling.shared.setShown(on)
            }
            // Leaving the app ends the boot, so its sound does not play on over another app.
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { boot.skip() }
            }
            .task { await host.start() }
            // Games copied into a game folder while the app was away show on return, as the library's pull to refresh does.
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                guard visible, host.playing == nil, !host.installs.busy, !InstallModel.installInFlight else { return }
                // Titles already probed keep their answers, so a return costs a scan, not a decrypt of every game.
                Task { await host.library.refresh(probeKnown: false) }
            }
            // An update that fails while the player is elsewhere is said, as the library's pill turns warm.
            .onChange(of: host.updates.pillFailed) { _, failed in
                if failed, let text = host.updates.pillText { XmbNotices.shared.post(text) }
            }
            // The launch's notices wait until the XMB is seen and its boot is over, so they are neither hidden nor talked over.
            .onChange(of: visible && host.booted && !boot.isRunning && host.launchNoticesDue, initial: true) { _, due in
                guard due else { return }
                host.launchNoticesDue = false
                Task { await host.sayLaunchNotices() }
            }
            .sheet(item: $host.presented, onDismiss: {
                XmbHost.sheetUp = false
                host.sheetClosed()
                if setupAfterSheet {
                    setupAfterSheet = false
                    NotificationCenter.default.post(name: SetupFlow.runAgainNotification, object: nil)
                }
            }) { presented in
                Group {
                    switch presented {
                    case .library(let which): librarySheet(which)
                    case .pane(let path): XmbSettingsSheet(model: host.settingsModel, path: path, controllerInput: host.controllerInput)
                    }
                }
                    .onAppear { XmbHost.sheetUp = true }
            }
            // Setup covers the whole app, and cannot open while a sheet is up, so Run Setup Again closes the sheet first.
            .onReceive(NotificationCenter.default.publisher(for: SetupFlow.runAgainNotification)) { _ in
                guard XmbHost.sheetUp else { return }
                setupAfterSheet = true
                host.presented = nil
            }
            // Unlocking the phone is the console waking: its greeting, with the player's picture.
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                if visible { XmbNotices.shared.welcomeBack(avatar: host.avatar) }
            }
            .fullScreenCover(item: $host.playing, onDismiss: {
                host.gameUp = false
                host.updates.gameStateChanged()
                // Read again only when no disc follows, so nothing reaches the core as the next disc boots.
                guard let disc = XmbInGame.shared.nextDisc else {
                    host.gameClosed()
                    if host.shown { host.media.resume() }
                    return
                }
                Task {
                    let entry = await host.library.entry(bootPath: disc)
                    XmbInGame.shared.nextDisc = nil
                    if let entry { host.launch(entry, changingDisc: true) }
                    // A disc that needs a licence or is explained away starts nothing, so the XMB comes back as after a game.
                    if host.playing == nil, !host.changingDisc {
                        // The screen's onDisappear kept it up for the disc that is not coming.
                        host.gameUp = false
                        host.gameClosed()
                        if host.shown { host.media.resume() }
                    }
                }
            }) { request in
                GameScreenView(game: request.game)
                    .onAppear { host.gameUp = true }
                    // Through Change Disc it counts as up until the cover has gone and the next disc is asked for.
                    .onDisappear { host.gameUp = XmbInGame.shared.nextDisc != nil }
            }
    }

    @ViewBuilder private func librarySheet(_ which: LibrarySheet) -> some View {
        switch which {
        case .folders:
            FolderListSheet(model: host.library)
        case .install:
            InstallView(model: host.installs, library: host.library, controllerInput: host.controllerInput,
                        onInstalled: { await host.library.refresh() })
        case .licence:
            InstallPicker(multiple: false) { urls in
                host.presented = nil
                guard let url = urls.first else { return }
                Task { await host.installLicence(url) }
            }
        case .gameSettings(let serial, let title):
            GameSettingsSheet(serial: serial, title: title)
        case .patches(let serial, let title):
            PatchesView(serial: serial, title: title)
        case .updateVersions(let serial, let title):
            UpdateVersionSheet(serial: serial, name: title, controllerInput: host.controllerInput)
        case .saveData(let serial, let title):
            SaveDataView(serial: serial, gameTitle: title)
        case .trophies(let titleDir, let title):
            TrophiesView(titleDir: URL(fileURLWithPath: titleDir, isDirectory: true), gameTitle: title)
        case .uninstall, .deleteGame:
            EmptyView()
        }
    }
}

/// A settings pane as a sheet over the XMB, with the Settings list under it and Done to close.
struct XmbSettingsSheet: View {
    let model: SettingsScreenModel
    @State var path: [SettingsPane]
    var controllerInput: MenuControllerInputRouter?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack(path: $path) {
            SettingsScreen(model: model, path: $path,
                           controller: SettingsControllerContext(input: controllerInput, onCloseHost: { dismiss(); return true }))
                .navigationTitle("Settings")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } } }
        }
        .settingsFailureAlert(model)
        .preferredColorScheme(.dark)
    }
}
