// ARMSX3: which SPU programs run a native version (SPUNativeCode.h); see SPUNativePrograms.h.
//
// Each native version matches the recompiled code (Approximate xfloat) bit for bit: it was checked
// against captured game state, against the recompiled code on the device call by call, and by fuzzing
// against a reference interpreter. It declines whenever the state is outside what it covers, and the
// translated code runs instead. ARMSX3_SPU_NATIVE=0 (driver_env.txt) turns them all off.
#include "stdafx.h"
#include "SPUNativePrograms.h"
#include "SPUNativeCode.h"
#include "SPUThread.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>

namespace spu_native
{
	namespace
	{
		struct program_t
		{
			const char* name;
			const char* what;
			u32 entry = 0;
			u64 key = 0;
			spu_native_code::program_fn fn = nullptr;
			atomic_t<bool> announced{false};
		};

		program_t s_programs[] =
		{
			// The Last of Us (its releases share these jobs)
			{"__spu-a-0x045d0-N8Lay9SfvF5ULS9svvzgLdFhT4N6", "a depth-aware shading filter"},
			{"__spu-a-0x054c8-eUh72AA0iP6TneVa58UPuioFeTsn", "a bit-field decoder"},
			{"__spu-a-0x040c0-9yspV9NGJJpXzWuaJxKGNw1Ukh0b", "per-vertex lighting"},
			{"__spu-a-0x05c40-9b4LqnRNfXg9bxExaNKLzZfGYSmU", "a triangle index decoder"},
		};

		const bool s_off = []
		{
			const char* v = std::getenv("ARMSX3_SPU_NATIVE");
			return v && v[0] == '0';
		}();

		// "__spu-a-0x<entry>-<hash>": the entry address, and the first 8 bytes of the SHA-1 from the first
		// 11 base57 digits (the recompiler's m_hash_start)
		bool parse_name(const char* name, u32& entry, u64& key)
		{
			const std::string_view s(name);
			static constexpr std::string_view prefix = "__spu-a-0x";

			if (!s.starts_with(prefix) || s.size() < prefix.size() + 5 + 1 + 11)
			{
				return false;
			}

			entry = static_cast<u32>(std::strtoul(std::string(s.substr(prefix.size(), 5)).c_str(), nullptr, 16));
			static constexpr std::string_view digits = "0123456789ACEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
			u64 value = 0;

			for (char c : s.substr(prefix.size() + 6, 11))
			{
				const usz d = digits.find(c);

				if (d == umax)
				{
					return false;
				}

				value = value * 57 + d;
			}

			key = value;
			return true;
		}

		const bool s_init = []
		{
			for (auto& p : s_programs)
			{
				if (parse_name(p.name, p.entry, p.key))
				{
					p.fn = spu_native_code::find(p.name);
				}
			}

			return true;
		}();
	}

	bool is_hooked(const char* name)
	{
		if (s_off)
		{
			return false;
		}

		for (const auto& p : s_programs)
		{
			if (p.fn && std::strcmp(p.name, name) == 0)
			{
				return true;
			}
		}

		return false;
	}

	u32 entry(spu_thread* spu, u8* ls, u32 entry_pc, u64 key)
	{
		for (auto& p : s_programs)
		{
			if (p.entry != entry_pc || p.key != key || !p.fn)
			{
				continue;
			}

			const u32 r = p.fn(reinterpret_cast<u8*>(spu->gpr.data()), ls);

			if (r == spu_native_code::c_declined)
			{
				return 0;
			}

			if (!p.announced.exchange(true))
			{
				spu_log.notice("SPU: %s (%s) runs its native version", p.name, p.what);
			}

			return 0x80000000u | (r & 0x3fffc);
		}

		return 0;
	}
}
