#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""SaveDataStore.swift and ZipArchive.swift, run on the Mac host over built folders and zips with a fake SaveDataIO,
import a save staged, validated and committed atomically, back up before any replace or delete, refuse every unsafe or
oversized entry even on /private paths, and extract trees safely; static checks pin the view's off-main jobs."""

import io
import json
import os
import re
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import body_of, build_sfo, read, STRING
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SFO = APP / "Library" / "ParamSfo.swift"
ZIP = APP / "Install" / "ZipArchive.swift"
STORE = APP / "Saves" / "SaveDataStore.swift"
VIEW = APP / "Saves" / "SaveDataView.swift"
HELPER = IOS / "tests" / "swiftc_host.py"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
NETWORK = ("URLSession", "http://", "https://")
ENTROPY = bytes(range(256)) * 16
CONTAIN = re.compile(r"guard\s+Self\.isInside\(\s*target\s*,\s*stagingDir\s*\)\s*else\s*\{\s*throw\s+SaveImportError\.unsafeEntry")
# Names that canonicalise through the filesystem, whose answer depends on what exists on disk.
FS_CANON = ("standardizedFileURL", "standardizingPath", "resolvingSymlinksInPath", "realpath", "fileExists")
DETACHED = re.compile(r"Task\.detached\s*\([^)]*\)\s*\{")
STORE_CALL = re.compile(r"\bstore\??\.(prepareImport|commit|discard|export|delete|saves)\(")
# A scalar split on `/` and `\`, with or without `(omittingEmptySubsequences: ...)`.
SCALAR_SPLIT = re.compile(r'\.split\s*(?:\([^)]*\))?\s*\{\s*\$0\s*==\s*"/"\s*\|\|\s*\$0\s*==\s*"\\\\"\s*\}')


def detached_spans(code):
    """(open, close) of every `Task.detached(...) { ... }` block in `code`."""
    return [brace_span(code, match.end() - 1) for match in DETACHED.finditer(code)]


def sfo_named(name):
    return build_sfo([("SAVEDATA_DIRECTORY", STRING, name.encode() + b"\0"),
                      ("TITLE", STRING, b"Synthetic Title\0")])


def make_save(folder, dir_name, files, mtime=None):
    folder.mkdir(parents=True)
    (folder / "PARAM.SFO").write_bytes(sfo_named(dir_name))
    for name, data in files.items():
        (folder / name).write_bytes(data)
    if mtime is not None:
        os.utime(folder / "PARAM.SFO", (mtime, mtime))
    return folder


def zip_folder(folder, target, compress=zipfile.ZIP_DEFLATED, extra=(), stream=False):
    """Zip `folder` under its own name into `target`; `extra` adds (name, bytes) entries as they are."""
    class Unseekable:
        def __init__(self, sink):
            self.sink = sink

        def write(self, data):
            return self.sink.write(data)

        def flush(self):
            self.sink.flush()

    with open(target, "wb") as sink:
        out = Unseekable(sink) if stream else sink
        with zipfile.ZipFile(out, "w") as archive:
            for path in sorted(folder.rglob("*")):
                if path.is_file():
                    name = folder.name + "/" + path.relative_to(folder).as_posix()
                    method = zipfile.ZIP_STORED if path.name == "RAW.BIN" else compress
                    archive.write(path, name, compress_type=method)
            for name, data in extra:
                archive.writestr(zipfile.ZipInfo(name), data)
    return target


def patched(source, target, edit):
    data = bytearray(source.read_bytes())
    eocd = data.rfind(b"PK\x05\x06")
    central = struct.unpack_from("<I", data, eocd + 16)[0]
    edit(data, eocd, central)
    target.write_bytes(bytes(data))
    return target


def write_fixtures(tmp):
    src = Path(tmp) / "src"
    make_save(src / "fold" / "WRAP" / "GOOD", "ABCD12345-GOOD",
              {"DATA.BIN": b"v1", "ICON0.PNG": bytes(64)}, mtime=1700000000)
    make_save(src / "fold2" / "GOOD", "ABCD12345-GOOD", {"DATA.BIN": b"v2"}, mtime=1700200000)

    zipped = make_save(src / "zipsrc" / "ZIPSAVE", "ABCD12345-ZIP",
                       {"DATA.BIN": b"compressible " * 2000, "RAW.BIN": bytes(range(200))})
    zip_folder(zipped, src / "a.zip")
    streamed = make_save(src / "zipsrc2" / "STREAMED", "ABCD12345-ZIPD", {"DATA.BIN": b"descriptor " * 3000})
    zip_folder(streamed, src / "b.zip", stream=True)
    flags = struct.unpack_from("<H", (src / "b.zip").read_bytes(), 6)[0]
    if not flags & 0x08:
        raise AssertionError("the streamed zip was written without data descriptors; the C4 case would prove nothing")

    sub = make_save(src / "sub" / "SUB", "ABCD12345-SUB", {"DATA.BIN": b"sub"})
    (sub / "PARAM.SFO").write_bytes(build_sfo([
        ("SAVEDATA_DIRECTORY", STRING, b"ABCD12345-SUB\0"),
        ("SUB_TITLE", STRING, 'Slot 1: "A/B"'.encode() + b"\0"),
        ("TITLE", STRING, b"Synthetic Title\0")]))
    make_save(src / "pfd" / "PFD", "ABCD12345-PFD", {"PARAM.PFD": bytes(512), "DATA.BIN": bytes(8192)})
    make_save(src / "ent" / "ENT", "ABCD12345-ENT", {"DATA.BIN": ENTROPY})

    (src / "nosfo" / "SAVE").mkdir(parents=True)
    (src / "nosfo" / "SAVE" / "DATA.BIN").write_bytes(b"x")
    make_save(src / "lower" / "S", "ABCD12345-low", {})
    make_save(src / "dot" / "S", "ABCD12345.DOT", {})
    make_save(src / "under" / "S", "_ABCD12345", {})
    make_save(src / "long" / "S", "A" * 32, {})
    make_save(src / "crypt" / "S", "ABCD12345-CRYPT", {"PARAM.PFD": bytes(512), "DATA.BIN": ENTROPY})
    linked = make_save(src / "link" / "S", "ABCD12345-LINK", {})
    os.symlink("/etc", str(linked / "escape"))

    plain = make_save(src / "zipplain" / "PLAIN", "ABCD12345-PLAIN", {"DATA.BIN": b"plain"})
    zip_folder(plain, src / "dotdot.zip", extra=[("../x", b"x")])
    zip_folder(plain, src / "abs.zip", extra=[("/x", b"x")])
    zip_folder(plain, src / "plain.zip")
    patched(src / "plain.zip", src / "zipenc.zip",
            lambda d, eocd, cd: struct.pack_into("<H", d, cd + 8, struct.unpack_from("<H", d, cd + 8)[0] | 1))
    patched(src / "plain.zip", src / "zip64.zip",
            lambda d, eocd, cd: struct.pack_into("<HH", d, eocd + 8, 0xFFFF, 0xFFFF))
    patched(src / "plain.zip", src / "method.zip", lambda d, eocd, cd: struct.pack_into("<H", d, cd + 10, 12))
    zip_folder(plain, src / "big.zip", extra=[("PLAIN/BIG.BIN", bytes(128 * 1024))])
    zip_folder(plain, src / "total.zip", extra=[("PLAIN/T{0}.BIN".format(i), bytes(60 * 1024)) for i in range(5)])
    zip_folder(plain, src / "count.zip", extra=[("PLAIN/N{0}.BIN".format(i), b"n") for i in range(60)])
    # `..` hidden behind a control character (zipfile keeps \x01, truncates at \x00).
    with zipfile.ZipFile(src / "ctrl.zip", "w") as archive:
        for path in sorted(plain.iterdir()):
            archive.writestr("..\x01/PLAIN/" + path.name, path.read_bytes())
        archive.writestr("..\x01/..\x01/evil.bin", b"evil")
    zip_folder(plain, src / "ctrl2.zip", extra=[(".\x01./.\x01./evil.bin", b"evil")])
    # A `/` followed by U+0301 is one Character that is not `/`, so a Character split misses the `..` parts.
    zip_folder(plain, src / "rctrl3.zip", extra=[("../́x/../́evil.bin", b"evil")])
    # The same mark glued to a leading `/`, which a Character `hasPrefix("/")` misses.
    zip_folder(plain, src / "rabs2.zip", extra=[("/́x/evil.bin", b"evil")])
    # Lexical containment holds only because staging can hold no symlink, so a symlink entry must be refused.
    zip_folder(plain, src / "zlink.zip")
    with zipfile.ZipFile(src / "zlink.zip", "a") as archive:
        link = zipfile.ZipInfo("PLAIN/link")
        link.external_attr = 0o120777 << 16
        archive.writestr(link, b"/etc")


DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let src = tmp.appendingPathComponent("src")
let user = tmp.appendingPathComponent("user")
let backups = tmp.appendingPathComponent("backups")
let files = FileManager.default

final class FakeIO: SaveDataIO {
    var calls: [String] = []
    var fail = false
    func zipDirectory(_ dir: URL, to zip: URL) throws {
        let data = (try? String(contentsOf: dir.appendingPathComponent("DATA.BIN"), encoding: .utf8)) ?? "-"
        calls.append("\(dir.lastPathComponent):\(data)")
        if fail { throw CocoaError(.fileWriteUnknown) }
        try files.createDirectory(at: zip.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("fake".utf8).write(to: zip)
    }
}

let fake = FakeIO()
let store = SaveDataStore(userDir: user, backupDir: backups, exportDir: tmp.appendingPathComponent("exports"), io: fake,
                          limits: ZipLimits(entryBytes: 64 * 1024, totalBytes: 256 * 1024, entries: 50))
let savedata = store.savedataDir
let staging = savedata.appendingPathComponent(".import-tmp")

func exists(_ url: URL) -> Bool { files.fileExists(atPath: url.path) }
func live(_ name: String) -> URL { savedata.appendingPathComponent(name) }
func text(_ url: URL) -> String { (try? String(contentsOf: url, encoding: .utf8)) ?? "-" }
func epoch(_ date: Date?) -> String { date.map { String(Int($0.timeIntervalSince1970)) } ?? "nil" }

// Every relative path under `dir` with its size, hidden ones included.
func snapshot(_ dir: URL) -> [String] {
    let paths = (files.enumerator(atPath: dir.path)?.allObjects as? [String] ?? []).sorted()
    return paths.map { path in
        let size = (try? files.attributesOfItem(atPath: dir.appendingPathComponent(path).path))?[.size] as? Int ?? -1
        return "\(path)=\(size)"
    }
}

// The same files with the same bytes, by relative path.
func same(_ a: URL, _ b: URL) -> Bool {
    let left = (files.enumerator(atPath: a.path)?.allObjects as? [String] ?? []).sorted()
    let right = (files.enumerator(atPath: b.path)?.allObjects as? [String] ?? []).sorted()
    guard !left.isEmpty, left == right else { return false }
    return left.allSatisfy { path in
        let x = a.appendingPathComponent(path), y = b.appendingPathComponent(path)
        var dir: ObjCBool = false
        files.fileExists(atPath: x.path, isDirectory: &dir)
        return dir.boolValue || (try? Data(contentsOf: x)) == (try? Data(contentsOf: y))
    }
}

func word(_ error: Error) -> String { (error as? SaveImportError)?.word ?? "other" }

// C1
let first = try! store.prepareImport(from: src.appendingPathComponent("fold"))
let firstExisting = first.existingDate == nil
let replaced1 = try! store.commit(first, replacing: false)
print("C1 name=\(first.dirName) new=\(firstExisting) replaced=\(replaced1) same=\(same(src.appendingPathComponent("fold/WRAP/GOOD"), live("ABCD12345-GOOD"))) backups=\(fake.calls.count) tmp=\(exists(staging))")

// C2
let again = try! store.prepareImport(from: src.appendingPathComponent("fold"))
var refused = "none"
do { try store.commit(again, replacing: false) } catch { refused = word(error) }
store.discard()
print("C2 incoming=\(epoch(again.incomingDate)) existing=\(epoch(again.existingDate)) commit=\(refused) tmp=\(exists(staging)) kept=\(text(live("ABCD12345-GOOD/DATA.BIN"))) backups=\(fake.calls.count)")

// C3
let newer = try! store.prepareImport(from: src.appendingPathComponent("fold2"))
let replaced3 = try! store.commit(newer, replacing: true)
let backupNames = ((try? files.contentsOfDirectory(atPath: backups.path)) ?? []).sorted()
let named = backupNames.count == 1 && backupNames[0].range(of: #"^ABCD12345-GOOD \d{4}-\d{2}-\d{2} \d{6}\.zip$"#, options: .regularExpression) != nil
print("C3 incoming=\(epoch(newer.incomingDate)) existing=\(epoch(newer.existingDate)) replaced=\(replaced3) calls=\(fake.calls.joined(separator: ",")) named=\(named) now=\(text(live("ABCD12345-GOOD/DATA.BIN"))) old=\(exists(live(".ABCD12345-GOOD.old"))) tmp=\(exists(staging))")

// C4
let a = try! store.prepareImport(from: src.appendingPathComponent("a.zip"))
try! store.commit(a, replacing: false)
let b = try! store.prepareImport(from: src.appendingPathComponent("b.zip"))
try! store.commit(b, replacing: false)
print("C4 a=\(a.dirName) same=\(same(src.appendingPathComponent("zipsrc/ZIPSAVE"), live("ABCD12345-ZIP"))) b=\(b.dirName) same=\(same(src.appendingPathComponent("zipsrc2/STREAMED"), live("ABCD12345-ZIPD"))) tmp=\(exists(staging))")

// C5
func imported(_ source: String) -> String {
    do {
        let staged = try store.prepareImport(from: src.appendingPathComponent(source))
        try store.commit(staged, replacing: false)
        return "ok"
    } catch { return word(error) }
}
print("C5 pfd=\(imported("pfd")) entropy=\(imported("ent"))")

// C6
let vanish = try! store.prepareImport(from: src.appendingPathComponent("fold"))
try! files.removeItem(at: vanish.folder)
var c6 = "none"
do { try store.commit(vanish, replacing: true) } catch { c6 = word(error) }
store.discard()
print("C6 error=\(c6) kept=\(text(live("ABCD12345-GOOD/DATA.BIN"))) old=\(exists(live(".ABCD12345-GOOD.old"))) tmp=\(exists(staging))")

// C7
try! files.createDirectory(at: staging.appendingPathComponent("junk"), withIntermediateDirectories: true)
try! files.createDirectory(at: live(".ABCD12345-GOOD.old"), withIntermediateDirectories: true)
try! files.createDirectory(at: live(".ABCD12345-LOST.old"), withIntermediateDirectories: true)
try! Data("lost".utf8).write(to: live(".ABCD12345-LOST.old/DATA.BIN"))
let swept = try! store.prepareImport(from: src.appendingPathComponent("a.zip"))
let junk = exists(staging.appendingPathComponent("junk"))
store.discard()
print("C7 junk=\(junk) oldWithSave=\(exists(live(".ABCD12345-GOOD.old"))) restored=\(text(live("ABCD12345-LOST/DATA.BIN"))) oldWithout=\(exists(live(".ABCD12345-LOST.old"))) tmp=\(exists(staging))")

// C8
fake.fail = true
let blocked = try! store.prepareImport(from: src.appendingPathComponent("fold"))
var c8 = "none"
do { try store.commit(blocked, replacing: true) } catch { c8 = word(error) }
store.discard()
fake.fail = false
print("C8 error=\(c8) kept=\(text(live("ABCD12345-GOOD/DATA.BIN"))) tmp=\(exists(staging))")

// C9
try! store.commit(try! store.prepareImport(from: src.appendingPathComponent("sub")), replacing: false)
let subBefore = snapshot(live("ABCD12345-SUB"))
let callsBefore = fake.calls.count
let e1 = try! store.export(dirName: "ABCD12345-SUB", gameTitle: "Synthetic: Title?")
let e2 = try! store.export(dirName: "ABCD12345-SUB", gameTitle: "Synthetic: Title?")
let e3 = try! store.export(dirName: "ABCD12345-GOOD", gameTitle: "Synthetic Title")
print("C9 name=\(e1.lastPathComponent) plain=\(e3.lastPathComponent) two=\(e1 != e2 && exists(e1) && exists(e2)) calls=\(fake.calls.count - callsBefore) unchanged=\(snapshot(live("ABCD12345-SUB")) == subBefore)")

// C10
fake.fail = true
var c10 = "none"
do { try store.delete(dirName: "ABCD12345-ZIPD") } catch { c10 = word(error) }
let keptAfterFailure = exists(live("ABCD12345-ZIPD"))
fake.fail = false
try! store.delete(dirName: "ABCD12345-ZIPD")
print("C10 failed=\(c10) kept=\(keptAfterFailure) backedUpWhilePresent=\(fake.calls.last?.hasPrefix("ABCD12345-ZIPD:descriptor") == true) gone=\(!exists(live("ABCD12345-ZIPD")))")

// The reader and the path cleaner on their own.
var z1 = "accepted"
do { _ = try ZipArchive(url: src.appendingPathComponent("ctrl.zip")) } catch {
    z1 = (error as? ZipError).map { SaveImportError($0).word } ?? "other"
}
print("Z1 error=\(z1)")
print("P1 \(SaveDataStore.safeRelativePath("..\u{1}/..\u{1}/evil.bin") ?? "nil")")
print("P2 \(SaveDataStore.safeRelativePath("A/B\u{1}C/D") ?? "nil")")
print("P3 \(SaveDataStore.safeRelativePath("\u{1}/x") ?? "nil")")

// A combining mark glued to a separator, refused by the reader and the cleaner on their own.
for (key, source) in [("Z2", "rctrl3.zip"), ("Z3", "rabs2.zip")] {
    var result = "accepted"
    do { _ = try ZipArchive(url: src.appendingPathComponent(source)) } catch {
        result = (error as? ZipError).map { SaveImportError($0).word } ?? "other"
    }
    print("\(key) error=\(result)")
}
print("P4 \(SaveDataStore.safeRelativePath("../\u{301}x/../\u{301}evil.bin") ?? "nil")")

// The refusals.
for (key, source) in [("Rnosfo", "nosfo"), ("Rlower", "lower"), ("Rdot", "dot"), ("Runder", "under"), ("Rlong", "long"),
                      ("Rcrypt", "crypt"), ("Rdotdot", "dotdot.zip"), ("Rabs", "abs.zip"), ("Rlink", "link"),
                      ("Rzipenc", "zipenc.zip"), ("Rzip64", "zip64.zip"), ("Rmethod", "method.zip"),
                      ("Rbig", "big.zip"), ("Rtotal", "total.zip"), ("Rcount", "count.zip"),
                      ("Rctrl", "ctrl.zip"), ("Rctrl2", "ctrl2.zip"), ("Rctrl3", "rctrl3.zip")] {
    let before = snapshot(savedata)
    var result = "accepted"
    do {
        let staged = try store.prepareImport(from: src.appendingPathComponent(source))
        store.discard()
    } catch { result = word(error) }
    print("\(key) error=\(result) unchanged=\(snapshot(savedata) == before) tmp=\(exists(staging))")
}

// The core hands the app /private/var/mobile/... paths. The same root, spelled with /private.
let privateRoot = URL(fileURLWithPath: @@PRIVATE@@)
let kbase = privateRoot.appendingPathComponent("kbase")
try! files.createDirectory(at: kbase, withIntermediateDirectories: true)
// The anti-vacuity witness: the base is /private-spelled, and Foundation's standardisation drops that from it.
print("V0 private=\(kbase.path.hasPrefix("/private/")) dropped=\(!kbase.standardizedFileURL.path.hasPrefix("/private/"))")

let privStore = SaveDataStore(userDir: privateRoot.appendingPathComponent("puser"),
                              backupDir: privateRoot.appendingPathComponent("pbackups"),
                              exportDir: privateRoot.appendingPathComponent("pexports"), io: fake,
                              limits: ZipLimits(entryBytes: 64 * 1024, totalBytes: 256 * 1024, entries: 50))
let privSavedata = privStore.savedataDir
let privStaging = privSavedata.appendingPathComponent(".import-tmp")
var v1 = "none"
var v1same = false
do {
    let staged = try privStore.prepareImport(from: privateRoot.appendingPathComponent("src/a.zip"))
    try privStore.commit(staged, replacing: false)
    v1 = staged.dirName
    v1same = same(src.appendingPathComponent("zipsrc/ZIPSAVE"), privSavedata.appendingPathComponent(staged.dirName))
} catch { v1 = word(error) }
print("V1 base=\(privSavedata.path.hasPrefix("/private/") ? "private" : "other") zip=\(v1) same=\(v1same) tmp=\(exists(privStaging))")

// The containment layer alone, on the /private base: the same answer whether the target exists or not.
print("K1 \(SaveDataStore.isInside(kbase.appendingPathComponent("PLAIN/DATA.BIN"), kbase))")
try! files.createDirectory(at: kbase.appendingPathComponent("PLAIN"), withIntermediateDirectories: true)
print("K2 \(SaveDataStore.isInside(kbase.appendingPathComponent("PLAIN"), kbase))")
print("K3 \(SaveDataStore.isInside(kbase.appendingPathComponent("../../evil.bin"), kbase))")
print("K4 \(SaveDataStore.isInside(kbase.appendingPathComponent("../\u{301}x/../\u{301}evil.bin"), kbase))")
print("K5 \(SaveDataStore.isInside(kbase.appendingPathComponent("a/../../kbase2/evil.bin"), kbase))")
print("K6 \(SaveDataStore.isInside(kbase, kbase))")

// The control-character and combining-mark refusals, and a zip symlink, on the /private base.
for (key, source) in [("VRdotdot", "dotdot.zip"), ("VRabs", "abs.zip"), ("VRctrl", "ctrl.zip"), ("VRctrl2", "ctrl2.zip"),
                      ("VRctrl3", "rctrl3.zip"), ("VRabs2", "rabs2.zip"), ("VRzlink", "zlink.zip")] {
    let before = snapshot(privSavedata)
    var result = "accepted"
    do {
        let staged = try privStore.prepareImport(from: privateRoot.appendingPathComponent("src").appendingPathComponent(source))
        privStore.discard()
    } catch { result = word(error) }
    print("\(key) error=\(result) unchanged=\(snapshot(privSavedata) == before) tmp=\(exists(privStaging))")
}
"""


def problems_of(raw, helper):
    """raw: {file name: raw text} for SaveDataStore, ZipArchive and SaveDataView; helper: swiftc_host.py."""
    problems = []
    store = without_comments(raw["SaveDataStore.swift"])
    archive = without_comments(raw["ZipArchive.swift"])
    view = without_comments(raw["SaveDataView.swift"])
    if IMPORT.findall(store) != ["Foundation"]:
        problems.append("host: SaveDataStore.swift imports {0}; Foundation only".format(IMPORT.findall(store)))
    if sorted(IMPORT.findall(archive)) != ["Compression", "Foundation"]:
        problems.append("host: ZipArchive.swift imports {0}; exactly Compression and Foundation".format(IMPORT.findall(archive)))
    for name, code in (("SaveDataStore.swift", store), ("ZipArchive.swift", archive), ("SaveDataView.swift", view)):
        if LOGGING.search(code):
            problems.append("log: {0} logs".format(name))
    if "COMPRESSION_ZLIB" not in archive or "compression_stream_process(" not in archive:
        problems.append("zlib: ZipArchive.swift does not inflate with COMPRESSION_ZLIB through compression_stream_process")
    if '".import-tmp"' not in store:
        problems.append("staging: SaveDataStore.swift does not stage in .import-tmp")
    commit = body_of(store, r"func\s+commit\s*\(") or ""
    at = -1
    for step in ("guard replacing", "io.zipDirectory(", "moveItem(at: target, to: old)"):
        at = commit.find(step, at + 1)
        if at == -1:
            problems.append("backup: commit does not run `guard replacing`, the backup, then the move aside, in order")
            break
    delete = body_of(store, r"func\s+delete\s*\(") or ""
    zipped, removed = delete.find("io.zipDirectory("), delete.find("removeItem(")
    if zipped == -1 or removed == -1 or zipped > removed:
        problems.append("backup: delete does not back the save up before removing it")
    upload = body_of(store, r"struct\s+FoundationSaveDataIO\b") or ""
    if "NSFileCoordinator" not in upload or ".forUploading" not in upload:
        problems.append("upload: FoundationSaveDataIO does not zip through NSFileCoordinator .forUploading")
    for name in ("SaveDataStore.swift", "ZipArchive.swift", "SaveDataView.swift"):
        for token in NETWORK:
            if token in raw[name]:
                problems.append("network: {0} names {1}".format(name, token))
    pickers = re.findall(r"UIDocumentPickerViewController\(([^\n]*)", view)
    if not pickers:
        problems.append("picker: SaveDataView.swift opens no document picker")
    for call in pickers:
        if "asCopy: false" not in call:
            problems.append("picker: a picker does not open with asCopy: false")
    if "Compression" not in helper:
        problems.append("helper: swiftc_host.py's scope sentence does not name Compression")
    extract = body_of(store, r"func\s+extractZip\s*\(") or ""
    guard = CONTAIN.search(extract)
    writes = [at for at in (extract.find("createDirectory("), extract.find("extract(")) if at != -1]
    if guard is None or not writes or guard.start() > min(writes):
        problems.append("contain: extractZip does not confirm the target is inside staging before writing")
    inside = body_of(store, r"static\s+func\s+isInside\s*\(") or ""
    if "pathComponents" not in inside or '".."' not in inside:
        problems.append("contain: isInside does not resolve the URLs' pathComponents and their `..` lexically")
    for name, body in (("isInside", inside), ("extractZip", extract)):
        for token in FS_CANON:
            if token in body:
                problems.append("contain: {0} canonicalises through the filesystem ({1})".format(name, token))
    cleaner = body_of(store, r"func\s+safeRelativePath\s*\(") or ""
    if ".split(separator:" in cleaner or "name.unicodeScalars" not in cleaner or not SCALAR_SPLIT.search(cleaner):
        problems.append("scalars: safeRelativePath does not split the name's unicodeScalars on / and \\")
    check = body_of(archive, r"init\s*\(\s*url:") or ""
    if (".split(separator:" in check or "name.unicodeScalars" not in check or not SCALAR_SPLIT.search(check)
            or "name.hasPrefix(" in check or "name.contains(" in check
            or 'scalars.first == "/"' not in check or 'scalars.contains(":")' not in check):
        problems.append("scalars: ZipArchive's name check does not split and test the name's unicodeScalars")
    spans = detached_spans(view)
    calls = list(STORE_CALL.finditer(view))
    for call in calls:
        if not any(start < call.start() < end for start, end in spans):
            problems.append("offmain: SaveDataView.swift calls store.{0}( outside a Task.detached block".format(call.group(1)))
    missing = {"prepareImport", "commit", "discard", "export", "delete", "saves"} - {call.group(1) for call in calls}
    if missing:
        problems.append("offmain: SaveDataView.swift never calls store.{0}".format(sorted(missing)))
    if ("@State private var working = false" not in view or "working = true" not in view
            or "working = false" not in view
            or not re.search(r'Button\("Import"[^\n]*\n\s*\.disabled\([^\n]*\bworking\b', view)
            or not re.search(r'Button\("Export"[^\n]*\.disabled\(working\)', view)
            or not re.search(r'Button\("Delete", systemImage[^\n]*\.disabled\(working\)', view)):
        problems.append("offmain: Import, Export and Delete are not disabled by a `working` flag while a save is in flight")
    nav = view.find("NavigationStack")
    chain = re.match(r"(?:\s*\.[^\n]*)*", view[brace_span(view, nav)[1]:]).group(0) if nav != -1 else ""
    if (".interactiveDismissDisabled(working)" not in chain
            or not re.search(r'Button\("Done"\)\s*\{\s*dismiss\(\)\s*\}\s*\.disabled\(working\)', view)):
        problems.append("dismiss: Save Data can close while a save job runs; the sheet and Done are not held by `working`")
    return sorted(problems)


def real_inputs():
    return {path.name: read(path) for path in (STORE, ZIP, VIEW)}, read(HELPER)


EXPECTED = {
    "C1": "C1 name=ABCD12345-GOOD new=true replaced=false same=true backups=0 tmp=false",
    "C2": "C2 incoming=1700000000 existing=1700000000 commit=exists tmp=false kept=v1 backups=0",
    "C3": "C3 incoming=1700200000 existing=1700000000 replaced=true calls=ABCD12345-GOOD:v1 named=true now=v2 old=false tmp=false",
    "C4": "C4 a=ABCD12345-ZIP same=true b=ABCD12345-ZIPD same=true tmp=false",
    "C5": "C5 pfd=ok entropy=ok",
    "C6": "C6 error=write-failed kept=v2 old=false tmp=false",
    "C7": "C7 junk=false oldWithSave=false restored=lost oldWithout=false tmp=false",
    "C8": "C8 error=backup-failed kept=v2 tmp=false",
    "C9": 'C9 name=Synthetic_ Title_ - Slot 1_ _A_B_.zip plain=Synthetic Title - ABCD12345-GOOD.zip two=true calls=3 unchanged=true',
    "C10": "C10 failed=backup-failed kept=true backedUpWhilePresent=true gone=true",
    "Z1": "Z1 error=unsafe-entry",
    "P1": "P1 nil",
    "P2": "P2 A/BC/D",
    "P3": "P3 x",
    "Z2": "Z2 error=unsafe-entry",
    "Z3": "Z3 error=unsafe-entry",
    "P4": "P4 nil",
    "V0": "V0 private=true dropped=true",
    "V1": "V1 base=private zip=ABCD12345-ZIP same=true tmp=false",
    "K1": "K1 true",
    "K2": "K2 true",
    "K3": "K3 false",
    "K4": "K4 false",
    "K5": "K5 false",
    "K6": "K6 false",
}
REFUSALS = {
    "Rnosfo": "no-save", "Rlower": "bad-name", "Rdot": "bad-name", "Runder": "bad-name", "Rlong": "bad-name",
    "Rcrypt": "encrypted", "Rdotdot": "unsafe-entry", "Rabs": "unsafe-entry", "Rlink": "symlink",
    "Rzipenc": "zip-encrypted", "Rzip64": "zip64", "Rmethod": "zip-method", "Rbig": "too-large",
    "Rtotal": "too-large", "Rcount": "too-many", "Rctrl": "unsafe-entry", "Rctrl2": "unsafe-entry",
    "Rctrl3": "unsafe-entry",
    "VRdotdot": "unsafe-entry", "VRabs": "unsafe-entry", "VRctrl": "unsafe-entry", "VRctrl2": "unsafe-entry",
    "VRctrl3": "unsafe-entry", "VRabs2": "unsafe-entry", "VRzlink": "symlink",
}


class TheImporterHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            # The same folder spelled with /private, as the core spells container paths on the phone.
            private = os.path.realpath(tmp)
            if not private.startswith("/private/"):
                raise AssertionError("FAILURE, not skip: the host cannot reproduce the device's /private paths")
            write_fixtures(tmp)
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@PRIVATE@@", json.dumps(private))
            out = compile_and_run([SFO, ZIP, STORE], driver, tmp)
            cls.escaped = sorted(os.path.relpath(os.path.join(root, name), tmp)
                                 for root, _, names in os.walk(tmp) for name in names if name.endswith("evil.bin"))
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_cases(self):
        for key, expected in EXPECTED.items():
            with self.subTest(key):
                self.assertEqual(expected, self.lines.get(key), key)

    def test_refusals(self):
        for key, error in REFUSALS.items():
            with self.subTest(key):
                self.assertEqual("{0} error={1} unchanged=true tmp=false".format(key, error), self.lines.get(key), key)

    def test_nothing_escapes_staging(self):
        # Rctrl, Rctrl2 and Rctrl3 name `evil.bin` only inside the zips; no such file may exist on disk.
        self.assertEqual([], self.escaped, "Rctrl wrote evil.bin outside staging")


# SaveDataStore.extractTree keeps a zip's depth and refuses hostile names and destinations before anything is written.
TREE_DRIVER = r"""
import Foundation

let tmp = URL(fileURLWithPath: @@TMP@@)
let root = tmp.appendingPathComponent("root")

func plain(_ name: String) -> (root: URL, relative: String)? { (root, name) }

func run(_ key: String, _ zip: String, map: (String) -> (root: URL, relative: String)? = plain) {
    var limits = ZipLimits(entryBytes: 64 * 1024, totalBytes: 256 * 1024, entries: 50)
    do {
        let archive = try ZipArchive(url: tmp.appendingPathComponent(zip))
        let (written, skipped) = try SaveDataStore.extractTree(archive, limits: &limits, map: map)
        print("\(key) written=\(written) skipped=\(skipped)")
    } catch let error as SaveImportError {
        print("\(key) refused=\(error.word)")
    } catch let error as ZipError {
        print("\(key) refused=zip-\(error)")
    } catch {
        print("\(key) refused=other")
    }
}

run("T1", "deep.zip")
let deep = (try? Data(contentsOf: root.appendingPathComponent("a/b/c/d/e.slangp"))).map { String(decoding: $0, as: UTF8.self) } ?? "-"
print("T1b content=\(deep)")
run("T2", "dotdot.zip")
run("T3", "abs.zip")
run("T4", "mid.zip")
run("T5", "ctrl.zip")
run("T6", "one.zip", map: { _ in (root, "../escaped.bin") })
run("T7", "one.zip", map: { _ in (root, "a/../../escaped.bin") })
run("T8", "one.zip", map: { _ in (root, "..\u{1}/escaped.bin") })
run("T9", "two.zip", map: { $0.hasPrefix("skip") ? nil : (root, $0) })
run("T10", "big.zip")
"""

TREE_EXPECTED = {
    "T1": "T1 written=1 skipped=0",
    "T1b": "T1b content=deep",
    "T2": "T2 refused=zip-unsafeName",
    "T3": "T3 refused=zip-unsafeName",
    "T4": "T4 refused=zip-unsafeName",
    "T5": "T5 refused=zip-unsafeName",
    "T6": "T6 refused=unsafe-entry",
    "T7": "T7 refused=unsafe-entry",
    "T8": "T8 refused=unsafe-entry",
    "T9": "T9 written=1 skipped=1",
    "T10": "T10 refused=zip-tooLarge",
}


def write_tree_fixtures(tmp):
    def zipped(name, entries):
        with zipfile.ZipFile(os.path.join(tmp, name), "w") as archive:
            for entry, data in entries:
                archive.writestr(zipfile.ZipInfo(entry), data)

    zipped("deep.zip", [("a/b/c/d/e.slangp", b"deep")])
    zipped("dotdot.zip", [("keep.bin", b"k"), ("../escaped.bin", b"evil")])
    zipped("abs.zip", [("keep.bin", b"k"), ("/abs/escaped.bin", b"evil")])
    zipped("mid.zip", [("keep.bin", b"k"), ("a/../../escaped.bin", b"evil")])
    zipped("ctrl.zip", [("keep.bin", b"k"), ("..\x01/escaped.bin", b"evil")])
    zipped("one.zip", [("keep.bin", b"k")])
    zipped("two.zip", [("keep.bin", b"k"), ("skip.bin", b"s")])
    zipped("big.zip", [("big.bin", bytes(128 * 1024))])


class TheTreeExtractorHolds(unittest.TestCase):
    """T1 a deep entry lands whole; T2-T8 hostile names and destinations are refused; T9 a declined entry is skipped and
    counted; T10 ZipLimits stops an oversize member; nothing named escaped.bin escapes the root."""

    @classmethod
    def setUpClass(cls):
        for path in (SFO, ZIP, STORE):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            write_tree_fixtures(tmp)
            out = compile_and_run([SFO, ZIP, STORE], TREE_DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
            cls.escaped = sorted(os.path.relpath(os.path.join(where, name), tmp)
                                 for where, _, names in os.walk(tmp) for name in names if name == "escaped.bin")
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_deep_entry_written_whole(self):
        self.assertEqual(TREE_EXPECTED["T1"], self.lines.get("T1"))
        self.assertEqual(TREE_EXPECTED["T1b"], self.lines.get("T1b"))

    def test_hostile_names_refused(self):
        for key in ("T2", "T3", "T4", "T5"):
            with self.subTest(key):
                self.assertEqual(TREE_EXPECTED[key], self.lines.get(key))

    def test_hostile_destinations_refused(self):
        for key in ("T6", "T7", "T8"):
            with self.subTest(key):
                self.assertEqual(TREE_EXPECTED[key], self.lines.get(key))

    def test_declined_entry_skipped_and_counted(self):
        self.assertEqual(TREE_EXPECTED["T9"], self.lines.get("T9"))

    def test_limits_stop_an_oversize_member(self):
        self.assertEqual(TREE_EXPECTED["T10"], self.lines.get("T10"))

    def test_nothing_escapes_the_root(self):
        self.assertEqual([], self.escaped)

class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        raw, helper = real_inputs()
        self.assertEqual([], problems_of(raw, helper))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.raw, cls.helper = real_inputs()

    def edited(self, name, old, new):
        text = self.raw[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        raw = dict(self.raw)
        raw[name] = mutated
        return raw

    def assertFlagged(self, raw, prefix, helper=None):
        problems = problems_of(raw, self.helper if helper is None else helper)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_zip_imports_uikit(self):
        self.assertFlagged(self.edited("ZipArchive.swift", "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_in_the_store(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", "import Foundation", "import Foundation\nfunc probe(_ s: String) { NSLog(s) }"), "log")

    def test_mutation_zlib_swapped(self):
        self.assertFlagged(self.edited("ZipArchive.swift", "COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB", "COMPRESSION_STREAM_DECODE, COMPRESSION_LZFSE"), "zlib")

    def test_mutation_staging_renamed(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", '".import-tmp"', '"import-tmp"'), "staging")

    def test_mutation_backup_skipped(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", "try io.zipDirectory(", "try noBackup("), "backup")

    def test_mutation_delete_without_backup(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", "try io.zipDirectory(dir, to:", "try noBackup(dir, to:"), "backup")

    def test_mutation_upload_without_coordinator(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", "options: .forUploading", "options: []"), "upload")

    def test_mutation_network(self):
        self.assertFlagged(self.edited("SaveDataView.swift", "import SwiftUI", "import SwiftUI\nlet probe = URLSession.shared"), "network")

    def test_mutation_picker_copies(self):
        self.assertFlagged(self.edited("SaveDataView.swift", "types, asCopy: false", "types, asCopy: true"), "picker")

    def test_mutation_helper_scope_not_widened(self):
        self.assertFlagged(dict(self.raw), "helper", helper=self.helper.replace("Compression", "Foundation"))

    def test_mutation_containment_guard_deleted(self):
        self.assertFlagged(self.edited("SaveDataStore.swift", CONTAINMENT_GUARD, ""), "contain")

    def test_mutation_containment_canonicalises_through_the_filesystem(self):
        # The first `.pathComponents` inside isInside reads through Foundation's existence-dependent standardisation.
        self.assertFlagged(self.edited("SaveDataStore.swift", "url.pathComponents", "url.standardizedFileURL.pathComponents"),
                           "contain")

    def test_mutation_cleaner_character_split(self):
        self.assertFlagged(self.edited("SaveDataStore.swift",
                                       r'name.unicodeScalars.filter { $0.value >= 0x20 }.split { $0 == "/" || $0 == "\\" }',
                                       r'name.replacingOccurrences(of: "\\", with: "/").split(separator: "/")'), "scalars")

    def test_mutation_zip_character_prefix(self):
        self.assertFlagged(self.edited("ZipArchive.swift", 'scalars.first == "/"', 'name.hasPrefix("/")'), "scalars")

    def test_mutation_sheet_dismissable_while_working(self):
        self.assertFlagged(self.edited("SaveDataView.swift", "\n        .interactiveDismissDisabled(working)", ""), "dismiss")

    def test_mutation_done_enabled_while_working(self):
        self.assertFlagged(self.edited("SaveDataView.swift", 'Button("Done") { dismiss() }.disabled(working)',
                                       'Button("Done") { dismiss() }'), "dismiss")

    def test_mutation_store_call_back_on_main(self):
        # The first detached block becomes a plain Task, which runs on the main actor.
        self.assertFlagged(self.edited("SaveDataView.swift", "await Task.detached(priority: .userInitiated)",
                                       "await Task(priority: .userInitiated)"), "offmain")


# The guard exactly as SaveDataStore.extractZip writes it; the `contain` mutation deletes it.
CONTAINMENT_GUARD = """            guard Self.isInside(target, stagingDir) else {
                throw SaveImportError.unsafeEntry
            }
"""


if __name__ == "__main__":
    unittest.main(verbosity=2)
