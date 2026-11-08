# SPDX-License-Identifier: GPL-2.0-only
"""The native overlay has its fonts and icons on iOS: get_font_dirs returns real absolute font directories, project.yml
bundles bin/Icons with every icon the overlay names, and any bundled font has a licence beside it.
It does not show that iOS lets the app read those directories or that the menu draws text."""

import os
import re
import unittest
from pathlib import Path

from source_helpers import without_c_comments as without_comments


ROOT = Path(os.environ.get("ARMSX3_TREE_ROOT", str(Path(__file__).resolve().parents[2])))

CALLBACKS = ROOT / "ios" / "bridge" / "callbacks.cpp"
PROJECT = ROOT / "ios" / "project.yml"
ICONS = ROOT / "bin" / "Icons"
HOME_ICONS = ROOT / "rpcs3" / "Emu" / "RSX" / "Overlays" / "HomeMenu" / "overlay_home_icons.cpp"

# Icons a device console reported missing, relative to Icons/ui/, pinned so a renamed upstream file shows up here.
REPORTED_MISSING = (
    "L1.png", "L2.png", "R1.png", "R2.png", "circle.png", "cross.png", "square.png",
    "triangle.png", "start.png", "select.png", "save.png", "new.png", "spinner-24.png",
    "fade_top.png", "fade_bottom.png",
    "home/32/bug-solid.png", "home/32/circle-left-solid.png", "home/32/display-solid.png",
    "home/32/floppy-disk-solid.png", "home/32/gamepad-solid.png", "home/32/gauge-solid.png",
    "home/32/headphones-solid.png", "home/32/maximize-solid.png", "home/32/power-off-solid.png",
    "home/32/rotate-left-solid.png", "home/32/screenshot.png", "home/32/settings.png",
    "home/32/sliders-solid.png", "home/32/trophy-solid.png", "home/32/video-camera.png",
)

IOS_FONT_DIRS = ("/System/Library/Fonts/Core/", "/System/Library/Fonts/WebFonts/")
FONT_SUFFIXES = (".ttf", ".ttc", ".otf")

FIELD = re.compile(r"\.get_font_dirs\s*=\s*ARMSX3_([A-Z]+)\s*\(")
FOLDER_ENTRY = re.compile(r"^\s*- path: \.\./bin/Icons\s*\n\s*type: folder\s*$", re.M)

WHY = ("Without font directories and the bundled Icons/ui set, the "
       "home menu draws its panel with no entry text on the phone.")


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise ValueError("{0} is empty".format(path))
    return text


def font_dirs_problems(code):
    """For comment-stripped callbacks.cpp."""
    m = FIELD.search(code)
    if not m:
        return ["font-field: no `.get_font_dirs = ARMSX3_<LABEL>(` in the table"]
    if m.group(1) != "REAL":
        return ["font-label: get_font_dirs is ARMSX3_{0}, not ARMSX3_REAL".format(m.group(1))]
    end = code.find("}),", m.end())
    body = code[m.end():end]
    dirs = re.findall(r'"([^"]*)"', body)
    if not dirs:
        return ["font-empty: get_font_dirs returns no directory"]
    found = ["font-shape: {0!r} is not absolute with a trailing '/'".format(d)
             for d in dirs if not (d.startswith("/") and d.endswith("/"))]
    if not any(d in IOS_FONT_DIRS for d in dirs):
        found.append("font-ios: none of {0} is an iOS font directory {1}".format(dirs, IOS_FONT_DIRS))
    return found


def project_problems(text):
    if not FOLDER_ENTRY.search(text):
        return ["bundle-entry: ios/project.yml has no `- path: ../bin/Icons` folder reference"]
    return []


def icon_problems(present, wanted):
    """`present`: set of paths relative to Icons/ui. `wanted`: names that must be in it."""
    return ["icon-missing: Icons/ui/{0}".format(n) for n in sorted(set(wanted) - set(present))]


def home_icon_names(code):
    return ["home/32/" + n for n in re.findall(r'"([A-Za-z0-9_-]+\.png)"', code)]


def licence_problems(files):
    """`files`: iterable of Paths. A font needs a LICENSE*/LICENCE* sibling."""
    found = []
    for f in files:
        if f.suffix.lower() in FONT_SUFFIXES:
            if not any(s.name.upper().startswith(("LICENSE", "LICENCE")) for s in f.parent.iterdir()):
                found.append("font-unlicensed: {0}".format(f))
    return found


def bundled_files():
    """Files under ios/ (minus the gitignored build tree) and bin/Icons/."""
    out = []
    for top in (ROOT / "ios", ICONS):
        for dirpath, dirnames, filenames in os.walk(str(top)):
            if top.name == "ios":
                dirnames[:] = [d for d in dirnames if not (Path(dirpath) == top and d == "build")]
            out.extend(Path(dirpath) / n for n in filenames)
    return out


def present_icons():
    ui = ICONS / "ui"
    return {str(p.relative_to(ui)) for p in ui.rglob("*") if p.is_file()}


class FontDirsAreReal(unittest.TestCase):
    def test_get_font_dirs_returns_ios_system_dirs(self):
        self.assertEqual([], font_dirs_problems(without_comments(read(CALLBACKS))), WHY)


class TheBundleCarriesTheIcons(unittest.TestCase):
    def test_project_has_the_folder_reference(self):
        self.assertEqual([], project_problems(read(PROJECT)), WHY)

    def test_icons_hold_every_reported_name(self):
        self.assertEqual([], icon_problems(present_icons(), REPORTED_MISSING), WHY)

    def test_icons_hold_every_home_menu_name(self):
        names = home_icon_names(read(HOME_ICONS))
        self.assertTrue(names, "no .png literal found in {0}".format(HOME_ICONS))
        self.assertEqual([], icon_problems(present_icons(), names), WHY)


class BundledFontsAreLicensed(unittest.TestCase):
    def test_every_bundled_font_has_a_licence(self):
        self.assertEqual([], licence_problems(bundled_files()))


class Mutations(unittest.TestCase):
    """Each method makes ONE edit and shows the matching check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = without_comments(read(CALLBACKS))
        cls.project = read(PROJECT)

    def edited(self, text, old, new):
        self.assertTrue(old in text, "mutation anchor {0!r} not found".format(old))
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def has(self, prefix, found):
        self.assertTrue(any(p.startswith(prefix) for p in found), "{0} not in {1}".format(prefix, found))

    def test_mutation_back_to_owned(self):
        self.has("font-label", font_dirs_problems(self.edited(
            self.code, "ARMSX3_REAL(get_font_dirs,", "ARMSX3_OWNED(8, get_font_dirs,")))

    def test_mutation_empty_list(self):
        m = FIELD.search(self.code)
        body = self.code[m.end():self.code.find("}),", m.end())]
        self.has("font-empty", font_dirs_problems(self.edited(
            self.code, body, re.sub(r'"[^"]*",?', "", body))))

    def test_mutation_ios_dirs_removed(self):
        code = self.code
        for d in IOS_FONT_DIRS:
            code = self.edited(code, '"{0}"'.format(d), '"/opt/fonts/"')
        self.has("font-ios", font_dirs_problems(code))

    def test_mutation_relative_dir(self):
        self.has("font-shape", font_dirs_problems(self.edited(
            self.code, '"/System/Library/Fonts/Core/"', '"Fonts/Core/"')))

    def test_mutation_folder_reference_dropped(self):
        self.has("bundle-entry", project_problems(self.edited(
            self.project, "type: folder\n        buildPhase: resources", "buildPhase: resources")))

    def test_mutation_icon_removed(self):
        present = present_icons()
        present.discard("cross.png")
        self.has("icon-missing", icon_problems(present, REPORTED_MISSING))

    def test_mutation_unlicensed_font(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            font = Path(tmp) / "Some.ttf"
            font.write_bytes(b"\0\1\0\0")
            self.has("font-unlicensed", licence_problems([font]))
            (Path(tmp) / "LICENSE.txt").write_text("x", encoding="utf-8")
            self.assertEqual([], licence_problems([font]))


if __name__ == "__main__":
    unittest.main()
