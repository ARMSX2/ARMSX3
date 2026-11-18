# SPDX-License-Identifier: GPL-2.0-only
"""Every command buffer submit and every present waits on the host's GPU-work check on Apple, and the iOS
host points that check at the present gate. Text checks on the real sources; the hold itself is shown by the
macOS leg, not here."""

import re
import unittest
from pathlib import Path

from source_helpers import arms_enclosing, brace_block
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
VK = ROOT / "rpcs3" / "Emu" / "RSX" / "VK"
COMMAND_STREAM = VK / "VKCommandStream.cpp"
SWAPCHAIN = VK / "vkutils" / "swapchain.cpp"
HELPERS = VK / "VKHelpers.h"
IOS_RENDER = ROOT / "ios" / "core" / "render" / "ios_render.cpp"

WAIT = "wait_while_gpu_work_refused();"


def read(path):
    text = path.read_text(encoding="utf-8") if path.is_file() else ""
    if not text.strip():
        raise AssertionError("%s is absent or empty. FAILURE, not skip." % path.relative_to(ROOT))
    return without_comments(text)


def apple_only(code, index):
    arms = arms_enclosing(code, index)
    return any("__APPLE__" in arm.condition and not arm.is_else for arm in arms)


def wait_problems(code, signature, driver_call):
    """The function holds the wait, Apple-only, before its driver call."""
    match = re.search(signature, code)
    if match is None:
        return ["no definition matching %r" % signature]
    body, start = brace_block(code, match.end())
    at = body.find(WAIT)
    if at == -1:
        return ["%r never waits on the GPU-work check" % signature]
    problems = []
    if not apple_only(code, start + at):
        problems.append("%r waits outside an __APPLE__ arm" % signature)
    call = body.find(driver_call)
    if call == -1 or call < at:
        problems.append("%r reaches %s before the wait" % (signature, driver_call))
    return problems


SUBMIT = (r"\bstatic\s+void\s+queue_submit_impl\s*\(", "acquire_global_submit_lock();")
PRESENT = (r"\bVkResult\s+swapchain_WSI::present\s*\(", "_vkQueuePresentKHR(")


class EveryDriverEntryWaits(unittest.TestCase):
    def test_a_submit_waits_before_it_takes_the_submit_lock(self):
        self.assertEqual([], wait_problems(read(COMMAND_STREAM), *SUBMIT))

    def test_a_present_waits_before_it_reaches_the_driver(self):
        self.assertEqual([], wait_problems(read(SWAPCHAIN), *PRESENT))

    def test_the_wait_ends_on_a_stop(self):
        code = read(COMMAND_STREAM)
        match = re.search(r"\bvoid\s+wait_while_gpu_work_refused\s*\(\s*\)", code)
        self.assertIsNotNone(match, "no wait_while_gpu_work_refused definition")
        self.assertTrue(apple_only(code, match.start()), "the wait is defined outside an __APPLE__ arm")
        body = brace_block(code, match.end())[0]
        loop = re.search(r"while\s*\(([^)]*\)[^{]*)\{", body)
        self.assertIsNotNone(loop, "the wait has no loop")
        self.assertIn("g_gpu_work_refused()", loop.group(1))
        self.assertIn("Emu.IsStopped()", loop.group(1), "a stop would wait forever while backgrounded")

    def test_the_check_is_declared_apple_only(self):
        code = read(HELPERS)
        at = code.find("g_gpu_work_refused")
        self.assertNotEqual(-1, at, "VKHelpers.h does not declare g_gpu_work_refused")
        self.assertTrue(apple_only(code, at), "g_gpu_work_refused is declared outside an __APPLE__ arm")


class TheIosHostInstallsTheGate(unittest.TestCase):
    def test_prepare_host_points_the_check_at_the_present_gate(self):
        code = read(IOS_RENDER)
        match = re.search(r"\bint\s+prepare_host\s*\(\s*\)", code)
        self.assertIsNotNone(match, "no prepare_host definition")
        body = brace_block(code, match.end())[0]
        self.assertRegex(body, r"vk::g_gpu_work_refused\s*=\s*armsx3::ios_frame::present_gate_closed\s*;")


class Mutations(unittest.TestCase):
    """Each method makes ONE edit to the comment-stripped real source and shows the check fail."""

    def test_mutation_the_submit_wait_deleted(self):
        code = read(COMMAND_STREAM)
        body = brace_block(code, re.search(SUBMIT[0], code).end())[0]
        mutated = code.replace(body, body.replace(WAIT, "", 1), 1)
        self.assertNotEqual(code, mutated)
        self.assertTrue(wait_problems(mutated, *SUBMIT))

    def test_mutation_the_present_wait_deleted(self):
        code = read(SWAPCHAIN)
        body = brace_block(code, re.search(PRESENT[0], code).end())[0]
        mutated = code.replace(body, body.replace(WAIT, "", 1), 1)
        self.assertNotEqual(code, mutated)
        self.assertTrue(wait_problems(mutated, *PRESENT))

    def test_mutation_the_guard_dropped(self):
        code = read(COMMAND_STREAM)
        body = brace_block(code, re.search(SUBMIT[0], code).end())[0]
        unguarded = re.sub(r"#ifdef __APPLE__\s*" + re.escape(WAIT) + r"\s*#endif", WAIT, body, count=1)
        mutated = code.replace(body, unguarded, 1)
        self.assertNotEqual(code, mutated)
        self.assertTrue(wait_problems(mutated, *SUBMIT))


if __name__ == "__main__":
    unittest.main(verbosity=2)
