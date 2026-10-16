#!/usr/bin/env python3
"""Writes the data behind About > Open-source licenses.

Every third-party component that ends up inside the APK, with its license text. BSD, MIT,
Apache-2.0 and the rest only allow shipping a binary when their notices go with it, and the
APK is that binary.

Where each text comes from:
  - 3rdparty/* and the app's own sources, for everything built from the tree;
  - android/tools/licenses/, for code that arrives prebuilt or from a checkout that is not
    tracked here (WebRTC and what is compiled into it, ANGLE, the icon packs, abseil,
    libadrenotools, lsfg-vk) -- copied verbatim from each project's repository;
  - android/tools/licenses/librashader-crates.json, for the Rust crates inside librashader.so.

    python3 android/tools/gen_open_source_licenses.py           # rewrite the assets
    python3 android/tools/gen_open_source_licenses.py --crates  # refresh the crate snapshot
                                                                  # from ~/.cargo, after a
                                                                  # librashader build

Adding a library to the build means adding it here. The output is two files: the main one
for both flavors, and src/github/assets for what only the GitHub build bundles (frame
generation), so the Play build never lists it.
"""

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIC = os.path.join(ROOT, "android", "tools", "licenses")
APP = os.path.join(ROOT, "android", "armsx3-ui", "app")
CRATES = os.path.join(LIC, "librashader-crates.json")


def normalize(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")
    lines = [line.rstrip() for line in text.split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines))


def read(path):
    if not os.path.isabs(path):
        path = os.path.join(ROOT, path)
    with open(path, encoding="utf-8", errors="replace") as f:
        return normalize(f.read())


def lic(name):
    return read(os.path.join(LIC, name))


def block(path, start, end):
    """The lines from the first one matching `start` to the next one matching `end`, with
    comment markers taken off. For licenses that only exist as a source file's header."""
    lines = read(path).split("\n")
    first = next(i for i, line in enumerate(lines) if re.search(start, line))
    last = next(i for i in range(first, len(lines)) if re.search(end, lines[i]))
    out = []
    for line in lines[first:last + 1]:
        line = re.sub(r"\s*\*/\s*$", "", line)
        line = re.sub(r"^\s*(?:/\*+|//+|\*+)\s?", "", line)
        out.append(line)
    return normalize("\n".join(out))


TEXTS = {
    "apache-2.0": read("3rdparty/oboe/oboe/LICENSE"),
    "gpl-2.0": read("LICENSE"),
    "gpl-3.0": read("3rdparty/wolfssl/wolfssl/COPYING"),
    "lgpl-2.1": read("3rdparty/ffmpeg/copyright"),
    "mpl-2.0": read("3rdparty/librashader/LICENSE.md"),
    "llvm": read("3rdparty/llvm/llvm/llvm/LICENSE.TXT"),
    "ofl-1.1": lic("ofl-1.1.txt"),
    "bsd-3-clause": lic("bsd-3-clause.txt"),
    "mit": lic("mit.txt"),
}


def c(name, license, text=None, text_id=None, notice=None, children=None):
    entry = {"name": name, "license": license}
    if notice:
        entry["notice"] = notice
    if text:
        entry["text"] = text
    if text_id:
        assert text_id in TEXTS, text_id
        entry["textId"] = text_id
    if children:
        entry["children"] = children
    return entry


def apache(name, holder, notice=None):
    lines = [f"Copyright {holder}."]
    if notice:
        lines.append(notice)
    return c(name, "Apache-2.0", text_id="apache-2.0", notice="\n".join(lines))


def crate_children():
    with open(CRATES, encoding="utf-8") as f:
        crates = json.load(f)
    children = []
    for crate in crates:
        entry = {"name": f"{crate['name']} {crate['version']}", "license": crate["license"]}
        if crate.get("text"):
            entry["text"] = crate["text"]
        else:
            entry["textId"] = crate["textId"]
        if crate.get("notice"):
            entry["notice"] = crate["notice"]
        children.append(entry)
    return children


def components():
    libusb_authors = [line for line in read("3rdparty/libusb/libusb/AUTHORS").split("\n") if line.startswith("Copyright")]
    return [
        c("ARMSX3", "GPL-2.0-only", text_id="gpl-2.0",
          notice="ARMSX3 is free software, based on RPCS3 (https://github.com/RPCS3/rpcs3).\n"
                 "Source code: https://github.com/ARMSX2/ARMSX3"),

        # Built into the emulator core (libarmsx3-core.so).
        c("LLVM (with libc++)", "Apache-2.0 WITH LLVM-exception", text_id="llvm",
          notice="LLVM is linked into the emulator core. libc++_shared.so is LLVM's libc++, from the Android NDK."),
        c("FFmpeg", "LGPL-2.1-or-later", text_id="lgpl-2.1",
          notice="Copyright (c) the FFmpeg developers. Source code: https://ffmpeg.org"),
        c("libusb", "LGPL-2.1-or-later", text_id="lgpl-2.1",
          notice="\n".join(libusb_authors) + "\nand the other libusb contributors. Source code: https://libusb.info"),
        c("SoundTouch", "LGPL-2.1", text_id="lgpl-2.1",
          notice="Copyright (c) Olli Parviainen. Source code: https://www.surina.net/soundtouch"),
        c("cubeb", "ISC", read("3rdparty/cubeb/cubeb/LICENSE")),
        c("Speex resampler", "BSD-3-Clause",
          block("3rdparty/cubeb/cubeb/subprojects/speex/speex_resampler.h", r"Copyright", r"SUCH DAMAGE")),
        apache("Oboe", "2015 The Android Open Source Project"),
        c("miniupnpc", "BSD-3-Clause", read("3rdparty/miniupnp/miniupnp/miniupnpc/LICENSE")),
        c("Protocol Buffers", "BSD-3-Clause", read("3rdparty/protobuf/protobuf/LICENSE")),
        c("utf8_range", "MIT", read("3rdparty/protobuf/protobuf/third_party/utf8_range/LICENSE")),
        apache("Abseil", "The Abseil Authors", "Used by the emulator core and by WebRTC."),
        c("pugixml", "MIT", read("3rdparty/pugixml/LICENSE.md")),
        c("glslang", "BSD-3-Clause and others", read("3rdparty/glslang/glslang/LICENSE.txt")),
        c("libpng", "libpng-2.0", read("3rdparty/libpng/libpng/LICENSE")),
        c("RtMidi", "MIT", read("3rdparty/rtmidi/rtmidi/LICENSE")),
        c("yaml-cpp", "MIT", read("3rdparty/yaml-cpp/yaml-cpp/LICENSE")),
        c("libchdr", "BSD-3-Clause", read("3rdparty/libchdr/LICENSE.txt")),
        c("LZMA SDK", "Public domain", read("3rdparty/libchdr/deps/lzma-26.02/LICENSE")),
        c("miniz", "MIT",
          block("3rdparty/libchdr/deps/miniz-3.1.2/miniz.c", r"Copyright 2013-2014 RAD", r"SOFTWARE\.$")),
        c("Zstandard", "BSD-3-Clause", read("3rdparty/zstd/zstd/LICENSE")),
        c("curl", "curl", read("3rdparty/curl/curl/COPYING")),
        c("wolfSSL", "GPL-3.0-or-later", text_id="gpl-3.0",
          notice="Copyright (C) 2006-2026 wolfSSL Inc. Source code: https://github.com/wolfSSL/wolfssl"),
        c("HIDAPI", "BSD-3-Clause", read("3rdparty/hidapi/hidapi/LICENSE-bsd.txt"),
          notice="HIDAPI can be used under any of three licenses; ARMSX3 uses it under this one."),
        c("AsmJit", "Zlib", read("3rdparty/asmjit/asmjit/LICENSE.md")),
        c("Fusion", "MIT", read("3rdparty/fusion/fusion/LICENSE.md")),
        c("sse2neon", "MIT", block("rpcs3/Emu/CPU/sse2neon.h", r"Contributors to this work are", r"SOFTWARE\.$")),
        c("stb", "MIT or Unlicense",
          block("3rdparty/stblib/stb/stb_image.h", r"This software is available under 2 licenses", r"^\s*\*/\s*$")),
        c("unordered_dense", "MIT",
          block("3rdparty/unordered_dense/include/unordered_dense.h", r"Licensed under the MIT License", r"SOFTWARE\.$")),
        c("Vulkan Memory Allocator", "MIT", read("3rdparty/GPUOpen/VulkanMemoryAllocator/LICENSE.txt")),
        c("AMD FidelityFX Super Resolution 1", "MIT",
          block("3rdparty/GPUOpen/include/ffx_a.h", r"Copyright \(c\) 2021 Advanced Micro Devices", r"THE SOFTWARE\.")),
        c("Snapdragon Game Super Resolution", "BSD-3-Clause", text_id="bsd-3-clause",
          notice="Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved."),

        # The app's own native libraries.
        c("libadrenotools", "BSD-2-Clause", lic("libadrenotools-LICENSE.txt")),
        c("Flurry", "BSD-3-Clause",
          block("android/armsx3-ui/app/src/main/cpp/flurry/flurry.c", r"Copyright \(c\) 2002, Calum Robinson", r"SUCH DAMAGE")),
        c("Really Slick Screensavers", "GPL-2.0-or-later", text_id="gpl-2.0",
          notice="Flux, Hyperspace, Lattice, Plasma, Skyrocket and Solar Winds, each under the same notice:\n\n"
                 + block("android/armsx3-ui/app/src/main/cpp/savers/solarwinds/solarWinds.cpp", r"Copyright \(C\)", r"Boston|gnu\.org|USA")),

        # Prebuilt libraries.
        c("ANGLE", "BSD-3-Clause", re.sub(r"(?m)^// ?", "", lic("angle-LICENSE.txt"))),
        c("librashader", "MPL-2.0", text_id="mpl-2.0",
          notice="librashader is available under MPL-2.0 or GPL-3.0; ARMSX3 ships its unmodified release "
                 "build under MPL-2.0. Source code: https://github.com/SnowflakePowered/librashader\n"
                 "It is built from the Rust crates listed under it, each with its own license.",
          children=crate_children()),
        c("WebRTC", "BSD-3-Clause",
          lic("webrtc-LICENSE.txt") + "\n\nAdditional IP Rights Grant (Patents)\n\n" + lic("webrtc-PATENTS.txt"),
          notice="Included as libjingle_peerconnection_so.so, from stream-webrtc-android, for remote play. "
                 "It contains the libraries listed after it."),
        apache("stream-webrtc-android", "Stream.io Inc.", "Source code: https://github.com/GetStream/webrtc-android"),
        c("BoringSSL", "Apache-2.0 and others", lic("boringssl-LICENSE.txt")),
        c("libvpx", "BSD-3-Clause", lic("libvpx-LICENSE.txt") + "\n\n" + lic("libvpx-PATENTS.txt")),
        c("libaom", "BSD-2-Clause", lic("libaom-LICENSE.txt") + "\n\n" + lic("libaom-PATENTS.txt")),
        c("dav1d", "BSD-2-Clause", lic("dav1d-COPYING.txt")),
        c("libsrtp", "BSD-3-Clause", lic("libsrtp-LICENSE.txt")),
        c("usrsctp", "BSD-3-Clause", lic("usrsctp-LICENSE.txt")),
        c("libyuv", "BSD-3-Clause", lic("libyuv-LICENSE.txt")),
        c("Opus", "BSD-3-Clause", lic("opus-COPYING.txt")),
        c("libevent", "BSD-3-Clause", lic("libevent-LICENSE.txt")),
        c("Discord Social SDK", "Proprietary",
          notice="libdiscord_partner_sdk.so is Discord's, used under the Discord Developer Terms of Service. "
                 "It is not open source."),

        # Java and Kotlin libraries.
        apache("AndroidX and Jetpack Compose", "The Android Open Source Project"),
        apache("Material Components for Android", "Google LLC"),
        apache("Accompanist", "Google LLC"),
        apache("Kotlin, kotlinx.coroutines and kotlinx.serialization",
               "JetBrains s.r.o. and Kotlin Programming Language contributors"),
        apache("JetBrains Java Annotations", "JetBrains s.r.o."),
        apache("Coil", "Coil Contributors"),
        apache("OkHttp and Okio", "Square, Inc."),
        apache("Guava ListenableFuture", "The Guava Authors"),
        apache("Error Prone annotations", "The Error Prone Authors"),
        apache("JSpecify", "The JSpecify Authors"),
        c("compose-icons", "MIT", lic("compose-icons-LICENSE.txt")),
        c("Font Awesome Free", "CC-BY-4.0, OFL-1.1 and MIT", lic("fontawesome5-LICENSE.txt"),
          notice="The icons come through compose-icons. https://fontawesome.com"),
        c("Line Awesome", "MIT", lic("line-awesome-LICENSE.txt"),
          notice="The icons come through compose-icons. https://icons8.com/line-awesome"),
        c("Bebas Neue", "OFL-1.1", text_id="ofl-1.1",
          notice="Copyright 2019 The Bebas Neue Project Authors (https://github.com/dharmatype/Bebas-Neue).\n"
                 "Bebas Neue is a trademark of Dharma Type."),
    ]


def github_components():
    note = "Frame generation, in the GitHub build only."
    return [
        c("lsfg-vk", "MIT", lic("lsfg-vk-LICENSE.txt"), notice=note),
        c("DXVK (DXBC translation)", "Zlib", lic("dxbc-LICENSE.txt"), notice=note),
        c("pe-parse", "MIT", lic("pe-parse-LICENSE.txt"), notice=note),
        c("volk", "MIT", lic("volk-LICENSE.txt"), notice=note),
    ]


def write(path, comps):
    used = {comp["textId"] for comp in comps if "textId" in comp}
    used |= {child["textId"] for comp in comps for child in comp.get("children", []) if "textId" in child}
    data = {"version": 1, "texts": {k: v for k, v in TEXTS.items() if k in used}, "components": comps}
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{os.path.relpath(path, ROOT)}: {len(comps)} components, {os.path.getsize(path) // 1024} KB")


def refresh_crates():
    """librashader-crates.json from the crates in ~/.cargo that librashader's Cargo.lock names,
    i.e. what a librashader build on this machine fetched. MIT texts are taken per crate, since
    they carry the crate's own copyright line; Apache-2.0-only crates point at the shared text."""
    lock = open(os.path.join(ROOT, "3rdparty", "librashader", "Cargo.lock"), encoding="utf-8").read()
    wanted = set(re.findall(r'\[\[package\]\]\nname = "([^"]+)"\nversion = "([^"]+)"\nsource = "registry', lock))
    registry = glob.glob(os.path.expanduser("~/.cargo/registry/src/index.crates.io-*"))
    if not registry:
        sys.exit("no ~/.cargo registry: build librashader first")
    crates = []
    for name, version in sorted(wanted):
        path = os.path.join(registry[0], f"{name}-{version}")
        if not os.path.isdir(path):
            continue
        toml = open(os.path.join(path, "Cargo.toml"), encoding="utf-8", errors="replace").read()
        m = re.search(r'^license\s*=\s*"([^"]+)"', toml, re.M)
        expr = m.group(1) if m else ""
        files = {os.path.basename(p).lower(): p for p in glob.glob(os.path.join(path, "*"))
                 if re.match(r"(?i)(licen[cs]e|copying|unlicense|notice)", os.path.basename(p))}
        entry = {"name": name, "version": version, "license": expr or "see crate"}
        mit = next((files[k] for k in sorted(files) if "mit" in k), None)
        if "MIT" in expr and mit:
            entry["text"] = read(mit)
        elif "Apache-2.0" in expr:
            entry["textId"] = "apache-2.0"
            notice = next((files[k] for k in files if k.startswith("notice")), None)
            if notice:
                entry["notice"] = read(notice)
        elif files:
            entry["text"] = "\n\n".join(read(files[k]) for k in sorted(files) if "apache" not in k)
        elif "MIT" in expr:
            entry["textId"] = "mit"
            entry["notice"] = f"Copyright the {name} authors."
        else:
            sys.exit(f"{name} {version}: no license text and license '{expr}'")
        crates.append(entry)
    with open(CRATES, "w", encoding="utf-8") as f:
        json.dump(crates, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{os.path.relpath(CRATES, ROOT)}: {len(crates)} of {len(wanted)} crates in Cargo.lock")


if __name__ == "__main__":
    if "--crates" in sys.argv:
        refresh_crates()
        sys.exit(0)
    write(os.path.join(APP, "src", "main", "assets", "licenses", "open_source_licenses.json"), components())
    write(os.path.join(APP, "src", "github", "assets", "licenses", "open_source_licenses_github.json"),
          github_components())
