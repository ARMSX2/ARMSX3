// SPDX-FileCopyrightText: 2026 ZeroFG authors
// SPDX-License-Identifier: GPL-2.0-or-later

// ZeroFG's lines in RPCS3's log. VKZeroFG.cpp formats them with {fmt}, which cannot share a file
// with RPCS3's own fmt (Utilities/StrFmt.h), so the text crosses here already formatted.

#include "stdafx.h"

LOG_CHANNEL(zerofg_log, "ZeroFG");

void vk_zerofg_log(int level, const char* text)
{
	switch (level)
	{
	case 0: zerofg_log.error("%s", text); break;
	case 1: zerofg_log.warning("%s", text); break;
	// success, not notice: the Android build shows warnings, errors and successes in logcat but not
	// notices, and the connection / handoff lines are what tells a ZeroFG that armed from one that did not.
	case 2: zerofg_log.success("%s", text); break;
	default: zerofg_log.trace("%s", text); break;
	}
}
