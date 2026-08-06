#!/usr/bin/env python3
"""Generate rpcs3/Emu/RSX/GL/OpenGL_ES_procs.inc from the NDK's Khronos GLES headers.

The output is one X-macro list of every OpenGL ES 2.0/3.0/3.1/3.2 core entry point,
expanded three times by OpenGL_ES.hpp/.cpp (typedef + extern, definition, resolve).

Why function pointers instead of just linking libGLESv3.so: linking binds us to the
system GLES driver for good. Android resolves libEGL.so / libGLESv2.so through the
public-library namespace, so an APK cannot shadow them. Every emulator that ships
ANGLE therefore dlopen()s it under a private soname (libEGL_angle.so) and resolves
every entry point by hand. This table is that hand.

Usage:  python3 android/tools/gen_gles_procs.py <repo-root>
        ARMSX3_NDK=/path/to/ndk  overrides NDK autodetection.
"""
import os
import re
import sys

NDK = os.environ.get(
    "ARMSX3_NDK", os.path.expanduser("~/Library/Android/sdk/ndk/29.0.14206865"))

HEADERS = [
    ("GLES2/gl2.h", "ES 2.0"),
    ("GLES3/gl3.h", "ES 3.0"),
    ("GLES3/gl31.h", "ES 3.1"),
    ("GLES3/gl32.h", "ES 3.2"),
]

PROTO = re.compile(
    r"^GL_APICALL\s+(.+?)\s*GL_APIENTRY\s+(gl\w+)\s*\((.*?)\);", re.M | re.S)

HEADER_TEXT = """\
// GENERATED - do not edit by hand.
// Source: the Khronos GLES headers shipped with the Android NDK.
// Regenerate: python3 android/tools/gen_gles_procs.py <repo-root>
//
// Every OpenGL ES core entry point, expressed as a function pointer rather than a
// link-time symbol. That indirection is the point: it is what lets the core run on
// ANGLE (dlopen'd under a private soname) instead of the system GLES driver.

#ifndef GL_ES_PROC
#error GL_ES_PROC must be defined before including this file
#endif

#define GL_ES_CORE_PROCS \\
"""


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    inc = os.path.join(
        NDK, "toolchains/llvm/prebuilt")
    # darwin-x86_64 / linux-x86_64 / windows-x86_64
    hosts = sorted(os.listdir(inc)) if os.path.isdir(inc) else []
    if not hosts:
        sys.exit(f"no NDK prebuilt toolchain under {inc}")
    inc = os.path.join(inc, hosts[0], "sysroot/usr/include")

    dest = os.path.join(root, "rpcs3/Emu/RSX/GL/OpenGL_ES_procs.inc")
    out = [HEADER_TEXT.rstrip("\n")]
    seen = set()
    count = 0

    for header, label in HEADERS:
        text = open(os.path.join(inc, header)).read()
        out.append("\t/* ---- %s ---- */ \\" % label)
        for ret, name, args in PROTO.findall(text):
            if name in seen:
                continue
            seen.add(name)
            count += 1
            out.append("\tGL_ES_PROC(%s, %s, (%s)) \\"
                       % (" ".join(ret.split()), name, " ".join(args.split())))

    out.append("\t/* end */")
    out.append("")

    os.makedirs(os.path.dirname(dest), exist_ok=True)
    open(dest, "w").write("\n".join(out))
    print("%s: %d core entry points" % (dest, count))


main()
