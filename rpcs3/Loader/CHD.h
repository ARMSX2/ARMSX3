#pragma once

#include "ISO.h"
#include "util/logs.hpp"

#include <memory>

// ARMSX3: CHD (MAME Compressed Hunks of Data) disc image support.
//
// Reference implementations, in order of relevance to a PS3 port:
//   - PPSSPP  Core/FileSystems/BlockDevices.cpp  CHDFileBlockDevice  <- USE THIS SHAPE
//   - PCSX2   pcsx2/CDVD/ChdFileReader.cpp                            (CD-ROM swap path, don't copy)
//
// PPSSPP is the right template because it reads a DVD-mode CHD as a LINEAR BYTE STREAM:
// a block index maps to a hunk index, chd_read() decompresses the hunk, and a single memcpy
// extracts the block. No sector translation, no EDC/ECC, no byte-swapping. A PS3 image is a
// DVD/Blu-ray image -- 2048-byte logical sectors, no EDC/ECC, no swap -- so that is exactly
// what is needed here.
//
// PCSX2 also works and also uses createdvd, but it works DESPITE carrying a CD-ROM sector-swap
// path that a PS3 image never exercises. Porting that here would import dead weight and create
// ambiguity about which path a given CHD takes.
//
// HUNK SIZE IS NOT ASSUMED. libchdr headers report hunkbytes/unitbytes/unitcount; blocks per
// hunk is COMPUTED from them. This matters: chdman changed its default from one sector per hunk
// (2048) to two (4096) in a recent version, which broke PPSSPP's documented `createdvd` command
// and forced it to ship a "bad CHD" warning. Computing rather than assuming means any hunk size
// works and we do not inherit that class of bug.
//
// Header values are captured ONCE at open time: chd_get_header() returns a pointer into the
// chd_file, so reading through it per call is both slower and unnecessarily risky.

namespace chd
{
	// True if the file at `path` starts with the CHD magic ("MComprHD").
	// Cheap: reads 8 bytes. Used by is_iso_file() to detect CHD before the ISO9660 probe.
	bool is_chd_file(const std::string& path);

	// Returns true and fills `size`/`sector_size` if `path` is a readable CHD.
	bool probe(const std::string& path, u64* size = nullptr, u32* sector_size = nullptr);
}

// NOTE the name. libchdr ALREADY declares `typedef struct _chd_file chd_file;`
// (chd.h:306), so a class called chd_file in the same translation unit is a
// redefinition error the moment chd.h is included. This was a real compile
// failure, not a style preference. Hence `chd_image` here; the libchdr opaque
// handle is stored as `::chd_file*` to be unambiguous.
class chd_image : public fs::file_base
{
public:
	// Public ONLY so the libchdr callbacks in CHD.cpp (free functions in an
	// anonymous namespace) can reach it. They receive an opaque void* that is
	// really an `impl*`. Friending them would require naming functions in an
	// anonymous namespace, which has internal linkage and cannot be declared as
	// a friend from a header -- so this nested type is public instead. Nothing
	// outside CHD.cpp has any reason to touch it.
	struct impl;

private:
	std::unique_ptr<impl> m_impl;

	// Read cache: one hunk, decompressed. CHD hunk reads decompress the WHOLE hunk, so
	// consecutive reads from the same hunk must not re-decompress. Mirrors PPSSPP's
	// currentHunk/readBuffer pair.
	std::vector<u8> m_hunk_buf;
	u64 m_current_hunk = umax;

	u64 m_pos = 0;
	u64 m_size = 0;          // header->unitcount * header->unitbytes
	u32 m_unit_bytes = 0;    // logical sector size (2048 for a PS3 image)
	u32 m_hunk_bytes = 0;
	u64 m_blocks_per_hunk = 0;

	// Decompress the hunk containing `block`, if not already cached. false on failure.
	bool load_hunk(u64 block);

	// Raw image-space read: `offset` addresses the decompressed logical image directly.
	// read_at() splits into this and the extent translation above so each has one job.
	u64 read_image(u64 offset, void* buffer, u64 size);

	// Extent window into the logical image, mirroring iso_file.
	//
	// This is NOT optional. iso_archive::get_iso_file hands back a reader for ONE FILE inside
	// the disc, and iso_file achieves that by remembering the file's extents and translating
	// every file-relative offset into an image-absolute one (see iso_file::file_offset:
	// extent.start * ISO_SECTOR_SIZE + local_pos). A chd_image that ignored extents would
	// return the WHOLE disc image from byte 0 for every open() -- so PARAM.SFO would be served
	// the disc's leading bytes, the PSF magic check would fail, and the boot would abort with
	// "File is not of PSF format" / "PS3_DISC.SFB may be truncated". That is the exact
	// on-device symptom this window exists to fix.
	std::vector<iso_extent_info> m_extents;

	// File-relative offset -> image-absolute offset, exactly as iso_file does it.
	// These mirror iso_file::get_extent_pos / file_offset / local_extent_remaining
	// (ISO.cpp:1423-1457) line for line, including the multiply by ISO_SECTOR_SIZE:
	// an extent's `start` is a SECTOR number, not a byte offset. Getting that wrong
	// silently shifts every read by 2048x.
	std::pair<u64, iso_extent_info> get_extent_pos(u64 pos) const;
	u64 file_offset(u64 pos) const;
	u64 local_extent_remaining(u64 pos) const;
	u64 window_size() const;

public:
	// `node` selects the sub-range to present. When null the whole image is exposed, which is
	// what the archive-level probe and chd::probe() want.
	chd_image(const std::string& path, bs_t<fs::open_mode> mode = fs::read,
		const iso_fs_node* node = nullptr);
	~chd_image() override;

	explicit operator bool() const { return m_impl != nullptr; }

	fs::stat_t get_stat() override;
	bool trunc(u64 length) override;
	u64 read(void* buffer, u64 size) override;
	u64 read_at(u64 offset, void* buffer, u64 size) override;
	u64 write(const void* buffer, u64 size) override;
	u64 seek(s64 offset, fs::seek_mode whence) override;
	u64 size() override;

	void release() override;

	// Convenience for the archive layer.
	u64 logical_size() const { return m_size; }
	u32 unit_bytes() const { return m_unit_bytes; }
	u32 hunk_bytes() const { return m_hunk_bytes; }
};
