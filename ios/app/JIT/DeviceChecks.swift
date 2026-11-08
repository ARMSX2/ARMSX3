// SPDX-License-Identifier: GPL-2.0-only
// The Device card's rows, values and fix sentences. Foundation only, so the host test runs it on the Mac.

import Foundation

enum Presence: Equatable {
    case present, missing, unknown

    init(_ value: Int64) {
        self = value == 1 ? .present : value == 0 ? .missing : .unknown
    }
}

struct DeviceRow: Equatable {
    let label: String
    let value: String
    let isProblem: Bool
    let fix: String?
    // A Presence rather than the view's StatusMark, so this file still compiles on its own for the host tests.
    var presence: Presence? = nil
}

struct DeviceReport: Equatable {
    static let keys = ["getTaskAllow", "increasedMemoryLimit", "extendedVirtualAddressing", "txm", "memoryLimitBytes", "liveContainer"]

    let getTaskAllow: Presence
    let increasedMemoryLimit: Presence
    let extendedVirtualAddressing: Presence
    let txm: Presence
    let memoryLimitBytes: Int64
    let liveContainer: Bool

    /// Nil when any key is absent, so the pane shows its unreadable state instead.
    init?(_ checks: [String: NSNumber]) {
        guard Self.keys.allSatisfy({ checks[$0] != nil }) else { return nil }
        func value(_ key: String) -> Int64 { checks[key]?.int64Value ?? -1 }
        getTaskAllow = Presence(value("getTaskAllow"))
        increasedMemoryLimit = Presence(value("increasedMemoryLimit"))
        extendedVirtualAddressing = Presence(value("extendedVirtualAddressing"))
        txm = Presence(value("txm"))
        memoryLimitBytes = value("memoryLimitBytes")
        liveContainer = value("liveContainer") == 1
    }

    /// Binary gigabytes to one decimal, so the 6 GiB limit reads "6.0 GB".
    static func memoryText(_ bytes: Int64) -> String {
        guard bytes >= 1 else { return String(localized: "Unknown") }
        return String(format: "%.1f GB", Double(bytes) / 1073741824)
    }

    private static func word(_ presence: Presence, present: String, missing: String) -> String {
        switch presence {
        case .present: return present
        case .missing: return missing
        case .unknown: return String(localized: "Unknown")
        }
    }

    var rows: [DeviceRow] {
        let entitlement = Self.word(getTaskAllow, present: String(localized: "Present"), missing: String(localized: "Missing"))
        let memory = Self.word(increasedMemoryLimit, present: String(localized: "Present"), missing: String(localized: "Missing"))
        let txmWord = Self.word(txm, present: String(localized: "Present"), missing: String(localized: "Not present"))
        let space = Self.word(extendedVirtualAddressing, present: String(localized: "Extended"), missing: String(localized: "Standard"))
        let memoryFix = liveContainer
            ? String(localized: "Games are more likely to close when memory runs low. Sign LiveContainer again with the increased-memory-limit entitlement.")
            : String(localized: "Games are more likely to close when memory runs low. Sign ARMSX3 again with the increased-memory-limit entitlement.")
        var rows = [
            DeviceRow(label: String(localized: "Debugging Entitlement"), value: entitlement, isProblem: getTaskAllow == .missing,
                      fix: getTaskAllow == .missing
                          ? String(localized: "No JIT method works without it. Sign ARMSX3 again with get-task-allow turned on, then reinstall it.")
                          : nil, presence: getTaskAllow),
            DeviceRow(label: String(localized: "Increased Memory Limit"), value: memory, isProblem: increasedMemoryLimit == .missing,
                      fix: increasedMemoryLimit == .missing ? memoryFix : nil, presence: increasedMemoryLimit),
            DeviceRow(label: String(localized: "Memory Limit"), value: Self.memoryText(memoryLimitBytes), isProblem: false, fix: nil),
            DeviceRow(label: String(localized: "TXM"), value: txmWord, isProblem: false, fix: nil, presence: txm),
            // The word, not a mark: a red mark read as something to fix, and signing cannot fix it.
            DeviceRow(label: String(localized: "Address Space"), value: space, isProblem: false, fix: nil),
        ]
        if liveContainer {
            rows.append(DeviceRow(label: String(localized: "LiveContainer"), value: String(localized: "Detected"), isProblem: false, fix: nil))
        }
        return rows
    }
}
