# SPDX-License-Identifier: GPL-2.0-only
"""The bridge header rpcs3_ios_abi.h compiles as C11, so Swift can call every declaration in it.
"""

import os
import shutil
import subprocess
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

TREE_ENV_VAR = "ARMSX3_FRONTEND_SYMBOLS_ROOT"

TREE = Path(os.environ.get(TREE_ENV_VAR, "").strip() or str(ROOT))

HEADER = TREE / "ios" / "bridge" / "rpcs3_ios_abi.h"

# The command, written once, must match the sentence the header publishes about itself.
C11_COMPILE = ("cc", "-fsyntax-only", "-x", "c", "-std=c11")

# Bounded, because a check that never reports reads like a skip; this parse returns in milliseconds.
C11_TIMEOUT_SECONDS = 60


class TheHeaderCompilesAsC11(unittest.TestCase):
    """The real C11 compile of the header, read through the tree root so a mutated scratch tree is the one compiled."""

    def test_a_c_compiler_is_available_or_this_is_not_a_pass(self):
        found = shutil.which(C11_COMPILE[0])
        if found is None:
            self.skipTest(
                "no `{0}` on PATH, so the C11 compile of {1} did not run. THIS SKIP IS NOT A "
                "PASS. The regex half of assertion 4 ran and is strictly weaker: it reads the "
                "`extern \"C\"` block for C++-only spellings it knows about, which is not the "
                "same as a C compiler accepting the file. The bridging header "
                "rest on the compile, not on the regex. Run the suite on a host with a C "
                "compiler before treating this header as C-callable.".format(
                    C11_COMPILE[0], HEADER
                )
            )

    def test_the_header_compiles_under_c11(self):
        found = shutil.which(C11_COMPILE[0])
        if found is None:
            self.skipTest(
                "no `{0}` on PATH. THIS SKIP IS NOT A PASS -- see the assertion above, which "
                "reports the same absence as its own result so a green log cannot hide "
                "it.".format(C11_COMPILE[0])
            )

        self.assertTrue(
            HEADER.is_file(),
            "{0} is missing, so there is nothing to compile. Set {1} to audit another "
            "tree.".format(HEADER, TREE_ENV_VAR),
        )

        argv = [found] + list(C11_COMPILE[1:]) + [str(HEADER)]
        try:
            done = subprocess.run(
                argv,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=C11_TIMEOUT_SECONDS,
            )
        except subprocess.TimeoutExpired:
            self.fail(
                "THE C11 COMPILE DID NOT RETURN within {0}s: {1}. A bounded check that expires "
                "is a FAILURE and not a skip -- an unbounded one is a required check that never "
                "reports, which reads the same as one that was never run.".format(
                    C11_TIMEOUT_SECONDS, " ".join(argv)
                )
            )

        self.assertEqual(
            0, done.returncode,
            "THE HEADER DOES NOT COMPILE AS C11. Command: {0}\n{1}\n"
            "`extern \"C\"` disables NAME MANGLING and nothing else. A declaration a C compiler "
            "rejects is one Swift cannot call, and the bridging header INCLUDES "
            "this file rather than redeclaring it. Fix the header; do not weaken this "
            "check to the regex above.".format(
                " ".join(argv), done.stdout.decode("utf-8", "replace").rstrip()
            ),
        )


if __name__ == "__main__":
    unittest.main()
