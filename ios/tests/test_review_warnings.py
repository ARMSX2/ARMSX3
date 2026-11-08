# SPDX-License-Identifier: GPL-2.0-only
"""Each fixed review warning holds on the comment-stripped text: audio deactivation, pad touch cancellation, the
deferred pause (also run on the host), readable folders, player 1's pad hiding, the audio default, the staged
firmware install, library refresh ordering and frame counters. It reads text and proves nothing about iOS."""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import abi_text, brace_span, facade_text, without_comments

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
INPUTS = {
    "session": APP / "Game" / "EmulatorSession.swift",
    "facade": IOS / "app" / "Bridge",
    "audio": IOS / "app" / "Support" / "ios_audio_session.mm",
    "pad": APP / "Controllers" / "VirtualControllerView.swift",
    "screen": APP / "Game" / "GameScreenView.swift",
    "frame": IOS / "bridge" / "ios_frame.mm",
    "pause": IOS / "bridge" / "lifecycle_pause.h",
    "store": APP / "Library" / "BookmarkStore.swift",
    "poller": APP / "Controllers" / "GamepadPoller.swift",
    "render": IOS / "core" / "render" / "ios_render.cpp",
    "abi": IOS / "bridge",
    "model": APP / "Library" / "LibraryModel.swift",
}


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def body(text, pattern):
    """The brace block of the first definition matching `pattern`, or "" when there is none."""
    match = re.search(pattern, text)
    return text[slice(*brace_span(text, match.end()))] if match else ""


def wr04_problems(session, facade, audio):
    problems = []
    method = body(facade, r"\+\s*\(void\)\s*deactivateAudioSessionWithCompletion:")
    hop = method.find("dispatch_async(rpcs3_bridge_queue()")
    if hop == -1 or "armsx3_audio_session_stop()" not in method[slice(*brace_span(method, hop))]:
        problems.append("wr04-audio: the façade has no deactivate method calling armsx3_audio_session_stop() on the bridge queue")
    if not re.search(r"setActive:\s*NO\s+withOptions:\s*AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation", audio):
        problems.append("wr04-audio: the session's setActive:NO does not notify others on deactivation")
    if "Rpcs3Bridge.deactivateAudioSession" not in body(session, r"func\s+deactivateAudio\s*\("):
        problems.append("wr04-audio: EmulatorSession.deactivateAudio( does not call Rpcs3Bridge.deactivateAudioSession")
    for name, condition in (("boot", "!="), ("stop", "==")):
        rule = r"if\s+status\s*{0}\s*0\s*\{{\s*await\s+deactivateAudio\(\)\s*\}}".format(re.escape(condition))
        if not re.search(rule, body(session, r"func\s+{0}\s*\(".format(name))):
            problems.append("wr04-audio: EmulatorSession.{0}( does not deactivate on `status {1} 0`".format(name, condition))
    return problems


def wr08_problems(pad, screen):
    problems = []
    lift = re.compile(r"PadTouches\.shared\.touch\(\s*lifted:\s*touches\.map")
    if not lift.search(body(pad, r"override\s+func\s+touchesCancelled\b")):
        problems.append("wr08-touch: PadTouchView's touchesCancelled does not lift its touches")
    if not lift.search(body(pad, r"override\s+func\s+touchesEnded\b")):
        problems.append("wr08-touch: PadTouchView's touchesEnded does not lift its touches")
    view = body(pad, r"struct\s+VirtualControllerView\b")
    change = view.find(".onChange(of: scenePhase)")
    if "@Environment(\\.scenePhase)" not in view or change == -1 or not re.search(
            r"phase\s*!=\s*\.active[^{]*\{\s*PadTouches\.shared\.liftAll\(\)", view[slice(*brace_span(view, change))]):
        problems.append("wr08-touch: VirtualControllerView does not lift every finger when the scene leaves .active")
    stick = body(pad, r"struct\s+PadStick\b")
    change = stick.find(".onChange(of: touching)")
    if not re.search(r"@GestureState\s+private\s+var\s+touching\b", stick) or ".updating($touching" not in stick \
            or change == -1 or "send(.zero)" not in stick[slice(*brace_span(stick, change))]:
        problems.append("wr08-touch: PadStick does not centre from the reset of a @GestureState touching")
    change = screen.find(".onChange(of: scenePhase)")
    if "@Environment(\\.scenePhase)" not in screen or change == -1 or not re.search(
            r"phase\s*!=\s*\.active[^{]*\{\s*PadSnapshot\.shared\.reset\(\)", screen[slice(*brace_span(screen, change))]):
        problems.append("wr08-touch: GameScreenView does not reset the pad when the scene leaves .active")
    return problems


def wr05_problems(frame, pause):
    problems = []
    if not re.search(r"\^\{\s*pause_on_deactivate\(at\);\s*defer_declined_pause\(at\);", frame):
        problems.append("wr05-pause: the deactivation does not queue defer_declined_pause(at) after pause_on_deactivate(at)")
    retry = body(frame, r"void\s+retry_deferred_pause\s*\(")
    running = retry.find("state == RPCS3_IOS_STATE_RUNNING")
    if "deferred_pause_owed(" not in retry or running == -1 or \
            "Rpcs3IOS_Pause()" not in retry[slice(*brace_span(retry, running))] or retry.count("Rpcs3IOS_Pause()") != 1:
        problems.append("wr05-pause: retry_deferred_pause does not pause only once the core reads RUNNING")
    if not re.search(r"dispatch_after\([^;]*s_pause_queue,\s*\^\{\s*retry_deferred_pause\(", retry):
        problems.append("wr05-pause: retry_deferred_pause does not re-queue itself on s_pause_queue")
    if re.search(r"\b(usleep|sleep|nanosleep|sleep_for)\s*\(", retry):
        problems.append("wr05-pause: retry_deferred_pause sleeps, which would hold the resume behind it")
    for name, value in (("on_deactivate", "true"), ("on_activate", "false")):
        if "wanted = {0};".format(value) not in body(pause, r"bool\s+{0}\s*\(".format(name)):
            problems.append("wr05-pause: the machine's {0} does not set wanted = {1}".format(name, value))
    return problems


def wr07_problems(store):
    resolve = body(store, r"func\s+resolveAll\s*\(")
    if not re.search(r"guard\s+started\s*\|\|\s*io\.isReadable\(url\)\s+else", resolve) \
            or not re.search(r"if\s+started\s*\{\s*held\[folder\.id\]\s*=\s*url\s*\}", resolve):
        return ["wr07-folder: resolveAll does not keep a readable folder whose access call returned false"]
    return []


# Every controller the provider returns, and player 1's slot.
READS = (r"static\s+var\s+readsAController\s*:\s*Bool\s*\{\s*shared\.controllersProvider\(\)\.contains\s*"
         r"\{\s*\$0\.extendedGamepad\s*!=\s*nil\s*\}\s*\}")
HIDES_ON = "GamepadPoller.playerOneHasController"


def wr09_problems(poller, screen):
    problems = []
    if not re.search(READS, poller) or "controllersProvider()" not in body(poller, r"func\s+tick\s*\("):
        problems.append("wr09-pad: GamepadPoller.readsAController is not the extended gamepad its tick() reads")
    listed = re.search(r"static\s+let\s+controllerChanges\s*:\s*\[Notification\.Name\]\s*=\s*\[(.*?)\]", poller, re.DOTALL)
    for name in (".GCControllerDidBecomeCurrent", ".armsx3PlayerSlotsChanged"):
        if listed is None or name not in listed.group(1):
            problems.append("wr09-pad: GamepadPoller.controllerChanges misses {0}".format(name))
    sets = re.findall(r"controllerConnected\s*=\s*([^\n]+)", screen)
    if "GCController.controllers()" in screen or not sets or any(v.strip() != HIDES_ON for v in sets):
        problems.append("wr09-pad: GameScreenView hides the pad on something other than {0}".format(HIDES_ON))
    return problems


def wr10_problems(render):
    make = body(render, r"std::shared_ptr\s*<\s*AudioBackend\s*>\s*make_audio\s*\(\s*\)")
    arm = make[make.find("default:"):] if "default:" in make else ""
    if not re.search(r"return\s+std::make_shared<CubebBackend>\(\)\s*;", arm) or "NullAudioBackend" in arm:
        return ["wr10-audio: make_audio()'s default arm is not CubebBackend"]
    return []


def wr11_problems(abi):
    problems = []
    install = body(abi, r"Rpcs3IOS_Status\s+install_pup\s*\(")
    first = re.search(r'vfs::mount\("/dev_flash",\s*([^)]*)\)', install)
    if first is None or first.group(1).replace(" ", "") != 'staging+"/"':
        problems.append("wr11-firmware: install_pup does not mount /dev_flash on the staging directory first")
    lam = install.find("const auto stage = [&]")
    span = brace_span(install, lam) if lam != -1 else (0, 0)
    extracts = [m.start() for m in re.finditer(r"\.extract\(\)", install)]
    if not extracts or any(not span[0] < at < span[1] for at in extracts):
        problems.append("wr11-firmware: an extract() runs outside the staging lambda")
    after = install[install.find("stage();"):] if "stage();" in install else ""
    for needed in ("fs::rename(live, previous, false)", "fs::rename(staging, live, false)",
                   'vfs::mount("/dev_flash", g_cfg_vfs.get_dev_flash())'):
        if needed not in after:
            problems.append("wr11-firmware: after stage(), install_pup lacks `{0}`".format(needed))
    return problems


GENERATION_GUARD = "guard generation == refreshGeneration else { return }"


def wr12_problems(model):
    problems = []
    refresh = body(model, r"func\s+refresh\s*\(")
    if not re.match(r"\{\s*refreshGeneration\s*\+=\s*1\s+let\s+generation\s*=\s*refreshGeneration\b", refresh):
        problems.append("wr12-refresh: refresh() does not begin by taking the next generation")
    awaits = [m.end() for m in re.finditer(r"\bawait\b", refresh)]
    for write in re.finditer(r"\b(phase|folders|live)\s*=[^=]|\bpublish\(", refresh):
        before = [at for at in awaits if at < write.start()]
        if before and GENERATION_GUARD not in refresh[before[-1]:write.start()]:
            problems.append("wr12-refresh: `{0}` after an await is not behind the generation guard".format(
                write.group(0).strip()))
    if not re.search(r"Task\.detached\([^)]*\)\s*\{[^}]*?await\s+previousSave\?\.value", refresh):
        problems.append("wr12-refresh: the cache save does not wait for the previous save")
    return problems


def wr13_problems(abi):
    counters = body(abi, r"Rpcs3IOS_Status\s+Rpcs3IOS_GetFrameCounters\s*\(")
    status = counters.find("Emu.GetStatus(false)")
    gate = re.search(r"if\s*\(\s*state\s*!=\s*system_state::running\s*&&\s*state\s*!=\s*system_state::paused\s*"
                     r"&&\s*state\s*!=\s*system_state::frozen\s*\)\s*\{\s*return\s+RPCS3_IOS_STATUS_OK;", counters)
    fetch = counters.find("g_fxo->try_get<rsx::thread>()")
    if status == -1 or gate is None or fetch == -1 or not status < gate.start() < fetch:
        return ["wr13-frames: GetFrameCounters reaches the RSX thread without first refusing a core that is not up"]
    return []


def all_problems(session, facade, audio, pad, screen, frame, pause, store, poller, render, abi, model):
    return (wr04_problems(session, facade, audio) + wr08_problems(pad, screen)
            + wr05_problems(frame, pause) + wr07_problems(store) + wr09_problems(poller, screen)
            + wr10_problems(render) + wr11_problems(abi) + wr12_problems(model) + wr13_problems(abi))


# Written into a temporary directory at run time; one line per scenario saying whether the pause is still owed.
MACHINE_DRIVER = r"""
#include "lifecycle_pause.h"
#include <cstdio>
using armsx3::ios_frame::pause_machine;
int main()
{
    { pause_machine m; m.on_deactivate(); m.on_pause_result(-9, 0, 1, 0, false);
      const bool before = m.deferred_pause_owed(1); m.on_activate();
      std::printf("declined-then-activated %d %d\n", before, m.deferred_pause_owed(1)); }
    { pause_machine m; m.on_deactivate(); m.on_pause_result(-9, 0, 1, 0, false);
      m.on_deactivate(); m.on_pause_result(-9, 0, 2, 0, false);
      std::printf("superseded %d %d\n", m.deferred_pause_owed(1), m.deferred_pause_owed(2)); }
    { pause_machine m; m.on_deactivate(); m.on_pause_result(0, 0, 1, 0, false);
      std::printf("held %d\n", m.deferred_pause_owed(1)); }
    { pause_machine m; m.on_deactivate(); m.on_pause_result(-9, 0, 1, 0, false);
      m.on_pause_result(0, 0, 5, 0, false);
      std::printf("landed-late %d held=%d pauses_ok=%llu\n", m.deferred_pause_owed(1), m.l.held ? 1 : 0,
                  (unsigned long long)m.l.pauses_ok); }
    return 0;
}
"""
MACHINE_EXPECTED = ["declined-then-activated 1 0", "superseded 0 1", "held 0", "landed-late 0 held=1 pauses_ok=1"]


READERS = {"abi": abi_text, "facade": facade_text}


def real_texts():
    return {name: without_comments(READERS.get(name, read)(path)) for name, path in INPUTS.items()}


class ReviewWarningsRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], all_problems(**real_texts()))

    def test_edge_missing_and_empty_inputs_raise(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class ReviewWarningsMutations(unittest.TestCase):
    """Each method makes ONE edit to the comment-stripped real text and shows the checker fail."""

    @classmethod
    def setUpClass(cls):
        cls.texts = real_texts()

    def edited(self, name, old, new):
        text = self.texts[name]
        self.assertIn(old, text)
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return dict(self.texts, **{name: mutated})

    def assertFlagged(self, texts, prefix):
        problems = all_problems(**texts)
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_mutation_wr04_stop_keeps_the_session(self):
        self.assertFlagged(self.edited("session", "if status == 0 { await deactivateAudio() }", ""), "wr04-audio")

    def test_mutation_wr04_failed_boot_keeps_the_session(self):
        self.assertFlagged(self.edited("session", "if status != 0 { await deactivateAudio() }", ""), "wr04-audio")

    def test_mutation_wr04_facade_starts_instead(self):
        method = self.texts["facade"].index("deactivateAudioSessionWithCompletion:")
        start, end = brace_span(self.texts["facade"], method)
        old = self.texts["facade"][start:end]
        self.assertFlagged(self.edited("facade", old, old.replace("armsx3_audio_session_stop()", "armsx3_audio_session_start()")),
                           "wr04-audio")

    def test_mutation_wr04_no_notify(self):
        self.assertFlagged(self.edited("audio", "withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation",
                                       "withOptions:0"), "wr04-audio")


    def test_mutation_wr08_cancelled_touch_stays_held(self):
        start, end = brace_span(self.texts["pad"], self.texts["pad"].index("override func touchesCancelled"))
        old = self.texts["pad"][start:end]
        self.assertFlagged(self.edited("pad", old, "{ }"), "wr08-touch")

    def test_mutation_wr08_pad_keeps_fingers_in_the_background(self):
        self.assertFlagged(self.edited("pad", "if phase != .active { PadTouches.shared.liftAll() }", "_ = phase"),
                           "wr08-touch")

    def test_mutation_wr08_stick_reset_sends_nothing(self):
        self.assertFlagged(self.edited("pad", "offset = .zero\n                send(.zero)", "offset = .zero"), "wr08-touch")

    def test_mutation_wr08_background_keeps_the_pad(self):
        self.assertFlagged(self.edited("screen", "{ PadSnapshot.shared.reset() }\n            }\n    }",
                                       "{ }\n            }\n    }"), "wr08-touch")


    def test_mutation_wr05_no_retry_queued(self):
        self.assertFlagged(self.edited("frame", "defer_declined_pause(at);", ""), "wr05-pause")

    def test_mutation_wr05_pause_while_starting(self):
        self.assertFlagged(self.edited("frame", "state == RPCS3_IOS_STATE_RUNNING", "state == RPCS3_IOS_STATE_STARTING"),
                           "wr05-pause")

    def test_mutation_wr05_activation_keeps_it_wanted(self):
        self.assertFlagged(self.edited("pause", "++l.activations;\n\t\t\twanted = false;", "++l.activations;"),
                           "wr05-pause")

    def test_mutation_wr07_false_access_is_unavailable(self):
        self.assertFlagged(self.edited("store", "guard started || io.isReadable(url) else", "guard started else"),
                           "wr07-folder")

    def test_mutation_wr09_any_controller_hides_the_pad(self):
        self.assertFlagged(self.edited("screen", "controllerConnected = GamepadPoller.playerOneHasController\n            }",
                                       "controllerConnected = !GCController.controllers().isEmpty\n            }"), "wr09-pad")

    def test_mutation_wr09_poller_reads_a_micro_gamepad(self):
        self.assertFlagged(self.edited("poller", "$0.extendedGamepad != nil }",
                                       "$0.microGamepad != nil }"), "wr09-pad")

    def test_mutation_wr09_a_claim_is_never_announced(self):
        # Without the slot notification the screen never learns player 1 was claimed.
        self.assertFlagged(self.edited("poller", ".armsx3PlayerSlotsChanged,", ""), "wr09-pad")

    def test_mutation_wr10_default_back_to_null(self):
        self.assertFlagged(self.edited("render", "return std::make_shared<CubebBackend>();\n\t\t}",
                                       "return std::make_shared<NullAudioBackend>();\n\t\t}"), "wr10-audio")

    def test_mutation_wr11_extract_over_the_installed_tree(self):
        self.assertFlagged(self.edited("abi", '!vfs::mount("/dev_flash", staging + "/")',
                                       '!vfs::mount("/dev_flash", g_cfg_vfs.get_dev_flash())'), "wr11-firmware")

    def test_mutation_wr11_staged_tree_never_swapped_in(self):
        self.assertFlagged(self.edited("abi", "!fs::rename(staging, live, false)", "false"), "wr11-firmware")

    def test_mutation_wr12_old_scan_publishes(self):
        self.assertFlagged(self.edited("model", "_ = await save.value\n        " + GENERATION_GUARD,
                                       "_ = await save.value"), "wr12-refresh")

    def test_mutation_wr12_saves_race(self):
        self.assertFlagged(self.edited("model", "_ = await previousSave?.value", ""), "wr12-refresh")

    def test_mutation_wr13_reads_while_stopping(self):
        # Other entries share this gate, so the edit lands in GetFrameCounters' own copy.
        text = self.texts["abi"]
        old = "state != system_state::running && state != system_state::paused"
        at = text.index(old, text.index("Rpcs3IOS_Status Rpcs3IOS_GetFrameCounters("))
        mutated = text[:at] + "state != system_state::running && state != system_state::stopping" + text[at + len(old):]
        self.assertFlagged(dict(self.texts, abi=mutated), "wr13-frames")


class DeferredPauseMachine(unittest.TestCase):
    def test_a_declined_pause_is_owed_until_activation_or_supersession(self):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")
        read(INPUTS["pause"])
        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
            driver.write_text(MACHINE_DRIVER, encoding="utf-8")
            built = subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(INPUTS["pause"].parent),
                                    str(driver), "-o", str(binary)], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, timeout=120, universal_newlines=True)
            self.assertEqual(0, built.returncode, built.stdout)
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30,
                                 universal_newlines=True)
        self.assertEqual(MACHINE_EXPECTED, ran.stdout.splitlines())


if __name__ == "__main__":
    unittest.main(verbosity=2)
