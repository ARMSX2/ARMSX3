// SPDX-License-Identifier: GPL-2.0-only
// What is installed in dev_hdd0, read from disk, never the library's cache. Foundation only, for the host test.

import Foundation

struct InstalledTitle: Identifiable, Sendable {
    let serial: String
    let title: String
    let category: String
    let appVersion: String?
    let size: Int64
    let dlcCount: Int
    let iconURL: URL?

    var id: String { serial }

    // "Update 1.04 · 2 DLC"; either part only when it applies, and empty when neither does.
    var detail: String {
        var parts: [String] = []
        if category == "GD", let version = appVersion, !version.isEmpty {
            let shown = Self.shortVersion(version)
            parts.append(String(localized: "Update \(shown)"))
        }
        if dlcCount > 0 { parts.append(String(localized: "\(dlcCount) DLC")) }
        return parts.joined(separator: " · ")
    }

    // "01.05" reads "1.05"; "1.05" and "10.00" are left alone.
    static func shortVersion(_ version: String) -> String {
        version.first == "0" && version.dropFirst().first?.isNumber == true ? String(version.dropFirst()) : version
    }
}

struct InstalledUpdate: Equatable, Sendable {
    let serial: String
    let folder: Bool
    let category: String
    let titleID: String
    let appVersion: String?
    let discVersion: String?
    let dlcCount: Int

    var isPSN: Bool { kind == "HG" }
    var hasUpdate: Bool { kind == "GD" && titleID == serial && appVersion?.isEmpty == false }
    var installedVersion: String? { (kind == "GD" || kind == "HG" ? appVersion : nil) ?? discVersion }

    private var kind: String { category.trimmingCharacters(in: .whitespaces) }
}

enum InstalledContent {
    // `ownContentIds` maps a serial to the content id of the game's own licence, which is not DLC.
    static func scan(gameDir: URL, exdataDir: URL, ownContentIds: [String: String]) -> [InstalledTitle] {
        let files = FileManager.default
        let folders = (try? files.contentsOfDirectory(at: gameDir, includingPropertiesForKeys: [.isDirectoryKey],
                                                       options: .skipsHiddenFiles)) ?? []
        let licences = (try? files.contentsOfDirectory(atPath: exdataDir.path)) ?? []
        var titles: [InstalledTitle] = []
        for folder in folders where (try? folder.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            guard let sfo = paramSfo(at: folder.appendingPathComponent("PARAM.SFO")) else { continue }
            let serial = folder.lastPathComponent
            let dlc = dlcCount(licenceNames: licences, serial: serial, own: ownContentIds[serial] ?? "")
            let icon = folder.appendingPathComponent("ICON0.PNG")
            let name = sfo.title?.trimmingCharacters(in: .whitespaces) ?? ""
            titles.append(InstalledTitle(serial: serial, title: name.isEmpty ? serial : name,
                                         category: sfo.strings["CATEGORY"] ?? "",
                                         appVersion: sfo.strings["APP_VER"]?.trimmingCharacters(in: .whitespaces),
                                         size: size(of: folder), dlcCount: dlc,
                                         iconURL: files.fileExists(atPath: icon.path) ? icon : nil))
        }
        return titles.sorted {
            let order = $0.title.localizedCaseInsensitiveCompare($1.title)
            return order == .orderedSame ? $0.serial < $1.serial : order == .orderedAscending
        }
    }

    static func dlcCount(licenceNames: [String], serial: String, own: String) -> Int {
        let own = own.uppercased()
        return licenceNames.filter { name in
            guard ["rap", "edat"].contains((name as NSString).pathExtension.lowercased()) else { return false }
            let stem = ((name as NSString).deletingPathExtension).uppercased()
            guard let dash = stem.firstIndex(of: "-") else { return false }
            let id = stem[stem.index(after: dash)...].prefix { $0 != "_" }
            return id == serial.uppercased() && stem != own
        }.count
    }

    static func update(gameDir: URL, exdataDir: URL, serial: String, discSFO: URL?) -> InstalledUpdate {
        let plain = !serial.isEmpty && !serial.contains("/") && serial != "." && serial != ".."
        let folder = gameDir.appendingPathComponent(serial, isDirectory: true)
        var isFolder: ObjCBool = false
        let exists = plain && FileManager.default.fileExists(atPath: folder.path, isDirectory: &isFolder) && isFolder.boolValue
        let sfo = exists ? paramSfo(at: folder.appendingPathComponent("PARAM.SFO")) : nil
        let disc = discSFO.flatMap(paramSfo(at:))
        let licences = (try? FileManager.default.contentsOfDirectory(atPath: exdataDir.path)) ?? []
        return InstalledUpdate(serial: serial, folder: exists, category: sfo?.strings["CATEGORY"] ?? "",
                               titleID: sfo?.strings["TITLE_ID"] ?? "",
                               appVersion: sfo?.strings["APP_VER"]?.trimmingCharacters(in: .whitespaces),
                               discVersion: disc?.strings["APP_VER"]?.trimmingCharacters(in: .whitespaces),
                               dlcCount: dlcCount(licenceNames: licences, serial: serial, own: ""))
    }

    // Only a GD folder whose own PARAM.SFO names this serial holds just an update; an HG folder is the game.
    static func canRemoveUpdate(_ state: InstalledUpdate, serial: String) -> Bool {
        state.folder && state.category.trimmingCharacters(in: .whitespaces) == "GD" && state.titleID == serial
    }

    private static func paramSfo(at url: URL) -> ParamSfo? {
        (try? Data(contentsOf: url)).flatMap { ParamSfo(data: $0) }
    }

    // A symbolic link is neither followed nor counted.
    private static func size(of folder: URL) -> Int64 {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let walk = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in walk {
            guard let values = try? file.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }
}
