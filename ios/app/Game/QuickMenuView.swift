// SPDX-License-Identifier: GPL-2.0-only
// The in-game quick menu over a paused game. GameScreenView owns every action; this lays out rows and calls back.

import SwiftUI

struct QuickMenuView: View {
    struct Actions {
        var resume: () -> Void
        var saveState: () -> Void
        var loadState: () -> Void
        var exit: () -> Void
        var settings: () -> Void
        var report: () -> Void
        var screenshot: () -> Void
        var controllers: () -> Void
        var changeDisc: () -> Void
        // The XMB's Change Disc: this game closes and another disc starts. The quick menu has only the swap.
        var bootDisc: () -> Void = {}
        var record: () -> Void
        var portal: () -> Void
        // A menu that never shows the row (the smoke scenes' own menus) leaves it empty.
        var friends: () -> Void = {}
        // nil while no touch pad is on screen, which hides the row.
        var touchLayout: (() -> Void)? = nil
    }

    static let controllerScope = "game.quickmenu"
    // Resume and Exit are the footer's buttons, under the rows, with the same ids.
    static let rowIDs = ["quickmenu.resume", "quickmenu.save", "quickmenu.load", "quickmenu.exit", "quickmenu.friends"]

    let title: String
    // The quick slot holds a file.
    let canLoad: Bool
    // A save, a load or a stop is in flight.
    let busy: Bool
    let actions: Actions
    var controllerInput: MenuControllerInputRouter? = nil
    var slotPanel: SaveStatesPanel? = nil
    var settingsPanel: InGameSettingsPanel? = nil
    // The game takes a disc swap right now, as read when the menu opened.
    var discSwap: Bool = false
    var recording: Bool = false
    // nil while this boot does not use RPCN.
    var onlineLine: String? = nil
    var signedIn: Bool = false
    var invites: Int = 0
    var friendsPanel: FriendsPanel? = nil

    static let liveSettings: [Rpcs3LiveSetting] = [.volume, .resolutionScale, .frameLimit, .perfOverlay]
    // Frame Limit cycles through the ABI's codes in this order: Auto, 30, 50, 60, Display rate.
    static let frameLimits = [0, 30, 50, 60, 1]
    // The values as the core reports them, by Rpcs3LiveSetting raw value.
    @State private var live: [Int: Int] = [:]
    @State private var confirmingExit = false
    // A phone on its side or the Duo's tabletop leaf: the title goes slim so the rows keep the height.
    @Environment(\.overlayShortCard) private var shortCard
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.uiCriticalTextColour) private var criticalTextColour
    // A wide card gets two columns too, and in book pose they sit either side of the fold.
    @Environment(\.overlayWideCard) private var wideCard
    @Environment(\.overlayBookFold) private var bookFold
    private var session: EmulatorSession { EmulatorSession.shared }

    var body: some View {
        if let slotPanel {
            slotPanel
        } else if let settingsPanel {
            settingsPanel
        } else if let friendsPanel {
            friendsPanel
        } else {
            menu
        }
    }

    private var menu: some View {
        OverlayPanelScaffold {
            // Title and footer are pinned, so Resume and Exit never scroll away; only the cards scroll.
            VStack(spacing: 0) {
                pinned {
                    header
                    hairline
                }
                ViewThatFits(in: .vertical) {
                    cards
                    // Where they scroll, the indicator flashes on opening to say so.
                    ScrollView { cards }
                        .scrollIndicatorsFlash(onAppear: true)
                }
                pinned {
                    hairline
                    footer
                }
            }
            .environment(\.overlayCompact, twoColumns)
        }
        // Exit already writes the autosave before it stops; this only catches a mis-tap.
        .alert("Exit to the library?", isPresented: $confirmingExit) {
            Button("Exit", role: .destructive, action: actions.exit)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your place is saved automatically first.")
        }
        .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: Self.controllerScope,
                                           directionalLinks: controllerLinks,
                                           preferredInitialFocusLabel: Self.rowIDs[0],
                                           declaredTargetOrder: controllerOrder)
        // As the menu opens, and again after every override, so a row shows what the core holds.
        .task(id: session.liveOverrides) {
            for which in Self.liveSettings { live[which.rawValue] = await session.liveValue(which) }
        }
    }

    // With the cards' corners this keeps them concentric with the outer card's.
    private static let gutter: CGFloat = 12
    // What the live settings need beside the actions for "Resolution Scale" to keep to one line.
    private static let settingsWidth: CGFloat = 360

    private var twoColumns: Bool { shortCard || wideCard || bookFold != nil }

    // At accessibility sizes the title and the footer stop growing, so the rows between them keep the height.
    private func pinned<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0, content: content)
            .dynamicTypeSize(DynamicTypeSize.xSmall...(shortCard ? .xxxLarge : .accessibility5))
            .frame(maxWidth: bookFold?.lowerBound ?? .infinity, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // Marks the title and the footer as fixed while the rows scroll.
    private var hairline: some View {
        OverlayTheme.separator.frame(height: 0.5)
    }

    private var header: some View {
        OverlayHeader(systemImage: "pause.circle.fill", title: title, subtitle: onlineLine, compact: shortCard,
                      inline: shortCard, rowGutter: Self.gutter)
    }

    @ViewBuilder private var cards: some View {
        if twoColumns {
            HStack(alignment: .top, spacing: bookFold.map { $0.upperBound - $0.lowerBound + 2 * Self.gutter } ?? Self.gutter) {
                VStack(spacing: Self.gutter) {
                    actionsCard
                    captureCard
                }
                // Top-aligned: the stack hands the shorter column the taller one's height.
                .frame(minWidth: leftColumnWidth?.lowerBound, maxWidth: leftColumnWidth?.upperBound, alignment: .top)
                .layoutPriority(leftColumnWidth == nil ? 0 : 1)
                .accessibilityElement(children: .contain)
                VStack(spacing: Self.gutter) {
                    settingsCard
                    sessionCard
                }
                .frame(minWidth: bookFold == nil && shortCard ? Self.settingsWidth : nil, alignment: .top)
                .accessibilityElement(children: .contain)
            }
            .padding(Self.gutter)
        } else {
            VStack(spacing: Self.gutter) {
                actionsCard
                settingsCard
                captureCard
                sessionCard
            }
            .padding(Self.gutter)
        }
    }

    // nil on a wide card, where the two columns share the width evenly.
    private var leftColumnWidth: ClosedRange<CGFloat>? {
        if let fold = bookFold {
            let width = max(0, fold.lowerBound - 2 * Self.gutter)
            return width...width
        }
        return shortCard ? 200...300 : nil
    }

    private var actionsCard: some View {
        OverlaySectionCard {
            OverlayActionRow(controllerNavigationID: Self.rowIDs[1], label: "Save State",
                             systemImage: "square.and.arrow.down", action: actions.saveState)
                .disabled(busy)
            OverlayActionRow(controllerNavigationID: Self.rowIDs[2], label: "Load State",
                             systemImage: "clock.arrow.circlepath", action: actions.loadState)
                .disabled(busy || !canLoad)
            OverlayActionRow(controllerNavigationID: "quickmenu.controllers", label: "Controllers", systemImage: "gamecontroller",
                             action: actions.controllers)
                .disabled(busy)
            if let touchLayout = actions.touchLayout {
                OverlayActionRow(controllerNavigationID: "quickmenu.touchlayout", label: "Edit Touch Layout", systemImage: "hand.tap",
                                 action: touchLayout)
                    .disabled(busy)
            }
        }
    }

    private var settingsCard: some View {
        // In two columns uncaptioned, so its rows line up with the actions' beside it.
        OverlaySectionCard(title: twoColumns ? nil : String(localized: "Settings")) {
            LiveStepRow(navigationID: "quickmenu.volume", label: "Volume", systemImage: "speaker.wave.2.fill",
                        value: percent(.volume), down: { step(.volume, by: -10, in: 0...200) },
                        up: { step(.volume, by: 10, in: 0...200) })
            LiveStepRow(navigationID: "quickmenu.scale", label: "Resolution Scale", systemImage: "arrow.up.left.and.arrow.down.right",
                        value: percent(.resolutionScale), down: { step(.resolutionScale, by: -25, in: 25...800) },
                        up: { step(.resolutionScale, by: 25, in: 25...800) })
            OverlayActionRow(controllerNavigationID: "quickmenu.framelimit", label: "Frame Limit", systemImage: "speedometer",
                             trailingValue: frameLimitText, keepsTrailingValue: true, action: nextFrameLimit)
            OverlayToggleRow(controllerNavigationID: "quickmenu.perfoverlay", label: "Performance Overlay",
                             systemImage: "chart.bar.xaxis", isOn: perfOverlay)
            OverlayActionRow(controllerNavigationID: "quickmenu.settings", label: "All Settings…", systemImage: "gearshape",
                             action: actions.settings)
        }
        .disabled(busy)
    }

    private var captureCard: some View {
        OverlaySectionCard {
            OverlayActionRow(controllerNavigationID: "quickmenu.screenshot", label: "Screenshot", systemImage: "camera",
                             action: actions.screenshot)
                .disabled(busy)
            OverlayActionRow(controllerNavigationID: "quickmenu.record", label: recording ? "Stop Recording" : "Record",
                             systemImage: recording ? "stop.circle" : "record.circle", action: actions.record)
                .disabled(busy)
            OverlayActionRow(controllerNavigationID: "quickmenu.portal", label: "Portal", systemImage: "circle.hexagongrid",
                             action: actions.portal)
                .disabled(busy)
            if signedIn {
                OverlayActionRow(controllerNavigationID: "quickmenu.friends", label: "Friends", systemImage: "person.2",
                                 trailingValue: RpcnSession.invitesValue(count: invites), keepsTrailingValue: true,
                                 action: actions.friends)
                    .disabled(busy)
            }
        }
    }

    private var sessionCard: some View {
        OverlaySectionCard {
            if discSwap {
                OverlayActionRow(controllerNavigationID: "quickmenu.changedisc", label: "Change Disc", systemImage: "opticaldisc",
                                 action: actions.changeDisc)
                    .disabled(busy)
            }
            OverlayActionRow(controllerNavigationID: "quickmenu.report", label: "Report a Problem",
                             systemImage: "exclamationmark.bubble", action: actions.report)
                .disabled(busy)
        }
    }

    private var footer: some View {
        HStack(spacing: Self.gutter) {
            footerButton(Self.rowIDs[3], label: "Exit", systemImage: "xmark.circle.fill", colour: criticalTextColour,
                         filled: false, action: { confirmingExit = true })
                .disabled(busy)
            footerButton(Self.rowIDs[0], label: "Resume", systemImage: "play.fill", colour: accentColour, filled: true,
                         action: actions.resume)
        }
        .padding(.horizontal, Self.gutter)
        .padding(.vertical, 8)
    }

    private func footerButton(_ id: String, label: LocalizedStringResource, systemImage: String, colour: Color, filled: Bool,
                              action: @escaping () -> Void) -> some View {
        let name = String(localized: label)
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        return Button(action: action) {
            Label(name, systemImage: systemImage)
                .font(.headline)
                .lineLimit(1)
                .foregroundStyle(filled ? Color.white : colour)
                .padding(.horizontal, 16)
                .frame(maxWidth: filled ? .infinity : nil, minHeight: 44)
                .background(colour.opacity(filled ? 1 : 0.16), in: shape)
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .controllerAccessibilityActionTarget(id: id, label: name, focusedColor: filled ? Color.white : colour,
                                             focusedNeonCornerRadius: 14, action: action)
    }

    // Only rows drawn now, or a press would seek a row that is not there.
    private var controllerOrder: [String] {
        let actions = [Self.rowIDs[1], Self.rowIDs[2], "quickmenu.controllers"]
            + (self.actions.touchLayout != nil ? ["quickmenu.touchlayout"] : [])
        let capture = ["quickmenu.screenshot", "quickmenu.record", "quickmenu.portal"] + (signedIn ? [Self.rowIDs[4]] : [])
        let settings = ["quickmenu.volume.down", "quickmenu.volume.up", "quickmenu.scale.down", "quickmenu.scale.up",
                        "quickmenu.framelimit", "quickmenu.perfoverlay", "quickmenu.settings"]
        let session = (discSwap ? ["quickmenu.changedisc"] : []) + ["quickmenu.report"]
        let footer = [Self.rowIDs[3], Self.rowIDs[0]]
        return (twoColumns ? actions + capture + settings + session : actions + settings + capture + session) + footer
    }

    // Up from the top of the right column and Down from Exit stop rather than jump across.
    private var controllerLinks: [ControllerAccessibilityDirectionalLink] {
        typealias Link = ControllerAccessibilityDirectionalLink
        let stop = Link.navigationBoundary
        var links = [Link(fromLabel: Self.rowIDs[3], direction: .down, toLabel: stop),
                     Link(fromLabel: Self.rowIDs[0], direction: .up, toLabel: "quickmenu.report")]
        if twoColumns {
            let leftFoot = signedIn ? Self.rowIDs[4] : "quickmenu.portal"
            links += [Link(fromLabel: leftFoot, direction: .down, toLabel: Self.rowIDs[3]),
                      Link(fromLabel: Self.rowIDs[3], direction: .up, toLabel: leftFoot),
                      Link(fromLabel: "quickmenu.report", direction: .down, toLabel: Self.rowIDs[0]),
                      Link(fromLabel: "quickmenu.volume.down", direction: .up, toLabel: stop)]
        }
        return links
    }

    private func percent(_ which: Rpcs3LiveSetting) -> String {
        live[which.rawValue].map { "\($0) %" } ?? "–"
    }

    private var frameLimitText: String {
        switch live[Rpcs3LiveSetting.frameLimit.rawValue] {
        case 0?: return String(localized: "Auto")
        case 1?: return String(localized: "Display rate")
        case let fps? where fps > 0: return "\(fps)"
        case _?: return String(localized: "Other")
        case nil: return "–"
        }
    }

    // A limit outside the cycle (one a configuration file asked for) goes to Auto.
    private func nextFrameLimit() {
        guard let current = live[Rpcs3LiveSetting.frameLimit.rawValue] else { return }
        let index = Self.frameLimits.firstIndex(of: current).map { ($0 + 1) % Self.frameLimits.count } ?? 0
        let next = Self.frameLimits[index]
        Task { await session.setLive(.frameLimit, next) }
    }

    private var perfOverlay: Binding<Bool> {
        Binding(get: { live[Rpcs3LiveSetting.perfOverlay.rawValue] == 1 },
                set: { on in Task { await session.setLive(.perfOverlay, on ? 1 : 0) } })
    }

    // Kept inside the setting's range; nothing is sent at an end of the range.
    private func step(_ which: Rpcs3LiveSetting, by delta: Int, in range: ClosedRange<Int>) {
        guard let current = live[which.rawValue] else { return }
        let next = min(max(current + delta, range.lowerBound), range.upperBound)
        guard next != current else { return }
        Task { await session.setLive(which, next) }
    }
}

// At accessibility text sizes the value and its buttons move under the label, the HIG's stacked layout.
struct LiveStepRow: View {
    let navigationID: String
    let label: String
    let systemImage: String
    let value: String
    let down: () -> Void
    let up: () -> Void
    @Environment(\.uiAccentColour) private var accentColour
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    // A literal label is a String Catalog key; a String label (a core setting's name) is shown as written.
    init(navigationID: String, label: LocalizedStringResource, systemImage: String, value: String,
         down: @escaping () -> Void, up: @escaping () -> Void) {
        self.init(navigationID: navigationID, label: String(localized: label), systemImage: systemImage, value: value,
                  down: down, up: up)
    }

    @_disfavoredOverload
    init<S: StringProtocol>(navigationID: String, label: S, systemImage: String, value: String,
                            down: @escaping () -> Void, up: @escaping () -> Void) {
        self.navigationID = navigationID
        self.label = String(label)
        self.systemImage = systemImage
        self.value = value
        self.down = down
        self.up = up
    }

    var body: some View {
        HStack(spacing: OverlayTheme.rowIconSpacing) {
            Image(systemName: systemImage)
                .overlayRowIconColumn()
                .foregroundStyle(accentColour)
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .foregroundStyle(OverlayTheme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: OverlayTheme.rowIconSpacing) {
                        valueText
                        Spacer(minLength: 0)
                        steppers
                    }
                }
            } else {
                // Wraps to a second line rather than shrinking, or cutting to "Resolution Sc…" on the Duo.
                Text(label)
                    .foregroundStyle(OverlayTheme.textPrimary)
                    .lineLimit(2)
                Spacer(minLength: 0)
                valueText
                steppers
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 44)
    }

    private var valueText: some View {
        Text(value)
            .foregroundStyle(OverlayTheme.textSecondary)
            .monospacedDigit()
            .lineLimit(1)
            .fixedSize()
    }

    // Each glyph sits at its button's trailing edge, so the + lines up with the switches' ends.
    private var steppers: some View {
        HStack(spacing: 8) {
            stepButton("minus.circle.fill", String(localized: "Lower \(label)"), navigationID + ".down", down)
            stepButton("plus.circle.fill", String(localized: "Raise \(label)"), navigationID + ".up", up)
        }
    }

    private func stepButton(_ image: String, _ name: String, _ id: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: image)
                .font(.title2)
                .foregroundStyle(accentColour)
                .frame(minWidth: 44, minHeight: 44, alignment: .trailing)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name)
        .controllerAccessibilityActionTarget(id: id, label: name, action: action)
    }
}
