#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""JitFallback.swift, run on the Mac host with DeviceChecks.swift, turns a launch's JIT facts and a LiveContainer probe
into one stored cause, the LiveContainer checklist rows and the one library notice, and a Wait launch and a JIT launch
served by the helper have no cause. LiveContainerProbe.swift reads the environment and LiveContainer's bundle behind
guards, with a stand-in LiveContainer made in a temporary folder. It does not read a real LiveContainer."""

import plistlib
import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
FALLBACK = IOS / "app" / "JIT" / "JitFallback.swift"
PROBE_SOURCE = IOS / "app" / "JIT" / "LiveContainerProbe.swift"
PRESENCE = IOS / "app" / "JIT" / "DeviceChecks.swift"

PROBE_ORDER = ["insideLiveContainer", "multitask", "version", "hooksActive", "liveProcessPresent",
               "helperSettingStored", "bundleID"]


def swift_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def swift_value(value):
    if value is None:
        return "nil"
    if isinstance(value, bool):
        return "true" if value else "false"
    return swift_string(value)


def probe(inside=True, **fields):
    """A Swift JitFallback.Probe literal. A field left out keeps the type's default (unreadable)."""
    given = dict(insideLiveContainer=inside, **fields)
    unknown = set(given) - set(PROBE_ORDER)
    assert not unknown, unknown
    return "JitFallback.Probe(" + ", ".join(
        "{0}: {1}".format(k, swift_value(given[k])) for k in PROBE_ORDER if k in given) + ")"


# A LiveContainer 3.8.13 whose hooks are active: built-in could run, so only the later causes apply.
READY = dict(version="3.8.13", hooksActive=True, liveProcessPresent=True)

# name: (jitOn, jitReason, helperWord, methodAtLaunch, storedCause, probe, "cause|otherInstall")
DECIDE = {
    "wait-off-multitask": (False, "no-enabler-reason", "", "wait", None, probe(multitask=True), "none|false"),
    "wait-off-outside": (False, "no-enabler-reason", "connect-failed", "wait", None, probe(False), "none|false"),
    "wait-traced-stored": (True, "already-traced", "", "wait", "vpn-off", probe(False), "none|false"),
    "wait-traced-pre": (True, "already-traced", "", "wait", None, probe(True, **READY), "none|false"),
    "helper-ok-outside": (True, "helper-ok", "finished", "builtin", None, probe(False), "none|false"),
    "helper-ok-active": (True, "helper-ok", "finished", "builtin", "vpn-off", probe(**READY), "none|false"),
    "helper-ok-inactive": (True, "helper-ok", "finished", "builtin", None,
                           probe(version="3.8.13", hooksActive=False), "none|true"),
    "helper-ok-inactive-clears-cause": (True, "helper-ok", "finished", "builtin", "lc-old",
                                        probe(hooksActive=False), "none|true"),
    "helper-ok-unreadable": (True, "helper-ok", "finished", "builtin", None, probe(), "none|false"),
    "traced-stored": (True, "already-traced", "", "stikdebug", "vpn-off", probe(False), "vpn-off|false"),
    "traced-stored-lc-old": (True, "already-traced", "", "builtin", "lc-old", probe(version="3.8.0"), "lc-old|false"),
    "traced-none-stored": (True, "already-traced", "", "stikdebug", None, probe(False), "pre-traced|false"),
    "traced-junk-stored": (True, "already-traced", "", "builtin", "no-such-cause", probe(False), "pre-traced|false"),
    "handshake-none-stored": (True, "stikdebug-handshake", "", "builtin", None, probe(**READY), "pre-traced|false"),
    "multitask-first": (False, "no-enabler-reason", "", "builtin", None, probe(multitask=True), "multitask|false"),
    "multitask-beats-old": (False, "no-enabler-reason", "", "builtin", None,
                            probe(multitask=True, version="3.8.0", hooksActive=False), "multitask|false"),
    "version-unreadable": (False, "no-enabler-reason", "", "builtin", None, probe(), "lc-unknown|false"),
    "version-not-dotted": (False, "no-enabler-reason", "", "builtin", None, probe(version="nightly"), "lc-unknown|false"),
    "version-old": (False, "no-enabler-reason", "", "builtin", None, probe(version="3.8.0"), "lc-old|false"),
    "version-just-old": (False, "no-enabler-reason", "", "builtin", None, probe(version="3.8.12"), "lc-old|false"),
    "version-tens": (False, "no-enabler-reason", "connect-failed", "builtin", None,
                     probe(version="3.10.0", hooksActive=True, liveProcessPresent=True), "vpn-off|false"),
    "liveprocess-missing": (False, "no-enabler-reason", "", "builtin", None,
                            probe(version="3.8.13", liveProcessPresent=False, hooksActive=False), "liveprocess-missing|false"),
    "hooks-inactive": (False, "no-enabler-reason", "", "builtin", None,
                       probe(version="3.8.13", hooksActive=False, liveProcessPresent=True), "helper-setting-off|false"),
    "hooks-inactive-setting-on": (False, "no-enabler-reason", "", "builtin", None,
                                  probe(version="3.8.13", hooksActive=False, liveProcessPresent=True, helperSettingStored=True),
                                  "lc-unknown|false"),
    "hooks-inactive-setting-off": (False, "no-enabler-reason", "", "builtin", None,
                                   probe(version="3.8.13", hooksActive=False, helperSettingStored=False),
                                   "helper-setting-off|false"),
    "hooks-unreadable": (False, "no-enabler-reason", "", "builtin", None,
                         probe(version="3.8.13", liveProcessPresent=True), "lc-unknown|false"),
    "hooks-inactive-beats-pairing": (False, "pairing-absent", "pairing-rejected", "builtin", None,
                                     probe(version="3.9", hooksActive=False), "helper-setting-off|false"),
    "inside-ready-pairing-absent": (False, "pairing-absent", "", "builtin", None, probe(**READY), "pairing-absent|false"),
    "pairing-absent": (False, "pairing-absent", "", "builtin", None, probe(False), "pairing-absent|false"),
    "pairing-absent-first": (False, "pairing-absent", "connect-failed", "builtin", None, probe(False), "pairing-absent|false"),
    "pairing-rejected": (False, "no-enabler-reason", "pairing-rejected", "builtin", None, probe(False), "pairing-rejected|false"),
    "tunnel-failed": (False, "no-enabler-reason", "tunnel-failed", "builtin", None, probe(False), "pairing-rejected|false"),
    "connect-failed": (False, "no-enabler-reason", "connect-failed", "builtin", None, probe(False), "vpn-off|false"),
    "ddi-not-mounted": (False, "no-enabler-reason", "ddi-not-mounted", "builtin", None, probe(False), "ddi-not-mounted|false"),
    "start-failed": (False, "no-enabler-reason", "start-failed", "builtin", None, probe(False), "other|false"),
    "empty-word": (False, "no-enabler-reason", "", "builtin", None, probe(False), "other|false"),
    "outside-multitask-flag": (False, "no-enabler-reason", "", "builtin", None, probe(False, multitask=True), "other|false"),
}

# (version, minimum): "true", "false" or "nil"
VERSIONS = {
    ("3.8.13", "3.8.13"): "true", ("3.8.14", "3.8.13"): "true", ("3.8.12", "3.8.13"): "false",
    ("3.8", "3.8.13"): "false", ("3.8.13.1", "3.8.13"): "true", ("3.10", "3.8.13"): "true",
    ("4", "3.8.13"): "true", ("3.8.0", "3.8.13"): "false", ("", "3.8.13"): "nil", ("nightly", "3.8.13"): "nil",
    ("3..8", "3.8.13"): "nil", ("+3.8.13", "3.8.13"): "nil", ("3.8.13-nightly", "3.8.13"): "nil",
    ("3.8.13", "x"): "nil",
}

# Cases the driver sets by hand: store a verdict, then read the key back.
STORE = {
    "store-cause": "vpn-off|vpn-off",
    "store-none-removes": "nil|nil",
    "store-pre-traced": "pre-traced|pre-traced",
    "junk-reads-nil": "nil",
}

CASE_NAMES = {
    "lc-old": "lcOld", "helper-setting-off": "helperSettingOff", "liveprocess-missing": "liveProcessMissing",
    "multitask": "multitask", "lc-unknown": "lcUnknown", "pairing-absent": "pairingAbsent",
    "pairing-rejected": "pairingRejected", "vpn-off": "vpnOff", "ddi-not-mounted": "ddiNotMounted", "other": "other",
    "pre-traced": "preTraced",
}

ENDS = "StikDebug support ends in an upcoming update."
FALLBACK_TITLE = "Built-in JIT didn't start"
WARNING = "exclamationmark.triangle.fill"
SETUP = "setUpBuiltin"

# The notice bodies, word for word (inside LiveContainer 3.8.0 for lc-old), and whether the notice has an action.
BODIES = {
    "lc-old": ("This LiveContainer is 3.8.0, and built-in JIT needs 3.8.13 or later, so StikDebug turned on JIT instead. "
               "Update LiveContainer, then install ARMSX3 in it again.", "none"),
    "helper-setting-off": ("Helper Process Support is off for ARMSX3 in LiveContainer, so StikDebug turned on JIT instead. "
                           "In LiveContainer, long-press ARMSX3, open Settings and turn it on.", "none"),
    "liveprocess-missing": ("LiveContainer's LiveProcess extension is missing, so StikDebug turned on JIT instead. "
                            "Reinstall LiveContainer and keep its extensions.", "none"),
    "multitask": ("ARMSX3 was opened in a LiveContainer window, where built-in JIT can't start, so StikDebug turned on JIT "
                  "instead. Next time, open ARMSX3 full screen from LiveContainer.", "none"),
    "lc-unknown": ("Built-in JIT didn't start inside LiveContainer, so StikDebug turned on JIT instead. Check that "
                   "LiveContainer is 3.8.13 or later and Helper Process Support is on for ARMSX3.", SETUP),
    "pairing-absent": ("ARMSX3 has no pairing file, so StikDebug turned on JIT instead. Add one, and ARMSX3 turns on JIT "
                       "itself.", SETUP),
    "pairing-rejected": ("This device didn't accept the pairing file, so StikDebug turned on JIT instead. Make a new "
                         "pairing file and add it.", SETUP),
    "vpn-off": ("ARMSX3 couldn't reach this device through LocalDevVPN, so StikDebug turned on JIT instead. Turn "
                "LocalDevVPN on before you open ARMSX3.", "none"),
    "ddi-not-mounted": ("The Developer Disk Image isn't mounted, so StikDebug turned on JIT instead. Mount it, and "
                        "ARMSX3 turns on JIT itself next time.", SETUP),
    "other": ("Built-in JIT didn't finish setting up, so StikDebug turned on JIT instead. ARMSX3 tries built-in JIT again "
              "each time it opens.", "none"),
}
PRE_TRACED_INSIDE = ("LiveContainer was opened through StikDebug, so ARMSX3 didn't try built-in JIT. Open LiveContainer "
                     "from the Home Screen instead.")
PRE_TRACED_OUTSIDE = ("ARMSX3 was opened through StikDebug, so it didn't try built-in JIT. Open ARMSX3 from the Home "
                      "Screen instead.")
MIGRATED_TITLE = "ARMSX3 now turns on JIT itself"
MIGRATED_NOT_RUN = ("StikDebug is only used if built-in JIT fails. Set it up once, and ARMSX3 turns on JIT each time it "
                    "opens.")
MIGRATED_RAN = "It did this time. StikDebug is only used if built-in JIT fails."

LABEL_VERSION = "LiveContainer 3.8.13 or later"
LABEL_HELPER = "Helper Process Support on"
LABEL_FULL = "Opened full screen"
FIX_OLD = "This LiveContainer is {0}. Update it to 3.8.13 or later, then install ARMSX3 in it again."
FIX_HELPER = ("In LiveContainer, long-press ARMSX3, open Settings and turn on Helper Process Support. Then close ARMSX3 "
              "and open it again.")
FIX_FULL = ("ARMSX3 is open in a LiveContainer window, where built-in JIT can't start. Close the window and open ARMSX3 "
            "from LiveContainer's app list.")


def row(label, presence, fix=None):
    return "{0}={1}={2}".format(label, presence, fix or "nil")


# name: (probe, jitOnThroughBuiltin, rows)
CHECKLIST = {
    "outside": (probe(False, **READY), False, []),
    "outside-old": (probe(False, version="3.8.0", hooksActive=False), False, []),
    "old": (probe(version="3.8.0", hooksActive=False), False,
            [row(LABEL_VERSION, "missing", FIX_OLD.format("3.8.0")), row(LABEL_HELPER, "unknown")]),
    "hooks-inactive": (probe(version="3.8.13", hooksActive=False), False,
                       [row(LABEL_VERSION, "present"), row(LABEL_HELPER, "missing", FIX_HELPER)]),
    "ready": (probe(**READY), False, []),
    "jit-on-through-builtin": (probe(version="3.8.0", hooksActive=False), True, []),
    "multitask": (probe(multitask=True), False,
                  [row(LABEL_VERSION, "unknown"), row(LABEL_HELPER, "unknown"), row(LABEL_FULL, "missing", FIX_FULL)]),
    "multitask-ready": (probe(multitask=True, **READY), False,
                        [row(LABEL_VERSION, "present"), row(LABEL_HELPER, "present"), row(LABEL_FULL, "missing", FIX_FULL)]),
    "unreadable": (probe(), False, [row(LABEL_VERSION, "unknown"), row(LABEL_HELPER, "unknown")]),
    "hooks-unreadable": (probe(version="3.9"), False, [row(LABEL_VERSION, "present"), row(LABEL_HELPER, "unknown")]),
}

OLD_LC = probe(version="3.8.0")
OUTSIDE = probe(False)


def verdict(cause):
    return "JitFallback.Verdict(cause: {0}, otherInstall: false)".format("nil" if cause is None else "." + CASE_NAMES[cause])


def shown_set(*keys):
    return "[" + ", ".join(swift_string(k) for k in keys) + "]"


def expected_notice(key, title, body, symbol, action, marks):
    return "|".join([key, title, body, symbol, action, ",".join(marks)])


def fallback_notice(cause, marks=None, ends=False):
    body, action = BODIES[cause]
    key = cause + "+ends" if ends else cause
    return expected_notice(key, FALLBACK_TITLE, body + (" " + ENDS if ends else ""), WARNING, action, marks or [key])


MIGRATE_NOT_RUN = expected_notice("migrated", MIGRATED_TITLE, MIGRATED_NOT_RUN, "info.circle.fill", SETUP, ["migrated"])
MIGRATE_RAN = expected_notice("migrated", MIGRATED_TITLE, MIGRATED_RAN, "info.circle.fill", "none", ["migrated"])

# name: (cause or None, jitOnThroughBuiltin, migrationPending, probe, shown, supportEnds, expected or "nil")
NOTICES = {
    "vpn-off": ("vpn-off", False, False, OUTSIDE, [], False, fallback_notice("vpn-off")),
    "lc-unknown-with-migration": ("lc-unknown", False, True, probe(), [], False,
                                  fallback_notice("lc-unknown", ["lc-unknown", "migrated"])),
    "shown-cause-quiet": ("vpn-off", False, False, OUTSIDE, ["vpn-off"], False, "nil"),
    "shown-cause-then-migration": ("vpn-off", False, True, OUTSIDE, ["vpn-off"], False, MIGRATE_NOT_RUN),
    "shown-cause-then-migration-ran": ("vpn-off", True, True, OUTSIDE, ["vpn-off"], False, MIGRATE_RAN),
    "pre-traced-outside": ("pre-traced", False, False, OUTSIDE, [], False,
                           expected_notice("pre-traced", "Opened through StikDebug", PRE_TRACED_OUTSIDE, WARNING, "none",
                                           ["pre-traced"])),
    "pre-traced-inside": ("pre-traced", False, False, probe(**READY), [], False,
                          expected_notice("pre-traced", "Opened through StikDebug", PRE_TRACED_INSIDE, WARNING, "none",
                                          ["pre-traced"])),
    "pre-traced-over-migration": ("pre-traced", False, True, OUTSIDE, [], False,
                                  expected_notice("pre-traced", "Opened through StikDebug", PRE_TRACED_OUTSIDE, WARNING,
                                                  "none", ["pre-traced", "migrated"])),
    "pre-traced-shown-then-migration": ("pre-traced", True, True, OUTSIDE, ["pre-traced"], False, MIGRATE_RAN),
    "migration-not-run": (None, False, True, OUTSIDE, [], False, MIGRATE_NOT_RUN),
    "migration-ran": (None, True, True, OUTSIDE, [], False, MIGRATE_RAN),
    "migration-shown": (None, True, True, OUTSIDE, ["migrated"], False, "nil"),
    "no-migration": (None, True, False, OUTSIDE, [], False, "nil"),
    "ends-after-plain-shown": ("vpn-off", False, False, OUTSIDE, ["vpn-off"], True, fallback_notice("vpn-off", ends=True)),
    "ends-shown": ("vpn-off", False, False, OUTSIDE, ["vpn-off", "vpn-off+ends"], True, "nil"),
    "ends-not-on-pre-traced": ("pre-traced", False, False, OUTSIDE, [], True,
                               expected_notice("pre-traced", "Opened through StikDebug", PRE_TRACED_OUTSIDE, WARNING, "none",
                                               ["pre-traced"])),
    "lc-old-names-the-version": ("lc-old", False, False, OLD_LC, [], False, fallback_notice("lc-old")),
}
for _cause in BODIES:
    if _cause not in ("lc-old", "vpn-off"):
        NOTICES["cause-" + _cause] = (_cause, False, False, OUTSIDE, [], False, fallback_notice(_cause))
        NOTICES["cause-" + _cause + "-ends"] = (_cause, False, False, OUTSIDE, [], True, fallback_notice(_cause, ends=True))

# Direct noticeBody calls: (cause, insideLiveContainer, supportEnds) -> body
BODY_CALLS = {}
for _cause, (_body, _) in BODIES.items():
    BODY_CALLS[(_cause, True, False)] = _body
    BODY_CALLS[(_cause, False, True)] = _body + " " + ENDS
BODY_CALLS[("pre-traced", True, True)] = PRE_TRACED_INSIDE
BODY_CALLS[("pre-traced", False, True)] = PRE_TRACED_OUTSIDE
BODY_CALLS[("pre-traced", True, False)] = PRE_TRACED_INSIDE
BODY_CALLS[("pre-traced", False, False)] = PRE_TRACED_OUTSIDE


def driver():
    lines = [
        "import Foundation",
        'func emit(_ kind: String, _ name: String, _ value: String?) { print("\\(kind)\\t\\(name)\\t\\(value ?? "nil")") }',
        "func word(_ v: JitFallback.Verdict) -> String { \"\\(v.cause?.rawValue ?? \"none\")|\\(v.otherInstall)\" }",
        'let suite = "test.jitfallback." + UUID().uuidString',
        "let defaults = UserDefaults(suiteName: suite)!",
        "defer { defaults.removePersistentDomain(forName: suite) }",
    ]
    for name, (on, reason, helper, method, stored, p, _) in sorted(DECIDE.items()):
        lines.append('emit("decide", {0}, word(JitFallback.decide(jitOn: {1}, jitReason: {2}, helperWord: {3}, '
                     "methodAtLaunch: {4}, storedCause: {5}, probe: {6})))".format(
                         swift_string(name), swift_value(on), swift_string(reason), swift_string(helper),
                         swift_string(method), swift_value(stored), p))
    for index, (version, minimum) in enumerate(sorted(VERSIONS)):
        lines.append('emit("version", "{0}", JitFallback.versionAtLeast({1}, {2}).map {{ $0 ? "true" : "false" }})'.format(
            index, swift_string(version), swift_string(minimum)))
    lines += [
        'JitFallback.store(JitFallback.Verdict(cause: .vpnOff, otherInstall: false), in: defaults)',
        'emit("store", "store-cause", (defaults.string(forKey: JitFallback.causeKey) ?? "nil") + "|" + (JitFallback.storedCause(in: defaults)?.rawValue ?? "nil"))',
        'JitFallback.store(JitFallback.Verdict(cause: nil, otherInstall: true), in: defaults)',
        'emit("store", "store-none-removes", (defaults.string(forKey: JitFallback.causeKey) ?? "nil") + "|" + (JitFallback.storedCause(in: defaults)?.rawValue ?? "nil"))',
        'JitFallback.store(JitFallback.Verdict(cause: .preTraced, otherInstall: false), in: defaults)',
        'emit("store", "store-pre-traced", (defaults.string(forKey: JitFallback.causeKey) ?? "nil") + "|" + (JitFallback.storedCause(in: defaults)?.rawValue ?? "nil"))',
        'defaults.set("no-such-cause", forKey: JitFallback.causeKey)',
        'emit("store", "junk-reads-nil", JitFallback.storedCause(in: defaults)?.rawValue)',
        'func rows(_ r: [JitFallback.ChecklistRow]) -> String { r.map { "\\($0.label)=\\($0.presence)=\\($0.fix ?? "nil")" }.joined(separator: " ## ") }',
        'func action(_ a: JitFallback.NoticeAction?) -> String { a == nil ? "none" : "setUpBuiltin" }',
        'func shown(_ n: (notice: JitFallback.Notice, marks: [String])?) -> String {',
        '    guard let n = n else { return "nil" }',
        '    return [n.notice.key, n.notice.title, n.notice.body, n.notice.symbol, action(n.notice.action), n.marks.joined(separator: ",")].joined(separator: "|")',
        '}',
    ]
    for name, (p, on, expected) in sorted(CHECKLIST.items()):
        lines.append('emit("checklist", {0}, rows(JitFallback.checklist(probe: {1}, jitOnThroughBuiltin: {2})) )'.format(
            swift_string(name), p, swift_value(on)))
    for name, (cause, on, pending, p, shown_keys, ends, _) in sorted(NOTICES.items()):
        lines.append('emit("notice", {0}, shown(JitFallback.notice(verdict: {1}, jitOnThroughBuiltin: {2}, migrationPending: {3}, '
                     "probe: {4}, shown: Set({5}), supportEnds: {6})))".format(
                         swift_string(name), verdict(cause), swift_value(on), swift_value(pending), p, shown_set(*shown_keys),
                         swift_value(ends)))
    for index, (cause, inside, ends) in enumerate(sorted(BODY_CALLS)):
        lines.append('emit("body", "{0}", JitFallback.noticeBody(cause: .{1}, insideLiveContainer: {2}, version: "3.8.0", '
                     "supportEnds: {3}))".format(index, CASE_NAMES[cause], swift_value(inside), swift_value(ends)))
    for word in ("stikdebug", "builtin", "wait", "", "StikDebug", "stikdebug "):
        lines.append('emit("migrates", {0}, String(JitFallback.migrates(storedMethod: {0})))'.format(swift_string(word)))
    lines += [
        'JitFallback.markShown(["a", "b"], in: defaults)',
        'JitFallback.markShown(["b", "c"], in: defaults)',
        'emit("shown", "union", JitFallback.shownKeys(in: defaults).sorted().joined(separator: ","))',
        'defaults.removeObject(forKey: JitFallback.noticesKey)',
        'emit("shown", "empty", String(JitFallback.shownKeys(in: defaults).count))',
    ]
    return "\n".join(lines) + "\n"


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = Path(path).read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def run(source_text):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "JitFallback.swift"
        path.write_text(source_text, encoding="utf-8")
        return compile_and_run([path, PRESENCE], driver(), tmp)


def parse(out):
    got = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            got[(parts[0], parts[1])] = parts[2]
    return got


def fallback_problems(out):
    got = parse(out)
    problems = []

    def check(kind, name, want):
        if got.get((kind, name)) != want:
            problems.append("{0}: {1!r} gave {2!r}, wanted {3!r}".format(kind, name, got.get((kind, name)), want))

    for name, case in DECIDE.items():
        check("decide", name, case[-1])
    for index, key in enumerate(sorted(VERSIONS)):
        check("version", str(index), VERSIONS[key])
    for name, want in STORE.items():
        check("store", name, want)
    for name, (_, _, rows_) in CHECKLIST.items():
        check("checklist", name, " ## ".join(rows_))
    for name, case in NOTICES.items():
        check("notice", name, case[-1])
    for index, key in enumerate(sorted(BODY_CALLS)):
        check("body", str(index), BODY_CALLS[key])
    for word, want in (("stikdebug", "true"), ("builtin", "false"), ("wait", "false"), ("", "false"),
                       ("StikDebug", "false"), ("stikdebug ", "false")):
        check("migrates", word, want)
    check("shown", "union", "a,b,c")
    check("shown", "empty", "0")
    return problems


class RealFiles(unittest.TestCase):
    def test_the_real_files_have_no_problem(self):
        out = run(read(FALLBACK))
        sys.stderr.write("\n" + "".join(l + "\n" for l in out.splitlines() if l.startswith("decide\t")))
        self.assertEqual(fallback_problems(out), [])

    def test_control_the_table_covers_every_cause(self):
        causes = {case[-1].split("|")[0] for case in DECIDE.values()}
        self.assertEqual(causes - {"none"}, {"lc-old", "helper-setting-off", "liveprocess-missing", "multitask", "lc-unknown",
                                             "pairing-absent", "pairing-rejected", "vpn-off", "ddi-not-mounted", "other",
                                             "pre-traced"})

    def test_the_keys_and_the_switch_are_as_pinned(self):
        text = read(FALLBACK)
        self.assertIn('static let causeKey = "armsx3.jit.fallbackCause"', text)
        self.assertIn('static let noticesKey = "armsx3.jit.noticesShown"', text)
        self.assertEqual(text.count("supportEndsAnnounced = false"), 1)

    def test_control_every_fallback_cause_has_a_body_and_both_wordings_are_pinned(self):
        self.assertEqual(len(BODIES), 10)
        self.assertEqual({k[0] for k in BODY_CALLS}, set(BODIES) | {"pre-traced"})
        self.assertNotEqual(PRE_TRACED_INSIDE, PRE_TRACED_OUTSIDE)

    def test_no_row_or_notice_opens_livecontainer_settings(self):
        self.assertNotIn("Open LiveContainer Settings", read(FALLBACK))

    def test_the_support_ends_sentence_is_in_the_source_and_off(self):
        text = read(FALLBACK)
        self.assertIn(ENDS, text)
        self.assertIn('"+ends"', text)
        self.assertEqual(text.count("supportEndsAnnounced = false"), 1)

    def test_foundation_is_the_only_import(self):
        self.assertEqual(re.findall(r"^import .*$", read(FALLBACK), re.M), ["import Foundation"])

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
        cls.text = read(FALLBACK)

    def edited(self, old, new):
        self.assertIn(old, self.text, "the mutation's anchor moved")
        return self.text.replace(old, new, 1)

    def assert_caught(self, text, prefix):
        problems = fallback_problems(run(text))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_wait_is_a_cause(self):
        self.assert_caught(self.edited('if methodAtLaunch == "wait" { return none }', ""), "decide:")

    def test_mutation_helper_ok_in_lc_is_not_other_install(self):
        self.assert_caught(self.edited("probe.insideLiveContainer && probe.hooksActive == false", "false"), "decide:")

    def test_mutation_multitask_after_the_bundle_reads(self):
        old = "            if probe.multitask { return .multitask }\n"
        moved = self.edited(old, "").replace("            if !passes { return .lcOld }\n",
                                             "            if !passes { return .lcOld }\n" + old, 1)
        self.assert_caught(moved, "decide:")

    def test_mutation_version_compared_as_text(self):
        self.assert_caught(self.edited("return !have.lexicographicallyPrecedes(want)", "return version >= minimum"), "version:")

    def test_mutation_stored_setting_ignored(self):
        self.assert_caught(self.edited("probe.helperSettingStored == true ? .lcUnknown : .helperSettingOff",
                                       ".helperSettingOff"), "decide:")

    def test_mutation_store_keeps_the_old_cause(self):
        self.assert_caught(self.edited("defaults.removeObject(forKey: causeKey)", "_ = 0"), "store:")

    def test_mutation_support_ends_key_is_the_plain_cause(self):
        self.assert_caught(self.edited('fallback && supportEnds ? cause.rawValue + "+ends" : cause.rawValue', "cause.rawValue"),
                           "notice:")

    def test_mutation_shown_cause_comes_back(self):
        self.assert_caught(self.edited("if !shown.contains(key) {", "if true {"), "notice:")

    def test_mutation_migration_shown_twice(self):
        self.assert_caught(self.edited('guard migrationPending, !shown.contains("migrated") else', "guard migrationPending else"),
                           "notice:")

    def test_mutation_pre_traced_gets_the_ends_key(self):
        self.assert_caught(self.edited("let key = fallback && supportEnds", "let key = supportEnds"), "notice:")

    def test_mutation_migration_variant_swapped(self):
        self.assert_caught(self.edited("let notice = jitOnThroughBuiltin\n", "let notice = !jitOnThroughBuiltin\n"), "notice:")

    def test_mutation_vpn_gets_an_action(self):
        self.assert_caught(self.edited("case .lcUnknown, .pairingAbsent,", "case .vpnOff, .lcUnknown, .pairingAbsent,"),
                           "notice:")

    def test_mutation_version_row_loses_the_version(self):
        self.assert_caught(self.edited('This LiveContainer is \\(probe.version ?? ""). Update', "This LiveContainer is old. Update"),
                           "checklist:")

    def test_mutation_ready_rows_stay(self):
        self.assert_caught(self.edited("if probe.hooksActive == true && versionPasses == true && !probe.multitask { return [] }", ""),
                           "checklist:")

    def test_mutation_migrates_any_word(self):
        self.assert_caught(self.edited('storedMethod == "stikdebug"', 'storedMethod != ""'), "migrates:")


PROBE_HEAD = """import Foundation
func emit(_ name: String, _ value: String) { print("\\(name)\\t\\(value)") }
func flag(_ value: Bool?) -> String { value.map { String($0) } ?? "nil" }
func text(_ p: JitFallback.Probe) -> String {
    [String(p.insideLiveContainer), String(p.multitask), p.version ?? "nil", flag(p.hooksActive), flag(p.liveProcessPresent),
     flag(p.helperSettingStored), p.bundleID ?? "nil"].joined(separator: "|")
}
func core(_ p: JitFallback.Probe) -> String {
    [String(p.insideLiveContainer), String(p.multitask), p.version ?? "nil", flag(p.liveProcessPresent),
     p.bundleID ?? "nil"].joined(separator: "|")
}
"""

# Without the LiveContainer selectors, which is every Mac and every player outside LiveContainer.
PROBE_PLAIN = PROBE_HEAD + """
unsetenv("LC_HOME_PATH")
unsetenv("LP_HOME_PATH")
emit("outside", text(LiveContainerProbe.read()))
setenv("LC_HOME_PATH", "/stand-in", 1)
let inside = LiveContainerProbe.read()
emit("inside", core(inside))
let verdict = JitFallback.decide(jitOn: false, jitReason: "no-enabler-reason", helperWord: "", methodAtLaunch: "builtin",
                                 storedCause: nil, probe: inside)
emit("inside-cause", verdict.cause?.rawValue ?? "none")
setenv("LP_HOME_PATH", "/stand-in", 1)
emit("window", text(LiveContainerProbe.read()))
MainActor.assumeIsolated {
    LiveContainerProbe.current = JitFallback.Probe(insideLiveContainer: true)
    emit("bundle-id", "\\(LiveContainerProbe.stikDebugBundleID)|\\(Bundle.main.bundleIdentifier ?? "")")
}
LiveContainerProbe.selectForRelaunch()
emit("relaunch", UserDefaults.standard.string(forKey: "selected") ?? "nil")
"""

# With stand-ins for the two selectors and a LiveContainer bundle made in a temporary folder.
PROBE_STAND_IN = PROBE_HEAD + """import ObjectiveC
var standInBundle: Bundle? = nil
var bundleReads = 0
let selection = UserDefaults(suiteName: "test.jitprobe." + UUID().uuidString)!
extension UserDefaults {
    @objc class func lcMainBundle() -> Bundle? { bundleReads += 1; return standInBundle }
    @objc class func lcUserDefaults() -> UserDefaults { selection }
}
func hooks() -> String { flag(LiveContainerProbe.read().hooksActive) }

unsetenv("LP_HOME_PATH")
setenv("LC_HOME_PATH", "/stand-in", 1)
standInBundle = Bundle(path: GOOD)!
emit("good", core(LiveContainerProbe.read()))
MainActor.assumeIsolated {
    LiveContainerProbe.current = LiveContainerProbe.read()
    emit("bundle-id", LiveContainerProbe.stikDebugBundleID)
}
standInBundle = Bundle(path: BARE)!
emit("no-extension", core(LiveContainerProbe.read()))
standInBundle = nil
emit("unreadable", core(LiveContainerProbe.read()))
setenv("HOME", "/stand-in/Containers/CONT-1234", 1)
LiveContainerProbe.selectForRelaunch()
emit("relaunch", "\\(selection.string(forKey: "selected") ?? "nil")|\\(selection.string(forKey: "selectedContainer") ?? "nil")|"
     + (Bundle.main.bundlePath as NSString).lastPathComponent)
emit("hooks-before", hooks())
if let cls = NSClassFromString("NSExtension"),
   let method = class_getClassMethod(cls, NSSelectorFromString("extensionWithIdentifier:excludingDisabledExtensions:error:")) {
    let replacement: @convention(c) (AnyObject, Selector, AnyObject?, Bool, AnyObject?) -> AnyObject? = { _, _, _, _, _ in nil }
    method_setImplementation(method, unsafeBitCast(replacement, to: IMP.self))
}
emit("hooks-after", hooks())
let before = bundleReads
setenv("LP_HOME_PATH", "/stand-in", 1)
standInBundle = Bundle(path: GOOD)!
emit("window", text(LiveContainerProbe.read()))
emit("window-reads", String(bundleReads - before))
"""


def make_bundle(root, name, version, with_extension):
    """A macOS-layout stand-in for LiveContainer.app, with or without its LiveProcess extension."""
    contents = Path(root) / (name + ".app") / "Contents"
    contents.mkdir(parents=True)
    with open(contents / "Info.plist", "wb") as f:
        plistlib.dump({"CFBundleIdentifier": "com.example.lc", "CFBundleShortVersionString": version,
                       "CFBundleName": name}, f)
    if with_extension:
        appex = contents / "PlugIns" / "LiveProcess.appex" / "Contents"
        appex.mkdir(parents=True)
        with open(appex / "Info.plist", "wb") as f:
            plistlib.dump({"CFBundleIdentifier": "com.example.lc.LiveProcess"}, f)
    return str(contents.parent)


def run_probe(source_text, driver_text, tmp):
    probe_path = Path(tmp) / "LiveContainerProbe.swift"
    probe_path.write_text(source_text, encoding="utf-8")
    return parse_lines(compile_and_run([probe_path, FALLBACK, PRESENCE], driver_text, tmp))


def parse_lines(out):
    got = {}
    for line in out.splitlines():
        name, _, value = line.partition("\t")
        got[name] = value
    return got


def probe_problems(plain, stand_in):
    problems = []

    def check(got, name, want):
        if got.get(name) != want:
            problems.append("probe: {0!r} gave {1!r}, wanted {2!r}".format(name, got.get(name), want))

    check(plain, "outside", "false|false|nil|nil|nil|nil|nil")
    check(plain, "inside", "true|false|nil|nil|nil")
    check(plain, "inside-cause", "lc-unknown")
    check(plain, "window", "true|true|nil|nil|nil|nil|nil")
    check(plain, "relaunch", "nil")
    shown, _, main = plain.get("bundle-id", "|").partition("|")
    if shown != main:
        problems.append("probe: the bundle id fell back to {0!r}, not Bundle.main's {1!r}".format(shown, main))
    check(stand_in, "good", "true|false|3.8.13|true|com.example.lc")
    check(stand_in, "bundle-id", "com.example.lc")
    check(stand_in, "no-extension", "true|false|3.8.0|false|com.example.lc")
    check(stand_in, "unreadable", "true|false|nil|nil|nil")
    check(stand_in, "window", "true|true|nil|nil|nil|nil|nil")
    check(stand_in, "window-reads", "0")
    selected, container, last = (stand_in.get("relaunch", "||").split("|") + ["", "", ""])[:3]
    if not (selected and selected == last and container == "CONT-1234"):
        problems.append("probe: relaunch selected {0!r} and {1!r} for the app folder {2!r}".format(selected, container, last))
    before, after = stand_in.get("hooks-before"), stand_in.get("hooks-after")
    # A host without NSExtension reads nil both times; one that has it reads the real method as unhooked.
    if before != "nil" and (before, after) != ("false", "true"):
        problems.append("probe: hooks read {0!r} then {1!r}, wanted false then true".format(before, after))
    if before == "nil" and after != "nil":
        problems.append("probe: hooks read nil then {0!r}".format(after))
    return problems


def run_both(source_text):
    with tempfile.TemporaryDirectory() as tmp_plain, tempfile.TemporaryDirectory() as tmp_fake, \
            tempfile.TemporaryDirectory() as tmp_build:
        plain = run_probe(source_text, PROBE_PLAIN, tmp_plain)
        good = make_bundle(tmp_fake, "Good", "3.8.13", True)
        bare = make_bundle(tmp_fake, "Bare", "3.8.0", False)
        stand_in = run_probe(source_text, PROBE_STAND_IN.replace("GOOD", '"{0}"'.format(good)).replace("BARE", '"{0}"'.format(bare)),
                             tmp_build)
    return plain, stand_in


class Probe(unittest.TestCase):
    def test_the_real_probe_reads_what_it_can_and_nothing_else(self):
        plain, stand_in = run_both(read(PROBE_SOURCE))
        sys.stderr.write("\n" + "".join("{0}\t{1}\n".format(k, v) for k, v in sorted({**plain, **stand_in}.items())))
        self.assertEqual(probe_problems(plain, stand_in), [])

    def test_every_private_selector_is_asked_before_it_is_used(self):
        text = read(PROBE_SOURCE)
        declared = re.findall(r"let (\w+) = NSSelectorFromString\(", text)
        self.assertEqual(len(declared), len(re.findall(r"NSSelectorFromString\(", text)), "a selector is used without a name")
        self.assertEqual(len(declared), 3)
        for match in re.finditer(r"let (\w+) = NSSelectorFromString\(", text):
            name = match.group(1)
            after = text[match.end():]
            first_use = re.search(r"\b" + name + r"\b", after)
            self.assertIsNotNone(first_use, name)
            self.assertTrue(after[:first_use.start()].endswith("responds(to: "), "{0} is used before responds(to:)".format(name))

    def test_the_probe_compares_image_bases_and_never_image_paths(self):
        text = read(PROBE_SOURCE)
        self.assertIn("dli_fbase", text)
        self.assertNotIn("dli_fname", text)

    def test_a_window_is_read_before_the_bundle_is_touched(self):
        text = read(PROBE_SOURCE)
        self.assertIn("LP_HOME_PATH", text)
        self.assertLess(text.index("LP_HOME_PATH"), text.index("liveContainerBundle()", text.index("static func read")))

    def test_the_probe_logs_nothing_and_moves_nothing(self):
        text = read(PROBE_SOURCE)
        for banned in (r"\bprint\s*\(", r"\bNSLog\s*\(", r"\bos_log\s*\(", r"\bdebugPrint\s*\(", r"\bLogger\b",
                       r"removeItem", r"moveItem", r"\.write\(", r"createFile"):
            self.assertIsNone(re.search(banned, text), banned)
        self.assertEqual(sorted(set(re.findall(r"^import (\w+)$", text, re.M))), ["Darwin", "Foundation", "ObjectiveC"])

    def test_the_two_files_log_nothing(self):
        for path in (PROBE_SOURCE, FALLBACK):
            self.assertIsNone(re.search(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b", read(path)), str(path))


class ProbeMutations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read(PROBE_SOURCE)

    def assert_caught(self, old, new):
        self.assertIn(old, self.text, "the mutation's anchor moved")
        plain, stand_in = run_both(self.text.replace(old, new, 1))
        self.assertTrue(probe_problems(plain, stand_in))

    def test_mutation_window_reads_the_bundle(self):
        self.assert_caught('if getenv("LP_HOME_PATH") != nil { return JitFallback.Probe(insideLiveContainer: true, multitask: true) }',
                           "")

    def test_mutation_hooks_compare_the_same_image(self):
        self.assert_caught("implementationInfo.dli_fbase != classInfo.dli_fbase", "implementationInfo.dli_fbase == classInfo.dli_fbase")

    def test_mutation_bundle_id_ignores_livecontainer(self):
        self.assert_caught("current.bundleID ?? Bundle.main.bundleIdentifier ?? \"\"", "Bundle.main.bundleIdentifier ?? \"\"")

    def test_mutation_container_taken_from_the_app_folder(self):
        self.assert_caught('(home as NSString).lastPathComponent, forKey: "selectedContainer"',
                           '(Bundle.main.bundlePath as NSString).lastPathComponent, forKey: "selectedContainer"')


if __name__ == "__main__":
    unittest.main()
