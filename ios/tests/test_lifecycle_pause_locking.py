# SPDX-License-Identifier: GPL-2.0-only
"""The pause wiring in ios_frame.mm never calls anything under s_pause_mutex but the pause machine and two lock-free
leaves, and the sliced real workers, run on the host, never re-lock it and pause a game booted or restarted while the
scene is inactive, through the iOS on_run hook. It is host evidence about the locking shape only."""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import abi_text, brace_block
from source_helpers import without_c_comments as without_comments

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "ios" / "bridge"
SUBJECT = BRIDGE / "ios_frame.mm"
IMPL = BRIDGE / "ios_frame_impl.cpp"
CALLBACKS = BRIDGE / "callbacks.cpp"  # the on_run hook
SYSTEM = ROOT / "rpcs3" / "Emu" / "System.cpp"  # read only: on_run before every STARTING
HELPER = "notify_pause_owner_of_run"

# Lock-free leaves allowed under the lock: a clock read and an atomic load.
LEAVES = {"monotonic_ms": SUBJECT, "gl_flip_entries": IMPL}
KEYWORDS = {"if", "while", "for", "switch", "return", "sizeof", "static_cast", "reinterpret_cast",
            "const_cast", "decltype"}

DECL = "std::mutex s_pause_mutex;"
LOCK = re.compile(r"std::lock_guard\s+\w+\s*\(\s*s_pause_mutex\s*\)\s*;")
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*\(")


def abi_code():
    return clean_code(abi_text(BRIDGE), BRIDGE)


def code_of(path):
    return clean_code(path.read_text(encoding="utf-8"), path)


def clean_code(text, path):
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    # Block comments become their own newlines, so reported line numbers stay the file's.
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.DOTALL)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', without_comments(text))


def enclosing_block_rest(code, start):
    """From `start` to the brace closing the block `start` sits in."""
    depth = 0
    for i in range(start, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            if depth == 0:
                return code[start:i]
            depth -= 1
    raise AssertionError("no closing brace after offset {0}".format(start))


def body_of(code, name):
    m = re.search(r"[\w:&*>]\s+" + name + r"\s*\([^;{}]*\)\s*\{", code)
    if m is None:
        raise AssertionError("no definition of {0}() found".format(name))
    return brace_block(code, m.end() - 1)[0]


class NothingRunsUnderThePauseMutex(unittest.TestCase):
    def test_every_hold_calls_only_the_machine_and_the_leaves(self):
        code = code_of(SUBJECT)
        self.assertEqual(1, code.count(DECL), "the s_pause_mutex declaration is not where expected")

        uses = [m.start() for m in re.finditer(r"\bs_pause_mutex\b", code)]
        locks = list(LOCK.finditer(code))
        self.assertTrue(locks, "no lock_guard on s_pause_mutex found; the guard would be vacuous")
        scoped = {m.start() + m.group(0).index("s_pause_mutex") for m in locks}
        scoped.add(code.index(DECL) + DECL.index("s_pause_mutex"))
        self.assertEqual([], [u for u in uses if u not in scoped],
                         "s_pause_mutex is used in a form other than `std::lock_guard lock(...)`; "
                         "this guard cannot scope it")

        bad = []
        for m in locks:
            held = enclosing_block_rest(code, m.end())
            held = re.sub(r"s_pause_machine\s*\.\s*\w+\s*\(", "(", held)
            line = code.count("\n", 0, m.start()) + 1
            for name in CALL.findall(held):
                if name in KEYWORDS or name in LEAVES:
                    continue
                takes = False
                try:
                    takes = "s_pause_mutex" in body_of(code, name)
                except AssertionError:
                    pass
                bad.append("{0}:{1}: {2}() is called while s_pause_mutex is held{3}".format(
                    SUBJECT.name, line, name,
                    " -- and it takes s_pause_mutex itself: SELF-DEADLOCK (std::mutex is not "
                    "recursive)" if takes else ""))
        self.assertEqual([], bad, "\n" + "\n".join(bad))

    def test_the_leaves_take_no_lock(self):
        for name, path in LEAVES.items():
            body = body_of(code_of(path), name)
            self.assertNotIn("s_pause_mutex", body, name)
            self.assertIsNone(re.search(r"mutex|\block|_lock\b", body),
                              "{0}() must stay lock-free to be called under the lock".format(name))


DRIVER_HEAD = r"""
#include "lifecycle_pause.h"
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <time.h>

typedef std::int32_t Rpcs3IOS_Status;
Rpcs3IOS_Status Rpcs3IOS_Pause();
Rpcs3IOS_Status Rpcs3IOS_Resume();
// The state read a declined resume makes; the values are the ABI header's.
Rpcs3IOS_Status Rpcs3IOS_GetState(std::int32_t* out_state);
constexpr std::int32_t RPCS3_IOS_STATUS_OK = 0;
constexpr std::int32_t RPCS3_IOS_STATE_PAUSED = 104;
constexpr std::int32_t RPCS3_IOS_STATE_RUNNING = 103;  // a new game after the kill

namespace armsx3::ios_frame
{
    using dispatch_queue_t = void*;
    std::atomic<bool> g_capture_written{false};
    std::atomic<bool> g_capture_failed{false};
    bool capture_armed() { return false; }
    unsigned long long gl_flip_entries() { return 7; }

    namespace
    {
        // Single-threaded driver standing in for the serial queue: a re-lock by the holder is the
        // self-deadlock, reported and exited on instead of hung on.
        struct checked_mutex
        {
            std::mutex m;
            bool held = false;
            void lock()
            {
                if (held)
                {
                    std::fprintf(stderr, "SELF-DEADLOCK: s_pause_mutex locked again by the thread "
                        "holding it (std::mutex is not recursive)\n");
                    std::_Exit(3);
                }
                m.lock();
                held = true;
            }
            void unlock() { held = false; m.unlock(); }
        };
"""

DRIVER_TAIL = r"""
    }
}

static Rpcs3IOS_Status g_status = 0;
static std::int32_t g_state = RPCS3_IOS_STATE_PAUSED;  // what the stubbed core reads
static Rpcs3IOS_Status abi(const char* what)
{
    if (armsx3::ios_frame::s_pause_mutex.held)
    {
        std::fprintf(stderr, "HELD-ACROSS-ABI: %s called with s_pause_mutex held\n", what);
        std::_Exit(4);
    }
    return g_status;
}
Rpcs3IOS_Status Rpcs3IOS_Pause() { return abi("Rpcs3IOS_Pause"); }
Rpcs3IOS_Status Rpcs3IOS_Resume() { return abi("Rpcs3IOS_Resume"); }
Rpcs3IOS_Status Rpcs3IOS_GetState(std::int32_t* out_state)
{
    (void)abi("Rpcs3IOS_GetState");
    *out_state = g_state;
    return RPCS3_IOS_STATUS_OK;
}

// Each step's outcome, read from the machine's ledger: one RESULT per pause or resume result it recorded.
static void report(const char* event, bool quiet_when_uncalled = false)
{
    using namespace armsx3::ios_frame;
    static std::uint64_t seen_pauses = 0, seen_resumes = 0;
    std::uint64_t pauses, resumes;
    long long pause_status, resume_status;
    {
        std::lock_guard lock(s_pause_mutex);
        pauses = s_pause_machine.l.pauses_ok + s_pause_machine.l.pauses_declined;
        resumes = s_pause_machine.l.resumes_ok + s_pause_machine.l.resumes_declined;
        pause_status = s_pause_machine.l.last_pause_status;
        resume_status = s_pause_machine.l.last_resume_status;
    }
    if (pauses == seen_pauses && resumes == seen_resumes && !quiet_when_uncalled)
        std::fprintf(stderr, "RESULT event=%s called=0 status=0\n", event);
    for (; seen_pauses < pauses; ++seen_pauses)
        std::fprintf(stderr, "RESULT event=%s called=1 status=%lld\n", event, pause_status);
    for (; seen_resumes < resumes; ++seen_resumes)
        std::fprintf(stderr, "RESULT event=%s called=1 status=%lld\n", event, resume_status);
}
static void deactivate() { armsx3::ios_frame::pause_on_deactivate(0); report("pause"); }
static void activate() { armsx3::ios_frame::resume_on_activate(); report("resume"); }

int main()
{
    using namespace armsx3::ios_frame;
    (void)s_pause_queue;
    activate();                             // launch: the first activate owes nothing
    deactivate();                           // a pause that lands
    deactivate();                           // a second deactivate while it is held
    activate();                             // the owed resume
    g_status = -9;
    deactivate();                           // a pause the emulator declines
    activate();                             // which owes nothing
    g_status = 0;
    deactivate();                           // a pause that lands
    g_status = -9;
    activate();                             // a resume declined while the core reads PAUSED
    g_status = 0;
    deactivate();                           // the pause is still held: nothing asked
    activate();                             // so the resume is owed again
    deactivate();                           // a pause that lands
    g_status = -9;
    activate();                             // a resume declined while the core reads PAUSED
    g_status = 0;
    g_state = RPCS3_IOS_STATE_RUNNING;      // the save's kill and a new game: the hold is stale
    deactivate();                           // so the stale hold is dropped and the new game pauses
    activate();                             // and the activation resumes it
    std::fprintf(stderr, "DONE\n");
    return 0;
}
"""

EXPECTED = [("resume", "0", "0"), ("pause", "1", "0"), ("pause", "0", "0"),
            ("resume", "1", "0"), ("pause", "1", "-9"), ("resume", "0", "0"),
            ("pause", "1", "0"), ("resume", "1", "-9"), ("pause", "0", "0"), ("resume", "1", "0"),
            ("pause", "1", "0"), ("resume", "1", "-9"), ("pause", "1", "0"), ("resume", "1", "0")]


def drop_problems(code):
    """Both pause askers drop a stale hold before the machine decides."""
    problems = []
    body = body_of(code, "pause_on_deactivate")
    drop = body.find("drop_stale_hold();")
    decide = body.find("on_deactivate()")
    if drop < 0 or decide < 0 or drop > decide:
        problems.append("g1-drop: pause_on_deactivate does not call drop_stale_hold() before "
                        "on_deactivate()")
    body = body_of(code, "menu_pause_worker")
    m = re.search(r"if\s*\(\s*open\s*\)\s*\{?\s*drop_stale_hold\s*\(\s*\)\s*;", body)
    decide = body.find("on_menu_open()")
    if m is None or decide < 0 or m.start() > decide:
        problems.append("g1-drop: menu_pause_worker does not call drop_stale_hold() under "
                        "`if (open)` before on_menu_open()")
    return problems


class BothPauseAskersDropAStaleHold(unittest.TestCase):
    def test_both_pause_askers_drop_a_stale_hold_first(self):
        self.assertEqual([], drop_problems(code_of(SUBJECT)))

    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is absent")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out, "the mutation did not take")
        return out

    def without_drop_in(self, name):
        code = code_of(SUBJECT)
        body = body_of(code, name)
        return self.edited(code, body, self.edited(body, "drop_stale_hold();", ""))

    def test_deactivation_without_the_drop(self):
        problems = drop_problems(self.without_drop_in("pause_on_deactivate"))
        self.assertTrue(any(p.startswith("g1-drop:") for p in problems), problems)

    def test_menu_open_without_the_drop(self):
        problems = drop_problems(self.without_drop_in("menu_pause_worker"))
        self.assertTrue(any(p.startswith("g1-drop:") for p in problems), problems)


class ThePauseFunctionsRunWithoutReLocking(unittest.TestCase):
    def test_the_launch_sequence_runs_and_reports_every_outcome(self):
        compiler = shutil.which("c++")
        if compiler is None:
            raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")

        text = SUBJECT.read_text(encoding="utf-8")
        self.assertEqual(1, text.count(DECL), "the s_pause_mutex declaration is not where expected")
        start = text.index(DECL)
        end_fn = re.search(r"void\s+resume_on_activate\s*\(\s*\)", text)
        self.assertIsNotNone(end_fn, "resume_on_activate() not found in ios_frame.mm")
        block, at = brace_block(text, end_fn.end())
        piece = text[start:at + len(block)].replace(DECL, "checked_mutex s_pause_mutex;", 1)

        with tempfile.TemporaryDirectory() as tmp:
            driver = Path(tmp) / "driver.cpp"
            binary = Path(tmp) / "driver"
            driver.write_text(DRIVER_HEAD + piece + DRIVER_TAIL, encoding="utf-8")
            built = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver),
                 "-o", str(binary)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120,
                universal_newlines=True)
            self.assertEqual(0, built.returncode,
                             "the sliced pause functions did not compile:\n" + built.stdout)
            try:
                ran = subprocess.run([str(binary)], stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=30,
                                     universal_newlines=True)
            except subprocess.TimeoutExpired:
                self.fail("the sliced pause functions HUNG (a real deadlock)")

        self.assertEqual(0, ran.returncode, "\n" + ran.stdout)
        got = re.findall(r"^RESULT event=([\w-]+) called=(-?\d+) status=(-?\d+)$", ran.stdout,
                         flags=re.MULTILINE)
        self.assertEqual(EXPECTED, got, "\n" + ran.stdout)
        self.assertIn("DONE", ran.stdout)


G3_HEAD = r"""
#include "lifecycle_pause.h"
#include "rpcs3_ios_abi.h"
#include <dispatch/dispatch.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <time.h>
#include <unistd.h>

namespace armsx3::ios_frame
{
    std::atomic<bool> g_capture_written{false};
    std::atomic<bool> g_capture_failed{false};
    bool capture_armed() { return false; }
    unsigned long long gl_flip_entries() { return 7; }

    namespace
    {
        // Every use of the mutex runs on the serial queue (dump() included), so a re-lock by the
        // holder is the self-deadlock, reported and exited on instead of hung on.
        struct checked_mutex
        {
            std::mutex m;
            bool held = false;
            void lock()
            {
                if (held)
                {
                    std::fprintf(stderr, "SELF-DEADLOCK: s_pause_mutex locked again by the thread "
                        "holding it (std::mutex is not recursive)\n");
                    std::_Exit(3);
                }
                m.lock();
                held = true;
            }
            void unlock() { held = false; m.unlock(); }
        };
"""

G3_TAIL = r"""
    }
}
@@ONRUN_DEFS@@
// The stubbed core: a pause succeeds only from RUNNING, a resume only from PAUSED.
static std::atomic<std::int32_t> g_state{RPCS3_IOS_STATE_RUNNING};
static void abi(const char* what)
{
    if (armsx3::ios_frame::s_pause_mutex.held)
    {
        std::fprintf(stderr, "HELD-ACROSS-ABI: %s called with s_pause_mutex held\n", what);
        std::_Exit(4);
    }
}
static Rpcs3IOS_Status transition(std::int32_t from, std::int32_t to)
{
    return g_state.compare_exchange_strong(from, to) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
}
Rpcs3IOS_Status Rpcs3IOS_Pause(void)
{
    abi("Rpcs3IOS_Pause");
    return transition(RPCS3_IOS_STATE_RUNNING, RPCS3_IOS_STATE_PAUSED);
}
Rpcs3IOS_Status Rpcs3IOS_Resume(void)
{
    abi("Rpcs3IOS_Resume");
    return transition(RPCS3_IOS_STATE_PAUSED, RPCS3_IOS_STATE_RUNNING);
}
Rpcs3IOS_Status Rpcs3IOS_GetState(int32_t* out_state)
{
    abi("Rpcs3IOS_GetState");
    *out_state = g_state.load();
    return RPCS3_IOS_STATUS_OK;
}

using armsx3::ios_frame::s_pause_queue;
static void barrier() { dispatch_sync(s_pause_queue, ^{}); }

// Each step's outcome, read from the machine's ledger: one RESULT per pause or resume result it recorded.
static void report(const char* event, bool quiet_when_uncalled = false)
{
    using namespace armsx3::ios_frame;
    static std::uint64_t seen_pauses = 0, seen_resumes = 0;
    std::uint64_t pauses, resumes;
    long long pause_status, resume_status;
    {
        std::lock_guard lock(s_pause_mutex);
        pauses = s_pause_machine.l.pauses_ok + s_pause_machine.l.pauses_declined;
        resumes = s_pause_machine.l.resumes_ok + s_pause_machine.l.resumes_declined;
        pause_status = s_pause_machine.l.last_pause_status;
        resume_status = s_pause_machine.l.last_resume_status;
    }
    if (pauses == seen_pauses && resumes == seen_resumes && !quiet_when_uncalled)
        std::fprintf(stderr, "RESULT event=%s called=0 status=0\n", event);
    for (; seen_pauses < pauses; ++seen_pauses)
        std::fprintf(stderr, "RESULT event=%s called=1 status=%lld\n", event, pause_status);
    for (; seen_resumes < resumes; ++seen_resumes)
        std::fprintf(stderr, "RESULT event=%s called=1 status=%lld\n", event, resume_status);
}
// A retry's pause lands later on the queue; it is reported at the driver's next step.
static void deferred() { dispatch_sync(s_pause_queue, ^{ report("pause-deferred", true); }); }
// The WillDeactivate observer's exact pair, and the DidActivate observer's call.
static void deactivate()
{
    dispatch_async(s_pause_queue, ^{
        armsx3::ios_frame::pause_on_deactivate(0);
        report("pause");
        armsx3::ios_frame::defer_declined_pause(0);
        report("pause-deferred", true);
    });
    barrier();
}
static void activate()
{
    dispatch_async(s_pause_queue, ^{ armsx3::ios_frame::resume_on_activate(); report("resume"); });
    barrier();
}
// What note_boot_accepted queues after an accepted boot.
static void boot_accepted()
{
    using namespace armsx3::ios_frame;
    @@BOOT@@
    barrier();
    deferred();
}
static void settle(std::int32_t state, unsigned ms)
{
    g_state = state;
    usleep(ms * 1000u);
    barrier();
    deferred();
}
static void dump()
{
    __block int wanted = 0;
    __block int held = 0;
    dispatch_sync(s_pause_queue, ^{
        using namespace armsx3::ios_frame;
        std::lock_guard lock(s_pause_mutex);
        wanted = s_pause_machine.wanted ? 1 : 0;
        held = s_pause_machine.l.held ? 1 : 0;
    });
    std::fprintf(stderr, "MACHINE wanted=%d held=%d\nCORE state=%d\n", wanted, held, (int)g_state.load());
    std::fflush(stderr);
}

int main(int argc, char** argv)
{
    if (argc != 2)
        return 2;
    s_pause_queue = dispatch_queue_create("g3.pause", DISPATCH_QUEUE_SERIAL);
    const char* s = argv[1];
    if (std::strcmp(s, "during-kill") == 0)
    {
        g_state = RPCS3_IOS_STATE_STOPPING;
        deactivate();                              // the kill is running: the pause is declined
        settle(RPCS3_IOS_STATE_STOPPED, 50);
        g_state = RPCS3_IOS_STATE_STARTING;
        boot_accepted();                           // the restart's BootGame returned no_errors
        settle(RPCS3_IOS_STATE_STARTING, 300);
        settle(RPCS3_IOS_STATE_RUNNING, 600);
        dump();
        activate();
        dump();
    }
    else if (std::strcmp(s, "before-kill") == 0)
    {
        g_state = RPCS3_IOS_STATE_RUNNING;
        deactivate();                              // the pause lands
        settle(RPCS3_IOS_STATE_STOPPING, 50);      // and the kill destroys it
        settle(RPCS3_IOS_STATE_STOPPED, 50);
        g_state = RPCS3_IOS_STATE_STARTING;
        boot_accepted();
        settle(RPCS3_IOS_STATE_STARTING, 300);
        settle(RPCS3_IOS_STATE_RUNNING, 600);
        dump();
        activate();
        dump();
    }
    else if (std::strcmp(s, "control") == 0)
    {
        g_state = RPCS3_IOS_STATE_STARTING;        // no kill, no boot hook
        deactivate();
        settle(RPCS3_IOS_STATE_STARTING, 300);
        settle(RPCS3_IOS_STATE_RUNNING, 600);
        dump();
    }
    else if (std::strcmp(s, "activated") == 0)
    {
        g_state = RPCS3_IOS_STATE_STOPPING;
        deactivate();
        activate();                                // the scene is active again before the boot
        g_state = RPCS3_IOS_STATE_STARTING;
        boot_accepted();
        settle(RPCS3_IOS_STATE_STARTING, 300);
        settle(RPCS3_IOS_STATE_RUNNING, 600);
        dump();
    }
    else
    {
        std::fprintf(stderr, "unknown scenario %s\n", s);
        return 2;
    }
    std::fprintf(stderr, "DONE\n");
    return 0;
}
"""

G3_BOOT_CALL = "dispatch_async(s_pause_queue, ^{ pause_on_boot_accepted(0); });"
G3_NO_HOOK = "// the unfixed ios_frame.mm has no pause_on_boot_accepted: nothing is queued"

G3_STATES_PAUSED = ["MACHINE wanted=1 held=1", "CORE state=104", "MACHINE wanted=0 held=0", "CORE state=103"]
G3_EXPECTED = {
    "during-kill": ([("pause", "1", "-9"), ("pause-deferred", "1", "0"), ("resume", "1", "0")],
                    G3_STATES_PAUSED),
    "before-kill": ([("pause", "1", "0"), ("pause-deferred", "1", "0"), ("resume", "1", "0")],
                    G3_STATES_PAUSED),
    "control": ([("pause", "1", "-9"), ("pause-deferred", "1", "0")],
                ["MACHINE wanted=1 held=1", "CORE state=104"]),
    "activated": ([("pause", "1", "-9"), ("resume", "0", "0")],
                  ["MACHINE wanted=0 held=0", "CORE state=103"]),
}


def g3_slice(text):
    """From the mutex declaration through `defer_declined_pause` or, when present, the later
    `pause_on_boot_accepted`. Returns (piece, has_hook)."""
    if text.count(DECL) != 1:
        raise AssertionError("the s_pause_mutex declaration is not where expected")
    start = text.index(DECL)
    ends = []
    for name in ("defer_declined_pause", "pause_on_boot_accepted"):
        m = re.search(r"void\s+" + name + r"\s*\(", text)
        if m is not None:
            block, at = brace_block(text, m.end())
            ends.append(at + len(block))
    if not ends or re.search(r"void\s+defer_declined_pause\s*\(", text) is None:
        raise AssertionError("defer_declined_pause() not found in ios_frame.mm")
    has_hook = re.search(r"void\s+pause_on_boot_accepted\s*\(", text) is not None
    return text[start:max(ends)].replace(DECL, "checked_mutex s_pause_mutex;", 1), has_hook


def g3_build(text, tmpdir):
    piece, has_hook = g3_slice(text)
    tail = G3_TAIL.replace("@@ONRUN_DEFS@@", "").replace(
        "@@BOOT@@", G3_BOOT_CALL if has_hook else G3_NO_HOOK)
    return g3_compile(G3_HEAD + piece + tail, tmpdir)


def g3_compile(source, tmpdir):
    driver = Path(tmpdir) / "g3.cpp"
    binary = Path(tmpdir) / "g3"
    driver.write_text(source, encoding="utf-8")
    built = subprocess.run(
        [shutil.which("c++"), "-std=c++17", "-Wall", "-Werror", "-I", str(BRIDGE), str(driver),
         "-o", str(binary)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, universal_newlines=True)
    if built.returncode != 0:
        raise AssertionError("the sliced G3 driver did not compile:\n" + built.stdout)
    return binary


def g3_run(binary, scenario):
    """(tuples, state lines, DONE printed, return code, full output)."""
    try:
        ran = subprocess.run([str(binary), scenario], stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, timeout=30, universal_newlines=True)
    except subprocess.TimeoutExpired:
        raise AssertionError("the G3 driver HUNG on {0} (a real deadlock)".format(scenario))
    out = ran.stdout
    tuples = re.findall(r"^RESULT event=([\w-]+) called=(-?\d+) status=(-?\d+)$", out, flags=re.MULTILINE)
    states = re.findall(r"^((?:MACHINE|CORE) .*)$", out, flags=re.MULTILINE)
    return tuples, states, "DONE" in out, ran.returncode, out


def need_compiler():
    if shutil.which("c++") is None:
        raise unittest.SkipTest("no `c++` on PATH; a skip is not a pass")


class TheBootHookPausesUnderADeactivatedScene(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        need_compiler()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.binary = g3_build(SUBJECT.read_text(encoding="utf-8"), cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def check(self, scenario):
        tuples, states, done, rc, out = g3_run(self.binary, scenario)
        self.assertEqual(G3_EXPECTED[scenario], (tuples, states), "\n" + out)
        self.assertTrue(done, "\n" + out)
        self.assertEqual(0, rc, "\n" + out)

    def test_g3_deactivation_during_the_kill_pauses_the_restarted_game(self):
        self.check("during-kill")

    def test_g3_a_pause_the_kill_destroyed_is_taken_again_after_the_restart(self):
        self.check("before-kill")

    def test_g3_control_starting_without_a_kill_still_defers(self):
        self.check("control")

    def test_g3_no_pause_once_the_scene_activated_again(self):
        self.check("activated")


# Emulator::Run's order: on_run while the core reads READY, then STARTING, with no boot_with step.
WR01_BOOT = ("g_state = RPCS3_IOS_STATE_READY;\n"
             "    armsx3::bridge::core_on_run();   // the table's REAL on_run body\n"
             "    barrier();\n"
             "    g_state = RPCS3_IOS_STATE_STARTING;")
ON_RUN_ENTRY = re.compile(r"\bARMSX3_OWNED\s*\(\s*\d+\s*,\s*on_run\s*,\s*\[[^\]]*\]\s*\([^)]*\)\s*\{")


def on_run_body(callbacks_code):
    """The `{ ... }` of the lambda the table's on_run entry passes. Raises if the entry is absent."""
    m = ON_RUN_ENTRY.search(callbacks_code)
    if m is None:
        raise AssertionError("no ARMSX3_OWNED on_run entry with a lambda found in callbacks.cpp")
    return brace_block(callbacks_code, m.end() - 1)[0]


def wr01_build(frame_text, callbacks_code, tmpdir):
    """The G3 driver with the real note_boot_accepted (ios_frame.mm) and the real on_run body and helper (callbacks.cpp,
    preprocessor lines dropped), with external linkage so a mutant that leaves one uncalled still compiles."""
    piece, _ = g3_slice(frame_text)
    try:
        helper = body_of(callbacks_code, HELPER)
        helper = "\n".join(line for line in helper.splitlines() if not line.lstrip().startswith("#"))
        helper = "    void {0}()\n{1}\n".format(HELPER, helper)
    except AssertionError:
        helper = ""  # the unfixed callbacks.cpp has no helper
    defs = ("namespace armsx3::ios_frame\n{\n"
            "    namespace\n    {\n        std::atomic<bool> s_observers_installed{true};\n    }\n"
            "    void note_boot_accepted()\n" + body_of(frame_text, "note_boot_accepted") + "\n}\n"
            "namespace armsx3::bridge\n{\n" + helper
            + "    void core_on_run()\n" + on_run_body(callbacks_code) + "\n}\n")
    tail = G3_TAIL.replace("@@ONRUN_DEFS@@", defs).replace("@@BOOT@@", WR01_BOOT)
    return g3_compile(G3_HEAD + piece + tail, tmpdir)


class TheCoreRunHookPausesUnderADeactivatedScene(unittest.TestCase):
    """The same four scenarios as the boot-hook class, reached through the core's on_run with no boot_with step."""

    @classmethod
    def setUpClass(cls):
        need_compiler()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.binary = wr01_build(SUBJECT.read_text(encoding="utf-8"), code_of(CALLBACKS), cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def check(self, scenario):
        tuples, states, done, rc, out = g3_run(self.binary, scenario)
        self.assertEqual(G3_EXPECTED[scenario], (tuples, states), "\n" + out)
        self.assertTrue(done, "\n" + out)
        self.assertEqual(0, rc, "\n" + out)

    def test_wr01_core_restart_during_the_kill_pauses_the_game(self):
        self.check("during-kill")

    def test_wr01_core_restart_after_a_destroyed_pause_pauses_again(self):
        self.check("before-kill")

    def test_wr01_control_starting_without_a_kill_still_defers(self):
        self.check("control")

    def test_wr01_no_pause_once_the_scene_activated_again(self):
        self.check("activated")


HOOK_CALL = "armsx3::ios_frame::note_boot_accepted();"
DIRECTIVE = re.compile(r"^[ \t]*#[ \t]*(\w+)([^\n]*)$", re.MULTILINE)
TARGET_CONDITIONALS = re.compile(r"^[ \t]*#[ \t]*include[ \t]*<TargetConditionals\.h>[^\n]*\n?",
                                 re.MULTILINE)
IF_IPHONE = re.compile(r"^[ \t]*#[ \t]*(?:if|ifdef|ifndef|elif)\b[^\n]*\bTARGET_OS_IPHONE\b",
                       re.MULTILINE)
STARTING = "m_state = system_state::starting;"
# System.cpp calls on_run through the global callback table.
ON_RUN_CALL = "g_emu_callbacks.on_run("


def wr01_problems(callbacks_code, system_code):
    """The table's on_run calls the helper, which calls note_boot_accepted() under its own TARGET_OS_IPHONE guard after
    TargetConditionals.h, and every STARTING setter in System.cpp follows on_run within 12 lines."""
    problems = []
    try:
        if re.search(r"\b" + HELPER + r"\s*\(\s*\)\s*;", on_run_body(callbacks_code)) is None:
            problems.append("wr01-on-run: the table's on_run does not call {0}()".format(HELPER))
    except AssertionError as e:
        problems.append("wr01-on-run: {0}".format(e))

    try:
        helper = body_of(callbacks_code, HELPER)
    except AssertionError:
        helper = None
        problems.append("wr01-on-run: no definition of {0}() in callbacks.cpp".format(HELPER))
    if helper is not None:
        call = helper.find(HOOK_CALL)
        if call < 0:
            problems.append("wr01-on-run: {0}() does not call note_boot_accepted()".format(HELPER))
        else:
            before = [d.group(1) + d.group(2).rstrip() for d in DIRECTIVE.finditer(helper, 0, call)]
            after = [d.group(1) for d in DIRECTIVE.finditer(helper, call)]
            if not before or before[-1] != "if TARGET_OS_IPHONE" or not after or after[0] != "endif":
                problems.append("wr01-on-run: {0}()'s note_boot_accepted() call is not inside its own "
                                "#if TARGET_OS_IPHONE ... #endif".format(HELPER))

    include = TARGET_CONDITIONALS.search(callbacks_code)
    first_if = IF_IPHONE.search(callbacks_code)
    if include is None or first_if is None or include.start() > first_if.start():
        problems.append("wr01-on-run: callbacks.cpp does not include <TargetConditionals.h> before its "
                        "first #if naming TARGET_OS_IPHONE (undefined, it is silently 0)")

    lines = system_code.split("\n")
    starts = [i for i, line in enumerate(lines) if STARTING in line]
    if not starts:
        problems.append("wr01-on-run: System.cpp has no `{0}`".format(STARTING))
    for i in starts:
        if not any(ON_RUN_CALL in line for line in lines[max(0, i - 12):i]):
            problems.append("wr01-on-run: System.cpp:{0} sets STARTING without {1} in the 12 lines "
                            "before it".format(i + 1, ON_RUN_CALL))
    return problems


class EachCoreRunGuardHasTeeth(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is absent")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out, "the mutation did not take")
        return out

    def without_the_helper_call(self):
        code = code_of(CALLBACKS)
        body = on_run_body(code)
        return self.edited(code, body, self.edited(body, HELPER + "();", ""))

    def helper_edited(self, edit):
        code = code_of(CALLBACKS)
        helper = body_of(code, HELPER)
        return self.edited(code, helper, edit(helper))

    def assert_caught(self, callbacks_code, system_code):
        problems = wr01_problems(callbacks_code, system_code)
        self.assertTrue(any(p.startswith("wr01-on-run:") for p in problems), problems)

    def test_wr01_mutation_on_run_without_the_hook(self):
        need_compiler()
        mutant = self.without_the_helper_call()
        with tempfile.TemporaryDirectory() as tmp:
            binary = wr01_build(SUBJECT.read_text(encoding="utf-8"), mutant, tmp)
            tuples, states, _, _, out = g3_run(binary, "during-kill")
        self.assertNotEqual(G3_EXPECTED["during-kill"], (tuples, states),
                            "the mutation was not caught by during-kill:\n" + out)

    def test_wr01_the_hook_is_wired_into_on_run(self):
        self.assertEqual([], wr01_problems(code_of(CALLBACKS), code_of(SYSTEM)))

    def test_wr01_mutation_on_run_without_the_helper_call(self):
        self.assert_caught(self.without_the_helper_call(), code_of(SYSTEM))

    def test_wr01_mutation_helper_without_the_hook(self):
        self.assert_caught(self.helper_edited(lambda h: self.edited(h, HOOK_CALL, "")),
                           code_of(SYSTEM))

    def test_wr01_mutation_hook_unguarded(self):
        def unguard(helper):
            out = re.sub(r"^[ \t]*#[ \t]*if[ \t]+TARGET_OS_IPHONE[^\n]*\n", "", helper, count=1,
                         flags=re.MULTILINE)
            self.assertNotEqual(helper, out, "the mutation did not take")
            return out
        self.assert_caught(self.helper_edited(unguard), code_of(SYSTEM))

    def test_wr01_mutation_no_target_conditionals(self):
        code = code_of(CALLBACKS)
        self.assertIsNotNone(TARGET_CONDITIONALS.search(code), "the mutation's anchor is absent")
        self.assert_caught(TARGET_CONDITIONALS.sub("", code, count=1), code_of(SYSTEM))

    def test_wr01_mutation_core_starts_without_on_run(self):
        self.assert_caught(code_of(CALLBACKS),
                           self.edited(code_of(SYSTEM), "g_emu_callbacks.on_run(start_playtime);", ""))


def g3_problems(frame_code, abi_code):
    """The boot hook only queues and drops a dead hold before it retries; the iOS on_run calls it, so boot_with must not."""
    problems = []

    def body(code, name, where):
        try:
            return body_of(code, name)
        except AssertionError:
            problems.append("g3-hook: no definition of {0}() in {1}".format(name, where))
            return None

    note = body(frame_code, "note_boot_accepted", "ios_frame.mm")
    if note is not None:
        for need in ("s_observers_installed.load()", "monotonic_ms()"):
            if need not in note:
                problems.append("g3-hook: note_boot_accepted does not read {0}".format(need))
        if re.search(r"!=\s*nullptr", note) is None:
            problems.append("g3-hook: note_boot_accepted does not test the queue against nullptr")
        if "dispatch_sync(" in note:
            problems.append("g3-hook: note_boot_accepted waits on the pause queue (dispatch_sync)")
        calls = note.count("pause_on_boot_accepted(")
        queued = 0
        for m in re.finditer(r"dispatch_async\s*\(", note):
            queued += brace_block(note, m.end())[0].count("pause_on_boot_accepted(")
        if calls == 0 or queued != calls:
            problems.append("g3-hook: note_boot_accepted does not call pause_on_boot_accepted only "
                            "inside a dispatch_async block")

    hook = body(frame_code, "pause_on_boot_accepted", "ios_frame.mm")
    if hook is not None:
        drop = hook.find("drop_stale_hold();")
        retry = hook.find("defer_declined_pause(")
        if drop < 0 or retry < 0 or drop > retry:
            problems.append("g3-hook: pause_on_boot_accepted does not call drop_stale_hold() before "
                            "defer_declined_pause()")

    # Inverted: every boot_with boot reaches on_run before BootGame returns, so a call here would queue a second retry.
    boot = body(abi_code, "boot_with", "rpcs3_ios_abi_lifecycle.cpp")
    if boot is not None and "note_boot_accepted(" in boot:
        problems.append("g3-hook: boot_with calls note_boot_accepted() itself; on_run already does on "
                        "every boot")
    return problems


class EachBootHookGuardHasTeeth(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text, "the mutation's anchor is absent")
        out = text.replace(old, new, 1)
        self.assertNotEqual(text, out, "the mutation did not take")
        return out

    def run_mutant(self, old, new, scenario):
        need_compiler()
        text = SUBJECT.read_text(encoding="utf-8")
        hook = body_of(text, "pause_on_boot_accepted")
        mutant = self.edited(text, hook, self.edited(hook, old, new))
        with tempfile.TemporaryDirectory() as tmp:
            tuples, states, _, _, out = g3_run(g3_build(mutant, tmp), scenario)
        self.assertNotEqual(G3_EXPECTED[scenario], (tuples, states),
                            "the mutation was not caught by " + scenario + ":\n" + out)

    def test_g3_mutation_hook_without_the_drop(self):
        self.run_mutant("drop_stale_hold();", "", "before-kill")

    def test_g3_mutation_hook_without_the_retry(self):
        self.run_mutant("defer_declined_pause(requested_ms);", "", "during-kill")

    def test_g3_the_hook_is_wired_on_every_accepted_boot(self):
        self.assertEqual([], g3_problems(code_of(SUBJECT), abi_code()))

    def assert_caught(self, frame_code, abi_code):
        problems = g3_problems(frame_code, abi_code)
        self.assertTrue(any(p.startswith("g3-hook:") for p in problems), problems)

    def boot_with_edited(self, edit):
        code = abi_code()
        boot = body_of(code, "boot_with")
        return self.edited(code, boot, edit(boot))

    def test_g3_mutation_boot_with_hooks_again(self):
        def hook(boot):
            at = boot.rfind("return RPCS3_IOS_STATUS_OK;")
            self.assertGreaterEqual(at, 0, "the mutation's anchor is absent")
            return boot[:at] + "#if TARGET_OS_IPHONE\n" + HOOK_CALL + "\n#endif\n" + boot[at:]
        self.assert_caught(code_of(SUBJECT), self.boot_with_edited(hook))

    def test_g3_mutation_dispatcher_runs_inline(self):
        code = code_of(SUBJECT)
        note = body_of(code, "note_boot_accepted")
        inline = re.sub(r"dispatch_async\s*\([^,]+,\s*\^\s*\{\s*pause_on_boot_accepted\s*\(\s*at\s*\)"
                        r"\s*;\s*\}\s*\)\s*;", "pause_on_boot_accepted(at);", note, count=1)
        self.assert_caught(self.edited(code, note, inline), abi_code())


if __name__ == "__main__":
    unittest.main(verbosity=2)
