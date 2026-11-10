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

    /// The verify window, and how long a StikDebug request counts as recent.
    static let verifyWindowNanoseconds: UInt64 = 3_000_000_000
    static let recentRequestSeconds: TimeInterval = 120

    /// On the model, so a smoke scene can raise it without a tap.
    var confirmingReset = false

    var mounting: Bool {
        if case .busy = diskImage { return true }
        return false
    }

    var liveContainer: Bool { report?.liveContainer ?? false }

    /// Read with the pre-main reader's whitelist.
    var stored: JitMethod { JitMethod.stored(SettingsStore.shared.jitMethod) }

    /// Nil reads "Not set": Built-in stored under LiveContainer.
    var method: JitMethod? { JitMethod.effective(stored: stored, liveContainer: liveContainer) }

    /// Built-in is hidden under LiveContainer.
    var choices: [JitMethod] { JitMethod.allCases.filter { !(liveContainer && $0 == .builtin) } }

    /// A launch-time method that differs from the one this launch read.
    var appliesOnReopen: Bool {
        guard let method, method != .stikdebug else { return false }
        return method != JitMethod.stored(SettingsStore.shared.jitMethodAtLaunch)
    }

    /// This process's own launch verdict, and nothing else.
    var jitOn: Bool { EmulatorSession.shared.jitAvailable == true }

    var situation: JitSituation {
        JitSituation(jitOn: jitOn, entitlementMissing: report?.getTaskAllow == .missing, method: method,
                     pairingImported: pairingImported, diskImage: diskImage,
                     helperWord: EmulatorSession.shared.jitHelperOutcome, stikDebugInstalled: stikDebugInstalled,
                     stikDebugReturnedOff: stikDebugReturnedOff, txm: report?.txm ?? .unknown, liveContainer: liveContainer,
                     mountResult: mountResult, actedThisLaunch: actedThisLaunch, importRefusal: importRefusal,
                     sideStoreAvailable: sideStoreAvailable, isPad: UIDevice.current.userInterfaceIdiom == .pad,
                     sideStoreSentNothing: sideStoreSentNothing, sideStoreLockdown: sideStoreLockdown)
    }

    var step: JitStep { JitNextStep.decide(situation) }

    var codeLine: String {
        JitNextStep.codeLine(reason: EmulatorSession.shared.jitReason, helperWord: EmulatorSession.shared.jitHelperOutcome,
                             mountReason: mountReason)
    }

    func choose(_ method: JitMethod) {
        SettingsStore.shared.jitMethod = method.rawValue
    }

    func refresh() {
        report = DeviceReport(Rpcs3Bridge.deviceChecks())
        refreshPairing()
        if !actedThisLaunch {
            diskImage = DiskImageState.fromHelperOutcome(EmulatorSession.shared.jitHelperOutcome)
        }
        stikDebugInstalled = URL(string: "stikdebug://").map { UIApplication.shared.canOpenURL($0) } ?? false
        sideStoreAvailable = !liveContainer && (URL(string: "sidestore://").map { UIApplication.shared.canOpenURL($0) } ?? false)
        // Once per process, after the launch verdict is known.
        if !launchVerified, EmulatorSession.shared.jitAvailable != nil {
            launchVerified = true
            launchVerify()
        }
    }

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
                                               awaitingSideStore: awaitingSideStore, report: launch)
        pairingRejected = PairingSource.paneRejected(report: launch, fileFound: pairingImported)
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
    func openStikDebug() {
        guard !checking, let url = StikDebugLink.url(bundleID: Bundle.main.bundleIdentifier ?? "", txm: report?.txm ?? .unknown,
                                                     forceScript: SettingsStore.shared.forceJitScript) else { return }
        SettingsStore.shared.stikdebugRequestedAt = Date().timeIntervalSince1970
        openedThisProcess = true
        UIApplication.shared.open(url) { accepted in
            Task { @MainActor in
                if !accepted { self.stikDebugInstalled = false }
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
        guard method == .stikdebug, !jitOn, openedThisProcess, requestIsRecent, !checking else { return }
        checking = true
        Task {
            try? await Task.sleep(nanoseconds: Self.verifyWindowNanoseconds)
            checking = false
            stikDebugReturnedOff = true
        }
    }

    /// A launch with StikDebug chosen and a recent request is the hand-off's answer.
    private func launchVerify() {
        guard method == .stikdebug, requestIsRecent else { return }
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
enum JitDeviceHost { case settings, jitOff, setup }

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
    @State private var store = SettingsStore.shared
    @State private var showingPicker = false
    @State private var pairingRequest: PairingImportRequest?
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
                // An edge-case switch for advanced users; it only adds the script to the StikDebug link.
                if SettingsStore.shared.showAdvancedSettings {
                    OverlaySectionCard {
                        OverlayToggleRow(controllerNavigationID: "settings.jit.forcescript", label: "Force JIT Script",
                                         systemImage: "doc.text", isOn: $store.forceJitScript)
                    }
                }
                if let primary {
                    // The label takes the width, so the fill spans the content column like the cards.
                    Button { act(primary) } label: { Text(primary.title).frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(model.mounting || model.checking)
                        .accessibilityHint(Text(Self.hint(primary)))
                        .controllerAccessibilityActionTarget(id: "settings.jit.next", label: primary.title, action: { act(primary) })
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
                // Not tied to the reset card, which only Built-in outside LiveContainer shows.
                if model.canDeleteRejected {
                    OverlaySectionCard {
                        OverlayActionRow(controllerNavigationID: "settings.jit.deletepairing", label: "Delete Rejected Pairing File",
                                         systemImage: nil, isDestructive: true) {
                            model.confirmingDeletePairing = true
                        }
                    }
                    .padding(.top, 16)
                }
                // Built-in only: the cache is the helper's, and under LiveContainer the helper never runs.
                if model.method == .builtin && !model.liveContainer {
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
        .controllerAccessibilityNavigation(controllerInput: controller.input,
                                           scopeKey: host == .settings ? "settings.pane." + SettingsPane.jitDevice.navigationKey : "jitoff.pane",
                                           onBack: onBack, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: primary?.title ?? JitCard.methodID)
        .onAppear { model.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.sceneBecameActive()
                model.refresh()
            }
        }
    }

    private var primary: JitPrimary? { model.step.primary }

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
        Self.perform(primary, model: model, showingPicker: $showingPicker, pairingRequest: $pairingRequest)
    }

    /// Shared with setup's JIT step: the picker and the pairing import open in the caller's own stack and sheet.
    static func perform(_ primary: JitPrimary, model: JitDeviceModel, showingPicker: Binding<Bool>,
                        pairingRequest: Binding<PairingImportRequest?>) {
        switch primary {
        case .chooseMethod: showingPicker.wrappedValue = true
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
        case .chooseMethod, .importPairingFile: return ""
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

    private func adviceText(_ advice: String) -> String {
        if host == .setup && Self.reopenRows.contains(model.step.row) { return advice + " " + JitNextStep.setupSuffix }
        return advice
    }

    var body: some View {
        OverlaySectionCard(title: String(localized: "JIT")) {
            StatusRow(label: String(localized: "JIT"), value: jitValue, isProblem: !model.jitOn && !model.checking, busy: model.checking,
                      mark: model.checking ? nil : model.jitOn ? .ok : .problem)
            methodRow
            if model.method == .builtin {
                pairingRow
                if let caption = model.pairingCaption {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                StatusRow(label: String(localized: "Developer Disk Image"), value: diskImageValue,
                          isProblem: model.diskImage == .notMounted && !model.jitOn, busy: model.mounting,
                          mark: diskImageMark)
            }
            if model.method == .stikdebug {
                StatusRow(label: String(localized: "StikDebug"),
                          value: model.stikDebugInstalled ? String(localized: "Installed") : String(localized: "Not installed"),
                          isProblem: !model.stikDebugInstalled, mark: model.stikDebugInstalled ? .ok : .problem)
            }
            if let advice = model.step.advice {
                Text(adviceText(advice))
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !model.jitOn {
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

    private var methodValue: String { model.method?.title ?? String(localized: "Not set") }

    @ViewBuilder private var methodRow: some View {
        OverlayActionRow(controllerNavigationID: Self.methodID, label: "Method", systemImage: nil,
                         trailingValue: methodValue, keepsTrailingValue: true, showsChevron: true) {
            showingPicker = true
        }
        .transformEnvironment(\.controllerTextAppearance) { if model.method == nil { $0.secondaryColor = OverlayTheme.warm } }
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
