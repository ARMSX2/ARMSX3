// SPDX-License-Identifier: GPL-2.0-only
// A slideshow theme's background: two slots, so at most two slides are in memory. A `.task` stops drawing out of sight.

import SwiftUI

struct ThemeSlideshowView: View {
    let entry: ThemeEntry
    let fitMode: BackgroundFitMode
    let size: CGSize
    let startIndex: Int?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var slides: [URL] = []
    @State private var urlA: URL?
    @State private var urlB: URL?
    @State private var opacityA = 0.0
    @State private var opacityB = 0.0
    @State private var aOnTop = true
    @State private var started = Date()

    private var clock: ThemeSlideshowClock {
        let t = entry.meta.timing
        return ThemeSlideshowClock(count: slides.count, interval: t?.interval ?? 10, fade: t?.fade ?? 1.5,
                                   zoom: t?.zoom ?? 0, zoomInterval: t?.zoomInterval ?? 0, zoomMove: t?.zoomMove ?? 0)
    }

    var body: some View {
        Group {
            if clock.hasZoom && !reduceMotion {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
                    pictures.scaleEffect(clock.zoomScale(at: context.date.timeIntervalSince(started), reduceMotion: false))
                }
            } else {
                pictures
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .task(id: entry.folderName) { await play() }
    }

    private var pictures: some View {
        ZStack {
            Color.clear
            slot(urlA).opacity(opacityA).zIndex(aOnTop ? 1 : 0)
            slot(urlB).opacity(opacityB).zIndex(aOnTop ? 0 : 1)
        }
        .frame(width: size.width, height: size.height)
    }

    @ViewBuilder
    private func slot(_ url: URL?) -> some View {
        if let url { StaticLibraryBackgroundImageView(url: url, fitMode: fitMode, size: size) }
    }

    private func play() async {
        slides = entry.slideURLs
        started = Date()
        let clock = self.clock
        guard !slides.isEmpty else { return }
        var index = startIndex.map { min(max(0, $0), slides.count - 1) }
            ?? clock.firstIndex(random: Double.random(in: 0 ..< 1))
        urlA = slides[index]
        opacityA = 0
        aOnTop = true
        withAnimation(.linear(duration: clock.fadeSeconds)) { opacityA = 1 }
        guard await wait(clock.fadeSeconds) else { return }
        while clock.plays {
            let next = clock.next(index)
            // The slot that is not showing takes the coming slide, on top and unseen, so it loads during the hold.
            if aOnTop { urlB = slides[next]; opacityB = 0 } else { urlA = slides[next]; opacityA = 0 }
            aOnTop.toggle()
            guard await wait(clock.holdSeconds) else { return }
            withAnimation(.linear(duration: clock.fadeSeconds)) { if aOnTop { opacityA = 1 } else { opacityB = 1 } }
            // A floor, so a theme.json with no hold and no fade cannot spin this loop.
            guard await wait(max(clock.fadeSeconds, 0.1)) else { return }
            index = next
        }
    }

    /// False when the task was cancelled. The clock's numbers are finite; the cap keeps a huge one representable.
    private func wait(_ seconds: Double) async -> Bool {
        do { try await Task.sleep(for: .seconds(min(seconds, 3600))) } catch { return false }
        return !Task.isCancelled
    }
}
