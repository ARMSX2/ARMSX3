#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The StikDebug route prepares in the built-in route's priority order from one range list at the learned split's
default shares, and caps pools 2 and 3 at the prefix its script's report proves; a node mock runs the rendered script
at the measured page rate. It says nothing about a device."""

import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

from source_helpers import brace_block, dual_map_text
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
CORE_DIR = ROOT / "ios" / "core"
HEADER = ROOT / "ios" / "core" / "ios_jit_dual_map.h"
SPLIT = ROOT / "ios" / "core" / "ios_jit_split.h"  # the walk's default shares

PAGE = 0x4000
MEASURED_PAGES = 26242  # pages the 10 s budget reached on iPhone18,2
MEASURED_MS = 10000     # the time those pages took, so the mock's rate holds at any rendered T
MEASURED_15S_PAGES = 34151  # pages the 15 s budget reached on iPhone18,2

ROUTE_STATEMENT = ("const char* const refused = build_prepare_script(reinterpret_cast<unsigned long long>(base), "
                   "stikdebug_walk(), &script_len);")
PROVEN_CALL = ('proven_extents(script_walk_offsets, std::strcmp(script_outcome, "finished") == 0 ? script_pages_ok : 0, '
               "script_first_refused_off, &prep_pool2_bytes, &prep_pool3_bytes);")


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def norm(text):
    return " ".join(text.split())


def constants(dual, header):
    """Every `constexpr unsigned long long k_*` of the source, evaluated in file order."""
    gran = re.search(r"jit_pool3_unit_granularity\s*=\s*([^;]+);", without_comments(header))
    if gran is None:
        raise AssertionError("the header no longer defines jit_pool3_unit_granularity")
    env = {}
    for name, expr in re.findall(r"constexpr unsigned long long (k_\w+)\s*=\s*([^;]+);", without_comments(dual)):
        env[name] = evaluate(expr.replace("utils::jit_pool3_unit_granularity", "(" + gran.group(1) + ")"), env)
    return env


def evaluate(expr, env):
    expr = re.sub(r"\b(0x[0-9a-fA-F]+|\d+)ull\b", r"\1", expr)
    return eval(expr, {"__builtins__": {}}, dict(env))  # constants, names and + only; no builtins


def walk_statements(code):
    """The twelve right-hand sides of script_walk, or raise."""
    body, _ = brace_block(code, code.index("void script_walk("))
    inner = norm(body[1:-1])
    statements = re.findall(r"w\[(\d+)\] = ([^;]+);", inner)
    rebuilt = " ".join("w[{0}] = {1};".format(i, e) for i, e in statements)
    if rebuilt != inner or [int(i) for i, _ in statements] != list(range(12)):
        raise AssertionError("script_walk's body is not exactly w[0] .. w[11] in index order")
    return [e for _, e in statements]


def tiers(env, small):
    if small:
        return env["k_prep_pool2_small"], env["k_prep_pool3_small"]
    return env["k_prep_pool2_bytes"], env["k_prep_pool3_bytes"]


def split_default(split, name):
    """A default share from ios_jit_split.h, in bytes."""
    m = re.search(r"#define " + name + r" (\d+)ull\b", without_comments(split))
    if m is None:
        raise AssertionError(name + " not found in ios_jit_split.h")
    return int(m.group(1)) << 20


def shares(env, split, small):
    """The walk's first tiers with no history: the default shares clamped to each tier's size."""
    pool2, pool3 = tiers(env, small)
    return (min(split_default(split, "ARMSX3_JIT_SPLIT_SHARE2_MIB"), pool2),
            min(split_default(split, "ARMSX3_JIT_SPLIT_SHARE3_MIB"), pool3))


def evaluated_walk(code, env, small, split):
    pool2, pool3 = tiers(env, small)
    share2, share3 = shares(env, split, small)
    scope = dict(env, pool2_bytes=pool2, pool3_bytes=pool3, share2=share2, share3=share3)
    return [evaluate(e, scope) for e in walk_statements(code)]


def expected_walk(env, small, split):
    """D1, written out from the constants by this guard."""
    pool2, pool3 = tiers(env, small)
    share2, share3 = shares(env, split, small)
    p3 = env["k_pool3_off"] + share3
    # Pool 2's first 16 MiB comes first, so a walk that runs out of time still gives pool 2 room to start.
    f2 = env["k_walk_pool2_floor"]
    return [0, f2,
            env["k_stub_off"], env["k_exec_half_size"],
            env["k_pool1_off"], p3,
            f2, share2,
            p3, env["k_pool3_off"] + pool3,
            share2, pool2]


def pages_of(walk):
    for i in range(0, len(walk), 2):
        a = walk[i]
        while a < walk[i + 1]:
            yield a
            a += PAGE


def model_extents(walk, env, pages, stop_off):
    """A Python mirror of proven_extents: (pool 2 extent, pool 3 extent)."""
    end2, end3 = 0, env["k_pool3_off"]
    for a in pages_of(walk):
        if pages <= 0 or a == stop_off:
            break
        pages -= 1
        if a == end2 and a < env["k_pool2_size"]:
            end2 = a + PAGE
        if a == end3 and a < env["k_pool3_off"] + env["k_pool3_size"]:
            end3 = a + PAGE
    gran = env["k_pool3_granule"]
    return end2, (end3 - env["k_pool3_off"]) // gran * gran


def script_format(code):
    start = code.index("constexpr char k_script_format[] =")
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', code[start:code.index('";', start) + 2]))


def problems(dual, header, split):
    code = without_comments(dual)
    d = norm(code)
    s = without_comments(split)
    out = []

    # The tier checks read the split header's default shares.
    if ("#define ARMSX3_JIT_SPLIT_SHARE3_MIB 240ull" not in s
            or "#define ARMSX3_JIT_SPLIT_SHARE2_MIB 152ull" not in s
            or "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
               "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) <= k_prep_pool2_bytes," not in d
            or "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
               "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) <= k_prep_pool3_bytes," not in d):
        out.append("tiers: the default shares are not 240 MiB (pool 3) and 152 MiB (pool 2), "
                   "each static_asserted inside its normal prepare")

    try:
        env = constants(dual, header)
        for small in (False, True):
            got, want = evaluated_walk(code, env, small, split), expected_walk(env, small, split)
            if got != want:
                out.append("order: the {0} walk is {1}, D1 says {2}".format(
                    "small" if small else "normal", [hex(v) for v in got], [hex(v) for v in want]))
        tier1 = sum(1 for _ in pages_of(expected_walk(env, False, split)[:8]))
        if tier1 != 26114 or tier1 > MEASURED_PAGES:
            out.append("order: the first four ranges are {0} pages, expected 26,114 within the measured 26,242".format(tier1))
    except (ValueError, KeyError, AssertionError, SyntaxError, NameError, TypeError) as e:
        out.append("order: the walk could not be evaluated from the source ({0})".format(e))

    try:
        s = re.search(r"S=\[([^\]]*)\]", script_format(code))
        if s is None or s.group(1) != "%s":
            out.append("render: k_script_format's S array is not the rendered walk (S=[%s])")
    except ValueError:
        out.append("render: k_script_format not found")
    if "constexpr int k_script_walk_words = 12;" not in d:
        out.append("render: k_script_walk_words is not 12")
    try:
        sig = "const char* build_prepare_script(unsigned long long base, const unsigned long long* walk, long long* len)"
        body = norm(brace_block(code, code.index(sig))[0])
        rendered = re.findall(r"std::snprintf\([^;]*?k_script_format, base, report, \w+, s_script_budget_ms, offsets\)", body)
        if ('"0x%llx"' not in body or "k_script_walk_words" not in body or len(rendered) != 2
                or body.count("k_script_format") != 2):
            out.append("render: build_prepare_script does not render the walk with \"0x%llx\" into both snprintf calls")
    except ValueError:
        out.append("render: build_prepare_script does not take the walk")
    if ROUTE_STATEMENT not in d:
        out.append("render: the StikDebug route does not build its script from stikdebug_walk()")
    try:
        body = norm(brace_block(code, code.index("const unsigned long long* stikdebug_walk()"))[0])
        if body != ("{ choose_prepare_sizes(); script_walk(prep_pool2_bytes, prep_pool3_bytes, split.share2, split.share3, "
                    "script_walk_offsets); return script_walk_offsets; }"):
            out.append("render: stikdebug_walk does not size the walk with choose_prepare_sizes() before script_walk")
    except ValueError:
        out.append("render: stikdebug_walk not found")

    try:
        prepare = code[code.index("void prepare_whole_executable_half("):]
        ok, _ = brace_block(prepare, prepare.index("else if (a.ok.load())"))
        ok = norm(ok)
        at = [ok.find(n) for n in ("read_script_report();", PROVEN_CALL, "cap_pools_to_partial_prepare();")]
        if -1 in at or at != sorted(at):
            out.append("cap: the handshake-ok branch does not read the report, prove the extents, then cap, in that order")
    except ValueError:
        out.append("cap: the handshake-ok branch not found")
    caps = len(re.findall(r"\bcap_pools_to_partial_prepare\(\);", code))
    if caps != 2:
        out.append("cap: cap_pools_to_partial_prepare(); occurs {0} times in code, expected 2 (built-in OK, handshake ok)".format(caps))

    try:
        sig = ("void proven_extents(const unsigned long long* w, long long pages, long long stop_off, "
               "unsigned long long* pool2_bytes, unsigned long long* pool3_bytes)")
        body = norm(brace_block(code, code.index(sig))[0])
        for needle in ("unsigned long long end2 = 0;", "unsigned long long end3 = k_pool3_off;",
                       "if (static_cast<long long>(a) == stop_off) { pages = 0; break; }", "pages--;",
                       "if (a == end2 && a < k_pool2_size) { end2 = a + 0x4000ull; }",
                       "if (a == end3 && a < k_pool3_off + k_pool3_size) { end3 = a + 0x4000ull; }",
                       "*pool2_bytes = end2;", "*pool3_bytes = (end3 - k_pool3_off) / k_pool3_granule * k_pool3_granule;"):
            if needle not in body:
                out.append("extents: proven_extents lacks " + needle)
    except ValueError:
        out.append("extents: proven_extents not found")
    return out


class TheSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dual, cls.header, cls.split = dual_map_text(CORE_DIR), read(HEADER), read(SPLIT)
        cls.code = without_comments(cls.dual)
        cls.env = constants(cls.dual, cls.header)
        cls.normal = evaluated_walk(cls.code, cls.env, False, cls.split)
        cls.small = evaluated_walk(cls.code, cls.env, True, cls.split)


class TheWalkHolds(TheSources):
    def test_real_files(self):
        self.assertEqual([], problems(self.dual, self.header, self.split))

    def test_walk_lengths(self):
        self.assertEqual(54274, sum(1 for _ in pages_of(self.normal)))
        self.assertEqual(33794, sum(1 for _ in pages_of(self.small)))
        self.assertEqual(self.small[10], self.small[11])  # range 6 is empty at the small sizes
        self.assertEqual(0x59000000, self.small[9])

    def ext(self, pages, stop=-1, walk=None):
        return model_extents(self.normal if walk is None else walk, self.env, pages, stop)

    def test_model_measured_rate(self):
        # 152 MiB of pool 2, 242 MiB of pool 3.
        self.assertEqual((0x9800000, 0xf200000), self.ext(MEASURED_PAGES))

    def test_model_measured_15s(self):
        # The default shares stay; the 15 s walk's extra pages go to the rest of pool 3.
        self.assertEqual((0x9800000, 0x16d94000), self.ext(MEASURED_15S_PAGES))

    def test_model_tier_edges(self):
        # Pool 2's 16 MiB floor comes first.
        self.assertEqual((0x1000000, 0x400000), self.ext(2306))
        self.assertEqual((0x1000000, 0x190000), self.ext(2150))
        self.assertEqual((0x800000, 0), self.ext(512))
        self.assertEqual((0x17d4000, 0xf000000), self.ext(17911))
        self.assertEqual((0x9800000, 0xf7a0000), self.ext(26602))

    def test_model_refusals(self):
        # A refusal inside pool 2's floor ends the walk there.
        self.assertEqual((0x100000, 0), self.ext(30000, 0x100000))
        self.assertEqual((0x1000000, 0x1000000), self.ext(30000, 0x42000000))

    def test_model_slow_walk_keeps_pool2_and_pool1(self):
        # The Last of Us demo walked 11,606 pages in 15 s and ended with pool 2 at 0 MiB. Now it keeps 16 MiB.
        self.assertEqual((0x1000000, 0x9550000), self.ext(11606))
        # Pool 1 is whole after the floor, the stubs and pool 1 (2,050 pages), inside the shortest budget at the slowest rate (3,868).
        through_pool1 = sum(1 for _ in pages_of(self.normal[:6])) - (self.normal[5] - self.env["k_pool3_off"]) // PAGE
        self.assertEqual(2050, through_pool1)
        self.assertLess(through_pool1, 11606 * 5000 // 15000)

    def test_model_whole_walks_and_bounds(self):
        self.assertEqual((0, 0), self.ext(0))
        self.assertEqual((0x10000000, 0x24000000), self.ext(54274))
        self.assertEqual((0x8000000, 0x18000000), self.ext(33794, walk=self.small))
        self.assertEqual((self.env["k_prep_pool2_bytes"], self.env["k_prep_pool3_bytes"]), self.ext(10 ** 9))


@unittest.skipUnless(shutil.which("node"), "node is not on PATH")
class TheRenderedScriptRuns(TheSources):
    REPORT = 0x100000

    def render(self, walk, base, budget=None):
        fmt = script_format(self.code).replace("%llx", "%x")
        if budget is None:  # the compiled default
            budget = int(re.search(r"constexpr int k_script_budget_ms = (\d+);", self.code).group(1))
        offsets = ",".join("0x%x" % v for v in walk)
        n = len(fmt % (base, self.REPORT, 0, budget, offsets))
        return fmt % (base, self.REPORT, n, budget, offsets), budget

    def run_node(self, walk, base, frozen, refuse, budget=None):
        text, _ = self.render(walk, base, budget)
        prelude = ("var log=[],pw=0;function send_command(p){log.push(p);var m=/^M([0-9a-f]+),1:69$/.exec(p);"
                   "if(m){pw++;if(parseInt(m[1],16)-%d===%d)return 'E01'}return 'OK'}"
                   "Date.now=function(){return %s?0:Math.floor(pw*%d/%d)};" % (base, refuse, "true" if frozen else "false",
                                                                                MEASURED_MS, MEASURED_PAGES))
        out = subprocess.run(["node", "-e", prelude + text + ";console.log(JSON.stringify(log))"],
                             capture_output=True, text=True, timeout=120, check=True).stdout
        log = json.loads(out)
        writes = [int(m.group(1), 16) - base for m in (re.match(r"^M([0-9a-f]+),1:69$", p) for p in log) if m]
        last = re.match(r"^M([0-9a-f]+),30:([0-9a-f]{96})$", log[-1])
        self.assertIsNotNone(last, log[-1])
        self.assertEqual(self.REPORT + 24, int(last.group(1), 16))
        words = [int.from_bytes(bytes.fromhex(last.group(2)[k * 16:(k + 1) * 16]), "little") for k in range(6)]
        return writes, words

    def budget_range(self):
        split = without_comments(read(SPLIT))
        return [int(re.search(r"^#define ARMSX3_JIT_BUDGET_{0}_MS (\d+)$".format(e), split, re.M).group(1))
                for e in ("MIN", "MAX")]

    def test_text_fits(self):
        # Also at the stored budget's two ends.
        for walk in (self.normal, self.small):
            for base in (0x7000000000, 0xffffffffffffffff):
                for budget in [None] + self.budget_range():
                    self.assertLess(len(self.render(walk, base, budget)[0]), 1000)

    def test_eval_stored_budget(self):
        # 15 s is the compiled default; at the 10 s measured rate it walks 15000 * 26242 / 10000 = 39,363 pages.
        self.assertEqual(15000, self.render(self.normal, 0x7000000000)[1])
        writes, words = self.run_node(self.normal, 0x7000000000, False, -1)
        self.assertEqual(list(pages_of(self.normal))[:39363], writes)
        self.assertEqual([39363, 0, 1], [words[1], words[2], words[5]])

    def test_eval_measured_rate(self):
        # A stored 10 s, the budget the measured walk ran under.
        writes, words = self.run_node(self.normal, 0x7000000000, False, -1, MEASURED_MS)
        self.assertEqual(list(pages_of(self.normal))[:MEASURED_PAGES], writes)
        self.assertEqual([MEASURED_PAGES, 0, 1], [words[1], words[2], words[5]])
        self.assertEqual((0x9800000, 0xf200000), model_extents(self.normal, self.env, words[1], -1))

    def test_eval_frozen_clock_one_refusal(self):
        writes, words = self.run_node(self.normal, 0x7000000000, True, 0x100000)
        self.assertEqual(list(pages_of(self.normal)), writes)
        self.assertEqual(54274, len(writes))
        self.assertEqual(0xfffc000, writes[-1])
        self.assertEqual([54273, 1, 0x100000, 0], [words[1], words[2], words[3], words[5]])
        self.assertEqual((0x100000, 0), model_extents(self.normal, self.env, words[1], words[3]))


class TheGuardCanFail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dual, cls.header, cls.split = dual_map_text(CORE_DIR), read(HEADER), read(SPLIT)

    def mutated(self, edits, prefix, split_edit=None):
        dual, split = self.dual, self.split
        for old, new in edits:
            self.assertTrue(old in dual, "mutation target missing: " + old)
            dual = dual.replace(old, new, 1)
        if split_edit is not None:
            self.assertTrue(split_edit[0] in split, "mutation target missing: " + split_edit[0])
            split = split.replace(split_edit[0], split_edit[1], 1)
        found = problems(dual, self.header, split)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def test_range6_past_prepare(self):
        self.mutated([("w[11] = pool2_bytes;", "w[11] = pool2_bytes + 0x4000ull;")], "order")

    def test_old_order_range3_ends_at_pool3(self):
        self.mutated([("w[5] = k_pool3_off + share3;", "w[5] = k_pool3_off;")], "order")

    def test_pool3_tier_doubled(self):
        self.mutated([], "tiers", ("#define ARMSX3_JIT_SPLIT_SHARE3_MIB 240ull", "#define ARMSX3_JIT_SPLIT_SHARE3_MIB 256ull"))

    def test_hard_coded_offsets(self):
        self.mutated([("S=[%s]", "S=[0,P,0x7fff8000,0x80000000,0x40000000,0x41000000]")], "render")

    def test_no_cap_in_ok_branch(self):
        self.mutated([("&prep_pool3_bytes);\n\t\t\t\tcap_pools_to_partial_prepare();", "&prep_pool3_bytes);")], "cap")

    def test_proven_before_report(self):
        self.mutated([("\t\t\t\tread_script_report();\n", ""),
                      ("&prep_pool3_bytes);\n\t\t\t\tcap_pools", "&prep_pool3_bytes);\n\t\t\t\tread_script_report();\n\t\t\t\tcap_pools")],
                     "cap")

    def test_started_counts_as_proof(self):
        self.mutated([('std::strcmp(script_outcome, "finished")', 'std::strcmp(script_outcome, "started")')], "cap")

    def test_no_stop_at_refusal(self):
        self.mutated([("static_cast<long long>(a) == stop_off", "false")], "extents")

    def test_no_granule_round_down(self):
        self.mutated([("(end3 - k_pool3_off) / k_pool3_granule * k_pool3_granule", "end3 - k_pool3_off")], "extents")

    def test_render_one_snprintf_keeps_the_constant(self):
        # The second snprintf renders the constant, so the measured length and the text disagree.
        self.mutated([("n, s_script_budget_ms, offsets)", "n, k_script_budget_ms, offsets)")], "render")

    def test_comment_only_edit_is_not_reported(self):
        dual = self.dual.replace("\tvoid proven_extents(", "\t// a comment-only edit\n\tvoid proven_extents(", 1)
        self.assertTrue(dual != self.dual, "the comment-only edit changed nothing")
        self.assertEqual([], problems(dual, self.header, self.split))


if __name__ == "__main__":
    unittest.main()
