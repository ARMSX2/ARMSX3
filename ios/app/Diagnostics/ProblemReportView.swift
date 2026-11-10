// SPDX-License-Identifier: GPL-2.0-only
// The problem report sheet. Nothing leaves without a press, and store calls run off the main actor.

import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ReportRequest: Identifiable {
    let id = UUID()
    let session: URL
    let serial: String?
}

private struct SharedReport: Identifiable {
    let id = UUID()
    let url: URL
}

private struct CrashFilePick: Identifiable {
    let id = UUID()
}

struct ProblemReportView: View {
    let session: URL
    let serial: String?
    var store: DiagnosticsStore = .live()

    @State var form = ReportForm()
    @State private var working = false
    @State private var sharing: SharedReport?
    @State private var size: Int?
    @State private var copied = false
    @State private var failure = false
    // Whether a MetricKit crash diagnostic rides in this report, read once on appear.
    @State private var crashIncluded: Bool?
    @State private var attachments: [URL] = []
    @State private var picking: CrashFilePick?
    @State private var refused = false

    private static let steps = [
        String(localized: "Open the Settings app, then Privacy & Security › Analytics & Improvements › Analytics Data."),
        String(localized: "Find the newest entry whose name starts with ARMSX3- (this app's crash files carry that name)."),
        String(localized: "Tap it, tap Share, then Save to Files."),
        String(localized: "Come back here and tap Attach Crash File."),
    ]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ReportChips(options: ReportForm.kinds, selected: form.kinds) { kind in
                        if let index = form.kinds.firstIndex(of: kind) { form.kinds.remove(at: index) } else { form.kinds.append(kind) }
                    }
                    TextField("Describe it in your own words", text: $form.whatHappened, axis: .vertical)
                        .lineLimit(3...8)
                } header: {
                    Text("What happened").overlayCaption()
                }
                Section {
                    ReportChips(options: ReportForm.places, selected: form.place.isEmpty ? [] : [form.place]) { place in
                        form.place = form.place == place ? "" : place
                    }
                    TextField("Which level, menu or scene?", text: $form.whereInGame, axis: .vertical)
                        .lineLimit(3...8)
                } header: {
                    Text("Where").overlayCaption()
                }
                if crashIncluded == true {
                    Section {
                        Text("A crash report from iOS is included.")
                    }
                } else if crashIncluded == false {
                    Section {
                        ForEach(Array(Self.steps.enumerated()), id: \.offset) { index, step in
                            Text("\(index + 1). \(step)")
                        }
                        Button("Attach Crash File", systemImage: "paperclip") { picking = CrashFilePick() }
                            .disabled(working)
                        if !attachments.isEmpty {
                            Text("Crash file attached.")
                        }
                    } header: {
                        Text("Add the crash file").overlayCaption()
                    } footer: {
                        Text("Attach only files that start with ARMSX3-. Other entries can name other apps.")
                    }
                    .sheet(item: $picking) { _ in
                        FirmwarePicker(types: [UTType(filenameExtension: "ips") ?? .json, .json, .plainText]) { url in
                            Task { await attach(url) }
                        }
                    }
                    .alert("That file isn't a crash report.", isPresented: $refused) {
                        Button("OK", role: .cancel) {}
                    }
                }
                Section {
                    Button("Share Zip", systemImage: "square.and.arrow.up") { Task { await shareZip() } }
                        .disabled(working)
                    Button("Copy Summary", systemImage: "doc.on.doc") { Task { await copySummary() } }
                        .disabled(working)
                    if let size {
                        Text("Report size: \(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))")
                        Text("Saved in Files › ARMSX3 › Reports.")
                    }
                    if copied {
                        Text("Summary copied.")
                    }
                } footer: {
                    Text("Nothing is sent until you share it. Folder paths are removed and the pairing file is never included.")
                }
            }
            // The app's dark background, as Settings, Install and Trophies draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Report a Problem")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() }.disabled(working) }
            }
            .sheet(item: $sharing) { shared in
                ShareSheet(url: shared.url) { sharing = nil }
            }
            .alert("Couldn't build the report.", isPresented: $failure) {
                Button("OK", role: .cancel) {}
            }
        }
        .task {
            let (store, session) = (store, session)
            crashIncluded = await Task.detached(priority: .utility) { store.hasCrashDiagnostic(for: session) }.value
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(working)
        .preferredColorScheme(.dark)
    }

    // A failed export or dump reads as no settings.
    private func gather() async -> ReportSettings {
        _ = await Rpcs3Bridge.syncLog()
        let emulator = EmulatorSession.shared
        let all = await emulator.exportSettings(serial: nil)
        var settings = ReportSettings(allGames: all.status == 0 ? all.yaml : "")
        if let serial {
            let own = await emulator.exportSettings(serial: serial)
            settings.thisGame = own.status == 0 ? own.yaml : ""
        }
        // The fixed list's values as this game runs them: forced, else the game's, else the database's, else global.
        let dump = await emulator.settingsDump(serial: serial)
        if dump.status == 0, let data = dump.data, let leaves = SettingsModel.decode(data)?.leaves {
            for leaf in leaves where ReportFacts.keySettings.contains(leaf.id) {
                settings.key[leaf.id] = leaf.forced ?? leaf.game ?? leaf.db ?? leaf.global
            }
        }
        return settings
    }

    // The pairing lookup ran in this launch, so words only; the file's name, path and contents stay out.
    private func pairingFacts() -> (file: String, ladder: String?) {
        let report = Rpcs3Bridge.jitPairingReport()
        let own = PairingImport.destination
        let ownFound = own.map(PairingImport.isNonEmptyRegularFile) ?? false
        let sidecar = own?.deletingLastPathComponent().appendingPathComponent(PairingSource.originName, isDirectory: false)
        let origin = PairingSource.origin(sidecar.flatMap { try? String(contentsOf: $0, encoding: .utf8) })
        var inUse = own
        if report["used"]?.hasPrefix("sidestore") == true {
            let home = ProcessInfo.processInfo.environment["LC_HOME_PATH"]
            inUse = PairingSource.sideStoreFile(liveContainerHome: home) { try? Data(contentsOf: $0) }
        }
        let attributes = inUse.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path) }
        let date = (attributes?[.creationDate] ?? attributes?[.modificationDate]) as? Date
        return (PairingSource.reportLine(report: report, ownFileFound: ownFound, origin: origin, fileDate: date),
                PairingSource.ladderLine(report: report))
    }

    // The dropped-line count and the pairing lookup belong to this launch only, so a report on an earlier session passes none.
    private func processFacts() -> ReportProcess {
        let running = session.lastPathComponent == DiagnosticsSession.shared.current?.lastPathComponent
        let info = Bundle.main.infoDictionary
        let build = (info?["CFBundleShortVersionString"] as? String).map { "\($0) (\(info?["CFBundleVersion"] as? String ?? "?"))" }
        let pairing = running ? pairingFacts() : nil
        return ReportProcess(liveContainer: Rpcs3Bridge.deviceChecks()["liveContainer"].map { $0.intValue == 1 },
                             stderrDropped: running ? Rpcs3Bridge.stderrWitnessDropped() : nil, appBuild: build,
                             pairingFile: pairing?.file, pairingLadder: pairing?.ladder)
    }

    private func shareZip() async {
        working = true
        let settings = await gather()
        let process = processFacts()
        let (store, session, form, attachments) = (store, session, form, attachments)
        let result = await Task.detached(priority: .userInitiated) {
            Result { try store.buildReport(session: session, form: form, settings: settings, process: process, attachments: attachments, now: Date()) }
        }.value
        working = false
        switch result {
        case .success(let report):
            size = report.bytes
            sharing = SharedReport(url: report.zip)
        case .failure:
            failure = true
        }
    }

    // A picked file is kept only when the store calls it a crash file; otherwise the sheet says so.
    private func attach(_ url: URL?) async {
        picking = nil
        guard let url else { return }
        let store = store
        let accepted = await Task.detached(priority: .userInitiated) { store.isCrashFile(url) }.value
        if accepted {
            attachments.append(url)
        } else {
            refused = true
        }
    }

    private func copySummary() async {
        working = true
        let settings = await gather()
        let process = processFacts()
        let (store, session, form) = (store, session, form)
        let text = await Task.detached(priority: .userInitiated) {
            store.summary(session: session, form: form, settings: settings, process: process)
        }.value
        working = false
        UIPasteboard.general.string = text
        copied = true
    }
}

private struct ReportChips: View {
    let options: [String]
    let selected: [String]
    let tap: (String) -> Void

    var body: some View {
        ChipFlow(spacing: 8) {
            ForEach(options, id: \.self) { option in
                let on = selected.contains(option)
                Button { tap(option) } label: {
                    Text(LocalizedStringKey(option))
                        .font(.subheadline.weight(on ? .semibold : .regular))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(OverlayTheme.textPrimary))
                        .background(Capsule().fill(on ? AnyShapeStyle(.tint.opacity(0.22)) : AnyShapeStyle(OverlayTheme.card)))
                        .overlay(Capsule().stroke(on ? AnyShapeStyle(.tint.opacity(0.6)) : AnyShapeStyle(OverlayTheme.separator), lineWidth: 0.65))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ChipFlow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let frames = place(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: frames.map(\.maxX).max() ?? 0, height: frames.map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (view, frame) in zip(subviews, place(subviews, width: bounds.width)) {
            view.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY), proposal: ProposedViewSize(frame.size))
        }
    }

    private func place(_ subviews: Subviews, width: CGFloat) -> [CGRect] {
        var frames: [CGRect] = []
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += line + spacing
                line = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            x += size.width + spacing
            line = max(line, size.height)
        }
        return frames
    }
}
