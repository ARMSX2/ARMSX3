// SPDX-License-Identifier: GPL-2.0-only
// The library background for a slot that holds a PS3 theme. Nothing is read from disk in `body`.

import SwiftUI

struct ThemeBackgroundView: View {
    let folderName: String
    let fitMode: BackgroundFitMode
    let size: CGSize
    @State private var model = ThemeLibraryModel.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var played: (folder: String, mode: ThemePlayMode)?
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    /// The theme was on screen live when the app last died.
    @State private var blocked = false
    @State private var liveFailed = false
    @State private var firstFrame = false

    /// Nil when it may play live.
    private var stillReason: String? {
        if reduceMotion { return "reducemotion" }
        if lowPower { return "lowpower" }
        if blocked || liveFailed { return "failed" }
        return nil
    }

    var body: some View {
        if let entry = model.entry(named: folderName) {
            // A ZStack over Color.clear, so the `.task` has a view to run on before a mode is chosen.
            ZStack {
                Color.clear
                if let played, played.folder == entry.folderName {
                    switch played.mode {
                    case .still:
                        StaticLibraryBackgroundImageView(url: entry.stillURL, fitMode: fitMode, size: size)
                    case .slideshow:
                        ThemeSlideshowView(entry: entry, fitMode: fitMode, size: size,
                                           startIndex: nil)
                    case .live:
                        StaticLibraryBackgroundImageView(url: entry.stillURL, fitMode: fitMode, size: size)
                        if stillReason == nil {
                            ThemeSceneView(entry: entry, fitMode: fitMode,
                                           forcedPath: nil,
                                           onFirstFrame: { firstFrame = true }, onFailure: { liveFailed = true })
                                .id(entry.folderName)
                                .opacity(firstFrame ? 1 : 0)
                        }
                    }
                }
            }
            .task(id: entry.folderName) {
                let playback = entry.playback()
                blocked = playback.mode == .live && model.library.isLiveBlocked(entry.folderName)
                liveFailed = false
                firstFrame = false
                played = (entry.folderName, playback.mode)
            }
            .onChange(of: stillReason) {
                // A new live view starts hidden again; the still shows until its first frame.
                firstFrame = false
            }
            .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)
                .receive(on: RunLoop.main)) { _ in
                lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
            }
        }
    }
}
