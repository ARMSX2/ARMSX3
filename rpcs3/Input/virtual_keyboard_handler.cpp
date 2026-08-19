#include "stdafx.h"
#include "virtual_keyboard_handler.h"

#include "Emu/IdManager.h"
#include "Emu/Io/interception.h"
#include "Emu/system_config.h"

LOG_CHANNEL(input_log, "Input");

namespace
{
	// Qt key codes, as literals.
	//
	// KeyboardHandlerBase keys its map on Qt's enum and cellKb's conversion tables are
	// written against it, so these values are part of the shared contract even where no
	// Qt exists. Printable ASCII is its own code point (upper case for letters), and
	// everything else is Qt's 0x01000000 block. KeyboardHandler.h already declares the
	// modifier subset it needs; the rest is here.
	enum qt_key : u32
	{
		qt_space        = 0x20,
		qt_numbersign   = 0x23,
		qt_apostrophe   = 0x27,
		qt_plus         = 0x2b,
		qt_comma        = 0x2c,
		qt_minus        = 0x2d,
		qt_period       = 0x2e,
		qt_slash        = 0x2f,
		qt_0            = 0x30,
		qt_colon        = 0x3a,
		qt_semicolon    = 0x3b,
		qt_less         = 0x3c,
		qt_equal        = 0x3d,
		qt_at           = 0x40,
		qt_a            = 0x41,
		qt_bracketleft  = 0x5b,
		qt_backslash    = 0x5c,
		qt_bracketright = 0x5d,
		qt_asciicircum  = 0x5e,
		qt_quoteleft    = 0x60,
		qt_yen          = 0xa5,
		qt_multiply     = 0xd7,
		qt_division     = 0xf7,

		qt_escape       = 0x01000000,
		qt_tab          = 0x01000001,
		qt_backspace    = 0x01000003,
		qt_return       = 0x01000004,
		qt_enter        = 0x01000005, // keypad
		qt_insert       = 0x01000006,
		qt_delete       = 0x01000007,
		qt_pause        = 0x01000008,
		qt_print        = 0x01000009,

		qt_home         = 0x01000010,
		qt_end          = 0x01000011,
		qt_left         = 0x01000012,
		qt_up           = 0x01000013,
		qt_right        = 0x01000014,
		qt_down         = 0x01000015,
		qt_pageup       = 0x01000016,
		qt_pagedown     = 0x01000017,

		qt_f1           = 0x01000030,
	};

	// Android KeyEvent constants. Hard-coded rather than pulled from <android/keycodes.h>
	// so this file builds in a desktop tree too.
	enum android_key : s32
	{
		ak_0             = 7,
		ak_dpad_up       = 19,
		ak_dpad_down     = 20,
		ak_dpad_left     = 21,
		ak_dpad_right    = 22,
		ak_a             = 29,
		ak_comma         = 55,
		ak_period        = 56,
		ak_alt_left      = 57,
		ak_alt_right     = 58,
		ak_shift_left    = 59,
		ak_shift_right   = 60,
		ak_tab           = 61,
		ak_space         = 62,
		ak_enter         = 66,
		ak_del           = 67, // backspace
		ak_grave         = 68,
		ak_minus         = 69,
		ak_equals        = 70,
		ak_left_bracket  = 71,
		ak_right_bracket = 72,
		ak_backslash     = 73,
		ak_semicolon     = 74,
		ak_apostrophe    = 75,
		ak_slash         = 76,
		ak_at            = 77,
		ak_plus          = 81,
		ak_page_up       = 92,
		ak_page_down     = 93,
		ak_escape        = 111,
		ak_forward_del   = 112,
		ak_ctrl_left     = 113,
		ak_ctrl_right    = 114,
		ak_caps_lock     = 115,
		ak_scroll_lock   = 116,
		ak_meta_left     = 117,
		ak_meta_right    = 118,
		ak_sysrq         = 120,
		ak_break         = 121,
		ak_move_home     = 122,
		ak_move_end      = 123,
		ak_insert        = 124,
		ak_f1            = 131,
		ak_num_lock      = 143,
		ak_numpad_0      = 144,
		ak_numpad_divide = 154,
		ak_numpad_multiply = 155,
		ak_numpad_subtract = 156,
		ak_numpad_add    = 157,
		ak_numpad_dot    = 158,
		ak_numpad_enter  = 160,
		ak_pound         = 18,
		ak_star          = 17,
	};
}

u32 virtual_keyboard_handler::qt_code_from_android(s32 code)
{
	// Contiguous runs first, so the table below only carries the exceptions.
	if (code >= ak_a && code <= ak_a + 25)
	{
		return qt_a + static_cast<u32>(code - ak_a); // A..Z
	}

	if (code >= ak_0 && code <= ak_0 + 9)
	{
		return qt_0 + static_cast<u32>(code - ak_0); // 0..9
	}

	if (code >= ak_f1 && code <= ak_f1 + 11)
	{
		return qt_f1 + static_cast<u32>(code - ak_f1); // F1..F12
	}

	// The numeric keypad has no separate PS3 raw codes in the map the desktop handler
	// builds (they are commented out there, because the digits are already taken), so
	// fold the digits onto the top row. The operators do have their own codes.
	if (code >= ak_numpad_0 && code <= ak_numpad_0 + 9)
	{
		return qt_0 + static_cast<u32>(code - ak_numpad_0);
	}

	switch (code)
	{
	case ak_space:          return qt_space;
	case ak_enter:          return qt_return;
	case ak_numpad_enter:   return qt_enter;
	case ak_del:            return qt_backspace;
	case ak_forward_del:    return qt_delete;
	case ak_tab:            return qt_tab;
	case ak_escape:         return qt_escape;

	case ak_shift_left:
	case ak_shift_right:    return Key_Shift;
	case ak_ctrl_left:
	case ak_ctrl_right:     return Key_Control;
	case ak_alt_left:
	case ak_alt_right:      return Key_Alt;
	case ak_meta_left:
	case ak_meta_right:     return Key_Meta;

	case ak_caps_lock:      return Key_CapsLock;
	case ak_num_lock:       return Key_NumLock;
	case ak_scroll_lock:    return Key_ScrollLock;

	case ak_dpad_up:        return qt_up;
	case ak_dpad_down:      return qt_down;
	case ak_dpad_left:      return qt_left;
	case ak_dpad_right:     return qt_right;
	case ak_move_home:      return qt_home;
	case ak_move_end:       return qt_end;
	case ak_page_up:        return qt_pageup;
	case ak_page_down:      return qt_pagedown;
	case ak_insert:         return qt_insert;
	case ak_sysrq:          return qt_print;
	case ak_break:          return qt_pause;

	case ak_minus:
	case ak_numpad_subtract: return qt_minus;
	case ak_equals:         return qt_equal;
	case ak_left_bracket:   return qt_bracketleft;
	case ak_right_bracket:  return qt_bracketright;
	case ak_backslash:      return qt_backslash;
	case ak_semicolon:      return qt_semicolon;
	case ak_apostrophe:     return qt_apostrophe;
	case ak_grave:          return qt_quoteleft;
	case ak_comma:          return qt_comma;
	case ak_period:
	case ak_numpad_dot:     return qt_period;
	case ak_slash:          return qt_slash;
	case ak_at:             return qt_at;
	case ak_plus:
	case ak_numpad_add:     return qt_plus;
	case ak_pound:          return qt_numbersign;
	case ak_numpad_divide:  return qt_division;
	case ak_numpad_multiply:
	case ak_star:           return qt_multiply;

	default:                return 0;
	}
}

u32 virtual_keyboard_handler::native_code_from_android(s32 code)
{
	// get_out_key_code compares against native_key, whose Android build inherits the
	// Linux/X11 values. Anything else lands on the "right-hand key" branch, so the
	// left-hand keys in particular have to be exact.
	switch (code)
	{
	case ak_ctrl_left:   return native_key::ctrl_l;
	case ak_ctrl_right:  return native_key::ctrl_r;
	case ak_shift_left:  return native_key::shift_l;
	case ak_shift_right: return native_key::shift_r;
	case ak_alt_left:    return native_key::alt_l;
	case ak_alt_right:   return native_key::alt_r;
	case ak_meta_left:   return native_key::meta_l;
	case ak_meta_right:  return native_key::meta_r;
	default:             return 0;
	}
}

void virtual_keyboard_handler::load_settings(Keyboard& keyboard)
{
	std::vector<KbButton> buttons;

	// Meta keys. Left/right is settled later by get_out_key_code from the native code,
	// which is why both sides map to the same Qt code here.
	buttons.emplace_back(Key_Control, CELL_KB_MKEY_L_CTRL);
	buttons.emplace_back(Key_Shift, CELL_KB_MKEY_L_SHIFT);
	buttons.emplace_back(Key_Alt, CELL_KB_MKEY_L_ALT);
	buttons.emplace_back(Key_Meta, CELL_KB_MKEY_L_WIN);
	buttons.emplace_back(Key_Super_L, CELL_KB_MKEY_L_WIN);
	buttons.emplace_back(Key_Super_R, CELL_KB_MKEY_R_WIN);

	// CELL_KB_RAWDAT
	buttons.emplace_back(qt_escape, CELL_KEYC_ESCAPE);
	buttons.emplace_back(Key_CapsLock, CELL_KEYC_CAPS_LOCK);
	buttons.emplace_back(qt_f1 + 0, CELL_KEYC_F1);
	buttons.emplace_back(qt_f1 + 1, CELL_KEYC_F2);
	buttons.emplace_back(qt_f1 + 2, CELL_KEYC_F3);
	buttons.emplace_back(qt_f1 + 3, CELL_KEYC_F4);
	buttons.emplace_back(qt_f1 + 4, CELL_KEYC_F5);
	buttons.emplace_back(qt_f1 + 5, CELL_KEYC_F6);
	buttons.emplace_back(qt_f1 + 6, CELL_KEYC_F7);
	buttons.emplace_back(qt_f1 + 7, CELL_KEYC_F8);
	buttons.emplace_back(qt_f1 + 8, CELL_KEYC_F9);
	buttons.emplace_back(qt_f1 + 9, CELL_KEYC_F10);
	buttons.emplace_back(qt_f1 + 10, CELL_KEYC_F11);
	buttons.emplace_back(qt_f1 + 11, CELL_KEYC_F12);
	buttons.emplace_back(qt_print, CELL_KEYC_PRINTSCREEN);
	buttons.emplace_back(Key_ScrollLock, CELL_KEYC_SCROLL_LOCK);
	buttons.emplace_back(qt_pause, CELL_KEYC_PAUSE);
	buttons.emplace_back(qt_insert, CELL_KEYC_INSERT);
	buttons.emplace_back(qt_home, CELL_KEYC_HOME);
	buttons.emplace_back(qt_pageup, CELL_KEYC_PAGE_UP);
	buttons.emplace_back(qt_delete, CELL_KEYC_DELETE);
	buttons.emplace_back(qt_end, CELL_KEYC_END);
	buttons.emplace_back(qt_pagedown, CELL_KEYC_PAGE_DOWN);
	buttons.emplace_back(qt_right, CELL_KEYC_RIGHT_ARROW);
	buttons.emplace_back(qt_left, CELL_KEYC_LEFT_ARROW);
	buttons.emplace_back(qt_down, CELL_KEYC_DOWN_ARROW);
	buttons.emplace_back(qt_up, CELL_KEYC_UP_ARROW);

	// CELL_KB_KEYPAD
	buttons.emplace_back(Key_NumLock, CELL_KEYC_KPAD_NUMLOCK);
	buttons.emplace_back(qt_division, CELL_KEYC_KPAD_SLASH);
	buttons.emplace_back(qt_multiply, CELL_KEYC_KPAD_ASTERISK);
	buttons.emplace_back(qt_plus, CELL_KEYC_KPAD_PLUS);
	buttons.emplace_back(qt_enter, CELL_KEYC_KPAD_ENTER);

	// ASCII printable
	for (u32 i = 0; i < 26; i++)
	{
		buttons.emplace_back(qt_a + i, CELL_KEYC_A + i);
	}

	buttons.emplace_back(qt_0 + 1, CELL_KEYC_1);
	buttons.emplace_back(qt_0 + 2, CELL_KEYC_2);
	buttons.emplace_back(qt_0 + 3, CELL_KEYC_3);
	buttons.emplace_back(qt_0 + 4, CELL_KEYC_4);
	buttons.emplace_back(qt_0 + 5, CELL_KEYC_5);
	buttons.emplace_back(qt_0 + 6, CELL_KEYC_6);
	buttons.emplace_back(qt_0 + 7, CELL_KEYC_7);
	buttons.emplace_back(qt_0 + 8, CELL_KEYC_8);
	buttons.emplace_back(qt_0 + 9, CELL_KEYC_9);
	buttons.emplace_back(qt_0 + 0, CELL_KEYC_0);

	buttons.emplace_back(qt_return, CELL_KEYC_ENTER);
	buttons.emplace_back(qt_backspace, CELL_KEYC_BS);
	buttons.emplace_back(qt_tab, CELL_KEYC_TAB);
	buttons.emplace_back(qt_space, CELL_KEYC_SPACE);
	buttons.emplace_back(qt_minus, CELL_KEYC_MINUS);
	buttons.emplace_back(qt_equal, CELL_KEYC_EQUAL_101);
	buttons.emplace_back(qt_asciicircum, CELL_KEYC_ACCENT_CIRCONFLEX_106);
	buttons.emplace_back(qt_at, CELL_KEYC_ATMARK_106);
	buttons.emplace_back(qt_semicolon, CELL_KEYC_SEMICOLON);
	buttons.emplace_back(qt_apostrophe, CELL_KEYC_QUOTATION_101);
	buttons.emplace_back(qt_colon, CELL_KEYC_COLON_106);
	buttons.emplace_back(qt_comma, CELL_KEYC_COMMA);
	buttons.emplace_back(qt_period, CELL_KEYC_PERIOD);
	buttons.emplace_back(qt_slash, CELL_KEYC_SLASH);
	buttons.emplace_back(qt_yen, CELL_KEYC_YEN_106);

	// Some buttons share a key code across layouts
	if (keyboard.m_config.arrange == CELL_KB_MAPPING_106)
	{
		buttons.emplace_back(qt_backslash, CELL_KEYC_BACKSLASH_106);
		buttons.emplace_back(qt_bracketleft, CELL_KEYC_LEFT_BRACKET_106);
		buttons.emplace_back(qt_bracketright, CELL_KEYC_RIGHT_BRACKET_106);
	}
	else
	{
		buttons.emplace_back(qt_backslash, CELL_KEYC_BACKSLASH_101);
		buttons.emplace_back(qt_bracketleft, CELL_KEYC_LEFT_BRACKET_101);
		buttons.emplace_back(qt_bracketright, CELL_KEYC_RIGHT_BRACKET_101);
	}

	buttons.emplace_back(qt_less, CELL_KEYC_LESS);
	buttons.emplace_back(qt_numbersign, CELL_KEYC_HASHTAG);
	buttons.emplace_back(qt_quoteleft, CELL_KEYC_BACK_QUOTE);

	for (const KbButton& button : buttons)
	{
		if (!keyboard.m_keys.try_emplace(button.m_keyCode, button).second)
		{
			input_log.error("virtual_keyboard_handler failed to set key code %d", button.m_keyCode);
		}
	}
}

void virtual_keyboard_handler::Init(keyboard_consumer& consumer, const u32 max_connect)
{
	KbInfo& info = consumer.GetInfo();
	std::vector<Keyboard>& keyboards = consumer.GetKeyboards();

	info = {};
	keyboards.clear();

	for (u32 i = 0; i < max_connect; i++)
	{
		Keyboard kb{};
		kb.m_config.arrange = g_cfg.sys.keyboard_type;

		if (consumer.id() == keyboard_consumer::identifier::overlays)
		{
			kb.m_key_repeat = true;
		}

		load_settings(kb);

		keyboards.emplace_back(kb);
	}

	info.max_connect = max_connect;
	info.now_connect = std::min(::size32(keyboards), max_connect);
	info.info        = input::g_keyboards_intercepted ? CELL_KB_INFO_INTERCEPTED : 0;
	info.status[0]   = CELL_KB_STATUS_CONNECTED;
}

bool handle_android_key(s32 android_key_code, char32_t unicode, bool pressed, bool is_auto_repeat)
{
	auto* handler = g_fxo->try_get<KeyboardHandlerBase>();

	if (!handler)
	{
		return false;
	}

	const u32 qt_code = virtual_keyboard_handler::qt_code_from_android(android_key_code);

	if (!qt_code)
	{
		return false;
	}

	const u32 native_code = virtual_keyboard_handler::native_code_from_android(android_key_code);

	// The overlays consumer matches on this string rather than on the key code, so it is
	// what makes typing work in the on-screen keyboard / text entry dialogs.
	std::u32string key;
	if (unicode)
	{
		key.push_back(unicode);
	}

	return handler->HandleKey(qt_code, native_code, pressed, is_auto_repeat, key);
}
