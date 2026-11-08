# SPDX-License-Identifier: GPL-2.0-only
"""The iOS LLVM build is AArch64-only with a stated host triple and explicit switches, configure.sh
requests static linking, the installed tree holds no X86 library and only arm64 archives, and the
Apple march flag still defines the architecture macro that types.hpp keys on."""

import os
import re
import shutil
import subprocess
import sys
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

DEFAULT_SCRIPT = ROOT / "ios" / "scripts" / "build-llvm-ios.sh"

DEFAULT_MODULE = ROOT / "ios" / "cmake" / "IosLlvm.cmake"

SCRIPT = Path(os.environ.get("ARMSX3_LLVM_BUILD_SCRIPT", str(DEFAULT_SCRIPT)))

MODULE = Path(os.environ.get("ARMSX3_LLVM_CMAKE_MODULE", str(DEFAULT_MODULE)))

CONFIGURE = ROOT / "ios" / "configure.sh"

COMPILER_CONFIG = ROOT / "buildfiles" / "cmake" / "ConfigureCompiler.cmake"

TYPES_HPP = ROOT / "rpcs3" / "util" / "types.hpp"

INSTALL_PREFIX = ROOT / "ios" / "build-deps" / "llvm-ios-install"

INSTALL_LIB = INSTALL_PREFIX / "lib"

# The one correct target list, pinned as data rather than read from the script.
EXPECTED_TARGETS = "AArch64"

FORBIDDEN_TARGET = "X86"

# The host-sysroot doors and what each would otherwise reach for; each reads like a feature knob and is not.
HOST_SYSROOT_SWITCHES = (
    ("LLVM_ENABLE_ZLIB", "the HOST macOS zlib"),
    ("LLVM_ENABLE_ZSTD", "the HOST macOS or Homebrew zstd"),
    ("LLVM_ENABLE_LIBXML2", "the HOST libxml2 -- iOS ships none in the shape LLVM expects"),
    ("LLVM_ENABLE_TERMINFO", "the HOST terminfo/ncurses"),
    ("LLVM_ENABLE_LIBEDIT", "the HOST libedit"),
)

ASSERTIONS_SWITCH = "LLVM_ENABLE_ASSERTIONS"


# `-DFOO=value` as it appears in a bash array, with or without surrounding quotes.
def flag_value(source, name):
    """Return the value passed for -D<name>=, or None. Quotes around the whole item are stripped."""
    match = re.search(r'-D' + re.escape(name) + r'=("?)([^"\s\)]*)\1', source)
    if match is None:
        return None
    return match.group(2)


def resolve(source, value):
    """Resolve a `${NAME}` value against a top-level NAME="..." assignment in the same script."""
    if value is None:
        return None
    match = re.match(r'^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$', value)
    if match is None:
        return value
    name = match.group(1)
    assignment = re.search(
        r'^' + re.escape(name) + r'="?([^"\n]*)"?$', source, flags=re.MULTILINE
    )
    if assignment is None:
        return None
    return assignment.group(1).strip()


def without_comments(source):
    """Strip shell `#` comments so prose naming a switch cannot satisfy a check.
    An unquoted `#` drops to end of line, so over-stripping can only cause a false failure."""
    out = []
    for line in source.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        # A trailing comment only when the `#` follows whitespace, so `${#array[@]}` and `$#` stay.
        out.append(re.sub(r"\s#.*$", "", line))
    return "\n".join(out)


def cmake_without_comments(source):
    """Strip CMake `#` comments. Same reasoning and same safe direction as above."""
    out = []
    for line in source.splitlines():
        if line.lstrip().startswith("#"):
            continue
        out.append(re.sub(r"\s#.*$", "", line))
    return "\n".join(out)


def read(path):
    return path.read_text(encoding="utf-8")


def note(message):
    """A visible skip note. Printed to stderr so it survives ios/tests/run_all.sh's capture."""
    sys.stderr.write("\n    NOTE: %s\n" % message)
    sys.stderr.flush()


class BuildScriptPassesTheTargetListExplicitly(unittest.TestCase):
    """Assertions 1-3: the script, over its comment-stripped source."""

    @classmethod
    def setUpClass(cls):
        if not SCRIPT.is_file():
            raise AssertionError(
                "%s does not exist. This guard fails rather than skips when its input cannot "
                "be resolved: a missing build script is the loudest possible version of the "
                "failure it exists to catch." % SCRIPT
            )
        cls.source = without_comments(read(SCRIPT))

    # 1. The target list and the host triple.

    def test_the_target_list_is_passed_explicitly_and_is_aarch64(self):
        raw = flag_value(self.source, "LLVM_TARGETS_TO_BUILD")

        self.assertIsNotNone(
            raw,
            "%s does not pass -DLLVM_TARGETS_TO_BUILD at all. Without it "
            "3rdparty/llvm/CMakeLists.txt INFERS the list from a check_cxx_compiler_flag probe "
            "whose false branch defaults to %s -- a very long build that succeeds and produces "
            "the wrong backend for an arm64 device."
            % (SCRIPT.name, FORBIDDEN_TARGET),
        )

        value = resolve(self.source, raw)

        self.assertEqual(
            EXPECTED_TARGETS,
            value,
            "%s passes -DLLVM_TARGETS_TO_BUILD=%r, which resolves to %r. The build requires "
            "exactly %r." % (SCRIPT.name, raw, value, EXPECTED_TARGETS),
        )

    def test_no_x86_target_is_requested_anywhere_in_the_script(self):
        # Scoped to `-D` flag lines, because the script's own verification text names X86.
        offenders = [
            line.strip()
            for line in self.source.splitlines()
            if re.search(r"-D[A-Za-z_]+=[^\s]*" + FORBIDDEN_TARGET, line)
        ]
        self.assertEqual(
            [],
            offenders,
            "%s passes %s as the value of a -D flag: %s. The iOS build has exactly one backend."
            % (SCRIPT.name, FORBIDDEN_TARGET, offenders),
        )

    def test_the_host_triple_is_stated(self):
        value = flag_value(self.source, "LLVM_HOST_TRIPLE")

        self.assertIsNotNone(
            value,
            "%s does not pass -DLLVM_HOST_TRIPLE. LLVM derives host information by probing, and "
            "on a cross-build an unstated host triple means it probes the CROSS target for HOST "
            "facts -- the documented cause of a build that succeeds and then reports the wrong "
            "native target at run time." % SCRIPT.name,
        )
        self.assertNotEqual("", value.strip(), "-DLLVM_HOST_TRIPLE is passed with an empty value.")

    def test_the_tablegen_bootstrap_points_at_the_host_binary(self):
        value = flag_value(self.source, "LLVM_TABLEGEN")

        self.assertIsNotNone(
            value,
            "%s does not pass -DLLVM_TABLEGEN. The build requires a native tblgen bootstrap, and "
            "that variable is how stage 2 is told which host binary to use." % SCRIPT.name,
        )
        self.assertIn(
            "tblgen",
            value,
            "-DLLVM_TABLEGEN=%r does not name a tblgen binary." % value,
        )

    # 2. The host-sysroot doors.

    def test_the_host_sysroot_integrations_are_disabled_explicitly(self):
        missing = []

        for name, reaches in HOST_SYSROOT_SWITCHES:
            if flag_value(self.source, name) != "OFF":
                missing.append("%s (would otherwise reach %s)" % (name, reaches))

        self.assertEqual(
            [],
            missing,
            "%s does not explicitly disable:\n  %s\nLeaving these to find_package lets LLVM's own "
            "find modules resolve HOST macOS libraries into an arm64-ios archive -- threat "
            "The same door --disable-autodetect closes for FFmpeg."
            % (SCRIPT.name, "\n  ".join(missing)),
        )

    # 3. The size lever.

    def test_the_assertions_switch_is_explicit_and_off(self):
        """LLVM_ENABLE_ASSERTIONS is passed explicitly OFF; ON or a build-type default fails."""
        value = flag_value(self.source, ASSERTIONS_SWITCH)

        self.assertEqual(
            "OFF",
            value,
            "%s must pass -D%s=OFF explicitly; it passes %r.\n"
            "It costs zero bytes: LLVM's own Release default already turns it off. The flag is "
            "explicit to remove the silent-default path: LLVM derives this from CMAKE_BUILD_TYPE, "
            "so anybody building this dependency RelWithDebInfo or Debug would silently get "
            "assertion strings and their file/line literals back into the largest library in the "
            "tree with nothing saying so.\n"
            "If this trade is ever reversed (an LLVM miscompile then fails silently instead of "
            "loudly), reverse it on purpose and re-measure. Do not arrive at it by accident "
            "through a build type."
            % (SCRIPT.name, ASSERTIONS_SWITCH, value),
        )


class ConfigureRequestsStaticLinkingAndTheFindPackageBranch(unittest.TestCase):
    """Assertion 4: ios/configure.sh's half of the mechanism."""

    @classmethod
    def setUpClass(cls):
        if not CONFIGURE.is_file():
            raise AssertionError("%s does not exist." % CONFIGURE)
        cls.source = without_comments(read(CONFIGURE))

    def test_static_linking_is_requested_explicitly(self):
        self.assertEqual(
            "ON",
            flag_value(self.source, "STATIC_LINK_LLVM"),
            "ios/configure.sh does not pass -DSTATIC_LINK_LLVM=ON. Upstream FORCEs it ON only "
            "inside the BUILD_LLVM branch, which the out-of-band route does not take; left unset, "
            "3rdparty/llvm/CMakeLists.txt falls through to `set(LLVM_LIBS LLVM)` -- the single "
            "shared libLLVM target, which does not exist in a static iOS install.",
        )

    def test_the_build_llvm_switch_is_off_so_the_find_package_branch_is_taken(self):
        self.assertEqual(
            "OFF",
            flag_value(self.source, "BUILD_LLVM"),
            "ios/configure.sh does not pass -DBUILD_LLVM=OFF. ON takes upstream's Android-shaped "
            "in-tree branch, which add_subdirectory()s LLVM inside the iOS cross-configure and "
            "rebuilds on every reconfigure -- the route this plan rejected.",
        )

    def test_the_target_list_is_passed_to_the_consumer_too(self):
        self.assertEqual(
            EXPECTED_TARGETS,
            resolve(self.source, flag_value(self.source, "LLVM_TARGETS_TO_BUILD")),
            "ios/configure.sh does not pass -DLLVM_TARGETS_TO_BUILD=%s. Upstream feeds this "
            "variable to llvm_map_components_to_libnames, and when it is undefined it infers it "
            "from the same probe whose false branch is %s."
            % (EXPECTED_TARGETS, FORBIDDEN_TARGET),
        )

    def test_the_cmake_module_names_llvm_dir_and_defines_no_target(self):
        if not MODULE.is_file():
            raise AssertionError("%s does not exist." % MODULE)

        body = cmake_without_comments(read(MODULE))

        self.assertRegex(
            body,
            re.compile(r"set\s*\(\s*LLVM_DIR\b"),
            "%s does not set LLVM_DIR. That variable IS the mechanism: it is what upstream's "
            "`find_package(LLVM CONFIG)` branch reads." % MODULE.name,
        )
        self.assertIn(
            "CACHE",
            body[body.index("set(LLVM_DIR"):] if "set(LLVM_DIR" in body else "",
            "LLVM_DIR must be a CACHE variable; a directory variable is invisible to the "
            "find_package call in the 3rdparty subdirectory.",
        )
        self.assertNotRegex(
            body,
            re.compile(r"add_library\s*\("),
            "%s defines a CMake target. It must not: upstream creates 3rdparty_llvm itself, and "
            "the whole point of this route is that no upstream file is edited and no upstream "
            "target is pre-empted." % MODULE.name,
        )
        self.assertRegex(
            body,
            re.compile(r"FATAL_ERROR"),
            "%s does not fail when the install is absent. Falling through lets "
            "find_package(LLVM CONFIG) search the HOST, and a macOS Homebrew or Xcode LLVM found "
            "there configures cleanly and fails at link with an architecture mismatch."
            % MODULE.name,
        )
        self.assertIn(
            "build-llvm-ios.sh",
            body,
            "%s's failure message does not name ios/scripts/build-llvm-ios.sh. A configure that "
            "stops without naming the fix is only marginally better than one that does not stop."
            % MODULE.name,
        )


class TheInstalledTreeCarriesAArch64AndNotX86(unittest.TestCase):
    """Assertion 5: the install, when it exists; the one acceptable skip."""

    @classmethod
    def setUpClass(cls):
        cls.archives = []
        cls.available = INSTALL_LIB.is_dir()

        if not cls.available:
            note(
                "install-tree assertions SKIPPED: %s does not exist. The install is a multi-hour "
                "artifact a fresh checkout will not have; run ios/scripts/build-llvm-ios.sh to "
                "make these run. The text assertions in this file ran regardless." % INSTALL_LIB
            )
            return

        cls.archives = sorted(p for p in INSTALL_LIB.glob("*.a"))

    def setUp(self):
        if not self.available:
            self.skipTest("no LLVM install at %s (see the NOTE above)" % INSTALL_LIB)

    def test_no_x86_target_library_is_installed(self):
        offenders = [p.name for p in self.archives if FORBIDDEN_TARGET in p.name]
        self.assertEqual(
            [],
            offenders,
            "the install contains %s target libraries: %s. LLVM_TARGETS_TO_BUILD did not take "
            "effect, which is the silent-wrong-answer this whole guard exists for."
            % (FORBIDDEN_TARGET, offenders),
        )

    def test_an_aarch64_target_library_is_installed(self):
        found = [p.name for p in self.archives if EXPECTED_TARGETS in p.name]
        self.assertTrue(
            found,
            "the install contains no %s target library, so the backend the device needs was not "
            "built at all." % EXPECTED_TARGETS,
        )

    def test_an_mcjit_library_is_installed(self):
        found = [p.name for p in self.archives if "MCJIT" in p.name]
        self.assertTrue(
            found,
            "the install contains no MCJIT library. Utilities/JITLLVM.cpp calls LLVMLinkInMCJIT() "
            "and constructs its engine with setMCJITMemoryManager, and "
            "3rdparty/llvm/CMakeLists.txt requests MCJIT by name, so the recompiler cannot link "
            "without it. Pruning it needs a device sign-off.",
        )

    def test_every_installed_archive_is_arm64(self):
        # `lipo` is Xcode tooling and a stale install can reach here without it, so its absence skips rather than errors.
        if shutil.which("lipo") is None:
            self.skipTest(
                "no lipo on this host, so the per-archive architecture check cannot run. This "
                "assertion is macOS-only by construction. What still ran: the three install-tree "
                "NAME assertions beside it -- no X86 target library, an AArch64 one present, an "
                "MCJIT one present -- and every text assertion in this file. What was NOT "
                "checked: whether the archives in %s are actually arm64, which only a macOS run "
                "can answer." % INSTALL_LIB
            )

        self.assertTrue(self.archives, "no archives under %s" % INSTALL_LIB)

        wrong = []
        for archive in self.archives:
            out = subprocess.run(
                ["lipo", "-info", str(archive)],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            ).stdout.decode("utf-8", "replace")
            if "arm64" not in out:
                wrong.append("%s -> %s" % (archive.name, out.strip()))

        self.assertEqual(
            [],
            wrong,
            "archives that are not arm64:\n  %s" % "\n  ".join(wrong),
        )


class TheArchitectureFeatureMacroSurvives(unittest.TestCase):
    """Assertion 7: R1's measurement, turned into a standing guard."""

    def test_types_hpp_still_keys_the_atomic_path_on_the_architecture_macro(self):
        source = read(TYPES_HPP)

        self.assertIn(
            "ARM_FEATURE_LSE2",
            source,
            "rpcs3/util/types.hpp no longer defines ARM_FEATURE_LSE2. The compile assertion below "
            "is only meaningful while that macro is what the -march flag buys.",
        )

        guard = re.search(
            r"#if[^\n]*__ARM_ARCH_8_4__[^\n]*\n#define ARM_FEATURE_LSE2", source
        )
        self.assertIsNotNone(
            guard,
            "rpcs3/util/types.hpp no longer gates ARM_FEATURE_LSE2 on __ARM_ARCH_8_4__. That is "
            "the macro the project's Apple -march flag produces; if upstream renamed it, the "
            "compile assertion below is testing the wrong thing and must be re-pointed.",
        )

    def test_the_apple_march_flag_still_defines_the_architecture_macro(self):
        # The one compiling assertion here, so it skips where Apple developer tooling is absent.
        if shutil.which("xcrun") is None:
            self.skipTest(
                "no xcrun on this host, so the arm64-ios compile probe cannot run. This "
                "assertion is macOS-only by construction. What still ran: both text assertions "
                "in this class -- that buildfiles/cmake/ConfigureCompiler.cmake still carries an "
                "-march flag in its `if (APPLE)` branch, and that rpcs3/util/types.hpp still "
                "gates ARM_FEATURE_LSE2 on __ARM_ARCH_8_4__ -- and every other assertion in this "
                "file. What was NOT checked: whether that -march flag STILL DEFINES that macro, "
                "which is R1's measurement and which only a compiler can answer."
            )

        flag = self.apple_march_flag()

        source = (
            "#if !defined(__ARM_ARCH_8_4__)\n"
            '#error "the -march flag no longer defines __ARM_ARCH_8_4__"\n'
            "#endif\n"
            "int armsx3_probe;\n"
        )

        result = subprocess.run(
            [
                "xcrun",
                "--sdk",
                "iphoneos",
                "clang",
                "-x",
                "c++",
                "-std=c++17",
                "-arch",
                "arm64",
                "-miphoneos-version-min=15.0",
                "-march=" + flag,
                "-fsyntax-only",
                "-",
            ],
            cwd=str(ROOT),
            input=source.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        self.assertEqual(
            0,
            result.returncode,
            "compiling a probe for arm64-ios with -march=%s did not define __ARM_ARCH_8_4__.\n%s\n"
            "This is R1's measurement: the flag is what gives the project both the AArch64 LLVM "
            "target inference and rpcs3/util/types.hpp's ARM_FEATURE_LSE2 16-byte atomic path. "
            "Losing it is a correctness-adjacent regression, not merely a performance one."
            % (flag, result.stdout.decode("utf-8", "replace")),
        )

    def apple_march_flag(self):
        """The -march value ConfigureCompiler.cmake uses on APPLE. Read, never restated."""
        lines = read(COMPILER_CONFIG).splitlines()

        for index, line in enumerate(lines):
            if re.match(r"\s*if\s*\(\s*APPLE\s*\)", line):
                for follower in lines[index : index + 6]:
                    match = re.search(r"-march=([A-Za-z0-9.+_-]+)", follower)
                    if match and match.group(1) != "native":
                        return match.group(1)

        raise AssertionError(
            "no -march value found in an `if (APPLE)` branch of %s. This guard fails rather than "
            "skips when its input cannot be resolved: an unreadable flag is not a passing flag."
            % COMPILER_CONFIG
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
