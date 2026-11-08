#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The generated block in DeviceTables.swift equals what this file renders from upstream's pad dialog, product and
config sources, and the This Game card writes a device choice for the running game only.
Regenerate the block with `/usr/bin/python3 ios/tests/test_device_tables.py --print` and paste it between the markers."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import body
from source_helpers import without_comments

ROOT = Path(__file__).resolve().parents[2]
DIALOG = ROOT / "rpcs3" / "rpcs3qt" / "pad_settings_dialog.cpp"
PRODUCT_H = ROOT / "rpcs3" / "Input" / "product_info.h"
PRODUCT_CPP = ROOT / "rpcs3" / "Input" / "product_info.cpp"
TABLES = ROOT / "ios" / "app" / "Controllers" / "DeviceTables.swift"
PANE = ROOT / "ios" / "app" / "Controllers" / "ControllersPaneView.swift"

BEGIN, END = "// BEGIN GENERATED TABLE", "// END GENERATED TABLE"
GUNS = {11, 12, 13, 14}
T_LINES = ("T1 devices=15 first=0:Standard (Pad) gun=11:GunCon 3", "T2 multi=Drum,Guitar",
           "T3 legend_gh_guitar=Cross:Green Fret", "T4 usb=3 ghltar_strumdown=dpad_down", "T5 usio_coin=L2",
           "T6 topshotelite=R2:Trigger,L2:Reload topshotfearmaster=R2:Trigger,L2:Heartrate "
           "udraw=Touch the picture:Pen down mouse=0")
AIM_BUTTONS = 'static let aimButtons = ["mouse_button_1": "R2", "mouse_button_2": "L2"]'
TABLET_ROW = '(ps3: "Touch the picture", does: "Pen down")'
PAD_TYPES = ROOT / "rpcs3" / "Emu" / "Io" / "pad_types.cpp"
USB_KEYS = ("ghltar", "turntable", "buzz", "guncon3", "topshotelite", "topshotfearmaster", "usio")
# The PS3 button each of HandleDeviceProductChange's `x_title` strings labels (its `ui->gb_x` group box).
TITLE_BUTTON = {"cross": "cross", "circle": "circle", "square": "square", "triangle": "triangle", "dpad_up": "dpad_up",
                "dpad_down": "dpad_down", "right_stick_up": "rs_up", "right_stick_down": "rs_down",
                "right_stick_right": "rs_right", "l1": "L1", "l2": "L2", "l3": "L3", "r1": "R1", "r3": "R3"}
USB_PATHS = ('"Input/Output@@GHLtar emulated controller"', '"Input/Output@@Turntable emulated controller"',
             '"Input/Output@@Buzz emulated controller"')
USB_COUNTS = 'static let usbCounts = ["Null", "1 controller", "2 controllers"]'
FOOTNOTES = ('"Takes effect when the game next starts."',
             '"Your controller\'s buttons reach these through the player\'s mapping."')

CLASS_ITEM = re.compile(r'ui->chooseClass->addItem\(\s*tr\(\s*"([^"\\]+)"\s*\)\s*,\s*u32\{\s*(\w+)\s*\}\s*\)')
PRODUCT_ITEM = re.compile(r'case\s+input::product_type::(\w+)\s*:\s*\{\s*ui->chooseProduct->addItem\(\s*tr\(\s*"([^"\\]+)"')
TITLE = re.compile(r'(\w+)_title\s*=\s*tr\(\s*"([^"\\]*)"\s*\)\s*;')
USB_BUTTON = re.compile(r'cfg_pad_btn<\w+>\s+\w+\s*\{\s*this\s*,\s*"([^"\\]+)"\s*,\s*\w+::\w+\s*,\s*pad_button::(\w+)\s*\}')
BUTTON_NAME = re.compile(r'case\s+pad_button::(\w+)\s*:\s*return\s*"([^"\\]*)"\s*;')
PRODUCT_CLASS = re.compile(r"\.type\s*=\s*product_type::(\w+)\s*,\s*\.class_id\s*=\s*(\w+)\s*,")

WHY = ("The device names are upstream's. Regenerate the block with: "
       "/usr/bin/python3 ios/tests/test_device_tables.py --print")

DRIVER = r"""
import Foundation

let d = DeviceTables.devices
let gun = d.first { $0.isGun }!
print("T1 devices=\(d.count) first=\(d[0].ordinal):\(d[0].name) gun=\(gun.ordinal):\(gun.name)")
let multi = Dictionary(grouping: d, by: { $0.className }).filter { $0.value.count > 1 }.keys.sorted()
print("T2 multi=\(multi.joined(separator: ","))")
let gh = DeviceTables.legend(1)[0]
print("T3 legend_gh_guitar=\(DeviceTables.buttonName(gh.ps3)):\(gh.does)")
let strum = DeviceTables.usbLegends["ghltar"]!.first { $0.does == "Strum Down" }!
print("T4 usb=\(DeviceTables.usbDevices.count) ghltar_strumdown=\(strum.ps3)")
let coin = DeviceTables.usbLegends["usio"]!.first { $0.does == "Coin" }!
print("T5 usio_coin=\(coin.ps3)")
func rows(_ o: Int) -> String {
    DeviceTables.legend(o).prefix(2).map { "\(DeviceTables.buttonName($0.ps3)):\($0.does)" }.joined(separator: ",")
}
let mouseRows = (0...14).reduce(0) { n, o in n + DeviceTables.legend(o).filter { $0.ps3.hasPrefix("mouse_button") }.count }
print("T6 topshotelite=\(rows(12)) topshotfearmaster=\(rows(13)) udraw=\(rows(14)) mouse=\(mouseRows)")
"""


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def enum_order(product_h):
    found = re.search(r"enum\s+class\s+product_type\s*\{([^}]*)\}", product_h)
    if found is None:
        raise AssertionError("product_info.h has no `enum class product_type { ... }`")
    return re.findall(r"^\s*(\w+)\s*,?", found.group(1), re.M)


def upstream(dialog, product_h, product_cpp):
    """The devices in ordinal order as (ordinal, class name, device name, is gun)."""
    classes = dict((const, name) for name, const in CLASS_ITEM.findall(dialog))
    change = body(dialog, r"void\s+pad_settings_dialog::HandleDeviceClassChange\s*\([^)]*\)\s*const\s*") or ""
    products = dict(PRODUCT_ITEM.findall(change))
    class_of = dict(PRODUCT_CLASS.findall(product_cpp))
    order = enum_order(product_h)
    rows = []
    for ordinal, product in enumerate(order):
        const = class_of.get(product)
        if const not in classes or product not in products:
            raise AssertionError("upstream: {0} has no class item or no product item ({1})".format(product, const))
        siblings = [p for p in order if class_of.get(p) == const]
        name = classes[const] if len(siblings) == 1 else "{0} ({1})".format(classes[const], products[product])
        rows.append((ordinal, classes[const], name, const.startswith("CELL_PAD_FAKE_TYPE_")))
    return rows


def pad_legends(dialog, product_h):
    """{ordinal: [(PS3 button, title)]} for every product whose label switch case changes a title."""
    change = body(dialog, r"void\s+pad_settings_dialog::HandleDeviceProductChange\s*\([^)]*\)\s*const\s*") or ""
    defaults = dict(re.findall(r"QString\s+" + TITLE.pattern, change))
    if not defaults or set(defaults) != set(TITLE_BUTTON):
        raise AssertionError("upstream: HandleDeviceProductChange's titles {0} are not TITLE_BUTTON's".format(sorted(defaults)))
    switch = change[change.index("switch"):] if "switch" in change else ""
    parts = re.split(r"case\s+input::product_type::(\w+)\s*:", switch)
    order = enum_order(product_h)
    legends = {}
    for product, block in zip(parts[1::2], parts[2::2]):
        rows = [(TITLE_BUTTON[v], title) for v, title in TITLE.findall(block) if title != defaults.get(v)]
        if rows:
            legends[order.index(product)] = rows
    return legends


def usb_legends(configs):
    return [(key, USB_BUTTON.findall(configs[key])) for key in USB_KEYS]


def button_names(pad_types):
    fmt = body(pad_types, r"void\s+fmt_class_string<pad_button>::format\s*\([^)]*\)\s*") or ""
    return [(b, n) for b, n in BUTTON_NAME.findall(fmt) if n]


def render(rows, pads=None, usbs=None, names=None):
    """The block: the devices always, the legends and names when given."""
    out = ["    static let devices: [Device] = [\n"]
    for ordinal, kind, name, gun in rows:
        out.append('        Device(ordinal: {0}, className: "{1}", name: "{2}", isGun: {3}),\n'.format(
            ordinal, kind, name, "true" if gun else "false"))
    out.append("    ]\n")
    if pads is not None:
        out.append("    static let padLegends: [Int: [(ps3: String, does: String)]] = [\n")
        for ordinal in sorted(pads):
            out.append("        {0}: [\n".format(ordinal))
            out.extend('            ("{0}", "{1}"),\n'.format(b, t) for b, t in pads[ordinal])
            out.append("        ],\n")
        out.append("    ]\n")
    if usbs is not None:
        out.append("    static let usbLegends: [String: [(does: String, ps3: String)]] = [\n")
        for key, rows in usbs:
            out.append('        "{0}": [\n'.format(key))
            out.extend('            ("{0}", "{1}"),\n'.format(d, b) for d, b in rows)
            out.append("        ],\n")
        out.append("    ]\n")
    if names is not None:
        out.append("    static let buttonNames: [String: String] = [\n")
        out.extend('        "{0}": "{1}",\n'.format(b, n) for b, n in names)
        out.append("    ]\n")
    return "".join(out)


def generated(tables):
    """The text strictly between the BEGIN line and the END marker, or None when the markers are wrong."""
    if tables.count(BEGIN) != 1 or tables.count(END) != 1 or tables.index(BEGIN) > tables.index(END):
        return None
    start = tables.index("\n", tables.index(BEGIN)) + 1
    return tables[start:tables.rindex("\n", 0, tables.index(END)) + 1]


def table_problems(rendered, tables):
    block = generated(tables)
    if block is None:
        return ["marker: the BEGIN/END GENERATED TABLE markers are not there exactly once, BEGIN first"]
    want, have = rendered.splitlines(), block.splitlines()
    found = ["table-missing: upstream has {0!r} and the block does not".format(l.strip()) for l in want if l not in have]
    found += ["table-extra: the block has {0!r} and upstream does not".format(l.strip()) for l in have if l not in want]
    if not found and want != have:
        found.append("table-order: the block's lines are upstream's in another order")
    return found


def generated_guns(tables):
    block = generated(tables) or ""
    return {int(o) for o, g in re.findall(r"Device\(ordinal:\s*(\d+),[^)]*isGun:\s*(true|false)\)", block) if g == "true"}


def tables_problems(tables, pane):
    found = []
    code = without_comments(tables)
    pane_code = without_comments(pane)
    if generated(tables) is None:
        found.append("marker: the BEGIN/END GENERATED TABLE markers are not there exactly once, BEGIN first")
    if set(re.findall(r"^\s*import\s+(\w+)", code, re.M)) != {"Foundation"}:
        found.append("pure: DeviceTables.swift must import Foundation and nothing else (it is host-compiled)")
    choose = body(pane_code, r"func\s+chooseDevice\s*\(") or ""
    if not re.search(r"guard\s+let\s+serial\s*,\s*!serial\.isEmpty\b", choose) \
            or not re.search(r"Rpcs3Bridge\.setPlayer\([^)]*serial:\s*serial\b", choose) or "serial: nil" in choose:
        found.append("pergame: chooseDevice does not write the device with the running title's serial only")
    gun = re.search(r"\.isGun\b", choose)
    if generated_guns(tables) != GUNS or gun is None \
            or not re.search(r'"Input/Output@@Mouse"', pane_code) \
            or not re.search(r'mousePath\s*,\s*"Basic"\s*,\s*serial\b', choose[gun.start():]):
        found.append("mouse: a GunCon 3, Top Shot or uDraw choice does not also write the title's Mouse as Basic")
    if re.search(r"midi", code, re.I) or re.search(r"midi", pane_code, re.I):
        found.append("nomidi: a Rock Band 3 Pro MIDI device appears; those are deferred")
    legend = body(pane_code, r"struct\s+ControllersDeviceLegend\s*:\s*View\s*") or ""
    if "Text(" not in legend or pane_code.count("ControllersDeviceLegend(") < 2 or any(
            t in legend for t in ("Button(", "OverlayActionRow(", "OverlayToggleRow(", "onTapGesture", "startCapture(",
                                  "controllerAccessibility", "Rpcs3Bridge.")):
        found.append("legendro: the legend is not a read-only list drawn under the Device and USB rows")
    step = body(pane_code, r"func\s+stepUSB\s*\(") or ""
    if any(p not in code for p in USB_PATHS) or USB_COUNTS not in code \
            or not re.search(r"guard\s+let\s+serial\s*,\s*!serial\.isEmpty\b", step) \
            or "DeviceTables.usbCounts" not in step or not re.search(r"setGameSetting\(path\s*,[^)]*\bserial\)", step):
        found.append("usbrows: the three USB devices are not written per game as Null, 1 controller or 2 controllers")
    if any(f not in pane_code for f in FOOTNOTES):
        found.append("effect: the card does not say a choice takes effect at the next start and reaches the PS3 "
                     "buttons through the player's mapping")
    # The mouse first, the device second, the row last.
    mouse_at, device_at = choose.find('mousePath, "Basic", serial'), choose.find("Rpcs3Bridge.setPlayer(")
    shown_at = choose.find("devices[n] = ordinal")
    if choose.count("devices[n] = ordinal") != 1 or not 0 <= mouse_at < device_at < shown_at:
        found.append("mousefirst: a gun can show as chosen while the title's Mouse write was refused")
    # The legend names what a phone player presses.
    legend_fn = body(code, r"func\s+legend\s*\(") or ""
    if AIM_BUTTONS not in code or TABLET_ROW not in code \
            or not re.search(r"if\s+ordinal\s*==\s*14\s*\{\s*return\s+tabletLegend\s*\}", legend_fn) \
            or "aimButtons[" not in legend_fn:
        found.append("aimlegend: the Top Shot legend does not name R2 and L2, or the uDraw legend does not say a "
                     "touch on the picture holds the pen down")
    return found


def real_inputs():
    return read(DIALOG), read(PRODUCT_H), read(PRODUCT_CPP), read(TABLES), read(PANE)


def rendered_upstream():
    """render() of every upstream source, which is what --print writes."""
    dialog, product_h, product_cpp = read(DIALOG), read(PRODUCT_H), read(PRODUCT_CPP)
    configs = {key: read(ROOT / "rpcs3" / "Emu" / "Io" / "{0}_config.h".format(key)) for key in USB_KEYS}
    for key, rows in usb_legends(configs):
        if not rows:
            raise AssertionError("upstream: {0}_config.h has no cfg_pad_btn line".format(key))
    return render(upstream(dialog, product_h, product_cpp), pad_legends(dialog, product_h), usb_legends(configs),
                  button_names(read(PAD_TYPES)))


class TheTablesMatchUpstream(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        dialog, product_h, product_cpp, cls.tables, _ = real_inputs()
        cls.rows = upstream(dialog, product_h, product_cpp)
        cls.rendered = rendered_upstream()
        with tempfile.TemporaryDirectory() as tmp:
            cls.out = compile_and_run([TABLES], DRIVER, tmp)
        cls.lines = [l for l in cls.out.splitlines() if l.startswith("T")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_generated_block_is_upstreams(self):
        self.assertGreater(len(self.rows), 10, "product_info.h parsed to only {0} products".format(len(self.rows)))
        self.assertEqual([], table_problems(self.rendered, self.tables), WHY)

    def test_the_host_compiled_table_prints_the_t_lines(self):
        self.assertEqual(list(T_LINES), self.lines, self.out)


class TheSourcesAreClean(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        _, _, _, tables, pane = real_inputs()
        self.assertEqual([], tables_problems(tables, pane))

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "DeviceTables.swift"
            with self.assertRaises(AssertionError):
                read(missing)
            missing.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(missing)


class TheGuardFailsOnAMutation(unittest.TestCase):
    """One mutation per check."""

    @classmethod
    def setUpClass(cls):
        _, _, _, cls.tables, cls.pane = real_inputs()
        cls.rendered = rendered_upstream()

    def edited(self, text, old, new):
        self.assertTrue(old in text, "mutation anchor {0!r} not found".format(old))
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def has(self, prefix, found):
        self.assertTrue(any(p.startswith(prefix) for p in found), "{0} not in {1}".format(prefix, found))

    def test_mutation_table_renamed(self):
        self.has("table-", table_problems(self.rendered, self.edited(self.tables, '"GunCon 3"', '"Light Gun"')))

    def test_mutation_table_upstream_adds_a_product(self):
        extra = self.rendered.replace("    ]\n    static let padLegends", '        Device(ordinal: 15, className: "X", name: "X", isGun: false),\n    ]\n    static let padLegends', 1)
        self.has("table-missing", table_problems(extra, self.tables))

    def test_mutation_marker_gone(self):
        self.has("marker", tables_problems(self.edited(self.tables, BEGIN, "// generated"), self.pane))

    def test_mutation_pure_imports_swiftui(self):
        self.has("pure", tables_problems(self.edited(self.tables, "import Foundation", "import SwiftUI"), self.pane))

    def test_mutation_pergame_written_globally(self):
        self.has("pergame", tables_problems(self.tables, self.edited(
            self.pane, "Rpcs3Bridge.setPlayer(n, device: ordinal, serial: serial)",
            "Rpcs3Bridge.setPlayer(n, device: ordinal, serial: nil)")))

    def test_mutation_mouse_left_null(self):
        self.has("mouse", tables_problems(self.tables, self.edited(
            self.pane, 'Self.mousePath, "Basic", serial', 'Self.mousePath, "Null", serial')))

    def test_mutation_nomidi_an_rb3_midi_entry(self):
        self.has("nomidi", tables_problems(self.edited(
            self.tables, "    ]\n    static let padLegends",
            '        Device(ordinal: 15, className: "Guitar", name: "Guitar (RB3 MIDI)", isGun: false),\n    ]\n    static let padLegends'),
            self.pane))

    def test_mutation_table_legend_reworded(self):
        self.has("table-", table_problems(self.rendered, self.edited(self.tables, '"Green Fret"', '"Green"')))

    def test_mutation_legendro_the_legend_remaps(self):
        self.has("legendro", tables_problems(self.tables, self.edited(
            self.pane, "Text(verbatim: row.does)", "Button(row.does) {}")))

    def test_mutation_usbrows_a_third_buzz_count(self):
        self.has("usbrows", tables_problems(self.edited(self.tables, '"2 controllers"]', '"3 controllers"]'), self.pane))

    def test_mutation_mousefirst_the_row_shown_before_the_writes(self):
        self.has("mousefirst", tables_problems(self.tables, self.edited(
            self.pane, "            var status = 0\n", "            devices[n] = ordinal\n            var status = 0\n")))

    def test_mutation_aimlegend_the_trigger_left_on_the_mouse(self):
        self.has("aimlegend", tables_problems(self.edited(
            self.tables, '"mouse_button_1": "R2"', '"mouse_button_1": "mouse_button_1"'), self.pane))

    def test_mutation_aimlegend_the_tablet_unexplained(self):
        self.has("aimlegend", tables_problems(self.edited(
            self.tables, "if ordinal == 14 { return tabletLegend }", ""), self.pane))

    def test_mutation_effect_footnote_gone(self):
        self.has("effect", tables_problems(self.tables, self.edited(
            self.pane, '"Takes effect when the game next starts."', '"Saved."')))


if __name__ == "__main__":
    if sys.argv[1:] == ["--print"]:
        sys.stdout.write(rendered_upstream())
    else:
        unittest.main()
