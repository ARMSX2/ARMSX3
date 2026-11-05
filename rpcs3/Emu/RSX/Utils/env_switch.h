#pragma once

#include <cstdlib>

namespace rsx
{
	// ARMSX3: environment switches for A/B testing the performance paths, set in driver_env.txt.
	//
	// armsx3-core applies driver_env.txt with setenv() after the library's static initialisers have
	// run, so callers cache the result in a function-local static, read on first use:
	//   static const bool s_enabled = rsx::env_switch("ARMSX3_X_NAME");
	//
	// ARMSX3_X_<NAME>=0 turns a path off and any other value turns it on. While the switch is unset
	// (or empty) the path takes default_value: on, unless its default depends on the platform or the
	// driver.
	inline bool env_switch(const char* name, bool default_value = true)
	{
		const char* value = std::getenv(name);
		if (!value || !value[0])
		{
			return default_value;
		}

		return value[0] != '0';
	}
}
