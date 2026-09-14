#pragma once

#include <jni.h>
#include <string_view>

// A user-picked folder, reached through the Storage Access Framework.
//
// The Play build has no filesystem permission, so a folder picker hands back a grant rather
// than a location: everything under it is reachable through ContentResolver and through
// nothing else. The core, meanwhile, boots games by opening paths.
//
// fs::device_base is the seam that reconciles those. Every fs:: entry point already begins by
// asking get_virtual_device whether the path belongs to one, so a device registered here is
// answering stat, open, opendir, rename, remove and the rest for free, with no change to any
// core file. The disc image overlay in rpcs3/Loader/ISO.cpp is the same idea applied to a
// container that also has no paths inside it.
namespace armsx3::saf
{
	// Registered name, and the prefix every path under this device carries.
	//
	// Both spellings are load-bearing rather than decorative. fs::get_virtual_device accepts a
	// path only if index 29 is an underscore, and fs::device_manager::get_device takes the
	// registered name to start after the first underscore at or past index 7. The iso overlay
	// device is spelled the way it is for the same reason. Keep in step with
	// ContentUri.DEVICE_PREFIX, which builds these paths on the Kotlin side.
	inline constexpr const char* device_name = "saf_storage_fs_dev";
	inline constexpr const char* device_prefix = "/vfsv0_virtual_saf_storage_fs_dev";

	// Cache the bridge and register the device. Must be called from a JNI upcall that started
	// in Java: a thread attached from native code gets the system class loader, which cannot
	// see app classes, so FindClass for ContentUri would fail there.
	void install(JNIEnv* env);

	bool is_device_path(std::string_view path);
}
