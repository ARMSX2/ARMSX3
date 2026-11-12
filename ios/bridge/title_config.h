// SPDX-License-Identifier: GPL-2.0-only
// One title's config-database entry merged into the product override, which the core would otherwise never apply; standard library only.
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace armsx3::bridge::title_config
{
	// The serial comes from the title's own PARAM.SFO, so it is untrusted and checked before it is searched.
	inline bool valid_serial(std::string_view s)
	{
		if (s.size() != 9)
		{
			return false;
		}

		for (std::size_t i = 0; i < 9; i++)
		{
			const char c = s[i];
			if (i < 4 ? (c < 'A' || c > 'Z') : (c < '0' || c > '9'))
			{
				return false;
			}
		}

		return true;
	}

	namespace detail
	{
		inline void skip_ws(std::string_view s, std::size_t& p)
		{
			while (p < s.size() && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n' || s[p] == '\r'))
			{
				p++;
			}
		}

		inline void put_utf8(std::string& out, std::uint32_t cp)
		{
			if (cp < 0x80)
			{
				out += static_cast<char>(cp);
			}
			else if (cp < 0x800)
			{
				out += static_cast<char>(0xC0 | (cp >> 6));
				out += static_cast<char>(0x80 | (cp & 0x3F));
			}
			else if (cp < 0x10000)
			{
				out += static_cast<char>(0xE0 | (cp >> 12));
				out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
				out += static_cast<char>(0x80 | (cp & 0x3F));
			}
			else
			{
				out += static_cast<char>(0xF0 | (cp >> 18));
				out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
				out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
				out += static_cast<char>(0x80 | (cp & 0x3F));
			}
		}

		inline bool hex4(std::string_view s, std::size_t p, std::uint32_t& v)
		{
			if (p + 4 > s.size())
			{
				return false;
			}

			v = 0;
			for (std::size_t i = p; i < p + 4; i++)
			{
				const char c = s[i];
				v <<= 4;
				if (c >= '0' && c <= '9') v |= static_cast<std::uint32_t>(c - '0');
				else if (c >= 'a' && c <= 'f') v |= static_cast<std::uint32_t>(c - 'a' + 10);
				else if (c >= 'A' && c <= 'F') v |= static_cast<std::uint32_t>(c - 'A' + 10);
				else return false;
			}

			return true;
		}

		// On success p is just past the closing quote.
		inline bool read_string(std::string_view s, std::size_t& p, std::string& out)
		{
			if (p >= s.size() || s[p] != '"')
			{
				return false;
			}

			out.clear();
			for (p++; p < s.size(); p++)
			{
				const char c = s[p];
				if (c == '"')
				{
					p++;
					return true;
				}

				if (c != '\\')
				{
					out += c;
					continue;
				}

				if (++p >= s.size())
				{
					return false;
				}

				switch (s[p])
				{
				case '"': out += '"'; break;
				case '\\': out += '\\'; break;
				case '/': out += '/'; break;
				case 'b': out += '\b'; break;
				case 'f': out += '\f'; break;
				case 'n': out += '\n'; break;
				case 'r': out += '\r'; break;
				case 't': out += '\t'; break;
				case 'u':
				{
					std::uint32_t cp = 0;
					if (!hex4(s, p + 1, cp))
					{
						return false;
					}
					p += 4;

					if (cp >= 0xD800 && cp <= 0xDBFF)
					{
						std::uint32_t lo = 0;
						if (p + 2 >= s.size() || s[p + 1] != '\\' || s[p + 2] != 'u' || !hex4(s, p + 3, lo) || lo < 0xDC00 || lo > 0xDFFF)
						{
							return false;
						}
						p += 6;
						cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
					}

					put_utf8(out, cp);
					break;
				}
				default: return false;
				}
			}

			return false;
		}
	}

	// A serial matches only as a key followed by `:` and `{`, so a serial quoted in another title's text cannot match.
	inline std::string lookup(std::string_view db, std::string_view serial)
	{
		if (!valid_serial(serial))
		{
			return {};
		}

		const std::string needle = '"' + std::string(serial) + '"';

		for (std::size_t at = db.find(needle); at != std::string_view::npos; at = db.find(needle, at + 1))
		{
			if (at > 0 && db[at - 1] == '\\')
			{
				continue;
			}

			std::size_t p = at + needle.size();
			detail::skip_ws(db, p);
			if (p >= db.size() || db[p] != ':')
			{
				continue;
			}
			p++;
			detail::skip_ws(db, p);
			if (p >= db.size() || db[p] != '{')
			{
				continue;
			}
			p++;

			std::string key, value;
			while (true)
			{
				detail::skip_ws(db, p);
				if (!detail::read_string(db, p, key))
				{
					return {};
				}
				detail::skip_ws(db, p);
				if (p >= db.size() || db[p] != ':')
				{
					return {};
				}
				p++;
				detail::skip_ws(db, p);
				if (!detail::read_string(db, p, value))
				{
					return {};
				}
				if (key == "config")
				{
					return value;
				}
				detail::skip_ws(db, p);
				if (p >= db.size() || db[p] != ',')
				{
					return {};
				}
				p++;
			}
		}

		return {};
	}

	struct item
	{
		enum class kind { scalar, list, block } k = kind::scalar;
		std::string key;
		std::string value;                // kind::scalar
		std::vector<std::string> entries; // kind::list
		std::vector<item> children;       // kind::block
	};

	namespace detail
	{
		struct line
		{
			std::size_t indent;
			std::string body;
		};

		inline bool split_lines(std::string_view text, std::vector<line>& out)
		{
			std::size_t p = 0;
			while (p <= text.size())
			{
				std::size_t e = text.find('\n', p);
				if (e == std::string_view::npos)
				{
					e = text.size();
				}

				std::string_view l = text.substr(p, e - p);
				if (!l.empty() && l.back() == '\r')
				{
					l.remove_suffix(1);
				}

				std::size_t indent = 0;
				while (indent < l.size() && l[indent] == ' ')
				{
					indent++;
				}

				std::string_view body = l.substr(indent);
				while (!body.empty() && body.back() == ' ')
				{
					body.remove_suffix(1);
				}

				if (!body.empty())
				{
					// Refusing everything outside the subset keeps a misread entry from being half-applied.
					if (body.find('\t') != std::string_view::npos || body[0] == '#' || body[0] == '{' || body[0] == '[' ||
						body[0] == '&' || body[0] == '*' || body[0] == '!' || body[0] == '|' || body[0] == '>' || body == "-")
					{
						return false;
					}

					out.push_back({indent, std::string(body)});
				}

				if (e == text.size())
				{
					break;
				}
				p = e + 1;
			}

			return true;
		}

		inline bool parse_items(const std::vector<line>& ls, std::size_t& i, long parent_indent, std::vector<item>& out)
		{
			long indent = -1;

			while (i < ls.size() && static_cast<long>(ls[i].indent) > parent_indent)
			{
				const line& l = ls[i];
				if (indent < 0)
				{
					indent = static_cast<long>(l.indent);
				}

				if (static_cast<long>(l.indent) != indent || l.body.rfind("- ", 0) == 0)
				{
					return false;
				}

				item it;
				const std::size_t colon = l.body.find(": ");

				if (colon == std::string::npos && l.body.back() == ':')
				{
					it.key = l.body.substr(0, l.body.size() - 1);
					i++;

					if (i < ls.size() && static_cast<long>(ls[i].indent) > indent && ls[i].body.rfind("- ", 0) == 0)
					{
						it.k = item::kind::list;
						const std::size_t list_indent = ls[i].indent;
						while (i < ls.size() && ls[i].indent == list_indent && ls[i].body.rfind("- ", 0) == 0)
						{
							it.entries.push_back(ls[i].body.substr(2));
							i++;
						}
					}
					else
					{
						it.k = item::kind::block;
						if (!parse_items(ls, i, indent, it.children) || it.children.empty())
						{
							return false;
						}
					}
				}
				else if (colon != std::string::npos && colon > 0 && l.body.find(": ", colon + 2) == std::string::npos)
				{
					it.key = l.body.substr(0, colon);
					it.value = l.body.substr(colon + 2);
					i++;
				}
				else
				{
					return false;
				}

				if (it.key.empty() || it.key.find(':') != std::string::npos)
				{
					return false;
				}

				out.push_back(std::move(it));
			}

			return true;
		}

		inline item* find(std::vector<item>& items, const std::string& key)
		{
			for (item& it : items)
			{
				if (it.key == key)
				{
					return &it;
				}
			}

			return nullptr;
		}

		inline void emit(const std::vector<item>& items, std::size_t depth, std::string& out)
		{
			const std::string pad(depth * 2, ' ');
			for (const item& it : items)
			{
				switch (it.k)
				{
				case item::kind::scalar:
					out += pad + it.key + ": " + it.value + "\n";
					break;
				case item::kind::list:
					out += pad + it.key + ":\n";
					for (const std::string& e : it.entries)
					{
						out += pad + "  - " + e + "\n";
					}
					break;
				case item::kind::block:
					out += pad + it.key + ":\n";
					emit(it.children, depth + 1, out);
					break;
				}
			}
		}
	}

	inline bool parse(std::string_view text, std::vector<item>& out)
	{
		std::vector<detail::line> ls;
		std::size_t i = 0;
		return detail::split_lines(text, ls) && detail::parse_items(ls, i, -1, out) && i == ls.size();
	}

	inline std::string emit(const std::vector<item>& items)
	{
		std::string out;
		detail::emit(items, 0, out);
		return out;
	}

	// Answered from the core's own config tree, so a key it lacks or a value it would refuse never reaches the override.
	using accepts_fn = std::function<bool(const std::vector<std::string>& path, const item& leaf)>;

	// Ported from the Android app's deny lists; both measurements are Android's, not an iPhone's.
	inline bool denied_on_ios(const item& leaf)
	{
		if (leaf.key == "Max SPURS Threads")
		{
			return true;
		}
		return leaf.key == "Frame limit" && leaf.k == item::kind::scalar && (leaf.value == "Off" || leaf.value == "Infinite");
	}

	namespace detail
	{
		inline void merge_into(std::vector<item>& into, const std::vector<item>& from, const accepts_fn& accepts,
			std::vector<std::string>& path, std::size_t& added)
		{
			for (const item& f : from)
			{
				path.push_back(f.key);
				item* existing = find(into, f.key);

				if (f.k == item::kind::block)
				{
					if (existing == nullptr)
					{
						item fresh;
						fresh.k = item::kind::block;
						fresh.key = f.key;
						merge_into(fresh.children, f.children, accepts, path, added);
						if (!fresh.children.empty())
						{
							into.push_back(std::move(fresh));
						}
					}
					else if (existing->k == item::kind::block)
					{
						merge_into(existing->children, f.children, accepts, path, added);
					}
				}
				else if (existing == nullptr && !denied_on_ios(f) && accepts(path, f))
				{
					// The product wins: a key it already sets never reaches this branch.
					into.push_back(f);
					added++;
				}

				path.pop_back();
			}
		}
	}

	namespace detail
	{
		inline void leaf_paths(const std::vector<item>& items, const std::string& prefix, std::vector<std::string>& out)
		{
			for (const item& it : items)
			{
				const std::string path = prefix.empty() ? it.key : prefix + "@@" + it.key;
				if (it.k == item::kind::block)
				{
					leaf_paths(it.children, path, out);
				}
				else
				{
					out.push_back(path);
				}
			}
		}
	}

	// Each entry goes through the boot's own subset parse, so an entry this file cannot read contributes nothing.
	inline std::vector<std::string> fix_paths(std::string_view db)
	{
		std::vector<std::string> out;
		constexpr std::string_view needle = "\"config\"";
		for (std::size_t at = db.find(needle); at != std::string_view::npos; at = db.find(needle, at + 1))
		{
			if (at > 0 && db[at - 1] == '\\')
			{
				continue;
			}

			std::size_t p = at + needle.size();
			detail::skip_ws(db, p);
			if (p >= db.size() || db[p] != ':')
			{
				continue;
			}
			p++;
			detail::skip_ws(db, p);

			std::string text;
			std::vector<item> items;
			if (!detail::read_string(db, p, text))
			{
				continue;
			}
			if (!parse(text, items))
			{
				continue;
			}
			detail::leaf_paths(items, {}, out);
		}

		std::sort(out.begin(), out.end());
		out.erase(std::unique(out.begin(), out.end()), out.end());
		return out;
	}

	struct merged
	{
		std::string text;   // the override to boot with; the product text unchanged when added == 0
		std::size_t added = 0;
	};

	// On any parse failure, or with nothing accepted, the product text comes back byte-identical and `added` is 0.
	inline merged merge(std::string_view product, std::string_view entry, const accepts_fn& accepts)
	{
		merged result{std::string(product), 0};

		std::vector<item> base, extra;
		if (entry.empty() || !parse(product, base) || !parse(entry, extra))
		{
			return result;
		}

		std::vector<std::string> path;
		detail::merge_into(base, extra, accepts, path, result.added);

		if (result.added != 0)
		{
			result.text = emit(base);
		}

		return result;
	}

	// Ported from the Android app's LOCAL_OVERRIDES and applied whether or not the database was fetched; tests hold it equal to Android's.
	struct local_override
	{
		std::string_view serial;
		std::string_view config;
	};

	inline constexpr local_override local_overrides[] = {
		// PS3 Native is the only limit mode that honours the game's vsync flip mode, so battles stop running at double speed.
		{"BLUS31213", "Video:\n  Frame limit: PS3 Native\n"},
		{"BLES01935", "Video:\n  Frame limit: PS3 Native\n"},

		// Battles hold 60 here; tied to the bundled "Field at 30 fps with a 60 fps limit" patch.
		{"BLUS30903", "Video:\n  Frame limit: 60\n"},

		// At Low a battle draws black frames.
		{"BLUS31519", "Video:\n  Shader Precision: High\n"},
		{"BLES02136", "Video:\n  Shader Precision: High\n"},
		{"BLJS10257", "Video:\n  Shader Precision: High\n"},

		// Carried for parity: on iOS the product file sets the decoders and wins over every layer.
		{"NPUA80930", "Core:\n  PPU Decoder: Recompiler (LLVM)\n"},

		// Carried for parity, like the row above.
		{"NPUB31590", "Core:\n  PPU Decoder: Recompiler (LLVM)\n"},

		// Tied to the bundled "Disable MLAA" patch; SPU threads at Auto avoid a prologue freeze that a cap exposes.
		{"BCUS98234", "Video:\n  Write Color Buffers: false\nCore:\n  Preferred SPU Threads: 0\n"},
		{"BCES01007", "Video:\n  Write Color Buffers: false\nCore:\n  Preferred SPU Threads: 0\n"},

		// On, the SPURS control-block atomics take vm::writer_lock about 550,000 times a second; off-spec, so this title only.
		{"BLUS30218", "Core:\n  Accurate SPU Reservations: false\n"},

		// Same reason as Web of Shadows: the SPURS control-block stores stall on vm::writer_lock.
		{"BLUS31202", "Core:\n  Accurate SPU Reservations: false\n"},
		{"BLES01885", "Core:\n  Accurate SPU Reservations: false\n"},
		{"BLES01886", "Core:\n  Accurate SPU Reservations: false\n"},
		{"BLUS30778", "Core:\n  Accurate SPU Reservations: false\n"},
		{"BLES01329", "Core:\n  Accurate SPU Reservations: false\n"},
		{"BLES01330", "Core:\n  Accurate SPU Reservations: false\n"},

		// Building the saved SPU list at startup freezes every boot after the first.
		{"BLUS30833", "Core:\n  SPU Cache: false\n"},
		{"BLES01395", "Core:\n  SPU Cache: false\n"},

		// Tied to the bundled Approximate collision fix patch, which keeps Niko from falling through the world.
		{"BLUS30682", "Core:\n  SPU XFloat Accuracy: Approximate\n"},
		{"BLUS30127", "Core:\n  SPU XFloat Accuracy: Approximate\n"},
		{"NPUB30702", "Core:\n  SPU XFloat Accuracy: Approximate\n"},

		// Holds the woken PPU back so the SPU saves a task's context before it is freed.
		{"BLUS30490", "Core:\n  PPU Event Wake-Up Delay: 35\n"},
		{"BLES00875", "Core:\n  PPU Event Wake-Up Delay: 35\n"},
		{"BLJM60180", "Core:\n  PPU Event Wake-Up Delay: 35\n"},
		{"BLUD80019", "Core:\n  PPU Event Wake-Up Delay: 35\n"},

		// Tied to the bundled raw SPU park fix; this lowers the VRAM cap from the iOS default for this title.
		{"BLUS30203", "Core:\n  SPU MMIO Delay Enabled: true\n  SPU MMIO Delay: 300\n  Pause Game During SPU Compilation: true\nVideo:\n  Vulkan:\n    VRAM allocation limit (MB): 3072\n"},
		{"BLES00397", "Core:\n  SPU MMIO Delay Enabled: true\n  SPU MMIO Delay: 300\n  Pause Game During SPU Compilation: true\nVideo:\n  Vulkan:\n    VRAM allocation limit (MB): 3072\n"},

		// Without it the RSDX job system stops an SPU with STOP 0 after a while.
		{"BLUS30612", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"BLES01236", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPEB00813", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPEB90337", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPEB90343", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPHB00369", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPHB00446", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPUB90567", "Core:\n  MFC Commands Shuffling Limit: 1\n"},
		{"NPUB90604", "Core:\n  MFC Commands Shuffling Limit: 1\n"},

		// Its graphics SPU runs better on whole-module blocks.
		{"NPUB30828", "Core:\n  SPU Block Size: Mega\n"},
		{"NPEB01054", "Core:\n  SPU Block Size: Mega\n"},

		// A PPU trap skips 20 instructions instead of stopping the game; MRTC00003 is the US disc's title ID.
		{"MRTC00003", "Core:\n  SPU Block Size: Mega\n  Stub PPU Traps: 20\n"},
		{"BLUS30416", "Core:\n  SPU Block Size: Mega\n  Stub PPU Traps: 20\n"},
		{"BLES00783", "Core:\n  SPU Block Size: Mega\n  Stub PPU Traps: 20\n"},
		{"BLJM67005", "Core:\n  SPU Block Size: Mega\n  Stub PPU Traps: 20\n"},
		{"BCAS25005", "Core:\n  SPU Block Size: Mega\n  Stub PPU Traps: 20\n"},

		// Relaxed XFloat clears the graphical errors in its cutscenes.
		{"BCUS98111", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"BCES00510", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"BCES00799", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"BCAS25003", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"BCKS15003", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"BCJS37001", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},
		{"NPUA70080", "Core:\n  SPU XFloat Accuracy: Relaxed\n"},

		// The iOS product file pins the scheduler to the OS, so only GETLLAR 0 applies here; it lets a polling SPU wait in the OS.
		{"BCES00052", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\nVideo:\n  Vulkan:\n    Vertex Fetch Specialization: true\n    Early Submit Draw Threshold: 1024\n"},
		{"BCUS98127", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\nVideo:\n  Vulkan:\n    Vertex Fetch Specialization: true\n    Early Submit Draw Threshold: 1024\n"},
		{"BCJS30014", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\nVideo:\n  Vulkan:\n    Vertex Fetch Specialization: true\n    Early Submit Draw Threshold: 1024\n"},
		{"NPEA00452", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\nVideo:\n  Vulkan:\n    Vertex Fetch Specialization: true\n    Early Submit Draw Threshold: 1024\n"},
		{"NPUA80965", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\nVideo:\n  Vulkan:\n    Vertex Fetch Specialization: true\n    Early Submit Draw Threshold: 1024\n"},
		{"BCES00301", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"BCAS20052", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPEA00088", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPUA80145", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPEA00106", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPJA00033", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"BCES01908", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"BCES01949", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"BCUS99245", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPEA00457", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
		{"NPUA80908", "Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  SPU GETLLAR Busy Waiting Percentage: 0\n"},
	};

	// iOS-only rows, kept apart so the table above stays Android's entry for entry; tests check the two do not overlap.
	inline constexpr local_override ios_only_overrides[] = {
		// Its SPU loop polls the decrementer, and loop detection lets it yield; per title because the global setting hurts other games.
		{"NPUB30558", "Core:\n  SPU loop detection: true\n"},

		// The shader interpreter draws this game's intro wrong until pipelines compile.
		{"BLUS30837", "Video:\n  Shader Mode: Async Recompiler (multi-threaded)\n"},
		{"BLES01613", "Video:\n  Shader Mode: Async Recompiler (multi-threaded)\n"},

		// A first launch crashes while an SPU stalls in an on-demand compile; pausing the game during compiles hides that stall.
		{"BLUS30405", "Core:\n  Pause Game During SPU Compilation: true\n"},
		{"BLES00713", "Core:\n  Pause Game During SPU Compilation: true\n  SPU Poll Park Sites: 3f47c:40801083\n"},
		{"BLES00714", "Core:\n  Pause Game During SPU Compilation: true\n"},

		// Exact ZCULL stats make the torches flicker on iPhone; approximate stats stop it.
		{"BLES00158", "Video:\n  Accurate ZCULL stats: false\n  Relaxed ZCULL Sync: false\n"},
		{"BLUS30089", "Video:\n  Accurate ZCULL stats: false\n  Relaxed ZCULL Sync: false\n"},

		// The database sets Low precision for AMD cards, which turns this game's textures black on iPhone.
		{"BLUS30065", "Video:\n  Shader Precision: High\n"},
		{"BLES00142", "Video:\n  Shader Precision: High\n"},

		// At 0 a PPU trap stops the game; at 1 the trapping instruction is skipped.
		{"BCES01176", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCJS37004", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCAS25009", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCUS99086", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCES01670", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCES01692", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCAS25014", "Core:\n  Stub PPU Traps: 1\n"},
		{"NPUA80858", "Core:\n  Stub PPU Traps: 1\n"},
		{"NPEA00422", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCUS98232", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCES01741", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCES01742", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCAS25016", "Core:\n  Stub PPU Traps: 1\n"},
		{"BCJS37008", "Core:\n  Stub PPU Traps: 1\n"},
		{"NPUA80918", "Core:\n  Stub PPU Traps: 1\n"},
		{"NPEA00445", "Core:\n  Stub PPU Traps: 1\n"},
		{"NPHA80258", "Core:\n  Stub PPU Traps: 1\n"},
	};

	// Empty when neither table has the serial.
	inline std::string_view local_override_for(std::string_view serial)
	{
		for (const local_override& o : local_overrides)
		{
			if (o.serial == serial)
			{
				return o.config;
			}
		}
		for (const local_override& o : ios_only_overrides)
		{
			if (o.serial == serial)
			{
				return o.config;
			}
		}

		return {};
	}

	// merge keeps a key that is already there, so where both set one, ours wins.
	inline merged title_layer(std::string_view entry, std::string_view serial, const accepts_fn& accepts)
	{
		const merged ours = merge("", local_override_for(serial), accepts);
		merged all = merge(ours.text, entry, accepts);
		all.added += ours.added;
		return all;
	}

	// Demon's Souls' official servers are gone; these redirect its DNS to The Archstones for one boot's copy only.
	struct title_redirect
	{
		std::string_view host;
		std::string_view address;
	};

	struct title_redirects
	{
		std::string_view serial;
		std::span<const title_redirect> hosts;
	};

	inline constexpr std::string_view k_archstones = "206.189.232.242";
	inline constexpr title_redirect k_demons_souls_redirects[] = {
		{"ds-eu-c.scej-online.jp", k_archstones},
		{"ds-eu-g.scej-online.jp", k_archstones},
		{"c.demons-souls.com", k_archstones},
		{"g.demons-souls.com", k_archstones},
		{"cmnap.scej-online.jp", k_archstones},
		{"demons-souls.scej-online.jp", k_archstones},
	};

	// Discs (US, EU, JP, Asia), then PSN (US, EU, JP).
	inline constexpr title_redirects k_title_redirects[] = {
		{"BLUS30443", k_demons_souls_redirects},
		{"BLES00932", k_demons_souls_redirects},
		{"BCJS30022", k_demons_souls_redirects},
		{"BCAS20071", k_demons_souls_redirects},
		{"NPUB30910", k_demons_souls_redirects},
		{"NPEB01202", k_demons_souls_redirects},
		{"NPJA00102", k_demons_souls_redirects},
	};

	namespace detail
	{
		// Kotlin's String.trim() for the ASCII whitespace a typed list can carry.
		inline std::string_view trim(std::string_view s)
		{
			const std::size_t first = s.find_first_not_of(" \t\r\n\f\v");
			if (first == std::string_view::npos)
			{
				return {};
			}
			return s.substr(first, s.find_last_not_of(" \t\r\n\f\v") - first + 1);
		}

		inline std::string ascii_lower(std::string_view s)
		{
			std::string out(s);
			for (char& c : out)
			{
				if (c >= 'A' && c <= 'Z')
				{
					c = static_cast<char>(c - 'A' + 'a');
				}
			}
			return out;
		}
	}

	// The player's entries come first, so the player's address wins per host; an unknown serial returns the list unchanged.
	inline std::string with_title_redirects(std::string_view user_list, std::string_view serial, std::size_t& added)
	{
		added = 0;
		const std::string wanted = detail::ascii_lower(detail::trim(serial));
		const title_redirects* row = nullptr;
		for (const title_redirects& r : k_title_redirects)
		{
			if (detail::ascii_lower(r.serial) == wanted)
			{
				row = &r;
			}
		}
		if (row == nullptr)
		{
			return std::string(user_list);
		}

		std::string out;
		std::vector<std::string> named;
		for (std::string_view rest = user_list;;)
		{
			const std::size_t at = rest.find("&&");
			const std::string_view entry = detail::trim(rest.substr(0, at));
			if (!entry.empty())
			{
				out += out.empty() ? "" : "&&";
				out += entry;
				named.push_back(detail::ascii_lower(detail::trim(entry.substr(0, entry.find('=')))));
			}
			if (at == std::string_view::npos)
			{
				break;
			}
			rest.remove_prefix(at + 2);
		}

		for (const title_redirect& r : row->hosts)
		{
			if (std::find(named.begin(), named.end(), detail::ascii_lower(r.host)) == named.end())
			{
				out += out.empty() ? "" : "&&";
				out += r.host;
				out += '=';
				out += r.address;
				added++;
			}
		}
		return out;
	}

	// The macOS leg boots with Emu.BootGame itself, so it asks for the config Rpcs3IOS_Boot would use.
	std::string leg_boot_config(const std::string& content, const std::string& config);
}
