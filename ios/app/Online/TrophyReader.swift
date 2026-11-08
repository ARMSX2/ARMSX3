// SPDX-License-Identifier: GPL-2.0-only
// Reads a title's trophies read-only, because the core's TROPUSR loader can write the file. Foundation only, for the host test.

import Foundation

enum TrophyGrade {
    case platinum, gold, silver, bronze
}

struct Trophy: Identifiable {
    let id: Int
    let name: String
    let detail: String
    // Nil when neither TROPCONF's ttype nor TROPUSR's table 4 names a grade.
    let grade: TrophyGrade?
    let isSecret: Bool
    let unlocked: Bool
    let unlockedAt: Date?
    let iconURL: URL?

    func isRevealed(showHidden: Bool) -> Bool { unlocked || !isSecret || showHidden }
}

struct TrophySet {
    let id: String
    let title: String
    let detail: String
    let trophies: [Trophy]
    let iconURL: URL?
}

enum TrophyReader {
    static let usrMagic: UInt32 = 0x818F54AD

    static func sets(titleDir: URL, userDir: URL) -> [String] {
        let files = FileManager.default
        let names = (try? files.contentsOfDirectory(atPath: titleDir.appendingPathComponent("TROPDIR").path)) ?? []
        return names.filter { name in
            var folder: ObjCBool = false
            let source = titleDir.appendingPathComponent("TROPDIR").appendingPathComponent(name).path
            guard files.fileExists(atPath: source, isDirectory: &folder), folder.boolValue else { return false }
            let target = userDir.appendingPathComponent("trophy").appendingPathComponent(name).path
            return files.fileExists(atPath: target, isDirectory: &folder) && folder.boolValue
        }.sorted()
    }

    static func read(setDir: URL) -> TrophySet? {
        guard let confData = try? Data(contentsOf: setDir.appendingPathComponent("TROPCONF.SFM")),
              let conf = ConfParser.parse(confData) else { return nil }
        var usr = UsrState(grades: [:], entries: [:])
        let usrURL = setDir.appendingPathComponent("TROPUSR.DAT")
        if FileManager.default.fileExists(atPath: usrURL.path) {
            guard let data = try? Data(contentsOf: usrURL), let parsed = readUsr(data) else { return nil }
            usr = parsed
        }
        let trophies = conf.trophies.map { def -> Trophy in
            let entry = usr.entries[def.id]
            let unlocked = entry?.state == 1
            let icon = setDir.appendingPathComponent(String(format: "TROP%03d.PNG", def.id))
            return Trophy(id: def.id, name: def.name, detail: def.detail, grade: def.grade ?? usr.grades[def.id],
                          isSecret: def.secret, unlocked: unlocked,
                          unlockedAt: unlocked ? entry.flatMap { unlockDate(tick: $0.tick) } : nil,
                          iconURL: FileManager.default.fileExists(atPath: icon.path) ? icon : nil)
        }
        let icon = setDir.appendingPathComponent("ICON0.PNG")
        return TrophySet(id: setDir.lastPathComponent, title: conf.title, detail: conf.detail, trophies: trophies,
                         iconURL: FileManager.default.fileExists(atPath: icon.path) ? icon : nil)
    }

    // Android's accepted range; zero, or anything outside it, is no date.
    static func unlockDate(tick: UInt64) -> Date? {
        let epoch: UInt64 = 62_135_596_800 * 1_000_000
        guard tick > epoch else { return nil }
        let seconds = Double(tick - epoch) / 1_000_000
        guard (315_532_800...4_102_444_800).contains(seconds) else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    // MARK: TROPUSR.DAT

    private struct UsrState {
        var grades: [Int: TrophyGrade]
        var entries: [Int: (state: UInt32, tick: UInt64)]
    }

    // Every read is bounds-checked; any violation is nil for the whole file.
    private static func readUsr(_ data: Data) -> UsrState? {
        let bytes = [UInt8](data)
        func u32(_ at: Int) -> UInt32? {
            guard at >= 0, at + 4 <= bytes.count else { return nil }
            return bytes[at..<at + 4].reduce(0) { $0 << 8 | UInt32($1) }
        }
        func u64(_ at: Int) -> UInt64? {
            guard at >= 0, at + 8 <= bytes.count else { return nil }
            return bytes[at..<at + 8].reduce(0) { $0 << 8 | UInt64($1) }
        }
        guard u32(0) == usrMagic, let tableCount = u32(8), 0x30 + Int(tableCount) * 32 <= bytes.count else { return nil }
        var state = UsrState(grades: [:], entries: [:])
        for table in 0..<Int(tableCount) {
            let head = 0x30 + table * 32
            guard let type = u32(head), let size = u32(head + 4), let count = u32(head + 12),
                  let rawOffset = u64(head + 16), rawOffset <= UInt64(bytes.count) else { return nil }
            let needed: Int
            switch type {
            case 4: needed = 8
            case 6: needed = 32
            default: continue
            }
            // The table must fit before any record is read; this also keeps `index * stride` in range.
            let offset = Int(rawOffset), stride = 16 + Int(size)
            guard Int(size) >= needed, Int(count) <= (bytes.count - offset) / stride else { return nil }
            for index in 0..<Int(count) {
                let base = offset + index * stride
                guard base + stride <= bytes.count, let id = u32(base + 16), let value = u32(base + 20) else { return nil }
                if type == 4 {
                    // TROPUSRLoader::trophy_grade: 1 platinum, 2 gold, 3 silver, 4 bronze.
                    let grades: [UInt32: TrophyGrade] = [1: .platinum, 2: .gold, 3: .silver, 4: .bronze]
                    state.grades[Int(id)] = grades[value]
                } else {
                    guard let tick = u64(base + 16 + 24) else { return nil }
                    state.entries[Int(id)] = (state: value, tick: tick)
                }
            }
        }
        return state
    }

    // MARK: TROPCONF.SFM

    private struct TrophyDef {
        let id: Int
        let name: String
        let detail: String
        let grade: TrophyGrade?
        let secret: Bool
    }

    private final class ConfParser: NSObject, XMLParserDelegate {
        var title = ""
        var detail = ""
        var trophies: [TrophyDef] = []
        private var path: [String] = []
        private var current: (id: Int, grade: TrophyGrade?, secret: Bool)?
        private var name = ""
        private var detailText = ""
        private var text = ""
        private var rootOK = false

        static func parse(_ data: Data) -> (title: String, detail: String, trophies: [TrophyDef])? {
            let delegate = ConfParser()
            let parser = XMLParser(data: data)
            parser.delegate = delegate
            guard parser.parse(), delegate.rootOK else { return nil }
            return (delegate.title.trimmed, delegate.detail.trimmed, delegate.trophies.sorted { $0.id < $1.id })
        }

        func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?,
                    qualifiedName: String?, attributes: [String: String] = [:]) {
            if path.isEmpty { rootOK = element == "trophyconf" }
            path.append(element)
            text = ""
            if element == "trophy", let id = attributes["id"].flatMap({ Int($0.trimmed) }) {
                let grades: [Character: TrophyGrade] = ["P": .platinum, "G": .gold, "S": .silver, "B": .bronze]
                current = (id: id, grade: attributes["ttype"]?.uppercased().first.flatMap { grades[$0] },
                           secret: attributes["hidden"]?.lowercased().first == "y")
                name = ""
                detailText = ""
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

        func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
            path.removeLast()
            switch (element, current) {
            case ("title-name", nil): title = text
            case ("title-detail", nil): detail = text
            case ("name", .some): name = text
            case ("detail", .some): detailText = text
            case ("trophy", .some(let trophy)):
                trophies.append(TrophyDef(id: trophy.id, name: name.trimmed, detail: detailText.trimmed,
                                          grade: trophy.grade, secret: trophy.secret))
                current = nil
            default: break
            }
            text = ""
        }
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
