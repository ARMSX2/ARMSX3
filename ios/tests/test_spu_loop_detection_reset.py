#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The global SPU loop detection setting is reset to off once at launch, as Android's ConfigStore.kt does: the key is
Android's, launch awaits the reset after prepare, and the Swift, run on the Mac host against a fake bridge, retries
only after a failure. It does not show the core running with loop detection off."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from swiftc_host import compile_and_run  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SWIFT = ROOT / "ios" / "app" / "Settings" / "SpuLoopDetectionReset.swift"
LAUNCH = ROOT / "ios" / "app" / "Shell" / "ARMSX3FrontendRoot.swift"
KOTLIN = ROOT / "android" / "armsx3-ui" / "app" / "src" / "main" / "java" / "com" / "armsx2" / "config" / "ConfigStore.kt"


def read(path):
    return Path(path).read_text(encoding="utf-8")


def android_problems(swift, kotlin):
    problems = []
    if 'KEY_SPU_LOOPDET_OFF = "config.migrated.spuLoopDetectionOff"' not in kotlin:
        problems.append("android: ConfigStore.kt no longer names KEY_SPU_LOOPDET_OFF config.migrated.spuLoopDetectionOff")
    if 'static let key = "config.migrated.spuLoopDetectionOff"' not in swift:
        problems.append("android: the Swift key is not Android's config.migrated.spuLoopDetectionOff")
    if 'CoreSettingOverrides.forget(SettingsScope.Global, null, "Core@@SPU loop detection")' not in kotlin:
        problems.append("android: Android no longer forgets only the global override")
    return problems


def launch_problems(launch):
    problems = []
    prepare = launch.find("await Rpcs3Bridge.prepare()")
    ensure = launch.find("await SpuLoopDetectionReset.ensure()")
    ready = launch.find("stage = .ready", prepare)
    if prepare < 0 or ensure < 0 or not prepare < ensure < ready:
        problems.append("launch: SpuLoopDetectionReset.ensure() is not awaited between prepare() and the library")
    return problems


DRIVER = r"""
import Foundation

nonisolated(unsafe) var stored = "true"
nonisolated(unsafe) var readStatus = 0
nonisolated(unsafe) var resetStatus = 0
nonisolated(unsafe) var resets: [String] = []

enum Rpcs3Bridge {
    static func settings(forSerial serial: String?) async -> (Data?, Int) {
        if readStatus != 0 { return (nil, readStatus) }
        let body = "{\"version\":1,\"leaves\":[{\"path\":[\"Core\",\"SPU Decoder\"],\"global\":\"Recompiler (LLVM)\"},"
            + "{\"path\":[\"Core\",\"SPU loop detection\"],\"global\":\"" + stored + "\"}]}"
        return (Data(body.utf8), 0)
    }
    static func resetSettings(_ paths: [String], serial: String?) async -> (Int, Bool) {
        resets.append(paths.joined(separator: ",") + "|" + (serial ?? "GLOBAL"))
        if resetStatus == 0 && serial == nil { stored = "false" }
        return (resetStatus, false)
    }
}

func run(_ label: String, _ d: UserDefaults) async {
    resets = []
    let status = await SpuLoopDetectionReset.ensure(defaults: d)
    print("\(label) status=\(status) resets=\(resets.count) first=\((resets.first ?? "none").replacingOccurrences(of: " ", with: "_")) key=\(d.bool(forKey: SpuLoopDetectionReset.key) ? 1 : 0) stored=\(stored)")
}

func fresh(_ name: String) -> UserDefaults {
    let d = UserDefaults(suiteName: name)!
    d.removePersistentDomain(forName: name)
    return d
}

let a = fresh("armsx3.loopdet.a")
stored = "true"
await run("ON", a)
stored = "true"
await run("REENABLED", a)

let b = fresh("armsx3.loopdet.b")
stored = "false"
await run("OFF", b)

let c = fresh("armsx3.loopdet.c")
stored = "true"
readStatus = -4
await run("READFAIL", c)
readStatus = 0
resetStatus = -20
await run("RESETFAIL", c)
resetStatus = 0
await run("RETRY", c)
"""


def behaviour_problems(swift_text):
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "SpuLoopDetectionReset.swift"
        src.write_text(swift_text, encoding="utf-8")
        out = compile_and_run([src], DRIVER, tmp)

    rows = {}
    for line in out.splitlines():
        parts = line.split()
        rows[parts[0]] = dict(p.split("=", 1) for p in parts[1:])

    def expect(label, **want):
        got = rows.get(label, {})
        bad = {k: (got.get(k), str(v)) for k, v in want.items() if got.get(k) != str(v)}
        return ["behave: {0} got {1}".format(label, bad)] if bad else []

    path = "Core@@SPU_loop_detection|GLOBAL"
    problems = []
    problems += expect("ON", status="reset", resets=1, first=path, key=1, stored="false")
    problems += expect("REENABLED", status="done", resets=0, key=1, stored="true")
    problems += expect("OFF", status="kept", resets=0, key=1, stored="false")
    problems += expect("READFAIL", status="read-failed", resets=0, key=0, stored="true")
    problems += expect("RESETFAIL", status="reset-failed", resets=1, key=0, stored="true")
    problems += expect("RETRY", status="reset", resets=1, first=path, key=1, stored="false")
    return problems


class RealFiles(unittest.TestCase):
    def test_matches_android(self):
        self.assertEqual([], android_problems(read(SWIFT), read(KOTLIN)))

    def test_launch_runs_it(self):
        self.assertEqual([], launch_problems(read(LAUNCH)))

    def test_it_behaves(self):
        self.assertEqual([], behaviour_problems(read(SWIFT)))


class Mutations(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone; re-aim it")
        return text.replace(old, new, 1)

    def caught(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_runs_every_launch(self):
        s = self.edited(read(SWIFT), "if defaults.bool(forKey: key) {", "if false {")
        self.caught(behaviour_problems(s), "behave")

    def test_resets_a_game_file(self):
        s = self.edited(read(SWIFT), "resetSettings([path], serial: nil)", 'resetSettings([path], serial: "BLES01717")')
        self.caught(behaviour_problems(s), "behave")

    def test_records_key_on_failure(self):
        s = self.edited(read(SWIFT), 'guard reset == 0 else { return "reset-failed" }', "")
        self.caught(behaviour_problems(s), "behave")

    def test_key_drifts_from_android(self):
        s = self.edited(read(SWIFT), '"config.migrated.spuLoopDetectionOff"', '"spuLoopDetectionOff"')
        self.caught(android_problems(s, read(KOTLIN)), "android")

    def test_launch_skips_it(self):
        a = self.edited(read(LAUNCH), "_ = await SpuLoopDetectionReset.ensure()", '_ = ""')
        self.caught(launch_problems(a), "launch")


if __name__ == "__main__":
    unittest.main(verbosity=2)
