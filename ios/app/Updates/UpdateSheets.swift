// SPDX-License-Identifier: GPL-2.0-only
// The game updater's version, confirm and title ID sheets.

import SwiftUI

enum UpdateSheet: Identifiable {
    case versions(serial: String, name: String)
    case confirm(UpdateConfirmRequest)
    case title

    var id: String {
        switch self {
        case .versions(let serial, _): return "versions." + serial
        case .confirm(let request): return "confirm." + request.id.uuidString
        case .title: return "title"
        }
    }
}

// One game to a chosen version, or Update All when there is no target.
struct UpdateConfirmRequest: Identifiable {
    struct Game {
        let serial: String
        let name: String
        let chain: UpdateChain
    }

    let id = UUID()
    let games: [Game]
    var target: String? = nil
    var installedBefore: String? = nil
    var dlc = false
    // Try Again on a stopped job: the sheet restarts it rather than starting a new one.
    var retry = false

    var isAll: Bool { target == nil }
    var isRollback: Bool { !isAll && games.first?.chain.removesFirst == true }
}

struct UpdateVersionSheet: View {
    let serial: String
    let name: String
    var controllerInput: MenuControllerInputRouter? = nil
    // Pushed from the title ID sheet: no stack, no Done.
    var pushed = false

    @State private var confirming: UpdateConfirmRequest?
    @State private var asking = false
    @State private var removeFailure: String?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var store: UpdateStore { UpdateStore.shared }

    var body: some View {
        if pushed {
            content
        } else {
            NavigationStack {
                content
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Done") { dismiss() }
                                .controllerAccessibilityActionTarget(id: "updates.sheet.done", label: String(localized: "Done")) {
                                    dismiss()
                                }
                        }
                    }
            }
            .presentationDetents([.medium, .large])
            .preferredColorScheme(.dark)
        }
    }

    // Title, message and destructive label of the Remove Update prompt.
    static func removePrompt(version: String, name: String, base: String?, dlc: Bool) -> (title: String, message: String, action: String) {
        let title = String(localized: "Remove the \(InstalledTitle.shortVersion(version)) update?")
        if dlc { return (title, UpdateText.dlcWarning, String(localized: "Remove Update and DLC")) }
        guard let base else {
            return (title, String(localized: "\(name) goes back to its disc version. Saves stay."), String(localized: "Remove Update"))
        }
        let message = String(localized: "\(name) goes back to \(InstalledTitle.shortVersion(base)). Saves stay.")
        return (title, message, String(localized: "Remove Update"))
    }

    // The full reason under a stopped chain's line.
    static func reason(_ job: UpdateJob) -> String? {
        guard let failure = job.failure else { return nil }
        if failure == .removeFailed { return job.status.map(UninstallSheet.describe) }
        return UpdateText.stoppedFull(failure) ?? job.status.map { InstallModel.describe(package: $0) }
    }

    private var content: some View {
        let state = store.installed[serial]
        let job = store.jobs[serial].flatMap { $0.isFinished ? nil : $0 }
        let busy = job != nil
        let rows = versionRows(state)
        let first = rows.first { $0.chain != nil }?.target
        return Form {
            Section {
                LabeledContent("Title ID") {
                    Text(serial).monospacedDigit().foregroundStyle(OverlayTheme.textSecondary)
                }
                LabeledContent("Installed") {
                    if let have = state?.installedVersion {
                        Text(InstalledTitle.shortVersion(have)).monospacedDigit().foregroundStyle(OverlayTheme.textSecondary)
                    } else {
                        Text("Not installed").foregroundStyle(OverlayTheme.textSecondary)
                    }
                }
            }
            if job != nil || removeFailure != nil {
                Section { status(job) }
            }
            Section {
                versions(rows, busy: busy)
            } header: {
                Text("Versions")
            } footer: {
                if state?.isPSN == true {
                    Text("Installed from PSN, so an older version can't be put back.")
                } else if rows.contains(where: { $0.chain?.removesFirst == true }) {
                    Text("Installing an older version removes the current update first.")
                }
            }
            if let state, InstalledContent.canRemoveUpdate(state, serial: serial) {
                Section {
                    Button("Remove Update", role: .destructive) { asking = true }
                        .disabled(busy)
                        .controllerAccessibilityActionTarget(id: "updates.remove", label: String(localized: "Remove Update")) {
                            if !busy { asking = true }
                        }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(OverlayFrostBackground().ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $confirming) { request in
            UpdateConfirmSheet(request: request, controllerInput: controllerInput)
        }
        .controllerPrompt(prompt(state).title, isPresented: $asking, message: prompt(state).message,
                          actions: [ControllerPrompt.Action(title: prompt(state).action, isDestructive: true,
                                                            run: { Task { await remove() } }),
                                    .cancel])
        .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: "updates.sheet.version", priority: 200,
                                           onBack: { dismiss(); return true },
                                           preferredInitialFocusLabel: first ?? "updates.sheet.done")
            // A new scope gets no focus until it asks for the entry.
            .onAppear { _ = controllerInput?.requestNavigationSessionEntry(preferLast: false, matchingScopePrefix: "updates.sheet.version") }
        .task { await store.prepareVersions(serial) }
    }

    // A title checked by ID takes the name Sony's answer carries.
    private var title: String {
        if pushed, case .found(let patch) = store.lookups[serial], let named = patch.name { return named }
        return name
    }

    private func prompt(_ state: InstalledUpdate?) -> (title: String, message: String, action: String) {
        Self.removePrompt(version: state?.appVersion ?? "", name: name, base: state?.discVersion, dlc: (state?.dlcCount ?? 0) > 0)
    }

    @ViewBuilder private func status(_ job: UpdateJob?) -> some View {
        if let job {
            let state = store.rowState(serial)
            // Two buttons don't fit beside the reason, so a stopped job puts them under it.
            let layout = dynamicTypeSize.isAccessibilitySize || job.isStopped
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(spacing: 12))
            let buttons = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(spacing: 8))
            layout {
                VStack(alignment: .leading, spacing: 4) {
                    Text(UpdateText.line(state))
                        .monospacedDigit()
                        .foregroundStyle(job.isStopped ? OverlayTheme.warm : OverlayTheme.textPrimary)
                        .accessibilityLabel(UpdateText.spoken(state))
                    if let reason = Self.reason(job) {
                        Text(reason).font(.footnote).foregroundStyle(OverlayTheme.textSecondary)
                    }
                    if !job.isStopped, job.installing == nil, job.receivedBytes > 0 || job.waitingForNetwork {
                        ProgressView(value: Double(job.percent), total: 100)
                    }
                }
                Spacer(minLength: 0)
                if job.isStopped {
                    buttons {
                        Button("Try Again") { tryAgain() }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .controllerAccessibilityActionTarget(id: "updates.sheet.status", label: String(localized: "Try Again")) {
                                tryAgain()
                            }
                        // A retry that can't succeed, like a package Sony took down, would otherwise lock this sheet.
                        Button("Cancel") { store.cancel(serial) }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .tint(OverlayTheme.textSecondary)
                            .accessibilityHint(String(localized: "Packages already installed stay."))
                            .controllerAccessibilityActionTarget(id: "updates.sheet.status.cancel", label: String(localized: "Cancel")) {
                                store.cancel(serial)
                            }
                    }
                } else if job.installing == nil {
                    Button("Cancel") { store.cancel(serial) }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .tint(OverlayTheme.textSecondary)
                        .controllerAccessibilityActionTarget(id: "updates.sheet.status", label: String(localized: "Cancel")) {
                            store.cancel(serial)
                        }
                }
            }
        }
        if let removeFailure {
            Text(removeFailure).foregroundStyle(OverlayTheme.textPrimary)
        }
    }

    @ViewBuilder private func versions(_ rows: [VersionRow], busy: Bool) -> some View {
        if !store.network.online {
            Text("You're offline. Connect to see the versions.").foregroundStyle(OverlayTheme.textSecondary)
        } else {
            switch store.lookups[serial] {
            case nil:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Checking…").foregroundStyle(OverlayTheme.textSecondary)
                }
            case .failed(let failure)?:
                HStack(spacing: 12) {
                    Text(UpdateText.couldNotCheckFull(failure)).foregroundStyle(OverlayTheme.textSecondary)
                    Spacer(minLength: 0)
                    Button("Try Again") { recheck() }
                        .buttonStyle(.borderless)
                        .controllerAccessibilityActionTarget(id: "updates.sheet.retry", label: String(localized: "Try Again")) {
                            recheck()
                        }
                }
            case .some(.none):
                Text("Sony publishes no updates for this game.").foregroundStyle(OverlayTheme.textSecondary)
            case .found?:
                ForEach(rows) { row in
                    versionRow(row, busy: busy)
                }
            }
        }
    }

    private func versionRow(_ row: VersionRow, busy: Bool) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.short).monospacedDigit().foregroundStyle(OverlayTheme.textPrimary)
                if let chain = row.chain {
                    Text(Self.sizeLine(chain, firmware: row.firmware))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            Spacer(minLength: 0)
            if row.chain == nil {
                Text("Current").foregroundStyle(OverlayTheme.textSecondary)
            } else if let chain = row.chain {
                let label = String(localized: "Install \(row.short)")
                Button("Install") { confirm(row.version, chain) }
                    .buttonStyle(.borderless)
                    .disabled(busy)
                    .accessibilityLabel(label)
                    .accessibilityValue(Self.spokenSize(chain, firmware: row.firmware))
                    .controllerAccessibilityActionTarget(id: row.target, label: label) {
                        if !busy { confirm(row.version, chain) }
                    }
            }
        }
    }

    private struct VersionRow: Identifiable {
        let version: String
        // Nil on the installed version.
        let chain: UpdateChain?
        let firmware: String?

        var id: String { version }
        var short: String { InstalledTitle.shortVersion(version) }
        var target: String { "updates.version." + short }
    }

    // Newest first; a PSN game lists nothing older than what it has.
    private func versionRows(_ state: InstalledUpdate?) -> [VersionRow] {
        guard case .found(let patch) = store.lookups[serial] else { return [] }
        let have = state?.installedVersion
        return patch.packages.reversed().compactMap { package in
            if let have, GameUpdates.compare(package.version, have) == 0 {
                return VersionRow(version: package.version, chain: nil, firmware: nil)
            }
            if state?.isPSN == true, let have, GameUpdates.compare(package.version, have) < 0 { return nil }
            guard let chain = GameUpdates.chain(published: patch.packages, installed: have, base: state?.discVersion,
                                                target: package.version) else { return nil }
            let firmware = store.firmware.flatMap { GameUpdates.needsFirmware(package, installed: $0) }
            return VersionRow(version: package.version, chain: chain, firmware: firmware)
        }
    }

    static func sizeLine(_ chain: UpdateChain, firmware: String?) -> String {
        let count = chain.packages.count
        let size = count == 1 ? UpdateText.size(chain.bytes) : UpdateText.packages(count: count, bytes: chain.bytes)
        guard let firmware else { return size }
        return String(localized: "\(size) · needs firmware \(firmware)")
    }

    static func spokenSize(_ chain: UpdateChain, firmware: String?) -> String {
        let count = chain.packages.count
        let size = count == 1 ? UpdateText.size(chain.bytes) : String(localized: "\(count) packages, \(UpdateText.size(chain.bytes))")
        guard let firmware else { return size }
        return String(localized: "\(size), needs firmware \(firmware)")
    }

    private func confirm(_ target: String, _ chain: UpdateChain) {
        let state = store.installed[serial]
        confirming = UpdateConfirmRequest(games: [.init(serial: serial, name: title, chain: chain)], target: target,
                                          installedBefore: state?.installedVersion, dlc: (state?.dlcCount ?? 0) > 0)
    }

    private func recheck() {
        Task { await store.prepareVersions(serial) }
    }

    // On mobile data a Wi-Fi job asks first, as the row's Try Again does.
    private func tryAgain() {
        guard let job = store.jobs[serial], let request = UpdateRow.mobileRetry(job, name: name) else { return store.retry(serial) }
        confirming = request
    }

    private func remove() async {
        let status = await UpdateStore.shared.removeUpdate(serial)
        guard status == 0 else {
            removeFailure = UninstallSheet.describe(status)
            return
        }
        removeFailure = nil
        AccessibilityNotification.Announcement(String(localized: "Update removed.")).post()
    }
}

struct UpdateConfirmSheet: View {
    let request: UpdateConfirmRequest
    var controllerInput: MenuControllerInputRouter? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let store = UpdateStore.shared
        let sizes = request.games.flatMap { $0.chain.packages.map(\.size) }
        let bytes = sizes.reduce(0, +)
        let available = store.availableBytes()
        let short = FreeSpace.shortfall(available: available, sizes: sizes)
        let onMobileData = store.network.expensive || store.network.constrained
        let removesDLC = request.isRollback && request.dlc
        let browsing = EmulatorSession.shared.browsing
        let go = goLabel(onMobileData: onMobileData)
        NavigationStack {
            Form {
                Section {
                    if request.isAll {
                        Text(UpdateText.games(count: request.games.count, bytes: bytes))
                            .monospacedDigit()
                            .foregroundStyle(OverlayTheme.textPrimary)
                            .accessibilityLabel(String(localized: "\(request.games.count) games, \(UpdateText.size(bytes))"))
                    } else {
                        Text(versionsLine)
                            .monospacedDigit()
                            .foregroundStyle(OverlayTheme.textPrimary)
                            .accessibilityLabel(spokenVersions)
                        Text(UpdateText.packages(count: sizes.count, bytes: bytes))
                            .monospacedDigit()
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .accessibilityLabel(sizes.count == 1 ? String(localized: "1 package, \(UpdateText.size(bytes))")
                                : String(localized: "\(sizes.count) packages, \(UpdateText.size(bytes))"))
                    }
                    // When space is short only the shortfall line speaks.
                    if short == 0 {
                        Text(UpdateText.freeAfter(max(0, FreeSpace.freeAfter(available: available, sizes: sizes))))
                            .monospacedDigit()
                            .foregroundStyle(OverlayTheme.textSecondary)
                    }
                    if onMobileData {
                        Text(UpdateText.mobileData).foregroundStyle(OverlayTheme.textSecondary)
                    }
                    if request.isRollback, let before = request.installedBefore {
                        Text(UpdateText.removedFirst(before)).foregroundStyle(OverlayTheme.textSecondary)
                    }
                    if removesDLC {
                        Text(UpdateText.dlcWarning).foregroundStyle(OverlayTheme.textSecondary)
                    }
                    if short > 0 {
                        Text(UpdateText.shortfall(short)).monospacedDigit().foregroundStyle(OverlayTheme.warm)
                    }
                    if browsing {
                        Text(EmulatorSession.browsingRefusal).foregroundStyle(OverlayTheme.warm)
                    }
                }
                Section {
                    Button(go, role: removesDLC ? .destructive : nil) { start(allowsCellular: onMobileData) }
                        .disabled(short > 0 || browsing)
                        .controllerAccessibilityActionTarget(id: "updates.confirm.go", label: go) {
                            if short == 0, !browsing { start(allowsCellular: onMobileData) }
                        }
                    Button("Cancel", role: .cancel) { dismiss() }
                        .controllerAccessibilityActionTarget(id: "updates.confirm.cancel", label: String(localized: "Cancel")) {
                            dismiss()
                        }
                }
            }
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            // A destructive or disabled go button never takes the first focus.
            .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: "updates.sheet.confirm", priority: 210,
                                               onBack: { dismiss(); return true },
                                               preferredInitialFocusLabel: removesDLC || short > 0 || browsing ? "updates.confirm.cancel"
                                                   : "updates.confirm.go")
            .onAppear { _ = controllerInput?.requestNavigationSessionEntry(preferLast: false, matchingScopePrefix: "updates.sheet.confirm") }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
    }

    private var title: String {
        let name = request.games.first?.name ?? ""
        if request.isAll { return String(localized: "Update \(request.games.count) Games") }
        return request.isRollback ? String(localized: "Roll Back \(name)") : String(localized: "Update \(name)")
    }

    private var versionsLine: String {
        let to = request.target ?? ""
        guard let from = request.installedBefore else { return InstalledTitle.shortVersion(to) }
        return UpdateText.versions(from: from, to: to)
    }

    private var spokenVersions: String {
        let to = InstalledTitle.shortVersion(request.target ?? "")
        guard let from = request.installedBefore else { return to }
        return String(localized: "\(InstalledTitle.shortVersion(from)) to \(to)")
    }

    private func goLabel(onMobileData: Bool) -> String {
        if request.isRollback {
            return request.dlc ? String(localized: "Roll Back and Remove DLC") : String(localized: "Roll Back")
        }
        return onMobileData ? String(localized: "Download on Mobile Data") : String(localized: "Install")
    }

    // Mobile data is allowed only from the form that said so.
    private func start(allowsCellular onMobileData: Bool) {
        if request.retry, let game = request.games.first {
            UpdateStore.shared.retry(game.serial, allowsCellular: onMobileData)
        } else if request.isAll {
            UpdateStore.shared.startAll(request.games.map(\.serial), allowsCellular: onMobileData)
        } else if let game = request.games.first {
            UpdateStore.shared.start(serial: game.serial, name: game.name, chain: game.chain, installedBefore: request.installedBefore,
                                     allowsCellular: onMobileData)
        }
        dismiss()
    }
}

struct UpdateTitleSheet: View {
    var controllerInput: MenuControllerInputRouter? = nil
    @State private var text: String
    @State private var invalid: Bool
    @State private var checked: String?
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(controllerInput: MenuControllerInputRouter? = nil, text: String = "", invalid: Bool = false) {
        self.controllerInput = controllerInput
        _text = State(initialValue: text)
        _invalid = State(initialValue: invalid)
    }

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 8))
        NavigationStack {
            Form {
                Section {
                    layout {
                        Text("Title ID").foregroundStyle(OverlayTheme.textPrimary).fixedSize()
                        TextField(text: $text, prompt: Text("BLUS30443").foregroundStyle(OverlayTheme.textSecondary)) {
                            Text("Title ID")
                        }
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .onSubmit(check)
                        .focused($focused)
                        .monospacedDigit()
                        .foregroundStyle(OverlayTheme.textPrimary)
                    }
                    .frame(minHeight: 44)
                    .controllerAccessibilityActionTarget(id: "updates.manual.field", label: String(localized: "Title ID")) {
                        focused = true
                    }
                } footer: {
                    if invalid {
                        Text("That isn't a title ID. It's four letters and five digits, like BLUS30443.")
                    } else {
                        Text("Four letters and five digits, from the game's case or its folder name.")
                    }
                }
                Section {
                    Button("Check Title", action: check)
                        .disabled(text.count != 9)
                        .controllerAccessibilityActionTarget(id: "updates.manual.go", label: String(localized: "Check Title"),
                                                             action: check)
                }
            }
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Check a Title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                        .controllerAccessibilityActionTarget(id: "updates.sheet.done", label: String(localized: "Done")) {
                            dismiss()
                        }
                }
            }
            .navigationDestination(item: $checked) { id in
                UpdateVersionSheet(serial: id, name: id, controllerInput: controllerInput, pushed: true)
            }
            .onChange(of: text) { _, typed in
                // Upper-cased as typed, letters and digits only, nine at most.
                let kept = String(typed.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(9))
                if kept != typed { text = kept }
                invalid = false
            }
            .controllerAccessibilityNavigation(controllerInput: controllerInput, isActive: checked == nil,
                                               scopeKey: "updates.sheet.manual", priority: 200,
                                               onBack: {
                                                   if focused { focused = false } else { dismiss() }
                                                   return true
                                               },
                                               preferredInitialFocusLabel: "updates.manual.field")
            .onAppear { _ = controllerInput?.requestNavigationSessionEntry(preferLast: false, matchingScopePrefix: "updates.sheet.manual") }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
    }

    private func check() {
        guard !text.isEmpty else { return }
        guard let id = GameUpdates.titleID(text) else {
            invalid = true
            return
        }
        focused = false
        checked = id
    }
}
