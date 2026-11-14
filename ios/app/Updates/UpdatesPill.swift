// SPDX-License-Identifier: GPL-2.0-only
// The library's update pill, and the card a launch shows while its update installs.

import SwiftUI

struct UpdatesPill: View {
    // In RootView's tabs the Install tab opens on Updates; elsewhere the library's Install sheet does.
    let inTabs: Bool
    let openSheet: () -> Void
    /// A phone as narrow as an iPhone SE, where 160 points would push Add into the toolbar's overflow.
    var narrow = false
    private var store: UpdateStore { UpdateStore.shared }

    var body: some View {
        if let text = store.pillText {
            Button {
                store.openOnUpdates = true
                store.failureSeen()
                if inTabs {
                    NotificationCenter.default.post(name: DeepLink.openTabNotification, object: 1)
                } else {
                    openSheet()
                }
            } label: {
                label(text)
                    .font(.footnote.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    // Narrow enough that the bar's own buttons stay out of its overflow.
                    .frame(maxWidth: narrow ? 136 : 160)
                    // White, not the tint: blue text vanished on the blue wallpaper.
                    .foregroundStyle(store.pillFailed ? OverlayTheme.warm : OverlayTheme.textPrimary)
            }
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .accessibilityLabel(String(localized: "Game updates"))
            .accessibilityValue(text.replacingOccurrences(of: " · ", with: ", "))
            .accessibilityHint(String(localized: "Opens Updates."))
        }
    }

    // Only the game's name gives way when the pill is too wide.
    @ViewBuilder private func label(_ text: String) -> some View {
        if let name = name, let range = text.range(of: name) {
            // A trailing space on a fixed segment is squeezed, so the space before the name travels with it.
            let head = text[..<range.lowerBound]
            let lead = head.reversed().prefix { $0 == " " }.count
            HStack(spacing: 0) {
                Text(head.dropLast(lead)).fixedSize()
                Text(String(repeating: " ", count: lead) + name).truncationMode(.tail)
                Text(text[range.upperBound...]).fixedSize()
            }
        } else {
            Text(text)
        }
    }

    private var name: String? {
        switch store.pill {
        case .installing(let name, _)?: return name
        case .finished(let name?, _)?: return name
        default: return nil
        }
    }
}

struct UpdateWaitCard: View {
    let name: String
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                ProgressView()
                Text(String(localized: "Finishing the \(name) update…"))
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "Finishing the \(name) update"))
            Button(action: onCancel) {
                Text(String(localized: "Don't Start Game"))
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(OverlayTheme.textSecondary)
            .accessibilityHint(String(localized: "The update keeps installing."))
        }
        .padding(16)
        .frame(maxWidth: 320)
        .background(OverlayTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
