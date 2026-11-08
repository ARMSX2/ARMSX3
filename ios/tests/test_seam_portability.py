# SPDX-License-Identifier: GPL-2.0-only
"""Every ios/core/ unit in target_sources(rpcs3_emu ...) keeps POSIX-only headers and constants inside a Windows-excluding
fence and the JIT seam keeps its non-Apple arm and seam symbols, while the root CMakeLists.txt adds ios/core only inside
if(APPLE). The banned names are pinned data; comments are stripped first, and string literals for the constant scan."""

import os
import re
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(os.environ.get("ARMSX3_TREE_ROOT", str(Path(__file__).resolve().parents[2])))

ROOT_CMAKELISTS = ROOT / "CMakeLists.txt"
SRC_CMAKELISTS = ROOT / "ios" / "core" / "CMakeLists.txt"

# The pinned data, not derived from the sources.

# Headers MSVC does not ship; `fcntl.h`, `sys/stat.h` and `sys/types.h` are absent because MSVC has them.
POSIX_ONLY_HEADERS = (
    "sys/mman.h",       # the header the fence first missed
    "unistd.h",         # its neighbour in the same include block
    "sys/xattr.h",
    "sys/param.h",
    "sys/sysctl.h",
    "sys/socket.h",
    "sys/wait.h",
    "sys/uio.h",
    "sys/ioctl.h",
    "sys/resource.h",
    "sys/statvfs.h",
    "sys/select.h",
    "sys/time.h",
    "dlfcn.h",
    "pthread.h",
    "dirent.h",
    "poll.h",
    "termios.h",
    "sched.h",
    "semaphore.h",
    "glob.h",
    "libgen.h",
    "syslog.h",
    "netdb.h",
    "netinet/in.h",
    "arpa/inet.h",
    "strings.h",
)

# Apple-only headers: not POSIX, but the same failure class and the same fix.
APPLE_ONLY_HEADERS = (
    "TargetConditionals.h",
    "mach/mach.h",
    "mach/vm_map.h",
    "libkern/OSCacheControl.h",
    "os/proc.h",
)

# Constants MSVC does not define; `O_RDWR`, `O_CREAT`, `O_EXCL`, `O_TRUNC`, `EEXIST`, `EPERM` and `EROFS` it has.
POSIX_ONLY_CONSTANTS = (
    "PROT_NONE",        # the constant the fence first missed
    "PROT_READ",
    "PROT_WRITE",
    "PROT_EXEC",
    "MAP_PRIVATE",
    "MAP_SHARED",
    "MAP_ANON",
    "MAP_ANONYMOUS",
    "MAP_FIXED",
    "MAP_FAILED",
    "MAP_JIT",
    "MAP_NORESERVE",
    "MADV_DONTNEED",
    "MS_SYNC",
    "MS_ASYNC",
    "O_CLOEXEC",
    "O_NONBLOCK",
    "S_IRWXU",
    "S_IRUSR",
    "S_IWUSR",
    "S_IXUSR",
    "S_ISDIR",
    "_SC_PAGESIZE",
    "_SC_NPROCESSORS_ONLN",
    "RTLD_NOW",
    "RTLD_LAZY",
    "PTHREAD_MUTEX_INITIALIZER",
)

# Preprocessor tokens whose truth means "this is not Windows".
NON_WINDOWS_TOKENS = frozenset(
    (
        "defined(__APPLE__)",
        "__APPLE__",
        "defined(__MACH__)",
        "__MACH__",
        "TARGET_OS_IPHONE",
        "TARGET_OS_MAC",
        "TARGET_OS_OSX",
        "TARGET_OS_SIMULATOR",
        "defined(__linux__)",
        "__linux__",
        "defined(__unix__)",
        "__unix__",
        "defined(__ANDROID__)",
        "__ANDROID__",
        "defined(__FreeBSD__)",
        "__FreeBSD__",
        "defined(__NetBSD__)",
        "__NetBSD__",
        "defined(__OpenBSD__)",
        "defined(__DragonFly__)",
        "defined(_POSIX_VERSION)",
    )
)

# Preprocessor tokens that are true whenever the compiler is targeting Windows.
WINDOWS_TOKENS = frozenset(
    (
        "defined(_WIN32)",
        "_WIN32",
        "defined(WIN32)",
        "WIN32",
        "defined(_WIN64)",
        "_WIN64",
        "defined(_MSC_VER)",
        "_MSC_VER",
        "defined(__MINGW32__)",
        "defined(__MINGW64__)",
    )
)

WHY = (
    "ios/core/CMakeLists.txt adds this translation unit to rpcs3_emu with no platform guard, "
    "and the root CMakeLists.txt reaches it through an UNCONDITIONAL `add_subdirectory("
    "ios/core)` -- outside the `if(IOS)` block, outside `if(ANDROID)`, outside everything. A "
    "Windows configure therefore compiles this file, and a POSIX-only name outside a "
    "Windows-excluding fence stops that build at `C1083: Cannot open include file` or "
    "`C2065: undeclared identifier`. The symbols linking everywhere and the source compiling "
    "everywhere are two different claims; this guard is the one that checks the second "
    "The fix is the three-way split ios/core/ios_shm_backend.cpp "
    "already uses: `#if defined(__APPLE__) / #elif !defined(_WIN32) / #endif`."
)


def without_comments(source):
    """Strip C/C++ block and line comments, preserving line numbers so diagnostics and region checks stay right."""

    def blank(match):
        return "\n" * match.group(0).count("\n")

    source = re.sub(r"/\*.*?\*/", blank, source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    return source


def without_string_literals(code):
    """Blank the contents of string and character literals, keeping delimiters and line numbers.
    A name inside a log message is not a compile-time reference."""

    def blank(match):
        return match.group(0)[0] + "\n" * match.group(0).count("\n") + match.group(0)[-1]

    code = re.sub(r'"(?:\\.|[^"\\])*"', blank, code, flags=re.DOTALL)
    code = re.sub(r"'(?:\\.|[^'\\])*'", blank, code, flags=re.DOTALL)
    return code


def without_cmake_comments(source):
    return re.sub(r"#[^\n]*", " ", source)


def read(path):
    if not path.is_file():
        raise AssertionError("expected file not found: {0}".format(path))

    return path.read_text(encoding="utf-8")


# Condition analysis: `!X` excludes Windows exactly when `X` is implied by Windows, and vice versa.


def _split_top(expr, operator):
    """Split on `operator` at paren depth zero."""
    parts = []
    depth = 0
    start = 0
    i = 0

    while i < len(expr):
        char = expr[i]

        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif depth == 0 and expr.startswith(operator, i):
            parts.append(expr[start:i])
            i += len(operator)
            start = i
            continue

        i += 1

    parts.append(expr[start:])
    return [part.strip() for part in parts if part.strip()]


def _strip_outer_parens(expr):
    expr = expr.strip()

    while expr.startswith("(") and expr.endswith(")"):
        depth = 0
        balanced = True

        for i, char in enumerate(expr):
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1

                if depth == 0 and i != len(expr) - 1:
                    balanced = False
                    break

        if not balanced:
            break

        expr = expr[1:-1].strip()

    return expr


def _normalise(expr):
    return re.sub(r"\s+", "", expr)


def _conjunct_excludes_windows(expr):
    """True when this single term being true means the target is not Windows."""
    expr = _strip_outer_parens(expr)

    if expr.startswith("!"):
        return _conjunct_implied_by_windows(expr[1:])

    return _normalise(expr) in NON_WINDOWS_TOKENS


def _conjunct_implied_by_windows(expr):
    """True when a Windows target makes this single term true."""
    expr = _strip_outer_parens(expr)

    if expr.startswith("!"):
        return _conjunct_excludes_windows(expr[1:])

    return _normalise(expr) in WINDOWS_TOKENS


def excludes_windows(expr):
    """True when `expr` being true proves the target is not Windows.
    Anything this cannot prove counts as not excluding, so an unknown expression fails the guard."""
    alternatives = _split_top(_strip_outer_parens(expr), "||")

    if not alternatives:
        return False

    for alternative in alternatives:
        conjuncts = _split_top(alternative, "&&")

        if not any(_conjunct_excludes_windows(term) for term in conjuncts):
            return False

    return True


DIRECTIVE = re.compile(
    r"^[ \t]*#[ \t]*(if|ifdef|ifndef|elif|else|endif)\b[ \t]*(.*)$", flags=re.M
)


def windows_excluded_lines(code):
    """The set of line numbers (1-based) inside a Windows-excluding region, given every arm's condition and earlier arms' negations."""
    protected = set()
    stack = []          # each entry: {"prior": [expr, ...], "current": expr or None}
    line_no = 0
    position = 0

    def in_force():
        conditions = []

        for frame in stack:
            for earlier in frame["prior"]:
                conditions.append("!(" + earlier + ")")

            if frame["current"] is not None:
                conditions.append(frame["current"])

        return conditions

    for raw_line in code.split("\n"):
        line_no += 1
        position += 1
        match = DIRECTIVE.match(raw_line)

        if match is None:
            if any(excludes_windows(condition) for condition in in_force()):
                protected.add(line_no)

            continue

        keyword = match.group(1)
        argument = match.group(2).strip()

        if keyword in ("if", "ifdef", "ifndef"):
            if keyword == "ifdef":
                argument = "defined(" + argument + ")"
            elif keyword == "ifndef":
                argument = "!defined(" + argument + ")"

            stack.append({"prior": [], "current": argument})
        elif keyword == "elif":
            if stack:
                frame = stack[-1]

                if frame["current"] is not None:
                    frame["prior"].append(frame["current"])

                frame["current"] = argument
        elif keyword == "else":
            if stack:
                frame = stack[-1]

                if frame["current"] is not None:
                    frame["prior"].append(frame["current"])

                frame["current"] = None
        elif keyword == "endif":
            if stack:
                stack.pop()

        # A directive line is judged by the region it opens, so `#elif !defined(_WIN32)` protects the lines beneath; `#endif` closes first.
        if keyword != "endif":
            if any(excludes_windows(condition) for condition in in_force()):
                protected.add(line_no)

    return protected


def unconditional_sources():
    """The `.cpp` files `ios/core/CMakeLists.txt` adds to rpcs3_emu, as absolute paths."""
    text = without_cmake_comments(read(SRC_CMAKELISTS))
    match = re.search(r"target_sources\s*\(\s*rpcs3_emu\b(.*?)\)", text, flags=re.DOTALL)

    if match is None:
        raise AssertionError(
            "no `target_sources(rpcs3_emu ...)` call found in {0}. This guard derives its "
            "population from the build file, because what makes a file's portability "
            "load-bearing is that CMake compiles it unconditionally. A population it "
            "cannot read is a population it is not guarding, so it fails rather than "
            "checking nothing.".format(SRC_CMAKELISTS)
        )

    names = re.findall(r"\$\{CMAKE_CURRENT_SOURCE_DIR\}/([A-Za-z0-9_./-]+\.cpp)", match.group(1))
    return [SRC_CMAKELISTS.parent / name for name in names]


class SeamPortability(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = unconditional_sources()

    def test_population_was_found(self):
        """A population this guard cannot read is one it is not guarding."""
        self.assertTrue(
            self.sources,
            "no unconditionally-compiled sources were extracted from {0}. {1}".format(
                SRC_CMAKELISTS, WHY
            ),
        )

        for source in self.sources:
            self.assertTrue(source.is_file(), "{0} is missing".format(source))

    def test_add_subdirectory_ios_core_is_apple_only(self):
        """The seam is added to the build only inside the root file's if(APPLE) block."""
        text = without_cmake_comments(read(ROOT_CMAKELISTS))
        match = re.search(
            r"^\s*if\s*\(\s*APPLE\s*\)\s*\n\s*add_subdirectory\s*\(\s*ios/core\s*\)\s*\n\s*endif\s*\(",
            text,
            flags=re.M,
        )
        self.assertIsNotNone(
            match,
            "the root {0} must add ios/core only inside an if(APPLE) block, so non-Apple "
            "builds compile none of the seam.".format(ROOT_CMAKELISTS),
        )
        self.assertEqual(
            len(re.findall(r"add_subdirectory\s*\(\s*ios/core\s*\)", text)),
            1,
            "the root {0} adds ios/core more than once.".format(ROOT_CMAKELISTS),
        )

    def test_no_posix_only_header_outside_a_windows_excluding_fence(self):
        for source in self.sources:
            code = without_comments(read(source))
            protected = windows_excluded_lines(code)

            for line_no, line in enumerate(code.split("\n"), start=1):
                include = re.match(r"^[ \t]*#[ \t]*include[ \t]*[<\"]([^>\"]+)[>\"]", line)

                if include is None:
                    continue

                header = include.group(1)

                if header not in POSIX_ONLY_HEADERS and header not in APPLE_ONLY_HEADERS:
                    continue

                self.assertIn(
                    line_no,
                    protected,
                    "{0}:{1} includes <{2}>, which MSVC does not ship, and the line is not "
                    "inside a preprocessor region that excludes Windows. {3}".format(
                        source.name, line_no, header, WHY
                    ),
                )

    def test_no_posix_only_constant_outside_a_windows_excluding_fence(self):
        for source in self.sources:
            code = without_comments(read(source))
            protected = windows_excluded_lines(code)
            # String literals are blanked for the constant scan only: a name in a log message is not a reference.
            lines = without_string_literals(code).split("\n")

            for constant in POSIX_ONLY_CONSTANTS:
                pattern = re.compile(r"\b" + re.escape(constant) + r"\b")

                for line_no, line in enumerate(lines, start=1):
                    if pattern.search(line) is None:
                        continue

                    self.assertIn(
                        line_no,
                        protected,
                        "{0}:{1} names {2}, which MSVC does not define, and the line is not "
                        "inside a preprocessor region that excludes Windows. {3}".format(
                            source.name, line_no, constant, WHY
                        ),
                    )

    def test_the_jit_seam_has_a_windows_arm(self):
        """ios_jit_backend.cpp still has a Windows arm and defines the three seam symbols vm_native.cpp calls."""
        source = SRC_CMAKELISTS.parent / "ios_jit_backend.cpp"
        code = without_comments(read(source))

        self.assertIn(
            "_WIN32",
            code,
            "ios/core/ios_jit_backend.cpp no longer mentions _WIN32 outside its comments, so "
            "it has no Windows arm at all. {0}".format(WHY),
        )

        for symbol in (
            "jit_backend_kind jit_backend()",
            "int jit_backend_reservation_prot(",
            "int jit_backend_reservation_flags(",
        ):
            self.assertIn(
                symbol,
                code,
                "ios/core/ios_jit_backend.cpp no longer defines `{0}`. rpcs3/util/"
                "vm_native.cpp calls all three from memory_reserve(), memory_decommit() and "
                "memory_reset() on every platform; a missing definition is a link failure "
                "everywhere rather than a compile failure on one platform.".format(symbol),
            )


if __name__ == "__main__":
    unittest.main()
