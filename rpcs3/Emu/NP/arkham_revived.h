#pragma once

#include <string>

// ARMSX3: Arkham: Revived, KiwifruitDev's community server for the multiplayer of Batman: Arkham Origins
// (https://kiwifruitdev.com/arkhamrevived/). Their PS3 patch is update 1.06's multiplayer COALESCED_INT.BIN with one
// section added to PS3-BmNetVars.ini, [Default__GDHttp GDHttp], which gives GDHttp the server's address in place of
// the title file WB's shut-down service used to send. ARMSX3 makes that same edit to the player's own files at boot,
// in every language, so nothing from the game ships with the app. The requests themselves go through the game's
// own libhttp, which cellSysmodule loads from the installed firmware.
namespace arkham_revived
{
	// Adds the server section to each of the update's multiplayer config files that lacks it
	void prepare(const std::string& title_id);
}
