#include "stdafx.h"
#include "VKGSRender.h"
#include "VKFrameGen.h"
#include "vkutils/buffer_object.h"
#include "vkutils/memory.h"
#include "Emu/RSX/Overlays/overlay_manager.h"
#include "Emu/RSX/Overlays/overlay_debug_overlay.h"
#include "Emu/Cell/Modules/cellVideoOut.h"
#include "Emu/RSX/rsx_profiler.h"
#include "vkutils/gpu_timer.h"

#include "upscalers/bilinear_pass.hpp"
#include "upscalers/fsr_pass.h"
#include "upscalers/nearest_pass.hpp"
#ifdef __ANDROID__
#include "upscalers/librashader_pass.h"
#endif
#include "util/asm.hpp"
#include "util/video_provider.h"

extern atomic_t<bool> g_user_asked_for_screenshot;
extern atomic_t<recording_mode> g_recording_mode;

namespace
{
	VkFormat RSX_display_format_to_vk_format(u8 format)
	{
		switch (format)
		{
		default:
			rsx_log.error("Unhandled video output format 0x%x", static_cast<s32>(format));
			[[fallthrough]];
		case CELL_VIDEO_OUT_BUFFER_COLOR_FORMAT_X8R8G8B8:
			return VK_FORMAT_B8G8R8A8_UNORM;
		case CELL_VIDEO_OUT_BUFFER_COLOR_FORMAT_X8B8G8R8:
			return VK_FORMAT_R8G8B8A8_UNORM;
		case CELL_VIDEO_OUT_BUFFER_COLOR_FORMAT_R16G16B16X16_FLOAT:
			return VK_FORMAT_R16G16B16A16_SFLOAT;
		}
	}
}

bool VKGSRender::reinitialize_swapchain()
{
	if (m_surface_lost)
	{
		// handle() blocks until the app hands us a live native window again, so
		// this parks here for as long as the user is away and resumes the moment
		// they come back.
		if (auto* wsi = dynamic_cast<vk::swapchain_WSI*>(m_swapchain.get()))
		{
			rsx_log.warning("Surface was lost; rebuilding it from the current native window.");

			// ORDER MATTERS. The swapchain has to be destroyed before the surface
			// it was built from -- Vulkan forbids destroying a surface that a live
			// swapchain still references, and doing it the other way round faulted
			// inside the driver on the very next present.
			wsi->destroy_swapchain_only();
			wsi->replace_surface(m_instance.recreate_surface(m_frame->handle()));
		}

		m_surface_lost = false;
	}

	m_swapchain_dims.width = m_frame->client_width();
	m_swapchain_dims.height = m_frame->client_height();
	m_display_epoch = m_frame->display_epoch();

	// A genuine rebuild re-arms the SUBOPTIMAL handling above: the latch exists to stop a rebuild
	// that changes nothing from repeating, not to disable the signal for the rest of the session.
	m_suboptimal_present_count = 0;

	// Reject requests to acquire new swapchain if the window is minimized
	// The NVIDIA driver will spam VK_ERROR_OUT_OF_DATE_KHR if you try to acquire an image from the swapchain and the window is minimized
	// However, any attempt to actually renew the swapchain will crash the driver with VK_ERROR_DEVICE_LOST while the window is in this state
	if (m_swapchain_dims.width == 0 || m_swapchain_dims.height == 0)
	{
		swapchain_unavailable = true;
		return false;
	}

	// NOTE: This operation will create a hard sync point
	if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[12]++; close_and_submit_command_buffer();
	m_current_command_buffer->reset();
	m_current_command_buffer->begin();

	for (auto &ctx : m_frame_context_storage)
	{
		if (ctx.present_image == umax)
			continue;

		// Release present image by presenting it
		frame_context_cleanup(&ctx);
	}

	// NOTE: frame_context_cleanup alters the queued_frames structure.
	while (!m_queued_frames.empty())
	{
		auto& frame = m_queued_frames.front();
		if (!frame->swap_command_buffer)
		{
			// Drop it
			m_queued_frames.pop_front();
			continue;
		}

		frame_context_cleanup(frame);
	}
	ensure(m_queued_frames.empty());

	// Discard the current upscaling pipeline if any
	m_upscaler.reset();

	// Drain all the queues
	vkDeviceWaitIdle(*m_device);

	// Reset frame context storage
	for (auto& ctx : m_frame_context_storage)
	{
		ctx.destroy(*m_device);
	}
	m_current_frame = nullptr;
	m_max_async_frames = 0;
	m_current_queue_index = 0;
	m_frame_context_storage.clear();

	// The frame frame generation was holding back lives in the storage just cleared, and its
	// swapchain image belongs to the swapchain about to be destroyed. There is nothing left to
	// present it to, so drop it rather than carry a pointer into freed contexts.
	m_deferred_present_frame = nullptr;
	m_framegen_blit_cb_count = 0;

	// Rebuild swapchain. Old swapchain destruction is handled by the init_swapchain call
	if (!m_swapchain->init(m_swapchain_dims.width, m_swapchain_dims.height))
	{
		rsx_log.warning("Swapchain initialization failed. Request ignored [%dx%d]", m_swapchain_dims.width, m_swapchain_dims.height);
		swapchain_unavailable = true;

		// Distinguish "no usable window right now" from "the VkSurfaceKHR is dead". Retrying
		// against a dead surface queries the same dead handle forever; the block at the top of
		// this function is what rebuilds it, and it only runs when this flag is set.
		if (auto* wsi = dynamic_cast<vk::swapchain_WSI*>(m_swapchain.get());
			wsi && wsi->surface_is_lost())
		{
			m_surface_lost = true;
		}

		return false;
	}

	// Adopt the size the swapchain came back with. The WSI backend prefers the surface's
	// currentExtent over the requested one, and every platform that reports an extent will
	// therefore hand back something we did not ask for the moment the window changes shape.
	//
	// m_swapchain_dims is not just bookkeeping: it sizes the framebuffer that the swapchain
	// image is attached to, and the present blit region. Leaving the requested value in it
	// draws a frame of one size into images of another, which on Android rotation is a
	// screenful of garbage that never recovers -- the mismatch is also what the resize check
	// in flip() compares against, so it re-confirms the stale size forever.
	if (m_swapchain->get_width() != m_swapchain_dims.width ||
		m_swapchain->get_height() != m_swapchain_dims.height)
	{
		rsx_log.notice("Swapchain: surface returned %dx%d for a %dx%d request.",
			m_swapchain->get_width(), m_swapchain->get_height(), m_swapchain_dims.width, m_swapchain_dims.height);

		m_swapchain_dims.width = m_swapchain->get_width();
		m_swapchain_dims.height = m_swapchain->get_height();
	}

	// Re-initialize CPU frame contexts
	m_max_async_frames = m_swapchain->get_swap_image_count();
	m_frame_context_storage.resize(m_max_async_frames);
	for (auto& ctx : m_frame_context_storage)
	{
		ctx.init(*m_device);
	}
	m_current_queue_index = 0;
	m_current_frame = &m_frame_context_storage[0];

	// Prepare new swapchain images for use
	for (u32 i = 0; i < m_swapchain->get_swap_image_count(); ++i)
	{
		const auto target_layout = m_swapchain->get_optimal_present_layout();
		const auto target_image = m_swapchain->get_image(i);
		VkClearColorValue clear_color{};
		VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

		vk::change_image_layout(*m_current_command_buffer, target_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
		vkCmdClearColorImage(*m_current_command_buffer, target_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear_color, 1, &range);
		vk::change_image_layout(*m_current_command_buffer, target_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, target_layout, range);
	}

	// Will have to block until rendering is completed
	vk::fence resize_fence(*m_device);

	// Flush the command buffer
	if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[13]++; close_and_submit_command_buffer(&resize_fence);
	vk::wait_for_fence(&resize_fence);

	m_current_command_buffer->reset();
	m_current_command_buffer->begin();

	swapchain_unavailable = false;
	should_reinitialize_swapchain = false;
	m_vsync_mode = g_cfg.video.vsync;
	return true;
}

// Put one generated image on screen.
//
// A self-contained acquire/blit/present that deliberately does NOT touch m_current_frame: that
// context belongs to the real frame still being assembled, and borrowing its image index or
// semaphores would present the same swapchain image twice.
//
// Best-effort throughout. A generated frame is an extra, so every failure path here simply
// returns and lets the real frame present normally -- dropping an interpolated frame is invisible,
// while stalling or presenting a torn one is not.
vk::command_buffer_chunk* VKGSRender::present_generated_frame(VkImage src)
{
	if (src == VK_NULL_HANDLE || swapchain_unavailable || m_swapchain->is_headless())
	{
		return nullptr;
	}

	u32 image = umax;

	// Zero timeout: if no swapchain image is free the display is already keeping up, and waiting
	// for one would make frame generation cost latency instead of adding smoothness.
	//
	// VK_SUBOPTIMAL_KHR has to be accepted, not treated as a failure. It is a SUCCESS code and the
	// image IS acquired -- bailing on it returned without presenting, so the image was never handed
	// back, and this driver reports SUBOPTIMAL as a standing condition rather than a one-off (see
	// the same handling in present() below). That leaked one swapchain image per generated frame
	// until the acquirable pool was empty, at which point the real frame's acquire in flip() blocks
	// its full 100ms timeout every frame -- a hard lock at ten fps that only a swapchain rebuild
	// clears, which is why it survived turning frame generation back off.
	const VkResult acquire_result =
		m_swapchain->acquire_next_swapchain_image(VK_NULL_HANDLE, 0ull, &image);

	if ((acquire_result != VK_SUCCESS && acquire_result != VK_SUBOPTIMAL_KHR) || image == umax)
	{
		return nullptr;
	}

	if (acquire_result == VK_SUBOPTIMAL_KHR)
	{
		// Worth acting on, but not from here: the rebuild happens on the real frame's path where
		// there is somewhere to recover to.
		should_reinitialize_swapchain = true;
	}

	auto* cmd = m_primary_cb_list.next();
	cmd->reset();
	cmd->begin();

	VkImage target = m_swapchain->get_image(image);

	const VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

	vk::change_image_layout(*cmd, target, VK_IMAGE_LAYOUT_UNDEFINED,
		VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);

	VkImageBlit region = {};
	region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
	region.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
	region.srcOffsets[1] = { s32(m_swapchain_dims.width), s32(m_swapchain_dims.height), 1 };
	region.dstOffsets[1] = { s32(m_swapchain_dims.width), s32(m_swapchain_dims.height), 1 };

	// Take ownership of the generated image before reading it, and say what layout it is really in.
	//
	// This blit used to name TRANSFER_SRC_OPTIMAL for an image that was never in that layout and
	// was never acquired from the device that wrote it. framegen releases its outputs with
	// newLayout = VK_IMAGE_LAYOUT_GENERAL and dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL
	// (framegen/v3.1_src/context.cpp), so reading it as TRANSFER_SRC with no acquire is a layout
	// mismatch and a missing ownership transfer at once -- undefined by the specification, and on
	// a tiler it reads whatever survived in cache rather than what framegen wrote. That is the
	// corrupted output seen whenever frame generation is switched on.
	//
	// Timing is already handled: generate() does not return until framegen's submission has
	// completed, so this is about visibility and ownership, not about waiting.
	VkImageMemoryBarrier acquire_src = {};
	acquire_src.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
	acquire_src.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
	acquire_src.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
	acquire_src.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
	acquire_src.dstQueueFamilyIndex = m_device->get_graphics_queue_family();
	acquire_src.image = src;
	acquire_src.subresourceRange = range;
	acquire_src.srcAccessMask = 0;
	acquire_src.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

	vkCmdPipelineBarrier(*cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
		0, 0, nullptr, 0, nullptr, 1, &acquire_src);

	vkCmdBlitImage(*cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_LINEAR);

	// Hand it back the way framegen expects to find it, or the next generation acquires an image
	// whose layout and owner no longer match what its own release barrier assumed.
	VkImageMemoryBarrier release_src = acquire_src;
	release_src.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
	release_src.newLayout = VK_IMAGE_LAYOUT_GENERAL;
	release_src.srcQueueFamilyIndex = m_device->get_graphics_queue_family();
	release_src.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
	release_src.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
	release_src.dstAccessMask = 0;

	vkCmdPipelineBarrier(*cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
		0, 0, nullptr, 0, nullptr, 1, &release_src);

	vk::change_image_layout(*cmd, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, range);

	cmd->end();

	// No semaphores either side. The source was already made visible by framegen's device idle,
	// and the present below has nothing to wait on because this command buffer is the only writer
	// of this swapchain image.
	vk::queue_submit_t submit_info{};
	submit_info.queue = m_device->get_graphics_queue();

	// flush, because the present below runs on this thread while a deferred submit would not have
	// happened yet under multithreaded RSX -- presenting a swapchain image before the blit that
	// fills it. VKGSRender::present avoids this the same way, with a flush of its own.
	cmd->submit(submit_info, VK_TRUE);

	// The result is not noise. On OUT_OF_DATE or SURFACE_LOST the image is NOT presented and is
	// leaked exactly as an unaccepted SUBOPTIMAL above leaks one, and none of the recovery flags
	// that present() sets would ever be raised, because generated frames never go through it.
	switch (const VkResult present_result = m_swapchain->present(VK_NULL_HANDLE, image))
	{
	case VK_SUCCESS:
		break;
	case VK_SUBOPTIMAL_KHR:
		should_reinitialize_swapchain = true;
		break;
	case VK_ERROR_OUT_OF_DATE_KHR:
		swapchain_unavailable = true;
		break;
	case VK_ERROR_SURFACE_LOST_KHR:
		m_surface_lost = true;
		swapchain_unavailable = true;
		break;
	default:
		rsx_log.error("Generated-frame present returned %lld; treating the swapchain as lost.",
			static_cast<s64>(present_result));
		swapchain_unavailable = true;
		break;
	}

	return cmd;
}

void VKGSRender::present(vk::frame_context_t *ctx)
{
	// Both of these are torn down by frame_context_cleanup, and frame generation's deferred
	// present is the one caller that can hold a context across the throttle that reclaims them.
	// The reclaim itself is prevented in advance_queued_frames, but a present owed by a context
	// that has already been recycled must not take the RSX thread down with it -- dropping the
	// present costs one frame on screen, and says so.
	if (ctx->present_image == umax || !ctx->swap_command_buffer)
	{
		rsx_log.error("Present requested for a frame context that was already retired; dropping it.");

		// Reset it on the way out, exactly as the normal exit below does.
		//
		// Leaving a stale index here is not harmless: the slot rotates back around and trips
		// ensure(m_current_frame->present_image == umax) in flip(), and reinitialize_swapchain
		// selects contexts by present_image != umax and then calls frame_context_cleanup, whose
		// first line asserts the command buffer this context no longer has. A dropped present
		// should cost one frame on screen, not a fatal two frames later.
		ctx->present_image = -1;
		return;
	}

	// Partial CS flush
	ctx->swap_command_buffer->flush();

	if (!swapchain_unavailable)
	{
		switch (VkResult error = m_swapchain->present(ctx->present_wait_semaphore, ctx->present_image))
		{
		case VK_SUCCESS:
			m_suboptimal_present_count = 0;
			break;
		case VK_SUBOPTIMAL_KHR:
#ifndef ANDROID
			should_reinitialize_swapchain = true;
#else
			// Android used to drop this signal entirely, which left no way back from a swapchain
			// the surface has outgrown.
			//
			// SUBOPTIMAL is what the driver reports when the swapchain no longer suits the surface
			// -- most often because its preTransform (forced to IDENTITY when the surface supports
			// it, see swapchain.cpp) disagrees with a currentTransform that has since rotated.
			// Opening a game the moment the app starts is how that happens: the swapchain is built
			// while the surface is still portrait and the landscape surface arrives a second later.
			// With this ignored, the only remaining recovery was VK_ERROR_OUT_OF_DATE_KHR, which is
			// what a MANUAL orientation change produces -- hence a black picture that the user could
			// only fix by rotating the device.
			//
			// It was ignored for a reason, though: some Android drivers report SUBOPTIMAL as a
			// standing condition, and rebuilding the swapchain on every frame that reports it would
			// be far worse than the problem. So act on it only once it has persisted, and only once
			// per surface size -- a rebuild that does not clear the condition must not be retried
			// forever.
			if (++m_suboptimal_present_count > 8 && m_suboptimal_handled_at != m_swapchain_dims.width)
			{
				m_suboptimal_handled_at = m_swapchain_dims.width;
				m_suboptimal_present_count = 0;
				should_reinitialize_swapchain = true;

				rsx_log.warning("Swapchain reported SUBOPTIMAL persistently at %ux%u; rebuilding.",
					m_swapchain_dims.width, m_swapchain_dims.height);
			}
#endif
			break;
		case VK_ERROR_OUT_OF_DATE_KHR:
			swapchain_unavailable = true;
			break;
		case VK_ERROR_SURFACE_LOST_KHR:
			// See the acquire path: the window went away, so the surface has to be
			// rebuilt before the swapchain can be.
			swapchain_unavailable = true;
			m_surface_lost = true;
			break;
		default:
			// Other errors not part of rpcs3. This can be caused by 3rd party injectors with bad code, of which we have no control over.
			// Let the application attempt to recover instead of crashing outright.
			rsx_log.error("VkPresent returned unexpected error code %lld. Will attempt to recreate the swapchain. Please disable 3rd party injector tools.", static_cast<s64>(error));
			swapchain_unavailable = true;
			break;
		}
	}

	// Presentation image released; reset value
	ctx->present_image = -1;
}

void VKGSRender::advance_queued_frames()
{
	// Reclaiming finished frames happens in the throttle below, not here. This call pokes the
	// oldest frame's fence, and a poke blocks on this driver, so running it before the frame's
	// own bookkeeping just moved the same GPU sync a few lines earlier.

	// Run video memory balancer
	m_device->rebalance_memory_type_usage();
	vk::vmm_check_memory_usage();

	// m_rtts storage is double buffered and should be safe to tag on frame boundary
	m_rtts.trim(*m_current_command_buffer, vk::vmm_determine_memory_load_severity());

	// Texture cache is also double buffered to prevent use-after-free
	m_texture_cache.on_frame_end();
	m_samplers_dirty.store(true);

	vk::remove_unused_framebuffers();

	m_vertex_cache->purge();
	m_current_frame->tag_frame_end();

	// Throttle here rather than by accident.
	//
	// The queue used to stay at one entry because the poll above blocked until the oldest
	// frame had finished, so the pipeline was bounded by never actually having one. Now that
	// the poll returns honestly, frames accumulate until the GPU retires them, and the
	// rotation below would eventually hand out a context that is still in flight.
	//
	// Wait only when the pipeline is genuinely full, which is the one moment a wait is worth
	// what it costs. It is charged to swap_wait, so what used to hide in a status query is
	// now visible as the frame pacing it always was.
	//
	// One below the context count, not equal to it. The queue and the rotation below advance
	// in the same order, so the frame this retires is the one the rotation is about to hand
	// out. Allowing the full count would hand out a context that is still in flight and push
	// every frame down the single aux context borrow path, which has room for one such frame
	// and an ensure() waiting for the second.
	// Pipelining costs memory: a second frame in flight means a second frame's resources are
	// alive before anything retires them. That is worth it at rest and not worth a fatal
	// VK_ERROR_OUT_OF_DEVICE_MEMORY, which on this GPU means system memory, shared with
	// everything else on a handheld. Under pressure, fall back to the single frame this used
	// to run with: slower, and slower is recoverable.
	const u32 depth_limit = (vk::vmm_determine_memory_load_severity() > rsx::problem_severity::low)
		? 2u
		: std::max(m_max_async_frames, 2u);

	const u32 max_frames_in_flight = depth_limit - 1u;

	while (m_queued_frames.size() >= max_frames_in_flight)
	{
		auto frame = m_queued_frames.front();

		// A frame frame generation is holding back has NOT been presented yet, and it still owns
		// the swapchain image it acquired. Reclaiming its context here tears down the two things
		// the outstanding present needs -- present_image and swap_command_buffer -- so the present
		// that comes due next frame dereferences a command buffer that is gone, and the acquired
		// image is never handed back to the swapchain either.
		//
		// That is the crash from turning frame generation on mid-game: the deferral begins, this
		// throttle reclaims the deferred context on the very next frame (max_frames_in_flight is
		// only 1 once the memory-pressure path drops depth_limit to 2), and the following
		// queue_swap_request presents it. Flush the deferral instead -- presenting a frame slightly
		// early costs one interpolated frame's ordering, which is invisible; the alternative is a
		// dead RSX thread.
		if (frame == m_deferred_present_frame)
		{
			present(m_deferred_present_frame);
			m_deferred_present_frame = nullptr;
		}

		if (!frame->swap_command_buffer)
		{
			m_queued_frames.pop_front();
			continue;
		}

		frame_context_cleanup(frame);
	}

	m_queued_frames.push_back(m_current_frame);
	ensure(m_queued_frames.size() <= m_max_async_frames);

	m_current_queue_index = (m_current_queue_index + 1) % m_max_async_frames;
	m_current_frame = &m_frame_context_storage[m_current_queue_index];
	m_current_frame->flags |= frame_context_state::dirty;

	vk::advance_frame_counter();
}

void VKGSRender::queue_swap_request()
{
	ensure(!m_current_frame->swap_command_buffer);
	m_current_frame->swap_command_buffer = m_current_command_buffer;

	const u32 framegen_frames = vk::frame_gen::generated_frame_count();

	if (!framegen_frames)
	{
		// Forget the recorded blits rather than leave them to be waited on if the setting comes back
		// on: the command buffers come from a ring and will have been reused by then, so the wait
		// would be for unrelated work.
		m_framegen_blit_cb_count = 0;
	}

	// Does this swapchain have room to run frame generation a frame behind?
	//
	// The pipelined path holds the real frame back by one present so the generated image can go out
	// ahead of it, which means one more image in flight than normal presentation needs: the one
	// being composited into, the one held back, and one for the generated frame. Below that,
	// present_generated_frame's zero-timeout acquire would fail every frame and frame generation
	// would compute images nothing ever puts on screen -- worse than the serialised path it
	// replaces, and silently so. So fall back rather than degrade.
	// Pipelining is OFF, and this is the switch rather than a revert.
	//
	// Holding the real frame back by one present conflicts with how frame contexts are recycled.
	// They are a fixed array, and the throttle in advance_queued_frames retires the deferred one
	// before the present it still owes -- first as a null swap_command_buffer dereference on the
	// RSX thread, then, once present() was taught to drop a retired context, as a lockup: the
	// dropped present never hands its acquired swapchain image back and acquire eventually starves.
	// Presenting the deferred frame before the throttle can reclaim it closed one of those paths,
	// but "already retired" still fired twice on device, so at least one more reclaim path exists
	// and has not been found.
	//
	// Everything else the pipelined work brought stays live and is worth keeping: the AHB teardown
	// ordering (release_shared_images used to free the input buffers while framegen's context still
	// held them imported -- a use-after-free on any resize with frame generation on), the leaked
	// g_shared_out[2] when dropping x4 to x2, and the recorded-vs-committed capture split.
	//
	// Flip this to true to resume that work; the serialised path below is what shipped working.
	constexpr bool k_framegen_pipelining_enabled = false;

	const bool pipelined = k_framegen_pipelining_enabled && framegen_frames != 0 &&
		!m_swapchain->is_headless() && m_swapchain->get_swap_image_count() >= 4;

	if (pipelined != m_framegen_pipelined)
	{
		m_framegen_pipelined = pipelined;

		if (framegen_frames)
		{
			rsx_log.notice("Frame generation: %s (swapchain has %u images)",
				pipelined ? "pipelined a frame behind" : "serialised, swapchain too shallow to pipeline",
				m_swapchain->get_swap_image_count());
		}
	}

	// Every generation reuses the same output images, and the blits that read the previous set were
	// submitted a frame ago. This is the cheapest place to make that reuse safe: the work is a frame
	// old, so in the steady state the fence is already signalled and this costs a fence poll.
	auto retire_generated_blits = [this]()
	{
		for (u32 i = 0; i < m_framegen_blit_cb_count; ++i)
		{
			m_framegen_blit_cb[i]->wait(FRAME_PRESENT_TIMEOUT);
		}

		m_framegen_blit_cb_count = 0;
	};

	u32 generated = 0;

	if (m_framegen_pipelined)
	{
		// Generate BEFORE this frame is submitted, from the pair of captures that have already
		// retired.
		//
		// This is the whole point of running a frame behind. framegen reads the shared images on its
		// own VkDevice with no semaphore joining it to ours, so it can only be given captures whose
		// submission has completed -- and the way that used to be arranged was to submit this frame
		// and then block on it, which put a full frame of GPU work on the critical path before
		// framegen had even started. Here the newest usable capture is the one from the previous
		// frame, which the game has had a whole frame to finish, so the wait below is normally
		// already satisfied. Waiting for that one covers the other half of the pair as well: it was
		// submitted to the same queue a frame earlier, and this renderer already treats work on the
		// graphics queue as retiring in submission order -- frame_context_cleanup walks the queued
		// frames oldest-first on exactly that basis.
		//
		// It also has to happen before the submit rather than after: this frame's command buffer
		// carries a capture that overwrites one of the two images framegen is about to read, and
		// with only two of them there is no slot that is not being read. See VKFrameGen.cpp for why
		// a third one cannot exist.
		if (m_deferred_present_frame && m_deferred_present_frame->swap_command_buffer)
		{
			m_deferred_present_frame->swap_command_buffer->wait(FRAME_PRESENT_TIMEOUT);
		}

		retire_generated_blits();
		generated = vk::frame_gen::generate(*m_device);
	}

	if (m_swapchain->is_headless())
	{
		m_swapchain->end_frame(*m_current_command_buffer, m_current_frame->present_image);
		if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[14]++; close_and_submit_command_buffer();
	}
	else
	{
		if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[15]++; close_and_submit_command_buffer(nullptr,
			m_current_frame->acquire_signal_semaphore,
			m_current_frame->present_wait_semaphore,
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
	}

	// The capture recorded during flip() is only now a write that has happened.
	//
	// Nothing may interpolate from it before this point, and framegen has no way to find out on its
	// own: it is a second VkDevice reading the same AHardwareBuffer, with no semaphore between them.
	vk::frame_gen::commit_capture();

	if (framegen_frames && !m_framegen_pipelined)
	{
		// Serialised fallback, for a swapchain with no room to hold a frame back.
		//
		// framegen reads the captured frame on ITS OWN device, so the only thing that can guarantee
		// our capture blit has actually landed is waiting for the submission that contains it --
		// this frame's, which was submitted moments ago, so this is a full frame of GPU work on the
		// critical path. That is the cost the pipelined path above exists to avoid.
		//
		// Without the wait the read races the write. It went unnoticed while the capture sat early
		// in the command buffer, with the whole output pipeline behind it as accidental slack;
		// moving the capture to the end of the frame closed that gap and the game image started
		// flickering between real and half-written interpolations. The overlay did not, because it
		// is drawn last and was therefore the one part reliably present in the copy.
		if (m_current_frame->swap_command_buffer)
		{
			m_current_frame->swap_command_buffer->wait(FRAME_PRESENT_TIMEOUT);
		}

		retire_generated_blits();
		generated = vk::frame_gen::generate(*m_device);
	}

	// Present the generated frames before the real one.
	//
	// This is what makes frame generation visible rather than merely computed: each generated
	// image gets its own acquire, blit and present, so the display sees more frames than the game
	// drew. Before the real frame because the interpolated image sits BETWEEN the previous frame
	// and this one -- presenting it afterwards would run time backwards.
	//
	// Which real frame that is differs by path, and it is the reason the pipelined one holds a frame
	// back at all: it interpolates the pair ending at the PREVIOUS frame, so the frame the generated
	// image leads into is the previous one, not this one. Presenting this frame here and the
	// generated image before it would show the interpolation after the frame it was interpolated
	// towards, which is time running backwards by half a frame, every frame.
	//
	// Everything is gated on generate() having succeeded, and any failure inside it disables the
	// feature for the session rather than retrying per frame. If nothing was generated this is a
	// single integer test and presentation is untouched.
	for (u32 i = 0; i < generated; ++i)
	{
		auto* cb = present_generated_frame(vk::frame_gen::generated_image(i));

		// Null when the acquire found no free swapchain image, which is a dropped generated frame
		// and nothing more. Only a blit that actually happened has to be waited for.
		if (cb && m_framegen_blit_cb_count < std::size(m_framegen_blit_cb))
		{
			m_framegen_blit_cb[m_framegen_blit_cb_count++] = cb;
		}
	}

	if (m_framegen_pipelined)
	{
		// Hand this frame's present to the next call and put the held-back one on screen now.
		vk::frame_context_t* const to_present = m_deferred_present_frame;
		m_deferred_present_frame = m_current_frame;

		if (to_present)
		{
			present(to_present);
		}
	}
	else
	{
		// A frame held back by the pipelined path has to go out before this one, or it is stranded
		// holding an acquired swapchain image that is never presented.
		if (m_deferred_present_frame)
		{
			present(m_deferred_present_frame);
			m_deferred_present_frame = nullptr;
		}

		// Set up a present request for this frame as well
		present(m_current_frame);
	}

	// Grab next cb in line and make it usable
	m_current_command_buffer = m_primary_cb_list.next();
	m_current_command_buffer->reset();
	m_current_command_buffer->begin();

	// Open the frame region here, on the path every frame actually takes.
	//
	// It was only opened at device init and in flush_command_queue, and this title triggers
	// flush_command_queue about zero times a frame, so the region opened once at boot, closed
	// on the first submit and never reopened. Everything downstream depends on it: the slot's
	// query range is reset when this region opens, the ring only advances once a slot has a
	// completed region in it, and collection refuses a slot that was never reset. So the timer
	// reported nothing at all for the whole session while looking perfectly healthy.
	vk::get_gpu_timer().begin(*m_current_command_buffer, vk::gpu_timer::region::frame);

	// Restart CPU pass numbering HERE, where the GPU timer's slot actually rotates, so an
	// ordinal names the same pass on both sides. It used to reset in tick_frame, which runs
	// from on_frame_end -- before flip -- while flip's own overlay and calibration passes went
	// on incrementing it and were dropped GPU-side. The CPU ordinal therefore ran ahead by the
	// number of present-path passes and the two by-pass tables described different passes.
	//
	// This site rather than next_frame(): queue_swap_request has one call site, so the mid-frame
	// flush_command_queue reopen cannot falsely restart numbering, and flip's passes land after
	// the reset where they belong rather than taking ordinals 0..k-1.
	rsx::prof::g_pass_ordinal = umax;

	// Set up new pointers for the next frame
	advance_queued_frames();
}

void VKGSRender::frame_context_cleanup(vk::frame_context_t *ctx)
{
	ensure(ctx->swap_command_buffer);

	// Pay any present this context still owes, before anything recycles it.
	//
	// Frame generation's pipelined path holds one frame back by a present, and this function is
	// how EVERY reclaim path destroys a context -- there are six call sites. The guard written for
	// this originally sat in advance_queued_frames and covered exactly one of them.
	// check_present_status() is the other function that walks m_queued_frames, it had no check at
	// all, and flush_command_queue calls it unconditionally on its way out -- so any of the twenty
	// or so flushes in a frame could retire the held-back frame, and flush_command_queue(true)
	// drains the queue outright and did so every time. That is the "already retired" report.
	//
	// Doing it here rather than at each call site is the point: a seventh reclaim path added later
	// cannot miss it. It has to run before swap_command_buffer is nulled below, because present()
	// flushes it, and the pointer is cleared first so a present that re-enters cannot loop.
	if (ctx == m_deferred_present_frame)
	{
		m_deferred_present_frame = nullptr;
		present(ctx);
	}

	if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_frame_cleanups++;

	// Perform hard swap here
	{
		// Split from the reclaim below because the two mean opposite things: time here is the
		// RSX thread blocked on the GPU, time below is the RSX thread doing CPU work. Both
		// were unscoped and landed in whatever called this.
		RSX_PROF_SCOPE(swap_wait);

		if (ctx->swap_command_buffer->wait(FRAME_PRESENT_TIMEOUT) != VK_SUCCESS)
		{
			// Lost surface/device, release swapchain
			swapchain_unavailable = true;
		}
	}

	// Resource cleanup.
	{
		RSX_PROF_SCOPE(res_trim);

		if (m_overlay_manager && m_overlay_manager->has_dirty())
		{
			auto ui_renderer = vk::get_overlay_pass<vk::ui_overlay_renderer>();
			m_overlay_manager->lock_shared();

			std::vector<u32> uids_to_dispose;
			uids_to_dispose.reserve(m_overlay_manager->get_dirty().size());

			for (const auto& view : m_overlay_manager->get_dirty())
			{
				ui_renderer->remove_temp_resources(view->uid);
				uids_to_dispose.push_back(view->uid);
			}

			m_overlay_manager->unlock_shared();
			m_overlay_manager->dispose(uids_to_dispose);
		}

		vk::get_resource_manager()->trim();

		vk::reset_global_resources();

		if (ctx->last_frame_sync_time > m_last_heap_sync_time)
		{
			m_last_heap_sync_time = ctx->last_frame_sync_time;

			// Heap cleanup; deallocates memory consumed by the frame if it is still held
			vk::data_heap_manager::restore_snapshot(ctx->heap_snapshot);
		}
	}

	ctx->swap_command_buffer = nullptr;

	// Remove from queued list
	while (!m_queued_frames.empty())
	{
		auto frame = m_queued_frames.front();
		m_queued_frames.pop_front();

		if (frame == ctx)
		{
			break;
		}
	}

	vk::advance_completed_frame_counter();
}

vk::viewable_image* VKGSRender::get_present_source(/* inout */ vk::present_surface_info* info, const rsx::avconf& avconfig)
{
	vk::viewable_image* image_to_flip = nullptr;

	// @FIXME: This entire function needs to be rewritten to go through the texture cache's "upload_texture" routine.
	// That method is not a 1:1 replacement due to handling of insets that is done differently here.

	// Check the surface store first
	const auto format_bpp = rsx::get_format_block_size_in_bytes(info->format);
	const auto overlap_info = m_rtts.get_merged_texture_memory_region(*m_current_command_buffer,
		info->address, info->width, info->height, info->pitch, format_bpp, rsx::surface_access::transfer_read);

	if (!overlap_info.empty())
	{
		const auto& section = overlap_info.back();
		auto surface = vk::as_rtt(section.surface);
		bool viable = false;

		if (section.base_address >= info->address)
		{
			const auto surface_width = surface->get_surface_width<rsx::surface_metrics::samples>();
			const auto surface_height = surface->get_surface_height<rsx::surface_metrics::samples>();

			if (section.base_address == info->address)
			{
				// Check for fit or crop
				viable = (surface_width >= info->width && surface_height >= info->height);
			}
			else
			{
				// Check for borders and letterboxing
				const u32 inset_offset = section.base_address - info->address;
				const u32 inset_y = inset_offset / info->pitch;
				const u32 inset_x = (inset_offset % info->pitch) / format_bpp;

				const u32 full_width = surface_width + inset_x + inset_x;
				const u32 full_height = surface_height + inset_y + inset_y;

				viable = (full_width == info->width && full_height == info->height);
			}

			if (viable)
			{
				image_to_flip = section.surface->get_surface(rsx::surface_access::transfer_read);

				std::tie(info->width, info->height) = rsx::apply_resolution_scale<true>(
					resolution_scaling_config,
					std::min(surface_width, info->width),
					std::min(surface_height, info->height));
			}
		}
	}
	else if (auto surface = m_texture_cache.find_texture_from_dimensions<true>(info->address, info->format);
			 surface && surface->get_width() >= info->width && surface->get_height() >= info->height)
	{
		// Hack - this should be the first location to check for output
		// The render might have been done offscreen or in software and a blit used to display
		image_to_flip = dynamic_cast<vk::viewable_image*>(surface->get_raw_texture());
	}

	// The correct output format is determined by the AV configuration set in CellVideoOutConfigure by the game.
	// 99.9% of the time, this will match the backbuffer fbo format used in rendering/compositing the output.
	// But in some cases, let's just say some devs are creative.
	const auto expected_format = RSX_display_format_to_vk_format(avconfig.format);

	if (!image_to_flip) [[ unlikely ]]
	{
		// Read from cell
		const auto range = utils::address_range32::start_length(info->address, info->pitch * info->height);
		const u32  lookup_mask = rsx::texture_upload_context::blit_engine_dst | rsx::texture_upload_context::framebuffer_storage;
		const auto overlap = m_texture_cache.find_texture_from_range<true>(range, 0, lookup_mask);

		for (const auto & section : overlap)
		{
			if (!section->is_synchronized())
			{
				section->copy_texture(*m_current_command_buffer, true);
			}
		}

		if (m_current_command_buffer->flags & vk::command_buffer::cb_has_dma_transfer)
		{
			// Submit for processing to lower hard fault penalty
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[16]++; flush_command_queue();
		}

		m_texture_cache.invalidate_range(*m_current_command_buffer, range, rsx::invalidation_cause::read);
		image_to_flip = m_texture_cache.upload_image_simple(*m_current_command_buffer, expected_format, info->address, info->width, info->height, info->pitch);
	}
	else if (image_to_flip->format() != expected_format)
	{
		// Devs are being creative. Force-cast this to the proper pixel layout.
		auto dst_img = m_texture_cache.create_temporary_subresource_storage(
			RSX_FORMAT_CLASS_COLOR, expected_format, info->width, info->height, 1, 1, 1,
			VK_IMAGE_TYPE_2D, 0, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

		if (dst_img)
		{
			const areai src_rect = { 0, 0, static_cast<int>(info->width), static_cast<int>(info->height) };
			const areai dst_rect = src_rect;

			dst_img->change_layout(*m_current_command_buffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

			if (vk::formats_are_bitcast_compatible(dst_img.get(), image_to_flip))
			{
				vk::copy_image(*m_current_command_buffer, image_to_flip, dst_img.get(), src_rect, dst_rect, 1);
			}
			else
			{
				vk::copy_image_typeless(*m_current_command_buffer, image_to_flip, dst_img.get(), src_rect, dst_rect, 1);
			}

			image_to_flip = dst_img.get();
			m_texture_cache.dispose_reusable_image(dst_img);
		}
	}

	return image_to_flip;
}

void VKGSRender::flip(const rsx::display_flip_info_t& info)
{
	// GPU timing is driven from the flip, the only place that corresponds to a real
	// presented frame. collect() is non-blocking: anything the GPU has not finished is
	// left for a later call rather than waited on.
	if (g_cfg.video.rsx_profiler)
	{
		auto& timer = vk::get_gpu_timer();
		timer.next_frame();
		timer.collect();

		// A collector that harvests nothing prints nothing, which reads exactly like a GPU
		// doing nothing. Say so periodically instead, with enough state to tell which of the
		// collection preconditions is the one not being met.
		if (timer.collected_frames() < 300 && timer.flips() && (timer.flips() % 300) == 0)
		{
			rsx_log.warning("[gpu_timer] no report yet: %s", timer.debug_state());
		}

		if (timer.collected_frames() >= 300)
		{
			const auto ms = timer.per_frame_ms();
			const auto counts = timer.event_counts();
			std::string report = fmt::format("GPU profile over %u frames", timer.collected_frames());

			for (u32 i = 0; i < vk::gpu_timer::region_count; i++)
			{
				if (ms[i] <= 0.0) continue;
				fmt::append(report, "\n\t%-14s %7.3f ms/frame  %5.1f events/frame",
					vk::gpu_timer::name_of(static_cast<vk::gpu_timer::region>(i)), ms[i],
					static_cast<double>(counts[i]) / static_cast<double>(timer.collected_frames()));
			}

			// Per-pass, so the draw total stops being a single number that only says "inside
			// render passes". One pass carrying most of it is a target; thirty even ones mean
			// the pass and draw count is the wall.
			if (const auto passes = timer.draw_pass_costs(); !passes.empty())
			{
				std::string list;
				for (u32 i = 0; i < passes.size() && i < 8; i++)
				{
					fmt::append(list, "\n\t  pass #%-3u %7.3f ms/frame  seen %llu",
						passes[i].ordinal, passes[i].ms_per_frame, passes[i].samples);
				}

				fmt::append(report, "\n\tdraw by pass (%u distinct)%s", ::size32(passes), list);
			}

			if (const u64 dropped = timer.dropped_events())
			{
				// Untimed events mean the regions below are an underestimate, so say so
				// rather than let the numbers read as complete.
				fmt::append(report, "\n\t%llu events went untimed (per-frame cap reached)", dropped);
			}

			// The texture cache's own view of the same frames. Readbacks per frame have
			// stayed near seven under load despite the eager copy, and these separate the
			// possible reasons: speculations are copies made before the guest asked, misses
			// are sections faulted on regardless, and unavoidable hard faults are the ones
			// upstream considers unpredictable because they are flush_always.
			fmt::append(report, "\n\ttexture cache   %u flushes, %u misses, %u speculations, %u hard faults (this frame)",
				m_texture_cache.get_num_flush_requests(),
				m_texture_cache.get_num_cache_misses(),
				m_texture_cache.get_num_cache_speculative_writes(),
				m_texture_cache.get_num_unavoidable_hard_faults());

			rsx_log.success("%s", report);
			timer.reset();
		}
	}

	// Check swapchain condition/status
	if (!m_swapchain->supports_automatic_wm_reports())
	{
		if (m_swapchain_dims.width != m_frame->client_width() + 0u ||
			m_swapchain_dims.height != m_frame->client_height() + 0u)
		{
			swapchain_unavailable = true;
		}
	}

	// A replacement window is not a resize, and the size check above cannot see one.
	//
	// Opening a game the instant the app starts swaps the SurfaceView under a swapchain that has
	// already been built, at identical dimensions -- so nothing above fired and the renderer went
	// on presenting into a window that was no longer composited. That is the black screen that
	// "fixed itself" on rotation: rotating changes the size, which is the trigger that did exist.
	if (m_display_epoch != m_frame->display_epoch())
	{
		swapchain_unavailable = true;
	}

	if (m_vsync_mode != g_cfg.video.vsync)
	{
		swapchain_unavailable = true;
	}

	if (swapchain_unavailable || should_reinitialize_swapchain)
	{
		// Reinitializing the swapchain is a failable operation. However, not all failures are fatal (e.g minimized window).
		// In the worst case, we can have the driver refuse to create the swapchain while we already deleted the previous one.
		// In such scenarios, we have to retry a few times before giving up as we cannot proceed without a swapchain.
		for (int i = 0; i < 10; ++i)
		{
			if (reinitialize_swapchain() || m_current_frame)
			{
				// If m_current_frame exists, then the initialization failure is non-fatal. Proceed as usual.
				break;
			}

			if (Emu.IsStopped())
			{
				m_frame->flip(m_context);
				rsx::thread::flip(info);
				return;
			}

			std::this_thread::sleep_for(100ms);
		}
	}

	m_profiler.start();

	ensure(m_current_frame, "Invalid swapchain setup. Resizing the game window failed.");

	if (m_current_frame == &m_aux_frame_context)
	{
		m_current_frame = &m_frame_context_storage[m_current_queue_index];
		if (m_current_frame->swap_command_buffer)
		{
			// Its possible this flip request is triggered by overlays and the flip queue is in undefined state
			frame_context_cleanup(m_current_frame);
		}

		// Swap aux storage and current frame; aux storage should always be ready for use at all times
		m_current_frame->grab_resources(m_aux_frame_context);
	}
	else if (m_current_frame->swap_command_buffer)
	{
		if (info.stats.draw_calls > 0)
		{
			// This can be 'legal' if the window was being resized and no polling happened because of swapchain_unavailable flag
			rsx_log.error("Possible data corruption on frame context storage detected");
		}

		// There were no draws and back-to-back flips happened
		frame_context_cleanup(m_current_frame);
	}

	if (info.skip_frame || swapchain_unavailable)
	{
		if (!info.skip_frame)
		{
			ensure(swapchain_unavailable);

			// Perform a mini-flip here without invoking present code
			m_current_frame->swap_command_buffer = m_current_command_buffer;
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[17]++; flush_command_queue(true);
			vk::advance_frame_counter();
			frame_context_cleanup(m_current_frame);
		}

		m_frame->flip(m_context);
		rsx::thread::flip(info);
		return;
	}

	u32 buffer_width = display_buffers[info.buffer].width;
	u32 buffer_height = display_buffers[info.buffer].height;
	u32 buffer_pitch = display_buffers[info.buffer].pitch;

	u32 av_format;
	const auto& avconfig = g_fxo->get<rsx::avconf>();

	if (!buffer_width)
	{
		buffer_width = avconfig.resolution_x;
		buffer_height = avconfig.resolution_y;
	}

	if (avconfig.state)
	{
		av_format = avconfig.get_compatible_gcm_format();
		if (!buffer_pitch)
			buffer_pitch = buffer_width * avconfig.get_bpp();

		const size2u video_frame_size = avconfig.video_frame_size();
		buffer_width = std::min(buffer_width, video_frame_size.width);
		buffer_height = std::min(buffer_height, video_frame_size.height);
	}
	else
	{
		av_format = CELL_GCM_TEXTURE_A8R8G8B8;
		if (!buffer_pitch)
			buffer_pitch = buffer_width * 4;
	}

	// Scan memory for required data. This is done early to optimize waiting for the driver image acquire below.
	vk::viewable_image* image_to_flip = nullptr;
	vk::viewable_image* image_to_flip2 = nullptr;

	if (info.buffer < display_buffers_count && buffer_width && buffer_height)
	{
		vk::present_surface_info present_info
		{
			.address = rsx::get_address(display_buffers[info.buffer].offset, CELL_GCM_LOCATION_LOCAL),
			.format = av_format,
			.width = buffer_width,
			.height = buffer_height,
			.pitch = buffer_pitch,
			.eye = 0
		};
		image_to_flip = get_present_source(&present_info, avconfig);

		if (avconfig.stereo_enabled) [[unlikely]]
		{
			const auto [unused, min_expected_height] = rsx::apply_resolution_scale<true>(resolution_scaling_config, RSX_SURFACE_DIMENSION_IGNORED, buffer_height + 30);
			if (image_to_flip->height() < min_expected_height)
			{
				// Get image for second eye
				const u32 image_offset = (buffer_height + 30) * buffer_pitch + display_buffers[info.buffer].offset;
				present_info.width = buffer_width;
				present_info.height = buffer_height;
				present_info.address = rsx::get_address(image_offset, CELL_GCM_LOCATION_LOCAL);
				present_info.eye = 1;

				image_to_flip2 = get_present_source(&present_info, avconfig);
			}
			else
			{
				// Account for possible insets
				const auto [unused2, scaled_buffer_height] = rsx::apply_resolution_scale<true>(resolution_scaling_config, RSX_SURFACE_DIMENSION_IGNORED, buffer_height);
				buffer_height = std::min<u32>(image_to_flip->height() - min_expected_height, scaled_buffer_height);
			}
		}

		buffer_width = present_info.width;
		buffer_height = present_info.height;
	}

	if (info.emu_flip)
	{
		evaluate_cpu_usage_reduction_limits();
	}

	// Prepare surface for new frame. Set no timeout here so that we wait for the next image if need be
	ensure(m_current_frame->present_image == umax);
	ensure(m_current_frame->swap_command_buffer == nullptr);

	u64 timeout = m_swapchain->get_swap_image_count() <= 2? 0ull: 100000000ull;
	// Braced so the scope covers the acquire and nothing else. Declared at function
	// scope it lived until flip() returned, so the bucket was silently charging the
	// whole present path -- overlays, blit, submit -- as swapchain wait.
	{
	rsx::prof::scope acquire_scope{rsx::prof::bucket::present_wait};
	while (VkResult status = m_swapchain->acquire_next_swapchain_image(m_current_frame->acquire_signal_semaphore, timeout, &m_current_frame->present_image))
	{
		switch (status)
		{
		case VK_TIMEOUT:
		case VK_NOT_READY:
		{
			// In some cases, after a fullscreen switch, the driver only allows N-1 images to be acquirable, where N = number of available swap images.
			// This means that any acquired images have to be released
			// before acquireNextImage can return successfully. This is despite the driver reporting 2 swap chain images available
			// This makes fullscreen performance slower than windowed performance as throughput is lowered due to losing one presentable image
			// Found on AMD Crimson 17.7.2


			// Whatever returned from status, this is now a spin
			timeout = 0ull;
			check_present_status();
			continue;
		}
		case VK_SUBOPTIMAL_KHR:
			should_reinitialize_swapchain = true;
			break;
		case VK_ERROR_OUT_OF_DATE_KHR:
			rsx_log.warning("vkAcquireNextImageKHR failed with VK_ERROR_OUT_OF_DATE_KHR. Flip request ignored until surface is recreated.");
			swapchain_unavailable = true;
			reinitialize_swapchain();
			ensure(m_current_frame, "Could not reinitialize swapchain after VK_ERROR_OUT_OF_DATE_KHR signal!");
			continue;
		case VK_ERROR_SURFACE_LOST_KHR:
		{
			// Recoverable, and on Android routine: the ANativeWindow is destroyed
			// every time the app leaves the foreground, taking the VkSurfaceKHR
			// with it. This fell through to die_with_error below, which killed the
			// RSX thread outright -- the picture never came back and the game ran
			// on with sound only until it was force-closed.
			rsx_log.warning("vkAcquireNextImageKHR failed with VK_ERROR_SURFACE_LOST. Rebuilding the surface.");
			swapchain_unavailable = true;
			m_surface_lost = true;

			if (!reinitialize_swapchain())
			{
				// Still no usable window. Drop this frame rather than spinning in
				// the acquire loop; the next flip retries.
				m_frame->flip(m_context);
				rsx::thread::flip(info);
				return;
			}
			continue;
		}
		default:
			vk::die_with_error(status);
		}

		if (should_reinitialize_swapchain)
		{
			// Image is valid, new swapchain will be generated later
			break;
		}
	}
	}

	// Confirm that the driver did not silently fail
	ensure(m_current_frame->present_image != umax);

	// Calculate output dimensions. Done after swapchain acquisition in case it was recreated.
	areai aspect_ratio;
	if (!g_cfg.video.stretch_to_display_area)
	{
		const auto converted = avconfig.aspect_convert_region({ buffer_width, buffer_height }, m_swapchain_dims);
		aspect_ratio = static_cast<areai>(converted);
	}
	else
	{
		aspect_ratio = { 0, 0, s32(m_swapchain_dims.width), s32(m_swapchain_dims.height) };
	}

	// Blit contents to screen..
	VkImage target_image = m_swapchain->get_image(m_current_frame->present_image);
	const auto present_layout = m_swapchain->get_optimal_present_layout();

	const VkImageSubresourceRange subresource_range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
	VkImageLayout target_layout = present_layout;

	VkRenderPass single_target_pass = VK_NULL_HANDLE;
	vk::framebuffer_holder* direct_fbo = nullptr;
	rsx::simple_array<vk::viewable_image*> calibration_src;

	const bool has_overlay = (m_overlay_manager && m_overlay_manager->has_visible());
	const bool user_asked_for_screenshot = g_user_asked_for_screenshot.exchange(false);
	const bool user_is_recording = (g_recording_mode != recording_mode::stopped && m_frame->can_consume_frame());
	const bool need_media_capture = user_asked_for_screenshot || user_is_recording;

	const auto render_overlays = [&](vk::framebuffer_holder* fbo, const areau& area)
	{
		if (!has_overlay) return;

		// Lock to avoid modification during run-update chain
		auto ui_renderer = vk::get_overlay_pass<vk::ui_overlay_renderer>();
		std::lock_guard lock(*m_overlay_manager);

		const areau display_area = {0, 0, static_cast<u32>(m_swapchain_dims.width), static_cast<u32>(m_swapchain_dims.height)};
		for (const auto& view : m_overlay_manager->get_views())
		{
			const areau render_area = view->use_window_space ? display_area : area;
			ui_renderer->run(*m_current_command_buffer, render_area, fbo, single_target_pass, m_texture_upload_buffer_ring_info, *view.get());
		}
	};

	// WARNING: We have to do this here. We cannot touch the acquired image on the CB and then do a hard sync on it before it is submitted to the presentation engine.
	// That introduces a WRITE_AFTER_PRESENT (from the previous present) when we later try to present on a different CB
	if (image_to_flip && need_media_capture)
	{
		const usz sshot_size = buffer_height * buffer_width * 4;

		vk::buffer sshot_vkbuf(*m_device, utils::align(sshot_size, 0x100000), m_device->get_memory_mapping().host_visible_coherent,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, VK_BUFFER_USAGE_TRANSFER_DST_BIT, 0, VMM_ALLOCATION_POOL_UNDEFINED);

		VkBufferImageCopy copy_info{};
		copy_info.bufferOffset = 0;
		copy_info.bufferRowLength = 0;
		copy_info.bufferImageHeight = 0;
		copy_info.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		copy_info.imageSubresource.baseArrayLayer = 0;
		copy_info.imageSubresource.layerCount = 1;
		copy_info.imageSubresource.mipLevel = 0;
		copy_info.imageOffset.x = 0;
		copy_info.imageOffset.y = 0;
		copy_info.imageOffset.z = 0;
		copy_info.imageExtent.width = buffer_width;
		copy_info.imageExtent.height = buffer_height;
		copy_info.imageExtent.depth = 1;

		vk::image* image_to_copy = image_to_flip;

		if (g_cfg.video.record_with_overlays && has_overlay)
		{
			const auto key = vk::get_renderpass_key(m_swapchain->get_surface_format());
			single_target_pass = vk::get_renderpass(*m_device, key);
			ensure(single_target_pass != VK_NULL_HANDLE);

			if (!m_overlay_recording_img ||
				m_overlay_recording_img->type() != image_to_flip->type() ||
				m_overlay_recording_img->format() != image_to_flip->format() ||
				m_overlay_recording_img->width() != image_to_flip->width() ||
				m_overlay_recording_img->height() != image_to_flip->height() ||
				m_overlay_recording_img->layers() != image_to_flip->layers())
			{
				m_overlay_recording_img = std::make_unique<vk::image>(*m_device, m_device->get_memory_mapping().device_local, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
					image_to_flip->type(), image_to_flip->format(), image_to_flip->width(), image_to_flip->height(), 1, 1, image_to_flip->layers(), VK_SAMPLE_COUNT_1_BIT,
					VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
					0, VMM_ALLOCATION_POOL_UNDEFINED);
			}

			m_overlay_recording_img->change_layout(*m_current_command_buffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			image_to_flip->push_layout(*m_current_command_buffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

			const areai rect = areai(0, 0, buffer_width, buffer_height);
			vk::copy_image(*m_current_command_buffer, image_to_flip, m_overlay_recording_img.get(), rect, rect, 1);

			image_to_flip->pop_layout(*m_current_command_buffer);
			m_overlay_recording_img->change_layout(*m_current_command_buffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

			vk::framebuffer_holder* sshot_fbo = vk::get_framebuffer(*m_device, buffer_width, buffer_height, VK_FALSE, single_target_pass, { m_overlay_recording_img.get() });
			sshot_fbo->add_ref();
			render_overlays(sshot_fbo, areau(rect));
			sshot_fbo->release();

			image_to_copy = m_overlay_recording_img.get();
		}

		image_to_copy->push_layout(*m_current_command_buffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		vk::copy_image_to_buffer(*m_current_command_buffer, image_to_copy, &sshot_vkbuf, copy_info);
		image_to_copy->pop_layout(*m_current_command_buffer);

		if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[18]++; flush_command_queue(true);
		const auto src = sshot_vkbuf.map(0, sshot_size);
		std::vector<u8> sshot_frame(sshot_size);
		memcpy(sshot_frame.data(), src, sshot_size);
		sshot_vkbuf.unmap();

		const bool is_bgra = image_to_copy->format() == VK_FORMAT_B8G8R8A8_UNORM;

		if (user_asked_for_screenshot)
		{
			m_frame->take_screenshot(std::move(sshot_frame), buffer_width, buffer_height, is_bgra);
		}
		else
		{
			m_frame->present_frame(std::move(sshot_frame), buffer_width * 4, buffer_width, buffer_height, is_bgra);
		}
	}

	if (!image_to_flip || aspect_ratio.x1 || aspect_ratio.y1)
	{
		// Clear the window background to black
		VkClearColorValue clear_black {};
		vk::change_image_layout(*m_current_command_buffer, target_image, present_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, subresource_range);
		vkCmdClearColorImage(*m_current_command_buffer, target_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear_black, 1, &subresource_range);

		// Prevent WAW on transfer writes
		vk::insert_image_memory_barrier(
			*m_current_command_buffer,
			target_image,
			target_layout,
			target_layout,
			VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT,
			subresource_range
		);

		target_layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
	}

	const output_scaling_mode output_scaling = g_cfg.video.output_scaling.get();

	if (!m_upscaler || m_output_scaling != output_scaling)
	{
		m_output_scaling = output_scaling;

		if (m_output_scaling == output_scaling_mode::nearest)
		{
			m_upscaler = std::make_unique<vk::nearest_upscale_pass>();
		}
		else if (m_output_scaling == output_scaling_mode::fsr)
		{
			m_upscaler = std::make_unique<vk::fsr_upscale_pass>();
		}
#ifdef __ANDROID__
		else if (m_output_scaling == output_scaling_mode::shader)
		{
			m_upscaler = std::make_unique<vk::librashader_upscale_pass>();
		}
#endif
		else
		{
			m_upscaler = std::make_unique<vk::bilinear_upscale_pass>();
		}
	}

	if (image_to_flip)
	{
		const bool use_full_rgb_range_output = g_cfg.video.full_rgb_range_output.get();

		if (!use_full_rgb_range_output || !rsx::fcmp(avconfig.gamma, 1.f) || avconfig.stereo_enabled) [[unlikely]]
		{
			if (image_to_flip) calibration_src.push_back(image_to_flip);
			if (image_to_flip2) calibration_src.push_back(image_to_flip2);

			if (m_output_scaling == output_scaling_mode::fsr && !avconfig.stereo_enabled) // 3D will be implemented later
			{
				// Run upscaling pass before the rest of the output effects pipeline
				// This can be done with all upscalers but we already get bilinear upscaling for free if we just out the filters directly
				VkImageBlit request = {};
				request.srcSubresource = { image_to_flip->aspect(), 0, 0, 1 };
				request.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
				request.srcOffsets[0] = { 0, 0, 0 };
				request.srcOffsets[1] = { s32(buffer_width), s32(buffer_height), 1 };
				request.dstOffsets[0] = { 0, 0, 0 };
				request.dstOffsets[1] = { aspect_ratio.width(), aspect_ratio.height(), 1 };

				for (unsigned i = 0; i < calibration_src.size(); ++i)
				{
					const rsx::flags32_t mode = (i == 0) ? UPSCALE_LEFT_VIEW : UPSCALE_RIGHT_VIEW;
					calibration_src[i] = m_upscaler->scale_output(*m_current_command_buffer, image_to_flip, VK_NULL_HANDLE, VK_IMAGE_LAYOUT_UNDEFINED, request, mode);
				}
			}

			vk::change_image_layout(*m_current_command_buffer, target_image, target_layout, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, subresource_range);
			target_layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

			const auto key = vk::get_renderpass_key(m_swapchain->get_surface_format());
			single_target_pass = vk::get_renderpass(*m_device, key);
			ensure(single_target_pass != VK_NULL_HANDLE);

			direct_fbo = vk::get_framebuffer(*m_device, m_swapchain_dims.width, m_swapchain_dims.height, VK_FALSE, single_target_pass, m_swapchain->get_surface_format(), target_image);
			direct_fbo->add_ref();

			vk::get_overlay_pass<vk::video_out_calibration_pass>()->run(
				*m_current_command_buffer, areau(aspect_ratio), direct_fbo, calibration_src,
				avconfig.gamma, !use_full_rgb_range_output, avconfig.stereo_enabled, single_target_pass);

			direct_fbo->release();
		}
		else
		{
			// Do raw transfer here as there is no image object associated with textures owned by the driver (TODO)
			VkImageBlit rgn = {};
			rgn.srcSubresource = { image_to_flip->aspect(), 0, 0, 1 };
			rgn.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
			rgn.srcOffsets[0] = { 0, 0, 0 };
			rgn.srcOffsets[1] = { s32(buffer_width), s32(buffer_height), 1 };
			rgn.dstOffsets[0] = { aspect_ratio.x1, aspect_ratio.y1, 0 };
			rgn.dstOffsets[1] = { aspect_ratio.x2, aspect_ratio.y2, 1 };

			if (target_layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
			{
				vk::change_image_layout(*m_current_command_buffer, target_image, target_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, subresource_range);
				target_layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
			}

			// target_image is the swapchain image, so the swapchain owns its format. Passes that
			// render into it rather than blitting need the real one; see upscaler::set_present_format.
			m_upscaler->set_present_format(m_swapchain->get_surface_format());
			m_upscaler->scale_output(*m_current_command_buffer, image_to_flip, target_image, target_layout, rgn, UPSCALE_AND_COMMIT | UPSCALE_DEFAULT_VIEW);
		}
	}

	if (g_cfg.video.debug_overlay || has_overlay)
	{
		if (target_layout != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
		{
			// Change the image layout whilst setting up a dependency on waiting for the blit op to finish before we start writing
			VkImageMemoryBarrier barrier = {};
			barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
			barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
			barrier.oldLayout = target_layout;
			barrier.image = target_image;
			barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
			barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
			barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
			barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
			barrier.subresourceRange = subresource_range;
			vkCmdPipelineBarrier(*m_current_command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_DEPENDENCY_BY_REGION_BIT, 0, nullptr, 0, nullptr, 1, &barrier);

			target_layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
		}

		if (!direct_fbo)
		{
			const auto key = vk::get_renderpass_key(m_swapchain->get_surface_format());
			single_target_pass = vk::get_renderpass(*m_device, key);
			ensure(single_target_pass != VK_NULL_HANDLE);

			direct_fbo = vk::get_framebuffer(*m_device, m_swapchain_dims.width, m_swapchain_dims.height, VK_FALSE, single_target_pass, m_swapchain->get_surface_format(), target_image);
		}

		direct_fbo->add_ref();

		render_overlays(direct_fbo, areau(aspect_ratio));

		if (g_cfg.video.debug_overlay)
		{
			const auto num_dirty_textures = m_texture_cache.get_unreleased_textures_count();
			const auto texture_memory_size = m_texture_cache.get_texture_memory_in_use() / (1024 * 1024);
			const auto tmp_texture_memory_size = m_texture_cache.get_temporary_memory_in_use() / (1024 * 1024);
			const auto num_flushes = m_texture_cache.get_num_flush_requests();
			const auto num_mispredict = m_texture_cache.get_num_cache_mispredictions();
			const auto num_speculate = m_texture_cache.get_num_cache_speculative_writes();
			const auto num_misses = m_texture_cache.get_num_cache_misses();
			const auto num_unavoidable = m_texture_cache.get_num_unavoidable_hard_faults();
			const auto cache_miss_ratio = static_cast<u32>(ceil(m_texture_cache.get_cache_miss_ratio() * 100));
			const auto num_texture_upload = m_texture_cache.get_texture_upload_calls_this_frame();
			const auto num_texture_upload_miss = m_texture_cache.get_texture_upload_misses_this_frame();
			const auto texture_upload_miss_ratio = m_texture_cache.get_texture_upload_miss_percentage();
			const auto texture_copies_ellided = m_texture_cache.get_texture_copies_ellided_this_frame();
			const auto vertex_cache_hit_count = (info.stats.vertex_cache_request_count - info.stats.vertex_cache_miss_count);
			const auto vertex_cache_hit_ratio = info.stats.vertex_cache_request_count
				? (vertex_cache_hit_count * 100) / info.stats.vertex_cache_request_count
				: 0;
			const auto program_cache_lookups = info.stats.program_cache_lookups_total;
			const auto program_cache_ellided = info.stats.program_cache_lookups_ellided;
			const auto program_cache_ellision_rate = program_cache_lookups
				? (program_cache_ellided * 100) / program_cache_lookups
				: 0;

			rsx::overlays::set_debug_overlay_text(fmt::format(
				"Internal Resolution:      %s\n"
				"RSX Load:                 %3d%%\n"
				"draw calls: %17d\n"
				"submits: %20d\n"
				"draw call setup: %12dus\n"
				"vertex upload time: %9dus\n"
				"texture upload time: %8dus\n"
				"draw call execution: %8dus\n"
				"submit and flip: %12dus\n"
				"Unreleased textures: %8d\n"
				"Texture cache memory: %7dM\n"
				"Temporary texture memory: %3dM\n"
				"Flush requests: %13d  = %2d (%3d%%) hard faults, %2d unavoidable, %2d misprediction(s), %2d speculation(s)\n"
				"Texture uploads: %12u (%u from CPU - %02u%%, %u copies avoided)\n"
				"Vertex cache hits: %10u/%u (%u%%)\n"
				"Program cache lookup ellision: %u/%u (%u%%)",
				info.stats.framebuffer_stats.to_string(resolution_scaling_config, !backend_config.supports_hw_msaa),
				get_load(), info.stats.draw_calls, info.stats.submit_count, info.stats.setup_time, info.stats.vertex_upload_time,
				info.stats.textures_upload_time, info.stats.draw_exec_time, info.stats.flip_time,
				num_dirty_textures, texture_memory_size, tmp_texture_memory_size,
				num_flushes, num_misses, cache_miss_ratio, num_unavoidable, num_mispredict, num_speculate,
				num_texture_upload, num_texture_upload_miss, texture_upload_miss_ratio, texture_copies_ellided,
				vertex_cache_hit_count, info.stats.vertex_cache_request_count, vertex_cache_hit_ratio,
				program_cache_ellided, program_cache_lookups, program_cache_ellision_rate)
			);
		}

		direct_fbo->release();
	}

	// Hand the finished frame to frame generation.
	//
	// HERE, after the overlays and before the image becomes PRESENT_SRC, so what framegen
	// interpolates is exactly what the user sees. It used to capture image_to_flip -- the game
	// image, upstream of the output pipeline and of every overlay -- and since generated frames are
	// presented straight from framegen's output, the performance overlay ended up on real frames
	// only. Presenting generated/real/generated/real then blinked it at half the display rate.
	//
	// This is also the one place that covers every configuration: presentation splits into a
	// calibration pass and a raw blit depending on scaling mode and stereo, and both converge here.
	//
	// Still gated on image_to_flip, which is what the old site got for free by sitting inside that
	// branch. Without the gate this also captures frames the GAME did not draw -- the PPU/SPU
	// compilation screen is the obvious one, being overlay on a cleared background -- and
	// interpolating a progress dialog produced exactly the flicker that moving the capture was
	// meant to remove.
	//
	// A swapchain image is not something framegen's separate VkDevice can see, so this copies into
	// AHardwareBuffer-backed storage both devices share. Returns false and costs nothing when the
	// feature is off, which is the default.
	if (image_to_flip)
	{
		vk::frame_gen::capture_presented_frame(*m_current_command_buffer, *m_device,
			target_image, target_layout, m_swapchain_dims.width, m_swapchain_dims.height);
	}

	if (target_layout != present_layout)
	{
		vk::change_image_layout(*m_current_command_buffer, target_image, target_layout, present_layout, subresource_range);
	}

	queue_swap_request();

	m_frame_stats.flip_time = m_profiler.duration();

	m_frame->flip(m_context);
	rsx::thread::flip(info);

	// Data sync
	const rsx::surface_scaling_config_t active_res_scaling_config =
	{
		.scale_percent = static_cast<u16>(g_cfg.video.resolution_scale_percent),
		.min_scalable_dimension = static_cast<u16>(g_cfg.video.min_scalable_dimension),
	};

	if (active_res_scaling_config != this->resolution_scaling_config)
	{
		// First, try to reclaim any memory since the res scale upgrade is so memory intensive
		if (const auto severity = vk::vmm_determine_memory_load_severity();
			severity > rsx::problem_severity::low && m_rtts.handle_memory_pressure(*m_current_command_buffer, severity))
		{
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[19]++; flush_command_queue(true);
		}

		// Then apply the change
		m_rtts.sync_scaling_config(*m_current_command_buffer, active_res_scaling_config);
		this->resolution_scaling_config = active_res_scaling_config;

		// Finally reclaim any unused resources
		if (const auto severity = vk::vmm_determine_memory_load_severity();
			severity > rsx::problem_severity::low && m_rtts.handle_memory_pressure(*m_current_command_buffer, severity))
		{
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_flush_sites[20]++; flush_command_queue(true);
		}
	}
}
