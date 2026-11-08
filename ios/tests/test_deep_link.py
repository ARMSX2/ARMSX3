#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""DeepLink.swift, run alone on the Mac host, accepts only strict armsx3:// boot, games and settings links and refuses
everything else; static checks keep it Foundation-only, silent and free of loose matching.
It does not run the app or boot a title."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
DEEPLINK = IOS / "app" / "Shell" / "DeepLink.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
FUZZY = re.compile(r"\.contains\(|\.hasPrefix\(|localizedCaseInsensitive")

ACCEPTED = {
    "armsx3://boot/SMOKE0001": "boot(SMOKE0001)",
    "ARMSX3://BOOT/smoke0001": "boot(SMOKE0001)",
    "armsx3://boot/ABCD12345/": "boot(ABCD12345)",
    "armsx3://library": "library",
    "armsx3://settings/": "settings",
}

REFUSED = [
    "https://boot/ABCD12345",
    "armsx3://boot",
    "armsx3://boot/",
    "armsx3://boot/ABCD1234",
    "armsx3://boot/ABCD123456",
    "armsx3://boot/ABCD-2345",
    "armsx3://boot/AB%2FCDEFGH",
    "armsx3://boot/ABCD12345/extra",
    "armsx3://boot/ABCD12345?x=1",
    "armsx3://boot/ABCD12345#x",
    "armsx3://user@boot/ABCD12345",
    "armsx3://boot:1/ABCD12345",
    "armsx3://store",
    "armsx3://library/x",
]

DRIVER = r"""
import Foundation

func show(_ link: DeepLink?) -> String {
    switch link {
    case .boot(let serial): return "boot(\(serial))"
    case .library: return "library"
    case .settings: return "settings"
    case nil: return "nil"
    }
}
for text in @@URLS@@ {
    print(text + " -> " + (URL(string: text).map { show(DeepLink.parse($0)) } ?? "nil"))
}
let copied = DeepLink.copyLink(serial: "ABCD12345")
print("COPY " + copied + " -> " + show(URL(string: copied).flatMap(DeepLink.parse)))
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def deeplink_problems(text):
    problems = []
    code = without_comments(text)
    imports = IMPORT.findall(code)
    if imports != ["Foundation"]:
        problems.append("host: DeepLink.swift imports {0}; it must import Foundation only".format(imports))
    if LOGGING.search(code):
        problems.append("log: DeepLink.swift logs; a URL or serial is never logged")
    if FUZZY.search(code):
        problems.append("fuzzy: DeepLink.swift matches loosely; the grammar is exact")
    return problems


class TheParserAcceptsAndRefuses(unittest.TestCase):
    results = None

    @classmethod
    def setUpClass(cls):
        read(DEEPLINK)
        urls = list(ACCEPTED) + REFUSED
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([DEEPLINK], DRIVER.replace("@@URLS@@", json.dumps(urls)), tmp)
        cls.results = dict(line.rsplit(" -> ", 1) for line in out.splitlines() if " -> " in line)

    def test_accepted(self):
        for url, expected in ACCEPTED.items():
            self.assertEqual(expected, self.results.get(url), url)

    def test_refused(self):
        for url in REFUSED:
            self.assertEqual("nil", self.results.get(url), url)

    def test_copy_link_round_trips(self):
        self.assertEqual("boot(ABCD12345)", self.results.get("COPY armsx3://boot/ABCD12345"))


class TheDeepLinkFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], deeplink_problems(read(DEEPLINK)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(DEEPLINK.with_name("NoSuchFile.swift"))
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class TheDeepLinkGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(DEEPLINK)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, text, check):
        problems = deeplink_problems(text)
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_host_imports_uikit(self):
        self.assert_names(self.edited(self.text, "import Foundation", "import Foundation\nimport UIKit"), "host")

    def test_mutation_log_the_url(self):
        self.assert_names(self.text + "\nfunc probe(_ u: URL) { print(u) }\n", "log")

    def test_mutation_fuzzy_match(self):
        self.assert_names(self.text + "\nfunc probe(_ s: String) -> Bool { s.hasPrefix(\"BL\") }\n", "fuzzy")


if __name__ == "__main__":
    unittest.main(verbosity=2)
