// SPDX-License-Identifier: GPL-2.0-only
// Lego Dimensions needs no creator here: the core has dimensions_toypad::create_blank_character.

#pragma once

#include "util/types.hpp"

#include <array>

namespace armsx3::bridge::portal_figures
{
	bool create_blank_skylander(std::array<u8, 0x40 * 0x10>& buf, u16 sky_id, u16 sky_var);

	// False when the number's top byte is zero, as on desktop.
	bool create_blank_infinity(std::array<u8, 0x14 * 0x10>& buf, u32 character, u8 series);

	bool create_blank_kamen_rider(std::array<u8, 0x14 * 0x10>& buf, u32 fig_id);
}
