// SPDX-License-Identifier: GPL-2.0-only
// The camera delivers BGRA at its own size; the core's sink wants RGBA at the game's size, so this swizzles, scales and mirrors.

#pragma once

#include <cstddef>
#include <cstdint>

namespace armsx3::camera_convert
{
	// shortcut: a plain nearest-neighbour loop, fine at 640x480 and 30 fps; switch to vImage if a profile shows it costs a frame.
	inline void bgra_to_rgba(const std::uint8_t* src, unsigned sw, unsigned sh, std::size_t spitch,
		std::uint8_t* dst, unsigned dw, unsigned dh, bool mirror)
	{
		for (unsigned y = 0; y < dh; y++)
		{
			const std::uint8_t* row = src + static_cast<std::size_t>(y) * sh / dh * spitch;
			std::uint8_t* out = dst + static_cast<std::size_t>(y) * dw * 4;

			for (unsigned x = 0; x < dw; x++, out += 4)
			{
				const unsigned sx = static_cast<unsigned>(static_cast<std::size_t>(mirror ? dw - 1 - x : x) * sw / dw);
				const std::uint8_t* p = row + static_cast<std::size_t>(sx) * 4;
				out[0] = p[2];
				out[1] = p[1];
				out[2] = p[0];
				out[3] = p[3];
			}
		}
	}
}
