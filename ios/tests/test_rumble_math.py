#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RumbleMath.swift, run alone on the Mac host, plays each motor byte linearly times the player's strength, sends only
changes and one stop, and gives the phone to player 1 alone; static checks pin RumbleFeed's pause, stop, route,
reset, shared phone engine and retry, and the recording haptics allowance. It says nothing about real haptics."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from source_helpers import brace_span

IOS = Path(__file__).resolve().parents[1]
MATH = IOS / "app" / "Controllers" / "RumbleMath.swift"
FEED = IOS / "app" / "Controllers" / "RumbleFeed.swift"
POLLER = IOS / "app" / "Controllers" / "GamepadPoller.swift"
AUDIO = IOS / "app" / "Support" / "ios_audio_session.mm"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
STOP_ALL_DEF = re.compile(r"\bfunc\s+stopAll\s*\(\s*\)\s*\{")
STOP_ALL_CALL = "RumbleFeed.shared.stopAll()"
# The pause silences and keeps the engines.
PAUSE_ALL_DEF = re.compile(r"\bfunc\s+pauseAll\s*\(\s*\)\s*\{")
PAUSE_ALL_CALL = "RumbleFeed.shared.pauseAll()"
OWNER_GUARD = "guard EmulatorSession.shared.inputOwner == .game else {"
# The route change.
ROUTE_CHANGE = re.compile(r"if\s+route\s*!=\s*routes\s*\[\s*slot\s*\]\s*\{")
STOPPED = re.compile(r"\bengine\.stoppedHandler\s*=")
RESET = re.compile(r"\bengine\.resetHandler\s*=")
# The phone stand-in.
PHONE_ENGINE = "CHHapticEngine()"
PHONE_MAKER_CALL = re.compile(r"(?<!func )\bmakePhoneTarget\(")
PHONE_CASE_LINE = re.compile(r"^[^\n]*\bcase\s+\.phone\s*:[^\n]*\bmakePhoneTarget\(", re.M)
CAPABILITY = re.compile(r"\bphoneHasHaptics\s*=\s*CHHapticEngine\.capabilitiesForHardware\(\)\.supportsHaptics\b")
NOT_PHONE = re.compile(r"\bfor\s+engine\s+in\s+\w+\.engines\s+where\s+engine\s*!==\s*phone\s*\{")
# The linear motor expressions.
LINEAR_LARGE = "Float(large) / 255 * strength"
LINEAR_SMALL = "Float(small) / 255 * strength"
CURVE_NAMES = re.compile(r"\b(threshold|largeIntensity|smallIntensity)\b")
ALLOW = re.compile(r"\bif\s*\(\s*on\b[^{;]*\[session\(\)\s+setAllowHapticsAndSystemSoundsDuringRecording:YES\b")

DRIVER = r'''
import Foundation

func f(_ v: Float) -> String { String(format: "%.2f", v) }

func m(_ l: UInt8, _ s: UInt8) -> RumbleMath.Motors { RumbleMath.motors(large: l, small: s, strengthPercent: 100, enabled: true) }
print("R1 large63=\(f(m(63, 0).large)) large128=\(f(m(128, 0).large))")
let full = m(255, 255)
print("R2 full=\(f(full.large)),\(f(full.small))")
print("R3 small0=\(f(m(0, 0).small)) small64=\(f(m(0, 64).small)) small255=\(f(m(0, 255).small))")
print("R4 strength50=\(f(RumbleMath.motors(large: 255, small: 0, strengthPercent: 50, enabled: true).large))")
let off = RumbleMath.motors(large: 255, small: 1, strengthPercent: 100, enabled: false)
print("R5 off=\(f(off.large)),\(f(off.small))")
print("R6 single=\(f(RumbleMath.single(m(64, 255))))")
var gate = RumbleMath.ChangeGate()
let a = RumbleMath.motors(large: 200, small: 0, strengthPercent: 100, enabled: true)
let b = RumbleMath.motors(large: 200, small: 1, strengthPercent: 100, enabled: true)
var sends = 0
for m in [a, a, a, b] where gate.update(m) { sends += 1 }
print("R7 sends=\(sends)")
let zero = RumbleMath.motors(large: 0, small: 0, strengthPercent: 100, enabled: true)
var stopGate = RumbleMath.ChangeGate()
var zeroSends = 0
for m in [zero, zero, a, zero, zero, zero] where stopGate.update(m) && m == zero { zeroSends += 1 }
print("R8 stop=\(f(zero.large)),\(f(zero.small)) sends=\(zeroSends)")
var phoneFor2to4 = 0
for slot in 1...3 {
    for c in [false, true] {
        for h in [false, true] {
            for p in [false, true] where RumbleMath.target(slot: slot, hasController: c, controllerHasHaptics: h, phoneHasHaptics: p) == .phone {
                phoneFor2to4 += 1
            }
        }
    }
}
print("R9 phone_for_2to4=\(phoneFor2to4)")
let touch = RumbleMath.target(slot: 0, hasController: false, controllerHasHaptics: false, phoneHasHaptics: true)
let motorless = RumbleMath.target(slot: 0, hasController: true, controllerHasHaptics: false, phoneHasHaptics: true)
let haptic = RumbleMath.target(slot: 0, hasController: true, controllerHasHaptics: true, phoneHasHaptics: true)
let nophone = RumbleMath.target(slot: 0, hasController: false, controllerHasHaptics: false, phoneHasHaptics: false)
print("R10 touch=\(touch) motorless=\(motorless) haptic=\(haptic) nophone=\(nophone)")
'''


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def without_comments(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", source)


def math_problems(math):
    problems = []
    imports = IMPORT.findall(without_comments(math))
    if imports != ["Foundation"]:
        problems.append("pure: RumbleMath.swift imports {0}, not Foundation alone".format(imports))
    # The bytes arrive converted, so Swift is linear.
    code = without_comments(math)
    if code.count(LINEAR_LARGE) != 1 or code.count(LINEAR_SMALL) != 1 or CURVE_NAMES.search(code):
        problems.append("linear: RumbleMath.swift does not play each byte as byte / 255 * strength, or keeps a curve")
    return problems


def block_after(code, match_end):
    """The brace-matched block whose `{` ends the match at `match_end`, or ""."""
    return code[slice(*brace_span(code, match_end - 1))] if match_end else ""


def body(code, pattern):
    match = re.search(pattern, code)
    return block_after(code, match.end()) if match else ""


def feed_problems(feed, poller):
    """The stop and route checks, on comment-stripped text."""
    feed = without_comments(feed)
    poller = without_comments(poller)
    problems = []

    owner_at = poller.find(OWNER_GUARD)
    owner_else = block_after(poller, owner_at + len(OWNER_GUARD)) if owner_at != -1 else ""
    stop_body = body(poller, r"\bfunc\s+stop\s*\(\s*\)\s*\{")
    # The pause silences and keeps the engines; the stop still forgets them.
    pause = body(feed, r"\bfunc\s+pauseAll\s*\(\s*\)\s*\{")
    silence = body(feed, r"\bfunc\s+silence\s*\([^{]*\{")
    if not STOP_ALL_DEF.search(feed) or not PAUSE_ALL_DEF.search(feed) or PAUSE_ALL_CALL not in owner_else \
            or STOP_ALL_CALL in owner_else or STOP_ALL_CALL not in stop_body \
            or "generation &+= 1" not in pause or "silence(slot)" not in pause \
            or "gates[slot] = RumbleMath.ChangeGate()" not in silence or "player.stop(" not in silence \
            or "engine.stop(" in silence or "targets[slot] = nil" in silence:
        problems.append("stopall: RumbleFeed has no stopAll(), or the poller does not call it when the game does not own "
                        "input and from stop()")

    apply = body(feed, r"\bfunc\s+apply\s*\([^{]*\{")
    change = ROUTE_CHANGE.search(apply)
    branch = block_after(apply, change.end()) if change else ""
    dropped, stored = branch.find("drop(slot)"), branch.find("routes[slot] = route")
    if dropped < 0 or stored < 0 or dropped > stored:
        problems.append("gone: a slot whose controller left (its target changed) does not drop(slot) its engines first")

    make = body(feed, r"\bfunc\s+makeTarget\s*\([^{]*\{")
    handlers = [STOPPED.search(make), RESET.search(make)]
    if any(h is None for h in handlers) \
            or any("markStale(" not in block_after(make, make.index("{", h.end()) + 1) for h in handlers if h):
        problems.append("reset: an engine does not set both stoppedHandler and resetHandler to closures that markStale(")
    return problems


def phone_problems(feed, audio):
    """The phone stand-in checks, on comment-stripped text."""
    feed = without_comments(feed)
    problems = []
    maker = body(feed, r"\bfunc\s+makePhoneTarget\s*\([^{]*\{")
    calls = PHONE_MAKER_CALL.findall(feed)
    if not maker or feed.count(PHONE_ENGINE) != maker.count(PHONE_ENGINE) or maker.count(PHONE_ENGINE) < 1 \
            or not calls or len(PHONE_CASE_LINE.findall(feed)) != len(calls):
        problems.append("phoneonly0: the phone engine is made outside makePhoneTarget, or makePhoneTarget is reached "
                        "other than from a `case .phone:` line")
    guard = maker.find("guard phoneHasHaptics")
    if not CAPABILITY.search(feed) or guard < 0 or guard > maker.find(PHONE_ENGINE):
        problems.append("capability: the phone engine is not made only after "
                        "CHHapticEngine.capabilitiesForHardware().supportsHaptics")
    # Slot 0 and Test Rumble share the phone engine, so neither may stop it.
    drop, end = body(feed, r"\bfunc\s+drop\s*\([^{]*\{"), body(feed, r"\bfunc\s+endTest\s*\([^{]*\{")
    make = body(feed, r"\bfunc\s+makeTarget\s*\([^{]*\{")
    stops = [body_ for body_ in (drop, end) if ".stop(completionHandler" in body_]
    if len(stops) != 2 or any(not NOT_PHONE.search(b) or b.count(".stop(completionHandler") != 1 for b in stops) \
            or not re.search(r"if\s+owner\s*!=\s*nil\s*\{\s*for\s+engine\s+in\s+target\.engines\s*\{\s*engine\.stop\(", make) \
            or "isAutoShutdownEnabled = true" not in maker:
        problems.append("sharedphone: an owner of the shared phone engine can stop it under the other")
    # A failed send resets the gate so the next poll tries again.
    apply_fn, send_fn = body(feed, r"\bfunc\s+apply\s*\([^{]*\{"), body(feed, r"\bfunc\s+send\s*\([^{]*\{")
    if not re.search(r"if\s+!send\([^)]*\)\s*\{\s*gates\[slot\]\s*=\s*RumbleMath\.ChangeGate\(\)\s*\}", apply_fn) \
            or "guard let target = targets[slot] else { return false }" not in send_fn \
            or "played = played && target.playing[index]" not in send_fn or "return played" not in send_fn:
        problems.append("retry: a send that played nothing leaves the gate refusing the same value")
    switch = body(without_comments(audio), r"\bint\s+armsx3_audio_session_set_recording\s*\(\s*int\s+on\s*\)\s*\{")
    allow = ALLOW.search(switch)
    if not allow:
        problems.append("allowhaptics: the play-and-record branch does not allow haptics while recording")
    # A refusal is a warning, never a session failure.
    refused = block_after(switch, re.compile(r"\{").search(switch, allow.end()).end()) if allow else ""
    warn = body(without_comments(audio), r"\bvoid\s+warn_ns_error_locked\s*\([^{]*\{")
    if not allow or "warn_ns_error_locked(" not in refused or "record_ns_error_locked(" in refused \
            or "set_error_locked(" in refused or not warn or re.search(r"\bg_(state|code|error)\b", warn):
        problems.append("allowhaptics: a refused haptics allowance marks the audio session FAILED")
    return problems


class TheRumbleHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(MATH)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([MATH], DRIVER, tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}
        sys.stderr.write("\n" + "\n".join(cls.lines[k] for k in sorted(cls.lines)) + "\n")

    def check(self, key, expected):
        self.assertEqual(expected, self.lines.get(key), key)

    def test_r1_the_large_motor_is_linear(self):
        self.check("R1", "R1 large63=0.25 large128=0.50")

    def test_r2_the_top_is_full(self):
        self.check("R2", "R2 full=1.00,1.00")

    def test_r3_the_small_motor_is_linear(self):
        self.check("R3", "R3 small0=0.00 small64=0.25 small255=1.00")

    def test_r4_strength_scales(self):
        self.check("R4", "R4 strength50=0.50")

    def test_r5_a_disabled_player_is_silent(self):
        self.check("R5", "R5 off=0.00,0.00")

    def test_r6_one_engine_plays_the_max(self):
        self.check("R6", "R6 single=1.00")

    def test_r7_only_a_change_is_sent(self):
        self.check("R7", "R7 sends=2")

    def test_r8_a_stop_is_sent_once(self):
        self.check("R8", "R8 stop=0.00,0.00 sends=1")

    def test_r9_players_two_to_four_never_reach_the_phone(self):
        self.check("R9", "R9 phone_for_2to4=0")

    def test_r10_player_one_reaches_the_phone_on_touch_or_a_motorless_controller(self):
        self.check("R10", "R10 touch=phone motorless=phone haptic=controller nophone=none")


class RumbleStatic(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], math_problems(read(MATH)))

    def test_real_feed_and_poller_have_no_problems(self):
        self.assertEqual([], feed_problems(read(FEED), read(POLLER)))

    def test_real_phone_stand_in_has_no_problems(self):
        self.assertEqual([], phone_problems(read(FEED), read(AUDIO)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.swift"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(OSError):
                read(Path(tmp) / "missing.swift")


class RumbleStaticMutations(unittest.TestCase):
    """Each method makes ONE edit to the real text and shows the checker fail."""

    def edited(self, text, old, new):
        self.assertTrue(old in text, "the mutation's target text {0!r} is not in the file".format(old))
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def assertFlagged(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_pure_imports_corehaptics(self):
        text = self.edited(read(MATH), "import Foundation", "import Foundation\nimport CoreHaptics")
        self.assertFlagged(math_problems(text), "pure")

    def test_mutation_linear_small_back_to_on_off(self):
        text = self.edited(read(MATH), "Float(small) / 255 * strength", "(small > 0 ? 1 : 0) * strength")
        self.assertFlagged(math_problems(text), "linear")

    def test_mutation_linear_threshold_restored(self):
        text = self.edited(read(MATH), "enum RumbleMath {", "enum RumbleMath {\n    static let threshold: UInt8 = 63")
        self.assertFlagged(math_problems(text), "linear")

    def test_mutation_stopall_not_called_from_stop(self):
        poller = read(POLLER)
        stop = body(poller, r"\bfunc\s+stop\s*\(\s*\)\s*\{")
        self.assertIn(STOP_ALL_CALL, stop)
        text = self.edited(poller, stop, stop.replace(STOP_ALL_CALL, "", 1))
        self.assertFlagged(feed_problems(read(FEED), text), "stopall")

    def test_mutation_stopall_pause_drops_the_engines_again(self):
        poller = read(POLLER)
        text = self.edited(poller, "            " + PAUSE_ALL_CALL, "            " + STOP_ALL_CALL)
        self.assertFlagged(feed_problems(read(FEED), text), "stopall")

    def test_mutation_stopall_pause_leaves_a_player_playing(self):
        feed = without_comments(read(FEED))
        silence = body(feed, r"\bfunc\s+silence\s*\([^{]*\{")
        text = self.edited(feed, silence, silence.replace("try? player.stop(atTime: CHHapticTimeImmediate)", "", 1))
        self.assertFlagged(feed_problems(text, read(POLLER)), "stopall")

    def test_mutation_gone_slot_keeps_its_engines(self):
        # Comment-stripped first, as feed_problems reads it.
        feed = without_comments(read(FEED))
        apply = body(feed, r"\bfunc\s+apply\s*\([^{]*\{")
        change = ROUTE_CHANGE.search(apply)
        self.assertIsNotNone(change)
        branch = block_after(apply, change.end())
        text = self.edited(feed, apply, apply.replace(branch, branch.replace("drop(slot)", "", 1), 1))
        self.assertFlagged(feed_problems(text, read(POLLER)), "gone")

    def test_mutation_reset_handler_dropped(self):
        feed = read(FEED)
        text = self.edited(feed, "engine.resetHandler =", "_ =")
        self.assertFlagged(feed_problems(text, read(POLLER)), "reset")

    def test_mutation_phoneonly0_any_target_reaches_the_phone(self):
        feed = without_comments(read(FEED))
        text = self.edited(feed, "case .phone:", "default:")
        self.assertFlagged(phone_problems(text, read(AUDIO)), "phoneonly0")

    def test_mutation_capability_the_phone_engine_is_made_unchecked(self):
        feed = without_comments(read(FEED))
        text = self.edited(feed, "guard phoneHasHaptics", "guard true")
        self.assertFlagged(phone_problems(text, read(AUDIO)), "capability")

    def test_mutation_sharedphone_drop_stops_the_phone(self):
        feed = without_comments(read(FEED))
        drop = body(feed, r"\bfunc\s+drop\s*\([^{]*\{")
        text = self.edited(feed, drop, drop.replace(" where engine !== phone", "", 1))
        self.assertFlagged(phone_problems(text, read(AUDIO)), "sharedphone")

    def test_mutation_sharedphone_never_idles_out(self):
        feed = without_comments(read(FEED))
        text = self.edited(feed, "isAutoShutdownEnabled = true", "isAutoShutdownEnabled = false")
        self.assertFlagged(phone_problems(text, read(AUDIO)), "sharedphone")

    def test_mutation_retry_failed_send_keeps_the_gate(self):
        feed = without_comments(read(FEED))
        text = self.edited(feed, "{ gates[slot] = RumbleMath.ChangeGate() }", "{ }")
        self.assertFlagged(phone_problems(text, read(AUDIO)), "retry")

    def test_mutation_allowhaptics_recording_mutes_the_phone(self):
        text = self.edited(read(AUDIO), "setAllowHapticsAndSystemSoundsDuringRecording:YES",
                           "setAllowHapticsAndSystemSoundsDuringRecording:NO")
        self.assertFlagged(phone_problems(read(FEED), text), "allowhaptics")

    def test_mutation_allowhaptics_refusal_fails_the_session(self):
        text = self.edited(read(AUDIO), "warn_ns_error_locked(err, \"set_recording: allowing",
                           "record_ns_error_locked(err, \"set_recording: allowing")
        self.assertFlagged(phone_problems(read(FEED), text), "allowhaptics")

    def test_mutation_allowhaptics_warning_sets_failed(self):
        text = self.edited(read(AUDIO), "\t\tarmsx3_stderr_witness_printf(\"@@AUDIO@@ warning %s",
                           "\t\tg_state = ARMSX3_AUDIO_SESSION_FAILED;\n\t\tarmsx3_stderr_witness_printf(\"@@AUDIO@@ warning %s")
        self.assertFlagged(phone_problems(read(FEED), text), "allowhaptics")


if __name__ == "__main__":
    unittest.main()
