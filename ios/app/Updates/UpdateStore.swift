// SPDX-License-Identifier: GPL-2.0-only
// The one shared store of title-update lookups.

import Foundation
import Network
import Observation
import SwiftUI

struct NetworkState: Equatable, Sendable {
    var online: Bool
    var expensive: Bool
    var constrained: Bool
}

// The live behaviour unless the smoke app swaps a part of it.
struct UpdateSeams {
    var lookup: (@Sendable (String) async -> LookupOutcome)?
    var startsTransfers = true
    var network: NetworkState?
    var freeBytes: Int64?
    var appActive: Bool?
    var gameRunning: Bool?
    var successShown: Duration = .seconds(4)

    static let live = UpdateSeams()
}

@MainActor @Observable final class UpdateStore {
    nonisolated static let sessionID = "com.armsx3.updates"
    static let shared = UpdateStore()

    private(set) var lookups: [String: LookupOutcome] = [:]
    private(set) var jobs: [String: UpdateJob] = [:]
    private(set) var order: [String] = []
    private(set) var installed: [String: InstalledUpdate] = [:]
    private(set) var checking = false
    private(set) var checked = 0
    private(set) var total = 0
    private(set) var rowOrder: [String] = []
    private(set) var network = NetworkState(online: true, expensive: false, constrained: false)
    private(set) var installingSerial: String?
    private(set) var contentRevision = 0
    private(set) var holdQueue = false
    private(set) var result: UpdateResult?
    private(set) var removals = 0
    private(set) var unseenFailure = false
    private(set) var firmware: String?
    // The game a launch waits on while its package installs.
    private(set) var waitingLaunch: String?
    var openOnUpdates = false
    var updatesVisible = false
    var seams = UpdateSeams.live {
        didSet { networkChanged() }
    }

    @ObservationIgnored weak var library: LibraryModel?
    @ObservationIgnored private var session: URLSession?
    @ObservationIgnored private var tasks: [String: Int] = [:]
    @ObservationIgnored private var progressShown = ContinuousClock.now
    @ObservationIgnored private var folders: (game: URL, exdata: URL)?
    @ObservationIgnored private var monitored = NetworkState(online: true, expensive: false, constrained: false)
    @ObservationIgnored private let monitor = NWPathMonitor()
    @ObservationIgnored private var cancelling: Set<String> = []
    @ObservationIgnored private var heard: Set<String> = []
    @ObservationIgnored private var launched = false
    @ObservationIgnored private var eventsDone = false
    @ObservationIgnored private var eventWaiters: [CheckedContinuation<Void, Never>] = []
    @ObservationIgnored private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    @ObservationIgnored private var recheck: Task<Void, Never>?
    @ObservationIgnored private var installWaiters: [CheckedContinuation<Bool, Never>] = []
    @ObservationIgnored private var removing = false
    @ObservationIgnored private var tally = UpdateResult(updated: 0, failed: 0, name: nil)
    @ObservationIgnored private var firmwareAsked = false
    @ObservationIgnored private var launchWait: CheckedContinuation<Bool, Never>?
    // A package this launch fetched again: true from resume data, false from the start.
    @ObservationIgnored private(set) var refetched: [String: Bool] = [:]
    @ObservationIgnored private var attempts: [String: Int] = [:]
    @ObservationIgnored private var scans = 0
    @ObservationIgnored private var scanned: [String: Int] = [:]
    @ObservationIgnored private var retryAfter: Task<Void, Never>?
    // A package that passed its check and whose install the core asked to retry later.
    @ObservationIgnored private var verifiedAhead: URL?

    nonisolated static var journalURL: URL {
        LibraryModel.defaultDirectory.appendingPathComponent("updates.json")
    }

    nonisolated static var cacheFolder: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3-updates", isDirectory: true)
    }

    init() {
        load()
        monitor.pathUpdateHandler = { [weak self] path in
            let state = NetworkState(online: path.status == .satisfied, expensive: path.isExpensive,
                                     constrained: path.isConstrained)
            Task { @MainActor in self?.pathChanged(state) }
        }
        monitor.start(queue: DispatchQueue(label: "com.armsx3.updates.network", qos: .utility))
    }

    var appActive: Bool {
        seams.appActive ?? (UIApplication.shared.applicationState == .active)
    }

    var gameRunning: Bool {
        seams.gameRunning ?? (EmulatorSession.shared.isRunning || EmulatorSession.shared.restorePhase != .idle)
    }

    // A game stopping lets the held queue go on.
    func gameStateChanged() {
        if !gameRunning { holdQueue = false }
        pump()
    }

    func appBecameActive() {
        pump()
    }

    // Every launch holds the queue until the game stops or its screen closes, so nothing installs mid-boot.
    func holdForLaunch() {
        holdQueue = true
    }

    // Before a launch: holds the queue and waits for the package installing now. True when it installed.
    func finishCurrentInstall() async -> Bool {
        holdQueue = true
        guard installingSerial != nil else { return true }
        return await withCheckedContinuation { installWaiters.append($0) }
    }

    // A launch while a package installs: true once that package is done, false when the player gave up.
    func waitForInstall() async -> Bool {
        guard let serial = installingSerial else { return true }
        finishWait(false)
        waitingLaunch = jobs[serial]?.name ?? serial
        return await withCheckedContinuation { reply in
            launchWait = reply
            Task {
                _ = await finishCurrentInstall()
                finishWait(true)
            }
        }
    }

    // Don't Start Game: the install carries on and the held queue goes on with it.
    func cancelWait() {
        finishWait(false)
        gameStateChanged()
    }

    private func finishWait(_ started: Bool) {
        guard let reply = launchWait else { return }
        launchWait = nil
        waitingLaunch = nil
        reply.resume(returning: started)
    }

    var pill: PillState? {
        UpdateBoard.pill(jobs: Array(jobs.values), offline: !network.online, result: result)
    }

    var pillText: String? { pill.map(UpdateText.pill) }

    var pillFailed: Bool {
        if case .failed = pill { return true }
        return false
    }

    func failureSeen() {
        unseenFailure = false
        if let failed = result?.failed, failed > 0 { result = nil }
    }

    private func announce(_ words: String) {
        AccessibilityNotification.Announcement(words).post()
    }

    // Only a GD folder whose own PARAM.SFO names this serial; never while a game runs or anything else installs.
    func removeUpdate(_ serial: String, rollingBack: Bool = false) async -> Int {
        await readInstalled([serial])
        guard let state = installed[serial], InstalledContent.canRemoveUpdate(state, serial: serial), !gameRunning,
              installingSerial == (rollingBack ? serial : nil), InstallModel.claimForUpdate() else { return -3 }
        defer { InstallModel.releaseForUpdate() }
        removals += 1
        let status = await Rpcs3Bridge.uninstallGame(withSerial: serial)
        await readInstalled([serial])
        contentRevision += 1
        await library?.refresh()
        return status
    }

    // Update All: one job per game in the given order; a game that already has a job keeps it.
    func startAll(_ serials: [String], allowsCellular: Bool) {
        let names = Dictionary(listed().map { ($0.serial, $0.name) }, uniquingKeysWith: { a, _ in a })
        for serial in serials where jobs[serial]?.isFinished ?? true {
            let have = installed[serial]?.installedVersion
            guard case .found(let patch) = lookups[serial], let newest = patch.packages.last,
                  let chain = GameUpdates.chain(published: patch.packages, installed: have, base: installed[serial]?.discVersion,
                                                target: newest.version) else { continue }
            start(serial: serial, name: names[serial] ?? serial, chain: chain, installedBefore: have, allowsCellular: allowsCellular)
        }
    }

    // Try Again: from the failed package on; mobile data only when the confirm sheet said so.
    func retry(_ serial: String, allowsCellular: Bool = false) {
        guard let job = jobs[serial], job.isStopped else { return }
        // A task still running for a reset package would otherwise land beside its replacement.
        let stale = Set(job.packages.filter { $0.state == .downloading }.map { Self.taskName(serial, $0.package.version) })
        cancelTasks(named: stale)
        for name in stale {
            tasks[name] = nil
            attempts[name] = nil
        }
        var next = UpdateQueue.retry(job, allowsCellular: allowsCellular)
        next.waitingForNetwork = waits(next)
        jobs[serial] = next
        result = nil
        save()
        pump()
    }

    // Asks Sony nothing; reading the journal again here could overwrite an install the queue has started since.
    func launch() {
        guard !launched else { return }
        launched = true
        let session = transfers
        Task {
            let live = Set(await session.allTasks.compactMap(\.taskDescription))
            try? await Task.sleep(for: .seconds(2))
            reconcile(live)
        }
    }

    // Every library game with a title ID, once each, in the library's order.
    func listed() -> [(serial: String, name: String)] {
        var seen = Set<String>()
        return (library?.entries ?? []).compactMap { entry in
            guard let id = GameUpdates.titleID(entry.serial), seen.insert(id).inserted else { return nil }
            return (id, entry.title)
        }
    }

    // Only when asked. Fresh asks again for every game; otherwise only games with no answer yet.
    func check(fresh: Bool) async {
        guard !checking else { return }
        checking = true
        defer { checking = false }
        let games = listed()
        rowOrder = UpdateBoard.order(games.map { ($0.serial, $0.name, RowState.checking) })
        total = games.count
        checked = fresh ? 0 : games.filter { lookups[$0.serial] != nil }.count
        await readInstalled(games.map(\.serial))
        let offline = !network.online
        if !offline {
            await ask(games.map(\.serial).filter { fresh || lookups[$0] == nil })
            if !jobs.values.contains(where: { !$0.isFinished && !$0.isStopped }) {
                rowOrder = UpdateBoard.order(games.map { ($0.serial, $0.name, rowState($0.serial)) })
            }
        }
    }

    func checkOne(_ titleID: String) async {
        guard let id = GameUpdates.titleID(titleID) else { return }
        await readInstalled([id])
        await ask([id])
    }

    // What the version sheet needs: the installed state, the firmware, then a fresh answer when online.
    func prepareVersions(_ serial: String) async {
        guard let id = GameUpdates.titleID(serial) else { return }
        if installed[id] == nil { await readInstalled([id]) }
        await loadFirmware()
        if network.online { await checkOne(id) }
    }

    // Asked of the façade once per process.
    func loadFirmware() async {
        guard !firmwareAsked else { return }
        firmwareAsked = true
        let version = await Self.folder(Rpcs3Bridge.firmwareVersion)
        firmware = version.isEmpty ? nil : version
    }

    // Free space on the cache's volume, or the smoke app's figure.
    func availableBytes() -> Int64 {
        if let bytes = seams.freeBytes { return bytes }
        let values = try? Self.cacheFolder.deletingLastPathComponent()
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    func rowState(_ serial: String) -> RowState {
        UpdateBoard.rowState(lookup: lookups[serial], installed: installed[serial], job: jobs[serial], offline: !network.online)
    }

    // One job per game; a second start while one is unfinished changes nothing.
    func start(serial: String, name: String, chain: UpdateChain, installedBefore: String?, allowsCellular: Bool) {
        guard GameUpdates.titleID(serial) == serial, jobs[serial]?.isFinished ?? true else { return }
        var job = UpdateJob(serial: serial, name: name, chain: chain, installedBefore: installedBefore, allowsCellular: allowsCellular)
        job.waitingForNetwork = waits(job)
        jobs[serial] = job
        result = nil
        order.removeAll { $0 == serial }
        order.append(serial)
        cancelling.remove(serial)
        save()
        pump()
    }

    // Stops the job's downloads, deletes every file not installed and forgets the job. Never asks first.
    func cancel(_ serial: String, evenInstalling: Bool = false) {
        guard let job = jobs[serial], evenInstalling || job.installing == nil else { return }
        // A job started again later fetches to the same path, so an earlier hash must not carry over.
        verifiedAhead = nil
        cancelling.insert(serial)
        let files = UpdateQueue.cancelled(job)
        let names = Set(files.map { ($0 as NSString).deletingPathExtension })
        cancelTasks(named: names)
        for file in files { try? FileManager.default.removeItem(at: Self.cacheFolder.appendingPathComponent(file)) }
        for name in names { tasks[name] = nil }
        jobs[serial] = nil
        order.removeAll { $0 == serial }
        save()
        if job.isStopped { dropFailure() }
        pump()
    }

    // A failed job the player cancelled no longer counts as a failure on the pill.
    private func dropFailure() {
        if let shown = result, shown.failed > 0 {
            result = shown.failed > 1 ? UpdateResult(updated: shown.updated, failed: shown.failed - 1, name: shown.name) : nil
        } else if tally.failed > 0 {
            tally.failed -= 1
        }
        if (result?.failed ?? 0) == 0, tally.failed == 0 { unseenFailure = false }
    }

    // An uninstalled game takes its job with it, so no package installs into the emptied folder.
    func forgetAfterUninstall(_ serial: String) async {
        cancel(serial, evenInstalling: true)
        await readInstalled([serial])
    }

    // Returns only once the session has delivered every event the system woke the app for.
    static func finishBackgroundEvents() async {
        let store = shared
        _ = store.transfers
        if store.eventsDone {
            store.eventsDone = false
            return
        }
        await withCheckedContinuation { store.eventWaiters.append($0) }
    }

    nonisolated static func outOfSpace(_ error: Error) -> Bool {
        var next: NSError? = error as NSError
        while let error = next {
            if (error.domain == NSCocoaErrorDomain && error.code == NSFileWriteOutOfSpaceError)
                || (error.domain == NSPOSIXErrorDomain && error.code == Int(ENOSPC)) { return true }
            next = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    nonisolated static func taskName(_ serial: String, _ version: String) -> String {
        (GameUpdates.cacheName(titleID: serial, version: version) as NSString).deletingPathExtension
    }

    // Read from disk, since the delegate runs off the main actor; only a package still downloading, so a late duplicate loses.
    nonisolated static func journaled(_ name: String?) -> URL? {
        guard let name else { return nil }
        for job in UpdateJournal.load(from: journalURL).jobs {
            for item in job.packages where item.state == .downloading && taskName(job.serial, item.package.version) == name {
                return cacheFolder.appendingPathComponent(GameUpdates.cacheName(titleID: job.serial, version: item.package.version))
            }
        }
        return nil
    }

    // At most four lookups in flight; each answer is kept as it lands.
    private func ask(_ ids: [String]) async {
        guard !ids.isEmpty else { return }
        let lookup: @Sendable (String) async -> LookupOutcome = seams.lookup ?? { await SonyLookup.shared.outcome(for: $0) }
        var waiting = ids.makeIterator()
        await withTaskGroup(of: (String, LookupOutcome).self) { group in
            for _ in 0 ..< 4 {
                if let id = waiting.next() { group.addTask { (id, await lookup(id)) } }
            }
            while let answer = await group.next() {
                lookups[answer.0] = answer.1
                checked += 1
                if let id = waiting.next() { group.addTask { (id, await lookup(id)) } }
            }
        }
    }

    // A scan that started earlier never overwrites a newer one that finished first.
    private func readInstalled(_ serials: [String]) async {
        scans += 1
        let scan = scans
        guard let folders = await contentFolders() else { return }
        var discs: [String: URL] = [:]
        for entry in library?.entries ?? [] where !entry.isISO && entry.category == "DG" {
            discs[entry.serial] = URL(fileURLWithPath: entry.bootPath, isDirectory: true).appendingPathComponent("PARAM.SFO")
        }
        let found = await Task.detached(priority: .utility) {
            serials.map { InstalledContent.update(gameDir: folders.game, exdataDir: folders.exdata, serial: $0, discSFO: discs[$0]) }
        }.value
        for state in found where scanned[state.serial, default: 0] < scan {
            installed[state.serial] = state
            scanned[state.serial] = scan
        }
    }

    // Asked of the façade once per process, because a long install holds its one queue.
    private func contentFolders() async -> (game: URL, exdata: URL)? {
        if let folders { return folders }
        let hdd0 = await Self.folder(Rpcs3Bridge.hdd0Directory)
        let user = await Self.folder(Rpcs3Bridge.userDirectory)
        guard !hdd0.isEmpty, !user.isEmpty else { return nil }
        let found = (game: URL(fileURLWithPath: hdd0, isDirectory: true).appendingPathComponent("game", isDirectory: true),
                     exdata: URL(fileURLWithPath: user, isDirectory: true).appendingPathComponent("exdata", isDirectory: true))
        folders = found
        return found
    }

    private static func folder(_ query: (@escaping @Sendable (String, Int) -> Void) -> Void) async -> String {
        await withCheckedContinuation { continuation in
            query { path, status in continuation.resume(returning: status == 0 ? path : "") }
        }
    }

    private func pathChanged(_ state: NetworkState) {
        monitored = state
        networkChanged()
    }

    // A connection coming back while Updates is showing checks by itself.
    private func networkChanged() {
        let next = seams.network ?? monitored
        let returned = next.online && !network.online
        network = next
        for (serial, job) in jobs where !job.isFinished && job.waitingForNetwork != waits(job) {
            jobs[serial]?.waitingForNetwork = waits(job)
        }
        if returned && updatesVisible { Task { await check(fresh: false) } }
    }

    // Offline, or on mobile data the job was not allowed to use.
    private func waits(_ job: UpdateJob) -> Bool {
        !network.online || (!job.allowsCellular && (network.expensive || network.constrained))
    }

    private var transfers: URLSession {
        if let session { return session }
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionID)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        let made = URLSession(configuration: configuration, delegate: UpdateSessionDelegate(), delegateQueue: nil)
        session = made
        return made
    }

    private func load() {
        let journal = UpdateJournal.load(from: Self.journalURL)
        jobs = Dictionary(journal.jobs.map { ($0.serial, UpdateQueue.afterRelaunch($0)) }, uniquingKeysWith: { a, _ in a })
        order = journal.order + journal.jobs.map(\.serial).filter { !journal.order.contains($0) }
    }

    private func save() {
        try? UpdateJournal(jobs: order.compactMap { jobs[$0] }, order: order).save(to: Self.journalURL)
    }

    private func locate(_ name: String?) -> (serial: String, index: Int)? {
        for serial in order {
            guard let job = jobs[serial] else { continue }
            if let index = job.packages.firstIndex(where: { Self.taskName(serial, $0.package.version) == name }) {
                return (serial, index)
            }
        }
        return nil
    }

    private func file(_ serial: String, _ index: Int) -> URL? {
        guard let package = jobs[serial]?.packages[index].package else { return nil }
        return Self.cacheFolder.appendingPathComponent(GameUpdates.cacheName(titleID: serial, version: package.version))
    }

    // A callback from a task this process has since replaced is stale.
    private func current(_ name: String?, _ task: Int) -> Bool {
        name.flatMap { tasks[$0] }.map { $0 == task } ?? true
    }

    // One job at a time: its downloads, two packages ahead, then its next package installs.
    func pump() {
        // Without a prepared core nothing could install, so nothing downloads or retries either.
        guard !EmulatorSession.shared.browsing else { return }
        guard let serial = UpdateQueue.next(order: order, jobs: Array(jobs.values)), var job = jobs[serial] else {
            return settle()
        }
        let indices = UpdateQueue.downloads(job)
        if !indices.isEmpty {
            for index in indices { job.packages[index].state = .downloading }
            jobs[serial] = job
            save()
            for index in indices { download(job, index) }
        }
        let removal = UpdateQueue.needsRemoval(job) && job.packages.first?.state == .downloaded
        guard installingSerial == nil, !removing, retryAfter == nil, removal || UpdateQueue.nextInstall(job) != nil else { return }
        guard UpdateQueue.canInstall(appActive: appActive, gameRunning: gameRunning, otherInstall: InstallModel.installInFlight,
                                     held: holdQueue) else {
            // Only the Install screen is in the way: look again shortly.
            if InstallModel.installInFlight, recheck == nil,
               UpdateQueue.canInstall(appActive: appActive, gameRunning: gameRunning, otherInstall: false, held: holdQueue) {
                recheck = Task {
                    try? await Task.sleep(for: .seconds(2))
                    recheck = nil
                    pump()
                }
            }
            return
        }
        if removal { return rollBack(serial) }
        guard let index = UpdateQueue.nextInstall(job), let file = file(serial, index), InstallModel.claimForUpdate() else { return }
        let package = job.packages[index].package
        installingSerial = serial
        job.installing = index
        jobs[serial] = job
        save()
        UIApplication.shared.isIdleTimerDisabled = true
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "armsx3-update") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundTask() }
        }
        Task {
            var done = false
            defer {
                if jobs[serial]?.installing != nil {
                    jobs[serial]?.installing = nil
                    save()
                }
                installingSerial = nil
                endBackgroundTask()
                UIApplication.shared.isIdleTimerDisabled = false
                InstallModel.releaseForUpdate()
                let waiters = installWaiters
                installWaiters = []
                for waiter in waiters { waiter.resume(returning: done) }
                pump()
            }
            var check = PackageCheck.ok
            if file != verifiedAhead {
                check = await Task.detached(priority: .userInitiated) {
                    PackageDigest.verify(at: file, size: package.size, sha1: package.sha1)
                }.value
            }
            verifiedAhead = nil
            if check == .missing {
                // The system purged the cache; fetch it again.
                jobs[serial]?.packages[index].state = .waiting
                jobs[serial]?.packages[index].received = 0
                return
            }
            guard check == .ok else { return stop(serial, at: index, .damaged, status: nil) }
            let status: Int = await withCheckedContinuation { reply in
                Rpcs3Bridge.installPackages(atPaths: [file.path], progress: nil) { answer, _ in reply.resume(returning: answer) }
            }
            switch UpdateQueue.verdict(status: status) {
            case .installed:
                done = true
                try? FileManager.default.removeItem(at: file)
                if let current = jobs[serial] { jobs[serial] = UpdateQueue.installed(current, index) }
                save()
                await readInstalled([serial])
                contentRevision += 1
                await library?.refresh()
                if let finished = jobs[serial], finished.isFinished {
                    tally.updated += 1
                    tally.name = finished.name
                    let version = finished.packages.last.map { InstalledTitle.shortVersion($0.package.version) } ?? ""
                    announce(String(localized: "\(finished.name) updated to \(version)."))
                }
            case .retryLater:
                // The core is busy or not ready yet: look again shortly, without hashing the package again.
                verifiedAhead = file
                pumpLater()
            case .failed(let failure):
                stop(serial, at: index, failure, status: status)
            }
        }
    }

    // A rollback takes the current update off first, as the job's first install: a launch waits and Cancel is refused.
    private func rollBack(_ serial: String) {
        removing = true
        installingSerial = serial
        jobs[serial]?.installing = 0
        save()
        Task {
            let status = await removeUpdate(serial, rollingBack: true)
            removing = false
            installingSerial = nil
            jobs[serial]?.installing = nil
            let state = installed[serial]
            if status == 0 || (status == -3 && state?.folder == false) {
                // No folder left means an earlier removal already took it.
                jobs[serial]?.removed = true
                save()
            } else if status == -9 || (status == -3 && (state == nil || gameRunning || InstallModel.installInFlight)) {
                save()
                pumpLater()
            } else {
                stop(serial, at: 0, .removeFailed, status: status)
            }
            let waiters = installWaiters
            installWaiters = []
            for waiter in waiters { waiter.resume(returning: status == 0) }
            pump()
        }
    }

    // Looks again in a few seconds rather than straight away.
    private func pumpLater() {
        retryAfter?.cancel()
        retryAfter = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            retryAfter = nil
            pump()
        }
    }

    // When the queue empties, its games are counted once.
    private func settle() {
        guard installingSerial == nil, !removing, tally.updated + tally.failed > 0 else { return }
        let done = tally
        result = done
        if done.failed > 0 { unseenFailure = true }
        if done.updated + done.failed > 1 { announce(String(localized: "\(done.updated) updated.")) }
        tally = UpdateResult(updated: 0, failed: 0, name: nil)
        // A success shows for 4 seconds; a failure stays until it is seen.
        guard done.failed == 0 else { return }
        let shown = seams.successShown
        Task {
            try? await Task.sleep(for: shown)
            if result == done { result = nil }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func download(_ job: UpdateJob, _ index: Int, resumeData: Data? = nil) {
        guard seams.startsTransfers else { return }
        let package = job.packages[index].package
        var request = URLRequest(url: package.url)
        request.allowsExpensiveNetworkAccess = job.allowsCellular
        request.allowsConstrainedNetworkAccess = job.allowsCellular
        let task = resumeData.map { transfers.downloadTask(withResumeData: $0) } ?? transfers.downloadTask(with: request)
        let name = Self.taskName(job.serial, package.version)
        task.taskDescription = name
        task.countOfBytesClientExpectsToReceive = package.size
        tasks[name] = task.taskIdentifier
        task.resume()
    }

    private func cancelTasks(named names: Set<String>) {
        transfers.getAllTasks { all in
            for task in all where names.contains(task.taskDescription ?? "") { task.cancel() }
        }
    }

    // A package the session lost starts again, unless its whole file reached the cache, which the install checks.
    private func reconcile(_ live: Set<String>) {
        for serial in order {
            guard let job = jobs[serial], !job.isStopped else { continue }
            for index in job.packages.indices where job.packages[index].state == .downloading {
                let name = Self.taskName(serial, job.packages[index].package.version)
                guard !live.contains(name), !heard.contains(name), tasks[name] == nil else { continue }
                let size = file(serial, index).flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize }
                if size.map(Int64.init) == job.packages[index].package.size {
                    downloaded(serial, index)
                } else {
                    refetch(serial, index, resumeData: nil)
                }
            }
        }
        pump()
    }

    // From the resume data when there is some, else from the start, after a pause that grows with each failure.
    private func refetch(_ serial: String, _ index: Int, resumeData: Data?) {
        guard let job = jobs[serial] else { return }
        let name = Self.taskName(serial, job.packages[index].package.version)
        refetched[name] = resumeData != nil
        let tries = attempts[name, default: 0]
        attempts[name] = tries + 1
        Task {
            try? await Task.sleep(for: .seconds(UpdateQueue.refetchDelay(tries)))
            guard let job = jobs[serial], !job.isStopped, !cancelling.contains(serial), tasks[name] == nil,
                  job.packages[index].state == .downloading else { return }
            download(job, index, resumeData: resumeData)
        }
    }

    fileprivate func eventsFinished() {
        guard !eventWaiters.isEmpty else {
            eventsDone = true
            return
        }
        eventWaiters.forEach { $0.resume() }
        eventWaiters = []
    }

    fileprivate func completed(_ name: String?, task: Int, status: Int?, code: Int?, outOfSpace: Bool, resumeData: Data?) {
        if let name { heard.insert(name) }
        guard current(name, task), let spot = locate(name), !cancelling.contains(spot.serial), let job = jobs[spot.serial],
              !job.isStopped, job.packages[spot.index].state == .downloading else { return }
        if let name { tasks[name] = nil }
        switch UpdateQueue.downloadVerdict(httpStatus: code == nil ? status : nil, errorCode: code, outOfSpace: outOfSpace) {
        case .ok:
            return
        case .stop(let failure):
            stop(spot.serial, at: spot.index, failure, status: code == nil ? status : nil)
        case .resume:
            refetch(spot.serial, spot.index, resumeData: resumeData)
        }
    }

    fileprivate func progress(_ name: String?, task: Int, bytes: Int64) {
        if let name { heard.insert(name) }
        guard current(name, task), let spot = locate(name), let package = jobs[spot.serial]?.packages[spot.index],
              package.state == .downloading else { return }
        // A fast link reports many times a second, and each write redraws everything that shows the progress.
        let now = ContinuousClock.now
        guard abs(bytes - package.received) >= package.package.size / 100 || now - progressShown >= .milliseconds(250) else { return }
        progressShown = now
        jobs[spot.serial]?.packages[spot.index].received = bytes
    }

    // The file is already in the cache, or `file` is nil when the move failed; the digest runs unless a game is running.
    fileprivate func arrived(_ name: String?, task: Int, file: URL?, outOfSpace: Bool) {
        if let name { heard.insert(name) }
        // A new file is unchecked, whatever was hashed at that path before.
        if file == verifiedAhead { verifiedAhead = nil }
        guard let spot = locate(name), !cancelling.contains(spot.serial) else {
            if let file { try? FileManager.default.removeItem(at: file) }
            return
        }
        guard current(name, task) else { return }
        guard let job = jobs[spot.serial], !job.isStopped, job.packages[spot.index].state == .downloading else {
            if let file, jobs[spot.serial]?.packages[spot.index].state != .downloaded { try? FileManager.default.removeItem(at: file) }
            return
        }
        if let name { tasks[name] = nil }
        guard let file else {
            if outOfSpace { return stop(spot.serial, at: spot.index, .space, status: nil) }
            return refetch(spot.serial, spot.index, resumeData: nil)
        }
        let package = job.packages[spot.index].package
        guard !gameRunning else { return downloaded(spot.serial, spot.index) }
        Task {
            let check = await Task.detached(priority: .utility) {
                PackageDigest.verify(at: file, size: package.size, sha1: package.sha1)
            }.value
            guard let now = jobs[spot.serial], now.packages[spot.index].state == .downloading else { return }
            // The chain stopped while this hashed; Try Again fetches it again.
            guard !now.isStopped else {
                try? FileManager.default.removeItem(at: file)
                return
            }
            if check == .ok {
                downloaded(spot.serial, spot.index)
            } else {
                stop(spot.serial, at: spot.index, .damaged, status: nil)
            }
        }
    }

    private func downloaded(_ serial: String, _ index: Int) {
        guard let job = jobs[serial] else { return }
        let next = UpdateQueue.downloaded(job, index)
        jobs[serial] = next
        attempts[Self.taskName(serial, job.packages[index].package.version)] = nil
        save()
        pump()
    }

    // Stops the chain and deletes the files from the failed package on; installed packages stay.
    private func stop(_ serial: String, at index: Int, _ failure: JobFailure, status: Int?) {
        guard let job = jobs[serial], job.packages.indices.contains(index) else { return }
        verifiedAhead = nil
        let stopped = UpdateQueue.stop(job, at: index, failure: failure, status: status)
        jobs[serial] = stopped
        save()
        var names = Set<String>()
        for later in index ..< stopped.packages.count where stopped.packages[later].state != .installed {
            names.insert(Self.taskName(serial, stopped.packages[later].package.version))
            if let url = file(serial, later) { try? FileManager.default.removeItem(at: url) }
        }
        cancelTasks(named: names)
        // A job stopped twice before Try Again is still one failed game.
        if !job.isStopped { tally.failed += 1 }
        unseenFailure = true
        let version = InstalledTitle.shortVersion(job.packages[index].package.version)
        announce(String(localized: "\(job.name) stopped at \(version)."))
        pump()
    }
}

// The session's callbacks, on its own queue: a finished file is moved before this method returns.
final class UpdateSessionDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let task = downloadTask.taskIdentifier, name = downloadTask.taskDescription
        guard let code = (downloadTask.response as? HTTPURLResponse)?.statusCode, code == 200 || code == 206,
              let target = UpdateStore.journaled(name)
        else { return }
        let files = FileManager.default
        var moved: URL? = target, space = false
        do {
            try files.createDirectory(at: UpdateStore.cacheFolder, withIntermediateDirectories: true)
            if files.fileExists(atPath: target.path) { try files.removeItem(at: target) }
            try files.moveItem(at: location, to: target)
        } catch {
            moved = nil
            space = UpdateStore.outOfSpace(error)
        }
        let file = moved, outOfSpace = space
        Task { @MainActor in UpdateStore.shared.arrived(name, task: task, file: file, outOfSpace: outOfSpace) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskIdentifier, name = task.taskDescription
        let status = (task.response as? HTTPURLResponse)?.statusCode
        let failure = error as NSError?
        let code = failure?.domain == NSURLErrorDomain ? failure?.code : (failure == nil ? nil : URLError.Code.unknown.rawValue)
        let resumeData = failure?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        let space = error.map(UpdateStore.outOfSpace) ?? false
        Task { @MainActor in
            UpdateStore.shared.completed(name, task: id, status: status, code: code, outOfSpace: space, resumeData: resumeData)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in UpdateStore.shared.eventsFinished() }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let task = downloadTask.taskIdentifier, name = downloadTask.taskDescription
        Task { @MainActor in UpdateStore.shared.progress(name, task: task, bytes: totalBytesWritten) }
    }
}
