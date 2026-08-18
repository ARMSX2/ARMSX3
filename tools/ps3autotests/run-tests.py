#!/usr/bin/env python3
"""Run RPCS3/ps3autotests against ARMSX3 on a connected device and diff the output.

Each test in that repository is a PS3 program plus a .expected file holding the TTY output a
real PS3 produced. So a test is: boot the ELF, capture what it printed, compare. A difference
names an instruction and the exact operands that behave differently here than on hardware,
which is the part that guesswork cannot supply -- see --summary, which groups differences by
mnemonic and turns 80k lines of hex into "fma: 1847 wrong, everything else: clean".

Usage:
  run-tests.py --list
  run-tests.py cpu/spu_fpu
  run-tests.py cpu/spu_fpu cpu/spu_alu --out results/
  run-tests.py --all-spu

Notes:
  - The app is force-stopped between tests, and each test is booted through the VIEW intent the
    manifest already accepts. That means this DRIVES the device: do not run it while someone is
    using the app for something else.
  - TTY.log cannot be truncated from adb (it lives under Android/data, which shell may read but
    not write). The harness notes its size first, then watches: the app resets the log when it
    boots, so a size DROP means the whole file belongs to this test. Assuming the pre-launch size
    was a prefix silently produced a 29k-of-108k-line capture that read as a huge test failure.
  - A test is considered finished when TTY.log stops growing for --idle seconds. There is no
    completion marker in the protocol, and some tests print for a long time.
"""

import argparse
import os
import subprocess
import sys
import time
from collections import Counter

PKG = "com.armsx3"
ACTIVITY = f"{PKG}/com.armsx2.Main"
TTY = f"/sdcard/Android/data/{PKG}/files/cache/TTY.log"
DEVICE_DIR = "/sdcard/ARMSX3-autotests"

REPO_DEFAULT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "ps3autotests"
)


def adb(*args, binary=False, check=True):
    """Run adb and return stdout. exec-out is used for file reads so nothing mangles bytes."""
    proc = subprocess.run(
        ["adb", *args], capture_output=True, check=False
    )
    if check and proc.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def tty_size():
    out = adb("exec-out", f"wc -c < {TTY} 2>/dev/null || echo 0").strip()
    return int(out.split()[0]) if out.split() else 0


def discover(tests_root):
    """Every test directory holding both an .expected and a bootable ELF."""
    found = []
    for dirpath, _dirnames, filenames in os.walk(tests_root):
        expected = [f for f in filenames if f.endswith(".expected")]
        if not expected:
            continue
        # A .ppu.elf is the loader even for SPU tests: it uploads the .spu.elf and prints what
        # comes back, so booting the SPU ELF directly would skip the half that reports results.
        elf = next((f for f in filenames if f.endswith(".ppu.elf")), None)
        elf = elf or next((f for f in filenames if f.endswith(".elf")), None)
        if not elf:
            continue
        found.append(
            (os.path.relpath(dirpath, tests_root), dirpath, elf, os.path.join(dirpath, expected[0]))
        )
    return sorted(found)


def normalise(text):
    """Line endings and trailing blanks only. Nothing else -- the point is to compare hex."""
    return [ln.rstrip() for ln in text.replace("\r\n", "\n").replace("\r", "\n").split("\n") if ln.strip()]


def mnemonic(line):
    """Leading token of a test line, e.g. 'fma' from 'fma ([00],[01],[02]) -> ...'.

    Not every test prints instruction-shaped lines: the lv2 suites print prose, and a line may
    open with '(' or have nothing before it at all, which crashed this on an empty split.
    """
    head = line.split("(", 1)[0].strip() if "(" in line else ""
    parts = head.split()
    return parts[0] if parts else "<other>"


def run_one(name, dirpath, elf, expected_path, args):
    print(f"\n=== {name} ===", flush=True)

    remote_dir = f"{DEVICE_DIR}/{os.path.basename(dirpath)}"
    adb("shell", f"mkdir -p {remote_dir}")
    # Whole directory: SPU tests load a sibling .spu.elf by relative path.
    for f in sorted(os.listdir(dirpath)):
        full = os.path.join(dirpath, f)
        if os.path.isfile(full) and not f.endswith(".expected"):
            adb("push", "-q", full, f"{remote_dir}/{f}")

    adb("shell", f"am force-stop {PKG}", check=False)
    time.sleep(1.5)

    before = tty_size()
    adb(
        "shell",
        f"am start -a android.intent.action.VIEW -d file://{remote_dir}/{elf} -n {ACTIVITY}",
        check=False,
    )

    # Grown-then-quiet, because there is no end-of-test marker to wait for.
    #
    # The offset is decided by watching, not assumed: the app TRUNCATES TTY.log when it boots, so
    # a previous test's output is not a prefix of this one's. Slicing at the pre-launch size then
    # reads from the middle of a fresh file -- which produced a torn first line and 29k of 108k
    # lines, silently, and looked like a spectacular test failure rather than a harness bug. If the
    # size is ever seen below where it started, the log was reset and the whole file is ours.
    start = time.time()
    last_size, last_change = before, time.time()
    offset = before
    while True:
        time.sleep(2)
        size = tty_size()
        if size < offset:
            offset = 0
        if size != last_size:
            last_size, last_change = size, time.time()
            print(f"  ... {size - offset} bytes", end="\r", flush=True)
        elapsed_quiet = time.time() - last_change
        if size > offset and elapsed_quiet >= args.idle:
            break
        if time.time() - start > args.timeout:
            print(f"  timed out after {args.timeout}s", flush=True)
            break
        if size == offset and time.time() - start > args.boot_wait:
            print(f"  no output after {args.boot_wait}s -- did it boot?", flush=True)
            break

    raw = adb("exec-out", f"cat {TTY}", binary=True)
    adb("shell", f"am force-stop {PKG}", check=False)

    got = normalise(raw[offset:].decode("utf-8", errors="replace"))

    # Two output conventions, and only one of them is TTY.
    #
    # The SPU suites reach TTY through spu_printf and the lv2 ones print to it directly, but the
    # PPU suites fopen "/app_home/output.txt" and write there -- /app_home being the directory the
    # test was launched from, i.e. the one pushed above. Six PPU tests were reported as "NO OUTPUT"
    # for a whole run while their results sat on the device, so check the file whenever TTY is
    # empty rather than assuming a silent test failed to boot.
    if not got:
        from_file = adb("exec-out", f"cat {remote_dir}/output.txt 2>/dev/null", binary=True)

        if from_file:
            print(f"  (output.txt, {len(from_file)} bytes -- this suite writes a file, not TTY)")
            got = normalise(from_file.decode("utf-8", errors="replace"))
    with open(expected_path, "r", encoding="utf-8", errors="replace") as fh:
        want = normalise(fh.read())

    os.makedirs(args.out, exist_ok=True)
    stem = name.replace("/", "_")
    with open(os.path.join(args.out, f"{stem}.actual"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(got) + "\n")

    return compare(name, got, want, args, stem)


def compare(name, got, want, args, stem):
    if not got:
        print("  NO OUTPUT -- test did not run (or printed nothing)")
        return False

    # Positional compare: these tests are deterministic and ordered, so line N vs line N is
    # meaningful and far more useful than a fuzzy diff -- it keeps the operands aligned.
    diffs = []
    for i in range(min(len(got), len(want))):
        if got[i] != want[i]:
            diffs.append((i + 1, want[i], got[i]))

    missing = len(want) - len(got)
    print(f"  lines: {len(got)} captured / {len(want)} expected"
          + (f"  ({missing:+d})" if missing else ""))

    if not diffs and not missing:
        print("  PASS")
        return True

    print(f"  FAIL: {len(diffs)} differing lines")

    by_mnem = Counter(mnemonic(w) for _, w, _ in diffs)
    total = Counter(mnemonic(w) for w in want)
    print("  by instruction:")
    for mn, count in by_mnem.most_common(args.summary):
        print(f"    {mn:<10} {count:>7} wrong of {total[mn]:>7}")

    with open(os.path.join(args.out, f"{stem}.diff"), "w", encoding="utf-8") as fh:
        for ln, w, g in diffs:
            fh.write(f"line {ln}\n  expected: {w}\n  actual  : {g}\n")
    print(f"  first differences:")
    for ln, w, g in diffs[:5]:
        print(f"    line {ln}\n      expected: {w}\n      actual  : {g}")
    print(f"  full diff: {os.path.join(args.out, f'{stem}.diff')}")
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tests", nargs="*", help="test paths relative to tests/, e.g. cpu/spu_fpu")
    ap.add_argument("--repo", default=REPO_DEFAULT, help="ps3autotests checkout")
    ap.add_argument("--out", default="autotest-results", help="where to write .actual/.diff")
    ap.add_argument("--list", action="store_true", help="list runnable tests and exit")
    ap.add_argument("--all-spu", action="store_true", help="every cpu/spu_* test")
    ap.add_argument("--idle", type=float, default=8.0, help="seconds of TTY silence = finished")
    ap.add_argument("--boot-wait", type=float, default=90.0, help="seconds to wait for first output")
    ap.add_argument("--timeout", type=float, default=900.0, help="hard cap per test")
    ap.add_argument("--summary", type=int, default=25, help="instructions to show in the summary")
    args = ap.parse_args()

    tests_root = os.path.join(os.path.abspath(args.repo), "tests")
    if not os.path.isdir(tests_root):
        sys.exit(f"no tests/ under {args.repo} -- clone https://github.com/RPCS3/ps3autotests")

    available = discover(tests_root)

    if args.list:
        for name, _d, elf, exp in available:
            print(f"{name:<40} {elf:<28} expected={os.path.getsize(exp) // 1024}K")
        return

    if args.all_spu:
        wanted = [t for t in available if os.path.basename(t[0]).startswith("spu_")]
    elif args.tests:
        wanted = [t for t in available if t[0] in args.tests]
        unknown = set(args.tests) - {t[0] for t in wanted}
        if unknown:
            sys.exit(f"unknown test(s): {', '.join(sorted(unknown))}  (try --list)")
    else:
        sys.exit("name at least one test, or --all-spu, or --list")

    if not adb("devices").strip().splitlines()[1:]:
        sys.exit("no device connected")

    results = {}
    for name, dirpath, elf, expected in wanted:
        results[name] = run_one(name, dirpath, elf, expected, args)

    print("\n=== summary ===")
    for name, ok in results.items():
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()
