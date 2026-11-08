# SPDX-License-Identifier: GPL-2.0-only
"""present_gate.h, compiled on the host, reports a zero client size while the scene is inactive after a flip, and
ios_frame.mm wires the gate in order, closes it on background and seeds it from the real scene state.
Host evidence about a pure header and text only; it says nothing about swapchain rebuilds."""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import brace_block
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "ios" / "bridge"
SUBJECT = BRIDGE / "present_gate.h"
FRAME = BRIDGE / "ios_frame.mm"

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

# tag -> (scene_active, renderer_started, published, expected)
ROWS = {
    "active_running": (True, True, 1170, 1170),
    "inactive_running": (False, True, 1170, 0),
    "inactive_booting": (False, False, 1170, 1170),
    "active_booting": (True, False, 1170, 1170),
    "inactive_running_declined": (False, True, -1, -1),
    "active_running_declined": (True, True, -1, -1),
    "inactive_booting_declined": (False, False, -1, -1),
    "inactive_running_zero": (False, True, 0, 0),
}


STORE = r"s_scene_active\s*(=\s*%s\b|\.store\(\s*%s\s*\))"
ANY_STORE = re.compile(r"s_scene_active\s*(=[^=]|\.store\()")


def background_close_problems(code):
    """The DidEnterBackground observer block stores false into the gate, before any mention of the display epoch or the pause mutex."""
    at = code.find("addObserverForName:UISceneDidEnterBackgroundNotification")
    if at == -1:
        return ["background-close: no UISceneDidEnterBackgroundNotification observer"]
    block = brace_block(code, code.index("usingBlock:^", at))[0]
    store = re.search(STORE % ("false", "false"), block)
    if store is None:
        return ["background-close: the background block never stores s_scene_active = false"]
    prelude = block[: store.start()]
    return ["background-close: the background block names %s before the gate store" % name
            for name in ("s_display_epoch", "s_pause_mutex") if name in prelude]


def seed_problems(code):
    """install_lifecycle_observers reads the real scene state and stores it into the gate after its last addObserverForName:."""
    match = re.search(r"\bvoid\s+install_lifecycle_observers\s*\(\s*\)", code)
    if match is None:
        return ["seed: no install_lifecycle_observers definition"]
    body = brace_block(code, match.end())[0]
    scenes = body.find("connectedScenes")
    if scenes == -1 or "UISceneActivationStateForegroundActive" not in body[scenes:]:
        return ["seed: the install does not read connectedScenes for UISceneActivationStateForegroundActive"]
    store = ANY_STORE.search(body, scenes)
    if store is None:
        return ["seed: no s_scene_active store follows the scene read"]
    if store.start() < body.rfind("addObserverForName:"):
        return ["seed: the seed store precedes the last addObserverForName:"]
    return []


def driver_source():
    lines = ['#include "present_gate.h"', "#include <cstdio>", "int main()", "{"]
    for tag, (active, started, published, _) in ROWS.items():
        lines.append(
            '    std::printf("{0} %d\\n", armsx3::ios_frame::gated_extent({1}, {2}, {3}));'.format(
                tag, str(active).lower(), str(started).lower(), published)
        )
    lines += ["    return 0;", "}", ""]
    return "\n".join(lines)


class TheSubjectExists(unittest.TestCase):
    def test_the_header_exists_and_is_not_empty(self):
        self.assertTrue(
            SUBJECT.is_file() and SUBJECT.read_text(encoding="utf-8").strip(),
            "ios/bridge/present_gate.h is absent or empty. FAILURE, not skip.",
        )


class TheDecisionTableHolds(unittest.TestCase):
    results = None

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")

        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
            driver.write_text(driver_source(), encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver),
                 "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
            )
            cls.compile_output = built.stdout
            if built.returncode != 0:
                return
            ran = subprocess.run(
                [str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True,
            )
            cls.run_code = ran.returncode
            cls.results = dict(line.split() for line in ran.stdout.splitlines())

    def setUp(self):
        self.assertIsNotNone(
            self.results,
            "the driver did not compile against ios/bridge/present_gate.h:\n"
            + (self.compile_output or ""),
        )
        self.assertEqual(0, self.run_code, "the driver exited non-zero")

    def check(self, tag):
        expected = ROWS[tag][3]
        self.assertEqual(str(expected), self.results.get(tag), "row %s" % tag)

    def test_active_and_running_passes_the_extent(self):
        self.check("active_running")

    def test_inactive_and_running_reports_zero(self):
        self.check("inactive_running")

    def test_inactive_before_the_first_flip_keeps_the_extent(self):
        self.check("inactive_booting")

    def test_active_before_the_first_flip_keeps_the_extent(self):
        self.check("active_booting")

    def test_declined_passes_through_while_inactive(self):
        self.check("inactive_running_declined")

    def test_declined_passes_through_while_active(self):
        self.check("active_running_declined")

    def test_declined_passes_through_before_the_first_flip(self):
        self.check("inactive_booting_declined")

    def test_a_zero_extent_stays_zero(self):
        self.check("inactive_running_zero")


class TheGateIsWiredInOrder(unittest.TestCase):
    """Static checks on the comment-stripped real ios/bridge/ios_frame.mm."""

    PAUSE_QUEUE = "dispatch_async(s_pause_queue"

    @classmethod
    def setUpClass(cls):
        text = FRAME.read_text(encoding="utf-8") if FRAME.is_file() else ""
        if not text.strip():
            raise AssertionError("ios/bridge/ios_frame.mm is absent or empty. FAILURE, not skip.")
        cls.code = without_comments(text)

    def observer_block(self, notification):
        at = self.code.index("addObserverForName:" + notification)
        return brace_block(self.code, self.code.index("usingBlock:^", at))[0]

    def body_of(self, signature):
        match = re.search(signature, self.code)
        self.assertIsNotNone(match, "no definition matching %r" % signature)
        return brace_block(self.code, match.end())[0]

    def check_store_precedes_queue(self, notification, value):
        block = self.observer_block(notification)
        store = re.search(r"s_scene_active\s*(=\s*%s\b|\.store\(\s*%s\s*\))" % (value, value),
                          block)
        self.assertIsNotNone(store, "%s: no s_scene_active %s store" % (notification, value))
        self.assertIn(self.PAUSE_QUEUE, block, "%s: no pause-queue dispatch" % notification)
        self.assertLess(store.start(), block.index(self.PAUSE_QUEUE),
                        "%s: the gate must move BEFORE the pause queue is used" % notification)
        prelude = block[: block.index(self.PAUSE_QUEUE)]
        for name in ("s_display_epoch", "s_pause_mutex"):
            self.assertNotIn(name, prelude, "%s: gate code names %s" % (notification, name))

    def test_the_gate_closes_before_the_pause_is_queued(self):
        self.check_store_precedes_queue("UISceneWillDeactivateNotification", "false")

    def test_the_gate_opens_before_the_resume_is_queued(self):
        self.check_store_precedes_queue("UISceneDidActivateNotification", "true")

    def test_both_accessors_are_gated(self):
        for name in ("client_pixel_width", "client_pixel_height"):
            body = self.body_of(r"\bint\s+%s\s*\(\s*\)" % name)
            self.assertIn("gated_extent(", body, "%s does not pass through gated_extent" % name)

    def test_attach_restarts_the_flip_clock(self):
        body = self.body_of(r"\bvoid\s+attach_view\s*\(")
        self.assertRegex(body, r"s_flips_at_attach\s*(=[^=]|\.store\()",
                         "attach_view does not snapshot s_flips_at_attach")

    def test_the_background_block_closes_the_gate(self):
        self.assertEqual([], background_close_problems(self.code))

    def test_the_seed_reads_the_real_scene_state_after_registration(self):
        self.assertEqual([], seed_problems(self.code))


class SeedAndBackgroundMutations(unittest.TestCase):
    """Each method makes ONE edit to the comment-stripped real ios_frame.mm and shows the check fail."""

    @classmethod
    def setUpClass(cls):
        text = FRAME.read_text(encoding="utf-8") if FRAME.is_file() else ""
        if not text.strip():
            raise AssertionError("ios/bridge/ios_frame.mm is absent or empty. FAILURE, not skip.")
        cls.code = without_comments(text)

    def edited(self, text, old, new):
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def test_mutation_the_background_store_deleted(self):
        at = self.code.index("addObserverForName:UISceneDidEnterBackgroundNotification")
        block, _ = brace_block(self.code, self.code.index("usingBlock:^", at))
        store = re.search(STORE % ("false", "false"), block)
        self.assertIsNotNone(store)
        mutated_block = self.edited(block, store.group(0), "(void)0")
        text = self.edited(self.code, block, mutated_block)
        self.assertTrue(any(p.startswith("background-close") for p in background_close_problems(text)))

    def test_mutation_the_seed_moved_above_the_first_observer(self):
        match = re.search(r"\bvoid\s+install_lifecycle_observers\s*\(\s*\)", self.code)
        body, _ = brace_block(self.code, match.end())
        start = body.rfind("\n", 0, body.index("connectedScenes"))
        store = ANY_STORE.search(body, start)
        end = body.index(";", store.end()) + 1
        seed = body[start:end]
        moved = self.edited(body, seed, "")
        first = moved.index("[centre addObserverForName:")
        moved = moved[:first] + seed.strip() + "\n" + moved[first:]
        text = self.edited(self.code, body, moved)
        self.assertTrue(any(p.startswith("seed") for p in seed_problems(text)), seed_problems(text))


if __name__ == "__main__":
    unittest.main(verbosity=2)
