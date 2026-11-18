// SPDX-License-Identifier: GPL-2.0-only
// Imports a picked PS3 theme off the main actor, from a staged copy, never the security-scoped original. The only slot writer.

import CryptoKit
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

enum ThemeImporter {
    static let sourceName = "source.p3t"
    static let sceneName = "scene.raf"
    private static let hashChunk = 1 << 20
    /// LibraryBackground.kt's MAX_SCENE_BYTES: the unpacked scene's cap.
    private static let maxSceneBytes = 256 << 20
    /// LibraryBackground.kt's MAX_OVERLAYS: see-through layers drawn over one slide.
    private static let maxOverlays = 4
    /// LibraryBackground.kt's SLIDE_QUALITY, 90, as ImageIO's 0 to 1 scale.
    private static let slideQuality = 0.9
    /// LibraryBackground.kt's STILL_WIDTH and STILL_HEIGHT: a live theme's opening frame.
    private static let stillWidth = 1920
    private static let stillHeight = 1080
    private static let liveStill = "still.jpg"

    private enum Animated {
        case live
        case slides([String], ThemeTiming)
    }

    nonisolated static func importTheme(from source: URL, into library: ThemeLibrary) -> Result<ThemeEntry, ThemeImportFailure> {
        library.sweep()
        guard let staging = try? library.makeStaging() else { return .failure(.writeFailed) }
        let outcome = stage(source, staging, library)
        if case .failure = outcome { try? FileManager.default.removeItem(at: staging) }
        return outcome
    }

    private static func stage(_ source: URL, _ staging: URL, _ library: ThemeLibrary) -> Result<ThemeEntry, ThemeImportFailure> {
        let fm = FileManager.default
        let copy = staging.appendingPathComponent(sourceName, isDirectory: false)
        let started = source.startAccessingSecurityScopedResource()
        let copied = (try? fm.copyItem(at: source, to: copy)) != nil
        if started { source.stopAccessingSecurityScopedResource() }
        guard copied, let digest = sha256(copy),
              let mapped = try? Data(contentsOf: copy, options: .alwaysMapped) else { return .failure(.readFailed) }

        let result = P3tTheme.read(DataByteSource(mapped))
        let still: String
        let kept: ThemeMeta.Source
        var slides: [String]?
        var timing: ThemeTiming?
        var scene: String?
        if let anim = result.anim, let animated = importAnimation(DataByteSource(mapped), anim, staging) {
            switch animated {
            case .live:
                still = liveStill
                kept = .live
                scene = sceneName
            case .slides(let baked, let shown):
                // One baked slide is a plain picture; two or more are a slideshow.
                still = baked[0]
                kept = .slides
                slides = baked
                timing = shown
            }
        } else {
            if let failure = result.failure { return .failure(.from(failure)) }
            guard let picture = result.picture else { return .failure(.damaged) }

            switch picture.source {
            case .hd, .sd:
                guard let jpeg = picture.jpeg, decodes(jpeg) else { return .failure(.damaged) }
                still = "still.jpg"
                kept = picture.source == .hd ? .hd : .sd
                do { try Data(jpeg).write(to: staging.appendingPathComponent(still, isDirectory: false)) } catch {
                    return .failure(.writeFailed)
                }
            case .preview:
                guard let rgba = picture.rgba else { return .failure(.damaged) }
                still = "still.png"
                kept = .preview
                guard let image = cgImage(rgba, picture.width, picture.height) else { return .failure(.damaged) }
                guard writePNG(image, to: staging.appendingPathComponent(still, isDirectory: false)) else {
                    return .failure(.writeFailed)
                }
            }
        }
        // The theme's own icons and sounds, kept beside it for the XMB.
        XmbThemeAssets.extract(DataByteSource(mapped), into: staging.appendingPathComponent(XmbThemeAssets.folderName, isDirectory: true))
        guard (try? fm.removeItem(at: copy)) != nil else { return .failure(.writeFailed) }

        let meta = ThemeMeta(version: 1,
                             name: ThemeLibrary.displayName(theme: result.name, fileName: source.lastPathComponent),
                             added: Date(), importer: ThemeMeta.importerLevel, digest: digest, source: kept, still: still,
                             slides: slides, timing: timing, scene: scene)
        guard let entry = try? library.install(staging: staging, meta: meta) else { return .failure(.writeFailed) }
        return .success(entry)
    }

    /// Nil when every step fails, and the caller takes the picture path; scene.raf goes on every return but the live one.
    private static func importAnimation(_ theme: ThemeByteSource, _ anim: P3tTheme.Anim,
                                        _ staging: URL) -> Animated? {
        let fm = FileManager.default
        let raw = staging.appendingPathComponent(sceneName, isDirectory: false)
        var keep = false
        defer { if !keep { try? fm.removeItem(at: raw) } }
        guard fm.createFile(atPath: raw.path, contents: nil),
              let handle = try? FileHandle(forWritingTo: raw) else { return nil }
        let unpacked = P3tAnimation.unpack(theme, offset: anim.offset, size: anim.size, cap: maxSceneBytes) {
            try handle.write(contentsOf: Data($0))
        }
        let closed = (try? handle.close()) != nil
        guard unpacked, closed, let mapped = try? Data(contentsOf: raw, options: .alwaysMapped) else { return nil }
        let scene = DataByteSource(mapped)
        if live(scene, into: staging) {
            keep = true
            return .live
        }
        guard let parsed = P3tAnimation.scene(scene), !parsed.layers.isEmpty,
              let slides = bake(scene: scene, layers: parsed.layers, into: staging) else { return nil }
        let t = parsed.timing
        return .slides(slides, ThemeTiming(interval: Double(t.interval), fade: Double(t.fade), zoom: Double(t.zoom),
                                           zoomInterval: Double(t.zoomInterval), zoomMove: Double(t.zoomMove)))
    }

    /// False, with nothing left behind, otherwise.
    private static func live(_ raf: ThemeByteSource, into staging: URL) -> Bool {
        autoreleasepool {
            guard let scene = RafScene.read(raf), ThemeScene.animates(scene),
                  let frame = ThemeSceneRenderer.still(scene: scene, raf: raf, width: stillWidth, height: stillHeight)
            else { return false }
            let url = staging.appendingPathComponent(liveStill, isDirectory: false)
            if writeJPEG(frame, to: url) { return true }
            try? FileManager.default.removeItem(at: url)
            return false
        }
    }

    /// Top layer down, so the layers above a slide decode before it and each texture once; nil deletes what it wrote.
    private static func bake(scene: ThemeByteSource, layers: [P3tAnimation.Layer], into folder: URL) -> [String]? {
        let width = layers.map { $0.texture.width }.max() ?? 0
        let height = layers.map { $0.texture.height }.max() ?? 0
        guard width > 0, height > 0 else { return nil }
        let area = CGRect(x: 0, y: 0, width: width, height: height)
        var above: [CGImage] = [] // bottom first
        var names = [String?](repeating: nil, count: layers.count)
        for i in layers.indices.reversed() {
            let baked: Bool = autoreleasepool {
                let texture = layers[i].texture
                guard let pixels = P3tAnimation.decode(scene, texture),
                      let picture = argbImage(pixels, texture.width, texture.height) else { return true }
                if !P3tAnimation.isSlide(texture, pixels) {
                    if above.count < maxOverlays { above.insert(picture, at: 0) }
                    return true
                }
                guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                              space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                                  | CGBitmapInfo.byteOrder32Little.rawValue) else { return false }
                context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
                context.fill(area)
                context.draw(picture, in: area)
                for layer in above { context.draw(layer, in: area) }
                let name = String(format: "slide_%03d.jpg", i)
                guard let frame = context.makeImage(),
                      writeJPEG(frame, to: folder.appendingPathComponent(name, isDirectory: false)) else { return false }
                names[i] = name
                return true
            }
            if !baked {
                for case let name? in names { try? FileManager.default.removeItem(at: folder.appendingPathComponent(name)) }
                return nil
            }
        }
        let baked = names.compactMap { $0 }
        return baked.isEmpty ? nil : baked
    }

    /// In little-endian memory each pixel is B, G, R, A, which byteOrder32Little with alpha first reads back as ARGB.
    private static func argbImage(_ argb: [UInt32], _ width: Int, _ height: Int) -> CGImage? {
        guard width > 0, height > 0, argb.count == width * height else { return nil }
        let data = argb.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue
                                                    | CGImageAlphaInfo.first.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    private static func writeJPEG(_ image: CGImage, to url: URL) -> Bool {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)
        else { return false }
        CGImageDestinationAddImage(destination, image,
                                   [kCGImageDestinationLossyCompressionQuality: slideQuality] as CFDictionary)
        return CGImageDestinationFinalize(destination)
    }

    private static func sha256(_ url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        do {
            // read(upToCount:) answers nil at the end of the file, and throws on a read error.
            while let chunk = try handle.read(upToCount: hashChunk), !chunk.isEmpty {
                hasher.update(data: chunk)
            }
        } catch {
            return nil
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// A JPEG is kept as stored only when ImageIO can open it and reports a width.
    private static func decodes(_ jpeg: [UInt8]) -> Bool {
        guard let source = CGImageSourceCreateWithData(Data(jpeg) as CFData, nil), CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int else { return false }
        return width > 0
    }

    private static func cgImage(_ rgba: [UInt8], _ width: Int, _ height: Int) -> CGImage? {
        guard width > 0, height > 0, rgba.count == width * height * 4,
              let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    private static func writePNG(_ image: CGImage, to url: URL) -> Bool {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
        else { return false }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination)
    }
}

@MainActor @Observable final class ThemeLibraryModel {
    static let shared = ThemeLibraryModel()

    let library = ThemeLibrary(root: BackgroundStorage.storageDirectory)
    private(set) var entries: [ThemeEntry] = []
    private(set) var importing: BackgroundPaneView.Slot?

    private init() {
        // A theme that was on screen live when the app last died plays its still, with no message.
        _ = library.takeArmedOnLaunch()
        reload()
        reconcileSlots()
    }

    func entry(named name: String) -> ThemeEntry? {
        entries.first { $0.folderName == name }
    }

    func reload() {
        entries = library.list()
    }

    /// Deletes nothing.
    func reconcileSlots() {
        let settings = SettingsStore.shared
        for slot in [BackgroundPaneView.Slot.primary, .landscape] {
            let asset = slot == .primary ? settings.backgroundPrimaryAsset : settings.backgroundLandscapeAsset
            if let asset, asset.kind == .theme, entry(named: asset.filename) == nil { setSlot(nil, slot) }
        }
    }

    /// Never deletes a theme's folder: only remove(_:) does. Choosing the theme a slot already holds changes nothing.
    func setSlot(_ asset: BackgroundAsset?, _ slot: BackgroundPaneView.Slot) {
        let settings = SettingsStore.shared
        let old = slot == .primary ? settings.backgroundPrimaryAsset : settings.backgroundLandscapeAsset
        // The player asked for this theme again, so it may play live again.
        if let asset, asset.kind == .theme { library.clearLiveBlock(asset.filename) }
        if let asset, asset.kind == .theme, old?.kind == .theme, old?.filename == asset.filename { return }
        if slot == .primary { settings.backgroundPrimaryAsset = asset } else { settings.backgroundLandscapeAsset = asset }
        if let old, old != asset, old.kind != .theme { BackgroundStorage.remove(old) }
    }

    func slots(using entry: ThemeEntry) -> [BackgroundPaneView.Slot] {
        let settings = SettingsStore.shared
        return [BackgroundPaneView.Slot.primary, .landscape].filter { slot in
            let asset = slot == .primary ? settings.backgroundPrimaryAsset : settings.backgroundLandscapeAsset
            return asset?.kind == .theme && asset?.filename == entry.folderName
        }
    }

    /// A failure is shown wherever the player is; while another import runs this returns nil.
    @discardableResult func importTheme(_ url: URL, into slot: BackgroundPaneView.Slot) async -> ThemeImportFailure? {
        guard importing == nil else { return nil }
        importing = slot
        defer { importing = nil }
        let library = self.library
        let outcome = await Task.detached(priority: .userInitiated) {
            ThemeImporter.importTheme(from: url, into: library)
        }.value
        switch outcome {
        case .success(let entry):
            library.clearLiveBlock(entry.folderName)
            reload()
            setSlot(BackgroundAsset(kind: .theme, filename: entry.folderName), slot)
            AccessibilityNotification.Announcement(String(localized: "\(entry.meta.name) imported.")).post()
            return nil
        case .failure(let failure):
            ControllerPrompt.shared.ask(failure.message, message: "", actions: [.ok])
            return failure
        }
    }

    /// On failure the row stays and the player is told.
    @discardableResult func remove(_ entry: ThemeEntry) -> Bool {
        do {
            try library.remove(named: entry.folderName)
        } catch {
            ControllerPrompt.shared.ask(ThemeLibrary.removeFailedMessage, message: "", actions: [.ok])
            return false
        }
        for slot in slots(using: entry) { setSlot(nil, slot) }
        reload()
        return true
    }

    nonisolated static func word(_ slot: BackgroundPaneView.Slot) -> String {
        slot == .primary ? "primary" : "landscape"
    }
}
