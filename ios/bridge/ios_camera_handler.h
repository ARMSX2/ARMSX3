// SPDX-License-Identifier: GPL-2.0-only
// The PS Eye over an injected camera_source, so the same handler runs on iOS and on the macOS leg.

#pragma once

#include "Emu/Io/camera_handler_base.h"

// camera_video_sink.h uses these without including them (upstream compiles it behind a precompiled header).
#include <array>
#include <functional>
#include <vector>

#include "Input/camera_video_sink.h"

#include <cstddef>
#include <cstdint>

namespace armsx3::bridge
{
	// `start` returns 0 running, 1 denied, 2 unavailable; an empty `start` is unavailable.
	struct camera_source
	{
		std::function<int(bool front, std::function<void(const std::uint8_t*, unsigned, unsigned, std::size_t)> on_frame)> start;
		std::function<void()> stop;
	};
}

class ios_camera_handler final : public camera_handler_base
{
public:
	explicit ios_camera_handler(armsx3::bridge::camera_source source);
	~ios_camera_handler() override;

	void open_camera() override;
	void close_camera() override;
	void start_camera() override;
	void stop_camera() override;

	// The second argument is the frame's whole byte size, whatever the base class names it.
	void set_format(s32 format, u32 bytesize) override;
	void set_frame_rate(u32 frame_rate) override;
	void set_resolution(u32 width, u32 height) override;
	void set_mirrored(bool mirrored) override;

	u64 frame_number() const override;
	camera_handler_state get_image(u8* buf, u64 size, u32& width, u32& height, u64& frame_number, u64& bytes_read) override;

private:
	void on_frame(const std::uint8_t* pixels, unsigned width, unsigned height, std::size_t pitch);
	void stop_source();

	armsx3::bridge::camera_source m_source;
	camera_video_sink m_sink{true};
	std::vector<u8> m_rgba;
	bool m_front = true;
	bool m_started = false;
};
