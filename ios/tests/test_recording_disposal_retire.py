#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The media capture in VKPresent.cpp hands the driver manager the completions its hard sync dropped, on Apple only,
after re-enabling it and after the current disposal scope was pushed past that sync. A source pin with one mutation;
it does not run the renderer."""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from source_helpers import arms_enclosing  # noqa: E402

PRESENT = ROOT / "rpcs3" / "Emu" / "RSX" / "VK" / "VKPresent.cpp"
NOTIFY = "driver_manager.notify_completed(vk::last_completed_event_id());"


def problems(text):
    found = []
    disable = text.find("driver_manager.set_enabled(false);")
    push = text.find("push_down_current_scope();", disable)
    enable = text.find("driver_manager.set_enabled(true);", disable)
    notify = text.find(NOTIFY, disable)

    if min(disable, push, enable) < 0:
        return ["the capture's set_enabled(false), push_down_current_scope() or set_enabled(true) is gone"]
    if notify < 0:
        return ["the capture no longer passes the dropped completions on after set_enabled(true)"]
    if not disable < push < enable < notify:
        found.append("notify_completed does not follow push_down_current_scope() and set_enabled(true)")

    arms = arms_enclosing(text, notify)
    apple = [arm for arm in arms if "__APPLE__" in arm.condition]
    if not apple or apple[-1].is_else:
        found.append("notify_completed is not inside an __APPLE__ arm, so upstream and Android would compile it")
    if text.count(NOTIFY) != 1:
        found.append("notify_completed(last_completed_event_id()) appears more than once")
    return found


class RecordingDisposalRetireTest(unittest.TestCase):
    def test_tree(self):
        self.assertEqual(problems(PRESENT.read_text(encoding="utf-8")), [])

    def test_unguarded_mutation_fails(self):
        text = PRESENT.read_text(encoding="utf-8")
        start = text.find("#ifdef __APPLE__", text.find("driver_manager.set_enabled(true);"))
        end = text.find("#endif", start) + len("#endif")
        guard = text[start:end]
        self.assertIn(NOTIFY, guard)
        unguarded = text[:start] + "\t\t" + NOTIFY + text[end:]
        self.assertNotEqual(problems(unguarded), [])


if __name__ == "__main__":
    unittest.main()
