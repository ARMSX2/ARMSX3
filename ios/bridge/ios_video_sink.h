// SPDX-License-Identifier: GPL-2.0-only
// Records the core's own frames and audio from its video_provider through an injected writer, never ReplayKit.

#pragma once

#include "util/video_sink.h"
#include "video_writer.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

class ios_video_sink final : public utils::video_sink
{
public:
	// At most this many frames wait for the writer; older ones are dropped and counted.
	static constexpr usz k_max_queued_frames = 8;

	explicit ios_video_sink(std::shared_ptr<armsx3::bridge::video_writer> writer, unsigned fps, unsigned sample_rate);
	~ios_video_sink() override;

	// Does not wait: the provider calls this holding both its mutexes, sometimes on a guest thread, so a join here froze games.
	void stop(bool flush = true) override;
	// Waits up to 10 s for the writer's finish; never called by the provider.
	void finish_and_join();
	void pause(bool flush = true) override;
	void resume() override;

	u64 dropped_frames() const;

	// Meaningful once the drain thread has ended, false before.
	bool finished_ok() const;

private:
	void trim_frames();
	void drain_loop();

	std::shared_ptr<armsx3::bridge::video_writer> m_writer;
	std::atomic<bool> m_quit{false};
	std::atomic<bool> m_drain_on_quit{true};
	std::atomic<u64> m_dropped{0};
	std::atomic<bool> m_writer_failed{false};
	std::atomic<bool> m_finished_ok{false};
	std::mutex m_stop_mtx;
	bool m_stopped = false;
	std::mutex m_join_mtx;
	std::thread m_drain_thread;
	std::thread m_trim_thread;
};

namespace armsx3::bridge
{
	// Defined in the C ABI beside the holder.
	std::shared_ptr<ios_video_sink> current_recording_sink();
}
