#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""UpdateJobs.swift, run on the Mac host, moves every update job through its fetch, install, stop, retry, cancel and
Update All rules, keeps a journal that survives a relaunch, applies the free-space rule and prints every row, pill and
sheet word from one tested file. It says nothing about the store, the views or a device."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
UPDATES = APP / "Updates" / "GameUpdates.swift"
CONTENT = APP / "Install" / "InstalledContent.swift"
SFO = APP / "Library" / "ParamSfo.swift"
JOBS = APP / "Updates" / "UpdateJobs.swift"
CATALOG = IOS / "app" / "Resources" / "Localizable.xcstrings"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
PLAN_ID = re.compile(r"D-17-|GUPD-|17-0")
MIB = 1 << 20
LOCALIZED = re.compile(r'String\(localized:\s*"((?:[^"\\]|\\.)*)"')
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

DRIVER = r"""
import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)

func pkg(_ serial: String, _ n: Int, host: String = "b0.ww.np.dl.playstation.net", version: String? = nil) -> TitlePackage {
    let v = version ?? String(format: "01.%02d", n)
    return TitlePackage(version: v, size: Int64(1000 + n), sha1: String(repeating: "ab", count: 20),
                        url: URL(string: "http://\(host)/tppkg/np/\(serial)/\(serial)-A0\(n)-PE.pkg")!, firmware: nil)
}
func job(_ count: Int, serial: String = "ABCD12345", removes: Bool = false, before: String? = "01.00") -> UpdateJob {
    let packages = (1...count).map { pkg(serial, $0) }
    let chain = UpdateChain(packages: packages, removesFirst: removes, bytes: packages.reduce(0) { $0 + $1.size })
    return UpdateJob(serial: serial, name: "Game", chain: chain, installedBefore: before, allowsCellular: false)
}
let letter: [PackageState: String] = [.waiting: "w", .downloading: "g", .downloaded: "d", .installed: "i"]
func states(_ j: UpdateJob) -> String { j.packages.map { letter[$0.state]! }.joined() }
func show(_ j: UpdateJob) -> String {
    "states=\(states(j)) received=\(j.packages.map { String($0.received) }.joined(separator: ",")) "
        + "failure=\(j.failure?.rawValue ?? "nil") at=\(j.failedAt.map(String.init) ?? "nil") "
        + "installing=\(j.installing.map(String.init) ?? "nil")"
}
func list(_ xs: [Int]) -> String { "[" + xs.map(String.init).joined(separator: ",") + "]" }
func opt(_ x: Int?) -> String { x.map(String.init) ?? "nil" }

var j1 = job(3)
var line = "J1 start=\(states(j1)) downloads=\(list(UpdateQueue.downloads(j1)))"
j1 = UpdateQueue.downloaded(j1, 0)
line += " next=\(opt(UpdateQueue.nextInstall(j1)))"
j1 = UpdateQueue.installed(j1, 0)
line += " after=\(opt(UpdateQueue.nextInstall(j1)))"
j1 = UpdateQueue.downloaded(j1, 1)
line += " then=\(opt(UpdateQueue.nextInstall(j1)))"
print(line)

var j2 = job(7)
line = "J2 downloads=\(list(UpdateQueue.downloads(j2)))"
j2 = UpdateQueue.installed(UpdateQueue.downloaded(j2, 0), 0)
line += " after=\(list(UpdateQueue.downloads(j2)))"
j2.packages[1].state = .downloading
j2 = UpdateQueue.downloaded(j2, 2)
line += " busy=\(list(UpdateQueue.downloads(j2)))"
print(line)

var j3 = job(7)
for i in 0..<2 { j3 = UpdateQueue.installed(UpdateQueue.downloaded(j3, i), i) }
j3 = UpdateQueue.downloaded(j3, 2)
j3.packages[3].state = .downloading
j3.packages[3].received = 500
j3 = UpdateQueue.stop(j3, at: 2, failure: .damaged, status: nil)
line = "J3 stopped=\(show(j3)) downloads=\(list(UpdateQueue.downloads(j3))) next=\(opt(UpdateQueue.nextInstall(j3)))"
let retried = UpdateQueue.retry(j3)
line += " retry=\(show(retried)) downloads=\(list(UpdateQueue.downloads(retried)))"
line += " cellular=\(retried.allowsCellular),\(UpdateQueue.retry(j3, allowsCellular: true).allowsCellular)"
print(line)

var j4 = job(3)
j4 = UpdateQueue.installed(UpdateQueue.downloaded(j4, 0), 0)
j4 = UpdateQueue.downloaded(j4, 1)
print("J4 " + UpdateQueue.cancelled(j4).joined(separator: ","))

var a = job(1, serial: "AAAA00001"), b = job(2, serial: "BBBB00002"), c = job(1, serial: "CCCC00003")
a = UpdateQueue.installed(UpdateQueue.downloaded(a, 0), 0)
b = UpdateQueue.stop(b, at: 0, failure: .space, status: nil)
let order = ["AAAA00001", "BBBB00002", "CCCC00003"]
var done = c
done = UpdateQueue.installed(UpdateQueue.downloaded(done, 0), 0)
print("J5 next=\(UpdateQueue.next(order: order, jobs: [a, b, c]) ?? "nil") none=\(UpdateQueue.next(order: order, jobs: [a, b, done]) ?? "nil")")

var bits = ""
for active in [false, true] { for running in [false, true] { for other in [false, true] { for held in [false, true] {
    bits += UpdateQueue.canInstall(appActive: active, gameRunning: running, otherInstall: other, held: held) ? "1" : "0"
} } } }
print("J6 " + bits)

var j7 = UpdateQueue.downloaded(job(2, removes: true), 0)
line = "J7 needs=\(UpdateQueue.needsRemoval(j7)) next=\(opt(UpdateQueue.nextInstall(j7)))"
j7.removed = true
line += " removed needs=\(UpdateQueue.needsRemoval(j7)) next=\(opt(UpdateQueue.nextInstall(j7)))"
print(line)

var j8 = UpdateQueue.installed(UpdateQueue.downloaded(job(3), 0), 0)
j8 = UpdateQueue.downloaded(j8, 1)
j8.installing = 1
print("J8 " + show(UpdateQueue.afterRelaunch(j8)))

let folder = dir.appendingPathComponent("support/armsx3", isDirectory: true)
let file = folder.appendingPathComponent("updates.json")
var good = UpdateQueue.downloaded(job(3), 0)
good.packages[1].state = .downloading
good.packages[1].received = 7
let other = job(2, serial: "WXYZ99999")
let journal = UpdateJournal(jobs: [good, other], order: ["ABCD12345", "WXYZ99999"])
try! journal.save(to: file)
let roundTrip = UpdateJournal.load(from: file) == journal
let left = try! FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
func loads(_ bytes: Data) -> Int {
    let probe = dir.appendingPathComponent("probe.json")
    try! bytes.write(to: probe)
    let loaded = UpdateJournal.load(from: probe)
    return loaded.jobs.count + loaded.order.count
}
let saved = try! String(contentsOf: file, encoding: .utf8)
let v2 = saved.replacingOccurrences(of: "\"version\":1", with: "\"version\":2")
let badSerial = UpdateJob(serial: "bad", name: "Game", chain: UpdateChain(packages: [pkg("bad", 1)], removesFirst: false, bytes: 1001),
                          installedBefore: nil, allowsCellular: false)
let onList = UpdateJob(serial: "EFGH55555", name: "Game",
                       chain: UpdateChain(packages: [pkg("EFGH55555", 1, host: "a0.ww.np.dl.playstation.net")], removesFirst: false, bytes: 1001),
                       installedBefore: nil, allowsCellular: false)
let escape = UpdateJob(serial: "IJKL66666", name: "Game",
                       chain: UpdateChain(packages: [pkg("IJKL66666", 1, version: "../x")], removesFirst: false, bytes: 1001),
                       installedBefore: nil, allowsCellular: false)
let mixed = dir.appendingPathComponent("mixed/updates.json")
try! UpdateJournal(jobs: [badSerial, good, onList, escape], order: ["bad", "ABCD12345", "EFGH55555", "IJKL66666"]).save(to: mixed)
let kept = UpdateJournal.load(from: mixed)
print("J9 roundtrip=\(roundTrip) files=\(left.joined(separator: ",")) garbage=\(loads(Data("{not json".utf8))) "
      + "empty=\(loads(Data())) v2=\(v2 != saved)/\(loads(Data(v2.utf8))) "
      + "kept=\(kept.jobs.map(\.serial).joined(separator: ",")) order=\(kept.order.joined(separator: ","))")

print("J10 " + [(0, 100), (50, 100), (1, 3), (5, 0), (150, 100), (-5, 100)]
    .map { String(UpdateJob.percent(Int64($0.0), of: Int64($0.1))) }.joined(separator: " "))

func verdict(_ v: InstallVerdict) -> String {
    switch v {
    case .installed: return "installed"
    case .retryLater: return "retryLater"
    case .failed(let f): return "failed(\(f.rawValue))"
    }
}
print("J11 " + [0, -14, -15, -16, -9, -4, -99].map { "\($0)=\(verdict(UpdateQueue.verdict(status: $0)))" }.joined(separator: " "))

func download(_ v: DownloadVerdict) -> String {
    switch v {
    case .ok: return "ok"
    case .resume: return "resume"
    case .stop(let f): return "stop(\(f.rawValue))"
    }
}
let fetches: [(String, Int?, Int?, Bool)] = [
    ("200", 200, nil, false), ("206", 206, nil, false), ("404", 404, nil, false), ("410", 410, nil, false), ("503", 503, nil, false),
    ("lost", nil, URLError.networkConnectionLost.rawValue, false), ("timeout", nil, URLError.timedOut.rawValue, false),
    ("offline", nil, URLError.notConnectedToInternet.rawValue, false), ("space", nil, nil, true),
    ("ats", nil, URLError.appTransportSecurityRequiresSecureConnection.rawValue, false),
    ("cert", nil, URLError.serverCertificateUntrusted.rawValue, false),
]
print("J12 " + fetches.map { "\($0.0)=\(download(UpdateQueue.downloadVerdict(httpStatus: $0.1, errorCode: $0.2, outOfSpace: $0.3)))" }
    .joined(separator: " "))

let j13 = job(7)
print("J13 at2=\(UpdateQueue.needsEarlier(j13, at: 2) ?? "nil") at0=\(UpdateQueue.needsEarlier(j13, at: 0) ?? "nil") "
      + "none=\(UpdateQueue.needsEarlier(job(7, before: nil), at: 0) ?? "nil")")

var j14 = job(3)
j14.packages[0].state = .downloading
j14.packages[0].received = 300
j14.packages[1].state = .downloading
j14.packages[1].received = 200
j14 = UpdateQueue.downloaded(j14, 2)
j14 = UpdateQueue.retry(UpdateQueue.stop(j14, at: 1, failure: .space, status: nil))
print("J14 \(show(j14)) downloads=\(list(UpdateQueue.downloads(j14)))")

print("J15 " + [-1, 0, 1, 2, 7, 8, 9, 1000].map { String(UpdateQueue.refetchDelay($0)) }.joined(separator: " "))

let sizes: [Int64] = [100 << 20, 200 << 20, 300 << 20]
let need = FreeSpace.needed(sizes: sizes)
print("S1 needed=\(need) equal=\(FreeSpace.shortfall(available: need, sizes: sizes)) "
      + "short=\(FreeSpace.shortfall(available: need - 1, sizes: sizes)) after=\(FreeSpace.freeAfter(available: 500 << 20, sizes: sizes))")

func sized(_ sizes: [Int64], name: String = "Game") -> UpdateJob {
    let packages = sizes.enumerated().map { index, size in
        TitlePackage(version: String(format: "01.%02d", index + 5), size: size, sha1: String(repeating: "ab", count: 20),
                     url: URL(string: "http://b0.ww.np.dl.playstation.net/tppkg/np/ABCD12345/ABCD12345-A0\(index).pkg")!, firmware: nil)
    }
    return UpdateJob(serial: "ABCD12345", name: name, chain: UpdateChain(packages: packages, removesFirst: false, bytes: sizes.reduce(0, +)),
                     installedBefore: "01.04", allowsCellular: false)
}
func have(_ app: String?) -> InstalledUpdate {
    InstalledUpdate(serial: "ABCD12345", folder: app != nil, category: app == nil ? "" : "GD", titleID: "ABCD12345",
                    appVersion: app, discVersion: nil, dlcCount: 0)
}
let five = TitlePatch(titleID: "ABCD12345", name: nil, packages: (1...5).map { pkg("ABCD12345", $0) })
let seven = TitlePatch(titleID: "ABCD12345", name: nil, packages: ["01.10", "01.20", "01.30", "01.40", "01.50", "01.70", "01.84"]
    .enumerated().map { pkg("ABCD12345", $0.offset + 1, version: $0.element) })
var fetching = UpdateJob(serial: "ABCD12345", name: "Game", chain: GameUpdates.chain(published: five.packages, installed: "01.04", base: nil, target: "01.05")!,
                         installedBefore: "01.04", allowsCellular: false)
fetching.packages[0].state = .downloading
fetching.packages[0].received = 502
let r = UpdateBoard.rowState
let board: [(String, Bool)] = [
    ("nil", r(nil, have("01.04"), nil, false) == .checking),
    ("untrusted", r(.failed(.untrusted), have("01.04"), nil, false) == .couldNotCheck(.untrusted)),
    ("none", r(LookupOutcome.none, have("01.04"), nil, false) == .noUpdates),
    ("equal", r(.found(five), have("01.05"), nil, false) == .upToDate("1.05")),
    ("below", r(.found(five), have("01.04"), nil, false) == .available(from: "1.04", to: "1.05", packages: 1, bytes: 1005)),
    ("chain", r(.found(seven), nil, nil, false) == .available(from: nil, to: "1.84", packages: 7, bytes: 7028)),
    ("job", r(.found(five), have("01.04"), fetching, false) == .downloading(version: "1.05", index: 1, count: 1, percent: 49)),
    ("offline", r(.found(five), have("01.04"), nil, true) == .offline(installed: "1.04")),
    ("offlinenone", r(nil, nil, nil, true) == .offline(installed: nil)),
]
print("B1 " + board.map { "\($0.0)=\($0.1)" }.joined(separator: " "))

print("B2 " + UpdateBoard.order([
    ("ZETA00001", "zeta", .available(from: "1.00", to: "1.01", packages: 1, bytes: 1)), ("ALFA00002", "Alpha", .noUpdates),
    ("BETA00003", "beta", .upToDate("1.00")), ("SAME00009", "Same", .noUpdates), ("SAME00001", "Same", .noUpdates),
]).joined(separator: ","))

let shown: [(String, RowState)] = [
    ("checking", .checking), ("unreachable", .couldNotCheck(.unreachable)), ("untrusted", .couldNotCheck(.untrusted)),
    ("http", .couldNotCheck(.http(503))), ("malformed", .couldNotCheck(.malformed)), ("none", .noUpdates),
    ("current", .upToDate("1.05")), ("one", .available(from: "1.04", to: "1.05", packages: 1, bytes: 125_000_000)),
    ("chain", .available(from: "1.00", to: "1.84", packages: 7, bytes: 1_234_567_890)),
    ("nobase", .available(from: nil, to: "1.05", packages: 1, bytes: 125_000_000)), ("queued", .waiting),
    ("chaindown", .downloading(version: "1.03", index: 3, count: 7, percent: 45)),
    ("onedown", .downloading(version: "1.05", index: 1, count: 1, percent: 45)), ("lost", .waitingForConnection(percent: 45)),
    ("ready", .ready), ("oneinstall", .installing(version: "1.05", index: 1, count: 1)),
    ("chaininstall", .installing(version: "1.03", index: 3, count: 7)),
    ("needs", .stopped(at: "1.03", failure: .needsEarlier, status: -15, needs: "1.02")),
    ("damaged", .stopped(at: "1.03", failure: .damaged, status: nil, needs: nil)),
    ("installed", .offline(installed: "1.04")), ("noinstall", .offline(installed: nil)),
]
for (key, state) in shown {
    print("W1.\(key) \(UpdateText.line(state))")
    print("W3.\(key) \(UpdateText.spoken(state))")
}
for bytes: Int64 in [125_000_000, 1_234_567_890, 1_359_567_890, 5_000_000_000, 300_000_000] {
    print("SZ.\(bytes) \(UpdateText.size(bytes))")
}

let failures: [JobFailure] = [.damaged, .space, .needsEarlier, .writeFailed, .invalid, .missingOnServer, .interrupted,
                              .installFailed, .removeFailed]
for failure in failures {
    print("W2.\(failure.rawValue) \(UpdateText.stoppedShort(failure, status: -99, needs: "1.02"))")
    print("W2F.\(failure.rawValue) \(UpdateText.stoppedFull(failure) ?? "nil")")
}
print("W2.needsnil \(UpdateText.stoppedShort(.needsEarlier, status: -15, needs: nil))")
print("W2.nostatus \(UpdateText.stoppedShort(.installFailed, status: nil, needs: nil))")
for (key, failure) in [("unreachable", LookupFailure.unreachable), ("untrusted", .untrusted), ("malformed", .malformed), ("http", .http(503))] {
    print("W2C.\(key) \(UpdateText.couldNotCheckFull(failure))")
}

let one = RowState.available(from: "1.04", to: "1.05", packages: 1, bytes: 125_000_000)
let many = RowState.available(from: "1.00", to: "1.84", packages: 7, bytes: 1_234_567_890)
print("W4.checking \(UpdateText.summary(states: Array(repeating: .noUpdates, count: 11) + Array(repeating: .checking, count: 29), pill: nil))")
print("W4.two \(UpdateText.summary(states: [one, many, .noUpdates], pill: nil))")
print("W4.one \(UpdateText.summary(states: [one, .upToDate("1.00")], pill: nil))")
print("W4.zero \(UpdateText.summary(states: [.noUpdates, .upToDate("1.00")], pill: nil))")
print("W4.untrusted \(UpdateText.summary(states: [.couldNotCheck(.untrusted), .couldNotCheck(.untrusted)], pill: nil))")
print("W4.unreachable \(UpdateText.summary(states: [.couldNotCheck(.unreachable)], pill: nil))")
print("W4.running \(UpdateText.summary(states: [one, .noUpdates], pill: .downloading(games: 2, percent: 45)))")
print("W4.finished \(UpdateText.summary(states: [.noUpdates], pill: .finished(name: nil, count: 3)))")

func pillText(_ jobs: [UpdateJob], offline: Bool = false, result: UpdateResult? = nil) -> String {
    UpdateBoard.pill(jobs: jobs, offline: offline, result: result).map(UpdateText.pill) ?? "nil"
}
var half = sized([1000])
half.packages[0].state = .downloading
half.packages[0].received = 450
var installing = sized([1000], name: "Some Game")
installing.installing = 0
let finished = UpdateQueue.installed(UpdateQueue.downloaded(sized([1000]), 0), 0)
print("W5.downloading \(pillText([half]))")
print("W5.offline \(pillText([half, sized([2000])], offline: true))")
print("W5.installing \(pillText([half, installing]))")
print("W5.one \(pillText([finished], result: UpdateResult(updated: 1, failed: 0, name: "Some Game")))")
print("W5.three \(pillText([], result: UpdateResult(updated: 3, failed: 0, name: "Some Game")))")
print("W5.failures \(pillText([], result: UpdateResult(updated: 3, failed: 1, name: nil)))")
print("W5.single \(pillText([], result: UpdateResult(updated: 0, failed: 1, name: "Some Game")))")
print("W5.nothing \(pillText([finished]))")
var lost = half
lost.waitingForNetwork = true
var parked = UpdateQueue.downloaded(sized([1000]), 0)
parked.waitingForNetwork = true
print("W5.mixed \(pillText([half, lost]))")
print("W5.lost \(pillText([lost, parked]))")
print("W5.parked \(pillText([parked]))")

print("W6.versions \(UpdateText.versions(from: "01.00", to: "01.84"))")
print("W6.package \(UpdateText.packages(count: 1, bytes: 125_000_000))")
print("W6.packages \(UpdateText.packages(count: 7, bytes: 1_234_567_890))")
print("W6.games \(UpdateText.games(count: 3, bytes: 1_234_567_890))")
print("W6.free \(UpdateText.freeAfter(5_000_000_000))")
print("W6.mobile \(UpdateText.mobileData)")
print("W6.removed \(UpdateText.removedFirst("01.05"))")
print("W6.dlc \(UpdateText.dlcWarning)")
print("W6.short \(UpdateText.shortfall(300_000_000))")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def comment_lines(text):
    return [line for line in text.splitlines() if line.strip().startswith("//")]


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def key_pattern(literal):
    """A Swift `String(localized:)` literal as a pattern over catalog keys."""
    out, at = [], 0
    while at < len(literal):
        if literal.startswith("\\(", at):
            depth, at = 1, at + 2
            while depth:
                depth += {"(": 1, ")": -1}.get(literal[at], 0)
                at += 1
            out.append("(?:%@|%lld)")
        elif literal[at] == "\\":
            out.append(re.escape(literal[at + 1]))
            at += 2
        else:
            out.append("%%" if literal[at] == "%" else re.escape(literal[at]))
            at += 1
    return re.compile("".join(out) + r"\Z")


def catalog_keys():
    data = json.loads(read(CATALOG))
    return set(data["strings"])


def jobs_problems(raw, keys):
    """raw: UpdateJobs.swift as read; keys: the String Catalog's keys."""
    code = without_comments(raw)
    problems = []
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("imports: UpdateJobs.swift imports {0}, not only Foundation".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: UpdateJobs.swift logs")
    if any(PLAN_ID.search(line) for line in comment_lines(raw)):
        problems.append("comment: UpdateJobs.swift names a plan ID in a comment")
    if ".atomic" not in (body_of(code, r"func\s+save\s*\(") or ""):
        problems.append("atomic: UpdateJournal.save does not write with .atomic")
    literals = LOCALIZED.findall(code)
    if not literals:
        problems.append("catalog: UpdateJobs.swift has no String(localized:) literal")
    for literal in literals:
        pattern = key_pattern(literal)
        if not any(pattern.match(key) for key in keys):
            problems.append("catalog: `{0}` is not a key in Localizable.xcstrings".format(literal))
    if "\u2014" in raw or any("!" in text for text in LITERAL.findall(raw)):
        problems.append("copy: UpdateJobs.swift holds an em dash or an exclamation mark")
    return sorted(problems)


class TheJobsHold(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (UPDATES, CONTENT, SFO, JOBS):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([UPDATES, CONTENT, SFO, JOBS], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_j1_a_chain_downloads_ahead_and_installs_in_order(self):
        self.assertEqual("J1 start=www downloads=[0,1,2] next=0 after=nil then=1", self.line("J1"))

    def test_j2_the_window_moves_with_the_installs(self):
        self.assertEqual("J2 downloads=[0,1,2] after=[1,2,3] busy=[3]", self.line("J2"))

    def test_j3_a_stop_keeps_installed_packages_and_try_again_restarts_there(self):
        self.assertEqual("J3 stopped=states=iidgwww received=1001,1002,1003,500,0,0,0 failure=damaged at=2 installing=nil "
                         "downloads=[] next=nil "
                         "retry=states=iidwwww received=1001,1002,1003,0,0,0,0 failure=nil at=nil installing=nil "
                         "downloads=[3,4] cellular=false,true", self.line("J3"))

    def test_j4_cancel_names_every_package_not_installed(self):
        self.assertEqual("J4 ABCD12345-01.02.pkg,ABCD12345-01.03.pkg", self.line("J4"))

    def test_j5_update_all_skips_finished_and_stopped_games(self):
        self.assertEqual("J5 next=CCCC00003 none=nil", self.line("J5"))

    def test_j6_the_install_gate(self):
        expected = ["1" if (a, r, o, h) == (1, 0, 0, 0) else "0"
                    for a in (0, 1) for r in (0, 1) for o in (0, 1) for h in (0, 1)]
        self.assertEqual("J6 " + "".join(expected), self.line("J6"))

    def test_j7_a_rollback_removes_before_it_installs(self):
        self.assertEqual("J7 needs=true next=nil removed needs=false next=0", self.line("J7"))

    def test_j8_a_relaunch_mid_install_stops_as_interrupted(self):
        self.assertEqual("J8 states=idw received=1001,1002,0 failure=interrupted at=1 installing=nil", self.line("J8"))

    def test_j9_the_journal_round_trips_and_drops_what_fails(self):
        self.assertEqual("J9 roundtrip=true files=updates.json garbage=0 empty=0 v2=true/0 kept=ABCD12345 order=ABCD12345",
                         self.line("J9"))

    def test_j10_percent_rounds_down_and_clamps(self):
        self.assertEqual("J10 0 50 33 0 100 0", self.line("J10"))

    def test_j11_install_status_verdicts(self):
        self.assertEqual("J11 0=installed -14=failed(invalid) -15=failed(needsEarlier) -16=failed(writeFailed) "
                         "-9=retryLater -4=retryLater -99=failed(installFailed)", self.line("J11"))

    def test_j12_download_verdicts(self):
        self.assertEqual("J12 200=ok 206=ok 404=stop(missingOnServer) 410=stop(missingOnServer) 503=resume lost=resume "
                         "timeout=resume offline=resume space=stop(space) ats=stop(installFailed) "
                         "cert=stop(installFailed)", self.line("J12"))

    def test_j13_the_version_a_package_needs_first(self):
        self.assertEqual("J13 at2=01.02 at0=01.00 none=nil", self.line("J13"))

    def test_j14_try_again_restarts_every_package_left_downloading(self):
        self.assertEqual("J14 states=wwd received=0,0,1003 failure=nil at=nil installing=nil downloads=[0,1]",
                         self.line("J14"))

    def test_j15_a_failed_download_backs_off(self):
        self.assertEqual("J15 2 2 4 8 256 300 300 300", self.line("J15"))

    def test_s1_free_space_counts_the_chain_three_packages_and_a_margin(self):
        need = 600 * MIB + 600 * MIB + 512 * MIB
        self.assertEqual("S1 needed={0} equal=0 short=1 after={1}".format(need, -100 * MIB), self.line("S1"))

    def text(self, key):
        return self.line(key).split(" ", 1)[1] if " " in self.line(key) else ""

    def sz(self, count):
        return self.text("SZ.{0}".format(count))

    def test_b1_one_row_state_from_the_lookup_the_installed_state_and_the_job(self):
        self.assertEqual("B1 nil=true untrusted=true none=true equal=true below=true chain=true job=true offline=true "
                         "offlinenone=true", self.line("B1"))

    def test_b2_updates_first_then_a_to_z_then_by_serial(self):
        self.assertEqual("B2 ZETA00001,ALFA00002,BETA00003,SAME00001,SAME00009", self.line("B2"))

    def test_w1_every_row_line(self):
        one, chain = self.sz(125000000), self.sz(1234567890)
        expected = {
            "checking": "Checking…",
            "unreachable": "Couldn't check · Sony's server didn't answer",
            "untrusted": "Couldn't check · couldn't verify Sony's server",
            "http": "Couldn't check · server error 503",
            "malformed": "Couldn't check · Sony's answer couldn't be read",
            "none": "No updates",
            "current": "Up to date (1.05)",
            "one": "1.04 → 1.05 · " + one,
            "chain": "1.84 · 7 packages · " + chain,
            "nobase": "1.05 · " + one,
            "queued": "Waiting to download",
            "chaindown": "Downloading 3 of 7 · 45%",
            "onedown": "Downloading 1.05 · 45%",
            "lost": "Waiting for a connection · 45%",
            "ready": "Ready to install",
            "oneinstall": "Installing 1.05",
            "chaininstall": "Installing 1.03 · 3 of 7",
            "needs": "Stopped at 1.03 · needs 1.02 first",
            "damaged": "Stopped at 1.03 · package damaged",
            "installed": "Installed: 1.04",
            "noinstall": "No update installed",
        }
        for key, value in expected.items():
            self.assertEqual(value, self.text("W1." + key), key)

    def test_w2_stop_reasons_and_full_sentences(self):
        short = {
            "damaged": "package damaged", "space": "not enough space", "needsEarlier": "needs 1.02 first",
            "writeFailed": "couldn't write the game", "invalid": "not a valid package",
            "missingOnServer": "package missing from Sony's server", "interrupted": "install interrupted",
            "installFailed": "install failed (status -99)", "removeFailed": "couldn't remove the current update",
            "needsnil": "install failed (status -15)", "nostatus": "install failed",
        }
        for key, value in short.items():
            self.assertEqual(value, self.text("W2." + key), key)
        full = {
            "damaged": "The package didn't match Sony's checksum, so it wasn't installed. Try again.",
            "space": "There isn't enough space for this package. Free some space, then try again.",
            "missingOnServer": "Sony's server no longer has this package.",
            "interrupted": "The app was closed while this package installed. Try again to install it.",
        }
        for key in ("damaged", "space", "needsEarlier", "writeFailed", "invalid", "missingOnServer", "interrupted",
                    "installFailed", "removeFailed"):
            self.assertEqual(full.get(key, "nil"), self.text("W2F." + key), key)
        checks = {
            "unreachable": "Couldn't check. Sony's server didn't answer.",
            "untrusted": "Couldn't check. Sony's server couldn't be verified.",
            "malformed": "Couldn't check. Sony's answer couldn't be read.",
            "http": "Couldn't check. Sony's server answered with error 503.",
        }
        for key, value in checks.items():
            self.assertEqual(value, self.text("W2C." + key), key)

    def test_w3_spoken_values_use_words_for_the_arrow_and_the_dots(self):
        spoken = [line for line in self.lines if line.startswith("W3.")]
        self.assertGreater(len(spoken), 0)
        for line in spoken:
            self.assertNotIn("→", line)
            self.assertNotIn("·", line)
        one, chain = self.sz(125000000), self.sz(1234567890)
        self.assertEqual("Update 1.05 available from 1.04, " + one, self.text("W3.one"))
        self.assertEqual("7 packages, {0}, up to 1.84".format(chain), self.text("W3.chain"))
        self.assertEqual("Update 1.05 available, " + one, self.text("W3.nobase"))
        self.assertEqual("Downloading, 45 percent", self.text("W3.onedown"))
        self.assertEqual("Downloading 3 of 7, 45 percent", self.text("W3.chaindown"))
        self.assertEqual("Waiting for a connection, 45 percent", self.text("W3.lost"))
        self.assertEqual("Installing 1.03, 3 of 7", self.text("W3.chaininstall"))
        self.assertEqual("Stopped at 1.03, package damaged", self.text("W3.damaged"))
        self.assertEqual("Couldn't check, server error 503", self.text("W3.http"))

    def test_w4_the_summary_line(self):
        expected = {
            "checking": "Checking 12 of 40…",
            "two": "2 updates · " + self.sz(1359567890),
            "one": "1 update · " + self.sz(125000000),
            "zero": "No updates available",
            "untrusted": "Couldn't verify Sony's server",
            "unreachable": "Couldn't reach Sony's server",
            "running": "Updating 2 · 45%",
            "finished": "No updates available",
        }
        for key, value in expected.items():
            self.assertEqual(value, self.text("W4." + key), key)

    def test_w5_the_pill(self):
        expected = {
            "downloading": "Updating 1 · 45%",
            "offline": "Updating 2 · offline",
            "installing": "Installing Some Game 1.05",
            "one": "Some Game updated",
            "three": "3 updated",
            "failures": "3 updated, 1 failed",
            "single": "Update failed",
            "nothing": "nil",
            "mixed": "Updating 2 · 45%",
            "lost": "Updating 2 · offline",
            "parked": "Updating 1 · 100%",
        }
        for key, value in expected.items():
            self.assertEqual(value, self.text("W5." + key), key)

    def test_w6_the_confirm_lines(self):
        expected = {
            "versions": "1.00 → 1.84",
            "package": "1 package · " + self.sz(125000000),
            "packages": "7 packages · " + self.sz(1234567890),
            "games": "3 games · " + self.sz(1234567890),
            "free": self.sz(5000000000) + " free after",
            "mobile": "You're on mobile data.",
            "removed": "The 1.05 update is removed first.",
            "dlc": "This also removes this game's DLC. Licences stay; install the DLC packages again after.",
            "short": "Not enough space. Free {0} more, then try again.".format(self.sz(300000000)),
        }
        for key, value in expected.items():
            self.assertEqual(value, self.text("W6." + key), key)


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], jobs_problems(read(JOBS), catalog_keys()))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.raw = read(JOBS)
        cls.keys = catalog_keys()

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.raw)
        mutated = self.raw.replace(old, new, 1)
        self.assertNotEqual(self.raw, mutated)
        problems = jobs_problems(mutated, self.keys)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_imports_uikit(self):
        self.flagged("imports", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_plan_id_in_a_comment(self):
        self.flagged("comment", "import Foundation", "// GUPD-04.\nimport Foundation")

    def test_mutation_journal_written_in_place(self):
        self.flagged("atomic", "options: .atomic", "options: []")

    def test_mutation_literal_missing_from_the_catalog(self):
        self.flagged("catalog", '"Ready to install"', '"Ready to go"')

    def test_mutation_exclamation_mark(self):
        self.flagged("copy", '"Update failed"', '"Update failed!"')


if __name__ == "__main__":
    unittest.main(verbosity=2)
