# SPDX-License-Identifier: GPL-2.0-only
"""game_scan.h, compiled on the host against a temporary dev_hdd0/game tree, lists the right children in the right
order and handles a missing or empty root. Skips only when no c++ is on PATH."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "ios" / "bridge"
SUBJECT = BRIDGE / "game_scan.h"

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
#include "game_scan.h"
#include <cstdio>
#include <string>

static int ends_with(const std::string& s, const char* tail)
{
    const std::string t{tail};
    return s.size() >= t.size() && s.compare(s.size() - t.size(), t.size(), t) == 0 ? 1 : 0;
}

int main(int argc, char** argv)
{
    if (argc != 2)
        return 2;
    const auto games = armsx3::bridge::scan_game_dirs(argv[1]);
    for (const auto& g : games)
        std::printf("%s %d %d\n", g.serial.c_str(), ends_with(g.sfo_path, "/PARAM.SFO"),
            ends_with(g.boot_path, "/USRDIR/EBOOT.BIN"));
    std::printf("END %zu\n", games.size());
    return 0;
}
"""


def touch(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"x")


def build_tree(root):
    for serial in ("BCES00001", "AAAA00002", ".hidden05"):
        touch(root / serial / "PARAM.SFO")
        touch(root / serial / "USRDIR" / "EBOOT.BIN")
    touch(root / "NOSFO0003" / "USRDIR" / "EBOOT.BIN")
    touch(root / "NOBOOT004" / "PARAM.SFO")
    touch(root / "FILE00006")
    (root / "EMPTY0007").mkdir()


class TheSubjectExists(unittest.TestCase):
    def test_the_header_exists_and_is_not_empty(self):
        self.assertTrue(
            SUBJECT.is_file() and SUBJECT.read_text(encoding="utf-8").strip(),
            "ios/bridge/game_scan.h is absent or empty. FAILURE, not skip.",
        )


class TheScanListsExactlyThis(unittest.TestCase):
    outputs = None
    compile_output = ""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")

        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            driver = tmp / "driver.cpp"
            binary = tmp / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver),
                 "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
            )
            cls.compile_output = built.stdout
            if built.returncode != 0:
                return

            games = tmp / "game"
            build_tree(games)
            empty = tmp / "empty"
            empty.mkdir()

            cls.outputs = {}
            for key, root in (("tree", games), ("missing", tmp / "absent"), ("empty", empty)):
                ran = subprocess.run(
                    [str(binary), str(root)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True,
                )
                cls.outputs[key] = (ran.returncode, ran.stdout.splitlines())

    def setUp(self):
        self.assertIsNotNone(
            self.outputs,
            "the driver did not compile against ios/bridge/game_scan.h:\n" + self.compile_output,
        )

    def check(self, key, lines):
        code, got = self.outputs[key]
        self.assertEqual(0, code, "%s: the driver exited non-zero" % key)
        self.assertEqual(lines, got, key)

    def test_the_tree_lists_two_titles_in_serial_order(self):
        self.check("tree", ["AAAA00002 1 1", "BCES00001 1 1", "END 2"])

    def test_a_missing_root_lists_nothing(self):
        self.check("missing", ["END 0"])

    def test_an_empty_root_lists_nothing(self):
        self.check("empty", ["END 0"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
