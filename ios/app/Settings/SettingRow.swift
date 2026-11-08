// SPDX-License-Identifier: GPL-2.0-only
// One setting row. Every change goes through the core, so the row always shows the core's value.

import SwiftUI

struct SettingRow: View {
    let leaf: SettingLeaf
    let model: SettingsScreenModel
    @Binding var path: [SettingsPane]

    @State private var editing = false
    @State private var draft = ""
    @State private var helpExpanded = false
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var name: String { leaf.path.last ?? "" }
    private var navigationID: String { Self.navigationID(leaf) }
    private var value: String { model.value(leaf) }
    private var markers: [SettingMarker] { model.markers(leaf) }

    static func navigationID(_ leaf: SettingLeaf) -> String { "settings.node." + leaf.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 0) {
                control
                if SettingsModel.resettable(leaf, scope: model.scope) {
                    resetButton
                }
            }
            if !markers.isEmpty {
                // One per line at accessibility sizes.
                markerLayout {
                    ForEach(Array(markers.enumerated()), id: \.offset) { _, marker in markerView(marker) }
                }
                .font(.caption)
            }
            if let help = model.help?.text(for: leaf.path) {
                // The first sentence, with the rest a tap away, so a pane is not a wall of text.
                let short = Self.firstSentence(help)
                Text(helpExpanded ? help : short)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if short != help {
                    Button(helpExpanded ? "Less" : "More") { helpExpanded.toggle() }
                        .font(.subheadline)
                        .buttonStyle(.borderless)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .alert(name, isPresented: $editing) {
            TextField(name, text: $draft)
                .keyboardType(keyboard)
            Button("Set Value") { submit() }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let range = Self.rangeText(leaf) { Text(range) }
        }
    }

    // MARK: - The name line

    // At accessibility sizes the value goes under the name.
    @ViewBuilder private var nameAndValue: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
                nameText
                Text(value)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .monospacedDigit()
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        } else {
            nameText
            Spacer(minLength: 0)
            trailingValue
        }
    }

    private var markerLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 8))
    }

    @ViewBuilder private var control: some View {
        switch SettingsModel.control(for: leaf) {
        case .toggle:
            Toggle(isOn: toggleBinding) { nameText }
                .frame(minHeight: 44)
                .controllerAccessibilityActionTarget(id: navigationID, label: name,
                                                     activationFeedback: .toggle(isOn: !toggleBinding.wrappedValue),
                                                     action: { toggleBinding.wrappedValue.toggle() })
        case .stepper(let range):
            LiveStepRow(navigationID: navigationID, label: name, systemImage: "number", value: value,
                        down: { step(-1, in: range) }, up: { step(1, in: range) })
        case .choice:
            valueButton(chevron: true) { path.append(.choices(leaf.path)) }
        case .text:
            valueButton(chevron: false) {
                draft = value
                editing = true
            }
        case .locked:
            HStack(spacing: 8) {
                nameAndValue
            }
            .frame(minHeight: 44)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Locked. The iOS version needs this value.")
            .controllerAccessibilityActionTarget(id: navigationID, label: name, action: {})
        }
    }

    private func valueButton(chevron: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                nameAndValue
                if chevron {
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .controllerAccessibilityActionTarget(id: navigationID, label: name, action: action)
    }

    private var nameText: some View {
        Text(name)
            .foregroundStyle(OverlayTheme.textPrimary)
            .layoutPriority(1)
    }

    private var trailingValue: some View {
        Text(value)
            .foregroundStyle(OverlayTheme.textSecondary)
            .monospacedDigit()
            .lineLimit(1)
            .truncationMode(.tail)
            .layoutPriority(-1)
    }

    private var resetButton: some View {
        let label = model.scope == .game ? String(localized: "Use the All games value for \(name)") : String(localized: "Reset \(name) to default")
        return Button {
            Task { await model.reset(leaf) }
        } label: {
            Image(systemName: "arrow.uturn.backward")
                .foregroundStyle(OverlayTheme.textSecondary)
                .frame(minWidth: 36, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    // MARK: - Markers

    @ViewBuilder private func markerView(_ marker: SettingMarker) -> some View {
        switch marker {
        case .changed: dotMarker(String(localized: "Changed"))
        case .thisGame: dotMarker(String(localized: "This game"))
        case .fromDatabase: glyphMarker("cloud", String(localized: "From database"), OverlayTheme.textSecondary)
        case .gameUses(let value): Text("This game uses \(value)").foregroundStyle(OverlayTheme.textSecondary)
        case .required: glyphMarker("lock.fill", String(localized: "Required on iOS"), OverlayTheme.textSecondary)
        case .nextBoot: glyphMarker("arrow.clockwise", String(localized: "Applies next boot"), OverlayTheme.warm)
        }
    }

    private func dotMarker(_ text: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(accentColour).frame(width: 6, height: 6)
            Text(text).foregroundStyle(OverlayTheme.textSecondary)
        }
    }

    private func glyphMarker(_ systemImage: String, _ text: String, _ colour: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
            Text(text)
        }
        .foregroundStyle(colour)
    }

    // MARK: - Changes

    // The settings-toggle smoke scene calls the same setter.
    private var toggleBinding: Binding<Bool> {
        Binding(get: { value == "true" },
                set: { on in Task { await model.set(leaf, value: on ? "true" : "false") } })
    }

    // Nothing is sent at an end.
    private func step(_ delta: Int, in range: ClosedRange<Int>) {
        guard let current = Int(value) else { return }
        let next = min(max(current + delta, range.lowerBound), range.upperBound)
        guard next != current else { return }
        Task { await model.set(leaf, value: String(next)) }
    }

    private var keyboard: UIKeyboardType {
        switch leaf.type {
        case "uint": return .numberPad
        case "int": return (Int(leaf.min) ?? 0) < 0 ? .numbersAndPunctuation : .numberPad
        case "float": return .decimalPad
        default: return .default
        }
    }

    // Refused here before any bridge call; anything in range still goes to the leaf's own parser.
    private func submit() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard Self.inRange(leaf, text) else {
            DispatchQueue.main.async { editing = true }
            return
        }
        Task { await model.set(leaf, value: text) }
    }

    /// The whole text when there is only one sentence.
    static func firstSentence(_ text: String) -> String {
        let ends = [text.range(of: ". "), text.range(of: "\n")].compactMap { $0 }
        guard let end = ends.min(by: { $0.lowerBound < $1.lowerBound }) else { return text }
        let cut = text[..<end.lowerBound]
        return String(text[end.lowerBound] == "." ? cut + "." : cut)
    }

    static func rangeText(_ leaf: SettingLeaf) -> String? {
        guard ["int", "uint", "float"].contains(leaf.type), !leaf.min.isEmpty, !leaf.max.isEmpty else { return nil }
        return String(localized: "Enter a number from \(leaf.min) to \(leaf.max).")
    }

    static func inRange(_ leaf: SettingLeaf, _ text: String) -> Bool {
        guard rangeText(leaf) != nil else { return true }
        guard let number = Double(text), let lo = Double(leaf.min), let hi = Double(leaf.max) else { return false }
        return number >= lo && number <= hi
    }
}
