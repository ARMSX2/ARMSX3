#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""On Apple ARM64 vm_reservation_slot.h gives each 128-byte guest line its own 128-byte reservation slot through one
compile-time switch with no -D route and one helper every C++ and SPU LLVM slot computation uses, the SPU cache key
lists the header, and the PUTLLUC and SUSPEND meters take nullptr; read over the Apple view of the core files."""

import functools
import os
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEADER = "ios/core/vm_reservation_slot.h"
VM_H = "rpcs3/Emu/Memory/vm.h"
VM_CPP = "rpcs3/Emu/Memory/vm.cpp"
SPU_THREAD = "rpcs3/Emu/Cell/SPUThread.cpp"
SPU_LLVM = "rpcs3/Emu/Cell/SPULLVMRecompiler.cpp"
CPU_THREAD = "rpcs3/Emu/CPU/CPUThread.cpp"
SRC_CMAKE = "ios/core/CMakeLists.txt"

EXPECTED_DEFAULT = "false"
APPLE_FENCE = "#if defined(__APPLE__) && defined(__aarch64__)"
C_SUFFIXES = (".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".inl", ".mm", ".m")
SOURCES_RE = re.compile(r"set\(ARMSX3_SPU_CODEGEN_SOURCES\b(.*?)\)", re.DOTALL)
SWITCH_RE = re.compile(r"^inline constexpr bool rsrv_stride_128 = (\w+);$")

HEADER_FORMULA = (
    "inline constexpr u32 rsrv_slot_shift = rsrv_stride_128 ? 0 : 1;",
    "inline constexpr u32 rsrv_slot_size = 128 >> rsrv_slot_shift;",
    "inline constexpr u32 rsrv_table_size = 65536 / 128 * rsrv_slot_size;",
    "template <typename T> constexpr auto reservation_slot_offset(T addr) { if constexpr (rsrv_slot_shift == 0) "
    "{ return addr & 0xff80; } else { return (addr & 0xff80) >> rsrv_slot_shift; } }",
)
HEADER_ASSERTS = (
    "static_assert(rsrv_slot_size == 64 || rsrv_slot_size == 128);",
    "static_assert(rsrv_slot_size >= 32,",
    "static_assert(reservation_slot_offset(0x80u) == rsrv_slot_size);",
    "static_assert(reservation_slot_offset(0xffffu) + rsrv_slot_size == rsrv_table_size);",
    "static_assert(reservation_slot_offset(0x10080u) == reservation_slot_offset(0x80u));",
    "static_assert(rsrv_stride_128 || reservation_slot_offset(0x12345u) == (0x12345u & 0xff80) / 2);",
)
METERS = ((SPU_THREAD, "PUTLLUC"), (VM_CPP, "SUSPEND"), (CPU_THREAD, "SUSPEND"))


def strip_c(text):
    """Comments removed, line structure kept (block comments become as many newlines)."""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return [" ".join(re.sub(r"//.*", "", line).split()) for line in text.split("\n")]


def flat(lines):
    return " ".join(" ".join(lines).split())


def strip_cmake(text):
    return "\n".join(re.sub(r"(^|\s)#.*", "", line) for line in text.split("\n"))


def corpus():
    """Every C/C++ file under rpcs3/ and Utilities/, and every CMake file there and under ios/ (build trees pruned)."""
    files = {}
    for top in ("rpcs3", "Utilities", "ios"):
        for d, dirs, names in os.walk(ROOT / top):
            dirs[:] = [x for x in dirs if not x.startswith("build") and x != ".git"]
            for n in names:
                p = Path(d) / n
                rel = p.relative_to(ROOT).as_posix()
                cmake = n == "CMakeLists.txt" or n.endswith(".cmake")
                if cmake or (top != "ios" and n.endswith(C_SUFFIXES)):
                    files[rel] = p.read_text(encoding="utf-8", errors="replace")
    files[HEADER] = (ROOT / HEADER).read_text(encoding="utf-8") if (ROOT / HEADER).exists() else ""
    root_cmake = ROOT / "CMakeLists.txt"
    if root_cmake.exists():
        files["CMakeLists.txt"] = root_cmake.read_text(encoding="utf-8")
    for rel in (HEADER, VM_H, VM_CPP, SPU_THREAD, SPU_LLVM, CPU_THREAD, SRC_CMAKE):
        if rel not in files or not files[rel].strip():
            raise AssertionError("{0} is missing or empty".format(rel))
    return files


@functools.lru_cache(maxsize=None)
def apple_view(text):
    """The text an Apple build compiles: the non-Apple arms of __APPLE__ conditionals dropped."""
    if "__APPLE__" not in text or not any(t in text for t in ("g_reservations", "0xff80", "perf_meter", "65536 / 128")):
        return text
    r = subprocess.run(["unifdef", "-t", "-D__APPLE__"], input=text, capture_output=True, text=True)
    if r.returncode not in (0, 1):
        raise AssertionError("unifdef failed: " + r.stderr)
    return r.stdout


def switch_problems(files):
    lines = strip_c(files[HEADER])
    stack, apple, other = [], [], []
    for line in lines:
        if re.match(r"#\s*if", line):
            stack.append([line, False])
            continue
        if re.match(r"#\s*(else|elif)", line) and stack:
            stack[-1][1] = True
            continue
        if re.match(r"#\s*endif", line) and stack:
            stack.pop()
            continue
        m = SWITCH_RE.match(line)
        if m:
            if stack == [[APPLE_FENCE, False]]:
                apple.append(m.group(1))
            elif stack == [[APPLE_FENCE, True]]:
                other.append(m.group(1))
            else:
                other.append("misplaced")
    out = []
    if apple != [EXPECTED_DEFAULT] or other != ["false"]:
        out.append("switch: expected one Apple ARM64 declaration = %s and one #else declaration = false (apple=%s, else=%s)"
                   % (EXPECTED_DEFAULT, apple, other))
    for rel, text in files.items():
        cmake = rel.endswith(".txt") or rel.endswith(".cmake")
        if cmake and "rsrv_stride_128" in strip_cmake(text):
            out.append("switch: CMake file %s names rsrv_stride_128 (a -D route)" % rel)
        elif not cmake and any(l.startswith("#") and "rsrv_stride_128" in l for l in strip_c(text)):
            out.append("switch: a preprocessor line in %s names rsrv_stride_128" % rel)
    return out


def problems(files):
    out = switch_problems(files)
    files = {r: apple_view(t) if r.endswith(C_SUFFIXES) and r != HEADER else t for r, t in files.items()}
    header = flat(strip_c(files[HEADER]))
    if not all(f in header for f in HEADER_FORMULA):
        out.append("formula: shift, slot size, table size or reservation_slot_offset is not the planned form")

    vm_cpp = flat(strip_c(files[VM_CPP]))
    if not all(a in header for a in HEADER_ASSERTS) or "static_assert(sizeof(g_reservations) == rsrv_table_size);" not in vm_cpp:
        out.append("asserts: a slot-layout static_assert is missing from the header, or vm.cpp's sizeof assert is gone")

    if ("extern u8 g_reservations[rsrv_table_size];" not in flat(strip_c(files[VM_H]))
            or "alignas(4096) u8 g_reservations[rsrv_table_size]{0};" not in vm_cpp
            or any("65536 / 128 * 64" in flat(strip_c(t)) for r, t in files.items() if r.endswith(C_SUFFIXES))):
        out.append("table: g_reservations is not sized [rsrv_table_size] in vm.h and vm.cpp, or the literal size remains")

    helper_uses, bad, halving = 0, [], []
    for rel, text in files.items():
        if not rel.endswith(C_SUFFIXES):
            continue
        code = flat(strip_c(text))
        for m in re.finditer(r"g_reservations \+ ?\(?\s*", code):
            rest = code[m.end():]
            if rest.startswith("sizeof(g_reservations)"):
                continue
            if re.match(r"\(?(vm::)?reservation_slot_offset\(", rest):
                helper_uses += 1
            else:
                bad.append(rel)
        if rel != HEADER and re.search(r"0xff80\s*\)\s*(/\s*2|>>\s*1)\b", code):
            halving.append(rel)
    llvm = flat(strip_c(files[SPU_LLVM]))
    loads = re.findall(r"spu_ptr\(&spu_thread::reserv_base_addr\)\)(.{0,60})", llvm)
    ir_ok = sum(1 for tail in loads if tail.startswith(", vm::reservation_slot_offset(eal_val).eval(m_ir)"))
    if bad or helper_uses < 3 or halving or len(loads) < 3 or ir_ok != len(loads):
        out.append("sites: g_reservations offsets not through the helper %s (helper uses %d), halving left in %s, IR loads %d of %d"
                   % (bad, helper_uses, halving, ir_ok, len(loads)))

    m = SOURCES_RE.search(strip_cmake(files[SRC_CMAKE]))
    if not m or HEADER not in m.group(1).split():
        out.append("key: ARMSX3_SPU_CODEGEN_SOURCES does not list " + HEADER)

    for rel, name in METERS:
        code = flat(strip_c(files[rel]))
        if ('perf_meter<"%s"_u64> perf0(nullptr);' % name) not in code or ('perf_meter<"%s"_u64> perf0;' % name) in code:
            out.append("meter: the %s meter in %s is not built with (nullptr), or a bare one remains" % (name, rel))
    return out


class TheRealFilesCarryTheSwitch(unittest.TestCase):
    def test_the_real_files(self):
        self.assertEqual([], problems(corpus()))


class EachCheckCanFail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = corpus()

    def edited(self, rel, old, new):
        text = self.files[rel]
        self.assertIn(old, text, "the mutation's anchor is gone: " + old)
        out = dict(self.files)
        out[rel] = text.replace(old, new, 1)
        return out

    def fails_with(self, prefix, files):
        found = problems(files)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), "%s did not fire: %s" % (prefix, found))

    def test_mutation_switch_flipped(self):
        other = "true" if EXPECTED_DEFAULT == "false" else "false"
        self.fails_with("switch", self.edited(HEADER, "rsrv_stride_128 = %s;\n#else" % EXPECTED_DEFAULT,
                                              "rsrv_stride_128 = %s;\n#else" % other))

    def test_mutation_switch_cmake_route(self):
        self.fails_with("switch", self.edited(SRC_CMAKE, "set(ARMSX3_SPU_CODEGEN_SOURCES",
                                              "add_compile_definitions(rsrv_stride_128=1)\nset(ARMSX3_SPU_CODEGEN_SOURCES"))

    def test_mutation_formula_shift_constant(self):
        self.fails_with("formula", self.edited(HEADER, ">> rsrv_slot_shift;\n\t\t}", ">> 1;\n\t\t}"))

    def test_mutation_asserts_sizeof_dropped(self):
        self.fails_with("asserts", self.edited(VM_CPP, "static_assert(sizeof(g_reservations) == rsrv_table_size);", ""))

    def test_mutation_table_literal_extern(self):
        self.fails_with("table", self.edited(VM_H, "g_reservations[rsrv_table_size]", "g_reservations[65536 / 128 * 64]"))

    def test_mutation_sites_cpp_halving(self):
        self.fails_with("sites", self.edited("rpcs3/Emu/Memory/vm_reservation.h", "g_reservations + reservation_slot_offset(addr)",
                                             "g_reservations + (addr & 0xff80) / 2"))

    def test_mutation_sites_ir_halving(self):
        self.fails_with("sites", self.edited(SPU_LLVM, "vm::reservation_slot_offset(eal_val).eval(m_ir)",
                                             "((eal_val & 0xff80) >> 1).eval(m_ir)"))

    def test_mutation_key_header_unlisted(self):
        self.fails_with("key", self.edited(SRC_CMAKE, "    " + HEADER + "\n", ""))

    def test_mutation_meter_bare(self):
        self.fails_with("meter", self.edited(SPU_THREAD, 'perf_meter<"PUTLLUC"_u64> perf0(nullptr);',
                                             'perf_meter<"PUTLLUC"_u64> perf0;'))


if __name__ == "__main__":
    unittest.main()
