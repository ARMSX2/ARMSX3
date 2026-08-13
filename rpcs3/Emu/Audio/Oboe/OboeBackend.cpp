#include "stdafx.h"
#include "OboeBackend.h"

#include "Emu/System.h"
#include "Emu/system_config.h"

LOG_CHANNEL(Oboe, "Oboe");

OboeBackend::OboeBackend()
	: AudioBackend()
{
}

OboeBackend::~OboeBackend()
{
	Close();
}

bool OboeBackend::Operational()
{
	return m_stream && !m_reset_req.observe();
}

bool OboeBackend::Open(std::string_view /*dev_id*/, AudioFreq freq, AudioSampleSize sample_size, AudioChannelCnt ch_cnt, audio_channel_layout layout)
{
	Close();

	oboe::AudioStreamBuilder builder;

	builder.setDirection(oboe::Direction::Output)
		// LowLatency asks for the fast mixer path. Exclusive asks to bypass the mixer
		// entirely; Oboe silently downgrades to Shared where the device will not grant it,
		// so requesting it costs nothing on hardware that refuses.
		->setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Exclusive)
		// Game usage tells the platform not to apply the media post-processing chain, which
		// on some devices adds tens of milliseconds of latency we cannot see or control.
		->setUsage(oboe::Usage::Game)
		->setSampleRate(static_cast<s32>(freq))
		// The PS3 side owns resampling; converting again here would be a second, worse pass.
		->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
		->setChannelCount(static_cast<s32>(ch_cnt))
		->setFormat(sample_size == AudioSampleSize::FLOAT ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
		->setDataCallback(this)
		->setErrorCallback(this);

	if (const oboe::Result result = builder.openStream(m_stream); result != oboe::Result::OK)
	{
		Oboe.error("Failed to open stream: %s", oboe::convertToText(result));
		m_stream.reset();
		return false;
	}

	m_sampling_rate = freq;
	m_sample_size = sample_size;
	m_channels = m_stream->getChannelCount();

	setup_channel_layout(static_cast<u32>(ch_cnt), m_channels, layout, Oboe);

	m_full_sample_size = static_cast<u8>(m_channels * get_sample_size());

	// A buffer of two bursts is the standard low-latency compromise: one burst in flight and
	// one being filled. Anything smaller underruns on the first scheduling hiccup, and the
	// device caps this anyway.
	if (const s32 burst = m_stream->getFramesPerBurst(); burst > 0)
	{
		m_stream->setBufferSizeInFrames(burst * 2);
	}

	m_reset_req = false;

	Oboe.notice("Opened stream: %dHz, %d channels, %s, API %s, burst %d, buffer %d",
		m_stream->getSampleRate(), m_stream->getChannelCount(),
		m_stream->getFormat() == oboe::AudioFormat::Float ? "f32" : "s16",
		oboe::convertToText(m_stream->getAudioApi()),
		m_stream->getFramesPerBurst(), m_stream->getBufferSizeInFrames());

	return true;
}

void OboeBackend::Close()
{
	if (!m_stream)
	{
		return;
	}

	// stop() before close() so the callback thread is joined first: closing a running stream
	// can leave onAudioReady in flight against members we are about to reset.
	m_stream->stop();
	m_stream->close();
	m_stream.reset();

	std::lock_guard lock(m_cb_mutex);
	m_playing = false;
	m_last_sample.fill(0);
}

f64 OboeBackend::GetCallbackFrameLen()
{
	if (!m_stream)
	{
		return 0.0;
	}

	const s32 burst = m_stream->getFramesPerBurst();
	const s32 rate = m_stream->getSampleRate();

	if (burst <= 0 || rate <= 0)
	{
		return 0.0;
	}

	return static_cast<f64>(burst) / static_cast<f64>(rate);
}

void OboeBackend::Play()
{
	if (!m_stream)
	{
		return;
	}

	{
		std::lock_guard lock(m_cb_mutex);
		if (m_playing) return;
		m_playing = true;
	}

	if (const oboe::Result result = m_stream->requestStart(); result != oboe::Result::OK)
	{
		Oboe.error("requestStart failed: %s", oboe::convertToText(result));
	}
}

void OboeBackend::Pause()
{
	if (!m_stream)
	{
		return;
	}

	// requestPause is not implemented for every API/stream combination, and a failure here
	// must not leave the emulator believing audio is still running.
	if (const oboe::Result result = m_stream->requestPause(); result != oboe::Result::OK)
	{
		Oboe.error("requestPause failed: %s", oboe::convertToText(result));
	}

	std::lock_guard lock(m_cb_mutex);
	m_playing = false;
	m_last_sample.fill(0);
}

oboe::DataCallbackResult OboeBackend::onAudioReady(oboe::AudioStream* /*stream*/, void* audio_data, int32_t num_frames)
{
	if (num_frames <= 0 || !audio_data)
	{
		return oboe::DataCallbackResult::Continue;
	}

	const u32 sample_size = m_full_sample_size.observe();
	const u32 bytes_req = static_cast<u32>(num_frames) * sample_size;

	// try_lock with a short timeout, never a blocking lock: this is a real-time callback and
	// missing a deadline here is an audible glitch. Cubeb's callback does the same.
	std::unique_lock lock(m_cb_mutex, std::defer_lock);

	u32 written = 0;

	if (!m_reset_req.observe() && lock.try_lock_for(std::chrono::microseconds{50}) && m_write_callback && m_playing)
	{
		written = std::min(m_write_callback(bytes_req, audio_data), bytes_req);
		written -= written % sample_size;

		if (written >= sample_size)
		{
			std::memcpy(m_last_sample.data(), static_cast<u8*>(audio_data) + written - sample_size, sample_size);
		}
	}

	// Pad the remainder by repeating the last sample. Holding a value is much less audible
	// than a hole, and the emulator legitimately runs dry whenever it stalls.
	for (u32 i = written; i < bytes_req; i += sample_size)
	{
		std::memcpy(static_cast<u8*>(audio_data) + i, m_last_sample.data(), sample_size);
	}

	return oboe::DataCallbackResult::Continue;
}

void OboeBackend::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result result)
{
	// Oboe has already closed the stream and joined its callback thread by the time this
	// runs, so the only safe action is to flag it. Operational() reports false and the
	// caller reopens on its own thread. Disconnects (headphones, BT, route change) are the
	// common cause and are entirely normal on a handheld.
	Oboe.error("Stream closed after error: %s; requesting reset.", oboe::convertToText(result));
	m_reset_req = true;
}
