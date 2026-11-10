// SPDX-License-Identifier: GPL-2.0-only
// SideStore's export link carries a private key, so it is accepted only right after ARMSX3 asked, once, and never logged.

import Foundation

enum PairingLink {
    // Outside the backup allow-list on purpose: a request must not survive a restore.
    static let requestedAtKey = "armsx3.jit.sideStoreRequestedAt"
    static let window: TimeInterval = 60
    static let maxBase64 = 131_072

    static var requestURL: URL { URL(string: "sidestore://pairing?urlname=armsx3")! }

    enum Verdict {
        case accepted(Data), ignored, malformed
    }

    // What the root made of the last link, for the pane to show once.
    enum Answer: String {
        case added, replaced, lockdown, invalid, failed, ignored
    }

    @MainActor static var lastAnswer: Answer?

    static func isPairingLink(_ url: URL) -> Bool {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        return parts.scheme?.lowercased() == "armsx3" && parts.host?.lowercased() == "pairingfile"
    }

    static func parse(_ url: URL) -> Data? {
        guard isPairingLink(url),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = parts.queryItems, items.count == 1, items[0].name == "data",
              let text = items[0].value, text.utf8.count <= maxBase64,
              let data = Data(base64Encoded: text), !data.isEmpty else { return nil }
        return data
    }

    static func markRequested(at date: Date, in defaults: UserDefaults) {
        defaults.set(date.timeIntervalSince1970, forKey: requestedAtKey)
    }

    static func isAwaiting(at date: Date, in defaults: UserDefaults) -> Bool {
        guard let stored = defaults.object(forKey: requestedAtKey) as? Double else { return false }
        let age = date.timeIntervalSince1970 - stored
        return age >= 0 && age <= window
    }

    // The request is removed whatever the verdict, so a link works once.
    static func take(_ url: URL, at date: Date, in defaults: UserDefaults) -> Verdict {
        let awaiting = isAwaiting(at: date, in: defaults)
        defaults.removeObject(forKey: requestedAtKey)
        guard awaiting else { return .ignored }
        guard let data = parse(url) else { return .malformed }
        return .accepted(data)
    }
}
