#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PadHitTest.swift, run on the Mac host over art laid out from the pad's size constants, gives each button a region
of at least 44 pt, lets a finger slide and chord within its cluster and keeps fingers apart; static checks pin the
one touch view's sends, haptic and highlight. It says nothing about how the pad feels under a thumb."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
HITS = APP / "Controllers" / "PadHitTest.swift"
PAD = APP / "Controllers" / "VirtualControllerView.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)

# Written into a temporary directory at run time; {face}, {sw}, {sh}, {mw}, {mh} are the pad's size constants.
DRIVER = r"""
import CoreGraphics
let f: CGFloat = {face}
let shoulder = CGSize(width: {sw}, height: {sh})
let small = CGSize(width: {mw}, height: {mh})
let ps = CGSize(width: 40, height: 40)
func box(_ cx: CGFloat, _ cy: CGFloat, _ s: CGSize) -> CGRect {{
    CGRect(x: cx - s.width / 2, y: cy - s.height / 2, width: s.width, height: s.height)
}}
// A diamond as the pad stacks it: top, then left and right one face apart, then bottom.
func diamond(_ x: CGFloat, _ y: CGFloat, _ top: String, _ left: String, _ right: String, _ bottom: String) -> [String: CGRect] {{
    let s = CGSize(width: f, height: f)
    return [top: box(x + 1.5 * f, y + 0.5 * f, s), left: box(x + 0.5 * f, y + 1.5 * f, s),
            right: box(x + 2.5 * f, y + 1.5 * f, s), bottom: box(x + 1.5 * f, y + 2.5 * f, s)]
}}
let row: CGFloat = 0
let shoulders: [String: CGRect] = [
    "L2": box(shoulder.width / 2, row, shoulder), "L1": box(shoulder.width * 1.5 + 10, row, shoulder),
    "R1": box(600 - shoulder.width * 1.5 - 10, row, shoulder), "R2": box(600 - shoulder.width / 2, row, shoulder)]
let dpadArt = diamond(0, 40, "up", "left", "right", "down")
let faceArt = diamond(600 - 3 * f, 40, "triangle", "square", "circle", "cross")
let middle: [String: CGRect] = ["select": box(260, 300, small), "PS": box(300, 300, ps), "start": box(340, 300, small)]
var t = PadTouchTracker<Int, String>()
t.clusters = [PadCluster(rule: .eightWay, art: dpadArt), PadCluster(rule: .chord, art: faceArt),
              PadCluster(rule: .nearest, art: shoulders), PadCluster(rule: .nearest, art: middle)]
func c(_ r: CGRect) -> CGPoint {{ CGPoint(x: r.midX, y: r.midY) }}
func s(_ set: Set<String>) -> String {{ set.isEmpty ? "-" : set.sorted().joined(separator: ",") }}
func ch(_ x: PadChange<String>) -> String {{ "+" + s(x.pressed) + " -" + s(x.released) }}

for (name, size) in [("face", CGSize(width: f, height: f)), ("shoulder", shoulder), ("small", small), ("ps", ps)] {{
    let art = CGRect(origin: .zero, size: size)
    let r = PadHit.region(art)
    let larger = r.width * r.height > art.width * art.height
    print("H1 \(name) contains=\(r.contains(art) ? 1 : 0) min44=\(r.width >= 44 && r.height >= 44 ? 1 : 0) larger=\(larger ? 1 : 0)")
}}
let cross = faceArt["cross"]!, circle = faceArt["circle"]!
t.down(1, at: CGPoint(x: cross.midX, y: cross.maxY + 5)); print("H1edge \(s(t.held))"); t.lift(1)

t.down(1, at: c(cross)); let down = t.held
t.move(1, to: c(circle)); print("H2 \(s(down)) \(s(t.held)) \(ch(PadHit.change(from: down, to: t.held)))"); t.lift(1)

let corner = CGPoint(x: (cross.midX + circle.midX) / 2, y: (cross.midY + circle.midY) / 2)
t.down(1, at: corner); let both = t.held; t.lift(1)
t.down(1, at: CGPoint(x: corner.x + 10, y: corner.y - 10)); print("H3 \(s(both)) \(s(t.held))"); t.lift(1)

let dc = CGPoint(x: 1.5 * f, y: 40 + 1.5 * f)
t.down(2, at: CGPoint(x: dc.x + f, y: dc.y - f)); let diag = t.held
t.move(2, to: CGPoint(x: dc.x + f * 1.2, y: dc.y - f * 1.2 * 0.176)); let ten = t.held
t.move(2, to: dc); print("H4 \(s(diag)) \(s(ten)) \(s(t.held))"); t.lift(2)

t.down(3, at: c(cross)); t.move(3, to: c(shoulders["R1"]!)); let wandered = t.held; t.lift(3)
t.down(3, at: CGPoint(x: 300, y: 500)); t.move(3, to: c(cross)); print("H5 \(s(wandered)) \(s(t.held))"); t.lift(3)

t.down(4, at: c(cross)); t.down(5, at: c(dpadArt["up"]!)); let union = t.held
t.lift(4); print("H6 \(s(union)) \(s(t.held))"); t.liftAll()

t.down(6, at: c(cross)); let before = t.held; t.move(6, to: CGPoint(x: cross.midX + 4, y: cross.midY + 3))
print("H7 empty=\(PadHit.change(from: before, to: t.held).isEmpty ? 1 : 0)"); t.lift(6)

t.down(7, at: c(dpadArt["left"]!)); let held = t.held; t.liftAll(); print("H8 \(ch(PadHit.change(from: held, to: t.held)))")

let l2 = shoulders["L2"]!, l1 = shoulders["L1"]!
t.down(8, at: CGPoint(x: (l2.maxX + l1.minX) / 2 + 2, y: row)); print("H9 \(s(t.held))")
"""

EXPECTED = {
    "H1": None,  # checked per size below
    "H1edge": "H1edge cross",
    "H2": "H2 cross circle +circle -cross",
    "H3": "H3 circle,cross circle",
    "H4": "H4 right,up right -",
    "H5": "H5 - -",
    "H6": "H6 cross,up up",
    "H7": "H7 empty=1",
    "H8": "H8 +- -left",
    "H9": "H9 L1",
}


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def sizes(pad):
    """The pad's art sizes, read from its own constants."""
    code = without_comments(pad)
    face = re.search(r"static\s+let\s+face:\s*CGFloat\s*=\s*(\d+)", code)
    shoulder = re.search(r"static\s+let\s+shoulder\s*=\s*CGSize\(width:\s*(\d+),\s*height:\s*(\d+)\)", code)
    small = re.search(r"static\s+let\s+small\s*=\s*CGSize\(width:\s*(\d+),\s*height:\s*(\d+)\)", code)
    if not (face and shoulder and small):
        raise AssertionError("VirtualControllerView.swift no longer declares face, shoulder and small")
    return dict(face=face.group(1), sw=shoulder.group(1), sh=shoulder.group(2), mw=small.group(1), mh=small.group(2))


def run_host(hits_source, pad):
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "PadHitTest.swift"
        source.write_text(hits_source, encoding="utf-8")
        run = Path(tmp) / "run"
        run.mkdir()
        out = compile_and_run([source], DRIVER.format(**sizes(pad)), run)
    return [line for line in out.splitlines() if line.strip()]


def host_problems(lines):
    problems = []
    h1 = [line for line in lines if line.startswith("H1 ")]
    if len(h1) != 4:
        problems.append("H1: expected four art sizes, got {0}".format(h1))
    for line in h1:
        if line.split(" ", 2)[2] != "contains=1 min44=1 larger=1":
            problems.append("H1: {0}".format(line))
    keyed = {line.split(" ", 1)[0]: line for line in lines}
    for key, expected in sorted(EXPECTED.items()):
        if expected is not None and keyed.get(key) != expected:
            problems.append("{0}: got {1!r}, expected {2!r}".format(key, keyed.get(key), expected))
    return problems


def text_problems(hits, pad):
    problems = []
    hits_code = without_comments(hits)
    pad_code = without_comments(pad)

    def block(text, pattern):
        match = re.search(pattern, text)
        return text[slice(*brace_span(text, match.end()))] if match else ""

    growth = re.search(r"static\s+let\s+growth:\s*CGFloat\s*=\s*([\d.]+)", hits_code)
    side = re.search(r"static\s+let\s+minimumSide:\s*CGFloat\s*=\s*([\d.]+)", hits_code)
    region = block(hits_code, r"static\s+func\s+region\(")
    if not growth or float(growth.group(1)) <= 1.0:
        problems.append("region: PadHit.growth is not above 1, so a region is no larger than its art")
    if not side or float(side.group(1)) < 44:
        problems.append("region: PadHit.minimumSide is below 44 pt")
    if "* growth" not in region or "minimumSide" not in region:
        problems.append("region: PadHit.region( does not grow the art by growth with a minimumSide floor")

    for name, label in (("touchesBegan", "began"), ("touchesMoved", "moved")):
        hand_off = r"PadTouches\.shared\.touch\(\s*{0}:\s*touches\.map".format(label)
        if not re.search(hand_off, block(pad_code, r"override\s+func\s+{0}\b".format(name))):
            problems.append("slide: PadTouchView's {0} does not hand its touches to PadTouches.shared.touch({1}:".format(name, label))
    if "holding[finger] = PadHit.buttons(at: point" not in block(hits_code, r"mutating\s+func\s+move\("):
        problems.append("slide: the tracker's move does not re-hit the finger on every call")

    apply = block(pad_code, r"private\s+func\s+apply\(")
    pressed = apply.find("if !change.pressed.isEmpty {")
    if pad_code.count("impactOccurred(") != 1 or pressed == -1 \
            or "impactOccurred(" not in apply[slice(*brace_span(apply, pressed))]:
        problems.append("haptic: the pad's one impactOccurred( is not inside `if !change.pressed.isEmpty {` in apply")
    began = block(pad_code, r"override\s+func\s+touchesBegan\b")
    if "PadTouches.shared.prepare()" not in began or began.find("prepare()") > began.find("touch(began:"):
        problems.append("haptic: touchesBegan does not prepare the generator before the touch is applied")

    if not re.match(r"\{\s*guard\s+!change\.isEmpty\s+else\s*\{\s*return\s*\}", apply):
        problems.append("send: apply does not return first on an empty change")
    if apply.count("PadSnapshot.shared.send(source: .touch)") != 1:
        problems.append("send: apply does not send exactly one snapshot per change")

    button = block(pad_code, r"struct\s+PadButton\b")
    if "let pressed = PadTouches.shared.held.contains(control.button)" not in button \
            or not re.search(r"\(pressed\s*\?", button):
        problems.append("highlight: PadButton does not draw a highlight from PadTouches.shared.held")

    if "isMultipleTouchEnabled = true" not in block(pad_code, r"override\s+init\(frame:"):
        problems.append("surface: PadTouchView is not multi-touch")
    if ".background { PadTouchSurface() }" not in block(pad_code, r"struct\s+VirtualControllerView\b"):
        problems.append("surface: the pad does not put PadTouchSurface() in its background")
    if ".allowsHitTesting(false)" not in button or ".gesture(" in button:
        problems.append("surface: PadButton takes touches of its own")

    imports = set(IMPORT.findall(hits_code))
    if not imports or imports - {"Foundation", "CoreGraphics"}:
        problems.append("host: PadHitTest.swift imports {0}; only Foundation and CoreGraphics run on the host".format(sorted(imports)))
    return problems


class TheHitRulesHoldOnTheHost(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        cls.lines = run_host(read(HITS), read(PAD))

    def test_h1_to_h9(self):
        self.assertEqual([], host_problems(self.lines), "\n".join(self.lines))


class TheHostMutationsAreCaught(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.hits, cls.pad = read(HITS), read(PAD)

    def edited(self, old, new):
        self.assertIn(old, self.hits)
        changed = self.hits.replace(old, new, 1)
        self.assertNotEqual(self.hits, changed)
        return changed

    def assert_names(self, problems, check):
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_region_no_growth(self):
        source = self.edited("static let growth: CGFloat = 1.3", "static let growth: CGFloat = 1.0")
        self.assert_names(host_problems(run_host(source, self.pad)), "H1")

    def test_mutation_slide_a_finger_keeps_its_first_buttons(self):
        source = self.edited("holding[finger] = PadHit.buttons(at: point",
                             "holding[finger] = holding[finger] ?? PadHit.buttons(at: point")
        self.assert_names(host_problems(run_host(source, self.pad)), "H2")


class TheWiringHolds(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        self.assertEqual([], text_problems(read(HITS), read(PAD)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")
        with self.assertRaises(AssertionError):
            sizes("struct Nothing {}")


class TheWiringGuardFailsOnAMutation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.hits = without_comments(read(HITS))
        cls.pad = without_comments(read(PAD))

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, problems, check):
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_region_floor_below_44(self):
        hits = self.edited(self.hits, "static let minimumSide: CGFloat = 44", "static let minimumSide: CGFloat = 30")
        self.assert_names(text_problems(hits, self.pad), "region")

    def test_mutation_slide_moves_ignored(self):
        start, end = brace_span(self.pad, self.pad.index("override func touchesMoved"))
        self.assert_names(text_problems(self.hits, self.edited(self.pad, self.pad[start:end], "{ }")), "slide")

    def test_mutation_haptic_on_release(self):
        pad = self.edited(self.pad, "if !change.pressed.isEmpty {", "if !change.released.isEmpty {")
        self.assert_names(text_problems(self.hits, pad), "haptic")

    def test_mutation_haptic_on_every_move(self):
        pad = self.edited(self.pad, "override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {",
                          "override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {\n"
                          "        UIImpactFeedbackGenerator(style: .light).impactOccurred()")
        self.assert_names(text_problems(self.hits, pad), "haptic")

    def test_mutation_send_on_every_touch_event(self):
        pad = self.edited(self.pad, "guard !change.isEmpty else { return }", "")
        self.assert_names(text_problems(self.hits, pad), "send")

    def test_mutation_highlight_never_drawn(self):
        pad = self.edited(self.pad, "let pressed = PadTouches.shared.held.contains(control.button)", "let pressed = false")
        self.assert_names(text_problems(self.hits, pad), "highlight")

    def test_mutation_surface_single_touch(self):
        pad = self.edited(self.pad, "isMultipleTouchEnabled = true\n        backgroundColor", "isMultipleTouchEnabled = false\n        backgroundColor")
        self.assert_names(text_problems(self.hits, pad), "surface")

    def test_mutation_host_imports_uikit(self):
        hits = self.edited(self.hits, "import Foundation", "import Foundation\nimport UIKit")
        self.assert_names(text_problems(hits, self.pad), "host")


if __name__ == "__main__":
    unittest.main(verbosity=2)
