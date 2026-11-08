# SPDX-License-Identifier: GPL-2.0-only
"""jit_state.h, compiled on the host, turns the dual map's launch-time report into the JIT verdict for the whole
decision table, and the ABI publishes that verdict and refuses a boot when JIT is off.
Skips only when no c++ is on PATH; it says nothing about a device."""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
ROOT = TESTS_DIR.parents[1]
BRIDGE = ROOT / "ios" / "bridge"
SUBJECT = BRIDGE / "jit_state.h"
ABI_H = BRIDGE / "rpcs3_ios_abi.h"

# Borrowed, not re-copied.
sys.path.insert(0, str(TESTS_DIR))
from source_helpers import abi_text, brace_block  # noqa: E402
from source_helpers import without_c_comments as without_comments  # noqa: E402

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
#include "jit_state.h"
#include <cstdio>

using armsx3::jit_state::decide;
using armsx3::jit_state::inputs;

static void run(const char* tag, const inputs& in)
{
    const auto v = decide(in);
    std::printf("%s %d %s\n", tag, v.on ? 1 : 0, v.reason);
}

int main()
{
    run("A", {false, false, nullptr, 0});
    run("B", {true, false, nullptr, 0});
    run("C", {true, true, "helper-ok", 0});
    run("D", {true, true, "helper-failed", 1});
    run("E", {true, true, "helper-failed", 0});
    run("F", {true, true, "opted-out", 0});
    run("G", {true, true, nullptr, 0});
    run("H", {true, true, "helper-ok", 1});
    run("I", {true, true, "already-traced", 1});
    run("J", {true, true, "livecontainer", 0});
    run("K", {true, true, "stikdebug-not-attached", 0});
    run("L", {true, true, "no-debugger", 0});
    run("M", {true, true, "debugger-attached", 1});
    return 0;
}
"""


def function_body(source, name):
    """The brace-matched body of the definition of `name` in comment-stripped `source`."""
    match = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*(noexcept\s*)?\{", source)
    if match is None:
        raise AssertionError("no definition of {0} found".format(name))
    return brace_block(source, match.start())[0]


def iphone_only_spans(source):
    """The text between each `#if TARGET_OS_IPHONE` and its matching `#else`/`#elif`/`#endif`."""
    spans = []
    lines = source.splitlines()
    for i, line in enumerate(lines):
        if not re.match(r"\s*#\s*if\s+TARGET_OS_IPHONE\s*$", line):
            continue
        depth = 0
        for j in range(i + 1, len(lines)):
            directive = re.match(r"\s*#\s*(if|ifdef|ifndef|elif|else|endif)\b", lines[j])
            if directive is None:
                continue
            word = directive.group(1)
            if word.startswith("if"):
                depth += 1
            elif depth == 0:
                spans.append("\n".join(lines[i + 1 : j]))
                break
            elif word == "endif":
                depth -= 1
    return spans


class TheSubjectExists(unittest.TestCase):
    def test_the_header_exists_and_is_not_empty(self):
        self.assertTrue(
            SUBJECT.is_file() and SUBJECT.read_text(encoding="utf-8").strip(),
            "ios/bridge/jit_state.h is absent or empty. FAILURE, not skip.",
        )


class TheDecisionTableHolds(unittest.TestCase):
    cases = None
    compile_output = ""
    run_code = None

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")

        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
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
            ran = subprocess.run(
                [str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True,
            )
            cls.run_code = ran.returncode
            cls.cases = {}
            for line in ran.stdout.splitlines():
                tag, on, reason = line.split()
                cls.cases[tag] = (on, reason)

    def setUp(self):
        self.assertIsNotNone(
            self.cases,
            "the driver did not compile against ios/bridge/jit_state.h:\n" + self.compile_output,
        )
        self.assertEqual(0, self.run_code, "the driver exited non-zero")

    def check(self, tag, on, reason):
        self.assertEqual((on, reason), self.cases[tag], "case %s" % tag)

    def test_a_macos_host_is_on(self):
        self.check("A", "1", "macos-host")

    def test_b_no_block_is_off(self):
        self.check("B", "0", "no-dual-map")

    def test_c_helper_ok_is_on(self):
        self.check("C", "1", "helper-ok")

    def test_d_handshake_is_on_after_a_failed_helper(self):
        self.check("D", "1", "stikdebug-handshake")

    def test_e_failed_helper_without_handshake_is_off(self):
        self.check("E", "0", "helper-failed")

    def test_f_opted_out_without_handshake_is_off(self):
        self.check("F", "0", "opted-out")

    def test_g_no_reason_is_off(self):
        self.check("G", "0", "no-enabler-reason")

    def test_h_the_helper_wins_over_the_handshake(self):
        self.check("H", "1", "helper-ok")

    def test_i_already_traced_with_handshake_is_on(self):
        self.check("I", "1", "stikdebug-handshake")

    # The launch plan's reasons: rule 5 reads OFF with the dual map's word; rule 4 makes a debugger attached in the wait ON.
    def test_j_livecontainer_is_off_with_its_word(self):
        self.check("J", "0", "livecontainer")

    def test_k_stikdebug_not_attached_is_off_with_its_word(self):
        self.check("K", "0", "stikdebug-not-attached")

    def test_l_no_debugger_is_off_with_its_word(self):
        self.check("L", "0", "no-debugger")

    def test_m_debugger_attached_with_handshake_is_on(self):
        self.check("M", "1", "stikdebug-handshake")


class TheAbiWiresTheDecision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header = without_comments(ABI_H.read_text(encoding="utf-8"))
        cls.source = without_comments(abi_text(BRIDGE))

    def test_a_the_two_verdict_values(self):
        self.assertRegex(self.header, r"\bRPCS3_IOS_JIT_ON\s*=\s*200\b")
        self.assertRegex(self.header, r"\bRPCS3_IOS_JIT_OFF\s*=\s*201\b")

    def test_b_the_declaration(self):
        self.assertIn(
            "Rpcs3IOS_Status Rpcs3IOS_GetJitState(int32_t* out_state, char* out_reason, "
            "uint32_t reason_cap);",
            self.header,
        )

    def test_c_the_entry_point_decides_from_the_report(self):
        body = function_body(self.source, "Rpcs3IOS_GetJitState")
        if "armsx3::jit_state::decide" not in body:
            self.assertIn("current_jit_verdict()", body)
            self.assertIn("armsx3::jit_state::decide",
                          function_body(self.source, "current_jit_verdict"))
        self.assertTrue(
            any("jit_dual_map_report_pool(" in span for span in iphone_only_spans(self.source)),
            "jit_dual_map_report_pool( is not inside an `#if TARGET_OS_IPHONE` branch",
        )



class TheBootRefusesWithoutJit(unittest.TestCase):
    """A boot from the JIT-off state is refused at the ABI, before the core is reached."""

    @classmethod
    def setUpClass(cls):
        cls.header = without_comments(ABI_H.read_text(encoding="utf-8"))
        cls.source = without_comments(abi_text(BRIDGE))

    def test_a_the_refusal_precedes_the_boot(self):
        body = function_body(self.source, "Rpcs3IOS_Boot")
        # The body lives in the shared `boot_with` helper; follow it there.
        if "boot_with(" in body:
            body = function_body(self.source, "boot_with")
        self.assertIn("RPCS3_IOS_STATUS_JIT_UNAVAILABLE", body)
        self.assertIn("Emu.BootGame(", body)
        self.assertLess(body.index("RPCS3_IOS_STATUS_JIT_UNAVAILABLE"), body.index("Emu.BootGame("),
                        "the JIT refusal must come before Emu.BootGame")

    def test_b_the_status_is_minus_ten_and_unique(self):
        self.assertRegex(self.header, r"\bRPCS3_IOS_STATUS_JIT_UNAVAILABLE\s*=\s*-10\b")
        self.assertEqual(
            ["RPCS3_IOS_STATUS_JIT_UNAVAILABLE"],
            re.findall(r"\b(RPCS3_IOS_STATUS_\w+)\s*=\s*-10\b", self.header),
        )

if __name__ == "__main__":
    unittest.main(verbosity=2)
