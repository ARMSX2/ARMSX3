#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PairingLink.swift, run alone on the Mac host, parses SideStore's export link, accepts it once and only within 60
seconds of ARMSX3's request, and never logs; it imports Foundation only. It does not open SideStore or a real link."""

import base64
import os
import re
import tempfile
import unittest
import urllib.parse
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import without_comments

IOS = Path(__file__).resolve().parents[1]
SOURCE = IOS / "app" / "JIT" / "PairingLink.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")

PAYLOAD = os.urandom(48)
ENCODED = urllib.parse.quote(base64.b64encode(PAYLOAD).decode(), safe="")
GOOD = "armsx3://pairingFile?data=" + ENCODED
BIG_OK = "armsx3://pairingFile?data=" + "A" * 131072
BIG_BAD = "armsx3://pairingFile?data=" + "A" * 131076

PARSED = {
    "good": GOOD,
    "upper": "ARMSX3://PAIRINGFILE?data=" + ENCODED,
}
REFUSED = {
    "scheme": "https://pairingFile?data=" + ENCODED,
    "host": "armsx3://library?data=" + ENCODED,
    "second-item": GOOD + "&x=1",
    "two-data": GOOD + "&data=" + ENCODED,
    "no-value": "armsx3://pairingFile?data",
    "no-query": "armsx3://pairingFile",
    "bad-base64": "armsx3://pairingFile?data=%21%21%21",
    "empty": "armsx3://pairingFile?data=",
    "other-name": "armsx3://pairingFile?file=" + ENCODED,
    "too-big": BIG_BAD,
}

DRIVER = r"""
import Foundation
let payload = Data(base64Encoded: "@@PAYLOAD@@")!
func link(_ text: String) -> URL { URL(string: text)! }
func name(_ verdict: PairingLink.Verdict) -> String {
    switch verdict {
    case .accepted(let data): return data == payload ? "accepted" : "accepted-wrong-bytes"
    case .ignored: return "ignored"
    case .malformed: return "malformed"
    }
}
func show(_ key: String, _ value: String) { print(key + "\t" + value) }

@@PARSED@@
@@REFUSED@@

let suite = "armsx3.test.pairinglink.\(getpid())"
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
let t0 = Date(timeIntervalSince1970: 1_000_000)
let good = link("@@GOOD@@")

func fresh() { defaults.removePersistentDomain(forName: suite) }

fresh()
show("take no request", name(PairingLink.take(good, at: t0, in: defaults)))
fresh(); PairingLink.markRequested(at: t0, in: defaults)
show("awaiting now", "\(PairingLink.isAwaiting(at: t0, in: defaults))")
show("take at 30", name(PairingLink.take(good, at: t0.addingTimeInterval(30), in: defaults)))
show("take again", name(PairingLink.take(good, at: t0.addingTimeInterval(31), in: defaults)))
show("awaiting after take", "\(PairingLink.isAwaiting(at: t0.addingTimeInterval(31), in: defaults))")
fresh(); PairingLink.markRequested(at: t0, in: defaults)
show("take at 60", name(PairingLink.take(good, at: t0.addingTimeInterval(60), in: defaults)))
fresh(); PairingLink.markRequested(at: t0, in: defaults)
show("take at 61", name(PairingLink.take(good, at: t0.addingTimeInterval(61), in: defaults)))
show("take after stale", name(PairingLink.take(good, at: t0.addingTimeInterval(62), in: defaults)))
fresh(); PairingLink.markRequested(at: t0.addingTimeInterval(10), in: defaults)
show("take from the future", name(PairingLink.take(good, at: t0, in: defaults)))
fresh(); PairingLink.markRequested(at: t0, in: defaults)
show("take malformed", name(PairingLink.take(link("armsx3://pairingFile?data=%21"), at: t0, in: defaults)))
show("take after malformed", name(PairingLink.take(good, at: t0, in: defaults)))
show("request url", PairingLink.requestURL.absoluteString)
show("is link", "\(PairingLink.isPairingLink(good)) \(PairingLink.isPairingLink(link("armsx3://boot/ABCD12345")))")
show("window", "\(PairingLink.window) \(PairingLink.maxBase64)")
MainActor.assumeIsolated {
    PairingLink.lastAnswer = .added
    show("answer", PairingLink.lastAnswer?.rawValue ?? "nil")
}
"""


def driver():
    parsed = "\n".join(
        'show("parse {0}", PairingLink.parse(link("{1}")).map {{ $0 == payload ? "bytes" : "wrong" }} ?? "nil")'.format(k, v)
        for k, v in PARSED.items())
    # For these only whether they parse matters, not what they decode to.
    refused = "\n".join(
        'show("parse {0}", PairingLink.parse(link("{1}")) == nil ? "nil" : "bytes")'.format(k, v)
        for k, v in dict(REFUSED, **{"big-ok": BIG_OK}).items())
    return (DRIVER.replace("@@PAYLOAD@@", base64.b64encode(PAYLOAD).decode()).replace("@@GOOD@@", GOOD)
            .replace("@@PARSED@@", parsed).replace("@@REFUSED@@", refused))


EXPECTED = {
    "parse good": "bytes",
    "parse upper": "bytes",
    "parse big-ok": "bytes",
    "take no request": "ignored",
    "awaiting now": "true",
    "take at 30": "accepted",
    "take again": "ignored",
    "awaiting after take": "false",
    "take at 60": "accepted",
    "take at 61": "ignored",
    "take after stale": "ignored",
    "take from the future": "ignored",
    "take malformed": "malformed",
    "take after malformed": "ignored",
    "request url": "sidestore://pairing?urlname=armsx3",
    "is link": "true false",
    "window": "60.0 131072",
    "answer": "added",
}
EXPECTED.update({"parse " + k: "nil" for k in REFUSED})


def source_problems(text):
    problems = []
    code = without_comments(text)
    if "SPDX-License-Identifier: GPL-2.0-only" not in text.splitlines()[0]:
        problems.append("source: line 1 is not the SPDX GPL-2.0-only line")
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: PairingLink.swift imports {0}; it must import Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: PairingLink.swift logs; the link carries a private key")
    return problems


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def run(text=None):
    with tempfile.TemporaryDirectory() as tmp:
        source = SOURCE
        if text is not None:
            source = Path(tmp) / "PairingLink.swift"
            source.write_text(text, encoding="utf-8")
        out = compile_and_run([source], driver(), tmp)
    return dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)


class TheLinkIsParsedAndWindowed(unittest.TestCase):
    def test_every_case(self):
        got = run()
        for key, want in EXPECTED.items():
            self.assertEqual(want, got.get(key), key)


class TheFileIsClean(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], source_problems(read(SOURCE)))

    def test_mutation_logging_is_caught(self):
        self.assertTrue(any(p.startswith("log:") for p in source_problems(read(SOURCE) + "\nfunc probe(_ u: URL) { print(u) }\n")))

    def test_mutation_the_window_is_not_enforced(self):
        text = read(SOURCE)
        anchor = "age >= 0 && age <= window"
        self.assertIn(anchor, text)
        self.assertEqual("accepted", run(text.replace(anchor, "true", 1))["take at 61"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
