// SPDX-License-Identifier: GPL-2.0-only
// The core's settings tree as JSON and writes into its settings files; strings only, so any bridge file can include it.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace armsx3::bridge::settings
{
	constexpr const char* k_path_separator = "@@";

	// The C ABI maps each to a status.
	enum class outcome
	{
		ok,
		invalid,      // an unknown path, a section, or a collection (set, map, log, device) node
		refused,      // the leaf's own parser refused the value; nothing was written
		write_failed, // the file could not be written; the previous file is intact
		locked,       // the product file forces this leaf; nothing was written
		unreadable,   // too large, nested too deep, not a YAML map or not YAML; nothing was written
	};

	// Every scalar leaf path lands in exactly one count; an empty collection in none.
	struct counts
	{
		std::uint32_t kept = 0;      // a real change: a leaf this build has, a value it accepts, not the default
		std::uint32_t unknown = 0;   // no editable leaf in this build, a value the leaf refuses, or a non-empty collection
		std::uint32_t defaulted = 0; // equal to the core's default
		std::uint32_t locked = 0;    // a key the iOS build forces, or this install's identity (System Name, Console PSID)
	};

	// Read once where the boot reads it; all empty without a cached database.
	struct db_layer_view
	{
		std::string entry_text;
		std::size_t added = 0;
		std::vector<std::string> fix_paths;
		bool known = false; // the database has an entry for the serial
	};

	// How many leaves each layer of a boot contributed, for the boot's product line.
	struct layer_counts
	{
		std::size_t global = 0, db = 0, game = 0, forced = 0;
		// Per boot only, never written to the player's files.
		unsigned long long jetsam_limit = 0;
		unsigned llvm_threads_clamped = 0;
		bool shader_interpreter_off = false;
		bool early_cse_off = false;
		std::size_t redirects = 0;
	};

	// Paths cross this boundary joined by k_path_separator, never `/`, because `Input/Output` is one node name.
	outcome dump(const std::string& serial, const std::string& product_config, const db_layer_view& db,
		std::string& out_json);

	// A forced leaf is refused as `locked`; `out_live` is true exactly when a running game took the value.
	outcome set(const std::string& serial, const std::string& path, const std::string& value,
		const std::string& product_config, const db_layer_view& running_db, bool& out_live);

	// An empty `paths` deletes the whole per-game file, and is invalid globally.
	outcome reset(const std::string& serial, const std::vector<std::string>& paths, const std::string& product_config,
		const db_layer_view& running_db, bool& out_live);

	// Emitted sparse: a whole root would overwrite the renderer and adapter Emulator::Init sets before an override.
	std::string boot_text(const std::string& serial, const db_layer_view& db, const std::string& product_config,
		layer_counts& counts);

	outcome live_value(const std::string& path, std::string& out);

	// Desktop's OnEmuSettingsChange scoped to one path; does nothing while the emulator is stopped.
	void apply_side_effects(const std::string& path);

	// Desktop's whole OnEmuSettingsChange recipe in its order; does nothing while stopped.
	void apply_all_side_effects();

	// Only dynamic leaves, never the whole tree, which would freeze every key and suppress every database fix.
	std::size_t save_running_title(const db_layer_view& db, const std::string& product_config);

	// Hostile input is `unreadable` before any parse that could recurse without bound.
	outcome normalise(std::string_view text, const std::string& product_config, std::string& out_yaml, counts& c);

	// One atomic write per call and none when nothing changed, so the same preset twice leaves the same bytes.
	outcome apply(const std::string& serial, std::string_view text, const std::string& product_config,
		const db_layer_view& running_db, std::uint32_t& out_changed, bool& out_live);

	// Both scopes are normalised, so an export is exactly what an import of it would keep.
	outcome export_scope(const std::string& serial, const std::string& product_config, std::string& out_yaml);

	// Once per install, and only while the stored value equals the default; Emulator::Init writes every key, so absence never happens.
	std::size_t seed_defaults(const std::vector<std::pair<std::string, std::string>>& path_values);

	// For the macOS leg's --seed-probe.
	std::size_t last_seed_count();

	// Defined with the C ABI, which holds the database layer and the boot record's product path.
	void on_save_emu_settings();
}
