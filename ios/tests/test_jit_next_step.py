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
EVERYTHING_WRONG = ('entitlementMissing: true, method: .stikdebug, pairingImported: false, diskImage: .notMounted, '
                    'helperWord: "connect-failed", stikDebugInstalled: false, stikDebugReturnedOff: true, txm: .missing, '
                    'liveContainer: true, mountResult: "mount", actedThisLaunch: true, importRefusal: "x", checklistFails: true, '
                    'liveProcessMissing: true')
BUILTIN = "method: .builtin, pairingImported: true"
STIK = "method: .stikdebug"
NOFILE = "method: .builtin, pairingImported: false"
INSTALLED = ", stikDebugInstalled: true"
FALLBACK = 'fallbackAdvice: "Fallback body"'


def ending(text, installed=False):
    """The advice with the StikDebug line the table puts at the end."""
    return text + " " + (C["sd-ready"] if installed else C["sd-instead"])


# name: (JitSituation arguments, row, primary, advice)
CASES = {
    "on": ("jitOn: true, " + EVERYTHING_WRONG, 1, "none", "none"),
    "on-fallback": ("jitOn: true, " + EVERYTHING_WRONG + ", " + FALLBACK, 12, "none", "Fallback body"),
    "on-fallback-plain": ("jitOn: true, " + FALLBACK, 12, "none", "Fallback body"),
    "entitlement-and-pairing": ("entitlementMissing: true, method: .builtin, pairingImported: false", 2, "none", C["row2"]),
    "entitlement-beats-returned": ("entitlementMissing: true, method: .builtin, pairingImported: true" + INSTALLED
                                   + ", stikDebugReturnedOff: true, checklistFails: true", 2, "none", C["row2"]),
    "returned-off": (BUILTIN + ", diskImage: .mounted" + INSTALLED + ", stikDebugReturnedOff: true", 9, "openStikDebug", C["row9"]),
    "returned-off-beats-pairing": (NOFILE + INSTALLED + ", stikDebugReturnedOff: true", 9, "openStikDebug", C["row9"]),
    "returned-off-beats-checklist": (NOFILE + INSTALLED + ", stikDebugReturnedOff: true, liveContainer: true, checklistFails: true",
                                     9, "openStikDebug", C["row9"]),
    "checklist-installed": (NOFILE + INSTALLED + ", liveContainer: true, checklistFails: true", 3, "openStikDebug", C["sd-ready"]),
    "checklist-not-installed": (NOFILE + ", liveContainer: true, checklistFails: true", 3, "tryAgain", C["sd-instead"]),
    "lc-old-installed": (NOFILE + INSTALLED + ", liveContainer: true, checklistFails: true, liveContainerOld: true",
                         3, "openStikDebug", C["sd-ready"]),
    "lc-old-not-installed": (NOFILE + ", liveContainer: true, checklistFails: true, liveContainerOld: true", 3, "none", C["sd-instead"]),
    "checklist-beats-ddi": (BUILTIN + ", diskImage: .notMounted, liveContainer: true, checklistFails: true", 3, "tryAgain", C["sd-instead"]),
    "checklist-beats-helper": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed", liveContainer: true, checklistFails: true',
                               3, "tryAgain", C["sd-instead"]),
    "lc-no-pairing-is-row-4": ("method: .builtin, pairingImported: false, liveContainer: true", 4, "importPairingFile", C["row4-lc-iphone"]),
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
    "helper": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed"', 6, "tryAgain",
               ending(C["helper-connect-failed"])),
    "helper-installed": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed"' + INSTALLED, 6, "openStikDebug",
                         ending(C["helper-connect-failed"], True)),
    "helper-after-mount": (BUILTIN + ', diskImage: .mounted, helperWord: "connect-failed", mountResult: "ready", actedThisLaunch: true',
                           7, "tryAgain", ending(C["row7"])),
    "helper-after-import": (BUILTIN + ', diskImage: .unknown, helperWord: "tunnel-failed", actedThisLaunch: true',
                            7, "tryAgain", ending(C["row7"])),
    "word-empty": (BUILTIN + ', diskImage: .unknown, helperWord: ""', 7, "tryAgain", ending(C["row7"])),
    "word-empty-installed": (BUILTIN + ', diskImage: .unknown, helperWord: ""' + INSTALLED, 7, "openStikDebug", ending(C["row7"], True)),
    "word-foo": (BUILTIN + ', diskImage: .unknown, helperWord: "foo"', 7, "tryAgain", ending(C["row7"])),
    "liveprocess-word": (BUILTIN + ', diskImage: .unknown, helperWord: "liveprocess-missing", liveContainer: true', 6, "tryAgain",
                         ending(C["helper-liveprocess"])),
    "liveprocess-flag": (BUILTIN + ', diskImage: .unknown, helperWord: "", liveContainer: true, liveProcessMissing: true', 6, "tryAgain",
                         ending(C["helper-liveprocess"])),
    "liveprocess-flag-after-act": (BUILTIN + ", diskImage: .unknown, liveContainer: true, actedThisLaunch: true, liveProcessMissing: true",
                                   7, "tryAgain", ending(C["row7"])),
    "stored-stikdebug-reads-as-builtin": (STIK + ", pairingImported: true, diskImage: .mounted", 7, "tryAgain", ending(C["row7"])),
    "stored-stikdebug-no-pairing": (STIK + ", pairingImported: false", 4, "importPairingFile", C["row4"]),
    "stored-stikdebug-lc-no-pairing": (STIK + ", pairingImported: false, liveContainer: true", 4, "importPairingFile", C["row4-lc-iphone"]),
    "wait-txm": ("method: .wait, txm: .present", 11, "none", C["row11-txm"]),
    "wait-unknown": ("method: .wait, txm: .unknown", 11, "none", C["row11-txm"]),
    "wait-mac": ("method: .wait, txm: .missing", 11, "none", C["row11-mac"]),
    # Test JIT Setup: a pass is the reopen sentence alone, a stop at the disk image is the mount step with no advice of its own,
# other stops leave only the StikDebug line, and an action after a stop hands the advice back to the launch rules.
    "test-passed": (BUILTIN + ", diskImage: .unknown, testResult: .passed", 7, "none", C["row7"]),
    "test-passed-beats-helper-word": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed", testResult: .passed', 7, "none", C["row7"]),
    "test-passed-no-stikdebug-line": (BUILTIN + ", diskImage: .mounted" + INSTALLED + ", testResult: .passed", 7, "none", C["row7"]),
    "test-passed-pairing-first": (NOFILE + ", testResult: .passed", 4, "importPairingFile", C["row4"]),
    "test-passed-stale-after-unmount": (BUILTIN + ", diskImage: .notMounted, testResult: .passed", 5, "mountDiskImage", C["row5"]),
    "test-stopped-disk": (BUILTIN + ', diskImage: .unknown, testResult: .stopped(.diskImage, reason: "")', 5, "mountDiskImage", "none"),
    "test-stopped-disk-beats-helper-word": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed", '
                                            'testResult: .stopped(.diskImage, reason: "")', 5, "mountDiskImage", "none"),
    "test-stopped-disk-not-mounted": (BUILTIN + ', diskImage: .notMounted, testResult: .stopped(.diskImage, reason: "")', 5, "mountDiskImage", "none"),
    "test-stopped-disk-busy": (BUILTIN + ', diskImage: .busy("mounting"), actedThisLaunch: true, testResult: .stopped(.diskImage, reason: "")',
                               5, "mountDiskImage", C["row5"]),
    "test-stopped-disk-mount-failed": (BUILTIN + ', diskImage: .notMounted, mountResult: "download", actedThisLaunch: true, '
                                       'testResult: .stopped(.diskImage, reason: "")', 5, "mountDiskImage", C["mount-download"]),
    "test-stopped-disk-then-mounted": (BUILTIN + ', diskImage: .mounted, testResult: .stopped(.diskImage, reason: "")', 7, "tryAgain",
                                       ending(C["row7"])),
    # After a stop (other than at the disk image) the test row carries the fix, so only the StikDebug line is left here.
    "test-stopped-helper": (BUILTIN + ', diskImage: .unknown, testResult: .stopped(.helper, reason: "start-failed")', 3, "tryAgain",
                            C["sd-instead"]),
    "test-stopped-vpn-installed": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed"' + INSTALLED
                                   + ', testResult: .stopped(.localDevVPN, reason: "")', 3, "openStikDebug", C["sd-ready"]),
    "test-stopped-pairing": (BUILTIN + ', diskImage: .unknown, testResult: .stopped(.pairing, reason: "")', 3, "tryAgain", C["sd-instead"]),
    "test-stopped-pairing-no-launch-sentence": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed", '
                                                'testResult: .stopped(.pairing, reason: "rejected")', 3, "tryAgain", C["sd-instead"]),
    "test-stopped-pairing-installed": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed"' + INSTALLED
                                       + ', testResult: .stopped(.pairing, reason: "rejected")', 3, "openStikDebug", C["sd-ready"]),
    "test-stopped-pairing-then-acted": (BUILTIN + ', diskImage: .unknown, helperWord: "connect-failed", actedThisLaunch: true, '
                                        'testResult: .stopped(.pairing, reason: "rejected")', 7, "tryAgain", ending(C["row7"])),
    "test-with-jit-on-changes-nothing": ("jitOn: true, " + BUILTIN + ", testResult: .passed", 1, "none", "none"),
}

# (jitOn, diskImage, testRowShown): the disk image row's fix.
DISK_FIX_TEXT = "Test JIT Setup checks it."
DISK_FIXES = {
    ("false", ".unknown", "true"): DISK_FIX_TEXT,
    ("true", ".unknown", "true"): "nil",
    ("false", ".unknown", "false"): "nil",
    ("false", ".mounted", "true"): "nil",
    ("false", ".notMounted", "true"): "nil",
    ("false", '.busy("mounting")', "true"): "nil",
}

UNFINISHED = ("attach-failed", "prepare-incomplete", "detach-failed", "timeout", "interrupted", "start-failed", "invalid-request",
              "connect-timeout")
HELPER = dict({"connect-failed": C["helper-connect-failed"], "tunnel-failed": C["helper-tunnel-failed"],
               "pairing-rejected": C["helper-pairing-rejected"], "ddi-not-mounted": C["helper-ddi-not-mounted"],
               "liveprocess-missing": C["helper-liveprocess"],
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
CONSTANT = {"severity": C["severity"], "setupSuffix": C["setup-suffix"]}
CAPTIONS = {"builtin": C["caption-builtin"], "wait": C["caption-wait"]}

HELPER_FIX = "The JIT helper didn't start. Close ARMSX3 fully and open it again."
HELPER_FIX_LC = ("LiveContainer didn't start the JIT helper. Check that Helper Process Support is on for ARMSX3, "
                 "then close ARMSX3 and open it again.")
HELPER_FIX_LC_OLD = ("This LiveContainer is 3.8.11, and built-in JIT needs 3.8.13 or later. "
                     "Update LiveContainer, then install ARMSX3 in it again.")
DISK_FIX = "The Developer Disk Image isn't mounted. Mount it, then test again."
PASSED_DETAIL = "Helper started, device reached through LocalDevVPN, pairing accepted, disk image mounted."


def stopped(value, report, fix, fix_lc=None, fix_old=None):
    return (value, report, fix, fix_lc or fix, "nil", fix_old or fix_lc or fix)


# (result word, reason): value | report word | fix outside LiveContainer | fix inside | passed detail
# | fix inside an old LiveContainer
TEST_RESULTS = {
    ("ready", "ignored"): ("Passed", "passed", "nil", "nil", PASSED_DETAIL, "nil"),
    ("other", "start-failed"): stopped("Stopped at helper", "stopped at helper (start-failed)", HELPER_FIX, HELPER_FIX_LC, HELPER_FIX_LC_OLD),
    ("other", "timeout"): stopped("Stopped at helper", "stopped at helper (timeout)", HELPER_FIX, HELPER_FIX_LC, HELPER_FIX_LC_OLD),
    ("other", "mounting:device:-8/0"): stopped("Stopped at helper", "stopped at helper (mounting:device:-8/0)", HELPER_FIX, HELPER_FIX_LC, HELPER_FIX_LC_OLD),
    ("other", ""): stopped("Stopped at helper", "stopped at helper", HELPER_FIX, HELPER_FIX_LC, HELPER_FIX_LC_OLD),
    ("download", "x"): stopped("Stopped at helper", "stopped at helper (x)", HELPER_FIX, HELPER_FIX_LC, HELPER_FIX_LC_OLD),
    ("unreachable", "connect-failed"): stopped("Stopped at LocalDevVPN", "stopped at LocalDevVPN (connect-failed)", C["mount-unreachable"]),
    ("pairing", "rejected"): stopped("Stopped at pairing", "stopped at pairing (rejected)", C["mount-pairing"]),
    ("unmounted", ""): stopped("Stopped at disk image", "stopped at disk image", DISK_FIX),
}
# The word the helper last sent: the row reads Starting helper… until one arrives, and a later word keeps it.
TEST_STAGES = {"none": ("nil", "Starting helper…"), "connecting": ('"connecting"', "Connecting…"),
               "pairing": ('"pairing"', "Pairing…"), "checking": ('"checking"', "Starting helper…"),
               "downloading": ('"downloading"', "Starting helper…")}


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
    for name in sorted(CAPTIONS):
        lines.append('emit("caption", "{0}", JitMethod.{0}.caption)'.format(name))
    for lc, store in sorted(OTHER_WAYS):
        lines.append('emit("otherways", "{0}{1}", JitNextStep.otherWays(liveContainer: {0}, sideStoreAvailable: {1}).joined(separator: "|"))'
                     .format(str(lc).lower(), str(store).lower()))
    for result, reason in sorted(TEST_RESULTS):
        lines.append('do {{ let r = JitTestResult(result: {0}, reason: {1}); emit("testresult", {2}, '
                     '[r.value, r.reportWord, r.fix(liveContainer: false) ?? "nil", r.fix(liveContainer: true) ?? "nil", '
                     'r.passedDetail ?? "nil", r.fix(liveContainer: true, tooOld: "3.8.11") ?? "nil"].joined(separator: "|")) }}'.format(swift_string(result), swift_string(reason),
                                                                                   swift_string(result + ":" + reason)))
    for key in sorted(DISK_FIXES):
        lines.append('emit("diskfix", {0}, JitNextStep.diskImageFix(jitOn: {1}, diskImage: {2}, testRowShown: {3}))'
                     .format(swift_string("|".join(key)), *key))
    for name, (arg, _) in sorted(TEST_STAGES.items()):
        lines.append('emit("teststage", "{0}", JitNextStep.testStageValue({1}))'.format(name, arg))
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
    for name, sentence in CAPTIONS.items():
        check("caption", name, sentence)
    for (lc, store), lines in OTHER_WAYS.items():
        check("otherways", str(lc).lower() + str(store).lower(), "|".join(lines))
    for (result, reason), words in TEST_RESULTS.items():
        check("testresult", result + ":" + reason, "|".join(words))
    for name, (_, value) in TEST_STAGES.items():
        check("teststage", name, value)
    for key, fix in DISK_FIXES.items():
        check("diskfix", "|".join(key), fix)
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
        self.assertEqual(sorted({row for _, row, _, _ in CASES.values()}), [1, 2, 3, 4, 5, 6, 7, 9, 11, 12])
        primaries = {row: primary for _, row, primary, _ in CASES.values()}
        self.assertEqual(primaries[1], "none")
        self.assertEqual(primaries[12], "none")
        self.assertEqual({primary for _, _, primary, _ in CASES.values()},
                         {"none", "tryAgain", "openStikDebug", "importPairingFile", "getFromSideStore", "mountDiskImage"})

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
        self.assert_caught(self.edited(self.text, "        if s.jitOn {\n", "        if s.jitOn && false {\n"), "decide:")

    def test_mutation_fallback_advice_ignored_with_jit_on(self):
        self.assert_caught(self.edited(self.text, "JitStep(row: 12, advice: advice, primary: nil)", "JitStep(row: 1, advice: nil, primary: nil)"),
                           "decide:")

    def test_mutation_returned_off_not_answered(self):
        line = "        if s.stikDebugReturnedOff { return JitStep(row: 9, advice: row9, primary: .openStikDebug) }\n"
        self.assert_caught(self.edited(self.text, line, ""), "decide:")

    def test_mutation_checklist_ignored(self):
        self.assert_caught(self.edited(self.text, "        if s.checklistFails {\n", "        if s.checklistFails && false {\n"), "decide:")

    def test_mutation_stikdebug_line_dropped(self):
        self.assert_caught(self.edited(self.text, "(sentence.map { $0 + \" \" + line } ?? line,", "(sentence ?? line,"), "decide:")

    def test_mutation_open_stikdebug_offered_when_not_installed(self):
        self.assert_caught(self.edited(self.text, "s.stikDebugInstalled ? .openStikDebug : .tryAgain", ".openStikDebug"), "decide:")

    def test_mutation_liveprocess_flag_ignored(self):
        self.assert_caught(self.edited(self.text, 's.liveProcessMissing ? "liveprocess-missing" : s.helperWord', "s.helperWord"), "decide:")

    def test_mutation_connect_timeout_not_unfinished(self):
        self.assert_caught(self.edited(self.text, '"invalid-request",\n             "connect-timeout":', '"invalid-request":'), "helper:")

    def test_mutation_mac_clause_kept_for_unknown_txm(self):
        self.assert_caught(self.edited(self.text, "s.txm == .missing ? row11Mac : row11TXM", "s.txm != .present ? row11Mac : row11TXM"), "decide:")

    def test_mutation_a_stale_helper_word_shown(self):
        self.assert_caught(self.edited(self.text, "!s.actedThisLaunch, let sentence", "let sentence"), "decide:")

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

    def test_mutation_wait_caption_names_stikdebug_again(self):
        self.assert_caught(self.edited(self.text, "you attach yourself, from a Mac.", "you attach yourself, from StikDebug or a Mac."), "caption:")

    def test_mutation_builtin_caption_reworded(self):
        self.assert_caught(self.edited(self.text, "Needs a pairing file and LocalDevVPN.", "Needs a pairing file."), "caption:")

    def test_mutation_setup_suffix_reworded(self):
        self.assert_caught(self.edited(self.text, '"Setup continues here."', '"Setup continues."'), "constant:")

    def test_mutation_unreachable_not_its_own_stage(self):
        self.assert_caught(self.edited(self.text, 'case "unreachable": self = .stopped(.localDevVPN, reason: reason)',
                                       'case "unreachable": self = .stopped(.helper, reason: reason)'), "testresult:")

    def test_mutation_unmounted_read_as_a_pass(self):
        self.assert_caught(self.edited(self.text, 'case "unmounted": self = .stopped(.diskImage, reason: reason)',
                                       'case "unmounted": self = .passed'), "testresult:")

    def test_mutation_unknown_word_passes(self):
        self.assert_caught(self.edited(self.text, "default: self = .stopped(.helper, reason: reason)", "default: self = .passed"),
                           "testresult:")

    def test_mutation_helper_fix_ignores_livecontainer(self):
        self.assert_caught(self.edited(self.text, "return liveContainer\n", "return false\n"), "testresult:")

    def test_mutation_old_livecontainer_offered_try_again(self):
        self.assert_caught(self.edited(self.text, "s.liveContainerOld && next.primary == .tryAgain ? nil : next.primary", "next.primary"),
                           "decide:")

    def test_mutation_old_livecontainer_gets_the_helper_support_fix(self):
        self.assert_caught(self.edited(self.text, "if let version {", "if let version, false {"), "testresult:")

    def test_mutation_report_word_drops_the_reason(self):
        self.assert_caught(self.edited(self.text, '(reason.isEmpty ? "" : " (\\(reason))")', '""'), "testresult:")

    def test_mutation_checking_stage_drawn(self):
        self.assert_caught(self.edited(self.text, 'case "pairing"?: return String(localized: "Pairing…")',
                                       'case "pairing"?: return String(localized: "Pairing…")\n        case "checking"?: return "Checking…"'),
                           "teststage:")

    def test_mutation_passed_test_keeps_the_stikdebug_line(self):
        self.assert_caught(self.edited(self.text, "if s.testResult == .passed { return JitStep(row: 7, advice: row7, primary: nil) }",
                                       "if s.testResult == .passed { let next = fallback(s, after: row7); "
                                       "return JitStep(row: 7, advice: next.advice, primary: next.primary) }"), "decide:")

    def test_mutation_passed_test_ignored(self):
        self.assert_caught(self.edited(self.text, "if s.testResult == .passed {", "if s.testResult == .passed && false {"), "decide:")

    def test_mutation_passed_test_beats_a_missing_pairing(self):
        passed = "            if s.testResult == .passed { return JitStep(row: 7, advice: row7, primary: nil) }\n"
        text = self.edited(self.text, passed, "")
        text = self.edited(text, "            if !s.pairingImported || s.importRefusal != nil {\n",
                           passed + "            if !s.pairingImported || s.importRefusal != nil {\n")
        self.assert_caught(text, "decide:")

    def test_mutation_disk_stop_never_asks_for_a_mount(self):
        self.assert_caught(self.edited(self.text, "if testFoundNoImage { return JitStep(row: 5, advice: nil, primary: .mountDiskImage) }", "break"),
                           "decide:")

    def test_mutation_disk_stop_outlives_a_mount(self):
        self.assert_caught(self.edited(self.text, "== .diskImage && s.diskImage != .mounted", "== .diskImage"), "decide:")

    def test_mutation_other_stop_asks_for_a_mount(self):
        self.assert_caught(self.edited(self.text, "s.testResult?.stoppedStage == .diskImage", "s.testResult?.stoppedStage != nil"), "decide:")

    def test_mutation_disk_stop_repeats_the_mount_sentence(self):
        self.assert_caught(self.edited(self.text, "failed ?? (repeatsTest ? nil : row5)", "failed ?? row5"), "decide:")

    def test_mutation_disk_stop_repeats_the_mount_sentence_when_unknown(self):
        self.assert_caught(self.edited(self.text, "if testFoundNoImage { return JitStep(row: 5, advice: nil, primary: .mountDiskImage) }",
                                       "if testFoundNoImage { return JitStep(row: 5, advice: row5, primary: .mountDiskImage) }"), "decide:")

    def test_mutation_disk_stop_hides_the_sentence_while_mounting(self):
        self.assert_caught(self.edited(self.text, "testFoundNoImage && s.diskImage == .notMounted", "testFoundNoImage"), "decide:")

    def test_mutation_disk_stop_hides_a_failed_mount(self):
        self.assert_caught(self.edited(self.text, "failed ?? (repeatsTest ? nil : row5)", "repeatsTest ? nil : (failed ?? row5)"), "decide:")

    def test_mutation_other_stop_keeps_the_launch_sentence(self):
        self.assert_caught(self.edited(self.text, "stage != .diskImage, !s.actedThisLaunch {", "stage != .diskImage, false {"), "decide:")

    def test_mutation_other_stop_outlives_an_action(self):
        self.assert_caught(self.edited(self.text, "stage != .diskImage, !s.actedThisLaunch {", "stage != .diskImage {"), "decide:")

    def test_mutation_stale_disk_stop_hides_the_reopen_sentence(self):
        self.assert_caught(self.edited(self.text, "stage != .diskImage, !s.actedThisLaunch {", "!s.actedThisLaunch {"), "decide:")

    def test_mutation_other_stop_keeps_the_reopen_sentence(self):
        self.assert_caught(self.edited(self.text, "let next = fallback(s, after: nil)\n                return JitStep(row: 3,",
                                       "let next = fallback(s, after: row7)\n                return JitStep(row: 3,"), "decide:")

    def test_mutation_disk_fix_with_jit_on(self):
        self.assert_caught(self.edited(self.text, "!jitOn && testRowShown && diskImage == .unknown", "testRowShown && diskImage == .unknown"),
                           "diskfix:")

    def test_mutation_disk_fix_without_the_row(self):
        self.assert_caught(self.edited(self.text, "!jitOn && testRowShown && diskImage == .unknown", "!jitOn && diskImage == .unknown"),
                           "diskfix:")

    def test_mutation_disk_fix_for_a_known_image(self):
        self.assert_caught(self.edited(self.text, "!jitOn && testRowShown && diskImage == .unknown", "!jitOn && testRowShown"), "diskfix:")

    def test_mutation_connecting_stage_missing(self):
        self.assert_caught(self.edited(self.text, 'case "connecting"?: return String(localized: "Connecting…")', ""), "teststage:")


if __name__ == "__main__":
    unittest.main()
