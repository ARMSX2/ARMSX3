// SPDX-License-Identifier: GPL-2.0-only
// Settings' RPCN pane. The typed password lives only in this view's state and is cleared after Sign In and when the pane goes.

import SwiftUI

struct RpcnPane: View {
    var controller = SettingsControllerContext()
    let onBack: @MainActor () -> Bool
    @State private var model = RpcnModel.shared
    @State private var password = ""
    @FocusState private var focus: RpcnFormField?

    var body: some View {
        SettingsPage {
            if model.configUnreadable && model.config == nil {
                SettingsMessage(heading: "Couldn't read your RPCN settings",
                                message: "Close Settings and open it again. If it keeps happening, restart ARMSX3.")
            } else {
                onlineCard
                if model.accountState == .form {
                    signInCard
                } else {
                    accountCard
                    RpcnFriendsCard()
                }
                // Only while Show advanced settings is on.
                if SettingsStore.shared.showAdvancedSettings {
                    OverlaySectionCard(title: String(localized: "Advanced")) {
                        // The server's name is a status value, so it is kept as a trailing value.
                        OverlayActionRow(controllerNavigationID: "settings.rpcn.servers", label: "Servers", systemImage: nil,
                                         trailingValue: model.currentServerName, keepsTrailingValue: true,
                                         showsChevron: true) { model.form = .servers }
                        Toggle(isOn: ipv6Binding) { Text("Use IPv6").foregroundStyle(OverlayTheme.textPrimary) }
                            .frame(minHeight: 44)
                            .controllerAccessibilityActionTarget(id: "settings.rpcn.ipv6", label: String(localized: "Use IPv6"),
                                                                 activationFeedback: .toggle(isOn: !ipv6Binding.wrappedValue),
                                                                 action: { ipv6Binding.wrappedValue.toggle() })
                        rpcnCaption(String(localized: "Experimental. Leave it off unless your server asks for it."))
                    }
                }
                // Only with the session open, because the delete is sent on it.
                if SettingsStore.shared.showAdvancedSettings && model.accountState == .signedIn {
                    OverlaySectionCard {
                        OverlayActionRow(controllerNavigationID: "settings.rpcn.trophies.delete", label: "Delete Synced Trophies",
                                         systemImage: nil, isDestructive: true) { model.confirmingTrophyDelete = true }
                        if let caption = model.trophiesNotice { rpcnCaption(caption) }
                    }
                    .padding(.top, 16)
                }
            }
        }
        .navigationTitle(SettingsPane.rpcn.title)
        .navigationBarTitleDisplayMode(.inline)
        // Circle lets a focused field's keyboard go first.
        .controllerAccessibilityNavigation(controllerInput: controller.input,
                                           scopeKey: "settings.pane.rpcn",
                                           onBack: { if focus != nil { focus = nil; return true }; return onBack() },
                                           onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           // A removed friend row's target unmounted, so focus goes to the row the model handed over.
                                           preferredInitialFocusLabel: model.focusAfterRemoval ?? initialFocus)
        // Sign Out asks first, through the prompt a controller can answer.
        .controllerPrompt(String(localized: "Sign out of RPCN?"), isPresented: $model.confirmingSignOut,
                          message: String(localized: "ARMSX3 forgets your password on this device. You'll need it to sign in again."),
                          actions: [ControllerPrompt.Action(title: String(localized: "Sign Out"), run: { Task { await model.signOut() } }),
                                    .cancel])
        // It cannot be undone, so it asks first.
        .controllerPrompt(String(localized: "Delete your trophies on RPCN?"), isPresented: $model.confirmingTrophyDelete,
                          message: String(localized: "Every trophy this account uploaded to RPCN is deleted, and this can't be undone. Trophies on this device stay."),
                          actions: [ControllerPrompt.Action(title: String(localized: "Delete Trophies"), isDestructive: true,
                                                            run: { Task { await model.deleteTrophies() } }),
                                    .cancel])
        .navigationDestination(item: $model.form) { form in
            switch form {
            case .create: CreateAccountPane(controller: controller)
            case .activate: ActivateAccountPane(controller: controller)
            case .reset: ResetPasswordPane(controller: controller)
            case .addFriend: AddFriendPane(controller: controller)
            case .servers: ServersPane(controller: controller)
            case .avatar: AvatarPane(controller: controller)
            }
        }
        // The pane holds the session while it is visible and Online Play is on; leaving it lets the session go.
        .task {
            await model.refresh()
            // Beside the session rather than before it, since an unanswered probe takes its whole 2 seconds.
            if !model.accountName.isEmpty { Task { await model.checkLocalNetwork() } }
            // Beside the session for the same reason.
            if !model.accountName.isEmpty { Task { await model.readCurrentAvatar() } }
            await model.setPaneVisible(true)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                if Task.isCancelled { break }
                await model.poll()
            }
        }
        // Add Friend pushed over the pane keeps the session, since its request is sent on it.
        .onDisappear {
            password = ""
            if model.form != .addFriend { Task { await model.setPaneVisible(false) } }
        }
        .onChange(of: model.accountState) { _, state in
            if state == .signedIn { AccessibilityNotification.Announcement(String(localized: "Signed in to RPCN.")).post() }
        }
        .onChange(of: model.notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
        .onChange(of: model.trophiesNotice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    private var ipv6Binding: Binding<Bool> {
        Binding(get: { model.config?.ipv6 ?? false }, set: { on in Task { await model.setIpv6(on) } })
    }

    private var initialFocus: String {
        switch model.accountState {
        case .form: return "settings.rpcn.signin"
        case .failed: return "settings.rpcn.retry"
        default: return "settings.rpcn.online"
        }
    }

    // Never confirmed.
    private var onlineCard: some View {
        OverlaySectionCard {
            Toggle(isOn: onlineBinding) { Text("Online Play").foregroundStyle(OverlayTheme.textPrimary) }
                .frame(minHeight: 44)
                .accessibilityHint(Text("While on, games that support online play sign in to RPCN."))
                .controllerAccessibilityActionTarget(id: "settings.rpcn.online", label: String(localized: "Online Play"),
                                                     activationFeedback: .toggle(isOn: !model.onlinePlay),
                                                     action: { onlineBinding.wrappedValue.toggle() })
            Text("While this is on, games that support online play sign in to RPCN when they start.")
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var onlineBinding: Binding<Bool> {
        Binding(get: { model.onlinePlay }, set: { on in Task { await model.setOnlinePlay(on) } })
    }

    private var accountCard: some View {
        OverlaySectionCard(title: String(localized: "Account")) {
            HStack(spacing: OverlayTheme.rowIconSpacing) {
                // Content, not a button: the Avatar row is the button.
                AvatarTile(path: model.currentAvatar?.path, size: 48)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: model.accountName)
                        .foregroundStyle(OverlayTheme.textPrimary)
                    // Warm only for "Couldn't sign in".
                    HStack(spacing: 4) {
                        if model.accountState == .opening {
                            ProgressView().controlSize(.small).accessibilityHidden(true)
                        }
                        Text(verbatim: model.accountState.word)
                    }
                    .font(.subheadline)
                    .foregroundStyle(model.accountState.warm ? OverlayTheme.warm : OverlayTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            // No notice line while Online Play is off.
            if model.accountState != .off { noticeLine }
            OverlayActionRow(controllerNavigationID: "settings.rpcn.avatar", label: "Avatar", systemImage: nil,
                             trailingValue: RpcnState.avatarValue(kind: model.currentAvatar?.kind ?? "default"),
                             // The value word is kept, not squeezed out.
                             keepsTrailingValue: true, showsChevron: true) {
                model.avatarNotice = nil
                model.form = .avatar
            }
                .accessibilityHint(Text("Opens the avatar list."))
            // Read-only, not a controller target.
            if model.localNetworkDenied {
                StatusRow(label: String(localized: "Local Network"), value: LocalNetwork.offValue, isProblem: true, fix: LocalNetwork.fix)
            }
            OverlayActionRow(controllerNavigationID: "settings.rpcn.signout", label: "Sign Out", systemImage: nil) {
                model.confirmingSignOut = true
            }
            // The only primary on card A: after a failed sign-in.
            if model.accountState == .failed {
                Button { Task { await model.retry() } } label: { Text("Try Again").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .padding(.top, 8)
                    .controllerAccessibilityActionTarget(id: "settings.rpcn.retry", label: String(localized: "Try Again"),
                                                         action: { Task { await model.retry() } })
            }
        }
    }

    private var signInCard: some View {
        OverlaySectionCard(title: String(localized: "Sign In")) {
            // A saved username with no Keychain login is not an error.
            Group {
                if model.missingSecret {
                    Text("Sign in again. ARMSX3 keeps your password on this device only, so it asks again after a reinstall or a new signature.")
                } else {
                    Text("Use your RPCN username and password, the same ones you use in RPCS3.")
                }
            }
                .font(.subheadline)
                .foregroundStyle(OverlayTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            RpcnField(label: String(localized: "Username"), text: $model.username, contentType: .username,
                      id: "settings.rpcn.field.username", focus: $focus, field: .username, onSubmit: { focus = .password })
            RpcnField(label: String(localized: "Password"), text: $password, secure: true, contentType: .password,
                      id: "settings.rpcn.field.password", focus: $focus, field: .password,
                      showID: "settings.rpcn.field.password.show", submitLabel: .go, onSubmit: { signIn() })
            Button { signIn() } label: { Text("Sign In").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.busy)
                .padding(.top, 8)
                .controllerAccessibilityActionTarget(id: "settings.rpcn.signin", label: String(localized: "Sign In"), action: { signIn() })
            noticeLine
            OverlayActionRow(controllerNavigationID: "settings.rpcn.create", label: "Create Account", systemImage: nil,
                             showsChevron: true) { model.form = .create }
            OverlayActionRow(controllerNavigationID: "settings.rpcn.forgot", label: "Forgot Password", systemImage: nil,
                             showsChevron: true) { model.form = .reset }
            OverlayActionRow(controllerNavigationID: "settings.rpcn.activate", label: "Enter Activation Code", systemImage: nil,
                             showsChevron: true) { model.form = .activate }
        }
        .disabled(model.busy)
    }

    private var noticeLine: some View { rpcnNoticeLine(model.notice, busy: model.busy) }

    private func signIn() {
        let typed = password
        focus = nil
        Task {
            await model.signIn(username: model.username, password: typed)
            if model.hasSecret { password = "" }
        }
    }
}

enum RpcnFormField: Hashable {
    case username, password, email, code, newPassword, serverName, serverAddress
}
