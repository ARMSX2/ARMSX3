// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"
#include "xmb_media.h"

using namespace armsx3::bridge::abi_internal;

namespace
{
	constexpr int k_shot_wait_ms = 2000;
	std::mutex g_recording_mutex;
	std::shared_ptr<ios_video_sink> g_recording_sink;
}


Rpcs3IOS_Status Rpcs3IOS_CaptureScreenshot(Rpcs3IOS_ImageSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_CaptureScreenshot");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	if (!Emu.IsRunning())
	{
		return finished_status("Rpcs3IOS_CaptureScreenshot", RPCS3_IOS_STATUS_WRONG_STATE);
	}

	Rpcs3IOS_Status status = RPCS3_IOS_STATUS_SCREENSHOT_NOT_TAKEN;
#if TARGET_OS_IPHONE
	const u64 since = armsx3::ios_frame::screenshot_generation();
	armsx3::ios_frame::request_screenshot();
	for (int waited = 0; waited < k_shot_wait_ms && armsx3::ios_frame::screenshot_generation() == since && Emu.IsRunning();
		waited += 10)
	{
		usleep(10 * 1000);
	}

	std::vector<u8> rgba;
	u32 width = 0;
	u32 height = 0;
	u64 generation = 0;
	if (armsx3::ios_frame::copy_screenshot(rgba, width, height, generation) && generation > since)
	{
		sink(context, rgba.data(), width, height);
		status = RPCS3_IOS_STATUS_OK;
	}
	else
	{
		// Nothing came: withdraw the request, so a later frame is not taken for no one.
		armsx3::ios_frame::cancel_screenshot();
	}
#endif

	return finished_status("Rpcs3IOS_CaptureScreenshot", status);
}

// Android's disc swap, with the bridge queue in place of its lifecycle mutex so a stop waits behind a swap.
Rpcs3IOS_Status Rpcs3IOS_DiscSwapState(int32_t* state)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_DiscSwapState");
	}

	if (state == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const bool live = Emu.IsRunning() || Emu.IsPaused();
	*state = !live ? 0
		: armsx3::bridge::g_disc_eject_enabled.load() ? 1
		: armsx3::bridge::g_disc_insert_enabled.load() ? 2 : 0;
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_ChangeDisc(const char* path, int32_t* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_ChangeDisc");
	}

	if (path == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::size_t length = strnlen(path, 4096);
	if (length == 0 || length >= 4096)
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	const int32_t code = [picked = std::string(path, length)]() mutable -> int32_t
	{
		while (picked.size() > 1 && picked.ends_with('/'))
		{
			picked.pop_back();
		}

		if (!armsx3::bridge::g_disc_eject_enabled.load() && !armsx3::bridge::g_disc_insert_enabled.load())
		{
			return 1;
		}

		// The new disc is checked before the old one leaves; a document picker may hand back any file inside a folder dump.
		const bool image = fs::is_file(picked) && is_iso_file(picked);
		std::string target;
		if (image)
		{
			if (!iso_archive(picked).is_valid())
			{
				return 2;
			}
		}
		else
		{
			target = fs::is_dir(picked) ? picked : fs::get_parent_dir(picked);
			std::string disc_root;
			std::string game_dir;
			if (disc::get_disc_type(target, disc_root, game_dir) == disc::disc_type::invalid)
			{
				return 2;
			}
		}

		// The eject completes on the game's own sysutil callback, which needs it running.
		for (int waited = 0; !Emu.IsRunning() && waited < 3000; waited += 20)
		{
			usleep(20 * 1000);
		}

		if (!Emu.IsRunning())
		{
			return 3;
		}

		// Inserting before the game has seen the tray empty would let its late unmount take the new disc out.
		if (armsx3::bridge::g_disc_eject_enabled.load())
		{
			Emu.EjectDisc();
			for (int waited = 0; !armsx3::bridge::g_disc_insert_enabled.load() && Emu.IsRunning() && waited < 10000; waited += 20)
			{
				usleep(20 * 1000);
			}

			if (!armsx3::bridge::g_disc_insert_enabled.load())
			{
				return 3;
			}
		}

		// An image goes through the ISO device a boot uses; a file the game still has open keeps its own handle.
		if (image)
		{
			load_iso(picked);
			target = iso_device::virtual_device_name + "/";
		}

		return Emu.InsertDisc(target) == game_boot_result::no_errors ? 0 : 4;
	}();

	*result = code;
	return RPCS3_IOS_STATUS_OK;
}

// NUL-terminated because a guest-chosen extension can hold a newline; the paths are host paths and never logged.
Rpcs3IOS_Status Rpcs3IOS_TakeExportedPhotos(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_TakeExportedPhotos");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	std::string joined;
	for (const std::string& path : armsx3::bridge::take_exported_photos())
	{
		joined += path;
		joined += '\0';
	}

	sink(context, joined.data(), static_cast<uint32_t>(joined.size()));
	return RPCS3_IOS_STATUS_OK;
}

std::shared_ptr<ios_video_sink> armsx3::bridge::current_recording_sink()
{
	std::lock_guard lock(g_recording_mutex);
	return g_recording_sink;
}

Rpcs3IOS_Status Rpcs3IOS_StartRecording(const char* path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_StartRecording");
	}

	if (path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The app names the file under Documents/Recordings.
	const std::size_t length = strnlen(path, 4096);
	const std::string name(path, length);
	if (length == 0 || length >= 4096 || path[0] != '/' || (name + "/").find("/../") != std::string::npos
		|| !name.ends_with(".mp4"))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	std::lock_guard lock(g_recording_mutex);

	// cellRec has priority, as on desktop.
	if (g_recording_mode == recording_mode::cell || g_recording_sink)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const armsx3::bridge::host_services& services = armsx3::bridge::current_host_services();
	utils::video_provider* const provider = g_fxo->try_get<utils::video_provider>();
	if (provider == nullptr || !services.make_video_writer)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	g_cfg_recording.load();
	armsx3::bridge::video_writer_settings settings;
	settings.path = name;
	settings.width = static_cast<unsigned>(g_cfg_recording.video.width.get());
	settings.height = static_cast<unsigned>(g_cfg_recording.video.height.get());
	settings.fps = static_cast<unsigned>(g_cfg_recording.video.framerate.get());
	settings.video_bps = static_cast<unsigned>(g_cfg_recording.video.video_bps.get());
	// shortcut: cellAudio's fixed rate, so a game on the rsxaudio provider records at the wrong speed; read its rate then.
	settings.sample_rate = DEFAULT_AUDIO_SAMPLING_RATE;
	settings.audio_bps = static_cast<unsigned>(g_cfg_recording.audio.audio_bps.get());

	std::shared_ptr<armsx3::bridge::video_writer> writer = services.make_video_writer(settings);
	if (!writer || !writer->append_video || !writer->append_audio || !writer->finish)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	auto sink = std::make_shared<ios_video_sink>(std::move(writer), settings.fps, settings.sample_rate);
	if (!provider->set_video_sink(sink, recording_mode::rpcs3))
	{
		sink->stop(false);
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	provider->set_pause_time_us(0);
	g_recording_mode = recording_mode::rpcs3;
	g_recording_sink = std::move(sink);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_StopRecording(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_StopRecording");
	}

	std::lock_guard lock(g_recording_mutex);

	// Only rpcs3 becomes stopped: a game's cellRec that took the provider over keeps recording.
	static_cast<void>(g_recording_mode.compare_and_swap_test(recording_mode::rpcs3, recording_mode::stopped));

	const std::shared_ptr<ios_video_sink> sink = std::move(g_recording_sink);
	if (!sink)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	sink->stop(true);
	// The wait for the writer is here on the bridge queue, never inside the provider's stop().
	sink->finish_and_join();
	if (utils::video_provider* const provider = g_fxo->try_get<utils::video_provider>())
	{
		provider->set_video_sink(nullptr, recording_mode::rpcs3);
	}
	// A file the writer did not complete, or not within 10 s, is reported as not written.
	return sink->finished_ok() ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
}

// The XMB's view of a game's own media; the paths are host paths and never logged.
Rpcs3IOS_Status Rpcs3IOS_XmbDecodeAudio(const char* in_path, const char* out_path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbDecodeAudio");
	}

	if (in_path == nullptr || out_path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	return armsx3::bridge::xmb::decode_audio(in_path, out_path) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
}

Rpcs3IOS_Status Rpcs3IOS_XmbDecodeIconAudio(const char* in_path, const char* out_path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbDecodeIconAudio");
	}

	if (in_path == nullptr || out_path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	return armsx3::bridge::xmb::decode_pam_audio(in_path, out_path) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
}

Rpcs3IOS_Status Rpcs3IOS_XmbDiscMedia(const char* image_path, const char* serial, const char* out_dir)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbDiscMedia");
	}

	if (image_path == nullptr || serial == nullptr || out_dir == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// An encrypted image's key is looked up under the config folder `Emu.Init()` sets.
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	switch (armsx3::bridge::xmb::copy_disc_media(image_path, serial, out_dir))
	{
	case armsx3::bridge::xmb::disc_media::copied: return RPCS3_IOS_STATUS_OK;
	case armsx3::bridge::xmb::disc_media::absent: return RPCS3_IOS_STATUS_WRONG_STATE;
	case armsx3::bridge::xmb::disc_media::failed: break;
	}
	return RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
}

Rpcs3IOS_Status Rpcs3IOS_XmbIconOpen(const char* path, Rpcs3IOS_XmbIcon** out_icon, uint32_t* width, uint32_t* height)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbIconOpen");
	}

	if (path == nullptr || out_icon == nullptr || width == nullptr || height == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_icon = armsx3::bridge::xmb::icon_open(path);
	if (*out_icon == nullptr)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	armsx3::bridge::xmb::icon_size(*out_icon, *width, *height);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_XmbIconFrame(Rpcs3IOS_XmbIcon* icon, uint8_t* rgba, uint64_t capacity, int64_t* time_ms)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbIconFrame");
	}

	if (icon == nullptr || rgba == nullptr || time_ms == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*time_ms = armsx3::bridge::xmb::icon_frame(icon, rgba, capacity);
	return *time_ms < 0 ? RPCS3_IOS_STATUS_WRONG_STATE : RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_XmbIconClose(Rpcs3IOS_XmbIcon* icon)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_XmbIconClose");
	}

	armsx3::bridge::xmb::icon_close(icon);
	return RPCS3_IOS_STATUS_OK;
}
