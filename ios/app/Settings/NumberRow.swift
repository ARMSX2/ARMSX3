// SPDX-License-Identifier: GPL-2.0-only
// The slider row the lifted dynamic background controls call; its interface is what those call sites spell.

import SwiftUI

struct NumberFormat {
    enum Kind {
        case multiplier, unitPercent, plain, points
        case opaque(String)
    }

    let kind: Kind
    var decimalPlaces: Int
    // For a range that runs either side of zero.
    var showsSign = false

    static let multiplier = NumberFormat(kind: .multiplier, decimalPlaces: 2)
    static let unitPercent = NumberFormat(kind: .unitPercent, decimalPlaces: 0)
    static let plain = NumberFormat(kind: .plain, decimalPlaces: 0)
    static let points = NumberFormat(kind: .points, decimalPlaces: 0)
    static func opaque(_ text: String) -> NumberFormat { NumberFormat(kind: .opaque(text), decimalPlaces: 0) }

    var signed: NumberFormat {
        var format = self
        format.showsSign = true
        return format
    }

    func decimals(_ places: Int) -> NumberFormat {
        var format = self
        format.decimalPlaces = max(0, places)
        return format
    }

    // Nil for a readout the caller wrote itself.
    var displayStep: Double? {
        switch kind {
        case .opaque: return nil
        case .unitPercent: return 0.01
        default: return pow(10, -Double(decimalPlaces))
        }
    }

    func text(_ value: Double) -> String {
        let sign = showsSign ? "+" : ""
        let number = String(format: "%\(sign).\(decimalPlaces)f", value)
        switch kind {
        case .multiplier: return number + "×"
        case .unitPercent: return String(format: "%\(sign)ld%%", Int((value * 100).rounded()))
        case .plain: return number
        case .points: return String(localized: "\(number) pt")
        case .opaque(let text): return text
        }
    }
}

// The palette editor reads this for its live preview.
struct NumberRowActivity {
    let update: @MainActor (String, String, Bool) -> Void
}

enum NumberRowStyle {
    case regular, compact
}

private struct NumberRowActivityKey: EnvironmentKey {
    static let defaultValue: NumberRowActivity? = nil
}

private struct NumberRowStyleKey: EnvironmentKey {
    static let defaultValue = NumberRowStyle.regular
}

extension EnvironmentValues {
    var numberRowActivity: NumberRowActivity? {
        get { self[NumberRowActivityKey.self] }
        set { self[NumberRowActivityKey.self] = newValue }
    }

    var numberRowStyle: NumberRowStyle {
        get { self[NumberRowStyleKey.self] }
        set { self[NumberRowStyleKey.self] = newValue }
    }
}

extension View {
    func numberRowStyle(_ style: NumberRowStyle) -> some View {
        environment(\.numberRowStyle, style)
    }
}

struct NumberRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let format: NumberFormat
    let step: Double?
    let defaultValue: Double?
    let settings: SettingsStore
    @Environment(\.numberRowActivity) private var activity
    @Environment(\.numberRowStyle) private var style
    @State private var editing = false

    init(_ title: String, value: Binding<Double>, in range: ClosedRange<Double>, format: NumberFormat,
         step: Double? = nil, default defaultValue: Double? = nil, settings: SettingsStore) {
        self.title = title
        _value = value
        self.range = range
        self.format = format
        self.step = step
        self.defaultValue = defaultValue
        self.settings = settings
    }

    var body: some View {
        let label = settings.localized(title)
        let readout = format.text(value)
        VStack(alignment: .leading, spacing: style == .compact ? 4 : 8) {
            HStack(spacing: 8) {
                Text(verbatim: label)
                Spacer(minLength: 8)
                Text(verbatim: readout)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                if let defaultValue {
                    DynamicSettingsResetButton(title: label) { value = defaultValue }
                        .disabled(value == defaultValue)
                }
            }
            .font(style == .compact ? .caption.weight(.semibold) : .body)
            slider(label: label)
        }
        .controllerAccessibilityAdjustableTarget(id: nil, label: label, value: readout,
                                                 onActivate: { nudge(1) }, onIncrement: { nudge(1) },
                                                 onDecrement: { nudge(-1) })
        .onChange(of: value) { _, newValue in
            if editing { activity?.update(label, format.text(newValue), true) }
        }
    }

    @ViewBuilder private func slider(label: String) -> some View {
        let changed: (Bool) -> Void = { isEditing in
            editing = isEditing
            activity?.update(label, format.text(value), isEditing)
        }
        if let step, step > 0, step < range.upperBound - range.lowerBound {
            Slider(value: $value, in: range, step: step, onEditingChanged: changed)
        } else {
            Slider(value: $value, in: range, onEditingChanged: changed)
        }
    }

    // A twentieth of the range for a caller-written readout.
    private func nudge(_ direction: Double) {
        let amount = step ?? (range.upperBound - range.lowerBound) / 20
        value = min(range.upperBound, max(range.lowerBound, value + direction * amount))
    }
}

struct ConfirmedSettingsResetButton: View {
    let title: String
    let confirmationTitle: String
    let confirmationMessage: String
    let completionMessage: String
    let controllerTargetID: String
    let action: () -> Void
    @State private var confirming = false
    @State private var done = false

    // Every caller passes app copy, so each title is a String Catalog key.
    init(_ title: LocalizedStringResource, confirmationTitle: LocalizedStringResource,
         confirmationMessage: LocalizedStringResource, completionMessage: LocalizedStringResource,
         controllerTargetID: String, action: @escaping () -> Void) {
        self.title = String(localized: title)
        self.confirmationTitle = String(localized: confirmationTitle)
        self.confirmationMessage = String(localized: confirmationMessage)
        self.completionMessage = String(localized: completionMessage)
        self.controllerTargetID = controllerTargetID
        self.action = action
    }

    var body: some View {
        Button { confirming = true } label: {
            Text(verbatim: done ? completionMessage : title)
        }
        .controllerAccessibilityActionTarget(id: controllerTargetID, label: title) { confirming = true }
        .alert(confirmationTitle, isPresented: $confirming) {
            Button("Reset", role: .destructive) {
                action()
                done = true
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    done = false
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(verbatim: confirmationMessage)
        }
    }
}
