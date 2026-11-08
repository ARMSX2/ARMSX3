// SPDX-License-Identifier: GPL-2.0-only
// Renderer selection and host bring-up; plain C++, because with CAMetalLayer visible the Vulkan headers stop compiling.
#pragma once

// A relative path, so it resolves the same under CMake and under the Xcode target's include roots.
#include "../bridge/host_services.h"
#include "util/types.hpp"

#include <memory>
#include <string>
#include <vector>

// Forward-declared, as host_services.h does, since it appears only as a pointer.
namespace utils
{
	struct serial;
}

class AudioBackend;

namespace armsx3::ios_render
{
	// The init_gs_render body: the setting picks the backend, OpenGL resolves to Vulkan, and anything else is Null.
	void init_gs_render(utils::serial* ar);

	// Fills get_gs_frame, init_gs_render and get_audio here, where GSFrameBase is a complete type the Objective-C++ side cannot see.
	void install_services(armsx3::bridge::host_services& services);

	// The backend g_cfg.audio.renderer names; the config must also name a channel layout, or rsxaudio throws.
	std::shared_ptr<AudioBackend> make_audio();

	// Mirrors the guest's vblank counter, which ios_frame.mm cannot read; returns it, or 0 with no RSX thread yet.
	u64 poll_guest_vblanks();

	// A background ticker that polls guest vblanks and arms the capture, since the arming conditions are not met inside a flip.
	void install_capture_arming_ticker(unsigned interval_ms);

	// Stops and joins the ticker, so no tick touches RSX after Rpcs3IOS_Stop; safe to call twice.
	void stop_capture_arming_ticker();

	// False means only flips evaluate the arming conditions.
	bool capture_arming_ticker_installed();

	// Ticks so far; zero means nothing evaluated the arming conditions, which capture_failed cannot report.
	unsigned long long capture_arming_ticks();

	// CMAKE_CXX_COMPILER_ID, a CMake compile definition the Xcode-built capture driver cannot see.
	const char* build_compiler_id();

	// Keeps the emulator's error-level messages in a fixed ring for the log; the emit path allocates nothing and never blocks.
	void install_log_ring_listener();

	// False means every counter below is absent, not zero.
	bool log_ring_installed();

	// The ring's level filter as a string, for the log reader.
	const char* log_ring_min_level();

	// How many messages passed the level filter in total, over the whole run.
	unsigned long long log_ring_seen();

	// How many are still held. Bounded by the ring's capacity by construction.
	unsigned long long log_ring_retained();

	// Messages overwritten, so a full ring never passes for a complete one.
	unsigned long long log_ring_dropped();

	// Messages stored short of their full text.
	unsigned long long log_ring_truncated();

	// A copy of the retained text, oldest first; the only part that may allocate.
	void log_ring_snapshot(std::vector<std::string>& out);

	// Named channels only: RSX at trace would flood the console relay.
	void raise_log_channels();

	// Log listeners, raised channels, host services and working directory, once per process; 0 or the chdir errno. Run before Rpcs3IOS_Initialize.
	int prepare_host();

	// Writes armsx3-product.yml, the boot override, on every call: Vulkan, Cubeb with a Stereo layout, LLVM when the core has it.
	std::string write_product_config();
}
