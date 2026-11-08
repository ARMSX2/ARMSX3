# SPDX-License-Identifier: GPL-2.0-only
"""The submodule list in ios/scripts/bootstrap-submodules.sh classifies every .gitmodules entry exactly once with a
reason, the sections do not overlap, and the never-fetch and gated LLVM paths are where they must be."""

import re
import subprocess
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

GITMODULES = ROOT / ".gitmodules"

BOOTSTRAP = ROOT / "ios" / "scripts" / "bootstrap-submodules.sh"

# The list lives in the script's SUBMODULES here-document.
ALLOWLIST = BOOTSTRAP

SECTIONS = ("ALLOW", "NEVER", "NOT-NEEDED")

MARKER_GATED_LLVM = "<gated:with-llvm>"

LLVM_PATH = "3rdparty/llvm/llvm"

# The four forced by a blocker, pinned as data rather than read from the file under test.
MUST_BE_NEVER = {
    "3rdparty/libsdl-org/SDL": "USE_SDL and USE_SYSTEM_SDL are both off for iOS",
    "3rdparty/libusb/libusb": "3rdparty/libusb/os.cmake refuses the platform at configure time",
    "3rdparty/hidapi/hidapi": "hidapi_darwin needs IOKit/hid/IOHIDManager.h, absent from the iOS SDK",
    "3rdparty/opencv/opencv": "USE_SYSTEM_OPENCV is left ON so find_package deliberately fails",
}

QUIET_GROWTH = (
    "A dependency that arrives because somebody ran one more `git submodule update --init` to "
    "get past an error is a dependency nobody can later justify, and it is invisible in a diff. "
    "The pinned SHAs only mean something if the SET of "
    "pinned things is fixed too."
)

SECTION_HEADER = re.compile(r"^\[([A-Z-]+)\]\s*$")


def allowlist_text(path):
    """The body of the script's `<<'EOF'` here-document."""
    lines = path.read_text(encoding="utf-8").splitlines()
    start = next(i for i, line in enumerate(lines) if line.rstrip().endswith("<<'EOF'"))
    end = lines.index("EOF", start + 1)
    return "\n".join(lines[start + 1:end])


def parse_allowlist(path):
    """Return a list of (section, path, markers tuple, reason, line number).
    A separate parser from the script's awk, so the two agree on the file and not by sharing code."""
    entries = []
    section = None

    for number, raw in enumerate(allowlist_text(path).splitlines(), start=1):
        line = raw.rstrip("\r")

        if not line.strip():
            continue
        if line.lstrip().startswith("#"):
            continue

        header = SECTION_HEADER.match(line.strip())
        if header:
            section = header.group(1)
            continue

        reason = ""
        body = line
        if "#" in line:
            body, reason = line.split("#", 1)
            reason = reason.strip()

        fields = body.split()
        if not fields:
            continue

        entries.append((section, fields[0], tuple(fields[1:]), reason, number))

    return entries


def strip_shell_comments(source):
    """Drop shell comments, so the bootstrap script's prose cannot satisfy a check.
    A `#` starts a comment only after whitespace, so `$#` and `${#array[@]}` stay."""
    kept = []
    for line in source.splitlines():
        if re.match(r"^\s*#", line):
            continue
        kept.append(re.sub(r"(?<=\s)#.*$", "", line))
    return "\n".join(kept)


def gitmodules_paths():
    """Every `path = ...` value in .gitmodules, in file order."""
    found = []
    for line in GITMODULES.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\s*path\s*=\s*(\S+)\s*$", line)
        if match:
            found.append(match.group(1))
    return found


def initialised_submodules():
    """Submodule paths git reports as initialised (any status prefix but '-')."""
    result = subprocess.run(
        ["git", "submodule", "status"],
        cwd=str(ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )

    paths = []
    for line in result.stdout.decode("utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        state = line[0]
        fields = line[1:].split()
        if len(fields) < 2:
            continue
        if state != "-":
            paths.append(fields[1])
    return paths


class SubmoduleAllowlist(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Fail rather than skip when an input cannot be resolved.
        if not GITMODULES.is_file():
            raise AssertionError("{0} does not exist.".format(GITMODULES))
        if not BOOTSTRAP.is_file():
            raise AssertionError(
                "{0} does not exist, so nothing consumes the allowlist.".format(BOOTSTRAP)
            )

        cls.entries = parse_allowlist(ALLOWLIST)
        cls.by_section = {}
        for section, path, _markers, _reason, _line in cls.entries:
            cls.by_section.setdefault(section, []).append(path)

        cls.markers = {}
        for _section, path, markers, _reason, _line in cls.entries:
            cls.markers.setdefault(path, set()).update(markers)

        cls.gitmodules = gitmodules_paths()
        cls.initialised = initialised_submodules()
        cls.bootstrap_source = strip_shell_comments(BOOTSTRAP.read_text(encoding="utf-8"))

    # ------------------------------------------------------------------ 1. the file has a shape ---

    def test_all_three_sections_are_present_and_non_empty(self):
        for section in SECTIONS:
            with self.subTest(section=section):
                self.assertIn(
                    section,
                    self.by_section,
                    "{0} has no [{1}] section. All three are required: an absent section is "
                    "indistinguishable from an empty one, and the reconciliation below cannot "
                    "tell 'nothing is forbidden' from 'nobody wrote the forbidden list'.".format(
                        ALLOWLIST, section
                    ),
                )
                self.assertNotEqual([], self.by_section.get(section, []))

        unknown = sorted(set(self.by_section) - set(SECTIONS))
        self.assertEqual(
            [],
            unknown,
            "{0} carries section(s) {1}, which no consumer reads. A fourth section is a set of "
            "submodules classified into a category nothing enforces.".format(ALLOWLIST, unknown),
        )

    # ------------------------------------------------------------------- 2. every entry is reasoned ---

    def test_every_entry_carries_a_reason(self):
        for section, path, _markers, reason, line in self.entries:
            with self.subTest(path=path):
                self.assertNotEqual(
                    "",
                    reason,
                    "{0}:{1} lists '{2}' in [{3}] with no reason after the '#'. {4}".format(
                        ALLOWLIST, line, path, section, QUIET_GROWTH
                    ),
                )

    # ----------------------------------------------------------------------- 3. the sections are disjoint ---

    def test_the_three_sections_share_no_path(self):
        for first in range(len(SECTIONS)):
            for second in range(first + 1, len(SECTIONS)):
                left, right = SECTIONS[first], SECTIONS[second]
                shared = sorted(
                    set(self.by_section.get(left, [])) & set(self.by_section.get(right, []))
                )
                with self.subTest(pair=(left, right)):
                    self.assertEqual(
                        [],
                        shared,
                        "{0} lists {1} in both [{2}] and [{3}]. A path that is both fetched and "
                        "never fetched makes the file unreadable and lets whichever consumer "
                        "reads first win.".format(ALLOWLIST, shared, left, right),
                    )

    def test_no_path_is_listed_twice_within_one_section(self):
        for section in SECTIONS:
            paths = self.by_section.get(section, [])
            duplicates = sorted({p for p in paths if paths.count(p) > 1})
            with self.subTest(section=section):
                self.assertEqual([], duplicates, "{0} lists {1} twice in [{2}].".format(
                    ALLOWLIST, duplicates, section))

    def test_the_four_forced_never_fetch_paths_are_in_never_and_not_in_allow(self):
        never = set(self.by_section.get("NEVER", []))
        allow = set(self.by_section.get("ALLOW", []))

        for path in sorted(MUST_BE_NEVER):
            with self.subTest(path=path):
                self.assertIn(
                    path,
                    never,
                    "'{0}' is not in [NEVER]. It is pinned in this test AS DATA because its "
                    "absence is FORCED, not a scope choice: {1}.".format(
                        path, MUST_BE_NEVER[path]
                    ),
                )
                self.assertNotIn(path, allow)

    # -------------------------------------------------- 4. two-sided reconciliation with .gitmodules ---

    def test_every_allowlisted_path_is_a_real_submodule(self):
        known = set(self.gitmodules)
        for section, path, _markers, _reason, line in self.entries:
            with self.subTest(path=path):
                self.assertIn(
                    path,
                    known,
                    "{0}:{1} classifies '{2}' in [{3}], but .gitmodules has no submodule at "
                    "that path. Either the path is misspelled -- in which case the entry "
                    "governs nothing -- or a submodule was removed and its classification was "
                    "left behind.".format(ALLOWLIST, line, path, section),
                )

    def test_every_submodule_is_classified_exactly_once(self):
        classified = {}
        for section, path, _markers, _reason, _line in self.entries:
            classified.setdefault(path, []).append(section)

        missing = sorted(set(self.gitmodules) - set(classified))
        self.assertEqual(
            [],
            missing,
            "{0} submodule(s) in .gitmodules are classified nowhere in {1}: {2}. {3}".format(
                len(missing), ALLOWLIST, missing, QUIET_GROWTH
            ),
        )

        multiple = sorted(p for p in classified if len(classified[p]) > 1)
        self.assertEqual([], multiple, "{0} classifies {1} more than once.".format(
            ALLOWLIST, multiple))

    def test_the_totals_reconcile_without_double_counting(self):
        section_total = sum(len(self.by_section.get(section, [])) for section in SECTIONS)
        distinct = len({path for _s, path, _m, _r, _l in self.entries})

        self.assertEqual(
            distinct,
            section_total,
            "{0} has {1} entries across its three sections but only {2} distinct paths, so "
            "something is counted twice.".format(ALLOWLIST, section_total, distinct),
        )
        self.assertEqual(
            len(self.gitmodules),
            section_total,
            "{0} classifies {1} path(s); .gitmodules declares {2} submodule(s). The two sides "
            "must reconcile exactly, because it is the LEFTOVER on either side that is the "
            "unrecorded dependency.".format(ALLOWLIST, section_total, len(self.gitmodules)),
        )

    # ------------------------------------------------------- 5. nothing is initialised off the list ---

    # --------------------------------------------------------------- 6. the marker cannot be abused ---

    # ------------------------------------------------------------------------ 7. LLVM is gated ---

    def test_llvm_is_allowed_but_gated_behind_an_explicit_flag(self):
        self.assertIn(
            LLVM_PATH,
            self.by_section.get("ALLOW", []),
            "'{0}' is not in [ALLOW]. The ios-llvm configuration needs it, so it is allowed -- "
            "what it is not is DEFAULT, which is what the gate marker expresses.".format(
                LLVM_PATH
            ),
        )
        self.assertIn(
            MARKER_GATED_LLVM,
            self.markers.get(LLVM_PATH, set()),
            "'{0}' is in [ALLOW] without the {1} marker, so the default bootstrap would fetch "
            "a multi-gigabyte checkout that the interpreter configuration does not build.".format(
                LLVM_PATH, MARKER_GATED_LLVM
            ),
        )

        gated = [p for p in self.markers if MARKER_GATED_LLVM in self.markers[p]]
        self.assertEqual(
            [LLVM_PATH],
            sorted(gated),
            "{0} is gated behind --with-llvm in {1}; exactly one entry should be.".format(
                sorted(gated), ALLOWLIST
            ),
        )

        self.assertTrue(
            "--with-llvm" in self.bootstrap_source,
            "{0} does not mention --with-llvm in its code, so the gate marker in {1} gates "
            "nothing.".format(BOOTSTRAP, ALLOWLIST),
        )

    # ------------------------------------------- 8. the script reads this file and guards its re-run ---


if __name__ == "__main__":
    unittest.main()
