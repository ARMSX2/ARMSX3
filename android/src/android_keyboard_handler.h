#pragma once

#include "util/types.hpp"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/system_config.h"
#include "Emu/Io/interception.h"

// A keyboard the guest believes is physically attached, driven from the Android UI.
//
// RPCS3's only real handler is basic_keyboard_handler, which derives from QObject and filters
// QKeyEvent off a QWindow -- android/CMakeLists.txt excludes it along with the rest of the Qt
// input layer, so cellKb previously had nothing but NullKeyboardHandler and reported no keyboard
// at all. Games that require one are then unreachable: NFS Most Wanted's beta debug menu, and
// native keyboard support in the likes of Counter-Strike.
//
// Almost none of basic_keyboard_handler is actually Qt-bound. KeyboardHandlerBase::HandleKey
// already takes plain u32 codes and keyboard_consumer::ConsumeKey resolves them with
// m_keys.find(code), so the code space only has to agree between whatever registers the buttons
// and whatever injects them. This handler therefore registers ANDROID KeyEvent keycodes directly
// rather than pretending to be Qt, and the UI passes the keycodes it already has.
//
// The PS3 side uses USB HID usage IDs (A = 0x04 .. Z = 0x1d, 1 = 0x1e .. 0 = 0x27), and Android's
// letter and digit keycodes are contiguous too, so those map arithmetically; only the rest needs a
// table.
class android_keyboard_handler final : public KeyboardHandlerBase
{
	using KeyboardHandlerBase::KeyboardHandlerBase;

	// Android KeyEvent keycodes. Named here rather than pulled from a header because the native
	// side has no Android SDK constants, and these are ABI-stable platform values.
	enum : u32
	{
		AKEY_0 = 7, AKEY_9 = 16,
		AKEY_DPAD_UP = 19, AKEY_DPAD_DOWN = 20, AKEY_DPAD_LEFT = 21, AKEY_DPAD_RIGHT = 22,
		AKEY_A = 29, AKEY_Z = 54,
		AKEY_COMMA = 55, AKEY_PERIOD = 56,
		AKEY_ALT_LEFT = 57, AKEY_ALT_RIGHT = 58,
		AKEY_SHIFT_LEFT = 59, AKEY_SHIFT_RIGHT = 60,
		AKEY_TAB = 61, AKEY_SPACE = 62,
		AKEY_ENTER = 66, AKEY_DEL = 67, AKEY_GRAVE = 68, AKEY_MINUS = 69, AKEY_EQUALS = 70,
		AKEY_LEFT_BRACKET = 71, AKEY_RIGHT_BRACKET = 72, AKEY_BACKSLASH = 73,
		AKEY_SEMICOLON = 74, AKEY_APOSTROPHE = 75, AKEY_SLASH = 76,
		AKEY_PAGE_UP = 92, AKEY_PAGE_DOWN = 93,
		AKEY_ESCAPE = 111, AKEY_FORWARD_DEL = 112,
		AKEY_CTRL_LEFT = 113, AKEY_CTRL_RIGHT = 114,
		AKEY_CAPS_LOCK = 115,
		AKEY_META_LEFT = 117, AKEY_META_RIGHT = 118,
		AKEY_MOVE_HOME = 122, AKEY_MOVE_END = 123, AKEY_INSERT = 124,
		AKEY_F1 = 131, AKEY_F12 = 142,
	};

public:
	void Init(keyboard_consumer& consumer, const u32 max_connect) override
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
				// Enable key repeat, matching basic_keyboard_handler: the OSK and the other
				// overlays rely on repeat for held arrows and backspace.
				kb.m_key_repeat = true;
			}

			LoadSettings(kb);
			keyboards.emplace_back(kb);
		}

		info.max_connect = max_connect;
		info.now_connect = std::min(::size32(keyboards), max_connect);

		// Ownership of keyboard data: 0 = application, 1 = system.
		info.info      = input::g_keyboards_intercepted ? CELL_KB_INFO_INTERCEPTED : 0;
		info.status[0] = CELL_KB_STATUS_CONNECTED;
	}

private:
	static void LoadSettings(Keyboard& keyboard)
	{
		std::vector<KbButton> buttons;

		const auto add = [&buttons](u32 android_code, u32 cell_code)
		{
			buttons.emplace_back(android_code, cell_code);
		};

		// Modifiers. Unlike Qt, Android does tell left from right, so all eight are real here.
		add(AKEY_CTRL_LEFT,   CELL_KB_MKEY_L_CTRL);
		add(AKEY_CTRL_RIGHT,  CELL_KB_MKEY_R_CTRL);
		add(AKEY_SHIFT_LEFT,  CELL_KB_MKEY_L_SHIFT);
		add(AKEY_SHIFT_RIGHT, CELL_KB_MKEY_R_SHIFT);
		add(AKEY_ALT_LEFT,    CELL_KB_MKEY_L_ALT);
		add(AKEY_ALT_RIGHT,   CELL_KB_MKEY_R_ALT);
		add(AKEY_META_LEFT,   CELL_KB_MKEY_L_WIN);
		add(AKEY_META_RIGHT,  CELL_KB_MKEY_R_WIN);

		// Letters and digits are contiguous on both sides. PS3 digits run 1..9 then 0, which is
		// why zero is handled apart from the rest.
		for (u32 i = 0; i <= (AKEY_Z - AKEY_A); i++)
		{
			add(AKEY_A + i, CELL_KEYC_A + i);
		}

		for (u32 i = 1; i <= 9; i++)
		{
			add(AKEY_0 + i, CELL_KEYC_1 + (i - 1));
		}

		add(AKEY_0, CELL_KEYC_0);

		// Function keys, also contiguous.
		for (u32 i = 0; i <= (AKEY_F12 - AKEY_F1); i++)
		{
			add(AKEY_F1 + i, CELL_KEYC_F1 + i);
		}

		add(AKEY_ENTER,         CELL_KEYC_ENTER);
		add(AKEY_ESCAPE,        CELL_KEYC_ESCAPE);
		add(AKEY_DEL,           CELL_KEYC_BS);
		add(AKEY_TAB,           CELL_KEYC_TAB);
		add(AKEY_SPACE,         CELL_KEYC_SPACE);
		add(AKEY_MINUS,         CELL_KEYC_MINUS);
		add(AKEY_EQUALS,        CELL_KEYC_EQUAL_101);
		add(AKEY_LEFT_BRACKET,  CELL_KEYC_LEFT_BRACKET_101);
		add(AKEY_RIGHT_BRACKET, CELL_KEYC_RIGHT_BRACKET_101);
		add(AKEY_BACKSLASH,     CELL_KEYC_BACKSLASH_101);
		add(AKEY_SEMICOLON,     CELL_KEYC_SEMICOLON);
		add(AKEY_APOSTROPHE,    CELL_KEYC_QUOTATION_101);
		add(AKEY_COMMA,         CELL_KEYC_COMMA);
		add(AKEY_PERIOD,        CELL_KEYC_PERIOD);
		add(AKEY_SLASH,         CELL_KEYC_SLASH);
		add(AKEY_CAPS_LOCK,     CELL_KEYC_CAPS_LOCK);
		add(AKEY_INSERT,        CELL_KEYC_INSERT);
		add(AKEY_FORWARD_DEL,   CELL_KEYC_DELETE);
		add(AKEY_MOVE_HOME,     CELL_KEYC_HOME);
		add(AKEY_MOVE_END,      CELL_KEYC_END);
		add(AKEY_PAGE_UP,       CELL_KEYC_PAGE_UP);
		add(AKEY_PAGE_DOWN,     CELL_KEYC_PAGE_DOWN);
		add(AKEY_DPAD_LEFT,     CELL_KEYC_LEFT_ARROW);
		add(AKEY_DPAD_RIGHT,    CELL_KEYC_RIGHT_ARROW);
		add(AKEY_DPAD_UP,       CELL_KEYC_UP_ARROW);
		add(AKEY_DPAD_DOWN,     CELL_KEYC_DOWN_ARROW);

		keyboard.m_keys.clear();

		for (const KbButton& button : buttons)
		{
			keyboard.m_keys[button.m_keyCode] = button;
		}
	}
};
