// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{
	// Android's installPup on the core's own hash check, decrypter and extractor, with /dev_flash mounted explicitly.
	Rpcs3IOS_Status install_pup(const std::string& pup_path, Rpcs3IOS_FirmwareProgress progress, void* context)
	{
		pup_object pup(fs::file{pup_path});

		// A hash mismatch and every structural error are one status: the file is refused and nothing was written.
		if (static_cast<pup_error>(pup) != pup_error::ok)
		{
			return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
		}

		fs::file update_files_f = pup.get_file(0x300);

		if (!update_files_f || update_files_f.size() == 0)
		{
			return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
		}

		tar_object update_files(update_files_f);

		std::vector<std::string> names = update_files.get_filenames();
		names.erase(std::remove_if(names.begin(), names.end(),
			[](const std::string& s) { return !s.starts_with("dev_flash_"); }), names.end());

		std::string version;

		if (fs::file version_f = pup.get_file(0x100))
		{
			version = version_f.to_string();
		}

		if (const usz nl = version.find('\n'); nl != std::string::npos)
		{
			version.erase(nl);
		}

		if (names.empty() || version.empty())
		{
			return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
		}

		// Extracted into a staging folder and swapped in with two renames, so a failure leaves the installed firmware alone.
		const std::string live = [] {
			std::string path = g_cfg_vfs.get_dev_flash();
			while (path.size() > 1 && path.back() == '/')
			{
				path.pop_back();
			}
			return path;
		}();
		const std::string staging = live + ".installing";
		const std::string previous = live + ".previous";

		// A process killed between the two renames left no firmware in place, so the old one goes back first.
		if (!fs::is_dir(live) && fs::is_dir(previous))
		{
			fs::rename(previous, live, false);
		}
		fs::remove_all(staging, true, true);
		if (fs::is_dir(live))
		{
			fs::remove_all(previous, true, true);
		}

		// The mount tar_object::extract() resolves through, pointed at the staging folder as upstream's installer does.
		if (!fs::create_path(staging) || !vfs::mount("/dev_flash", staging + "/"))
		{
			fs::remove_all(staging, true, true);
			vfs::mount("/dev_flash", g_cfg_vfs.get_dev_flash());
			return RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED;
		}

		const u32 total = static_cast<u32>(names.size());
		u32 done = 0;

		// Every return inside goes back through the cleanup and swap below.
		const auto stage = [&]() -> Rpcs3IOS_Status
		{
			for (const std::string& name : names)
			{
				auto stream = update_files.get_file(name);

				if (!stream)
				{
					return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
				}

				if (stream->m_file_handler)
				{
					// Forcefully read all the data, as both upstream installers do.
					stream->m_file_handler->handle_file_op(*stream, 0, stream->get_size(umax), nullptr);
				}

				fs::file update_file = fs::make_stream(std::move(stream->data));

				SCEDecrypter decrypter(update_file);

				if (!decrypter.LoadHeaders() || !decrypter.LoadMetadata(SCEPKG_ERK, SCEPKG_RIV) || !decrypter.DecryptData())
				{
					return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
				}

				std::vector<fs::file> parts = decrypter.MakeFile();

				if (parts.size() < 3)
				{
					return RPCS3_IOS_STATUS_FIRMWARE_INVALID;
				}

				tar_object dev_flash_tar(parts[2]);

				// The file passed its hash check and decrypted, so a failure here is the destination's.
				if (!dev_flash_tar.extract())
				{
					return RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED;
				}

				if (progress != nullptr)
				{
					progress(context, ++done, total);
				}
			}

			return RPCS3_IOS_STATUS_OK;
		};

		Rpcs3IOS_Status status = stage();

		if (status == RPCS3_IOS_STATUS_OK)
		{
			const bool had = fs::is_dir(live);

			if (had && !fs::rename(live, previous, false))
			{
				status = RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED;
			}
			else if (!fs::rename(staging, live, false))
			{
				if (had)
				{
					fs::rename(previous, live, false);
				}
				status = RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED;
			}
		}

		// The old tree is deleted only once a tree is in place again.
		fs::remove_all(staging, true, true);
		if (fs::is_dir(live))
		{
			fs::remove_all(previous, true, true);
		}

		// A stop's g_fxo reset drops the whole vfs table, so the real dev_flash is mounted again whatever happened.
		if (!vfs::mount("/dev_flash", g_cfg_vfs.get_dev_flash()) && status == RPCS3_IOS_STATUS_OK)
		{
			status = RPCS3_IOS_STATUS_FIRMWARE_WRITE_FAILED;
		}

		return status;
	}
	// Mapped by name with no default arm, so a new core enumerator is a -Wswitch warning, not a wrong status.
	Rpcs3IOS_Status pkg_status(package_install_result::error_type error)
	{
		switch (error)
		{
		case package_install_result::error_type::no_error:
			return RPCS3_IOS_STATUS_OK;
		case package_install_result::error_type::app_version:
			return RPCS3_IOS_STATUS_PKG_UPDATE_MISMATCH;
		case package_install_result::error_type::other:
			return RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
		}

		return RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
	}

	// Android's installPkg on the core's own package_reader and extract_data, with readers built from paths.
	Rpcs3IOS_Status install_pkgs(const std::vector<std::string>& files, Rpcs3IOS_InstallProgress progress,
		void* context, char* out_title_id, uint32_t cap)
	{
		std::deque<package_reader> readers;

		for (const std::string& file : files)
		{
			const package_reader& reader = readers.emplace_back(file);

			if (!reader.is_valid())
			{
				return RPCS3_IOS_STATUS_PKG_INVALID;
			}
		}

		std::deque<std::string> bootable_paths;
		package_install_result result{};
		std::atomic<bool> finished{false};

		named_thread worker("PKG Installer", [&]()
		{
			result = package_reader::extract_data(readers, bootable_paths, false);
			finished.store(true, std::memory_order_release);
		});

		// The reader in progress is the first one not yet complete; extract_data goes in order.
		std::size_t last_index = readers.size();
		int last_permille = -1;

		const auto report = [&]()
		{
			if (progress == nullptr)
			{
				return;
			}

			std::size_t index = readers.size() - 1;
			for (std::size_t i = 0; i < readers.size(); i++)
			{
				if (readers[i].get_progress(1000) < 1000)
				{
					index = i;
					break;
				}
			}

			const int permille = readers[index].get_progress(1000);

			if (index != last_index || permille != last_permille)
			{
				last_index = index;
				last_permille = permille;
				progress(context, static_cast<uint32_t>(index), static_cast<uint32_t>(permille));
			}
		};

		while (!finished.load(std::memory_order_acquire))
		{
			report();
			usleep(100 * 1000);
		}

		static_cast<void>(worker());
		report();

		const Rpcs3IOS_Status status = pkg_status(result.error);

		if (status == RPCS3_IOS_STATUS_OK)
		{
			// The first package's PARAM.SFO, else the serial inside its header's content id.
			std::string id{psf::get_string(readers.front().get_psf(), "TITLE_ID", "")};

			if (id.empty())
			{
				const char* const content = readers.front().get_header().title_id;
				const std::string content_id(content, strnlen(content, sizeof(readers.front().get_header().title_id)));
				id = content_id.size() >= 16 ? content_id.substr(7, 9) : std::string{};
			}

			std::snprintf(out_title_id, cap, "%s", id.c_str());
		}

		return status;
	}

	// The core reads a licence by path, so it must never see one half written.
	bool write_licence(const std::string& dest, const std::vector<u8>& bytes)
	{
		const std::string tmp = dest + ".armsx3-tmp";

		if (!fs::create_path(fs::get_parent_dir(dest)) || !fs::write_file(tmp, fs::rewrite, bytes) || !fs::rename(tmp, dest, true))
		{
			fs::remove_file(tmp);
			return false;
		}

		return true;
	}

	// A RAP: 16 bytes of key. `serial` is empty when no game was chosen.
	Rpcs3IOS_Status install_rap(const std::string& path, const std::vector<u8>& key, const std::string& serial)
	{
		const std::string eboot = game_dir(serial) + "/USRDIR/EBOOT.BIN";
		std::string content_id;

		if (!serial.empty())
		{
			NPD_HEADER npd{};

			if (!fs::is_file(eboot))
			{
				return RPCS3_IOS_STATUS_PATH_REJECTED;
			}

			// A game with no NPD header needs no RAP.
			if (!get_npdrm_self_header(fs::file{eboot}, npd))
			{
				return RPCS3_IOS_STATUS_LICENCE_INVALID;
			}

			content_id = npd.get_content_id();
		}
		else
		{
			// No game: the name must be `<content id>.<anything>`, by the core's own rule.
			const std::string leaf = path.substr(path.find_last_of('/') + 1);
			const std::string stem = leaf.substr(0, leaf.rfind('.'));

			if (rpcs3::utils::get_rap_file_path(stem).empty())
			{
				return RPCS3_IOS_STATUS_LICENCE_INVALID;
			}

			content_id = stem;
		}

		// Where the core will look: an existing RAP for this id, else `home/<user>/exdata/<id>.rap`.
		const std::string dest = rpcs3::utils::get_rap_file_path(content_id);

		if (dest.empty())
		{
			return RPCS3_IOS_STATUS_LICENCE_INVALID;
		}

		const fs::file old{dest};
		const bool had_previous = static_cast<bool>(old);
		const std::vector<u8> previous = had_previous ? old.to_vector<u8>() : std::vector<u8>{};

		if (!write_licence(dest, key))
		{
			return RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
		}

		// A key that does not unlock the game is not left where the core reads it.
		if (!serial.empty())
		{
			if (!decrypt_self(fs::file{eboot}))
			{
				if (!had_previous || !write_licence(dest, previous))
				{
					fs::remove_file(dest);
				}

				return RPCS3_IOS_STATUS_LICENCE_INVALID;
			}
		}

		return RPCS3_IOS_STATUS_OK;
	}

	// An EDAT: named by its own NPD header.
	Rpcs3IOS_Status install_edat(const std::vector<u8>& bytes, const std::string& serial)
	{
		NPD_HEADER npd{};
		std::memcpy(&npd, bytes.data(), sizeof(npd));

		if (!serial.empty())
		{
			const std::string sfo = game_dir(serial) + "/PARAM.SFO";

			if (!fs::is_file(sfo))
			{
				return RPCS3_IOS_STATUS_PATH_REJECTED;
			}

			if (psf::get_string(psf::load_object(sfo), "CONTENT_ID") != npd.get_content_id())
			{
				return RPCS3_IOS_STATUS_LICENCE_INVALID;
			}
		}

		const std::string content_id = npd.get_content_id();

		// The core's content-id rule, so a header cannot name a path.
		if (rpcs3::utils::get_rap_file_path(content_id).empty())
		{
			return RPCS3_IOS_STATUS_LICENCE_INVALID;
		}

		const std::string dest = rpcs3::utils::get_hdd0_dir() + "home/" + Emu.GetUsr() + "/exdata/" + content_id + ".edat";

		return write_licence(dest, bytes) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
	}

	Rpcs3IOS_Status install_licence(const std::string& path, const std::string& serial)
	{
		if (!serial.empty() && !fs::is_dir(game_dir(serial)))
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}

		const fs::file file{path};

		if (!file)
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}

		const std::vector<u8> bytes = file.to_vector<u8>();

		if (bytes.size() == 16)
		{
			return install_rap(path, bytes, serial);
		}

		if (bytes.size() >= sizeof(NPD_HEADER) && std::memcmp(bytes.data(), "NPD\0", 4) == 0)
		{
			return install_edat(bytes, serial);
		}

		return RPCS3_IOS_STATUS_LICENCE_INVALID;
	}
}


Rpcs3IOS_Status Rpcs3IOS_InstallFirmware(const char* pup_path, Rpcs3IOS_FirmwareProgress progress, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InstallFirmware");
	}

	if (pup_path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string pup{pup_path};

	if (pup.empty() || !fs::exists(pup))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	// The destination comes from the vfs configuration `Emu.Init()` loads.
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// dev_flash holds the running title's system modules, so an install never overlaps a boot.
	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const Rpcs3IOS_Status status = install_pup(pup, progress, context);

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InstallFirmware finished with status %d.\n", static_cast<int>(status));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_GetFirmwareVersion(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetFirmwareVersion");
	}

	if (out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const std::string version = utils::get_firmware_version();
	std::snprintf(out, cap, "%s", version.c_str());

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_InstallPkg(const char* const* paths, uint32_t count, Rpcs3IOS_InstallProgress progress,
	void* context, char* out_title_id, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InstallPkg");
	}

	if (paths == nullptr || out_title_id == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// 16 bytes holds a 9-character serial and its NUL; 16 packages is more than any game, update and DLC set.
	if (count == 0 || count > 16 || cap < 16)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	out_title_id[0] = '\0';

	std::vector<std::string> files;

	for (uint32_t i = 0; i < count; i++)
	{
		if (paths[i] == nullptr || paths[i][0] == '\0' || !fs::is_file(paths[i]))
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}

		files.emplace_back(paths[i]);
	}

	// The destination comes from the vfs configuration `Emu.Init()` loads.
	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const Rpcs3IOS_Status status = install_pkgs(files, progress, context, out_title_id, cap);

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InstallPkg finished with status %d.\n", static_cast<int>(status));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_InstallLicence(const char* path, const char* serial)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_InstallLicence");
	}

	if (path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (path[0] == '\0' || !fs::is_file(path))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	// The serial becomes a path component.
	if (serial != nullptr && !serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const Rpcs3IOS_Status status = install_licence(path, serial != nullptr ? serial : "");

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_InstallLicence finished with status %d.\n", static_cast<int>(status));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_ProbeLicence(const char* serial, int32_t* out_locked, char* out_content_id, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_ProbeLicence");
	}

	if (serial == nullptr || out_locked == nullptr || out_content_id == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// 49 bytes holds the NPD header's 48-byte content id field and a NUL.
	if (!serial_component_ok(serial) || cap < 49)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	out_content_id[0] = '\0';

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	Rpcs3IOS_Status status = RPCS3_IOS_STATUS_PATH_REJECTED;
	int32_t locked = 0;
	s32 licence = -1;
	const std::string eboot = game_dir(serial) + "/USRDIR/EBOOT.BIN";

	if (fs::is_file(eboot))
	{
		const fs::file file{eboot};

		// The core decides, and only for a SELF; a name is never compared with an id.
		if (file && file.size() >= 4 && file.read<u32>() == "SCE\0"_u32)
		{
			NPD_HEADER npd{};
			const bool npdrm = get_npdrm_self_header(file, npd);
			const std::string id = npdrm ? npd.get_content_id() : std::string{};
			std::snprintf(out_content_id, cap, "%s", id.c_str());
			licence = npdrm ? npd.license : -1;
			// 2: licence 3 needs no RAP, a SELF without an NPD header needs none, and the core knows no other type.
			locked = decrypt_self(file) ? 0 : (licence == 1 || licence == 2) ? 1 : 2;
		}

		*out_locked = locked;
		status = RPCS3_IOS_STATUS_OK;
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_ProbeLicence finished with status %d, outcome %d, licence type %d.\n", static_cast<int>(status), static_cast<int>(locked), static_cast<int>(licence));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_GetUserDir(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetUserDir");
	}

	if (out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	out[0] = '\0';

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const std::string dir = rpcs3::utils::get_hdd0_dir() + "home/" + Emu.GetUsr() + "/";
	if (dir.size() >= cap)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::snprintf(out, cap, "%s", dir.c_str());

	return RPCS3_IOS_STATUS_OK;
}

// Takes a serial and builds the folder itself, because Android's path prefix test lets game/../ through.
Rpcs3IOS_Status Rpcs3IOS_UninstallGame(const char* serial)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_UninstallGame");
	}

	if (serial == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The serial becomes the folder's last component, and nothing else does: no `.`, no `/`.
	if (!serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	const std::string dir = game_dir(serial);

	// fs::remove_all follows a link and empties its target, so a folder that is or holds a link is refused.
	std::error_code ec;
	bool contained = !std::filesystem::is_symlink(dir, ec) && !ec && fs::is_file(dir + "/PARAM.SFO");
	for (std::filesystem::recursive_directory_iterator it(dir, ec), end; contained && !ec && it != end; it.increment(ec))
	{
		contained = !it->is_symlink(ec);
	}

	Rpcs3IOS_Status status = RPCS3_IOS_STATUS_PATH_REJECTED;
	if (contained && !ec)
	{
		status = fs::remove_all(dir) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_UninstallGame finished with status %d.\n", static_cast<int>(status));

	return status;
}

// A disc title has no dev_hdd0/game folder, but its PPU, SPU and shader caches all live under cache/<serial>/.
Rpcs3IOS_Status Rpcs3IOS_ClearGameCache(const char* serial)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_ClearGameCache");
	}

	if (serial == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The serial becomes the folder's last component, and nothing else does: no `.`, no `/`.
	if (!serial_component_ok(serial))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	// Nothing is removed while a game runs: its objects may be mapped or being written.
	if (Emu.GetStatus(false) != system_state::stopped)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	// shortcut: caches shared between titles stay warm; upgrade if a test needs them cold as well.
	const std::string dir = rpcs3::utils::get_cache_dir_by_serial(serial);

	// symlink_status does not follow a link; a title's folder holds a ppu- directory, the others beside it do not.
	std::error_code ec;
	const std::filesystem::file_type kind = std::filesystem::symlink_status(dir, ec).type();

	Rpcs3IOS_Status status = RPCS3_IOS_STATUS_OK;
	if (kind != std::filesystem::file_type::not_found)
	{
		bool contained = !ec && kind == std::filesystem::file_type::directory;
		bool titled = false;
		for (std::filesystem::recursive_directory_iterator it(dir, ec), end; contained && !ec && it != end; it.increment(ec))
		{
			contained = !it->is_symlink(ec);
			if (contained && it.depth() == 0 && it->is_directory(ec) && it->path().filename().string().compare(0, 4, "ppu-") == 0)
			{
				titled = true;
			}
		}

		// shortcut: a link swapped in between check and removal is missed; nothing else writes the cache while stopped.
		status = RPCS3_IOS_STATUS_PATH_REJECTED;
		if (contained && titled && !ec)
		{
			status = fs::remove_all(dir) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_INSTALL_WRITE_FAILED;
		}
	}

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_ClearGameCache finished with status %d.\n", static_cast<int>(status));

	return status;
}

Rpcs3IOS_Status Rpcs3IOS_GetHdd0Dir(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetHdd0Dir");
	}

	if (out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	out[0] = '\0';

	if (!g_initialized.load())
	{
		return RPCS3_IOS_STATUS_NOT_INITIALIZED;
	}

	const std::string dir = rpcs3::utils::get_hdd0_dir();
	if (dir.size() >= cap)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::snprintf(out, cap, "%s", dir.c_str());

	return RPCS3_IOS_STATUS_OK;
}
