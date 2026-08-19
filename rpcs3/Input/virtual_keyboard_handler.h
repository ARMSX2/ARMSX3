#pragma once

#include "util/types.hpp"
#include "Emu/Io/KeyboardHandler.h"

// Keyboard handler for the Android front end.
//
// The desktop handler is a QObject that installs an event filter on a QWindow, so
// none of it survives the port. It does not need to: everything that turns a key
// into cellKb data already lives in KeyboardHandlerBase::HandleKey, and a concrete
// handler owes it exactly one thing -- a populated qt_code -> CELL_KEYC map. This
// builds that map and nothing else.
//
// Keys arrive from the UI (a physical/Bluetooth keyboard, or the Android IME the
// On-Screen Keyboard hotkey raises) through _rpcsx_keyboardKey.
class virtual_keyboard_handler final : public KeyboardHandlerBase
{
	using KeyboardHandlerBase::KeyboardHandlerBase;

public:
	void Init(keyboard_consumer& consumer, const u32 max_connect) override;

	// Android KeyEvent keycode -> the Qt key code the shared map is keyed on.
	// Returns 0 for keys a PS3 keyboard has no equivalent of.
	static u32 qt_code_from_android(s32 android_key_code);

	// Left/right modifier discrimination, in the native_key encoding that
	// keyboard_consumer::get_out_key_code expects. 0 for everything else.
	static u32 native_code_from_android(s32 android_key_code);

private:
	static void load_settings(Keyboard& keyboard);
};

// Feed one key transition to the running keyboard handler.
//
// Safe to call at any time: with no game booted, or with the handler set to Null,
// there is nothing to consume the key and this returns false.
bool handle_android_key(s32 android_key_code, char32_t unicode, bool pressed, bool is_auto_repeat);
