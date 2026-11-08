// SPDX-License-Identifier: GPL-2.0-only
// The JIT method list. Under LiveContainer Built-in is not listed, and the app never picks a method for the player.

import SwiftUI

struct JitMethodPicker: View {
    let model: JitDeviceModel
    var controller = SettingsControllerContext()
    let onDone: @MainActor () -> Void

    static func navigationID(_ method: JitMethod) -> String { "settings.jit.method.choice." + method.rawValue }

    var body: some View {
        SettingsPage {
            if model.liveContainer {
                Text("Built-in isn't available inside LiveContainer, so it isn't listed.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OverlaySectionCard {
                ForEach(model.choices, id: \.self) { method in
                    ChoiceRow(navigationID: Self.navigationID(method),
                              label: method == .builtin ? String(localized: "\(method.title) (default)") : method.title,
                              checked: model.method == method) {
                        model.choose(method)
                        onDone()
                    }
                    Text(method.caption)
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .overlayRowLabelInset()
                }
            }
        }
        .navigationTitle(String(localized: "JIT Method"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.jitanddevice.method",
                                           onBack: { onDone(); return true }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: model.method.map(Self.navigationID))
    }
}
