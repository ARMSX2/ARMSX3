// SPDX-License-Identifier: GPL-2.0-only
// HID keyboard usages to the Qt key codes the core's keyboard handler takes; literals, so it compiles alone on the host.
#pragma once

namespace armsx3::ios_keymap
{
	// Usages 44-56 in order: Space - = [ ] \ (non-US #) ; ' ` , . /
	inline constexpr char k_punct[] = " -=[]\\#;'`,./";
	inline constexpr char k_punct_shift[] = " _+{}|~:\"~<>?";
	// Usages 30-39 with Shift: 1-9 then 0.
	inline constexpr char k_digit_shift[] = "!@#$%^&*()";

	inline unsigned qt_code(int hid)
	{
		if (hid >= 4 && hid <= 29) return 0x41u + static_cast<unsigned>(hid - 4);         // A-Z
		if (hid >= 30 && hid <= 38) return 0x31u + static_cast<unsigned>(hid - 30);       // 1-9
		if (hid >= 44 && hid <= 56) return static_cast<unsigned char>(k_punct[hid - 44]); // its own ASCII
		if (hid >= 58 && hid <= 69) return 0x01000030u + static_cast<unsigned>(hid - 58); // F1-F12
		switch (hid)
		{
		case 39: return 0x30u;        // 0
		case 40: return 0x01000004u;  // Return
		case 41: return 0x01000000u;  // Escape
		case 42: return 0x01000003u;  // Backspace
		case 43: return 0x01000001u;  // Tab
		case 57: return 0x01000024u;  // Caps Lock
		case 73: return 0x01000006u;  // Insert
		case 74: return 0x01000010u;  // Home
		case 75: return 0x01000016u;  // Page Up
		case 76: return 0x01000007u;  // Delete
		case 77: return 0x01000011u;  // End
		case 78: return 0x01000017u;  // Page Down
		case 79: return 0x01000014u;  // Right
		case 80: return 0x01000012u;  // Left
		case 81: return 0x01000015u;  // Down
		case 82: return 0x01000013u;  // Up
		case 88: return 0x01000005u;  // keypad Enter
		case 224: case 228: return 0x01000021u; // Control
		case 225: case 229: return 0x01000020u; // Shift
		case 226: case 230: return 0x01000023u; // Alt (Option)
		case 227: case 231: return 0x01000022u; // Meta (Command)
		default: return 0;
		}
	}

	// The left/right modifiers in `native_key`'s __APPLE__ encoding; 0 for every other key.
	inline unsigned native_code(int hid)
	{
		switch (hid)
		{
		case 224: return 0x3Bu; // ctrl_l
		case 225: return 0x38u; // shift_l
		case 226: return 0x3Au; // alt_l
		case 227: return 0x37u; // meta_l
		case 228: return 0x3Eu; // ctrl_r
		case 229: return 0x3Cu; // shift_r
		case 230: return 0x3Du; // alt_r
		case 231: return 0x36u; // meta_r
		default: return 0;
		}
	}

	// shortcut: US layout only, and Shift without Caps Lock; take the character from UIKey.characters to follow the user's layout.
	inline char32_t character(int hid, bool shift)
	{
		if (hid >= 4 && hid <= 29) return static_cast<char32_t>((shift ? 'A' : 'a') + (hid - 4));
		if (hid >= 30 && hid <= 39) return static_cast<char32_t>(shift ? k_digit_shift[hid - 30] : (hid == 39 ? '0' : '1' + (hid - 30)));
		if (hid >= 44 && hid <= 56) return static_cast<char32_t>(shift ? k_punct_shift[hid - 44] : k_punct[hid - 44]);
		return 0;
	}
}
