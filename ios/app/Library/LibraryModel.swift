// SPDX-License-Identifier: GPL-2.0-only
// The library: the cache at once, then prepare, resolve bookmarks, register, scan, save. A non-zero status is an error.

import Foundation
import Observation

@MainActor @Observable final class LibraryModel {
    enum Phase: Equatable { case loading, ready, error(Int) }

    struct FolderRow: Identifiable {
        let folder: BookmarkStore.Folder
        let available: Bool
        var id: UUID { folder.id }
    }

    static let favouritesKey = "armsx3.library.favourites"
    // Boot paths of games removed from the library while their files stay where they are.
    static let hiddenKey = "armsx3.library.hidden"
    // The player's one answer to "move folders out of a temporary place?".
    static let moveTemporaryKey = "armsx3.library.moveTemporary"

    nonisolated static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
    }

    private(set) var entries: [LibraryCache.Entry] = []
    private(set) var phase: Phase = .loading
    private(set) var favourites: Set<String>
    private(set) var hidden: Set<String>
    private(set) var folders: [FolderRow] = []
    private(set) var askingMove = false
    private var moveWaiters: [CheckedContinuation<Void, Never>] = []

    // Only scanned façade objects can boot; a cached entry becomes playable once the scan behind it lands.
    private var live: [String: Rpcs3GameEntry] = [:]
    private var started = false
    // Each refresh takes a generation and writes nothing after an await once a newer one exists, so the newest scan wins.
    private var refreshGeneration = 0
    private var lastSave: Task<Int, Never>?
    private let cache: LibraryCache
    private let store: BookmarkStore
    private let defaults: UserDefaults

    init(directory: URL = LibraryModel.defaultDirectory, defaults: UserDefaults = .standard) {
        cache = LibraryCache(directory: directory)
        store = BookmarkStore(directory: directory)
        self.defaults = defaults
        favourites = Set(defaults.stringArray(forKey: Self.favouritesKey) ?? [])
        hidden = Set(defaults.stringArray(forKey: Self.hiddenKey) ?? [])
    }

    private var cacheKey: String { LibraryCache.key(folderIDs: store.folders.map(\.id)) }

    /// Runs once; later calls return at once.
    func start() async {
        guard !started else { return }
        started = true
        let cached = cache.load(expectedKey: cacheKey)
        if let cached { publish(cached) }
        await refresh()
    }

    /// `probeKnown` false asks the licence probe only about titles new to this scan, as a quick rescan on return does.
    func refresh(probeKnown: Bool = true) async {
        refreshGeneration += 1
        let generation = refreshGeneration
        // Without JIT the core is never prepared, so the cached list is all there is.
        guard !EmulatorSession.shared.browsing else {
            phase = .ready
            return
        }
        phase = .loading
        let (hostErrno, initStatus) = await Rpcs3Bridge.prepare()
        guard generation == refreshGeneration else { return }
        guard hostErrno == 0, initStatus == 0 else {
            phase = .error(initStatus != 0 ? initStatus : hostErrno)
            return
        }
        let resolved = migrateTemporary(store.resolveAll())
        folders = resolved.map { FolderRow(folder: $0.0, available: $0.1 != nil) }
        for case let (_, url?) in resolved {
            // A folder that no longer registers is left out of this scan; the others still count.
            _ = await Rpcs3Bridge.registerGameFolder(atPath: url.path)
        }
        let (games, status) = await Rpcs3Bridge.scanLibrary()
        guard generation == refreshGeneration else { return }
        guard status == 0 else {
            phase = .error(status)
            return
        }
        live = Dictionary(games.map { ($0.bootPath, $0) }, uniquingKeysWith: { first, _ in first })
        // The last known probe answers carry over until this scan's probe pass lands.
        let known = Dictionary(entries.map { ($0.bootPath, $0) }, uniquingKeysWith: { first, _ in first })
        let scanned = games.map { game -> LibraryCache.Entry in
            var entry = Self.entry(game)
            entry.locked = known[entry.bootPath]?.locked
            entry.unreadable = known[entry.bootPath]?.unreadable
            return entry
        }
        let key = cacheKey
        let cache = self.cache
        let previousSave = lastSave
        // The cache write reads the disk, so it leaves the main thread.
        let save = Task.detached(priority: .utility) { () -> Int in
            _ = await previousSave?.value
            do { try cache.save(key: key, entries: scanned) } catch {}
            return 0
        }
        lastSave = save
        _ = await save.value
        guard generation == refreshGeneration else { return }
        publish(scanned)
        phase = .ready
        probeLocks(generation, known: probeKnown)
    }

    func reprobe(serial: String) async {
        let result = await Self.probe([serial])
        guard let answer = result.answers[serial] else { return }
        apply([serial: answer])
    }

    private func probeLocks(_ generation: Int, known: Bool = true) {
        let eligible = entries.filter { entry in !entry.fromGamesYml && entry.category == "HG" && (known || entry.locked == nil) }
            .map(\.serial)
        guard !eligible.isEmpty else { return }
        Task {
            let result = await Self.probe(eligible)
            guard generation == refreshGeneration else { return }
            apply(result.answers)
        }
    }

    // A failed probe leaves that title's answer as it was; the first non-zero status is reported.
    private nonisolated static func probe(_ serials: [String]) async -> (answers: [String: Int], status: Int) {
        await Task.detached(priority: .utility) {
            var answers: [String: Int] = [:]
            var first = 0
            for serial in serials {
                let (answer, _, status) = await Rpcs3Bridge.probeLicence(forSerial: serial)
                if status == 0 { answers[serial] = answer } else if first == 0 { first = status }
            }
            return (answers: answers, status: first)
        }.value
    }

    private func apply(_ answers: [String: Int]) {
        guard !answers.isEmpty else { return }
        publish(entries.map { entry in
            var entry = entry
            // 0 readable, 1 needs a licence, 2 can't be read and a licence would not help.
            if let answer = answers[entry.serial] {
                entry.locked = answer == 1
                entry.unreadable = answer == 2
            }
            return entry
        })
        persist(entries)
    }

    // Chained after the previous save, so saves land on disk in the order they started.
    private func persist(_ list: [LibraryCache.Entry]) {
        let key = cacheKey
        let cache = self.cache
        let previousSave = lastSave
        lastSave = Task.detached(priority: .utility) { () -> Int in
            _ = await previousSave?.value
            do { try cache.save(key: key, entries: list) } catch {}
            return 0
        }
    }

    /// False when the bookmark could not be made, so setup's games step can say the folder could not be read.
    @discardableResult func addFolder(_ url: URL) async -> Bool {
        let url = await relocated(url)
        do {
            _ = try store.add(url)
        } catch {
            return false
        }
        _ = await Rpcs3Bridge.registerGameFolder(atPath: url.path)
        await refresh()
        return true
    }

    /// Bookmark only, for setup with JIT off: no emulator call, so nothing initialises the emulator.
    func rememberFolder(_ url: URL) async throws {
        let url = await relocated(url)
        _ = try store.add(url)
    }

    // Only after the player answered Move; with no answer yet the ask is raised and the pick waits for it.
    private func relocated(_ url: URL) async -> URL {
        if moveAnswer == nil, LibraryRelocation.isTemporary(url) {
            askingMove = true
            await withCheckedContinuation { moveWaiters.append($0) }
        }
        return moveIfAllowed(url) ?? url
    }

    private var moveAnswer: Bool? { defaults.object(forKey: Self.moveTemporaryKey) as? Bool }

    private func mayMoveNow() -> Bool {
        switch moveAnswer {
        case true?:
            return true
        case false?:
            return false
        case nil:
            askingMove = true
            return false
        }
    }

    // The only place the model moves anything. isTemporary comes first, so a folder kept elsewhere never raises the ask.
    private func moveIfAllowed(_ url: URL) -> URL? {
        guard LibraryRelocation.isTemporary(url), mayMoveNow(),
              case .moved(let destination) = LibraryRelocation.relocate(url) else { return nil }
        return destination
    }

    func answerMove(_ move: Bool) {
        guard askingMove else { return }
        defaults.set(move, forKey: Self.moveTemporaryKey)
        askingMove = false
        let waiting = moveWaiters
        moveWaiters = []
        for waiter in waiting { waiter.resume() }
        // A JIT-off remember that waited carries on by itself, so a JIT-off Move never reaches Rpcs3Bridge.prepare.
        if move && waiting.isEmpty { Task { await refresh() } }
    }

    // Synchronous on purpose: no await, so the refresh's generation rule is untouched.
    private func migrateTemporary(_ resolved: [(BookmarkStore.Folder, URL?)]) -> [(BookmarkStore.Folder, URL?)] {
        var moved = false
        func keep(_ destination: URL) -> Bool {
            do { _ = try store.add(destination); return true } catch {
                return false
            }
        }
        for case let (folder, url?) in resolved {
            guard let destination = moveIfAllowed(url) else { continue }
            moved = true
            // The old row goes only once the new one exists; a failed add keeps it.
            if keep(destination) { store.remove(folder.id) }
        }
        for entry in entries where FileManager.default.fileExists(atPath: entry.bootPath) {
            guard let item = LibraryRelocation.item(containing: entry.bootPath),
                  let destination = moveIfAllowed(item) else { continue }
            moved = true
            _ = keep(destination)
        }
        return moved ? store.resolveAll() : resolved
    }

    /// Read from the bookmarks, so it holds with JIT off as well.
    var folderCount: Int { store.folders.count }

    /// games.yml keeps the titles it registered; the ABI has no call that unregisters them.
    func removeFolder(_ id: UUID) async {
        store.remove(id)
        await refresh()
    }

    /// The bootable façade object for a shown entry, or nil while only the cache has it.
    func game(for entry: LibraryCache.Entry) -> Rpcs3GameEntry? { live[entry.bootPath] }

    /// The entry at a boot path, read again from the folders when a disc copied in since the last refresh is missing.
    func entry(bootPath: String) async -> LibraryCache.Entry? {
        if let entry = entries.first(where: { $0.bootPath == bootPath }) { return entry }
        await refresh()
        return entries.first { $0.bootPath == bootPath }
    }

    func isFavourite(_ entry: LibraryCache.Entry) -> Bool { favourites.contains(entry.serial) }

    /// Kept per serial on this device, and sorted first.
    func toggleFavourite(_ entry: LibraryCache.Entry) {
        if favourites.remove(entry.serial) == nil { favourites.insert(entry.serial) }
        defaults.set(favourites.sorted(), forKey: Self.favouritesKey)
        publish(entries)
    }

    var hiddenCount: Int { hidden.count }

    /// Takes the game out of the library; its files are left alone.
    func hide(_ entry: LibraryCache.Entry) {
        hidden.insert(entry.bootPath)
        defaults.set(hidden.sorted(), forKey: Self.hiddenKey)
        publish(entries)
    }

    func showHidden() async {
        hidden = []
        defaults.removeObject(forKey: Self.hiddenKey)
        await refresh()
    }

    private func publish(_ list: [LibraryCache.Entry]) {
        entries = list.filter { !hidden.contains($0.bootPath) }.sorted { a, b in
            let fa = favourites.contains(a.serial), fb = favourites.contains(b.serial)
            if fa != fb { return fa }
            return a.title.localizedStandardCompare(b.title) == .orderedAscending
        }
    }

    private static func entry(_ game: Rpcs3GameEntry) -> LibraryCache.Entry {
        LibraryCache.Entry(serial: game.serial, title: game.title, category: game.category, bootPath: game.bootPath,
                           iconPath: game.iconPath, backdropPath: game.backdropPath, isISO: game.isISO,
                           fromGamesYml: game.fromGamesYml)
    }
}
