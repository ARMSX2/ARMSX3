# SPDX-License-Identifier: GPL-2.0-only
"""The stall trigger header compiled with clang++ and run through nineteen tick series, with the expected lines derived apart.
The diag_log.cpp wiring checks stay, each with one mutation that shows it fail.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_body, without_comments_log_ring_contract as without_comments


ROOT = Path(__file__).resolve().parents[2]

HEADER = ROOT / "ios" / "bridge" / "stall_trigger.h"

DIAG_CPP = ROOT / "ios" / "bridge" / "diag_log.cpp"

DIAG_H = ROOT / "ios" / "bridge" / "diag_log.h"

FRAME_IMPL = ROOT / "ios" / "bridge" / "ios_frame_impl.cpp"

REPORT_SWIFT = ROOT / "ios" / "app" / "Diagnostics" / "ProblemReportView.swift"

COMPILE_TIMEOUT_SECONDS = 120

RUN_TIMEOUT_SECONDS = 30

EXPECTED = [
    "S1 7:fps0",
    "S2 none",
    "S3 6:fps0 12:fps0",
    "S4 6:fps0 18:fps0",
    "S5 5:stopping",
    "S6 none",
    "S7 none",
    "S8 none",
    "S9 6:fps0",
    "S10 9:fps0",
    "S11 14:fps0",
    "S12 none",
    "S13 none",
    "S14 none",
    "S15 7:sync",
    "S16 7:fps0",
    "S17 10:fps0",
    "S18 4:sync 10:fps0",
    "S19 7:fps0",
]

DRIVER = r'''
#include "stall_trigger.h"
#include <cstdio>
#include <vector>

using namespace armsx3::bridge::diag;

struct tick { unsigned long long boot; char kind; unsigned long long frames; int count; };

static void run(int k, std::vector<tick> ticks)
{
	stall_trigger t{};
	int index = 0;
	bool any = false;
	std::printf("S%d", k);

	for (const tick& x : ticks)
	{
		for (int i = 0; i < x.count; i++)
		{
			index++;
			const stall_reason r = x.kind == 'q' ? stall_on_sync(t, x.boot)
				: stall_tick(t, x.boot, x.kind == 'r', x.kind == 'p', x.kind == 's', x.frames);

			if (r != stall_reason::none)
			{
				std::printf(" %d:%s", index, stall_reason_word(r));
				any = true;
			}
		}
	}

	std::printf("%s\n", any ? "" : " none");
}

int main()
{
	run(1, {{1, 'r', 60, 2}, {1, 'r', 0, 7}});
	run(2, {{1, 'r', 0, 10}});
	run(3, {{1, 'r', 30, 1}, {1, 'r', 0, 5}, {1, 'r', 30, 1}, {1, 'r', 0, 5}});
	run(4, {{1, 'r', 30, 1}, {1, 'r', 0, 5}, {2, 'r', 0, 6}, {2, 'r', 30, 1}, {2, 'r', 0, 5}});
	run(5, {{1, 's', 0, 6}});
	run(6, {{1, 'r', 30, 1}, {1, 'p', 0, 10}});
	run(7, {{1, 'r', 30, 1}, {1, 'r', 0, 4}, {1, 'r', 30, 1}, {1, 'r', 0, 4}});
	run(8, {{1, 's', 0, 4}, {1, 'p', 0, 1}, {1, 's', 0, 4}});
	run(9, {{1, 'r', 30, 1}, {1, 'r', 0, 5}, {1, 's', 0, 6}});
	run(10, {{1, 'r', 30, 1}, {1, 'r', 0, 2}, {1, 'p', 0, 3}, {1, 'r', 0, 3}});
	run(11, {{1, 'r', 30, 1}, {1, 'r', 0, 1}, {1, 'p', 0, 2}, {1, 'r', 0, 1}, {1, 'p', 0, 2}, {1, 'r', 0, 1}, {1, 'p', 0, 2},
		{1, 'r', 0, 1}, {1, 'p', 0, 2}, {1, 'r', 0, 1}, {1, 'p', 0, 2}});
	run(12, {{1, 'r', 30, 1}, {1, 'r', 0, 4}, {1, 'p', 0, 20}});
	run(13, {{1, 'r', 30, 1}, {1, 'r', 0, 3}, {1, 'o', 0, 1}, {1, 'r', 0, 3}});
	run(14, {{1, 'r', 30, 1}, {1, 'r', 0, 3}, {1, 's', 0, 1}, {1, 'r', 0, 3}});
	run(15, {{1, 'r', 30, 1}, {1, 'r', 0, 2}, {1, 'p', 0, 3}, {1, 'q', 0, 1}, {1, 'r', 0, 3}});
	run(16, {{1, 'r', 30, 1}, {1, 'r', 0, 1}, {1, 'q', 0, 1}, {1, 'r', 0, 5}});
	run(17, {{1, 'r', 0, 3}, {1, 'q', 0, 1}, {1, 'r', 30, 1}, {1, 'r', 0, 5}, {1, 'q', 0, 1}});
	run(18, {{1, 'r', 30, 1}, {1, 'r', 0, 2}, {1, 'q', 0, 1}, {1, 'r', 30, 1}, {1, 'r', 0, 5}});
	run(19, {{1, 'r', 30, 1}, {1, 'r', 0, 2}, {2, 'q', 0, 1}, {1, 'r', 0, 3}});
	return 0;
}
'''


def read(path):
    return without_comments(Path(path).read_text(encoding="utf-8"))


def wired_problems(diag):
    body = function_body(diag, "perf_loop")
    if body is None or "stall_tick(" not in body or "dump_stalled_threads(" not in body:
        return ["wired: perf_loop does not call stall_tick( and dump_stalled_threads("]
    if "Emu.IsPaused()" not in body:
        return ["wired: perf_loop does not pass Emu.IsPaused()"]
    return []


def lines_problems(diag):
    wanted = ('diag_log.notice("stall dump reason=', 'diag_log.notice("stall ppu id=', 'diag_log.notice("stall spu id=')
    missing = [w for w in wanted if w not in diag]
    return ["lines: diag_log.cpp lacks " + ", ".join(missing)] if missing else []


def lock_problems(diag):
    body = function_body(diag, "dump_stalled_threads")
    if body is None:
        return ["lock: no single dump_stalled_threads definition"]
    missing = [w for w in ("try_lock_shared()", "unlock_shared()", "idm::unlocked") if w not in body]
    return ["lock: dump_stalled_threads lacks " + ", ".join(missing)] if missing else []


def host_problems(diag):
    if 'diag_log.notice("stall host id=' not in diag:
        return ["host: diag_log.cpp writes no stall host notice"]
    return []


def reads_problems(diag):
    body = function_body(diag, "host_frames")
    if body is None:
        return ["reads: no single host_frames definition"]
    problems = ["reads: host_frames lacks " + w for w in ("vm_read_overwrite(", "thread_get_state(") if w not in body]
    if "*reinterpret_cast<" in body:
        problems.append("reads: host_frames reads through a cast address")
    return problems


def nosuspend_problems(diag):
    return ["nosuspend: diag_log.cpp names " + w for w in ("thread_suspend", "task_suspend") if w in diag]


def sync_problems(diag):
    problems = []
    body = function_body(diag, "sync")
    if body is None or "sync_stall_dump();" not in body or "sync_all();" not in body or \
            body.index("sync_stall_dump();") > body.index("sync_all();"):
        problems.append("sync: sync() does not call sync_stall_dump(); before sync_all();")
    dump = function_body(diag, "sync_stall_dump")
    if dump is None:
        problems.append("sync: no single sync_stall_dump definition")
    else:
        problems += ["sync: sync_stall_dump lacks " + w for w in ("stall_on_sync(", "dump_stalled_threads(", "s_stall_mutex")
                     if w not in dump]
    perf = function_body(diag, "perf_loop")
    if perf is None or "s_stall_mutex" not in perf:
        problems.append("sync: perf_loop does not take s_stall_mutex")
    return problems


def rsx_problems(diag):
    problems = []
    dump = function_body(diag, "dump_stalled_threads")
    if dump is None or "dump_rsx_side(" not in dump:
        problems.append("rsx: dump_stalled_threads does not call dump_rsx_side(")
    body = function_body(diag, "dump_rsx_side")
    if body is None:
        return problems + ["rsx: no single dump_rsx_side definition"]
    problems += ["rsx: dump_rsx_side lacks " + w for w in
                 ("get_current_renderer()", "THREAD_EXTENDED_INFO", "host_frames(", '"rsx::thread"', '"RSX.W"', '"VBlank"',
                  "present_gate_closed()", "TARGET_OS_IPHONE", "s_rsx_held_since") if w not in body]
    problems += ["rsx: dump_rsx_side names " + w for w in ("lock(", "get_pipe_compiler(", "thread_suspend") if w in body]
    problems += ["rsx: diag_log.cpp lacks " + w for w in
                 ('diag_log.notice("stall rsx thread present=', 'diag_log.notice("stall rsx host name=') if w not in diag]
    return problems


def hold_problems(diag, diag_h, impl):
    problems = []
    if "void note_rsx_hold(bool held);" not in diag_h:
        problems.append("hold: diag_log.h does not declare void note_rsx_hold(bool held);")
    body = function_body(diag, "note_rsx_hold")
    if body is None or "s_rsx_held_since" not in body:
        problems.append("hold: no single note_rsx_hold definition over s_rsx_held_since")
    on, off = "armsx3::bridge::diag::note_rsx_hold(true);", "armsx3::bridge::diag::note_rsx_hold(false);"
    if impl.count(on) != 1 or impl.count(off) != 1:
        return problems + ["hold: ios_frame_impl.cpp does not call note_rsx_hold(true) and (false) once each"]
    order = ['"RSX thread held:', on, "while (armsx3::ios_frame::present_gate_closed() && !Emu.IsStopped())", off,
             '"RSX thread released:']
    at = [impl.find(w) for w in order]
    if -1 in at or at != sorted(at):
        problems.append("hold: ios_frame_impl.cpp's hold does not report held, wait, released in that order")
    return problems


def between(text, start, end):
    i = text.find(start)
    if i < 0:
        return None
    j = text.find(end, i + len(start))
    return text[i:] if j < 0 else text[i:j]


def staged_problems(swift):
    problems = []
    gather = between(swift, "func gather(", "func ")
    if gather is None or "await Rpcs3Bridge.syncLog()" not in gather:
        problems.append("staged: gather does not await Rpcs3Bridge.syncLog()")
    share = between(swift, "func shareZip(", "store.buildReport(")
    if share is None or "await gather()" not in share:
        problems.append("staged: shareZip does not await gather() before store.buildReport(")
    return problems


class TheTriggerFollowsTheRules(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, universal_newlines=True)
        if found.returncode != 0:
            raise unittest.SkipTest("xcrun finds no macOS `clang++`; a skip is not a pass")
        if not HEADER.is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(HEADER))

        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            built = subprocess.run(
                ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-I", str(HEADER.parent), "-o", str(binary),
                 str(driver)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            if built.returncode != 0:
                raise AssertionError("clang++ failed:\n" + built.stdout)
            ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
        cls.code, cls.out = ran.returncode, ran.stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("S")]

    def test_trigger_series(self):
        self.assertEqual(0, self.code, self.out[-4000:])
        self.assertEqual(EXPECTED, self.lines)


class TheDumpIsWired(unittest.TestCase):
    def test_wired(self):
        self.assertEqual([], wired_problems(read(DIAG_CPP)))

    def test_lines(self):
        self.assertEqual([], lines_problems(read(DIAG_CPP)))

    def test_lock(self):
        self.assertEqual([], lock_problems(read(DIAG_CPP)))

    def test_host(self):
        self.assertEqual([], host_problems(read(DIAG_CPP)))

    def test_reads(self):
        self.assertEqual([], reads_problems(read(DIAG_CPP)))

    def test_nosuspend(self):
        self.assertEqual([], nosuspend_problems(read(DIAG_CPP)))

    def test_sync(self):
        self.assertEqual([], sync_problems(read(DIAG_CPP)))

    def test_rsx(self):
        self.assertEqual([], rsx_problems(read(DIAG_CPP)))

    def test_hold(self):
        self.assertEqual([], hold_problems(read(DIAG_CPP), read(DIAG_H), read(FRAME_IMPL)))

    def test_staged(self):
        self.assertEqual([], staged_problems(read(REPORT_SWIFT)))


class EachCheckCanFail(unittest.TestCase):
    def edited(self, path, old, new):
        text = read(path)
        self.assertIn(old, text)
        return text.replace(old, new)

    def test_wired_call_removed(self):
        self.assertTrue(wired_problems(self.edited(DIAG_CPP, "stall_tick(s_stall,", "stall_none(s_stall,")))

    def test_wired_paused_removed(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "perf_loop")
        self.assertIsNotNone(body)
        self.assertIn("Emu.IsPaused()", body)
        self.assertTrue(wired_problems(diag.replace(body, body.replace("Emu.IsPaused()", "false"))))

    def test_lines_renamed(self):
        self.assertTrue(lines_problems(self.edited(DIAG_CPP, "stall dump reason=", "stall dumped reason=")))

    def test_lock_try_removed(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "dump_stalled_threads")
        self.assertIsNotNone(body)
        self.assertIn("try_lock_shared()", body)
        self.assertTrue(lock_problems(diag.replace(body, body.replace("try_lock_shared()", "is_free()"))))

    def test_host_renamed(self):
        self.assertTrue(host_problems(self.edited(DIAG_CPP, "stall host id=", "stall hosts id=")))

    def test_reads_direct_read_inserted(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "host_frames")
        self.assertIsNotNone(body)
        bad = body.replace("u64 fp = out.fp;", "u64 fp = out.fp; fp = *reinterpret_cast<const u64*>(fp);", 1)
        self.assertNotEqual(body, bad)
        self.assertTrue(reads_problems(diag.replace(body, bad)))

    def test_nosuspend_call_appended(self):
        self.assertTrue(nosuspend_problems(read(DIAG_CPP) + "\nvoid f(thread_t t) { thread_suspend(t); }\n"))

    def test_sync_call_removed(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "sync")
        self.assertIsNotNone(body)
        self.assertIn("sync_stall_dump();", body)
        self.assertTrue(sync_problems(diag.replace(body, body.replace("sync_stall_dump();", ""))))

    def test_rsx_call_removed(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "dump_stalled_threads")
        self.assertIsNotNone(body)
        self.assertIn("dump_rsx_side(", body)
        self.assertTrue(rsx_problems(diag.replace(body, body.replace("dump_rsx_side(", "dump_rsx_none("))))

    def test_rsx_lock_inserted(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "dump_rsx_side")
        self.assertIsNotNone(body)
        bad = body.replace("{", "{ std::lock_guard lock(s_stall_mutex);", 1)
        self.assertTrue(rsx_problems(diag.replace(body, bad)))

    def test_rsx_line_renamed(self):
        self.assertTrue(rsx_problems(self.edited(DIAG_CPP, "stall rsx thread present=", "stall rsx thrd present=")))

    def test_hold_release_removed(self):
        impl = self.edited(FRAME_IMPL, "armsx3::bridge::diag::note_rsx_hold(false);", "")
        self.assertTrue(hold_problems(read(DIAG_CPP), read(DIAG_H), impl))

    def test_staged_sync_removed(self):
        self.assertTrue(staged_problems(self.edited(REPORT_SWIFT, "await Rpcs3Bridge.syncLog()", "Rpcs3Bridge.noop()")))


if __name__ == "__main__":
    unittest.main()
