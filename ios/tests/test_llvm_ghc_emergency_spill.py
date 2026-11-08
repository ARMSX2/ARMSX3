# SPDX-License-Identifier: GPL-2.0-only
"""The GHC emergency-spill regression IR aborts the unpatched llc and compiles on the patched llc for three
Apple CPUs (each half skips when its llc is missing), and each LLVM install stamp names the current
patch hash."""

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

PATCH = ROOT / "3rdparty" / "llvm" / "armsx3-aarch64-ghc-emergency-spill.patch"

VERSION_CMAKE = ROOT / "3rdparty" / "llvm" / "llvm" / "cmake" / "Modules" / "LLVMVersion.cmake"

STAMPS = {
    "iOS": ROOT / "ios" / "build-deps" / "llvm-ios-install" / ".armsx3-llvm-stamp",
    "macOS": ROOT / "ios" / "build-deps" / "llvm-macos-install" / ".armsx3-llvm-stamp",
}

SCAVENGER = "Cannot scavenge register without an emergency spill slot"

# Pinned exactly: 24-26 and 28-40 live values compile on the unpatched llc; only 27 aborts.
LIVE_GPRS = 27

TABLE_PAIRS = 20

PATCHED_CPUS = ("apple-m2", "apple-a17", "apple-a19")


def default_unpatched_llc():
    brew = shutil.which("brew")
    if brew is None:
        return ""
    try:
        prefix = subprocess.run(
            [brew, "--prefix", "llvm@22"], capture_output=True, text=True, timeout=60
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""
    return prefix + "/bin/llc" if prefix else ""


UNPATCHED_LLC = os.environ.get("ARMSX3_UNPATCHED_LLC") or default_unpatched_llc()

PATCHED_LLC = os.environ.get(
    "ARMSX3_PATCHED_LLC", str(ROOT / "ios" / "build-deps" / "llvm-macos-install" / "bin" / "llc")
)


def ghc_tbl2_ir(live_gprs, table_pairs):
    n, nt = live_gprs, table_pairs
    lines = [
        'target triple = "arm64-apple-ios"',
        'declare <16 x i8> @llvm.aarch64.neon.tbl2.v16i8(<16 x i8>, <16 x i8>, <16 x i8>)',
        'define ghccc void @f(ptr %p, ptr %q) {',
    ]
    for i in range(n):
        lines += [f'  %g{i} = getelementptr i64, ptr %p, i64 {i}', f'  %a{i} = load volatile i64, ptr %g{i}']
    for i in range(2 * nt + 1):
        lines += [f'  %vg{i} = getelementptr <16 x i8>, ptr %q, i64 {i}', f'  %v{i} = load volatile <16 x i8>, ptr %vg{i}']
    idx = f'%v{2 * nt}'
    for i in range(nt):
        lines += [
            f'  %t{i} = call <16 x i8> @llvm.aarch64.neon.tbl2.v16i8(<16 x i8> %v{2*i}, <16 x i8> %v{2*i+1}, <16 x i8> {idx})',
            f'  store volatile <16 x i8> %t{i}, ptr %vg{i}',
        ]
    for i in reversed(range(nt)):
        lines += [
            f'  %u{i} = call <16 x i8> @llvm.aarch64.neon.tbl2.v16i8(<16 x i8> %v{2*i}, <16 x i8> %v{2*i+1}, <16 x i8> %t{i})',
            f'  store volatile <16 x i8> %u{i}, ptr %vg{i}',
        ]
    for i in range(n):
        lines += [f'  %b{i} = add i64 %a{i}, %a{(i + 1) % n}', f'  store volatile i64 %b{i}, ptr %g{i}']
    lines += ['  ret void', '}']
    return '\n'.join(lines) + '\n'


def note(message):
    """A visible skip note. Printed to stderr so it survives ios/tests/run_all.sh's capture."""
    sys.stderr.write("\n    NOTE: %s\n" % message)
    sys.stderr.flush()


def submodule_version():
    text = VERSION_CMAKE.read_text(encoding="utf-8")
    parts = [re.search(r"set\(LLVM_VERSION_%s\s+(\d+)\)" % k, text) for k in ("MAJOR", "MINOR", "PATCH")]
    return ".".join(p.group(1) for p in parts) if all(parts) else None


def run_llc(llc, ir, cpu):
    """Compile `ir` with `llc` at -O3; return (returncode, stdout asm, stderr)."""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "ghc-tbl2.ll"
        out = Path(tmp) / "ghc-tbl2.s"
        src.write_text(ir, encoding="utf-8")
        env = dict(os.environ, LLVM_DISABLE_SYMBOLIZATION="1")
        proc = subprocess.run(
            [llc, "-mtriple=arm64-apple-ios", "-mcpu=" + cpu, "-O3", str(src), "-o", str(out)],
            capture_output=True, text=True, timeout=300, env=env,
        )
        asm = out.read_text(encoding="utf-8") if out.is_file() else ""
        return proc.returncode, asm, proc.stderr


class TheUnpatchedLlcAborts(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        if not UNPATCHED_LLC or not os.access(UNPATCHED_LLC, os.X_OK):
            note("no unpatched llc at '%s' (Homebrew llvm@22); skipping the before half." % UNPATCHED_LLC)
            raise unittest.SkipTest("unpatched llc missing")
        version = subprocess.run([UNPATCHED_LLC, "--version"], capture_output=True, text=True).stdout
        want = submodule_version()
        if want is None or ("LLVM version %s" % want) not in version:
            note("the unpatched llc is not LLVM %s (the submodule's release); skipping the before half." % want)
            raise unittest.SkipTest("unpatched llc release differs")

    def test_the_ghc_ir_aborts_with_the_scavenger_message(self):
        rc, _, err = run_llc(UNPATCHED_LLC, ghc_tbl2_ir(LIVE_GPRS, TABLE_PAIRS), "apple-m2")
        self.assertNotEqual(rc, 0, "%s compiled the GHC regression IR; it no longer reproduces the abort the patch fixes." % UNPATCHED_LLC)
        self.assertIn(SCAVENGER, err, "%s failed, but not with the scavenger abort: %s" % (UNPATCHED_LLC, err[:600]))

    def test_the_c_convention_twin_compiles(self):
        ir = ghc_tbl2_ir(LIVE_GPRS, TABLE_PAIRS).replace("define ghccc", "define")
        rc, _, err = run_llc(UNPATCHED_LLC, ir, "apple-m2")
        self.assertEqual(rc, 0, "the C-convention twin failed on %s, so the IR itself is suspect: %s" % (UNPATCHED_LLC, err[:600]))


class ThePatchedLlcCompiles(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        if not os.access(PATCHED_LLC, os.X_OK):
            note("no patched llc at %s; run ios/scripts/build-llvm-macos.sh. Skipping the after half." % PATCHED_LLC)
            raise unittest.SkipTest("patched llc missing")

    def test_the_ghc_ir_compiles_on_every_cpu(self):
        ir = ghc_tbl2_ir(LIVE_GPRS, TABLE_PAIRS)
        for cpu in PATCHED_CPUS:
            with self.subTest(cpu=cpu):
                rc, asm, err = run_llc(PATCHED_LLC, ir, cpu)
                self.assertEqual(rc, 0, "%s -mcpu=%s failed on the GHC regression IR: %s" % (PATCHED_LLC, cpu, err[:600]))
                self.assertIn("_f:", asm)


class TheInstallsCarryTheCurrentPatch(unittest.TestCase):

    def test_each_present_stamp_names_the_current_patch(self):
        want = hashlib.sha256(PATCH.read_bytes()).hexdigest()
        for name, stamp in STAMPS.items():
            with self.subTest(install=name):
                if not stamp.is_file():
                    note("no %s LLVM install stamp at %s; nothing to join." % (name, stamp))
                    continue
                lines = stamp.read_text(encoding="utf-8").splitlines()
                got = [l.split("=", 1)[1] for l in lines if l.startswith("llvm_patch_sha256=")]
                self.assertEqual(
                    got, [want],
                    "the %s LLVM install at %s was not built from the current patch bytes (stamp says %s, patch is %s). Re-run its build script." % (name, stamp.parent, got or "nothing", want),
                )


if __name__ == "__main__":
    unittest.main()
