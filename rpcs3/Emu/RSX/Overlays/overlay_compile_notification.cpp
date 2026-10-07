#include "stdafx.h"
#include "overlay_message.h"
#include "overlay_loading_icon.hpp"

namespace rsx
{
	namespace overlays
	{
		static std::shared_ptr<loading_icon24> s_shader_loading_icon24;
		static std::shared_ptr<loading_icon24> s_ppu_loading_icon24;

		void show_shader_compile_notification()
		{
			if (!s_shader_loading_icon24)
			{
				// Creating the icon requires FS read, so it is important to cache it
				s_shader_loading_icon24 = std::make_shared<loading_icon24>();
			}

			queue_message(
				localized_string_id::RSX_OVERLAYS_SPINNER_NO_TEXT,
				5'000'000,
				{},
				message_pin_location::bottom_left,
				s_shader_loading_icon24,
				true);
		}

		void show_spu_compile_notification()
		{
			// Called for every SPU function built during play, which on a first run is thousands a
			// minute from several SPU threads at once. Refresh the message at most twice a second,
			// so the hint costs nothing next to the compile it announces.
			static atomic_t<u64> s_last_refresh{0};

			const u64 now = std::chrono::duration_cast<std::chrono::microseconds>(
				std::chrono::steady_clock::now().time_since_epoch()).count();
			const u64 last = s_last_refresh;

			if (now - last < 500'000 || !s_last_refresh.compare_and_swap_test(last, now))
			{
				return;
			}

			// Creating the icon reads a file; a static local is built once, whichever SPU thread
			// gets here first
			static const auto s_spu_loading_icon24 = std::make_shared<loading_icon24>();

			queue_message(
				localized_string_id::RSX_OVERLAYS_COMPILING_SPU_CODE,
				3'000'000,
				{},
				message_pin_location::bottom_left,
				s_spu_loading_icon24,
				true);
		}

		std::shared_ptr<atomic_t<u32>> show_ppu_compile_notification()
		{
			if (!s_ppu_loading_icon24)
			{
				// Creating the icon requires FS read, so it is important to cache it
				s_ppu_loading_icon24 = std::make_shared<loading_icon24>();
			}

			std::shared_ptr<atomic_t<u32>> refs = std::make_shared<atomic_t<u32>>(1);

			queue_message(
				localized_string_id::RSX_OVERLAYS_COMPILING_PPU_MODULES,
				20'000'000,
				refs,
				message_pin_location::bottom_left,
				s_ppu_loading_icon24,
				true);

			return refs;
		}
	}
}
