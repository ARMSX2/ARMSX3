// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace armsx3::bridge::abi_internal
{
	// The serial the core will read for this content, resolved as Emulator::AddGameToYml does; empty when anything is missing.
	std::string boot_serial(const std::string& content)
	{
		std::string elf = content;
		if (fs::is_dir(content) && Emulator::GetElfPathFromDir(elf, content) != game_boot_result::no_errors)
		{
			return {};
		}

		if (is_iso_file(elf))
		{
			iso_archive archive(elf);
			return archive.is_valid() ? std::string(psf::get_string(archive.open_psf("PS3_GAME/PARAM.SFO"), "TITLE_ID")) : std::string{};
		}

		const std::string sfo_dir = rpcs3::utils::get_sfo_dir_from_game_path(fs::get_parent_dir(fs::get_parent_dir(elf)));
		return std::string(psf::get_string(psf::load_object(sfo_dir + "/PARAM.SFO"), "TITLE_ID"));
	}

	// Walks a throwaway cfg_root so a key or value the core would refuse never reaches the override.
	bool core_accepts(cfg_root& probe, const std::vector<std::string>& path, const armsx3::bridge::title_config::item& leaf)
	{
		cfg::_base* node = &probe;
		for (const std::string& name : path)
		{
			if (node->get_type() != cfg::type::node)
			{
				return false;
			}

			const auto& nodes = static_cast<cfg::node*>(node)->get_nodes();
			const auto it = std::find_if(nodes.begin(), nodes.end(), [&](cfg::_base* n) { return n->get_name() == name; });
			if (it == nodes.end())
			{
				return false;
			}
			node = *it;
		}

		if (leaf.k == armsx3::bridge::title_config::item::kind::list)
		{
			return node->get_type() == cfg::type::set && node->from_list(std::vector<std::string>(leaf.entries));
		}

		return node->get_type() != cfg::type::node && node->get_type() != cfg::type::set && node->from_string(leaf.value);
	}

	// The app fetches the config database into Application Support; HOME resolves the same relative path on device and Mac.
	std::string cached_config_db_path()
	{
		const char* const home = std::getenv("HOME");
		return home != nullptr && *home != '\0' ? std::string{home} + "/Library/Application Support/armsx3/rpcs3-config-db.json" : std::string{};
	}

	// The same cached copy, deny lists and core_accepts every boot uses, so the dump shows exactly what a boot adds.
	armsx3::bridge::settings::db_layer_view db_layer(const std::string& serial)
	{
		armsx3::bridge::settings::db_layer_view view;
		std::string text;
		if (const fs::file db{cached_config_db_path()})
		{
			text = db.to_string();
		}
		const std::string entry = armsx3::bridge::title_config::lookup(text, serial);
		// On the heap: cfg_root is large, and this runs on the façade's GCD queue thread.
		const auto probe = std::make_unique<cfg_root>();
		const auto merged = armsx3::bridge::title_config::title_layer(entry, serial,
			[&probe](const std::vector<std::string>& keys, const armsx3::bridge::title_config::item& leaf) { return core_accepts(*probe, keys, leaf); });
		view.entry_text = merged.added != 0 ? merged.text : std::string{};
		view.added = merged.added;
		view.fix_paths = armsx3::bridge::title_config::fix_paths(text);
		view.known = !entry.empty();
		return view;
	}
	std::string strip_layered_suffix(std::string config)
	{
		while (config.ends_with(layered_config_suffix))
		{
			config.resize(config.size() - layered_config_suffix.size());
		}
		return config;
	}

	std::string title_boot_config(const std::string& content, const std::string& config)
	{
		const std::string serial = boot_serial(content);
		const armsx3::bridge::settings::db_layer_view db = db_layer(serial);
		armsx3::bridge::settings::layer_counts counts;
		const std::string text = armsx3::bridge::settings::boot_text(serial, db, config, counts);

		// An already-layered config boots as is, because layering it again stacks suffixes or drops the layers it carries.
		const bool layered = strip_layered_suffix(config) != config;

		std::string path = config;
		// The low-memory clamps and the community-server redirects need the copy too.
		if (!config.empty() && !layered && (counts.global + counts.db + counts.game + counts.llvm_threads_clamped + counts.shader_interpreter_off + counts.early_cse_off != 0 || counts.redirects != 0) && fs::write_file(config + ".title.yml", fs::rewrite, text))
		{
			path = config + ".title.yml";
		}

		return path;
	}

	// The title and final config of the last boot the core accepted, so a restart boots the same thing.
	std::mutex g_boot_config_mutex;
	std::string g_boot_config_title;
	std::string g_boot_config;
	// The product file the boot used, never a `.title.yml`, so a settings write can rebuild the record.
	std::string g_boot_config_product;

	// Rebuilds the record from the layers after a settings write, only for the running title and under the record's lock.
	void refresh_boot_record()
	{
		std::lock_guard lock(g_boot_config_mutex);
		if (g_boot_config_title.empty() || g_boot_config_title != Emu.GetTitleID() || g_boot_config_product.empty())
		{
			return;
		}

		armsx3::bridge::settings::layer_counts counts;
		const std::string text = armsx3::bridge::settings::boot_text(g_boot_config_title, db_layer(g_boot_config_title),
			g_boot_config_product, counts);
		const std::string copy = g_boot_config_product + ".title.yml";
		if (counts.global + counts.db + counts.game == 0)
		{
			g_boot_config = g_boot_config_product;
		}
		else if (fs::write_file(copy, fs::rewrite, text))
		{
			g_boot_config = copy;
		}
	}

	// Every boot goes through here, so the bridge keeps one Emu.BootGame call and one set of checks.
	Rpcs3IOS_Status boot_with(const char* content_path, const std::string& product_config)
	{
		// No containment check: the caller is this process with the same file access, and the sandbox contains it on a device.
		if (content_path == nullptr)
		{
			return RPCS3_IOS_STATUS_NULL_ARGUMENT;
		}

		const std::string content{content_path};

		if (content.empty() || !fs::exists(content))
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}

		if (!g_initialized.load())
		{
			return RPCS3_IOS_STATUS_NOT_INITIALIZED;
		}

		if (!g_callbacks_installed.load())
		{
			return RPCS3_IOS_STATUS_CALLBACKS_NOT_INSTALLED;
		}

		// Rpcs3IOS_Stop returns while teardown is still running, and the defaulted GetStatus reports stopping as stopped.
		if (Emu.GetStatus(false) == system_state::stopping)
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}

		// The kill comes on the first execution after a boot, so a process nothing prepared returns JIT_UNAVAILABLE instead.
		if (!current_jit_verdict().on)
		{
			return RPCS3_IOS_STATUS_JIT_UNAVAILABLE;
		}

		// The product file with the title's layers rides inside the override, because config_override skips the core's database step.
		const std::string config = title_boot_config(content, product_config);
		const cfg_mode mode = config.empty() ? cfg_mode::custom : cfg_mode::config_override;

		Emu.SetForceBoot(true);

		// A new boot starts with the Simulated latch clear, so the banner names this boot's game.
		armsx3::bridge::rpcn::on_boot();

		// The offers count only what happens from this boot on.
		g_fatal_baseline.store(armsx3::bridge::diag::fatal_thread_exits(), std::memory_order_relaxed);
		g_watchdog_baseline.store(armsx3::bridge::stop_no_response_reports(), std::memory_order_relaxed);

		// `direct` false, so a title directory resolves to its EBOOT instead of a cache-build scan.
		const game_boot_result result = Emu.BootGame(content, "", false, mode, config);

		if (result != game_boot_result::no_errors)
		{
			// The core's enumerator is not passed on, since a merge can renumber it; the reason is in the log.
			return RPCS3_IOS_STATUS_BOOT_FAILED;
		}

		{
			std::lock_guard lock(g_boot_config_mutex);
			g_boot_config_title = Emu.GetTitleID();
			g_boot_config = config;
			// A restart boots the layered copy, so the product file is kept without its suffix.
			g_boot_config_product = strip_layered_suffix(product_config);
		}

		// A new game starts with full presses, whatever the last one held.
		g_pressure_modifier.store(false);

		// The pause owner hears of every boot through the on_run callback, so nothing is called here.

		return RPCS3_IOS_STATUS_OK;
	}
}

namespace
{

	// Each is set once per install and only while it still holds the core's default, so the player's own choice is kept.
	constexpr std::pair<std::string_view, std::string_view> k_seed_defaults[] = {
		{"Input/Output@@Keyboard", "Basic"}, // the hardware keyboard is the PS3's USB keyboard
		{"Audio@@Microphone Type", "Standard"}, // every game hears a microphone unless the player turns it off
		{"Audio@@Microphone Devices", "CoreAudio Default@@@@@@@@@"},
		{"Input/Output@@Camera", "Qt"}, // the device camera is the PS Eye unless access is denied
		{"Input/Output@@Camera type", "PS Eye"},
		// Shader interpreter off, Android's default: in cold boots on the Mac it was equal or better every time.
		{"Video@@Shader Mode", "Async Recompiler (multi-threaded)"},
	};

	// The kill sites never call this, so Stop on a frozen or fatal game still runs; atomics only, for the main thread.
	Rpcs3IOS_Status offer_reason()
	{
		const system_state now = Emu.GetStatus(false);

		// The core's guest access-violation path freezes the game this way (Emu.Pause(true)).
		if (now == system_state::frozen)
			return RPCS3_IOS_STATUS_GAME_FATAL;

		// A thread that died through thread_ctrl::emergency_exit leaves the state as it was.
		if ((now == system_state::running || now == system_state::paused) &&
			armsx3::bridge::diag::fatal_thread_exits() > g_fatal_baseline.load(std::memory_order_relaxed))
			return RPCS3_IOS_STATUS_GAME_FATAL;

		if (now == system_state::stopping &&
			armsx3::bridge::stop_no_response_reports() > g_watchdog_baseline.load(std::memory_order_relaxed))
			return RPCS3_IOS_STATUS_STOP_HUNG;

		return RPCS3_IOS_STATUS_OK;
	}

	// A pure-C entry point cannot take host_services, so they come from the process-wide slot the frontend fills first.
	void install_callbacks()
	{
		g_emu_callbacks = armsx3::bridge::make_callbacks(armsx3::bridge::current_host_services());

		g_callbacks_installed.store(true);
	}

	// Must not take pad::g_pad_mutex, which pad_thread::Init already holds when bindPadToDevice calls this.
	bool connect_virtual_pad(const std::shared_ptr<Pad>& pad)
	{
		// A port that exists is not a controller plugged into it.
		const u32 initial_status =
			pad->m_player_id < CELL_PAD_MAX_PORT_NUM && g_port_connected[pad->m_player_id].load() ? CELL_PAD_STATUS_CONNECTED : 0;

		// The DualShock 3 default reports vendor 0x054C and product 0x0268, the pair a real one reports.
		const cfg_player* player = pad->m_player_id < g_cfg_input.player.size() ? g_cfg_input.player[pad->m_player_id] : nullptr;
		const input::product_info product = player != nullptr
			? stored_product(static_cast<u32>(player->config.device_class_type.get()),
				static_cast<u16>(player->config.vendor_id.get()), static_cast<u16>(player->config.product_id.get()))
			: input::get_product_info(input::product_type::playstation_3_controller);
		pad->Init(initial_status, product.capabilites, CELL_PAD_DEV_TYPE_STANDARD, product.class_id, product.pclass_profile,
			product.vendor_id, product.product_id, 50);

		constexpr pad_button_code buttons[] = {
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_UP},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_DOWN},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_LEFT},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_RIGHT},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_CROSS},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_SQUARE},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_CIRCLE},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_TRIANGLE},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_L1},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_L2},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_L3},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_R1},
			{CELL_PAD_BTN_OFFSET_DIGITAL2, CELL_PAD_CTRL_R2},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_R3},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_START},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_SELECT},
			{CELL_PAD_BTN_OFFSET_DIGITAL1, CELL_PAD_CTRL_PS},
		};
		for (const pad_button_code& b : buttons)
		{
			pad->m_buttons.emplace_back(b.offset, std::vector<std::set<u32>>{}, b.code);
		}

		pad->m_sticks[0] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_X, {}, {});
		pad->m_sticks[1] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_Y, {}, {});
		pad->m_sticks[2] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_X, {}, {});
		pad->m_sticks[3] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_Y, {}, {});

		pad->m_sensors[0] = AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_X, 0, 0, 0, DEFAULT_MOTION_X);
		pad->m_sensors[1] = AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Y, 0, 0, 0, DEFAULT_MOTION_Y);
		pad->m_sensors[2] = AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Z, 0, 0, 0, DEFAULT_MOTION_Z);
		pad->m_sensors[3] = AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_G, 0, 0, 0, DEFAULT_MOTION_G);

		pad->m_vibrate_motors[0] = VibrateMotor(true);
		pad->m_vibrate_motors[1] = VibrateMotor(false);

		return true;
	}

	// The load comes first and the save is needed, because pad_thread::Init reloads g_cfg_input from disk.
	void register_virtual_pads()
	{
		virtual_pad_handler::set_on_connect_cb(connect_virtual_pad);
		g_cfg_input.load("", g_cfg_input_configs.default_config);
		for (cfg_player* player : g_cfg_input.player)
		{
			player->handler.set(pad_handler::virtual_pad);
			player->device.from_string("Virtual");
		}
		g_cfg_input.save("", g_cfg_input_configs.default_config);
	}
}


Rpcs3IOS_Status Rpcs3IOS_Initialize(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_Initialize");
	}

	// Armed before Emu.Init(), so nothing in this process reads a secret from rpcn.yml or saves one into it.
	rpcn_arm_memory_secrets();

	// The per-boot Simulated latch's log listener, added once; it stores one bit and logs nothing.
	armsx3::bridge::rpcn::install_simulated_latch();

	// The table goes in before Emu.Init(), whose resolve_path call would otherwise get upstream's identity default.
	install_callbacks();

	Emu.SetHasGui(false);

	// Without these, Emu.Init() asserts a graphics adapter and ApplySettings forces the Null renderer.
	Emu.SetSupportedRenderers({video_renderer::null, video_renderer::vulkan});
	Emu.SetDefaultRenderer(video_renderer::vulkan);
	Emu.SetDefaultGraphicsAdapter("Default");

	// The core looks icons up in the config folder first; replacing a real folder is iOS only, on the Mac it is the developer's.
	armsx3::bridge::link_icon_tree(fs::get_config_dir(), armsx3::bridge::executable_path(), TARGET_OS_IPHONE != 0);

	Emu.Init();

	// The touch pad is always player 1.
	g_port_connected[0].store(true);

	register_virtual_pads();

	// config.yml exists now, so the seeds can compare with the core's defaults.
	{
		std::vector<std::pair<std::string, std::string>> seeds;
		for (const auto& [path, value] : k_seed_defaults)
		{
			seeds.emplace_back(path, value);
		}
		armsx3::bridge::settings::seed_defaults(seeds);
	}

	g_initialized.store(true);

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_Boot(const char* content_path, const char* config_path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_Boot");
	}

	return boot_with(content_path, config_path == nullptr ? std::string{} : std::string{config_path});
}

Rpcs3IOS_Status Rpcs3IOS_GetState(int32_t* out_state)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetState");
	}

	if (out_state == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// The defaulted GetStatus() reports frozen as paused and stopping as stopped.
	const system_state state = Emu.GetStatus(false);

	// Mapped by name with no default arm, and -Wswitch is an error here so a new core state cannot slip by.
#pragma clang diagnostic push
#pragma clang diagnostic error "-Wswitch"
	switch (state)
	{
	case system_state::stopped: *out_state = RPCS3_IOS_STATE_STOPPED; break;
	case system_state::loading: *out_state = RPCS3_IOS_STATE_LOADING; break;
	case system_state::stopping: *out_state = RPCS3_IOS_STATE_STOPPING; break;
	case system_state::running: *out_state = RPCS3_IOS_STATE_RUNNING; break;
	case system_state::paused: *out_state = RPCS3_IOS_STATE_PAUSED; break;
	case system_state::frozen: *out_state = RPCS3_IOS_STATE_FROZEN; break;
	case system_state::ready: *out_state = RPCS3_IOS_STATE_READY; break;
	case system_state::starting: *out_state = RPCS3_IOS_STATE_STARTING; break;
	}
#pragma clang diagnostic pop

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetFrameCounters(uint64_t* out_guest_vblanks, uint64_t* out_guest_flips)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetFrameCounters");
	}

	if (out_guest_vblanks == nullptr || out_guest_flips == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_guest_vblanks = 0;
	*out_guest_flips = 0;

	// shortcut: a Kill starting between this check and the loads is outrun only by its joins; upgrade with core-published atomics.
	const system_state state = Emu.GetStatus(false);

	if (state != system_state::running && state != system_state::paused && state != system_state::frozen)
	{
		return RPCS3_IOS_STATUS_OK;
	}

	if (auto* rsx = g_fxo->try_get<rsx::thread>())
	{
		*out_guest_vblanks = rsx->display_ctrl().get_vblank_count();

		*out_guest_flips = rsx->display_ctrl().get_flip_index(); // a benign race: an aligned 64-bit load cannot tear
	}

	return RPCS3_IOS_STATUS_OK;
}

// No main-thread refusal: the app polls it on the main thread, because a bridge queue behind a parked boot must not hide a halt.
Rpcs3IOS_Status Rpcs3IOS_GetHaltStatus(int32_t* out_status)
{
	if (out_status == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	*out_status = halt_reason();

	if (*out_status == RPCS3_IOS_STATUS_OK)
	{
		*out_status = offer_reason();
	}

	return RPCS3_IOS_STATUS_OK;
}

// The dual map's header is included for iOS only, so the Mac reads 0.
Rpcs3IOS_Status Rpcs3IOS_TakeJitSecondPassOffer(int32_t* out_trigger)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_TakeJitSecondPassOffer");
	}

	if (out_trigger == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

#if TARGET_OS_IPHONE
	*out_trigger = utils::jit_second_pass_take_offer();
#else
	*out_trigger = 0;
#endif
	return RPCS3_IOS_STATUS_OK;
}

// Spawns the dual map's waiting thread and returns; it never waits itself.
Rpcs3IOS_Status Rpcs3IOS_StartJitSecondPass(int32_t trigger, int32_t opened)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_StartJitSecondPass");
	}

#if TARGET_OS_IPHONE
	return utils::jit_second_pass_start(trigger, opened != 0) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
#else
	static_cast<void>(trigger);
	static_cast<void>(opened);
	return RPCS3_IOS_STATUS_WRONG_STATE;
#endif
}

Rpcs3IOS_Status Rpcs3IOS_Stop(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_Stop");
	}

	// Emulator::Kill posts work through CallFromMainThread, so a host whose main thread does not drain that queue can block here.
	if (const Rpcs3IOS_Status halted = halt_status("Rpcs3IOS_Stop"); halted != RPCS3_IOS_STATUS_OK)
	{
		return halted;
	}

	Emu.Kill(false);

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_Pause(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_Pause");
	}

	// No resume message: its thread asks for a present every 33 ms while paused, and a backgrounded app may not submit GPU work.
	if (!Emu.Pause(false, false))
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	// After the state flip, so the arming ticker cannot re-arm across the pause; the harness capture asks again after resume.
	g_user_asked_for_screenshot = false;

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_Resume(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_Resume");
	}

	// Emulator::Resume declines while a savestate or close is in flight, so the state is read again afterwards.
	if (Emu.GetStatus(false) != system_state::paused)
	{
		return RPCS3_IOS_STATUS_WRONG_STATE;
	}

	Emu.Resume();

	return Emu.GetStatus(false) == system_state::paused ? RPCS3_IOS_STATUS_WRONG_STATE
		: RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetJitState(int32_t* out_state, char* out_reason, uint32_t reason_cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetJitState");
	}

	if (out_state == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	// Needs no Rpcs3IOS_Initialize: the report was filled at static initialisation.
	const armsx3::jit_state::verdict v = current_jit_verdict();
	*out_state = v.on ? RPCS3_IOS_JIT_ON : RPCS3_IOS_JIT_OFF;

	if (out_reason != nullptr && reason_cap > 0)
	{
		std::snprintf(out_reason, reason_cap, "%s", v.reason);
	}

	return RPCS3_IOS_STATUS_OK;
}

// The core's home-menu save; it logs a count only and rebuilds the boot record so a restart boots what was saved.
void armsx3::bridge::settings::on_save_emu_settings()
{
	if (Emu.IsStopped())
	{
		return;
	}

	const std::string title = Emu.GetTitleID();
	std::string product;
	{
		std::lock_guard lock(g_boot_config_mutex);
		if (g_boot_config_title == title)
		{
			product = g_boot_config_product;
		}
	}

	const std::size_t saved = armsx3::bridge::settings::save_running_title(db_layer(title), product);
	utils::stderr_witness_printf("ARMSX3: save_emu_settings saved %zu keys.\n", saved);
	if (saved != 0)
	{
		refresh_boot_record();
	}
}

std::string armsx3::bridge::title_config::leg_boot_config(const std::string& content, const std::string& config)
{
	return title_boot_config(content, config);
}
