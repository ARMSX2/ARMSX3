// SPDX-License-Identifier: GPL-2.0-only
// The game screen: render view, touch pad, quick menu, saves, recording and RPCN banners. Reproduces ARMSX2 PR 757's look.

import AVFoundation
import Combine
import GameController
import SwiftUI

struct GameScreenView: View {
    let game: Rpcs3GameEntry

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.menuControllerInputRouter) private var controllerInput
    @State private var bootRequested = false
    @State private var bootStatus: Int?
    @State private var aiming = false
    // A finger is down on the aim area; it resets when the drag ends or is cancelled.
    @GestureState private var aimHeld = false
    @State private var menuOpen = false
    // The touch layout editor, opened from the menu; the menu stays open beneath it, so the game stays paused.
    @State private var layoutEditor: PadEditor?
    @State private var stopping = false
    @State private var stopStatus: Int?
    // The pool (2 or 3) whose second StikDebug pass the alert offers; nil when none is up.
    @State private var jitOffer: Int?
    @State private var saveFailure: Int?
    // A load never discards progress unasked.
    @State private var loadFailure: Int?
    @State private var stateTooOld = false
    @State private var stateDirectory: URL?
    @State private var hasStates = false
    @State private var slotPanel: SaveStatesPanel.Mode?
    @State private var savingPanel: SaveStatesPanel?
    @State private var settingsOpen = false
    @State private var report: ReportRequest?
    @State private var controllersSheet: ControllersSheetRequest?
    @State private var jitDeviceSheet: JitDeviceSheetRequest?
    @State private var shotFile: URL?
    @State private var shotFailed = false
    @StateObject private var shotBanner = TransientBannerController<String>(defaultDisplayDuration: 2.5)
    @State private var recordingFile: URL?
    @State private var recordingFailed = false
    // Files picks keep their access until the game stops, because the game keeps reading the disc.
    @State private var discSwap = 0
    @State private var discPicker = false
    // True while the picker is for Change Disc, which starts the picked disc in place of this game.
    @State private var discBoots = false
    @State private var discChanging = false
    @State private var discAccess: [URL] = []
    @State private var portalSheet = false
    @State private var restartLabel = String(localized: "Saving…")
    @State private var exitSaving = false
    @State private var exitFailed = false
    @State private var exitFailureStatus = 0
    @State private var exitStopped = false
    @State private var stoppedTitle = ""
    @State private var stoppedMessage = ""
    @State private var resumePrompt = false
    @State private var resumeChoice: Bool?
    @State private var confirmQuickLoad = false
    @State private var showShortcutHelp = false
    // Session-only: every screen starts at the quick slot.
    @State private var activeFileSlot = EmulatorSession.quickFileSlot
    @State private var slotNotice: String?
    // Neither discards progress unasked.
    @State private var confirmClose = false
    @State private var confirmReset = false
    // The touch pad is player 1 only, so it stays up until a controller claims player 1 with its first press.
    @State private var controllerConnected = GamepadPoller.playerOneHasController
    // nil until the first read after the boot.
    @State private var rpcnStatus: RpcnState.Status?
    @State private var rpcnInvites: [RpcnSession.Invite] = []
    @State private var rpcnSeenInvites: Set<UInt64> = []
    @State private var friendsOpen = false
    @State private var rpcnBootShown = false
    @State private var rpcnReloginsAtBackground: Int?
    @State private var rpcnReturn: RpcnSession.ReturnWatch?
    @State private var rpcnReturnSince = Date()
    @State private var rpcnDrop = RpcnSession.DropWatch()

    private var session: EmulatorSession { EmulatorSession.shared }

    // The savestate-epoch smoke scene reads this.
    static var oldSessionNotes = 0
    // One game screen at a time, so the expiration handler captures nothing.
    static var rpcnBackgroundTask = UIBackgroundTaskIdentifier.invalid
    static let shortcutHelpSeconds: UInt64 = 8

    private var showPad: Bool { bootStatus == 0 && !menuOpen && !controllerConnected }
    private var showMoveTouch: Bool { bootStatus == 0 && !menuOpen && SettingsStore.shared.movePointer[game.serial] == 1 }
    private var showAimTouch: Bool { bootStatus == 0 && !menuOpen && aiming }

    // In tabletop the picture keeps to the top leaf, above the fold, and the pad below it.
    private var renderArea: some View {
        GeometryReader { screen in
            GameRenderView()
                .frame(height: screen.tabletopFold()?.minY ?? screen.size.height)
                .frame(maxHeight: .infinity, alignment: .top)
        }
        .ignoresSafeArea()
        .background { DuoHingeReader() }
    }

    var body: some View {
        renderArea
            .statusBarHidden()
            .persistentSystemOverlays(.hidden)
            .background { GamePointerLockAnchor() }
            // First, so the touch pad and the menu button above it keep their touches.
            .overlay {
                if showMoveTouch { moveTouchArea }
            }
            // Below the touch pad, which keeps its own fingers.
            .overlay {
                if showAimTouch { aimTouchArea }
            }
            .overlay {
                // The pad spans the screen less the insets on both sides, so a one-sided status column cannot squeeze it.
                GeometryReader { area in
                    let inset = area.safeAreaInsets, even = min(inset.leading, inset.trailing)
                    if showPad {
                        VirtualControllerView(serial: game.serial)
                            .padding(.leading, even - inset.leading)  // ≤ 0: reaches into the wider side's inset
                            .padding(.trailing, even - inset.trailing)
                            // Tabletop: the pad keeps to the bottom leaf; its stack already sits at the bottom.
                            .padding(.top, area.tabletopFold()?.maxY ?? 0)
                            // GeometryReader pins its child top-left; this keeps a pad wider than a small phone centred.
                            .frame(width: area.size.width, height: area.size.height)
                    }
                }
            }
            .overlay {
                if showShortcutHelp && controllerConnected && !menuOpen {
                    GameplayControllerShortcutHelpOverlay(settings: SettingsStore.shared, controllerInput: controllerInput)
                }
            }
            // In landscape the menu button sits top centre, between the shoulder buttons, and the banner moves below it.
            .overlay(alignment: .top) { banner.padding(.top, verticalSizeClass == .compact ? 48 : 0) }
            .overlay(alignment: verticalSizeClass == .compact ? .top : .topTrailing) {
                // Hidden while the menu is open: the card's glass smeared the button through. The scrim takes every tap anyway.
                if menuOpen {
                    EmptyView()
                } else if verticalSizeClass == .compact {
                    // Centred on the whole screen, as the pad is, not between unequal safe-area insets.
                    menuButton.frame(maxWidth: .infinity).ignoresSafeArea(.container, edges: .horizontal)
                } else {
                    menuButton
                }
            }
            // Over the game only, while a recording runs. The open menu covers it.
            .overlay(alignment: .topLeading) {
                if RecordingStore.shared.active {
                    Circle()
                        .fill(.red)
                        .frame(width: 10, height: 10)
                        .padding(16)
                        .accessibilityLabel("Recording")
                        .onAppear { RecordingStore.shared.dotDrawn = true }
                }
            }
            .overlay { menuLayer }
            .overlay {
                if SettingsStore.shared.xmbMode { XmbGameNotices() }
            }
            .overlay {
                if session.restartPending || exitSaving { restartOverlay }
            }
            .overlay {
                if resumePrompt { resumePanel }
            }
            .alert("Couldn't save. Stop anyway?", isPresented: $exitFailed) {
                Button("Stop anyway", role: .destructive) { stop() }
                Button("Keep playing", role: .cancel) {
                    // The disc Change Disc picked is not started after all, and the app stays on.
                    XmbInGame.shared.nextDisc = nil
                    XmbInGame.shared.turnOffAfterExit = false
                }
            } message: {
                exitFailureStatus == EmulatorSession.savestateSpuBusy
                    ? spuBusyText : Text("The game was not saved (status \(exitFailureStatus)).")
            }
            // Nothing here can run the game again; the one button closes the screen as a finished stop does.
            .alert(stoppedTitle, isPresented: $exitStopped) {
                Button("OK") { Task { await closeAfterStop() } }
            } message: {
                Text(stoppedMessage)
            }
            .alert(quickLoadQuestion, isPresented: $confirmQuickLoad) {
                Button("Load", role: .destructive) { loadSlot(activeFileSlot) }
                Button("Cancel", role: .cancel) {}
            }
            // An older-epoch state never reached the core, and the game runs on.
            .alert("This state was saved by an older version of ARMSX3 and can't be loaded.", isPresented: $stateTooOld) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Your in-game saves are not affected.")
            }
            .alert("Close the game? Unsaved progress is lost.", isPresented: $confirmClose) {
                Button("Close", role: .destructive) { stop() }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Reset the game? Unsaved progress is lost.", isPresented: $confirmReset) {
                Button("Reset", role: .destructive) { resetGame() }
                Button("Cancel", role: .cancel) {}
            }
            // The menu stays open under the sheet, so the game stays paused.
            .sheet(item: $report) { ProblemReportView(session: $0.session, serial: $0.serial) }
            // Back at the pane's root and Done close the sheet; the menu stays open beneath.
            .sheet(item: $controllersSheet) { request in
                NavigationStack {
                    ControllersPaneView(path: .constant([]),
                                        controller: SettingsControllerContext(input: controllerInput,
                                                                              onCloseHost: { controllersSheet = nil; return true }),
                                        model: ControllersModel(serial: request.serial, scope: .game))
                        .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Done") { controllersSheet = nil } } }
                }
                .background(OverlayFrostBackground().ignoresSafeArea())
                .presentationDetents([.large])
                .preferredColorScheme(.dark)
            }
            // Photos is asked only from this button, for add-only access.
            .alert("Screenshot saved to Files › ARMSX3 › Screenshots.",
                   isPresented: Binding(get: { shotFile != nil }, set: { if !$0 { shotFile = nil } }), presenting: shotFile) { file in
                Button("Save to Photos") { Task { _ = await ScreenshotStore.saveToPhotos(file) } }
                Button("OK", role: .cancel) {}
            }
            .alert("Couldn't capture a frame. Try again while the game is running.", isPresented: $shotFailed) {
                Button("OK", role: .cancel) {}
            }
            // Photos is asked only from this button, for add-only access.
            .alert("Recording saved to Files › ARMSX3 › Recordings.",
                   isPresented: Binding(get: { recordingFile != nil }, set: { if !$0 { recordingFile = nil } }), presenting: recordingFile) { file in
                Button("Save to Photos") { Task { _ = await RecordingStore.saveToPhotos(file) } }
                Button("OK", role: .cancel) {}
            }
            .alert("Couldn't save the recording.", isPresented: $recordingFailed) {
                Button("OK", role: .cancel) {}
            }
            // Not Now ends it for this session: the dual map offers once per game session. Set Up Built-in JIT keeps the game paused under the sheet.
            .alert("More JIT memory needed",
                   isPresented: Binding(get: { jitOffer != nil }, set: { if !$0 { jitOffer = nil } }), presenting: jitOffer) { trigger in
                Button("Open StikDebug") { openStikDebugForMoreJit(trigger) }
                Button("Set Up Built-in JIT") {
                    setMenu(open: true)
                    jitDeviceSheet = JitDeviceSheetRequest()
                }
                Button("Not Now", role: .cancel) {}
            } message: { _ in
                Text("Open StikDebug to prepare more. The game pauses while it works. Built-in JIT prepares enough from the start, so setting it up avoids this.")
            }
            // The menu stays open beneath, so the game stays paused.
            .sheet(item: $jitDeviceSheet) { _ in
                NavigationStack {
                    JitDevicePane(host: .inGame,
                                  controller: SettingsControllerContext(input: controllerInput),
                                  onBack: { jitDeviceSheet = nil; return true })
                        .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Done") { jitDeviceSheet = nil } } }
                }
                .background(OverlayFrostBackground().ignoresSafeArea())
                .presentationDetents([.large])
                .preferredColorScheme(.dark)
            }
            .sheet(isPresented: $discPicker) {
                DiscPicker(onPick: { path, url in
                    discPicker = false
                    Task { if discBoots { await bootInstead(path: path) } else { await swapDisc(path: path, filesURL: url) } }
                }, onCancel: { discPicker = false })
            }
            // The menu stays open beneath, so the game stays paused.
            .sheet(isPresented: $portalSheet) {
                PortalSheet(onDone: { portalSheet = false })
            }
            .onChange(of: controllerInput?.latestQuickPauseRequest) { _, request in
                guard request != nil, chordsReady, !menuOpen else { return }
                setMenu(open: true)
            }
            // Every arm prints the action's raw name, a word and never a value.
            .onChange(of: controllerInput?.latestEmulationShortcutRequest) { _, request in
                guard let request, chordsReady, !menuOpen else { return }
                switch request.shortcut {
                case .saveLastState:
                    saveSlot(activeFileSlot)
                case .loadLastState:
                    confirmQuickLoad = true
                case .cycleSaveSlot:
                    activeFileSlot = HotkeyTable.nextSaveSlot(after: activeFileSlot)
                    showSlotNotice()
                case .saveStateAndExit:
                    exitWithAutosave()
                case .togglePerformanceOverlay:
                    Task { let on = await session.liveValue(.perfOverlay) == 1; await session.setLive(.perfOverlay, on ? 0 : 1) }
                case .resolutionUp:
                    Task { if let scale = await session.liveValue(.resolutionScale) { await session.setLive(.resolutionScale, HotkeyTable.steppedScale(scale, by: 25)) } }
                case .resolutionDown:
                    Task { if let scale = await session.liveValue(.resolutionScale) { await session.setLive(.resolutionScale, HotkeyTable.steppedScale(scale, by: -25)) } }
                case .closeGame:
                    confirmClose = true
                case .resetGame:
                    confirmReset = true
                case .screenshot:
                    Task { await captureScreenshot(fromMenu: false) }
                case .toggleRecording:
                    Task { await toggleRecording(fromMenu: false) }
                default:
                    break
                }
            }
            .animation(.easeOut(duration: 0.2), value: menuOpen)
            // .task fires after makeUIView attached the view, so the first swapchain never sees a zero extent (present_gate.h).
            .task { await run() }
            // Exported photos go to Photos every 2 s while this screen is up; .task ends the loop when it goes away.
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    if Task.isCancelled { break }
                    await PhotoExportDrain.shared.drainOnce()
                }
            }
            // The RPCN status every 2 s while the game runs. .task ends it when the screen goes.
            .task { await pollRpcn() }
            // A full phone ends the game without a word, so the banner comes first and repeats while space stays low.
            .task { await watchStorage() }
            // A controller does not reset the idle timer, so the phone would lock mid-game.
            .onChange(of: bootStatus == 0 && !stopping && !menuOpen && scenePhase == .active, initial: true) { _, awake in
                UIApplication.shared.isIdleTimerDisabled = awake
            }
            .onAppear { XmbInGame.shared.screenUp = true }
            .onDisappear {
                XmbInGame.shared.screenUp = false
                UIApplication.shared.isIdleTimerDisabled = false
                // The screen closing finishes a running recording's file.
                stopRecordingAutomatically()
                GamepadPoller.shared.stop()
                KeyboardMouseFeed.shared.stop()
                GamepadPoller.shared.onPlayerLost = nil
                AimFeed.shared.activate(gunPlayer: nil) // the game has stopped, so nothing aims
                // The game has stopped, so the Files discs it read are given back.
                for url in discAccess { url.stopAccessingSecurityScopedResource() }
                discAccess = []
                session.quickMenuOpen = false
                controllerInput?.setNavigationCaptured(false, owner: QuickMenuView.controllerScope, priority: 300)
                XmbInGame.shared.end()
                session.inputOwner = .menu
                // Reactivating a background that was never released is a no-op.
                NotificationCenter.default.post(name: EmulatorSession.menuBackgroundReturnNotification, object: nil)
                // A chosen power-off, so the next launch does not take it for a crash.
                if XmbInGame.shared.turnOffAfterExit {
                    DiagnosticsSession.shared.mark(.background)
                    _exit(0)
                }
            }
            .onReceive(Publishers.MergeMany(GamepadPoller.controllerChanges.map { NotificationCenter.default.publisher(for: $0) })) { _ in
                controllerConnected = GamepadPoller.playerOneHasController
            }
            // A restarted game has a new video provider that holds no recording, so the file is finished first.
            .onChange(of: session.state == EmulatorSession.stateStopped || session.restartPending) { _, ended in
                if ended { stopRecordingAutomatically() }
            }
            // In XMB mode a halt is also the console's error dialog, in the XMB over the stopped game; the banner keeps Close ARMSX3.
            .onChange(of: stopStatus) { _, status in
                guard SettingsStore.shared.xmbMode, let status, XmbInGame.shared.gameError(status, jitReason: session.jitReason) else { return }
                showMenu(true)
            }
            .onChange(of: showPad) { _, shown in
                if !shown { PadSnapshot.shared.reset() }
            }
            // Leaving the active state releases every button once, so the deactivation pause never freezes one held.
            .onChange(of: scenePhase) { _, phase in
                // iOS reclaiming a backgrounded game is not a crash, so the session says background and the log reaches disk.
                if bootStatus == 0, !stopping {
                    if phase == .background {
                        DiagnosticsSession.shared.mark(.background)
                        Task { _ = await Rpcs3Bridge.syncLog() }
                    } else if phase == .active {
                        DiagnosticsSession.shared.mark(.inGame)
                    }
                }
                // Only the background ends a recording, since a backgrounded app has no hardware encoder; `.inactive` keeps it.
                rpcnScenePhase(phase)
                if phase == .background { stopRecordingAutomatically(inBackground: true) }
                if phase != .active, bootStatus == 0 { PadSnapshot.shared.reset() }
            }
    }

    @ViewBuilder private var banner: some View {
        if let shot = shotBanner.content {
            Text(shot)
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                // A capsule's round ends cut across text that wraps to four lines; this radius matches it for one or two lines.
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
                // Keeps the sentences clear of the menu button in the top-trailing corner.
                .padding(.horizontal, 56)
                .padding()
        } else if let notice = slotNotice {
            Text(notice)
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.ultraThinMaterial, in: Capsule())
                .padding()
        } else if stopStatus == EmulatorSession.rendererDied || stopStatus == EmulatorSession.jitExhausted
            || stopStatus == EmulatorSession.gameFatal || stopStatus == EmulatorSession.stopHung {
            VStack(spacing: 12) {
                haltText
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    // On a sideways phone the shoulder pair's reach keeps every line between L1 and R1.
                    .padding(.horizontal, verticalSizeClass == .compact ? VirtualControllerView.shoulderReach : 56)
                // _exit skips C++ static destructors, which would run the same blocking teardown, and leaves the crash offer due.
                Button("Close ARMSX3") { _exit(0) }
                    .buttonStyle(.borderedProminent)
                // The report's log sync runs on the bridge queue, which -28 can leave held, so -27 and -28 keep their screen.
                if stopStatus == EmulatorSession.gameFatal || stopStatus == EmulatorSession.stopHung {
                    Button("Report a Problem") { openReport() }
                        .buttonStyle(.bordered)
                }
            }
            .padding()
        } else if saveFailure == EmulatorSession.savestateSpuBusy {
            spuBusyText
                .foregroundStyle(.orange)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 56)
                .padding()
        } else if let status = saveFailure {
            Text("Couldn't save to that slot (status \(status))")
                .foregroundStyle(.orange)
                .padding()
        } else if let status = loadFailure {
            Text("Couldn't load (status \(status))")
                .foregroundStyle(.orange)
                .padding()
        } else if let status = stopStatus {
            Text("Still stopping — try again in a moment (status \(status))")
                .foregroundStyle(.orange)
                .padding()
        } else if let status = bootStatus, status != 0 {
            VStack(spacing: 12) {
                Text("Boot failed (status \(status))")
                    .foregroundStyle(.red)
                // Nothing is running after a failed boot, so leaving is safe.
                Button("Back") { dismiss() }
                    .buttonStyle(.borderedProminent)
            }
            .padding()
        }
    }

    private var menuButton: some View {
        Button {
            setMenu(open: !menuOpen)
        } label: {
            Image(systemName: "line.3.horizontal")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
        }
        .accessibilityLabel("Menu")
        .padding(12)
        .disabled(bootStatus == nil)
    }

    // Lifting the finger leaves the pointer where it was, as an absolute pointer stays.
    private var moveTouchArea: some View {
        GeometryReader { area in
            Color.clear
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                    guard area.size.width > 0, area.size.height > 0 else { return }
                    func axis(_ at: CGFloat, _ length: CGFloat) -> Double { min(max(Double(at / length) * 2 - 1, -1), 1) }
                    GamepadPoller.shared.moveTouch = MovePointer(x: axis(drag.location.x, area.size.width),
                                                                 y: axis(drag.location.y, area.size.height))
                })
        }
    }

    // A drag that starts in a letterbox bar aims nowhere until it enters the image; lifting keeps the last aim.
    private var aimTouchArea: some View {
        GeometryReader { area in
            let frame = area.frame(in: .global)
            Color.clear
                .contentShape(Rectangle())
                // A finger on the picture also holds the uDraw pen down until it lifts, or the system cancels the drag.
                .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .updating($aimHeld) { _, held, _ in held = true }
                    .onChanged { drag in
                        if AimFeed.shared.touch(at: drag.location, imageRect: AimFeed.shared.imageRect) {
                            AimFeed.shared.pen(true, from: .screen)
                        }
                    })
                .onChange(of: aimHeld) { _, held in
                    if !held { AimFeed.shared.pen(false, from: .screen) }
                }
                // An iPad mouse without pointer lock aims where its pointer is, so a click never jumps the aim.
                .onContinuousHover(coordinateSpace: .global) { phase in
                    if case .active(let at) = phase, !KeyboardMouseFeed.deltasAim {
                        AimFeed.shared.touch(at: at, imageRect: AimFeed.shared.imageRect)
                    }
                }
                .onAppear { AimFeed.shared.imageRect = AimFeed.imageRect(in: frame) }
                .onChange(of: frame) { _, now in AimFeed.shared.imageRect = AimFeed.imageRect(in: now) }
        }
        .ignoresSafeArea()
    }

    // A read that fails counts as the standard pad.
    private func activateAim() async {
        var gun: Int?
        var aimDevice = 0
        for player in 0..<PlayerSlots.count {
            let device = await withCheckedContinuation { continuation in
                Rpcs3Bridge.device(forPlayer: player, serial: game.serial) { status, type in
                    continuation.resume(returning: status == 0 ? type : 0)
                }
            }
            if AimFeed.gunOrdinals.contains(device) {
                gun = player
                // The device decides which mouse buttons the feed presses.
                aimDevice = device
                break
            }
        }
        AimFeed.shared.activate(gunPlayer: gun, device: aimDevice)
        aiming = gun != nil
    }

    // The touch layout editor takes the menu's place while it is open.
    @ViewBuilder private var menuLayer: some View {
        if menuOpen, let layoutEditor {
            PadLayoutEditorView(editor: layoutEditor, overGame: true, onClose: { self.layoutEditor = nil })
        } else if menuOpen, SettingsStore.shared.xmbMode, slotPanel == nil, !settingsOpen, !friendsOpen {
            // In XMB mode the pause menu is the XMB; its save slots, settings and friends stay the app's panels.
            XmbGameMenu(game: game, busy: session.restartPending || stopping, calls: xmbCalls, controllerInput: controllerInput,
                        discSwap: discSwap != 0 && !discChanging, recording: RecordingStore.shared.active, invites: rpcnInvites,
                        sheetUp: report != nil || discPicker || portalSheet || controllersSheet != nil || jitDeviceSheet != nil)
        } else if menuOpen {
            menuPanel
        }
    }

    private var menuActions: QuickMenuView.Actions {
        QuickMenuView.Actions(
            resume: { setMenu(open: false) },
            saveState: { slotPanel = .save },
            loadState: { slotPanel = .load },
            exit: { exitWithAutosave() },
            settings: { settingsOpen = true },
            report: { openReport() },
            screenshot: { Task { await captureScreenshot(fromMenu: true) } },
            controllers: { controllersSheet = ControllersSheetRequest(serial: game.serial) },
            changeDisc: { discBoots = false; discPicker = true },
            bootDisc: { discBoots = true; discPicker = true },
            record: { Task { await toggleRecording(fromMenu: true) } },
            portal: { portalSheet = true },
            friends: { friendsOpen = true },
            touchLayout: controllerConnected ? nil : {
                layoutEditor = PadEditor(serial: game.serial, library: PadLayoutStore.shared.library)
            })
    }

    // The XMB asks before a restart and before overwriting a slot, so these act at once.
    private var xmbCalls: XmbInGame.Calls {
        XmbInGame.Calls(actions: menuActions, restart: { resetGame() }, saveSlot: { saveSlot($0) }, loadState: { loadState(at: $0) },
                        declined: { id in rpcnInvites.removeAll { $0.id == id } })
    }

    private var menuPanel: some View {
        // Only the menu itself splits around the fold; its slot, settings and friends panels sit on one leaf.
        GameOverlayContainer(onTapOutside: { setMenu(open: false) },
                             spansFold: slotPanel == nil && !settingsOpen && !friendsOpen) { _ in
            QuickMenuView(
                title: game.title,
                canLoad: hasStates,
                busy: session.restartPending || stopping,
                actions: menuActions,
                controllerInput: controllerInput,
                slotPanel: slotPanelView,
                settingsPanel: settingsOpen
                    ? InGameSettingsPanel(game: game, onBack: { settingsOpen = false }, controllerInput: controllerInput)
                    : nil,
                // Not offered again while a swap is in flight.
                discSwap: discSwap != 0 && !discChanging,
                recording: RecordingStore.shared.active,
                onlineLine: rpcnStatus.flatMap { RpcnSession.quickMenuLine(status: $0) },
                signedIn: rpcnSignedIn,
                invites: rpcnInvites.count,
                friendsPanel: friendsOpen
                    ? FriendsPanel(invites: rpcnInvites, onlineLine: rpcnStatus.flatMap { RpcnSession.quickMenuLine(status: $0) },
                                   onJoined: { setMenu(open: false) },
                                   onDeclined: { id in rpcnInvites.removeAll { $0.id == id } },
                                   onBack: { friendsOpen = false }, controllerInput: controllerInput)
                    : nil)
        }
    }

    private var rpcnSignedIn: Bool { rpcnStatus.map { $0.bootPsn == 2 && $0.session == 2 } ?? false }

    private func rpcnBanner(_ text: String, seconds: Double) {
        shotBanner.present(text, displayDuration: seconds)
        AccessibilityNotification.Announcement(text).post()
    }

    // The read never creates a client and never waits behind a sign-in.
    private func pollInvites() async {
        guard rpcnSignedIn else { rpcnInvites = []; return }
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnInvites { continuation.resume(returning: ($0, $1)) }
        }
        guard status == 0, let json else { return }
        let current = RpcnSession.invites(json)
        let xmb = SettingsStore.shared.xmbMode
        for invite in RpcnSession.inviteChanges(previous: rpcnSeenInvites, current: current) {
            if xmb { XmbNotices.shared.invited(by: invite.from) } else { rpcnBanner(RpcnSession.inviteBanner(invite), seconds: 8) }
        }
        // In XMB mode a friend request is told over the game too, as the console tells it.
        if xmb { await XmbNotices.shared.readRequests() }
        rpcnSeenInvites.formUnion(current.map(\.id))
        rpcnInvites = current
    }

    // A game that is not signed in borrows no background time.
    private func rpcnScenePhase(_ phase: ScenePhase) {
        if phase == .background, rpcnSignedIn, Self.rpcnBackgroundTask == .invalid {
            rpcnReloginsAtBackground = rpcnStatus?.relogins
            rpcnReturn = nil
            Self.rpcnBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "armsx3-rpcn") {
                MainActor.assumeIsolated { Self.endRpcnBackgroundTask() }
            }
        } else if phase == .active {
            Self.endRpcnBackgroundTask()
            if let relogins = rpcnReloginsAtBackground {
                rpcnReloginsAtBackground = nil
                rpcnReturn = RpcnSession.ReturnWatch(reloginsAtBackground: relogins)
                rpcnReturnSince = Date()
            }
        }
    }

    private static func endRpcnBackgroundTask() {
        guard rpcnBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(rpcnBackgroundTask)
        rpcnBackgroundTask = .invalid
    }

    private func watchStorage() async {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let repeatEvery = 6
        var sinceWarned = repeatEvery
        while !Task.isCancelled {
            if bootStatus == 0, !stopping, StorageWatch.isLow(StorageWatch.availableBytes(at: documents)) {
                sinceWarned += 1
                if sinceWarned >= repeatEvery {
                    sinceWarned = 0
                    let text = String(localized: "Storage is almost full. Free some space or the game may close.")
                    shotBanner.present(text, displayDuration: 6)
                    AccessibilityNotification.Announcement(text).post()
                }
            } else {
                sinceWarned = repeatEvery
            }
            try? await Task.sleep(for: .seconds(10))
        }
    }

    private func pollRpcn() async {
        while !Task.isCancelled {
            if bootStatus == 0, !stopping {
                await RpcnModel.shared.readStatus()
                rpcnStatus = RpcnModel.shared.status
                if let status = rpcnStatus, !rpcnBootShown {
                    if let sentence = RpcnSession.bootBanner(status: status, alreadyShown: rpcnBootShown) {
                        rpcnBootShown = true
                        rpcnBanner(sentence, seconds: 6)
                    } else if status.bootPsn == 2, status.session == 2 {
                        rpcnBootShown = true
                    }
                }
                // A return under way, read before the return watch answers this sample.
                let returning = rpcnReloginsAtBackground != nil || rpcnReturn != nil
                if let status = rpcnStatus, var watch = rpcnReturn {
                    let outcome = watch.feed(status, elapsed: Date().timeIntervalSince(rpcnReturnSince))
                    rpcnReturn = outcome == nil ? watch : nil
                    if let outcome {
                        if let sentence = RpcnSession.returnBanner(outcome) { rpcnBanner(sentence, seconds: 6) }
                    }
                }
                if let status = rpcnStatus, rpcnDrop.feed(status, returning: returning) {
                    rpcnBanner(RpcnSession.dropBanner, seconds: 6)
                }
                await pollInvites()
            }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
    }

    private var slotPanelView: SaveStatesPanel? {
        guard let mode = slotPanel, let directory = stateDirectory else { return nil }
        return SaveStatesPanel(mode: mode, gameTitle: game.title, directory: directory,
                               onSave: { saveSlot($0, fromPanel: true) }, onLoad: { loadState(at: $0) }, onBack: { slotPanel = nil })
    }

    // The core's own progress overlay dies with RSX, so this one covers the game during a restart.
    private var restartOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            if SaveProgress.shared.active, SaveProgress.shared.fromPanel, let savingPanel {
                GameOverlayContainer { _ in savingPanel }
                    .allowsHitTesting(false)
            } else if SaveProgress.shared.active {
                VStack {
                    SaveProgressBar(inPanel: false)
                        .padding()
                    Spacer()
                }
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                        .controlSize(.large)
                        .tint(.white)
                    Text(restartLabel)
                        .foregroundStyle(.white)
                }
            }
        }
    }

    // No tap outside answers it.
    private var resumePanel: some View {
        GameOverlayContainer { _ in
            OverlayPanelScaffold {
                VStack(spacing: 12) {
                    OverlayHeader(systemImage: "clock.arrow.circlepath", title: game.title, compact: false)
                    Text("Resume where you left off?")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(OverlayTheme.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    OverlaySectionCard {
                        OverlayActionRow(label: "Resume", systemImage: "play.fill") { resumeChoice = true }
                        OverlayActionRow(label: "Start fresh", systemImage: "arrow.counterclockwise") { resumeChoice = false }
                    }
                }
                .padding()
            }
        }
    }

    // A core that reads STOPPED gets the stopped alert, which closes the screen, so no dead frame is left playable.
    private func saveSlot(_ fileSlot: Int, fromPanel: Bool = false) {
        restartLabel = String(localized: "Saving…")
        savingPanel = fromPanel ? slotPanelView : nil
        Task {
            await leaveMenu()
            let (status, historyName) = await session.saveState(fileSlot: fileSlot, resume: true, fromPanel: fromPanel)
            savingPanel = nil
            guard status != 0 else {
                // A refused restart leaves the game stopped, so the save is recorded and the screen says it is safe and closes.
                await recordSave(fileSlot: fileSlot, historyName: historyName)
                _ = closedOverStoppedCore("Saved, but the game has stopped.", String(localized: "The save is in its slot. The game could not start again."))
                return
            }
            if closedOverStoppedCore("Couldn't save. The game has stopped.", String(localized: "Nothing was saved (status \(status)).")) { return }
            saveFailure = status
            try? await Task.sleep(nanoseconds: status == EmulatorSession.savestateSpuBusy ? 10_000_000_000 : 4_000_000_000)
            saveFailure = nil
        }
    }

    // The history file the core wrote for this save is the app's, so the PS-menu section leaves it out.
    private func recordSave(fileSlot: Int, historyName: String) async {
        guard let directory = await session.saveStateDirectory(serial: game.serial) else { return }
        try? SaveStateFiles.recordAppHistory(name: historyName, in: directory.appendingPathComponent(SaveStateFiles.slotsFolder))
        guard let ui = SaveStateFiles.uiSlot(forFileSlot: fileSlot),
              let file = await SaveStateFile.current(directory: directory).first(where: { $0.slot == ui }) else { return }
        SaveStateMetadataStore.shared.recordSave(of: file, playedSeconds: SaveStateAutoSave.shared.secondsSinceRestart, fresh: false)
    }

    private func loadSlot(_ fileSlot: Int) {
        Task {
            guard let path = await session.slotFile(fileSlot: fileSlot, serial: game.serial) else {
                await leaveMenu()
                return
            }
            await load(path: path)
        }
    }

    private func loadState(at url: URL) {
        Task { await load(path: url.path) }
    }

    private func load(path: String) async {
        restartLabel = String(localized: "Loading…")
        await leaveMenu()
        let status = await session.loadState(path: path, game: game)
        guard status != 0 else {
            SaveStateAutoSave.shared.restart()
            return
        }
        // Refused before the core, so nothing stopped; the plain message, not the status banner.
        if status == EmulatorSession.savestateTooOld {
            stateTooOld = true
            return
        }
        // A stopped core closes the screen, one still stopping goes to Stop; a game that still runs gets the banner.
        if closedOverStoppedCore("Couldn't load. The game has stopped.", String(localized: "The state was not loaded (status \(status)).")) { return }
        loadFailure = status
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        loadFailure = nil
    }

    // The pause machine makes a repeated open or close a no-op.
    private func setMenu(open: Bool) {
        showMenu(open)
        guard pausesWithMenu else { return }
        Task { await session.setMenuPaused(open) }
    }

    // A paused game presents no frame, so the menu's row lets the game run before asking for one.
    private func captureScreenshot(fromMenu: Bool) async {
        if fromMenu { await leaveMenuUntilRunning() }
        let (_, file) = await ScreenshotStore.capture(serial: game.serial)
        if fromMenu, SettingsStore.shared.xmbMode {
            // The XMB says nothing for a shot, which shows in its Photo column; a miss is one of its notices.
            if file == nil { XmbNotices.shared.post(String(localized: "Couldn't capture a frame")) }
        } else if fromMenu {
            if let file { shotFile = file } else { shotFailed = true }
        } else {
            shotBanner.present(file != nil ? String(localized: "Screenshot saved") : String(localized: "Couldn't capture a frame"))
        }
    }

    // refreshState() returns the query's status, never the state, so the loop reads session.state.
    private func leaveMenuUntilRunning() async {
        await leaveMenu()
        var polls = 0
        while polls < 40 {
            await session.refreshState()
            if session.state == EmulatorSession.stateRunning { break }
            try? await Task.sleep(for: .milliseconds(50))
            polls += 1
        }
    }

    // A stop from the menu offers Save to Photos; a stop from the hotkey answers with a banner.
    private func toggleRecording(fromMenu: Bool) async {
        let store = RecordingStore.shared
        guard store.active else {
            if fromMenu { await leaveMenuUntilRunning() }
            if await store.start(serial: game.serial) != 0 { shotBanner.present(String(localized: "Couldn't start recording")) }
            return
        }
        let file = await store.stop()
        if fromMenu, SettingsStore.shared.xmbMode {
            // An alert over the XMB would leave its pad on the menu behind, so the XMB says it as a notice.
            if file != nil {
                XmbNotices.shared.post(String(localized: "Recording saved"), detail: String(localized: "Files › ARMSX3 › Recordings"))
            } else {
                XmbNotices.shared.post(String(localized: "Couldn't save the recording"))
            }
        } else if fromMenu {
            if let file { recordingFile = file } else { recordingFailed = true }
        } else {
            shotBanner.present(file != nil ? String(localized: "Recording saved") : String(localized: "Couldn't save the recording"))
        }
    }

    // In the background the stop runs as a background task, so the writer can finish the file before a suspension.
    private func stopRecordingAutomatically(inBackground: Bool = false) {
        let app = UIApplication.shared
        var task = UIBackgroundTaskIdentifier.invalid
        if inBackground { task = app.beginBackgroundTask(withName: "armsx3-recording") { app.endBackgroundTask(task) } }
        Task {
            if await RecordingStore.shared.stop() != nil { shotBanner.present(String(localized: "Recording saved")) }
            if task != .invalid { app.endBackgroundTask(task) }
        }
    }

    // The menu gives its pause back before the swap, because the eject finishes on the game's own callback.
    private func swapDisc(path: String, filesURL: URL?) async {
        guard !discChanging else { return }
        discChanging = true
        if let filesURL, filesURL.startAccessingSecurityScopedResource() { discAccess.append(filesURL) }
        await leaveMenu()
        let (status, result) = await session.changeDisc(path: path)
        discChanging = false
        shotBanner.present(DiscPicker.message(for: status == 0 ? result : 2))
    }

    // The bridge boots only what the library scanned, so a disc from elsewhere is refused before the game closes.
    private func bootInstead(path: String) async {
        let picked = URL(fileURLWithPath: path).resolvingSymlinksInPath().path
        let games = await Rpcs3Bridge.scanLibrary().0
        guard let disc = games.first(where: { URL(fileURLWithPath: $0.bootPath).resolvingSymlinksInPath().path == picked }) else {
            return shotBanner.present(String(localized: "Only a disc in the library can be started here"))
        }
        XmbInGame.shared.nextDisc = disc.bootPath
        exitWithAutosave()
    }

    private func leaveMenu() async {
        guard menuOpen else { return }
        showMenu(false)
        guard pausesWithMenu else { return }
        await session.setMenuPaused(false)
    }

    private var pausesWithMenu: Bool { bootStatus == 0 && !session.restartPending }

    // A chord acts on a booted game with no save, load, stop or question in flight.
    private var chordsReady: Bool {
        pausesWithMenu && !stopping && !exitFailed && !exitStopped && !confirmQuickLoad && !confirmClose && !confirmReset
    }

    // "Load the quick save?" for the quick slot, else the numbered slot (file slot + 1).
    private var quickLoadQuestion: String {
        activeFileSlot == EmulatorSession.quickFileSlot
            ? String(localized: "Load the quick save? Unsaved progress is lost.")
            : String(localized: "Load save slot \(activeFileSlot + 1)? Unsaved progress is lost.")
    }

    // A later cycle replaces it.
    private func showSlotNotice() {
        let notice = activeFileSlot == EmulatorSession.quickFileSlot ? String(localized: "Save slot: Quick") : String(localized: "Save slot: \(activeFileSlot + 1)")
        slotNotice = notice
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if slotNotice == notice { slotNotice = nil }
        }
    }

    // restartPending is held, so the library never shows.
    private func resetGame() {
        guard !stopping else { return }
        restartLabel = String(localized: "Restarting…")
        Task {
            await leaveMenu()
            let status = await session.resetGame(game)
            guard status != 0 else {
                SaveStateAutoSave.shared.restart()
                return
            }
            if closedOverStoppedCore("Couldn't restart the game.", String(localized: "The game could not start again (status \(status)).")) { return }
            stopStatus = status
        }
    }

    private func showMenu(_ open: Bool) {
        let wasOpen = menuOpen
        menuOpen = open
        if !open { layoutEditor = nil }
        if bootStatus == 0 { session.inputOwner = open ? .menu : .game }
        // The menu frees the pointer at once, and closing it locks the pointer again.
        KeyboardMouseFeed.shared.refreshPointerLock()
        // The router navigates the open quick menu, and captures navigation from the library under it.
        session.quickMenuOpen = open && bootStatus == 0
        // An open in-game XMB already holds its capture, or has let it go to a sheet, when a dropped pad opens the menu again.
        if !(wasOpen && open && SettingsStore.shared.xmbMode) {
            controllerInput?.setNavigationCaptured(session.quickMenuOpen, owner: QuickMenuView.controllerScope, priority: 300,
                                                   padFirst: open && SettingsStore.shared.xmbMode)
        }
        if open {
            // Change Disc is offered only while the game takes a swap, read now.
            discSwap = 0
            if bootStatus == 0 { Task { discSwap = await session.discSwapState() } }
            Task {
                stateDirectory = await session.saveStateDirectory(serial: game.serial)
                if let directory = stateDirectory {
                    let listing = await SaveStatesPanel.scan(directory)
                    hasStates = !listing.slots.isEmpty || !listing.psMenu.isEmpty
                }
            }
        } else {
            slotPanel = nil
            settingsOpen = false
            friendsOpen = false
        }
    }

    private func run() async {
        guard !bootRequested else { return }
        bootRequested = true
        // A title with an exit autosave asks first, and never resumes without an answer.
        // A state picked in the XMB's Load / Manage Saves is the one to start from, with nothing to ask.
        let picked = XmbToolsStore.shared.takeState(serial: game.serial)
        var autosave = if let picked { picked } else { await session.slotFile(fileSlot: EmulatorSession.autoFileSlot, serial: game.serial) }
        // An exit autosave from before the USB bus returned cannot be resumed, so the game boots fresh and says so once.
        let oldAutosave = autosave.map { EmulatorSession.stateEpoch(path: $0) < SaveStateFiles.formatEpoch } ?? false
        if oldAutosave {
            autosave = nil
        }
        var resume = picked != nil
        if autosave != nil, picked == nil {
            guard let answer = await askResume() else { return }
            resume = answer
        }
        // Activated first, so the core's Cubeb stream opens against an active session. A refusal does not stop the boot.
        // The XMB's music and videos may have left the session mixable, and the game's own setup runs once a run.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        _ = await session.activateAudio()
        let status: Int
        if let autosave, resume {
            // The title's own configuration: the game is stopped (LoadState's STOPPED branch).
            status = await session.boot(game, fromState: autosave)
        } else {
            status = await session.boot(game)
        }
        if oldAutosave {
            Self.oldSessionNotes += 1
            shotBanner.present(String(localized: "Your last session was saved by an older version of ARMSX3 and can't be resumed."), displayDuration: 6)
        }
        bootStatus = status
        guard status == 0 else { return }
        SaveStateAutoSave.shared.restart()
        // The title gets the GPU and memory the menu backgrounds were holding.
        NotificationCenter.default.post(name: EmulatorSession.releaseMenuBackgroundResourcesNotification, object: nil)
        session.inputOwner = .game
        GamepadPoller.shared.loadMappings(serial: game.serial)
        GamepadPoller.shared.start()
        KeyboardMouseFeed.shared.start() // the hardware keyboard is the PS3's USB keyboard
        // A controller that drops mid-game pauses the game and names its player by number only, never the controller.
        GamepadPoller.shared.onPlayerLost = { slot in
            setMenu(open: true)
            shotBanner.present(String(localized: "Player \(slot + 1) controller disconnected"))
        }
        showShortcutHelp = true
        Task {
            try? await Task.sleep(nanoseconds: Self.shortcutHelpSeconds * 1_000_000_000)
            showShortcutHelp = false
        }
        // .task cancels the loop when the screen goes away.
        await SessionRecord.shared.start(game: game, bootStatus: status)
        // A kill from here on leaves the session in-game, which is the crash offer's evidence.
        DiagnosticsSession.shared.mark(.inGame, serial: game.serial, title: game.title)
        await activateAim()
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(SessionRecord.updateSeconds * 1_000_000_000))
            if Task.isCancelled { break }
            // Read even mid-stop or restart, because a halt is what keeps a Stop from ever landing.
            let halt = Rpcs3Bridge.haltStatus()
            if halt != 0 && stopStatus != halt {
                stopStatus = halt
            }
            // A Stop in flight or a save or load restart skips the tick; a Stop that timed out lets the record carry on.
            if stopping || session.restartPending { continue }
            // The dual map latches at most one offer per game session; the read clears it.
            // An open XMB leaves the offer latched, so its alert never comes up over the XMB, which keeps the pad.
            if stopStatus == nil, jitOffer == nil, !(menuOpen && SettingsStore.shared.xmbMode) {
                let taken = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.takeJitSecondPassOffer { continuation.resume(returning: $0) }
                }
                let trigger = taken
                if trigger != 0 {
                    jitOffer = trigger
                }
            }
            // The state read at boot is STARTING; this picks up RUNNING, and a self-exit.
            await session.refreshState()
            await SessionRecord.shared.update()
        }
    }

    // nil only when the screen went away before an answer, and then nothing boots.
    private func askResume() async -> Bool? {
        resumePrompt = true
        defer { resumePrompt = false }
        while resumeChoice == nil {
            if Task.isCancelled { return nil }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return resumeChoice
    }

    // The screen stays until the core reads STOPPED. Retrying is safe: Emulator::Kill(false) returns while a close is pending.
    private func stop() {
        stopping = true
        Task {
            let status = await session.stop()
            if status == 0 {
                // Giving the pause back to a stopped core calls nothing that can run the game.
                if menuOpen { await session.setMenuPaused(false) }
                GamepadPoller.shared.stop()
                KeyboardMouseFeed.shared.stop()
                GamepadPoller.shared.onPlayerLost = nil
                await SessionRecord.shared.finish(stopStatus: status)
                DiagnosticsSession.shared.mark(.menu)
                // Music picked in the open XMB is held before the screen goes; the home's cover hands it back after.
                if menuOpen, SettingsStore.shared.xmbMode { XmbMedia.shared.stop() }
                dismiss()
            } else {
                stopStatus = status
                stopping = false
            }
        }
    }

    // Within settleSeconds of a boot or a load it just stops, so a quick look never replaces a real autosave.
    private func exitWithAutosave() {
        guard bootStatus == 0 else { return stop() }
        guard !stopping else { return }
        guard SaveStateAutoSave.shared.secondsSinceRestart >= SaveStateAutoSave.settleSeconds else {
            return stop()
        }
        stopping = true
        restartLabel = String(localized: "Saving…")
        exitSaving = true
        Task {
            await leaveMenu()
            let directory = await session.saveStateDirectory(serial: game.serial)
            let status = await withCheckedContinuation { continuation in
                SaveStateAutoSave.shared.save(leaving: true, directory: directory) { continuation.resume(returning: $0) }
            }
            guard status == 0 else {
                // Stopped: Keep playing would leave a dead frame, so it is never offered.
                exitSaving = false
                stopping = false
                exitFailureStatus = status
                await session.refreshState()
                if closedOverStoppedCore("Couldn't save. The game has stopped.", String(localized: "Nothing was saved (status \(status)).")) { return }
                // Keep playing only over a game that runs or is paused; anything else stops.
                guard session.state == EmulatorSession.stateRunning || session.state == EmulatorSession.statePaused else { return stop() }
                exitFailed = true
                return
            }
            // The core reads STOPPED once the state is written; polled for at most 30 s.
            var polls = 0
            while session.state != EmulatorSession.stateStopped && polls < 120 {
                try? await Task.sleep(nanoseconds: 250_000_000)
                await session.refreshState()
                polls += 1
            }
            exitSaving = false
            // Still not stopped: the ordinary Stop, with its own retry, takes over.
            guard session.state == EmulatorSession.stateStopped else { return stop() }
            await closeAfterStop()
        }
    }

    private func closeAfterStop() async {
        GamepadPoller.shared.stop()
        KeyboardMouseFeed.shared.stop()
        GamepadPoller.shared.onPlayerLost = nil
        await SessionRecord.shared.finish(stopStatus: 0)
        DiagnosticsSession.shared.mark(.menu)
        await session.deactivateAudio()
        // Music picked in the open XMB is held before the screen goes; the home's cover hands it back after.
        if menuOpen, SettingsStore.shared.xmbMode { XmbMedia.shared.stop() }
        dismiss()
    }

    // The one place the SPU-busy sentence lives, so the String Catalog extracts it once.
    private var spuBusyText: Text {
        Text("This game was busy and couldn't be saved. Turn on Compatible Savestate Mode in this game's settings, restart the game, then save again.")
    }

    // StikDebug attaches by pid (JitSecondPass.swift says why). The pass starts even when iOS refuses the link, so it is logged.
    private func openStikDebugForMoreJit(_ trigger: Int) {
        guard stopStatus == nil else { return }
        let start: (Bool) -> Void = { opened in
            Rpcs3Bridge.startJitSecondPass(trigger, opened: opened) { _ in }
        }
        guard let url = JitSecondPassLink.url(bundleID: LiveContainerProbe.stikDebugBundleID,
                                              pid: ProcessInfo.processInfo.processIdentifier) else {
            start(false)
            return
        }
        UIApplication.shared.open(url) { start($0) }
    }

    private func openReport() {
        guard let current = DiagnosticsSession.shared.current else {
            return
        }
        report = ReportRequest(session: current, serial: game.serial)
    }

    // One literal per case, so the String Catalog extracts each.
    private var haltText: Text {
        if stopStatus == EmulatorSession.gameFatal {
            return Text("The game stopped because of an error. Close ARMSX3 and open it again to keep playing.")
        }
        if stopStatus == EmulatorSession.stopHung {
            return Text("The game isn't responding, so it can't be stopped. Close ARMSX3 and open it again to keep playing.")
        }
        guard stopStatus == EmulatorSession.jitExhausted else {
            return Text("The game's graphics crashed, so it can't be stopped safely. Close ARMSX3 and open it again to keep playing.")
        }
        if session.jitReason != "helper-ok" {
            return Text("The game ran out of JIT memory. Built-in JIT prepares more. Set it up in Settings › JIT and Device, then open ARMSX3 again.")
        }
        return Text("The game ran out of JIT memory. Close ARMSX3 and open it again.")
    }

    // STOPPED: the stopped alert. Still STOPPING after the 30 s settle: the ordinary Stop. Otherwise the game carries on.
    private func closedOverStoppedCore(_ title: String, _ message: String) -> Bool {
        // Every caller's title is a literal String Catalog key, looked up here.
        let title = String(localized: String.LocalizationValue(title))
        if session.state == EmulatorSession.stateStopped {
            stoppedTitle = title
            stoppedMessage = message
            exitStopped = true
            return true
        }
        if session.state == EmulatorSession.stateStopping {
            stop()
            return true
        }
        return false
    }
}

// One request per opening, so the sheet is rebuilt each time.
struct JitDeviceSheetRequest: Identifiable {
    let id = UUID()
}
