#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""HelperDecisions.swift, run alone on the Mac host, gives the DDI verdict, retry gate, mount-result class and short
reason token as the tables here say; the vendored EndpointProbe.swift fails closed and unreachable ports within its
bound, and the request handler probes, retries and mounts nothing in its pre-main pass."""

import hashlib
import re
import socket
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
DECISIONS = IOS / "jit-enabler" / "JITEnablerExtension" / "HelperDecisions.swift"
HANDLER = IOS / "jit-enabler" / "JITEnablerExtension" / "JITEnablerRequestHandler.swift"
PROBE = IOS / "jit-enabler" / "engine" / "EndpointProbe.swift"
PROBE_BLOB = "13f50b7c202f994c62db778d9431588db9995a4b"  # VENDOR.md's audited blob SHA at StikJIT 3623e725

# (debugProxyListed, legacy) -> verdict. "n" is nil.
def expected(rsd, legacy):
    decided = rsd if rsd != "n" else legacy
    return {"t": "mounted", "f": "not-mounted", "n": "unknown"}[decided]


VERDICT_DRIVER = r'''
import Foundation
func v(_ c: Character) -> Bool? { c == "n" ? nil : c == "t" }
for r in "ntf" { for l in "ntf" {
    print("\(r)\(l)=\(HelperDecisions.ddiVerdict(debugProxyListed: v(r), legacy: v(l)).rawValue)")
} }
print("retry2500=\(HelperDecisions.shouldRetryTunnel(elapsedMs: 2500)) retry2501=\(HelperDecisions.shouldRetryTunnel(elapsedMs: 2501))")
print("probeTimeout=\(HelperDecisions.probeTimeout)")
'''

PROBE_DRIVER = r'''
import Foundation
for (address, port) in [("127.0.0.1", UInt16(LISTENING)), ("127.0.0.1", UInt16(CLOSED)), ("192.0.2.1", UInt16(49152))] {
    let start = Date()
    let reason = EndpointProbe.failureReason(address: address, port: port, timeout: 1.5)
    print("\(address):\(port) \(reason == nil ? "ok" : "fail") \(Int(Date().timeIntervalSince(start) * 1000))")
}
'''


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def without_comments(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", source)


def handler_problems(handler):
    code = without_comments(handler)
    problems = []
    probe, tunnel = code.find("EndpointProbe.failureReason("), code.find("tunnel_create_rppairing(")
    if probe < 0 or tunnel < 0 or probe > tunnel:
        problems.append("probe: the handler does not probe the endpoint before tunnel_create_rppairing")
    if "HelperDecisions.shouldRetryTunnel(" not in code:
        problems.append("retry: the second tunnel attempt is not gated on HelperDecisions.shouldRetryTunnel")
    if "HelperDecisions.ddiVerdict(" not in code or "rsd_service_available(" not in code:
        problems.append("ddi: the handler does not ask RSD and feed HelperDecisions.ddiVerdict")
    if "cryptexd_" in code:
        problems.append("link: the handler names a cryptexd_ symbol, which the audited idevice archive does not carry")
    # The `link:` and `r7:` names mean the pre-main pass mounts and downloads nothing.
    for forbidden in ("image_mounter_mount", "cryptexd_install_ddi", "cryptex1_assets_load", "idevice_init_logger"):
        if forbidden in code:
            problems.append("r7: the handler calls {0}".format(forbidden))
    return problems


# The mount-result classifier and its reason token.

STEPS = (None, "checking", "downloading", "mounting", "verifying")
KINDS = ("pairing-file", "debug-proxy", "device", "ddi-download", "ddi-files-missing", "ddi-not-mounted", "other")
RULE4 = "        if step == .downloading || errorKind == \"ddi-download\" { return .download }\n"
RULE6 = "        if step == .mounting || step == .verifying { return .mount }\n"
TOKEN_CAP = "prefix(40)"


def expected_mount(probe, step, kind):
    """The seven rules, in order, the first that holds."""
    if probe:
        return "unreachable"
    if step is None:
        return "ready"
    if kind == "pairing-file":
        return "pairing"
    if step == "downloading" or kind == "ddi-download":
        return "download"
    if step == "checking" and kind == "device":
        return "pairing"
    if step in ("mounting", "verifying"):
        return "mount"
    return "other"


MOUNT_DRIVER = r'''
import Foundation
let steps: [MountStep?] = [nil, .checking, .downloading, .mounting, .verifying]
let kinds = ["pairing-file", "debug-proxy", "device", "ddi-download", "ddi-files-missing", "ddi-not-mounted", "other"]
for p in [true, false] { for s in steps { for k in kinds {
    print("mount \(p) \(s?.rawValue ?? "nil") \(k) \(HelperDecisions.mountResult(probeFailed: p, failedStep: s, errorKind: k).rawValue)")
} } }
'''

# name -> (Swift call, the exact token wanted or None when only the form is checked)
TOKEN_CASES = {
    "example": ("HelperDecisions.reasonToken(step: .mounting, kind: \"device\", code: -8, subCode: 0)", "mounting:device:-8/0"),
    "probe": ("HelperDecisions.reasonToken(step: nil, kind: \"unreachable\", code: nil, subCode: nil)", "probe:unreachable"),
    "upper": ("HelperDecisions.reasonToken(step: .checking, kind: \"Pairing File\", code: nil, subCode: nil)", "checking:pairingfile"),
    "slashes": ("HelperDecisions.reasonToken(step: .downloading, kind: \"/var/x:y\", code: 2, subCode: 1)", "downloading:varxy:2/1"),
    "long": ("HelperDecisions.reasonToken(step: .verifying, kind: String(repeating: \"ab\", count: 60), code: Int32.min, subCode: Int32.min)", None),
    "wide": ("HelperDecisions.reasonToken(step: .mounting, kind: \"device\", code: Int32.min, subCode: Int32.min)", None),
}


def token_driver():
    lines = ["import Foundation"]
    for name, (call, _) in sorted(TOKEN_CASES.items()):
        lines.append("print(\"token {0} \\({1})\")".format(name, call))
    return "\n".join(lines) + "\n"


def mount_table_problems(out):
    rows = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 5 and parts[0] == "mount":
            rows[(parts[1] == "true", None if parts[2] == "nil" else parts[2], parts[3])] = parts[4]
    want = {(p, s, k): expected_mount(p, s, k) for p in (True, False) for s in STEPS for k in KINDS}
    problems = []
    if len(rows) != len(want):
        problems.append("mount: the driver printed {0} rows, the table has {1}".format(len(rows), len(want)))
    for key, value in sorted(want.items(), key=str):
        if rows.get(key) != value:
            problems.append("mount: {0} gave {1}, the rules give {2}".format(key, rows.get(key), value))
    return problems


def token_problems(out):
    tokens = {}
    for line in out.splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3 and parts[0] == "token":
            tokens[parts[1]] = parts[2]
    problems = []
    for name, (_, wanted) in sorted(TOKEN_CASES.items()):
        token = tokens.get(name)
        if token is None:
            problems.append("token: {0} printed nothing".format(name))
            continue
        if not re.fullmatch(r"[a-z0-9:/-]{1,40}", token):
            problems.append("token: {0} is {1!r}, not 1-40 characters of [a-z0-9:/-]".format(name, token))
        if wanted is not None and token != wanted:
            problems.append("token: {0} is {1!r}, wanted {2!r}".format(name, token, wanted))
    return problems


def compile_decisions(source_text, driver):
    """Compile `source_text` (HelperDecisions.swift, maybe mutated) alone with `driver`."""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "src"
        src.mkdir()
        decisions = src / "HelperDecisions.swift"
        decisions.write_text(source_text, encoding="utf-8")
        return compile_and_run([decisions], driver, tmp)


def edited(text, old, new):
    assert old in text, "mutation anchor missing: {0!r}".format(old)
    changed = text.replace(old, new, 1)
    assert changed != text
    return changed


class MountTable(unittest.TestCase):
    measured = None

    @classmethod
    def tearDownClass(cls):
        # Printed after the class's results and flushed, so the line never splits unittest's "... ok".
        if cls.measured is not None:
            print("MEASURED mountResult: {0} rows compared".format(cls.measured), flush=True)

    def test_every_combination_follows_the_seven_rules(self):
        out = compile_decisions(read(DECISIONS), MOUNT_DRIVER)
        self.assertEqual([], mount_table_problems(out))
        type(self).measured = sum(1 for l in out.splitlines() if l.startswith("mount "))

    def test_mutation_rule_4_after_rule_6(self):
        text = read(DECISIONS)
        moved = edited(edited(text, RULE4, ""), RULE6, RULE6 + RULE4)
        self.assertNotEqual([], mount_table_problems(compile_decisions(moved, MOUNT_DRIVER)))


class Token(unittest.TestCase):
    def test_tokens_are_path_free_and_capped(self):
        self.assertEqual([], token_problems(compile_decisions(read(DECISIONS), token_driver())))

    def test_mutation_the_length_cap_removed(self):
        text = edited(read(DECISIONS), TOKEN_CAP, "prefix(4000)")
        self.assertNotEqual([], token_problems(compile_decisions(text, token_driver())))


class Verdict(unittest.TestCase):
    def test_table_and_retry_edge(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([DECISIONS], VERDICT_DRIVER, tmp).split("\n")
        rows = dict(line.split("=", 1) for line in out if len(line) > 3 and line[2] == "=")
        want = {r + l: expected(r, l) for r in "ntf" for l in "ntf"}
        self.assertEqual(want, rows)
        self.assertIn("retry2500=true retry2501=false", out)
        self.assertIn("probeTimeout=1.5", out)


class Probe(unittest.TestCase):
    measured = None

    @classmethod
    def tearDownClass(cls):
        # Printed after the result line and flushed, for the reason MountTable gives.
        if cls.measured is not None:
            print(cls.measured, flush=True)

    def test_probe_answers_and_stays_bounded(self):
        listening = socket.socket()
        listening.bind(("127.0.0.1", 0))
        listening.listen(1)
        closed = socket.socket()
        closed.bind(("127.0.0.1", 0))
        closed_port = closed.getsockname()[1]
        closed.close()
        try:
            driver = PROBE_DRIVER.replace("LISTENING", str(listening.getsockname()[1])).replace("CLOSED", str(closed_port))
            with tempfile.TemporaryDirectory() as tmp:
                out = compile_and_run([PROBE], driver, tmp)
        finally:
            listening.close()
        lines = [l.split() for l in out.strip().split("\n")]
        self.assertEqual(3, len(lines), out)
        (_, ok, _), (_, refused, refused_ms), (_, blackhole, blackhole_ms) = lines
        self.assertEqual("ok", ok, out)
        self.assertEqual("fail", refused, out)
        self.assertLessEqual(int(refused_ms), 2000, out)
        self.assertEqual("fail", blackhole, out)
        self.assertLessEqual(int(blackhole_ms), 2000, out)
        type(self).measured = "MEASURED probe: refused {0} ms, non-routable {1} ms (bound 1500 ms; idevice TCP timeout 5000 ms)".format(
            refused_ms, blackhole_ms)


class Vendored(unittest.TestCase):
    def test_the_compiled_probe_is_the_audited_blob(self):
        data = PROBE.read_bytes()
        self.assertEqual(PROBE_BLOB, hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest())


class Wired(unittest.TestCase):
    def test_handler_is_wired(self):
        self.assertEqual([], handler_problems(read(HANDLER)))

    def test_mutation_tunnel_before_probe(self):
        code = "tunnel_create_rppairing(x)\nEndpointProbe.failureReason(y)\nHelperDecisions.shouldRetryTunnel(1)\n" \
               "HelperDecisions.ddiVerdict(a)\nrsd_service_available(b)\n"
        self.assertTrue(any(p.startswith("probe:") for p in handler_problems(code)))

    def test_mutation_install_ddi(self):
        mutated = read(HANDLER) + "\nlet _ = cryptexd_install_ddi(a, b)\n"
        self.assertTrue(any(p.startswith("r7:") for p in handler_problems(mutated)))


if __name__ == "__main__":
    unittest.main()
