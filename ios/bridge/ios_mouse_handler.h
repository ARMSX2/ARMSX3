// SPDX-License-Identifier: GPL-2.0-only
// Stands in for the core's Qt and raw mouse handlers, which do not build here; MouseHandlerBase does the rest.

#pragma once

#include "Emu/Io/MouseHandler.h"

#include <algorithm>

class ios_mouse_handler final : public MouseHandlerBase
{
	using MouseHandlerBase::MouseHandlerBase;

public:
	void Init(const u32 max_connect) override
	{
		if (m_info.max_connect > 0)
		{
			// Already initialised.
			return;
		}

		m_mice.clear();
		m_mice.emplace_back(Mouse());

		m_info = {};
		m_info.max_connect = max_connect;
		m_info.now_connect = std::min(::size32(m_mice), max_connect);
		for (u32 i = 1; i < max_connect; i++)
		{
			m_info.status[i] = CELL_MOUSE_STATUS_DISCONNECTED;
			m_info.mode[i] = CELL_MOUSE_INFO_TABLET_MOUSE_MODE;
			m_info.tablet_is_supported[i] = CELL_MOUSE_INFO_TABLET_NOT_SUPPORTED;
		}
		m_info.status[0] = CELL_MOUSE_STATUS_CONNECTED;
		m_info.vendor_id[0] = 0x1234;
		m_info.product_id[0] = 0x1234;

		type = mouse_handler::basic;
	}
};
