// SPDX-License-Identifier: GPL-2.0-only
// The flat C surface the Objective-C facade calls into the emulator through. It is pure C so Swift
// can import it, and the suite compiles it as C11. Every call returns 0 or a negative status, and
// every call but Rpcs3IOS_GetHaltStatus refuses the main thread with CALLED_ON_MAIN_THREAD.
#ifndef ARMSX3_IOS_BRIDGE_RPCS3_IOS_ABI_H
#define ARMSX3_IOS_BRIDGE_RPCS3_IOS_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Fixed width, because the caller is another language.
typedef int32_t Rpcs3IOS_Status;

enum
{
	RPCS3_IOS_STATUS_OK = 0,

	// Release builds only; a debug build aborts instead.
	RPCS3_IOS_STATUS_CALLED_ON_MAIN_THREAD = -1,

	RPCS3_IOS_STATUS_NULL_ARGUMENT = -2,

	// The path was refused before it reached the emulator.
	RPCS3_IOS_STATUS_PATH_REJECTED = -3,

	RPCS3_IOS_STATUS_NOT_INITIALIZED = -4,

	RPCS3_IOS_STATUS_CALLBACKS_NOT_INSTALLED = -5,

	// The reason is in the emulator log; the core's own code is not passed on, since a merge can renumber it.
	RPCS3_IOS_STATUS_BOOT_FAILED = -6,

	RPCS3_IOS_STATUS_INVALID_ARGUMENT = -7,

	// No pad thread, no pad in that port, or a pad set to another handler.
	RPCS3_IOS_STATUS_PAD_UNAVAILABLE = -8,

	// The emulator is not in the state the call starts from, or the core declined it.
	RPCS3_IOS_STATUS_WRONG_STATE = -9,

	// The launch-time JIT verdict is off, so the first guest instruction would end in a code-signing kill.
	RPCS3_IOS_STATUS_JIT_UNAVAILABLE = -10,

	// Not a PUP, a PUP that fails its hashes, or one missing the update package; nothing was written.
	RPCS3_IOS_STATUS_FIRMWARE_INVALID = -11,

	// The PUP was fine but dev_flash could not be written; the installed firmware is unchanged.
	RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED = -12,

	// The core gave the save up before stopping the game; the game still runs and nothing was written.
	RPCS3_IOS_STATUS_SAVESTATE_FAILED = -13,

	// Not a PKG, a split part passed alone, unreadable, or a split set with a part missing.
	RPCS3_IOS_STATUS_PKG_INVALID = -14,

	// An update whose base game is missing or at a version it does not apply to.
	RPCS3_IOS_STATUS_PKG_UPDATE_MISMATCH = -15,

	// A write failed, most often for want of space: a package, a licence, a removal or a recording.
	RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED = -16,

	// Not a RAP or EDAT this game accepts; nothing is left in exdata.
	RPCS3_IOS_STATUS_LICENCE_INVALID = -17,

	// The game was stopped but no slot was written; the slot's previous save is kept.
	RPCS3_IOS_STATUS_SAVESTATE_NOT_WRITTEN = -18,

	// The setting's own parser refused the value; nothing was written.
	RPCS3_IOS_STATUS_SETTING_REFUSED = -19,

	// The previous file is intact, because every write is a temporary file renamed over it.
	RPCS3_IOS_STATUS_SETTINGS_WRITE_FAILED = -20,

	// The iOS product file forces this setting at every boot, so a saved value would never apply.
	RPCS3_IOS_STATUS_SETTING_LOCKED = -21,

	// Too large, nested too deep, or not a YAML map; refused before any parse.
	RPCS3_IOS_STATUS_SETTINGS_UNREADABLE = -22,

	// No new frame arrived inside the bounded wait.
	RPCS3_IOS_STATUS_SCREENSHOT_NOT_TAKEN = -23,

	// Empty, not YAML, or refused by the core's patch parser; nothing was written.
	RPCS3_IOS_STATUS_PATCH_INVALID = -24,

	// The previous file is intact, because every write is a temporary file renamed over it.
	RPCS3_IOS_STATUS_PATCH_WRITE_FAILED = -25,

	// The core could not stop the SPUs for a save with Compatible Savestate Mode off; the game still runs.
	RPCS3_IOS_STATUS_SAVESTATE_SPU_BUSY = -26,

	// The renderer thread died holding locks a teardown would wait on forever; the process must end.
	RPCS3_IOS_STATUS_RENDERER_DIED = -27,

	// A JIT pool filled and its threads are parked, so a teardown would hang; the process must end.
	RPCS3_IOS_STATUS_JIT_EXHAUSTED = -28,

	// A thread died of a fatal error or the core froze; only Rpcs3IOS_GetHaltStatus reports it, so Stop still works.
	RPCS3_IOS_STATUS_GAME_FATAL = -29,

	// The core's stop watchdog fired and it still reads stopping; only Rpcs3IOS_GetHaltStatus reports it.
	RPCS3_IOS_STATUS_STOP_HUNG = -30
};

// Mapped from the core's system_state by name, never cast; the value sets here never overlap.
enum
{
	RPCS3_IOS_STATE_STOPPED = 100,
	RPCS3_IOS_STATE_LOADING = 101,
	RPCS3_IOS_STATE_STOPPING = 102,
	RPCS3_IOS_STATE_RUNNING = 103,
	RPCS3_IOS_STATE_PAUSED = 104,
	RPCS3_IOS_STATE_FROZEN = 105,
	RPCS3_IOS_STATE_READY = 106,
	RPCS3_IOS_STATE_STARTING = 107
};

enum
{
	RPCS3_IOS_JIT_ON = 200,
	RPCS3_IOS_JIT_OFF = 201
};

// The live settings, each with a closed value set; anything else is INVALID_ARGUMENT.
enum
{
	RPCS3_IOS_LIVE_VOLUME = 300, // 0-200 per cent
	RPCS3_IOS_LIVE_RESOLUTION_SCALE = 301, // 25-800 per cent, in steps of 25
	RPCS3_IOS_LIVE_FRAME_LIMIT = 302, // 0 auto, 1 display rate, 30, 50 or 60
	RPCS3_IOS_LIVE_PERF_OVERLAY = 303 // 0 off, 1 on
};

// Bit i of a mask is button i, in ARMSX2's order so its controller view maps one to one.
enum
{
	RPCS3_IOS_PAD_BUTTON_UP = 0,
	RPCS3_IOS_PAD_BUTTON_DOWN = 1,
	RPCS3_IOS_PAD_BUTTON_LEFT = 2,
	RPCS3_IOS_PAD_BUTTON_RIGHT = 3,
	RPCS3_IOS_PAD_BUTTON_CROSS = 4,
	RPCS3_IOS_PAD_BUTTON_CIRCLE = 5,
	RPCS3_IOS_PAD_BUTTON_SQUARE = 6,
	RPCS3_IOS_PAD_BUTTON_TRIANGLE = 7,
	RPCS3_IOS_PAD_BUTTON_L1 = 8,
	RPCS3_IOS_PAD_BUTTON_R1 = 9,
	RPCS3_IOS_PAD_BUTTON_L2 = 10,
	RPCS3_IOS_PAD_BUTTON_R2 = 11,
	RPCS3_IOS_PAD_BUTTON_START = 12,
	RPCS3_IOS_PAD_BUTTON_SELECT = 13,
	RPCS3_IOS_PAD_BUTTON_L3 = 14,
	RPCS3_IOS_PAD_BUTTON_R3 = 15,
	// The core opens its home menu on the PS button's rising edge.
	RPCS3_IOS_PAD_BUTTON_PS = 16
};

// Installs the callback table, picks the renderer and runs Emu.Init(); call it first, after set_host_services.
Rpcs3IOS_Status Rpcs3IOS_Initialize(void);

// Boots `content_path` with an optional per-run `config_path` (null or empty for none).
Rpcs3IOS_Status Rpcs3IOS_Boot(const char* content_path, const char* config_path);

Rpcs3IOS_Status Rpcs3IOS_GetState(int32_t* out_state);

// The RSX thread's guest vblank and flip counts, 0 before a boot; needs no Rpcs3IOS_Initialize.
Rpcs3IOS_Status Rpcs3IOS_GetFrameCounters(uint64_t* out_guest_vblanks, uint64_t* out_guest_flips);

// Writes RENDERER_DIED or JIT_EXHAUSTED when the game must end, else GAME_FATAL, STOP_HUNG or 0; safe on any thread.
Rpcs3IOS_Status Rpcs3IOS_GetHaltStatus(int32_t* out_status);

// Writes 0, or the JIT pool (2 or 3) whose second StikDebug pass this session offers; reading clears it.
Rpcs3IOS_Status Rpcs3IOS_TakeJitSecondPassOffer(int32_t* out_trigger);

// Starts that pass once StikDebug was asked to attach (`opened` 0 if iOS refused); WRONG_STATE when none can start.
Rpcs3IOS_Status Rpcs3IOS_StartJitSecondPass(int32_t trigger, int32_t opened);

Rpcs3IOS_Status Rpcs3IOS_Stop(void);

// Player 1's whole pad: a button mask (bit 16 is PS), sticks 0-255 centred on 128, and L2 and R2 pressure.
Rpcs3IOS_Status Rpcs3IOS_SetPadState(uint32_t buttons_mask, uint8_t lx, uint8_t ly, uint8_t rx, uint8_t ry, uint8_t l2, uint8_t r2);

// Rpcs3IOS_SetPadState for port `player`, 0-6; port 0 is player 1.
Rpcs3IOS_Status Rpcs3IOS_SetPlayerPadState(int32_t player, uint32_t buttons_mask, uint8_t lx, uint8_t ly, uint8_t rx, uint8_t ry, uint8_t l2, uint8_t r2);

// Plugs port `player` in or out; the choice is kept for later boots even when PAD_UNAVAILABLE comes back.
Rpcs3IOS_Status Rpcs3IOS_SetPlayerConnected(int32_t player, int32_t connected);

// A port's Sixaxis in the core's units, 0-1023 centred on 512; a DualShock 3 at rest reads 512, 399, 512, 512.
Rpcs3IOS_Status Rpcs3IOS_SetPlayerMotion(int32_t player, uint16_t x, uint16_t y, uint16_t z, uint16_t g);

// The colour, 0 to 1 per channel, the game holds for PS Move `gem` (0-3); PAD_UNAVAILABLE when it has none.
Rpcs3IOS_Status Rpcs3IOS_GetMoveColor(int32_t gem, float* out_r, float* out_g, float* out_b);

// The two motor speeds, 0-255, the game last wrote for a port; a motor not written for 3 s reads 0.
Rpcs3IOS_Status Rpcs3IOS_GetPlayerRumble(int32_t player, uint8_t* large, uint8_t* small);

// One hardware keyboard key as a USB HID usage, for the PS3's USB keyboard; PAD_UNAVAILABLE with no game.
Rpcs3IOS_Status Rpcs3IOS_KeyboardKey(int32_t hid_usage, int32_t pressed);

// Send it again while a game runs, because the game's cellKbInit marks the port connected.
Rpcs3IOS_Status Rpcs3IOS_SetKeyboardConnected(int32_t connected);

// A mouse button 1-8 for the PS3's USB mouse; PAD_UNAVAILABLE until the game has initialised its mouse.
Rpcs3IOS_Status Rpcs3IOS_MouseButton(int32_t button, int32_t pressed);

// A relative move, each axis within -127..128; statuses as Rpcs3IOS_MouseButton's.
Rpcs3IOS_Status Rpcs3IOS_MouseMove(int32_t dx, int32_t dy);

// `dx` is the tilt and `dy` the wheel, each within -128..127; statuses as Rpcs3IOS_MouseButton's.
Rpcs3IOS_Status Rpcs3IOS_MouseScroll(int32_t dx, int32_t dy);

// A disconnect releases every button and drops the moves the game has not read.
Rpcs3IOS_Status Rpcs3IOS_SetMouseConnected(int32_t connected);

// An absolute aim for mouse 0 inside bounds of 1-32767, for the light guns and uDraw; not gated on cellMouseInit.
Rpcs3IOS_Status Rpcs3IOS_MouseAim(int32_t x, int32_t y, int32_t x_max, int32_t y_max);

// A mouse 0 button for the aim devices, not gated on cellMouseInit because the gun initialises the handler.
Rpcs3IOS_Status Rpcs3IOS_MouseAimButton(int32_t button, int32_t pressed);

// While held, a pressed digital button reads the pad's pressure intensity instead of full; cleared at every boot.
Rpcs3IOS_Status Rpcs3IOS_SetPressureModifier(int32_t held);

// Pauses without the core's resume message, whose refresher presents frames while a backgrounded app may not.
Rpcs3IOS_Status Rpcs3IOS_Pause(void);

// Resumes any pause, so the caller resumes only one it took.
Rpcs3IOS_Status Rpcs3IOS_Resume(void);

// Writes RPCS3_IOS_JIT_ON or _OFF from the launch-time report, with an optional reason word; works before Initialize.
Rpcs3IOS_Status Rpcs3IOS_GetJitState(int32_t* out_state, char* out_reason, uint32_t reason_cap);

// The strings live only for the call; `boot_path` is the title's USRDIR/EBOOT.BIN.
typedef void (*Rpcs3IOS_GameVisitor)(void* context, const char* serial, const char* title, const char* boot_path);

// Visits each title under dev_hdd0/game in serial order; a missing folder is zero visits and OK.
Rpcs3IOS_Status Rpcs3IOS_ForEachGame(Rpcs3IOS_GameVisitor visit, void* context);

// The strings live only for the call and are never null; `source` is 0 for dev_hdd0/game, 1 for games.yml.
typedef struct
{
	const char* serial;
	const char* title;
	const char* category;
	const char* boot_path;
	const char* icon_path;
	const char* backdrop_path;
	int32_t is_iso;
	int32_t source;
} Rpcs3IOS_LibraryEntry;

typedef void (*Rpcs3IOS_LibraryVisitor)(void* context, const Rpcs3IOS_LibraryEntry* entry);

// Visits every bootable title from the core's own game scan, sorted by title then serial; it can take seconds.
Rpcs3IOS_Status Rpcs3IOS_ScanLibrary(Rpcs3IOS_LibraryVisitor visit, void* context);

// Adds the PS3 content at or directly under `folder_path` to games.yml; WRONG_STATE unless stopped.
Rpcs3IOS_Status Rpcs3IOS_RegisterGameFolder(const char* folder_path, uint32_t* out_games_added);

// Called once per installed package, `done` running from 1 to `total`.
typedef void (*Rpcs3IOS_FirmwareProgress)(void* context, uint32_t done, uint32_t total);

// Installs a PUP into dev_flash through a staging folder, so a failed install leaves the old firmware in place.
Rpcs3IOS_Status Rpcs3IOS_InstallFirmware(const char* pup_path, Rpcs3IOS_FirmwareProgress progress, void* context);

// The installed firmware version, such as 4.92, or an empty string.
Rpcs3IOS_Status Rpcs3IOS_GetFirmwareVersion(char* out, uint32_t cap);

// Saves into slot 0-9, 10 (autosave) or 11 (quick), rebooting from it when `resume` is 1; OK means the slot was written.
Rpcs3IOS_Status Rpcs3IOS_SaveStateToSlot(uint32_t file_slot, int32_t resume, char* out_history_name, uint32_t cap);

// The folder the core keeps `serial`'s save states in, with a trailing slash; the slots are in armsx3_slots/.
Rpcs3IOS_Status Rpcs3IOS_GetSaveStateDir(const char* serial, char* out, uint32_t cap);

// Boots the save state at `state_path`; from stopped it needs `game_boot_path`, and OK means the boot was accepted.
Rpcs3IOS_Status Rpcs3IOS_LoadState(const char* state_path, const char* game_boot_path, const char* config_path);

// Called when package `file_index` reaches a new `permille`, at most about ten times a second.
typedef void (*Rpcs3IOS_InstallProgress)(void* context, uint32_t file_index, uint32_t permille);

// Installs up to 16 packages in the order given (part 00 only for a split set); `out_title_id` gets the first title.
Rpcs3IOS_Status Rpcs3IOS_InstallPkg(const char* const* paths, uint32_t count, Rpcs3IOS_InstallProgress progress,
	void* context, char* out_title_id, uint32_t cap);

// Installs a RAP or EDAT into exdata; with `serial` it must unlock that game's EBOOT or it is taken out again.
Rpcs3IOS_Status Rpcs3IOS_InstallLicence(const char* path, const char* serial);

// Writes 0 when the game's EBOOT decrypts, 1 when a RAP would unlock it, 2 when no licence would help.
Rpcs3IOS_Status Rpcs3IOS_ProbeLicence(const char* serial, int32_t* out_locked, char* out_content_id, uint32_t cap);

// The current user's dev_hdd0/home/<user>/ folder, with a trailing slash.
Rpcs3IOS_Status Rpcs3IOS_GetUserDir(char* out, uint32_t cap);

// Removes dev_hdd0/game/<serial> only; PATH_REJECTED for a folder without PARAM.SFO or holding a link.
Rpcs3IOS_Status Rpcs3IOS_UninstallGame(const char* serial);

// Removes cache/<serial>/ so the next boot compiles again; OK when it was already gone.
Rpcs3IOS_Status Rpcs3IOS_ClearGameCache(const char* serial);

// The core's dev_hdd0/ folder, with a trailing slash.
Rpcs3IOS_Status Rpcs3IOS_GetHdd0Dir(char* out, uint32_t cap);

// Applies at once to a running game; a boot drops it and it is never written to a file.
Rpcs3IOS_Status Rpcs3IOS_SetLiveSetting(int32_t which, int32_t value);

// A frame limit outside the live set reads -1.
Rpcs3IOS_Status Rpcs3IOS_GetLiveSetting(int32_t which, int32_t* out_value);

// Called once with `size` bytes of UTF-8 that live only for the call.
typedef void (*Rpcs3IOS_TextSink)(void* context, const char* text, uint32_t size);

// The settings tree as JSON, one record per editable leaf with its global, database, game and forced values.
Rpcs3IOS_Status Rpcs3IOS_SettingsDump(const char* serial, const char* product_config, Rpcs3IOS_TextSink sink, void* context);

// Writes one leaf, its node names joined by @@, to config.yml or the game's file; `out_live` 1 if it applied at once.
Rpcs3IOS_Status Rpcs3IOS_SettingsSet(const char* serial, const char* path, const char* value, const char* product_config,
	int32_t* out_live);

// Resets leaves to the core's defaults, or removes them from a game's file; `count` 0 deletes that whole file.
Rpcs3IOS_Status Rpcs3IOS_SettingsReset(const char* serial, const char* const* paths, uint32_t count, const char* product_config,
	int32_t* out_live);

// One leaf of the running configuration, as the leaf prints it.
Rpcs3IOS_Status Rpcs3IOS_SettingsGetLive(const char* path, char* out, uint32_t cap);

// What Rpcs3IOS_SettingsNormalise kept and why it dropped the rest, for the import notice.
typedef struct Rpcs3IOS_SettingsCounts
{
	uint32_t kept;
	uint32_t unknown;
	uint32_t defaulted;
	uint32_t locked;
} Rpcs3IOS_SettingsCounts;

// Reduces a settings YAML to the keys that change something and hands it to `sink`; writes no file.
Rpcs3IOS_Status Rpcs3IOS_SettingsNormalise(const char* yaml, uint32_t size, const char* product_config,
	Rpcs3IOS_TextSink sink, void* context, Rpcs3IOS_SettingsCounts* out_counts);

// Merges a settings YAML into a game's file or config.yml, the file winning; `out_changed` counts the changes.
Rpcs3IOS_Status Rpcs3IOS_SettingsApply(const char* serial, const char* yaml, uint32_t size, const char* product_config,
	uint32_t* out_changed, int32_t* out_live);

// One scope's changed keys as a settings file, as Rpcs3IOS_SettingsNormalise would keep them.
Rpcs3IOS_Status Rpcs3IOS_SettingsExport(const char* serial, const char* product_config, Rpcs3IOS_TextSink sink, void* context);

// Four players' controller mappings as JSON, from the game's own file when it exists, else the global one.
Rpcs3IOS_Status Rpcs3IOS_InputConfigDump(const char* serial, Rpcs3IOS_TextSink sink, void* context);

// Sets one mapping key for player 0-3; a game's first write starts from a copy of the global file.
Rpcs3IOS_Status Rpcs3IOS_InputConfigSet(const char* serial, int32_t player, const char* key, const char* value);

// Puts player 0-3's mapping back to defaults; `player` -1 with a serial deletes the game's file.
Rpcs3IOS_Status Rpcs3IOS_InputConfigReset(const char* serial, int32_t player);

// Sets the device player 0-3 presents, an input::product_type ordinal 0-14; it takes effect at the next boot.
Rpcs3IOS_Status Rpcs3IOS_SetPlayerDevice(const char* serial, int32_t player, int32_t product_type);

// The device ordinal player 0-3 will present, 0 (DualShock 3) for an untouched file.
Rpcs3IOS_Status Rpcs3IOS_GetPlayerDevice(const char* serial, int32_t player, int32_t* product_type);

// Starts RPCS3.log in `dir`, capped at 512 MiB or a quarter of free space; call it before Rpcs3IOS_Initialize.
Rpcs3IOS_Status Rpcs3IOS_DiagStart(const char* dir, int32_t verbose);

// 1 raises the RSX channels to trace and keeps them there across boots; 0 returns them to notice.
Rpcs3IOS_Status Rpcs3IOS_DiagSetVerbose(int32_t verbose);

// Flushes the product log, first writing a stall dump when the game has shown no new frame for 2 s.
Rpcs3IOS_Status Rpcs3IOS_DiagSync(void);

// The patch files live under the core's config folder and are read at the next boot.

// The patch schema version this core parses, which a download from rpcs3.net asks for.
Rpcs3IOS_Status Rpcs3IOS_PatchEngineVersion(char* out, uint32_t cap);

// Replaces patch.yml, artemis_patch.yml or armsx3_patch.yml once the core has parsed the new text.
Rpcs3IOS_Status Rpcs3IOS_PatchesWrite(const char* file_name, const char* yaml);

// Merges a picked patch file into imported_patch.yml through the core's own import.
Rpcs3IOS_Status Rpcs3IOS_PatchesImport(const char* yaml);

// The patches for `serial` as a JSON array; patches for all games are left out.
Rpcs3IOS_Status Rpcs3IOS_PatchesList(const char* serial, Rpcs3IOS_TextSink sink, void* context);

// Switches a patch on or off for every app version of `serial`; it applies at the next boot.
Rpcs3IOS_Status Rpcs3IOS_PatchSetEnabled(const char* hash, const char* description, const char* serial,
	const char* app_version, int32_t enabled);

// The core's version line for the About screen, in the problem report's header format.
Rpcs3IOS_Status Rpcs3IOS_GetVersion(char* out, uint32_t cap);

// The core's config folder, with a trailing slash, for backup.
Rpcs3IOS_Status Rpcs3IOS_GetConfigDir(char* out, uint32_t cap);

// `rgba` is width * height * 4 bytes, tightly packed, and lives only for the call.
typedef void (*Rpcs3IOS_ImageSink)(void* context, const uint8_t* rgba, uint32_t width, uint32_t height);

// Hands the next presented frame to `sink`, waiting at most 2 s; SCREENSHOT_NOT_TAKEN when none came.
Rpcs3IOS_Status Rpcs3IOS_CaptureScreenshot(Rpcs3IOS_ImageSink sink, void* context);

// Writes 1 when the game takes an eject, 2 when it takes an insert, else 0.
Rpcs3IOS_Status Rpcs3IOS_DiscSwapState(int32_t* state);

// Swaps the disc, checking the new one first; `*result` 0 changed, 1 no swaps, 2 not a disc, 3 no eject, 4 refused.
Rpcs3IOS_Status Rpcs3IOS_ChangeDisc(const char* path, int32_t* result);

// The photos the game exported since the last call, as NUL-terminated host paths that may not be readable yet.
Rpcs3IOS_Status Rpcs3IOS_TakeExportedPhotos(Rpcs3IOS_TextSink sink, void* context);

// How many bytes the save in progress has written, and whether it is finalizing; WRONG_STATE when no save runs.
Rpcs3IOS_Status Rpcs3IOS_SaveStateProgress(uint64_t* bytes, int32_t* finalizing);

// Records the game's frames and audio into the .mp4 at `path`, sized by the core's recording config.
Rpcs3IOS_Status Rpcs3IOS_StartRecording(const char* path);

// Blocks until the file is finished; INSTALL_WRITE_FAILED when it was not completed.
Rpcs3IOS_Status Rpcs3IOS_StopRecording(void);

// Puts the figure file on a portal slot; the core keeps the file open and writes the game's changes back into it.
Rpcs3IOS_Status Rpcs3IOS_PortalLoad(int32_t portal, int32_t slot, const char* path);

Rpcs3IOS_Status Rpcs3IOS_PortalRemove(int32_t portal, int32_t slot);

// A portal's slots as JSON, each with the figure's id when it holds one.
Rpcs3IOS_Status Rpcs3IOS_PortalList(int32_t portal, Rpcs3IOS_TextSink sink, void* context);

// Writes a new blank figure file for a portal; an existing file is never overwritten.
Rpcs3IOS_Status Rpcs3IOS_PortalCreate(int32_t portal, int32_t figure_id, int32_t variant, const char* path);

// Puts the RPCN password and token in the core's memory slot, never in rpcn.yml; empty strings sign out.
Rpcs3IOS_Status Rpcs3IOS_RpcnSetSecret(const char* secret, const char* token);

// RPCN outcomes: `state` is how far a call got, `error` the server's answer, `raw` the core's own number.
typedef struct Rpcs3IOS_RpcnResult
{
	int32_t state;
	int32_t error;
	int32_t raw;
} Rpcs3IOS_RpcnResult;

enum
{
	RPCS3_IOS_RPCN_STATE_OK = 0,
	RPCS3_IOS_RPCN_STATE_INPUT = 1,
	RPCS3_IOS_RPCN_STATE_WOLFSSL = 2,
	RPCS3_IOS_RPCN_STATE_RESOLVE = 3,
	RPCS3_IOS_RPCN_STATE_BINDING = 4,
	RPCS3_IOS_RPCN_STATE_CONNECT = 5,
	RPCS3_IOS_RPCN_STATE_ID = 6,
	RPCS3_IOS_RPCN_STATE_ID_ALREADY_LOGGED_IN = 7,
	RPCS3_IOS_RPCN_STATE_ID_USERNAME = 8,
	RPCS3_IOS_RPCN_STATE_ID_PASSWORD = 9,
	RPCS3_IOS_RPCN_STATE_ID_TOKEN = 10,
	RPCS3_IOS_RPCN_STATE_PROTOCOL = 11,
	RPCS3_IOS_RPCN_STATE_OTHER = 12
};

enum
{
	RPCS3_IOS_RPCN_ERROR_NONE = 0,
	RPCS3_IOS_RPCN_ERROR_MALFORMED = 1,
	RPCS3_IOS_RPCN_ERROR_INVALID_INPUT = 2,
	RPCS3_IOS_RPCN_ERROR_TOO_SOON = 3,
	RPCS3_IOS_RPCN_ERROR_LOGIN_ERROR = 4,
	RPCS3_IOS_RPCN_ERROR_LOGIN_ALREADY_LOGGED_IN = 5,
	RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_USERNAME = 6,
	RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_PASSWORD = 7,
	RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_TOKEN = 8,
	RPCS3_IOS_RPCN_ERROR_CREATION_ERROR = 9,
	RPCS3_IOS_RPCN_ERROR_CREATION_EXISTING_USERNAME = 10,
	RPCS3_IOS_RPCN_ERROR_CREATION_BANNED_EMAIL_PROVIDER = 11,
	RPCS3_IOS_RPCN_ERROR_CREATION_EXISTING_EMAIL = 12,
	RPCS3_IOS_RPCN_ERROR_EMAIL_FAIL = 13,
	RPCS3_IOS_RPCN_ERROR_NOT_FOUND = 14,
	RPCS3_IOS_RPCN_ERROR_BLOCKED = 15,
	RPCS3_IOS_RPCN_ERROR_ALREADY_FRIEND = 16,
	RPCS3_IOS_RPCN_ERROR_OTHER = 99
};

// Signs in to RPCN, trying the derived password first; `out_secret` gets the form the server took.
Rpcs3IOS_Status Rpcs3IOS_RpcnSignIn(const char* npid, const char* password, const char* token, char* out_secret, uint32_t out_capacity, Rpcs3IOS_RpcnResult* result);

Rpcs3IOS_Status Rpcs3IOS_RpcnSignOut(void);

// The saved RPCN config as JSON; whether a password and token are held, never their values.
Rpcs3IOS_Status Rpcs3IOS_RpcnConfig(Rpcs3IOS_TextSink sink, void* context);

// RPCN's current state as JSON; it never creates a client and never waits on the network.
Rpcs3IOS_Status Rpcs3IOS_RpcnStatus(Rpcs3IOS_TextSink sink, void* context);

// Creates an RPCN account, which RPCN then activates by email; nothing is saved to rpcn.yml.
Rpcs3IOS_Status Rpcs3IOS_RpcnCreateAccount(const char* npid, const char* password, const char* email, Rpcs3IOS_RpcnResult* result);

// Emails the account's activation code again.
Rpcs3IOS_Status Rpcs3IOS_RpcnResendToken(const char* npid, const char* password, Rpcs3IOS_RpcnResult* result);

// Emails a password-reset code for `npid` to `email`.
Rpcs3IOS_Status Rpcs3IOS_RpcnSendResetToken(const char* npid, const char* email, Rpcs3IOS_RpcnResult* result);

// Sets a new password with the emailed reset `code`; LOGIN_INVALID_TOKEN in `result` for a wrong code.
Rpcs3IOS_Status Rpcs3IOS_RpcnResetPassword(const char* npid, const char* code, const char* password, Rpcs3IOS_RpcnResult* result);

// Opens or closes the counted friends session; pair every open with a close, whatever the open answered.
Rpcs3IOS_Status Rpcs3IOS_RpcnFriendsSession(int32_t open, Rpcs3IOS_RpcnResult* result);

// The friends list as JSON; the strings are remote input, so render them as plain text.
Rpcs3IOS_Status Rpcs3IOS_RpcnFriends(Rpcs3IOS_TextSink sink, void* context);

// Sends a friend request, or accepts one; WRONG_STATE when no client is signed in.
Rpcs3IOS_Status Rpcs3IOS_RpcnAddFriend(const char* npid, Rpcs3IOS_RpcnResult* result);

// Removes a friend or a request either way; statuses as Rpcs3IOS_RpcnAddFriend's.
Rpcs3IOS_Status Rpcs3IOS_RpcnRemoveFriend(const char* npid, Rpcs3IOS_RpcnResult* result);

// The running game's unused invites as JSON; the strings are remote input, so render them as plain text.
Rpcs3IOS_Status Rpcs3IOS_RpcnInvites(Rpcs3IOS_TextSink sink, void* context);

// Tells the running game an invite was selected, after checking it against the game's list again.
Rpcs3IOS_Status Rpcs3IOS_RpcnAcceptInvite(uint64_t invite_id);

Rpcs3IOS_Status Rpcs3IOS_RpcnDeclineInvite(uint64_t invite_id);

// Adds a saved server without choosing it; WRONG_STATE when that name and address are already listed.
Rpcs3IOS_Status Rpcs3IOS_RpcnAddHost(const char* name, const char* host);

// Removes a saved server; the official one is refused with INVALID_ARGUMENT.
Rpcs3IOS_Status Rpcs3IOS_RpcnDelHost(const char* name, const char* host);

// Puts the server list back to the official server alone and chooses it.
Rpcs3IOS_Status Rpcs3IOS_RpcnResetHosts(void);

// Chooses the saved server RPCN uses; a live client reconnects to it.
Rpcs3IOS_Status Rpcs3IOS_RpcnSelectHost(const char* host);

Rpcs3IOS_Status Rpcs3IOS_RpcnSetIpv6(int32_t on);

// Deletes every synced trophy, only through the friends session's client; ask the player first.
Rpcs3IOS_Status Rpcs3IOS_RpcnDeleteTrophies(Rpcs3IOS_RpcnResult* result);

// Every avatar the core knows, for the picker, as JSON in Android's order.
Rpcs3IOS_Status Rpcs3IOS_AvatarList(Rpcs3IOS_TextSink sink, void* context);

// The avatar games are given now, or the firmware default when none is chosen.
Rpcs3IOS_Status Rpcs3IOS_AvatarCurrent(Rpcs3IOS_TextSink sink, void* context);

// Chooses the avatar games are given; the path must lie under the home, PSN avatar or dev_flash folders.
Rpcs3IOS_Status Rpcs3IOS_AvatarSelect(const char* path);

// The picture inside an avatar file as PNG bytes; WRONG_STATE when the core finds none.
Rpcs3IOS_Status Rpcs3IOS_AvatarImage(const char* path, Rpcs3IOS_TextSink sink, void* context);

// Stores a square PNG of the player's own as their avatar and chooses it.
Rpcs3IOS_Status Rpcs3IOS_AvatarUseImage(const uint8_t* bytes, uint32_t size);

// WRONG_STATE when the firmware has no default avatar or the choice could not be cleared.
Rpcs3IOS_Status Rpcs3IOS_AvatarUseDefault(void);

// A game's SND0.AT3 as a 16-bit stereo WAV at `out_path`, with the core's FFmpeg; WRONG_STATE when it does not decode.
Rpcs3IOS_Status Rpcs3IOS_XmbDecodeAudio(const char* in_path, const char* out_path);

// An ICON1.PAM's own sound as a WAV; WRONG_STATE when it has none or it does not decode.
Rpcs3IOS_Status Rpcs3IOS_XmbDecodeIconAudio(const char* in_path, const char* out_path);

// Copies PS3_GAME's PIC1.PNG, SND0.AT3 and ICON1.PAM out of a disc image into `out_dir`, only when the image's
// TITLE_ID is `serial`; WRONG_STATE when it holds none of them or is another game's, INSTALL_WRITE_FAILED when it
// does not read or a file does not write (worth asking again).
Rpcs3IOS_Status Rpcs3IOS_XmbDiscMedia(const char* image_path, const char* serial, const char* out_dir);

// An open ICON1.PAM, played frame by frame.
typedef struct Rpcs3IOS_XmbIcon Rpcs3IOS_XmbIcon;

// WRONG_STATE when the file is not an animated icon this decoder plays.
Rpcs3IOS_Status Rpcs3IOS_XmbIconOpen(const char* path, Rpcs3IOS_XmbIcon** out_icon, uint32_t* width, uint32_t* height);

// The next frame as RGBA into `rgba` (width * height * 4 bytes) and its time in milliseconds; WRONG_STATE at the end.
Rpcs3IOS_Status Rpcs3IOS_XmbIconFrame(Rpcs3IOS_XmbIcon* icon, uint8_t* rgba, uint64_t capacity, int64_t* time_ms);

Rpcs3IOS_Status Rpcs3IOS_XmbIconClose(Rpcs3IOS_XmbIcon* icon);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* ARMSX3_IOS_BRIDGE_RPCS3_IOS_ABI_H */
