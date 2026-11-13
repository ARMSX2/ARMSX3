#pragma once

// ARMSX3: hand-written native versions of a few heavy games' hottest SPU programs, by recompiler name
// ("__spu-a-0x<entry>-<base57 SHA-1 of the program's words>"). Each is our own implementation of what
// the program computes, proven against captured game state and on the device (SPUNativePrograms.cpp)
// before it runs in place of the translated code.
//
// Kept free of emulator headers so the developer's test bench builds the same file.

#include <cstdint>

namespace spu_native_code
{
	// gpr: the thread's 128 registers as stored in spu_thread::gpr (each quadword byte-reversed).
	// ls: the local store. Returns the address the program branches to when it ends, or c_declined
	// when the state is outside what the native version covers, before changing anything: the
	// translated code then runs instead. A native version matches the translated code in the local
	// store, the return address and the registers a caller can rely on (r0, r1, r80 to r127); the
	// scratch registers r2 to r79 are left as they were (the translated code leaves some stale too),
	// except any that later code reads before setting, which a version sets exactly.
	using program_fn = std::uint32_t (*)(std::uint8_t* gpr, std::uint8_t* ls);

	constexpr std::uint32_t c_declined = 0xffffffffu;

	// The native version of the program with this recompiler name, or nullptr.
	program_fn find(const char* name);
}
