// SPDX-License-Identifier: GPL-2.0-only
// No Apple header, so ios_render.cpp can name the factory without seeing AVFoundation.

#pragma once

#include "video_writer.h"

#include <memory>

namespace armsx3::bridge
{
	// Null when the file cannot be set up; the returned functions are called from the sink's drain thread only.
	std::shared_ptr<video_writer> make_avfoundation_video_writer(const video_writer_settings& settings);
}
