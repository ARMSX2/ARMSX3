// SPDX-License-Identifier: GPL-2.0-only
// Pad mappings stored as RPCS3's own input_configs files; the app applies them, since the core's virtual pad does not.
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace armsx3::bridge::input_config
{
	// The C ABI maps each to a status.
	enum class outcome
	{
		ok,
		invalid,      // a player outside 0..3, a key outside the list, or a reset of nothing
		refused,      // the value is too long, carries a control byte, or the core's node refused it
		write_failed, // the file could not be written or removed; the previous file is intact
		unreadable,   // the stored file is too large or the core's parser refused it; nothing was written
	};

	// `serial` empty is the global file; otherwise the title's file when it exists, else the global one, as cfg_input::load does.
	outcome dump(const std::string& serial, std::string& out_json);

	// A title's first write starts from a copy of the global file, because the core reads a title's file instead of it.
	outcome set(const std::string& serial, int player, const std::string& key, const std::string& value);

	// Every pair is checked first, so the keys land together or not at all; an empty list is invalid.
	outcome set_many(const std::string& serial, int player, const std::vector<std::pair<std::string, std::string>>& pairs);

	// `invalid` for a player or key outside the lists.
	outcome get(const std::string& serial, int player, const std::string& key, std::string& value);

	// Player -1 with a serial deletes the title's file; a reset keeps the three device keys.
	outcome reset(const std::string& serial, int player);
}
