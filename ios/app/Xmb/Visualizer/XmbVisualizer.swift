// SPDX-License-Identifier: GPL-2.0-only
// The music player's visualizers in Now Playing: the chooser, the song's levels, Lines, and the console's Waves, Valley and Gaia; ported from Android's XmbVisualizer.kt and the visualizer parts of XmbNowPlaying.kt.

import AVFoundation
import CryptoKit
import SwiftUI

extension XmbVisualizerKind {
    var title: String {
        switch self {
        case .waves: String(localized: "Waves")
        case .valley: String(localized: "Valley")
        case .gaia: String(localized: "Gaia")
        case .lines: String(localized: "Lines")
        }
    }
}

/// Which visualizer Square shows, kept for next time; Left and Right change it while it shows, and its name comes up
/// for a moment each time. The right stick and a swipe move Valley's and Gaia's presets on.
@MainActor @Observable final class XmbVisualizerChooser {
    static let shared = XmbVisualizerChooser()

    private(set) var kind: XmbVisualizerKind
    /// The name with arrows either side, and Back, shown for three and a half seconds.
    private(set) var nameShown = false
    /// Valley is drawn from the firmware's canyon pack; without it, Valley shows the lines.
    private(set) var valley: XmbValleyFirmware.Pack?
    /// Gaia needs the firmware's earth.qrc; without it, Gaia shows the lines.
    private(set) var gaiaReady = false

    /// The console visualizer drawn now, which the right stick goes to.
    @ObservationIgnored fileprivate var showing: XmbVisualizerKind?
    @ObservationIgnored private var full = false
    @ObservationIgnored private var nameTask: Task<Void, Never>?
    @ObservationIgnored private var valleyHeld = [false, false, false, false]
    @ObservationIgnored private var gaiaHeld = false

    private init() {
        kind = UserDefaults.standard.string(forKey: XmbVisualizerKind.preferenceKey).flatMap(XmbVisualizerKind.init(rawValue:)) ?? .waves
    }

    /// Left or Right while the visualizer has the screen: the next or the one before.
    func step(_ step: Int) {
        kind = kind.stepped(step)
        UserDefaults.standard.set(kind.rawValue, forKey: XmbVisualizerKind.preferenceKey)
        showName()
    }

    /// The visualizer taking the screen or giving it back: its name comes up as it opens.
    fileprivate func setFull(_ on: Bool) {
        guard on != full else { return }
        full = on
        if on { showName() } else { hideName() }
    }

    private func showName() {
        guard full else { return }
        nameShown = true
        nameTask?.cancel()
        nameTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(3500))
            if !Task.isCancelled { self?.nameShown = false }
        }
    }

    private func hideName() {
        nameTask?.cancel()
        nameShown = false
    }

    /// A touch on the visualizer, `dx` and `dy` how far it went, `nameUp` whether the name showed as it began. A tap with
    /// the name up answers true (back to the player), without it brings the name up; a swipe moves Valley's and Gaia's
    /// presets on as the right stick does (for Valley: across another of the group, up or down another group). Told
    /// apart by how far the touch went.
    func touch(dx: CGFloat, dy: CGFloat, nameUp: Bool, far: CGFloat = 60) -> Bool {
        let across = abs(dx) >= abs(dy) && abs(dx) > far
        let upDown = !across && abs(dy) > far
        if !across && !upDown {
            if nameUp { return true }
            showName()
        } else if kind == .gaia {
            XmbGaiaPresets.flick()
        } else if kind == .valley && across {
            XmbValleyPresets.flick(dx < 0 ? -1 : 1, 0)
        } else if kind == .valley {
            XmbValleyPresets.flick(0, dy < 0 ? -1 : 1)
        }
        return false
    }

    /// The right stick, -1...1 with up negative. Each direction goes once a push, as the console's player reads the
    /// stick: past 70 of 128 to go, back under 66 to go again. Valley: left and right another of the group, up and down
    /// another group. Gaia: any way, another preset.
    func stick(x: Float, y: Float) {
        switch showing {
        case .valley:
            let push = [-x, x, -y, y]
            for d in 0 ..< 4 {
                let v = push[d] * 128
                if !valleyHeld[d] && v > 70 {
                    valleyHeld[d] = true
                    switch d {
                    case 0: XmbValleyPresets.flick(-1, 0)
                    case 1: XmbValleyPresets.flick(1, 0)
                    case 2: XmbValleyPresets.flick(0, -1)
                    default: XmbValleyPresets.flick(0, 1)
                    }
                } else if valleyHeld[d] && v < 66 {
                    valleyHeld[d] = false
                }
            }
        case .gaia:
            let v = max(abs(x), abs(y)) * 128
            if !gaiaHeld && v > 70 {
                gaiaHeld = true
                XmbGaiaPresets.flick()
            } else if gaiaHeld && v < 66 {
                gaiaHeld = false
            }
        default:
            break
        }
    }

    /// Whether the firmware has Valley and Gaia, read off the main thread; Valley's pack is kept once read.
    fileprivate func checkFirmware() async {
        let qgl = XmbFirmware.devFlash.appendingPathComponent("vsh/resource/qgl")
        if valley == nil {
            valley = await Task.detached(priority: .utility) {
                XmbQrc.open(qgl.appendingPathComponent("canyon.qrc")).flatMap(XmbValleyFirmware.pack(files:))
            }.value
        }
        gaiaReady = FileManager.default.fileExists(atPath: qgl.appendingPathComponent("earth.qrc").path)
    }
}

/// The song now playing, as the visualizers read it: its levels (worked out once and kept in the cache), where the
/// player is in it, and how hard it pushes the Waves.
@MainActor final class XmbVisualizerSong {
    static let shared = XmbVisualizerSong()

    private(set) var levels: XmbVisualizerLevels?
    private var drive = XmbMusicDrive()
    private var memory: [(key: String, levels: XmbVisualizerLevels)] = []

    private var media: XmbMedia { .shared }

    /// `song`'s levels (an id of XmbMedia's queue): from memory, the cache, or worked out now. None for the app's own
    /// track or a song that will not decode.
    func load(_ song: String?) async {
        levels = nil
        guard let song else { return }
        let url = XmbMedia.url(for: song)
        let key = Self.key(song, url)
        if let i = memory.firstIndex(where: { $0.key == key }) {
            let kept = memory.remove(at: i)
            memory.append(kept)
            levels = kept.levels
            return
        }
        // Off the main actor, and stopped when the song changes before it is done.
        let found = await Self.levels(url, key: key)
        guard !Task.isCancelled, let found else { return }
        memory.append((key, found))
        if memory.count > 3 { memory.removeFirst() }
        levels = found
    }

    var positionMs: Int { media.positionMs }
    var playing: Bool { media.isPlaying }

    /// The Waves' push now; asked once a frame.
    func nextDrive() -> Float { drive.next(levels, positionMs: positionMs, playing: playing) }

    /// Valley's row of the console's spectrum now, zeros while nothing plays.
    func spectrum(_ out: inout [Float]) {
        guard let levels, playing else {
            for c in out.indices { out[c] = 0 }
            return
        }
        let frame = positionMs * XmbVisualizerLevels.spectrumFPS / 1000
        for c in out.indices { out[c] = levels.column(frame, c) }
    }

    /// The song and its size and date, so a changed file is worked out again.
    private static func key(_ song: String, _ url: URL) -> String {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let stamp = "\(values?.fileSize ?? 0)-\(values?.contentModificationDate?.timeIntervalSince1970 ?? 0)"
        return String(Insecure.SHA1.hash(data: Data("\(song)|\(stamp)".utf8)).map { String(format: "%02x", $0) }.joined().prefix(24))
    }

    private nonisolated static func levels(_ url: URL, key: String) async -> XmbVisualizerLevels? {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("xmb-music/vis", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let cached = folder.appendingPathComponent(key + ".vis")
        if let data = try? Data(contentsOf: cached), let levels = XmbVisualizerLevels(cache: [UInt8](data)) { return levels }
        guard let levels = await analyze(url) else { return nil }
        try? Data(levels.encoded()).write(to: cached, options: .atomic)
        prune(folder)
        return levels
    }

    /// Keeps the folder under a size, oldest first out, since every song played adds about 2 MB and iOS purges late.
    private nonisolated static func prune(_ folder: URL, limit: Int = 200 << 20) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = ((try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys)) ?? []).map { file in
            let values = try? file.resourceValues(forKeys: Set(keys))
            return (file, values?.fileSize ?? 0, values?.contentModificationDate ?? .distantPast)
        }
        var total = files.reduce(0) { $0 + $1.1 }
        for (file, size, _) in files.sorted(by: { $0.2 < $1.2 }) where total > limit {
            try? FileManager.default.removeItem(at: file)
            total -= size
        }
    }

    /// The song decoded once as floats, as the player's own AVFoundation reads it, through the analyzer.
    private nonisolated static func analyze(_ url: URL) async -> XmbVisualizerLevels? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .audio).first, let reader = try? AVAssetReader(asset: asset)
        else { return nil }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsNonInterleaved: false, AVLinearPCMIsBigEndianKey: false,
        ])
        guard reader.canAdd(output) else { return nil }
        reader.add(output)
        guard reader.startReading() else { return nil }
        defer { reader.cancelReading() }
        var analyzer: XmbVisualizerAnalyzer?
        var samples: [Float] = []
        var steps = 0
        while let buffer = output.copyNextSampleBuffer() {
            steps += 1
            if steps % 64 == 0, Task.isCancelled { return nil }
            if analyzer == nil {
                guard let format = CMSampleBufferGetFormatDescription(buffer),
                      let description = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else { return nil }
                analyzer = XmbVisualizerAnalyzer(rate: Int(description.mSampleRate), channels: Int(description.mChannelsPerFrame))
            }
            guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            samples = [Float](repeating: 0, count: length / MemoryLayout<Float>.stride)
            let copied = samples.withUnsafeMutableBytes { CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!) }
            guard copied == kCMBlockBufferNoErr else { continue }
            samples.withUnsafeBufferPointer { analyzer?.feed($0) }
            if let analyzer, analyzer.frames > XmbVisualizerLevels.maxFrames { break }
        }
        return analyzer?.levels()
    }
}

/// Now Playing's visualizer: in the player, the lines; with the screen to itself, the
/// chosen one (Waves, Valley, Gaia or Lines; Valley and Gaia show the lines without the firmware's packs).
struct XmbVisualizerView: View {
    let full: Bool
    let accent: Color

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let chooser = XmbVisualizerChooser.shared
        let media = XmbMedia.shared
        // Nothing drawn while the app is away.
        let paused = scenePhase != .active
        Group {
            if full && chooser.kind == .waves {
                XmbWavesVisualizer(paused: paused)
                    .showing(.waves)
            } else if full && chooser.kind == .valley, let pack = chooser.valley {
                XmbValleyView(pack: pack, paused: paused) { XmbVisualizerSong.shared.spectrum(&$0) }
                    .showing(.valley)
            } else if full && chooser.kind == .gaia && chooser.gaiaReady {
                XmbGaiaView(paused: paused)
                    .showing(.gaia)
            } else {
                XmbVisualizerLinesView(color: accent, strength: full ? 1 : 0.8, paused: paused)
            }
        }
        .task(id: "\(media.current ?? "")|\(media.trackSerial)") { await XmbVisualizerSong.shared.load(media.current) }
        .task { await chooser.checkFirmware() }
        .onAppear {
            media.nowPlaying?.visualizerStep = { chooser.step($0) }
            chooser.setFull(full)
        }
        .onChange(of: full) { _, on in chooser.setFull(on) }
        .onDisappear { chooser.setFull(false) }
    }
}

private extension View {
    /// Which console visualizer is on screen, for the right stick.
    func showing(_ kind: XmbVisualizerKind) -> some View {
        onAppear { XmbVisualizerChooser.shared.showing = kind }
            .onDisappear { if XmbVisualizerChooser.shared.showing == kind { XmbVisualizerChooser.shared.showing = nil } }
    }
}

extension View {
    /// The visualizer's touches, in place of a plain tap: a tap with the name up calls `back`, without it brings the
    /// name up; a swipe moves Valley's and Gaia's presets on.
    func xmbVisualizerTouch(back: @escaping () -> Void) -> some View {
        modifier(XmbVisualizerTouch(back: back))
    }
}

private struct XmbVisualizerTouch: ViewModifier {
    let back: () -> Void
    @State private var nameUp: Bool?

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if nameUp == nil { nameUp = XmbVisualizerChooser.shared.nameShown }
                }
                .onEnded { drag in
                    let wasUp = nameUp ?? XmbVisualizerChooser.shared.nameShown
                    nameUp = nil
                    if XmbVisualizerChooser.shared.touch(dx: drag.translation.width, dy: drag.translation.height, nameUp: wasUp) { back() }
                })
    }
}

/// Over the visualizer while its name shows: the name with arrows either side (a touch on one changes it), and Back,
/// top right, laid over the visualizer with the song's label.
struct XmbVisualizerNameBar: View {
    let u: CGFloat
    let firmware: XmbFirmware
    let back: () -> Void

    private static var circleRed: Color { Color(red: 0xE8 / 255, green: 0x5C / 255, blue: 0x5C / 255) }

    var body: some View {
        let chooser = XmbVisualizerChooser.shared
        ZStack {
            if chooser.nameShown {
                HStack(spacing: 0) {
                    text("\u{25C0}", 22 * u, 0.95)
                        .padding(.horizontal, 22 * u)
                        .padding(.vertical, 18 * u)
                        .contentShape(Rectangle())
                        .onTapGesture { change(-1) }
                    // A touch on the name reaches the visualizer behind it, which goes back to the player.
                    text(chooser.kind.title, 22 * u, 0.95)
                        .allowsHitTesting(false)
                    text("\u{25B6}", 22 * u, 0.95)
                        .padding(.horizontal, 22 * u)
                        .padding(.vertical, 18 * u)
                        .contentShape(Rectangle())
                        .onTapGesture { change(1) }
                }
                .padding(.top, 12 * u)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .transition(.opacity)
                Button(action: back) {
                    HStack(spacing: 0) {
                        Text(verbatim: "\u{25CB}").font(firmware.font(size: 16 * u)).foregroundStyle(Self.circleRed).xmbShadow()
                        text(firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")), 16 * u, 0.9)
                    }
                    .padding(18 * u)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Back"))
                .padding(.top, 12 * u)
                .padding(.trailing, 12 * u)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: chooser.nameShown)
    }

    private func change(_ step: Int) {
        XmbVisualizerChooser.shared.step(step)
        firmware.play(.cursor)
    }

    private func text(_ words: String, _ size: CGFloat, _ alpha: Double) -> some View {
        Text(verbatim: words)
            .font(firmware.font(size: size))
            .foregroundStyle(.white.opacity(alpha))
            .lineLimit(1)
            .xmbShadow()
    }
}

/// Waves, as the console's: XMB mode's own wave in the mood the console's music player gives it (lines.qrc
/// override/music_1: its sky, colours, placement and glare), pushed by the song, so it billows on the loud parts and
/// settles on the quiet, with the XMB's sparkles on it.
private struct XmbWavesVisualizer: View {
    let paused: Bool
    @State private var link = XmbWaveLink()

    var body: some View {
        ZStack {
            XmbWaveView(themeColor: SettingsStore.shared.xmbThemeColor, paused: paused, link: link, mood: "music_1",
                        drive: { XmbVisualizerSong.shared.nextDrive() })
            XmbSparkleView(paused: paused) { [link] in link.renderer?.controlPoints }
        }
    }
}

/// Lines: glowing lines across the screen, still at the edges and swelling in the middle, as the PSP's music player
/// draws them, each line a range of the bands (the bass the slowest and widest), in `color`. At a pause, or with no
/// levels (a song still being read, or one that will not decode), the lines settle to a slow ripple.
struct XmbVisualizerLinesView: View {
    let color: Color
    var strength: CGFloat = 1
    var paused = false

    private final class Motion {
        var amp = [Float](repeating: 0, count: 5)
        var phase = [Float](repeating: 0, count: 5)
        var last: TimeInterval = 0
    }

    @State private var motion = Motion()

    var body: some View {
        // The lines are drawn on the CPU, so 30 a second, as the wave, is plenty.
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
            Canvas { context, size in
                draw(&context, size, now: timeline.date.timeIntervalSinceReferenceDate)
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize, now: TimeInterval) {
        let lines = 5
        let dt = motion.last == 0 ? 0 : Float(min(max(now - motion.last, 0), 0.1))
        motion.last = now
        let song = XmbVisualizerSong.shared
        let on = song.playing
        let levels = song.levels
        let frame = song.positionMs * XmbVisualizerLevels.fps / 1000
        let w = Float(size.width), h = Float(size.height)
        guard w > 0 else { return }
        let cy = h / 2
        let height = h * 0.34 * Float(strength)
        let pi = Float(Double.pi)
        context.blendMode = .plusLighter
        for i in 0 ..< lines {
            // Line i follows bands [3i, 3i + 3), the last one the top four.
            let from = i * 3
            let to = i == lines - 1 ? XmbVisualizerLevels.bands : from + 3
            var target: Float = 0
            if on, let levels {
                for b in from ..< to { target += levels.band(frame, b) }
                target /= Float(to - from)
            }
            // Quick to rise, slow to fall, as a meter is.
            let k: Float = target > motion.amp[i] ? 0.45 : 0.10
            motion.amp[i] += (target - motion.amp[i]) * k
            motion.phase[i] += dt * (0.9 + Float(i) * 0.55) * (0.6 + motion.amp[i] * 1.6)
            let a = 0.035 + motion.amp[i] * (1 - Float(i) * 0.1)
            let f1 = 1.2 + Float(i) * 0.65
            let sign: Float = i % 2 == 0 ? 1 : -1
            var path = Path()
            let step: Float = 6
            var x: Float = 0
            while x <= w + step {
                let t = x / w
                let e = (t - 0.5) / 0.23
                let env = exp(-(e * e))
                let wave = sin(2 * pi * f1 * t + motion.phase[i]) * 0.72 + sin(2 * pi * (f1 * 2.1) * t - motion.phase[i] * 1.37) * 0.28
                let y = cy + height * a * env * wave * sign
                let point = CGPoint(x: CGFloat(x), y: CGFloat(y))
                if x == 0 { path.move(to: point) } else { path.addLine(to: point) }
                x += step
            }
            context.stroke(path, with: .color(color.opacity(0.10 * strength)), style: StrokeStyle(lineWidth: 14, lineCap: .round))
            context.stroke(path, with: .color(color.opacity(0.28 * strength)), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            context.stroke(path, with: .color(.white.opacity(0.75 * strength)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
        }
    }
}
