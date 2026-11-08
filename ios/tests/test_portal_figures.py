# SPDX-License-Identifier: GPL-2.0-only
"""The generated name tables in PortalNames.swift against the four rpcs3qt dialogs, both ways, and the names compiled with swiftc on the host.
Run with --print to write the generated block.
"""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import brace_span, read, without_comments
from swiftc_host import compile_and_run


IOS_DIR = Path(__file__).resolve().parents[1]

ROOT = IOS_DIR.parent

SKY_QT = ROOT / "rpcs3" / "rpcs3qt" / "skylander_dialog.cpp"

INF_QT = ROOT / "rpcs3" / "rpcs3qt" / "infinity_dialog.cpp"

KR_QT = ROOT / "rpcs3" / "rpcs3qt" / "kamen_rider_dialog.cpp"

DIM_QT = ROOT / "rpcs3" / "rpcs3qt" / "dimensions_dialog.cpp"

NAMES = IOS_DIR / "app" / "Controllers" / "PortalNames.swift"

SHEET = IOS_DIR / "app" / "Controllers" / "PortalSheet.swift"

STORE = IOS_DIR / "app" / "Controllers" / "PortalStore.swift"

# The name tables: (Swift name, upstream input index, C++ name, entry shape); index 0 sky, 1 inf, 2 dim, 3 kr.
BEGIN, END = "// BEGIN GENERATED TABLE", "// END GENERATED TABLE"

LITERAL = r'"((?:[^"\\]|\\.)*)"'

NUM = r"(0[xX][0-9A-Fa-f']+|[0-9][0-9']*)"

PAIR_KEYED = re.compile(r"\{\s*\{\s*" + NUM + r"\s*,\s*" + NUM + r"\s*\}\s*,\s*" + LITERAL + r"\s*\}")

SERIES_VALUED = re.compile(r"\{\s*" + NUM + r"\s*,\s*\{\s*" + NUM + r"\s*,\s*" + LITERAL + r"\s*\}\s*\}")

PLAIN = re.compile(r"\{\s*" + NUM + r"\s*,\s*" + LITERAL + r"\s*\}")

TABLES = (
    ("skylanders", "(id: Int, variant: Int, name: String)", 0, "list_skylanders", PAIR_KEYED),
    ("infinity", "(number: Int, series: Int, name: String)", 1, "list_figures", SERIES_VALUED),
    ("dimensionsMinifigs", "(id: Int, name: String)", 2, "list_minifigs", PLAIN),
    ("dimensionsTokens", "(id: Int, name: String)", 2, "list_tokens", PLAIN),
    ("kamenRiders", "(id: Int, name: String)", 3, "list_kamen_riders", PLAIN),
)

SWIFT_TABLE = re.compile(r"static let (\w+): \[\([^)]*\)\] = \[(.*?)\n    \]", re.DOTALL)

SWIFT_ROW = re.compile(r"\(\s*((?:-?[0-9]+\s*,\s*)+)" + LITERAL + r"\s*\)")

IMPORT = re.compile(r"(?m)^\s*import\s+(\w+)")

SHEET_CREATE = ("PortalStore.freeFile(", "Rpcs3Bridge.portalCreate(", "Rpcs3Bridge.portalLoad(")

SHEET_NAMED = re.compile(r'PortalNames\.name\([^)]*\)\s*\?\?\s*String\(localized:\s*"Unknown figure"\)')

NAMES_DRIVER = """import Foundation
let n = [PortalNames.skylanders.count, PortalNames.infinity.count, PortalNames.dimensions.count, PortalNames.kamenRiders.count]
print("N1 sky\\(n[0] > 0 ? ">0" : "=0") inf\\(n[1] > 0 ? ">0" : "=0") dim\\(n[2] > 0 ? ">0" : "=0") kr\\(n[3] > 0 ? ">0" : "=0")")
let s = PortalNames.skylanders[0], i = PortalNames.infinity[0], d = PortalNames.dimensions[0], k = PortalNames.kamenRiders[0]
let named = [PortalNames.name(portal: 0, id: s.id, variant: s.variant) == s.name,
             PortalNames.name(portal: 1, id: i.number, variant: 0) == i.name,
             PortalNames.name(portal: 2, id: d.id, variant: 0) == d.name,
             PortalNames.name(portal: 3, id: k.id, variant: 0) == k.name].filter { $0 }.count
let unknown = PortalNames.name(portal: 0, id: 70000, variant: 70000) == nil ? 1 : 0
print("N2 named=\\(named) unknown=\\(unknown)")
"""


def number(text):
    digits = text.replace("'", "")
    return int(digits, 16) if digits[:2].lower() == "0x" else int(digits)


def upstream_tables(texts):
    """{swift name: [row tuple]} parsed from the four rpcs3qt sources, or (None, problems) on a reshape."""
    tables, problems = {}, []
    for swift_name, _, index, cxx_name, shape in TABLES:
        text = without_comments(texts[index])
        found = list(re.finditer(r"\b" + cxx_name + r"\s*=\s*\{", text))
        if len(found) != 1:
            problems.append("parse: `{0} = {{` is not in its rpcs3qt source exactly once".format(cxx_name))
            continue
        start, end = brace_span(text, found[0].end() - 1)
        block = text[start + 1:end - 1]
        rows = [tuple(number(n) for n in m[:-1]) + (m[-1],) for m in shape.findall(block)]
        if not rows or len(rows) != len(re.findall(LITERAL, block)):
            problems.append("parse: {0} no longer has one entry of the expected shape per name".format(cxx_name))
            continue
        tables[swift_name] = rows
    return (None if problems else tables), problems


def render(tables):
    """The generated block as --print writes it, markers excluded."""
    out = []
    for swift_name, row_type, _, cxx_name, _ in TABLES:
        out.append("    static let {0}: [{1}] = [  // {2}, in upstream's order.\n".format(swift_name, row_type, cxx_name))
        for row in tables[swift_name]:
            out.append("        ({0}, \"{1}\"),\n".format(", ".join(str(v) for v in row[:-1]), row[-1]))
        out.append("    ]\n")
    return "".join(out)


def swift_tables(block):
    return {name: [tuple(int(v) for v in re.findall(r"-?[0-9]+", nums)) + (text,) for nums, text in SWIFT_ROW.findall(rows)]
            for name, rows in SWIFT_TABLE.findall(block)}


def names_problems(sky_qt, inf_qt, dim_qt, kr_qt, names, sheet, store):
    problems = []
    tables, problems = upstream_tables((sky_qt, inf_qt, dim_qt, kr_qt))

    if names.count(BEGIN) != 1 or names.count(END) != 1 or names.index(BEGIN) > names.index(END):
        problems.append("marker: PortalNames.swift does not carry `{0}` and `{1}` exactly once, in order".format(BEGIN, END))
        block = None
    else:
        block = names[names.index(BEGIN) + len(BEGIN) + 1:names.index(END)].rstrip(" ")

    if tables is not None and block is not None:
        have = swift_tables(block)
        for swift_name, _, _, cxx_name, _ in TABLES:
            want, got = tables[swift_name], have.get(swift_name, [])
            gone = [row for row in want if row not in got]
            extra = [row for row in got if row not in want]
            if gone:
                problems.append("upstream: {0} entries of {1} are not in PortalNames.{2}".format(len(gone), cxx_name, swift_name))
            if extra:
                problems.append("upstream: {0} rows of PortalNames.{1} are not in {2}".format(len(extra), swift_name, cxx_name))
        if block != render(tables):
            problems.append("print: the generated block is not byte for byte what `test_portal_figures.py --print` writes")

    if IMPORT.findall(names) != ["Foundation"]:
        problems.append("pure: PortalNames.swift imports something other than Foundation alone")

    sheet_code, store_code = without_comments(sheet), without_comments(store)
    create = re.search(r"\bfunc\s+create\s*\(", sheet_code)
    body = sheet_code[brace_span(sheet_code, create.end())[0]:brace_span(sheet_code, create.end())[1]] if create else ""
    at = [body.find(call) for call in SHEET_CREATE]
    free = re.search(r"\bstatic\s+func\s+freeFile\s*\(", store_code)
    free_body = store_code[slice(*brace_span(store_code, free.end()))] if free else ""
    if min(at) < 0 or at != sorted(at) or "folder(" not in free_body or "fileExists(" not in free_body:
        problems.append("create: the sheet's create does not name a free file in the portal's folder, create into it, "
                        "then load it, in that order")

    if not SHEET_NAMED.search(sheet_code):
        problems.append("unknown: the sheet does not name a loaded slot through PortalNames.name( with an "
                        "\"Unknown figure\" fallback")
    return problems


def names_inputs():
    return [read(p) for p in (SKY_QT, INF_QT, DIM_QT, KR_QT, NAMES, SHEET, STORE)]


class TheNamesMatchUpstream(unittest.TestCase):
    """The generated name tables against rpcs3qt, and PortalNames.swift on the Mac host.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    def test_the_generated_block_is_upstreams_both_ways(self):
        self.assertEqual([], names_problems(*names_inputs()))

    def test_the_tables_compile_and_name_figures(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([NAMES], NAMES_DRIVER, tmp)
        lines = [line for line in out.splitlines() if line.startswith(("N1 ", "N2 "))]
        sys.stderr.write("\n" + "\n".join(lines) + "\n")
        self.assertEqual(["N1 sky>0 inf>0 dim>0 kr>0", "N2 named=4 unknown=1"], lines)


if __name__ == "__main__":
    if sys.argv[1:] == ["--print"]:
        tables, problems = upstream_tables([read(p) for p in (SKY_QT, INF_QT, DIM_QT, KR_QT)])
        if tables is None:
            sys.exit("\n".join(problems))
        sys.stdout.write(render(tables))
    else:
        unittest.main()
