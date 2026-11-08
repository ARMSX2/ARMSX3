// SPDX-License-Identifier: GPL-2.0-only
// Touch pad skins, Android's format. A partial skin falls back per image; nothing is fetched until the player asks.

import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Android SkinRepo.kt's shape: `{"skins":[{"file","name","preview","author"}]}`.
struct OnlineSkin: Decodable, Identifiable, Hashable {
    let file: String
    let name: String?
    let preview: String?
    let author: String?

    var id: String { file }
    var title: String { name ?? (file as NSString).lastPathComponent.replacingOccurrences(of: ".zip", with: "") }
}

struct InstalledSkin: Identifiable, Hashable {
    let id: String     // the folder name under skins/
    let name: String
}

@MainActor @Observable final class SkinStore {
    static let shared = SkinStore()

    static let activeKey = "armsx3.skins.active"
    // shortcut: an RFC 2606 `.invalid` placeholder; change it once a PS3 skin repository exists, never to ARMSX2's PS2 one.
    static let repositoryBase = URL(string: "https://example.invalid/armsx3-ps3-controller-skins/")!
    static let maxBytes: Int64 = 32 * 1024 * 1024
    static let noImages = String(localized: "No PS3 pad images found in that file.")

    // Exactly the bundled default set's file stems.
    static let imageNames: Set<String> = Set(VirtualControllerView.table.map(\.art).filter { !$0.isEmpty }
        + [VirtualControllerView.stickBaseArt, VirtualControllerView.stickKnobArt, "ic_controller_analog_button"])

    // Library/Application Support/armsx3/skins/, beside ConfigDatabase's file.
    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("skins", isDirectory: true)
    }

    enum BrowseState: Equatable {
        case idle, loading, empty, unreachable
        case loaded([OnlineSkin])
    }

    struct Refusal: Error {
        let message: String
    }

    private(set) var installed: [InstalledSkin] = []
    private(set) var active: String?
    var browse: BrowseState = .idle
    // A layout the last installed skin came with, already added to Layouts; the Skins pane offers to use it.
    var offeredLayout: PadLayoutPreset?
    static let layoutFile = "layout." + PadLayoutFile.fileExtension
    // nil when the skin lacks the image. Cleared on every switch.
    @ObservationIgnored private var cache: [String: UIImage?] = [:]

    // Not a folder, so never listed in `installed` or removed.
    static let ghostID = "builtin.ghost"

    private init() {
        reload()
        let stored = UserDefaults.standard.string(forKey: Self.activeKey)
        active = stored == Self.ghostID || installed.contains { $0.id == stored } ? stored : nil
    }

    // nil under Ghost, which PadFace draws.
    func image(named name: String) -> UIImage? {
        if active == Self.ghostID { return nil }
        if let active, Self.imageNames.contains(name) {
            if cache[name] == nil {
                cache[name] = UIImage(contentsOfFile: Self.root.appendingPathComponent(active).appendingPathComponent(name + ".png").path)
            }
            if let image = cache[name] ?? nil { return image }
        }
        return Self.bundled(name)
    }

    static func bundled(_ name: String) -> UIImage? {
        name.isEmpty ? nil : UIImage(named: name, in: .main, with: nil)
    }

    func reload() {
        let files = FileManager.default
        let folders = (try? files.contentsOfDirectory(at: Self.root, includingPropertiesForKeys: [.isDirectoryKey],
                                                      options: [.skipsHiddenFiles])) ?? []
        installed = folders.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
            .map { folder in
                let meta = (try? Data(contentsOf: folder.appendingPathComponent("skin.json")))
                    .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                return InstalledSkin(id: folder.lastPathComponent, name: meta?["name"] as? String ?? folder.lastPathComponent)
            }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    // nil is the bundled default.
    func setActive(_ id: String?) {
        active = id
        cache = [:]
        if let id { UserDefaults.standard.set(id, forKey: Self.activeKey) } else { UserDefaults.standard.removeObject(forKey: Self.activeKey) }
    }

    func remove(_ id: String) {
        try? FileManager.default.removeItem(at: Self.root.appendingPathComponent(id))
        if active == id { setActive(nil) }
        reload()
    }

    // Throws a Refusal with the sentence to show. `name` overrides the zip's file name as the skin's name.
    @discardableResult
    func importZip(at url: URL, name: String? = nil) throws -> (accepted: Int, skipped: Int) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let files = FileManager.default
        let staging = Self.root.appendingPathComponent(".staging-" + UUID().uuidString, isDirectory: true)
        defer { try? files.removeItem(at: staging) }
        do {
            let counts = try unpack(url, into: staging)
            let display = Self.folderName(name ?? url.deletingPathExtension().lastPathComponent)
            var folder = display, n = 2
            while files.fileExists(atPath: Self.root.appendingPathComponent(folder).path) {
                folder = "\(display) \(n)"
                n += 1
            }
            let target = Self.root.appendingPathComponent(folder, isDirectory: true)
            try files.moveItem(at: staging, to: target)
            let meta = try JSONSerialization.data(withJSONObject: ["name": folder])
            try meta.write(to: target.appendingPathComponent("skin.json"), options: .atomic)
            // A layout that cannot be read is skipped, and the skin installs as before.
            let layout = target.appendingPathComponent(Self.layoutFile)
            if let data = try? Data(contentsOf: layout), let file = try? PadLayoutFile.decode(data, fallbackName: folder) {
                var preset: PadLayoutPreset?
                PadLayoutStore.shared.update { preset = $0.installSkinLayout(folder, layout: file.layout) }
                offeredLayout = preset
            }
            try? files.removeItem(at: layout)
            reload()
            setActive(folder)
            return counts
        } catch let refusal as Refusal {
            throw refusal
        } catch {
            throw Refusal(message: String(localized: "That file couldn't be read as a skin zip."))
        }
    }

    private func unpack(_ zip: URL, into staging: URL) throws -> (accepted: Int, skipped: Int) {
        let files = FileManager.default
        try files.createDirectory(at: staging, withIntermediateDirectories: true)
        let archive = try ZipArchive(url: zip)
        var limits = ZipLimits(entryBytes: 4 * 1024 * 1024, totalBytes: Self.maxBytes, entries: 200)
        let counts = try SaveDataStore.extractTree(archive, limits: &limits) { entry in
            if entry.hasSuffix("/") { return (staging, "") }   // a folder entry: nothing to write, not counted
            let parts = entry.split(separator: "/").map(String.init)
            // Finder's zips carry __MACOSX/._name copies of each file, which would overwrite the real layout.
            if parts.contains(where: { $0 == "__MACOSX" || $0.hasPrefix("._") }) { return nil }
            if parts.count <= 2, let leaf = parts.last, leaf.lowercased().hasSuffix("." + PadLayoutFile.fileExtension) {
                return (staging, Self.layoutFile)
            }
            guard parts.count <= 2, let leaf = parts.last, leaf.lowercased().hasSuffix(".png") else { return nil }
            let stem = String(leaf.dropLast(4))
            return Self.imageNames.contains(stem) ? (staging, stem + ".png") : nil
        }
        let kept = ((try? files.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "png" }
        guard !kept.isEmpty else { throw Refusal(message: Self.noImages) }
        for image in kept where UIImage(contentsOfFile: image.path) == nil {
            throw Refusal(message: String(localized: "One of the skin's images couldn't be read."))
        }
        return (kept.count, counts.skipped)
    }

    // Letters, digits, spaces, `-` and `_`; anything else becomes `_`.
    static func folderName(_ raw: String) -> String {
        let cleaned = String(raw.map { $0.isLetter || $0.isNumber || " -_".contains($0) ? $0 : "_" })
            .trimmingCharacters(in: .whitespaces).prefix(64)
        return cleaned.isEmpty || cleaned.hasPrefix(".") ? "Skin" : String(cleaned)
    }

    // MARK: the online browser

    // From the "Browse online skins" tap only.
    func fetchCatalog() async {
        browse = .loading
        var request = URLRequest(url: Self.repositoryBase.appendingPathComponent("manifest.json"), timeoutInterval: 20)
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (body, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else {
            browse = .unreachable
            return
        }
        struct Manifest: Decodable { let skins: [OnlineSkin] }
        let skins = ((try? JSONDecoder().decode(Manifest.self, from: body))?.skins ?? []).filter { Self.isSafeFile($0.file) }
        browse = skins.isEmpty ? .empty : .loaded(skins)
    }

    // No `..`, no scheme, no leading slash, no backslash.
    static func isSafeFile(_ file: String) -> Bool {
        !file.isEmpty && !file.hasPrefix("/") && !file.contains(":") && !file.contains("\\")
            && !file.split(separator: "/").contains("..") && !file.contains("?") && !file.contains("#")
    }

    // nil on success, else the sentence to show.
    func download(_ skin: OnlineSkin) async -> String? {
        guard Self.isSafeFile(skin.file) else { return Self.noImages }
        let files = FileManager.default
        var request = URLRequest(url: Self.repositoryBase.appendingPathComponent(skin.file), timeoutInterval: 20)
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (zip, response) = try? await URLSession.shared.download(for: request) else {
            return String(localized: "Couldn't reach the skin repository.")
        }
        defer { try? files.removeItem(at: zip) }
        let size = ((try? files.attributesOfItem(atPath: zip.path))?[.size] as? NSNumber)?.int64Value ?? .max
        guard (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false, size <= Self.maxBytes else {
            return String(localized: "Couldn't download that skin.")
        }
        do {
            try importZip(at: zip, name: skin.title)
            return nil
        } catch let refusal as Refusal {
            return refusal.message
        } catch {
            return Self.noImages
        }
    }
}

struct SkinsPaneView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var store = SkinStore.shared
    @State private var importing = false
    @State private var message: String?
    @State private var removing: InstalledSkin?
    @State private var busy = false

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Installed")) {
                // Graphite is the bundled grey art and stays the default; Ghost is the drawn, see-through look.
                OverlayActionRow(controllerNavigationID: "settings.skins.default", label: String(localized: "Graphite"),
                                 trailingValue: store.active == nil ? "✓" : nil, keepsTrailingValue: true) {
                    store.setActive(nil)
                }
                OverlayActionRow(controllerNavigationID: "settings.skins.ghost", label: String(localized: "Ghost"),
                                 trailingValue: store.active == SkinStore.ghostID ? "✓" : nil, keepsTrailingValue: true) {
                    store.setActive(SkinStore.ghostID)
                }
                ForEach(store.installed) { skin in
                    OverlayActionRow(controllerNavigationID: "settings.skins." + skin.id, label: skin.name,
                                     trailingValue: store.active == skin.id ? "✓" : nil, keepsTrailingValue: true) {
                        store.setActive(skin.id)
                    }
                    .contextMenu {
                        Button("Remove Skin", systemImage: "trash", role: .destructive) { removing = skin }
                    }
                }
                OverlayActionRow(controllerNavigationID: "settings.skins.import", label: String(localized: "Import skin…"),
                                 systemImage: "square.and.arrow.down") { importing = true }
                    .disabled(busy)
                Text("A skin is a zip of ic_controller_*.png images, and can carry a .armsx3pad button layout. Missing images use the default look. Touch and hold a skin to remove it.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OverlaySectionCard(title: String(localized: "Button Opacity")) {
                Slider(value: Bindable(SettingsStore.shared).padOpacity, in: 0.2...1) { Text("Button Opacity") }
                Text("Lower lets more of the game show through the touch buttons. A pressed button always lights up fully.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OverlaySectionCard(title: String(localized: "Online")) {
                OverlayActionRow(controllerNavigationID: "settings.skins.browse", label: String(localized: "Browse online skins"),
                                 systemImage: "globe") { Task { await store.fetchCatalog() } }
                    .disabled(store.browse == .loading || busy)
                browseContent
            }
        }
        .navigationTitle(SettingsPane.skins.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.skins.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .onAppear { store.reload() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            guard case .success(let url) = result else { return }
            do { try store.importZip(at: url) } catch { message = (error as? SkinStore.Refusal)?.message ?? SkinStore.noImages }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
        // Asked only once the message, if any, is gone, so the two never stack.
        .controllerPrompt(String(localized: "Use this skin's layout?"),
                          isPresented: Binding(get: { store.offeredLayout != nil && message == nil },
                                               set: { if !$0 { store.offeredLayout = nil } }),
                          message: String(localized: "“\(store.offeredLayout?.name ?? "")” comes with its own button layout. It's added to Layouts either way."),
                          actions: [.init(title: String(localized: "Use This Skin's Layout"), run: {
                                        if let id = store.offeredLayout?.id { PadLayoutStore.shared.update { $0.shared = .preset(id) } }
                                    }),
                                    .init(title: String(localized: "Not Now"), isCancel: true)])
        .alert("Remove “\(removing?.name ?? "")”?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
               presenting: removing) { skin in
            Button("Remove", role: .destructive) { store.remove(skin.id) }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder private var browseContent: some View {
        switch store.browse {
        case .idle:
            note(String(localized: "Nothing downloads until you tap Browse."))
        case .loading:
            ProgressView().frame(maxWidth: .infinity)
        case .empty:
            note(String(localized: "No skins available yet."))
        case .unreachable:
            note(String(localized: "Couldn't reach the skin repository."))
        case .loaded(let skins):
            ForEach(skins) { skin in
                OverlayActionRow(controllerNavigationID: "settings.skins.online." + skin.file, label: skin.title,
                                 systemImage: "arrow.down.circle", trailingValue: skin.author) {
                    Task {
                        busy = true
                        message = await store.download(skin)
                        busy = false
                    }
                }
                .disabled(busy)
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.subheadline)
            .foregroundStyle(OverlayTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}
