#pragma once

// Frame generation (Lossless Scaling) as seen by the renderer.
//
// The implementation lives in libarmsx3_lsfg.so and is reached by dlopen, never by linking. That
// is not a packaging preference: framegen statically links volk, which defines 655 globals named
// vkCreateImage, vkQueueSubmit, ... including all 124 that rpcs3/Emu/RSX/VK/vk_android_loader.h
// declares for our own renderer. Link them together and either the link fails, or the symbols
// merge and framegen's volkLoadDevice() silently repoints our entire renderer at framegen's
// VkDevice. See 3rdparty/lsfg/armsx3_lsfg_shim.h.
//
// Everything here is a no-op returning false off Android, or when the library is absent, or when
// the user has not supplied the shaders. Callers do not need to check the platform.

#include "util/types.hpp"
#include "VulkanAPI.h"

#include <string>
#include <vector>

// Forward-declared rather than including device.h: this header is pulled in by the present
// path, and device.h drags most of the Vulkan utility layer with it.
namespace vk
{
	class render_device;
	class command_buffer;
	class image;
}

namespace vk::frame_gen
{
	// Is the library present and its ABI one we understand?
	//
	// Cheap after the first call. A false here means frame generation is simply not on offer --
	// no library in the APK, wrong ABI, or a load failure -- and the caller should carry on
	// presenting normally.
	bool available();

	// Why available() said no, for the settings screen. Empty when it said yes.
	std::string unavailable_reason();

	// Bring framegen up on the adapter we are rendering with.
	//
	// device_uuid comes from VkPhysicalDeviceIDProperties. framegen creates its OWN VkDevice on
	// that adapter rather than sharing ours, which is why images have to be handed over as
	// AHardwareBuffer rather than VkImage.
	//
	// shader_for returns the SPIR-V for a named shader, or an empty vector if it has none.
	// framegen does not read Lossless.dll -- extracting the shaders from the user's own copy is
	// the caller's job, and without them this fails and frame generation stays off.
	bool initialize(u64 device_uuid, bool is_hdr, f32 flow_scale, u32 generated_frames,
		bool performance, std::vector<u8> (*shader_for)(const std::string& name, void* user), void* user);

	// True once initialize() has succeeded and contexts can be created.
	bool initialized();

	void shutdown();

	// Message from the last failed call, for logging. Never null.
	const char* last_error();

	// Read the shaders out of the user's own Lossless.dll.
	//
	// Frame generation cannot start without these and nothing ships them -- they are THS's
	// property and the user must supply a legitimately purchased copy. Only the translated SPIR-V
	// is kept; the file itself is not needed afterwards.
	//
	// Returns how many shaders were extracted, or a negative value on failure, in which case
	// last_error() carries a message meant for the user rather than for a log.
	int import_shaders(const std::string& dll_path);

	// How many shaders are held. Zero means frame generation will not start.
	int shader_count();

	// An image both devices can see.
	//
	// A plain device-local image. The passes run on our own device now, so there is no second
	// device to share with and no AHardwareBuffer round-trip -- the renderer blits the presented
	// frame in, and the interpolation passes read and write it directly.
	//
	// This is also why frame generation cannot be free. The presented frame is not in one of
	// these -- it is in a swapchain image -- so every frame has to be copied in, and every
	// generated frame copied back out.
	class shared_image
	{
	public:
		shared_image() = default;
		~shared_image();

		shared_image(const shared_image&) = delete;
		shared_image& operator=(const shared_image&) = delete;

		// Allocate the buffer and import it. False leaves the object empty and logs why.
		bool create(const vk::render_device& dev, u32 width, u32 height, VkFormat format);

		void destroy();

		bool valid() const { return m_image != VK_NULL_HANDLE; }

		// For our own command buffers.
		VkImage handle() const { return m_image; }

		// For framegen, which takes it as an opaque void*.
		/// The passes write through a view rather than blitting, so they need this as well as
		/// the image.
		VkImageView view() const { return m_view; }

		u32 width() const { return m_width; }
		u32 height() const { return m_height; }
		VkFormat format() const { return m_format; }

		// Tracked here rather than in vk::image because these live outside the renderer's normal
		// resource management -- nothing else knows they exist, so nothing else can track them.
		VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;

	private:
		VkImage m_image = VK_NULL_HANDLE;
		VkImageView m_view = VK_NULL_HANDLE;
		VkDeviceMemory m_memory = VK_NULL_HANDLE;
		VkDevice m_device = VK_NULL_HANDLE;
		u32 m_width = 0;
		u32 m_height = 0;
		VkFormat m_format = VK_FORMAT_UNDEFINED;
		VkImageLayout m_layout = VK_IMAGE_LAYOUT_UNDEFINED;
	};

	// Copy the frame about to be presented into a shared image.
	//
	// This is the price of frame generation before any frame is generated: framegen's device
	// cannot see a swapchain image, so every presented frame has to be copied into storage both
	// devices can reach, and every generated frame copied back out. Measuring it in isolation is
	// the point -- if the copies alone do not fit in the GPU's idle time, nothing built on top of
	// them will either, and that is worth knowing before the shader pipeline exists.
	//
	// src is the FINAL swapchain image -- game plus every overlay -- stated in whatever layout it
	// currently holds, and left in that same layout.
	//
	// Capturing the composited image rather than the game image is not a detail. Generated frames
	// are presented from framegen's output, so anything not in the captured image is missing from
	// them: capturing pre-overlay put the performance overlay on real frames only, and presenting
	// generated/real/generated/real made it blink at half the display rate.
	//
	// Returns false when frame generation is off, unsupported, or the shared images could not be
	// created; callers present normally either way.
	/// [guest_width]/[guest_height] are what the GAME rendered, before the upscale to the
	/// presentation size; the passes size their optical flow from the ratio between the two.
	bool capture_presented_frame(const vk::command_buffer& cmd, const vk::render_device& dev,
		VkImage src, VkImageLayout src_layout, u32 width, u32 height,
		u32 guest_width, u32 guest_height);

	// Promote the capture recorded this frame to one framegen is allowed to read.
	//
	// Call immediately after the command buffer holding the capture has been submitted, and only
	// then. capture_presented_frame() records a blit; it does not perform one, and framegen sits on
	// a second VkDevice with no semaphore joining the two, so a capture that has only been recorded
	// is a write that has not happened. Interpolating from it is what made the game image flicker
	// between real frames and half-written ones.
	void commit_capture();

	// Release the shared images. Safe to call when none exist.
	void release_shared_images();

	// How many frames the current setting asks us to generate between each real pair.
	// Zero when frame generation is off, unsupported, or has no shaders.
	u32 generated_frame_count();

	// Run framegen over the last two COMMITTED captures.
	//
	// Only meaningful after two captures. Returns the number of generated images now waiting in
	// generated_image(), which is zero on any failure -- and a failure disables frame generation
	// for the rest of the session rather than retrying every frame, because the causes (no
	// shaders, no device support, a context that would not create) do not fix themselves.
	//
	// This reads and writes AHardwareBuffers on framegen's device and returns only once that device
	// is idle again, so the caller must not have a submission in flight that touches any of them.
	// In practice that means: call it BEFORE submitting the command buffer that carries this frame's
	// capture, since that capture overwrites one of the two images being read. Doing it the other
	// way round is the race the caller used to pay a full frame-completion wait to avoid.
	u32 generate(const vk::render_device& dev);

	// Handle of generated image i, for blitting into a swapchain image.
	VkImage generated_image(u32 index);

	// Frames per second actually reaching the display, or 0 when frame generation is not running.
	//
	// Exists because the FPS counter cannot answer "is this working": it reports the rate the GAME
	// renders at, which frame generation deliberately leaves alone. The only visible difference is
	// how many frames get presented, so that is what this counts.
	f32 display_fps();

	// One line for the performance overlay, or empty when the user has not turned frame generation
	// on.
	//
	// Deliberately NOT just the FPS. Frame generation has several ways to be switched on and still
	// do nothing -- no library, no imported shaders, a failure that disabled it for the session --
	// and every one of them looked identical on screen to "off": no line. Reporting the reason is
	// the difference between a feature the user can fix and one that appears broken.
	std::string status_text();
}
