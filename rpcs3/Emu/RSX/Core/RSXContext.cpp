#include "stdafx.h"
#include "Emu/RSX/Utils/algorithm.hpp"
#include "Emu/system_config.h"
#include "Emu/RSX/rsx_methods.h"
#include "RSXContext.h"

namespace rsx
{
	context::context(u32 id, thread* owner)
		: rsxthr(owner)
		, register_state(&method_registers)
		, m_id(id)
	{
	}

	context::~context() = default;
}
