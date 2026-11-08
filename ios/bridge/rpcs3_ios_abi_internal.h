// SPDX-License-Identifier: GPL-2.0-only
// What the C ABI parts share: the includes, and the state and helpers more than one part uses.
#pragma once

#include "rpcs3_ios_abi.h"
#include "ios/core/ios_stderr_witness.h"

#include "callbacks.h"
#include "diag_log.h"
#include "disc_swap.h" // the disc-swap flags the guest's cellGame sets
#include "photo_export.h" // the photos get_photo_path named, waiting for Photos
#include "portal_figures.h" // the blank-figure creators from desktop's portal dialogs
#include "rpcn_bridge.h"
#include "save_progress.h" // the save handles on_save_state_progress parks
#include "ios_video_sink.h" // the recording sink the core's video provider feeds
#include "game_scan.h"
#include "icon_link.h" // link_icon_tree, the config Icons/ui link to the bundle's icon tree
#include "host_services.h"
#include "input_config_engine.h"
#include "jit_state.h"
#include "library_scan.h"
#include "settings_engine.h"
#include "title_config.h"

#include "Emu/System.h"
#include "Emu/emu_callbacks.h"
#include "Emu/GameInfo.h"
#include "Emu/game_enumeration.h"
#include "Emu/system_config.h"
#include "Emu/IdManager.h"
#include "Emu/NP/rpcn_config.h" // rpcn_arm_memory_secrets, the in-memory RPCN secret slot
#include "Emu/RSX/RSXThread.h"
#include "Emu/system_config_types.h"
#include "Emu/system_utils.hpp"
#include "Emu/Io/pad_config.h"
// The four portal globals.
#include "Emu/Io/Skylander.h"
#include "Emu/Io/Infinity.h"
#include "Emu/Io/Dimensions.h"
#include "Emu/Io/KamenRider.h"
#include "Emu/Cell/Modules/cellGem.h" // gem_get_sphere_rgb
#include "Emu/Cell/timers.hpp" // get_system_time
#include "Emu/Audio/AudioBackend.h" // DEFAULT_AUDIO_SAMPLING_RATE
#include "Emu/Io/recording_config.h" // g_cfg_recording
#include "util/video_provider.h"
#include "Emu/VFS.h"
#include "Emu/vfs_config.h"
#include "Emu/savestate_utils.hpp"
#include "Crypto/unself.h"
#include "Crypto/unpkg.h"
#include "Input/pad_thread.h"
#include "Input/product_info.h" // input::get_product_info
#include "Loader/ISO.h"
#include "Loader/disc.h" // disc::get_disc_type
#include "Loader/PSF.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Utilities/File.h"
#include "Utilities/Thread.h"
#include "Utilities/bin_patch.h"
#include "rpcs3_version.h"
#include "util/serialization.hpp"
#include "util/sysinfo.hpp"

// Included directly, because an undefined TARGET_OS_IPHONE in a #if is silently 0.
#include <TargetConditionals.h>
#include "Input/virtual_pad_handler.h"
#include "Emu/Io/KeyboardHandler.h"
#include "ios_mouse_handler.h"
#include "ios_keymap.h"
// Outside the iPhone block, because the macOS leg's bridge reads the pool state too.
#include "ios/core/ios_jit_pool_full.h"
#if TARGET_OS_IPHONE
#include "ios/core/ios_jit_dual_map.h"
// iOS only: ios_frame_impl.cpp is built for iOS alone, so the macOS leg's save writes no thumbnail.
#include "ios_frame.h"
#endif

// pthread_main_np rather than [NSThread isMainThread], because AppKit cannot be imported next to the core headers.
#include <pthread.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

// Defined in RSXThread.cpp; Rpcs3IOS_Pause clears it.
extern atomic_t<bool> g_user_asked_for_screenshot;
// Defined in util/video_provider.cpp; the recording entries move it as desktop's toggle_recording does.
extern atomic_t<recording_mode> g_recording_mode;

namespace armsx3::bridge::abi_internal
{
	extern std::atomic<bool> g_initialized;
	extern std::atomic<bool> g_callbacks_installed;
	extern std::atomic<bool> g_pressure_modifier;
	extern std::array<std::atomic<bool>, CELL_PAD_MAX_PORT_NUM> g_port_connected;
	Rpcs3IOS_Status refuse_main_thread(const char* entry_point);
	Rpcs3IOS_Status halt_reason();
	extern std::atomic<unsigned long long> g_fatal_baseline;
	extern std::atomic<unsigned long long> g_watchdog_baseline;
	Rpcs3IOS_Status halt_status(const char* entry_point);

	// Indexed by RPCS3_IOS_PAD_BUTTON_* value; each entry is the core's offset and code for that button.
	struct pad_button_code
	{
		u32 offset;
		u32 code;
	};
	input::product_info stored_product(u32 class_id, u16 vendor_id, u16 product_id);
	armsx3::jit_state::verdict current_jit_verdict();
	std::string boot_serial(const std::string& content);
	bool core_accepts(cfg_root& probe, const std::vector<std::string>& path, const armsx3::bridge::title_config::item& leaf);
	std::string cached_config_db_path();
	armsx3::bridge::settings::db_layer_view db_layer(const std::string& serial);

	// A per-boot layered copy of the product file carries this suffix; strip_layered_suffix recovers the original.
	constexpr std::string_view layered_config_suffix = ".title.yml";
	std::string strip_layered_suffix(std::string config);
	std::string title_boot_config(const std::string& content, const std::string& config);
	extern std::mutex g_boot_config_mutex;
	extern std::string g_boot_config_title;
	extern std::string g_boot_config;
	extern std::string g_boot_config_product;
	void refresh_boot_record();
	Rpcs3IOS_Status boot_with(const char* content_path, const std::string& product_config);
	bool serial_component_ok(const std::string& serial);
	std::string game_dir(const std::string& serial);
	std::string patch_json_quote(std::string_view v);
	Rpcs3IOS_Status finished_status(const char* entry_point, Rpcs3IOS_Status status);
}
