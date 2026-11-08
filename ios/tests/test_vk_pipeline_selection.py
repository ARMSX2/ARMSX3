# SPDX-License-Identifier: GPL-2.0-only
"""vk::get_pipe_compiler, cut from VKPipelineCompiler.cpp and run in a host mock, picks the worker with the fewest pending
jobs in five scenarios where upstream's round robin fails. It does not run a title or the real pipe compiler."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_body, without_comments_log_ring_contract as without_comments

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "rpcs3" / "Emu" / "RSX" / "VK" / "VKPipelineCompiler.cpp"

EXPECTED = [
    "S1 0 1 2 3 0 1 2 3",
    "S2 1 1 3 3",
    "S3 1 1 1 1",
    "S4 1 1 0",
    "S5 0 1 2 0",
]

ROUND_ROBIN = """
	ensure(g_pipe_compilers);
	int thread_index = g_compiler_index++;

	return g_pipe_compilers.get()->begin() + (thread_index % g_num_pipe_compilers);
"""

MOCK = r"""
#include <cstdint>
#include <cstdio>
#include <memory>
#include <type_traits>
using u32 = uint32_t;
constexpr u32 umax = 0xffffffffu;
template <class T> void ensure(const T&) {}
template <class T> struct atomic_t
{
	T v{};
	T operator++(int) { using U = std::make_unsigned_t<T>; T o = v; v = static_cast<T>(static_cast<U>(v) + 1u); return o; }
};
namespace vk
{
	struct pipe_compiler { u32 load = 0; u32 pending_jobs() const { return load; } };
	struct element : pipe_compiler { char pad[37]; };
	struct group { element e[4]; element* begin() { return e; } };
	std::unique_ptr<group> g_pipe_compilers;
	int g_num_pipe_compilers = 0;
	atomic_t<int> g_compiler_index;
	pipe_compiler* get_pipe_compiler()
	{
@BODY@
	}
}
static void scenario(const char* name, const u32 (&loads)[4], int visible, int start, int calls, bool raise)
{
	using namespace vk;
	g_pipe_compilers = std::make_unique<group>();
	for (int i = 0; i < 4; i++) g_pipe_compilers->e[i].load = loads[i];
	g_num_pipe_compilers = visible;
	g_compiler_index.v = start;
	std::printf("%s", name);
	for (int c = 0; c < calls; c++)
	{
		pipe_compiler* p = get_pipe_compiler();
		int idx = -1;
		for (int i = 0; i < 4; i++) if (static_cast<pipe_compiler*>(&g_pipe_compilers->e[i]) == p) idx = i;
		if (idx < 0) { std::printf(" ?"); continue; }
		std::printf(" %d", idx);
		if (raise) g_pipe_compilers->e[idx].load++;
	}
	std::printf("\n");
}
int main()
{
	scenario("S1", {0, 0, 0, 0}, 4, 0, 8, false);
	scenario("S2", {3, 1, 2, 1}, 4, 0, 4, false);
	scenario("S3", {5, 4, 0, 0}, 2, 0, 4, false);
	scenario("S4", {2, 0, 0, 0}, 2, 0, 3, true);
	scenario("S5", {0, 0, 0, 0}, 3, 2147483646, 4, false);
}
"""


def clang_available():
    if shutil.which("xcrun") is None:
        return False
    try:
        return subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], capture_output=True).returncode == 0
    except OSError:
        return False


def run_body(body):
    """Compile the body inside the mock and return the lines starting with S, or raise on a compile failure."""
    if shutil.which("xcrun") is None:
        raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "mock.cpp"
        exe = Path(tmp) / "mock"
        src.write_text(MOCK.replace("@BODY@", body))
        build = subprocess.run(["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-o", str(exe), str(src)],
                               capture_output=True, text=True)
        if build.returncode != 0:
            raise AssertionError("mock did not compile:\n" + build.stderr)
        out = subprocess.run([str(exe)], capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line.startswith("S")]


@unittest.skipUnless(clang_available(), "no clang++ via xcrun; a skip is not a pass")
class SelectionTest(unittest.TestCase):
    def test_TheSelectionMatchesTheScenarios(self):
        body = function_body(without_comments(SOURCE.read_text()), "get_pipe_compiler")
        self.assertIsNotNone(body, "get_pipe_compiler has no single definition")
        self.assertEqual(run_body(body), EXPECTED)

    def test_RoundRobinFails(self):
        self.assertNotEqual(run_body(ROUND_ROBIN), EXPECTED)


if __name__ == "__main__":
    unittest.main()
