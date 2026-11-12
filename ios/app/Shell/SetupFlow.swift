// SPDX-License-Identifier: GPL-2.0-only
// First-run setup: six pages, each skippable. The current step is saved so a relaunch for JIT resumes there.

import GameController
import SwiftUI
import UniformTypeIdentifiers

struct SetupRequest: Identifiable {
    let id = UUID()
    let step: Int
}

enum SetupAction: String {
    case continueStep = "continue", skip, finish, skipAll = "skip-all"
}

struct SetupFlow: View {
    static let runAgainNotification = Notification.Name("ARMSX3RunSetupAgain")

    let start: Int
    let library: LibraryModel
    let controllerInput: MenuControllerInputRouter
    let onEnd: () -> Void
    @State private var path: [Int]
    @State private var steps: SetupStepsModel

    init(start: Int, library: LibraryModel, controllerInput: MenuControllerInputRouter, onEnd: @escaping () -> Void) {
        self.start = start
        self.library = library
        self.controllerInput = controllerInput
        self.onEnd = onEnd
        // A resumed step starts with the earlier steps beneath it, so back reaches them.
        let resume = SetupRules.steps.contains(start) ? start : 1
        _path = State(initialValue: resume > 1 ? Array(2...resume) : [])
        _steps = State(initialValue: SetupStepsModel(library: library))
    }

    var body: some View {
        NavigationStack(path: $path) {
            page(1)
                .navigationDestination(for: Int.self) { page($0) }
        }
        .preferredColorScheme(.dark)
        // The cover hides the library's own alert, so the one-time move ask is shown here too.
        .libraryMoveAsk(library)
        .onAppear {
            // The cover can come up before RootView starts the router, and RootView never appears with JIT off.
            controllerInput.start()
            controllerInput.setMenuActive(true)
        }
    }

    private func page(_ step: Int) -> some View {
        SetupStepPage(step: step, controller: SettingsControllerContext(input: controllerInput), steps: steps,
                      onAction: { act($0, on: step) }, onBack: { back(from: step) })
    }

    private func act(_ action: SetupAction, on step: Int) {
        if action != .skipAll, action != .finish, let next = SetupRules.next(step) {
            path.append(next)
            return
        }
        let store = SettingsStore.shared
        store.setupFinished = true
        store.setupStep = 0
        onEnd()
    }

    // Circle/B: on step 1 nothing, so the router gives boundary feedback.
    private func back(from step: Int) -> Bool {
        // Not while the firmware installs; step 4 hides its back button then too.
        guard !steps.installs.busy, SetupRules.previous(step) != nil, !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}

// One per setup cover, so a step keeps what it knows when the player goes back and forth.
@MainActor @Observable final class SetupStepsModel {
    static let noGames = String(localized: "No PS3 games were found in that folder. Pick the folder that holds them, or install .pkg games from the library.")
    static let unreadable = String(localized: "That folder couldn't be read. If it is in iCloud Drive, download it first.")
    static let gamesNeedJit = String(localized: "Games are counted once JIT is on.")
    static let firmwareNeedsJit = String(localized: "Firmware can be installed once JIT is on. Install it later from the library's Install screen.")

    let library: LibraryModel
    private(set) var folderCount: Int
    private(set) var adding = false
    private var gamesProblem: String?
    var version = ""
    var reading = false
    let installs = InstallModel()
    private(set) var firmwareResult: String?
    private(set) var controllerName: String?

    init(library: LibraryModel) {
        self.library = library
        folderCount = library.folderCount
    }

    var jitOn: Bool { JitDeviceModel.shared.jitOn }
    var busy: Bool { adding || reading || installs.busy }

    var folderText: String {
        SetupRules.folderText(folders: folderCount, games: jitOn ? library.entries.count : nil, jitOn: jitOn)
    }

    var gamesCaption: String? { gamesProblem ?? (jitOn ? nil : Self.gamesNeedJit) }

    /// JIT off saves the bookmark only, because the library's add ends in Rpcs3Bridge.prepare().
    func add(_ url: URL) async {
        adding = true
        defer { adding = false }
        if jitOn {
            let before = library.entries.count
            if await library.addFolder(url) {
                gamesProblem = library.entries.count > before ? nil : Self.noGames
            } else {
                gamesProblem = Self.unreadable
            }
        } else {
            do {
                try await library.rememberFolder(url)
                gamesProblem = nil
            } catch {
                gamesProblem = Self.unreadable
            }
        }
        folderCount = library.folderCount
    }

    var firmwareText: String {
        SetupRules.firmwareValue(jitOn: jitOn, version: version, installing: installs.busy, reading: reading)
    }

    var firmwareCaption: String? { jitOn ? firmwareResult : Self.firmwareNeedsJit }

    func installFirmware(_ url: URL) async {
        guard jitOn else { return }
        await installs.install(urls: [url])
        if let installed = installs.version { version = installed }
        firmwareResult = installs.rows.first?.result ?? installs.message
        if let firmwareResult { AccessibilityNotification.Announcement(firmwareResult).post() }
    }

    /// Step 6: the first controller with an extended gamepad, which on one controller is player 1's.
    func refreshController() {
        controllerName = GamepadPoller.shared.controllersProvider().first { $0.extendedGamepad != nil }.map(Self.name)
    }

    static func name(_ controller: GCController) -> String { controller.vendorName ?? controller.productCategory }
}

// The folder picker is a file importer, not a sheet (see pickingFolder).
enum SetupSheet: String, Identifiable {
    case firmware
    case report
    var id: String { rawValue }
}

enum SetupStepPrimary: Equatable {
    case setup(SetupPrimary), jit(JitPrimary)

    var title: String {
        switch self {
        case .setup(let primary): return primary.title
        case .jit(let primary): return primary.title
        }
    }
}

struct SetupStepPage: View {
    let step: Int
    var controller = SettingsControllerContext()
    let steps: SetupStepsModel
    let onAction: (SetupAction) -> Void
    let onBack: @MainActor () -> Bool
    @State private var model = JitDeviceModel.shared
    @State private var sheet: SetupSheet?
    @State private var pickingFolder = false
    @State private var showingPicker = false
    @State private var pairingRequest: PairingImportRequest?
    @Environment(\.scenePhase) private var scenePhase
    // A phone on its side puts the heading beside the card and Skip beside the primary, so the card stays in view.
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private var sideBySide: Bool { verticalSizeClass == .compact }
    private var hasCard: Bool { (2...SetupRules.stepCount).contains(step) }
    private static let headingWidth: CGFloat = 260

    var body: some View {
        SettingsPage {
            if sideBySide && hasCard {
                HStack(alignment: .top, spacing: 24) {
                    heading.frame(width: Self.headingWidth)
                    VStack(spacing: 16) { content }
                }
            } else {
                heading
                content
            }
        }
        .safeAreaInset(edge: .bottom) {
            Group {
                if sideBySide {
                    HStack(spacing: 24) {
                        skipButton.frame(maxWidth: primary == nil ? .infinity : Self.headingWidth)
                        primaryButton
                    }
                } else {
                    VStack(spacing: 8) {
                        primaryButton
                        skipButton
                    }
                }
            }
            // Inset, then capped, as SettingsPage does its cards, so the buttons line up with the columns above.
            .padding(.horizontal)
            .frame(maxWidth: 720)
            .padding(.top, sideBySide ? 8 : 12)
            .frame(maxWidth: .infinity)
            .background(.bar)
        }
        .navigationTitle(Text("Step \(step) of \(SetupRules.stepCount)"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(steps.installs.busy)
        // Setup runs before Settings can be opened, so this is the only way to a report.
        .toolbar {
            if DiagnosticsSession.shared.current != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Report") { sheet = .report }
                        .controllerAccessibilityActionTarget(id: "setup.report", label: String(localized: "Report")) { sheet = .report }
                }
            }
        }
        // The system presents this picker itself: embedded in a sheet, its Open button did nothing for a folder.
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { Task { await steps.add(url) } }
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .firmware:
                FirmwarePicker(types: [.item]) { url in
                    sheet = nil
                    if let url { Task { await steps.installFirmware(url) } }
                }
            case .report:
                if let session = DiagnosticsSession.shared.current {
                    ProblemReportView(session: session, serial: nil)
                }
            }
        }
        .jitActionPresenters(model: model, controller: controller, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
        // Above the library's scopes, which can register after the cover appears.
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "setup.step.\(step)", priority: 200,
                                           onBack: onBack, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: primary?.title ?? skipTitle)
        .onAppear {
            SettingsStore.shared.setupStep = step
            if step == 2 || step == 3 { model.refresh() }
        }
        .onChange(of: scenePhase) { _, phase in
            // Step 3 reads a StikDebug hand-off coming back, as the pane does.
            if step == 3, phase == .active {
                model.sceneBecameActive()
                model.refresh()
            }
        }
    }

    private var heading: some View {
        let leading = sideBySide && hasCard
        return VStack(alignment: leading ? .leading : .center, spacing: 12) {
            ProgressView(value: Double(step), total: Double(SetupRules.stepCount))
            Image(systemName: Self.symbol(step))
                .font(.system(size: sideBySide ? 32 : 44))
                .foregroundStyle(.tint)
                .padding(.top, sideBySide ? 4 : 20)
                .accessibilityHidden(true)
            Text(Self.title(step))
                .font(.title2.weight(.bold))
                .foregroundStyle(OverlayTheme.textPrimary)
            Text(Self.sentence(step))
                .font(.body)
                .foregroundStyle(OverlayTheme.textSecondary)
                .multilineTextAlignment(leading ? .leading : .center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: leading ? .leading : .center)
        // VoiceOver reads the progress, the title and the sentence as one element.
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var primaryButton: some View {
        if let primary {
            Button { press(primary) } label: { Text(primary.title).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.mounting || model.checking || steps.busy)
                .accessibilityHint(Text(hint(primary)))
                .controllerAccessibilityActionTarget(id: "setup.primary", label: primary.title, action: { press(primary) })
        }
    }

    private var skipButton: some View {
        Button { skip() } label: { Text(skipTitle).frame(maxWidth: .infinity, minHeight: 44) }
            .buttonStyle(.borderless)
            .disabled(steps.installs.busy)
            .controllerAccessibilityActionTarget(id: "setup.skip", label: skipTitle, action: { skip() })
    }

    @ViewBuilder private var content: some View {
        switch step {
        case 2: DeviceCard(model: model)
        case 3: JitCard(model: model, host: .setup, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
        case 4: firmwareCard
        case 5: gamesCard
        case 6: controllerCard
        default: EmptyView()
        }
    }

    private var jitOn: Bool { model.jitOn }

    // The version is read only with JIT on, after the core is prepared; later calls report the first answer.
    private var firmwareCard: some View {
        OverlaySectionCard {
            StatusRow(label: String(localized: "Firmware"), value: steps.firmwareText, fix: steps.firmwareCaption,
                      busy: steps.installs.busy)
        }
        .task {
            guard jitOn else { return }
            steps.reading = true
            let (host, status) = await Rpcs3Bridge.prepare()
            var version = ""
            if host == 0, status == 0 {
                // The Keychain's RPCN login to the core, as the root does.
                await RpcnModel.shared.restoreSecret()
                version = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.firmwareVersion { version, status in continuation.resume(returning: status == 0 ? version : "") }
                }
            }
            steps.version = version
            steps.reading = false
        }
    }

    // The name is never logged.
    private var controllerCard: some View {
        let value = steps.controllerName ?? String(localized: "None")
        return OverlaySectionCard {
            StatusRow(label: String(localized: "Controller"), value: value)
                .accessibilityLabel(Text("Controller"))
                .accessibilityValue(Text(value))
        }
        .onAppear { steps.refreshController() }
        .onReceive(NotificationCenter.default.publisher(for: .GCControllerDidConnect)) { note in
            steps.refreshController()
            if let controller = note.object as? GCController, controller.extendedGamepad != nil {
                AccessibilityNotification.Announcement("\(SetupStepsModel.name(controller)) connected.").post()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .GCControllerDidDisconnect)) { _ in steps.refreshController() }
    }

    private var gamesCard: some View {
        OverlaySectionCard {
            OverlayActionRow(controllerNavigationID: "setup.games.folders", label: "Game Folders", systemImage: nil,
                             trailingValue: steps.folderText, keepsTrailingValue: true) {
                pickingFolder = true
            }
            .disabled(steps.busy)
            .accessibilityLabel(Text("Game Folders"))
            .accessibilityValue(Text(steps.folderText))
            .accessibilityHint(Text("Adds another game folder."))
            if let caption = steps.gamesCaption { Self.caption(caption) }
        }
    }

    static func caption(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(OverlayTheme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var primary: SetupStepPrimary? { Self.primary(step: step, model: model, steps: steps) }

    /// Step 3: Continue when JIT is on, else the same primary the JIT-off screen shows; none leaves Skip as the only button.
    static func primary(step: Int, model: JitDeviceModel, steps: SetupStepsModel) -> SetupStepPrimary? {
        switch step {
        case 3:
            if model.jitOn { return .setup(.continueStep) }
            return model.step.primary.map(SetupStepPrimary.jit)
        case 4: return SetupRules.firmwarePrimary(jitOn: model.jitOn, installed: !steps.version.isEmpty).map(SetupStepPrimary.setup)
        case 5: return .setup(SetupRules.gamesPrimary(folderCount: steps.folderCount))
        case SetupRules.stepCount: return .setup(.finishSetup)
        default: return .setup(.continueStep)
        }
    }

    private func hint(_ primary: SetupStepPrimary) -> String {
        if case .jit(let jit) = primary { return JitDevicePane.hint(jit) }
        return ""
    }

    private var skipTitle: String { step == 1 ? String(localized: "Skip Setup") : String(localized: "Skip") }

    private func press(_ primary: SetupStepPrimary) {
        switch primary {
        case .setup(.addGameFolder): pickingFolder = true
        case .setup(.installFirmware): sheet = .firmware
        case .setup(let setup): onAction(setup == .finishSetup ? .finish : .continueStep)
        case .jit(let jit): JitDevicePane.perform(jit, model: model, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
        }
    }

    private func skip() { onAction(step == 1 ? .skipAll : .skip) }

    static func title(_ step: Int) -> String {
        switch step {
        case 1: return String(localized: "Set Up ARMSX3")
        case 2: return String(localized: "This Device")
        case 3: return String(localized: "JIT")
        case 4: return String(localized: "PS3 Firmware")
        case 5: return String(localized: "Your Games")
        default: return String(localized: "Controller")
        }
    }

    static func symbol(_ step: Int) -> String {
        switch step {
        case 1: return "sparkles"
        case 2: return "checkmark.shield"
        case 3: return "bolt"
        case 4: return "arrow.down.circle"
        case 5: return "folder"
        default: return "gamecontroller"
        }
    }

    static func sentence(_ step: Int) -> String {
        switch step {
        case 1: return String(localized: "Setup checks this device, turns on JIT, installs PS3 firmware and adds your games. Skip any step you don't need, and run setup again from Settings › JIT and Device.")
        case 2: return String(localized: "ARMSX3 reads these from iOS and from how it was signed. A missing entitlement is fixed by signing again, not in the app.")
        case 3: return JitNextStep.severity
        case 4: return String(localized: "Games need PS3 firmware, the file PS3UPDAT.PUP from PlayStation's website.")
        case 5: return String(localized: "Add the folder that holds your PS3 games. They stay where they are and are not copied.")
        default: return String(localized: "Pair a controller in the Settings app under Bluetooth. Without one, games show touch controls.")
        }
    }
}
