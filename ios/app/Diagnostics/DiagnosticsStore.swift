// SPDX-License-Identifier: GPL-2.0-only
// Log sessions on disk, their rotation, and the redacted problem report. Foundation only, for the host test.

import Foundation

// The live values are this install's; a host test passes synthetic ones.
struct Redaction {
    var home: String
    var bundle: String
    var bundleID: String
    // The player's RPCN username, which the core logs at every sign-in. Empty replaces nothing.
    var rpcnName: String = ""

    // Read from rpcn.yml each time the store is made, so a report opened after a sign-in carries the current one.
    static var live: Redaction {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return Redaction(home: NSHomeDirectory(), bundle: Bundle.main.bundlePath,
                         bundleID: Bundle.main.bundleIdentifier ?? "", rpcnName: rpcnName(inSupport: support))
    }

    // On Apple the core's config folder has no `config/` subfolder; that one is Windows-only (Utilities/File.cpp).
    static func rpcnName(inSupport support: URL) -> String {
        let yml = support.appendingPathComponent("rpcs3/rpcn.yml")
        return rpcnName(fromRpcnYml: (try? String(contentsOf: yml, encoding: .utf8)) ?? "")
    }

    static func rpcnName(fromRpcnYml text: String) -> String {
        for line in text.components(separatedBy: .newlines) where line.hasPrefix("NPID:") {
            var value = line.dropFirst("NPID:".count).trimmingCharacters(in: .whitespaces)
            if value.count >= 2, let quote = value.first, quote == "\"" || quote == "'", value.last == quote {
                value = String(value.dropFirst().dropLast())
            }
            return value
        }
        return ""
    }
}

struct ReportForm {
    static let fieldLimit = 2000
    // English whatever the app's language, so the summary reads the same everywhere.
    static let kinds = ["Crash", "Freeze", "Slow", "Graphics", "Audio", "Controls"]
    static let places = ["Boot", "Menu", "Loading", "Cutscene", "Gameplay"]
    var whatHappened = ""
    var whereInGame = ""
    var kinds: [String] = []
    var place = ""
}

struct ReportSettings {
    var allGames = ""
    var thisGame = ""
    // By path; a missing one prints as unknown.
    var key: [String: String] = [:]
}

// What the running process knows that no file in the session holds; nil prints as unknown.
struct ReportProcess {
    var liveContainer: Bool? = nil
    var stderrDropped: UInt64? = nil
    var appBuild: String? = nil
}

enum DiagnosticsError: Error {
    case notACrashFile
}

struct ReportResult {
    let zip: URL
    let bytes: Int
    let summary: String
    let files: [String]
    let trimmed: Bool
}

struct DiagnosticsStore {
    enum SessionState: String {
        case menu
        case inGame = "in-game"
        case background
    }

    enum Incident: String {
        case game, menu, starting
    }

    enum SessionOutcome: String {
        case running, crashed, starting, background
    }

    struct SessionRow: Identifiable {
        let url: URL
        let started: Date?
        // state.json's title, else its serial; nil for a session that named neither.
        let label: String?
        let game: Bool
        let serial: String?
        let outcome: SessionOutcome?
        var id: URL { url }
    }

    struct SessionStart {
        let current: URL
        let offer: URL?
        let incident: Incident?
        let kept: Int
        let toArchive: [URL]
        let removed: Int
    }

    // `/2` marks background in the menus and carries the title.
    static let schema = "armsx3-diag-session/2"
    static let keptGameSessions = 3
    static let keptRecentSessions = 5
    static let firstScreenName = "first-screen"
    static let keptArchives = 7

    let root: URL
    // The core's log directory (fs::get_log_dir() on iOS, Application Support/rpcs3/), where TTY.log lives.
    let rpcs3Dir: URL
    let recordDir: URL
    let io: SaveDataIO
    // Documents/Reports: finished, redacted report zips, the only diagnostics the Files app shows.
    let reportsDir: URL
    let tmpDir: URL
    let redaction: Redaction
    // 8 MB read as 8,000,000 bytes, under the chat apps' common free limits.
    let budget: Int
    // Memory bounds on a phone already running a game: a log is staged from its tail, `chunk` bytes at a time.
    let stageTail: Int
    let chunk: Int
    let ramBytes: UInt64

    init(root: URL, rpcs3Dir: URL, recordDir: URL, io: SaveDataIO = FoundationSaveDataIO(),
         reportsDir: URL = DiagnosticsStore.documents.appendingPathComponent("Reports", isDirectory: true),
         tmpDir: URL = FileManager.default.temporaryDirectory, redaction: Redaction = .live,
         budget: Int = 8_000_000, stageTail: Int = 32 << 20, chunk: Int = 4 << 20,
         ramBytes: UInt64 = ProcessInfo.processInfo.physicalMemory) {
        self.root = root
        self.rpcs3Dir = rpcs3Dir
        self.recordDir = recordDir
        self.io = io
        self.reportsDir = reportsDir
        self.tmpDir = tmpDir
        self.redaction = redaction
        self.budget = budget
        self.stageTail = stageTail
        self.chunk = chunk
        self.ramBytes = ramBytes
    }

    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    var sessionsDir: URL { root.appendingPathComponent("sessions", isDirectory: true) }
    var archiveDir: URL { root.appendingPathComponent("archive", isDirectory: true) }

    static func sessionName(for date: Date) -> String {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(identifier: "UTC")
        format.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return format.string(from: date)
    }

    // The start a session folder's name spells; nil when the name is not one.
    static func sessionDate(fromName name: String) -> Date? {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(identifier: "UTC")
        format.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return format.date(from: String(name.prefix(16)))
    }

    static func timestamp(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    func sessions() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: sessionsDir.path)) ?? []
        return names.filter { !$0.hasPrefix(".") }.sorted().map { sessionsDir.appendingPathComponent($0, isDirectory: true) }
    }

    func readState(_ session: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: session.appendingPathComponent("state.json")) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    func serial(of session: URL) -> String? {
        (readState(session)?["serial"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    func beginSession(now: Date) throws -> SessionStart {
        let files = FileManager.default
        try files.createDirectory(at: sessionsDir, withIntermediateDirectories: true)
        let existing = sessions()
        let previous = existing.last
        let incident = previous.flatMap { self.incident(of: $0) }
        let offer = incident == nil ? nil : previous

        var kept = 0
        var toArchive: [URL] = []
        var removed = 0
        if let previous {
            copyFile(rpcs3Dir.appendingPathComponent("TTY.log"), into: previous)
            for session in existing {
                try? files.removeItem(at: session.appendingPathComponent("RPCS3.log.gz"))
            }
            var games = 0
            let newest = Set(existing.suffix(Self.keptRecentSessions))
            for session in existing.reversed() {
                let game = readState(session)?["game"] as? Bool ?? false
                if session == previous || newest.contains(session) || (game && games < Self.keptGameSessions) {
                    if game { games += 1 }
                    kept += 1
                } else if game {
                    toArchive.append(session)
                } else if (try? files.removeItem(at: session)) != nil {
                    removed += 1
                }
            }
        }

        // shortcut: `-n` keeps name order chronological for up to nine launches inside one second; add milliseconds if needed.
        let base = Self.sessionName(for: now)
        var name = base
        var suffix = 0
        while files.fileExists(atPath: sessionsDir.appendingPathComponent(name).path) {
            suffix += 1
            name = "\(base)-\(suffix)"
        }
        let current = sessionsDir.appendingPathComponent(name, isDirectory: true)
        try files.createDirectory(at: current, withIntermediateDirectories: false)
        try writeState(current, state: .menu, serial: nil, game: false, started: Self.timestamp(now))
        return SessionStart(current: current, offer: offer, incident: incident, kept: kept, toArchive: toArchive,
                            removed: removed)
    }

    // Most process ends are kills with no callback, so how a session ended is read from what state.json still says.
    func incident(of session: URL) -> Incident? {
        guard let record = readState(session) else { return nil }
        let state = record["state"] as? String
        if state == SessionState.inGame.rawValue { return .game }
        // shortcut: only this build's schema is trusted for menu evidence; a later schema bump must decide again.
        guard state == SessionState.menu.rawValue, record["schema"] as? String == Self.schema else { return nil }
        if FileManager.default.fileExists(atPath: session.appendingPathComponent(Self.firstScreenName).path) { return .menu }
        return isRegularFile(session.appendingPathComponent("launch.json")) ? .starting : nil
    }

    func markFirstScreen(_ session: URL) {
        FileManager.default.createFile(atPath: session.appendingPathComponent(Self.firstScreenName).path, contents: nil)
    }

    // No MetricKit date is used: iOS delivers a payload during a later launch, so its date belongs to no session.
    func sessionRows(current: URL?) -> [SessionRow] {
        sessions().reversed().map { session in
            let record = readState(session)
            let title = (record?["title"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let outcome: SessionOutcome?
            if session.lastPathComponent == current?.lastPathComponent {
                outcome = .running
            } else {
                switch incident(of: session) {
                case .game, .menu: outcome = .crashed
                case .starting: outcome = .starting
                case nil: outcome = record?["state"] as? String == SessionState.background.rawValue ? .background : nil
                }
            }
            return SessionRow(url: session,
                              started: started(of: session) ?? Self.sessionDate(fromName: session.lastPathComponent),
                              label: title ?? serial(of: session), game: record?["game"] as? Bool ?? false,
                              serial: serial(of: session), outcome: outcome)
        }
    }

    // Zipped to `.zip.partial`, renamed, and only then removed; a failed zip leaves the folder.
    func archive(_ sessions: [URL]) -> Int {
        let files = FileManager.default
        guard !sessions.isEmpty, (try? files.createDirectory(at: archiveDir, withIntermediateDirectories: true)) != nil else { return 0 }
        var archived = 0
        for session in sessions {
            let zip = archiveDir.appendingPathComponent(session.lastPathComponent + ".zip")
            let partial = archiveDir.appendingPathComponent(session.lastPathComponent + ".zip.partial")
            do {
                try io.zipDirectory(session, to: partial)
                try? files.removeItem(at: zip)
                try files.moveItem(at: partial, to: zip)
                try files.removeItem(at: session)
                archived += 1
            } catch {
                try? files.removeItem(at: partial)
            }
        }
        let zips = ((try? files.contentsOfDirectory(atPath: archiveDir.path)) ?? []).filter { $0.hasSuffix(".zip") }.sorted()
        for name in zips.dropLast(Self.keptArchives) {
            try? files.removeItem(at: archiveDir.appendingPathComponent(name))
        }
        return archived
    }

    func copyLaunchRecord(into session: URL) {
        copyFile(recordDir.appendingPathComponent("launch.json"), into: session)
    }

    // shortcut: reads the whole file into memory and never follows a link; stream TTY.log if it ever grows.
    private func copyFile(_ source: URL, into session: URL) {
        guard isRegularFile(source), let data = try? Data(contentsOf: source) else { return }
        try? data.write(to: session.appendingPathComponent(source.lastPathComponent), options: .atomic)
    }

    // The item's own type, links not followed: a symbolic link is never a regular file here.
    private func isRegularFile(_ url: URL) -> Bool {
        (try? FileManager.default.attributesOfItem(atPath: url.path))?[.type] as? FileAttributeType == .typeRegular
    }

    // MARK: the report

    static let reportPrefix = "ARMSX3-report-"
    static let keptReports = 5
    // The cap loop never keeps less of the current log than this; at the floor the report ships as it is.
    static let trimFloor = 64 << 10
    // How much of the log's end the summary scans for the serial, the last FPS and the last error.
    static let scanBytes = 4 << 20
    // The image line is a header line, which a long session pushes out of the tail window, so the head is read for it.
    static let imageLine = "IOSDIAG: image "
    static let imageHeadBytes = 64 << 10
    // The core's own sys_log texts; test_problem_report.py pins them, so an upstream rewording fails the suite.
    static let pausedLine = "SYS: Emulation is being paused"
    static let resumedLine = "SYS: Emulation has been resumed!"
    static let stoppingLine = "SYS: Stopping emulator..."
    static let bootLine = "SYS: Emulator::BootGame: "
    static let serialLine = "SYS: Serial: "

    // The bridge's first-launch seeds, mirrored because the store is Foundation-only; a test keeps the two equal.
    static let seededDefaults: [(path: String, value: String)] = [
        ("Input/Output@@Keyboard", "Basic"),
        ("Audio@@Microphone Type", "Standard"),
        ("Audio@@Microphone Devices", "CoreAudio Default@@@@@@@@@"),
        ("Input/Output@@Camera", "Qt"),
        ("Input/Output@@Camera type", "PS Eye"),
        ("Video@@Shader Mode", "Async Recompiler (multi-threaded)"),
    ]

    // shortcut: a seed the player set back to RPCS3's default is absent from the export, so the summary cannot show it.
    static func splitSeeded(_ yaml: String) -> (changed: String, seeded: String) {
        var changed: [String] = []
        var seeded: [String] = []
        var section = ""
        var header: String?
        var headed = (changed: false, seeded: false)
        for line in yaml.components(separatedBy: "\n") where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            if !line.hasPrefix(" ") {
                if line.hasSuffix(":") {
                    section = String(line.dropLast())
                    header = line
                } else {
                    section = ""
                    header = nil
                    changed.append(line)
                }
                headed = (false, false)
                continue
            }
            if isSeed(line, in: section) {
                if !headed.seeded, let header { seeded.append(header) }
                headed.seeded = true
                seeded.append(line)
            } else {
                if !headed.changed, let header { changed.append(header) }
                headed.changed = true
                changed.append(line)
            }
        }
        return (changed.joined(separator: "\n"), seeded.joined(separator: "\n"))
    }

    private static func isSeed(_ line: String, in section: String) -> Bool {
        guard line.hasPrefix("  "), !line.hasPrefix("   "), let colon = line.range(of: ": ") else { return false }
        let key = line[line.index(line.startIndex, offsetBy: 2)..<colon.lowerBound]
        var value = String(line[colon.upperBound...])
        if value.count >= 2, let quote = value.first, quote == "\"" || quote == "'", value.last == quote {
            value = String(value.dropFirst().dropLast())
        }
        return seededDefaults.contains { $0.path == section + "@@" + key && $0.value == value }
    }

    // Harmless lv2 results and the overlay's missing icon only, so an HLE result or an ENOMEM still shows.
    private static let harmlessSyscall = try! NSRegularExpression(
        pattern: "'_?sys_[A-Za-z0-9_]+' failed with 0x[0-9a-f]{8} : CELL_(?:ESRCH|EPERM|ENOENT|EDEADLK|ENOTCONN|PRX_ERROR_UNKNOWN_MODULE)\\b")

    static func isHarmless<S: StringProtocol>(_ line: S) -> Bool {
        let text = String(line)
        if text.contains("Image resource file `") && text.contains("' could not be opened (") { return true }
        return harmlessSyscall.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil || ReportFacts.isBenign(text)
    }

    func buildReport(session: URL, form: ReportForm, settings: ReportSettings, process: ReportProcess = ReportProcess(),
                     attachments: [URL] = [], now: Date) throws -> ReportResult {
        // One refused attachment stops the build before anything is staged.
        guard attachments.allSatisfy(isCrashFile) else { throw DiagnosticsError.notACrashFile }
        let files = FileManager.default
        let name = Self.reportPrefix + Self.sessionName(for: now)
        let work = tmpDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? files.removeItem(at: work) }
        let staging = work.appendingPathComponent(name, isDirectory: true)
        try files.createDirectory(at: staging, withIntermediateDirectories: true)

        let log = session.appendingPathComponent("RPCS3.log")
        var keep = min(size(of: log), stageTail)
        var trimmed = stage(log, as: "RPCS3.log", in: staging) == true
        // The session's own TTY.log, even as a link (refused, no fallback); otherwise the core's, which belongs to this session.
        let tty = session.appendingPathComponent("TTY.log")
        let hasTTY = (try? files.attributesOfItem(atPath: tty.path)) != nil
        stage(hasTTY ? tty : rpcs3Dir.appendingPathComponent("TTY.log"), as: "TTY.log", in: staging)
        stage(session.appendingPathComponent("launch.json"), as: "launch.json", in: staging)
        stage(recordDir.appendingPathComponent("session.json"), as: "session-record.json", in: staging)
        if !settings.allGames.isEmpty { write(settings.allGames, as: "settings-all-games.yml", in: staging) }
        if !settings.thisGame.isEmpty { write(settings.thisGame, as: "settings-this-game.yml", in: staging) }
        // Only older sessions, so a report on the previous session never carries the newer launch's log.
        let olderDir = staging.appendingPathComponent("older", isDirectory: true)
        for older in sessions() where older.lastPathComponent < session.lastPathComponent {
            stage(older.appendingPathComponent("RPCS3.log"), as: "older/\(older.lastPathComponent)-RPCS3.log", in: staging)
        }
        // Crashes of other apps (LiveContainer files them under its own bundle id) are left out.
        for payload in started(of: session).map(payloads(since:)) ?? [] {
            switch ownPayload(payload) {
            case .asIs: stage(payload, as: "metrickit/\(payload.lastPathComponent)", in: staging)
            case .trimmed(let text): write(text, as: "metrickit/\(payload.lastPathComponent)", in: staging)
            case .foreign: break
            }
        }
        for (index, attachment) in attachments.enumerated() {
            stage(attachment, as: index == 0 ? "crash-attached.ips" : "crash-attached-\(index + 1).ips", in: staging)
        }
        let text = summary(session: session, form: form, settings: settings, process: process)
        write(text, as: "summary.txt", in: staging)

        // Over the cap: drop the older sessions, then halve what is kept of the log's start down to the floor; the tail stays.
        let zip = work.appendingPathComponent(name + ".zip")
        try io.zipDirectory(staging, to: zip)
        if size(of: zip) > budget, files.fileExists(atPath: olderDir.path) {
            try files.removeItem(at: olderDir)
            try io.zipDirectory(staging, to: zip)
        }
        while size(of: zip) > budget, keep > Self.trimFloor, isRegularFile(log) {
            keep = max(keep / 2, Self.trimFloor)
            trimmed = true
            stage(log, as: "RPCS3.log", in: staging, keep: keep)
            try io.zipDirectory(staging, to: zip)
        }
        let bytes = size(of: zip)

        try files.createDirectory(at: reportsDir, withIntermediateDirectories: true)
        var target = reportsDir.appendingPathComponent(name + ".zip")
        var suffix = 0
        while files.fileExists(atPath: target.path) {
            suffix += 1
            target = reportsDir.appendingPathComponent("\(name)-\(suffix).zip")
        }
        try files.moveItem(at: zip, to: target)
        pruneReports(keeping: target)
        let staged = ((try? files.subpathsOfDirectory(atPath: staging.path)) ?? [])
            .filter { isRegularFile(staging.appendingPathComponent($0)) }.sorted()
        return ReportResult(zip: target, bytes: bytes, summary: text, files: staged, trimmed: trimmed)
    }

    // shortcut: the state comes from the core's lines in the last scanBytes, so an older pause reads as running.
    func summary(session: URL, form: ReportForm, settings: ReportSettings, process: ReportProcess = ReportProcess()) -> String {
        // Characters are grapheme clusters, so the cap never splits an emoji or an accented letter.
        func field(_ text: String) -> String {
            text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "(not given)" : String(text.prefix(ReportForm.fieldLimit))
        }
        func block(_ yaml: String) -> String {
            let body = yaml.trimmingCharacters(in: .newlines)
            return body.trimmingCharacters(in: .whitespaces).isEmpty ? "(none)" : body
        }
        let launch = jsonObject(session.appendingPathComponent("launch.json"))
        func part(_ key: String) -> String { launch[key] as? String ?? "unknown" }
        let log = session.appendingPathComponent("RPCS3.log")
        let hasLog = isRegularFile(log)
        var logSerial: String?
        var fatal: String?
        var error: String?
        var meaningful: String?
        var skipped = false
        var thermal: String?
        var headroom: String?
        // The newest non-zero sample before a pause or stop is reported, because opening the report pauses the game.
        var newest: String?
        var running: String?
        var before: String?
        var held: String?
        var decided = false
        var bounded = false
        if hasLog {
            for line in tail(of: log, bytes: Self.scanBytes).split(separator: "\n").reversed() {
                if logSerial == nil, let mark = line.range(of: Self.serialLine) {
                    let value = line[mark.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
                    if !value.isEmpty { logSerial = value }
                }
                if fatal == nil, line.hasPrefix("\u{00B7}F ") { fatal = String(line) }
                if line.hasPrefix("\u{00B7}E ") {
                    if Self.isHarmless(line) {
                        skipped = true
                    } else {
                        if error == nil { error = String(line) }
                        if meaningful == nil, ReportFacts.meaningfulChannels.contains(ReportFacts.channel(of: line)) { meaningful = String(line) }
                    }
                }
                if thermal == nil, let mark = line.range(of: "IOSDIAG: perf fps=") {
                    for token in line[mark.upperBound...].split(separator: " ") {
                        if token.hasPrefix("thermal=") { thermal = String(token.dropFirst(8)) }
                        if token.hasPrefix("headroom_mib=") { headroom = String(token.dropFirst(13)) }
                    }
                    if thermal == nil { thermal = "unknown" }
                }
                if !bounded {
                    if let mark = line.range(of: "IOSDIAG: perf fps=") {
                        let value = String(line[mark.upperBound...].prefix { $0 != " " })
                        let live = (Double(value) ?? 0) > 0
                        if newest == nil { newest = value }
                        if running == nil, live { running = value }
                        if held != nil, before == nil, live { before = value }
                    } else {
                        let paused = line.contains(Self.pausedLine)
                        let stopping = line.contains(Self.stoppingLine)
                        let boot = line.contains(Self.bootLine)
                        if !decided, paused || stopping || boot || line.contains(Self.resumedLine) {
                            decided = true
                            held = paused ? "pause" : stopping ? "stop" : nil
                        }
                        bounded = boot
                    }
                }
                if bounded, logSerial != nil, fatal != nil, thermal != nil { break }
            }
        }
        let fps: String
        switch held {
        case "pause": fps = before.map { "\($0) (before pause)" } ?? "none (paused)"
        case "stop": fps = before.map { "\($0) (before stop)" } ?? "none (stopped)"
        default: fps = running == nil ? "none" : newest ?? "none"
        }
        let ram = String(format: "%.1f GB RAM", Double(ramBytes) / 1e9)
        let image = hasLog ? imageValue(of: log) : nil
        // The words are armsx3::jit_state::decide's (ios/bridge/jit_state.h), as launch_record.m writes them.
        let reason = part("jit_reason")
        let route: String
        switch reason {
        case "helper-ok": route = "built-in helper"
        case "stikdebug-handshake": route = "StikDebug"
        case _ where part("jit_state") == "off": route = "none (\(reason))"
        case "unknown": route = "unknown"
        default: route = "other (\(reason))"
        }
        let (changed, seeded) = Self.splitSeeded(settings.allGames)
        let limit = (launch["jetsam_limit_bytes"] as? NSNumber).map { "\($0.int64Value >> 20) MiB" } ?? "unknown"
        let keySettings: [String?] = ReportFacts.keySettings.map { path in
            "  \(path.components(separatedBy: "@@").last ?? path): \(settings.key[path] ?? "unknown")"
        }
        let earlier = sessions().filter { $0.lastPathComponent < session.lastPathComponent }.map { older -> String? in
            let olderLog = older.appendingPathComponent("RPCS3.log")
            guard isRegularFile(olderLog) else { return "Earlier run \(older.lastPathComponent): no log" }
            let ending = ReportFacts.ending(of: tail(of: olderLog, bytes: Self.scanBytes), head: head(of: olderLog, bytes: Self.imageHeadBytes))
            let game = serial(of: older).map { " (\($0))" } ?? ""
            return "Earlier run \(older.lastPathComponent): \(ReportFacts.endingLabels[ending] ?? ending)\(game)"
        }
        let lines: [String?] = ["ARMSX3 problem report",
                     "Report format: \(ReportFacts.format)",
                     "What happened: \(field(form.whatHappened))",
                     "Where in the game: \(field(form.whereInGame))",
                     "Problem: \(form.kinds.isEmpty ? "(not given)" : form.kinds.map { $0.lowercased() }.joined(separator: ", "))",
                     "Happened during: \(form.place.isEmpty ? "(not given)" : form.place.lowercased())",
                     "Serial: \(logSerial ?? serial(of: session) ?? "unknown")",
                     "Build: \(process.appBuild ?? "unknown")",
                     "Thermal (last sample): \(thermal ?? "none")",
                     "Memory: limit \(limit), headroom \(headroom.map { "\($0) MiB" } ?? "unknown") at the last sample",
                     "Device: \(part("machine")), iOS \(part("os_build")), JIT \(part("jit_state")), \(ram)",
                     image.map { "Image: \($0)" },
                     "JIT route: \(route)",
                     "JIT helper: \(part("helper_outcome"))",
                     // Inferred: LiveContainer runs the app with a HOME of its own, so every session here ran the way this process runs.
                     "LiveContainer: \(process.liveContainer.map { $0 ? "yes" : "no" } ?? "unknown")",
                     // Approximate: unread, each `dropped <n>` notice is itself dropped and counted, so it runs up to about twice the loss.
                     "Console lines dropped, approximate (RPCS3.log is complete): \(process.stderrDropped.map { String($0) } ?? "unknown (an earlier launch)")",
                     "Last FPS: \(fps)",
                     // The newest fatal, else the newest error from a class that says something about emulation, else any non-routine one.
                     "Last error: \(fatal ?? meaningful ?? error ?? (skipped ? "none (only harmless errors)" : "none"))",
                     "Log: \(hasLog ? "staged" : "none")"] + earlier + ["Key settings, as the game runs them:"] + keySettings + [
                     "Settings changed from the defaults, all games:",
                     block(changed),
                     "App defaults (set by ARMSX3 on first launch):",
                     block(seeded),
                     "Settings changed from the defaults, this game:",
                     block(settings.thisGame)]
        return Self.redact(lines.compactMap { $0 }.joined(separator: "\n") + "\n", with: redaction)
    }

    // MARK: crash diagnostics

    static let keptPayloads = 5

    var metricKitDir: URL { root.appendingPathComponent("metrickit", isDirectory: true) }

    // Written `.atomic`, so a report built at the same moment reads a payload whole or not at all.
    func persistPayload(_ json: Data, ending: Date) throws {
        let files = FileManager.default
        try files.createDirectory(at: metricKitDir, withIntermediateDirectories: true)
        let base = Self.sessionName(for: ending)
        var n = 1
        while files.fileExists(atPath: metricKitDir.appendingPathComponent("\(base)-\(n).json").path) { n += 1 }
        try json.write(to: metricKitDir.appendingPathComponent("\(base)-\(n).json"), options: .atomic)
        let names = ((try? files.contentsOfDirectory(atPath: metricKitDir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        for name in names.dropLast(Self.keptPayloads) {
            try? files.removeItem(at: metricKitDir.appendingPathComponent(name))
        }
    }

    func started(of session: URL) -> Date? {
        (readState(session)?["started"] as? String).flatMap { ISO8601DateFormatter().date(from: $0) }
    }

    // Links and anything else are skipped.
    func payloads(since: Date) -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(atPath: metricKitDir.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.sorted()
            .map { metricKitDir.appendingPathComponent($0) }
            .filter { url in
                guard isRegularFile(url),
                      let modified = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date
                else { return false }
                return modified >= since
            }
    }

    // Nothing plist-shaped or naming a private key, so the pairing file the same picker shows never rides in a report.
    static let attachmentLimit = 4 << 20

    func isCrashFile(_ url: URL) -> Bool {
        guard isRegularFile(url), size(of: url) <= Self.attachmentLimit,
              let data = try? Data(contentsOf: url), data.count <= Self.attachmentLimit,
              let text = String(data: data, encoding: .utf8) else { return false }
        if ["<?xml", "<plist", "bplist", "PrivateKey"].contains(where: { text.contains($0) }) { return false }
        let header = Data(text.prefix { $0 != "\n" }.utf8)
        return ((try? JSONSerialization.jsonObject(with: header)) as? [String: Any])?["bug_type"] != nil
    }

    func hasCrashDiagnostic(for session: URL) -> Bool {
        started(of: session).map { payloads(since: $0).contains { if case .foreign = ownPayload($0) { false } else { true } } } ?? false
    }

    enum PayloadUse {
        case asIs
        case trimmed(String)
        case foreign
    }

    // Crash diagnostics that name no ARMSX3 frame are another app's and are left out.
    func ownPayload(_ url: URL) -> PayloadUse {
        var payload = jsonObject(url)
        guard let crashes = payload["crashDiagnostics"] as? [Any] else { return .asIs }
        let own = crashes.filter { crash in
            (try? JSONSerialization.data(withJSONObject: crash)).map { ReportFacts.isOwnCrash(String(decoding: $0, as: UTF8.self)) } ?? false
        }
        if own.count == crashes.count { return .asIs }
        payload["crashDiagnostics"] = own.isEmpty ? nil : own
        let left = ["cpuExceptionDiagnostics", "crashDiagnostics", "diskWriteExceptionDiagnostics", "hangDiagnostics"]
            .contains { !((payload[$0] as? [Any]) ?? []).isEmpty }
        guard left, let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else { return .foreign }
        return .trimmed(String(decoding: data, as: UTF8.self))
    }

    // The one just made is always kept.
    private func pruneReports(keeping newest: URL) {
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: reportsDir.path)) ?? [])
            .filter { $0.hasPrefix(Self.reportPrefix) && $0.hasSuffix(".zip") && $0 != newest.lastPathComponent }.sorted()
        for name in names.dropLast(Self.keptReports - 1) {
            try? FileManager.default.removeItem(at: reportsDir.appendingPathComponent(name))
        }
    }

    private func size(of url: URL) -> Int {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
    }

    private func jsonObject(_ url: URL) -> [String: Any] {
        guard isRegularFile(url), let data = try? Data(contentsOf: url) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    // A cut character becomes U+FFFD in the first line only.
    private func tail(of url: URL, bytes: Int) -> String {
        guard let input = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? input.close() }
        try? input.seek(toOffset: UInt64(max(0, size(of: url) - bytes)))
        return String(decoding: (try? input.read(upToCount: bytes)) ?? Data(), as: UTF8.self)
    }

    // A cut character becomes U+FFFD in the last line only.
    private func head(of url: URL, bytes: Int) -> String {
        guard let input = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? input.close() }
        return String(decoding: (try? input.read(upToCount: bytes)) ?? Data(), as: UTF8.self)
    }

    private func imageValue(of log: URL) -> String? {
        for line in head(of: log, bytes: Self.imageHeadBytes).split(separator: "\n") {
            guard let mark = line.range(of: Self.imageLine) else { continue }
            let value = line[mark.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
            if Self.imageShape.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil { return value }
        }
        return nil
    }

    // Never a copy, so a link is never followed. Each chunk is redacted up to its last newline, so no line is split.
    @discardableResult
    private func stage(_ source: URL, as name: String, in staging: URL, keep: Int = .max) -> Bool? {
        let files = FileManager.default
        let target = staging.appendingPathComponent(name)
        guard isRegularFile(source), let input = try? FileHandle(forReadingFrom: source) else { return nil }
        defer { try? input.close() }
        try? files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard files.createFile(atPath: target.path, contents: nil),
              let output = try? FileHandle(forWritingTo: target) else { return nil }
        defer { try? output.close() }
        let total = size(of: source)
        let start = max(0, total - min(keep, stageTail))
        var remaining = total - start
        var pending = Data()
        var cutting = start > 0
        var omitted = start
        func emit(_ data: Data) throws {
            try autoreleasepool {
                try output.write(contentsOf: Data(Self.redact(String(decoding: data, as: UTF8.self), with: redaction).utf8))
            }
        }
        do {
            try input.seek(toOffset: UInt64(start))
            while remaining > 0, let piece = try input.read(upToCount: min(chunk, remaining)), !piece.isEmpty {
                remaining -= piece.count
                pending.append(piece)
                if cutting {
                    guard let newline = pending.firstIndex(of: 0x0A) else {
                        omitted += pending.count
                        pending = Data()
                        continue
                    }
                    omitted += newline - pending.startIndex + 1
                    pending = Data(pending[(newline + 1)...])
                    try output.write(contentsOf: Data("-- \(omitted) bytes omitted --\n".utf8))
                    cutting = false
                }
                if let newline = pending.lastIndex(of: 0x0A) {
                    try emit(pending[...newline])
                    pending = Data(pending[(newline + 1)...])
                }
            }
            if cutting {
                try output.write(contentsOf: Data("-- \(omitted) bytes omitted --\n".utf8))
            }
            if !pending.isEmpty { try emit(pending) }
        } catch {
            return nil
        }
        return start > 0
    }

    @discardableResult
    private func write(_ text: String, as name: String, in staging: URL) -> Bool {
        let target = staging.appendingPathComponent(name)
        try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        return (try? Data(Self.redact(text, with: redaction).utf8).write(to: target)) != nil
    }

    private static let containerPath = try! NSRegularExpression(
        pattern: "(?:/private)?/var/(?:mobile/)?[Cc]ontainers/(?:[A-Za-z]+/){1,2}[0-9A-Fa-f-]{36}")
    private static let imageShape = try! NSRegularExpression(
        pattern: "^base=0x[0-9a-f]+ slide=0x[0-9a-f]+ uuid=[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}$")
    private static let containerPrefix = try! NSRegularExpression(pattern: "(?:/private)?/var/mobile/Containers/")
    // The guard suite's own device-identifier shape (test_findings_no_host_paths.py).
    private static let deviceID = try! NSRegularExpression(
        pattern: "(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}(?![0-9A-Fa-f-])")
    private static let identifierKeys = try! NSRegularExpression(
        pattern: "\"(crashReporterKey|deviceIdentifierForVendor|codeSigningTeamID|bootSessionUUID)\"\\s*:\\s*\"[^\"]*\"")
    // Anchored on the core's line shapes, never on a list of names.
    private static let friendShapes = [("add_friend(\"", "\") succeeded"), ("add_friend(\"", "\") failed with error"),
                                       ("You have successfully removed \"", "\" from your friendlist"),
                                       ("Received message from ", ":"), ("Sending message to \"", "\":"),
                                       (", from:", ", size:"), ("Received notification that user ", "("),
                                       ("Received notification that user's ", "("), ("told to connect to member(", ") of room("),
                                       ("sceNp2: npid: ", ""), ("sceNp2: npId: ", ""), ("sceNp2: onlineName: ", ""),
                                       ("sceNp: npId: ", ""), ("sceNp: onlineName: ", ""), ("sceNp: userId: ", ""),
                                       ("sceNp: name: ", ""), ("sceNp: kick_actor: ", "")]
    // The name runs lazily to the first after-half, and a name already `<rpcn-user>` is the player's own.
    private static let friendLines = friendShapes.map { before, after in
        try! NSRegularExpression(pattern: "(" + NSRegularExpression.escapedPattern(for: before) + ")(?!<rpcn-user>)[^\\n]*?("
                                     + (after.isEmpty ? "(?=\\n|$)" : NSRegularExpression.escapedPattern(for: after)) + ")")
    }
    private static let ownShapes = ["You are now logged in RPCN(", "You have successfully created a RPCN account("]
    private static let ownLines = ownShapes.map { before in
        try! NSRegularExpression(pattern: "(" + NSRegularExpression.escapedPattern(for: before) + ")[^\\n]*?(\\)!)")
    }

    // The home rules run before the container pattern, so the home reads `~/...` rather than `<container>/...`.
    static func redact(_ text: String, with rules: Redaction) -> String {
        // Crash files and MetricKit payloads are JSON, whose writers escape `/` as `\/`.
        var text = autoreleasepool { text.replacingOccurrences(of: "\\/", with: "/") }
        for (path, mark) in [(rules.home, "~"), (rules.bundle, "<app>")] {
            for spelling in spellings(of: path) {
                text = autoreleasepool { text.replacingOccurrences(of: spelling, with: mark) }
            }
        }
        text = replace(containerPath, in: text, with: "<container>")
        text = replace(containerPrefix, in: text, with: "<container>/")
        text = replace(deviceID, in: text, with: "<udid>")
        if !rules.bundleID.isEmpty {
            text = autoreleasepool { text.replacingOccurrences(of: rules.bundleID, with: "<bundle-id>") }
        }
        text = replace(identifierKeys, in: text, with: "\"$1\" : \"<redacted>\"")
        // Never with an empty name, whose pattern would match between every two characters.
        if !rules.rpcnName.isEmpty, let name = try? NSRegularExpression(
            pattern: "(?<![A-Za-z0-9_-])" + NSRegularExpression.escapedPattern(for: rules.rpcnName) + "(?![A-Za-z0-9_-])",
            options: .caseInsensitive) {
            text = replace(name, in: text, with: "<rpcn-user>")
        }
        for line in friendLines {
            text = replace(line, in: text, with: "$1<rpcn-friend>$2")
        }
        // The own-account pair as a whole, even with no name read from rpcn.yml.
        for line in ownLines {
            text = replace(line, in: text, with: "$1<rpcn-user> | <rpcn-user>$2")
        }
        return text
    }

    // The longer spelling first, so no tail of it is left behind. A relative path or the root is never replaced.
    private static func spellings(of path: String) -> [String] {
        let bare = path.hasPrefix("/private/") ? String(path.dropFirst("/private".count)) : path
        guard bare.hasPrefix("/"), bare.count > 1 else { return [] }
        return ["/private" + bare, bare]
    }

    // The report runs in one detached task that drains no pool, so without a pool per pass every pass stays alive.
    private static func replace(_ pattern: NSRegularExpression, in text: String, with template: String) -> String {
        autoreleasepool {
            pattern.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
        }
    }

    func writeState(_ session: URL, state: SessionState, serial: String?, game: Bool, started: String,
                    title: String? = nil) throws {
        let record: [String: Any] = ["schema": Self.schema, "started": started, "state": state.rawValue,
                                     "game": game, "serial": serial.map { $0 as Any } ?? NSNull(),
                                     "title": title.map { $0 as Any } ?? NSNull()]
        let data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
        try data.write(to: session.appendingPathComponent("state.json"), options: .atomic)
    }

    static func live() -> DiagnosticsStore {
        let files = FileManager.default
        let support = files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var root = support.appendingPathComponent("armsx3-logs", isDirectory: true)
        try? files.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)
        return DiagnosticsStore(root: root,
                                rpcs3Dir: support.appendingPathComponent("rpcs3", isDirectory: true),
                                recordDir: documents.appendingPathComponent("armsx3", isDirectory: true),
                                reportsDir: documents.appendingPathComponent("Reports", isDirectory: true),
                                tmpDir: files.temporaryDirectory,
                                redaction: .live)
    }
}

@MainActor final class DiagnosticsSession {
    static let shared = DiagnosticsSession()

    private(set) var current: URL?
    private(set) var crashed: URL?
    private(set) var crashKind: DiagnosticsStore.Incident?
    private var store: DiagnosticsStore?
    private var started = ""
    private var serial: String?
    private var title: String?
    private var game = false
    private var foreground = true
    private var playing = false
    private var firstScreen = false

    func begin(store: DiagnosticsStore = .live()) async -> URL? {
        let now = Date()
        let opened = await Task.detached(priority: .userInitiated) { try? store.beginSession(now: now) }.value
        guard let start = opened else {
            return nil
        }
        self.store = store
        current = start.current
        crashed = start.offer
        crashKind = start.incident
        started = DiagnosticsStore.timestamp(now)
        // Off the launch path: prepare never waits for a large old log to zip.
        let older = start.toArchive
        Task.detached(priority: .utility) { _ = store.archive(older) }
        return start.current
    }

    // So a failed boot's report still names its device. A no-op before begin.
    func recordLaunch() {
        guard let store, let current else { return }
        store.copyLaunchRecord(into: current)
    }

    // A menu mark while the scene is in the background is written as background, which is no incident.
    func mark(_ state: DiagnosticsStore.SessionState, serial: String? = nil, title: String? = nil) {
        if state == .inGame { playing = true } else if state == .menu { playing = false }
        guard let store, let current else { return }
        if let serial {
            self.serial = serial
            game = true
        }
        if let title { self.title = title }
        let written = state == .menu && !foreground ? .background : state
        do {
            try store.writeState(current, state: written, serial: self.serial, game: game, started: started, title: self.title)
        } catch {
        }
    }

    // So a swipe-away from the menus never reads as a death; while a game runs the game screen marks its own phases.
    func scene(active: Bool) {
        foreground = active
        if !playing { mark(active ? .menu : .background) }
    }

    // Once per session, a no-op before begin.
    func markFirstScreen() {
        guard !firstScreen, let store, let current else { return }
        firstScreen = true
        store.markFirstScreen(current)
    }
}
