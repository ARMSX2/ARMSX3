// SPDX-License-Identifier: GPL-2.0-only
// One settings pane, its choice list and the tree's states. Which leaves it holds is SettingsModel's rule.

import SwiftUI

struct SettingsPaneView: View {
    let pane: SettingsPane
    let model: SettingsScreenModel
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()

    @State private var confirmingReset = false

    var body: some View {
        SettingsPage {
            if let state = SettingsTreeState(model) {
                state
            } else if case .choices(let leafPath) = pane {
                if let leaf = model.leaf(leafPath) {
                    choiceList(leaf)
                }
            } else {
                paneContent
            }
        }
        .navigationTitle(pane.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + pane.navigationKey,
                                           onBack: { pop() }, onContextMenu: { id in model.resetRow(navigationID: id) },
                                           onPreviousTab: controller.onPreviousTab, onNextTab: controller.onNextTab,
                                           onBoundary: controller.onBoundary)
        .alert(resetTitle, isPresented: $confirmingReset) {
            Button(model.scope == .game ? "Remove" : "Reset", role: .destructive) {
                Task { await model.resetPane(pane) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(resetMessage)
        }
    }

    @ViewBuilder private var paneContent: some View {
        if let caption = Self.caption(pane) {
            Text(caption)
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        let leaves = model.leaves(pane)
        if pane == .fixes && leaves.isEmpty {
            Text("The online game database hasn't downloaded yet, so there are no fixes to show.")
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if leaves.isEmpty && !model.showAdvanced {
            // Every row this pane holds is advanced, and advanced settings are hidden.
            SettingsMessage(heading: "Nothing to show here",
                            message: "Every setting in this section is advanced. Turn on Show advanced settings to see it.")
        }
        ForEach(SettingsModel.sections(leaves)) { section in
            OverlaySectionCard(title: section.title) {
                ForEach(section.leaves) { leaf in
                    SettingRow(leaf: leaf, model: model, path: $path)
                }
            }
        }
        OverlaySectionCard {
            OverlayActionRow(controllerNavigationID: "settings.reset." + pane.navigationKey, label: "Reset \(pane.title)",
                             systemImage: "arrow.counterclockwise", isDestructive: true) {
                confirmingReset = true
            }
            // Greyed while nothing in the pane differs from its default.
            .disabled(!leaves.contains { SettingsModel.resettable($0, scope: model.scope) })
        }
        .padding(.top, 16)
    }

    static func caption(_ pane: SettingsPane) -> String? {
        pane == .network ? String(localized: "Online play uses RPCN. Turn it on and sign in from Settings › RPCN.") : nil
    }

    // Choosing a value sets it and pops.
    private func choiceList(_ leaf: SettingLeaf) -> some View {
        let current = model.value(leaf)
        return OverlaySectionCard(title: leaf.path.dropLast().last) {
            ForEach(Array(leaf.variants.enumerated()), id: \.offset) { index, variant in
                ChoiceRow(navigationID: SettingRow.navigationID(leaf) + ".choice.\(index)",
                          label: variant == leaf.defaultValue ? String(localized: "\(variant) (default)") : variant,
                          checked: variant == current) {
                    if variant != current { Task { await model.set(leaf, value: variant) } }
                    _ = pop()
                }
            }
        }
    }

    private var resetTitle: String {
        model.scope == .game ? String(localized: "Remove this game's \(pane.title) changes?") : String(localized: "Reset all \(pane.title) settings?")
    }

    private var resetMessage: String {
        model.scope == .game ? String(localized: "These settings go back to the All games values for \(model.gameTitle).")
            : String(localized: "Every \(pane.title) setting goes back to its default.")
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }

}

// Rows never render from a missing tree.
struct SettingsTreeState: View {
    private let failure: Int?

    init?(_ model: SettingsScreenModel) {
        if let status = model.failureStatus, !model.loading {
            failure = status
        } else if model.loading || model.dump == nil {
            failure = nil
        } else {
            return nil
        }
    }

    var body: some View {
        if let failure {
            SettingsMessage(heading: "Couldn't read the emulator's settings (status \(failure))",
                            message: "Close Settings and open it again. If it keeps happening, restart the app.")
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.top, 48)
        }
    }
}

struct SettingsMessage: View {
    let heading: String
    let message: String

    // Literal copy is a String Catalog key; a String is shown as written.
    init(heading: LocalizedStringResource, message: LocalizedStringResource) {
        self.init(heading: String(localized: heading), message: String(localized: message))
    }

    @_disfavoredOverload
    init<H: StringProtocol, M: StringProtocol>(heading: H, message: M) {
        self.heading = String(heading)
        self.message = String(message)
    }

    var body: some View {
        VStack(spacing: 8) {
            Text(heading)
                .font(.title3.weight(.semibold))
                .foregroundStyle(OverlayTheme.textPrimary)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }
}

// Not private, because the JIT method picker reuses it.
struct ChoiceRow: View {
    let navigationID: String
    let label: String
    let checked: Bool
    // VoiceOver gets it from the caller's value, so it is hidden here.
    var caption: String? = nil
    let action: () -> Void
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: action) {
            HStack(spacing: OverlayTheme.rowIconSpacing) {
                // The value's name wraps at accessibility sizes instead of being cut.
                Image(systemName: "checkmark")
                    .overlayRowIconColumn()
                    .foregroundStyle(accentColour)
                    .opacity(checked ? 1 : 0)
                VStack(alignment: .leading, spacing: 4) {
                    Text(label)
                        .foregroundStyle(OverlayTheme.textPrimary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    if let caption {
                        Text(verbatim: caption)
                            .font(.subheadline)
                            .foregroundStyle(OverlayTheme.textSecondary)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                            .truncationMode(.middle)
                            .accessibilityHidden(true)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
        .controllerAccessibilityActionTarget(id: navigationID, label: label, action: action)
    }
}
