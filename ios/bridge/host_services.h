// SPDX-License-Identifier: GPL-2.0-only
// The callbacks the macOS leg and the iOS app answer differently, injected into the one shared table as a plain struct.
#pragma once

// atomic_t cannot be forward-declared: a class template's default argument may be given by only one declaration.
#include "util/atomic.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>

#include "video_writer.h"

class AudioBackend;
class GSFrameBase;
class camera_handler_base;

namespace utils
{
	struct serial;
}

namespace armsx3::bridge
{
	// Each member's type is the emu_callbacks field's own, so make_callbacks() assigns rather than wraps.
	struct host_services
	{
		// A non-null `wake_up` means the caller blocks on the result; null means post and return.
		std::function<void(std::function<void()>, atomic_t<std::uint32_t>*)> call_from_main_thread;

		std::function<std::unique_ptr<GSFrameBase>()> get_gs_frame;

		std::function<void(utils::serial*)> init_gs_render;

		std::function<std::shared_ptr<AudioBackend>()> get_audio;

		// Optional, like every member below: empty means the host has no display-sleep control, never a call to an empty function.
		std::function<void(bool)> enable_display_sleep;

		// microphone_denied only reads the record permission; set_recording switches the audio session's category.
		std::function<bool()> microphone_denied;
		std::function<void(bool)> set_recording;

		// Called for `Camera: Qt`, the core's real-camera value.
		std::function<std::shared_ptr<camera_handler_base>()> get_camera_handler;

		// Called on RSX overlay threads, so it must return at once.
		std::function<void(const std::string&, std::optional<float>)> play_sound;

		// Called once per recording on the bridge queue; null when the file cannot begin.
		std::function<std::shared_ptr<armsx3::bridge::video_writer>(const armsx3::bridge::video_writer_settings&)> make_video_writer;

		// A host member because answering needs the Vulkan renderer types, which armsx3_bridge does not link.
		std::function<bool()> rsx_thread_died;
	};

	// Process-wide because a pure-C entry point cannot take a host_services, and Emu is a singleton anyway.
	void set_host_services(host_services services);

	// A default value's members are empty, so calling one before set_host_services is a caller error.
	const host_services& current_host_services();
}
