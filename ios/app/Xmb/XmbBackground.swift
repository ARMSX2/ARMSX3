// SPDX-License-Identifier: GPL-2.0-only
// What is behind the XMB: the month's colour, the wave and its sparkles, and a focused game's picture over them.

import SwiftUI

struct XmbBackground: View {
    let host: XmbHost
    let boot: XmbBootClock
    var paused = false

    /// The focused game's PIC1 once the focus has rested on it.
    @State private var settled: String?
    @State private var link = XmbWaveLink()
    /// A game has booted, so the wave and sparkles are let go until its screen starts to leave.
    @State private var released = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let settings = host.settings
        let backdrop = showsBackdrop ? settled : nil
        // Under something or in the background, the theme rests on its still, so a slideshow or live scene does not run
        // unseen and its crash marker is not left armed. A game's picture does not rest it, as Android runs it under PIC1.
        let resting = paused || host.covered || host.overlaid || scenePhase == .background
        // Reduce Motion holds the wave and its tilting sparkles still once the boot is over, as the app's other backgrounds.
        let still = paused || host.covered || host.overlaid || backdrop != nil || (reduceMotion && !boot.isRunning)
        ZStack {
            // The boot is the console's own, on its wave, whatever the theme, as on Android.
            if let theme = XmbTheme.shared.entry, !boot.isRunning {
                if resting {
                    XmbPicture(path: theme.stillURL.path, fit: false) { EmptyView() }
                } else {
                    // The library's own theme player: the slideshow or the live scene, the still under Reduce Motion.
                    GeometryReader { geometry in
                        ThemeBackgroundView(folderName: theme.folderName, fitMode: .fill, size: geometry.size)
                    }
                }
            } else if released {
                // Under a booted game the views are let go, as the library's backgrounds are, so the title gets their memory.
                Color.black
            } else {
                // A game's picture covers the wave, so it rests under it.
                XmbWaveView(themeColor: settings.xmbThemeColor, level: Float(boot.wave), colorLevel: Float(boot.color),
                            drawsWave: backdrop == nil, paused: still, link: link)
                // The sparkles are born on the wave, so they share its size and go with it; a game's picture covers both.
                XmbSparkleView(level: Float(boot.sparkles * boot.wave), paused: still) { [link] in
                    link.renderer?.controlPoints
                }
            }
            if let backdrop {
                XmbPicture(path: backdrop, fit: false) { EmptyView() }
                    .transition(.opacity)
                    .id(backdrop)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .animation(.xmb(XmbTiming.backdropFade), value: backdrop)
        .onReceive(NotificationCenter.default.publisher(for: EmulatorSession.releaseMenuBackgroundResourcesNotification)) { _ in
            released = true
        }
        // Back as the game screen starts to slide away, or, for a game the XMB did not open, once it has gone.
        .onChange(of: host.playing == nil) { _, gone in if gone { released = false } }
        .onChange(of: XmbInGame.shared.screenUp) { _, up in if !up { released = false } }
        .task(id: rootBackdrop) {
            settled = nil
            guard let path = rootBackdrop else { return }
            // A game's PIC1 comes in only once the focus rests on it, so scrolling past games does not flash them.
            try? await Task.sleep(for: .seconds(XmbTiming.backdropDelay))
            guard !Task.isCancelled else { return }
            settled = path
        }
    }

    /// The column's focused game's PIC1, whatever is open over it.
    private var rootBackdrop: String? {
        let state = host.state
        guard let items = state.items(atDepth: 0), items.indices.contains(state.focusIndex(atDepth: 0)) else { return nil }
        let node = items[state.focusIndex(atDepth: 0)]
        guard node.isGame else { return nil }
        // A disc image's picture is taken out of the image once the focus rests on the game.
        return node.backdrop ?? (host.settings.xmbGameBackgrounds ? XmbGameMedia.shared.backdrop(node.id) : nil)
    }

    /// Android drops the picture inside a folder and while a panel, a dialog or text to read is open.
    private var showsBackdrop: Bool {
        host.state.depth == 0 && !host.state.busy && XmbReading.shared.page == nil
    }
}
