// SPDX-License-Identifier: GPL-2.0-only
// The XMB's icons, words, font and sounds from the firmware the player installed, after Android's XmbFirmware.kt.

import AVFoundation
import CoreText
import SwiftUI
import UIKit

/// The firmware RCOs the XMB reads its words and icons from.
enum XmbRco {
    static let full = "explore_plugin_full"
    static let ingame = "xmb_ingame"
    static let game = "explore_category_game"
    static let sysconf = "explore_category_sysconf"
    static let friend = "explore_category_friend"
    static let user = "explore_category_user"
    static let psn = "explore_category_psn"
    static let photo = "explore_category_photo"
    static let music = "explore_category_music"
    static let video = "explore_category_video"
    static let network = "explore_category_network"
}

/// Where things are in dev_flash/vsh/resource: the category icons and words in xmb_ingame.rco, each column's item
/// icons and words in explore_category_<category>.rco, shared words in explore_plugin_full.rco, the system sounds in
/// system_plugin.rco, the in-game menu's words in impose_plugin.rco. Fonts are dev_flash/data/font/SCE-PS3-*.TTF. Without
/// firmware every lookup answers nil. A PS3 theme in use (XmbTheme) has its icons and sounds go first.
@MainActor @Observable final class XmbFirmware {
    static let shared = XmbFirmware()

    /// Bumped when the firmware is found or reloaded, so views read icons and words again.
    private(set) var generation = 0
    private(set) var present = false
    private(set) var fontName: String?
    /// The firmware's Latin2, Japanese, Korean and Chinese fonts behind Rodin, as Android chains them.
    private(set) var fallbackFontNames: [String] = []

    private var words: [String: [String: String]] = [:]
    private var sounds: [XmbSound: URL] = [:]
    // Caches filled while views draw, so reading them must not count as a change.
    @ObservationIgnored private var containers: [String: Rco] = [:]
    @ObservationIgnored private var images: [String: UIImage] = [:]
    @ObservationIgnored private var missing: Set<String> = []
    @ObservationIgnored private var players: [AVAudioPlayer] = []
    @ObservationIgnored private var fonts: [CGFloat: Font] = [:]
    @ObservationIgnored private var loading = false

    /// The words the XMB shows come from these, as on Android.
    nonisolated private static let wordSources = ["xmb_ingame", "explore_plugin_full", "explore_category_sysconf", "explore_category_game",
                                      "explore_category_user", "explore_category_psn", "explore_category_photo", "explore_category_music",
                                      "explore_category_video", "explore_category_network", "impose_plugin"]

    nonisolated static var devFlash: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rpcs3/dev_flash", isDirectory: true)
    }

    nonisolated private static var resource: URL { devFlash.appendingPathComponent("vsh/resource", isDirectory: true) }

    /// The console's number for the app's language, or nil for one the console never had.
    nonisolated static func consoleLanguage(_ code: String) -> Int? {
        ["ja": 0, "en": 1, "fr": 2, "es": 3, "de": 4, "it": 5, "nl": 6, "pt": 7, "ru": 8, "ko": 9, "zh-Hant": 10,
         "zh-Hans": 11, "fi": 12, "sv": 13, "da": 14, "nb": 15, "pl": 16, "pt-BR": 17, "en-GB": 18, "tr": 19][code]
    }

    /// Reads what the XMB shows once. Sounds are decoded to WAV files in Caches the first time. `again` reads it all
    /// once more, sounds decoded afresh, for firmware installed while the app runs.
    func load(again: Bool = false) async {
        guard !loading, again || !present else { return }
        loading = true
        defer { loading = false }
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        let language = Self.consoleLanguage(code) ?? 1
        let found = await Task.detached(priority: .utility) { Self.read(language: language, fresh: again) }.value
        guard let found else { return }
        words = found.words
        sounds = found.sounds
        fontName = found.fonts.first
        fallbackFontNames = Array(found.fonts.dropFirst())
        fonts = [:]
        containers = [:]
        images = [:]
        missing = []
        present = true
        generation += 1
    }

    private struct Found: Sendable {
        var words: [String: [String: String]]
        var sounds: [XmbSound: URL]
        /// Rodin first, then its fallbacks; empty without Rodin.
        var fonts: [String]
    }

    nonisolated private static func read(language: Int, fresh: Bool) -> Found? {
        guard FileManager.default.fileExists(atPath: resource.path) else { return nil }
        var words: [String: [String: String]] = [:]
        for name in wordSources {
            words[name] = Rco.open(resource.appendingPathComponent(name + ".rco"))?.strings(language) ?? [:]
        }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("xmb-sounds", isDirectory: true)
        // The clips are cached by name alone, so new firmware must not find the old ones.
        if fresh { try? FileManager.default.removeItem(at: cache) }
        try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        var sounds: [XmbSound: URL] = [:]
        let plugin = Rco.open(resource.appendingPathComponent("system_plugin.rco"))
        for sound in XmbSound.allCases {
            let wav = cache.appendingPathComponent(sound.rawValue + ".wav")
            if !FileManager.default.fileExists(atPath: wav.path) {
                guard let channels = plugin?.sound(sound.rawValue) else { continue }
                let pcm = channels.compactMap(Vag.decode)
                guard pcm.count == channels.count, let data = Vag.wav(pcm), (try? data.write(to: wav, options: .atomic)) != nil else {
                    continue
                }
            }
            sounds[sound] = wav
        }
        XmbBootClock.prepareBootSound()
        let rodin = registerFont("SCE-PS3-RD-R-LATIN.TTF")
        let fallbacks = rodin == nil ? [] : fallbackFonts.compactMap(registerFont)
        return Found(words: words, sounds: sounds, fonts: rodin.map { [$0] + fallbacks } ?? [])
    }

    /// Behind Rodin for titles in other scripts, as on Android; the system's own font comes after them.
    nonisolated private static let fallbackFonts = ["SCE-PS3-RD-R-LATIN2.TTF", "SCE-PS3-NR-R-JPN.TTF", "SCE-PS3-YG-R-KOR.TTF",
                                                    "SCE-PS3-DH-R-CGB.TTF"]

    /// A firmware font registered for this process only, by its PostScript name; nil when the file is not there or
    /// will not load.
    nonisolated private static func registerFont(_ file: String) -> String? {
        let url = devFlash.appendingPathComponent("data/font/" + file)
        guard let provider = CGDataProvider(url: url as CFURL), let font = CGFont(provider) else { return nil }
        var error: Unmanaged<CFError>?
        // A font registered earlier in this run fails again with a duplicate error, which still leaves it usable.
        _ = CTFontManagerRegisterGraphicsFont(font, &error)
        return font.postScriptName as String?
    }

    /// The firmware's words for `id` in `rco`, without the console's private-use button marks, or `fallback`.
    func text(_ rco: String, _ id: String, _ fallback: String) -> String {
        guard let raw = words[rco]?[id] else { return fallback }
        let cleaned = String(raw.unicodeScalars.filter { !(0xE000 ... 0xF8FF).contains($0.value) }.map(Character.init))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? fallback : cleaned
    }

    /// The theme's picture for `name` from `rco`, else the firmware's, decoded and cached for the run, or nil. A name
    /// ending in "#restart" is the restart icon made from that picture.
    func image(_ rco: String, _ name: String) -> UIImage? {
        if let themed = XmbTheme.shared.image(rco, name) { return themed }
        guard present else { return nil }
        let key = rco + "/" + name
        if let hit = images[key] { return hit }
        guard !missing.contains(key) else { return nil }
        // Icons are 128 px and decode in well under a millisecond, so this stays on the main actor.
        if containers[rco] == nil { containers[rco] = Rco.open(Self.resource.appendingPathComponent(rco + ".rco")) }
        guard let container = containers[rco],
              let gim = container.image(String(name.prefix { $0 != "#" })).flatMap(GimImage.init),
              let image = (name.hasSuffix("#restart") ? Self.restartIcon(from: gim).map { UIImage(cgImage: $0) } : nil) ?? Self.uiImage(gim) else {
            missing.insert(key)
            return nil
        }
        images[key] = image
        return image
    }

    nonisolated static func uiImage(_ gim: GimImage) -> UIImage? {
        guard let provider = CGDataProvider(data: Data(gim.rgba) as CFData),
              let cg = CGImage(width: gim.width, height: gim.height, bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: gim.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue), provider: provider,
                               decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Restart, which the firmware has no icon for, made from its Quit icon (a ring crossed out): the ring alone, opened
    /// at the top right with an arrowhead going round, in the ring's own shades. Nil when there is no ring to find.
    nonisolated static func restartIcon(from quit: GimImage) -> CGImage? {
        let w = quit.width, h = quit.height, px = quit.rgba
        func alpha(_ index: Int) -> UInt8 { px[index * 4 + 3] }
        let cx = Float(w) / 2, cy = Float(h) / 2
        // The ring's edges, along the middle row in from the left.
        let mid = (h / 2) * w
        guard let outer = (0 ..< w / 2).first(where: { alpha(mid + $0) > 96 }) else { return nil }
        var inner = outer
        while inner < w / 2 && alpha(mid + inner) > 96 { inner += 1 }
        let rOut = cx - Float(outer), rIn = cx - Float(inner)
        let thick = rOut - rIn
        guard thick >= 2 else { return nil }
        func at(_ r: Float, _ deg: Float) -> Int {
            let a = deg * .pi / 180
            return min(max(Int(cy + r * sin(a)), 0), h - 1) * w + min(max(Int(cx + r * cos(a)), 0), w - 1)
        }
        // Where the cross-bars meet the ring (opaque just inside it), so as to fill those with ring.
        let joints = (0 ..< 360).map { alpha(at(rIn - 2, Float($0))) > 96 }
        func nearJoint(_ deg: Float) -> Bool {
            let d = ((Int(deg) % 360) + 360) % 360
            return (-14 ... 14).contains { joints[(d + $0 + 360) % 360] }
        }
        let gapFrom: Float = -80, gapTo: Float = -22
        // Premultiplied, as the context the arrowhead is drawn in wants it.
        var out = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0 ..< h {
            for x in 0 ..< w {
                let dx = Float(x) + 0.5 - cx, dy = Float(y) + 0.5 - cy
                let r = (dx * dx + dy * dy).squareRoot()
                if r < rIn - 1.5 || r > rOut + 1.5 { continue }
                let deg = atan2(dy, dx) * 180 / .pi
                if (gapFrom ... gapTo).contains(deg) { continue }
                var source = y * w + x
                if nearJoint(deg) {
                    var d = deg + 40
                    // A picture that is joint all round would never end the search.
                    while nearJoint(d) && d < deg + 400 { d += 20 }
                    source = at(r, d)
                }
                let a = UInt16(alpha(source)), target = (y * w + x) * 4
                for c in 0 ..< 3 { out[target + c] = UInt8((UInt16(px[source * 4 + c]) * a + 127) / 255) }
                out[target + 3] = UInt8(a)
            }
        }
        // The arrowhead at the ring's end by the gap, going round clockwise.
        let t0 = gapFrom * .pi / 180, rm = (rIn + rOut) / 2
        let bx = cx + rm * cos(t0), by = cy + rm * sin(t0)
        let radX = cos(t0), radY = sin(t0)
        let half = thick * 1.25, len = thick * 1.9
        let outerEdge = CGPoint(x: CGFloat(bx + radX * half), y: CGFloat(by + radY * half))
        let innerEdge = CGPoint(x: CGFloat(bx - radX * half), y: CGFloat(by - radY * half))
        func opaque(_ index: Int) -> [CGFloat] { (0 ..< 3).map { CGFloat(px[index * 4 + $0]) / 255 } + [1] }
        let space = CGColorSpaceCreateDeviceRGB()
        guard let gradient = CGGradient(colorSpace: space, colorComponents: opaque(at(rm + thick * 0.3, -110)) + opaque(at(rm - thick * 0.3, -110)),
                                        locations: [0, 1], count: 2) else { return nil }
        return out.withUnsafeMutableBytes { buffer -> CGImage? in
            guard let context = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            // Top-down, as the pixels are.
            context.translateBy(x: 0, y: CGFloat(h))
            context.scaleBy(x: 1, y: -1)
            context.move(to: outerEdge)
            context.addLine(to: innerEdge)
            context.addLine(to: CGPoint(x: CGFloat(bx - radY * len), y: CGFloat(by + radX * len)))
            context.closePath()
            context.clip()
            context.drawLinearGradient(gradient, start: outerEdge, end: innerEdge, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            return context.makeImage()
        }
    }

    func font(size: CGFloat) -> Font {
        guard let fontName else { return .system(size: size) }
        if fallbackFontNames.isEmpty { return .custom(fontName, fixedSize: size) }
        if let hit = fonts[size] { return hit }
        let cascade = fallbackFontNames.map { UIFontDescriptor(name: $0, size: size) }
        let descriptor = UIFontDescriptor(name: fontName, size: size).addingAttributes([.cascadeList: cascade])
        let font = Font(UIFont(descriptor: descriptor, size: size) as CTFont)
        fonts[size] = font
        return font
    }

    /// The theme's sound, else the firmware's, else the app's own menu sound that Android plays in its place.
    func play(_ sound: XmbSound) {
        let percent = (UserDefaults.standard.object(forKey: SoundRouting.menuVolumeKey) as? NSNumber)?.intValue
            ?? SoundRouting.defaultMenuPercent
        // The app has no menu sounds switch of its own, so a menu volume of 0 is off.
        guard percent > 0,
              let url = XmbTheme.shared.sound(sound.rawValue) ?? sounds[sound] ?? Self.appSound(sound),
              let player = try? AVAudioPlayer(contentsOf: url) else { return }
        // The firmware's clips are mastered quiet, so they sit a little above the menu volume, as on Android.
        player.volume = min(Float(min(max(percent, 0), 100)) / 100 * 2.2, 1)
        players.removeAll { !$0.isPlaying }
        if players.count >= 4 { players.removeFirst().stop() }
        if player.play() { players.append(player) }
    }

    /// Android's own menu sound for each: NAV, SELECT or BACK, which are the player's imported snd_cursor, snd_decide
    /// and snd_cancel here. Android's popup sound for Triangle has no imported counterpart, and nothing ships with the app.
    nonisolated private static func appSound(_ sound: XmbSound) -> URL? {
        let name: String
        switch sound {
        case .cursor: name = "snd_cursor"
        case .decide, .systemOK: name = "snd_decide"
        case .cancel, .error: name = "snd_cancel"
        case .option: return nil
        }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rpcs3/sounds/\(name).wav")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}
