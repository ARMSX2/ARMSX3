// SPDX-License-Identifier: GPL-2.0-only
// While the scene is inactive the frame reports a zero extent, the core's quiet minimized path; never before the first flip.
#pragma once

namespace armsx3::ios_frame
{
	inline bool gate_closed(bool scene_active, bool renderer_started)
	{
		return !scene_active && renderer_started;
	}

	inline int gated_extent(bool scene_active, bool renderer_started, int published)
	{
		if (published < 0)
			return published;

		return gate_closed(scene_active, renderer_started) ? 0 : published;
	}
}
