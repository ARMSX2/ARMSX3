#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""Reads the library notice's wiring from source: the root decides and stores the fallback cause between the JIT check
and the JIT-off return, the card sits above the firmware card, a notice counts as shown when it appears, and the dismiss
label and the controller ids are fixed. It says nothing about what a phone or a simulator draws."""

import re
import unittest
from pathlib import Path

from source_helpers import brace_block

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
FRONTEND_ROOT = APP / "Shell" / "ARMSX3FrontendRoot.swift"
GAME_LIST = APP / "Library" / "GameListView.swift"
CARD = APP / "JIT" / "JitNoticeCard.swift"
ROOT_VIEW = APP / "Shell" / "RootView.swift"


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def block_after(text, signature):
    return brace_block(text, text.index(signature))[0]


def in_order(text, needles):
    positions = [text.find(needle) for needle in needles]
    return -1 not in positions and positions == sorted(positions)


def root_problems(text):
    start = block_after(text, "private func start() async")
    problems = []
    if not in_order(start, ["session.checkJIT()", "JitFallback.decide(", "JitFallback.store(", "if !on {"]):
        problems.append("root: decide and store do not sit between checkJIT and the JIT-off return")
    if "supportEnds: JitFallback.supportEndsAnnounced" not in start:
        problems.append("root: the notice is not given JitFallback.supportEndsAnnounced")
    if "JitNoticeModel.shared.current" not in start:
        problems.append("root: the notice is never handed to the model")
    if not in_order(start, ["JitFallback.migrates(", "SettingsStore.shared.jitMethod = JitMethod.builtin.rawValue",
                            "JitFallback.decide("]):
        problems.append("root: the stikdebug word is not rewritten to builtin before the cause is decided")
    if "migrationPending: migrationPending" not in start:
        problems.append("root: the notice is not told the migration is pending")
    if "jitMethodAtLaunch =" in start:
        problems.append("root: jitMethodAtLaunch is written")
    return problems


def root_view_problems(text):
    handler = block_after(text, "publisher(for: JitNoticeModel.openJitDeviceNotification)) { _ in")
    problems = []
    if "selectedMenuTab = 2" not in handler:
        problems.append("rootview: the notice action does not switch to the Settings tab")
    if "settingsMenuNavigationPath = [.jitDevice]" not in handler:
        problems.append("rootview: the notice action does not push JIT and Device")
    return problems


def library_problems(text):
    grid = block_after(text, "private var grid: some View")
    problems = []
    if not in_order(grid, ["JitNoticeCard(", "JitNoticeModel.shared.appeared()", "FirmwareStatusCard("]):
        problems.append("library: the notice card does not come before the firmware card with an appeared call")
    return problems


def card_problems(text):
    problems = []
    if "markShown" not in block_after(text, "func appeared()"):
        problems.append("card: appeared() does not mark the keys shown")
    if 'Button("Dismiss Notice"' not in text:
        problems.append("card: the dismiss label is not Dismiss Notice")
    for ident in ("library.jitnotice.action", "library.jitnotice.dismiss"):
        if '"{0}"'.format(ident) not in text:
            problems.append("card: the controller id {0} is missing".format(ident))
    for word in ("TransientBanner", "asyncAfter"):
        if word in text:
            problems.append("card: {0} would time the notice out".format(word))
    title = re.search(r"Text\(verbatim: title\)(.*?)Text\(verbatim: message\)", text, re.S)
    if title is None or "fixedSize(horizontal: false, vertical: true)" not in title.group(1):
        problems.append("card: the title can be cut short at large text sizes")
    if "lineLimit" in text:
        problems.append("card: a line limit would cut the notice at large text sizes")
    return problems


class WiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = read(FRONTEND_ROOT)
        cls.library = read(GAME_LIST)
        cls.card = read(CARD)
        cls.root_view = read(ROOT_VIEW)

    def test_the_sources_pass(self):
        self.assertEqual(root_problems(self.root), [])
        self.assertEqual(library_problems(self.library), [])
        self.assertEqual(card_problems(self.card), [])
        self.assertEqual(root_view_problems(self.root_view), [])


class MutationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = read(FRONTEND_ROOT)
        cls.library = read(GAME_LIST)
        cls.card = read(CARD)
        cls.root_view = read(ROOT_VIEW)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor moved")
        return text.replace(old, new, 1)

    def test_mutation_decide_after_the_jit_off_return(self):
        text = self.edited(self.root, "let defaults = UserDefaults.standard", "if !on { return }\n        let defaults = UserDefaults.standard")
        self.assertTrue(root_problems(text))

    def test_mutation_the_cause_is_never_stored(self):
        text = self.edited(self.root, "JitFallback.store(verdict, in: defaults)", "")
        self.assertTrue(root_problems(text))

    def test_mutation_support_ends_hard_coded(self):
        text = self.edited(self.root, "supportEnds: JitFallback.supportEndsAnnounced", "supportEnds: true")
        self.assertTrue(root_problems(text))

    def test_mutation_the_card_below_the_firmware_card(self):
        text = self.edited(self.library, "JitNoticeCard(", "FirmwareStatusCard(version: \"\")\n            JitNoticeCard(")
        self.assertTrue(library_problems(text))

    def test_mutation_the_card_never_reports_appearing(self):
        text = self.edited(self.library, "JitNoticeModel.shared.appeared()", "JitNoticeModel.shared.dismiss()")
        self.assertTrue(library_problems(text))

    def test_mutation_appeared_does_not_mark(self):
        text = self.edited(self.card, "JitFallback.markShown(marks, in: .standard)", "")
        self.assertTrue(card_problems(text))

    def test_mutation_the_dismiss_label_renamed(self):
        text = self.edited(self.card, 'Button("Dismiss Notice"', 'Button("Close"')
        self.assertTrue(card_problems(text))

    def test_mutation_a_controller_id_dropped(self):
        text = self.edited(self.card, '"library.jitnotice.dismiss"', '"library.jitnotice.close"')
        self.assertTrue(card_problems(text))

    def test_mutation_the_title_cannot_grow(self):
        text = self.edited(self.card, "Text(verbatim: title)\n                            .font(.headline)\n                            .foregroundStyle(OverlayTheme.textPrimary)\n                            .fixedSize(horizontal: false, vertical: true)",
                           "Text(verbatim: title)\n                            .font(.headline)\n                            .foregroundStyle(OverlayTheme.textPrimary)")
        self.assertTrue(card_problems(text))

    def test_mutation_the_title_is_limited_to_one_line(self):
        text = self.edited(self.card, "Text(verbatim: title)\n                            .font(.headline)",
                           "Text(verbatim: title)\n                            .lineLimit(1)\n                            .font(.headline)")
        self.assertTrue(card_problems(text))

    def test_mutation_the_message_is_limited_to_two_lines(self):
        text = self.edited(self.card, "Text(verbatim: message)", "Text(verbatim: message).lineLimit(2)")
        self.assertTrue(card_problems(text))

    def test_mutation_the_card_times_out(self):
        text = self.edited(self.card, "func dismiss() {", "func dismiss() {\n        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {}")
        self.assertTrue(card_problems(text))

    def test_mutation_the_old_word_is_not_rewritten(self):
        text = self.edited(self.root, "SettingsStore.shared.jitMethod = JitMethod.builtin.rawValue", "")
        self.assertTrue(root_problems(text))

    def test_mutation_the_rewrite_comes_after_the_decision(self):
        text = self.edited(self.root, "if migrationPending { SettingsStore.shared.jitMethod = JitMethod.builtin.rawValue }", "")
        text = self.edited(text, "JitFallback.store(verdict, in: defaults)",
                           "JitFallback.store(verdict, in: defaults)\n        SettingsStore.shared.jitMethod = JitMethod.builtin.rawValue")
        self.assertTrue(root_problems(text))

    def test_mutation_the_notice_not_told_about_the_migration(self):
        text = self.edited(self.root, "migrationPending: migrationPending", "migrationPending: false")
        self.assertTrue(root_problems(text))

    def test_mutation_the_launch_method_is_rewritten_too(self):
        text = self.edited(self.root, "let migrationPending", "SettingsStore.shared.jitMethodAtLaunch = \"builtin\"\n        let migrationPending")
        self.assertTrue(root_problems(text))

    def test_mutation_the_action_stays_on_the_library_tab(self):
        text = self.edited(self.root_view, "selectedMenuTab = 2\n            settingsMenuNavigationPath = [.jitDevice]",
                           "settingsMenuNavigationPath = [.jitDevice]")
        self.assertTrue(root_view_problems(text))

    def test_mutation_the_action_pushes_nothing(self):
        text = self.edited(self.root_view, "settingsMenuNavigationPath = [.jitDevice]", "settingsMenuNavigationPath = []")
        self.assertTrue(root_view_problems(text))


if __name__ == "__main__":
    unittest.main()
