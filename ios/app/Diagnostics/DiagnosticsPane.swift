// SPDX-License-Identifier: GPL-2.0-only
// Settings > Diagnostics. iOS cannot tell a background kill from a swipe-away, so no session is called ended normally.

import SwiftUI

struct DiagnosticsPane: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    var store: DiagnosticsStore = .live()
    // A scene may pass the current session; the app leaves it nil and the pane reads DiagnosticsSession's.
    var current: URL? = nil
    @State private var rows: [DiagnosticsStore.SessionRow] = []
    @State private var report: ReportRequest?

    // An older row's date beside the chevron cut longer words off on a phone, so today's rows show the time alone.
    private static let todayFormat: DateFormatter = {
        let format = DateFormatter()
        format.timeStyle = .short
        return format
    }()
    private static let olderFormat: DateFormatter = {
        let format = DateFormatter()
        format.setLocalizedDateFormatFromTemplate("MMMdjmm")
        return format
    }()

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Report a Problem")) {
                // No label inset: the session rows carry no icon, so their labels start at the card's edge.
                Text("Tap the session where the problem happened. A report opens for it.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(rows) { row in
                    let now = row.outcome == .running
                    OverlayActionRow(controllerNavigationID: "settings.diagnostics.session." + (now ? "now" : row.url.lastPathComponent),
                                     label: label(row, now: now), trailingValue: detail(row), showsChevron: true) {
                        report = ReportRequest(session: row.url, serial: row.serial)
                    }
                }
            }
            OverlaySectionCard {
                OverlayToggleRow(controllerNavigationID: "settings.verboselogging", label: "Verbose logging", systemImage: "doc.text.magnifyingglass",
                                 isOn: Binding(get: { SettingsStore.shared.verboseLogging },
                                               set: { on in SettingsStore.shared.verboseLogging = on; Task { _ = await Rpcs3Bridge.setVerboseLogging(on) } }))
                Text("Records much more detail about graphics for a problem report. Turn it on only when asked to reproduce a stutter: it can slow games down.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .overlayRowLabelInset()
            }
        }
        .navigationTitle(SettingsPane.diagnostics.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.diagnostics.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .task { await reload() }
        .sheet(item: $report, onDismiss: { Task { await reload() } }) {
            ProblemReportView(session: $0.session, serial: $0.serial)
        }
    }

    private func label(_ row: DiagnosticsStore.SessionRow, now: Bool) -> String {
        if now { return String(localized: "Now") }
        return row.label ?? (row.game ? String(localized: "Game") : String(localized: "Menu"))
    }

    private func detail(_ row: DiagnosticsStore.SessionRow) -> String? {
        let start = row.started.map { (Calendar.current.isDateInToday($0) ? Self.todayFormat : Self.olderFormat).string(from: $0) }
        let outcome: String?
        switch row.outcome {
        case .crashed: outcome = String(localized: "Crashed")
        case .starting: outcome = String(localized: "Closed while starting")
        case .background: outcome = String(localized: "Closed by iOS")
        case .running, nil: outcome = nil
        }
        let parts = [start, outcome].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    // The folder scan reads every session's state.json, so it never runs on the main actor.
    private func reload() async {
        let store = store
        let current = current ?? DiagnosticsSession.shared.current
        rows = await Task.detached(priority: .userInitiated) { store.sessionRows(current: current) }.value
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}
