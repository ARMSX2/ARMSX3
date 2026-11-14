// SPDX-License-Identifier: GPL-2.0-only
// A PS3 theme's XMB icons and system sounds, taken out at import and used in place of the firmware's, after Android's XmbTheme.kt.

import CoreGraphics
import Foundation
import ImageIO

/// What a theme carries for the XMB and where it is kept: icons/<id>.png, sounds/<id>.wav and the theme's own icon.png
/// in an `xmb` folder beside its background, PNG and WAV so nothing is decoded again to show or play them. Every
/// offset in the .p3t is checked, and an icon or sound that does not read is left out.
enum XmbThemeAssets {
    static let folderName = "xmb"

    /// An icon or system sound: `kind` "icon" or "se", its `id`, and its data as (offset, size) in the file: one
    /// zlib-packed GIM for an icon, a VAG per channel (left, then right) for a sound. The theme's own icon is the icon
    /// "theme". `unpacked` is the size an icon says it inflates to, or 0.
    struct Asset {
        let kind: String
        let id: String
        let parts: [(offset: Int, size: Int)]
        let unpacked: Int
    }

    private static let MAGIC = 0x50335446 // "P3TF"
    private static let HEADER_BYTES = 56
    private static let ELEMENT_BYTES = 28
    private static let ATTRIBUTE_BYTES = 16
    private static let TYPE_INT = 1
    private static let TYPE_FILE = 6
    private static let TYPE_ID = 7
    private static let MAX_TABLE_BYTES = 16 << 20
    private static let MAX_NAME_BYTES = 256
    private static let MAX_ASSET_BYTES = 8 << 20
    private static let MAX_ICON_BYTES = 8 << 20

    /// A theme's icon ids, as the console names them in a theme, against the firmware icons they stand in for. Taken
    /// from real themes' icon tables; the console's own theme has no PlayStation Network icon to replace.
    static let icons: [String: [String]] = [
        "icon_user": ["xmb_ingame/tex_user"],
        "icon_setting": ["xmb_ingame/tex_sysconf"],
        "icon_photo": ["xmb_ingame/tex_photo"],
        "icon_music": ["xmb_ingame/tex_music"],
        "icon_video": ["xmb_ingame/tex_video"],
        "icon_tv": ["xmb_ingame/tex_tv"],
        "icon_game": ["xmb_ingame/tex_game"],
        "icon_network": ["xmb_ingame/tex_network"],
        "icon_friend": ["xmb_ingame/tex_friend"],
        "icon_poweroff": ["explore_category_user/tex_power_off"],
        "icon_newuser": ["explore_category_user/item_tex_NewUser"],
        "icon_update": ["explore_category_sysconf/tex_update"],
        "icon_game_setting": ["explore_category_sysconf/tex_game"],
        "icon_bdvd_setting": ["explore_category_sysconf/tex_bddvd"],
        "icon_music_setting": ["explore_category_sysconf/tex_music"],
        "icon_chat_setting": ["explore_category_sysconf/tex_chat"],
        "icon_system_setting": ["explore_category_sysconf/tex_console"],
        "icon_theme_setting": ["explore_category_sysconf/tex_thema"],
        "icon_datetime_setting": ["explore_category_sysconf/tex_datetime"],
        "icon_powersave_setting": ["explore_category_sysconf/tex_power"],
        "icon_accessory": ["explore_category_sysconf/tex_pd"],
        "icon_printer_setting": ["explore_category_sysconf/tex_printer"],
        "icon_display_setting": ["explore_category_sysconf/tex_display"],
        "icon_sound_setting": ["explore_category_sysconf/tex_sound"],
        "icon_security_setting": ["explore_category_sysconf/tex_security"],
        "icon_remoteplay_setting": ["explore_category_sysconf/tex_remote"],
        "icon_network_setting": ["explore_category_sysconf/tex_network"],
        "icon_setting_item": ["explore_category_sysconf/tex_sett"],
        "icon_savedata": ["explore_category_game/item_tex_ps3util"],
        // Game Data Utility's: the PKG/Data Manager stands where it was.
        "icon_gamedata": ["explore_category_game/item_tex_disc_install"],
        "icon_trophy": ["explore_category_psn/item_tex_trophy"],
        "icon_browser": ["explore_category_network/tex_browser"],
        "icon_remoteplay": ["explore_category_network/tex_premo"],
        "icon_onlinemanual": ["explore_category_network/tex_onlinemanual"],
        "icon_inet_search": ["explore_category_network/tex_kensaku"],
        "icon_download": ["explore_category_network/tex_dl_manage"],
        "icon_accountmanage": ["explore_category_psn/item_tex_account"],
        "icon_addfriend": ["explore_category_friend/item_tex_NewFriend"],
        "icon_playermet": ["explore_category_friend/item_tex_Players"],
        "icon_photo_default": ["explore_category_photo/photo_tex_default"],
        "icon_photo_album_default": ["explore_category_photo/photo_tex_album_default"],
        "icon_music_default": ["explore_category_music/music_tex_default"],
        "icon_music_album_default": ["explore_category_music/music_tex_album_default"],
        "icon_video_default": ["explore_category_video/video_tex_default"],
    ]

    /// A theme's system sounds against the firmware's, which they replace.
    static let sounds: [String: [String]] = [
        "se_cursor": ["snd_cursor", "snd_category_decide"],
        "se_decide": ["snd_decide"],
        "se_cancel": ["snd_cancel"],
        "se_optionmenu": ["snd_option"],
        "se_system_ok": ["snd_system_ok"],
    ]

    /// The kept files in `folder` (a theme's `xmb` folder) by what they replace: icons by "rco/name", sounds by the
    /// firmware's sound name.
    static func files(in folder: URL) -> (icons: [String: URL], sounds: [String: URL]) {
        func listed(_ sub: String, _ ext: String, _ table: [String: [String]]) -> [String: URL] {
            let dir = folder.appendingPathComponent(sub, isDirectory: true)
            let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
            var found: [String: URL] = [:]
            for name in names where name.hasSuffix("." + ext) {
                let file = dir.appendingPathComponent(name, isDirectory: false)
                guard isFile(file) else { continue }
                table[String(name.dropLast(ext.count + 1))]?.forEach { found[$0] = file }
            }
            return found
        }
        return (listed("icons", "png", icons), listed("sounds", "wav", sounds))
    }

    /// How many icons and sounds were kept in `folder`, or nil when none were ever read for that theme.
    static func counts(in folder: URL) -> (icons: Int, sounds: Int)? {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: folder.path, isDirectory: &isDir), isDir.boolValue else { return nil }
        let count = { (sub: String) in
            (try? FileManager.default.contentsOfDirectory(atPath: folder.appendingPathComponent(sub).path).count) ?? 0
        }
        return (count("icons"), count("sounds"))
    }

    /// Takes the theme's icons and sounds out into `folder`, which is made even when the theme has none, to say it was
    /// read. True when anything was taken out. Blocking.
    @discardableResult static func extract(_ theme: ThemeByteSource, into folder: URL) -> Bool {
        let fm = FileManager.default
        guard (try? fm.createDirectory(at: folder, withIntermediateDirectories: true)) != nil else { return false }
        var any = false
        for asset in assets(theme) {
            let name = String(asset.id.filter { $0.isLetter || ($0.isASCII && $0.isNumber) || $0 == "_" }.prefix(64))
            if name.isEmpty { continue }
            switch asset.kind {
            case "icon":
                guard let gim = iconGim(theme, asset), let image = GimImage(gim) else { continue }
                let out = asset.id == "theme" ? folder.appendingPathComponent("icon.png")
                    : folder.appendingPathComponent("icons", isDirectory: true).appendingPathComponent(name + ".png")
                if writePNG(image, to: out) { any = true }
            case "se":
                let channels = asset.parts.map { part in (try? theme.read(part.offset, part.size)).flatMap(Vag.decode) }
                if channels.isEmpty || channels.contains(where: { $0 == nil }) { continue }
                let out = folder.appendingPathComponent("sounds", isDirectory: true).appendingPathComponent(name + ".wav")
                guard let wav = Vag.wav(channels.compactMap { $0 }),
                      (try? fm.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)) != nil,
                      (try? wav.write(to: out, options: .atomic)) != nil else { continue }
                any = true
            default:
                continue
            }
        }
        return any
    }

    /// The icons and system sounds `src` carries for the XMB; empty when it is damaged.
    static func assets(_ src: ThemeByteSource) -> [Asset] {
        (try? parseAssets(src)) ?? []
    }

    /// An icon's GIM, inflated, and no larger than it says it is. Nil when it will not.
    static func iconGim(_ src: ThemeByteSource, _ asset: Asset) -> [UInt8]? {
        guard let part = asset.parts.first else { return nil }
        let cap = (1 ... MAX_ICON_BYTES).contains(asset.unpacked) ? asset.unpacked : MAX_ICON_BYTES
        return try? ThemeZlib.inflate(src.read(part.offset, part.size), cap: cap)
    }

    private static func parseAssets(_ src: ThemeByteSource) throws -> [Asset] {
        if src.size < HEADER_BYTES { return [] }
        let header = ByteReader(try src.read(0, HEADER_BYTES))
        if try header.be32(0) != MAGIC { return [] }
        var tables: [(offset: Int, size: Int)] = []
        for i in 0 ..< 6 {
            let offset = try header.be32(8 + i * 8)
            let size = try header.be32(12 + i * 8)
            if offset > src.size || size > src.size - offset { throw ThemeDamaged() }
            tables.append((offset, size))
        }
        let elementTable = tables[0]
        let idTable = tables[1]
        let stringTable = tables[2]
        let fileTable = tables[5]
        if elementTable.size > MAX_TABLE_BYTES || idTable.size > MAX_TABLE_BYTES || stringTable.size > MAX_TABLE_BYTES {
            throw ThemeDamaged()
        }
        let elements = ByteReader(try src.read(elementTable.offset, elementTable.size))
        let ids = ByteReader(try src.read(idTable.offset, idTable.size))
        let strings = ByteReader(try src.read(stringTable.offset, stringTable.size))

        func file(_ offset: Int, _ size: Int) -> (offset: Int, size: Int)? {
            if offset > fileTable.size || size > fileTable.size - offset || size > MAX_ASSET_BYTES || size < 4 { return nil }
            return (fileTable.offset + offset, size)
        }

        var out: [Asset] = []
        var pos = 0
        while pos + ELEMENT_BYTES <= elements.count {
            let name = try strings.cString(at: try elements.be32(pos), limit: MAX_NAME_BYTES)
            let count = try elements.be32(pos + 4)
            if count > (elements.count - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES { throw ThemeDamaged() }
            if name == "icon" || name == "se" || name == "info" {
                var id: String?
                var data: (offset: Int, size: Int)?
                var left: (offset: Int, size: Int)?
                var right: (offset: Int, size: Int)?
                var unpacked = 0
                for i in 0 ..< count {
                    let at = pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES
                    let attribute = try strings.cString(at: try elements.be32(at), limit: MAX_NAME_BYTES)
                    let type = try elements.be32(at + 4)
                    let a = try elements.be32(at + 8)
                    let b = try elements.be32(at + 12)
                    if type == TYPE_ID && attribute == "id" {
                        id = try ids.cString(at: a + 4, limit: MAX_NAME_BYTES)
                    } else if type == TYPE_FILE && attribute == "src" && name == "icon" {
                        data = file(a, b)
                    } else if type == TYPE_FILE && attribute == "icon" && name == "info" {
                        data = file(a, b)
                    } else if type == TYPE_FILE && attribute == "left" {
                        left = file(a, b)
                    } else if type == TYPE_FILE && attribute == "right" {
                        right = file(a, b)
                    } else if type == TYPE_INT && (attribute == "size" || attribute == "iconsize") {
                        unpacked = a
                    }
                }
                switch name {
                case "icon":
                    if let id, let data { out.append(Asset(kind: "icon", id: id, parts: [data], unpacked: unpacked)) }
                case "info":
                    if let data { out.append(Asset(kind: "icon", id: "theme", parts: [data], unpacked: unpacked)) }
                default:
                    if let id, let left { out.append(Asset(kind: "se", id: id, parts: [left] + (right.map { [$0] } ?? []), unpacked: 0)) }
                }
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }
        return out
    }

    private static func writePNG(_ gim: GimImage, to url: URL) -> Bool {
        guard let provider = CGDataProvider(data: Data(gim.rgba) as CFData),
              let image = CGImage(width: gim.width, height: gim.height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: gim.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue), provider: provider,
                                  decode: nil, shouldInterpolate: false, intent: .defaultIntent),
              (try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)) != nil,
              let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else { return false }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination)
    }

    private static func isFile(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && !isDir.boolValue
    }
}

// The host suite compiles the readers above on the Mac, where there is no UIKit.
#if canImport(UIKit)
import UIKit

/// The theme the XMB shows, chosen in its own Theme Settings, and its icons and sounds in place of the firmware's.
/// Original is the firmware's.
@MainActor final class XmbTheme {
    static let shared = XmbTheme()

    private var applied: String?
    private var icons: [String: URL] = [:]
    private var sounds: [String: URL] = [:]
    private var images: [URL: UIImage] = [:]
    private var undecodable: Set<URL> = []

    /// The theme in use, or nil for Original. Reading it in a view follows the settings that choose it.
    var entry: ThemeEntry? {
        SettingsStore.shared.xmbTheme.flatMap { ThemeLibraryModel.shared.entry(named: $0) }
    }

    /// The theme's picture for the firmware icon `rco`/`name`, or nil.
    func image(_ rco: String, _ name: String) -> UIImage? {
        refresh()
        guard let file = icons[rco + "/" + name], !undecodable.contains(file) else { return nil }
        if let hit = images[file] { return hit }
        // A kept icon is a small PNG, so it is read on the main actor as the firmware's are.
        guard let image = UIImage(contentsOfFile: file.path) else {
            undecodable.insert(file)
            return nil
        }
        images[file] = image
        return image
    }

    /// The theme's WAV for the firmware's sound `name` (snd_cursor), or nil.
    func sound(_ name: String) -> URL? {
        refresh()
        return sounds[name]
    }

    /// How many icons and sounds were kept with `entry`, or nil for a theme imported before they were read.
    func assetCounts(_ entry: ThemeEntry) -> (icons: Int, sounds: Int)? {
        XmbThemeAssets.counts(in: entry.folder.appendingPathComponent(XmbThemeAssets.folderName, isDirectory: true))
    }

    /// A theme imported before its icons and sounds were read: it brings its background alone until imported again.
    func lacksAssets(_ entry: ThemeEntry) -> Bool { assetCounts(entry) == nil }

    /// A theme's own icon, when it has one.
    func iconOf(_ entry: ThemeEntry) -> URL? {
        let file = entry.folder.appendingPathComponent(XmbThemeAssets.folderName, isDirectory: true).appendingPathComponent("icon.png")
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    /// Reads the kept files again when the theme in use changed, or its files came with an import of it again.
    private func refresh() {
        let folder = entry?.folder.appendingPathComponent(XmbThemeAssets.folderName, isDirectory: true)
        var isDir: ObjCBool = false
        let kept = folder.map { FileManager.default.fileExists(atPath: $0.path, isDirectory: &isDir) && isDir.boolValue } ?? false
        let key = (folder?.path ?? "") + "|" + String(kept)
        guard key != applied else { return }
        applied = key
        let found = kept ? folder.map(XmbThemeAssets.files(in:)) : nil
        icons = found?.icons ?? [:]
        sounds = found?.sounds ?? [:]
        images = [:]
        undecodable = []
    }
}
#endif
