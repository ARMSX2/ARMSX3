// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{
	// Writes an ISO's ICON0.PNG where a caller can open it, from the core's cache or the image; a failure is an empty path.
	std::string cache_iso_icon(const GameInfo& g, const std::string& caches_root)
	{
		const std::string dest = armsx3::bridge::icon_cache_path(caches_root, g.serial);
		if (dest.empty())
		{
			return {};
		}

		// The cache key game_enumeration.h uses: the image, or the image plus a PS3_GMxx directory.
		std::vector<u8> bytes;
		iso_metadata_cache_entry cached{};
		if (iso_cache::load(g.path, g.game_dir.empty() ? g.path : g.path + "//" + g.game_dir, cached))
		{
			bytes = std::move(cached.icon_data);
		}

		if (bytes.empty())
		{
			iso_archive archive(g.path);
			if (archive.is_valid())
			{
				if (const auto file = archive.open(g.icon_path); file && file->size() > 0)
				{
					bytes.resize(file->size());
					bytes.resize(file->read(bytes.data(), bytes.size()));
				}
			}
		}

		if (bytes.empty() || !fs::create_path(fs::get_parent_dir(dest)) || !fs::write_file(dest, fs::rewrite, bytes))
		{
			return {};
		}

		return dest;
	}
}


Rpcs3IOS_Status Rpcs3IOS_ForEachGame(Rpcs3IOS_GameVisitor visit, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_ForEachGame");
	}

	if (visit == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The root comes from the vfs configuration `Emu.Init()` loads.
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// The title is display text only and never reaches a path.
	for (const armsx3::bridge::game_dir& g : armsx3::bridge::scan_game_dirs(rpcs3::utils::get_hdd0_dir() + "game/"))
	{
		const auto sfo = psf::load_object(g.sfo_path);
		std::string title{psf::get_string(sfo, "TITLE")};
		if (title.empty())
		{
			title = g.serial;
		}

		visit(context, g.serial.c_str(), title.c_str(), g.boot_path.c_str());
	}

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_ScanLibrary(Rpcs3IOS_LibraryVisitor visit, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_ScanLibrary");
	}

	if (visit == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The roots come from the vfs configuration and games.yml, both loaded by `Emu.Init()`.
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// Upstream's scanner in the Big Picture overlay's order, without the VSH tile and with each game's source known.
	game_enumeration<GameInfo> scan;
	scan.initialize_paths();
	scan.set_localization(g_cfg.sys.language, "Unknown", [](const std::string&) { return std::string(); });
	scan.set_show_custom_icons(false);
	scan.set_play_hover_movies(false);
	scan.set_play_hover_music(false);
	scan.parse_directories();
	scan.remove_duplicates();

	std::vector<std::int32_t> sources;
	for (const auto& entry : scan.path_entries())
	{
		scan.parse_entry(entry);
		sources.resize(scan.games().size(), entry.is_from_yml ? 1 : 0);
	}

	scan.apply_patches();
	std::vector<GameInfo> games = scan.take_games();
	scan.clear(true);

	// HOME is the app container on a device and a throwaway folder in the host test.
	const char* const home = std::getenv("HOME");
	const std::string caches_root = home != nullptr && *home != '\0' ? std::string{home} + "/Library/Caches" : std::string{};

	// Bootable, and not game data, add-on, save, home or media content.
	std::vector<armsx3::bridge::library_row> rows;
	for (usz i = 0; i < games.size(); i++)
	{
		const GameInfo& g = games[i];
		if (!g.bootable || armsx3::bridge::excluded_category(g.category))
		{
			continue;
		}

		armsx3::bridge::library_row row{g.serial, g.name, g.category, g.path, {}, {}, g.is_iso_file, sources[i]};

		if (g.is_iso_file && g.icon_in_archive)
		{
			row.icon_path = cache_iso_icon(g, caches_root);
		}
		else if (fs::is_file(g.icon_path))
		{
			row.icon_path = g.icon_path;
		}

		if (!g.is_iso_file)
		{
			row.backdrop_path = armsx3::bridge::backdrop_path_for(row.icon_path);
		}

		rows.push_back(std::move(row));
	}

	armsx3::bridge::sort_rows(rows);

	// No path and no serial is logged anywhere on this path.
	for (const armsx3::bridge::library_row& r : rows)
	{
		const Rpcs3IOS_LibraryEntry entry{r.serial.c_str(), r.title.c_str(), r.category.c_str(), r.boot_path.c_str(),
			r.icon_path.c_str(), r.backdrop_path.c_str(), r.is_iso ? 1 : 0, r.source};
		visit(context, &entry);
	}

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RegisterGameFolder(const char* folder_path, uint32_t* out_games_added)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RegisterGameFolder");
	}

	// No containment check: the caller is this process, and on a device the sandbox is the containment.
	if (folder_path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string folder{folder_path};

	if (folder.empty() || !fs::exists(folder))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// games.yml is the running game's configuration too; it is rewritten only while stopped.
	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const u32 added = Emu.AddGamesFromDir(folder);

	if (out_games_added != nullptr)
	{
		*out_games_added = added;
	}

	return RPCS3_IOS_STATUS_OK;
}
