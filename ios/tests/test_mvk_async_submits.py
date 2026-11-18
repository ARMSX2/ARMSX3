# SPDX-License-Identifier: GPL-2.0-only
"""On iOS MoltenVK encodes submits on its own queue thread, and on Apple the framebuffer cache hands what it
removes to the deferred-dispose queue instead of destroying it. Text checks on the real sources; the crash and
its absence are shown by the macOS leg, not here."""

import re
import unittest
from pathlib import Path

from source_helpers import arms_enclosing
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
VK = ROOT / "rpcs3" / "Emu" / "RSX" / "VK"
FRAMEBUFFER = VK / "VKFramebuffer.cpp"
INSTANCE = VK / "vkutils" / "instance.cpp"


def read(path):
    text = path.read_text(encoding="utf-8") if path.is_file() else ""
    if not text.strip():
        raise AssertionError("%s is absent or empty. FAILURE, not skip." % path.relative_to(ROOT))
    return without_comments(text)


def guarded_by(code, index, macro):
    return any(macro in arm.condition and not arm.is_else for arm in arms_enclosing(code, index))


class FramebuffersWaitForTheGpu(unittest.TestCase):
    def test_removed_holders_go_to_the_dispose_queue_on_apple(self):
        code = read(FRAMEBUFFER)
        at = code.find("vk::get_gc()->dispose(*fbo);")
        self.assertNotEqual(-1, at, "the filter never disposes removed holders")
        self.assertTrue(guarded_by(code, at, "__APPLE__"), "the dispose sits outside an __APPLE__ arm")

    def test_the_holders_are_erased_only_after_they_are_disposed(self):
        code = read(FRAMEBUFFER)
        dispose = code.find("vk::get_gc()->dispose(*fbo);")
        erase = code.find("It->second.erase(removed, It->second.end());")
        self.assertTrue(-1 < dispose < erase, "the erase must come after the dispose loop")


class IosSubmitsAsynchronously(unittest.TestCase):
    def test_the_setting_is_pushed_on_ios_only(self):
        code = read(INSTANCE)
        match = re.search(r'"MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS"[^;]*&setting_synchronous_submits', code)
        self.assertIsNotNone(match, "the layer setting is never pushed")
        self.assertTrue(guarded_by(code, match.start(), "TARGET_OS_IPHONE"))

    def test_texture_streaming_keeps_submits_synchronous(self):
        code = read(INSTANCE)
        match = re.search(r"setting_synchronous_submits\s*=\s*([^;]*);", code)
        self.assertIsNotNone(match)
        self.assertIn("asynchronous_texture_streaming", match.group(1))
        self.assertRegex(match.group(1), r"\?\s*VK_TRUE\s*:\s*VK_FALSE")


if __name__ == "__main__":
    unittest.main()
