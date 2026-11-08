// SPDX-License-Identifier: GPL-2.0-only
// The RPCN pane's pure rules. The bridge returns codes, never English; every sentence the player reads is here.

import Foundation

enum RpcnState {
    /// `session`: 0 none, 1 signing in, 2 signed in, 3 failed.
    struct Status: Decodable, Equatable {
        var configured: Bool
        var npid: String
        var client: Bool
        var connected: Bool
        var signedIn: Bool
        var state: Int
        var session: Int
        var onlineName: String
        var bootPsn: Int
        var relogins: Int
        var simulatedNeedsRpcn: Bool
    }

    struct Host: Decodable, Equatable {
        var desc: String
        var host: String
    }

    /// Never the secret or the token, only whether the slot holds them.
    struct Config: Decodable, Equatable {
        var host: String
        var npid: String
        var hasSecret: Bool
        var hasToken: Bool
        var ipv6: Bool
        var hosts: [Host]
    }

    static let psnStatusPath = "Net@@PSN status"
    static let internetPath = "Net@@Internet enabled"

    /// On sets Internet first, because the core resets PSN status on each boot while Internet is off.
    static func onlineSwitchWrites(on: Bool, internetConnected: Bool) -> [(path: String, value: String)] {
        guard on else { return [(psnStatusPath, "Disconnected")] }
        return (internetConnected ? [] : [(internetPath, "Connected")]) + [(psnStatusPath, "RPCN")]
    }

    enum AccountState: Equatable {
        case off, opening, signedIn, failed, form

        /// Only `Couldn't sign in` is drawn warm.
        var word: String {
            switch self {
            case .off: return String(localized: "Online play is off")
            case .opening: return String(localized: "Signing in…")
            case .signedIn: return String(localized: "Signed in")
            case .failed: return String(localized: "Couldn't sign in")
            case .form: return ""
            }
        }

        var warm: Bool { self == .failed }
    }

    static func accountState(onlinePlay: Bool, configured: Bool, hasSecret: Bool, session: Int) -> AccountState {
        guard configured, hasSecret else { return .form }
        guard onlinePlay else { return .off }
        switch session {
        case 2: return .signedIn
        case 3: return .failed
        default: return .opening
        }
    }

    /// The order is every screen's own field order. Passwords count as empty only when they hold nothing at all.
    static func missingInput(username: String? = nil, password: String? = nil, email: String? = nil, code: String? = nil,
                             newPassword: String? = nil) -> String? {
        let blank = { (text: String?) in text.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? false }
        if blank(username) { return String(localized: "Enter your username.") }
        if password?.isEmpty == true { return String(localized: "Enter your password.") }
        if blank(email) { return String(localized: "Enter the email address for this account.") }
        if blank(code) { return String(localized: "Enter the code from the email.") }
        if newPassword?.isEmpty == true { return String(localized: "Enter a new password.") }
        return nil
    }

    /// Only LoginInvalidToken reads differently by screen.
    enum AccountScreen {
        case signIn, create, activate, reset, newPassword
    }

    /// `raw` is the core's own number for the "code {n}" sentences.
    static func accountSentence(error: Int, raw: Int, screen: AccountScreen) -> String {
        switch error {
        case 0: return "" // NONE
        case 1: return String(localized: "ARMSX3 sent RPCN an incomplete request (code \(raw)). Report it from Settings › Diagnostics.") // MALFORMED
        case 2: return String(localized: "RPCN didn't accept those details. Usernames are 3 to 16 letters, numbers, - or _.") // INVALID_INPUT
        case 3: return String(localized: "Too many tries. Wait a few minutes, then try again.") // TOO_SOON
        case 4: return String(localized: "RPCN didn't accept the sign-in. Try again in a few minutes.") // LOGIN_ERROR
        case 5: return String(localized: "This account is signed in on another device. Sign out there, then try again.") // LOGIN_ALREADY_LOGGED_IN
        case 6: return String(localized: "There's no RPCN account with that username.") // LOGIN_INVALID_USERNAME
        case 7: return String(localized: "That password isn't right. Try again, or use Forgot Password.") // LOGIN_INVALID_PASSWORD
        case 8: // LOGIN_INVALID_TOKEN
            return screen == .reset || screen == .newPassword
                ? String(localized: "That code isn't right or has expired. Go back and use Email Me a Code for a new one.")
                : String(localized: "That code isn't right or has expired. Use Email the Code Again for a new one.")
        case 9: return String(localized: "RPCN couldn't create the account. Try again later.") // CREATION_ERROR
        case 10: return String(localized: "That username is taken. Pick another one.") // CREATION_EXISTING_USERNAME
        case 11: return String(localized: "RPCN doesn't accept addresses from that email provider. Use another address.") // CREATION_BANNED_EMAIL_PROVIDER
        case 12: return String(localized: "That email address already has an account. Sign in, or use Forgot Password.") // CREATION_EXISTING_EMAIL
        case 13: return String(localized: "RPCN couldn't send the email. Try again later.") // EMAIL_FAIL
        // Friend-call codes, 99 OTHER and any later code read as the generic sentence.
        default: return String(localized: "Something went wrong with RPCN (code \(raw)). Try again.")
        }
    }

    /// -7 is the bridge refusing the details' shape before the network.
    static func accountOutcome(status: Int, state: Int, error: Int, raw: Int, screen: AccountScreen) -> String? {
        if status == -7 { return accountSentence(error: 2, raw: 2, screen: screen) }
        if status != 0 { return accountSentence(error: 99, raw: status, screen: screen) }
        if state != 0 { return signInSentence(state: state, error: 0, raw: raw) }
        return error == 0 ? nil : accountSentence(error: error, raw: raw, screen: screen)
    }

    /// `state` wins when it is not OK; otherwise `error` decides.
    static func signInSentence(state: Int, error: Int, raw: Int) -> String {
        switch state {
        case 0: break // RPCS3_IOS_RPCN_STATE_OK: the server's answer decides
        case 1: return String(localized: "That server address doesn't work. Pick another server in Servers.") // INPUT
        case 2: return String(localized: "Couldn't make a secure connection to RPCN. Check that this device's date and time are right, then try again.") // WOLFSSL
        case 3: return String(localized: "The server couldn't be found. Check the internet connection and the server address.") // RESOLVE
        case 4: return String(localized: "ARMSX3 couldn't use the Bind address set in Network. Set it back to 0.0.0.0, then try again.") // BINDING
        case 5: return String(localized: "Couldn't reach RPCN. Check the internet connection, then try again.") // CONNECT
        case 6: return String(localized: "RPCN didn't accept the sign-in. Try again in a few minutes.") // ID
        case 7: return String(localized: "This account is signed in on another device. Sign out there, then try again.") // ID_ALREADY_LOGGED_IN
        case 8: return String(localized: "There's no RPCN account with that username.") // ID_USERNAME
        case 9: return String(localized: "That password isn't right. Try again, or use Forgot Password.") // ID_PASSWORD
        case 10: return String(localized: "That code isn't right or has expired. Use Email the Code Again for a new one.") // ID_TOKEN
        case 11: return String(localized: "This server needs a newer version of ARMSX3.") // PROTOCOL
        default: return String(localized: "Couldn't sign in to RPCN (code \(raw)). Try again.") // 12 OTHER, and any later state
        }
        switch error {
        case 0: return "" // NONE
        case 1: return String(localized: "ARMSX3 sent RPCN an incomplete request (code \(raw)). Report it from Settings › Diagnostics.") // MALFORMED
        case 2: return String(localized: "RPCN didn't accept those details. Usernames are 3 to 16 letters, numbers, - or _.") // INVALID_INPUT
        case 3: return String(localized: "Too many tries. Wait a few minutes, then try again.") // TOO_SOON
        case 4: return String(localized: "RPCN didn't accept the sign-in. Try again in a few minutes.") // LOGIN_ERROR
        case 5: return String(localized: "This account is signed in on another device. Sign out there, then try again.") // LOGIN_ALREADY_LOGGED_IN
        case 6: return String(localized: "There's no RPCN account with that username.") // LOGIN_INVALID_USERNAME
        case 7: return String(localized: "That password isn't right. Try again, or use Forgot Password.") // LOGIN_INVALID_PASSWORD
        case 8: return String(localized: "That code isn't right or has expired. Use Email the Code Again for a new one.") // LOGIN_INVALID_TOKEN
        // Codes that do not answer a sign-in, 99 OTHER and any later code read as the generic sentence.
        default: return String(localized: "Something went wrong with RPCN (code \(raw)). Try again.")
        }
    }

    struct FriendRow: Hashable {
        enum Kind: String {
            case received, sent, online, offline
        }

        var name: String
        var kind: Kind
        var value: String
    }

    /// The presence fields other than the title are not drawn here.
    private struct FriendsList: Decodable {
        struct Friend: Decodable {
            var npid: String
            var online: Bool
            var title: String
        }

        var friends: [Friend]
        var received: [String]
        var sent: [String]
    }

    /// Every string is remote input, to be drawn as plain text. JSON that does not parse gives no rows.
    static func friendRows(_ json: Data) -> [FriendRow] {
        guard let list = try? JSONDecoder().decode(FriendsList.self, from: json) else { return [] }
        let byName = { (lhs: String, rhs: String) in lhs.lowercased() < rhs.lowercased() }
        let friends = list.friends.sorted { byName($0.npid, $1.npid) }
        let presence = { (friend: FriendsList.Friend) -> String in
            let title = friend.title.trimmingCharacters(in: .whitespacesAndNewlines)
            return title.isEmpty ? String(localized: "Online") : String(localized: "Playing \(title)")
        }
        return list.received.sorted(by: byName).map { FriendRow(name: $0, kind: .received, value: String(localized: "Wants to be your friend")) }
            + list.sent.sorted(by: byName).map { FriendRow(name: $0, kind: .sent, value: String(localized: "Request sent")) }
            + friends.filter(\.online).map { FriendRow(name: $0.npid, kind: .online, value: presence($0)) }
            + friends.filter { !$0.online }.map { FriendRow(name: $0.npid, kind: .offline, value: String(localized: "Offline")) }
    }

    static func friendSentence(error: Int, raw: Int, name: String) -> String {
        switch error {
        case 0: return "" // NONE
        case 14: return String(localized: "There's no RPCN account named \(name).") // NOT_FOUND
        case 15: return String(localized: "\(name) can't be added.") // BLOCKED
        case 16: return String(localized: "You're already friends with \(name).") // ALREADY_FRIEND
        default: return String(localized: "Couldn't update your friends (code \(raw)). Try again.") // 99 OTHER and the rest
        }
    }

    /// -7 is a name RPCN cannot hold, so no such account exists.
    static func friendOutcome(status: Int, state: Int, error: Int, raw: Int, name: String) -> String? {
        if status == -7 { return friendSentence(error: 14, raw: 26, name: name) }
        if status != 0 { return friendSentence(error: 99, raw: status, name: name) }
        if state != 0 { return friendSentence(error: 99, raw: raw, name: name) }
        return error == 0 ? nil : friendSentence(error: error, raw: raw, name: name)
    }

    /// Paths and titles are drawn as plain text, never logged.
    struct AvatarItem: Decodable, Equatable {
        var path: String
        var kind: String
        var title: String
        var usable: Bool?

        /// A tile's controller target and the key a favourite is kept by.
        var file: String { String(path.split(separator: "/").last ?? "") }
    }

    struct AvatarList: Decodable {
        var firmware: Bool
        var avatars: [AvatarItem]
        var unusable: Int
    }

    /// An avatar still needing its licence is left out, as Android and RPCS3 leave it out; the count line counts it.
    static func avatarTiles(_ items: [AvatarItem]) -> [AvatarItem] {
        let rank = ["builtin": 0, "psn": 1, "picture": 2]
        return items.enumerated()
            .filter { $0.element.usable != false }
            .sorted { lhs, rhs in
                let left = rank[lhs.element.kind] ?? 3, right = rank[rhs.element.kind] ?? 3
                if left != right { return left < right }
                if left == 1 && lhs.element.title != rhs.element.title { return lhs.element.title < rhs.element.title }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// The second sentence only when m > 0.
    static func avatarCountLine(n: Int, m: Int) -> String {
        let count = n == 1 ? String(localized: "1 avatar.") : String(localized: "\(n) avatars.")
        let more = m == 1 ? String(localized: "1 more needs their licence.") : String(localized: "\(m) more need their licence.")
        return m > 0 ? count + " " + more : count
    }

    static func avatarValue(kind: String) -> String {
        switch kind {
        case "builtin": return String(localized: "PS3 user icon")
        case "psn": return String(localized: "PSN avatar")
        case "picture": return String(localized: "Your picture")
        default: return String(localized: "Default")
        }
    }
}
