#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""BookmarkStore.swift, run on the Mac host against a fake BookmarkIO, starts access once per folder, re-creates stale
bookmarks while access is held, stops access only on removal and keeps one folders.plist.
It uses no real bookmark or security scope, so it says nothing about iOS."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
STORE = IOS / "app" / "Library" / "BookmarkStore.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
DOCUMENTS = re.compile(r"\bdocumentDirectory\b|\bDocuments\b")
RESOLVE = re.compile(r"resolvingBookmarkData:\s*\w+\s*,\s*options:\s*\[\]")

# Written into a temporary directory at run time; fake bookmark bytes are "<path>#<version>" so the driver sees what was saved.
DRIVER = r"""
import Foundation

final class FakeIO: BookmarkIO {
    var log: [String] = []
    var stale: Set<String> = []
    var broken: Set<String> = []
    var refused: Set<String> = []
    var unreadable: Set<String> = []
    var version = 1
    private var open: Set<String> = []

    func makeBookmark(_ url: URL) throws -> Data {
        log.append("make:\(url.lastPathComponent):held=\(open.contains(url.path))")
        return Data("\(url.path)#\(version)".utf8)
    }
    func resolve(_ data: Data) throws -> (url: URL, stale: Bool) {
        let path = String(String(decoding: data, as: UTF8.self).split(separator: "#")[0])
        let url = URL(fileURLWithPath: path)
        log.append("resolve:\(url.lastPathComponent)")
        if broken.contains(url.lastPathComponent) { throw CocoaError(.fileNoSuchFile) }
        return (url, stale.contains(url.lastPathComponent))
    }
    func startAccess(_ url: URL) -> Bool {
        log.append("start:\(url.lastPathComponent)")
        if refused.contains(url.lastPathComponent) { return false }
        open.insert(url.path)
        return true
    }
    func isReadable(_ url: URL) -> Bool { !unreadable.contains(url.lastPathComponent) }
    func stopAccess(_ url: URL) {
        log.append("stop:\(url.lastPathComponent)")
        open.remove(url.path)
    }
    func count(_ prefix: String) -> Int { log.filter { $0.hasPrefix(prefix) }.count }
}

let root = URL(fileURLWithPath: @@TMP@@)
let dir = root.appendingPathComponent("armsx3", isDirectory: true)
let alpha = root.appendingPathComponent("alpha"), beta = root.appendingPathComponent("beta"),
    gamma = root.appendingPathComponent("gamma")

@MainActor func bytes(_ store: BookmarkStore, _ id: UUID) -> String {
    String(decoding: store.folders.first { $0.id == id }!.bookmark, as: UTF8.self)
}

MainActor.assumeIsolated {
    let io1 = FakeIO()
    let s1 = BookmarkStore(directory: dir, io: io1)
    let a = try! s1.add(alpha), b = try! s1.add(beta), c = try! s1.add(gamma)
    let heldAtMake = io1.log.filter { $0.hasPrefix("make:") }.allSatisfy { $0.hasSuffix("held=true") }
    print("B1 start=\(io1.count("start:")) stop=\(io1.count("stop:")) make=\(io1.count("make:")) heldAtMake=\(heldAtMake) folders=\(s1.folders.count)")

    let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path))?.sorted() ?? []
    print("B6 files=\(names.joined(separator: ","))")

    let io2 = FakeIO()
    let s2 = BookmarkStore(directory: dir, io: io2)
    let r2 = s2.resolveAll()
    let again = s2.resolveAll()
    let paths = r2.map { $0.1?.lastPathComponent ?? "-" }.joined(separator: ",")
    print("B2 paths=\(paths) start=\(io2.count("start:")) stop=\(io2.count("stop:")) again=\(again.filter { $0.1 != nil }.count)")

    let io3 = FakeIO()
    io3.stale = ["beta"]
    io3.version = 2
    _ = BookmarkStore(directory: dir, io: io3).resolveAll()
    let s3 = BookmarkStore(directory: dir, io: FakeIO())
    print("B3 remade=\(io3.log.filter { $0.hasPrefix("make:") }.joined(separator: ",")) saved=\(bytes(s3, b.id).hasSuffix("#2")) kept=\(bytes(s3, a.id).hasSuffix("#1") && bytes(s3, c.id).hasSuffix("#1"))")

    let io4 = FakeIO()
    io4.broken = ["gamma"]
    let s4 = BookmarkStore(directory: dir, io: io4)
    let r4 = s4.resolveAll().map { "\($0.0.displayName)=\($0.1 == nil ? "unavailable" : "ok")" }
    print("B4 \(r4.joined(separator: " ")) start=\(io4.count("start:"))")

    s4.remove(b.id)
    s4.remove(c.id)
    let s5 = BookmarkStore(directory: dir, io: FakeIO())
    print("B5 stops=\(io4.log.filter { $0.hasPrefix("stop:") }.joined(separator: ",")) left=\(s5.folders.map(\.displayName).joined(separator: ","))")

    let io7 = FakeIO()
    io7.refused = ["alpha"]
    let s7 = BookmarkStore(directory: dir, io: io7)
    let readable = s7.resolveAll().map { "\($0.0.displayName)=\($0.1 == nil ? "unavailable" : "ok")" }
    s7.remove(a.id)
    let io8 = FakeIO()
    io8.refused = ["beta"]
    io8.unreadable = ["beta"]
    let s8 = BookmarkStore(directory: dir, io: io8)
    let b8 = try! s8.add(beta)
    let gone = BookmarkStore(directory: dir, io: io8).resolveAll().map { "\($0.0.displayName)=\($0.1 == nil ? "unavailable" : "ok")" }
    print("B7 \(readable.joined(separator: " ")) stops=\(io7.count("stop:")) refused-unreadable \(gone.joined(separator: " ")) added=\(b8.displayName)")
}
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def store_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: BookmarkStore.swift imports {0}; it must import Foundation only".format(imports))
    if "withSecurityScope" in code:
        problems.append("scope: BookmarkStore.swift names withSecurityScope, which iOS does not have")
    if '"folders.plist"' not in code:
        problems.append("file: BookmarkStore.swift does not name folders.plist")
    if DOCUMENTS.search(code):
        problems.append("documents: BookmarkStore.swift names a Documents directory (V8)")
    if "bookmarkData(options: []" not in code:
        problems.append("foundation: no bookmarkData(options: []) call")
    if not RESOLVE.search(code):
        problems.append("foundation: no URL(resolvingBookmarkData: ..., options: []) call")
    for call in ("startAccessingSecurityScopedResource()", "stopAccessingSecurityScopedResource()"):
        if call not in code:
            problems.append("foundation: FoundationBookmarkIO never calls {0}".format(call))
    if LOGGING.search(code):
        problems.append("log: BookmarkStore.swift logs; no folder path or bookmark byte may be logged")
    return problems


class TheStoreHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(STORE)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([STORE], DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_b1_add_starts_access_once_and_keeps_it(self):
        self.check("B1", "B1 start=3 stop=0 make=3 heldAtMake=true folders=3")

    def test_b2_a_fresh_store_resolves_all_starting_once_each(self):
        self.check("B2", "B2 paths=alpha,beta,gamma start=3 stop=0 again=3")

    def test_b3_a_stale_bookmark_is_remade_while_held_and_saved(self):
        self.check("B3", "B3 remade=make:beta:held=true saved=true kept=true")

    def test_b4_one_unresolvable_folder_is_unavailable_the_rest_resolve(self):
        self.check("B4", "B4 alpha=ok beta=ok gamma=unavailable start=2")

    def test_b5_remove_stops_that_folder_only_and_deletes_it(self):
        self.check("B5", "B5 stops=stop:beta left=alpha")

    def test_b6_the_only_file_is_folders_plist_in_the_given_directory(self):
        self.check("B6", "B6 files=folders.plist")

    def test_b7_a_readable_folder_without_scoped_access_is_available(self):
        self.check("B7", "B7 alpha=ok stops=0 refused-unreadable beta=unavailable added=beta")


class TheStoreFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], store_problems(read(STORE)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(STORE.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class TheStoreGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(STORE)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = store_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_scope_the_macos_option(self):
        self.assert_names(self.edited(self.text, "bookmarkData(options: []", "bookmarkData(options: [.withSecurityScope]"), "scope")

    def test_mutation_file_renamed(self):
        self.assert_names(self.edited(self.text, '"folders.plist"', '"folders.json"'), "file")

    def test_mutation_documents_directory(self):
        self.assert_names(self.text + "\nlet probe = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)\n", "documents")

    def test_mutation_foundation_stop_never_called(self):
        self.assert_names(self.edited(self.text, "url.stopAccessingSecurityScopedResource()", "_ = url"), "foundation")

    def test_mutation_log_a_path(self):
        self.assert_names(self.text + "\nfunc probe(_ u: URL) { NSLog(\"%@\", u.path) }\n", "log")


if __name__ == "__main__":
    unittest.main(verbosity=2)
