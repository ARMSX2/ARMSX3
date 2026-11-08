// SPDX-License-Identifier: GPL-2.0-only
// Sony's title-update list, parsed and checked; ported from Android's Ps3UpdateService.kt.

import Foundation

struct TitlePackage: Equatable, Sendable, Codable {
    let version: String
    let size: Int64
    let sha1: String
    let url: URL
    let firmware: String?
}

struct TitlePatch: Equatable, Sendable {
    let titleID: String
    let name: String?
    let packages: [TitlePackage]
}

enum LookupFailure: Equatable, Sendable {
    case unreachable, untrusted, malformed, http(Int)
}

enum LookupOutcome: Equatable, Sendable {
    case none, found(TitlePatch), failed(LookupFailure)
}

enum VerXml {
    static let maxBody = 256 * 1024
    static let maxPackages = 64
    static let maxTitle = 128

    // Nil for anything that is not a titlepatch document for this title, or that breaks any field rule.
    static func parse(_ body: Data, titleID: String) -> TitlePatch? {
        guard body.count <= maxBody,
              isUTF8Document(body),
              body.range(of: Data("<!DOCTYPE".utf8)) == nil,
              body.range(of: Data("<!ENTITY".utf8)) == nil,
              let titleID = GameUpdates.titleID(titleID) else { return nil }
        let reader = Reader(titleID: titleID)
        let parser = XMLParser(data: body)
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        guard parser.parse(), !reader.refused,
              reader.rootTitleID.flatMap(GameUpdates.titleID) == titleID else { return nil }
        var unique: [TitlePackage] = []
        for package in reader.packages where !unique.contains(where: { GameUpdates.compare($0.version, package.version) == 0 }) {
            unique.append(package)
        }
        let packages = unique.sorted { GameUpdates.compare($0.version, $1.version) < 0 }
        return TitlePatch(titleID: titleID, name: reader.name, packages: packages)
    }

    // In any encoding but UTF-8 the byte search for a DOCTYPE sees nothing, so only UTF-8 reaches the parser.
    static func isUTF8Document(_ body: Data) -> Bool {
        guard body.first == UInt8(ascii: "<"), !body.contains(0) else { return false }
        let head = String(decoding: body.prefix(200), as: UTF8.self).lowercased()
        guard head.hasPrefix("<?xml") else { return true }
        guard let end = head.range(of: "?>") else { return false }
        let declaration = head[..<end.lowerBound]
        return !declaration.contains("encoding") || declaration.contains("utf-8")
    }

    // Trimmed, without control or format characters (bidi overrides among them), at most 128 characters.
    static func displayTitle(_ raw: String) -> String? {
        let kept = raw.unicodeScalars.filter { ![.control, .format].contains($0.properties.generalCategory) }
        let title = String(String.UnicodeScalarView(kept)).trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : String(title.prefix(maxTitle))
    }

    private final class Reader: NSObject, XMLParserDelegate {
        let titleID: String
        var rootTitleID: String?
        var name: String?
        var packages: [TitlePackage] = []
        var refused = false
        private var path: [String] = []
        private var text = ""

        init(titleID: String) { self.titleID = titleID }

        func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?,
                    qualifiedName: String?, attributes: [String: String] = [:]) {
            if path.isEmpty, element == "titlepatch" { rootTitleID = attributes["titleid"] }
            if element == "package" {
                if packages.count < VerXml.maxPackages, let package = Self.package(attributes, titleID: titleID) {
                    packages.append(package)
                } else {
                    refused = true
                    parser.abortParsing()
                }
            }
            path.append(element)
            text = ""
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

        func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
            path.removeLast()
            if element == "TITLE", path.last == "paramsfo", name == nil { name = VerXml.displayTitle(text) }
            text = ""
        }

        // Sony's http link has no integrity but the sha1sum, so an empty one is refused too.
        private static func package(_ attributes: [String: String], titleID: String) -> TitlePackage? {
            guard let version = attributes["version"], isVersion(version),
                  let size = attributes["size"].flatMap({ Int64($0) }), size > 0x20, size <= 32 << 30,
                  let sha1 = attributes["sha1sum"]?.lowercased(), sha1.utf8.count == 40,
                  sha1.utf8.allSatisfy({ (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }),
                  let url = attributes["url"].flatMap({ URL(string: $0) }),
                  GameUpdates.isPackageURL(url, titleID: titleID) else { return nil }
            // b0's https presents another host's certificate and can never load, so the http link is kept.
            var link = URLComponents(url: url, resolvingAgainstBaseURL: false)
            link?.scheme = "http"
            return TitlePackage(version: version, size: size, sha1: sha1, url: link?.url ?? url, firmware: attributes["ps3_system_ver"])
        }

        private static func isVersion(_ version: String) -> Bool {
            let bytes = Array(version.utf8)
            guard bytes.count == 4 || bytes.count == 5, bytes[bytes.count - 3] == 0x2E else { return false }
            return bytes.indices.allSatisfy { $0 == bytes.count - 3 || (0x30...0x39).contains(bytes[$0]) }
        }
    }
}

struct UpdateChain: Equatable, Sendable {
    let packages: [TitlePackage]
    let removesFirst: Bool
    let bytes: Int64
}

enum GameUpdates {
    static let listHost = "a0.ww.np.dl.playstation.net"
    static let packageHost = "b0.ww.np.dl.playstation.net"

    // Field by field as integers, so "1.10" is after "1.9" and "01.04" equals "1.04".
    static func compare(_ a: String, _ b: String) -> Int {
        let left = fields(a), right = fields(b)
        for index in 0 ..< max(left.count, right.count) {
            let l = index < left.count ? left[index] : 0, r = index < right.count ? right[index] : 0
            if l != r { return l < r ? -1 : 1 }
        }
        return 0
    }

    private static func fields(_ version: String) -> [Int] {
        version.split(separator: ".").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }

    // An older target than the installed one takes the update off first and rebuilds from the disc.
    static func chain(published: [TitlePackage], installed: String?, base: String?, target: String) -> UpdateChain? {
        guard published.contains(where: { compare($0.version, target) == 0 }) else { return nil }
        if let installed = installed, compare(target, installed) == 0 { return nil }
        let removesFirst = installed.map { compare(target, $0) < 0 } ?? false
        let floor = removesFirst ? base : (installed ?? base)
        let packages = published
            .filter { package in floor.map { compare(package.version, $0) > 0 } ?? true }
            .filter { compare($0.version, target) <= 0 }
            .sorted { compare($0.version, $1.version) < 0 }
        guard !packages.isEmpty else { return nil }
        return UpdateChain(packages: packages, removesFirst: removesFirst, bytes: packages.reduce(0) { $0 + $1.size })
    }

    // Sony's own path for this title on the package host, and nothing that could leave it.
    static func isPackageURL(_ url: URL, titleID: String) -> Bool {
        guard let id = Self.titleID(titleID), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme == "http" || parts.scheme == "https", parts.host == packageHost,
              parts.user == nil, parts.password == nil, parts.port == nil, parts.query == nil, parts.fragment == nil
        else { return false }
        let path = parts.path
        return path.hasPrefix("/tppkg/np/" + id + "/") && path.hasSuffix(".pkg")
            && !path.split(separator: "/", omittingEmptySubsequences: false).contains { $0 == "." || $0 == ".." }
    }

    static func firmwareHundredths(_ text: String) -> Int? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, (1...2).contains(parts[0].utf8.count), (1...4).contains(parts[1].utf8.count),
              parts.allSatisfy({ $0.utf8.allSatisfy { (0x30...0x39).contains($0) } }),
              let major = Int(parts[0]), let minor = Int(parts[1].padding(toLength: 4, withPad: "0", startingAt: 0))
        else { return nil }
        return major * 100 + minor / 100
    }

    static func needsFirmware(_ package: TitlePackage, installed: String) -> String? {
        guard let wanted = package.firmware.flatMap(firmwareHundredths), let have = firmwareHundredths(installed),
              wanted > have else { return nil }
        return String(wanted / 100) + "." + String(format: "%02d", wanted % 100)
    }

    static func cacheName(titleID: String, version: String) -> String {
        titleID + "-" + version + ".pkg"
    }

    // Four ASCII letters then five ASCII digits, after trimming and upper-casing.
    static func titleID(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.utf8.allSatisfy({ $0 < 0x80 }) else { return nil }
        let id = trimmed.uppercased()
        let bytes = Array(id.utf8)
        guard bytes.count == 9,
              bytes[..<4].allSatisfy({ (0x41...0x5A).contains($0) }),
              bytes[4...].allSatisfy({ (0x30...0x39).contains($0) }) else { return nil }
        return id
    }

    static func lookupURL(_ titleID: String) -> URL? {
        guard let id = Self.titleID(titleID) else { return nil }
        return URL(string: "https://a0.ww.np.dl.playstation.net/tpl/np/" + id + "/" + id + "-ver.xml")
    }

    // Sony answers 403 or 404, or an empty 200, for a title that never had an update.
    static func outcome(status: Int, body: Data, titleID: String) -> LookupOutcome {
        switch status {
        case 403, 404:
            return .none
        case 200:
            if body.allSatisfy({ $0 == 0x20 || $0 == 0x09 || $0 == 0x0A || $0 == 0x0D }) { return .none }
            guard let patch = VerXml.parse(body, titleID: titleID) else { return .failed(.malformed) }
            return patch.packages.isEmpty ? .none : .found(patch)
        default:
            return .failed(.http(status))
        }
    }
}
