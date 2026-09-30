#include "stdafx.h"
#include "CHD.h"
#include "ISO.h"

#include <libchdr/chd.h>
#include <libchdr/cdrom.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

// CHD disc images. Feature contributed by jdubbing (PR #173); reworked on merge so the image is
// opened once per disc and read through the ISO loader's own file readers and decryption, and so
// a CD-mode CHD (chdman createcd) of an ISO reads too.

LOG_CHANNEL(chd_log, "CHD");

namespace
{
	constexpr char s_magic[8] = {'M', 'C', 'o', 'm', 'p', 'r', 'H', 'D'};

	// chdman makes 4 KB hunks for a DVD image and 8-frame ones for a CD. Anything up to libchdr's
	// own limit reads, just slower: a hunk is decoded whole for every read that touches it.
	constexpr u32 s_max_hunk_bytes = 16 * 1024 * 1024;

	// libchdr keeps the whole hunk map in memory, 12 bytes a hunk: 150 MB for a 50 GB disc in
	// 4 KB hunks. An image that would need more than this is refused.
	constexpr u64 s_max_map_bytes = 384ull * 1024 * 1024;

	// Decoded hunks kept for reads that come back to them: a read that ends inside a hunk and the
	// next one that starts there, or two files being streamed at once.
	constexpr u64 s_cache_bytes = 4 * 1024 * 1024;
	constexpr usz s_max_cache_slots = 4;

	std::string codec_name(u32 codec)
	{
		std::string name;

		for (int shift = 24; shift >= 0; shift -= 8)
		{
			const char c = static_cast<char>(codec >> shift);
			name += (c >= ' ' && c <= '~') ? c : '?';
		}

		return name;
	}
}

class chd::image
{
public:
	explicit image(const std::string& path)
		: m_path(path)
	{
	}

	image(const image&) = delete;
	image& operator=(const image&) = delete;

	~image()
	{
		if (m_chd)
		{
			chd_close(m_chd);
		}
	}

	// Reads and checks the header. False, without a word, for a file that is not a CHD at all.
	bool read_header(chd_header& header)
	{
		m_file.open(m_path);

		char magic[sizeof(s_magic)]{};

		if (!m_file || m_file.read_at(0, magic, sizeof(magic)) != sizeof(magic) || std::memcmp(magic, s_magic, sizeof(magic)) != 0)
		{
			return false;
		}

		m_file_size = m_file.size();
		m_file_pos = 0;

		if (const chd_error err = chd_read_header_core_file_callbacks(&s_callbacks, this, &header); err != CHDERR_NONE)
		{
			chd_log.error("'%s': the CHD header does not read: %s", m_path, chd_error_string(err));
			return false;
		}

		return check_header(header);
	}

	bool open()
	{
		chd_header header{};

		if (!read_header(header))
		{
			return false;
		}

		if (const chd_error err = chd_open_core_file_callbacks(&s_callbacks, this, CHD_OPEN_READ, nullptr, &m_chd); err != CHDERR_NONE)
		{
			m_chd = nullptr;
			chd_log.error("'%s' does not open: %s", m_path, chd_error_string(err));
			return false;
		}

		m_hunk_bytes = header.hunkbytes;

		if (header.unitbytes == CD_FRAME_SIZE)
		{
			if (!find_cd_data_track())
			{
				return false;
			}
		}
		else
		{
			m_size = header.logicalbytes;
			m_frame_bytes = ISO_SECTOR_SIZE;
		}

		m_slots.resize(std::clamp<usz>(s_cache_bytes / m_hunk_bytes, 1, s_max_cache_slots));

		for (hunk_slot& slot : m_slots)
		{
			slot.data = std::make_unique<u8[]>(m_hunk_bytes);
		}

		chd_log.notice("Opened '%s': %llu MB disc image, %u-byte hunks%s", m_path, m_size >> 20, m_hunk_bytes, m_frame_bytes == ISO_SECTOR_SIZE ? "" : " (CD)");
		return true;
	}

	u64 size() const
	{
		return m_size;
	}

	// Safe from any thread. libchdr's decoder is not, so reads take turns.
	u64 read_at(u64 offset, void* buffer, u64 size)
	{
		if (offset >= m_size)
		{
			return 0;
		}

		size = std::min(size, m_size - offset);

		std::lock_guard lock(m_mutex);

		u8* out = static_cast<u8*>(buffer);
		u64 done = 0;

		while (done < size)
		{
			// Where the byte is stored: a DVD image as it is, a CD one as the 2048 bytes of each frame
			// that hold the sector. A frame never spans two hunks.
			const u64 pos = offset + done;
			const u64 in_sector = pos % ISO_SECTOR_SIZE;
			const u64 stored = (m_first_frame + pos / ISO_SECTOR_SIZE) * m_frame_bytes + m_data_offset + in_sector;
			const u32 index = static_cast<u32>(stored / m_hunk_bytes);
			const u64 in_hunk = stored % m_hunk_bytes;
			const u64 chunk = std::min<u64>(size - done, m_frame_bytes == ISO_SECTOR_SIZE ? m_hunk_bytes - in_hunk : ISO_SECTOR_SIZE - in_sector);

			if (const u8* hunk = find_cached(index))
			{
				std::memcpy(out + done, hunk + in_hunk, chunk);
			}
			else if (chunk == m_hunk_bytes)
			{
				// The read wants the whole hunk: decode it straight into the caller's buffer, and
				// leave the cache to the partial hunks that later reads come back to.
				if (!decode(index, out + done))
				{
					break;
				}
			}
			else if (const u8* hunk = decode_cached(index))
			{
				std::memcpy(out + done, hunk + in_hunk, chunk);
			}
			else
			{
				break;
			}

			done += chunk;
		}

		return done;
	}

private:
	struct hunk_slot
	{
		u32 index = umax;
		u64 last_use = 0;
		std::unique_ptr<u8[]> data;
	};

	bool check_header(const chd_header& header) const
	{
		if (header.version != 5)
		{
			chd_log.error("'%s' is a version %u CHD. Only version 5 reads: convert it again with a current chdman.", m_path, header.version);
			return false;
		}

		if (std::any_of(std::begin(header.parentsha1), std::end(header.parentsha1), [](u8 byte) { return byte != 0; }))
		{
			chd_log.error("'%s' only holds the changes to a parent CHD, which is not supported.", m_path);
			return false;
		}

		// A DVD image (chdman createdvd) is the disc's sectors as they are. A CD image (createcd)
		// keeps each sector in a 2448-byte frame, and which bytes of it depends on the track.
		const bool cd = header.unitbytes == CD_FRAME_SIZE;

		if ((!cd && header.unitbytes != ISO_SECTOR_SIZE) || header.logicalbytes % header.unitbytes || header.logicalbytes / header.unitbytes <= ISO_DESCRIPTORS_OFFSET / ISO_SECTOR_SIZE)
		{
			chd_log.error("'%s' does not hold a disc image (%u-byte units, %llu bytes). Make it with chdman createdvd.",
				m_path, header.unitbytes, header.logicalbytes);
			return false;
		}

		if (!header.hunkbytes || header.hunkbytes % header.unitbytes || header.hunkbytes > s_max_hunk_bytes)
		{
			chd_log.error("'%s' has %u-byte hunks, which do not read.", m_path, header.hunkbytes);
			return false;
		}

		for (const u32 codec : header.compression)
		{
			switch (codec)
			{
			case CHD_CODEC_NONE:
			case CHD_CODEC_ZLIB:
			case CHD_CODEC_LZMA:
			case CHD_CODEC_HUFFMAN:
			case CHD_CODEC_FLAC:
			case CHD_CODEC_ZSTD:
				break;
			case CHD_CODEC_CD_ZLIB:
			case CHD_CODEC_CD_LZMA:
			case CHD_CODEC_CD_FLAC:
			case CHD_CODEC_CD_ZSTD:
				if (cd)
				{
					break;
				}
				[[fallthrough]];
			default:
				chd_log.error("'%s' is compressed with '%s', which is not used for disc images.", m_path, codec_name(codec));
				return false;
			}
		}

		if (u64{header.hunkcount} * 12 > s_max_map_bytes)
		{
			chd_log.error("'%s' has %u hunks, whose map would take %llu MB of memory.", m_path, header.hunkcount, u64{header.hunkcount} * 12 >> 20);
			return false;
		}

		return true;
	}

	// A CD image reads as a disc image when it is one data track of 2048-byte sectors, which is
	// what chdman createcd makes of an ISO. The sector sits at the start of each frame, or after
	// the sync and header of a raw sector.
	bool find_cd_data_track()
	{
		char track[256]{};
		u32 length = 0;

		if (chd_get_metadata(m_chd, CDROM_TRACK_METADATA2_TAG, 0, track, sizeof(track) - 1, &length, nullptr, nullptr) != CHDERR_NONE &&
			chd_get_metadata(m_chd, CDROM_TRACK_METADATA_TAG, 0, track, sizeof(track) - 1, &length, nullptr, nullptr) != CHDERR_NONE)
		{
			chd_log.error("'%s' is a CD image without a track list.", m_path);
			return false;
		}

		char more[8]{};
		const bool one_track =
			chd_get_metadata(m_chd, CDROM_TRACK_METADATA2_TAG, 1, more, sizeof(more), &length, nullptr, nullptr) != CHDERR_NONE &&
			chd_get_metadata(m_chd, CDROM_TRACK_METADATA_TAG, 1, more, sizeof(more), &length, nullptr, nullptr) != CHDERR_NONE;

		char type[32]{};
		char pregap_type[32]{};
		u32 frames = 0;
		u32 pregap = 0;

		// "TRACK:1 TYPE:MODE1 SUBTYPE:NONE FRAMES:1315408 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
		// or the older form that stops after FRAMES.
		const int fields = std::sscanf(track, "TRACK:%*u TYPE:%31s SUBTYPE:%*s FRAMES:%u PREGAP:%u PGTYPE:%31s", type, &frames, &pregap, pregap_type);

		if (fields < 2 || !one_track || (std::strcmp(type, "MODE1") != 0 && std::strcmp(type, "MODE1_RAW") != 0))
		{
			chd_log.error("'%s' is a CD image (%s), not one track of a disc image.", m_path, track);
			return false;
		}

		// A pregap chdman stored ('V' types) comes before the track's first sector.
		m_first_frame = fields >= 4 && pregap_type[0] == 'V' ? pregap : 0;
		m_frame_bytes = CD_FRAME_SIZE;
		m_data_offset = std::strcmp(type, "MODE1_RAW") == 0 ? 16 : 0;
		m_size = u64{frames} * ISO_SECTOR_SIZE;

		if ((m_first_frame + u64{frames}) * CD_FRAME_SIZE > chd_get_header(m_chd)->logicalbytes)
		{
			chd_log.error("'%s' lists more sectors than it holds (%s).", m_path, track);
			return false;
		}

		return true;
	}

	bool decode(u32 index, u8* dest)
	{
		if (const chd_error err = chd_read(m_chd, index, dest); err != CHDERR_NONE)
		{
			chd_log.error("'%s': hunk %u does not decode: %s", m_path, index, chd_error_string(err));
			return false;
		}

		return true;
	}

	const u8* find_cached(u32 index)
	{
		for (hunk_slot& slot : m_slots)
		{
			if (slot.index == index)
			{
				slot.last_use = ++m_uses;
				return slot.data.get();
			}
		}

		return nullptr;
	}

	const u8* decode_cached(u32 index)
	{
		hunk_slot& slot = *std::min_element(m_slots.begin(), m_slots.end(), [](const hunk_slot& a, const hunk_slot& b)
		{
			return a.last_use < b.last_use;
		});

		// A failed decode can leave the buffer half written.
		slot.index = umax;
		slot.last_use = 0;

		if (!decode(index, slot.data.get()))
		{
			return nullptr;
		}

		slot.index = index;
		slot.last_use = ++m_uses;
		return slot.data.get();
	}

	// libchdr reads the file through these, with this image as their argument. They only run
	// inside libchdr calls, which read_header() and open() make before the image is shared, and
	// read_at() makes under m_mutex.

	static u64 callback_size(void* argp)
	{
		return static_cast<image*>(argp)->m_file_size;
	}

	static size_t callback_read(void* buffer, size_t size, size_t count, void* argp)
	{
		image& self = *static_cast<image*>(argp);

		if (!size || !count || count > std::numeric_limits<size_t>::max() / size || self.m_file_pos >= self.m_file_size)
		{
			return 0;
		}

		const u64 bytes = std::min<u64>(size * count, self.m_file_size - self.m_file_pos);
		const u64 read = self.m_file.read_at(self.m_file_pos, buffer, bytes);

		self.m_file_pos += read;
		return static_cast<size_t>(read / size);
	}

	static int callback_seek(void* argp, s64 offset, int whence)
	{
		image& self = *static_cast<image*>(argp);
		u64 base = 0;

		switch (whence)
		{
		case SEEK_SET: break;
		case SEEK_CUR: base = self.m_file_pos; break;
		case SEEK_END: base = self.m_file_size; break;
		default: return -1;
		}

		const u64 pos = base + static_cast<u64>(offset);

		// Refuse a position before the start of the file, or one that wraps around.
		if (offset < 0 ? pos > base : pos < base)
		{
			return -1;
		}

		self.m_file_pos = pos;
		return 0;
	}

	static int callback_close(void*)
	{
		// m_file is the image's own, and closes with it.
		return 0;
	}

	static constexpr core_file_callbacks s_callbacks{&callback_size, &callback_read, &callback_close, &callback_seek};

	const std::string m_path;
	fs::file m_file;
	u64 m_file_size = 0;
	u64 m_file_pos = 0; // libchdr's position in m_file, which its seek and read callbacks move
	chd_file* m_chd = nullptr;
	u64 m_size = 0; // of the disc image inside
	u32 m_hunk_bytes = 0;
	u64 m_frame_bytes = ISO_SECTOR_SIZE; // what one sector takes in the CHD: 2448 on a CD image
	u64 m_data_offset = 0; // where in that the sector's 2048 bytes start
	u64 m_first_frame = 0;
	std::vector<hunk_slot> m_slots;
	u64 m_uses = 0;
	std::mutex m_mutex;
};

namespace
{
	// One open file on a chd::image: the image is shared, the position is not.
	class chd_image_file final : public fs::file_base
	{
		const std::shared_ptr<chd::image> m_image;
		u64 m_pos = 0;

	public:
		explicit chd_image_file(std::shared_ptr<chd::image> img)
			: m_image(std::move(img))
		{
		}

		fs::stat_t get_stat() override
		{
			return fs::stat_t
			{
				.is_directory = false,
				.is_symlink = false,
				.is_writable = false,
				.size = m_image->size(),
			};
		}

		bool trunc(u64 /*length*/) override
		{
			fs::g_tls_error = fs::error::readonly;
			return false;
		}

		u64 read(void* buffer, u64 size) override
		{
			const u64 result = m_image->read_at(m_pos, buffer, size);

			m_pos += result;
			return result;
		}

		u64 read_at(u64 offset, void* buffer, u64 size) override
		{
			return m_image->read_at(offset, buffer, size);
		}

		u64 write(const void* /*buffer*/, u64 /*size*/) override
		{
			fs::g_tls_error = fs::error::readonly;
			return 0;
		}

		u64 seek(s64 offset, fs::seek_mode whence) override
		{
			const s64 new_pos =
				whence == fs::seek_set ? offset :
				whence == fs::seek_cur ? offset + static_cast<s64>(m_pos) :
				whence == fs::seek_end ? offset + static_cast<s64>(m_image->size()) : -1;

			if (new_pos < 0)
			{
				fs::g_tls_error = fs::error::inval;
				return umax;
			}

			m_pos = static_cast<u64>(new_pos);
			return m_pos;
		}

		u64 size() override
		{
			return m_image->size();
		}
	};
}

bool chd::is_chd_file(const std::string& path)
{
	const fs::file file(path);
	char magic[sizeof(s_magic)]{};

	return file && file.read_at(0, magic, sizeof(magic)) == sizeof(magic) && std::memcmp(magic, s_magic, sizeof(magic)) == 0;
}

bool chd::probe(const std::string& path, u64* size)
{
	image img(path);
	chd_header header{};

	if (!img.read_header(header))
	{
		return false;
	}

	if (size)
	{
		// A CD image's track list is only readable once it is open: to within its padding here.
		*size = header.logicalbytes / header.unitbytes * ISO_SECTOR_SIZE;
	}

	return true;
}

std::shared_ptr<chd::image> chd::open(const std::string& path)
{
	auto img = std::make_shared<image>(path);

	if (!img->open())
	{
		return nullptr;
	}

	return img;
}

fs::file chd::make_file(std::shared_ptr<image> img)
{
	return fs::file(std::make_unique<chd_image_file>(std::move(img)));
}
