// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{

	bool pressure_modifier_held() { return g_pressure_modifier.load(); }

	// GCKeyboard gives key codes, not text, so the bridge tracks Shift itself; bit 0 is left Shift, bit 1 right.
	std::atomic<int> g_kb_shift{0};

	constexpr pad_button_code k_pad_buttons[] = {
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_UP},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_DOWN},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_LEFT},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_RIGHT},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_CROSS},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_CIRCLE},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_SQUARE},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_TRIANGLE},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_L1},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_R1},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_L2},
		{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_R2},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_START},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_SELECT},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_L3},
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_R3},
		// The core opens its home menu on the PS button's rising edge; nothing here calls it.
		{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_PS},
	};
	static_assert(std::size(k_pad_buttons) == RPCS3_IOS_PAD_BUTTON_PS + 1,
		"one {offset, code} pair per RPCS3_IOS_PAD_BUTTON_* value, in the header's order");
	// Holds the game's cellMouse init access, so cellMouseInit and cellMouseEnd cannot run under the call.
	template <typename F>
	Rpcs3IOS_Status with_mouse(MouseHandlerBase* handler, F&& call)
	{
		const auto init = handler->init.access();
		if (!init)
		{
			return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
		}
		call();
		return RPCS3_IOS_STATUS_OK;
	}
	Rpcs3IOS_Status input_config_status(armsx3::bridge::input_config::outcome outcome)
	{
		switch (outcome)
		{
		case armsx3::bridge::input_config::outcome::ok: return RPCS3_IOS_STATUS_OK;
		case armsx3::bridge::input_config::outcome::invalid: return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		case armsx3::bridge::input_config::outcome::refused: return RPCS3_IOS_STATUS_SETTING_REFUSED;
		case armsx3::bridge::input_config::outcome::write_failed: return RPCS3_IOS_STATUS_SETTINGS_WRITE_FAILED;
		case armsx3::bridge::input_config::outcome::unreadable: return RPCS3_IOS_STATUS_SETTINGS_UNREADABLE;
		}
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}
	struct portal_spec
	{
		int32_t slots;
		u64 bytes;
	};

	// 0 Skylanders, 1 Disney Infinity, 2 Lego Dimensions, 3 Kamen Rider: the dialogs' slot counts and the files' sizes.
	constexpr portal_spec k_portals[4] = {{8, 0x40 * 0x10}, {9, 0x14 * 0x10}, {7, 0x2D * 0x04}, {8, 0x14 * 0x10}};

	// dimensions_dialog's layout: the toypad pad each of its seven slots sits on.
	constexpr u8 k_dimensions_pads[7] = {2, 1, 3, 2, 2, 3, 3};

	struct portal_figure
	{
		u8 handle; // what the core's remove takes: its own slot (Skylanders, Kamen Rider) or the dialog's (the others)
		u32 id;
		u16 var;
	};

	std::mutex g_portal_mutex;
	std::array<std::array<std::optional<portal_figure>, 9>, 4> g_portal_slots;

	// Desktop's clear_* for one slot, with g_portal_mutex held and the portal and slot already checked.
	void clear_portal_slot(int32_t portal, int32_t slot)
	{
		std::optional<portal_figure>& held = g_portal_slots[portal][slot];
		if (!held)
		{
			return;
		}

		switch (portal)
		{
		case 0: g_skyportal.remove_skylander(held->handle); break;
		case 1: g_infinitybase.remove_figure(held->handle); break;
		case 2: g_dimensionstoypad.remove_figure(k_dimensions_pads[slot], held->handle, true, true); break;
		default: g_ridergate.remove_figure(held->handle); break;
		}
		held.reset();
	}
}


Rpcs3IOS_Status Rpcs3IOS_SetPadState(uint32_t buttons_mask, uint8_t lx, uint8_t ly, uint8_t rx, uint8_t ry, uint8_t l2, uint8_t r2)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPadState");
	}

	return Rpcs3IOS_SetPlayerPadState(0, buttons_mask, lx, ly, rx, ry, l2, r2);
}

Rpcs3IOS_Status Rpcs3IOS_SetPlayerPadState(int32_t player, uint32_t buttons_mask, uint8_t lx, uint8_t ly, uint8_t rx, uint8_t ry, uint8_t l2, uint8_t r2)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPlayerPadState");
	}

	if (player < 0 || player >= CELL_PAD_MAX_PORT_NUM)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// Bit i is button i, so a bit above PS names no button and is refused rather than dropped.
	if ((buttons_mask >> (RPCS3_IOS_PAD_BUTTON_PS + 1)) != 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// The relaxed accessor only observes; the plain one aborts when no pad thread exists.
	std::lock_guard lock(pad::g_pad_mutex);

	pad_thread* thread = pad::get_pad_thread(true);
	if (thread == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	const std::shared_ptr<Pad>& pad = thread->GetPads()[player];
	if (!pad)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// Every row is written, pressed or not; a pressed button reads its analog value, and 0 there reads `full`.
	const u16 full = pressure_modifier_held() ? pad->m_pressure_intensity : 255;
	bool matched = false;
	for (u32 i = 0; i < std::size(k_pad_buttons); i++)
	{
		const bool pressed = ((buttons_mask >> i) & 1u) != 0;
		const u16 analog = i == RPCS3_IOS_PAD_BUTTON_L2 ? l2 : i == RPCS3_IOS_PAD_BUTTON_R2 ? r2 : 0;
		const u16 value = !pressed ? 0 : (analog == 0 ? full : analog);

		for (Button& b : pad->m_buttons)
		{
			if (b.m_offset == k_pad_buttons[i].offset && b.m_outKeyCode == k_pad_buttons[i].code)
			{
				b.m_pressed = pressed;
				b.m_value = value;
				matched = true;
			}
		}
	}

	// A pad carrying none of the buttons belongs to another handler, so its sticks stay untouched too.
	if (!matched)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// 128 is a centred stick, AnalogStick's own default.
	pad->m_sticks[0].m_value = lx;
	pad->m_sticks[1].m_value = ly;
	pad->m_sticks[2].m_value = rx;
	pad->m_sticks[3].m_value = ry;

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_SetPlayerConnected(int32_t player, int32_t connected)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPlayerConnected");
	}

	if (player < 0 || player >= CELL_PAD_MAX_PORT_NUM || (connected != 0 && connected != 1))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// Remembered first, so this counts even when there is no pad to change yet.
	g_port_connected[player].store(connected == 1);

	std::lock_guard lock(pad::g_pad_mutex);

	pad_thread* thread = pad::get_pad_thread(true);
	if (thread == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	const std::shared_ptr<Pad>& pad = thread->GetPads()[player];
	if (!pad)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// ASSIGN_CHANGES tells cellPad the port changed either way, as a real hot-plug does.
	if (connected == 1)
	{
		pad->m_port_status |= CELL_PAD_STATUS_CONNECTED | CELL_PAD_STATUS_ASSIGN_CHANGES;
	}
	else
	{
		pad->m_port_status &= ~CELL_PAD_STATUS_CONNECTED;
		pad->m_port_status |= CELL_PAD_STATUS_ASSIGN_CHANGES;
	}

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_SetPlayerMotion(int32_t player, uint16_t x, uint16_t y, uint16_t z, uint16_t g)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPlayerMotion");
	}

	// The core clamps its own handlers' Sixaxis to 0-1023.
	if (player < 0 || player >= CELL_PAD_MAX_PORT_NUM || x > 1023 || y > 1023 || z > 1023 || g > 1023)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::lock_guard lock(pad::g_pad_mutex);

	pad_thread* thread = pad::get_pad_thread(true);
	if (thread == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	const std::shared_ptr<Pad>& pad = thread->GetPads()[player];
	if (!pad)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// The four sensors connect_virtual_pad seeded at the DEFAULT_MOTION_* rest values.
	pad->m_sensors[0].m_value = x;
	pad->m_sensors[1].m_value = y;
	pad->m_sensors[2].m_value = z;
	pad->m_sensors[3].m_value = g;

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetMoveColor(int32_t gem, float* out_r, float* out_g, float* out_b)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetMoveColor");
	}

	if (out_r == nullptr || out_g == nullptr || out_b == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (gem < 0 || gem >= static_cast<int32_t>(CELL_GEM_MAX_NUM))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	*out_r = 0;
	*out_g = 0;
	*out_b = 0;

	// A guest-started teardown frees the getter's pointer after stopping, so it is read only while running, paused or frozen.
	const system_state state = Emu.GetStatus(false);

	if (state != system_state::running && state != system_state::paused && state != system_state::frozen)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	f32 r = 0, g = 0, b = 0;
	if (!gem_get_sphere_rgb(static_cast<u32>(gem), r, g, b))
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	*out_r = r;
	*out_g = g;
	*out_b = b;
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetPlayerRumble(int32_t player, uint8_t* large, uint8_t* small)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetPlayerRumble");
	}

	if (large == nullptr || small == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (player < 0 || player >= CELL_PAD_MAX_PORT_NUM)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::lock_guard lock(pad::g_pad_mutex);

	pad_thread* thread = pad::get_pad_thread(true);
	if (thread == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	const std::shared_ptr<Pad>& pad = thread->GetPads()[player];
	if (!pad)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// The core's 3 s rumble timeout, applied here because the virtual pad's empty process() never runs it.
	const bool stale = pad->m_last_rumble_time_us == 0 || get_system_time() - pad->m_last_rumble_time_us > 3'000'000;
	// Converted through the player's pad config as a physical pad's motors are; a stale motor never reaches it.
	const cfg_player* const player_cfg = pad->m_player_id < g_cfg_input.player.size() ? g_cfg_input.player[pad->m_player_id] : nullptr;
	*large = stale || player_cfg == nullptr ? 0 : player_cfg->config.get_large_motor_speed(pad->m_vibrate_motors);
	*small = stale || player_cfg == nullptr ? 0 : player_cfg->config.get_small_motor_speed(pad->m_vibrate_motors);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_KeyboardKey(int32_t hid_usage, int32_t pressed)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_KeyboardKey");
	}

	// Only a mapped usage of the keyboard page and a 0 or 1 value, before the handler lookup.
	if (hid_usage < 1 || hid_usage > 231 || (pressed != 0 && pressed != 1) || armsx3::ios_keymap::qt_code(hid_usage) == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (hid_usage == 225 || hid_usage == 229)
	{
		const int bit = hid_usage == 225 ? 1 : 2;
		if (pressed == 1)
			g_kb_shift.fetch_or(bit);
		else
			g_kb_shift.fetch_and(~bit);
	}

	auto* handler = g_fxo->try_get<KeyboardHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// The overlays consumer (the PS3's own text dialogs) matches on this string when a key has no code of its own.
	std::u32string key;
	if (const char32_t character = armsx3::ios_keymap::character(hid_usage, g_kb_shift.load() != 0))
	{
		key.push_back(character);
	}

	// HandleKey takes the handler's own mutex. Its bool says whether a consumer took the key; that is not a refusal.
	handler->HandleKey(armsx3::ios_keymap::qt_code(hid_usage), armsx3::ios_keymap::native_code(hid_usage), pressed == 1,
		false, key);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_SetKeyboardConnected(int32_t connected)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetKeyboardConnected");
	}

	if (connected != 0 && connected != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<KeyboardHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// cellKb's consumer exists only between cellKbInit and cellKbEnd, so the init access comes first.
	const auto init = handler->init.access();
	if (!init)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	std::lock_guard lock(handler->m_mutex);
	keyboard_consumer& consumer = handler->GetConsumer(keyboard_consumer::identifier::cellKb);
	consumer.GetInfo().status[0] = connected == 1 ? CELL_KB_STATUS_CONNECTED : CELL_KB_STATUS_DISCONNECTED;
	if (connected == 0)
	{
		// No key stays down in the guest after the keyboard goes.
		consumer.ReleaseAllKeys();
	}
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_MouseButton(int32_t button, int32_t pressed)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_MouseButton");
	}

	// get_mouse_button_code throws past its table, so the range comes first.
	if (button < 1 || button > 8 || (pressed != 0 && pressed != 1))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	return with_mouse(handler, [&]
	{
		handler->Button(0, get_mouse_button_code(button - 1), pressed == 1);
	});
}

Rpcs3IOS_Status Rpcs3IOS_MouseMove(int32_t dx, int32_t dy)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_MouseMove");
	}

	if (dx < -127 || dx > 128 || dy < -127 || dy > 128)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// Relative: the PS3 reads deltas, and an iPad mouse under pointer lock has no position.
	return with_mouse(handler, [&]
	{
		handler->Move(0, 0, 0, 0, 0, true, dx, dy);
	});
}

Rpcs3IOS_Status Rpcs3IOS_MouseScroll(int32_t dx, int32_t dy)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_MouseScroll");
	}

	if (dx < -128 || dx > 127 || dy < -128 || dy > 127)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	return with_mouse(handler, [&]
	{
		handler->Scroll(0, static_cast<s8>(dx), static_cast<s8>(dy));
	});
}

Rpcs3IOS_Status Rpcs3IOS_SetMouseConnected(int32_t connected)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetMouseConnected");
	}

	if (connected != 0 && connected != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	return with_mouse(handler, [&]
	{
		std::lock_guard lock(handler->mutex);
		// The null handler has no port 0, and a status there would report a mouse it cannot feed.
		if (handler->GetMice().empty())
		{
			return;
		}
		handler->GetInfo().status[0] = connected == 1 ? CELL_MOUSE_STATUS_CONNECTED : CELL_MOUSE_STATUS_DISCONNECTED;
		if (connected == 0)
		{
			// No button stays down in the guest after the mouse goes, and no stale move is read later.
			Mouse& mouse = handler->GetMice()[0];
			mouse.buttons = 0;
			mouse.m_datalist.clear();
		}
	});
}

Rpcs3IOS_Status Rpcs3IOS_MouseAim(int32_t x, int32_t y, int32_t x_max, int32_t y_max)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_MouseAim");
	}

	// The guns divide by the bounds and GunCon 3 multiplies the position by 65535.
	if (x_max <= 0 || y_max <= 0 || x_max > 32767 || y_max > 32767 || x < 0 || x > x_max || y < 0 || y > y_max)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// Not through with_mouse: a gun game need not call cellMouseInit, because the gun initialises the handler itself.
	handler->Move(0, x, y, x_max, y_max, false, 0, 0);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_MouseAimButton(int32_t button, int32_t pressed)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_MouseAimButton");
	}

	// get_mouse_button_code throws past its table, so the range comes first.
	if (button < 1 || button > 8 || (pressed != 0 && pressed != 1))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	auto* handler = g_fxo->try_get<MouseHandlerBase>();
	if (handler == nullptr)
	{
		return RPCS3_IOS_STATUS_PAD_UNAVAILABLE;
	}

	// Not through with_mouse, for Rpcs3IOS_MouseAim's reason; Button checks port 0's status itself.
	handler->Button(0, get_mouse_button_code(button - 1), pressed == 1);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_SetPressureModifier(int32_t held)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPressureModifier");
	}

	if (held != 0 && held != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	g_pressure_modifier.store(held == 1);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_InputConfigDump(const char* serial, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InputConfigDump");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	std::string json;
	const Rpcs3IOS_Status status = input_config_status(armsx3::bridge::input_config::dump(serial != nullptr ? serial : "", json));
	if (status == RPCS3_IOS_STATUS_OK)
	{
		sink(context, json.c_str(), static_cast<uint32_t>(json.size()));
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InputConfigDump finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_InputConfigSet(const char* serial, int32_t player, const char* key, const char* value)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InputConfigSet");
	}

	if (key == nullptr || value == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const Rpcs3IOS_Status status = input_config_status(armsx3::bridge::input_config::set(serial != nullptr ? serial : "",
		player, key, value));
	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InputConfigSet finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_InputConfigReset(const char* serial, int32_t player)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InputConfigReset");
	}

	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const Rpcs3IOS_Status status = input_config_status(armsx3::bridge::input_config::reset(serial != nullptr ? serial : "",
		player));
	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InputConfigReset finished with status %d.\n", static_cast<int>(status));
	return status;
}

// The ordinals are the core's input::product_type, stored as the player's three device keys.
static_assert(static_cast<int>(input::product_type::udraw_gametablet) == 14,
	"Rpcs3IOS_SetPlayerDevice documents ordinals 0-14; the core's product list changed, so the app's table must too");

Rpcs3IOS_Status Rpcs3IOS_SetPlayerDevice(const char* serial, int32_t player, int32_t product_type)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SetPlayerDevice");
	}

	if (player < 0 || player > 3 || product_type < 0 || product_type > 14)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const std::string scope = serial != nullptr ? serial : "";
	if (!scope.empty() && !serial_component_ok(scope))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const input::product_info product = input::get_product_info(static_cast<input::product_type>(product_type));
	const Rpcs3IOS_Status status = input_config_status(armsx3::bridge::input_config::set_many(scope, player, {
		{"Device Class Type", std::to_string(product.class_id)},
		{"Vendor ID", std::to_string(product.vendor_id)},
		{"Product ID", std::to_string(product.product_id)},
	}));
	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SetPlayerDevice finished with status %d.\n", static_cast<int>(status));
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_GetPlayerDevice(const char* serial, int32_t player, int32_t* product_type)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetPlayerDevice");
	}

	if (product_type == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (player < 0 || player > 3)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const std::string scope = serial != nullptr ? serial : "";
	if (!scope.empty() && !serial_component_ok(scope))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// The engine's own reads, each a number the core's node printed (it accepts nothing else), so stoul cannot throw.
	std::string class_id, vendor_id, product_id;
	armsx3::bridge::input_config::outcome outcome = armsx3::bridge::input_config::get(scope, player, "Device Class Type", class_id);
	if (outcome == armsx3::bridge::input_config::outcome::ok)
	{
		outcome = armsx3::bridge::input_config::get(scope, player, "Vendor ID", vendor_id);
	}
	if (outcome == armsx3::bridge::input_config::outcome::ok)
	{
		outcome = armsx3::bridge::input_config::get(scope, player, "Product ID", product_id);
	}
	const Rpcs3IOS_Status status = input_config_status(outcome);
	if (status == RPCS3_IOS_STATUS_OK)
	{
		*product_type = static_cast<int32_t>(stored_product(static_cast<u32>(std::stoul(class_id)),
			static_cast<u16>(std::stoul(vendor_id)), static_cast<u16>(std::stoul(product_id))).type);
	}
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_PortalLoad(int32_t portal, int32_t slot, const char* path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PortalLoad");
	}

	if (path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (portal < 0 || portal > 3 || slot < 0 || slot >= k_portals[portal].slots)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// The app hands in only copies in its own Figures folder.
	const std::size_t length = strnlen(path, 4096);
	const std::string name(path, length);
	if (length == 0 || length >= 4096 || path[0] != '/' || (name + "/").find("/../") != std::string::npos)
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	// Read-write because the core writes the game's changes back, and locked so one file cannot sit on two slots.
	const u64 bytes = k_portals[portal].bytes;
	std::array<u8, 0x40 * 0x10> raw{};
	fs::file file(name, fs::read + fs::write + fs::lock);
	if (!file || file.size() != bytes || file.read(raw.data(), bytes) != bytes)
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	std::lock_guard lock(g_portal_mutex);
	clear_portal_slot(portal, slot);

	portal_figure figure{};
	switch (portal)
	{
	case 0:
	{
		// skylander_dialog: the ID at 0x10 and the variant at 0x1C, little-endian.
		figure.id = u32{raw[0x10]} | u32{raw[0x11]} << 8;
		figure.var = static_cast<u16>(raw[0x1C] | raw[0x1D] << 8);
		figure.handle = g_skyportal.load_skylander(raw, std::move(file));
		break;
	}
	case 1:
	{
		std::array<u8, 0x14 * 0x10> buf{};
		std::memcpy(buf.data(), raw.data(), buf.size());
		figure.handle = static_cast<u8>(slot);
		figure.id = g_infinitybase.load_figure(buf, std::move(file), static_cast<u8>(slot));
		break;
	}
	case 2:
	{
		std::array<u8, 0x2D * 0x04> buf{};
		std::memcpy(buf.data(), raw.data(), buf.size());
		figure.handle = static_cast<u8>(slot);
		figure.id = g_dimensionstoypad.load_figure(buf, std::move(file), k_dimensions_pads[slot], static_cast<u8>(slot), true);
		break;
	}
	default:
	{
		std::array<u8, 0x14 * 0x10> buf{};
		std::memcpy(buf.data(), raw.data(), buf.size());
		// kamen_rider_dialog: the ID at 0x18, little-endian.
		figure.id = u32{buf[0x18]} | u32{buf[0x19]} << 8 | u32{buf[0x1A]} << 16 | u32{buf[0x1B]} << 24;
		figure.handle = g_ridergate.load_figure(buf, std::move(file));
		// The core's gate answers 0xFF past seven figures; desktop would later index the core's array with it.
		if (figure.handle == 0xFF)
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		break;
	}
	}

	g_portal_slots[portal][slot] = figure;
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_PortalRemove(int32_t portal, int32_t slot)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PortalRemove");
	}

	if (portal < 0 || portal > 3 || slot < 0 || slot >= k_portals[portal].slots)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::lock_guard lock(g_portal_mutex);
	clear_portal_slot(portal, slot);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_PortalList(int32_t portal, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PortalList");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (portal < 0 || portal > 3)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// Built by hand: numbers only.
	std::string json = "[";
	{
		std::lock_guard lock(g_portal_mutex);
		for (int32_t slot = 0; slot < k_portals[portal].slots; slot++)
		{
			json += slot == 0 ? "" : ",";
			json += "{\"slot\":" + std::to_string(slot);
			if (const std::optional<portal_figure>& held = g_portal_slots[portal][slot])
			{
				json += ",\"id\":" + std::to_string(held->id);
				if (portal == 0)
				{
					json += ",\"var\":" + std::to_string(held->var);
				}
			}
			json += "}";
		}
	}
	json += ']';

	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

// The file is created exclusively, so a figure that already exists is never overwritten.
Rpcs3IOS_Status Rpcs3IOS_PortalCreate(int32_t portal, int32_t figure_id, int32_t variant, const char* path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_PortalCreate");
	}

	if (path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// Infinity's series is 1-3, as infinity_dialog checks it.
	const bool in_range = (portal == 0 && figure_id >= 0 && figure_id <= 0xFFFF && variant >= 0 && variant <= 0xFFFF) ||
		(portal == 1 && figure_id >= 0 && figure_id <= 0xFFFFFF && variant >= 1 && variant <= 3) ||
		(portal == 2 && figure_id >= 0 && figure_id <= 0xFFFF) || (portal == 3 && figure_id >= 0);
	if (!in_range)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// The load's path rule: absolute, no `..` component, under 4096 bytes.
	const std::size_t length = strnlen(path, 4096);
	const std::string name(path, length);
	if (length == 0 || length >= 4096 || path[0] != '/' || (name + "/").find("/../") != std::string::npos)
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	namespace pf = armsx3::bridge::portal_figures;
	std::vector<u8> image;
	bool made = false;
	switch (portal)
	{
	case 0:
	{
		std::array<u8, 0x40 * 0x10> buf{};
		made = pf::create_blank_skylander(buf, static_cast<u16>(figure_id), static_cast<u16>(variant));
		image.assign(buf.begin(), buf.end());
		break;
	}
	case 1:
	{
		std::array<u8, 0x14 * 0x10> buf{};
		made = pf::create_blank_infinity(buf, static_cast<u32>(figure_id), static_cast<u8>(variant));
		image.assign(buf.begin(), buf.end());
		break;
	}
	case 2:
	{
		std::array<u8, 0x2D * 0x04> buf{};
		made = dimensions_toypad::create_blank_character(buf, static_cast<u16>(figure_id));
		image.assign(buf.begin(), buf.end());
		break;
	}
	default:
	{
		std::array<u8, 0x14 * 0x10> buf{};
		made = pf::create_blank_kamen_rider(buf, static_cast<u32>(figure_id));
		image.assign(buf.begin(), buf.end());
		break;
	}
	}

	if (!made)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	fs::file file(name, fs::write_new);
	const bool written = file && file.write(image.data(), image.size()) == image.size();
	if (!written)
	{
		// Only a file this call created is removed; an existing one failed the exclusive open and is left alone.
		if (file)
		{
			file.close();
			fs::remove_file(name);
		}
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	file.close();
	return RPCS3_IOS_STATUS_OK;
}
