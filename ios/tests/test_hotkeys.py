#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""HotkeyTable.swift, run alone on the Mac host, holds the stored, rebindable hotkey chords with their defaults,
normalisation and conflict rules; static checks cover the router's capture and the Hotkeys pane.
It presses nothing on a real controller."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import body
from source_helpers import brace_block
from source_helpers import without_comments

ROOT = Path(__file__).resolve().parents[2]
HOTKEYS = ROOT / "ios" / "app" / "Controllers" / "HotkeyTable.swift"
ROUTER = ROOT / "ios" / "app" / "Controllers" / "MenuControllerInputRouter.swift"
PANE = ROOT / "ios" / "app" / "Controllers" / "HotkeysView.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
WHERE_STORED = re.compile(r"where\s+settings\.controllerMacroBinding\(\s*for:\s*action\s*\)")
TABLE_ACTIONS = re.compile(r"static\s+let\s+actions\s*=\s*\[([^\]]*)\]")
NO_SHORTCUT = ("quickMenu", "pressureModifier")


def enum_cases(text, name):
    """The case names declared directly in `enum <name>`'s body (comma lists included)."""
    found = re.search(r"enum\s+" + name + r"\b[^{]*\{", text)
    if found is None:
        return set()
    block = brace_block(text, found.end() - 1)[0]
    return {c.strip() for line in re.findall(r"^[ \t]*case[ \t]+([\w \t,]+)$", block, re.M) for c in line.split(",")}

# Written at run time into a temporary directory, never committed.
DRIVER = r"""
import Foundation

func s(_ value: String?) -> String { value ?? "nil" }
func d(_ result: HotkeyTable.AssignResult) -> String {
    switch result {
    case .assigned: return "assigned"
    case .conflict(let holder): return "conflict:" + holder
    case .refused: return "refused"
    }
}
func table(_ result: HotkeyTable.AssignResult) -> HotkeyTable? {
    if case .assigned(let next) = result { return next }
    return nil
}
func all(_ t: HotkeyTable) -> String {
    HotkeyTable.actions.map { "\($0)=\(s(t.chord(for: $0)))" }.joined(separator: " ")
}

let fresh = HotkeyTable(stored: [:])
print("K1 " + all(fresh))

let inputs = ["select+pause", "pause+r2", "start+start", "start+pause", "l1", "foo+r1", "", "l1++r1", "select+r1+r2"]
print("K2 " + inputs.map { "[\($0)]=\(s(HotkeyTable.normalised($0)))" }.joined(separator: " "))

let titled = ["select+start", "start+dpadUp", "l3+cross", "select+pause", "r2+dpadRight"].map { HotkeyTable.title($0) }
print("K3 " + (titled + [HotkeyTable.title(nil), HotkeyTable.title("l1")]).joined(separator: "|"))
print("K3t " + HotkeyTable.actions.map { HotkeyTable.actionTitle($0) }.joined(separator: "|"))
print("K3c " + HotkeyTable.actions.compactMap { a in HotkeyTable.caption(a).map { "\(a)=\($0)" } }.joined(separator: "|"))

let replaced = table(fresh.assigning("start+r2", to: "cycleSaveSlot", replacing: true))!
print("K4 ask=\(d(fresh.assigning("start+r2", to: "cycleSaveSlot", replacing: false)))"
    + " cycle=\(s(replaced.chord(for: "cycleSaveSlot"))) save=\(s(replaced.chord(for: "saveGameState")))"
    + " saveRaw=[\(replaced.stored["saveGameState"] ?? "missing")]"
    + " menuTakes=\(d(fresh.assigning("start+r2", to: "quickMenu", replacing: false)))")

let refusals: [(String, String, Bool)] = [
    ("select+start", "saveGameState", false), ("start+select", "cycleSaveSlot", false),
    ("select+pause", "closeGame", false), ("pause+select", "resetGame", true),
    ("l1", "resolutionUp", false), ("select+r1", "noSuchAction", false),
]
let moved = table(fresh.assigning("l3+r3", to: "quickMenu", replacing: false))!
print("K5 " + refusals.map { "\($0.1)=\(d(fresh.assigning($0.0, to: $0.1, replacing: $0.2)))" }.joined(separator: " ")
    + " moved=\(d(moved.assigning("r3+l3", to: "closeGame", replacing: true)))"
    + " freed=\(d(moved.assigning("select+start", to: "closeGame", replacing: false)))")

let cleared = fresh.clearing("saveGameState")!
print("K6 quickMenu=\(fresh.clearing("quickMenu") == nil ? "nil" : "table")"
    + " saveRaw=[\(cleared.stored["saveGameState"] ?? "missing")] save=\(s(cleared.chord(for: "saveGameState")))"
    + " title=\(HotkeyTable.title(cleared.chord(for: "saveGameState")))")

var busy = table(fresh.assigning("l1+r1", to: "resetGame", replacing: false))!
busy = busy.clearing("loadGameState")!
busy = table(busy.assigning("l3+r3", to: "quickMenu", replacing: false))!
let reset = HotkeyTable.resetTable
print("K7 changed=\(busy != reset) empty=\(reset.stored.isEmpty) " + all(reset))

print("K8 ask=\(d(fresh.assigning("r2+start", to: "cycleSaveSlot", replacing: false)))"
    + " holder=\(s(fresh.holder(of: "r2+start", except: "cycleSaveSlot")))"
    + " self=\(s(fresh.holder(of: "start+r2", except: "saveGameState")))")

print("K9 " + [11, 0, 1, 8, 9, 10, -1, 42].map { "\($0)>\(HotkeyTable.nextSaveSlot(after: $0))" }.joined(separator: " "))

let steps: [(Int, Int)] = [(100, 25), (100, -25), (775, 25), (800, 25), (50, -25), (25, -25), (0, 25), (900, -25)]
print("K10 " + steps.map { "\($0.0)\($0.1 > 0 ? "+" : "")\($0.1)=\(HotkeyTable.steppedScale($0.0, by: $0.1))" }.joined(separator: " "))

let once = table(fresh.assigning("select+r1", to: "saveGameState", replacing: false))!
let twice = table(once.assigning("select+r1", to: "saveGameState", replacing: false))!
let tampered = HotkeyTable(stored: ["quickMenu": "", "loadGameState": "garbage+r9", "closeGame": "pause+l1"])
print("K11 missing=\(s(fresh.chord(for: "quickMenu")))"
    + " stored=\(once.stored.map { "\($0.key):\($0.value)" }.sorted().joined(separator: ","))"
    + " same=\(twice.stored == once.stored) roundtrip=\(HotkeyTable(stored: once.stored) == once)"
    + " empty=\(s(HotkeyTable(stored: ["saveGameState": ""]).chord(for: "saveGameState")))"
    + " malformed=\(s(tampered.chord(for: "loadGameState"))) pause=\(s(tampered.chord(for: "closeGame")))"
    + " menuTampered=\(s(tampered.chord(for: "quickMenu")))")
"""

ALL_DEFAULT = ("quickMenu=select+start saveGameState=start+r2 loadGameState=start+l2 cycleSaveSlot=nil"
               " saveStateAndExit=nil togglePerformanceOverlay=nil resolutionUp=nil resolutionDown=nil"
               " closeGame=nil resetGame=nil pressureModifier=nil screenshot=nil toggleRecording=nil")
EXPECTED = {
    "K1": "K1 " + ALL_DEFAULT,
    "K2": ("K2 [select+pause]=select+start [pause+r2]=start+r2 [start+start]=nil [start+pause]=nil [l1]=nil"
           " [foo+r1]=nil []=nil [l1++r1]=nil [select+r1+r2]=nil"),
    "K3": "K3 Select + Start|Start + Up|L3 + Cross|Select + Start|R2 + Right|None|None",
    "K3t": ("K3t Quick Menu|Quick Save|Quick Load|Cycle Save Slot|Save State and Exit|Performance Overlay"
            "|Resolution Up|Resolution Down|Close Game|Reset Game|Pressure Modifier|Screenshot|Record Video"),
    "K3c": "K3c loadGameState=Asks before loading.|pressureModifier=Works while held.",
    "K4": "K4 ask=conflict:saveGameState cycle=start+r2 save=nil saveRaw=[] menuTakes=conflict:saveGameState",
    "K5": ("K5 saveGameState=refused cycleSaveSlot=refused closeGame=refused resetGame=refused"
           " resolutionUp=refused noSuchAction=refused moved=refused freed=assigned"),
    "K6": "K6 quickMenu=nil saveRaw=[] save=nil title=None",
    "K7": "K7 changed=true empty=true " + ALL_DEFAULT,
    "K8": "K8 ask=conflict:saveGameState holder=saveGameState self=nil",
    "K9": "K9 11>0 0>1 1>2 8>9 9>11 10>11 -1>11 42>11",
    "K10": "K10 100+25=125 100-25=75 775+25=800 800+25=800 50-25=25 25-25=25 0+25=25 900-25=800",
    "K11": ("K11 missing=select+start stored=saveGameState:select+r1 same=true roundtrip=true empty=nil"
            " malformed=nil pause=start+l1 menuTampered=select+start"),
}


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def hotkey_problems(table_text, router_text):
    problems = []
    table = without_comments(table_text)
    imports = set(IMPORT.findall(table))
    if imports != {"Foundation"}:
        problems.append("host: HotkeyTable.swift imports {0}, not Foundation alone".format(sorted(imports)))
    for match in LOGGING.finditer(table):
        problems.append("log: HotkeyTable.swift logs through {0!r}".format(match.group(0)))

    macros = body(without_comments(router_text), r"private\s+func\s+updateEmulationControllerMacros\s*\(")
    if macros is None:
        problems.append("router: the router defines no updateEmulationControllerMacros(")
    else:
        if not WHERE_STORED.search(macros):
            problems.append("router: the macro loop does not match on settings.controllerMacroBinding(for: action)")
        if "defaultBinding" in macros:
            problems.append("router: the macro loop names defaultBinding instead of the stored chord")

    router = without_comments(router_text)
    listed = TABLE_ACTIONS.search(table)
    actions = re.findall(r'"(\w+)"', listed.group(1)) if listed else []
    declared = enum_cases(router, "ControllerMacroAction")
    shortcuts = enum_cases(router, "EmulationControllerShortcut")
    mapping = body(router, r"var\s+emulationShortcut\s*:\s*EmulationControllerShortcut\?")
    arms = dict(re.findall(r"case\s+\.(\w+)\s*:\s*\.?(\w+)", mapping or ""))
    if not actions:
        problems.append("cases: HotkeyTable.swift declares no `static let actions` list")
    targets = []
    for action in actions:
        if action not in declared:
            problems.append("cases: {0} is not a ControllerMacroAction case".format(action))
        target = arms.get(action)
        if action in NO_SHORTCUT:
            if target != "nil":
                problems.append("cases: {0} publishes {1}, not nil".format(action, target))
        elif target is None or target == "nil" or target not in shortcuts:
            problems.append("cases: {0} maps to no declared EmulationControllerShortcut case".format(action))
        else:
            targets.append(target)
    if len(targets) != len(set(targets)):
        problems.append("cases: two actions publish the same shortcut: {0}".format(sorted(targets)))

    # record
    defaults = re.search(r"static\s+let\s+defaults\s*=\s*\[([^\]]*)\]", table)
    if actions[-2:] != ["screenshot", "toggleRecording"] or defaults is None or "toggleRecording" in defaults.group(1):
        problems.append("record: HotkeyTable.actions does not end screenshot, toggleRecording, or a default chord binds it")
    raw = router_text.split("\n")
    code = [i for i, line in enumerate(raw) if "toggleRecording" in line and not line.strip().startswith("//")]
    shots = [line for line in router.split("\n") if re.search(r"\bscreenshot\b", line)]
    marked = [i + 1 for i in code if i > 0 and raw[i - 1].strip().startswith("// ARMSX3:")]
    if marked or len(code) != len(shots):
        problems.append("record: router toggleRecording lines {0} carry an `// ARMSX3:` marker above, or {1} lines for {2} "
                        "screenshot sites".format(marked, len(code), len(shots)))
    return problems


def under_armsx3(raw, signature):
    """True when the comment block directly above `signature`'s line holds a port marker comment."""
    found = re.search(signature, raw)
    if found is None:
        return False
    lines = raw[:found.start()].split("\n")[:-1]
    block = []
    while lines and lines[-1].strip().startswith("//"):
        block.append(lines.pop().strip())
    return any(line.startswith("// ARMSX3:") for line in block)


def capture_problems(router_text):
    problems = []
    router = without_comments(router_text)
    for name in ("beginHotkeyCapture", "cancelHotkeyCapture"):
        signature = r"func\s+" + name + r"\s*\("
        if not re.search(signature, router):
            problems.append("capture: the router defines no {0}(".format(name))
        elif under_armsx3(router_text, signature):
            problems.append("capture: {0}( is under an // ARMSX3: marker".format(name))
    begin = body(router, r"func\s+beginHotkeyCapture\s*\(")
    if begin is not None:
        cancel = begin.find("cancelHotkeyCapture()")
        store = begin.find("hotkeyCaptureCompletion =")
        if cancel < 0 or store < 0 or cancel > store:
            problems.append("capture: beginHotkeyCapture does not cancel the running capture before it stores its own")
    extended = body(router, r"private\s+func\s+handleExtendedInput\s*\(") or ""
    held = re.search(r"if\s+hotkeyCaptureCompletion\s*!=\s*nil\s*\{", extended)
    gate = extended.find("guard isMenuActive")
    matcher = extended.find("updateEmulationControllerMacros(")
    if held is None:
        problems.append("capture: handleExtendedInput has no `if hotkeyCaptureCompletion != nil {` branch")
    else:
        branch = brace_block(extended, held.end() - 1)[0]
        if "feedHotkeyCapture(" not in branch or not re.search(r"\breturn\b", branch):
            problems.append("capture: the capture branch does not feed the capture and return")
        if gate < 0 or matcher < 0 or held.start() > gate or held.start() > matcher:
            problems.append("capture: the capture branch does not come before navigation and the chord matcher")
    feed = body(router, r"private\s+func\s+feedHotkeyCapture\s*\(")
    if feed is None:
        problems.append("capture: the router defines no feedHotkeyCapture(")
    else:
        if not re.search(r"!=\s*\.pause\b", feed):
            problems.append("capture: feedHotkeyCapture does not skip .pause, which is Start")
        if "HotkeyTable.normalised(" not in feed:
            problems.append("capture: feedHotkeyCapture does not complete through HotkeyTable.normalised(")
        if not re.search(r"\.button\s*==\s*\.circle\s*&&\s*\w+\.alone\s*\{\s*finishHotkeyCapture\(\s*nil\s*\)", feed):
            problems.append("capture: a lone Circle release does not end the capture with nil")
    return problems


def pane_problems(view_text):
    problems = []
    view = without_comments(view_text)
    start = body(view, r"func\s+startCapture\s*\(")
    if start is None:
        problems.append("pane: HotkeysView.swift defines no startCapture(")
    else:
        guard = re.search(r"guard\s+readsController\(\)", start)
        begin = re.search(r"beginHotkeyCapture\s*[({]", start)
        if guard is None or begin is None or guard.start() > begin.start():
            problems.append("pane: capture can start without GamepadPoller.readsAController")
        sleep = re.search(r"\.seconds\(\s*10\s*\)", start)
        cancel = start.find("cancelHotkeyCapture()")
        if sleep is None or cancel < 0 or sleep.start() > cancel:
            problems.append("pane: no 10-second timeout cancels the capture")
    if not re.search(r"readsController:\s*@escaping\s*(?:@MainActor\s*)?\(\)\s*->\s*Bool\s*=\s*\{\s*GamepadPoller\.readsAController\s*\}", view):
        problems.append("pane: the controller reader does not default to GamepadPoller.readsAController")
    captured = body(view, r"func\s+captured\s*\(") or ""
    if "replacing: false" not in captured:
        problems.append("pane: captured does not assign with replacing: false")
    for arm, alert in (("conflict", "alert = .conflict"), ("refused", "alert = .refused")):
        found = re.search(r"case\s+\." + arm + r"\b[^:]*:", captured)
        rest = captured[found.end():] if found else ""
        end = re.search(r"\bcase\s+\.|\bdefault\s*:|\n\s*\}", rest)
        if alert not in rest[:end.start() if end else len(rest)]:
            problems.append("pane: the {0} answer does not raise {1}".format(arm, alert))
    use = body(view, r"func\s+useIt\s*\(") or ""
    if len(re.findall(r"replacing:\s*true", view)) != 1 or not re.search(r"replacing:\s*true", use):
        problems.append("pane: replacing: true is not confined to useIt, the conflict alert's Use It")
    if not re.search(r'Button\("Use It"\)\s*\{\s*model\.useIt\(\)\s*\}', view):
        problems.append("pane: the Use It button does not call model.useIt()")
    pick = re.search(r'Button\("Pick Other Buttons"\)\s*\{', view)
    if pick is None or "startCapture(" not in brace_block(view, pick.end() - 1)[0]:
        problems.append("pane: Pick Other Buttons does not restart capture")
    can = body(view, r"func\s+canClear\s*\(") or ""
    clear = body(view, r"func\s+clear\s*\(") or ""
    if not re.search(r"!=\s*HotkeyTable\.quickMenu\b", can) or not re.search(r"\{\s*guard\s+canClear\(", clear):
        problems.append("pane: Clear Hotkey is not refused for Quick Menu")
    return problems


class TheTableHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(HOTKEYS)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([HOTKEYS], DRIVER, tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, *keys):
        self.assertEqual([EXPECTED[k] for k in keys], [self.lines.get(k) for k in keys])

    def test_k1_the_three_defaults_and_eight_unbound(self):
        self.check("K1")

    def test_k2_normalise_pause_to_start_and_refuse_the_rest(self):
        self.check("K2")

    def test_k3_titles_action_titles_and_captions(self):
        self.check("K3", "K3t", "K3c")

    def test_k4_a_held_chord_is_a_conflict_and_replacing_unbinds_the_holder(self):
        self.check("K4")

    def test_k5_quick_menus_chord_is_refused_for_any_other_action(self):
        self.check("K5")

    def test_k6_quick_menu_cannot_be_cleared(self):
        self.check("K6")

    def test_k7_reset_restores_the_three_defaults(self):
        self.check("K7")

    def test_k8_chords_conflict_as_unordered_pairs(self):
        self.check("K8")

    def test_k9_next_save_slot(self):
        self.check("K9")

    def test_k10_stepped_scale_clamps(self):
        self.check("K10")

    def test_k11_the_stored_dictionary_round_trips_and_is_idempotent(self):
        self.check("K11")


class TheSourcesAreClean(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        self.assertEqual([], hotkey_problems(read(HOTKEYS), read(ROUTER)))

    def test_capture_the_real_router_has_no_problem(self):
        self.assertEqual([], capture_problems(read(ROUTER)))

    def test_pane_the_real_view_has_no_problem(self):
        self.assertEqual([], pane_problems(read(PANE)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "HotkeyTable.swift"
            with self.assertRaises(AssertionError):
                read(missing)
            missing.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(missing)


class TheHotkeyGuardFailsOnAMutation(unittest.TestCase):
    def setUp(self):
        self.table, self.router = read(HOTKEYS), read(ROUTER)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def expect(self, prefix, table, router):
        problems = hotkey_problems(table, router)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), (prefix, problems))

    def test_mutation_host_the_model_imports_swiftui(self):
        self.expect("host", self.edited(self.table, "import Foundation", "import Foundation\nimport SwiftUI"), self.router)

    def test_mutation_log_the_model_prints(self):
        self.expect("log", self.edited(self.table, "struct HotkeyTable: Equatable {",
                                       "struct HotkeyTable: Equatable {\n    func probe() { print(stored) }"), self.router)

    def test_mutation_cases_two_actions_publish_one_shortcut(self):
        self.expect("cases", self.table, self.edited(self.router, "case .resolutionDown: .resolutionDown",
                                                     "case .resolutionDown: .resolutionUp"))

    def test_mutation_record_a_site_marked(self):
        router = self.router
        site = re.search(r"\n(\s*)case toggleRecording\n", router)
        self.assertIsNotNone(site)
        self.expect("record", self.table, router.replace(site.group(0), "\n" + site.group(1) + "// ARMSX3: marker"
                                                         + site.group(0), 1))

    def test_mutation_router_the_loop_reads_the_default(self):
        self.expect("router", self.table, self.edited(self.router, "where settings.controllerMacroBinding(for: action)",
                                                      "where Optional(action.defaultBinding)"))


class TheCaptureAndPaneGuardsFailOnAMutation(unittest.TestCase):
    """One mutation per `capture` and `pane` rule."""

    def setUp(self):
        self.router, self.view = read(ROUTER), read(PANE)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def capture(self, router):
        problems = capture_problems(router)
        self.assertTrue(any(p.startswith("capture:") for p in problems), problems)

    def pane(self, view):
        problems = pane_problems(view)
        self.assertTrue(any(p.startswith("pane:") for p in problems), problems)

    def test_mutation_capture_begin_gains_an_armsx3_line(self):
        self.capture(self.edited(self.router, "    func beginHotkeyCapture", "    // ARMSX3: marker\n    func beginHotkeyCapture"))

    def test_mutation_capture_a_new_begin_does_not_cancel(self):
        begin = body(self.router, r"func\s+beginHotkeyCapture\s*\(")
        self.capture(self.edited(self.router, begin, begin.replace("cancelHotkeyCapture()", "_ = 0", 1)))

    def test_mutation_capture_navigation_is_not_held(self):
        self.capture(self.edited(self.router, "if hotkeyCaptureCompletion != nil {", "if hotkeyCaptureCompletion == nil, false {"))

    def test_mutation_capture_pause_is_not_skipped(self):
        feed = body(self.router, r"private\s+func\s+feedHotkeyCapture\s*\(")
        self.capture(self.edited(self.router, feed, re.sub(r"\$0\s*!=\s*\.pause\s*&&\s*", "", feed, count=1)))

    def test_mutation_capture_circle_alone_does_not_cancel(self):
        self.capture(self.edited(self.router, "finishHotkeyCapture(nil) }", "hotkeyCaptureFirst = nil }"))

    def test_mutation_pane_capture_ignores_the_controller(self):
        self.pane(self.edited(self.view, "guard readsController(),", "guard true,"))

    def test_mutation_pane_the_reader_ignores_the_controller(self):
        self.pane(self.edited(self.view, "= { GamepadPoller.readsAController }", "= { true }"))

    def test_mutation_pane_the_timeout_is_not_ten_seconds(self):
        self.pane(self.edited(self.view, ".seconds(10)", ".seconds(60)"))

    def test_mutation_pane_a_conflict_is_taken_without_asking(self):
        self.pane(self.edited(self.view, "assigning(chord, to: action, replacing: false)", "assigning(chord, to: action, replacing: true)"))

    def test_mutation_pane_a_refusal_raises_no_alert(self):
        self.pane(self.edited(self.view, "alert = .refused(action: action)", "alert = nil"))

    def test_mutation_pane_use_it_does_nothing(self):
        self.pane(self.edited(self.view, 'Button("Use It") { model.useIt() }', 'Button("Use It") { model.alert = nil }'))

    def test_mutation_pane_pick_other_buttons_does_not_restart(self):
        pick = re.search(r'Button\("Pick Other Buttons"\)\s*\{', self.view)
        block = brace_block(self.view, pick.end() - 1)[0]
        self.pane(self.edited(self.view, block, block.replace("startCapture(", "cancelCapture(", 1)))

    def test_mutation_pane_quick_menu_can_be_cleared(self):
        self.pane(self.edited(self.view, "action != HotkeyTable.quickMenu", "!action.isEmpty"))


if __name__ == "__main__":
    unittest.main()
