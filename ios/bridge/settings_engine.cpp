// SPDX-License-Identifier: GPL-2.0-only
// The settings engine: every walk is over a throwaway cfg_root built from files, and only apply_live writes g_cfg.
#include "settings_engine.h"
#include "low_memory.h"
#include "title_config.h" // boot_text's community-server redirects

#include "rpcs3_ios_abi.h"

#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/system_utils.hpp"
#include "Emu/Cell/Modules/cellAudio.h"
#include "Emu/Cell/lv2/sys_rsxaudio.h"
#include "Utilities/File.h"
#include "util/yaml.hpp"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#if defined(__APPLE__)
#include <mach/mach.h>
#endif

// Defined in rpcs3/Emu/System.cpp and set by Emulator::Init to the defaults it computed.
extern std::string g_cfg_defaults;

// Defined in the core's overlay sources and declared here the way main_application.cpp declares them.
namespace rsx::overlays
{
	extern void reset_performance_overlay();
	extern void reset_debug_overlay();
}

namespace
{
	using armsx3::bridge::settings::k_path_separator;
	using armsx3::bridge::settings::outcome;

	// Empty when any name is empty, which is never a node.
	std::vector<std::string> split_path(const std::string& path)
	{
		const std::string separator{k_path_separator};
		std::vector<std::string> keys;
		std::size_t start = 0;
		while (true)
		{
			const std::size_t end = path.find(separator, start);
			keys.push_back(path.substr(start, end == std::string::npos ? std::string::npos : end - start));
			if (keys.back().empty())
			{
				return {};
			}
			if (end == std::string::npos)
			{
				return keys;
			}
			start = end + separator.size();
		}
	}

	// Null when a name is missing or a leaf stands where a section belongs.
	cfg::_base* find_node(const cfg::_base& root, const std::vector<std::string>& keys)
	{
		cfg::_base* node = nullptr;
		const cfg::_base* at = &root;
		for (const std::string& name : keys)
		{
			if (at->get_type() != cfg::type::node)
			{
				return nullptr;
			}

			node = nullptr;
			for (cfg::_base* child : static_cast<const cfg::node*>(at)->get_nodes())
			{
				if (child->get_name() == name)
				{
					node = child;
					break;
				}
			}
			if (node == nullptr)
			{
				return nullptr;
			}
			at = node;
		}
		return node;
	}

	void json_append_escaped(std::string& out, const std::string& value)
	{
		out += '"';
		for (const char c : value)
		{
			switch (c)
			{
			case '"': out += "\\\""; break;
			case '\\': out += "\\\\"; break;
			case '\n': out += "\\n"; break;
			case '\r': out += "\\r"; break;
			case '\t': out += "\\t"; break;
			default:
				if (static_cast<unsigned char>(c) < 0x20)
				{
					char escaped[8];
					std::snprintf(escaped, sizeof(escaped), "\\u%04x", static_cast<unsigned>(c) & 0xff);
					out += escaped;
				}
				else
				{
					out += c;
				}
			}
		}
		out += '"';
	}

	// cfg::_float reports cfg::type::_int; std::to_string of a floating-point range always has a decimal point.
	bool cfg_is_float(const cfg::_base* node)
	{
		return node->min_to_string().find('.') != std::string::npos;
	}

	// The collection nodes never implemented from_string; their stub logs a fatal purecall and changes nothing.
	bool cfg_is_editable(const cfg::_base* node)
	{
		switch (node->get_type())
		{
		case cfg::type::node:
		case cfg::type::_bool:
		case cfg::type::_enum:
		case cfg::type::_int:
		case cfg::type::uint:
		case cfg::type::uint128:
		case cfg::type::string:
			return true;
		default:
			return false;
		}
	}

	const char* cfg_type_name(const cfg::_base* node)
	{
		switch (node->get_type())
		{
		case cfg::type::_bool: return "bool";
		case cfg::type::_enum: return "enum";
		case cfg::type::_int: return cfg_is_float(node) ? "float" : "int";
		case cfg::type::uint: return "uint";
		default: return "string";
		}
	}

	// The one place a value reaches a leaf's own parser; from_string's second argument is ignored by every leaf.
	outcome parse_into(cfg::_base& root, const std::vector<std::string>& keys, const std::string& value)
	{
		cfg::_base* const leaf = find_node(root, keys);
		if (leaf == nullptr || leaf->get_type() == cfg::type::node || !cfg_is_editable(leaf))
		{
			return outcome::invalid;
		}
		return leaf->from_string(value) ? outcome::ok : outcome::refused;
	}

	outcome normalised(cfg_root& scratch, const std::vector<std::string>& keys, const std::string& value, std::string& out)
	{
		const outcome parsed = parse_into(scratch, keys, value);
		if (parsed == outcome::ok)
		{
			out = find_node(scratch, keys)->to_string();
		}
		return parsed;
	}

	bool names_editable_leaf(const cfg_root& probe, const std::vector<std::string>& keys)
	{
		const cfg::_base* const leaf = keys.empty() ? nullptr : find_node(probe, keys);
		return leaf != nullptr && leaf->get_type() != cfg::type::node && cfg_is_editable(leaf);
	}

	// Unknown keys are skipped, so a desktop file with keys this build lacks still loads.
	void load_into(cfg_root& probe, const std::string& text)
	{
		if (!text.empty())
		{
			probe.from_string(text);
		}
	}

	std::string read_text(const std::string& path)
	{
		const fs::file file{path};
		return file ? file.to_string() : std::string{};
	}

	std::string global_config_path()
	{
		return fs::get_config_dir(true) + "config.yml";
	}

	bool write_atomically(const std::string& path, const std::string& text)
	{
		fs::pending_file temp(path);
		if (!temp.file)
		{
			return false;
		}
		temp.file.write(text.data(), text.size());
		return temp.commit();
	}

	// Through the core's yaml_load, which catches yaml-cpp's exception; null for an empty, unparseable or non-map text.
	YAML::Node load_yaml(const std::string& text)
	{
		if (text.empty())
		{
			return YAML::Node();
		}
		auto [node, error] = yaml_load(text);
		return error.empty() && node.IsMap() ? node : YAML::Node();
	}

	// Const lookups only: yaml-cpp's non-const operator[] inserts.
	bool yaml_scalar(const YAML::Node& node, const std::vector<std::string>& keys, std::size_t depth, std::string& out)
	{
		if (!node.IsDefined())
		{
			return false;
		}
		if (depth == keys.size())
		{
			if (!node.IsScalar())
			{
				return false;
			}
			out = node.Scalar();
			return true;
		}
		return node.IsMap() && yaml_scalar(node[keys[depth]], keys, depth + 1, out);
	}

	void assign(YAML::Node node, const std::vector<std::string>& keys, std::size_t depth, const std::string& value)
	{
		const std::string& key = keys[depth];
		if (depth + 1 == keys.size())
		{
			node[key] = value;
			return;
		}
		const YAML::Node& view = node;
		const YAML::Node existing = view[key];
		if (!existing.IsDefined() || !existing.IsMap())
		{
			node[key] = YAML::Node(YAML::NodeType::Map);
		}
		assign(node[key], keys, depth + 1, value);
	}

	// Prunes a section the removal empties. False when absent.
	bool erase(YAML::Node node, const std::vector<std::string>& keys, std::size_t depth)
	{
		const YAML::Node& view = node;
		if (!view.IsMap())
		{
			return false;
		}
		const YAML::Node child = view[keys[depth]];
		if (!child.IsDefined())
		{
			return false;
		}
		if (depth + 1 == keys.size())
		{
			return node.remove(keys[depth]);
		}
		if (!erase(child, keys, depth + 1))
		{
			return false;
		}
		if (child.IsMap() && child.size() == 0)
		{
			node.remove(keys[depth]);
		}
		return true;
	}

	// The per-game file holds only the keys the player set; a node with nothing left deletes the file instead.
	outcome write_game_file(const std::string& file, const YAML::Node& root)
	{
		if (root.size() == 0)
		{
			return fs::remove_file(file) || !fs::is_file(file) ? outcome::ok : outcome::write_failed;
		}

		YAML::Emitter out;
		out << root;
		const std::string dir = rpcs3::utils::get_custom_config_dir();
		if (!out.good() || !(fs::create_path(dir) || fs::is_dir(dir)))
		{
			return outcome::write_failed;
		}
		return write_atomically(file, std::string(out.c_str(), out.size())) ? outcome::ok : outcome::write_failed;
	}

	// Null before Init has run; a leaf missing there falls back to its def_to_string().
	std::unique_ptr<cfg_root> core_defaults()
	{
		if (g_cfg_defaults.empty())
		{
			return nullptr;
		}
		auto defaults = std::make_unique<cfg_root>();
		load_into(*defaults, g_cfg_defaults);
		return defaults;
	}

	std::string default_of(const cfg_root* defaults, const cfg::_base& leaf, const std::vector<std::string>& keys)
	{
		const cfg::_base* const core_default = defaults != nullptr ? find_node(*defaults, keys) : nullptr;
		return core_default != nullptr ? core_default->to_string() : leaf.def_to_string();
	}

	// Found by the leaf's address in g_cfg, so a renamed leaf follows without an edit here.
	int live_id(const std::vector<std::string>& keys)
	{
		const cfg::_base* const twin = find_node(g_cfg, keys);
		if (twin == &g_cfg.audio.volume)
		{
			return RPCS3_IOS_LIVE_VOLUME;
		}
		if (twin == &g_cfg.video.resolution_scale_percent)
		{
			return RPCS3_IOS_LIVE_RESOLUTION_SCALE;
		}
		if (twin == &g_cfg.video.frame_limit)
		{
			return RPCS3_IOS_LIVE_FRAME_LIMIT;
		}
		if (twin == &g_cfg.video.perf_overlay.enabled)
		{
			return RPCS3_IOS_LIVE_PERF_OVERLAY;
		}
		return -1;
	}

	struct dump_layers
	{
		const cfg_root* defaults; // Init's defaults, or null before Init has run
		const cfg_root& random_a; // two fresh roots: a leaf whose default differs between them is random
		const cfg_root& random_b;
		const YAML::Node& db;     // the title's database entry (null node for All games)
		const YAML::Node& game;   // the title's per-game file (null node for All games)
		const YAML::Node& forced; // the product file
		cfg_root& scratch;        // where a layer's value is normalised by the leaf's own parser
		const std::vector<std::string>& fix; // sorted, k_path_separator-joined
	};

	// JSON null when the layer does not set the leaf or the leaf refuses the value.
	void append_layer(std::string& out, const YAML::Node& layer, cfg_root& scratch, const std::vector<std::string>& keys)
	{
		std::string raw, value;
		if (yaml_scalar(layer, keys, 0, raw) && normalised(scratch, keys, raw, value) == outcome::ok)
		{
			json_append_escaped(out, value);
		}
		else
		{
			out += "null";
		}
	}

	std::string joined(const std::vector<std::string>& keys)
	{
		std::string out;
		for (const std::string& key : keys)
		{
			out += out.empty() ? key : k_path_separator + key;
		}
		return out;
	}

	bool random_default(const cfg_root& random_a, const cfg_root& random_b, const std::vector<std::string>& keys)
	{
		const cfg::_base* const a = find_node(random_a, keys);
		const cfg::_base* const b = find_node(random_b, keys);
		return (a != nullptr && b != nullptr && a->def_to_string() != b->def_to_string())
			|| find_node(g_cfg, keys) == &g_cfg.sys.system_name;
	}

	void emit_leaf(const cfg::_base& leaf, const std::vector<std::string>& keys, const dump_layers& layers, std::string& out)
	{
		out += "{\"path\":[";
		for (std::size_t i = 0; i < keys.size(); ++i)
		{
			if (i != 0)
			{
				out += ',';
			}
			json_append_escaped(out, keys[i]);
		}
		out += "],\"type\":";
		json_append_escaped(out, cfg_type_name(&leaf));
		out += leaf.get_is_dynamic() ? ",\"dynamic\":true" : ",\"dynamic\":false";

		out += ",\"default\":";
		json_append_escaped(out, default_of(layers.defaults, leaf, keys));
		out += ",\"global\":";
		json_append_escaped(out, leaf.to_string());
		out += ",\"db\":";
		append_layer(out, layers.db, layers.scratch, keys);
		out += ",\"game\":";
		append_layer(out, layers.game, layers.scratch, keys);
		out += ",\"forced\":";
		append_layer(out, layers.forced, layers.scratch, keys);

		out += ",\"variants\":[";
		if (leaf.get_type() == cfg::type::_enum)
		{
			bool first = true;
			for (const std::string& variant : leaf.to_list())
			{
				if (!first)
				{
					out += ',';
				}
				first = false;
				json_append_escaped(out, variant);
			}
		}
		out += "],\"min\":";
		json_append_escaped(out, leaf.min_to_string());
		out += ",\"max\":";
		json_append_escaped(out, leaf.max_to_string());

		// Console PSID differs between two fresh roots; System Name seeds from std::time, so it is also matched by address.
		out += random_default(layers.random_a, layers.random_b, keys) ? ",\"randomDefault\":true" : ",\"randomDefault\":false";
		out += std::binary_search(layers.fix.begin(), layers.fix.end(), joined(keys)) ? ",\"fix\":true" : ",\"fix\":false";
		out += ",\"liveId\":";
		const int live = live_id(keys);
		out += live < 0 ? std::string{"null"} : std::to_string(live);
		out += '}';
	}

	// The boot applies the product file last, so a value saved for a forced leaf could never take effect.
	bool is_forced(const std::string& product_config, const std::vector<std::string>& keys)
	{
		std::string ignored;
		return !product_config.empty() && yaml_scalar(load_yaml(read_text(product_config)), keys, 0, ignored);
	}

	outcome set_game(const std::string& serial, const std::vector<std::string>& keys, const std::string& value)
	{
		const auto probe = std::make_unique<cfg_root>();
		std::string normal;
		if (const outcome parsed = normalised(*probe, keys, value, normal); parsed != outcome::ok)
		{
			return parsed;
		}

		const std::string file = rpcs3::utils::get_custom_config_path(serial);
		const YAML::Node loaded = load_yaml(read_text(file));
		YAML::Node root = loaded.IsMap() ? loaded : YAML::Node(YAML::NodeType::Map);
		assign(root, keys, 0, normal);
		return write_game_file(file, root);
	}

	// None at all deletes the whole file.
	outcome reset_game(const std::string& serial, const std::vector<std::vector<std::string>>& all)
	{
		const std::string file = rpcs3::utils::get_custom_config_path(serial);
		const YAML::Node loaded = all.empty() ? YAML::Node() : load_yaml(read_text(file));
		if (!all.empty() && !loaded.IsMap())
		{
			return outcome::ok; // no file, or none this engine can edit: nothing set, nothing to remove
		}

		YAML::Node root = loaded.IsMap() ? loaded : YAML::Node(YAML::NodeType::Map);
		for (const std::vector<std::string>& keys : all)
		{
			erase(root, keys, 0);
		}
		return write_game_file(file, root);
	}

	outcome set_global(const std::vector<std::string>& keys, const std::string& value)
	{
		const std::string file = global_config_path();
		const auto probe = std::make_unique<cfg_root>();
		load_into(*probe, read_text(file));
		if (const outcome parsed = parse_into(*probe, keys, value); parsed != outcome::ok)
		{
			return parsed;
		}
		return write_atomically(file, probe->to_string()) ? outcome::ok : outcome::write_failed;
	}

	outcome reset_global(cfg_root& probe, const std::vector<std::vector<std::string>>& all)
	{
		const std::string file = global_config_path();
		load_into(probe, read_text(file));
		const std::unique_ptr<cfg_root> defaults = core_defaults();
		for (const std::vector<std::string>& keys : all)
		{
			if (const outcome parsed = parse_into(probe, keys, default_of(defaults.get(), *find_node(probe, keys), keys));
				parsed != outcome::ok)
			{
				return parsed;
			}
		}
		return write_atomically(file, probe.to_string()) ? outcome::ok : outcome::write_failed;
	}

	// A key `base` has and `layered` lacks cannot be expressed here; cfg_root always prints every key.
	YAML::Node diff(const YAML::Node& layered, const YAML::Node& base)
	{
		YAML::Node out(YAML::NodeType::Map);
		if (!layered.IsMap())
		{
			return out;
		}
		for (const auto& entry : layered)
		{
			const std::string key = entry.first.Scalar();
			const YAML::Node value = entry.second;
			const YAML::Node was = base.IsMap() ? base[key] : YAML::Node();
			if (value.IsMap() && was.IsDefined() && was.IsMap())
			{
				const YAML::Node inner = diff(value, was);
				if (inner.size() != 0)
				{
					out[key] = inner;
				}
			}
			else if (!was.IsDefined() || YAML::Dump(value) != YAML::Dump(was))
			{
				out[key] = value;
			}
		}
		return out;
	}

	std::size_t count_leaves(const YAML::Node& node)
	{
		if (!node.IsMap())
		{
			return 1;
		}
		std::size_t n = 0;
		for (const auto& entry : node)
		{
			n += count_leaves(entry.second);
		}
		return n;
	}

	// A sequence is not counted.
	std::size_t accepted_leaves(const YAML::Node& node, cfg_root& scratch, std::vector<std::string>& keys)
	{
		std::size_t n = 0;
		if (!node.IsMap())
		{
			return 0;
		}
		for (const auto& entry : node)
		{
			keys.push_back(entry.first.Scalar());
			if (entry.second.IsMap())
			{
				n += accepted_leaves(entry.second, scratch, keys);
			}
			else if (entry.second.IsScalar() && parse_into(scratch, keys, entry.second.Scalar()) == outcome::ok)
			{
				n++;
			}
			keys.pop_back();
		}
		return n;
	}

	std::size_t accepted_leaves(const YAML::Node& node, cfg_root& scratch)
	{
		std::vector<std::string> keys;
		return accepted_leaves(node, scratch, keys);
	}

	// Defaults, config.yml, the database entry, the per-game file, then the product file, in one throwaway root.
	std::unique_ptr<cfg_root> layered_probe(const std::string& serial, const armsx3::bridge::settings::db_layer_view& db,
		const std::string& product_config, armsx3::bridge::settings::layer_counts& counts, YAML::Node& baseline)
	{
		counts = {};
		// On the heap: cfg_root is large, and this runs on the facade's GCD queue thread.
		auto probe = std::make_unique<cfg_root>();
		const auto scratch = std::make_unique<cfg_root>();

		// Taken from the same root, so a random default cannot differ between the baseline and the layers on top.
		load_into(*probe, g_cfg_defaults);
		baseline = load_yaml(probe->to_string());

		load_into(*probe, read_text(global_config_path()));
		counts.global = count_leaves(diff(load_yaml(probe->to_string()), baseline));

		load_into(*probe, db.entry_text);
		counts.db = db.added;

		const std::string game_text = serial.empty() ? std::string{} : read_text(rpcs3::utils::get_custom_config_path(serial));
		load_into(*probe, game_text);
		counts.game = accepted_leaves(load_yaml(game_text), *scratch);

		// Last, so a key the product file forces wins over the player's and the database's.
		const std::string product_text = product_config.empty() ? std::string{} : read_text(product_config);
		load_into(*probe, product_text);
		counts.forced = accepted_leaves(load_yaml(product_text), *scratch);
		return probe;
	}

	void editable_leaves(const cfg::node& node, std::vector<std::string>& keys, std::vector<std::vector<std::string>>& out)
	{
		for (const cfg::_base* child : node.get_nodes())
		{
			if (!cfg_is_editable(child))
			{
				continue;
			}
			keys.push_back(child->get_name());
			if (child->get_type() == cfg::type::node)
			{
				editable_leaves(*static_cast<const cfg::node*>(child), keys, out);
			}
			else
			{
				out.push_back(keys);
			}
			keys.pop_back();
		}
	}

	// Checks `dynamic` here because from_string ignores it; the running leaf takes the title's effective value.
	bool apply_live(const std::string& serial, const std::vector<std::vector<std::string>>& all,
		const armsx3::bridge::settings::db_layer_view& running_db, const std::string& product_config)
	{
		if (Emu.IsStopped())
		{
			return false;
		}
		const std::string title = Emu.GetTitleID();
		if (title.empty() || (!serial.empty() && serial != title))
		{
			return false;
		}

		armsx3::bridge::settings::layer_counts ignored;
		YAML::Node baseline;
		const std::unique_ptr<cfg_root> effective = layered_probe(title, running_db, product_config, ignored, baseline);
		std::unique_ptr<cfg_root> own;
		if (serial.empty())
		{
			own = layered_probe({}, {}, {}, ignored, baseline);
		}

		bool applied = false;
		for (const std::vector<std::string>& keys : all)
		{
			cfg::_base* const leaf = find_node(g_cfg, keys);
			if (leaf == nullptr || leaf->get_type() == cfg::type::node || !cfg_is_editable(leaf)
				|| !leaf->get_is_dynamic() || is_forced(product_config, keys))
			{
				continue;
			}
			const std::string value = find_node(*effective, keys)->to_string();
			if (own != nullptr && find_node(*own, keys)->to_string() != value)
			{
				continue;
			}
			if (leaf->from_string(value))
			{
				armsx3::bridge::settings::apply_side_effects(joined(keys));
				applied = true;
			}
		}
		return applied;
	}

	// yaml-cpp allows about 500 levels of recursion, which nobody has measured fits the GCD stack, so the pre-scan is the bound.
	constexpr std::size_t k_settings_size_cap = 256 * 1024;
	constexpr std::size_t k_settings_depth_limit = 16;

	// Openers are counted over the whole text and never counted down, because a quoted `]` could otherwise cancel a real `[`.
	bool readable(std::string_view text)
	{
		if (text.empty() || text.size() > k_settings_size_cap || text.find('\0') != std::string_view::npos)
		{
			return false;
		}

		std::size_t flow = 0;
		for (const char c : text)
		{
			// `\x7b` is an opening brace, spelled so that the guard's brace-matching parse of this body holds.
			if ((c == '[' || c == '\x7b') && ++flow > k_settings_depth_limit)
			{
				return false;
			}
		}

		std::vector<std::size_t> indents;
		std::size_t at = 0;
		while (at < text.size())
		{
			const std::size_t end = std::min(text.find('\n', at), text.size());
			const std::string_view line = text.substr(at, end - at);
			at = end + 1;

			std::size_t i = 0, dashes = 0;
			while (i < line.size() && (line[i] == ' ' || line[i] == '\t'))
			{
				i++;
			}
			const std::size_t column = i;
			while (i < line.size() && line[i] == '-' && (i + 1 == line.size() || line[i + 1] == ' '))
			{
				dashes++;
				for (i++; i < line.size() && line[i] == ' '; i++)
				{
				}
			}
			if (i < line.size() && line[i] != '#' && line[i] != '\r')
			{
				while (!indents.empty() && indents.back() > column)
				{
					indents.pop_back();
				}
				if (indents.empty() || indents.back() < column)
				{
					indents.push_back(column);
				}
				if (indents.size() + dashes > k_settings_depth_limit)
				{
					return false;
				}
			}
		}
		return true;
	}

	enum class verdict
	{
		kept,      // a real change
		unknown,   // no editable leaf in this build, a value it refuses, or a non-empty collection
		defaulted, // equal to the core's default
		locked,    // forced by the product file, or this install's identity (a random default)
	};

	struct judged_leaf
	{
		std::vector<std::string> keys;
		std::string value; // the leaf's own spelling; empty for unknown
		verdict kind;
	};

	struct judge_roots
	{
		cfg_root& scratch;        // where each value is parsed by the leaf's own parser
		const cfg_root* defaults; // Init's defaults, or null before Init has run
		const cfg_root& random_a; // as dump_layers: a leaf whose default differs between them is random
		const cfg_root& random_b;
		const YAML::Node& forced; // the product file
	};

	verdict classify(const YAML::Node& value, const std::vector<std::string>& keys, const judge_roots& roots, std::string& normal)
	{
		// normalised refuses a missing node, a section and a collection before any parse.
		if (!value.IsScalar() || normalised(roots.scratch, keys, value.Scalar(), normal) != outcome::ok)
		{
			return verdict::unknown;
		}
		std::string ignored;
		if (yaml_scalar(roots.forced, keys, 0, ignored) || random_default(roots.random_a, roots.random_b, keys))
		{
			return verdict::locked;
		}
		return normal == default_of(roots.defaults, *find_node(roots.scratch, keys), keys) ? verdict::defaulted : verdict::kept;
	}

	// Only an alias graph can pass the entry budget, and it is refused rather than walked.
	bool judge(const YAML::Node& map, std::vector<std::string>& keys, const judge_roots& roots, std::size_t& budget,
		std::vector<judged_leaf>& out)
	{
		if (keys.size() >= k_settings_depth_limit)
		{
			return false;
		}
		for (const auto& entry : map)
		{
			if (budget == 0)
			{
				return false;
			}
			budget--;

			keys.push_back(entry.first.IsScalar() ? entry.first.Scalar() : std::string{});
			const YAML::Node value = entry.second;
			const cfg::_base* const node = find_node(roots.scratch, keys);
			bool walked = true;
			if (value.IsMap() && (node == nullptr || node->get_type() == cfg::type::node))
			{
				walked = judge(value, keys, roots, budget, out);
			}
			else if (node != nullptr && node->get_type() != cfg::type::node && !cfg_is_editable(node))
			{
				if (value.size() != 0 || (value.IsScalar() && !value.Scalar().empty()))
				{
					out.push_back({keys, {}, verdict::unknown});
				}
			}
			else
			{
				std::string normal;
				const verdict kind = classify(value, keys, roots, normal);
				out.push_back({keys, kind == verdict::unknown ? std::string{} : normal, kind});
			}
			keys.pop_back();
			if (!walked)
			{
				return false;
			}
		}
		return true;
	}

	bool judge_file(const YAML::Node& root, std::size_t budget, const std::string& product_config, std::vector<judged_leaf>& out)
	{
		// On the heap: cfg_root is large, and this runs on the facade's GCD queue thread.
		const auto scratch = std::make_unique<cfg_root>();
		const auto random_a = std::make_unique<cfg_root>();
		const auto random_b = std::make_unique<cfg_root>();
		const std::unique_ptr<cfg_root> defaults = core_defaults();
		const YAML::Node forced = product_config.empty() ? YAML::Node() : load_yaml(read_text(product_config));
		std::vector<std::string> keys;
		return judge(root, keys, {*scratch, defaults.get(), *random_a, *random_b, forced}, budget, out);
	}

	// config.yml is written once, and only when a stored value changed.
	outcome merge_global(const std::vector<judged_leaf>& leaves, std::vector<std::vector<std::string>>& changed)
	{
		const std::string file = global_config_path();
		const auto probe = std::make_unique<cfg_root>();
		load_into(*probe, read_text(file));
		for (const judged_leaf& leaf : leaves)
		{
			const std::string before = find_node(*probe, leaf.keys)->to_string();
			if (parse_into(*probe, leaf.keys, leaf.value) == outcome::ok && find_node(*probe, leaf.keys)->to_string() != before)
			{
				changed.push_back(leaf.keys);
			}
		}
		if (changed.empty())
		{
			return outcome::ok;
		}
		return write_atomically(file, probe->to_string()) ? outcome::ok : outcome::write_failed;
	}

	// The preset wins; keys it does not name keep their values.
	outcome merge_game(const std::string& serial, const std::vector<judged_leaf>& leaves,
		std::vector<std::vector<std::string>>& changed)
	{
		const std::string file = rpcs3::utils::get_custom_config_path(serial);
		const YAML::Node loaded = load_yaml(read_text(file));
		YAML::Node root = loaded.IsMap() ? loaded : YAML::Node(YAML::NodeType::Map);
		const auto scratch = std::make_unique<cfg_root>();
		for (const judged_leaf& leaf : leaves)
		{
			std::string raw, stored;
			if (yaml_scalar(root, leaf.keys, 0, raw) && normalised(*scratch, leaf.keys, raw, stored) == outcome::ok
				&& stored == leaf.value)
			{
				continue;
			}
			assign(root, leaf.keys, 0, leaf.value);
			changed.push_back(leaf.keys);
		}
		return changed.empty() ? outcome::ok : write_game_file(file, root);
	}

	// Leaves nothing on iOS reads, so a row for them would change nothing.
	bool left_out_on_ios(const cfg::_base* leaf)
	{
		const cfg::_base* const unused[] = {
			&g_cfg.video.disable_swapchain_prerotation, // turns frames for a sideways Android panel; MoltenVK never turns one
			&g_cfg.video.vk.exclusive_fullscreen_mode,  // read only on Windows
			&g_cfg.misc.autoexit,                       // the iOS quit callback ignores it
			&g_cfg.misc.autopause,                      // desktop app only
			&g_cfg.misc.start_fullscreen,               // desktop app only
			&g_cfg.misc.start_big_picture_mode,         // desktop app only
			&g_cfg.misc.show_fatal_error_hints,         // desktop app only
			&g_cfg.misc.title_format,                   // iOS has no window title
			&g_cfg.misc.enable_gamemode,                // the iOS callback for it does nothing
			&g_cfg.net.ip_address,                      // read nowhere
			&g_cfg.io.load_sdl_mappings,                // SDL is not built for iOS
			&g_cfg.io.sdl_camera_id,                    // SDL is not built for iOS
		};
		return std::find(std::begin(unused), std::end(unused), leaf) != std::end(unused);
	}

	void walk(const cfg::node& node, std::vector<std::string>& keys, const dump_layers& layers, std::string& out, bool& first)
	{
		for (const cfg::_base* child : node.get_nodes())
		{
			if (!cfg_is_editable(child))
			{
				continue;
			}

			keys.push_back(child->get_name());
			if (child->get_type() == cfg::type::node)
			{
				walk(*static_cast<const cfg::node*>(child), keys, layers, out, first);
			}
			else if (left_out_on_ios(find_node(g_cfg, keys)))
			{
			}
			else
			{
				if (!first)
				{
					out += ',';
				}
				first = false;
				emit_leaf(*child, keys, layers, out);
			}
			keys.pop_back();
		}
	}
}

namespace armsx3::bridge::settings
{
	outcome dump(const std::string& serial, const std::string& product_config, const db_layer_view& db,
		std::string& out_json)
	{
		// On the heap: cfg_root is large, and this runs on the facade's GCD queue thread.
		const auto global = std::make_unique<cfg_root>();
		load_into(*global, read_text(global_config_path()));

		const std::unique_ptr<cfg_root> defaults = core_defaults();
		const auto random_a = std::make_unique<cfg_root>();
		const auto random_b = std::make_unique<cfg_root>();
		const auto scratch = std::make_unique<cfg_root>();

		const YAML::Node db_layer = serial.empty() ? YAML::Node() : load_yaml(db.entry_text);
		const YAML::Node game_layer = serial.empty() ? YAML::Node() : load_yaml(read_text(rpcs3::utils::get_custom_config_path(serial)));
		const YAML::Node forced_layer = product_config.empty() ? YAML::Node() : load_yaml(read_text(product_config));

		std::vector<std::string> fix = db.fix_paths;
		std::sort(fix.begin(), fix.end());

		const dump_layers layers{defaults.get(), *random_a, *random_b, db_layer, game_layer, forced_layer, *scratch, fix};

		std::string out = "{\"version\":1,\"leaves\":[";
		std::vector<std::string> keys;
		bool first = true;
		walk(*global, keys, layers, out, first);
		out += "]}";
		out_json = std::move(out);
		return outcome::ok;
	}

	outcome set(const std::string& serial, const std::string& path, const std::string& value,
		const std::string& product_config, const db_layer_view& running_db, bool& out_live)
	{
		out_live = false; // set by apply_live below

		const std::vector<std::string> keys = split_path(path);
		if (keys.empty())
		{
			return outcome::invalid;
		}
		if (is_forced(product_config, keys))
		{
			return outcome::locked;
		}

		const outcome written = serial.empty() ? set_global(keys, value) : set_game(serial, keys, value);
		if (written == outcome::ok)
		{
			out_live = apply_live(serial, {keys}, running_db, product_config);
		}
		return written;
	}

	outcome reset(const std::string& serial, const std::vector<std::string>& paths, const std::string& product_config,
		const db_layer_view& running_db, bool& out_live)
	{
		out_live = false;

		const auto probe = std::make_unique<cfg_root>();
		std::vector<std::vector<std::string>> all;
		for (const std::string& path : paths)
		{
			all.push_back(split_path(path));
			if (is_forced(product_config, all.back()))
			{
				return outcome::locked;
			}
			if (!names_editable_leaf(*probe, all.back()))
			{
				return outcome::invalid;
			}
		}

		if (serial.empty() && all.empty())
		{
			return outcome::invalid;
		}

		const outcome written = serial.empty() ? reset_global(*probe, all) : reset_game(serial, all);
		if (written == outcome::ok)
		{
			// A whole per-game file deleted: every leaf falls back to the layers beneath it.
			std::vector<std::vector<std::string>> applied = all;
			if (applied.empty())
			{
				std::vector<std::string> keys;
				editable_leaves(g_cfg, keys, applied);
			}
			out_live = apply_live(serial, applied, running_db, product_config);
		}
		return written;
	}

	// 0 when the kernel reports no limit, as on the Mac.
	static unsigned long long jetsam_limit_bytes()
	{
#if defined(__APPLE__)
		task_vm_info_data_t vm{};
		mach_msg_type_number_t n = TASK_VM_INFO_COUNT;
		if (task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&vm), &n) == KERN_SUCCESS && vm.limit_bytes_remaining != 0)
		{
			return vm.phys_footprint + vm.limit_bytes_remaining;
		}
#endif
		return 0;
	}

	std::string boot_text(const std::string& serial, const db_layer_view& db, const std::string& product_config,
		layer_counts& counts)
	{
		YAML::Node baseline;
		const std::unique_ptr<cfg_root> probe = layered_probe(serial, db, product_config, counts, baseline);

		// On a 4 GB-class device the boot compiles with one LLVM worker; this boot's copy only, so the player's value survives.
		counts.jetsam_limit = jetsam_limit_bytes();
		if (const unsigned n = armsx3::bridge::low_memory::llvm_threads_clamp(counts.jetsam_limit, probe->core.llvm_threads.get()))
		{
			probe->core.llvm_threads.set(n);
			counts.llvm_threads_clamped = n;
		}
		if (armsx3::bridge::low_memory::shader_interpreter_off(counts.jetsam_limit, probe->video.shadermode.get() == shader_mode::async_with_interpreter))
		{
			probe->video.shadermode.set(shader_mode::async_recompiler);
			counts.shader_interpreter_off = true;
		}
		if (armsx3::bridge::low_memory::early_cse_off(counts.jetsam_limit, probe->core.ppu_llvm_early_cse.get()))
		{
			probe->core.ppu_llvm_early_cse.set(false);
			counts.early_cse_off = true;
		}

		// Community-server redirects go after every layer, so the player's own entries stay first; this boot's copy only.
		const std::string swaps = armsx3::bridge::title_config::with_title_redirects(probe->net.swap_list.get(), serial, counts.redirects);
		if (counts.redirects != 0)
		{
			probe->net.swap_list.set(swaps);
		}

		YAML::Emitter out;
		out << diff(load_yaml(probe->to_string()), baseline);
		if (!out.good())
		{
			return {};
		}
		return std::string(out.c_str(), out.size());
	}

	outcome live_value(const std::string& path, std::string& out)
	{
		const std::vector<std::string> keys = split_path(path);
		if (!names_editable_leaf(g_cfg, keys))
		{
			return outcome::invalid;
		}
		out = find_node(g_cfg, keys)->to_string();
		return outcome::ok;
	}

	void apply_side_effects(const std::string& path)
	{
		// Not while stopped: overlay resets walk into RSX-owned state; not through CallFromMainThread, which livelocked a game on load.
		if (Emu.IsStopped())
		{
			return;
		}

		// Found by address in g_cfg, as live_id finds its four.
		const std::vector<std::string> keys = split_path(path);
		if (keys.empty())
		{
			return;
		}
		if (find_node(g_cfg, {keys[0]}) == &g_cfg.audio)
		{
			audio::configure_audio();
		}
		else if (keys.size() > 2 && find_node(g_cfg, {keys[0], keys[1]}) == &g_cfg.video.perf_overlay)
		{
			rsx::overlays::reset_performance_overlay();
		}
		else if (const cfg::_base* const leaf = find_node(g_cfg, keys);
			leaf == &g_cfg.video.debug_overlay || leaf == &g_cfg.io.pad_debug_overlay || leaf == &g_cfg.io.mouse_debug_overlay)
		{
			rsx::overlays::reset_debug_overlay();
		}
	}

	void apply_all_side_effects()
	{
		// Desktop's OnEmuSettingsChange minus configure_logs and display sleep, which have no iOS counterpart.
		if (Emu.IsStopped())
		{
			return;
		}
		audio::configure_audio();
		audio::configure_rsxaudio();
		rsx::overlays::reset_performance_overlay();
		rsx::overlays::reset_debug_overlay();
	}

	std::size_t save_running_title(const db_layer_view& db, const std::string& product_config)
	{
		if (Emu.IsStopped())
		{
			return 0;
		}
		const std::string title = Emu.GetTitleID();
		if (title.empty())
		{
			return 0;
		}

		layer_counts ignored;
		YAML::Node baseline;
		const std::unique_ptr<cfg_root> layered = layered_probe(title, db, product_config, ignored, baseline);
		const auto random_a = std::make_unique<cfg_root>();
		const auto random_b = std::make_unique<cfg_root>();
		const auto scratch = std::make_unique<cfg_root>();

		std::vector<std::vector<std::string>> all;
		std::vector<std::string> keys;
		editable_leaves(g_cfg, keys, all);

		const std::string file = rpcs3::utils::get_custom_config_path(title);
		const YAML::Node loaded = load_yaml(read_text(file));
		YAML::Node root = loaded.IsMap() ? loaded : YAML::Node(YAML::NodeType::Map);
		std::size_t saved = 0;
		for (const std::vector<std::string>& leaf_keys : all)
		{
			const cfg::_base* const running = find_node(g_cfg, leaf_keys);
			if (!running->get_is_dynamic() || is_forced(product_config, leaf_keys) || random_default(*random_a, *random_b, leaf_keys))
			{
				continue;
			}
			std::string normal;
			if (running->to_string() == find_node(*layered, leaf_keys)->to_string()
				|| normalised(*scratch, leaf_keys, running->to_string(), normal) != outcome::ok)
			{
				continue;
			}
			assign(root, leaf_keys, 0, normal);
			saved++;
		}
		if (saved != 0 && write_game_file(file, root) != outcome::ok)
		{
			return 0;
		}
		return saved;
	}

	outcome normalise(std::string_view text, const std::string& product_config, std::string& out_yaml, counts& c)
	{
		c = {};
		out_yaml.clear();
		if (!readable(text))
		{
			return outcome::unreadable;
		}
		auto [root, error] = yaml_load(std::string(text));
		if (!error.empty() || !root.IsMap())
		{
			return outcome::unreadable;
		}

		std::vector<judged_leaf> leaves;
		if (!judge_file(root, text.size(), product_config, leaves))
		{
			return outcome::unreadable;
		}

		YAML::Node changes(YAML::NodeType::Map);
		for (const judged_leaf& leaf : leaves)
		{
			switch (leaf.kind)
			{
			case verdict::kept: c.kept++; assign(changes, leaf.keys, 0, leaf.value); break;
			case verdict::unknown: c.unknown++; break;
			case verdict::defaulted: c.defaulted++; break;
			case verdict::locked: c.locked++; break;
			}
		}

		YAML::Emitter out;
		out << changes;
		if (!out.good())
		{
			return outcome::unreadable;
		}
		out_yaml = std::string(out.c_str(), out.size());
		return outcome::ok;
	}

	outcome apply(const std::string& serial, std::string_view text, const std::string& product_config,
		const db_layer_view& running_db, std::uint32_t& out_changed, bool& out_live)
	{
		out_changed = 0;
		out_live = false;
		if (!readable(text))
		{
			return outcome::unreadable;
		}
		auto [root, error] = yaml_load(std::string(text));
		if (!error.empty() || !root.IsMap())
		{
			return outcome::unreadable;
		}

		std::vector<judged_leaf> leaves;
		if (!judge_file(root, text.size(), product_config, leaves))
		{
			return outcome::unreadable;
		}

		// A value equal to the default is written too, since a preset may set a key back to it.
		std::vector<judged_leaf> writes;
		for (judged_leaf& leaf : leaves)
		{
			if (leaf.kind == verdict::kept || leaf.kind == verdict::defaulted)
			{
				writes.push_back(std::move(leaf));
			}
		}

		std::vector<std::vector<std::string>> changed;
		const outcome written = serial.empty() ? merge_global(writes, changed) : merge_game(serial, writes, changed);
		out_changed = static_cast<std::uint32_t>(changed.size());
		if (written == outcome::ok && !changed.empty())
		{
			out_live = apply_live(serial, changed, running_db, product_config);
		}
		return written;
	}

	outcome export_scope(const std::string& serial, const std::string& product_config, std::string& out_yaml)
	{
		out_yaml.clear();
		const std::string text = read_text(serial.empty() ? global_config_path() : rpcs3::utils::get_custom_config_path(serial));
		if (text.empty())
		{
			return outcome::ok; // nothing set in this scope
		}
		counts ignored;
		return normalise(text, product_config, out_yaml, ignored);
	}

	namespace
	{
		std::atomic<std::size_t> g_last_seed_count{0};
	}

	std::size_t seed_defaults(const std::vector<std::pair<std::string, std::string>>& path_values)
	{
		const std::string marker_file = fs::get_config_dir(true) + "armsx3-seeded.txt";
		std::string marker = read_text(marker_file);
		const std::string file = global_config_path();
		const auto probe = std::make_unique<cfg_root>();
		load_into(*probe, read_text(file));
		const std::unique_ptr<cfg_root> defaults = core_defaults();

		std::size_t seeded = 0;
		bool considered = false;
		for (const auto& [path, value] : path_values)
		{
			const std::vector<std::string> keys = split_path(path);
			// A listed path was seeded before and is the player's now.
			if (!names_editable_leaf(*probe, keys) || ("\n" + marker).find("\n" + path + "\n") != std::string::npos)
			{
				continue;
			}
			const cfg::_base& leaf = *find_node(*probe, keys);
			if (leaf.to_string() == default_of(defaults.get(), leaf, keys) && parse_into(*probe, keys, value) == outcome::ok)
			{
				seeded++;
			}
			marker += path + "\n";
			considered = true;
		}

		// config.yml first: a failed write marks nothing, so the next launch tries again.
		if (seeded > 0 && !write_atomically(file, probe->to_string()))
		{
			seeded = 0;
		}
		else if (considered)
		{
			write_atomically(marker_file, marker);
		}
		g_last_seed_count.store(seeded);
		return seeded;
	}

	std::size_t last_seed_count()
	{
		return g_last_seed_count.load();
	}
}
