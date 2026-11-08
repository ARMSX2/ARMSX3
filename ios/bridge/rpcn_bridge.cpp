// SPDX-License-Identifier: GPL-2.0-only
// RPCN and avatar logic behind the Rpcs3IOS_Rpcn* entry points; nothing here logs a secret, a token, a username or a server.

#include "rpcn_bridge.h"
#include "rpcn_derive.h"
#include "callbacks.h"

#include "Emu/NP/rpcn_client.h"
#include "Emu/NP/rpcn_config.h"
#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/IdManager.h"
#include "Emu/NP/np_handler.h"
#include "util/logs.hpp"
#include "Emu/NP/np_avatar.h"
#include "Emu/system_utils.hpp"
#include "Emu/vfs_config.h"
#include "Utilities/File.h"
#include "Crypto/sha1.h"

#include <wolfssl/wolfcrypt/pwdbased.h>
#include <wolfssl/wolfcrypt/sha3.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <vector>

namespace armsx3::bridge::rpcn
{
	void set_secret(std::string_view secret, std::string_view token)
	{
		rpcn_set_memory_secrets(secret, token);
	}

	namespace
	{
		// Sign-in releases the held client first: a client keeps the first failure it met and answers every later wait with it.
		std::mutex s_holder_mutex;
		std::shared_ptr<::rpcn::rpcn_client> s_held;
		// release_held leaves it alone, so a list still showing after a sign-in signs in again at its next open.
		u32 s_friends_users = 0;
		std::mutex s_config_mutex;

		// Dropping the last owner runs the client's destructor, which terminates and joins its three threads.
		void release_held()
		{
			std::shared_ptr<::rpcn::rpcn_client> held;
			{
				std::lock_guard lock(s_holder_mutex);
				held = std::move(s_held);
			}
			held.reset();
		}

		// Connect first: the client's thread acts on a connection request only while disconnected.
		::rpcn::rpcn_state connect_and_authenticate(const std::shared_ptr<::rpcn::rpcn_client>& client)
		{
			if (const ::rpcn::rpcn_state state = client->wait_for_connection(); state != ::rpcn::rpcn_state::failure_no_failure)
			{
				return state;
			}
			return client->wait_for_authentified();
		}
	}

	std::string derive_password(std::string_view typed)
	{
		static constexpr std::string_view salt = "No matter where you go, everybody's connected.";

		if (typed.size() > static_cast<std::size_t>(INT_MAX))
		{
			return {};
		}

		unsigned char digest[WC_SHA3_256_DIGEST_SIZE];
		if (wc_PBKDF2(digest, reinterpret_cast<const byte*>(typed.data()), static_cast<int>(typed.size()),
				reinterpret_cast<const byte*>(salt.data()), static_cast<int>(salt.size()), 200'000, WC_SHA3_256_DIGEST_SIZE,
				WC_SHA3_256) != 0)
		{
			return {};
		}
		return to_upper_hex(digest, sizeof(digest));
	}

	// The pragma makes a missing enumerator an error, so an upstream addition stops the build instead of a wrong code.
	void map_state(::rpcn::rpcn_state state, Rpcs3IOS_RpcnResult& result)
	{
		result.raw = static_cast<int32_t>(state);
#pragma clang diagnostic push
#pragma clang diagnostic error "-Wswitch"
		switch (state)
		{
		case ::rpcn::rpcn_state::failure_no_failure: result.state = RPCS3_IOS_RPCN_STATE_OK; break;
		case ::rpcn::rpcn_state::failure_input: result.state = RPCS3_IOS_RPCN_STATE_INPUT; break;
		case ::rpcn::rpcn_state::failure_wolfssl: result.state = RPCS3_IOS_RPCN_STATE_WOLFSSL; break;
		case ::rpcn::rpcn_state::failure_resolve: result.state = RPCS3_IOS_RPCN_STATE_RESOLVE; break;
		case ::rpcn::rpcn_state::failure_binding: result.state = RPCS3_IOS_RPCN_STATE_BINDING; break;
		case ::rpcn::rpcn_state::failure_connect: result.state = RPCS3_IOS_RPCN_STATE_CONNECT; break;
		case ::rpcn::rpcn_state::failure_id: result.state = RPCS3_IOS_RPCN_STATE_ID; break;
		case ::rpcn::rpcn_state::failure_id_already_logged_in: result.state = RPCS3_IOS_RPCN_STATE_ID_ALREADY_LOGGED_IN; break;
		case ::rpcn::rpcn_state::failure_id_username: result.state = RPCS3_IOS_RPCN_STATE_ID_USERNAME; break;
		case ::rpcn::rpcn_state::failure_id_password: result.state = RPCS3_IOS_RPCN_STATE_ID_PASSWORD; break;
		case ::rpcn::rpcn_state::failure_id_token: result.state = RPCS3_IOS_RPCN_STATE_ID_TOKEN; break;
		case ::rpcn::rpcn_state::failure_protocol: result.state = RPCS3_IOS_RPCN_STATE_PROTOCOL; break;
		case ::rpcn::rpcn_state::failure_other: result.state = RPCS3_IOS_RPCN_STATE_OTHER; break;
		}
#pragma clang diagnostic pop
	}

	void map_error(::rpcn::ErrorType error, Rpcs3IOS_RpcnResult& result)
	{
		result.raw = static_cast<int32_t>(error);
#pragma clang diagnostic push
#pragma clang diagnostic error "-Wswitch"
		switch (error)
		{
		case ::rpcn::ErrorType::NoError: result.error = RPCS3_IOS_RPCN_ERROR_NONE; break;
		case ::rpcn::ErrorType::Malformed: result.error = RPCS3_IOS_RPCN_ERROR_MALFORMED; break;
		case ::rpcn::ErrorType::InvalidInput: result.error = RPCS3_IOS_RPCN_ERROR_INVALID_INPUT; break;
		case ::rpcn::ErrorType::TooSoon: result.error = RPCS3_IOS_RPCN_ERROR_TOO_SOON; break;
		case ::rpcn::ErrorType::LoginError: result.error = RPCS3_IOS_RPCN_ERROR_LOGIN_ERROR; break;
		case ::rpcn::ErrorType::LoginAlreadyLoggedIn: result.error = RPCS3_IOS_RPCN_ERROR_LOGIN_ALREADY_LOGGED_IN; break;
		case ::rpcn::ErrorType::LoginInvalidUsername: result.error = RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_USERNAME; break;
		case ::rpcn::ErrorType::LoginInvalidPassword: result.error = RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_PASSWORD; break;
		case ::rpcn::ErrorType::LoginInvalidToken: result.error = RPCS3_IOS_RPCN_ERROR_LOGIN_INVALID_TOKEN; break;
		case ::rpcn::ErrorType::CreationError: result.error = RPCS3_IOS_RPCN_ERROR_CREATION_ERROR; break;
		case ::rpcn::ErrorType::CreationExistingUsername: result.error = RPCS3_IOS_RPCN_ERROR_CREATION_EXISTING_USERNAME; break;
		case ::rpcn::ErrorType::CreationBannedEmailProvider: result.error = RPCS3_IOS_RPCN_ERROR_CREATION_BANNED_EMAIL_PROVIDER; break;
		case ::rpcn::ErrorType::CreationExistingEmail: result.error = RPCS3_IOS_RPCN_ERROR_CREATION_EXISTING_EMAIL; break;
		case ::rpcn::ErrorType::EmailFail: result.error = RPCS3_IOS_RPCN_ERROR_EMAIL_FAIL; break;
		case ::rpcn::ErrorType::NotFound: result.error = RPCS3_IOS_RPCN_ERROR_NOT_FOUND; break;
		case ::rpcn::ErrorType::Blocked: result.error = RPCS3_IOS_RPCN_ERROR_BLOCKED; break;
		case ::rpcn::ErrorType::AlreadyFriend: result.error = RPCS3_IOS_RPCN_ERROR_ALREADY_FRIEND; break;
		// No code of its own: Swift words these as "code {n}" from `raw`.
		case ::rpcn::ErrorType::Invalid:
		case ::rpcn::ErrorType::RoomMissing:
		case ::rpcn::ErrorType::RoomAlreadyJoined:
		case ::rpcn::ErrorType::RoomFull:
		case ::rpcn::ErrorType::RoomPasswordMismatch:
		case ::rpcn::ErrorType::RoomPasswordMissing:
		case ::rpcn::ErrorType::RoomGroupNoJoinLabel:
		case ::rpcn::ErrorType::RoomGroupFull:
		case ::rpcn::ErrorType::RoomGroupJoinLabelNotFound:
		case ::rpcn::ErrorType::RoomGroupMaxSlotMismatch:
		case ::rpcn::ErrorType::Unauthorized:
		case ::rpcn::ErrorType::DbFail:
		case ::rpcn::ErrorType::ScoreNotBest:
		case ::rpcn::ErrorType::ScoreInvalid:
		case ::rpcn::ErrorType::ScoreHasData:
		case ::rpcn::ErrorType::CondFail:
		case ::rpcn::ErrorType::Unsupported: result.error = RPCS3_IOS_RPCN_ERROR_OTHER; break;
		}
#pragma clang diagnostic pop
	}

	bool sign_in(std::string_view npid, std::string_view password, std::string_view token, std::size_t max_form,
		std::string& accepted, Rpcs3IOS_RpcnResult& result)
	{
		accepted.clear();
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};

		std::vector<std::string> forms = password_forms(password, derive_password);
		std::erase_if(forms, [max_form](const std::string& form) { return form.size() > max_form; });

		// A mistyped password while switching accounts must not cost the account that worked; get_npid() would invent one.
		std::string prev_npid, prev_host, prev_secret, prev_token;
		{
			std::lock_guard lock(s_config_mutex);
			g_cfg_rpcn.load();
			prev_npid = g_cfg_rpcn.npid.to_string();
			prev_host = g_cfg_rpcn.get_host();
			prev_secret = g_cfg_rpcn.get_password();
			prev_token = g_cfg_rpcn.get_token();
		}
		const std::string use_token = token.empty() ? prev_token : std::string(token);

		::rpcn::rpcn_state state = ::rpcn::rpcn_state::failure_input;
		for (const std::string& form : forms)
		{
			release_held();
			std::shared_ptr<::rpcn::rpcn_client> client;
			{
				std::lock_guard lock(s_config_mutex);
				rpcn_set_memory_secrets(form, use_token);
				g_cfg_rpcn.set_npid(npid);
				// On disk before the client exists, because its constructor load()s rpcn.yml; the armed slot keeps the secret out.
				g_cfg_rpcn.save();
				client = ::rpcn::rpcn_client::get_instance(0, false);
			}
			state = client ? connect_and_authenticate(client) : ::rpcn::rpcn_state::failure_other;
			// Dropped so the next form gets a fresh client; the core signs in again when a game asks.
			client.reset();

			if (state == ::rpcn::rpcn_state::failure_no_failure)
			{
				accepted = form;
				map_state(state, result);
				return true;
			}
			if (state != ::rpcn::rpcn_state::failure_id_password)
			{
				break;
			}
		}

		{
			std::lock_guard lock(s_config_mutex);
			g_cfg_rpcn.load();
			g_cfg_rpcn.set_npid(prev_npid);
			g_cfg_rpcn.set_host(prev_host);
			g_cfg_rpcn.save();
			rpcn_set_memory_secrets(prev_secret, prev_token);
		}
		map_state(state, result);
		return false;
	}

	void sign_out()
	{
		release_held();
		rpcn_set_memory_secrets("", "");
	}

	std::string config_json(std::string (*quote)(std::string_view))
	{
		std::lock_guard lock(s_config_mutex);
		g_cfg_rpcn.load();

		std::string hosts;
		for (const auto& [desc, address] : g_cfg_rpcn.get_hosts())
		{
			hosts += hosts.empty() ? "" : ",";
			hosts += "{\"desc\":" + quote(desc) + ",\"host\":" + quote(address) + "}";
		}

		return "{\"host\":" + quote(g_cfg_rpcn.get_host()) + ",\"npid\":" + quote(g_cfg_rpcn.npid.to_string()) +
			",\"hasSecret\":" + (g_cfg_rpcn.get_password().empty() ? "false" : "true") +
			",\"hasToken\":" + (g_cfg_rpcn.get_token().empty() ? "false" : "true") +
			",\"ipv6\":" + (g_cfg_rpcn.get_ipv6_support() ? "true" : "false") + ",\"hosts\":[" + hosts + "]}";
	}

	// With PSN status Simulated np_handler throws and the thread dies with a SIG fatal line; tests pin the two literals.
	namespace
	{
		constexpr std::string_view k_fatal_prefix = "Thread terminated due to fatal error: ";
		constexpr std::string_view k_needs_rpcn = "RPCN is required to use PSN online features.";

		std::atomic<bool> s_simulated_needs_rpcn{false};

		// Runs on every log line of every thread, so it allocates nothing, takes no lock and returns at once.
		struct simulated_latch final : logs::listener
		{
			void log(u64 /*stamp*/, const logs::message& msg, std::string_view /*prefix*/, std::string_view text) override
			{
				if (static_cast<logs::level>(msg) == logs::level::fatal && text.starts_with(k_fatal_prefix) &&
					text.find(k_needs_rpcn) != std::string_view::npos)
				{
					s_simulated_needs_rpcn.store(true, std::memory_order_relaxed);
				}
			}
		};
	}

	void install_simulated_latch()
	{
		// Once: logs::listener::add has no duplicate check. It must outlive every thread that can still log.
		static simulated_latch s_latch;
		static const bool s_added = (logs::listener::add(&s_latch), true);
		static_cast<void>(s_added);
	}

	void on_boot()
	{
		s_simulated_needs_rpcn.store(false, std::memory_order_relaxed);
	}

	bool simulated_needs_rpcn()
	{
		return s_simulated_needs_rpcn.load(std::memory_order_relaxed);
	}

	// Does not reload rpcn.yml, which logs its path and this is polled; `relogins` covers the race after backgrounding.
	std::string status_json(std::string (*quote)(std::string_view))
	{
		std::string npid;
		bool configured = false;
		{
			std::lock_guard lock(s_config_mutex);
			npid = g_cfg_rpcn.npid.to_string();
			configured = !npid.empty() && !g_cfg_rpcn.get_password().empty();
		}

		bool client = false, connected = false, signed_in = false;
		int32_t state = RPCS3_IOS_RPCN_STATE_OK;
		std::string online_name;
		if (const std::shared_ptr<::rpcn::rpcn_client> rpcn = ::rpcn::rpcn_client::peek_instance())
		{
			client = true;
			connected = rpcn->is_connected();
			signed_in = rpcn->is_authentified();
			Rpcs3IOS_RpcnResult mapped{};
			map_state(rpcn->get_rpcn_state(), mapped);
			state = mapped.state;
			if (signed_in)
			{
				online_name = rpcn->get_online_name();
			}
		}

		// IsStopped is true while stopping too.
		int32_t boot_psn = -1;
		if (!Emu.IsStopped())
		{
#pragma clang diagnostic push
#pragma clang diagnostic error "-Wswitch"
			switch (g_cfg.net.psn_status.get())
			{
			case np_psn_status::disabled: boot_psn = 0; break;
			case np_psn_status::psn_fake: boot_psn = 1; break;
			case np_psn_status::psn_rpcn: boot_psn = 2; break;
			}
#pragma clang diagnostic pop
		}

		// A stop that begins between the two reads is a residual race.
		bool psn_online = false;
		if (boot_psn == 2)
		{
			if (const auto handler = g_fxo->try_get<named_thread<np::np_handler>>())
			{
				psn_online = handler->get_psn_status() == SCE_NP_MANAGER_STATUS_ONLINE;
			}
		}
		const int32_t session = session_code(client, signed_in, state, boot_psn, psn_online);
		const bool needs_rpcn = simulated_needs_rpcn();

		return "{\"configured\":" + std::string(configured ? "true" : "false") + ",\"npid\":" + quote(npid) +
			",\"client\":" + (client ? "true" : "false") + ",\"connected\":" + (connected ? "true" : "false") +
			",\"signedIn\":" + (signed_in ? "true" : "false") + ",\"state\":" + std::to_string(state) +
			",\"session\":" + std::to_string(session) + ",\"onlineName\":" + quote(online_name) +
			",\"bootPsn\":" + std::to_string(boot_psn) + ",\"relogins\":" + std::to_string(rpcn_relogins()) +
			",\"simulatedNeedsRpcn\":" + (needs_rpcn ? "true" : "false") + "}";
	}

	namespace
	{
		// The server takes these from an anonymous connection; the client is dropped after, since it keeps its first failure.
		template <typename Body>
		void with_connection(Rpcs3IOS_RpcnResult& result, Body&& body)
		{
			result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
			release_held();
			std::shared_ptr<::rpcn::rpcn_client> client;
			{
				// The client's constructor load()s rpcn.yml.
				std::lock_guard lock(s_config_mutex);
				client = ::rpcn::rpcn_client::get_instance(0, false);
			}
			const ::rpcn::rpcn_state state = client ? client->wait_for_connection() : ::rpcn::rpcn_state::failure_other;
			map_state(state, result);
			if (state == ::rpcn::rpcn_state::failure_no_failure)
			{
				map_error(body(*client), result);
			}
		}

		// The derived form, so desktop RPCS3 signs in with the same password.
		std::string set_form(std::string_view password, Rpcs3IOS_RpcnResult& result)
		{
			std::vector<std::string> forms = password_forms(password, derive_password);
			if (forms.empty())
			{
				result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
				map_state(::rpcn::rpcn_state::failure_input, result);
				return {};
			}
			return std::move(forms.front());
		}
	}

	void create_account(std::string_view npid, std::string_view password, std::string_view email, Rpcs3IOS_RpcnResult& result)
	{
		// The desktop dialog's default avatar; the server requires the field.
		static constexpr std::string_view avatar = "https://rpcs3.net/cdn/netplay/DefaultAvatar.png";

		const std::string form = set_form(password, result);
		if (form.empty())
		{
			return;
		}
		with_connection(result, [&](::rpcn::rpcn_client& client) { return client.create_user(npid, form, npid, avatar, email); });
	}

	void resend_token(std::string_view npid, std::string_view password, Rpcs3IOS_RpcnResult& result)
	{
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
		map_state(::rpcn::rpcn_state::failure_input, result);
		for (const std::string& form : password_forms(password, derive_password))
		{
			::rpcn::ErrorType error = ::rpcn::ErrorType::NoError;
			with_connection(result, [&](::rpcn::rpcn_client& client) { return error = client.resend_token(npid, form); });
			// A connection that failed leaves NoError, so it stops here too.
			if (error != ::rpcn::ErrorType::LoginInvalidPassword)
			{
				break;
			}
		}
	}

	void send_reset_token(std::string_view npid, std::string_view email, Rpcs3IOS_RpcnResult& result)
	{
		with_connection(result, [&](::rpcn::rpcn_client& client) { return client.send_reset_token(npid, email); });
	}

	void reset_password(std::string_view npid, std::string_view code, std::string_view password, Rpcs3IOS_RpcnResult& result)
	{
		const std::string form = set_form(password, result);
		if (form.empty())
		{
			return;
		}
		with_connection(result, [&](::rpcn::rpcn_client& client) { return client.reset_password(npid, code, form); });
	}

	namespace
	{
		// A friend change never opens a session of its own.
		std::shared_ptr<::rpcn::rpcn_client> signed_in_client()
		{
			std::shared_ptr<::rpcn::rpcn_client> client = ::rpcn::rpcn_client::peek_instance();
			return client && client->is_authentified() ? client : nullptr;
		}
	}

	void friends_session(bool open, Rpcs3IOS_RpcnResult& result)
	{
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
		if (!open)
		{
			std::shared_ptr<::rpcn::rpcn_client> last;
			{
				std::lock_guard lock(s_holder_mutex);
				if (s_friends_users > 0 && --s_friends_users == 0)
				{
					last = std::move(s_held);
				}
			}
			// Outside the lock: the last owner's release joins the client's threads.
			last.reset();
			return;
		}

		{
			std::lock_guard lock(s_holder_mutex);
			s_friends_users++;
			if (s_held)
			{
				map_state(s_held->get_rpcn_state(), result);
				return;
			}
		}

		release_held();
		std::shared_ptr<::rpcn::rpcn_client> client;
		{
			std::lock_guard lock(s_config_mutex);
			g_cfg_rpcn.load();
			// The NPID from its node: get_npid() invents and saves one when it is empty.
			if (g_cfg_rpcn.npid.to_string().empty() || g_cfg_rpcn.get_password().empty())
			{
				map_state(::rpcn::rpcn_state::failure_input, result);
				return;
			}
			client = ::rpcn::rpcn_client::get_instance(0, false);
		}
		const ::rpcn::rpcn_state state = client ? connect_and_authenticate(client) : ::rpcn::rpcn_state::failure_other;
		map_state(state, result);
		if (state != ::rpcn::rpcn_state::failure_no_failure)
		{
			// Dropped on return: a failed open holds nothing.
			return;
		}

		std::lock_guard lock(s_holder_mutex);
		// Unless every list closed during the wait; then `client` is dropped on return.
		if (s_friends_users > 0 && !s_held)
		{
			s_held = std::move(client);
		}
	}

	std::string friends_json(std::string (*quote)(std::string_view))
	{
		const std::shared_ptr<::rpcn::rpcn_client> client = signed_in_client();
		if (!client)
		{
			return R"({"signedIn":false,"friends":[],"received":[],"sent":[]})";
		}

		// One copy under the client's lock, so the three lists agree with each other.
		::rpcn::friend_data data;
		client->get_friends(data);

		std::string friends;
		for (const auto& [name, presence] : data.friends)
		{
			friends += friends.empty() ? "" : ",";
			friends += "{\"npid\":" + quote(name) + ",\"online\":" + (presence.online ? "true" : "false") +
				",\"title\":" + quote(presence.pr_title) + ",\"status\":" + quote(presence.pr_status) +
				",\"comment\":" + quote(presence.pr_comment) + "}";
		}
		const auto names = [quote](const std::set<std::string>& set)
		{
			std::string out;
			for (const std::string& name : set)
			{
				out += out.empty() ? "" : ",";
				out += quote(name);
			}
			return out;
		};
		return "{\"signedIn\":true,\"friends\":[" + friends + "],\"received\":[" + names(data.requests_received) +
			"],\"sent\":[" + names(data.requests_sent) + "]}";
	}

	Rpcs3IOS_Status add_friend(std::string_view npid, Rpcs3IOS_RpcnResult& result)
	{
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
		const std::shared_ptr<::rpcn::rpcn_client> client = signed_in_client();
		if (!client)
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}

		// An empty optional is a request that got no answer; otherwise the server's own error.
		if (const std::optional<::rpcn::ErrorType> error = client->add_friend(npid))
		{
			map_error(*error, result);
		}
		else
		{
			map_state(::rpcn::rpcn_state::failure_other, result);
		}
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status remove_friend(std::string_view npid, Rpcs3IOS_RpcnResult& result)
	{
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
		const std::shared_ptr<::rpcn::rpcn_client> client = signed_in_client();
		if (!client)
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}

		// The core answers yes or no only, so a refusal and a failed send have no code of their own.
		if (!client->remove_friend(npid))
		{
			result.error = RPCS3_IOS_RPCN_ERROR_OTHER;
		}
		return RPCS3_IOS_STATUS_OK;
	}

	namespace
	{
		// A stop that begins between the two reads is status_json's residual race.
		std::optional<SceNpCommunicationId> running_game_context()
		{
			if (Emu.IsStopped())
			{
				return std::nullopt;
			}
			auto* const handler = g_fxo->try_get<named_thread<np::np_handler>>();
			if (!handler || !handler->basic_handler_registered)
			{
				return std::nullopt;
			}
			return handler->get_basic_handler_context();
		}

		// Only the running game's invites, never another title's.
		auto invites_for(const ::rpcn::rpcn_client& client, const SceNpCommunicationId& context)
		{
			auto invites = client.get_messages(SCE_NP_BASIC_MESSAGE_MAIN_TYPE_INVITE, true);
			std::erase_if(invites, [&context](const auto& entry)
			{
				return std::strncmp(entry.second->second.commId.data, context.data, sizeof(context.data) - 1) != 0;
			});
			return invites;
		}

		// Asked afresh at every press, because the game can have changed since the list was drawn.
		Rpcs3IOS_Status check_invite(uint64_t id, std::shared_ptr<::rpcn::rpcn_client>& client)
		{
			client = signed_in_client();
			const std::optional<SceNpCommunicationId> context = running_game_context();
			if (!client || !context)
			{
				return RPCS3_IOS_STATUS_WRONG_STATE;
			}
			const auto invites = invites_for(*client, *context);
			const bool listed = std::any_of(invites.begin(), invites.end(), [id](const auto& entry) { return entry.first == id; });
			return listed ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
	}

	std::string invites_json(std::string (*quote)(std::string_view))
	{
		const std::shared_ptr<::rpcn::rpcn_client> client = signed_in_client();
		const std::optional<SceNpCommunicationId> context = running_game_context();
		if (!client || !context)
		{
			return "[]";
		}

		std::string entries;
		for (const auto& [id, message] : invites_for(*client, *context))
		{
			entries += entries.empty() ? "" : ",";
			entries += "{\"id\":" + std::to_string(id) + ",\"from\":" + quote(message->first) + ",\"subject\":" +
				quote(message->second.subject) + ",\"body\":" + quote(message->second.body) + "}";
		}
		return "[" + entries + "]";
	}

	Rpcs3IOS_Status accept_invite(uint64_t id)
	{
		std::shared_ptr<::rpcn::rpcn_client> client;
		if (const Rpcs3IOS_Status checked = check_invite(id, client); checked != RPCS3_IOS_STATUS_OK)
		{
			return checked;
		}
		// As selecting it in the XMB: the game is told one was selected and loads it.
		auto* const handler = g_fxo->try_get<named_thread<np::np_handler>>();
		return handler && handler->select_invitation(id) ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
	}

	Rpcs3IOS_Status decline_invite(uint64_t id)
	{
		std::shared_ptr<::rpcn::rpcn_client> client;
		if (const Rpcs3IOS_Status checked = check_invite(id, client); checked != RPCS3_IOS_STATUS_OK)
		{
			return checked;
		}
		client->mark_message_used(id);
		return RPCS3_IOS_STATUS_OK;
	}

	namespace
	{
		// tests/test_rpcn_bridge.py reads both literals back from the core's rpcn_config.cpp.
		constexpr std::string_view k_official_name = "Official RPCN Server";
		constexpr std::string_view k_official_host = "np.rpcs3.net";

		// The client's thread reads g_cfg_rpcn's host at every connect, so a reconnect picks up the new server.
		void tell_client()
		{
			if (const std::shared_ptr<::rpcn::rpcn_client> client = ::rpcn::rpcn_client::peek_instance())
			{
				client->server_infos_updated();
			}
		}
	}

	Rpcs3IOS_Status add_host(std::string_view name, std::string_view host)
	{
		std::lock_guard lock(s_config_mutex);
		g_cfg_rpcn.load();
		if (!g_cfg_rpcn.add_host(name, host))
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		g_cfg_rpcn.save();
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status del_host(std::string_view name, std::string_view host)
	{
		// Before the core's del_host, which answers true for the official server and deletes nothing.
		if (name == k_official_name && host == k_official_host)
		{
			return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
		}
		std::lock_guard lock(s_config_mutex);
		g_cfg_rpcn.load();
		if (!g_cfg_rpcn.del_host(name, host))
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		g_cfg_rpcn.save();
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status reset_hosts()
	{
		{
			std::lock_guard lock(s_config_mutex);
			g_cfg_rpcn.load();
			g_cfg_rpcn.hosts.from_default();
			g_cfg_rpcn.host.from_default();
			g_cfg_rpcn.save();
		}
		tell_client();
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status select_host(std::string_view host)
	{
		{
			std::lock_guard lock(s_config_mutex);
			g_cfg_rpcn.load();
			const auto hosts = g_cfg_rpcn.get_hosts();
			if (std::none_of(hosts.begin(), hosts.end(), [host](const auto& entry) { return entry.second == host; }))
			{
				return RPCS3_IOS_STATUS_WRONG_STATE;
			}
			g_cfg_rpcn.set_host(host);
			g_cfg_rpcn.save();
		}
		tell_client();
		return RPCS3_IOS_STATUS_OK;
	}

	void set_ipv6(bool on)
	{
		std::lock_guard lock(s_config_mutex);
		g_cfg_rpcn.load();
		g_cfg_rpcn.set_ipv6_support(on);
		g_cfg_rpcn.save();
	}

	Rpcs3IOS_Status delete_trophies(Rpcs3IOS_RpcnResult& result)
	{
		result = {RPCS3_IOS_RPCN_STATE_OK, RPCS3_IOS_RPCN_ERROR_NONE, 0};
		std::shared_ptr<::rpcn::rpcn_client> client;
		{
			std::lock_guard lock(s_holder_mutex);
			client = s_held;
		}
		// The held session only, and signed in: never a client of its own, never one a game holds.
		if (!client || !client->is_authentified())
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		// An empty communication ID is every title's trophies, as RPCS3's own call defaults to.
		map_error(client->delete_trophies(), result);
		return RPCS3_IOS_STATUS_OK;
	}

	namespace
	{
		constexpr std::string_view k_picture_prefix = "armsx3_avatar_";

		// Each ends in `/`; PSN avatar packs install in the shared folder and dev_flash holds the firmware's user icons.
		struct avatar_roots
		{
			std::string user;
			std::string home;
			std::string shared;
			std::string flash;
		};

		avatar_roots roots()
		{
			// The one user this app makes; Emu.GetUsr() names it once the core has read it.
			std::string user = Emu.GetUsr();
			if (user.empty())
			{
				user = "00000001";
			}
			const std::string hdd0 = rpcs3::utils::get_hdd0_dir();
			std::string flash = g_cfg_vfs.get_dev_flash();
			if (!flash.empty() && flash.back() != '/')
			{
				flash += '/';
			}
			return {user, hdd0 + "home/" + user + "/", hdd0 + "home/psn_avatar/", flash};
		}

		// The ABI has already refused relative paths and `..`, so a prefix test cannot be walked out of.
		bool under_avatar_root(std::string_view path, const avatar_roots& at)
		{
			return path.starts_with(at.home) || path.starts_with(at.shared) || (!at.flash.empty() && path.starts_with(at.flash));
		}

		const char* avatar_kind(std::string_view path, const avatar_roots& at)
		{
			if (!at.flash.empty() && path.starts_with(at.flash))
			{
				return "builtin";
			}
			if (path.starts_with(at.home + "psn_avatar/") || path.starts_with(at.shared))
			{
				return "psn";
			}
			return "picture";
		}

		std::string avatar_title(std::string_view path, const avatar_roots& at)
		{
			const std::string folder = at.home + "psn_avatar/";
			if (!path.starts_with(folder))
			{
				return {};
			}
			const std::string_view rest = path.substr(folder.size());
			const std::size_t slash = rest.find('/');
			return slash == std::string_view::npos ? std::string() : std::string(rest.substr(0, slash));
		}

		std::string avatar_entry(std::string (*quote)(std::string_view), std::string_view path, const avatar_roots& at,
			bool usable)
		{
			return "{\"path\":" + quote(path) + ",\"kind\":\"" + avatar_kind(path, at) + "\",\"title\":" +
				quote(avatar_title(path, at)) + ",\"usable\":" + (usable ? "true" : "false") + "}";
		}
	}

	std::string avatar_list(std::string (*quote)(std::string_view))
	{
		const avatar_roots at = roots();
		std::vector<std::string> icons;
		std::vector<std::string> pictures;
		if (!at.flash.empty())
		{
			const std::string icon_dir = at.flash + "vsh/resource/explore/user/";
			for (const auto& entry : fs::dir(icon_dir))
			{
				if (!entry.is_directory && fmt::to_lower(entry.name).ends_with(".png"))
				{
					icons.push_back(icon_dir + entry.name);
				}
			}
		}
		for (const auto& entry : fs::dir(at.home))
		{
			if (!entry.is_directory && entry.name.starts_with(k_picture_prefix) && entry.name.ends_with(".png"))
			{
				pictures.push_back(at.home + entry.name);
			}
		}
		std::sort(icons.begin(), icons.end());
		std::sort(pictures.begin(), pictures.end());

		// Android's order: the firmware's icons, the PSN avatars by path, then the player's own pictures.
		std::string avatars;
		u32 unusable = 0;
		const auto add = [&](const std::string& path, bool usable)
		{
			avatars += (avatars.empty() ? "" : ",") + avatar_entry(quote, path, at, usable);
		};
		for (const std::string& path : icons)
		{
			add(path, true);
		}
		for (const std::string& path : np::avatar::get_installed_avatars(at.user))
		{
			const bool usable = np::avatar::is_file_usable(path);
			unusable += usable ? 0 : 1;
			add(path, usable);
		}
		for (const std::string& path : pictures)
		{
			add(path, true);
		}
		const bool firmware = fs::is_file(np::avatar::get_default_avatar_path(at.user));
		return "{\"firmware\":" + std::string(firmware ? "true" : "false") + ",\"avatars\":[" + avatars +
			"],\"unusable\":" + std::to_string(unusable) + "}";
	}

	std::string avatar_current(std::string (*quote)(std::string_view))
	{
		const avatar_roots at = roots();
		const std::string selected = np::avatar::get_selected_avatar(at.user);
		if (selected.empty())
		{
			return "{\"path\":" + quote(np::avatar::get_default_avatar_path(at.user)) + ",\"kind\":\"default\",\"title\":\"\"}";
		}
		return "{\"path\":" + quote(selected) + ",\"kind\":\"" + avatar_kind(selected, at) + "\",\"title\":" +
			quote(avatar_title(selected, at)) + "}";
	}

	Rpcs3IOS_Status avatar_select(std::string_view path, const char*& kind)
	{
		const avatar_roots at = roots();
		kind = "picture";
		// Before any np::avatar call.
		if (!under_avatar_root(path, at))
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}
		kind = avatar_kind(path, at);
		const std::string file(path);
		// Read first, so a file the core cannot load never becomes the selection.
		if (np::avatar::load_file(file).empty() || !np::avatar::set_selected_avatar(at.user, file))
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status avatar_image(std::string_view path, std::vector<u8>& png)
	{
		png.clear();
		const avatar_roots at = roots();
		// Before any np::avatar call.
		if (!under_avatar_root(path, at))
		{
			return RPCS3_IOS_STATUS_PATH_REJECTED;
		}
		png = np::avatar::load_file(std::string(path));
		return png.empty() ? RPCS3_IOS_STATUS_WRONG_STATE : RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status avatar_use_image(const u8* bytes, std::size_t size)
	{
		const avatar_roots at = roots();
		// Named after its contents: sceNp caches converted avatars by path, so a new picture needs a new name to be seen.
		u8 digest[20];
		sha1(bytes, size, digest);
		u64 tag = 0;
		for (int i = 0; i < 8; i++)
		{
			tag = (tag << 8) | digest[i];
		}
		const std::string name = fmt::format("%s%016x.png", k_picture_prefix, tag);
		const std::string path = at.home + name;

		if (!fs::is_file(path))
		{
			// Through a temporary, so a failed write never leaves a broken file under the final name.
			const std::string part = path + ".part";
			fs::file file(part, fs::rewrite);
			if (!file || file.write(bytes, size) != size)
			{
				file.close();
				fs::remove_file(part);
				return RPCS3_IOS_STATUS_WRONG_STATE;
			}
			file.close();
			if (!fs::rename(part, path, true))
			{
				fs::remove_file(part);
				return RPCS3_IOS_STATUS_WRONG_STATE;
			}
		}

		if (!np::avatar::set_selected_avatar(at.user, path))
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}

		// Names first: fs::dir is a live handle on the folder being changed.
		std::vector<std::string> previous;
		for (const auto& entry : fs::dir(at.home))
		{
			if (!entry.is_directory && entry.name != name && entry.name.starts_with(k_picture_prefix) &&
				entry.name.ends_with(".png"))
			{
				previous.push_back(entry.name);
			}
		}
		for (const std::string& old_name : previous)
		{
			fs::remove_file(at.home + old_name);
		}
		return RPCS3_IOS_STATUS_OK;
	}

	Rpcs3IOS_Status avatar_use_default()
	{
		const avatar_roots at = roots();
		// The default is a firmware user icon, so with no firmware there is nothing to fall back to.
		if (!fs::is_file(np::avatar::get_default_avatar_path(at.user)))
		{
			return RPCS3_IOS_STATUS_WRONG_STATE;
		}
		return np::avatar::set_selected_avatar(at.user, "") ? RPCS3_IOS_STATUS_OK : RPCS3_IOS_STATUS_WRONG_STATE;
	}
}
