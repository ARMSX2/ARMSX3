// SPDX-License-Identifier: GPL-2.0-only
// The XMB's RPCN column: Account Management, Add a Friend, requests and friends, ported from Android's XmbFriends.kt.

import SwiftUI

/// The friend list read every two seconds while the RPCN column is in view, with the friends session held meanwhile.
@MainActor final class XmbFriendsPolling {
    static let shared = XmbFriendsPolling()

    private var task: Task<Void, Never>?

    func setShown(_ shown: Bool) {
        if shown {
            guard task == nil else { return }
            task = Task {
                let rpcn = RpcnModel.shared
                await rpcn.refresh()
                while !Task.isCancelled {
                    // The RPCN settings sheet lets the session go as it closes, so it is taken again here.
                    if !rpcn.paneVisible { await rpcn.setPaneVisible(true) }
                    let before = rpcn.friends
                    await rpcn.poll()
                    XmbNotices.shared.friendsRead(before, rpcn.friends)
                    try? await Task.sleep(for: .seconds(2))
                }
            }
        } else if let task {
            task.cancel()
            self.task = nil
            Task { await RpcnModel.shared.setPaneVisible(false) }
        }
    }
}

extension XmbHost {
    func friendsSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "rpcn", title: "RPCN", icon: .firmware(rco: XmbRco.ingame, name: "tex_friend", symbol: "person.2"), items: []))
        let requestIcon = XmbIcon.firmware(rco: XmbRco.friend, name: "item_tex_NewFriend", symbol: "person.badge.plus")
        let friendIcon = XmbIcon.firmware(rco: XmbRco.friend, name: "tex_Avatar_Default", symbol: "person.crop.square")
        var items = [accountFolder(&section)]
        items.append(section.action(XmbNode(
            id: "rpcn.add", title: firmware.text(XmbRco.full, "msg_friend_register", String(localized: "Add a Friend")), subtitle: nil,
            icon: requestIcon, kind: .action)) { [weak self] in self?.addFriend() })
        // Requests from others are accepted or declined, requests sent are cancelled, and friends removed.
        for row in rpcn.friends {
            switch row.kind {
            case .received:
                let id = "rpcn.received." + row.name
                let answer: @MainActor (String) -> Void = { [weak self] option in
                    if option == "accept" {
                        self?.answerFriend(row, .accept, done: String(localized: "You and \(row.name) are friends now."))
                    } else {
                        self?.answerFriend(row, .decline, done: String(localized: "Declined the request from \(row.name)."))
                    }
                }
                section.options[id] = answer
                let wants = String(localized: "Wants to be your friend")
                items.append(section.action(XmbNode(
                    id: id, title: row.name, subtitle: wants, icon: requestIcon, kind: .action,
                    options: [.init(id: "accept", title: String(localized: "Accept")), .init(id: "decline", title: String(localized: "Decline"))])) {
                    // Android asks in the dark band, the request's name over the question.
                    [weak self] in
                    self?.ask(row.name + "\n" + wants, buttons: [String(localized: "Accept"), String(localized: "Decline")]) { button in
                        answer(button == 0 ? "accept" : "decline")
                    }
                })
            case .sent:
                let id = "rpcn.sent." + row.name
                section.options[id] = { [weak self] _ in
                    self?.answerFriend(row, .withdraw, done: String(localized: "Cancelled your request to \(row.name)."))
                }
                items.append(XmbNode(id: id, title: row.name, subtitle: String(localized: "Waiting for them to accept"), icon: requestIcon,
                                     kind: .action, options: [.init(id: "cancel", title: String(localized: "Cancel"))]))
            case .online, .offline:
                let id = "rpcn.friend." + row.name
                section.options[id] = { [weak self] _ in
                    guard let self else { return }
                    ask(String(localized: "Remove \(row.name) from your friends?"), buttons: [yes, no], focus: 1) { [weak self] button in
                        guard button == 0 else { return }
                        self?.answerFriend(row, .remove, done: String(localized: "Removed \(row.name)."))
                    }
                }
                // The row says Online or Playing <title>; the console's own word is used for Online.
                let online = row.value == String(localized: "Online") ? firmware.text(XmbRco.full, "msg_online", row.value) : row.value
                items.append(section.action(XmbNode(
                    id: id, title: row.name, subtitle: row.kind == .online ? online : String(localized: "Offline"), icon: friendIcon,
                    kind: .action, options: [.init(id: "remove", title: String(localized: "Remove"))])) { [weak self] in self?.perform(.options) })
            }
        }
        section.column.items = items
        return section
    }

    /// Adding someone who already asked is accepting them, and the server says so.
    private func addFriend() {
        type(String(localized: "RPCN username")) { [weak self] typed in
            guard let self else { return }
            let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { notice(String(localized: "Enter a username to add.")); return }
            let asked = rpcn.friends.contains { $0.kind == .received && $0.name == name }
            Task {
                let added = await self.rpcn.addFriend(name)
                guard added else { self.notice(self.rpcn.notice ?? ""); return }
                self.notice(asked ? String(localized: "You and \(name) are friends now.") : String(localized: "Friend request sent to \(name)."))
            }
        }
    }

    /// The model drops the row when the server agrees; otherwise its refusal is told.
    private func answerFriend(_ row: RpcnState.FriendRow, _ action: RpcnModel.FriendAction, done: String) {
        Task {
            await rpcn.answer(row, action)
            let kept = rpcn.friends.contains { $0.name == row.name && $0.kind == row.kind }
            notice(kept ? rpcn.friendsNotice ?? "" : done)
            await rpcn.readFriends()
        }
    }

    // MARK: - Account Management

    /// Who is signed in and on which server, signing in, the avatar, the servers, and the rest of the account.
    private func accountFolder(_ section: inout XmbSection) -> XmbNode {
        let icon = XmbIcon.firmware(rco: XmbRco.psn, name: "item_tex_account", symbol: "person.crop.circle")
        // A saved name without its password, after Sign Out or a reinstall, needs a sign-in, not a check.
        let configured = rpcn.accountState != .form
        let host = rpcn.config?.host ?? "np.rpcs3.net"
        let server = String(localized: "Server: \(host)")
        let state = rpcn.accountState == .signedIn ? Self.signedInWord
            : configured ? String(localized: "Account saved") : String(localized: "Not set up")
        let who = rpcn.accountName

        var whoRow = XmbNode(id: "rpcn.account.who", title: who.isEmpty ? String(localized: "No RPCN account yet") : who,
                             subtitle: state + "   " + server, icon: icon, kind: .action)
        if configured {
            whoRow.options = [.init(id: "check", title: String(localized: "Check sign-in"))]
            section.options[whoRow.id] = { [weak self] _ in self?.checkSignIn() }
        }
        whoRow = section.action(whoRow) { [weak self] in
            if configured { self?.checkSignIn() } else { self?.signIn() }
        }
        var rows = [whoRow]
        rows.append(section.action(XmbNode(
            id: "rpcn.account.signIn", title: configured ? String(localized: "Switch account") : String(localized: "Sign in"),
            subtitle: nil, icon: icon, kind: .action)) { [weak self] in self?.signIn() })
        // Android has no Sign Out here; the app's RPCN pane does.
        if configured {
            rows.append(section.action(XmbNode(id: "rpcn.account.signOut", title: String(localized: "Sign Out"), subtitle: nil,
                                               icon: icon, kind: .action)) { [weak self] in
                guard let self else { return }
                ask(String(localized: "Sign out of RPCN?") + "\n"
                    + String(localized: "ARMSX3 forgets your password on this device. You'll need it to sign in again."),
                    buttons: [String(localized: "Sign Out"), String(localized: "Cancel")], focus: 1) { [weak self] button in
                    guard button == 0, let self else { return }
                    Task { await self.rpcn.signOut() }
                }
            })
        }
        rows.append(avatarFolder(&section, icon: icon))
        rows.append(serversFolder(&section, icon: icon, server: server))
        rows.append(accountSettingsFolder(&section, icon: icon))
        let summary = [who, state].filter { !$0.isEmpty }.joined(separator: "   ")
        // The player's own avatar on it, as the console's column leads with its user.
        return XmbNode(id: "rpcn.account", title: firmware.text(XmbRco.full, "msg_account_manage", String(localized: "Account Management")),
                       subtitle: summary, icon: avatarFile.map { .game(path: $0) } ?? icon, kind: .folder(rows))
    }

    private static var signedInWord: String { String(localized: "Signed in") }

    private func checkSignIn() {
        Task {
            rpcn.notice = nil
            await rpcn.retry()
            let state = rpcn.accountState
            notice(state == .signedIn ? Self.signedInWord
                : rpcn.notice ?? (state == .form ? String(localized: "Not set up") : state.word), error: state != .signedIn)
        }
    }

    private func signIn() {
        askLogin { [weak self] user, pass in
            guard let self else { return }
            Task {
                let ok = await self.rpcn.signIn(username: user, password: pass, token: nil, screen: .signIn)
                // The model's sentence says more on success too: the first sign-in turns online play on.
                self.notice(self.rpcn.notice ?? (ok ? Self.signedInWord : ""), error: !ok)
            }
        }
    }

    /// Username, then password, each typed on the XMB's keyboard. Nothing typed stops it.
    private func askLogin(initial: String? = nil, _ done: @escaping @MainActor (String, String) -> Void) {
        type(String(localized: "Username"), initial: initial ?? rpcn.accountName) { [weak self] user in
            guard !user.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            self?.type(String(localized: "Password"), secure: true) { pass in
                if !pass.isEmpty { done(user.trimmingCharacters(in: .whitespacesAndNewlines), pass) }
            }
        }
    }

    /// The model's refusal when it failed, `done` when it worked, each with Android's tone.
    private func tell(_ ok: Bool, _ done: String) {
        notice(ok ? done : rpcn.notice ?? done, error: !ok)
    }

    /// The server, from the saved ones; adding one is typed on the XMB's keyboard.
    private func serversFolder(_ section: inout XmbSection, icon: XmbIcon, server: String) -> XmbNode {
        let title = String(localized: "Saved servers")
        let servers = rpcn.servers
        let host = rpcn.config?.host
        var rows: [XmbNode] = []
        if !servers.isEmpty {
            let labels = servers.map { $0.desc == $0.host || $0.desc.isEmpty ? $0.host : "\($0.desc) (\($0.host))" }
            let checked = servers.firstIndex { ServersPane.isChecked($0, host: host) } ?? 0
            section.picks["rpcn.server"] = { [weak self] index in
                guard let self, servers.indices.contains(index) else { return }
                Task {
                    await self.rpcn.selectServer(servers[index])
                    let failed = self.rpcn.serversNotice
                    self.notice(failed ?? String(localized: "Switched server. Check sign-in to try it."), error: failed != nil)
                }
            }
            rows.append(XmbNode(id: "rpcn.server", title: title, subtitle: server, icon: icon,
                                kind: .setting(choices: labels, selected: checked)))
        }
        rows.append(section.action(XmbNode(id: "rpcn.server.add", title: String(localized: "Add a server"), subtitle: nil, icon: icon,
                                           kind: .action)) { [weak self] in
            self?.type(String(localized: "Name for this server")) { [weak self] name in
                guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                self?.type(String(localized: "Server address")) { [weak self] address in
                    guard let self, !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    Task { self.tell(await self.rpcn.addServer(name: name, address: address), String(localized: "Server saved to the list.")) }
                }
            }
        })
        for entry in servers where entry.host.caseInsensitiveCompare("np.rpcs3.net") != .orderedSame {
            rows.append(section.action(XmbNode(id: "rpcn.server.remove." + entry.host, title: String(localized: "Remove: \(entry.desc)"),
                                               subtitle: entry.host, icon: icon, kind: .action)) { [weak self] in
                guard let self else { return }
                Task {
                    await self.rpcn.removeServer(entry)
                    let gone = !self.rpcn.servers.contains { $0.host == entry.host }
                    self.notice(self.rpcn.serversNotice ?? String(localized: "Server removed."), error: !gone)
                }
            })
        }
        rows.append(section.action(XmbNode(id: "rpcn.server.reset", title: String(localized: "Reset to official"), subtitle: nil, icon: icon,
                                           kind: .action)) { [weak self] in
            guard let self else { return }
            Task {
                await self.rpcn.resetServers()
                let reset = self.rpcn.servers.allSatisfy(ServersPane.isOfficial)
                self.notice(reset ? String(localized: "Back to the official server (np.rpcs3.net).") : self.rpcn.serversNotice ?? "",
                            error: !reset)
            }
        })
        let description = String(localized: "Most people play on the official server. Add another to switch between them; the official one always stays.")
        return XmbNode(id: "rpcn.account.servers", title: title, subtitle: server + "   " + description, icon: icon, kind: .folder(rows))
    }

    /// The rest of the account, each step typed on the XMB's keyboard: a new account, its code from the email,
    /// a forgotten password and the code for a new one, IPv6, and deleting synced trophies.
    private func accountSettingsFolder(_ section: inout XmbSection, icon: XmbIcon) -> XmbNode {
        let create = section.action(XmbNode(id: "rpcn.more.create", title: String(localized: "Create an account"),
                                            subtitle: String(localized: "3 to 16 letters, numbers, - or _."), icon: icon, kind: .action)) {
            [weak self] in
            self?.askLogin(initial: "") { [weak self] user, pass in
                self?.type(String(localized: "Email address")) { [weak self] email in
                    guard let self, !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    Task {
                        self.tell(await self.rpcn.createAccount(username: user, password: pass, email: email),
                                  String(localized: "Account created. RPCN emailed you a code to activate it."))
                    }
                }
            }
        }
        let activate = section.action(XmbNode(id: "rpcn.more.activate", title: String(localized: "Have an activation code?"),
                                              subtitle: nil, icon: icon, kind: .action)) { [weak self] in
            self?.askLogin { [weak self] user, pass in
                self?.type(String(localized: "Code from the email")) { [weak self] code in
                    guard let self, !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    Task { self.tell(await self.rpcn.activate(username: user, password: pass, code: code), Self.signedInWord) }
                }
            }
        }
        let resend = section.action(XmbNode(id: "rpcn.more.resend", title: String(localized: "Email the code again"),
                                            subtitle: nil, icon: icon, kind: .action)) { [weak self] in
            self?.askLogin { [weak self] user, pass in
                guard let self else { return }
                // The model words both outcomes itself.
                Task {
                    await self.rpcn.resend(username: user, password: pass)
                    let sent = String(localized: "RPCN sent a new code to your email.")
                    self.notice(self.rpcn.notice ?? "", error: self.rpcn.notice != sent)
                }
            }
        }
        let forgot = section.action(XmbNode(id: "rpcn.more.forgot", title: String(localized: "Forgot password?"),
                                            subtitle: nil, icon: icon, kind: .action)) { [weak self] in
            guard let self else { return }
            type(String(localized: "Username"), initial: rpcn.accountName) { [weak self] user in
                guard !user.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                self?.type(String(localized: "Email address")) { [weak self] email in
                    guard let self, !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    Task {
                        self.tell(await self.rpcn.askReset(username: user, email: email),
                                  String(localized: "A reset code is on its way to your email."))
                    }
                }
            }
        }
        let reset = section.action(XmbNode(id: "rpcn.more.reset", title: String(localized: "Choose a new password"),
                                           subtitle: nil, icon: icon, kind: .action)) { [weak self] in
            guard let self else { return }
            type(String(localized: "Username"), initial: rpcn.accountName) { [weak self] user in
                guard !user.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                self?.type(String(localized: "Reset code")) { [weak self] code in
                    guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    self?.type(String(localized: "New password"), secure: true) { [weak self] pass in
                        guard let self, !pass.isEmpty else { return }
                        Task {
                            let ok = await self.rpcn.resetPassword(username: user, code: code, password: pass)
                            self.notice(self.rpcn.notice ?? (ok ? Self.signedInWord : ""), error: !ok)
                        }
                    }
                }
            }
        }
        let ipv6On = rpcn.config?.ipv6 ?? false
        section.picks["rpcn.more.ipv6"] = { [weak self] choice in
            guard let self else { return }
            Task { await self.rpcn.setIpv6(choice == 0) }
        }
        let ipv6 = XmbNode(id: "rpcn.more.ipv6", title: String(localized: "Experimental IPv6"), subtitle: ipv6On ? on : off,
                           icon: icon, kind: .setting(choices: [on, off], selected: ipv6On ? 0 : 1),
                           description: String(localized: "Use IPv6 for RPCN where available. Experimental upstream; leave off unless the server asks for it."))
        let trophies = section.action(XmbNode(
            id: "rpcn.more.trophies", title: String(localized: "Delete synced trophies"),
            subtitle: String(localized: "Removes every trophy this account has uploaded to RPCN. Your trophies on this device are not touched."),
            icon: icon, kind: .action)) { [weak self] in
            guard let self else { return }
            ask(String(localized: "The copy on RPCN cannot be recovered. Trophies on this device stay."),
                buttons: [String(localized: "Delete"), String(localized: "Cancel")], focus: 1) { [weak self] button in
                guard button == 0, let self else { return }
                Task {
                    await self.rpcn.deleteTrophies()
                    let deleted = String(localized: "Deleted your trophies on RPCN.")
                    self.notice(self.rpcn.trophiesNotice ?? "", error: self.rpcn.trophiesNotice != deleted)
                }
            }
        }
        return XmbNode(id: "rpcn.account.more", title: String(localized: "RPCN Account Settings"),
                       subtitle: String(localized: "Create an account, reset a password or choose a server"), icon: icon,
                       kind: .folder([create, activate, resend, forgot, reset, ipv6, trophies]))
    }
}
