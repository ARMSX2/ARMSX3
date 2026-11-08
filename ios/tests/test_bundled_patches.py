#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""BundledPatches.swift compiled and run on the Mac host against a fake bridge: first install,
next launch, restored file, upgrade from an older revision, Killzone retries, no YAML, failed write.
Its patch names must match the bundled YAML asset."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


ROOT = Path(__file__).resolve().parents[2]

SWIFT = ROOT / "ios" / "app" / "Settings" / "BundledPatches.swift"

ASSET_REL = "android/armsx3-ui/app/src/main/assets/canary_patches.yml"

ASSET = ROOT / ASSET_REL

KILLZONE_HASHES = {"PPU-ae204e2198c9a051a44a69913c48f6591b811082", "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387"}


def read(path):
    return Path(path).read_text(encoding="utf-8")


def swift_entries(text):
    return re.findall(r'Entry\(hash: "([^"]+)", name: "([^"]+)", serial: "([^"]+)", appVersion: "([^"]+)", '
                      r'sinceRevision: (\d+)\)', text)


def yaml_patches(text):
    """{hash: {patch names}} from the top-level hash keys and their two-space-indented names, quoted or not."""
    out, current = {}, None
    for line in text.splitlines():
        top = re.match(r"^((?:PPU|SPU)-[0-9a-f]{40}):", line)
        if top:
            current = out.setdefault(top.group(1), set())
            continue
        if line and not line.startswith((" ", "#")):
            current = None
        name = re.match(r'^  (?:"([^"]+)"|([^\s"#][^:]*)):\s*$', line)
        if name and current is not None:
            current.add(name.group(1) or name.group(2))
    return out


def yaml_problems(swift, yaml):
    problems = []
    patches = yaml_patches(yaml)
    absent = set()
    for h, name, serial, _, _ in swift_entries(swift):
        if h not in patches:
            absent.add(h)
        elif name not in patches[h]:
            problems.append("yaml: {0} ({1}) names `{2}`, which is not under that hash".format(h, serial, name))
    if absent != KILLZONE_HASHES:
        problems.append("yaml: hashes outside the bundled YAML are {0}, not Killzone 3's two".format(sorted(absent)))
    return problems


DRIVER = r"""
import Foundation

nonisolated(unsafe) var configDir = ""
nonisolated(unsafe) var writes = 0
nonisolated(unsafe) var writeStatus = 0
nonisolated(unsafe) var enables: [String] = []
nonisolated(unsafe) var unknownHashes: Set<String> = []

enum Rpcs3Bridge {
    static func configDirectory() async -> (String, Int) { (configDir, 0) }
    static func writePatchFile(_ name: String, yaml: String) async -> Int {
        writes += 1
        if writeStatus == 0 {
            try? FileManager.default.createDirectory(atPath: configDir + "patches", withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: configDir + "patches/" + name, contents: Data(yaml.utf8))
        }
        return writeStatus
    }
    static func setPatchEnabled(_ on: Bool, hash: String, patchDescription: String, serial: String,
                                appVersion: String) async -> Int {
        if unknownHashes.contains(hash) { return 3 }
        enables.append(hash + "/" + serial + "/" + patchDescription)
        return 0
    }
}

func run(_ label: String, _ defaults: UserDefaults, yaml: String? = "Version: 1.2\n") async {
    writes = 0
    enables = []
    let r = await BundledPatches.ensure(yaml: yaml, defaults: defaults)
    let stored = defaults.integer(forKey: BundledPatches.revisionKey)
    let pending = (defaults.stringArray(forKey: BundledPatches.enabledKey) ?? []).count
    print("\(label) status=\(r.status) enabled=\(r.enabled) failed=\(r.failed) writes=\(writes) calls=\(enables.count) unique=\(Set(enables).count) stored=\(stored) remembered=\(pending)")
}

func fresh(_ name: String) -> UserDefaults {
    let d = UserDefaults(suiteName: name)!
    d.removePersistentDomain(forName: name)
    return d
}

let base = CommandLine.arguments[1]
let killzone: Set<String> = ["PPU-ae204e2198c9a051a44a69913c48f6591b811082", "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387"]
let all = BundledPatches.entries.count
let kz = BundledPatches.entries.filter { killzone.contains($0.hash) }.count
let r15 = BundledPatches.entries.filter { $0.sinceRevision > 14 }.count
print("COUNTS all=\(all) killzone=\(kz) r15=\(r15)")

configDir = base + "/a/"
let a = fresh("armsx3.bundled.a")
await run("FRESH", a)
await run("AGAIN", a)
try? FileManager.default.removeItem(atPath: configDir + "patches/armsx3_patch.yml")
await run("RESTORED", a)

configDir = base + "/b/"
let b = fresh("armsx3.bundled.b")
b.set(14, forKey: BundledPatches.revisionKey)
await run("FROM14", b)

configDir = base + "/c/"
let c = fresh("armsx3.bundled.c")
unknownHashes = killzone
await run("NOKZ1", c)
await run("NOKZ2", c)
unknownHashes = []
await run("KZ", c)
await run("KZAGAIN", c)

configDir = base + "/d/"
let d = fresh("armsx3.bundled.d")
await run("NOYAML", d, yaml: nil)
writeStatus = 9
await run("WRITEFAIL", d)
writeStatus = 0
"""


def behaviour_problems(swift_text):
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "BundledPatches.swift"
        src.write_text(swift_text, encoding="utf-8")
        work = Path(tmp) / "work"
        work.mkdir()
        driver = DRIVER.replace("CommandLine.arguments[1]", repr(str(work)).replace("'", '"'))
        out = compile_and_run([src], driver, tmp)

    rows = {}
    for line in out.splitlines():
        parts = line.split()
        rows[parts[0]] = dict(p.split("=", 1) for p in parts[1:])
    counts = rows.get("COUNTS", {})
    n, kz, r15 = int(counts.get("all", -1)), int(counts.get("killzone", -1)), int(counts.get("r15", -1))

    def expect(label, **want):
        got = rows.get(label, {})
        bad = {k: (got.get(k), str(v)) for k, v in want.items() if got.get(k) != str(v)}
        return ["behave: {0} got {1}".format(label, bad)] if bad else []

    # The stored revision is read from the Swift file's own `revision` constant, so a patch bump needs no test edit.
    declared = re.findall(r"(?m)^\s*static let revision = (\d+)\s*$", swift_text)
    if len(declared) != 1:
        return ["behave: expected one `static let revision = N` declaration, found {0}".format(len(declared))]
    rev = int(declared[0])

    problems = []
    problems += expect("FRESH", status="enabled", enabled=n, failed=0, writes=1, calls=n, unique=n, stored=rev, remembered=0)
    problems += expect("AGAIN", status="current", writes=0, calls=0, stored=rev)
    problems += expect("RESTORED", status="written", enabled=0, writes=1, calls=0, stored=rev)
    problems += expect("FROM14", status="enabled", enabled=r15, writes=1, calls=r15, stored=rev)
    problems += expect("NOKZ1", status="partial", enabled=n - kz, failed=kz, writes=1, calls=n - kz, stored=0, remembered=n - kz)
    problems += expect("NOKZ2", status="partial", enabled=0, failed=kz, calls=0, stored=0, remembered=n - kz)
    problems += expect("KZ", status="enabled", enabled=kz, failed=0, calls=kz, stored=rev, remembered=0)
    problems += expect("KZAGAIN", status="current", writes=0, calls=0)
    problems += expect("NOYAML", status="missing", writes=0, calls=0, stored=0)
    problems += expect("WRITEFAIL", status="write-failed", writes=1, calls=0, stored=0)
    return problems


class RealFiles(unittest.TestCase):

    def test_every_entry_names_a_bundled_patch(self):
        self.assertEqual([], yaml_problems(read(SWIFT), read(ASSET)))


    def test_it_behaves(self):
        self.assertEqual([], behaviour_problems(read(SWIFT)))


class Mutations(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone; re-aim it")
        return text.replace(old, new, 1)

    def caught(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)


    def test_name_drifts_from_yaml(self):
        s = self.edited(read(SWIFT), 'name: "ARMSX3 Junkyard crash fix"', 'name: "ARMSX3 Junkyard crash Fix"')
        self.caught(yaml_problems(s, read(ASSET)), "yaml")


    def test_partial_failure_retries_everything(self):
        s = self.edited(read(SWIFT), "!done.contains(entry.key)", "true")
        self.caught(behaviour_problems(s), "behave")

    def test_reenables_every_launch(self):
        s = self.edited(read(SWIFT), "if stored >= revision && present {", "if false && present {")
        self.caught(behaviour_problems(s), "behave")

    def test_revision_ignored(self):
        s = self.edited(read(SWIFT), "entry.sinceRevision > stored && ", "")
        self.caught(behaviour_problems(s), "behave")

    def test_stores_a_revision_other_than_its_constant(self):
        s = self.edited(read(SWIFT), "defaults.set(revision, forKey: revisionKey)", "defaults.set(revision - 1, forKey: revisionKey)")
        self.caught(behaviour_problems(s), "behave")


if __name__ == "__main__":
    unittest.main(verbosity=2)
