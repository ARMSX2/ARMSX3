// SPDX-License-Identifier: GPL-2.0-only
// The frontend's UI preferences, never emulator settings. Stored members persist under `armsx3.settings.<name>`.

import Foundation
import Observation
import SwiftUI

@MainActor @Observable final class SettingsStore {
    static let shared = SettingsStore()

    @ObservationIgnored private let defaults: UserDefaults

    nonisolated static func key(_ member: String) -> String { "armsx3.settings." + member }

    // MARK: - The background

    var dynamicBackgroundsEnabled: Bool {
        didSet { defaults.set(dynamicBackgroundsEnabled, forKey: Self.key("dynamicBackgroundsEnabled")) }
    }

    /// Stored as JSON.
    var dynamicAppearancePreferences: DynamicAppearancePreferences {
        didSet {
            if let data = try? JSONEncoder().encode(dynamicAppearancePreferences) {
                defaults.set(data, forKey: Self.key("dynamicAppearancePreferences"))
            }
        }
    }

    /// BackgroundContainerView raises the dimming under Reduce Transparency or Increase Contrast.
    var padOpacity: Double {
        didSet { defaults.set(padOpacity, forKey: Self.key("padOpacity")) }
    }

    var padVibrates: Bool {
        didSet { defaults.set(padVibrates, forKey: Self.key("padVibrates")) }
    }

    var backgroundDim: Double {
        didSet { defaults.set(backgroundDim, forKey: Self.key("backgroundDim")) }
    }

    /// Also shown in landscape when no landscape asset is set; an unreadable value reads as nil.
    var backgroundPrimaryAsset: BackgroundAsset? {
        didSet {
            if let asset = backgroundPrimaryAsset, let data = try? JSONEncoder().encode(asset) {
                defaults.set(data, forKey: Self.key("backgroundPrimaryAsset"))
            } else {
                defaults.removeObject(forKey: Self.key("backgroundPrimaryAsset"))
            }
        }
    }

    var backgroundLandscapeAsset: BackgroundAsset? {
        didSet {
            if let asset = backgroundLandscapeAsset, let data = try? JSONEncoder().encode(asset) {
                defaults.set(data, forKey: Self.key("backgroundLandscapeAsset"))
            } else {
                defaults.removeObject(forKey: Self.key("backgroundLandscapeAsset"))
            }
        }
    }

    var backgroundFitMode: BackgroundFitMode {
        didSet { defaults.set(backgroundFitMode.rawValue, forKey: Self.key("backgroundFitMode")) }
    }

    var backgroundLandscapeFitMode: BackgroundFitMode {
        didSet { defaults.set(backgroundLandscapeFitMode.rawValue, forKey: Self.key("backgroundLandscapeFitMode")) }
    }

    var backgroundVideoMuted: Bool {
        didSet { defaults.set(backgroundVideoMuted, forKey: Self.key("backgroundVideoMuted")) }
    }

    var hasCustomBackground: Bool { dynamicBackgroundsEnabled || backgroundPrimaryAsset != nil }

    /// The console's XrossMediaBar in place of the library tabs, kept across launches.
    var xmbMode: Bool {
        didSet { defaults.set(xmbMode, forKey: Self.key("xmbMode")) }
    }

    /// The XMB's colour: -1 follows the month as the console's Original does, 0 to 11 is a month, 12 cycles through them.
    var xmbThemeColor: Int {
        didSet { defaults.set(xmbThemeColor, forKey: Self.key("xmbThemeColor")) }
    }

    /// A focused game's PIC1 behind the XMB.
    var xmbGameBackgrounds: Bool {
        didSet { defaults.set(xmbGameBackgrounds, forKey: Self.key("xmbGameBackgrounds")) }
    }

    /// A focused game's ICON1 playing in place of its icon.
    var xmbAnimatedIcons: Bool {
        didSet { defaults.set(xmbAnimatedIcons, forKey: Self.key("xmbAnimatedIcons")) }
    }

    /// A focused game's SND0 playing.
    var xmbGameMusic: Bool {
        didSet { defaults.set(xmbGameMusic, forKey: Self.key("xmbGameMusic")) }
    }

    /// The XMB's theme, a saved theme's folder name, or nil for Original. The library's background is its own.
    var xmbTheme: String? {
        didSet { defaults.set(xmbTheme, forKey: Self.key("xmbTheme")) }
    }

    // MARK: - Glass

    var clearLiquidGlassUIQuickMenu: Bool {
        didSet { defaults.set(clearLiquidGlassUIQuickMenu, forKey: Self.key("clearLiquidGlassUIQuickMenu")) }
    }

    var clearLiquidGlassUI: Bool {
        didSet { defaults.set(clearLiquidGlassUI, forKey: Self.key("clearLiquidGlassUI")) }
    }

    // MARK: - Feedback

    var hapticFeedback: Bool {
        didSet { defaults.set(hapticFeedback, forKey: Self.key("hapticFeedback")) }
    }

    /// A Float, because the router scales it into a Core Haptics intensity.
    var uiRumbleStrength: Float {
        didSet { defaults.set(uiRumbleStrength, forKey: Self.key("uiRumbleStrength")) }
    }

    // MARK: - Controller focus

    var focusOrbsEnabled: Bool {
        didSet { defaults.set(focusOrbsEnabled, forKey: Self.key("focusOrbsEnabled")) }
    }

    var controllerNavigationDepthEffectEnabled: Bool {
        didSet { defaults.set(controllerNavigationDepthEffectEnabled, forKey: Self.key("controllerNavigationDepthEffectEnabled")) }
    }

    var controllerFocusBoxPalette: ThemePalette {
        didSet { defaults.set(controllerFocusBoxPalette.rawValue, forKey: Self.key("controllerFocusBoxPalette")) }
    }

    var controllerFocusBoxAnimationSpeed: Double {
        didSet { defaults.set(controllerFocusBoxAnimationSpeed, forKey: Self.key("controllerFocusBoxAnimationSpeed")) }
    }

    var controllerFocusBoxGlowIntensity: Double {
        didSet { defaults.set(controllerFocusBoxGlowIntensity, forKey: Self.key("controllerFocusBoxGlowIntensity")) }
    }

    var controllerFocusBoxCustomColor: SavedPaletteColor? { nil }

    var controllerOrbColors: [Color] { controllerFocusBoxPalette.colors }

    // MARK: - Status bar

    var hideIntroStatusBar: Bool {
        didSet { defaults.set(hideIntroStatusBar, forKey: Self.key("hideIntroStatusBar")) }
    }

    var hideMenuStatusBar: Bool {
        didSet { defaults.set(hideMenuStatusBar, forKey: Self.key("hideMenuStatusBar")) }
    }

    // MARK: - The settings screen

    var showAdvancedSettings: Bool {
        didSet { defaults.set(showAdvancedSettings, forKey: Self.key("showAdvancedSettings")) }
    }

    // MARK: - RPCN

    /// By file name, so a favourite survives a move of the data folder. Never logged.
    var rpcnAvatarFavorites: [String] {
        didSet { defaults.set(rpcnAvatarFavorites, forKey: Self.key("rpcnAvatarFavorites")) }
    }

    // MARK: - JIT

    /// Read before `main()` through CFPreferences, so a change takes effect at the next launch.
    var jitMethod: String {
        didSet { defaults.set(jitMethod, forKey: Self.key("jitMethod")) }
    }

    /// The pane's "Applies when ARMSX3 reopens" marker compares the current choice with this.
    @ObservationIgnored let jitMethodAtLaunch: String

    /// Stored before the open because StikDebug relaunches ARMSX3; a time, never a URL or a process id.
    var stikdebugRequestedAt: Double {
        didSet { defaults.set(stikdebugRequestedAt, forKey: Self.key("stikdebugRequestedAt")) }
    }

    // MARK: - First-run setup

    /// Setup never opens by itself once this is set; Run Setup Again does not clear it.
    var setupFinished: Bool {
        didSet { defaults.set(setupFinished, forKey: Self.key("setupFinished")) }
    }

    /// Saved as each step appears, so a relaunch resumes there.
    var setupStep: Int {
        didSet { defaults.set(setupStep, forKey: Self.key("setupStep")) }
    }

    // MARK: - Diagnostics

    var verboseLogging: Bool {
        didSet { defaults.set(verboseLogging, forKey: Self.key("verboseLogging")) }
    }

    // MARK: - Hotkeys

    /// A missing entry is the action's default and an empty string is None.
    var controllerMacroBindings: [String: String] {
        didSet { defaults.set(controllerMacroBindings, forKey: Self.key("controllerMacroBindings")) }
    }

    /// Never logged.
    var controllerPins: [String: Int] {
        didSet { defaults.set(controllerPins, forKey: Self.key("controllerPins")) }
    }

    var controllerTiltOff: [Int] {
        didSet { defaults.set(controllerTiltOff, forKey: Self.key("controllerTiltOff")) }
    }

    var controllerTiltInvert: [String: Int] {
        didSet { defaults.set(controllerTiltInvert, forKey: Self.key("controllerTiltInvert")) }
    }

    var gyroStickTarget: [String: Int] {
        didSet { defaults.set(gyroStickTarget, forKey: Self.key("gyroStickTarget")) }
    }

    /// Clamped where it is applied.
    var gyroStickSensitivity: [String: Int] {
        didSet { defaults.set(gyroStickSensitivity, forKey: Self.key("gyroStickSensitivity")) }
    }

    /// The core's own switch is that title's `Input/Output@@Move` = `Fake`, which the Controllers pane writes beside it.
    var moveSlot: [String: Int] {
        didSet { defaults.set(moveSlot, forKey: Self.key("moveSlot")) }
    }

    var movePointer: [String: Int] {
        didSet { defaults.set(movePointer, forKey: Self.key("movePointer")) }
    }

    /// One mode at a time, changed only through KeyboardMouseFeed.setKeyboardSlot.
    var keyboardSlot: Int {
        didSet { defaults.set(keyboardSlot, forKey: Self.key("keyboardSlot")) }
    }

    var hotkeys: HotkeyTable {
        get { HotkeyTable(stored: controllerMacroBindings) }
        set { controllerMacroBindings = newValue.stored }
    }

    var backgroundEnabledInSettings: Bool { true }

    // MARK: - The default theme

    private var themePalette: ThemePalette { dynamicAppearancePreferences.sharedPalette }
    private var themeText: Color { themePalette.semanticInterfaceTextColor }

    var controllerNavigationAccentColor: Color { themePalette.semanticAccentColor }
    var controllerBottomTabBarColor: Color { controllerNavigationAccentColor }
    var controllerBottomTabBarUnselectedColor: Color { themeText.opacity(0.6) }
    var controllerTabTitleColor: Color { themeText }
    var controllerTabSubtitleColor: Color { themeText.opacity(0.7) }
    var controllerCardTitleColor: Color { themeText }
    var controllerCardSubtitleColor: Color { themeText.opacity(0.7) }
    /// The context menu sits on dark glass, so its text is light whatever the palette.
    var controllerContextMenuColor: Color { .white }
    var controllerContextMenuSecondaryColor: Color { .white.opacity(0.72) }
    var controllerCriticalTextColor: Color { .red }

    // MARK: - Text

    /// A key the catalog lacks reads as itself, so a lifted view's dynamic text still shows.
    func localized(_ text: String) -> String { String(localized: String.LocalizationValue(text)) }

    /// English is the only shipped locale.
    var localizedLayoutDirection: LayoutDirection { .leftToRight }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        func bool(_ member: String, _ fallback: Bool) -> Bool { defaults.object(forKey: Self.key(member)) as? Bool ?? fallback }
        func double(_ member: String, _ fallback: Double) -> Double { defaults.object(forKey: Self.key(member)) as? Double ?? fallback }

        dynamicBackgroundsEnabled = bool("dynamicBackgroundsEnabled", true)
        xmbMode = bool("xmbMode", false)
        xmbThemeColor = min(max(defaults.object(forKey: Self.key("xmbThemeColor")) as? Int ?? -1, -1), 12)
        xmbGameBackgrounds = bool("xmbGameBackgrounds", true)
        xmbAnimatedIcons = bool("xmbAnimatedIcons", true)
        xmbGameMusic = bool("xmbGameMusic", true)
        xmbTheme = defaults.string(forKey: Self.key("xmbTheme"))
        dynamicAppearancePreferences = defaults.data(forKey: Self.key("dynamicAppearancePreferences"))
            .flatMap { try? JSONDecoder().decode(DynamicAppearancePreferences.self, from: $0) } ?? .standard
        backgroundDim = min(max(double("backgroundDim", 0), 0), 1)
        padOpacity = min(max(double("padOpacity", 0.75), 0.2), 1)
        padVibrates = bool("padVibrates", true)
        func asset(_ member: String) -> BackgroundAsset? {
            defaults.data(forKey: Self.key(member)).flatMap { try? JSONDecoder().decode(BackgroundAsset.self, from: $0) }
        }
        func fit(_ member: String) -> BackgroundFitMode {
            defaults.string(forKey: Self.key(member)).flatMap(BackgroundFitMode.init(rawValue:)) ?? .fill
        }
        backgroundPrimaryAsset = asset("backgroundPrimaryAsset")
        backgroundLandscapeAsset = asset("backgroundLandscapeAsset")
        backgroundFitMode = fit("backgroundFitMode")
        backgroundLandscapeFitMode = fit("backgroundLandscapeFitMode")
        backgroundVideoMuted = bool("backgroundVideoMuted", true)
        clearLiquidGlassUIQuickMenu = bool("clearLiquidGlassUIQuickMenu", true)
        clearLiquidGlassUI = bool("clearLiquidGlassUI", true)
        hapticFeedback = bool("hapticFeedback", true)
        uiRumbleStrength = Float(double("uiRumbleStrength", 0.5))
        focusOrbsEnabled = bool("focusOrbsEnabled", true)
        controllerNavigationDepthEffectEnabled = bool("controllerNavigationDepthEffectEnabled", false)
        controllerFocusBoxPalette = (defaults.string(forKey: Self.key("controllerFocusBoxPalette"))).flatMap(ThemePalette.init(rawValue:)) ?? .blue
        controllerFocusBoxAnimationSpeed = double("controllerFocusBoxAnimationSpeed", 1)
        controllerFocusBoxGlowIntensity = double("controllerFocusBoxGlowIntensity", 1)
        hideIntroStatusBar = bool("hideIntroStatusBar", false)
        hideMenuStatusBar = bool("hideMenuStatusBar", false)
        showAdvancedSettings = bool("showAdvancedSettings", false)
        rpcnAvatarFavorites = defaults.stringArray(forKey: Self.key("rpcnAvatarFavorites")) ?? []
        verboseLogging = bool("verboseLogging", false)
        let method = defaults.string(forKey: Self.key("jitMethod")) ?? "builtin"
        jitMethodAtLaunch = method
        jitMethod = method
        stikdebugRequestedAt = double("stikdebugRequestedAt", 0)
        setupFinished = bool("setupFinished", false)
        setupStep = defaults.object(forKey: Self.key("setupStep")) as? Int ?? 0
        controllerMacroBindings = defaults.dictionary(forKey: Self.key("controllerMacroBindings")) as? [String: String] ?? [:]
        controllerPins = defaults.dictionary(forKey: Self.key("controllerPins")) as? [String: Int] ?? [:]
        controllerTiltOff = defaults.array(forKey: Self.key("controllerTiltOff")) as? [Int] ?? []
        controllerTiltInvert = defaults.dictionary(forKey: Self.key("controllerTiltInvert")) as? [String: Int] ?? [:]
        gyroStickTarget = defaults.dictionary(forKey: Self.key("gyroStickTarget")) as? [String: Int] ?? [:]
        gyroStickSensitivity = defaults.dictionary(forKey: Self.key("gyroStickSensitivity")) as? [String: Int] ?? [:]
        moveSlot = defaults.dictionary(forKey: Self.key("moveSlot")) as? [String: Int] ?? [:]
        movePointer = defaults.dictionary(forKey: Self.key("movePointer")) as? [String: Int] ?? [:]
        let keyboard = defaults.object(forKey: Self.key("keyboardSlot")) as? Int ?? -1
        keyboardSlot = (0...3).contains(keyboard) ? keyboard : -1
    }
}

// MARK: - Controller focus, with types from the lifted controller navigation

// In an extension beside the lifted types they name.
extension SettingsStore {
    var controllerFocusBoxStyle: ControllerFocusBoxStyle {
        get {
            access(keyPath: \.controllerFocusBoxStyle)
            return defaults.string(forKey: Self.key("controllerFocusBoxStyle")).flatMap(ControllerFocusBoxStyle.init(rawValue:)) ?? .neonBlue
        }
        set {
            withMutation(keyPath: \.controllerFocusBoxStyle) {
                defaults.set(newValue.rawValue, forKey: Self.key("controllerFocusBoxStyle"))
            }
        }
    }

    var controllerNavigationFocusAnimation: ControllerNavigationFocusTravelStyle {
        get {
            access(keyPath: \.controllerNavigationFocusAnimation)
            return defaults.string(forKey: Self.key("controllerNavigationFocusAnimation")).flatMap(ControllerNavigationFocusTravelStyle.init(rawValue:)) ?? .easeOutBack
        }
        set {
            withMutation(keyPath: \.controllerNavigationFocusAnimation) {
                defaults.set(newValue.rawValue, forKey: Self.key("controllerNavigationFocusAnimation"))
            }
        }
    }

    var controllerTextAppearance: ControllerTextAppearance { .standard }

    // MARK: - The controller chords

    func controllerMacroBinding(for action: ControllerMacroAction) -> ControllerMacroBinding? {
        hotkeys.chord(for: action.rawValue).flatMap(ControllerMacroBinding.init(rawValue:))
    }
    /// Never unbound, so never nil.
    var controllerMacroQuickMenu: ControllerMacroBinding {
        controllerMacroBinding(for: .quickMenu) ?? ControllerMacroBinding(rawValue: "select+start")!
    }
    var controllerMacroSaveGameState: ControllerMacroBinding? { controllerMacroBinding(for: .saveGameState) }
    var controllerMacroLoadGameState: ControllerMacroBinding? { controllerMacroBinding(for: .loadGameState) }
}
