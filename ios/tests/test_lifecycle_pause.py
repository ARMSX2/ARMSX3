# SPDX-License-Identifier: GPL-2.0-only
"""lifecycle_pause.h, compiled on the host with a driver, keeps one pause for scene deactivation and the in-game menu,
keeps it held when the core declines a resume while paused, and drops it once the core no longer reads PAUSED.
Mutations of each guard must change a case; it says nothing about a device."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "ios" / "bridge"
SUBJECT = BRIDGE / "lifecycle_pause.h"

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
#include "lifecycle_pause.h"
#include <cstdio>
#include <string>

using armsx3::ios_frame::pause_machine;
using armsx3::ios_frame::pause_ledger;

static std::string s;
static void d(pause_machine& m) { s += m.on_deactivate() ? '1' : '0'; }
static void a(pause_machine& m) { s += m.on_activate() ? '1' : '0'; }
static void o(pause_machine& m) { s += m.on_menu_open() ? '1' : '0'; }
static void c(pause_machine& m) { s += m.on_menu_close() ? '1' : '0'; }

static void dump(char tag, const pause_machine& m)
{
    const pause_ledger& l = m.l;
    std::printf("%c %s", tag, s.empty() ? "-" : s.c_str());
#define U(f) std::printf(" " #f "=%llu", (unsigned long long)l.f)
#define I(f) std::printf(" " #f "=%lld", (long long)l.f)
    U(deactivations); U(activations); U(pauses_ok); U(pauses_declined); I(last_pause_status);
    U(resumes_ok); U(resumes_declined); U(resumes_not_owed); I(last_resume_status);
    U(pause_latency_ms_max); U(pauses_landed_in_background); U(pauses_during_armed_capture);
    U(paused_ms_total); U(background_ms_total); U(background_ms_longest);
    I(flips_at_last_pause); I(flips_at_last_resume);
    U(menu_opens); U(menu_closes); U(resumes_kept_for_menu); U(resumes_kept_for_lifecycle);
    std::printf(" held=%d\n", l.held ? 1 : 0);
    s.clear();
}

int main()
{
    { pause_machine m; d(m); m.on_pause_result(0, 1000, 1004, 500, false); a(m);
      m.on_resume_result(0, 3000, 501, false); dump('A', m); }
    { pause_machine m; d(m); m.on_pause_result(0, 0, 3, 100, false); m.on_background(50);
      m.on_foreground(8050); a(m); m.on_resume_result(0, 8100, 100, false); dump('B', m); }
    { pause_machine m; d(m); m.on_background(10); m.on_pause_result(0, 0, 20, 7, false);
      dump('C', m); }
    { pause_machine m;
      for (unsigned long long i = 0; i < 5; ++i)
      {
          const unsigned long long t = 100 * i;
          d(m); m.on_pause_result(0, t, t + 1, i, false);
          if (i == 2) d(m);
          a(m); m.on_resume_result(0, t + 50, i, false);
      }
      dump('D', m); }
    { pause_machine m; d(m); m.on_pause_result(-9, 0, 1, 0, false); a(m); dump('E', m); }
    { pause_machine m; d(m); m.on_pause_result(0, 0, 1, 10, false); a(m);
      m.on_resume_result(-9, 500, 10, false); a(m); dump('F', m); }
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 3, true); dump('G', m); }
    { pause_machine m; m.on_foreground(5); m.on_background(100); m.on_foreground(3100);
      m.on_background(4000); m.on_background(4500); m.on_foreground(9000); dump('H', m); }
    { pause_machine m; dump('I', m); }
    // M1: the menu opens on a running game: one pause, held.
    { pause_machine m; o(m); m.on_pause_result(0, 0, 2, 9, false); dump('1', m); }
    // M2: the menu holds the pause, then the scene deactivates and activates: no resume.
    { pause_machine m; o(m); m.on_pause_result(0, 0, 2, 9, false); d(m); a(m); dump('2', m); }
    // M3: the background holds the pause, the menu opens and closes: no resume; the activation does.
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 9, false); o(m); c(m); a(m);
      m.on_resume_result(0, 50, 9, false); dump('3', m); }
    // M4: two opens (the second before the first's result), one pause; two closes, one resume.
    { pause_machine m; o(m); o(m); m.on_pause_result(0, 0, 2, 9, false); c(m);
      m.on_resume_result(0, 40, 9, false); c(m); dump('4', m); }
    // M5: closes that owe nothing: no open at all; an open the background's pause answered; an open
    // the emulator declined.
    { pause_machine m; c(m); d(m); m.on_pause_result(0, 0, 2, 9, false); c(m); a(m);
      m.on_resume_result(0, 30, 9, false); o(m); m.on_pause_result(-9, 40, 41, 9, false); c(m); dump('5', m); }
    // R1 (r, R): the menu's resume is declined while the core still reads
    // PAUSED; the pause stays held, the next open asks nothing, and the next close resumes it.
    { pause_machine m; o(m); m.on_pause_result(0, 0, 2, 9, false); c(m);
      m.on_resume_result(-9, 50, 9, true); dump('r', m);
      o(m); c(m); m.on_resume_result(0, 90, 9, false); dump('R', m); }
    // R2 (s, S): the same for the lifecycle; the next deactivation asks nothing, the next
    // activation resumes it.
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 9, false); a(m);
      m.on_resume_result(-9, 50, 9, true); dump('s', m);
      d(m); a(m); m.on_resume_result(0, 90, 9, false); dump('S', m); }
    // The R2 prefix (a resume declined while paused keeps the hold), then the
    // caller's core read. n: the core still reads PAUSED, so the hold is kept and d asks nothing.
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 9, false); a(m);
      m.on_resume_result(-9, 50, 9, true); m.on_core_state(true); d(m); dump('n', m); }
    // N: the core left PAUSED (the save's kill, a new game), so the hold is dropped and the next
    // deactivation pauses the new game; the activation resumes it.
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 9, false); a(m);
      m.on_resume_result(-9, 50, 9, true); m.on_core_state(false); d(m);
      m.on_pause_result(0, 100, 102, 12, false); a(m); m.on_resume_result(0, 150, 12, false);
      dump('N', m); }
    // K: the same, then the menu opens over the new game: it pauses, and its close resumes.
    { pause_machine m; d(m); m.on_pause_result(0, 0, 2, 9, false); a(m);
      m.on_resume_result(-9, 50, 9, true); m.on_core_state(false); o(m);
      m.on_pause_result(0, 100, 102, 12, false); c(m); m.on_resume_result(0, 150, 12, false);
      dump('K', m); }
    return 0;
}
"""

FRESH = {
    "deactivations": 0, "activations": 0, "pauses_ok": 0, "pauses_declined": 0,
    "last_pause_status": 1, "resumes_ok": 0, "resumes_declined": 0, "resumes_not_owed": 0,
    "last_resume_status": 1, "pause_latency_ms_max": 0, "pauses_landed_in_background": 0,
    "pauses_during_armed_capture": 0, "paused_ms_total": 0, "background_ms_total": 0,
    "background_ms_longest": 0, "flips_at_last_pause": -1, "flips_at_last_resume": -1, "held": 0,
    "menu_opens": 0, "menu_closes": 0, "resumes_kept_for_menu": 0, "resumes_kept_for_lifecycle": 0,
}

MENU_FIELDS = ("menu_opens", "menu_closes", "resumes_kept_for_menu", "resumes_kept_for_lifecycle")


def run_driver(include_dir):
    """Compiles DRIVER against `include_dir`'s lifecycle_pause.h and runs it.
    Returns (cases, output); `cases` is None when the driver did not compile or exited non-zero."""
    compiler = shutil.which("c++")
    if compiler is None:
        raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        driver = Path(tmp) / "driver.cpp"
        binary = Path(tmp) / "driver"
        driver.write_text(DRIVER, encoding="utf-8")
        built = subprocess.run(
            [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(include_dir), str(driver),
             "-o", str(binary)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
        )
        if built.returncode != 0:
            return None, built.stdout
        ran = subprocess.run(
            [str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True,
        )
        if ran.returncode != 0:
            return None, ran.stdout
        cases = {}
        for line in ran.stdout.splitlines():
            tag, returns, *fields = line.split()
            cases[tag] = (returns, {k: int(v) for k, v in (f.split("=") for f in fields)})
        return cases, ran.stdout


# Each M case's exact returns string and pinned ledger fields, shared by the real-file tests and the mutations.
MENU_EXPECTED = {
    "1": ("1", dict(menu_opens=1, menu_closes=0, pauses_ok=1, held=1)),
    "2": ("100", dict(menu_opens=1, deactivations=1, activations=1, pauses_ok=1,
                      resumes_kept_for_menu=1, resumes_not_owed=0, resumes_ok=0, held=1)),
    "3": ("1001", dict(menu_opens=1, menu_closes=1, pauses_ok=1, resumes_kept_for_lifecycle=1,
                       resumes_kept_for_menu=0, resumes_ok=1, held=0)),
    "4": ("1010", dict(menu_opens=2, menu_closes=2, pauses_ok=1, resumes_ok=1, held=0)),
    "5": ("010110", dict(menu_opens=1, menu_closes=3, pauses_ok=1, pauses_declined=1,
                         resumes_ok=1, resumes_not_owed=0, resumes_kept_for_lifecycle=0,
                         resumes_kept_for_menu=0, held=0)),
}


# R1 (r after the declined resume, R after the retry) and R2 (s, S).
HELD_EXPECTED = {
    "r": ("11", dict(menu_opens=1, menu_closes=1, pauses_ok=1, resumes_declined=1, resumes_ok=0,
                     last_resume_status=-9, paused_ms_total=0, held=1)),
    "R": ("01", dict(menu_opens=2, menu_closes=2, pauses_ok=1, resumes_declined=1, resumes_ok=1,
                     last_resume_status=0, paused_ms_total=88, flips_at_last_resume=9, held=0)),
    "s": ("11", dict(deactivations=1, activations=1, pauses_ok=1, resumes_declined=1,
                     resumes_ok=0, resumes_not_owed=0, last_resume_status=-9, held=1)),
    "S": ("01", dict(deactivations=2, activations=2, pauses_ok=1, resumes_declined=1,
                     resumes_ok=1, resumes_not_owed=0, paused_ms_total=88, held=0)),
}


# n keeps a hold while the core still reads PAUSED; N and K drop it before the deactivation or menu open decides.
HOLD_EXPECTED = {
    "n": ("110", dict(deactivations=2, activations=1, resumes_declined=1, held=1)),
    "N": ("1111", dict(deactivations=2, activations=2, pauses_ok=2, resumes_declined=1,
                       resumes_ok=1, resumes_not_owed=0, paused_ms_total=48, held=0)),
    "K": ("1111", dict(menu_opens=1, menu_closes=1, pauses_ok=2, resumes_declined=1,
                       resumes_ok=1, held=0)),
}


def expected_problems(cases, expected, label):
    """Every way `cases` departs from `expected`, as `label`-prefixed strings."""
    problems = []
    for tag, (returns, fields) in expected.items():
        if tag not in cases:
            problems.append("{0}{1}: case missing".format(label, tag))
            continue
        got_returns, ledger = cases[tag]
        if got_returns != returns:
            problems.append("{0}{1}: returns {2}, expected {3}".format(label, tag, got_returns, returns))
        for k, v in fields.items():
            if ledger.get(k) != v:
                problems.append("{0}{1}: {2}={3}, expected {4}".format(label, tag, k, ledger.get(k), v))
    return problems


def menu_problems(cases):
    """Every way `cases` departs from MENU_EXPECTED, as prefixed strings."""
    return expected_problems(cases, MENU_EXPECTED, "M")


def held_problems(cases):
    """Every way `cases` departs from HELD_EXPECTED, as prefixed strings."""
    return expected_problems(cases, HELD_EXPECTED, "R")


def hold_problems(cases):
    """Every way `cases` departs from HOLD_EXPECTED, as prefixed strings."""
    return expected_problems(cases, HOLD_EXPECTED, "G")


class TheSubjectExists(unittest.TestCase):
    def test_the_header_exists_and_is_not_empty(self):
        self.assertTrue(
            SUBJECT.is_file() and SUBJECT.read_text(encoding="utf-8").strip(),
            "ios/bridge/lifecycle_pause.h is absent or empty. FAILURE, not skip.",
        )


class TheMachineRecordsExactlyThis(unittest.TestCase):
    cases = None

    @classmethod
    def setUpClass(cls):
        cls.cases, cls.output = run_driver(BRIDGE)

    def setUp(self):
        self.assertIsNotNone(
            self.cases,
            "the driver did not compile against ios/bridge/lifecycle_pause.h, or exited non-zero:\n"
            + (self.output or ""),
        )

    def check(self, tag, returns, **expected):
        got_returns, ledger = self.cases[tag]
        if returns is not None:
            self.assertEqual(returns, got_returns, "case %s: deactivate/activate returns" % tag)
        for k, v in expected.items():
            self.assertEqual(v, ledger[k], "case %s: %s" % (tag, k))

    def test_a_control_centre_pull(self):
        self.check("A", "11", deactivations=1, activations=1, pauses_ok=1, resumes_ok=1,
                   pause_latency_ms_max=4, paused_ms_total=1996, background_ms_total=0,
                   pauses_landed_in_background=0, flips_at_last_pause=500,
                   flips_at_last_resume=501, held=0)

    def test_b_background_round_trip(self):
        self.check("B", None, background_ms_total=8000, background_ms_longest=8000,
                   paused_ms_total=8097, pauses_landed_in_background=0, held=0)

    def test_c_pause_lands_after_background(self):
        self.check("C", None, pauses_landed_in_background=1, pause_latency_ms_max=20, held=1)

    def test_d_rapid_toggles(self):
        self.check("D", "11111011111", deactivations=6, activations=5, pauses_ok=5,
                   resumes_ok=5, paused_ms_total=245, pause_latency_ms_max=1, held=0)

    def test_e_declined_pause_owes_no_resume(self):
        self.check("E", "10", pauses_declined=1, pauses_ok=0, last_pause_status=-9,
                   resumes_not_owed=1, resumes_ok=0, flips_at_last_pause=-1, held=0)

    def test_f_stop_between_pause_and_resume(self):
        self.check("F", "110", resumes_declined=1, resumes_ok=0, last_resume_status=-9,
                   resumes_not_owed=1, flips_at_last_resume=-1, paused_ms_total=499, held=0)

    def test_g_pause_during_armed_capture(self):
        self.check("G", None, pauses_during_armed_capture=1)

    def test_h_background_spans(self):
        self.check("H", None, background_ms_total=8000, background_ms_longest=5000)

    def test_i_fresh_ledger(self):
        self.check("I", "-", **FRESH)

    # Each M case prints its driver line.
    def menu_case(self, tag):
        returns, fields = MENU_EXPECTED[tag]
        print("\nM{0}: returns={1} {2}".format(tag, self.cases[tag][0], " ".join(
            "{0}={1}".format(k, self.cases[tag][1][k]) for k in MENU_FIELDS + ("held",))))
        self.check(tag, returns, **fields)

    def test_m1_menu_open_pauses_once(self):
        self.menu_case("1")

    def test_m2_activation_never_resumes_a_menu_pause(self):
        self.menu_case("2")

    def test_m3_menu_close_never_resumes_a_lifecycle_pause(self):
        self.menu_case("3")

    def test_m4_open_twice_close_twice_one_pause_one_resume(self):
        self.menu_case("4")

    def test_m5_a_close_that_owes_nothing_resumes_nothing(self):
        self.menu_case("5")

    def test_r1_a_declined_menu_resume_keeps_the_pause_held(self):
        self.assertEqual([], expected_problems(self.cases, {k: HELD_EXPECTED[k] for k in "rR"}, "R"))

    def test_r2_a_declined_lifecycle_resume_keeps_the_pause_held(self):
        self.assertEqual([], expected_problems(self.cases, {k: HELD_EXPECTED[k] for k in "sS"}, "R"))

    # These print nothing: a print splits the verbose `... ok` line.
    def test_g1_kept_while_the_core_reads_paused(self):
        self.assertEqual([], expected_problems(self.cases, {"n": HOLD_EXPECTED["n"]}, "G"))

    def test_g1_dropped_before_a_deactivation(self):
        self.assertEqual([], expected_problems(self.cases, {"N": HOLD_EXPECTED["N"]}, "G"))

    def test_g1_dropped_before_a_menu_open(self):
        self.assertEqual([], expected_problems(self.cases, {"K": HOLD_EXPECTED["K"]}, "G"))

    def test_m6_lifecycle_cases_move_no_menu_counter(self):
        for tag in "ABCDEFGHI":
            for k in MENU_FIELDS:
                self.assertEqual(0, self.cases[tag][1][k], "case %s: %s" % (tag, k))


class EachMenuGuardHasTeeth(unittest.TestCase):
    """Removes one guard condition at a time from a copy of the header; some M case must change."""

    @classmethod
    def setUpClass(cls):
        cls.header = SUBJECT.read_text(encoding="utf-8")
        if not cls.header.strip():
            raise AssertionError("ios/bridge/lifecycle_pause.h is empty. FAILURE, not skip.")

    def edited(self, old, new):
        self.assertEqual(1, self.header.count(old), "the mutation's anchor is not in the header once: " + old)
        return self.header.replace(old, new)

    def caught(self, text, problems=menu_problems):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "lifecycle_pause.h").write_text(text, encoding="utf-8")
            cases, output = run_driver(Path(tmp))
        self.assertIsNotNone(cases, "the mutated header did not compile:\n" + output)
        self.assertNotEqual([], problems(cases), "the mutation went unnoticed")

    def test_open_without_the_first_open_guard(self):
        self.caught(self.edited("return first && !l.held;", "(void)first; return !l.held;"))

    def test_open_without_the_held_guard(self):
        self.caught(self.edited("return first && !l.held;", "return first;"))

    def test_close_without_the_menu_wanted_guard(self):
        self.caught(self.edited("if (!menu_wanted)\n\t\t\t\treturn false;", ""))

    def test_close_without_the_held_guard(self):
        self.caught(self.edited("if (!l.held)\n\t\t\t\treturn false;\n\t\t\tif (wanted)", "if (wanted)"))

    def test_close_without_the_lifecycle_guard(self):
        self.caught(self.edited("if (wanted)\n", "if (false)\n"))

    def test_activate_without_the_menu_guard(self):
        self.caught(self.edited("if (menu_wanted)\n", "if (false)\n"))

    # The old unconditional clear, put back before the status test.
    def test_always_clears(self):
        anchor = "if (status != 0 && still_paused)"
        self.caught(self.edited(anchor, "l.held = false;\n\t\t\t" + anchor), held_problems)

    # on_core_state never drops a hold (N or K changes) ...
    def test_g1_never_drops(self):
        self.caught(self.edited("if (l.held && !core_paused)\n\t\t\t\tl.held = false;",
                                "(void)core_paused;"), hold_problems)

    # ... or drops it whatever the core reads (n changes).
    def test_g1_always_drops(self):
        self.caught(self.edited("if (l.held && !core_paused)", "(void)core_paused;\n\t\t\tif (l.held)"),
                    hold_problems)


if __name__ == "__main__":
    unittest.main(verbosity=2)
