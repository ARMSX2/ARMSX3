// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;

namespace
{
	// 3-16 characters of [A-Za-z0-9_-], as RPCN requires; strnlen never walks an unterminated name past 17 bytes.
	bool rpcn_name_ok(const char* name)
	{
		const std::size_t length = strnlen(name, 17);
		if (length < 3 || length > 16)
		{
			return false;
		}
		for (std::size_t i = 0; i < length; i++)
		{
			const char c = name[i];
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'))
			{
				return false;
			}
		}
		return true;
	}

	// Each field is read with strnlen one past its limit, so a bad string never reaches the core's packet builder.
	bool rpcn_password_ok(const char* password)
	{
		const std::size_t length = strnlen(password, 129);
		return length > 0 && length <= 128;
	}

	// 1-254 bytes, the longest address SMTP carries, with exactly one '@'; RPCN checks the rest.
	bool rpcn_email_ok(const char* email)
	{
		const std::size_t length = strnlen(email, 255);
		if (length == 0 || length > 254)
		{
			return false;
		}
		return std::count(email, email + length, '@') == 1;
	}

	// An activation or reset code is 1-64 bytes; RPCN checks the rest.
	bool rpcn_code_ok(const char* code)
	{
		const std::size_t length = strnlen(code, 65);
		return length > 0 && length <= 64;
	}
	// No '|', because it separates the core's list entries.
	bool rpcn_server_name_ok(const char* name)
	{
		const std::size_t length = strnlen(name, 65);
		if (length == 0 || length > 64)
		{
			return false;
		}
		return std::all_of(name, name + length, [](char c) { return c >= 0x20 && c <= 0x7e && c != '|'; });
	}

	// The core's parse_rpcn_host narrows the port with a throwing narrow, so the range is checked here.
	bool rpcn_server_address_ok(const char* host)
	{
		const std::size_t length = strnlen(host, 254);
		if (length == 0 || length > 253)
		{
			return false;
		}
		const std::string_view text(host, length);
		const std::size_t colon = text.find(':');
		const std::string_view name = text.substr(0, colon);
		const auto name_char = [](char c)
		{
			return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
		};
		if (name.empty() || !std::all_of(name.begin(), name.end(), name_char))
		{
			return false;
		}
		if (colon == std::string_view::npos)
		{
			return true;
		}
		const std::string_view port = text.substr(colon + 1);
		if (port.empty() || port.size() > 5)
		{
			return false;
		}
		u32 value = 0;
		for (const char c : port)
		{
			if (c < '0' || c > '9')
			{
				return false;
			}
			value = value * 10 + static_cast<u32>(c - '0');
		}
		return value >= 1 && value <= 65535;
	}
	// The first half of the path rule; armsx3::bridge::rpcn checks that it lies under the home folder or dev_flash.
	bool avatar_path_ok(const char* path)
	{
		const std::size_t length = strnlen(path, 4096);
		const std::string name(path, length);
		return length > 0 && length < 4096 && path[0] == '/' && (name + "/").find("/../") == std::string::npos;
	}
}


// No log line at all, so neither value nor even the call's timing reaches a log.
Rpcs3IOS_Status Rpcs3IOS_RpcnSetSecret(const char* secret, const char* token)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSetSecret");
	}

	if (secret == nullptr || token == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	armsx3::bridge::rpcn::set_secret(secret, token);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnSignIn(const char* npid, const char* password, const char* token, char* out_secret,
	uint32_t out_capacity, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSignIn");
	}

	if (npid == nullptr || password == nullptr || token == nullptr || out_secret == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid) || password[0] == '\0' || out_capacity < 65)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::string accepted;
	armsx3::bridge::rpcn::sign_in(npid, password, token, out_capacity - 1, accepted, *result);
	std::memcpy(out_secret, accepted.data(), accepted.size());
	out_secret[accepted.size()] = '\0';
	std::fill(accepted.begin(), accepted.end(), '\0');

	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnSignOut(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSignOut");
	}

	armsx3::bridge::rpcn::sign_out();
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnConfig(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnConfig");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::config_json(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnStatus(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnStatus");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::status_json(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnCreateAccount(const char* npid, const char* password, const char* email, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnCreateAccount");
	}

	if (npid == nullptr || password == nullptr || email == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid) || !rpcn_password_ok(password) || !rpcn_email_ok(email))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::create_account(npid, password, email, *result);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnResendToken(const char* npid, const char* password, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnResendToken");
	}

	if (npid == nullptr || password == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid) || !rpcn_password_ok(password))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::resend_token(npid, password, *result);
	return RPCS3_IOS_STATUS_OK;
}

// The email rule refuses an empty address here, before the network.
Rpcs3IOS_Status Rpcs3IOS_RpcnSendResetToken(const char* npid, const char* email, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSendResetToken");
	}

	if (npid == nullptr || email == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid) || !rpcn_email_ok(email))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::send_reset_token(npid, email, *result);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnResetPassword(const char* npid, const char* code, const char* password, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnResetPassword");
	}

	if (npid == nullptr || code == nullptr || password == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid) || !rpcn_code_ok(code) || !rpcn_password_ok(password))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::reset_password(npid, code, password, *result);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnFriendsSession(int32_t open, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnFriendsSession");
	}

	if (result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (open != 0 && open != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::friends_session(open == 1, *result);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnFriends(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnFriends");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::friends_json(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnAddFriend(const char* npid, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnAddFriend");
	}

	if (npid == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::add_friend(npid, *result);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnRemoveFriend(const char* npid, Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnRemoveFriend");
	}

	if (npid == nullptr || result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_name_ok(npid))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::remove_friend(npid, *result);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnInvites(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnInvites");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::invites_json(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

// Any 64-bit id is a valid argument; accept_invite checks it against the running game's list.
Rpcs3IOS_Status Rpcs3IOS_RpcnAcceptInvite(uint64_t invite_id)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnAcceptInvite");
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::accept_invite(invite_id);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnDeclineInvite(uint64_t invite_id)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnDeclineInvite");
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::decline_invite(invite_id);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnAddHost(const char* name, const char* host)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnAddHost");
	}

	if (name == nullptr || host == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_server_name_ok(name) || !rpcn_server_address_ok(host))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::add_host(name, host);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnDelHost(const char* name, const char* host)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnDelHost");
	}

	if (name == nullptr || host == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_server_name_ok(name) || !rpcn_server_address_ok(host))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::del_host(name, host);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnResetHosts(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnResetHosts");
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::reset_hosts();
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnSelectHost(const char* host)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSelectHost");
	}

	if (host == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!rpcn_server_address_ok(host))
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::select_host(host);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnSetIpv6(int32_t on)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnSetIpv6");
	}

	if (on != 0 && on != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::rpcn::set_ipv6(on == 1);
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_RpcnDeleteTrophies(Rpcs3IOS_RpcnResult* result)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_RpcnDeleteTrophies");
	}

	if (result == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::delete_trophies(*result);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarList(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarList");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::avatar_list(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarCurrent(Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarCurrent");
	}

	if (sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	const std::string json = armsx3::bridge::rpcn::avatar_current(patch_json_quote);
	sink(context, json.data(), static_cast<uint32_t>(json.size()));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarSelect(const char* path)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarSelect");
	}

	if (path == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!avatar_path_ok(path))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	const char* kind = "picture";
	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::avatar_select(path, kind);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarImage(const char* path, Rpcs3IOS_TextSink sink, void* context)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarImage");
	}

	if (path == nullptr || sink == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (!avatar_path_ok(path))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	std::vector<u8> image;
	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::avatar_image(path, image);
	if (status == RPCS3_IOS_STATUS_OK)
	{
		sink(context, reinterpret_cast<const char*>(image.data()), static_cast<uint32_t>(image.size()));
	}
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarUseImage(const uint8_t* bytes, uint32_t size)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarUseImage");
	}

	if (bytes == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	static constexpr uint8_t k_png_signature[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	if (size < sizeof(k_png_signature) || size > 16 * 1024 * 1024 || std::memcmp(bytes, k_png_signature, sizeof(k_png_signature)) != 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::avatar_use_image(bytes, size);
	return status;
}

Rpcs3IOS_Status Rpcs3IOS_AvatarUseDefault(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_AvatarUseDefault");
	}

	const Rpcs3IOS_Status status = armsx3::bridge::rpcn::avatar_use_default();
	return status;
}
