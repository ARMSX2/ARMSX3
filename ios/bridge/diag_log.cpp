// SPDX-License-Identifier: GPL-2.0-only
// The product log: the core's RPCS3.log plus header lines and one IOSDIAG perf line a second; it never writes paths or identifiers.
#include "diag_log.h"
#include "frame_time_stats.h"
#include "gpu_busy.h"
#include "image_id.h"
#include "stall_trigger.h"

#include "rpcs3_ios_abi.h"

#include "util/logs.hpp"
#include "util/sysinfo.hpp"
#include "Utilities/File.h"
#include "rpcs3_version.h"
#include "ios/core/ios_jit_dual_map.h"
#include "Utilities/JIT.h"

#include <TargetConditionals.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <malloc/malloc.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <pthread.h>
#include <sys/resource.h>

#if TARGET_OS_IPHONE
#include <os/proc.h>
#include "ios_frame.h"
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// After <objc/runtime.h>: these core headers bring a second `Method` that makes the runtime header's own ambiguous.
#include "Emu/System.h"
#include "Emu/RSX/RSXThread.h"
#include "Emu/Cell/timers.hpp"
#include "Emu/IdManager.h"
#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/SPUThread.h"

LOG_CHANNEL(diag_log, "IOSDIAG");

// Declared here because the core's VK memory header pulls in Vulkan and VMA, which this target does not take.
namespace vk
{
	void vmm_notify_os_memory_critical();
	void vmm_get_usage_snapshot(u64& texture_cache, u64& surface_cache, u64& total);
	u64 vmm_get_device_local_block_bytes();
}

// libobjc exports these (what @autoreleasepool lowers to); no header declares them.
extern "C" void* objc_autoreleasePoolPush(void);
extern "C" void objc_autoreleasePoolPop(void* pool);

namespace
{
	// The core's file-size cap is min(a quarter of the free space, this).
	constexpr u64 k_log_size_ceiling = 512ull * 1024 * 1024;

	constexpr u64 k_free_space_fallback = 128ull * 1024 * 1024;

	// `logs::listener::add` has no duplicate check, so a second install would write every line twice.
	std::atomic<bool> s_started{false};

	constexpr bool k_gpu_time_hook = true;

	// MTLCommandBufferStatus value for Committed in MTLCommandBuffer.h.
	constexpr unsigned long k_mtl_status_committed = 2;
	constexpr unsigned long k_mtl_status_completed = 4;

	// Not every channel: the file listener reserves 50,000 bytes per message, so trace everywhere distorts the stutter being measured.
	constexpr const char* k_verbose_channels[] = {"RSX", "RSXPROF"};

	// The flag and the levels change together under this lock, so an "off" cannot land between the loop's check and its raise.
	std::mutex s_verbose_mutex;
	bool s_verbose = false;

	void set_verbose_channels(logs::level level)
	{
		for (const char* channel : k_verbose_channels)
		{
			logs::set_level(channel, level);
		}
	}

	// The core's on-screen-keyboard dialog logs every key code and the typed text at notice on these channels.
	constexpr const char* k_quiet_channels[] = {"OSK", "cellOskDialog"};

	// sys_rwlock logs every create and destroy at warning, which buries everything else; tests/test_log_hygiene.py checks the name.
	constexpr const char* k_error_channels[] = {"sys_rwlock"};

	// Raises and never lowers, so a channel that Silence All Logs put at `always` stays there.
	void quiet_channels()
	{
		for (const char* channel : k_quiet_channels)
		{
			if (logs::get_level(channel) > logs::level::warning)
			{
				logs::set_level(channel, logs::level::warning);
			}
		}

		for (const char* channel : k_error_channels)
		{
			if (logs::get_level(channel) > logs::level::error)
			{
				logs::set_level(channel, logs::level::error);
			}
		}
	}

	std::atomic<u64> s_shader_adds{0};

	std::atomic<u64> s_fatal_exits{0};

	constexpr usz k_window_cap = 2048;
	std::mutex s_window_mutex;
	std::vector<u32> s_window;

	std::mutex s_stall_mutex;
	armsx3::bridge::diag::stall_trigger s_stall{};

	std::atomic<u64> s_rsx_held_since{0};

	// Counts the core's RSX `Add program (` notices; it runs on every log line, so it allocates nothing and takes no lock.
	struct shader_counter final : logs::listener
	{
		void log(u64 /*stamp*/, const logs::message& msg, std::string_view /*prefix*/, std::string_view text) override
		{
			const logs::level lv = static_cast<logs::level>(msg);

			if (lv != logs::level::notice || std::strcmp(msg->name, "RSX") != 0)
			{
				return;
			}

			if (text.starts_with("Add program ("))
			{
				s_shader_adds.fetch_add(1, std::memory_order_relaxed);
			}
		}
	};

	// Counts fatal thread exits for the halt poll; like shader_counter it allocates nothing and takes no lock.
	struct fatal_counter final : logs::listener
	{
		void log(u64 /*stamp*/, const logs::message& msg, std::string_view /*prefix*/, std::string_view text) override
		{
			if (static_cast<logs::level>(msg) != logs::level::fatal || std::strcmp(msg->name, "SIG") != 0)
			{
				return;
			}

			if (text.starts_with("Thread terminated due to fatal error"))
			{
				s_fatal_exits.fetch_add(1, std::memory_order_relaxed);
			}
		}
	};

	// Through the Objective-C runtime so this stays a plain C++ file; a missing class reads `unknown`, not `nominal`.
	const char* thermal_word()
	{
		const auto cls = objc_getClass("NSProcessInfo");

		if (!cls)
		{
			return "unknown";
		}

		const auto send_id = reinterpret_cast<id (*)(id, SEL)>(objc_msgSend);
		const auto send_long = reinterpret_cast<long (*)(id, SEL)>(objc_msgSend);
		const id info = send_id(reinterpret_cast<id>(cls), sel_registerName("processInfo"));

		if (!info)
		{
			return "unknown";
		}

		switch (send_long(info, sel_registerName("thermalState")))
		{
		case 0: return "nominal";
		case 1: return "fair";
		case 2: return "serious";
		case 3: return "critical";
		default: return "unknown";
		}
	}

	// phys_footprint is the figure jetsam judges the process by; every field reads 0 when the query fails.
	task_vm_info_data_t vm_snapshot()
	{
		task_vm_info_data_t info{};
		mach_msg_type_number_t count = TASK_VM_INFO_COUNT;

		if (task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&info), &count) != KERN_SUCCESS)
		{
			return task_vm_info_data_t{};
		}

		return info;
	}

	// The iPhoneOS SDK ships no libproc.h, so proc_pid_rusage is resolved at run time.
	rusage_info_v4 io_snapshot()
	{
		static const auto s_rusage = reinterpret_cast<int (*)(int, int, rusage_info_t*)>(dlsym(RTLD_DEFAULT, "proc_pid_rusage"));
		rusage_info_v4 info{};

		if (!s_rusage || s_rusage(getpid(), RUSAGE_INFO_V4, reinterpret_cast<rusage_info_t*>(&info)) != 0)
		{
			return rusage_info_v4{};
		}

		return info;
	}

	// Through dlsym because this target links no framework; the device is created once and kept for the process.
	id metal_device()
	{
		static const id s_device = []() -> id
		{
			const auto create = reinterpret_cast<id (*)()>(dlsym(RTLD_DEFAULT, "MTLCreateSystemDefaultDevice"));
			return create ? create() : nullptr;
		}();

		return s_device;
	}

	u64 metal_allocated_bytes()
	{
		const id device = metal_device();

		if (!device)
		{
			return 0;
		}

		const auto send = reinterpret_cast<unsigned long (*)(id, SEL)>(objc_msgSend);
		return static_cast<u64>(send(device, sel_registerName("currentAllocatedSize")));
	}

	std::mutex s_gpu_mutex;
	armsx3::bridge::diag::gpu_busy s_gpu{};

	const SEL k_sel_commit = sel_registerName("commit");
	const SEL k_sel_add_completed = sel_registerName("addCompletedHandler:");
	const SEL k_sel_status = sel_registerName("status");
	const SEL k_sel_gpu_start = sel_registerName("GPUStartTime");
	const SEL k_sel_gpu_end = sel_registerName("GPUEndTime");

	// shortcut: assumes Metal's host time is mach_absolute_time; if gpu_ms reads 0 on running ticks, compare with mach_continuous_time.
	double host_seconds()
	{
		static const double s_scale = []
		{
			mach_timebase_info_data_t base{};
			mach_timebase_info(&base);
			return base.denom ? static_cast<double>(base.numer) / base.denom / 1e9 : 1e-9;
		}();

		return static_cast<double>(mach_absolute_time()) * s_scale;
	}

	// Runs on Metal's completion thread, so it never logs, never touches the emulator and allocates nothing.
	void on_gpu_complete(id cb)
	{
		if (reinterpret_cast<unsigned long (*)(id, SEL)>(objc_msgSend)(cb, k_sel_status) != k_mtl_status_completed)
		{
			return;
		}

		const auto send = reinterpret_cast<double (*)(id, SEL)>(objc_msgSend);
		const double start = send(cb, k_sel_gpu_start);
		const double end = send(cb, k_sel_gpu_end);
		std::lock_guard lock(s_gpu_mutex);
		armsx3::bridge::diag::gpu_busy_add(s_gpu, start, end);
	}

	constexpr int k_gpu_slots = 2;
	std::atomic<IMP> s_gpu_original[k_gpu_slots]{};
	thread_local bool t_in_commit = false;

	// The flag stops a subclass's super-commit from adding a second handler.
	template <int Slot>
	void gpu_commit(id self, SEL cmd)
	{
		const bool outer = !t_in_commit;

		if (outer)
		{
			if (reinterpret_cast<unsigned long (*)(id, SEL)>(objc_msgSend)(self, k_sel_status) < k_mtl_status_committed)
			{
				// Captures nothing, so clang emits a global block and Metal's copy of it is free.
				reinterpret_cast<void (*)(id, SEL, void (^)(id))>(objc_msgSend)(self, k_sel_add_completed, ^(id done) { on_gpu_complete(done); });
			}

			t_in_commit = true;
		}

		reinterpret_cast<void (*)(id, SEL)>(s_gpu_original[Slot].load(std::memory_order_acquire))(self, cmd);

		if (outer)
		{
			t_in_commit = false;
		}
	}

	const char* install_gpu_time_hook(unsigned& classes, std::string& names)
	{
		classes = 0;

		if (!k_gpu_time_hook)
		{
			return "off";
		}

		const id device = metal_device();

		if (!device)
		{
			return "no_device";
		}

		const auto send = reinterpret_cast<id (*)(id, SEL)>(objc_msgSend);
		const auto send_id = reinterpret_cast<id (*)(id, SEL, id)>(objc_msgSend);
		const auto send_bool = reinterpret_cast<void (*)(id, SEL, BOOL)>(objc_msgSend);
		Class found[k_gpu_slots]{};
		int count = 0;

		const id queue = send(device, sel_registerName("newCommandQueue"));
		const Class descriptor_class = objc_getClass("MTLCommandBufferDescriptor");
		const id descriptor = queue && descriptor_class
			? send(send(reinterpret_cast<id>(descriptor_class), sel_registerName("alloc")), sel_registerName("init"))
			: nullptr;

		if (descriptor)
		{
			void* const pool = objc_autoreleasePoolPush();

			for (const BOOL retained : {YES, NO})
			{
				send_bool(descriptor, sel_registerName("setRetainedReferences:"), retained);
				const id cb = send_id(queue, sel_registerName("commandBufferWithDescriptor:"), descriptor);
				const Class cls = cb ? object_getClass(cb) : nullptr;

				if (cls && count < k_gpu_slots && std::find(found, found + count, cls) == found + count)
				{
					found[count++] = cls;
				}
			}

			objc_autoreleasePoolPop(pool);
		}

		for (const id owned : {descriptor, queue})
		{
			if (owned)
			{
				send(owned, sel_registerName("release"));
			}
		}

		if (count == 0)
		{
			return "no_buffer";
		}

		int kept = 0;

		for (int i = 0; i < count; i++)
		{
			bool answers = true;

			for (const SEL sel : {k_sel_commit, k_sel_add_completed, k_sel_status, k_sel_gpu_start, k_sel_gpu_end})
			{
				answers = answers && class_respondsToSelector(found[i], sel);
			}

			if (answers)
			{
				found[kept++] = found[i];
			}
		}

		if (kept == 0)
		{
			return "no_selector";
		}

		{
			std::lock_guard lock(s_gpu_mutex);
			s_gpu.window_start = host_seconds();
		}

		// Every original first, so a subclass never captures this file's own replacement as its original.
		for (int i = 0; i < kept; i++)
		{
			s_gpu_original[i].store(class_getMethodImplementation(found[i], k_sel_commit), std::memory_order_release);
		}

		// class_replaceMethod adds an override when the class only inherits commit, so no superclass changes.
		const IMP replacements[k_gpu_slots] = {reinterpret_cast<IMP>(&gpu_commit<0>), reinterpret_cast<IMP>(&gpu_commit<1>)};

		for (int i = 0; i < kept; i++)
		{
			class_replaceMethod(found[i], k_sel_commit, replacements[i], "v@:");
			names += (names.empty() ? "" : ",") + std::string(class_getName(found[i]));
		}

		classes = static_cast<unsigned>(kept);
		return "installed";
	}

	// iOS-only call, so the Mac reads -1; Apple documents 0 as either "not an app" or "already over".
	s64 headroom_mib()
	{
#if TARGET_OS_IPHONE
		return static_cast<s64>(os_proc_available_memory() >> 20);
#else
		return -1;
#endif
	}

	void on_memory_pressure(void* context)
	{
		const auto level = dispatch_source_get_data(static_cast<dispatch_source_t>(context));
		const bool critical = (level & DISPATCH_MEMORYPRESSURE_CRITICAL) != 0;

		if (critical)
		{
			vk::vmm_notify_os_memory_critical();
		}

		diag_log.warning("memory-pressure level=%s", critical ? "critical" : "warn");
	}

	// The core's CPU line averages over every core on Apple, which hides one maxed thread; 100 is one full core.
	std::string busy_threads()
	{
		const task_t task = mach_task_self();
		thread_act_array_t list{};
		mach_msg_type_number_t count = 0;

		if (task_threads(task, &list, &count) != KERN_SUCCESS)
		{
			return "?";
		}

		std::vector<std::pair<u32, std::string>> busy;
		u32 spu = 0, ppu = 0, rsx = 0;

		for (mach_msg_type_number_t i = 0; i < count; i++)
		{
			thread_extended_info_data_t info{};
			mach_msg_type_number_t size = THREAD_EXTENDED_INFO_COUNT;

			if (thread_info(list[i], THREAD_EXTENDED_INFO, reinterpret_cast<thread_info_t>(&info), &size) == KERN_SUCCESS)
			{
				const u32 percent = static_cast<u32>(info.pth_cpu_usage) * 100 / TH_USAGE_SCALE;

				// shortcut: the totals add floored per-thread percents and skip Iso[ and RSX.W threads; add a prefix when a report needs them.
				const std::string_view name(info.pth_name, strnlen(info.pth_name, sizeof(info.pth_name)));

				if (name.starts_with("PPU["))
				{
					ppu += percent;
				}
				else if (name.starts_with("SPU[") || name.starts_with("RawSPU["))
				{
					spu += percent;
				}
				else if (name.starts_with("rsx::thread"))
				{
					rsx += percent;
				}

				if (percent >= 5)
				{
					busy.emplace_back(percent, name.empty() ? std::string("?") : std::string(name));
				}
			}

			mach_port_deallocate(task, list[i]);
		}

		vm_deallocate(task, reinterpret_cast<vm_address_t>(list), sizeof(thread_t) * count);

		std::sort(busy.begin(), busy.end(), [](const auto& a, const auto& b) { return a.first > b.first; });

		std::string out;

		for (usz i = 0; i < std::min<usz>(busy.size(), 6); i++)
		{
			fmt::append(out, "%s%s=%u%%", i ? " | " : "", busy[i].second, busy[i].first);
		}

		fmt::append(out, "%ssum spu=%u%% ppu=%u%% rsx=%u%%", out.empty() ? "none | " : " | ", spu, ppu, rsx);

		return out;
	}

	// Where every guest thread is once per stall; it takes no lock it can wait on and never stops a thread.
	struct host_regs
	{
		u64 pc = 0;
		u64 lr = 0;
		u64 fp = 0;
		u64 sp = 0;
		// pc, lr, then up to 16 walked return addresses.
		u64 frames[18]{};
		u32 count = 0;
	};

	constexpr int k_host_no_arch = -1;

	// shortcut: drops a pointer-authentication signature; 47 bits covers every user address today, widen it if Apple's user VA grows.
	constexpr u64 k_user_address_mask = (1ull << 47) - 1;

	// Frame records are read through vm_read_overwrite, which fails instead of faulting, so a bad frame pointer ends the walk.
	int host_frames(thread_t port, host_regs& out)
	{
#if defined(__arm64__) || defined(__aarch64__)
		arm_thread_state64_t ts{};
		mach_msg_type_number_t tsc = ARM_THREAD_STATE64_COUNT;
		const kern_return_t kr = thread_get_state(port, ARM_THREAD_STATE64, reinterpret_cast<thread_state_t>(&ts), &tsc);

		if (kr != KERN_SUCCESS)
		{
			return kr;
		}

		// Through the accessors, which strip an arm64e signature.
		out.pc = static_cast<u64>(__darwin_arm_thread_state64_get_pc(ts)) & k_user_address_mask;
		out.lr = static_cast<u64>(__darwin_arm_thread_state64_get_lr(ts)) & k_user_address_mask;
		out.fp = static_cast<u64>(__darwin_arm_thread_state64_get_fp(ts));
		out.sp = static_cast<u64>(__darwin_arm_thread_state64_get_sp(ts));
		out.frames[0] = out.pc;
		out.frames[1] = out.lr;
		out.count = 2;

		u64 fp = out.fp;

		// The stack grows down, so each record must sit above the last one; that and the cap bound the walk.
		while (out.count < std::size(out.frames) && fp != 0 && fp % 8 == 0)
		{
			u64 record[2]{};
			vm_size_t got = 0;

			if (vm_read_overwrite(mach_task_self(), static_cast<vm_address_t>(fp), sizeof(record),
				reinterpret_cast<vm_address_t>(record), &got) != KERN_SUCCESS || got != sizeof(record))
			{
				break;
			}

			const u64 ret = record[1] & k_user_address_mask;

			if (ret == 0)
			{
				break;
			}

			out.frames[out.count++] = ret;

			if (record[0] <= fp)
			{
				break;
			}

			fp = record[0];
		}

		return 0;
#else
		static_cast<void>(port);
		static_cast<void>(out);
		return k_host_no_arch;
#endif
	}

	// Basename only: the full path holds the app container's identifier on a phone.
	std::string annotate_frames(const host_regs& r)
	{
		static const void* const s_own_base = []() -> const void*
		{
			Dl_info own{};
			return dladdr(reinterpret_cast<const void*>(&annotate_frames), &own) ? own.dli_fbase : nullptr;
		}();

		std::string out;

		for (u32 i = 0; i < r.count; i++)
		{
			fmt::append(out, "%s0x%llx", i ? "," : "", r.frames[i]);
			Dl_info where{};

			if (dladdr(reinterpret_cast<const void*>(r.frames[i]), &where) && where.dli_fbase)
			{
				const char* const slash = where.dli_fname ? std::strrchr(where.dli_fname, '/') : nullptr;
				const char* const base = where.dli_fname ? (slash ? slash + 1 : where.dli_fname) : "?";
				fmt::append(out, "@%s+0x%llx", base, r.frames[i] - reinterpret_cast<u64>(where.dli_fbase));

				if (where.dli_fbase != s_own_base && where.dli_sname)
				{
					fmt::append(out, "(%s)", where.dli_sname);
				}
			}
		}

		return out;
	}

	struct ppu_snapshot
	{
		u32 id = 0;
		std::string name;
		u32 cia = 0;
		u32 lr = 0;
		bs_t<cpu_flag> state{};
		// String literals in the core, so they outlive the lock.
		const char* current_function = nullptr;
		const char* last_function = nullptr;
		u64 start_time = 0;
		u64 native = 0;
	};

	struct spu_snapshot
	{
		u32 id = 0;
		std::string name;
		u32 pc = 0;
		bs_t<cpu_flag> state{};
	};

	// Runs while the RSX thread may hold anything, so it takes no lock and reads stack memory only through host_frames.
	void dump_rsx_side()
	{
		const u64 now_us = get_system_time();
		const u64 held = s_rsx_held_since.load(std::memory_order_relaxed);
		const std::string hold = held && held <= now_us ? std::to_string((now_us - held) / 1000) : "none";
		const char* gate = "na";
#if TARGET_OS_IPHONE
		gate = armsx3::ios_frame::present_gate_closed() ? "closed" : "open";
#endif

		rsx::thread* const rsx = rsx::get_current_renderer();

		if (!rsx)
		{
			diag_log.notice("stall rsx thread present=0 gate=%s hold_ms=%s", gate, hold);
		}
		else
		{
			const u64 last = rsx->display_ctrl().get_last_host_flip_timestamp();
			const std::string since = last && last <= now_us ? std::to_string((now_us - last) / 1000) : "none";
			const auto* const fifo = rsx->fifo_ctrl.get();
			const std::string pos = fifo ? fmt::format("0x%08x", fifo->get_pos()) : "none";
			const std::string cmd = fifo ? fmt::format("0x%08x", fifo->last_cmd()) : "none";

			diag_log.notice("stall rsx thread present=1 state=%s running=%d init=%u flip_index=%llu since_flip_ms=%s fifo_pos=%s "
				"last_cmd=%s gate=%s hold_ms=%s", rsx->state.load(), static_cast<int>(rsx->rsx_thread_running.load()),
				rsx->is_initialized.load(), rsx->display_ctrl().get_flip_index(), since, pos, cmd, gate, hold);
		}

		struct rsx_host
		{
			std::string name;
			std::string run;
			u32 cpu = 0;
			int pri = 0;
			int sleep_s = 0;
			std::string tail;
		};

		std::vector<rsx_host> found;
		const task_t task = mach_task_self();
		thread_act_array_t list{};
		mach_msg_type_number_t count = 0;

		if (task_threads(task, &list, &count) == KERN_SUCCESS)
		{
			for (mach_msg_type_number_t i = 0; i < count; i++)
			{
				thread_extended_info_data_t info{};
				mach_msg_type_number_t size = THREAD_EXTENDED_INFO_COUNT;

				if (thread_info(list[i], THREAD_EXTENDED_INFO, reinterpret_cast<thread_info_t>(&info), &size) != KERN_SUCCESS)
				{
					continue;
				}

				const std::string_view name(info.pth_name, strnlen(info.pth_name, sizeof(info.pth_name)));
				const bool is_rsx = name.starts_with("rsx::thread");

				if (!is_rsx && !name.starts_with("RSX.W") && !name.starts_with("VBlank"))
				{
					continue;
				}

				rsx_host h{std::string(name), {}, static_cast<u32>(info.pth_cpu_usage) * 100 / TH_USAGE_SCALE, info.pth_curpri,
					info.pth_sleep_time, {}};

				switch (info.pth_run_state)
				{
				case TH_STATE_RUNNING: h.run = "running"; break;
				case TH_STATE_STOPPED: h.run = "stopped"; break;
				case TH_STATE_WAITING: h.run = "waiting"; break;
				case TH_STATE_UNINTERRUPTIBLE: h.run = "uninterruptible"; break;
				case TH_STATE_HALTED: h.run = "halted"; break;
				default: h.run = "?" + std::to_string(info.pth_run_state); break;
				}

				if (is_rsx)
				{
					host_regs regs;
					const int got = host_frames(list[i], regs);
					h.tail = got == 0 ? fmt::format(" pc=0x%llx lr=0x%llx fp=0x%llx sp=0x%llx frames=%s", regs.pc, regs.lr, regs.fp,
						regs.sp, annotate_frames(regs)) : got == k_host_no_arch ? std::string(" host=no_arch") : fmt::format(" host=kr%d", got);
				}

				found.push_back(std::move(h));
			}

			for (mach_msg_type_number_t i = 0; i < count; i++)
			{
				mach_port_deallocate(task, list[i]);
			}

			vm_deallocate(task, reinterpret_cast<vm_address_t>(list), sizeof(thread_t) * count);
		}

		for (const rsx_host& h : found)
		{
			diag_log.notice("stall rsx host name='%s' run=%s cpu=%u pri=%d sleep_s=%d%s", h.name, h.run, h.cpu, h.pri, h.sleep_s, h.tail);
		}
	}

	void dump_stalled_threads(armsx3::bridge::diag::stall_reason reason)
	{
		std::vector<ppu_snapshot> ppus;
		std::vector<spu_snapshot> spus;

		// Each entry is a +1 send right until the release below, so an exited thread fails thread_get_state instead of hitting a reused name.
		const task_t task = mach_task_self();
		thread_act_array_t list{};
		mach_msg_type_number_t count = 0;
		const bool have_list = task_threads(task, &list, &count) == KERN_SUCCESS;
		std::vector<std::pair<u64, thread_t>> natives;

		for (mach_msg_type_number_t i = 0; have_list && i < count; i++)
		{
			natives.emplace_back(reinterpret_cast<u64>(pthread_from_mach_thread_np(list[i])), list[i]);
		}

		// try_lock_shared never blocks; a shared lock here could wait forever behind a stuck writer.
		const bool locked = id_manager::g_mutex.try_lock_shared();

		if (g_fxo->is_init<id_manager::id_map<named_thread<ppu_thread>>>())
		{
			idm::select<named_thread<ppu_thread>>([&](u32 id, named_thread<ppu_thread>& ppu)
			{
				ppu_snapshot s;
				s.id = id;
				// The same string the core's "is too sleepy" line prints, so the two match by eye.
				s.name = thread_ctrl::get_name(ppu);
				s.cia = ppu.cia;
				s.lr = static_cast<u32>(ppu.lr);
				s.state = ppu.state.load();
				s.current_function = ppu.current_function;
				s.last_function = ppu.last_function;
				s.start_time = ppu.start_time;
				s.native = thread_ctrl::get_native_id(ppu);
				ppus.push_back(std::move(s));
			}, idm::unlocked);
		}

		if (g_fxo->is_init<id_manager::id_map<named_thread<spu_thread>>>())
		{
			idm::select<named_thread<spu_thread>>([&](u32 id, named_thread<spu_thread>& spu)
			{
				spus.push_back(spu_snapshot{id, thread_ctrl::get_name(spu), spu.pc, spu.state.load()});
			}, idm::unlocked);
		}

		if (locked)
		{
			id_manager::g_mutex.unlock_shared();
		}

		// 1 no port matched, 2 the register read failed (kr holds why), 3 not arm64, 0 read.
		struct host_result
		{
			int status = 1;
			int kr = 0;
			host_regs regs;
		};

		std::vector<host_result> hosts(ppus.size());

		for (usz i = 0; i < ppus.size(); i++)
		{
			const auto match = std::find_if(natives.begin(), natives.end(), [&](const auto& n) { return n.first && n.first == ppus[i].native; });

			if (match == natives.end())
			{
				continue;
			}

			const int got = host_frames(match->second, hosts[i].regs);
			hosts[i].status = got == 0 ? 0 : got == k_host_no_arch ? 3 : 2;
			hosts[i].kr = got;
		}

		if (have_list)
		{
			for (mach_msg_type_number_t i = 0; i < count; i++)
			{
				mach_port_deallocate(task, list[i]);
			}

			vm_deallocate(task, reinterpret_cast<vm_address_t>(list), sizeof(thread_t) * count);
		}

		diag_log.notice("stall dump reason=%s idm=%s ppu=%u spu=%u", armsx3::bridge::diag::stall_reason_word(reason),
			locked ? "locked" : "unlocked", ppus.size(), spus.size());

		const u64 now = get_guest_system_time();

		for (usz i = 0; i < ppus.size(); i++)
		{
			const ppu_snapshot& s = ppus[i];
			const host_result& h = hosts[i];
			// current_function is set inside an HLE function or an LV2 syscall.
			const char* const in = s.current_function ? "func" : s.last_function ? "last" : "none";
			const char* const func = s.current_function ? s.current_function : s.last_function ? s.last_function : "-";
			const double sleep_s = s.start_time && s.start_time <= now ? static_cast<double>(now - s.start_time) / 1e6 : 0.0;

			diag_log.notice("stall ppu id=0x%x cia=0x%08x lr=0x%08x state=%s in=%s func=%s sleep_s=%.1f name='%s'",
				s.id, s.cia, s.lr, s.state, in, func, sleep_s, s.name);

			switch (h.status)
			{
			case 0:
				diag_log.notice("stall host id=0x%x pc=0x%llx lr=0x%llx fp=0x%llx sp=0x%llx frames=%s", s.id, h.regs.pc, h.regs.lr,
					h.regs.fp, h.regs.sp, annotate_frames(h.regs));
				break;
			case 2: diag_log.notice("stall host id=0x%x host=kr%d", s.id, h.kr); break;
			case 3: diag_log.notice("stall host id=0x%x host=no_arch", s.id); break;
			default: diag_log.notice("stall host id=0x%x host=absent", s.id); break;
			}
		}

		for (const spu_snapshot& s : spus)
		{
			diag_log.notice("stall spu id=0x%x pc=0x%05x state=%s name='%s'", s.id, s.pc, s.state, s.name);
		}

		dump_rsx_side();
	}

	// Runs right before a problem report stages the log, so a game that stopped drawing reaches the report with its dump.
	void sync_stall_dump()
	{
		std::lock_guard lock(s_stall_mutex);

		if (armsx3::bridge::diag::stall_on_sync(s_stall, static_cast<u64>(Emu.GetEmulationIdentifier())) ==
			armsx3::bridge::diag::stall_reason::sync)
		{
			dump_stalled_threads(armsx3::bridge::diag::stall_reason::sync);
		}
	}

	// Notice and never error: the log ring keeps every error-level line, and this one would push the errors out.
	void perf_loop()
	{
		pthread_setname_np("ARMSX3 diag perf");

		using clock = std::chrono::steady_clock;

		// shortcut: the state and the two RSX fields are not read in one atomic step; a hard guarantee needs a core hook.
		uint64_t vblanks = 0;
		uint64_t last_flips = 0;
		Rpcs3IOS_GetFrameCounters(&vblanks, &last_flips);

		// Zero, not a load: the counter is installed before this thread starts, so lines logged in between would vanish.
		u64 last_adds = 0;
		u64 ticks = 0;
		bool prepare_told = false;
		std::vector<u32> window;
		auto last = clock::now();

		for (;;)
		{
			std::this_thread::sleep_for(std::chrono::seconds(1));

			// Every boot resets the channel levels, so verbose is re-asserted here within a second.
			{
				std::lock_guard lock(s_verbose_mutex);

				if (s_verbose && logs::get_level("RSX") != logs::level::trace)
				{
					set_verbose_channels(logs::level::trace);
				}
			}

			// Silence All Logs puts every channel at `always`, which would stop this line; IOSDIAG alone comes back.
			if (logs::get_level("IOSDIAG") < logs::level::notice)
			{
				logs::set_level("IOSDIAG", logs::level::notice);
			}

			// shortcut: configure_logs lowers these on every Load, Run and stop, and this raise lags it by up to a second.
			quiet_channels();

			uint64_t flips = 0;
			Rpcs3IOS_GetFrameCounters(&vblanks, &flips);

			// The swap hands the RSX thread back this vector's capacity, so it stops allocating after a few windows.
			{
				std::lock_guard lock(s_window_mutex);
				window.swap(s_window);
			}

			// Before the stats, which sort the window.
			if (logs::get_level("IOSDIAG") == logs::level::trace)
			{
				std::string us;

				for (const u32 v : window)
				{
					fmt::append(us, "%s%u", us.empty() ? "" : ",", v);
				}

				diag_log.trace("frames n=%u us=%s", window.size(), us);
			}

			const armsx3::bridge::diag::frame_time_stats ft = armsx3::bridge::diag::compute_frame_time_stats(window);
			window.clear();

			const auto now = clock::now();
			const double seconds = std::chrono::duration<double>(now - last).count();
			// A boot or a stop resets the flip index; that interval reads 0.
			const u64 frames = flips >= last_flips ? flips - last_flips : 0;
			const double fps = seconds > 0 ? static_cast<double>(frames) / seconds : 0.0;
			const double frame_ms = fps > 0 ? 1000.0 / fps : 0.0;

			const u64 adds = s_shader_adds.load(std::memory_order_relaxed);

			u64 vk_texture = 0;
			u64 vk_surface = 0;
			u64 vk_total = 0;
			vk::vmm_get_usage_snapshot(vk_texture, vk_surface, vk_total);
			// vk_blk_mib minus vk_mib is VMA's slack; jit3_mib is pool 3's peak since launch.
			const u64 vk_blocks = vk::vmm_get_device_local_block_bytes();
			utils::jit_dual_map_report jit{};
			utils::jit_dual_map_report_pool(utils::jit_pool::llvm_code, &jit);
			// jit2_mib is pool 2's code high-water mark; it resets when the emulator stops.
			u8* const pool2 = jit_runtime::alloc(0, 0, true);
			const u64 jit2_bytes = pool2 ? static_cast<u64>(jit_runtime::alloc(0, 1, true) - pool2) : 0;
			utils::jit_dual_map_note_peaks(jit2_bytes, !Emu.IsStopped());

			char second_pass[320];

			if (utils::jit_second_pass_tick(!Emu.IsStopped(), second_pass, sizeof(second_pass)))
			{
				diag_log.notice("%s", second_pass);
			}

			// Once per launch: the StikDebug route's stderr is unreadable after the relaunch, so reports are read against this line.
			if (!prepare_told)
			{
				prepare_told = true;
				const char* const reason = jit.enabler_reason;
				diag_log.notice("jit prepare route=%s reason=%s script=%s pool2_mib=%u pool3_mib=%u pages_ok=%d elapsed_ms=%d budget_hit=%d first_refused_off=%d sessions=%u budget_pages=%u need2_mib=%u need3_mib=%u share2_mib=%u share3_mib=%u budget_ms=%d",
					reason == nullptr ? "none" : std::strcmp(reason, "helper-ok") == 0 ? "builtin" : "stikdebug",
					reason == nullptr ? "none" : reason, jit.script_outcome == nullptr ? "none" : jit.script_outcome,
					jit.pool2_limit_bytes >> 20, jit.pool3_limit_bytes >> 20, jit.script_pages_ok, jit.script_elapsed_ms,
					jit.script_budget_hit, jit.script_first_refused_off, jit.split_sessions, jit.split_budget_pages,
					jit.split_need2_bytes >> 20, jit.split_need3_bytes >> 20, jit.split_share2_bytes >> 20, jit.split_share3_bytes >> 20, jit.script_budget_ms);
			}

			const task_vm_info_data_t vm = vm_snapshot();
			const rusage_info_v4 io = io_snapshot();
			malloc_statistics_t heap{};
			// nullptr sums every zone.
			malloc_zone_statistics(nullptr, &heap);

			// The time is read inside the lock, so every span already added ended before it.
			armsx3::bridge::diag::gpu_busy_tick gpu{};
			{
				std::lock_guard lock(s_gpu_mutex);
				gpu = armsx3::bridge::diag::gpu_busy_take(s_gpu, host_seconds(), frames);
			}

			diag_log.notice("perf fps=%.1f frame_ms=%.1f shaders_new=%u mem_mib=%u headroom_mib=%d vk_mib=%u vk_tex_mib=%u vk_rtt_mib=%u int_mib=%u cmp_mib=%u gfx_mib=%u pnv_mib=%u malloc_mib=%u mtl_mib=%u vk_blk_mib=%u jit2_mib=%u jit3_mib=%u thermal=%s ft_n=%u ft_low1_fps=%.1f ft_p95_ms=%.1f ft_p99_ms=%.1f ft_gt100=%u ft_gt250=%u ft_gt500=%u dsk_wr_mib=%u lgc_wr_mib=%u gpu_ms=%.1f gpu_pct=%.0f gpu_cbs=%u",
				fps, frame_ms, adds - last_adds, vm.phys_footprint >> 20, headroom_mib(), vk_total >> 20, vk_texture >> 20, vk_surface >> 20,
				vm.internal >> 20, vm.compressed >> 20,
				static_cast<u64>(vm.ledger_tag_graphics_footprint + vm.ledger_tag_graphics_footprint_compressed) >> 20,
				static_cast<u64>(vm.ledger_purgeable_nonvolatile + vm.ledger_purgeable_novolatile_compressed) >> 20,
				static_cast<u64>(heap.size_in_use) >> 20, metal_allocated_bytes() >> 20,
				vk_blocks >> 20, jit2_bytes >> 20, (jit.llvm_units_peak * jit.llvm_unit_bytes) >> 20,
				thermal_word(), ft.n, ft.low1_fps, ft.p95_ms, ft.p99_ms, ft.gt100, ft.gt250, ft.gt500,
				io.ri_diskio_byteswritten >> 20, io.ri_logical_writes >> 20, gpu.gpu_ms, gpu.gpu_pct, gpu.cbs);

			// The lock is held across the dump, which never waits on a lock.
			{
				std::lock_guard lock(s_stall_mutex);
				const auto why = armsx3::bridge::diag::stall_tick(s_stall, static_cast<u64>(Emu.GetEmulationIdentifier()), Emu.IsRunning(),
					Emu.IsPaused(), Emu.IsStopped() && !Emu.IsStopped(true), frames);

				if (why != armsx3::bridge::diag::stall_reason::none)
				{
					dump_stalled_threads(why);
				}
			}

			// Every 5 s: a name list every second would double the log for little extra.
			if (ticks++ % 5 == 0)
			{
				diag_log.notice("threads busy=%s", busy_threads());
			}

			last = now;
			last_flips = flips;
			last_adds = adds;
		}
	}
}

namespace armsx3::bridge::diag
{
	unsigned long long fatal_thread_exits()
	{
		return s_fatal_exits.load(std::memory_order_relaxed);
	}

	bool start(const std::string& dir, bool verbose)
	{
		if (s_started.exchange(true))
		{
			return false;
		}

		quiet_channels();

		fs::device_stat stats{};

		if (!fs::statfs(dir, stats))
		{
			stats.avail_free = k_free_space_fallback;
		}

		// Leaked on purpose: it must outlive every thread that can still log.
		static logs::listener* const s_file = logs::make_file_listener(dir + "/RPCS3.log",
			std::min<u64>(stats.avail_free / 4, k_log_size_ceiling)).release();
		static_cast<void>(s_file);

		// The header, then every message stored since launch, then the core stops storing.
		logs::stored_message version{diag_log.always()};
		version.text = fmt::format("ARMSX3 iOS | RPCS3 v%s", rpcs3::get_verbose_version());

		logs::stored_message system{diag_log.always()};
		system.text = utils::get_system_info();

		logs::stored_message os{diag_log.always()};
		os.text = utils::get_OS_version_string();

		// A header message is written in order whatever the channel level, so the image line cannot be lost; hence the literal prefix.
		const std::string image_text = own_image_text();
		logs::stored_message image{diag_log.always()};
		image.text = "IOSDIAG: image " + (image_text.empty() ? std::string{"unavailable"} : image_text);

		logs::set_init({std::move(version), std::move(system), std::move(os), std::move(image)});

		// After set_init, so no trace line is stored in memory.
		{
			std::lock_guard lock(s_verbose_mutex);
			s_verbose = s_verbose || verbose;

			if (s_verbose)
			{
				set_verbose_channels(logs::level::trace);
			}
		}

		static shader_counter s_counter;
		logs::listener::add(&s_counter);
		static fatal_counter s_fatal;
		logs::listener::add(&s_fatal);

		unsigned gpu_classes = 0;
		std::string gpu_names;
		const char* const gpu_word = install_gpu_time_hook(gpu_classes, gpu_names);
		diag_log.notice("gpu time hook=%s classes=%u names=%s", gpu_word, gpu_classes, gpu_names.empty() ? "-" : gpu_names);

		// Leaked with the process: it reports for as long as the process lives.
		const dispatch_source_t pressure = dispatch_source_create(DISPATCH_SOURCE_TYPE_MEMORYPRESSURE, 0,
			DISPATCH_MEMORYPRESSURE_WARN | DISPATCH_MEMORYPRESSURE_CRITICAL, dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));

		if (pressure)
		{
			dispatch_set_context(pressure, pressure);
			dispatch_source_set_event_handler_f(pressure, on_memory_pressure);
			dispatch_resume(pressure);
		}

		std::thread(perf_loop).detach();
		return true;
	}

	void set_verbose(bool on)
	{
		std::lock_guard lock(s_verbose_mutex);
		s_verbose = on;

		if (!s_started)
		{
			return;
		}

		if (on)
		{
			set_verbose_channels(logs::level::trace);
		}
		else
		{
			set_verbose_channels(logs::level::notice);
		}
	}

	void sync()
	{
		// The dump goes first, so the flush below carries it to disk.
		sync_stall_dump();
		logs::listener::sync_all();
	}

	void note_rsx_hold(bool held)
	{
		s_rsx_held_since.store(held ? std::max<u64>(get_system_time(), 1) : 0, std::memory_order_relaxed);
	}

	void note_flip()
	{
		if (!s_started.load(std::memory_order_relaxed))
		{
			return;
		}

		auto* rsx = rsx::get_current_renderer();

		if (!rsx)
		{
			return;
		}

		// thread_local because each boot runs a new RSX thread, so a boot's first flip only sets the baseline.
		static thread_local u64 s_last_index = umax;
		static thread_local u64 s_last_time = 0;
		static thread_local u64 s_last_pause = 0;

		// An overlay's native-UI flip does not advance the guest flip index, so it is not a frame.
		const u64 index = rsx->display_ctrl().get_flip_index();

		if (index == s_last_index)
		{
			return;
		}

		const u64 time = get_system_time();
		const u64 pause = Emu.GetPauseTime();

		if (s_last_index != umax)
		{
			const u64 elapsed = time > s_last_time ? time - s_last_time : 0;
			const u64 paused = pause > s_last_pause ? pause - s_last_pause : 0;
			const u64 interval = std::min<u64>(elapsed > paused ? elapsed - paused : 0, UINT32_MAX);

			std::lock_guard lock(s_window_mutex);

			if (s_window.size() < k_window_cap)
			{
				s_window.push_back(static_cast<u32>(interval));
			}
		}

		s_last_index = index;
		s_last_time = time;
		s_last_pause = pause;
	}
}
