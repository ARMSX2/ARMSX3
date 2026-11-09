// SPDX-License-Identifier: GPL-2.0-only

#include "ios_jit_dual_map_private.h"

#if defined(__APPLE__) && defined(ARCH_ARM64)

namespace jit_dual_map_internal
{

	// universal.js parses x0, x1 and pc from five bytes and writes pc + 4 back, so the buffer and the request's code must sit below 2^40.
	constexpr unsigned long long k_script_addr_limit = 1ull << 40;
	static_assert(k_script_budget_ms >= ARMSX3_JIT_BUDGET_MIN_MS && k_script_budget_ms <= ARMSX3_JIT_BUDGET_MAX_MS && ARMSX3_JIT_BUDGET_MAX_MS < 100000, "the default is a value the store could hold, and T renders in at most five digits");

	// TXM lets a page execute only after a debugger has written it, so this script has StikDebug write one byte per page.
	// Emitters then write code through the alias at g_code_rw_offset, because TXM keeps write and execute addresses apart.
	// Writing through the execute address faults, and the kernel kills the process for it.
	constexpr char k_script_format[] =
		"(function(){var B=0x%llx,R=0x%llx,L=%4d,T=%d,P=16384,n=0,r=0,q=0,c=0,t,a,i,S=[%s];"
		"function h(v){var s='',k;"
		"for(k=0;k<8;k++){s+=(v%%256).toString(16).padStart(2,'0');"
		"v=Math.floor(v/256)}return s}function w(x,s){return send_command('M'+x.toString(16)+','+(s.length/2).toString(16)+':'+s)}w(R,h(0x5354415254)+h(B)+h(L));"
		"t=Date.now();"
		"o:for(i=0;i<S.length;i+=2)for(a=S[i];a<S[i+1];a+=P){if(Date.now()-t>=T){c=1;"
		"break o}if(send_command('M'+(B+a).toString(16)+',1:69')=='OK')n++;"
		"else{if(!r)q=a;"
		"r++}}w(R+24,h(0x46494e495348)+h(n)+h(r)+h(q)+h(Date.now()-t)+h(c))})()";

	static_assert(k_script_x1 == 1000 && k_script_buffer_size == 0x1000, "universal.js L143 reads m<x0>,<x1> with x1 in decimal, so x1 = 1000 reads exactly this 0x1000-byte buffer");
	// The partial prepare's three ranges rest on this layout.
	static_assert(k_pool1_off + k_pool1_size == k_pool3_off, "pool 1 and pool 3 are adjacent, so one range covers both");
	static_assert(k_prep_pool2_bytes % 0x200000ull == 0 && k_prep_pool2_bytes <= k_pool2_size, "pool 2's prepare is a 2 MiB multiple inside pool 2");
	static_assert(k_prep_pool3_bytes % k_pool3_granule == 0 && k_prep_pool3_bytes <= k_pool3_size, "pool 3's prepare is whole granules inside pool 3");
	static_assert(k_pool3_off + k_prep_pool3_bytes <= k_stub_off, "pool 3's prepare ends below the stub pages");
	static_assert(k_prep_pool2_small % 0x200000ull == 0 && k_prep_pool2_small <= k_prep_pool2_bytes, "the small pool-2 prepare is a 2 MiB multiple no larger than the normal one");
	static_assert(k_prep_pool3_small % k_pool3_granule == 0 && k_prep_pool3_small <= k_prep_pool3_bytes, "the small pool-3 prepare is whole granules no larger than the normal one");
	// What the rendered walk relies on.
	static_assert(k_exec_half_size < 0x100000000ull && k_pool1_off == 0x40000000ull && k_stub_off == 0x7fff8000ull && k_stub_new_off == k_stub_off + 0x4000ull && k_exec_half_size == k_stub_new_off + 0x4000ull, "the walk renders 32-bit offsets, and its first three ranges hold every page the readbacks and the stubs use");

	// What the walk and the cap rely on; the default shares live in ios_jit_split.h.
	static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) <= k_prep_pool2_bytes, "pool 2's default share is a 2 MiB multiple inside its normal prepare");
	static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) <= k_prep_pool3_bytes, "pool 3's default share is whole granules inside its normal prepare");
	static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB) <= k_prep_pool2_small, "pool 2's floor is a 2 MiB multiple inside its small prepare");
	static_assert(ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR3_MIB) % ARMSX3_JIT_SPLIT_ROUND == 0 && ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR3_MIB) <= k_prep_pool3_small, "pool 3's floor is whole granules inside its small prepare");
	static_assert(ARMSX3_JIT_SPLIT_ROUND % k_pool3_granule == 0 && ARMSX3_JIT_SPLIT_PAGE == 0x4000ull && k_pool3_granule % ARMSX3_JIT_SPLIT_PAGE == 0, "the split rounds to whole pool 3 granules, each whole script pages (P=16384)");
	// Pool 2's first 16 MiB, walked first. Every share and prepare is at least this, so the ranges after it stay in order.
	constexpr unsigned long long k_walk_pool2_floor = 0x1000000ull;
	static_assert(k_walk_pool2_floor == ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_WALK2_MIB) && k_walk_pool2_floor <= ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB) && k_walk_pool2_floor % ARMSX3_JIT_SPLIT_ROUND == 0, "the walk's pool-2 floor is the split's, inside pool 2's smallest share and prepare");

	// The six [begin, end) ranges the script walks, most-needed first; the script and the cap both read this list.
	void script_walk(unsigned long long pool2_bytes, unsigned long long pool3_bytes, unsigned long long share2, unsigned long long share3, unsigned long long* w)
	{
		w[0] = 0;
		w[1] = k_walk_pool2_floor;
		w[2] = k_stub_off;
		w[3] = k_exec_half_size;
		w[4] = k_pool1_off;
		w[5] = k_pool3_off + share3;
		w[6] = k_walk_pool2_floor;
		w[7] = share2;
		w[8] = k_pool3_off + share3;
		w[9] = k_pool3_off + pool3_bytes;
		w[10] = share2;
		w[11] = pool2_bytes;
	}

	// The pool extents the walk's first pages prove: only pages contiguous from a pool's start count, and pool 3 in whole granules.
	void proven_extents(const unsigned long long* w, long long pages, long long stop_off, unsigned long long* pool2_bytes, unsigned long long* pool3_bytes)
	{
		unsigned long long end2 = 0;
		unsigned long long end3 = k_pool3_off;

		for (int i = 0; i < k_script_walk_words; i += 2)
		{
			for (unsigned long long a = w[i]; a < w[i + 1] && pages > 0; a += 0x4000ull)
			{
				// The first refused page ends the proven prefix; -1 (no refusal) never matches.
				if (static_cast<long long>(a) == stop_off)
				{
					pages = 0;
					break;
				}

				pages--;

				if (a == end2 && a < k_pool2_size)
				{
					end2 = a + 0x4000ull;
				}

				if (a == end3 && a < k_pool3_off + k_pool3_size)
				{
					end3 = a + 0x4000ull;
				}
			}
		}

		*pool2_bytes = end2;
		*pool3_bytes = (end3 - k_pool3_off) / k_pool3_granule * k_pool3_granule;
	}

	// Static storage, so zero-filled: the text's NUL and the rest of the 0x1000 bytes are zero.
	alignas(16) char s_script_text[k_script_buffer_size];

	// The budget T renders and the split uses; read_pool_history() sets it before build_prepare_script runs.
	int s_script_budget_ms = k_script_budget_ms;

	// volatile, since only the debugger writes it and the compiler could fold the reads to zero.
	volatile unsigned long long s_script_report[k_script_report_words];

	// The second pass's offer, in-flight flag, trigger and log line.
	std::atomic<int>  s_second_offer{0};
	// Set while a pass runs and cleared on every exit; a waiting pool 3 carve re-arms its deadline meanwhile.
	std::atomic<bool> s_second_pass_in_flight{false};
	int               s_second_trigger = 0;
	std::atomic<bool> s_second_line_ready{false};
	char              s_second_line[320];

	// How much longer a pool 3 carve may wait during a second pass, re-armed one wait at a time so none waits forever.
	constexpr unsigned long long k_second_pass_margin_ms = 15000;
	static_assert(k_carve_second_pass_extra_ms == k_second_pass_wait_ms + k_script_budget_ms + k_second_pass_margin_ms, "the extension is the pass's attach wait, the default budget and the margin");
	static_assert(k_carve_second_pass_extra_ms % utils::jit_pool3_carve_wait_ms == 0, "the extension is whole carve waits");
	static_assert(ARMSX3_JIT_BUDGET_MAX_MS <= k_script_budget_ms + k_second_pass_margin_ms, "a stored budget still ends inside the extension");

	// Builds the text into s_script_text: nullptr when built, else the refusal word; *len once measured.
	const char* build_prepare_script(unsigned long long base, const unsigned long long* walk, long long* len)
	{
		if (reinterpret_cast<unsigned long long>(s_script_text) + k_script_buffer_size > k_script_addr_limit
			|| reinterpret_cast<unsigned long long>(&jit_send_script_universal) >= k_script_addr_limit)
		{
			return "refused-address";
		}

		// The walk, comma-separated; each offset is "0x" and at most eight hex digits.
		char offsets[k_script_walk_words * 11];
		int at = 0;

		for (int i = 0; i < k_script_walk_words; ++i)
		{
			at += std::snprintf(offsets + at, sizeof(offsets) - at, "0x%llx", walk[i]);

			if (i + 1 < k_script_walk_words)
			{
				offsets[at++] = ',';
			}
		}

		const unsigned long long report = reinterpret_cast<unsigned long long>(s_script_report);
		const int n = std::snprintf(nullptr, 0, k_script_format, base, report, 0, s_script_budget_ms, offsets);
		*len = n;

		if (n < 0 || static_cast<std::size_t>(n) >= k_script_x1)
		{
			return "refused-length";
		}

		if (std::snprintf(s_script_text, sizeof(s_script_text), k_script_format, base, report, n, s_script_budget_ms, offsets) != n)
		{
			return "refused-length";
		}

		return nullptr;
	}

	void* prepare_worker(void* raw)
	{
		prepare_args* a = static_cast<prepare_args*>(raw);

		if (sigsetjmp(s_brk_jmp, 1) == 0)
		{
			jit_send_script_universal(s_script_text, k_script_x1);
			a->ok.store(1);
		}
		else
		{
			a->trapped.store(1);
		}

		a->done.store(1);

		// The detach, under its own guard, whether the prepare returned or trapped; with no debugger it traps and is caught.
		int unclaimed = 0;

		if (!a->claim.compare_exchange_strong(unclaimed, 1))
		{
			return nullptr;
		}

		// The before call: while the debugger is still attached, and only when the prepare returned.
		if (a->ok.load() && a->stub_guarded.load())
		{
			// Read back before the stub is planted: a page start the prepare wrote reads 0x69, an unwritten one 0x00; a fault is caught.
			unsigned char* const exec = static_cast<unsigned char*>(a->addr);
			a->readback_exec_0x0.store(read_guarded_byte(exec + 0x0ull));
			a->readback_exec_0x40000000.store(read_guarded_byte(exec + 0x40000000ull));
			a->readback_exec_0x7fff8000.store(read_guarded_byte(exec + 0x7fff8000ull));
			a->readback_exec_0x7fffc000.store(read_guarded_byte(exec + 0x7fffc000ull));
			a->readback_exec_0x1.store(read_guarded_byte(exec + 0x1ull));
			a->readback_exec_0x40000340.store(read_guarded_byte(exec + 0x40000340ull));
			a->readback_alias_0x0.store(read_guarded_byte(utils::jit_rw(exec + 0x0ull)));
			a->readback_alias_0x7fff8000.store(read_guarded_byte(utils::jit_rw(exec + 0x7fff8000ull)));

			plant_stub(static_cast<unsigned char*>(a->addr) + k_stub_off);
			run_stub(&a->before, static_cast<unsigned char*>(a->addr) + k_stub_off);
		}

		a->traced_before.store(process_is_traced());

		const unsigned long long t0 = mono_ms();

		if (sigsetjmp(s_brk_jmp, 1) == 0)
		{
			jit_detach_universal();
			a->detach_ok.store(1);
		}

		a->detach_ms.store(mono_ms() - t0);
		a->detach_done.store(1);
		return nullptr;
	}

	unsigned long long mono_ms()
	{
		struct timespec ts;
		clock_gettime(CLOCK_MONOTONIC, &ts);
		return static_cast<unsigned long long>(ts.tv_sec) * 1000ull
			+ static_cast<unsigned long long>(ts.tv_nsec) / 1000000ull;
	}
	static_assert(ARMSX3_JIT_BUDGET_MAX_MS < k_prepare_timeout_polls * k_prepare_poll_ms, "a stored budget always ends before the app stops waiting for the prepare");

	// Polls P_TRACED, never csops: CS_DEBUGGED stays set after a detach.
	bool wait_for_debugger(int budget_ms)
	{
		for (int waited = 0; waited < budget_ms; waited += k_prepare_poll_ms)
		{
			if (process_is_traced() == 1)
			{
				return true;
			}

			struct timespec ts;
			ts.tv_sec = 0;
			ts.tv_nsec = static_cast<long>(k_prepare_poll_ms) * 1000000L;
			nanosleep(&ts, nullptr);
		}

		return process_is_traced() == 1;
	}

	// The launcher's armsx3_jit_enabler_prepare, reached through dlsym; void* keeps its Objective-C types out of this unit.
	using builtin_enabler_prepare_fn = int (*)(u64 base, u64 size, s32 pid, void* result);
}

#endif
