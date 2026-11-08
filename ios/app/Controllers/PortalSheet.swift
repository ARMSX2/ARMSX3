// SPDX-License-Identifier: GPL-2.0-only
// The Portal sheet. Touch only, because Load from Files opens the system picker; no file name is logged.

import Observation
import SwiftUI
import UniformTypeIdentifiers

@MainActor @Observable final class PortalSheetModel {
    // The portal shown, by PortalStore.Portal id.
    var portalID = 0 {
        didSet { Task { await refresh() } }
    }
    // "Unknown figure" when no table holds its ID, nil when the slot is empty.
    private(set) var names: [String?] = []
    var loaded: [Bool] { names.map { $0 != nil } }
    let documents: URL

    nonisolated init(documents: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]) {
        self.documents = documents
    }

    var portal: PortalStore.Portal { PortalStore.portals.first { $0.id == portalID } ?? PortalStore.portals[0] }

    func refresh() async {
        let portal = self.portal
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.portalList(portal.id) { continuation.resume(returning: ($0, $1)) }
        }
        var held = [String?](repeating: nil, count: portal.slots)
        if status == 0, let json, let rows = (try? JSONSerialization.jsonObject(with: json)) as? [[String: Any]] {
            for row in rows {
                if let slot = row["slot"] as? Int, held.indices.contains(slot), let id = row["id"] as? Int {
                    held[slot] = PortalNames.name(portal: portal.id, id: id, variant: row["var"] as? Int ?? 0)
                        ?? String(localized: "Unknown figure")
                }
            }
        }
        if portal.id == portalID { names = held }
    }

    // The façade's status, or -3 when the copy was refused (not the portal's size, or not a file).
    func load(picked source: URL, slot: Int) async -> Int {
        let portal = self.portal
        let scoped = source.startAccessingSecurityScopedResource()
        let copy = PortalStore.copyIn(source, portal: portal, documents: documents)
        if scoped { source.stopAccessingSecurityScopedResource() }
        var status = -3
        if let copy {
            status = await withCheckedContinuation { continuation in
                Rpcs3Bridge.portalLoad(portal.id, slot: slot, path: copy.path) { continuation.resume(returning: $0) }
            }
        }
        await refresh()
        return status
    }

    // The create's status when it failed, else the load's; -3 when no free file could be named.
    func create(_ choice: (name: String, figure: Int, variant: Int), slot: Int) async -> Int {
        let portal = self.portal
        var status = -3
        if let file = PortalStore.freeFile(choice.name, ext: "bin", portal: portal, documents: documents) {
            status = await withCheckedContinuation { continuation in
                Rpcs3Bridge.portalCreate(portal.id, figure: choice.figure, variant: choice.variant, path: file.path) {
                    continuation.resume(returning: $0)
                }
            }
            if status == 0 {
                status = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.portalLoad(portal.id, slot: slot, path: file.path) { continuation.resume(returning: $0) }
                }
            }
        }
        await refresh()
        return status
    }

    func remove(slot: Int) async -> Int {
        let portal = self.portal
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.portalRemove(portal.id, slot: slot) { continuation.resume(returning: $0) }
        }
        await refresh()
        return status
    }

    // What the player is told when a load fails, by the façade's status.
    static func message(for status: Int) -> String {
        switch status {
        case -3: return String(localized: "That file isn't a figure for this portal, or it's already on a slot.")
        case -9: return String(localized: "The portal has no free place for another figure.")
        default: return String(localized: "Couldn't load the figure.")
        }
    }
}

struct PortalSheet: View {
    let onDone: () -> Void
    @State private var model: PortalSheetModel
    @State private var pickingSlot: Int?
    @State private var creating: CreateTarget?
    @State private var failure: String?

    init(model: PortalSheetModel = PortalSheetModel(), onDone: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Portal", selection: $model.portalID) {
                        ForEach(PortalStore.portals) { portal in
                            Text(portal.name).tag(portal.id)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                }
                Section {
                    ForEach(Array(model.names.enumerated()), id: \.offset) { slot, name in
                        slotRow(slot, name: name)
                    }
                } footer: {
                    Text("Figures are copied into Files › ARMSX3 › Figures; the game saves into the copy.")
                }
            }
            .navigationTitle("Portal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDone) } }
            .fileImporter(isPresented: Binding(get: { pickingSlot != nil }, set: { if !$0 { pickingSlot = nil } }),
                          allowedContentTypes: [.data]) { result in
                guard let slot = pickingSlot else { return }
                pickingSlot = nil
                if case .success(let url) = result {
                    Task {
                        let status = await model.load(picked: url, slot: slot)
                        if status != 0 { failure = PortalSheetModel.message(for: status) }
                    }
                }
            }
            .sheet(item: $creating) { target in
                CreateFigureList(portal: model.portal, onPick: { choice in
                    creating = nil
                    Task {
                        let status = await model.create(choice, slot: target.slot)
                        if status != 0 { failure = PortalSheetModel.message(for: status) }
                    }
                }, onCancel: { creating = nil })
            }
            .alert(failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
        .preferredColorScheme(.dark)
        .task { await model.refresh() }
    }

    // Two lines, because three actions do not fit one at iPhone width.
    private func slotRow(_ slot: Int, name: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("Slot \(slot + 1)")
                Text(name ?? String(localized: "Empty"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Button("Remove", role: .destructive) { Task { _ = await model.remove(slot: slot) } }
                    .buttonStyle(.borderless)
                    .disabled(name == nil)
            }
            HStack(spacing: 20) {
                Button("Load from Files") { pickingSlot = slot }
                    .buttonStyle(.borderless)
                Button("Create Blank Figure") { creating = CreateTarget(slot: slot) }
                    .buttonStyle(.borderless)
            }
            .font(.subheadline)
            .lineLimit(1)
        }
        .frame(minHeight: 44)
    }
}

// `.sheet(item:)` needs an Identifiable.
struct CreateTarget: Identifiable {
    let slot: Int
    var id: Int { slot }
}

// Choosing a name hands its +portalCreate arguments back; nothing here touches a file.
struct CreateFigureList: View {
    let portal: PortalStore.Portal
    let onPick: ((name: String, figure: Int, variant: Int)) -> Void
    let onCancel: () -> Void
    @State private var search = ""

    var body: some View {
        let all = Array(PortalNames.creatable(portal: portal.id).enumerated())
        let shown = search.isEmpty ? all : all.filter { $0.element.name.localizedCaseInsensitiveContains(search) }
        NavigationStack {
            List(shown, id: \.offset) { row in
                Button(row.element.name) { onPick(row.element) }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always))
            .navigationTitle("Create Blank Figure")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) } }
        }
        .preferredColorScheme(.dark)
    }
}
