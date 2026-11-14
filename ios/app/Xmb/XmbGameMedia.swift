// SPDX-License-Identifier: GPL-2.0-only
// The focused game's own media in the XMB: SND0 music, the animated ICON1 and a disc image's PIC1, ported from Android's XmbMedia.kt.

import AVFoundation
import CoreGraphics
import os
import UIKit

/// What the console does with the game under the cursor: after a moment its music fades in and loops, and its ICON1 plays
/// in place of its icon. The home says which game is focused; the rest follows from that.
@MainActor @Observable final class XmbGameMedia {
    static let shared = XmbGameMedia()

    /// True while a game's music plays; the XMB's own music holds still until it ends.
    private(set) var previewing = false
    private var iconOwner: String?
    private var iconFrame: CGImage?
    private var backdropOwner: String?
    private var backdropPath: String?

    @ObservationIgnored private var entry: LibraryCache.Entry?
    @ObservationIgnored private var quiet = true
    @ObservationIgnored private var still = true
    @ObservationIgnored private var active = UIApplication.shared.applicationState == .active
    // The folder holding the focused game's PARAM.SFO and media, once looked up.
    @ObservationIgnored private var dir: URL?
    @ObservationIgnored private var lookup: Task<Void, Never>?
    @ObservationIgnored private var sound: Task<Void, Never>?
    @ObservationIgnored private var icon: Task<Void, Never>?
    @ObservationIgnored private var player: AVAudioPlayer?

    private init() {
        observe()
    }

    /// The game focused at the top of the Game column, or nil. `quiet` keeps its music off and `still` its icon; its PIC1 stays.
    func focus(_ entry: LibraryCache.Entry?, quiet: Bool, still: Bool) {
        let stillChanged = still != self.still
        self.still = still
        if entry != self.entry {
            self.entry = entry
            self.quiet = quiet
            lookUp()
            return
        }
        if quiet != self.quiet {
            self.quiet = quiet
            playSound(waits: true)
        }
        if stillChanged {
            if still { stopIcon() } else { playIcon() }
        }
    }

    /// The Game column's id for an entry's node.
    nonisolated static func nodeID(_ entry: LibraryCache.Entry) -> String { "game." + entry.bootPath }

    /// The frame of ICON1 to draw in place of this node's icon, or nil to draw its ICON0.
    func animatedIcon(_ nodeID: String) -> CGImage? { iconOwner == nodeID ? iconFrame : nil }

    /// This node's PIC1 once looked up, also for a disc image whose media had to be taken out first.
    func backdrop(_ nodeID: String) -> String? { backdropOwner == nodeID ? backdropPath : nil }

    private func lookUp() {
        lookup?.cancel()
        stopIcon()
        stopSound()
        dir = nil
        backdropOwner = nil
        backdropPath = nil
        let turn = Self.lookups.withLock { $0 += 1; return $0 }
        guard let entry else { return }
        lookup = Task {
            // Holding Down queues a look per game passed; only the one still focused copies a disc image's media.
            let found = await Self.run { Self.lookups.withLock { $0 } == turn ? Self.mediaDir(entry) : nil }
            guard !Task.isCancelled else { return }
            dir = found
            if let pic1 = Self.file(found, "PIC1.PNG") {
                backdropOwner = Self.nodeID(entry)
                backdropPath = pic1.path
            }
            playIcon()
            playSound(waits: true)
        }
    }

    private func playSound(waits: Bool) {
        stopSound()
        guard active, !quiet, let entry, let dir, SettingsStore.shared.xmbGameMusic else { return }
        let snd0 = Self.file(dir, "SND0.AT3")
        // Failing SND0, the animated icon's own sound, which starts with the icon.
        let pam = snd0 == nil && SettingsStore.shared.xmbAnimatedIcons ? Self.file(dir, "ICON1.PAM") : nil
        guard snd0 != nil || pam != nil else { return }
        let serial = entry.serial
        sound = Task {
            if waits { try? await Task.sleep(for: .milliseconds(snd0 != nil ? 1200 : 500)) }
            guard !Task.isCancelled else { return }
            let wav = await Self.run {
                snd0.flatMap { Self.decodedAudio($0, serial) } ?? pam.flatMap { Self.decodedIconAudio($0, serial) }
            }
            guard !Task.isCancelled, let wav, let player = try? AVAudioPlayer(contentsOf: wav) else { return }
            player.numberOfLoops = -1
            player.volume = 0
            XmbMedia.shared.shareSession()
            guard player.play() else { return }
            // Android adds 0.05 every 60 ms up to 0.85; the player ramps the same line itself.
            player.setVolume(0.85, fadeDuration: 0.06 * 17)
            self.player = player
            previewing = true
            XmbMedia.shared.startPreview()
        }
    }

    /// Headphones taken off: the game's music stops instead of moving to the speaker.
    func stopMusic() { stopSound() }

    private func stopSound() {
        sound?.cancel()
        sound = nil
        player?.stop()
        player = nil
        if previewing { XmbMedia.shared.endPreview() }
        previewing = false
    }

    private func playIcon() {
        stopIcon()
        guard active, !still, let entry, SettingsStore.shared.xmbAnimatedIcons, let pam = Self.file(dir, "ICON1.PAM") else { return }
        let id = Self.nodeID(entry)
        icon = Task.detached(priority: .userInitiated) { [weak self] in
            // The console waits a beat before the icon moves.
            try? await Task.sleep(for: .milliseconds(500))
            await Self.play(pam) { frame in await self?.show(frame, id) }
        }
    }

    private func show(_ frame: CGImage, _ id: String) {
        guard icon != nil, entry.map(Self.nodeID) == id else { return }
        iconOwner = id
        iconFrame = frame
    }

    private func stopIcon() {
        icon?.cancel()
        icon = nil
        iconOwner = nil
        iconFrame = nil
    }

    // Plays only while the app is in front; back in front, the music starts over at once and the icon after its beat.
    private func setActive(_ on: Bool) {
        guard on != active else { return }
        active = on
        if on {
            playIcon()
            playSound(waits: false)
        } else {
            stopIcon()
            stopSound()
        }
    }

    private func observe() {
        let centre = NotificationCenter.default
        centre.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.setActive(false) }
        }
        centre.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.setActive(true) }
        }
        // A call banner or an alarm pauses the music without the app leaving the front, so nothing else starts it again.
        centre.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.ended.rawValue else { return }
            Task { @MainActor in
                guard let self, self.previewing else { return }
                self.player?.play()
            }
        }
    }

    // Frames paced by their own times, looping from the start, as Android's XmbAnimatedIcon does.
    nonisolated private static func play(_ pam: URL, show: @escaping (CGImage) async -> Void) async {
        guard var decoder = Rpcs3Bridge.xmbOpenIcon(atPath: pam.path) else { return }
        let rgba = NSMutableData()
        var start = ContinuousClock.now
        var first: Int64 = -1
        var frames = 0
        while !Task.isCancelled {
            let ms = decoder.readFrame(into: rgba)
            if ms < 0 {
                // A one-frame icon stays as it is; reopening it would read the file again every few milliseconds.
                guard frames > 1, let again = Rpcs3Bridge.xmbOpenIcon(atPath: pam.path) else { return }
                decoder = again
                start = .now
                first = -1
                frames = 0
                continue
            }
            if first < 0 { first = ms }
            let due = start + .milliseconds(ms - first)
            if due > .now { try? await Task.sleep(until: due, clock: .continuous) }
            guard !Task.isCancelled, let image = image(rgba, width: decoder.width, height: decoder.height) else { return }
            frames += 1
            await show(image)
        }
    }

    nonisolated private static func image(_ rgba: NSMutableData, width: Int, height: Int) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(bytes: rgba.bytes, count: rgba.length) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    // One at a time, so two looks at the same disc image never extract into the same folder.
    nonisolated private static let queue = DispatchQueue(label: "xmb.game-media", qos: .utility)
    // Counts the looks, so a queued one knows it is stale.
    nonisolated private static let lookups = OSAllocatedUnfairLock(initialState: 0)

    nonisolated private static func run<T>(_ work: @escaping () -> T) async -> T {
        await withCheckedContinuation { done in queue.async { done.resume(returning: work()) } }
    }

    nonisolated private static func file(_ dir: URL?, _ name: String) -> URL? {
        guard let url = dir?.appendingPathComponent(name) else { return nil }
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    nonisolated private static func size(_ url: URL) -> Int {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
    }

    nonisolated private static func safe(_ serial: String) -> String {
        String(serial.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) && $0.isASCII || $0 == "_" || $0 == "-" })
    }

    nonisolated private static var caches: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0] }

    /// The folder holding the game's PARAM.SFO and media: a disc in folder form, an installed title's own folder, or for a
    /// disc image the media taken out of it into the caches.
    nonisolated private static func mediaDir(_ entry: LibraryCache.Entry) -> URL? {
        if entry.isISO { return discMedia(entry) }
        var top = URL(fileURLWithPath: entry.bootPath)
        // The folder itself, or its EBOOT.BIN two folders down.
        if top.lastPathComponent.caseInsensitiveCompare("EBOOT.BIN") == .orderedSame {
            top = top.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        }
        for dir in [top.appendingPathComponent("PS3_GAME"), top] where file(dir, "PARAM.SFO") != nil {
            return dir
        }
        // An installed title keeps its media beside its ICON0.
        return entry.iconPath.map { URL(fileURLWithPath: $0).deletingLastPathComponent() }
    }

    /// A disc image's media, copied out once by the core and checked to be this game's; an image with none is remembered.
    nonisolated private static func discMedia(_ entry: LibraryCache.Entry) -> URL? {
        let serial = safe(entry.serial)
        guard !serial.isEmpty else { return nil }
        let fm = FileManager.default
        let root = caches.appendingPathComponent("xmb-media", isDirectory: true)
        let dir = root.appendingPathComponent(serial, isDirectory: true)
        let hasMedia = { ["PIC1.PNG", "SND0.AT3", "ICON1.PAM"].contains { file(dir, $0) != nil } }
        if hasMedia() { return dir }
        let none = root.appendingPathComponent(serial + ".none")
        if fm.fileExists(atPath: none.path) { return nil }
        let part = root.appendingPathComponent(serial + ".part", isDirectory: true)
        try? fm.removeItem(at: part)
        guard (try? fm.createDirectory(at: part, withIntermediateDirectories: true)) != nil else { return nil }
        let result = Rpcs3Bridge.xmbCopyDiscMedia(fromImage: entry.bootPath, serial: entry.serial, toFolder: part.path)
        guard result == .copied else {
            try? fm.removeItem(at: part)
            // Not yet readable is asked again later, rather than the disc being marked as having nothing.
            if result == .absent { fm.createFile(atPath: none.path, contents: nil) }
            return nil
        }
        try? fm.removeItem(at: dir)
        guard (try? fm.moveItem(at: part, to: dir)) != nil else {
            try? fm.removeItem(at: part)
            return nil
        }
        return hasMedia() ? dir : nil
    }

    nonisolated private static func wav(_ source: URL, _ serial: String) -> URL {
        caches.appendingPathComponent("xmb-audio", isDirectory: true)
            .appendingPathComponent("\(safe(serial))-\(source.deletingPathExtension().lastPathComponent)-\(size(source)).wav")
    }

    /// SND0 decoded into a WAV the player can play, cached by game, name and size.
    nonisolated private static func decodedAudio(_ source: URL, _ serial: String) -> URL? {
        let out = wav(source, serial)
        if size(out) > 44 { return out }
        try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard Rpcs3Bridge.xmbDecodeAudio(atPath: source.path, toWAV: out.path), size(out) > 44 else { return nil }
        return out
    }

    /// The animated icon's own sound as a WAV; an icon without one is remembered. Some games have no SND0 and this is their music.
    nonisolated private static func decodedIconAudio(_ pam: URL, _ serial: String) -> URL? {
        let out = wav(pam, serial)
        if size(out) > 44 { return out }
        let none = out.appendingPathExtension("none")
        if FileManager.default.fileExists(atPath: none.path) { return nil }
        try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard Rpcs3Bridge.xmbDecodeIconAudio(atPath: pam.path, toWAV: out.path), size(out) > 44 else {
            FileManager.default.createFile(atPath: none.path, contents: nil)
            return nil
        }
        return out
    }
}
