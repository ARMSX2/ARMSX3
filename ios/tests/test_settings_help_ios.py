#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ios/app/Resources/settings-help-ios.json has the generated help file's shape, names only paths that file has, and
holds no empty text, desktop-only wording, exclamation mark or dash. It does not check that every path has iOS
wording or that the wording is accurate."""

import json
import re
import sys
import unittest
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / "app" / "Resources"
OVERLAY = RESOURCES / "settings-help-ios.json"
GENERATED = RESOURCES / "settings-help.json"
DESKTOP = re.compile(r"OpenGL|\bmonitor|Windows|Linux|macOS|D-Bus|NVIDIA|\bAMD\b|\bIntel\b", re.IGNORECASE)
STYLE = re.compile("[!—–]")


def problems(overlay, generated):
    out = []
    help_ = overlay.get("help") if isinstance(overlay, dict) else None
    if overlay.get("version") != 1 or not isinstance(help_, dict) or not help_:
        return ["shape: settings-help-ios.json is not {\"version\": 1, \"help\": {...}} with entries"]
    known = set(generated.get("help", {}))
    for path, text in sorted(help_.items()):
        if path not in known:
            out.append("orphan: %s is not a path of settings-help.json" % path)
        if not isinstance(text, str) or not text.strip():
            out.append("empty: %s" % path)
            continue
        if DESKTOP.search(text):
            out.append("desktop: %s names %r" % (path, DESKTOP.search(text).group(0)))
        if STYLE.search(text):
            out.append("style: %s carries %r" % (path, STYLE.search(text).group(0)))
    return out


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        overlay = json.loads(OVERLAY.read_text(encoding="utf-8"))
        generated = json.loads(GENERATED.read_text(encoding="utf-8"))
        self.assertEqual(problems(overlay, generated), [])


class Mutations(unittest.TestCase):
    GENERATED = {"version": 1, "help": {"Video@@Renderer": "x", "Audio@@Volume": "y"}}

    def flagged(self, help_, prefix):
        found = problems({"version": 1, "help": help_}, self.GENERATED)
        self.assertTrue(any(p.startswith(prefix) for p in found), found)

    def test_orphan_path_fails(self):
        self.flagged({"Video@@Gone": "Text."}, "orphan")

    def test_empty_text_fails(self):
        self.flagged({"Video@@Renderer": "  "}, "empty")

    def test_desktop_word_fails(self):
        self.flagged({"Video@@Renderer": "Fall back to OpenGL."}, "desktop")

    def test_exclamation_fails(self):
        self.flagged({"Audio@@Volume": "Loud!"}, "style")

    def test_clean_entry_passes(self):
        self.assertEqual(problems({"version": 1, "help": {"Audio@@Volume": "How loud games play."}}, self.GENERATED), [])


if __name__ == "__main__":
    unittest.main(argv=sys.argv[:1], verbosity=1)
