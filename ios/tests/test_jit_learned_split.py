#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The learned JIT pool split: a C driver over ios/core/ios_jit_split.h gives the same prepare sizes and walk shares as
this file's model for every stored history, and the launcher, dual map and perf loop store, read and log the
history and budget as pinned. Rows measured over 10 s run at STORED_10S; it says nothing about a device."""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import brace_block, dual_map_text
from source_helpers import without_c_comments as without_comments
from test_stikdebug_prepare_order import constants, evaluate, model_extents, walk_statements

ROOT = Path(__file__).resolve().parents[2]
SPLIT = ROOT / "ios" / "core" / "ios_jit_split.h"
CORE_DIR = ROOT / "ios" / "core"
DUAL_HEADER = ROOT / "ios" / "core" / "ios_jit_dual_map.h"
LAUNCHER = ROOT / "ios" / "jit-enabler" / "StikJITExtensionLauncher.m"
DIAG = ROOT / "ios" / "bridge" / "diag_log.cpp"

MIB = 1 << 20
PAGE = 0x4000
ROUND = 0x200000
MAGIC = 0x41334A31
RING = 8
POOL2_MAX = 0x40000000
POOL3_MAX = 1056931840  # (k_stub_off - k_pool3_off) / k_pool3_granule * k_pool3_granule, 1008 MiB less 32 KiB
FIXED_PAGES = 1026      # (k_exec_half_size - k_stub_off + k_pool1_size) / 0x4000: the stubs and pool 1
BUDGET_MS = 15000  # the compiled default
STORED_10S = 10000  # a stored 10 s budget, the one the 10 s rates below were measured under
NORMAL = (256 * MIB, 576 * MIB)
SMALL = (128 * MIB, 384 * MIB)
IPHONE = (26242, 10000)  # iPhone18,2
IPAD = (18563, 10000)    # inferred from an iPad14,6 report
MK_IPAD = (18500, 10000)  # Mortal Kombat KE on a tester iPad
TLOU = (11606, 15000)  # The Last of Us demo, iPhone14,5: the walk ended with pool 2 at 0 MiB
IPHONE_15S = (34151, 15000)  # iPhone18,2, Debug, Mortal Kombat KE through StikDebug

# Stored budgets and what armsx3_jit_budget_ms(stored, BUDGET_MS) must return.
BUDGET_CASES = (0, -1, 4999, 5000, 12000, 15000, 18000, 25000, 25001, 30000, 4294982296, -4294967296)
PINNED_BUDGET = {0: 15000, -1: 15000, 4999: 15000, 5000: 5000, 12000: 12000, 15000: 15000, 18000: 18000,
                 25000: 25000, 25001: 15000, 30000: 15000, 4294982296: 15000, -4294967296: 15000}

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

DEFAULTS = (("ARMSX3_JIT_SPLIT_SHARE2_MIB", 152), ("ARMSX3_JIT_SPLIT_SHARE3_MIB", 240),
            ("ARMSX3_JIT_SPLIT_FLOOR2_MIB", 64), ("ARMSX3_JIT_SPLIT_FLOOR3_MIB", 128),
            ("ARMSX3_JIT_SPLIT_MARGIN_MIB", 32), ("ARMSX3_JIT_SPLIT_GROW_MIB", 64),
            ("ARMSX3_JIT_SPLIT_STEP_MIB", 2), ("ARMSX3_JIT_SPLIT_BUDGET_PERCENT", 90),
            ("ARMSX3_JIT_SPLIT_WALK2_MIB", 16))


def hist(sessions=(), rate=(0, 0), magic=MAGIC, count=None):
    return {"magic": magic, "count": len(sessions) if count is None else count,
            "sessions": list(sessions), "rate_pages": rate[0], "rate_ms": rate[1]}


BO2 = (128, 92, 1, 0)
# name: (history or None, low memory)
SCENARIOS = [
    ("none_null", None, False),
    ("none_normal", hist(), False),
    ("none_low", hist(), True),
    ("none_rate_iphone", hist(rate=IPHONE), False),
    ("none_rate_ipad", hist(rate=IPAD), False),
    ("bo2_hit", hist([BO2]), False),
    ("bo2_hit_rate", hist([BO2], IPHONE), False),
    ("bo2_hit_ipad", hist([BO2], IPAD), False),
    ("bo2_cap152_rate", hist([(152, 92, 1, 0)], IPHONE), False),
    ("mk_need300_rate", hist([(240, 100, 0, 0)], IPHONE), False),
    # Mortal Kombat KE measured on the Mac (pool 3 228 MiB, pool 2 17 MiB) on a walk of about 18,500 pages in 10 s.
    ("mk_ipad", hist([(17, 228, 0, 0)], MK_IPAD), False),
    ("des_wwe", hist([(9, 236, 0, 0), (13, 288, 0, 0)]), False),
    ("des_wwe_rate", hist([(9, 236, 0, 0), (13, 288, 0, 0)], IPHONE), False),
    ("both_rate", hist([BO2, (9, 236, 0, 0)], IPHONE), False),
    ("both_ipad", hist([BO2, (9, 236, 0, 0)], IPAD), False),
    ("rayman_low", hist([(2, 162, 0, 0)]), True),
    ("rayman_normal", hist([(2, 162, 0, 0)]), False),
    ("pool3_hit", hist([(10, 266, 0, 1)]), False),
    ("pool3_hit_low", hist([(10, 266, 0, 1)]), True),
    ("corrupt_peak3", hist([(10, 2000, 0, 0)]), False),
    ("corrupt_hit2", hist([(10, 100, 7, 0)]), False),
    ("corrupt_magic", hist([BO2], magic=0x41334A30), False),
    ("corrupt_count", hist([BO2], count=9), False),
    ("valid_beside_corrupt", hist([(10, 2000, 0, 0), (2, 162, 0, 0)]), False),
    ("rate_zero", hist([BO2], (0, 0)), False),
    ("rate_ms_60001", hist([BO2], (26242, 60001)), False),
    ("rate_pages_past_half", hist([BO2], (131073, 10000)), False),
    ("full_ring", hist([(i, 100 + i, 0, 0) for i in range(8)], IPHONE), False),
    # These three run at the compiled default (AT_DEFAULT); every row above at a stored 10 s.
    ("both_iphone_15s", hist([BO2, (9, 236, 0, 0)], IPHONE_15S), False),
    ("mk_ipad_15s", hist([(17, 228, 0, 0)], MK_IPAD), False),
    ("both_ipad_15s", hist([BO2, (9, 236, 0, 0)], IPAD), False),
    # Pool 2's walk floor keeps a slow device at 16 MiB, and a pool 2 smaller than the floor keeps its whole size.
    ("rayman_slow", hist([(2, 162, 0, 0)], TLOU), True),
    ("tiny_pool2", hist([(2, 162, 0, 0)], TLOU), True),
]
# Pool 2's largest size, where a scenario overrides POOL2_MAX.
POOL2_MAX_OF = {"tiny_pool2": 8 * MIB}
AT_DEFAULT = ("both_iphone_15s", "mk_ipad_15s", "both_ipad_15s")


def budget_of(name):
    return BUDGET_MS if name in AT_DEFAULT else STORED_10S

# MiB: size2 size3 share2 share3 need2 need3 sessions budget_pages; a rate with no counted session is ignored.
NONE_NORMAL = (256, 576, 152, 240, 0, 0, 0, 0)
PINNED = {
    "none_null": NONE_NORMAL,
    "none_normal": NONE_NORMAL,
    "none_low": (128, 384, 128, 240, 0, 0, 0, 0),
    "none_rate_iphone": NONE_NORMAL,
    "none_rate_ipad": NONE_NORMAL,
    "bo2_hit": (256, 576, 240, 128, 240, 128, 1, 0),
    "bo2_hit_rate": (256, 576, 230, 120, 240, 128, 1, 26242),
    "des_wwe": (256, 576, 64, 360, 64, 360, 2, 0),
    "des_wwe_rate": (256, 576, 34, 318, 64, 360, 2, 26242),
    "both_rate": (256, 576, 176, 216, 240, 296, 2, 26242),
    "mk_ipad": (256, 576, 18, 228, 64, 286, 1, 18500),
    "rayman_low": (64, 204, 64, 204, 64, 204, 1, 0),
    "rayman_normal": (256, 576, 64, 204, 64, 204, 1, 0),
    "pool3_hit": (256, 576, 64, 500, 64, 500, 1, 0),
    "corrupt_peak3": NONE_NORMAL,
    "corrupt_hit2": NONE_NORMAL,
    "corrupt_magic": NONE_NORMAL,
    "corrupt_count": NONE_NORMAL,
    "valid_beside_corrupt": (256, 576, 64, 204, 64, 204, 1, 0),
    "rate_zero": (256, 576, 240, 128, 240, 128, 1, 0),
    "rate_ms_60001": (256, 576, 240, 128, 240, 128, 1, 0),
    "both_iphone_15s": (256, 576, 208, 256, 240, 296, 2, 34151),
    "mk_ipad_15s": (256, 576, 64, 286, 64, 286, 1, 27750),
    "both_ipad_15s": (256, 576, 186, 230, 240, 296, 2, 27844),
    "rayman_slow": (64, 204, 16, 128, 64, 204, 1, 7737),
    "tiny_pool2": (8, 204, 8, 128, 8, 204, 1, 7737),
}
# merge: r(1 MiB), r(2 MiB), noted.peak2, r(new hit2), r(same again); put: slot counts, then the oldest and newest left.
PINNED_MERGE = (0, 1, 2, 1, 0)
PINNED_PUT = (0, 1, 0, 1, 2, 7, 8, 1, 99)


def model_split(h, low, tier2, tier3, pool2_max, pool3_max, fixed_pages, budget_ms):
    """A Python mirror of armsx3_jit_split_compute: (size2, size3, share2, share3, need2, need3, sessions, budget)."""
    floor2, floor3 = 64 * MIB, 128 * MIB
    raw2 = raw3 = 0
    sessions = 0
    usable = h is not None and h["magic"] == MAGIC and h["count"] <= RING
    for p2, p3, h2, h3 in (h["sessions"][:h["count"]] if usable else []):
        if p2 * MIB > pool2_max or p3 * MIB > pool3_max or h2 > 1 or h3 > 1:
            continue

        def raw(peak, hit):
            peak *= MIB
            return max(peak * 3 // 2, peak + 64 * MIB) if hit else peak
        raw2, raw3 = max(raw2, raw(p2, h2)), max(raw3, raw(p3, h3))
        sessions += 1
    if sessions == 0:
        return (tier2, tier3, min(152 * MIB, tier2), min(240 * MIB, tier3), 0, 0, 0, 0)

    def need(r, floor, top):
        n = -(-(r + max(r // 4, 32 * MIB)) // ROUND) * ROUND
        return min(max(n, floor), top)
    need2, need3 = need(raw2, floor2, pool2_max), need(raw3, floor3, pool3_max)
    size2 = need2 if low else max(tier2, need2)
    size3 = need3 if low else max(tier3, need3)
    share2, share3 = need2, need3
    budget = 0
    if 0 < h["rate_ms"] <= 60000 and 0 < h["rate_pages"] <= 131072:
        budget = h["rate_pages"] * budget_ms // h["rate_ms"]
    if budget:
        target = budget * 90 // 100
        total = share2 // PAGE + share3 // PAGE
        if fixed_pages + total > target:
            # Margins first, then the 10 % headroom, then below the bare peaks, never below min(floor, peak).
            bare2 = min(-(-raw2 // ROUND) * ROUND, need2)
            bare3 = min(-(-raw3 // ROUND) * ROUND, need3)
            bare = bare2 // PAGE + bare3 // PAGE
            if fixed_pages + bare <= target:
                spare = target - fixed_pages - bare
                share2 = bare2 + (need2 - bare2) * spare // (total - bare) // ROUND * ROUND
                share3 = bare3 + (need3 - bare3) * spare // (total - bare) // ROUND * ROUND
            elif fixed_pages + bare <= budget:
                share2, share3 = bare2, bare3
            else:
                avail = max(budget - fixed_pages, 0)
                share2 = max(min(floor2, bare2), bare2 * avail // bare // ROUND * ROUND)
                share3 = max(min(floor3, bare3), bare3 * avail // bare // ROUND * ROUND)
    share2 = max(share2, min(16 * MIB, size2))  # the walk's pool-2 floor
    return (size2, size3, share2, share3, need2, need3, sessions, budget)


def model_for(name, h, low):
    tier2, tier3 = SMALL if low else NORMAL
    return model_split(h, low, tier2, tier3, POOL2_MAX_OF.get(name, POOL2_MAX), POOL3_MAX, FIXED_PAGES, budget_of(name))


def in_mib(row):
    return tuple(v // MIB for v in row[:6]) + tuple(row[6:])


def c_history(h):
    sessions = (h["sessions"] + [(0, 0, 0, 0)] * RING)[:RING]
    body = ", ".join("{%du, %du, %du, %du}" % s for s in sessions)
    return "{0x%xu, %du, {%s}, %du, %du}" % (h["magic"], h["count"], body, h["rate_pages"], h["rate_ms"])


def driver_source():
    rows = []
    for name, h, low in SCENARIOS:
        tier2, tier3 = SMALL if low else NORMAL
        if h is None:
            rows.append('    row("%s", NULL, %d, %dull, %dull, %dull, %dull);' % (name, 1 if low else 0, tier2, tier3,
                                                                            POOL2_MAX_OF.get(name, POOL2_MAX), budget_of(name)))
        else:
            rows.append('    { static const struct armsx3_jit_pool_history h = %s; row("%s", &h, %d, %dull, %dull, %dull, %dull); }'
                        % (c_history(h), name, 1 if low else 0, tier2, tier3, POOL2_MAX_OF.get(name, POOL2_MAX),
                           budget_of(name)))
    return r"""
#include "ios_jit_split.h"
#include <stdio.h>

static void row(const char* name, const struct armsx3_jit_pool_history* h, int low, unsigned long long t2, unsigned long long t3,
                unsigned long long p2max, unsigned long long budget)
{
    struct armsx3_jit_split s;
    armsx3_jit_split_compute(h, low, t2, t3, p2max, %dull, %dull, budget, &s);
    printf("%%s %%llu %%llu %%llu %%llu %%llu %%llu %%u %%llu\n", name, s.size2, s.size3, s.share2, s.share3, s.need2, s.need3,
           s.sessions, s.budget_pages);
}

int main(void)
{
%s
    {
        struct armsx3_jit_pool_session noted = {0, 0, 0, 0};
        const struct armsx3_jit_pool_session one = {1, 0, 0, 0}, two = {2, 0, 0, 0}, hit = {2, 0, 1, 0};
        const int r1 = armsx3_jit_session_merge(&noted, &one);
        const int r2 = armsx3_jit_session_merge(&noted, &two);
        const unsigned p2 = noted.peak2_mib;
        const int r3 = armsx3_jit_session_merge(&noted, &hit);
        const int r4 = armsx3_jit_session_merge(&noted, &hit);
        printf("merge %%d %%d %%u %%d %%d\n", r1, r2, p2, r3, r4);
    }
    {
        struct armsx3_jit_pool_history h;
        struct armsx3_jit_pool_session s = {1, 0, 0, 0};
        int slot = -1, slot0, slot1;
        unsigned c0, c1, p0;
        memset(&h, 0, sizeof h);
        armsx3_jit_history_put(&h, &slot, &s);
        slot0 = slot;
        c0 = h.count;
        s.peak2_mib = 2;
        armsx3_jit_history_put(&h, &slot, &s);
        slot1 = slot;
        c1 = h.count;
        p0 = h.session[0].peak2_mib;
        for (unsigned i = 0; i < 8; i++)
            h.session[i].peak2_mib = 10 + i;
        h.count = 8;
        slot = -1;
        s.peak2_mib = 99;
        armsx3_jit_history_put(&h, &slot, &s);
        printf("put %%d %%u %%d %%u %%u %%d %%u %%u %%u\n", slot0, c0, slot1, c1, p0, slot, h.count,
               h.session[0].peak2_mib - 10, h.session[7].peak2_mib);
    }
    {
        static const long long cases[] = {%s};
        for (unsigned i = 0; i < sizeof cases / sizeof cases[0]; i++)
            printf("budget %%lld %%d\n", cases[i], armsx3_jit_budget_ms(cases[i], %d));
    }
    return 0;
}
""" % (POOL3_MAX, FIXED_PAGES, "\n".join(rows), ", ".join("%dLL" % v for v in BUDGET_CASES),
       BUDGET_MS)


def run_driver(header_text):
    """Compile the driver against `header_text`; return (rows, merge, put, budget) or raise AssertionError."""
    compiler = shutil.which("cc")
    if compiler is None:
        raise unittest.SkipTest("no `cc` on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "ios_jit_split.h").write_text(header_text, encoding="utf-8")
        driver = Path(tmp) / "driver.c"
        binary = Path(tmp) / "driver"
        driver.write_text(driver_source(), encoding="utf-8")
        built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", tmp, str(driver), "-o", str(binary)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                               universal_newlines=True)
        if built.returncode != 0:
            raise AssertionError("the driver does not compile: " + built.stdout)
        ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True)
        if ran.returncode != 0:
            raise AssertionError("the driver failed: " + ran.stdout)
    rows, merge, put, budget = {}, None, None, {}
    for line in ran.stdout.splitlines():
        f = line.split()
        if f[0] == "budget":
            budget[int(f[1])] = int(f[2])
        elif f[0] == "merge":
            merge = tuple(int(v) for v in f[1:])
        elif f[0] == "put":
            put = tuple(int(v) for v in f[1:])
        else:
            rows[f[0]] = tuple(int(v) for v in f[1:])
    return rows, merge, put, budget


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing; this guard fails rather than skipping".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def norm(text):
    return " ".join(text.split())


def without_strings(code):
    return re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', code)


def body(code, signature):
    at = code.find(signature)
    if at < 0:
        return None
    return norm(brace_block(code, at)[0])


LN9_HEAD = ("#if TARGET_OS_IPHONE available_at_launch = static_cast<unsigned long long>(os_proc_available_memory()); #endif "
            "const bool small = available_at_launch != 0 && available_at_launch < k_prep_small_below; "
            "prep_pool2_bytes = small ? k_prep_pool2_small : k_prep_pool2_bytes; "
            "prep_pool3_bytes = small ? k_prep_pool3_small : k_prep_pool3_bytes;")
COMPUTE = ("armsx3_jit_split_compute(&history, small ? 1 : 0, prep_pool2_bytes, prep_pool3_bytes, k_pool2_size, "
           "(k_stub_off - k_pool3_off) / k_pool3_granule * k_pool3_granule, (k_exec_half_size - k_stub_off + k_pool1_size) / "
           "0x4000ull, static_cast<unsigned long long>(s_script_budget_ms), &split);")
APPLY = "{ " + LN9_HEAD + " read_pool_history(); " + COMPUTE + " prep_pool2_bytes = split.size2; prep_pool3_bytes = split.size3; }"
LAYOUT = (
    "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
    "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) <= k_prep_pool2_bytes,",
    "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
    "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) <= k_prep_pool3_bytes,",
    "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
    "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB) <= k_prep_pool2_small,",
    "static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR3_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && "
    "ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR3_MIB) <= k_prep_pool3_small,",
    "static_assert(ARMSX3_JIT_SPLIT_ROUND % k_pool3_granule == 0 && ARMSX3_JIT_SPLIT_PAGE == 0x4000ull && "
    "k_pool3_granule % ARMSX3_JIT_SPLIT_PAGE == 0,",
)
STORE_NAMES = ("armsx3_jit_pool_history_read", "armsx3_jit_pool_history_write")
BUDGET_TAKEN = "s_script_budget_ms = armsx3_jit_budget_ms(stored_budget_ms, k_script_budget_ms);"


def layout_values(dual):
    """The two layout expressions choose_prepare_sizes passes, evaluated here with integer arithmetic."""
    code = without_comments(dual)

    def const(name):
        m = re.search(r"constexpr unsigned long long " + name + r"\s*=\s*(0x[0-9a-fA-F]+)ull;", code)
        if m is None:
            raise AssertionError(name + " not found")
        return int(m.group(1), 16)
    granule = PAGE  # utils::jit_pool3_unit_granularity; the layout static_assert pins it to whole pages dividing ROUND
    pool3_max = (const("k_stub_off") - const("k_pool3_off")) // granule * granule
    fixed = (const("k_exec_half_size") - const("k_stub_off") + const("k_pool1_size")) // PAGE
    return pool3_max, fixed


def problems(texts):
    split, dual = texts["split"], texts["dual"]
    s = without_comments(split)
    code = without_comments(dual)
    d = norm(code)
    out = []

    try:
        rows, merge, put, budget = run_driver(split)
        for name, h, low in SCENARIOS:
            want = model_for(name, h, low)
            if rows.get(name) != want:
                out.append("rule: {0}: the driver gives {1}, the model {2}".format(name, rows.get(name), want))
            elif name in PINNED and in_mib(want) != PINNED[name]:
                out.append("rule: {0}: {1} MiB, pinned {2}".format(name, in_mib(want), PINNED[name]))
        if merge != PINNED_MERGE:
            out.append("rule: merge gives {0}, pinned {1}".format(merge, PINNED_MERGE))
        if put != PINNED_PUT:
            out.append("rule: put gives {0}, pinned {1}".format(put, PINNED_PUT))
        if budget != PINNED_BUDGET:
            out.append("rule: armsx3_jit_budget_ms gives {0}, pinned {1}".format(budget, PINNED_BUDGET))
    except AssertionError as e:
        out.append("rule: " + str(e))

    for name, value in DEFAULTS:
        if not re.search(r"#define {0} {1}ull\b".format(name, value), s):
            out.append("defaults: {0} is not {1}".format(name, value))
    if not re.search(r"#define ARMSX3_JIT_HISTORY_SESSIONS 8\b", s) or \
            '#define ARMSX3_JIT_HISTORY_KEY "armsx3.jit.poolHistory"' not in s:
        out.append("defaults: the ring is not 8 sessions under armsx3.jit.poolHistory")
    # The budget key and its 5000..25000 ms range.
    if '#define ARMSX3_JIT_BUDGET_KEY "armsx3.jit.scriptBudgetMs"' not in s \
            or not re.search(r"^#define ARMSX3_JIT_BUDGET_MIN_MS 5000$", s, re.M) \
            or not re.search(r"^#define ARMSX3_JIT_BUDGET_MAX_MS 25000$", s, re.M):
        out.append("defaults: the budget is not kept inside 5000..25000 ms under armsx3.jit.scriptBudgetMs")
    for needle in ("constexpr int k_script_budget_ms = 15000;",
                   "static_assert(k_script_budget_ms >= ARMSX3_JIT_BUDGET_MIN_MS && k_script_budget_ms <= "
                   "ARMSX3_JIT_BUDGET_MAX_MS && ARMSX3_JIT_BUDGET_MAX_MS < 100000,",
                   "static_assert(ARMSX3_JIT_BUDGET_MAX_MS < k_prepare_timeout_polls * k_prepare_poll_ms,"):
        if needle not in d:
            out.append("defaults: the dual map lacks " + needle)

    for needle in LAYOUT:
        if needle not in d:
            out.append("layout: the dual map lacks " + needle)

    reader = body(code, "void read_pool_history()")
    if reader is None:
        out.append("reader: read_pool_history() not found")
    else:
        looked = re.findall(r'::dlsym\(RTLD_DEFAULT, "(\w+)"\)', reader)
        if sorted(looked) != sorted(STORE_NAMES) or reader.count("dlsym(") != 2:
            out.append("reader: read_pool_history() does not hold exactly the two store lookups ({0})".format(looked))
        for needle in ("!= 1", "|| history.magic != ARMSX3_JIT_HISTORY_MAGIC", "|| history.count > ARMSX3_JIT_HISTORY_SESSIONS",
                       "std::memset(&history, 0, sizeof history);", "history.magic = ARMSX3_JIT_HISTORY_MAGIC;",
                       "long long stored_budget_ms = 0;",
                       "reinterpret_cast<history_read_fn>(read_entry)(&history, &stored_budget_ms)"):
            if needle not in reader:
                out.append("reader: read_pool_history() lacks " + needle)
        if not reader.endswith(BUDGET_TAKEN + " }") or reader.find(BUDGET_TAKEN) < reader.find("history.magic = ARMSX3_JIT_HISTORY_MAGIC;"):
            out.append("reader: read_pool_history() does not end with {0} after the reset block".format(BUDGET_TAKEN))
    if "using history_read_fn = int (*)(armsx3_jit_pool_history*, long long*);" not in d:
        out.append("reader: history_read_fn does not take the budget out-parameter")
    assigned = re.findall(r"\bs_script_budget_ms\s*=(?!=)", code)
    if len(assigned) != 2 or "int s_script_budget_ms = k_script_budget_ms;" not in d:
        out.append("reader: s_script_budget_ms is assigned {0} times, not only at its definition and in "
                   "read_pool_history()".format(len(assigned)))
    bare = without_strings(code)
    for name in STORE_NAMES:
        if re.search(r"\b" + name + r"\b", bare):
            out.append("reader: the dual map names {0} outside a string literal".format(name))
    if re.findall(r"\bgetenv\s*\(", code):
        out.append("reader: the dual map reads the environment with getenv(")

    choose = body(code, "void choose_prepare_sizes()")
    if choose != APPLY:
        out.append("apply: choose_prepare_sizes() is not ln9's four lines, read_pool_history(), the compute call and "
                   "the two size assignments")
    try:
        if layout_values(dual) != (POOL3_MAX, FIXED_PAGES):
            out.append("apply: the layout expressions are {0}, expected ({1}, {2})".format(layout_values(dual), POOL3_MAX, FIXED_PAGES))
    except AssertionError as e:
        out.append("apply: " + str(e))
    out += store_problems(texts["launcher"])
    out += note_problems(code, without_comments(texts["header"]))
    out += perf_problems(without_comments(texts["diag"]))
    out += report_problems(code, without_comments(texts["header"]))
    return out


EXTENSION_WORDS = ("NSExtension", "beginExtensionRequest", "prepareForParentPID")


def store_problems(launcher):
    code = without_comments(launcher)
    out = []
    if '#include "../core/ios_jit_split.h"' not in code:
        out.append("store: the launcher does not include ../core/ios_jit_split.h")
    for name in STORE_NAMES:
        defs = re.findall(r'__attribute__\(\(used, visibility\("default"\)\)\)\s*\w+ ' + name + r"\(", code)
        if len(defs) != 1 or len(re.findall(r"\b" + name + r"\(", code)) != 1:
            out.append("store: {0} is not defined exactly once, used and default-visible".format(name))
    reader = body(code, "int armsx3_jit_pool_history_read(")
    writer = body(code, "void armsx3_jit_pool_history_write(")
    if reader is None or writer is None:
        out.append("store: the reader or the writer has no body")
        return out
    for needle in ("memset(out, 0, sizeof *out);",
                   "CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_HISTORY_KEY), kCFPreferencesCurrentApplication)",
                   "CFGetTypeID(value) == CFDataGetTypeID()",
                   "CFDataGetLength((CFDataRef)value) == (CFIndex)sizeof *out",
                   "CFDataGetBytes(", "CFRelease(value);"):
        if needle not in reader:
            out.append("store: the reader lacks " + needle)
    for needle in ("CFDataCreate(", "(CFIndex)sizeof *in",
                   "CFPreferencesSetAppValue(CFSTR(ARMSX3_JIT_HISTORY_KEY), data, kCFPreferencesCurrentApplication);",
                   "CFRelease(data);", "CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);"):
        if needle not in writer:
            out.append("store: the writer lacks " + needle)
    # The budget is read here and never written.
    if not reader.startswith("{ *budget_ms = 0;"):
        out.append("store: the reader does not set *budget_ms = 0; first")
    for needle in ("CFPreferencesCopyAppValue(CFSTR(ARMSX3_JIT_BUDGET_KEY), kCFPreferencesCurrentApplication)",
                   "CFGetTypeID(budget) == CFNumberGetTypeID()",
                   "CFNumberGetValue((CFNumberRef)budget, kCFNumberLongLongType, &stored)",
                   "*budget_ms = stored;", "CFRelease(budget);"):
        if needle not in reader:
            out.append("store: the reader lacks " + needle)
    if len(re.findall(r"\bARMSX3_JIT_BUDGET_KEY\b", code)) != 1 or "ARMSX3_JIT_BUDGET_KEY" in writer:
        out.append("store: the launcher names ARMSX3_JIT_BUDGET_KEY other than once, in the reader")
    if any(w in reader or w in writer for w in EXTENSION_WORDS):
        out.append("store: the reader or the writer names an extension")
    return out


NOTE_SIGNATURE = "void note_peaks(unsigned long long pool2_top, bool game_running)"


def note_problems(code, header):
    out = []
    if "void jit_dual_map_note_peaks(unsigned long long pool2_top, bool game_running) noexcept;" not in norm(header):
        out.append("note: the header does not declare jit_dual_map_note_peaks")
    apple = code[:code.index("THE PORTABLE ARM")] if "THE PORTABLE ARM" in code else code
    arms = [norm(brace_block(code, m.start())[0]) for m in re.finditer(r"void jit_dual_map_note_peaks\(", code)]
    if len(arms) != 2 or arms[0] != "{ dual_map_block& b = block(); if (!b.active) { return; } b.note_peaks(pool2_top, game_running); }" \
            or arms[1] != "{ }":
        out.append("note: jit_dual_map_note_peaks does not forward to an active block's note_peaks (Apple) and do nothing (portable)")
    del apple
    note = body(code, NOTE_SIGNATURE)
    if note is None:
        out.append("note: the block has no " + NOTE_SIGNATURE)
        return out
    if not note.startswith("{ if (!game_running) { return; }"):
        out.append("note: note_peaks does not return at once unless a game runs")
    rate = re.search(r"if \(!rate_noted\) \{ rate_noted = true; if \(([^{]*)\) \{", note)
    if rate is None or any(n not in rate.group(1) for n in (
            'std::strcmp(script_outcome, "finished") == 0', "script_budget_hit == 1", "script_pages_ok > 0",
            "script_elapsed_ms > 0")):
        out.append("note: the rate is not taken once, only from a finished walk that hit its budget")
    for needle in ("(pool2_top & 0x40000000ull) != 0", "top2 + 0x200000ull > utils::g_pool2_code_limit",
                   "utils::jit_pool_full_reason() == 1",
                   "const bool hit3 = carve_waits > 0 || units_peak >= pool3_prepared_granules"
                   " || utils::jit_pool_full_reason() == 3;"):
        if needle not in note:
            out.append("note: note_peaks lacks " + needle)
    merge = note.find("if (armsx3_jit_session_merge(&noted, &now))")
    if merge < 0 or not body(note, "if (armsx3_jit_session_merge(&noted, &now))").startswith(
            "{ armsx3_jit_history_put(&history, &history_slot, &noted); changed = true; }"):
        out.append("note: the session does not grow only through armsx3_jit_session_merge")
    if note.count("changed = true;") != 2:
        out.append("note: changed = true; occurs {0} times, expected 2 (the rate, the merge)".format(note.count("changed = true;")))
    write = body(note, "if (changed && history_write != nullptr)")
    if write != "{ history_write(&history); }" or note.count("history_write(") != 1:
        out.append("note: the writer is not called exactly once, only when something changed")
    return out


PERF_CALL = "utils::jit_dual_map_note_peaks(jit2_bytes, !Emu.IsStopped());"
LINE_FIELDS = ("route", "reason", "script", "pool2_mib", "pool3_mib", "pages_ok", "elapsed_ms", "budget_hit",
               "first_refused_off", "sessions", "budget_pages", "need2_mib", "need3_mib", "share2_mib", "share3_mib",
               "budget_ms")


def perf_problems(diag):
    out = []
    perf = body(diag, "void perf_loop()")
    if perf is None:
        return ["perf: perf_loop not found", "line: perf_loop not found"]
    at = perf.find("const u64 jit2_bytes =")
    if diag.count(PERF_CALL) != 1 or at < 0 or perf.find(PERF_CALL) < at:
        out.append("perf: perf_loop does not call {0} once, after jit2_bytes".format(PERF_CALL))
    literals = re.findall(r'"(jit prepare route=[^"]*)"', diag)
    fields = re.findall(r"(\w+)=", literals[0]) if len(literals) == 1 else []
    if tuple(fields) != LINE_FIELDS:
        out.append("line: there is not exactly one jit prepare literal with the sixteen fields in order ({0})".format(fields))
    told = body(perf, "if (!prepare_told)")
    if told is None or not told.startswith("{ prepare_told = true;") or 'diag_log.notice("jit prepare route=' not in told:
        out.append("line: the jit prepare line is not a notice inside if (!prepare_told), which sets prepare_told = true;")
    if told is None or not re.search(r'diag_log\.notice\("jit prepare route=[^;]*, jit\.script_budget_ms\);', told):
        out.append("line: the jit prepare notice's last argument is not jit.script_budget_ms")
    return out


REPORT_FIELDS = (("pool2_limit_bytes", "utils::g_pool2_code_limit"),
                 ("pool3_limit_bytes", "static_cast<unsigned long long>(b.pool3_prepared_granules) * k_pool3_granule"),
                 ("split_sessions", "b.split.sessions"), ("split_budget_pages", "b.split.budget_pages"),
                 ("split_need2_bytes", "b.split.need2"), ("split_need3_bytes", "b.split.need3"),
                 ("split_share2_bytes", "b.split.share2"), ("split_share3_bytes", "b.split.share3"))


def report_problems(code, header):
    out = []
    report = body(code, "void jit_dual_map_report_pool(jit_pool pool, jit_dual_map_report* out) noexcept")
    for field, value in REPORT_FIELDS:
        if "unsigned long long " + field + ";" not in header:
            out.append("report: the report struct lacks " + field)
        if report is None or "out->{0} = {1};".format(field, value) not in report:
            out.append("report: jit_dual_map_report_pool does not copy {0} from {1}".format(field, value))
    # The budget the script's T and the split used.
    if "long long script_budget_ms;" not in norm(header):
        out.append("report: the report struct lacks long long script_budget_ms;")
    if report is None or "out->script_budget_ms = s_script_budget_ms;" not in report:
        out.append("report: jit_dual_map_report_pool does not copy script_budget_ms from s_script_budget_ms")
    return out


def real_texts():
    return {"split": read(SPLIT), "dual": dual_map_text(CORE_DIR), "header": read(DUAL_HEADER), "launcher": read(LAUNCHER),
            "diag": read(DIAG)}


class TheSplitHolds(unittest.TestCase):
    def test_real_files(self):
        self.assertEqual([], problems(real_texts()))

    def test_model_matches_the_pins(self):
        for name, pinned in PINNED.items():
            h, low = next((h, low) for n, h, low in SCENARIOS if n == name)
            self.assertEqual(pinned, in_mib(model_for(name, h, low)), name)


    def test_walk_proves_the_mk_peaks_on_a_slow_walk(self):
        # Host simulation of the real script_walk for one MK session; mk_ipad runs at STORED_10S, its measured budget.
        dual = dual_map_text(CORE_DIR)
        env = constants(dual, read(DUAL_HEADER))
        h, low = next((h, low) for n, h, low in SCENARIOS if n == "mk_ipad")
        size2, size3, share2, share3 = model_for("mk_ipad", h, low)[:4]
        scope = dict(env, pool2_bytes=size2, pool3_bytes=size3, share2=share2, share3=share3)
        walk = [evaluate(e, scope) for e in walk_statements(without_comments(dual))]
        pool2, pool3 = model_extents(walk, env, MK_IPAD[0], -1)
        self.assertGreaterEqual(pool3, 228 * MIB)
        self.assertGreaterEqual(pool2, 17 * MIB)


class TheGuardCanFail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.texts = real_texts()

    def mutated(self, which, old, new, prefix):
        texts = dict(self.texts)
        self.assertTrue(old in texts[which], "mutation target missing: " + old)
        texts[which] = texts[which].replace(old, new, 1)
        found = problems(texts)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def test_margin_divisor(self):
        self.mutated("split", "raw / 4;", "raw / 8;", "rule")

    def test_walk2_floor_dropped(self):
        self.mutated("split", "\tout->share2 = out->share2 < walk2 ? walk2 : out->share2;\n", "", "rule")

    def test_floor3_lowered(self):
        self.mutated("split", "#define ARMSX3_JIT_SPLIT_FLOOR3_MIB 128ull", "#define ARMSX3_JIT_SPLIT_FLOOR3_MIB 96ull", "defaults")

    def test_compute_removed(self):
        self.mutated("dual", "\t\t\tarmsx3_jit_split_compute(", "\t\t\t(void)sizeof(", "apply")

    def test_direct_store_call(self):
        self.mutated("dual", "reinterpret_cast<history_read_fn>(read_entry)(&history, &stored_budget_ms)",
                     "armsx3_jit_pool_history_read(&history, &stored_budget_ms)", "reader")

    def test_magic_check_removed(self):
        self.mutated("dual", "|| history.magic != ARMSX3_JIT_HISTORY_MAGIC", "", "reader")

    def test_store_length_test_removed(self):
        self.mutated("launcher", "CFDataGetLength((CFDataRef)value) == (CFIndex)sizeof *out", "1", "store")

    def test_store_synchronise_removed(self):
        self.mutated("launcher", "    CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);\n", "", "store")

    def test_note_without_game(self):
        self.mutated("dual", "if (!game_running)\n\t\t\t{\n\t\t\t\treturn;\n\t\t\t}\n", "", "note")

    def test_note_merge_bypassed(self):
        self.mutated("dual", "if (armsx3_jit_session_merge(&noted, &now))", "if (true)", "note")

    def test_note_rate_without_budget_hit(self):
        self.mutated("dual", "script_budget_hit == 1", "script_budget_hit >= 0", "note")

    def test_perf_writes_without_game(self):
        self.mutated("diag", "jit_dual_map_note_peaks(jit2_bytes, !Emu.IsStopped());", "jit_dual_map_note_peaks(jit2_bytes, true);", "perf")

    def test_line_every_second(self):
        self.mutated("diag", "\t\t\t\tprepare_told = true;\n", "", "line")

    def test_line_at_error(self):
        self.mutated("diag", 'diag_log.notice("jit prepare route=', 'diag_log.error("jit prepare route=', "line")

    def test_report_copy_dropped(self):
        self.mutated("dual", "out->split_share3_bytes = b.split.share3;", "", "report")

    def test_budget_out_of_range_saturates(self):
        self.mutated("split", ": fallback;", ": ARMSX3_JIT_BUDGET_MAX_MS;", "rule")

    def test_budget_not_taken(self):
        self.mutated("dual", "\t\t\t" + BUDGET_TAKEN + "\n", "", "reader")

    def test_budget_compute_uses_the_constant(self):
        self.mutated("dual", "static_cast<unsigned long long>(s_script_budget_ms)",
                     "static_cast<unsigned long long>(k_script_budget_ms)", "apply")

    def test_budget_store_type_check_removed(self):
        self.mutated("launcher", "CFGetTypeID(budget) == CFNumberGetTypeID()", "1", "store")

    def test_budget_store_writes_the_key(self):
        line = "    CFPreferencesSetAppValue(CFSTR(ARMSX3_JIT_HISTORY_KEY), data, kCFPreferencesCurrentApplication);\n"
        self.mutated("launcher", line,
                     line + "    CFPreferencesSetAppValue(CFSTR(ARMSX3_JIT_BUDGET_KEY), data, kCFPreferencesCurrentApplication);\n",
                     "store")

    def test_budget_log_field_dropped(self):
        self.mutated("diag", ' budget_ms=%d"', '"', "line")

    def test_budget_report_copy_dropped(self):
        self.mutated("dual", "out->script_budget_ms = s_script_budget_ms;", "", "report")

    def test_note_pool3_stop_not_a_hit(self):
        self.mutated("dual", " || utils::jit_pool_full_reason() == 3;", ";", "note")

    def test_comment_only_edit_is_not_reported(self):
        texts = dict(self.texts)
        texts["dual"] = texts["dual"].replace("\t\tvoid read_pool_history()", "\t\t// a comment-only edit\n\t\tvoid read_pool_history()", 1)
        self.assertNotEqual(texts["dual"], self.texts["dual"])
        self.assertEqual([], problems(texts))


if __name__ == "__main__":
    unittest.main()
