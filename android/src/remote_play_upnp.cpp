// Remote play, online hosting: a port opened on the host's router (UPnP) for the session.
//
// Online, the friend reaches the host straight across the internet, with no relay. Where the
// friend's own network lets nothing in (a phone on mobile data is the common case) and the host's
// router only lets in what the host sent out first, the two never connect. A router that speaks
// UPnP can be asked to let packets for one port in: then the host has a fixed public address the
// friend's side can always reach, as consoles and Moonlight's hosts do. It is still direct: the
// router only forwards to the host.
//
// The app (RemotePlayUpnp.kt) asks for the UDP port WebRTC already listens on, adds the router's
// public address for it to the offer, renews the lease while the session lasts and closes the port
// when it ends. The lease means a port left open by a crash closes itself, so a router that only
// opens ports for good is not used at all: nothing would ever close a port the app did not live to.

#include "stdafx.h"

#include <algorithm>
#include <cstdio>
#include <mutex>
#include <string>

#include <miniupnpc.h>
#include <upnpcommands.h>
#include <upnperrors.h>

LOG_CHANNEL(rp_upnp_log, "RemotePlay");

namespace
{
	std::mutex g_mutex;
	bool g_found = false;
	UPNPUrls g_urls{};
	IGDdatas g_data{};
	char g_lan_addr[64]{};
	char g_wan_addr[64]{};

	// UPnP error codes from the WANIPConnection spec.
	constexpr int conflict_in_mapping_entry = 718;
	constexpr int only_permanent_leases_supported = 725;

	// Under g_mutex.
	void forget_router()
	{
		if (g_found)
		{
			FreeUPNPUrls(&g_urls);
		}
		g_found = false;
		g_urls = {};
		g_data = {};
	}

	// Under g_mutex. The router found before when it still answers (the device may have moved to
	// another network since), otherwise a new search, which takes about two seconds.
	bool find_router()
	{
		if (g_found)
		{
			char wan[64]{};
			if (UPNP_GetExternalIPAddress(g_urls.controlURL, g_data.first.servicetype, wan) == UPNPCOMMAND_SUCCESS && wan[0])
			{
				std::snprintf(g_wan_addr, sizeof(g_wan_addr), "%s", wan);
				return true;
			}
			forget_router();
		}

		int error = 0;
		UPNPDev* devices = upnpDiscover(2000, nullptr, nullptr, UPNP_LOCAL_PORT_ANY, 0, 2, &error);
		if (!devices)
		{
			rp_upnp_log.notice("Remote play: no UPnP router answered (%d)", error);
			return false;
		}

		const int igd = UPNP_GetValidIGD(devices, &g_urls, &g_data, g_lan_addr, sizeof(g_lan_addr), g_wan_addr, sizeof(g_wan_addr));
		freeUPNPDevlist(devices);

		if (igd == UPNP_CONNECTED_IGD)
		{
			g_found = true;
			rp_upnp_log.notice("Remote play: UPnP router found, this device %s, public %s", g_lan_addr, g_wan_addr);
			return true;
		}

		if (igd == UPNP_PRIVATEIP_IGD)
		{
			// Behind another NAT (the provider's, say): a port opened here leads nowhere.
			rp_upnp_log.notice("Remote play: the UPnP router's own address %s is private, not using it", g_wan_addr);
		}
		else
		{
			rp_upnp_log.notice("Remote play: no usable UPnP router (%d)", igd);
		}

		if (igd != 0)
		{
			FreeUPNPUrls(&g_urls);
		}
		g_urls = {};
		g_data = {};
		return false;
	}

	// Under g_mutex.
	int add_mapping(const std::string& external, const std::string& internal, const std::string& lease)
	{
		return UPNP_AddPortMapping(g_urls.controlURL, g_data.first.servicetype, external.c_str(), internal.c_str(),
			g_lan_addr, "ARMSX3 remote play", "UDP", nullptr, lease.c_str());
	}
}

// Looks for the router. Writes this device's address on the router's network into lan_addr and
// returns true when there is a router to ask, with a public address of its own. Blocks for about
// two seconds the first time: call it off the main thread.
extern "C" bool _rpcsx_remotePlayUpnpPrepare(char* lan_addr, int lan_addr_len)
{
	std::lock_guard lock(g_mutex);

	if (!find_router())
	{
		return false;
	}

	std::snprintf(lan_addr, static_cast<usz>(lan_addr_len), "%s", g_lan_addr);
	return true;
}

// Opens UDP internal_port on this device on the router: the same port, or when another device holds
// it the next free one up to 32 above, for lease_seconds. Asked again for the same ports it renews
// the lease. Writes the router's public address into public_addr and returns the router's port, or
// 0 when it refused. external_hint: the router port this one had before, to renew; 0 the first time.
extern "C" int _rpcsx_remotePlayUpnpMap(int internal_port, int external_hint, int lease_seconds, char* public_addr, int public_addr_len)
{
	std::lock_guard lock(g_mutex);

	if (!g_found || internal_port <= 0 || internal_port > 0xffff)
	{
		return 0;
	}

	const std::string internal = std::to_string(internal_port);
	const std::string lease = std::to_string(lease_seconds);
	const int first = external_hint > 0 ? external_hint : internal_port;
	// A renewal asks for the port it had and no other. That port is the one in the friend's offer,
	// and a mapping moved anywhere else would only leave the old one open behind it.
	const int last = external_hint > 0 ? first : std::min(first + 32, 0xffff);

	for (int external_port = first; external_port <= last; external_port++)
	{
		const std::string external = std::to_string(external_port);
		const int result = add_mapping(external, internal, lease);

		if (result == only_permanent_leases_supported)
		{
			// Not taken: a permanent forward stays open on the router for good if the app is killed
			// before the session ends. The offer goes out as it was, as with no router at all.
			rp_upnp_log.notice("Remote play: the router only opens ports permanently, so it is not used");
			return 0;
		}

		if (result == UPNPCOMMAND_SUCCESS)
		{
			rp_upnp_log.notice("Remote play: router port %d open to %s:%d (lease %s s)", external_port, g_lan_addr, internal_port, lease);
			std::snprintf(public_addr, static_cast<usz>(public_addr_len), "%s", g_wan_addr);
			return external_port;
		}

		if (result != conflict_in_mapping_entry)
		{
			rp_upnp_log.notice("Remote play: the router would not open port %d: %d (%s)", external_port, result, strupnperror(result));
			return 0;
		}
	}

	rp_upnp_log.notice("Remote play: no free router port near %d", first);
	return 0;
}

// Closes a router port opened by _rpcsx_remotePlayUpnpMap.
extern "C" void _rpcsx_remotePlayUpnpUnmap(int external_port)
{
	std::lock_guard lock(g_mutex);

	if (!g_found || external_port <= 0)
	{
		return;
	}

	const std::string external = std::to_string(external_port);
	const int result = UPNP_DeletePortMapping(g_urls.controlURL, g_data.first.servicetype, external.c_str(), "UDP", nullptr);
	rp_upnp_log.notice("Remote play: router port %d closed (%d)", external_port, result);
}
