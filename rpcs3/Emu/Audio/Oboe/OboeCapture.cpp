#include "stdafx.h"
#include "OboeCapture.h"

#include "Utilities/mutex.h"

#include <oboe/Oboe.h>

#include <shared_mutex>

LOG_CHANNEL(mic_log, "Microphone");

namespace
{
	shared_mutex s_devices_mutex;
	std::vector<std::pair<std::string, s32>> s_devices;

	// Android's id for a device the app listed, or unspecified (Android chooses) when it is not plugged in
	s32 device_id(const std::string& name)
	{
		if (name.empty() || name == "Default")
		{
			return oboe::kUnspecified;
		}

		std::shared_lock lock(s_devices_mutex);

		for (const auto& [listed, id] : s_devices)
		{
			if (listed == name)
			{
				return id;
			}
		}

		mic_log.warning("Microphone '%s' is not connected: using the one Android picks", name);
		return oboe::kUnspecified;
	}
}

oboe_capture::~oboe_capture()
{
	close();
}

void oboe_capture::set_devices(std::vector<std::pair<std::string, s32>> devices)
{
	std::lock_guard lock(s_devices_mutex);
	s_devices = std::move(devices);
}

bool oboe_capture::open(const std::string& name, u32 sample_rate, u32 channels)
{
	close();

	const s32 id = device_id(name);

	// The voice performance preset first: it is the one for singing into, with no gain control or noise
	// suppression to fight the pitch detection, and it needs Android 10. Generic is the fallback.
	for (const oboe::InputPreset preset : {oboe::InputPreset::VoicePerformance, oboe::InputPreset::Generic})
	{
		oboe::AudioStreamBuilder builder;
		builder.setDirection(oboe::Direction::Input)
			->setDeviceId(id)
			->setSharingMode(oboe::SharingMode::Shared)
			->setPerformanceMode(oboe::PerformanceMode::None)
			->setInputPreset(preset)
			->setFormat(oboe::AudioFormat::I16)
			->setChannelCount(static_cast<s32>(channels))
			->setSampleRate(static_cast<s32>(sample_rate))
			// 200 ms: the microphone thread reads about every 5 ms, but not while the game holds it up
			->setBufferCapacityInFrames(static_cast<s32>(sample_rate / 5))
			->setFormatConversionAllowed(true)
			->setChannelConversionAllowed(true)
			->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);

		if (const oboe::Result result = builder.openStream(m_stream); result != oboe::Result::OK)
		{
			mic_log.warning("Could not open microphone '%s' (%u Hz, %u channel(s), preset %d): %s", name, sample_rate, channels, static_cast<s32>(preset), oboe::convertToText(result));
			m_stream.reset();
			continue;
		}

		m_name = name;
		m_read_failed = false;
		mic_log.notice("Opened microphone '%s' (Android device %d): %d Hz, %d channel(s), preset %d", name, m_stream->getDeviceId(),
			m_stream->getSampleRate(), m_stream->getChannelCount(), static_cast<s32>(preset));
		return true;
	}

	return false;
}

void oboe_capture::close()
{
	if (m_stream)
	{
		m_stream->close();
		m_stream.reset();
	}
}

bool oboe_capture::start()
{
	if (!m_stream)
	{
		return false;
	}

	if (const oboe::Result result = m_stream->requestStart(); result != oboe::Result::OK)
	{
		// Most often the microphone permission: Android refuses the stream rather than the open
		mic_log.error("Could not start microphone '%s': %s", m_name, oboe::convertToText(result));
		return false;
	}

	return true;
}

void oboe_capture::stop()
{
	if (m_stream)
	{
		m_stream->requestStop();
	}
}

u32 oboe_capture::read(void* dst, u32 frames)
{
	if (!m_stream || !frames)
	{
		return 0;
	}

	const auto result = m_stream->read(dst, static_cast<s32>(frames), 0);

	if (!result)
	{
		// Once, not every 5 ms: an unplugged microphone stays unplugged
		if (!m_read_failed)
		{
			mic_log.error("Microphone '%s' stopped delivering: %s", m_name, oboe::convertToText(result.error()));
			m_read_failed = true;
		}

		return 0;
	}

	return static_cast<u32>(result.value());
}
