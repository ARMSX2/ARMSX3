#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PlayerSlots.swift, run alone on the Mac host, hands players 1-4 to controllers by first press, keeps a lost player's
slot, honours pins and the three-player Move limit; static checks show the poller and game screen pause and name a lost player.
It says nothing about which controller iOS reports first or a real controller dropping."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span, without_comments

IOS = Path(__file__).resolve().parents[1]
SLOTS = IOS / "app" / "Controllers" / "PlayerSlots.swift"
POLLER = IOS / "app" / "Controllers" / "GamepadPoller.swift"
SCREEN = IOS / "app" / "Game" / "GameScreenView.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)

# Written at run time into a temporary directory, never committed. Synthetic model words only.
DRIVER = r"""
func show(_ n: Int?) -> String { n.map(String.init) ?? "nil" }
do {
    var s = PlayerSlots()
    let a = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [])
    let b = PlayerSlots.key(category: "OtherKind", vendor: "Maker", live: [a])
    let first = s.claim(a), second = s.claim(b), again = s.claim(a)
    print("R1 first=\(show(first)) second=\(show(second)) again=\(show(again))")
}
do {
    var s = PlayerSlots()
    var live: [ControllerKey] = []
    for _ in 0..<4 {
        let k = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: live)
        live.append(k)
        _ = s.claim(k)
    }
    let fifth = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: live)
    print("R2 fifth=\(show(s.claim(fifth)))")
}
do {
    let k0 = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [])
    let k1 = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [k0])
    let k2 = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [k1])
    print("R3 twin=\(k0.ordinal),\(k1.ordinal) after=\(k2.ordinal)")
}
do {
    var s = PlayerSlots()
    let a = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [])
    _ = s.claim(a)
    let released = s.release(a)
    let b = PlayerSlots.key(category: "OtherKind", vendor: "Maker", live: [])
    print("R4 released=\(show(released)) next=\(show(s.claim(b)))")
}
do {
    var s = PlayerSlots()
    let before = s.playerOneHasController ? 1 : 0
    _ = s.claim(PlayerSlots.key(category: "PadKind", vendor: "Maker", live: []))
    print("R5 one=\(before) one=\(s.playerOneHasController ? 1 : 0)")
}
// A, B and C are three keys; names[] prints them as single letters.
let ka = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [])
let kb = PlayerSlots.key(category: "PadKind", vendor: "Maker", live: [ka])
let kc = PlayerSlots.key(category: "OtherKind", vendor: "Maker", live: [])
let names: [ControllerKey: String] = [ka: "A", kb: "B", kc: "C"]
func name(_ k: ControllerKey?) -> String { k.map { names[$0] ?? "?" } ?? "nil" }
do {
    var s = PlayerSlots()
    _ = s.claim(ka); _ = s.claim(kb)
    let lost = s.lose(kb)
    print("R6 lost=\(show(lost)) held=\(name(s.holders[1])) reserved=\(name(s.reserved[1]))")
    let back = s.claim(kb)
    print("R7 back=\(show(back))" + (s.reserved.contains { $0 != nil } ? " still-reserved" : ""))
}
do {
    var s = PlayerSlots()
    _ = s.claim(ka)
    let before = s
    let unheld = s.lose(kb)
    print("R8 unheld=\(show(unheld))" + (s == before ? "" : " changed"))
}
do {
    var s = PlayerSlots()
    _ = s.claim(ka); _ = s.claim(kb); _ = s.lose(kb)
    print("R9 newcomer=\(show(s.claim(kc)))")
}
do {
    var s = PlayerSlots()
    _ = s.claim(ka); _ = s.claim(kb); _ = s.lose(kb)
    let holders = s.holders
    s.endSession()
    print("R10 ended=\(s.reserved.contains { $0 != nil } ? "some" : "nil")" + (s.holders == holders ? "" : " moved"))
}
do {
    var s = PlayerSlots()
    let ok = s.pin(kb, to: 2)
    let pinned = s.claim(kb)
    var stored = s.storedPins
    stored["PadKind|Maker|0"] = 9
    let loaded = PlayerSlots(storedPins: stored)
    let kept = ok && loaded.pins == [kb: 2]
    print("R11 pinned=\(show(pinned))" + (kept ? "" : " load-wrong"))
}
do {
    var s = PlayerSlots()
    _ = s.claim(ka); _ = s.claim(kb)
    _ = s.pin(kb, to: 0)
    let refused = !s.pin(ka, to: 4) && !s.pin(ka, to: -1)
    print("R12 swapped=A:\(show(s.slot(of: ka))),B:\(show(s.slot(of: kb)))" + (refused ? "" : " out-of-range-accepted"))
}
do {
    let kd = PlayerSlots.key(category: "OtherKind", vendor: "Maker", live: [kc])
    var s = PlayerSlots()
    _ = s.claim(ka, limit: 3); _ = s.claim(kb, limit: 3); _ = s.claim(kc, limit: 3)
    let fourth = s.claim(kd, limit: 3)
    var pinned = s
    _ = pinned.pin(kd, to: 3)
    var lost = PlayerSlots()
    _ = lost.claim(ka); _ = lost.claim(kb); _ = lost.claim(kc); _ = lost.claim(kd); _ = lost.lose(kd)
    let over = pinned.claim(kd, limit: 3) != nil || lost.claim(kd, limit: 3) != nil
    print("R13 limit=\(show(fourth))" + (over ? " slot-3-offered" : ""))
}
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def router_problems(slots):
    imports = IMPORT.findall(without_comments(slots))
    if imports != ["Foundation"]:
        return ["host: PlayerSlots.swift imports {0}; it must import Foundation only".format(imports)]
    return []


def shell_problems(poller, screen):
    """The shell checks, on comment-stripped text; one `prefix: message` per failed check."""
    poller = without_comments(poller)
    screen = without_comments(screen)
    problems = []

    def body(text, signature):
        at = text.find(signature)
        if at == -1:
            return ""
        return text[slice(*brace_span(text, at))]

    tick = body(poller, "func tick(")
    reconcile_at, owner_at = tick.find("reconcile("), tick.find("inputOwner == .game")
    if reconcile_at == -1 or owner_at == -1 or reconcile_at > owner_at:
        problems.append("reconcile: tick() does not reconcile the live set before its inputOwner == .game gate")
    lose = body(poller, "func loseSlot(")
    if "slots.lose(" not in lose or "onPlayerLost?(" not in lose:
        problems.append("lostcall: the loss path does not call slots.lose( and onPlayerLost?(")
    at = screen.find("GamepadPoller.shared.onPlayerLost = {")
    handler = screen[slice(*brace_span(screen, at))] if at != -1 else ""
    if "setMenu(open: true)" not in handler or "shotBanner.present(" not in handler \
            or "controller disconnected" not in handler:
        problems.append("pause: GameScreenView's onPlayerLost does not pause through setMenu(open: true) and name the player")
    if "slots.endSession()" not in body(poller, "func stop()"):
        problems.append("endsession: stop() does not end the session's reservations")
    show = body(poller, "func showSlot(")
    if "playerIndex = GCControllerPlayerIndex(rawValue:" not in show or "showSlot(" not in body(poller, "func claim("):
        problems.append("index: a claim does not set the controller's playerIndex to its slot")
    colors = re.search(r"static\s+let\s+slotColors\b[^\]]*\]", poller)
    if "light?.color = Self.slotColors[" not in show or not colors or colors.group(0).count("GCColor(") != 4:
        problems.append("light: a claim does not set the light from four slotColors")
    if "SettingsStore.shared.controllerPins" not in body(poller, "func start()"):
        problems.append("pins: start() does not load the stored pins")
    return problems


class TheSlotsHold(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(SLOTS)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([SLOTS], DRIVER, tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_r1_first_press_claims_the_lowest_free_slot(self):
        self.check("R1", "R1 first=0 second=1 again=0")

    def test_r2_a_fifth_controller_gets_no_slot(self):
        self.check("R2", "R2 fifth=nil")

    def test_r3_twins_are_ordered_and_a_leaver_frees_its_ordinal(self):
        self.check("R3", "R3 twin=0,1 after=0")

    def test_r4_a_released_slot_is_claimed_again(self):
        self.check("R4", "R4 released=0 next=0")

    def test_r5_player_one_has_a_controller_only_while_slot_0_is_held(self):
        self.check("R5", "R5 one=0 one=1")

    def test_r6_a_lost_holder_leaves_its_slot_reserved(self):
        self.check("R6", "R6 lost=1 held=nil reserved=B")

    def test_r7_the_same_key_gets_its_reserved_slot_back(self):
        self.check("R7", "R7 back=1")

    def test_r8_losing_an_unheld_key_changes_nothing(self):
        self.check("R8", "R8 unheld=nil")

    def test_r9_a_newcomer_never_takes_a_reserved_slot(self):
        self.check("R9", "R9 newcomer=2")

    def test_r10_ending_the_session_clears_reservations_only(self):
        self.check("R10", "R10 ended=nil")

    def test_r11_a_pinned_key_claims_its_pinned_slot(self):
        self.check("R11", "R11 pinned=2")

    def test_r12_pinning_swaps_live_holders(self):
        self.check("R12", "R12 swapped=A:1,B:0")

    def test_r13_a_limit_of_three_leaves_slot_3_unclaimed(self):
        self.check("R13", "R13 limit=nil")


class TheSlotsStayHostable(unittest.TestCase):
    def test_the_real_file_has_no_problem(self):
        self.assertEqual([], router_problems(read(SLOTS)))

    def test_edge_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")

    def test_mutation_host_imports_gamecontroller(self):
        text = read(SLOTS)
        self.assertIn("import Foundation", text)
        mutated = text.replace("import Foundation", "import Foundation\nimport GameController", 1)
        self.assertTrue(any(p.startswith("host:") for p in router_problems(mutated)))


class TheShellHolds(unittest.TestCase):
    """The real files pass, and one mutation per check is caught."""

    @classmethod
    def setUpClass(cls):
        cls.poller, cls.screen = read(POLLER), read(SCREEN)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def flagged(self, prefix, poller=None, screen=None):
        problems = shell_problems(poller or self.poller, screen or self.screen)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_real_files_have_no_shell_problems(self):
        self.assertEqual([], shell_problems(self.poller, self.screen))

    def test_mutation_reconcile_after_the_owner_gate(self):
        self.flagged("reconcile", poller=self.edited(self.poller, "reconcile(controllers, report: true)", "_ = controllers"))

    def test_mutation_lostcall_never_tells_the_screen(self):
        self.flagged("lostcall", poller=self.edited(self.poller, "onPlayerLost?(slot)", "_ = slot"))

    def test_mutation_pause_without_the_menu(self):
        self.flagged("pause", screen=self.edited(self.screen, "setMenu(open: true)\n            shotBanner.present(",
                                                 "shotBanner.present("))

    def test_mutation_endsession_stop_keeps_reservations(self):
        self.flagged("endsession", poller=self.edited(self.poller, "slots.endSession()", "_ = slots"))

    def test_mutation_index_never_set(self):
        self.flagged("index", poller=self.edited(self.poller, "playerIndex = GCControllerPlayerIndex(rawValue:",
                                                 "playerIndex = .indexUnset; _ = GCControllerPlayerIndex(rawValue:"))

    def test_mutation_light_three_colours(self):
        self.flagged("light", poller=self.edited(self.poller, "GCColor(red: 1, green: 0.4, blue: 0.7)", ""))

    def test_mutation_pins_never_loaded(self):
        self.flagged("pins", poller=self.edited(self.poller, "SettingsStore.shared.controllerPins)", "[:])"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
