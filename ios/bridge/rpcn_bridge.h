// SPDX-License-Identifier: GPL-2.0-only

#pragma once

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

#include "rpcs3_ios_abi.h"

#include "Emu/NP/rpcn_types.h"
#include "util/types.hpp"

namespace armsx3::bridge::rpcn
{
	// Never written to rpcn.yml and never logged; empty strings empty the slot.
	void set_secret(std::string_view secret, std::string_view token);

	// PBKDF2-HMAC-SHA3-256 as RPCS3's settings dialog derives it, as upper-case hex; "" on failure.
	std::string derive_password(std::string_view typed);

	// By name, with no default arm, so a new core enumerator stops the build.
	void map_state(::rpcn::rpcn_state state, Rpcs3IOS_RpcnResult& result);
	void map_error(::rpcn::ErrorType error, Rpcs3IOS_RpcnResult& result);

	// Each form is tried on a fresh client until one is not a refused password; on failure the previous account is put back.
	bool sign_in(std::string_view npid, std::string_view password, std::string_view token, std::size_t max_form,
		std::string& accepted, Rpcs3IOS_RpcnResult& result);

	void sign_out();

	// Says whether a secret and a token are set, never either one.
	std::string config_json(std::string (*quote)(std::string_view));

	// Never creates a client, reloads rpcn.yml or logs, so it can be polled.
	std::string status_json(std::string (*quote)(std::string_view));

	// Pure and free of core types so the host test can compile it alone; a failed client mid-boot is still signing in.
	inline int32_t session_code(bool client, bool authentified, int32_t state, int32_t boot_psn, bool psn_online)
	{
		if (!client)
		{
			return 0;
		}
		if (authentified)
		{
			return 2;
		}
		if (state == RPCS3_IOS_RPCN_STATE_OK || (boot_psn == 2 && psn_online))
		{
			return 1;
		}
		return 3;
	}

	// Set by the core's fatal line when a game needs RPCN under PSN status Simulated; cleared before every boot.
	void install_simulated_latch();
	void on_boot();
	bool simulated_needs_rpcn();

	// Writes nothing to rpcn.yml: Swift carries the details to Activate in memory.
	void create_account(std::string_view npid, std::string_view password, std::string_view email, Rpcs3IOS_RpcnResult& result);

	// Each on a fresh, connected client that is never signed in, and none writes rpcn.yml.
	void resend_token(std::string_view npid, std::string_view password, Rpcs3IOS_RpcnResult& result);
	void send_reset_token(std::string_view npid, std::string_view email, Rpcs3IOS_RpcnResult& result);
	void reset_password(std::string_view npid, std::string_view code, std::string_view password, Rpcs3IOS_RpcnResult& result);

	// Opens and closes pair one to one; a failed open holds nothing, so the next open tries again.
	void friends_session(bool open, Rpcs3IOS_RpcnResult& result);

	// Never creates a client and logs nothing, so it can be polled.
	std::string friends_json(std::string (*quote)(std::string_view));

	// RPCN friends are mutual adds, so accepting is an add and declining, withdrawing and removing are a remove.
	Rpcs3IOS_Status add_friend(std::string_view npid, Rpcs3IOS_RpcnResult& result);
	Rpcs3IOS_Status remove_friend(std::string_view npid, Rpcs3IOS_RpcnResult& result);

	// An invite handed to the wrong game is used up for nothing, so both calls re-check the ID against the running game.
	std::string invites_json(std::string (*quote)(std::string_view));
	Rpcs3IOS_Status accept_invite(uint64_t id);
	Rpcs3IOS_Status decline_invite(uint64_t id);

	// Each change ends with g_cfg_rpcn.save(), which the core's add_host and del_host leave to the caller.
	Rpcs3IOS_Status add_host(std::string_view name, std::string_view host);
	Rpcs3IOS_Status del_host(std::string_view name, std::string_view host);
	Rpcs3IOS_Status reset_hosts();
	Rpcs3IOS_Status select_host(std::string_view host);

	// delete_trophies uses only the held signed-in session, so no anonymous request is ever sent.
	void set_ipv6(bool on);
	Rpcs3IOS_Status delete_trophies(Rpcs3IOS_RpcnResult& result);

	// avatar_select refuses a path outside the user's home, the shared PSN avatar folder and dev_flash.
	std::string avatar_list(std::string (*quote)(std::string_view));
	std::string avatar_current(std::string (*quote)(std::string_view));
	Rpcs3IOS_Status avatar_select(std::string_view path, const char*& kind);

	// A picture is named after its contents because sceNp caches converted avatars by path for the session.
	Rpcs3IOS_Status avatar_image(std::string_view path, std::vector<u8>& png);
	Rpcs3IOS_Status avatar_use_image(const u8* bytes, std::size_t size);
	Rpcs3IOS_Status avatar_use_default();
}
