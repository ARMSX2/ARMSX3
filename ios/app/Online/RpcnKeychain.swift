// SPDX-License-Identifier: GPL-2.0-only
// The RPCN login in this device's Keychain, never synced. A missing item reads as signed out, and nothing here logs.

import Foundation
import Security

enum RpcnKeychain {
    private static let service = "armsx3.rpcn"

    // The secret and the token travel together, so the item is never half written.
    private struct Login: Codable {
        var secret: String
        var token: String
    }

    private static func item(_ npid: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: npid]
    }

    /// False when the Keychain refused the write.
    @discardableResult
    static func save(npid: String, secret: String, token: String) -> Bool {
        guard !npid.isEmpty, let data = try? JSONEncoder().encode(Login(secret: secret, token: token)) else { return false }
        var add = item(npid)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        var status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let change: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
            status = SecItemUpdate(item(npid) as CFDictionary, change as CFDictionary)
        }
        return status == errSecSuccess
    }

    /// Not found, unreadable or empty all read as signed out.
    static func load(npid: String) -> (secret: String, token: String)? {
        guard !npid.isEmpty else { return nil }
        var query = item(npid)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = found as? Data,
              let login = try? JSONDecoder().decode(Login.self, from: data), !login.secret.isEmpty else { return nil }
        return (login.secret, login.token)
    }

    @discardableResult
    static func delete(npid: String) -> Bool {
        guard !npid.isEmpty else { return false }
        return SecItemDelete(item(npid) as CFDictionary) == errSecSuccess
    }
}
