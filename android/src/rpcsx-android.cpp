#include "Crypto/unpkg.h"
#include "Crypto/unself.h"
#include "Emu/Audio/Cubeb/CubebBackend.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/Cell/PPUAnalyser.h"
#include "Emu/Cell/SPURecompiler.h"
#include "Emu/IdManager.h"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/Null/NullPadHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/Io/pad_config_types.h"
#include "Emu/RSX/Null/NullGSRender.h"
#include "Emu/RSX/Overlays/overlay_manager.h"
#include "Emu/RSX/Overlays/overlay_save_dialog.h"
#include "Emu/RSX/Overlays/overlay_trophy_notification.h"
#include "Emu/RSX/RSXThread.h"
#include "Emu/RSX/GL/GLGSRender.h"
#include "Emu/RSX/VK/VKGSRender.h"
#include "Emu/savestate_utils.hpp"
// patch_engine, needed by the precompile path before ppu_load_exec.
#include "Utilities/bin_patch.h"
#include "Emu/localized_string_id.h"
#include "Emu/system_config.h"
#include "Emu/system_config_types.h"
#include "Emu/system_progress.hpp"
#include "Emu/system_utils.hpp"
#include "Emu/vfs_config.h"
#include "Input/ds3_pad_handler.h"
#include "Input/ds4_pad_handler.h"
#include "Input/dualsense_pad_handler.h"
#include "Input/hid_pad_handler.h"
#include "Input/pad_thread.h"
#include "Input/virtual_pad_handler.h"
#include "Loader/ISO.h"
#include "Loader/PSF.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Emu/Cell/lv2/sys_sync.h"
#include "dev/block_dev.hpp"
#include "dev/iso.hpp"
#include "hidapi_libusb.h"
#include "libusb.h"
#include "rpcs3_version.h"
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellSysutil.h"
#include "util/asm.hpp"
#include "Utilities/File.h"
#include "Utilities/JIT.h"
#include "Utilities/StrFmt.h"
#include "Utilities/StrUtil.h"
#include "Utilities/Thread.h"
#include "util/console.h"
#include "util/fixed_typemap.hpp"
#include "util/logs.hpp"
#include "util/serialization.hpp"
#include "util/sysinfo.hpp"
#include <Emu/Io/pad_config.h>
#include <Emu/RSX/GSFrameBase.h>
#include <Emu/System.h>
#include <Emu/Cell/Modules/cellSaveData.h>
#include <Emu/Cell/Modules/sceNpTrophy.h>

#include <algorithm>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <filesystem>
#include <functional>
#include <iterator>
#include <jni.h>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <vector>

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wreturn-type-c-linkage"

struct AtExit {
  std::function<void()> cb;
  ~AtExit() { cb(); }
};

static bool g_initialized;
static std::atomic<ANativeWindow *> g_native_window;

extern std::string g_android_executable_dir;
extern std::string g_android_config_dir;
extern std::string g_android_cache_dir;

static std::mutex g_virtual_pad_mutex;
// One per PS3 pad port. This was a single pad, which silently capped local
// co-op at one player: every port above the first registered a Pad the app
// could never address, so its input was assembled and then dropped.
static std::array<std::shared_ptr<Pad>, CELL_PAD_MAX_PORT_NUM> g_virtual_pads;

std::string g_input_config_override;
cfg_input_configurations g_cfg_input_configs;

LOG_CHANNEL(rpcsx_android, "ANDROID");

struct LogListener : logs::listener {
  LogListener() { logs::listener::add(this); }

  void log(u64 stamp, const logs::message &msg, std::string_view prefix,
           std::string_view text) override {
    int prio = 0;
    switch (static_cast<logs::level>(msg)) {
    case logs::level::always:
      prio = ANDROID_LOG_INFO;
      break;
    case logs::level::fatal:
      prio = ANDROID_LOG_FATAL;
      break;
    case logs::level::error:
      prio = ANDROID_LOG_ERROR;
      break;
    case logs::level::todo:
      prio = ANDROID_LOG_WARN;
      break;
    case logs::level::success:
      prio = ANDROID_LOG_INFO;
      break;
    case logs::level::warning:
      prio = ANDROID_LOG_WARN;
      break;
    case logs::level::notice:
      prio = ANDROID_LOG_DEBUG;
      break;
    case logs::level::trace:
      prio = ANDROID_LOG_VERBOSE;
      break;
    }

    // text is a string_view now (upstream changed the listener signature) and
    // string_view is not guaranteed null-terminated, so it cannot be handed
    // straight to a C API.
    __android_log_write(prio, "ARMSX3", std::string(text).c_str());
  }
} static g_androidLogListener;

struct GraphicsFrame : GSFrameBase {
  mutable ANativeWindow *activeNativeWindow = nullptr;
  mutable int width = 0;
  mutable int height = 0;

  ~GraphicsFrame() {
    if (activeNativeWindow != nullptr) {
      ANativeWindow_release(activeNativeWindow);
    }
  }

  ANativeWindow *getNativeWindow() const {
    ANativeWindow *result;
    while ((result = g_native_window.load()) == nullptr) [[unlikely]] {
      if (Emu.IsStopped()) {
        return activeNativeWindow;
      }

      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (result != activeNativeWindow) [[unlikely]] {
      ANativeWindow_acquire(result);

      if (activeNativeWindow != nullptr) {
        ANativeWindow_release(activeNativeWindow);
      }

      activeNativeWindow = result;

      width = ANativeWindow_getWidth(result);
      height = ANativeWindow_getHeight(result);
    }

    return result;
  }

  void close() override {}
  void reset() override {}
  bool shown() override { return true; }
  void hide() override {}
  void show() override {}
  void toggle_fullscreen() override {}

  // The Vulkan backend never touches these - it takes handle() and builds its
  // own surface and swapchain. The OpenGL backend does the opposite: it expects
  // the frame to own the context entirely (GLGSRender::on_init_thread calls
  // make_context, then set_current, and GLPresent calls flip). So all four are
  // real on the GL path and inert on the VK one.
  //
  // Sharing matters here. GLGSRender asks for one extra context per async
  // shader-compiler thread and every one of them has to share objects with the
  // primary, so the first context created is remembered and handed to
  // gl::es::create_context as the share group for all the rest.
  mutable void *primaryGlContext = nullptr;

  // RSX_GLES is a COMPILE-time flag: it says this build contains the GL backend,
  // not that the GL backend is the one running. Gating on it alone made every
  // hook below fire on Vulkan boots too, because GSRender::on_init_thread calls
  // make_context()/set_current() for whichever renderer is active.
  //
  // That was not harmless. set_current() runs eglCreateWindowSurface on the same
  // ANativeWindow the Vulkan swapchain already owns, and a native window can only
  // be connected to one consumer at a time -- so every Vulkan boot logged a fatal
  // "eglCreateWindowSurface failed (0x3003)" (EGL_BAD_ALLOC) and left a stray GLES
  // context behind.
  static bool usingGlRenderer() {
    return g_cfg.video.renderer == video_renderer::opengl;
  }

  void delete_context(draw_context_t ctx) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    if (ctx == primaryGlContext) {
      primaryGlContext = nullptr;
    }

    gl::es::destroy_context(ctx);
#endif
  }

  draw_context_t make_context() override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return nullptr;
    }

    void *ctx = gl::es::create_context(getNativeWindow(), primaryGlContext);
    if (ctx != nullptr && primaryGlContext == nullptr) {
      primaryGlContext = ctx;
    }

    return ctx;
#else
    return nullptr;
#endif
  }

  void set_current(draw_context_t ctx) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    gl::es::make_current(ctx, getNativeWindow());
#endif
  }

  void flip(draw_context_t ctx, bool skip_frame = false) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    if (!skip_frame) {
      gl::es::swap_buffers();
    }
#endif
  }
  int client_width() override { return width; }
  int client_height() override { return height; }
  f64 client_display_rate() override { return 30.f; }
  bool has_alpha() override {
    return ANativeWindow_getFormat(getNativeWindow()) ==
           WINDOW_FORMAT_RGBA_8888;
  }

  display_handle_t handle() const override { return getNativeWindow(); }

  bool can_consume_frame() const override { return false; }

  // Upstream takes the buffer by rvalue ref (it moves it); RPCSX's lvalue-ref
  // signature no longer overrides, which left GraphicsFrame abstract.
  void present_frame(std::vector<u8> &&data, u32 pitch, u32 width, u32 height,
                     bool is_bgra) const override {}
  void take_screenshot(std::vector<u8> &&sshot_data, u32 sshot_width,
                       u32 sshot_height, bool is_bgra) override {}
  // Added upstream after RPCSX forked. The Android UI draws its own FPS via
  // the perf overlay, so there is no host window title to update.
  void update_title(double fps = 0.0) override {}
};

void jit_announce(uptr, usz, std::string_view);

[[noreturn]] void report_fatal_error(std::string_view _text,
                                     bool is_html = false,
                                     bool include_help_text = true) {
  std::string buf;

  buf = std::string(_text);

  // Check if thread id is in string
  if (_text.find("\nThread id = "sv) == umax && !thread_ctrl::is_main()) {
    // Append thread id if it isn't already, except on main thread
    fmt::append(buf, "\n\nThread id = %u.", thread_ctrl::get_tid());
  }

  if (!g_tls_serialize_name.empty()) {
    fmt::append(buf, "\nSerialized Object: %s", g_tls_serialize_name);
  }

  const system_state state = Emu.GetStatus(false);

  if (state == system_state::stopped) {
    fmt::append(buf, "\nEmulation is stopped");
  } else {
    const std::string &name = Emu.GetTitleAndTitleID();
    fmt::append(buf, "\nTitle: \"%s\" (emulation is %s)",
                name.empty() ? "N/A" : name.data(),
                state == system_state::stopping ? "stopping" : "running");
  }

  fmt::append(buf, "\nBuild: \"%s\"", rpcs3::get_verbose_version());
  fmt::append(buf, "\nDate: \"%s\"", std::chrono::system_clock::now());

  __android_log_write(ANDROID_LOG_FATAL, "RPCS3", buf.c_str());

  jit_announce(0, 0, "");
  utils::trap();
  std::abort();
  std::terminate();
}

void qt_events_aware_op(int repeat_duration_ms,
                        std::function<bool()> wrapped_op) {
  /// ?????
}

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

static std::string fix_dir_path(std::string string) {
  if (!string.empty() && !string.ends_with('/')) {
    string += '/';
  }

  return string;
}

enum class FileType {
  Unknown,
  Pup,
  Pkg,
  Edat,
  Rap,
  Iso,
};

static FileType getFileType(const fs::file &file) {
  file.seek(0);
  if (PUPHeader pupHeader; file.read(pupHeader)) {
    if (pupHeader.magic == "SCEUF\0\0\0"_u64) {
      return FileType::Pup;
    }
  }

  file.seek(0);
  if (PKGHeader pkgHeader; file.read(pkgHeader)) {
    if (pkgHeader.pkg_magic == std::bit_cast<le_t<u32>>("\x7FPKG"_u32)) {
      return FileType::Pkg;
    }
  }

  file.seek(0);
  if (NPD_HEADER npdHeader; file.read(npdHeader)) {
    if (npdHeader.magic == "NPD\0"_u32) {
      return FileType::Edat;
    }
  }

  if (file.size() == 16) {
    return FileType::Rap;
  }

  if (iso_dev::open(std::make_unique<file_view_block_dev>(file))) {
    return FileType::Iso;
  }

  return FileType::Unknown;
}

#define MAKE_STRING(id, x) [int(localized_string_id::id)] = {x, U##x}

static std::pair<std::string, std::u32string> g_strings[] = {
    MAKE_STRING(RSX_OVERLAYS_COMPILING_SHADERS, "Compiling shaders"),
    MAKE_STRING(RSX_OVERLAYS_COMPILING_PPU_MODULES, "Compiling PPU Modules"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_YES, "Yes"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_NO, "No"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_OK, "OK"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_TITLE, "Save Dialog"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_DELETE, "Delete Save"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_LOAD, "Load Save"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_SAVE, "Save"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ACCEPT, "Enter"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_SPACE, "Space"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_BACKSPACE, "Backspace"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_SHIFT, "Shift"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ENTER_TEXT, "[Enter Text]"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ENTER_PASSWORD, "[Enter Password]"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_TITLE, "Select media"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_TITLE_PHOTO_IMPORT,
                "Select photo to import"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_EMPTY, "No media found."),
    MAKE_STRING(RSX_OVERLAYS_LIST_SELECT, "Enter"),
    MAKE_STRING(RSX_OVERLAYS_LIST_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_LIST_DENY, "Deny"),
    MAKE_STRING(CELL_OSK_DIALOG_TITLE, "On Screen Keyboard"),
    MAKE_STRING(
        CELL_OSK_DIALOG_BUSY,
        "The Home Menu can't be opened while the On Screen Keyboard is busy!"),
    MAKE_STRING(CELL_SAVEDATA_CB_BROKEN, "Error - Save data corrupted"),
    MAKE_STRING(CELL_SAVEDATA_CB_FAILURE, "Error - Failed to save or load"),
    MAKE_STRING(CELL_SAVEDATA_CB_NO_DATA, "Error - Save data cannot be found"),
    MAKE_STRING(CELL_SAVEDATA_NO_DATA, "There is no saved data."),
    MAKE_STRING(CELL_SAVEDATA_NEW_SAVED_DATA_TITLE, "New Saved Data"),
    MAKE_STRING(CELL_SAVEDATA_NEW_SAVED_DATA_SUB_TITLE,
                "Select to create a new entry"),
    MAKE_STRING(CELL_SAVEDATA_SAVE_CONFIRMATION,
                "Do you want to save this data?"),
    MAKE_STRING(CELL_SAVEDATA_AUTOSAVE, "Saving..."),
    MAKE_STRING(CELL_SAVEDATA_AUTOLOAD, "Loading..."),
    MAKE_STRING(
        CELL_CROSS_CONTROLLER_FW_MSG,
        "If your system software version on the PS Vita system is earlier than "
        "1.80, you must update the system software to the latest version."),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE, "Select Message"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE_INVITE, "Select Invite"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE_ADD_FRIEND, "Add Friend"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_FROM, "From:"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_SUBJECT, "Subject:"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE, "Select Message To Send"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE_INVITE, "Send Invite"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE_ADD_FRIEND, "Add Friend"),
    MAKE_STRING(RECORDING_ABORTED, "Recording aborted!"),
    MAKE_STRING(RPCN_NO_ERROR, "RPCN: No Error"),
    MAKE_STRING(RPCN_ERROR_INVALID_INPUT,
                "RPCN: Invalid Input (Wrong Host/Port)"),
    MAKE_STRING(RPCN_ERROR_WOLFSSL, "RPCN Connection Error: WolfSSL Error"),
    MAKE_STRING(RPCN_ERROR_RESOLVE, "RPCN Connection Error: Resolve Error"),
    MAKE_STRING(RPCN_ERROR_CONNECT, "RPCN Connection Error"),
    MAKE_STRING(RPCN_ERROR_LOGIN_ERROR,
                "RPCN Login Error: Identification Error"),
    MAKE_STRING(RPCN_ERROR_ALREADY_LOGGED,
                "RPCN Login Error: User Already Logged In"),
    MAKE_STRING(RPCN_ERROR_INVALID_LOGIN, "RPCN Login Error: Invalid Username"),
    MAKE_STRING(RPCN_ERROR_INVALID_PASSWORD,
                "RPCN Login Error: Invalid Password"),
    MAKE_STRING(RPCN_ERROR_INVALID_TOKEN, "RPCN Login Error: Invalid Token"),
    MAKE_STRING(RPCN_ERROR_INVALID_PROTOCOL_VERSION,
                "RPCN Misc Error: Protocol Version Error (outdated RPCS3?)"),
    MAKE_STRING(RPCN_ERROR_UNKNOWN, "RPCN: Unknown Error"),
    MAKE_STRING(RPCN_SUCCESS_LOGGED_ON, "Successfully logged on RPCN!"),
    MAKE_STRING(HOME_MENU_TITLE, "Home Menu"),
    MAKE_STRING(HOME_MENU_EXIT_GAME, "Exit Game"),
    MAKE_STRING(HOME_MENU_RESUME, "Resume Game"),
    MAKE_STRING(HOME_MENU_FRIENDS, "Friends"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUESTS, "Pending Friend Requests"),
    MAKE_STRING(HOME_MENU_FRIENDS_BLOCKED, "Blocked Users"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_ONLINE, "Online"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_OFFLINE, "Offline"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_BLOCKED, "Blocked"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUEST_SENT, "You sent a friend request"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUEST_RECEIVED,
                "Sent you a friend request"),
    MAKE_STRING(HOME_MENU_FRIENDS_REJECT_REQUEST, "Reject Request"),
    MAKE_STRING(HOME_MENU_FRIENDS_NEXT_LIST, "Next list"),
    MAKE_STRING(HOME_MENU_RESTART, "Restart Game"),
    MAKE_STRING(HOME_MENU_SETTINGS, "Settings"),
    MAKE_STRING(HOME_MENU_SETTINGS_SAVE, "Save custom configuration?"),
    MAKE_STRING(HOME_MENU_SETTINGS_SAVE_BUTTON, "Save"),
    MAKE_STRING(HOME_MENU_SETTINGS_DISCARD,
                "Discard the current settings' changes?"),
    MAKE_STRING(HOME_MENU_SETTINGS_DISCARD_BUTTON, "Discard"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO, "Audio"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_MASTER_VOLUME, "Master Volume"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BACKEND, "Audio Backend"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BUFFERING, "Enable Buffering"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BUFFER_DURATION,
                "Desired Audio Buffer Duration"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_TIME_STRETCHING,
                "Enable Time Stretching"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_TIME_STRETCHING_THRESHOLD,
                "Time Stretching Threshold"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO, "Video"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_FRAME_LIMIT, "Frame Limit"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_ANISOTROPIC_OVERRIDE,
                "Anisotropic Filter Override"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_OUTPUT_SCALING, "Output Scaling"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_RCAS_SHARPENING,
                "FidelityFX CAS Sharpening Intensity"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_STRETCH_TO_DISPLAY,
                "Stretch To Display Area"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT, "Input"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_BACKGROUND_INPUT,
                "Background Input Enabled"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_KEEP_PADS_CONNECTED,
                "Keep Pads Connected"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_SHOW_PS_MOVE_CURSOR,
                "Show PS Move Cursor"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_CAMERA_FLIP, "Camera Flip"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_PAD_MODE, "Pad Handler Mode"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_PAD_SLEEP, "Pad Handler Sleep"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_FAKE_MOVE_ROTATION_CONE_H,
                "Fake PS Move Rotation Cone (Horizontal)"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_FAKE_MOVE_ROTATION_CONE_V,
                "Fake PS Move Rotation Cone (Vertical)"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED, "Advanced"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_PREFERRED_SPU_THREADS,
                "Preferred SPU Threads"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_MAX_CPU_PREEMPTIONS,
                "Max Power Saving CPU-Preemptions"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_ACCURATE_RSX_RESERVATION_ACCESS,
                "Accurate RSX reservation access"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_SLEEP_TIMERS_ACCURACY,
                "Sleep Timers Accuracy"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_MAX_SPURS_THREADS,
                "Max SPURS Threads"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_DRIVER_WAKE_UP_DELAY,
                "Driver Wake-Up Delay"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_VBLANK_FREQUENCY,
                "VBlank Frequency"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_VBLANK_NTSC, "VBlank NTSC Fixup"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS, "Overlays"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_TROPHY_POPUPS,
                "Show Trophy Popups"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_RPCN_POPUPS,
                "Show RPCN Popups"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_SHADER_COMPILATION_HINT,
                "Show Shader Compilation Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_PPU_COMPILATION_HINT,
                "Show PPU Compilation Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_AUTO_SAVE_LOAD_HINT,
                "Show Autosave/Autoload Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_PRESSURE_INTENSITY_TOGGLE_HINT,
                "Show Pressure Intensity Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_ANALOG_LIMITER_TOGGLE_HINT,
                "Show Analog Limiter Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_MOUSE_AND_KB_TOGGLE_HINT,
                "Show Mouse And Keyboard Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY, "Performance Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE,
                "Enable Performance Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE_FRAMERATE_GRAPH,
                "Enable Framerate Graph"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE_FRAMETIME_GRAPH,
                "Enable Frametime Graph"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_DETAIL_LEVEL,
                "Detail level"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMERATE_DETAIL_LEVEL,
                "Framerate Graph Detail Level"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMETIME_DETAIL_LEVEL,
                "Frametime Graph Detail Level"),
    MAKE_STRING(
        HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMERATE_DATAPOINT_COUNT,
        "Framerate Datapoints"),
    MAKE_STRING(
        HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMETIME_DATAPOINT_COUNT,
        "Frametime Datapoints"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_UPDATE_INTERVAL,
                "Metrics Update Interval"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_POSITION, "Position"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_CENTER_X,
                "Center Horizontally"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_CENTER_Y,
                "Center Vertically"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_MARGIN_X,
                "Horizontal Margin"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_MARGIN_Y,
                "Vertical Margin"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FONT_SIZE, "Font Size"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_OPACITY, "Opacity"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG, "Debug"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_OVERLAY, "Debug Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_INPUT_OVERLAY, "Input Debug Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_DISABLE_VIDEO_OUTPUT,
                "Disable Video Output"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_TEXTURE_LOD_BIAS,
                "Texture LOD Bias Addend"),
    MAKE_STRING(HOME_MENU_SCREENSHOT, "Take Screenshot"),
    MAKE_STRING(HOME_MENU_SAVESTATE, "SaveState"),
    MAKE_STRING(HOME_MENU_SAVESTATE_SAVE, "Save Emulation State"),
    MAKE_STRING(HOME_MENU_SAVESTATE_AND_EXIT, "Save Emulation State And Exit"),
    MAKE_STRING(HOME_MENU_RELOAD_SAVESTATE, "Reload Last Emulation State"),
    MAKE_STRING(HOME_MENU_RECORDING, "Start/Stop Recording"),
    MAKE_STRING(HOME_MENU_TROPHIES, "Trophies"),
    MAKE_STRING(HOME_MENU_TROPHY_HIDDEN_TITLE, "Hidden trophy"),
    MAKE_STRING(HOME_MENU_TROPHY_HIDDEN_DESCRIPTION, "This trophy is hidden"),
    MAKE_STRING(HOME_MENU_TROPHY_PLATINUM_RELEVANT, "Platinum relevant"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_BRONZE, "Bronze"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_SILVER, "Silver"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_GOLD, "Gold"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_PLATINUM, "Platinum"),
    MAKE_STRING(AUDIO_MUTED, "Audio muted"),
    MAKE_STRING(AUDIO_UNMUTED, "Audio unmuted"),
    MAKE_STRING(PROGRESS_DIALOG_PROGRESS, "Progress:"),
    MAKE_STRING(PROGRESS_DIALOG_PROGRESS_ANALYZING, "Progress: analyzing..."),
    MAKE_STRING(PROGRESS_DIALOG_REMAINING, "remaining"),
    MAKE_STRING(PROGRESS_DIALOG_DONE, "done"),
    MAKE_STRING(PROGRESS_DIALOG_FILE, "file"),
    MAKE_STRING(PROGRESS_DIALOG_MODULE, "module"),
    MAKE_STRING(PROGRESS_DIALOG_OF, "of"),
    MAKE_STRING(PROGRESS_DIALOG_PLEASE_WAIT, "Please wait"),
    MAKE_STRING(PROGRESS_DIALOG_STOPPING_PLEASE_WAIT,
                "Stopping. Please wait..."),
    MAKE_STRING(PROGRESS_DIALOG_SAVESTATE_PLEASE_WAIT,
                "Creating savestate. Please wait..."),
    MAKE_STRING(PROGRESS_DIALOG_SCANNING_PPU_EXECUTABLE,
                "Scanning PPU Executable..."),
    MAKE_STRING(PROGRESS_DIALOG_ANALYZING_PPU_EXECUTABLE,
                "Analyzing PPU Executable..."),
    MAKE_STRING(PROGRESS_DIALOG_SCANNING_PPU_MODULES,
                "Scanning PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_LOADING_PPU_MODULES, "Loading PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_COMPILING_PPU_MODULES,
                "Compiling PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_LINKING_PPU_MODULES, "Linking PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_APPLYING_PPU_CODE, "Applying PPU Code..."),
    MAKE_STRING(PROGRESS_DIALOG_BUILDING_SPU_CACHE, "Building SPU Cache..."),
    MAKE_STRING(EMULATION_PAUSED_RESUME_WITH_START,
                "Press and hold the START button to resume"),
    MAKE_STRING(EMULATION_RESUMING, "Resuming...!"),
    MAKE_STRING(EMULATION_FROZEN,
                "The PS3 application has likely crashed, you can close it."),
    MAKE_STRING(
        SAVESTATE_FAILED_DUE_TO_SAVEDATA,
        "SaveState failed: Game saving is in progress, wait until finished."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_VDEC,
                "SaveState failed: VDEC-base video/cutscenes are in order, "
                "wait for them to end or enable libvdec.sprx."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_MISSING_SPU_SETTING,
                "SaveState failed: Failed to lock SPU state, enabling "
                "SPU-Compatible mode may fix it."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_SPU,
                "SaveState failed: Failed to lock SPU state, using SPU ASMJIT "
                "will fix it."),
    MAKE_STRING(INVALID, "Invalid"),
};

enum GameFlags {
  kGameFlagLocked = 1 << 0,
  kGameFlagTrial = 1 << 1,
};

struct GameInfo {
  std::string path;
  std::string name;
  std::string iconPath;
  int flags = 0;
};

class Progress {
  JNIEnv *env;
  jlong progressId;
  jclass progressRepositoryClass;
  jmethodID onProgressEventMethodId;

public:
  Progress(JNIEnv *env, jlong progressId) : env(env), progressId(progressId) {
    progressRepositoryClass =
        ensure(env->FindClass("net/rpcsx/ProgressRepository"));
    onProgressEventMethodId = env->GetStaticMethodID(
        progressRepositoryClass, "onProgressEvent", "(JJJLjava/lang/String;)Z");
  }

  bool report(jlong value, jlong max, const std::string &message = {}) {
    return env->CallStaticBooleanMethod(
        progressRepositoryClass, onProgressEventMethodId, progressId, value,
        max, message.empty() ? nullptr : wrap(env, message));
  }

  void failure(const std::string &message = {}) { report(-1, 0, message); }

  void success(jlong value, const std::string &message = {}) {
    value = std::max<jlong>(value, 1);
    report(value, value, message);
  }

  jlong getProgressId() const { return progressId; }
};

static void sendFirmwareInstalled(JNIEnv *env, const std::string &version) {
  auto fwRepositoryClass =
      ensure(env->FindClass("net/rpcsx/FirmwareRepository"));
  auto methodId = ensure(env->GetStaticMethodID(
      fwRepositoryClass, "onFirmwareInstalled", "(Ljava/lang/String;)V"));

  env->CallStaticVoidMethod(fwRepositoryClass, methodId, wrap(env, version));
}

static void sendFirmwareCompiled(JNIEnv *env, const std::string &version) {
  auto fwRepositoryClass =
      ensure(env->FindClass("net/rpcsx/FirmwareRepository"));
  auto methodId = ensure(env->GetStaticMethodID(
      fwRepositoryClass, "onFirmwareCompiled", "(Ljava/lang/String;)V"));

  env->CallStaticVoidMethod(fwRepositoryClass, methodId, wrap(env, version));
}

static void sendGameInfo(JNIEnv *env, jlong progressId,
                         std::span<const GameInfo> infos) {
  auto gameRepositoryClass = ensure(env->FindClass("net/rpcsx/GameRepository"));
  auto addMethodId = ensure(env->GetStaticMethodID(
      gameRepositoryClass, "add", "([Lnet/rpcsx/GameInfo;J)V"));
  auto gameClass = ensure(env->FindClass("net/rpcsx/GameInfo"));

  jmethodID gameConstructor = ensure(env->GetMethodID(
      gameClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V"));

  std::vector<jobject> objects;
  objects.reserve(infos.size());

  for (const auto &info : infos) {
    auto path = Emu.GetCallbacks().resolve_path(info.path);
    if (path.ends_with('/')) {
      path.resize(path.size() - 1);
    }

    objects.push_back(env->NewObject(
        gameClass, gameConstructor, wrap(env, path), wrap(env, info.name),
        wrap(env, Emu.GetCallbacks().resolve_path(info.iconPath)),
        jint(info.flags)));
  }

  auto result = env->NewObjectArray(objects.size(), gameClass, nullptr);

  for (std::size_t i = 0; i < objects.size(); ++i) {
    env->SetObjectArrayElement(result, i, objects[i]);
  }

  env->CallStaticVoidMethod(gameRepositoryClass, addMethodId, result,
                            progressId);
}

static void sendVshBootable(JNIEnv *env, jlong progressId) {
  auto dev_flash = g_cfg_vfs.get_dev_flash();

  sendGameInfo(
      env, progressId,
      {{GameInfo{
          .path = dev_flash + "/vsh/module/vsh.self",
          .name = "VSH",
          .iconPath = dev_flash + "vsh/resource/explore/icon/icon_home.png",
      }}});
}

static bool tryUnlockGame(const psf::registry &psf) {
  auto contentId = psf::get_string(psf, "CONTENT_ID");

  if (contentId.empty()) {
    return true;
  }

  const auto licenseDir = fmt::format(
      "%shome/%s/exdata/", rpcs3::utils::get_hdd0_dir(), Emu.GetUsr());

  const auto licenseFile = fmt::format("%s%s", licenseDir, contentId);
  if (std::filesystem::is_regular_file(licenseFile + ".rap")) {
    return true;
  }

  if (std::filesystem::is_regular_file(licenseFile + ".edat")) {
    return true;
  }

  return false;
}

static void collectGamePaths(std::vector<std::string> &paths,
                             const std::string &rootDir) {
  std::error_code ec;
  std::vector<std::filesystem::path> workList;
  workList.reserve(32);
  if (!std::filesystem::is_directory(rootDir)) {
    auto rootPath = std::filesystem::path(rootDir).parent_path();
    if (rootPath.filename() == "USRDIR") {
      rootPath = rootPath.parent_path();
    }
    if (rootPath.filename() == "PS3_GAME") {
      rootPath = rootPath.parent_path();
    }

    workList.push_back(rootPath);
  } else {
    workList.push_back(rootDir);
  }

  while (!workList.empty()) {
    auto dir = std::move(workList.back());
    workList.pop_back();

    for (auto &entry : std::filesystem::directory_iterator(dir, ec)) {
      if (entry.is_directory()) {
        if (entry.path().filename() != "C00") {
          workList.push_back(entry.path());
        }

        continue;
      }

      if (entry.is_regular_file() && entry.path().filename() == "PARAM.SFO") {
        paths.push_back(entry.path().parent_path().string());
        continue;
      }
    }
  }
}

static std::string locateEbootPath(std::string_view root) {
  if (std::filesystem::is_regular_file(root)) {
    return std::string(root);
  }

  for (auto suffix : {
           "/EBOOT.BIN",
           "/USRDIR/EBOOT.BIN",
           "/USRDIR/ISO.BIN.EDAT",
           "/PS3_GAME/USRDIR/EBOOT.BIN",
       }) {
    auto tryPath = std::string(root);
    tryPath += suffix;

    if (std::filesystem::is_regular_file(tryPath)) {
      return tryPath;
    }
  }

  return {};
}

static std::string locateParamSfoPath(std::string_view root) {
  if (std::filesystem::is_regular_file(root)) {
    return std::string(root);
  }

  for (auto suffix : {
           "/PARAM.SFO",
           "/PS3_GAME/PARAM.SFO",
       }) {
    auto tryPath = std::string(root);
    tryPath += suffix;

    if (std::filesystem::is_regular_file(tryPath)) {
      return tryPath;
    }
  }

  return {};
}

static std::optional<GameInfo>
fetchGameInfo(const psf::registry &psf,
              std::filesystem::path psfRootPath = {}) {
  auto titleId = std::string(psf::get_string(psf, "TITLE_ID"));
  auto name = std::string(psf::get_string(psf, "TITLE"));
  auto bootable = psf::get_integer(psf, "BOOTABLE", 0);
  auto category = psf::get_string(psf, "CATEGORY");

  if (!bootable || titleId.empty()) {
    return {};
  }

  bool isDiscGame = category == "DG";

  std::string path;

  if (!isDiscGame) {
    path = rpcs3::utils::get_hdd0_dir() + "game/" + titleId + "/";
  } else {
    if (psfRootPath.empty()) {
      path = fs::get_config_dir() + "games/" + titleId + "/";
    } else {
      // Locate game root path
      if (psfRootPath.filename() == "USRDIR") {
        psfRootPath = psfRootPath.parent_path();
      }

      if (psfRootPath.filename() == "PS3_GAME") {
        psfRootPath = psfRootPath.parent_path();
      }

      path = psfRootPath;
      if (!path.ends_with('/')) {
        path += '/';
      }
    }
  }

  auto dataPath = isDiscGame ? path + "PS3_GAME/" : path;
  auto iconPath = dataPath + "ICON0.PNG";
  auto moviePath = dataPath + "ICON1.PAM";

  int flags = 0;

  if (!isDiscGame) {
    auto ebootPath = locateEbootPath(path);

    bool isLocked = false;

    if (!ebootPath.empty()) {
      if (fs::file eboot{ebootPath};
          eboot && eboot.size() >= 4 && eboot.read<u32>() == "SCE\0"_u32) {
        isLocked = !decrypt_self(eboot);
      }
    }

    if (isLocked) {
      flags |= kGameFlagLocked;
      rpcsx_android.warning("game %s is locked", path);
    }

    auto c00Path = path + "/C00";

    bool isTrial = std::filesystem::is_directory(c00Path);

    if (isTrial) {
      if (!tryUnlockGame(psf)) {
        flags |= kGameFlagTrial;
        rpcsx_android.warning("game %s is trial", path);
      } else {
        auto c00IconPath = c00Path + "/ICON0.PNG";
        if (std::filesystem::is_regular_file(c00IconPath)) {
          iconPath = c00IconPath;
        }

        auto c00SfoPath = c00Path + "/PARAM.SFO";

        if (std::filesystem::is_regular_file(c00IconPath)) {
          auto c00Sfo = psf::load_object(c00SfoPath);
          titleId = psf::get_string(c00Sfo, "TITLE_ID", titleId);
          name = psf::get_string(c00Sfo, "TITLE", name);
        }
      }
    }
  }

  return GameInfo{
      .path = std::move(path),
      .name = std::move(name),
      .iconPath = std::move(iconPath),
      .flags = flags,
  };
}

static void collectGameInfo(JNIEnv *env, jlong progressId,
                            const std::vector<std::string> &rootDirs) {
  std::vector<std::string> paths;
  for (auto &&rootDir : rootDirs) {
    collectGamePaths(paths, rootDir);

    rpcsx_android.notice("collectGameInfo: processed %s", rootDir);
  }

  rpcsx_android.notice("collectGameInfo: found %d paths", paths.size());

  Progress progress(env, progressId);
  progress.report(0, paths.size());

  std::vector<GameInfo> gameInfos;
  gameInfos.reserve(10);
  std::size_t processed = 0;

  auto submit = [&] {
    if (gameInfos.empty()) {
      return;
    }

    sendGameInfo(env, progressId, gameInfos);
    progress.report(processed, paths.size());
    gameInfos.clear();
  };

  for (auto &&path : paths) {
    processed++;

    if (!std::filesystem::is_regular_file(path + "/PARAM.SFO")) {
      continue;
    }

    const auto psf = psf::load_object(path + "/PARAM.SFO");

    rpcsx_android.notice("collectGameInfo: sfo at %s", path);

    if (auto gameInfo = fetchGameInfo(psf, path)) {
      gameInfos.push_back(std::move(*gameInfo));

      if (gameInfos.size() >= 10) {
        submit();
      }
    }
  }

  submit();

  progress.success(processed);
}

class MainThreadProcessor {
  std::mutex mutex;
  std::condition_variable cv;
  std::deque<std::pair<std::function<void(JNIEnv *)>, atomic_t<u32> *>> queue;

public:
  void push(std::function<void(JNIEnv *)> cb, atomic_t<u32> *wakeUp = nullptr) {
    std::lock_guard lock(mutex);
    queue.push_back({std::move(cb), wakeUp});
    cv.notify_one();
  }

  void push(std::function<void()> cb, atomic_t<u32> *wakeUp = nullptr) {
    push([cb = std::move(cb)](JNIEnv *) { cb(); }, wakeUp);
  }

  void process(JNIEnv *env) {
    while (true) {
      std::function<void(JNIEnv *)> cb;
      atomic_t<u32> *wakeUp = nullptr;

      {
        std::unique_lock lock(mutex);
        if (queue.empty()) {
          cv.wait(lock);
          continue;
        }

        auto item = std::move(queue.front());
        queue.pop_front();

        cb = std::move(item.first);
        wakeUp = item.second;
      }

      cb(env);
      if (wakeUp) {
        *wakeUp = true;
        wakeUp->notify_all();
      }
    }
  }
} static g_mainThreadProcessor;

static void invokeAsync(std::function<void(JNIEnv *)> cb) {
  g_mainThreadProcessor.push(std::move(cb));
}

static void invokeSync(std::function<void(JNIEnv *)> cb) {
  atomic_t<u32> wakeup{false};
  g_mainThreadProcessor.push(std::move(cb), &wakeup);

  while (wakeup.load() == false) {
    wakeup.wait(false);
  }
}

struct ProgressMessageDialog : MsgDialogBase {
  jlong progressId;
  jlong value = 0;
  jlong max = 0;

  ProgressMessageDialog(jlong progressId) : progressId(progressId) {}

  void Create(const std::string &msg, const std::string &title) override {
    rpcsx_android.warning("ProgressMessageDialog::Create(%s, %s)", msg, title);
    max = 100;
    invokeSync([this, &msg](JNIEnv *env) {
      Progress progress(env, progressId);
      progress.report(0, 0, msg);
    });
  }

  jlong getValue() const {
    return value == max && max != 0 ? value - 1 : value;
  }

  void Close(bool success) override {
    rpcsx_android.warning("ProgressMessageDialog::Close(%s)", success);
    invokeSync([this](JNIEnv *env) {
      Progress progress(env, progressId);
      progress.report(0, 0);
    });

    //   Progress progress(env, progressId);
    //   if (success) {
    //     progress.success(0);
    //   } else {
    //     progress.failure();
    //   }
    // });
  }

  void SetMsg(const std::string &msg) override {
    rpcsx_android.warning("ProgressMessageDialog::SetMsg(%s)", msg);
    invokeSync([this, msg](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max, msg);
    });
  }

  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {
    rpcsx_android.warning("ProgressMessageDialog::ProgressBarSetMsg(%d, %s)",
                          progressBarIndex, msg);
    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    invokeSync([this, msg](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max, msg);
    });
  }

  void ProgressBarReset(u32 progressBarIndex) override {
    rpcsx_android.warning("ProgressMessageDialog::ProgressBarReset(%d)",
                          progressBarIndex);

    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    value = 0;
    invokeSync(
        [this](JNIEnv *env) { Progress(env, progressId).report(value, max); });
  }

  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {
    rpcsx_android.warning("ProgressMessageDialog::ProgressBarInc(%d, %d)",
                          progressBarIndex, delta);

    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    value += delta;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }

  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {
    rpcsx_android.warning("ProgressMessageDialog::ProgressBarSetValue(%d, %d)",
                          progressBarIndex, value);

    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    this->value = value;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }
  void ProgressBarSetLimit(u32 index, u32 limit) override {
    rpcsx_android.warning("ProgressMessageDialog::ProgressBarSetLimit(%d, %d)",
                          index, limit);

    if (index != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    max = limit;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }
};

struct UiMessageDialog : MsgDialogBase {
  // FIXME: implement

  void Create(const std::string &msg, const std::string &title) override {}
  void Close(bool success) override {}
  void SetMsg(const std::string &msg) override {}
  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {}
  void ProgressBarReset(u32 progressBarIndex) override {}
  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {}
  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {}
  void ProgressBarSetLimit(u32 index, u32 limit) override {}
};

struct MessageDialog : MsgDialogBase {
  std::unique_ptr<MsgDialogBase> impl = nullptr;

  void Create(const std::string &msg, const std::string &title) override {
    auto progressId = s_pendingProgressId.load();

    rpcsx_android.warning("MessageDialog::Create(%s, %s): source %s, id %d",
                          msg, title, source, progressId);

    if (progressId != -1) {
      impl = std::make_unique<ProgressMessageDialog>(progressId);
    } else {
      impl = std::make_unique<UiMessageDialog>();
    }

    impl->type = type;
    impl->source = source;
    impl->Create(msg, title);
  }

  void Close(bool success) override { impl->Close(success); }

  void SetMsg(const std::string &msg) override { impl->SetMsg(msg); }

  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {
    impl->ProgressBarSetMsg(progressBarIndex, msg);
  }

  void ProgressBarReset(u32 progressBarIndex) override {
    impl->ProgressBarReset(progressBarIndex);
  }

  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {
    impl->ProgressBarInc(progressBarIndex, delta);
  }

  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {
    impl->ProgressBarSetValue(progressBarIndex, value);
  }

  void ProgressBarSetLimit(u32 index, u32 limit) override {
    impl->ProgressBarSetLimit(index, limit);
  }

  static void pushPendingProgressId(jlong id) {
    jlong value = -1;

    while (!s_pendingProgressId.compare_exchange_weak(value, id)) {
      s_pendingProgressId.wait(value);
      value = -1;
    }
  }

  static bool popPendingProgressId(jlong id) {
    return s_pendingProgressId.compare_exchange_strong(id, -1);
  }

private:
  static std::atomic<jlong> s_pendingProgressId;
};

struct OverlaySaveDialog : SaveDialogBase {
  s32 ShowSaveDataList(const std::string &base_dir,
                       std::vector<SaveDataEntry> &save_entries, s32 focused,
                       u32 op, vm::ptr<CellSaveDataListSet> listSet,
                       bool enable_overlay) override {
    rpcsx_android.notice("ShowSaveDataList(save_entries=%d, focused=%d, "
                         "op=0x%x, listSet=*0x%x, enable_overlay=%d)",
                         save_entries.size(), focused, op, listSet,
                         enable_overlay);

    bool use_end = sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_BEGIN, 0) >= 0;

    auto atExit = AtExit([&] {
      if (use_end) {
        sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_END, 0);
      }
    });

    if (!use_end) {
      rpcsx_android.error(
          "ShowSaveDataList(): Not able to notify DRAWING_BEGIN callback "
          "because one has already been sent!");
    }

    if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>()) {
      rpcsx_android.notice("ShowSaveDataList: Showing native UI dialog");

      s32 result = manager->create<rsx::overlays::save_dialog>()->show(
          base_dir, save_entries, focused, op, listSet, enable_overlay);

      if (result != rsx::overlays::user_interface::selection_code::error) {
        rpcsx_android.notice(
            "ShowSaveDataList: Native UI dialog returned with selection %d",
            result);

        return result;
      }

      rpcsx_android.error("ShowSaveDataList: Native UI dialog returned error");
    }

    return -2;
  }
};

class OverlayTrophyNotification : public TrophyNotificationBase {
public:
  s32 ShowTrophyNotification(
      const SceNpTrophyDetails &trophy,
      const std::vector<uchar> &trophy_icon_buffer) override {
    if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>()) {
      auto popup = std::make_shared<rsx::overlays::trophy_notification>();
      return manager->add(popup, false)->show(trophy, trophy_icon_buffer);
    }

    return 0;
  }
};

std::atomic<jlong> MessageDialog::s_pendingProgressId = -1;

struct CompilationWorkload {
  jlong progressId;
  std::string path;
};

extern bool ppu_load_exec(const ppu_exec_object &, bool virtual_load,
                          const std::string &, utils::serial * = nullptr);
extern void spu_load_exec(const spu_exec_object &);
extern void spu_load_rel_exec(const spu_rel_object &);
// Upstream gained a third parameter (is_fast_compilation) after RPCSX forked.
extern void ppu_precompile(std::vector<std::string> &dir_queue,
                           std::vector<ppu_module<lv2_obj> *> *loaded_prx,
                           bool is_fast_compilation);
extern bool ppu_initialize(const ppu_module<lv2_obj> &, bool check_only = false,
                           u64 file_size = 0);
extern void ppu_finalize(const ppu_module<lv2_obj> &);
extern bool ppu_load_rel_exec(const ppu_rel_object &);

class CompilationQueue {
  std::atomic<std::uint64_t> nextWorkTag{0};
  std::uint64_t lastProcessedTag = 0;
  std::mutex queueMutex;
  std::deque<CompilationWorkload> queue;

public:
  void push(CompilationWorkload workload) {
    {
      std::lock_guard lock(queueMutex);
      queue.push_back(std::move(workload));
    }

    nextWorkTag.fetch_add(1);
  }

  void push(Progress &progress, std::string path) {
    progress.report(0, 0);

    push({
        .progressId = progress.getProgressId(),
        .path = std::move(path),
    });
  }

  void process(JNIEnv *env) {
    while (true) {
      auto nextWorkTagValue = nextWorkTag.load();

      if (nextWorkTagValue == lastProcessedTag) {
        nextWorkTag.wait(lastProcessedTag);
      }

      if (nextWorkTagValue == lastProcessedTag || queue.empty()) {
        continue;
      }

      CompilationWorkload workload;

      {
        std::lock_guard lock(queueMutex);

        if (queue.empty()) {
          continue;
        }

        workload = std::move(queue.front());
        queue.pop_front();
      }

      impl(env, std::move(workload));
      lastProcessedTag++;
    }
  }

private:
  void impl(JNIEnv *env, CompilationWorkload workload) {
    if (workload.path.empty()) {
      Progress(env, workload.progressId).success(0);
      return;
    }

    rpcsx_android.error("Creating cache initiated, state %d",
                        (int)Emu.GetStatus(false));

    while (true) {
      auto state = Emu.GetStatus(false);

      if (state == system_state::stopped || state == system_state::ready) {
        break;
      }

      rpcsx_android.error("Creating cache wait, state %d", (int)state);
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    bool is_vsh = workload.path.ends_with("/vsh.self");

    Emu.SetState(system_state::running);

    MessageDialog::pushPendingProgressId(workload.progressId);

    g_fxo->init<named_thread<progress_dialog_server>>();
    g_fxo->init<main_ppu_module<lv2_obj>>();

    // patch_engine must exist BEFORE ppu_load_exec, which calls
    // g_fxo->get<patch_engine>().apply() on every segment. g_fxo->get() on an
    // object that was never created hands back raw uninitialised storage, so the
    // patch map's hash table is garbage and apply() segfaults.
    //
    // need<> rather than init<>: it creates only if absent, so it stays correct
    // when several modules are compiled in a row. init<> would return null the
    // second time and the ensure() would abort.
    //
    // Emulator::Load() does exactly this at System.cpp:1766, well before it
    // loads anything.
    g_fxo->need<patch_engine>();
    g_fxo->get<patch_engine>().append_global_patches();

    // Map the PS3 address space.
    //
    // Emulator::Init() does NOT do this -- only the boot paths in
    // Emulator::Load() do, and precompilation deliberately never boots. Without
    // it vm::get() has no blocks, so vm::alloc() returns 0, ppu_register_range()
    // is handed address 0, and its ensure(vm::page_protect(...)) aborts the
    // process with SIGTRAP the moment firmware finishes installing.
    //
    // Load() calls this immediately before ppu_load_exec for the same reason.
    vm::init();
    auto rootPath = std::filesystem::path(workload.path);

    if (is_vsh) {
      rootPath = g_cfg_vfs.get_dev_flash() + "sys/external/";
    } else {
      if (!std::filesystem::is_directory(rootPath)) {
        rootPath = rootPath.parent_path();
        if (rootPath.filename() == "USRDIR") {
          rootPath = rootPath.parent_path();
        }
      }
    }

    auto &_main = *ensure(g_fxo->try_get<main_ppu_module<lv2_obj>>());

    if (fs::is_file(workload.path)) {
      if (!is_vsh) {
        auto sfoPath = locateParamSfoPath(std::string(rootPath));

        if (!sfoPath.empty()) {
          const auto psf = psf::load_object(sfoPath);
          rpcsx_android.warning("title id is %s",
                                psf::get_string(psf, "TITLE_ID"));

          Emu.SetTitleID(std::string(psf::get_string(psf, "TITLE_ID")));
        } else {
          rpcsx_android.warning("param.sfo not found");
        }
      }

      // Compile binary first
      rpcsx_android.notice("Trying to load binary: %s", workload.path);

      fs::file src{workload.path};
      src = decrypt_self(src);

      const ppu_exec_object obj = src;

      if (obj == elf_error::ok && ppu_load_exec(obj, true, workload.path)) {
        _main.path = workload.path;
      } else {
        rpcsx_android.error("Failed to load binary '%s' (%s)", workload.path,
                            obj.get_error());
      }
    }

    // Bulk-init the fixed objects AFTER loading, which is the order
    // Emulator::Load() uses (ppu_load_exec at System.cpp:2603, then
    // g_fxo->init(false) at :2618).
    //
    // Doing it first looks harmless and is not: ppu_load_exec ->
    // ppu_initialize_modules does its own `ensure(g_fxo->init<hle_vars_save>())`,
    // and g_fxo->init returns NULL when the object already exists. So a bulk
    // init beforehand makes that ensure() abort the process.
    g_fxo->init(false, nullptr);

    std::vector<std::string> dir_queue;
    dir_queue.push_back(rootPath.string());

    for (auto &entry :
         std::filesystem::recursive_directory_iterator(rootPath)) {
      if (entry.is_directory()) {
        dir_queue.push_back(entry.path().string());
      }
    }

    std::vector<ppu_module<lv2_obj> *> mod_list;
    rpcsx_android.error("Going to analyze executable");

    // FIXME: split states
    if (!is_vsh) {
      if (_main.analyse(0, _main.elf_entry, _main.seg0_code_end,
                        _main.applied_patches, std::vector<u32>{})) {
        Emu.ConfigurePPUCache();
        Emu.SetTestMode();
        rpcsx_android.error("Going to precompile main PPU module");
        ppu_initialize(_main);
        mod_list.emplace_back(&_main);
      }
    }

    ppu_precompile(dir_queue, mod_list.empty() ? nullptr : &mod_list, false);

    rpcsx_android.error("Finalization");
    g_fxo->reset();

    // Pairs with the vm::init() above. Nothing else unmaps it, since vm::close()
    // is only reached from Emu.Stop() and precompilation never boots, so the
    // blocks this pass mapped outlive it and stay valid.
    //
    // The next vm::init(), either the next workload or Emulator::Load() booting
    // a game, assigns over g_locations. That destroys blocks that are still
    // valid and aborts the process in ~block_t()'s ensure(!is_valid()).
    //
    // Has to come after g_fxo->reset(): vm::close() requires each block to be
    // uniquely owned, and _unmap_block throws "External memory usage at block"
    // if anything still holds one of its shm references.
    vm::close();

    Emu.SetState(system_state::stopped);

    MessageDialog::popPendingProgressId(workload.progressId);

    Progress(env, workload.progressId).success(0);
  }
} static g_compilationQueue;

static void setupCallbacks() {
  Emu.SetCallbacks({
      .call_from_main_thread =
          [](std::function<void()> cb, atomic_t<u32> *wake_up) {
            cb();
            if (wake_up) {
              *wake_up = true;
            }
          },
      .on_run = [](auto...) {},
      .on_pause = [](auto...) {},
      .on_resume = [](auto...) {},
      .on_stop = [](auto...) {},
      .on_ready = [](auto...) {},
      .on_missing_fw = [](auto...) {},
      .on_emulation_stop_no_response = [](auto...) {},
      .on_save_state_progress = [](auto...) {},
      .enable_disc_eject = [](auto...) {},
      .enable_disc_insert = [](auto...) {},
      .handle_taskbar_progress = [](auto...) {},
      .init_kb_handler =
          [](auto...) {
            ensure(g_fxo->init<KeyboardHandlerBase, NullKeyboardHandler>(
                Emu.DeserialManager()));
          },
      .init_mouse_handler =
          [](auto...) {
            ensure(g_fxo->init<MouseHandlerBase, NullMouseHandler>(
                Emu.DeserialManager()));
          },
      .init_pad_handler =
          [](auto...) {
            ensure(g_fxo->init<named_thread<pad_thread>>(nullptr, nullptr, ""));
          },
      .update_emu_settings = [](auto...) {},
      .save_emu_settings =
          [](auto...) {
            Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID());
          },
      // These five were never set, so they were empty std::functions. Emulator::Load
      // invokes enable_gamemode and get_database_config unconditionally, which
      // aborted the process with
      //   "bad_function_call was thrown in -fno-exceptions mode"
      // the moment anything booted -- including Boot XMB.
      //
      // GameMode is a Linux desktop daemon and has no Android equivalent, so it is
      // a no-op. get_database_config returns the path RPCS3 reads the game
      // database from; the desktop build points it at its own config dir, so we
      // point it at ours.
      .enable_gamemode = [](bool) {},
      .get_database_config =
          [](const std::string &name) {
            return g_cfg_vfs.get_dev_flash() + "../config/" + name;
          },
      .get_photo_path = [](std::string_view) { return std::string{}; },
      .try_to_quit = [](bool, std::function<void()> on_exit) {
        if (on_exit) on_exit();
        return true;
      },
      .make_video_source = []() -> std::unique_ptr<video_source> { return nullptr; },
      .close_gs_frame = [](auto...) {},
      .get_gs_frame = [] { return std::make_unique<GraphicsFrame>(); },
      .get_camera_handler =
          [](auto...) { return std::make_shared<null_camera_handler>(); },
      .get_music_handler =
          [](auto...) { return std::make_shared<null_music_handler>(); },
      // ARMSX3: RPCSX injected a pad-handler FACTORY here via a
      // `create_pad_handler` callback that no longer exists -- upstream's
      // EmuCallbacks only has `init_pad_handler(std::string_view title_id)`,
      // and pad_thread::GetHandler() owns construction now. The Android-only
      // virtual_pad case lives there instead (guarded by __ANDROID__, matching
      // the enum in Emu/Io/pad_config_types.h), so upstream keeps ownership of
      // the switch and we do not have to re-list every handler it gains.
      .init_gs_render =
          [](utils::serial *ar) {
            switch (g_cfg.video.renderer.get()) {
            case video_renderer::null:
              g_fxo->init<rsx::thread, named_thread<NullGSRender>>(ar);
              break;
            case video_renderer::vulkan:
              g_fxo->init<rsx::thread, named_thread<VKGSRender>>(ar);
              break;

            // ARMSX3: the GL backend now targets OpenGL ES 3.2 (see
            // Emu/RSX/GL/OpenGL_ES.hpp). It is also the only path that can run on
            // ANGLE, which is why it exists on a Vulkan-capable platform at all.
            case video_renderer::opengl:
              g_fxo->init<rsx::thread, named_thread<GLGSRender>>(ar);
              break;

            default:
              break;
            }
          },
      .get_audio =
          [](auto...) {
            std::shared_ptr<AudioBackend> result;

            switch (g_cfg.audio.renderer.get()) {
            case audio_renderer::null:
              result = std::make_shared<NullAudioBackend>();
              break;

            case audio_renderer::cubeb:
            default:
              result = std::make_shared<CubebBackend>();
              break;
            }

            if (!result->Initialized()) {
              rpcsx_android.error(
                  "Audio renderer %s could not be initialized, using a Null "
                  "renderer instead. Make sure that no other application is "
                  "running that might block audio access (e.g. Netflix).",
                  result->GetName());
              result = std::make_shared<NullAudioBackend>();
            }
            return result;
          },
      .get_audio_enumerator = [](auto...) { return nullptr; },
      .get_msg_dialog = [] { return std::make_shared<MessageDialog>(); },
      .get_osk_dialog = [](auto...) { return nullptr; },
      .get_save_dialog =
          [](auto...) { return std::make_unique<OverlaySaveDialog>(); },
      .get_sendmessage_dialog = [](auto...) { return nullptr; },
      .get_recvmessage_dialog = [](auto...) { return nullptr; },
      .get_trophy_notification_dialog =
          [](auto...) { return std::make_unique<OverlayTrophyNotification>(); },
      .get_localized_string = [](localized_string_id id,
                                 const char *) -> std::string {
        if (std::size_t(id) < std::size(g_strings)) {
          return g_strings[int(id)].first;
        }
        return "";
      },
      .get_localized_u32string = [](localized_string_id id,
                                    const char *) -> std::u32string {
        if (std::size_t(id) < std::size(g_strings)) {
          return g_strings[int(id)].second;
        }
        return U"";
      },
      .get_localized_setting = [](auto...) { return ""; },
      .play_sound = [](auto...) {},
      .get_image_info = [](auto...) { return false; },
      .get_scaled_image = [](auto...) { return false; },
      .resolve_path =
          [](std::string_view arg) {
            std::error_code ec;
            auto result =
                std::filesystem::weakly_canonical(
                    std::filesystem::path(fmt::replace_all(arg, "\\", "/")), ec)
                    .string();
            return ec ? std::string(arg) : result;
          },
      .get_font_dirs = [](auto...) { return std::vector<std::string>(); },
      .on_install_pkgs =
          [](const std::vector<std::string> &pkgs) {
            for (const std::string &pkg : pkgs) {
              if (!rpcs3::utils::install_pkg(pkg)) {
                rpcsx_android.error("cd install pkgs: failed to install %s",
                                    pkg);
                return false;
              }
            }
            return true;
          },
      .add_breakpoint = [](auto...) {},
      .display_sleep_control_supported = [](auto...) { return false; },
      .enable_display_sleep = [](auto...) {},
      .check_microphone_permissions = [](auto...) {},
  });
}

static bool initVirtualPad(const std::shared_ptr<Pad> &pad) {
  u32 pclass_profile = 0;
  pad->Init(CELL_PAD_STATUS_CONNECTED,
            CELL_PAD_CAPABILITY_PS3_CONFORMITY |
                CELL_PAD_CAPABILITY_PRESS_MODE |
                CELL_PAD_CAPABILITY_HP_ANALOG_STICK |
                CELL_PAD_CAPABILITY_ACTUATOR //| CELL_PAD_CAPABILITY_SENSOR_MODE
            ,
            CELL_PAD_DEV_TYPE_STANDARD, CELL_PAD_PCLASS_TYPE_STANDARD,
            pclass_profile, 0, 0, 50);

  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_UP);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_DOWN);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_LEFT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_RIGHT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_CROSS);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_SQUARE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_CIRCLE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_TRIANGLE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L1);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L2);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L3);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R1);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R2);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R3);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_START);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_SELECT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_PS);

  pad->m_sticks[0] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_X, {}, {});
  pad->m_sticks[1] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_Y, {}, {});
  pad->m_sticks[2] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_X, {}, {});
  pad->m_sticks[3] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_Y, {}, {});

  pad->m_sensors[0] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_X, 0, 0, 0, DEFAULT_MOTION_X);
  pad->m_sensors[1] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Y, 0, 0, 0, DEFAULT_MOTION_Y);
  pad->m_sensors[2] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Z, 0, 0, 0, DEFAULT_MOTION_Z);
  pad->m_sensors[3] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_G, 0, 0, 0, DEFAULT_MOTION_G);

  pad->m_vibrate_motors[0] = VibrateMotor(true);
  pad->m_vibrate_motors[1] = VibrateMotor(false);

  if (pad->m_player_id < g_virtual_pads.size()) {
    std::lock_guard lock(g_virtual_pad_mutex);
    g_virtual_pads[pad->m_player_id] = pad;
  }
  return true;
}

extern "C" bool _rpcsx_overlayPadData(int port, int digital1, int digital2,
                                      int leftStickX, int leftStickY,
                                      int rightStickX, int rightStickY) {

  if (port < 0 || static_cast<usz>(port) >= g_virtual_pads.size()) {
    return false;
  }

  auto pad = [port] {
    std::shared_ptr<Pad> result;
    std::lock_guard lock(g_virtual_pad_mutex);
    result = g_virtual_pads[port];
    return result;
  }();

  if (pad == nullptr) {
    return false;
  }

  for (auto &btn : pad->m_buttons) {
    if (btn.m_offset == CELL_PAD_BTN_OFFSET_DIGITAL1) {
      btn.m_pressed = (digital1 & btn.m_outKeyCode) != 0;

      if (btn.m_outKeyCode == CELL_PAD_CTRL_PS && btn.m_pressed) {
        if (auto padThread = pad::get_pad_thread(true)) {
          padThread->open_home_menu();
        }
      }

    } else if (btn.m_offset == CELL_PAD_BTN_OFFSET_DIGITAL2) {
      btn.m_pressed = (digital2 & btn.m_outKeyCode) != 0;
    }

    btn.m_value = btn.m_pressed ? 255 : 0;
  }

  pad->m_sticks[0].m_value = leftStickX;
  pad->m_sticks[1].m_value = leftStickY;
  pad->m_sticks[2].m_value = rightStickX;
  pad->m_sticks[3].m_value = rightStickY;
  return true;
}

extern "C" bool _rpcsx_initialize(std::string_view rootDir,
                                  std::string_view user) {
  auto rootDirStr = fix_dir_path(std::string(rootDir));

  if (g_android_executable_dir != rootDirStr) {
    g_android_executable_dir = rootDirStr;
    g_android_config_dir = rootDirStr + "config/";
    g_android_cache_dir = rootDirStr + "cache/";

    std::filesystem::create_directories(g_android_config_dir);
    std::error_code ec;
    // std::filesystem::remove_all(g_android_cache_dir, ec);
    std::filesystem::create_directories(g_android_cache_dir);
  }

  if (g_initialized) {
    return true;
  }

  g_initialized = true;

  if (int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY,
                                nullptr);
      r != 0) {
    rpcsx_android.warning(
        "libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY) -> %d", r);
  }

  // Initialize thread pool finalizer // ???
  static_cast<void>(named_thread("", [](int) {}));

  static std::unique_ptr<logs::listener> log_file;
  {
    // Check free space
    fs::device_stat stats{};
    if (!fs::statfs(fs::get_cache_dir(), stats) ||
        stats.avail_free < 128 * 1024 * 1024) {
      std::fprintf(stderr, "Not enough free space for logs (%f KB)",
                   stats.avail_free / 1000000.);
    }

    // preserve old log file
    if (std::filesystem::exists(fs::get_log_dir() + "RPCSX.log")) {
      std::error_code ec;
      std::filesystem::remove(fs::get_log_dir() + "RPCSX.old.log", ec);
      std::filesystem::rename(fs::get_log_dir() + "RPCSX.log",
                              fs::get_log_dir() + "RPCSX.old.log", ec);
    }

    // Limit log size to ~25% of free space
    log_file = logs::make_file_listener(fs::get_log_dir() + "RPCSX.log",
                                        stats.avail_free / 4);
  }

  logs::stored_message ver{rpcsx_android.always()};
  ver.text = fmt::format("RPCSX-ps3-android v%s", rpcs3::get_version().to_string());

  // Write System information
  logs::stored_message sys{rpcsx_android.always()};
  sys.text = utils::get_system_info();

  // Write OS version
  logs::stored_message os{rpcsx_android.always()};
  os.text = utils::get_OS_version_string();

  // Write current time
  logs::stored_message time{rpcsx_android.always()};
  time.text = fmt::format("Current Time: %s", std::chrono::system_clock::now());

  logs::set_init(
      {std::move(ver), std::move(sys), std::move(os), std::move(time)});

  auto set_rlim = [](int resource, std::uint64_t limit) {
    rlimit64 rlim{};
    if (getrlimit64(resource, &rlim) != 0) {
      rpcsx_android.error("failed to get rlimit for %d", resource);
      return;
    }

    rlim.rlim_cur = std::min<std::size_t>(rlim.rlim_max, limit);
    rpcsx_android.error("rlimit[%d] = %u (requested %u, max %u)", resource,
                        rlim.rlim_cur, limit, rlim.rlim_max);

    if (setrlimit64(resource, &rlim) != 0) {
      rpcsx_android.error("failed to set rlimit for %d", resource);
      return;
    }
  };

  set_rlim(RLIMIT_MEMLOCK, RLIM_INFINITY);
  set_rlim(RLIMIT_NOFILE, RLIM_INFINITY);
  set_rlim(RLIMIT_STACK, 128 * 1024 * 1024);
  set_rlim(RLIMIT_AS, RLIM_INFINITY);

  virtual_pad_handler::set_on_connect_cb(initVirtualPad);
  setupCallbacks();
  Emu.SetHasGui(false);
  Emu.SetUsr(std::string(user));

  // Tell the core which renderers exist.
  //
  // m_supported_renderers defaults to {null} (System.cpp:124) and the desktop
  // frontend is what normally populates it. Nothing did here, so ApplySettings
  // logged "The video renderer 'Vulkan' is not supported on this device and will
  // therefore be set to 'Null'" and forced Null -- after which every boot failed
  // and tripped the ensure() in BootGame's restore-on-no-boot path.
  //
  // Emu.Init() also asserts a non-empty adapter when the default is Vulkan
  // (System.cpp:493), and an empty string means "first device" to vk::instance,
  // so it has to be a real name rather than left blank.
  Emu.SetSupportedRenderers({
      video_renderer::null,
      video_renderer::opengl,
      video_renderer::vulkan,
  });
  Emu.SetDefaultRenderer(video_renderer::vulkan);
  Emu.SetDefaultGraphicsAdapter("Default");

  Emu.Init();

  g_cfg_input.player1.handler.set(pad_handler::virtual_pad);
  g_cfg_input.player1.device.from_string("Virtual");
  g_cfg_input.save("", g_cfg_input_configs.default_config);

  // LLVM JIT target.
  //
  // This was pinned to "cortex-a34" -- an ARMv8.0-A IN-ORDER little core from
  // 2016. Every PPU and SPU block LLVM compiles was therefore scheduled for an
  // in-order pipeline and restricted to the ARMv8.0 baseline, on hardware that is
  // out-of-order ARMv9 (Cortex-X3/A715 on an 8 Gen 2). The hand-written NEON
  // paths were unaffected -- m_use_dotprod / m_use_i8mm come from runtime HWCAP,
  // not from this string -- but everything LLVM generates itself was built for
  // the wrong machine.
  //
  // Empty means "detect the host", which is what desktop RPCS3 does. Feature
  // asymmetry across big.LITTLE is not a hazard here: the kernel requires every
  // core in an SoC to expose the same ISA, so the difference is scheduling, not
  // legality.
  // Thread affinity is gated on this being anything other than `os`, and the
  // affinity path itself was compiled out on Android until now (Thread.cpp), so
  // the setting has never done anything here. With a big.LITTLE mask available
  // it is worth having: six SPU threads scattered across prime and A510 cores
  // run at the speed of the slowest one they land on.
  g_cfg.core.thread_scheduler.set(thread_scheduler_mode::alt);

  g_cfg.core.llvm_cpu.from_string("");

  rpcsx_android.warning("LLVM JIT target: '%s'",
                        g_cfg.core.llvm_cpu.to_string().empty()
                            ? "<host-detected>"
                            : g_cfg.core.llvm_cpu.to_string());

  Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID());
  return true;
}

// ---------------------------------------------------------------------------
// JNI_GetCreatedJavaVMs
//
// 3rdparty/rtmidi's Android (AMidi) backend calls this to reach the VM. It is
// part of the JNI Invocation API exported by libnativehelper/ART -- NOT by the
// public NDK, so linking it is impossible and rtmidi is added unconditionally
// with no option to disable. It was the last undefined symbol in the build.
//
// JNI_OnLoad never runs for us: the app dlopen()s this core rather than
// System.loadLibrary()ing it, and dlopen does not invoke JNI_OnLoad. So capture
// the VM from the first JNIEnv that crosses the boundary instead, and report
// zero VMs until then -- rtmidi treats that as "no MIDI available" and degrades
// cleanly rather than crashing.
// ---------------------------------------------------------------------------
static std::atomic<JavaVM *> g_java_vm{nullptr};

static void armsx3_capture_java_vm(JNIEnv *env) {
  if (env == nullptr || g_java_vm.load(std::memory_order_acquire) != nullptr) {
    return;
  }

  JavaVM *vm = nullptr;
  if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
    JavaVM *expected = nullptr;
    g_java_vm.compare_exchange_strong(expected, vm);
  }
}

extern "C" jint JNI_GetCreatedJavaVMs(JavaVM **vmBuf, jsize bufLen,
                                      jsize *nVMs) {
  JavaVM *vm = g_java_vm.load(std::memory_order_acquire);

  if (nVMs != nullptr) {
    *nVMs = (vm != nullptr) ? 1 : 0;
  }

  if (vm != nullptr && vmBuf != nullptr && bufLen > 0) {
    vmBuf[0] = vm;
  }

  return JNI_OK;
}

extern "C" bool _rpcsx_processCompilationQueue(JNIEnv *env) {
  armsx3_capture_java_vm(env);
  g_compilationQueue.process(env);
  return true;
}

extern "C" bool _rpcsx_startMainThreadProcessor(JNIEnv *env) {
  armsx3_capture_java_vm(env);
  g_mainThreadProcessor.process(env);
  return true;
}

extern "C" bool _rpcsx_collectGameInfo(JNIEnv *env, std::string_view rootDir,
                                       long progressId) {

  if (std::filesystem::is_regular_file(g_cfg_vfs.get_dev_flash() +
                                       "/vsh/module/vsh.self")) {
    sendVshBootable(env, progressId);
  }

  collectGameInfo(env, progressId, {std::string(rootDir)});
  return true;
}

// Serialises disc probing against everything that owns g_fxo: booting AND shutdown.
//
// probeDiscInfo mounts the image into the GLOBAL vfs to read its PARAM.SFO, and the library
// scan runs it on a background thread. vfs::mount starts with g_fxo->need<vfs_manager>(),
// so it is not just the mount table that has to be quiet, it is g_fxo itself.
//
// Closing a game calls Emu.Kill(), which resets g_fxo, and closing also returns to the
// library, which starts a rescan. Those two overlap, and the probe aborted the process
// inside vfs::mount. Booting another game immediately made it far more likely, which is
// what "crashes if I open a new game within ~2s, fine after ~10s" actually was: waiting
// let the teardown and the scan finish, not the boot.
//
// An Emu.IsStopped() check alone is not enough. It is a plain read, the state reaches
// stopped before g_fxo teardown has finished, and a boot or kill can start right after it.
static std::mutex g_emu_lifecycle_mutex;

// Held so a probe cannot be inside vfs::mount while this resets g_fxo underneath it.
extern "C" void _rpcsx_shutdown() {
  std::lock_guard vfs_lock(g_emu_lifecycle_mutex);
  Emu.Kill();
}

extern "C" int _rpcsx_boot(std::string_view path_) {
  // Taken FIRST, before the Kill below, and held across the whole boot.
  //
  // Emu.Kill() spawns an "Emulation Join Thread" that joins every emulator thread. It is
  // not safe to have two of those in flight: they end up joining each other and neither
  // finishes, which pins the emulator in a non-stopped state forever. Observed on device
  // as FOUR live join threads plus six leaked AudioTrack threads after a few close-then-
  // boot cycles, with the join thread logging "Thread [Emulation Join Thread] is too
  // sleepy" against itself.
  //
  // This used to Kill() before taking the lock, so a Close (which calls _rpcsx_kill) and
  // the boot that followed it each started their own teardown and deadlocked. The pile-up
  // is what left the emulator never reaching stopped, and every later boot then failed and
  // bounced the user back to the library.
  std::lock_guard emu_lock(g_emu_lifecycle_mutex);

  // BootGame's restore_on_no_boot path does ensure(IsStopped()) whenever the boot fails,
  // so entering it while the emulator is still winding down aborts the process outright
  // (System.cpp, "Verification failed (object: 0x0)").
  //
  // Kill() is idempotent and returns quickly when already stopped. The bound is generous
  // because a real teardown joins the RSX and SPU threads and flushes caches; past it we
  // boot anyway and let BootGame report a normal error rather than hanging the UI.
  if (!Emu.IsStopped()) {
    rpcsx_android.notice("boot: previous VM still running, stopping it first");
    Emu.Kill();

    for (int waited = 0; !Emu.IsStopped() && waited < 10000; waited += 20) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    if (!Emu.IsStopped()) {
      rpcsx_android.error("boot: previous VM did not stop in time, booting anyway");
    }
  }

  Emu.SetForceBoot(true);
  std::string path = std::string(path_);
  while (path.ends_with('/')) {
    path.pop_back();
  }

  // Folder-format games boot through their EBOOT, not the directory.
  //
  // A PKG install and a JB folder dump both land as a directory, and RPCSX's own UI
  // boots them by the bootable path the installer reported -- which is the EBOOT.BIN,
  // never the folder. Handing BootGame the directory instead came up as a permanent
  // black screen on an installed title. locateEbootPath handles both layouts
  // (PS3_GAME/USRDIR for a disc, USRDIR for an installed game); if it finds nothing we
  // fall through to the original behaviour rather than failing the boot outright.
  if (fs::is_dir(path)) {
    if (auto eboot = locateEbootPath(path); !eboot.empty() && fs::is_file(eboot)) {
      rpcsx_android.notice("boot: resolved directory '%s' to '%s'", path, eboot);
      path = eboot;
    }
  }

  return static_cast<int>(Emu.BootGame(path, "", false, cfg_mode::custom));
}

extern "C" int _rpcsx_getState() {
  return static_cast<int>(Emu.GetStatus(false));
}
extern "C" void _rpcsx_kill() {
  std::lock_guard vfs_lock(g_emu_lifecycle_mutex);
  Emu.Kill();
}
extern "C" void _rpcsx_resume() { Emu.Resume(); }

extern "C" void _rpcsx_openHomeMenu() {
  if (auto padThread = pad::get_pad_thread(true)) {
    padThread->open_home_menu();
  }
}

extern "C" std::string _rpcsx_getTitleId() { return Emu.GetTitleID(); }

// ---------------------------------------------------------------------------
// Save states
//
// RPCS3's model is NOT PCSX2's numbered slots. Saving tears the VM down and
// writes a .SAVESTAT.zst; states are then addressed as a rolling history where
// index 1 is the MOST RECENT, 2 the one before it, and so on (how many are kept
// is Savestate@@"Maximum SaveState Files"). So "slot 3" here means "the third
// most recent state", not "the state I put in slot 3" -- the UI has to say so,
// because silently renumbering a user's saves is worse than the constraint.
//
// The save recipe below is lifted from RPCS3's own home menu
// (overlay_home_menu_savestate.cpp): outside suspend mode it arms a restart in
// after_kill_callback and holds the window open, so the game resumes from the
// state it just wrote instead of exiting to the library.
// ---------------------------------------------------------------------------

extern "C" bool _rpcsx_saveState() {
  if (!Emu.IsRunning() && !Emu.IsPaused()) {
    rpcsx_android.error("saveState: no game is running");
    return false;
  }

  Emu.CallFromMainThread([]() {
    if (!g_cfg.savestate.suspend_emu.get()) {
      // Come straight back up from the state we are about to write. Without
      // this, saving drops the user to the library, which reads as a crash.
      Emu.after_kill_callback = []() { Emu.Restart(true, false); };
      Emu.SetContinuousMode(true);
    }

    Emu.Kill(false, true);
  });

  return true;
}

// index is 1-based and 1 == most recent, matching boot_current_game_savestate.
extern "C" bool _rpcsx_loadState(unsigned int index) {
  if (index == 0) {
    index = 1;
  }

  // testing=true only probes for existence, so a missing state is reported here
  // rather than tearing down a running game and failing to boot afterwards.
  if (!boot_current_game_savestate(true, index)) {
    rpcsx_android.error("loadState: no savestate at index %u", index);
    return false;
  }

  Emu.CallFromMainThread(
      [index]() { boot_current_game_savestate(false, index); });
  return true;
}

// ---------------------------------------------------------------------------
// Patches / graphics mods
//
// RPCS3 keeps two files:
//   patches/patch.yml   - the patch DATABASE, downloaded from rpcs3.net
//   patch_config.yml    - which patches are ENABLED, keyed
//                         hash -> description -> title -> serial -> app_version
//
// Both are YAML with a fiddly nested shape, and patch_engine already parses and
// writes them. So the app downloads the bytes and everything else happens here,
// through RPCS3's own code -- reimplementing the format in Kotlin would be a
// second parser to keep in sync with upstream.
// ---------------------------------------------------------------------------

/** Import a downloaded patch.yml. Returns the number of patches merged, or -1. */
static std::string json_quote(std::string_view v) {
  std::string out = "\"";
  for (char c : v) {
    switch (c) {
    case '"': out += "\\\""; break;
    case '\\': out += "\\\\"; break;
    case '\n': out += "\\n"; break;
    case '\r': out += "\\r"; break;
    case '\t': out += "\\t"; break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        char buf[8];
        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
        out += buf;
      } else {
        out += c;
      }
    }
  }
  return out + "\"";
}

extern "C" int _rpcsx_patchesImport(std::string_view content) {
  patch_engine::patch_map patches;
  std::stringstream log;

  if (!patch_engine::load(patches, "<downloaded>", std::string(content), true,
                          &log)) {
    rpcsx_android.error("patchesImport: parse failed: %s", log.str());
    return -1;
  }

  usz count = 0;
  usz total = 0;
  if (!patch_engine::import_patches(patches, patch_engine::get_patches_path() +
                                                 "patch.yml",
                                    count, total, &log)) {
    rpcsx_android.error("patchesImport: write failed: %s", log.str());
    return -1;
  }

  rpcsx_android.success("patchesImport: %u of %u patches", count, total);
  return static_cast<int>(count);
}

/**
 * Patches applicable to `serial`, as JSON.
 *
 * Emitted by hand rather than with a JSON library because the core does not link
 * one, and the shape is small and fixed.
 */
/**
 * Read a PS3 disc image's identity without booting it.
 *
 * The library scanner had no way to identify a PS3 game: the ARMSX2 probe it
 * inherited looks for a PS2 SYSTEM.CNF, so every ISO came back with no serial,
 * and no serial means no title, no compatibility entry and no cover art.
 *
 * Mounts the ISO through the core's own reader (which also handles 3k3y and
 * Redump-encrypted images) and reads PS3_GAME/PARAM.SFO for TITLE_ID / TITLE.
 * ICON0.PNG is copied out to `iconOut` when present -- that is the artwork the
 * disc itself carries and what desktop RPCS3 shows in its game grid, so covers
 * need no network and always match the disc in the drive.
 *
 * Returns a JSON object, or "{}" when the image is not a readable PS3 disc.
 * Must not run while a game is loaded: load_iso installs a process-wide virtual
 * device, so probing mid-session would swap the running title's disc.
 */
// Read TITLE_ID/TITLE out of <root>/PARAM.SFO and, when iconOut is set, copy
// <root>/ICON0.PNG next to it. Returns "{}" when the folder has no usable SFO.
// Shared by the ISO path (root inside the mounted virtual device) and the
// folder path (root on real storage), which read identically once mounted.
static std::string read_sfo_game_info(const std::string &root,
                                      std::string_view iconOut) {
  if (!fs::is_file(root + "/PARAM.SFO")) {
    return "{}";
  }

  fs::file sfo{root + "/PARAM.SFO"};
  if (!sfo) {
    return "{}";
  }

  const auto psf = psf::load_object(sfo, root + "/PARAM.SFO");
  const auto titleId = std::string(psf::get_string(psf, "TITLE_ID"));
  const auto title = std::string(psf::get_string(psf, "TITLE"));

  if (titleId.empty()) {
    return "{}";
  }

  bool haveIcon = false;

  if (!iconOut.empty() && fs::is_file(root + "/ICON0.PNG")) {
    if (fs::file icon{root + "/ICON0.PNG"}) {
      const auto bytes = icon.to_vector<u8>();
      if (!bytes.empty()) {
        if (fs::file out{std::string(iconOut), fs::rewrite}) {
          out.write(bytes.data(), bytes.size());
          haveIcon = true;
        }
      }
    }
  }

  // json_quote() supplies the surrounding quotes itself.
  return "{\"titleId\":" + json_quote(titleId) + ",\"title\":" +
         json_quote(title) + ",\"icon\":" + (haveIcon ? "true" : "false") + "}";
}

extern "C" std::string _rpcsx_probeDiscInfo(std::string_view isoPath,
                                            std::string_view iconOut) {
  if (isoPath.empty() || !Emu.IsStopped()) {
    return "{}";
  }

  const std::string path(isoPath);

  // Folder-format games need no mount at all: a JB folder dump keeps its
  // metadata in <dir>/PS3_GAME, and an installed/HDD game folder (what
  // RPCS3's own games directory holds, and the shape some prototype dumps
  // ship in) keeps it at the top level next to USRDIR.
  if (fs::is_dir(path)) {
    std::string result = "{}";
    try {
      result = read_sfo_game_info(path + "/PS3_GAME", iconOut);
      if (result == "{}") {
        result = read_sfo_game_info(path, iconOut);
      }
    } catch (const std::exception &e) {
      rpcsx_android.error("probeDiscInfo('%s') [folder] failed: %s", path,
                          e.what());
    }
    return result;
  }

  if (!is_iso_file(path)) {
    return "{}";
  }

  std::string result = "{}";

  // Held across the whole mount/read/unmount so a boot cannot mount underneath us.
  std::lock_guard vfs_lock(g_emu_lifecycle_mutex);

  // Re-checked under the lock: a boot may have started while we were waiting for it,
  // and mounting into a live VM's vfs would shadow the disc it is running from.
  if (!Emu.IsStopped()) {
    return "{}";
  }

  // unload_iso() on every exit: leaving the device mounted would shadow the
  // next boot's disc with whichever image was scanned last.
  try {
    load_iso(path);
    result = read_sfo_game_info(iso_device::virtual_device_name + "/PS3_GAME",
                                iconOut);
  } catch (const std::exception &e) {
    rpcsx_android.error("probeDiscInfo('%s') failed: %s", path, e.what());
  }

  unload_iso();
  return result;
}

extern "C" std::string _rpcsx_patchesList(std::string_view serial) {
  patch_engine::patch_map db;
  std::stringstream log;
  patch_engine::load(db, patch_engine::get_patches_path() + "patch.yml", {},
                     false, &log);

  const patch_engine::patch_map enabled = patch_engine::load_config();

  std::string out = "[";
  bool first = true;

  for (const auto &[hash, container] : db) {
    for (const auto &[description, info] : container.patch_info_map) {
      // A patch lists the titles it applies to; keep only ones naming this
      // serial, or the "all games" wildcard RPCS3 uses.
      // titles maps GAME TITLE -> serial -> app_version. "all" is RPCS3's
      // wildcard serial, used by patches that are not tied to one release.
      bool applies = serial.empty();
      std::string app_version = "all";
      std::string game_title;

      for (const auto &[title, serials] : info.titles) {
        for (const auto &[ser, versions] : serials) {
          const bool hit = (!serial.empty() && ser == serial) || ser == "all";
          if (hit || serial.empty()) {
            if (game_title.empty()) game_title = title;
          }
          if (hit) {
            applies = true;
            game_title = title;
            if (!versions.empty()) app_version = versions.begin()->first;
          }
        }
      }

      if (!applies) continue;

      bool is_on = false;
      if (auto it = enabled.find(hash); it != enabled.end()) {
        if (auto p = it->second.patch_info_map.find(description);
            p != it->second.patch_info_map.end()) {
          for (const auto &[title, serials] : p->second.titles)
            for (const auto &[ser, versions] : serials)
              for (const auto &[ver, values] : versions)
                if (values.enabled) is_on = true;
        }
      }

      if (!first) out += ",";
      first = false;
      out += "{\"hash\":" + json_quote(hash) +
             ",\"name\":" + json_quote(description) +
             ",\"author\":" + json_quote(info.author) +
             ",\"notes\":" + json_quote(info.notes) +
             ",\"version\":" + json_quote(info.patch_version) +
             ",\"appVersion\":" + json_quote(app_version) +
             ",\"game\":" + json_quote(game_title) +
             ",\"enabled\":" + (is_on ? "true" : "false") + "}";
    }
  }

  return out + "]";
}

/** Enable or disable one patch and persist patch_config.yml. */
extern "C" bool _rpcsx_patchSetEnabled(std::string_view hash,
                                       std::string_view description,
                                       std::string_view serial,
                                       std::string_view appVersion,
                                       bool enabled) {
  patch_engine::patch_map db;
  std::stringstream log;
  patch_engine::load(db, patch_engine::get_patches_path() + "patch.yml", {},
                     false, &log);

  patch_engine::patch_map config = patch_engine::load_config();

  auto h = db.find(std::string(hash));
  if (h == db.end()) {
    rpcsx_android.error("patchSetEnabled: unknown hash %s", hash);
    return false;
  }

  auto p = h->second.patch_info_map.find(std::string(description));
  if (p == h->second.patch_info_map.end()) {
    rpcsx_android.error("patchSetEnabled: unknown patch %s", description);
    return false;
  }

  // save_config only writes entries whose `enabled` is set, so disabling is
  // simply not carrying the entry over.
  auto &entry = config[std::string(hash)];
  entry.hash = std::string(hash);
  entry.version = h->second.version;

  auto &info = entry.patch_info_map[std::string(description)];
  info = p->second;

  for (auto &[title, serials] : info.titles)
    for (auto &[ser, versions] : serials)
      for (auto &[ver, values] : versions)
        if (ser == serial || serial.empty() || ser == "all")
          values.enabled = enabled;

  patch_engine::save_config(config);
  return true;
}

extern "C" bool _rpcsx_hasState(unsigned int index) {
  if (index == 0) {
    index = 1;
  }

  return boot_current_game_savestate(true, index);
}

extern "C" bool _rpcsx_surfaceEvent(JNIEnv *env, jobject surface, jint event) {
  rpcsx_android.warning("surface event %p, %d", surface, event);

  if (event == 2) {
    auto prevWindow = g_native_window.exchange(nullptr);
    if (prevWindow != nullptr) {
      ANativeWindow_release(prevWindow);
    }

    // get_pad_thread() defaults to relaxed=false, which is
    // ensure(g_pad_thread.load()) -- it ABORTS on null rather than returning it,
    // so this `if` never got the chance to guard anything. With no game running
    // there is no pad thread, and simply navigating away from the surface (e.g.
    // opening Setup from the library) killed the process.
    //
    // _rpcsx_openHomeMenu already uses the relaxed form; this call site was
    // missed.
    if (auto padThread = pad::get_pad_thread(true)) {
      padThread->open_home_menu();
    }

    Emu.Pause();
  } else {
    auto newWindow = ANativeWindow_fromSurface(env, surface);

    if (newWindow == nullptr) {
      rpcsx_android.fatal("returned native window is null, surface %p",
                          surface);
      return false;
    }

    auto prevWindow = g_native_window.exchange(newWindow);

    if (newWindow != prevWindow) {
      ANativeWindow_acquire(newWindow);

      if (prevWindow != nullptr) {
        ANativeWindow_release(prevWindow);
      }
    }

    if (event == 0 && Emu.IsPaused()) {
      Emu.Resume();
    }
  }

  return true;
}

extern "C" bool _rpcsx_usbDeviceEvent(int fd, int vendorId, int productId,
                                      int event) {
  rpcsx_android.warning(
      "usb device event %d fd: %d, vendorId: %d, productId: %d", event, fd,
      vendorId, productId);

  {
    std::lock_guard lock(g_android_usb_devices_mutex);

    if (event == 0) {
      g_android_usb_devices.push_back({
          .fd = int(fd),
          .vendorId = u16(vendorId),
          .productId = u16(productId),
      });
    } else {
      auto filter = [fd](auto device) { return device.fd == fd; };
      if (auto it = std::ranges::find_if(g_android_usb_devices, filter);
          it != g_android_usb_devices.end()) {
        g_android_usb_devices.erase(it);
      }
    }
  }

  {
    auto selectedHandler = g_cfg_input.player1.handler.get();
    std::string selectedDevice;

    std::map<pad_handler, std::pair<std::unique_ptr<PadHandlerBase>,
                                    std::vector<std::string>>>
        handlerToDevices;

    auto collectDevices = [&]<typename T>(T handler) {
      handler->Init();

      std::vector<std::string> devices;
      for (const auto &device : handler->list_devices()) {
        devices.push_back(device.name);
      }

      auto type = handler->m_type;

      handlerToDevices[type] = std::pair{
          std::move(handler),
          std::move(devices),
      };
    };

    collectDevices(std::make_unique<dualsense_pad_handler>());
    collectDevices(std::make_unique<ds4_pad_handler>());
    collectDevices(std::make_unique<ds3_pad_handler>());

    if (handlerToDevices[selectedHandler].second.empty()) {
      selectedHandler = pad_handler::null;
    }

    if (!handlerToDevices[pad_handler::dualsense].second.empty()) {
      selectedHandler = pad_handler::dualsense;
    } else if (!handlerToDevices[pad_handler::ds4].second.empty()) {
      selectedHandler = pad_handler::ds4;
    } else if (!handlerToDevices[pad_handler::ds3].second.empty()) {
      selectedHandler = pad_handler::ds3;
    }

    if (selectedHandler == pad_handler::null) {
      selectedHandler = pad_handler::virtual_pad;
    }

    // Ports 2-7 are always virtual. The probe above only ever assigns player 1,
    // so without this every extra controller the app routed had no pad on the
    // other side and simply did nothing.
    //
    // Deliberately OUTSIDE the "did player 1's handler change?" branch below:
    // once player 1 is saved as virtual_pad, that branch stops running on every
    // later boot, and ports 2-7 were left Null forever -- which is exactly what
    // the pad log showed (Pad 0 Virtual, Pads 1-6 Null).
    //
    // The app is the input source for them either way: it reads Android input
    // events itself and pushes whole-pad snapshots through _rpcsx_overlayPadData,
    // so a core-side HID handler is not wanted here.
    bool extra_ports_changed = false;
    for (usz i = 1; i < g_cfg_input.player.size(); i++) {
      if (g_cfg_input.player[i]->handler.get() != pad_handler::virtual_pad) {
        g_cfg_input.player[i]->handler.set(pad_handler::virtual_pad);
        g_cfg_input.player[i]->device.from_string("Virtual");
        extra_ports_changed = true;
      }
    }

    if (extra_ports_changed) {
      g_cfg_input.save("", g_cfg_input_configs.default_config);

      if (!Emu.IsStopped()) {
        pad::reset(Emu.GetTitleID());
      }
    }

    if (selectedHandler != g_cfg_input.player1.handler.get()) {
      rpcsx_android.warning("install %s pad handler", selectedHandler);

      g_cfg_input.player1.handler.set(selectedHandler);

      if (selectedHandler == pad_handler::null) {
        g_cfg_input.player1.device.from_default();
      } else if (selectedHandler == pad_handler::virtual_pad) {
        g_cfg_input.player1.handler.set(pad_handler::virtual_pad);
        g_cfg_input.player1.device.from_string("Virtual");
      } else {
        g_cfg_input.player1.device.from_string(
            handlerToDevices[selectedHandler].second.front());
        handlerToDevices[selectedHandler].first->init_config(
            &g_cfg_input.player1.config);
        if (selectedHandler != pad_handler::virtual_pad) {
          std::lock_guard lock(g_virtual_pad_mutex);
          g_virtual_pads[0] = nullptr;
        }
      }

      g_cfg_input.save("", g_cfg_input_configs.default_config);

      if (!Emu.IsStopped()) {
        pad::reset(Emu.GetTitleID());
      }
    }
  }

  return true;
}

static bool installPup(JNIEnv *env, fs::file &&pup_f, jlong progressId) {
  Progress progress(env, progressId);

  pup_object pup(std::move(pup_f));
  AtExit atExit{[&] { pup.file().release_handle(); }};

  if (static_cast<pup_error>(pup) == pup_error::hash_mismatch) {
    rpcsx_android.fatal("installFw: invalid PUP");
    progress.failure("Selected file is not firmware update file");
    return false;
  }

  if (static_cast<pup_error>(pup) != pup_error::ok) {
    rpcsx_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  fs::file update_files_f = pup.get_file(0x300);

  const usz update_files_size = update_files_f ? update_files_f.size() : 0;

  if (!update_files_size) {
    rpcsx_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  tar_object update_files(update_files_f);

  auto update_filenames = update_files.get_filenames();
  update_filenames.erase(std::remove_if(update_filenames.begin(),
                                        update_filenames.end(),
                                        [](const std::string &s) {
                                          return !s.starts_with("dev_flash_");
                                        }),
                         update_filenames.end());

  if (update_filenames.empty()) {
    rpcsx_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  std::string version_string;

  if (fs::file version = pup.get_file(0x100)) {
    version_string = version.to_string();
  }

  if (const usz version_pos = version_string.find('\n');
      version_pos != std::string::npos) {
    version_string.erase(version_pos);
  }

  if (version_string.empty()) {
    rpcsx_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  sendVshBootable(env, progressId);

  jlong processed = 0;
  for (const auto &update_filename : update_filenames) {
    auto update_file_stream = update_files.get_file(update_filename);

    if (update_file_stream->m_file_handler) {
      // Forcefully read all the data
      update_file_stream->m_file_handler->handle_file_op(
          *update_file_stream, 0, update_file_stream->get_size(umax), nullptr);
    }

    fs::file update_file = fs::make_stream(std::move(update_file_stream->data));

    SCEDecrypter self_dec(update_file);
    self_dec.LoadHeaders();
    self_dec.LoadMetadata(SCEPKG_ERK, SCEPKG_RIV);
    self_dec.DecryptData();

    auto dev_flash_tar_f = self_dec.MakeFile();

    if (dev_flash_tar_f.size() < 3) {
      rpcsx_android.error(
          "Firmware installation failed: Firmware could not be decompressed");

      progress.failure("Firmware update file could not be decompressed");
      return false;
    }

    tar_object dev_flash_tar(dev_flash_tar_f[2]);

    if (!dev_flash_tar.extract()) {

      rpcsx_android.error("Error while installing firmware: TAR contents are "
                          "invalid. (package=%s)",
                          update_filename);

      progress.failure(fmt::format("TAR contents are invalid (package=%s)",
                                   update_filename));
      return false;
    }

    if (!progress.report(processed++, update_filenames.size())) {
      // Installation was cancelled
      return false;
    }
  }

  sendFirmwareInstalled(env, utils::get_firmware_version());

  g_compilationQueue.push(progress,
                          g_cfg_vfs.get_dev_flash() + "/vsh/module/vsh.self");
  return true;
}

// Install one or more PKG files as a single package.
//
// A split release ships as .pkg_0/.pkg_1/... and only installs correctly when every
// part is handed to package_reader::extract_data TOGETHER -- which already takes a
// deque and has always supported this. Only the entry point was single-file, so a
// split game could not be installed at all. Parts must arrive in order; the caller
// sorts them by filename.
static bool installPkg(JNIEnv *env, std::vector<fs::file> &&files,
                       jlong progressId) {
  Progress progress(env, progressId);

  if (files.empty()) {
    progress.failure("No package files selected");
    return false;
  }

  std::deque<package_reader> readers;
  std::deque<std::string> bootable_paths;
  for (std::size_t i = 0; i < files.size(); ++i) {
    readers.emplace_back(fmt::format("part%u.pkg", i), std::move(files[i]));
  }

  AtExit atExit{[&] {
    for (auto &reader : readers) {
      reader.file().release_handle();
    }
  }};

  package_install_result result = {};
  named_thread worker("PKG Installer", [&readers, &result, &bootable_paths] {
    result = package_reader::extract_data(readers, bootable_paths);
    return result.error == package_install_result::error_type::no_error;
  });

  for (auto &reader : readers) {
    if (auto gameInfo = fetchGameInfo(reader.get_psf())) {
      sendGameInfo(env, progressId, {{*gameInfo}});
    }
  }

  const jlong maxProgress = 10000;

  while (true) {
    std::uint64_t totalProgress = 0;
    for (auto &reader : readers) {
      if (result.error != package_install_result::error_type::no_error) {
        progress.failure("Installation failed");
        for (package_reader &reader : readers) {
          reader.abort_extract();
        }
        return false;
      }

      totalProgress += reader.get_progress(maxProgress);
    }

    if (totalProgress == maxProgress * readers.size()) {
      break;
    }

    totalProgress /= readers.size();

    if (!progress.report(totalProgress, maxProgress)) {
      for (package_reader &reader : readers) {
        reader.abort_extract();
      }

      return false;
    }

    std::this_thread::sleep_for(std::chrono::seconds(2));
  }

  if (worker()) {
    auto paths = std::vector(bootable_paths.begin(), bootable_paths.end());
    collectGameInfo(env, -1, paths);

    // Deliberately NOT queued for precompilation.
    //
    // CompilationQueue::impl does Emu.SetState(running), g_fxo->init<>() of the
    // progress-dialog server and main_ppu_module, and vm::init(). That is only safe in
    // a process that has not already brought those up -- which is true during onboarding
    // (firmware install) and false for a PKG installed from the library after a game has
    // been booted. init<> returns null the second time and vm::init() re-maps an address
    // space that already exists, which crashed the app the moment extraction finished.
    // The extraction itself had already completed, which is why the game still appeared
    // in the library on the next launch.
    //
    // Nothing is lost by skipping it: the game precompiles on its first boot, with the
    // normal compile screen, exactly as a disc game does. RPCS3 desktop does not
    // precompile on install either.
  }

  return true;
}

static bool installEdat(JNIEnv *env, fs::file &&file, jlong progressId,
                        std::string_view rootPath = {}) {
  Progress progress(env, progressId);

  NPD_HEADER npdHeader;
  if (!file.read(npdHeader)) {
    progress.failure("Invalid EDAT file");
    return false;
  }

  if (!rootPath.empty()) {
    auto ebootPath = locateEbootPath(rootPath);
    auto sfoPath = locateParamSfoPath(rootPath);

    if (sfoPath.empty()) {
      progress.failure("Game is broken: PARAM.SFO not found");
      return false;
    }

    auto psf = psf::load_object(sfoPath);
    auto contentId = psf::get_string(psf, "CONTENT_ID");

    if (contentId != npdHeader.content_id) {
      progress.failure(fmt::format("File cannot be used for this game. EDAT "
                                   "content ID missmatch %s vs %s",
                                   contentId, npdHeader.content_id));
      return false;
    }
  }

  const auto licenseFile =
      fmt::format("%shome/%s/exdata/%s.edat", rpcs3::utils::get_hdd0_dir(),
                  Emu.GetUsr(), npdHeader.content_id);

  file.seek(0);

  std::vector<std::uint8_t> bytes(file.size());
  if (!file.read(bytes)) {
    progress.failure("Failed to read key");
    return false;
  }

  if (!fs::write_file(licenseFile, fs::open_mode::create + fs::open_mode::trunc,
                      bytes)) {
    progress.failure(fmt::format("Failed to write EDAT to %s", licenseFile));
    return false;
  }

  auto root = std::string(rootPath);

  if (root.empty()) {
    root = rpcs3::utils::get_hdd0_dir() + "game";
  }

  collectGameInfo(env, progressId, {std::move(root)});
  return true;
}

static bool installRap(JNIEnv *env, fs::file &&file, jlong progressId,
                       std::string_view rootPath) {
  Progress progress(env, progressId);

  auto ebootPath = locateEbootPath(rootPath);

  std::vector<std::uint8_t> bytes;
  if (!file.read(bytes, 16)) {
    progress.failure("Failed to read key");
    return false;
  }

  SelfAdditionalInfo info;
  decrypt_self(fs::file(ebootPath), nullptr, &info);

  auto npd = [&]() -> NPD_HEADER * {
    for (auto &supplemental : info.supplemental_hdr) {
      if (supplemental.type == 3) {
        return &supplemental.PS3_npdrm_header.npd;
      }
    }

    return nullptr;
  }();

  if (npd == nullptr) {
    progress.failure("Failed to fetch NPDRM of SELF");
    return false;
  }

  const auto licenseFile =
      fmt::format("%shome/%s/exdata/%s.rap", rpcs3::utils::get_hdd0_dir(),
                  Emu.GetUsr(), npd->content_id);

  if (!fs::write_file(licenseFile, fs::open_mode::create + fs::open_mode::trunc,
                      bytes)) {
    progress.failure(fmt::format("Failed to write key to %s", licenseFile));
    return false;
  }

  if (!decrypt_self(fs::file(ebootPath))) {
    progress.failure("Provided key is invalid for selected game");
    fs::remove_file(licenseFile);
    return false;
  }

  collectGameInfo(env, -1, {std::string(rootPath)});
  g_compilationQueue.push(progress, std::move(ebootPath));
  return true;
}

static bool installIso(JNIEnv *env, fs::file &&file, jlong progressId) {
  auto optIso = iso_dev::open(std::make_unique<file_view_block_dev>(file));
  Progress progress(env, progressId);

  if (!optIso) {
    progress.failure("Failed to read ISO");
    return false;
  }

  auto iso = std::move(*optIso);
  auto sfo_raw_file = iso.open("PS3_GAME/PARAM.SFO", fs::read);

  if (!sfo_raw_file) {
    progress.failure("Failed to locate PARAM.SFO in ISO");
    return false;
  }

  fs::file sfo_file;
  sfo_file.reset(std::move(sfo_raw_file));

  auto sfo = psf::load_object(sfo_file, "iso://PS3_GAME/PARAM.SFO");
  auto title_id = psf::get_string(sfo, "TITLE_ID");

  if (title_id.empty()) {
    progress.failure("Failed to fetch TITLE_ID from PARAM.SFO in ISO");
    return false;
  }

  if (auto gameInfo = fetchGameInfo(sfo)) {
    sendGameInfo(env, progressId, {{*gameInfo}});
  }

  std::filesystem::path destinationPath =
      fs::get_config_dir() + "games/" + std::string(title_id);
  std::size_t filesCount = 0;

  auto roots = [&] {
    std::vector<std::filesystem::path> result;
    std::vector<std::filesystem::path> workList;
    workList.push_back({});
    result.push_back({});

    while (!workList.empty()) {
      auto path = std::move(workList.back());
      workList.pop_back();

      fs::dir dir;
      dir.reset(iso.open_dir(path));

      for (auto &entry : dir) {
        if (entry.name == "." || entry.name == "..") {
          continue;
        }
        if (entry.name == "PS3_UPDATE" && path.empty()) {
          continue;
        }

        if (entry.is_directory) {
          result.push_back(path / entry.name);
          workList.push_back(path / entry.name);
        } else {
          filesCount++;
        }
      }
    }

    return result;
  }();

  progress.report(0, filesCount);

  std::size_t processedFiles = 0;
  std::error_code ec;

  for (auto &root : roots) {
    auto rootDestPath = root.empty() ? destinationPath : destinationPath / root;

    std::filesystem::create_directories(rootDestPath, ec);
    if (ec) {
      progress.failure(fmt::format("Failed to create dir %s: %s",
                                   rootDestPath.string(), ec.message()));
      return false;
    }

    fs::dir dir;
    dir.reset(iso.open_dir(root));

    for (auto &entry : dir) {
      if (entry.name == "." || entry.name == "..") {
        continue;
      }

      auto entryDestPath = rootDestPath / entry.name;

      if (entry.is_directory) {
        std::filesystem::create_directories(entryDestPath, ec);
        if (ec) {
          progress.failure(fmt::format("Failed to create dir %s: %s",
                                       entryDestPath.string(), ec.message()));
          return false;
        }

        continue;
      }
      auto raw_file = iso.open(root / entry.name, fs::read);

      if (!raw_file) {
        progress.failure(fmt::format("Failed to open file in ISO: %s",
                                     (root / entry.name).string()));
        return false;
      }

      fs::file file;
      file.reset(std::move(raw_file));

      if (!fs::write_file(entryDestPath,
                          fs::open_mode::create + fs::open_mode::trunc,
                          file.to_vector<std::uint8_t>())) {
        progress.failure(fmt::format("Failed to write file: %s, dest %s",
                                     entryDestPath.string(),
                                     destinationPath.string()));
        return false;
      }

      progress.report(processedFiles++, filesCount);
    }
  }

  collectGameInfo(env, -1, {destinationPath});
  auto ebootPath = locateEbootPath(destinationPath.string());
  g_compilationQueue.push(progress, std::move(ebootPath));
  return true;
}

extern "C" bool _rpcsx_installFw(JNIEnv *env, int fd, long progressId) {
  return installPup(env, fs::file::from_native_handle(fd), progressId);
}

extern "C" bool _rpcsx_isInstallableFile(jint fd) {
  auto file = fs::file::from_native_handle(fd);
  AtExit atExit{[&] { file.release_handle(); }};

  auto type = getFileType(file);
  file.seek(0);
  return type != FileType::Unknown &&
         type != FileType::Rap; // FIXME: implement rap preinstallation
}

extern "C" jstring _rpcsx_getDirInstallPath(JNIEnv *env, jint fd) {
  auto file = fs::file::from_native_handle(fd);
  AtExit atExit{[&] { file.release_handle(); }};

  auto psf = psf::load_object(file, "");
  if (auto gameInfo = fetchGameInfo(psf)) {
    return wrap(env, gameInfo->path);
  }

  return nullptr;
}

// Install a set of PKG parts as one package. All parts must be PKGs, in order.
// Single-file installs keep going through _rpcsx_install, which also handles
// PUP/EDAT/ISO by sniffing the file type.
extern "C" bool _rpcsx_installSplitPkg(JNIEnv *env, const int *fds, int count,
                                       long progressId) {
  if (fds == nullptr || count <= 0) {
    Progress(env, progressId).failure("No package files selected");
    return false;
  }

  std::vector<fs::file> files;
  files.reserve(count);
  for (int i = 0; i < count; ++i) {
    files.push_back(fs::file::from_native_handle(fds[i]));
  }

  // The Java side owns every fd; release each handle so closing the
  // ParcelFileDescriptors there is not a double close.
  AtExit atExit{[&] {
    for (auto &f : files) {
      f.release_handle();
    }
  }};

  for (auto &f : files) {
    if (getFileType(f) != FileType::Pkg) {
      Progress(env, progressId)
          .failure("Every selected file must be a .pkg part");
      return false;
    }
    f.seek(0);
  }

  return installPkg(env, std::move(files), progressId);
}

// Delete an installed title's directory (dev_hdd0/game/<TITLEID> and its
// matching save-data-free content). Path comes from the app, which only offers
// this for games it found under the emulator's own game directories.
extern "C" bool _rpcsx_uninstallGame(std::string_view path) {
  if (path.empty()) {
    return false;
  }

  const std::string dir(path);

  // Refuse anything that is not inside the emulator's own game storage, so a
  // mis-sent path cannot delete a user's ROM folder.
  const std::string hdd0 = rpcs3::utils::get_hdd0_dir();
  const std::string games = hdd0 + "game/";
  if (!dir.starts_with(games)) {
    rpcsx_android.error("uninstallGame: refusing path outside %s: %s", games,
                        dir);
    return false;
  }

  if (!fs::is_dir(dir)) {
    return false;
  }

  rpcsx_android.notice("uninstallGame: removing %s", dir);
  return fs::remove_all(dir);
}

extern "C" bool _rpcsx_install(JNIEnv *env, int fd, long progressId) {
  auto file = fs::file::from_native_handle(fd);
  AtExit atExit{[&] { file.release_handle(); }};

  auto type = getFileType(file);
  file.seek(0);

  switch (type) {
  case FileType::Unknown:
    Progress(env, progressId).failure("Unsupported file type");
    return false;

  case FileType::Pup:
    return installPup(env, std::move(file), progressId);

  case FileType::Pkg: {
    std::vector<fs::file> files;
    files.push_back(std::move(file));
    return installPkg(env, std::move(files), progressId);
  }

  case FileType::Edat:
    return installEdat(env, std::move(file), progressId);

  case FileType::Iso:
    return installIso(env, std::move(file), progressId);

  case FileType::Rap:
    Progress(env, progressId)
        .failure("RAP file cannot be preinstalled. Use lock button on "
                 "installed game instead");
    return false;
  }

  return true;
}

extern "C" bool _rpcsx_installKey(JNIEnv *env, int fd, long progressId,
                                  std::string_view gamePath) {
  auto file = fs::file::from_native_handle(fd);
  AtExit atExit{[&] { file.release_handle(); }};

  auto type = getFileType(file);
  file.seek(0);

  if (type == FileType::Rap) {
    return installRap(env, std::move(file), progressId, gamePath);
  }

  if (type == FileType::Edat) {
    return installEdat(env, std::move(file), progressId, gamePath);
  }

  Progress(env, progressId).failure("Unsupported key type");
  return false;
}

extern "C" std::string _rpcsx_systemInfo() {
  std::string result;

  fmt::append(result, "%s\n\nLLVM CPU: %s\n\n", utils::get_system_info(),
              fallback_cpu_detection());

  {
    vk::instance device_enum_context;
    if (device_enum_context.create("RPCS3")) {
      device_enum_context.bind();
      const std::vector<vk::physical_device> &gpus =
          device_enum_context.enumerate_devices();

      for (const auto &gpu : gpus) {
        // Upstream has no get_driver_name()/get_driver_vk_version(); the
        // vendor enum plus the driver version string is what it exposes.
        fmt::append(result, "GPU: %s\n\nDriver: v%s",
                    gpu.get_name(), gpu.get_driver_version());
      }
    }
  }

  return result;
}

static cfg::_base *find_cfg_node(cfg::_base *root, std::string_view path) {
  auto pathList = fmt::split(path, {"@@"});
  std::ranges::reverse(pathList);

  while (!pathList.empty()) {
    auto elem = pathList.back();
    pathList.pop_back();
    if (elem.empty()) {
      continue;
    }

    auto root_node = dynamic_cast<cfg::node *>(root);
    if (root_node == nullptr) {
      return nullptr;
    }

    cfg::_base *child_node = nullptr;

    for (auto node : root_node->get_nodes()) {
      if (node->get_name() == elem) {
        child_node = node;
        break;
      }
    }

    if (child_node == nullptr) {
      return nullptr;
    }

    root = child_node;
  }

  return root;
}

extern "C" void _rpcsx_loginUser(std::string_view userId) {
  Emu.SetUsr(std::string(userId));
}

extern "C" std::string _rpcsx_getUser() { return Emu.GetUsr(); }

// ---------------------------------------------------------------------------
// Settings bridge.
//
// The Compose settings screen is GENERATED from this JSON -- it is not a
// hand-written list of screens. That is how all ~281 entries of RPCS3's config
// tree (Core, VFS, Video/Vulkan/Perf-Overlay, Audio, Input/Output, System, Net,
// Savestate, Miscellaneous) show up without anyone maintaining a UI per knob.
// Break the shape below and the entire settings UI silently empties out.
//
// Contract expected by ui/settings/SettingsScreen.kt:
//   node  -> { "<child name>": <recurse>, ... }          (no "type" key)
//   leaf  -> { "type": "bool"|"enum"|"int"|"uint"|"float",
//              "value": <bool | string>,
//              "default": <bool | string>,
//              "variants": [ ... ]     // enum only
//              "min": "..", "max": ".." // numerics only
//            }
//
// RPCSX did this by adding to_json/from_json virtuals to Utilities/Config.h and
// pulling in nlohmann. We keep the core clean instead: the only upstream
// addition is min_to_string()/max_to_string() on cfg::_base, because _int/uint/
// _float carry Min/Max as static constexpr TEMPLATE parameters that a _base*
// cannot otherwise reach -- without them every numeric renders as a text box
// instead of a slider.
// ---------------------------------------------------------------------------

static void json_append_escaped(std::string &out, std::string_view value) {
  out += '"';
  for (char c : value) {
    switch (c) {
    case '"': out += "\\\""; break;
    case '\\': out += "\\\\"; break;
    case '\n': out += "\\n"; break;
    case '\r': out += "\\r"; break;
    case '\t': out += "\\t"; break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        fmt::append(out, "\\u%04x", static_cast<unsigned>(c) & 0xff);
      } else {
        out += c;
      }
    }
  }
  out += '"';
}

// cfg::_float reports itself as cfg::type::_int (see its ctor), so the enum
// alone cannot separate float from integer. The range strings can: std::to_string
// on a floating point type always yields a decimal point.
static bool cfg_is_float(const cfg::_base *node) {
  const std::string min = node->min_to_string();
  return min.find('.') != std::string::npos;
}

static const char *cfg_type_name(const cfg::_base *node) {
  switch (node->get_type()) {
  case cfg::type::_bool: return "bool";
  case cfg::type::_enum: return "enum";
  case cfg::type::_int: return cfg_is_float(node) ? "float" : "int";
  case cfg::type::uint: return "uint";
  default: return "string";
  }
}

static void emit_cfg_json(const cfg::_base *node, std::string &out) {
  if (node->get_type() == cfg::type::node) {
    out += '{';
    bool first = true;
    for (const auto *child : static_cast<const cfg::node *>(node)->get_nodes()) {
      if (!first) out += ',';
      first = false;
      json_append_escaped(out, child->get_name());
      out += ':';
      emit_cfg_json(child, out);
    }
    out += '}';
    return;
  }

  const bool is_bool = node->get_type() == cfg::type::_bool;

  out += "{\"type\":";
  json_append_escaped(out, cfg_type_name(node));

  // Booleans go out as real JSON booleans -- the Kotlin reads them with
  // getBoolean(), which throws on a quoted string.
  out += ",\"value\":";
  if (is_bool) {
    out += (node->to_string() == "true") ? "true" : "false";
  } else {
    json_append_escaped(out, node->to_string());
  }

  out += ",\"default\":";
  if (is_bool) {
    out += (node->def_to_string() == "true") ? "true" : "false";
  } else {
    json_append_escaped(out, node->def_to_string());
  }

  if (node->get_type() == cfg::type::_enum) {
    out += ",\"variants\":[";
    bool first = true;
    for (const std::string &variant : node->to_list()) {
      if (!first) out += ',';
      first = false;
      json_append_escaped(out, variant);
    }
    out += ']';
  }

  if (const std::string min = node->min_to_string(); !min.empty()) {
    out += ",\"min\":";
    json_append_escaped(out, min);
    out += ",\"max\":";
    json_append_escaped(out, node->max_to_string());
  }

  out += '}';
}

extern "C" std::string _rpcsx_settingsGet(std::string_view path) {
  auto root = find_cfg_node(&g_cfg, path);

  if (root == nullptr) {
    return {};
  }

  std::string out;
  out.reserve(64 * 1024);
  emit_cfg_json(root, out);
  return out;
}

// Defined in Emu/RSX/Overlays/overlay_perf_metrics.cpp and overlay_debug_overlay.cpp;
// upstream declares them the same way in main_application.cpp.
namespace rsx::overlays {
extern void reset_performance_overlay();
extern void reset_debug_overlay();
} // namespace rsx::overlays

// Settings batching.
//
// Every settingsSet used to end in Emulator::SaveSettings(g_cfg.to_string(), ""), which
// serialises the WHOLE PS3 config to YAML and writes it to disk. The app pushes its full
// settings block on each change -- ~165 keys -- so one toggle in the in-game menu cost 165
// whole-config serialisations and 165 file writes, synchronously, on the UI thread. That is
// the reported "lag when switching settings in the in game menu".
//
// Between begin/end the save is deferred and coalesced into exactly one write. Single
// settingsSet calls (the dynamic settings screen) are unaffected and still save immediately.
static std::atomic<int> g_settings_batch_depth{0};
static std::atomic<bool> g_settings_batch_dirty{false};

extern "C" void _rpcsx_settingsBeginBatch() { g_settings_batch_depth.fetch_add(1); }

extern "C" void _rpcsx_settingsEndBatch() {
  // Nested/unbalanced calls must not drop the save; only the outermost end flushes.
  if (g_settings_batch_depth.fetch_sub(1) != 1) {
    return;
  }
  if (g_settings_batch_dirty.exchange(false)) {
    Emulator::SaveSettings(g_cfg.to_string(), "");
  }
}

extern "C" bool _rpcsx_settingsSet(std::string_view path,
                                   std::string_view valueString) {
  auto root = find_cfg_node(&g_cfg, path);

  if (root == nullptr) {
    rpcsx_android.error("settingsSet: node %s not found", path);
    return false;
  }

  // Values arrive JSON-encoded: bools/numbers bare, enums and strings quoted.
  // cfg::from_string wants the raw value, so unwrap one level of quoting and
  // undo the escapes we emit above.
  std::string decoded;
  if (valueString.size() >= 2 && valueString.front() == '"' &&
      valueString.back() == '"') {
    decoded.reserve(valueString.size() - 2);
    for (std::size_t i = 1; i + 1 < valueString.size(); ++i) {
      if (valueString[i] == '\\' && i + 2 < valueString.size()) {
        switch (valueString[++i]) {
        case 'n': decoded += '\n'; break;
        case 'r': decoded += '\r'; break;
        case 't': decoded += '\t'; break;
        case '"': decoded += '"'; break;
        case '\\': decoded += '\\'; break;
        default: decoded += valueString[i]; break;
        }
      } else {
        decoded += valueString[i];
      }
    }
  } else {
    decoded.assign(valueString);
  }

  if (!root->from_string(decoded, !Emu.IsStopped())) {
    rpcsx_android.error("settingsSet: node %s does not accept value '%s'", path,
                        decoded);
    return false;
  }

  if (g_settings_batch_depth.load() > 0) {
    g_settings_batch_dirty.store(true);
  } else {
    Emulator::SaveSettings(g_cfg.to_string(), "");
  }

  // The overlays own live objects built from the config; they are not re-read
  // per frame. reset_*_overlay() is what applies a change to the running one
  // (and creates it if it is newly enabled).
  //
  // Android only ever called these from rsx::thread::on_task(), i.e. once when
  // the RSX thread starts -- so changing an overlay setting mid-session did
  // nothing until the next boot. Desktop re-applies after every settings change
  // in main_application::OnEmuSettingsChange(), which lives in
  // rpcs3qt/CMakeLists.txt and is therefore not part of this build.
  //
  // Scoped to the overlay nodes on purpose: applyTo() pushes ~200 settings at
  // boot and re-applying every overlay property 200 times would be pure waste.
  //
  // POSTED, not called here. Both of these walk the overlay display manager and end up in
  // perf_metrics_overlay::update(), which touches RSX-owned state. This function runs on
  // whichever thread called through JNI, so doing it inline raced the RSX thread and the
  // teardown that Emu.Kill() performs. It aborted inside update() when a settings apply
  // landed while a game was closing or starting, which is the reported "changing any
  // setting in game crashes the app".
  //
  // CallFromMainThread is drained by the processor thread started in Rpcs3Bridge, so the
  // work is serialised against the rest of the emulator's main-thread queue instead.
  //
  // Skipped entirely when stopped: there is no overlay to reset, and posting would only
  // queue work against an emulator that is being torn down.
  if (Emu.IsStopped()) {
    return true;
  }

  if (path.starts_with("Video@@Performance Overlay")) {
    Emu.CallFromMainThread([]() { rsx::overlays::reset_performance_overlay(); });
  } else if (path.starts_with("Video@@Debug overlay") ||
             path.starts_with("Miscellaneous@@Debug overlay")) {
    Emu.CallFromMainThread([]() { rsx::overlays::reset_debug_overlay(); });
  }
  return true;
}

extern "C" std::string _rpcsx_getVersion() {
  return rpcs3::get_version().to_string();
}

// Custom Vulkan driver (adrenotools).
//
// SPLIT OF RESPONSIBILITY: the JNI glue (native-lib.cpp) is what calls
// adrenotools_open_libvulkan and hands us the resulting dlopen handle. That
// keeps adrenotools -- which is Adreno/Qualcomm specific -- out of the emulator
// core entirely. All we do is point the Vulkan dispatch table at it.
//
// That table only exists because upstream RPCS3 calls Vulkan through direct
// linked symbols; see rpcs3/Emu/RSX/VK/vk_android_loader.h.
//
// Returns the PREVIOUS handle so the caller can dlclose it. Passing nullptr
// reverts to the system driver.
extern "C" void *_rpcsx_setCustomDriver(void *driverHandle) {
  void *previous = vk::android::set_driver_handle(driverHandle);

  if (driverHandle != nullptr && !vk::android::using_custom_driver()) {
    rpcsx_android.error("setCustomDriver: driver rejected (%s)",
                        vk::android::last_error());
  }

  return previous;
}

#pragma GCC diagnostic pop
