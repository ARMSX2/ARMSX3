#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ConfigFolderMigration.swift compiled and run on the Mac host against temporary homes: config entries move from
Library/Application Support/rpcs3 to Documents/rpcs3 and the cache stays, links are moved and never followed, nothing is
overwritten, a clash is reported and kept, and a repeat, half-done, missing or unreadable old folder behaves. Static
checks tie the paths to Utilities/File.cpp and the call to ARMSX3App.init()."""

import os
import re
import tempfile
import unittest
from pathlib import Path

from source_helpers import arms_enclosing
from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
ROOT = IOS.parent
SOURCE = IOS / "app" / "Library" / "ConfigFolderMigration.swift"
APP = IOS / "app" / "Shell" / "ARMSX3App.swift"
FILE_CPP = ROOT / "Utilities" / "File.cpp"

DRIVER = r"""
import Foundation

let fm = FileManager.default
let root = @@TMP@@
let root_is_super = @@SUPER@@
func p(_ s: String) -> String { root + "/" + s }
func mk(_ s: String) { try! fm.createDirectory(atPath: p(s), withIntermediateDirectories: true) }
func put(_ s: String, _ text: String) {
    mk((s as NSString).deletingLastPathComponent)
    try! text.write(toFile: p(s), atomically: true, encoding: .utf8)
}
func read(_ s: String) -> String { (try? String(contentsOfFile: p(s), encoding: .utf8)) ?? "-" }
func has(_ s: String) -> Bool { var i = stat(); return lstat(p(s), &i) == 0 }
func link(_ s: String, to t: String) { try! fm.createSymbolicLink(atPath: p(s), withDestinationPath: t) }
func names(_ s: String) -> String { ((try? fm.contentsOfDirectory(atPath: p(s))) ?? []).sorted().joined(separator: ",") }
func run(_ home: String) -> [String] { ConfigFolderMigration.run(home: URL(fileURLWithPath: p(home), isDirectory: true)) }
let old = "/Library/Application Support/rpcs3"
let new = "/Documents/rpcs3"

// M: plain move, the stay-set stays, a top-level link moves as a link
let cache = ["cache", "ppu_progs", "spu_progs", "shaderlog", "DEBUG", "ASMJIT", "TTY.log", "RPCS3.log", "RPCS3.log.gz",
             "RPCS3.buf", "rpcs3_vm_sparse.tmp", "framegen_shaders.bin", "lsfg_spirv.cache", "command_dump.log",
             "armsx3-product.yml", "rpcs3-mem-1.tmp", "audio_dump.wav", "vk_pipeline_cache.bin", "vk_pipeline_cache.bin.tmp"]
for c in cache { put("M" + old + "/" + c, "c") }
put("M" + old + "/dev_hdd0/game/NPUB1/PARAM.SFO", "sfo")
put("M" + old + "/config.yml", "cfg")
put("M" + old + "/patches/a.yml", "p")
put("M" + old + "/custom_configs/x.yml", "x")
put("M/Outside/target.txt", "t")
link("M" + old + "/Icons-link", to: p("M/Outside"))
let m = run("M")
print("M1 returned=\(m.count) old=\(names("M" + old)) new=\(names("M" + new))")
print("M2 game=\(read("M" + new + "/dev_hdd0/game/NPUB1/PARAM.SFO")) cfg=\(read("M" + new + "/config.yml")) link=\((try? fm.destinationOfSymbolicLink(atPath: p("M" + new + "/Icons-link"))) == p("M/Outside") ? 1 : 0) target=\(names("M/Outside"))")
print("M3 again=\(run("M").count) old=\(names("M" + old).split(separator: ",").count) new=\(names("M" + new).split(separator: ",").count)")

// G: Documents/rpcs3 already holds dev_hdd0/home, the old game and home entries merge in
put("G" + new + "/dev_hdd0/home/00000001/localusername", "new")
put("G" + old + "/dev_hdd0/game/NPUB2/PARAM.SFO", "g")
put("G" + old + "/dev_hdd0/home/00000002/localusername", "two")
put("G" + old + "/dev_hdd0/home/00000001/other", "o")
let g = run("G")
print("G1 returned=\(g.count) hdd=\(names("G" + new + "/dev_hdd0")) users=\(names("G" + new + "/dev_hdd0/home")) one=\(names("G" + new + "/dev_hdd0/home/00000001")) oldhdd=\(has("G" + old + "/dev_hdd0") ? 1 : 0)")

// C: a file on both sides keeps the destination bytes and stays in the old folder
put("C" + new + "/dev_hdd0/home/00000001/localusername", "keep")
put("C" + old + "/dev_hdd0/home/00000001/localusername", "old")
put("C" + old + "/dev_hdd0/home/00000001/extra", "e")
put("C" + old + "/config.yml", "cfg")
let c = run("C")
print("C1 returned=\(c) dest=\(read("C" + new + "/dev_hdd0/home/00000001/localusername")) left=\(read("C" + old + "/dev_hdd0/home/00000001/localusername")) extra=\(has("C" + new + "/dev_hdd0/home/00000001/extra") ? 1 : 0) cfg=\(has("C" + new + "/config.yml") ? 1 : 0)")

// S: a destination that is a link to a directory is not followed
put("S/Outside/data", "d")
mk("S" + new)
link("S" + new + "/dev_hdd0", to: p("S/Outside"))
put("S" + old + "/dev_hdd0/game/f", "f")
let s = run("S")
print("S1 returned=\(s) oldkept=\(has("S" + old + "/dev_hdd0/game/f") ? 1 : 0) target=\(names("S/Outside"))")

// H: half-finished earlier run
put("H" + new + "/dev_hdd0/game/A/f", "a")
put("H" + old + "/dev_hdd0/game/B/f", "b")
put("H" + old + "/config.yml", "cfg")
let h = run("H")
print("H1 returned=\(h.count) games=\(names("H" + new + "/dev_hdd0/game")) cfg=\(has("H" + new + "/config.yml") ? 1 : 0) oldempty=\(names("H" + old))")

// Y: games.yml paths under the old folder follow the move, other paths stay
put("Y" + old + "/games.yml", "BLUS1: " + p("Y" + old) + "/dev_hdd0/game/BLUS1/./\nBLUS2: /elsewhere/BLUS2/\n")
let y = run("Y")
print("Y1 returned=\(y.count) games=\(read("Y" + new + "/games.yml") == "BLUS1: " + p("Y" + new) + "/dev_hdd0/game/BLUS1/./\nBLUS2: /elsewhere/BLUS2/\n" ? 1 : 0)")
// Z: an earlier run moved games.yml and stopped before the rewrite
put("Z" + old + "/RPCS3.log", "l")
put("Z" + new + "/games.yml", "BLUS1: " + p("Z" + old) + "/dev_hdd0/game/BLUS1/./\n")
let z = run("Z")
print("Z1 returned=\(z.count) games=\(read("Z" + new + "/games.yml") == "BLUS1: " + p("Z" + new) + "/dev_hdd0/game/BLUS1/./\n" ? 1 : 0)")

// N: a missing old folder
mk("N")
let n = run("N")
print("N1 returned=\(n.count) documents=\(has("N/Documents") ? 1 : 0)")

// U: an unreadable old folder
put("U" + old + "/config.yml", "cfg")
chmod(p("U" + old), 0)
let u = run("U")
chmod(p("U" + old), 0o755)
print("U1 returned=\(root_is_super ? 1 : u.count) moved=\(has("U" + new + "/config.yml") ? 1 : 0)")
"""

EXPECTED = [
    "M1 returned=0 old=ASMJIT,DEBUG,RPCS3.buf,RPCS3.log,RPCS3.log.gz,TTY.log,armsx3-product.yml,audio_dump.wav,cache,"
    "command_dump.log,framegen_shaders.bin,lsfg_spirv.cache,ppu_progs,rpcs3-mem-1.tmp,rpcs3_vm_sparse.tmp,shaderlog,"
    "spu_progs,vk_pipeline_cache.bin,vk_pipeline_cache.bin.tmp new=Icons-link,config.yml,custom_configs,dev_hdd0,patches",
    "M2 game=sfo cfg=cfg link=1 target=target.txt",
    "M3 again=0 old=19 new=5",
    "G1 returned=0 hdd=game,home users=00000001,00000002 one=localusername,other oldhdd=0",
    "C1 returned=[\"dev_hdd0/home/00000001/localusername: already in Documents/rpcs3\"] dest=keep left=old extra=1 cfg=1",
    "S1 returned=[\"dev_hdd0: already in Documents/rpcs3\"] oldkept=1 target=data",
    "H1 returned=0 games=A,B cfg=1 oldempty=",
    "Y1 returned=0 games=1",
    "Z1 returned=0 games=1",
    "N1 returned=0 documents=0",
    "U1 returned=1 moved=0",
]


class ConfigFolderMigrationHost(unittest.TestCase):
    def test_moves_config_and_keeps_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            work = root / "build"
            work.mkdir()
            driver = DRIVER.replace("@@TMP@@", '"{0}"'.format(root / "homes")).replace(
                "@@SUPER@@", "true" if os.geteuid() == 0 else "false")
            output = compile_and_run([SOURCE], driver, work)
        self.assertEqual(output.strip().splitlines(), EXPECTED)


class ConfigFolderMigrationWiring(unittest.TestCase):
    def test_core_arm_matches_the_swift_destination(self):
        cpp = FILE_CPP.read_text(encoding="utf-8")
        config = cpp[cpp.index("fs::get_config_dir("):]
        at = config.index('home + "/Documents"s')
        arms = arms_enclosing(config, at)
        self.assertTrue(any(a.mentions_ios() and not a.is_else for a in arms),
                        "the /Documents assignment is outside the TARGET_OS_IPHONE arm")
        cache = cpp[cpp.index("fs::get_cache_dir("):]
        start = cache.index("#if TARGET_OS_IPHONE")
        ios_arm = cache[start:cache.index("#else", start)]
        self.assertIn("/Library/Application Support", ios_arm)

    def test_swift_paths_name_both_folders(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn("Documents/rpcs3", text)
        self.assertIn("Library/Application Support/rpcs3", text)

    def test_app_init_runs_the_migration(self):
        text = APP.read_text(encoding="utf-8")
        init = re.search(r"\binit\(\)\s*\{(.*?)\n    \}", text, re.S)
        self.assertIsNotNone(init, "ARMSX3App has no init()")
        self.assertIn("ConfigFolderMigration.run(", init.group(1))


if __name__ == "__main__":
    unittest.main()
