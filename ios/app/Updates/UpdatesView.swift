// SPDX-License-Identifier: GPL-2.0-only
// The Updates side of the Install screen, one row per library game.

import SwiftUI

struct UpdatesSide: View {
    @Binding var sheet: UpdateSheet?

    // The order on screen; it changes only when a check ends with no job running.
    @State private var shown: [String] = []
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var store: UpdateStore { UpdateStore.shared }

    var body: some View {
        let games = store.listed()
        let names = Dictionary(games.map { ($0.serial, $0.name) }, uniquingKeysWith: { a, _ in a })
        let order = displayOrder(games.map(\.serial))
        let online = store.network.online
        let running = store.jobs.values.contains { !$0.isFinished && !$0.isStopped }
        let available = order.filter { if case .available = store.rowState($0) { return true } else { return false } }
        let icons = iconPaths()
        VStack(spacing: 20) {
            if !online {
                Text("You're offline. Showing installed versions only.")
                    .font(.footnote)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            if games.isEmpty {
                Text("No games with a title ID in your library yet. Add a game folder in Games, or check a title ID below.")
                    .font(.footnote)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            } else {
                VStack(spacing: 8) {
                    // Offline there is nothing to check; pull to refresh stays.
                    if online { summaryRow(games.map { store.rowState($0.serial) }, running: running) }
                    if available.count >= 2 && !running && !store.checking {
                        let all = { sheet = .confirm(UpdateConfirmRequest(games: available.compactMap { newest($0, names[$0])?.games.first })) }
                        Button(action: all) { Text("Update All").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .controllerAccessibilityActionTarget(id: "updates.all", label: String(localized: "Update All"), action: all)
                    }
                }
                .padding(.horizontal)
                LazyVStack(spacing: 8) {
                    ForEach(order, id: \.self) { serial in
                        UpdateRow(serial: serial, name: names[serial] ?? serial, icon: icons[serial] ?? nil, sheet: $sheet)
                    }
                }
            }
            Button { sheet = .title } label: {
                Text("Check Another Title…")
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(OverlayTheme.card.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .controllerAccessibilityActionTarget(id: "updates.manual", label: String(localized: "Check Another Title…")) {
                sheet = .title
            }
            .padding(.horizontal)
            .padding(.top, 12)
        }
        .onAppear {
            store.updatesVisible = true
            store.failureSeen()
            Task { await store.check(fresh: false) }
        }
        .onDisappear { store.updatesVisible = false }
        .onChange(of: store.checking) { _, checking in
            guard !checking, shown.isEmpty || !running else { return }
            withAnimation { shown = store.rowOrder }
        }
    }

    private func iconPaths() -> [String: String?] {
        var found: [String: String?] = [:]
        for entry in store.library?.entries ?? [] {
            if let id = GameUpdates.titleID(entry.serial), found[id] == nil { found[id] = entry.iconPath }
        }
        return found
    }

    // The settled order, else the store's; games added since go last.
    private func displayOrder(_ serials: [String]) -> [String] {
        let base = (shown.isEmpty ? store.rowOrder : shown).filter(serials.contains)
        return base + serials.filter { !base.contains($0) }
    }

    @ViewBuilder private func summaryRow(_ states: [RowState], running: Bool) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Text(summary(states, running: running))
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(OverlayTheme.textPrimary)
                .accessibilityLabel(summary(states, running: running).replacingOccurrences(of: " · ", with: ", "))
            Spacer(minLength: 0)
            Button("Check Again", action: checkAgain)
                .buttonStyle(.bordered)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .onTapGesture(perform: checkAgain)
                .disabled(store.checking)
                .controllerAccessibilityActionTarget(id: "updates.check", label: String(localized: "Check Again"), action: checkAgain)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func checkAgain() {
        guard !store.checking, store.network.online else { return }
        Task { await store.check(fresh: true) }
    }

    private func summary(_ states: [RowState], running: Bool) -> String {
        if store.checking && !running {
            return String(localized: "Checking \(min(store.checked + 1, store.total)) of \(store.total)…")
        }
        let pill = UpdateBoard.pill(jobs: Array(store.jobs.values), offline: !store.network.online, result: store.result)
        return UpdateText.summary(states: states, pill: pill)
    }

    private func newest(_ serial: String, _ name: String?) -> UpdateConfirmRequest? {
        UpdateRow.newest(serial, name: name ?? serial)
    }
}

struct UpdateRow: View {
    let serial: String
    let name: String
    let icon: String?
    @Binding var sheet: UpdateSheet?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var store: UpdateStore { UpdateStore.shared }

    var body: some View {
        let state = store.rowState(serial)
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Button { sheet = .versions(serial: serial, name: name) } label: { card(state) }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(name)
                .accessibilityValue(UpdateText.spoken(state))
                .accessibilityHint(String(localized: "Shows the versions."))
                .controllerAccessibilityActionTarget(id: "updates.row." + serial, label: name) {
                    sheet = .versions(serial: serial, name: name)
                }
            trailing(state)
        }
        .padding(12)
        .background(OverlayTheme.card.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal)
    }

    private func card(_ state: RowState) -> some View {
        HStack(spacing: 12) {
            Group {
                if let image = icon.flatMap({ UIImage(contentsOfFile: $0) }) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "gamecontroller").foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(2)
                Text(UpdateText.line(state))
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(isStopped(state) ? OverlayTheme.warm : OverlayTheme.textSecondary)
                    .lineLimit(2)
                // The installer reports no progress, so an install spins.
                if case .installing = state {
                    ProgressView().controlSize(.small)
                } else if let progress = progress(state) {
                    ProgressView(value: progress, total: 100)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder private func trailing(_ state: RowState) -> some View {
        switch state {
        case .available(_, let to, _, _):
            action("Get", label: String(localized: "Get \(name) \(to)"), hint: nil, grey: false) { get() }
        case .waiting, .downloading, .waitingForConnection:
            action("Cancel", label: String(localized: "Cancel the \(name) download"),
                   hint: String(localized: "Packages already installed stay."), grey: true) { store.cancel(serial) }
        case .stopped:
            action("Try Again", label: String(localized: "Try \(name) again"), hint: nil, grey: false) { tryAgain() }
        default:
            EmptyView()
        }
    }

    private func action(_ title: LocalizedStringKey, label: String, hint: String?, grey: Bool,
                        run: @escaping () -> Void) -> some View {
        Button(title, action: run)
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .controlSize(.regular)
            .tint(grey ? OverlayTheme.textSecondary : nil)
            // The capsule keeps its size; a tap in the frame around it runs the same action.
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .onTapGesture(perform: run)
            .accessibilityLabel(label)
            .accessibilityHint(hint ?? "")
            .controllerAccessibilityActionTarget(id: "updates.row." + serial + ".action", label: label, action: run)
    }

    private func isStopped(_ state: RowState) -> Bool {
        if case .stopped = state { return true }
        return false
    }

    // Percent for a bar; nil when the row has none.
    private func progress(_ state: RowState) -> Double? {
        switch state {
        case .downloading(_, _, _, let percent), .waitingForConnection(let percent):
            return Double(percent)
        default:
            return nil
        }
    }

    private func get() {
        guard let request = Self.newest(serial, name: name) else { return }
        sheet = .confirm(request)
    }

    // On mobile data a job confirmed on Wi-Fi asks again first; the stopped job stays until the player says yes.
    private func tryAgain() {
        guard let job = store.jobs[serial], let request = Self.mobileRetry(job, name: name) else { return store.retry(serial) }
        sheet = .confirm(request)
    }

    // The mobile-data confirm a retry needs first, or nil when it can go straight on.
    static func mobileRetry(_ job: UpdateJob, name: String) -> UpdateConfirmRequest? {
        let network = UpdateStore.shared.network
        guard !job.allowsCellular, network.online, network.expensive || network.constrained else { return nil }
        return retryRequest(job, name: name)
    }

    // The confirm request for what a retry still has to fetch and install.
    static func retryRequest(_ job: UpdateJob, name: String) -> UpdateConfirmRequest? {
        let state = UpdateStore.shared.installed[job.serial]
        let left = job.packages.filter { $0.state != .installed }.map(\.package)
        guard let target = job.packages.last?.package.version, !left.isEmpty else { return nil }
        let chain = UpdateChain(packages: left, removesFirst: job.removesFirst && !job.removed, bytes: left.reduce(0) { $0 + $1.size })
        return UpdateConfirmRequest(games: [.init(serial: job.serial, name: name, chain: chain)], target: target,
                                    installedBefore: state?.installedVersion, dlc: (state?.dlcCount ?? 0) > 0, retry: true)
    }

    // The confirm request for the newest version, as the version sheet builds it.
    static func newest(_ serial: String, name: String) -> UpdateConfirmRequest? {
        let store = UpdateStore.shared
        let state = store.installed[serial]
        guard case .found(let patch) = store.lookups[serial], let newest = patch.packages.last,
              let chain = GameUpdates.chain(published: patch.packages, installed: state?.installedVersion, base: state?.discVersion,
                                            target: newest.version) else { return nil }
        return UpdateConfirmRequest(games: [.init(serial: serial, name: name, chain: chain)], target: newest.version,
                                    installedBefore: state?.installedVersion, dlc: (state?.dlcCount ?? 0) > 0)
    }
}
