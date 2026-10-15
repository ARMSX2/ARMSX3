#pragma once

// ARMSX3 remote play: the frames the Vulkan presenter captures for the stream (VKPresent.cpp), passed
// to the app's video thread (android/src/remote_play.cpp) without the RSX thread touching the pixels.
//
// The presenter draws each frame's YUV planes on the GPU into a slot's mapped buffer and has the GPU
// write the slot's sequence number to markers[i] once they are in. The app's thread sees the number,
// copies the frame out and frees the slot. The RSX thread only ever moves a slot from free to
// recorded; the app's thread moves it back, so neither waits on the other.

#include "util/types.hpp"

#include <array>
#include <atomic>
#include <mutex>

namespace remote_capture
{
	enum slot_state : u32
	{
		slot_free = 0,     // the presenter may record into it
		slot_recorded = 1, // the GPU is filling it, or has: markers[i] == seq says which
	};

	struct slot
	{
		std::atomic<u32> state{slot_free};
		u32 seq = 0;
		u32 width = 0;
		u32 height = 0;

		// NV12, mapped for as long as the slot exists: the Y plane (width x height), then the
		// interleaved UV plane (width x height / 2), rows packed.
		const u8* data = nullptr;
	};

	struct ring
	{
		std::array<slot, 4> slots;
		const volatile u32* markers = nullptr; // one per slot, written by the GPU
	};

	// The ring in use, or null. The presenter publishes and withdraws it under the mutex, and the app's
	// thread holds the mutex while it copies a frame out, so a ring is never torn down mid-copy.
	inline std::mutex g_mutex;
	inline ring* g_ring = nullptr;
}
