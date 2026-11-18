# ARMSX3 for iOS

ARMSX3 for iOS is a port of ARMSX3, the RPCS3-based PlayStation 3 emulator, to iPhone and iPad. It runs the same emulator core as the Android app, with a SwiftUI front end adapted from ARMSX2's iOS app. Graphics go through Vulkan on MoltenVK.

This folder holds the app, the bridge between the app and the core, and the scripts that build them. BUILDING.md explains how to build the app yourself.

## What you need

An iPhone or iPad with an A14 chip or newer, on iOS 17.4 or later. Older chips are not supported, because the larger memory limit iOS can give an app has no effect on them and games do not fit without it.

A way to sideload apps, such as AltStore or SideStore. ARMSX3 is not on the App Store.

JIT. ARMSX3 cannot run games without it, so it turns JIT on itself each time it opens. For that, install LocalDevVPN, give ARMSX3 a pairing file made for your device on a computer, turn LocalDevVPN on, then open ARMSX3 from the home screen.

ARMSX3 takes the pairing file in any of four ways. In Settings › JIT and Device, tap Import Pairing File, or Get from SideStore if SideStore is installed. You can also share the file to ARMSX3 or open it in ARMSX3 from Files or another app. Or put a file named pairingFile.plist in ARMSX3's folder in Files. ARMSX3 moves that file out of the folder the next time it opens.

Built-in JIT also works inside LiveContainer. You need LiveContainer 3.8.13 or later and Helper Process Support turned on for ARMSX3 in LiveContainer's app settings. Open ARMSX3 full screen from LiveContainer's app list, because a LiveContainer window cannot start it.

StikDebug is used only when built-in JIT cannot start. ARMSX3 then says why, and you can launch ARMSX3 from StikDebug's app list to turn on JIT that way.

You also need the PS3 system software file and your own games. Neither is included.

## Installing

Install the IPA with AltStore or SideStore, which re-sign it with your Apple ID. Test builds are signed ad hoc so that the re-signer keeps the app's entitlements, including the larger memory limit. An IPA with no signature at all loses them and closes at launch.

## Moving from an earlier test build

This version has a new bundle ID, com.armsx3.ARMSX3, so iOS treats it as a different app. It cannot see the old app's saves or settings, and StikDebug's app list still points at the old app.

Before you switch, open the old app and go to Settings › Backup & Restore, then tap Back Up Now. The zip goes to the old app's Backups folder in the Files app, so copy it somewhere else, such as iCloud Drive, before you delete anything.

Then install the new IPA, open the same screen and use Restore from Backup to bring your saves and settings back. Games and firmware are not in the backup, so install them again. Last, set up JIT for the new app: import your pairing file again for built-in JIT. If built-in JIT cannot start, pick ARMSX3 again in StikDebug.

## Reporting problems

Settings › Diagnostics › Report a Problem writes a report to Files › ARMSX3 › Reports. During setup, and on the screen ARMSX3 shows when JIT is off, the Report button at the top right writes the same report. Nothing is sent unless you share it, folder paths are removed, and the pairing file is never included. Attach the report when you open an issue on the ARMSX3 GitHub project, along with the game's title and serial and your device model.

## Licence

The iOS port is GPL-2.0-only, like the rest of the repository. Files adapted from ARMSX2's iOS app carry their own GPL-2.0-or-later header, and one ARMSX2 shader-library file is MIT. The JIT enabler's vendored code keeps its own licences, which are in jit-enabler/LICENSES.
