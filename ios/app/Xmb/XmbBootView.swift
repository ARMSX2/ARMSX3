// SPDX-License-Identifier: GPL-2.0-only
// The XMB's boot animation, the way out of XMB mode and the clock for both, after Android's XmbBoot.kt and XmbShutdown.kt.

import AVFoundation
import QuartzCore
import SwiftUI

/// The boot's own layers, over the wave and the XMB: black until the wave starts, then the logo under its light.
/// A tap skips it. It draws nothing once the boot is over.
struct XmbBootView: View {
    let clock: XmbBootClock

    var body: some View {
        if clock.isRunning {
            ZStack {
                Color.black.opacity(clock.black)
                XmbBootLogo(seconds: clock.seconds)
            }
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture { clock.skip() }
        }
    }
}

/// The logo where the console puts its own, lit from the side the light of the firmware's coldboot script comes from.
struct XmbBootLogo: View {
    let seconds: Double

    var body: some View {
        GeometryReader { geometry in
            if let light = XmbBootTimeline.light(seconds) {
                let box = Self.box(in: geometry.size)
                // Scene units to points.
                let scale = box.width / XmbBootTimeline.logoPlaneWidth
                XmbWordmark(letterHeight: box.width / XmbWordmark.unitsWide)
                    .opacity(XmbBootTimeline.logoAlpha(seconds))
                    .mask {
                        RadialGradient(
                            stops: light.alphas.enumerated().map {
                                Gradient.Stop(color: .white.opacity($0.element), location: CGFloat($0.offset) / 8)
                            },
                            center: UnitPoint(x: 0.5 + light.x * scale / box.width,
                                              y: 0.5 - light.y * scale / box.height),
                            startRadius: 0, endRadius: max(light.reach * scale, 1))
                    }
                    .offset(x: box.minX, y: box.minY)
            }
        }
    }

    /// The logo's box in a 16:9 frame fitted to the screen: the letters 0.058 of the frame high (the PS3 letters were
    /// 0.078; ARMSX3 has twice as many, set wide), their middle 0.495 down, the whole ending 0.925 across.
    static func box(in size: CGSize) -> CGRect {
        let frameWidth = min(size.width, size.height * 16 / 9)
        let frameHeight = frameWidth * 9 / 16
        let unit = 0.058 * frameHeight
        let width = XmbWordmark.unitsWide * unit
        return CGRect(x: (size.width - frameWidth) / 2 + 0.925 * frameWidth - width,
                      y: (size.height - frameHeight) / 2 + 0.495 * frameHeight - (XmbWordmark.pad + 0.5) * unit,
                      width: width, height: XmbWordmark.unitsHigh * unit)
    }
}

/// The way out of XMB mode, over every screen while it runs: the black, then the app's intro on it.
struct XmbShutdownView: View {
    let clock: XmbBootClock

    var body: some View {
        if clock.isShuttingDown {
            ZStack {
                Color.black
                if clock.showsIntro { BootSplashView { clock.introFinished() } }
            }
            .opacity(clock.dark)
            .ignoresSafeArea()
        }
    }
}

// MARK: - Clock

/// Runs the boot when XMB mode appears and the way out when the player leaves it, a frame at a time. The wave, the
/// sparkles and the XMB read their levels here; every level is 1 at rest.
@MainActor @Observable final class XmbBootClock: NSObject {
    static let shared = XmbBootClock()

    private enum Phase { case idle, boot, darken, intro, lift }

    /// The boot is playing: Cross and Circle skip it and the pad does nothing else.
    private(set) var isRunning = false
    /// XMB mode is being left: the pad does nothing.
    private(set) var isShuttingDown = false
    /// Seconds into the boot.
    private(set) var seconds = XmbBootTimeline.end
    private(set) var wave = 1.0
    private(set) var color = 1.0
    private(set) var sparkles = 1.0
    /// The XMB's alpha; it also sits (1 - reveal) * XmbBootTimeline.revealRise points low.
    private(set) var reveal = 1.0
    private(set) var black = 0.0
    /// The black over every screen while XMB mode is left.
    private(set) var dark = 0.0
    private(set) var showsIntro = false

    @ObservationIgnored private var phase = Phase.idle
    @ObservationIgnored private var link: CADisplayLink?
    @ObservationIgnored private var began: CFTimeInterval = 0
    @ObservationIgnored private var sound: AVAudioPlayer?
    @ObservationIgnored private var exit: (() -> Void)?
    @ObservationIgnored private var introLimit: Task<Void, Never>?
    @ObservationIgnored private var musicIn: Task<Void, Never>?

    /// The boot, from black. The sound and the clock start together, so a slow first frame cannot put one ahead.
    func start() {
        guard phase == .idle else { return }
        phase = .boot
        isRunning = true
        show(0)
        sound?.stop()
        sound = Self.bootSound()
        sound?.play()
        // The music waits for the startup sound to end, then comes in under the menu.
        XmbMedia.shared.hold()
        musicIn = Task { [length = sound?.duration ?? 0] in
            try? await Task.sleep(for: .seconds(length))
            if !Task.isCancelled { XmbMedia.shared.fadeIn() }
        }
        run()
    }

    /// Cross, Circle or a tap: the boot goes on from where the logo has gone. The sound fades out, which the app
    /// leaving the foreground wants too.
    func skip() {
        // A sound that has ended has already handed over to the music.
        if let sound, sound.isPlaying {
            self.sound = nil
            sound.setVolume(0, fadeDuration: 1.5)
            musicIn?.cancel()
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                sound.stop()
                XmbMedia.shared.fadeIn()
            }
        }
        // Android holds at this point until its clock catches up; this one goes straight on.
        if phase == .boot { began = min(began, CACurrentMediaTime() - XmbBootTimeline.skipTo) }
    }

    /// Leaving XMB mode. `exit` ends it once the screen is black; the intro and the library follow.
    func shutDown(exit: @escaping () -> Void) {
        guard phase == .idle else { return }
        phase = .darken
        isShuttingDown = true
        self.exit = exit
        XmbFirmware.shared.play(.cancel)
        XmbMedia.shared.fadeOut(over: 1.2)
        run()
    }

    /// The intro ended or was tapped away: the black lifts off the library.
    func introFinished() {
        guard phase == .intro else { return }
        introLimit?.cancel()
        showsIntro = false
        phase = .lift
        run()
    }

    private func run() {
        began = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    private func halt() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick() {
        let t = CACurrentMediaTime() - began
        switch phase {
        case .boot:
            show(min(t, XmbBootTimeline.end))
            if t >= XmbBootTimeline.end {
                halt()
                phase = .idle
                isRunning = false
            }
        case .darken:
            dark = XmbShutdownTimeline.dark(t)
            if t < XmbShutdownTimeline.exitAt {
                set(\.wave, XmbShutdownTimeline.sink(t))
                set(\.color, XmbShutdownTimeline.sink(t))
            } else if let exit {
                self.exit = nil
                exit()
                wave = 1
                color = 1
            }
            if t >= XmbShutdownTimeline.introAt {
                halt()
                phase = .intro
                showsIntro = true
                introLimit = Task {
                    try? await Task.sleep(for: .seconds(XmbShutdownTimeline.introLimit))
                    if !Task.isCancelled { introFinished() }
                }
            }
        case .lift:
            dark = XmbShutdownTimeline.lift(t)
            if t >= XmbShutdownTimeline.liftTime {
                halt()
                phase = .idle
                isShuttingDown = false
            }
        case .idle, .intro:
            halt()
        }
    }

    private func show(_ t: Double) {
        seconds = t
        set(\.wave, XmbBootTimeline.wave(t))
        set(\.color, XmbBootTimeline.color(t))
        set(\.sparkles, XmbBootTimeline.sparkles(t))
        set(\.reveal, XmbBootTimeline.reveal(t))
        set(\.black, XmbBootTimeline.black(t))
    }

    /// A level that has not moved is left alone, so the views reading it are not drawn again every frame.
    private func set(_ level: ReferenceWritableKeyPath<XmbBootClock, Double>, _ value: Double) {
        if self[keyPath: level] != value { self[keyPath: level] = value }
    }

    /// The console's startup sound from the player's firmware, ready to start at once at full volume as on Android, or
    /// nil. Where the system will not open the AC-3, the WAV the core decoded from it plays instead.
    private static func bootSound() -> AVAudioPlayer? {
        let player = (try? AVAudioPlayer(contentsOf: bootSoundFile))
            ?? decodedBootSoundFile().flatMap { try? AVAudioPlayer(contentsOf: $0) }
        guard let player, player.prepareToPlay() else { return nil }
        return player
    }

    nonisolated private static var bootSoundFile: URL {
        XmbFirmware.devFlash.appendingPathComponent("vsh/resource/coldboot_stereo.ac3")
    }

    /// Where the decoded sound goes, named for the file and its size as Android names it, so new firmware is decoded
    /// again; nil without the file.
    nonisolated private static func decodedBootSoundFile() -> URL? {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: bootSoundFile.path)[.size]) as? Int else { return nil }
        return FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("xmb-audio/coldboot_stereo.ac3-\(size).wav")
    }

    /// Decodes the startup sound through the core when the system will not open the AC-3, so the boot has it ready.
    /// Blocking and off the main thread; the firmware's loading calls it.
    // shortcut: the first boot on such a device can start before this ends and stay silent; Android waits up to 1.5 s
    // for it, which would need the boot to wait too.
    nonisolated static func prepareBootSound() {
        guard (try? AVAudioPlayer(contentsOf: bootSoundFile)) == nil, let wav = decodedBootSoundFile(),
              (try? AVAudioPlayer(contentsOf: wav)) == nil else { return }
        try? FileManager.default.createDirectory(at: wav.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !Rpcs3Bridge.xmbDecodeAudio(atPath: bootSoundFile.path, toWAV: wav.path) { try? FileManager.default.removeItem(at: wav) }
    }
}
