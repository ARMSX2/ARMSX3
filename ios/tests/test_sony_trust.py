#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""Sony's version-list host is trusted through Sony's own root only, and the app opens exactly two ATS exceptions.
ThePinAndTheParserHold compiles SonyTrust.swift and GameUpdates.swift with swiftc and runs them against
openssl-made certificates and synthetic version lists. The static checks cover the same sources and ios/app/Resources/Info.plist."""

import datetime
import hashlib
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
TRUST = APP / "Updates" / "SonyTrust.swift"
UPDATES = APP / "Updates" / "GameUpdates.swift"
HARNESS_PLIST = IOS / "app" / "Resources" / "Info.plist"

ROOT_SHA256 = "51d5a7833f67f1d1c6212f75997e4b83dada0f7910ba4b5a3fa4e9b7b9bc827c"
HOSTS = {"a0.ww.np.dl.playstation.net", "b0.ww.np.dl.playstation.net"}
EXCEPTION = {"NSExceptionAllowsInsecureHTTPLoads": True}
LIST_LITERAL = '"https://a0.ww.np.dl.playstation.net/tpl/np/"'

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
PLAN_ID = re.compile(r"D-17-|GUPD-|17-0")
ANCHORS_ONLY = re.compile(r"SecTrustSetAnchorCertificatesOnly\([^)]*\btrue\s*\)")

CA_CONFIG = """[req]
distinguished_name = dn
prompt = no
[dn]
CN = SCEI DNAS Root 05
[ca]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
"""
LEAF_CONFIG = """[req]
distinguished_name = dn
prompt = no
[dn]
CN = *.ww.np.dl.playstation.net
"""

PACKAGE_URL = "http://b0.ww.np.dl.playstation.net/tppkg/np/ABCD12345/ABCD12345-A01{0}-V0100.pkg"
TWO_PACKAGES = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<titlepatch status="alive" titleid="ABCD12345"><tag name="ABCD12345_T1" popup="true">'
    '<package version="01.01" size="1000" sha1sum="{0}" url="{1}" ps3_system_ver="04.6000"/>'
    '<package version="01.02" size="2000" sha1sum="{0}" url="{2}" ps3_system_ver="04.6500">'
    "<paramsfo><TITLE>Synthetic Title</TITLE></paramsfo></package></tag></titlepatch>"
).format("ab" * 20, PACKAGE_URL.format("01"), PACKAGE_URL.format("02"))
MAX_BODY = 256 * 1024

BODIES = {
    "two.bin": TWO_PACKAGES.encode(),
    "patch.bin": TWO_PACKAGES.replace("titlepatch", "patch").encode(),
    "over.bin": TWO_PACKAGES.encode().ljust(MAX_BODY + 1, b" "),
    "max.bin": TWO_PACKAGES.encode().ljust(MAX_BODY, b" "),
    "doctype.bin": ("<!DOCTYPE titlepatch>" + TWO_PACKAGES.split("?>", 1)[1]).encode(),
    "entity.bin": TWO_PACKAGES.replace("<tag ", "<!-- <!ENTITY x 'y'> --><tag ").encode(),
}

DRIVER = r"""
import Foundation
import Security

let dir = URL(fileURLWithPath: @@TMP@@)
func bytes(_ name: String) -> Data { try! Data(contentsOf: dir.appendingPathComponent(name)) }
func cert(_ name: String) -> SecCertificate { SecCertificateCreateWithData(nil, bytes(name) as CFData)! }

let ca = cert("ca.der"), ca2 = cert("ca2.der"), leaf = cert("leaf.der"), leaf2 = cert("leaf2.der")
let host = "a0.ww.np.dl.playstation.net"
func pin(_ chain: [SecCertificate], _ host: String, _ anchor: SecCertificate, _ date: Date? = nil) -> Bool {
    SonyTrust.evaluate(chain: chain, host: host, anchor: anchor, at: date)
}

print("P1 chain=\(pin([leaf, ca], host, ca)) leaf=\(pin([leaf], host, ca))")
print("P2 forged=\(pin([leaf, ca], host, ca2)) other=\(pin([leaf2, ca2], host, ca)) otherleaf=\(pin([leaf2], host, ca))")
let late = Date(timeIntervalSince1970: @@LATE@@)
print("P3 late=\(pin([leaf, ca], host, ca, late))")
let upper = pin([leaf, ca], "A0.WW.NP.DL.PLAYSTATION.NET", ca)
let deeper = pin([leaf, ca], "x.a0.ww.np.dl.playstation.net", ca)
let fused = pin([leaf, ca], "evilww.np.dl.playstation.net", ca)
let bare = pin([leaf, ca], "ww.np.dl.playstation.net", ca)
let empty = pin([leaf, ca], "", ca)
print("P4 upper=\(upper) deeper=\(deeper) fused=\(fused) bare=\(bare) empty=\(empty) nochain=\(pin([], host, ca))")
print("H1 exact=\(SonyTrust.hostMatches("a.b", "a.b")) other=\(SonyTrust.hostMatches("a.b", "c.b")) one=\(SonyTrust.hostMatches("*.a.b", "c.a.b")) none=\(SonyTrust.hostMatches("*.a.b", "a.b"))")

let probe = SonyPinDelegate()
let idle = URLSession(configuration: .ephemeral)
let listURL = URL(string: "https://a0.ww.np.dl.playstation.net/tpl/np/ABCD12345/ABCD12345-ver.xml")!
let moved = HTTPURLResponse(url: listURL, statusCode: 302, httpVersion: nil, headerFields: nil)!
var redirect = "unanswered"
probe.urlSession(idle, task: idle.dataTask(with: listURL), willPerformHTTPRedirection: moved,
                 newRequest: URLRequest(url: URL(string: "http://a0.ww.np.dl.playstation.net/x")!)) {
    redirect = $0 == nil ? "refused" : "followed"
}
print("P5 redirect=\(redirect)")

try! Data(SonyTrust.rootDER).write(to: dir.appendingPathComponent("shipped.der"))
var cn: CFString?
SecCertificateCopyCommonName(SonyTrust.root, &cn)
print("R1 cn=\((cn as String?) ?? "nil")")

func word(_ outcome: LookupOutcome) -> String {
    switch outcome {
    case .none: return "none"
    case .found(let patch): return "found\(patch.packages.count)"
    case .failed(.http(let code)): return "http\(code)"
    case .failed(let failure): return "\(failure)"
    }
}
let id = "ABCD12345"
func outcome(_ status: Int, _ body: Data) -> String { word(GameUpdates.outcome(status: status, body: body, titleID: id)) }
print("O1 404=\(outcome(404, Data("Not found".utf8))) 403=\(outcome(403, Data())) empty=\(outcome(200, Data())) ws=\(outcome(200, Data("  \n".utf8))) 500=\(outcome(500, Data())) two=\(outcome(200, bytes("two.bin"))) patch=\(outcome(200, bytes("patch.bin")))")

func parsed(_ name: String, _ titleID: String = id) -> String { VerXml.parse(bytes(name), titleID: titleID) == nil ? "nil" : "ok" }
let two = VerXml.parse(bytes("two.bin"), titleID: id)!
let versions = two.packages.map(\.version).joined(separator: ",")
let sizes = two.packages.map { String($0.size) }.joined(separator: ",")
let firmware = two.packages.map { $0.firmware ?? "nil" }.joined(separator: ",")
print("X1 over=\(parsed("over.bin")) max=\(parsed("max.bin")) doctype=\(parsed("doctype.bin")) entity=\(parsed("entity.bin")) other=\(parsed("two.bin", "ABCD12346")) versions=\(versions) sizes=\(sizes) fw=\(firmware) name=\(two.name ?? "nil")")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def code_lines(text):
    return "\n".join(line for line in text.splitlines() if not line.strip().startswith("//"))


def comment_lines(text):
    return [line for line in text.splitlines() if line.strip().startswith("//")]


def trust_problems(raw):
    trust, updates = raw["SonyTrust.swift"], raw["GameUpdates.swift"]
    trust_code, updates_code = code_lines(trust), code_lines(updates)
    problems = []
    if sorted(IMPORT.findall(trust_code)) != ["Foundation", "Security"]:
        problems.append("imports: SonyTrust.swift imports {0}, not exactly Foundation and Security".format(
            sorted(IMPORT.findall(trust_code))))
    if IMPORT.findall(updates_code) != ["Foundation"]:
        problems.append("imports: GameUpdates.swift imports {0}, not only Foundation".format(IMPORT.findall(updates_code)))
    if "SecPolicyCreateBasicX509()" not in trust_code:
        problems.append("basic: SonyTrust.swift never calls SecPolicyCreateBasicX509()")
    if not ANCHORS_ONLY.search(trust_code):
        problems.append("anchorsonly: SonyTrust.swift never calls SecTrustSetAnchorCertificatesOnly(…, true)")
    if ".cancelAuthenticationChallenge" not in trust_code:
        problems.append("cancel: SonyTrust.swift never answers .cancelAuthenticationChallenge")
    if trust_code.count(".useCredential") != 1:
        problems.append("credential: SonyTrust.swift names .useCredential {0} times, not once".format(
            trust_code.count(".useCredential")))
    if ".performDefaultHandling" in trust_code:
        problems.append("default: SonyTrust.swift answers a challenge with .performDefaultHandling")
    if "willPerformHTTPRedirection" not in trust_code or "completionHandler(nil)" not in trust_code:
        problems.append("redirect: the pin delegate follows redirects")
    outcome = trust_code[trust_code.find("func outcome(for"):]
    final = outcome.find("GameUpdates.listHost")
    if "response.url" not in outcome or final < 0 or final > outcome.find("GameUpdates.outcome("):
        problems.append("redirect: the lookup does not check its final URL before reading the body")
    if "SecPolicyCreateSSL" in trust_code:
        problems.append("ssl: SonyTrust.swift names SecPolicyCreateSSL")
    if LIST_LITERAL not in updates_code or "http://a0" in updates_code:
        problems.append("url: GameUpdates.swift lacks the https list-host literal or carries an http://a0 one")
    for name, code in (("SonyTrust.swift", trust_code), ("GameUpdates.swift", updates_code)):
        if LOGGING.search(code):
            problems.append("log: {0} logs".format(name))
    for name, text in (("SonyTrust.swift", trust), ("GameUpdates.swift", updates)):
        if any(PLAN_ID.search(line) for line in comment_lines(text)):
            problems.append("comment: {0} names a plan ID in a comment".format(name))
    return sorted(problems)


def all_keys(value):
    if isinstance(value, dict):
        for key, inner in value.items():
            yield key
            for nested in all_keys(inner):
                yield nested
    elif isinstance(value, list):
        for inner in value:
            for nested in all_keys(inner):
                yield nested


def ats_problems(plists):
    problems = []
    for name in sorted(plists):
        try:
            info = plistlib.loads(plists[name].encode("utf-8"))
        except Exception as error:  # a broken plist is a finding, not a crash
            problems.append("ats: {0} does not parse ({1})".format(name, type(error).__name__))
            continue
        ats = info.get("NSAppTransportSecurity")
        domains = ats.get("NSExceptionDomains") if isinstance(ats, dict) else None
        if not isinstance(ats, dict) or set(ats) != {"NSExceptionDomains"} or not isinstance(domains, dict):
            problems.append("ats: {0}'s NSAppTransportSecurity is not exactly NSExceptionDomains".format(name))
        elif set(domains) != HOSTS or any(domains[host] != EXCEPTION for host in domains):
            problems.append("ats: {0}'s exception domains are not exactly a0 and b0 with insecure HTTP loads".format(name))
        wide = [key for key in all_keys(info) if key.startswith("NSAllowsArbitraryLoads") or key == "NSIncludesSubdomains"]
        if wide:
            problems.append("wide: {0} carries {1}".format(name, sorted(set(wide))))
    return problems


def real_trust():
    return {"SonyTrust.swift": read(TRUST), "GameUpdates.swift": read(UPDATES)}


def real_plists():
    return {"Info.plist": read(HARNESS_PLIST)}


def make_certificates(openssl, tmp):
    """The four test certificates as DER into tmp. Returns the leaf's notAfter as a UTC datetime."""
    tmp = Path(tmp)
    (tmp / "ca.cnf").write_text(CA_CONFIG, encoding="utf-8")
    (tmp / "leaf.cnf").write_text(LEAF_CONFIG, encoding="utf-8")

    def run(*args):
        subprocess.run([openssl] + list(args), cwd=str(tmp), check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)

    for ca, leaf, serial in (("ca", "leaf", "2"), ("ca2", "leaf2", "3")):
        run("req", "-x509", "-config", "ca.cnf", "-extensions", "ca", "-newkey", "rsa:2048", "-nodes",
            "-keyout", ca + ".key", "-out", ca + ".pem", "-sha1", "-days", "3650")
        run("req", "-new", "-config", "leaf.cnf", "-newkey", "rsa:2048", "-nodes", "-keyout", leaf + ".key",
            "-out", leaf + ".csr")
        run("x509", "-req", "-in", leaf + ".csr", "-CA", ca + ".pem", "-CAkey", ca + ".key", "-set_serial", serial,
            "-days", "365", "-sha1", "-out", leaf + ".pem")
        for name in (ca, leaf):
            run("x509", "-in", name + ".pem", "-outform", "DER", "-out", name + ".der")
    end = subprocess.run([openssl, "x509", "-in", "leaf.pem", "-noout", "-enddate"], cwd=str(tmp), check=True,
                         stdout=subprocess.PIPE, universal_newlines=True).stdout.strip().split("=", 1)[1]
    return datetime.datetime.strptime(end, "%b %d %H:%M:%S %Y GMT").replace(tzinfo=datetime.timezone.utc)


def certificates(tmp):
    """Tries PATH's openssl first, then /usr/bin/openssl; skips when neither can sign with SHA-1."""
    for openssl in [shutil.which("openssl"), "/usr/bin/openssl"]:
        if openssl and Path(openssl).is_file():
            try:
                return make_certificates(openssl, tmp)
            except (subprocess.CalledProcessError, ValueError, IndexError):
                continue
    raise unittest.SkipTest("no openssl here makes SHA-1 test certificates; a skip is not a pass")


class ThePinAndTheParserHold(unittest.TestCase):
    lines = None
    shipped_sha256 = None

    @classmethod
    def setUpClass(cls):
        for path in (TRUST, UPDATES):
            read(path)
        with tempfile.TemporaryDirectory() as tmp:
            not_after = certificates(tmp)
            late = not_after + datetime.timedelta(days=400)
            for name, body in BODIES.items():
                (Path(tmp) / name).write_bytes(body)
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@LATE@@", str(int(late.timestamp())))
            out = compile_and_run([TRUST, UPDATES], driver, tmp)
            cls.shipped_sha256 = hashlib.sha256((Path(tmp) / "shipped.der").read_bytes()).hexdigest()
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_p1_the_chain_and_the_leaf_alone_pass_under_the_anchor(self):
        self.assertEqual("P1 chain=true leaf=true", self.line("P1"))

    def test_p2_a_same_name_root_with_another_key_is_refused(self):
        self.assertEqual("P2 forged=false other=false otherleaf=false", self.line("P2"))

    def test_p3_an_expired_leaf_is_refused(self):
        self.assertEqual("P3 late=false", self.line("P3"))

    def test_p4_only_one_label_stands_for_the_wildcard(self):
        self.assertEqual("P4 upper=true deeper=false fused=false bare=false empty=false nochain=false", self.line("P4"))

    def test_p5_a_redirect_is_not_followed(self):
        self.assertEqual("P5 redirect=refused", self.line("P5"))

    def test_h1_host_matches(self):
        self.assertEqual("H1 exact=true other=false one=true none=false", self.line("H1"))

    def test_r1_the_shipped_root_is_sonys(self):
        self.assertEqual(ROOT_SHA256, self.shipped_sha256)
        self.assertEqual("R1 cn=SCEI DNAS Root 05", self.line("R1"))

    def test_o1_lookup_outcomes(self):
        self.assertEqual("O1 404=none 403=none empty=none ws=none 500=http500 two=found2 patch=malformed", self.line("O1"))

    def test_x1_hostile_bodies_are_refused_and_both_package_shapes_read(self):
        self.assertEqual("X1 over=nil max=ok doctype=nil entity=nil other=nil versions=01.01,01.02 sizes=1000,2000 "
                         "fw=04.6000,04.6500 name=Synthetic Title", self.line("X1"))


class TheRealFiles(unittest.TestCase):
    def test_real_trust_files_have_no_problems(self):
        self.assertEqual([], trust_problems(real_trust()))

    def test_real_plists_have_no_problems(self):
        self.assertEqual([], ats_problems(real_plists()))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "Empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.trust = real_trust()
        cls.plists = real_plists()

    def edited(self, raw, name, old, new):
        text = raw[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        raw = dict(raw)
        raw[name] = mutated
        return raw

    def assertTrust(self, name, old, new, prefix):
        problems = trust_problems(self.edited(self.trust, name, old, new))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def assertAts(self, old, new, prefix):
        problems = ats_problems(self.edited(self.plists, "Info.plist", old, new))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_imports_uikit(self):
        self.assertTrust("SonyTrust.swift", "import Security", "import Security\nimport UIKit", "imports")

    def test_mutation_basic_policy_dropped(self):
        self.assertTrust("SonyTrust.swift", "SecPolicyCreateBasicX509()", "SecPolicyCreateBasicX509Missing()", "basic")

    def test_mutation_anchors_only_false(self):
        self.assertTrust("SonyTrust.swift", "SecTrustSetAnchorCertificatesOnly(trust, true)",
                         "SecTrustSetAnchorCertificatesOnly(trust, false)", "anchorsonly")

    def test_mutation_refusal_not_cancelled(self):
        self.assertTrust("SonyTrust.swift", "completionHandler(.cancelAuthenticationChallenge, nil)",
                         "completionHandler(.rejectProtectionSpace, nil)", "cancel")

    def test_mutation_second_credential(self):
        self.assertTrust("SonyTrust.swift", "completionHandler(.cancelAuthenticationChallenge, nil)",
                         "completionHandler(.useCredential, URLCredential(trust: space.serverTrust!))", "credential")

    def test_mutation_default_handling_added(self):
        self.assertTrust("SonyTrust.swift", "completionHandler(.cancelAuthenticationChallenge, nil)",
                         "completionHandler(.performDefaultHandling, nil)", "default")

    def test_mutation_redirect_followed(self):
        self.assertTrust("SonyTrust.swift", "        completionHandler(nil)\n", "        completionHandler(request)\n", "redirect")

    def test_mutation_final_url_unchecked(self):
        self.assertTrust("SonyTrust.swift", "response.url?.host == GameUpdates.listHost", "true", "redirect")

    def test_mutation_ssl_policy_swapped_in(self):
        self.assertTrust("SonyTrust.swift", "SecPolicyCreateBasicX509()", "SecPolicyCreateSSL(true, nil)", "ssl")

    def test_mutation_list_url_over_http(self):
        self.assertTrust("GameUpdates.swift", "https://a0.", "http://a0.", "url")

    def test_mutation_log_a_title(self):
        self.assertTrust("GameUpdates.swift", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }",
                         "log")

    def test_mutation_plan_id_in_a_comment(self):
        self.assertTrust("SonyTrust.swift", "import Foundation", "// The pin, GUPD-06.\nimport Foundation", "comment")

    def test_mutation_third_exception_domain(self):
        self.assertAts("\t\t\t<key>b0.ww.np.dl.playstation.net</key>",
                       "\t\t\t<key>c0.ww.np.dl.playstation.net</key>\n\t\t\t<dict>\n"
                       "\t\t\t\t<key>NSExceptionAllowsInsecureHTTPLoads</key>\n\t\t\t\t<true/>\n\t\t\t</dict>\n"
                       "\t\t\t<key>b0.ww.np.dl.playstation.net</key>", "ats")

    def test_mutation_include_subdomains(self):
        self.assertAts("\t\t\t\t<true/>", "\t\t\t\t<true/>\n\t\t\t\t<key>NSIncludesSubdomains</key>\n\t\t\t\t<true/>",
                       "wide")

    def test_mutation_arbitrary_loads(self):
        self.assertAts("\t\t<key>NSExceptionDomains</key>",
                       "\t\t<key>NSAllowsArbitraryLoads</key>\n\t\t<true/>\n\t\t<key>NSExceptionDomains</key>", "wide")


if __name__ == "__main__":
    unittest.main(verbosity=2)
