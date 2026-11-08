#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ThemeLibrary.swift, run on the Mac host over a temporary folder, keeps each theme in its own folder with a versioned
theme.json, installs and updates through staging, hides damaged entries, deletes only through its own remove, sweep,
install and armed-marker paths, and plays slides and live scenes only when their files are present."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import body_of, read
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
SOURCES = [APP / "Themes" / "ThemeBytes.swift", APP / "Themes" / "P3tTheme.swift", APP / "Themes" / "ThemeLibrary.swift"]
LIBRARY = APP / "Themes" / "ThemeLibrary.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
REMOVERS = (r"func\s+remove\s*\(\s*named", r"func\s+sweep\s*\(", r"func\s+install\s*\(\s*staging",
            r"func\s+removeArmedMarker\s*\(")
ARMED_REMOVER = r"func\s+removeArmedMarker\s*\("

# The UI copy, verbatim.
MESSAGES = {
    "not-theme": "That file isn't a PS3 theme. Pick a .p3t file.",
    "damaged": "This PS3 theme is damaged and couldn't be read. Try another copy of it.",
    "no-picture": "This PS3 theme has no background picture. Pick another theme.",
    "dynamic-only": "ARMSX3 can't play this dynamic theme, and it has no still picture to show instead. Pick another theme.",
    "read-failed": "That file couldn't be read. If it is in iCloud Drive, download it first, then try again.",
    "write-failed": "The theme couldn't be saved. Check free space, then try again.",
}
REMOVE_FAILED = "The theme's files couldn't be deleted. Try again."

# The stored format, version 1, as ThemeLibrary writes it; every later build must read it.
THEME_JSON = """{
  "added" : "2027-01-15T08:00:00Z",
  "digest" : "9f2c",
  "importer" : 1,
  "name" : "Cherry Blossoms",
  "source" : "hd",
  "still" : "still.jpg",
  "version" : 1
}"""

# The slideshow form of the same document; the two optional fields are left out when nil.
SLIDES_JSON = """{
  "added" : "2027-01-15T08:00:00Z",
  "digest" : "5e7d",
  "importer" : 2,
  "name" : "Return To Serenity",
  "slides" : [
    "slide_000.jpg",
    "slide_002.jpg"
  ],
  "source" : "slides",
  "still" : "slide_000.jpg",
  "timing" : {
    "fade" : 1.5,
    "interval" : 10,
    "zoom" : 0.125,
    "zoomInterval" : 7,
    "zoomMove" : 4
  },
  "version" : 1
}"""

HAND_UUID = "0E7A3C5B-2D41-4F6A-9B8C-1D2E3F405162"
HAND_JSON = ('{"version":1,"still":"still.png","source":"preview","name":"Hand Written",'
             '"importer":1,"digest":"abcd","added":"2026-10-25T12:00:00Z"}')

EXPECTED = {
    "L1": "folder=true json=true still=true listed=true staging=false",
    "L2": "same=true added=true importer=2 still=still.png oldstill=false backups=0 count=1",
    "L3": "first=true last=C tie=true sorted=true",
    "L4": ("nojson=hidden badjson=hidden v2=hidden dotdot=hidden slash=hidden empty=hidden missing=hidden "
           "notauuid=hidden lower=hidden import=hidden old=hidden file=hidden listed=1"),
    "L5": "refused=5/5 unchanged=true removed=true others=true",
    "L6": "import=gone oldWithLive=gone live=kept restored=true others=unchanged",
    "L7": "threw=true unchanged=true",
    "L8": "from=not-theme,damaged,no-picture,dynamic-only",
    "L9": ("trim=Cherry Blossoms|bidi=ABC|long=256/128|emoji=253|file=15589-CherryBlossoms|upper=Foo|"
           "dotonly=PS3 theme|onlybidi=PS3 theme"),
    "L10": "hd=Picture/picture sd=Picture/picture preview=Preview picture/preview",
    "L11": "decoded=true name=Hand Written source=preview still=still.png added=1792929600",
    "L12": "roundtrip=true old=true oldslides=nil oldtiming=nil",
    "L13": "two=Slideshow/slideshow one=Picture/picture",
    "L14": ("two=slideshow/none three=slideshow/none urls=2 missing=still/failed unplain=still/failed "
            "notiming=still/failed infinite=still/failed folder=still/failed one=still/none hd=still/none"),
    "L15": "level=3",
    "L16": ("kind=Live/live live=live/none noscene=still/failed missing=still/failed unplain=still/failed "
            "folder=still/failed nostill=still/failed roundtrip=true"),
    "L17": "armed=true took=true marker=false blocked=true cleared=true disarmed=true none=true",
    "L18": "kept=true listed=1 junk=0/3 good=true junkTaken=nil junkAdded=false junkArm=false",
}

DRIVER = r"""import Foundation

let fm = FileManager.default
let base = URL(fileURLWithPath: @@BASE@@)
let root = base.appendingPathComponent("root", isDirectory: true)
let lib = ThemeLibrary(root: root)
let t1 = Date(timeIntervalSince1970: 1_800_000_000)
let t2 = Date(timeIntervalSince1970: 1_800_000_600)

func clear() {
    try? fm.removeItem(at: root)
    try! fm.createDirectory(at: root, withIntermediateDirectories: true)
}
func names() -> [String] { ((try? fm.contentsOfDirectory(atPath: root.path)) ?? []).sorted() }
func exists(_ url: URL) -> Bool { fm.fileExists(atPath: url.path) }
// Every path under `base` with its size, sorted: a byte-identical listing means nothing was touched.
func snapshot() -> [String] {
    var out: [String] = []
    let walker = fm.enumerator(atPath: base.path)!
    while let path = walker.nextObject() as? String {
        let attributes = try! fm.attributesOfItem(atPath: base.appendingPathComponent(path).path)
        out.append("\(path) \(attributes[.size] as? Int ?? -1)")
    }
    return out.sorted()
}
func meta(_ digest: String, name: String = "A", still: String = "still.jpg", source: ThemeMeta.Source = .hd,
          importer: Int = 1, added: Date = t1, version: Int = 1) -> ThemeMeta {
    ThemeMeta(version: version, name: name, added: added, importer: importer, digest: digest, source: source, still: still)
}
func stage(_ still: String = "still.jpg") -> URL {
    let staging = try! lib.makeStaging()
    try! Data([0xFF, 0xD8, 0xFF, 0xD9]).write(to: staging.appendingPathComponent(still))
    return staging
}
let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; return e }()
// A theme folder written by hand, bypassing install, for the cases install would never write.
func handFolder(_ name: String, json: Data?, still: String? = "still.jpg") {
    let folder = root.appendingPathComponent(name, isDirectory: true)
    try! fm.createDirectory(at: folder, withIntermediateDirectories: true)
    if let json = json { try! json.write(to: folder.appendingPathComponent("theme.json")) }
    if let still = still { try! Data([1, 2, 3]).write(to: folder.appendingPathComponent(still)) }
}
func json(_ m: ThemeMeta) -> Data { try! encoder.encode(m) }
func word(_ hidden: Bool) -> String { hidden ? "hidden" : "listed" }

// L1
clear()
let s1 = stage()
let e1 = try! lib.install(staging: s1, meta: meta("d1"))
print("L1 folder=\(ThemeLibrary.isThemeFolderName(e1.folderName)) json=\(exists(e1.folder.appendingPathComponent("theme.json"))) still=\(exists(e1.stillURL)) listed=\(lib.list().map { $0.folderName } == [e1.folderName]) staging=\(exists(s1))")

// L2
let e2 = try! lib.install(staging: stage("still.png"), meta: meta("d1", still: "still.png", importer: 2, added: t2))
print("L2 same=\(e2.folderName == e1.folderName) added=\(e2.meta.added == e1.meta.added) importer=\(e2.meta.importer) still=\(e2.meta.still) oldstill=\(exists(e1.folder.appendingPathComponent("still.jpg"))) backups=\(names().filter { $0.hasPrefix(".old-theme-") }.count) count=\(lib.list().count)")

// L3
clear()
_ = try! lib.install(staging: stage(), meta: meta("d3c", name: "C", added: t2))
_ = try! lib.install(staging: stage(), meta: meta("d3a", name: "A", added: t1))
_ = try! lib.install(staging: stage(), meta: meta("d3b", name: "B", added: t1))
let l3 = lib.list()
let sortedL3 = zip(l3, l3.dropFirst()).allSatisfy { ($0.meta.added, $0.folderName) <= ($1.meta.added, $1.folderName) }
print("L3 first=\(l3[0].meta.added == t1) last=\(l3[2].meta.name) tie=\(l3[0].folderName < l3[1].folderName) sorted=\(sortedL3)")

// L4
clear()
_ = try! lib.install(staging: stage(), meta: meta("good"))
func u() -> String { UUID().uuidString }
var cases: [(String, String)] = []
func hand(_ word: String, _ name: String, json: Data?, still: String? = "still.jpg") {
    handFolder(name, json: json, still: still)
    cases.append((word, name))
}
hand("nojson", "theme-" + u(), json: nil)
hand("badjson", "theme-" + u(), json: Data("{not json".utf8))
hand("v2", "theme-" + u(), json: json(meta("v2", version: 2)))
hand("dotdot", "theme-" + u(), json: json(meta("dd", still: "../x")), still: nil)
hand("slash", "theme-" + u(), json: json(meta("sl", still: "a/b")), still: nil)
hand("empty", "theme-" + u(), json: json(meta("em", still: "")), still: nil)
hand("missing", "theme-" + u(), json: json(meta("mi", still: "missing.jpg")), still: nil)
hand("notauuid", "theme-notauuid", json: json(meta("na")))
hand("lower", "theme-" + u().lowercased(), json: json(meta("lo")))
hand("import", ".import-" + u(), json: json(meta("im")))
hand("old", ".old-theme-" + u(), json: json(meta("ol")))
let plainFile = "theme-" + u()
try! Data("x".utf8).write(to: root.appendingPathComponent(plainFile))
cases.append(("file", plainFile))
let listedL4 = lib.list()
let words = cases.map { c in "\(c.0)=\(word(lib.entry(named: c.1) == nil && !listedL4.contains { $0.folderName == c.1 }))" }
print("L4 \(words.joined(separator: " ")) listed=\(listedL4.count)")

// L5
clear()
try! Data("keep".utf8).write(to: base.appendingPathComponent("sentinel.txt"))
let keep = try! lib.install(staging: stage(), meta: meta("k5"))
let gone = try! lib.install(staging: stage(), meta: meta("g5"))
handFolder(".import-" + u(), json: nil)
handFolder("theme-notauuid", json: nil)
let before5 = snapshot()
let refusedNames = ["..", "../x", names().first { $0.hasPrefix(".import-") }!, "theme-notauuid", ""]
var refused5 = 0
for name in refusedNames { do { try lib.remove(named: name) } catch { refused5 += 1 } }
let unchanged5 = snapshot() == before5
try! lib.remove(named: gone.folderName)
let after5 = snapshot()
// The root folder's own size counts its entries on APFS, so its line is left out of this comparison.
let others5 = after5.filter { !$0.hasPrefix("root ") } == before5.filter { !$0.hasPrefix("root/" + gone.folderName) && !$0.hasPrefix("root ") }
print("L5 refused=\(refused5)/\(refusedNames.count) unchanged=\(unchanged5) removed=\(!exists(gone.folder) && exists(keep.folder)) others=\(others5)")

// L6
clear()
let stagingName = ".import-" + u()
handFolder(stagingName, json: nil)
let live = try! lib.install(staging: stage(), meta: meta("live"))
let liveBackup = ".old-theme-" + String(live.folderName.dropFirst("theme-".count))
handFolder(liveBackup, json: nil)
let orphanID = u()
handFolder(".old-theme-" + orphanID, json: json(meta("orphan")))
try! Data("k".utf8).write(to: root.appendingPathComponent("keep.jpg"))
let backgroundFile = u() + "_imported.png"
try! Data("b".utf8).write(to: root.appendingPathComponent(backgroundFile))
handFolder(".import-notauuid", json: nil)
handFolder("other", json: nil)
let others6 = ["keep.jpg", backgroundFile, ".import-notauuid", "other"]
let before6 = snapshot().filter { line in others6.contains { line.hasPrefix("root/" + $0) } }
lib.sweep()
let after6 = names()
let othersSame = snapshot().filter { line in others6.contains { line.hasPrefix("root/" + $0) } } == before6
print("L6 import=\(after6.contains(stagingName) ? "kept" : "gone") oldWithLive=\(after6.contains(liveBackup) ? "kept" : "gone") live=\(lib.entry(named: live.folderName) != nil ? "kept" : "gone") restored=\(after6.contains("theme-" + orphanID) && !after6.contains(".old-theme-" + orphanID)) others=\(othersSame && others6.allSatisfy { after6.contains($0) } ? "unchanged" : "changed")")

// L7
clear()
let before7 = snapshot()
var threw7 = false
do { _ = try lib.install(staging: root.appendingPathComponent(".import-" + u()), meta: meta("m7")) } catch { threw7 = true }
print("L7 threw=\(threw7) unchanged=\(snapshot() == before7)")

// L8
let froms: [P3tTheme.Failure] = [.notATheme, .damaged, .noPicture, .dynamicOnly]
print("L8 from=\(froms.map { ThemeImportFailure.from($0).rawValue }.joined(separator: ","))")
for failure in [ThemeImportFailure.notTheme, .damaged, .noPicture, .dynamicOnly, .readFailed, .writeFailed] {
    print("L8m-\(failure.rawValue) \(failure.message)")
}
print("L8r \(ThemeLibrary.removeFailedMessage)")

// L9
let long = ThemeLibrary.displayName(theme: String(repeating: "\u{E9}", count: 200), fileName: "x.p3t")
let emoji = ThemeLibrary.displayName(theme: "a" + String(repeating: "\u{1F600}", count: 100), fileName: "x.p3t")
let l9 = [
    "trim=" + ThemeLibrary.displayName(theme: "Cherry Blossoms\u{0}\u{0}  ", fileName: "x.p3t"),
    "bidi=" + ThemeLibrary.displayName(theme: "A\u{202E}B\u{7}C", fileName: "x.p3t"),
    "long=\(long.utf8.count)/\(long.count)",
    "emoji=\(emoji.utf8.count)",
    "file=" + ThemeLibrary.displayName(theme: nil, fileName: "15589-CherryBlossoms.p3t"),
    "upper=" + ThemeLibrary.displayName(theme: "", fileName: "Foo.P3T"),
    "dotonly=" + ThemeLibrary.displayName(theme: nil, fileName: ".p3t"),
    "onlybidi=" + ThemeLibrary.displayName(theme: "\u{202E}", fileName: ""),
]
print("L9 " + l9.joined(separator: "|"))

// L10
let k = [ThemeMeta.Source.hd, .sd, .preview].map { s -> String in
    let m = meta("k", source: s)
    return "\(s.rawValue)=\(m.kindWord)/\(m.witnessKind)"
}
print("L10 " + k.joined(separator: " "))

// L11
clear()
let stored = try! lib.install(staging: stage(), meta: ThemeMeta(version: 1, name: "Cherry Blossoms", added: t1,
    importer: 1, digest: "9f2c", source: .hd, still: "still.jpg"))
let text = String(decoding: try! Data(contentsOf: stored.folder.appendingPathComponent("theme.json")), as: UTF8.self)
print("L11JSON " + text.replacingOccurrences(of: "\n", with: "\\n"))
let handEntry = ThemeLibrary(root: base.appendingPathComponent("hand", isDirectory: true)).entry(named: "theme-@@HAND@@")
print("L11 decoded=\(handEntry != nil) name=\(handEntry?.meta.name ?? "-") source=\(handEntry?.meta.source.rawValue ?? "-") still=\(handEntry?.meta.still ?? "-") added=\(Int(handEntry?.meta.added.timeIntervalSince1970 ?? -1))")

// L12
clear()
let timing12 = ThemeTiming(interval: 10, fade: 1.5, zoom: 0.125, zoomInterval: 7, zoomMove: 4)
let s12 = stage("slide_000.jpg")
try! Data([0xFF, 0xD8, 0xFF, 0xD9]).write(to: s12.appendingPathComponent("slide_002.jpg"))
let m12 = ThemeMeta(version: 1, name: "Return To Serenity", added: t1, importer: 2, digest: "5e7d", source: .slides,
                    still: "slide_000.jpg", slides: ["slide_000.jpg", "slide_002.jpg"], timing: timing12)
let e12 = try! lib.install(staging: s12, meta: m12)
let text12 = String(decoding: try! Data(contentsOf: e12.folder.appendingPathComponent("theme.json")), as: UTF8.self)
print("L12JSON " + text12.replacingOccurrences(of: "\n", with: "\\n"))
print("L12 roundtrip=\(lib.entry(named: e12.folderName)?.meta == m12) old=\(handEntry != nil) oldslides=\(handEntry?.meta.slides == nil ? "nil" : "set") oldtiming=\(handEntry?.meta.timing == nil ? "nil" : "set")")

// L13
func slidesMeta(_ names: [String], timing: ThemeTiming? = timing12, still: String = "slide_000.jpg") -> ThemeMeta {
    ThemeMeta(version: 1, name: "S", added: t1, importer: 2, digest: "s", source: .slides, still: still,
              slides: names, timing: timing)
}
let two13 = slidesMeta(["slide_000.jpg", "slide_001.jpg"])
let one13 = slidesMeta(["slide_000.jpg"])
print("L13 two=\(two13.kindWord)/\(two13.witnessKind) one=\(one13.kindWord)/\(one13.witnessKind)")

// L14
clear()
func play(_ m: ThemeMeta, files: [String], folders: [String] = []) -> String {
    let folder = root.appendingPathComponent("theme-" + u(), isDirectory: true)
    try! fm.createDirectory(at: folder, withIntermediateDirectories: true)
    for f in files { try! Data([1]).write(to: folder.appendingPathComponent(f)) }
    for d in folders { try! fm.createDirectory(at: folder.appendingPathComponent(d), withIntermediateDirectories: true) }
    let p = ThemeEntry(folderName: folder.lastPathComponent, folder: folder, meta: m).playback()
    return "\(p.mode == .slideshow ? "slideshow" : "still")/\(p.reason)"
}
let threeMeta = slidesMeta(["slide_000.jpg", "slide_001.jpg", "slide_002.jpg"])
let threeFolder = root.appendingPathComponent("theme-" + u(), isDirectory: true)
try! fm.createDirectory(at: threeFolder, withIntermediateDirectories: true)
for f in ["slide_000.jpg", "slide_002.jpg"] { try! Data([1]).write(to: threeFolder.appendingPathComponent(f)) }
let threeEntry = ThemeEntry(folderName: threeFolder.lastPathComponent, folder: threeFolder, meta: threeMeta)
let l14 = [
    "two=" + play(two13, files: ["slide_000.jpg", "slide_001.jpg"]),
    "three=" + play(threeMeta, files: ["slide_000.jpg", "slide_002.jpg"]),
    "urls=\(threeEntry.slideURLs.count)",
    "missing=" + play(two13, files: ["slide_000.jpg"]),
    "unplain=" + play(slidesMeta(["slide_000.jpg", "../slide_001.jpg", "slide_002.jpg"]),
                      files: ["slide_000.jpg", "slide_002.jpg"]),
    "notiming=" + play(slidesMeta(["slide_000.jpg", "slide_001.jpg"], timing: nil), files: ["slide_000.jpg", "slide_001.jpg"]),
    "infinite=" + play(slidesMeta(["slide_000.jpg", "slide_001.jpg"],
                                  timing: ThemeTiming(interval: .infinity, fade: 1.5, zoom: 0, zoomInterval: 0, zoomMove: 0)),
                       files: ["slide_000.jpg", "slide_001.jpg"]),
    "folder=" + play(two13, files: ["slide_000.jpg"], folders: ["slide_001.jpg"]),
    "one=" + play(one13, files: ["slide_000.jpg"]),
    "hd=" + play(meta("h"), files: ["still.jpg"]),
]
print("L14 " + l14.joined(separator: " "))

// L15
print("L15 level=\(ThemeMeta.importerLevel)")

// L16
clear()
func liveMeta(_ scene: String?, still: String = "still.jpg") -> ThemeMeta {
    ThemeMeta(version: 1, name: "Heavy Rain", added: t1, importer: 3, digest: "l16", source: .live, still: still,
              scene: scene)
}
func livePlay(_ m: ThemeMeta, files: [String], folders: [String] = []) -> String {
    let folder = root.appendingPathComponent("theme-" + u(), isDirectory: true)
    try! fm.createDirectory(at: folder, withIntermediateDirectories: true)
    for f in files { try! Data([1]).write(to: folder.appendingPathComponent(f)) }
    for d in folders { try! fm.createDirectory(at: folder.appendingPathComponent(d), withIntermediateDirectories: true) }
    let p = ThemeEntry(folderName: folder.lastPathComponent, folder: folder, meta: m).playback()
    return "\(p.mode == .live ? "live" : (p.mode == .slideshow ? "slideshow" : "still"))/\(p.reason)"
}
let live16 = liveMeta("scene.raf")
let s16 = stage()
try! Data([0x52, 0x41, 0x46, 0x30]).write(to: s16.appendingPathComponent("scene.raf"))
let e16 = try! lib.install(staging: s16, meta: live16)
let l16 = [
    "kind=\(live16.kindWord)/\(live16.witnessKind)",
    "live=" + livePlay(live16, files: ["scene.raf", "still.jpg"]),
    "noscene=" + livePlay(liveMeta(nil), files: ["scene.raf", "still.jpg"]),
    "missing=" + livePlay(live16, files: ["still.jpg"]),
    "unplain=" + livePlay(liveMeta("../scene.raf"), files: ["scene.raf", "still.jpg"]),
    "folder=" + livePlay(live16, files: ["still.jpg"], folders: ["scene.raf"]),
    "nostill=" + livePlay(live16, files: ["scene.raf"]),
    "roundtrip=\(lib.entry(named: e16.folderName)?.meta == live16)",
]
print("L16 " + l16.joined(separator: " "))

// L17
clear()
let e17 = try! lib.install(staging: stage(), meta: meta("l17"))
let marker = root.appendingPathComponent(".live-armed")
lib.armLive(e17.folderName)
let armed17 = exists(marker)
let launched = ThemeLibrary(root: root)
let took17 = launched.takeArmedOnLaunch() == e17.folderName
let marker17 = exists(marker)
let blocked17 = lib.isLiveBlocked(e17.folderName)
lib.clearLiveBlock(e17.folderName)
let cleared17 = !lib.isLiveBlocked(e17.folderName)
lib.armLive(e17.folderName)
lib.disarmLive()
let disarmed17 = !exists(marker)
let none17 = ThemeLibrary(root: root).takeArmedOnLaunch() == nil && !lib.isLiveBlocked(e17.folderName)
print("L17 armed=\(armed17) took=\(took17) marker=\(marker17) blocked=\(blocked17) cleared=\(cleared17) disarmed=\(disarmed17) none=\(none17)")

// L18
clear()
let e18 = try! lib.install(staging: stage(), meta: meta("l18"))
let blockedFile = root.appendingPathComponent(".live-blocked")
let junk = ["../x", "theme-notauuid", "theme-" + u().lowercased()]
try! Data((junk + [e18.folderName]).map { $0 + "\n" }.joined().utf8).write(to: blockedFile)
try! Data(e18.folderName.utf8).write(to: marker)
let before18 = snapshot().filter { $0.hasPrefix("root/.live-") }
let markerBytes = try! Data(contentsOf: marker), blockedBytes = try! Data(contentsOf: blockedFile)
lib.sweep()
let listed18 = lib.list().count
let kept18 = snapshot().filter { $0.hasPrefix("root/.live-") } == before18 && before18.count == 2
    && (try? Data(contentsOf: marker)) == markerBytes && (try? Data(contentsOf: blockedFile)) == blockedBytes
let junkBlocked = junk.filter { lib.isLiveBlocked($0) }.count
let good18 = lib.isLiveBlocked(e18.folderName)
try! Data("../x".utf8).write(to: marker)
let junkTaken = lib.takeArmedOnLaunch()
let junkAdded = (try? Data(contentsOf: blockedFile)) != blockedBytes
lib.armLive("../x")
print("L18 kept=\(kept18) listed=\(listed18) junk=\(junkBlocked)/\(junk.count) good=\(good18) junkTaken=\(junkTaken ?? "nil") junkAdded=\(junkAdded) junkArm=\(exists(marker))")
"""


def library_problems(code_raw):
    """code_raw: ThemeLibrary.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: ThemeLibrary.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: ThemeLibrary.swift logs")
    spans = []
    for signature in REMOVERS:
        match = re.search(signature, code)
        if match is None:
            problems.append("remove: ThemeLibrary.swift has no `{0}`".format(signature))
            continue
        body = body_of(code, signature)
        start = code.index(body, match.start())
        spans.append((start, start + len(body)))
    for match in re.finditer(r"removeItem\(", code):
        if not any(lo <= match.start() < hi for lo, hi in spans):
            problems.append("remove: a removeItem( outside remove(named:), sweep() and install(staging:meta:)")
    armed = body_of(code, ARMED_REMOVER) or ""
    if "Self.armedName" not in armed or armed.count("removeItem(") != 1:
        problems.append("armed: removeArmedMarker does not remove exactly the armed marker")
    for signature in (r"func\s+entry\s*\(\s*named", r"func\s+install\s*\(\s*staging"):
        body = body_of(code, signature) or ""
        if "isPlainName(meta.still)" not in body:
            problems.append("plain: `{0}` does not check the stored still with isPlainName".format(signature))
    return problems


class TheLibraryHolds(unittest.TestCase):
    """L1-L18 over folders in a temporary directory; one exact line per case."""

    @classmethod
    def setUpClass(cls):
        for path in SOURCES:
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "lib"
            hand = base / "hand" / ("theme-" + HAND_UUID)
            hand.mkdir(parents=True)
            (hand / "theme.json").write_text(HAND_JSON, encoding="utf-8")
            (hand / "still.png").write_bytes(b"\x89PNG")
            driver = DRIVER.replace("@@BASE@@", json.dumps(str(base))).replace("@@HAND@@", HAND_UUID)
            out = compile_and_run(SOURCES, driver, tmp)
        cls.lines = {}
        for text in out.splitlines():
            if text.strip():
                key, _, rest = text.partition(" ")
                cls.lines[key] = rest

    def check(self, key):
        self.assertEqual(EXPECTED[key], self.lines.get(key))

    def test_l1_install_new(self):
        self.check("L1")

    def test_l2_reinstall_updates_in_place(self):
        self.check("L2")

    def test_l3_oldest_first(self):
        self.check("L3")

    def test_l4_unreadable_folders_hidden(self):
        self.check("L4")

    def test_l5_remove_only_theme_folders(self):
        self.check("L5")

    def test_l6_sweep(self):
        self.check("L6")

    def test_l7_missing_staging_throws(self):
        self.check("L7")

    def test_l8_failures_and_messages(self):
        self.check("L8")
        for raw, sentence in MESSAGES.items():
            with self.subTest(raw):
                self.assertEqual(sentence, self.lines.get("L8m-" + raw))
        self.assertEqual(REMOVE_FAILED, self.lines.get("L8r"))

    def test_l9_display_name(self):
        self.check("L9")

    def test_l10_kind_words(self):
        self.check("L10")

    def test_l11_stored_format(self):
        self.assertEqual(THEME_JSON, (self.lines.get("L11JSON") or "").replace("\\n", "\n"))
        self.check("L11")

    def test_l12_slides_and_timing_round_trip(self):
        self.assertEqual(SLIDES_JSON, (self.lines.get("L12JSON") or "").replace("\\n", "\n"))
        self.check("L12")

    def test_l13_slideshow_kind_words(self):
        self.check("L13")

    def test_l14_playback(self):
        self.check("L14")

    def test_l15_importer_level(self):
        self.check("L15")

    def test_l16_live_source_and_playback(self):
        self.check("L16")

    def test_l17_armed_marker(self):
        self.check("L17")

    def test_l18_marker_files_survive_and_junk_is_ignored(self):
        self.check("L18")


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], library_problems(read(LIBRARY)))

    def test_messages_appear_verbatim(self):
        raw = read(LIBRARY)
        for sentence in list(MESSAGES.values()) + [REMOVE_FAILED]:
            with self.subTest(sentence):
                self.assertIn('"' + sentence + '"', raw)

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(LIBRARY)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = library_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { NSLog(s) }")

    def test_mutation_remove_outside(self):
        self.flagged("remove", "func list() -> [ThemeEntry] {",
                     "func list() -> [ThemeEntry] {\n        try? FileManager.default.removeItem(at: root)")

    def test_mutation_armed_marker_widened(self):
        self.flagged("armed", "removeItem(at: root.appendingPathComponent(Self.armedName, isDirectory: false))",
                     "removeItem(at: root)")

    def test_mutation_plain_check_dropped(self):
        self.flagged("plain", "(1 ... ThemeMeta.currentVersion).contains(meta.version),\n              Self.isPlainName(meta.still)",
                     "(1 ... ThemeMeta.currentVersion).contains(meta.version)")


if __name__ == "__main__":
    unittest.main()
