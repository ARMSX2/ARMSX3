// SPDX-License-Identifier: GPL-2.0-only
// Settings' JIT and Device pane. The pairing file is a secret: the pane checks only that it exists.

import SwiftUI

@MainActor @Observable final class JitDeviceModel {
    static let shared = JitDeviceModel()

    /// Nil when this device's checks could not be read.
    private(set) var report: DeviceReport?
    /// A pairing file is found: ARMSX3's own, or SideStore's read in place.
    private(set) var pairingImported = false
    private(set) var pairingValue = ""
    /// The device turned down the file in use this launch, so the row shows a problem although a file exists.
    private(set) var pairingRejected = false
    private(set) var awaitingSideStore = false
    private(set) var launch: [String: String] = [:]
    private(set) var ownPairingFound = false
    private(set) var replacedCaption: String?
    private(set) var deletedCaption: String?
    private(set) var documentsNote: String?
    static let documentsNoteKey = "armsx3.jit.documentsNoteShown"
    var confirmingDeletePairing = false
    private(set) var importRefusal: String?
    /// An action ran this launch, so the launch helper's word no longer describes the device.
    private(set) var actedThisLaunch = false
    private(set) var diskImage = DiskImageState.unknown
    /// Settable so a smoke scene can draw the installed rows in a Simulator that has no StikDebug.
    var stikDebugInstalled = false
    /// Settable for the same reason. LiveContainer declares the scheme itself, so it never counts as SideStore.
    var sideStoreAvailable = false
    private(set) var sideStoreLockdown = false
    private(set) var sideStoreSentNothing = false
    @ObservationIgnored private var sideStoreRequested = false
    @ObservationIgnored private var leftForSideStore: NSObjectProtocol?
    @ObservationIgnored private var sideStoreLeft = false
    /// The last mount's result word and the helper's path-free reason token; nil until the player mounts.
    private(set) var mountResult: String?
    private(set) var mountReason: String?
    private(set) var resetCaption: String?
    /// The old process came back from StikDebug, so the JIT value reads Checking… for the verify window.
    private(set) var checking = false
    private(set) var stikDebugReturnedOff = false
    /// This process opened StikDebug, so a return to the foreground is that hand-off coming back.
    private(set) var openedThisProcess = false
    @ObservationIgnored private var launchVerified = false
    @ObservationIgnored private var stikDebugRefused = false

    /// The verify window, and how long a StikDebug request counts as recent.
    static let verifyWindowNanoseconds: UInt64 = 3_000_000_000
    static let recentRequestSeconds: TimeInterval = 120

    /// On the model, so a smoke scene can raise it without a tap.
    var confirmingReset = false

    /// Test JIT Setup, kept on the model so it runs on while the player leaves the pane.
    private(set) var testRunning = false
    private(set) var testStage: String?
    private(set) var testResult: JitTestResult?

    var diskImageBusy: Bool {
        if case .busy = diskImage { return true }
        return false
    }

    /// The helper has a request in flight, a mount or a test, so every control that would send another waits.
    var mounting: Bool { diskImageBusy || testRunning }

    /// The test needs the helper, which inside LiveContainer runs only while the hooks are active.
    var canTest: Bool { method == .builtin && toolsAvailable }

    var liveContainer: Bool { report?.liveContainer ?? false }

    /// Read with the pre-main reader's whitelist.
    var stored: JitMethod { JitMethod.stored(SettingsStore.shared.jitMethod) }

    var method: JitMethod { Self.route(stored) }

    private static func route(_ method: JitMethod) -> JitMethod { method == .stikdebug ? .builtin : method }

    var choices: [JitMethod] { SettingsStore.shared.showAdvancedSettings ? [.builtin, .wait] : [.builtin] }

    /// A player who stored Wait can always get back to Built-in.
    var showsMethodRow: Bool { SettingsStore.shared.showAdvancedSettings || stored == .wait }

    /// A launch-time method that differs from the one this launch read.
    var appliesOnReopen: Bool {
        method != Self.route(JitMethod.stored(SettingsStore.shared.jitMethodAtLaunch))
    }

    var probe: JitFallback.Probe { LiveContainerProbe.current }

    /// Inside LiveContainer the helper runs only while LiveContainer's hooks are active.
    var toolsAvailable: Bool { !liveContainer || probe.hooksActive == true }

    /// The launch helper turned JIT on, as opposed to a debugger that was already attached.
    var jitOnThroughBuiltin: Bool { jitOn && EmulatorSession.shared.jitReason == "helper-ok" }

    /// This process's own launch verdict, and nothing else.
    var jitOn: Bool { EmulatorSession.shared.jitAvailable == true }

    var situation: JitSituation {
        JitSituation(jitOn: jitOn, entitlementMissing: report?.getTaskAllow == .missing, method: method,
                     pairingImported: pairingImported, diskImage: diskImage,
                     helperWord: EmulatorSession.shared.jitHelperOutcome, stikDebugInstalled: stikDebugInstalled,
                     stikDebugReturnedOff: stikDebugReturnedOff, txm: report?.txm ?? .unknown, liveContainer: liveContainer,
                     mountResult: mountResult, actedThisLaunch: actedThisLaunch, importRefusal: importRefusal,
                     sideStoreAvailable: sideStoreAvailable, isPad: UIDevice.current.userInterfaceIdiom == .pad,
                     sideStoreSentNothing: sideStoreSentNothing, sideStoreLockdown: sideStoreLockdown,
                     checklistFails: !checklist.isEmpty, liveProcessMissing: liveContainer && probe.liveProcessPresent == false,
                     liveContainerOld: liveContainerOldVersion != nil, fallbackAdvice: fallbackAdvice, testResult: testResult)
    }

    var step: JitStep { JitNextStep.decide(situation) }

    /// The LiveContainer rows, only for a player who relies on built-in JIT.
    var checklist: [JitFallback.ChecklistRow] {
        method == .builtin ? JitFallback.checklist(probe: probe, jitOnThroughBuiltin: jitOnThroughBuiltin) : []
    }

    /// The installed LiveContainer version when built-in JIT needs a newer one; nil when it is unknown or new enough.
    var liveContainerOldVersion: String? {
        guard method == .builtin, probe.insideLiveContainer, let version = probe.version,
              JitFallback.versionAtLeast(version, JitFallback.minimumLiveContainer) == false else { return nil }
        return version
    }

    /// With JIT on through StikDebug or an already traced launch, the notice body for the cause that holds.
    var fallbackAdvice: String? {
        guard jitOn else { return nil }
        let session = EmulatorSession.shared
        let verdict = JitFallback.decide(jitOn: jitOn, jitReason: session.jitReason, helperWord: session.jitHelperOutcome,
                                         methodAtLaunch: SettingsStore.shared.jitMethodAtLaunch,
                                         storedCause: JitFallback.storedCause(in: .standard)?.rawValue, probe: probe)
        return verdict.cause.map {
            JitFallback.noticeBody(cause: $0, insideLiveContainer: probe.insideLiveContainer, version: probe.version,
                                   supportEnds: JitFallback.supportEndsAnnounced)
        }
    }

    var codeLine: String {
        JitNextStep.codeLine(reason: EmulatorSession.shared.jitReason, helperWord: EmulatorSession.shared.jitHelperOutcome,
                             mountReason: mountReason)
    }

    func choose(_ method: JitMethod) {
        SettingsStore.shared.jitMethod = method.rawValue
    }

    func refresh() {
        report = DeviceReport(Rpcs3Bridge.deviceChecks())
        // These read report, so they are computed before report is modified.
        let version = liveContainer ? probe.version : nil
        let fromOtherInstall = liveContainer && jitOnThroughBuiltin && probe.hooksActive == false
        report?.liveContainerVersion = version
        report?.helperFromOtherInstall = fromOtherInstall
        refreshPairing()
        // A test result is newer than the launch helper's word.
        if !actedThisLaunch && testResult == nil {
            diskImage = DiskImageState.fromHelperOutcome(EmulatorSession.shared.jitHelperOutcome)
        }
        // LiveContainer may not pass the query on, so inside it StikDebug counts as installed until iOS refuses a link.
        stikDebugInstalled = liveContainer ? !stikDebugRefused : URL(string: "stikdebug://").map { UIApplication.shared.canOpenURL($0) } ?? false
        sideStoreAvailable = !liveContainer && (URL(string: "sidestore://").map { UIApplication.shared.canOpenURL($0) } ?? false)
        // Once per process, after the launch verdict is known.
        if !launchVerified, EmulatorSession.shared.jitAvailable != nil {
            launchVerified = true
            launchVerify()
        }
    }

    /// The test found the device turned the file down, and no import has replaced it since.
    private var testStoppedAtPairing: Bool { testResult?.stoppedStage == .pairing && !actedThisLaunch }

    /// Reads only existence, the origin word and file dates; SideStore's file is opened in memory for its shape.
    private func refreshPairing() {
        let own = PairingImport.destination
        let ownFound = own.map(PairingImport.isNonEmptyRegularFile) ?? false
        let sideStore = getenv("LC_HOME_PATH").flatMap { PairingSource.sideStoreFile(liveContainerHome: String(cString: $0)) { try? Data(contentsOf: $0) } }
        let origin = PairingSource.origin(own.flatMap { try? String(contentsOf: $0.deletingLastPathComponent().appendingPathComponent(PairingSource.originName), encoding: .utf8) })
        launch = Rpcs3Bridge.jitPairingReport()
        ownPairingFound = ownFound
        noteDocumentsFile(launch["documents"])
        awaitingSideStore = PairingLink.isAwaiting(at: Date(), in: .standard)
        pairingImported = ownFound || sideStore != nil
        pairingValue = PairingSource.paneValue(ownFileFound: ownFound, sideStoreInPlace: sideStore != nil, origin: origin,
                                               awaitingSideStore: awaitingSideStore, report: launch,
                                               testStoppedAtPairing: testStoppedAtPairing)
        pairingRejected = PairingSource.paneRejected(report: launch, fileFound: pairingImported,
                                                     testStoppedAtPairing: testStoppedAtPairing)
        let used = launch["used"] ?? ""
        let inUse = used.hasPrefix("sidestore") || (used.isEmpty && !ownFound) ? sideStore : own
        let attributes = inUse.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path) }
        let date = (attributes?[.creationDate] ?? attributes?[.modificationDate]) as? Date
        report?.pairingRow = PairingSource.deviceRow(report: launch, ownFileFound: ownFound || sideStore != nil, origin: origin,
                                                     fileDate: date, now: Date(), locale: .current, calendar: .current)
    }

    /// A Documents file that failed the check is said once; an empty folder lets a later bad file say it again.
    private func noteDocumentsFile(_ word: String?) {
        let defaults = UserDefaults.standard
        switch word {
        case "invalid"?, "lockdown"?:
            guard !defaults.bool(forKey: Self.documentsNoteKey) else { return }
            defaults.set(true, forKey: Self.documentsNoteKey)
            documentsNote = String(localized: "The pairingFile.plist in ARMSX3's Documents folder isn't a Remote Pairing file, so ARMSX3 didn't use it.")
        case ""?:
            defaults.removeObject(forKey: Self.documentsNoteKey)
        default:
            break
        }
    }

    var usedSourceWord: String {
        PairingSource.sourceWord(used: launch["used"] ?? "", origin: .imported)
    }

    /// Only ARMSX3's own file, and only when another source worked, so the player is never left without one.
    var canDeleteRejected: Bool {
        ownPairingFound && launch["rejected"] == "own" && launch["result"] == "accepted"
    }

    func deleteRejectedPairing() {
        guard canDeleteRejected, let file = PairingImport.destination else { return }
        let origin = file.deletingLastPathComponent().appendingPathComponent(PairingSource.originName, isDirectory: false)
        do {
            try FileManager.default.removeItem(at: file)
            try? FileManager.default.removeItem(at: origin)
            deletedCaption = String(localized: "Deleted.")
        } catch {
            deletedCaption = String(localized: "Couldn't delete the file. Try again.")
        }
        replacedCaption = nil
        refresh()
    }

    private var requestIsRecent: Bool {
        let requested = SettingsStore.shared.stikdebugRequestedAt
        let age = Date().timeIntervalSince1970 - requested
        return requested > 0 && age >= 0 && age < Self.recentRequestSeconds
    }

    /// The request time is stored first, because StikDebug kills this process and the new launch reads it.
    /// Inside LiveContainer the link names LiveContainer and its selection is written first, so the relaunch lands in ARMSX3.
    func openStikDebug() {
        guard !checking, let url = StikDebugLink.url(bundleID: LiveContainerProbe.stikDebugBundleID, txm: report?.txm ?? .unknown) else { return }
        if liveContainer { LiveContainerProbe.selectForRelaunch() }
        SettingsStore.shared.stikdebugRequestedAt = Date().timeIntervalSince1970
        openedThisProcess = true
        UIApplication.shared.open(url) { accepted in
            Task { @MainActor in
                if !accepted {
                    self.stikDebugRefused = true
                    self.stikDebugInstalled = false
                }
            }
        }
    }

    /// The request time is stored first, so the root accepts SideStore's link only when ARMSX3 asked for it.
    func getFromSideStore() {
        sideStoreLockdown = false
        sideStoreSentNothing = false
        PairingLink.markRequested(at: Date(), in: .standard)
        sideStoreRequested = true
        // A system prompt can dismiss without ARMSX3 leaving, and that is not a return.
        leftForSideStore.map(NotificationCenter.default.removeObserver)
        leftForSideStore = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                                                  queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.sideStoreLeft = true }
        }
        sideStoreLeft = false
        refresh()
        UIApplication.shared.open(PairingLink.requestURL) { accepted in
            Task { @MainActor in
                if !accepted {
                    self.sideStoreRequested = false
                    UserDefaults.standard.removeObject(forKey: PairingLink.requestedAtKey)
                    self.sideStoreAvailable = false
                    self.refresh()
                }
            }
        }
    }

    /// After a short settle, takes what the root made of SideStore's link; nothing at all means SideStore sent none.
    private func sideStoreReturned() {
        guard sideStoreRequested, sideStoreLeft else { return }
        sideStoreRequested = false
        leftForSideStore.map(NotificationCenter.default.removeObserver)
        leftForSideStore = nil
        Task {
            try? await Task.sleep(nanoseconds: Self.verifyWindowNanoseconds)
            let answer = PairingLink.lastAnswer
            PairingLink.lastAnswer = nil
            switch answer {
            case .added?, .replaced?:
                actedThisLaunch = true
                AccessibilityNotification.Announcement(answer == .added ? String(localized: "Pairing file added.")
                                                                         : String(localized: "Pairing file replaced.")).post()
            case .lockdown?:
                sideStoreLockdown = true
            case nil:
                sideStoreSentNothing = true
                UserDefaults.standard.removeObject(forKey: PairingLink.requestedAtKey)
            case .invalid?, .failed?, .ignored?:
                break
            }
            refresh()
        }
    }

    /// This process cannot turn JIT on, so the Checking… window only keeps the answer from flashing.
    func sceneBecameActive() {
        sideStoreReturned()
        guard !jitOn, openedThisProcess, requestIsRecent, !checking else { return }
        checking = true
        Task {
            try? await Task.sleep(nanoseconds: Self.verifyWindowNanoseconds)
            checking = false
            stikDebugReturnedOff = true
        }
    }

    /// A launch with a recent StikDebug request is the hand-off's answer.
    private func launchVerify() {
        guard requestIsRecent else { return }
        if jitOn {
            SettingsStore.shared.stikdebugRequestedAt = 0
        } else {
            stikDebugReturnedOff = true
        }
    }

    /// The state lives on the model, so leaving the pane does not cancel the mount.
    @discardableResult func mount() async -> String {
        if mounting { return "busy" }
        actedThisLaunch = true
        mountResult = nil
        mountReason = nil
        diskImage = .busy("connecting")
        let (result, reason) = await withCheckedContinuation { (continuation: CheckedContinuation<(String, String), Never>) in
            Rpcs3Bridge.mountDiskImage(stage: { stage in
                // The façade calls both blocks on the main queue.
                MainActor.assumeIsolated { if self.mounting { self.diskImage = .busy(stage) } }
            }, completion: { result, reason in
                continuation.resume(returning: (result, reason))
            })
        }
        mountResult = result
        mountReason = reason
        diskImage = result == "ready" ? .mounted : .notMounted
        // The witness carries a known word only, never the reason token.
        let status = ["ready", "unreachable", "download", "pairing", "mount"].contains(result) ? result : "other"
        // Announced once, for the result; the stages are not announced.
        if let sentence = JitNextStep.mountSentence(status) {
            AccessibilityNotification.Announcement(sentence).post()
        }
        return result
    }

    /// Check only: it never downloads or mounts. The stage words move the row; VoiceOver hears the result once.
    func runTest() async {
        guard canTest, !mounting else { return }
        testRunning = true
        testStage = nil
        let (result, reason) = await withCheckedContinuation { (continuation: CheckedContinuation<(String, String), Never>) in
            Rpcs3Bridge.testJitSetup(stage: { stage in
                // The façade calls both blocks on the main queue.
                MainActor.assumeIsolated { if stage == "connecting" || stage == "pairing" { self.testStage = stage } }
            }, completion: { result, reason in
                continuation.resume(returning: (result, reason))
            })
        }
        let found = JitTestResult(result: result, reason: reason)
        testResult = found
        testRunning = false
        refreshPairing()
        // The check read the image, so the disk image row can say so.
        if found == .passed { diskImage = .mounted } else if found.stoppedStage == .diskImage { diskImage = .notMounted }
        let sentence = found == .passed && !jitOn
            ? String(localized: "Built-in JIT is ready. Close ARMSX3 and open it again.")
            : found.passedDetail ?? [found.value, found.fix(liveContainer: liveContainer, tooOld: liveContainerOldVersion)].compactMap { $0 }.joined(separator: ". ")
        AccessibilityNotification.Announcement(sentence).post()
    }

    /// Deletes only the downloaded copy; the row keeps its value because a mounted image stays mounted until a restart.
    @discardableResult func reset() async -> String {
        if mounting { return "busy" }
        let (result, _) = await withCheckedContinuation { (continuation: CheckedContinuation<(String, String), Never>) in
            Rpcs3Bridge.resetDiskImage { result, reason in continuation.resume(returning: (result, reason)) }
        }
        let status = result == "deleted" ? "deleted" : "failed"
        resetCaption = status == "deleted" ? String(localized: "Deleted. The next mount downloads it again.")
                                           : String(localized: "Couldn't delete the downloaded copy. Try again.")
        return result
    }

    /// A refused import keeps the previous file and says why in the advice slot.
    func importFinished(_ outcome: PairingImport.Outcome) {
        if outcome == .imported {
            let replaced = ownPairingFound
            importRefusal = nil
            deletedCaption = nil
            actedThisLaunch = true
            replacedCaption = replaced && jitOn ? String(localized: "Replaced. ARMSX3 uses the new file the next time it opens.") : nil
            refresh()
        } else {
            replacedCaption = nil
            importRefusal = JitNextStep.importRefusalSentence(outcome.rawValue)
        }
    }

    /// Under the Pairing File row. With JIT on there is no advice line, so a refusal shows here.
    var pairingCaption: String? {
        deletedCaption ?? replacedCaption ?? (jitOn ? importRefusal : nil) ?? documentsNote
    }
}

// Not a controller target, because it does nothing when activated.
struct StatusRow: View {
    let label: String
    let value: String
    var isProblem = false
    var fix: String? = nil
    var busy = false
    /// A mark in place of a yes-or-no value; VoiceOver still reads the word.
    var mark: StatusMark? = nil
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 4) {
                        name.fixedSize(horizontal: false, vertical: true)
                        valueText.fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                } else {
                    name.layoutPriority(1)
                    Spacer(minLength: 0)
                    valueText.lineLimit(1)
                }
            }
            .frame(minHeight: 44)
            if let fix {
                Text(fix)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text(fix ?? ""))
    }

    private var name: some View {
        Text(label).foregroundStyle(OverlayTheme.textPrimary)
    }

    private var valueText: some View {
        HStack(spacing: 4) {
            if busy {
                ProgressView().controlSize(.small).accessibilityHidden(true)
            }
            if let mark, !busy {
                mark.image
                    .font(.title3)
                    .accessibilityLabel(Text(value))
            } else {
                Text(value)
                    .foregroundStyle(isProblem ? OverlayTheme.warm : OverlayTheme.textSecondary)
                    .monospacedDigit()
            }
        }
    }
}

// A problem value in the warm colour, on a row whose trailing value the row itself draws.
private struct WarmValue: ViewModifier {
    let isOn: Bool
    @Environment(\.controllerTextAppearance) private var appearance

    func body(content: Content) -> some View {
        var warm = appearance
        if isOn { warm.secondaryColor = OverlayTheme.warm }
        return content.environment(\.controllerTextAppearance, warm)
    }
}

// The symbol's shape repeats the colour's state for anyone who cannot tell green from red.
enum StatusMark {
    case ok, problem, unknown

    init(_ presence: Presence) {
        switch presence {
        case .present: self = .ok
        case .missing: self = .problem
        case .unknown: self = .unknown
        }
    }

    var image: some View {
        switch self {
        case .ok: return Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.green)
        case .problem: return Image(systemName: "xmark.circle.fill").foregroundStyle(Color.red)
        case .unknown: return Image(systemName: "questionmark.circle.fill").foregroundStyle(OverlayTheme.textSecondary)
        }
    }
}

// `setup` hosts only the JIT card, never the whole pane.
enum JitDeviceHost { case settings, jitOff, setup, inGame }

// No Settings tab around it, because the library cannot start with JIT off.
struct JitOffScreen: View {
    let reason: String
    let helperOutcome: String
    let controllerInput: MenuControllerInputRouter

    var body: some View {
        NavigationStack {
            JitDevicePane(host: .jitOff, controller: SettingsControllerContext(input: controllerInput), onBack: { false })
        }
        .preferredColorScheme(.dark)
        .onAppear {
            // RootView never appears on this route, so the screen starts the root's router itself.
            controllerInput.start()
            controllerInput.setMenuActive(true)
        }
    }
}

struct PairingImportRequest: Identifiable {
    let id = UUID()
}

struct JitDevicePane: View {
    let host: JitDeviceHost
    var controller = SettingsControllerContext()
    let onBack: @MainActor () -> Bool
    @State private var model = JitDeviceModel.shared
    @State private var showingPicker = false
    @State private var pairingRequest: PairingImportRequest?
    @State private var report: ReportRequest?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        SettingsPage {
            if host == .jitOff {
                Text(JitNextStep.severity)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if model.report != nil {
                JitCard(model: model, host: host, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
                if let primary {
                    // The label takes the width, so the fill spans the content column like the cards.
                    Button { act(primary) } label: { Text(primary.title).frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(model.mounting || model.checking || mountBlockedInGame)
                        .accessibilityHint(Text(Self.hint(primary)))
                        .controllerAccessibilityActionTarget(id: "settings.jit.next", label: primary.title, action: { act(primary) })
                    if mountBlockedInGame {
                        Text("Stop the game first.")
                            .font(.subheadline)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if !model.pairingImported, primary == .getFromSideStore || primary == .importPairingFile {
                        otherWays(primary)
                    }
                }
                DeviceCard(model: model)
                // Not confirmed: it only asks the root to open setup and changes nothing itself.
                OverlaySectionCard {
                    OverlayActionRow(controllerNavigationID: "settings.jit.setup", label: "Run Setup Again", systemImage: nil) {
                        NotificationCenter.default.post(name: SetupFlow.runAgainNotification, object: nil)
                    }
                }
                // Not tied to the reset card, which only Built-in with its tools available shows.
                if model.canDeleteRejected {
                    OverlaySectionCard {
                        OverlayActionRow(controllerNavigationID: "settings.jit.deletepairing", label: "Delete Rejected Pairing File",
                                         systemImage: nil, isDestructive: true) {
                            model.confirmingDeletePairing = true
                        }
                    }
                    .padding(.top, 16)
                }
                // Built-in only: the cache is the helper's, and under LiveContainer it exists once the hooks are active.
                if model.method == .builtin && model.toolsAvailable {
                    OverlaySectionCard {
                        OverlayActionRow(controllerNavigationID: "settings.jit.resetddi", label: "Reset Developer Disk Image",
                                         systemImage: nil, isDestructive: true) {
                            if !model.mounting { model.confirmingReset = true }
                        }
                        .disabled(model.mounting)
                        if let caption = model.resetCaption {
                            Text(caption)
                                .font(.subheadline)
                                .foregroundStyle(OverlayTheme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.top, 16)
                }
            } else {
                SettingsMessage(heading: "Couldn't read this device's status",
                                message: "Close Settings and open it again. If it keeps happening, restart ARMSX3.")
            }
        }
        .navigationTitle(SettingsPane.jitDevice.title)
        .navigationBarTitleDisplayMode(.inline)
        .jitActionPresenters(model: model, controller: controller, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
        .alert("Delete the rejected pairing file?", isPresented: $model.confirmingDeletePairing) {
            Button("Delete File", role: .destructive) { model.deleteRejectedPairing() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This device no longer accepts it. ARMSX3 keeps using \(model.usedSourceWord).")
        }
        .alert("Reset the Developer Disk Image?", isPresented: $model.confirmingReset) {
            Button("Reset Disk Image", role: .destructive) { Task { await model.reset() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("ARMSX3 deletes its downloaded copy. The next mount downloads it again, about 16 MB. A disk image that is mounted now stays mounted until this device restarts.")
        }
        // Here rather than on JitOffScreen so the button joins this pane's controller scope.
        .toolbar {
            if host == .jitOff && DiagnosticsSession.shared.current != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Report") { openReport() }
                        .controllerAccessibilityActionTarget(id: "jitoff.report", label: String(localized: "Report")) { openReport() }
                }
            }
        }
        .sheet(item: $report) { ProblemReportView(session: $0.session, serial: $0.serial) }
        .controllerAccessibilityNavigation(controllerInput: controller.input,
                                           scopeKey: host == .settings ? "settings.pane." + SettingsPane.jitDevice.navigationKey : host == .inGame ? "game.jitdevice" : "jitoff.pane",
                                           onBack: onBack, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: primary?.title ?? (model.showsMethodRow ? JitCard.methodID : nil))
        .onAppear { model.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.sceneBecameActive()
                model.refresh()
            }
        }
    }

    private var primary: JitPrimary? { model.step.primary }

    private func openReport() {
        report = DiagnosticsSession.shared.current.map { ReportRequest(session: $0, serial: nil) }
    }

    private var mountBlockedInGame: Bool { host == .inGame && primary == .mountDiskImage }

    // The sources less likely to work, in one quiet card; with SideStore the picker moves down here from the primary.
    private func otherWays(_ primary: JitPrimary) -> some View {
        OverlaySectionCard(title: String(localized: "Other Ways")) {
            if primary == .getFromSideStore {
                OverlayActionRow(controllerNavigationID: "settings.jit.other.import", label: "Import Pairing File", systemImage: nil) {
                    pairingRequest = PairingImportRequest()
                }
            }
            ForEach(JitNextStep.otherWays(liveContainer: model.liveContainer, sideStoreAvailable: model.sideStoreAvailable), id: \.self) { line in
                Text(line)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 16)
    }

    private func act(_ primary: JitPrimary) {
        guard !mountBlockedInGame else { return }
        Self.perform(primary, model: model, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
    }

    /// Shared with setup's JIT step: the picker and the pairing import open in the caller's own stack and sheet.
    static func perform(_ primary: JitPrimary, model: JitDeviceModel, showingPicker: Binding<Bool>,
                        pairingRequest: Binding<PairingImportRequest?>) {
        switch primary {
        case .tryAgain:
            model.refresh()
            if model.canTest { Task { await model.runTest() } }
        case .importPairingFile: pairingRequest.wrappedValue = PairingImportRequest()
        case .getFromSideStore: model.getFromSideStore()
        case .mountDiskImage: Task { await model.mount() }
        case .openStikDebug: model.openStikDebug()
        }
    }

    static func hint(_ primary: JitPrimary) -> String {
        switch primary {
        case .mountDiskImage: return String(localized: "Downloads and mounts the disk image.")
        case .openStikDebug: return String(localized: "Opens StikDebug, which turns on JIT and returns you here.")
        case .getFromSideStore: return String(localized: "Opens SideStore, which sends the pairing file back.")
        case .tryAgain: return String(localized: "Checks the setup again.")
        case .importPairingFile: return ""
        }
    }

    // A word the helper adds later reads Connecting…, the first stage, rather than a raw token.
    static func stageWord(_ stage: String) -> String {
        switch stage {
        case "downloading": return String(localized: "Downloading…")
        case "mounting": return String(localized: "Mounting…")
        case "checking": return String(localized: "Checking…")
        default: return String(localized: "Connecting…")
        }
    }
}

// Each Built-in or StikDebug row shows only for that method; setup's JIT step draws this card too.
struct JitCard: View {
    let model: JitDeviceModel
    let host: JitDeviceHost
    @Binding var showingPicker: Bool
    @Binding var pairingRequest: PairingImportRequest?

    static let methodID = "settings.jit.method"
    /// Rows whose next step is a reopen: in setup the advice says setup continues here.
    static let reopenRows: Set<Int> = [6, 7, 11]
    /// JIT is on through the StikDebug fallback or an already traced launch.
    static let fallbackRow = 12

    private static func checklistValue(_ presence: Presence) -> String {
        switch presence {
        case .present: return String(localized: "Yes")
        case .missing: return String(localized: "No")
        case .unknown: return String(localized: "Unknown")
        }
    }

    private func adviceText(_ advice: String) -> String {
        if host == .setup && Self.reopenRows.contains(model.step.row) { return advice + " " + JitNextStep.setupSuffix }
        return advice
    }

    var body: some View {
        OverlaySectionCard(title: String(localized: "JIT")) {
            StatusRow(label: String(localized: "JIT"), value: jitValue, isProblem: !model.jitOn && !model.checking, busy: model.checking,
                      mark: model.checking ? nil : model.jitOn ? .ok : .problem)
            if model.showsMethodRow { methodRow }
            ForEach(model.checklist, id: \.label) { row in
                StatusRow(label: row.label, value: Self.checklistValue(row.presence), isProblem: row.presence == .missing,
                          fix: row.fix, mark: StatusMark(row.presence))
            }
            if model.method == .builtin {
                pairingRow
                if let caption = model.pairingCaption {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if model.toolsAvailable {
                    StatusRow(label: String(localized: "Developer Disk Image"), value: diskImageValue,
                              isProblem: model.diskImage == .notMounted && !model.jitOn,
                              fix: JitNextStep.diskImageFix(jitOn: model.jitOn, diskImage: model.diskImage, testRowShown: testRowShown),
                              busy: model.diskImageBusy, mark: diskImageMark)
                }
                if testRowShown { testRow }
            }
            if let advice = model.step.advice {
                Text(adviceText(advice))
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !model.jitOn || model.step.row == Self.fallbackRow {
                Text(model.codeLine)
                    .font(.caption.monospaced())
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel(Text("Code"))
                    .accessibilityValue(Text(model.codeLine))
            }
        }
    }

    private var jitValue: String {
        if model.checking { return String(localized: "Checking…") }
        return model.jitOn ? String(localized: "On") : String(localized: "Off")
    }

    private var methodValue: String { model.method.title }

    @ViewBuilder private var methodRow: some View {
        OverlayActionRow(controllerNavigationID: Self.methodID, label: "Method", systemImage: nil,
                         trailingValue: methodValue, keepsTrailingValue: true, showsChevron: true) {
            showingPicker = true
        }
        .accessibilityLabel(Text("JIT Method"))
        .accessibilityValue(Text(methodValue))
        .accessibilityHint(Text("Opens the list of JIT methods."))
        if model.appliesOnReopen {
            HStack(spacing: 4) {
                Image(systemName: "arrow.clockwise")
                Text("Applies when ARMSX3 reopens")
            }
            .font(.subheadline)
            .foregroundStyle(OverlayTheme.warm)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var pairingRow: some View {
        OverlayActionRow(controllerNavigationID: "settings.jit.pairing", label: "Pairing File", systemImage: nil,
                         trailingValue: model.pairingValue, keepsTrailingValue: true) {
            pairingRequest = PairingImportRequest()
        }
        // Leaves the mark's column free so the value does not sit under it.
        .padding(.trailing, 32)
        .overlay(alignment: .trailing) {
            Group {
                if model.awaitingSideStore {
                    ProgressView().controlSize(.small)
                } else {
                    (model.pairingImported && !model.pairingRejected ? StatusMark.ok : StatusMark.problem).image.font(.title3)
                }
            }
            .accessibilityHidden(true)
        }
        .accessibilityLabel(Text("Pairing File"))
        .accessibilityValue(Text(model.pairingValue))
        .accessibilityHint(Text("Imports a pairing file from Files."))
    }

    private var testValue: String? {
        if model.testRunning { return JitNextStep.testStageValue(model.testStage) }
        return model.testResult?.value
    }

    /// The JIT-off screen and setup start the test from Try Again, so their row only reports it, and only once it has run.
    private var testRowReadOnly: Bool { host == .jitOff || host == .setup }

    private var testRowShown: Bool {
        model.method == .builtin && model.canTest && (!testRowReadOnly || model.testRunning || model.testResult != nil)
    }

    private var testDetail: String? {
        guard !model.testRunning, let result = model.testResult else { return nil }
        return result.fix(liveContainer: model.liveContainer, tooOld: model.liveContainerOldVersion) ?? result.passedDetail
    }

    @ViewBuilder private var testRow: some View {
        if testRowReadOnly {
            StatusRow(label: String(localized: "Test JIT Setup"), value: testValue ?? "",
                      isProblem: !model.testRunning && model.testResult?.stoppedStage != nil, fix: testDetail, busy: model.testRunning,
                      mark: model.testResult == .passed ? .ok : nil)
        } else {
            actionTestRow
        }
    }

    @ViewBuilder private var actionTestRow: some View {
        OverlayActionRow(controllerNavigationID: "settings.jit.test", label: "Test JIT Setup", systemImage: nil,
                         trailingValue: model.testResult == .passed && !model.testRunning ? nil : testValue, keepsTrailingValue: true) {
            Task { await model.runTest() }
        }
        .modifier(WarmValue(isOn: !model.testRunning && model.testResult?.stoppedStage != nil))
        .disabled(model.mounting || host == .inGame)
        // A tick stands in for the word Passed; the column is kept free so a value does not sit under it.
        .padding(.trailing, 32)
        .overlay(alignment: .trailing) {
            Group {
                if model.testRunning {
                    ProgressView().controlSize(.small)
                } else if model.testResult == .passed {
                    StatusMark.ok.image.font(.title3)
                }
            }
            .accessibilityHidden(true)
        }
        .accessibilityLabel(Text("Test JIT Setup"))
        .accessibilityValue(Text(testValue ?? ""))
        .accessibilityHint(Text("Checks the helper, LocalDevVPN, the pairing file and the disk image."))
        if host == .inGame {
            Text("Stop the game first.")
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        if let detail = testDetail {
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var diskImageMark: StatusMark? {
        switch model.diskImage {
        case .mounted: return .ok
        case .notMounted: return .problem
        case .unknown: return .unknown
        case .busy: return nil
        }
    }

    private var diskImageValue: String {
        switch model.diskImage {
        case .mounted: return String(localized: "Mounted")
        case .notMounted: return String(localized: "Not mounted")
        case .unknown: return String(localized: "Unknown")
        case .busy(let stage): return JitDevicePane.stageWord(stage)
        }
    }

}

// Nothing is drawn when the checks could not be read; setup's device step draws this card too.
struct DeviceCard: View {
    let model: JitDeviceModel

    var body: some View {
        if let report = model.report {
            OverlaySectionCard(title: String(localized: "Device")) {
                ForEach(report.rows, id: \.label) { row in
                    StatusRow(label: row.label, value: row.value, isProblem: row.isProblem, fix: row.fix,
                              mark: row.presence.map(StatusMark.init))
                }
            }
        }
    }
}

extension View {
    /// The method picker and the pairing import sheet, for the pane and for setup's JIT step.
    func jitActionPresenters(model: JitDeviceModel, controller: SettingsControllerContext, showingPicker: Binding<Bool>,
                             pairingRequest: Binding<PairingImportRequest?>) -> some View {
        navigationDestination(isPresented: showingPicker) {
            JitMethodPicker(model: model, controller: controller, onDone: { showingPicker.wrappedValue = false })
        }
        .sheet(item: pairingRequest) { _ in
            PairingImport.Picker { outcome in
                pairingRequest.wrappedValue = nil
                if let outcome { model.importFinished(outcome) }
            }
        }
    }
}
