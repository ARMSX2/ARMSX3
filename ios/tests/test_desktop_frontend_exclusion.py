# SPDX-License-Identifier: GPL-2.0-only
"""The iOS CMake seam keeps its shape: the dependency hook runs before 3rdparty, the Homebrew prefix is fenced,
one option carries the desktop-frontend exclusion, and the HID units stay out of the iOS list.
CMake comments are stripped first, so prose naming these cannot satisfy a check."""

import re
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

ROOT_CMAKE = ROOT / "CMakeLists.txt"
RPCS3_CMAKE = ROOT / "rpcs3" / "CMakeLists.txt"
IOS_CMAKE = ROOT / "ios" / "CMakeLists.txt"

# The three rpcs3/CMakeLists.txt sites meaning "no desktop frontend", as (name, regex for the site's line).
EXCLUSION_SITES = (
    ("the Qt find", re.compile(r"include\s*\(.*qt6\.cmake")),
    ("the rpcs3qt subdirectory", re.compile(r"add_subdirectory\s*\(\s*rpcs3qt\s*\)")),
    ("the rpcs3_lib target", re.compile(r"add_library\s*\(\s*rpcs3_lib\b")),
)

OPTION_NAME = "BUILD_DESKTOP_FRONTEND"

# The six rpcs3/Input/ units that cannot build for iOS, pinned as data so a list that regrows one fails.
UNBUILDABLE_FOR_IOS = (
    "hid_pad_handler.cpp",
    "ds3_pad_handler.cpp",
    "ds4_pad_handler.cpp",
    "dualsense_pad_handler.cpp",
    "skateboard_pad_handler.cpp",
    "ps_move_handler.cpp",
)

# Link inputs iOS must not acquire: hidapi cannot build there and libusb has no USB-host role.
FORBIDDEN_LINK_TARGETS = ("hidapi", "libusb")

WHY_LATE = (
    "This is a shape, not a behaviour, and when it breaks it breaks late: the configure "
    "still succeeds and the failure surfaces as a linker error, a wrong-architecture "
    "object, or a silently unused cache variable."
)


def strip_comments(text):
    """Remove CMake comments (# to end of line), preserving line numbering.
    A '#' inside a quoted argument is not respected, so over-stripping can only cause a false failure."""
    return "\n".join(re.sub(r"#.*$", "", line) for line in text.splitlines())


def read_code(path):
    """The comment-stripped text of a CMake file, as a list of lines (1-based index 0)."""
    return strip_comments(path.read_text(encoding="utf-8")).splitlines()


def enclosing_conditions(lines, target_index):
    """The if()/elseif() conditions enclosing lines[target_index], outermost first.
    Expects each keyword at the start of its own line, as these three files write them."""
    stack = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if index == target_index:
            return list(stack)
        opened = re.match(r"^if\s*\((.*)$", stripped)
        if opened:
            stack.append(collect_condition(lines, index))
            continue
        if re.match(r"^elseif\s*\(", stripped):
            if stack:
                stack[-1] = collect_condition(lines, index)
            continue
        if re.match(r"^else\s*\(", stripped):
            if stack:
                stack[-1] = ""
            continue
        if re.match(r"^endif\s*\(", stripped):
            if stack:
                stack.pop()
            continue
    return []


def collect_condition(lines, index):
    """The condition text of the if()/elseif() starting at lines[index], joined across continuation lines."""
    text = lines[index]
    depth = text.count("(") - text.count(")")
    cursor = index
    while depth > 0 and cursor + 1 < len(lines):
        cursor += 1
        text += " " + lines[cursor]
        depth += lines[cursor].count("(") - lines[cursor].count(")")
    opened = text.find("(")
    closed = text.rfind(")")
    if opened == -1 or closed == -1 or closed < opened:
        return text
    return text[opened + 1:closed]


def tests_ios(condition):
    """True when a CMake condition names the IOS variable as a whole word."""
    return re.search(r"\bIOS\b", condition) is not None


def find_line(lines, pattern):
    """The 0-based index of the first line matching pattern, or None."""
    for index, line in enumerate(lines):
        if pattern.search(line):
            return index
    return None


class RootWiring(unittest.TestCase):
    """ios/ is added from the repo root behind one guarded line."""

    @classmethod
    def setUpClass(cls):
        cls.lines = read_code(ROOT_CMAKE)

    def test_add_subdirectory_ios_appears_exactly_once(self):
        occurrences = [
            index
            for index, line in enumerate(self.lines)
            if re.search(r"add_subdirectory\s*\(\s*ios\s*\)", line)
        ]
        self.assertEqual(
            1,
            len(occurrences),
            "the root CMakeLists.txt has {0} add_subdirectory(ios) call(s), expected "
            "exactly 1. The seam asks for a SINGLE guarded line, mirroring the android "
            "block, so that desktop and Android configures are byte-for-byte "
            "unaffected. Two calls would add the directory twice and redefine its "
            "targets.".format(len(occurrences)),
        )

    def test_add_subdirectory_ios_is_inside_a_block_testing_ios(self):
        index = find_line(self.lines, re.compile(r"add_subdirectory\s*\(\s*ios\s*\)"))
        self.assertIsNotNone(index, "no add_subdirectory(ios) call in the root CMakeLists.txt")
        conditions = enclosing_conditions(self.lines, index)
        self.assertTrue(
            any(tests_ios(condition) for condition in conditions),
            "add_subdirectory(ios) is not inside a block testing IOS; the enclosing "
            "conditions are {0}. Unguarded, the iOS subdirectory is processed on every "
            "desktop and Android configure, which appends the iOS Input subset to "
            "rpcs3_emu everywhere and collides with android/CMakeLists.txt's own "
            "list.".format(conditions or "none"),
        )

    def test_the_ios_include_hook_runs_before_the_3rdparty_subdirectory(self):
        # Upstream's find_package calls in 3rdparty/ read the cache variables the iOS modules prime, so priming must come first.
        hook = find_line(self.lines, re.compile(r"include\s*\(.*ios/cmake/"))
        third_party = find_line(
            self.lines, re.compile(r"add_subdirectory\s*\(\s*3rdparty\s*\)")
        )
        self.assertIsNotNone(
            hook,
            "no include() of an ios/cmake/ module in the root CMakeLists.txt. That hook "
            "is where the iOS Vulkan, FFmpeg and LLVM cache variables are primed; "
            "without it every one of upstream's find modules is left to guess.",
        )
        self.assertIsNotNone(
            third_party, "no add_subdirectory(3rdparty) in the root CMakeLists.txt"
        )
        self.assertLess(
            hook,
            third_party,
            "the ios/cmake/ include() hook is at line {0} and add_subdirectory(3rdparty) "
            "is at line {1}: the hook runs AFTER the directory whose find modules it "
            "exists to prime, so the cache variables it sets are never read. {2}".format(
                hook + 1, third_party + 1, WHY_LATE
            ),
        )

    def test_the_ios_include_hook_is_guarded_on_ios(self):
        index = find_line(self.lines, re.compile(r"include\s*\(.*ios/cmake/"))
        self.assertIsNotNone(index, "no include() of an ios/cmake/ module in the root")
        conditions = enclosing_conditions(self.lines, index)
        self.assertTrue(
            any(tests_ios(condition) for condition in conditions),
            "the ios/cmake/ include() hook is not inside a block testing IOS; the "
            "enclosing conditions are {0}. It would then run on desktop and Android "
            "configures too.".format(conditions or "none"),
        )

    def test_the_homebrew_prefix_append_excludes_ios(self):
        # On iOS APPLE is true and CMAKE_OSX_ARCHITECTURES is arm64, so upstream's condition would prepend the host's Homebrew tree.
        index = find_line(
            self.lines, re.compile(r"list\s*\(\s*APPEND\s+CMAKE_PREFIX_PATH.*homebrew")
        )
        self.assertIsNotNone(
            index,
            "the /opt/homebrew CMAKE_PREFIX_PATH append is gone from the root "
            "CMakeLists.txt. If upstream removed it the guard below is moot and this "
            "test should be retired deliberately, not left to pass vacuously.",
        )
        conditions = enclosing_conditions(self.lines, index)
        self.assertTrue(
            any(
                re.search(r"\bNOT\s+IOS\b", condition) for condition in conditions
            ),
            "the /opt/homebrew prefix append is not fenced with NOT IOS; the enclosing "
            "conditions are {0}. On an iOS cross-configure every find_package reached "
            "from 3rdparty/ -- Vulkan, CURL, ZLIB, SDL3, OpenCV -- can then resolve a "
            "host macOS dylib into an arm64-ios link. {1}".format(
                conditions or "none", WHY_LATE
            ),
        )


class DesktopFrontendExclusion(unittest.TestCase):
    """One named option carries the exclusion for Android, iOS and the Mac leg."""

    @classmethod
    def setUpClass(cls):
        cls.lines = read_code(RPCS3_CMAKE)

    def test_all_three_exclusion_sites_test_the_option(self):
        for name, pattern in EXCLUSION_SITES:
            with self.subTest(site=name):
                index = find_line(self.lines, pattern)
                self.assertIsNotNone(
                    index,
                    "{0} was not found in rpcs3/CMakeLists.txt at all. If upstream moved "
                    "it, this guard must be re-pointed rather than allowed to pass on a "
                    "site that no longer exists.".format(name),
                )
                conditions = enclosing_conditions(self.lines, index)
                self.assertTrue(
                    any(OPTION_NAME in condition for condition in conditions),
                    "{0} is not inside a block testing {1}; the enclosing conditions are "
                    "{2}. All three sites mean the same concept, and a configuration "
                    "that excludes two of the three fails at Qt with an error that "
                    "names neither this file nor the option. {3}".format(
                        name, OPTION_NAME, conditions or "none", WHY_LATE
                    ),
                )

    def test_the_option_is_declared_with_a_default(self):
        declared = find_line(
            self.lines, re.compile(r"option\s*\(\s*" + OPTION_NAME + r"\b")
        )
        self.assertIsNotNone(
            declared,
            "{0} is used but never declared with option() in rpcs3/CMakeLists.txt. An "
            "undeclared variable in a CMake if() is false, so every exclusion site "
            "would silently take the excluded branch and a plain desktop build would "
            "stop producing the rpcs3 executable.".format(OPTION_NAME),
        )

    def test_the_option_defaults_on(self):
        index = find_line(self.lines, re.compile(r"option\s*\(\s*" + OPTION_NAME + r"\b"))
        self.assertIsNotNone(index, "{0} is never declared".format(OPTION_NAME))
        declaration = collect_condition(self.lines, index)
        self.assertRegex(
            declaration.strip(),
            r"\bON\s*$",
            "{0} does not default to ON. The default is what keeps a plain "
            "desktop configure byte-for-byte what it was; only ANDROID and IOS are "
            "meant to force it off.".format(OPTION_NAME),
        )

    def test_the_option_is_forced_off_for_android_and_for_ios(self):
        forced = find_line(
            self.lines,
            re.compile(r"set\s*\(\s*" + OPTION_NAME + r"\s+OFF\b"),
        )
        self.assertIsNotNone(
            forced,
            "nothing forces {0} OFF in rpcs3/CMakeLists.txt. Android and iOS would then "
            "have to pass -D{0}=OFF by hand on every configure, and forgetting it fails "
            "at the Qt find rather than at the platform check.".format(OPTION_NAME),
        )
        conditions = enclosing_conditions(self.lines, forced)
        joined = " ".join(conditions)
        self.assertRegex(
            joined,
            r"\bANDROID\b",
            "the {0} force-OFF is not conditioned on ANDROID; the enclosing conditions "
            "are {1}.".format(OPTION_NAME, conditions or "none"),
        )
        self.assertTrue(
            tests_ios(joined),
            "the {0} force-OFF is not conditioned on IOS; the enclosing conditions are "
            "{1}. Without it an iOS configure tries to find Qt.".format(
                OPTION_NAME, conditions or "none"
            ),
        )

    def test_the_x11_and_wayland_guard_is_left_alone(self):
        # This one guards DATADIR, X11 and Wayland and already excludes Apple, so it is pinned rather than converted.
        self.assertIsNotNone(
            find_line(
                self.lines,
                re.compile(r"if\s*\(\s*UNIX\s+AND\s+NOT\s+APPLE\s+AND\s+NOT\s+ANDROID\s*\)"),
            ),
            "the `UNIX AND NOT APPLE AND NOT ANDROID` guard is gone from "
            "rpcs3/CMakeLists.txt. It guards DATADIR, X11 and Wayland -- not the desktop "
            "frontend -- and it already excludes Apple, so the exclusion must not have "
            "touched it.",
        )


class IosInputSubset(unittest.TestCase):
    """The hand-listed rpcs3/Input/ subset is real, and is not Android's."""

    @classmethod
    def setUpClass(cls):
        cls.lines = read_code(IOS_CMAKE)
        cls.code = "\n".join(cls.lines)

    def test_the_ios_cmakelists_exists(self):
        self.assertTrue(
            IOS_CMAKE.is_file(),
            "{0} is missing. It is the only place the rpcs3/Input/ subset rpcs3_emu "
            "needs is compiled for iOS.".format(IOS_CMAKE),
        )

    def test_every_listed_source_path_exists_on_disk(self):
        listed = re.findall(r"\$\{CMAKE_SOURCE_DIR\}/([A-Za-z0-9_./-]+\.(?:cpp|c|mm|m))", self.code)
        self.assertNotEqual(
            [],
            listed,
            "ios/CMakeLists.txt lists no source files at all. rpcs3_emu then has no "
            "definition for pad::get_pad_thread or rpcs3::get_version and the iOS link "
            "fails with undefined symbols.",
        )
        missing = [name for name in listed if not (ROOT / name).is_file()]
        self.assertEqual(
            [],
            missing,
            "ios/CMakeLists.txt lists {0} source path(s) that do not exist: {1}. A "
            "misspelled path is a CMake configure error on some generators and a silent "
            "omission on others. {2}".format(len(missing), missing, WHY_LATE),
        )

    def test_no_unbuildable_hid_unit_is_in_the_source_list(self):
        # Comment-stripped, so the comment block that names all six by basename cannot satisfy this.
        present = [name for name in UNBUILDABLE_FOR_IOS if name in self.code]
        self.assertEqual(
            [],
            present,
            "ios/CMakeLists.txt's source list contains {0}, which cannot be built for "
            "iOS: the unit reaches <hidapi.h>, and hidapi's CMake selects the "
            "hidapi_darwin backend plus IOKit on APPLE, using "
            "IOKit/hid/IOHIDManager.h -- a header the iOS SDK does not ship. Android's "
            "thirteen-unit list is NOT the answer here; iOS gets eight.".format(present),
        )

    def test_neither_hidapi_nor_libusb_is_linked(self):
        for name in FORBIDDEN_LINK_TARGETS:
            with self.subTest(target=name):
                self.assertNotRegex(
                    self.code,
                    r"3rdparty::" + name + r"\b",
                    "ios/CMakeLists.txt links 3rdparty::{0}. Its absence is FORCED, not "
                    "a scope choice: hidapi cannot build for iOS and libusb has no "
                    "USB-host role on the platform, so linking either means an "
                    "exclusion was undone somewhere.".format(name),
                )

    def test_the_ios_src_include_path_is_set_on_rpcs3_emu(self):
        # The seam headers are reached from files compiled into rpcs3_emu, so the search path is set on rpcs3_emu.
        self.assertRegex(
            self.code,
            r"target_include_directories\s*\(\s*rpcs3_emu\b[^)]*ios/core",
            "ios/CMakeLists.txt does not add ios/core to rpcs3_emu's include path. The "
            "iOS branches of rpcs3/util/vm_native.cpp and friends compile into "
            "rpcs3_emu and reach into ios/core for their seam headers; without this the "
            "failure is a missing-header error in an upstream file.",
        )
        self.assertTrue(
            (ROOT / "ios" / "core").is_dir(),
            "ios/core does not exist on disk, so the include path above names nothing. "
            "It is held by ios/core/.gitkeep until 02-09, 02-11 and 02-13 populate it.",
        )


if __name__ == "__main__":
    unittest.main()
