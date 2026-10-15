// Remote play, core side: hands the running game's picture and sound to the app, which streams
// them to a friend's device (the friend plays as player 2).
//
// Nothing here talks to the network. RPCS3's recording hook (utils::video_provider) already gives
// every presented frame and every mixed audio block to a video_sink; this sink keeps the newest
// frame and the queued audio, and the app's streaming threads pull them out through the
// _rpcsx_remotePlay* functions below. Pulling rather than calling into Java keeps JNI off the RSX
// and cellAudio threads, which must never wait on the app.
//
// Frames come from VKPresent's remote capture path (see g_remote_play_max_size): already scaled to
// fit the stream, in RGBA, and read back a frame late, so the game never waits on the GPU for them.
// The app converts them to the encoder's YUV with libyuv.

#include "stdafx.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/IdManager.h"
#include "Emu/System.h"
#include "util/video_provider.h"

#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

extern "C"
{
#include <libavutil/pixfmt.h>
}

LOG_CHANNEL(rp_log, "RemotePlay");

extern atomic_t<recording_mode> g_recording_mode;

// Read by VKPresent.cpp. The largest frame to stream, packed (width << 16) | height, or 0 while
// remote play is off. While it is set the presenter reads every frame back scaled to fit and
// without a GPU stall, instead of taking the full-size, fully synchronous recording path.
atomic_t<u32> g_remote_play_max_size{0};

namespace
{
	class remote_play_sink final : public utils::video_sink
	{
	public:
		remote_play_sink()
		{
			use_internal_video = true;
			use_internal_audio = true;
			accept_all_samples = true;

			// The provider drops a frame whose timestamp rounds to the previous one at this rate.
			// At 120 that never happens to a 60 fps game, so the game sets the pace, not the sink.
			m_framerate = 120;
		}

		void stop(bool /*flush*/) override { m_stopped = true; }
		void pause(bool /*flush*/) override {}
		void resume() override {}

		bool stopped() const { return m_stopped; }

		// The newest frame only: anything older is already late for a live stream.
		bool take_frame(encoder_frame& out)
		{
			std::lock_guard lock(m_video_mtx);

			if (m_frames_to_encode.empty())
			{
				return false;
			}

			out = std::move(m_frames_to_encode.back());
			m_frames_to_encode.clear();
			return true;
		}

		bool take_samples(std::deque<encoder_sample>& out)
		{
			std::lock_guard lock(m_audio_mtx);

			if (m_samples_to_encode.empty())
			{
				return false;
			}

			out.swap(m_samples_to_encode);
			return true;
		}

	private:
		atomic_t<bool> m_stopped{false};
	};

	std::mutex g_sink_mutex;
	std::shared_ptr<remote_play_sink> g_sink;

	// Stereo s16 samples converted but not yet handed out. Only the app's one audio thread polls.
	std::mutex g_audio_mutex;
	std::vector<s16> g_audio_pending;

	std::shared_ptr<remote_play_sink> current_sink()
	{
		std::lock_guard lock(g_sink_mutex);
		return g_sink;
	}

	// Tightly packed RGBA (pitch = width * 4). The remote capture path always delivers RGBA; a
	// BGRA frame can only come from elsewhere and is swapped here rather than miscoloured.
	void copy_rgba(const u8* src, u32 pitch, u32 width, u32 height, bool bgra, u8* dst)
	{
		const usz row_bytes = usz{width} * 4;

		for (u32 row = 0; row < height; row++)
		{
			const u8* in = src + usz{row} * pitch;
			u8* out = dst + usz{row} * row_bytes;

			if (!bgra)
			{
				std::memcpy(out, in, row_bytes);
				continue;
			}

			for (u32 col = 0; col < width; col++)
			{
				out[col * 4 + 0] = in[col * 4 + 2];
				out[col * 4 + 1] = in[col * 4 + 1];
				out[col * 4 + 2] = in[col * 4 + 0];
				out[col * 4 + 3] = in[col * 4 + 3];
			}
		}
	}

	// One cellAudio block (f32, interleaved, 2/6/8 channels) appended as stereo s16.
	void append_stereo_s16(const utils::video_sink::encoder_sample& block, std::vector<f32>& scratch, std::vector<s16>& out)
	{
		const u32 frames = block.sample_count;
		const u32 channels = block.channels;
		const f32* src = reinterpret_cast<const f32*>(block.data.data());

		if (!frames || !channels || block.data.size() < usz{frames} * channels * sizeof(f32))
		{
			return;
		}

		const f32* stereo = src;

		if (channels > 2)
		{
			scratch.resize(usz{frames} * 2);
			AudioBackend::downmix(frames * channels, channels, audio_channel_layout::stereo, src, scratch.data());
			stereo = scratch.data();
		}

		const usz base = out.size();
		out.resize(base + usz{frames} * 2);

		for (usz i = 0; i < usz{frames} * 2; i++)
		{
			const f32 sample = channels == 1 ? src[i / 2] : stereo[i];
			out[base + i] = static_cast<s16>(std::clamp(sample, -1.f, 1.f) * 32767.f);
		}
	}
}

// Starts handing frames and audio to the app. Only while a game runs: the provider lives in the
// game's g_fxo and goes away with it.
extern "C" bool _rpcsx_remotePlayStart(int max_width, int max_height)
{
	if (Emu.IsStopped() || !g_fxo->is_init<utils::video_provider>())
	{
		rp_log.warning("remote play: no game running");
		return false;
	}

	auto sink = std::make_shared<remote_play_sink>();

	{
		std::lock_guard lock(g_sink_mutex);
		g_sink = sink;
	}

	{
		std::lock_guard lock(g_audio_mutex);
		g_audio_pending.clear();
	}

	g_remote_play_max_size = (static_cast<u32>(std::clamp(max_width, 16, 4096)) << 16) | static_cast<u32>(std::clamp(max_height, 16, 4096));

	if (!g_fxo->get<utils::video_provider>().set_video_sink(sink, recording_mode::rpcs3))
	{
		rp_log.error("remote play: the recording hook is taken (the game is recording itself)");
		g_remote_play_max_size = 0;
		std::lock_guard lock(g_sink_mutex);
		g_sink.reset();
		return false;
	}

	g_recording_mode = recording_mode::rpcs3;
	rp_log.notice("remote play: capture started, up to %dx%d", max_width, max_height);
	return true;
}

extern "C" void _rpcsx_remotePlayStop()
{
	std::shared_ptr<remote_play_sink> sink;

	{
		std::lock_guard lock(g_sink_mutex);
		sink.swap(g_sink);
	}

	g_remote_play_max_size = 0;

	if (!sink)
	{
		return;
	}

	sink->stop(false);

	// The game may already be gone, and its provider with it.
	if (!Emu.IsStopped() && g_fxo->is_init<utils::video_provider>())
	{
		g_recording_mode = recording_mode::stopped;
		g_fxo->get<utils::video_provider>().set_video_sink(nullptr, recording_mode::rpcs3);
	}

	rp_log.notice("remote play: capture stopped");
}

// Waits up to timeout_ms for a frame and writes it as tightly packed RGBA.
// info receives width, height and the frame's timestamp in ms (low, high).
// Returns 1 with a frame, 0 on timeout, -1 when capture is not running, -2 when cap is too small
// (info still holds the size, and that frame is dropped).
extern "C" int _rpcsx_remotePlayPollVideo(u8* dst, s64 cap, s32* info, int timeout_ms)
{
	const auto sink = current_sink();

	if (!sink || sink->stopped() || !dst || !info)
	{
		return -1;
	}

	const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
	utils::video_sink::encoder_frame frame;

	while (!sink->take_frame(frame))
	{
		if (sink->stopped())
		{
			return -1;
		}

		if (std::chrono::steady_clock::now() >= deadline)
		{
			return 0;
		}

		std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}

	const u32 width = frame.width & ~1u;
	const u32 height = frame.height & ~1u;
	const usz needed = usz{width} * height * 4;

	info[0] = static_cast<s32>(width);
	info[1] = static_cast<s32>(height);
	info[2] = static_cast<s32>(frame.timestamp_ms & 0xffffffffu);
	info[3] = static_cast<s32>(frame.timestamp_ms >> 32);

	if (!width || !height || frame.data.size() < usz{frame.pitch} * height)
	{
		return 0;
	}

	if (static_cast<usz>(cap) < needed)
	{
		return -2;
	}

	copy_rgba(frame.data.data(), frame.pitch, width, height, frame.av_pixel_format == AV_PIX_FMT_BGRA, dst);
	return 1;
}

// Waits up to timeout_ms for audio and writes up to cap_frames stereo s16 frames at 48 kHz.
// Returns the frames written, 0 on timeout, -1 when capture is not running.
extern "C" int _rpcsx_remotePlayPollAudio(s16* dst, int cap_frames, int timeout_ms)
{
	const auto sink = current_sink();

	if (!sink || sink->stopped() || !dst || cap_frames <= 0)
	{
		return -1;
	}

	std::lock_guard lock(g_audio_mutex);

	const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
	std::deque<utils::video_sink::encoder_sample> blocks;
	std::vector<f32> scratch;

	while (g_audio_pending.empty())
	{
		if (sink->take_samples(blocks))
		{
			for (const auto& block : blocks)
			{
				append_stereo_s16(block, scratch, g_audio_pending);
			}

			blocks.clear();
			continue;
		}

		if (sink->stopped())
		{
			return -1;
		}

		if (std::chrono::steady_clock::now() >= deadline)
		{
			return 0;
		}

		std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}

	const usz frames = std::min<usz>(g_audio_pending.size() / 2, static_cast<usz>(cap_frames));
	std::copy_n(g_audio_pending.begin(), frames * 2, dst);
	g_audio_pending.erase(g_audio_pending.begin(), g_audio_pending.begin() + frames * 2);
	return static_cast<int>(frames);
}
