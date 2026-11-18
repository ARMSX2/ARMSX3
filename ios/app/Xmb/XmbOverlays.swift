// SPDX-License-Identifier: GPL-2.0-only
// What the home and the in-game XMB both lay over the XMB: the Files picker and the XMB's own panels, after XmbHome.kt and XmbInGame.kt.

import SwiftUI
import UniformTypeIdentifiers

/// The one Files picker, for whatever `host.picking` asks.
struct XmbPicker: View {
    let host: XmbHost

    var body: some View {
        // The picker clears `picking` as it closes, so what it was for is read from `picked`.
        let what = host.picking ?? host.picked
        Color.clear
            .fileImporter(isPresented: Binding(get: { host.picking != nil }, set: { if !$0 { host.pickerClosed() } }),
                          allowedContentTypes: what.map(Self.types) ?? [], allowsMultipleSelection: what.map(Self.multiple) ?? false) { result in
                if case .success(let urls) = result { host.filesPicked(urls) }
            }
            .allowsHitTesting(false)
    }

    private static func types(_ what: XmbPicking) -> [UTType] {
        switch what {
        case .music(let pick): return XmbMedia.types(for: pick)
        case .videos(.folder): return [.folder]
        // Only what the player opens, so an import never brings a video the list leaves out.
        case .videos: return XmbVideos.formats.sorted().compactMap { UTType(filenameExtension: $0) }
        case .tools(.patch): return PatchesView.patchTypes
        case .tools: return [.item]
        case .theme: return [UTType(filenameExtension: "p3t")].compactMap { $0 }
        case .figures: return [.data]
        case .avatarPicture: return [.image]
        }
    }

    private static func multiple(_ what: XmbPicking) -> Bool {
        switch what {
        // Songs and albums come several at a time; a cover, a track or a sound is one file.
        case .music(.songs), .music(.album), .videos(.videos), .tools(.packages), .figures: return true
        case .music, .videos(.folder), .tools(.patch), .theme, .avatarPicture: return false
        }
    }
}

/// The XMB's own panels over its columns: Now Playing, a picture, text to read, the notices, the keyboard and a video.
struct XmbOverlays: View {
    let host: XmbHost
    /// Over a game the game screen draws the notices, and no video plays.
    var overGame = false

    var body: some View {
        ZStack {
            if let panel = host.media.nowPlaying {
                // On black, as the console lays its full-screen panels, so the columns do not show through.
                ZStack {
                    Color.black.ignoresSafeArea()
                    XmbNowPlayingView(panel: panel, firmware: host.firmware)
                }
                .accessibilityElement(children: .contain)
                .accessibilityAddTraits(.isModal)
                .transition(.opacity.animation(.xmb(XmbTiming.panel)))
            }
            // The outer reader keeps the safe area the inner one gives up, so text to read stays clear of the island.
            GeometryReader { safe in
                GeometryReader { geometry in
                    let m = XmbMetrics(width: geometry.size.width, height: geometry.size.height,
                                       leftInset: safe.safeAreaInsets.leading, rightInset: safe.safeAreaInsets.trailing)
                    ZStack {
                        XmbPhotoViewer(firmware: host.firmware, m: m)
                        XmbTextPanel(firmware: host.firmware, m: m)
                        if !overGame { XmbNoticeStack(firmware: host.firmware, m: m) }
                        if let keyboard = host.keyboard {
                            XmbKeyboardView(keyboard: keyboard, firmware: host.firmware, m: m, touch: host.touch)
                                .accessibilityElement(children: .contain)
                                .accessibilityAddTraits(.isModal)
                                .transition(.opacity)
                        }
                    }
                    .animation(.xmb(XmbTiming.dialog), value: host.keyboard == nil)
                }
                .ignoresSafeArea()
            }
            if !overGame { XmbVideoPlayerView(firmware: host.firmware) }
        }
    }
}
