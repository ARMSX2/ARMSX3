// SPDX-License-Identifier: GPL-2.0-only
// The XMB's video player over AVPlayer: its pad, its time bar and its options, ported from XmbVideoPlayer in Android's XmbVideo.kt.

import AVFoundation
import SwiftUI
import UIKit

/// A video full screen. Cross plays and pauses; Left and Right go back and on ten seconds; L1 and R1 the previous and
/// next video in its folder; Square changes the Screen Mode; Triangle opens the options; Circle stops, and the video
/// starts where it was left next time. The title and the time bar show on any press and go after three seconds of
/// playing.
@MainActor @Observable final class XmbVideoPlayback {
    static let shared = XmbVideoPlayback()

    private(set) var list: [XmbVideos.Entry] = []
    private(set) var index = 0
    private(set) var isOpen = false
    private(set) var playing = false
    private(set) var positionMs = 0
    private(set) var durationMs = 0
    private(set) var videoSize = CGSize(width: 16, height: 9)
    /// AVFoundation cannot play this one.
    private(set) var unsupported = false
    /// Said in the middle for a moment.
    private(set) var message: String?
    private(set) var screenMode = 1
    private(set) var aspectMode = 0
    private(set) var optionsOpen = false
    /// 0 Screen Mode, 1 Aspect Ratio.
    private(set) var optionsFocus = 0
    private(set) var shownAt = Date()
    private(set) var now = Date()

    @ObservationIgnored let player = AVPlayer()
    @ObservationIgnored private var first = false
    @ObservationIgnored private var prepared = false
    @ObservationIgnored private var ended = false
    @ObservationIgnored private var watchers: [NSKeyValueObservation] = []
    @ObservationIgnored private var endWatcher: NSObjectProtocol?
    @ObservationIgnored private var timeWatcher: Any?
    @ObservationIgnored private var pauseWatcher: NSKeyValueObservation?
    @ObservationIgnored private var clock: Task<Void, Never>?
    @ObservationIgnored private var messageClear: Task<Void, Never>?

    private var firmware: XmbFirmware { XmbFirmware.shared }
    private var store: XmbVideos { XmbVideos.shared }

    var entry: XmbVideos.Entry? { list.indices.contains(index) ? list[index] : nil }
    /// The bar hides three seconds into playing without a press; paused, it stays.
    var barShown: Bool { !playing || unsupported || now.timeIntervalSince(shownAt) < 3 }

    var screenModeWord: String { firmware.text("videoplayer_plugin", "msg_viewmode", String(localized: "Screen Mode")) }
    var screenModes: [String] {
        [firmware.text("videoplayer_plugin", "msg_normal", String(localized: "Normal")),
         firmware.text("videoplayer_plugin", "msg_full_screen", String(localized: "Full Screen")),
         firmware.text("videoplayer_plugin", "msg_zoom", String(localized: "Zoom")),
         firmware.text("videoplayer_plugin", "msg_original", String(localized: "Original"))]
    }
    var aspectWord: String { String(localized: "Aspect Ratio") }
    var aspects: [String] { [String(localized: "Auto"), "4:3", "16:9", "21:9"] }

    /// The picture's shape: its own, or the Aspect Ratio set for it.
    var aspect: Double {
        switch aspectMode {
        case 1: return 4.0 / 3.0
        case 2: return 16.0 / 9.0
        case 3: return 21.0 / 9.0
        default: return videoSize.width / videoSize.height
        }
    }

    // MARK: - Opening and closing

    func open(_ list: [XmbVideos.Entry], at index: Int, fromBeginning: Bool) {
        guard list.indices.contains(index) else { return }
        self.list = list
        self.index = index
        first = fromBeginning
        optionsOpen = false
        screenMode = store.screenMode
        isOpen = true
        // Chosen to be watched, so it plays with the Ring/Silent switch on, as a video app does. A game's session already plays.
        if !EmulatorSession.shared.isRunning { try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback) }
        if pauseWatcher == nil {
            pauseWatcher = player.observe(\.timeControlStatus) { [weak self] _, _ in
                Task { @MainActor in
                    // A call, Siri or headphones taken off pause the player by itself.
                    guard let self, self.playing, self.player.timeControlStatus == .paused else { return }
                    self.playing = false
                    self.poke()
                }
            }
        }
        if timeWatcher == nil {
            timeWatcher = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] time in
                MainActor.assumeIsolated {
                    guard let self, self.playing, time.seconds.isFinite else { return }
                    self.positionMs = Int(time.seconds * 1000)
                }
            }
        }
        clock?.cancel()
        clock = Task { [weak self] in
            while !Task.isCancelled {
                self?.now = Date()
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        load()
    }

    func close() {
        guard isOpen else { return }
        saveCurrent()
        unload()
        clock?.cancel()
        messageClear?.cancel()
        message = nil
        isOpen = false
        list = []
    }

    /// Leaving the app pauses it; coming back, it stays paused until Cross.
    func appLeft() {
        if playing { pauseNow() }
    }

    private func load() {
        unload()
        guard let entry else { return }
        playing = false
        positionMs = 0
        durationMs = 0
        unsupported = false
        prepared = false
        ended = false
        aspectMode = store.aspectMode(entry.key)
        let item = AVPlayerItem(url: entry.url)
        watchers = [
            item.observe(\.status) { [weak self] item, _ in
                Task { @MainActor in self?.statusChanged(item) }
            },
            item.observe(\.presentationSize) { [weak self] item, _ in
                Task { @MainActor in
                    let size = item.presentationSize
                    if size.width > 0, size.height > 0 { self?.videoSize = size }
                }
            },
        ]
        endWatcher = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item,
                                                            queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.finished() }
        }
        player.replaceCurrentItem(with: item)
    }

    private func unload() {
        player.pause()
        watchers = []
        if let endWatcher { NotificationCenter.default.removeObserver(endWatcher) }
        endWatcher = nil
        player.replaceCurrentItem(with: nil)
    }

    private func statusChanged(_ item: AVPlayerItem) {
        guard item === player.currentItem, let entry else { return }
        switch item.status {
        case .readyToPlay:
            guard !prepared else { return }
            prepared = true
            let seconds = item.duration.seconds
            durationMs = seconds.isFinite ? max(Int(seconds * 1000), 0) : 0
            let at = first ? 0 : store.resumeAt(entry)
            first = false
            if at > 0 {
                player.seek(to: CMTime(value: CMTimeValue(at), timescale: 1000))
                positionMs = at
            }
            player.play()
            playing = true
            poke()
        case .failed:
            playing = false
            unsupported = true
        default:
            break
        }
    }

    private func finished() {
        playing = false
        ended = true
        if let entry { store.saveResume(entry.key, positionMs: 0, durationMs: 0) }
        poke()
    }

    private func saveCurrent() {
        guard let entry, durationMs > 0, !unsupported else { return }
        let seconds = player.currentTime().seconds
        store.saveResume(entry.key, positionMs: seconds.isFinite ? Int(seconds * 1000) : positionMs, durationMs: durationMs)
    }

    // MARK: - What the pad and a touch do

    private func poke() {
        shownAt = Date()
        now = shownAt
    }

    private func pauseNow() {
        player.pause()
        playing = false
    }

    private func playNow() {
        // At the end, Cross plays it again from the start.
        if ended {
            ended = false
            seekNow(0)
        }
        player.play()
        playing = true
    }

    private func seekNow(_ ms: Int) {
        player.seek(to: CMTime(value: CMTimeValue(ms), timescale: 1000))
        positionMs = ms
        ended = false
    }

    func toggle() {
        poke()
        guard !unsupported, durationMs > 0 else { return }
        if playing { pauseNow() } else { playNow() }
    }

    func seek(by ms: Int) {
        poke()
        guard durationMs > 0 else { return }
        seekNow(min(max(positionMs + ms, 0), max(durationMs - 500, 0)))
    }

    func seek(to fraction: Double) {
        poke()
        guard durationMs > 0 else { return }
        seekNow(min(Int(Double(durationMs) * min(max(fraction, 0), 1)), max(durationMs - 500, 0)))
    }

    /// The previous or next video in its folder.
    func go(_ step: Int) -> XmbSound {
        let next = index + step
        guard list.indices.contains(next) else {
            say(firmware.text(XmbRco.full, "msg_error_no_title", String(localized: "There are no titles.")))
            return .error
        }
        saveCurrent()
        index = next
        load()
        return .cursor
    }

    /// Square, or its hint touched: the next Screen Mode, named for a moment as it changes.
    func nextScreenMode() {
        screenMode = (screenMode + 1) % screenModes.count
        store.screenMode = screenMode
        say(screenModeWord + "  " + screenModes[screenMode])
        poke()
    }

    func changeOption(_ row: Int, _ step: Int) -> XmbSound {
        if row == 0 {
            screenMode = (screenMode + step + screenModes.count) % screenModes.count
            store.screenMode = screenMode
        } else if let entry {
            aspectMode = (aspectMode + step + aspects.count) % aspects.count
            store.setAspectMode(entry.key, aspectMode)
        }
        return .cursor
    }

    func openOptions(_ open: Bool) -> XmbSound {
        optionsOpen = open
        optionsFocus = 0
        poke()
        return open ? .option : .cancel
    }

    /// A tap plays or pauses with the bar up, else brings the bar up; with the options open it closes them.
    func tap() {
        if optionsOpen {
            firmware.play(openOptions(false))
        } else if barShown {
            toggle()
        } else {
            poke()
        }
    }

    private func say(_ text: String) {
        message = text
        messageClear?.cancel()
        messageClear = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(2500))
            guard !Task.isCancelled else { return }
            self?.message = nil
        }
    }

    /// A press while the player is up. Answers the console's sound for it, or nil for silence.
    func apply(_ command: MenuControllerCommand) -> XmbSound? {
        guard isOpen else { return nil }
        if optionsOpen {
            switch command {
            case .up, .down:
                let next = min(max(optionsFocus + (command == .up ? -1 : 1), 0), 1)
                guard next != optionsFocus else { return nil }
                optionsFocus = next
                return .cursor
            case .left: return changeOption(optionsFocus, -1)
            case .right, .activate: return changeOption(optionsFocus, 1)
            case .toggleFavorite, .back: return openOptions(false)
            default: return nil
            }
        }
        switch command {
        case .activate: toggle()
        case .left: seek(by: -10_000)
        case .right: seek(by: 10_000)
        case .previousTab: return go(-1)
        case .nextTab: return go(1)
        case .showContextMenu: nextScreenMode()
        case .toggleFavorite: return openOptions(true)
        case .back:
            close()
            return .cancel
        default: poke()
        }
        return nil
    }
}

/// The player on black with its title, time bar and options, drawn while `XmbVideoPlayback.shared` is open.
struct XmbVideoPlayerView: View {
    let firmware: XmbFirmware
    @Environment(\.displayScale) private var displayScale
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let playback = XmbVideoPlayback.shared
        if playback.isOpen, let entry = playback.entry {
            GeometryReader { geometry in
                let u = min(geometry.size.width / 1280, geometry.size.height / 720)
                let picture = XmbVideoRules.pictureSize(mode: playback.screenMode, aspect: playback.aspect, screen: geometry.size,
                                                       videoHeight: playback.videoSize.height, scale: displayScale)
                ZStack {
                    Color.black
                        // The bars go after three seconds of playing, so VoiceOver plays, pauses, seeks and leaves here.
                        .accessibilityElement()
                        .accessibilityLabel(Text(verbatim: entry.title))
                        .accessibilityValue((playback.playing ? Text("Playing") : Text("Paused"))
                            + Text(verbatim: ", " + XmbTime.spoken(playback.positionMs, of: playback.durationMs)))
                        .accessibilityAction { playback.toggle() }
                        .accessibilityAction(.magicTap) { playback.toggle() }
                        .accessibilityAdjustableAction { playback.seek(by: $0 == .increment ? 10_000 : -10_000) }
                        .accessibilityAction(.escape) { if let sound = playback.apply(.back) { firmware.play(sound) } }
                    XmbPlayerLayer(player: playback.player)
                        .frame(width: picture.width, height: picture.height)
                    if playback.unsupported {
                        centered(firmware.text(XmbRco.full, "msg_error_unsupported_data",
                                               String(localized: "The data type is not supported.")), size: 22 * u, u: u)
                    }
                    if let message = playback.message {
                        centered(message, size: 20 * u, u: u)
                    }
                    if playback.barShown {
                        bars(playback, entry: entry, u: u)
                            .transition(.opacity)
                    }
                    if playback.optionsOpen {
                        options(playback, u: u)
                            .transition(.opacity)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
                .contentShape(Rectangle())
                .onTapGesture { playback.tap() }
                .animation(.easeInOut(duration: 0.2), value: playback.barShown)
                .animation(.easeInOut(duration: 0.2), value: playback.optionsOpen)
            }
            .ignoresSafeArea()
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { playback.appLeft() }
            }
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { playback.close() }
        }
    }

    private func centered(_ text: String, size: CGFloat, u: CGFloat) -> some View {
        Text(verbatim: text)
            .font(firmware.font(size: size))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .xmbShadow()
            .padding(24 * u)
    }

    /// The title over a shade at the top, with Back for a touch; the time bar over one at the bottom, its hints taking
    /// a touch as their buttons do.
    private func bars(_ playback: XmbVideoPlayback, entry: XmbVideos.Entry, u: CGFloat) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                Text(verbatim: entry.title)
                    .font(firmware.font(size: 24 * u))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.leading, 40 * u)
                    .padding(.top, 22 * u)
                    .padding(.trailing, 20 * u)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { playback.close(); firmware.play(.cancel) } label: {
                    HStack(spacing: 0) {
                        Text(verbatim: "\u{25CB}").font(firmware.font(size: 15 * u)).foregroundStyle(Color(red: 0.91, green: 0.36, blue: 0.36))
                        Text(verbatim: firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")))
                            .font(firmware.font(size: 15 * u)).foregroundStyle(.white.opacity(0.9))
                    }
                    .padding(20 * u)
                    .xmbTouchTarget()
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Back"))
                .padding(.trailing, 20 * u)
            }
            .frame(height: 110 * u, alignment: .top)
            .background(LinearGradient(colors: [.black.opacity(0.65), .clear], startPoint: .top, endPoint: .bottom))
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                HStack(spacing: 14 * u) {
                    XmbIconView(icon: .firmware(rco: XmbRco.music, name: playback.playing ? "tex_play_status" : "tex_pause_status",
                                                symbol: playback.playing ? "play.fill" : "pause.fill"), firmware: firmware)
                        .frame(width: 28 * u, height: 28 * u)
                    clock(playback.positionMs, u: u)
                    timeBar(playback, u: u)
                    clock(playback.durationMs, u: u)
                }
                HStack(spacing: 0) {
                    if playback.list.count > 1 {
                        Text(verbatim: "\(playback.index + 1) / \(playback.list.count)")
                            .font(firmware.font(size: 15 * u)).foregroundStyle(.white.opacity(0.7))
                    }
                    Spacer(minLength: 0)
                    hint("\u{25A1}", Color(red: 0.894, green: 0.545, blue: 0.839), playback.screenModeWord, u: u) { playback.nextScreenMode() }
                    hint("\u{25B3}", Color(red: 0.435, green: 0.827, blue: 0.627), String(localized: "Options"), u: u) {
                        firmware.play(playback.openOptions(true))
                    }
                }
                .padding(.top, 10 * u)
            }
            .padding(.horizontal, 40 * u)
            .padding(.vertical, 22 * u)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom))
        }
    }

    private func clock(_ ms: Int, u: CGFloat) -> some View {
        Text(verbatim: XmbTime.clock(ms))
            .font(firmware.font(size: 17 * u))
            .foregroundStyle(.white.opacity(0.95))
            .monospacedDigit()
    }

    private func timeBar(_ playback: XmbVideoPlayback, u: CGFloat) -> some View {
        GeometryReader { bar in
            let fraction = playback.durationMs > 0 ? min(max(Double(playback.positionMs) / Double(playback.durationMs), 0), 1) : 0
            let h = 4 * u
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25)).frame(height: h)
                Capsule().fill(.white).frame(width: bar.size.width * fraction, height: h)
                slider(u: u)
                    .position(x: bar.size.width * fraction, y: bar.size.height / 2)
            }
            .frame(width: bar.size.width, height: bar.size.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                playback.seek(to: drag.location.x / max(bar.size.width, 1))
            })
        }
        .frame(height: 26 * u)
    }

    @ViewBuilder private func slider(u: CGFloat) -> some View {
        if let image = firmware.image(XmbRco.video, "tex_sb_slider") {
            Image(uiImage: image).resizable().frame(width: 34 * u, height: 12 * u)
        } else {
            Capsule().fill(.white).frame(width: 12 * u, height: 12 * u)
        }
    }

    private func hint(_ mark: String, _ color: Color, _ word: String, u: CGFloat, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Text(verbatim: mark).font(firmware.font(size: 15 * u)).foregroundStyle(color)
                Text(verbatim: " " + word).font(firmware.font(size: 15 * u)).foregroundStyle(.white.opacity(0.9))
            }
            .padding(.horizontal, 12 * u)
            .padding(.vertical, 10 * u)
            // Grown downward only, so the time bar above keeps its whole width for seeking.
            .xmbTouchTarget(above: 0, below: 15)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: word))
    }

    /// Triangle's options, at the right as the console's are: each one's name over its setting, Left and Right (or
    /// Cross) to change it, Circle or Triangle to close.
    private func options(_ playback: XmbVideoPlayback, u: CGFloat) -> some View {
        let rows = [(playback.screenModeWord, playback.screenModes[playback.screenMode]),
                    (playback.aspectWord, playback.aspects[playback.aspectMode])]
        return ZStack(alignment: .trailing) {
            LinearGradient(stops: [.init(color: .clear, location: 0.45), .init(color: .black.opacity(0.8), location: 1)],
                           startPoint: .leading, endPoint: .trailing)
            VStack(spacing: 14 * u) {
                ForEach(rows.indices, id: \.self) { row in
                    VStack(alignment: .leading, spacing: 0) {
                        Text(verbatim: rows[row].0).font(firmware.font(size: 16 * u)).foregroundStyle(.white.opacity(0.75))
                        HStack(spacing: 0) {
                            Text(verbatim: "\u{25C0}").font(firmware.font(size: 15 * u)).foregroundStyle(.white.opacity(0.6))
                            Text(verbatim: rows[row].1).font(firmware.font(size: 22 * u)).foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                            Text(verbatim: "\u{25B6}").font(firmware.font(size: 15 * u)).foregroundStyle(.white.opacity(0.6))
                        }
                    }
                    .padding(.horizontal, 18 * u)
                    .padding(.vertical, 10 * u)
                    .background(RoundedRectangle(cornerRadius: 6 * u).fill(.white.opacity(row == playback.optionsFocus ? 0.12 : 0)))
                    .contentShape(Rectangle())
                    .onTapGesture { firmware.play(playback.changeOption(row, 1)) }
                }
            }
            .frame(width: 440 * u)
            .padding(.trailing, 48 * u)
        }
    }
}

/// AVPlayer's picture, stretched to the frame it is given: the Screen Mode is worked out in that frame.
private struct XmbPlayerLayer: UIViewRepresentable {
    let player: AVPlayer

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resize
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {}
}
