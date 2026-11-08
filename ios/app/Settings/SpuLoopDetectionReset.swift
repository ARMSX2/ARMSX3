// SPDX-License-Identifier: GPL-2.0-only
// Turns the global SPU loop detection off once, as Android does, because it halves the frame rate; a later ON is respected.

import Foundation

enum SpuLoopDetectionReset {
    // Android's preference name, so the two say the same thing in a support thread.
    static let key = "config.migrated.spuLoopDetectionOff"
    static let path = "Core@@SPU loop detection"

    /// A read or a reset that fails records nothing, so the next launch tries again.
    static func ensure(defaults: UserDefaults = .standard) async -> String {
        if defaults.bool(forKey: key) { return "done" }

        let (json, status) = await Rpcs3Bridge.settings(forSerial: nil)
        guard status == 0, let json, let global = globalValue(json) else { return "read-failed" }

        if global == "true" {
            let (reset, _) = await Rpcs3Bridge.resetSettings([path], serial: nil)
            guard reset == 0 else { return "reset-failed" }
            defaults.set(true, forKey: key)
            return "reset"
        }
        defaults.set(true, forKey: key)
        return "kept"
    }

    // Nil when the dump does not have it.
    static func globalValue(_ json: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let leaves = root["leaves"] as? [[String: Any]] else { return nil }
        let want = path.components(separatedBy: "@@")
        return leaves.first { ($0["path"] as? [String]) == want }?["global"] as? String
    }
}
