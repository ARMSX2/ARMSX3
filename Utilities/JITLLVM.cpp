#include "util/types.hpp"
#include "util/sysinfo.hpp"
#include "Utilities/Thread.h"
#include "JIT.h"
#include "StrFmt.h"
#include "File.h"
#include "util/logs.hpp"
#include "mutex.h"
#include "util/vm.hpp"
#include "util/asm.hpp"
#include "Crypto/unzip.h"

#include <charconv>
#include <functional>
#include <new>

#if defined(__APPLE__)
#include <pthread.h>
#include <TargetConditionals.h>
#if defined(ARCH_ARM64)
#include <cstdio>
#include <cstdlib>
#include <sys/mman.h>
#include <unistd.h>
#endif
#elif defined(ANDROID)
#include "util/cctype.hpp"
#endif

LOG_CHANNEL(jit_log, "JIT");

#ifdef LLVM_AVAILABLE

#include <unordered_map>

#ifdef _MSC_VER
#pragma warning(push, 0)
#else
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wall"
#pragma GCC diagnostic ignored "-Wextra"
#pragma GCC diagnostic ignored "-Wold-style-cast"
#pragma GCC diagnostic ignored "-Wunused-parameter"
#pragma GCC diagnostic ignored "-Wstrict-aliasing"
#pragma GCC diagnostic ignored "-Wredundant-decls"
#pragma GCC diagnostic ignored "-Weffc++"
#pragma GCC diagnostic ignored "-Wmissing-noreturn"
#endif
#include <llvm/Support/CodeGen.h>
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/TargetSelect.h"
#include "llvm/TargetParser/Host.h"
#include "llvm/ExecutionEngine/ExecutionEngine.h"
#include "llvm/ExecutionEngine/RTDyldMemoryManager.h"
#include "llvm/ExecutionEngine/ObjectCache.h"
#include "llvm/ExecutionEngine/JITEventListener.h"
#include "llvm/Object/ObjectFile.h"
#include "llvm/Object/SymbolSize.h"
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
#include "llvm/ADT/StringSet.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/Mangler.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/CRC.h"
#include <chrono>
#endif
#ifdef _MSC_VER
#pragma warning(pop)
#else
#pragma GCC diagnostic pop
#endif

#ifdef ARCH_ARM64
#include "Emu/CPU/Backends/AArch64/AArch64Common.h"
#endif

#if defined(__APPLE__) && defined(ARCH_ARM64)
#include "ios_jit_dual_map.h"
#endif

namespace
{
	thread_local std::string* g_llvm_fatal_message = nullptr;

	// Points at a flag in run_recoverable_llvm's frame while a recoverable job runs. The
	// new_handler sets it, so it must be a plain bool: that handler runs because malloc just
	// failed, and cannot allocate a message.
	thread_local bool* g_llvm_out_of_memory = nullptr;

	template <typename F>
	bool run_recoverable_llvm(F&& func, std::string& error)
	{
		error.clear();

		bool out_of_memory = false;

		// Run LLVM codegen in a disposable thread. If LLVM invokes the fatal
		// handler, only this helper thread exits.
		named_thread worker("LLVM JIT", [&]()
		{
#if defined(__APPLE__) && !TARGET_OS_IPHONE && !defined(ARCH_ARM64)
			pthread_jit_write_protect_np(false);
#endif
			g_llvm_fatal_message = &error;
			g_llvm_out_of_memory = &out_of_memory;

			std::forward<F>(func)();

			g_llvm_out_of_memory = nullptr;
			g_llvm_fatal_message = nullptr;
#if defined(__APPLE__) && !TARGET_OS_IPHONE && !defined(ARCH_ARM64)
			pthread_jit_write_protect_np(true);
#endif
		});

		worker();
		const bool result = static_cast<thread_state>(worker) == thread_state::finished;

		if (!result && error.empty())
		{
			error = out_of_memory ? "Out of memory (operator new)" : "LLVM crash recovery invoked";
		}

		return result;
	}

	// Out of memory inside the JIT's memory manager.
	//
	// fmt::throw_exception does not throw: it ends the thread through thread_ctrl::emergency_exit,
	// which logs the reason and never hands it back. So inside run_recoverable_llvm every one of
	// these came back as "LLVM crash recovery invoked", and the PPU compiler could not tell the
	// device running out of memory from a codegen failure. Metal Gear Solid V: The Phantom Pain
	// (BLUS31491) did exactly that on an Odin 3: the module was retried at once with
	// allocator-friendly codegen, a second large compile under the same pressure, and the app died
	// seconds later. Hand the reason back the way the LLVM fatal handler does.
	[[noreturn]] void jit_out_of_memory(const std::string& message)
	{
		if (g_llvm_fatal_message)
		{
			*g_llvm_fatal_message = message;
			thread_ctrl::silent_exit();
		}

		fmt::throw_exception("%s", message);
	}
}

// Runs func on a disposable thread, the way try_add runs codegen, so that LLVM's fatal handler or
// running out of memory ends that thread rather than the caller. False when it did not finish;
// error then says why, starting with "Out of memory" when that was the reason. The PPU compile
// workers run translation through this (PPUThread.cpp).
bool jit_run_recoverable(const std::function<void()>& func, std::string& error)
{
	return run_recoverable_llvm(func, error);
}

const bool jit_initialize = []() -> bool
{
	llvm::InitializeNativeTarget();
	llvm::InitializeNativeTargetAsmPrinter();
	llvm::InitializeNativeTargetAsmParser();
	LLVMLinkInMCJIT();
	return true;
}();

[[noreturn]] static void null(const char* name)
{
	fmt::throw_exception("Null function: %s", name);
}

namespace vm
{
	extern u8* const g_sudo_addr;
}

static shared_mutex null_mtx;

static std::unordered_map<std::string, u64> null_funcs;

static u64 make_null_function(const std::string& name)
{
	if (name.starts_with("__0x"))
	{
		u32 addr = -1;
		auto res = std::from_chars(name.c_str() + 4, name.c_str() + name.size(), addr, 16);

		if (res.ec == std::errc() && res.ptr == name.c_str() + name.size() && addr < 0x8000'0000)
		{
			fmt::throw_exception("Unhandled symbols cementing! (name='%s'", name);
		}
	}

	std::lock_guard lock(null_mtx);

	if (u64& func_ptr = null_funcs[name]) [[likely]]
	{
		// Already exists
		return func_ptr;
	}
	else
	{
		using namespace asmjit;

		// Build a "null" function that contains its name
		const auto func = build_function_asm<void (*)()>("NULL", [&](native_asm& c, auto& args)
		{
#if defined(ARCH_X64)
			Label data = c.newLabel();
			c.lea(args[0], x86::qword_ptr(data, 0));
			c.jmp(Imm(&null));
			c.align(AlignMode::kCode, 16);
			c.bind(data);

			// Copy function name bytes
			for (char ch : name)
				c.db(ch);
			c.db(0);
			c.align(AlignMode::kData, 16);
#else
			// AArch64 implementation
			Label data = c.newLabel();
			Label jump_address = c.newLabel();
			c.ldr(args[0], arm::ptr(data, 0));
			c.ldr(a64::x14, arm::ptr(jump_address, 0));
			c.br(a64::x14);

			// Data frame
			c.align(AlignMode::kCode, 16);
			c.bind(jump_address);
			c.embedUInt64(reinterpret_cast<u64>(&null));

			c.align(AlignMode::kData, 16);
			c.bind(data);
			c.embed(name.c_str(), name.size());
			c.embedUInt8(0U);
			c.align(AlignMode::kData, 16);
#endif
		});

		func_ptr = reinterpret_cast<u64>(func);
		return func_ptr;
	}
}

struct JITAnnouncer : llvm::JITEventListener
{
	void notifyObjectLoaded(u64, const llvm::object::ObjectFile& obj, const llvm::RuntimeDyld::LoadedObjectInfo& info) override
	{
		using namespace llvm;

		object::OwningBinary<object::ObjectFile> debug_obj_ = info.getObjectForDebug(obj);
		if (!debug_obj_.getBinary())
		{
#ifdef __linux__
			jit_log.error("LLVM: Failed to announce JIT events (no debug object)");
#endif
			return;
		}

		const object::ObjectFile& debug_obj = *debug_obj_.getBinary();

		for (const auto& [sym, size] : computeSymbolSizes(debug_obj))
		{
			Expected<object::SymbolRef::Type> type_ = sym.getType();
			if (!type_ || *type_ != object::SymbolRef::ST_Function)
				continue;

			Expected<StringRef> name = sym.getName();
			if (!name)
				continue;

			Expected<u64> addr = sym.getAddress();
			if (!addr)
				continue;

			jit_announce(*addr, size, {name->data(), name->size()});
		}
	}
};

// Simple memory manager
struct MemoryManager1 : llvm::RTDyldMemoryManager
{
#if defined(__APPLE__) && defined(ARCH_ARM64)
	// Sizes the data half and allocate()'s bound; code goes to pool 3 chunks. Holds 50 parts (c_modules_per_jit here).
	static constexpr u64 c_max_size = 0x360'0000;
#else
	// 256 MiB for code or data
	static constexpr u64 c_max_size = 0x1000'0000;
#endif

	// Allocation unit (2M)
	static constexpr u64 c_page_size = 2 * 1024 * 1024;

	// Reserve 256 MiB blocks
	void* m_code_mems = nullptr;
	void* m_data_ro_mems = nullptr;
	void* m_data_rw_mems = nullptr;

	u64 code_ptr = 0;
	u64 data_ro_ptr = 0;
	u64 data_rw_ptr = 0;

#if defined(__APPLE__) && defined(ARCH_ARM64)
	// Code split across chunks is safe here: cross-section branches use stubs and Large + Static is absolute.
	std::vector<std::pair<u8*, u64>> m_code_chunks;
	u8* m_chunk = nullptr;
	u64 m_chunk_size = 0;
#endif

	// First fallback for non-existing symbols
	// May be a memory container internally
	std::function<u64(const std::string&)> m_symbols_cement;

#if defined(ARCH_ARM64)
	// Code ranges allocated since the last finalizeMemory(), for icache maintenance
	std::vector<std::pair<u8*, uptr>> m_code_ranges;
#endif

	// Give the address space back when destroyed. Only for an auxiliary JIT, whose code is
	// written to the object cache and never run from this memory (see the destructor).
	bool m_release_on_destroy = false;

	// Reservations alive right now, for the note in reserve_once().
	static inline atomic_t<u64> s_held_count{0};

	MemoryManager1(std::function<u64(const std::string&)> symbols_cement = {}, bool release_on_destroy = false) noexcept
		: m_symbols_cement(std::move(symbols_cement))
		, m_release_on_destroy(release_on_destroy)
	{
	}

	// Reserve on first use, not on construction.
	//
	// 768 MiB of address space per instance, and the destructor deliberately keeps the
	// reservation (see below). An SPU thread builds one of these whether or not it ever
	// compiles anything (SPUThread.cpp makes a recompiler per thread), so a SPURS title that
	// cycles through thread groups burns the space a few hundred times over. A 64 bit Android
	// process has 512 GiB of it, against 128 TiB on desktop, so what is harmless upstream ran
	// Assassin's Creed IV out of ADDRESS SPACE, not memory: commits failed with ENOMEM while
	// the device still had 6 GB free and the process held 1.4 GB.
#if defined(__APPLE__) && defined(ARCH_ARM64)
	void reserve_once(u64 code_need = 0)
#else
	void reserve_once()
#endif
	{
		if (m_code_mems)
		{
			return;
		}

#if defined(__APPLE__) && defined(ARCH_ARM64)
		// Pool 3 code is carved inside the dual-map block, where jit_rw() reaches it, in chunks sized to
		// each object so firmware modules held until stop cannot exhaust the pool. The data half needs no
		// alias and stays outside. A null carve means there is no block: take upstream's reservation.
		m_code_mems = carve_chunk(code_need);

		if (!m_code_mems)
		{
			auto ptr = reinterpret_cast<u8*>(utils::memory_reserve(c_max_size * 3, true));
			m_code_mems = ptr;
			ptr += c_max_size;
			m_data_rw_mems = ptr;
		}
		else
		{
			m_data_rw_mems = utils::memory_reserve(c_max_size, false);
		}
#else
		auto ptr = reinterpret_cast<u8*>(utils::memory_reserve(c_max_size * 3, true));
		m_code_mems = ptr;
		// ptr += c_max_size;
		// m_data_ro_mems = ptr;
		 ptr += c_max_size;
		m_data_rw_mems = ptr;
#endif

		// Every 64 instances, because the ceiling is the thing worth seeing coming: this is what
		// ran out on Assassin's Creed IV, and the number says how close a long session is.
		static atomic_t<u64> s_reserved_count{0};

		const u64 held = ++s_held_count;

		if (const u64 n = ++s_reserved_count; n % 64 == 0)
		{
			jit_log.notice("JIT: %u reservations made, %u held now: %u GiB of address space", n, held, held * c_max_size * 3 / (1024 * 1024 * 1024));
		}
	}

#if defined(__APPLE__) && defined(ARCH_ARM64)
	// A pool 3 chunk of at least need bytes in 2 MiB granules; nullptr only when there is no block.
	u8* carve_chunk(u64 need)
	{
		const u64 size = utils::align(std::max<u64>(need, 1), u64{utils::jit_pool3_unit_granularity});
		u8* const chunk = static_cast<u8*>(utils::jit_dual_map_carve(utils::jit_pool::llvm_code, size));

		if (!chunk)
		{
			return nullptr;
		}

		// Inside the block this commit only checks the access the block already has. A real mprotect
		// asking for write and execute here reports success yet strips execute for good, so block
		// pages must never be re-protected; vm_native keeps them away from mprotect.
		if (!utils::try_memory_commit(chunk, size, utils::protection::wx))
		{
			fmt::throw_exception("Out of memory (commit failed: size=0x%x)", size);
		}

		m_code_chunks.emplace_back(chunk, size);
		m_chunk = chunk;
		m_chunk_size = size;
		code_ptr = 0;
		return chunk;
	}

	// Room in the current chunk, or a new chunk; called per object, so its sections stay together.
	void code_room(u64 size, u64 align)
	{
		if (utils::align(code_ptr, align) + size > m_chunk_size)
		{
			ensure(carve_chunk(size));
		}
	}
#endif

	MemoryManager1(const MemoryManager1&) = delete;

	MemoryManager1& operator=(const MemoryManager1&) = delete;

	~MemoryManager1() override
	{
		if (!m_code_mems)
		{
			// Never compiled anything, so nothing was ever reserved.
			return;
		}

		// An auxiliary JIT only writes objects to the cache; the code it emitted is loaded
		// again from disk into a long-lived JIT before anything runs it, so no pointer into
		// this memory survives it. Keeping its reservation, as below, only spends address
		// space, and a phone has 512 GiB of it. Metal Gear Solid V: The Phantom Pain
		// (BLUS31491) compiles about 500 PPU modules on a cold cache, each through one of
		// these: at 768 MiB apiece the log read "576 instances hold 432 GiB" when commits
		// started failing with ENOMEM and 1.5 GB still free, and scudo then died unable to
		// map anything at all, closing the app (Odin 3).
#if defined(__APPLE__) && defined(ARCH_ARM64)
		// Torn down the way it was built: outside the block it holds upstream's reservation.
		if (!utils::jit_dual_map_contains(m_code_mems))
		{
			if (m_release_on_destroy)
			{
				utils::memory_release(m_code_mems, c_max_size * 3);
				s_held_count--;
				return;
			}

			utils::memory_decommit(m_code_mems, c_max_size * 3, true);
		}
		else
		{
			// Decommit each chunk before returning it, so the next manager never bumps into stale code.
			for (const auto& [chunk, size] : m_code_chunks)
			{
				utils::memory_decommit(chunk, size, true);
				utils::jit_dual_map_release(utils::jit_pool::llvm_code, chunk);
			}

			// Released, not decommitted: decommit here unmaps and remaps by hint, which races other workers.
			utils::memory_release(m_data_rw_mems, c_max_size);
			s_held_count--;
		}
#else
		if (m_release_on_destroy)
		{
			utils::memory_release(m_code_mems, c_max_size * 3);
			s_held_count--;
			return;
		}

		// Hack: don't release to prevent reuse of address space, see jit_announce
		// constexpr auto how_much = [](u64 pos) { return utils::align(pos, pos < c_page_size ? c_page_size / 4 : c_page_size); };
		// utils::memory_decommit(m_code_mems, how_much(code_ptr));
		// utils::memory_decommit(m_data_ro_mems, how_much(data_ro_ptr));
		// utils::memory_decommit(m_data_rw_mems, how_much(data_rw_ptr));
		utils::memory_decommit(m_code_mems, c_max_size * 3, true);
#endif
	}

	llvm::JITSymbol findSymbol(const std::string& name) override
	{
		u64 addr = RTDyldMemoryManager::getSymbolAddress(name);

		if (!addr && m_symbols_cement)
		{
			addr = m_symbols_cement(name);
		}

		if (!addr)
		{
			addr = make_null_function(name);

			if (!addr)
			{
				fmt::throw_exception("Failed to link '%s'", name);
			}
		}

		return {addr, llvm::JITSymbolFlags::Exported};
	}

	u8* allocate(u64& alloc_pos, void*& block, uptr size, u64 align, utils::protection prot)
	{
		// block is a reference to the member this instance allocates from, so reserving here
		// gives the caller the address it was passed a reference to.
		reserve_once();
		align = align ? align : 16;
 
		const u64 sizea = utils::align(size, align);

		if (!size || align > c_page_size || sizea > c_max_size || sizea < size)
		{
			jit_log.fatal("Unsupported size/alignment (size=0x%x, align=0x%x)", size, align);
			return nullptr;
		}

		u64 oldp = alloc_pos;

		u64 olda = utils::align(oldp, align);

		ensure(olda >= oldp);
		ensure(olda < ~sizea);

		u64 newp = olda + sizea;

#if defined(__APPLE__) && defined(ARCH_ARM64)
		// Abort at the cause: past c_max_size a section would spill into a neighbouring carve or wrap over this unit.
		if (newp > c_max_size)
		{
			std::fprintf(stderr,
				"A MemoryManager1 allocation would leave its unit: size=0x%llx align=0x%llx "
				"pos=0x%llx would end at 0x%llx against a unit of 0x%llx bytes. Continuing would "
				"write past the carve into a neighbouring live manager's code, or wrap back over "
				"this manager's own, with no fault at the cause.\n",
				static_cast<unsigned long long>(size), static_cast<unsigned long long>(align),
				static_cast<unsigned long long>(oldp), static_cast<unsigned long long>(newp),
				static_cast<unsigned long long>(c_max_size));
			std::fflush(stderr);
			std::abort();
		}
#endif

		if ((newp - 1) / c_max_size != (oldp - 1) / c_max_size)
		{
			constexpr usz num_of_allocations = 1;

			if ((newp - 1) / c_max_size > num_of_allocations)
			{
				// Allocating more than one region does not work for relocations, needs more robust solution
				jit_out_of_memory(fmt::format("Out of memory (size=0x%x, align=0x%x)", size, align));
			}
		}

		// Update allocation counter
		alloc_pos = newp;

		constexpr usz page_quarter = c_page_size / 4;

		// Optimization: split the first allocation to 512 KiB for single-module compilers
		if (oldp < c_page_size && align < page_quarter && (std::min(newp, c_page_size) - 1) / page_quarter != (oldp - 1) / page_quarter)
		{
			const u64 pagea = utils::align(oldp, page_quarter);
			const u64 psize = utils::align(std::min(newp, c_page_size) - pagea, page_quarter);

			// try_ rather than memory_commit: a commit failure here is the device being out of
			// memory, and the caller has a real fallback -- the module does not compile and its
			// functions are interpreted. The fatal version reported it as "LLVM crash recovery
			// invoked", which reads like a codegen bug and sent this diagnosis the wrong way.
			if (!utils::try_memory_commit(reinterpret_cast<u8*>(block) + (pagea % c_max_size), psize, prot))
			{
				jit_out_of_memory(fmt::format("Out of memory (commit failed: size=0x%x, align=0x%x)", size, align));
			}

			// Advance
			oldp = pagea + psize;
		}

		if ((newp - 1) / c_page_size != (oldp - 1) / c_page_size)
		{
			// Allocate pages on demand
			const u64 pagea = utils::align(oldp, c_page_size);
			const u64 psize = utils::align(newp - pagea, c_page_size);

			if (!utils::try_memory_commit(reinterpret_cast<u8*>(block) + (pagea % c_max_size), psize, prot))
			{
				jit_out_of_memory(fmt::format("Out of memory (commit failed: size=0x%x, align=0x%x)", size, align));
			}
		}

		return reinterpret_cast<u8*>(block) + (olda % c_max_size);
	}

#if defined(__APPLE__) && defined(ARCH_ARM64)
	// Called once per object before its sections, with its total code size, so a chunk fits the object.
	bool needsToReserveAllocationSpace() override
	{
		return true;
	}

	void reserveAllocationSpace(uintptr_t code_size, llvm::Align code_align, uintptr_t, llvm::Align, uintptr_t, llvm::Align) override
	{
		reserve_once(code_size);

		if (m_chunk)
		{
			code_room(code_size, code_align.value());
		}
	}
#endif

	u8* allocateCodeSection(uptr size, uint align, uint /*sec_id*/, llvm::StringRef /*sec_name*/) override
	{
#if defined(__APPLE__) && defined(ARCH_ARM64)
		reserve_once(size);

		u8* p = nullptr;

		if (m_chunk)
		{
			// Bump through the current chunk; reserveAllocationSpace already made room for the object.
			const u64 a = align ? align : 16;
			const u64 sizea = utils::align(u64{size}, a);

			if (!size || a > c_page_size)
			{
				jit_log.fatal("Unsupported size/alignment (size=0x%x, align=0x%x)", size, align);
			}
			else
			{
				code_room(sizea, a);
				const u64 olda = utils::align(code_ptr, a);
				code_ptr = olda + sizea;
				p = m_chunk + olda;
			}
		}
		else
		{
			p = allocate(code_ptr, m_code_mems, size, align, utils::protection::wx);
		}
#else
		u8* const p = allocate(code_ptr, m_code_mems, size, align, utils::protection::wx);
#endif

#if defined(ARCH_ARM64)
		// Track for instruction-cache maintenance in finalizeMemory()
		if (p)
		{
			m_code_ranges.emplace_back(p, size);
		}
#endif

#if defined(__APPLE__) && defined(ARCH_ARM64)
		// TXM splits write and execute addresses: LLVM copies the section into the alias returned here,
		// notifyObjectLoaded remaps it to p for relocations and entry points, and m_code_ranges keeps p
		// because an instruction-cache flush aimed at the alias does nothing.
		return p ? utils::jit_rw(p) : nullptr;
#else
		return p;
#endif
	}

#if defined(__APPLE__) && defined(ARCH_ARM64)
	// Re-declared so the base overloads stay visible.
	using llvm::RTDyldMemoryManager::notifyObjectLoaded;

	void notifyObjectLoaded(llvm::ExecutionEngine* ee, const llvm::object::ObjectFile&) override
	{
		if (!ee)
		{
			return;
		}

		// Remapping is idempotent, so ranges left from an earlier unfinalised object are harmless.
		for (const auto& [p, size] : m_code_ranges)
		{
			ee->mapSectionAddress(utils::jit_rw(p), reinterpret_cast<u64>(p));
		}
	}
#endif

	u8* allocateDataSection(uptr size, uint align, uint /*sec_id*/, llvm::StringRef /*sec_name*/, bool is_ro) override
	{
		if (is_ro)
		{
			// Disabled
			//return allocate(data_ro_ptr, m_data_ro_mems, size, align, utils::protection::rw);
		}

		return allocate(data_rw_ptr, m_data_rw_mems, size, align, utils::protection::rw);
	}

	bool finalizeMemory(std::string* = nullptr) override
	{
#if defined(ARCH_ARM64)
		// See MemoryManager2::finalizeMemory(): RuntimeDyld relies on this callback
		// for instruction-cache maintenance of freshly written code sections.
		for (const auto& [p, size] : m_code_ranges)
		{
			asmjit::VirtMem::flushInstructionCache(p, size);
		}

		m_code_ranges.clear();
#endif
		return false;
	}

	void registerEHFrames(u8*, u64, usz) override
	{
	}

	void deregisterEHFrames() override
	{
	}
};

// Simple memory manager
struct MemoryManager2 : llvm::RTDyldMemoryManager
{
	// First fallback for non-existing symbols
	// May be a memory container internally
	std::function<u64(const std::string&)> m_symbols_cement;

#if defined(ARCH_ARM64)
	// Code ranges allocated since the last finalizeMemory(), for icache maintenance
	std::vector<std::pair<u8*, uptr>> m_code_ranges;
#endif

	MemoryManager2(std::function<u64(const std::string&)> symbols_cement = {}) noexcept
		: m_symbols_cement(std::move(symbols_cement))
	{
	}

	~MemoryManager2() override
	{
	}

	llvm::JITSymbol findSymbol(const std::string& name) override
	{
		u64 addr = RTDyldMemoryManager::getSymbolAddress(name);

		if (!addr && m_symbols_cement)
		{
			addr = m_symbols_cement(name);
		}

		if (!addr)
		{
			addr = make_null_function(name);

			if (!addr)
			{
				fmt::throw_exception("Failed to link '%s' (MM2)", name);
			}
		}

		return {addr, llvm::JITSymbolFlags::Exported};
	}

	u8* allocateCodeSection(uptr size, uint align, uint /*sec_id*/, llvm::StringRef /*sec_name*/) override
	{
		u8* const p = jit_runtime::alloc(size, align, true);

#if defined(ARCH_ARM64)
		// Track for instruction-cache maintenance in finalizeMemory()
		if (p)
		{
			m_code_ranges.emplace_back(p, size);
		}
#endif

#if defined(__APPLE__) && defined(ARCH_ARM64)
		// The same TXM split as MemoryManager1.
		return p ? utils::jit_rw(p) : nullptr;
#else
		return p;
#endif
	}

#if defined(__APPLE__) && defined(ARCH_ARM64)
	using llvm::RTDyldMemoryManager::notifyObjectLoaded;

	void notifyObjectLoaded(llvm::ExecutionEngine* ee, const llvm::object::ObjectFile&) override
	{
		if (!ee)
		{
			return;
		}

		for (const auto& [p, size] : m_code_ranges)
		{
			ee->mapSectionAddress(utils::jit_rw(p), reinterpret_cast<u64>(p));
		}
	}
#endif

	u8* allocateDataSection(uptr size, uint align, uint /*sec_id*/, llvm::StringRef /*sec_name*/, bool /*is_ro*/) override
	{
		return jit_runtime::alloc(size, align, false);
	}

	bool finalizeMemory(std::string* = nullptr) override
	{
#if defined(ARCH_ARM64)
		// RuntimeDyld calls finalizeMemory() after writing code and relies on it for
		// instruction-cache maintenance. This was a no-op: freshly emitted code was
		// never flushed, so other cores could execute stale icache contents for it.
		// x86 has a coherent instruction cache and never noticed. The asmjit helper
		// performs the required DC CVAU / IC IVAU broadcast sequence.
		for (const auto& [p, size] : m_code_ranges)
		{
			asmjit::VirtMem::flushInstructionCache(p, size);
		}

		m_code_ranges.clear();
#endif
		return false;
	}

	void registerEHFrames(u8*, u64, usz) override
	{
	}

	void deregisterEHFrames() override
	{
	}
};

#if defined(__APPLE__) && defined(ARCH_ARM64)
// MCJIT keeps every object buffer until the engine dies but never reads it after loading; this copy gives the pages back.
struct mapped_object final : llvm::MemoryBuffer
{
	void* const m_base;
	const usz m_size;

	mapped_object(void* base, usz size, usz used)
		: m_base(base)
		, m_size(size)
	{
		init(static_cast<const char*>(base), static_cast<const char*>(base) + used, false);
	}

	~mapped_object() override
	{
		::munmap(m_base, m_size);
	}

	BufferKind getBufferKind() const override
	{
		return MemoryBuffer_MMap;
	}

	// nullptr when no mapping is available; the caller then keeps the original buffer.
	static std::unique_ptr<mapped_object> copy_of(const llvm::MemoryBuffer& from)
	{
		const usz used = from.getBufferSize();
		const usz size = utils::align<usz>(used, ::getpagesize());
		void* const base = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);

		if (base == MAP_FAILED)
		{
			return nullptr;
		}

		std::memcpy(base, from.getBufferStart(), used);
		return std::make_unique<mapped_object>(base, size, used);
	}

	void release() const
	{
		::mmap(m_base, m_size, PROT_READ, MAP_FIXED | MAP_ANON | MAP_PRIVATE, -1, 0);
	}
};
#endif

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
// SPU object cache checks. All but the gzip trailer check run only for compilers that carry stats.

// unzip returns a zero-padded buffer on an inflate error, so check the gzip trailer's CRC32 and size.
static bool gzip_trailer_matches(const std::vector<u8>& gz, const std::vector<u8>& data)
{
	if (gz.size() < 18)
	{
		return false;
	}

	const auto le32 = [&](usz at)
	{
		return u32{gz[at]} | u32{gz[at + 1]} << 8 | u32{gz[at + 2]} << 16 | u32{gz[at + 3]} << 24;
	};

	return le32(gz.size() - 4) == static_cast<u32>(data.size()) && le32(gz.size() - 8) == llvm::crc32(data);
}

// Usable only if it parses and defines every non-local function of the module, under its mangled name.
static bool object_defines_module(const llvm::Module& module, llvm::MemoryBufferRef ref, std::string& reason)
{
	auto object = llvm::object::ObjectFile::createObjectFile(ref);

	if (!object)
	{
		reason = llvm::toString(object.takeError());
		return false;
	}

	llvm::StringSet<> defined;

	for (const llvm::object::SymbolRef& symbol : (*object)->symbols())
	{
		auto flags = symbol.getFlags();
		auto name = symbol.getName();

		if (flags && name && !(*flags & llvm::object::SymbolRef::SF_Undefined))
		{
			defined.insert(*name);
		}

		if (!flags)
		{
			llvm::consumeError(flags.takeError());
		}

		if (!name)
		{
			llvm::consumeError(name.takeError());
		}
	}

	llvm::Mangler mangler;
	usz checked = 0;

	for (const llvm::Function& func : module)
	{
		// isDeclarationForLinker: available_externally bodies are not emitted, so they are not expected
		if (func.isDeclarationForLinker() || func.hasLocalLinkage())
		{
			continue;
		}

		llvm::SmallString<128> mangled;
		mangler.getNameWithPrefix(mangled, &func, false);

		if (!defined.contains(mangled))
		{
			reason = "object does not define " + func.getName().str();
			return false;
		}

		checked++;
	}

	if (!checked)
	{
		reason = "module defines no global function";
		return false;
	}

	return true;
}

// An inttoptr of a constant in [2^32, 2^48) is a host address baked into the code.
static bool is_host_address(const llvm::Value* value)
{
	const auto number = llvm::dyn_cast<llvm::ConstantInt>(value);
	return number && number->getValue().getActiveBits() > 32 && number->getValue().getActiveBits() <= 48;
}

// Globals are not followed: they are relocatable, which is how host_data_ptr names host data.
static bool constant_bakes(const llvm::Constant* constant, llvm::SmallPtrSetImpl<const llvm::Constant*>& seen)
{
	if (llvm::isa<llvm::GlobalValue>(constant) || !seen.insert(constant).second)
	{
		return false;
	}

	if (const auto expr = llvm::dyn_cast<llvm::ConstantExpr>(constant); expr && expr->getOpcode() == llvm::Instruction::IntToPtr && is_host_address(expr->getOperand(0)))
	{
		return true;
	}

	for (const llvm::Use& operand : constant->operands())
	{
		if (const auto inner = llvm::dyn_cast<llvm::Constant>(operand.get()); inner && constant_bakes(inner, seen))
		{
			return true;
		}
	}

	return false;
}

// The first global or function whose IR bakes a host address, or null.
static const llvm::GlobalValue* module_bakes_host_address(const llvm::Module& module)
{
	llvm::SmallPtrSet<const llvm::Constant*, 32> seen;

	for (const llvm::GlobalVariable& var : module.globals())
	{
		if (var.hasInitializer() && constant_bakes(var.getInitializer(), seen))
		{
			return &var;
		}
	}

	for (const llvm::Function& func : module)
	{
		for (const llvm::Instruction& inst : llvm::instructions(func))
		{
			if (llvm::isa<llvm::IntToPtrInst>(inst) && is_host_address(inst.getOperand(0)))
			{
				return &func;
			}

			for (const llvm::Use& operand : inst.operands())
			{
				if (const auto constant = llvm::dyn_cast<llvm::Constant>(operand.get()); constant && constant_bakes(constant, seen))
				{
					return &func;
				}
			}
		}
	}

	return nullptr;
}

// Proves the walk flags baked constants and passes small ones and loads through relocatable globals.
static bool walk_self_check()
{
	llvm::LLVMContext context;
	const auto ptr = llvm::PointerType::getUnqual(context);
	const auto i64 = llvm::Type::getInt64Ty(context);

	const auto bakes = [&](auto&& body)
	{
		llvm::Module module("walk_self_check", context);
		const auto func = llvm::Function::Create(llvm::FunctionType::get(ptr, false), llvm::GlobalValue::ExternalLinkage, "f", module);
		llvm::IRBuilder<> ir(llvm::BasicBlock::Create(context, "", func));
		ir.CreateRet(body(module, ir));
		return module_bakes_host_address(module) != nullptr;
	};

	const auto host = [&](llvm::IRBuilder<>& ir, u64 value)
	{
		return ir.CreateIntToPtr(ir.getInt64(value), ptr);
	};

	return bakes([&](llvm::Module&, llvm::IRBuilder<>& ir) -> llvm::Value* { return host(ir, 0x123456000); })
		&& bakes([&](llvm::Module& module, llvm::IRBuilder<>& ir) -> llvm::Value*
		{
			new llvm::GlobalVariable(module, ptr, true, llvm::GlobalValue::InternalLinkage, llvm::cast<llvm::Constant>(host(ir, 0x123456000)), "g");
			return llvm::ConstantPointerNull::get(ptr);
		})
		&& !bakes([&](llvm::Module&, llvm::IRBuilder<>& ir) -> llvm::Value* { return host(ir, 0xFFFFFFFF); })
		&& !bakes([&](llvm::Module& module, llvm::IRBuilder<>& ir) -> llvm::Value*
		{
			const auto ext = new llvm::GlobalVariable(module, i64, false, llvm::GlobalValue::ExternalLinkage, nullptr, "ext");
			return ir.CreateIntToPtr(ir.CreateLoad(i64, ext), ptr);
		});
}

static void ensure_walk_self_check()
{
	// Once per process: SPU workers call this concurrently, and a function-local static initialises exactly once.
	[[maybe_unused]] static const bool checked = ensure(walk_self_check(), "SPU object cache: IR walk self-check failed");
}

static u64 ns_since(std::chrono::steady_clock::time_point start)
{
	return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
}
#endif

// Helper class
class ObjectCache final : public llvm::ObjectCache
{
	const std::string& m_path;
	const std::add_pointer_t<jit_compiler> m_compiler = nullptr;
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
	// Cleared by getObject when the module bakes a host address; one ObjectCache per add().
	bool m_persist = true;
#endif

public:
	ObjectCache(const std::string& path, jit_compiler* compiler = nullptr)
		: m_path(path)
		, m_compiler(compiler)
	{
	}

	~ObjectCache() override = default;

	void notifyObjectCompiled(const llvm::Module* _module, llvm::MemoryBufferRef obj) override
	{
		std::string name = m_path;

		name.append(_module->getName());
		//fs::file(name, fs::rewrite).write(obj.getBufferStart(), obj.getBufferSize());
		name.append(".gz");

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
		jit_obj_cache_stats* const stats = m_compiler ? m_compiler->get_obj_cache_stats() : nullptr;

		// getObject found a baked host address: the module runs but is not written
		if (stats && !m_persist)
		{
			return;
		}

		const auto failed = [stats]()
		{
			if (stats)
			{
				stats->write_failed.fetch_add(1);
			}
		};
#endif
#if (defined(__ANDROID__) || defined(__APPLE__)) && defined(ARCH_ARM64)
		// Never the write that fills the disk. fs::file::write treats a failed write as fatal (an
		// ensure, so ENOSPC ends the app), and the SPU object cache writes during play on storage it
		// shares with everything else. So each SPU object is written only while at least 512 MB
		// (Android's own "storage low" line on large phones) plus its worst case would stay free;
		// past that, no more for the rest of this boot.
		if (stats)
		{
			constexpr u64 min_free_after = 512ull << 20;
			fs::device_stat free_stat{};

			if (stats->disk_low || !fs::statfs(m_path, free_stat) || free_stat.avail_free < min_free_after + obj.getBufferSize() * 4)
			{
				if (!stats->disk_low.exchange(true))
				{
					jit_log.warning("LLVM: SPU object cache stops writing for this boot: %u MB free, under the %u MB it leaves alone",
						free_stat.avail_free >> 20, min_free_after >> 20);
				}

				stats->skipped_low_space.fetch_add(1);
				return;
			}
		}
#endif

		if (!obj.getBufferSize())
		{
			jit_log.error("LLVM: Nothing to write: %s", name);
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
			failed();
#endif
			return;
		}

		ensure(m_compiler);

		fs::pending_file module_file;

		if (!module_file.open((name)))
		{
			jit_log.error("LLVM: Failed to create module file: %s (%s)", name, fs::g_tls_error);
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
			failed();
#endif
			return;
		}

		// Bold assumption about upper limit of space consumption
		const usz max_size = obj.getBufferSize() * 4;

		if (!m_compiler->add_sub_disk_space(0 - max_size))
		{
			jit_log.error("LLVM: Failed to create module file: %s (not enough disk space left)", name);
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
			failed();
#endif
			return;
		}

		if (!zip(obj.getBufferStart(), obj.getBufferSize(), module_file.file))
		{
			jit_log.error("LLVM: Failed to compress module: %s", std::string(_module->getName()));
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
			failed();
#endif
			return;
		}

		jit_log.trace("LLVM: Created module: %s", std::string(_module->getName()));

		// Restore space that was overestimated
		ensure(m_compiler->add_sub_disk_space(max_size - module_file.file.size()));
#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
		// A failed rename leaves no object under the final name, so it is logged and counted.
		if (!module_file.commit())
		{
			jit_log.error("LLVM: Failed to commit module file: %s (%s)", name, fs::g_tls_error);
			failed();
			return;
		}

		if (stats)
		{
			stats->written.fetch_add(1);
		}
#else
		module_file.commit();
#endif
	}

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
	static std::unique_ptr<llvm::MemoryBuffer> load(const std::string& path, std::string* reason = nullptr)
#else
	static std::unique_ptr<llvm::MemoryBuffer> load(const std::string& path)
#endif
	{
		if (fs::file cached{path + ".gz", fs::read})
		{
			const std::vector<u8> cached_data = cached.to_vector<u8>();

			if (cached_data.size() < 18) [[unlikely]] // gzip header + trailer
			{
				return nullptr;
			}

			std::unique_ptr<llvm::WritableMemoryBuffer> buf;

			// Get out size from trailer
			const u32 out_size = read_from_ptr<u32>(cached_data, cached_data.size() - 4);
			if (out_size != 0 && out_size <= cached_data.size() * 1032) // Check for impossible sizes
			{
				buf = llvm::WritableMemoryBuffer::getNewUninitMemBuffer(out_size);
			}

			if (!buf || !unzip(cached_data.data(), cached_data.size(), reinterpret_cast<u8*>(buf->getBufferStart()), out_size))
			{
				jit_log.error("LLVM: Failed to unzip module directly into the buffer: '%s'", path);

				// Try again with old unzip method
				const std::vector<u8> out = unzip(cached_data);
				if (out.empty())
				{
					jit_log.error("LLVM: Failed to unzip module: '%s'", path);
					return nullptr;
				}

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
				// PPU loads too: a damaged object recompiles. The direct path needs none, zlib checks the trailer itself.
				if (!gzip_trailer_matches(cached_data, out))
				{
					if (reason)
					{
						*reason = "gzip trailer does not match";
					}
					else
					{
						jit_log.error("LLVM: Damaged module (gzip trailer does not match): '%s'", path);
					}

					return nullptr;
				}
#endif

				buf = llvm::WritableMemoryBuffer::getNewUninitMemBuffer(out.size());
				std::memcpy(buf->getBufferStart(), out.data(), out.size());
			}

			return buf;
		}

		if (fs::file cached{path, fs::read})
		{
			if (cached.size() == 0) [[unlikely]]
			{
				return nullptr;
			}

			auto buf = llvm::WritableMemoryBuffer::getNewUninitMemBuffer(cached.size());
			cached.read(buf->getBufferStart(), buf->getBufferSize());
			return buf;
		}

		return nullptr;
	}

	std::unique_ptr<llvm::MemoryBuffer> getObject(const llvm::Module* _module) override
	{
		std::string path = m_path;
		path.append(_module->getName().data());

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
		if (jit_obj_cache_stats* const stats = m_compiler ? m_compiler->get_obj_cache_stats() : nullptr)
		{
			return get_checked(*_module, path, *stats);
		}
#endif

		if (auto buf = load(path))
		{
			jit_log.notice("LLVM: Loaded module: %s", _module->getName().data());
			return buf;
		}

		return nullptr;
	}

#if (defined(__APPLE__) || defined(__ANDROID__)) && defined(ARCH_ARM64)
	// A cached object is returned only after it passes the checks; a bad one is deleted and recompiled.
	std::unique_ptr<llvm::MemoryBuffer> get_checked(const llvm::Module& module, const std::string& path, jit_obj_cache_stats& stats)
	{
		std::string reason;
		auto buf = load(path, &reason);

		if (buf)
		{
			const auto start = std::chrono::steady_clock::now();
			const bool valid = object_defines_module(module, buf->getMemBufferRef(), reason);
			stats.validate_ns.fetch_add(ns_since(start));

			if (valid)
			{
				stats.hits.fetch_add(1);
				jit_log.notice("LLVM: Loaded module: %s", module.getName().data());
				return buf;
			}
		}

		if (buf || !reason.empty() || fs::is_file(path + ".gz") || fs::is_file(path))
		{
			fs::remove_file(path + ".gz");
			fs::remove_file(path);

			if (stats.rejected.fetch_add(1) == 0)
			{
				jit_log.error("LLVM: Rejected cached module %s: %s. It is recompiled; later rejections this boot are only counted.", path, reason.empty() ? "empty or unreadable" : reason);
			}
		}
		else
		{
			stats.misses.fetch_add(1);
		}

		// On the IR before codegen: CodeGenPrepare and ConstantHoisting can hide the constant later.
		ensure_walk_self_check();
		const auto start = std::chrono::steady_clock::now();
		const llvm::GlobalValue* const baked = module_bakes_host_address(module);
		stats.walk_ns.fetch_add(ns_since(start));

		if (baked)
		{
			m_persist = false;

			// Names only, never the address.
			if (stats.not_persisted.fetch_add(1) == 0)
			{
				jit_log.error("LLVM: Not caching module %s: %s bakes a host address. Later refusals this boot are only counted.", module.getName().str(), baked->getName().str());
			}
		}

		return nullptr;
	}
#endif
};

std::string jit_compiler::cpu(std::string_view _cpu)
{
	std::string m_cpu = std::string(_cpu);

	if (m_cpu.empty())
	{
		m_cpu = llvm::sys::getHostCPUName().str();

		if (m_cpu == "generic")
		{
			// Try to detect a best match based on other criteria
			m_cpu = fallback_cpu_detection();
		}

#ifdef ARCH_ARM64
		// Detection reads the MIDR of whichever core happens to be running, so on big.LITTLE it
		// can name a small in-order core. JIT'd code runs on every core, so scheduling for the
		// smallest one is the wrong default -- fall back to the same wide out-of-order baseline
		// used when detection fails outright. Only the schedule/cost model is affected: the
		// instruction set still comes from setMAttrs (HWCAP-gated), so this can never emit an
		// illegal instruction. Ported from an earlier Android fork (cc3a18e29), widened to the
		// A5xx little cores that modern SoCs actually ship.
		if (m_cpu == "cortex-a34" || m_cpu == "cortex-a35" || m_cpu == "cortex-a53" ||
			m_cpu == "cortex-a55" || m_cpu == "cortex-a510" || m_cpu == "cortex-a520")
		{
			jit_log.notice("CPU detection named a little core ('%s'); using cortex-a78 as the schedule baseline.", m_cpu);
			m_cpu = "cortex-a78";
		}
#endif

		if (m_cpu == "sandybridge" ||
			m_cpu == "ivybridge" ||
			m_cpu == "haswell" ||
			m_cpu == "broadwell" ||
			m_cpu == "skylake" ||
			m_cpu == "skylake-avx512" ||
			m_cpu == "cascadelake" ||
			m_cpu == "cooperlake" ||
			m_cpu == "cannonlake" ||
			m_cpu == "icelake" ||
			m_cpu == "icelake-client" ||
			m_cpu == "icelake-server" ||
			m_cpu == "tigerlake" ||
			m_cpu == "rocketlake" ||
			m_cpu == "alderlake" ||
			m_cpu == "raptorlake" ||
			m_cpu == "meteorlake")
		{
			// Downgrade if AVX is not supported by some chips
			if (!utils::has_avx())
			{
				m_cpu = "nehalem";
			}
		}

		if (m_cpu == "skylake-avx512" ||
			m_cpu == "cascadelake" ||
			m_cpu == "cooperlake" ||
			m_cpu == "cannonlake" ||
			m_cpu == "icelake" ||
			m_cpu == "icelake-client" ||
			m_cpu == "icelake-server" ||
			m_cpu == "tigerlake" ||
			m_cpu == "rocketlake")
		{
			// Downgrade if AVX-512 is disabled or not supported
			if (!utils::has_avx512())
			{
				m_cpu = "skylake";
			}
		}

		if (m_cpu == "znver1" && utils::has_clwb())
		{
			// Upgrade
			m_cpu = "znver2";
		}

		if ((m_cpu == "znver3" || m_cpu == "goldmont" || m_cpu == "alderlake" || m_cpu == "raptorlake" || m_cpu == "meteorlake") && utils::has_avx512_icl())
		{
			// Upgrade
			m_cpu = "icelake-client";
		}

		if (m_cpu == "goldmont" && utils::has_avx2())
		{
			// Upgrade
			m_cpu = "alderlake";
		}
	}

	return m_cpu;
}

std::string jit_compiler::triple1()
{
#if defined(_WIN32)
	return llvm::Triple::normalize(llvm::sys::getProcessTriple());
#elif defined(__APPLE__) && defined(ARCH_X64)
	return llvm::Triple::normalize("x86_64-unknown-linux-gnu");
#elif (defined(__ANDROID__) || defined(__APPLE__)) && defined(ARCH_ARM64)
	return llvm::Triple::normalize("aarch64-unknown-linux-android"); // Set environment to android to reserve x18
#elif defined(__ANDROID__) && defined(ARCH_X64)
	return llvm::Triple::normalize("x86_64-unknown-linux-android");
#else
	return llvm::Triple::normalize(llvm::sys::getProcessTriple());
#endif
}

std::string jit_compiler::triple2()
{
#if defined(_WIN32) && defined(ARCH_X64)
	return llvm::Triple::normalize("x86_64-unknown-linux-gnu");
#elif defined(_WIN32) && defined(ARCH_ARM64)
	return llvm::Triple::normalize("aarch64-unknown-linux-gnu");
#elif defined(__APPLE__) && defined(ARCH_X64)
	return llvm::Triple::normalize("x86_64-unknown-linux-gnu");
#elif (defined(__ANDROID__) || defined(__APPLE__)) && defined(ARCH_ARM64)
	return llvm::Triple::normalize("aarch64-unknown-linux-android"); // Set environment to android to reserve x18
#elif defined(__ANDROID__) && defined(ARCH_X64)
	return llvm::Triple::normalize("x86_64-unknown-linux-android"); // Set environment to android to reserve x18
#else
	return llvm::Triple::normalize(llvm::sys::getProcessTriple());
#endif
}

bool jit_compiler::add_sub_disk_space(ssz space)
{
	if (space >= 0)
	{
		ensure(m_disk_space.fetch_add(space) < ~static_cast<usz>(space));
		return true;
	}

	return m_disk_space.fetch_op([sub_size = static_cast<usz>(0 - space)](usz& val)
	{
		if (val >= sub_size)
		{
			val -= sub_size;
			return true;
		}

		return false;
	}).second;
}

jit_compiler::jit_compiler(const std::unordered_map<std::string, u64>& _link, std::string_view _cpu, u32 flags, std::function<u64(const std::string&)> symbols_cement) noexcept
	: m_context(new llvm::LLVMContext)
	, m_cpu(cpu(_cpu))
{
	[[maybe_unused]] static const bool s_install_llvm_error_handler = []()
	{
		llvm::remove_fatal_error_handler();
		llvm::install_fatal_error_handler([](void*, const char* msg, bool)
		{
			const std::string_view out = msg ? msg : "";

			if (g_llvm_fatal_message)
			{
				*g_llvm_fatal_message = out;
				thread_ctrl::silent_exit();
			}

			fmt::throw_exception("LLVM Emergency Exit Invoked: '%s'", out);
		}, nullptr);

		// A separate handler from the fatal one -- LLVM installs and dispatches the two
		// independently. Without this, an allocation failure inside LLVM (SmallVector growth
		// while codegenning the enormous PPU symbol-resolver module, say) writes "LLVM ERROR:
		// out of memory" to fd 2 -- which goes nowhere in an Android app -- and calls abort():
		// a signal-6 death with nothing whatsoever in the log. Route it through the same
		// recoverable path as the fatal handler, so a guarded compile survives and anything
		// else at least says why it died. Allocating inside a bad-alloc handler is
		// best-effort, but the failures here are huge single allocations, so a short log
		// string still succeeds. Ported from an earlier Android fork (39a6a4c36).
		llvm::remove_bad_alloc_error_handler();
		llvm::install_bad_alloc_error_handler([](void*, const char* msg, bool)
		{
			const std::string_view out = msg ? msg : "";

			if (g_llvm_fatal_message)
			{
				// Worded so the PPU compiler recognises it as out of memory (it looks for
				// "Out of memory"); LLVM's own text is just "Allocation failed".
				*g_llvm_fatal_message = fmt::format("Out of memory in LLVM: %s", out);
				thread_ctrl::silent_exit();
			}

			fmt::throw_exception("LLVM Out Of Memory: '%s'", out);
		}, nullptr);

		// operator new failing, which the LLVM handlers above never see: IR objects come straight
		// from the C++ allocator. This core is built without exceptions, so the std::bad_alloc
		// operator new throws can never be caught and always aborted the app. That is how Metal
		// Gear Solid V: The Phantom Pain (BLUS31491) died 18 minutes into its first PPU compile on
		// an Odin 3, in operator new under PPUTranslator::SetGpr.
		//
		// Inside a recoverable job (run_recoverable_llvm, which the PPU compiler now uses for
		// translation as well as codegen) end that disposable thread instead and report it as out
		// of memory; the caller waits and retries. Our libc++ is linked statically, so this handler
		// covers this library's allocations and nothing else in the app.
		std::set_new_handler([]
		{
			if (g_llvm_out_of_memory && !*g_llvm_out_of_memory)
			{
				// Nothing before the exit may allocate.
				*g_llvm_out_of_memory = true;
				thread_ctrl::silent_exit();
			}

			// Anywhere else, or if the exit itself ran out of memory and came back here: do what
			// operator new does with no handler. Thrown rather than uninstalling the handler, so a
			// nothrow allocation that fails elsewhere (it catches this) does not switch recovery
			// off for the rest of the session.
			std::__throw_bad_alloc();
		});

		return true;
	}();

#ifdef ARCH_ARM64
	// Turn off InterleavedLoadCombine, an IR pass the AArch64 backend alone adds to codegen (x86
	// never runs it). It looks for loads split into pieces and reassembled with shuffles, to merge
	// them into one ld2/ld3/ld4, and it finds them by recursing through every shufflevector,
	// bitcast and load behind a candidate, with no bound on the depth. PS3 vector code translates
	// into long shuffle chains (byte swaps, permutes, splats), and on one function that search does
	// not end: The Guided Fate Paradox's EBOOT (BLUS31312, NPUB31320; issue #149) sat in it for
	// over ten minutes at 100% of a core, the boot never finished and Stop could not join the
	// thread. Profiled on the Odin 3: VectorInfo::compute / computeFromSVI, allocating and freeing
	// std::set and std::list nodes, under MCJIT::emitObject. Windows compiles the same EBOOT in
	// seconds because it never has this pass.
	//
	// What it would buy is strided loads, which the translators do not emit; the later
	// InterleavedAccess pass still handles the ordinary interleaved patterns. LLVM options are
	// process-wide, so this is set once, before the first compile, and covers SPU codegen too.
	[[maybe_unused]] static const bool s_llvm_options = []()
	{
		auto& options = llvm::cl::getRegisteredOptions();

		if (const auto found = options.find("disable-interleaved-load-combine"); found != options.end())
		{
			static_cast<llvm::cl::opt<bool>*>(found->second)->setValue(true);
			jit_log.notice("LLVM: InterleavedLoadCombine disabled");
			return true;
		}

		jit_log.error("LLVM: -disable-interleaved-load-combine not found; InterleavedLoadCombine stays on");
		return false;
	}();
#endif

	std::string result;

	auto null_mod = std::make_unique<llvm::Module> ("null_", *m_context);
	null_mod->setTargetTriple(llvm::Triple(jit_compiler::triple1()));

	std::unique_ptr<llvm::RTDyldMemoryManager> mem;

	if (_link.empty())
	{
		// Auxiliary JIT (does not use custom memory manager, only writes the objects)
		if (flags & 0x1)
		{
			mem = std::make_unique<MemoryManager1>(std::move(symbols_cement), true);
		}
		else
		{
			mem = std::make_unique<MemoryManager2>(std::move(symbols_cement));
			null_mod->setTargetTriple(llvm::Triple(jit_compiler::triple2()));
		}
	}
	else
	{
		mem = std::make_unique<MemoryManager1>(std::move(symbols_cement));
	}

	std::vector<std::string> attributes;

#if defined(ARCH_ARM64)
	// LLVM groups FEAT_SHA3 and FEAT_SHA512 under the same target feature
	if (utils::has_sha512() && utils::has_sha3())
		attributes.push_back("+sha3");
	else
		attributes.push_back("-sha3");

	if (utils::has_dotprod())
		attributes.push_back("+dotprod");
	else
		attributes.push_back("-dotprod");

	// The recompilers emit i8mm intrinsics (e.g. ummla) gated on utils::has_i8mm().
	// The JIT target features must advertise i8mm too, otherwise the backend fails
	// with "Cannot select: intrinsic %llvm.aarch64.neon.ummla" whenever the resolved
	// -mcpu does not already imply it (e.g. the cortex-a78 fallback on Apple silicon).
	if (utils::has_i8mm())
		attributes.push_back("+i8mm");
	else
		attributes.push_back("-i8mm");

	if (utils::has_sve())
		attributes.push_back("+sve");
	else
		attributes.push_back("-sve");

	if (utils::has_sve2())
		attributes.push_back("+sve2");
	else
		attributes.push_back("-sve2");
#endif

	{
		m_engine.reset(llvm::EngineBuilder(std::move(null_mod))
			.setErrorStr(&result)
			.setEngineKind(llvm::EngineKind::JIT)
			.setMCJITMemoryManager(std::move(mem))
			.setOptLevel(llvm::CodeGenOptLevel::Aggressive)
#if defined(__APPLE__) && defined(ARCH_ARM64)
			// JIT code and its data can sit more than 4 GiB apart, past ADRP's range, and RuntimeDyld
			// truncates the page delta silently with assertions off. Large + Static, the ELF triple and
			// mark_module_dso_local keep every reference absolute; an Apple triple would bring back GOT ADRPs.
			.setCodeModel(llvm::CodeModel::Large)
			.setRelocationModel(llvm::Reloc::Model::Static)
#else
			.setCodeModel(flags & 0x2 ? llvm::CodeModel::Large : llvm::CodeModel::Small)
#ifdef __APPLE__
			//.setCodeModel(llvm::CodeModel::Large)
#endif
			.setRelocationModel(llvm::Reloc::Model::PIC_)
#endif
			.setMAttrs(attributes)
			.setMCPU(m_cpu)
			.create());
	}

	if (!_link.empty())
	{
		for (auto&& [name, addr] : _link)
		{
			m_engine->updateGlobalMapping(name, addr);
		}
	}

	if (!_link.empty() || !(flags & 0x1))
	{
		m_engine->RegisterJITEventListener(llvm::JITEventListener::createIntelJITEventListener());
		m_engine->RegisterJITEventListener(new JITAnnouncer);
	}

	if (!m_engine)
	{
		fmt::throw_exception("LLVM: Failed to create ExecutionEngine: %s", result);
	}

#ifdef __APPLE__
	// MCJIT registers LLVM's GDB JIT listener, which keeps a copy of every loaded object; nothing here uses it.
	m_engine->UnregisterJITEventListener(llvm::JITEventListener::createGDBRegistrationListener());
#endif

	fs::device_stat stats{};

	if (fs::statfs(fs::get_cache_dir(), stats))
	{
		m_disk_space = stats.avail_free / 4;
	}
}

jit_compiler& jit_compiler::operator=(thread_state s) noexcept
{
	if (s == thread_state::destroying_context)
	{
		// Release resources explicitly
		m_engine.reset();
		m_context.reset();
	}

	return *this;
}

jit_compiler::~jit_compiler() noexcept
{
	if (m_poisoned)
	{
		jit_log.error("Abandoning poisoned LLVM execution engine (leaked to avoid a deadlock in ~MCJIT)");
		static_cast<void>(m_engine.release());
		static_cast<void>(m_context.release());
	}
}

#if defined(__APPLE__) && defined(ARCH_ARM64)
// Symbols that are not dso_local are reached through a GOT ADRP even under Large + Static.
static void mark_module_dso_local(llvm::Module& module)
{
	for (llvm::Function& func : module.functions())
		func.setDSOLocal(true);
	for (llvm::GlobalVariable& var : module.globals())
		var.setDSOLocal(true);
}
#endif

void jit_compiler::add(std::unique_ptr<llvm::Module> _module, const std::string& path)
{
#if defined(__APPLE__) && defined(ARCH_ARM64)
	mark_module_dso_local(*_module);
#endif
	ObjectCache cache{path, this};

	m_poisoned = true;
	m_engine->setObjectCache(&cache);

	const auto ptr = _module.get();
	m_engine->addModule(std::move(_module));
	m_engine->generateCodeForModule(ptr);
	m_engine->setObjectCache(nullptr);
	m_poisoned = false;

	for (auto& func : ptr->functions())
	{
		// Delete IR to lower memory consumption
		func.deleteBody();
	}
}

bool jit_compiler::try_add(std::unique_ptr<llvm::Module> _module, const std::string& path, std::string& error)
{
#if defined(__APPLE__) && defined(ARCH_ARM64)
	mark_module_dso_local(*_module);
#endif
	ObjectCache cache{path, this};
	m_engine->setObjectCache(&cache);

	const auto ptr = _module.get();
	m_engine->addModule(std::move(_module));

	if (!run_recoverable_llvm([&]()
	{
		m_engine->generateCodeForModule(ptr);
	}, error))
	{
		m_poisoned = true;
		return false;
	}

	m_engine->setObjectCache(nullptr);

	for (auto& func : ptr->functions())
	{
		// Delete IR to lower memory consumption
		func.deleteBody();
	}

	return true;
}

void jit_compiler::add(std::unique_ptr<llvm::Module> _module)
{
#if defined(__APPLE__) && defined(ARCH_ARM64)
	mark_module_dso_local(*_module);
#endif
	const auto ptr = _module.get();

	m_poisoned = true;
	m_engine->addModule(std::move(_module));
	m_engine->generateCodeForModule(ptr);
	m_poisoned = false;

	for (auto& func : ptr->functions())
	{
		// Delete IR to lower memory consumption
		func.deleteBody();
	}
}

bool jit_compiler::try_add(std::unique_ptr<llvm::Module> _module, std::string& error)
{
#if defined(__APPLE__) && defined(ARCH_ARM64)
	mark_module_dso_local(*_module);
#endif
	const auto ptr = _module.get();
	m_engine->addModule(std::move(_module));

	if (!run_recoverable_llvm([&]()
	{
		m_engine->generateCodeForModule(ptr);
	}, error))
	{
		m_poisoned = true;
		return false;
	}

	for (auto& func : ptr->functions())
	{
		// Delete IR to lower memory consumption
		func.deleteBody();
	}

	return true;
}

bool jit_compiler::add(const std::string& path)
{
	auto cache = ObjectCache::load(path);

	if (!cache)
	{
		jit_log.error("ObjectCache: Failed to read file. (path='%s', error=%s)", path, fs::g_tls_error);
		return false;
	}

	return add(std::move(cache), path);
}

bool jit_compiler::add(std::unique_ptr<llvm::MemoryBuffer> object, const std::string& path)
{
	if (!object)
	{
		jit_log.error("ObjectCache: Failed to add nullptr object for file. (path='%s')", path);
		return false;
	}

#if defined(__APPLE__) && defined(ARCH_ARM64)
	// Here rather than in add(path), so load_object's objects get it too.
	const mapped_object* mapped = nullptr;

	if (auto copy = mapped_object::copy_of(*object))
	{
		mapped = copy.get();
		object = std::move(copy);
	}
#endif

	if (auto object_file = llvm::object::ObjectFile::createObjectFile(*object))
	{
		m_engine->addObjectFile(llvm::object::OwningBinary<llvm::object::ObjectFile>(std::move(*object_file), std::move(object)));
#if defined(__APPLE__) && defined(ARCH_ARM64)
		if (mapped)
		{
			mapped->release();
		}
#endif
		jit_log.trace("ObjectCache: Successfully added %s", path);
		return true;
	}
	else
	{
		jit_log.error("ObjectCache: Adding failed: %s (%s)", path, llvm::toString(object_file.takeError()));
		return false;
	}
}

std::unique_ptr<llvm::MemoryBuffer> jit_compiler::load_object(const std::string& path)
{
	if (auto cache = ObjectCache::load(path))
	{
		if (auto object_file = llvm::object::ObjectFile::createObjectFile(*cache))
		{
			return cache;
		}
		else
		{
			llvm::consumeError(object_file.takeError());
		}

		// Remove cached and uncached file
		const bool removed_gz = fs::remove_file(path + ".gz");
		const bool removed = fs::remove_file(path);

		if (removed_gz || removed)
		{
			jit_log.error("ObjectCache: Removed damaged file: %s", path);
		}
	}

	return nullptr;
}

void jit_compiler::update_global_mapping(const std::string& name, u64 addr)
{
	m_engine->updateGlobalMapping(name, addr);
}

void jit_compiler::fin()
{
	m_poisoned = true;
	m_engine->finalizeObject();
	m_poisoned = false;
}

bool jit_compiler::try_fin(std::string& error)
{
	if (!run_recoverable_llvm([&]()
	{
		m_engine->finalizeObject();
	}, error))
	{
		m_poisoned = true;
		return false;
	}

	return true;
}

u64 jit_compiler::get(const std::string& name)
{
	return m_engine->getGlobalValueAddress(name);
}

const char * fallback_cpu_detection()
{
#if defined(ARCH_X64)
	// If we got here we either have a very old and outdated CPU or a new CPU that has not been seen by LLVM yet.
	const std::string brand = utils::get_cpu_brand();
	const auto family = utils::get_cpu_family();
	const auto model = utils::get_cpu_model();

	jit_log.error("CPU wasn't identified by LLVM, brand = %s, family = 0x%x, model = 0x%x", brand, family, model);

	if (brand.starts_with("AMD"))
	{
		switch (family)
		{
		case 0x10:
		case 0x12: // Unimplemented in LLVM
			return "amdfam10";
		case 0x15:
			// Bulldozer class, includes piledriver, excavator, steamroller, etc
			return utils::has_avx2() ? "bdver4" : "bdver1";
		case 0x17:
		case 0x18:
			// No major differences between znver1 and znver2, return the lesser
			return "znver1";
		case 0x19:
			// Models 0-Fh are zen3 as are 20h-60h. The rest we can assume are zen4
			return ((model >= 0x20 && model <= 0x60) || model < 0x10) ? "znver3" : "znver4";
		case 0x1a:
			// Only one generation in family 1a so far, zen5, which we do not support yet.
			// Return zen4 as a workaround until the next LLVM upgrade.
			return "znver4";
		default:
			// Safest guesses
			return utils::has_avx512() ? "znver4" :
			       utils::has_avx2()   ? "znver1" :
			       utils::has_avx()    ? "bdver1" :
			                             "nehalem";
		}
	}
	else if (brand.find("Intel") != std::string::npos)
	{
		if (!utils::has_avx())
		{
			return "nehalem";
		}
		if (!utils::has_avx2())
		{
			return "ivybridge";
		}
		if (!utils::has_avx512())
		{
			return "skylake";
		}
		if (utils::has_avx512_icl())
		{
			return "cannonlake";
		}
		return "icelake-client";
	}
	else if (brand.starts_with("VirtualApple"))
	{
		// No AVX. This will change in MacOS 15+, at which point we may revise this.
		return utils::has_avx() ? "haswell" : "nehalem";
	}

#elif defined(ARCH_ARM64)
#ifdef ANDROID
	static std::string s_result = []() -> std::string
	{
		// get_cpu_name() already returns a canonical LLVM processor name
		std::string result = aarch64::get_cpu_name();
		if (result.empty())
		{
			return "cortex-a78";
		}

		return result;
	}();

	return s_result.c_str();
#else
	// TODO: Read the data from /proc/cpuinfo. ARM CPU registers are not accessible from usermode.
	// This will be a pain when supporting snapdragon on windows but we'll cross that bridge when we get there.
	// Require at least armv8-2a. Older chips are going to be useless anyway.
	return "cortex-a78";
#endif
#endif

	// Failed to guess, use generic fallback
	return "generic";
}

#endif // LLVM_AVAILABLE
