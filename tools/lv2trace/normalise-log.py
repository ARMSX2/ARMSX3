#!/usr/bin/env python3
"""Reduce an RPCS3/ARMSX3 log to a per-thread syscall trace, for diffing one platform against
another.

Why per-thread and not the raw log: the interleaving between threads differs on every boot and on
every machine, so a global diff is all noise. What each thread DID, in order, is stable -- so this
emits one sequence per guest thread and drops everything that legitimately varies (timestamps,
addresses, argument values, pointer widths). Diff the output of two runs and the first divergence
in a thread's sequence is the call that behaved differently.

Both emulators write the same log format, so the same normalisation applies to a desktop RPCS3.log
and an Android RPCSX.log with no flags to remember.

Usage:
  normalise-log.py RPCSX.log                        > arm.trace
  normalise-log.py RPCS3.log --tail 90              > x86.trace     # last 90s only
  diff -u x86.trace arm.trace | head -40

  # then, to see it per thread:
  normalise-log.py RPCSX.log --thread PhysWISESpursHdlr0
"""

import argparse
import re
import sys
from collections import OrderedDict

# ·W 0:00:58.030816 {PPU[0x1000000] Thread (main_thread) [liblv2: 0x01b309ac]} sys_fs: sys_fs_stat(path=...)
LINE = re.compile(
    r"^.?(?P<lvl>[A-Z!]?)\s*(?P<t>\d+:\d\d:\d\d\.\d+)\s+\{(?P<ctx>[^}]*)\}\s*(?P<body>.*)$"
)
# Thread identity: the name in parentheses if present, else the raw context minus its address.
TNAME = re.compile(r"Thread \(([^)]*)\)|^(RSX|SPU|PPU)")
CALL = re.compile(r"(?:^|\s)(?P<name>_?sys[a-zA-Z0-9_]*|cell[A-Za-z0-9_]+|sceNp[A-Za-z0-9_]+)\s*\(")


def seconds(stamp):
    h, m, s = stamp.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def thread_of(ctx):
    m = re.search(r"Thread \(([^)]*)\)", ctx)
    if m:
        # PPU[0x1000000] Thread (main_thread) -> "PPU main_thread". The id is per-run, the name is not.
        kind = ctx.split("[", 1)[0].strip() or "PPU"
        return f"{kind} {m.group(1)}"
    # SPU[0x1000100] 'Name' / RSX [0x...] -- keep the kind and any quoted name.
    q = re.search(r"'([^']*)'", ctx)
    kind = ctx.split("[", 1)[0].strip()
    return f"{kind} {q.group(1)}" if q else kind


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("--tail", type=float, default=None,
                    help="only the last N seconds of emulated time (the window around a hang)")
    ap.add_argument("--thread", default=None, help="restrict to threads whose name contains this")
    ap.add_argument("--keep-repeats", action="store_true",
                    help="do not collapse a call repeated back-to-back (default collapses, with a count)")
    args = ap.parse_args()

    per_thread = OrderedDict()
    last_time = 0.0
    rows = []

    with open(args.log, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = LINE.match(line)
            if not m:
                continue
            call = CALL.search(m.group("body"))
            if not call:
                continue
            t = seconds(m.group("t"))
            last_time = max(last_time, t)
            rows.append((t, thread_of(m.group("ctx")), call.group("name")))

    cutoff = (last_time - args.tail) if args.tail else -1.0

    for t, thread, name in rows:
        if t < cutoff:
            continue
        if args.thread and args.thread not in thread:
            continue
        seq = per_thread.setdefault(thread, [])
        if not args.keep_repeats and seq and seq[-1][0] == name:
            seq[-1][1] += 1
        else:
            seq.append([name, 1])

    if not per_thread:
        sys.exit("no syscalls matched -- wrong log, or --tail/--thread too narrow")

    print(f"# {args.log}: {len(rows)} calls, {len(per_thread)} threads"
          + (f", last {args.tail}s of {last_time:.1f}s" if args.tail else f", {last_time:.1f}s"))

    for thread, seq in sorted(per_thread.items()):
        print(f"\n=== {thread} ({sum(n for _, n in seq)} calls) ===")
        for name, n in seq:
            print(f"{name}{f'  x{n}' if n > 1 else ''}")


if __name__ == "__main__":
    main()
