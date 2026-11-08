# SPDX-License-Identifier: GPL-2.0-only
"""diag_log.cpp writes the image base, slide and UUID as RPCS3.log's fourth header line, and image_id.h, run on the
Mac host, gives a base that `atos -l` resolves to known functions while a base one page off does not.
It says nothing about an iOS run or summary.txt, which test_problem_report.py owns."""

import platform
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_body, without_comments_log_ring_contract as without_comments

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "ios" / "bridge" / "image_id.h"
DIAG_CPP = ROOT / "ios" / "bridge" / "diag_log.cpp"
TOOL_TIMEOUT_SECONDS = 120

IMAGE_PREFIX = "IOSDIAG: image "
# No anchors, and no backslash or double quote: test_problem_report.py pastes it into a Swift string and compares text.
IMAGE_VALUE = r"base=0x[0-9a-f]+ slide=0x[0-9a-f]+ uuid=[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}"

DRIVER = r'''
#include "image_id.h"
#include <cstdio>
#include <mach-o/dyld.h>

extern "C" __attribute__((noinline)) int armsx3_symbolize_marker(int x)
{
	return x * 3 + 1;
}

int main()
{
	std::printf("IMAGE %s\n", armsx3::bridge::diag::own_image_text().c_str());
	std::printf("MARKER %p\n", reinterpret_cast<void*>(&armsx3_symbolize_marker));
	std::printf("ZERO %p\n", reinterpret_cast<const void*>(_dyld_get_image_header(0)));
	return armsx3_symbolize_marker(1) == 4 ? 0 : 1;
}
'''


def read(path):
    path = Path(path)
    if not path.is_file():
        raise AssertionError("{0} is missing. FAILURE, not skip.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    return text


def image_problems(diag):
    """Prefixed problems of the comment-stripped diag_log.cpp."""
    problems = []
    if '#include "image_id.h"' not in diag:
        problems.append('include: diag_log.cpp does not include "image_id.h"')
    body = function_body(diag, "start")
    if body is None:
        return problems + ["header: no single start definition"]
    call = re.search(r"logs::set_init\(\s*\{(.*?)\}\s*\)\s*;", body, re.DOTALL)
    names = re.findall(r"std::move\(\s*(\w+)\s*\)", call.group(1)) if call else []
    if len(names) != 4:
        return problems + ["header: logs::set_init( does not receive exactly four std::move elements"]
    last = re.escape(names[3])
    if not re.search(r"logs::stored_message\s+" + last + r"\s*\{\s*diag_log\.always\(\)\s*\}", body):
        problems.append("header: the fourth element is not a logs::stored_message built with diag_log.always()")
    if not re.search(last + r'\.text\s*=\s*"' + re.escape(IMAGE_PREFIX) + r'"\s*\+', body):
        problems.append('header: the fourth element\'s text does not start with "IOSDIAG: image "')
    if "own_image_text(" not in body:
        problems.append("header: start does not call own_image_text(")
    return problems


def tool_missing():
    if shutil.which("xcrun") is None:
        return "no `xcrun` on PATH"
    if platform.machine() != "arm64":
        return "not an arm64 host"
    for tool in ("atos", "dwarfdump"):
        if shutil.which(tool) is None:
            return "no `%s` on PATH" % tool
    for find in (["xcrun", "--sdk", "macosx", "--find", "clang++"], ["xcrun", "--find", "dsymutil"]):
        if subprocess.run(find, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).returncode != 0:
            return "xcrun cannot find `%s`" % find[-1]
    return None


def run(command, **kw):
    return subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=TOOL_TIMEOUT_SECONDS,
                          universal_newlines=True, **kw)


class TheLineSymbolizes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        missing = tool_missing()
        if missing:
            raise unittest.SkipTest(missing + "; a skip is not a pass")
        read(HEADER)
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "driver.cpp").write_text(DRIVER, encoding="utf-8")
        # Two steps: the one-step form deletes its object and dsymutil then builds an empty dSYM.
        built = run(["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-g", "-O0", "-I", str(HEADER.parent),
                     "-c", "driver.cpp", "-o", "driver.o"], cwd=tmp)
        if built.returncode != 0:
            raise AssertionError("compile failed:\n" + built.stdout)
        linked = run(["xcrun", "--sdk", "macosx", "clang++", "-g", "-o", "driver", "driver.o"], cwd=tmp)
        if linked.returncode != 0:
            raise AssertionError("link failed:\n" + linked.stdout)
        dsym = run(["xcrun", "dsymutil", "driver", "-o", "driver.dSYM"], cwd=tmp)
        if dsym.returncode != 0:
            raise AssertionError("dsymutil failed:\n" + dsym.stdout)
        ran = run([str(tmp / "driver")], cwd=tmp)
        if ran.returncode != 0:
            raise AssertionError("the driver failed:\n" + ran.stdout)
        got = dict(line.split(" ", 1) for line in ran.stdout.splitlines() if " " in line)
        cls.value, cls.marker, cls.zero = got["IMAGE"], got["MARKER"], got["ZERO"]
        cls.binary, cls.dsym = tmp / "driver", tmp / "driver.dSYM"
        found = re.fullmatch(r"base=0x([0-9a-f]+) slide=0x([0-9a-f]+) uuid=(\S+)", cls.value)
        cls.base, cls.slide, cls.uuid = (int(found.group(1), 16), int(found.group(2), 16), found.group(3)) if found else (0, 0, "")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def atos(self, target, base):
        return run(["atos", "-arch", "arm64", "-o", str(target), "-l", hex(base), self.marker], cwd=self.tmp.name)

    def test_the_value_has_the_shared_shape(self):
        self.assertRegex(self.value, "^" + IMAGE_VALUE + "$")

    def test_the_base_is_the_arm64_executable_base(self):
        self.assertEqual(0x100000000, self.base - self.slide)
        self.assertEqual(int(self.zero, 16), self.base)

    def test_the_uuid_is_the_binarys(self):
        out = run(["dwarfdump", "--uuid", str(self.binary)]).stdout
        self.assertIn(self.uuid, out.upper())

    def test_atos_names_the_marker_from_the_binary_and_from_the_dsym(self):
        for target in (self.binary, self.dsym):
            with self.subTest(target=target.name):
                out = self.atos(target, self.base)
                self.assertTrue(out.stdout.startswith("armsx3_symbolize_marker (in driver)"), out.stdout)

    def test_a_wrong_base_does_not_symbolize(self):
        out = self.atos(self.binary, self.base + 0x4000)
        self.assertFalse(out.stdout.startswith("armsx3_symbolize_marker"), out.stdout)


class TheRealFileKeepsTheContract(unittest.TestCase):
    def test_diag_log_lands_the_line_as_the_fourth_header_message(self):
        self.assertEqual([], image_problems(without_comments(read(DIAG_CPP))))


class EachCheckCanFail(unittest.TestCase):
    def edited(self, old, new):
        text = without_comments(read(DIAG_CPP))
        self.assertIn(old, text)
        return text.replace(old, new)

    def assertFlagged(self, prefix, text):
        self.assertTrue([p for p in image_problems(text) if p.startswith(prefix + ":")], image_problems(text))

    def test_the_fourth_element_dropped(self):
        self.assertFlagged("header", self.edited(", std::move(image)", ""))

    def test_the_prefix_renamed(self):
        self.assertFlagged("header", self.edited('"IOSDIAG: image "', '"IOSDIAG: img "'))

    def test_the_include_removed(self):
        self.assertFlagged("include", self.edited('#include "image_id.h"', ""))

    def test_a_comment_only_edit_changes_nothing(self):
        self.assertEqual([], image_problems(without_comments(read(DIAG_CPP) + "\n// image_id.h\n")))


if __name__ == "__main__":
    unittest.main()
