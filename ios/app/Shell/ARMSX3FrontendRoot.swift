// SPDX-License-Identifier: GPL-2.0-only
// The app's root: stderr drain, diagnostics, the JIT check, prepare and the library, then RootView. Nothing is restored.

import SwiftUI

struct ARMSX3FrontendRoot: View {
    @Environment(\.scenePhase) private var scenePhase

    private enum Stage {
        case checking
        case jitOff(String)
        case failed(String)
        case ready
    }

    private struct AutobootRequest: Identifiable {
        let id = UUID()
        let game: Rpcs3GameEntry
    }

    @State private var stage: Stage = .checking
    @State private var library = LibraryModel()
    @State private var controllerInput = MenuControllerInputRouter()
    @State private var autoboot: AutobootRequest?
    @State private var started = false
    @State private var crashOffer = false
    @State private var report: ReportRequest?
    @State private var pendingLink: DeepLink?
    @State private var libraryReady = false
    @State private var linkMessage: String?
    @State private var setup: SetupRequest?

    var body: some View {
        content
            // Without a tint here, switches outside the Settings and overlay kits drew iOS green.
            .tint(Color.blue)
            .onReceive(NotificationCenter.default.publisher(for: SetupFlow.runAgainNotification)) { _ in
                SettingsStore.shared.setupStep = 1
                setup = SetupRequest(step: 1)
            }
            .task { await start() }
            .fullScreenCover(item: $autoboot) { request in
                GameScreenView(game: request.game)
                    .tint(Color.blue)
                    .environment(\.menuControllerInputRouter, controllerInput)
            }
            .fullScreenCover(item: $setup) { request in
                SetupFlow(start: request.step, library: library, controllerInput: controllerInput, onEnd: { setup = nil })
                    .tint(Color.blue)
                    .environment(\.menuControllerInputRouter, controllerInput)
            }
            // Backgrounding in the menus is not a death; `.inactive` is ignored, as in GameScreenView.
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    DiagnosticsSession.shared.scene(active: true)
                    UpdateStore.shared.appBecameActive()
                } else if phase == .background {
                    DiagnosticsSession.shared.scene(active: false)
                }
            }
            .alert(crashOfferTitle, isPresented: $crashOffer) {
                Button("Report a Problem") {
                    if let crashed = DiagnosticsSession.shared.crashed {
                        report = ReportRequest(session: crashed, serial: DiagnosticsStore.live().serial(of: crashed))
                    }
                }
                Button("Not Now", role: .cancel) {}
            } message: {
                Text("Send a report so we can look into it? Nothing is sent unless you share it.")
            }
            .sheet(item: $report) { ProblemReportView(session: $0.session, serial: $0.serial) }
            .onOpenURL { receive($0) }
            // A link waits behind the crash offer and its report, so its message or boot is never lost under them.
            .onChange(of: crashOffer) { resolvePending() }
            .onChange(of: report == nil) { resolvePending() }
            .onChange(of: setup == nil) { resolvePending() }
            .onChange(of: EmulatorSession.shared.isRunning) { UpdateStore.shared.gameStateChanged() }
            // A link boot that failed never changes isRunning, so its screen closing releases the update queue.
            .onChange(of: autoboot == nil) { if autoboot == nil { UpdateStore.shared.gameStateChanged() } }
            .alert(linkMessage ?? "", isPresented: linkMessageShown) {
                Button("OK", role: .cancel) {}
            }
    }

    private var crashOfferTitle: String {
        switch DiagnosticsSession.shared.crashKind {
        case .menu: return String(localized: "ARMSX3 closed unexpectedly")
        case .starting: return String(localized: "ARMSX3 closed while starting")
        case .game, nil: return String(localized: "ARMSX3 closed during a game")
        }
    }

    private var linkMessageShown: Binding<Bool> {
        Binding(get: { linkMessage != nil }, set: { if !$0 { linkMessage = nil } })
    }

    private func receive(_ url: URL) {
        if PairingLink.isPairingLink(url) {
            switch PairingLink.take(url, at: Date(), in: .standard) {
            case .accepted(let data):
                installPairing(data, origin: .sidestoreLink)
            case .ignored:
                PairingLink.lastAnswer = .ignored
                linkMessage = String(localized: "ARMSX3 didn't ask for that pairing file, so it was ignored.")
            case .malformed:
                PairingLink.lastAnswer = .invalid
                linkMessage = JitNextStep.importRefusalSentence(PairingImport.Outcome.invalid.rawValue)
            }
            return
        }
        if url.isFileURL, url.pathExtension.lowercased() == PadLayoutFile.fileExtension {
            linkMessage = PadLayoutStore.shared.importMessage(url)
            return
        }
        if url.isFileURL, ["plist", "mobiledevicepairing", "mobiledevicepair"].contains(url.pathExtension.lowercased()) {
            installPairing(PairingImport.readShared(url), origin: .shared)
            return
        }
        guard let link = DeepLink.parse(url) else {
            linkMessage = String(localized: "That isn't an ARMSX3 link.")
            return
        }
        pendingLink = link
        resolvePending()
    }

    // A nil read counts as a failed import. The link's answer is kept for the pane; a shared file is only told in the alert.
    private func installPairing(_ data: Data?, origin: PairingSource.Origin) {
        let result = data.map { PairingImport.install(data: $0, origin: origin) } ?? (outcome: PairingImport.Outcome.failed, replaced: false)
        let answer: PairingLink.Answer
        switch result.outcome {
        case .imported: answer = result.replaced ? .replaced : .added
        case .legacyLockdown: answer = .lockdown
        case .invalid: answer = .invalid
        case .failed: answer = .failed
        }
        if origin == .sidestoreLink { PairingLink.lastAnswer = answer }
        switch answer {
        case .replaced:
            linkMessage = String(localized: "Pairing file replaced. ARMSX3 uses the new one the next time it opens.")
        case .added:
            linkMessage = String(localized: "Pairing file added. ARMSX3 uses it the next time it opens.")
        default:
            let refusal = JitNextStep.importRefusalSentence(result.outcome.rawValue)
            linkMessage = answer == .lockdown && origin == .sidestoreLink ? refusal.map { $0 + " " + PairingSource.sideStoreLockdownFix } : refusal
        }
    }

    private func resolvePending() {
        guard libraryReady, !crashOffer, report == nil, setup == nil, let link = pendingLink else { return }
        pendingLink = nil
        switch link {
        case .library, .settings:
            NotificationCenter.default.post(name: DeepLink.openTabNotification, object: link == .library ? 0 : 2)
        case .boot(let serial):
            // Exact serial, first in the library's own order; no fuzzy match.
            let entry = library.entries.first { $0.serial == serial }
            let game = entry.flatMap { library.game(for: $0) }
            if EmulatorSession.shared.restorePhase != .idle {
                // Nothing boots during a restore or before the relaunch it needs.
                linkMessage = String(localized: "A backup is being restored. Close and reopen ARMSX3, then open the link again.")
            } else if EmulatorSession.shared.isRunning || autoboot != nil {
                linkMessage = String(localized: "Stop the current game before opening another.")
            } else if entry?.locked == true {
                linkMessage = String(localized: "That game needs a licence first. Open it from the library to add one.")
            } else if entry?.unreadable == true {
                linkMessage = String(localized: "This game can't be read, and a licence won't help. It may be built for a PS3 running CFW or HEN.")
            } else if let entry, let gone = LibraryRelocation.goneMessage(bootPath: entry.bootPath, isISO: entry.isISO) {
                // The ISO's image is gone, so nothing boots.
                linkMessage = gone
            } else if let game {
                UpdateStore.shared.holdForLaunch()
                autoboot = AutobootRequest(game: game)
            } else {
                linkMessage = String(localized: "No installed game has the serial \(serial).")
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch stage {
        case .checking:
            ProgressView("Checking JIT…")
        case .jitOff(let reason):
            JitOffScreen(reason: reason, helperOutcome: EmulatorSession.shared.jitHelperOutcome, controllerInput: controllerInput)
                .onAppear { DiagnosticsSession.shared.markFirstScreen() }
        case .failed(let message):
            Text(message)
                .font(.footnote)
                .foregroundStyle(.red)
                .padding()
                .onAppear { DiagnosticsSession.shared.markFirstScreen() }
        case .ready:
            // During a restore the menus take no touch, so nothing writes a stale copy over what it restored.
            if EmulatorSession.shared.restorePhase == .restored {
                Text("Your backup is restored. Close ARMSX3 and open it again to finish.")
                    .multilineTextAlignment(.center)
                    .padding()
                    .onAppear { DiagnosticsSession.shared.markFirstScreen() }
            } else {
                RootView(library: library, controllerInput: controllerInput)
                    .onAppear { DiagnosticsSession.shared.markFirstScreen() }
                    .overlay {
                        if EmulatorSession.shared.restorePhase == .restoring {
                            ZStack {
                                Color.black.opacity(0.6).ignoresSafeArea()
                                ProgressView("Restoring your backup…").tint(.white).foregroundStyle(.white)
                            }
                        }
                    }
            }
        }
    }

    // File metadata only; the firmware file is never opened and no path is logged.
    private func presentSetupIfDue() {
        let store = SettingsStore.shared
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let decision = SetupRules.launchStep(
            finished: store.setupFinished, savedStep: store.setupStep,
            hasFolders: !BookmarkStore(directory: LibraryModel.defaultDirectory).folders.isEmpty,
            hasFirmware: PairingImport.isNonEmptyRegularFile(SetupRules.firmwareMarker(applicationSupport: support)))
        if decision.markFinished { store.setupFinished = true }
        if let step = decision.present { setup = SetupRequest(step: step) }
    }

    private func start() async {
        UpdateStore.shared.library = library
        UpdateStore.shared.launch()
        guard !started else { return }
        started = true
        // fd 2 must be the app's drained pipe before the JIT check and any UIKit NSLog.
        _ = Rpcs3Bridge.installStderrDrain()
        // Off the main actor: a deleted game can be tens of gigabytes.
        Task.detached(priority: .utility) {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let freed = DocumentsTrash.empty(documents: documents)
            if freed > 0 { NSLog("ARMSX3: emptied Documents/.Trash, freed %lld bytes", freed) }
        }
        // A savestate with no epoch record that is at least this new was made by this build.
        if UserDefaults.standard.object(forKey: SaveStateFiles.epochSinceKey) == nil {
            UserDefaults.standard.set(Date(), forKey: SaveStateFiles.epochSinceKey)
        }
        Rpcs3Bridge.installLifecycleObservers()
        // Registered before anything can boot. The handler runs on main; only imported files play.
        Rpcs3Bridge.setSoundHandler { path, volume in
            MainActor.assumeIsolated { _ = SystemSounds.shared.play(path: path, volume: volume) }
        }
        if let directory = await DiagnosticsSession.shared.begin() {
            _ = await Rpcs3Bridge.startDiagnostics(inDirectory: directory.path,
                                                   verbose: SettingsStore.shared.verboseLogging)
        }
        CrashDiagnostics.shared.start()
        // Detached and never awaited, so launch never waits on the config list.
        ConfigDatabase.refreshIfStale()

        let session = EmulatorSession.shared
        let on = await session.checkJIT()
        let defaults = UserDefaults.standard
        let migrationPending = JitFallback.migrates(storedMethod: SettingsStore.shared.jitMethod)
        // A backup restore can bring the old word back, and the once-only key stops a second notice.
        if migrationPending { SettingsStore.shared.jitMethod = JitMethod.builtin.rawValue }
        let verdict = JitFallback.decide(jitOn: on, jitReason: session.jitReason, helperWord: session.jitHelperOutcome,
                                         methodAtLaunch: SettingsStore.shared.jitMethodAtLaunch,
                                         storedCause: JitFallback.storedCause(in: defaults)?.rawValue,
                                         probe: LiveContainerProbe.current)
        JitFallback.store(verdict, in: defaults)
        if on {
            let announced = JitFallback.notice(verdict: verdict, jitOnThroughBuiltin: session.jitReason == "helper-ok",
                                               migrationPending: migrationPending, probe: LiveContainerProbe.current,
                                               shown: JitFallback.shownKeys(in: defaults),
                                               supportEnds: JitFallback.supportEndsAnnounced)
            JitNoticeModel.shared.current = announced?.notice
            JitNoticeModel.shared.marks = announced?.marks ?? []
        }
        // Every session carries this launch's record, booted or not.
        DiagnosticsSession.shared.recordLaunch()
        presentSetupIfDue()
        if !on {
            stage = .jitOff(session.jitReason)
            return
        }

        let (host, initStatus) = await Rpcs3Bridge.prepare()
        guard host == 0, initStatus == 0 else {
            stage = .failed(String(localized: "Could not start the emulator (host \(host), init \(initStatus))."))
            return
        }
        // Before anything can boot, so a game that signs in finds the saved login.
        await RpcnModel.shared.restoreSecret()
        // The preset's path is under this launch's container, so it is set again before the library.
        await ShaderPacks.reapply()
        // Bundled game patches go in before anything can boot, so a first boot already gets them.
        _ = await BundledPatches.ensure()
        // Android's one-time reset of SPU loop detection to off, before anything boots.
        _ = await SpuLoopDetectionReset.ensure()

        // Shown at once over the cached library; this runs before GameListView's .task, so the model starts once.
        stage = .ready
        if DiagnosticsSession.shared.crashed != nil {
            crashOffer = true
        }
        await library.start()

        // A link that arrived early is resolved now.
        libraryReady = true
        resolvePending()
    }
}
