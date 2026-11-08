// SPDX-License-Identifier: GPL-2.0-only
// The library scan's rules; the serial comes from a user-picked PARAM.SFO, so safe_serial keeps only [A-Za-z0-9_-].
#pragma once

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>

namespace armsx3::bridge
{
	inline bool excluded_category(std::string_view category)
	{
		return category == "GD" || category == "AC" || category == "SD" || category == "HM" || category == "MS";
	}

	inline std::string safe_serial(std::string_view serial)
	{
		std::string out;
		for (const char c : serial)
		{
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')
				out += c;
		}
		return out;
	}

	// Empty when either input reduces to nothing: the caller then reports no icon.
	inline std::string icon_cache_path(const std::string& caches_root, std::string_view serial)
	{
		const std::string safe = safe_serial(serial);
		if (caches_root.empty() || safe.empty())
			return {};
		return (std::filesystem::path(caches_root) / "armsx3" / "icons" / safe / "ICON0.PNG").string();
	}

	inline std::string backdrop_path_for(const std::string& icon_path)
	{
		if (icon_path.empty())
			return {};
		std::error_code ec;
		const std::filesystem::path pic1 = std::filesystem::path(icon_path).parent_path() / "PIC1.PNG";
		return std::filesystem::is_regular_file(pic1, ec) ? pic1.string() : std::string{};
	}

	struct library_row
	{
		std::string serial;
		std::string title;
		std::string category;
		std::string boot_path;
		std::string icon_path;
		std::string backdrop_path;
		bool is_iso = false;
		std::int32_t source = 0; // 0 = dev_hdd0, 1 = games.yml
	};

	inline void sort_rows(std::vector<library_row>& rows)
	{
		std::sort(rows.begin(), rows.end(), [](const library_row& a, const library_row& b)
		{
			return a.title != b.title ? a.title < b.title : a.serial < b.serial;
		});
	}
} // namespace armsx3::bridge
