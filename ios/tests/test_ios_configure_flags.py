# SPDX-License-Identifier: GPL-2.0-only
"""Every -D build-option override lives once in ios/configure.sh and nowhere else, the script refuses a missing or
unknown configuration, and generated ios/build/ trees, when present, carry no host include paths or faked link probes.
ARMSX3_IOS_CONFIGURE, ARMSX3_IOS_CMAKELISTS and ARMSX3_IOS_BUILD_DIR point it at other inputs for falsification."""

import os
import re
import subprocess
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

CONFIGURE_ENV_VAR = "ARMSX3_IOS_CONFIGURE"
CMAKELISTS_ENV_VAR = "ARMSX3_IOS_CMAKELISTS"
BUILD_DIR_ENV_VAR = "ARMSX3_IOS_BUILD_DIR"

DEFAULT_CONFIGURE = ROOT / "ios" / "configure.sh"
DEFAULT_CMAKELISTS = ROOT / "ios" / "CMakeLists.txt"
DEFAULT_BUILD_DIR = ROOT / "ios" / "build"

CONFIGURE = Path(os.environ.get(CONFIGURE_ENV_VAR, "").strip() or str(DEFAULT_CONFIGURE))
CMAKELISTS = Path(os.environ.get(CMAKELISTS_ENV_VAR, "").strip() or str(DEFAULT_CMAKELISTS))
BUILD_DIR = Path(os.environ.get(BUILD_DIR_ENV_VAR, "").strip() or str(DEFAULT_BUILD_DIR))

# The three configuration names, pinned as data.
CONFIG_NAMES = ("ios-nollvm", "ios-llvm")

# Options with the same value in every configuration that sets them, pinned as data.
REQUIRED_OPTIONS = {
    # try_compile links an executable by default, which fails when cross-compiling, so probes compile a static library.
    "CMAKE_TRY_COMPILE_TARGET_TYPE": "STATIC_LIBRARY",
    # The static-library form makes openal-soft's rt and -latomic link probes answer true, so the right empty answers are seeded.
    "HAVE_LIBRT": "",
    "HAVE_LIBATOMIC": "",
    # openal-soft's RTKit block reaches the host's D-Bus through pkg-config, which no CMAKE_PREFIX_PATH governs.
    "ALSOFT_RTKIT": "OFF",
    "CMAKE_SYSTEM_NAME": "iOS",
    # Also makes 3rdparty/DetectArchitecture.cmake short-circuit before any check_symbol_exists.
    "CMAKE_OSX_ARCHITECTURES": "arm64",
    "CMAKE_BUILD_TYPE": "Release",
    # rpcs3_test links rpcs3_lib, which BUILD_DESKTOP_FRONTEND removes, so turning tests on fails at configure.
    "BUILD_RPCS3_TESTS": "OFF",
    "BUILD_DESKTOP_FRONTEND": "OFF",
    "USE_NATIVE_INSTRUCTIONS": "OFF",
    "USE_PRECOMPILED_HEADERS": "OFF",
    "USE_DISCORD_RPC": "OFF",
    "USE_SYSTEM_MVK": "ON",
    # ON so find_package(OpenCV) fails in an iOS sysroot and OpenCV drops out to the dummy target.
    "USE_SYSTEM_OPENCV": "ON",
    "USE_LTO": "OFF",
    # A host library must not resolve into an arm64-ios link.
    "USE_SYSTEM_LIBUSB": "OFF",
    "USE_GAMEMODE": "OFF",
    "USE_FAUDIO": "OFF",
    "USE_LIBEVDEV": "OFF",
    # A bare POSIX shared-memory name is EPERM under the iOS sandbox, and asmjit's dual mapping uses one.
    "ASMJIT_NO_SHM_OPEN": "ON",
    # Passing it stops a failed COMPILER_ARM probe from defaulting LLVM_TARGETS_TO_BUILD to X86.
    "LLVM_TARGETS_TO_BUILD": "AArch64",
    # With this OFF, cubeb builds with no backend and cubeb_init fails at run time.
    "USE_AUDIOUNIT": "ON",
}

# Options that differ between configurations and may be written twice; every other name appears exactly once.
PLATFORM_DIVERGENT = {
    "WITH_LLVM": "off for ios-nollvm, on for ios-llvm",
}

# Options written once per arm with each arm's value pinned on its own; empty since only the iOS arms remain.
PER_ARM_PINNED = {}

# Prefixes that mark a CMake variable as a build option; a set() of one in ios/CMakeLists.txt is the defect.
OPTION_PREFIXES = (
    "USE_",
    "BUILD_",
    "WITH_",
    "STATIC_LINK_",
    "LLVM_",
    "CMAKE_",
    "ASMJIT_",
    "RUN_",
    "HAS_",
)

# Non-option locals ios/CMakeLists.txt may set.
CMAKELISTS_ALLOWED_SET = ("ARMSX3_IOS_INPUT_SOURCES",)

TOO_LATE = (
    "A build option set() in ios/CMakeLists.txt is read AFTER add_subdirectory(3rdparty), so "
    "the decision it means to change has already been taken. It does not error, it does not "
    "warn, and it reads in a diff exactly like the fix."
)

DRIFT = (
    "A shared flag copied into a per-configuration branch does not break on the day of the "
    "copy. It breaks when one copy is edited, and the symptom is the macOS leg and the iOS "
    "build disagreeing about something nobody thinks they disagree about."
)


def strip_shell_comments(source):
    """Drop whole-line and trailing shell comments, so prose cannot satisfy a check.
    A `#` starts a comment only after whitespace, so `$#` and `${#array[@]}` stay."""
    kept = []
    for line in source.splitlines():
        if re.match(r"^\s*#", line):
            continue
        kept.append(re.sub(r"(?<=\s)#.*$", "", line))
    return "\n".join(kept)


def bash_array_body(source, name):
    """The text between `NAME=(` and the closing `)`, each at the start of a line.
    Returns None when the array is absent, which the callers turn into a failure."""
    opener = re.compile(r"^%s=\($" % re.escape(name), re.MULTILINE)
    found = opener.search(source)
    if not found:
        return None
    closer = re.compile(r"^\)$", re.MULTILINE)
    end = closer.search(source, found.end())
    if not end:
        return None
    return source[found.end():end.start()]


def strip_cmake_comments(source):
    """Drop CMake comments. Same reasoning and the same safe failure direction."""
    kept = []
    for line in source.splitlines():
        if re.match(r"^\s*#", line):
            continue
        kept.append(re.sub(r"(?<=\s)#.*$", "", line))
    return "\n".join(kept)


# Generated build tree readers: everything below reads ios/build/<config>/ and nothing else.

# A compile statement is arm64-ios when its own FLAGS carry the iOS minimum-version flag.
IOS_STATEMENT = re.compile(r"-m(?:iphoneos|ios)-version-min=")

# The sysroot comes from the statement, because CMAKE_OSX_SYSROOT in these caches holds the SDK name.
ISYSROOT = re.compile(r"-isysroot\s+(\S+)")

# Every way a directory reaches a compile line.
DIRECTORY_FLAG = re.compile(r"(?:^|\s)-(?:I|isystem\s*|iframework\s*|F|L)\s*(\S+)")

# A CMakeCache.txt entry; the file's `//` and `#` comment lines cannot match it.
CACHE_ENTRY = re.compile(r"^([A-Za-z0-9_\-\.]+):([A-Z]+)=(.*)$")

# A library-existence answer by shape; `_H` names are header probes, which stay honest without a link step.
LIBRARY_EXISTENCE_ENTRY = re.compile(r"^HAVE_LIB(?!.*_H$)[A-Z0-9_]+$")

PKGCONFIG_DOOR = (
    "The Homebrew-prefix fence in CMakeLists.txt is NOT the answer here and looking at it will "
    "mislead you: it guards CMAKE_PREFIX_PATH, which find_package respects and pkg-config does "
    "NOT. pkg_check_modules answers from the host's own pkg-config database whatever the prefix "
    "path says. That is how openal-soft's RTKit block put a host D-Bus on 94 arm64-ios compile "
    "lines. The remedy is a -D override in "
    "ios/configure.sh that turns the feature off, not a prefix-path adjustment."
)

SKIPPED_LINK_STEP = (
    "CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY skips the LINK step, and a link probe that "
    "never links returns TRUE for every library. An entry answered on iOS and unanswered on macOS "
    "is that lie's signature. Seed the correct answer in ios/configure.sh's IOS_FLAGS next to the "
    "two that are already seeded, and add its row to REQUIRED_OPTIONS."
)


def read_cache(tree):
    """Return {NAME: value} for one build tree, or None when it has no cache."""
    path = tree / "CMakeCache.txt"
    if not path.is_file():
        return None
    entries = {}
    with path.open("r", errors="replace") as handle:
        for line in handle:
            match = CACHE_ENTRY.match(line.rstrip("\n"))
            if match:
                entries[match.group(1)] = match.group(3)
    return entries


def discover_trees():
    """Classify every build tree under BUILD_DIR by its own CMAKE_SYSTEM_NAME.
    Returns (ios_trees, macos_trees, unreadable); a directory with no cache is reported as unreadable."""
    ios_trees = []
    macos_trees = []
    unreadable = []
    if not BUILD_DIR.is_dir():
        return ios_trees, macos_trees, unreadable
    for child in sorted(BUILD_DIR.iterdir()):
        if not child.is_dir():
            continue
        cache = read_cache(child)
        if cache is None:
            unreadable.append(child)
            continue
        system = cache.get("CMAKE_SYSTEM_NAME", "")
        if system == "iOS":
            ios_trees.append(child)
        elif system in ("Darwin", ""):
            macos_trees.append(child)
    return ios_trees, macos_trees, unreadable


def compile_statements(ninja_path):
    """Yield the text of each `build` statement in a generated ninja file, keeping only FLAGS and INCLUDES."""
    current = None
    kept = []
    with ninja_path.open("r", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("build "):
                if current is not None:
                    yield current, " ".join(kept)
                current = line
                kept = []
            elif current is not None and line.startswith("  "):
                stripped = line.strip()
                if stripped.startswith("FLAGS =") or stripped.startswith("INCLUDES ="):
                    kept.append(stripped)
            elif current is not None and not line.strip():
                yield current, " ".join(kept)
                current = None
                kept = []
    if current is not None:
        yield current, " ".join(kept)


def foreign_directories(ninja_path, tree, source_root):
    """Directories on arm64-ios compile statements that are outside the sysroot and the repository.
    Returns (offending_statement_count, {directory: statement_count}, arm64_ios_statement_count)."""
    offending = {}
    offending_statements = 0
    ios_statements = 0
    # Allowed roots: this repository, the tree's own CMAKE_HOME_DIRECTORY, the tree itself, and each statement's -isysroot.
    always_allowed = [os.path.realpath(str(ROOT)), os.path.realpath(str(tree))]
    if source_root:
        always_allowed.append(os.path.realpath(source_root))

    for _statement, text in compile_statements(ninja_path):
        if not IOS_STATEMENT.search(text):
            continue
        ios_statements += 1
        allowed = list(always_allowed)
        for sysroot in ISYSROOT.findall(text):
            if sysroot.startswith("/"):
                allowed.append(os.path.realpath(sysroot))

        bad = set()
        for directory in DIRECTORY_FLAG.findall(text):
            directory = directory.strip('"')
            if not directory.startswith("/"):
                continue
            real = os.path.realpath(directory)
            inside = False
            for root in allowed:
                if real == root or real.startswith(root + os.sep):
                    inside = True
                    break
            if not inside:
                bad.add(directory)

        if bad:
            offending_statements += 1
            for directory in bad:
                offending[directory] = offending.get(directory, 0) + 1

    return offending_statements, offending, ios_statements


# The configure script is the one thing this suite executes, so its run is bounded and a hang fails loudly.
CONFIGURE_TIMEOUT_SECONDS = 120


# No `\s*` after `=`, so an empty value cannot swallow the next element; values are either double-quoted or bare.
D_FLAG = re.compile(r"-D([A-Za-z_][A-Za-z0-9_]*)\s*=(?:\"([^\"]*)\"|([^\"\s]*))")


def extract_d_flags(text):
    """[(name, value)] for every `-D` option in `text`, with surrounding quotes removed.
    An unquoted empty value yields "", distinct from a read failure."""
    out = []
    for name, quoted, bare in D_FLAG.findall(text):
        # findall gives "" for a group that did not match, so a quoted match is told apart by its source text.
        out.append((name, quoted if quoted else bare))
    return out


def run_configure(args):
    """Run the configure script and return (returncode, combined output).
    A timeout becomes a named assertion failure that says which script hung."""
    try:
        result = subprocess.run(
            ["bash", str(CONFIGURE)] + list(args),
            cwd=str(ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=CONFIGURE_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise AssertionError(
            "{0} did not exit within {1} seconds when run with {2!r}, so it was killed.\n\n"
            "This is the only place in ios/tests/ that executes a shell script, and both call "
            "sites exercise the argument-validation path, which refuses and exits in "
            "milliseconds. Exceeding that bound means the script BLOCKED -- waiting on stdin, on a "
            "network fetch, or on a submodule -- and NOT that the limit is too tight. The bound "
            "is here because an unbounded run inside a CI job is a required check that never "
            "reports, which reads the same as one that was skipped.".format(
                CONFIGURE, CONFIGURE_TIMEOUT_SECONDS, list(args)
            )
        )

    return result.returncode, result.stdout.decode("utf-8", errors="replace")


class ConfigureFlagsAreCentralised(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Fail rather than skip when the input cannot be resolved.
        if not CONFIGURE.is_file():
            raise AssertionError(
                "{0} does not exist, so nothing carries the -D overrides. Set {1} to audit "
                "another path.".format(CONFIGURE, CONFIGURE_ENV_VAR)
            )
        if not CMAKELISTS.is_file():
            raise AssertionError(
                "{0} does not exist. Set {1} to audit another path.".format(
                    CMAKELISTS, CMAKELISTS_ENV_VAR
                )
            )

        cls.configure_code = strip_shell_comments(CONFIGURE.read_text(encoding="utf-8"))
        cls.cmakelists_code = strip_cmake_comments(CMAKELISTS.read_text(encoding="utf-8"))

        cls.flags = extract_d_flags(cls.configure_code)
        cls.names = [name for name, _value in cls.flags]
        cls.arm_flags = {
            arm: extract_d_flags(bash_array_body(cls.configure_code, arm))
            for arm in sorted({a for spec in PER_ARM_PINNED.values() for a in spec})
        }

    # ---------------------------------------------------------------- 1. the options are there ---

    def test_every_required_option_is_set_with_its_pinned_value(self):
        values = {}
        for name, value in self.flags:
            values.setdefault(name, []).append(value)

        for option in sorted(REQUIRED_OPTIONS):
            with self.subTest(option=option):
                self.assertIn(
                    option,
                    values,
                    "{0} sets no -D{1}. It is in this test's pinned REQUIRED_OPTIONS table "
                    "because the build relies on it; removing it from the script without "
                    "removing it here is the case this assertion exists for.".format(
                        CONFIGURE, option
                    ),
                )
                self.assertEqual(
                    [REQUIRED_OPTIONS[option]],
                    sorted(set(values[option])),
                    "-D{0} is set to {1} in {2}; the pinned value is {3}.".format(
                        option, values[option], CONFIGURE, REQUIRED_OPTIONS[option]
                    ),
                )

    # -------------------------------------------------------- 2 and 3. written once, not twice ---

    def test_no_option_is_written_more_than_its_pinned_number_of_times(self):
        counts = {}
        for name in self.names:
            counts[name] = counts.get(name, 0) + 1

        for name in sorted(counts):
            expected = (
                2 if name in PLATFORM_DIVERGENT or name in PER_ARM_PINNED else 1
            )
            with self.subTest(option=name):
                self.assertEqual(
                    expected,
                    counts[name],
                    "-D{0} is written {1} time(s) in {2}; {3} expected. {4}".format(
                        name,
                        counts[name],
                        CONFIGURE,
                        expected,
                        "It is in PLATFORM_DIVERGENT ({0}), so it belongs in the macOS array "
                        "and the iOS array, once each, and nowhere else.".format(
                            PLATFORM_DIVERGENT.get(name, "")
                        )
                        if name in PLATFORM_DIVERGENT
                        else DRIFT,
                    ),
                )

    def test_each_per_arm_option_is_pinned_independently_in_its_own_arm(self):
        """Each arm's value is read from that arm's array and compared to that arm's pin."""
        for option in sorted(PER_ARM_PINNED):
            for arm in sorted(PER_ARM_PINNED[option]):
                expected = PER_ARM_PINNED[option][arm]
                with self.subTest(option=option, arm=arm):
                    pairs = self.arm_flags.get(arm)
                    self.assertIsNotNone(
                        pairs,
                        "{0} defines no bash array {1}, so -D{2}'s value for that arm "
                        "could not be read. This FAILS rather than skips: an absent arm "
                        "is the case this assertion exists for.".format(
                            CONFIGURE, arm, option
                        ),
                    )
                    values = [v for n, v in pairs if n == option]
                    self.assertEqual(
                        [expected],
                        values,
                        "-D{0} in {1}'s {2} array is {3}; the pinned value for THAT ARM "
                        "is {4}. The two arms are pinned separately on purpose -- do not "
                        "change one because the other changed.".format(
                            option, CONFIGURE, arm, values, expected
                        ),
                    )

    def test_no_per_arm_option_is_also_claimed_as_platform_divergent(self):
        """The two tables are disjoint, so an option has exactly one owner."""
        overlap = sorted(set(PER_ARM_PINNED) & set(PLATFORM_DIVERGENT))
        self.assertEqual(
            [],
            overlap,
            "{0} appear in both PER_ARM_PINNED and PLATFORM_DIVERGENT.".format(overlap),
        )

    def test_every_per_arm_option_is_written_once_in_each_of_its_arms(self):
        """Each per-arm option is written once in each of its arms and nowhere else."""
        for option in sorted(PER_ARM_PINNED):
            arms = PER_ARM_PINNED[option]
            total = sum(
                len([v for n, v in (self.arm_flags.get(arm) or []) if n == option])
                for arm in arms
            )
            with self.subTest(option=option):
                self.assertEqual(
                    len(arms),
                    total,
                    "-D{0} is written {1} time(s) across its pinned arms {2}; expected "
                    "exactly one per arm.".format(option, total, sorted(arms)),
                )
                self.assertEqual(
                    len(arms),
                    self.names.count(option),
                    "-D{0} is written {1} time(s) in {2} overall but {3} time(s) inside "
                    "its pinned arms -- so it is also set somewhere else. {4}".format(
                        option,
                        self.names.count(option),
                        CONFIGURE,
                        total,
                        DRIFT,
                    ),
                )

    def test_every_platform_divergent_option_is_actually_divergent(self):
        counts = {}
        for name in self.names:
            counts[name] = counts.get(name, 0) + 1

        for name in sorted(PLATFORM_DIVERGENT):
            with self.subTest(option=name):
                self.assertEqual(
                    2,
                    counts.get(name, 0),
                    "{0} is listed in this test's PLATFORM_DIVERGENT table -- '{1}' -- but "
                    "appears {2} time(s) in {3}. The table is a claim about which options "
                    "genuinely differ between configurations; a stale claim quietly licenses a "
                    "duplicate that is NOT divergent.".format(
                        name, PLATFORM_DIVERGENT[name], counts.get(name, 0), CONFIGURE
                    ),
                )

    # -------------------------------------------- 4. no build option set() in ios/CMakeLists.txt --

    def test_no_pinned_build_option_is_set_in_the_ios_cmakelists(self):
        for option in sorted(set(REQUIRED_OPTIONS) | set(PLATFORM_DIVERGENT)):
            with self.subTest(option=option):
                self.assertNotRegex(
                    self.cmakelists_code,
                    r"set\s*\(\s*" + re.escape(option) + r"\b",
                    "{0} contains set({1} ...). {2}".format(CMAKELISTS, option, TOO_LATE),
                )

    def test_no_build_option_shaped_name_is_set_in_the_ios_cmakelists(self):
        offenders = []
        for name in re.findall(r"set\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)", self.cmakelists_code):
            if name in CMAKELISTS_ALLOWED_SET:
                continue
            if name.startswith(OPTION_PREFIXES):
                offenders.append(name)

        self.assertEqual(
            [],
            sorted(set(offenders)),
            "{0} set()s {1}, whose name(s) carry a build-option prefix. {2} If one of these is "
            "genuinely a local rather than an option, add it to CMAKELISTS_ALLOWED_SET in this "
            "test with the reason.".format(CMAKELISTS, sorted(set(offenders)), TOO_LATE),
        )

    # ----------------------------------------------- 5. the configuration names are written twice --

    def test_each_configuration_name_appears_exactly_twice(self):
        for name in CONFIG_NAMES:
            with self.subTest(config=name):
                occurrences = len(
                    re.findall(r"(?<![A-Za-z0-9_-])" + re.escape(name) + r"(?![A-Za-z0-9_-])",
                               self.configure_code)
                )
                self.assertEqual(
                    2,
                    occurrences,
                    "'{0}' appears {1} time(s) in the code of {2}; exactly two are expected -- "
                    "one in the CONFIGS list and one as a dispatch case label. A third "
                    "occurrence means a configuration name has been written down in a place "
                    "that can drift from those two.".format(name, occurrences, CONFIGURE),
                )

    # ---------------------------------------------------- 6 and 7. it refuses rather than guesses --

    def test_no_argument_exits_non_zero_and_names_every_configuration(self):
        code, output = run_configure([])
        self.assertNotEqual(
            0,
            code,
            "{0} exited 0 with no configuration argument, which means it fell through to a "
            "default configure. That produces a build tree nobody asked for and a failure "
            "nobody can place.".format(CONFIGURE),
        )
        for name in CONFIG_NAMES:
            with self.subTest(config=name):
                self.assertIn(
                    name,
                    output,
                    "{0} refused a missing configuration without naming '{1}' in its output. A "
                    "refusal that does not say what the valid answers are is a refusal the "
                    "reader has to go and look up.".format(CONFIGURE, name),
                )

    def test_an_unrecognised_argument_exits_non_zero_and_names_every_configuration(self):
        code, output = run_configure(["not-a-config"])
        self.assertNotEqual(
            0,
            code,
            "{0} exited 0 for the configuration name 'not-a-config'.".format(CONFIGURE),
        )
        for name in CONFIG_NAMES:
            with self.subTest(config=name):
                self.assertIn(name, output)


class GeneratedIosTreesCarryNoHostLibrary(unittest.TestCase):
    """Assertions 8 and 9 read ios/build/<config>/ only."""

    # ------------------------------- 8. no host directory on an arm64-ios compile statement ------

    def test_no_arm64_ios_compile_statement_carries_a_directory_outside_sysroot_and_repo(self):
        ios_trees, _macos_trees, unreadable = discover_trees()
        if not ios_trees:
            self.skipTest(
                "NOT CHECKED, and this is a skip rather than a pass so the difference is "
                "visible: no iOS build tree under {0} (a tree is one whose own CMakeCache.txt "
                "says CMAKE_SYSTEM_NAME=iOS). {1} directory/directories there had no readable "
                "cache: {2}. ios/build/ is gitignored, so a fresh clone has none -- configure "
                "one with `ios/configure.sh ios-nollvm`, or set {3} to a directory that holds "
                "trees.".format(
                    BUILD_DIR,
                    len(unreadable),
                    [p.name for p in unreadable] or "none",
                    BUILD_DIR_ENV_VAR,
                )
            )

        for tree in ios_trees:
            with self.subTest(tree=tree.name):
                ninja = tree / "build.ninja"
                if not ninja.is_file():
                    self.skipTest(
                        "NOT CHECKED for {0}: it has a cache but no generated build.ninja at "
                        "{1}, so there are no compile statements to read. A configure that was "
                        "interrupted before the generate step leaves exactly this "
                        "state.".format(tree.name, ninja)
                    )
                source_root = (read_cache(tree) or {}).get("CMAKE_HOME_DIRECTORY", "")
                count, offending, ios_statements = foreign_directories(ninja, tree, source_root)
                self.assertEqual(
                    {},
                    offending,
                    "{0} of {1} arm64-ios compile statements in {2} carry {3} directory/"
                    "directories that are neither inside that statement's own -isysroot nor "
                    "inside this repository ({4}):\n  {5}\n\n{6}".format(
                        count,
                        ios_statements,
                        ninja,
                        len(offending),
                        source_root or ROOT,
                        "\n  ".join(
                            "{0}  (on {1} statement(s))".format(directory, hits)
                            for directory, hits in sorted(offending.items())
                        ),
                        PKGCONFIG_DOOR,
                    ),
                )

    # ------------------ 9. no library-existence answer the macOS side does not also give ---------

    def test_no_library_existence_cache_entry_is_answered_on_ios_and_not_on_macos(self):
        ios_trees, macos_trees, _unreadable = discover_trees()
        if not ios_trees or not macos_trees:
            self.skipTest(
                "NOT CHECKED, and this is a skip rather than a pass so the difference is "
                "visible: the cache diff needs BOTH sides and {0} holds {1} iOS tree(s) and {2} "
                "macOS tree(s). The macOS side is the control -- without it an iOS answer has "
                "nothing to be wrong against. Configure the missing side, or set {3}.".format(
                    BUILD_DIR,
                    len(ios_trees),
                    len(macos_trees),
                    BUILD_DIR_ENV_VAR,
                )
            )

        # A name is unanswered when absent from the cache or empty: a probe that said no, or never ran.
        def answered(entries, name):
            value = entries.get(name, "")
            return value.strip() != ""

        for ios_tree in ios_trees:
            ios_entries = read_cache(ios_tree) or {}
            candidates = sorted(
                name for name in ios_entries if LIBRARY_EXISTENCE_ENTRY.match(name)
            )
            for macos_tree in macos_trees:
                macos_entries = read_cache(macos_tree) or {}
                with self.subTest(ios=ios_tree.name, macos=macos_tree.name):
                    disagreeing = [
                        "{0}: {1}={2!r} but {3} has {4!r}".format(
                            name,
                            ios_tree.name,
                            ios_entries.get(name, ""),
                            macos_tree.name,
                            macos_entries.get(name, "<absent>"),
                        )
                        for name in candidates
                        if answered(ios_entries, name)
                        and not answered(macos_entries, name)
                    ]
                    self.assertEqual(
                        [],
                        disagreeing,
                        "{0} answers {1} library-existence probe(s) that {2} leaves "
                        "unanswered:\n  {3}\n\n{4}".format(
                            ios_tree.name,
                            len(disagreeing),
                            macos_tree.name,
                            "\n  ".join(disagreeing),
                            SKIPPED_LINK_STEP,
                        ),
                    )



class TheDashDExtractorReadsEveryValueShapeTheScriptUses(unittest.TestCase):
    """The four `-D` value shapes the extractor reads, over synthetic fragments."""

    def test_the_four_value_shapes_read_back_as_written(self):
        cases = [
            (
                "unquoted",
                "-DUSE_LTO=OFF",
                "USE_LTO",
                "OFF",
                "the ordinary case; every pinned value in REQUIRED_OPTIONS is one of these.",
            ),
            (
                "quoted, no space",
                '-DCMAKE_BUILD_TYPE="Release"',
                "CMAKE_BUILD_TYPE",
                "Release",
                "quotes are removed, so a quoted value and its unquoted twin compare equal.",
            ),
            (
                "quoted, containing spaces and a command substitution",
                '-DCMAKE_OSX_SYSROOT="$(xcrun --sdk macosx --show-sdk-path)"',
                "CMAKE_OSX_SYSROOT",
                "$(xcrun --sdk macosx --show-sdk-path)",
                "The case that motivated this repair. The old regex "
                "backtracked its quote group to empty on the first space and reported this "
                "value as EMPTY, which is a legitimate pinned state "
                "(HAVE_LIBRT), so a future REQUIRED_OPTIONS row for a quoted flag would have "
                "passed for exactly the wrong reason. This is ios/configure.sh's real macOS "
                "sysroot line.",
            ),
            (
                "genuinely empty",
                "-DHAVE_LIBRT=\n",
                "HAVE_LIBRT",
                "",
                "Measured and pinned: STATIC_LIBRARY fabricates this probe answer, "
                "and empty IS the correct value. It must stay reachable.",
            ),
        ]

        for label, fragment, name, expected, why in cases:
            with self.subTest(shape=label):
                extracted = extract_d_flags(fragment)
                self.assertEqual(
                    [(name, expected)],
                    extracted,
                    "the '{0}' shape `{1}` read back as {2}; expected {3}. {4}".format(
                        label, fragment.strip(), extracted, [(name, expected)], why
                    ),
                )

    def test_an_empty_value_stays_distinguishable_from_a_quoted_one_containing_spaces(self):
        empty = extract_d_flags("-DHAVE_LIBRT=\n")[0][1]
        spaced = extract_d_flags('-DCMAKE_OSX_SYSROOT="$(xcrun --show-sdk-path)"')[0][1]

        self.assertNotEqual(
            empty,
            spaced,
            "a deliberately EMPTY value and a quoted value containing spaces read back "
            "identically. Empty is a pinned, "
            "meaningful state here, so a misread quoted value becomes indistinguishable from a "
            "correct answer rather than from an error.",
        )

    def test_the_real_configure_script_sysroot_reads_back_non_empty(self):
        # The iOS array sets it once, as a quoted command substitution that must not read back empty.
        flags = extract_d_flags(strip_shell_comments(CONFIGURE.read_text(encoding="utf-8")))
        sysroots = [value for name, value in flags if name == "CMAKE_OSX_SYSROOT"]

        self.assertTrue(flags, "the extractor found no -D options at all in {0}.".format(CONFIGURE))
        self.assertEqual(
            1,
            len(sysroots),
            "{0} sets -DCMAKE_OSX_SYSROOT {1} time(s); one is expected, in the iOS "
            "array.".format(CONFIGURE, len(sysroots)),
        )
        for value in sysroots:
            with self.subTest(value=value):
                self.assertNotEqual(
                    "",
                    value,
                    "-DCMAKE_OSX_SYSROOT reads back EMPTY from {0}. Before this repair the macOS "
                    "one did exactly that, because its value is a quoted command substitution "
                    "containing spaces.".format(CONFIGURE),
                )


if __name__ == "__main__":
    unittest.main()
