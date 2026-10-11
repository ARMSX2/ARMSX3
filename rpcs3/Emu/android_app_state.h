#pragma once

#include <atomic>

// ARMSX3: the Android app is out of sight (its activity is stopped: home, recents, another app,
// or the screen locked). Set through _rpcsx_setAppBackgrounded. Threads that keep polling while a
// game is paused read it to poll less, since nobody can resume a game they cannot see (#196).
inline std::atomic<bool> g_android_app_backgrounded{false};
