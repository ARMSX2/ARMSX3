// SPDX-License-Identifier: GPL-2.0-only
// Game update jobs, the rules that move them, and the journal that keeps them.

import Foundation

enum JobFailure: String, Codable, Sendable {
    case damaged, space, needsEarlier, writeFailed, invalid, missingOnServer, interrupted, installFailed, removeFailed
}

enum PackageState: String, Codable, Sendable {
    case waiting, downloading, downloaded, installed
}

struct JobPackage: Codable, Equatable, Sendable {
    let package: TitlePackage
    var state: PackageState
    var received: Int64
}

// One game's chain of packages, from the player's confirm until the last one installs.
struct UpdateJob: Codable, Equatable, Sendable {
    let serial: String
    let name: String
    var packages: [JobPackage]
    let removesFirst: Bool
    var removed: Bool
    var allowsCellular: Bool
    let installedBefore: String?
    var failure: JobFailure?
    var failedAt: Int?
    var status: Int?
    var waitingForNetwork: Bool
    var installing: Int?

    init(serial: String, name: String, chain: UpdateChain, installedBefore: String?, allowsCellular: Bool) {
        self.serial = serial
        self.name = name
        packages = chain.packages.map { JobPackage(package: $0, state: .waiting, received: 0) }
        removesFirst = chain.removesFirst
        removed = false
        self.allowsCellular = allowsCellular
        self.installedBefore = installedBefore
        waitingForNetwork = false
    }

    var bytes: Int64 { packages.reduce(0) { $0 + $1.package.size } }

    var receivedBytes: Int64 {
        packages.reduce(0) { total, item in
            let done = item.state == .downloaded || item.state == .installed
            return total + (done ? item.package.size : min(max(item.received, 0), item.package.size))
        }
    }

    var percent: Int { Self.percent(receivedBytes, of: bytes) }
    var isFinished: Bool { packages.allSatisfy { $0.state == .installed } }
    var isStopped: Bool { failure != nil }

    static func percent(_ received: Int64, of total: Int64) -> Int {
        total > 0 ? Int(min(max(received, 0), total) * 100 / total) : 0
    }

    // The journal is read back from disk, so every field that names a file or a URL is checked again.
    fileprivate var isSound: Bool {
        let indices = packages.indices
        guard GameUpdates.titleID(serial) == serial, !packages.isEmpty, packages.count <= VerXml.maxPackages,
              failedAt.map(indices.contains) ?? true, installing.map(indices.contains) ?? true else { return false }
        return packages.allSatisfy { item in
            let package = item.package
            let version = Array(package.version.utf8), sha1 = package.sha1.utf8
            let shaped = (version.count == 4 || version.count == 5) && version[version.count - 3] == 0x2E
                && version.indices.allSatisfy { $0 == version.count - 3 || (0x30...0x39).contains(version[$0]) }
            return shaped && package.size > 0x20 && package.size <= 32 << 30 && sha1.count == 40
                && sha1.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
                && GameUpdates.isPackageURL(package.url, titleID: serial)
        }
    }
}

struct UpdateJournal: Codable, Equatable {
    var version = 1
    var jobs: [UpdateJob] = []
    var order: [String] = []

    // Anything unreadable loses only unfinished jobs; installed games are on disk, not here.
    static func load(from url: URL) -> UpdateJournal {
        guard let data = try? Data(contentsOf: url),
              let journal = try? JSONDecoder().decode(UpdateJournal.self, from: data), journal.version == 1 else {
            return UpdateJournal()
        }
        var jobs: [UpdateJob] = []
        for job in journal.jobs where job.isSound && !jobs.contains(where: { $0.serial == job.serial }) {
            jobs.append(job)
        }
        return UpdateJournal(jobs: jobs, order: journal.order.filter { serial in jobs.contains { $0.serial == serial } })
    }

    func save(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        try encoder.encode(self).write(to: url, options: .atomic)
    }
}

enum InstallVerdict: Equatable {
    case installed, retryLater, failed(JobFailure)
}

enum DownloadVerdict: Equatable {
    case ok, resume, stop(JobFailure)
}

enum UpdateQueue {
    static let downloadsAhead = 2

    // The packages to start fetching: the next one to install and two ahead of it.
    static func downloads(_ job: UpdateJob) -> [Int] {
        guard job.failure == nil, let first = job.packages.firstIndex(where: { $0.state != .installed }) else { return [] }
        return (first ..< min(first + downloadsAhead + 1, job.packages.count)).filter { job.packages[$0].state == .waiting }
    }

    // Strictly in order: only the first package not yet installed, once it has downloaded.
    static func nextInstall(_ job: UpdateJob) -> Int? {
        guard job.failure == nil, job.installing == nil, !needsRemoval(job),
              let first = job.packages.firstIndex(where: { $0.state != .installed }),
              job.packages[first].state == .downloaded else { return nil }
        return first
    }

    static func needsRemoval(_ job: UpdateJob) -> Bool {
        job.removesFirst && !job.removed
    }

    static func canInstall(appActive: Bool, gameRunning: Bool, otherInstall: Bool, held: Bool) -> Bool {
        appActive && !gameRunning && !otherInstall && !held
    }

    static func downloaded(_ job: UpdateJob, _ index: Int) -> UpdateJob {
        mark(job, index, .downloaded)
    }

    static func installed(_ job: UpdateJob, _ index: Int) -> UpdateJob {
        var job = mark(job, index, .installed)
        job.installing = nil
        return job
    }

    private static func mark(_ job: UpdateJob, _ index: Int, _ state: PackageState) -> UpdateJob {
        guard job.packages.indices.contains(index) else { return job }
        var job = job
        job.packages[index].state = state
        job.packages[index].received = job.packages[index].package.size
        return job
    }

    // Installed packages stay installed; the store deletes the files fetched ahead.
    static func stop(_ job: UpdateJob, at index: Int, failure: JobFailure, status: Int?) -> UpdateJob {
        var job = job
        job.failure = failure
        job.failedAt = index
        job.status = status
        job.installing = nil
        return job
    }

    // Every package mid-download starts again; a downloaded one keeps its file, which the install checks again.
    static func retry(_ job: UpdateJob, allowsCellular: Bool = false) -> UpdateJob {
        var job = job
        job.allowsCellular = job.allowsCellular || allowsCellular
        for index in job.packages.indices where job.packages[index].state == .downloading {
            job.packages[index].state = .waiting
            job.packages[index].received = 0
        }
        job.failure = nil
        job.failedAt = nil
        job.status = nil
        job.waitingForNetwork = false
        return job
    }

    // Cache file names to delete on Cancel, built from the fields and never from a URL.
    static func cancelled(_ job: UpdateJob) -> [String] {
        job.packages.filter { $0.state != .installed }.map { GameUpdates.cacheName(titleID: job.serial, version: $0.package.version) }
    }

    static func afterRelaunch(_ job: UpdateJob) -> UpdateJob {
        guard let index = job.installing else { return job }
        return stop(job, at: index, failure: .interrupted, status: nil)
    }

    static func next(order: [String], jobs: [UpdateJob]) -> String? {
        order.first { serial in jobs.contains { $0.serial == serial && !$0.isFinished && !$0.isStopped } }
    }

    static func needsEarlier(_ job: UpdateJob, at index: Int) -> String? {
        index > 0 && index <= job.packages.count ? job.packages[index - 1].package.version : job.installedBefore
    }

    // Seconds before a failed download tries again: doubling from 2, at most 5 minutes.
    static func refetchDelay(_ attempt: Int) -> Int {
        min(2 << min(max(attempt, 0), 8), 300)
    }

    // A game running (-9) or a core not yet prepared (-4) is a wait, not a failure.
    static func verdict(status: Int) -> InstallVerdict {
        switch status {
        case 0: return .installed
        case -9, -4: return .retryLater
        case -14: return .failed(.invalid)
        case -15: return .failed(.needsEarlier)
        case -16: return .failed(.writeFailed)
        default: return .failed(.installFailed)
        }
    }

    // Errors no retry can clear; any other network error waits and tries again.
    static let permanentErrors: Set<URLError.Code> = [
        .badURL, .unsupportedURL, .appTransportSecurityRequiresSecureConnection, .fileDoesNotExist, .secureConnectionFailed,
        .serverCertificateHasBadDate, .serverCertificateUntrusted, .serverCertificateHasUnknownRoot,
        .serverCertificateNotYetValid, .clientCertificateRejected, .clientCertificateRequired,
    ]

    static func downloadVerdict(httpStatus: Int?, errorCode: Int?, outOfSpace: Bool) -> DownloadVerdict {
        if outOfSpace { return .stop(.space) }
        if let code = errorCode { return permanentErrors.contains(URLError.Code(rawValue: code)) ? .stop(.installFailed) : .resume }
        // A resumed transfer answers 206; the size and SHA-1 check proves the bytes either way.
        switch httpStatus {
        case 200, 206: return .ok
        case 404, 410: return .stop(.missingOnServer)
        default: return .resume
        }
    }
}

// The whole chain, its three largest packages again while they sit on disk, and a margin.
enum FreeSpace {
    static let margin: Int64 = 512 << 20

    static func needed(sizes: [Int64]) -> Int64 {
        sizes.reduce(0, +) + sizes.sorted(by: >).prefix(UpdateQueue.downloadsAhead + 1).reduce(0, +) + margin
    }

    static func shortfall(available: Int64, sizes: [Int64]) -> Int64 {
        max(0, needed(sizes: sizes) - available)
    }

    static func freeAfter(available: Int64, sizes: [Int64]) -> Int64 {
        available - sizes.reduce(0, +)
    }
}

enum RowState: Equatable, Sendable {
    case checking
    case couldNotCheck(LookupFailure)
    case noUpdates
    case upToDate(String)
    case available(from: String?, to: String, packages: Int, bytes: Int64)
    case waiting
    case downloading(version: String, index: Int, count: Int, percent: Int)
    case waitingForConnection(percent: Int)
    case ready
    case installing(version: String, index: Int, count: Int)
    case stopped(at: String, failure: JobFailure, status: Int?, needs: String?)
    case offline(installed: String?)
}

enum PillState: Equatable, Sendable {
    case downloading(games: Int, percent: Int)
    case offline(games: Int)
    case installing(name: String, version: String)
    case finished(name: String?, count: Int)
    case failed(updated: Int, failed: Int)
}

struct UpdateResult: Equatable, Sendable {
    var updated: Int
    var failed: Int
    var name: String?
}

enum UpdateBoard {
    // A job's state wins over the lookup; offline shows the installed version only.
    static func rowState(lookup: LookupOutcome?, installed: InstalledUpdate?, job: UpdateJob?, offline: Bool) -> RowState {
        if let job = job, !job.isFinished { return state(of: job) }
        let have = installed?.installedVersion
        if offline { return .offline(installed: have.map(InstalledTitle.shortVersion)) }
        guard let lookup = lookup else { return .checking }
        switch lookup {
        case .failed(let failure):
            return .couldNotCheck(failure)
        case .none:
            return .noUpdates
        case .found(let patch):
            guard let newest = patch.packages.last else { return .noUpdates }
            if let have = have, GameUpdates.compare(have, newest.version) >= 0 {
                return .upToDate(InstalledTitle.shortVersion(have))
            }
            let to = InstalledTitle.shortVersion(newest.version)
            guard let chain = GameUpdates.chain(published: patch.packages, installed: have, base: installed?.discVersion,
                                                target: newest.version) else { return .upToDate(to) }
            return .available(from: have.map(InstalledTitle.shortVersion), to: to, packages: chain.packages.count,
                              bytes: chain.bytes)
        }
    }

    private static func state(of job: UpdateJob) -> RowState {
        let count = job.packages.count
        func version(_ index: Int) -> String { InstalledTitle.shortVersion(job.packages[index].package.version) }
        if let failure = job.failure, let at = job.failedAt, job.packages.indices.contains(at) {
            let needs = failure == .needsEarlier ? UpdateQueue.needsEarlier(job, at: at).map(InstalledTitle.shortVersion) : nil
            return .stopped(at: version(at), failure: failure, status: job.status, needs: needs)
        }
        if let index = job.installing, job.packages.indices.contains(index) {
            return .installing(version: version(index), index: index + 1, count: count)
        }
        guard let first = job.packages.firstIndex(where: { $0.state != .installed }) else { return .ready }
        if job.packages[first].state == .downloaded { return .ready }
        if job.waitingForNetwork { return .waitingForConnection(percent: job.percent) }
        guard job.packages.contains(where: { $0.state == .downloading }) else { return .waiting }
        return .downloading(version: version(first), index: first + 1, count: count, percent: job.percent)
    }

    // Games with an update first, then the rest, each A to Z.
    static func order(_ rows: [(serial: String, name: String, state: RowState)]) -> [String] {
        func available(_ state: RowState) -> Bool {
            if case .available = state { return true }
            return false
        }
        return rows.sorted { a, b in
            if available(a.state) != available(b.state) { return available(a.state) }
            let order = a.name.localizedCaseInsensitiveCompare(b.name)
            return order == .orderedSame ? a.serial < b.serial : order == .orderedAscending
        }.map { $0.serial }
    }

    static func pill(jobs: [UpdateJob], offline: Bool, result: UpdateResult?) -> PillState? {
        let running = jobs.filter { !$0.isFinished && !$0.isStopped }
        if let job = running.first(where: { $0.installing != nil }), let index = job.installing,
           job.packages.indices.contains(index) {
            return .installing(name: job.name, version: InstalledTitle.shortVersion(job.packages[index].package.version))
        }
        if !running.isEmpty {
            // A downloaded job waiting to install says nothing about the connection.
            let fetching = running.filter { job in job.packages.first(where: { $0.state != .installed })?.state != .downloaded }
            if offline || (!fetching.isEmpty && fetching.allSatisfy(\.waitingForNetwork)) { return .offline(games: running.count) }
            let percent = UpdateJob.percent(running.reduce(0) { $0 + $1.receivedBytes }, of: running.reduce(0) { $0 + $1.bytes })
            return .downloading(games: running.count, percent: percent)
        }
        guard let result = result, result.updated + result.failed > 0 else { return nil }
        if result.failed > 0 { return .failed(updated: result.updated, failed: result.failed) }
        return .finished(name: result.updated == 1 ? result.name : nil, count: result.updated)
    }
}

// Every word the rows, the summary, the pill and the confirm sheet show.
enum UpdateText {
    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func line(_ state: RowState) -> String {
        switch state {
        case .checking:
            return String(localized: "Checking…")
        case .couldNotCheck(let failure):
            return String(localized: "Couldn't check · \(couldNotCheck(failure))")
        case .noUpdates:
            return String(localized: "No updates")
        case .upToDate(let version):
            return String(localized: "Up to date (\(version))")
        case let .available(from, to, packages, bytes):
            if packages > 1 { return String(localized: "\(to) · \(packages) packages · \(size(bytes))") }
            guard let from = from else { return String(localized: "\(to) · \(size(bytes))") }
            return String(localized: "\(from) → \(to) · \(size(bytes))")
        case .waiting:
            return String(localized: "Waiting to download")
        case let .downloading(version, index, count, percent):
            if count > 1 { return String(localized: "Downloading \(index) of \(count) · \(percent)%") }
            return String(localized: "Downloading \(version) · \(percent)%")
        case .waitingForConnection(let percent):
            return String(localized: "Waiting for a connection · \(percent)%")
        case .ready:
            return String(localized: "Ready to install")
        case let .installing(version, index, count):
            if count > 1 { return String(localized: "Installing \(version) · \(index) of \(count)") }
            return String(localized: "Installing \(version)")
        case let .stopped(at, failure, status, needs):
            return String(localized: "Stopped at \(at) · \(stoppedShort(failure, status: status, needs: needs))")
        case .offline(let installed):
            guard let installed = installed else { return String(localized: "No update installed") }
            return String(localized: "Installed: \(installed)")
        }
    }

    // VoiceOver's value: words for the arrow and the dots, so neither is read out.
    static func spoken(_ state: RowState) -> String {
        switch state {
        case .couldNotCheck(let failure):
            return String(localized: "Couldn't check, \(couldNotCheck(failure))")
        case let .available(from, to, packages, bytes):
            if packages > 1 { return String(localized: "\(packages) packages, \(size(bytes)), up to \(to)") }
            guard let from = from else { return String(localized: "Update \(to) available, \(size(bytes))") }
            return String(localized: "Update \(to) available from \(from), \(size(bytes))")
        case let .downloading(_, index, count, percent):
            if count > 1 { return String(localized: "Downloading \(index) of \(count), \(percent) percent") }
            return String(localized: "Downloading, \(percent) percent")
        case .waitingForConnection(let percent):
            return String(localized: "Waiting for a connection, \(percent) percent")
        case let .installing(version, index, count) where count > 1:
            return String(localized: "Installing \(version), \(index) of \(count)")
        case let .stopped(at, failure, status, needs):
            return String(localized: "Stopped at \(at), \(stoppedShort(failure, status: status, needs: needs))")
        default:
            return line(state)
        }
    }

    static func couldNotCheck(_ failure: LookupFailure) -> String {
        switch failure {
        case .unreachable: return String(localized: "Sony's server didn't answer")
        case .untrusted: return String(localized: "couldn't verify Sony's server")
        case .malformed: return String(localized: "Sony's answer couldn't be read")
        case .http(let code): return String(localized: "server error \(code)")
        }
    }

    static func couldNotCheckFull(_ failure: LookupFailure) -> String {
        switch failure {
        case .unreachable: return String(localized: "Couldn't check. Sony's server didn't answer.")
        case .untrusted: return String(localized: "Couldn't check. Sony's server couldn't be verified.")
        case .malformed: return String(localized: "Couldn't check. Sony's answer couldn't be read.")
        case .http(let code): return String(localized: "Couldn't check. Sony's server answered with error \(code).")
        }
    }

    static func stoppedShort(_ failure: JobFailure, status: Int?, needs: String?) -> String {
        switch failure {
        case .damaged: return String(localized: "package damaged")
        case .space: return String(localized: "not enough space")
        case .writeFailed: return String(localized: "couldn't write the game")
        case .invalid: return String(localized: "not a valid package")
        case .missingOnServer: return String(localized: "package missing from Sony's server")
        case .interrupted: return String(localized: "install interrupted")
        case .removeFailed: return String(localized: "couldn't remove the current update")
        case .needsEarlier:
            if let needs = needs { return String(localized: "needs \(needs) first") }
            return stoppedShort(.installFailed, status: status, needs: nil)
        case .installFailed:
            guard let status = status else { return String(localized: "install failed") }
            return String(localized: "install failed (status \(status))")
        }
    }

    // Download stops only; an install status reads through the Install screen's own sentence.
    static func stoppedFull(_ failure: JobFailure) -> String? {
        switch failure {
        case .damaged: return String(localized: "The package didn't match Sony's checksum, so it wasn't installed. Try again.")
        case .space: return String(localized: "There isn't enough space for this package. Free some space, then try again.")
        case .missingOnServer: return String(localized: "Sony's server no longer has this package.")
        case .interrupted: return String(localized: "The app was closed while this package installed. Try again to install it.")
        default: return nil
        }
    }

    static func summary(states: [RowState], pill: PillState?) -> String {
        if let pill = pill {
            switch pill {
            case .downloading, .offline, .installing: return Self.pill(pill)
            case .finished, .failed: break
            }
        }
        let checking = states.filter { $0 == .checking }.count
        if checking > 0 { return String(localized: "Checking \(states.count - checking + 1) of \(states.count)…") }
        var count = 0, bytes: Int64 = 0
        for case let .available(_, _, _, chain) in states {
            count += 1
            bytes += chain
        }
        if count > 1 { return String(localized: "\(count) updates · \(size(bytes))") }
        if count == 1 { return String(localized: "1 update · \(size(bytes))") }
        let failures = states.compactMap { state -> LookupFailure? in
            if case .couldNotCheck(let failure) = state { return failure }
            return nil
        }
        if let first = failures.first, failures.count == states.count, failures.allSatisfy({ $0 == first }) {
            switch first {
            case .unreachable: return String(localized: "Couldn't reach Sony's server")
            case .untrusted: return String(localized: "Couldn't verify Sony's server")
            case .malformed: return String(localized: "Sony's answer couldn't be read")
            case .http(let code): return String(localized: "Sony's server answered with error \(code)")
            }
        }
        return String(localized: "No updates available")
    }

    static func pill(_ state: PillState) -> String {
        switch state {
        case let .downloading(games, percent):
            return String(localized: "Updating \(games) · \(percent)%")
        case .offline(let games):
            return String(localized: "Updating \(games) · offline")
        case let .installing(name, version):
            return String(localized: "Installing \(name) \(version)")
        case let .finished(name, count):
            guard let name = name else { return String(localized: "\(count) updated") }
            return String(localized: "\(name) updated")
        case let .failed(updated, failed):
            if updated + failed == 1 { return String(localized: "Update failed") }
            return String(localized: "\(updated) updated, \(failed) failed")
        }
    }

    static func versions(from: String, to: String) -> String {
        String(localized: "\(InstalledTitle.shortVersion(from)) → \(InstalledTitle.shortVersion(to))")
    }

    static func packages(count: Int, bytes: Int64) -> String {
        if count == 1 { return String(localized: "1 package · \(size(bytes))") }
        return String(localized: "\(count) packages · \(size(bytes))")
    }

    static func games(count: Int, bytes: Int64) -> String {
        String(localized: "\(count) games · \(size(bytes))")
    }

    static func freeAfter(_ bytes: Int64) -> String {
        String(localized: "\(size(bytes)) free after")
    }

    static func shortfall(_ bytes: Int64) -> String {
        String(localized: "Not enough space. Free \(size(bytes)) more, then try again.")
    }

    static var mobileData: String { String(localized: "You're on mobile data.") }

    static func removedFirst(_ version: String) -> String {
        String(localized: "The \(InstalledTitle.shortVersion(version)) update is removed first.")
    }

    static var dlcWarning: String {
        String(localized: "This also removes this game's DLC. Licences stay; install the DLC packages again after.")
    }
}
