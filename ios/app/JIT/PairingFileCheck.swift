// SPDX-License-Identifier: GPL-2.0-only
// Mirrors idevice's RpPairingFile field checks. The file is a secret: it is read in memory only and never logged.

import Foundation

enum PairingFileVerdict {
    case valid
    case legacyLockdown
    case invalid
}

enum PairingFileCheck {
    // Keys only a lockdown pairing record carries.
    private static let lockdownKeys = ["HostCertificate", "DeviceCertificate", "HostPrivateKey", "SystemBUID"]

    static func classify(_ data: Data) -> PairingFileVerdict {
        guard let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil),
              let dictionary = plist as? [String: Any] else { return .invalid }
        if (dictionary["public_key"] as? Data)?.count == 32,
           (dictionary["private_key"] as? Data)?.count == 32,
           dictionary["identifier"] is String {
            return .valid
        }
        return lockdownKeys.contains { dictionary[$0] != nil } ? .legacyLockdown : .invalid
    }

    static func carriesLockdownKeys(_ data: Data) -> Bool {
        guard let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil),
              let dictionary = plist as? [String: Any] else { return false }
        return lockdownKeys.contains { dictionary[$0] != nil }
    }
}
