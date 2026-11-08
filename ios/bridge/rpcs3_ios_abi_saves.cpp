// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{

	// Call it before the kill is armed: the kill's completion clears the core's config path.
	std::string restart_config(const std::string& title)
	{
		{
			std::lock_guard lock(g_boot_config_mutex);
			if (!title.empty() && g_boot_config_title == title && !g_boot_config.empty())
			{
				return g_boot_config;
			}
		}

		if (std::string used = Emu.GetUsedConfig(); !used.empty())
		{
			return used;
		}

		return {};
	}
	// The core's own save recipe, as the PS menu and Android run it; the slot layout is Android's so a state stays portable.

	// Never a status: the value a result holds until its callback publishes one.
	constexpr Rpcs3IOS_Status k_unpublished = 1;

	struct kill_result
	{
		std::atomic<Rpcs3IOS_Status> status{k_unpublished};
	};

	// A subfolder because the core's history scan skips directories, so the slots never feed its numbering or pruning.
	std::string slot_dir(const std::string& title)
	{
		return get_savestate_file(title, "", -1) + "armsx3_slots/";
	}

	// Derived from the core and never spelled out here, so it follows the core.
	std::string savestates_root()
	{
		constexpr std::string_view probe = "_";
		const std::string dir = get_savestate_file(probe, "", -1);
		return dir.substr(0, dir.size() - probe.size() - 1);
	}

	// Narrower than a boot path, because only the emulator writes state files.
	bool under_savestates_root(const std::string& path)
	{
		char root[PATH_MAX];
		char real[PATH_MAX];
		if (realpath(savestates_root().c_str(), root) == nullptr || realpath(path.c_str(), real) == nullptr)
		{
			return false;
		}

		return std::string_view{real}.starts_with(std::string{root} + "/");
	}

	// Hard-links the new state into the slot, so the previous slot survives any failure and the restart boots the core's own file.
	Rpcs3IOS_Status capture_slot(uint32_t file_slot, const std::string& title, const std::string& newest, const std::string& before, std::string& out_name)
	{
		const std::string dir = slot_dir(title);

		if (newest.empty() || newest == before || !fs::is_file(newest))
		{
			return RPCS3_IOS_STATUS_SAVESTATE_NOT_WRITTEN;
		}

		if (!fs::create_path(dir))
		{
			return RPCS3_IOS_STATUS_SAVESTATE_NOT_WRITTEN;
		}

		constexpr std::string_view stem = ".SAVESTAT";
		const usz at = newest.rfind(stem);
		const std::string ext = at == umax ? std::string{} : newest.substr(at + stem.size());
		const std::string base = dir + "slot" + std::to_string(file_slot) + std::string(stem);
		// Named so no `slot<N>.SAVESTAT*` lookup can ever take it for a slot.
		const std::string temp = dir + ".partial-slot" + std::to_string(file_slot);

		// A directory there cannot be removed, and then the link and the copy both fail, which is the safe outcome.
		fs::remove_file(temp);

		const bool staged = ::link(newest.c_str(), temp.c_str()) == 0 || fs::copy_file(newest, temp, false);
		if (!staged || !fs::rename(temp, base + ext, true))
		{
			fs::remove_file(temp);
			return RPCS3_IOS_STATUS_SAVESTATE_NOT_WRITTEN;
		}

		for (const std::string_view spelling : {"", ".zst", ".gz"})
		{
			if (spelling != ext)
			{
				fs::remove_file(base + std::string(spelling));
			}
		}

		out_name = newest.substr(newest.find_last_of('/') + 1);
		return RPCS3_IOS_STATUS_OK;
	}

	// Android's wait: two frames at 30 fps plus slack, then the save goes ahead without a thumbnail.
	constexpr int k_thumb_wait_ms = 250;

	// Only a thumbnail newer than `since` is this save's; otherwise the old one is removed, so a slot never shows another moment.
	void write_slot_thumbnail(const std::string& dir, uint32_t file_slot, u64 since)
	{
		const std::string thumb = dir + "slot" + std::to_string(file_slot) + ".thumb";
		const std::string temp = dir + ".partial-thumb" + std::to_string(file_slot);
		std::vector<u8> rgba;
		u32 width = 0;
		u32 height = 0;
		u64 generation = 0;
#if TARGET_OS_IPHONE
		const bool have = armsx3::ios_frame::copy_thumbnail(rgba, width, height, generation);
#else
		const bool have = false;
#endif

		if (!have || generation <= since)
		{
			fs::remove_file(thumb);
			return;
		}

		{
			fs::file out(temp, fs::rewrite);

			if (!out)
			{
				fs::remove_file(thumb);
				return;
			}

			const u32 header[2] = {width, height};
			out.write("AX3T", 4);
			out.write(header, sizeof(header));
			out.write(rgba.data(), rgba.size());
		}

		if (!fs::rename(temp, thumb, true))
		{
			fs::remove_file(temp);
			fs::remove_file(thumb);
		}
	}

	// The core destroys the callback without calling it when it gives a kill up, so an expired, unpublished token means it never will.
	Rpcs3IOS_Status await_kill_callback(const std::weak_ptr<int>& alive, const kill_result& result, int max_polls, Rpcs3IOS_Status abandoned, Rpcs3IOS_Status timed_out)
	{
		for (int poll = 0; poll < max_polls; ++poll)
		{
			if (const Rpcs3IOS_Status status = result.status.load(); status != k_unpublished)
			{
				return status;
			}

			if (alive.expired())
			{
				// The fence pairs the expiry this read saw with the callback's publication.
				std::atomic_thread_fence(std::memory_order_acquire);
				const Rpcs3IOS_Status status = result.status.load();
				return status != k_unpublished ? status : abandoned;
			}

			usleep(50 * 1000);
		}

		return timed_out;
	}
}


Rpcs3IOS_Status Rpcs3IOS_SaveStateToSlot(uint32_t file_slot, int32_t resume, char* out_history_name, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SaveStateToSlot");
	}

	if (out_history_name == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0 || file_slot > 11 || (resume != 0 && resume != 1))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	out_history_name[0] = '\0';

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const system_state now = Emu.GetStatus(false);
	if (now != system_state::running && now != system_state::paused)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	if (const Rpcs3IOS_Status halted = halt_status("Rpcs3IOS_SaveStateToSlot"); halted != RPCS3_IOS_STATUS_OK)
	{
		return halted;
	}

	// Read now, because by the callback the VM is torn down and Emu no longer answers for the title.
	const std::string title = Emu.GetTitleID();
	const std::string boot = Emu.GetBoot();
	if (title.empty())
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	// Asked for before the kill and only while running, because a paused game presents no new frame.
	u64 thumb_since = ~0ull;
#if TARGET_OS_IPHONE
	if (now == system_state::running)
	{
		thumb_since = armsx3::ios_frame::thumbnail_generation();
		armsx3::ios_frame::request_thumbnail();
		for (int waited = 0; waited < k_thumb_wait_ms && armsx3::ios_frame::thumbnail_generation() == thumb_since; waited += 10)
		{
			usleep(10 * 1000);
		}
	}
#endif

	const std::string cfg = restart_config(title);

	const std::string before = get_savestate_file(title, boot, 1);

	// shortcut: a write the volume cannot hold aborts in the core's writer and this check only estimates; upgrade with a writer that fails cleanly.
	{
		fs::stat_t last{};
		const u64 need = !before.empty() && fs::get_stat(before, last) && !last.is_directory ? last.size * 2 : (1ull << 30);
		const std::string root = savestates_root();
		fs::device_stat space{};

		if (fs::statfs(fs::is_dir(root) ? root : fs::get_config_dir(), space) && space.avail_free < need)
		{
			utils::stderr_witness_printf("ARMSX3: save refused, %llu MiB free, about %llu MiB needed.\n",
				static_cast<unsigned long long>(space.avail_free >> 20), static_cast<unsigned long long>(need >> 20));
			return RPCS3_IOS_STATUS_SAVESTATE_FAILED;
		}
	}

	// The SPU-refusal count before the kill is armed; see callbacks.h.
	const u64 spu_refusals = armsx3::bridge::spu_savestate_refusal_count();

	auto token = std::make_shared<int>(0);
	const std::weak_ptr<int> alive = token;
	const auto result = std::make_shared<kill_result>();
	const bool restart = resume == 1;

	Emu.CallFromMainThread([token = std::move(token), result, restart]() mutable
	{
		// The callback holds the only strong reference to the token and only reports; the slot copy and restart run on this thread.
		Emu.after_kill_callback = [token = std::move(token), result]()
		{
			result->status.store(RPCS3_IOS_STATUS_OK);
		};

		if (restart)
		{
			Emu.SetContinuousMode(true);
		}

		Emu.Kill(false, true);
	});

	// A kill that was armed and timed out reads SAVESTATE_NOT_WRITTEN, never FAILED.
	Rpcs3IOS_Status status = await_kill_callback(alive, *result, 2400, RPCS3_IOS_STATUS_SAVESTATE_FAILED, RPCS3_IOS_STATUS_SAVESTATE_NOT_WRITTEN);

	// shortcut: the SPU reason is seen only through the core's overlay message, so with that interface off it reads SAVESTATE_FAILED; upgrade with a core reason code.
	if (status == RPCS3_IOS_STATUS_SAVESTATE_FAILED && armsx3::bridge::spu_savestate_refusal_count() != spu_refusals)
	{
		status = RPCS3_IOS_STATUS_SAVESTATE_SPU_BUSY;
	}

	std::string name;

	if (status == RPCS3_IOS_STATUS_OK)
	{
		// The kill finished, so the state is complete on disk if the core wrote one, which a new name shows.
		const std::string newest = get_savestate_file(title, boot, 1);
		const bool wrote = !newest.empty() && newest != before && fs::is_file(newest);
		status = capture_slot(file_slot, title, newest, before, name);
		if (status == RPCS3_IOS_STATUS_OK)
		{
			write_slot_thumbnail(slot_dir(title), file_slot, thumb_since);
		}

		// Only a state this kill wrote is booted, and the status stays the slot's whatever the boot does.
		if (restart && wrote)
		{
			boot_with(newest.c_str(), cfg);
		}
	}

	if (status == RPCS3_IOS_STATUS_OK)
	{
		std::snprintf(out_history_name, cap, "%s", name.c_str());
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_SaveStateToSlot finished with status %d.\n", static_cast<int>(status));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_GetSaveStateDir(const char* serial, char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetSaveStateDir");
	}

	if (serial == nullptr || out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0 || !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const std::string dir = get_savestate_file(serial, "", -1);
	if (dir.size() >= cap)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::snprintf(out, cap, "%s", dir.c_str());

	return RPCS3_IOS_STATUS_OK;
}

// Loads go through boot_with, so every boot keeps one Emu.BootGame call and one set of checks.
Rpcs3IOS_Status Rpcs3IOS_LoadState(const char* state_path, const char* game_boot_path, const char* config_path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_LoadState");
	}

	if (state_path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string state{state_path};

	if (state.empty() || !fs::is_file(state) || !under_savestates_root(state))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	Rpcs3IOS_Status status = RPCS3_IOS_STATUS_WRONG_STATE;
	const system_state now = Emu.GetStatus(false);

	if (now == system_state::running || now == system_state::paused)
	{
		if (const Rpcs3IOS_Status halted = halt_status("Rpcs3IOS_LoadState"); halted != RPCS3_IOS_STATUS_OK)
		{
			return halted;
		}

		// The record first, then the core's used config, then a config rebuilt from the caller's paths; the core's default mode would drop both.
		const std::string title = Emu.GetTitleID();
		std::string cfg = restart_config(title);
		if (cfg.empty() && game_boot_path != nullptr && config_path != nullptr && fs::exists(game_boot_path))
		{
			cfg = title_boot_config(game_boot_path, config_path);
		}

		auto token = std::make_shared<int>(0);
		const std::weak_ptr<int> alive = token;
		const auto result = std::make_shared<kill_result>();

		// Boot only once the kill finished, because booting straight after a shutdown races the previous VM coming down.
		Emu.CallFromMainThread([token = std::move(token), result]() mutable
		{
			Emu.after_kill_callback = [token = std::move(token), result]()
			{
				result->status.store(RPCS3_IOS_STATUS_OK);
			};

			Emu.SetContinuousMode(true);
			Emu.Kill(false);
		});

		status = await_kill_callback(alive, *result, 1200, RPCS3_IOS_STATUS_BOOT_FAILED, RPCS3_IOS_STATUS_BOOT_FAILED);

		if (status == RPCS3_IOS_STATUS_OK)
		{
			// `cfg` already carries the title's settings, and boot_with's own merge adds none to a state.
			status = boot_with(state.c_str(), cfg);
		}
	}
	else if (now == system_state::stopped)
	{
		// A state file resolves to no serial, so the title's settings come from the game's boot path.
		const std::string game = game_boot_path != nullptr ? game_boot_path : "";

		if (game.empty() || !fs::exists(game))
		{
			status = RPCS3_IOS_STATUS_PATH_REJECTED;
		}
		else
		{
			// Every other check (callbacks, a stop still tearing down, the JIT verdict) is boot_with's.
			const std::string config = title_boot_config(game, config_path != nullptr ? config_path : "");
			status = boot_with(state_path, config);
		}
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_LoadState finished with status %d.\n", static_cast<int>(status));

	return status;
}

// Polled from the input queue, because the save itself holds the bridge queue.
Rpcs3IOS_Status Rpcs3IOS_SaveStateProgress(uint64_t* bytes, int32_t* finalizing)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_SaveStateProgress");
	}

	if (bytes == nullptr || finalizing == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	std::uint64_t written = 0;
	bool ending = false;
	if (armsx3::bridge::read_save_progress(written, ending) != 0)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	*bytes = written;
	*finalizing = ending ? 1 : 0;
	return RPCS3_IOS_STATUS_OK;
}
