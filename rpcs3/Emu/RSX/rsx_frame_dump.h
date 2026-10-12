#pragma once

#include "util/types.hpp"

// Frame dumps: one frame's render targets saved as PNGs, for whoever is chasing a graphics bug.
//
// Asked for from the in-game menu ("Dump this frame") or, for scripted runs, with
// ARMSX3_DUMP_PASSES=<flip> in driver_env.txt (ARMSX3_DUMP_PRESENT_ONLY=1 for the final image only).
// The Vulkan renderer dumps the game's next whole frame, from one of its flips to the next, and
// writes config/captures/<title>_<date>_frame/, with frame.txt describing every image in it.
//
// Pairs with RSX captures (.rrc): replaying a capture and dumping a frame of it shows a bug pass by
// pass without the game.
namespace rsx::frame_dump
{
	enum class dump_mode : u32
	{
		none = 0,

		// The image the flip shows, and the screen buffer it was copied into when that can be read.
		// Costs one GPU wait.
		final_image = 1,

		// Every render target as the frame finishes with it, then the final image. Waits for the GPU
		// at every target switch, so the frame is much slower and races between passes can vanish.
		every_pass = 2,
	};

	// Arm a dump of the next frame. False when the active renderer cannot dump frames.
	bool request(dump_mode mode);

	// Renderer side: the pending request, cleared as it is taken.
	dump_mode take_request();
}
