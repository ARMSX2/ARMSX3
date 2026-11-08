// SPDX-License-Identifier: GPL-2.0-only
// RPCN password forms: RPCS3's derived form first, the typed form for accounts older builds made; PBKDF2 is injected for the host test.

#pragma once

#include <cstddef>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace armsx3::bridge::rpcn
{
	// 64 upper-case hex digits: a password RPCS3 has already derived.
	inline bool is_prederived(std::string_view text)
	{
		if (text.size() != 64)
		{
			return false;
		}
		for (const char c : text)
		{
			if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
			{
				return false;
			}
		}
		return true;
	}

	inline std::string to_upper_hex(const unsigned char* bytes, std::size_t size)
	{
		constexpr char digits[] = "0123456789ABCDEF";
		std::string out(size * 2, '0');
		for (std::size_t i = 0; i < size; i++)
		{
			out[i * 2] = digits[bytes[i] >> 4];
			out[i * 2 + 1] = digits[bytes[i] & 15];
		}
		return out;
	}

	// An empty password is never sent, so a failed derivation offers only the typed form.
	template <typename Pbkdf2>
	std::vector<std::string> password_forms(std::string_view typed, Pbkdf2&& derive)
	{
		if (typed.empty())
		{
			return {};
		}
		if (is_prederived(typed))
		{
			return {std::string(typed)};
		}
		std::vector<std::string> forms;
		if (std::string derived = std::forward<Pbkdf2>(derive)(typed); !derived.empty())
		{
			forms.push_back(std::move(derived));
		}
		forms.emplace_back(typed);
		return forms;
	}
}
