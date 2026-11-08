#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""librashader is reachable on iOS but never linked: the core's shader pieces sit under the Android-or-iOS guard with
TargetConditionals included first, the CMake, project.yml and build scripts embed the pinned dylib and sign it ad hoc.
It reads text only and cannot tell whether a chain renders on MoltenVK."""

import re
import unittest
from pathlib import Path

from source_helpers import yaml_without_comments
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "VKPresent.cpp": ROOT / "rpcs3" / "Emu" / "RSX" / "VK" / "VKPresent.cpp",
    "system_config.h": ROOT / "rpcs3" / "Emu" / "system_config.h",
    "system_config_types.h": ROOT / "rpcs3" / "Emu" / "system_config_types.h",
    "system_config_types.cpp": ROOT / "rpcs3" / "Emu" / "system_config_types.cpp",
    "CMakeLists.txt": ROOT / "ios" / "CMakeLists.txt",
    "project.yml": ROOT / "ios" / "project.yml",
    "build-librashader-ios.sh": ROOT / "ios" / "scripts" / "build-librashader-ios.sh",
    "build-ios-ipa.sh": ROOT / "ios" / "scripts" / "build-ios-ipa.sh",
}

WIDE = "#if defined(__ANDROID__) || (defined(__APPLE__) && TARGET_OS_IPHONE)"
ANDROID = "#ifdef __ANDROID__"
APPLE = "#ifdef __APPLE__"
PIN = "87e8a97b50516d997defeaa168173dcd185d4022"

# Per core file: (shader-piece patterns, SGSR-piece patterns), matched against comment-stripped lines.
PIECES = {
    "VKPresent.cpp": ((r'#include "upscalers/librashader_pass\.h"', r"output_scaling_mode::shader\b"),
                      (r'#include "upscalers/sgsr_pass\.h"', r"output_scaling_mode::sgsr\b",
                       r"output_scaling_mode::sgsr_edge\b")),
    "system_config.h": ((r"\bshader_preset_path\b",), ()),
    "system_config_types.h": ((r"^\s*shader\s*,",), (r"^\s*sgsr\s*,", r"^\s*sgsr_edge\b")),
    "system_config_types.cpp": ((r"case output_scaling_mode::shader:",),
                                (r"case output_scaling_mode::sgsr:", r"case output_scaling_mode::sgsr_edge:")),
}

WHY = ("Only the librashader pieces were widened to iOS; SGSR stays Android-only, and "
       "librashader ships as its own dylib that the core dlopens.")


def read(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise ValueError("%s is empty" % path)
    return text


def directive(line):
    """The normalised preprocessor directive on `line`, or None."""
    found = re.match(r"^\s*#\s*(\w+)(.*)$", line)
    if not found:
        return None
    return "#" + found.group(1) + (" " + found.group(2).strip() if found.group(2).strip() else "")


def enclosing(code):
    """[(line, innermost open condition or None)] for comment-stripped `code`, an #if stack per line."""
    stack, out = [], []
    for line in code.splitlines():
        d = directive(line)
        if d and d.split()[0] in ("#if", "#ifdef", "#ifndef"):
            stack.append(d)
        elif d and d.split()[0] in ("#elif", "#else"):
            if stack:
                stack[-1] = d
        elif d and d.split()[0] == "#endif":
            if stack:
                stack.pop()
        out.append((line, stack[-1] if stack else None))
    return out


def guards_problems(name, code):
    problems = []
    shader, sgsr = PIECES[name]
    lines = enclosing(code)
    for patterns, want in ((shader, WIDE), (sgsr, ANDROID)):
        for pattern in patterns:
            hits = [cond for line, cond in lines if re.search(pattern, line)]
            if not hits:
                problems.append("guards: %s has no line matching %r" % (name, pattern))
            for cond in hits:
                if cond != want:
                    problems.append("guards: %s has %r under %r, not %r" % (name, pattern, cond, want))
    return problems


def ordinal_problems(code):
    found = re.search(r"enum class output_scaling_mode\s*\{(.*?)\};", code, re.S)
    if not found:
        return ["ordinal: enum class output_scaling_mode is not found"]
    names = []
    for line in found.group(1).splitlines():
        if directive(line):
            continue
        names += [part.strip().split("=")[0].strip() for part in line.split(",") if part.strip()]
    if "fsr" not in names or names.index("fsr") + 1 >= len(names) or names[names.index("fsr") + 1] != "shader":
        return ["ordinal: the enumerator after fsr is not shader (%s)" % ", ".join(names)]
    if names.index("shader") != 3:
        return ["ordinal: shader is enumerator %d, not 3" % names.index("shader")]
    return []


def first_line(lines, pattern):
    for index, (line, _) in enumerate(lines):
        if re.search(pattern, line):
            return index
    return None


def reach_problems(name, code):
    lines = enclosing(code)
    use = first_line(lines, r"\bTARGET_OS_IPHONE\b")
    if use is None:
        return ["reach: %s never names TARGET_OS_IPHONE" % name]
    at = first_line(lines, r"^\s*#\s*include\s*<TargetConditionals\.h>")
    if at is None or at > use:
        return ["reach: %s does not include <TargetConditionals.h> before its first TARGET_OS_IPHONE" % name]
    if lines[at][1] != APPLE:
        return ["reach: %s includes <TargetConditionals.h> under %r, not %r" % (name, lines[at][1], APPLE)]
    return []


def cmake_problems(cmake):
    code = "\n".join(line.split("#", 1)[0] for line in cmake.splitlines())
    problems = []
    if not re.search(r"set\(ARMSX3_IOS_LIBRASHADER_INCLUDE\s+\$\{CMAKE_SOURCE_DIR\}/ios/build-deps/librashader-src/include\)", code):
        problems.append("cmake: ARMSX3_IOS_LIBRASHADER_INCLUDE is not set to the pinned checkout's include folder")
    fatal = re.search(r'if\(NOT EXISTS "\$\{ARMSX3_IOS_LIBRASHADER_INCLUDE\}/librashader_ld\.h"\)\s*message\(FATAL_ERROR(.*?)\)\s*endif\(\)',
                      cmake, re.S)
    if not fatal or "build-librashader-ios.sh" not in fatal.group(1):
        problems.append("cmake: no FATAL_ERROR naming build-librashader-ios.sh when librashader_ld.h is absent")
    if not re.search(r"target_include_directories\(rpcs3_emu PRIVATE\s+\$\{ARMSX3_IOS_LIBRASHADER_INCLUDE\}\s*\)", code):
        problems.append("cmake: rpcs3_emu does not get ARMSX3_IOS_LIBRASHADER_INCLUDE")
    return problems


def embed_problems(project, cmake):
    problems = []
    code = yaml_without_comments(project)
    found = re.search(r"^([ ]*)- framework: build-deps/librashader-ios/librashader\.dylib\n((?:\1  \S.*\n?)*)", code, re.M)
    if not found:
        problems.append("embed: ios/project.yml has no build-deps/librashader-ios/librashader.dylib dependency")
    else:
        keys = dict(line.strip().split(": ", 1) for line in found.group(2).splitlines() if ": " in line)
        if keys.get("embed") != "true":
            problems.append("embed: the librashader dependency is not embed: true")
        if keys.get("link") != "false":
            problems.append("embed: the librashader dependency is not link: false, so the app would link it")
    cmake_code = "\n".join(line.split("#", 1)[0] for line in cmake.splitlines())
    for label, text in (("ios/project.yml", code), ("ios/CMakeLists.txt", cmake_code)):
        if re.search(r"librashader_capi\.a\b", text):
            problems.append("embed: %s names the capi staticlib" % label)
    return problems


def script_problems(script):
    code = "\n".join(line for line in script.splitlines() if not line.lstrip().startswith("#"))
    problems = []
    for label, pattern in (("pin", r"^PIN=" + PIN + r"$"), ("locked", r"--locked\b"),
                           ("no-default-features", r"--no-default-features\b"),
                           ("runtime-vulkan", r"--features runtime-vulkan\b"),
                           ("target", r"--target aarch64-apple-ios\b"),
                           ("deployment", r"^export IPHONEOS_DEPLOYMENT_TARGET=17\.4$"),
                           ("install-name", r"install_name_tool -id @rpath/librashader\.dylib\b"),
                           ("cdylib", r"liblibrashader_capi\.dylib\b")):
        if not re.search(pattern, code, re.M):
            problems.append("script: build-librashader-ios.sh lacks %s" % label)
    if re.search(r"librashader_capi\.a\b", code):
        problems.append("script: build-librashader-ios.sh names the capi staticlib")
    return problems


def ipa_problems(ipa):
    code = "\n".join(line for line in ipa.splitlines() if not line.lstrip().startswith("#"))
    blocks = [b for b in re.findall(r'^if \[\[ "\$\{SIGN\}" == 0 \]\]; then\n(.*?)^fi$', code, re.M | re.S)
              if 'codesign --force --sign - --entitlements "${ADHOC_APP_ENTS}" "${APP}"' in b]
    if len(blocks) != 1:
        return ["ipa: the SIGN=0 block that ad-hoc signs the app is not found exactly once"]
    block = blocks[0]
    loop = block.find('for dylib in "${APP}"/Frameworks/*.dylib; do')
    sign = re.search(r'^\s*codesign --force --sign - (.*)"\$\{dylib\}"\s*$', block, re.M)
    if loop < 0 or not sign or sign.start() < loop:
        return ["ipa: the SIGN=0 block does not sign Frameworks/*.dylib ad-hoc"]
    if "--entitlements" in sign.group(1):
        return ["ipa: the SIGN=0 block signs the dylib with entitlements"]
    if sign.start() > block.index('"${ADHOC_APP_ENTS}" "${APP}"'):
        return ["ipa: the SIGN=0 block signs the app before the dylib"]
    return []


def librashader_problems(texts):
    """`texts` is {file name: text} for every key in FILES. Prefixed problem strings."""
    problems = []
    code = dict((name, without_comments(texts[name])) for name in PIECES)
    for name in PIECES:
        problems += guards_problems(name, code[name])
    problems += ordinal_problems(code["system_config_types.h"])
    for name in PIECES:
        problems += reach_problems(name, code[name])
    problems += cmake_problems(texts["CMakeLists.txt"])
    problems += embed_problems(texts["project.yml"], texts["CMakeLists.txt"])
    problems += script_problems(texts["build-librashader-ios.sh"])
    problems += ipa_problems(texts["build-ios-ipa.sh"])
    return problems


def real_texts():
    return dict((name, read(path)) for name, path in FILES.items())


class TheRealFilesHold(unittest.TestCase):
    def test_the_real_files_have_no_problems(self):
        self.assertEqual([], librashader_problems(real_texts()), WHY)


class LibrashaderMutations(unittest.TestCase):
    """Each method makes ONE edit to one real file and shows the matching check fail."""

    @classmethod
    def setUpClass(cls):
        cls.texts = real_texts()

    def edited(self, text, old, new):
        self.assertIn(old, text, "mutation anchor %r not found" % old)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def has(self, prefix, name, old, new):
        texts = dict(self.texts)
        texts[name] = self.edited(texts[name], old, new)
        found = librashader_problems(texts)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), "%s not in %s" % (prefix, found))

    def test_guards_include_back_under_android(self):
        self.has("guards", "VKPresent.cpp", WIDE + '\n#include "upscalers/librashader_pass.h"',
                 ANDROID + '\n#include "upscalers/librashader_pass.h"')

    def test_guards_sgsr_widened(self):
        self.has("guards", "system_config_types.cpp",
                 ANDROID + "\n\t\tcase output_scaling_mode::sgsr:", WIDE + "\n\t\tcase output_scaling_mode::sgsr:")

    def test_ordinal_shader_inserted_before_fsr(self):
        self.has("ordinal", "system_config_types.h", "\tbilinear,\n\tfsr,", "\tbilinear,\n\tshader_x,\n\tfsr,")

    def test_reach_target_conditionals_dropped(self):
        self.has("reach", "system_config_types.h", "#include <TargetConditionals.h>", "")

    def test_reach_target_conditionals_only_transitive(self):
        self.has("reach", "system_config.h", "#include <TargetConditionals.h>", "")

    def test_cmake_fatal_error_dropped(self):
        self.has("cmake", "CMakeLists.txt", "run ios/scripts/build-librashader-ios.sh", "run the script")

    def test_embed_linked(self):
        self.has("embed", "project.yml", "embed: true\n        codeSign: true\n        link: false",
                 "embed: true\n        codeSign: true\n        link: true")

    def test_script_unlocked(self):
        self.has("script", "build-librashader-ios.sh", "cargo build --release --locked", "cargo build --release")

    def test_ipa_dylib_not_signed(self):
        self.has("ipa", "build-ios-ipa.sh", 'for dylib in "${APP}"/Frameworks/*.dylib; do', "for dylib in; do")


if __name__ == "__main__":
    unittest.main()
