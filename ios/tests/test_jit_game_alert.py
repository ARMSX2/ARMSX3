# SPDX-License-Identifier: GPL-2.0-only
"""GameScreenView's second-pass alert offers Open StikDebug, Set Up Built-in JIT and Not Now in that order with the
built-in JIT sentence, and the Set Up button pauses the game and opens the JIT and Device pane in a sheet with Done.
The out-of-JIT-memory text follows the route that ran, and the second pass's StikDebug link carries LiveContainer's
bundle id. It reads comment-stripped source text and proves nothing about iOS."""

import re
import unittest
from pathlib import Path

from source_helpers import brace_span, read, without_comments

SCREEN = Path(__file__).resolve().parents[1] / "app" / "Game" / "GameScreenView.swift"

MESSAGE = ("Open StikDebug to prepare more. The game pauses while it works. "
           "Built-in JIT prepares enough from the start, so setting it up avoids this.")

BUTTONS = ('Button("Open StikDebug")', 'Button("Set Up Built-in JIT")', 'Button("Not Now", role: .cancel)')


def alert_problems(code):
    at = code.find('.alert("More JIT memory needed"')
    if at == -1:
        return ["alert: the More JIT memory needed alert is missing"]
    region = code[at:code.find(".sheet(", at)]
    problems = []
    last = -1
    for button in BUTTONS:
        found = region.find(button)
        if found <= last:
            problems.append("alert: {0} is missing or out of order".format(button))
        last = max(last, found)
    if 'Text("{0}")'.format(MESSAGE) not in region:
        problems.append("alert: the message is not the built-in JIT sentence")
    set_up = region[region.find(BUTTONS[1]):region.find(BUTTONS[2])] if BUTTONS[1] in region else ""
    if "setMenu(open: true)" not in set_up or "jitDeviceSheet = " not in set_up:
        problems.append("alert: Set Up Built-in JIT does not pause through setMenu(open: true) and open the sheet")
    return problems


def sheet_problems(code):
    match = re.search(r"\.sheet\(item:\s*\$jitDeviceSheet\)", code)
    if match is None:
        return ["sheet: the JIT and Device sheet is not presented with .sheet(item:"]
    body = code[slice(*brace_span(code, match.end()))]
    problems = []
    if "NavigationStack" not in body:
        problems.append("sheet: no NavigationStack")
    if "JitDevicePane(host: .inGame" not in body:
        problems.append("sheet: the pane is not hosted with host: .inGame")
    if 'Button("Done")' not in body:
        problems.append("sheet: no Done button")
    if "jitDeviceSheet = nil" not in body:
        problems.append("sheet: Done and Circle/B do not close it")
    return problems


HALT_FALLBACK = ("The game ran out of JIT memory. Built-in JIT prepares more. "
                 "Set it up in Settings › JIT and Device, then open ARMSX3 again.")

HALT_BUILTIN = "The game ran out of JIT memory. Close ARMSX3 and open it again."


def halt_problems(code):
    problems = []
    match = re.search(r"var\s+haltText\s*:\s*Text\s*", code)
    body = code[slice(*brace_span(code, match.end()))] if match else ""
    keyed = re.search(r'if\s+session\.jitReason\s*!=\s*"helper-ok"\s*\{\s*return\s+Text\("([^"]*)"\)', body)
    if keyed is None or keyed.group(1) != HALT_FALLBACK:
        problems.append("halt: a launch that did not use the helper does not get the set-up sentence")
    if 'return Text("{0}")'.format(HALT_BUILTIN) not in body:
        problems.append("halt: a built-in launch lost its reopen sentence")
    if "stikdebug-handshake" in code:
        problems.append("halt: still keyed on stikdebug-handshake")
    return problems


def link_problems(code):
    match = re.search(r"func\s+openStikDebugForMoreJit\s*\(", code)
    body = code[slice(*brace_span(code, match.end()))] if match else ""
    if "JitSecondPassLink.url(bundleID: LiveContainerProbe.stikDebugBundleID," not in body:
        return ["link: the second pass does not use LiveContainer's bundle id"]
    return []


def all_problems(code):
    return alert_problems(code) + sheet_problems(code) + halt_problems(code) + link_problems(code)


class GameJitAlert(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = without_comments(read(SCREEN))

    def mutated(self, old, new):
        self.assertIn(old, self.code)
        return all_problems(self.code.replace(old, new, 1))

    def test_source_holds_every_pin(self):
        self.assertEqual([], all_problems(self.code))

    def test_mutation_buttons_out_of_order(self):
        swapped = self.code.replace(BUTTONS[1], "@@", 1).replace(BUTTONS[0], BUTTONS[1], 1).replace("@@", BUTTONS[0], 1)
        self.assertTrue(any("out of order" in p for p in alert_problems(swapped)))

    def test_mutation_not_now_is_not_cancel(self):
        self.assertTrue(any("Not Now" in p for p in self.mutated(BUTTONS[2], 'Button("Not Now")')))

    def test_mutation_message_changes(self):
        self.assertTrue(any("message" in p for p in self.mutated("avoids this.", "avoids it.")))

    def test_mutation_set_up_does_not_pause(self):
        set_up = self.code.index(BUTTONS[1])
        mutated = self.code[:set_up] + self.code[set_up:].replace("setMenu(open: true)", "", 1)
        self.assertTrue(any("pause" in p for p in alert_problems(mutated)))

    def test_mutation_sheet_is_not_item_based(self):
        self.assertTrue(any("sheet(item:" in p for p in self.mutated(".sheet(item: $jitDeviceSheet)", ".sheet(isPresented: $jitDeviceFlag)")))

    def test_mutation_pane_host_changes(self):
        self.assertTrue(any("inGame" in p for p in self.mutated("host: .inGame", "host: .settings")))

    def test_mutation_done_button_removed(self):
        done = self.code.index('Button("Done") { jitDeviceSheet')
        self.assertTrue(any("Done" in p for p in sheet_problems(self.code[:done] + 'Button("Finish") { jitDeviceSheet' + self.code[done + len('Button("Done") { jitDeviceSheet'):])))

    def test_mutation_halt_keyed_on_handshake(self):
        self.assertTrue(any("halt" in p for p in self.mutated('jitReason != "helper-ok"', 'jitReason == "stikdebug-handshake"')))

    def test_mutation_halt_sentence_changes(self):
        self.assertTrue(any("halt" in p for p in self.mutated("then open ARMSX3 again.", "then reopen it.")))

    def test_mutation_builtin_sentence_changes(self):
        self.assertTrue(any("built-in launch" in p for p in self.mutated(HALT_BUILTIN, "The game ran out of JIT memory.")))

    def test_mutation_link_uses_main_bundle(self):
        self.assertTrue(any("link" in p for p in self.mutated("LiveContainerProbe.stikDebugBundleID", 'Bundle.main.bundleIdentifier ?? ""')))


if __name__ == "__main__":
    unittest.main(verbosity=2)
