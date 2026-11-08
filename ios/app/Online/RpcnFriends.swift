// SPDX-License-Identifier: GPL-2.0-only
// RPCN friends in Settings and in game. Names and presence words are remote input: plain Text only, never logged.

import SwiftUI

/// Requests are answered here only, through `.controllerPrompt`, which touch and a controller can both answer.
struct RpcnFriendsCard: View {
    @State private var model = RpcnModel.shared

    var body: some View {
        OverlaySectionCard(title: String(localized: "Friends")) {
            if Self.showsAdd(model.accountState) {
                OverlayActionRow(controllerNavigationID: "settings.rpcn.friends.add", label: "Add Friend", systemImage: nil,
                                 showsChevron: true) { model.form = .addFriend }
            }
            if model.accountState == .signedIn {
                ForEach(model.friends, id: \.name) { row in
                    // keepsTrailingValue false: a long game name is cut before the friend's name is.
                    OverlayActionRow(controllerNavigationID: Self.target(row.name), label: row.name, systemImage: nil,
                                     trailingValue: row.value, keepsTrailingValue: false) { model.friendPrompt = row }
                        .accessibilityLabel(Text(verbatim: row.name))
                        .accessibilityValue(Text(verbatim: row.value))
                        .accessibilityHint(Self.hint(row.kind))
                }
            }
            if let caption = Self.caption(model.accountState, rows: model.friends.count) {
                HStack(spacing: 4) {
                    if model.accountState == .opening { ProgressView().controlSize(.small).accessibilityHidden(true) }
                    rpcnCaption(caption)
                }
            }
            // Online Play off holds the caption alone.
            if model.accountState != .off { rpcnNoticeLine(model.friendsNotice, busy: false) }
        }
        // Accept is an add; Decline, Withdraw Request and Remove Friend are a remove.
        .controllerPrompt(String(localized: "\(promptName) wants to be your friend"), isPresented: prompting(.received),
                          actions: [ControllerPrompt.Action(title: String(localized: "Accept"), run: answer(.accept)),
                                    ControllerPrompt.Action(title: String(localized: "Decline"), run: answer(.decline)),
                                    .cancel])
        .controllerPrompt(String(localized: "Withdraw your request to \(promptName)?"), isPresented: prompting(.sent),
                          actions: [ControllerPrompt.Action(title: String(localized: "Withdraw Request"), run: answer(.withdraw)),
                                    ControllerPrompt.Action(title: String(localized: "Keep"), isCancel: true)])
        .controllerPrompt(String(localized: "Remove \(promptName) from your friends?"), isPresented: prompting(.online, .offline),
                          message: String(localized: "To be friends again, you both have to add each other."),
                          actions: [ControllerPrompt.Action(title: String(localized: "Remove Friend"), isDestructive: true,
                                                            run: answer(.remove)),
                                    ControllerPrompt.Action(title: String(localized: "Keep"), isCancel: true)])
        .onChange(of: model.friendsNotice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    private var promptName: String { model.friendPrompt?.name ?? "" }

    /// Answering the prompt in any way lets the row go.
    private func prompting(_ kinds: RpcnState.FriendRow.Kind...) -> Binding<Bool> {
        Binding(get: { model.friendPrompt.map { kinds.contains($0.kind) } ?? false },
                set: { if !$0 { model.friendPrompt = nil } })
    }

    /// Taken when the prompt was raised.
    private func answer(_ action: RpcnModel.FriendAction) -> @MainActor () -> Void {
        let row = model.friendPrompt
        return { if let row { Task { await model.answer(row, action) } } }
    }

    static func target(_ name: String) -> String { "settings.rpcn.friend." + name }

    static func showsAdd(_ state: RpcnState.AccountState) -> Bool { state != .off }

    static func caption(_ state: RpcnState.AccountState, rows: Int) -> String? {
        switch state {
        case .off: return String(localized: "Turn on Online Play to see your friends.")
        case .opening: return String(localized: "Connecting to RPCN…")
        // The reason is already in the Account card.
        case .failed: return String(localized: "Friends show here once ARMSX3 is signed in.")
        case .signedIn, .form: return rows == 0 ? String(localized: "No friends yet. Add someone by their RPCN username.") : nil
        }
    }

    static func hint(_ kind: RpcnState.FriendRow.Kind) -> Text {
        switch kind {
        case .received: return Text("Accept or decline.")
        case .sent: return Text("Withdraw the request.")
        case .online, .offline: return Text("Asks whether to remove this friend.")
        }
    }
}

struct AddFriendPane: View {
    var controller = SettingsControllerContext()
    @State private var username = ""
    @FocusState private var focus: RpcnFormField?

    var body: some View {
        SettingsPage {
            rpcnCaption(String(localized: "Enter their RPCN username. You're friends once they add you back."))
            OverlaySectionCard {
                RpcnField(label: String(localized: "Username"), text: $username, contentType: .nickname,
                          id: "settings.rpcn.field.friend", focus: $focus, field: .username, submitLabel: .send,
                          onSubmit: send)
            }
            .disabled(RpcnModel.shared.busy)
            VStack(spacing: 8) {
                rpcnPrimary(String(localized: "Send Request"), id: "settings.rpcn.friends.send", action: send)
                rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
            }
        }
        .navigationTitle(String(localized: "Add Friend"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.addfriend",
                                           onBack: { rpcnBack(focus: $focus) }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: "settings.rpcn.field.friend")
        // The pane kept its session for this request; leaving any other way lets it go, as leaving the pane does.
        .onDisappear {
            if RpcnModel.shared.form == .addFriend { Task { await RpcnModel.shared.setPaneVisible(false) } }
        }
    }

    private func send() {
        focus = nil
        let typed = username
        Task { await RpcnModel.shared.addFriend(typed) }
    }
}


/// Nothing is asked in game; Join hands the invite to the game, whose bridge checks it again.
struct FriendsPanel: View {
    let invites: [RpcnSession.Invite]
    let onlineLine: String?
    let onJoined: () -> Void
    let onDeclined: (UInt64) -> Void
    let onBack: () -> Void
    var controllerInput: MenuControllerInputRouter? = nil

    static let controllerScope = "game.quickmenu.friends"
    /// For the smoke scenes; a count only.
    static var drawnInvites = -1

    @State private var friends: [RpcnState.FriendRow] = []
    @State private var notice: String?

    var body: some View {
        OverlayPanelScaffold {
            VStack(spacing: 12) {
                OverlayHeader(systemImage: "person.2", title: "Friends", subtitle: onlineLine, compact: false)
                ScrollView {
                    VStack(spacing: 12) {
                        ForEach(invites) { invite in inviteCard(invite) }
                        // Read-only: requests are answered in Settings only.
                        OverlaySectionCard(title: String(localized: "Friends")) {
                            ForEach(friends, id: \.name) { row in StatusRow(label: row.name, value: row.value) }
                            if friends.isEmpty { rpcnCaption(String(localized: "No friends yet. Add them in Settings › RPCN.")) }
                        }
                        rpcnNoticeLine(notice, busy: false)
                    }
                }
                OverlaySectionCard {
                    OverlayActionRow(controllerNavigationID: "friends.back", label: "Back", systemImage: "chevron.backward",
                                     action: onBack)
                }
            }
            .padding()
        }
        .controllerAccessibilityNavigation(controllerInput: controllerInput, scopeKey: Self.controllerScope,
                                           onBack: { onBack(); return true },
                                           preferredInitialFocusLabel: invites.first.map { Self.joinTarget($0.id) } ?? "friends.back")
        .onAppear { Self.drawnInvites = invites.count }
        .onChange(of: notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
        // A peek-only read from the client the game holds.
        .task {
            while !Task.isCancelled {
                let (status, json) = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.rpcnFriends { continuation.resume(returning: ($0, $1)) }
                }
                if status == 0, let json {
                    friends = RpcnState.friendRows(json).filter { $0.kind == .online || $0.kind == .offline }
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    static func joinTarget(_ id: UInt64) -> String { "friends.invite.\(id).join" }

    private func inviteCard(_ invite: RpcnSession.Invite) -> some View {
        OverlaySectionCard(title: String(localized: "Invite from \(invite.from)")) {
            ForEach([invite.subject, invite.body].filter { !$0.isEmpty }, id: \.self) { line in
                Text(verbatim: line)
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            OverlayActionRow(controllerNavigationID: Self.joinTarget(invite.id), label: "Join Game") { join(invite) }
            OverlayActionRow(controllerNavigationID: "friends.invite.\(invite.id).decline", label: "Decline Invite") {
                decline(invite)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: [String(localized: "Invite from \(invite.from)"), invite.subject]
            .filter { !$0.isEmpty }.joined(separator: ", ")))
    }

    private func join(_ invite: RpcnSession.Invite) {
        Task {
            if await Self.accept(invite.id) {
                onJoined()
            } else {
                notice = String(localized: "That invite can't be used any more.")
            }
        }
    }

    static func accept(_ id: UInt64) async -> Bool {
        let code = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnAcceptInvite(id) { continuation.resume(returning: $0) }
        }
        return code == 0
    }

    private func decline(_ invite: RpcnSession.Invite) {
        let sender = invite.from
        Task {
            let code = await withCheckedContinuation { continuation in
                Rpcs3Bridge.rpcnDeclineInvite(invite.id) { continuation.resume(returning: $0) }
            }
            // Gone either way: declined, or no longer one the game can use. The next read puts back one that is.
            onDeclined(invite.id)
            notice = code == 0 ? String(localized: "Declined the invite from \(sender).")
                : String(localized: "That invite can't be used any more.")
        }
    }
}
