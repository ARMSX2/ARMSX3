// SPDX-License-Identifier: GPL-2.0-only
// The library's one notice about how JIT was turned on this launch.

import SwiftUI

@MainActor @Observable
final class JitNoticeModel {
    static let shared = JitNoticeModel()
    static let openJitDeviceNotification = Notification.Name("ARMSX3OpenJitDevice")

    var current: JitFallback.Notice?
    var marks: [String] = []

    /// A notice counts as shown when it first appears, whether or not it is dismissed.
    func appeared() {
        guard !marks.isEmpty else { return }
        JitFallback.markShown(marks, in: .standard)
        marks = []
    }

    func dismiss() {
        current = nil
    }
}

struct JitNoticeCard: View {
    let title: String
    let message: String
    let symbol: String
    var action: (() -> Void)? = nil
    let onDismiss: () -> Void

    var body: some View {
        OverlaySectionCard {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundStyle(symbol == "info.circle.fill" ? OverlayTheme.textSecondary : Color.orange)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: title)
                            .font(.headline)
                            .foregroundStyle(OverlayTheme.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(verbatim: message)
                            .font(.subheadline)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { buttons }
                        VStack(alignment: .leading, spacing: 8) { buttons }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: 720, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var buttons: some View {
        if let action {
            Button("Set Up Built-in JIT", action: action)
                .buttonStyle(.borderedProminent)
                .controllerAccessibilityActionTarget(id: "library.jitnotice.action",
                                                     label: String(localized: "Set Up Built-in JIT"), action: action)
        }
        Button("Dismiss Notice", action: onDismiss)
            .buttonStyle(.borderless)
            .controllerAccessibilityActionTarget(id: "library.jitnotice.dismiss",
                                                 label: String(localized: "Dismiss Notice"), action: onDismiss)
    }
}
