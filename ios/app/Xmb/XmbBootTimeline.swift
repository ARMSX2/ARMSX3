// SPDX-License-Identifier: GPL-2.0-only
// When each part of the XMB's boot and of leaving XMB mode shows, after Android's XmbBoot.kt and XmbShutdown.kt.

import Foundation

/// The console's boot, timed off a recording of a real PS3 starting: the wave comes up on its own over black, the
/// logo is lit from left to right, the colour rises behind it from black, the logo goes, and the XMB's icons come in
/// as the colour arrives. Every level is a function of the seconds since the boot started.
enum XmbBootTimeline {
    static let blackTime = 0.5
    static let waveStart = 0.5
    static let waveTime = 1.5
    static let lightStart = 2.0
    static let lightTime = 0.75
    static let colorStart = 2.5
    static let colorTime = 4.0
    static let fadeStart = 4.75
    static let fadeTime = 0.5
    static let revealStart = 5.6
    static let revealTime = 0.9
    static let end = 6.5
    /// The boot sound starts with the clock, and runs on past the end.
    static let soundStart = 0.0
    /// Where a skip puts the clock: the logo has just gone.
    static let skipTo = fadeStart + fadeTime
    /// How far below its place the XMB starts, in points, while `reveal` is 0.
    static let revealRise = 24.0

    static func clamp01(_ x: Double) -> Double { min(max(x, 0), 1) }

    static func ease(_ x: Double) -> Double {
        let x = clamp01(x)
        return x * x * (3 - 2 * x)
    }

    /// The black in front of everything before the wave starts.
    static func black(_ t: Double) -> Double { 1 - ease((t - blackTime) / 0.25) }

    /// How much of the wave shows.
    static func wave(_ t: Double) -> Double { ease((t - waveStart) / waveTime) }

    /// How much of the colour shows.
    static func color(_ t: Double) -> Double { ease((t - colorStart) / colorTime) }

    /// 0 to 1 as the XMB's own icons come in, at the end.
    static func reveal(_ t: Double) -> Double { ease((t - revealStart) / revealTime) }

    /// The console keeps the sparkles out of its boot, so they come in with the icons.
    static func sparkles(_ t: Double) -> Double { reveal(t) }

    /// 0 to 1 as the logo goes.
    static func fade(_ t: Double) -> Double { ease((t - fadeStart) / fadeTime) }

    /// The logo's own alpha, before the light masks it; 0 while the light is dark.
    static func logoAlpha(_ t: Double) -> Double { light(t) == nil ? 0 : 1 - fade(t) }

    /// Where the light is, in the scene units of the firmware's coldboot script with the logo's middle at the origin
    /// and y up, and how bright it is at nine distances evenly spaced from 0 out to `reach`.
    struct Light: Equatable {
        var x: Double
        var y: Double
        var reach: Double
        var alphas: [Double]
    }

    /// The logo plane of the script is this many scene units wide.
    static let logoPlaneWidth = 2.56

    /// The script's light: brightness at distance d is color / (a0 + a1 d + a2 d^2), the attenuation going from the
    /// scene's (0, 1, 4) to the script's (0.1, 0.1, 0.1) as it sweeps in, and back as it leaves. Nil while it is dark.
    static func light(_ t: Double) -> Light? {
        let fadeP = fade(t)
        let inP = clamp01((t - lightStart) / lightTime)
        let color, x, y, k: Double
        if t < fadeStart {
            color = inP
            x = -3 + 3 * inP
            y = 0.5
            k = inP
        } else {
            color = 1 - fadeP
            x = 2.25 * fadeP
            y = 0.5 + 1.5 * fadeP
            k = 1 - fadeP
        }
        if color <= 0.001 { return nil }
        let a0 = 0.1 * k
        let a1 = 1 + (0.1 - 1) * k
        let a2 = 4 + (0.1 - 4) * k
        // Out to where it drops under 1%.
        let target = color / 0.01
        let reach = max((-a1 + (a1 * a1 - 4 * a2 * (a0 - target)).squareRoot()) / (2 * a2), 0.05)
        let alphas = (0 ..< 9).map { i -> Double in
            let d = Double(i) / 8 * reach
            let denominator = a0 + a1 * d + a2 * d * d
            return denominator <= 1e-4 ? 1 : clamp01(color / denominator)
        }
        return Light(x: x, y: y, reach: reach, alphas: alphas)
    }
}

/// Leaving XMB mode the way a console is switched off: the XMB goes dark with its wave and colour sinking, a moment
/// of black while XMB mode ends behind it, the app's intro, then the black lifts off the library.
enum XmbShutdownTimeline {
    static let sinkTime = 1.2
    static let darkTime = 1.3
    static let blackTime = 0.7
    static let liftTime = 0.7
    /// XMB mode ends once the screen is black.
    static let exitAt = darkTime
    /// The intro starts after the moment of black.
    static let introAt = darkTime + blackTime
    /// An intro that will not play, or runs long, ends anyway.
    static let introLimit = 12.0

    /// The wave and its colour, sinking with the screen.
    static func sink(_ t: Double) -> Double { 1 - XmbBootTimeline.clamp01(t / sinkTime) }

    /// The black coming down over the XMB.
    static func dark(_ t: Double) -> Double { fastOutSlowIn(t / darkTime) }

    /// The black lifting off the library, `t` seconds after the intro ended.
    static func lift(_ t: Double) -> Double { 1 - fastOutSlowIn(t / liftTime) }

    /// Compose's FastOutSlowInEasing: the cubic Bezier through (0.4, 0) and (0.2, 1).
    static func fastOutSlowIn(_ x: Double) -> Double {
        let x = XmbBootTimeline.clamp01(x)
        func bezier(_ s: Double, _ a: Double, _ b: Double) -> Double {
            3 * a * (1 - s) * (1 - s) * s + 3 * b * (1 - s) * s * s + s * s * s
        }
        var low = 0.0, high = 1.0
        for _ in 0 ..< 40 {
            let middle = (low + high) / 2
            if bezier(middle, 0.4, 0.2) < x { low = middle } else { high = middle }
        }
        return bezier((low + high) / 2, 0, 1)
    }
}
