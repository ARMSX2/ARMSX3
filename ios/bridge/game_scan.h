// SPDX-License-Identifier: GPL-2.0-only
// A title is a direct child of dev_hdd0/game with PARAM.SFO and USRDIR/EBOOT.BIN, listed in byte order; nothing here throws.
#pragma once

#include <algorithm>
#include <filesystem>
#include <string>
#include <system_error>
#include <vector>

namespace armsx3::bridge
{
	struct game_dir
	{
		std::string serial;
		std::string sfo_path;
		std::string boot_path;
	};

	inline std::vector<game_dir> scan_game_dirs(const std::string& root)
	{
		namespace fs = std::filesystem;
		std::vector<game_dir> out;
		std::error_code ec;

		for (fs::directory_iterator it(root, ec), end; !ec && it != end; it.increment(ec))
		{
			const fs::path dir = it->path();
			const std::string name = dir.filename().string();
			if (name.empty() || name[0] == '.')
				continue;

			std::error_code probe;
			if (!fs::is_directory(dir, probe))
				continue;

			const fs::path sfo = dir / "PARAM.SFO";
			const fs::path boot = dir / "USRDIR" / "EBOOT.BIN";
			if (!fs::is_regular_file(sfo, probe) || !fs::is_regular_file(boot, probe))
				continue;

			out.push_back({name, sfo.string(), boot.string()});
		}

		std::sort(out.begin(), out.end(), [](const game_dir& a, const game_dir& b) { return a.serial < b.serial; });
		return out;
	}
} // namespace armsx3::bridge
