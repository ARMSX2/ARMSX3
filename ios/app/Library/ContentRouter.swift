// SPDX-License-Identifier: GPL-2.0-only
// What a picked file is, by its bytes, and the install order. Foundation only, for the host test; it never sees a path.

import Foundation

enum ContentKind: Equatable {
    case pup
    case pkg
    case pkgPart(base: String, index: Int)
    case rap
    case edat
    case unknown
}

enum ContentRouter {
    struct Item {
        let index: Int
        let name: String
        let kind: ContentKind
    }

    /// A raw split set is listed in `packages` by its part 00 only; the core opens the other parts beside it.
    struct Plan {
        var firmware: [Int] = []
        var packages: [Int] = []
        var coveredParts: [Int] = []
        var licences: [Int] = []
        var errors: [(index: Int, reason: String)] = []
    }

    static func sniff(header: Data, fileName: String, fileSize: Int64) -> ContentKind {
        let bytes = [UInt8](header.prefix(8))
        if bytes.starts(with: [0x53, 0x43, 0x45, 0x55, 0x46, 0, 0, 0]) { return .pup }   // SCEUF\0\0\0
        if bytes.starts(with: [0x7F, 0x50, 0x4B, 0x47]) { return .pkg }                // \x7FPKG
        if bytes.starts(with: [0x4E, 0x50, 0x44, 0x00]) { return .edat }               // NPD\0
        if fileSize == 16 { return .rap }
        if let part = splitPart(fileName), part.index > 0 { return .pkgPart(base: part.base, index: part.index) }
        return .unknown
    }

    static func splitPart(_ name: String) -> (base: String, index: Int)? {
        guard name.lowercased().hasSuffix(".pkg") else { return nil }
        let stem = name.dropLast(4)
        guard let underscore = stem.lastIndex(of: "_") else { return nil }
        let digits = stem[stem.index(after: underscore)...]
        guard (2...4).contains(digits.count), digits.allSatisfy({ $0.isASCII && $0.isNumber }),
              let index = Int(digits) else { return nil }
        return (String(stem[..<underscore]), index)
    }

    /// Android's NaturalOrder. Digit runs compare by length, then digit by digit, so no run is too long.
    static func naturalLess(_ a: String, _ b: String) -> Bool {
        let x = Array(a), y = Array(b)
        var i = 0, j = 0
        while i < x.count && j < y.count {
            if isDigit(x[i]) && isDigit(y[j]) {
                var ai = i, bj = j
                while ai < x.count && isDigit(x[ai]) { ai += 1 }
                while bj < y.count && isDigit(y[bj]) { bj += 1 }
                let da = x[i..<ai].drop { $0 == "0" }, db = y[j..<bj].drop { $0 == "0" }
                if da.count != db.count { return da.count < db.count }
                if !da.elementsEqual(db) { return da.lexicographicallyPrecedes(db) }
                i = ai
                j = bj
            } else {
                let ca = x[i].lowercased(), cb = y[j].lowercased()
                if ca != cb { return ca < cb }
                i += 1
                j += 1
            }
        }
        return x.count - i < y.count - j
    }

    private static func isDigit(_ c: Character) -> Bool { c.isASCII && c.isNumber }

    /// Licences go last because a licence unlocks content a package has to have written.
    static func plan(_ items: [Item]) -> Plan {
        var plan = Plan()
        let ordered = items.sorted { naturalLess($0.name, $1.name) }
        var heads: [String: Item] = [:]
        var parts: [String: [Item]] = [:]

        for item in ordered {
            switch item.kind {
            case .pup:
                plan.firmware.append(item.index)
            case .rap, .edat:
                plan.licences.append(item.index)
            case .pkgPart(let base, _):
                parts[base.lowercased(), default: []].append(item)
            case .pkg:
                if let head = splitPart(item.name), head.index == 0 {
                    heads[head.base.lowercased()] = item
                }
            case .unknown:
                plan.errors.append((item.index, String(localized: "not a PS3 file")))
            }
        }

        var broken = Set<Int>()
        for base in parts.keys.sorted(by: naturalLess) {
            let sorted = parts[base, default: []].sorted { partIndex($0) < partIndex($1) }
            let have = Set(sorted.map(partIndex))
            let missing = heads[base] == nil ? 0 : (1...(have.max() ?? 1)).first { !have.contains($0) }
            if let missing {
                let at = heads[base]?.index ?? sorted[0].index
                // Typed interpolation, so a translation cannot change the argument's type.
                let part = String(format: "%02d", missing)
                plan.errors.append((at, String(localized: "missing part \(part)")))
                if let head = heads[base] { broken.insert(head.index) }
            } else {
                plan.coveredParts += sorted.map(\.index)
            }
        }

        plan.packages = ordered.filter { $0.kind == .pkg && !broken.contains($0.index) }.map(\.index)
        plan.errors.sort { $0.index < $1.index }
        return plan
    }

    private static func partIndex(_ item: Item) -> Int {
        if case .pkgPart(_, let index) = item.kind { return index }
        return 0
    }
}
