// SPDX-License-Identifier: GPL-2.0-only
// Why built-in JIT failed and what the library says about it. Foundation only, so the host test runs it on the Mac.

import Foundation

enum JitFallback {
    static let causeKey = "armsx3.jit.fallbackCause"
    static let noticesKey = "armsx3.jit.noticesShown"

    // Turned on by the update that follows a stable LiveContainer release with guest extensions.
    static let supportEndsAnnounced = false

    static let minimumLiveContainer = "3.8.13"

    /// What the app could read of LiveContainer. A nil field is unreadable, and the table turns it into lc-unknown.
    struct Probe: Equatable {
        var insideLiveContainer: Bool
        var multitask = false
        var version: String? = nil
        var hooksActive: Bool? = nil
        var liveProcessPresent: Bool? = nil
        var helperSettingStored: Bool? = nil
        var bundleID: String? = nil
    }

    enum Cause: String {
        case lcOld = "lc-old"
        case helperSettingOff = "helper-setting-off"
        case liveProcessMissing = "liveprocess-missing"
        case multitask = "multitask"
        case lcUnknown = "lc-unknown"
        case pairingAbsent = "pairing-absent"
        case pairingRejected = "pairing-rejected"
        case vpnOff = "vpn-off"
        case ddiNotMounted = "ddi-not-mounted"
        case other = "other"
        case preTraced = "pre-traced"
    }

    struct Verdict: Equatable {
        let cause: Cause?
        let otherInstall: Bool
    }

    static func decide(jitOn: Bool, jitReason: String, helperWord: String, methodAtLaunch: String,
                       storedCause: String?, probe: Probe) -> Verdict {
        let none = Verdict(cause: nil, otherInstall: false)
        // A Wait player is traced on purpose, so a traced launch is no sign of a hand-off.
        if methodAtLaunch == "wait" { return none }
        if jitOn && jitReason == "helper-ok" {
            return Verdict(cause: nil, otherInstall: probe.insideLiveContainer && probe.hooksActive == false)
        }
        if jitOn {
            return Verdict(cause: storedCause.flatMap(Cause.init(rawValue:)) ?? .preTraced, otherInstall: false)
        }
        return Verdict(cause: offCause(jitReason: jitReason, helperWord: helperWord, probe: probe), otherInstall: false)
    }

    private static func offCause(jitReason: String, helperWord: String, probe: Probe) -> Cause {
        if probe.insideLiveContainer {
            if probe.multitask { return .multitask }
            guard let version = probe.version, let passes = versionAtLeast(version, minimumLiveContainer) else {
                return .lcUnknown
            }
            if !passes { return .lcOld }
            if probe.liveProcessPresent == false { return .liveProcessMissing }
            switch probe.hooksActive {
            case nil: return .lcUnknown
            // The setting reads on, so it changed after launch or LiveContainer is not what the probe expects.
            case false?: return probe.helperSettingStored == true ? .lcUnknown : .helperSettingOff
            case true?: break
            }
        }
        if jitReason == "pairing-absent" || helperWord == "pairing-absent" { return .pairingAbsent }
        switch helperWord {
        case "pairing-rejected", "tunnel-failed": return .pairingRejected
        case "connect-failed": return .vpnOff
        case "ddi-not-mounted": return .ddiNotMounted
        default: return .other
        }
    }

    /// Nil when either side is not dotted integers.
    static func versionAtLeast(_ version: String, _ minimum: String) -> Bool? {
        func parts(_ text: String) -> [Int]? {
            let pieces = text.split(separator: ".", omittingEmptySubsequences: false)
            guard pieces.allSatisfy({ !$0.isEmpty && $0.allSatisfy { ("0"..."9").contains($0) } }) else { return nil }
            let numbers = pieces.compactMap { Int($0) }
            return numbers.count == pieces.count ? numbers : nil
        }
        guard var have = parts(version), var want = parts(minimum) else { return nil }
        let width = max(have.count, want.count)
        have += Array(repeating: 0, count: width - have.count)
        want += Array(repeating: 0, count: width - want.count)
        return !have.lexicographicallyPrecedes(want)
    }

    static func storedCause(in defaults: UserDefaults) -> Cause? {
        defaults.string(forKey: causeKey).flatMap(Cause.init(rawValue:))
    }

    static func store(_ verdict: Verdict, in defaults: UserDefaults) {
        if let cause = verdict.cause {
            defaults.set(cause.rawValue, forKey: causeKey)
        } else {
            defaults.removeObject(forKey: causeKey)
        }
    }

    struct ChecklistRow: Equatable {
        let label: String
        let presence: Presence
        let fix: String?
    }

    /// The LiveContainer rows of the JIT card. Empty when nothing about LiveContainer stands in the way.
    static func checklist(probe: Probe, jitOnThroughBuiltin: Bool) -> [ChecklistRow] {
        guard probe.insideLiveContainer, !jitOnThroughBuiltin else { return [] }
        let versionPasses = probe.version.flatMap { versionAtLeast($0, minimumLiveContainer) }
        if probe.hooksActive == true && versionPasses == true && !probe.multitask { return [] }

        var rows: [ChecklistRow] = []
        switch versionPasses {
        case true?:
            rows.append(ChecklistRow(label: versionLabel, presence: .present, fix: nil))
        case false?:
            let fix = String(localized: "This LiveContainer is \(probe.version ?? ""). Update it to 3.8.13 or later, then install ARMSX3 in it again.")
            rows.append(ChecklistRow(label: versionLabel, presence: .missing, fix: fix))
        case nil:
            rows.append(ChecklistRow(label: versionLabel, presence: .unknown, fix: nil))
        }

        let helperLabel = String(localized: "Helper Process Support on")
        if versionPasses != true {
            rows.append(ChecklistRow(label: helperLabel, presence: .unknown, fix: nil))
        } else {
            switch probe.hooksActive {
            case true?:
                rows.append(ChecklistRow(label: helperLabel, presence: .present, fix: nil))
            case false?:
                let fix = String(localized: "In LiveContainer, long-press ARMSX3, open Settings and turn on Helper Process Support. Then close ARMSX3 and open it again.")
                rows.append(ChecklistRow(label: helperLabel, presence: .missing, fix: fix))
            case nil:
                rows.append(ChecklistRow(label: helperLabel, presence: .unknown, fix: nil))
            }
        }

        if probe.multitask {
            let fix = String(localized: "ARMSX3 is open in a LiveContainer window, where built-in JIT can't start. Close the window and open ARMSX3 from LiveContainer's app list.")
            rows.append(ChecklistRow(label: String(localized: "Opened full screen"), presence: .missing, fix: fix))
        }
        return rows
    }

    private static var versionLabel: String { String(localized: "LiveContainer 3.8.13 or later") }

    enum NoticeAction { case setUpBuiltin }

    struct Notice: Equatable {
        let key: String
        let title: String
        let body: String
        let symbol: String
        let action: NoticeAction?
    }

    static func noticeBody(cause: Cause, insideLiveContainer: Bool, version: String?, supportEnds: Bool) -> String {
        let body: String
        switch cause {
        case .lcOld:
            if let version {
                body = String(localized: "This LiveContainer is \(version), and built-in JIT needs 3.8.13 or later, so StikDebug turned on JIT instead. Update LiveContainer, then install ARMSX3 in it again.")
            } else {
                return noticeBody(cause: .lcUnknown, insideLiveContainer: insideLiveContainer, version: nil, supportEnds: supportEnds)
            }
        case .helperSettingOff:
            body = String(localized: "Helper Process Support is off for ARMSX3 in LiveContainer, so StikDebug turned on JIT instead. In LiveContainer, long-press ARMSX3, open Settings and turn it on.")
        case .liveProcessMissing:
            body = String(localized: "LiveContainer's LiveProcess extension is missing, so StikDebug turned on JIT instead. Reinstall LiveContainer and keep its extensions.")
        case .multitask:
            body = String(localized: "ARMSX3 was opened in a LiveContainer window, where built-in JIT can't start, so StikDebug turned on JIT instead. Next time, open ARMSX3 full screen from LiveContainer.")
        case .lcUnknown:
            body = String(localized: "Built-in JIT didn't start inside LiveContainer, so StikDebug turned on JIT instead. Check that LiveContainer is 3.8.13 or later and Helper Process Support is on for ARMSX3.")
        case .pairingAbsent:
            body = String(localized: "ARMSX3 has no pairing file, so StikDebug turned on JIT instead. Add one, and ARMSX3 turns on JIT itself.")
        case .pairingRejected:
            body = String(localized: "This device didn't accept the pairing file, so StikDebug turned on JIT instead. Make a new pairing file and add it.")
        case .vpnOff:
            body = String(localized: "ARMSX3 couldn't reach this device through LocalDevVPN, so StikDebug turned on JIT instead. Turn LocalDevVPN on before you open ARMSX3.")
        case .ddiNotMounted:
            body = String(localized: "The Developer Disk Image isn't mounted, so StikDebug turned on JIT instead. Mount it, and ARMSX3 turns on JIT itself next time.")
        case .other:
            body = String(localized: "Built-in JIT didn't finish setting up, so StikDebug turned on JIT instead. ARMSX3 tries built-in JIT again each time it opens.")
        case .preTraced:
            return insideLiveContainer
                ? String(localized: "LiveContainer was opened through StikDebug, so ARMSX3 didn't try built-in JIT. Open LiveContainer from the Home Screen instead.")
                : String(localized: "ARMSX3 was opened through StikDebug, so it didn't try built-in JIT. Open ARMSX3 from the Home Screen instead.")
        }
        return supportEnds ? body + " " + String(localized: "StikDebug support ends in an upcoming update.") : body
    }

    private static func action(for cause: Cause) -> NoticeAction? {
        switch cause {
        case .lcUnknown, .pairingAbsent, .pairingRejected, .ddiNotMounted: return .setUpBuiltin
        default: return nil
        }
    }

    /// The one notice a launch may show and the keys that count as shown once it appears. Priority: the fallback cause,
    /// then opened through StikDebug, then the migration. A key already in `shown` never comes back.
    static func notice(verdict: Verdict, jitOnThroughBuiltin: Bool, migrationPending: Bool, probe: Probe,
                       shown: Set<String>, supportEnds: Bool) -> (notice: Notice, marks: [String])? {
        let migrationMark = migrationPending ? ["migrated"] : []
        if let cause = verdict.cause {
            let fallback = cause != .preTraced
            let key = fallback && supportEnds ? cause.rawValue + "+ends" : cause.rawValue
            if !shown.contains(key) {
                let body = noticeBody(cause: cause, insideLiveContainer: probe.insideLiveContainer, version: probe.version,
                                      supportEnds: fallback && supportEnds)
                let notice = fallback
                    ? Notice(key: key, title: String(localized: "Built-in JIT didn't start"), body: body,
                             symbol: "exclamationmark.triangle.fill", action: action(for: cause))
                    : Notice(key: key, title: String(localized: "Opened through StikDebug"), body: body,
                             symbol: "exclamationmark.triangle.fill", action: nil)
                return (notice, [key] + migrationMark)
            }
        }
        guard migrationPending, !shown.contains("migrated") else { return nil }
        let notice = jitOnThroughBuiltin
            ? Notice(key: "migrated", title: String(localized: "ARMSX3 now turns on JIT itself"),
                     body: String(localized: "It did this time. StikDebug is only used if built-in JIT fails."),
                     symbol: "info.circle.fill", action: nil)
            : Notice(key: "migrated", title: String(localized: "ARMSX3 now turns on JIT itself"),
                     body: String(localized: "StikDebug is only used if built-in JIT fails. Set it up once, and ARMSX3 turns on JIT each time it opens."),
                     symbol: "info.circle.fill", action: .setUpBuiltin)
        return (notice, ["migrated"])
    }

    /// The word the older versions stored for the method that this update replaces.
    static func migrates(storedMethod: String) -> Bool {
        storedMethod == "stikdebug"
    }

    static func shownKeys(in defaults: UserDefaults) -> Set<String> {
        Set(defaults.stringArray(forKey: noticesKey) ?? [])
    }

    static func markShown(_ keys: [String], in defaults: UserDefaults) {
        defaults.set(shownKeys(in: defaults).union(keys).sorted(), forKey: noticesKey)
    }
}
