#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""BackupArchive.swift, run on the Mac host against built fixtures, stages only allow-listed files, zips and restores
them byte for byte, refuses unsafe or foreign entries before any write, and keeps the RPCN login out both ways.
Static checks cover its imports, logging, allow-list and callers; it is not a device run."""

import datetime
import json
import plistlib
import re
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SFO = APP / "Library" / "ParamSfo.swift"
ZIP = APP / "Install" / "ZipArchive.swift"
STORE = APP / "Saves" / "SaveDataStore.swift"
STATES = APP / "Saves" / "SaveStateFiles.swift"
BACKUP = APP / "Saves" / "BackupArchive.swift"
VIEW = APP / "Saves" / "BackupView.swift"
SHADERS = APP / "Settings" / "ShaderPacks.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
DENIED = re.compile(r"(?i)dev_flash|dev_hdd1|game/|games\.yml|\blogs\b|stikjit|pairing|folders\.plist|library-cache|"
                    r"install-pending|rpcs3-config-db")

ZIPPER = r"""
import os, struct, sys, zipfile
src, out, zip64 = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
top = os.path.basename(src.rstrip("/"))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for where, _, names in sorted(os.walk(src)):
        for name in sorted(names):
            path = os.path.join(where, name)
            archive.write(path, top + "/" + os.path.relpath(path, src))
if zip64:
    data = bytearray(open(out, "rb").read())
    eocd = data.rfind(b"PK\x05\x06")
    struct.pack_into("<HH", data, eocd + 8, 0xFFFF, 0xFFFF)
    open(out, "wb").write(bytes(data))
"""

FIXTURE = {
    "config/config.yml": b"Core: {}\n",
    "config/patch_config.yml": b"patches: {}\n",
    "config/games.yml": b"ABCD12345: /somewhere/\n",
    "config/custom_configs/config_ABCD12345.yml": b"Video: {}\n",
    "config/patches/imported_patch.yml": b"Version: 1.2\n",
    "config/savestates/ABCD12345/s.SAVESTAT": b"state" * 100,
    "config/input_configs/global/Default.yml": b"Player 1 Input: {}\n",
    "config/dev_flash/sys/x": b"firmware",
    "config/dev_hdd1/caches/x": b"cache",
    "config/logs/x": b"log",
    "hdd0/home/00000001/savedata/ABCD12345-DATA/PARAM.SFO": b"PSF\x00sfo",
    "hdd0/game/ABCD12345/x": b"game",
    "app/presets/index.json": b"[]",
    "app/skins/s1/ic_controller_cross_button.png": b"\x89PNG skin",
    "app/playtime.json": b'{"version":1,"titles":{}}',
    "app/covers/ABCD12345.jpg": b"jpg",
    "app/shaders/p/x.slangp": b"shaders = 0",
    "app/library-cache.json": b"{}",
    "app/folders.plist": b"bookmarks",
    "app/rpcs3-config-db.json": b"{}",
    "app/install-pending.json": b"{}",
}

STAGED = sorted([
    "armsx3-backup.json",
    "files/armsx3/playtime.json",
    "files/armsx3/presets/index.json",
    "files/armsx3/skins/s1/ic_controller_cross_button.png",
    "files/config/config.yml",
    "files/config/custom_configs/config_ABCD12345.yml",
    "files/config/dev_hdd0/home/00000001/savedata/ABCD12345-DATA/PARAM.SFO",
    "files/config/input_configs/global/Default.yml",
    "files/config/patch_config.yml",
    "files/config/patches/imported_patch.yml",
    "files/config/savestates/ABCD12345/s.SAVESTAT",
    "prefs/armsx3.plist",
])

MANIFEST = json.dumps({"schemaVersion": 1, "package": "test", "versionName": "1"}).encode()
TOP = "ARMSX3-Backup/"
VALID = TOP + "files/config/config.yml"


def hostile_zip(target, entries, manifest=MANIFEST, top=TOP, link=None):
    """A zip with a manifest under `top` (None: no manifest), one valid file, then `entries` as given."""
    with zipfile.ZipFile(target, "w") as archive:
        if manifest is not None:
            archive.writestr(top + "armsx3-backup.json", manifest)
        archive.writestr(top + "files/config/config.yml", b"valid")
        for name, data in entries:
            archive.writestr(zipfile.ZipInfo(name), data)
        if link is not None:
            info = zipfile.ZipInfo(link)
            info.external_attr = 0o120777 << 16
            archive.writestr(info, b"/etc")


CORRUPT = TOP + "files/config/dev_hdd0/home/00000001/savedata/ABCD12345-DATA/DATA.BIN"
# The core's rpcn.yml with synthetic login values.
RPCN_YML = b'Version: 2\nNPID: probeuser\nPassword: "probe-password"\nToken: "probe-token"\n'
# The Keychain calls the backup code must never make (Security.framework's SecItem family).
KEYCHAIN = re.compile(r"\bSecItem\w*\s*\(")


def corrupt_zip(target):
    """A manifest and a valid config.yml, then a DEFLATED save entry whose stream does not decode (bit rot)."""
    with zipfile.ZipFile(target, "w") as archive:
        archive.writestr(TOP + "armsx3-backup.json", MANIFEST)
        archive.writestr(TOP + "files/config/config.yml", b"valid")
        archive.writestr(CORRUPT, b"restored save " * 64, compress_type=zipfile.ZIP_DEFLATED)
        info = archive.getinfo(CORRUPT)
    data = bytearray(Path(target).read_bytes())
    name_length, extra_length = struct.unpack_from("<HH", data, info.header_offset + 26)
    # BFINAL 1, BTYPE 11: a reserved block type, which no inflater accepts.
    data[info.header_offset + 30 + name_length + extra_length] = 0xFF
    Path(target).write_bytes(bytes(data))


# A PS-menu state dated between the backup's first-launch date and the reinstall's.
EPOCH_STATE = TOP + "files/config/savestates/ABCD12345/ABCD12345_1.SAVESTAT"


def epoch_zip(target, since):
    """A backup holding one PS-menu state dated 1 March and a preference file whose epoch2Since is `since`."""
    with zipfile.ZipFile(target, "w") as archive:
        archive.writestr(TOP + "armsx3-backup.json", MANIFEST)
        archive.writestr(zipfile.ZipInfo(EPOCH_STATE, date_time=(2026, 3, 1, 12, 0, 0)), b"state" * 10)
        archive.writestr(TOP + "prefs/armsx3.plist", plistlib.dumps({"armsx3.savestate.epoch2Since": since}))


def write_fixtures(tmp):
    root = Path(tmp)
    for name, data in FIXTURE.items():
        path = root / "src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    (root / "zipper.py").write_text(ZIPPER, encoding="utf-8")
    hz = root / "hz"
    hz.mkdir()
    hostile_zip(hz / "h1.zip", [(TOP + "files/config/../../x", b"evil")])
    hostile_zip(hz / "h2.zip", [("/abs/x", b"evil")])
    hostile_zip(hz / "h3.zip", [], link=TOP + "files/config/custom_configs/link")
    hostile_zip(hz / "h4.zip", [(TOP + "files/config/custom_configs/ ../x", b"evil")])
    hostile_zip(hz / "h5.zip", [(TOP + "files/config/dev_flash/sys/x", b"f"),
                                (TOP + "files/config/dev_hdd0/game/ABCD12345/x", b"g"),
                                (TOP + "files/config/games.yml", b"y"),
                                ("other/x", b"o"),
                                (TOP + "prefs/ARMSX2.xml", b"<map/>"),
                                (TOP + "files/armsx3-settings.json", b"{}")])
    hostile_zip(hz / "h6.zip", [], manifest=None)
    hostile_zip(hz / "h7.zip", [], manifest=json.dumps({"schemaVersion": 2}).encode())
    hostile_zip(hz / "h8.zip", [("files/config/dev_hdd0/home/00000001/savedata/ABCD12345-A/DATA.BIN", b"android")],
                top="")
    hostile_zip(hz / "h9.zip", [(TOP + "prefs/armsx3.plist",
                                 plistlib.dumps({"armsx3.settings.backgroundDim": 0.25, "other.key": "x"}))])
    corrupt_zip(hz / "h12.zip")
    hostile_zip(hz / "h13.zip", [(TOP + "prefs/armsx3.plist", plistlib.dumps({
        "armsx3.settings.backgroundDim": 0.5, "armsx3.skins.active": "s1",
        "armsx3.autorun.armed": True, "armsx3.autorun.armed_at": 9e9, "armsx3.autorun.hook.ARMSX3_X": "1",
        "armsx3.settings.backgroundPrimaryAsset": b"asset", "armsx3.shaders.activePreset": "../skins/s1/p.slangp"}))])
    epoch_zip(hz / "h14.zip", datetime.datetime(2026, 1, 1))
    epoch_zip(hz / "h14later.zip", datetime.datetime(2026, 1, 1))
    epoch_zip(hz / "h14bad.zip", "2026-01-01")
    # An archive carrying the core's rpcn.yml, with synthetic login values.
    hostile_zip(hz / "h15.zip", [(TOP + "files/config/rpcn.yml", RPCN_YML)])


DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let files = FileManager.default
func roots(_ base: String) -> BackupArchive.Roots {
    let b = tmp.appendingPathComponent(base)
    return BackupArchive.Roots(config: b.appendingPathComponent("config"), hdd0: b.appendingPathComponent("hdd0"),
                               app: b.appendingPathComponent("app"))
}
func listing(_ dir: URL) -> [String] {
    var out: [String] = []
    let base = dir.standardizedFileURL.resolvingSymlinksInPath().path
    if let walk = files.enumerator(at: dir, includingPropertiesForKeys: [.isRegularFileKey]) {
        for case let url as URL in walk where (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true {
            out.append(String(url.standardizedFileURL.resolvingSymlinksInPath().path.dropFirst(base.count + 1)))
        }
    }
    return out.sorted()
}
func count(_ r: BackupArchive.Roots) -> Int { listing(r.config).count + listing(r.hdd0).count + listing(r.app).count }
func suite(_ name: String) -> UserDefaults {
    let full = "armsx3-backup-test-\(ProcessInfo.processInfo.processIdentifier)-\(name)"
    UserDefaults().removePersistentDomain(forName: full)
    return UserDefaults(suiteName: full)!
}

struct PythonZip: SaveDataIO {
    var zip64 = false
    func zipDirectory(_ dir: URL, to zip: URL) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        p.arguments = ["-I", tmp.appendingPathComponent("zipper.py").path, dir.path, zip.path, zip64 ? "1" : "0"]
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw CocoaError(.fileWriteUnknown) }
    }
}
func word(_ error: Error) -> String { String(describing: error) }

let src = roots("src")
let defaults = suite("src")
defaults.set(0.4, forKey: "armsx3.settings.backgroundDim")
defaults.set("x", forKey: "other.key")

// B1, B2
let stage = tmp.appendingPathComponent("stage")
do {
    try BackupArchive.stage(roots: src, defaults: defaults, includeSavestates: true, into: stage)
    let top = stage.appendingPathComponent(BackupArchive.topFolder)
    print("B1 " + listing(top).joined(separator: ","))
    let manifest = (try? JSONSerialization.jsonObject(with: Data(contentsOf: top.appendingPathComponent("armsx3-backup.json")))) as? [String: Any] ?? [:]
    let prefs = (try? PropertyListSerialization.propertyList(from: Data(contentsOf: top.appendingPathComponent("prefs/armsx3.plist")), format: nil)) as? [String: Any] ?? [:]
    print("B2 keys=\(manifest.keys.sorted().joined(separator: ",")) schema=\(manifest["schemaVersion"] as? Int ?? -1) platform=\(manifest["platform"] as? String ?? "-") prefs=\(prefs.keys.sorted().joined(separator: ","))")
} catch { print("B1 error=\(word(error))") }

// B3
let backups = tmp.appendingPathComponent("backups")
do {
    let zip = try BackupArchive.export(roots: src, defaults: defaults, includeSavestates: true, io: PythonZip(), to: backups)
    let named = zip.deletingLastPathComponent().standardizedFileURL.path == backups.standardizedFileURL.path
        && zip.lastPathComponent.range(of: #"^ARMSX3-Backup-[0-9]{8}-[0-9]{6}\.zip$"#, options: .regularExpression) != nil
        && files.fileExists(atPath: zip.path)
    let out = roots("out-b3")
    let into = suite("b3")
    let result = try BackupArchive.restore(from: zip, roots: out, defaults: into)
    var same = true
    for (a, b) in [(src.config, out.config), (src.hdd0, out.hdd0), (src.app, out.app)] {
        for rel in listing(b) where (try? Data(contentsOf: a.appendingPathComponent(rel))) != (try? Data(contentsOf: b.appendingPathComponent(rel))) {
            same = false
        }
    }
    print("B3 named=\(named ? 1 : 0) config=\(listing(out.config).count) hdd0=\(listing(out.hdd0).count) app=\(listing(out.app).count) written=\(result.written) skipped=\(result.skipped) prefs=\(result.prefs) same=\(same ? 1 : 0) dim=\(into.double(forKey: "armsx3.settings.backgroundDim")) other=\(into.object(forKey: "other.key") == nil ? "absent" : "present")")
} catch { print("B3 error=\(word(error))") }

// H1-H9
let hz = tmp.appendingPathComponent("hz")
for name in ["h1", "h2", "h3", "h4", "h5", "h6", "h7", "h8", "h9"] {
    let out = roots("out-" + name)
    let into = suite(name)
    var line = name.uppercased()
    do {
        let r = try BackupArchive.restore(from: hz.appendingPathComponent(name + ".zip"), roots: out, defaults: into)
        line += " written=\(r.written) skipped=\(r.skipped) prefs=\(r.prefs)"
    } catch {
        line += " error=\(word(error))"
    }
    line += " files=\(count(out))"
    if name == "h5" { line += " config=" + listing(out.config).joined(separator: ",") }
    if name == "h8" { line += " hdd0=" + listing(out.hdd0).joined(separator: ",") }
    if name == "h9" { line += " dim=\(into.double(forKey: "armsx3.settings.backgroundDim")) other=\(into.object(forKey: "other.key") == nil ? "absent" : "present")" }
    print(line)
}

// H10
let stage2 = tmp.appendingPathComponent("stage2")
do {
    try BackupArchive.stage(roots: src, defaults: defaults, includeSavestates: false, into: stage2)
    let staged = listing(stage2.appendingPathComponent(BackupArchive.topFolder))
    print("H10 files=\(staged.count) savestates=\(staged.contains { $0.hasPrefix("files/config/savestates/") } ? 1 : 0)")
} catch { print("H10 error=\(word(error))") }

// H11
let backups2 = tmp.appendingPathComponent("backups2")
do {
    _ = try BackupArchive.export(roots: src, defaults: defaults, includeSavestates: true, io: PythonZip(zip64: true), to: backups2)
    print("H11 exported")
} catch {
    print("H11 error=\(word(error)) left=\(listing(backups2).count)")
}

// H12: live files seeded first; a corrupt LATER entry must leave them byte-identical.
let live = roots("out-h12")
let seeds: [(URL, Data)] = [
    (live.config.appendingPathComponent("config.yml"), Data("live config".utf8)),
    (live.hdd0.appendingPathComponent("home/00000001/savedata/ABCD12345-DATA/DATA.BIN"), Data(repeating: 0x5A, count: 4096)),
]
for (url, data) in seeds {
    try? files.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? data.write(to: url)
}
var line12 = "H12"
do {
    let r = try BackupArchive.restore(from: hz.appendingPathComponent("h12.zip"), roots: live, defaults: suite("h12"))
    line12 += " written=\(r.written)"
} catch {
    line12 += " error=\(word(error))"
}
print(line12 + " same=\(seeds.allSatisfy { (try? Data(contentsOf: $0.0)) == $0.1 } ? 1 : 0) files=\(count(live))")

// H13, B4: the preference allow-list, import and export.
func armsx3Keys(_ d: UserDefaults) -> String {
    d.dictionaryRepresentation().keys.filter { $0.hasPrefix("armsx3.") }.sorted().joined(separator: ",")
}
let into13 = suite("h13")
do {
    let r = try BackupArchive.restore(from: hz.appendingPathComponent("h13.zip"), roots: roots("out-h13"), defaults: into13)
    print("H13 prefs=\(r.prefs) keys=\(armsx3Keys(into13))")
} catch { print("H13 error=\(word(error))") }
let from4 = suite("b4")
from4.set(0.3, forKey: "armsx3.settings.backgroundDim")
from4.set(true, forKey: "armsx3.autorun.armed")
from4.set(Data("asset".utf8), forKey: "armsx3.settings.backgroundLandscapeAsset")
from4.set("../skins/s1/p.slangp", forKey: "armsx3.shaders.activePreset")
let stage4 = tmp.appendingPathComponent("stage4")
do {
    try BackupArchive.stage(roots: src, defaults: from4, includeSavestates: false, into: stage4)
    let data = try Data(contentsOf: stage4.appendingPathComponent(BackupArchive.topFolder + "/prefs/armsx3.plist"))
    let prefs = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any] ?? [:]
    print("B4 prefs=" + prefs.keys.sorted().joined(separator: ","))
} catch { print("B4 error=\(word(error))") }

// H14: a reinstall, a restore, then a PS-menu state that has no epoch record.
func day(_ y: Int, _ m: Int, _ d: Int) -> Date {
    DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(identifier: "UTC"), year: y, month: m, day: d).date!
}
for (name, current) in [("h14", day(2026, 6, 1)), ("h14later", day(2025, 6, 1)), ("h14bad", day(2026, 6, 1))] {
    let into = suite(name)
    into.set(current, forKey: SaveStateFiles.epochSinceKey)
    let out = roots("out-" + name)
    do {
        let r = try BackupArchive.restore(from: hz.appendingPathComponent(name + ".zip"), roots: out, defaults: into)
        let since = into.object(forKey: SaveStateFiles.epochSinceKey) as? Date
        let kept = since.map { $0 == current ? "current" : $0 == day(2026, 1, 1) ? "backup" : "other" } ?? "nil"
        let state = out.config.appendingPathComponent("savestates/ABCD12345/ABCD12345_1.SAVESTAT")
        print("\(name.uppercased()) prefs=\(r.prefs) since=\(kept) ps=\(SaveStateFiles.epoch(ofStateAt: state, since: since))")
    } catch { print("\(name.uppercased()) error=\(word(error))") }
}

// h15: an archive carrying rpcn.yml restores with it skipped.
do {
    let out = roots("out-h15")
    let r = try BackupArchive.restore(from: hz.appendingPathComponent("h15.zip"), roots: out, defaults: suite("h15"))
    let yml = files.fileExists(atPath: out.config.appendingPathComponent("rpcn.yml").path)
    print("h15 skipped=\(r.skipped) rpcnyml=\(yml ? 1 : 0)")
} catch { print("h15 error=\(word(error))") }

// S1: the containment ShaderPacks resolves a stored preset through.
let shaders = tmp.appendingPathComponent("app/shaders")
func ok(_ relative: String) -> Int { SaveDataStore.containedFile(relative, in: shaders, suffix: ".slangp") == nil ? 0 : 1 }
print("S1 in=\(ok("shaders_slang/crt/crt.slangp")) up=\(ok("../skins/s1/p.slangp")) climb=\(ok("shaders_slang/../../skins/p.slangp")) suffix=\(ok("shaders_slang/crt/crt.glslp")) empty=\(ok(""))")

for name in ["src", "b3", "b4", "h1", "h2", "h3", "h4", "h5", "h6", "h7", "h8", "h9", "h12", "h13", "h14", "h14later", "h14bad", "h15"] {
    UserDefaults().removePersistentDomain(forName: "armsx3-backup-test-\(ProcessInfo.processInfo.processIdentifier)-\(name)")
}
"""

RESTORED = "B3 named=1 config=6 hdd0=1 app=3 written=10 skipped=0 prefs=1 same=1 dim=0.4 other=absent"
EXPECTED = {
    "B1": "B1 " + ",".join(STAGED),
    "B2": "B2 keys=package,platform,schemaVersion,versionName schema=1 platform=ios prefs=armsx3.settings.backgroundDim",
    "B3": RESTORED,
    "H1": "H1 error=unsafeEntry files=0",
    "H2": "H2 error=unsafeEntry files=0",
    "H3": "H3 error=unsafeEntry files=0",
    "H4": "H4 error=unsafeEntry files=0",
    "H5": "H5 written=1 skipped=6 prefs=0 files=1 config=config.yml",
    "H6": "H6 error=notABackup files=0",
    "H7": "H7 error=newerVersion files=0",
    "H8": "H8 written=2 skipped=0 prefs=0 files=2 hdd0=home/00000001/savedata/ABCD12345-A/DATA.BIN",
    "H9": "H9 written=1 skipped=0 prefs=1 files=1 dim=0.25 other=absent",
    "H10": "H10 files={0} savestates=0".format(len(STAGED) - 1),
    "H11": "H11 error=tooLarge left=0",
    "H12": "H12 error=corrupt same=1 files=2",
    "H13": "H13 prefs=2 keys=armsx3.settings.backgroundDim,armsx3.skins.active",
    "B4": "B4 prefs=armsx3.settings.backgroundDim",
    "H14": "H14 prefs=1 since=backup ps=2",
    "H14LATER": "H14LATER prefs=1 since=current ps=2",
    "H14BAD": "H14BAD prefs=0 since=current ps=1",
    "S1": "S1 in=1 up=0 climb=0 suffix=0 empty=0",
    # The pinned rpcn.yml line.
    "h15": "h15 skipped=1 rpcnyml=0",
}


def backup_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: BackupArchive.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: BackupArchive.swift logs")
    if 'static let manifestName = "armsx3-backup.json"' not in code:
        problems.append("manifest: BackupArchive.swift does not name the owner's manifest, armsx3-backup.json")
    for literal in LITERAL.findall(code):
        if DENIED.search(literal):
            problems.append("deny: BackupArchive.swift names {0!r}; the archive is an allow-list".format(literal))
    if "SaveDataStore.extractTree(" not in code:
        problems.append("tree: BackupArchive.swift's restore does not write through SaveDataStore.extractTree")
    if not re.search(r"//\s*shortcut:[^\n]*ZIP64", text):
        problems.append("ceiling: BackupArchive.swift has no shortcut comment naming the ZIP64 ceiling")
    problems.extend(rpcn_problems(text))
    return problems


def rpcn_problems(text):
    """The RPCN login stays out of every backup, both ways."""
    problems = []
    code = without_comments(text)
    start = code.find("static let areas: [Area] = [")
    areas = code[start:code.find("\n    ]", start)] if start != -1 else ""
    if not areas:
        problems.append("norpcn: BackupArchive.swift has no `static let areas: [Area] = [` list to read")
    elif any("rpcn" in literal.lower() for literal in LITERAL.findall(areas)):
        problems.append("norpcn: an item in `areas` names rpcn; the RPCN login never enters a backup")
    if KEYCHAIN.search(code):
        problems.append("nokeychain: BackupArchive.swift calls a SecItem function; the Keychain never enters a backup")
    above = text[:text.find("static let areas: [Area] = [")].rstrip().split("\n")
    comment = []
    while above and above[-1].strip().startswith("//"):
        comment.append(above.pop())
    if "RPCN login" not in " ".join(comment):
        problems.append("why: the comment directly above `areas` does not say the RPCN login stays out")
    return problems


def shader_problems(text):
    code = without_comments(text)
    if "SaveDataStore.containedFile(" not in code or code.count("presetURL(") < 3:
        return ["shader: ShaderPacks.swift does not resolve every stored preset through presetURL and "
                "SaveDataStore.containedFile"]
    return []


def view_problems(text, task3=True):
    problems = []
    code = without_comments(text)
    for needle in ("Rpcs3Bridge.configDirectory", "Rpcs3Bridge.hdd0Directory", "BackupArchive.export(",
                   "BackupArchive.restore("):
        if needle not in code:
            problems.append("view: BackupView.swift does not call {0}".format(needle))
    if task3:
        if code.count("EmulatorSession.shared.isRunning") < 2:
            problems.append("running: BackupView.swift does not check EmulatorSession.shared.isRunning before both export "
                            "and restore")
        if '"Include save states"' not in code:
            problems.append("running: BackupView.swift has no Include save states toggle")
    return problems


class TheBackupRoundTrips(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, STORE, BACKUP):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_fixtures(tmp)
            out = compile_and_run([SFO, ZIP, STORE, STATES, BACKUP], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, *keys):
        for key in keys:
            with self.subTest(key):
                self.assertEqual(EXPECTED[key], self.lines.get(key))

    def test_b1_stage_holds_exactly_the_allow_list(self):
        self.check("B1")

    def test_b2_manifest_and_prefs_carry_nothing_else(self):
        self.check("B2")

    def test_b3_export_then_restore_is_byte_identical(self):
        self.check("B3")

    def test_h1_h4_hostile_names_write_nothing(self):
        self.check("H1", "H2", "H3", "H4")

    def test_h5_foreign_entries_are_skipped_and_counted(self):
        self.check("H5")

    def test_h6_h7_no_manifest_or_a_newer_schema_is_refused(self):
        self.check("H6", "H7")

    def test_h8_an_android_shaped_archive_restores(self):
        self.check("H8")

    def test_h9_only_armsx3_preferences_are_imported(self):
        self.check("H9")

    def test_h10_savestates_can_be_left_out(self):
        self.check("H10")

    def test_h11_a_zip64_export_is_refused_and_removed(self):
        self.check("H11")

    def test_h12_a_corrupt_later_entry_leaves_live_files_untouched(self):
        self.check("H12")

    def test_h13_b4_only_allow_listed_preferences_cross(self):
        self.check("H13", "B4")

    def test_h14_a_restore_keeps_the_earlier_epoch_date(self):
        self.check("H14", "H14LATER", "H14BAD")

    def test_s1_a_shader_preset_stays_inside_its_folder(self):
        self.check("S1")

    # The line is echoed so a verify can read it.
    def test_h15_a_restore_skips_rpcn_yml(self):
        sys.stderr.write("\n" + str(self.lines.get("h15")) + "\n")
        self.check("h15")


class TheRealFiles(unittest.TestCase):
    def test_backup_archive_has_no_problems(self):
        self.assertEqual([], backup_problems(read(BACKUP)))

    def test_backup_view_has_no_problems(self):
        self.assertEqual([], view_problems(read(VIEW)))

    def test_shader_packs_have_no_problems(self):
        self.assertEqual([], shader_problems(read(SHADERS)))

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
        cls.backup = read(BACKUP)
        cls.view = read(VIEW)
        cls.shaders = read(SHADERS)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, problems, check):
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host(self):
        self.assert_names(backup_problems(self.edited(self.backup, "import Foundation", "import Foundation\nimport UIKit")), "host")

    def test_mutation_log(self):
        self.assert_names(backup_problems(self.backup + "\nfunc probe(_ s: String) { print(s) }\n"), "log")

    def test_mutation_manifest(self):
        self.assert_names(backup_problems(self.edited(self.backup, '"armsx3-backup.json"', '"armsx3-ios-backup.json"')),
                          "manifest")

    def test_mutation_deny(self):
        self.assert_names(backup_problems(self.edited(self.backup, '"custom_configs/"', '"dev_flash/"')), "deny")

    def test_mutation_tree(self):
        self.assert_names(backup_problems(self.edited(self.backup, "SaveDataStore.extractTree(", "extractAll(")), "tree")

    def test_mutation_ceiling(self):
        self.assert_names(backup_problems(self.edited(self.backup, "shortcut:", "note:")), "ceiling")

    def test_mutation_view(self):
        self.assert_names(view_problems(self.edited(self.view, "Rpcs3Bridge.hdd0Directory", "Rpcs3Bridge.userDirectory")),
                          "view")

    def test_mutation_shader(self):
        self.assert_names(shader_problems(self.edited(self.shaders, "guard let preset = presetURL(relativePath)",
                                                      "guard let preset = Optional(root.appendingPathComponent(relativePath))")),
                          "shader")

    def test_mutation_running(self):
        self.assert_names(view_problems(self.edited(self.view, "EmulatorSession.shared.isRunning", "false")), "running")

    # One mutation per RPCN check.
    def test_mutation_norpcn(self):
        self.assert_names(backup_problems(self.edited(self.backup, '"input_configs/"]', '"input_configs/", "rpcn.yml"]')),
                          "norpcn")

    def test_mutation_nokeychain(self):
        self.assert_names(backup_problems(self.backup + "\nfunc probe() { SecItemDelete([:] as CFDictionary) }\n"),
                          "nokeychain")

    def test_mutation_why(self):
        self.assert_names(backup_problems(self.edited(self.backup, "RPCN login", "RPCN data")), "why")


if __name__ == "__main__":
    unittest.main(verbosity=2)
