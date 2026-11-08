// SPDX-License-Identifier: GPL-2.0-only

#include "rpcs3_ios_abi_internal.h"

using namespace armsx3::bridge::abi_internal;


// Thin wrappers over diag_log.cpp, callable before Rpcs3IOS_Initialize; the directory is never logged.
Rpcs3IOS_Status Rpcs3IOS_DiagStart(const char* dir, int32_t verbose)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_DiagStart");
	}

	if (dir == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (verbose != 0 && verbose != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	if (*dir == '\0' || !fs::is_dir(dir))
	{
		return RPCS3_IOS_STATUS_PATH_REJECTED;
	}

	armsx3::bridge::diag::start(dir, verbose != 0);

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_DiagStart finished with status %d.\n", static_cast<int>(RPCS3_IOS_STATUS_OK));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_DiagSetVerbose(int32_t verbose)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_DiagSetVerbose");
	}

	if (verbose != 0 && verbose != 1)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	armsx3::bridge::diag::set_verbose(verbose != 0);

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_DiagSetVerbose finished with status %d.\n", static_cast<int>(RPCS3_IOS_STATUS_OK));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_DiagSync(void)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_DiagSync");
	}

	armsx3::bridge::diag::sync();

	utils::stderr_witness_printf("ARMSX3: Rpcs3IOS_DiagSync finished with status %d.\n", static_cast<int>(RPCS3_IOS_STATUS_OK));
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetVersion(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetVersion");
	}

	if (out == nullptr)
	{
		return RPCS3_IOS_STATUS_NULL_ARGUMENT;
	}

	if (cap == 0)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	// diag_log.cpp's header line, so About and a problem report name the same build.
	const std::string version = fmt::format("ARMSX3 iOS | RPCS3 v%s", rpcs3::get_verbose_version());
	std::snprintf(out, cap, "%s", version.c_str());
	return RPCS3_IOS_STATUS_OK;
}

Rpcs3IOS_Status Rpcs3IOS_GetConfigDir(char* out, uint32_t cap)
{
	if (pthread_main_np() != 0)
	{
		return refuse_main_thread("Rpcs3IOS_GetConfigDir");
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

	// A truncated folder would name a different one, so it is refused rather than cut.
	const std::string& dir = fs::get_config_dir();
	if (dir.size() >= cap)
	{
		return RPCS3_IOS_STATUS_INVALID_ARGUMENT;
	}

	std::snprintf(out, cap, "%s", dir.c_str());
	return RPCS3_IOS_STATUS_OK;
}
