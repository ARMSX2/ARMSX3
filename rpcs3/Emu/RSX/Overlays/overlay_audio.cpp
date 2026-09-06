#include "stdafx.h"
#include "overlay_audio.h"
#include "Emu/System.h"

namespace rsx
{
	namespace overlays
	{
		audio_player::audio_player(const std::string& audio_path, bool audio_in_archive, const std::string& iso_path)
		{
			init_audio(audio_path, audio_in_archive, iso_path);
		}

		void audio_player::init_audio(const std::string& audio_path, bool audio_in_archive, const std::string& iso_path)
		{
			if (audio_path.empty()) return;

			// A platform with no media backend legitimately has no video source: the
			// Android callbacks return nullptr from make_video_source, and ensure() on
			// that aborted the whole process. It fired for any game whose folder holds a
			// SND0.AT3 boot sound -- which is every folder-format game, since for an .iso
			// the fs::is_file check in rsx::thread::thread looks inside the mounted
			// virtual device and never finds one. Boot music simply does not play.
			m_video_source = Emu.GetCallbacks().make_video_source();

			if (!m_video_source)
			{
				rsx_log.notice("Overlay audio unavailable: no video source on this platform");
				return;
			}

			m_video_source->set_audio_path(audio_path, audio_in_archive);

			if (audio_in_archive)
			{
				m_video_source->set_iso_path(iso_path);
			}
		}

		void audio_player::set_active(bool active)
		{
			if (m_video_source)
			{
				m_video_source->set_active(active);
			}
		}
	}
}
