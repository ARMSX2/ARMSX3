#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The title config header compiled with the host compiler: the database merge, the deny lists, the iOS-only rows and the redirect table.
One mutation per check edits the real header and shows the driver fail.
"""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import abi_text


ROOT = Path(__file__).resolve().parents[2]

HEADER = ROOT / "ios" / "bridge" / "title_config.h"

ABI_DIR = ROOT / "ios" / "bridge"

RENDER = ROOT / "ios" / "core" / "render" / "ios_render.cpp"

# The API's shape as it answers; ABCD12345 is synthetic and malformed on purpose, and BCUS98234 is synthetic with NPUB91037's keys.
DATABASE = """{
    "return_code": 0,
    "timestamp": 1790718361,
    "games": {
        "BCJS30022": {
            "config": "Video:\\n  Write Color Buffers: true"
        },
        "BLES00932": {
            "config": "Video:\\n  Write Color Buffers: true"
        },
        "BLUS30443": {
            "config": "Video:\\n  Write Color Buffers: true"
        },
        "NPEA00021": {
            "config": "Video:\\n  Frame limit: Auto"
        },
        "NPUB91037": {
            "config": "Core:\\n  PPU Decoder: Interpreter (static)\\nVideo:\\n  Write Color Buffers: true"
        },
        "BCUS98234": {
            "config": "Core:\\n  PPU Decoder: Interpreter (static)\\nVideo:\\n  Write Color Buffers: true"
        },
        "ABCD12345": {
            "config": "Video:\\n  Half Key: true\\nCore: a: b"
        }
    }
}
"""

DES = ("BLUS30443", "BCJS30022", "BLES00932")

FLOW = "NPEA00021"

UNKNOWN = ("BLUS99999", "../x/PARAM")

KOTLIN_CONFIG_DB = ROOT / "android" / "armsx3-ui" / "app" / "src" / "main" / "java" / "com" / "armsx2" / "config" / "ConfigDatabase.kt"

KILLZONE = "BCUS98234"

IOS_ONLY_REQUIRED = ("NPUB30558", "BLUS30837", "BLES01613", "BLUS30405", "BLES00713",
                     "BLES00714", "BLES00158", "BLUS30089", "BLUS30065", "BLES00142")

IOS_ONLY_NO_INTERPRETER = "Video:\\n  Shader Mode: Async Recompiler (multi-threaded)\\n"

IOS_ONLY_SPU_COMPILE_PAUSE = "Core:\\n  Pause Game During SPU Compilation: true\\n"

IOS_ONLY_DANTE_EU_PARK = "Core:\\n  Pause Game During SPU Compilation: true\\n  SPU Poll Park Sites: 3f47c:40801083\\n"

IOS_ONLY_STUB_TRAPS = "Core:\\n  Stub PPU Traps: 1\\n"

IOS_ONLY_APPROXIMATE_ZCULL = "Video:\\n  Accurate ZCULL stats: false\\n  Relaxed ZCULL Sync: false\\n"

IOS_ONLY_HIGH_PRECISION = "Video:\\n  Shader Precision: High\\n"

IOS_ONLY_ROWS = {
    "NPUB30558": "Core:\\n  SPU loop detection: true\\n",
    "BLUS30837": IOS_ONLY_NO_INTERPRETER,
    "BLES01613": IOS_ONLY_NO_INTERPRETER,
    "BLUS30405": IOS_ONLY_SPU_COMPILE_PAUSE,
    "BLES00713": IOS_ONLY_DANTE_EU_PARK,
    "BLES00714": IOS_ONLY_SPU_COMPILE_PAUSE,
    "BLES00158": IOS_ONLY_APPROXIMATE_ZCULL,
    "BLUS30089": IOS_ONLY_APPROXIMATE_ZCULL,
    "BLUS30065": IOS_ONLY_HIGH_PRECISION,
    "BLES00142": IOS_ONLY_HIGH_PRECISION,
    "BCES01176": IOS_ONLY_STUB_TRAPS,
    "BCJS37004": IOS_ONLY_STUB_TRAPS,
    "BCAS25009": IOS_ONLY_STUB_TRAPS,
    "BCUS99086": IOS_ONLY_STUB_TRAPS,
    "BCES01670": IOS_ONLY_STUB_TRAPS,
    "BCES01692": IOS_ONLY_STUB_TRAPS,
    "BCAS25014": IOS_ONLY_STUB_TRAPS,
    "NPUA80858": IOS_ONLY_STUB_TRAPS,
    "NPEA00422": IOS_ONLY_STUB_TRAPS,
    "BCUS98232": IOS_ONLY_STUB_TRAPS,
    "BCES01741": IOS_ONLY_STUB_TRAPS,
    "BCES01742": IOS_ONLY_STUB_TRAPS,
    "BCAS25016": IOS_ONLY_STUB_TRAPS,
    "BCJS37008": IOS_ONLY_STUB_TRAPS,
    "NPUA80918": IOS_ONLY_STUB_TRAPS,
    "NPEA00445": IOS_ONLY_STUB_TRAPS,
    "NPHA80258": IOS_ONLY_STUB_TRAPS,
}

FIX_PATHS = ["Core@@PPU Decoder", "Video@@Frame limit", "Video@@Write Color Buffers"]

DRIVER = r"""
#include "title_config.h"
#include <cstdio>
#include <fstream>
#include <sstream>
using namespace armsx3::bridge::title_config;
static std::string slurp(const char* p) { std::ifstream f(p); std::stringstream s; s << f.rdbuf(); return s.str(); }
int main(int argc, char** argv)
{
    const std::string db = slurp(argv[1]), product = slurp(argv[2]);
    std::string fixes;
    for (const std::string& path : fix_paths(db)) fixes += path + "\n";
    std::printf("== FIX known=0 added=0\n%s--END--\n", fixes.c_str());
    for (int i = 3; i < argc; i++)
    {
        const std::string a = argv[i];
        // '%SERIAL' and '^SERIAL' are title_layer with the database and with none.
        if (a[0] == '%' || a[0] == '^')
        {
            const std::string serial = a.substr(1), entry = lookup(a[0] == '%' ? db : std::string(), serial);
            const merged m = title_layer(entry, serial, [](const std::vector<std::string>&, const item&) { return true; });
            std::printf("== %s known=%d added=%zu\n%s--END--\n", a.c_str(), entry.empty() ? 0 : 1, m.added, m.text.c_str());
            continue;
        }
        // '@file' is an entry read from a file; '!SERIAL' is a lookup in an empty database, which is
        // what the bridge has when no copy was ever fetched.
        const std::string entry = a[0] == '@' ? slurp(a.c_str() + 1) : a[0] == '!' ? lookup(std::string(), a.substr(1)) : lookup(db, a);
        const merged m = merge(product, entry, [](const std::vector<std::string>& p, const item&) { return p.back() != "Nonexistent Key"; });
        std::printf("== %s known=%d added=%zu\n%s--END--\n", a.c_str(), entry.empty() ? 0 : 1, m.added, m.text.c_str());
    }
    return 0;
}
"""

SYNTHETIC = {
    "product.yml": "Video:\n  Renderer: OpenGL\n  Write Color Buffers: true\n",
    "filter.yml": "Video:\n  Nonexistent Key: true\n  Write Color Buffers: true\n",
    "deny-off.yml": "Core:\n  Max SPURS Threads: 3\nVideo:\n  Frame limit: Off\n  Write Color Buffers: true\n",
    "deny-infinite.yml": "Core:\n  Max SPURS Threads: 4\nVideo:\n  Frame limit: Infinite\n  Write Color Buffers: true\n",
    "deny-keep.yml": "Video:\n  Frame limit: 30\n",
}

# The titles the merge acts on, the one place a reference serial is named; Demon's Souls discs, then PSN.
REDIRECT_SERIALS = ("BLUS30443", "BLES00932", "BCJS30022", "BCAS20071", "NPUB30910", "NPEB01202", "NPJA00102")

KOTLIN_GAME_DEFAULTS = ROOT / "android" / "armsx3-ui" / "app" / "src" / "main" / "java" / "com" / "armsx2" / "config" / "GameDefaults.kt"

RRM_LINE = "RRM empty=6 theirs=5 case=5 serials=7 nontable=0 malformed=1"

# The player's entry names the table's third host, read from the table rather than typed here.
REDIRECT_DRIVER = r"""
#include "title_config.h"
#include <cstdio>
#include <iterator>
using namespace armsx3::bridge::title_config;
static std::size_t added(const std::string& list, std::string_view serial, std::string* out = nullptr)
{
    std::size_t n = 99;
    const std::string merged = with_title_redirects(list, serial, n);
    if (out != nullptr) *out = merged;
    return n;
}
int main()
{
    const std::string host(k_title_redirects[0].hosts[2].host);
    std::string upper = host, kept;
    for (char& c : upper) if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
    const std::size_t m = added("garbage", "BLUS30443", &kept);
    std::printf("RRM empty=%zu theirs=%zu case=%zu serials=%zu nontable=%zu malformed=%d\n",
        added("", "BLUS30443"), added(host + "=10.0.0.1", "BLUS30443"), added(upper + "=10.0.0.1", "BLUS30443"),
        std::size(k_title_redirects), added("", "ABCD12345"), m == 6 && kept.rfind("garbage&&", 0) == 0 ? 1 : 0);
    return 0;
}
"""


def header_redirects(header_text):
    """(address, hosts, serials) of title_config.h's redirect table, in order."""
    body = header_text[header_text.index("inline constexpr std::string_view k_archstones"):]
    body = body[:body.index("namespace detail")]
    address = re.search(r'k_archstones = "([^"]+)";', body)
    return (address.group(1) if address else None, re.findall(r'^\t\t\{"([^"]+)", k_archstones\},$', body, re.M),
            re.findall(r'^\t\t\{"([A-Z]{4}[0-9]{5})", k_demons_souls_redirects\},$', body, re.M))


def kotlin_redirects(kotlin_text):
    """(address, hosts, serials) of GameDefaults.kt's REDIRECTS_BY_SERIAL, in order."""
    body = kotlin_text[kotlin_text.index("private val REDIRECTS_BY_SERIAL"):]
    body = body[:body.index("fun withTitleRedirects")]
    address = re.search(r'val archstones = "([^"]+)"', body)
    hosts = body[body.index("val demonsSouls = listOf("):body.index(").associateWith { archstones }")]
    serials = body[body.index(").associateWith { archstones }"):body.index(").associateWith { demonsSouls }")]
    return (address.group(1) if address else None, re.findall(r'"([^"]+)"', hosts), re.findall(r'"([A-Z]{4}[0-9]{5})"', serials))


def redirect_problems(header_text, kotlin_text):
    """`redirect` problems and the driver's RRM line ("" if it did not run)."""
    problems = []
    ours, theirs = header_redirects(header_text), kotlin_redirects(kotlin_text)
    if not theirs[1] or ours != theirs:
        problems.append("redirect: the table ({0} hosts, {1} serials) is not Android's REDIRECTS_BY_SERIAL ({2}, {3})".format(
            len(ours[1]), len(ours[2]), len(theirs[1]), len(theirs[2])))
    if tuple(ours[2]) != REDIRECT_SERIALS:
        problems.append("redirect: the table's serials are not the seven redirected titles, in Android's order")
    compiler = shutil.which("c++")
    if compiler is None:
        raise unittest.SkipTest("no c++ on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "title_config.h").write_text(header_text, encoding="utf-8")
        (Path(tmp) / "rrm.cpp").write_text(REDIRECT_DRIVER, encoding="utf-8")
        built = subprocess.run([compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-O1", str(Path(tmp) / "rrm.cpp"), "-o", str(Path(tmp) / "rrm")],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, universal_newlines=True)
        if built.returncode != 0:
            return problems + ["redirect: the header does not compile: " + built.stdout[:400]], ""
        ran = subprocess.run([str(Path(tmp) / "rrm")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30, universal_newlines=True)
    lines = [line for line in ran.stdout.splitlines() if line.startswith("RRM ")]
    if lines != [RRM_LINE]:
        problems.append("redirect: the merge printed {0}, not {1!r}".format(lines, RRM_LINE))
    return problems, "\n".join(lines)


def read(path):
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("empty input: {0}".format(path))
    return text


def product_text(render_source):
    """The LLVM arm of write_product_config()'s literal, as the device build writes it."""
    body = re.search(r"std::fputs\(\s*\n(.*?)\n\s*f\);", render_source, re.S)
    if body is None:
        raise AssertionError("no std::fputs( ... f); literal in ios_render.cpp")
    out, skipping = [], False
    for line in body.group(1).split("\n"):
        s = line.strip().rstrip(",")
        if s.startswith("#else"):
            skipping = True
        elif s.startswith("#endif"):
            skipping = False
        elif s.startswith('"') and not skipping:
            out.append(s[1:-1].replace('\\"', '"').replace("\\n", "\n"))
    return "".join(out)


def run_driver(header_text, product, args, tmp):
    compiler = shutil.which("c++")
    if compiler is None:
        raise unittest.SkipTest("no c++ on PATH; a skip is not a pass")
    tmp = Path(tmp)
    (tmp / "title_config.h").write_text(header_text, encoding="utf-8")
    (tmp / "drv.cpp").write_text(DRIVER, encoding="utf-8")
    (tmp / "product.yml.in").write_text(product, encoding="utf-8")
    (tmp / "db.json").write_text(DATABASE, encoding="utf-8")
    for name, text in SYNTHETIC.items():
        (tmp / name).write_text(text, encoding="utf-8")
    built = subprocess.run([compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-O1", str(tmp / "drv.cpp"), "-o", str(tmp / "drv")],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if built.returncode != 0:
        return None, built.stdout.decode(errors="replace")
    ran = subprocess.run([str(tmp / "drv"), str(tmp / "db.json"), str(tmp / "product.yml.in")] + args,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    results = {}
    for m in re.finditer(r"== (\S+) known=(\d) added=(\d+)\n(.*?)--END--\n", ran.stdout.decode(), re.S):
        results[m.group(1)] = (int(m.group(2)), int(m.group(3)), m.group(4))
    return results, ""


def section(text, name):
    m = re.search(r"^" + re.escape(name) + r":\n((?:  .*\n)*)", text, re.M)
    return m.group(1) if m else None


def local_table(header_text):
    body = header_text[header_text.index("local_overrides[] = {"):]
    body = body[:body.index("\n\t};")]
    return re.findall(r'^\t\t\{"([A-Z]{4}[0-9]{5})", "((?:[^"\\]|\\.)*)"\},$', body, re.M)


def ios_only_table(header_text):
    body = header_text[header_text.index("ios_only_overrides[] = {"):]
    body = body[:body.index("\n\t};")]
    return re.findall(r'^\t\t\{"([A-Z]{4}[0-9]{5})", "((?:[^"\\]|\\.)*)"\},$', body, re.M)


def ios_only_problems(header_text, kotlin_text, results):
    problems = []
    rows = ios_only_table(header_text)
    serials = [s for s, _ in rows]
    ported = {s for s, _ in local_table(header_text)} | {s for s, _ in kotlin_table(kotlin_text)}
    for serial in IOS_ONLY_REQUIRED:
        if serial not in serials:
            problems.append("iosonly: {0} is not in ios_only_overrides".format(serial))
    if len(set(serials)) != len(serials):
        problems.append("iosonly: a serial repeats in ios_only_overrides")
    for serial, config in rows:
        want = IOS_ONLY_ROWS.get(serial)
        if want is None:
            problems.append("iosonly: {0} has no expected row in the test".format(serial))
        elif config != want:
            problems.append("iosonly: {0} sets {1!r}, not exactly {2!r}".format(serial, config, want))
        if serial in ported:
            problems.append("iosonly: {0} is also in the ported table or Android's".format(serial))
        text = config.replace("\\n", "\n")
        known, added, got = results.get("^" + serial, (1, 0, ""))
        # One leaf per "\n  " line.
        if known or added != text.count("\n  ") or got != text:
            problems.append("iosonly: with no database {0} added={1} text={2!r}, not its own row".format(serial, added, got))
    return problems


def kotlin_table(kotlin_text):
    body = kotlin_text[kotlin_text.index("private val LOCAL_OVERRIDES"):]
    body = body[:body.index("\n    )\n")]
    return re.findall(r'^\s*"([A-Z]{4}[0-9]{5})" to "((?:[^"\\]|\\.)*)",$', body, re.M)


def local_problems(header_text, kotlin_text, results):
    problems = []
    ours, theirs = local_table(header_text), kotlin_table(kotlin_text)
    if not theirs or ours != theirs:
        problems.append("local: local_overrides ({0}) is not Android's LOCAL_OVERRIDES ({1})".format(len(ours), len(theirs)))
    for serial, config in theirs:
        text = config.replace("\\n", "\n")
        known, added, got = results.get("^" + serial, (1, 0, ""))
        leaves = len(re.findall(r"^\s+[^:\n]+: \S", text, re.M))
        if known or added != leaves or got != text:
            problems.append("local: with no database {0} added={1} text={2!r}, not its own entry".format(serial, added, got))
    known, added, got = results.get("%" + KILLZONE, (0, 0, ""))
    if not known or added != 3 or "  Write Color Buffers: false\n" not in (section(got, "Video") or "") or \
            "Write Color Buffers: true" in got or "  PPU Decoder: Interpreter (static)\n" not in (section(got, "Core") or "") or \
            "  Preferred SPU Threads: 0\n" not in (section(got, "Core") or ""):
        problems.append("local: {0} with the database did not keep ours over it and add its other key (added={1}): {2!r}".format(KILLZONE, added, got))
    known, added, got = results.get("%" + DES[0], (0, 0, ""))
    if not known or added != 1 or got != "Video:\n  Write Color Buffers: true\n":
        problems.append("local: a title not in the table no longer gets exactly its database entry (added={0})".format(added))
    return problems


def behaviour_problems(header_text, product, kotlin_text=None):
    locals_ = [s for s, _ in kotlin_table(kotlin_text)] if kotlin_text else []
    args = list(DES) + [FLOW] + list(UNKNOWN) + ["!" + s for s in DES]
    args += ["^" + s for s in locals_] + ["%" + KILLZONE, "%" + DES[0]] if kotlin_text else []
    args += ["^" + s for s, _ in ios_only_table(header_text)] if kotlin_text else []
    with tempfile.TemporaryDirectory() as tmp:
        args += ["@" + str(Path(tmp) / n) for n in SYNTHETIC]
        results, error = run_driver(header_text, product, args, tmp)
    if results is None:
        return ["des: the header does not compile: " + error[:400]]

    problems = local_problems(header_text, kotlin_text, results) + ios_only_problems(header_text, kotlin_text, results) if kotlin_text else []
    by_name = {k.rsplit("/", 1)[-1]: v for k, v in results.items() if k[0] not in "%^"}
    for name, (_, _, text) in sorted(by_name.items()):
        for header in re.findall(r"^(\S[^:\n]*):$", text, re.M):
            if len(re.findall(r"^" + re.escape(header) + r":$", text, re.M)) != 1:
                problems.append("single: `{0}:` appears more than once for {1}".format(header, name))

    fixes = results.get("FIX", (0, 0, None))[2]
    if fixes is None or fixes.splitlines() != FIX_PATHS:
        problems.append("fixpaths: fix_paths(db) is {0}, not {1}".format(fixes and fixes.splitlines(), FIX_PATHS))

    for serial in DES:
        known, added, text = results.get(serial, (0, 0, ""))
        video = section(text, "Video") or ""
        if not known or added != 1 or "  Write Color Buffers: true\n" not in video or text.replace("  Write Color Buffers: true\n", "", 1) != product:
            problems.append("des: {0} known={1} added={2} does not add exactly `Write Color Buffers: true` under Video".format(serial, known, added))

    for serial in UNKNOWN:
        known, added, text = results.get(serial, (1, 1, ""))
        if known or added or text != product:
            problems.append("unknown: {0} known={1} added={2} changed the product text".format(serial, known, added))

    for serial in DES:
        known, added, text = results.get("!" + serial, (1, 1, ""))
        if known or added or text != product:
            problems.append("missing: with no database {0} known={1} added={2} changed the product text".format(serial, known, added))

    known, added, text = results.get(FLOW, (0, 0, ""))
    if not known or added != 1 or "  Frame limit: Auto\n" not in (section(text, "Video") or "") or '  Renderer: "Vulkan"\n' not in text:
        problems.append("flow: flOw known={0} added={1} is not exactly `Frame limit: Auto`".format(known, added))

    _, added, text = by_name.get("product.yml", (0, 0, ""))
    video = section(text, "Video") or ""
    if "OpenGL" in text or video.count("Renderer:") != 1 or "  Write Color Buffers: true\n" not in video:
        problems.append("product: an entry's `Renderer` displaced the product's, or its other key was lost")

    _, added, text = by_name.get("filter.yml", (0, 0, ""))
    if "Nonexistent Key" in text or added != 1:
        problems.append("filter: a refused leaf reached the text (added={0})".format(added))

    for name in ("deny-off.yml", "deny-infinite.yml"):
        _, added, text = by_name.get(name, (0, 0, "Max SPURS Threads"))
        if "Max SPURS Threads" in text or "Frame limit" in text or "  Write Color Buffers: true\n" not in (section(text, "Video") or "") or added != 1:
            problems.append("deny: {0} let a denied leaf through or counted it (added={1})".format(name, added))
    _, added, text = by_name.get("deny-keep.yml", (0, 0, ""))
    if "  Frame limit: 30\n" not in (section(text, "Video") or "") or added != 1:
        problems.append("deny: `Frame limit: 30` was dropped, so the value list denies more than `Off` and `Infinite` (added={0})".format(added))
    return problems


class RealFiles(unittest.TestCase):
    def test_the_merge_behaves(self):
        self.assertEqual([], behaviour_problems(read(HEADER), product_text(read(RENDER)), read(KOTLIN_CONFIG_DB)))

    def test_product_pins_the_os_scheduler(self):
        self.assertIn('  Thread Scheduler Mode: "Operating System"\n', section(product_text(read(RENDER)), "Core") or "")


class Mutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header = read(HEADER)
        cls.abi = abi_text(ABI_DIR)
        cls.product = product_text(read(RENDER))
        cls.kotlin = read(KOTLIN_CONFIG_DB)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is gone; re-aim it")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def caught(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_product_no_longer_wins(self):
        h = self.edited(self.header, "else if (existing == nullptr && !denied_on_ios(f) && accepts(path, f))", "else if (!denied_on_ios(f) && accepts(path, f))")
        self.caught(behaviour_problems(h, self.product), "product")

    def test_sections_duplicated(self):
        h = self.edited(self.header, "item* existing = find(into, f.key);", "item* existing = nullptr;")
        self.caught(behaviour_problems(h, self.product), "single")

    def test_lookup_misses_the_entry(self):
        h = self.edited(self.header, 'if (key == "config")', 'if (key == "configs")')
        self.caught(behaviour_problems(h, self.product), "des")

    def test_merged_text_dropped(self):
        h = self.edited(self.header, "result.text = emit(base);", "result.text = std::string(product);")
        self.caught(behaviour_problems(h, self.product), "des")

    def test_predicate_ignored(self):
        h = self.edited(self.header, "else if (existing == nullptr && !denied_on_ios(f) && accepts(path, f))", "else if (existing == nullptr && !denied_on_ios(f))")
        self.caught(behaviour_problems(h, self.product), "filter")

    DENY_CALL = "else if (existing == nullptr && !denied_on_ios(f) && accepts(path, f))"

    def test_deny_spurs_lost(self):
        h = self.edited(self.header, 'leaf.key == "Max SPURS Threads"', 'leaf.key == "Max SPURS Thread"')
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_deny_off_lost(self):
        h = self.edited(self.header, 'leaf.value == "Off" || ', "")
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_deny_infinite_lost(self):
        h = self.edited(self.header, ' || leaf.value == "Infinite"', "")
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_deny_widened_to_every_frame_limit(self):
        h = self.edited(self.header, '(leaf.value == "Off" || leaf.value == "Infinite")', "true")
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_deny_filter_not_applied(self):
        h = self.edited(self.header, self.DENY_CALL, "else if (existing == nullptr && accepts(path, f))")
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_deny_counted_as_added(self):
        h = self.edited(self.header, self.DENY_CALL + "\n\t\t\t\t{\n",
                        self.DENY_CALL.replace("!denied_on_ios(f) && ", "") + "\n\t\t\t\t{\n\t\t\t\t\tif (denied_on_ios(f)) { added++; path.pop_back(); continue; }\n")
        self.caught(behaviour_problems(h, self.product), "deny")

    def test_unknown_serial_gets_keys(self):
        h = self.edited(self.header, "\t\tif (!valid_serial(serial))\n\t\t{\n\t\t\treturn {};\n\t\t}",
                        '\t\tif (!valid_serial(serial))\n\t\t{\n\t\t\treturn "Video:\\n  Write Color Buffers: true";\n\t\t}')
        self.caught(behaviour_problems(h, self.product), "unknown")

    def test_fixpaths_keeps_a_malformed_entry(self):
        h = self.edited(self.header, "if (!parse(text, items))", "if (!(parse(text, items) || true))")
        self.caught(behaviour_problems(h, self.product), "fixpaths")

    def test_empty_database_yields_an_entry(self):
        h = self.edited(self.header, "\t\tif (!valid_serial(serial))\n\t\t{\n\t\t\treturn {};\n\t\t}",
                        '\t\tif (db.empty())\n\t\t{\n\t\t\treturn "Video:\\n  Write Color Buffers: true";\n\t\t}\n\t\tif (!valid_serial(serial))\n\t\t{\n\t\t\treturn {};\n\t\t}')
        self.caught(behaviour_problems(h, self.product), "missing")

    def test_local_entry_drifts_from_android(self):
        h = self.edited(self.header, '{"BLUS30218", "Core:\\n  Accurate SPU Reservations: false\\n"},', "")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "local")

    def test_database_wins_over_ours(self):
        h = self.edited(self.header, "const merged ours = merge(\"\", local_override_for(serial), accepts);\n\t\tmerged all = merge(ours.text, entry, accepts);",
                        "const merged ours = merge(\"\", entry, accepts);\n\t\tmerged all = merge(ours.text, local_override_for(serial), accepts);")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "local")

    def test_local_table_not_consulted(self):
        h = self.edited(self.header, "\t\t\t\treturn o.config;", "\t\t\t\treturn {};")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "local")

    IOS_ONLY_LOOP = "for (const local_override& o : ios_only_overrides)\n\t\t{\n\t\t\tif (o.serial == serial)\n\t\t\t{\n\t\t\t\treturn o.config;"

    def test_ios_only_table_not_consulted(self):
        h = self.edited(self.header, self.IOS_ONLY_LOOP, self.IOS_ONLY_LOOP.replace("return o.config;", "return {};"))
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_ios_only_row_moved_into_the_ported_table(self):
        row = '\t\t{"BLUS30065", "Video:\\n  Shader Precision: High\\n"},\n'
        h = self.edited(self.header, row, "")
        h = self.edited(h, '\t\t{"BLUD80019", "Core:\\n  PPU Event Wake-Up Delay: 35\\n"},\n', '\t\t{"BLUD80019", "Core:\\n  PPU Event Wake-Up Delay: 35\\n"},\n' + row)
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_ios_only_row_sets_another_key(self):
        h = self.edited(self.header, '{"BLES00142", "Video:\\n  Shader Precision: High\\n"},', '{"BLES00142", "Video:\\n  Strict Rendering Mode: true\\n"},')
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    NBA_JAM_ROW = '{"NPUB30558", "Core:\\n  SPU loop detection: true\\n"},'

    def test_nba_jam_row_turns_loop_detection_off(self):
        h = self.edited(self.header, self.NBA_JAM_ROW, self.NBA_JAM_ROW.replace("true", "false"))
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_nba_jam_row_dropped(self):
        h = self.edited(self.header, "\t\t" + self.NBA_JAM_ROW + "\n", "")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    DANTE_ROW = '{"BLUS30405", "Core:\\n  Pause Game During SPU Compilation: true\\n"},'

    def test_dante_row_turns_the_pause_off(self):
        h = self.edited(self.header, self.DANTE_ROW, self.DANTE_ROW.replace("true", "false"))
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_dante_row_dropped(self):
        h = self.edited(self.header, "\t\t" + self.DANTE_ROW + "\n", "")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_dante_eu_park_dropped(self):
        h = self.edited(self.header, "  SPU Poll Park Sites: 3f47c:40801083\\n", "")
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_assassins_creed_row_keeps_accurate_zcull(self):
        h = self.edited(self.header, '{"BLUS30089", "Video:\\n  Accurate ZCULL stats: false', '{"BLUS30089", "Video:\\n  Accurate ZCULL stats: true')
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_simpsons_row_takes_the_database_precision(self):
        h = self.edited(self.header, '{"BLUS30065", "Video:\\n  Shader Precision: High', '{"BLUS30065", "Video:\\n  Shader Precision: Low')
        self.caught(behaviour_problems(h, self.product, self.kotlin), "iosonly")

    def test_redirect_merge_turns_case_sensitive(self):
        h = self.edited(self.header, "named.push_back(detail::ascii_lower(detail::trim(entry.substr(0, entry.find('=')))));",
                        "named.push_back(std::string(detail::trim(entry.substr(0, entry.find('=')))));")
        self.caught(redirect_problems(h, read(KOTLIN_GAME_DEFAULTS))[0], "redirect")

    def test_redirect_table_drifts_from_android(self):
        h = self.edited(self.header, '\t\t{"cmnap.scej-online.jp", k_archstones},\n', "")
        self.caught(redirect_problems(h, read(KOTLIN_GAME_DEFAULTS))[0], "redirect")


class TheMergeKeepsThePlayers(unittest.TestCase):
    """title_config.h's redirect table against Android's, and its merge compiled alone on the host. Not device evidence.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    @classmethod
    def setUpClass(cls):
        cls.problems, line = redirect_problems(read(HEADER), read(KOTLIN_GAME_DEFAULTS))
        sys.stderr.write("\n" + line + "\n")

    def test_the_players_entries_win_and_only_listed_titles_change(self):
        self.assertEqual([], self.problems)


if __name__ == "__main__":
    unittest.main(verbosity=2)
