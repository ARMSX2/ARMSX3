# SPDX-License-Identifier: GPL-2.0-only
"""The RPCN derive header and the session code in rpcn_bridge.h, each compiled with the host c++ and run.
The printed lines are compared exactly.
"""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import brace_span, read


IOS_DIR = Path(__file__).resolve().parents[1]

LOGIC_H = IOS_DIR / "bridge" / "rpcn_bridge.h"

RDF_LINE = "RDF forms=2 first=derived second=typed prederived=1 empty=0"

RSD_LINE = "RSD none=0 signing=1 signed=2 failed=3 notrpcn=0"

SESSION_CODE = re.compile(r"inline\s+int32_t\s+session_code\s*\([^)]*\)\s*\{")

SESSION_DRIVER = r"""
#include <cstdint>
#include <cstdio>
#include "rpcs3_ios_abi.h"

namespace probe
{
@SESSION_CODE@
}

int main()
{
	using probe::session_code;
	std::printf("RSD none=%d signing=%d signed=%d failed=%d notrpcn=%d\n", session_code(false, false, 0, -1, false),
		session_code(true, false, RPCS3_IOS_RPCN_STATE_OK, 2, true), session_code(true, true, RPCS3_IOS_RPCN_STATE_OK, 2, true),
		session_code(true, false, RPCS3_IOS_RPCN_STATE_CONNECT, 2, false), session_code(false, false, 0, 0, false));
	return 0;
}
"""

FORMS_DRIVER = r"""
#include "rpcn_derive.h"
#include <cstdio>
#include <string>
#include <string_view>

int main()
{
	using armsx3::bridge::rpcn::password_forms;
	const auto fake = [](std::string_view typed) { return "derived:" + std::string(typed); };
	const auto forms = password_forms("probe-password", fake);
	const auto label = [](const std::string& form) {
		return form == "derived:probe-password" ? "derived" : form == "probe-password" ? "typed" : "other";
	};
	const std::string pre(64, 'A');
	const auto prederived = password_forms(pre, fake);
	const auto empty = password_forms("", fake);
	std::printf("RDF forms=%zu first=%s second=%s prederived=%d empty=%zu\n", forms.size(),
		forms.size() > 0 ? label(forms[0]) : "none", forms.size() > 1 ? label(forms[1]) : "none",
		prederived.size() == 1 && prederived[0] == pre ? 1 : 0, empty.size());
	return 0;
}
"""


class TheFormsComeInOrder(unittest.TestCase):
    """rpcn_derive.h compiled on the host with a fake PBKDF2. A host run, not device evidence."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no c++ on PATH; a skip is not a pass")
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "forms.cpp"
            source.write_text(FORMS_DRIVER, encoding="utf-8")
            built = subprocess.run([compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-I", str(IOS_DIR / "bridge"),
                                    str(source), "-o", str(Path(tmp) / "forms")],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, universal_newlines=True)
            cls.build = built.stdout
            cls.out = ""
            if built.returncode == 0:
                cls.out = subprocess.run([str(Path(tmp) / "forms")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         timeout=30, universal_newlines=True).stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("RDF ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_derived_first_typed_second(self):
        self.assertEqual([RDF_LINE], self.lines, self.build[-4000:])


class TheSessionDerives(unittest.TestCase):
    """rpcn_bridge.h's session_code compiled alone on the host. Not device evidence."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no c++ on PATH; a skip is not a pass")
        header = read(LOGIC_H)
        found = SESSION_CODE.search(header)
        if found is None:
            raise AssertionError("rpcn_bridge.h defines no inline int32_t session_code(...)")
        function = header[found.start():brace_span(header, found.end() - 1)[1] + 1]
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "session.cpp"
            source.write_text(SESSION_DRIVER.replace("@SESSION_CODE@", function), encoding="utf-8")
            built = subprocess.run([compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-I", str(IOS_DIR / "bridge"),
                                    str(source), "-o", str(Path(tmp) / "session")],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, universal_newlines=True)
            cls.build = built.stdout
            cls.out = ""
            if built.returncode == 0:
                cls.out = subprocess.run([str(Path(tmp) / "session")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         timeout=30, universal_newlines=True).stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("RSD ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_five_inputs_give_the_five_sessions(self):
        self.assertEqual([RSD_LINE], self.lines, self.build[-4000:])


if __name__ == "__main__":
    unittest.main()
