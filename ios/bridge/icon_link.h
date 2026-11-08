// SPDX-License-Identifier: GPL-2.0-only
// Links the config folder's Icons/ui to the bundle's, so the core's icon lookup hits first; re-checked every launch.
#pragma once

#include <climits>
#include <cstdint>
#include <filesystem>
#include <string>
#include <system_error>

#include <mach-o/dyld.h>

namespace armsx3::bridge
{
	// Not fs::get_executable_dir(): on Apple that climbs three folders for a macOS .app, wrong for the flat iOS bundle.
	inline std::string executable_path()
	{
		char buf[PATH_MAX];
		uint32_t size = sizeof(buf);
		return _NSGetExecutablePath(buf, &size) == 0 ? std::string(buf) : std::string();
	}

	// True when `<config_dir>/Icons/ui` is, on return, a readable link to `<folder of exe_path>/Icons/ui`.
	inline bool link_icon_tree(const std::string& config_dir, const std::string& exe_path, bool replace_dir)
	{
		namespace fs = std::filesystem;
		std::error_code ec;
		if (exe_path.empty())
			return false;

		const fs::path source = fs::canonical(fs::path(exe_path).parent_path() / "Icons" / "ui", ec);
		if (ec || !fs::is_directory(source, ec) || ec)
			return false;

		const fs::path link = fs::path(config_dir) / "Icons" / "ui";
		bool in_place = false;
		const fs::file_status st = fs::symlink_status(link, ec);

		if (fs::is_symlink(st))
		{
			std::error_code rec;
			if (fs::read_symlink(link, rec) == source && !rec)
				in_place = true;
			else if (!fs::remove(link, ec))
				return false;
		}
		else if (fs::exists(st))
		{
			if (!replace_dir)
				return false;
			fs::remove_all(link, ec);
			if (ec)
				return false;
		}

		if (!in_place)
		{
			fs::create_directories(link.parent_path(), ec);
			ec.clear();
			fs::create_directory_symlink(source, link, ec);
			if (ec)
				return false;
		}

		// If the sandbox will not let the app read its bundle through the link, drop it so the core copies on find.
		fs::directory_iterator probe(link, ec);
		if (ec)
		{
			std::error_code rec;
			fs::remove(link, rec);
			return false;
		}

		return true;
	}
}
