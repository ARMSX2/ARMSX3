// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UIKit
import Observation
import GameController

private struct MenuTabIsActiveEnvironmentKey: EnvironmentKey {
    static let defaultValue = true
}

private struct MenuBackgroundSessionStartEnvironmentKey: EnvironmentKey {
    static let defaultValue = Date()
}

private struct MenuLargeTitleNamespaceEnvironmentKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

private struct MenuLargeTitleMorphActiveEnvironmentKey: EnvironmentKey {
    static let defaultValue = false
}

private struct MenuLargeTitleIsSourceEnvironmentKey: EnvironmentKey {
    static let defaultValue: Bool? = nil
}

private struct MenuTabBarContentMarginEnvironmentKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var menuTabIsActive: Bool {
        get { self[MenuTabIsActiveEnvironmentKey.self] }
        set { self[MenuTabIsActiveEnvironmentKey.self] = newValue }
    }

    var menuBackgroundSessionStart: Date {
        get { self[MenuBackgroundSessionStartEnvironmentKey.self] }
        set { self[MenuBackgroundSessionStartEnvironmentKey.self] = newValue }
    }

    var menuLargeTitleNamespace: Namespace.ID? {
        get { self[MenuLargeTitleNamespaceEnvironmentKey.self] }
        set { self[MenuLargeTitleNamespaceEnvironmentKey.self] = newValue }
    }

    var menuLargeTitleMorphActive: Bool {
        get { self[MenuLargeTitleMorphActiveEnvironmentKey.self] }
        set { self[MenuLargeTitleMorphActiveEnvironmentKey.self] = newValue }
    }

    var menuLargeTitleIsSource: Bool? {
        get { self[MenuLargeTitleIsSourceEnvironmentKey.self] }
        set { self[MenuLargeTitleIsSourceEnvironmentKey.self] = newValue }
    }

    /// Room a list needs below its last row to end just above the menu tab bar.
    var menuTabBarContentMargin: CGFloat {
        get { self[MenuTabBarContentMarginEnvironmentKey.self] }
        set { self[MenuTabBarContentMarginEnvironmentKey.self] = newValue }
    }
}

private struct RootControllerAlertCommandListener: View {
    let controllerInput: MenuControllerInputRouter
    let onCommand: (MenuControllerCommand) -> Void

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onChange(of: controllerInput.latestEvent) { _, event in
                guard let event,
                      event.captureOwner
                        == MenuControllerNavigationCaptureOwner.rootAlert else {
                    return
                }
                onCommand(event.command)
            }
    }
}

/// Shows RootView's prompts in a window above the main one. The window lives only
/// while a prompt is up and never becomes key, so controller events still reach
/// the main window's event host.
private struct RootAlertWindow: UIViewRepresentable {
    let content: AnyView?

    func makeUIView(context: Context) -> RootAlertWindowAnchor {
        RootAlertWindowAnchor()
    }

    func updateUIView(_ anchor: RootAlertWindowAnchor, context: Context) {
        anchor.show(content.map { AnyView($0.environment(\.self, context.environment)) })
    }

    static func dismantleUIView(_ anchor: RootAlertWindowAnchor, coordinator: ()) {
        anchor.show(nil)
    }
}

private final class RootAlertWindowAnchor: UIView {
    private var alertWindow: UIWindow?
    private var content: AnyView?

    func show(_ content: AnyView?) {
        self.content = content
        update()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        update()
    }

    private func update() {
        guard let content else {
            alertWindow?.isHidden = true
            alertWindow = nil
            return
        }
        if let host = alertWindow?.rootViewController as? ARMSX2HostingController<AnyView> {
            host.rootView = content
            return
        }
        guard let scene = window?.windowScene else { return }
        let host = ARMSX2HostingController(rootView: content)
        host.view.backgroundColor = .clear
        host.view.accessibilityViewIsModal = true
        let alertWindow = UIWindow(windowScene: scene)
        alertWindow.windowLevel = .alert
        alertWindow.rootViewController = host
        alertWindow.isHidden = false
        self.alertWindow = alertWindow
    }
}

// The menu's tabs; every tab-count and tab-bar site below reads these.
private let menuTabCount = 3
private let menuTabTitles = [String(localized: "Games"), String(localized: "Install"), String(localized: "Settings")]
private let menuTabSystemImages = ["gamecontroller", "square.and.arrow.down", "gearshape"]
typealias MenuSettingsNavigationPath = [SettingsPane]

private enum RootControllerAlertKind: Equatable {
    case navigationMode(MenuNavigationInputMode)
    case prompt(UUID)

    /// Shown over gameplay too, not only on the menu screens.
    var showsOverGameplay: Bool {
        switch self {
        case .navigationMode, .prompt: true
        }
    }
}

/// A question any screen can ask through RootView's prompt window. SwiftUI's own
/// `.confirmationDialog` is a UIAlertController, which a controller cannot reach.
@MainActor
@Observable
final class ControllerPrompt {
    struct Action {
        let title: String
        var isDestructive = false
        var isCancel = false
        var run: @MainActor () -> Void = {}
    }

    struct Request {
        let id = UUID()
        let title: String
        let message: String
        let actions: [Action]
    }

    static let shared = ControllerPrompt()
    private(set) var request: Request?

    func ask(_ title: String, message: String, actions: [Action]) {
        // A question already up is cancelled first, so its owner's binding is cleared and can ask again.
        if request != nil { answer(nil) }
        request = Request(title: title, message: message, actions: actions)
    }

    /// Runs the chosen action, or the cancel action for nil (Circle, or a tap outside).
    func answer(_ index: Int?) {
        guard let request else { return }
        self.request = nil
        let chosen = index.flatMap { request.actions.indices.contains($0) ? request.actions[$0] : nil }
        (chosen ?? request.actions.first(where: \.isCancel))?.run()
    }
}

extension ControllerPrompt.Action {
    @MainActor static var cancel: Self { .init(title: SettingsStore.shared.localized("Cancel"), isCancel: true) }
    @MainActor static var ok: Self { .init(title: SettingsStore.shared.localized("OK"), isCancel: true) }
}

extension View {
    /// Use in place of `.alert` and `.confirmationDialog`: the question goes to RootView's prompt
    /// window, which touch and the controller can both answer. Any answer clears `isPresented`.
    func controllerPrompt(
        _ title: String,
        isPresented: Binding<Bool>,
        message: String = "",
        actions: [ControllerPrompt.Action]
    ) -> some View {
        onChange(of: isPresented.wrappedValue, initial: true) { _, presented in
            guard presented else { return }
            ControllerPrompt.shared.ask(title, message: message, actions: actions.map { action in
                var action = action
                let run = action.run
                action.run = {
                    run()
                    isPresented.wrappedValue = false
                }
                return action
            })
        }
    }
}

/// Non-interactive, one-shot startup guidance for the controller macros.
/// GameScreenView inserts this beneath its Quick Menu; RootView uses the same
/// view only when Emulation-Only Mode replaces the complete gameplay hierarchy.
/// A pad button the way the connected controller draws it: □ on a DualSense, X on an Xbox pad.
/// Start and Select stay words in a chip, which read better than their icons.
struct ControllerButtonGlyph: View {
    let button: ControllerMacroButton
    let tint: Color
    var symbolFont: Font = .caption.weight(.semibold)
    var chipFont: Font = .caption2.weight(.semibold)

    /// `current` follows the controller the player used last, so the artwork can't come from a
    /// second, idle one.
    static var connectedGamepad: GCExtendedGamepad? {
        if let current = GCController.current?.extendedGamepad { return current }
        return GCController.controllers().lazy.compactMap(\.extendedGamepad).first
    }

    var body: some View {
        if let title = chipTitle {
            Text(title)
                .font(chipFont)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(tint.opacity(0.10), in: Capsule(style: .continuous))
                .overlay {
                    Capsule(style: .continuous)
                        .stroke(tint.opacity(0.22), lineWidth: 0.65)
                }
                .accessibilityLabel(title)
        } else if let element, let symbol = element.sfSymbolsName, !symbol.isEmpty {
            Image(systemName: symbol)
                .font(symbolFont)
                .symbolRenderingMode(.hierarchical)
                .accessibilityLabel(element.localizedName ?? button.title)
        } else {
            Text(shortTitle)
                .font(chipFont.bold())
                .accessibilityLabel(button.title)
        }
    }

    private var chipTitle: String? {
        switch button {
        case .select: SettingsStore.shared.localized("Select")
        case .pause, .start: SettingsStore.shared.localized("Start")
        default: nil
        }
    }

    private var element: GCControllerElement? {
        guard let gamepad = Self.connectedGamepad else { return nil }
        switch button {
        case .select, .pause, .start: return nil
        case .l3: return gamepad.leftThumbstickButton
        case .r3: return gamepad.rightThumbstickButton
        case .l1: return gamepad.leftShoulder
        case .r1: return gamepad.rightShoulder
        case .l2: return gamepad.leftTrigger
        case .r2: return gamepad.rightTrigger
        case .cross: return gamepad.buttonA
        case .circle: return gamepad.buttonB
        case .square: return gamepad.buttonX
        case .triangle: return gamepad.buttonY
        case .dpadUp: return gamepad.dpad.up
        case .dpadDown: return gamepad.dpad.down
        case .dpadLeft: return gamepad.dpad.left
        case .dpadRight: return gamepad.dpad.right
        }
    }

    private var shortTitle: String {
        switch button {
        case .cross: "✕"
        case .circle: "○"
        case .square: "□"
        case .triangle: "△"
        case .dpadUp: "↑"
        case .dpadDown: "↓"
        case .dpadLeft: "←"
        case .dpadRight: "→"
        default: button.title
        }
    }
}

// The controller macros' help strip, which GameScreenView shows.
struct GameplayControllerShortcutHelpOverlay: View {
    let settings: SettingsStore
    let controllerInput: MenuControllerInputRouter?
    /// Clear glass reads as part of the game; under the pause card it needs the card's frost.
    var overPauseMenu = false

    private static let height: CGFloat = 52
    private static let bottomPadding: CGFloat = 6

    // Menu, Save and Load: there is no speed macro on PS3.
    static let tiles: [(title: String, action: ControllerMacroAction)] = [
        (String(localized: "Menu"), .quickMenu), (String(localized: "Save"), .saveGameState), (String(localized: "Load"), .loadGameState),
    ]

    // GameScreenView shows the strip only while a controller is connected, so this view does not gate on one.
    var body: some View {
        shortcutContent
    }

    private var shortcutContent: some View {
        GeometryReader { geometry in
            let accent = themedAccentColor
            HStack(spacing: 0) {
                ForEach(Self.tiles, id: \.title) { tile in
                    shortcutTile(
                        title: settings.localized(tile.title)
                    ) {
                        macroBadge(settings.controllerMacroBinding(for: tile.action))
                    }
                }
            }
            .padding(4)
            .frame(height: Self.height)
            .frame(
                maxWidth: min(
                    420,
                    max(
                        0,
                        geometry.size.width
                            - geometry.safeAreaInsets.leading
                            - geometry.safeAreaInsets.trailing
                            - 20
                    )
                )
            )
            .glassSurface(
                tint: accent.opacity(0.05),
                clear: !overPauseMenu,
                forceClear: !overPauseMenu,
                materializeTransition: true,
                cornerRadius: 28
            )
            .overlay {
                Capsule(style: .continuous)
                    .stroke(
                        Color.primary.opacity(0.13),
                        lineWidth: 0.65
                    )
            }
            .shadow(color: .black.opacity(0.12), radius: 12, y: 5)
            .padding(.bottom, geometry.safeAreaInsets.bottom + Self.bottomPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        }
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            [
                String(localized: "Press \(settings.controllerMacroQuickMenu.title) to open the Quick Menu."),
                // A cleared chord reads as no hotkey.
                settings.controllerMacroSaveGameState.map { String(localized: "Press \($0.title) to save a game state.") }
                    ?? String(localized: "Saving a game state has no hotkey."),
                settings.controllerMacroLoadGameState.map { String(localized: "Press \($0.title) to load a game state.") }
                    ?? String(localized: "Loading a game state has no hotkey."),
            ].joined(separator: " ")
        )
    }

    private var themedAccentColor: Color {
        settings.controllerBottomTabBarColor
    }

    private var themedSecondaryTextColor: Color {
        settings.controllerBottomTabBarUnselectedColor
    }

    // A cleared chord shows as None.
    private func macroBadge(_ binding: ControllerMacroBinding?) -> some View {
        HStack(spacing: 5) {
            if let binding {
                glyph(binding.first)
                plusSymbol
                glyph(binding.second)
            } else {
                Text(settings.localized("None"))
                    .font(.system(size: 10, weight: .semibold, design: .rounded))
            }
        }
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: false)
    }

    private func glyph(_ button: ControllerMacroButton) -> some View {
        ControllerButtonGlyph(
            button: button,
            tint: themedAccentColor,
            symbolFont: .system(size: 16, weight: .semibold),
            chipFont: .system(size: 10, weight: .semibold, design: .rounded)
        )
    }

    @ViewBuilder
    private func shortcutTile<Content: View>(
        title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 2) {
            HStack(spacing: 5) {
                content()
            }
            .foregroundStyle(themedAccentColor)

            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(themedSecondaryTextColor)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity)
    }

    private var plusSymbol: some View {
        Image(systemName: "plus")
            .font(.system(size: 7, weight: .black))
            .foregroundStyle(themedSecondaryTextColor.opacity(0.72))
    }
}

struct RootView: View {
    @State private var appState = AppState.shared
    @State private var settings = SettingsStore.shared
    @State private var frameRates = UIFrameRateSettings.shared
    @State private var gameCoverThemePreview = GameCoverThemePreviewStore.shared
    // XMB mode brings itself up from black, as the console does, in place of the intro. Setup's cover would hide it.
    @State private var showBootSplash = !AppState.shared.automaticGameStartupPending && !SettingsStore.shared.xmbMode
        && SettingsStore.shared.setupFinished && !SetupRules.steps.contains(SettingsStore.shared.setupStep)
    @State private var rootControllerAlertSelection = 0
    // Set by the initialiser, so a caller can hand in the router.
    @State private var menuControllerInput: MenuControllerInputRouter
    @State private var bootSplashInputReleaseTask: Task<Void, Never>?
    @State private var presentedGameplayHelpSessionID: UUID?
    // Theme/background renderer changes are allowed to rebuild MenuTabView,
    // but they must never reset the selected tab or discard a pushed Settings
    // destination. Keep both routing values above that reactive subtree.
    @State private var selectedMenuTab = 0
    @State private var settingsMenuNavigationPath: MenuSettingsNavigationPath = []
    @StateObject private var gameplayHelpBanner =
        TransientBannerController<UUID>(defaultDisplayDuration: 7.0)
    // The background layer observes renderer state directly. Keeping only the
    // host reference here prevents snapshot/visibility publications from
    // invalidating the complete menu hierarchy.
    @State private var backgroundHost = PersistentMenuBackgroundHost()
    // Handed in by the app root or made here; the router too, so its controller focus is observable outside.
    @State private var library: LibraryModel
    // The XMB's state lives here so its focus survives the library's grid and a game.
    @State private var xmbHost: XmbHost
    // The real shape from the first frame, so a landscape iPad never mounts the library for a frame before the XMB.
    @State private var menuLandscape = UIApplication.shared.appWindowScene
        .map { $0.coordinateSpace.bounds.width > $0.coordinateSpace.bounds.height } ?? false

    @MainActor init(library: LibraryModel? = nil, controllerInput: MenuControllerInputRouter? = nil) {
        _library = State(initialValue: library ?? LibraryModel())
        _menuControllerInput = State(initialValue: controllerInput ?? MenuControllerInputRouter())
        _xmbHost = State(initialValue: XmbHost(library: _library.wrappedValue, controllerInput: _menuControllerInput.wrappedValue))
    }

    // ARMSX2's theme gallery preview, not built in this app.
    private var themeGalleryPreviewing: Bool {
        #if ARMSX3_PHASE10
        ThemeGalleryStore.shared.isPreviewing
        #else
        false
        #endif
    }

    private var menuScreenActive: Bool {
        if case .menu = appState.currentScreen {
            return true
        }
        return false
    }

    /// The console's menu has no status bar, whatever the library's setting says.
    private var menuHidesStatusBar: Bool { settings.hideMenuStatusBar || xmbShown }

    /// The iPhone holds the XMB in landscape. iPadOS cannot hold a resizable window's shape, so an upright iPad window shows
    /// the library instead, and a game or sheet the XMB opened keeps it up through a turn.
    private var xmbShown: Bool {
        settings.xmbMode && !XmbLibraryCover.shared.presenting
            && (UIDevice.current.userInterfaceIdiom != .pad || menuLandscape || xmbHost.covered)
    }

    /// Free while any game screen is up, and across Change Disc's swap so the screen does not turn between discs.
    private var xmbHoldsLandscape: Bool {
        xmbShown && menuScreenActive && xmbHost.playing == nil && !xmbHost.changingDisc && !XmbInGame.shared.screenUp
            && XmbInGame.shared.nextDisc == nil && !XmbLibraryCover.shared.setupUp
    }

    /// A game the XMB did not open, such as one from a boot link, stands in front of it, also while it changes discs.
    private var xmbBehindGame: Bool { (XmbInGame.shared.screenUp || XmbInGame.shared.nextDisc != nil) && !xmbHost.gameUp }

    private var menuPresentationActive: Bool {
        // Keep library layout, covers and dynamic background
        // rendering out of the intro's first-frame presentation transaction.
        menuScreenActive && !showBootSplash && !appState.automaticGameStartupPending
    }

    private var coverPreviewAccentColor: Color {
        gameCoverThemePreview.preview?.accentColor
            ?? settings.controllerNavigationAccentColor
    }

    private var coverPreviewPrimaryTextColor: Color {
        gameCoverThemePreview.preview?.primaryTextColor
            ?? (settings.controllerTextAppearance.contentColor ?? .primary)
    }

    private var coverPreviewSecondaryTextColor: Color {
        gameCoverThemePreview.preview?.secondaryTextColor
            ?? settings.controllerTextAppearance.unselectedColor
    }

    /// The cover analyzer may choose dark text for a bright dynamic background, but every
    /// Context Menu is presented on dark regular glass. Keep that panel readable while still
    /// using the cover-derived accent for its focus and symbols.
    private var coverPreviewContextMenuPrimaryTextColor: Color {
        gameCoverThemePreview.preview == nil
            ? settings.controllerContextMenuColor
            : .white
    }

    private var coverPreviewContextMenuSecondaryTextColor: Color {
        gameCoverThemePreview.preview == nil
            ? settings.controllerContextMenuSecondaryColor
            : Color(red: 0.86, green: 0.91, blue: 0.96)
    }

    private var coverPreviewTextAppearance: ControllerTextAppearance {
        guard let preview = gameCoverThemePreview.preview else {
            return settings.controllerTextAppearance
        }
        var appearance = settings.controllerTextAppearance
        appearance.normalColor = preview.primaryTextColor
        appearance.secondaryColor = preview.secondaryTextColor
        appearance.titleColor = preview.primaryTextColor
        appearance.contentColor = preview.primaryTextColor
        appearance.unselectedColor = preview.secondaryTextColor
        appearance.focusedColor = preview.accentColor
        return appearance
    }

    var body: some View {
        jitAlertContent
            .uiSecondaryForegroundStyle(
                gameCoverThemePreview.preview == nil
                    ? settings.controllerTextAppearance.contentColor
                    : coverPreviewPrimaryTextColor
            )
            .tint(coverPreviewAccentColor)
            .environment(
                \.uiAccentColour,
                coverPreviewAccentColor
            )
            .environment(
                \.controllerTextAppearance,
                coverPreviewTextAppearance
            )
            .environment(
                \.uiSecondaryTextColour,
                coverPreviewSecondaryTextColor
            )
            .environment(
                \.uiTitleTextColour,
                gameCoverThemePreview.preview?.primaryTextColor
                    ?? settings.controllerTextAppearance.titleColor
            )
            .environment(
                \.uiContentTextColour,
                coverPreviewPrimaryTextColor
            )
            .environment(
                \.uiTabTitleColour,
                gameCoverThemePreview.preview?.primaryTextColor
                    ?? settings.controllerTabTitleColor
            )
            .environment(
                \.uiTabSubtitleColour,
                gameCoverThemePreview.preview?.secondaryTextColor
                    ?? settings.controllerTabSubtitleColor
            )
            .environment(
                \.uiBottomTabBarColour,
                coverPreviewAccentColor
            )
            .environment(
                \.uiBottomTabBarUnselectedColour,
                gameCoverThemePreview.preview?.secondaryTextColor
                    ?? settings.controllerBottomTabBarUnselectedColor
            )
            .environment(
                \.uiCardTitleColour,
                gameCoverThemePreview.preview?.primaryTextColor
                    ?? settings.controllerCardTitleColor
            )
            .environment(
                \.uiCardSubtitleColour,
                gameCoverThemePreview.preview?.secondaryTextColor
                    ?? settings.controllerCardSubtitleColor
            )
            .environment(
                \.uiContextMenuColour,
                coverPreviewContextMenuPrimaryTextColor
            )
            .environment(
                \.uiContextMenuSecondaryColour,
                coverPreviewContextMenuSecondaryTextColor
            )
            .environment(
                \.uiContextMenuFocusedColour,
                coverPreviewAccentColor
            )
            .environment(
                \.uiImportActionColour,
                coverPreviewAccentColor
            )
            .environment(
                \.uiToolbarColour,
                coverPreviewAccentColor
            )
            .environment(
                \.uiCriticalTextColour,
                settings.controllerCriticalTextColor
            )
            .environment(
                \.uiFrameRateConfiguration,
                frameRates.configuration
            )
            .environment(
                \.controllerNavigationDepthEffectEnabled,
                settings.controllerNavigationDepthEffectEnabled
            )
            // Master uses white label copy with blue/action-coloured symbols.
            // Install that split once so Settings, Per-Game Settings, Quick
            // Menu, context menus, and standard prompts stay consistent.
            .labelStyle(UIAccentIconLabelStyle())
            // The XMB holds the screen in landscape; the library's grid and a game turn freely.
            .onChange(of: xmbHoldsLandscape, initial: true) { _, held in
                ARMSX3AppDelegate.landscapeOnly = held
            }
    }

    private var baseContent: some View {
        ZStack {
            // Menu backdrop, and only that. It used to live inside the menu branch of a
            // switch; once this became a ZStack it started painting over gameplay too,
            // where it is near white in light mode and shows in the strip of top safe
            // area the portrait game view leaves clear for the Dynamic Island. Same
            // condition as the layer below so the two cannot drift apart.
            Color(uiColor: .systemGroupedBackground)
                .ignoresSafeArea()
                .opacity(
                    menuPresentationActive ||
                    appState.gameplayLaunchBackgroundVisible ? 1 : 0
                )

            // Do not construct the library or its renderer for direct startup.
            // The same host still survives ordinary tab changes.
            if menuPresentationActive || appState.gameplayLaunchTransition != nil {
                PersistentMenuBackgroundLayer(host: backgroundHost)
                    .opacity(
                        menuPresentationActive ||
                        appState.gameplayLaunchBackgroundVisible ? 1 : 0
                    )
                    .zIndex(
                        appState.currentScreen == .playing &&
                        appState.gameplayLaunchTransition != nil ? 80 : 0
                    )
            }

            // Keep the menu hierarchy alive for the launch transition so the
            // library fades behind the live SwiftUI card instead of vanishing
            // on the same update that starts the VM.
            if menuPresentationActive || appState.gameplayLaunchTransition != nil {
                ZStack {
                    if settings.xmbMode {
                        // The XMB draws its own wave and its theme's picture.
                        XmbHomeView(host: xmbHost, visible: xmbShown && !xmbBehindGame && !XmbLibraryCover.shared.setupUp)
                            .opacity(xmbShown ? 1 : 0)
                            .allowsHitTesting(xmbShown)
                            .accessibilityHidden(!xmbShown)
                            // The library's background would keep drawing unseen under the XMB; MenuTabView turns it back on.
                            .onChange(of: xmbShown, initial: true) { _, shown in
                                guard shown else { return }
                                backgroundHost.setMenuBackgroundAvailable(false)
                                backgroundHost.setPresentationVisible(false)
                            }
                    }
                    if !xmbShown {
                        MenuTabView(
                            backgroundHost: backgroundHost,
                            controllerInput: menuControllerInput,
                            library: library,
                            selectedTab: $selectedMenuTab,
                            settingsNavigationPath: $settingsMenuNavigationPath
                        )
                    }
                }
                    .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { menuLandscape = $0 }
                    .opacity(menuScreenActive && !themeGalleryPreviewing ? 1 : 0)
                    .animation(
                        .easeOut(duration: 0.34),
                        value: menuScreenActive
                    )
                    .allowsHitTesting(menuScreenActive)
                    .accessibilityHidden(!menuScreenActive)
                    .zIndex(
                        appState.gameplayLaunchTransition == nil ? 1 : 85
                    )
            }

            // The library presents the game screen itself, so there is no playing screen here.

            if let transition = appState.gameplayLaunchTransition {
                GameplayLaunchOverlay(
                    transition: transition,
                    onBeginAudioFade: { duration in
                        // ARMSX2's UI audio packs, not built in this app.
                        #if ARMSX3_PHASE10
                        MenuAudioPackManager.shared
                            .finishGameLaunchWithBackgroundFade(
                                duration: duration
                            )
                        #endif
                    },
                    onRevealGameplay: {
                        appState.revealGameplayLaunchControls()
                    },
                    onComplete: {
                        appState.completeGameplayLaunchTransition(id: transition.id)
                    }
                )
                .zIndex(90)
            }

            // The game screen mounts the splash instead; this frame never enters `.playing`.

            if showBootSplash {
                BootSplashView {
                    finishBootSplash()
                }
                .transition(.opacity)
                .zIndex(100)
            }

            // Leaving XMB mode fades to black and outlives the XMB itself.
            XmbShutdownView(clock: XmbBootClock.shared)
                .zIndex(101)
                // The library is mounted under the black from the moment XMB mode ends, so until the black has lifted
                // a press only ends the intro, as at launch.
                .onChange(of: XmbBootClock.shared.isShuttingDown) { _, leaving in
                    if leaving {
                        menuControllerInput.setLaunchInputInterceptor { XmbBootClock.shared.introFinished() }
                    } else {
                        menuControllerInput.setLaunchInputInterceptor(nil)
                    }
                }

            if appState.automaticGameStartupPending {
                ZStack {
                    Color.black.ignoresSafeArea()
                    ProgressView(settings.localized("Starting Last Game…"))
                        .tint(settings.controllerNavigationAccentColor)
                }
                .zIndex(100)
            }
        }
        // A sheet from the pause menu covers every layer of this view, so the
        // prompts get a window of their own.
        .background {
            RootAlertWindow(content: rootControllerAlert)
        }
        // ARMSX2's app language setting, not built in this app.
        #if ARMSX3_PHASE10
        .environment(\.locale, settings.appLanguage == .system ? .autoupdatingCurrent : Locale(identifier: settings.appLanguage.bcp47Code))
        #endif
        .environment(\.layoutDirection, settings.localizedLayoutDirection)
        .environment(\.clearLiquidGlassUIEnabled, settings.clearLiquidGlassUI)
        .environment(\.menuControllerInputRouter, menuControllerInput)
#if !targetEnvironment(macCatalyst)
        .background {
            NativeMenuTouchInputObserver(
                observesTouchActions:
                    !menuControllerInput.isControllerNavigationEnabled,
                blocksUnderlyingTouches:
                    menuControllerInput.isMenuActive
                        && menuControllerInput.isControllerNavigationEnabled
                        && menuControllerInput.pendingNavigationModeSwitchRequest == nil
                        && frameRates.asksBeforeTouchNavigation
                        && !menuControllerInput.isPadFirstCaptured,
                onTouch: {
                    menuControllerInput.noteTouchInput()
                },
                onTap: {
                    // ARMSX2's UI audio packs, not built in this app.
                    #if ARMSX3_PHASE10
                    MenuAudioPackManager.shared.playTouchNavigation()
                    #endif
                    menuControllerInput.playTouchHaptics(.activate)
                },
                onBack: {
                    // ARMSX2's UI audio packs, not built in this app.
                    #if ARMSX3_PHASE10
                    MenuAudioPackManager.shared.playEvent(.return)
                    #endif
                    menuControllerInput.playTouchHaptics(.back)
                },
                onToggle: { isOn in
                    // ARMSX2's UI audio packs, not built in this app.
                    #if ARMSX3_PHASE10
                    MenuAudioPackManager.shared.playTouchToggle(isOn: isOn)
                    #endif
                    menuControllerInput.playTouchHaptics(.toggle(isOn: isOn))
                },
                onContextMenu: {
                    // ARMSX2's UI audio packs, not built in this app.
                    #if ARMSX3_PHASE10
                    MenuAudioPackManager.shared.playTouchContextMenu()
                    #endif
                    menuControllerInput.playTouchHaptics(.contextMenu)
                },
                onEmptySpaceLongPress: { step in
                    menuControllerInput.requestThemePresetChangeFromTouch(
                        step: step
                    )
                }
            )
            .frame(width: 0, height: 0)
        }
#endif
        .statusBarHidden(
            showBootSplash
                ? settings.hideIntroStatusBar
                : (menuScreenActive && menuHidesStatusBar)
        )
    }

    private var lifecycleContent: some View {
        baseContent
        .onAppear {
            // ARMSX2's automatic no-JIT appearance switch, not built in this app.
            #if ARMSX3_PHASE10
            settings.restoreDefaultAppearanceAfterAutomaticNoJITIfNeeded(
                jitAvailable: ARMSX2Bridge.isJITAvailable()
            )
            #endif
            // The app is hosted by a custom UIKit controller whose status-bar
            // answer comes from AppState, so mirror the active presentation's
            // preference as soon as the root hierarchy appears.
            appState.hideStatusBar = showBootSplash
                ? settings.hideIntroStatusBar
                : (menuScreenActive && menuHidesStatusBar)
            // ARMSX2's UI audio packs, not built in this app.
            #if ARMSX3_PHASE10
            MenuAudioPackManager.shared.setAutomaticGameStartupActive(
                appState.automaticGameStartupPending
            )
            // RootView is hosted by SDL's UIKit scene rather than a SwiftUI
            // App/Scene. The scenePhase environment can therefore still be
            // inactive while this hierarchy is already interactive. Use the
            // process state here and UIKit lifecycle notifications below so a
            // stale SwiftUI value cannot permanently gate every audio event.
            MenuAudioPackManager.shared.setApplicationActive(
                UIApplication.shared.applicationState == .active
            )
            MenuAudioPackManager.shared.setMainInterfaceActive(
                mainAudioInterfaceActive
            )
            #endif
            menuControllerInput.start()
            if showBootSplash {
                menuControllerInput.setLaunchInputInterceptor {
                    finishBootSplash()
                }
            } else {
                menuControllerInput.setLaunchInputInterceptor(nil)
                // ARMSX2's UI audio packs, not built in this app.
                #if ARMSX3_PHASE10
                MenuAudioPackManager.shared.introDidFinish(playsStartupSound: false)
                #endif
            }
            menuControllerInput.setMenuActive(menuScreenActive)
            // ARMSX2's shader downloads, not built in this app.
            #if ARMSX3_PHASE10
            ShaderCatalogInstaller.sweepStagedDownloads()
            #endif
            scheduleGameplayControllerHelpIfReady()
        }
        // ARMSX2's Automatic Load Last Game, not built in this app.
        #if ARMSX3_PHASE9
        .task {
            appState.startAutomaticGameIfNeeded()
        }
        #endif
        .onChange(of: appState.automaticGameStartupPending) { _, pending in
            // Update the destination before releasing the startup music gate;
            // controller-owned JIT prompts are not an emulation Quick Menu.
            menuControllerInput.setMenuActive(menuScreenActive)
            // ARMSX2's UI audio packs, not built in this app.
            #if ARMSX3_PHASE10
            MenuAudioPackManager.shared.setMainInterfaceActive(mainAudioInterfaceActive)
            MenuAudioPackManager.shared.setAutomaticGameStartupActive(pending)
            #endif
        }
        .onChange(of: settings.hideIntroStatusBar) { _, hideStatusBar in
            guard showBootSplash else { return }
            appState.hideStatusBar = hideStatusBar
        }
        .onChange(of: menuHidesStatusBar) { _, hideStatusBar in
            guard !showBootSplash, menuScreenActive else { return }
            appState.hideStatusBar = hideStatusBar
        }
        .onChange(of: menuScreenActive) { _, active in
            handleMenuScreenActiveChange(active)
        }
        // ARMSX2's UI audio packs, not built in this app.
        #if ARMSX3_PHASE10
        .onChange(of: mainAudioInterfaceActive) { _, active in
            MenuAudioPackManager.shared.setMainInterfaceActive(active)
        }
        #endif
        .onChange(of: appState.gameplayLaunchTransition?.id) { previous, current in
            if previous != nil, current == nil {
                // ARMSX2's UI audio packs, not built in this app.
                #if ARMSX3_PHASE10
                MenuAudioPackManager.shared.setMainInterfaceActive(
                    mainAudioInterfaceActive
                )
                MenuAudioPackManager.shared.cancelGameLaunchAudio()
                #endif
                scheduleGameplayControllerHelpIfReady()
            }
        }
        .onChange(of: appState.emulationSessionID) { _, _ in
            scheduleGameplayControllerHelpIfReady()
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: UIApplication.didBecomeActiveNotification
            )
        ) { _ in
            // ARMSX2's UI audio packs, not built in this app.
            #if ARMSX3_PHASE10
            MenuAudioPackManager.shared.setApplicationActive(true)
            #endif
            menuControllerInput.applicationDidBecomeActive()
            // ARMSX2's no-JIT appearance switch and Automatic Load Last Game, not built in this app.
            #if ARMSX3_PHASE10
            settings.restoreDefaultAppearanceAfterAutomaticNoJITIfNeeded(
                jitAvailable: ARMSX2Bridge.isJITAvailable()
            )
            #endif
            #if ARMSX3_PHASE9
            appState.resumeAutomaticGameAfterJITIfReady()
            #endif
        }
        // ARMSX2's no-JIT appearance switch and UI audio packs, not built in this app.
        #if ARMSX3_PHASE10
        .onReceive(
            NotificationCenter.default.publisher(
                for: Notification.Name("ARMSX2iOSIdleVMPrewarmResolved")
            )
        ) { _ in
            settings.restoreDefaultAppearanceAfterAutomaticNoJITIfNeeded(
                jitAvailable: ARMSX2Bridge.isJITAvailable()
            )
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: Notification.Name("ARMSX2iOSVMDidShutdown")
            )
        ) { _ in
            MenuAudioPackManager.shared.emulationAudioDidStop()
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: UIApplication.willResignActiveNotification
            )
        ) { _ in
            MenuAudioPackManager.shared.setApplicationActive(false)
        }
        #endif
        // An armsx3://library or armsx3://settings link switches the tab.
        .onReceive(NotificationCenter.default.publisher(for: DeepLink.openTabNotification)) { note in
            guard let tab = note.object as? Int else { return }
            // The XMB has no tabs: the settings link opens its Settings column, and the library link changes nothing.
            if xmbShown {
                // Not behind a game or a sheet, where it would move the XMB unseen.
                if tab == 2, xmbHost.playing == nil, xmbHost.presented == nil, !XmbInGame.shared.screenUp,
                   let column = xmbHost.state.columns.firstIndex(where: { $0.id == "settings" }) {
                    xmbHost.perform(.column(column))
                }
                return
            }
            selectedMenuTab = tab
        }
        .onReceive(NotificationCenter.default.publisher(for: JitNoticeModel.openJitDeviceNotification)) { _ in
            selectedMenuTab = 2
            settingsMenuNavigationPath = [.jitDevice]
        }
        // A prompt shown over a running game takes the pad once its menu opens.
        .onChange(of: menuControllerInput.isMenuActive) { _, _ in
            if activeControllerAlertKind != nil { updateRootAlertNavigationCapture(isPresented: true) }
        }
        .onChange(of: activeControllerAlertKind, initial: true) { previous, kind in
            rootControllerAlertSelection = 0
            updateRootAlertNavigationCapture(isPresented: kind != nil)
            guard let kind, kind != previous else { return }
            // Presentation sound belongs to the modal transition, not to a
            // hidden row underneath it. File/import handlers already emit the
            // toast sound in most paths; the audio coalescer makes this safe for
            // the remaining direct BIOS-disclaimer path.
            #if ARMSX3_PHASE10
            switch kind {
            case .navigationMode, .prompt:
                MenuAudioPackManager.shared.playEvent(.uiToast)
            }
            #endif
        }
        .onDisappear {
            bootSplashInputReleaseTask?.cancel()
            bootSplashInputReleaseTask = nil
            // ARMSX2's UI audio packs, not built in this app.
            #if ARMSX3_PHASE10
            MenuAudioPackManager.shared.setMainInterfaceActive(false)
            MenuAudioPackManager.shared.setApplicationActive(false)
            #endif
            menuControllerInput.stop()
            gameplayHelpBanner.clear()
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: AppState.releaseMenuBackgroundResourcesNotification
            )
        ) { _ in
            backgroundHost.release()
            // These stores outlive the tab hierarchy. Explicitly discard their
            // decoded images and presentation-only state instead of depending
            // exclusively on the selected tab's onDisappear ordering.
        }
        // The game screen is a full-screen cover, so MenuTabView's onAppear does not fire after a game; the screen says when it has gone.
        .onReceive(
            NotificationCenter.default.publisher(
                for: EmulatorSession.menuBackgroundReturnNotification
            )
        ) { _ in
            backgroundHost.reactivateForMenu(isAvailable: settings.hasCustomBackground && !xmbShown)
        }
    }

    @MainActor
    private func finishBootSplash() {
        guard showBootSplash else { return }
        NotificationCenter.default.post(
            name: BootSplashView.stopPlaybackForAudioHandoff,
            object: nil
        )
        withAnimation(.easeOut(duration: 0.2)) {
            showBootSplash = false
        }
        Task { @MainActor in
            // Keep the status bar out of the splash fade, then apply the menu
            // preference once the intro has actually left the hierarchy.
            try? await Task.sleep(for: .milliseconds(220))
            guard !Task.isCancelled,
                  !showBootSplash,
                  appState.currentScreen == .menu else { return }
            appState.hideStatusBar = menuHidesStatusBar
        }
        scheduleGameplayControllerHelpIfReady()

        bootSplashInputReleaseTask?.cancel()
        bootSplashInputReleaseTask = Task { @MainActor in
            // Keep consuming the press which dismissed the movie until UIKit
            // has completed the splash transition. Otherwise the same Cross
            // edge can reach the already-mounted game library underneath.
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            menuControllerInput.setLaunchInputInterceptor(nil)
            bootSplashInputReleaseTask = nil
        }

        // The opacity transition retains the boot AVPlayer for its full
        // duration. Wait until it is gone before claiming the audio session.
        // ARMSX2's UI audio packs, not built in this app.
        #if ARMSX3_PHASE10
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 240_000_000)
            guard !Task.isCancelled else { return }
            MenuAudioPackManager.shared.introDidFinish()
        }
        #endif
    }

    private var standardAlertContent: some View {
        // App-owned prompts use ControllerNavigationAlert for touch and
        // controller input alike. JIT warnings opt into regular,
        // non-clear glass while keeping the same stable focus graph.
        lifecycleContent
    }

    private var jitAlertContent: some View {
        standardAlertContent
    }

    private var rootControllerAlert: AnyView? {
        guard let kind = activeControllerAlertKind,
              menuScreenActive || kind.showsOverGameplay else { return nil }
        return AnyView(
            ControllerNavigationAlert(
                title: controllerAlertTitle(for: kind),
                message: controllerAlertMessage(for: kind),
                actions: controllerAlertActions(for: kind),
                selectedIndex: rootControllerAlertSelection,
                onSelect: { index in
                    selectControllerAlertAction(index, kind: kind)
                },
                onDismiss: {
                    dismissControllerAlert(kind)
                }
            )
            .overlay {
                RootControllerAlertCommandListener(
                    controllerInput: menuControllerInput,
                    onCommand: { command in
                        handleControllerAlertCommand(command, kind: kind)
                    }
                )
            }
        )
    }

    private var activeControllerAlertKind: RootControllerAlertKind? {
        if let request = menuControllerInput.pendingNavigationModeSwitchRequest {
            return .navigationMode(request.mode)
        }
        if let request = ControllerPrompt.shared.request {
            return .prompt(request.id)
        }
        return nil
    }

    private func handleMenuScreenActiveChange(_ active: Bool) {
        menuControllerInput.setMenuActive(active)
        guard active else {
            scheduleGameplayControllerHelpIfReady()
            return
        }
        if !showBootSplash {
            appState.hideStatusBar = menuHidesStatusBar
        }
        gameplayHelpBanner.clear()
        updateRootAlertNavigationCapture(
            isPresented: activeControllerAlertKind != nil
        )
    }

    private func scheduleGameplayControllerHelpIfReady() {
        Task { @MainActor in
            // Coalesce AppState's session, screen, and launch-transition
            // publications before deciding whether gameplay is actually visible.
            await Task.yield()
            guard !showBootSplash,
                  appState.currentScreen == .playing,
                  appState.gameplayLaunchTransition == nil,
                  menuControllerInput.hasConnectedController,
                  GCController.controllers().contains(where: {
                      $0.extendedGamepad != nil
                  }),
                  let sessionID = appState.emulationSessionID,
                  sessionID != presentedGameplayHelpSessionID else {
                return
            }
            presentedGameplayHelpSessionID = sessionID
            gameplayHelpBanner.present(sessionID, displayDuration: 7.0)
        }
    }

    private func updateRootAlertNavigationCapture(isPresented: Bool) {
        if isPresented {
            // Import completion follows a touch-owned document picker. Restore
            // controller presentation before the modal claims its input so OK
            // is visibly selected as soon as the alert appears.
            menuControllerInput.claimPresentedNavigationFocus()
        }
        menuControllerInput.setNavigationCaptured(
            isPresented,
            owner: MenuControllerNavigationCaptureOwner.rootAlert,
            priority: 1_000
        )
    }

    private func controllerAlertTitle(
        for kind: RootControllerAlertKind
    ) -> String {
        switch kind {
        case .navigationMode(.controller):
            settings.localized("Use Controller Navigation?")
        case .navigationMode(.touch):
            settings.localized("Use Touch Navigation?")
        case .prompt:
            ControllerPrompt.shared.request?.title ?? ""
        }
    }

    private func controllerAlertMessage(
        for kind: RootControllerAlertKind
    ) -> String {
        switch kind {
        case .navigationMode(.controller):
            settings.localized(
                "Controller navigation will replace touch navigation and load controller focus only for the active screen."
            )
        case .navigationMode(.touch):
            settings.localized(
                "Touch navigation will release controller focus and unload its inactive focus and scroll resources."
            )
        case .prompt:
            ControllerPrompt.shared.request?.message ?? ""
        }
    }

    private func controllerAlertActions(
        for kind: RootControllerAlertKind
    ) -> [ControllerNavigationAlertAction] {
        switch kind {
        case .prompt:
            (ControllerPrompt.shared.request?.actions ?? []).enumerated().map {
                .init(
                    id: "prompt.\($0.offset)",
                    title: $0.element.title,
                    isDestructive: $0.element.isDestructive
                )
            }
        case .navigationMode:
            [
                .init(id: "ok", title: settings.localized("OK")),
                .init(id: "cancel", title: settings.localized("Cancel")),
                .init(
                    id: "do-not-ask-again",
                    title: settings.localized("Do Not Ask Again")
                ),
            ]
        }
    }

    private func handleControllerAlertCommand(
        _ command: MenuControllerCommand,
        kind: RootControllerAlertKind
    ) {
        guard activeControllerAlertKind == kind else { return }
        switch command {
        case .activate:
            selectControllerAlertAction(
                rootControllerAlertSelection,
                kind: kind
            )
        case .back:
            dismissControllerAlert(kind)
        case .up, .upLeft, .left, .downLeft:
            moveControllerAlertSelection(by: -1, kind: kind, command: command)
        case .upRight, .right, .downRight, .down:
            moveControllerAlertSelection(by: 1, kind: kind, command: command)
        case .toggleFavorite, .showContextMenu,
             .previousTab, .nextTab, .start, .select:
            menuControllerInput.playFeedback(.boundary)
        }
    }

    private func moveControllerAlertSelection(
        by offset: Int,
        kind: RootControllerAlertKind,
        command: MenuControllerCommand
    ) {
        let actions = controllerAlertActions(for: kind)
        guard !actions.isEmpty else { return }
        let next = min(
            max(rootControllerAlertSelection + offset, actions.startIndex),
            actions.index(before: actions.endIndex)
        )
        guard next != rootControllerAlertSelection else {
            menuControllerInput.playFeedback(.boundary)
            return
        }
        rootControllerAlertSelection = next
        menuControllerInput.playFeedback(.move(command))
    }

    private func selectControllerAlertAction(
        _ index: Int,
        kind: RootControllerAlertKind
    ) {
        guard controllerAlertActions(for: kind).indices.contains(index),
              activeControllerAlertKind == kind else { return }
        switch kind {
        case .navigationMode(let mode):
            if index == 1 {
                menuControllerInput.cancelNavigationModeSwitch(mode)
                menuControllerInput.playFeedback(.back)
            } else {
                menuControllerInput.acceptNavigationModeSwitch(
                    mode,
                    doNotAskAgain: index == 2
                )
                menuControllerInput.playFeedback(.activate)
                if mode == .controller {
                    restoreControllerFocusAfterModeHandoff()
                }
            }
        case .prompt:
            let cancels = ControllerPrompt.shared.request?.actions[index].isCancel == true
            ControllerPrompt.shared.answer(index)
            menuControllerInput.playFeedback(cancels ? .back : .activate)
        }
    }

    private func restoreControllerFocusAfterModeHandoff() {
        Task { @MainActor in
            // Controller probes are intentionally absent while touch owns the
            // screen. Give SwiftUI one mounting turn, then use the router's
            // mount-aware entry request rather than polling for rows.
            await Task.yield()
            guard menuControllerInput.isControllerNavigationEnabled else {
                return
            }
            if selectedMenuTab == 0 {
                menuControllerInput.requestLibraryEntry(preferLast: true)
            } else {
                _ = menuControllerInput.requestNavigationSessionEntry(
                    preferLast: true,
                    matchingScopePrefix: selectedMenuTab == 1
                        ? "menu.bios"
                        : "settings."
                )
            }
        }
    }

    private func dismissControllerAlert(_ kind: RootControllerAlertKind) {
        guard activeControllerAlertKind == kind else { return }
        switch kind {
        case .navigationMode(let mode):
            menuControllerInput.cancelNavigationModeSwitch(mode)
            menuControllerInput.playFeedback(.back)
        case .prompt:
            ControllerPrompt.shared.answer(nil)
            menuControllerInput.playFeedback(.back)
        }
    }

}

/// Rebuilds the selected card as a live SwiftUI Liquid Glass surface while the
/// menu tree is dismantled underneath it. The cover and text scale with the
/// glass geometry, then the complete surface fades into the live Metal view.
private struct GameplayLaunchOverlay: View {
    let transition: GameplayLaunchTransition
    let onBeginAudioFade: (TimeInterval) -> Void
    let onRevealGameplay: () -> Void
    let onComplete: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var zoomed = false
    @State private var fadingOut = false

    var body: some View {
        GeometryReader { geometry in
            let viewport = geometry.frame(in: .global)
            let sourceCenter = CGPoint(
                x: transition.sourceFrame.midX - viewport.minX,
                y: transition.sourceFrame.midY - viewport.minY
            )
            let destinationCenter = CGPoint(
                x: geometry.size.width / 2,
                y: geometry.size.height / 2
            )
            let destinationScale = zoomScale(in: geometry.size)

            ZStack {
                Color.black
                    .opacity(zoomed && !reduceMotion ? 0.14 : 0)

                FluidGameplayLaunchCard(transition: transition)
                    .frame(
                        width: transition.sourceFrame.width,
                        height: transition.sourceFrame.height
                    )
                    .scaleEffect(zoomed && !reduceMotion ? destinationScale : 1)
                    .position(zoomed && !reduceMotion ? destinationCenter : sourceCenter)
                    .shadow(
                        color: .black.opacity(fadingOut ? 0 : 0.34),
                        radius: zoomed ? 26 : 8,
                        y: zoomed ? 14 : 4
                    )
            }
            .opacity(fadingOut ? 0 : 1)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            DispatchQueue.main.async {
                withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) {
                    zoomed = true
                }
            }
        }
        .task(id: transition.id) {
            await revealWhenEmulationIsRunning()
        }
    }

    private func zoomScale(in viewport: CGSize) -> CGFloat {
        guard transition.sourceFrame.width > 0, transition.sourceFrame.height > 0 else {
            return 1.2
        }

        let availableWidthScale = (viewport.width * 0.72) / transition.sourceFrame.width
        let availableHeightScale = (viewport.height * 0.76) / transition.sourceFrame.height
        return min(2.1, max(1.16, min(availableWidthScale, availableHeightScale)))
    }

    @MainActor
    private func revealWhenEmulationIsRunning() async {
        // The spring settles in roughly 0.42 seconds. Keeping the overlay for
        // 1.42 seconds leaves the fully zoomed Liquid Glass card readable for
        // approximately one second without delaying the VM boot itself.
        let minimumDisplayNanoseconds: UInt64 = reduceMotion
            ? 240_000_000
            : 1_420_000_000
        try? await Task.sleep(nanoseconds: minimumDisplayNanoseconds)
        guard !Task.isCancelled else { return }

        for _ in 0..<24 where !EmulatorSession.shared.isRunning {
            try? await Task.sleep(nanoseconds: 50_000_000)
            guard !Task.isCancelled else { return }
        }

        let fadeDuration = reduceMotion ? 0.15 : 0.30
        onBeginAudioFade(fadeDuration)
        withAnimation(.easeOut(duration: fadeDuration)) {
            onRevealGameplay()
            fadingOut = true
        }

        try? await Task.sleep(nanoseconds: UInt64(fadeDuration * 1_000_000_000))
        guard !Task.isCancelled else { return }
        onComplete()
    }
}

struct FluidGameplayLaunchCard: View {
    let transition: GameplayLaunchTransition
    var showsUnselectedFavoriteIndicator = false

    var body: some View {
        Group {
            switch transition.style {
            case .list:
                listContent
            case .grid, .coverFlow:
                coverContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topTrailing) {
            if transition.showsFavoriteIndicator,
               transition.isFavorite || showsUnselectedFavoriteIndicator {
                Image(systemName: transition.isFavorite ? "star.fill" : "star")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(
                        transition.isFavorite ? .yellow : .white.opacity(0.86)
                    )
                    .padding(10)
                    .shadow(color: .black.opacity(0.28), radius: 4, y: 2)
            }
        }
        .glassSurface(
            interactive: true,
            clear: transition.usesClearGlass,
            cornerRadius: transition.cornerRadius
        )
        .clipShape(
            RoundedRectangle(
                cornerRadius: transition.cornerRadius,
                style: .continuous
            )
        )
    }

    private var listContent: some View {
        HStack(spacing: 12) {
            cover

            VStack(alignment: .leading, spacing: 4) {
                if transition.showsGameName {
                    Text(transition.title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(transition.usesSingleLineGameName ? 1 : 2)
                }
                if transition.showsGameInfo {
                    Text(transition.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(12)
    }

    private var coverContent: some View {
        VStack(spacing: transition.style == .coverFlow ? 8 : 10) {
            cover
                .shadow(color: .black.opacity(0.28), radius: 18, y: 10)

            if transition.showsGameName || transition.showsGameInfo {
                VStack(spacing: 4) {
                    if transition.showsGameName {
                        Text(transition.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.center)
                            .lineLimit(
                                transition.usesSingleLineGameName ? 1 : 2
                            )
                    }
                    if transition.showsGameInfo {
                        Text(transition.detail)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(transition.style == .coverFlow ? 8 : 12)
    }

    private var cover: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))

            if let image = transition.coverImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                VStack(spacing: 6) {
                    Image(
                        systemName: transition.gameName.lowercased().hasSuffix(".chd")
                            ? "archivebox"
                            : "opticaldisc"
                    )
                    .font(.system(size: 24, weight: .medium))
                    Text(
                        transition.gameName.lowercased().hasSuffix(".chd")
                            ? "CHD"
                            : "PS3"
                    )
                    .font(.caption2.bold())
                }
                .foregroundStyle(.secondary)
            }
        }
        .frame(
            width: transition.coverSize.width,
            height: transition.coverSize.height
        )
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct MenuTabControllerCommandListener: View {
    let controllerInput: MenuControllerInputRouter
    let onEvent: (MenuControllerInputEvent) -> Void

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onChange(of: controllerInput.latestEvent) { _, event in
                guard let event else { return }
                onEvent(event)
            }
    }
}

@MainActor
@Observable
private final class MenuControllerTabFocusState {
    var focusedIndex = 0

    init(focusedIndex: Int = 0) {
        self.focusedIndex = focusedIndex
    }
}

private struct MenuBottomTabBarLayout {
    let height: CGFloat
    let iconScale: CGFloat
    let labelScale: CGFloat
    let bottomClearance: CGFloat
    let showsLabels: Bool
}

struct MenuTabView: View {
    @State private var settings = SettingsStore.shared
    @Binding var selectedTab: Int
    @Binding var settingsNavigationPath: MenuSettingsNavigationPath
    @State private var titleMorphSelection = 0
    @State private var settingsRootResetRequest = 0
    @State private var settingsNavigationHasDestination = false
    @State private var menuLargeTitleMorphActive = false
    @State private var menuLargeTitleMorphResetTask: Task<Void, Never>?
    @State private var controllerTabEntryTask: Task<Void, Never>?
    @State private var gameRenamePresented = false
    @State private var libraryMenuOpen = false
    @State private var controllerTabFocusState: MenuControllerTabFocusState
    @State private var menuViewportSize = CGSize.zero
    @AppStorage("ARMSX2iOSPortraitBottomNavigationTabBarHeight")
    private var portraitBottomNavigationTabBarHeight = 84.0
    @AppStorage("ARMSX2iOSLandscapeBottomNavigationTabBarHeight")
    private var landscapeBottomNavigationTabBarHeight = 84.0
    @AppStorage("ARMSX2iOSPortraitBottomNavigationTabBarIconScale")
    private var portraitBottomNavigationTabBarIconScale = 1.0
    @AppStorage("ARMSX2iOSLandscapeBottomNavigationTabBarIconScale")
    private var landscapeBottomNavigationTabBarIconScale = 1.0
    @AppStorage("ARMSX2iOSPortraitBottomNavigationTabBarLabelScale")
    private var portraitBottomNavigationTabBarLabelScale = 1.0
    @AppStorage("ARMSX2iOSLandscapeBottomNavigationTabBarLabelScale")
    private var landscapeBottomNavigationTabBarLabelScale = 1.0
    @AppStorage("ARMSX2iOSPortraitBottomNavigationTabBarClearance")
    private var portraitBottomNavigationTabBarClearance = 0.0
    @AppStorage("ARMSX2iOSLandscapeBottomNavigationTabBarClearance")
    private var landscapeBottomNavigationTabBarClearance = 0.0
    @AppStorage("ARMSX2iOSPortraitBottomNavigationTabBarShowsLabels")
    private var portraitBottomNavigationTabBarShowsLabels = 1
    @AppStorage("ARMSX2iOSLandscapeBottomNavigationTabBarShowsLabels")
    private var landscapeBottomNavigationTabBarShowsLabels = 1
    let backgroundHost: PersistentMenuBackgroundHost
    let controllerInput: MenuControllerInputRouter
    let library: LibraryModel
    @Namespace private var largeTitleNamespace
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    init(
        backgroundHost: PersistentMenuBackgroundHost,
        controllerInput: MenuControllerInputRouter,
        library: LibraryModel,
        selectedTab: Binding<Int>,
        settingsNavigationPath: Binding<MenuSettingsNavigationPath>
    ) {
        self.backgroundHost = backgroundHost
        self.controllerInput = controllerInput
        self.library = library
        self._selectedTab = selectedTab
        self._settingsNavigationPath = settingsNavigationPath
        self._titleMorphSelection = State(initialValue: selectedTab.wrappedValue)
        self._controllerTabFocusState = State(
            initialValue: MenuControllerTabFocusState(
                focusedIndex: selectedTab.wrappedValue
            )
        )
    }

    // The Install tab has no background switch of its own: it follows the library's.
    private var biosBackgroundActive: Bool { settings.hasCustomBackground }
    private var settingsBackgroundActive: Bool { settings.hasCustomBackground && settings.backgroundEnabledInSettings }

    private var selectedTabShowsBackground: Bool {
        switch selectedTab {
        case 0:
            return settings.hasCustomBackground
        case 1:
            return biosBackgroundActive
        default:
            return settingsBackgroundActive
        }
    }

    private var bottomTabBarLayout: MenuBottomTabBarLayout {
        let isLandscape = menuViewportSize == .zero
            ? verticalSizeClass == .compact
            : menuViewportSize.width > menuViewportSize.height
        let height = isLandscape
            ? landscapeBottomNavigationTabBarHeight
            : portraitBottomNavigationTabBarHeight
        let iconScale = isLandscape
            ? landscapeBottomNavigationTabBarIconScale
            : portraitBottomNavigationTabBarIconScale
        let labelScale = isLandscape
            ? landscapeBottomNavigationTabBarLabelScale
            : portraitBottomNavigationTabBarLabelScale
        let clearance = isLandscape
            ? landscapeBottomNavigationTabBarClearance
            : portraitBottomNavigationTabBarClearance
        let storedShowsLabels = isLandscape
            ? landscapeBottomNavigationTabBarShowsLabels
            : portraitBottomNavigationTabBarShowsLabels

        return MenuBottomTabBarLayout(
            height: CGFloat(min(max(height, 42), 100)),
            iconScale: CGFloat(min(max(iconScale, 0.75), 1.5)),
            labelScale: CGFloat(min(max(labelScale, 0.75), 1.5)),
            bottomClearance: CGFloat(min(max(clearance, -32), 32)),
            showsLabels: storedShowsLabels != 0
        )
    }

    // The library's flag counts only on its own page, so a page change never leaves the bar hidden.
    private var tabBarHidden: Bool {
        gameRenamePresented || (libraryMenuOpen && selectedTab == 0)
    }

    private var tabContentBottomMargin: CGFloat {
        max(
            96,
            bottomTabBarLayout.height
                + bottomTabBarLayout.bottomClearance
                + 46
        )
    }

    private var tabBarContentSpacing: CGFloat {
        20
    }

    /// Settings lists don't get the tab bar's safe area inset and run on to the bottom of the
    /// safe area, so they need room for the part the bar covers. Their own bottom padding is the gap.
    private var settingsTabBarContentMargin: CGFloat {
        let offset = verticalSizeClass == .compact ? ControllerFocusedMenuTabBar.compactOffset : 0
        return bottomTabBarLayout.height + bottomTabBarLayout.bottomClearance - offset
    }

    /// Landscape is the expensive horizontal cover-flow presentation in this
    /// menu (including regular-size-class iPads).
    /// Cross-fading complete retained pages forces every large glass card into
    /// one full-screen compositing group. Portrait can afford that transition;
    /// landscape switches page visibility without animating the page raster.
    private var animatesRetainedPageTransitions: Bool {
        let usesLandscapeLayout = menuViewportSize == .zero
            ? verticalSizeClass == .compact
            : menuViewportSize.width > menuViewportSize.height
        return !usesLandscapeLayout
    }

    private var tabSelection: Binding<Int> {
        Binding(
            get: { selectedTab },
            set: { selectTab($0) }
        )
    }

    /// Passing nil dismantles every controller probe, focus overlay, geometry
    /// sampler, and right-stick target while touch owns the interface. The
    /// router retains only its lightweight physical-input callback so it can
    /// offer the explicit controller-mode handoff.
    private var activeControllerInput: MenuControllerInputRouter? {
        controllerInput.hasConnectedController
            && controllerInput.isControllerNavigationEnabled
            && controllerInput.pendingNavigationModeSwitchRequest?.mode
                != .controller
            ? controllerInput
            : nil
    }

    private func selectTab(
        _ tab: Int,
        playsAudio: Bool = true,
        entersControllerContent: Bool = true
    ) {
        guard (0..<menuTabCount).contains(tab), tab != selectedTab else { return }
        if playsAudio {
            // ARMSX2's UI audio packs, not built in this app.
            #if ARMSX3_PHASE10
            MenuAudioPackManager.shared.playEvent(.tabTransition)
            #endif
        }
        controllerTabEntryTask?.cancel()
        controllerTabEntryTask = nil
        if controllerInput.hasConnectedController,
           controllerInput.isControllerNavigationEnabled {
            // Publish the new tab-bar focus before the content selection. This
            // keeps UIKit and the custom orb anchor on one index throughout
            // the retained-page transaction instead of showing the old tab.
            controllerTabFocusState.focusedIndex = tab
        }
        menuLargeTitleMorphResetTask?.cancel()
        menuLargeTitleMorphActive = true
        if animatesRetainedPageTransitions {
            withAnimation(.easeInOut(duration: 0.24)) {
                titleMorphSelection = tab
                selectedTab = tab
            }
        } else {
            // Landscape keeps the cover-flow and retained pages out of the
            // animation transaction. Only the tiny title/toolbar state uses
            // the morph animation, avoiding a full glass-card relayout.
            var transaction = Transaction(animation: nil)
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                selectedTab = tab
            }
            withAnimation(.easeInOut(duration: 0.24)) {
                titleMorphSelection = tab
            }
        }
        if controllerInput.hasConnectedController,
           controllerInput.isControllerNavigationEnabled,
           entersControllerContent {
            // Establish the destination page's initial focus before accepting
            // another direction. The router serializes a movement received
            // during mounting, so Down advances from Language instead of being
            // misinterpreted as another request to enter Language.
            scheduleControllerContentEntry(for: tab)
        }
        menuLargeTitleMorphResetTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(280))
            guard !Task.isCancelled else { return }
            menuLargeTitleMorphActive = false
            menuLargeTitleMorphResetTask = nil
        }
    }

    private func scheduleControllerContentEntry(for tab: Int) {
        guard (0..<menuTabCount).contains(tab) else { return }
        controllerTabEntryTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled, selectedTab == tab else { return }
            if tab == 0 {
                controllerInput.requestLibraryEntry(preferLast: false)
            } else {
                _ = controllerInput.requestNavigationSessionEntry(
                    preferLast: false,
                    matchingScopePrefix: tab == 1 ? "menu.bios" : "settings."
                )
            }
            controllerTabEntryTask = nil
        }
    }

    private func navigateFromHorizontalSwipe(_ translation: CGFloat) {
        let physicalStep = translation < 0 ? 1 : -1
        let logicalStep = layoutDirection == .rightToLeft ? -physicalStep : physicalStep
        selectTab(selectedTab + logicalStep)
    }

    var body: some View {
        ZStack {
            Group {
                ZStack {
                    // Keep only the selected page in the UIKit hierarchy. Retaining
                    // three complete NavigationStack/List trees left inactive native
                    // focus environments and toolbar items attached to the window.
                    // That could make BIOS display Games chrome in touch mode and made
                    // the Settings focus engine reconcile overlapping target graphs.
                    if selectedTab == 0 {
                        // The library registers its cards with this frame's router and hands tab and boundary commands back here.
                        GameListView(
                            model: library,
                            controllerInput: activeControllerInput,
                            onPreviousTab: selectPreviousControllerTab,
                            onNextTab: selectNextControllerTab,
                            onBoundary: handleSharedControllerBoundary
                        )
                            // Past AX3 the favorite star covers the cover art.
                            .dynamicTypeSize(...DynamicTypeSize.accessibility3)
                            .environment(\.menuTabIsActive, true)
                            .environment(
                                \.menuLargeTitleIsSource,
                                titleMorphSelection == 0
                            )
                            .onPreferenceChange(LibraryMenuOpenKey.self) { libraryMenuOpen = $0 }
                    } else if selectedTab == 1 {
                        // A tab has nothing to dismiss to, so InstallView hides its Done.
                        InstallView(showsDone: false,
                                    controllerInput: activeControllerInput,
                                    onPreviousTab: selectPreviousControllerTab,
                                    onNextTab: selectNextControllerTab,
                                    onBoundary: handleSharedControllerBoundary,
                                    onInstalled: {
                            Task { await library.refresh() }
                        })
                            .environment(\.menuTabIsActive, true)
                            .environment(
                                \.menuLargeTitleIsSource,
                                titleMorphSelection == 1
                            )
                    }
                    if selectedTab == 2 {
                        SafeAreaProtectedMenuTabContent(
                            appliesLegacyLandscapeInsets: !settingsBackgroundActive
                        ) {
                            SettingsRootView(
                                navigationPath: $settingsNavigationPath,
                                resetToRootRequest: settingsRootResetRequest,
                                onNavigationPathActivityChanged: {
                                    settingsNavigationHasDestination = $0
                                },
                                controllerInput: activeControllerInput,
                                onPreviousControllerTab: selectPreviousControllerTab,
                                onNextControllerTab: selectNextControllerTab,
                                onControllerBoundary: handleSharedControllerBoundary
                            )
                        }
                        .environment(\.menuTabIsActive, true)
                        .environment(
                            \.menuLargeTitleIsSource,
                            titleMorphSelection == 2
                        )
                    }

                    NativeMenuTabSwipeRecognizer(
                        isEnabled: selectedTab != 2 || !settingsNavigationHasDestination,
                        onSwipe: navigateFromHorizontalSwipe
                    )
                    .frame(width: 0, height: 0)
                }
                .environment(\.menuLargeTitleNamespace, largeTitleNamespace)
                .environment(
                    \.menuLargeTitleMorphActive,
                    menuLargeTitleMorphActive
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentMargins(
                    .bottom,
                    tabContentBottomMargin,
                    for: .scrollContent
                )
                .environment(\.menuTabBarContentMargin, settingsTabBarContentMargin)
                .safeAreaInset(edge: .bottom, spacing: tabBarContentSpacing) {
                    ControllerFocusedMenuTabBar(
                        selection: tabSelection,
                        focusState: controllerTabFocusState,
                        controllerInput: controllerInput,
                        layout: bottomTabBarLayout,
                        titles: menuTabTitles.map { settings.localized($0) },
                        onReselect: { tab in
                            guard tab == 2 else { return }
                            settingsRootResetRequest += 1
                        }
                    )
                    .frame(maxWidth: .infinity)
                    // Centred on the whole screen, so the iPhone Duo's right-hand status column does not push the bar left.
                    .ignoresSafeArea(.container, edges: .horizontal)
                    // Half open like a book, the bar sits on the left leaf, off the fold.
                    .inLeftLeafInBookPose()
                    .opacity(tabBarHidden ? 0 : 1)
                    .allowsHitTesting(!tabBarHidden)
                    .accessibilityHidden(tabBarHidden)
                    // Retain the UIKit tab bar while OrbitKeys is presented so
                    // its title attributes and item identities survive dismissal.
                    .transaction { transaction in
                        transaction.animation = nil
                    }
                    .zIndex(1_000)
                }
            }
        }
        .environment(\.menuBackgroundHost, backgroundHost)
        .overlay {
            if activeControllerInput != nil {
                MenuTabControllerCommandListener(
                    controllerInput: controllerInput,
                    onEvent: handleControllerInputEvent
                )
            }
        }
        .background {
            GeometryReader { proxy in
                Color.clear
                    .allowsHitTesting(false)
                    .onAppear {
                        menuViewportSize = proxy.size
                    }
                    .onChange(of: proxy.size) { _, size in
                        guard menuViewportSize != size else { return }
                        menuViewportSize = size
                    }
            }
        }
        // One foreground field owns every controller-focus anchor in this
        // presentation, including cards, toolbars, alerts, and the tab bar.
        .controllerNavigationOrbOverlay(
            controllerInput: gameRenamePresented ? nil : activeControllerInput
        )
        // ARMSX2's theme preset shortcut, not built in this app.
        #if ARMSX3_PHASE10
        .overlay(alignment: .top) {
            // Theme Gallery observation and its modal state are unnecessary
            // while a Settings destination owns the whole content surface.
            // Remove the subtree instead of retaining it disabled.
            if !gameRenamePresented
                && (selectedTab != 2 || !settingsNavigationHasDestination) {
                MenuThemePresetShortcutOverlay(
                    controllerInput: controllerInput,
                    isEnabled: true
                )
                .padding(.horizontal, 24)
                .padding(.top, 12)
            }
        }
        #endif
        .onAppear {
            backgroundHost.reactivateForMenu(isAvailable: settings.hasCustomBackground)
            backgroundHost.setPresentationVisible(selectedTabShowsBackground)
        }
        .onChange(of: settings.hasCustomBackground) { _, hasBackground in
            backgroundHost.setMenuBackgroundAvailable(hasBackground)
        }
        .onChange(of: settings.dynamicBackgroundsEnabled) { _, _ in
            backgroundHost.setMenuBackgroundAvailable(settings.hasCustomBackground)
        }
        .onChange(of: selectedTabShowsBackground) { _, showsBackground in
            backgroundHost.setPresentationVisible(showsBackground)
        }
        .onDisappear {
            controllerTabEntryTask?.cancel()
            controllerTabEntryTask = nil
            menuLargeTitleMorphResetTask?.cancel()
            menuLargeTitleMorphResetTask = nil
            menuLargeTitleMorphActive = false
        }
    }

    private func handleControllerInputEvent(
        _ event: MenuControllerInputEvent
    ) {
        guard event.captureOwner == nil,
              !event.isNavigationSessionRouted else { return }
        // The persistent bottom bar remains the owner of its commands even
        // while the selected BIOS/Settings page has a registered session.
        if controllerInput.navigationZone == .tabBar {
            handleControllerTabBarCommand(event.command)
            return
        }
        guard !controllerInput.isNavigationCaptured else { return }
        switch event.command {
        case .previousTab:
            selectTab((selectedTab + menuTabCount - 1) % menuTabCount, playsAudio: false)
            controllerTabFocusState.focusedIndex = selectedTab
            controllerInput.playFeedback(.previousTab)
        case .nextTab:
            selectTab((selectedTab + 1) % menuTabCount, playsAudio: false)
            controllerTabFocusState.focusedIndex = selectedTab
            controllerInput.playFeedback(.nextTab)
        default:
            break
        }
    }

    @MainActor
    private func selectPreviousControllerTab() -> Bool {
        let next = (selectedTab + menuTabCount - 1) % menuTabCount
        selectTab(next, playsAudio: false)
        controllerTabFocusState.focusedIndex = next
        controllerInput.playFeedback(.previousTab)
        return true
    }

    @MainActor
    private func selectNextControllerTab() -> Bool {
        let next = (selectedTab + 1) % menuTabCount
        selectTab(next, playsAudio: false)
        controllerTabFocusState.focusedIndex = next
        controllerInput.playFeedback(.nextTab)
        return true
    }

    private func handleControllerTabBarCommand(
        _ command: MenuControllerCommand
    ) {
        switch command {
        case .left, .right:
            let physicalStep = command == .left ? -1 : 1
            let step = layoutDirection == .rightToLeft
                ? -physicalStep
                : physicalStep
            let next = min(
                menuTabCount - 1,
                max(0, controllerTabFocusState.focusedIndex + step)
            )
            guard next != controllerTabFocusState.focusedIndex else {
                controllerInput.playFeedback(.boundary)
                return
            }
            controllerTabFocusState.focusedIndex = next
            controllerInput.playFeedback(.move(command))
        case .activate:
            let focusedIndex = controllerTabFocusState.focusedIndex
            let transitionsTab = selectedTab != focusedIndex
            if selectedTab == focusedIndex {
                if focusedIndex == 2 {
                    settingsRootResetRequest += 1
                }
            } else {
                // Cross selects this tab and leaves the bar as focus owner.
                // Up is the explicit transition into the selected page.
                selectTab(
                    focusedIndex,
                    playsAudio: false,
                    entersControllerContent: false
                )
            }
            controllerInput.playFeedback(
                transitionsTab ? .tabTransition : .activate
            )
        case .up, .upLeft, .upRight:
            if selectedTab == 0 {
                controllerInput.requestLibraryEntry(preferLast: true)
                controllerInput.playFeedback(.move(.up))
            } else if controllerInput.requestNavigationSessionEntry(
                preferLast: true,
                matchingScopePrefix: selectedTab == 1 ? "menu.bios" : "settings."
            ) {
                controllerInput.playFeedback(.move(.up))
            } else {
                controllerInput.playFeedback(.boundary)
            }
        case .back:
            controllerTabFocusState.focusedIndex = selectedTab
            if selectedTab == 0 {
                controllerInput.setNavigationZone(.library)
            }
            controllerInput.playFeedback(.back)
        case .previousTab:
            controllerTabFocusState.focusedIndex = (
                controllerTabFocusState.focusedIndex + menuTabCount - 1
            ) % menuTabCount
            selectTab(
                controllerTabFocusState.focusedIndex,
                playsAudio: false,
                entersControllerContent: false
            )
            controllerInput.playFeedback(.previousTab)
        case .nextTab:
            controllerTabFocusState.focusedIndex = (
                controllerTabFocusState.focusedIndex + 1
            ) % menuTabCount
            selectTab(
                controllerTabFocusState.focusedIndex,
                playsAudio: false,
                entersControllerContent: false
            )
            controllerInput.playFeedback(.nextTab)
        case .down, .downLeft, .downRight:
            if selectedTab == 2,
               !settingsNavigationHasDestination,
               controllerInput.requestNavigationSessionEntry(
                   preferLast: false,
                   matchingScopePrefix: "settings.root"
               ) {
                controllerInput.playFeedback(.move(.down))
            } else {
                controllerInput.playFeedback(.boundary)
            }
        case .toggleFavorite, .showContextMenu, .start, .select:
            controllerInput.playFeedback(.boundary)
        }
    }

    @MainActor
    private func handleSharedControllerBoundary(
        _ direction: MenuControllerCommand
    ) -> Bool {
        guard direction == .down else { return false }
        controllerTabFocusState.focusedIndex = selectedTab
        controllerInput.setNavigationZone(.tabBar)
        return true
    }
}

#if !targetEnvironment(macCatalyst)
/// The tab bar is introduced with `safeAreaInset`, whose descendants do not
/// reliably publish anchor preferences back to the page overlay. Measure the
/// compact icon/title bounds here; the presentation-wide orb host consumes the
/// window-space rectangle directly, without reconstructing a second anchor.
private struct ControllerTabBarOrbOverlay: View {
    let exactFrame: CGRect?
    let segmentIndex: Int?
    let segmentCount: Int
    let segmentInset: CGFloat
    let permitsSegmentFallback: Bool
    let selection: Int
    let controllerInput: MenuControllerInputRouter
    var body: some View {
        GeometryReader { proxy in
            if controllerInput.hasConnectedController,
               controllerInput.isControllerNavigationEnabled,
               controllerInput.navigationZone == .tabBar,
               let focusedIndex = segmentIndex,
               let frame = resolvedFrame(in: proxy.size) {
                let overlayFrame = proxy.frame(in: .global)
                let windowFrame = frame.offsetBy(
                    dx: overlayFrame.minX,
                    dy: overlayFrame.minY
                )
                ZStack(alignment: .topLeading) {
                    ControllerBottomNavigationFocusGlow(cornerRadius: 18)
                        .frame(width: frame.width, height: frame.height)
                        .position(x: frame.midX, y: frame.midY)
                    Color.clear
                        .onAppear {
                            controllerInput.updateTabBarOrbFocusFrame(
                                windowFrame,
                                focusedIndex: focusedIndex,
                                selectedIndex: selection
                            )
                        }
                        .onChange(of: windowFrame) { _, next in
                            controllerInput.updateTabBarOrbFocusFrame(
                                next,
                                focusedIndex: focusedIndex,
                                selectedIndex: selection
                            )
                        }
                        .onChange(of: selection) { _, next in
                            controllerInput.updateTabBarOrbFocusFrame(
                                windowFrame,
                                focusedIndex: focusedIndex,
                                selectedIndex: next
                            )
                        }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func resolvedFrame(in size: CGSize) -> CGRect? {
        if let exactFrame,
           !exactFrame.isNull,
           !exactFrame.isInfinite,
           exactFrame.width > 1,
           exactFrame.height > 1 {
            return compactOrbitFrame(exactFrame)
        }
        guard permitsSegmentFallback,
              let segmentIndex,
              segmentCount > 0,
              (0..<segmentCount).contains(segmentIndex) else { return nil }
        let availableWidth = max(0, size.width - (segmentInset * 2))
        let segmentWidth = availableWidth / CGFloat(segmentCount)
        return compactOrbitFrame(CGRect(
            x: segmentInset + CGFloat(segmentIndex) * segmentWidth,
            y: segmentInset,
            width: segmentWidth,
            height: max(1, size.height - (segmentInset * 2))
        ))
    }

    /// The tab item owns a wide hit target, especially on iPad, but OrbitKeys'
    /// particles circle the visible key rather than the complete grid cell.
    /// Preserve the tab center while keeping the first and last ellipses from
    /// extending beyond the Games and Settings button surfaces.
    private func compactOrbitFrame(_ frame: CGRect) -> CGRect {
        let maximumWidth: CGFloat =
            UIDevice.current.userInterfaceIdiom == .pad ? 112 : 96
        let width = min(frame.width, maximumWidth)
        let height = min(frame.height, 40)
        return CGRect(
            x: frame.midX - (width / 2),
            y: frame.midY - (height / 2),
            width: width,
            height: height
        )
    }

}

/// Reads controller-only tab focus at the smallest possible observation scope.
/// Moving across the bar therefore updates only this surface instead of the
/// retained Games/BIOS/Settings hierarchy behind it.
private struct ControllerFocusedMenuTabBar: View {
    @Binding var selection: Int
    let focusState: MenuControllerTabFocusState
    let controllerInput: MenuControllerInputRouter
    let layout: MenuBottomTabBarLayout
    let titles: [String]
    let onReselect: (Int) -> Void
    @State private var nativeControllerFocusFrame: CGRect?

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.uiBottomTabBarColour) private var accentColour
    @Environment(\.uiBottomTabBarUnselectedColour) private var secondaryTextColour

    static var compactOffset: CGFloat {
        if #available(iOS 26.0, *) { return 24.5 }
        return -6
    }

    private var controllerFocusedIndex: Int? {
        controllerInput.hasConnectedController
            && controllerInput.isControllerNavigationEnabled
            && controllerInput.navigationZone == .tabBar
            ? focusState.focusedIndex
            : nil
    }

    @ViewBuilder
    var body: some View {
        Group {
            if #available(iOS 26.0, *) {
                NativeMenuTabBar(
                    selection: $selection,
                    controllerFocusedIndex: controllerFocusedIndex,
                    controllerFocusFrame: $nativeControllerFocusFrame,
                    controllerNavigationEnabled:
                        controllerInput.hasConnectedController
                            && controllerInput.isControllerNavigationEnabled,
                    accentColor: UIColor(accentColour),
                    secondaryTextColor: UIColor(secondaryTextColour),
                    isCompactHeight: verticalSizeClass == .compact,
                    layout: layout,
                    titles: titles,
                    onReselect: onReselect
                )
                .background {
                    ControllerTabBarOrbOverlay(
                        exactFrame: nativeControllerFocusFrame,
                        segmentIndex: controllerFocusedIndex,
                        segmentCount: menuTabCount,
                        segmentInset: 4,
                        permitsSegmentFallback: false,
                        selection: selection,
                        controllerInput: controllerInput
                    )
                }
                // Align the native iOS 26 tab bar with compact-height content.
                .offset(y: verticalSizeClass == .compact ? Self.compactOffset : 0)
            } else if controllerInput.hasConnectedController,
                      controllerInput.isControllerNavigationEnabled {
                LegacyGlassMenuTabBar(
                    selection: $selection,
                    controllerFocusedIndex: controllerFocusedIndex,
                    layout: layout,
                    titles: titles,
                    onReselect: onReselect
                )
                .background {
                    ControllerTabBarOrbOverlay(
                        exactFrame: nil,
                        segmentIndex: controllerFocusedIndex,
                        segmentCount: menuTabCount,
                        segmentInset: 4,
                        permitsSegmentFallback: true,
                        selection: selection,
                        controllerInput: controllerInput
                    )
                }
                // Raise the legacy compact tab bar above the home indicator.
                .offset(y: verticalSizeClass == .compact ? Self.compactOffset : 0)
            } else {
                OriginalLegacyGlassMenuTabBar(
                    selection: $selection,
                    layout: layout,
                    titles: titles,
                    onReselect: onReselect
                )
                // Raise the legacy compact tab bar above the home indicator.
                .offset(y: verticalSizeClass == .compact ? Self.compactOffset : 0)
            }
        }
        .padding(.bottom, layout.bottomClearance)
    }

}

/// iOS 17/18 does not provide the floating Liquid Glass tab bar used by iOS 26.
/// Keep one compact, orientation-independent glass capsule so landscape uses
/// the same centered icon-over-label layout and dimensions as portrait.
private struct LegacyGlassMenuTabBar: View {
    @Binding var selection: Int
    let controllerFocusedIndex: Int?
    let layout: MenuBottomTabBarLayout
    let titles: [String]
    let onReselect: (Int) -> Void
    @Environment(\.uiBottomTabBarColour) private var accentColour
    @Environment(\.uiBottomTabBarUnselectedColour) private var secondaryTextColour
    private let systemImages = menuTabSystemImages

    private var barWidth: CGFloat {
        UIDevice.current.userInterfaceIdiom == .pad ? 360 : 276
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(systemImages.indices, id: \.self) { index in
                let controllerFocused = controllerFocusedIndex == index
                Button {
                    if selection == index {
                        onReselect(index)
                    } else {
                        selection = index
                        UISelectionFeedbackGenerator().selectionChanged()
                    }
                } label: {
                    VStack(spacing: 1) {
                        Image(systemName: systemImages[index])
                            .font(
                                .system(
                                    size: 20 * layout.iconScale,
                                    weight: .medium
                                )
                            )
                            .symbolRenderingMode(.monochrome)
                            .scaleEffect(selection == index ? 1.06 : 1)

                        if layout.showsLabels {
                            Text(
                                titles.indices.contains(index)
                                    ? titles[index]
                                    : ""
                            )
                            .font(
                                .system(
                                    size: 10 * layout.labelScale,
                                    weight: .semibold
                                )
                            )
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        }
                    }
                    .foregroundStyle(
                        controllerFocused
                            ? accentColour
                            : secondaryTextColour
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Capsule())
                    .background {
                        ZStack {
                            if selection == index {
                                Capsule()
                                    .fill(.ultraThinMaterial)
                                    .overlay {
                                        Capsule()
                                            .stroke(
                                                Color.primary.opacity(0.14),
                                                lineWidth: 0.65
                                            )
                                    }
                            }
                        }
                    }
                }
                .buttonStyle(LegacyGlassTabButtonStyle())
                .focusEffectDisabled()
                .accessibilityLabel(
                    titles.indices.contains(index) ? titles[index] : ""
                )
                .accessibilityAddTraits(
                    selection == index ? .isSelected : []
                )
            }
        }
        .padding(4)
        .frame(width: barWidth)
        .frame(height: layout.height)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay {
            Capsule()
                .stroke(Color.primary.opacity(0.13), lineWidth: 0.65)
        }
        .shadow(color: .black.opacity(0.12), radius: 12, y: 5)
    }
}

/// The pre-controller tab bar copied from Master. Keeping it as a separate
/// type guarantees that disconnecting replaces the enhanced subtree and cannot
/// leave controller glass, colors, focus effects, or animation state behind.
private struct OriginalLegacyGlassMenuTabBar: View {
    @Binding var selection: Int
    let layout: MenuBottomTabBarLayout
    let titles: [String]
    let onReselect: (Int) -> Void
    @Namespace private var selectionNamespace
    @Environment(\.uiBottomTabBarColour) private var accentColour
    @Environment(\.uiBottomTabBarUnselectedColour) private var secondaryTextColour

    private let systemImages = menuTabSystemImages

    private let selectionSpring = Animation.spring(
        response: 0.5,
        dampingFraction: 0.7,
        blendDuration: 0.18
    )

    var body: some View {
        HStack(spacing: 0) {
            ForEach(systemImages.indices, id: \.self) { index in
                Button {
                    if selection == index {
                        onReselect(index)
                    } else {
                        selection = index
                        UISelectionFeedbackGenerator().selectionChanged()
                    }
                } label: {
                    VStack(spacing: 1) {
                        Image(systemName: systemImages[index])
                            .font(
                                .system(
                                    size: 20 * layout.iconScale,
                                    weight: .medium
                                )
                            )
                            .symbolRenderingMode(.monochrome)
                            .scaleEffect(selection == index ? 1.06 : 1)

                        if layout.showsLabels {
                            Text(
                                titles.indices.contains(index)
                                    ? titles[index]
                                    : ""
                            )
                            .font(
                                .system(
                                    size: 10 * layout.labelScale,
                                    weight: .semibold
                                )
                            )
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        }
                    }
                    .foregroundStyle(
                        selection == index
                            ? accentColour
                            : secondaryTextColour
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Capsule())
                    .background {
                        if selection == index {
                            Capsule()
                                .fill(.ultraThinMaterial)
                                .overlay {
                                    Capsule()
                                        .fill(accentColour.opacity(0.075))
                                }
                                .overlay {
                                    Capsule()
                                        .stroke(
                                            accentColour.opacity(0.2),
                                            lineWidth: 0.65
                                        )
                                }
                                .matchedGeometryEffect(
                                    id: "legacy.tab.selection",
                                    in: selectionNamespace
                                )
                        }
                    }
                }
                .buttonStyle(LegacyGlassTabButtonStyle())
                .accessibilityLabel(
                    titles.indices.contains(index) ? titles[index] : ""
                )
                .accessibilityAddTraits(
                    selection == index ? .isSelected : []
                )
            }
        }
        .padding(4)
        .frame(width: 276)
        .frame(height: layout.height)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay {
            Capsule()
                .stroke(Color.primary.opacity(0.13), lineWidth: 0.65)
        }
        .shadow(color: .black.opacity(0.12), radius: 12, y: 5)
        .animation(selectionSpring, value: selection)
    }
}

private struct LegacyGlassTabButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.78 : 1)
            .animation(
                .spring(
                    response: 0.25,
                    dampingFraction: 0.62,
                    blendDuration: 0.08
                ),
                value: configuration.isPressed
            )
    }
}
#endif

#if !targetEnvironment(macCatalyst)
/// A real UIKit tab bar kept outside every page's GlassEffectContainer.
/// This gives the bar its native system appearance while guaranteeing that it
/// remains a foreground sibling rather than being composited below tab content.
private struct NativeMenuTabBar: UIViewRepresentable {
    @Binding var selection: Int
    let controllerFocusedIndex: Int?
    @Binding var controllerFocusFrame: CGRect?
    let controllerNavigationEnabled: Bool
    let accentColor: UIColor
    let secondaryTextColor: UIColor
    let isCompactHeight: Bool
    let layout: MenuBottomTabBarLayout
    let titles: [String]
    let onReselect: (Int) -> Void

    private let systemImages = menuTabSystemImages

    func makeCoordinator() -> Coordinator {
        Coordinator(
            selection: $selection,
            controllerFocusFrame: $controllerFocusFrame,
            onReselect: onReselect
        )
    }

    func makeUIView(context: Context) -> LegacyCompatibleMenuTabBar {
        let tabBar = LegacyCompatibleMenuTabBar()
        tabBar.delegate = context.coordinator
        if controllerNavigationEnabled {
            tabBar.onControllerFocusFrameChanged =
                context.coordinator.publishControllerFocusFrame
        } else {
            tabBar.onControllerFocusFrameChanged = nil
        }
        tabBar.controllerNavigationEnabled = controllerNavigationEnabled
        configureAppearance(of: tabBar)
        tabBar.items = makeItems()
        tabBar.configurationKey = configurationKey
        if let items = tabBar.items, items.indices.contains(selection) {
            tabBar.selectedItem = items[selection]
        }
        tabBar.setSelectedIndex(selection, animated: false)
        tabBar.controllerFocusedIndex = controllerNavigationEnabled
            ? controllerFocusedIndex
            : nil
        if controllerNavigationEnabled {
            applyControllerFocusColors(to: tabBar)
        }
        return tabBar
    }

    func updateUIView(
        _ tabBar: LegacyCompatibleMenuTabBar,
        context: Context
    ) {
        context.coordinator.selection = $selection
        context.coordinator.controllerFocusFrame = $controllerFocusFrame
        context.coordinator.onReselect = onReselect
        if controllerNavigationEnabled {
            tabBar.onControllerFocusFrameChanged =
                context.coordinator.publishControllerFocusFrame
        } else {
            tabBar.onControllerFocusFrameChanged = nil
        }
        tabBar.controllerNavigationEnabled = controllerNavigationEnabled
        if tabBar.configurationKey != configurationKey {
            configureAppearance(of: tabBar)
            tabBar.items = makeItems()
            tabBar.configurationKey = configurationKey
        }

        // Keep the same UITabBar and item identities, but do not morph its
        // selection glass between tabs. The tab bar remains geometrically
        // stable while title/top-toolbar transitions animate independently.
        if controllerNavigationEnabled {
            UIView.performWithoutAnimation {
                if let items = tabBar.items,
                   items.indices.contains(selection) {
                    tabBar.selectedItem = items[selection]
                }
                tabBar.setSelectedIndex(selection, animated: false)
                tabBar.layoutIfNeeded()
            }
        } else {
            if let items = tabBar.items,
               items.indices.contains(selection) {
                tabBar.selectedItem = items[selection]
            }
            tabBar.setSelectedIndex(selection, animated: true)
        }
        tabBar.controllerFocusedIndex = controllerNavigationEnabled
            ? controllerFocusedIndex
            : nil
        if controllerNavigationEnabled {
            applyControllerFocusColors(to: tabBar)
        }
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: LegacyCompatibleMenuTabBar,
        context: Context
    ) -> CGSize? {
        // An overlay dismissal can briefly give SwiftUI a nil/zero proposal.
        // Retain the container width instead of the tab bar's prior intrinsic
        // width, which fixes the post-stop narrow bar until the next tab swap.
        // The current UIWindow is authoritative. Taking the maximum with the
        // view's previous bounds retained a landscape width after rotating to
        // portrait; trusting a transient SwiftUI proposal first caused the bar
        // to squeeze while retained tabs swapped.
        let width = uiView.resolvedMeasurementWidth(
            windowWidth: uiView.safeWindowWidth,
            containerWidth: uiView.superview?.bounds.width ?? 0,
            boundsWidth: uiView.bounds.width,
            proposalWidth: proposal.width ?? 0
        )
        return CGSize(width: width, height: layout.height)
    }

    private var configurationKey: String {
        "\(controllerNavigationEnabled)|\(isCompactHeight)|\(accentColor)|\(secondaryTextColor)|\(layout.height)|\(layout.iconScale)|\(layout.labelScale)|\(layout.showsLabels)|\(titles.joined(separator: "\u{1F}"))"
    }

    private func makeItems() -> [UITabBarItem] {
        systemImages.indices.map { index in
            let item = UITabBarItem(
                title: layout.showsLabels && titles.indices.contains(index)
                    ? titles[index]
                    : nil,
                image: itemImage(at: index),
                tag: index
            )
            item.accessibilityLabel = titles.indices.contains(index)
                ? titles[index]
                : nil
            return item
        }
    }

    private func itemImage(at index: Int) -> UIImage? {
        let configuration = UIImage.SymbolConfiguration(
            pointSize: (isCompactHeight ? 25 : 23) * layout.iconScale,
            weight: .regular
        )
        return UIImage(
            systemName: systemImages[index],
            withConfiguration: configuration
        )
    }

    private func applyControllerFocusColors(
        to tabBar: LegacyCompatibleMenuTabBar
    ) {
        guard let items = tabBar.items else { return }
        for index in items.indices {
            let isControllerFocused = controllerFocusedIndex == index
            let baseImage = itemImage(at: index)
            if isControllerFocused {
                let focusedImage = baseImage?.withTintColor(
                    accentColor,
                    renderingMode: .alwaysOriginal
                )
                items[index].image = focusedImage
                items[index].selectedImage = focusedImage
            } else {
                items[index].image = baseImage
                items[index].selectedImage = baseImage
            }

            items[index].setTitleTextAttributes(
                [.foregroundColor: isControllerFocused
                    ? accentColor
                    : secondaryTextColor],
                for: .normal
            )
            items[index].setTitleTextAttributes(
                [.foregroundColor: accentColor],
                for: .selected
            )
        }
    }

    private func configureAppearance(of tabBar: LegacyCompatibleMenuTabBar) {
        tabBar.tintColor = accentColor
        tabBar.unselectedItemTintColor = secondaryTextColor
        tabBar.accentColor = accentColor
        tabBar.isTranslucent = true
        tabBar.isOpaque = false
        tabBar.backgroundColor = .clear
        tabBar.backgroundImage = UIImage()
        tabBar.shadowImage = UIImage()

        let appearance = tabBar.standardAppearance.copy()
        appearance.configureWithTransparentBackground()
        appearance.backgroundColor = .clear
        appearance.backgroundEffect = nil
        appearance.shadowColor = .clear

        let allLayouts = [
            appearance.stackedLayoutAppearance,
            appearance.inlineLayoutAppearance,
            appearance.compactInlineLayoutAppearance,
        ]
        for layout in allLayouts {
            layout.normal.iconColor = secondaryTextColor
            layout.normal.titleTextAttributes[.foregroundColor] =
                secondaryTextColor
            layout.selected.iconColor = accentColor
            layout.selected.titleTextAttributes[.foregroundColor] =
                accentColor
            let baseSize: CGFloat = isCompactHeight ? 13 : 10
            layout.normal.titleTextAttributes[.font] = UIFont.systemFont(
                ofSize: baseSize * self.layout.labelScale,
                weight: .regular
            )
            layout.selected.titleTextAttributes[.font] = UIFont.systemFont(
                ofSize: baseSize * self.layout.labelScale,
                weight: .semibold
            )
        }

        if #available(iOS 26.0, *) {
            // Tint UIKit's own Liquid Glass selection pill instead of replacing
            // it. Its blur, refraction, morph, and press effects remain native.
            appearance.selectionIndicatorImage = nil
            appearance.selectionIndicatorTintColor = controllerNavigationEnabled
                ? .clear
                : accentColor.withAlphaComponent(0.18)
            tabBar.usesLegacySelectionPill = false
        } else {
            // iOS 17/18 has no native floating tab capsule. Suppress UIKit's
            // rectangular bar/indicator and render a material outer pill with
            // a live, animated monochrome-blue selection pill below.
            appearance.selectionIndicatorImage = UIImage()
            appearance.selectionIndicatorTintColor = .clear
            tabBar.usesLegacySelectionPill = true

        }
        tabBar.standardAppearance = appearance
        tabBar.scrollEdgeAppearance = appearance
    }

    final class LegacyCompatibleMenuTabBar: UITabBar {
        var configurationKey = ""
        var onControllerFocusFrameChanged: ((CGRect?) -> Void)?
        var controllerNavigationEnabled = false
        var accentColor: UIColor = .systemBlue {
            didSet {
                legacySelectionPill.updateAccentColor(accentColor)
            }
        }
        private var stableMeasurementWidth: CGFloat = 0

        var isCompactHeightLayout = false {
            didSet {
                guard oldValue != isCompactHeightLayout else { return }
                setNeedsLayout()
            }
        }

        var usesLegacySelectionPill = false {
            didSet {
                legacyBarPill.isHidden = !usesLegacySelectionPill
                legacySelectionPill.isHidden = !usesLegacySelectionPill
                setNeedsLayout()
            }
        }

        var controllerFocusedIndex: Int? {
            didSet {
                guard oldValue != controllerFocusedIndex else { return }
                updateControllerFocusPill(animated: window != nil)
            }
        }

        private let legacyBarPill = LegacyBarPillView()
        private let legacySelectionPill = LegacySelectionPillView()
        private let controllerFocusPill = ControllerFocusPillView()
        private var selectedIndex = 0

        // The window's width inside its left and right safe-area insets, 0 before a window.
        var safeWindowWidth: CGFloat {
            guard let window else { return 0 }
            return window.bounds.width - window.safeAreaInsets.left - window.safeAreaInsets.right
        }

        /// SwiftUI can detach a retained safe-area inset for one reconciliation
        /// pass while changing tabs. During that pass both its proposal and
        /// temporary superview may be the width of one tab item. Keep the last
        /// valid bar width until the UIWindow publishes a new authoritative
        /// width (including after rotation or Stage Manager resizing).
        func resolvedMeasurementWidth(
            windowWidth: CGFloat,
            containerWidth: CGFloat,
            boundsWidth: CGFloat,
            proposalWidth: CGFloat
        ) -> CGFloat {
            if windowWidth > 1 {
                stableMeasurementWidth = windowWidth
                return windowWidth
            }
            if stableMeasurementWidth > 1 {
                return stableMeasurementWidth
            }
            let proposedInitialWidth = [
                containerWidth,
                boundsWidth,
                proposalWidth,
            ].first(where: { $0 > 1 }) ?? 0
            // A representable can be measured before it has a window. The
            // first proposal is sometimes only one tab-item wide, which makes
            // UIKit cache titles such as "G", "B", and "S". Start from a
            // complete compact bar until the UIWindow supplies its exact size.
            let minimumBarWidth = min(
                UIScreen.main.bounds.width,
                UIDevice.current.userInterfaceIdiom == .pad ? 360 : 276
            )
            let initialWidth = max(proposedInitialWidth, minimumBarWidth)
            stableMeasurementWidth = initialWidth
            return initialWidth
        }

        override init(frame: CGRect) {
            super.init(frame: frame)
            legacyBarPill.isHidden = true
            legacyBarPill.isUserInteractionEnabled = false
            legacySelectionPill.isHidden = true
            legacySelectionPill.isUserInteractionEnabled = false
            controllerFocusPill.isHidden = true
            controllerFocusPill.isUserInteractionEnabled = false
            addSubview(legacyBarPill)
            addSubview(legacySelectionPill)
            addSubview(controllerFocusPill)
        }

        required init?(coder: NSCoder) {
            super.init(coder: coder)
            legacyBarPill.isHidden = true
            legacyBarPill.isUserInteractionEnabled = false
            legacySelectionPill.isHidden = true
            legacySelectionPill.isUserInteractionEnabled = false
            controllerFocusPill.isHidden = true
            controllerFocusPill.isUserInteractionEnabled = false
            addSubview(legacyBarPill)
            addSubview(legacySelectionPill)
            addSubview(controllerFocusPill)
        }

        func setSelectedIndex(_ index: Int, animated: Bool) {
            let changed = selectedIndex != index
            selectedIndex = index
            updateLegacySelectionPill(
                animated: animated && changed && window != nil
            )
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            updateLegacyBarLayout()
            updateLegacySelectionPill(animated: false)
            updateControllerFocusPill(animated: false)
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            // The safe-area width, as sizeThatFits measures it.
            guard safeWindowWidth > 1 else { return }
            stableMeasurementWidth = safeWindowWidth
            invalidateIntrinsicContentSize()
            setNeedsLayout()
        }

        private var legacyBarFrame: CGRect {
            let maximumWidth: CGFloat = isCompactHeightLayout ? 420 : 360
            let proportionalWidth = bounds.width * 0.84
            let width = min(
                max(0, bounds.width - 24),
                proportionalWidth,
                maximumWidth
            )
            return CGRect(
                x: (bounds.width - width) / 2,
                y: 2,
                width: width,
                height: max(0, bounds.height - 4)
            )
        }

        private func legacyItemControls() -> [UIControl] {
            let controls = subviews
                .compactMap { $0 as? UIControl }
                .sorted { $0.frame.minX < $1.frame.minX }
            if controllerNavigationEnabled {
                for control in controls {
                    control.focusEffect = nil
                }
            }
            return controls
        }

        /// Native floating tab bars can nest their item controls beneath a
        /// private content container. Discover the first control on each
        /// branch and always convert its bounds into this tab bar's coordinate
        /// space before publishing controller-orb geometry.
        private func controllerItemControls() -> [UIControl] {
            var controls: [UIControl] = []

            func collectControls(in view: UIView) {
                for subview in view.subviews {
                    if let control = subview as? UIControl {
                        controls.append(control)
                    } else {
                        collectControls(in: subview)
                    }
                }
            }

            collectControls(in: self)
            let visibleControls = controls.filter { control in
                let frame = control.convert(control.bounds, to: self)
                return !control.isHidden
                    && control.alpha > 0.01
                    && frame.width > 1
                    && frame.height > 1
                    && frame.intersects(bounds)
            }
            let expectedCount = items?.count ?? 0

            // Prefer UIKit's accessibility relationship: unlike private view
            // class names, the tab item's title remains stable across native
            // floating-bar layouts and OS releases.
            if expectedCount > 0, let items {
                let semanticControls = items.compactMap { item -> UIControl? in
                    guard let title = item.title, !title.isEmpty else {
                        return nil
                    }
                    return visibleControls
                        .filter { control in
                            guard let label = control.accessibilityLabel else {
                                return false
                            }
                            return label.compare(
                                title,
                                options: [.caseInsensitive, .diacriticInsensitive]
                            ) == .orderedSame
                                || label.localizedCaseInsensitiveContains(title)
                        }
                        .max { lhs, rhs in
                            let leftFrame = lhs.convert(lhs.bounds, to: self)
                            let rightFrame = rhs.convert(rhs.bounds, to: self)
                            return leftFrame.width * leftFrame.height
                                < rightFrame.width * rightFrame.height
                        }
                }
                let uniqueSemanticControls = Set(
                    semanticControls.map(ObjectIdentifier.init)
                )
                if semanticControls.count == expectedCount,
                   uniqueSemanticControls.count == expectedCount {
                    if controllerNavigationEnabled {
                        for control in semanticControls {
                            control.focusEffect = nil
                        }
                    }
                    return semanticControls
                }
            }

            // Some iOS 26 layouts omit the accessibility label from the outer
            // item control. Collapse controls sharing the same horizontal
            // center and retain the largest surface for that tab; otherwise
            // nested controls from Games can occupy the BIOS/Settings indices.
            let sortedControls = visibleControls.sorted {
                $0.convert($0.bounds, to: self).minX
                    < $1.convert($1.bounds, to: self).minX
            }
            var horizontalGroups: [[UIControl]] = []
            for control in sortedControls {
                let centerX = control.convert(control.bounds, to: self).midX
                if let lastGroup = horizontalGroups.last,
                   let representative = lastGroup.first {
                    let representativeX = representative.convert(
                        representative.bounds,
                        to: self
                    ).midX
                    if abs(centerX - representativeX) <= 6 {
                        horizontalGroups[horizontalGroups.count - 1]
                            .append(control)
                        continue
                    }
                }
                horizontalGroups.append([control])
            }
            let groupedControls = horizontalGroups.compactMap { group in
                group.max { lhs, rhs in
                    let leftFrame = lhs.convert(lhs.bounds, to: self)
                    let rightFrame = rhs.convert(rhs.bounds, to: self)
                    return leftFrame.width * leftFrame.height
                        < rightFrame.width * rightFrame.height
                }
            }
            let resolvedControls = groupedControls.count == expectedCount
                ? groupedControls
                : sortedControls
            if controllerNavigationEnabled {
                for control in resolvedControls {
                    control.focusEffect = nil
                }
            }
            return resolvedControls
        }

        private func updateLegacyBarLayout() {
            guard usesLegacySelectionPill else {
                setSystemBarBackgroundHidden(false)
                return
            }

            setSystemBarBackgroundHidden(true)
            let barFrame = legacyBarFrame
            legacyBarPill.frame = barFrame
            legacyBarPill.updateCornerRadius(barFrame.height / 2)

            let itemControls = legacyItemControls()
            guard !itemControls.isEmpty else { return }
            let contentFrame = barFrame.insetBy(dx: 8, dy: 0)
            let itemWidth = contentFrame.width / CGFloat(itemControls.count)
            for (index, control) in itemControls.enumerated() {
                control.frame = CGRect(
                    x: contentFrame.minX + (CGFloat(index) * itemWidth),
                    y: contentFrame.minY,
                    width: itemWidth,
                    height: contentFrame.height
                )
            }

            insertSubview(legacyBarPill, at: 0)
            if let firstItem = itemControls.first {
                insertSubview(legacySelectionPill, belowSubview: firstItem)
            }
        }

        private func setSystemBarBackgroundHidden(_ hidden: Bool) {
            for view in subviews {
                let className = NSStringFromClass(type(of: view))
                if className.contains("BarBackground") {
                    view.isHidden = hidden
                }
            }
        }

        private func updateLegacySelectionPill(animated: Bool) {
            guard usesLegacySelectionPill else { return }

            let itemControls = legacyItemControls()
            guard itemControls.indices.contains(selectedIndex) else {
                return
            }

            if let firstItem = itemControls.first {
                insertSubview(legacySelectionPill, belowSubview: firstItem)
            }

            let itemFrame = itemControls[selectedIndex].frame
            // Slightly overlap each slot so the selected pill feels broader
            // than the icon/title pair while remaining inside the outer pill.
            let proposedFrame = itemFrame.insetBy(dx: -5, dy: 4)
            let targetFrame = proposedFrame.intersection(
                legacyBarFrame.insetBy(dx: 4, dy: 4)
            )
            legacySelectionPill.updateCornerRadius(
                max(0, targetFrame.height / 2)
            )

            let updates = {
                self.legacySelectionPill.frame = targetFrame
                self.legacySelectionPill.alpha = 1
            }
            if animated {
                UIView.animate(
                    withDuration: 0.36,
                    delay: 0,
                    usingSpringWithDamping: 0.82,
                    initialSpringVelocity: 0.18,
                    options: [.beginFromCurrentState, .allowUserInteraction],
                    animations: updates
                )
            } else {
                updates()
            }
        }

        private func updateControllerFocusPill(animated: Bool) {
            let itemControls = controllerItemControls()
            guard let controllerFocusedIndex else {
                controllerFocusPill.isHidden = true
                onControllerFocusFrameChanged?(nil)
                return
            }
            // UIKit briefly removes its item controls while rotating or
            // reconciling the native bar. Retain the last valid orb anchor
            // during that pass instead of publishing a false disappearance.
            guard itemControls.indices.contains(controllerFocusedIndex) else {
                // Keep the last valid neon pill visible too; UIKit will lay
                // out the requested item again on the next pass.
                return
            }

            controllerFocusPill.isHidden = false
            if let firstItem = itemControls.first {
                insertSubview(controllerFocusPill, belowSubview: firstItem)
            }
            let itemControl = itemControls[controllerFocusedIndex]
            let itemFrame = itemControl.convert(itemControl.bounds, to: self)
            let boundsFrame = usesLegacySelectionPill
                ? legacyBarFrame.insetBy(dx: 4, dy: 4)
                : bounds.insetBy(dx: 8, dy: 3)
            let visualContentFrame = itemVisualContentFrame(in: itemControl)
            let centerX = visualContentFrame?.midX ?? itemFrame.midX
            let minimumWidth: CGFloat =
                UIDevice.current.userInterfaceIdiom == .pad ? 120 : 0
            let focusWidth = max(itemFrame.width + 8, minimumWidth)
            let proposedFrame = CGRect(
                x: centerX - (focusWidth / 2),
                y: itemFrame.minY + 3,
                width: focusWidth,
                height: max(1, itemFrame.height - 6)
            )
            let targetFrame = proposedFrame
                .intersection(boundsFrame)
            // UIKit can briefly hand us an empty intersection while the tab
            // bar is being reparented during rotation. Keep the last valid
            // SwiftUI orb anchor until the next layout pass resolves it.
            guard !targetFrame.isNull,
                  !targetFrame.isInfinite,
                  targetFrame.width > 1,
                  targetFrame.height > 1 else {
                // Preserve the preceding valid focus surface during a
                // transient zero/intersecting layout pass.
                return
            }
            onControllerFocusFrameChanged?(
                controllerOrbFrame(
                    centeredAt: centerX,
                    visualContentFrame: visualContentFrame,
                    focusFrame: targetFrame
                )
            )
            controllerFocusPill.updateCornerRadius(
                max(0, targetFrame.height / 2)
            )

            let updates = {
                self.controllerFocusPill.frame = targetFrame
                self.controllerFocusPill.alpha = 1
            }
            if animated {
                UIView.animate(
                    withDuration: 0.28,
                    delay: 0,
                    usingSpringWithDamping: 0.84,
                    initialSpringVelocity: 0.12,
                    options: [.beginFromCurrentState, .allowUserInteraction],
                    animations: updates
                )
            } else {
                updates()
            }
        }

        private func itemVisualContentFrame(in control: UIControl) -> CGRect? {
            var contentFrame: CGRect?

            func collectContent(from view: UIView) {
                for subview in view.subviews
                where !subview.isHidden && subview.alpha > 0.01 {
                    if (subview is UILabel || subview is UIImageView),
                       subview.bounds.width > 1,
                       subview.bounds.height > 1 {
                        let frame = subview.convert(subview.bounds, to: self)
                        contentFrame = contentFrame.map { $0.union(frame) }
                            ?? frame
                    }
                    collectContent(from: subview)
                }
            }

            collectContent(from: control)
            return contentFrame
        }

        private func controllerOrbFrame(
            centeredAt centerX: CGFloat,
            visualContentFrame: CGRect?,
            focusFrame: CGRect
        ) -> CGRect {
            let maximumWidth: CGFloat =
                UIDevice.current.userInterfaceIdiom == .pad ? 112 : 96
            let contentWidth = (visualContentFrame?.width ?? 64) + 24
            let width = min(
                focusFrame.width,
                min(maximumWidth, max(72, contentWidth))
            )
            let contentHeight = (visualContentFrame?.height ?? 32) + 8
            let height = min(focusFrame.height, max(30, contentHeight))
            let centerY = visualContentFrame?.midY ?? focusFrame.midY
            let proposedFrame = CGRect(
                x: centerX - (width / 2),
                y: centerY - (height / 2),
                width: width,
                height: height
            )
            return proposedFrame.intersection(focusFrame)
        }
    }

    final class ControllerFocusPillView: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isOpaque = false
            backgroundColor = .clear
            // SwiftUI renders the selected UI Focus Box style from this
            // view's measured frame. Keep UIKit's focus marker transparent so
            // native and legacy tab bars use the same visual implementation.
            layer.borderWidth = 0
            layer.shadowOpacity = 0
        }

        required init?(coder: NSCoder) { nil }

        func updateCornerRadius(_ radius: CGFloat) {
            layer.cornerRadius = radius
        }
    }

    final class LegacyBarPillView: UIView {
        private let materialView = UIVisualEffectView(
            effect: UIBlurEffect(style: .systemUltraThinMaterial)
        )

        override init(frame: CGRect) {
            super.init(frame: frame)
            isOpaque = false

            layer.shadowColor = UIColor.black.cgColor
            layer.shadowOpacity = 0.2
            layer.shadowRadius = 14
            layer.shadowOffset = CGSize(width: 0, height: 6)

            materialView.isUserInteractionEnabled = false
            materialView.clipsToBounds = true
            addSubview(materialView)
        }

        required init?(coder: NSCoder) {
            nil
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            materialView.frame = bounds
            layer.shadowPath = UIBezierPath(
                roundedRect: bounds,
                cornerRadius: layer.cornerRadius
            ).cgPath
        }

        func updateCornerRadius(_ radius: CGFloat) {
            layer.cornerRadius = radius
            materialView.layer.cornerRadius = radius
            materialView.layer.borderWidth = 0.65
            materialView.layer.borderColor =
                UIColor.separator.withAlphaComponent(0.22).cgColor
            setNeedsLayout()
        }
    }

    final class LegacySelectionPillView: UIView {
        private let materialView = UIVisualEffectView(
            effect: UIBlurEffect(style: .systemUltraThinMaterial)
        )
        private let accentTintView = UIView()
        private var currentAccentColor = UIColor.systemBlue

        override init(frame: CGRect) {
            super.init(frame: frame)
            isOpaque = false

            layer.shadowColor = UIColor.systemBlue.cgColor
            layer.shadowOpacity = 0.1
            layer.shadowRadius = 8
            layer.shadowOffset = CGSize(width: 0, height: 2)

            materialView.isUserInteractionEnabled = false
            materialView.clipsToBounds = true
            addSubview(materialView)

            accentTintView.backgroundColor =
                UIColor.systemBlue.withAlphaComponent(0.11)
            materialView.contentView.addSubview(accentTintView)
        }

        required init?(coder: NSCoder) {
            nil
        }

        func updateAccentColor(_ color: UIColor) {
            currentAccentColor = color
            layer.shadowColor = color.cgColor
            accentTintView.backgroundColor = color.withAlphaComponent(0.11)
            materialView.layer.borderColor =
                color.withAlphaComponent(0.2).cgColor
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            materialView.frame = bounds
            accentTintView.frame = materialView.bounds
            layer.shadowPath = UIBezierPath(
                roundedRect: bounds,
                cornerRadius: layer.cornerRadius
            ).cgPath
        }

        func updateCornerRadius(_ radius: CGFloat) {
            layer.cornerRadius = radius
            materialView.layer.cornerRadius = radius
            materialView.layer.borderWidth = 0.75
            materialView.layer.borderColor =
                currentAccentColor.withAlphaComponent(0.2).cgColor
            setNeedsLayout()
        }
    }

    final class Coordinator: NSObject, UITabBarDelegate {
        var selection: Binding<Int>
        var controllerFocusFrame: Binding<CGRect?>
        var onReselect: (Int) -> Void
        private var latestControllerFocusFrame: CGRect?

        init(
            selection: Binding<Int>,
            controllerFocusFrame: Binding<CGRect?>,
            onReselect: @escaping (Int) -> Void
        ) {
            self.selection = selection
            self.controllerFocusFrame = controllerFocusFrame
            self.onReselect = onReselect
        }

        func publishControllerFocusFrame(_ frame: CGRect?) {
            guard latestControllerFocusFrame != frame
                    || controllerFocusFrame.wrappedValue != frame else {
                return
            }
            latestControllerFocusFrame = frame

            // UIKit can report this from layoutSubviews. Publish on the next
            // main pass so SwiftUI never mutates state during representable
            // reconciliation, and discard an older queued frame if focus moved.
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.latestControllerFocusFrame == frame,
                      self.controllerFocusFrame.wrappedValue != frame else {
                    return
                }
                self.controllerFocusFrame.wrappedValue = frame
            }
        }

        func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
            if selection.wrappedValue == item.tag {
                onReselect(item.tag)
            } else {
                selection.wrappedValue = item.tag
            }
        }
    }
}
#endif

#if !targetEnvironment(macCatalyst)
/// Observes touch-down without delaying or cancelling the native control or
/// scroll gesture underneath it. Controller focus remains disengaged until a
/// new physical controller event arrives.
@MainActor
private struct NativeMenuTouchInputObserver: UIViewRepresentable {
    let observesTouchActions: Bool
    let blocksUnderlyingTouches: Bool
    let onTouch: () -> Void
    let onTap: () -> Void
    let onBack: () -> Void
    let onToggle: (Bool) -> Void
    let onContextMenu: () -> Void
    let onEmptySpaceLongPress: (Int) -> Void

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.observesTouchActions = observesTouchActions
        view.blocksUnderlyingTouches = blocksUnderlyingTouches
        view.onTouch = onTouch
        view.onTap = onTap
        view.onBack = onBack
        view.onToggle = onToggle
        view.onContextMenu = onContextMenu
        view.onEmptySpaceLongPress = onEmptySpaceLongPress
        return view
    }

    func updateUIView(_ uiView: InstallerView, context: Context) {
        uiView.observesTouchActions = observesTouchActions
        uiView.blocksUnderlyingTouches = blocksUnderlyingTouches
        uiView.onTouch = onTouch
        uiView.onTap = onTap
        uiView.onBack = onBack
        uiView.onToggle = onToggle
        uiView.onContextMenu = onContextMenu
        uiView.onEmptySpaceLongPress = onEmptySpaceLongPress
    }

    static func dismantleUIView(_ uiView: InstallerView, coordinator: ()) {
        uiView.uninstall()
    }

    final class InstallerView: UIView {
        var observesTouchActions = true {
            didSet {
                guard oldValue != observesTouchActions else { return }
                let host = gestureHost
                uninstall()
                install(on: host)
            }
        }
        var blocksUnderlyingTouches = false {
            didSet {
                touchDownRecognizer.blocksUnderlyingTouches = blocksUnderlyingTouches
            }
        }
        var onTouch: () -> Void = {}
        var onTap: () -> Void = {}
        var onBack: () -> Void = {}
        var onToggle: (Bool) -> Void = { _ in }
        var onContextMenu: () -> Void = {}
        var onEmptySpaceLongPress: (Int) -> Void = { _ in }
        private weak var gestureHost: UIView?
        private var suppressesTapForCurrentLongPress = false
        private lazy var touchDownRecognizer: TouchDownRecognizer = {
            let recognizer = TouchDownRecognizer()
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            recognizer.onTouchDown = { [weak self] in self?.onTouch() }
            recognizer.blocksUnderlyingTouches = blocksUnderlyingTouches
            return recognizer
        }()
        private lazy var tapRecognizer: ObservationTapGestureRecognizer = {
            let recognizer = ObservationTapGestureRecognizer(
                target: self,
                action: #selector(handleTap(_:))
            )
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            return recognizer
        }()
        private lazy var contextMenuRecognizer: ObservationLongPressGestureRecognizer = {
            let recognizer = ObservationLongPressGestureRecognizer(
                target: self,
                action: #selector(handleLongPress(_:))
            )
            recognizer.minimumPressDuration = 0.42
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            return recognizer
        }()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            install(on: window)
        }

        func uninstall() {
            gestureHost?.removeGestureRecognizer(touchDownRecognizer)
            gestureHost?.removeGestureRecognizer(tapRecognizer)
            gestureHost?.removeGestureRecognizer(contextMenuRecognizer)
            gestureHost = nil
            suppressesTapForCurrentLongPress = false
        }

        private func install(on host: UIView?) {
            guard gestureHost !== host else { return }
            uninstall()
            guard let host else { return }
            host.addGestureRecognizer(touchDownRecognizer)
            if observesTouchActions {
                host.addGestureRecognizer(tapRecognizer)
                host.addGestureRecognizer(contextMenuRecognizer)
            }
            gestureHost = host
        }

        @objc private func handleTap(
            _ recognizer: ObservationTapGestureRecognizer
        ) {
            guard recognizer.state == .ended, let host = gestureHost else { return }
            guard !suppressesTapForCurrentLongPress else { return }
            let hitView = recognizer.initialHitView ?? {
                let point = recognizer.location(in: host)
                return host.hitTest(point, with: nil)
            }()
            if let toggle = touchedSwitch(from: hitView, stoppingAt: host) {
                // SwiftUI applies a Toggle binding in the same event turn. Read
                // UISwitch after that write so audio reflects the resulting state.
                DispatchQueue.main.async { [weak self, weak toggle] in
                    guard let self, let toggle else { return }
                    self.onToggle(toggle.isOn)
                }
            } else if isNavigationBackControl(
                from: hitView,
                stoppingAt: host
            ) {
                onBack()
            } else if ownsTapAction(
                from: hitView,
                stoppingAt: host
            ) {
                onTap()
            }
        }

        @objc private func handleLongPress(
            _ recognizer: UILongPressGestureRecognizer
        ) {
            switch recognizer.state {
            case .began:
                // The observation tap recognizer intentionally does not compete
                // with SwiftUI's gesture arena. Mark every real long press so its
                // eventual release cannot emit a delayed Navigation sound after
                // a native or unified context menu has already opened.
                suppressesTapForCurrentLongPress = true
                guard let host = gestureHost else { return }
                let point = recognizer.location(in: host)
                let hitView = host.hitTest(point, with: nil)
                if ownsContextMenu(from: hitView, stoppingAt: host) {
                    onContextMenu()
                } else if isEmptyThemeShortcutSpace(
                    from: hitView,
                    stoppingAt: host
                ) {
                    // Physical left/right screen halves intentionally map to
                    // L2/R2 and do not mirror in right-to-left locales.
                    let step = point.x < host.bounds.midX ? -1 : 1
                    // Let a card's own long-press/context-menu transaction
                    // claim modal ownership first. The router then rejects
                    // this backdrop shortcut instead of changing the theme
                    // underneath that menu.
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.gestureHost === host else { return }
                        self.onEmptySpaceLongPress(step)
                    }
                }
            case .ended, .cancelled, .failed:
                DispatchQueue.main.async { [weak self] in
                    self?.suppressesTapForCurrentLongPress = false
                }
            default:
                break
            }
        }

        private func touchedSwitch(
            from hitView: UIView?,
            stoppingAt host: UIView
        ) -> UISwitch? {
            var candidate = hitView
            while let view = candidate, view !== host {
                if let toggle = view as? UISwitch {
                    return toggle
                }
                if NSStringFromClass(type(of: view)).localizedCaseInsensitiveContains("Cell"),
                   let toggle = firstSwitch(in: view) {
                    return toggle
                }
                candidate = view.superview
            }
            return nil
        }

        private func firstSwitch(in view: UIView) -> UISwitch? {
            for child in view.subviews {
                if let toggle = child as? UISwitch {
                    return toggle
                }
                if let toggle = firstSwitch(in: child) {
                    return toggle
                }
            }
            return nil
        }

        private func ownsTapAction(
            from hitView: UIView?,
            stoppingAt host: UIView
        ) -> Bool {
            var candidate = hitView
            while let view = candidate, view !== host {
                guard !view.isHidden, view.alpha >= 0.01 else {
                    return false
                }
                if let control = view as? UIControl {
                    return control.isEnabled && control.isUserInteractionEnabled
                }
                if view.isAccessibilityElement {
                    let traits = view.accessibilityTraits
                    if traits.contains(.notEnabled) { return false }
                    if traits.contains(.button)
                        || traits.contains(.link)
                        || traits.contains(.adjustable) {
                        return view.isUserInteractionEnabled
                    }
                }
                candidate = view.superview
            }
            return false
        }

        private func isNavigationBackControl(
            from hitView: UIView?,
            stoppingAt host: UIView
        ) -> Bool {
            var candidate = hitView
            var navigationBar: UINavigationBar?
            var touchesButton = false
            while let view = candidate, view !== host {
                if view is UIControl
                    || view.accessibilityTraits.contains(.button) {
                    touchesButton = true
                }
                if let bar = view as? UINavigationBar {
                    navigationBar = bar
                    break
                }
                candidate = view.superview
            }
            guard touchesButton, let navigationBar,
                  let navigationController = navigationController(
                    containing: navigationBar,
                    from: host.window?.rootViewController
                  ),
                  navigationController.viewControllers.count > 1,
                  let hitView else { return false }

            let hitFrame = hitView.convert(hitView.bounds, to: navigationBar)
            switch navigationBar.effectiveUserInterfaceLayoutDirection {
            case .rightToLeft:
                return hitFrame.midX > navigationBar.bounds.midX
            default:
                return hitFrame.midX < navigationBar.bounds.midX
            }
        }

        private func navigationController(
            containing navigationBar: UINavigationBar,
            from controller: UIViewController?
        ) -> UINavigationController? {
            guard let controller else { return nil }
            if let navigationController = controller as? UINavigationController,
               navigationController.navigationBar === navigationBar {
                return navigationController
            }
            if let presented = navigationController(
                containing: navigationBar,
                from: controller.presentedViewController
            ) {
                return presented
            }
            for child in controller.children {
                if let match = navigationController(
                    containing: navigationBar,
                    from: child
                ) {
                    return match
                }
            }
            return nil
        }

        private func ownsContextMenu(
            from hitView: UIView?,
            stoppingAt host: UIView
        ) -> Bool {
            var candidate = hitView
            while let view = candidate, view !== host {
                if view.interactions.contains(where: { $0 is UIContextMenuInteraction }) {
                    return true
                }
                candidate = view.superview
            }
            return false
        }

        /// Treat only noninteractive backdrop as a theme shortcut. SwiftUI
        /// buttons expose accessibility action traits through their hosting
        /// views, while native controls, cells, and context-menu interactions
        /// are rejected directly. This keeps game-card context menus, sliders,
        /// and navigation chrome in their original gesture arenas.
        private func isEmptyThemeShortcutSpace(
            from hitView: UIView?,
            stoppingAt host: UIView
        ) -> Bool {
            guard let hitView,
                  hitView === host || hitView.isDescendant(of: host),
                  !ownsTapAction(from: hitView, stoppingAt: host) else {
                return false
            }

            var candidate: UIView? = hitView
            while let view = candidate, view !== host {
                guard !view.isHidden,
                      view.alpha >= 0.01,
                      view.isUserInteractionEnabled else {
                    return false
                }
                if view is UIControl
                    || view is UITabBar
                    || view is UITableViewCell
                    || view is UICollectionViewCell
                    || view is UITextField
                    || view is UITextView {
                    return false
                }
                if view.interactions.contains(where: {
                    $0 is UIContextMenuInteraction
                }) {
                    return false
                }
                candidate = view.superview
            }
            return true
        }
    }

    final class TouchDownRecognizer: UIGestureRecognizer {
        var onTouchDown: () -> Void = {}
        var blocksUnderlyingTouches = false {
            didSet { cancelsTouchesInView = blocksUnderlyingTouches }
        }

        override func touchesBegan(
            _ touches: Set<UITouch>,
            with event: UIEvent
        ) {
            onTouchDown()
            // In controller mode, consume only the first touch that presents
            // the handoff prompt. Once the prompt exists this returns to pure
            // observation so its buttons remain directly tappable.
            state = blocksUnderlyingTouches ? .ended : .failed
        }
    }

    final class ObservationTapGestureRecognizer: UITapGestureRecognizer {
        private(set) weak var initialHitView: UIView?

        override func touchesBegan(
            _ touches: Set<UITouch>,
            with event: UIEvent
        ) {
            if let touch = touches.first, let view {
                initialHitView = view.hitTest(
                    touch.location(in: view),
                    with: event
                )
            } else {
                initialHitView = nil
            }
            super.touchesBegan(touches, with: event)
        }

        override func reset() {
            initialHitView = nil
            super.reset()
        }

        override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool {
            false
        }

        override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
            false
        }
    }

    final class ObservationLongPressGestureRecognizer: UILongPressGestureRecognizer {
        override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool {
            false
        }

        override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
            false
        }
    }
}

#endif

#if !targetEnvironment(macCatalyst)
/// Recognizes horizontal tab swipes without cancelling touches in the active
/// List. Sliders, the horizontal cover flow, and navigation-edge gestures keep
/// ownership of their own drags.
@MainActor
private struct NativeMenuTabSwipeRecognizer: UIViewRepresentable {
    let isEnabled: Bool
    let onSwipe: (CGFloat) -> Void

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.onSwipe = onSwipe
        view.setEnabled(isEnabled)
        return view
    }

    func updateUIView(_ uiView: InstallerView, context: Context) {
        uiView.onSwipe = onSwipe
        uiView.setEnabled(isEnabled)
    }

    static func dismantleUIView(_ uiView: InstallerView, coordinator: ()) {
        uiView.uninstall()
    }

    final class InstallerView: UIView, UIGestureRecognizerDelegate {
        var onSwipe: (CGFloat) -> Void = { _ in }
        private var recognizesTabSwipes = true
        private weak var gestureHost: UIView?
        private lazy var panGesture: UIPanGestureRecognizer = {
            let gesture = UIPanGestureRecognizer(
                target: self,
                action: #selector(handlePan(_:))
            )
            gesture.cancelsTouchesInView = false
            gesture.delaysTouchesBegan = false
            gesture.delaysTouchesEnded = false
            gesture.delegate = self
            return gesture
        }()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            install(on: window == nil ? nil : nearestViewController()?.view ?? window)
        }

        func uninstall() {
            gestureHost?.removeGestureRecognizer(panGesture)
            gestureHost = nil
        }

        func setEnabled(_ enabled: Bool) {
            guard recognizesTabSwipes != enabled else { return }
            recognizesTabSwipes = enabled
            panGesture.isEnabled = enabled
        }

        private func install(on host: UIView?) {
            guard gestureHost !== host else { return }
            uninstall()
            guard let host else { return }
            host.addGestureRecognizer(panGesture)
            gestureHost = host
        }

        @objc
        private func handlePan(_ gesture: UIPanGestureRecognizer) {
            guard gesture.state == .ended, let gestureHost else { return }
            let translation = gesture.translation(in: gestureHost)
            let velocity = gesture.velocity(in: gestureHost)
            let projectedTranslation = translation.x + velocity.x * 0.12
            let usesDirectTranslation = abs(translation.x) >= 64
            let direction = usesDirectTranslation ? translation.x : projectedTranslation
            guard abs(direction) >= (usesDirectTranslation ? 64 : 96) else { return }
            onSwipe(direction)
        }

        override func gestureRecognizerShouldBegin(
            _ gestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            guard recognizesTabSwipes,
                  let pan = gestureRecognizer as? UIPanGestureRecognizer,
                  let gestureHost else {
                return false
            }
            let velocity = pan.velocity(in: gestureHost)
            return abs(velocity.x) > abs(velocity.y) * 1.25
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldReceive touch: UITouch
        ) -> Bool {
            guard let gestureHost,
                  let touchView = touch.view,
                  touchView === gestureHost || touchView.isDescendant(of: gestureHost) else {
                return false
            }

            let location = touch.location(in: gestureHost)
            let protectedLeadingEdge = gestureHost.safeAreaInsets.left + 24
            let protectedTrailingEdge = gestureHost.bounds.width
                - gestureHost.safeAreaInsets.right
                - 24
            guard location.x > protectedLeadingEdge,
                  location.x < protectedTrailingEdge else {
                return false
            }

            var touchedView: UIView? = touchView
            while let view = touchedView, view !== gestureHost {
                if view is UIControl || view is UITabBar {
                    return false
                }
                if let scrollView = view as? UIScrollView,
                   scrollView.isScrollEnabled,
                   (
                       scrollView.alwaysBounceHorizontal
                           || scrollView.contentSize.width > scrollView.bounds.width + 24
                   ) {
                    return false
                }
                touchedView = view.superview
            }
            return true
        }

        private func nearestViewController() -> UIViewController? {
            var responder: UIResponder? = self
            while let current = responder {
                if let controller = current as? UIViewController {
                    return controller
                }
                responder = current.next
            }
            return nil
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}
#endif

@MainActor
private struct SafeAreaProtectedMenuTabContent<Content: View>: View {
    @Environment(\.layoutDirection) private var layoutDirection
    @State private var safeAreaInsets = KeyWindowSafeArea.horizontalInsets()
    let appliesLegacyLandscapeInsets: Bool
    let content: Content

    init(
        appliesLegacyLandscapeInsets: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.appliesLegacyLandscapeInsets = appliesLegacyLandscapeInsets
        self.content = content()
    }

    var body: some View {
        // Pre-iOS 26, SwiftUI reports no horizontal safe-area inset for a TabView page in
        // landscape, so a bare list slides under the notch and we pad it manually from the
        // key-window insets. On iOS 26+ SwiftUI gets it right, and padding again would
        // double-inset the column. Keep this view tree stable when a theme first enables
        // the edge-to-edge background: swapping between a bare content branch and a
        // GeometryReader destroys an active Settings NavigationStack and its controller
        // navigation session.
        GeometryReader { geometry in
            let isLandscapePhone = geometry.size.width > geometry.size.height
                && UIDevice.current.userInterfaceIdiom == .phone
            let systemProvidesInset = geometry.safeAreaInsets.leading > 0
                || geometry.safeAreaInsets.trailing > 0
            let insets: (left: CGFloat, right: CGFloat) = {
                guard appliesLegacyLandscapeInsets,
                      isLandscapePhone,
                      !systemProvidesInset else {
                    return (0, 0)
                }
                return NormalTabContentMargin.effectiveHorizontalInsets(
                    raw: safeAreaInsets,
                    isLandscape: true,
                    idiom: .phone
                )
            }()
            content
                .padding(.leading, layoutDirection == .rightToLeft ? insets.right : insets.left)
                .padding(.trailing, layoutDirection == .rightToLeft ? insets.left : insets.right)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onChange(of: geometry.size) { _, _ in
                    safeAreaInsets = KeyWindowSafeArea.horizontalInsets()
                }
        }
        .onAppear {
            safeAreaInsets = KeyWindowSafeArea.horizontalInsets()
        }
    }
}

/// Reads the real left/right safe-area insets from the active key window. Used because
/// SwiftUI-reported horizontal safe-area insets are unreliable inside a TabView page.
private enum KeyWindowSafeArea {
    @MainActor
    static func horizontalInsets() -> (left: CGFloat, right: CGFloat) {
        let insets = keyWindowInsets()
        return (insets.left, insets.right)
    }

    @MainActor
    private static func keyWindowInsets() -> UIEdgeInsets {
        let windows = UIApplication.shared.appWindowScene?.windows ?? []
        return (windows.first(where: { $0.isKeyWindow }) ?? windows.first)?.safeAreaInsets ?? .zero
    }
}

/// Adds a small readable horizontal margin for normal app tabs in iPhone landscape.
///
/// Only used on the legacy path of `SafeAreaProtectedMenuTabContent` (pre-iOS 26, where
/// SwiftUI reports no horizontal safe-area inset in a TabView page). The raw key-window
/// insets only just clear the notch, so this pads each side a bit more. Portrait, iPad,
/// and gameplay surfaces are unaffected.
private enum NormalTabContentMargin {
    /// Minimum horizontal content margin for normal app tabs on an iPhone in landscape.
    static let minimumLandscapeMargin: CGFloat = 20

    static func effectiveHorizontalInsets(
        raw: (left: CGFloat, right: CGFloat),
        isLandscape: Bool,
        idiom: UIUserInterfaceIdiom
    ) -> (left: CGFloat, right: CGFloat) {
        guard isLandscape, idiom == .phone else { return raw }
        return (max(raw.left, minimumLandscapeMargin), max(raw.right, minimumLandscapeMargin))
    }
}
