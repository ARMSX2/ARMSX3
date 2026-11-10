#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""JitNextStep.swift, run on the Mac host with DeviceChecks.swift, picks the JIT pane's one advice line and at most one
button in the screen table's order, and its helper, mount, refusal, disk and code lines use the shared copy.
It does not show a view drawing the step or a device reporting these words."""

import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import COPY

IOS = Path(__file__).resolve().parents[1]
SOURCE = IOS / "app" / "JIT" / "JitNextStep.swift"
PRESENCE = IOS / "app" / "JIT" / "DeviceChecks.swift"

C = COPY
EVERYTHING_WRONG = ('entitlementMissing: true, method: nil, pairingImported: false, diskImage: .notMounted, '
                    'helperWord: "connect-failed", stikDebugInstalled: false, stikDebugReturnedOff: true, txm: .missing, '
                    'liveContainer: true, mountResult: "mount", actedThisLaunch: true, importRefusal: "x"')
BUILTIN = "method: .builtin, pairingImported: true"
STIK = "method: .stikdebug"
NOFILE = "method: .builtin, pairingImported: false"

# name: (JitSituation arguments, row, primary, advice)
CASES = {
    "on": ("jitOn: true, " + EVERYTHING_WRONG, 1, "none", "none"),
    "entitlement-and-pairing": ("entitlementMissing: true, method: .builtin, pairingImported: false", 2, "none", C["row2"]),
    "lc-builtin": ("method: nil, liveContainer: true", 3, "chooseMethod", C["row3"]),
    "pairing": ("method: .builtin, pairingImported: false, diskImage: .notMounted", 4, "importPairingFile", C["row4"]),
    "refusal": (BUILTIN + ", importRefusal: JitNextStep.importRefusalSentence(2)", 4, "importPairingFile", C["refusal-legacy"]),
    "refusal-no-file": ("method: .builtin, pairingImported: false, importRefusal: JitNextStep.importRefusalSentence(3)",
                        4, "importPairingFile", C["refusal-invalid"]),
    "pairing-sidestore": (NOFILE + ", sideStoreAvailable: true", 4, "getFromSideStore", C["row4-sidestore"]),
    "pairing-lc-iphone": (NOFILE + ", liveContainer: true", 4, "importPairingFile", C["row4-lc-iphone"]),
    "pairing-lc-ipad": (NOFILE + ", liveContainer: true, isPad: true", 4, "importPairingFile", C["row4-lc-ipad"]),
    "pairing-lc-beats-sidestore": (NOFILE + ", liveContainer: true, sideStoreAvailable: true", 4, "importPairingFile", C["row4-lc-iphone"]),
    "sidestore-nothing": (NOFILE + ", sideStoreAvailable: true, sideStoreSentNothing: true", 4, "getFromSideStore", C["sidestore-nothing"]),
    "sidestore-lockdown": (NOFILE + ", sideStoreAvailable: true, sideStoreLockdown: true", 4, "getFromSideStore", C["sidestore-lockdown"]),
    "sidestore-lockdown-beats-nothing": (NOFILE + ", sideStoreAvailable: true, sideStoreSentNothing: true, sideStoreLockdown: true",
                                         4, "getFromSideStore", C["sidestore-lockdown"]),
    "refusal-beats-sidestore": (NOFILE + ", importRefusal: JitNextStep.importRefusalSentence(3), sideStoreAvailable: true, sideStoreLockdown: true",
                                4, "importPairingFile", C["refusal-invalid"]),
    "ddi": (BUILTIN + ", diskImage: .notMounted", 5, "mountDiskImage", C["row5"]),
    "ddi-download": (BUILTIN + ', diskImage: .notMounted, mountResult: "download", actedThisLaunch: true',
                     5, "mountDiskImage", C["mount-download"]),
    "ddi-busy": (BUILTIN + ', diskImage: .busy("downloading"), actedThisLaunch: true', 5, "mountDiskImage", C["row5"]),
    "helper": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed"', 6, "none", C["helper-connect-failed"]),
    "helper-after-mount": (BUILTIN + ', diskImage: .mounted, helperWord: "connect-failed", mountResult: "ready", actedThisLaunch: true',
                           7, "none", C["row7"]),
    "helper-after-import": (BUILTIN + ', diskImage: .unknown, helperWord: "tunnel-failed", actedThisLaunch: true', 7, "none", C["row7"]),
    "word-empty": (BUILTIN + ', diskImage: .unknown, helperWord: ""', 7, "none", C["row7"]),
    "word-foo": (BUILTIN + ', diskImage: .unknown, helperWord: "foo"', 7, "none", C["row7"]),
    "sd-missing": (STIK + ", stikDebugInstalled: false, stikDebugReturnedOff: true", 8, "none", C["row8"]),
    "sd-returned": (STIK + ", stikDebugInstalled: true, stikDebugReturnedOff: true", 9, "openStikDebug", C["row9"]),
    "sd": (STIK + ", stikDebugInstalled: true", 10, "openStikDebug", C["row10"]),
    "sd-refusal": ('method: .stikdebug, pairingImported: false, diskImage: .notMounted, stikDebugInstalled: true, importRefusal: "x"',
                   10, "openStikDebug", C["row10"]),
    "sd-lc": (STIK + ", stikDebugInstalled: true, liveContainer: true", 10, "openStikDebug", C["row10"] + " " + C["livecontainer"]),
    "sd-returned-lc": (STIK + ", stikDebugInstalled: true, stikDebugReturnedOff: true, liveContainer: true",
                       9, "openStikDebug", C["row9"] + " " + C["livecontainer"]),
    "wait-txm": ("method: .wait, txm: .present", 11, "none", C["row11-txm"]),
    "wait-unknown": ("method: .wait, txm: .unknown", 11, "none", C["row11-txm"]),
    "wait-mac": ("method: .wait, txm: .missing", 11, "none", C["row11-mac"]),
}

UNFINISHED = ("attach-failed", "prepare-incomplete", "detach-failed", "timeout", "interrupted", "start-failed", "invalid-request")
HELPER = dict({"connect-failed": C["helper-connect-failed"], "tunnel-failed": C["helper-tunnel-failed"],
               "pairing-rejected": C["helper-pairing-rejected"], "ddi-not-mounted": C["helper-ddi-not-mounted"],
               "pairing-absent": "nil", "finished": "nil", "": "nil", "foo": "nil"},
              **{word: C["helper-unfinished"] for word in UNFINISHED})
MOUNT = {"ready": C["row7"], "unreachable": C["mount-unreachable"], "download": C["mount-download"],
         "pairing": C["mount-pairing"], "mount": C["mount-mount"], "other": C["mount-other"],
         "weird": C["mount-other"], "": "nil"}
REFUSAL = {0: "nil", 1: C["refusal-failed"], 2: C["refusal-legacy"], 3: C["refusal-invalid"], 4: "nil"}
DISK = {"finished": "mounted", "attach-failed": "mounted", "prepare-incomplete": "mounted", "detach-failed": "mounted",
        "ddi-not-mounted": "notMounted", "connect-failed": "unknown", "": "unknown", "timeout": "unknown"}
CODE = {
    'reason: "helper-failed", helperWord: "", mountReason: nil': "Code: helper-failed",
    'reason: "helper-failed", helperWord: "connect-failed", mountReason: nil': "Code: helper-failed · connect-failed",
    'reason: "helper-failed", helperWord: "", mountReason: "mounting:device:-8/0"': "Code: helper-failed · mounting:device:-8/0",
    'reason: "helper-failed", helperWord: "connect-failed", mountReason: "mounting:device:-8/0"':
        "Code: helper-failed · mounting:device:-8/0",
    'reason: "helper-failed", helperWord: "connect-failed", mountReason: ""': "Code: helper-failed · connect-failed",
}
# (liveContainer, sideStoreAvailable): the lines, in order. SideStore's Import row belongs to the pane, not the lines.
OTHER_WAYS = {
    (False, False): [C["other-share"], C["other-documents"], C["other-iloader"]],
    (False, True): [C["other-share"], C["other-documents"], C["other-iloader"]],
    (True, False): [C["other-iloader"]],
    (True, True): [C["other-iloader"]],
}
CONSTANT = {"severity": C["severity"], "setupSuffix": C["setup-suffix"], "liveContainerSentence": C["livecontainer"]}


def swift_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def driver():
    lines = [
        "import Foundation",
        'func emit(_ kind: String, _ name: String, _ value: String?) { print("\\(kind)\\t\\(name)\\t\\(value ?? "nil")") }',
        "func disk(_ d: DiskImageState) -> String {",
        "    switch d { case .mounted: return \"mounted\"; case .notMounted: return \"notMounted\"; case .unknown: return \"unknown\"; case .busy(let s): return \"busy:\" + s }",
        "}",
    ]
    for name, (args, _, _, _) in sorted(CASES.items()):
        lines.append("do {{ let s = JitNextStep.decide(JitSituation({0})); "
                     'emit("decide", "{1}", "\\(s.row)|\\(s.primary?.rawValue ?? "none")|\\(s.advice ?? "none")") }}'.format(args, name))
    for word in sorted(HELPER):
        lines.append('emit("helper", {0}, JitNextStep.helperSentence({0}))'.format(swift_string(word)))
    for word in sorted(MOUNT):
        lines.append('emit("mount", {0}, JitNextStep.mountSentence({0}))'.format(swift_string(word)))
    for value in sorted(REFUSAL):
        lines.append('emit("refusal", "{0}", JitNextStep.importRefusalSentence({0}))'.format(value))
    for word in sorted(DISK):
        lines.append('emit("disk", {0}, disk(DiskImageState.fromHelperOutcome({0})))'.format(swift_string(word)))
    for index, args in enumerate(sorted(CODE)):
        lines.append('emit("code", "{0}", JitNextStep.codeLine({1}))'.format(index, args))
    for name in sorted(CONSTANT):
        lines.append('emit("constant", "{0}", JitNextStep.{0})'.format(name))
    for lc, store in sorted(OTHER_WAYS):
        lines.append('emit("otherways", "{0}{1}", JitNextStep.otherWays(liveContainer: {0}, sideStoreAvailable: {1}).joined(separator: "|"))'
                     .format(str(lc).lower(), str(store).lower()))
    return "\n".join(lines) + "\n"


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def next_step_problems(out):
    got = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            got[(parts[0], parts[1])] = parts[2]
    problems = []

    def check(kind, name, want):
        if got.get((kind, name)) != want:
            problems.append("{0}: {1!r} gave {2!r}, wanted {3!r}".format(kind, name, got.get((kind, name)), want))

    for name, (_, row, primary, advice) in CASES.items():
        check("decide", name, "{0}|{1}|{2}".format(row, primary, advice))
    for word, sentence in HELPER.items():
        check("helper", word, sentence)
    for word, sentence in MOUNT.items():
        check("mount", word, sentence)
    for value, sentence in REFUSAL.items():
        check("refusal", str(value), sentence)
    for word, state in DISK.items():
        check("disk", word, state)
    for index, args in enumerate(sorted(CODE)):
        check("code", str(index), CODE[args])
    for name, sentence in CONSTANT.items():
        check("constant", name, sentence)
    for (lc, store), lines in OTHER_WAYS.items():
        check("otherways", str(lc).lower() + str(store).lower(), "|".join(lines))
    return problems


def run(source_text):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "JitNextStep.swift"
        path.write_text(source_text, encoding="utf-8")
        return compile_and_run([path, PRESENCE], driver(), tmp)


class RealFiles(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        out = run(read(SOURCE))
        sys.stderr.write("\n" + "".join(l + "\n" for l in out.splitlines() if l.startswith("decide\t")))
        self.assertEqual(next_step_problems(out), [])

    def test_control_every_row_has_a_case(self):
        self.assertEqual(sorted({row for _, row, _, _ in CASES.values()}), list(range(1, 12)))
        primaries = {row: primary for _, row, primary, _ in CASES.values()}
        self.assertEqual(primaries[8], "none")
        self.assertEqual(primaries[1], "none")

    def test_edge_missing_or_empty_input_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(FileNotFoundError):
                read(Path(tmp) / "absent.swift")


class Mutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(SOURCE)

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor moved")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out)
        return out

    def assert_caught(self, text, prefix):
        problems = next_step_problems(run(text))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_row_4_checked_before_row_2(self):
        row2 = "        if s.entitlementMissing { return JitStep(row: 2, advice: row2, primary: nil) }\n"
        row4 = "                return pairingStep(s)\n            }\n"
        text = self.edited(self.edited(self.text, row2, ""), row4, row4 + "    " + row2)
        self.assert_caught(text, "decide:")

    def test_mutation_jit_on_not_first(self):
        self.assert_caught(self.edited(self.text, "        if s.jitOn { return JitStep(row: 1, advice: nil, primary: nil) }\n", ""), "decide:")

    def test_mutation_mac_clause_kept_for_unknown_txm(self):
        self.assert_caught(self.edited(self.text, "s.txm == .missing ? row11Mac : row11TXM", "s.txm != .present ? row11Mac : row11TXM"), "decide:")

    def test_mutation_a_stale_helper_word_shown(self):
        self.assert_caught(self.edited(self.text, "!s.actedThisLaunch, let sentence", "let sentence"), "decide:")

    def test_mutation_livecontainer_sentence_dropped(self):
        self.assert_caught(self.edited(self.text, 's.liveContainer ? advice + " " + liveContainerSentence : advice', "advice"), "decide:")

    def test_mutation_phone_restored_in_connect_failed(self):
        self.assert_caught(self.edited(self.text, "couldn't reach this device through", "couldn't reach this phone through"), "helper:")

    def test_mutation_unfinished_mount_counted_as_unknown(self):
        self.assert_caught(self.edited(self.text, '"finished", "attach-failed", "prepare-incomplete", "detach-failed"',
                                       '"finished", "attach-failed", "prepare-incomplete"'), "disk:")

    def test_mutation_ready_mount_is_a_failure_sentence(self):
        self.assert_caught(self.edited(self.text, 'case "ready": return row7', 'case "ready": return mountOther'), "mount:")

    def test_mutation_refusal_codes_swapped(self):
        self.assert_caught(self.edited(self.text, "case 2: return", "case 9: return"), "refusal:")

    def test_mutation_mount_reason_loses_to_the_helper_word(self):
        self.assert_caught(self.edited(self.text, "let detail = mountReason.flatMap { $0.isEmpty ? nil : $0 } ?? helperWord",
                                       "let detail = helperWord.isEmpty ? (mountReason ?? \"\") : helperWord"), "code:")

    def test_mutation_sidestore_offered_inside_livecontainer(self):
        self.assert_caught(self.edited(self.text, "        if s.liveContainer {\n            let place", "        if s.liveContainer && !s.sideStoreAvailable {\n            let place"), "decide:")

    def test_mutation_refusal_not_answered_first(self):
        line = "        if let refusal = s.importRefusal { return JitStep(row: 4, advice: refusal, primary: .importPairingFile) }\n"
        self.assert_caught(self.edited(self.text, line, ""), "decide:")

    def test_mutation_nothing_sent_outranks_lockdown(self):
        lockdown = "        if s.sideStoreLockdown { return JitStep(row: 4, advice: row4Lockdown, primary: .getFromSideStore) }\n"
        nothing = "        if s.sideStoreSentNothing { return JitStep(row: 4, advice: row4NoAnswer, primary: .getFromSideStore) }\n"
        self.assert_caught(self.edited(self.text, lockdown + nothing, nothing + lockdown), "decide:")

    def test_mutation_share_line_shown_inside_livecontainer(self):
        self.assert_caught(self.edited(self.text, "if liveContainer { return [iLoader] }", "if false { return [iLoader] }"), "otherways:")

    def test_mutation_mac_wording_restored(self):
        self.assert_caught(self.edited(self.text, "couldn't be read. Make a new pairing file on a computer",
                                       "couldn't be read. Make a new pairing file on your Mac"), "helper:")

    def test_mutation_setup_suffix_reworded(self):
        self.assert_caught(self.edited(self.text, '"Setup continues here."', '"Setup continues."'), "constant:")


if __name__ == "__main__":
    unittest.main()
