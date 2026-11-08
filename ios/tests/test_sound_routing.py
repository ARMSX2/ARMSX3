#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The overlay sound routing in SoundRouting.swift: which file takes which volume, which names and formats play, and the
menu folder copy rules. Compiled with swiftc on the host; the driver prints R-lines that are compared exactly.
"""

import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run


IOS = Path(__file__).resolve().parents[1]

APP = IOS / "app"

ROUTING = APP / "Themes" / "SoundRouting.swift"

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
import Foundation

func show(_ value: Float?) -> String { value.map { String(format: "%.2f", $0) } ?? "nil" }
func vol(_ name: String) -> Float? { SoundRouting.volume(forFile: name, menuPercent: 15, trophyPercent: 70) }
print("R1 trophy=\(show(vol("snd_trophy.wav")))")
print("R2 cursor=\(show(vol("snd_cursor.wav")))")
print("R3 unknown=\(show(vol("snd_other.wav")))")
print("R4 notwav=\(show(vol("snd_cursor.mp3")))")
print("R5 names=\(SoundRouting.menuNames.count)")

// The import rules, in a temporary directory removed at the end.
let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("armsx3-sounds-\(UUID().uuidString)")
func folder(_ name: String, _ files: [String: Int]) -> URL {
    let dir = root.appendingPathComponent(name)
    try! fm.createDirectory(at: dir, withIntermediateDirectories: true)
    for (file, size) in files { try! Data(count: size).write(to: dir.appendingPathComponent(file)) }
    return dir
}
let sounds = root.appendingPathComponent("sounds")
let r6 = SoundRouting.importFolder(folder("menu", ["snd_cursor.wav": 64, "snd_other.wav": 64, "snd_decide.mp3": 64]), into: sounds)
print("R6 copied=\(r6.copied) ignored=\(r6.ignored)")
let r7 = SoundRouting.importFolder(folder("big", ["snd_cancel.wav": 5 * 1024 * 1024]), into: sounds)
print("R7 big=\(r7.copied)")
let picked = folder("picked", ["ping.wav": 64, "ping.mp3": 64])
let r8 = SoundRouting.importTrophy(picked.appendingPathComponent("ping.wav"), into: sounds)
let landed = fm.fileExists(atPath: sounds.appendingPathComponent("snd_trophy.wav").path) ? "snd_trophy.wav" : "none"
print("R8 trophy=\(r8 ? 1 : 0) name=\(landed)")
let r8b = SoundRouting.importTrophy(picked.appendingPathComponent("ping.mp3"), into: root.appendingPathComponent("sounds-b"))
print("R8b trophy=\(r8b ? 1 : 0)")
try? fm.removeItem(at: root)
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


class TheSoundsRoute(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(ROUTING)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([ROUTING], DRIVER, tmp)
        cls.lines = {line.split("=", 1)[0]: line for line in out.splitlines() if line.strip()}
        sys.stderr.write("\n" + "\n".join(cls.lines.values()) + "\n")

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_r1_the_trophy_file_takes_the_trophy_volume(self):
        self.check("R1 trophy", "R1 trophy=0.70")

    def test_r2_a_menu_file_takes_the_menu_volume(self):
        self.check("R2 cursor", "R2 cursor=0.15")

    def test_r3_a_name_outside_the_eight_plays_nothing(self):
        self.check("R3 unknown", "R3 unknown=nil")

    def test_r4_a_file_that_is_not_wav_plays_nothing(self):
        self.check("R4 notwav", "R4 notwav=nil")

    def test_r5_the_menu_folder_has_seven_names(self):
        self.check("R5 names", "R5 names=7")

    def test_r6_only_the_menu_names_with_wav_are_copied(self):
        self.check("R6 copied", "R6 copied=1 ignored=2")

    def test_r7_a_file_over_four_mib_is_skipped(self):
        self.check("R7 big", "R7 big=0")

    def test_r8_a_picked_wav_lands_as_the_trophy_sound(self):
        self.check("R8 trophy", "R8 trophy=1 name=snd_trophy.wav")

    def test_r8b_a_picked_mp3_is_refused(self):
        self.check("R8b trophy", "R8b trophy=0")


if __name__ == "__main__":
    unittest.main(verbosity=2)
