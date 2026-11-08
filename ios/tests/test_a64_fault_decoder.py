# SPDX-License-Identifier: GPL-2.0-only
"""`decode_a64_mem_inst`, cut from Utilities/Thread.cpp and run on the host, decodes the LDR/STR register-offset,
immediate and rejected encodings in CASES as the A64 tables say.
It does not run the fault handler or anything on a phone."""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_body, struct_body

ROOT = Path(__file__).resolve().parents[2]
THREAD_CPP = ROOT / "Utilities" / "Thread.cpp"
COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

# (encoding, what it is, the expected line after the encoding). op 1 is a load, 2 a store, 0 rejected.
CASES = (
    (0xB8626820, "ldr w0, [x1, x2]", "op=1 mem=4 reg=4 rt=0 signed=0"),
    (0xF8257883, "str x3, [x4, x5, lsl #3]", "op=2 mem=8 reg=8 rt=3 signed=0"),
    (0x386848E6, "ldrb w6, [x7, w8, uxtw]", "op=1 mem=1 reg=4 rt=6 signed=0"),
    (0x78ABF949, "ldrsh x9, [x10, x11, sxtx #1]", "op=1 mem=2 reg=8 rt=9 signed=1"),
    (0xB8AED9AC, "ldrsw x12, [x13, w14, sxtw #2]", "op=1 mem=4 reg=8 rt=12 signed=1"),
    (0x78316A0F, "strh w15, [x16, x17]", "op=2 mem=2 reg=2 rt=15 signed=0"),
    (0x38F46A72, "ldrsb w18, [x19, x20]", "op=1 mem=1 reg=4 rt=18 signed=1"),
    (0xF8775AD5, "ldr x21, [x22, w23, uxtw #3]", "op=1 mem=8 reg=8 rt=21 signed=0"),
    (0x383A7B38, "strb w24, [x25, x26, lsl #0]", "op=2 mem=1 reg=1 rt=24 signed=0"),
    (0xB9400420, "ldr w0, [x1, #4] (unsigned immediate, as before)", "op=1 mem=4 reg=4 rt=0 signed=0"),
    (0xF9000483, "str x3, [x4, #8] (unsigned immediate, as before)", "op=2 mem=8 reg=8 rt=3 signed=0"),
    (0xB85FC020, "ldur w0, [x1, #-4] (unscaled immediate, as before)", "op=1 mem=4 reg=4 rt=0 signed=0"),
    (0xB8404C20, "ldr w0, [x1, #4]! (pre-index writeback)", "op=0"),
    (0xB8404420, "ldr w0, [x1], #4 (post-index writeback)", "op=0"),
    (0xB8200041, "ldadd w0, w1, [x2] (atomic)", "op=0"),
    (0xF8A26820, "prfm pldl1keep, [x1, x2]", "op=0"),
    (0xBC626820, "ldr s0, [x1, x2] (SIMD)", "op=0"),
    (0xF8200420, "ldraa x0, [x1] (pointer authentication)", "op=0"),
    (0xB8622820, "ldr w0 register form with option 001 (option<1> clear, unallocated)", "op=0"),
    (0xB8628820, "ldr w0 register form with option 100 (option<1> clear, unallocated)", "op=0"),
    (0xB8E26820, "register form with size 10 opc 11 (unallocated)", "op=0"),
)

DRIVER = """#include <cstdint>
#include <cstdio>
using u32 = std::uint32_t;
enum mem_a64_op_t {{{enum}}};
struct a64_mem_info_t {{{struct}}};
a64_mem_info_t decode_a64_mem_inst(u32 inst) {{{function}}}
int main()
{{
	static const u32 cases[] = {{{cases}}};
	for (const u32 inst : cases)
	{{
		const a64_mem_info_t r = decode_a64_mem_inst(inst);
		if (r.op == A64_INVALID)
			std::printf("%08x op=0\\n", inst);
		else
			std::printf("%08x op=%d mem=%u reg=%u rt=%u signed=%d\\n", inst, static_cast<int>(r.op), r.mem_size,
				r.reg_size, r.reg_num, r.reg_signed ? 1 : 0);
	}}
	return 0;
}}
"""


def decoder_parts(source):
    """The enum, struct and function bodies, or an AssertionError naming the one not found exactly once."""
    enums = re.findall(r"\benum\s+mem_a64_op_t\s*\{([^}]*)\}", source)
    if len(enums) != 1:
        raise AssertionError("enum mem_a64_op_t is in {0} {1} times, want once".format(THREAD_CPP, len(enums)))
    struct = struct_body(source, "a64_mem_info_t")
    if struct is None:
        raise AssertionError("struct a64_mem_info_t is not in {0} exactly once".format(THREAD_CPP))
    function = function_body(source, "decode_a64_mem_inst")
    if function is None:
        raise AssertionError("decode_a64_mem_inst is not defined in {0} exactly once".format(THREAD_CPP))
    return enums[0], struct, function


class TheDecoderReadsRegisterOffsetForms(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, universal_newlines=True)
        if found.returncode != 0:
            raise unittest.SkipTest("xcrun finds no macOS `clang++`; a skip is not a pass")
        if not THREAD_CPP.is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(THREAD_CPP))

        enum, struct, function = decoder_parts(THREAD_CPP.read_text(encoding="utf-8", errors="replace"))
        driver_text = DRIVER.format(enum=enum, struct=struct, function=function,
                                    cases=", ".join("0x{0:08x}u".format(c[0]) for c in CASES))
        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / "driver"
            driver.write_text(driver_text, encoding="utf-8")
            built = subprocess.run(
                ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-o", str(binary), str(driver)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            if built.returncode != 0:
                raise AssertionError("clang++ failed:\n" + built.stdout)
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
        cls.code, cls.lines = ran.returncode, ran.stdout.splitlines()

    def test_the_driver_ran(self):
        self.assertEqual(self.code, 0, "the driver exited {0}".format(self.code))
        self.assertEqual(len(self.lines), len(CASES), "the driver printed {0} lines".format(len(self.lines)))

    def test_each_encoding_decodes_as_the_tables_say(self):
        for (inst, what, want), got in zip(CASES, self.lines):
            with self.subTest(what=what):
                self.assertEqual(got, "{0:08x} {1}".format(inst, want), what)


if __name__ == "__main__":
    unittest.main()
