// SPDX-License-Identifier: GPL-2.0-only
// Sees the Vulkan headers and must never import the iOS view framework; see ios_render.h.

#include "ios_render.h"
#include "ios_host.h"

#include "ios_dispatcher.h"
#include "ios_frame.h"
#include "ios_capture.h"
#include "ios_camera_handler.h"
#include "ios_video_writer.h"
#include "stderr_log_listener.h"

#include "Emu/system_config.h"
#include "Emu/IdManager.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/Audio/Cubeb/CubebBackend.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/RSX/RSXThread.h"
#include "Emu/RSX/Core/RSXRendererFactory.h"
#include "Emu/RSX/GSFrameBase.h"

#include "util/logs.hpp"

#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <string>
#include <string_view>
#include <system_error>
#include <thread>
#include <vector>

LOG_CHANNEL(ios_render_log, "IOSRENDER");

// Defined in Rpcs3Bridge.mm and resolved at app link; Swift plays the file, and a negative volume means the core gave none.
extern "C" void armsx3_play_sound(const char* path, float volume);

namespace armsx3::ios_render
{
	void init_gs_render(utils::serial* ar)
	{
		// The core picks the backend from the setting, as on Android; OpenGL resolves to Vulkan, since Apple builds have no GL backend.
		switch (rsx::resolve_render_backend_type(g_cfg.video.renderer.get()))
		{
		case video_renderer::vulkan:
			ios_render_log.notice("renderer: Vulkan selected -- MoltenVK over the view's "
			                      "CAMetalLayer");
			break;
		default:
			ios_render_log.notice("renderer: Null selected");
			break;
		}

		rsx::create_render_thread(ar);

		// Every boot passes through here before it has an RSX thread, so the capture clock starts with the renderer.
		install_capture_arming_ticker(50);
	}

	// The bridge cannot ask this itself, because answering needs the renderer types.
	static bool rsx_thread_died()
	{
		rsx::thread* rsx = g_fxo->try_get<rsx::thread>();
		if (!rsx)
			return false;
		return static_cast<thread_state>(*static_cast<named_thread<rsx::thread>*>(rsx)) == thread_state::errored;
	}

	void install_services(armsx3::bridge::host_services& services)
	{
		services.get_gs_frame = [] { return armsx3::ios_frame::make_frame(); };
		services.init_gs_render = [](utils::serial* ar) { init_gs_render(ar); };
		services.get_audio = [] { return make_audio(); };
		services.enable_display_sleep = armsx3::ios_frame::enable_display_sleep;
		services.microphone_denied = armsx3::ios_capture::microphone_denied;
		services.set_recording = armsx3::ios_capture::set_recording;
		// The device camera is the PS Eye, handed out only when the Camera setting is Qt; access is asked when a game starts it.
		services.get_camera_handler = [] { return std::make_shared<ios_camera_handler>(armsx3::ios_capture::camera_source()); };
		// The core's overlay sounds go to the Swift player, without waiting.
		services.play_sound = [](const std::string& path, std::optional<float> volume)
		{
			armsx3_play_sound(path.c_str(), volume ? *volume : -1.0f);
		};
		// Recordings go through AVAssetWriter on Apple's hardware encoder; the macOS leg keeps its counting writer.
		services.make_video_writer = armsx3::bridge::make_avfoundation_video_writer;
		services.rsx_thread_died = rsx_thread_died;
	}

	// Follows the configured renderer and falls back to Cubeb, never Null: a Null backend under a non-Null renderer throws in downmix.
	std::shared_ptr<AudioBackend> make_audio()
	{
		switch (g_cfg.audio.renderer.get())
		{
		case audio_renderer::cubeb:
			return std::make_shared<CubebBackend>();

		case audio_renderer::null:
			return std::make_shared<NullAudioBackend>();

		default:
			ios_render_log.error("unsupported audio renderer selected; using Cubeb, the one backend this build ships.");
			return std::make_shared<CubebBackend>();
		}
	}

	namespace
	{
		// Big enough for one stall dump's PPU census; it is BSS, so only slots actually written become resident.
		constexpr unsigned k_log_ring_capacity = 256;
		constexpr unsigned k_log_ring_message_max = 4096;

		// Static, so a slot never written reads as the empty string.
		char s_log_ring[k_log_ring_capacity][k_log_ring_message_max]{};

		// Every published figure derives from this one index, so the counters cannot disagree.
		atomic_t<u64> s_log_ring_index{0};

		// Not derivable from the index: it counts length, not arrivals.
		atomic_t<u64> s_log_ring_truncated{0};

		atomic_t<bool> s_log_ring_installed{false};

		// logs::level counts down in severity, so at least as severe as error is <=.
		constexpr logs::level k_log_ring_min_level = logs::level::error;

		// Bounded, allocation-free append; sets overflowed when part of src did not fit, keeping a byte for the terminator.
		unsigned log_ring_append(char* dst, unsigned used, const char* src, unsigned n,
			bool& overflowed)
		{
			if (!src || n == 0)
			{
				return used;
			}

			const unsigned room = used + 1 < k_log_ring_message_max
				? k_log_ring_message_max - 1 - used
				: 0;

			const unsigned take = n < room ? n : room;

			for (unsigned i = 0; i < take; ++i)
			{
				dst[used + i] = src[i];
			}

			if (take < n)
			{
				overflowed = true;
			}

			return used + take;
		}

		// The stderr listener's level marks, so both transcripts read the same.
		const char* log_ring_mark(logs::level lv)
		{
			switch (lv)
			{
			case logs::level::always:  return ".";
			case logs::level::fatal:   return "F";
			case logs::level::error:   return "E";
			case logs::level::todo:    return "U";
			case logs::level::success: return "S";
			case logs::level::warning: return "W";
			case logs::level::notice:  return "!";
			case logs::level::trace:   return "T";
			}

			return "?";
		}

		// Lock-free and allocation-free like stderr_listener; a torn line is accepted, a lock in the emit path is not.
		struct log_ring_listener final : logs::listener
		{
			void log(u64 /*stamp*/, const logs::message& msg, std::string_view prefix,
				std::string_view text) override
			{
				const logs::level lv = static_cast<logs::level>(msg);

				// One comparison on the rejected path, so trace floods displace nothing.
				if (static_cast<unsigned>(lv) > static_cast<unsigned>(k_log_ring_min_level))
				{
					return;
				}

				const u64 slot = s_log_ring_index.fetch_add(1) % k_log_ring_capacity;
				char* const dst = s_log_ring[slot];

				bool overflowed = false;
				unsigned used = 0;

				used = log_ring_append(dst, used, log_ring_mark(lv), 1, overflowed);
				used = log_ring_append(dst, used, " ", 1, overflowed);

				if (!prefix.empty())
				{
					used = log_ring_append(dst, used, prefix.data(),
						static_cast<unsigned>(prefix.size()), overflowed);
					used = log_ring_append(dst, used, " ", 1, overflowed);
				}

				const char* const name = msg->name;
				used = log_ring_append(dst, used, name,
					name ? static_cast<unsigned>(std::strlen(name)) : 0, overflowed);
				used = log_ring_append(dst, used, ": ", 2, overflowed);
				used = log_ring_append(dst, used, text.data(),
					static_cast<unsigned>(text.size()), overflowed);

				dst[used] = '\0';

				if (overflowed)
				{
					++s_log_ring_truncated;
				}
			}

			// Nothing to flush: the ring is memory.
		};

		// The ticker state, with the same exchange guard as the listener.
		atomic_t<bool> s_ticker_installed{false};
		atomic_t<bool> s_ticker_stop{false};
		atomic_t<u64> s_ticker_ticks{0};

		// Joinable, so stop can prove no tick is still inside g_fxo when RSX goes down.
		std::thread s_ticker;
	}

	void install_log_ring_listener()
	{
		if (s_log_ring_installed.exchange(true))
		{
			return;
		}

		static log_ring_listener s_ring;
		logs::listener::add(&s_ring);
	}

	bool log_ring_installed()
	{
		return s_log_ring_installed.load();
	}

	const char* log_ring_min_level()
	{
		// From the compiled constant, so the log never misstates its own filter.
		switch (k_log_ring_min_level)
		{
		case logs::level::always:  return "always";
		case logs::level::fatal:   return "fatal";
		case logs::level::error:   return "error";
		case logs::level::todo:    return "todo";
		case logs::level::success: return "success";
		case logs::level::warning: return "warning";
		case logs::level::notice:  return "notice";
		case logs::level::trace:   return "trace";
		}

		return "(unknown)";
	}

	unsigned long long log_ring_seen()
	{
		return s_log_ring_index.load();
	}

	unsigned long long log_ring_retained()
	{
		const u64 written = s_log_ring_index.load();
		return written < k_log_ring_capacity ? written : k_log_ring_capacity;
	}

	unsigned long long log_ring_dropped()
	{
		const u64 written = s_log_ring_index.load();
		return written > k_log_ring_capacity ? written - k_log_ring_capacity : 0;
	}

	unsigned long long log_ring_truncated()
	{
		return s_log_ring_truncated.load();
	}

	void log_ring_snapshot(std::vector<std::string>& out)
	{
		out.clear();

		const u64 written = s_log_ring_index.load();
		const u64 held = written < k_log_ring_capacity ? written : k_log_ring_capacity;

		// Oldest first: the oldest held slot is dropped messages past the start.
		const u64 first = written - held;

		for (u64 i = 0; i < held; ++i)
		{
			const char* const line = s_log_ring[(first + i) % k_log_ring_capacity];
			out.push_back(std::string(line));
		}
	}

	void install_capture_arming_ticker(unsigned interval_ms)
	{
		if (s_ticker_installed.exchange(true))
		{
			return;
		}

		if (interval_ms == 0)
		{
			interval_ms = 50;
		}

		s_ticker = std::thread([interval_ms]
		{
			// Wakes on a slice rather than the whole interval, so a stop joins promptly.
			constexpr unsigned k_slice_ms = 10;

			for (;;)
			{
				if (s_ticker_stop.load())
				{
					return;
				}

				// Once the capture is written or has failed there is nothing left to arm.
				if (armsx3::ios_frame::g_capture_written.load() ||
					armsx3::ios_frame::g_capture_failed.load())
				{
					return;
				}

				poll_guest_vblanks();
				armsx3::ios_frame::maybe_arm_capture();

				// The re-arm lives here and not in the evaluator, which flip() calls inside the frame that took the request.
				armsx3::ios_frame::maybe_rearm_capture();

				++s_ticker_ticks;

				for (unsigned slept = 0; slept < interval_ms; slept += k_slice_ms)
				{
					if (s_ticker_stop.load())
					{
						return;
					}

					std::this_thread::sleep_for(std::chrono::milliseconds(k_slice_ms));
				}
			}
		});
	}

	void stop_capture_arming_ticker()
	{
		s_ticker_stop = true;

		if (s_ticker.joinable())
		{
			s_ticker.join();
		}
	}

	bool capture_arming_ticker_installed()
	{
		return s_ticker_installed.load();
	}

	unsigned long long capture_arming_ticks()
	{
		return s_ticker_ticks.load();
	}

	void raise_log_channels()
	{
		// IOSFRAME carries the lifecycle notices; SYS and RSX at notice, never a global raise.
		logs::set_channel_levels({
			{"IOSFRAME", logs::level::trace},
			{"IOSRENDER", logs::level::trace},
			{"SYS", logs::level::notice},
			{"RSX", logs::level::notice},
		});
	}

	int prepare_host()
	{
		static std::once_flag once;
		static int result = 0;

		std::call_once(once, []
		{
			// The log goes to stderr before anything else, since nothing else installs a listener in this build.
			armsx3::bridge::install_stderr_log_listener();

			// The retained ring too, before the emulator has anything to say.
			install_log_ring_listener();

			raise_log_channels();

			armsx3::bridge::host_services services;
			armsx3::bridge::ios_dispatcher::install(services);
			install_services(services);
			armsx3::bridge::set_host_services(services);

			// iOS starts apps in /, which is not writable, and vm.cpp opens its block files by relative name, so chdir to the config directory.
			const char* home = std::getenv("HOME");

			if (home == nullptr || home[0] == '\0')
			{
				result = ENOENT;
				return;
			}

			const std::string work_dir = std::string(home) + "/Library/Application Support/rpcs3";

			// Not returned: the chdir that follows fails, and its errno is the result.
			std::error_code ec;
			std::filesystem::create_directories(work_dir, ec);

			result = ::chdir(work_dir.c_str()) == 0 ? 0 : errno;
		});

		return result;
	}

	std::string write_product_config()
	{
		const char* home = std::getenv("HOME");

		if (home == nullptr || home[0] == '\0')
		{
			errno = ENOENT;
			return std::string();
		}

		const std::string path = std::string(home) + "/Library/Application Support/rpcs3/armsx3-product.yml";
		std::FILE* f = std::fopen(path.c_str(), "wb");

		if (f == nullptr)
		{
			return std::string();
		}

		// One Core: key, since a duplicate YAML key silently undoes the override; these keys are applied last and the settings engine cannot change them.
		std::fputs(
			"Core:\n"
#ifdef LLVM_AVAILABLE
			"  PPU Decoder: \"Recompiler (LLVM)\"\n"
			"  SPU Decoder: \"Recompiler (LLVM)\"\n"
#else
			"  PPU Decoder: \"Interpreter (static)\"\n"
			"  SPU Decoder: \"Interpreter (static)\"\n"
#endif
			"  Thread Scheduler Mode: \"Operating System\"\n"
			"Video:\n"
			"  Renderer: \"Vulkan\"\n"
			"Audio:\n"
			"  Renderer: \"Cubeb\"\n"
			"  Audio Channel Layout: \"Stereo\"\n",
			f);

		// One line, so test_title_config.py's product_text keeps reading only the literal above.
		if (armsx3::ios_capture::camera_denied())
		{
			std::fputs("Input/Output:\n  Camera: \"Null\"\n", f);
		}

		if (std::fclose(f) != 0)
		{
			return std::string();
		}

		return path;
	}

	const char* build_compiler_id()  { return ARMSX3_COMPILER_ID; }

	u64 poll_guest_vblanks()
	{
		// try_get, never get: before init_gs_render there is no RSX thread, and zero means the guest clock has not started.
		if (auto* rsx = g_fxo->try_get<rsx::thread>())
		{
			const u64 vblanks = rsx->display_ctrl().get_vblank_count();
			armsx3::ios_frame::g_guest_vblank_count = vblanks;
			return vblanks;
		}

		return 0;
	}
}

// 0, prepare_host()'s errno, or the config write's errno; the path is truncated to cap and always NUL-terminated.
extern "C" int32_t armsx3_ios_prepare_host(char* out_config_path, uint32_t cap)
{
	const int host = armsx3::ios_render::prepare_host();

	if (host != 0)
	{
		return host;
	}

	const std::string path = armsx3::ios_render::write_product_config();

	if (path.empty())
	{
		return errno != 0 ? errno : EIO;
	}

	if (out_config_path != nullptr && cap > 0)
	{
		std::snprintf(out_config_path, cap, "%s", path.c_str());
	}

	return 0;
}
