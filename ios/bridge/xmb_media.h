// SPDX-License-Identifier: GPL-2.0-only
// A game's own XMB media for the C ABI, ported from Android's android/src/xmb-media.cpp and _armsx3_xmbDiscMedia.
#pragma once

#include "rpcs3_ios_abi.h"

#include <cstdint>
#include <string>

namespace armsx3::bridge::xmb
{
	// The first audio stream of in_path (a game's ATRAC3 SND0.AT3) as a 16-bit stereo WAV at out_path.
	bool decode_audio(const std::string& in_path, const std::string& out_path);

	// The ATRAC3plus track of an ICON1.PAM as a 16-bit stereo WAV; false when it has none.
	bool decode_pam_audio(const std::string& in_path, const std::string& out_path);

	// An ICON1.PAM opened for playback, or null.
	Rpcs3IOS_XmbIcon* icon_open(const std::string& path);
	void icon_size(const Rpcs3IOS_XmbIcon* icon, uint32_t& width, uint32_t& height);
	// The next frame as RGBA into dst; its time in milliseconds, or -1 at the end or on an error.
	int64_t icon_frame(Rpcs3IOS_XmbIcon* icon, uint8_t* dst, uint64_t capacity);
	void icon_close(Rpcs3IOS_XmbIcon* icon);

	// What copy_disc_media did. Absent is for good: the image read and has none of the files, or is another game's.
	// Failed may pass: the image did not read (a key not yet installed, a drive gone) or a file did not write.
	enum class disc_media
	{
		copied,
		absent,
		failed,
	};

	// PS3_GAME's PIC1.PNG, SND0.AT3 and ICON1.PAM out of a disc image into out_dir, when its TITLE_ID is serial.
	disc_media copy_disc_media(const std::string& image, const std::string& serial, const std::string& out_dir);
} // namespace armsx3::bridge::xmb
