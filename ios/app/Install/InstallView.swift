// SPDX-License-Identifier: GPL-2.0-only
// The Install screen. Picked files are read where they sit and deleted only from the prompt's Delete button.

import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct InstallView: View {
    // False when the view is a tab of RootView rather than a sheet: a tab has nothing to dismiss to.
    let showsDone: Bool
    let onInstalled: () async -> Void
    let controllerInput: MenuControllerInputRouter?
    let onPreviousTab: (@MainActor () -> Bool)?
    let onNextTab: (@MainActor () -> Bool)?
    let onBoundary: (@MainActor (MenuControllerCommand) -> Bool)?

    @Environment(\.dismiss) private var dismiss
    @State private var model: InstallModel
    @State private var picking = false
    @State private var uninstalling: InstalledTitle?
    @State private var side: InstallSide
    @State private var updateSheet: UpdateSheet?

    // Optional rather than a default argument: a default is evaluated outside the main actor.
    @MainActor init(showsDone: Bool = true, model: InstallModel? = nil, library: LibraryModel? = nil,
                    controllerInput: MenuControllerInputRouter? = nil, onPreviousTab: (@MainActor () -> Bool)? = nil,
                    onNextTab: (@MainActor () -> Bool)? = nil, onBoundary: (@MainActor (MenuControllerCommand) -> Bool)? = nil,
                    onInstalled: @escaping () async -> Void = {}) {
        self.showsDone = showsDone
        self.onInstalled = onInstalled
        self.controllerInput = controllerInput
        self.onPreviousTab = onPreviousTab
        self.onNextTab = onNextTab
        self.onBoundary = onBoundary
        let model = model ?? InstallModel()
        if let library { model.library = library }
        _model = State(initialValue: model)
        // Opens on Updates while a job runs, a failure is unseen, or the pill asked.
        let store = UpdateStore.shared
        let running = store.jobs.values.contains { !$0.isFinished && !$0.isStopped }
        _side = State(initialValue: running || store.unseenFailure || store.openOnUpdates ? .updates : .install)
    }

    var body: some View {
        NavigationStack {
            Group {
                if side == .updates {
                    page { UpdatesSide(sheet: $updateSheet) }
                        .refreshable { await refreshUpdates() }
                } else {
                    page {
                        FirmwareStatusCard(version: model.version)
                            .controllerAccessibilityActionTarget(id: "install.firmware", label: String(localized: "Firmware")) {}
                            .padding(.horizontal)
                        if let message = model.message {
                            Text(message)
                                .foregroundStyle(OverlayTheme.textSecondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }
                        Button { picking = true } label: { Text("Install…").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .padding(.horizontal)
                            .disabled(model.busy)
                            .controllerAccessibilityActionTarget(id: "install.pick", label: String(localized: "Install…")) {
                                if !model.busy { picking = true }
                            }
                        Text("Firmware (PS3UPDAT.PUP), games (.pkg) and licences (.rap, .edat). Games stay where they are.")
                            .font(.footnote)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        ForEach(model.rows) { row in
                            rowView(row)
                        }
                        if !model.installed.isEmpty {
                            Text("Installed")
                                .overlayCaption()
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal)
                            ForEach(model.installed) { title in
                                installedRow(title)
                            }
                        }
                    }
                }
            }
            .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: "menu.bios.install",
                                               priority: showsDone ? 200 : 100, onBack: back, onPreviousTab: onPreviousTab,
                                               onNextTab: onNextTab, onBoundary: onBoundary,
                                               preferredInitialFocusLabel: "install.switch",
                                               preferredLastEntryLabel: "install.switch")
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Install")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if showsDone {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { dismiss() }.disabled(model.busy)
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
        .interactiveDismissDisabled(model.busy)
        .task {
            model.noteInterruptedInstall()
            await model.refreshVersion()
            await model.rescanInstalled()
        }
        .onChange(of: UpdateStore.shared.contentRevision) { Task { await model.rescanInstalled() } }
        // The pill's ask is taken here, not in init, which can run again without the state it sets.
        .onAppear {
            if UpdateStore.shared.openOnUpdates {
                side = .updates
                UpdateStore.shared.openOnUpdates = false
            }
        }
        .sheet(isPresented: $picking) {
            InstallPicker { urls in
                picking = false
                guard !urls.isEmpty else { return }
                Task { if await model.install(urls: urls) { await model.refreshAfterContentChange(onInstalled) } }
            }
        }
        .sheet(item: $uninstalling) { title in
            UninstallSheet(serial: title.serial, gameTitle: title.title, onUninstalled: {
                await model.refreshAfterContentChange(onInstalled)
            })
        }
        .alert(model.deletePrompt?.title ?? "", isPresented: deleteShown, presenting: model.deletePrompt) { prompt in
            Button("Delete", role: .destructive) { model.deleteSources(prompt) }
            Button("Keep", role: .cancel) {}
        }
        .sheet(item: $updateSheet) { sheet in
            switch sheet {
            case .versions(let serial, let name):
                UpdateVersionSheet(serial: serial, name: name, controllerInput: controllerInput)
            case .confirm(let request):
                UpdateConfirmSheet(request: request, controllerInput: controllerInput)
            case .title:
                UpdateTitleSheet(controllerInput: controllerInput)
            }
        }
    }

    private func page<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView {
            VStack(spacing: 20) {
                Picker("Install or Updates", selection: $side) {
                    Text("Install").tag(InstallSide.install)
                    Text("Updates").tag(InstallSide.updates)
                }
                .pickerStyle(.segmented)
                .controllerAccessibilityActionTarget(id: "install.switch", label: String(localized: "Install or Updates")) {
                    side = side == .install ? .updates : .install
                }
                .padding(.horizontal)
                content()
            }
            .padding(.vertical, 24)
            .frame(maxWidth: .infinity)
        }
    }

    private func refreshUpdates() async {
        let store = UpdateStore.shared
        guard store.network.online else { return }
        await store.check(fresh: true)
    }

    // Circle closes the library's sheet; the tab has nothing to close.
    private var back: (@MainActor () -> Bool)? {
        guard showsDone else { return nil }
        return {
            if !model.busy { dismiss() }
            return true
        }
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { model.deletePrompt != nil }, set: { if !$0 { model.deletePrompt = nil } })
    }

    private func rowView(_ row: InstallRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(row.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Text(row.kind.label)
                    .font(.caption)
                    .foregroundStyle(OverlayTheme.textSecondary)
            }
            if let progress = row.progress {
                ProgressView(value: progress)
            }
            if let result = row.result {
                Text(result)
                    .font(.footnote)
                    .foregroundStyle(OverlayTheme.textSecondary)
            }
        }
        .padding(12)
        .background(OverlayTheme.card.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal)
    }

    private func installedRow(_ title: InstalledTitle) -> some View {
        let size = ByteCountFormatter.string(fromByteCount: title.size, countStyle: .file)
        return HStack(spacing: 12) {
            Group {
                if let icon = title.iconURL.flatMap({ UIImage(contentsOfFile: $0.path) }) {
                    Image(uiImage: icon).resizable().scaledToFill()
                } else {
                    Image(systemName: "gamecontroller").foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(title.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(2)
                Text(title.detail.isEmpty ? size : "\(size) · \(title.detail)")
                    .font(.footnote)
                    .foregroundStyle(OverlayTheme.textSecondary)
            }
            Spacer()
            Button("Uninstall", role: .destructive) { uninstalling = title }
                .disabled(model.busy)
                .controllerAccessibilityActionTarget(id: "install.uninstall." + title.serial, label: String(localized: "Uninstall")) {
                    if !model.busy { uninstalling = title }
                }
        }
        .padding(12)
        .background(OverlayTheme.card.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal)
    }
}

enum InstallSide {
    case install, updates
}

struct InstallRow: Identifiable {
    let id: Int
    let name: String
    let kind: ContentKind
    var progress: Double?
    var result: String?
}

struct DeletePrompt {
    let urls: [URL]
    let bytes: Int64

    var title: String {
        let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        return urls.count == 1 ? String(localized: "Delete the .pkg to free \(size)?") : String(localized: "Delete the \(urls.count) .pkg files to free \(size)?")
    }
}

@MainActor @Observable final class InstallModel {
    private(set) var rows: [InstallRow] = []
    private(set) var busy = false
    private(set) var version: String?
    private(set) var installed: [InstalledTitle] = []
    var message: String?
    var deletePrompt: DeletePrompt?
    @ObservationIgnored weak var library: LibraryModel?
    @ObservationIgnored private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    static let pendingMarker = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("armsx3", isDirectory: true)
        .appendingPathComponent("install-pending.json")
    // One flag for every model, because the pending marker is one file for all of them; `busy` stays per instance.
    private static var installing = false
    // The updater shares this flag, so it and this screen never install at once.
    static var installInFlight: Bool { installing }

    static func claimForUpdate() -> Bool {
        guard !installing else { return false }
        installing = true
        return true
    }

    static func releaseForUpdate() {
        installing = false
    }

    // The PUP is copied into tmp and the copy deleted on every path; the picked file is the player's and only read.
    private func install(_ picked: URL, row: Int) async -> Int {
        let files = FileManager.default
        let copy = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("armsx3-firmware.pup")
        defer {
            try? files.removeItem(at: copy)
        }
        try? files.removeItem(at: copy)
        do {
            try files.copyItem(at: picked, to: copy)
        } catch {
            rows[row].result = String(localized: "Could not read that file. Try picking it again.")
            return -3
        }
        rows[row].progress = 0
        let status: Int = await withCheckedContinuation { continuation in
            Rpcs3Bridge.installFirmware(atPath: copy.path, progress: { done, total in
                self.rows[row].progress = total > 0 ? Double(done) / Double(total) : 0
            }, completion: { status in
                continuation.resume(returning: status)
            })
        }
        rows[row].progress = nil
        let installed = await Self.installedVersion()
        version = installed
        // The XMB reads its icons, words and sounds from the firmware just installed.
        if status == 0 { await XmbFirmware.shared.load(again: true) }
        rows[row].result = Self.describe(status, version: installed)
        return status
    }

    // Licences go after the packages: a licence unlocks content the package has to have written.
    @discardableResult
    func install(urls: [URL]) async -> Bool {
        guard !busy, !urls.isEmpty else { return false }
        if Self.installing {
            message = String(localized: "Another install is running. Wait for it to finish.")
            return false
        }
        // Without a prepared core every install fails, so nothing is copied first.
        if EmulatorSession.shared.browsing {
            message = EmulatorSession.browsingRefusal
            return false
        }
        Self.installing = true
        busy = true
        message = nil
        deletePrompt = nil
        let scoped = urls.map { $0.startAccessingSecurityScopedResource() }
        UIApplication.shared.isIdleTimerDisabled = true
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "armsx3-install") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundTask() }
        }
        Self.writePendingMarker()
        defer {
            Self.removePendingMarker()
            endBackgroundTask()
            UIApplication.shared.isIdleTimerDisabled = false
            for (url, started) in zip(urls, scoped) where started { url.stopAccessingSecurityScopedResource() }
            busy = false
            Self.installing = false
        }

        // A file's first bytes and its size decide its kind, never its extension.
        var items: [ContentRouter.Item] = []
        var sizes: [Int64] = []
        rows = []
        for (at, url) in urls.enumerated() {
            let name = url.lastPathComponent
            guard let probe = Self.probe(url) else {
                sizes.append(0)
                rows.append(InstallRow(id: at, name: name, kind: .unknown,
                                       result: String(localized: "Couldn't read this file. If it is in iCloud Drive, download it first.")))
                continue
            }
            let kind = ContentRouter.sniff(header: probe.header, fileName: name, fileSize: probe.size)
            sizes.append(probe.size)
            rows.append(InstallRow(id: at, name: name, kind: kind))
            items.append(ContentRouter.Item(index: at, name: name, kind: kind))
        }
        let plan = ContentRouter.plan(items)
        for error in plan.errors {
            rows[error.index].result = String(localized: "Not installed: \(error.reason)")
        }

        var installedAny = false
        var reprobe: [String] = []
        for at in plan.firmware {
            let status = await install(urls[at], row: at)
            if status == 0 { installedAny = true }
        }

        if !plan.packages.isEmpty {
            let job = plan.packages
            let sources = job + plan.coveredParts
            for at in job { rows[at].progress = 0 }
            let (status, titleId): (Int, String) = await withCheckedContinuation { continuation in
                Rpcs3Bridge.installPackages(atPaths: job.map { urls[$0].path }, progress: { file, permille in
                    guard Int(file) < job.count else { return }
                    // Whole percents, so the XMB's columns, which show the percent, are not rebuilt a thousand times.
                    let progress = Double(permille / 10) / 100
                    if self.rows[job[Int(file)]].progress != progress { self.rows[job[Int(file)]].progress = progress }
                }, completion: { status, titleId in
                    continuation.resume(returning: (status, titleId))
                })
            }
            for at in sources {
                rows[at].progress = nil
                rows[at].result = Self.describe(package: status)
            }
            if status == 0 {
                installedAny = true
                if !titleId.isEmpty { reprobe.append(titleId) }
                deletePrompt = DeletePrompt(urls: sources.map { urls[$0] }, bytes: sources.reduce(0) { $0 + sizes[$1] })
            }
        }

        var licenced = false
        for at in plan.licences {
            let status: Int = await withCheckedContinuation { continuation in
                Rpcs3Bridge.installLicence(atPath: urls[at].path, forSerial: nil) { status in
                    continuation.resume(returning: status)
                }
            }
            rows[at].result = Self.describe(licence: status)
            if status == 0 {
                installedAny = true
                licenced = true
            }
        }

        if let library {
            if licenced { reprobe += library.entries.filter { $0.locked == true }.map(\.serial) }
            for serial in Set(reprobe) { await library.reprobe(serial: serial) }
        }

        return installedAny
    }

    // The only place a player's file is deleted. A File Provider may refuse; that is reported and not retried.
    func deleteSources(_ prompt: DeletePrompt) {
        deletePrompt = nil
        var refused = 0
        for url in prompt.urls {
            let started = url.startAccessingSecurityScopedResource()
            defer { if started { url.stopAccessingSecurityScopedResource() } }
            var coordination: NSError?
            var deleted = false
            NSFileCoordinator().coordinate(writingItemAt: url, options: .forDeleting, error: &coordination) { target in
                deleted = (try? FileManager.default.removeItem(at: target)) != nil
            }
            if !deleted { refused += 1 }
        }
        message = refused == 0
            ? String(localized: "Deleted.")
            : String(localized: "Files would not delete \(refused) of \(prompt.urls.count). Delete it in the Files app instead.")
    }

    // Once per visit: a marker left behind means a kill ended an install half-way.
    func noteInterruptedInstall() {
        guard !busy, !Self.installing, FileManager.default.fileExists(atPath: Self.pendingMarker.path) else { return }
        message = String(localized: "An install was interrupted. Install the package again to finish it.")
        Self.removePendingMarker()
    }

    func refreshVersion() async {
        version = await Self.installedVersion()
    }

    // shortcut: probed once per title per process (it reads the whole EBOOT); a reinstall keeps its id, so a restart refreshes.
    private static var ownContentIds: [String: String] = [:]

    // Empty when the core has no folders yet.
    func rescanInstalled() async {
        let hdd0 = await Self.folder(Rpcs3Bridge.hdd0Directory)
        let user = await Self.folder(Rpcs3Bridge.userDirectory)
        guard !hdd0.isEmpty, !user.isEmpty else {
            installed = []
            return
        }
        let game = URL(fileURLWithPath: hdd0, isDirectory: true).appendingPathComponent("game", isDirectory: true)
        let exdata = URL(fileURLWithPath: user, isDirectory: true).appendingPathComponent("exdata", isDirectory: true)
        func scan() async -> [InstalledTitle] {
            let ids = Self.ownContentIds
            return await Task.detached(priority: .utility) {
                InstalledContent.scan(gameDir: game, exdataDir: exdata, ownContentIds: ids)
            }.value
        }
        var found = await scan()
        let unprobed = found.filter { $0.category == "HG" && Self.ownContentIds[$0.serial] == nil }.map(\.serial)
        for serial in unprobed {
            let (_, contentId, status) = await Rpcs3Bridge.probeLicence(forSerial: serial)
            Self.ownContentIds[serial] = status == 0 ? contentId : ""
        }
        if !unprobed.isEmpty { found = await scan() }
        installed = found
    }

    func refreshAfterContentChange(_ refreshLibrary: () async -> Void) async {
        await rescanInstalled()
        await refreshLibrary()
    }

    // A façade folder query, "" on any status but 0.
    private static func folder(_ query: (@escaping @Sendable (String, Int) -> Void) -> Void) async -> String {
        await withCheckedContinuation { continuation in
            query { path, status in continuation.resume(returning: status == 0 ? path : "") }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private static func writePendingMarker() {
        try? FileManager.default.createDirectory(at: pendingMarker.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        let body = "{\"started\": \(Int(Date().timeIntervalSince1970))}"
        try? Data(body.utf8).write(to: pendingMarker, options: .atomic)
    }

    private static func removePendingMarker() {
        try? FileManager.default.removeItem(at: Self.pendingMarker)
    }

    // The first 16 bytes and the size, or nil when the file cannot be opened.
    private static func probe(_ url: URL) -> (header: Data, size: Int64)? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        let header = (try? handle.read(upToCount: 16)) ?? Data()
        let size = (try? handle.seekToEnd()).map { Int64($0) } ?? 0
        return (header, size)
    }

    static func describe(_ status: Int, version: String) -> String {
        switch status {
        case 0: return String(localized: "Firmware \(version) installed")
        case -11: return String(localized: "That file is not PS3 firmware")
        case -12: return String(localized: "Could not write the firmware. Check free space and try again")
        case -9: return String(localized: "Stop the game first")
        default: return String(localized: "The install failed (status \(status))")
        }
    }

    static func describe(package status: Int) -> String {
        switch status {
        case 0: return String(localized: "Installed")
        case -14: return String(localized: "Not a PS3 package, or a part is missing")
        case -15: return String(localized: "This update needs its game installed first, at the right version")
        case -16: return String(localized: "Couldn't write the game; check free space")
        case -9: return String(localized: "Stop the game first")
        default: return String(localized: "The install failed (status \(status))")
        }
    }

    static func describe(licence status: Int) -> String {
        switch status {
        case 0: return String(localized: "Licence installed")
        case -17: return String(localized: "Not a licence this app can place by name — add it from the locked game instead")
        case -3: return String(localized: "Couldn't read this file")
        case -16: return String(localized: "Couldn't write the licence; check free space")
        case -9: return String(localized: "Stop the game first")
        default: return String(localized: "The licence wasn't installed (status \(status))")
        }
    }

    // "" when none is installed or the query failed.
    private static func installedVersion() async -> String {
        await withCheckedContinuation { continuation in
            Rpcs3Bridge.firmwareVersion { version, status in
                continuation.resume(returning: status == 0 ? version : "")
            }
        }
    }
}

private extension ContentKind {
    var label: String {
        switch self {
        case .pup: return String(localized: "Firmware")
        case .pkg: return String(localized: "Package")
        case .pkgPart: return String(localized: "Package part")
        case .rap: return String(localized: "Licence (RAP)")
        case .edat: return String(localized: "Licence (EDAT)")
        case .unknown: return String(localized: "Unknown")
        }
    }
}

// Opened in place and not copied, so a package of many gigabytes never lands in the app. Empty on cancel.
struct InstallPicker: UIViewControllerRepresentable {
    var multiple = true
    let onFinish: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.allowsMultipleSelection = multiple
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: ([URL]) -> Void

        init(onFinish: @escaping ([URL]) -> Void) { self.onFinish = onFinish }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFinish(urls)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFinish([]) }
    }
}

// Only the pairing import uses this copying picker, narrowed to property lists. nil on cancel.
struct FirmwarePicker: UIViewControllerRepresentable {
    var types: [UTType] = [.item]
    let onFinish: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: (URL?) -> Void

        init(onFinish: @escaping (URL?) -> Void) { self.onFinish = onFinish }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFinish(urls.first)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFinish(nil) }
    }
}

// `version` is InstallModel's: nil while it is read, "" when none is installed.
struct FirmwareStatusCard: View {
    let version: String?
    var install: (() -> Void)? = nil

    var body: some View {
        OverlaySectionCard {
            HStack(spacing: 12) {
                symbol
                    .font(.title3)
                    .frame(width: 28)
                Text(title)
                    .font(.headline)
                    .foregroundStyle(OverlayTheme.textPrimary)
                Spacer(minLength: 8)
                if let version, !version.isEmpty {
                    Text(verbatim: version)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .monospacedDigit()
                }
                if let install {
                    Button("Install…", action: install)
                        .buttonStyle(.borderedProminent)
                        .controllerAccessibilityActionTarget(id: "library.firmware.install",
                                                             label: String(localized: "Install…"), action: install)
                }
            }
            .frame(minHeight: 36)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var symbol: some View {
        if version == nil {
            ProgressView()
        } else if version == "" {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        } else {
            Image(systemName: "checkmark.seal.fill").foregroundStyle(.green)
        }
    }

    private var title: String {
        guard let version else { return String(localized: "Checking the PS3 firmware…") }
        return version.isEmpty ? String(localized: "No PS3 firmware") : String(localized: "PS3 firmware installed")
    }
}
