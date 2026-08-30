#!/usr/bin/env python3
"""Diff two platforms' ps3autotests output per instruction: which opcodes differ on ARM vs x86.

This is the deliverable the tests exist for, and it is NOT the same as diffing either platform
against the .expected file. A diff against hardware answers "is this emulator perfect", which no
emulator is -- RPCS3 does not implement every status bit, and some tests print through paths whose
ABI makes them fragile. A diff of ARM against x86 answers the question that actually matters here:
which instructions behave DIFFERENTLY on the two backends. Only those can explain a game that
works on one and not the other.

Three columns per instruction, because the distinction decides who owns the bug:
  arm!=x86   the ARM backend diverges. Ours to fix.
  both!=hw   both backends differ from hardware the same way. Upstream, or a test artifact.
  arm!=hw    total, for context.

Usage:
  compare-platforms.py cpu/spu_fpu --arm results/cpu_spu_fpu.actual --x86 x86/spu_fpu.txt
  compare-platforms.py --arm-dir results/ --x86-dir x86/          # every test found in both
"""

import argparse
import glob
import os
import sys
from collections import Counter

REPO_DEFAULT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "ps3autotests"
)


def load(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return [ln.rstrip() for ln in fh.read().replace("\r\n", "\n").split("\n") if ln.strip()]


def mnemonic(line):
    head = line.split("(", 1)[0].strip() if "(" in line else ""
    parts = head.split()
    return parts[0] if parts else "<other>"


def expected_for(test, repo):
    hits = glob.glob(os.path.join(repo, "tests", test, "*.expected"))
    return load(hits[0]) if hits else None


def report(test, arm, x86, hw, args):
    n = min(len(arm), len(x86))
    if not n:
        print(f"{test}: nothing to compare (arm={len(arm)} x86={len(x86)} lines)")
        return None

    if len(arm) != len(x86):
        print(f"  NOTE: line counts differ (arm={len(arm)} x86={len(x86)}); comparing the first {n}")

    arm_vs_x86 = Counter()
    both_vs_hw = Counter()
    arm_vs_hw = Counter()
    total = Counter()
    examples = {}

    for i in range(n):
        a, b = arm[i], x86[i]
        h = hw[i] if hw and i < len(hw) else None
        m = mnemonic(b) if "(" in b else mnemonic(a)
        total[m] += 1

        if a != b:
            arm_vs_x86[m] += 1
            examples.setdefault(m, (i + 1, b, a, h))
        if h is not None:
            if a != h:
                arm_vs_hw[m] += 1
            if a == b and a != h:
                both_vs_hw[m] += 1

    ours = sum(arm_vs_x86.values())
    print(f"\n=== {test} ===")
    print(f"  {n} lines compared, {ours} differ between ARM and x86")

    if not ours:
        print("  ARM matches x86 exactly." + ("" if not hw else
              f"  ({sum(both_vs_hw.values())} lines where BOTH differ from hardware -- upstream, not ours)"))
        return 0

    print(f"  {'instruction':<12}{'arm!=x86':>10}{'both!=hw':>10}{'arm!=hw':>9}{'of':>8}")
    for m, c in arm_vs_x86.most_common(args.summary):
        print(f"  {m:<12}{c:>10}{both_vs_hw[m]:>10}{arm_vs_hw[m]:>9}{total[m]:>8}")

    print("\n  first ARM-vs-x86 differences:")
    for m, _c in arm_vs_x86.most_common(args.examples):
        ln, b, a, h = examples[m]
        print(f"    {m}  line {ln}")
        print(f"      x86     : {b}")
        print(f"      arm     : {a}")
        if h is not None:
            print(f"      hardware: {h}")
    return ours


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tests", nargs="*", help="test path(s) like cpu/spu_fpu, for the .expected lookup")
    ap.add_argument("--arm", help="ARM output file")
    ap.add_argument("--x86", help="x86 output file")
    ap.add_argument("--arm-dir", help="directory of ARM .actual files")
    ap.add_argument("--x86-dir", help="directory of x86 output files")
    ap.add_argument("--repo", default=REPO_DEFAULT, help="ps3autotests checkout, for .expected")
    ap.add_argument("--summary", type=int, default=30)
    ap.add_argument("--examples", type=int, default=4)
    args = ap.parse_args()

    pairs = []

    if args.arm and args.x86:
        test = args.tests[0] if args.tests else os.path.basename(args.arm).split(".")[0]
        pairs.append((test, args.arm, args.x86))
    elif args.arm_dir and args.x86_dir:
        # Match on the test's basename, so cpu_spu_fpu.actual pairs with spu_fpu.txt or
        # cpu_spu_fpu.actual -- whatever the desktop side happened to call it.
        #
        # Only .actual on our side, and never .diff on either: the runner writes <test>.diff
        # beside <test>.actual, and a bare glob pairs each test with its own diff as though that
        # were a second platform. That produced two entries per test and a verdict of "80292
        # differing lines" from a run that had none.
        def usable(path):
            return not os.path.isdir(path) and not path.endswith(".diff")

        arm_files = [a for a in sorted(glob.glob(os.path.join(args.arm_dir, "*.actual")))] or \
                    [a for a in sorted(glob.glob(os.path.join(args.arm_dir, "*"))) if usable(a)]

        for a in arm_files:
            key = os.path.basename(a).split(".")[0].replace("cpu_", "").replace("lv2_", "")
            hits = [b for b in sorted(glob.glob(os.path.join(args.x86_dir, "*")))
                    if key in os.path.basename(b) and usable(b)]
            if hits:
                pairs.append((key, a, hits[0]))
        if not pairs:
            sys.exit("no matching filenames between --arm-dir and --x86-dir")
    else:
        sys.exit("give --arm and --x86, or --arm-dir and --x86-dir")

    worst = 0
    for test, apath, bpath in pairs:
        lookup = test if "/" in test else next(
            (t for t in ("cpu/" + test, "lv2/" + test, "rsx/" + test)
             if glob.glob(os.path.join(args.repo, "tests", t, "*.expected"))), None)
        hw = expected_for(lookup, args.repo) if lookup else None
        d = report(test, load(apath), load(bpath), hw, args)
        worst = max(worst, d or 0)

    print("\n=== verdict ===")
    print("  ARM matches x86 on every compared line." if worst == 0 else
          f"  {worst} differing lines on the worst test -- those instructions are the ARM-specific ones.")
    sys.exit(0 if worst == 0 else 1)


if __name__ == "__main__":
    main()
