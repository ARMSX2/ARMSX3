#include "stdafx.h"
#include "stack_trace.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#define DBGHELP_TRANSLATE_TCHAR
#include <DbgHelp.h>
#include <codecvt>
#elif defined(ANDROID)
// bionic has no backtrace()/backtrace_symbols(), which is why both were compiled out here and
// every native crash on this port had to be read out of a tombstone or symbolized by hand.
// _Unwind_Backtrace is always present, and dladdr gives the library-relative offset that
// llvm-symbolizer wants.
#include <unwind.h>
#include <dlfcn.h>
#else
#include <execinfo.h>
#endif

namespace utils
{
#ifdef _WIN32
	std::string wstr_to_utf8(LPWSTR data, int str_len)
	{
		if (!str_len)
		{
			return {};
		}

		// Calculate size
		const auto length = WideCharToMultiByte(CP_UTF8, 0, data, str_len, NULL, 0, NULL, NULL);

		// Convert
		std::vector<char> out(length + 1, 0);
		WideCharToMultiByte(CP_UTF8, 0, data, str_len, out.data(), length, NULL, NULL);
		return out.data();
	}

	std::vector<void*> get_backtrace(int max_depth, PCONTEXT ctx)
	{
		static struct sym_initer_t
		{
			sym_initer_t() noexcept
			{
				SymInitialize(GetCurrentProcess(), NULL, TRUE);
			}
			~sym_initer_t() noexcept
			{
				SymCleanup(GetCurrentProcess());
			}
		} s_initer{};

		std::vector<void*> result = {};

		const auto hProcess = ::GetCurrentProcess();
		const auto hThread = ::GetCurrentThread();

		CONTEXT context{};
		if (ctx)
			context = *ctx;
		else
			RtlCaptureContext(&context);

		STACKFRAME64 stack = {};
		stack.AddrPC.Mode = AddrModeFlat;
		stack.AddrStack.Mode = AddrModeFlat;
		stack.AddrFrame.Mode = AddrModeFlat;
#if defined(ARCH_X64)
		const DWORD machineType = IMAGE_FILE_MACHINE_AMD64;
		stack.AddrPC.Offset = context.Rip;
		stack.AddrStack.Offset = context.Rsp;
		stack.AddrFrame.Offset = context.Rbp;
#elif defined(ARCH_ARM64)
		const DWORD machineType = IMAGE_FILE_MACHINE_ARM64;
		stack.AddrPC.Offset = context.Pc;
		stack.AddrStack.Offset = context.Sp;
		stack.AddrFrame.Offset = context.Fp;
#else
#error "Unsupported architecture"
#endif

		while (max_depth--)
		{
			if (!StackWalk64(
					machineType,
					hProcess,
					hThread,
					&stack,
					&context,
					NULL,
					SymFunctionTableAccess64,
					SymGetModuleBase64,
					NULL))
			{
				break;
			}

			result.push_back(reinterpret_cast<void*>(stack.AddrPC.Offset));
		}

		return result;
	}

	std::vector<std::string> get_backtrace_symbols(const std::vector<void*>& stack)
	{
		std::vector<std::string> result = {};
		std::vector<u8> symbol_buf(sizeof(SYMBOL_INFOW) + sizeof(TCHAR) * 256);

		const auto hProcess = ::GetCurrentProcess();

		auto sym = reinterpret_cast<SYMBOL_INFOW*>(symbol_buf.data());
		sym->SizeOfStruct = sizeof(SYMBOL_INFOW);
		sym->MaxNameLen = 256;

		IMAGEHLP_LINEW64 line_info{};
		line_info.SizeOfStruct = sizeof(IMAGEHLP_LINEW64);

		SymInitialize(hProcess, NULL, TRUE);
		SymSetOptions(SYMOPT_LOAD_LINES);

		for (const auto& pointer : stack)
		{
			DWORD64 unused;
			SymFromAddrW(hProcess, reinterpret_cast<DWORD64>(pointer), &unused, sym);

			if (sym->NameLen)
			{
				std::string function_name = wstr_to_utf8(sym->Name, static_cast<int>(sym->NameLen));

				// Attempt to get file and line information if available
				DWORD unused2;
				if (SymGetLineFromAddrW64(hProcess, reinterpret_cast<DWORD64>(pointer), &unused2, &line_info))
				{
					std::string full_path = fmt::format("%s:%u %s", wstr_to_utf8(line_info.FileName, -1), line_info.LineNumber, function_name);
					result.push_back(std::move(full_path));
				}
				else
				{
					result.push_back(std::move(function_name));
				}
			}
			else
			{
				result.push_back(fmt::format("rpcs3@0x%p", pointer));
			}
		}

		return result;
	}
#elif defined(ANDROID)
	namespace
	{
		struct unwind_state
		{
			void** current;
			void** end;
		};

		_Unwind_Reason_Code unwind_collect(_Unwind_Context* ctx, void* arg)
		{
			auto* state = static_cast<unwind_state*>(arg);

			// A frame with no PC is the end of what the unwinder can see; keep the frames
			// gathered so far rather than discarding a partial stack, which is still the
			// answer most of the time.
			const auto pc = _Unwind_GetIP(ctx);

			if (!pc)
			{
				return _URC_END_OF_STACK;
			}

			if (state->current == state->end)
			{
				return _URC_END_OF_STACK;
			}

			*state->current++ = reinterpret_cast<void*>(pc);
			return _URC_NO_REASON;
		}
	}

	std::vector<void*> get_backtrace(int max_depth)
	{
		std::vector<void*> result(max_depth);
		unwind_state state{ result.data(), result.data() + max_depth };
		_Unwind_Backtrace(&unwind_collect, &state);
		result.resize(state.current - result.data());
		return result;
	}

	std::vector<std::string> get_backtrace_symbols(const std::vector<void*>& stack)
	{
		std::vector<std::string> result;
		result.reserve(stack.size());

		for (void* const pointer : stack)
		{
			Dl_info info{};

			if (!dladdr(pointer, &info) || !info.dli_fname)
			{
				result.push_back(fmt::format("0x%p", pointer));
				continue;
			}

			// Library-relative, because that is what symbolizes. The shipped .so is stripped
			// and loaded at a random base, so an absolute PC is useless on its own; this
			// offset is what llvm-symbolizer takes against the unstripped build output.
			const auto base = reinterpret_cast<uptr>(info.dli_fbase);
			const auto off = reinterpret_cast<uptr>(pointer) - base;

			// Basename only: the full path is the app's private data dir and the same for
			// every frame.
			std::string_view lib = info.dli_fname;

			if (const auto slash = lib.find_last_of('/'); slash != umax)
			{
				lib.remove_prefix(slash + 1);
			}

			if (info.dli_sname)
			{
				result.push_back(fmt::format("%s+0x%x (%s)", lib, off, info.dli_sname));
			}
			else
			{
				result.push_back(fmt::format("%s+0x%x", lib, off));
			}
		}

		return result;
	}
#else
	std::vector<void*> get_backtrace(int max_depth)
	{
		std::vector<void*> result(max_depth);
		int depth = backtrace(result.data(), max_depth);
		result.resize(depth);
		return result;
	}

	std::vector<std::string> get_backtrace_symbols(const std::vector<void*>& stack)
	{
		std::vector<std::string> result;
		result.reserve(stack.size());

		const auto symbols = backtrace_symbols(stack.data(), static_cast<int>(stack.size()));
		for (usz i = 0; i < stack.size(); ++i)
		{
			result.push_back(symbols[i]);
		}

		free(symbols);
		return result;
	}
#endif
}
