#pragma once

#include <atomic>
#include <string_view>

// ARMSX3: the Android app is out of sight (its activity is stopped: home, recents, another app,
// or the screen locked). Set through _armsx3_setAppBackgrounded. Threads that keep polling while a
// game is paused read it to poll less, since nobody can resume a game they cannot see (#196).
inline std::atomic<bool> g_android_app_backgrounded{false};

// ARMSX3: PPU code compiling while a game runs, for the app's "Compiling PPU modules" banner.
// ppu_initialize keeps both: compiling counts compile workloads in flight during play, done counts
// the modules that finished. _armsx3_getPlayCompileState turns them into a banner that shows only
// once compiling has gone on for ten seconds without a two-second break, the kind that holds the
// game up (Castlevania: Harmony of Despair compiles 61 DLC modules on one loading screen), never
// the short compiles games do in passing. Loading modules that are already compiled is not
// compiling and does not count.
inline std::atomic<int> g_android_play_compiling{0};
inline std::atomic<unsigned> g_android_play_compile_done{0};

// ARMSX3: called by sys_prx with the guest path of every module a game loads. armsx3-core.cpp sets
// it to count the modules the compile banner is watching, so the banner can say "N of M".
inline void (*g_android_on_prx_loaded)(std::string_view vpath) = nullptr;
