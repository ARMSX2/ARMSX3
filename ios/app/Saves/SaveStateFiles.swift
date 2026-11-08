// SPDX-License-Identifier: GPL-2.0-only
// Save-state files for one title, in Android's layout so states move between the two apps. Foundation only, for the host test.

import Foundation

enum SaveStateFiles {
    static let slotsFolder = "armsx3_slots"
    static let historyFile = "app-history.json"
    // Android's kThumbMaxEdge.
    static let thumbMaxEdge = 320

    // 2 since the USB bus returned: upstream saves its handler thread, so older states read differently. The app refuses older.
    static let formatEpoch = 2
    // A state with no record that is at least this new was made by this build.
    static let epochSinceKey = "armsx3.savestate.epoch2Since"

    // The size and modification second tie the record to the file the save just wrote, never a later one.
    static func recordEpoch(fileSlot: Int, in slots: URL) throws {
        try FileManager.default.createDirectory(at: slots, withIntermediateDirectories: true)
        guard let state = files(in: slots).filter({ slotNumber($0.name) == fileSlot }).max(by: { $0.modified < $1.modified })
        else { throw CocoaError(.fileNoSuchFile) }
        try "\(formatEpoch) \(state.size) \(wholeSeconds(state.modified))"
            .write(to: slots.appendingPathComponent("slot\(fileSlot).epoch"), atomically: true, encoding: .utf8)
    }

    // shortcut: with no matching record the date decides, which a reset date can fool; a marker inside the file would fix it.
    static func epoch(ofStateAt url: URL, since: Date?) -> Int {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let modified = attributes?[.modificationDate] as? Date
        let folder = url.deletingLastPathComponent()
        if folder.lastPathComponent == slotsFolder, let slot = slotNumber(url.lastPathComponent),
           let size = (attributes?[.size] as? NSNumber)?.int64Value, let modified,
           let text = try? String(contentsOf: folder.appendingPathComponent("slot\(slot).epoch"), encoding: .utf8) {
            let fields = text.split(whereSeparator: { $0.isWhitespace }).map { Int64($0) }
            if fields.count == 3, let value = fields[0], fields[1] == size, fields[2] == wholeSeconds(modified) {
                return Int(value)
            }
        }
        guard let since, let modified else { return 1 }
        return modified >= since ? formatEpoch : 1
    }

    private static func wholeSeconds(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970.rounded(.down)) }

    // UI slot to the ABI's file slot, and back; nil outside the layout.
    static func fileSlot(forUISlot slot: Int) -> Int? {
        switch slot {
        case 0: return 11
        case -2: return 10
        case 1...10: return slot - 1
        default: return nil
        }
    }

    static func uiSlot(forFileSlot slot: Int) -> Int? {
        switch slot {
        case 11: return 0
        case 10: return -2
        case 0...9: return slot + 1
        default: return nil
        }
    }

    struct Entry: Equatable {
        let url: URL
        // Nil for a PS-menu state.
        let fileSlot: Int?
        let size: Int64
        let modified: Date
        let thumbnail: URL?
    }

    struct Listing: Equatable {
        var slots: [Int: Entry] = [:]
        var psMenu: [Entry] = []
    }

    // Two spellings of one slot resolve to the newer file.
    static func scan(directory: URL, appHistory: Set<String>) -> Listing {
        var listing = Listing()
        let slots = directory.appendingPathComponent(slotsFolder, isDirectory: true)
        for (name, url, size, modified) in files(in: slots) {
            guard let slot = slotNumber(name) else { continue }
            if let kept = listing.slots[slot], kept.modified >= modified { continue }
            let thumb = slots.appendingPathComponent("slot\(slot).thumb")
            listing.slots[slot] = Entry(url: url, fileSlot: slot, size: size, modified: modified,
                                        thumbnail: FileManager.default.fileExists(atPath: thumb.path) ? thumb : nil)
        }
        let serial = directory.lastPathComponent
        listing.psMenu = files(in: directory)
            .filter { isHistoryName($0.name, serial: serial) && !appHistory.contains($0.name) }
            .map { Entry(url: $0.url, fileSlot: nil, size: $0.size, modified: $0.modified, thumbnail: nil) }
            .sorted { $0.modified != $1.modified ? $0.modified > $1.modified : $0.url.lastPathComponent > $1.url.lastPathComponent }
        return listing
    }

    static func appHistory(in slotsDirectory: URL) -> Set<String> {
        guard let data = try? Data(contentsOf: slotsDirectory.appendingPathComponent(historyFile)),
              let names = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return Set(names)
    }

    // shortcut: the list only grows; the core prunes its history files, so stale names cost a few bytes.
    static func recordAppHistory(name: String, in slotsDirectory: URL) throws {
        var names = appHistory(in: slotsDirectory)
        guard !name.isEmpty, names.insert(name).inserted else { return }
        try FileManager.default.createDirectory(at: slotsDirectory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(names.sorted())
        try data.write(to: slotsDirectory.appendingPathComponent(historyFile), options: .atomic)
    }

    // Anything else is nil, so a crafted file can neither oversize an image nor read past its end.
    static func decodeThumbnail(_ data: Data) -> (width: Int, height: Int, rgba: Data)? {
        let bytes = [UInt8](data)
        guard bytes.count >= 12, bytes[0..<4].elementsEqual("AX3T".utf8) else { return nil }
        func u32(_ at: Int) -> Int { (0..<4).reduce(0) { $0 | Int(bytes[at + $1]) << (8 * $1) } }
        let width = u32(4), height = u32(8)
        guard (1...thumbMaxEdge).contains(width), (1...thumbMaxEdge).contains(height),
              bytes.count == 12 + width * height * 4 else { return nil }
        return (width, height, Data(bytes[12...]))
    }

    // The `.partial-slot<N>` temporary a save copies through never matches.
    private static func slotNumber(_ name: String) -> Int? {
        guard name.hasPrefix("slot"), let base = stateBase(name) else { return nil }
        let digits = base.dropFirst(4)
        guard !digits.isEmpty, digits.allSatisfy(\.isASCII), let n = Int(digits), String(n) == digits,
              (0...11).contains(n) else { return nil }
        return n
    }

    // The core's own rule for its history files, in savestate_utils.cpp.
    private static func isHistoryName(_ name: String, serial: String) -> Bool {
        guard name.hasPrefix(serial + "_"), let base = stateBase(name),
              let underscore = base.lastIndex(of: "_") else { return false }
        let digits = base[base.index(after: underscore)...]
        return !digits.isEmpty && digits.allSatisfy { $0 >= "0" && $0 <= "9" }
    }

    private static func stateBase(_ name: String) -> Substring? {
        for suffix in [".SAVESTAT", ".SAVESTAT.zst", ".SAVESTAT.gz"] where name.hasSuffix(suffix) {
            return name.dropLast(suffix.count)
        }
        return nil
    }

    private static func files(in directory: URL) -> [(name: String, url: URL, size: Int64, modified: Date)] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else { return [] }
        return urls.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true,
                  let modified = values.contentModificationDate else { return nil }
            return (url.lastPathComponent, url, Int64(values.fileSize ?? 0), modified)
        }
    }
}
