// SPDX-License-Identifier: GPL-2.0-only
// The PS Eye handler; its state machine follows the core's SDL camera handler, and the core's sink does the format conversion.

#include "ios_camera_handler.h"
#include "ios_camera_convert.h"

#include "Emu/system_config.h"

#include <utility>

ios_camera_handler::ios_camera_handler(armsx3::bridge::camera_source source)
	: m_source(std::move(source))
{
}

ios_camera_handler::~ios_camera_handler()
{
	stop_source();
}

void ios_camera_handler::stop_source()
{
	if (m_started && m_source.stop)
	{
		m_source.stop();
	}
	m_started = false;
}

void ios_camera_handler::open_camera()
{
	set_state(camera_handler_state::open);
}

void ios_camera_handler::close_camera()
{
	stop_source();
	set_state(camera_handler_state::closed);
}

void ios_camera_handler::start_camera()
{
	stop_source();

	// The front camera unless the player chose `Back`.
	m_front = g_cfg.io.camera_id.to_string() != "Back";

	// The source may deliver its first frame before `start` returns, so m_mutex is not held here.
	const int started = m_source.start
		? m_source.start(m_front, [this](const std::uint8_t* pixels, unsigned width, unsigned height, std::size_t pitch)
			{
				on_frame(pixels, width, height, pitch);
			})
		: 2;

	if (started != 0)
	{
		set_state(camera_handler_state::closed);
		return;
	}

	m_started = true;
	set_state(camera_handler_state::running);
}

void ios_camera_handler::stop_camera()
{
	stop_source();
	set_state(camera_handler_state::open);
}

void ios_camera_handler::on_frame(const std::uint8_t* pixels, unsigned width, unsigned height, std::size_t pitch)
{
	// A frame that cannot be read whole is dropped.
	if (!pixels || !width || !height || pitch < static_cast<std::size_t>(width) * 4)
	{
		return;
	}

	std::lock_guard lock(m_mutex);
	if (!m_width || !m_height)
	{
		return;
	}

	// As the SDL sink: a front camera is mirrored already, and the flip setting and the game's mirror flag each toggle that.
	const camera_flip flip = g_cfg.io.camera_flip_option;
	const bool mirror = m_front ^ (flip == camera_flip::horizontal || flip == camera_flip::both) ^ m_mirrored;

	const u32 pitch_out = m_width * 4;
	m_rgba.resize(static_cast<std::size_t>(pitch_out) * m_height);
	armsx3::camera_convert::bgra_to_rgba(pixels, width, height, pitch, m_rgba.data(), m_width, m_height, mirror);

	m_sink.present(m_width, m_height, pitch_out, 4, [this, pitch_out](u32 y) -> const u8*
	{
		return m_rgba.data() + static_cast<std::size_t>(y) * pitch_out;
	});
}

void ios_camera_handler::set_format(s32 format, u32 bytesize)
{
	std::lock_guard lock(m_mutex);
	m_format = format;
	m_bytesize = bytesize;
	m_sink.set_format(format, bytesize);
}

void ios_camera_handler::set_frame_rate(u32 frame_rate)
{
	std::lock_guard lock(m_mutex);
	m_frame_rate = frame_rate;
}

void ios_camera_handler::set_resolution(u32 width, u32 height)
{
	std::lock_guard lock(m_mutex);
	m_width = width;
	m_height = height;
	m_sink.set_resolution(width, height);
}

void ios_camera_handler::set_mirrored(bool mirrored)
{
	std::lock_guard lock(m_mutex);
	m_mirrored = mirrored;
	m_sink.set_mirrored(mirrored);
}

u64 ios_camera_handler::frame_number() const
{
	return m_sink.frame_number();
}

camera_handler_base::camera_handler_state ios_camera_handler::get_image(u8* buf, u64 size, u32& width, u32& height, u64& frame_number, u64& bytes_read)
{
	width = 0;
	height = 0;
	frame_number = 0;
	bytes_read = 0;

	const camera_handler_state state = get_state();
	if (state == camera_handler_state::running)
	{
		m_sink.get_image(buf, size, width, height, frame_number, bytes_read);
	}
	return state;
}
