#!/usr/bin/env python3
"""Regenerate SETTINGS_SEARCH_INDEX (app/src/main/java/com/armsx2/ui/settingshub/SettingsSearchIndex.kt).

Settings search finds a row by its label and jumps to the tab that shows it, so the index is every
labelled row widget in every settings tab (ToggleRow, SegmentedRow, SegmentedGridRow, IntSliderRow,
CollapsibleSection, and the Network tab's EditableTextRow, RpcnCard and ActionRow), each mapped to
the SettingsCategory whose tab draws it (see SettingsScreen: General is AppTab, Graphics is
RendererTab, Advanced is FixesTab, and so on). A label written as str("key") is stored as the key,
so search matches it in every language; a plain string is stored as it is. A label chosen by a
`when` (str(when (mode) { ... -> "key" })) is stored once per key it can take.

Rows in a function nothing calls are left out: FixesTab still has the PS2 build's RecompilerSection
(EE, IOP, VU0, VU1), which no tab shows, and a search result for it would open a tab without it.

Run it after adding or renaming a setting:

    python3 android/armsx3-ui/tools/gen_settings_search_index.py

A label that is neither a str("...") call nor a string literal (a variable, a when expression) is
not indexed; each one is printed so it can be checked by hand.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java", "com", "armsx2")
INDEX = os.path.join(ROOT, "ui", "settingshub", "SettingsSearchIndex.kt")

# The files each settings tab is drawn from, in the order the tabs appear.
SOURCES = [
    ("General", ["ui/settings/AppTab.kt"]),
    ("Performance", ["ui/settings/PerformanceTab.kt"]),
    ("Graphics", ["ui/settings/RendererTab.kt", "ui/settings/RendererBackendSection.kt", "ui/common/ShaderChainSection.kt"]),
    ("Audio", ["ui/settings/AudioTab.kt"]),
    ("Controls", ["ui/settings/PadTab.kt"]),
    ("Hotkeys", ["ui/settings/HotkeysTab.kt"]),
    ("Network", ["ui/settings/NetworkTab.kt", "ui/settings/RpcnAccountSection.kt", "ui/settings/RpcnFriendsSection.kt"]),
    ("OnScreen", ["ui/settings/OverlayTab.kt"]),
    ("Skins", ["ui/settings/SkinsTab.kt"]),
    ("Advanced", ["ui/settings/FixesTab.kt"]),
]

WIDGETS = ("ToggleRow", "SegmentedRow", "SegmentedGridRow", "IntSliderRow", "CollapsibleSection",
           "EditableTextRow", "RpcnCard", "ActionRow")
CALL = re.compile(r"(?<![\w.])(?:com\.armsx2\.ui\.settings\.)?(" + "|".join(WIDGETS) + r")\(")


def strip_comments(src):
    """The source with // and /* */ comments blanked out, string literals kept."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            if src.startswith('"""', i):
                j = src.index('"""', i + 3) + 3
            else:
                while j < n and src[j] != '"':
                    j += 2 if src[j] == "\\" else 1
                j += 1
            out.append(src[i:j])
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.index("*/", i + 2) + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def arguments(src, start):
    """The top-level arguments of the call whose '(' is at [start], as source text."""
    depth = 0
    args = []
    current = []
    i = start
    while i < len(src):
        c = src[i]
        if c == '"':
            j = i + 1
            while j < len(src) and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            current.append(src[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
            if depth > 1:
                current.append(c)
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append("".join(current).strip())
                return args
            current.append(c)
        elif c == "," and depth == 1:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(c)
        i += 1
    return args


def label_of(args):
    """The label argument: named label/title, else the first positional one."""
    for a in args:
        m = re.match(r"(label|title)\s*=\s*(.*)$", a, re.S)
        if m:
            return m.group(2).strip()
    positional = [a for a in args if not re.match(r"\w+\s*=", a)]
    return positional[0] if positional else None


def functions(src):
    """(name, body start, body end) of every fun in [src], bodies found by brace matching."""
    out = []
    for m in re.finditer(r"\bfun\s+(?:[\w.<>]+\.)?(\w+)\s*\(", src):
        depth = 0
        i = m.end() - 1
        # Skip the parameter list, then find the body's opening brace (or an = expression body).
        while i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        brace = src.find("{", i)
        newline_eq = re.match(r"[^{]*?=", src[i:brace]) if brace >= 0 else None
        if brace < 0 or newline_eq:
            continue
        depth = 0
        j = brace
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((m.group(1), brace, j))
    return out


def all_sources():
    text = []
    for base, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".kt"):
                text.append(strip_comments(open(os.path.join(base, n), encoding="utf-8").read()))
    return "\n".join(text)


def main():
    entries = []
    seen = set()
    skipped = []
    everything = all_sources()

    def called(name):
        return re.search(r"(?<!fun )(?<![\w])" + re.escape(name) + r"\s*\(", everything) is not None
    for category, files in SOURCES:
        for rel in files:
            src = strip_comments(open(os.path.join(ROOT, rel), encoding="utf-8").read())
            funs = functions(src)
            for m in CALL.finditer(src):
                if src[:m.start()].rstrip().endswith("fun"):
                    continue  # the widget's own definition
                # The innermost function around the row: left out when nothing calls it.
                around = [f for f in funs if f[1] < m.start() < f[2]]
                if around:
                    name = min(around, key=lambda f: f[2] - f[1])[0]
                    if not called(name):
                        continue
                label = label_of(arguments(src, m.end() - 1))
                if label is None:
                    continue
                key = re.fullmatch(r'str\("([^"]+)"\)', label)
                literal = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', label)
                chosen = re.fullmatch(r'str\(\s*when\b.*\)', label, re.S)
                if key:
                    found = [(key.group(1), True, category)]
                elif literal:
                    found = [(literal.group(1), False, category)]
                elif chosen:
                    found = [(k, True, category) for k in re.findall(r'->\s*"([^"]+)"', label)]
                else:
                    line = src[:m.start()].count("\n") + 1
                    skipped.append(f"{rel}:{line} {m.group(1)}({' '.join(label.split())[:60]})")
                    continue
                for entry in found:
                    if (entry[0], entry[2]) not in seen:
                        seen.add((entry[0], entry[2]))
                        entries.append(entry)

    kt = open(INDEX, encoding="utf-8").read()
    head = kt[:kt.index("internal val SETTINGS_SEARCH_INDEX")]
    body = "".join(
        f'    SettingsSearchEntry("{text}", {"true" if is_key else "false"}, SettingsCategory.{category}),\n'
        for text, is_key, category in entries
    )
    open(INDEX, "w", encoding="utf-8").write(
        head + "internal val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> = listOf(\n" + body + ")\n"
    )
    print(f"{len(entries)} entries written")
    for s in skipped:
        print("not indexed (label is not a string):", s, file=sys.stderr)


if __name__ == "__main__":
    main()
