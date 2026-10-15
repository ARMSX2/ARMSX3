#pragma once

// ARMSX3 remote play: the frames the Vulkan presenter captures for the stream (VKPresent.cpp), passed
// to the app's video thread (android/src/remote_play.cpp) without the RSX thread touching the pixels.
//
// The presenter draws each frame on the GPU into a slot and has the GPU write the slot's sequence
// number to markers[i] once it is in. The app's thread sees the number and takes the frame. Each
// frame is one of two kinds, chosen by the presenter as it records:
//  - hardware: the slot's AHardwareBuffer, filled with RGBA. The app hands it to the video encoder as
//    a GL texture and gives the slot back when the encoder has drawn it, so the frame never passes
//    through the CPU.
//  - NV12: the slot's mapped buffer, holding the frame as the encoder's YUV, which the app copies out.
//    Used where the device cannot share buffers, or the app could not import one.
// The RSX thread only ever moves a slot from free to recorded; the app's side moves it on and back,
// so neither waits on the other.

#include "util/types.hpp"
#include "util/atomic.hpp"

#include <array>
#include <atomic>
#include <mutex>

struct AHardwareBuffer;

namespace remote_capture
{
	enum slot_state : u32
	{
		slot_free = 0,     // the presenter may record into it
		slot_recorded = 1, // the GPU is filling it, or has: markers[i] == seq says which
		slot_held = 2,     // hardware frame: the app has it until it gives the slot back
	};

	struct slot
	{
		std::atomic<u32> state{slot_free};
		u32 seq = 0;
		u32 width = 0;
		u32 height = 0;

		// When the presenter recorded the frame (steady clock, ns), for how long the GPU usually takes.
		u64 recorded_ns = 0;

		// NV12 frames: mapped for as long as the slot exists. The Y plane (width x height), then the
		// interleaved UV plane (width x height / 2), rows packed.
		const u8* data = nullptr;

		// Hardware frames: the buffer, and a number that changes whenever it is replaced, so the app
		// knows to import it again (from g_generation, so it never repeats within the process).
		// hardware says which kind the recorded frame is. The buffer pointer only changes under
		// g_mutex, on a free slot.
		AHardwareBuffer* hardware_buffer = nullptr;
		u32 generation = 0;
		bool hardware = false;
	};

	struct ring
	{
		std::array<slot, 4> slots;
		const volatile u32* markers = nullptr; // one per slot, written by the GPU
	};

	// The ring in use, or null. The presenter publishes and withdraws it under the mutex, and the app's
	// side holds the mutex while it reads a slot, so a ring is never torn down mid-read.
	inline std::mutex g_mutex;
	inline ring* g_ring = nullptr;

	// Counts frames the presenter has recorded; it notifies after each. The app's thread sleeps on it
	// until there is a frame to wait for, instead of checking every millisecond in between.
	inline atomic_t<u32> g_recorded{0};

	// Hardware buffer generations, process-wide, so a renderer that comes back never repeats one the
	// app has already imported.
	inline atomic_t<u32> g_generation{0};
}
