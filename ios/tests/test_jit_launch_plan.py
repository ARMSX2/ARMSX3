#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The stored JIT method and LiveContainer decide the pre-main route: jit_launch_plan.h, compiled with cc, composes the
plan as the table says, the launcher reads the key safely, and the dual map checks in order and never reaches the
helper under LiveContainer. It is a host and source check, not device evidence."""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_span, normalise, read_jit_default_route as read, switch_body, SWITCH_SIGNATURE
from source_helpers import brace_block, dual_map_text
from source_helpers import without_c_comments as without_comments


ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "ios" / "jit-enabler" / "jit_launch_plan.h"
LAUNCHER_M = ROOT / "ios" / "jit-enabler" / "StikJITExtensionLauncher.m"
CORE_DIR = ROOT / "ios" / "core"
JIT_NEXT_STEP = ROOT / "ios" / "app" / "JIT" / "JitNextStep.swift"

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

METHODS = (None, "", "builtin", "stikdebug", "wait", "Wait", "stikdebugx", "garbage")
EXPECTED_METHOD = {"stikdebug": 1, "wait": 2}

HEADER_WORDS = (
    '#define ARMSX3_JIT_METHOD_KEY "armsx3.settings.jitMethod"',
    '#define ARMSX3_JIT_METHOD_BUILTIN "builtin"',
    '#define ARMSX3_JIT_METHOD_STIKDEBUG "stikdebug"',
    '#define ARMSX3_JIT_METHOD_WAIT "wait"',
)
HEADER_COMPARES = (
    "strcmp(method, ARMSX3_JIT_METHOD_STIKDEBUG) == 0",
    "strcmp(method, ARMSX3_JIT_METHOD_WAIT) == 0",
)
MIRRORS = (
    ("k_jit_plan_stikdebug", "ARMSX3_JIT_PLAN_STIKDEBUG"),
    ("k_jit_plan_wait", "ARMSX3_JIT_PLAN_WAIT"),
    ("k_jit_plan_method_mask", "ARMSX3_JIT_PLAN_METHOD_MASK"),
    ("k_jit_plan_livecontainer", "ARMSX3_JIT_PLAN_LIVECONTAINER"),
)

# The Swift method type mirrors the header's three words.
SWIFT_METHOD = re.compile(r"enum JitMethod: String, CaseIterable \{ case (\w+), (\w+), (\w+) \}")
HEADER_METHOD = re.compile(r'#define ARMSX3_JIT_METHOD_(BUILTIN|STIKDEBUG|WAIT) "(\w+)"')

READER_SIGNATURE = re.compile(r'__attribute__\(\(used,\s*visibility\("default"\)\)\)\s*int\s+armsx3_jit_launch_plan\s*\(\s*void\s*\)\s*\{')
READER_NEEDS = (
    'getenv("LC_HOME_PATH")',
    "CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_METHOD_KEY), kCFPreferencesCurrentApplication)",
    "CFGetTypeID(value) == CFStringGetTypeID()",
    "char method[16]",
    "CFStringGetCString((CFStringRef)value, method, sizeof method, kCFStringEncodingUTF8)",
    "CFRelease(value);",
    "return armsx3_jit_plan_compose(livecontainer, method);",
)
READER_FORBIDS = re.compile(r"armsx3_jit_enabler_prepare|NSExtension|beginExtensionRequest|Armsx3Launcher|StikJITExtensionLauncher")

PLAN_NAME = "armsx3_jit_launch_plan"
HELPER_LOOKUP = 'void* const entry = ::dlsym(RTLD_DEFAULT, "armsx3_jit_enabler_prepare_ranges");'
NOT_ATTACHED = 'if ((plan & k_jit_plan_method_mask) == k_jit_plan_stikdebug) { enabler_reason = "stikdebug-not-attached"; return false; }'
LC_RETURN = 'enabler_reason = "livecontainer";'
HELPER_STRING = '"armsx3_jit_enabler_prepare_ranges"'

# The pre-main debugger wait.
WAIT_SIGNATURE = "bool wait_for_debugger(int budget_ms)"
WAIT_BUDGET = re.compile(r"constexpr\s+int\s+k_wait_for_debugger_ms\s*=\s*(\d+)\s*;")
WAIT_LOOP = "for (int waited = 0; waited < budget_ms; waited += k_prepare_poll_ms)"
WAIT_FORBIDS = re.compile(r"\bcsops\b|\bCS_DEBUGGED\b|while\s*\(\s*true\s*\)|while\s*\(\s*1\s*\)|for\s*\(\s*;\s*;\s*\)")
WAIT_CALL = 'if ((plan & k_jit_plan_method_mask) == k_jit_plan_wait) { enabler_reason = wait_for_debugger(k_wait_for_debugger_ms) ? "debugger-attached" : "no-debugger"; return false; }'
ORDER = (
    'if (process_is_traced() == 1) { enabler_reason = "already-traced"; return false; }',
    'void* const plan_entry = ::dlsym(RTLD_DEFAULT, "armsx3_jit_launch_plan");',
    "const int plan = plan_entry != nullptr ? reinterpret_cast<launch_plan_fn>(plan_entry)() : 0;",
    'if ((plan & k_jit_plan_livecontainer) != 0 && (plan & k_jit_plan_method_mask) == 0) { enabler_reason = "livecontainer"; return false; }',
    NOT_ATTACHED,
    WAIT_CALL,
    HELPER_LOOKUP,
)

DRIVER = r"""
#include "jit_launch_plan.h"
#include <stdio.h>

static void row(int lc, const char* method)
{
    printf("%d %s %d\n", lc, method == NULL ? "<null>" : (method[0] == '\0' ? "<empty>" : method),
           armsx3_jit_plan_compose(lc, method));
}

int main(void)
{
    const char* methods[] = { NULL, "", "builtin", "stikdebug", "wait", "Wait", "stikdebugx", "garbage" };
    for (int lc = 0; lc < 2; lc++)
        for (unsigned i = 0; i < sizeof methods / sizeof methods[0]; i++)
            row(lc, methods[i]);
    return 0;
}
"""


def label(method):
    if method is None:
        return "<null>"
    return "<empty>" if method == "" else method


def expected_table():
    return {(lc, label(m)): EXPECTED_METHOD.get(m, 0) + (4 if lc else 0) for lc in (0, 1) for m in METHODS}


def plan_table(header_text):
    """Compile the driver against `header_text`; return ({(lc, method): plan}, compiler output)."""
    compiler = shutil.which("cc")
    if compiler is None:
        raise unittest.SkipTest("no `cc` on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "jit_launch_plan.h").write_text(header_text, encoding="utf-8")
        driver = Path(tmp) / "driver.c"
        binary = Path(tmp) / "driver"
        driver.write_text(DRIVER, encoding="utf-8")
        built = subprocess.run(
            [compiler, "-std=c11", "-Wall", "-Werror", "-I", tmp, str(driver), "-o", str(binary)],
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
        table = {}
        for line in ran.stdout.splitlines():
            lc, method, plan = line.split()
            table[(int(lc), method)] = int(plan)
        return table, ran.stdout


def body_of(code, signature):
    """The brace-matched body that follows `signature`, or None when the signature is absent."""
    at = code.find(signature)
    if at < 0:
        return None
    return brace_block(code, at)[0]


def launch_plan_problems(header_raw, launcher_raw, dual_raw, swift_raw=None):
    header = normalise(without_comments(header_raw))
    launcher = without_comments(launcher_raw)
    code = without_comments(dual_raw)
    problems = []

    # header
    for word in HEADER_WORDS + HEADER_COMPARES:
        if word not in header:
            problems.append("header: jit_launch_plan.h does not carry " + word)

    # mirror
    for local, enum in MIRRORS:
        here = re.findall(r"constexpr\s+int\s+" + local + r"\s*=\s*(\d+)\s*;", code)
        there = re.findall(r"\b" + enum + r"\s*=\s*(\d+)", header)
        if len(here) != 1 or len(there) != 1 or here != there:
            problems.append("mirror: {0} {1} does not equal the header's {2} {3}".format(local, here, enum, there))
    if swift_raw is not None:
        words = dict(HEADER_METHOD.findall(header))
        want = [words.get(k) for k in ("BUILTIN", "STIKDEBUG", "WAIT")]
        swift = without_comments(swift_raw)
        cases = SWIFT_METHOD.findall(swift)
        if len(cases) != 1 or list(cases[0]) != want:
            problems.append("mirror: JitNextStep.swift's JitMethod cases {0} do not equal the header's method words {1}".format(cases, want))

    # reader
    match = READER_SIGNATURE.search(launcher)
    if match is None:
        problems.append("reader: armsx3_jit_launch_plan(void) is not defined with used + default visibility")
    else:
        body = normalise(brace_block(launcher, match.start())[0])
        for need in READER_NEEDS:
            if need not in body:
                problems.append("reader: armsx3_jit_launch_plan does not carry " + need)
        if READER_FORBIDS.search(body):
            problems.append("reader: armsx3_jit_launch_plan names an extension launch; it must only read")
    if not re.search(r'#\s*include\s*"jit_launch_plan\.h"', launcher):
        problems.append('reader: the launcher does not include "jit_launch_plan.h"')

    # order
    try:
        flat = normalise(switch_body(code))
        start, end = function_span(code, SWITCH_SIGNATURE)
    except (ValueError, AssertionError):
        return problems + ["order: the launch-time switch prepare_through_builtin_enabler() not found"]
    at = [flat.find(step) for step in ORDER]
    if -1 in at or at != sorted(at):
        problems.append("order: the switch does not run, in order, the traced check, the plan lookup, the LiveContainer return, the not-attached and wait returns and the helper lookup; positions {0}".format(at))
    named = [m.start() for m in re.finditer(r"\b" + PLAN_NAME + r"\b", code)]
    if len(named) != 1 or not start < named[0] < end or code[named[0] - 1] != '"':
        problems.append("order: {0} must appear once in the dual map, as the dlsym string inside the switch; found {1}".format(PLAN_NAME, len(named)))

    # nohelper-lc
    lc = code.find(LC_RETURN)
    helper = code.find(HELPER_STRING)
    if lc < 0 or helper < 0 or not lc < helper:
        problems.append("nohelper-lc: the livecontainer return does not precede the helper's dlsym; LiveContainer could reach the helper")

    # wait
    budget = WAIT_BUDGET.findall(code)
    if len(budget) != 1 or not 0 < int(budget[0]) <= 10000:
        problems.append("wait: k_wait_for_debugger_ms {0} is not one constant in (0, 10000]".format(budget))
    wait = body_of(code, WAIT_SIGNATURE)
    if wait is None:
        problems.append("wait: " + WAIT_SIGNATURE + " not found")
    else:
        flat_wait = normalise(wait)
        for need in ("process_is_traced() == 1", "nanosleep(", "k_prepare_poll_ms", WAIT_LOOP):
            if need not in flat_wait:
                problems.append("wait: wait_for_debugger does not carry " + need)
        if WAIT_FORBIDS.search(wait):
            problems.append("wait: wait_for_debugger reads csops or CS_DEBUGGED, or loops without a bound")
    if WAIT_CALL not in flat:
        problems.append("wait: the switch does not call wait_for_debugger(k_wait_for_debugger_ms) recording debugger-attached or no-debugger")

    return problems


def has(test, problems, check):
    test.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))


class Plan(unittest.TestCase):
    """The compiled table."""

    def test_the_header_exists_and_is_not_empty(self):
        self.assertTrue(read(HEADER).strip())

    def test_the_compose_table(self):
        table, output = plan_table(read(HEADER))
        self.assertIsNotNone(table, "the driver did not compile or run against jit_launch_plan.h:\n" + output)
        sys.stderr.write("\n" + output)  # stderr, so the table lands before unittest's summary
        self.assertEqual(expected_table(), table)


class TheLaunchPlanIsWired(unittest.TestCase):
    def test_the_sources_have_their_shape(self):
        self.assertEqual([], launch_plan_problems(read(HEADER), read(LAUNCHER_M), dual_map_text(CORE_DIR), read(JIT_NEXT_STEP)))


class TheGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header = read(HEADER)
        cls.launcher = read(LAUNCHER_M)
        cls.dual = dual_map_text(CORE_DIR)
        cls.swift = read(JIT_NEXT_STEP)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's target text is not in the file; the mutation would be vacuous")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def problems(self, header=None, launcher=None, dual=None, swift=None):
        return launch_plan_problems(header or self.header, launcher or self.launcher, dual or self.dual, swift or self.swift)

    def test_mutation_stikdebug_spelling_in_the_header_is_caught(self):
        header = self.edited(self.header, '"stikdebug"', '"Stikdebug"')
        has(self, self.problems(header=header), "header")
        table, output = plan_table(header)
        self.assertIsNotNone(table, output)
        self.assertNotEqual(expected_table(), table)

    def test_mutation_mirror_drifts_is_caught(self):
        has(self, self.problems(dual=self.edited(self.dual, "k_jit_plan_livecontainer = 4;", "k_jit_plan_livecontainer = 8;")), "mirror")

    def test_mutation_swift_method_word_drifts_is_caught(self):
        has(self, self.problems(swift=self.edited(self.swift, "case builtin, stikdebug, wait }", "case builtin, stikDebug, wait }")), "mirror")

    def test_mutation_swift_raw_value_overridden_is_caught(self):
        swift = self.edited(self.swift, "case builtin, stikdebug, wait }", 'case builtin, stikdebug = "stik", wait }')
        has(self, self.problems(swift=swift), "mirror")

    def test_mutation_reader_keeps_the_value_is_caught(self):
        has(self, self.problems(launcher=self.edited(self.launcher, "CFRelease(value);", ";")), "reader")

    def test_mutation_reader_launches_the_helper_is_caught(self):
        launcher = self.edited(self.launcher, "return armsx3_jit_plan_compose(livecontainer, method);",
                               "armsx3_jit_enabler_prepare_ranges(NULL, 0, 0, NULL);\n    return armsx3_jit_plan_compose(livecontainer, method);")
        has(self, self.problems(launcher=launcher), "reader")

    def test_mutation_not_attached_return_dropped_is_caught(self):
        dual = self.edited(self.dual, 'enabler_reason = "stikdebug-not-attached";\n\t\t\t\treturn false;', ";")
        has(self, self.problems(dual=dual), "order")

    def test_mutation_livecontainer_after_the_helper_lookup_is_caught(self):
        lc = ('\t\t\tif ((plan & k_jit_plan_livecontainer) != 0 && (plan & k_jit_plan_method_mask) == 0)\n'
              '\t\t\t{\n\t\t\t\tenabler_reason = "livecontainer";\n\t\t\t\treturn false;\n\t\t\t}\n\n')
        dual = self.edited(self.dual, lc, "")
        dual = self.edited(dual, "\t\t\tif (entry == nullptr)\n", lc + "\t\t\tif (entry == nullptr)\n")
        has(self, self.problems(dual=dual), "nohelper-lc")

    def test_mutation_plan_called_directly_is_caught(self):
        dual = self.edited(self.dual, "reinterpret_cast<launch_plan_fn>(plan_entry)()", "armsx3_jit_launch_plan()")
        has(self, self.problems(dual=dual), "order")

    def test_mutation_wait_budget_raised_past_ten_seconds_is_caught(self):
        has(self, self.problems(dual=self.edited(self.dual, "k_wait_for_debugger_ms = 10000;", "k_wait_for_debugger_ms = 10001;")), "wait")

    def test_mutation_wait_reads_csops_is_caught(self):
        dual = self.edited(self.dual, "\t\t\tif (process_is_traced() == 1)\n\t\t\t{\n\t\t\t\treturn true;",
                           "\t\t\tif (process_is_traced() == 1 || csops(0, 0, nullptr, 0) == 0)\n\t\t\t{\n\t\t\t\treturn true;")
        has(self, self.problems(dual=dual), "wait")

    def test_mutation_wait_loop_condition_dropped_is_caught(self):
        has(self, self.problems(dual=self.edited(self.dual, "waited < budget_ms;", ";")), "wait")

    def test_mutation_wait_not_called_from_the_switch_is_caught(self):
        has(self, self.problems(dual=self.edited(self.dual, "wait_for_debugger(k_wait_for_debugger_ms) ?", "false ?")), "wait")


if __name__ == "__main__":
    unittest.main(verbosity=2)
