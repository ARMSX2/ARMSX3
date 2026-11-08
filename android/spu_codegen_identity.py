#!/usr/bin/env python3
"""The SPU object cache key for one Android build: a SHA-1 of the code that decides what SPU
machine code comes out.

Usage: spu_codegen_identity.py <repo root> <build dir> <output header>

The build runs this (android/CMakeLists.txt) every time, before anything in rpcs3_emu compiles,
so a core can never be built with an identity its own sources did not produce, however it is
built. The header is rewritten only when the identity changes, so a build that leaves this code
alone does not recompile SPUCommonRecompiler.cpp, the one file that includes it.

The persistent SPU object cache (spu_runtime, SPUCommonRecompiler.cpp) may only reuse an object
compiled by code that would compile it the same way. Keying it on the build (commit, build ID)
is safe but throws every player's cache away on every update. This keys it on that code instead,
so an update that leaves it alone keeps every player's cache:

- every translation unit in UNITS, preprocessed with this build's own compile command (folded
  too), so each header they include counts and no list of headers can go stale;
- DEFINITIONS: what the compiler runs or copies from other files while it compiles. The rest of
  those files is runtime code that compiled SPU code only calls by name, so it can change freely;
- RAW files, hashed as written;
- the LLVM that turns the IR into machine code: its commit and any local change to it.

Settings, CPU features, the timer frequency and the debug levers are runtime inputs, folded into
the key (or turning the cache off) in spu_runtime.

How the lists were drawn up, and how to recheck them after a merge: take every symbol the UNITS'
objects use that another object defines (llvm-nm -u), with the function using it (llvm-objdump
-r; the build has -ffunction-sections). A use from code that runs while compiling, rather than
from code that compiled SPU code calls, belongs in a list here. Anything listed that cannot be
found fails the build, so a list cannot rot silently.
"""
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys

UNITS = [
    "rpcs3/Emu/Cell/SPULLVMRecompiler.cpp",
    "rpcs3/Emu/Cell/SPUCommonRecompiler.cpp",
    "rpcs3/Emu/CPU/CPUTranslator.cpp",
    "Utilities/JITLLVM.cpp",
    "rpcs3/Emu/CPU/Backends/AArch64/AArch64JIT.cpp",
    "rpcs3/Emu/CPU/Backends/AArch64/AArch64ASM.cpp",
]

# Each from its first line to the closing brace at column 0.
DEFINITIONS = [
    # Run by the analyser and the compiler
    ("rpcs3/Emu/Cell/SPUThread.cpp", "std::array<u32, 2> op_branch_targets(u32 pc, spu_opcode_t op)"),
    ("rpcs3/Emu/Cell/SPUThread.cpp", "std::tuple<u32, std::array<u32, 3>, u32> op_register_targets(u32 /*pc*/, spu_opcode_t op)"),
    # Copied into the modules that use FREST and FRSQEST
    ("rpcs3/Emu/Cell/SPUThread.cpp", "const u32 spu_frest_fraction_lut[32] ="),
    ("rpcs3/Emu/Cell/SPUThread.cpp", "const u32 spu_frsqest_fraction_lut[64] ="),
    # Decide what the compiler emits: the Killzone 3 guard in A, DMA inlining in WRCH
    ("rpcs3/Emu/Cell/SPUThread.cpp", "bool spu_is_killzone3()"),
    ("rpcs3/Emu/Cell/SPUThread.cpp", "static std::pair<u32, u32> spu_ls_watch_range()"),
    ("rpcs3/Emu/Cell/SPUThread.cpp", "bool spu_ls_watch_enabled()"),
    ("rpcs3/Emu/Cell/PPUThread.cpp", "bool kz3_sync_probe_enabled()"),
]

RAW = [
    # Defines the instruction decoders the analyser classifies with. Their tables come from headers
    # the units include; this file is in the compile database more than once.
    "rpcs3/Emu/Cell/SPUAnalyser.cpp",
]

LLVM = "3rdparty/llvm/llvm"

# __DATE__, __TIME__ and __TIMESTAMP__ as they expand: build stamps, which would otherwise change
# the identity on every compile.
STAMPS = re.compile(
    rb'"[A-Z][a-z]{2} [A-Z][a-z]{2} [ 0-9][0-9] [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}"'
    rb'|"[A-Z][a-z]{2} [ 0-9][0-9] [0-9]{4}"'
    rb'|"[0-9]{2}:[0-9]{2}:[0-9]{2}"'
)


def fail(message):
    print(f"spu_codegen_identity: {message}", file=sys.stderr)
    sys.exit(1)


def preprocess(args, directory, unit):
    out, skip = [], False
    for a in args:
        if skip:
            skip = False
            continue
        if a in ("-o", "-MF", "-MT", "-MQ"):
            skip = True
            continue
        if a in ("-c", "-MD", "-MMD"):
            continue
        out.append(a)
    # SPUCommonRecompiler.cpp puts a placeholder in place of the identity while it is hashed, so it
    # does not depend on its own result and is otherwise hashed exactly as it compiles.
    out += ["-E", "-P", "-DARMSX3_SPU_IDENTITY_HASHING", "-o", "-"]
    r = subprocess.run(out, cwd=directory, capture_output=True)
    if r.returncode != 0:
        fail(f"preprocessing {unit} failed:\n{r.stderr.decode(errors='replace')[-2000:]}")
    return STAMPS.sub(b'"STAMP"', r.stdout)


def definition_text(root, rel, first_line):
    text = open(os.path.join(root, rel), "rb").read()
    start = text.find(b"\n" + first_line.encode() + b"\n{\n")
    if start < 0 or text.find(b"\n" + first_line.encode() + b"\n{\n", start + 1) >= 0:
        fail(f"expected one '{first_line}' definition in {rel}")
    end = re.compile(rb"\n\};?\n").search(text, start + 1)
    if not end:
        fail(f"'{first_line}' in {rel} has no closing brace at column 0")
    return text[start + 1 : end.end()]


def git(path, *args):
    r = subprocess.run(["git", "-C", path, *args], capture_output=True)
    if r.returncode != 0:
        fail(f"git {' '.join(args)} in {path} failed: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout


def main():
    if len(sys.argv) != 4:
        fail("usage: spu_codegen_identity.py <repo root> <build dir> <output header>")
    root, build, header = (os.path.abspath(a) for a in sys.argv[1:4])

    db_path = os.path.join(build, "compile_commands.json")
    if not os.path.isfile(db_path):
        fail(f"no {db_path}; configure the build directory first")
    db = json.load(open(db_path))

    h = hashlib.sha1()

    def fold(name, data):
        h.update(name.encode() + b"\0" + str(len(data)).encode() + b"\0" + data)

    for unit in UNITS:
        entries = [e for e in db if os.path.normpath(e["file"]).endswith(os.path.normpath("/" + unit))]
        if len(entries) != 1:
            fail(f"{unit} is in {db_path} {len(entries)} times, expected once")
        args = entries[0].get("arguments") or shlex.split(entries[0]["command"])
        fold(f"{unit}#command", json.dumps(args).encode())
        fold(unit, preprocess(args, entries[0]["directory"], unit))

    for rel, first_line in DEFINITIONS:
        fold(f"{rel}#{first_line}", definition_text(root, rel, first_line))

    for rel in RAW:
        fold(rel, open(os.path.join(root, rel), "rb").read())

    llvm = os.path.join(root, LLVM)
    # Inside an uninitialised submodule, git would answer for the superproject instead
    if os.path.realpath(git(llvm, "rev-parse", "--show-toplevel").decode().strip()) != os.path.realpath(llvm):
        fail(f"{LLVM} is not a checked-out submodule")
    fold("llvm-commit", git(llvm, "rev-parse", "HEAD"))
    fold("llvm-local-changes", git(llvm, "diff", "--no-ext-diff", "--no-color", "--binary", "HEAD"))
    for rel in sorted(p for p in git(llvm, "ls-files", "-z", "--others", "--exclude-standard").decode().split("\0") if p):
        fold(f"llvm-untracked:{rel}", open(os.path.join(llvm, rel), "rb").read())

    identity = h.hexdigest()
    content = (
        "// Generated by android/spu_codegen_identity.py on every build; do not edit.\n"
        "#pragma once\n"
        f'#define ARMSX3_ANDROID_SPU_CODEGEN_IDENTITY "spu-codegen-{identity}"\n'
    )

    old = open(header).read() if os.path.isfile(header) else None
    if old != content:
        os.makedirs(os.path.dirname(header), exist_ok=True)
        with open(header + ".tmp", "w") as f:
            f.write(content)
        os.replace(header + ".tmp", header)
        print(f"spu_codegen_identity: {identity} (written)")
    else:
        print(f"spu_codegen_identity: {identity} (unchanged)")


if __name__ == "__main__":
    main()
