// SPDX-License-Identifier: GPL-2.0-only
// Every emu_callbacks field as an X-macro, in declaration order; tests/test_callback_table_order.py holds it to the header.

#pragma once

// A new upstream field goes here and in callbacks.cpp at the same position, with a classification.
#define ARMSX3_BRIDGE_CALLBACK_LIST(X) \
	X(call_from_main_thread) \
	X(on_run) \
	X(on_pause) \
	X(on_resume) \
	X(on_stop) \
	X(on_ready) \
	X(on_missing_fw) \
	X(on_emulation_stop_no_response) \
	X(on_save_state_progress) \
	X(enable_disc_eject) \
	X(enable_disc_insert) \
	X(try_to_quit) \
	X(handle_taskbar_progress) \
	X(init_kb_handler) \
	X(init_mouse_handler) \
	X(init_pad_handler) \
	X(update_emu_settings) \
	X(save_emu_settings) \
	X(close_gs_frame) \
	X(get_gs_frame) \
	X(replace_gs_frame) \
	X(get_camera_handler) \
	X(get_music_handler) \
	X(init_gs_render) \
	X(get_audio) \
	X(get_audio_enumerator) \
	X(get_msg_dialog) \
	X(get_osk_dialog) \
	X(get_save_dialog) \
	X(get_sendmessage_dialog) \
	X(get_recvmessage_dialog) \
	X(get_trophy_notification_dialog) \
	X(get_localized_string) \
	X(get_localized_u32string) \
	X(get_localized_setting) \
	X(get_photo_path) \
	X(play_sound) \
	X(get_image_info) \
	X(get_scaled_image) \
	X(resolve_path) \
	X(resolve_path_may_not_exist) \
	X(get_font_dirs) \
	X(on_install_pkgs) \
	X(add_breakpoint) \
	X(display_sleep_control_supported) \
	X(enable_display_sleep) \
	X(check_microphone_permissions) \
	X(make_video_source) \
	X(enable_gamemode) \
	X(get_database_config)

