#!/usr/bin/env python3
"""Write the toy portals' figure lists, taken from RPCS3's own portal managers, as app assets.

RPCS3 names every figure its managers can create in three tables in rpcs3qt, which the Android build
does not compile: list_skylanders (skylander_dialog.cpp), list_figures (infinity_dialog.cpp) and
list_minifigs (dimensions_dialog.cpp). This copies them to assets/portal/*.tsv for the Create figure
list. Run it again after an upstream merge that touches those tables.

  skylanders.tsv  id <tab> variant <tab> name
  infinity.tsv    figure number <tab> series <tab> name
  dimensions.tsv  id <tab> name
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
QT = os.path.join(ROOT, "rpcs3", "rpcs3qt")
OUT = os.path.join(ROOT, "android", "armsx3-ui", "app", "src", "main", "assets", "portal")
NUM = r"(0x[0-9A-Fa-f]+|\d+)"
STR = r'"((?:[^"\\]|\\.)*)"'


def table(path, name):
    text = open(path, encoding="utf-8").read()
    start = text.index(name + " = {")
    end = text.index("};", start)  # the last entry and the brace can share a line
    return text[start:end]


def unescape(s):
    return s.replace('\\"', '"').replace("\\\\", "\\")


def write(name, rows):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            assert all("\t" not in str(v) and "\n" not in str(v) for v in row), row
            f.write("\t".join(str(v) for v in row) + "\n")
    print(f"{name}: {len(rows)} figures")


sky = [(int(a, 0), int(b, 0), unescape(n)) for a, b, n in
       re.findall(r"\{\{\s*" + NUM + r"\s*,\s*" + NUM + r"\s*\}\s*,\s*" + STR + r"\s*\}",
                  table(os.path.join(QT, "skylander_dialog.cpp"), "list_skylanders"))]
inf = [(int(a, 0), int(b, 0), unescape(n)) for a, b, n in
       re.findall(r"\{\s*" + NUM + r"\s*,\s*\{\s*" + NUM + r"\s*,\s*" + STR + r"\s*\}\s*\}",
                  table(os.path.join(QT, "infinity_dialog.cpp"), "list_figures"))]
dim = [(int(a, 0), unescape(n)) for a, n in
       re.findall(r"\{\s*" + NUM + r"\s*,\s*" + STR + r"\s*\}",
                  table(os.path.join(QT, "dimensions_dialog.cpp"), "list_minifigs"))]
if not (sky and inf and dim):
    sys.exit("a table came back empty: the source format changed")
write("skylanders.tsv", sky)
write("infinity.tsv", inf)
write("dimensions.tsv", dim)
