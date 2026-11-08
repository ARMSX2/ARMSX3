// SPDX-License-Identifier: GPL-2.0-only
// Pad mappings edited in throwaway cfg_input copies and written atomically; cfg_input::load and save log the path, so neither is used.
#include "input_config_engine.h"

#include "Emu/Io/pad_config.h"
#include "Emu/system_utils.hpp"
#include "Utilities/File.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

namespace
{
	using armsx3::bridge::input_config::outcome;

	// cfg_pad's binding strings in declaration order, then the four stick numbers, then the three device keys.
	constexpr std::size_t k_binding_count = 25;
	constexpr std::array<const char*, k_binding_count + 4 + 3> k_keys = {
		"Left Stick Left", "Left Stick Down", "Left Stick Right", "Left Stick Up", "Right Stick Left",
		"Right Stick Down", "Right Stick Right", "Right Stick Up", "Start", "Select", "PS Button", "Square",
		"Cross", "Circle", "Triangle", "Left", "Down", "Right", "Up", "R1", "R2", "R3", "L1", "L2", "L3",
		"Left Stick Deadzone", "Right Stick Deadzone", "Left Stick Multiplier", "Right Stick Multiplier",
		"Device Class Type", "Vendor ID", "Product ID"};

	// Players 1-4 are exposed; ports 4-6 stay registered Virtual and unmapped.
	constexpr int k_players = 4;

	// A binding is a GameController element name; 64 bytes is a tuning cap.
	constexpr std::size_t k_value_cap = 64;

	// A tuning cap: a seven-player file the core writes is a few KiB.
	constexpr std::uint64_t k_file_cap = 256 * 1024;

	// Exact bytes, no case folding.
	std::size_t key_index(const std::string& key)
	{
		std::size_t index = 0;
		while (index < k_keys.size() && key != k_keys[index])
		{
			index++;
		}
		return index;
	}

	cfg::_base* pad_node(const cfg_pad& pad, const char* name)
	{
		for (cfg::_base* child : pad.get_nodes())
		{
			if (child->get_name() == name)
			{
				return child;
			}
		}
		return nullptr;
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

	std::string global_path()
	{
		return rpcs3::utils::get_input_config_dir("") + "Default.yml";
	}

	std::string path_for(const std::string& serial, bool& per_game)
	{
		per_game = false;
		if (!serial.empty())
		{
			const std::string title = rpcs3::utils::get_custom_input_config_path(serial);
			if (fs::is_file(title))
			{
				per_game = true;
				return title;
			}
		}
		return global_path();
	}

	// A title's file replaces the global one entirely when the core loads it, so its first write starts from a copy.
	void layer_for_write(const std::string& serial, std::string& from, std::string& to)
	{
		to = serial.empty() ? global_path() : rpcs3::utils::get_custom_input_config_path(serial);
		from = fs::is_file(to) ? to : global_path();
	}

	// An absent file is the defaults; a stored file is untrusted text, since Backup & Restore copies it back.
	outcome load_into(cfg_input& input, const std::string& path)
	{
		input.from_default();
		const fs::file file{path};
		if (!file)
		{
			return fs::is_file(path) ? outcome::unreadable : outcome::ok;
		}
		if (file.size() > k_file_cap)
		{
			return outcome::unreadable;
		}
		const std::string text = file.to_string();
		if (!text.empty() && !input.from_string(text))
		{
			return outcome::unreadable;
		}
		return outcome::ok;
	}

	// By the handler's printed name: the virtual pad's registration lives in the C ABI, this only names it.
	void force_virtual(cfg_input& input)
	{
		for (cfg_player* player : input.player)
		{
			player->handler.from_string("Virtual");
			player->device.from_string("Virtual");
		}
	}

	outcome save_to(const std::string& path, cfg_input& input)
	{
		if (!fs::create_path(fs::get_parent_dir(path)))
		{
			return outcome::write_failed;
		}
		force_virtual(input);
		return write_atomically(path, input.to_string()) ? outcome::ok : outcome::write_failed;
	}
}

namespace armsx3::bridge::input_config
{
	outcome dump(const std::string& serial, std::string& out_json)
	{
		bool per_game = false;
		cfg_input input;
		const outcome loaded = load_into(input, path_for(serial, per_game));
		if (loaded != outcome::ok)
		{
			return loaded;
		}

		std::string json = per_game ? "{\"perGame\":1,\"players\":[" : "{\"perGame\":0,\"players\":[";
		for (int player = 0; player < k_players; player++)
		{
			json += player == 0 ? "{" : ",{";
			for (std::size_t index = 0; index < k_keys.size(); index++)
			{
				const cfg::_base* node = pad_node(input.player[player]->config, k_keys[index]);
				json += index == 0 ? "" : ",";
				json_append_escaped(json, k_keys[index]);
				json += ':';
				if (index < k_binding_count)
				{
					json_append_escaped(json, node != nullptr ? node->to_string() : std::string{});
				}
				else
				{
					json += node != nullptr ? node->to_string() : std::string{"0"};
				}
			}
			json += '}';
		}
		json += "]}";
		out_json = std::move(json);
		return outcome::ok;
	}

	outcome set(const std::string& serial, int player, const std::string& key, const std::string& value)
	{
		return set_many(serial, player, {{key, value}});
	}

	// All pairs are checked first and saved once, so a device's class, vendor and product land together or not at all.
	outcome set_many(const std::string& serial, int player, const std::vector<std::pair<std::string, std::string>>& pairs)
	{
		if (player < 0 || player >= k_players || pairs.empty())
		{
			return outcome::invalid;
		}
		for (const auto& [key, value] : pairs)
		{
			const std::size_t index = key_index(key);
			if (index >= k_keys.size())
			{
				return outcome::invalid;
			}
			if (index < k_binding_count)
			{
				if (value.size() > k_value_cap)
				{
					return outcome::refused;
				}
				for (const char c : value)
				{
					if (static_cast<unsigned char>(c) < 0x20 || c == 0x7f)
					{
						return outcome::refused;
					}
				}
			}
		}

		std::string from, to;
		layer_for_write(serial, from, to);
		cfg_input input;
		const outcome loaded = load_into(input, from);
		if (loaded != outcome::ok)
		{
			return loaded;
		}

		for (const auto& [key, value] : pairs)
		{
			cfg::_base* node = pad_node(input.player[player]->config, k_keys[key_index(key)]);
			if (node == nullptr || !node->from_string(value))
			{
				return outcome::refused;
			}
		}
		return save_to(to, input);
	}

	outcome get(const std::string& serial, int player, const std::string& key, std::string& value)
	{
		const std::size_t index = key_index(key);
		if (player < 0 || player >= k_players || index >= k_keys.size())
		{
			return outcome::invalid;
		}
		bool per_game = false;
		cfg_input input;
		const outcome loaded = load_into(input, path_for(serial, per_game));
		if (loaded != outcome::ok)
		{
			return loaded;
		}
		const cfg::_base* node = pad_node(input.player[player]->config, k_keys[index]);
		if (node == nullptr)
		{
			return outcome::invalid;
		}
		value = node->to_string();
		return outcome::ok;
	}

	outcome reset(const std::string& serial, int player)
	{
		// Already absent is done.
		if (player == -1)
		{
			if (serial.empty())
			{
				return outcome::invalid;
			}
			const std::string title = rpcs3::utils::get_custom_input_config_path(serial);
			return fs::remove_file(title) || !fs::is_file(title) ? outcome::ok : outcome::write_failed;
		}

		if (player < 0 || player >= k_players)
		{
			return outcome::invalid;
		}

		std::string from, to;
		layer_for_write(serial, from, to);
		cfg_input input;
		const outcome loaded = load_into(input, from);
		if (loaded != outcome::ok)
		{
			return loaded;
		}

		// A reset puts back the buttons and sticks only; the three device keys keep the player's choice.
		cfg_pad& pad = input.player[player]->config;
		const auto device_class = pad.device_class_type.get();
		const auto vendor = pad.vendor_id.get();
		const auto product = pad.product_id.get();
		pad.from_default();
		pad.device_class_type.set(device_class);
		pad.vendor_id.set(vendor);
		pad.product_id.set(product);
		return save_to(to, input);
	}
}
