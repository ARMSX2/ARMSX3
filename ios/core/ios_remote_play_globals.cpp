// SPDX-License-Identifier: GPL-2.0-only
// VKPresent.cpp reads Android's remote play counters and Cardboard VR flag; nothing sets them on iOS, so they stay off.
#include "util/types.hpp"
#include "util/atomic.hpp"

atomic_t<u32> g_remote_play_max_size{0};
atomic_t<u32> g_remote_play_frames{0};
atomic_t<u32> g_remote_play_skipped{0};
atomic_t<bool> g_remote_play_hardware{false};
atomic_t<u32> g_cardboard_vr{0};
