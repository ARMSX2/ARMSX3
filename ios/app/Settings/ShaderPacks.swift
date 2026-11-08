// SPDX-License-Identifier: GPL-2.0-only
// librashader presets. The active one is kept relative to the shaders folder, so a container that moves does not strand it.

import Foundation
import SwiftUI

// The two packs Android's ShaderRepo.kt offers, with its URLs and its folder names.
enum ShaderPackSource: String, CaseIterable, Identifiable {
    case slang, retroCrisis

    var id: String { rawValue }

    var title: String {
        switch self {
        case .slang: return String(localized: "RetroArch Slang Shaders")
        case .retroCrisis: return String(localized: "Retro Crisis GDV-NTSC")
        }
    }

    var url: URL {
        switch self {
        case .slang: return URL(string: "https://buildbot.libretro.com/assets/frontend/shaders_slang.zip")!
        case .retroCrisis:
            return URL(string: "https://github.com/RetroCrisis/Retro-Crisis-GDV-NTSC/releases/download/20260321/Retro.Crisis.GDV-NTSC.2026.03.21.zip")!
        }
    }

    // Retro Crisis reaches the slang pack's shaders by relative path, so it merges into the root and needs that pack first.
    var folder: String {
        switch self {
        case .slang: return "shaders_slang"
        case .retroCrisis: return "retro_crisis_gdv_ntsc"
        }
    }

    var requires: ShaderPackSource? { self == .retroCrisis ? .slang : nil }
}

enum ShaderPacks {
    // "crt/crt-royale.slangp" reads "CRT Royale"; a name two folders share gets its folder after it.
    static let acronyms: Set<String> = ["crt", "ntsc", "pal", "gdv", "lcd", "vhs", "hq", "xbr", "sabr", "fxaa", "smaa", "aa", "hdr", "sdr"]

    static func displayName(_ relativePath: String) -> String {
        let file = (relativePath as NSString).lastPathComponent
        let words = (file as NSString).deletingPathExtension
            .split(whereSeparator: { $0 == "-" || $0 == "_" || $0 == " " })
            .map { word -> String in
                let lower = word.lowercased()
                return acronyms.contains(lower) ? lower.uppercased() : word.prefix(1).uppercased() + word.dropFirst()
            }
        return words.joined(separator: " ")
    }

    static func displayNames(_ presets: [String]) -> [String: String] {
        var count: [String: Int] = [:]
        for preset in presets { count[displayName(preset), default: 0] += 1 }
        return Dictionary(uniqueKeysWithValues: presets.map { preset in
            let name = displayName(preset)
            let folder = (preset as NSString).deletingLastPathComponent
            return (preset, count[name, default: 0] > 1 && !folder.isEmpty ? "\(name) (\(folder))" : name)
        })
    }

    static let modeValue = "Shader chain (librashader)"
    static let modePath = ["Video", "Output Scaling Mode"]
    static let presetPath = ["Video", "Shader Preset Path"]
    static let activeKey = "armsx3.shaders.activePreset"
    static let maxDownload: Int64 = 256 * 1024 * 1024

    // Beside ConfigDatabase's file.
    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("shaders", isDirectory: true)
    }

    static var activePreset: String? { UserDefaults.standard.string(forKey: activeKey) }

    // shortcut: stops at 2,000 presets; the libretro pack holds well under that, paginate if a pack ever exceeds it.
    static func installedPresets() -> [String] {
        let base = root.standardizedFileURL.path + "/"
        guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil,
                                                          options: [.skipsHiddenFiles]) else { return [] }
        var found: [String] = []
        for case let url as URL in walker where url.pathExtension.lowercased() == "slangp" {
            let path = url.standardizedFileURL.path
            guard path.hasPrefix(base) else { continue }
            found.append(String(path.dropFirst(base.count)))
            if found.count >= 2_000 { break }
        }
        return found.sorted()
    }

    // Only inside the shaders folder and to a .slangp, so a restored or hand-edited value never points elsewhere.
    static func presetURL(_ relative: String) -> URL? {
        SaveDataStore.containedFile(relative, in: root, suffix: ".slangp")
    }

    // 0 or the first refusing status.
    @MainActor static func apply(relativePath: String) async -> Int {
        guard let preset = presetURL(relativePath) else {
            return -7 // RPCS3_IOS_STATUS_INVALID_ARGUMENT
        }
        let absolute = preset.path
        var (status, _) = await EmulatorSession.shared.setSetting(modePath, value: modeValue, serial: nil, liveIds: [])
        if status == 0 {
            (status, _) = await EmulatorSession.shared.setSetting(presetPath, value: absolute, serial: nil, liveIds: [])
        }
        if status == 0 { UserDefaults.standard.set(relativePath, forKey: activeKey) }
        return status
    }

    @MainActor static func clear() async -> Int {
        let (status, _) = await EmulatorSession.shared.resetSettings([modePath, presetPath], serial: nil, liveIds: [])
        if status == 0 { UserDefaults.standard.removeObject(forKey: activeKey) }
        return status
    }

    // A clear when the file is gone or the path points outside the shaders folder.
    @MainActor static func reapply() async {
        guard let relative = activePreset else { return }
        let exists = presetURL(relative).map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        _ = exists ? await apply(relativePath: relative) : await clear()
    }

    // The companion leaves a marker, because it merges into the root.
    static func isInstalled(_ source: ShaderPackSource) -> Bool {
        FileManager.default.fileExists(atPath: marker(source).path)
    }

    private static func marker(_ source: ShaderPackSource) -> URL {
        root.appendingPathComponent("." + source.folder + ".installed", isDirectory: false)
    }

    // On any failure nothing new is visible, because the pack unpacks into hidden staging first.
    static func download(_ source: ShaderPackSource) async -> Bool {
        let ok = await install(source)
        return ok
    }

    private static func install(_ source: ShaderPackSource) async -> Bool {
        let files = FileManager.default
        if let needed = source.requires, !isInstalled(needed) { return false }
        let staging = root.appendingPathComponent(".staging-" + source.folder, isDirectory: true)
        defer { try? files.removeItem(at: staging) }

        var request = URLRequest(url: source.url, timeoutInterval: 60)
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (zip, response) = try? await URLSession.shared.download(for: request) else { return false }
        defer { try? files.removeItem(at: zip) }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return false }
        let size = ((try? files.attributesOfItem(atPath: zip.path))?[.size] as? NSNumber)?.int64Value ?? .max
        guard size <= maxDownload else { return false }

        do {
            try? files.removeItem(at: staging)
            try files.createDirectory(at: staging, withIntermediateDirectories: true)
            let archive = try ZipArchive(url: zip)
            let strip = commonRoot(archive.entries.map(\.name).filter { !isJunk($0) })
            var limits = ZipLimits(entryBytes: 64 * 1024 * 1024, totalBytes: 1024 * 1024 * 1024, entries: 40_000)
            _ = try SaveDataStore.extractTree(archive, limits: &limits) { name in
                isJunk(name) ? nil : (staging, String(name.dropFirst(strip.count)))
            }
            guard containsPreset(staging) else { return false }
            if source.requires == nil {
                let target = root.appendingPathComponent(source.folder, isDirectory: true)
                try? files.removeItem(at: target)
                try files.moveItem(at: staging, to: target)
            } else {
                try merge(staging, into: root)
            }
            files.createFile(atPath: marker(source).path, contents: Data())
            return true
        } catch {
            return false
        }
    }

    // Android's single-common-root strip.
    static func commonRoot(_ names: [String]) -> String {
        guard let first = names.first, let slash = first.firstIndex(of: "/"), slash != first.startIndex else { return "" }
        let prefix = String(first[...slash])
        return names.allSatisfy { $0.hasPrefix(prefix) } ? prefix : ""
    }

    // macOS zip junk, as Android drops it.
    static func isJunk(_ name: String) -> Bool {
        let leaf = name.split(separator: "/").last.map(String.init) ?? ""
        return name.hasPrefix("__MACOSX/") || leaf == ".DS_Store" || leaf.hasPrefix("._")
    }

    private static func containsPreset(_ dir: URL) -> Bool {
        let walker = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)
        while let url = walker?.nextObject() as? URL {
            if url.pathExtension.lowercased() == "slangp" { return true }
        }
        return false
    }

    // A whole subtree that is new moves in one rename; only a folder that already exists is entered.
    private static func merge(_ source: URL, into target: URL) throws {
        let files = FileManager.default
        for child in try files.contentsOfDirectory(at: source, includingPropertiesForKeys: [.isDirectoryKey]) {
            let destination = target.appendingPathComponent(child.lastPathComponent)
            var isDirectory: ObjCBool = false
            let exists = files.fileExists(atPath: destination.path, isDirectory: &isDirectory)
            if exists && isDirectory.boolValue && (try? child.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                try merge(child, into: destination)
            } else {
                if exists { try files.removeItem(at: destination) }
                try files.moveItem(at: child, to: destination)
            }
        }
    }
}

private enum PackState: Equatable {
    case idle, downloading, installed, failed
}

struct ShadersPaneView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var presets: [String] = []
    @State private var active: String? = ShaderPacks.activePreset
    @State private var refused: Int?
    @State private var packs: [ShaderPackSource: PackState] = [:]

    var body: some View {
        SettingsPage {
            OverlaySectionCard(title: String(localized: "Presets")) {
                OverlayActionRow(controllerNavigationID: "settings.shaders.off", label: String(localized: "Off"),
                                 trailingValue: active == nil ? "✓" : nil, keepsTrailingValue: true) {
                    Task { await run { await ShaderPacks.clear() } }
                }
                let names = ShaderPacks.displayNames(presets)
                ForEach(presets, id: \.self) { preset in
                    OverlayActionRow(controllerNavigationID: "settings.shaders." + preset, label: names[preset] ?? preset,
                                     trailingValue: active == preset ? "✓" : nil, keepsTrailingValue: true) {
                        Task { await run { await ShaderPacks.apply(relativePath: preset) } }
                    }
                }
                if presets.isEmpty {
                    Text("No shader presets installed yet.")
                        .font(.subheadline)
                        .foregroundStyle(OverlayTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            OverlaySectionCard(title: String(localized: "Download")) {
                ForEach(ShaderPackSource.allCases) { source in
                    VStack(alignment: .leading, spacing: 4) {
                        OverlayActionRow(controllerNavigationID: "settings.shaders.download." + source.rawValue, label: source.title,
                                         systemImage: "arrow.down.circle", trailingValue: trailing(source), keepsTrailingValue: true) {
                            Task { await download(source) }
                        }
                        .disabled(packs[source] == .downloading)
                        if packs[source] == .failed {
                            Text("Couldn't download \(source.title). Check your connection and try again.")
                                .font(.subheadline)
                                .foregroundStyle(OverlayTheme.textSecondary)
                        }
                    }
                }
                Text("From buildbot.libretro.com and the Retro Crisis releases on GitHub. Nothing downloads until you tap a pack. Retro Crisis needs the RetroArch pack first.")
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .navigationTitle(SettingsPane.shaders.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.shaders.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .onAppear { refresh() }
        .alert("Shader not applied", isPresented: Binding(get: { refused != nil }, set: { if !$0 { refused = nil } })) {
            Button("OK", role: .cancel) { refused = nil }
        } message: {
            Text("The emulator refused the shader setting (status \(refused ?? 0)).")
        }
    }

    private func trailing(_ source: ShaderPackSource) -> String? {
        switch packs[source] ?? .idle {
        case .downloading: return String(localized: "Downloading…")
        case .installed: return String(localized: "Installed")
        case .idle, .failed: return nil
        }
    }

    private func refresh() {
        presets = ShaderPacks.installedPresets()
        active = ShaderPacks.activePreset
        for source in ShaderPackSource.allCases where packs[source] != .downloading && packs[source] != .failed {
            packs[source] = ShaderPacks.isInstalled(source) ? .installed : .idle
        }
    }

    private func download(_ source: ShaderPackSource) async {
        packs[source] = .downloading
        let ok = await ShaderPacks.download(source)
        packs[source] = ok ? .installed : .failed
        refresh()
    }

    private func run(_ change: () async -> Int) async {
        let status = await change()
        if status != 0 { refused = status }
        refresh()
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}
