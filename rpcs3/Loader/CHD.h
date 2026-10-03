#pragma once

// CHD (MAME Compressed Hunks of Data) disc images: an ISO compressed with chdman createdvd, or
// with createcd, which stores it as one CD data track.
// Feature contributed by jdubbing (PR #173); reworked on merge so the image is opened once per
// disc and read through the ISO loader's own file readers and decryption, and so a createcd
// image reads too.
//
// A CHD stands in for the .iso it was made from. is_iso_file() recognises one from its header,
// iso_archive opens it once, and every file on the disc is an iso_file reading through that one
// open image, so extents, encryption and everything else work exactly as for an .iso.

#include "Utilities/File.h"
#include "util/types.hpp"

#include <memory>
#include <string>

namespace chd
{
	class image;

	// Whether the file starts with the CHD signature. Reads 8 bytes.
	bool is_chd_file(const std::string& path);

	// Whether `path` is a CHD this loader can read as a disc image, and its decompressed size.
	// Reads the header only: opening one decodes its whole hunk map, which is tens of MB on a
	// Blu-ray disc, and this runs on every library scan and boot. Explains a rejection in the log.
	bool probe(const std::string& path, u64* size = nullptr);

	// Opens the image, decoding its hunk map. Null when it cannot be read; the log says why.
	std::shared_ptr<image> open(const std::string& path);

	// A file over the whole decompressed image, with its own position. Any number can be open on
	// one image, from any thread.
	fs::file make_file(std::shared_ptr<image> img);
}
