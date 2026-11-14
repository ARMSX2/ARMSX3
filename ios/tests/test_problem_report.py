#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""DiagnosticsStore.swift and its neighbours, run on the Mac host over built containers, keep one log session per launch,
offer only incidents, prune and archive old sessions, and build a problem report that leaves out the pairing file,
redacts paths, identifiers and RPCN names, fits its budget and writes a truthful summary; static checks pin the wiring."""

import calendar
import json
import os
import re
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import body_of, read
from source_helpers import abi_text, brace_span, facade_text, without_comments
from test_image_base_line import IMAGE_PREFIX, IMAGE_VALUE
from source_helpers import scan
from test_save_import import detached_spans

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SFO = APP / "Library" / "ParamSfo.swift"
ZIP = APP / "Install" / "ZipArchive.swift"
SAVES = APP / "Saves" / "SaveDataStore.swift"
STORE = IOS / "app" / "Diagnostics" / "DiagnosticsStore.swift"
FACTS = IOS / "app" / "Diagnostics" / "ReportFacts.swift"
ROOT = APP / "Shell" / "ARMSX3FrontendRoot.swift"
SCREEN = APP / "Game" / "GameScreenView.swift"
SETTINGS = APP / "Settings" / "SettingsRootView.swift"
# The Diagnostics pane, which holds the Verbose logging switch, and the pane kinds.
PANE = IOS / "app" / "Diagnostics" / "DiagnosticsPane.swift"
MODEL = APP / "Settings" / "SettingsModel.swift"
SHEET = IOS / "app" / "Diagnostics" / "ProblemReportView.swift"
CRASH = IOS / "app" / "Diagnostics" / "CrashDiagnostics.swift"
FRONTEND = IOS / "app"
SYSTEM = IOS.parent / "rpcs3" / "Emu" / "System.cpp"
# The C++ writer of the image line.
DIAG_LOG = IOS / "bridge" / "diag_log.cpp"
# The façade, whose +stderrWitnessDropped the sheet reads; the seed list and the core formats the harmless-error skip reads.
OVERLAY_CONTROLS = IOS.parent / "rpcs3" / "Emu" / "RSX" / "Overlays" / "overlay_controls.cpp"
ERROR_CODES = IOS.parent / "rpcs3" / "Emu" / "Cell" / "ErrorCodes.cpp"
# The core's own sys_log texts summary() walks. Trailing spaces are significant.
CORELINES = ("Emulation is being paused", "Emulation has been resumed!", "Stopping emulator...",
             "Emulator::BootGame: ", "Serial: ")
# The core's three friend lines, each as the text before and after its quoted name.
RPCN_CLIENT = IOS.parent / "rpcs3" / "Emu" / "NP" / "rpcn_client.cpp"
FRIEND_SHAPES = (('add_friend("', '") succeeded'), ('add_friend("', '") failed with error'),
                 ('You have successfully removed "', '" from your friendlist'))
# The core's own-account lines, `RPCN(%s | %s)!` with the NPID and the online name.
OWN_SHAPES = ("You are now logged in RPCN(", "You have successfully created a RPCN account(")
# Other core NP lines that log another player's name at notice or above: (before, after, core lines, fixture message).
NP_DIR = IOS.parent / "rpcs3" / "Emu" / "NP"
NP_HANDLER = NP_DIR / "np_handler.cpp"
NP_NOTIFICATIONS = NP_DIR / "np_notifications.cpp"
NP_REQUESTS = NP_DIR / "np_requests.cpp"
NP_STRUCTS = NP_DIR / "np_structs_extra.cpp"
FRIEND_LOG_SHAPES = (
    ("Received message from ", ":", (("rpcn_client.cpp", "rpcn_log", "Received message from %s:"),),
     "rpcn: Received message from {0}:"),
    ('Sending message to "', '":', (("np_handler.cpp", "rpcn_log", 'Sending message to \\"%s\\":'),),
     'rpcn: Sending message to "{0}","{0}2":'),
    (", from:", ", size:", (("np_handler.cpp", "nph_log", "basic_event: event:%d, from:%s(%s), size:%d"),),
     "NPHandler: basic_event: event:3, from:{0}({0}), size:0"),
    ("Received notification that user ", "(",
     (("np_notifications.cpp", "rpcn_log", "Received notification that user %s(%d) joined the room(%d)"),
      ("np_notifications.cpp", "rpcn_log", "Received notification that user %s(%d) left the room(%d)")),
     "rpcn: Received notification that user {0}(2) joined the room(5)"),
    ("Received notification that user's ", "(",
     (("np_notifications.cpp", "rpcn_log", "Received notification that user's %s(%d) room (%d) data was updated"),),
     "rpcn: Received notification that user's {0}(2) room (5) data was updated"),
    ("told to connect to member(", ") of room(",
     (("np_notifications.cpp", "rpcn_log", "Join notification told to connect to member(%d=%s) of room(%d): %s:%d"),
      ("np_requests.cpp", "rpcn_log", "JoinRoomResult told to connect to member(%d=%s) of room(%d): %s:%d")),
     "rpcn: Join notification told to connect to member(2={0}) of room(5): 0.0.0.0:0"),
    ("sceNp2: npid: ", "", (("np_structs_extra.cpp", "sceNp2", "npid: %s"),), "sceNp2: npid: {0}"),
    ("sceNp2: npId: ", "", (("np_structs_extra.cpp", "sceNp2", "npId: %s"),), "sceNp2: npId: {0}"),
    ("sceNp2: onlineName: ", "", (("np_structs_extra.cpp", "sceNp2", "onlineName: *0x%x(%s)"),
                                  ("np_structs_extra.cpp", "sceNp2", "onlineName: %s")),
     "sceNp2: onlineName: *0x10({0})"),
    ("sceNp: npId: ", "", (("np_structs_extra.cpp", "sceNp", "npId: %s"),), "sceNp: npId: {0}"),
    ("sceNp: onlineName: ", "", (("np_structs_extra.cpp", "sceNp", "onlineName: %s"),), "sceNp: onlineName: {0}"),
    ("sceNp: userId: ", "", (("np_structs_extra.cpp", "sceNp", "userId: %s"),), "sceNp: userId: {0}"),
    ("sceNp: name: ", "", (("np_structs_extra.cpp", "sceNp", "name: %s"),), "sceNp: name: {0}"),
    ("sceNp: kick_actor: ", "", (("np_structs_extra.cpp", "sceNp", "kick_actor: %s"),), "sceNp: kick_actor: {0}"),
)

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
NETWORK = ("URLSession", "http://", "https://")
PAIRING = ("StikJIT", "pairingFile.plist", "pairingFile.origin")
COPIES = re.compile(r"\bcopyItem\s*\(")
IDENTITY = re.compile(r"\b(identifierForVendor|UIDevice)\b")
SPDX_LINE = "// SPDX-License-Identifier: GPL-2.0-only"
LIFECYCLE = re.compile(r"\.(task|onAppear)\b")
UNPROMPTED = ("buildReport", "summary(", "UIPasteboard", "shareZip(", "copySummary(")
# The clipboard uses the share check lets through, each removed once: copying a launch link, and the XMB keyboard's
# Paste key and Select, which read the clipboard when pressed.
CLIPBOARD_USES = {
    "Library/GameListView.swift": "UIPasteboard.general.string = DeepLink.copyLink(serial: entry.serial)",
    "Xmb/XmbKeyboard.swift": "static func clipboard() -> String { UIPasteboard.general.string ?? \"\" }",
}
STORE_CALL = re.compile(r"\bstore\.(buildReport|summary)\(")
ORDER = ("installLifecycleObservers", "DiagnosticsSession.shared.begin", "startDiagnostics(inDirectory:",
         "CrashDiagnostics.shared.start()", "checkJIT", "DiagnosticsSession.shared.recordLaunch()",
         "Rpcs3Bridge.prepare()")
MX_DIAGNOSTIC = re.compile(r"func\s+didReceive\s*\(\s*_\s+payloads\s*:\s*\[\s*MXDiagnosticPayload\s*\]\s*\)")
MX_METRIC = re.compile(r"\[\s*MXMetricPayload\s*\]")

# The previous session every B2 root holds, older than the driver's launch instant.
PREVIOUS = "20260101T000000Z"
TTY = b"guest console \x00\xff synthetic\n" * 64
LAUNCH = b'{"schema":"armsx3-launch-record/1","machine":"synthetic","jit_state":"on"}\n'

DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let files = FileManager.default
let now = Date(timeIntervalSince1970: 1_800_000_000)

func line(_ text: String) { print(text) }

func store(_ name: String) -> DiagnosticsStore {
    let base = tmp.appendingPathComponent(name, isDirectory: true)
    return DiagnosticsStore(root: base.appendingPathComponent("logs", isDirectory: true),
                            rpcs3Dir: base.appendingPathComponent("rpcs3", isDirectory: true),
                            recordDir: base.appendingPathComponent("record", isDirectory: true))
}

func state(_ session: URL) -> [String: Any] {
    guard let data = try? Data(contentsOf: session.appendingPathComponent("state.json")),
          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return object
}

func sessions(_ s: DiagnosticsStore) -> [String] {
    ((try? files.contentsOfDirectory(atPath: s.sessionsDir.path)) ?? []).sorted()
}

// B1: the first launch.
do {
    let s = store("b1")
    let start = try s.beginSession(now: now)
    let st = state(start.current)
    line("B1 created=\(sessions(s).count) state=\(st["state"] as? String ?? "nil")"
        + " game=\(st["game"] as? Bool ?? true) offer=\(start.offer == nil ? 0 : 1)")
} catch {
    line("B1 error")
}

// B2: only a previous session that still says in-game is offered.
var offers: [String] = []
for kind in ["ingame", "background", "menu", "corrupt", "missing"] {
    do {
        let start = try store("b2-" + kind).beginSession(now: now)
        offers.append("\(kind)=\(start.offer?.lastPathComponent == "@@PREVIOUS@@" ? 1 : 0)")
    } catch {
        offers.append("\(kind)=error")
    }
}
line("B2 " + offers.joined(separator: " "))

// B3: the guest console of the session that just ended is kept.
do {
    let s = store("b3")
    _ = try s.beginSession(now: now)
    let copied = try? Data(contentsOf: s.sessionsDir.appendingPathComponent("@@PREVIOUS@@/TTY.log"))
    let source = try Data(contentsOf: s.rpcs3Dir.appendingPathComponent("TTY.log"))
    let a = store("b3-absent")
    _ = try a.beginSession(now: now)
    let absent = files.fileExists(atPath: a.sessionsDir.appendingPathComponent("@@PREVIOUS@@/TTY.log").path)
    let first = sessions(store("b1")).filter {
        files.fileExists(atPath: store("b1").sessionsDir.appendingPathComponent($0 + "/TTY.log").path) }
    line("B3 tty=\(copied == nil ? 0 : 1) same=\(copied == source ? 1 : 0) absent=\(absent ? 1 : 0) first=\(first.count)")
} catch {
    line("B3 error")
}

// B4: the core's unfinished gzip twin goes from every session.
do {
    let s = store("b4")
    _ = try s.beginSession(now: now)
    let left = sessions(s).filter { files.fileExists(atPath: s.sessionsDir.appendingPathComponent($0 + "/RPCS3.log.gz").path) }
    line("B4 gz=\(left.count)")
} catch {
    line("B4 error")
}

// B5, B9, B9b: what stays, what is handed to archiving, what is removed.
for (key, root) in [("B5", "b5"), ("B9", "b9"), ("B9b", "b9b")] {
    do {
        let s = store(root)
        let before = sessions(s)
        let start = try s.beginSession(now: now)
        let handed = start.toArchive.map { $0.lastPathComponent }
        let oldest = handed.sorted() == Array(before.prefix(handed.count)) ? 1 : 0
        line("\(key) kept=\(start.kept) archive=\(handed.count) removed=\(start.removed) oldest=\(oldest)")
    } catch {
        line("\(key) error")
    }
}

// B10: six menu-only sessions keep the newest five.
do {
    let s = store("b10")
    let before = sessions(s)
    let start = try s.beginSession(now: now)
    let left = sessions(s).filter { $0 != start.current.lastPathComponent }
    line("B10 kept=\(start.kept) archive=\(start.toArchive.count) removed=\(start.removed)"
        + " oldestgone=\(left.contains(before[0]) ? 0 : 1) threeback=\(left.contains(before[3]) ? 1 : 0) survivors=\(left.count)")
} catch {
    line("B10 error")
}

// I1: the previous session's incident and whether it is offered.
var incidents: [String] = []
for kind in ["game", "menu", "starting", "nolaunch", "background", "oldmenu", "oldgame"] {
    do {
        let start = try store("i1-" + kind).beginSession(now: now)
        incidents.append("\(kind)=\(start.incident?.rawValue ?? "none")/\(start.offer == nil ? 0 : 1)")
    } catch {
        incidents.append("\(kind)=error")
    }
}
line("I1 " + incidents.joined(separator: " "))

// D1: the Diagnostics pane's rows.
do {
    let s = store("d1")
    let all = s.sessions()
    let rows = s.sessionRows(current: all.last)
    let outcomes = rows.map { $0.outcome?.rawValue ?? "none" }.joined(separator: ",")
    let labels = rows.map { $0.label ?? "-" }.joined(separator: ",")
    let first = rows.first?.started.map { Int($0.timeIntervalSince1970) } ?? -1
    let iso = rows.first { $0.url.lastPathComponent == "20260102T000000Z" }?.started.map { Int($0.timeIntervalSince1970) } ?? -1
    line("D1 n=\(rows.count) outcomes=\(outcomes) labels=\(labels) first=\(first) iso=\(iso)")
}

// B7: the real system zip, then the archive cap.
do {
    let s = store("b7")
    let archived = s.archive(s.sessions())
    let names = (try? files.contentsOfDirectory(atPath: s.archiveDir.path)) ?? []
    let zips = names.filter { $0.hasSuffix(".zip") }.count
    let partial = names.filter { $0.hasSuffix(".partial") }.count
    line("B7 zips=\(zips) folders=\(sessions(s).count) partial=\(partial) archived=\(archived)")
}

// B8: the marks a game screen makes.
let marker = DiagnosticsSession()
let b8 = store("b8")
if let session = await marker.begin(store: b8) {
    func now8() -> [String: Any] { state(session) }
    // Right after the JIT check, before any boot: every session carries the launch record.
    marker.recordLaunch()
    let record = try? Data(contentsOf: b8.recordDir.appendingPathComponent("launch.json"))
    let launched = record != nil && (try? Data(contentsOf: session.appendingPathComponent("launch.json"))) == record
    let early = now8()
    marker.mark(.inGame, serial: "ABCD12345")
    let inGame = now8()
    marker.mark(.background)
    let back = now8()["state"] as? String ?? "invalid"
    marker.mark(.menu)
    let menu = now8()
    line("B8 launch=\(launched ? 1 : 0)"
        + " early=\(early["state"] as? String ?? "invalid")/\(early["game"] as? Bool ?? true)/\(early["serial"] is NSNull ? "nil" : "set")"
        + " state=\(inGame["state"] as? String ?? "invalid") serial=\(inGame["serial"] as? String ?? "nil")"
        + " game=\(inGame["game"] as? Bool ?? false) back=\(back)"
        + " menu=\(menu["state"] as? String ?? "invalid")"
        + " keepgame=\((menu["game"] as? Bool ?? false) && menu["serial"] as? String == "ABCD12345")")
} else {
    line("B8 error")
}

// B11: the menus' scene marks, the title and the first-screen marker.
let unbegun = DiagnosticsSession()
unbegun.markFirstScreen()
unbegun.scene(active: false)
unbegun.mark(.menu)
let scener = DiagnosticsSession()
if let session = await scener.begin(store: store("b11")) {
    func st() -> String { state(session)["state"] as? String ?? "invalid" }
    scener.scene(active: false)
    let idleBack = st()
    scener.scene(active: true)
    let idleFront = st()
    scener.mark(.inGame, serial: "ABCD12345", title: "Synthetic Title")
    let title = state(session)["title"] as? String ?? "nil"
    scener.scene(active: false)
    let playing = st()
    scener.mark(.menu)
    let stopped = st()
    scener.scene(active: true)
    let returned = st()
    scener.markFirstScreen()
    scener.markFirstScreen()
    let first = files.fileExists(atPath: session.appendingPathComponent("first-screen").path)
    line("B11 idle=\(idleBack)/\(idleFront) playing=\(playing) stopped=\(stopped) returned=\(returned)"
        + " title=\(title) first=\(first)")
} else {
    line("B11 error")
}

// S1: the serial a session names, for the crash offer's sheet.
do {
    let s = store("s1")
    let named = s.sessionsDir.appendingPathComponent("named", isDirectory: true)
    let unnamed = s.sessionsDir.appendingPathComponent("unnamed", isDirectory: true)
    let empty = s.sessionsDir.appendingPathComponent("empty", isDirectory: true)
    let missing = s.sessionsDir.appendingPathComponent("missing", isDirectory: true)
    for folder in [named, unnamed, empty, missing] { try files.createDirectory(at: folder, withIntermediateDirectories: true) }
    try s.writeState(named, state: .inGame, serial: "ABCD12345", game: true, started: "s1")
    try s.writeState(unnamed, state: .inGame, serial: nil, game: false, started: "s1")
    try s.writeState(empty, state: .inGame, serial: "", game: true, started: "s1")
    let none = [unnamed, empty, missing].allSatisfy { s.serial(of: $0) == nil }
    line("S1 serial=\(s.serial(of: named) ?? "nil") none=\(none ? "nil" : "set")")
} catch {
    line("S1 error")
}

// B6: two launches inside one second.
do {
    let s = store("b6")
    let a = try s.beginSession(now: now)
    let b = try s.beginSession(now: now)
    let made = [a.current.lastPathComponent, b.current.lastPathComponent]
    line("B6 distinct=\(made[0] != made[1] ? 1 : 0) ordered=\(sessions(s) == made ? 1 : 0)")
} catch {
    line("B6 error")
}
"""

EXPECTED = {
    "B1": "B1 created=1 state=menu game=false offer=0",
    "B2": "B2 ingame=1 background=0 menu=0 corrupt=0 missing=0",
    "B6": "B6 distinct=1 ordered=1",
    "B3": "B3 tty=1 same=1 absent=0 first=0",
    "B4": "B4 gz=0",
    # The newest five (sessions 4 to 8) stay whatever their kind; games 1 to 3 are archived.
    "B5": "B5 kept=5 archive=3 removed=0 oldest=1",
    "B9": "B9 kept=3 archive=0 removed=0 oldest=1",
    # Six game sessions, so the newest five stay and the oldest alone is handed over.
    "B9b": "B9b kept=5 archive=1 removed=0 oldest=1",
    # Six no-game sessions; the newest five are 2 to 6, so only 1 goes.
    "B10": "B10 kept=5 archive=0 removed=1 oldestgone=1 threeback=1 survivors=5",
    # In-game is a game under any schema; menu evidence counts under /2 only.
    "I1": "I1 game=game/1 menu=menu/1 starting=starting/1 nolaunch=none/0 background=none/0 oldmenu=none/0 oldgame=game/1",
    "B11": "B11 idle=background/menu playing=in-game stopped=background returned=menu title=Synthetic Title first=true",
    # 07 is current, 06 background, 05 launch.json without the marker, 04 to 02 incidents, 01 a /1 menu.
    "D1": "D1 n=7 outcomes=running,background,starting,crashed,crashed,crashed,none labels=-,-,-,-,EFGH67890,Synthetic Game,- first=1767744000 iso=1767357000",
    "B7": "B7 zips=7 folders=0 partial=0 archived=9",
    "B8": "B8 launch=1 early=menu/false/nil state=in-game serial=ABCD12345 game=true back=background menu=menu keepgame=true",
    "B7zip": "B7zip RPCS3.log=1",
    "S1": "S1 serial=ABCD12345 none=nil",
}


def session_dir(tmp, root, name):
    folder = Path(tmp) / root / "logs" / "sessions" / name
    folder.mkdir(parents=True)
    return folder


def write_state(folder, state, game, serial=None, started=None, schema="armsx3-diag-session/1", title=None):
    record = {"schema": schema, "started": started or folder.name, "state": state, "game": game,
              "serial": serial, "title": title}
    (folder / "state.json").write_text(json.dumps(record), encoding="utf-8")


def write_fixtures(tmp):
    write_state(session_dir(tmp, "b2-ingame", PREVIOUS), "in-game", True, "ABCD12345")
    write_state(session_dir(tmp, "b2-background", PREVIOUS), "background", True, "ABCD12345")
    write_state(session_dir(tmp, "b2-menu", PREVIOUS), "menu", False)
    (session_dir(tmp, "b2-corrupt", PREVIOUS) / "state.json").write_bytes(b"\x00{not json")
    session_dir(tmp, "b2-missing", PREVIOUS)
    # B1's first launch must not copy this: there is no previous session to copy it into.
    (Path(tmp) / "b1" / "rpcs3").mkdir(parents=True)
    (Path(tmp) / "b1" / "rpcs3" / "TTY.log").write_bytes(TTY)
    write_state(session_dir(tmp, "b3", PREVIOUS), "in-game", True, "ABCD12345")
    (Path(tmp) / "b3" / "rpcs3").mkdir()
    (Path(tmp) / "b3" / "rpcs3" / "TTY.log").write_bytes(TTY)
    write_state(session_dir(tmp, "b3-absent", PREVIOUS), "menu", False)
    for name in ("20251231T000000Z", PREVIOUS):
        folder = session_dir(tmp, "b4", name)
        write_state(folder, "menu", True, "ABCD12345")
        (folder / "RPCS3.log").write_bytes(b"log\n")
        (folder / "RPCS3.log.gz").write_bytes(b"\x1f\x8b unfinished")
        os.chmod(folder / "RPCS3.log.gz", 0)
    # B5: oldest to newest. True is a session that booted a game.
    for index, game in enumerate((True, True, True, False, True, True, True, False)):
        write_state(session_dir(tmp, "b5", "2026010{0}T000000Z".format(index + 1)), "menu", game)
    for index in range(3):
        write_state(session_dir(tmp, "b9", "2026010{0}T000000Z".format(index + 1)), "menu", True)
    for index in range(6):
        write_state(session_dir(tmp, "b9b", "2026010{0}T000000Z".format(index + 1)), "menu", True)
    for index in range(6):
        write_state(session_dir(tmp, "b10", "2026010{0}T000000Z".format(index + 1)), "menu", False)
    # I1: (root, schema, state, marker, launch.json).
    for kind, schema, state, marker, launch in (("game", 2, "in-game", False, False), ("menu", 2, "menu", True, True),
                                                ("starting", 2, "menu", False, True), ("nolaunch", 2, "menu", False, False),
                                                ("background", 2, "background", True, True), ("oldmenu", 1, "menu", True, True),
                                                ("oldgame", 1, "in-game", False, False)):
        folder = session_dir(tmp, "i1-" + kind, PREVIOUS)
        game = state == "in-game"
        write_state(folder, state, game, "ABCD12345" if game else None, schema="armsx3-diag-session/{0}".format(schema))
        if marker:
            (folder / "first-screen").write_bytes(b"")
        if launch:
            (folder / "launch.json").write_bytes(LAUNCH)
    v2 = "armsx3-diag-session/2"
    write_state(session_dir(tmp, "d1", "20260101T000000Z"), "menu", False)
    write_state(session_dir(tmp, "d1", "20260102T000000Z"), "in-game", True, "ABCD12345", started="2026-01-02T12:30:00Z",
                schema=v2, title="Synthetic Game")
    write_state(session_dir(tmp, "d1", "20260103T000000Z"), "in-game", True, "EFGH67890", schema=v2)
    folder = session_dir(tmp, "d1", "20260104T000000Z")
    write_state(folder, "menu", False, schema=v2)
    (folder / "first-screen").write_bytes(b"")
    folder = session_dir(tmp, "d1", "20260105T000000Z")
    write_state(folder, "menu", False, schema=v2)
    (folder / "launch.json").write_bytes(LAUNCH)
    write_state(session_dir(tmp, "d1", "20260106T000000Z"), "background", False, schema=v2)
    write_state(session_dir(tmp, "d1", "20260107T000000Z"), "menu", False, schema=v2)
    for index in range(9):
        folder = session_dir(tmp, "b7", "2026010{0}T000000Z".format(index + 1))
        write_state(folder, "menu", True, "ABCD12345")
        (folder / "RPCS3.log").write_bytes(b"synthetic log line\n" * 32)
    (Path(tmp) / "b8" / "record").mkdir(parents=True)
    (Path(tmp) / "b8" / "record" / "launch.json").write_bytes(LAUNCH)


def zip_holds_log(tmp):
    archive = Path(tmp) / "b7" / "logs" / "archive"
    newest = sorted(archive.glob("*.zip"))
    if not newest:
        return "B7zip none"
    with zipfile.ZipFile(newest[-1]) as zipped:
        names = zipped.namelist()
    return "B7zip RPCS3.log={0}".format(int(any(n == "RPCS3.log" or n.endswith("/RPCS3.log") for n in names)))


# The problem report.

# The redaction oracle: `ios-app-container-root` and `device-identifier-udid`.
ORACLE = (
    re.compile(r"(?<![A-Za-z0-9_./-])(?:/private)?/var/mobile/Containers/"),
    re.compile(r"(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}(?![0-9A-Fa-f-])"),
)


def synthetic_uuid(digit):
    return "-".join(digit * n for n in (8, 4, 4, 4, 12))


# Synthetic values, assembled at run time so this file holds no literal container path or identifier shape.
CONTAINERS = "/var/mobile/" + "Containers/"
HOME = CONTAINERS + "Data/Application/" + synthetic_uuid("A")
BUNDLE = "/var/containers/Bundle/Application/" + synthetic_uuid("B") + "/ARMSX3.app"
BUNDLE_ID = "org.example.synthetic.armsx3"
APPGROUP = "/private" + CONTAINERS + "Shared/AppGroup/" + synthetic_uuid("C")
LEFTOVER = CONTAINERS + "Data/"
UDID = "0" * 8 + "-" + "1E" * 8
REPORTER_KEY = "synthetic-reporter-key"
TEAM_ID = "SYNTHTEAM0"
BOOT_SESSION = synthetic_uuid("F")
VENDOR_ID = synthetic_uuid("E")
MID = "·"
BINARY_LINE = MID + "! 0:00:00.200000 SYS: binary " + synthetic_uuid("D") + " loaded"
DECOY = "DECOY-TOKEN-never-in-a-report"
NEWER_TOKEN = "NEWER-SESSION-never-in-this-report"
OLDER, REPORTED, NEWER = "20260101T000000Z", "20260102T000000Z", "20260103T000000Z"
REPORT_NOW = 1_800_000_000
STAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(REPORT_NOW))
REPORT_LAUNCH = json.dumps({"schema": "armsx3-launch-record/1", "machine": "iPhone-synthetic", "os_build": "23A000",
                            "jit_state": "on", "helper_outcome": "finished"}).encode("utf-8")


def fixture_log():
    """The redaction fixture, shaped like RPCS3.log: a BOM, a bare header, then `·<L> time Channel: text` lines."""
    lines = [
        "﻿ARMSX3 iOS | RPCS3 synthetic",
        MID + "! 0:00:00.100000 SYS: Path: /private" + HOME + "/Library/Application Support/rpcs3/",
        MID + "! 0:00:00.110000 SYS: Records: " + HOME + "/Documents/armsx3/",
        MID + "! 0:00:00.120000 SYS: Icons: /private" + BUNDLE + "/Icons/ui/",
        MID + "! 0:00:00.130000 SYS: Executable: " + BUNDLE + "/ARMSX3",
        MID + "! 0:00:00.140000 SYS: Games: " + APPGROUP + "/games/ABCD12345/",
        MID + "! 0:00:00.150000 SYS: Scan: " + LEFTOVER,
        MID + "! 0:00:00.160000 SYS: Device: " + UDID,
        MID + "! 0:00:00.170000 SYS: Bundle: " + BUNDLE_ID,
        MID + '! 0:00:00.180000 SYS: "crashReporterKey" : "' + REPORTER_KEY + '"',
        MID + '! 0:00:00.190000 SYS: "deviceIdentifierForVendor":"' + VENDOR_ID + '"',
        BINARY_LINE,
        MID + "E 0:00:01.000000 SYS: Could not open " + HOME + "/Documents/missing.bin",
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


# (rule, text the staged RPCS3.log must hold), in the order the rules run.
REDACTED = (
    ("home", "Path: ~/Library/Application Support/rpcs3/"),
    ("home", "Records: ~/Documents/armsx3/"),
    ("bundle", "Icons: <app>/Icons/ui/"),
    ("bundle", "Executable: <app>/ARMSX3"),
    ("container", "Games: <container>/games/ABCD12345/"),
    ("prefix", "Scan: <container>/Data/"),
    ("udid", "Device: <udid>"),
    ("bundle-id", "Bundle: <bundle-id>"),
    ("keys", '"crashReporterKey" : "<redacted>"'),
    ("keys", '"deviceIdentifierForVendor" : "<redacted>"'),
)

ALLOWLIST = ("RPCS3.log", "TTY.log", "launch.json", "older/" + OLDER + "-RPCS3.log", "session-record.json",
             "settings-all-games.yml", "settings-this-game.yml", "summary.txt")

REPORT_DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let files = FileManager.default
let now = Date(timeIntervalSince1970: @@NOW@@)
// The RPCN username is read from r1's rpcn.yml the way Redaction.live reads the app's.
let rules = Redaction(home: @@HOME@@, bundle: @@BUNDLE@@, bundleID: @@BUNDLEID@@,
                      rpcnName: Redaction.rpcnName(inSupport: tmp.appendingPathComponent("r1/Library/Application Support")))
let form = ReportForm(whatHappened: "It froze on the title screen", whereInGame: "")
let settings = ReportSettings(allGames: "Video:\n  Renderer: Vulkan\n", thisGame: "Core:\n  PPU Decoder: Interpreter\n")

func line(_ text: String) { print(text) }

func container(_ name: String) -> DiagnosticsStore {
    let base = tmp.appendingPathComponent(name, isDirectory: true)
    let support = base.appendingPathComponent("Library/Application Support", isDirectory: true)
    let documents = base.appendingPathComponent("Documents", isDirectory: true)
    return DiagnosticsStore(root: support.appendingPathComponent("armsx3-logs", isDirectory: true),
                            rpcs3Dir: support.appendingPathComponent("rpcs3", isDirectory: true),
                            recordDir: documents.appendingPathComponent("armsx3", isDirectory: true),
                            reportsDir: documents.appendingPathComponent("Reports", isDirectory: true),
                            tmpDir: base.appendingPathComponent("tmp", isDirectory: true),
                            redaction: rules)
}

func entries(_ dir: URL) -> Int { ((try? files.contentsOfDirectory(atPath: dir.path)) ?? []).count }

func report(_ name: String) throws -> (DiagnosticsStore, ReportResult) {
    let s = container(name)
    let session = s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true)
    return (s, try s.buildReport(session: session, form: form, settings: settings, now: now))
}

// R1: the allowlist, through the real system zipper, into Reports.
do {
    let (s, r) = try report("r1")
    line("R1 built=\(files.fileExists(atPath: r.zip.path) ? 1 : 0) staging=\(entries(s.tmpDir)) reports=\(entries(s.reportsDir))")
} catch {
    line("R1 error")
}

// R8: no RPCS3.log in the session.
do {
    let (_, r) = try report("r8")
    let log = r.summary.components(separatedBy: "\n").contains("Log: none") ? "none" : "staged"
    line("R8 built=\(files.fileExists(atPath: r.zip.path) ? 1 : 0) log=\(log)")
} catch {
    line("R8 error")
}

// R9: a session TTY.log that is a symbolic link.
do {
    let (_, r) = try report("r9")
    line("R9 tty=\(r.files.contains("TTY.log") ? "staged" : "skipped")")
} catch {
    line("R9 error")
}

// ---- The cap, the summary, the pruning.

let oracle = [@@ORACLE@@].map { try! NSRegularExpression(pattern: $0) }
func leaks(_ text: String) -> Int {
    oracle.reduce(0) { $0 + $1.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)) }
}
// The fixtures' container ids: a JSON file escapes its slashes, which the oracle's patterns never match.
let containerIDs = [@@CONTAINERIDS@@]
func keepsAnID(_ text: String) -> Bool { containerIDs.contains { text.contains($0) } }

// A zip a budget can be exact against: as large as the staged files together. It keeps a copy of what
// it was handed, so the staged files can be read after the staging folder is gone.
struct SizeZipper: SaveDataIO {
    let capture: URL
    func zipDirectory(_ dir: URL, to zip: URL) throws {
        try? files.removeItem(at: capture)
        try files.createDirectory(at: capture.deletingLastPathComponent(), withIntermediateDirectories: true)
        try files.copyItem(at: dir, to: capture)
        var total = 0
        for sub in files.subpaths(atPath: dir.path) ?? [] {
            let a = try files.attributesOfItem(atPath: dir.appendingPathComponent(sub).path)
            if a[.type] as? FileAttributeType == .typeRegular { total += (a[.size] as? NSNumber)?.intValue ?? 0 }
        }
        try Data(count: total).write(to: zip)
    }
}

func capped(_ name: String, budget: Int = 8_000_000, stageTail: Int = 32 << 20, chunk: Int = 4 << 20)
    -> (DiagnosticsStore, URL) {
    let base = tmp.appendingPathComponent(name, isDirectory: true)
    let plain = container(name)
    let capture = base.appendingPathComponent("capture", isDirectory: true)
    return (DiagnosticsStore(root: plain.root, rpcs3Dir: plain.rpcs3Dir, recordDir: plain.recordDir,
                             io: SizeZipper(capture: capture), reportsDir: plain.reportsDir, tmpDir: plain.tmpDir,
                             redaction: rules, budget: budget, stageTail: stageTail, chunk: chunk,
                             ramBytes: 6_400_000_000), capture)
}

func build(_ s: DiagnosticsStore, form: ReportForm = form) throws -> ReportResult {
    try s.buildReport(session: s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true),
                      form: form, settings: settings, now: now)
}

func text(_ url: URL) -> String { (try? String(contentsOf: url, encoding: .utf8)) ?? "" }
func older(_ r: ReportResult) -> Int { r.files.contains { $0.hasPrefix("older/") } ? 1 : 0 }
let marker = try! NSRegularExpression(pattern: "^-- [0-9]+ bytes omitted --$")
func isMarker(_ s: Substring) -> Bool {
    let t = String(s)
    return marker.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
}
func lastLine(_ url: URL) -> Substring { text(url).split(separator: "\n").last ?? "" }

// R3: the older sessions go first.
do {
    let (s, capture) = capped("r3", budget: 100_000)
    let r = try build(s)
    let source = try Data(contentsOf: s.sessionsDir.appendingPathComponent("@@REPORTED@@/RPCS3.log"))
    let staged = try Data(contentsOf: capture.appendingPathComponent("RPCS3.log"))
    line("R3 older=\(older(r)) trimmed=\(r.trimmed || staged != source ? 1 : 0) fits=\(r.bytes <= 100_000 ? 1 : 0)")
} catch {
    line("R3 error")
}

// R4: then the start of the current log, behind the marker, at a line boundary.
do {
    let (s, capture) = capped("r4", budget: 200_000)
    let r = try build(s)
    let source = s.sessionsDir.appendingPathComponent("@@REPORTED@@/RPCS3.log")
    let staged = text(capture.appendingPathComponent("RPCS3.log")).split(separator: "\n", omittingEmptySubsequences: false)
    let boundary = staged.count > 1 && staged[1].hasPrefix("\u{00B7}")
    line("R4 trimmed=\(r.trimmed ? 1 : 0) tail=\(lastLine(capture.appendingPathComponent("RPCS3.log")) == lastLine(source) ? 1 : 0)"
        + " marker=\(staged.first.map(isMarker) == true ? 1 : 0) boundary=\(boundary ? 1 : 0) fits=\(r.bytes <= 200_000 ? 1 : 0)")
} catch {
    line("R4 error")
}

// R5: a first zip exactly the budget is not trimmed.
do {
    let first = try build(capped("r5", budget: Int.max).0)
    let r = try build(capped("r5", budget: first.bytes).0)
    line("R5 trimmed=\(r.trimmed ? 1 : 0) older=\(older(r))")
} catch {
    line("R5 error")
}

// R10: the first staging bound.
do {
    let (s, capture) = capped("r10", stageTail: 4096)
    let r = try build(s)
    let log = capture.appendingPathComponent("RPCS3.log")
    let size = (try? Data(contentsOf: log).count) ?? Int.max
    let first = text(log).split(separator: "\n").first ?? ""
    line("R10 capped=\(r.trimmed && size <= 4096 + 64 ? 1 : 0)"
        + " tail=\(lastLine(log) == lastLine(s.sessionsDir.appendingPathComponent("@@REPORTED@@/RPCS3.log")) ? 1 : 0)"
        + " marker=\(isMarker(first) ? 1 : 0)")
} catch {
    line("R10 error")
}

// R11: line-aligned chunks smaller than a line.
do {
    let (s, capture) = capped("r11", chunk: 64)
    _ = try build(s)
    let staged = text(capture.appendingPathComponent("RPCS3.log"))
    line("R11 leaks=\(staged.isEmpty ? -1 : leaks(staged))")
} catch {
    line("R11 error")
}

// R6: the summary, and the same text as summary.txt.
do {
    let (s, capture) = capped("r6")
    let flags = String(repeating: "\u{1F1F3}\u{1F1F1}", count: 2500)
    let r = try build(s, form: ReportForm(whatHappened: flags, whereInGame: " \n "))
    let lines = r.summary.components(separatedBy: "\n")
    func value(_ key: String) -> String? {
        lines.first { $0.hasPrefix(key + ": ") }.map { String($0.dropFirst(key.count + 2)) }
    }
    let settingsBlocks = r.summary.contains("Settings changed from the defaults, all games:\nVideo:\n  Renderer: Vulkan\n")
        && r.summary.contains("Settings changed from the defaults, this game:\nCore:\n  PPU Decoder: Interpreter\n")
    let what = value("What happened") ?? ""
    line("R6 serial=\(value("Serial") == "ABCD12345" ? 1 : 0)"
        + " device=\(value("Device") == "iPhone-synthetic, iOS 23A000, JIT on, 6.4 GB RAM" ? 1 : 0)"
        + " jithelper=\(value("JIT helper") ?? "nil")"
        + " fps=\(value("Last FPS") ?? "nil")"
        + " error=\(value("Last error") == "\u{00B7}F 0:00:04.000000 SYS: Fatal at <container>/saves" ? 1 : 0)"
        + " settings=\(settingsBlocks ? 1 : 0)"
        + " empty=\(value("Where in the game") == "(not given)" ? 1 : 0)"
        + " cap=\(what.count)\(what == String(flags.prefix(2000)) ? "" : "-mangled")"
        + " redacted=\(leaks(r.summary) == 0 && r.summary.contains("<container>") ? 1 : 0)")
    line("R6zip same=\(text(capture.appendingPathComponent("summary.txt")) == r.summary ? 1 : 0)")
} catch {
    line("R6 error")
}

// R7: Reports keeps the five newest.
do {
    let (s, _) = capped("r7")
    let r = try build(s)
    let left = ((try? files.contentsOfDirectory(atPath: s.reportsDir.path)) ?? [])
        .filter { $0.hasPrefix("ARMSX3-report-") && $0.hasSuffix(".zip") }
    line("R7 reports=\(left.count)")
    line("R7new kept=\(left.contains(r.zip.lastPathComponent) ? 1 : 0)")
} catch {
    line("R7 error")
}

// ---- The summary tells the truth.

func summaryLines(_ name: String) -> [String] {
    let s = container(name)
    return s.summary(session: s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true),
                     form: form, settings: settings).components(separatedBy: "\n")
}
func summaryValue(_ name: String, _ key: String) -> String? {
    summaryLines(name).first { $0.hasPrefix(key + ": ") }.map { String($0.dropFirst(key.count + 2)) }
}
for name in ["R12", "R13", "R14", "R15", "R18"] {
    line("\(name) fps=\(summaryValue(name.lowercased(), "Last FPS") ?? "nil")")
}
line("R16 serial=\(summaryValue("r16", "Serial") ?? "nil") fps=\(summaryValue("r16", "Last FPS") ?? "nil")"
    + " error=\(summaryValue("r16", "Last error") == @@R16ERROR@@ ? 1 : 0)")
line("R17 serial=\(summaryValue("r17", "Serial") ?? "nil") error=\(summaryValue("r17", "Last error") == @@R17FATAL@@ ? 1 : 0)")
// The Image line, directly under Device, from the head of the log.
for name in ["R19", "R20", "R21"] {
    let lines = summaryLines(name.lowercased())
    let image = lines.firstIndex { $0.hasPrefix("Image: ") }
    let device = lines.firstIndex { $0.hasPrefix("Device: ") }
    let next = (image != nil && device != nil && image! == device! + 1) ? 1 : 0
    line("\(name) \(image.map { lines[$0] } ?? "image=none") next=\(next)")
}

// ---- MetricKit's crash diagnostics.

func payloadNames(_ s: DiagnosticsStore) -> [String] {
    ((try? files.contentsOfDirectory(atPath: s.metricKitDir.path)) ?? []).sorted()
}

// M1: written whole at once, the five newest kept, nothing else left in the folder.
do {
    let s = container("m1")
    func body(_ n: Int) -> Data { Data("{\"payload\":\(n)}".utf8) }
    try s.persistPayload(body(1), ending: now)
    let first = payloadNames(s)
    let persisted = first.count == 1 && first[0].hasSuffix(".json")
        && (try? Data(contentsOf: s.metricKitDir.appendingPathComponent(first[0]))) == body(1)
    // Payload 2 ends in the same second as payload 1, so it needs a name of its own.
    for n in 2...7 { try s.persistPayload(body(n), ending: now.addingTimeInterval(Double(max(0, n - 2)))) }
    let names = payloadNames(s)
    let kept = names.map { try? Data(contentsOf: s.metricKitDir.appendingPathComponent($0)) }
    let newest = Set(kept) == Set((3...7).map { Optional(body($0)) })
    let atomic = names.allSatisfy { $0.hasSuffix(".json") && !$0.hasPrefix(".") }
    line("M1 persisted=\(persisted ? 1 : 0) pruned=\(newest ? names.count : -1) atomic=\(atomic ? 1 : 0)")
} catch {
    line("M1 error")
}

// M2, M3: a session's report carries the payloads written since it started, redacted.
do {
    let (s, capture) = capped("m2")
    let r = try build(s)
    let after = "metrickit/@@PAYLOAD_AFTER@@"
    let staged = text(capture.appendingPathComponent(after))
    let redacted = !staged.isEmpty && leaks(staged) == 0 && !staged.contains(@@REPORTERKEY@@)
        && !staged.contains(@@HOME@@) && staged.contains("\"crashReporterKey\" : \"<redacted>\"")
        && !keepsAnID(staged) && staged.contains("~/tmp/never-a-real-path")
    line("M2 included=\(r.files.contains(after) ? 1 : 0)"
        + " older=\(r.files.contains("metrickit/@@PAYLOAD_BEFORE@@") ? 1 : 0)"
        + " redacted=\(redacted ? 1 : 0) binary=\(staged.contains(@@BINARYUUID@@) ? 1 : 0)")
    let none = container("m3")
    line("M3 crash=\(s.hasCrashDiagnostic(for: s.sessionsDir.appendingPathComponent("@@REPORTED@@")) ? 1 : 0)"
        + " none=\(none.hasCrashDiagnostic(for: none.sessionsDir.appendingPathComponent("@@REPORTED@@")) ? 1 : 0)")
} catch {
    line("M2 error")
}

// A1: the tester's crash file rides in the zip, redacted.
do {
    let (s, capture) = capped("a1")
    let ips = tmp.appendingPathComponent("a1/Inbox/ARMSX3-2026-01-02-000100.ips")
    let r = try s.buildReport(session: s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true),
                              form: form, settings: settings, attachments: [ips], now: now)
    let name = r.files.first { $0.hasPrefix("crash-attached") } ?? "none"
    let staged = text(capture.appendingPathComponent(name))
    let redacted = !staged.isEmpty && leaks(staged) == 0 && !staged.contains(@@REPORTERKEY@@)
        && !staged.contains(@@HOME@@) && staged.contains("\"crashReporterKey\" : \"<redacted>\"")
        && !keepsAnID(staged) && staged.contains("<app>/ARMSX3") && staged.contains("~/tmp/never-a-real-path")
        && !staged.contains(@@TEAMID@@) && staged.contains("\"codeSigningTeamID\" : \"<redacted>\"")
        && !staged.contains(@@BOOTSESSION@@) && staged.contains("\"bootSessionUUID\" : \"<redacted>\"")
    line("A1 attached=\(r.files.contains(name) ? 1 : 0) name=\(name) redacted=\(redacted ? 1 : 0)")
} catch {
    line("A1 error")
}

// A2: only a small `.ips`-shaped text file is a crash file; a refused one stops the build before anything
// is staged. The pairing file, even behind an `.ips` header, is never one.
do {
    let s = container("a2")
    let inbox = tmp.appendingPathComponent("a2/Inbox", isDirectory: true)
    func verdict(_ name: String) -> String { s.isCrashFile(inbox.appendingPathComponent(name)) ? "accepted" : "refused" }
    func refusedBuild(_ names: [String]) -> Bool {
        do {
            _ = try s.buildReport(session: s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true),
                                  form: form, settings: settings,
                                  attachments: names.map { inbox.appendingPathComponent($0) }, now: now)
        } catch DiagnosticsError.notACrashFile {
            return entries(s.reportsDir) == 0 && entries(s.tmpDir) == 0
        } catch {}
        return false
    }
    let threw = refusedBuild(["big.ips"]) && refusedBuild(["ok.ips", "pairingFile.plist"])
    line("A2 big=\(verdict("big.ips")) binary=\(verdict("binary.ips")) ok=\(verdict("ok.ips"))"
        + " plain=\(verdict("plain.ips")) pairing=\(verdict("pairingFile.plist"))"
        + " disguised=\(verdict("disguised.ips")) throws=\(threw ? 1 : 0)")
}
"""

# A made-up build UUID, assembled so no literal identifier shape sits in this file.
IMAGE_SAMPLE = "base=0x10f4c4000 slide=0xf4c4000 uuid=" + "-".join(("8E1B6F2C", "FFA5", "3B5A", "9C1D", "AFF7996C0D10"))
IMAGE_BAD = "base=0x10 slide=0x0 uuid=not-a-uuid"

EXPECTED_REPORT = {
    "R1": "R1 built=1 staging=0 reports=1",
    "R8": "R8 built=1 log=none",
    "R9": "R9 tty=skipped",
    "R3": "R3 older=0 trimmed=0 fits=1",
    "R4": "R4 trimmed=1 tail=1 marker=1 boundary=1 fits=1",
    "R5": "R5 trimmed=0 older=1",
    "R10": "R10 capped=1 tail=1 marker=1",
    "R11": "R11 leaks=0",
    "R6": "R6 serial=1 device=1 jithelper=finished fps=29.8 error=1 settings=1 empty=1 cap=2000 redacted=1",
    "R6zip": "R6zip same=1",
    "R7": "R7 reports=5",
    "R7new": "R7new kept=1",
    "R12": "R12 fps=45.2 (before pause)",
    "R13": "R13 fps=30.0 (before stop)",
    "R14": "R14 fps=50.0",
    "R15": "R15 fps=0.0",
    "R16": "R16 serial=WXYZ00001 fps=none error=1",
    "R17": "R17 serial=WXYZ00002 error=1",
    "R18": "R18 fps=none (paused)",
    "R19": "R19 Image: " + IMAGE_SAMPLE + " next=1",
    "R20": "R20 image=none next=0",
    "R21": "R21 image=none next=0",
    "M1": "M1 persisted=1 pruned=5 atomic=1",
    "M2": "M2 included=1 older=0 redacted=1 binary=1",
    "M3": "M3 crash=1 none=0",
    "A1": "A1 attached=1 name=crash-attached.ips redacted=1",
    "A2": "A2 big=refused binary=refused ok=accepted plain=refused pairing=refused disguised=refused throws=1",
}


# The RPCN username rule over synthetic text; the decoys hold the name inside a longer token, so they must survive.
RPCN_NAME = "probeuser"
RPCN_YML = 'Version: 2\nHost: "127.0.0.1:1"\nNPID: ' + RPCN_NAME + '\nPassword: ""\nToken: ""\n'
RPCN_LOGGED_IN = MID + "S 0:00:02.000000 RPCN: You are now logged in RPCN(" + RPCN_NAME + " | ProbeUser)!"
RPCN_DECOYS = ("probeuser2", "my_probeuser", "probeuser-old", "xprobeuser")
# The core's three friend lines with a synthetic name, and the same name in another line shape.
FRIEND_NAME = "frienduser"
FRIEND_LEVELS = ("S 0:00:03.000000", "E 0:00:03.100000", "S 0:00:03.200000")
FRIEND_LINES = tuple(MID + level + " RPCN: " + before + FRIEND_NAME + after + (": NotFound" if "error" in after else "")
                     for level, (before, after) in zip(FRIEND_LEVELS, FRIEND_SHAPES))
FRIEND_ELSEWHERE = MID + '! 0:00:03.300000 RPCN: Sending message to "' + FRIEND_NAME + '":'
# The other line shapes, the player's own name in a member dump, and a line of no shape that must survive.
RPCN_ONLINE = "DeskOnline"
OWN_LINES = (MID + "S 0:00:02.300000 RPCN: You are now logged in RPCN(" + RPCN_NAME + " | " + RPCN_ONLINE + ")!",
             MID + "S 0:00:02.400000 RPCN: You have successfully created a RPCN account(" + RPCN_NAME + " | "
             + RPCN_NAME + ")!")
FRIEND_LOG_LINES = tuple(MID + "! 0:00:04.%06d " % (index * 10) + message.format(FRIEND_NAME)
                         for index, (_b, _a, _core, message) in enumerate(FRIEND_LOG_SHAPES))


def shape_tail(before, after, message):
    """A fixture message as the friend rule should leave it: the name between the halves replaced."""
    start = message.index(before) + len(before)
    end = message.index(after, start) if after else len(message)
    return message[:start] + "<rpcn-friend>" + message[end:]


FRIEND_LOG_TAILS = tuple(shape_tail(before, after, message.format(FRIEND_NAME))
                         for before, after, _core, message in FRIEND_LOG_SHAPES)
OWN_IN_DUMP = MID + "W 0:00:04.900000 sceNp2: npId: " + RPCN_NAME
UNSHAPED = MID + "! 0:00:04.950000 SYS: Note " + FRIEND_NAME + "."
RPCN_LINES = (RPCN_LOGGED_IN,
              MID + "! 0:00:02.100000 RPCN: Others: " + ", ".join(RPCN_DECOYS) + ".",
              MID + "! 0:00:02.200000 SYS: Signed in as " + RPCN_NAME.upper() + ".") + FRIEND_LINES + (FRIEND_ELSEWHERE,) \
    + OWN_LINES + FRIEND_LOG_LINES + (OWN_IN_DUMP, UNSHAPED)

RPCN_DRIVER = r"""
import Foundation

let name = @@NAME@@
let yml = @@YML@@
let quoted = yml.replacingOccurrences(of: "NPID: " + name, with: "NPID: \"" + name + "\"")
let fromYml = Redaction.rpcnName(fromRpcnYml: yml) == name && Redaction.rpcnName(fromRpcnYml: quoted) == name
    && Redaction.rpcnName(fromRpcnYml: "Version: 2\n").isEmpty
    && Redaction.rpcnName(inSupport: URL(fileURLWithPath: @@SUPPORT@@)) == name
let home = @@HOME@@
let log = "Path: " + home + "/Library/Application Support/rpcs3/\n" + @@LOG@@
func marks(_ text: String, _ mark: String) -> Int { text.components(separatedBy: mark).count - 1 }

let out = DiagnosticsStore.redact(log, with: Redaction(home: home, bundle: "", bundleID: "", rpcnName: name))
let partial = [@@DECOYS@@].filter { !out.contains($0) }.count
let empty = marks(DiagnosticsStore.redact(log, with: Redaction(home: home, bundle: "", bundleID: "")), "<rpcn-user>")
let other = out.contains("Path: ~/") && !out.contains(home) ? 1 : 0
print("RPR fromyml=\(fromYml ? 1 : 0) replaced=\(marks(out, "<rpcn-user>")) partial=\(partial) empty=\(empty) other=\(other)")

let friend = @@FRIEND@@
let shaped = out.components(separatedBy: "\n").filter { $0.contains("add_friend(") || $0.contains("from your friendlist") }
let tails = [@@TAILS@@].filter { tail in shaped.contains { $0.hasSuffix(tail) } }.count
print("RPF friends=\(marks(out, "<rpcn-friend>")) left=\(shaped.filter { $0.contains(friend) }.count) tails=\(tails) elsewhere=\(out.contains(@@ELSEWHERE@@) ? 1 : 0)")

// The other shapes, the line of no shape, the own name in a dump, and the own-account pairs.
let outLines = out.components(separatedBy: "\n")
let shapes = [@@SHAPETAILS@@].filter { tail in outLines.contains { $0.hasSuffix(tail) } }.count
let named = outLines.filter { $0.contains(friend) }.count
let unshaped = out.contains(@@UNSHAPED@@) ? 1 : 0
let own = out.contains("sceNp2: npId: <rpcn-user>") ? 1 : 0
let ownpair = outLines.filter { $0.hasSuffix("(<rpcn-user> | <rpcn-user>)!") }.count
let online = out.contains(@@ONLINE@@) ? 1 : 0
print("RPS shapes=\(shapes) named=\(named) unshaped=\(unshaped) own=\(own) ownpair=\(ownpair) online=\(online)")
"""

# The pinned username, friend and shape lines, each measured by a real run.
RPR_LINE = "RPR fromyml=1 replaced=8 partial=0 empty=6 other=1"
RPF_LINE = "RPF friends=18 left=0 tails=3 elsewhere=0"
RPS_LINE = "RPS shapes=14 named=1 unshaped=1 own=1 ownpair=3 online=0"


def fabricated(tmp, name):
    """A container laid out like the app's, with the decoys every report must leave behind."""
    base = Path(tmp) / name
    support = base / "Library" / "Application Support"
    dirs = {"base": base, "sessions": support / "armsx3-logs" / "sessions", "rpcs3": support / "rpcs3",
            "record": base / "Documents" / "armsx3"}
    for key in ("sessions", "rpcs3", "record"):
        dirs[key].mkdir(parents=True)
    # The pairing file at both of its real relative locations (PairingImport.swift), synthetic content.
    for folder in (support / "StikJIT", base / "Documents" / "StikJIT"):
        folder.mkdir(parents=True)
        (folder / "pairingFile.plist").write_text("<plist><string>" + DECOY + "</string></plist>\n", encoding="utf-8")
    save = dirs["rpcs3"] / "dev_hdd0" / "home" / "00000001" / "savedata" / "ABCD12345-SAVE"
    save.mkdir(parents=True)
    (save / "SAVE.DAT").write_bytes(DECOY.encode("utf-8") * 4)
    (base / "Documents" / "stray.txt").write_text(DECOY + "\n", encoding="utf-8")
    return dirs


def write_report_fixtures(tmp):
    log = fixture_log()
    r1 = fabricated(tmp, "r1")
    older = r1["sessions"] / OLDER
    older.mkdir()
    write_state(older, "menu", True, "ABCD12345")
    (older / "RPCS3.log").write_bytes(log)
    (older / "TTY.log").write_bytes(DECOY.encode("utf-8"))
    reported = r1["sessions"] / REPORTED
    reported.mkdir()
    write_state(reported, "in-game", True, "ABCD12345")
    # The RPCN lines after the header, and the core's rpcn.yml where the driver's Redaction reads the username.
    (reported / "RPCS3.log").write_bytes(log.replace(b"\n", ("\n" + "\n".join(RPCN_LINES) + "\n").encode("utf-8"), 1))
    (r1["rpcs3"] / "rpcn.yml").write_text(RPCN_YML, encoding="utf-8")
    (reported / "launch.json").write_bytes(REPORT_LAUNCH)
    (reported / "notes.txt").write_text(DECOY + "\n", encoding="utf-8")
    (reported / "RPCS3.log.gz").write_bytes(b"\x1f\x8b unfinished")
    os.chmod(reported / "RPCS3.log.gz", 0)
    newer = r1["sessions"] / NEWER
    newer.mkdir()
    write_state(newer, "menu", False)
    (newer / "RPCS3.log").write_text(NEWER_TOKEN + "\n", encoding="utf-8")
    (r1["rpcs3"] / "TTY.log").write_bytes(log)
    (r1["record"] / "session.json").write_text(json.dumps({"schema": "armsx3-session-record/1",
                                                           "data": HOME + "/Documents", "stop_status": None}),
                                               encoding="utf-8")

    r8 = fabricated(tmp, "r8")
    (r8["sessions"] / REPORTED).mkdir()
    write_state(r8["sessions"] / REPORTED, "in-game", True, "ABCD12345")

    r9 = fabricated(tmp, "r9")
    session = r9["sessions"] / REPORTED
    session.mkdir()
    write_state(session, "in-game", True, "ABCD12345")
    (session / "RPCS3.log").write_bytes(log)
    os.symlink(r9["base"] / "Library" / "Application Support" / "StikJIT" / "pairingFile.plist", session / "TTY.log")
    (r9["rpcs3"] / "TTY.log").write_text("core console\n", encoding="utf-8")


def log_lines(count, text):
    """`count` RPCS3.log-shaped lines of varying length, numbered, each starting with the `·` level mark."""
    return [MID + "! 0:00:{0:02d}.{1:06d} SYS: {2} {1} {3}".format(n % 60, n, text, "pad" * (n % 7)) for n in range(count)]


def report_session(tmp, name, log, older=()):
    """A container whose reported session holds `log` (bytes) and whose older sessions hold the given logs."""
    dirs = fabricated(tmp, name)
    for index, data in enumerate(older):
        folder = dirs["sessions"] / "2025120{0}T000000Z".format(index + 1)
        folder.mkdir()
        write_state(folder, "menu", True, "ABCD12345")
        (folder / "RPCS3.log").write_bytes(data)
    session = dirs["sessions"] / REPORTED
    session.mkdir()
    write_state(session, "in-game", True, "ABCD12345")
    (session / "launch.json").write_bytes(REPORT_LAUNCH)
    if log is not None:
        (session / "RPCS3.log").write_bytes(log)
    return dirs


def text_bytes(lines):
    return ("\n".join(lines) + "\n").encode("utf-8")


IMAGE_HEADER = ["\ufeffARMSX3 iOS | RPCS3 synthetic", "synthetic system line", "synthetic OS line",
                IMAGE_PREFIX + IMAGE_SAMPLE]


def write_image_fixtures(tmp):
    """R19 to R21."""
    long_log = text_bytes(IMAGE_HEADER + log_lines(100000, "frame") + [perf(59, "30.0")])
    if len(long_log) <= (4 << 20) + (64 << 10):
        raise AssertionError("R19's log is {0} bytes; it must outgrow the 4 MiB tail window, or it proves nothing".format(len(long_log)))
    report_session(tmp, "r19", long_log)
    report_session(tmp, "r20", text_bytes(IMAGE_HEADER[:3] + [boot(1), perf(2, "30.0")]))
    report_session(tmp, "r21", text_bytes(IMAGE_HEADER[:3] + [IMAGE_PREFIX + IMAGE_BAD, boot(1), perf(2, "30.0")]))


def write_memory_fixture(tmp):
    """P1: a reported session whose RPCS3.log is at least 20 MiB, every line holding a container path."""
    lines, total, n = [], 0, 0
    while total < 20 << 20:
        line = MID + "! 0:00:{0:02d}.{1:06d} SYS: open ".format(n % 60, n) + APPGROUP + "/Library/f{0} frame data here".format(n)
        lines.append(line)
        total += len(line.encode("utf-8")) + 1
        n += 1
    log = text_bytes(lines)
    # Under the store's default stageTail (32 MiB) the log is staged whole; a smaller one proves less.
    if not (20 << 20) <= len(log) < (32 << 20):
        raise AssertionError("P1's log is {0} bytes; it must be 20 to 32 MiB".format(len(log)))
    report_session(tmp, "p1", log)


def write_cap_fixtures(tmp):
    # R3: two large older logs, a small current log.
    report_session(tmp, "r3", text_bytes(log_lines(100, "current")),
                   older=(text_bytes(log_lines(3000, "older")), text_bytes(log_lines(3000, "older"))))
    # R4: a current log of about 1 MB, a distinct last line.
    report_session(tmp, "r4", text_bytes(log_lines(12000, "frame") + [MID + "E 0:59:59.999999 SYS: THE LAST LINE"]))
    # R5: one older session and a current log, both small.
    report_session(tmp, "r5", text_bytes(log_lines(50, "current")), older=(text_bytes(log_lines(50, "older")),))
    # R10: a log of about 100 KB.
    report_session(tmp, "r10", text_bytes(log_lines(1200, "frame") + [MID + "W 0:59:59.999999 SYS: THE LAST LINE"]))
    # R11: lines longer than the 64-byte chunk, each carrying a container path or an identifier.
    lines = []
    for n in range(200):
        lines.append(MID + "! 0:00:00.{0:06d} SYS: open {1}/Library/f{0}".format(n, ["/private" + HOME, HOME][n % 2]))
        lines.append(MID + "! 0:00:00.{0:06d} SYS: game {1}/g{0} device {2}".format(n, APPGROUP, UDID))
    report_session(tmp, "r11", text_bytes(lines))
    # R6: perf lines, errors, then later lines that are neither.
    report_session(tmp, "r6", text_bytes([
        "\ufeffARMSX3 iOS | RPCS3 synthetic",
        MID + "! 0:00:01.000000 IOSDIAG: perf fps=12.5 frame_ms=80.0 shaders_new=0",
        MID + "E 0:00:02.000000 SYS: an earlier error",
        MID + "! 0:00:03.000000 IOSDIAG: perf fps=29.8 frame_ms=33.5 shaders_new=1",
        MID + "F 0:00:04.000000 SYS: Fatal at " + APPGROUP + "/saves",
        MID + "W 0:00:05.000000 SYS: a warning after it",
    ]))
    write_truth_fixtures(tmp)
    write_image_fixtures(tmp)
    # R7: six earlier reports already in Reports, and a file that is not a report.
    dirs = report_session(tmp, "r7", text_bytes(log_lines(10, "current")))
    reports = dirs["base"] / "Documents" / "Reports"
    reports.mkdir()
    for n in range(1, 7):
        (reports / "ARMSX3-report-2026010{0}T000000Z.zip".format(n)).write_bytes(b"PK earlier report")
    (reports / "notes.txt").write_text("not a report\n", encoding="utf-8")


# Lines shaped like the core's own, timestamps rising line by line.
def boot(t):
    return MID + "! 0:00:{0:02d}.000000 SYS: Emulator::BootGame: path='/games/x', title_id=''".format(t)


def serial_line(t, serial):
    return MID + "! 0:00:{0:02d}.000000 SYS: Serial: {1}".format(t, serial)


def perf(t, fps):
    return MID + ("! 0:00:{0:02d}.000000 IOSDIAG: perf fps={1} frame_ms=22.1 shaders_new=0 mem_mib=100 headroom_mib=50"
                  " thermal=fair").format(t, fps)


def pause(t):
    return MID + "S 0:00:{0:02d}.000000 {{TID: 915421}} SYS: Emulation is being paused... (mark=0)".format(t)


def resume(t):
    return MID + "S 0:00:{0:02d}.000000 SYS: Emulation has been resumed!".format(t)


def stop(t):
    return MID + "! 0:00:{0:02d}.000000 SYS: Stopping emulator...".format(t)


R16_ERROR = MID + "E 0:00:04.000000 SYS: Failed to install packages"
R17_FATAL = MID + "F 0:00:03.000000 SYS: the fatal line"


def write_truth_fixtures(tmp):
    report_session(tmp, "r12", text_bytes([boot(1), serial_line(2, "ABCD12345"), perf(3, "59.9"), perf(4, "45.2"),
                                           perf(5, "0.0"), pause(6), perf(7, "0.0"), perf(8, "0.0")]))
    report_session(tmp, "r13", text_bytes([boot(1), perf(2, "30.0"), stop(3), perf(4, "0.0"), perf(5, "0.0")]))
    report_session(tmp, "r14", text_bytes([boot(1), perf(2, "40.0"), pause(3), perf(4, "0.0"), resume(5),
                                           perf(6, "50.0")]))
    report_session(tmp, "r15", text_bytes([boot(1), perf(2, "40.0"), pause(3), perf(4, "0.0"), resume(5),
                                           perf(6, "0.0")]))
    report_session(tmp, "r18", text_bytes([boot(1), perf(2, "50.0"), stop(3), boot(4), perf(5, "0.0"), pause(6),
                                           perf(7, "0.0")]))
    # R16: a boot that failed. state.json never got a serial; the config dump's indented line is not the core's.
    dirs = report_session(tmp, "r16", text_bytes([boot(1), serial_line(2, "WXYZ00001"), '    Serial: ""', R16_ERROR,
                                                  perf(5, "0.0"), perf(6, "0.0"), perf(7, "0.0")]))
    write_state(dirs["sessions"] / REPORTED, "menu", False)
    report_session(tmp, "r17", text_bytes([serial_line(1, "WXYZ00002"), perf(2, "20.0"), R17_FATAL,
                                           MID + "E 0:00:04.000000 SYS: a newer error",
                                           MID + "W 0:00:05.000000 SYS: a warning"]))


# The session starts at T; one payload is written a minute after T and one a minute before, shaped like MXDiagnosticPayload.
STARTED = calendar.timegm(time.strptime(REPORTED, "%Y%m%dT%H%M%SZ"))
STARTED_ISO = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(STARTED))
PAYLOAD_AFTER = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(STARTED + 60)) + "-1.json"
PAYLOAD_BEFORE = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(STARTED - 60)) + "-1.json"
BINARY_UUID = synthetic_uuid("D")


def payload_json():
    return json.dumps({"timeStampBegin": STARTED_ISO, "crashDiagnostics": [{
        "diagnosticMetaData": {"crashReporterKey": REPORTER_KEY, "bundleIdentifier": BUNDLE_ID,
                               "exceptionType": 1, "signal": 9},
        "callStackTree": {"callStacks": [{"callStackRootFrames": [{
            "binaryUUID": BINARY_UUID, "binaryName": "ARMSX3", "offsetIntoBinaryTextSegment": 4096,
            "path": HOME + "/tmp/never-a-real-path"}]}]}}]}, indent=1).replace("/", "\\/").encode("utf-8")


def write_payload_fixtures(tmp):
    for name, payloads in (("m2", ((PAYLOAD_AFTER, 60), (PAYLOAD_BEFORE, -60))), ("m3", ((PAYLOAD_BEFORE, -60),))):
        dirs = report_session(tmp, name, text_bytes(log_lines(10, "current")))
        write_state(dirs["sessions"] / REPORTED, "in-game", True, "ABCD12345", started=STARTED_ISO)
        folder = dirs["sessions"].parent / "metrickit"
        folder.mkdir()
        for file_name, offset in payloads:
            (folder / file_name).write_bytes(payload_json())
            os.utime(folder / file_name, (STARTED + offset, STARTED + offset))


def ips_text():
    """A crash file shaped like an `.ips`: a one-line JSON header, then a JSON body with its slashes escaped
    as Apple writes them (`\\/`). Synthetic values."""
    header = json.dumps({"app_name": "ARMSX3", "bug_type": "309", "name": "ARMSX3"})
    body = json.dumps({"crashReporterKey": REPORTER_KEY, "procPath": BUNDLE + "/ARMSX3",
                       "codeSigningTeamID": TEAM_ID, "bootSessionUUID": BOOT_SESSION,
                       "usedImages": [{"uuid": BINARY_UUID, "path": HOME + "/tmp/never-a-real-path"}]}, indent=1)
    return (header + "\n" + body.replace("/", "\\/") + "\n").encode("utf-8")


def write_attachment_fixtures(tmp):
    dirs = report_session(tmp, "a1", text_bytes(log_lines(10, "current")))
    (dirs["base"] / "Inbox").mkdir()
    (dirs["base"] / "Inbox" / "ARMSX3-2026-01-02-000100.ips").write_bytes(ips_text())
    dirs = report_session(tmp, "a2", text_bytes(log_lines(10, "current")))
    inbox = dirs["base"] / "Inbox"
    inbox.mkdir()
    (inbox / "big.ips").write_bytes(b"x" * (5 << 20))
    (inbox / "binary.ips").write_bytes(b"\xc3\x28\xff\xfe" * 64)
    (inbox / "ok.ips").write_bytes(ips_text())
    (inbox / "plain.ips").write_text("just some text, no .ips header\n", encoding="utf-8")
    # A decoy pairing file shaped like the StikJIT one, synthetic values only, and the same plist behind an `.ips`-like line.
    pairing = ('<?xml version="1.0" encoding="UTF-8"?>\n<plist version="1.0">\n<dict>\n'
               "<key>HostPrivateKey</key>\n<data>" + DECOY + "</data>\n</dict>\n</plist>\n")
    (inbox / "pairingFile.plist").write_text(pairing, encoding="utf-8")
    (inbox / "disguised.ips").write_text(json.dumps({"bug_type": "309"}) + "\n" + pairing, encoding="utf-8")


def zip_entries(reports):
    """{entry name: bytes} of the one zip in `reports`, file entries only; {} when there is not exactly one."""
    zips = sorted(Path(reports).glob("*.zip"))
    if len(zips) != 1:
        return {}
    with zipfile.ZipFile(zips[0]) as zipped:
        return {name: zipped.read(name) for name in zipped.namelist() if not name.endswith("/")}


def frontend_swift():
    """{path relative to ios/app: text} of every Swift file under ios/app/."""
    files = {p.relative_to(FRONTEND).as_posix(): p.read_text(encoding="utf-8") for p in FRONTEND.rglob("*.swift")}
    if not files:
        raise AssertionError("no Swift file under ios/app/; the guard cannot pass over nothing")
    return files


def sheet_problems(raw_sheet, swift):
    """share, offmain and header over ProblemReportView.swift; `swift` maps every ios/app Swift file."""
    problems = []
    code = without_comments(raw_sheet)
    for match in LIFECYCLE.finditer(code):
        open_at = code.find("{", match.end())
        closure = code[slice(*brace_span(code, open_at))] if open_at != -1 else ""
        for token in UNPROMPTED:
            if token in closure:
                problems.append("share: a .{0} closure in ProblemReportView.swift names {1}".format(match.group(1), token))
    for path, text in sorted(swift.items()):
        if path in CLIPBOARD_USES:
            text = text.replace(CLIPBOARD_USES[path], "", 1)
        if path != "Diagnostics/ProblemReportView.swift" and "UIPasteboard" in text:
            problems.append("share: {0} names UIPasteboard; only ProblemReportView's Copy Summary may".format(path))
    spans = detached_spans(code)
    for match in STORE_CALL.finditer(code):
        if not any(start < match.start() < end for start, end in spans):
            problems.append("offmain: store.{0}( in ProblemReportView.swift runs outside Task.detached".format(match.group(1)))
    try:
        _, literals = scan(raw_sheet)
    except AssertionError as error:
        problems.append("walkthrough: ProblemReportView.swift cannot be scanned: {0}".format(error))
        literals = []
    for token in ("ARMSX3-", "Analytics Data"):
        if not any(token in literal for literal in literals):
            problems.append("walkthrough: no string literal in ProblemReportView.swift names {0}".format(token))
    if "FirmwarePicker(" not in code:
        problems.append("picker: ProblemReportView.swift presents no FirmwarePicker(")
    if "UIDocumentPickerViewController" in code:
        problems.append("picker: ProblemReportView.swift opens a UIDocumentPickerViewController of its own")
    if raw_sheet.split("\n", 1)[0] != SPDX_LINE:
        problems.append("header: ProblemReportView.swift's line 1 is not `{0}`".format(SPDX_LINE))
    if "Adapted from ARMSX2" in raw_sheet:
        problems.append("header: ProblemReportView.swift carries the lifted-file header")
    return problems


def problems_of(raw, swift=None):
    """raw: {file name: raw text} for the Swift and core files the static checks read; `swift` maps every ios/app Swift
    file (read from disk when None)."""
    swift = frontend_swift() if swift is None else swift
    problems = []
    store = without_comments(raw["DiagnosticsStore.swift"])
    if IMPORT.findall(store) != ["Foundation"]:
        problems.append("host: DiagnosticsStore.swift imports {0}; Foundation only".format(IMPORT.findall(store)))
    crash = without_comments(raw["CrashDiagnostics.swift"])
    for name, code in (("DiagnosticsStore.swift", store), ("CrashDiagnostics.swift", crash)):
        if LOGGING.search(code):
            problems.append("log: {0} logs".format(name))
    sheet = without_comments(raw["ProblemReportView.swift"])
    for name in ("DiagnosticsStore.swift", "ProblemReportView.swift", "CrashDiagnostics.swift"):
        for token in NETWORK:
            if token in raw[name]:
                problems.append("network: {0} names {1}".format(name, token))
    for name, code in (("DiagnosticsStore.swift", store), ("CrashDiagnostics.swift", crash)):
        for token in PAIRING:
            if token in code:
                problems.append("pairing: {0} names {1}".format(name, token))
    problems.extend(metrickit_problems(crash, store))
    problems.extend(sheet_problems(raw["ProblemReportView.swift"], swift))
    if COPIES.search(store):
        problems.append("copies: DiagnosticsStore.swift calls copyItem(; every staged file is read, redacted and written")
    for token in sorted(set(IDENTITY.findall(raw["DiagnosticsStore.swift"]))):
        problems.append("identity: DiagnosticsStore.swift names {0}".format(token))
    live = body_of(store, r"static\s+func\s+live\s*\(") or ""
    if ".applicationSupportDirectory" not in live or '"armsx3-logs"' not in live:
        problems.append("hidden: live() does not root the sessions at Application Support/armsx3-logs")
    start = body_of(without_comments(raw["ARMSX3FrontendRoot.swift"]), r"func\s+start\s*\(\s*\)\s*async") or ""
    at = -1
    for call in ORDER:
        at = start.find(call, at + 1)
        if at == -1:
            problems.append("order: start() does not run {0} in the order {1}".format(call, " < ".join(ORDER)))
            break
    problems.extend(marks_problems(without_comments(raw["GameScreenView.swift"])))
    problems.extend(offer_problems(without_comments(raw["ARMSX3FrontendRoot.swift"])))
    problems.extend(verbose_problems(without_comments(raw["DiagnosticsPane.swift"]),
                                     without_comments(raw["ARMSX3FrontendRoot.swift"])))
    problems.extend(scene_problems(without_comments(raw["ARMSX3FrontendRoot.swift"])))
    problems.extend(pane_problems(raw["DiagnosticsPane.swift"], without_comments(raw["SettingsRootView.swift"]),
                                  without_comments(raw["SettingsModel.swift"])))
    problems.extend(corelines_problems(store, raw["System.cpp"]))
    problems.extend(image_problems(store, raw["diag_log.cpp"]))
    problems.extend(friends_problems(store, raw["rpcn_client.cpp"]))
    problems.extend(ownpair_problems(store, raw["rpcn_client.cpp"]))
    problems.extend(friendshapes_problems(store, raw))
    problems.extend(process_problems(sheet, without_comments(raw["Rpcs3Bridge.mm"])))
    problems.extend(seeds_problems(store, without_comments(raw["rpcs3_ios_abi_*.cpp"])))
    problems.extend(harmless_problems(store, raw["overlay_controls.cpp"], raw["ErrorCodes.cpp"]))
    return sorted(problems)


def swift_literal(piece):
    """A Swift string literal holding `piece` as written in DiagnosticsStore.swift."""
    return '"' + piece.replace('"', '\\"') + '"'


PROCESS_NAMES = ("Rpcs3Bridge.stderrWitnessDropped()", "Rpcs3Bridge.deviceChecks()", "DiagnosticsSession.shared.current",
                 "Rpcs3Bridge.jitPairingReport()", "PairingSource.reportLine(", "PairingSource.ladderLine(",
                 "JitFallback.decide(", "running ? jitRoute() : nil", '"built-in helper (other install)"', '"built-in helper"',
                 '"Wait for Debugger"', '"opened through StikDebug"', '"StikDebug fallback (', '"none (',
                 "LiveContainerProbe.current", "running ? liveContainerDetail() : nil", "Helper Process Support \\(hooks)",
                 '"version unknown"', 'guard probe.insideLiveContainer else { return "no" }',
                 "running ? JitDeviceModel.shared.testResult?.reportWord : nil")


def process_problems(sheet, facade):
    """`process`: every store.buildReport( and store.summary( line passes `process:`, the sheet reads its facts, and
    the façade's +stderrWitnessDropped calls the witness's own counter."""
    problems = []
    for call in ("store.buildReport(", "store.summary("):
        found = [line for line in sheet.split("\n") if call in line]
        if not found:
            problems.append("process: ProblemReportView.swift has no {0} line".format(call))
        for line in found:
            if "process:" not in line:
                problems.append("process: a {0} line in ProblemReportView.swift passes no process:".format(call))
    for name in PROCESS_NAMES:
        if name not in sheet:
            problems.append("process: ProblemReportView.swift never names {0}".format(name))
    if "bundleID" in sheet or "bundlePath" in sheet:
        problems.append("process: ProblemReportView.swift reads a bundle id or path, which no report line may carry")
    body = body_of(facade, r"\+\s*\(uint64_t\)\s*stderrWitnessDropped\b") or ""
    if "armsx3_stderr_witness_dropped()" not in body:
        problems.append("process: Rpcs3Bridge.mm's +stderrWitnessDropped does not call armsx3_stderr_witness_dropped()")
    return problems


SEED_PAIR = re.compile(r'\{\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\}')
SWIFT_PAIR = re.compile(r'\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)')


def seeds_problems(store, abi):
    """`seeds`: DiagnosticsStore's `seededDefaults` holds k_seed_defaults' pairs, the same and in the same order."""
    start = abi.find("k_seed_defaults[] = {")
    core = SEED_PAIR.findall(abi[start:abi.find("};", start)]) if start != -1 else []
    decl = store.find("static let seededDefaults")
    open_at = store.find("= [", decl) if decl != -1 else -1
    mirror = SWIFT_PAIR.findall(store[open_at:store.find("]", open_at + 3)]) if open_at != -1 else []
    if not core:
        return ["seeds: rpcs3_ios_abi_*.cpp has no k_seed_defaults pairs"]
    if core != mirror:
        return ["seeds: DiagnosticsStore.swift's seededDefaults {0} is not k_seed_defaults {1}".format(mirror, core)]
    return []


HARMLESS_PATTERN = re.compile(r'"[^"\n]*failed with 0x\[0-9a-f\]\{8\} : CELL_[^"\n]*"')


def harmless_problems(store, overlay, errors):
    """`harmless`: the two skipped shapes in DiagnosticsStore.swift, pinned to the raw core formats that write them."""
    problems = []
    for literal in ('"Image resource file `"', '"\' could not be opened ("'):
        if literal not in store:
            problems.append("harmless: DiagnosticsStore.swift has no literal {0}".format(literal))
    if not HARMLESS_PATTERN.search(store):
        problems.append("harmless: DiagnosticsStore.swift has no pattern literal with `failed with 0x[0-9a-f]{8} : CELL_`")
    if 'rsx_log.error("Image resource file `%s\' could not be opened (%s)"' not in overlay:
        problems.append("harmless: overlay_controls.cpp no longer logs the icon miss the summary skips")
    for text in ('"\'%s\' failed with 0x%08x"', '" : "'):
        if text not in errors:
            problems.append("harmless: ErrorCodes.cpp no longer holds {0}".format(text))
    return problems


def ownpair_problems(store, client):
    """`ownpair`: each own-account line's text before the pair is a Swift literal, and rpcn_client.cpp logs that line."""
    problems = []
    for before in OWN_SHAPES:
        if swift_literal(before) not in store:
            problems.append("ownpair: DiagnosticsStore.swift has no literal for {0!r}".format(before))
        if not re.search(r'rpcn_log\.\w+\(\s*"' + re.escape(before + "%s | %s)!"), client):
            problems.append("ownpair: rpcn_client.cpp logs no {0!r} pair line; the own-pair rule would never match it".format(before))
    return problems


def friendshapes_problems(store, raw):
    """`friendshapes`: each FRIEND_LOG_SHAPES entry's halves are Swift literals, and every core line it stands for is logged whole."""
    problems = []
    for before, after, core, _message in FRIEND_LOG_SHAPES:
        for piece in (before, after):
            if piece and swift_literal(piece) not in store:
                problems.append("friendshapes: DiagnosticsStore.swift has no literal for {0!r}".format(piece))
        for name, channel, text in core:
            if not re.search(re.escape(channel) + r'\.\w+\(\s*"' + re.escape(text) + '"', raw[name]):
                problems.append("friendshapes: {0} logs no {1} line {2!r}; the friend rule would never match it".format(
                    name, channel, text))
    return problems


def friends_problems(store, client):
    """`friends`: each friend line's two halves are Swift literals, and rpcn_client.cpp logs that line whole."""
    problems = []
    for before, after in FRIEND_SHAPES:
        for piece in (before, after):
            if '"' + piece.replace('"', '\\"') + '"' not in store:
                problems.append("friends: DiagnosticsStore.swift has no literal for {0!r}".format(piece))
        logged = before.replace('"', '\\"') + "%s" + after.replace('"', '\\"')
        if not re.search(r'rpcn_log\.\w+\(\s*"' + re.escape(logged), client):
            problems.append("friends: rpcn_client.cpp logs no rpcn_log line {0!r}; the friend rule would never match it".format(logged))
    return problems


def image_problems(store, diag):
    """`image` over the comment-stripped DiagnosticsStore.swift and the raw diag_log.cpp."""
    problems = []
    for needle in ('"' + IMAGE_PREFIX + '"', '"^' + IMAGE_VALUE + '$"', '"Image: '):
        if needle not in store:
            problems.append("image: DiagnosticsStore.swift has no literal {0}".format(needle))
    if not re.search(r'\.text\s*=\s*"' + re.escape(IMAGE_PREFIX) + '"', diag):
        problems.append('image: diag_log.cpp does not assign .text = "{0}"'.format(IMAGE_PREFIX))
    return problems


def corelines_problems(store, system):
    """`corelines` over the comment-stripped DiagnosticsStore.swift and the RAW System.cpp."""
    problems = []
    for text in CORELINES:
        if '"SYS: {0}"'.format(text) not in store:
            problems.append('corelines: DiagnosticsStore.swift has no literal "SYS: {0}"'.format(text))
        if not re.search(r'sys_log\.\w+\(\s*"' + re.escape(text), system):
            problems.append("corelines: System.cpp logs no sys_log line starting {0!r}; summary() would never match it".format(text))
    if 'LOG_CHANNEL(sys_log, "SYS")' not in system:
        problems.append('corelines: System.cpp does not declare LOG_CHANNEL(sys_log, "SYS")')
    return problems


def metrickit_problems(crash, store):
    """`metrickit` over the comment-stripped CrashDiagnostics.swift and DiagnosticsStore.swift."""
    problems = []
    if sorted(IMPORT.findall(crash)) != ["Foundation", "MetricKit"]:
        problems.append("metrickit: CrashDiagnostics.swift imports {0}; exactly Foundation and MetricKit".format(
            IMPORT.findall(crash)))
    if not MX_DIAGNOSTIC.search(crash):
        problems.append("metrickit: CrashDiagnostics.swift does not implement didReceive(_ payloads: [MXDiagnosticPayload])")
    if "persistPayload(" not in crash:
        problems.append("metrickit: CrashDiagnostics.swift does not call persistPayload(")
    if MX_METRIC.search(crash):
        problems.append("metrickit: CrashDiagnostics.swift handles [MXMetricPayload]; daily metrics are not subscribed to")
    if ".atomic" not in (body_of(store, r"func\s+persistPayload\s*\(") or ""):
        problems.append("metrickit: DiagnosticsStore.persistPayload does not write with .atomic")
    return problems


VERBOSE_SWITCH = re.compile(
    r'"settings\.verboselogging"[^\n]*\n[^\n]*isOn:\s*Binding\(\s*get:\s*\{\s*SettingsStore\.shared\.verboseLogging\s*\}\s*,'
    r'\s*set:\s*\{\s*(\w+)\s+in\s+SettingsStore\.shared\.verboseLogging\s*=\s*\1\b[^}]*Rpcs3Bridge\.setVerboseLogging\(\1\)')


def verbose_problems(settings, root):
    problems = []
    if not VERBOSE_SWITCH.search(settings):
        problems.append("verbose: the Verbose logging switch does not read, store and apply SettingsStore.shared.verboseLogging")
    if not re.search(r"verbose:\s*SettingsStore\.shared\.verboseLogging\b", root):
        problems.append("verbose: the root does not start the log with the stored verbose flag")
    return problems


def after(body, first, then):
    """True when `then` appears in `body` after the first `first`."""
    at = body.find(first)
    return at != -1 and body.find(then, at) != -1


def marks_problems(screen):
    problems = []
    run = body_of(screen, r"private\s+func\s+run\s*\(\s*\)\s*async") or ""
    # The full call, title included.
    if not after(run, "SessionRecord.shared.start(", "DiagnosticsSession.shared.mark(.inGame, serial: game.serial, title: game.title)"):
        problems.append("marks: run() does not mark the session in-game with the serial after the record starts")
    for name, signature in (("stop()", r"private\s+func\s+stop\s*\(\s*\)"),
                            ("closeAfterStop()", r"private\s+func\s+closeAfterStop\s*\(\s*\)\s*async")):
        if not after(body_of(screen, signature) or "", "SessionRecord.shared.finish(", "DiagnosticsSession.shared.mark(.menu)"):
            problems.append("marks: {0} does not mark the session menu after the record finishes".format(name))
    change = screen.find(".onChange(of: scenePhase)")
    handler = screen[slice(*brace_span(screen, change))] if change != -1 else ""
    if "DiagnosticsSession.shared.mark(.background)" not in handler or "syncLog" not in handler:
        problems.append("marks: the scene-phase handler does not mark background and sync the log")
    return problems


OFFER_SHEET = re.compile(r"\.sheet\(item:\s*\$\w+\)\s*\{[^}]*?\bProblemReportView\(")


def offer_problems(root):
    problems = []
    start = body_of(root, r"func\s+start\s*\(\s*\)\s*async") or ""
    ready = start.find("stage = .ready")
    raised = start.find("crashOffer = true", ready) if ready != -1 else -1
    if raised == -1:
        problems.append("offer: start() raises no crash offer after stage = .ready")
    else:
        condition = start[start.rfind("if ", 0, raised):raised]
        if "DiagnosticsSession.shared.crashed" not in condition:
            problems.append("offer: the crash offer is not guarded by DiagnosticsSession.shared.crashed")
    if not OFFER_SHEET.search(root):
        problems.append("offer: ARMSX3FrontendRoot.swift presents no ProblemReportView( from a .sheet(item:")
    return problems


def scene_problems(root):
    """`scene` over the comment-stripped ARMSX3FrontendRoot.swift."""
    problems = []
    change = root.find(".onChange(of: scenePhase)")
    handler = root[slice(*brace_span(root, change))] if change != -1 else ""
    for active in ("true", "false"):
        if "DiagnosticsSession.shared.scene(active: {0})".format(active) not in handler:
            problems.append("scene: the root's scene-phase handler does not call scene(active: {0})".format(active))
    if root.count("markFirstScreen()") < 3:
        problems.append("scene: the root marks the first screen in fewer than three places")
    return problems


# The sheet line carries an onDismiss closure, so the trailing closure is the one after the line's last `)`.
PANE_CAPTION = "Tap the session where the problem happened. A report opens for it."
PANE_SHEET = re.compile(r"\.sheet\(item:[^\n]*\)\s*\{[^}]*?\bProblemReportView\(")


def pane_problems(raw_pane, settings, model):
    """`pane` over the raw DiagnosticsPane.swift and the comment-stripped SettingsRootView.swift and SettingsModel.swift."""
    problems = []
    if raw_pane.split("\n", 1)[0] != SPDX_LINE or "Adapted from ARMSX2" in raw_pane:
        problems.append("pane: DiagnosticsPane.swift lacks the SPDX first line or carries the lifted-file header")
    pane = without_comments(raw_pane)
    if not PANE_SHEET.search(pane):
        problems.append("pane: DiagnosticsPane.swift presents no ProblemReportView( from a .sheet(item:")
    rows = pane.find("sessionRows(current")
    if rows == -1 or not any(a <= rows < b for a, b in detached_spans(pane)):
        problems.append("pane: DiagnosticsPane.swift does not load sessionRows(current: inside Task.detached")
    if '"settings.pane." + SettingsPane.diagnostics.navigationKey' not in pane:
        problems.append("pane: DiagnosticsPane.swift's scope key is not built from SettingsPane.diagnostics.navigationKey")
    if "UIPasteboard" in pane:
        problems.append("pane: DiagnosticsPane.swift names UIPasteboard")
    # The rows read as tappable (a chevron) and the instruction sits above them.
    each = pane.find("ForEach(rows)")
    if each == -1 or "showsChevron: true" not in pane[each:]:
        problems.append("pane: the session rows do not pass showsChevron: true")
    caption = pane.find(PANE_CAPTION)
    if caption == -1 or each == -1 or caption > each:
        problems.append("pane: the instruction caption is missing or not above the session rows")
    if not re.search(r"managePanes[^\n]*\.jitDevice, \.diagnostics, \.about", settings):
        problems.append("pane: managePanes does not list .jitDevice, .diagnostics, .about")
    if "pane == .diagnostics" not in settings:
        problems.append("pane: SettingsRootView.swift does not dispatch .diagnostics")
    if "settings.verboselogging" in settings:
        problems.append("pane: SettingsRootView.swift still carries the Verbose logging switch")
    if not re.search(r"^\s*case [^\n]*\bdiagnostics\b", model, re.M):
        problems.append("pane: SettingsModel.swift declares no diagnostics case")
    return problems


def real_inputs():
    # The pane, SettingsModel.swift, the core NP files, Rpcs3Bridge.mm, and the files `seeds` and `harmless` read.
    raw = {path.name: read(path) for path in (STORE, ROOT, SCREEN, SETTINGS, SHEET, CRASH, SYSTEM, PANE, MODEL, DIAG_LOG,
                                              RPCN_CLIENT, NP_HANDLER, NP_NOTIFICATIONS, NP_REQUESTS, NP_STRUCTS,
                                              OVERLAY_CONTROLS, ERROR_CODES)}
    raw["rpcs3_ios_abi_*.cpp"] = abi_text(IOS / "bridge")
    raw["Rpcs3Bridge.mm"] = facade_text(IOS / "app" / "Bridge")
    return raw


class TheSessionStoreHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, SAVES, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_fixtures(tmp)
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@PREVIOUS@@", PREVIOUS)
            out = compile_and_run([SFO, ZIP, SAVES, FACTS, STORE], driver, tmp)
            out += "\n" + zip_holds_log(tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_cases(self):
        for key, expected in EXPECTED.items():
            with self.subTest(key):
                self.assertEqual(expected, self.lines.get(key), key)


class TheReportBuilds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, SAVES, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_report_fixtures(tmp)
            write_cap_fixtures(tmp)
            write_payload_fixtures(tmp)
            write_attachment_fixtures(tmp)
            driver = (REPORT_DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@NOW@@", str(REPORT_NOW))
                      .replace("@@HOME@@", json.dumps(HOME)).replace("@@BUNDLE@@", json.dumps(BUNDLE))
                      .replace("@@BUNDLEID@@", json.dumps(BUNDLE_ID)).replace("@@REPORTED@@", REPORTED)
                      .replace("@@ORACLE@@", ", ".join(json.dumps(pattern.pattern) for pattern in ORACLE))
                      .replace("@@PAYLOAD_AFTER@@", PAYLOAD_AFTER).replace("@@PAYLOAD_BEFORE@@", PAYLOAD_BEFORE)
                      .replace("@@REPORTERKEY@@", json.dumps(REPORTER_KEY))
                      .replace("@@BINARYUUID@@", json.dumps(BINARY_UUID))
                      .replace("@@TEAMID@@", json.dumps(TEAM_ID)).replace("@@BOOTSESSION@@", json.dumps(BOOT_SESSION))
                      .replace("@@CONTAINERIDS@@", ", ".join(json.dumps(synthetic_uuid(d)) for d in "ABC"))
                      .replace("@@R16ERROR@@", json.dumps(R16_ERROR, ensure_ascii=False)).replace("@@R17FATAL@@", json.dumps(R17_FATAL, ensure_ascii=False)))
            out = compile_and_run([SFO, ZIP, SAVES, FACTS, STORE], driver, tmp)
            cls.r1 = zip_entries(Path(tmp) / "r1" / "Documents" / "Reports")
            cls.r9 = zip_entries(Path(tmp) / "r9" / "Documents" / "Reports")
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_the_image_sample_is_the_shared_shape(self):
        self.assertRegex(IMAGE_SAMPLE, "^" + IMAGE_VALUE + "$")
        self.assertNotRegex(IMAGE_BAD, "^" + IMAGE_VALUE + "$")

    def test_cases(self):
        for key, expected in EXPECTED_REPORT.items():
            with self.subTest(key):
                self.assertEqual(expected, self.lines.get(key), key)

    def test_r1_holds_the_allowlist_and_nothing_else(self):
        folder = "ARMSX3-report-" + STAMP + "/"
        self.assertEqual(sorted(folder + name for name in ALLOWLIST), sorted(self.r1))
        for name, data in self.r1.items():
            with self.subTest(name):
                self.assertNotIn("pairing", name.lower())
                self.assertNotIn(DECOY.encode("utf-8"), data)
                self.assertNotIn(NEWER_TOKEN.encode("utf-8"), data)

    def test_r2_every_entry_is_redacted(self):
        self.assertTrue(self.r1, "R1 produced no zip to read")
        for name, data in self.r1.items():
            text = data.decode("utf-8")
            with self.subTest(name):
                for pattern in ORACLE:
                    self.assertIsNone(pattern.search(text), pattern.pattern)
                for secret in (HOME, "/private" + HOME, BUNDLE, "/private" + BUNDLE, BUNDLE_ID, REPORTER_KEY, VENDOR_ID):
                    self.assertNotIn(secret, text)
                # The RPCN username as a whole token in any case, since the decoys hold it inside longer tokens, and the friend lines.
                self.assertIsNone(re.search(r"(?i)(?<![A-Za-z0-9_-])" + RPCN_NAME + r"(?![A-Za-z0-9_-])", text))
                for line in FRIEND_LINES + FRIEND_LOG_LINES:
                    self.assertNotIn(line, text)
                self.assertNotIn(RPCN_ONLINE, text)
        log = self.r1["ARMSX3-report-" + STAMP + "/RPCS3.log"].decode("utf-8")
        for rule, expected in REDACTED:
            with self.subTest(rule=rule, text=expected):
                self.assertIn(expected, log)
        # The core's logged-in line comes out with both names replaced; the decoys survive.
        self.assertIn("You are now logged in RPCN(<rpcn-user> | <rpcn-user>)!", log)
        self.assertIn("Signed in as <rpcn-user>.", log)
        self.assertIn(", ".join(RPCN_DECOYS), log)
        self.assertIn('add_friend("<rpcn-friend>") succeeded', log)
        # A line of no shape survives, and the player's own name in a member dump stays the player's.
        self.assertIn('Sending message to "<rpcn-friend>":', log)
        self.assertIn(UNSHAPED, log)
        self.assertIn("sceNp2: npId: <rpcn-user>", log)
        self.assertIn("You are now logged in RPCN(<rpcn-user> | <rpcn-user>)!", log)
        self.assertIn((BINARY_LINE + "\n").encode("utf-8"), self.r1["ARMSX3-report-" + STAMP + "/RPCS3.log"])
        self.assertIn('"data": "~/Documents"', self.r1["ARMSX3-report-" + STAMP + "/session-record.json"].decode("utf-8"))

    def test_r9_a_linked_tty_log_is_not_followed(self):
        self.assertTrue(self.r9, "R9 produced no zip to read")
        for name, data in self.r9.items():
            with self.subTest(name):
                self.assertFalse(name.endswith("/TTY.log"))
                self.assertNotIn(DECOY.encode("utf-8"), data)


class TheUsernameIsRedacted(unittest.TestCase):
    """The RPCN rules on the host, never device evidence."""

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, SAVES, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            support = Path(tmp) / "Library" / "Application Support"
            (support / "rpcs3").mkdir(parents=True)
            (support / "rpcs3" / "rpcn.yml").write_text(RPCN_YML, encoding="utf-8")
            tails = (before + "<rpcn-friend>" + after + (": NotFound" if "error" in after else "")
                     for before, after in FRIEND_SHAPES)
            driver = (RPCN_DRIVER.replace("@@NAME@@", json.dumps(RPCN_NAME)).replace("@@YML@@", json.dumps(RPCN_YML))
                      .replace("@@SUPPORT@@", json.dumps(str(support))).replace("@@HOME@@", json.dumps(HOME))
                      .replace("@@LOG@@", json.dumps("\n".join(RPCN_LINES) + "\n", ensure_ascii=False))
                      .replace("@@DECOYS@@", ", ".join(json.dumps(d) for d in RPCN_DECOYS))
                      .replace("@@FRIEND@@", json.dumps(FRIEND_NAME))
                      .replace("@@TAILS@@", ", ".join(json.dumps(t) for t in tails))
                      .replace("@@ELSEWHERE@@", json.dumps(FRIEND_ELSEWHERE, ensure_ascii=False))
                      .replace("@@SHAPETAILS@@", ", ".join(json.dumps(t) for t in FRIEND_LOG_TAILS))
                      .replace("@@UNSHAPED@@", json.dumps(UNSHAPED, ensure_ascii=False))
                      .replace("@@ONLINE@@", json.dumps(RPCN_ONLINE)))
            out = compile_and_run([SFO, ZIP, SAVES, FACTS, STORE], driver, tmp)
        cls.lines = [line for line in out.splitlines() if line[:4] in ("RPR ", "RPF ", "RPS ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_username_line(self):
        self.assertIn(RPR_LINE, self.lines)

    def test_the_friend_line(self):
        self.assertIn(RPF_LINE, self.lines)

    # The other friend shapes and the own-account pairs.
    def test_the_shapes_line(self):
        self.assertIn(RPS_LINE, self.lines)


MEMORY_DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let files = FileManager.default

// No zip work is measured: the cap loop never runs and only staging and redaction grow the footprint.
struct NullZipper: SaveDataIO {
    func zipDirectory(_ dir: URL, to zip: URL) throws { try Data(count: 1).write(to: zip) }
}

// The figure jetsam judges. TASK_VM_INFO_COUNT is a macro Swift does not import, so the count is computed.
func footprintPeak() -> Int64? {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? info.ledger_phys_footprint_peak : nil
}

let base = tmp.appendingPathComponent("p1", isDirectory: true)
let support = base.appendingPathComponent("Library/Application Support", isDirectory: true)
let documents = base.appendingPathComponent("Documents", isDirectory: true)
let rules = Redaction(home: @@HOME@@, bundle: @@BUNDLE@@, bundleID: @@BUNDLEID@@, rpcnName: @@RPCN@@)
let store = DiagnosticsStore(root: support.appendingPathComponent("armsx3-logs", isDirectory: true),
                             rpcs3Dir: support.appendingPathComponent("rpcs3", isDirectory: true),
                             recordDir: documents.appendingPathComponent("armsx3", isDirectory: true),
                             io: NullZipper(),
                             reportsDir: documents.appendingPathComponent("Reports", isDirectory: true),
                             tmpDir: base.appendingPathComponent("tmp", isDirectory: true),
                             redaction: rules, budget: Int.max)
let session = store.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true)

do {
    // The size comes from attributes; nothing reads the log before the first reading.
    let size = (try files.attributesOfItem(atPath: session.appendingPathComponent("RPCS3.log").path)[.size] as? Int) ?? 0
    guard let before = footprintPeak() else { print("P1 error"); exit(0) }
    let r = try store.buildReport(session: session, form: ReportForm(), settings: ReportSettings(), now: Date())
    guard let after = footprintPeak() else { print("P1 error"); exit(0) }
    let staged = r.files.contains("RPCS3.log") ? 1 : 0
    print("P1 growth_mib=\((after - before) >> 20) limit_mib=\((40 * store.chunk) >> 20) chunk_mib=\(store.chunk >> 20) log_mib=\(size >> 20) staged=\(staged)")
} catch {
    print("P1 error")
}
"""

P1_LINE = re.compile(r"^P1 growth_mib=(\d+) limit_mib=(\d+) chunk_mib=(\d+) log_mib=(\d+) staged=1$")


class TheReportStaysSmall(unittest.TestCase):
    """Building a report over a large log stays within 40 x chunk, on the host."""

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, SAVES, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_memory_fixture(tmp)
            driver = (MEMORY_DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@HOME@@", json.dumps(HOME))
                      .replace("@@BUNDLE@@", json.dumps(BUNDLE)).replace("@@BUNDLEID@@", json.dumps(BUNDLE_ID))
                      .replace("@@RPCN@@", json.dumps(RPCN_NAME)).replace("@@REPORTED@@", REPORTED))
            out = compile_and_run([SFO, ZIP, SAVES, FACTS, STORE], driver, tmp)
        cls.lines = [line for line in out.splitlines() if line.startswith("P1 ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_p1_staging_a_20_mib_log_stays_bounded(self):
        self.assertEqual(1, len(self.lines), self.lines)
        found = P1_LINE.match(self.lines[0])
        self.assertIsNotNone(found, self.lines[0])
        growth, limit, chunk, size = (int(g) for g in found.groups())
        self.assertGreaterEqual(size, 20)
        self.assertEqual(40 * chunk, limit)
        self.assertLessEqual(growth, limit, "building a report grew the peak footprint by {0} MiB, over the {1} MiB limit".format(growth, limit))


# What the summary's lines mean.
MEANING_DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let rules = Redaction(home: @@HOME@@, bundle: @@BUNDLE@@, bundleID: @@BUNDLEID@@)
let now = Date(timeIntervalSince1970: @@NOW@@)

func store(_ name: String) -> DiagnosticsStore {
    let base = tmp.appendingPathComponent(name, isDirectory: true)
    let support = base.appendingPathComponent("Library/Application Support", isDirectory: true)
    let documents = base.appendingPathComponent("Documents", isDirectory: true)
    return DiagnosticsStore(root: support.appendingPathComponent("armsx3-logs", isDirectory: true),
                            rpcs3Dir: support.appendingPathComponent("rpcs3", isDirectory: true),
                            recordDir: documents.appendingPathComponent("armsx3", isDirectory: true),
                            reportsDir: documents.appendingPathComponent("Reports", isDirectory: true),
                            tmpDir: base.appendingPathComponent("tmp", isDirectory: true),
                            redaction: rules, ramBytes: 6_400_000_000)
}

func session(_ s: DiagnosticsStore) -> URL { s.sessionsDir.appendingPathComponent("@@REPORTED@@", isDirectory: true) }
func lines(_ text: String) -> [String] { text.components(separatedBy: "\n") }
func flag(_ ok: Bool) -> Int { ok ? 1 : 0 }
// Lines starting with the given prefixes, one after another.
func consecutive(_ text: String, _ prefixes: [String]) -> Bool {
    let t = lines(text)
    guard let at = t.firstIndex(where: { $0.hasPrefix(prefixes[0]) }), at + prefixes.count <= t.count else { return false }
    return zip(t[at...], prefixes).allSatisfy { $0.hasPrefix($1) }
}
func summary(_ name: String, settings: ReportSettings = ReportSettings(), process: ReportProcess = ReportProcess()) -> String {
    let s = store(name)
    return s.summary(session: session(s), form: ReportForm(), settings: settings, process: process)
}

// U4: the running process's own facts.
do {
    let yes = lines(summary("u4", process: ReportProcess(liveContainer: true, stderrDropped: 42)))
    let no = lines(summary("u4", process: ReportProcess(liveContainer: false, stderrDropped: 0)))
    let unknown = lines(summary("u4"))
    let s = store("u4")
    let r = try s.buildReport(session: session(s), form: ReportForm(), settings: ReportSettings(),
                              process: ReportProcess(liveContainer: true, stderrDropped: 42), now: now)
    let zipped = lines(r.summary).contains("LiveContainer: yes") && lines(r.summary).contains("Console lines dropped, approximate (RPCS3.log is complete): 42")
    let order = consecutive(summary("u4", process: ReportProcess(liveContainer: true, stderrDropped: 42)),
                            ["JIT helper:", "Pairing file:", "JIT test:", "LiveContainer:", "Console lines dropped, approximate (RPCS3.log is complete):", "Last FPS:"])
    print("U4 lcyes=\(flag(yes.contains("LiveContainer: yes"))) lcno=\(flag(no.contains("LiveContainer: no")))"
        + " lcunknown=\(flag(unknown.contains("LiveContainer: unknown")))"
        + " dropped=\(flag(yes.contains("Console lines dropped, approximate (RPCS3.log is complete): 42")))"
        + " unknown=\(flag(unknown.contains("Console lines dropped, approximate (RPCS3.log is complete): unknown (an earlier launch)")))"
        + " zip=\(flag(zipped)) order=\(flag(order))")
} catch {
    print("U4 error")
}

// U5: the pairing file's words, for the running session only.
do {
    let line = "SideStore's (LiveContainer), Remote Pairing, created 12 Oct, accepted"
    let known = lines(summary("u4", process: ReportProcess(pairingFile: line)))
    let none = lines(summary("u4", process: ReportProcess(pairingFile: "none")))
    let unknown = lines(summary("u4"))
    let order = consecutive(summary("u4", process: ReportProcess(pairingFile: line)),
                            ["JIT helper:", "Pairing file:", "JIT test:", "LiveContainer:"])
    let tried = "imported rejected, SideStore's (LiveContainer) accepted"
    let ladder = lines(summary("u4", process: ReportProcess(pairingFile: line, pairingLadder: tried)))
    let ladderOrder = consecutive(summary("u4", process: ReportProcess(pairingFile: line, pairingLadder: tried)),
                                  ["JIT helper:", "Pairing file:", "Pairing ladder:", "JIT test:", "LiveContainer:"])
    let words = (known + none + unknown + ladder).filter { $0.hasPrefix("Pairing") }
    let clean = !words.isEmpty && words.allSatisfy { !$0.contains("/") && !$0.contains(".plist") }
    print("U5 line=\(flag(known.contains("Pairing file: " + line))) none=\(flag(none.contains("Pairing file: none")))"
        + " unknown=\(flag(unknown.contains("Pairing file: unknown (an earlier launch)")))"
        + " order=\(flag(order)) clean=\(flag(clean))"
        + " ladder=\(flag(ladder.contains("Pairing ladder: " + tried))) ladderorder=\(flag(ladderOrder))"
        + " noladder=\(flag(!(known + none + unknown).contains { $0.hasPrefix("Pairing ladder:") }))")
}

// The lines strictly between the line `from` and the line `to` (the end when nil), empty lines left out.
func between(_ text: String, _ from: String, _ to: String?) -> [String] {
    let t = lines(text)
    guard let a = t.firstIndex(of: from) else { return ["<no \(from)>"] }
    let rest = t[(a + 1)...]
    let b = to.flatMap { rest.firstIndex(of: $0) } ?? rest.endIndex
    return rest[..<b].filter { !$0.isEmpty }
}
let allHeader = "Settings changed from the defaults, all games:"
let seedHeader = "App defaults (set by ARMSX3 on first launch):"
let gameHeader = "Settings changed from the defaults, this game:"

// U1: the port's own seeds apart from the player's changes.
let u1 = summary("u1", settings: ReportSettings(allGames: @@U1ALL@@))
let u1seeded = summary("u1", settings: ReportSettings(allGames: @@U1SEEDED@@))
let u1noseed = summary("u1", settings: ReportSettings(allGames: "Core:\n  PPU Decoder: Interpreter\n"))
let u1game = summary("u1", settings: ReportSettings(thisGame: "Video:\n  Shader Mode: Async Recompiler (multi-threaded)\n"))
let u1changed: [String] = @@U1CHANGED@@
let u1defaults: [String] = @@U1DEFAULTS@@
let u1seededLines: [String] = @@U1SEEDEDLINES@@
let changed = between(u1, allHeader, seedHeader) == u1changed
let defaults = between(u1, seedHeader, gameHeader) == u1defaults
let allSeeded = between(u1seeded, allHeader, seedHeader) == ["(none)"] && between(u1seeded, seedHeader, gameHeader) == u1seededLines
let noSeed = between(u1noseed, allHeader, seedHeader) == ["Core:", "  PPU Decoder: Interpreter"]
    && between(u1noseed, seedHeader, gameHeader) == ["(none)"]
let thisGame = between(u1game, gameHeader, nil) == ["Video:", "  Shader Mode: Async Recompiler (multi-threaded)"]
print("U1 changed=\(flag(changed)) defaults=\(flag(defaults)) allseeded=\(flag(allSeeded)) noseed=\(flag(noSeed))"
    + " thisgame=\(flag(thisGame))")

// U2: Last error skips harmless lines.
func lastError(_ name: String) -> String { lines(summary(name)).first { $0.hasPrefix("Last error: ") } ?? "<none>" }
print("U2 real=\(flag(lastError("u2real") == "Last error: " + @@U2REAL@@))"
    + " harmless=\(flag(lastError("u2harmless") == "Last error: none (only harmless errors)"))"
    + " hle=\(flag(lastError("u2hle") == "Last error: " + @@U2HLE@@))"
    + " enomem=\(flag(lastError("u2enomem") == "Last error: " + @@U2ENOMEM@@))"
    + " fatal=\(flag(lastError("u2fatal") == "Last error: " + @@U2FATAL@@))"
    + " none=\(flag(lastError("u2none") == "Last error: none"))")

// U3: the JIT route from launch.json.
func route(_ name: String) -> String { lines(summary(name)).first { $0.hasPrefix("JIT route: ") } ?? "<none>" }
let u3order = consecutive(summary("u3builtin"), ["Device:", "JIT route:", "JIT helper:", "Pairing file:", "JIT test:", "LiveContainer:",
                                                "Console lines dropped, approximate (RPCS3.log is complete):", "Last FPS:"])
// U7: the running session's LiveContainer detail wins over the yes/no/unknown line; an earlier session keeps that line.
do {
    let details = ["yes, 3.8.13, Helper Process Support on", "yes, 3.8.13, Helper Process Support off",
                   "yes, 3.8.13, Helper Process Support unknown", "yes, version unknown, Helper Process Support on", "no"]
    let named = details.allSatisfy { detail in
        let out = summary("u4", process: ReportProcess(liveContainer: true, liveContainerDetail: detail))
        return lines(out).contains("LiveContainer: " + detail) && consecutive(out, ["Pairing file:", "JIT test:", "LiveContainer:", "Console lines dropped"])
    }
    let earlier = lines(summary("u4", process: ReportProcess(liveContainer: true))).contains("LiveContainer: yes")
        && lines(summary("u4", process: ReportProcess(liveContainer: false))).contains("LiveContainer: no")
        && lines(summary("u4")).contains("LiveContainer: unknown")
    print("U7 details=\(flag(named)) earlier=\(flag(earlier))")
}

// U8: the running session's JIT test word, or "not run" for an earlier session or no test.
do {
    let words = ["passed", "stopped at helper (start-failed)", "stopped at LocalDevVPN (connect-failed)", "stopped at pairing (rejected)",
                 "stopped at disk image"]
    let named = words.allSatisfy { word in
        let out = summary("u4", process: ReportProcess(pairingFile: "none", jitTest: word))
        return lines(out).contains("JIT test: " + word) && consecutive(out, ["Pairing file:", "JIT test:", "LiveContainer:"])
    }
    let ladder = consecutive(summary("u4", process: ReportProcess(pairingFile: "none", pairingLadder: "a rejected, b accepted", jitTest: "passed")),
                             ["Pairing file:", "Pairing ladder:", "JIT test:", "LiveContainer:"])
    let notRun = lines(summary("u4")).contains("JIT test: not run")
        && lines(summary("u4", process: ReportProcess(liveContainer: true))).contains("JIT test: not run")
    print("U8 words=\(flag(named)) ladder=\(flag(ladder)) notrun=\(flag(notRun))")
}

// U6: the running session's route words win over the launch record's; an earlier session keeps the record's.
do {
    let words = ["built-in helper", "built-in helper (other install)", "StikDebug fallback (helper-setting-off)",
                 "opened through StikDebug", "Wait for Debugger", "none (no-dual-map)"]
    let named = words.allSatisfy { word in
        let out = summary("u3stikdebug", process: ReportProcess(jitRoute: word))
        return lines(out).contains("JIT route: " + word) && consecutive(out, ["Device:", "JIT route:", "JIT helper:"])
    }
    let earlier = route("u3stikdebug") == "JIT route: StikDebug" && route("u3builtin") == "JIT route: built-in helper"
    print("U6 words=\(flag(named)) earlier=\(flag(earlier))")
}
print("U3 builtin=\(flag(route("u3builtin") == "JIT route: built-in helper"))"
    + " stikdebug=\(flag(route("u3stikdebug") == "JIT route: StikDebug"))"
    + " off=\(flag(route("u3off") == "JIT route: none (no-dual-map)"))"
    + " other=\(flag(route("u3other") == "JIT route: other (some-new-word)"))"
    + " missing=\(flag(route("u3missing") == "JIT route: unknown"))"
    + " order=\(flag(u3order))")
"""

EXPECTED_MEANING = {
    "U1": "U1 changed=1 defaults=1 allseeded=1 noseed=1 thisgame=1",
    "U2": "U2 real=1 harmless=1 hle=1 enomem=1 fatal=1 none=1",
    "U3": "U3 builtin=1 stikdebug=1 off=1 other=1 missing=1 order=1",
    "U4": "U4 lcyes=1 lcno=1 lcunknown=1 dropped=1 unknown=1 zip=1 order=1",
    "U5": "U5 line=1 none=1 unknown=1 order=1 clean=1 ladder=1 ladderorder=1 noladder=1",
    "U6": "U6 words=1 earlier=1",
    "U7": "U7 details=1 earlier=1",
    "U8": "U8 words=1 ladder=1 notrun=1",
}


# U1: seeds with and without the tester's own changes, and an export of the seeds alone.
U1_ALL = ["Video:", "  Shader Mode: Async Recompiler (multi-threaded)", "  Resolution Scale: 150", "  Vulkan:",
          "    Asynchronous Texture Streaming 2: true", "Audio:", "  Microphone Type: Standard",
          "  Microphone Devices: CoreAudio Default@@@@@@@@@", "Input/Output:", '  Keyboard: "Basic"', "  Camera: Null",
          "  Camera type: PS Eye", "Net:", "  Internet enabled: Connected", "  PSN status: RPCN"]
U1_CHANGED = ["Video:", "  Resolution Scale: 150", "  Vulkan:", "    Asynchronous Texture Streaming 2: true",
              "Input/Output:", "  Camera: Null", "Net:", "  Internet enabled: Connected", "  PSN status: RPCN"]
U1_DEFAULTS = ["Video:", "  Shader Mode: Async Recompiler (multi-threaded)", "Audio:", "  Microphone Type: Standard",
               "  Microphone Devices: CoreAudio Default@@@@@@@@@", "Input/Output:", '  Keyboard: "Basic"',
               "  Camera type: PS Eye"]
U1_SEEDED = ["Video:", "  Shader Mode: Async Recompiler (multi-threaded)", "Audio:", "  Microphone Type: Standard",
             "  Microphone Devices: CoreAudio Default@@@@@@@@@", "Input/Output:", "  Keyboard: Basic", "  Camera: Qt",
             "  Camera type: PS Eye"]


def error_line(t, text, level="E"):
    """A log line with the tester logs' head: level mark, time, thread."""
    return MID + "{0} 0:00:{1:02d}.000000 {{PPU[0x1000000] main_thread}} {2}".format(level, t, text)


U2_RSX = "sys_rsx: RSX queue appears to be stuck. We have been waiting for 1001ms already. Aborting wait..."
U2_HARMLESS = ("SYS: 'sys_mutex_unlock' failed with 0x80010009 : CELL_EPERM [1]",
               "SYS: Stat: '_sys_lwmutex_lock' failed with 0x80010005 : CELL_ESRCH, Invalid ID [x1288]",
               "SYS: 'sys_mutex_lock' failed with 0x80010008 : CELL_EDEADLK [1]",
               "sys_fs: 'sys_fs_open' failed with 0x80010006 : CELL_ENOENT, \u201c/dev_bdvd/PS3_GAME/USRDIR/x.dat\u201d [1]",
               "SYS: 'sys_event_port_disconnect' failed with 0x8001002e : CELL_ENOTCONN [1]",
               "SYS: '_sys_prx_get_module_id_by_name' failed with 0x8001112e : CELL_PRX_ERROR_UNKNOWN_MODULE [1]",
               "RSX: Image resource file `rpcs3/Icons/ui/home/32/power-off-solid.png' could not be opened (Not found)",
               "RSX: Image resource file `Icons/ui/home/32/power-off-solid.png' could not be opened (Not found)")
U2_HLE = "SYS: 'cellAudioInit' failed with 0x80310701 : CELL_AUDIO_ERROR_ALREADY_INIT [1]"
U2_ENOMEM = "SYS: 'sys_memory_allocate' failed with 0x80010004 : CELL_ENOMEM [1]"


def u2_harmless(start):
    return [error_line(start + n, text) for n, text in enumerate(U2_HARMLESS)]


U2_LOGS = {
    "u2real": [boot(1), perf(2, "30.0"), error_line(3, U2_RSX)] + u2_harmless(4) + [error_line(20, "SYS: a warning", "W")],
    "u2harmless": [boot(1), perf(2, "30.0")] + u2_harmless(4),
    "u2hle": [boot(1), perf(2, "30.0")] + u2_harmless(4) + [error_line(20, U2_HLE)],
    "u2enomem": [boot(1), perf(2, "30.0")] + u2_harmless(4) + [error_line(20, U2_ENOMEM)],
    "u2fatal": [boot(1), perf(2, "30.0"), error_line(3, "SYS: the fatal line", "F")] + u2_harmless(4),
    "u2none": [boot(1), perf(2, "30.0"), error_line(3, "SYS: a warning", "W")],
}

# U3: launch.json's JIT words as launch_record.m writes them; None is a session without launch.json.
U3_LAUNCH = {"u3builtin": ("on", "helper-ok"), "u3stikdebug": ("on", "stikdebug-handshake"),
             "u3off": ("off", "no-dual-map"), "u3other": ("on", "some-new-word"), "u3missing": None}


def write_meaning_fixtures(tmp):
    """One container per case, each a `report_session`."""
    report_session(tmp, "u4", text_bytes([boot(1), serial_line(2, "ABCD12345"), perf(3, "30.0")]))
    report_session(tmp, "u1", text_bytes([boot(1), perf(2, "30.0")]))
    for name, log in U2_LOGS.items():
        report_session(tmp, name, text_bytes(log))
    for name, words in U3_LAUNCH.items():
        dirs = report_session(tmp, name, text_bytes([boot(1), perf(2, "30.0")]))
        launch = dirs["sessions"] / REPORTED / "launch.json"
        if words is None:
            launch.unlink()
        else:
            launch.write_text(json.dumps({"schema": "armsx3-launch-record/1", "machine": "iPhone-synthetic",
                                          "os_build": "23A000", "helper_outcome": "finished", "jit_state": words[0],
                                          "jit_reason": words[1]}), encoding="utf-8")


def swift_string(text):
    """`text` as a Swift string literal (JSON's escapes are Swift's for these texts)."""
    return json.dumps(text, ensure_ascii=False)


class TheSummarySaysWhatItMeans(unittest.TestCase):
    """The summary's lines say what they mean, on the host."""

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, SAVES, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_meaning_fixtures(tmp)
            driver = (MEANING_DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@HOME@@", json.dumps(HOME))
                      .replace("@@BUNDLE@@", json.dumps(BUNDLE)).replace("@@BUNDLEID@@", json.dumps(BUNDLE_ID))
                      .replace("@@NOW@@", str(REPORT_NOW)).replace("@@REPORTED@@", REPORTED)
                      # The texts last: the seed values hold runs of `@`.
                      .replace("@@U1ALL@@", swift_string("\n".join(U1_ALL) + "\n"))
                      .replace("@@U1SEEDED@@", swift_string("\n".join(U1_SEEDED) + "\n"))
                      .replace("@@U1CHANGED@@", json.dumps(U1_CHANGED, ensure_ascii=False))
                      .replace("@@U1DEFAULTS@@", json.dumps(U1_DEFAULTS, ensure_ascii=False))
                      .replace("@@U1SEEDEDLINES@@", json.dumps(U1_SEEDED, ensure_ascii=False))
                      .replace("@@U2REAL@@", swift_string(U2_LOGS["u2real"][2]))
                      .replace("@@U2HLE@@", swift_string(U2_LOGS["u2hle"][-1]))
                      .replace("@@U2ENOMEM@@", swift_string(U2_LOGS["u2enomem"][-1]))
                      .replace("@@U2FATAL@@", swift_string(U2_LOGS["u2fatal"][2])))
            out = compile_and_run([SFO, ZIP, SAVES, FACTS, STORE], driver, tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_cases(self):
        for key, expected in EXPECTED_MEANING.items():
            with self.subTest(key):
                self.assertEqual(expected, self.lines.get(key), key)


class TheReportFilesAreClean(unittest.TestCase):
    # Every problem printed, so a red run names each one.
    maxDiff = None

    def test_real_files_have_no_problems(self):
        self.assertEqual([], problems_of(real_inputs()))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.raw = real_inputs()

    def edited(self, name, old, new):
        text = self.raw[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        raw = dict(self.raw)
        raw[name] = mutated
        return raw

    def assertFlagged(self, raw, prefix):
        problems = problems_of(raw)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "import Foundation", "import Foundation\nimport UIKit"),
                           "host")

    def test_mutation_log_calls_nslog(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "import Foundation",
                                       "import Foundation\nfunc probe(_ s: String) { NSLog(s) }"), "log")

    def test_mutation_network_names_a_url(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "import Foundation",
                                       "import Foundation\nlet probe = \"https://example.invalid\""), "network")

    def test_mutation_pairing_names_the_folder(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", '"armsx3-logs"', '"StikJIT"'), "pairing")

    def test_mutation_hidden_under_documents(self):
        live = body_of(self.raw["DiagnosticsStore.swift"], r"static\s+func\s+live\s*\(")
        self.assertIsNotNone(live)
        self.assertFlagged(self.edited("DiagnosticsStore.swift", live,
                                       live.replace(".applicationSupportDirectory", ".documentDirectory")), "hidden")

    def test_mutation_order_log_after_prepare(self):
        start = body_of(self.raw["ARMSX3FrontendRoot.swift"], r"func\s+start\s*\(\s*\)\s*async")
        self.assertIsNotNone(start)
        # The log's start and the JIT check trade places: the start now runs after the check.
        moved = (start.replace("startDiagnostics(inDirectory:", "@@SWAP@@", 1)
                 .replace("checkJIT", "startDiagnostics(inDirectory:", 1).replace("@@SWAP@@", "checkJIT", 1))
        self.assertFlagged(self.edited("ARMSX3FrontendRoot.swift", start, moved), "order")

    def test_mutation_marks_no_in_game_mark(self):
        self.assertFlagged(self.edited("GameScreenView.swift",
                                       "DiagnosticsSession.shared.mark(.inGame, serial: game.serial, title: game.title)",
                                       "_ = game.serial"), "marks")

    def test_mutation_copies_a_file(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "import Foundation",
                                       "import Foundation\nfunc probe(_ a: URL, _ b: URL) throws {"
                                       " try FileManager.default.copyItem(at: a, to: b) }"), "copies")

    def test_mutation_identity_reads_the_vendor_id(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "import Foundation",
                                       "import Foundation\nlet probe = UIDevice.current"), "identity")

    def test_mutation_share_an_onappear_copies(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", ".interactiveDismissDisabled(working)",
                                       ".interactiveDismissDisabled(working)\n        .onAppear { UIPasteboard.general.string = \"\" }"),
                           "share")

    def test_mutation_share_a_second_pasteboard_in_the_library(self):
        for path, line in CLIPBOARD_USES.items():
            with self.subTest(path=path):
                swift = frontend_swift()
                self.assertIn(line, swift[path])
                swift[path] = swift[path].replace(line, line + "\n            " + line, 1)
                problems = problems_of(self.raw, swift)
                self.assertTrue(any(p.startswith("share:") for p in problems), problems)

    def test_mutation_offmain_the_build_on_the_main_actor(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "Task.detached(priority: .userInitiated)",
                                       "Task(priority: .userInitiated)"), "offmain")

    def test_mutation_header_the_spdx_line_moved(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "// SPDX-License-Identifier: GPL-2.0-only\n",
                                       "//\n// SPDX-License-Identifier: GPL-2.0-only\n"), "header")

    def test_mutation_order_crash_diagnostics_after_prepare(self):
        start = body_of(self.raw["ARMSX3FrontendRoot.swift"], r"func\s+start\s*\(\s*\)\s*async")
        self.assertIsNotNone(start)
        # The subscriber's start and prepare trade places: the subscriber now starts after prepare.
        moved = (start.replace("CrashDiagnostics.shared.start()", "@@SWAP@@", 1)
                 .replace("Rpcs3Bridge.prepare()", "CrashDiagnostics.shared.start()", 1)
                 .replace("@@SWAP@@", "Rpcs3Bridge.prepare()", 1))
        self.assertFlagged(self.edited("ARMSX3FrontendRoot.swift", start, moved), "order")

    def test_mutation_metrickit_daily_metrics_handled(self):
        self.assertFlagged(self.edited("CrashDiagnostics.swift", "    func didReceive(_ payloads: [MXDiagnosticPayload]) {",
                                       "    func didReceive(_ payloads: [MXMetricPayload]) {}\n"
                                       "    func didReceive(_ payloads: [MXDiagnosticPayload]) {"), "metrickit")

    def test_mutation_log_crash_diagnostics_prints(self):
        self.assertFlagged(self.edited("CrashDiagnostics.swift", "import MetricKit",
                                       "import MetricKit\nfunc probe() { print(\"payload\") }"), "log")

    def test_mutation_network_crash_diagnostics_posts(self):
        self.assertFlagged(self.edited("CrashDiagnostics.swift", "import MetricKit",
                                       "import MetricKit\nlet probe = URLSession.shared"), "network")

    def test_mutation_pairing_crash_diagnostics_names_the_file(self):
        self.assertFlagged(self.edited("CrashDiagnostics.swift", "import MetricKit",
                                       "import MetricKit\nlet probe = \"pairingFile.plist\""), "pairing")

    def test_mutation_walkthrough_settings_path_dropped(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "Analytics Data.", "Analytics."), "walkthrough")

    def test_mutation_picker_a_picker_of_its_own(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "FirmwarePicker(types:",
                                       "CrashFilePicker(types:"), "picker")

    def test_mutation_verbose_switch_not_applied(self):
        self.assertFlagged(self.edited("DiagnosticsPane.swift", "Rpcs3Bridge.setVerboseLogging(",
                                       "Rpcs3Bridge.ignoreVerboseLogging("), "verbose")

    def test_mutation_scene_background_not_marked(self):
        self.assertFlagged(self.edited("ARMSX3FrontendRoot.swift", "DiagnosticsSession.shared.scene(active: false)", "_ = ()"),
                           "scene")

    def test_mutation_pane_not_in_manage(self):
        self.assertFlagged(self.edited("SettingsRootView.swift", ".jitDevice, .diagnostics, .about", ".jitDevice, .about"),
                           "pane")

    def test_mutation_pane_opens_no_report(self):
        self.assertFlagged(self.edited("DiagnosticsPane.swift", "ProblemReportView(", "ProblemReportPreview("), "pane")

    def test_mutation_pane_rows_without_chevron(self):
        self.assertFlagged(self.edited("DiagnosticsPane.swift", "showsChevron: true", "showsChevron: false"), "pane")

    def test_mutation_pane_caption_reworded(self):
        raw = self.edited("DiagnosticsPane.swift", "A report opens for it.", "A report opens.")
        self.assertFlagged(raw, "pane")

    def test_mutation_corelines_swift_needle_renamed(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", '"SYS: Stopping emulator..."',
                                       '"SYS: Stopping emulation..."'), "corelines")

    def test_mutation_corelines_core_reworded(self):
        self.assertFlagged(self.edited("System.cpp", 'sys_log.success("Emulation has been resumed!")',
                                       'sys_log.success("Emulation resumed!")'), "corelines")

    def test_mutation_image_swift_pattern_changed(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", "[0-9A-F]{8}", "[0-9a-f]{8}"), "image")

    def test_mutation_image_core_prefix_renamed(self):
        self.assertFlagged(self.edited("diag_log.cpp", '.text = "IOSDIAG: image "', '.text = "IOSDIAG: img "'), "image")

    def test_mutation_order_no_launch_record(self):
        self.assertFlagged(self.edited("ARMSX3FrontendRoot.swift", "DiagnosticsSession.shared.recordLaunch()", "_ = ()"),
                           "order")

    # An upstream rewording of a friend line turns `friends` red.
    def test_mutation_friends_core_reworded(self):
        self.assertFlagged(self.edited("rpcn_client.cpp", 'add_friend(\\"%s\\") succeeded', 'add_friend(\\"%s\\") worked'),
                           "friends")

    # An upstream rewording of the sign-in pair turns `ownpair` red.
    def test_mutation_ownpair_core_reworded(self):
        self.assertFlagged(self.edited("rpcn_client.cpp", "You are now logged in RPCN(%s | %s)!",
                                       "You are now logged in RPCN(%s / %s)!"), "ownpair")

    # One mutation per friend shape, rewording every call of its first core line, since some are logged twice.
    def test_mutation_friendshapes_core_reworded(self):
        for before, _after, core, _message in FRIEND_LOG_SHAPES:
            name, channel, text = core[0]
            with self.subTest(before):
                call = re.compile(r'(' + re.escape(channel) + r'\.\w+\(\s*"' + re.escape(text) + r')"')
                mutated, count = call.subn(r'\1 (renamed)"', self.raw[name])
                self.assertGreater(count, 0)
                raw = dict(self.raw)
                raw[name] = mutated
                self.assertFlagged(raw, "friendshapes")

    # The Swift mirror drifts from k_seed_defaults.
    def test_mutation_seeds_mirror_drifts(self):
        self.assertFlagged(self.edited("DiagnosticsStore.swift", '("Input/Output@@Keyboard", "Basic")',
                                       '("Input/Output@@Keyboard", "Basic2")'), "seeds")

    # An upstream rewording of the icon miss turns `harmless` red.
    def test_mutation_harmless_core_reworded(self):
        self.assertFlagged(self.edited("overlay_controls.cpp", "could not be opened (%s)", "could not be loaded (%s)"),
                           "harmless")

    def test_mutation_process_drops_the_ladder_line(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "PairingSource.ladderLine(", "PairingSource.other("), "process")

    def test_mutation_process_drops_the_pairing_line(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "PairingSource.reportLine(", "PairingSource.other("), "process")

    def test_mutation_process_drops_the_other_install_route(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", '"built-in helper (other install)"', '"built-in helper (other)"'),
                           "process")

    def test_mutation_process_drops_the_fallback_route(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", '"StikDebug fallback (', '"StikDebug (fallback '), "process")

    def test_mutation_process_gives_an_earlier_session_the_route(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "running ? jitRoute() : nil", "jitRoute()"), "process")

    def test_mutation_process_drops_the_hooks_state(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "Helper Process Support \\(hooks)", "Helper Processes \\(hooks)"),
                           "process")

    def test_mutation_process_gives_an_earlier_session_the_detail(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "running ? liveContainerDetail() : nil",
                                       "liveContainerDetail()"), "process")

    def test_mutation_process_names_the_bundle_id(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", '"yes, \\(probe.version ?? "version unknown"), ',
                                       '"yes, \\(probe.bundleID ?? ""), '), "process")

    def test_mutation_process_gives_an_earlier_session_the_test(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "running ? JitDeviceModel.shared.testResult?.reportWord : nil",
                                       "JitDeviceModel.shared.testResult?.reportWord"), "process")

    # The sheet's build drops the process facts.
    def test_mutation_process_build_without_process(self):
        self.assertFlagged(self.edited("ProblemReportView.swift", "settings: settings, process: process, attachments:",
                                       "settings: settings, attachments:"), "process")


if __name__ == "__main__":
    unittest.main()
