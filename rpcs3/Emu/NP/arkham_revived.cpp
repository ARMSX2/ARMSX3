#include "stdafx.h"
#include "arkham_revived.h"

#include "Emu/VFS.h"
#include "Utilities/File.h"
#include "Utilities/StrUtil.h"

#include <span>

LOG_CHANNEL(arkham_log, "Arkham Revived");

namespace arkham_revived
{
	namespace
	{
		// Batman: Arkham Origins: disc USA (two editions), disc Europe, PSN USA
		constexpr std::string_view s_titles[] = {"BLUS31147", "BLUS31207", "BLES01784", "NPUB31261"};

		// Where update 1.06 keeps the multiplayer config, one COALESCED_<language>.BIN per language
		constexpr std::string_view s_config_dir = "/USRDIR/MULTIPLAYER/PATCH/BMGAME/COOKEDPS3/";

		constexpr std::string_view s_netvars_ini = "\\ps3-bmnetvars.ini";
		constexpr std::string_view s_section = "Default__GDHttp GDHttp";

		// The section their patch adds, key by key
		constexpr std::pair<std::string_view, std::string_view> s_settings[] =
		{
			{"TitleFileName", "dummy.json"},
			{"Settings", "(BaseURL=\"http://arkhamrevived.kiwifruitdev.com/\")"},
		};

		// The coalesced format: big-endian 32-bit counts. A string is a big-endian length, then its characters with
		// the terminator, UTF-16LE when the length is negative. Layout: files, each a name and its sections; each
		// section a name and its key/value strings.
		struct reader
		{
			std::span<const u8> data;
			usz pos = 0;
			bool failed = false;

			s32 read_s32()
			{
				if (failed || data.size() - pos < 4)
				{
					failed = true;
					return 0;
				}

				const u32 value = u32{data[pos]} << 24 | u32{data[pos + 1]} << 16 | u32{data[pos + 2]} << 8 | u32{data[pos + 3]};
				pos += 4;
				return static_cast<s32>(value);
			}

			struct string_bytes
			{
				std::span<const u8> bytes;
				bool wide = false; // UTF-16LE
			};

			// Empty when the string runs past the end
			string_bytes read_string()
			{
				const s64 length = read_s32();
				const usz size = static_cast<usz>(length < 0 ? -length * 2 : length);

				if (failed || data.size() - pos < size)
				{
					failed = true;
					return {};
				}

				const string_bytes s{data.subspan(pos, size), length < 0};
				pos += size;
				return s;
			}

			// ASCII names only, which is all this compares
			std::string read_name()
			{
				const auto [bytes, wide] = read_string();
				const usz step = wide ? 2 : 1;

				std::string out;
				for (usz i = 0; i + step <= bytes.size(); i += step)
				{
					const u32 c = wide ? (bytes[i] | u32{bytes[i + 1]} << 8) : bytes[i];
					if (!c)
						break;
					out += c < 0x80 ? static_cast<char>(c) : '?';
				}

				return out;
			}
		};

		void append_s32(std::vector<u8>& out, s32 value)
		{
			const u32 v = static_cast<u32>(value);
			out.insert(out.end(), {static_cast<u8>(v >> 24), static_cast<u8>(v >> 16), static_cast<u8>(v >> 8), static_cast<u8>(v)});
		}

		// UTF-16LE with the terminator, like every string in the game's own files
		void append_string(std::vector<u8>& out, std::string_view s)
		{
			append_s32(out, -static_cast<s32>(s.size() + 1));

			for (const char c : s)
			{
				out.insert(out.end(), {static_cast<u8>(c), u8{0}});
			}

			out.insert(out.end(), {u8{0}, u8{0}});
		}

		enum class edit_result
		{
			added,
			already_set,
			failed,
		};

		// The file with the server section first in PS3-BmNetVars.ini, where their patch has it. Everything else
		// stays byte for byte.
		edit_result add_server(std::span<const u8> data, std::vector<u8>& out, std::string& error)
		{
			reader r{data};
			std::optional<usz> count_pos;
			s32 count = 0;
			bool has_section = false;

			const s32 files = r.read_s32();

			for (s32 i = 0; i < files && !r.failed; i++)
			{
				const bool netvars = fmt::to_lower(r.read_name()).ends_with(s_netvars_ini);
				const usz sections_pos = r.pos;
				const s32 sections = r.read_s32();

				for (s32 j = 0; j < sections && !r.failed; j++)
				{
					if (r.read_name() == s_section && netvars)
					{
						has_section = true;
					}

					const s32 pairs = r.read_s32();

					for (s32 k = 0; k < pairs && !r.failed; k++)
					{
						r.read_string();
						r.read_string();
					}
				}

				if (netvars)
				{
					count_pos = sections_pos;
					count = sections;
				}
			}

			if (r.failed || files < 0 || r.pos != data.size())
			{
				error = "it is not in the expected format";
				return edit_result::failed;
			}

			if (!count_pos)
			{
				error = "it has no PS3-BmNetVars.ini";
				return edit_result::failed;
			}

			if (has_section)
			{
				return edit_result::already_set;
			}

			std::vector<u8> section;
			append_string(section, s_section);
			append_s32(section, static_cast<s32>(std::size(s_settings)));

			for (const auto& [key, value] : s_settings)
			{
				append_string(section, key);
				append_string(section, value);
			}

			const auto before = data.subspan(0, *count_pos);
			const auto after = data.subspan(*count_pos + 4);

			out.clear();
			out.reserve(data.size() + section.size());
			out.insert(out.end(), before.begin(), before.end());
			append_s32(out, count + 1);
			out.insert(out.end(), section.begin(), section.end());
			out.insert(out.end(), after.begin(), after.end());
			return edit_result::added;
		}
	}

	bool is_arkham_origins(std::string_view title_id)
	{
		return std::find(std::begin(s_titles), std::end(s_titles), title_id) != std::end(s_titles);
	}

	void prepare(const std::string& title_id)
	{
		if (!is_arkham_origins(title_id))
		{
			return;
		}

		std::string dir = vfs::get(fmt::format("/dev_hdd0/game/%s%s", title_id, s_config_dir));

		if (dir.empty() || !fs::is_dir(dir))
		{
			arkham_log.warning("Update 1.06 is not installed, so multiplayer cannot reach the Arkham: Revived server");
			return;
		}

		if (!dir.ends_with('/'))
		{
			dir += '/';
		}

		u32 total = 0;
		u32 ready = 0;
		u32 added = 0;

		for (const auto& entry : fs::dir(dir))
		{
			const std::string name = fmt::to_upper(entry.name);

			if (entry.is_directory || !name.starts_with("COALESCED_") || !name.ends_with(".BIN"))
			{
				continue;
			}

			total++;

			const std::string path = dir + entry.name;
			std::vector<u8> data;

			if (fs::file file{path})
			{
				data = file.to_vector<u8>();
			}
			else
			{
				arkham_log.error("Could not read %s (%s)", path, fs::g_tls_error);
				continue;
			}

			std::vector<u8> edited;
			std::string error;

			switch (add_server(data, edited, error))
			{
			case edit_result::already_set:
			{
				ready++;
				break;
			}
			case edit_result::failed:
			{
				arkham_log.error("Left %s alone: %s", entry.name, error);
				break;
			}
			case edit_result::added:
			{
				// The edited file has to read back with the section before it replaces the original
				std::vector<u8> again;

				if (add_server(edited, again, error) != edit_result::already_set)
				{
					arkham_log.error("Left %s alone: the edited file did not read back", entry.name);
					break;
				}

				fs::pending_file out(path);

				if (!out.file || out.file.write(edited.data(), edited.size()) != edited.size() || !out.commit())
				{
					arkham_log.error("Could not write %s (%s)", path, fs::g_tls_error);
					break;
				}

				arkham_log.success("Added the Arkham: Revived server to %s", entry.name);
				ready++;
				added++;
				break;
			}
			}
		}

		arkham_log.notice("Multiplayer config files that reach Arkham: Revived: %u of %u (%u edited now)", ready, total, added);
	}
}
