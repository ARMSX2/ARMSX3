// SPDX-FileCopyrightText: 2026 ZeroFG authors
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include "Emu/RSX/VK/VulkanAPI.h"

#include <string>

/// ZeroFG frame generation (https://github.com/hy300leosquizz-ctrl/ZeroFG) on its own Vulkan device.
/// Ported from ZeroFG's ARMSX2 adapter (GSZeroFG); "the GS device" below is the RSX renderer's
/// device (device A), and "the GS thread" the thread that presents (VKGSRender::flip).
///
/// ZeroFG needs an effective Vulkan 1.3 device with synchronization2. Rather than change the
/// renderer's device, ZeroFG creates its own (device B) on the same GPU and driver, from the
/// renderer's instance (raised to 1.3 while ZeroFG is on: device B presents on the renderer's
/// surface, and Android connects a window to one surface at a time). While ZeroFG is on the
/// renderer's device also enables what the handoff needs (AHardwareBuffers, sync files,
/// synchronization2); off, it is created exactly as without ZeroFG.
///
/// Each new frame is copied into an image the handoff publishes to device B, where the ZeroFG
/// Independent Presenter (built through the ZeroFG repository's host/portable kit) generates one
/// frame between every two, runs the Post (the frame into the present's draw rectangle) and
/// presents both, Real and generated, on the window: once it takes the window, the renderer drops
/// its swap chain and presents nothing (Main Surface Authority). The frame ZeroFG takes is the
/// composited one, the game with RPCS3's overlays already drawn on it, so the overlays reach every
/// output. A failure gives the window back to the renderer. Switched off or to another engine in
/// game, ZeroFG gives it back and takes it again.
///
/// Every entry point is a no-op when ARMSX3_HAS_ZEROFG is undefined (the play flavour, and every
/// desktop build), so call sites need no #ifdef of their own.
namespace VKZeroFG
{
	/// Asked by GSDeviceVK::SelectDeviceExtensions: whether the GS device should carry what the
	/// handoff needs from it (AHardwareBuffers, sync files, synchronization2). True only while
	/// ZeroFG is built in and switched on.
	bool WantsSourceHandoff();

	/// What the GS device enabled for the handoff: all of it, or nothing.
	void NoteSourceHandoff(bool enabled);
	bool SourceHandoffEnabled();

	/// The API version GSDeviceVK creates its instance with. Device B is created from the GS instance
	/// too, because it presents on the GS device's surface (Android lets one surface connect a window
	/// at a time), and it needs 1.3: [base] is raised to 1.3 while ZeroFG is switched on and the
	/// loader has it. The GS device itself uses nothing past [base].
	u32 InstanceApiVersion(u32 base);

	/// The GS device ZeroFG takes frames from (device A).
	struct SourceDevice
	{
		VkInstance instance = VK_NULL_HANDLE;
		u32 instance_api_version = 0;
		VkPhysicalDevice physical_device = VK_NULL_HANDLE;
		VkDevice device = VK_NULL_HANDLE;
		VkQueue queue = VK_NULL_HANDLE;
		u32 queue_family = 0;
		u32 api_version = 0;
	};

	/// Called once from GSDeviceVK after its device and swap chain came up, on the GS thread
	/// before it submits anything. When the user setting EmuCore/GS/ZeroFGEnabled is on, creates
	/// device B on the same GPU, the engine on it, and qualifies the handoff from [source]. Any
	/// failure leaves ZeroFG off and logs why; it never fails the GS device.
	void OnDeviceCreated(const SourceDevice& source);

	/// Releases the engine, device B and its instance. Safe to call when never brought up.
	void Shutdown();

	/// True while device B and the engine are up.
	bool IsActive();

	/// One line for the log and the overlay: what came up, or why ZeroFG is off.
	std::string GetStatusText();

	// ---- The window (Main Surface Authority) ------------------------------------------------------
	//
	// One producer at a time on the game's window. The GS device presents until ZeroFG's presenter
	// owns the output; then the GS device drops its swap chain but keeps its surface
	// (MainSurfaceHandoffPending), and device B presents on that surface, voting the panel's high
	// refresh. If the presenter fails it gives the window back (ReconnectRequested): the GS device
	// rebuilds its swap chain on the surface and keeps the window (FailOpen). All on the GS thread.

	/// The window, the GS device's surface on it and the swap chain the GS device has there.
	struct OutputSurface
	{
		void* window = nullptr; ///< ANativeWindow*
		VkSurfaceKHR surface = VK_NULL_HANDLE;
		u32 width = 0;
		u32 height = 0;
		VkFormat format = VK_FORMAT_UNDEFINED;
	};

	/// Offers the window to the presenter, after the GS device (re)created its swap chain on it.
	void ConnectSurface(const OutputSurface& output);
	/// Takes the presenter off the window, before the GS device drops its swap chain or the window
	/// goes away, or when ZeroFG is switched off in game. Waits for the work the window still has in
	/// flight.
	void DisconnectSurface();
	/// The presenter is on the window, whoever presents on it now.
	bool Connected();
	/// A presenter is up, off the window, and may be offered it (ZeroFG switched back on in game, or
	/// a new presenter after a fail-open).
	bool CanConnect();
	/// The engine mode was switched in game (GSConfig.ZeroFGMode): the GS device takes the presenter
	/// off the window, and the next ConnectSurface rebuilds the engine in the new mode.
	bool ModeChanged();

	/// The presenter owns what reaches the screen: the GS device must not present.
	bool OwnsOutput();
	/// ... and wants the window now: the GS device drops its swap chain, then says so.
	bool MainSurfaceHandoffPending();
	void MainSurfaceReleasedByA();
	/// The presenter gave the window back after a failure (or its surface went out of date).
	bool ReconnectRequested();
	/// Takes the failed presenter off the window and replaces it, so the next connection gets a new
	/// one; after too many failures on this device ZeroFG stays off it and the GS device presents alone.
	void FailOpen();

	// ---- The frames -----------------------------------------------------------------------------

	/// A new game frame was presented (taken by ZeroFG or not). The presenter learns the game's own
	/// cadence from these before it takes frames at all. GS thread.
	void NoteSourceFrame();

	/// The game frame about to be captured starts a new scene or screen (the GS draw signature changed: the textures of the frame share little with the
	/// frame before). The presenter then publishes it alone, with no synthetic across the break. GS thread.
	void NoteContentBreak();

	/// True while the presenter takes frames.
	bool WantsFrames();

	/// The game's frame of this present and where the present drew it.
	struct GameFrame
	{
		VkImage image = VK_NULL_HANDLE; ///< in TRANSFER_SRC_OPTIMAL (the caller transitions it)
		s32 src[4] = {};                ///< the visible area in it: left, top, right, bottom
		float draw[4] = {};             ///< where the present drew it, in output pixels
		bool linear = true;             ///< the present's filter: bilinear, else nearest
		u32 output_width = 0;           ///< the size of the output the draw rectangle is in
		u32 output_height = 0;
		/// ZeroGS: the frame's HUD planes (P drawn over black, W over white; GSDevice::GetZeroFGPlane), in
		/// TRANSFER_SRC_OPTIMAL, and the frame's visible area in them; null when the frame has none.
		VkImage hud_p = VK_NULL_HANDLE;
		VkImage hud_w = VK_NULL_HANDLE;
		s32 hud_src[4] = {};
	};

	/// Records the copy of [frame] into the image ZeroFG takes it from, at the size frames are generated
	/// at, into [cmd], the frame's own command buffer (where the game texture's layout is tracked).
	void CaptureGameFrame(VkCommandBuffer cmd, const GameFrame& frame);
	/// After that command buffer is submitted: hands the frame to the presenter. May wait while
	/// ZeroFG's buffers are all taken, which is ZeroFG's Source backpressure.
	void PublishCapturedFrame();

	/// The user's settings: ZeroFG switched on, and the engine mode (0 Zero, 1 ReallyZero; the
	/// ARMSX2 numbering, whose GS-informed modes have no RSX counterpart). Set before the device is
	/// created and on every present; read by the adapter where ARMSX2 read GSConfig.
	void SetUserSettings(bool enabled, int mode);

	/// ZeroFG's line in the performance overlay, beside LSFG's: what reaches the screen while it
	/// presents (Real and generated frames per second, over half-second windows), "starting" until
	/// it takes the window, "off" after it failed; empty while switched off. GS thread.
	std::string GetOverlayText();
} // namespace VKZeroFG
