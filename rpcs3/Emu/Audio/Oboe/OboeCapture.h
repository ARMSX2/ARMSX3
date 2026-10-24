#pragma once

#include "util/types.hpp"

#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace oboe
{
	class AudioStream;
}

// ARMSX3: a microphone on Android, in place of the OpenAL capture device cellMic opens elsewhere.
//
// Android lists its input devices only to Java, so the app sends that list here (set_devices), and
// a device is named by what the user picked in Audio settings: "Default" lets Android choose, which
// is a plugged-in USB microphone when there is one and the built-in one otherwise. Reads never
// wait, like alcCaptureSamples: they return what arrived since the last one.
class oboe_capture
{
public:
	oboe_capture() = default;
	~oboe_capture();
	oboe_capture(const oboe_capture&) = delete;
	oboe_capture& operator=(const oboe_capture&) = delete;

	// 16-bit samples at that rate and channel count, converted by Oboe where the device differs
	bool open(const std::string& name, u32 sample_rate, u32 channels);
	void close();
	bool start();
	void stop();

	// Up to frames frames into dst, interleaved and host endian, without waiting
	u32 read(void* dst, u32 frames);

	// The app's list of input devices: the name settings store, and Android's id for it now
	static void set_devices(std::vector<std::pair<std::string, s32>> devices);

private:
	std::shared_ptr<oboe::AudioStream> m_stream;
	std::string m_name;
	bool m_read_failed = false;
};
