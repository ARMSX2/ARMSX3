// SPDX-License-Identifier: GPL-2.0-only
// The running game's RPCN rules: the online line, invites and banners. Foundation only, for the host test.

import Foundation

enum RpcnSession {
    /// Nil when this boot's PSN status is not RPCN; a signed-out or dropped session reads Offline.
    static func quickMenuLine(status: RpcnState.Status) -> String? {
        guard status.bootPsn == 2 else { return nil }
        switch status.session {
        case 2: return String(localized: "Online as \(status.onlineName)")
        case 1: return String(localized: "Signing in to RPCN…")
        default: return String(localized: "Offline")
        }
    }

    /// Every string is remote input: draw it as plain text, never log it.
    struct Invite: Decodable, Equatable, Identifiable {
        var id: UInt64
        var from: String
        var subject: String
        var body: String
    }

    /// None when the JSON does not parse.
    static func invites(_ json: Data) -> [Invite] {
        (try? JSONDecoder().decode([Invite].self, from: json)) ?? []
    }

    /// So each invite is announced once.
    static func inviteChanges(previous: Set<UInt64>, current: [Invite]) -> [Invite] {
        current.filter { !previous.contains($0.id) }
    }

    static func invitesValue(count: Int) -> String? {
        count == 0 ? nil : count == 1 ? String(localized: "1 invite") : String(localized: "\(count) invites")
    }

    /// At most once per boot; a failed session's `state` code picks the cause.
    static func bootBanner(status: RpcnState.Status, alreadyShown: Bool) -> String? {
        guard !alreadyShown else { return nil }
        if status.simulatedNeedsRpcn {
            return String(localized: "This game needs RPCN to play online, and PSN status is set to Simulated. Turn on Online Play in Settings › RPCN, then start the game again.")
        }
        guard status.bootPsn == 2 else { return nil }
        guard status.configured else {
            return String(localized: "Online play is on, but you're not signed in to RPCN. Sign in from Settings › RPCN, then start the game again.")
        }
        guard status.session == 3 else { return nil }
        switch status.state {
        case 9: return String(localized: "RPCN didn't accept your password, so this game is offline. Sign in again from Settings › RPCN.")
        case 3, 5: return String(localized: "Couldn't reach RPCN, so this game is offline. Check the internet connection, then start the game again.")
        case 7: return String(localized: "Your RPCN account is signed in on another device, so this game is offline.")
        default: return String(localized: "Couldn't sign in to RPCN (code \(status.state)), so this game is offline.")
        }
    }

    /// It names the sender only, never the invite's own text.
    static func inviteBanner(_ invite: Invite) -> String {
        String(localized: "\(invite.from) invited you to play. Open the menu, then Friends, to join.")
    }

    /// The core signs in again by itself; a state it will not retry, or no sign-in after 15 s, reads offline.
    struct ReturnWatch {
        enum Outcome: String { case kept, resigned, offline }
        static let window: TimeInterval = 15
        static let notRetried: Set<Int> = [1, 6, 8, 9, 10, 11]
        let reloginsAtBackground: Int
        private var answered = false

        init(reloginsAtBackground: Int) { self.reloginsAtBackground = reloginsAtBackground }

        /// `elapsed` is the time since `.active`.
        mutating func feed(_ status: RpcnState.Status, elapsed: TimeInterval) -> Outcome? {
            guard !answered else { return nil }
            let signedIn = status.bootPsn == 2 && status.session == 2
            let outcome: Outcome?
            if status.relogins > reloginsAtBackground {
                outcome = .resigned
            } else if !signedIn, Self.notRetried.contains(status.state) {
                outcome = .offline
            } else if elapsed >= Self.window {
                outcome = signedIn ? .kept : .offline
            } else {
                outcome = nil
            }
            answered = outcome != nil
            return outcome
        }
    }

    /// Nil when the session was kept.
    static func returnBanner(_ outcome: ReturnWatch.Outcome) -> String? {
        switch outcome {
        case .kept: return nil
        case .resigned: return String(localized: "You were away too long, so RPCN closed your session. You're signed in again.")
        case .offline:
            return String(localized: "You were away too long, so RPCN closed your session, and signing in again didn't work. This game is offline.")
        }
    }

    /// Answers once per drop, outside a return; a re-sign-in between two polls is never seen signed out.
    struct DropWatch {
        private var wasSignedIn = false

        mutating func feed(_ status: RpcnState.Status, returning: Bool) -> Bool {
            let signedIn = status.bootPsn == 2 && status.session == 2
            defer { wasSignedIn = signedIn }
            return wasSignedIn && !signedIn && !returning
        }
    }

    static var dropBanner: String { String(localized: "RPCN disconnected. Signing in again.") }
}
