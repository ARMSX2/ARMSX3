// SPDX-License-Identifier: GPL-2.0-only
// Strict armsx3://boot/<SERIAL>, armsx3://library and armsx3://settings parsing. Foundation only, for the host test.

import Foundation

enum DeepLink: Equatable {
    case boot(serial: String)
    case library
    case settings

    static let openTabNotification = Notification.Name("ARMSX3DeepLinkOpenTab")

    static func parse(_ url: URL) -> DeepLink? {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "armsx3",
              parts.user == nil, parts.password == nil, parts.port == nil,
              parts.query == nil, parts.fragment == nil,
              let host = parts.percentEncodedHost?.lowercased() else { return nil }
        var path = parts.percentEncodedPath
        if path.hasSuffix("/") { path.removeLast() }
        switch host {
        case "library": return path.isEmpty ? .library : nil
        case "settings": return path.isEmpty ? .settings : nil
        case "boot":
            guard path.first == "/" else { return nil }
            let serial = path.dropFirst()
            let ascii = serial.utf8.allSatisfy { 0x30...0x39 ~= $0 || 0x41...0x5A ~= $0 || 0x61...0x7A ~= $0 }
            return serial.utf8.count == 9 && ascii ? .boot(serial: serial.uppercased()) : nil
        default: return nil
        }
    }

    static func copyLink(serial: String) -> String { "armsx3://boot/" + serial }
}
