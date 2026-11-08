// SPDX-License-Identifier: GPL-2.0-only
// cellPhotoExport copies the photo itself after asking for a path, so destinations are queued for Swift to add to Photos.
#pragma once

#include "Emu/System.h"
#include "Emu/VFS.h"
#include "Utilities/File.h"
#include "Utilities/StrFmt.h"
#include "Emu/IdManager.h" // g_fxo, the core's object table

#include <ctime>
#include <deque>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

namespace armsx3::bridge
{
	// A parameter only so the macOS leg's probe can reach the numbered suffixes.
	inline std::string photo_destination(std::string_view filename, const std::tm& when,
		bool (*stopped)() = [] { return Emu.IsStopped(); })
	{
		std::string_view extension = ".png";
		if (const auto at = filename.find_last_of('.'); at != std::string_view::npos)
		{
			extension = filename.substr(at);
			filename = filename.substr(0, at);
		}

		// Before Emu.Init, vfs::get would abort the process.
		if (!g_fxo->is_init())
		{
			return {};
		}

		const std::string path = vfs::get(fmt::format("/dev_hdd0/photo/%04d/%02d/%02d/%s %02d-%02d-%04d %02d-%02d-%02d",
			when.tm_year + 1900, when.tm_mon + 1, when.tm_mday, vfs::escape(filename, true),
			when.tm_mday, when.tm_mon + 1, when.tm_year + 1900, when.tm_hour, when.tm_min, when.tm_sec));
		if (path.empty())
		{
			return {}; // no /dev_hdd0 mounted: the core's copy then fails, as with no callback
		}

		std::string suffix(extension);
		for (u32 counter = 0; !stopped() && fs::is_file(path + suffix);)
		{
			suffix = fmt::format(" %d%s", ++counter, extension);
		}

		return path + suffix;
	}

	inline std::mutex& exported_photos_mutex()
	{
		static std::mutex mutex;
		return mutex;
	}

	inline std::deque<std::string>& exported_photos()
	{
		static std::deque<std::string> list;
		return list;
	}

	inline void push_exported_photo(std::string path)
	{
		std::lock_guard lock(exported_photos_mutex());
		auto& list = exported_photos();
		list.push_back(std::move(path));
		while (list.size() > 32)
		{
			list.pop_front();
		}
	}

	inline std::vector<std::string> take_exported_photos()
	{
		std::lock_guard lock(exported_photos_mutex());
		auto& list = exported_photos();
		std::vector<std::string> taken(std::make_move_iterator(list.begin()), std::make_move_iterator(list.end()));
		list.clear();
		return taken;
	}
}
