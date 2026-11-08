// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UIKit

enum GameplayLaunchCardStyle: Equatable {
    case list
    case grid
    case coverFlow
}

/// Lightweight content used to rebuild the selected library card in SwiftUI.
/// Only the already-decoded cover is retained; the menu hierarchy and full
/// window are never rasterized for the transition.
struct GameplayLaunchTransition: Identifiable {
    let id = UUID()
    let sourceFrame: CGRect
    let cornerRadius: CGFloat
    let style: GameplayLaunchCardStyle
    let gameName: String
    let title: String
    let detail: String
    let coverImage: UIImage?
    let coverSize: CGSize
    let isFavorite: Bool
    let showsGameName: Bool
    let showsGameInfo: Bool
    let showsFavoriteIndicator: Bool
    let usesSingleLineGameName: Bool
    let usesClearGlass: Bool
}

struct EmulationOnlyPresentation: Equatable {
    var showsVirtualControls = false
    var showsQuickMenu = false
    // ARMSX2's per-game pad layouts and skins, not built in this app.
    #if ARMSX3_PHASE10
    var padLayoutSnapshot: PadLayoutSnapshot?
    var padSkinDescriptor: VPadSkinDescriptor?
    #endif

    static let minimal = EmulationOnlyPresentation()
}

@Observable
final class AppState: @unchecked Sendable {
    static let shared = AppState()
    static let systemChromeNeedsUpdateNotification = Notification.Name("ARMSX2iOSSystemChromeNeedsUpdate")
    // The same name EmulatorSession declares.
    static let releaseMenuBackgroundResourcesNotification = EmulatorSession.releaseMenuBackgroundResourcesNotification
    static let emulationOnlyStartupReadyNotification = Notification.Name("ARMSX2iOSEmulationOnlyStartupReady")

    enum Screen {
        case menu
        case playing
    }

    var currentScreen: Screen = .menu
    var selectedTab: Int = 0
    var runningGameName: String? = nil
    private(set) var automaticGameStartupPending: Bool
    var gameplayLaunchTransition: GameplayLaunchTransition?
    var gameplayLaunchControlsVisible = true
    var gameplayLaunchBackgroundVisible = false
    var externalDisplayConnected = false
    // ARMSX2's per-game pad layouts, not built in this app.
    #if ARMSX3_PHASE10
    /// Serial supplied by the library before the VM has published its CRC.
    /// This lets gameplay resolve a serial-scoped automatic pad assignment on
    /// its very first SwiftUI frame instead of briefly drawing Global Default.
    private(set) var gameplayPadSerial: String?
    /// Library-resolved identity available before the VM publishes its own
    /// settings identity. This prevents the first virtual-pad frame from
    /// falling back to the default layout.
    private(set) var gameplayPadIdentity: PadLayoutGameIdentity?
    #endif
    /// Changes only when a new VM boot begins. The root gameplay overlay uses
    /// this identity to show startup guidance once per emulation session,
    /// rather than every time GameScreenView remounts after visiting the menu.
    private(set) var emulationSessionID: UUID?
    var isEmulationOnlyMode: Bool = false
    var emulationOnlyPresentation = EmulationOnlyPresentation.minimal
    private(set) var emulationOnlyStartupReady: Bool = false
    var hideStatusBar: Bool = false {
        didSet {
            if systemChromeNotificationsEnabled, oldValue != hideStatusBar {
                NotificationCenter.default.post(name: Self.systemChromeNeedsUpdateNotification, object: nil)
            }
        }
    }
    var hideHomeIndicator: Bool = false {
        didSet {
            if systemChromeNotificationsEnabled, oldValue != hideHomeIndicator {
                NotificationCenter.default.post(name: Self.systemChromeNeedsUpdateNotification, object: nil)
            }
        }
    }

    @ObservationIgnored private var systemChromeNotificationsEnabled = false
    @ObservationIgnored private var shutdownObserver: NSObjectProtocol?

    @ObservationIgnored private var autoBootObserver: NSObjectProtocol?
    @ObservationIgnored private var emulationOnlyStartupReadyObserver: NSObjectProtocol?

    private init() {
        // Read only persisted boot policy here, never SettingsStore.shared:
        // singleton initialization can itself reach AppState.
        // ARMSX2's Automatic Load Last Game, not built in this app.
        #if ARMSX3_PHASE9
        automaticGameStartupPending = ARMSX2Bridge.getINIBool(
            "ARMSX2iOS/Boot", key: "AutomaticLoadLastGame", defaultValue: false
        )
        #else
        automaticGameStartupPending = false
        #endif
        // RootView is attached as a child of SDL's controller. Seed the
        // launch-time preference before UIKit asks for its first status-bar
        // appearance, rather than waiting for SwiftUI's onAppear callback.
        // Automatic game startup skips the intro and lets GameScreenView own
        // the gameplay preference.
        if !automaticGameStartupPending {
            hideStatusBar = UserDefaults.standard.object(
                forKey: SettingsStore.key("hideIntroStatusBar")
            ) as? Bool ?? false
        }
        shutdownObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ARMSX2iOSVMDidShutdown"),
            object: nil, queue: .main
        ) { [weak self] _ in
            self?.cancelGameplayLaunchTransition()
            self?.isEmulationOnlyMode = false
            self?.emulationOnlyPresentation = .minimal
            self?.emulationOnlyStartupReady = false
            // There is no queued reboot, so a shutdown always returns to the menu.
            self?.runningGameName = nil
            #if ARMSX3_PHASE10
            self?.gameplayPadSerial = nil
            self?.gameplayPadIdentity = nil
            #endif
            self?.restoreMenuSystemChrome()
            self?.currentScreen = .menu
        }

        // Synchronize SwiftUI state when the native auto-boot path starts a VM.
        autoBootObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ARMSX2iOSAutoBootDidStart"),
            object: nil, queue: .main
        ) { [weak self] _ in
            self?.isEmulationOnlyMode = false
            self?.emulationOnlyPresentation = .minimal
            self?.emulationOnlyStartupReady = false
            self?.cancelGameplayLaunchTransition()
            self?.releaseMenuBackgroundResourcesForGameplay()
            self?.runningGameName = "AutoBoot"
            self?.emulationSessionID = UUID()
            self?.currentScreen = .playing
        }

        emulationOnlyStartupReadyObserver = NotificationCenter.default.addObserver(
            forName: Self.emulationOnlyStartupReadyNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            self?.emulationOnlyStartupReady = true
        }
        systemChromeNotificationsEnabled = true
    }

    // ARMSX2's per-game pad layouts, not built in this app.
    #if ARMSX3_PHASE10
    /// Call immediately before a library boot. The value survives
    /// JIT and shutdown gates so the accepted skin is ready before GameScreenView
    /// mounts; the VM's canonical serial/CRC takes over once available.
    func prepareGameplayPadSelection(
        forSerial rawSerial: String?,
        crc rawCRC: String? = nil
    ) {
        let serial = PadLayoutGameIdentity.normalizedSerial(rawSerial)
        gameplayPadSerial = serial.isEmpty ? nil : serial
        gameplayPadIdentity = PadLayoutGameIdentity(
            serial: serial,
            crc: rawCRC
        )
    }
    #endif

    // ARMSX2's Automatic Load Last Game, not built in this app.
    #if ARMSX3_PHASE9
    @MainActor
    func startAutomaticGameIfNeeded() {
        guard automaticGameStartupPending, !automaticGameStartupStarted else { return }
        automaticGameStartupStarted = true
        guard currentScreen == .menu, runningGameName == nil else {
            automaticGameStartupPending = false
            return
        }
        // Older versions retained only BootISO. Use it until the first
        // successful boot records LastGame; BIOS boots cannot clear LastGame.
        let legacyGame = ARMSX2Bridge.getINIString(
            "GameISO", key: "BootISO", defaultValue: ""
        )
        let game = ARMSX2Bridge.getINIString(
            "ARMSX2iOS/Boot", key: "LastGame", defaultValue: legacyGame
        )
        guard !game.isEmpty, ARMSX2Bridge.canResolveISO(game) else {
            automaticGameStartupPending = false
            if !game.isEmpty {
                bootDisclaimerTitle = "Automatic Load Last Game"
                bootDisclaimerMessage = "The last game is no longer available. Reconnect its storage or choose another game."
            }
            return
        }
        if !bootGame(isoName: game), pendingJITGameBoot == nil {
            automaticGameStartupPending = false
        }
    }
    #endif

    // The other half of Automatic Load Last Game.
    #if ARMSX3_PHASE9
    @MainActor
    func resumeAutomaticGameAfterJITIfReady() {
        guard automaticGameStartupPending, pendingJITGameBoot != nil,
              ARMSX2Bridge.isJITAvailable() else { return }
        continuePendingJITGameBoot()
    }
    #endif

    // ARMSX2's PS2 boot path is gone: games boot through EmulatorSession from the library and the game screen.

    func revealGameplayLaunchControls() {
        gameplayLaunchControlsVisible = true
        gameplayLaunchBackgroundVisible = false
    }

    func completeGameplayLaunchTransition(id: UUID) {
        guard gameplayLaunchTransition?.id == id else { return }
        gameplayLaunchTransition = nil
        gameplayLaunchControlsVisible = true
        gameplayLaunchBackgroundVisible = false
        releaseMenuBackgroundResourcesForGameplay()
    }

    func cancelGameplayLaunchTransition() {
        gameplayLaunchTransition = nil
        gameplayLaunchControlsVisible = true
        gameplayLaunchBackgroundVisible = false
    }

    private func releaseMenuBackgroundResourcesForGameplay() {
        NotificationCenter.default.post(
            name: Self.releaseMenuBackgroundResourcesNotification,
            object: nil
        )
    }

    private func restoreMenuSystemChrome() {
        // Gameplay fullscreen belongs to SDL's root controller. Clear that
        // authoritative state before asking the child SwiftUI host to show
        // the menu status bar and home indicator again.
        hideStatusBar = false
        hideHomeIndicator = false
    }
}
