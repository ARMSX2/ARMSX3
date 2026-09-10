#pragma once

#include "sys_sync.h"
#include "sys_mutex.h"

struct lv2_mutex;

struct sys_cond_attribute_t
{
	be_t<u32> pshared;
	be_t<s32> flags;
	be_t<u64> ipc_key;

	union
	{
		nse_t<u64, 1> name_u64;
		char name[sizeof(u64)];
	};
};

struct lv2_cond final : lv2_obj
{
	static const u32 id_base = 0x86000000;

	const u64 key;
	const u64 name;
	const u32 mtx_id;

	lv2_mutex* mutex; // Associated Mutex
	shared_ptr<lv2_obj> _mutex;
	ppu_thread* sq{};

	// Who last signalled this condvar, and how often. The semaphore equivalent showed that
	// threads which look permanently parked are in fact cycling -- a snapshot catches them
	// mid-wait. Only a signal COUNT distinguishes "blocked forever" from "waiting normally
	// between iterations", and that distinction has flipped the diagnosis twice on this hang.
	atomic_t<u64> dbg_last_signal_us{0};
	atomic_t<u32> dbg_last_signaller{0};
	atomic_t<u64> dbg_signal_count{0};

	lv2_cond(u64 key, u64 name, u32 mtx_id, shared_ptr<lv2_obj> mutex0) noexcept;

	lv2_cond(utils::serial& ar) noexcept;
	static std::function<void(void*)> load(utils::serial& ar);
	void save(utils::serial& ar);

	CellError on_id_create();
};

class ppu_thread;

// Syscalls

error_code sys_cond_create(ppu_thread& ppu, vm::ptr<u32> cond_id, u32 mutex_id, vm::ptr<sys_cond_attribute_t> attr);
error_code sys_cond_destroy(ppu_thread& ppu, u32 cond_id);
error_code sys_cond_wait(ppu_thread& ppu, u32 cond_id, u64 timeout);
error_code sys_cond_signal(ppu_thread& ppu, u32 cond_id);
error_code sys_cond_signal_all(ppu_thread& ppu, u32 cond_id);
error_code sys_cond_signal_to(ppu_thread& ppu, u32 cond_id, u32 thread_id);
