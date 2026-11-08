#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RPCS3's per-title config database is fetched at run time into Application Support, never bundled: the Swift fetch,
run on the Mac host over file URLs, caches, replaces and keeps copies correctly, off the main thread, at the bridge's path.
It does not show the network fetch working on a device or a boot picking up an entry."""

import os
import re
import tempfile
import time
import unittest
from pathlib import Path

from source_helpers import abi_text
from swiftc_host import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
SWIFT = ROOT / "ios" / "app" / "Settings" / "ConfigDatabase.swift"
APP_ROOT = ROOT / "ios" / "app" / "Shell" / "ARMSX3FrontendRoot.swift"
ABI_DIR = ROOT / "ios" / "bridge"
PROJECT = ROOT / "ios" / "project.yml"
FRONTEND = ROOT / "ios" / "app"

GOOD = '{\n    "return_code": 0,\n    "games": {\n        "BLUS30443": {\n            "config": "Video:\\n  Write Color Buffers: true"\n        }\n    }\n}\n'
BAD = '{"return_code": -2}\n'
OLD = "OLD COPY\n"
STALE_SECONDS = 8 * 24 * 60 * 60

DRIVER = r"""
import Foundation
let dir = URL(fileURLWithPath: "@DIR@")
func run(_ label: String, _ source: String, _ name: String) async {
    let file = dir.appendingPathComponent(name)
    let status = await ConfigDatabase.refresh(from: dir.appendingPathComponent(source), to: file)
    let text = (try? Data(contentsOf: file)).map { String(decoding: $0, as: UTF8.self) } ?? "<absent>"
    let excluded = (try? file.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    print("== \(label) status=\(status) excluded=\(excluded ? 1 : 0)\n\(text)--END--")
}
await run("young", "missing.json", "young.json")
await run("stale-good", "good.json", "stale-good.json")
await run("stale-bad", "bad.json", "stale-bad.json")
await run("stale-offline", "missing.json", "stale-offline.json")
await run("none", "missing.json", "none.json")
await run("first", "good.json", "sub/first.json")
"""

EXPECTED = {
    "young": ("cached", None, OLD),
    "stale-good": ("fetched", 1, GOOD),
    "stale-bad": ("failed", None, OLD),
    "stale-offline": ("failed", None, OLD),
    "none": ("none", None, "<absent>"),
    "first": ("fetched", 1, GOOD),
}


def read(path):
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("empty input: {0}".format(path))
    return text


def code(swift):
    """Swift with line comments removed, so prose cannot satisfy or trip a check. A comment starts a
    line or follows whitespace, which keeps the `//` inside `https://` in the code."""
    return re.sub(r"(^|[ \t])//[^\n]*", r"\1", swift, flags=re.M)


def fetch_problems(swift_text):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        source = tmp / "ConfigDatabase.swift"
        source.write_text(swift_text, encoding="utf-8")
        data = tmp / "data"
        data.mkdir()
        (data / "good.json").write_text(GOOD, encoding="utf-8")
        (data / "bad.json").write_text(BAD, encoding="utf-8")
        for name in ("young.json", "stale-good.json", "stale-bad.json", "stale-offline.json"):
            (data / name).write_text(OLD, encoding="utf-8")
        old = time.time() - STALE_SECONDS
        for name in ("stale-good.json", "stale-bad.json", "stale-offline.json"):
            os.utime(data / name, (old, old))
        try:
            out = compile_and_run([source], DRIVER.replace("@DIR@", str(data)), tmp)
        except AssertionError as error:
            return ["fetch: the Swift does not build or run: " + str(error)[:400]]

    found = {m.group(1): (m.group(2), int(m.group(3)), m.group(4))
             for m in re.finditer(r"== (\S+) status=(\w+) excluded=(\d)\n(.*?)--END--", out, re.S)}
    problems = []
    for label, (status, excluded, text) in sorted(EXPECTED.items()):
        got = found.get(label)
        if got is None:
            problems.append("fetch: no result for `{0}`".format(label))
            continue
        if got[0] != status or got[2] != text or (excluded is not None and got[1] != excluded):
            problems.append("fetch: `{0}` gave status={1} excluded={2} and {3} bytes; expected status={4}".format(
                label, got[0], got[1], len(got[2]), status))
    return problems


def bundle_problems(frontend_files, project_text):
    problems = []
    for path in frontend_files:
        if Path(path).name == "rpcs3-config-db.json":
            problems.append("bundle: {0} is still in the frontend tree".format(Path(path).relative_to(ROOT)))
    if "rpcs3-config-db" in project_text:
        problems.append("bundle: ios/project.yml names the database")
    return problems


def source_problems(swift, root, abi):
    problems = []
    body = code(swift)

    file_var = re.search(r"static var file: URL \{(.*?)\n    \}", body, re.S)
    file_var = file_var.group(1) if file_var else ""
    parts = re.findall(r'appendingPathComponent\("([^"]+)"', file_var)
    bridge = re.search(r'"/Library/Application Support/([^"]+)"', abi)
    if ".applicationSupportDirectory" not in file_var or ".documentDirectory" in body:
        problems.append("path: the copy is not written under applicationSupportDirectory")
    if not parts or bridge is None or "/".join(parts) != bridge.group(1):
        problems.append("path: the Swift path {0} is not the bridge's {1}".format(
            "/".join(parts), bridge.group(1) if bridge else None))

    refresh_if_stale = re.search(r"static func refreshIfStale\(\) \{(.*?)\n    \}", body, re.S)
    if refresh_if_stale is None or "Task.detached(" not in refresh_if_stale.group(1) or "@MainActor" in refresh_if_stale.group(1):
        problems.append("main: refreshIfStale() does not run the fetch in Task.detached")
    if "@MainActor" in body:
        problems.append("main: ConfigDatabase is isolated to the main actor")
    if "Data(contentsOf:" in body:
        problems.append("main: the fetch reads synchronously with Data(contentsOf:)")
    root_code = code(root)
    if not re.search(r"^\s*ConfigDatabase\.refreshIfStale\(\)\s*$", root_code, re.M):
        problems.append("main: launch does not start the fetch as a plain ConfigDatabase.refreshIfStale() call")
    if re.search(r"await\s+ConfigDatabase\.", root_code):
        problems.append("main: launch awaits the fetch")

    if not re.search(r"\.write\(to: file, options: \.atomic\)", body):
        problems.append("write: the copy is not written atomically")
    if "values.isExcludedFromBackup = true" not in body:
        problems.append("write: the copy is not excluded from backup")

    urls = re.findall(r'URL\(string: "([^"]+)"\)', body)
    if urls != ["https://api.rpcs3.net/config/?api=v1"]:
        problems.append("source: the database URL is {0}".format(urls))

    statuses = set(re.findall(r'return "(\w+)"', body))
    for pair in re.findall(r'\? "(\w+)" : "(\w+)"', body):
        statuses |= set(pair)
    if not statuses or not statuses <= {"fetched", "cached", "failed", "none"}:
        problems.append("log: a status outside fetched, cached, failed and none: {0}".format(sorted(statuses)))
    return problems


def frontend_files():
    return [p for p in FRONTEND.rglob("*") if p.is_file()]


class RealFiles(unittest.TestCase):
    def test_the_fetch_behaves(self):
        self.assertEqual([], fetch_problems(read(SWIFT)))

    def test_the_bundle_does_not_carry_it(self):
        self.assertEqual([], bundle_problems(frontend_files(), read(PROJECT)))

    def test_the_source_wires_it(self):
        self.assertEqual([], source_problems(read(SWIFT), read(APP_ROOT), abi_text(ABI_DIR)))


class Mutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.swift = read(SWIFT)
        cls.root = read(APP_ROOT)
        cls.abi = abi_text(ABI_DIR)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone; re-aim it")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def caught(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def src(self, swift=None, root=None, abi=None):
        return source_problems(swift or self.swift, root or self.root, abi or self.abi)

    def test_young_copy_refetched(self):
        s = self.edited(self.swift, "now.timeIntervalSince(modified) < maxAge", "now.timeIntervalSince(modified) < 0")
        self.caught(fetch_problems(s), "fetch")

    def test_bad_body_written(self):
        s = self.edited(self.swift, "guard isDatabase(body) else { return kept }", "")
        self.caught(fetch_problems(s), "fetch")

    def test_backup_flag_dropped_at_run_time(self):
        s = self.edited(self.swift, "values.isExcludedFromBackup = true", "values.isExcludedFromBackup = false")
        self.caught(fetch_problems(s), "fetch")

    def test_bundled_again(self):
        self.caught(bundle_problems(frontend_files() + [FRONTEND / "Resources" / "ConfigDatabase" / "rpcs3-config-db.json"], read(PROJECT)), "bundle")

    def test_written_to_documents(self):
        self.caught(self.src(swift=self.edited(self.swift, ".applicationSupportDirectory", ".documentDirectory")), "path")

    def test_name_drifts_from_the_bridge(self):
        self.caught(self.src(swift=self.edited(self.swift, '"rpcs3-config-db.json", isDirectory: false', '"config-db.json", isDirectory: false')), "path")

    def test_fetch_on_the_main_actor(self):
        self.caught(self.src(swift=self.edited(self.swift, "Task.detached(priority: .utility) {", "Task { @MainActor in")), "main")

    def test_type_on_the_main_actor(self):
        self.caught(self.src(swift=self.edited(self.swift, "enum ConfigDatabase {", "@MainActor enum ConfigDatabase {")), "main")

    def test_launch_awaits_it(self):
        self.caught(self.src(root=self.edited(self.root, "        ConfigDatabase.refreshIfStale()\n", "        await ConfigDatabase.refresh()\n")), "main")

    def test_launch_never_fetches(self):
        self.caught(self.src(root=self.edited(self.root, "        ConfigDatabase.refreshIfStale()\n", "")), "main")

    def test_not_atomic(self):
        self.caught(self.src(swift=self.edited(self.swift, "options: .atomic", "options: []")), "write")

    def test_wrong_source(self):
        self.caught(self.src(swift=self.edited(self.swift, "https://api.rpcs3.net/config/?api=v1", "https://example.invalid/db")), "source")

    def test_status_outside_the_four(self):
        self.caught(self.src(swift=self.edited(self.swift, 'return "cached"', 'return "young"')), "log")


if __name__ == "__main__":
    unittest.main(verbosity=2)
