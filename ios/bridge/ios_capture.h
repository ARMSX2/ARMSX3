// SPDX-License-Identifier: GPL-2.0-only
// Pure C++, so ios_render.cpp can include it without reaching AVFoundation; the bodies are in ios_capture.mm.

#pragma once

namespace armsx3::bridge
{
	struct camera_source;
}

namespace armsx3::ios_capture
{
	// Reads the permission and never requests it: iOS asks on its own the first time a game captures.
	bool microphone_denied();

	void set_recording(bool on);

	// Reads the status and never requests it.
	bool camera_denied();

	// Its `start` asks for access the first time a game starts the camera, and never before.
	armsx3::bridge::camera_source camera_source();
}
