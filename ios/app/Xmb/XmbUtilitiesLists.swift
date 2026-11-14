// SPDX-License-Identifier: GPL-2.0-only
// What the Utilities column reads from disk and the picker it opens, ported from XmbLibraryTools.kt, XmbTrophies.kt, DlcTab.kt and XmbFiles.kt.

import SwiftUI

/// One save state as Load / Manage Saves and the in-game Save State and Load State lists show it.
struct XmbSavedState: Sendable {
    let serial: String
    let title: String
    let url: URL
    /// Nil for a state saved from the PS menu.
    let fileSlot: Int?
    /// The panel's slot: 1 to 10, the quick save or the exit autosave; nil for a state saved from the PS menu.
    let uiSlot: Int?
    let modified: Date
    /// The state's picture as a PNG the XMB can show.
    let picture: String?

    /// The states in each title folder, the slots and then the PS menu's, each picture written once to Caches.
    @MainActor static func read(_ dirs: [URL], settings: SettingsStore) async -> [XmbSavedState] {
        // Opening each state's picture reads a file, so it is done off the main thread.
        let scanned = await Task.detached(priority: .utility) { () -> [(serial: String, files: [(SaveStateFile, SaveStateFiles.Entry)], psMenu: [SaveStateFiles.Entry])] in
            dirs.map { dir in
                let listing = SaveStateFiles.scan(directory: dir, appHistory: SaveStateFiles.appHistory(
                    in: dir.appendingPathComponent(SaveStateFiles.slotsFolder)))
                let files = listing.slots.sorted { $0.key < $1.key }.compactMap { fileSlot, entry -> (SaveStateFile, SaveStateFiles.Entry)? in
                    SaveStateFiles.uiSlot(forFileSlot: fileSlot).map { (SaveStateFile(slot: $0, entry: entry, directory: dir), entry) }
                }
                return (dir.lastPathComponent, files, listing.psMenu)
            }
        }.value
        var found: [XmbSavedState] = []
        for (serial, files, psMenu) in scanned {
            for (file, entry) in files {
                let kind: SaveStateSlot.Kind = file.slot == SaveStateSlot.quickSlot ? .quick : file.slot == SaveStateSlot.autoSlot ? .auto : .manual
                let name = SaveStateSlot(file: file, kind: kind, metadata: SaveStateMetadataStore.shared.metadata(for: file)).title(settings)
                found.append(XmbSavedState(serial: serial, title: name, url: entry.url, fileSlot: entry.fileSlot, uiSlot: file.slot,
                                           modified: entry.modified, picture: entry.thumbnail?.path))
            }
            found += psMenu.map {
                XmbSavedState(serial: serial, title: String(localized: "PS menu save"), url: $0.url, fileSlot: nil, uiSlot: nil,
                              modified: $0.modified, picture: nil)
            }
        }
        return await Task.detached(priority: .utility) {
            found.map { state in
                XmbSavedState(serial: state.serial, title: state.title, url: state.url, fileSlot: state.fileSlot, uiSlot: state.uiSlot,
                              modified: state.modified, picture: state.picture.flatMap { png(thumb: $0, modified: state.modified) })
            }
        }.value
    }

    /// A state's picture in the app's own thumbnail format, written once as a PNG the XMB can show.
    private nonisolated static func png(thumb: String, modified: Date) -> String? {
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("xmb-states", isDirectory: true)
        let key = (thumb + "\(Int(modified.timeIntervalSince1970))").utf8.reduce(UInt64(14_695_981_039_346_656_037)) { ($0 ^ UInt64($1)) &* 1_099_511_628_211 }
        let target = cache.appendingPathComponent(String(key, radix: 16) + ".png")
        if FileManager.default.fileExists(atPath: target.path) { return target.path }
        guard let data = FileManager.default.contents(atPath: thumb), let image = SaveStateFiles.decodeThumbnail(data),
              let provider = CGDataProvider(data: image.rgba as CFData),
              let cg = CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: image.width * 4,
                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent),
              let png = UIImage(cgImage: cg).pngData() else { return nil }
        try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        return (try? png.write(to: target, options: .atomic)) != nil ? target.path : nil
    }

    /// What deleting it asks.
    var deleteQuestion: String {
        if let slot = uiSlot, (1 ... 10).contains(slot) { return String(localized: "Delete the save state in slot \(slot)? This can't be undone.") }
        return String(localized: "Delete \(title)?")
    }

    /// Removes the state, and with it the slot's picture and format record, or the slot's next save shows this one's.
    func delete() -> Bool {
        let files = FileManager.default
        guard (try? files.removeItem(at: url)) != nil else { return false }
        if let picture { try? files.removeItem(atPath: picture) }
        if let fileSlot {
            let folder = url.deletingLastPathComponent()
            try? files.removeItem(at: folder.appendingPathComponent("slot\(fileSlot).thumb"))
            try? files.removeItem(at: folder.appendingPathComponent("slot\(fileSlot).epoch"))
        }
        return true
    }
}

/// A licence file or a content folder installed for a game.
struct XmbAddOn: Sendable {
    let name: String
    let url: URL
    let isLicence: Bool
}

@MainActor @Observable final class XmbToolsStore {
    static let shared = XmbToolsStore()

    enum Pick { case packages, patch }

    /// Every trophy set by title; nil while reading.
    private(set) var trophySets: [TrophySet]?
    /// Each game's save states, newest first; nil while reading.
    private(set) var states: [XmbSavedState]?
    private(set) var licences: [URL] = []
    /// Each game's add-ons by title ID and name; nil while reading.
    private(set) var addOns: [(id: String, name: String, items: [XmbAddOn])]?
    private(set) var patches: [String: [PatchRow]] = [:]
    /// A state picked to boot from, taken by the game screen as it starts.
    @ObservationIgnored var pendingState: (serial: String, path: String)?
    @ObservationIgnored private var seenOpen: Set<String> = []
    @ObservationIgnored private var patchesShown: String?

    /// The state picked for `serial`, once.
    func takeState(serial: String) -> String? {
        guard let pending = pendingState, pending.serial == serial else { return nil }
        pendingState = nil
        return pending.path
    }

    /// True the first time `id` is seen open since it was last seen closed. The columns are rebuilt whenever a folder
    /// opens or closes, so this is how a list is read again on each open, as Android's lists are.
    func justOpened(_ id: String, _ open: Bool) -> Bool {
        guard open else {
            seenOpen.remove(id)
            return false
        }
        return seenOpen.insert(id).inserted
    }

    func patchesOpened(_ serial: String?) -> Bool {
        defer { patchesShown = serial }
        return serial != nil && serial != patchesShown
    }

    // MARK: - Reading

    static func folder(_ query: (@escaping @Sendable (String, Int) -> Void) -> Void) async -> URL? {
        let path = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            query { path, status in continuation.resume(returning: status == 0 ? path : "") }
        }
        return path.isEmpty ? nil : URL(fileURLWithPath: path, isDirectory: true)
    }

    /// Every set, or with `titleDir` only the sets that game installed.
    /// `refresh` keeps the list up while it is read again, so a folder open on it keeps its place.
    func loadTrophies(titleDir: URL? = nil, refresh: Bool = false) async {
        if !refresh { trophySets = nil }
        guard let user = await Self.folder(Rpcs3Bridge.userDirectory) else {
            trophySets = []
            return
        }
        trophySets = await Task.detached(priority: .utility) {
            let root = user.appendingPathComponent("trophy", isDirectory: true)
            let dirs = titleDir.map { TrophyReader.sets(titleDir: $0, userDir: user).map { root.appendingPathComponent($0) } }
                ?? (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
            return dirs.compactMap { TrophyReader.read(setDir: $0) }.sorted { $0.title.lowercased() < $1.title.lowercased() }
        }.value
    }

    func loadLicences() async {
        guard let hdd0 = await Self.folder(Rpcs3Bridge.hdd0Directory) else { return }
        licences = await Task.detached(priority: .utility) { Self.licenceFiles(hdd0: hdd0) }.value
    }

    nonisolated static func licenceFiles(hdd0: URL) -> [URL] {
        let files = FileManager.default
        let users = (try? files.contentsOfDirectory(at: hdd0.appendingPathComponent("home"), includingPropertiesForKeys: nil)) ?? []
        return users.flatMap { user in
            ((try? files.contentsOfDirectory(at: user.appendingPathComponent("exdata"), includingPropertiesForKeys: nil)) ?? [])
                .filter { ["rap", "edat"].contains($0.pathExtension.lowercased()) }
        }
    }

    /// UP0700-NPUB30910_00-XXXX: the title ID sits between the first dash and the underscore.
    nonisolated static func licenceTitleID(_ file: URL) -> String? {
        let stem = file.deletingPathExtension().lastPathComponent
        guard let dash = stem.firstIndex(of: "-") else { return nil }
        let id = stem[stem.index(after: dash)...].prefix { $0 != "_" }
        return id.count == 9 && id.allSatisfy({ $0.isLetter || $0.isNumber }) ? id.uppercased() : nil
    }

    /// Licences named for a game, and other folders whose PARAM.SFO names it, as Ps3Sfo.installedDlc finds them.
    /// A PSN game's own licence carries its title ID too, so it is left out.
    func loadAddOns() async {
        addOns = nil
        guard let hdd0 = await Self.folder(Rpcs3Bridge.hdd0Directory) else {
            addOns = []
            return
        }
        let game = hdd0.appendingPathComponent("game", isDirectory: true)
        let (found, licenceFiles) = await Task.detached(priority: .utility) { (Self.addOnFolders(game: game), Self.licenceFiles(hdd0: hdd0)) }.value
        var ids = Set(licenceFiles.compactMap(Self.licenceTitleID))
        ids.formUnion(found.map(\.id))
        var groups: [(id: String, name: String, items: [XmbAddOn])] = []
        for id in ids {
            let installed = game.appendingPathComponent(id, isDirectory: true)
            let sfo = (try? Data(contentsOf: installed.appendingPathComponent("PARAM.SFO"))).flatMap { ParamSfo(data: $0) }
            var own = ""
            if id.hasPrefix("N"), sfo != nil {
                let (_, contentId, status) = await Rpcs3Bridge.probeLicence(forSerial: id)
                if status == 0 { own = contentId }
            }
            let licences = licenceFiles.filter {
                $0.lastPathComponent.localizedCaseInsensitiveContains(id)
                    && !(own.isEmpty == false && $0.deletingPathExtension().lastPathComponent.caseInsensitiveCompare(own) == .orderedSame)
            }.map { file in
                let stem = file.deletingPathExtension().lastPathComponent
                let name = stem.split(separator: "-").last.map(String.init) ?? file.lastPathComponent
                return XmbAddOn(name: name.isEmpty ? file.lastPathComponent : name, url: file, isLicence: true)
            }
            let folders = found.filter { $0.id == id }.map(\.addOn)
            let items = (licences + folders).sorted { $0.name.lowercased() < $1.name.lowercased() }
            guard !items.isEmpty else { continue }
            let title = sfo?.title?.trimmingCharacters(in: .whitespaces) ?? ""
            groups.append((id, title.isEmpty ? id : title, items))
        }
        addOns = groups.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private nonisolated static func addOnFolders(game: URL) -> [(id: String, addOn: XmbAddOn)] {
        let dirs = (try? FileManager.default.contentsOfDirectory(at: game, includingPropertiesForKeys: nil)) ?? []
        return dirs.compactMap { dir in
            guard let sfo = (try? Data(contentsOf: dir.appendingPathComponent("PARAM.SFO"))).flatMap({ ParamSfo(data: $0) }),
                  let id = sfo.strings["TITLE_ID"]?.uppercased(), id != dir.lastPathComponent.uppercased() else { return nil }
            let title = sfo.title?.trimmingCharacters(in: .whitespaces) ?? ""
            return (id, XmbAddOn(name: title.isEmpty ? dir.lastPathComponent : title, url: dir, isLicence: false))
        }
    }

    /// The save-state tree, the parent of every title's folder.
    static func stateRoot() async -> URL? {
        // Any well-formed serial names a folder in the tree; the bridge creates nothing for it.
        await folder { done in Rpcs3Bridge.saveStateDirectory(forSerial: "BLUS00000", completion: done) }?.deletingLastPathComponent()
    }

    func loadStates(settings: SettingsStore) async {
        guard let root = await Self.stateRoot() else {
            states = []
            return
        }
        let dirs = await Task.detached(priority: .utility) {
            (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        }.value
        states = await XmbSavedState.read(dirs, settings: settings).sorted { $0.modified > $1.modified }
    }

    func loadPatches(_ serial: String) async {
        let (json, status) = await Rpcs3Bridge.patches(forSerial: serial)
        patches[serial] = status == 0 ? PatchRow.decode(json) : []
    }

    func reloadPatches() async {
        for serial in patches.keys { await loadPatches(serial) }
    }
}

// MARK: - Trophies, saves and patches

extension XmbHost {
    /// Trophy Collection as the console keeps it: each set with its share earned, and in it every trophy with its grade
    /// and when it was earned. A hidden trophy not yet earned keeps its name to itself. Read again on every open. Over a
    /// game, `titleDir` is the running game's, and a game with one set lists its trophies straight away.
    func toolsTrophies(id: String = "tools.trophies", titleDir: URL? = nil) -> XmbNode {
        let store = XmbToolsStore.shared
        if store.justOpened(id, isOpen(id)) { Task { await store.loadTrophies(titleDir: titleDir) } }
        let icon = XmbIcon.firmware(rco: XmbRco.psn, name: "item_tex_trophy", symbol: "trophy")
        let title = firmware.text(XmbRco.full, "msg_trophy_collection", String(localized: "Trophies"))
        let rows: [XmbNode]
        switch store.trophySets {
        case nil:
            rows = [XmbNode(id: id + ".loading", title: String(localized: "Reading trophy data…"), subtitle: nil, icon: icon, kind: .info)]
        case let sets? where sets.isEmpty && titleDir != nil:
            rows = [XmbNode(id: id + ".none", title: String(localized: "No trophies for this game"),
                            subtitle: String(localized: "This game either has no trophy set, or has not opened it yet; many games only do that once you reach a menu or start playing. Check again later in the session."),
                            icon: icon, kind: .info)]
        case let sets? where sets.isEmpty:
            rows = [XmbNode(id: id + ".none", title: String(localized: "No trophies yet"),
                            subtitle: String(localized: "Trophies appear here once you play a game that has them. The game installs its trophy set the first time it runs, and this reads the same data the console would. A game with no trophy set never adds one."),
                            icon: icon, kind: .info)]
        case let sets? where titleDir != nil && sets.count == 1:
            rows = sets[0].trophies.map { toolsTrophyRow($0, set: sets[0].id, icon: icon) }
        case let sets?:
            rows = sets.map { set in
                let earned = set.trophies.filter(\.unlocked).count
                let percent = set.trophies.isEmpty ? 0 : 100 * earned / set.trophies.count
                let folder = id + "." + set.id
                return XmbNode(id: folder, title: set.title, subtitle: "\(percent)%   \(earned) / \(set.trophies.count)",
                               icon: set.iconURL.map { .game(path: $0.path) } ?? icon,
                               kind: .folder(isOpen(folder) ? set.trophies.map { toolsTrophyRow($0, set: set.id, icon: icon) } : [toolsClosed(folder)]))
            }
        }
        return XmbNode(id: id, title: title, subtitle: nil, icon: icon, kind: .folder(rows))
    }

    private func toolsTrophyRow(_ trophy: Trophy, set: String, icon: XmbIcon) -> XmbNode {
        let secret = trophy.isSecret && !trophy.unlocked
        let grade: String? = switch trophy.grade {
        case .platinum?: String(localized: "Platinum")
        case .gold?: String(localized: "Gold")
        case .silver?: String(localized: "Silver")
        case .bronze?: String(localized: "Bronze")
        case nil: nil
        }
        let state = trophy.unlocked
            ? trophy.unlockedAt.map { String(localized: "Earned \($0.formatted(date: .abbreviated, time: .shortened))") } ?? String(localized: "Earned")
            : String(localized: "Not earned")
        let detail = secret ? String(localized: "This trophy is hidden") : (trophy.detail.isEmpty ? nil : trophy.detail)
        return XmbNode(id: "tools.trophy.\(set).\(trophy.id)", title: secret ? String(localized: "Hidden trophy") : trophy.name,
                       subtitle: [grade, state, detail].compactMap { $0 }.joined(separator: "   "),
                       icon: !secret && trophy.iconURL != nil ? .game(path: trophy.iconURL?.path) : icon, kind: .action)
    }

    /// Load / Manage Saves: each game's save states with their pictures, newest first. Cross loads one, booting its game;
    /// Triangle offers Load and Delete. Then Backup, and deleting every state.
    func toolsSaves(_ section: inout XmbSection) -> XmbNode {
        let store = XmbToolsStore.shared
        let id = "tools.saves"
        if store.justOpened(id, isOpen(id)) {
            Task { [settings] in await store.loadStates(settings: settings) }
        }
        let icon = Self.toolsUtilIcon
        var rows: [XmbNode] = []
        switch store.states {
        case nil:
            rows = [XmbNode(id: "tools.saves.loading", title: "…", subtitle: nil, icon: icon, kind: .info)]
        case let states? where states.isEmpty:
            rows = [XmbNode(id: "tools.saves.none", title: String(localized: "No save states yet"),
                            subtitle: String(localized: "Create a save state while a game is running, then manage or back it up here."),
                            icon: icon, kind: .info)]
        case let states?:
            var order: [String] = []
            for state in states where !order.contains(state.serial) { order.append(state.serial) }
            for serial in order {
                let own = states.filter { $0.serial == serial }
                let picture = own.compactMap(\.picture).first
                let title = library.entries.first { $0.serial == serial }?.title ?? serial
                rows.append(XmbNode(id: "tools.saves.game." + serial, title: title, subtitle: "\(serial)   \(own.count)",
                                    icon: picture.map { .game(path: $0) } ?? icon,
                                    kind: .folder(own.map { toolsStateRow(&section, $0, icon: icon) })))
            }
            rows.append(section.action(XmbNode(id: "tools.saves.backup", title: String(localized: "Backup"), subtitle: nil, icon: icon,
                                               kind: .action)) { [weak self] in self?.toolsBackUpStates(states) })
        }
        rows.append(section.action(XmbNode(id: "tools.saves.wipe", title: String(localized: "Delete all save states"), subtitle: nil, icon: icon,
                                           kind: .action)) { [weak self] in
            let question = String(localized: "Delete every save state?") + "\n"
                + String(localized: "This removes the whole save state folder, including files the manager cannot list. It cannot be undone.")
            self?.toolsConfirm(question, String(localized: "Delete")) { self?.toolsWipeStates() }
        })
        return XmbNode(id: id, title: String(localized: "Load / Manage Saves"), subtitle: nil, icon: icon, kind: .folder(rows))
    }

    private func toolsStateRow(_ section: inout XmbSection, _ state: XmbSavedState, icon: XmbIcon) -> XmbNode {
        let id = "tools.saves.state." + state.url.path
        section.options[id] = { [weak self] option in
            if option == "load" { self?.toolsLoadState(state) } else { self?.toolsDeleteState(state) }
        }
        let node = XmbNode(id: id, title: state.title, subtitle: XmbStamp.string(state.modified),
                           icon: state.picture.map { .game(path: $0) } ?? icon, kind: .action,
                           options: [.init(id: "load", title: String(localized: "Load save state")),
                                     .init(id: "delete", title: String(localized: "Delete"))])
        return section.action(node) { [weak self] in self?.toolsLoadState(state) }
    }

    private func toolsLoadState(_ state: XmbSavedState) {
        toolsConfirm(String(localized: "Load this save state?"), String(localized: "Load save state")) { [weak self] in
            guard let self else { return }
            guard let entry = library.entries.first(where: { $0.serial == state.serial }) else {
                return notice(String(localized: "Couldn't load that slot."))
            }
            guard EmulatorSession.stateEpoch(path: state.url.path) >= SaveStateFiles.formatEpoch else {
                return notice(String(localized: "This state was saved by a different version of ARMSX3 and can't be loaded in this one."))
            }
            let store = XmbToolsStore.shared
            store.pendingState = (state.serial, state.url.path)
            launch(entry)
            // A launch that was explained instead of started leaves nothing waiting.
            if playing == nil && updates.installingSerial == nil { store.pendingState = nil }
        }
    }

    private func toolsDeleteState(_ state: XmbSavedState) {
        toolsConfirm(state.deleteQuestion, String(localized: "Delete")) { [weak self] in
            guard let self else { return }
            guard state.delete() else { return notice(String(localized: "Couldn't delete that save state.")) }
            Task { await XmbToolsStore.shared.loadStates(settings: self.settings) }
        }
    }

    /// Copies every state into a dated folder in Files, where a backup can be taken off the device.
    private func toolsBackUpStates(_ states: [XmbSavedState]) {
        let stamp = Date().formatted(.iso8601.year().month().day().time(includingFractionalSeconds: false)).replacingOccurrences(of: ":", with: "-")
        let target = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Save State Backups", isDirectory: true).appendingPathComponent(stamp, isDirectory: true)
        Task {
            let count = await Task.detached(priority: .utility) {
                states.filter { state in
                    let folder = target.appendingPathComponent(state.serial, isDirectory: true)
                    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    return (try? FileManager.default.copyItem(at: state.url, to: folder.appendingPathComponent(state.url.lastPathComponent))) != nil
                }.count
            }.value
            notice(String(localized: "Backup · \(count)"), error: false)
        }
    }

    /// Everything under the save-state tree, listed or not: a save cut off part-way leaves files the list cannot show.
    private func toolsWipeStates() {
        Task {
            guard let root = await XmbToolsStore.stateRoot() else { return }
            let (count, bytes, failed) = await Task.detached(priority: .utility) { () -> (Int, Int64, Int) in
                let files = FileManager.default
                let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
                var count = 0, failed = 0, bytes: Int64 = 0
                let all = (files.enumerator(at: root, includingPropertiesForKeys: keys)?.allObjects as? [URL]) ?? []
                for file in all.reversed() {
                    let values = try? file.resourceValues(forKeys: Set(keys))
                    let size = Int64(values?.fileSize ?? 0)
                    if (try? files.removeItem(at: file)) != nil {
                        if values?.isRegularFile == true { count += 1; bytes += size }
                    } else if values?.isRegularFile == true, files.fileExists(atPath: file.path) {
                        failed += 1
                    }
                }
                return (count, bytes, failed)
            }.value
            let freed = XmbFileSize.text(bytes)
            // Two sentences, so each count picks its own plural.
            let deleted = String(localized: "Deleted \(count) files, \(freed) freed.")
            notice(failed > 0 ? deleted + " " + String(localized: "\(failed) could not be removed.") : deleted, error: false)
            await XmbToolsStore.shared.loadStates(settings: settings)
        }
    }

    /// Each game's patches where Android lists each game's mods: On or Off, applied at the next boot.
    func toolsPatches(_ section: inout XmbSection) -> XmbNode {
        let store = XmbToolsStore.shared
        let id = "tools.patches"
        let icon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3game_folder", symbol: "wrench.and.screwdriver")
        var rows = [
            section.action(XmbNode(id: "tools.patches.download", title: String(localized: "Download latest patches"), subtitle: nil,
                                   icon: icon, kind: .action)) { [weak self] in self?.toolsDownloadPatches() },
            section.action(XmbNode(id: "tools.patches.import", title: String(localized: "Import patch file…"), subtitle: nil,
                                   icon: icon, kind: .action)) { [weak self] in self?.pick(.tools(.patch)) },
        ]
        var seen = Set<String>()
        let games = isOpen(id) ? library.entries.filter { !$0.serial.isEmpty && seen.insert($0.serial).inserted }
            .sorted { $0.title.lowercased() < $1.title.lowercased() } : []
        let open = games.first { isOpen("tools.patches." + $0.serial) }?.serial
        if store.patchesOpened(open), let open { Task { await store.loadPatches(open) } }
        for game in games {
            let folder = "tools.patches." + game.serial
            let found = store.patches[game.serial]
            let subtitle = found.map { $0.isEmpty ? String(localized: "No patches yet") : String(localized: "\($0.count) patches") + ", " + String(localized: "\($0.filter(\.enabled).count) on") }
            var children = [toolsClosed(folder, open == game.serial ? "…" : "")]
            if open == game.serial, let found {
                children = found.isEmpty
                    ? [XmbNode(id: folder + ".none", title: String(localized: "No patches for this game yet. Download the latest patches or import a patch file."),
                               subtitle: nil, icon: icon, kind: .info)]
                    : found.map { toolsPatchRow(&section, $0, serial: game.serial, choices: [on, off], icon: icon) }
            }
            rows.append(XmbNode(id: folder, title: game.title, subtitle: subtitle ?? game.serial,
                                icon: .game(path: game.iconPath), kind: .folder(children)))
        }
        if isOpen(id), games.isEmpty {
            rows.append(XmbNode(id: "tools.patches.nogames", title: String(localized: "No games yet"), subtitle: nil, icon: icon, kind: .info))
        }
        return XmbNode(id: id, title: String(localized: "Patches"), subtitle: String(localized: "Changes apply the next time the game boots."),
                       icon: icon, kind: .folder(rows))
    }

    private func toolsPatchRow(_ section: inout XmbSection, _ patch: PatchRow, serial: String, choices: [String], icon: XmbIcon) -> XmbNode {
        let id = "tools.patch." + serial + "." + patch.id
        section.picks[id] = { choice in
            Task {
                _ = await Rpcs3Bridge.setPatchEnabled(choice == 0, hash: patch.hash, patchDescription: patch.name, serial: serial,
                                                      appVersion: patch.appVersion)
                await XmbToolsStore.shared.loadPatches(serial)
            }
        }
        var details: [String] = []
        if !patch.author.isEmpty { details.append(patch.author) }
        if !patch.version.isEmpty { details.append("v" + patch.version) }
        details.append(patch.appVersion == "all" ? String(localized: "All versions") : String(localized: "App version \(patch.appVersion)"))
        return XmbNode(id: id, title: patch.name, subtitle: choices[patch.enabled ? 0 : 1], icon: icon,
                       kind: .setting(choices: choices, selected: patch.enabled ? 0 : 1), description: details.joined(separator: " · "))
    }

    private func toolsDownloadPatches() {
        Task {
            switch await PatchDownload.fetch() {
            case 0: notice(String(localized: "Patches updated."), error: false)
            case -1: notice(String(localized: "Couldn't reach the patch server. Check your connection and try again."), error: true)
            default: notice(String(localized: "The patch server sent something unexpected; nothing changed."), error: true)
            }
            await XmbToolsStore.shared.reloadPatches()
        }
    }

    /// The picked file's access is held only while it is read.
    func importPatches(_ url: URL) {
        let started = url.startAccessingSecurityScopedResource()
        let text = try? String(contentsOf: url, encoding: .utf8)
        if started { url.stopAccessingSecurityScopedResource() }
        Task {
            let status = await Rpcs3Bridge.importPatches(text ?? "")
            notice(status == 0 ? String(localized: "Patches imported.")
                : String(localized: "That file isn't a patch file this version can read; nothing changed."), error: status != 0)
            await XmbToolsStore.shared.reloadPatches()
        }
    }
}

enum XmbFileSize {
    /// Every size the XMB shows, as XmbFiles.size writes it in Android's units.
    static func text(_ bytes: Int64) -> String {
        switch bytes {
        case (1 << 30)...: return String(format: "%.2f GB", Double(bytes) / Double(1 << 30))
        case (1 << 20)...: return String(format: "%.0f MB", Double(bytes) / Double(1 << 20))
        case (1 << 10)...: return String(format: "%.0f KB", Double(bytes) / Double(1 << 10))
        default: return "\(bytes) B"
        }
    }
}
