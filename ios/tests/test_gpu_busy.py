# SPDX-License-Identifier: GPL-2.0-only
"""gpu_busy.h, compiled on the host with a driver, sums Metal command-buffer busy time as the header defines it, and
diag_log.cpp installs the hook, adds time only from the completion handler and takes it in the perf loop.
It boots nothing and proves nothing on a phone."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_body, without_comments_log_ring_contract as without_comments

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "ios" / "bridge" / "gpu_busy.h"
DIAG_CPP = ROOT / "ios" / "bridge" / "diag_log.cpp"
COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30

EXPECTED = [
    "G0 ms=0.000 pct=0.000 cbs=1",
    "G1 ms=10.000 pct=30.000 cbs=2",
    "G2 ms=10.000 pct=60.000 cbs=2",
    "G3 ms=0.000 pct=80.000 cbs=2",
    "G4 ms=10.000 pct=10.000 cbs=1",
    "G5 ms=10.000 pct=25.000 cbs=1",
    "G6a ms=0.000 pct=0.000 cbs=0",
    "G6b ms=15.000 pct=30.000 cbs=2",
    "G7 ms=0.000 pct=30.000 cbs=2",
    "G8 ms=20.000 pct=100.000 cbs=2",
    "G9 windows=1000 over=0 under=0",
]

DRIVER = r'''
#include "gpu_busy.h"
#include <cmath>
#include <cstdio>

using namespace armsx3::bridge::diag;

static gpu_busy fresh(double window_start)
{
	gpu_busy g{};
	g.window_start = window_start;
	return g;
}

static void show(const char* name, gpu_busy& g, double now, std::uint64_t frames)
{
	const gpu_busy_tick t = gpu_busy_take(g, now, frames);
	std::printf("%s ms=%.3f pct=%.3f cbs=%u\n", name, t.gpu_ms, t.gpu_pct, t.cbs);
}

static std::uint64_t s_x = 1;

static double next_u()
{
	s_x = s_x * 6364136223846793005ull + 1442695040888963407ull;
	return static_cast<double>(s_x >> 11) * 0x1.0p-53;
}

int main()
{
	const double nan = std::nan("");

	gpu_busy g0 = fresh(0);
	gpu_busy_add(g0, 10.1, 10.2);
	show("G0", g0, 11.0, 0);

	gpu_busy g1 = fresh(10);
	gpu_busy_add(g1, 10.1, 10.2); gpu_busy_add(g1, 10.3, 10.5);
	show("G1", g1, 11.0, 30);

	gpu_busy g2 = fresh(10);
	gpu_busy_add(g2, 10.0, 10.4); gpu_busy_add(g2, 10.2, 10.6);
	show("G2", g2, 11.0, 60);

	gpu_busy g3 = fresh(10);
	gpu_busy_add(g3, 10.1, 10.9); gpu_busy_add(g3, 10.2, 10.3);
	show("G3", g3, 11.0, 0);

	gpu_busy g4 = fresh(10);
	gpu_busy_add(g4, 0.0, 10.5); gpu_busy_add(g4, 10.5, 10.5); gpu_busy_add(g4, 10.6, 10.4);
	gpu_busy_add(g4, nan, 10.7); gpu_busy_add(g4, 10.1, nan); gpu_busy_add(g4, 10.1, 10.2);
	show("G4", g4, 11.0, 10);

	gpu_busy g5 = fresh(10);
	gpu_busy_add(g5, 9.5, 10.25);
	show("G5", g5, 11.0, 25);

	gpu_busy g6 = fresh(10);
	show("G6a", g6, 11.0, 30);
	gpu_busy_add(g6, 10.5, 11.2); gpu_busy_add(g6, 11.1, 11.3);
	show("G6b", g6, 12.0, 20);

	gpu_busy g7 = fresh(10);
	gpu_busy_add(g7, 10.5, 10.8); gpu_busy_add(g7, 10.1, 10.3);
	show("G7", g7, 11.0, 0);

	gpu_busy g8 = fresh(10);
	gpu_busy_add(g8, 9.9, 10.5); gpu_busy_add(g8, 10.4, 11.0);
	show("G8", g8, 11.0, 50);

	gpu_busy g9 = fresh(10);
	int over = 0;
	int under = 0;

	for (int i = 0; i < 1000; i++)
	{
		const double w = 10.0 + i;
		s_x = s_x * 6364136223846793005ull + 1442695040888963407ull;
		const int spans = 1 + static_cast<int>(s_x % 20);

		for (int k = 0; k < spans; k++)
		{
			const double start = w - 0.5 + 1.5 * next_u();
			const double end = std::fmin(start + 0.3 * next_u(), w + 1.0);
			gpu_busy_add(g9, start, end);
		}

		const gpu_busy_tick t = gpu_busy_take(g9, w + 1.0, 30);
		over += t.gpu_pct > 100.0 + 1e-9;
		under += t.gpu_pct < 0;
	}

	std::printf("G9 windows=1000 over=%d under=%d\n", over, under);
	return 0;
}
'''


def read(path):
    return without_comments(Path(path).read_text(encoding="utf-8"))


def hook_problems(diag):
    if "constexpr bool k_gpu_time_hook" not in diag:
        return ["hook: no constexpr bool k_gpu_time_hook"]
    body = function_body(diag, "install_gpu_time_hook")
    if body is None:
        return ["hook: no single install_gpu_time_hook definition"]
    swap = body.find("class_replaceMethod(")
    switch = body.find("k_gpu_time_hook")
    keep = body.find("class_getMethodImplementation(")
    if swap < 0 or switch < 0 or switch > swap or keep < 0 or keep > swap:
        return ["hook: k_gpu_time_hook or class_getMethodImplementation( is absent or after the first class_replaceMethod("]
    return []


def guard_problems(diag):
    body = function_body(diag, "gpu_commit")
    if body is None or "k_mtl_status_committed" not in body or "t_in_commit" not in body:
        return ["guard: gpu_commit does not name k_mtl_status_committed and t_in_commit"]
    return []


def handler_problems(diag):
    body = function_body(diag, "on_gpu_complete")
    if body is None or "gpu_busy_add(" not in body or "k_mtl_status_completed" not in body:
        return ["handler: on_gpu_complete does not call gpu_busy_add( behind k_mtl_status_completed"]
    banned = [word for word in ("diag_log.", "logs::", "Emu.", "fmt::", "std::string") if word in body]
    if banned:
        return ["handler: on_gpu_complete names " + ", ".join(banned)]
    return []


def take_problems(diag):
    body = function_body(diag, "perf_loop")
    if body is None or "gpu_busy_take(" not in body or "s_gpu_mutex" not in body:
        return ["take: perf_loop does not call gpu_busy_take( under s_gpu_mutex"]
    return []


class TheUnionMatchesTheDefinitions(unittest.TestCase):
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
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("G")]

    def test_series(self):
        self.assertEqual(0, self.code, self.out[-4000:])
        self.assertEqual(EXPECTED, self.lines)


class TheHookReachesThePerfLine(unittest.TestCase):
    def test_hook(self):
        self.assertEqual([], hook_problems(read(DIAG_CPP)))

    def test_guard(self):
        self.assertEqual([], guard_problems(read(DIAG_CPP)))

    def test_handler(self):
        self.assertEqual([], handler_problems(read(DIAG_CPP)))

    def test_take(self):
        self.assertEqual([], take_problems(read(DIAG_CPP)))


class EachCheckCanFail(unittest.TestCase):
    def edited_body(self, name, old, new):
        diag = read(DIAG_CPP)
        body = function_body(diag, name)
        self.assertIsNotNone(body)
        self.assertIn(old, body)
        return diag.replace(body, body.replace(old, new))

    def test_hook_switch_removed(self):
        self.assertTrue(hook_problems(self.edited_body("install_gpu_time_hook", "k_gpu_time_hook", "true")))

    def test_guard_status_check_broken(self):
        self.assertTrue(guard_problems(self.edited_body("gpu_commit", "k_mtl_status_committed", "9")))

    def test_handler_logs(self):
        diag = read(DIAG_CPP)
        body = function_body(diag, "on_gpu_complete")
        self.assertIsNotNone(body)
        self.assertTrue(handler_problems(diag.replace(body, ' diag_log.notice("x");' + body)))

    def test_take_removed(self):
        self.assertTrue(take_problems(self.edited_body("perf_loop", "gpu_busy_take(", "gpu_busy_peek(")))


if __name__ == "__main__":
    unittest.main()
