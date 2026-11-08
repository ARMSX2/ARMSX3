# SPDX-License-Identifier: GPL-2.0-only
"""The libusb stand-in header: its USB constants and API version, the core's annotated case lines, the
identifiers the core uses, inline-only functions and the CMake include, checked on the real files, plus a
driver compiled against the header with clang++ and run on the Mac host."""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "ios" / "core" / "libusb-standin" / "libusb.h"
CMAKE = ROOT / "ios" / "CMakeLists.txt"
IO_DIR = ROOT / "rpcs3" / "Emu" / "Io"
SYS_USBD = ROOT / "rpcs3" / "Emu" / "Cell" / "lv2" / "sys_usbd.cpp"
SOURCE_SUFFIXES = (".c", ".cc", ".cpp", ".cxx", ".h", ".hpp")

# USB 2.0 chapter 9: bmRequestType fields (Table 9-2), request codes (Table 9-4), transfer type (9.6.6), setup size (9.3).
VALUES = {
    "LIBUSB_ENDPOINT_OUT": 0x00,
    "LIBUSB_ENDPOINT_IN": 0x80,
    "LIBUSB_REQUEST_TYPE_STANDARD": 0x00,
    "LIBUSB_REQUEST_TYPE_CLASS": 0x20,
    "LIBUSB_RECIPIENT_DEVICE": 0,
    "LIBUSB_RECIPIENT_INTERFACE": 1,
    "LIBUSB_RECIPIENT_ENDPOINT": 2,
    "LIBUSB_REQUEST_GET_STATUS": 0x00,
    "LIBUSB_REQUEST_GET_DESCRIPTOR": 0x06,
    "LIBUSB_REQUEST_GET_CONFIGURATION": 0x08,
    "LIBUSB_REQUEST_SET_CONFIGURATION": 0x09,
    "LIBUSB_REQUEST_SET_INTERFACE": 0x0B,
    "LIBUSB_TRANSFER_TYPE_CONTROL": 0,
    "LIBUSB_TRANSFER_TYPE_BULK": 2,
    "LIBUSB_TRANSFER_TYPE_MASK": 0x03,
    "LIBUSB_CONTROL_SETUP_SIZE": 8,
}
HOTPLUG_VERSION = 0x01000102

# Named in the core only under version, hotplug, _WIN32 or __ANDROID__ guards that no iOS build with this stand-in compiles.
NOT_COMPILED = frozenset((
    "LIBUSB_CAP_HAS_HOTPLUG", "LIBUSB_HOTPLUG_EVENT_DEVICE_ARRIVED", "LIBUSB_HOTPLUG_EVENT_DEVICE_LEFT",
    "LIBUSB_HOTPLUG_MATCH_ANY", "LIBUSB_LOG_LEVEL_DEBUG", "LIBUSB_LOG_LEVEL_ERROR", "LIBUSB_LOG_LEVEL_INFO",
    "LIBUSB_LOG_LEVEL_WARNING", "LIBUSB_OPTION_LOG_CB", "LIBUSB_OPTION_LOG_LEVEL", "LIBUSB_WINDOWS_HOTPLUG",
    "libusb_has_capability", "libusb_hotplug_callback_fn", "libusb_hotplug_callback_handle",
    "libusb_hotplug_deregister_callback", "libusb_hotplug_event", "libusb_hotplug_flag",
    "libusb_hotplug_register_callback", "libusb_init_context", "libusb_init_option", "libusb_log_level",
    "LIBUSB_OPTION_NO_DEVICE_DISCOVERY", "libusb_get_device", "libusb_wrap_sys_device",
))

ANNOTATED = re.compile(r"LIBUSB_[A-Z_]+[^/]*// 0x[0-9a-fA-F]{2}")
CASE = re.compile(r"\bcase\b(.*):\s*//\s*0x([0-9a-fA-F]{2})")
IDENTIFIER = re.compile(r"\b(libusb_[a-z_]+|LIBUSB_[A-Z0-9_]+)\b")
ENUM_VALUE = re.compile(r"\b(LIBUSB_[A-Z0-9_]+)\s*=\s*(-?0x[0-9A-Fa-f]+|-?\d+)")
DEFINE_VALUE = re.compile(r"^\s*#\s*define\s+(LIBUSB_[A-Z0-9_]+)\s+(0x[0-9A-Fa-f]+|\d+)\s*$", re.MULTILINE)
FUNCTION_LINE = re.compile(r"^\S[^\n]*\blibusb_[a-z_]+\s*\(", re.MULTILINE)
INCLUDE = re.compile(r"^\s*#\s*(include|import)\s*([<\"][^>\"]+[>\"])", re.MULTILINE)
ALLOWED_INCLUDES = ("<stddef.h>", "<stdint.h>")
PUBLIC_INCLUDE = re.compile(r"target_include_directories\s*\(\s*rpcs3_emu\s+PUBLIC\b[^)]*ios/core/libusb-standin")
FOREACH = re.compile(r"foreach\s*\(\s*excluded\s+IN\s+LISTS\s+([^)]*)\)")

LS_LINE = "LS init=0 ctx=1 devices=0 listnull=1 allocnull=1 submitfails=1 notbusy=1 events=0 nohotplug=1"
COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def without_comments(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", source)


def without_cmake_comments(text):
    return re.sub(r"#[^\n]*", " ", text)


def core_sources():
    """rel path -> text for every source under rpcs3/Emu/Io plus sys_usbd.cpp, the git grep population."""
    paths = sorted(p for p in IO_DIR.rglob("*") if p.is_file() and p.suffix in SOURCE_SUFFIXES) + [SYS_USBD]
    return {str(p.relative_to(ROOT)): read(p) for p in paths}


def header_values(header):
    code = without_comments(header)
    values = {name: int(value, 0) for name, value in ENUM_VALUE.findall(code)}
    values.update({name: int(value, 0) for name, value in DEFINE_VALUE.findall(code)})
    return values


def annotated_lines(sources):
    """(path, line) for every annotated LIBUSB_ case line."""
    return [(path, line) for path, text in sorted(sources.items()) for line in text.splitlines()
            if ANNOTATED.search(line)]


def evaluate(expression, values):
    """OR the terms of a case label; None when a term is neither a literal nor a known stand-in constant."""
    result = 0
    for term in re.sub(r"/\*.*?\*/", " ", expression).split("|"):
        term = term.strip().lstrip("+").strip()
        if re.fullmatch(r"\d+[uU]?", term):
            result |= int(term.rstrip("uU"))
        elif term in values:
            result |= values[term]
        else:
            return None
    return result


def standin_problems(header, cmake, sources):
    problems = []
    values = header_values(header)
    code = without_comments(header)

    wrong = sorted(name for name, want in VALUES.items() if values.get(name) != want)
    version = values.get("LIBUSB_API_VERSION")
    if wrong or version is None or version >= HOTPLUG_VERSION:
        problems.append("values: {0} differ from the USB 2.0 values, or LIBUSB_API_VERSION is not below "
                        "0x01000102".format(wrong))

    lines = annotated_lines(sources)
    if not lines:
        problems.append("annotations: no annotated LIBUSB_ case line was found; the check would assert nothing")
    for path, line in lines:
        case = CASE.search(line)
        got = evaluate(case.group(1), values) if case else None
        if case is None or got != int(case.group(2), 16):
            problems.append("annotations: {0}: `{1}` does not evaluate to its annotated byte with the stand-in's "
                            "values".format(path, line.strip()))

    used = set()
    for text in sources.values():
        used.update(IDENTIFIER.findall(without_comments(text)))
    missing = sorted(name for name in used - NOT_COMPILED if not re.search(r"\b%s\b" % name, code))
    if missing:
        problems.append("coverage: the core uses {0}, which the stand-in does not declare".format(missing))

    functions = FUNCTION_LINE.findall(code)
    includes = [name for _, name in INCLUDE.findall(code)]
    if not functions or any(not line.startswith("static inline ") for line in functions) \
            or re.search(r"\bextern\b", code) or any(name not in ALLOWED_INCLUDES for name in includes):
        problems.append("inline: a stand-in function is not static inline, something is extern, or an include "
                        "other than <stddef.h> and <stdint.h> is named")

    cmake_code = without_cmake_comments(cmake)
    loops = FOREACH.findall(cmake_code)
    if not PUBLIC_INCLUDE.search(cmake_code) or not loops \
            or any("ARMSX3_IOS_USB_EXCLUDED_SOURCES" in loop for loop in loops) \
            or "ios_usb_stub.cpp" in cmake_code or "3rdparty::libusb" in cmake_code:
        problems.append("cmake: rpcs3_emu does not take the stand-in PUBLIC, the foreach still removes the USB "
                        "sources, or the stub or 3rdparty::libusb is named")

    return problems


def real_inputs():
    return [read(HEADER), read(CMAKE), core_sources()]


class TheRealFilesHaveNoProblems(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs = real_inputs()
        cls.population = len(annotated_lines(cls.inputs[2]))
        sys.stderr.write("\nLS-ANNOTATIONS annotated_case_lines={0}\n".format(cls.population))

    def test_real_files_have_no_problems(self):
        self.assertEqual([], standin_problems(*self.inputs))

    def test_the_annotation_population_is_not_empty(self):
        self.assertGreater(self.population, 0)

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.h"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(OSError):
                read(Path(tmp) / "missing.h")


class StandInMutations(unittest.TestCase):
    """Each method makes ONE edit to the real inputs and shows the checker fail."""

    @classmethod
    def setUpClass(cls):
        cls.inputs = real_inputs()

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone: " + old)
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def assertFlagged(self, index, value, prefix):
        inputs = list(self.inputs)
        inputs[index] = value
        problems = standin_problems(*inputs)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_values_class_request_drifts(self):
        self.assertFlagged(0, self.edited(self.inputs[0], "LIBUSB_REQUEST_TYPE_CLASS = 0x20",
                                          "LIBUSB_REQUEST_TYPE_CLASS = 0x40"), "values")

    def test_mutation_annotations_byte_disagrees(self):
        sources = dict(self.inputs[2])
        key = "rpcs3/Emu/Io/usb_microphone.cpp"
        sources[key] = self.edited(sources[key], "LIBUSB_RECIPIENT_ENDPOINT: // 0xa2",
                                   "LIBUSB_RECIPIENT_ENDPOINT: // 0xa3")
        self.assertFlagged(2, sources, "annotations")

    def test_mutation_coverage_a_used_function_is_undeclared(self):
        self.assertFlagged(0, self.edited(self.inputs[0], "libusb_get_port_number(", "libusb_get_port_numbr("),
                           "coverage")

    def test_mutation_inline_a_function_is_external(self):
        self.assertFlagged(0, self.edited(self.inputs[0], "static inline int libusb_init(", "int libusb_init("),
                           "inline")


    def test_mutation_cmake_private_include(self):
        self.assertFlagged(1, self.edited(self.inputs[1], "rpcs3_emu PUBLIC", "rpcs3_emu PRIVATE"), "cmake")


DRIVER = r'''// Host driver for test_libusb_standin.py. A macOS HOST run of the stand-in's inline answers.
#include <libusb.h>

#include <cstdio>
#include <sys/time.h>

int main()
{
	libusb_context* ctx = nullptr;
	const int init = libusb_init(&ctx);
	libusb_device** list = reinterpret_cast<libusb_device**>(&ctx);
	const auto devices = libusb_get_device_list(ctx, &list);
	libusb_free_device_list(list, 1);
	libusb_transfer* transfer = libusb_alloc_transfer(8);
	const int submit = libusb_submit_transfer(transfer);
	timeval tv{0, 0};
	const int events = libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
	libusb_exit(ctx);
	std::printf("LS init=%d ctx=%d devices=%d listnull=%d allocnull=%d submitfails=%d notbusy=%d events=%d nohotplug=%d\n",
		init, int(ctx != nullptr), int(devices), int(list == nullptr), int(transfer == nullptr),
		int(submit != LIBUSB_SUCCESS), int(submit != LIBUSB_ERROR_BUSY), events, int(LIBUSB_API_VERSION < 0x01000102));
	return 0;
}
'''


class TheStandInBehaves(unittest.TestCase):
    """A macOS host run of the header's inline answers, never device evidence.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    @classmethod
    def setUpClass(cls):
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, universal_newlines=True)
        if found.returncode != 0:
            raise unittest.SkipTest("xcrun finds no macOS `clang++`; a skip is not a pass")
        if not HEADER.is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(HEADER))

        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run(
                ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-I", str(HEADER.parent), "-o", str(binary),
                 str(driver)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            if built.returncode != 0:
                raise AssertionError("clang++ failed:\n" + built.stdout)
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
        cls.code, cls.out = ran.returncode, ran.stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("LS ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_bus_initialises_empty_and_never_busy(self):
        self.assertEqual(0, self.code, self.out[-4000:])
        self.assertEqual([LS_LINE], self.lines, self.out[-4000:])


if __name__ == "__main__":
    unittest.main()
