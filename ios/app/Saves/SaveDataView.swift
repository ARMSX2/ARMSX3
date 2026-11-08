// SPDX-License-Identifier: GPL-2.0-only
// One title's save data, opened from the library only, so no game runs while it reads or writes a save.

import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SaveDataView: View {
    let serial: String
    let gameTitle: String

    @State private var store: SaveDataStore?
    @State private var saves: [SaveEntry] = []
    @State private var failure: Int?
    @State private var choosingSource = false
    @State private var picking: SavePick?
    @State private var pending: StagedImport?
    @State private var notice: String?
    @State private var sharing: SharedZip?
    @State private var deleting: SaveEntry?
    @State private var working = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let failure {
                        Text("Couldn't find the save folder (status \(failure))").foregroundStyle(.orange)
                    } else if store != nil && saves.isEmpty {
                        Text("No saves yet for this game").foregroundStyle(.secondary)
                    }
                    ForEach(saves) { save in
                        HStack {
                            SaveRow(save: save)
                            Spacer()
                            Menu {
                                Button("Export", systemImage: "square.and.arrow.up") { Task { await export(save) } }.disabled(working)
                                Button("Delete", systemImage: "trash", role: .destructive) { deleting = save }.disabled(working)
                            } label: {
                                Image(systemName: "ellipsis.circle")
                            }
                            .accessibilityLabel("Save actions")
                        }
                    }
                } header: {
                    Text(gameTitle).overlayCaption()
                }
            }
            // The app's dark background, as the other sheets draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Save Data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() }.disabled(working) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Import", systemImage: "square.and.arrow.down") { choosingSource = true }
                        .disabled(store == nil || working)
                }
            }
            .confirmationDialog("Import a save", isPresented: $choosingSource) {
                Button("From a folder") { picking = .folder }
                Button("From a zip") { picking = .zip }
            }
            .sheet(item: $picking) { kind in
                SavePicker(types: kind == .folder ? [.folder] : [.zip]) { url in
                    picking = nil
                    if let url { Task { await importSave(url) } }
                }
            }
            .alert("Replace this save?", isPresented: pendingShown, presenting: pending) { staged in
                Button("Replace", role: .destructive) { Task { await finishImport(staged, replacing: true) } }
                Button("Cancel", role: .cancel) { Task { await discardImport() } }
            } message: { staged in
                Text("Replace the save from \(Self.when(staged.existingDate)) with the one from \(Self.when(staged.incomingDate))? The current save is backed up first.")
            }
            .alert("Delete this save?", isPresented: deletingShown, presenting: deleting) { save in
                Button("Delete", role: .destructive) { Task { await delete(save) } }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("A backup is kept in Files › ARMSX3 › Save Backups.")
            }
            .sheet(item: $sharing) { shared in
                ShareSheet(url: shared.url) {
                    try? FileManager.default.removeItem(at: shared.url.deletingLastPathComponent())
                    sharing = nil
                }
            }
            .alert(notice ?? "", isPresented: noticeShown) {
                Button("OK", role: .cancel) {}
            }
        }
        .presentationDetents([.medium, .large])
        // shortcut: a job that never returned would keep the sheet up; revisit if jobs stop being bounded file work.
        .interactiveDismissDisabled(working)
        .preferredColorScheme(.dark)
        .task { await load() }
    }

    private func load() async {
        let (path, status) = await withCheckedContinuation { (continuation: CheckedContinuation<(String, Int), Never>) in
            Rpcs3Bridge.userDirectory { path, status in
                continuation.resume(returning: (path, status))
            }
        }
        guard status == 0, !path.isEmpty else {
            failure = status
            return
        }
        store = SaveDataStore(userDir: URL(fileURLWithPath: path, isDirectory: true))
        await reload()
    }

    private func reload() async {
        guard let store else { return }
        let serial = serial
        working = true
        saves = await Task.detached(priority: .userInitiated) { store.saves(forSerial: serial) }.value
        working = false
    }

    private var pendingShown: Binding<Bool> {
        Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })
    }

    private var deletingShown: Binding<Bool> {
        Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
    }

    private func export(_ save: SaveEntry) async {
        guard let store else { return }
        let title = gameTitle
        working = true
        let result = await Task.detached(priority: .userInitiated) { () -> Result<(URL, Int), Error> in
            Result {
                let zip = try store.export(dirName: save.dirName, gameTitle: title)
                return (zip, (try? FileManager.default.attributesOfItem(atPath: zip.path))?[.size] as? Int ?? 0)
            }
        }.value
        working = false
        switch result {
        case .success(let (zip, _)):
            sharing = SharedZip(url: zip)
        case .failure(let error):
            notice = (error as? SaveImportError ?? .unreadable).message
        }
    }

    private func delete(_ save: SaveEntry) async {
        guard let store else { return }
        working = true
        let result = await Task.detached(priority: .userInitiated) { () -> Result<Void, Error> in
            Result { try store.delete(dirName: save.dirName) }
        }.value
        working = false
        switch result {
        case .success:
            break
        case .failure(let error):
            let refusal = error as? SaveImportError ?? .unreadable
            notice = refusal == .backupFailed ? String(localized: "The save couldn't be backed up, so it wasn't deleted") : refusal.message
        }
        await reload()
    }

    private var noticeShown: Binding<Bool> {
        Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })
    }

    // The scope is opened before the detached work and closed after its result, never inside it.
    private func importSave(_ url: URL) async {
        guard let store else { return }
        let started = url.startAccessingSecurityScopedResource()
        working = true
        let result = await Task.detached(priority: .userInitiated) { () -> Result<StagedImport, Error> in
            Result { try store.prepareImport(from: url) }
        }.value
        working = false
        if started { url.stopAccessingSecurityScopedResource() }
        switch result {
        case .success(let staged):
            if staged.existingDate != nil {
                pending = staged
            } else {
                await finishImport(staged, replacing: false)
            }
        case .failure(let error):
            report(error)
        }
    }

    private func finishImport(_ staged: StagedImport, replacing: Bool) async {
        guard let store else { return }
        working = true
        let result = await Task.detached(priority: .userInitiated) { () -> Result<Bool, Error> in
            let result = Result { try store.commit(staged, replacing: replacing) }
            if case .failure = result { store.discard() }
            return result
        }.value
        working = false
        switch result {
        case .success(let replaced):
            notice = replaced ? String(localized: "Save replaced; the old one is in Save Backups") : String(localized: "Save imported")
            await reload()
        case .failure(let error):
            report(error)
        }
    }

    private func discardImport() async {
        guard let store else { return }
        working = true
        await Task.detached(priority: .userInitiated) { store.discard() }.value
        working = false
    }

    private func report(_ error: Error) {
        let refusal = error as? SaveImportError ?? .unreadable
        notice = refusal.message
    }

    static func when(_ date: Date?) -> String {
        date?.formatted(date: .abbreviated, time: .shortened) ?? String(localized: "an unknown date")
    }
}

private struct SharedZip: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, _, _, _ in onDone() }
        return sheet
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private enum SavePick: String, Identifiable {
    case folder, zip
    var id: String { rawValue }
}

// Opened in place: the store copies what it needs while the scope is held, so nothing is copied twice.
struct SavePicker: UIViewControllerRepresentable {
    let types: [UTType]
    let onFinish: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: (URL?) -> Void

        init(onFinish: @escaping (URL?) -> Void) { self.onFinish = onFinish }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFinish(urls.first)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFinish(nil) }
    }
}

private struct SaveRow: View {
    let save: SaveEntry

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let icon = save.iconURL, let image = UIImage(contentsOfFile: icon.path) {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    Image(systemName: "externaldrive").foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(width: 64, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(save.title).foregroundStyle(OverlayTheme.textPrimary)
                if !save.subtitle.isEmpty {
                    Text(save.subtitle).font(.subheadline).foregroundStyle(OverlayTheme.textSecondary)
                }
                Text("\(save.modified.formatted(date: .abbreviated, time: .shortened)) · \(ByteCountFormatter.string(fromByteCount: save.size, countStyle: .file))")
                    .font(.caption)
                    .foregroundStyle(OverlayTheme.textSecondary)
            }
        }
    }
}
