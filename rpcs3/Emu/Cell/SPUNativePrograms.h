#pragma once

// ARMSX3: hand-written native versions of a few SPU programs (SPUNativeCode.h) run in place of their
// translated code. The recompiler asks is_hooked() about each program it compiles, by its exact name
// ("__spu-a-0x<entry>-<base57 SHA-1 of the program's words>"). A hooked program keeps its entry check
// and its translated code, calls entry() once the check passes, and is cached under a name of its own,
// so the persistent object cache never hands back an unhooked object for it, nor a hooked one for any
// other program. Which programs are hooked is decided in SPUNativePrograms.cpp and never changes the
// code emitted for the others. Keep this header stable: the recompiler includes it, so it is part of the
// SPU code identity.

#include <cstdint>

class spu_thread;

namespace spu_native
{
	// Whether the recompiler should hook the program with this name.
	bool is_hooked(const char* name);

	// After the entry check. Returns 0 to run the translated code, or 0x80000000 | the address to
	// continue at when a native version ran instead. key: the first 8 bytes of the program's SHA-1.
	std::uint32_t entry(spu_thread* spu, std::uint8_t* ls, std::uint32_t entry_pc, std::uint64_t key);
}
