// SPDX-License-Identifier: GPL-2.0-only
// Runs during static initialisation, so it reaches for nothing in the emulator and never throws: failures abort at the cause.
#include "ios_jit_dual_map_private.h"

namespace utils
{
	// Zero until the block is built, so jit_rw() is the identity for anything that runs before it.
	ptrdiff_t g_code_rw_offset = 0;

	// Pool 2's usable code bytes: the whole half unless a partial prepare covered less.
	usz g_pool2_code_limit = 0x40000000;

	// Outside both arms because it has no platform term; called from a signal handler, so string constants only.
	const char* jit_view_name(jit_view view) noexcept
	{
		switch (view)
		{
		case jit_view::execute: return "execute";
		case jit_view::alias:   return "alias";
		case jit_view::data:    return "data";
		case jit_view::outside: return "outside";
		default:                return "invalid";
		}
	}
}

#if defined(__APPLE__) && defined(ARCH_ARM64)

namespace jit_dual_map_internal
{
	// The block geometry the device probes measured; do not round or rearrange it.
	constexpr unsigned long long k_block_size = 0x140000000ull; // block_size_bytes, 5 GiB
	constexpr unsigned long long k_alias_off      = 0x80000000ull;  // alias_offset
	constexpr unsigned long long k_alias_size     = 0x80000000ull;  // alias_size
	constexpr unsigned long long k_data_off       = 0x100000000ull; // data_slot_offset
	constexpr unsigned long long k_data_size      = 0x40000000ull;  // data_slot_size

	constexpr unsigned long long k_pool2_off  = 0x0ull;         // pool2_code_offset
	constexpr unsigned k_pool3_granules =
		static_cast<unsigned>(k_pool3_size / k_pool3_granule);

	// The alias offset and the global delta are the same number by construction.
	constexpr long long k_code_rw_offset = 0x80000000ll;

	// 4 GB devices have a jetsam limit near 2.2 GB, so below this much available the prepare shrinks to the small sizes.
	constexpr unsigned long long k_prep_small_below = 0xC0000000ull;   // 3 GiB available at launch

	// MAP_NORESERVE is taken the way vm_native.cpp takes it, never assumed present.
#ifdef MAP_NORESERVE
	constexpr int k_map_noreserve = MAP_NORESERVE;
#else
	constexpr int k_map_noreserve = 0;
#endif

	// One object, built once from a function-local static, so the prepare lands before any emission.
	struct dual_map_block
	{
		bool               active = false;
		unsigned char*     base = nullptr;
		unsigned char*     alias = nullptr;

		int                handshake_performed = 0;
		unsigned long long handshake_ms = 0;
		const char*        handshake_outcome = "skipped";

		// Stays skipped until prepare_whole_executable_half() records otherwise.
		const char*        detach_outcome = "skipped";
		unsigned long long detach_ms = 0;
		int                traced_before_detach = -1;
		int                traced_after_detach = -1;

		// Stays skipped until prepare_whole_executable_half() records otherwise.
		utils::jit_stub_call_report stub_before{"skipped", -1, -1, -1, -1, -1};
		utils::jit_stub_call_report stub_after{"skipped", -1, -1, -1, -1, -1};
		utils::jit_stub_call_report stub_after_new{"skipped", -1, -1, -1, -1, -1};

		// Stays k_readback_skipped until prepare_whole_executable_half() records otherwise.
		long long readback_exec_0x0 = k_readback_skipped;
		long long readback_exec_0x40000000 = k_readback_skipped;
		long long readback_exec_0x7fff8000 = k_readback_skipped;
		long long readback_exec_0x7fffc000 = k_readback_skipped;
		long long readback_exec_0x1 = k_readback_skipped;
		long long readback_exec_0x40000340 = k_readback_skipped;
		long long readback_alias_0x0 = k_readback_skipped;
		long long readback_alias_0x7fff8000 = k_readback_skipped;

		// Page starts reading 0x69 after an OK built-in prepare, and the first that does not.
		long long readback_exec_pages_0x69 = k_readback_skipped;
		long long readback_exec_first_miss_off = k_readback_skipped;

		// Stays skipped until prepare_whole_executable_half() records otherwise.
		const char* script_outcome = "skipped";
		long long   script_len = -1;
		long long   script_pages_ok = -1;
		long long   script_pages_refused = -1;
		long long   script_first_refused_off = -1;
		long long   script_elapsed_ms = -1;
		long long   script_budget_hit = -1;

		// What the launch-time switch saw; skipped when it never ran.
		const char* enabler_selector = "skipped";
		const char* enabler_reason = "skipped";

		dual_map_block()
		{
			if (utils::jit_backend() != utils::jit_backend_kind::write_alias)
			{
				// Not an error: JITASM.cpp falls back to upstream's memory_reserve on a null carve.
				utils::stderr_witness_printf(
					"ARMSX3: JIT dual mapping not built -- rung is '%s', not 'write_alias'. The "
					"AsmJIT pools use upstream's reservations.\n",
					utils::jit_backend_name(utils::jit_backend()));
				return;
			}

			build();
		}

		void build()
		{
			// Created with execute: a PROT_NONE block's maximum is rw-, so execute could never be added later.
			void* got = ::mmap(nullptr, static_cast<std::size_t>(k_block_size),
				PROT_READ | PROT_EXEC,
				MAP_ANON | MAP_PRIVATE | k_map_noreserve, -1, 0);

			if (got == MAP_FAILED)
			{
				abort_at_the_cause("the 5 GiB PROT_READ|PROT_EXEC reservation was refused",
					"errno", static_cast<long long>(errno));
			}

			base = static_cast<unsigned char*>(got);

			// vm_remap, not mach_vm_remap, which the iOS SDK refuses; copy = false makes it an alias rather than a snapshot.
			vm_address_t  target = reinterpret_cast<vm_address_t>(base + k_alias_off);
			vm_prot_t     cur = 0;
			vm_prot_t     max = 0;

			const kern_return_t kr = vm_remap(mach_task_self(), &target,
				static_cast<vm_size_t>(k_alias_size), 0,
				VM_FLAGS_FIXED | VM_FLAGS_OVERWRITE,
				mach_task_self(), reinterpret_cast<vm_address_t>(base), /*copy=*/false,
				&cur, &max, VM_INHERIT_DEFAULT);

			if (kr != KERN_SUCCESS)
			{
				abort_at_the_cause("vm_remap refused the writable alias",
					kern_return_name(kr), static_cast<long long>(kr));
			}

			if (reinterpret_cast<unsigned char*>(target) != base + k_alias_off)
			{
				abort_at_the_cause("VM_FLAGS_FIXED did not honour the alias address",
					"delta", static_cast<long long>(
						reinterpret_cast<unsigned char*>(target) - (base + k_alias_off)));
			}

			alias = reinterpret_cast<unsigned char*>(target);

			// Checked first, so the message names the cause rather than the symptom.
			if ((max & VM_PROT_WRITE) == 0)
			{
				abort_at_the_cause("the alias inherited a maximum protection without write",
					"max_protection", static_cast<long long>(max));
			}

			// Never mprotect the executable half: one call asking for write there strips execute for good and still reports success.
			// These two calls touch only the alias and the data half, once, so jit_dual_map_commit never has to call mprotect later.
			// Code is written through the alias instead, so no page is ever writable and executable at once.
			if (::mprotect(alias, static_cast<std::size_t>(k_alias_size),
					PROT_READ | PROT_WRITE) != 0)
			{
				abort_at_the_cause("mprotect could not make the alias writable",
					"errno", static_cast<long long>(errno));
			}

			if (::mprotect(base + k_data_off, static_cast<std::size_t>(k_data_size),
					PROT_READ | PROT_WRITE) != 0)
			{
				abort_at_the_cause("mprotect could not make pool 2's data half writable",
					"errno", static_cast<long long>(errno));
			}

			// The offset, and the check that it is the measured one.
			utils::g_code_rw_offset = static_cast<ptrdiff_t>(alias - base);

			if (utils::g_code_rw_offset != static_cast<ptrdiff_t>(k_code_rw_offset))
			{
				abort_at_the_cause("g_code_rw_offset is not the measured 0x80000000",
					"offset", static_cast<long long>(utils::g_code_rw_offset));
			}

			// mprotect succeeding is not the check; read the protection back.
			region_report alias_rr;
			region_probe(reinterpret_cast<unsigned long long>(alias), &alias_rr);

			if (!alias_rr.contained || (alias_rr.prot & VM_PROT_WRITE) == 0)
			{
				abort_at_the_cause("the alias did not read back writable",
					"conferred protection", static_cast<long long>(alias_rr.prot));
			}

			if ((alias_rr.prot & VM_PROT_EXECUTE) != 0)
			{
				// An executable alias would be a W+X page, the thing this rung exists to avoid.
				abort_at_the_cause("the alias read back EXECUTABLE, which is a W+X page",
					"conferred protection", static_cast<long long>(alias_rr.prot));
			}

			region_report exec_rr;
			region_probe(reinterpret_cast<unsigned long long>(base), &exec_rr);

			if (!exec_rr.contained || (exec_rr.prot & VM_PROT_EXECUTE) == 0)
			{
				abort_at_the_cause("the executable half did not read back executable",
					"conferred protection", static_cast<long long>(exec_rr.prot));
			}

			if ((exec_rr.prot & VM_PROT_WRITE) != 0)
			{
				abort_at_the_cause("the executable half read back WRITABLE, which is a W+X page",
					"conferred protection", static_cast<long long>(exec_rr.prot));
			}

			// The one prepare covers the whole executable half, here, before any carve is handed out and so before any emission.
			prepare_whole_executable_half();

			// Each readback byte as skipped, fault or 0xNN; all skipped on the host, where the prepare traps.
			char rb[8][8];
			char readbacks_line[96];
			std::snprintf(readbacks_line, sizeof(readbacks_line), "%s,%s,%s,%s,%s,%s,%s,%s",
				readback_tok(readback_exec_0x0, rb[0], sizeof(rb[0])),
				readback_tok(readback_exec_0x40000000, rb[1], sizeof(rb[1])),
				readback_tok(readback_exec_0x7fff8000, rb[2], sizeof(rb[2])),
				readback_tok(readback_exec_0x7fffc000, rb[3], sizeof(rb[3])),
				readback_tok(readback_exec_0x1, rb[4], sizeof(rb[4])),
				readback_tok(readback_exec_0x40000340, rb[5], sizeof(rb[5])),
				readback_tok(readback_alias_0x0, rb[6], sizeof(rb[6])),
				readback_tok(readback_alias_0x7fff8000, rb[7], sizeof(rb[7])));

			utils::stderr_witness_printf(
				"ARMSX3: JIT dual map established. block=%p size=0x%llx exec=[%p,+0x%llx) "
				"alias=%p offset=0x%llx exec_prot=%d/%d alias_prot=%d handshake=%s ms=%llu "
				"detach=%s detach_ms=%llu traced=%d->%d stubs=%s,%s,%s readbacks=%s\n",
				static_cast<void*>(base), k_block_size, static_cast<void*>(base),
				k_exec_half_size, static_cast<void*>(alias),
				static_cast<unsigned long long>(utils::g_code_rw_offset),
				exec_rr.prot, exec_rr.max_prot, alias_rr.prot,
				handshake_outcome, handshake_ms,
				detach_outcome, detach_ms, traced_before_detach, traced_after_detach,
				stub_before.outcome, stub_after.outcome, stub_after_new.outcome, readbacks_line);

			// No address is printed.
			utils::stderr_witness_printf(
				"ARMSX3: JIT dual map script request: outcome=%s len=%lld pages_ok=%lld pages_refused=%lld "
				"first_refused_off=%lld elapsed_ms=%lld budget_hit=%lld\n",
				script_outcome, script_len, script_pages_ok, script_pages_refused,
				script_first_refused_off, script_elapsed_ms, script_budget_hit);

			// Offsets only, no address; -1 unless a built-in prepare succeeded.
			utils::stderr_witness_printf(
				"ARMSX3: JIT dual map builtin probes: pages_0x69=%lld first_miss_off=%lld\n",
				readback_exec_pages_0x69, readback_exec_first_miss_off);

			// The launch-time switch's two words, never an address.
			utils::stderr_witness_printf("ARMSX3: JIT enabler switch: selector=%s reason=%s\n", enabler_selector, enabler_reason);
			// The prepare's sizes and the memory that chose them, in MiB.
			utils::stderr_witness_printf("ARMSX3: JIT prepare sizes: available_mib=%llu pool2_mib=%llu pool3_mib=%llu\n",
				available_at_launch >> 20, prep_pool2_bytes >> 20, prep_pool3_bytes >> 20);

			active = true;
		}

		// Runs once the request returned, when the debugger has finished writing the report.
		void read_script_report()
		{
			unsigned long long w[k_script_report_words];

			for (int i = 0; i < k_script_report_words; ++i)
			{
				w[i] = s_script_report[i];
			}

			if (w[0] != k_script_started)
			{
				// A debugger took the request and no script wrote its report: the outcome stays `not-run`.
				return;
			}

			if (w[1] != reinterpret_cast<unsigned long long>(base) || w[2] != static_cast<unsigned long long>(script_len))
			{
				script_outcome = "foreign";
				return;
			}

			if (w[3] != k_script_finished)
			{
				script_outcome = "started";
				return;
			}

			script_outcome = "finished";
			script_pages_ok = static_cast<long long>(w[4]);
			script_pages_refused = static_cast<long long>(w[5]);
			script_first_refused_off = w[5] != 0 ? static_cast<long long>(w[6]) : -1;
			script_elapsed_ms = static_cast<long long>(w[7]);
			script_budget_hit = static_cast<long long>(w[8]);
		}

		// The stored method picks the route; the launcher is found with dlsym, and any refusal falls back to the StikDebug request.
		bool prepare_through_builtin_enabler()
		{
			enabler_selector = "unset";

			if (process_is_traced() == 1)
			{
				enabler_reason = "already-traced";
				return false;
			}

			void* const plan_entry = ::dlsym(RTLD_DEFAULT, "armsx3_jit_launch_plan");
			const int plan = plan_entry != nullptr ? reinterpret_cast<launch_plan_fn>(plan_entry)() : 0;

			if ((plan & k_jit_plan_livecontainer) != 0 && (plan & k_jit_plan_method_mask) == 0)
			{
				enabler_reason = "livecontainer";
				return false;
			}

			if ((plan & k_jit_plan_method_mask) == k_jit_plan_stikdebug)
			{
				enabler_reason = "stikdebug-not-attached";
				return false;
			}

			if ((plan & k_jit_plan_method_mask) == k_jit_plan_wait)
			{
				enabler_reason = wait_for_debugger(k_wait_for_debugger_ms) ? "debugger-attached" : "no-debugger";
				return false;
			}

			void* const entry = ::dlsym(RTLD_DEFAULT, "armsx3_jit_enabler_prepare_ranges");

			if (entry == nullptr)
			{
				enabler_reason = "helper-absent";
				return false;
			}

			const auto prepare = reinterpret_cast<builtin_enabler_prepare_ranges_fn>(entry);
			// Three ranges in one attach, sized to the memory available.
			choose_prepare_sizes();
			const u64 b = reinterpret_cast<u64>(base);
			const u64 ranges[6] = {
				b, prep_pool2_bytes,                                  // pool 2's code, from its start
				b + k_pool1_off, k_pool1_size + prep_pool3_bytes,     // pool 1, then pool 3's start (adjacent)
				b + k_stub_off, k_exec_half_size - k_stub_off,        // the two stub pages the probes run
			};
			const int rc = prepare(ranges, 3, static_cast<s32>(::getpid()), nullptr);
			const bool ok = rc == k_builtin_enabler_ok;
			enabler_reason = ok ? "helper-ok" : rc == k_builtin_enabler_pairing_absent ? "pairing-absent" : "helper-failed";

			if (ok)
			{
				cap_pools_to_partial_prepare();
			}

			return ok;
		}

		// Read before main() through dlsym on every route; anything not exactly valid reads as no history.
		void read_pool_history()
		{
			void* const read_entry = ::dlsym(RTLD_DEFAULT, "armsx3_jit_pool_history_read");
			history_write = reinterpret_cast<history_write_fn>(::dlsym(RTLD_DEFAULT, "armsx3_jit_pool_history_write"));
			long long stored_budget_ms = 0;

			if (read_entry == nullptr || reinterpret_cast<history_read_fn>(read_entry)(&history, &stored_budget_ms) != 1
				|| history.magic != ARMSX3_JIT_HISTORY_MAGIC || history.count > ARMSX3_JIT_HISTORY_SESSIONS)
			{
				std::memset(&history, 0, sizeof history);
				history.magic = ARMSX3_JIT_HISTORY_MAGIC;
			}

			// The reader fills the budget even without history; with no reader (the Mac) it is 0.
			s_script_budget_ms = armsx3_jit_budget_ms(stored_budget_ms, k_script_budget_ms);
		}

		// Neither pool may hand out an unprepared page, since executing one is a SIGBUS; 0 available means no limit applies.
		void choose_prepare_sizes()
		{
#if TARGET_OS_IPHONE
			available_at_launch = static_cast<unsigned long long>(os_proc_available_memory());
#endif
			const bool small = available_at_launch != 0 && available_at_launch < k_prep_small_below;
			prep_pool2_bytes = small ? k_prep_pool2_small : k_prep_pool2_bytes;
			prep_pool3_bytes = small ? k_prep_pool3_small : k_prep_pool3_bytes;
			// The stored history moves those tiers and sets the StikDebug walk's first shares.
			read_pool_history();
			armsx3_jit_split_compute(&history, small ? 1 : 0, prep_pool2_bytes, prep_pool3_bytes, k_pool2_size,
				(k_stub_off - k_pool3_off) / k_pool3_granule * k_pool3_granule, (k_exec_half_size - k_stub_off + k_pool1_size) / 0x4000ull,
				static_cast<unsigned long long>(s_script_budget_ms), &split);
			prep_pool2_bytes = split.size2;
			prep_pool3_bytes = split.size3;
		}

		void cap_pools_to_partial_prepare()
		{
			utils::g_pool2_code_limit = prep_pool2_bytes;
			pool3_prepared_granules = static_cast<unsigned>(prep_pool3_bytes / k_pool3_granule);

			for (unsigned i = pool3_prepared_granules; i < k_pool3_granules; i++)
			{
				unit_taken[i] = true;
			}
		}

		// shortcut: a unit live past the cap gives its granules back only on release; the macOS test leg calls this before the boot.
		void cap_pool3_for_leg(unsigned mib)
		{
			const unsigned keep = static_cast<unsigned>(static_cast<unsigned long long>(mib) * 1024 * 1024 / k_pool3_granule);

			::pthread_mutex_lock(&unit_lock);

			if (mib != 0 && keep < pool3_prepared_granules)
			{
				for (unsigned i = keep; i < k_pool3_granules; i++)
				{
					unit_taken[i] = true;
				}

				pool3_prepared_granules = keep;

				if (unit_bytes != 0)
				{
					units_capacity = keep;
				}
			}

			::pthread_mutex_unlock(&unit_lock);
		}

		// After an OK built-in prepare: the readbacks and a whole-half count first, then the stubs once P_TRACED reads 0.
		void probe_after_builtin_prepare()
		{
			if (!install_stub_guard())
			{
				return;
			}

			readback_exec_0x0 = read_guarded_byte(base + 0x0ull);
			readback_exec_0x40000000 = read_guarded_byte(base + 0x40000000ull);
			readback_exec_0x7fff8000 = read_guarded_byte(base + 0x7fff8000ull);
			readback_exec_0x7fffc000 = read_guarded_byte(base + 0x7fffc000ull);
			readback_exec_0x1 = read_guarded_byte(base + 0x1ull);
			readback_exec_0x40000340 = read_guarded_byte(base + 0x40000340ull);
			readback_alias_0x0 = read_guarded_byte(utils::jit_rw(base + 0x0ull));
			readback_alias_0x7fff8000 = read_guarded_byte(utils::jit_rw(base + 0x7fff8000ull));

			long long pages_0x69 = 0;
			long long first_miss_off = k_readback_skipped;

			// Only the prepared ranges are read: touching an unprepared page through the execute view faults in a real page.
			const unsigned long long pool2_end = utils::g_pool2_code_limit;
			const unsigned long long pool3_end = k_pool3_off + static_cast<unsigned long long>(pool3_prepared_granules) * k_pool3_granule;

			for (unsigned long long off = 0; off < k_exec_half_size; off += 0x4000ull)
			{
				if ((off >= pool2_end && off < k_pool1_off) || (off >= pool3_end && off < k_stub_off))
				{
					off = off < k_pool1_off ? k_pool1_off - 0x4000ull : k_stub_off - 0x4000ull;
					continue;
				}

				if (read_guarded_byte(base + off) == 0x69)
				{
					++pages_0x69;
				}
				else if (first_miss_off == k_readback_skipped)
				{
					first_miss_off = static_cast<long long>(off);
				}
			}

			readback_exec_pages_0x69 = pages_0x69;
			readback_exec_first_miss_off = first_miss_off;

			if (process_is_traced() == 0)
			{
				static stub_call after;
				static stub_call after_new;

				plant_stub(base + k_stub_off);
				run_stub(&after, base + k_stub_off);
				plant_stub(base + k_stub_new_off);
				run_stub(&after_new, base + k_stub_new_off);

				const unsigned long long last0 = mono_ms();

				while ((stub_unresolved(after) || stub_unresolved(after_new)) && mono_ms() - last0 < k_stub_bound_ms)
				{
					struct timespec ts;
					ts.tv_sec = 0;
					ts.tv_nsec = k_stub_poll_ns;
					nanosleep(&ts, nullptr);
				}

				stub_after = snapshot_stub(after);
				stub_after_new = snapshot_stub(after_new);
			}

			restore_stub_guard();
		}

		// Sizes the StikDebug walk the way the built-in route does, and keeps it for the cap.
		const unsigned long long* stikdebug_walk()
		{
			choose_prepare_sizes();
			script_walk(prep_pool2_bytes, prep_pool3_bytes, split.share2, split.share3, script_walk_offsets);
			return script_walk_offsets;
		}

		void prepare_whole_executable_half()
		{
			// Only a successful built-in prepare skips the StikDebug route below.
			if (prepare_through_builtin_enabler())
			{
				probe_after_builtin_prepare();
				return;
			}

			// The text is built first, and a refusal makes no request and starts no worker.
			const char* const refused = build_prepare_script(reinterpret_cast<unsigned long long>(base), stikdebug_walk(), &script_len);

			if (refused != nullptr)
			{
				script_outcome = refused;
				handshake_outcome = "skipped-script";
				return;
			}

			struct sigaction sa;
			struct sigaction old;

			std::memset(&sa, 0, sizeof(sa));
			sa.sa_handler = brk_trap_handler;
			sigemptyset(&sa.sa_mask);

			if (::sigaction(SIGTRAP, &sa, &old) != 0)
			{
				// Never attempted, so recorded as skipped with the blocking step named.
				handshake_outcome = "skipped-sigtrap-handler";
				return;
			}

			// Static: the detached worker may still write here after this function returns.
			static prepare_args a;
			a.addr = base;
			a.done.store(0);
			a.ok.store(0);
			a.trapped.store(0);
			a.claim.store(0);
			a.detach_done.store(0);
			a.detach_ok.store(0);
			a.traced_before.store(-1);
			a.detach_ms.store(0);

			// The guard goes in before the worker starts, because its before call reads stub_guarded.
			a.stub_guarded.store(install_stub_guard() ? 1 : 0);

			pthread_t worker;
			const unsigned long long t0 = mono_ms();

			if (::pthread_create(&worker, nullptr, prepare_worker, &a) != 0)
			{
				if (a.stub_guarded.exchange(0))
				{
					restore_stub_guard();
				}

				::sigaction(SIGTRAP, &old, nullptr);
				handshake_outcome = "skipped-pthread_create";
				return;
			}

			::pthread_detach(worker);
			script_outcome = "not-run";

			for (int poll = 0; poll < k_prepare_timeout_polls; ++poll)
			{
				if (a.done.load())
				{
					break;
				}

				struct timespec ts;
				ts.tv_sec = 0;
				ts.tv_nsec = static_cast<long>(k_prepare_poll_ms) * 1000000L;
				nanosleep(&ts, nullptr);
			}

			handshake_ms = mono_ms() - t0;

			if (!a.done.load())
			{
				handshake_outcome = "timeout";

				// The report is not read: the debugger may still be writing it.
				script_outcome = "timeout";
			}
			else if (a.ok.load())
			{
				handshake_outcome = "ok";
				handshake_performed = 1;
				read_script_report();
				// Only the walked and proven prefix may be handed out.
				proven_extents(script_walk_offsets, std::strcmp(script_outcome, "finished") == 0 ? script_pages_ok : 0, script_first_refused_off, &prep_pool2_bytes, &prep_pool3_bytes);
				cap_pools_to_partial_prepare();
			}
			else
			{
				// Expected on a Mac, where no debugger listens; it says nothing about a device.
				handshake_outcome = "sigtrap";
			}

			// If this compare-exchange wins, the prepare never returned in time and no detach will be sent.
			int unclaimed = 0;

			if (!a.claim.compare_exchange_strong(unclaimed, 2))
			{
				const unsigned long long d0 = mono_ms();

				for (int poll = 0; poll < k_prepare_timeout_polls; ++poll)
				{
					if (a.detach_done.load())
					{
						break;
					}

					struct timespec ts;
					ts.tv_sec = 0;
					ts.tv_nsec = static_cast<long>(k_prepare_poll_ms) * 1000000L;
					nanosleep(&ts, nullptr);
				}

				if (a.detach_done.load())
				{
					// sigtrap is the expected answer on a Mac.
					detach_outcome = a.detach_ok.load() ? "ok" : "sigtrap";
					detach_ms = a.detach_ms.load();
				}
				else
				{
					// A hang is a result: recorded, and the boot continues.
					detach_outcome = "timeout";
					detach_ms = mono_ms() - d0;
				}

				traced_before_detach = a.traced_before.load();
				traced_after_detach = process_is_traced();
			}

			// Only once the prepare was taken and P_TRACED reads 0; then a fresh copy on the next page up.
			if (handshake_performed && traced_after_detach == 0 && a.stub_guarded.load())
			{
				run_stub(&a.after, base + k_stub_off);
				plant_stub(base + k_stub_new_off);
				run_stub(&a.after_new, base + k_stub_new_off);
			}

			if (a.stub_guarded.load())
			{
				// The last bounded wait, so a late fault still lands in the stub guard; a stale guard would take every later guest fault.
				const unsigned long long last0 = mono_ms();

				while ((stub_unresolved(a.before) || stub_unresolved(a.after) || stub_unresolved(a.after_new))
					&& mono_ms() - last0 < k_stub_bound_ms)
				{
					struct timespec ts;
					ts.tv_sec = 0;
					ts.tv_nsec = k_stub_poll_ns;
					nanosleep(&ts, nullptr);
				}

				stub_before = snapshot_stub(a.before);
				stub_after = snapshot_stub(a.after);
				stub_after_new = snapshot_stub(a.after_new);

				// The readbacks finished before the before call, which the wait above joined on.
				readback_exec_0x0 = a.readback_exec_0x0.load();
				readback_exec_0x40000000 = a.readback_exec_0x40000000.load();
				readback_exec_0x7fff8000 = a.readback_exec_0x7fff8000.load();
				readback_exec_0x7fffc000 = a.readback_exec_0x7fffc000.load();
				readback_exec_0x1 = a.readback_exec_0x1.load();
				readback_exec_0x40000340 = a.readback_exec_0x40000340.load();
				readback_alias_0x0 = a.readback_alias_0x0.load();
				readback_alias_0x7fff8000 = a.readback_alias_0x7fff8000.load();

				// stub_guarded goes to 0 first, so a worker that has not started its call starts none.
				a.stub_guarded.store(0);
				restore_stub_guard();
			}

			::sigaction(SIGTRAP, &old, nullptr);
		}

		// Pool 3 has many concurrent MemoryManager1 callers, so it is a granule bitmap kept outside the r-x memory it hands out.
		bool               unit_taken[k_pool3_granules] = {};
		unsigned           unit_granules[k_pool3_granules] = {};

		// Usable granules; the unprepared rest are marked taken and never handed out.
		unsigned           pool3_prepared_granules = k_pool3_granules;

		// What the built-in prepare covers and what it read to decide.
		unsigned long long prep_pool2_bytes = k_prep_pool2_bytes;
		unsigned long long prep_pool3_bytes = k_prep_pool3_bytes;
		unsigned long long available_at_launch = 0;
		// The StikDebug route's walk, kept for the cap.
		unsigned long long script_walk_offsets[k_script_walk_words] = {};
		// The stored history read before main(), the split it gave, and the store's writer.
		armsx3_jit_pool_history history{};
		armsx3_jit_split split{};
		history_write_fn history_write = nullptr;
		// This process's session maximum, its ring slot (-1 until the first put), and the rate taken.
		armsx3_jit_pool_session noted{};
		int history_slot = -1;
		bool rate_noted = false;

		// shortcut: only the perf thread calls this and it reads the unit counters unlocked; take the lock if a sanitizer flags it.
		void note_peaks(unsigned long long pool2_top, bool game_running)
		{
			if (!game_running)
			{
				return;
			}

			bool changed = false;

			if (!rate_noted)
			{
				rate_noted = true;

				if (std::strcmp(script_outcome, "finished") == 0 && script_budget_hit == 1 && script_pages_ok > 0 && script_elapsed_ms > 0)
				{
					history.rate_pages = static_cast<uint32_t>(script_pages_ok);
					history.rate_ms = static_cast<uint32_t>(script_elapsed_ms);
					changed = true;
				}
			}

			// Pool 2: the counter without its failure flag; a cap hit records the limit itself.
			const unsigned long long top2 = pool2_top & ~0x40000000ull;
			const bool hit2 = (pool2_top & 0x40000000ull) != 0 || top2 + 0x200000ull > utils::g_pool2_code_limit || utils::jit_pool_full_reason() == 1;
			// Pool 3's peak live granules; a cap hit or a full-pool stop records the prepared count, so the next launch grows it.
			const bool hit3 = carve_waits > 0 || units_peak >= pool3_prepared_granules || utils::jit_pool_full_reason() == 3;
			const unsigned long long below_stubs = (k_stub_off - k_pool3_off) / k_pool3_granule;
			const unsigned long long prepared3 = pool3_prepared_granules < below_stubs ? pool3_prepared_granules : below_stubs;
			armsx3_jit_pool_session now{};
			now.peak2_mib = static_cast<uint32_t>((hit2 ? utils::g_pool2_code_limit : top2) >> 20);
			now.peak3_mib = static_cast<uint32_t>(((hit3 ? prepared3 : units_peak) * k_pool3_granule) >> 20);
			now.hit2 = hit2 ? 1 : 0;
			now.hit3 = hit3 ? 1 : 0;

			if (armsx3_jit_session_merge(&noted, &now))
			{
				armsx3_jit_history_put(&history, &history_slot, &noted);
				changed = true;
			}

			if (changed && history_write != nullptr)
			{
				history_write(&history);
			}
		}

		// The second pass's per-session offer state; only the perf thread touches it.
		bool               offer_session_open = false;
		bool               offered_this_session = false;
		unsigned long long offer_waits_at_start = 0;

		// StikDebug relaunched this process traced and its detach left P_TRACED at 0, so a second attach can happen.
		bool stikdebug_route() const
		{
			return std::strcmp(enabler_reason, "already-traced") == 0 && std::strcmp(script_outcome, "finished") == 0
				&& traced_after_detach == 0;
		}

		// shortcut: one offer per session, and pool 3's counters are read without the unit lock, as note_peaks does.
		void check_offer(bool game_running)
		{
			if (!game_running)
			{
				offer_session_open = false;
				s_second_offer.store(0);
				return;
			}

			if (!offer_session_open)
			{
				offer_session_open = true;
				offered_this_session = false;
				offer_waits_at_start = carve_waits;
			}

			if (offered_this_session || !stikdebug_route() || s_second_pass_in_flight.load() || utils::jit_pool_full_reason() != 0)
			{
				return;
			}

			const unsigned below_stubs = static_cast<unsigned>((k_stub_off - k_pool3_off) / k_pool3_granule);
			const bool near3 = pool3_prepared_granules < below_stubs
				&& (carve_waits > offer_waits_at_start || units_live + 1 >= pool3_prepared_granules);
			const bool near2 = utils::g_pool2_code_limit < k_pool2_size && utils::jit_pool2_refused_blocks() != 0;
			const int trigger = near3 ? 3 : near2 ? 2 : 0;

			if (trigger != 0)
			{
				offered_this_session = true;
				s_second_offer.store(trigger);
			}
		}

		// Hands out only proven pages; pool 2's limit is a plain aligned store the core reads unlocked, raised only after the detach.
		void raise_caps(unsigned long long pool2_bytes, unsigned long long pool3_bytes)
		{
			const unsigned to = static_cast<unsigned>(pool3_bytes / k_pool3_granule);

			::pthread_mutex_lock(&unit_lock);

			if (to > pool3_prepared_granules)
			{
				for (unsigned i = pool3_prepared_granules; i < to; i++)
				{
					unit_taken[i] = false;
				}

				pool3_prepared_granules = to;

				if (unit_bytes != 0)
				{
					units_capacity = to;
				}

				::pthread_cond_broadcast(&unit_free);
			}

			::pthread_mutex_unlock(&unit_lock);

			if (pool2_bytes > __atomic_load_n(&utils::g_pool2_code_limit, __ATOMIC_RELAXED))
			{
				__atomic_store_n(&utils::g_pool2_code_limit, static_cast<usz>(pool2_bytes), __ATOMIC_RELEASE);
			}
		}

		unsigned long long units_carved = 0;
		unsigned long long units_released = 0;
		unsigned long long units_live = 0;
		unsigned long long units_peak = 0;
		unsigned long long units_capacity = 0;
		unsigned long long carve_waits = 0;
		unsigned long long unit_bytes = 0;

		// Static initialisers, so the mutex needs no constructor before the singleton is reached.
		pthread_mutex_t    unit_lock = PTHREAD_MUTEX_INITIALIZER;
		pthread_cond_t     unit_free = PTHREAD_COND_INITIALIZER;

		// Offsets inside the block only, never absolute addresses, so a pasted log leaks no ASLR slide.
		void print_unit_table_locked() const
		{
			utils::stderr_witness_printf(
				"ARMSX3: pool 3 unit table -- granule %llu bytes, %u granules, capacity %llu "
				"units of %llu bytes; carved %llu, released %llu, live %llu, peak %llu, "
				"waits %llu\n",
				k_pool3_granule, k_pool3_granules, units_capacity, unit_bytes,
				units_carved, units_released, units_live, units_peak, carve_waits);

			for (unsigned i = 0; i < k_pool3_granules; i++)
			{
				if (unit_granules[i] != 0)
				{
					utils::stderr_witness_printf(
						"ARMSX3:   unit at pool3+0x%llx, %u granules (%llu bytes)\n",
						static_cast<unsigned long long>(i) * k_pool3_granule,
						unit_granules[i],
						static_cast<unsigned long long>(unit_granules[i]) * k_pool3_granule);
				}
			}

		}

		// First fit over the granule bitmap; k_pool3_granules when nothing fits now. Caller holds unit_lock.
		unsigned first_fit_locked(unsigned want) const
		{
			if (want == 0 || want > k_pool3_granules)
			{
				return k_pool3_granules;
			}

			for (unsigned start = 0; start + want <= k_pool3_granules; start++)
			{
				unsigned run = 0;

				while (run < want && !unit_taken[start + run])
				{
					run++;
				}

				if (run == want)
				{
					return start;
				}

				// Skip the whole taken span rather than re-walking it one granule at a time.
				start += run;
			}

			return k_pool3_granules;
		}

		// Returns the unit's execute base; a worker parks and the game halts with -28 when pool 3 stays full past the wait.
		unsigned char* carve_llvm_unit(unsigned long long size)
		{
			if (size == 0 || size > k_pool3_size)
			{
				abort_at_the_cause(
					"an LLVM memory manager asked pool 3 for a unit larger than the measured "
					"carve", "requested bytes", static_cast<long long>(size));
			}

			const unsigned want = static_cast<unsigned>(
				(size + k_pool3_granule - 1) / k_pool3_granule);

			// Destroyed after every unlock below, so a thread jit_pool_full marked parks with no lock held.
			utils::jit_pool_full_parker park_after_unlock;

			::pthread_mutex_lock(&unit_lock);

			// Capacity is set at the first carve and never recomputed; units vary in size, so the counts are in 2 MiB granules.
			if (unit_bytes == 0)
			{
				unit_bytes = k_pool3_granule;
				units_capacity = pool3_prepared_granules;
			}

			unsigned at = first_fit_locked(want);

			if (at == k_pool3_granules)
			{
				carve_waits++;

				struct timespec deadline;
				::clock_gettime(CLOCK_REALTIME, &deadline);
				deadline.tv_sec += static_cast<time_t>(utils::jit_pool3_carve_wait_ms / 1000);
				unsigned long long rearmed_ms = 0;

				while (at == k_pool3_granules)
				{
					const int rc = ::pthread_cond_timedwait(&unit_free, &unit_lock, &deadline);

					at = first_fit_locked(want);

					if (at != k_pool3_granules)
					{
						break;
					}

					if (rc == ETIMEDOUT)
					{
						// A second StikDebug pass may still be running: wait one more carve wait, at most k_carve_second_pass_extra_ms in all.
						if (s_second_pass_in_flight.load() && rearmed_ms < k_carve_second_pass_extra_ms)
						{
							rearmed_ms += utils::jit_pool3_carve_wait_ms;
							::clock_gettime(CLOCK_REALTIME, &deadline);
							deadline.tv_sec += static_cast<time_t>(utils::jit_pool3_carve_wait_ms / 1000);
							continue;
						}

						// Stop rather than reserve outside the block, where the alias offset cannot reach; the unparked main thread still aborts.
						const unsigned long long live_bytes = units_live * k_pool3_granule;
						const unsigned long long prepared_bytes = static_cast<unsigned long long>(pool3_prepared_granules) * k_pool3_granule;
						print_unit_table_locked();
						::pthread_mutex_unlock(&unit_lock);
						utils::jit_pool_full("pool 3 (llvm code)", live_bytes, prepared_bytes, size);

						if (pthread_main_np() != 0)
						{
							abort_at_the_cause(
								"pool 3's carve is exhausted -- the title's PEAK CONCURRENT "
								"MemoryManager1 count exceeds what the measured layout reserves, "
								"and a reservation outside the block would break the single offset; "
								"the remedy is a layout change and is the owner's",
								"waited ms", static_cast<long long>(utils::jit_pool3_carve_wait_ms));
						}

						return nullptr;
					}
				}
			}

			for (unsigned i = 0; i < want; i++)
			{
				unit_taken[at + i] = true;
			}

			unit_granules[at] = want;
			units_carved++;
			units_live += want;

			if (units_live > units_peak)
			{
				units_peak = units_live;
			}

			::pthread_mutex_unlock(&unit_lock);

			return base + k_pool3_off + static_cast<unsigned long long>(at) * k_pool3_granule;
		}

		// The caller has decommitted the pages; this returns only the address range.
		void release_llvm_unit(void* p)
		{
			if (p == nullptr)
			{
				return;
			}

			const unsigned char* q = static_cast<const unsigned char*>(p);

			if (q < base + k_pool3_off || q >= base + k_pool3_off + k_pool3_size)
			{
				// Not pool 3's: ignored, since release is a no-op for the other pools, but the bitmap stays intact.
				return;
			}

			const unsigned long long off =
				static_cast<unsigned long long>(q - (base + k_pool3_off));

			if (off % k_pool3_granule != 0)
			{
				return;
			}

			const unsigned at = static_cast<unsigned>(off / k_pool3_granule);

			::pthread_mutex_lock(&unit_lock);

			const unsigned want = unit_granules[at];

			if (want == 0)
			{
				// A double release or a pointer into a unit: ignored, since aborting a shutdown path would turn a leak into a crash.
				::pthread_mutex_unlock(&unit_lock);
				return;
			}

			for (unsigned i = 0; i < want; i++)
			{
				unit_taken[at + i] = false;
			}

			unit_granules[at] = 0;
			units_released++;

			units_live -= units_live >= want ? want : units_live;

			::pthread_cond_broadcast(&unit_free);
			::pthread_mutex_unlock(&unit_lock);
		}

		// The lowest live unit, or nullptr; jit_dual_map_report_pool probes pool 3 there.
		unsigned char* lowest_live_unit()
		{
			unsigned char* found = nullptr;

			::pthread_mutex_lock(&unit_lock);

			for (unsigned i = 0; i < k_pool3_granules; i++)
			{
				if (unit_granules[i] != 0)
				{
					found = base + k_pool3_off
						+ static_cast<unsigned long long>(i) * k_pool3_granule;
					break;
				}
			}

			::pthread_mutex_unlock(&unit_lock);

			return found;
		}

		unsigned long long pool_offset(utils::jit_pool pool) const
		{
			switch (pool)
			{
			case utils::jit_pool::asmjit_arena: return k_pool1_off;
			case utils::jit_pool::jit_memory: return k_pool2_off;
			case utils::jit_pool::llvm_code: return k_pool3_off;
			}

			return k_pool2_off;
		}

		// get_jit_memory() asks for both 1 GiB halves at once; here they are apart, so the carve returns the code half's base.
		unsigned long long pool_capacity(utils::jit_pool pool) const
		{
			switch (pool)
			{
			case utils::jit_pool::asmjit_arena: return k_pool1_size;
			case utils::jit_pool::jit_memory: return k_pool2_size + k_data_size;
			case utils::jit_pool::llvm_code: return k_pool3_size;
			}

			return 0;
		}

		unsigned long long pool_exec_size(utils::jit_pool pool) const
		{
			switch (pool)
			{
			case utils::jit_pool::asmjit_arena: return k_pool1_size;
			case utils::jit_pool::jit_memory: return k_pool2_size;
			case utils::jit_pool::llvm_code: return k_pool3_size;
			}

			return 0;
		}

		bool contains(const void* p) const
		{
			if (!active)
			{
				return false;
			}

			const unsigned char* q = static_cast<const unsigned char*>(p);
			return q >= base && q < base + k_block_size;
		}

		utils::jit_view view_of(const void* p) const
		{
			if (!contains(p))
			{
				return utils::jit_view::outside;
			}

			const unsigned long long off = static_cast<unsigned long long>(
				static_cast<const unsigned char*>(p) - base);

			if (off < k_exec_half_size)
			{
				return utils::jit_view::execute;
			}

			if (off < k_data_off)
			{
				return utils::jit_view::alias;
			}

			return utils::jit_view::data;
		}
	};

	// Function-local, since jit_dual_map_carve is reached from a static initialiser in another translation unit.
	dual_map_block& block()
	{
		static dual_map_block s_block;
		return s_block;
	}

	// The second StikDebug pass: the same x16 = 2 request over only the pages above the caps, then the detach and the raised caps.

	pthread_t        s_second_thread;
	struct sigaction s_second_trap;
	struct sigaction s_second_old_trap;

	// Only the pass's own thread jumps back; another thread's SIGTRAP goes to the disposition the guard replaced.
	void second_pass_trap_handler(int sig, siginfo_t* info, void* context)
	{
		if (::pthread_equal(::pthread_self(), s_second_thread))
		{
			siglongjmp(s_brk_jmp, 1);
		}

		if ((s_second_old_trap.sa_flags & SA_SIGINFO) != 0)
		{
			s_second_old_trap.sa_sigaction(sig, info, context);
		}
		else if (s_second_old_trap.sa_handler == SIG_DFL)
		{
			sigset_t trap;
			sigemptyset(&trap);
			sigaddset(&trap, SIGTRAP);
			::sigaction(SIGTRAP, &s_second_old_trap, nullptr);
			::pthread_sigmask(SIG_UNBLOCK, &trap, nullptr);
			::raise(sig);
			::sigaction(SIGTRAP, &s_second_trap, nullptr);
		}
		else if (s_second_old_trap.sa_handler != SIG_IGN)
		{
			s_second_old_trap.sa_handler(sig);
		}
	}

	// The pass's one line goes to the perf loop for RPCS3.log; then the next pass may start.
	void second_pass_finish(dual_map_block& b, int opened, long long traced_ms, long long pages_ok, long long elapsed_ms,
		long long budget_hit, long long stop_ms, const char* result)
	{
		std::snprintf(s_second_line, sizeof(s_second_line),
			"jit second-pass trigger=%s opened=%d traced_after_ms=%lld pages_ok=%lld elapsed_ms=%lld budget_hit=%lld "
			"pool2_mib=%llu pool3_mib=%llu stop_ms=%lld result=%s",
			s_second_trigger == 2 ? "pool2" : "pool3", opened, traced_ms, pages_ok, elapsed_ms, budget_hit,
			static_cast<unsigned long long>(__atomic_load_n(&utils::g_pool2_code_limit, __ATOMIC_RELAXED)) >> 20,
			static_cast<unsigned long long>(b.pool3_prepared_granules) * k_pool3_granule >> 20, stop_ms, result);
		s_second_line_ready.store(true, std::memory_order_release);
		// The flag first, then a waiting carve is woken.
		s_second_pass_in_flight.store(false);
		::pthread_mutex_lock(&b.unit_lock);
		::pthread_cond_broadcast(&b.unit_free);
		::pthread_mutex_unlock(&b.unit_lock);
	}

	void* second_pass_worker(void*)
	{
		dual_map_block& b = block();
		const unsigned long long t0 = mono_ms();

		// Nothing is requested until a debugger holds the process, so the brk always has a listener.
		if (!wait_for_debugger(k_second_pass_wait_ms))
		{
			second_pass_finish(b, 1, -1, -1, -1, -1, -1, "no-debugger");
			return nullptr;
		}

		const long long traced_ms = static_cast<long long>(mono_ms() - t0);
		const unsigned long long cap2 = __atomic_load_n(&utils::g_pool2_code_limit, __ATOMIC_RELAXED);
		::pthread_mutex_lock(&b.unit_lock);
		const unsigned long long cap3 = static_cast<unsigned long long>(b.pool3_prepared_granules) * k_pool3_granule;
		::pthread_mutex_unlock(&b.unit_lock);

		const char* refused = nullptr;
		unsigned long long allow = 0;
#if TARGET_OS_IPHONE
		// Every prepared page stays resident, so the pass takes at most half of what this process may still use.
		allow = static_cast<unsigned long long>(os_proc_available_memory()) / 2;

		if (allow < k_pool3_granule)
		{
			refused = "low-memory";
		}
#endif

		unsigned long long walk[k_script_walk_words];
		armsx3_jit_second_walk(s_second_trigger, cap2, k_pool2_size, k_pool3_off, cap3,
			(k_stub_off - k_pool3_off) / k_pool3_granule * k_pool3_granule, allow, walk);

		for (int i = 0; i < k_script_report_words; ++i)
		{
			s_script_report[i] = 0;
		}

		long long len = -1;

		if (refused == nullptr)
		{
			refused = build_prepare_script(reinterpret_cast<unsigned long long>(b.base), walk, &len);
		}

		// The guard is installed only around the two requests.
		s_second_thread = ::pthread_self();
		std::memset(&s_second_trap, 0, sizeof(s_second_trap));
		s_second_trap.sa_sigaction = second_pass_trap_handler;
		s_second_trap.sa_flags = SA_SIGINFO;
		sigemptyset(&s_second_trap.sa_mask);

		if (::sigaction(SIGTRAP, &s_second_trap, &s_second_old_trap) != 0)
		{
			second_pass_finish(b, 1, traced_ms, -1, -1, -1, -1, "skipped-sigtrap-handler");
			return nullptr;
		}

		const unsigned long long s0 = mono_ms();
		volatile int answered = 0;

		// A refused walk sends no script, but the detach still goes, because the debugger is waiting for a request.
		if (refused == nullptr)
		{
			if (sigsetjmp(s_brk_jmp, 1) == 0)
			{
				jit_send_script_universal(s_script_text, k_script_x1);
				answered = 1;
			}
		}

		if (sigsetjmp(s_brk_jmp, 1) == 0)
		{
			jit_detach_universal();
		}

		const long long stop_ms = static_cast<long long>(mono_ms() - s0);
		::sigaction(SIGTRAP, &s_second_old_trap, nullptr);

		const char* result = refused != nullptr ? refused : answered ? "not-run" : "sigtrap";
		long long pages_ok = -1;
		long long elapsed_ms = -1;
		long long budget_hit = -1;

		// The launch's report words, decoded the way read_script_report decodes them, into this pass's own locals.
		if (answered && s_script_report[0] == k_script_started)
		{
			unsigned long long w[k_script_report_words];

			for (int i = 0; i < k_script_report_words; ++i)
			{
				w[i] = s_script_report[i];
			}

			if (w[1] != reinterpret_cast<unsigned long long>(b.base) || w[2] != static_cast<unsigned long long>(len))
			{
				result = "foreign";
			}
			else if (w[3] != k_script_finished)
			{
				result = "started";
			}
			else
			{
				pages_ok = static_cast<long long>(w[4]);
				elapsed_ms = static_cast<long long>(w[7]);
				budget_hit = static_cast<long long>(w[8]);

				// A pool filled during the pass: the halt is sticky, so the caps stay and the line says halted.
				if (utils::jit_pool_full_reason() != 0)
				{
					result = "halted";
				}
				else
				{
					result = "finished";

					unsigned long long new2 = cap2;
					unsigned long long new3 = cap3;
					armsx3_jit_second_extents(walk, w[4], w[5] != 0 ? w[6] : ~0ull, cap2, k_pool3_off, cap3, &new2, &new3);
					b.raise_caps(new2, new3);
				}
			}
		}

		second_pass_finish(b, 1, traced_ms, pages_ok, elapsed_ms, budget_hit, stop_ms, result);
		return nullptr;
	}
}

using namespace jit_dual_map_internal;

namespace utils
{
	void* jit_dual_map_carve(jit_pool pool, usz size) noexcept
	{
		dual_map_block& b = block();

		if (!b.active)
		{
			return nullptr;
		}

		if (static_cast<unsigned long long>(size) > b.pool_capacity(pool))
		{
			abort_at_the_cause("a pool asked for more than the measured layout reserves for it",
				"requested bytes", static_cast<long long>(size));
		}

		// Pool 3 hands each caller a different unit; pools 1 and 2 return their fixed base.
		if (pool == jit_pool::llvm_code)
		{
			return b.carve_llvm_unit(static_cast<unsigned long long>(size));
		}

		return b.base + b.pool_offset(pool);
	}

	void jit_dual_map_release(jit_pool pool, void* unit) noexcept
	{
		// jit_runtime::finalize() resets the bump-allocated pools wholesale; only pool 3 releases per unit.
		if (pool != jit_pool::llvm_code)
		{
			return;
		}

		dual_map_block& b = block();

		if (!b.active)
		{
			return;
		}

		b.release_llvm_unit(unit);
	}

	bool jit_dual_map_contains(const void* p) noexcept
	{
		return block().contains(p);
	}

	jit_view jit_view_of(const void* p) noexcept
	{
		return block().view_of(p);
	}

	bool jit_dual_map_commit(void* p, usz size, bool executable) noexcept
	{
		dual_map_block& b = block();

		if (!b.contains(p))
		{
			return false;
		}

		const jit_view where = b.view_of(p);

		// Upstream asks for protection::wx on every code commit; a page both writable and executable cannot be given.
		if (executable && where != jit_view::execute)
		{
			abort_at_the_cause("an executable commit was asked for outside the executable half",
				"view", static_cast<long long>(static_cast<int>(where)));
		}

		if (!executable && where == jit_view::execute)
		{
			// A writable commit in the executable half would need the forbidden mprotect, so refuse it.
			abort_at_the_cause("a writable commit was asked for INSIDE the executable half; "
				"rule 2 forbids it and mprotect would have reported success",
				"offset into block", static_cast<long long>(
					static_cast<unsigned char*>(p) - b.base));
		}

		// Nothing to do: every access this could ask for was set at creation, and asking again would be the forbidden mprotect.
		static_cast<void>(size);
		return true;
	}

	bool jit_dual_map_decommit(void* p, usz size) noexcept
	{
		dual_map_block& b = block();

		if (!b.contains(p))
		{
			return false;
		}

		// MADV_FREE only, never munmap or mprotect; a refusal is reported, not fatal, since the range stays correct.
		if (::madvise(p, static_cast<std::size_t>(size), MADV_FREE) != 0)
		{
			utils::stderr_witness_printf(
				"ARMSX3: madvise(MADV_FREE) on a JIT dual-map range returned errno %d. The range "
				"stays mapped and correct; only the physical pages were not released.\n", errno);
		}

		return true;
	}

	void jit_dual_map_report_pool(jit_pool pool, jit_dual_map_report* out) noexcept
	{
		if (!out)
		{
			return;
		}

		std::memset(out, 0, sizeof(*out));
		std::strcpy(out->exec_prot, "?");
		std::strcpy(out->exec_max_prot, "?");
		std::strcpy(out->alias_prot, "?");

		dual_map_block& b = block();

		if (!b.active)
		{
			return;
		}

		out->block_base = reinterpret_cast<unsigned long long>(b.base);
		out->block_size = k_block_size;
		out->alias_base = reinterpret_cast<unsigned long long>(b.alias);
		out->code_rw_offset = static_cast<long long>(g_code_rw_offset);
		out->exec_size = b.pool_exec_size(pool);
		out->handshake_performed = b.handshake_performed;
		out->handshake_ms = b.handshake_ms;

		// Block-wide, so every pool's report carries the same values.
		out->detach_outcome = b.detach_outcome;
		out->detach_ms = b.detach_ms;
		out->detach_traced_before = b.traced_before_detach;
		out->detach_traced_after = b.traced_after_detach;

		out->stub_before = b.stub_before;
		out->stub_after = b.stub_after;
		out->stub_after_new = b.stub_after_new;

		out->readback_exec_0x0 = b.readback_exec_0x0;
		out->readback_exec_0x40000000 = b.readback_exec_0x40000000;
		out->readback_exec_0x7fff8000 = b.readback_exec_0x7fff8000;
		out->readback_exec_0x7fffc000 = b.readback_exec_0x7fffc000;
		out->readback_exec_0x1 = b.readback_exec_0x1;
		out->readback_exec_0x40000340 = b.readback_exec_0x40000340;
		out->readback_alias_0x0 = b.readback_alias_0x0;
		out->readback_alias_0x7fff8000 = b.readback_alias_0x7fff8000;

		out->readback_exec_pages_0x69 = b.readback_exec_pages_0x69;
		out->readback_exec_first_miss_off = b.readback_exec_first_miss_off;

		// For the host smoke tracer only, also the text as built and the report block's address.
		out->script_outcome = b.script_outcome;
		out->script_len = b.script_len;
		out->script_pages_ok = b.script_pages_ok;
		out->script_pages_refused = b.script_pages_refused;
		out->script_first_refused_off = b.script_first_refused_off;
		out->script_elapsed_ms = b.script_elapsed_ms;
		out->script_budget_hit = b.script_budget_hit;
		out->script_budget_ms = s_script_budget_ms;

		out->enabler_selector = b.enabler_selector;
		out->enabler_reason = b.enabler_reason;

		out->pool2_limit_bytes = utils::g_pool2_code_limit;
		out->pool3_limit_bytes = static_cast<unsigned long long>(b.pool3_prepared_granules) * k_pool3_granule;
		out->split_sessions = b.split.sessions;
		out->split_budget_pages = b.split.budget_pages;
		out->split_need2_bytes = b.split.need2;
		out->split_need3_bytes = b.split.need3;
		out->split_share2_bytes = b.split.share2;
		out->split_share3_bytes = b.split.share3;

		out->script_text = s_script_text[0] != '\0' ? s_script_text : nullptr;
		out->script_report_addr = out->script_text != nullptr ? reinterpret_cast<unsigned long long>(s_script_report) : 0;

		// Filled for every pool, so an empty field means zero and not a forgotten one.
		out->llvm_units_carved = b.units_carved;
		out->llvm_units_released = b.units_released;
		out->llvm_units_live = b.units_live;
		out->llvm_units_peak = b.units_peak;
		out->llvm_units_capacity = b.units_capacity;
		out->llvm_carve_waits = b.carve_waits;
		out->llvm_unit_bytes = b.unit_bytes;

		// Pool 3 is probed at its lowest live unit.
		if (pool == jit_pool::llvm_code)
		{
			unsigned char* const lowest = b.lowest_live_unit();

			if (lowest == nullptr)
			{
				// No live unit: nothing is probed, so an interpreter run never reports a protection it did not read.
				return;
			}

			out->exec_base = reinterpret_cast<unsigned long long>(lowest);
			out->exec_size = b.unit_bytes;
		}
		else
		{
			out->exec_base = reinterpret_cast<unsigned long long>(b.base + b.pool_offset(pool));
		}

		region_report exec_rr;
		region_report alias_rr;
		region_probe(out->exec_base, &exec_rr);
		region_probe(out->exec_base + static_cast<unsigned long long>(g_code_rw_offset), &alias_rr);

		prot_string(exec_rr.contained ? exec_rr.prot : -1, out->exec_prot);
		prot_string(exec_rr.contained ? exec_rr.max_prot : -1, out->exec_max_prot);
		prot_string(alias_rr.contained ? alias_rr.prot : -1, out->alias_prot);
		out->share_mode = exec_rr.share_mode;

		// A conjunction of read-back facts, not a call-succeeded flag.
		out->ok = (exec_rr.contained
			&& (exec_rr.prot & VM_PROT_EXECUTE) != 0
			&& (exec_rr.prot & VM_PROT_WRITE) == 0
			&& alias_rr.contained
			&& (alias_rr.prot & VM_PROT_WRITE) != 0
			&& (alias_rr.prot & VM_PROT_EXECUTE) == 0
			&& out->code_rw_offset == k_code_rw_offset) ? 1 : 0;
	}

	bool low_memory_device() noexcept
	{
		// The jetsam limit is footprint plus limit_bytes_remaining; the Mac reports none, so it is never low-memory.
		static const bool low = []
		{
			task_vm_info_data_t vm{};
			mach_msg_type_number_t count = TASK_VM_INFO_COUNT;

			if (task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&vm), &count) != KERN_SUCCESS
				|| vm.limit_bytes_remaining == 0)
			{
				return false;
			}

			return vm.phys_footprint + static_cast<unsigned long long>(vm.limit_bytes_remaining) < k_prep_small_below;
		}();

		return low;
	}

	void jit_dual_map_note_peaks(unsigned long long pool2_top, bool game_running) noexcept
	{
		dual_map_block& b = block();

		if (!b.active)
		{
			return;
		}

		b.note_peaks(pool2_top, game_running);
	}

	void jit_dual_map_cap_pool3_for_leg(unsigned mib)
	{
		block().cap_pool3_for_leg(mib);
	}
}

namespace utils
{
	int jit_second_pass_take_offer() noexcept
	{
		return block().active ? s_second_offer.exchange(0) : 0;
	}

	// Never on the built-in route; one pass at a time.
	bool jit_second_pass_start(int trigger, bool opened) noexcept
	{
		dual_map_block& b = block();
		bool idle = false;

		if (!b.active || (trigger != 2 && trigger != 3) || !b.stikdebug_route() || !s_second_pass_in_flight.compare_exchange_strong(idle, true))
		{
			return false;
		}

		s_second_trigger = trigger;

		if (!opened)
		{
			second_pass_finish(b, 0, -1, -1, -1, -1, -1, "not-opened");
			return true;
		}

		pthread_t worker;

		if (::pthread_create(&worker, nullptr, second_pass_worker, nullptr) != 0)
		{
			second_pass_finish(b, 1, -1, -1, -1, -1, -1, "no-thread");
			return false;
		}

		::pthread_detach(worker);
		return true;
	}

	bool jit_second_pass_tick(bool game_running, char* out, unsigned long long size) noexcept
	{
		dual_map_block& b = block();

		if (b.active)
		{
			b.check_offer(game_running);
		}

		if (out == nullptr || size == 0 || !s_second_line_ready.exchange(false, std::memory_order_acq_rel))
		{
			return false;
		}

		std::snprintf(out, static_cast<std::size_t>(size), "%s", s_second_line);
		return true;
	}
}

#else

// Upstream's behaviour off Apple ARM64; the Apple arm stays the #if, so a test can see no POSIX name reaches Windows.
namespace utils
{
	void* jit_dual_map_carve(jit_pool, usz) noexcept
	{
		return nullptr;
	}

	void jit_dual_map_release(jit_pool, void*) noexcept
	{
	}

	bool jit_dual_map_contains(const void*) noexcept
	{
		return false;
	}

	jit_view jit_view_of(const void*) noexcept
	{
		return jit_view::outside;
	}

	bool jit_dual_map_commit(void*, usz, bool) noexcept
	{
		return false;
	}

	bool jit_dual_map_decommit(void*, usz) noexcept
	{
		return false;
	}

	void jit_dual_map_report_pool(jit_pool, jit_dual_map_report* out) noexcept
	{
		if (!out)
		{
			return;
		}

		std::memset(out, 0, sizeof(*out));
		std::strcpy(out->exec_prot, "?");
		std::strcpy(out->exec_max_prot, "?");
		std::strcpy(out->alias_prot, "?");
		out->code_rw_offset = 0;
		out->ok = 0;
	}

	bool low_memory_device() noexcept
	{
		return false;
	}

	void jit_dual_map_note_peaks(unsigned long long, bool) noexcept
	{
	}

	void jit_dual_map_cap_pool3_for_leg(unsigned)
	{
	}
}

// The second StikDebug pass does not exist off Apple ARM64.
namespace utils
{
	int jit_second_pass_take_offer() noexcept
	{
		return 0;
	}

	bool jit_second_pass_start(int, bool) noexcept
	{
		return false;
	}

	bool jit_second_pass_tick(bool, char*, unsigned long long) noexcept
	{
		return false;
	}
}

#endif
