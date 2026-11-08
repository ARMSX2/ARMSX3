# SPDX-License-Identifier: GPL-2.0-only
"""library_scan.h compiled with c++ and run on the Mac host: excluded categories, safe serials, the icon
cache path, backdrop lookup and row ordering, checked against fixed expected lines.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

BRIDGE = ROOT / "ios" / "bridge"

COMPILE_TIMEOUT_SECONDS = 120

RULES_DRIVER = r"""
#include "library_scan.h"
#include <cstdio>
#include <fstream>
#include <string>

using namespace armsx3::bridge;

static int ends_with(const std::string& s, const std::string& t)
{
    return s.size() >= t.size() && s.compare(s.size() - t.size(), t.size(), t) == 0 ? 1 : 0;
}

int main(int argc, char** argv)
{
    if (argc != 2)
        return 2;
    const std::string dir = argv[1];
    for (const char* c : {"GD", "AC", "SD", "HM", "MS", "HG", "DG", "HD", ""})
        std::printf("excluded %s %d\n", c, excluded_category(c) ? 1 : 0);
    std::printf("safe %s\n", safe_serial("../x").c_str());
    std::printf("safe %s\n", safe_serial("AB_12-cd/..\\:*").c_str());
    std::printf("safe [%s]\n", safe_serial("../").c_str());
    const std::string icon = icon_cache_path("/caches", "../../TEST00009");
    std::printf("icon %d %d\n", ends_with(icon, "/armsx3/icons/TEST00009/ICON0.PNG"), icon.find("..") == std::string::npos ? 1 : 0);
    std::printf("icon-empty [%s] [%s]\n", icon_cache_path("/caches", "/..").c_str(), icon_cache_path("", "TEST00001").c_str());
    std::ofstream(dir + "/with/ICON0.PNG").put('x');
    std::ofstream(dir + "/with/PIC1.PNG").put('x');
    std::ofstream(dir + "/without/ICON0.PNG").put('x');
    std::printf("backdrop %d\n", ends_with(backdrop_path_for(dir + "/with/ICON0.PNG"), "/with/PIC1.PNG"));
    std::printf("backdrop [%s] [%s]\n", backdrop_path_for(dir + "/without/ICON0.PNG").c_str(), backdrop_path_for("").c_str());
    std::vector<library_row> rows(3);
    rows[0].title = "B"; rows[0].serial = "S1";
    rows[1].title = "A"; rows[1].serial = "S3";
    rows[2].title = "A"; rows[2].serial = "S2";
    sort_rows(rows);
    for (const auto& r : rows)
        std::printf("row %s %s\n", r.title.c_str(), r.serial.c_str());
    return 0;
}
"""

RULES_EXPECTED = [
    "excluded GD 1", "excluded AC 1", "excluded SD 1", "excluded HM 1", "excluded MS 1",
    "excluded HG 0", "excluded DG 0", "excluded HD 0", "excluded  0",
    "safe x", "safe AB_12-cd", "safe []",
    "icon 1 1",
    "icon-empty [] []",
    "backdrop 1",
    "backdrop [] []",
    "row A S2", "row A S3", "row B S1",
]


class TheRulesHold(unittest.TestCase):
    def test_the_rules_print_exactly_this(self):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "with").mkdir()
            (tmp / "without").mkdir()
            source = tmp / "rules.cpp"
            binary = tmp / "rules"
            source.write_text(RULES_DRIVER, encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(source), "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            self.assertEqual(0, built.returncode, "the rules driver did not compile:\n" + built.stdout)
            ran = subprocess.run(
                [str(binary), str(tmp)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
            )
            self.assertEqual(0, ran.returncode, ran.stdout)
            self.assertEqual(RULES_EXPECTED, ran.stdout.splitlines())


if __name__ == "__main__":
    unittest.main(verbosity=2)
