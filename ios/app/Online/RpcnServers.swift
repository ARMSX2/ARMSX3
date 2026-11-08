// SPDX-License-Identifier: GPL-2.0-only
// RPCN servers. The secret may be sent to a custom server, so adding one never chooses it, and server names never reach a log.

import SwiftUI

/// Choosing a server checks it and stays here; removing one or resetting the list asks first.
struct ServersPane: View {
    var controller = SettingsControllerContext()
    @State private var model = RpcnModel.shared

    var body: some View {
        SettingsPage {
            rpcnCaption(String(localized: "ARMSX3 signs in to the checked server. Most players use the official one."))
            OverlaySectionCard {
                ForEach(model.servers.indices, id: \.self) { index in
                    let server = model.servers[index]
                    // The address is the row's own caption, so the card's spacing falls between servers.
                    ChoiceRow(navigationID: Self.target(index), label: server.desc, checked: Self.isChecked(server, host: model.config?.host),
                              caption: server.host) {
                        Task { await model.selectServer(server) }
                    }
                    .accessibilityValue(Text(verbatim: server.host))
                    // The official server offers no remove, by touch, VoiceOver or Square.
                    .accessibilityActions {
                        if !Self.isOfficial(server) { Button("Remove Server") { model.serverPrompt = server } }
                    }
                    .contextMenu {
                        if !Self.isOfficial(server) {
                            Button("Remove Server", systemImage: "trash", role: .destructive) { model.serverPrompt = server }
                        }
                    }
                }
                // A little more space above the notice, so "Server added." does not read as one more server.
                if let notice = model.serversNotice { rpcnNoticeLine(notice, busy: false).padding(.top, 4) }
            }
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.rpcn.server.add", label: "Add Server", systemImage: nil,
                                 showsChevron: true) { model.addingServer = true }
            }
            OverlaySectionCard {
                OverlayActionRow(controllerNavigationID: "settings.rpcn.server.reset", label: "Reset Server List",
                                 systemImage: nil, isDestructive: true) { model.confirmingServerReset = true }
            }
            .padding(.top, 16)
        }
        .navigationTitle(String(localized: "Servers"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.servers",
                                           onBack: { model.form = nil; return true },
                                           onContextMenu: { id in offerRemove(id) },
                                           onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: Self.focusTarget(model.servers, host: model.config?.host))
        .navigationDestination(isPresented: $model.addingServer) { AddServerPane(controller: controller) }
        // After a removal the next press lands on the checked server, the screen's preferred focus.
        .controllerPrompt(String(localized: "Remove “\(promptName)”?"), isPresented: removing,
                          message: String(localized: "ARMSX3 forgets this server. You can add it again later."),
                          actions: [ControllerPrompt.Action(title: String(localized: "Remove Server"), isDestructive: true,
                                                            run: remove()),
                                    .cancel])
        .controllerPrompt(String(localized: "Reset the server list?"), isPresented: $model.confirmingServerReset,
                          message: String(localized: "Servers you added are removed, and ARMSX3 uses the official server."),
                          actions: [ControllerPrompt.Action(title: String(localized: "Reset List"), isDestructive: true,
                                                            run: { Task { await model.resetServers() } }),
                                    .cancel])
        .onChange(of: model.serversNotice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    private var promptName: String { model.serverPrompt?.desc ?? "" }

    private var removing: Binding<Bool> {
        Binding(get: { model.serverPrompt != nil }, set: { if !$0 { model.serverPrompt = nil } })
    }

    /// Taken when the prompt was raised.
    private func remove() -> @MainActor () -> Void {
        let server = model.serverPrompt
        return { if let server { Task { await model.removeServer(server) } } }
    }

    private func offerRemove(_ id: String) -> Bool {
        let prefix = "settings.rpcn.server."
        guard id.hasPrefix(prefix), let index = Int(id.dropFirst(prefix.count)), model.servers.indices.contains(index),
              !Self.isOfficial(model.servers[index]) else { return false }
        model.serverPrompt = model.servers[index]
        return true
    }

    static func target(_ index: Int) -> String { "settings.rpcn.server." + String(index) }

    /// RPCS3 compares addresses without regard to case.
    static func isChecked(_ server: RpcnState.Host, host: String?) -> Bool {
        host.map { server.host.caseInsensitiveCompare($0) == .orderedSame } ?? false
    }

    /// The first row when none is checked.
    static func focusTarget(_ servers: [RpcnState.Host], host: String?) -> String {
        target(servers.firstIndex { isChecked($0, host: host) } ?? 0)
    }

    static func isOfficial(_ server: RpcnState.Host) -> Bool {
        server.desc == "Official RPCN Server" && server.host == "np.rpcs3.net"
    }
}

/// Success pops back to Servers with the new server listed and not checked.
struct AddServerPane: View {
    var controller = SettingsControllerContext()
    @State private var name = ""
    @State private var address = ""
    @FocusState private var focus: RpcnFormField?

    var body: some View {
        SettingsPage {
            rpcnCaption(String(localized: "Give the server a name you'll recognise, and its address from the server's own page."))
            OverlaySectionCard {
                RpcnField(label: String(localized: "Name"), text: $name, contentType: .URL, id: "settings.rpcn.field.servername",
                          focus: $focus, field: .serverName, onSubmit: { focus = .serverAddress })
                RpcnField(label: String(localized: "Address"), text: $address, contentType: .URL,
                          id: "settings.rpcn.field.serveraddress", focus: $focus, field: .serverAddress, submitLabel: .done,
                          onSubmit: save)
            }
            .disabled(RpcnModel.shared.busy)
            VStack(spacing: 8) {
                rpcnPrimary(String(localized: "Add Server"), id: "settings.rpcn.server.save", action: save)
                rpcnNoticeLine(RpcnModel.shared.notice, busy: RpcnModel.shared.busy)
            }
        }
        .navigationTitle(String(localized: "Add Server"))
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane.rpcn.addserver",
                                           onBack: {
                                               if focus != nil { focus = nil } else { RpcnModel.shared.addingServer = false }
                                               return true
                                           },
                                           onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary,
                                           preferredInitialFocusLabel: "settings.rpcn.field.servername")
    }

    private func save() {
        focus = nil
        let typedName = name, typedAddress = address
        Task { await RpcnModel.shared.addServer(name: typedName, address: typedAddress) }
    }
}
