// SPDX-License-Identifier: GPL-2.0-only
// The recording sink: the drain thread is the writer's only caller, and the trim thread keeps the queue bounded while it waits.

#include "ios_video_sink.h"

extern "C"
{
#include <libavutil/pixfmt.h>
}

#include <chrono>
#include <deque>
#include <utility>

namespace
{
	constexpr auto k_drain_period = std::chrono::milliseconds(5);
}

ios_video_sink::ios_video_sink(std::shared_ptr<armsx3::bridge::video_writer> writer, unsigned fps, unsigned sample_rate)
	: m_writer(std::move(writer))
{
	m_framerate = fps;
	m_sample_rate = sample_rate;
	use_internal_audio = true;
	use_internal_video = true;
	m_drain_thread = std::thread([this] { drain_loop(); });
	m_trim_thread = std::thread([this]
	{
		while (!m_quit)
		{
			trim_frames();
			std::this_thread::sleep_for(k_drain_period);
		}
	});
}

ios_video_sink::~ios_video_sink()
{
	stop(false);
	finish_and_join();
}

void ios_video_sink::stop(bool flush)
{
	std::lock_guard lock(m_stop_mtx);
	if (m_stopped)
	{
		return;
	}
	m_stopped = true;

	m_flush = true; // the base class refuses new frames and samples from here on
	m_drain_on_quit = flush;
	m_quit = true;
}

void ios_video_sink::finish_and_join()
{
	stop(true);
	std::lock_guard lock(m_join_mtx);
	if (m_trim_thread.joinable())
	{
		m_trim_thread.join();
	}
	if (m_drain_thread.joinable())
	{
		m_drain_thread.join();
	}
}

void ios_video_sink::pause(bool)
{
	m_paused = true;
}

void ios_video_sink::resume()
{
	m_paused = false;
}

u64 ios_video_sink::dropped_frames() const
{
	return m_dropped.load();
}

void ios_video_sink::trim_frames()
{
	std::lock_guard lock(m_video_mtx);
	while (m_frames_to_encode.size() > k_max_queued_frames)
	{
		m_frames_to_encode.pop_front();
		++m_dropped;
	}
}

void ios_video_sink::drain_loop()
{
	std::vector<float> stereo;

	const auto drain = [&]
	{
		// One frame at a time, so at most one is ever outside the bounded queue and a slow writer cannot starve the audio.
		trim_frames();

		std::deque<encoder_sample> samples;
		{
			std::lock_guard lock(m_audio_mtx);
			samples.swap(m_samples_to_encode);
		}

		for (usz taken = 0; taken < k_max_queued_frames; ++taken)
		{
			encoder_frame frame;
			{
				std::lock_guard lock(m_video_mtx);
				if (m_frames_to_encode.empty())
				{
					break;
				}
				frame = std::move(m_frames_to_encode.front());
				m_frames_to_encode.pop_front();
			}
			m_writer->append_video(frame.data.data(), frame.pitch, frame.width, frame.height,
				frame.av_pixel_format == AV_PIX_FMT_BGRA, static_cast<std::int64_t>(frame.timestamp_ms));
		}

		// Stereo: the front left/right pair of a multichannel block, a mono block duplicated.
		for (const encoder_sample& sample : samples)
		{
			const auto* in = reinterpret_cast<const float*>(sample.data.data());
			const usz right = sample.channels > 1 ? 1 : 0;
			stereo.resize(usz{sample.sample_count} * 2);
			for (usz i = 0; i < sample.sample_count; ++i)
			{
				stereo[i * 2] = in[i * sample.channels];
				stereo[i * 2 + 1] = in[i * sample.channels + right];
			}
			m_writer->append_audio(stereo.data(), sample.sample_count, 2, static_cast<std::int64_t>(sample.timestamp_us));
		}
	};

	// A dead writer sets has_error, so the provider stops the recording at the next frame or sample block.
	const auto check_writer = [&]
	{
		if (!m_writer_failed && m_writer->failed && m_writer->failed())
		{
			m_writer_failed = true;
			has_error = true;
		}
	};

	while (!m_quit)
	{
		drain();
		check_writer();
		std::this_thread::sleep_for(k_drain_period);
	}

	if (m_drain_on_quit)
	{
		drain();
	}
	check_writer();

	const bool finished = m_writer->finish();
	m_finished_ok = finished && !m_writer_failed;
}

bool ios_video_sink::finished_ok() const
{
	return m_finished_ok.load();
}
