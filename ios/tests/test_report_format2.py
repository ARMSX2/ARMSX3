# SPDX-License-Identifier: GPL-2.0-only
"""ReportFacts.swift, compiled on the Mac, ends each fixed log as the report names it, and DiagnosticsStore.summary
over fixed sessions gives the format, build, thermal state, settings, earlier run, last error and chips.
Every ReportFacts.keySettings path is a leaf of system_config.h. Standard library only."""

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
FACTS = ROOT / "ios" / "app" / "Diagnostics" / "ReportFacts.swift"
STORE = ROOT / "ios" / "app" / "Diagnostics" / "DiagnosticsStore.swift"
SOURCES = [APP / "Library" / "ParamSfo.swift", APP / "Install" / "ZipArchive.swift", APP / "Saves" / "SaveDataStore.swift", FACTS, STORE]
CONFIG = ROOT / "rpcs3" / "Emu" / "system_config.h"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from swiftc_host import compile_and_run  # noqa: E402


def swift_strings(body):
    return [bytes(m, "utf-8").decode("unicode_escape") for m in re.findall(r'"((?:[^"\\]|\\.)*)"', body)]


def swift_array(text, name):
    m = re.search(r"static let " + name + r"(?:: \[[^\]]*\])? = \[(.*?)\n?\s*\]\n", text, re.S)
    if m is None:
        raise AssertionError("ReportFacts.swift has no array " + name)
    return swift_strings(m.group(1))


def settings_problems(facts, config):
    out = []
    for path in swift_array(facts, "keySettings"):
        if '{ this, "%s"' % path.split("@@")[-1] not in config and '{this, "%s"' % path.split("@@")[-1] not in config:
            out.append("settings: {0} is not a leaf in system_config.h".format(path))
    return out


def ts(sec):
    return "0:%02d:%02d.000000" % (sec // 60, sec % 60)


def perf(sec, fps, headroom=3000, mem=2000):
    return "·! %s {TID: 1} IOSDIAG: perf fps=%s frame_ms=16 shaders_new=0 mem_mib=%d headroom_mib=%d thermal=nominal\n" % (
        ts(sec), fps, mem, headroom)


def boot(serial="BLUS00001", tag=""):
    return ("·! 0:00:01.000000 {TID: 2} SYS: Title: Test Game %s\n" % tag +
            "·! 0:00:01.000001 {TID: 2} SYS: Serial: %s\n" % serial)


def rendered(n, start=2, fps=30):
    return "".join(perf(start + i, fps) for i in range(n))


FATAL = (boot("BLUS00002") + rendered(40) +
         "·F 0:00:50.000000 {PPU[0x1000011] Thread (Main) [0x0070dd68]} VM: Access violation reading location 0x7c6a1b98 (unmapped memory)\n"
         "·F 0:00:50.000100 {PPU[0x1000011] Thread (Main) [0x0070dd68]} VM: Host backtrace:\n"
         "  #00 0   ARMSX3                              0x000000010cb341cc _ZN5utils13get_backtraceEi + 108\n"
         "  #01 1   ???                                 0x000000704530c2d0 0x0 + 482197160656\n"
         "  #02 2   ARMSX3                              0x000000010cc2b10c _ZN10ppu_thread8cpu_taskEv + 2172\n")
CLEAN = boot("BLUS00003") + rendered(40) + "·! 0:01:00.000000 SYS: Stopping emulator...\n"
JETSAM = boot("BLUS00004") + rendered(40) + "".join(perf(42 + i, 30, headroom=200, mem=5900) for i in range(5))
FREEZE = boot("BLUS00005") + rendered(40) + "".join(perf(42 + i, 0.0) for i in range(25))
PAUSED = (boot("BLUS00006") + rendered(40) + "·S 0:00:43.000000 {TID: 3} SYS: Emulation is being paused... (mark=1)\n" +
          "".join(perf(44 + i, 0.0) for i in range(25)))
NEVER = boot("BLUS00007") + "".join(perf(2 + i * 8, 0.0) for i in range(25))
LEFT = boot("BLUS00008") + "".join(perf(2 + i, 0.0) for i in range(25))
NOGAME = "·! 0:00:01.000000 {TID: 1} LLVM version: 22\n"


RUNS = {"fatal": FATAL, "clean": CLEAN, "jetsam": JETSAM, "freeze": FREEZE, "paused": PAUSED, "never": NEVER,
        "left": LEFT, "nogame": NOGAME, "killed": boot("BLUS00009") + rendered(40)}

ENDINGS_DRIVER = """import Foundation
let dir = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : @@DIR@@)
for name in (try! FileManager.default.contentsOfDirectory(atPath: dir.path)).sorted() where name.hasSuffix(".log") {
    let text = String(decoding: try! Data(contentsOf: dir.appendingPathComponent(name)), as: UTF8.self)
    print("\\(name) \\(ReportFacts.ending(of: text))")
}
"""

PERF = ("·! 0:00:{0:02d}.000000 {{TID: 1}} IOSDIAG: perf fps=30.0 frame_ms=16 shaders_new=0 mem_mib=4000 "
        "headroom_mib={1} thermal={2}\n")
LOG = ("·! 0:00:01.000000 {TID: 2} SYS: Emulator::BootGame: /x\n"
       "·! 0:00:01.000001 {TID: 2} SYS: Serial: BLUS00001\n"
       + PERF.format(2, 900, "nominal") +
       "·E 0:00:03.000000 {RSX} RSX: texture upload failed\n"
       "·E 0:00:04.000000 {PPU} cellAudio: buffer underrun\n"
       "·E 0:00:05.000000 {PPU} SYS: 'sceNpTrophyGetTrophyInfo' failed with 0x8002a50a : SCE_NP_TROPHY_ERROR_HIDDEN\n"
       "·E 0:00:06.000000 {PPU} sys_fs: 'sys_fs_open' failed with 0x80010006 : CELL_ENOENT\n"
       + PERF.format(7, 812, "fair"))

SUMMARY_DRIVER = """import Foundation
let base = URL(fileURLWithPath: @@DIR@@)
let support = base.appendingPathComponent("support", isDirectory: true)
let store = DiagnosticsStore(root: support.appendingPathComponent("logs", isDirectory: true),
                             rpcs3Dir: support.appendingPathComponent("rpcs3", isDirectory: true),
                             recordDir: base.appendingPathComponent("record", isDirectory: true),
                             reportsDir: base.appendingPathComponent("Reports", isDirectory: true),
                             tmpDir: base.appendingPathComponent("tmp", isDirectory: true),
                             redaction: Redaction(home: "/nowhere-home", bundle: "/nowhere-bundle", bundleID: "x.y", rpcnName: ""))
func summary(_ name: String, _ log: String) -> [String] {
    let s = store.sessionsDir.appendingPathComponent(name, isDirectory: true)
    try! FileManager.default.createDirectory(at: s, withIntermediateDirectories: true)
    try! Data(log.utf8).write(to: s.appendingPathComponent("RPCS3.log"))
    try! Data(#"{"jetsam_limit_bytes": 6442450944, "machine": "iPhone17,2"}"#.utf8).write(to: s.appendingPathComponent("launch.json"))
    var settings = ReportSettings()
    settings.key["Core@@PPU Decoder"] = "LLVM Recompiler"
    return store.summary(session: s, form: ReportForm(), settings: settings,
                         process: ReportProcess(stderrDropped: 3, appBuild: "1.6 (23001)")).split(separator: "\\n").map(String.init)
}
let earlier = try! String(contentsOfFile: base.appendingPathComponent("earlier.log").path, encoding: .utf8)
_ = summary("20261101T000000Z", earlier)
let main = try! String(contentsOfFile: base.appendingPathComponent("main.log").path, encoding: .utf8)
for line in summary("20261102T000000Z", main) { print("S " + line) }
let fatal = main + "\\u{00B7}F 0:00:08.000000 {PPU} VM: Access violation\\n"
for line in summary("20261103T000000Z", fatal) where line.hasPrefix("Last error") { print("F " + line) }
let chips = store.summary(session: store.sessionsDir.appendingPathComponent("20261102T000000Z", isDirectory: true),
                          form: ReportForm(kinds: ["Freeze", "Graphics"], place: "Gameplay"), settings: ReportSettings())
for line in chips.split(separator: "\\n") where line.hasPrefix("Problem: ") || line.hasPrefix("Happened during: ") { print("C " + line) }
let dull = "\\u{00B7}E 0:00:04.000000 {PPU} cellAudio: buffer underrun\\n"
for line in summary("20261104T000000Z", dull) where line.hasPrefix("Last error") { print("D " + line) }

func payload(_ name: String, _ binaries: [String]) -> URL {
    let crashes = binaries.map { ["callStackTree": ["callStacks": [["callStackRootFrames": [["binaryName": $0]]]]]] }
    let url = base.appendingPathComponent(name)
    try! JSONSerialization.data(withJSONObject: ["crashDiagnostics": crashes]).write(to: url)
    return url
}
func use(_ u: DiagnosticsStore.PayloadUse) -> String {
    switch u {
    case .asIs: return "asIs"
    case .trimmed(let text): return "trimmed harness=\\(text.contains("ARMSX3")) other=\\(text.contains("OtherJIT"))"
    case .foreign: return "foreign"
    }
}
print("P own=\\(use(store.ownPayload(payload("own.json", ["ARMSX3"])))) mixed=\\(use(store.ownPayload(payload("mixed.json", ["ARMSX3", "OtherJIT"])))) other=\\(use(store.ownPayload(payload("other.json", ["OtherJIT"]))))")
"""


# What ReportFacts.ending gave each fixture when this test was written.
ENDINGS = {"fatal": "fatal", "clean": "clean_stop", "jetsam": "jetsam", "freeze": "freeze", "paused": "ended_paused",
           "never": "stuck_boot", "left": "left_boot", "nogame": "no_game", "killed": "killed_running"}


class TheSettingsExist(unittest.TestCase):
    def test_key_settings_exist(self):
        self.assertEqual([], settings_problems(FACTS.read_text(encoding="utf-8"), CONFIG.read_text(encoding="utf-8")))


class TheEndingsAreFixed(unittest.TestCase):
    def test_each_run_ends_as_pinned(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in RUNS.items():
                (Path(tmp) / (name + ".log")).write_text(text, encoding="utf-8")
            out = compile_and_run([FACTS], ENDINGS_DRIVER.replace("@@DIR@@", json.dumps(tmp)), tmp)
        got = dict((line.split(" ", 1)[0][:-4], line.split(" ", 1)[1]) for line in out.splitlines() if line.endswith(tuple(ENDINGS.values())))
        self.assertEqual(ENDINGS, got)
        self.assertEqual(len(set(got.values())), len(RUNS))


class TheSummarySaysWhatHappened(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "main.log").write_text(LOG, encoding="utf-8")
            (Path(tmp) / "earlier.log").write_text(FREEZE, encoding="utf-8")
            cls.out = compile_and_run(SOURCES, SUMMARY_DRIVER.replace("@@DIR@@", json.dumps(tmp)), tmp).splitlines()
        cls.summary = [line[2:] for line in cls.out if line.startswith("S ")]

    def test_format_build_thermal_memory(self):
        for line in ("Report format: 2", "Build: 1.6 (23001)", "Thermal (last sample): fair",
                     "Memory: limit 6144 MiB, headroom 812 MiB at the last sample",
                     "Console lines dropped, approximate (RPCS3.log is complete): 3"):
            self.assertIn(line, self.summary)

    def test_key_settings(self):
        self.assertIn("  PPU Decoder: LLVM Recompiler", self.summary)
        self.assertIn("  Write Color Buffers: unknown", self.summary)

    def test_earlier_run(self):
        self.assertIn("Earlier run 20261101T000000Z: Froze, then killed", self.summary)

    def test_last_error_choice(self):
        self.assertIn("Last error: ·E 0:00:03.000000 {RSX} RSX: texture upload failed", self.summary)
        self.assertIn("F Last error: ·F 0:00:08.000000 {PPU} VM: Access violation", self.out)
        self.assertIn("D Last error: ·E 0:00:04.000000 {PPU} cellAudio: buffer underrun", self.out)

    def test_chips(self):
        self.assertIn("Problem: (not given)", self.summary)
        self.assertIn("Happened during: (not given)", self.summary)
        self.assertIn("C Problem: freeze, graphics", self.out)
        self.assertIn("C Happened during: gameplay", self.out)

    def test_foreign_crashes_left_out(self):
        self.assertIn("P own=asIs mixed=trimmed harness=true other=false other=foreign", self.out)


class TheGuardCanFail(unittest.TestCase):
    def test_unknown_setting_is_caught(self):
        facts = FACTS.read_text(encoding="utf-8").replace('"Video@@Vblank Rate"', '"Video@@No Such Leaf"', 1)
        self.assertTrue(settings_problems(facts, CONFIG.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main(argv=sys.argv[:1], verbosity=2)
