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
	case 2: zerofg_log.notice("%s", text); break;
	default: zerofg_log.trace("%s", text); break;
	}
}
