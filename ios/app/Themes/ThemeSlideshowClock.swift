// SPDX-License-Identifier: GPL-2.0-only
// The slideshow's timing, ported from Android's ThemeSlideshow.kt. Foundation only, for the host test.

import Foundation

struct ThemeSlideshowClock {
    let count: Int
    let interval: Double
    let fadeSeconds: Double
    let zoom: Double
    let zoomInterval: Double
    let zoomMove: Double

    // Swift: sanitised again, because a hand-edited theme.json could carry a NaN that would reach a sleep or a duration.
    init(count: Int, interval: Double, fade: Double, zoom: Double, zoomInterval: Double, zoomMove: Double) {
        func clean(_ v: Double, _ fallback: Double) -> Double { v.isFinite && v >= 0 ? v : fallback }
        self.count = max(0, count)
        self.interval = clean(interval, 10)
        self.fadeSeconds = clean(fade, 1.5)
        self.zoom = clean(zoom, 0)
        self.zoomInterval = clean(zoomInterval, 0)
        self.zoomMove = clean(zoomMove, 0)
    }

    /// The interval includes the fade.
    var holdSeconds: Double { max(0, interval - fadeSeconds) }

    var plays: Bool { count > 1 }

    var hasZoom: Bool { zoom > 0 && zoomMove > 0 }

    /// The Kotlin's `Random.nextInt(frames.size)`.
    func firstIndex(random: Double) -> Int {
        guard count > 0, random.isFinite, random > 0 else { return 0 }
        return Swift.min(count - 1, Int(Swift.min(random, 1) * Double(count)))
    }

    func next(_ index: Int) -> Int { count > 0 ? (index + 1) % count : 0 }

    /// The Kotlin's reverse-repeating tween with its delay; always 1 under Reduce Motion.
    func zoomScale(at seconds: Double, reduceMotion: Bool) -> Double {
        guard !reduceMotion, hasZoom else { return 1 }
        let rest = max(0, zoomInterval - zoomMove)
        let period = rest + zoomMove
        let t = seconds.isFinite && seconds > 0 ? seconds : 0
        let lap = (t / period).rounded(.down)
        let into = t - lap * period
        // A time so large that `into` lost its precision counts as the start of a lap.
        let p = into.isFinite && into > rest ? Self.easeInOut(Swift.min(1, (into - rest) / zoomMove)) : 0
        let reversed = lap.truncatingRemainder(dividingBy: 2) == 1
        return reversed ? 1 + zoom * p : 1 + zoom - zoom * p
    }

    // Swift: a cubic ease-in-out stands for Compose's EaseInOut, a cubic Bezier curve of the same shape.
    private static func easeInOut(_ p: Double) -> Double {
        p < 0.5 ? 4 * p * p * p : 1 - pow(-2 * p + 2, 3) / 2
    }
}
