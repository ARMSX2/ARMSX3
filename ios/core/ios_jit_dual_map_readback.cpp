// SPDX-License-Identifier: GPL-2.0-only

#include "ios_jit_dual_map_private.h"

#if defined(__APPLE__) && defined(ARCH_ARM64)

namespace jit_dual_map_internal
{

	// A broken mapping would surface later at an unrelated emission, so abort here, where the cause is.
	[[noreturn]] void abort_at_the_cause(const char* what, const char* detail, long long value)
	{
		utils::stderr_witness_printf(
			"ARMSX3: the JIT dual mapping could not be established: %s (%s = %lld). The "
			"write_alias rung cannot run without it and continuing would hand every emitter a "
			"pointer into memory that is not what it claims to be.\n",
			what, detail, value);
		std::abort();
	}

	// Names the kern_return_t values this sequence can produce, so a refusal reads as a name.
	const char* kern_return_name(kern_return_t kr)
	{
		switch (kr)
		{
		case KERN_SUCCESS: return "KERN_SUCCESS";
		case KERN_INVALID_ADDRESS: return "KERN_INVALID_ADDRESS";
		case KERN_PROTECTION_FAILURE: return "KERN_PROTECTION_FAILURE";
		case KERN_NO_SPACE: return "KERN_NO_SPACE";
		case KERN_INVALID_ARGUMENT: return "KERN_INVALID_ARGUMENT";
		case KERN_FAILURE: return "KERN_FAILURE";
		case KERN_RESOURCE_SHORTAGE: return "KERN_RESOURCE_SHORTAGE";
		case KERN_NOT_SUPPORTED: return "KERN_NOT_SUPPORTED";
		case KERN_MEMORY_ERROR: return "KERN_MEMORY_ERROR";
		default: return "kern_return_t";
		}
	}

	void region_probe(unsigned long long at, region_report* out)
	{
		vm_region_submap_info_data_64_t info;
		mach_msg_type_number_t          count = VM_REGION_SUBMAP_INFO_COUNT_64;
		vm_address_t                    address = static_cast<vm_address_t>(at);
		vm_size_t                       size = 0;
		natural_t                       depth = 0;

		std::memset(out, 0, sizeof(*out));
		std::memset(&info, 0, sizeof(info));

		out->prot = -1;
		out->max_prot = -1;
		out->share_mode = -1;

		out->kr = vm_region_recurse_64(mach_task_self(), &address, &size, &depth,
			reinterpret_cast<vm_region_recurse_info_t>(&info), &count);

		if (out->kr != KERN_SUCCESS)
		{
			return;
		}

		out->base = static_cast<unsigned long long>(address);
		out->size = static_cast<unsigned long long>(size);
		out->prot = static_cast<int>(info.protection);
		out->max_prot = static_cast<int>(info.max_protection);
		out->share_mode = static_cast<int>(info.share_mode);
		out->contained = (at >= out->base && at < out->base + out->size) ? 1 : 0;
	}

	// `rwx` in the shape `vmmap(1)` prints, so a record cell reads the way a human reads it.
	void prot_string(int bits, char out[4])
	{
		if (bits < 0)
		{
			std::strcpy(out, "?");
			return;
		}

		out[0] = (bits & VM_PROT_READ) ? 'r' : '-';
		out[1] = (bits & VM_PROT_WRITE) ? 'w' : '-';
		out[2] = (bits & VM_PROT_EXECUTE) ? 'x' : '-';
		out[3] = '\0';
	}

	// The prepare is a debugger request (brk #0xf00d), so it can take seconds or hang and runs on a timed worker.

	// With no debugger listening, brk raises SIGTRAP and kills the process; this jumps back out so the macOS build can boot.
	_Thread_local sigjmp_buf s_brk_jmp;

	// [[noreturn]] because -Werror=missing-noreturn is on: siglongjmp resumes at sigsetjmp.
	[[noreturn]] void brk_trap_handler(int)
	{
		siglongjmp(s_brk_jmp, 1);
	}

	// noinline and optnone keep the brk in a frame the jump buffer describes, with its registers set up in order.
	__attribute__((noinline, optnone))
	void jit_send_script_universal(const char* script, std::size_t len)
	{
		__asm__ volatile("mov x0, %0\n"
		                 "mov x1, %1\n"
		                 "mov x16, #2\n"
		                 "brk #0xf00d\n"
		                 :: "r"(script), "r"(len) : "x0", "x1", "x16", "memory");
	}

	// x16 = 0 asks the debugger to detach; same attributes as the prepare request.
	__attribute__((noinline, optnone))
	void jit_detach_universal()
	{
		__asm__ volatile("mov x16, #0\n"
		                 "brk #0xf00d\n"
		                 ::: "x16", "memory");
	}

	// P_TRACED from this process's kinfo_proc, as Utilities/Thread.cpp reads it: 1, 0, or -1 when the read fails.
	int process_is_traced()
	{
		struct kinfo_proc info;
		std::size_t       len = sizeof(info);
		int               mib[4] = { CTL_KERN, KERN_PROC, KERN_PROC_PID, ::getpid() };

		std::memset(&info, 0, sizeof(info));

		if (::sysctl(mib, 4, &info, &len, nullptr, 0) != 0)
		{
			return -1;
		}

		return (info.kp_proc.p_flag & P_TRACED) != 0 ? 1 : 0;
	}

	// A two-instruction stub that returns 42, called from the prepared half only when a debugger took the prepare.
	constexpr unsigned int k_stub_insn_mov_w0_42 = 0x52800540u;
	constexpr unsigned int k_stub_insn_ret       = 0xd65f03c0u;
	constexpr long long k_readback_fault   = -2;

	// The stub guard's own fault counter: the core handler does not see these faults while the guard holds.
	std::atomic<long long> s_stub_faults{0};

	_Thread_local sigjmp_buf   s_stub_jmp;
	_Thread_local volatile int s_stub_signal = 0;
	_Thread_local bool         s_stub_worker = false;

	constexpr int    k_stub_signals[3] = { SIGBUS, SIGSEGV, SIGILL };
	struct sigaction s_stub_saved[3];

	// SIGILL too, so bytes that are not the stub cannot kill the process.
	void stub_fault_handler(int sig)
	{
		s_stub_faults.fetch_add(1);

		if (!s_stub_worker)
		{
			// Not a stub worker's fault: restore the saved disposition and return, so the instruction re-runs under it.
			for (int i = 0; i < 3; ++i)
			{
				if (k_stub_signals[i] == sig)
				{
					::sigaction(sig, &s_stub_saved[i], nullptr);
				}
			}

			return;
		}

		s_stub_signal = sig;
		siglongjmp(s_stub_jmp, 1);
	}

	bool install_stub_guard()
	{
		struct sigaction sa;
		std::memset(&sa, 0, sizeof(sa));
		sa.sa_handler = stub_fault_handler;
		sigemptyset(&sa.sa_mask);

		for (int i = 0; i < 3; ++i)
		{
			if (::sigaction(k_stub_signals[i], &sa, &s_stub_saved[i]) != 0)
			{
				while (i-- > 0)
				{
					::sigaction(k_stub_signals[i], &s_stub_saved[i], nullptr);
				}

				return false;
			}
		}

		return true;
	}

	void restore_stub_guard()
	{
		for (int i = 0; i < 3; ++i)
		{
			::sigaction(k_stub_signals[i], &s_stub_saved[i], nullptr);
		}
	}

	void* stub_worker(void* raw)
	{
		stub_call* const c = static_cast<stub_call*>(raw);
		s_stub_worker = true;

		const unsigned long long t0 = mono_ms();

		if (sigsetjmp(s_stub_jmp, 1) == 0)
		{
			const int w0 = reinterpret_cast<int (*)()>(c->exec)();
			c->value.store(w0);
			c->ms.store(static_cast<long long>(mono_ms() - t0));
			c->state.store(1);
		}
		else
		{
			c->signal.store(s_stub_signal);
			c->ms.store(static_cast<long long>(mono_ms() - t0));
			c->state.store(2);
		}

		return nullptr;
	}

	// Written through the alias and flushed at the execute address; never before the prepare.
	void plant_stub(unsigned char* exec)
	{
		volatile unsigned int* const code = reinterpret_cast<volatile unsigned int*>(utils::jit_rw(exec));

		code[0] = k_stub_insn_mov_w0_42;
		code[1] = k_stub_insn_ret;
		sys_icache_invalidate(exec, 2 * sizeof(unsigned int));
	}

	// A hang is a result: an unreturned call is recorded as a timeout and left running.
	void run_stub(stub_call* c, unsigned char* exec)
	{
		c->exec = exec;
		c->faults_at_start.store(s_stub_faults.load());
		c->faults_at_end.store(s_stub_faults.load());
		c->outcome.store("timeout");

		pthread_t worker;

		if (::pthread_create(&worker, nullptr, stub_worker, c) != 0)
		{
			c->outcome.store("skipped");
			c->faults_at_start.store(-1);
			c->faults_at_end.store(-1);
			return;
		}

		::pthread_detach(worker);

		const unsigned long long t0 = mono_ms();

		while (c->state.load() == 0 && mono_ms() - t0 < k_stub_bound_ms)
		{
			struct timespec ts;
			ts.tv_sec = 0;
			ts.tv_nsec = k_stub_poll_ns;
			nanosleep(&ts, nullptr);
		}

		const int state = c->state.load();
		c->faults_at_end.store(s_stub_faults.load());
		c->outcome.store(state == 1 ? "ok" : state == 2 ? "fault" : "timeout");
	}

	bool stub_unresolved(const stub_call& c)
	{
		return std::strcmp(c.outcome.load(), "skipped") != 0 && c.state.load() == 0;
	}

	// The constructor's last read, so a call that resolved after its bound still shows.
	utils::jit_stub_call_report snapshot_stub(const stub_call& c)
	{
		utils::jit_stub_call_report r{"skipped", -1, -1, -1, -1, -1};
		const char* const outcome = c.outcome.load();

		if (std::strcmp(outcome, "skipped") == 0)
		{
			return r;
		}

		r.outcome = outcome;
		r.faults_at_start = c.faults_at_start.load();
		r.faults_at_end = c.faults_at_end.load();
		r.signal = 0;

		if (c.state.load() != 0)
		{
			r.value = c.value.load();
			r.signal = c.signal.load();
			r.ms = c.ms.load();
		}

		return r;
	}

	// Reads one byte under the stub guard by borrowing this thread's jump buffer; the caller owes an installed guard and no stub worker yet.
	long long read_guarded_byte(const volatile unsigned char* p)
	{
		s_stub_worker = true;

		if (sigsetjmp(s_stub_jmp, 1) == 0)
		{
			const unsigned char b = *p;
			s_stub_worker = false;
			return static_cast<long long>(b);
		}

		s_stub_worker = false;
		return k_readback_fault;
	}

	// skipped, fault or 0xNN for the log line; buf needs at least 5 bytes.
	const char* readback_tok(long long v, char* buf, std::size_t n)
	{
		if (v == k_readback_skipped) return "skipped";
		if (v == k_readback_fault)   return "fault";
		std::snprintf(buf, n, "0x%02llx", static_cast<unsigned long long>(v) & 0xffull);
		return buf;
	}
}

#endif
