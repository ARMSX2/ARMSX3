#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""MoltenVK writes nothing to stderr when configured through our VkInstance, and its errors and warnings still reach
RPCS3.log through vk::dbgFunc on Apple: source pins with one mutation each, and a host probe of the pinned macOS dylib.
It says nothing about a device or about messages MoltenVK reports with no instance."""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from source_helpers import arms_enclosing  # noqa: E402

VK = ROOT / "rpcs3" / "Emu" / "RSX" / "VK"
FILES = {
    "instance": VK / "vkutils" / "instance.cpp",
    "shared": VK / "vkutils" / "shared.cpp",
    "render": VK / "VKGSRender.cpp",
}
PKG = ROOT / "ios" / "3rdparty" / "MoltenVK" / "macos-pkg" / "MoltenVK"
APPLE = "defined(__APPLE__)"


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def strip(text):
    """Comments blanked to spaces, newlines and string literals kept, so every offset is the original's."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if text[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def extent(text, head):
    """(start, end) offsets of the brace-balanced body after the first occurrence of head, or None."""
    i = text.find(head)
    if i < 0:
        return None
    j = text.find("{", i + len(head))
    if j < 0:
        return None
    depth = 0
    for k in range(j, len(text)):
        depth += {"{": 1, "}": -1}.get(text[k], 0)
        if depth == 0:
            return (j + 1, k)
    return None


def apple(text, at):
    """'in' for a non-else __APPLE__ arm, 'else' for its #else, None outside any __APPLE__ arm."""
    for arm in reversed(arms_enclosing(text, at)):
        if re.sub(r"\s+", "", arm.condition) == APPLE:
            return "else" if arm.is_else else "in"
    return None


def finds(text, pattern, lo=0, hi=None):
    hi = len(text) if hi is None else hi
    return [m.start() for m in re.finditer(pattern, text) if lo <= m.start() < hi]


DECL = r"const int32_t setting_log_level = MVK_CONFIG_LOG_LEVEL_NONE;"
PUSH = (r"mvk_settings\.push_back\(VkLayerSettingEXT\{\s*kMVKMoltenVKDriverLayerName,\s*\"MVK_CONFIG_LOG_LEVEL\",\s*"
        r"VK_LAYER_SETTING_TYPE_INT32_EXT,\s*1,\s*&setting_log_level\s*\}\);")
NULL_TEST = r"if \(!vkGetInstanceProcAddr\(m_instance, \"vkCreateDebugReportCallbackEXT\"\)\)\s*return;"


def problems(t):
    out = []
    ins, sh, rend = (strip(t[k]) for k in ("instance", "shared", "render"))

    # level
    create = extent(ins, "bool instance::create(") or (0, 0)
    decl = finds(ins, re.escape(DECL), *create)
    push = finds(ins, PUSH, *create)
    vec = ins.find("std::vector<VkLayerSettingEXT> mvk_settings;", *create)
    fast = ins.find('"MVK_CONFIG_FAST_MATH_ENABLED"', *create)
    stype = ins.find("mvk_layer_settings_create_info.sType", *create)
    if len(decl) != 1 or len(push) != 1 or ins.count("setting_log_level") != 2:
        out.append("level: create() lacks exactly one MVK_CONFIG_LOG_LEVEL_NONE declaration and one INT32 push of it")
    elif apple(ins, decl[0]) != "in" or apple(ins, push[0]) != "in":
        out.append("level: the log-level declaration or push is not inside a non-else __APPLE__ arm")
    elif not (0 <= decl[0] < vec and 0 <= fast < push[0] < stype):
        out.append("level: the declaration is not before mvk_settings, or the push is not between fast math and sType")

    # callback
    dbg = extent(ins, "void instance::enable_debugging()") or (0, 0)
    gate = finds(ins, r"g_cfg\.video\.debug_output", *dbg)
    null = finds(ins, NULL_TEST, *dbg)
    keep = [finds(ins, re.escape(s), *dbg) for s in (
        "dbgCreateInfo.flags = VK_DEBUG_REPORT_ERROR_BIT_EXT | VK_DEBUG_REPORT_WARNING_BIT_EXT;",
        "dbgCreateInfo.pfnCallback = callback;",
        "PFN_vkDebugReportCallbackEXT callback = vk::dbgFunc;")]
    if len(gate) != 1 or apple(ins, gate[0]) != "else":
        out.append("callback: the Debug-output early return is not exactly once, in the #else of __APPLE__")
    if len(null) != 1 or apple(ins, null[0]) != "in":
        out.append("callback: the Apple arm does not return on a null vkCreateDebugReportCallbackEXT")
    if any(len(k) != 1 or apple(ins, k[0]) is not None for k in keep):
        out.append("callback: the callback is not created with vk::dbgFunc at ERROR or WARNING outside any Apple arm")

    # extension
    m = re.search(r"if \(([^\n]*)\)\s*\{\s*extensions\.push_back\(VK_EXT_DEBUG_REPORT_EXTENSION_NAME\);", ins[create[0]:create[1]])
    if not m or m.group(1).strip() != "support.is_supported(VK_EXT_DEBUG_REPORT_EXTENSION_NAME)" or \
            ins.count("extensions.push_back(VK_EXT_DEBUG_REPORT_EXTENSION_NAME);") != 1 or \
            apple(ins, create[0] + m.start()) is not None:
        out.append("extension: create() does not request VK_EXT_debug_report on is_supported alone, outside Apple arms")

    # wiring
    ctor = extent(rend, "VKGSRender::VKGSRender(") or (0, 0)
    made = rend.find('m_instance.create("RPCS3")', *ctor)
    bound = rend.find("m_instance.bind();", *ctor)
    bind = extent(ins, "void instance::bind()") or (0, 0)
    if not (0 <= made < bound < ctor[1]) or "enable_debugging();" not in ins[bind[0]:bind[1]]:
        out.append("wiring: the constructor does not bind() after create(\"RPCS3\"), or bind() skips enable_debugging()")

    # sink
    fn = extent(sh, "VkBool32 VKAPI_CALL dbgFunc(") or (0, 0)
    body = sh[fn[0]:fn[1]]
    err = extent(body, "if (msgFlags & VK_DEBUG_REPORT_ERROR_BIT_EXT)") or (0, 0)
    warn = extent(body, "if (msgFlags & VK_DEBUG_REPORT_WARNING_BIT_EXT)") or (0, 0)
    if not re.search(r"rsx_log\.error\([^;]*pLayerPrefix[^;]*pMsg\);", body[err[0]:err[1]]) or \
            not re.search(r"rsx_log\.warning\([^;]*pLayerPrefix[^;]*pMsg\);", body[warn[0]:warn[1]]):
        out.append("sink: dbgFunc does not log ERROR through rsx_log.error and WARNING through rsx_log.warning")
    return out


class TheRealFilesCarryTheChange(unittest.TestCase):
    def test_the_real_files(self):
        self.assertEqual([], problems({k: read(p) for k, p in FILES.items()}))


class EachCheckCanFail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.t = {k: read(p) for k, p in FILES.items()}

    def fails_with(self, prefix, key, old, new):
        self.assertTrue(old in self.t[key], "the mutation's anchor is gone: " + old)
        t = dict(self.t)
        t[key] = t[key].replace(old, new, 1)
        self.assertNotEqual(self.t[key], t[key])
        found = problems(t)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), "%s did not fire: %s" % (prefix, found))

    def test_mutation_level_enum_error(self):
        self.fails_with("level", "instance", "= MVK_CONFIG_LOG_LEVEL_NONE;", "= MVK_CONFIG_LOG_LEVEL_ERROR;")

    def test_mutation_level_push_removed(self):
        line = re.search(r"[^\n]*\"MVK_CONFIG_LOG_LEVEL\"[^\n]*\n", self.t["instance"]).group(0)
        self.fails_with("level", "instance", line, "")

    def test_mutation_level_name_misspelled(self):
        self.fails_with("level", "instance", '"MVK_CONFIG_LOG_LEVEL"', '"MVK_CONFIG_LOG_LEVL"')

    def test_mutation_level_type_bool32(self):
        self.fails_with("level", "instance", "VK_LAYER_SETTING_TYPE_INT32_EXT, 1, &setting_log_level",
                        "VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, &setting_log_level")

    def test_mutation_callback_apple_arm_removed(self):
        arm = re.search(r"#ifdef __APPLE__\n(?:(?!#endif)[\s\S])*?vkCreateDebugReportCallbackEXT\"\)\) return;\n#else\n"
                        r"(\t\tif \(!g_cfg\.video\.debug_output\) return;\n)#endif\n", self.t["instance"])
        self.assertIsNotNone(arm, "the enable_debugging Apple arm is not in the expected form")
        self.fails_with("callback", "instance", arm.group(0), arm.group(1))

    def test_mutation_callback_warning_flag_dropped(self):
        self.fails_with("callback", "instance", "VK_DEBUG_REPORT_ERROR_BIT_EXT | VK_DEBUG_REPORT_WARNING_BIT_EXT;",
                        "VK_DEBUG_REPORT_ERROR_BIT_EXT;")

    def test_mutation_callback_null_test_removed(self):
        self.fails_with("callback", "instance",
                        'if (!vkGetInstanceProcAddr(m_instance, "vkCreateDebugReportCallbackEXT")) return;', "")

    def test_mutation_extension_behind_debug_output(self):
        self.fails_with("extension", "instance", "if (support.is_supported(VK_EXT_DEBUG_REPORT_EXTENSION_NAME))",
                        "if (g_cfg.video.debug_output && support.is_supported(VK_EXT_DEBUG_REPORT_EXTENSION_NAME))")

    def test_mutation_sink_error_traced(self):
        self.fails_with("sink", "shared", 'rsx_log.error("ERROR: [%s]', 'rsx_log.trace("ERROR: [%s]')

    def test_mutation_wiring_bind_removed(self):
        self.fails_with("wiring", "render", "m_instance.bind();", ";")


# The pinned package defaults to INFO and Homebrew's build to NONE, so the control sets the level explicitly.
PROBE_SOURCE = r'''// Reference driver for ios/tests/test_moltenvk_log_quiet.py, run against
// ios/3rdparty/MoltenVK/macos-pkg (MoltenVK 1.4.2, the release package the iOS static slice comes from), linked directly,
// no Vulkan loader, no portability extension: the same shape as the iOS instance. Not the macOS leg; no game, no window.
//
// argv[1] is `default` (no layer settings: the release build's own log level applies) or `silenced` (the layer setting
// instance.cpp passes on Apple: MVK_CONFIG_LOG_LEVEL = MVK_CONFIG_LOG_LEVEL_NONE as an INT32 setting).
// It registers a VK_EXT_debug_report callback (errors and warnings, the flags instance::enable_debugging uses), makes
// MoltenVK report one error (a shader module whose first word is not the SPIR-V magic number) and prints one stdout line.
#include <vulkan/vulkan.h>
#include <MoltenVK/mvk_vulkan.h>
#include <MoltenVK/mvk_private_api.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int g_err = 0, g_warn = 0, g_other = 0;
static std::string g_prefix, g_first;

static VKAPI_ATTR VkBool32 VKAPI_CALL callback(VkFlags flags, VkDebugReportObjectTypeEXT, uint64_t, size_t, int32_t,
	const char* prefix, const char* msg, void*)
{
	if (flags & VK_DEBUG_REPORT_ERROR_BIT_EXT) g_err++;
	else if (flags & VK_DEBUG_REPORT_WARNING_BIT_EXT) g_warn++;
	else g_other++;
	if (g_first.empty()) { g_prefix = prefix ? prefix : "(null)"; g_first = msg ? msg : "(null)"; }
	return VK_FALSE;
}

int main(int argc, char** argv)
{
	if (argc < 2 || (strcmp(argv[1], "default") && strcmp(argv[1], "silenced"))) { std::printf("PROBE-FAIL usage\n"); return 1; }
	const bool silenced = !strcmp(argv[1], "silenced");

	const int32_t level_none = MVK_CONFIG_LOG_LEVEL_NONE;
	VkLayerSettingEXT setting{ kMVKMoltenVKDriverLayerName, "MVK_CONFIG_LOG_LEVEL", VK_LAYER_SETTING_TYPE_INT32_EXT, 1, &level_none };
	VkLayerSettingsCreateInfoEXT layer_settings{ VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT };
	layer_settings.settingCount = 1;
	layer_settings.pSettings = &setting;

	std::vector<const char*> extensions = { VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
		VK_EXT_LAYER_SETTINGS_EXTENSION_NAME, VK_EXT_DEBUG_REPORT_EXTENSION_NAME };
	VkApplicationInfo app{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
	app.apiVersion = VK_API_VERSION_1_2;
	VkInstanceCreateInfo info{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
	info.pApplicationInfo = &app;
	info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
	info.ppEnabledExtensionNames = extensions.data();
	info.pNext = silenced ? &layer_settings : nullptr;
	VkInstance instance{};
	if (const VkResult r = vkCreateInstance(&info, nullptr, &instance)) { std::printf("PROBE-FAIL vkCreateInstance=%d\n", r); return 2; }

	auto create_callback = reinterpret_cast<PFN_vkCreateDebugReportCallbackEXT>(vkGetInstanceProcAddr(instance, "vkCreateDebugReportCallbackEXT"));
	if (!create_callback) { std::printf("PROBE-FAIL no vkCreateDebugReportCallbackEXT\n"); return 3; }
	VkDebugReportCallbackCreateInfoEXT callback_info{ VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT };
	callback_info.flags = VK_DEBUG_REPORT_ERROR_BIT_EXT | VK_DEBUG_REPORT_WARNING_BIT_EXT;
	callback_info.pfnCallback = callback;
	VkDebugReportCallbackEXT handle{};
	if (create_callback(instance, &callback_info, nullptr, &handle)) { std::printf("PROBE-FAIL create callback\n"); return 4; }

	uint32_t count = 0;
	vkEnumeratePhysicalDevices(instance, &count, nullptr);
	if (!count) { std::printf("PROBE-FAIL no gpu\n"); return 5; }
	std::vector<VkPhysicalDevice> gpus(count);
	vkEnumeratePhysicalDevices(instance, &count, gpus.data());

	float priority = 1.f;
	VkDeviceQueueCreateInfo queue{ VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
	queue.queueCount = 1;
	queue.pQueuePriorities = &priority;
	const char* device_extensions[] = { "VK_KHR_portability_subset" };
	VkDeviceCreateInfo device_info{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
	device_info.queueCreateInfoCount = 1;
	device_info.pQueueCreateInfos = &queue;
	device_info.enabledExtensionCount = 1;
	device_info.ppEnabledExtensionNames = device_extensions;
	VkDevice device{};
	if (vkCreateDevice(gpus[0], &device_info, nullptr, &device)) { std::printf("PROBE-FAIL vkCreateDevice\n"); return 6; }

	uint32_t bad_code[2] = { 0xDEADBEEFu, 0 };
	VkShaderModuleCreateInfo module_info{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
	module_info.codeSize = sizeof(bad_code);
	module_info.pCode = bad_code;
	VkShaderModule module{};
	const VkResult result = vkCreateShaderModule(device, &module_info, nullptr, &module);
	std::printf("PROBE mode=%s shader_result=%d cb_err=%d cb_warn=%d cb_other=%d prefix=%s first=\"%s\"\n",
		argv[1], static_cast<int>(result), g_err, g_warn, g_other, g_prefix.c_str(), g_first.c_str());
	return 0;
}
'''

PROBE_LINE = re.compile(r'^PROBE mode=(\S+) shader_result=(-?\d+) cb_err=(\d+) cb_warn=(\d+) cb_other=(\d+) '
                        r'prefix=(\S*) first="(.*)"$', re.M)
REMEDY = "run `bash ios/scripts/fetch-moltenvk.sh`, or install the Xcode command line tools"


@unittest.skipUnless(sys.platform == "darwin",
                     "the MoltenVK probe needs macOS and Metal; this skip is NOT a pass, run the guard on a Mac")
class MoltenVKProbe(unittest.TestCase):
    """BEHAVIOURAL, on the Mac host, against the pinned MoltenVK 1.4.2 macOS dylib. Not device evidence."""

    def run_mode(self, binary, mode, extra):
        env = {k: v for k, v in os.environ.items() if not k.startswith("MVK_")}
        env.update(extra)
        try:
            return subprocess.run([str(binary), mode], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=60)
        except subprocess.TimeoutExpired:
            self.fail("the probe's {0} scenario did not finish within 60 s".format(mode))

    def probe_line(self, ran, mode):
        out = ran.stdout.decode("utf-8", "replace")
        self.assertEqual(0, ran.returncode, "{0} scenario exit {1}:\n{2}".format(mode, ran.returncode, out))
        m = PROBE_LINE.search(out)
        self.assertIsNotNone(m, "{0} scenario printed no PROBE line:\n{1}".format(mode, out))
        return m

    def test_silenced_writes_nothing_and_the_callback_hears(self):
        cxx = shutil.which("c++")
        self.assertIsNotNone(cxx, "no `c++` on PATH; this test FAILS rather than skips on a Mac: " + REMEDY)
        dylib_dir = PKG / "dylib" / "macOS"
        for need in (PKG / "include", dylib_dir / "libMoltenVK.dylib"):
            self.assertTrue(need.exists(), "{0} is missing: {1}".format(need, REMEDY))
        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "probe.cpp"
            binary = Path(tmp) / "probe"
            driver.write_text(PROBE_SOURCE, encoding="utf-8")
            try:
                built = subprocess.run([cxx, "-std=c++17", "-O0", "-DVK_PROTOTYPES", "-I" + str(PKG / "include"),
                                        str(driver), "-L" + str(dylib_dir), "-lMoltenVK",
                                        "-Wl,-rpath," + str(dylib_dir), "-o", str(binary)],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True,
                                       timeout=300)
            except subprocess.TimeoutExpired:
                self.fail("compiling the MoltenVK probe did not finish within 300 s")
            self.assertEqual(0, built.returncode, "{0} failed:\n{1}".format(cxx, built.stdout))

            control = self.run_mode(binary, "default", {"MVK_CONFIG_LOG_LEVEL": "1"})
            m = self.probe_line(control, "default")
            self.assertEqual("1", m.group(3), "control: the callback did not receive the error: " + m.group(0))
            self.assertIn(b"[mvk-error]", control.stderr, "control: no [mvk-error] on fd 2, so the rig cannot see a write")

            silenced = self.run_mode(binary, "silenced", {"MVK_CONFIG_LOG_LEVEL": "4", "MVK_CONFIG_DEBUG": "1"})
            m = self.probe_line(silenced, "silenced")
            self.assertEqual(b"", silenced.stderr, "silenced: MoltenVK wrote {0} bytes to fd 2:\n{1}".format(
                len(silenced.stderr), silenced.stderr[:2000].decode("utf-8", "replace")))
            self.assertEqual("1", m.group(3), "silenced: the callback did not receive the error: " + m.group(0))
            self.assertEqual("MoltenVK", m.group(6), "silenced: layer prefix is not MoltenVK: " + m.group(0))
            self.assertIn("invalid magic number", m.group(7), "silenced: unexpected message: " + m.group(0))
            print("\nSM4-PROBE-OK control_stderr_bytes={0} silenced_stderr_bytes=0 callback_errors=1 prefix=MoltenVK".format(
                len(control.stderr)), flush=True)


if __name__ == "__main__":
    unittest.main()
