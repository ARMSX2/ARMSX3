#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The idevice archive is force-loaded into the JITEnabler extension through its own xcconfig, its paths lie under the
gitignored ios/3rdparty/ tree, and nothing in the ARMSX3 target or shared settings names it.
It does not check that the archive exists, links or holds any symbol."""

import re
import unittest
from pathlib import Path

from source_helpers import targets_of, yaml_without_comments


# ios/tests/ is one level below ios/.
IOS_DIR = Path(__file__).resolve().parents[1]
PROJECT_YML = IOS_DIR / "project.yml"
XCCONFIG = IOS_DIR / "jit-enabler" / "JITEnablerExtension.xcconfig"

HOST_TARGET = "ARMSX3"
EXTENSION_TARGET = "JITEnabler"
XCCONFIG_NAME = "JITEnablerExtension.xcconfig"
XCCONFIG_REF = "jit-enabler/" + XCCONFIG_NAME
ARCHIVE = "libidevice_ffi.a"
# Relative to ios/ ($(SRCROOT)); .gitignore excludes ios/3rdparty/.
GITIGNORED_PREFIX = "$(SRCROOT)/3rdparty/"
IDEVICE_TREE = "3rdparty/jit-enabler"
CONFIGURATIONS = ("Debug", "Release")
SEARCH_SETTINGS = ("HEADER_SEARCH_PATHS", "SWIFT_INCLUDE_PATHS")


def read_required(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def xcconfig_settings(text):
    """{setting: value} from an xcconfig, `//` comments stripped. Raises when nothing is set."""
    settings = {}
    for line in text.splitlines():
        line = line.split("//", 1)[0].strip()
        match = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
        if match:
            settings[match.group(1)] = match.group(2).strip()
    if not settings:
        raise AssertionError("the xcconfig sets nothing; the guard cannot pass over nothing")
    return settings


def force_load_paths(value):
    """Every path following a -force_load in an OTHER_LDFLAGS value, quotes removed."""
    return [path.strip('"') for path in re.findall(r'-force_load\s+("[^"]+"|\S+)', value)]


def xcconfig_problems(text):
    settings = xcconfig_settings(text)
    problems = []
    loads = [path for path in force_load_paths(settings.get("OTHER_LDFLAGS", "")) if path.endswith("/" + ARCHIVE)]
    if not loads:
        problems.append("force-load: OTHER_LDFLAGS has no -force_load of a path ending {0}".format(ARCHIVE))
    for path in loads:
        if not path.startswith(GITIGNORED_PREFIX):
            problems.append("archive-path: the force_load path {0} is not under the gitignored {1}".format(path, GITIGNORED_PREFIX))
    for key in SEARCH_SETTINGS:
        paths = [path.strip('"') for path in settings.get(key, "").split() if path != "$(inherited)"]
        if not any(path.startswith(GITIGNORED_PREFIX + "jit-enabler/") for path in paths):
            problems.append("search-paths: {0} names no path under {1}jit-enabler/".format(key, GITIGNORED_PREFIX))
        for path in paths:
            if not path.startswith(GITIGNORED_PREFIX):
                problems.append("search-paths: {0} names {1}, outside the gitignored tree".format(key, path))
    return sorted(problems)


def project_problems(yaml_text):
    """`yaml_text` is comment-stripped project.yml."""
    targets = targets_of(yaml_text)
    if EXTENSION_TARGET not in targets:
        raise AssertionError("project.yml declares no {0} target; the link guard has nothing to check".format(EXTENSION_TARGET))
    if HOST_TARGET not in targets:
        raise AssertionError("project.yml declares no {0} target".format(HOST_TARGET))

    problems = []
    block = targets[EXTENSION_TARGET]
    for configuration in CONFIGURATIONS:
        if not re.search(r"(?m)^      " + configuration + r":\s*" + re.escape(XCCONFIG_REF) + r"\s*$", block) or not re.search(r"(?m)^    configFiles:\s*$", block):
            problems.append("applied: the {0} target does not apply {1} to {2} through configFiles".format(EXTENSION_TARGET, XCCONFIG_REF, configuration))

    host = targets[HOST_TARGET]
    for token in (ARCHIVE, XCCONFIG_NAME, IDEVICE_TREE):
        if token in host:
            problems.append("harness-clean: the {0} target names {1}; the archive must never link into the app binary".format(HOST_TARGET, token))

    for token in (ARCHIVE, XCCONFIG_NAME, IDEVICE_TREE):
        if yaml_text.count(token) != block.count(token):
            problems.append("elsewhere: {0} appears outside the {1} target in project.yml".format(token, EXTENSION_TARGET))
    return sorted(problems)


class TheArchiveLinksIntoTheHelperOnly(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.xcconfig = read_required(XCCONFIG)
        cls.yaml = yaml_without_comments(read_required(PROJECT_YML))

    def test_the_xcconfig_force_loads_the_gitignored_archive(self):
        self.assertEqual([], xcconfig_problems(self.xcconfig))

    def test_the_xcconfig_is_applied_to_the_helper_and_never_the_app(self):
        self.assertEqual([], project_problems(self.yaml))

    def test_control_the_force_load_parser_sees_a_quoted_path(self):
        self.assertEqual(["/x/" + ARCHIVE], force_load_paths('$(inherited) -force_load "/x/' + ARCHIVE + '"'))

    def test_edge_empty_and_missing_inputs_raise(self):
        with self.assertRaises(AssertionError):
            xcconfig_settings("// only a comment\n")
        with self.assertRaises(AssertionError):
            project_problems(re.sub(r"(?m)^  " + EXTENSION_TARGET + r":", "  Renamed:", self.yaml))
        with self.assertRaises(AssertionError):
            read_required(IOS_DIR / "jit-enabler" / "no-such.xcconfig")


class TheLinkGuardFailsOnAMutation(unittest.TestCase):
    CONFIG_LINES = "".join("      {0}: {1}\n".format(name, XCCONFIG_REF) for name in CONFIGURATIONS)

    @classmethod
    def setUpClass(cls):
        cls.xcconfig = read_required(XCCONFIG)
        cls.yaml = yaml_without_comments(read_required(PROJECT_YML))

    def mutated(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def assert_names(self, problems, check):
        self.assertTrue(any(p.startswith(check + ":") for p in problems), "expected {0} in {1}".format(check, problems))

    def test_mutation_the_force_load_removed_is_caught(self):
        text = self.mutated(self.xcconfig, "-force_load ", "")
        self.assert_names(xcconfig_problems(text), "force-load")

    def test_mutation_the_xcconfig_moved_onto_harness_is_caught(self):
        text = self.mutated(self.yaml, "    configFiles:\n" + self.CONFIG_LINES, "")
        text = self.mutated(text, "  " + HOST_TARGET + ":\n", "  " + HOST_TARGET + ":\n    configFiles:\n" + self.CONFIG_LINES)
        problems = project_problems(text)
        self.assert_names(problems, "applied")
        self.assert_names(problems, "harness-clean")

    def test_mutation_an_archive_reference_added_to_harness_is_caught(self):
        text = self.mutated(self.yaml, "  " + HOST_TARGET + ":\n", "  " + HOST_TARGET + ":\n    settings:\n      base:\n        OTHER_LDFLAGS: -force_load 3rdparty/jit-enabler/out/lib/" + ARCHIVE + "\n")
        self.assert_names(project_problems(text), "harness-clean")

    def test_mutation_the_archive_pointed_at_a_tracked_directory_is_caught(self):
        text = self.mutated(self.xcconfig, '"$(SRCROOT)/3rdparty/jit-enabler/out/lib/', '"$(SRCROOT)/jit-enabler/lib/')
        self.assert_names(xcconfig_problems(text), "archive-path")

    def test_mutation_a_search_path_dropped_is_caught(self):
        line = [line for line in self.xcconfig.splitlines() if line.startswith("SWIFT_INCLUDE_PATHS")][0]
        text = self.mutated(self.xcconfig, line, "SWIFT_INCLUDE_PATHS = $(inherited)")
        self.assert_names(xcconfig_problems(text), "search-paths")

    def test_mutation_a_project_level_application_is_caught(self):
        self.assertIn("\nsettings:\n  base:\n", self.yaml)
        text = self.mutated(self.yaml, "\nsettings:\n  base:\n", "\nsettings:\n  base:\n    LIBRARY_SEARCH_PATHS: 3rdparty/jit-enabler/out/lib\n")
        self.assert_names(project_problems(text), "elsewhere")


if __name__ == "__main__":
    unittest.main(verbosity=2)
