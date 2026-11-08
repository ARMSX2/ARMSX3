// SPDX-License-Identifier: GPL-2.0-only
// The writer the recording sink calls from its drain thread; AVAssetWriter on iOS, a counting one on the macOS leg.

#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace armsx3::bridge
{
	// The config's FFmpeg codec ids are ignored: the writer chooses Apple's codec.
	struct video_writer_settings
	{
		std::string path;
		unsigned width = 0;
		unsigned height = 0;
		unsigned fps = 0;
		unsigned video_bps = 0;
		unsigned sample_rate = 0;
		unsigned audio_bps = 0;
	};

	// A call should drop rather than wait, because a frame being written is held until it returns.
	struct video_writer
	{
		// Timestamped in ms from the recording's start.
		std::function<bool(const std::uint8_t* pixels, unsigned pitch, unsigned width, unsigned height, bool bgra,
			std::int64_t pts_ms)> append_video;
		// Timestamped in us; the sink always hands 2 channels.
		std::function<bool(const float* interleaved, unsigned frames, unsigned channels, std::int64_t ts_us)> append_audio;
		// Called once per recording.
		std::function<bool()> finish;
		// Optional: empty means the writer never fails.
		std::function<bool()> failed;
	};
}
