// SPDX-License-Identifier: GPL-2.0-only
// One title's game patches. The core's patch engine reads and writes every YAML file; nothing downloads until the player taps.

import CryptoKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct PatchRow: Decodable, Identifiable, Equatable {
    let hash: String
    let name: String
    let author: String
    let notes: String
    let version: String
    let appVersion: String
    let game: String
    let enabled: Bool

    var id: String { hash + "\u{1F}" + name }

    static func decode(_ json: Data) -> [PatchRow] {
        (try? JSONDecoder().decode([PatchRow].self, from: json)) ?? []
    }
}

enum PatchDownload {
    static let maxBody: Int64 = 32 * 1024 * 1024

    static func url(engine: String) -> URL? {
        var parts = URLComponents(string: "https://rpcs3.net/compatibility?patch&api=v1")
        parts?.queryItems?.append(URLQueryItem(name: "v", value: engine))
        return parts?.url
    }

    private struct Envelope: Decodable {
        let return_code: Int
        let version: String?
        let sha256: String?
        let patch: String?
    }

    // 0 written, -1 the server was not reached, -2 the envelope was refused, else the bridge's status.
    static func fetch() async -> Int {
        let (engine, engineStatus) = await Rpcs3Bridge.patchEngineVersion()
        guard engineStatus == 0, !engine.isEmpty, let source = url(engine: engine) else { return -2 }

        // ConfigDatabase's request shape: a 30 s timeout and the User-Agent the API wants.
        var request = URLRequest(url: source, timeoutInterval: 30)
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (file, response) = try? await URLSession.shared.download(for: request) else { return -1 }
        defer { try? FileManager.default.removeItem(at: file) }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { return -1 }
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? .max
        guard size > 0, size <= maxBody, let body = try? Data(contentsOf: file),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: body) else { return -2 }

        guard envelope.return_code == 0, envelope.version == engine,
              let yaml = envelope.patch, !yaml.isEmpty,
              let expected = envelope.sha256, expected.caseInsensitiveCompare(sha256Hex(yaml)) == .orderedSame
        else { return -2 }

        return await Rpcs3Bridge.writePatchFile("patch.yml", yaml: yaml)
    }

    static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

struct PatchesView: View {
    let serial: String
    let title: String

    @Environment(\.dismiss) private var dismiss
    @State private var rows: [PatchRow] = []
    @State private var loaded = false
    @State private var busy = false
    @State private var importing = false
    @State private var message: String?

    // A name can carry a row per hash, one per game build.
    private var names: [String] {
        Set(rows.map(\.name)).sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            List {
                if loaded && rows.isEmpty {
                    Section {
                        Text("No patches for this game yet. Download the latest patches or import a patch file.")
                            .foregroundStyle(.secondary)
                    }
                }
                if !rows.isEmpty {
                    Section {
                        ForEach(names, id: \.self) { name in
                            ForEach(rows.filter { $0.name == name }) { row in
                                Toggle(isOn: Binding(get: { row.enabled }, set: { on in toggle(row, on) })) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(verbatim: row.name)
                                        Text(verbatim: details(row))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        if !row.notes.isEmpty {
                                            Text(verbatim: row.notes)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                                .disabled(busy)
                            }
                        }
                    } header: {
                        Text("Patches").overlayCaption()
                    }
                }
                Section {
                    Button("Download latest patches") { download() }
                        .disabled(busy)
                    Button("Import patch file…") { importing = true }
                        .disabled(busy)
                } footer: {
                    Text("Changes apply the next time the game boots.")
                }
            }
            // The app's dark background, as the other sheets draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle(Text(verbatim: title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
                if busy { ToolbarItem(placement: .topBarTrailing) { ProgressView() } }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: Self.patchTypes) { result in
                if case .success(let url) = result { importFile(url) }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
        .task { await reload() }
    }

    static let patchTypes: [UTType] = [.yaml] + [UTType(filenameExtension: "yml")].compactMap { $0 }

    private func details(_ row: PatchRow) -> String {
        var parts: [String] = []
        if !row.author.isEmpty { parts.append(row.author) }
        if !row.version.isEmpty { parts.append("v" + row.version) }
        parts.append(row.appVersion == "all" ? String(localized: "All versions") : String(localized: "App version \(row.appVersion)"))
        return parts.joined(separator: " · ")
    }

    private func reload() async {
        let (json, status) = await Rpcs3Bridge.patches(forSerial: serial)
        rows = status == 0 ? PatchRow.decode(json) : []
        loaded = true
    }

    private func toggle(_ row: PatchRow, _ on: Bool) {
        busy = true
        Task {
            _ = await Rpcs3Bridge.setPatchEnabled(on, hash: row.hash, patchDescription: row.name, serial: serial,
                                                           appVersion: row.appVersion)
            await reload()
            busy = false
        }
    }

    private func download() {
        busy = true
        Task {
            let status = await PatchDownload.fetch()
            switch status {
            case 0: message = String(localized: "Patches updated.")
            case -1: message = String(localized: "Couldn't reach the patch server. Check your connection and try again.")
            default: message = String(localized: "The patch server sent something unexpected; nothing changed.")
            }
            await reload()
            busy = false
        }
    }

    // The picked file's access is held only while it is read.
    private func importFile(_ url: URL) {
        let started = url.startAccessingSecurityScopedResource()
        let text = try? String(contentsOf: url, encoding: .utf8)
        if started { url.stopAccessingSecurityScopedResource() }
        busy = true
        Task {
            let status = await Rpcs3Bridge.importPatches(text ?? "")
            message = status == 0 ? String(localized: "Patches imported.")
                : String(localized: "That file isn't a patch file this version can read; nothing changed.")
            await reload()
            busy = false
        }
    }
}
