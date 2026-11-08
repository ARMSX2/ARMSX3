#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The app's own copy lives in one English String Catalog: it parses, has no empty keys, holds every literal that reaches
it at run time through the localization seams, carries no other locale, and names no PS2, PCSX2 or Xbox concept.
It reads the catalog and sources, not a build, and says nothing about wording quality."""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "ios" / "app"
CATALOG = FRONTEND / "Resources" / "Localizable.xcstrings"

# Calls that look their literal up at run time: the whole argument of a lookup, or a control's leading title argument.
SEAM_CALLS = (
    "localized", "settingsLocalized", "NumberRow", "controlSlider", "particleSlider", "controlToggle",
    "BackgroundControlSection", "BackgroundControlToggle", "DynamicSettingsValueSlider",
    "DynamicSettingsResetButton", "dynamicSettingsGroup", "xmbMartSettingGroup", "settingResetButton",
    "PaletteGradientEditor", "closedOverStoppedCore",
)
SEAM = re.compile(r'\b(?:' + "|".join(SEAM_CALLS) + r')\(\s*(?:title:\s*)?"((?:[^"\\\n]|\\.)*)"\s*[,)]')
ESCAPES = {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'", "0": "\0", "r": "\r"}

TERM = re.compile(
    r"(?i:memory[ -]?card|\bbios\b|\bps2\b|playstation[ -]?2\b|pcsx2|retroachievements|hardcore)"
    r"|\b[ABXY] (?i:button)",
)


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    return text


def swift_texts(root=FRONTEND):
    files = sorted(root.rglob("*.swift")) if root.is_dir() else []
    if not files:
        raise AssertionError("{0} holds no Swift file. FAILURE, not skip.".format(root))
    return {p.relative_to(ROOT).as_posix(): read(p) for p in files}


def unescape(literal):
    return re.sub(r"\\(.)", lambda m: ESCAPES.get(m.group(1), m.group(0)), literal)


def english_values(entry):
    """Every English string value an entry carries: its plain unit and any plural or device variation."""
    found = []

    def walk(node):
        if isinstance(node, dict):
            unit = node.get("stringUnit")
            if isinstance(unit, dict) and isinstance(unit.get("value"), str):
                found.append(unit["value"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk((entry.get("localizations") or {}).get("en"))
    return found


def catalog_problems(catalog_text, swift_texts):
    try:
        catalog = json.loads(catalog_text)
    except ValueError as error:
        return ["parse: the catalog is not JSON ({0})".format(error)]
    if not isinstance(catalog, dict) or catalog.get("sourceLanguage") != "en":
        return ["parse: the catalog's sourceLanguage is not en"]
    strings = catalog.get("strings")
    if not isinstance(strings, dict):
        return ["parse: the catalog has no strings object"]

    problems = []
    for key in sorted(strings):
        if not key.strip():
            problems.append("empty: the catalog holds an empty or whitespace key {0!r}".format(key))
    for name, text in sorted(swift_texts.items()):
        for found in SEAM.finditer(text):
            key = unescape(found.group(1))
            if key not in strings:
                problems.append("seam: {0} looks up {1!r} at run time and the catalog has no such key".format(name, key))
    for key in sorted(strings):
        entry = strings[key] if isinstance(strings[key], dict) else {}
        others = sorted(set(entry.get("localizations") or {}) - {"en"})
        if others:
            problems.append("locale: {0!r} carries {1}; English is the only shipped locale".format(key, ", ".join(others)))
        for text in [key] + english_values(entry):
            hit = TERM.search(text)
            if hit:
                problems.append("term: {0!r} names {1!r}, a PS2/PCSX2 or Xbox term".format(text, hit.group(0)))
    return problems


class TheCatalogHolds(unittest.TestCase):
    def test_the_real_files_have_no_problems(self):
        self.assertEqual([], catalog_problems(read(CATALOG), swift_texts()))

    def test_a_missing_catalog_raises(self):
        with self.assertRaises(FileNotFoundError):
            read(FRONTEND / "Resources" / "absent.xcstrings")


GOOD = json.dumps({
    "sourceLanguage": "en",
    "strings": {
        "Cancel": {},
        "Slot %d": {"localizations": {"en": {"stringUnit": {"state": "translated", "value": "Slot %d"}}}},
    },
    "version": "1.0",
}, indent=2)
SWIFT = {"a.swift": 'let t = settings.localized(\n    "Cancel"\n)\nlet u = settings.localized(title)\n'
                    'controlSlider("Slot %d", value: v)\nlet w = String(localized: "Not a seam")\n'}


class Mutations(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def prefixes(self, catalog_text, swift=SWIFT):
        return [p.split(":", 1)[0] for p in catalog_problems(catalog_text, swift)]

    def test_the_fixture_is_clean(self):
        self.assertEqual([], catalog_problems(GOOD, SWIFT))

    def test_mutation_parse(self):
        self.assertIn("parse", self.prefixes(self.edited(GOOD, '"sourceLanguage": "en"', '"sourceLanguage": "de"')))
        self.assertIn("parse", self.prefixes(self.edited(GOOD, '"version"', ',"version"')))

    def test_mutation_empty(self):
        self.assertIn("empty", self.prefixes(self.edited(GOOD, '"Cancel": {}', '"Cancel": {},\n    " ": {}')))

    def test_mutation_seam(self):
        swift = {"a.swift": self.edited(SWIFT["a.swift"], '"Cancel"', '"Do Not Ask Again"')}
        self.assertIn("seam", self.prefixes(GOOD, swift))
        swift = {"a.swift": self.edited(SWIFT["a.swift"], 'controlSlider("Slot %d"', 'controlSlider("Particle Amount"')}
        self.assertIn("seam", self.prefixes(GOOD, swift))

    def test_mutation_locale(self):
        changed = self.edited(GOOD, '"Cancel": {}',
                              '"Cancel": {"localizations": {"de": {"stringUnit": {"state": "translated", "value": "Abbrechen"}}}}')
        self.assertIn("locale", self.prefixes(changed))

    def test_mutation_term(self):
        self.assertIn("term", self.prefixes(self.edited(GOOD, '"value": "Slot %d"', '"value": "PS2 disc"')))
        self.assertIn("term", self.prefixes(self.edited(GOOD, '"Cancel": {}', '"Cancel": {},\n    "Press the A Button": {}')))
        self.assertEqual([], self.prefixes(self.edited(GOOD, '"Cancel": {}', '"Cancel": {},\n    "Press a button": {}')))


if __name__ == "__main__":
    unittest.main()
