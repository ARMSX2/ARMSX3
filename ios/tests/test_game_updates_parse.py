#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""VerXml.parse in GameUpdates.swift, run alone on the Mac host over built bodies, reads Sony's version list as untrusted
input: any package outside the allowed shapes, a DOCTYPE, an entity or an oversized body refuses the whole list.
Truncation and byte-flip fuzzing must never trap; it says nothing about Sony's live answers or a device."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments
from update_fixtures import (OMIT, billion_laughs, fuzz_list, oversized, package, package_url, refused_variants,
                             ver_xml)

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
UPDATES = APP / "Updates" / "GameUpdates.swift"

ID = "ABCD12345"
SHA = "ab" * 20
MAX_BODY = 256 * 1024
LONG_TITLE = "T" * 300


def bodies():
    """{file name: body bytes}, every one synthetic."""
    good = package("01.01", 1000, SHA, firmware="04.6000")
    out = {
        "nested.bin": ver_xml(ID, [good, package("01.02", 2000, SHA)], title="Synthetic Title"),
        "self.bin": ver_xml(ID, [good, package("01.02", 2000, SHA)], self_closing=True),
        "mixed.bin": ver_xml(ID, [package("01.01", 1000, "AbCdEf" + "0" * 34)]),
        "https.bin": ver_xml(ID, [package("01.01", 1000, SHA, url=package_url(ID, "01.01").replace("http:", "https:"))]),
        "nofw.bin": ver_xml(ID, [package("01.01", 1000, SHA, firmware=OMIT)]),
        "max.bin": ver_xml(ID, [package("01.01", 32 << 30, SHA)]),
        "nopkg.bin": ver_xml(ID, []),
        "bidi.bin": ver_xml(ID, [good], title="  Game‮Name\t  "),
        "long.bin": ver_xml(ID, [good], title=LONG_TITLE),
        "laughs.bin": billion_laughs(),
        "over.bin": oversized(ver_xml(ID, [good]), MAX_BODY + 1),
        "doctype.bin": ver_xml(ID, [good]).replace(b"<tag ", b"<!DOCTYPE x><tag "),
        "entity.bin": ver_xml(ID, [good]).replace(b"<tag ", b"<!-- <!ENTITY x 'y'> --><tag "),
        "utf16.bin": wide(ver_xml(ID, [good]), "utf-16"),
        "utf16be.bin": wide(ver_xml(ID, [good]), "utf-16-be"),
        "latin.bin": ver_xml(ID, [good]).replace(b'encoding="UTF-8"', b'encoding="ISO-8859-1"'),
        "fuzz.bin": fuzz_list(ID),
    }
    for name, body in refused_variants(ID).items():
        out["r-" + name + ".bin"] = body
    return out


def wide(body, codec):
    """body without its declaration, with an internal DOCTYPE before the root, in another encoding."""
    text = body.decode("utf-8").split("?>", 1)[1].lstrip()
    return text.replace("<titlepatch", '<!DOCTYPE titlepatch [<!ENTITY e "x">]><titlepatch', 1).encode(codec)


def refused_names():
    return sorted(refused_variants(ID))


DRIVER = r"""
import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
let id = "ABCD12345"
func body(_ name: String) -> Data { try! Data(contentsOf: dir.appendingPathComponent(name)) }
func parsed(_ name: String) -> TitlePatch? { VerXml.parse(body(name), titleID: id) }
func shape(_ name: String) -> String {
    guard let patch = parsed(name) else { return "nil" }
    return "ok:\(patch.packages.count):\(patch.name ?? "-")"
}

let mixed = parsed("mixed.bin")?.packages.first?.sha1 ?? "nil"
let fw = parsed("nofw.bin").map { $0.packages.first?.firmware ?? "nil" } ?? "refused"
let biggest = parsed("max.bin")?.packages.first.map { String($0.size) } ?? "nil"
let scheme = parsed("https.bin")?.packages.first?.url.scheme ?? "nil"
print("G1 nested=\(shape("nested.bin")) self=\(shape("self.bin")) sha=\(mixed) https=\(shape("https.bin")) scheme=\(scheme) fw=\(fw) max=\(biggest)")

let refused = @@REFUSED@@
let accepted = refused.filter { parsed("r-" + $0 + ".bin") != nil }
print("R1 checked=\(refused.count) accepted=\(accepted.isEmpty ? "none" : accepted.joined(separator: ","))")
print("R2 laughs=\(shape("laughs.bin")) over=\(shape("over.bin")) doctype=\(shape("doctype.bin")) entity=\(shape("entity.bin")) done=1")
print("R3 utf16=\(shape("utf16.bin")) utf16be=\(shape("utf16be.bin")) latin=\(shape("latin.bin"))")

func word(_ outcome: LookupOutcome) -> String {
    switch outcome {
    case .none: return "none"
    case .found(let patch): return "found\(patch.packages.count)"
    case .failed(let failure): return "\(failure)"
    }
}
let nosha = word(GameUpdates.outcome(status: 200, body: body("r-nosha.bin"), titleID: id))
let nourl = word(GameUpdates.outcome(status: 200, body: body("r-nourl.bin"), titleID: id))
print("E1 nopkg=\(word(GameUpdates.outcome(status: 200, body: body("nopkg.bin"), titleID: id))) nosha=\(nosha) nourl=\(nourl)")

let long = parsed("long.bin")?.name ?? "nil"
print("T1 direct=[\(VerXml.displayTitle("  Game\u{202E}Name\u{0007}  ") ?? "nil")] parsed=[\(parsed("bidi.bin")?.name ?? "nil")] long=\(long.count) blank=\(VerXml.displayTitle(" \u{200E} ") ?? "nil")")

func fwPackage(_ fw: String?) -> TitlePackage {
    TitlePackage(version: "01.01", size: 1000, sha1: String(repeating: "a", count: 40),
                 url: URL(string: "http://b0.ww.np.dl.playstation.net/tppkg/np/ABCD12345/a.pkg")!, firmware: fw)
}
let hundredths = ["04.6000", "4.93", "4.9", "x", ""].map { "\(GameUpdates.firmwareHundredths($0).map(String.init) ?? "nil")" }
let needs = [("04.6000", "4.93"), ("04.9500", "4.93"), ("x", "4.93"), ("04.9500", "x")].map {
    GameUpdates.needsFirmware(fwPackage($0.0), installed: $0.1) ?? "nil"
}
print("F1 hundredths=\(hundredths.joined(separator: ",")) needs=\(needs.joined(separator: ",")) none=\(GameUpdates.needsFirmware(fwPackage(nil), installed: "4.93") ?? "nil")")

let ids = [" abcd12345 ", "ABCD1234", "ABCDE1234", "\u{00C0}BCD12345", "ABCD\u{FF11}2345", "\u{FB00}CD12345"]
print("I1 " + ids.map { GameUpdates.titleID($0) ?? "nil" }.joined(separator: ","))

func ruleBreaks(_ patch: TitlePatch) -> Bool {
    func digit(_ b: UInt8) -> Bool { (0x30...0x39).contains(b) }
    if patch.packages.count > 64 || patch.titleID != id { return true }
    if let name = patch.name {
        if name.isEmpty || name.count > 128 { return true }
        if name.unicodeScalars.contains(where: { [.control, .format].contains($0.properties.generalCategory) }) { return true }
    }
    for (index, p) in patch.packages.enumerated() {
        let v = Array(p.version.utf8)
        guard v.count == 4 || v.count == 5, v[v.count - 3] == 0x2E,
              v.enumerated().allSatisfy({ $0.offset == v.count - 3 || digit($0.element) }) else { return true }
        if index > 0, GameUpdates.compare(patch.packages[index - 1].version, p.version) >= 0 { return true }
        guard p.size > 0x20, p.size <= 32 << 30 else { return true }
        guard p.sha1.utf8.count == 40, p.sha1.utf8.allSatisfy({ digit($0) || (0x61...0x66).contains($0) }) else { return true }
        guard let c = URLComponents(url: p.url, resolvingAgainstBaseURL: false),
              c.scheme == "http" || c.scheme == "https", c.host == "b0.ww.np.dl.playstation.net",
              c.user == nil, c.password == nil, c.port == nil, c.query == nil, c.fragment == nil,
              c.path.hasPrefix("/tppkg/np/ABCD12345/"), c.path.hasSuffix(".pkg"),
              !c.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else { return true }
    }
    return false
}

let fuzz = [UInt8](body("fuzz.bin"))
var violations = 0
let whole = parsed("fuzz.bin")?.packages.count ?? -1
var truncations = 0
for n in 0 ... fuzz.count {
    if let patch = VerXml.parse(Data(fuzz[0 ..< n]), titleID: id), ruleBreaks(patch) { violations += 1 }
    truncations += 1
}
var state: UInt64 = 0x17_02_2026
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
var flips = 0
for _ in 0 ..< 2000 {
    var copy = fuzz
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    if let patch = VerXml.parse(Data(copy), titleID: id), ruleBreaks(patch) { violations += 1 }
    flips += 1
}
print("FUZZWHOLE \(whole)")
print("FUZZ truncations=\(truncations) flips=\(flips) violations=\(violations) done=1")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def parse_problems(raw):
    code = without_comments(raw)
    problems = []
    parse = body_of(code, r"static\s+func\s+parse\s*\(") or ""
    built = parse.find("XMLParser(")
    for token in ("maxBody", "isUTF8Document(", "<!DOCTYPE", "<!ENTITY"):
        at = parse.find(token)
        if built == -1 or at == -1 or at > built:
            problems.append("early: VerXml.parse does not check {0} before constructing XMLParser".format(token))
    if not re.search(r"\.shouldResolveExternalEntities\s*=\s*false", code):
        problems.append("entities: VerXml.parse does not turn external entities off")
    check = body_of(code, r"static\s+func\s+isPackageURL\s*\(") or ""
    if not re.search(r"\.host\s*==\s*packageHost\b", check):
        problems.append("host: isPackageURL does not compare the host with packageHost")
    reader = body_of(code, r"class\s+Reader\b") or ""
    if "isPackageURL(" not in reader:
        problems.append("url: the package reader does not call isPackageURL(")
    if re.search(r"\bstatic\s+var\b", code):
        problems.append("shared: GameUpdates.swift declares a static var")
    return sorted(problems)


class TheRulesHold(unittest.TestCase):
    lines = None
    fuzz_size = None

    @classmethod
    def setUpClass(cls):
        read(UPDATES)
        with tempfile.TemporaryDirectory() as tmp:
            for name, body in bodies().items():
                (Path(tmp) / name).write_bytes(body)
            cls.fuzz_size = len(bodies()["fuzz.bin"])
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@REFUSED@@", json.dumps(refused_names()))
            out = compile_and_run([UPDATES], driver, tmp)
        cls.lines = [line for line in out.splitlines() if line.strip()]

    def line(self, key):
        found = [line for line in self.lines if line.split(" ", 1)[0] == key]
        self.assertEqual(1, len(found), "{0} in {1}".format(key, self.lines))
        return found[0]

    def test_g1_measured_shapes_are_read(self):
        self.assertEqual("G1 nested=ok:2:Synthetic Title self=ok:2:- sha=abcdef" + "0" * 34 +
                         " https=ok:1:- scheme=http fw=nil max={0}".format(32 << 30), self.line("G1"))

    def test_r1_each_bad_field_refuses_the_whole_list(self):
        self.assertEqual("R1 checked={0} accepted=none".format(len(refused_names())), self.line("R1"))

    def test_r2_entity_and_oversized_bodies_are_refused(self):
        self.assertEqual("R2 laughs=nil over=nil doctype=nil entity=nil done=1", self.line("R2"))

    def test_r3_a_body_in_another_encoding_is_refused(self):
        self.assertEqual("R3 utf16=nil utf16be=nil latin=nil", self.line("R3"))

    def test_e1_empty_and_malformed_outcomes(self):
        self.assertEqual("E1 nopkg=none nosha=malformed nourl=malformed", self.line("E1"))

    def test_t1_title_is_display_text_only(self):
        self.assertEqual("T1 direct=[GameName] parsed=[GameName] long=128 blank=nil", self.line("T1"))

    def test_f1_firmware(self):
        self.assertEqual("F1 hundredths=460,493,490,nil,nil needs=nil,4.95,nil,nil none=nil", self.line("F1"))

    def test_i1_title_ids_are_ascii(self):
        self.assertEqual("I1 ABCD12345,nil,nil,nil,nil,nil", self.line("I1"))

    def test_z1_fuzz_never_traps_or_breaks_a_rule(self):
        self.assertTrue(2000 <= self.fuzz_size <= 4096, self.fuzz_size)
        self.assertNotEqual("FUZZWHOLE -1", self.line("FUZZWHOLE"))
        self.assertEqual("FUZZ truncations={0} flips=2000 violations=0 done=1".format(self.fuzz_size + 1),
                         self.line("FUZZ"))


class TheRealFiles(unittest.TestCase):
    def test_real_file_has_no_problems(self):
        self.assertEqual([], parse_problems(read(UPDATES)))

    def test_every_refused_variant_differs_from_the_good_list(self):
        good = ver_xml(ID, [package("01.01", 1000, SHA)])
        for name, body in refused_variants(ID).items():
            self.assertNotEqual(good, body, name)

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
        cls.raw = read(UPDATES)

    def assertFlagged(self, old, new, prefix):
        self.assertIn(old, self.raw)
        mutated = self.raw.replace(old, new, 1)
        self.assertNotEqual(self.raw, mutated)
        problems = parse_problems(mutated)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_entity_marker_dropped(self):
        self.assertFlagged('Data("<!ENTITY".utf8)', 'Data("<!NOTHING".utf8)', "early")

    def test_mutation_encoding_unchecked(self):
        self.assertFlagged("isUTF8Document(body),", "true,", "early")

    def test_mutation_external_entities_on(self):
        self.assertFlagged("shouldResolveExternalEntities = false", "shouldResolveExternalEntities = true", "entities")

    def test_mutation_host_against_the_list_host(self):
        self.assertFlagged(".host == packageHost", ".host == listHost", "host")

    def test_mutation_reader_skips_the_url_rule(self):
        self.assertFlagged("GameUpdates.isPackageURL(", "GameUpdates.isAnyURL(", "url")

    def test_mutation_shared_state(self):
        self.assertFlagged("static let maxBody", "static var maxBody", "shared")


if __name__ == "__main__":
    unittest.main(verbosity=2)
