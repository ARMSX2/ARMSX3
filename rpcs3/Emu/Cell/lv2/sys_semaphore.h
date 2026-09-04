#pragma once

#include "sys_sync.h"

#include "Emu/Memory/vm_ptr.h"

struct sys_semaphore_attribute_t
{
	be_t<u32> protocol;
	be_t<u32> pshared;
	be_t<u64> ipc_key;
	be_t<s32> flags;
	be_t<u32> pad;

	union
	{
		nse_t<u64, 1> name_u64;
		char name[sizeof(u64)];
	};
};

struct lv2_sema final : lv2_obj
{
	static const u32 id_base = 0x96000000;

	const lv2_protocol protocol;
	const u64 key;
	const u64 name;
	const s32 max;

	shared_mutex mutex;
	atomic_t<s32> val;

	// Who last posted this semaphore, and when. A blocked waiter names the object it is parked
	// on, but not the party that was supposed to release it -- and on a hang that is the question.
	// Recording the poster turns "VSync is waiting on 0x96009c00 forever" into "and thread X
	// posted it every frame until 125 seconds ago", which names the half that stopped.
	atomic_t<u64> dbg_last_post_us{0};
	atomic_t<u32> dbg_last_poster{0};
	atomic_t<u64> dbg_post_count{0};
	// Return address of the last post, i.e. the CALL SITE in guest code. cia during a syscall is
	// only the `sc` in the library wrapper and says nothing about who invoked it; lr is the caller.
	// On a producer that stops posting, this is what names the code path that used to run.
	atomic_t<u32> dbg_last_post_lr{0};
	ppu_thread* sq{};

	lv2_sema(u32 protocol, u64 key, u64 name, s32 max, s32 value) noexcept
		: protocol{static_cast<u8>(protocol)}
		, key(key)
		, name(name)
		, max(max)
		, val(value)
	{
	}

	lv2_sema(utils::serial& ar);
	static std::function<void(void*)> load(utils::serial& ar);
	void save(utils::serial& ar);
};

// Aux
class ppu_thread;

// Syscalls

error_code sys_semaphore_create(ppu_thread& ppu, vm::ptr<u32> sem_id, vm::ptr<sys_semaphore_attribute_t> attr, s32 initial_val, s32 max_val);
error_code sys_semaphore_destroy(ppu_thread& ppu, u32 sem_id);
error_code sys_semaphore_wait(ppu_thread& ppu, u32 sem_id, u64 timeout);
error_code sys_semaphore_trywait(ppu_thread& ppu, u32 sem_id);
error_code sys_semaphore_post(ppu_thread& ppu, u32 sem_id, s32 count);
error_code sys_semaphore_get_value(ppu_thread& ppu, u32 sem_id, vm::ptr<s32> count);
