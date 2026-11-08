// SPDX-License-Identifier: GPL-2.0-only
// The state and helpers the ABI parts share, and the main-thread gate.

#include "rpcs3_ios_abi_internal.h"

namespace armsx3::bridge::abi_internal
{
	// Lets a call made before Rpcs3IOS_Initialize get NOT_INITIALIZED instead of an abort inside the loader.
	std::atomic<bool> g_initialized{false};
	std::atomic<bool> g_callbacks_installed{false};

	// Set by Rpcs3IOS_SetPressureModifier, read by every pad call, cleared by every accepted boot.
	std::atomic<bool> g_pressure_modifier{false};

	// Read at every pad_thread::Init, so a port's state outlives the reset each boot does.
	std::array<std::atomic<bool>, CELL_PAD_MAX_PORT_NUM> g_port_connected{};

	// The diagnostic is flushed first, because the debug branch is about to abort.
	Rpcs3IOS_Status refuse_main_thread(const char* entry_point)
	{
		utils::stderr_witness_printf(
			"ARMSX3: %s was called on the main thread. Emulator lifecycle calls hang the UI "
			"thread; on a device that ends as a watchdog kill with no stack. Call it from a "
			"worker thread.\n",
			entry_point);

#ifdef NDEBUG
		return RPCS3_IOS_STATUS_CALLED_ON_MAIN_THREAD;
#else
		// A debug build aborts, so a main-thread call cannot ship.
		std::abort();
#endif
	}

	// Why a teardown would wait forever, or OK: a full JIT pool parks threads and a dead renderer holds its locks.
	Rpcs3IOS_Status halt_reason()
	{
		// No state gate: the parked threads leave the core's state reading whatever it was.
		if (utils::jit_pool_full_reason() != 0)
			return RPCS3_IOS_STATUS_JIT_EXHAUSTED;

		// Only these states keep the RSX thread alive. shortcut: a death during ready or starting is missed; upgrade with a core-owned flag.
		const system_state now = Emu.GetStatus(false);
		if (now != system_state::running && now != system_state::paused && now != system_state::frozen)
			return RPCS3_IOS_STATUS_OK;

		if (!armsx3::bridge::current_host_services().rsx_thread_died || !armsx3::bridge::current_host_services().rsx_thread_died())
			return RPCS3_IOS_STATUS_OK;

		return RPCS3_IOS_STATUS_RENDERER_DIED;
	}

	// The counts when this boot began, so an earlier session's fatal or watchdog is not this one's.
	std::atomic<unsigned long long> g_fatal_baseline{0};
	std::atomic<unsigned long long> g_watchdog_baseline{0};

	// halt_reason() for the three kill sites, logging one line when it refuses.
	Rpcs3IOS_Status halt_status(const char* entry_point)
	{
		const Rpcs3IOS_Status status = halt_reason();

		if (status != RPCS3_IOS_STATUS_OK)
		{
			if (status == RPCS3_IOS_STATUS_JIT_EXHAUSTED)
				sys_log.error("%s: a JIT pool is full and the threads that needed it are parked; stopping, saving or loading would wait on them forever", entry_point);
			else
				sys_log.error("%s: the RSX thread died of a fatal error; tearing down would wait forever on locks it still holds", entry_point);
			logs::listener::sync_all();
		}

		return status;
	}

	// A stored file is untrusted, so every pair resolves: the pair, else the class's first product, else the DualShock 3.
	input::product_info stored_product(u32 class_id, u16 vendor_id, u16 product_id)
	{
		const std::vector<input::product_info> products = input::get_products_by_class(static_cast<int>(class_id));
		for (const input::product_info& product : products)
		{
			if (product.vendor_id == vendor_id && product.product_id == product_id)
			{
				return product;
			}
		}
		return products.empty() ? input::get_product_info(input::product_type::playstation_3_controller) : products.front();
	}

	// Read from the dual map's report and never probed, because executing the executable half to find out is the kill itself.
	armsx3::jit_state::verdict current_jit_verdict()
	{
#if TARGET_OS_IPHONE
		utils::jit_dual_map_report r{};
		utils::jit_dual_map_report_pool(utils::jit_pool::asmjit_arena, &r);
		return armsx3::jit_state::decide({true, r.block_size != 0, r.enabler_reason, r.handshake_performed});
#else
		return armsx3::jit_state::decide({false, false, nullptr, 0});
#endif
	}

	// The alphabet has no `.` or `/`, so `.`, `..` and separators never pass.
	bool serial_component_ok(const std::string& serial)
	{
		return !serial.empty() && serial.size() <= 32 && std::all_of(serial.begin(), serial.end(), [](char c)
		{
			return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
		});
	}

	std::string game_dir(const std::string& serial)
	{
		return rpcs3::utils::get_hdd0_dir() + "game/" + serial;
	}
	std::string patch_json_quote(std::string_view v)
	{
		std::string out = "\"";
		for (const char c : v)
		{
			switch (c)
			{
			case '"': out += "\\\""; break;
			case '\\': out += "\\\\"; break;
			case '\n': out += "\\n"; break;
			case '\r': out += "\\r"; break;
			case '\t': out += "\\t"; break;
			default:
				if (static_cast<unsigned char>(c) < 0x20)
				{
					char buf[8];
					std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(static_cast<unsigned char>(c)));
					out += buf;
				}
				else
				{
					out += c;
				}
			}
		}
		return out + "\"";
	}

	Rpcs3IOS_Status finished_status(const char* entry_point, Rpcs3IOS_Status status)
	{
		utils::stderr_witness_printf("ARMSX3: %s finished with status %d.\n", entry_point, static_cast<int>(status));
		return status;
	}
}
