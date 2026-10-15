// Remote play, core side: hands the running game's picture and sound to the app, which streams
// them to a friend's device (the friend plays as player 2).
//
// Nothing here talks to the network. The app's streaming threads pull frames and audio out through
// the _rpcsx_remotePlay* functions below; pulling rather than calling into Java keeps JNI off the
// RSX and cellAudio threads, which must never wait on the app.
//
// Frames come from VKPresent's remote capture path (see g_remote_play_max_size and
// Emu/RSX/remote_capture.h): drawn on the GPU straight into the encoder's NV12 at the stream's size,
// and picked up here, on the app's thread, as soon as the GPU marks them done. The RSX thread never
// touches the pixels. Audio comes through RPCS3's recording hook (utils::video_provider), which
// gives every mixed audio block to the sink below.

#include "stdafx.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/IdManager.h"
#include "Emu/RSX/remote_capture.h"
#include "Emu/System.h"
#include "util/video_provider.h"

#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

LOG_CHANNEL(rp_log, "RemotePlay");

extern atomic_t<recording_mode> g_recording_mode;

// Read by VKPresent.cpp. The largest frame to stream, packed (width << 16) | height, or 0 while
// remote play is off. While it is set the presenter reads every frame back scaled to fit and
// without a GPU stall, instead of taking the full-size, fully synchronous recording path.
atomic_t<u32> g_remote_play_max_size{0};

// Counted by VKPresent.cpp while capturing: every game frame the presenter saw, and the ones it
// could not capture because the GPU or the app still held every slot. With the two below they are
// the host's side of the stream stats (_rpcsx_remotePlayCounters).
atomic_t<u32> g_remote_play_frames{0};
atomic_t<u32> g_remote_play_skipped{0};

namespace
{
	// Frames handed to the app, and finished frames passed over because a newer one was ready.
	atomic_t<u32> g_delivered{0};
	atomic_t<u32> g_superseded{0};

	class remote_play_sink final : public utils::video_sink
	{
	public:
		remote_play_sink()
		{
			// Video: the provider then reports that it can take frames (can_consume_frame), which is
			// what turns the presenter's capture on. The frames themselves bypass the provider and go
			// through remote_capture's ring.
			use_internal_video = true;
			use_internal_audio = true;
			accept_all_samples = true;

			// can_consume_frame says no to a second frame within one tick of this rate. At 120 a game
			// running at 60 is never refused, so the game sets the pace, not the sink.
			m_framerate = 120;
		}

		void stop(bool /*flush*/) override { m_stopped = true; }
		void pause(bool /*flush*/) override {}
		void resume() override {}

		bool stopped() const { return m_stopped; }

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

	// The newest frame the GPU has finished, copied to dst (NV12, rows packed) and its slot freed.
	// Finished frames older than it are freed unread: for a live stream they are already late.
	// Returns 1 with a frame, 0 when none is ready, -2 when cap is too small (info still says why).
	int take_frame(u8* dst, usz cap, s32* info)
	{
		std::lock_guard lock(remote_capture::g_mutex);

		auto* const ring = remote_capture::g_ring;

		if (!ring || !ring->markers)
		{
			return 0;
		}

		remote_capture::slot* newest = nullptr;

		for (usz i = 0; i < ring->slots.size(); i++)
		{
			auto& slot = ring->slots[i];

			if (slot.state.load(std::memory_order_acquire) != remote_capture::slot_recorded || ring->markers[i] != slot.seq)
			{
				continue;
			}

			if (newest && static_cast<s32>(slot.seq - newest->seq) < 0)
			{
				slot.state.store(remote_capture::slot_free, std::memory_order_release);
				g_superseded++;
				continue;
			}

			if (newest)
			{
				newest->state.store(remote_capture::slot_free, std::memory_order_release);
				g_superseded++;
			}

			newest = &slot;
		}

		if (!newest)
		{
			return 0;
		}

		// The marker was read before the pixels; keep it that way.
		std::atomic_thread_fence(std::memory_order_acquire);

		const usz size = usz{newest->width} * newest->height * 3 / 2;
		info[0] = static_cast<s32>(newest->width);
		info[1] = static_cast<s32>(newest->height);
		info[2] = 0;
		info[3] = 0;

		int result = -2;

		if (cap >= size)
		{
			std::memcpy(dst, newest->data, size);
			g_delivered++;
			result = 1;
		}

		newest->state.store(remote_capture::slot_free, std::memory_order_release);
		return result;
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

	// The presenter frees its capture buffers at its next flip once this is 0.
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

// Waits up to timeout_ms for a frame and writes it as NV12: the Y plane, then the interleaved UV
// plane, rows packed (width x height x 3 / 2 bytes). info receives width, height and two unused
// ints. Runs on the app's video thread, which does the only copy a frame gets on the CPU.
// Returns 1 with a frame, 0 on timeout, -1 when capture is not running, -2 when cap is too small
// (info still holds the size, and that frame is dropped).
extern "C" int _rpcsx_remotePlayPollVideo(u8* dst, s64 cap, s32* info, int timeout_ms)
{
	const auto sink = current_sink();

	if (!sink || sink->stopped() || !dst || !info || cap <= 0)
	{
		return -1;
	}

	const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);

	while (true)
	{
		if (const int result = take_frame(dst, static_cast<usz>(cap), info))
		{
			return result;
		}

		if (sink->stopped())
		{
			return -1;
		}

		if (std::chrono::steady_clock::now() >= deadline)
		{
			return 0;
		}

		// The GPU finishes a frame within a few ms of the flip; this finds it within one more.
		std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}
}

// Running totals for the stream stats, in out[0..3]: game frames seen while capturing, frames not
// captured (no free slot), frames handed to the app, finished frames passed over for a newer one.
extern "C" void _rpcsx_remotePlayCounters(u32* out)
{
	if (!out)
	{
		return;
	}

	out[0] = g_remote_play_frames;
	out[1] = g_remote_play_skipped;
	out[2] = g_delivered;
	out[3] = g_superseded;
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
