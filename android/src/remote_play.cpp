// Remote play, core side: hands the running game's picture and sound to the app, which streams
// them to a friend's device (the friend plays as player 2).
//
// Nothing here talks to the network. The app's streaming threads pull frames and audio out through
// the _rpcsx_remotePlay* functions below; pulling rather than calling into Java keeps JNI off the
// RSX and cellAudio threads, which must never wait on the app.
//
// Frames come from VKPresent's remote capture path (see g_remote_play_max_size and
// Emu/RSX/remote_capture.h), drawn on the GPU at the stream's size and picked up here, on the app's
// thread, as soon as the GPU marks them done. Where the device can share buffers, a frame is an
// AHardwareBuffer the app hands straight to the video encoder and gives back afterwards
// (_rpcsx_remotePlayReleaseFrame), so it never passes through the CPU; otherwise it is NV12 the app
// copies out once. The RSX thread never touches the pixels either way. Audio comes through RPCS3's
// recording hook (utils::video_provider), which gives every mixed audio block to the sink below.

#include "stdafx.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/IdManager.h"
#include "Emu/RSX/remote_capture.h"
#include "Emu/RSX/VK/VKGSRender.h"
#include "Emu/System.h"
#include "util/video_provider.h"

#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include <android/hardware_buffer.h>

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

// Read by VKPresent.cpp: frames go out as shareable buffers when the device can, unless the app has
// said it cannot import them (_rpcsx_remotePlaySetHardware).
atomic_t<bool> g_remote_play_hardware{false};

namespace
{
	// Frames handed to the app, and finished frames passed over because a newer one was ready.
	atomic_t<u32> g_delivered{0};
	atomic_t<u32> g_superseded{0};

	// How long the GPU usually takes from recording a frame to finishing it, in us, averaged. Only
	// the app's video thread uses it, to sleep through most of that wait instead of polling.
	u64 g_gpu_delay_us = 8000;

	u64 steady_ns()
	{
		return static_cast<u64>(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
	}

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

	// Whether the running game's renderer captures frames for the stream: only the Vulkan one does
	// (VKPresent.cpp). Asked of the renderer itself, not the setting: a renderer picked in the menu
	// only takes over when the game restarts.
	bool renderer_captures()
	{
		auto* const rsx = rsx::get_current_renderer();
		return rsx && dynamic_cast<VKGSRender*>(rsx) != nullptr;
	}

	std::shared_ptr<remote_play_sink> current_sink()
	{
		std::lock_guard lock(g_sink_mutex);
		return g_sink;
	}

	// The newest frame the GPU has finished. Finished frames older than it are freed unread: for a
	// live stream they are already late. A hardware frame stays with the app (its slot held) until
	// _rpcsx_remotePlayReleaseFrame; an NV12 frame is copied to dst and its slot freed at once.
	// Returns 2 with a hardware frame (info: width, height, slot, generation, seq), 1 with an NV12
	// frame in dst (info: width, height), 0 when none is finished yet, -2 when cap is too small for
	// one. pending_since: when the oldest frame still on the GPU was recorded, or 0 for none.
	int take_frame(u8* dst, usz cap, s32* info, u64& pending_since)
	{
		std::lock_guard lock(remote_capture::g_mutex);

		pending_since = 0;
		auto* const ring = remote_capture::g_ring;

		if (!ring || !ring->markers)
		{
			return 0;
		}

		remote_capture::slot* newest = nullptr;
		usz newest_index = 0;

		for (usz i = 0; i < ring->slots.size(); i++)
		{
			auto& slot = ring->slots[i];

			if (slot.state.load(std::memory_order_acquire) != remote_capture::slot_recorded)
			{
				continue;
			}

			if (ring->markers[i] != slot.seq)
			{
				if (!pending_since || slot.recorded_ns < pending_since)
				{
					pending_since = slot.recorded_ns;
				}

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
			newest_index = i;
		}

		if (!newest)
		{
			return 0;
		}

		// The marker was read before the frame; keep it that way.
		std::atomic_thread_fence(std::memory_order_acquire);

		// How long that one took, for the next wait.
		const u64 now = steady_ns();
		if (now > newest->recorded_ns)
		{
			const u64 took_us = std::min<u64>((now - newest->recorded_ns) / 1000, 100'000);
			g_gpu_delay_us = (g_gpu_delay_us * 7 + took_us) / 8;
		}

		info[0] = static_cast<s32>(newest->width);
		info[1] = static_cast<s32>(newest->height);
		info[4] = static_cast<s32>(newest->seq);
		info[5] = 0;

		if (newest->hardware)
		{
			info[2] = static_cast<s32>(newest_index);
			info[3] = static_cast<s32>(newest->generation);
			newest->state.store(remote_capture::slot_held, std::memory_order_release);
			g_delivered++;
			return 2;
		}

		info[2] = -1;
		info[3] = 0;

		const usz size = usz{newest->width} * newest->height * 3 / 2;
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

	if (!renderer_captures())
	{
		// The friend would hear the game and never see it.
		rp_log.warning("remote play: the game is not on the Vulkan renderer, the only one that captures frames");
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

// Whether the running game can be hosted: a game is running, on a renderer that captures frames.
// The app asks before it starts hosting, so the player hears why at once rather than when a friend
// connects to a stream with no picture.
extern "C" bool _rpcsx_remotePlayCanCapture()
{
	return !Emu.IsStopped() && renderer_captures();
}

// Emulation (re)started running (g_emu_callbacks.on_run, on the thread that ran it). A game that
// restarts itself into another executable (exitspawn: Gran Turismo 6 at every boot) or an in-game
// Restart comes back with a new video provider and nothing attached, so the stream froze for good
// while the session looked healthy. While remote play runs, the stream's sink goes onto the new one.
void remote_play_on_run()
{
	const auto sink = current_sink();

	if (!sink || sink->stopped() || Emu.IsStopped() || !g_fxo->is_init<utils::video_provider>())
	{
		return;
	}

	// The same sink again is a no-op: a game that only paused and ran on keeps its stream.
	if (!g_fxo->get<utils::video_provider>().set_video_sink(sink, recording_mode::rpcs3))
	{
		rp_log.error("remote play: the restarted game took the recording hook; the stream stops here");
		return;
	}

	g_recording_mode = recording_mode::rpcs3;
	rp_log.notice("remote play: capture follows the game into its restart");
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

	// The app's threads may be asleep waiting for a frame or audio: wake them to see the stop.
	remote_capture::g_recorded++;
	remote_capture::g_recorded.notify_all();
	sink->samples_added++;
	sink->samples_added.notify_all();

	// The game may already be gone, and its provider with it.
	if (!Emu.IsStopped() && g_fxo->is_init<utils::video_provider>())
	{
		g_recording_mode = recording_mode::stopped;
		g_fxo->get<utils::video_provider>().set_video_sink(nullptr, recording_mode::rpcs3);
	}

	rp_log.notice("remote play: capture stopped");
}

// Waits up to timeout_ms for a frame. Runs on the app's video thread. info has 6 ints. Returns:
//  2: a hardware frame. info: width, height, slot, generation, seq. The slot is the app's until
//     _rpcsx_remotePlayReleaseFrame(slot, seq); _rpcsx_remotePlayHardwareBuffer gives its buffer.
//  1: an NV12 frame written to dst: the Y plane, then the interleaved UV plane, rows packed
//     (width x height x 3 / 2 bytes). info: width, height, -1, 0, seq.
//  0: timeout. -1: capture is not running. -2: cap is too small (info holds the size; the frame
//     is dropped).
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
		// Read before looking, so a frame recorded in between still wakes the wait below.
		const u32 recorded = remote_capture::g_recorded;
		u64 pending_since = 0;

		if (const int result = take_frame(dst, static_cast<usz>(cap), info, pending_since))
		{
			return result;
		}

		if (sink->stopped())
		{
			return -1;
		}

		const auto now = std::chrono::steady_clock::now();

		if (now >= deadline)
		{
			return 0;
		}

		if (pending_since)
		{
			// Recorded and still on the GPU, which says nothing when it is done. Sleep through
			// most of the time it usually takes, then look every millisecond.
			const u64 waited_us = (steady_ns() - pending_since) / 1000;
			const u64 sleep_us = waited_us + 1500 < g_gpu_delay_us ? g_gpu_delay_us - waited_us - 1000 : 1000;
			const u64 left_us = std::chrono::duration_cast<std::chrono::microseconds>(deadline - now).count();
			std::this_thread::sleep_for(std::chrono::microseconds(std::min<u64>({sleep_us, left_us, 20'000})));
		}
		else
		{
			// Nothing recorded: sleep until the presenter records a frame.
			const u64 left_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(deadline - now).count();
			remote_capture::g_recorded.wait(recorded, atomic_wait_timeout{left_ns});
		}
	}
}

// Gives a hardware frame's slot back to the presenter, once the encoder has drawn it. seq is the
// frame's, so a late release from an earlier session cannot free a slot that now holds another.
extern "C" void _rpcsx_remotePlayReleaseFrame(int slot, u32 seq)
{
	std::lock_guard lock(remote_capture::g_mutex);

	auto* const ring = remote_capture::g_ring;

	if (!ring || slot < 0 || static_cast<usz>(slot) >= ring->slots.size() || ring->slots[slot].seq != seq)
	{
		return;
	}

	u32 held = remote_capture::slot_held;
	ring->slots[slot].state.compare_exchange_strong(held, remote_capture::slot_free, std::memory_order_acq_rel);
}

// The AHardwareBuffer behind a hardware frame's slot, with a reference the caller releases, or null.
// generation says which buffer it is: it changes when the presenter replaces the slot's buffer.
extern "C" AHardwareBuffer* _rpcsx_remotePlayHardwareBuffer(int slot, u32* generation)
{
	std::lock_guard lock(remote_capture::g_mutex);

	auto* const ring = remote_capture::g_ring;

	if (!ring || slot < 0 || static_cast<usz>(slot) >= ring->slots.size())
	{
		return nullptr;
	}

	auto& entry = ring->slots[slot];

	if (!entry.hardware_buffer)
	{
		return nullptr;
	}

	AHardwareBuffer_acquire(entry.hardware_buffer);

	if (generation)
	{
		*generation = entry.generation;
	}

	return entry.hardware_buffer;
}

// Whether frames may go out as shareable buffers. The app turns it off when it cannot import them;
// the presenter then sends NV12 from its next free slot on.
extern "C" void _rpcsx_remotePlaySetHardware(bool enabled)
{
	g_remote_play_hardware = enabled;
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

// Waits up to timeout_ms for cap_frames of audio and writes them as stereo s16 at 48 kHz. Full
// chunks, so each network packet carries cap_frames rather than whatever cellAudio's last block
// held (about 5 ms, which nearly doubled the packets). On timeout it returns what there is.
// Returns the frames written, 0 when there were none, -1 when capture is not running.
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

	while (g_audio_pending.size() / 2 < static_cast<usz>(cap_frames))
	{
		// Read before looking, so a block that lands in between still wakes the wait below.
		const u32 added = sink->samples_added;

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

		const auto now = std::chrono::steady_clock::now();

		if (now >= deadline)
		{
			break;
		}

		// cellAudio mixes a block every few ms; sleep until the next one arrives.
		const u64 left_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(deadline - now).count();
		sink->samples_added.wait(added, atomic_wait_timeout{left_ns});
	}

	const usz frames = std::min<usz>(g_audio_pending.size() / 2, static_cast<usz>(cap_frames));

	if (!frames)
	{
		return 0;
	}

	std::copy_n(g_audio_pending.begin(), frames * 2, dst);
	g_audio_pending.erase(g_audio_pending.begin(), g_audio_pending.begin() + frames * 2);
	return static_cast<int>(frames);
}
