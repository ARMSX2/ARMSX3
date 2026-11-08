// SPDX-License-Identifier: GPL-2.0-only
// The one emu_callbacks table the app and the macOS leg share; plain C++, since AppKit and the RSX headers clash.

#include "callbacks.h"
#include "host_services.h"
#include "classification.h"
#include "localized_strings.h"
#include "settings_engine.h"
#include "disc_swap.h"
#include "photo_export.h"
#include "save_progress.h"

#include "Emu/System.h"
#include "Emu/emu_callbacks.h"
#include "Emu/system_config.h"
#include "Emu/system_utils.hpp"
#include "Emu/IdManager.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellOskDialog.h"
#include "Emu/Cell/Modules/cellSaveData.h"
#include "Emu/Cell/Modules/cellSysutil.h"
#include "Emu/Cell/Modules/sceNp.h"
#include "Emu/Cell/Modules/sceNpTrophy.h"
#include "Emu/RSX/Overlays/overlay_manager.h"
#include "Emu/RSX/Overlays/overlay_save_dialog.h"
#include "Emu/RSX/Overlays/overlay_trophy_notification.h"
#include "util/video_source.h"
#include "Input/pad_thread.h"
#include "Input/virtual_keyboard_handler.h"
#include "ios_mouse_handler.h"
#include "Utilities/Thread.h"
#include "util/logs.hpp"
#include "util/fixed_typemap.hpp"

#include <atomic>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

// Included directly: an undefined TARGET_OS_IPHONE in a #if is silently 0.
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
// ios_frame.mm is built for iOS alone.
#include "ios_frame.h"
#endif

LOG_CHANNEL(bridge_log, "BRIDGE");

namespace armsx3::bridge
{
	namespace
	{
		// A counter, not a flag: run_self_check invokes on_ready too, so a flag would read true on a boot that never got ready.
		std::atomic<u64> g_ready_observations{0};

		std::atomic<u64> g_spu_savestate_refusals{0};

		// The core looks this text up when it re-signs in after a dropped session, so counting it here needs no core edit.
		std::atomic<u64> g_rpcn_relogins{0};

		std::atomic<unsigned long long> g_stop_no_response_reports{0};

		void note_localized(localized_string_id id)
		{
			if (id == localized_string_id::SAVESTATE_FAILED_DUE_TO_MISSING_SPU_SETTING)
			{
				g_spu_savestate_refusals.fetch_add(1, std::memory_order_relaxed);
			}
			else if (id == localized_string_id::RPCN_SUCCESS_LOGGED_ON)
			{
				g_rpcn_relogins.fetch_add(1, std::memory_order_relaxed);
			}
		}

		// The core's own PS3-style pop-up; it logs nothing because a trophy name is game text.
		class ios_trophy_notification final : public TrophyNotificationBase
		{
		public:
			s32 ShowTrophyNotification(const SceNpTrophyDetails& trophy, const std::vector<uchar>& trophy_icon_buffer) override
			{
				if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>())
				{
					auto popup = std::make_shared<rsx::overlays::trophy_notification>();
					return manager->add(popup, false)->show(trophy, trophy_icon_buffer);
				}

				return 0;
			}
		};

		// The core's PS3-style save list, which upstream's Qt dialog shows first; without it every list save is cancelled.
		class ios_save_dialog final : public SaveDialogBase
		{
		public:
			s32 ShowSaveDataList(const std::string& base_dir, std::vector<SaveDataEntry>& save_entries, s32 focused, u32 op, vm::ptr<CellSaveDataListSet> listSet, bool enable_overlay) override
			{
				auto manager = g_fxo->try_get<rsx::overlays::display_manager>();
				if (!manager)
				{
					return rsx::overlays::user_interface::selection_code::canceled;
				}

				const bool use_end = sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_BEGIN, 0) >= 0;
				const s32 result = manager->create<rsx::overlays::save_dialog>()->show(base_dir, save_entries, focused, op, listSet, enable_overlay);
				if (use_end)
				{
					sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_END, 0);
				}

				return result == rsx::overlays::user_interface::selection_code::error ? rsx::overlays::user_interface::selection_code::canceled : result;
			}
		};

		// A function-local static, so there is no static initialisation order question with rpcs3_emu.
		host_services& mutable_host_services()
		{
			static host_services s_services;
			return s_services;
		}

		// A named function because a #if inside the table's macro argument is undefined; on_run also covers the core's restarts.
		void notify_pause_owner_of_run()
		{
#if TARGET_OS_IPHONE
			armsx3::ios_frame::note_boot_accepted();
#endif
		}
	}

	void set_host_services(host_services services)
	{
		mutable_host_services() = std::move(services);
	}

	const host_services& current_host_services()
	{
		return mutable_host_services();
	}

	// Defined here because this file owns the BRIDGE channel; notice, because reaching a stub is not a fault.
	void report_stub_reached(const char* classification, int phase, const char* field)
	{
		if (phase >= 0)
		{
			bridge_log.notice("callback reached: %s is %s, owed to phase %d -- this is the first "
			                  "time the emulator has called it", field, classification, phase);
			return;
		}

		bridge_log.notice("callback reached: %s is %s -- nothing is planned for it on iOS, and the "
		                  "emulator has now called it, which is when that decision wants "
		                  "re-examining", field, classification);
	}

	// Designated initializers in emu_callbacks' own field order, so an upstream field addition is a compile error here.
	emu_callbacks make_callbacks(const host_services& services)
	{
		return emu_callbacks{
			// A non-null `wake_up` means the caller blocks on the result; null means post and return.
			.call_from_main_thread = ARMSX3_REAL(call_from_main_thread, services.call_from_main_thread),
			.on_run = ARMSX3_OWNED(10, on_run, [](bool) { notify_pause_owner_of_run(); }),
			.on_pause = ARMSX3_OWNED(6, on_pause, [] {}),
			.on_resume = ARMSX3_OWNED(6, on_resume, [] {}),
			.on_stop = ARMSX3_OWNED(6, on_stop, [] {}),
			// relaxed is enough: the caller brackets the boot with two reads of this one counter.
			.on_ready = ARMSX3_OWNED(6, on_ready, []
			{
				g_ready_observations.fetch_add(1, std::memory_order_relaxed);
			}),
			.on_missing_fw = ARMSX3_OWNED(7, on_missing_fw, []
			{
				bridge_log.error("the emulator reports missing firmware. Install it first: "
				                 "ios/scripts/build-macos-leg.sh --stage a --boot does this "
				                 "with ARMSX3_PS3_FIRMWARE.");
			}),
			// Counted rather than aborted as upstream headless does, so a stalled stop still leaves a record.
			.on_emulation_stop_no_response = ARMSX3_OWNED(6, on_emulation_stop_no_response, [](std::shared_ptr<atomic_t<bool>>, int seconds)
			{
				armsx3::bridge::stop_no_response_note(seconds);
				bridge_log.error("emulation has not stopped after %d seconds", seconds);
			}),
			// Runs on the core's stop watchdog thread, so it only parks the handles and never blocks.
			.on_save_state_progress = ARMSX3_REAL(on_save_state_progress, [](std::shared_ptr<atomic_t<bool>> closed,
				stx::shared_ptr<utils::serial> ar, stx::atomic_ptr<std::string>* where, std::shared_ptr<void> init)
			{
				if (!closed || !ar || !where || !init)
				{
					return;
				}

				armsx3::bridge::park_save_progress(std::move(closed), std::move(ar), where, std::move(init));
			}),
			// cellGame calls these on a guest thread, so each body only stores the flag.
			.enable_disc_eject = ARMSX3_REAL(enable_disc_eject, [](bool enabled) { armsx3::bridge::g_disc_eject_enabled.store(enabled); }),
			.enable_disc_insert = ARMSX3_REAL(enable_disc_insert, [](bool enabled) { armsx3::bridge::g_disc_insert_enabled.store(enabled); }),
			.try_to_quit = ARMSX3_OWNED(6, try_to_quit, [](bool, std::function<void()> on_exit)
			{
				if (on_exit) on_exit();
				return true;
			}),
			// There is no taskbar on Apple platforms.
			.handle_taskbar_progress = ARMSX3_DELIBERATE(handle_taskbar_progress, [](s32, s32) {}),
			.init_kb_handler = ARMSX3_REAL(init_kb_handler, []
			{
				switch (g_cfg.io.keyboard.get())
				{
				case keyboard_handler::null:
					ensure(g_fxo->init<KeyboardHandlerBase, NullKeyboardHandler>(
						Emu.DeserialManager()));
					break;
				case keyboard_handler::basic:
					ensure(g_fxo->init<KeyboardHandlerBase, virtual_keyboard_handler>(
						Emu.DeserialManager()));
					break;
				}
			}),
			.init_mouse_handler = ARMSX3_REAL(init_mouse_handler, []
			{
				switch (g_cfg.io.mouse.get())
				{
				case mouse_handler::null:
					ensure(g_fxo->init<MouseHandlerBase, NullMouseHandler>(
						Emu.DeserialManager()));
					break;
				// `Raw` is the desktop raw-input handler, which does not build here, so it gets the same handler as `Basic`.
				case mouse_handler::raw:
				case mouse_handler::basic:
					ensure(g_fxo->init<MouseHandlerBase, ios_mouse_handler>(
						Emu.DeserialManager()));
					break;
				}
			}),
			.init_pad_handler = ARMSX3_REAL(init_pad_handler, [](std::string_view)
			{
				ensure(g_fxo->init<named_thread<pad_thread>>(nullptr, nullptr, ""));
			}),
			// Both run on the caller's thread and do nothing while stopped, never through CallFromMainThread.
			.update_emu_settings = ARMSX3_REAL(update_emu_settings, [] { ::armsx3::bridge::settings::apply_all_side_effects(); }),
			.save_emu_settings = ARMSX3_REAL(save_emu_settings, [] { ::armsx3::bridge::settings::on_save_emu_settings(); }),
			.close_gs_frame = ARMSX3_OWNED(4, close_gs_frame, [] {}),
			.get_gs_frame = ARMSX3_REAL(get_gs_frame, services.get_gs_frame),
			// iOS forces Vulkan, so the frame is reused.
			.replace_gs_frame = ARMSX3_REAL(replace_gs_frame, services.get_gs_frame),
			// `Qt` is the core's real-camera value; every other value gets the null handler.
			.get_camera_handler = ARMSX3_REAL(get_camera_handler, [camera = services.get_camera_handler]() -> std::shared_ptr<camera_handler_base>
			{
				if (g_cfg.io.camera.get() == camera_handler::qt && camera)
				{
					return camera();
				}
				return std::make_shared<null_camera_handler>();
			}),
			.get_music_handler = ARMSX3_REAL(get_music_handler, [] { return std::make_shared<null_music_handler>(); }),
			.init_gs_render = ARMSX3_REAL(init_gs_render, services.init_gs_render),
			.get_audio = ARMSX3_REAL(get_audio, services.get_audio),
			.get_audio_enumerator = ARMSX3_OWNED(4, get_audio_enumerator, [](u64) -> std::shared_ptr<audio_device_enumerator>
			{
				return nullptr;
			}),
			// A callable returning nullptr is safe; an empty std::function throws bad_function_call.
			.get_msg_dialog = ARMSX3_OWNED(8, get_msg_dialog, []() -> std::shared_ptr<MsgDialogBase> { return nullptr; }),
			.get_osk_dialog = ARMSX3_OWNED(8, get_osk_dialog, []() -> std::shared_ptr<OskDialogBase> { return nullptr; }),
			.get_save_dialog = ARMSX3_REAL(get_save_dialog, []() -> std::unique_ptr<SaveDialogBase> { return std::make_unique<ios_save_dialog>(); }),
			// PSN basic-messaging dialogs, which this port has no overlay for.
			.get_sendmessage_dialog = ARMSX3_DELIBERATE(get_sendmessage_dialog, []() -> std::shared_ptr<SendMessageDialogBase>
			{
				return nullptr;
			}),
			.get_recvmessage_dialog = ARMSX3_DELIBERATE(get_recvmessage_dialog, []() -> std::shared_ptr<RecvMessageDialogBase>
			{
				return nullptr;
			}),
			.get_trophy_notification_dialog = ARMSX3_REAL(get_trophy_notification_dialog, []() -> std::unique_ptr<TrophyNotificationBase>
			{
				return std::make_unique<ios_trophy_notification>();
			}),
			// Upstream's English text from localized_emu.h; other languages are not translated.
			.get_localized_string = ARMSX3_REAL(get_localized_string, [](localized_string_id id, const char* args)
			{
				note_localized(id);
				return ::armsx3::bridge::localized_string(id, args);
			}),
			.get_localized_u32string = ARMSX3_REAL(get_localized_u32string, [](localized_string_id id, const char* args)
			{
				note_localized(id);
				return ::armsx3::bridge::localized_u32string(id, args);
			}),
			// Until friendly names exist, the home menu shows the setting's own option names.
			.get_localized_setting = ARMSX3_OWNED(10, get_localized_setting, [](const cfg::_base* node, u32 index)
			{
				const std::vector<std::string> names = node ? node->to_list() : std::vector<std::string>{};
				return index < names.size() ? names[index] : std::string{};
			}),
			// The core copies the photo there after this returns; before Emu.Init there is no /dev_hdd0, so it returns "".
			.get_photo_path = ARMSX3_REAL(get_photo_path, [](std::string_view filename)
			{
				const std::time_t now = std::time(nullptr);
				std::tm local{};
				localtime_r(&now, &local);
				std::string path = ::armsx3::bridge::photo_destination(filename, local);
				if (!path.empty())
				{
					::armsx3::bridge::push_exported_photo(path);
				}

				return path;
			}),
			// The core calls this from its RSX overlay threads, so it only hands the path to the player and returns.
			.play_sound = ARMSX3_REAL(play_sound, [play = services.play_sound](const std::string& path, std::optional<f32> volume)
			{
				if (path.empty() || !play)
				{
					return;
				}

				play(path, volume);
			}),
			.get_image_info = ARMSX3_OWNED(8, get_image_info, [](auto...) { return false; }),
			.get_scaled_image = ARMSX3_OWNED(8, get_scaled_image, [](auto...) { return false; }),
			// Canonicalise as upstream's frontend does, or the boot path drops a character; empty on failure, since System.cpp compares results.
			.resolve_path = ARMSX3_REAL(resolve_path, [](std::string_view arg) -> std::string
			{
				std::error_code ec;
				const std::filesystem::path resolved =
					std::filesystem::canonical(std::filesystem::path{arg}, ec);

				if (ec)
				{
					return std::string{};
				}

				return resolved.string();
			}),
			.resolve_path_may_not_exist = ARMSX3_REAL(resolve_path_may_not_exist, [](std::string_view arg) -> std::string
			{
				std::error_code ec;
				const std::filesystem::path resolved =
					std::filesystem::weakly_canonical(std::filesystem::path{arg}, ec);

				if (ec)
				{
					return std::string{arg};
				}

				return resolved.string();
			}),
			// Each directory is tried in turn, so one list serves iOS (WebFonts, Core) and macOS (Supplemental).
			.get_font_dirs = ARMSX3_REAL(get_font_dirs, []
			{
				return std::vector<std::string>{
					"/System/Library/Fonts/WebFonts/",
					"/System/Library/Fonts/Core/",
					"/System/Library/Fonts/Supplemental/",
					"/System/Library/Fonts/",
				};
			}),
			// Emulator::Load installs a disc's packages through this before it boots; false fails the boot.
			.on_install_pkgs = ARMSX3_REAL(on_install_pkgs, [](const std::vector<std::string>& pkgs, bool from_optical_drive)
			{
				for (const std::string& pkg : pkgs)
				{
					if (!rpcs3::utils::install_pkg(pkg, from_optical_drive))
					{
						bridge_log.error("on_install_pkgs: failed to install %s", pkg);
						return false;
					}
				}

				return true;
			}),
			// The debugger UI lives in the Qt frontend, which this port does not build.
			.add_breakpoint = ARMSX3_DELIBERATE(add_breakpoint, [](u32) {}),
			// Wrapped, not assigned, because the member is optional and empty means unsupported.
			.display_sleep_control_supported = ARMSX3_REAL(display_sleep_control_supported, [supported = static_cast<bool>(services.enable_display_sleep)]
			{
				return supported;
			}),
			.enable_display_sleep = ARMSX3_REAL(enable_display_sleep, [set = services.enable_display_sleep](bool enabled)
			{
				if (set)
				{
					set(enabled);
				}
			}),
			// shortcut: reads the permission without prompting and never switches the session back to playback; do that at stop if it costs game audio.
			.check_microphone_permissions = ARMSX3_REAL(check_microphone_permissions, [denied = services.microphone_denied, record = services.set_recording]
			{
				if (denied && denied())
				{
					g_cfg.audio.microphone_type.set(microphone_handler::null);
					return;
				}

				if (record)
				{
					record(true);
				}
			}),
			.make_video_source = ARMSX3_OWNED(4, make_video_source, []() -> std::unique_ptr<video_source> { return nullptr; }),
			// Feral GameMode is a Linux daemon; there is nothing to enable on Apple platforms.
			.enable_gamemode = ARMSX3_DELIBERATE(enable_gamemode, [](bool) {}),
			.get_database_config = ARMSX3_OWNED(7, get_database_config, [](const std::string&) { return std::string{}; }),
		};

	}

	u64 ready_observation_count()
	{
		return g_ready_observations.load(std::memory_order_relaxed);
	}

	u64 spu_savestate_refusal_count()
	{
		return g_spu_savestate_refusals.load(std::memory_order_relaxed);
	}

	u64 rpcn_relogins()
	{
		return g_rpcn_relogins.load(std::memory_order_relaxed);
	}

	void stop_no_response_note(int seconds)
	{
		g_stop_no_response_reports.fetch_add(1, std::memory_order_relaxed);
	}

	unsigned long long stop_no_response_reports()
	{
		return g_stop_no_response_reports.load(std::memory_order_relaxed);
	}
}
