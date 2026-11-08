// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI

// MARK: - Overlay Theme

/// In-game overlay design tokens. Scoped to overlays presented over gameplay (the pause menu and
/// Per-Game Settings); this is NOT a global app theme.
///
/// The clear glass shell uses lightly tinted section cards for grouping while allowing paused
/// gameplay to remain visible. The gameplay scrim stays a plain tinted color and never uses
/// Material. The user-selected semantic accent is injected through the SwiftUI environment; red remains destructive only.
enum OverlayTheme {
    // MARK: Surfaces: opaque graphite ladder (darkest -> lightest)

    /// Panel/shell root surface (darkest). Replaces `Color(.systemBackground)` on overlays.
    static let shell = Color(red: 0.110, green: 0.122, blue: 0.149)          // #1C1F26
    /// Section/card surface, one step lighter than the shell for grouping depth.
    static let card = Color(red: 0.149, green: 0.165, blue: 0.200)           // #262A33
    /// Hairline separator / divider stroke between rows and above a footer.
    static let separator = Color(red: 0.227, green: 0.247, blue: 0.290)      // #3A3F4A
    /// Top stop of the shell gradient, a step lighter so the panel reads as lit from above.
    static let shellGradientTop = Color(red: 0.149, green: 0.165, blue: 0.200) // #262A33

    // MARK: Gameplay scrim: lighter, controlled, decoupled from panel opacity

    /// Tinted near-black base for the gameplay dim (NOT pure `Color.black`). Apply at a controlled
    /// opacity, see below, so the dim only signals paused while the panel carries the look.
    static let scrimBase = Color(red: 0.039, green: 0.047, blue: 0.063)      // #0A0C10
    static let scrimPad = 0.30
    static let scrimPhoneLandscape = 0.34
    static let scrimPhonePortrait = 0.32
    /// Reduce-Transparency fallbacks (heavier, so RT users still get a strong dim).
    static let scrimPadReduceTransparency = 0.55
    static let scrimPhoneLandscapeReduceTransparency = 0.58
    static let scrimPhonePortraitReduceTransparency = 0.56

    // MARK: Accents: used sparingly

    /// Status / warning ONLY (e.g. "Start this game once before saving").
    static let warm = Color(red: 0.941, green: 0.627, blue: 0.125)           // #F0A020

    // MARK: Text

    /// Primary labels, headers, primary button title.
    static let textPrimary = Color(red: 0.925, green: 0.933, blue: 0.949)   // #ECEEF2
    /// Trailing values, captions, subtitles.
    static let textSecondary = Color(red: 0.66, green: 0.69, blue: 0.74)  // #A8B0BD

    // MARK: Glass: controlled frosted chrome (panel shell + cards; never the gameplay scrim)

    static let shellGlassTint: Double = 0.72
    static let cardGlassTint: Double = 0.28
    static let glassTopHighlight = Color.white.opacity(0.10)
    static let cardTopHighlight = Color.white.opacity(0.07)
    static let cardShadow = Color.black.opacity(0.18)

    // MARK: Row metrics: the icon column every overlay row shares

    /// Icon column width at the default text size; rows scale it with Dynamic Type so titles still line up.
    static let rowIconWidth: CGFloat = 22
    static let rowIconSpacing: CGFloat = 12

    // Named, so a header lined up with the rows uses the same padding.
    static func cardPadding(compact: Bool) -> CGFloat { compact ? 8 : 12 }
}

// Grows with Dynamic Type, so a symbol that scales with the text stays inside its column.
private struct OverlayRowIconColumn: ViewModifier {
    var hangsUnderLabel = false
    @ScaledMetric(relativeTo: .body) private var width = OverlayTheme.rowIconWidth

    @ViewBuilder func body(content: Content) -> some View {
        if hangsUnderLabel {
            content.padding(.leading, width + OverlayTheme.rowIconSpacing)
        } else {
            content.frame(width: width)
        }
    }
}

extension View {
    func overlayRowIconColumn() -> some View {
        modifier(OverlayRowIconColumn())
    }

    func overlayRowLabelInset() -> some View {
        modifier(OverlayRowIconColumn(hangsUnderLabel: true))
    }
}

// MARK: - Overlay Components

/// Controlled frosted graphite panel surface: a subtle blur layered under a mostly-opaque graphite
/// tint, so the panel reads as premium frosted glass that holds a stable graphite hue instead of
/// picking up muddy game colors. Falls back to a fully opaque shell under Reduce Transparency.
struct OverlayFrostBackground: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    var body: some View {
        let gradient = LinearGradient(
            colors: [OverlayTheme.shellGradientTop, OverlayTheme.shell],
            startPoint: .top,
            endPoint: .bottom
        )
        if reduceTransparency || colorSchemeContrast == .increased {
            gradient
        } else {
            ZStack {
                Rectangle().fill(.regularMaterial)
                gradient.opacity(OverlayTheme.shellGlassTint)
            }
            .overlay(alignment: .top) {
                OverlayTheme.glassTopHighlight
                    .frame(height: 1)
                    .padding(.horizontal, 1)
                    .padding(.top, 0.5)
            }
        }
    }
}

private struct OverlayCompactKey: EnvironmentKey {
    static let defaultValue: Bool = false
}

private struct OverlayKeyboardOverlapKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var overlayCompact: Bool {
        get { self[OverlayCompactKey.self] }
        set { self[OverlayCompactKey.self] = newValue }
    }

    /// How much of the overlay card the keyboard covers. The panel insets itself by this,
    /// since an inset applied out here would shrink the box it measures itself in.
    var overlayKeyboardOverlap: CGFloat {
        get { self[OverlayKeyboardOverlapKey.self] }
        set { self[OverlayKeyboardOverlapKey.self] = newValue }
    }
}

/// Clear glass overlay shell content. Host this INSIDE `GameOverlayContainer` (which supplies the
/// scrim, bounded card, corner clipping, and shadow); the scaffold is the panel's CONTENT, not the
/// card frame. One shared glass surface avoids stacking a separate material renderer under every
/// section. It also clamps Dynamic Type and scopes the accent tint locally.
struct OverlayPanelScaffold<Content: View>: View {
    private let content: Content
    private let usesRegularGlass: Bool
    @Environment(\.uiAccentColour) private var accentColour

    init(usesRegularGlass: Bool = false, @ViewBuilder content: () -> Content) {
        self.usesRegularGlass = usesRegularGlass
        self.content = content()
    }

    var body: some View {
        content
            .dynamicTypeSize(...DynamicTypeSize.accessibility3)
            .tint(accentColour)
            .glassSurface(
                clear: !usesRegularGlass,
                cornerRadius: 26
            )
    }
}

/// A lighter-graphite grouping card with an optional small caption header. Gives a consistent depth
/// step on the shell and replaces the ad-hoc `secondarySystemBackground` menu-card pattern.
struct OverlaySectionCard<Content: View>: View {
    @Environment(\.overlayCompact) private var compact
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @Environment(\.controllerTextAppearance) private var textAppearance
    private let title: String?
    private let content: Content

    // A literal title is a String Catalog key; a String title is data and is shown as written.
    init(@ViewBuilder content: () -> Content) {
        self.title = nil
        self.content = content()
    }

    init(title: LocalizedStringResource, @ViewBuilder content: () -> Content) {
        self.title = String(localized: title)
        self.content = content()
    }

    @_disfavoredOverload
    init<S: StringProtocol>(title: S?, @ViewBuilder content: () -> Content) {
        self.title = title.map { String($0) }
        self.content = content()
    }

    private var accessible: Bool {
        reduceTransparency || colorSchemeContrast == .increased
    }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 10) {
            if let title {
                Text(title)
                    .font(compact ? .caption2 : .caption)
                    .foregroundStyle(
                        textAppearance.secondaryColor
                            ?? OverlayTheme.textSecondary
                    )
                    .textCase(.uppercase)
                    .padding(.horizontal, 4)
            }
            // Wrap the rows so they are spaced (a bare @ViewBuilder tuple would pack tight).
            VStack(alignment: .leading, spacing: compact ? 5 : 8) {
                content
            }
        }
        .padding(OverlayTheme.cardPadding(compact: compact))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            OverlayTheme.card.opacity(accessible ? 1.0 : OverlayTheme.cardGlassTint),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(OverlayTheme.cardTopHighlight, lineWidth: 1)
                .opacity(accessible ? 0 : 1)
        }
        .shadow(color: OverlayTheme.cardShadow.opacity(accessible ? 0 : 1), radius: 4, x: 0, y: 2)
    }
}

// The small caps caption OverlaySectionCard draws, so every section is captioned one way.
extension View {
    func overlayCaption() -> some View {
        font(.caption)
            .foregroundStyle(OverlayTheme.textSecondary)
            .textCase(.uppercase)
    }
}

// MARK: - Header

/// Command-deck header for an overlay: an accent glyph, a primary title, and an optional subtitle
/// (e.g. the game title, middle-truncated). Blue is used only for the glyph; the title stays
/// `textPrimary` so blue never becomes a row/title color.
struct OverlayHeader: View {
    private let systemImage: String
    private let title: String
    private let subtitle: String?
    private let compact: Bool
    // The slim one-line form a short card's pause menu uses.
    private let inline: Bool
    // The gutter the cards below sit in, when the header lines up with their rows; nil keeps the free layout.
    private let rowGutter: CGFloat?
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.controllerTextAppearance) private var textAppearance
    @Environment(\.overlayCompact) private var compactCards
    // Grows with Dynamic Type; unchanged at the default size.
    @ScaledMetric(relativeTo: .title3) private var glyphSize: CGFloat = 30
    @ScaledMetric(relativeTo: .title3) private var compactGlyphSize: CGFloat = 24

    // A literal title is a String Catalog key; a String title is data and is shown as written.
    init(systemImage: String, title: LocalizedStringResource, subtitle: String? = nil, compact: Bool, inline: Bool = false,
         rowGutter: CGFloat? = nil) {
        self.init(systemImage: systemImage, title: String(localized: title), subtitle: subtitle, compact: compact,
                  inline: inline, rowGutter: rowGutter)
    }

    @_disfavoredOverload
    init<S: StringProtocol>(systemImage: String, title: S, subtitle: String? = nil, compact: Bool, inline: Bool = false,
                            rowGutter: CGFloat? = nil) {
        self.systemImage = systemImage
        self.title = String(title)
        self.subtitle = subtitle
        self.compact = compact
        self.inline = inline
        self.rowGutter = rowGutter
    }

    var body: some View {
        HStack(spacing: rowGutter == nil ? (compact ? 10 : 12) : OverlayTheme.rowIconSpacing) {
            glyph
                .foregroundStyle(accentColour)
                .accessibilityHidden(true)
            // Shared by the one-line and stacked arrangements.
            if inline {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    titleText
                    subtitleText.fixedSize()
                }
            } else {
                VStack(alignment: .leading, spacing: 1) {
                    titleText
                    subtitleText
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, rowGutter.map { $0 + OverlayTheme.cardPadding(compact: compactCards) } ?? (compact ? 18 : 20))
        .padding(.top, inline ? 9 : (compact ? 12 : 18))
        .padding(.bottom, inline ? 9 : (compact ? 6 : 10))
        .accessibilityElement(children: .combine)
        // VoiceOver's headings rotor finds it.
        .accessibilityAddTraits(.isHeader)
    }

    // Row-aligned, the glyph sits in the rows' scaled icon column so it lines up with their icons.
    @ViewBuilder private var glyph: some View {
        if rowGutter != nil {
            Image(systemName: systemImage)
                .font(inline ? .body : .title3)
                .overlayRowIconColumn()
        } else {
            Image(systemName: systemImage)
                .font(.system(size: compact ? compactGlyphSize : glyphSize))
        }
    }

    private var titleText: some View {
        Text(title)
            .font(inline ? .callout : (compact ? .headline : .title3))
            .fontWeight(.semibold)
            .foregroundStyle(
                textAppearance.normalColor
                    ?? OverlayTheme.textPrimary
            )
            .lineLimit(1)
    }

    @ViewBuilder private var subtitleText: some View {
        if let subtitle, !subtitle.isEmpty {
            Text(subtitle)
                .font(inline ? .caption : .subheadline)
                .foregroundStyle(
                    textAppearance.secondaryColor
                        ?? OverlayTheme.textSecondary
                )
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }
}

// MARK: - Rows

/// A fixed-min-height overlay action row that GUARANTEES the main label wins over a trailing
/// value: the label gets `layoutPriority(1)` and the trailing value `layoutPriority(-1)` with tail
/// truncation, so on a narrow width the trailing value elides first while the label stays intact.
/// `keepsTrailingValue` reverses that for a short status such as "In drive".
/// The label is never blue (`textPrimary`); red is used only when `isDestructive`.
// At accessibility sizes the label wraps and the value sits under it instead, so neither is cut short.
struct OverlayActionRow: View {
    private let controllerNavigationID: String?
    private let label: String
    private let systemImage: String?
    private let trailingValue: String?
    private let isDestructive: Bool
    private let keepsTrailingValue: Bool
    private let showsChevron: Bool
    private let action: () -> Void
    @Environment(\.uiCriticalTextColour) private var criticalTextColour
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.controllerTextAppearance) private var textAppearance
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    // A literal label is a String Catalog key; a String label is data and is shown as written.
    init(
        controllerNavigationID: String? = nil,
        label: LocalizedStringResource,
        systemImage: String? = nil,
        trailingValue: String? = nil,
        isDestructive: Bool = false,
        keepsTrailingValue: Bool = false,
        showsChevron: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(controllerNavigationID: controllerNavigationID, label: String(localized: label),
                  systemImage: systemImage, trailingValue: trailingValue, isDestructive: isDestructive,
                  keepsTrailingValue: keepsTrailingValue, showsChevron: showsChevron, action: action)
    }

    @_disfavoredOverload
    init<S: StringProtocol>(
        controllerNavigationID: String? = nil,
        label: S,
        systemImage: String? = nil,
        trailingValue: String? = nil,
        isDestructive: Bool = false,
        keepsTrailingValue: Bool = false,
        showsChevron: Bool = false,
        action: @escaping () -> Void
    ) {
        self.controllerNavigationID = controllerNavigationID
        self.label = String(label)
        self.systemImage = systemImage
        self.trailingValue = trailingValue
        self.isDestructive = isDestructive
        self.keepsTrailingValue = keepsTrailingValue
        self.showsChevron = showsChevron
        self.action = action
    }

    private var title: some View {
        Text(label)
            .controllerFocusedTextColor(
                normal: isDestructive
                    ? criticalTextColour
                    : (textAppearance.normalColor
                        ?? OverlayTheme.textPrimary),
                allowsFocusedBlue: !isDestructive
            )
    }

    private func value(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(
                textAppearance.secondaryColor
                    ?? OverlayTheme.textSecondary
            )
    }

    // The size and weight RootView's game-transition row uses for its own chevron.
    private var chevron: some View {
        Image(systemName: "chevron.right")
            .font(.caption)
            .foregroundStyle(textAppearance.secondaryColor ?? OverlayTheme.textSecondary)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: OverlayTheme.rowIconSpacing) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .overlayRowIconColumn()
                        .foregroundStyle(
                            isDestructive
                                ? criticalTextColour
                                : accentColour
                        )
                }
                // At accessibility sizes the label wraps and its value sits under it, instead of cutting both to one line.
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 2) {
                        title.fixedSize(horizontal: false, vertical: true)
                        if let trailingValue {
                            value(trailingValue).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 0)
                    if showsChevron { chevron }
                } else {
                    title
                        .lineLimit(1)
                        .layoutPriority(keepsTrailingValue ? 0 : 1)
                    Spacer(minLength: 0)
                    if let trailingValue {
                        value(trailingValue)
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .layoutPriority(keepsTrailingValue ? 2 : -1)
                    }
                    if showsChevron { chevron }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .controllerAccessibilityActionTarget(
            id: controllerNavigationID,
            label: label,
            focusedColor: isDestructive ? criticalTextColour : nil,
            action: action
        )
    }
}

/// A toggle row on the graphite card: graphite label + accent switch (accent comes from the
/// scaffold's local `.tint`). Matches `OverlayActionRow` height so toggles and actions align.
struct OverlayToggleRow: View {
    private let controllerNavigationID: String?
    private let label: String
    private let systemImage: String
    @Binding private var isOn: Bool
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.controllerTextAppearance) private var textAppearance
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    // A literal label is a String Catalog key; a String label is data and is shown as written.
    init(
        controllerNavigationID: String? = nil,
        label: LocalizedStringResource,
        systemImage: String,
        isOn: Binding<Bool>
    ) {
        self.init(controllerNavigationID: controllerNavigationID, label: String(localized: label),
                  systemImage: systemImage, isOn: isOn)
    }

    @_disfavoredOverload
    init<S: StringProtocol>(
        controllerNavigationID: String? = nil,
        label: S,
        systemImage: String,
        isOn: Binding<Bool>
    ) {
        self.controllerNavigationID = controllerNavigationID
        self.label = String(label)
        self.systemImage = systemImage
        self._isOn = isOn
    }

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: OverlayTheme.rowIconSpacing) {
                Image(systemName: systemImage)
                    .overlayRowIconColumn()
                    .foregroundStyle(accentColour)
                Text(label)
                    .controllerFocusedTextColor(
                        normal: textAppearance.normalColor
                            ?? OverlayTheme.textPrimary
                    )
                    // At accessibility sizes the label wraps instead of being cut.
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    .layoutPriority(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 44)
        // Quick Menu uses horizontal geometry to cross between its two
        // columns. Register Cross as the toggle action without `.adjustable`
        // traits so Left/Right remain spatial navigation commands.
        .controllerAccessibilityActionTarget(
            id: controllerNavigationID,
            label: label,
            activationFeedback: .toggle(isOn: !isOn),
            action: { isOn.toggle() }
        )
    }
}
