// SPDX-License-Identifier: GPL-2.0-only
// The RPCN account screens. Typed fields stay in each screen's own state, so Back discards them.

import SwiftUI

struct RpcnField: View {
    let label: String
    @Binding var text: String
    var secure = false
    let contentType: UITextContentType
    let id: String
    let focus: FocusState<RpcnFormField?>.Binding
    let field: RpcnFormField
    /// `id` + ".show" unless the caller names it.
    var showID: String? = nil
    var hint: String? = nil
    var submitLabel: SubmitLabel = .next
    var onSubmit: () -> Void = {}
    @State private var shows = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 8))
        VStack(alignment: .leading, spacing: 4) {
            layout {
                Text(verbatim: label).foregroundStyle(OverlayTheme.textPrimary).fixedSize()
                HStack(spacing: 0) {
                    Group {
                        let prompt = Text("Required").foregroundStyle(OverlayTheme.textSecondary)
                        if secure && !shows {
                            SecureField(text: $text, prompt: prompt) { Text(verbatim: label) }
                        } else {
                            TextField(text: $text, prompt: prompt) { Text(verbatim: label) }
                        }
                    }
                    .textContentType(contentType)
                    .keyboardType(contentType == .emailAddress ? .emailAddress : .default)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(submitLabel)
                    .onSubmit(onSubmit)
                    .focused(focus, equals: field)
                    .foregroundStyle(OverlayTheme.textPrimary)
                    if secure {
                        Button { shows.toggle() } label: {
                            Image(systemName: shows ? "eye.slash" : "eye")
                                .foregroundStyle(OverlayTheme.textSecondary)
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(shows ? Text("Hide Password") : Text("Show Password"))
                        .controllerAccessibilityActionTarget(id: showID ?? id + ".show",
                                                             label: shows ? String(localized: "Hide Password") : String(localized: "Show Password"),
                                                             action: { shows.toggle() })
                    }
                }
            }
            // The minimum height covers the row and its hint together, so the hint hangs under its label.
            if let hint { rpcnCaption(hint) }
        }
        .frame(minHeight: 44)
        .controllerAccessibilityActionTarget(id: id, label: label, action: { focus.wrappedValue = field })
    }
}

@MainActor func rpcnCaption(_ text: String) -> some View {
    Text(verbatim: text)
        .font(.subheadline)
        .foregroundStyle(OverlayTheme.textSecondary)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
}

@MainActor @ViewBuilder func rpcnNoticeLine(_ notice: String?, busy: Bool) -> some View {
    if busy {
        HStack(spacing: 4) {
            ProgressView().controlSize(.small).accessibilityHidden(true)
            Text("Contacting RPCN…")
        }
        .font(.subheadline)
        .foregroundStyle(OverlayTheme.textSecondary)
        .frame(maxWidth: .infinity, alignment: .leading)
    } else if let notice {
        rpcnCaption(notice)
    }
}

/// While a request runs it keeps its title and is disabled.
@MainActor func rpcnPrimary(_ title: String, id: String, action: @escaping () -> Void) -> some View {
    Button(action: action) { Text(verbatim: title).frame(maxWidth: .infinity) }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(RpcnModel.shared.busy)
        .controllerAccessibilityActionTarget(id: id, label: title, action: action)
}

/// A refusal keeps what was typed except the password.
struct CreateAccountPane: View {
    var controller = SettingsControllerContext()
    @State private var username = ""
    @State private var password = ""
    @State private var email = ""
    @State private var created: (username: String, password: String)?
    @FocusState private var focus: RpcnFormField?

    var body: some View {
        if let created {
            ActivateAccountPane(controller: controller, carried: created)
        } else {
            SettingsPage {
                rpcnCaption(String(localized: "Free. RPCN emails you a code that activates the account."))
                OverlaySectionCard {
                    RpcnField(label: String(localized: "Username"), text: $username, contentType: .username,
                              id: "settings.rpcn.field.username", focus: $focus, field: .username,
                              hint: String(localized: "3 to 16 letters, numbers, - or _."), onSubmit: { focus = .password })
                    RpcnField(label: String(localized: "Password"), text: $password, secure: true, contentType: .newPassword,
                              id: "settings.rpcn.field.password", focus: $focus, field: .password, onSubmit: { focus = .email })
                    RpcnField(label: String(localized: "Email"), text: $email, contentType: .emailAddress,
                              id: "settings.rpcn.field.email", focus: $focus, field: .email,
                              hint: String(localized: "RPCN uses it only to send the activation code and password reset codes."),
                              submitLabel: .go, onSubmit: create)
                }
                .disabled(RpcnModel.shared.busy)
                VStack(spacing: 8) {
                    rpcnPrimary(String(localized: "Create Account"), id: "settings.rpcn.create.go", action: create)
                    rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
                }
            }
            .navigationTitle(String(localized: "Create Account"))
            .navigationBarTitleDisplayMode(.inline)
            // The first empty field is Username: every field is empty when the screen opens.
            .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.create",
                                               onBack: { rpcnBack(focus: $focus) }, onPreviousTab: controller.onPreviousTab,
                                               onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                               preferredInitialFocusLabel: "settings.rpcn.field.username")
        }
    }

    private func create() {
        focus = nil
        let typed = (username: username, password: password, email: email)
        // A refusal clears the password; an empty field sends nothing, so it keeps everything.
        let sends = RpcnState.missingInput(username: typed.username, password: typed.password, email: typed.email) == nil
        Task {
            if await RpcnModel.shared.createAccount(username: typed.username, password: typed.password, email: typed.email) {
                created = (typed.username.trimmingCharacters(in: .whitespacesAndNewlines), typed.password)
            } else if sends {
                password = ""
            }
        }
    }
}

/// The username and password are asked for only when nothing was carried here.
struct ActivateAccountPane: View {
    var controller = SettingsControllerContext()
    /// In memory only; nil when the pane opened this screen.
    @State private var carried: (username: String, password: String)?
    @State private var username = ""
    @State private var password = ""
    @State private var code = ""
    @FocusState private var focus: RpcnFormField?

    init(controller: SettingsControllerContext = SettingsControllerContext(), carried: (username: String, password: String)? = nil) {
        self.controller = controller
        _carried = State(initialValue: carried)
    }

    var body: some View {
        SettingsPage {
            if carried != nil { rpcnCaption(String(localized: "Account created. Check your email for the code.")) }
            rpcnCaption(String(localized: "Enter the code RPCN emailed you. It activates the account and signs you in."))
            OverlaySectionCard {
                if carried == nil {
                    RpcnField(label: String(localized: "Username"), text: $username, contentType: .username,
                              id: "settings.rpcn.field.username", focus: $focus, field: .username, onSubmit: { focus = .password })
                    RpcnField(label: String(localized: "Password"), text: $password, secure: true, contentType: .password,
                              id: "settings.rpcn.field.password", focus: $focus, field: .password, onSubmit: { focus = .code })
                }
                RpcnField(label: String(localized: "Code"), text: $code, contentType: .oneTimeCode,
                          id: "settings.rpcn.field.code", focus: $focus, field: .code, submitLabel: .go, onSubmit: activate)
            }
            .disabled(RpcnModel.shared.busy)
            VStack(spacing: 8) {
                rpcnPrimary(String(localized: "Activate and Sign In"), id: "settings.rpcn.activate.go", action: activate)
                rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
            }
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.rpcn.activate.resend", label: "Email the Code Again",
                                 systemImage: nil) { resend() }
            }
            .disabled(RpcnModel.shared.busy)
        }
        .navigationTitle(String(localized: "Activate Account"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.activate",
                                           onBack: { rpcnBack(focus: $focus) }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: "settings.rpcn.field.code")
    }

    private var details: (username: String, password: String) { carried ?? (username, password) }

    private func activate() {
        focus = nil
        let details = details, typed = code
        Task { await RpcnModel.shared.activate(username: details.username, password: details.password, code: typed) }
    }

    private func resend() {
        focus = nil
        let details = details
        Task { await RpcnModel.shared.resend(username: details.username, password: details.password) }
    }
}

struct ResetPasswordPane: View {
    var controller = SettingsControllerContext()
    @State private var username = ""
    @State private var email = ""
    /// Nil while this screen shows.
    @State private var next: (username: String, sent: Bool)?
    @FocusState private var focus: RpcnFormField?

    var body: some View {
        if let next {
            NewPasswordPane(controller: controller, username: next.username, sent: next.sent)
        } else {
            SettingsPage {
                rpcnCaption(String(localized: "RPCN emails a reset code to the address on the account."))
                OverlaySectionCard {
                    RpcnField(label: String(localized: "Username"), text: $username, contentType: .username,
                              id: "settings.rpcn.field.username", focus: $focus, field: .username, onSubmit: { focus = .email })
                    RpcnField(label: String(localized: "Email"), text: $email, contentType: .emailAddress,
                              id: "settings.rpcn.field.email", focus: $focus, field: .email, submitLabel: .go, onSubmit: ask)
                }
                .disabled(RpcnModel.shared.busy)
                VStack(spacing: 8) {
                    rpcnPrimary(String(localized: "Email Me a Code"), id: "settings.rpcn.reset.ask", action: ask)
                    rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
                }
                OverlaySectionCard {
                    OverlayActionRow(controllerNavigationID: "settings.rpcn.reset.havecode", label: "I Have a Code",
                                     systemImage: nil) { haveCode() }
                }
                .disabled(RpcnModel.shared.busy)
            }
            .navigationTitle(String(localized: "Reset Password"))
            .navigationBarTitleDisplayMode(.inline)
            // The first empty field is Username: both are empty when the screen opens.
            .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.reset",
                                               onBack: { rpcnBack(focus: $focus) }, onPreviousTab: controller.onPreviousTab,
                                               onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                               preferredInitialFocusLabel: "settings.rpcn.field.username")
        }
    }

    private var trimmedUsername: String { username.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func ask() {
        focus = nil
        let name = username, mail = email
        Task {
            if await RpcnModel.shared.askReset(username: name, email: mail) { next = (trimmedUsername, true) }
        }
    }

    private func haveCode() {
        focus = nil
        RpcnModel.shared.notice = nil
        next = (trimmedUsername, false)
    }
}

struct NewPasswordPane: View {
    var controller = SettingsControllerContext()
    let sent: Bool
    @State private var username: String
    @State private var code = ""
    @State private var password = ""
    @FocusState private var focus: RpcnFormField?

    init(controller: SettingsControllerContext = SettingsControllerContext(), username: String, sent: Bool) {
        self.controller = controller
        self.sent = sent
        _username = State(initialValue: username)
    }

    var body: some View {
        SettingsPage {
            if sent { rpcnCaption(String(localized: "RPCN sent a reset code to your email.")) }
            rpcnCaption(String(localized: "Enter the code from the email and the password you want."))
            OverlaySectionCard {
                RpcnField(label: String(localized: "Username"), text: $username, contentType: .username,
                          id: "settings.rpcn.field.username", focus: $focus, field: .username, onSubmit: { focus = .code })
                RpcnField(label: String(localized: "Code"), text: $code, contentType: .oneTimeCode,
                          id: "settings.rpcn.field.code", focus: $focus, field: .code, onSubmit: { focus = .newPassword })
                RpcnField(label: String(localized: "New Password"), text: $password, secure: true, contentType: .newPassword,
                          id: "settings.rpcn.field.newpassword", focus: $focus, field: .newPassword, submitLabel: .go,
                          onSubmit: save)
            }
            .disabled(RpcnModel.shared.busy)
            VStack(spacing: 8) {
                rpcnPrimary(String(localized: "Save and Sign In"), id: "settings.rpcn.reset.go", action: save)
                rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
            }
        }
        .navigationTitle(String(localized: "New Password"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.newpassword",
                                           onBack: { rpcnBack(focus: $focus) }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: "settings.rpcn.field.code")
    }

    private func save() {
        focus = nil
        let name = username, entered = code, typed = password
        Task { await RpcnModel.shared.resetPassword(username: name, code: entered, password: typed) }
    }
}

/// A focused field lets the keyboard go first; the next press goes back to the pane.
@MainActor func rpcnBack(focus: FocusState<RpcnFormField?>.Binding) -> Bool {
    if focus.wrappedValue != nil {
        focus.wrappedValue = nil
    } else {
        RpcnModel.shared.form = nil
    }
    return true
}
