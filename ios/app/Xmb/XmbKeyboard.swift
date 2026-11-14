// SPDX-License-Identifier: GPL-2.0-only
// The XMB's on-screen keyboard, its keys and its panel, ported from the XMB half of Android's LibraryKeyboard.kt.

import Foundation

struct XmbKeyboard: Equatable {
    enum Key: Equatable {
        case character(Character)
        case backspace
        case space
        case shift
        case clear
        case done
    }

    /// What a press did, for its sound and for closing the keyboard.
    enum Result: Equatable {
        /// A key was pressed: the text or the shift changed.
        case typed
        /// The field's own typing, which Android leaves silent.
        case edited
        case moved
        /// The cursor is at an edge, or the press is not the keyboard's.
        case refused
        case done(String)
        case cancelled
    }

    /// A touch on the panel, or typing in its field.
    enum Touch: Equatable {
        case key(row: Int, column: Int)
        case press(Key)
        case paste(String)
        case field(String)
        case cancel
    }

    /// The console's layout: the characters in a block, and the keys that do something down the right, one a row.
    static let rows: [[Key]] = zip(["1234567890", "qwertyuiop", "asdfghjkl:", "zxcvbnm./-", "@_!?#&'+=~"],
                                   [Key.backspace, .space, .shift, .clear, .done]).map { $0.map(Key.character) + [$1] }

    let title: String
    /// A password: the field shows dots.
    let secure: Bool
    private(set) var text: String
    private(set) var row = 0
    private(set) var column = 0
    /// Caps lock: letters type and show in capitals until Shift is pressed again.
    private(set) var shifted = false

    init(title: String, text: String = "", secure: Bool = false) {
        self.title = title
        self.text = text
        self.secure = secure
    }

    var focused: Key { Self.rows[row][column] }

    /// What a character key types and shows.
    func typed(_ character: Character) -> String {
        shifted ? character.uppercased() : String(character)
    }

    mutating func move(dx: Int, dy: Int) -> Result {
        let before = (row, column)
        row = min(max(row + dy, 0), Self.rows.count - 1)
        column = min(max(column + dx, 0), Self.rows[row].count - 1)
        return (row, column) == before ? .refused : .moved
    }

    mutating func press(_ key: Key) -> Result {
        switch key {
        case .character(let character): text += typed(character)
        case .space: text += " "
        case .backspace: if !text.isEmpty { text.removeLast() }
        case .clear: text = ""
        case .shift: shifted.toggle()
        case .done: return .done(text)
        }
        return .typed
    }

    /// The clipboard goes in as one line: a code or an address copied from a message.
    mutating func paste(_ clipboard: String) -> Result {
        text += clipboard.replacingOccurrences(of: "\\s*[\\r\\n]+\\s*", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return .typed
    }

    mutating func apply(_ touch: Touch) -> Result {
        switch touch {
        case .key(let row, let column):
            guard Self.rows.indices.contains(row), Self.rows[row].indices.contains(column) else { return .refused }
            // The cursor goes to a tapped key.
            self.row = row
            self.column = column
            return press(focused)
        case .press(let key):
            return press(key)
        case .paste(let clipboard):
            return paste(clipboard)
        case .field(let typed):
            text = typed.replacingOccurrences(of: "\n", with: "")
            return .edited
        case .cancel:
            return .cancelled
        }
    }

    /// The pad as Android maps it: the D-pad moves the cursor, Cross presses the key under it and Circle closes.
    /// Triangle (Space) and Square (Backspace) both arrive as `.options`, which is Backspace here. L1 and R1 do nothing.
    mutating func apply(_ action: XmbAction) -> Result {
        switch action {
        case .move(let dx, let dy): return move(dx: dx, dy: dy)
        case .confirm: return press(focused)
        case .back: return .cancelled
        case .options, .square: return press(.backspace)
        case .page, .column, .focus, .focusAndConfirm, .focusAndOptions, .panelRow, .rangeAt: return .refused
        }
    }
}

// The host suite compiles the model above on the Mac, where there is no UIKit.
#if canImport(UIKit)
import GameController
import SwiftUI
import UIKit

extension XmbKeyboard {
    /// The pad with Triangle and Square told apart, as Android has them: Triangle is Space and Square is Backspace.
    /// Start is Done and Select pastes.
    mutating func apply(_ command: MenuControllerCommand) -> Result {
        switch command {
        case .toggleFavorite: return press(.space)
        case .showContextMenu: return press(.backspace)
        case .start: return press(.done)
        case .select: return paste(Self.clipboard())
        default: return apply(XmbAction(command))
        }
    }

    /// What the clipboard holds, read only when Paste or Select is pressed.
    static func clipboard() -> String { UIPasteboard.general.string ?? "" }
}

/// The keyboard drawn as the console's: a dark glass panel over the dimmed XMB, the text in a light field at its
/// head, the keys as soft panes with the one under the cursor lit, and the pad's guide along the foot.
struct XmbKeyboardView: View {
    let keyboard: XmbKeyboard
    let firmware: XmbFirmware
    let m: XmbMetrics
    let touch: (XmbKeyboard.Touch) -> Void

    /// The field is the system's own: after a touch on it, or from the start with a real keyboard attached.
    @State private var typing = false
    @FocusState private var fieldFocused: Bool

    private static let ink = Color(red: 0.063, green: 0.063, blue: 0.078)

    var body: some View {
        // Android sizes this panel in dp, which are points here; a short screen scales it down to fit.
        let k = min(1, m.height / 420)
        let width = 0.86 * m.width
        // Ten characters and one wider key a row, 5 between keys and 6 more before the last.
        let keyWidth = (width - 36 * k - 61 * k) / 12.4
        ZStack {
            // The XMB behind is dimmed and takes no taps.
            Color.black.opacity(0.55)
                .contentShape(Rectangle())
                .onTapGesture {}
            VStack(alignment: .leading, spacing: 7 * k) {
                Text(verbatim: keyboard.title)
                    .font(firmware.font(size: 14 * k))
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(1)
                field(k)
                    .padding(.bottom, 11 * k)
                ForEach(Array(XmbKeyboard.rows.enumerated()), id: \.offset) { row, keys in
                    HStack(spacing: 5 * k) {
                        ForEach(Array(keys.enumerated()), id: \.offset) { column, key in
                            // The keys that do something stand apart down the right, as the console's do.
                            let wide = column == keys.count - 1
                            keyCap(key, row: row, column: column, width: wide ? 2.4 * keyWidth : keyWidth, k: k)
                                .padding(.leading, wide ? 11 * k : 0)
                        }
                    }
                }
                guide(k)
            }
            .padding(.horizontal, 18 * k)
            .padding(.vertical, 14 * k)
            .frame(width: width)
            .background(LinearGradient(colors: [Color(red: 0.188, green: 0.188, blue: 0.212, opacity: 0.9),
                                                Color(red: 0.078, green: 0.078, blue: 0.094, opacity: 0.9)],
                                       startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: 10 * k))
            .overlay(RoundedRectangle(cornerRadius: 10 * k).strokeBorder(.white.opacity(0.22), lineWidth: 1))
        }
        .onAppear { typing = GCKeyboard.coalesced != nil }
    }

    // MARK: - The field

    @ViewBuilder private func field(_ k: CGFloat) -> some View {
        let text = Binding(get: { keyboard.text }, set: { touch(.field($0)) })
        Group {
            if typing {
                Group {
                    if keyboard.secure {
                        SecureField("", text: text)
                    } else {
                        TextField("", text: text)
                    }
                }
                .focused($fieldFocused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit { touch(.press(.done)) }
                .tint(Self.ink)
                .onAppear { fieldFocused = true }
            } else {
                // Drawn here until the field is touched: a field in focus would bring the system keyboard up, where
                // Android can hold the caret without it.
                HStack(spacing: 1) {
                    Text(verbatim: keyboard.secure ? String(repeating: "\u{2022}", count: keyboard.text.count) : keyboard.text)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Rectangle().frame(width: 1.5 * k, height: 22 * k)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
                .onTapGesture { typing = true }
            }
        }
        .font(firmware.font(size: 19 * k))
        .foregroundStyle(Self.ink)
        .padding(.horizontal, 10 * k)
        .padding(.vertical, 7 * k)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 4 * k))
    }

    // MARK: - The keys

    private func label(_ key: XmbKeyboard.Key) -> String {
        switch key {
        case .character(let character): return keyboard.typed(character)
        case .space: return String(localized: "Space")
        case .backspace: return "\u{232B}"
        case .clear: return String(localized: "Clear")
        case .done: return String(localized: "Done")
        case .shift: return "\u{21E7}"
        }
    }

    /// What VoiceOver calls a key: its word, not its glyph.
    private func spoken(_ key: XmbKeyboard.Key) -> String {
        switch key {
        case .backspace: return String(localized: "Delete")
        case .shift: return String(localized: "Shift")
        default: return label(key)
        }
    }

    private func keyCap(_ key: XmbKeyboard.Key, row: Int, column: Int, width: CGFloat, k: CGFloat) -> some View {
        let selected = keyboard.row == row && keyboard.column == column
        let locked = key == .shift && keyboard.shifted
        let fill: [Double] = selected ? [0.62, 0.34] : locked ? [0.30, 0.18] : [0.14, 0.06]
        let label = label(key)
        let shape = RoundedRectangle(cornerRadius: 5 * k)
        return Button { touch(.key(row: row, column: column)) } label: {
            Text(verbatim: label)
                .font(firmware.font(size: (label.count > 1 ? 14 : 18) * k))
                .foregroundStyle(selected ? Color(red: 0.047, green: 0.047, blue: 0.063) : .white)
                .lineLimit(1)
                .frame(width: width, height: 44 * k)
                .background(LinearGradient(colors: fill.map { Color.white.opacity($0) }, startPoint: .top, endPoint: .bottom), in: shape)
                .overlay(shape.strokeBorder(.white.opacity(selected ? 0.85 : 0.16), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: spoken(key)))
        .accessibilityAddTraits(locked ? .isSelected : [])
    }

    // MARK: - The guide

    /// What the pad's buttons do here. Each also does its job when tapped, so Paste is a tap away on a phone.
    private func guide(_ k: CGFloat) -> some View {
        HStack(spacing: 18 * k) {
            hint("\u{25B3}", Color(red: 0.239, green: 0.863, blue: 0.518), label(.space), k) { touch(.press(.space)) }
            hint("\u{25A1}", Color(red: 0.910, green: 0.545, blue: 0.835), label(.backspace), spoken: spoken(.backspace), k) {
                touch(.press(.backspace))
            }
            hint("\u{25CB}", Color(red: 1, green: 0.353, blue: 0.353), String(localized: "Back"), k) { touch(.cancel) }
            hint("SELECT", .white, String(localized: "Paste"), k) { touch(.paste(XmbKeyboard.clipboard())) }
            hint("START", .white, label(.done), k) { touch(.press(.done)) }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.top, 6 * k)
    }

    private func hint(_ button: String, _ color: Color, _ label: String, spoken: String? = nil, _ k: CGFloat,
                      _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Text(verbatim: button)
                    .font(firmware.font(size: 14 * k))
                    .foregroundStyle(color)
                Text(verbatim: " " + label)
                    .font(firmware.font(size: 13 * k))
                    .foregroundStyle(.white.opacity(0.85))
            }
            .lineLimit(1)
            // Mostly downward, as the keys sit just above.
            .xmbTouchTarget(above: 6, below: 24)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: spoken ?? label))
    }
}
#endif
