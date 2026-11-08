// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{
	// The five frame limits the menu offers and nothing else, never none or infinite.
	bool frame_limit_from_code(int32_t code, frame_limit_type& out)
	{
		switch (code)
		{
		case 0: out = frame_limit_type::_auto; return true;
		case 1: out = frame_limit_type::display_rate; return true;
		case 30: out = frame_limit_type::_30; return true;
		case 50: out = frame_limit_type::_50; return true;
		case 60: out = frame_limit_type::_60; return true;
		default: return false;
		}
	}

	// The reverse, for the read back; a limit a configuration file asked for outside the five is -1.
	int32_t frame_limit_code(frame_limit_type limit)
	{
		switch (limit)
		{
		case frame_limit_type::_auto: return 0;
		case frame_limit_type::display_rate: return 1;
		case frame_limit_type::_30: return 30;
		case frame_limit_type::_50: return 50;
		case frame_limit_type::_60: return 60;
		default: return -1;
		}
	}
	Rpcs3IOS_Status settings_status(armsx3::bridge::settings::outcome outcome)
	{
		switch (outcome)
		{
		case armsx3::bridge::settings::outcome::ok: return RPCS3_IOS_STATUS_OK;
		case armsx3::bridge::settings::outcome::invalid: return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		case armsx3::bridge::settings::outcome::refused: return RPCS3_IOS_STATUS_SETTING_REFUSED;
		case armsx3::bridge::settings::outcome::write_failed: return RPCS3_IOS_STATUS_SETTINGS_WRITE_FAILED;
		case armsx3::bridge::settings::outcome::locked: return RPCS3_IOS_STATUS_SETTING_LOCKED;
		case armsx3::bridge::settings::outcome::unreadable: return RPCS3_IOS_STATUS_SETTINGS_UNREADABLE;
		}
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// Every file a boot applies patches from, re-read only when one changed; returned by value under the lock.
	patch_engine::patch_map cached_patch_db()
	{
		static std::mutex mutex;
		static patch_engine::patch_map db;
		static std::array<std::pair<u64, s64>, 4> cached{};
		static bool loaded = false;

		std::lock_guard lock(mutex);

		const std::string dir = patch_engine::get_patches_path();
		const std::array<std::string, 4> paths{
			dir + "patch.yml", patch_engine::get_imported_patch_path(), dir + "artemis_patch.yml", dir + "armsx3_patch.yml"};

		std::array<std::pair<u64, s64>, 4> now{};
		for (usz i = 0; i < paths.size(); i++)
		{
			fs::stat_t info{};
			if (fs::get_stat(paths[i], info))
			{
				now[i] = {info.size, info.mtime};
			}
		}

		if (!loaded || now != cached)
		{
			db.clear();
			std::stringstream log;
			for (const auto& path : paths)
			{
				patch_engine::load(db, path, {}, false, &log);
			}
			cached = now;
			loaded = true;
		}

		return db;
	}

	// Never with empty text, because patch_engine::load then reads the file named by `path`.
	bool parse_patches(patch_engine::patch_map& patches, const std::string& name, const char* yaml)
	{
		std::stringstream log;
		return *yaml != '\0' && patch_engine::load(patches, name, std::string(yaml), true, &log);
	}
}


Rpcs3IOS_Status Rpcs3IOS_SetLiveSetting(int32_t which, int32_t value)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetLiveSetting");
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	frame_limit_type limit{};
	switch (which)
	{
	case RPCS3_IOS_LIVE_VOLUME:
		if (value < 0 || value > 200)
		{
			return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
		g_cfg.audio.volume.set(value);
		break;
	case RPCS3_IOS_LIVE_RESOLUTION_SCALE:
		if (value < 25 || value > 800 || value % 25 != 0)
		{
			return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
		g_cfg.video.resolution_scale_percent.set(static_cast<u64>(value));
		break;
	case RPCS3_IOS_LIVE_FRAME_LIMIT:
		if (!frame_limit_from_code(value, limit))
		{
			return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
		g_cfg.video.frame_limit.set(limit);
		break;
	case RPCS3_IOS_LIVE_PERF_OVERLAY:
		if (value != 0 && value != 1)
		{
			return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
		g_cfg.video.perf_overlay.enabled.set(value == 1);
		// The overlay is built from the configuration, so turning it on mid-game goes through the core's own reset.
		armsx3::bridge::settings::apply_side_effects("Video@@Performance Overlay@@Enabled");
		break;
	default:
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SetLiveSetting finished with status %d.\n", static_cast<int>(RPCS3_IOS_STATUS_OK));

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetLiveSetting(int32_t which, int32_t* out_value)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetLiveSetting");
	}

	if (out_value == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	switch (which)
	{
	case RPCS3_IOS_LIVE_VOLUME:
		*out_value = static_cast<int32_t>(g_cfg.audio.volume.get());
		return RPCS3_IOS_STATUS_OK;
	case RPCS3_IOS_LIVE_RESOLUTION_SCALE:
		*out_value = static_cast<int32_t>(g_cfg.video.resolution_scale_percent.get());
		return RPCS3_IOS_STATUS_OK;
	case RPCS3_IOS_LIVE_FRAME_LIMIT:
		*out_value = frame_limit_code(g_cfg.video.frame_limit.get());
		return RPCS3_IOS_STATUS_OK;
	case RPCS3_IOS_LIVE_PERF_OVERLAY:
		*out_value = g_cfg.video.perf_overlay.enabled.get() ? 1 : 0;
		return RPCS3_IOS_STATUS_OK;
	default:
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}
}

Rpcs3IOS_Status Rpcs3IOS_SettingsDump(const char* serial, const char* product_config, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsDump");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const std::string scope = serial != nullptr ? serial : "";
	std::string json;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::dump(scope,
		product_config != nullptr ? product_config : "", db_layer(scope), json));
	if (status == RPCS3_IOS_STATUS_OK)
	{
		sink(context, json.c_str(), static_cast<uint32_t>(json.size()));
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsDump finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_SettingsSet(const char* serial, const char* path, const char* value, const char* product_config,
	int32_t* out_live)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsSet");
	}

	if (path == nullptr || value == nullptr || out_live == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_live = 0;
	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// The running title's database layer, for the live apply's effective value.
	const bool running = !Emu.IsStopped();
	bool live = false;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::set(serial != nullptr ? serial : "", path, value,
		product_config != nullptr ? product_config : "",
		running ? db_layer(Emu.GetTitleID()) : armsx3::bridge::settings::db_layer_view{}, live));
	*out_live = live ? 1 : 0;
	if (status == RPCS3_IOS_STATUS_OK && !Emu.IsStopped())
	{
		refresh_boot_record();
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsSet finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_SettingsReset(const char* serial, const char* const* paths, uint32_t count, const char* product_config,
	int32_t* out_live)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsReset");
	}

	if (out_live == nullptr || (paths == nullptr && count != 0))
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	for (uint32_t i = 0; i < count; i++)
	{
		if (paths[i] == nullptr)
		{
			return RPCS3_IOS_STATUS_NULL_ARGUMENT;
		}
	}

	*out_live = 0;
	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const std::vector<std::string> keys(paths, paths + count);
	const bool running = !Emu.IsStopped();
	bool live = false;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::reset(serial != nullptr ? serial : "", keys,
		product_config != nullptr ? product_config : "",
		running ? db_layer(Emu.GetTitleID()) : armsx3::bridge::settings::db_layer_view{}, live));
	*out_live = live ? 1 : 0;
	if (status == RPCS3_IOS_STATUS_OK && !Emu.IsStopped())
	{
		refresh_boot_record();
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsReset finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_SettingsGetLive(const char* path, char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsGetLive");
	}

	if (path == nullptr || out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	std::string value;
	Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::live_value(path, value));
	if (status == RPCS3_IOS_STATUS_OK && value.size() >= cap)
	{
		status = RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}
	if (status == RPCS3_IOS_STATUS_OK)
	{
		std::snprintf(out, cap, "%s", value.c_str());
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsGetLive finished with status %d.\n", static_cast<int>(status));
	return status;
}

// Thin wrappers; every YAML byte is read and judged in settings_engine.cpp, and only status and counts are logged.
Rpcs3IOS_Status Rpcs3IOS_SettingsNormalise(const char* yaml, uint32_t size, const char* product_config,
	Rpcs3IOS_TextSink sink, void* context, Rpcs3IOS_SettingsCounts* out_counts)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsNormalise");
	}

	if (yaml == nullptr || sink == nullptr || out_counts == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_counts = {};
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	std::string text;
	armsx3::bridge::settings::counts found;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::normalise(std::string_view(yaml, size),
		product_config != nullptr ? product_config : "", text, found));
	*out_counts = {found.kept, found.unknown, found.defaulted, found.locked};
	if (status == RPCS3_IOS_STATUS_OK)
	{
		sink(context, text.c_str(), static_cast<uint32_t>(text.size()));
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsNormalise finished with status %d kept=%u unknown=%u defaulted=%u locked=%u.\n",
		static_cast<int>(status), found.kept, found.unknown, found.defaulted, found.locked);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_SettingsApply(const char* serial, const char* yaml, uint32_t size, const char* product_config,
	uint32_t* out_changed, int32_t* out_live)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsApply");
	}

	if (yaml == nullptr || out_changed == nullptr || out_live == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_changed = 0;
	*out_live = 0;
	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// As Rpcs3IOS_SettingsSet: the running title's database layer, for the live apply's effective value.
	const bool running = !Emu.IsStopped();
	std::uint32_t changed = 0;
	bool live = false;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::apply(serial != nullptr ? serial : "",
		std::string_view(yaml, size), product_config != nullptr ? product_config : "",
		running ? db_layer(Emu.GetTitleID()) : armsx3::bridge::settings::db_layer_view{}, changed, live));
	*out_changed = changed;
	*out_live = live ? 1 : 0;
	if (status == RPCS3_IOS_STATUS_OK && !Emu.IsStopped())
	{
		refresh_boot_record();
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsApply finished with status %d changed=%u live=%d.\n",
		static_cast<int>(status), changed, live ? 1 : 0);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_SettingsExport(const char* serial, const char* product_config, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SettingsExport");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	std::string text;
	const Rpcs3IOS_Status status = settings_status(armsx3::bridge::settings::export_scope(serial != nullptr ? serial : "",
		product_config != nullptr ? product_config : "", text));
	if (status == RPCS3_IOS_STATUS_OK)
	{
		sink(context, text.c_str(), static_cast<uint32_t>(text.size()));
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SettingsExport finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_PatchEngineVersion(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PatchEngineVersion");
	}

	if (out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::snprintf(out, cap, "%s", patch_engine_version.c_str());
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_PatchesWrite(const char* file_name, const char* yaml)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PatchesWrite");
	}

	if (file_name == nullptr || yaml == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// Only the three files a source owns outright; the player's imports are merged, never replaced.
	const std::string_view name{file_name};
	if (name != "patch.yml" && name != "artemis_patch.yml" && name != "armsx3_patch.yml")
	{
		return finished_status("Rpcs3IOS_PatchesWrite", RPCS3_IOS_STATUS_INVALID_ARGUMENT);
	}

	patch_engine::patch_map patches;
	if (!parse_patches(patches, std::string(name), yaml))
	{
		return finished_status("Rpcs3IOS_PatchesWrite", RPCS3_IOS_STATUS_PATCH_INVALID);
	}

	const std::string dir = patch_engine::get_patches_path();
	const usz size = std::strlen(yaml);
	if (!fs::create_path(dir))
	{
		return finished_status("Rpcs3IOS_PatchesWrite", RPCS3_IOS_STATUS_PATCH_WRITE_FAILED);
	}

	fs::pending_file file(dir + std::string(name));
	if (!file.file || file.file.write(yaml, size) != size || !file.commit())
	{
		return finished_status("Rpcs3IOS_PatchesWrite", RPCS3_IOS_STATUS_PATCH_WRITE_FAILED);
	}

	return finished_status("Rpcs3IOS_PatchesWrite", RPCS3_IOS_STATUS_OK);
}

Rpcs3IOS_Status Rpcs3IOS_PatchesImport(const char* yaml)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PatchesImport");
	}

	if (yaml == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	patch_engine::patch_map patches;
	if (!parse_patches(patches, "<imported>", yaml))
	{
		return finished_status("Rpcs3IOS_PatchesImport", RPCS3_IOS_STATUS_PATCH_INVALID);
	}

	usz count = 0;
	usz total = 0;
	std::stringstream log;
	if (!fs::create_path(patch_engine::get_patches_path()) ||
		!patch_engine::import_patches(patches, patch_engine::get_imported_patch_path(), count, total, &log))
	{
		return finished_status("Rpcs3IOS_PatchesImport", RPCS3_IOS_STATUS_PATCH_WRITE_FAILED);
	}

	return finished_status("Rpcs3IOS_PatchesImport", RPCS3_IOS_STATUS_OK);
}

Rpcs3IOS_Status Rpcs3IOS_PatchesList(const char* serial, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PatchesList");
	}

	if (serial == nullptr || sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!serial_component_ok(serial))
	{
		return finished_status("Rpcs3IOS_PatchesList", RPCS3_IOS_STATUS_INVALID_ARGUMENT);
	}

	const std::string_view wanted{serial};
	const patch_engine::patch_map db = cached_patch_db();
	const patch_engine::patch_map enabled = patch_engine::load_config();

	std::string out = "[";
	bool first = true;
	for (const auto& [hash, container] : db)
	{
		for (const auto& [description, info] : container.patch_info_map)
		{
			// Only patches naming this serial; an `All`-only patch is filtered by hash and belongs to no one game.
			bool applies = false;
			std::string app_version = "all";
			std::string game_title;
			for (const auto& [title, serials] : info.titles)
			{
				for (const auto& [ser, versions] : serials)
				{
					if (ser == wanted)
					{
						applies = true;
						game_title = title;
						if (!versions.empty())
						{
							app_version = versions.begin()->first;
						}
					}
				}
			}

			if (!applies)
			{
				continue;
			}

			// On for this serial only, or through an `All` entry, which reaches this game too.
			bool is_on = false;
			if (const auto it = enabled.find(hash); it != enabled.end())
			{
				if (const auto p = it->second.patch_info_map.find(description); p != it->second.patch_info_map.end())
				{
					for (const auto& [title, serials] : p->second.titles)
					{
						for (const auto& [ser, versions] : serials)
						{
							if (ser != wanted && ser != patch_key::all)
							{
								continue;
							}
							for (const auto& [ver, values] : versions)
							{
								is_on = is_on || values.enabled;
							}
						}
					}
				}
			}

			out += first ? "" : ",";
			first = false;
			out += "{\"hash\":" + patch_json_quote(hash) + ",\"name\":" + patch_json_quote(description) +
				",\"author\":" + patch_json_quote(info.author) + ",\"notes\":" + patch_json_quote(info.notes) +
				",\"version\":" + patch_json_quote(info.patch_version) + ",\"appVersion\":" + patch_json_quote(app_version) +
				",\"game\":" + patch_json_quote(game_title) + ",\"enabled\":" + (is_on ? "true" : "false") + "}";
		}
	}
	out += "]";

	sink(context, out.c_str(), static_cast<uint32_t>(out.size()));
	return finished_status("Rpcs3IOS_PatchesList", RPCS3_IOS_STATUS_OK);
}

Rpcs3IOS_Status Rpcs3IOS_PatchSetEnabled(const char* hash, const char* description, const char* serial,
	const char* app_version, int32_t enabled)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PatchSetEnabled");
	}

	if (hash == nullptr || description == nullptr || serial == nullptr || app_version == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if ((enabled != 0 && enabled != 1) || !serial_component_ok(serial))
	{
		return finished_status("Rpcs3IOS_PatchSetEnabled", RPCS3_IOS_STATUS_INVALID_ARGUMENT);
	}

	const patch_engine::patch_map db = cached_patch_db();
	const auto h = db.find(hash);
	if (h == db.end())
	{
		return finished_status("Rpcs3IOS_PatchSetEnabled", RPCS3_IOS_STATUS_INVALID_ARGUMENT);
	}

	const auto p = h->second.patch_info_map.find(description);
	if (p == h->second.patch_info_map.end())
	{
		return finished_status("Rpcs3IOS_PatchSetEnabled", RPCS3_IOS_STATUS_INVALID_ARGUMENT);
	}

	// save_config writes only entries that are on or tuned, so switching off drops the entry.
	patch_engine::patch_map config = patch_engine::load_config();

	// save_config judges tuned values against default_config_values, which load_config never fills, so they come from the database.
	for (auto& [config_hash, container] : config)
	{
		if (const auto dh = db.find(config_hash); dh != db.end())
		{
			for (auto& [config_description, config_info] : container.patch_info_map)
			{
				if (const auto dp = dh->second.patch_info_map.find(config_description); dp != dh->second.patch_info_map.end())
				{
					config_info.default_config_values = dp->second.default_config_values;
				}
			}
		}
	}

	auto& entry = config[hash];
	entry.hash = hash;
	entry.version = h->second.version;

	// Every serial's state as patch_config.yml holds it now, so only the requested serial's switch moves.
	auto& info = entry.patch_info_map[description];
	patch_engine::patch_info merged = p->second;
	for (auto& [title, serials] : merged.titles)
	{
		for (auto& [ser, versions] : serials)
		{
			for (auto& [ver, values] : versions)
			{
				values = {};
			}
		}
	}
	for (const auto& [title, serials] : info.titles)
	{
		for (const auto& [ser, versions] : serials)
		{
			for (const auto& [ver, values] : versions)
			{
				merged.titles[title][ser][ver] = values;
			}
		}
	}
	info = std::move(merged);

	const std::string_view wanted{serial};
	for (auto& [title, serials] : info.titles)
	{
		for (auto& [ser, versions] : serials)
		{
			for (auto& [ver, values] : versions)
			{
				if (ser == wanted || ser == patch_key::all)
				{
					values.enabled = enabled != 0;
				}
			}
		}
	}

	patch_engine::save_config(config);
	return finished_status("Rpcs3IOS_PatchSetEnabled", RPCS3_IOS_STATUS_OK);
}
