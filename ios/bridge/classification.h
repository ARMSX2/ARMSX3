// SPDX-License-Identifier: GPL-2.0-only
// The REAL, OWNED and DELIBERATE field markers: code tags, not comments, so the comment-stripping guards can check them.

#pragma once

// For the field-existence check: a stale field name in any marker is a compile error.
#include "Emu/emu_callbacks.h"

#include <atomic>
#include <utility>

namespace armsx3::bridge
{
	// Defined in callbacks.cpp, which owns the BRIDGE channel; `phase` is negative for DELIBERATE.
	void report_stub_reached(const char* classification, int phase, const char* field);

	// A class template so the static flag is per field: std::function copies its callable, so a member flag would report twice.
	template <int Phase, typename Fn>
	class classified_callback
	{
	public:
		classified_callback(const char* classification, const char* field, Fn fn)
			: m_classification(classification)
			, m_field(field)
			, m_fn(std::move(fn))
		{
		}

		template <typename... Args>
		decltype(auto) operator()(Args&&... args) const
		{
			static std::atomic<bool> s_reported{false};

			bool expected = false;
			if (s_reported.compare_exchange_strong(expected, true))
			{
				report_stub_reached(m_classification, Phase, m_field);
			}

			return m_fn(std::forward<Args>(args)...);
		}

	private:
		const char* m_classification;
		const char* m_field;
		Fn m_fn;
	};

	template <int Phase, typename Fn>
	classified_callback<Phase, Fn> owned(const char* field, Fn fn)
	{
		return classified_callback<Phase, Fn>("OWNED", field, std::move(fn));
	}

	template <typename Fn>
	classified_callback<-1, Fn> deliberate(const char* field, Fn fn)
	{
		return classified_callback<-1, Fn>("DELIBERATE", field, std::move(fn));
	}
}

// Variadic because a lambda's parameter list has commas; the sizeof is unevaluated and costs nothing.

// Forwards the callable unchanged: no wrapper, no flag, no branch.
#define ARMSX3_REAL(field, ...) \
	(static_cast<void>(sizeof(&emu_callbacks::field)), (__VA_ARGS__))

// Logs its own field name once if the emulator reaches it.
#define ARMSX3_OWNED(phase, field, ...) \
	(static_cast<void>(sizeof(&emu_callbacks::field)), \
	 ::armsx3::bridge::owned<phase>(#field, __VA_ARGS__))

// Logs once if reached, which is when the "ever" wants re-examining.
#define ARMSX3_DELIBERATE(field, ...) \
	(static_cast<void>(sizeof(&emu_callbacks::field)), \
	 ::armsx3::bridge::deliberate(#field, __VA_ARGS__))
