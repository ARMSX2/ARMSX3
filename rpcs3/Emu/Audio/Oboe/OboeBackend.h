#pragma once

#include "util/atomic.hpp"
#include "Emu/Audio/AudioBackend.h"

#include <oboe/Oboe.h>

// Android audio through Oboe.
//
// cubeb already has an AAudio backend, so this is not about reaching AAudio -- it is about
// what Oboe wraps around it: a per-device quirks database, an automatic AAudio -> OpenSL ES
// fallback on the older parts where AAudio is unreliable, and error-callback-driven stream
// recovery when the device disconnects or the stream is torn down underneath us. Those are
// exactly the failure modes behind the glitching reported on low-end hardware.
class OboeBackend final : public AudioBackend, oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback
{
public:
	OboeBackend();
	~OboeBackend() override;

	OboeBackend(const OboeBackend&) = delete;
	OboeBackend& operator=(const OboeBackend&) = delete;

	std::string_view GetName() const override { return "Oboe"sv; }

	bool Operational() override;

	bool Open(std::string_view dev_id, AudioFreq freq, AudioSampleSize sample_size, AudioChannelCnt ch_cnt, audio_channel_layout layout) override;
	void Close() override;

	f64 GetCallbackFrameLen() override;

	void Play() override;
	void Pause() override;

private:
	// Oboe callbacks. onAudioReady runs on a real-time thread: no allocation, no blocking.
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audio_data, int32_t num_frames) override;
	void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result) override;

	std::shared_ptr<oboe::AudioStream> m_stream{};

	// Repeated on underrun rather than emitting silence, matching the Cubeb backend: a held
	// sample is far less audible than a gap.
	std::array<u8, sizeof(f32) * static_cast<u32>(AudioChannelCnt::SURROUND_7_1)> m_last_sample{};
	atomic_t<u8> m_full_sample_size = 0;

	// Set from the error callback when the stream dies (device disconnect, route change).
	// Operational() reports it and the caller reopens; the callback thread is already gone
	// by then, so nothing here can be torn down underneath a live callback.
	atomic_t<bool> m_reset_req = false;
};
