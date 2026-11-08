#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The Local Network prompt is raised once, from the RPCN pane's first sign-in, after the sentence that explains it,
with a usage string and no Bonjour or multicast; a denial is read as TN3179 describes and shown as one warning row.
It does not show iOS raising the prompt or detecting a denial."""

import json
import plistlib
import re
import unittest
from pathlib import Path

from source_helpers import brace_body, live_code, read_rpcn_screens as read

ROOT = Path(__file__).resolve().parents[2]
PLIST = ROOT / "ios" / "app" / "Resources" / "Info.plist"
APP = ROOT / "ios" / "app"
LOCAL_NETWORK = APP / "Online" / "LocalNetwork.swift"
RPCN_MODEL = APP / "Online" / "RpcnModel.swift"
RPCN_PANE = APP / "Online" / "RpcnPane.swift"
CATALOG = ROOT / "ios" / "app" / "Resources" / "Localizable.xcstrings"
PRODUCT_SWIFT_ROOTS = (ROOT / "ios" / "app",)
ENTITLEMENTS = ROOT / "ios" / "app"

USAGE_KEY = "NSLocalNetworkUsageDescription"
BONJOUR_KEY = "NSBonjourServices"
MULTICAST = "com.apple.developer.networking.multicast"
TRIGGER = "LocalNetwork.trigger()"
# The Local Network sentence and the line before it, from the UI copy.
SENTENCE = ("iOS asks next whether ARMSX3 can use your local network. Games need it to reach a player on the same "
            "Wi-Fi.")
SIGNED_IN_ON = "Signed in. Online play is on."
FIRST_NOTICE = re.compile(r'notice\s*=[^\n]*String\(localized:\s*"%s"\)[^\n]*"\\n"[^\n]*LocalNetwork\.sentence'
                          % re.escape(SIGNED_IN_ON))


def product_swift():
    files = {}
    for root in PRODUCT_SWIFT_ROOTS:
        for path in sorted(root.rglob("*.swift")):
            files[str(path.relative_to(ROOT))] = read(path)
    return files


def entitlement_texts():
    return [(path.name, read(path)) for path in sorted(ENTITLEMENTS.glob("*.entitlements"))]


def catalog_value(catalog, key):
    try:
        entry = json.loads(catalog)["strings"].get(key)
    except (ValueError, KeyError, TypeError, AttributeError):
        return None
    if not isinstance(entry, dict):
        return None
    return entry.get("localizations", {}).get("en", {}).get("stringUnit", {}).get("value")


def plist_problems(plist):
    try:
        info = plistlib.loads(plist.encode("utf-8"))
    except Exception as error:  # a broken plist is a finding, not a crash
        return ["usage: Info.plist does not parse ({0})".format(type(error).__name__)]
    problems = []
    value = info.get(USAGE_KEY)
    if not isinstance(value, str) or not value.strip():
        problems.append("usage: Info.plist has no non-empty {0}".format(USAGE_KEY))
    if BONJOUR_KEY in info:
        problems.append("nobonjour: Info.plist carries {0}, and nothing uses Bonjour".format(BONJOUR_KEY))
    return problems


def multicast_problems(entitlements):
    named = [name for name, text in entitlements if MULTICAST in text]
    if named:
        return ["nomulticast: {0} request the multicast entitlement".format(named)]
    return []


def shared_sign_in(model):
    # The sign-in every path shares: the overload whose parameters name the screen.
    for match in re.finditer(r"func signIn\(", model):
        head = model[match.start():model.find("{", match.start())]
        if "screen:" in head:
            return brace_body(model, match.start())
    return ""


def trigger_problems(swift_files, catalog):
    problems = []
    rel_model = str(RPCN_MODEL.relative_to(ROOT))
    sites = [(name, len(re.findall(re.escape(TRIGGER), live_code(text)))) for name, text in swift_files.items()]
    sites = [(name, count) for name, count in sites if count]
    model = live_code(swift_files.get(rel_model, ""))
    body = shared_sign_in(model)
    at = body.find(TRIGGER)
    in_first = any(TRIGGER in brace_body(body, m.start()) for m in re.finditer(r"\bif first\b", body))
    if sites != [(rel_model, 1)] or at < 0 or not in_first:
        problems.append("onlypane: LocalNetwork.trigger() is not called exactly once, from the shared sign-in's "
                        "`if first` block in RpcnModel.swift ({0})".format(sites))

    notice = FIRST_NOTICE.search(body)
    if not notice or at < 0 or notice.start() > at:
        problems.append("after: the trigger does not come after the first-sign-in notice is set")

    local = live_code(swift_files.get(str(LOCAL_NETWORK.relative_to(ROOT)), ""))
    if 'String(localized: "%s")' % SENTENCE not in local or not notice \
            or catalog_value(catalog, SENTENCE) != SENTENCE or catalog_value(catalog, SIGNED_IN_ON) != SIGNED_IN_ON:
        problems.append("sentence: the Local Network sentence is not the UI-SPEC text, not on its own line after "
                        "the first-sign-in line, or not in the String Catalog")
    return problems


# The denial and its row.
FIX = ("Games can't reach a player on the same Wi-Fi. Turn on Local Network for ARMSX3 in the Settings app, under "
       "Privacy & Security.")
DENIAL_TOKENS = ("NWConnection(", "case .waiting", "connection.currentPath?.unsatisfiedReason", ".localNetworkDenied",
                 "asyncAfter(deadline: .now() + 2)", 'DispatchQueue(label: "armsx3.localnetwork"',
                 "connection.start(queue: queue)")
ROW = ('StatusRow(label: String(localized: "Local Network"), value: LocalNetwork.offValue, isProblem: true, '
       'fix: LocalNetwork.fix)')


def denial_problems(swift_files, catalog):
    problems = []
    local = live_code(swift_files.get(str(LOCAL_NETWORK.relative_to(ROOT)), ""))
    at = local.find("static func isDenied(")
    missing = [token for token in DENIAL_TOKENS if token not in local]
    if at < 0 or "async -> Bool" not in local[at:at + 80] or missing or "DispatchQueue.main" in local:
        problems.append("denial: isDenied() is not TN3179's NWConnection reading with a 2-second timeout on a queue "
                        "of its own ({0})".format(missing))

    pane = live_code(swift_files.get(str(RPCN_PANE.relative_to(ROOT)), ""))
    if 'String(localized: "%s")' % FIX not in local or 'String(localized: "Off")' not in local or ROW not in pane \
            or catalog_value(catalog, FIX) != FIX or catalog_value(catalog, "Local Network") != "Local Network" \
            or catalog_value(catalog, "Off") != "Off":
        problems.append("fix: the Local Network row is not `Off` drawn warm (isProblem) with the UI-SPEC fix sentence, "
                        "or its strings are not in the String Catalog")

    model = live_code(swift_files.get(str(RPCN_MODEL.relative_to(ROOT)), ""))
    shown = re.search(r"\bif model\.localNetworkDenied \{", pane)
    assigned = re.findall(r"(?<!var )\blocalNetworkDenied\s*=(?!=)\s*([^\n]*)", model)
    if not shown or ROW not in brace_body(pane, shown.start()) or pane.count("StatusRow(") != 1 \
            or not assigned or any(rhs.strip() != "await LocalNetwork.isDenied()" for rhs in assigned):
        problems.append("onlyknown: the Local Network row is not drawn only when isDenied() returned true")
    return problems

def local_network_problems(plist, swift_files, catalog, entitlements):
    return (plist_problems(plist) + multicast_problems(entitlements) + trigger_problems(swift_files, catalog)
            + denial_problems(swift_files, catalog))


WHY = ("iOS asks for Local Network once, on the RPCN pane right after the first "
       "sign-in under the sentence that says why, never during a game, and the app asks for nothing it does not use.")


def real_inputs():
    return read(PLIST), product_swift(), read(CATALOG), entitlement_texts()


class TheLocalNetworkQuestionKeepsItsPlace(unittest.TestCase):
    # No docstrings, so `unittest -v` prints `... ok` on the method's own line.
    def test_the_real_files_hold(self):
        self.assertEqual([], local_network_problems(*real_inputs()), WHY)

    def test_an_empty_input_raises(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".swift") as empty:
            with self.assertRaises(AssertionError):
                read(Path(empty.name))


class EachCheckCanFail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plist, cls.swift, cls.catalog, cls.entitlements = real_inputs()

    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def fails_with(self, prefix, plist=None, swift=None, catalog=None, entitlements=None):
        found = local_network_problems(plist if plist is not None else self.plist,
                                       swift if swift is not None else self.swift,
                                       catalog if catalog is not None else self.catalog,
                                       entitlements if entitlements is not None else self.entitlements)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def swift_with(self, path, old, new):
        files = dict(self.swift)
        name = str(path.relative_to(ROOT))
        files[name] = self.edited(files[name], old, new)
        return files

    def test_usage_the_key_renamed_fails(self):
        self.fails_with("usage", plist=self.edited(self.plist, "<key>%s</key>" % USAGE_KEY, "<key>NSLocalNetwork</key>"))

    def test_nobonjour_a_service_added_fails(self):
        plist = self.edited(self.plist, "<key>%s</key>" % USAGE_KEY,
                            "<key>%s</key><array><string>_rpcn._udp</string></array><key>%s</key>"
                            % (BONJOUR_KEY, USAGE_KEY))
        self.fails_with("nobonjour", plist=plist)

    def test_nomulticast_the_entitlement_requested_fails(self):
        name, text = self.entitlements[0]
        text = self.edited(text, "<dict>", "<dict>\n\t<key>%s</key>\n\t<true/>" % MULTICAST)
        self.fails_with("nomulticast", entitlements=[(name, text)] + self.entitlements[1:])

    def test_onlypane_a_second_call_site_fails(self):
        self.fails_with("onlypane", swift=self.swift_with(RPCN_PANE, "    private func signIn() {",
                                                          "    private func signIn() {\n        %s" % TRIGGER))

    def test_after_the_call_moved_before_the_notice_fails(self):
        model = self.swift[str(RPCN_MODEL.relative_to(ROOT))]
        at = model.find("            " + TRIGGER + "\n")
        self.assertGreaterEqual(at, 0)
        moved = model[:at] + model[at + len("            " + TRIGGER + "\n"):]
        moved = self.edited(moved, "        if first && !onlinePlay {", "        if first { %s }\n        if first && !onlinePlay {"
                            % TRIGGER)
        files = dict(self.swift)
        files[str(RPCN_MODEL.relative_to(ROOT))] = moved
        self.fails_with("after", swift=files)

    def test_sentence_reworded_fails(self):
        self.fails_with("sentence", swift=self.swift_with(LOCAL_NETWORK, 'String(localized: "%s")' % SENTENCE,
                                                          'String(localized: "iOS asks about your local network.")'))

    # One mutation per check.
    def test_denial_no_timeout_fails(self):
        self.fails_with("denial", swift=self.swift_with(LOCAL_NETWORK, "asyncAfter(deadline: .now() + 2)",
                                                        "asyncAfter(deadline: .now() + 60)"))

    def test_fix_value_not_warm_fails(self):
        self.fails_with("fix", swift=self.swift_with(RPCN_PANE, "value: LocalNetwork.offValue, isProblem: true,",
                                                     "value: LocalNetwork.offValue, isProblem: false,"))

    def test_onlyknown_drawn_without_a_denial_fails(self):
        self.fails_with("onlyknown", swift=self.swift_with(RPCN_PANE, "if model.localNetworkDenied {",
                                                           "if !model.accountName.isEmpty {"))


if __name__ == "__main__":
    unittest.main()
