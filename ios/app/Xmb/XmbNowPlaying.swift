// SPDX-License-Identifier: GPL-2.0-only
// Now Playing, the XMB's music player as a panel over it, ported from Android's XmbNowPlaying.kt.

import SwiftUI

/// Now Playing while it is open (`XmbMedia.shared.nowPlaying`): where the pad is and whether the visualizer has the
/// screen. On the pad: Left and Right along the buttons and Cross to press one; Up to the time bar, where Left and
/// Right go back and on ten seconds; L1 and R1 the previous and next song; Square the visualizer on its own and back;
/// Circle closes it and the music plays on behind the XMB.
@MainActor @Observable final class XmbNowPlayingPanel {
    enum Control: Int, CaseIterable {
        case previous, play, next, stop, repeatMode, shuffle, volume

        var texture: String {
            ["tex_cp_prev", "tex_cp_pause", "tex_cp_next", "tex_cp_stop", "tex_cp_repeat", "tex_cp_shuffle", "tex_cp_volume"][rawValue]
        }

        var symbol: String {
            ["backward.end.fill", "playpause.fill", "forward.end.fill", "stop.fill", "repeat", "shuffle", "speaker.wave.2.fill"][rawValue]
        }
    }

    /// The button the pad is on, or nil on the time bar.
    private(set) var focus: Control? = .play
    private(set) var visualizerOnly = false
    /// "There is no track available.", for a moment.
    private(set) var message: String?
    /// Left and Right while the visualizer has the screen; the visualizer sets it to change its kind.
    @ObservationIgnored var visualizerStep: ((Int) -> Void)?
    @ObservationIgnored private var lastButton = Control.play
    @ObservationIgnored private var messageTask: Task<Void, Never>?

    /// A step down each press, round to full again, as the console's panel steps it.
    static let volumeSteps = [100, 75, 50, 25]

    private var media: XmbMedia { .shared }

    func close() {
        if media.nowPlaying === self { media.nowPlaying = nil }
    }

    /// A press while Now Playing is up; the answer is the console's sound for it, or nil for silence.
    func apply(_ command: MenuControllerCommand) -> XmbSound? {
        switch command {
        case .back:
            close()
            return .cancel
        case .previousTab:
            return skip(-1)
        case .nextTab:
            return skip(1)
        case .showContextMenu:
            showVisualizer(!visualizerOnly)
            return nil
        case .toggleFavorite:
            return nil
        default:
            break
        }
        if visualizerOnly {
            switch command {
            case .activate:
                media.playPause()
                return .decide
            case .left, .right:
                guard let step = visualizerStep else { return nil }
                step(command == .left ? -1 : 1)
                return .cursor
            default:
                return nil
            }
        }
        switch command {
        case .up, .upLeft, .upRight:
            guard let button = focus else { return nil }
            lastButton = button
            focus = nil
            return .cursor
        case .down, .downLeft, .downRight:
            guard focus == nil else { return nil }
            focus = lastButton
            return .cursor
        case .left, .right:
            let step = command == .left ? -1 : 1
            guard let button = focus else {
                seek(by: step * 10_000)
                return nil
            }
            guard let next = Control(rawValue: button.rawValue + step) else { return nil }
            focus = next
            return .cursor
        case .activate:
            guard let button = focus else { return nil }
            return press(button)
        default:
            return nil
        }
    }

    /// A touch on a button: the pad goes there too.
    func tap(_ button: Control) {
        focus = button
        lastButton = button
        if let sound = press(button) { XmbFirmware.shared.play(sound) }
    }

    @discardableResult func press(_ button: Control) -> XmbSound? {
        switch button {
        case .previous: return skip(-1)
        case .next: return skip(1)
        case .play: media.playPause()
        case .stop: media.stopSong()
        case .repeatMode: media.setRepeat(XmbRepeat(rawValue: (media.repeatMode.rawValue + 1) % XmbRepeat.allCases.count) ?? .off)
        case .shuffle: media.setShuffle(!media.shuffle)
        case .volume:
            let volume = media.chosen ? media.playerVolumePercent : media.volumePercent
            let next = Self.volumeSteps.first { $0 < volume } ?? Self.volumeSteps[0]
            if media.chosen { media.setPlayerVolume(next) } else { media.setVolume(next) }
        }
        return .decide
    }

    /// Back from the visualizer the pad is on Pause, as on the console.
    func showVisualizer(_ on: Bool) {
        visualizerOnly = on
        guard !on else { return }
        focus = .play
        lastButton = .play
    }

    private func skip(_ step: Int) -> XmbSound {
        if media.skip(step) { return .cursor }
        let words = XmbFirmware.shared.text(XmbRco.full, "msg_error_no_nexttrack", String(localized: "There is no track available."))
        message = words
        messageTask?.cancel()
        messageTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(2500))
            if !Task.isCancelled { self?.message = nil }
        }
        return .error
    }

    func seek(by ms: Int) {
        let duration = media.durationMs
        guard duration > 0 else { return }
        media.seek(to: min(max(media.positionMs + ms, 0), duration - 500))
    }

    func seek(toFraction fraction: Double) {
        let duration = media.durationMs
        guard duration > 0 else { return }
        media.seek(to: min(Int(Double(duration) * min(max(fraction, 0), 1)), duration - 500))
    }
}

/// The player: the song's cover on its reflection, its title, artist and album, where it stands in its list, the
/// time bar and the play mode, over the visualizer; along the bottom the console's control panel.
struct XmbNowPlayingView<Visualizer: View>: View {
    let panel: XmbNowPlayingPanel
    let firmware: XmbFirmware
    /// Where a visualizer is mounted: `full` while it has the screen, `accent` the month's colour lightened for its
    /// light.
    @ViewBuilder var visualizer: (_ full: Bool, _ accent: Color) -> Visualizer

    private static var squarePink: Color { Color(red: 0xE4 / 255, green: 0x8B / 255, blue: 0xD6 / 255) }
    private static var circleRed: Color { Color(red: 0xE8 / 255, green: 0x5C / 255, blue: 0x5C / 255) }
    @State private var keptAwake = false

    var body: some View {
        let media = XmbMedia.shared
        let month = XmbOriginalColors.at(Date(), themeColor: SettingsStore.shared.xmbThemeColor)
        let end = month.end, start = month.start
        let accent = Color(red: Double(end.x + (1 - end.x) * 0.35), green: Double(end.y + (1 - end.y) * 0.35),
                           blue: Double(end.z + (1 - end.z) * 0.35))
        GeometryReader { geometry in
            let u = min(geometry.size.width / 1232, geometry.size.height / 620)
            ZStack {
                // Its own dark ground with the month's colour coming up from the bottom: the XMB's lists do not show
                // through, and the visualizer's light reads against it.
                LinearGradient(colors: [Color(red: 5 / 255, green: 5 / 255, blue: 7 / 255).opacity(0xF4 / 255),
                                        Color(red: 5 / 255, green: 5 / 255, blue: 7 / 255).opacity(0xF0 / 255),
                                        Color(red: Double(start.x) * 0.22, green: Double(start.y) * 0.22, blue: Double(start.z) * 0.22).opacity(0.94)],
                               startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
                visualizer(panel.visualizerOnly, accent)
                    .frame(maxWidth: .infinity, maxHeight: panel.visualizerOnly ? .infinity : geometry.size.height * 0.46)
                    .padding(.bottom, panel.visualizerOnly ? 0 : 96 * u)
                    .frame(maxHeight: .infinity, alignment: panel.visualizerOnly ? .center : .bottom)
                    // Edge to edge as on Android: the whole screen on its own, the full width under the player.
                    .ignoresSafeArea(edges: panel.visualizerOnly ? .all : .horizontal)
                    .allowsHitTesting(false)
                if panel.visualizerOnly {
                    visualizerOverlay(media, u: u)
                } else {
                    player(media, u: u, height: geometry.size.height)
                        .transition(.opacity)
                }
                if let message = panel.message {
                    text(message, 20 * u).padding(20 * u)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: panel.visualizerOnly)
        }
        .accessibilityAction(.escape) { leave() }
        .accessibilityAction(.magicTap) { media.playPause() }
        // The visualizer is the thing to watch, so the phone stays awake while a song plays, as Android keeps its saver away.
        .onChange(of: media.enabled && !media.userPaused, initial: true) { _, playing in keepAwake(playing) }
        .onDisappear { keepAwake(false) }
    }

    /// Circle for VoiceOver: the visualizer gives the screen back, the player closes.
    private func leave() {
        if panel.visualizerOnly {
            panel.showVisualizer(false)
        } else {
            panel.close()
            firmware.play(.cancel)
        }
    }

    /// Installs and updates use the same switch, so the panel turns off only what it turned on.
    private func keepAwake(_ on: Bool) {
        if on, !keptAwake, !UIApplication.shared.isIdleTimerDisabled {
            UIApplication.shared.isIdleTimerDisabled = true
            keptAwake = true
        } else if !on, keptAwake {
            UIApplication.shared.isIdleTimerDisabled = false
            keptAwake = false
        }
    }

    // MARK: - The player

    private func player(_ media: XmbMedia, u: CGFloat, height: CGFloat) -> some View {
        let song = media.current
        let track = song.flatMap { media.library.byId[$0] }
        let album = song.flatMap { media.library.album(of: $0) }
        let paused = media.userPaused || !media.enabled
        return ZStack {
            VStack(spacing: 0) {
                // The title row, as the XMB's panels have it: the player's icon and name, and Back.
                HStack(spacing: 14 * u) {
                    XmbIconView(icon: .firmware(rco: XmbRco.music, name: "tex_now_playing", symbol: "play.circle"), firmware: firmware)
                        .frame(width: 40 * u, height: 40 * u)
                    text(String(localized: "Now Playing"), 23 * u)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    hint("\u{25CB}", Self.circleRed, firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")), u: u) { panel.close() }
                }
                .padding(.leading, 40 * u)
                .padding(.trailing, 10 * u)
                .padding(.top, 18 * u)
                .frame(height: 64 * u, alignment: .top)
                HStack(spacing: 44 * u) {
                    cover(song.flatMap { media.library.cover(of: $0) }, size: 300 * u)
                    VStack(alignment: .leading, spacing: 0) {
                        text(track?.title ?? song.map { (XmbMedia.songName($0) as NSString).deletingPathExtension } ?? String(localized: "Built-in Track"),
                             34 * u, lines: 2)
                        if let artist = track?.artist ?? album?.artist {
                            text(artist, 22 * u, 0.92).padding(.top, 6 * u)
                        }
                        if let albumTitle = track?.album ?? album?.title {
                            text(albumTitle, 18 * u, 0.7).padding(.top, 2 * u)
                        }
                        if media.queue.count > 1 {
                            text("\(media.queueIndex + 1) / \(media.queue.count)", 16 * u, 0.7).padding(.top, 10 * u)
                        }
                        timeBar(media, u: u).padding(.top, 26 * u)
                        modeIcons(media, u: u, paused: paused).padding(.top, 10 * u)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 40 * u)
                .frame(maxHeight: .infinity)
                controlPanel(media, u: u, paused: paused)
                    .padding(.bottom, 18 * u)
            }
            // Square's hint, bottom right, where the XMB's own hints sit.
            hint("\u{25A1}", Self.squarePink, " " + String(localized: "Visualizer"), u: u) { panel.showVisualizer(true) }
                .padding(.trailing, 10 * u)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        }
    }

    /// The cover, the console's way: framed in white, on its own reflection. A touch opens the visualizer.
    private func cover(_ path: String?, size: CGFloat) -> some View {
        VStack(spacing: 3) {
            picture(path, size: size)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: 3))
                .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(.white.opacity(0.55), lineWidth: 1))
                .contentShape(Rectangle())
                .onTapGesture { panel.showVisualizer(true) }
            // The reflection: the picture upside down, fading out a third of the way down.
            picture(path, size: size)
                .frame(width: size, height: size * 0.3)
                .clipShape(RoundedRectangle(cornerRadius: 3))
                .scaleEffect(x: 1, y: -1)
                .mask(LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom))
                .opacity(0.32)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder private func picture(_ path: String?, size: CGFloat) -> some View {
        let missing = XmbIconView(icon: .firmware(rco: XmbRco.music, name: "music_tex_album_default", symbol: "square.stack"), firmware: firmware)
            .frame(width: size * 0.6, height: size * 0.6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        if let path {
            XmbPicture(path: path, fit: false) { missing }
        } else {
            missing
        }
    }

    /// The time gone, the bar, the song's length. A touch or a drag along the bar goes there.
    private func timeBar(_ media: XmbMedia, u: CGFloat) -> some View {
        let selected = panel.focus == nil && !panel.visualizerOnly
        // Four times a second, as the console's play clock samples.
        return TimelineView(.animation(minimumInterval: 0.25)) { _ in
            let duration = media.durationMs
            let fraction = duration > 0 ? min(max(Double(media.positionMs) / Double(duration), 0), 1) : 0
            HStack(spacing: 0) {
                text(XmbTime.clock(media.positionMs), 16 * u, 0.9)
                    .frame(width: 64 * u, alignment: .leading)
                GeometryReader { bar in
                    let h = (selected ? 5 : 3.5) * u
                    ZStack(alignment: .leading) {
                        if selected {
                            Capsule().fill(.white.opacity(0.18)).padding(.horizontal, -4)
                        }
                        Capsule().fill(.white.opacity(0.24)).frame(height: h)
                        Capsule().fill(.white.opacity(selected ? 1 : 0.85)).frame(width: bar.size.width * fraction, height: h)
                        // The firmware's slider for the thumb.
                        if let thumb = firmware.image(XmbRco.music, "tex_sb_slider") {
                            Image(uiImage: thumb).resizable().scaledToFit()
                                .frame(width: 34 * u, height: 12 * u)
                                .offset(x: bar.size.width * fraction - 34 * u)
                        }
                    }
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                        panel.seek(toFraction: drag.location.x / max(bar.size.width, 1))
                    })
                }
                .frame(width: 440 * u, height: 24 * u)
                text(XmbTime.clock(duration), 16 * u, 0.9)
                    .padding(.leading, 14 * u)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Track Position"))
            .accessibilityValue(Text(verbatim: XmbTime.spoken(media.positionMs, of: duration)))
            .accessibilityAdjustableAction { panel.seek(by: $0 == .increment ? 10_000 : -10_000) }
        }
    }

    /// The play mode as the console marks it: playing or paused, shuffle, and repeat all or one.
    private func modeIcons(_ media: XmbMedia, u: CGFloat, paused: Bool) -> some View {
        HStack(spacing: 10 * u) {
            XmbIconView(icon: .firmware(rco: XmbRco.music, name: paused ? "tex_pause_status" : "tex_play_status",
                                        symbol: paused ? "pause.fill" : "play.fill"), firmware: firmware)
                .frame(width: 26 * u, height: 26 * u)
            if media.shuffle {
                XmbIconView(icon: .firmware(rco: XmbRco.music, name: "tex_shuffle_status", symbol: "shuffle"), firmware: firmware)
                    .frame(width: 34 * u, height: 26 * u)
            }
            switch media.repeatMode {
            case .all:
                XmbIconView(icon: .firmware(rco: XmbRco.music, name: "tex_repeat_status", symbol: "repeat"), firmware: firmware)
                    .frame(width: 43 * u, height: 26 * u)
            case .one:
                ZStack {
                    XmbIconView(icon: .firmware(rco: XmbRco.music, name: "tex_repeat_status", symbol: "repeat.1"), firmware: firmware)
                        .frame(width: 43 * u, height: 26 * u)
                    if firmware.image(XmbRco.music, "tex_1repeat_status") != nil {
                        XmbIconView(icon: .firmware(rco: XmbRco.music, name: "tex_1repeat_status", symbol: "1.circle"), firmware: firmware)
                            .frame(width: 26 * u, height: 13 * u)
                    }
                }
            case .off:
                EmptyView()
            }
        }
    }

    /// The console's control panel: its button pictures in a row, the selected one lit from behind, and under a button
    /// what it is set to.
    private func controlPanel(_ media: XmbMedia, u: CGFloat, paused: Bool) -> some View {
        let volume = media.chosen ? media.playerVolumePercent : media.volumePercent
        return HStack(spacing: 6 * u) {
            ForEach(XmbNowPlayingPanel.Control.allCases, id: \.self) { button in
                VStack(spacing: 0) {
                    ZStack {
                        if panel.focus == button {
                            if let lit = firmware.image(XmbRco.music, "tex_cp_focus") {
                                Image(uiImage: lit).resizable().scaledToFit().frame(width: 100 * u, height: 66 * u)
                            } else {
                                RoundedRectangle(cornerRadius: 8 * u).fill(.white.opacity(0.18)).frame(width: 100 * u, height: 66 * u)
                            }
                        }
                        XmbIconView(icon: .firmware(rco: XmbRco.music, name: button.texture, symbol: button.symbol), firmware: firmware)
                            .frame(width: 87 * u, height: 58 * u)
                    }
                    .frame(width: 96 * u, height: 66 * u)
                    .contentShape(Rectangle())
                    .onTapGesture { panel.tap(button) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(name(of: button, paused: paused))
                    .accessibilityValue(spokenState(of: button, media, volume: volume))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { panel.tap(button) }
                    text(state(of: button, media, paused: paused, volume: volume) ?? "", 13 * u, 0.9)
                        .frame(height: 18 * u)
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func name(of button: XmbNowPlayingPanel.Control, paused: Bool) -> Text {
        switch button {
        case .previous: return Text("Previous")
        case .play: return paused ? Text("Play") : Text("Pause")
        case .next: return Text("Next")
        case .stop: return Text("Stop")
        case .repeatMode: return Text("Repeat")
        case .shuffle: return Text("Shuffle")
        case .volume: return Text("Volume")
        }
    }

    private func spokenState(of button: XmbNowPlayingPanel.Control, _ media: XmbMedia, volume: Int) -> Text {
        switch button {
        case .repeatMode: return media.repeatMode == .off ? Text("Off") : media.repeatMode == .all ? Text("All") : Text("One")
        case .shuffle: return media.shuffle ? Text("On") : Text("Off")
        case .volume: return Text(verbatim: "\(volume)%")
        default: return Text(verbatim: "")
        }
    }

    private func state(of button: XmbNowPlayingPanel.Control, _ media: XmbMedia, paused: Bool, volume: Int) -> String? {
        switch button {
        case .play: return paused ? "\u{25B6}" : nil
        case .repeatMode: return media.repeatMode == .off ? nil : media.repeatMode == .all ? "\u{25CF}" : "1"
        case .shuffle: return media.shuffle ? "\u{25CF}" : nil
        case .volume: return "\(volume)%"
        default: return nil
        }
    }

    // MARK: - The visualizer on its own

    /// Over the visualizer, the song as the console labels it there: bottom left, its icon, the title, and
    /// "artist / album" under it. A touch goes back to the player.
    private func visualizerOverlay(_ media: XmbMedia, u: CGFloat) -> some View {
        let song = media.current
        let track = song.flatMap { media.library.byId[$0] }
        let album = song.flatMap { media.library.album(of: $0) }
        let cover = song.flatMap { media.library.cover(of: $0) }
        let line = [track?.artist ?? album?.artist, track?.album ?? album?.title].compactMap { $0 }.joined(separator: " / ")
        return ZStack(alignment: .bottomLeading) {
            // The whole screen answers, as the visualizer is drawn edge to edge.
            Color.clear.ignoresSafeArea().xmbVisualizerTouch { panel.showVisualizer(false) }
                // Back goes with the visualizer's name, so VoiceOver changes the kind and goes back here.
                .accessibilityElement()
                .accessibilityLabel(Text("Visualizer"))
                .accessibilityValue(Text(verbatim: XmbVisualizerChooser.shared.kind.title))
                .accessibilityHint(Text("Goes back to the player."))
                .accessibilityAction { panel.showVisualizer(false) }
                .accessibilityAction(.escape) { panel.showVisualizer(false) }
                .accessibilityAdjustableAction { panel.visualizerStep?($0 == .increment ? 1 : -1) }
            XmbVisualizerNameBar(u: u, firmware: firmware) { panel.showVisualizer(false) }
            HStack(spacing: 12 * u) {
                XmbIconView(icon: cover.map { .game(path: $0) } ?? .firmware(rco: XmbRco.music, name: "music_tex_default", symbol: "music.note"),
                            firmware: firmware)
                    .frame(width: 52 * u, height: 52 * u)
                VStack(alignment: .leading, spacing: 0) {
                    text(track?.title ?? song.map { (XmbMedia.songName($0) as NSString).deletingPathExtension } ?? String(localized: "Built-in Track"), 21 * u)
                    if !line.isEmpty { text(line, 15 * u, 0.85) }
                }
            }
            .padding(.leading, 70 * u)
            .padding(.bottom, 40 * u)
            .allowsHitTesting(false)
        }
    }

    // MARK: - Pieces

    private func text(_ words: String, _ size: CGFloat, _ alpha: Double = 1, lines: Int = 1) -> some View {
        Text(verbatim: words)
            .font(firmware.font(size: size))
            .foregroundStyle(.white.opacity(alpha))
            .lineLimit(lines)
            .xmbShadow()
    }

    /// A pad hint that takes a touch too, a finger's worth round the words.
    private func hint(_ glyph: String, _ colour: Color, _ words: String, u: CGFloat, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Text(verbatim: glyph).font(.system(size: 16 * u)).foregroundStyle(colour)
                text(words, 16 * u, 0.9)
            }
            .padding(.horizontal, 20 * u)
            .padding(.vertical, 14 * u)
            .xmbTouchTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: words.xmbHintWord))
    }
}

extension XmbNowPlayingView where Visualizer == XmbVisualizerView {
    init(panel: XmbNowPlayingPanel, firmware: XmbFirmware) {
        self.init(panel: panel, firmware: firmware) { full, accent in XmbVisualizerView(full: full, accent: accent) }
    }
}
