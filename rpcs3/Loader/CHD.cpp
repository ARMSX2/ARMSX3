#include "stdafx.h"
#include "CHD.h"

#include "Utilities/File.h"
#include "util/logs.hpp"

#include <libchdr/chd.h>

LOG_CHANNEL(chd_log, "CHD");

// The CHD magic. A CHD file begins with the 8-byte ASCII string "MComprHD".
// Reading this is how we tell a CHD from an ISO before the ISO9660 CD001 probe at 32768+1,
// which a CHD can never satisfy.
static constexpr char CHD_MAGIC[8] = {'M','C','o','m','p','r','H','D'};

struct chd_image::impl
{
	chd_file* chd = nullptr;   // the opened libchdr handle
	fs::file backing;          // kept alive; libchdr's callbacks wrap this

	// Read cursor for the callbacks. The LEGACY core_file struct carried its own `offset`
	// field; the modern void*-argp callbacks do not, so the position lives here instead.
	// libchdr only ever does sequential reads+seeks through fseek/fread, so tracking it
	// ourselves is exactly equivalent.
	u64 read_offset = 0;

	// Header values CAPTURED AT OPEN (see CHD.h) -- never re-read per call.
	u32 unit_bytes = 0;
	u32 hunk_bytes = 0;
	u64 unit_count = 0;

	~impl()
	{
		if (chd)
		{
			chd_close(chd);
			chd = nullptr;
		}
	}

	impl() = default;
	impl(const impl&) = delete;
	impl& operator=(const impl&) = delete;
};

namespace
{
	// ---- libchdr callbacks over fs::file ------------------------------------------------
	//
	// These use the MODERN chd_core_file_callbacks interface (void* argp), NOT the legacy
	// core_file struct. chd_open_core_file()/core_file are marked "Legacy; use
	// chd_open_core_file_callbacks instead!" in chd.h:396, and the legacy struct takes
	// callbacks that receive a `struct chd_core_file*` while the modern ones receive the
	// bare argp. Mixing the two silently mis-casts the user pointer, so pick one: modern.
	//
	// fs::file is the emulator's own abstraction and already what the ISO path uses, so CHD
	// reads go through the same layer -- which matters on Android, where the underlying
	// handle may be a SAF/content URI rather than a plain fd.

	u64 CHD_FSIZE(void* argp)
	{
		return static_cast<chd_image::impl*>(argp)->backing.size();
	}

	// NOTE the argument order: fread(void* ptr, size_t size, size_t nmemb, void* argp) --
	// four args, mirroring stdio, NOT the three-arg (buf, len) form.
	// core_fread() calls it as fread(ptr, 1, len, argp), so size==1 and the return value is
	// the byte count, which is exactly what libchdr expects back.
	size_t CHD_FREAD(void* ptr, size_t size, size_t nmemb, void* argp)
	{
		auto* impl = static_cast<chd_image::impl*>(argp);
		const size_t want = size * nmemb;
		const u64 got = impl->backing.read_at(impl->read_offset, ptr, want);
		impl->read_offset += got;
		return static_cast<size_t>(got);
	}

	int CHD_FSEEK(void* argp, int64_t offset, int whence)
	{
		auto* impl = static_cast<chd_image::impl*>(argp);

		switch (whence)
		{
		case SEEK_SET: impl->read_offset = static_cast<u64>(offset); break;
		case SEEK_CUR: impl->read_offset += offset; break;
		case SEEK_END: impl->read_offset = static_cast<u64>(offset) + impl->backing.size(); break;
		default: return 1;
		}

		return 0;
	}

	int CHD_FCLOSE(void* argp)
	{
		(void)argp;
		return 0; // Ownership stays with impl::backing; nothing to close here.
	}

	const core_file_callbacks chd_callbacks
	{
		.fsize = &CHD_FSIZE,
		.fread = &CHD_FREAD,
		.fclose = &CHD_FCLOSE,
		.fseek = &CHD_FSEEK,
	};
}

bool chd::is_chd_file(const std::string& path)
{
	if (path.empty() || !fs::is_file(path))
	{
		return false;
	}

	fs::file f(path);
	if (!f)
	{
		return false;
	}

	char magic[8]{};
	if (f.read_at(0, magic, sizeof(magic)) != sizeof(magic))
	{
		return false;
	}

	return std::memcmp(magic, CHD_MAGIC, sizeof(CHD_MAGIC)) == 0;
}

bool chd::probe(const std::string& path, u64* size, u32* sector_size)
{
	chd_image f(path);
	if (!f)
	{
		return false;
	}

	if (size) *size = f.logical_size();
	if (sector_size) *sector_size = f.unit_bytes();
	return true;
}

chd_image::chd_image(const std::string& path, bs_t<fs::open_mode> mode, const iso_fs_node* node)
{
	// A CHD is inherently read-only, so write-intent modes must be rejected -- but ONLY a
	// write-intent mode. Testing for equality with fs::read is wrong: callers legitimately
	// OR in non-write bits. iso_device::open() forwards the guest's own open mode straight
	// through (ISO.cpp get_iso_file), and PS3 titles routinely ask for read|lock or
	// read|isfile, which arrives as e.g. 257 (read|256). An equality check rejects those
	// perfectly valid reads with "CHD images are read-only", which is exactly the
	// InvalidFileOrFolder boot failure seen on-device.
	if (mode & (fs::write + fs::append + fs::create + fs::trunc))
	{
		chd_log.error("CHD images are read-only (mode=%u)", static_cast<u32>(mode));
		return;
	}

	if (!chd::is_chd_file(path))
	{
		chd_log.error("Not a CHD file: '%s'", path);
		return;
	}

	auto impl = std::make_unique<chd_image::impl>();

	impl->backing.open(path, fs::read);
	if (!impl->backing)
	{
		chd_log.error("Failed to open CHD backing file: '%s'", path);
		return;
	}

	// No parent/child support. PPSSPP disabled it for the same reason that applies here:
	// scanning directories for a parent by SHA1 is expensive on Android with scoped storage.
	// Parent/child is rejected explicitly so the user gets a clear message rather than a
	// confusing read failure later.
	chd_error err = chd_open_core_file_callbacks(&chd_callbacks, impl.get(), CHD_OPEN_READ, nullptr, &impl->chd);

	if (err == CHDERR_REQUIRES_PARENT)
	{
		chd_log.error("CHD requires a parent file, which is not supported: '%s'", path);
		return;
	}

	if (err != CHDERR_NONE)
	{
		chd_log.error("chd_open failed for '%s': %s", path, chd_error_string(err));
		return;
	}

	const chd_header* header = chd_get_header(impl->chd);
	if (!header || !header->unitbytes || !header->hunkbytes)
	{
		chd_log.error("CHD header is unusable for '%s'", path);
		return;
	}

	impl->unit_bytes = header->unitbytes;
	impl->hunk_bytes = header->hunkbytes;
	impl->unit_count = header->unitcount;

	m_unit_bytes = impl->unit_bytes;
	m_hunk_bytes = impl->hunk_bytes;
	m_blocks_per_hunk = m_hunk_bytes / m_unit_bytes;   // COMPUTED, never assumed (see CHD.h)
	m_size = static_cast<u64>(m_unit_bytes) * impl->unit_count;

	if (m_blocks_per_hunk == 0)
	{
		chd_log.error("CHD hunk size (%u) is smaller than its unit size (%u): '%s'",
			m_hunk_bytes, m_unit_bytes, path);
		return;
	}

	m_hunk_buf.resize(m_hunk_bytes);
	m_current_hunk = umax;

	// Extent window (see CHD.h). `m_size` is deliberately set to the IMAGE size above and then
	// narrowed here in exactly one place, so load_hunk/read_at have a single consistent notion
	// of the byte space they address.
	//
	// Order matters: read_at translates a WINDOW offset through file_offset() into an IMAGE
	// offset before touching m_size, so m_size must be the image size at that point. Exposing
	// the window size here and translating nothing -- which is what this class did before --
	// serves the whole disc image for every open(), so PARAM.SFO receives the disc's leading
	// bytes and boot aborts with "File is not of PSF format".
	if (node)
	{
		m_extents = node->metadata.extents;
	}
	else
	{
		// Whole-image window. This is the shape chd::probe() and the archive constructor want:
		// extent.start = 0 sectors, size = the entire logical image, so file_offset(p) == p.
		m_extents.push_back({0, m_size});
	}

	if (m_extents.empty())
	{
		chd_log.error("CHD node has no extents, cannot present a window: '%s'", path);
		return;
	}

	m_impl = std::move(impl);

	chd_log.success("CHD opened: '%s' (image %llu bytes, unit=%u, hunk=%u, %llu blocks/hunk, window %llu bytes, %llu extent(s))",
		path, m_size, m_unit_bytes, m_hunk_bytes, m_blocks_per_hunk, window_size(), m_extents.size());
}

// File-relative offset -> (offset within extent, extent). Verbatim from
// iso_file::get_extent_pos (ISO.cpp:1423) so the two readers cannot drift apart.
std::pair<u64, iso_extent_info> chd_image::get_extent_pos(u64 pos) const
{
	ensure(!m_extents.empty());

	auto it = m_extents.begin();

	while (pos >= it->size && it != m_extents.end() - 1)
	{
		pos -= it->size;

		it++;
	}

	return {pos, *it};
}

u64 chd_image::local_extent_remaining(u64 pos) const
{
	const auto [local_pos, extent] = get_extent_pos(pos);

	return extent.size - local_pos;
}

// The whole point of this class's window support. An extent's `start` is in SECTORS, so the
// multiply by ISO_SECTOR_SIZE is mandatory -- without it every read lands 2048x too early.
// Byte-identical to iso_file::file_offset (ISO.cpp:1452).
u64 chd_image::file_offset(u64 pos) const
{
	const auto [local_pos, extent] = get_extent_pos(pos);

	return (extent.start * ISO_SECTOR_SIZE) + local_pos;
}

// Total bytes this reader exposes: sum of all extents, matching iso_file::size() (ISO.cpp:1669).
u64 chd_image::window_size() const
{
	u64 total = 0;

	for (const auto& extent : m_extents)
	{
		total += extent.size;
	}

	return total;
}

chd_image::~chd_image() = default;

bool chd_image::load_hunk(u64 block)
{
	const u64 hunk = block / m_blocks_per_hunk;

	if (hunk == m_current_hunk)
	{
		return true;
	}

	// libchdr reads the WHOLE hunk; the cache is what stops consecutive reads inside one hunk
	// from re-decompressing it. PPSSPP hit the same thing (their PR #18931, "fix unnecessary
	// reloads of hunks during large reads"), which is why this is a cached single-hunk buffer
	// rather than a direct call per read.
	const chd_error err = chd_read(m_impl->chd, static_cast<u32>(hunk), m_hunk_buf.data());
	if (err != CHDERR_NONE)
	{
		chd_log.error("chd_read failed (hunk %llu): %s", hunk, chd_error_string(err));
		m_current_hunk = umax;
		return false;
	}

	m_current_hunk = hunk;
	return true;
}

u64 chd_image::read_at(u64 offset, void* buffer, u64 size)
{
	if (!m_impl || !buffer || m_extents.empty())
	{
		return 0;
	}

	// `offset` is WINDOW-relative; `file_offset()` maps it into IMAGE space. iso_file does the
	// same translation (ISO.cpp:1477) and clamps the request to the end of the current extent,
	// spilling the remainder into the next extent if the file has more than one.
	u64 max_size = std::min(size, local_extent_remaining(offset));

	if (max_size == 0)
	{
		return 0;
	}

	const u64 archive_first_offset = file_offset(offset);
	const u64 clamped = read_image(archive_first_offset, buffer, max_size);

	if (clamped != max_size)
	{
		return clamped;
	}

	// Contiguous multi-extent files: read the rest out of the following extent, exactly as
	// iso_file::read_at recurses (ISO.cpp:1493). Without this a fragmented file would
	// silently truncate at the first extent boundary.
	if (size > max_size && (offset + max_size) < window_size())
	{
		max_size += read_at(offset + max_size, &static_cast<u8*>(buffer)[max_size], size - max_size);
	}

	return max_size;
}

// Raw image-space read: `offset` addresses the decompressed logical image directly. This is
// the original linear read path, unchanged -- the extent translation lives in read_at() above
// so that this function has exactly one job.
u64 chd_image::read_image(u64 offset, void* buffer, u64 size)
{
	if (!m_impl || !buffer)
	{
		return 0;
	}

	if (offset >= m_size)
	{
		return 0;
	}

	size = std::min(size, m_size - offset);

	u8* out = static_cast<u8*>(buffer);
	u64 done = 0;

	// Straight linear mapping: offset -> block -> hunk -> offset within hunk.
	// No sector translation of any kind; a PS3 image is a plain byte stream.
	while (done < size)
	{
		const u64 pos = offset + done;
		const u64 block = pos / m_unit_bytes;
		const u64 block_offset = pos % m_unit_bytes;

		if (block >= m_impl->unit_count)
		{
			break;
		}

		if (!load_hunk(block))
		{
			return done;
		}

		// Copy out of the decompressed hunk. PPSSPP's ReadBlock is the reference here
	// (Core/FileSystems/BlockDevices.cpp): it memcpy's a FULL unit from
	// readBuffer + blockInHunk * unitbytes. Capping the copy at whatever is left in the
	// current unit would return a short read whenever the request is not unit-aligned,
	// which the caller sees as a truncated disc. A request may span several hunks, so
	// re-derive the hunk for each unit rather than assuming the cached one still matches.
	const u64 in_hunk = (block % m_blocks_per_hunk) * m_unit_bytes + block_offset;
	const u64 avail = std::min(size - done, static_cast<u64>(m_unit_bytes) - block_offset);
	const u64 in_hunk_avail = m_hunk_buf.size() - in_hunk;
	const u64 chunk = std::min(avail, in_hunk_avail);

		if (!chunk)
		{
			break;
		}

		std::memcpy(out + done, m_hunk_buf.data() + in_hunk, static_cast<size_t>(chunk));
		done += chunk;
	}

	return done;
}

u64 chd_image::read(void* buffer, u64 size)
{
	const u64 got = read_at(m_pos, buffer, size);
	m_pos += got;
	return got;
}

u64 chd_image::seek(s64 offset, fs::seek_mode whence)
{
	const s64 new_pos =
		whence == fs::seek_set ? offset :
		whence == fs::seek_cur ? offset + static_cast<s64>(m_pos) :
		whence == fs::seek_end ? offset + static_cast<s64>(m_size) : -1;

	if (new_pos < 0)
	{
		fs::g_tls_error = fs::error::inval;
		return umax;
	}

	m_pos = static_cast<u64>(new_pos);
	return m_pos;
}

u64 chd_image::size()
{
	return window_size();
}

u64 chd_image::write(const void* buffer, u64 size)
{
	(void)buffer;
	(void)size;
	fs::g_tls_error = fs::error::readonly;
	return 0;
}

bool chd_image::trunc(u64 length)
{
	(void)length;
	fs::g_tls_error = fs::error::readonly;
	return false;
}

fs::stat_t chd_image::get_stat()
{
	return fs::stat_t
	{
		.is_directory = false,
		.is_symlink = false,
		.is_writable = false,
		.size = window_size(),
		.atime = 0,
		.mtime = 0,
		.ctime = 0
	};
}

void chd_image::release()
{
	m_impl.reset();
	m_hunk_buf.clear();
	m_current_hunk = umax;
	m_pos = 0;
	m_size = 0;
	m_extents.clear();
}
