// SPDX-License-Identifier: GPL-2.0-only
// The words that say which pairing file is in use. Foundation only so the host test runs it; never the file, its path or its identifier.

import Foundation

enum PairingSource {
    // A one-word sidecar beside pairingFile.plist, so the launcher and the app read the same record.
    static let originName = "pairingFile.origin"

    static let sideStoreLockdownFix = String(localized: "In SideStore, open Pairing File Management, long-press Remote Pairing File and choose Set as Preferred, then try again.")

    enum Origin: String {
        case imported, shared, documents
        case sidestoreLink = "sidestore-link"
    }

    // Files from before the sidecar existed were picked by hand.
    static func origin(_ text: String?) -> Origin {
        Origin(rawValue: text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") ?? .imported
    }

    private static func inSideStore(_ used: String) -> Bool {
        used == "sidestore-lc-rp" || used == "sidestore-lc-alt"
    }

    static func sourceWord(used: String, origin: Origin) -> String {
        if inSideStore(used) { return String(localized: "SideStore's (LiveContainer)") }
        switch origin {
        case .imported: return String(localized: "Imported")
        case .shared: return String(localized: "Shared to ARMSX3")
        case .sidestoreLink: return String(localized: "From SideStore")
        case .documents: return String(localized: "Documents folder")
        }
    }

    static func paneRejected(report: [String: String], fileFound: Bool) -> Bool {
        fileFound && report["result"] == "rejected"
    }

    static func paneValue(ownFileFound: Bool, sideStoreInPlace: Bool, origin: Origin, awaitingSideStore: Bool,
                          report: [String: String] = [:]) -> String {
        if awaitingSideStore { return String(localized: "Waiting for SideStore…") }
        if paneRejected(report: report, fileFound: ownFileFound || sideStoreInPlace) { return String(localized: "Rejected") }
        if ownFileFound, report["rejected"] == "own", inSideStore(report["used"] ?? "") { return String(localized: "SideStore's") }
        if ownFileFound {
            switch origin {
            case .imported, .shared: return String(localized: "Imported")
            case .sidestoreLink: return String(localized: "From SideStore")
            case .documents: return String(localized: "Documents folder")
            }
        }
        return sideStoreInPlace ? String(localized: "SideStore's") : String(localized: "Missing")
    }

    static func formatWord(_ format: String) -> String? {
        switch format {
        case "rp": return String(localized: "Remote Pairing")
        case "rp-lockdown": return String(localized: "Remote Pairing with lockdown keys")
        default: return nil
        }
    }

    static func dateText(_ date: Date, now: Date, locale: Locale, calendar: Calendar) -> String {
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = DateFormatter.dateFormat(fromTemplate: sameYear ? "dMMM" : "dMMMy", options: 0, locale: locale)
        return formatter.string(from: date)
    }

    static func deviceRow(report: [String: String], ownFileFound: Bool, origin: Origin, fileDate: Date?,
                          now: Date, locale: Locale, calendar: Calendar) -> DeviceRow? {
        let used = report["used"] ?? ""
        guard !used.isEmpty || ownFileFound else { return nil }
        let value: String
        let presence: Presence?
        switch report["result"] {
        case "accepted"?: (value, presence) = (String(localized: "Accepted"), .present)
        case "rejected"?: (value, presence) = (String(localized: "Rejected"), .missing)
        default: (value, presence) = (String(localized: "Not tried yet"), nil)
        }
        var parts = [sourceWord(used: used, origin: origin)]
        if let format = formatWord(report["format"] ?? "") { parts.append(format) }
        if let fileDate {
            let day = dateText(fileDate, now: now, locale: locale, calendar: calendar)
            parts.append(String(localized: "created \(day)"))
        }
        var fix = parts.joined(separator: ", ") + "."
        if report["rejected"] == "own", !used.isEmpty, used != "own" {
            fix += " " + String(localized: "Your imported file was rejected, so this one was used.")
        }
        if presence == .missing {
            fix += " " + String(localized: "This device rejected it. Make a new pairing file on a computer and add it.")
        }
        return DeviceRow(label: String(localized: "Pairing File"), value: value, isProblem: false, fix: fix, presence: presence)
    }

    // The problem report is English whatever the device language, like DiagnosticsStore's lines.
    private static func plainSource(_ used: String, _ origin: Origin) -> String {
        if inSideStore(used) { return "SideStore's (LiveContainer)" }
        switch origin {
        case .imported: return "imported"
        case .shared: return "shared to ARMSX3"
        case .sidestoreLink: return "from SideStore"
        case .documents: return "documents folder"
        }
    }

    private static func plainResult(_ report: [String: String]) -> String {
        switch report["result"] {
        case "accepted"?: return "accepted"
        case "rejected"?: return "rejected"
        default: return "not tried"
        }
    }

    static func reportLine(report: [String: String], ownFileFound: Bool, origin: Origin, fileDate: Date?,
                           now: Date = Date(), calendar: Calendar = Calendar(identifier: .gregorian)) -> String {
        let used = report["used"] ?? ""
        guard !used.isEmpty || ownFileFound else { return "none" }
        var parts = [plainSource(used, origin)]
        switch report["format"] {
        case "rp"?: parts.append("Remote Pairing")
        case "rp-lockdown"?: parts.append("Remote Pairing with lockdown keys")
        default: break
        }
        if let fileDate {
            let sameYear = calendar.component(.year, from: fileDate) == calendar.component(.year, from: now)
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = calendar
            formatter.timeZone = calendar.timeZone
            formatter.dateFormat = sameYear ? "d MMM" : "d MMM yyyy"
            parts.append("created " + formatter.string(from: fileDate))
        }
        parts.append(plainResult(report))
        return parts.joined(separator: ", ")
    }

    static let sideStoreNames = ["PairingFile_RemoteRP.plist", "ALTPairingFile.mobiledevicepairing"]

    static func format(of data: Data) -> String {
        guard PairingFileCheck.classify(data) == .valid else { return "" }
        return PairingFileCheck.carriesLockdownKeys(data) ? "rp-lockdown" : "rp"
    }

    // SideStore's file is read in place and never moved; one that is not a Remote Pairing file is skipped.
    static func sideStoreFile(liveContainerHome: String?, read: (URL) -> Data?) -> URL? {
        guard let home = liveContainerHome, !home.isEmpty else { return nil }
        let folder = URL(fileURLWithPath: home).appendingPathComponent("Documents/SideStore/Documents", isDirectory: true)
        for name in sideStoreNames {
            let url = folder.appendingPathComponent(name, isDirectory: false)
            if let data = read(url), PairingFileCheck.classify(data) == .valid { return url }
        }
        return nil
    }

    static func ladderLine(report: [String: String]) -> String? {
        guard (Int(report["tried"] ?? "") ?? 0) >= 2 else { return nil }
        let used = report["used"] ?? ""
        let last = "\(inSideStore(used) ? "SideStore's (LiveContainer)" : "imported") \(plainResult(report))"
        guard let first = report["rejected"], !first.isEmpty else { return last }
        return "\(inSideStore(first) ? "SideStore's (LiveContainer)" : "imported") rejected, \(last)"
    }
}
