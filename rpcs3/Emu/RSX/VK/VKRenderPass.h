#pragma once

#include "VulkanAPI.h"
#include "Utilities/geometry.h"

namespace vk
{
	class image;
	class command_buffer;

	// Surface layout parking policy.
	//
	// "Parking" is declining to move a render target out of a layout that is legal both for
	// sampling and for use as an attachment, so a sample-then-rebind cycle costs no layout
	// transition at all. A transition can never be recorded inside a render pass instance
	// (VUID-vkCmdPipelineBarrier-oldLayout-01181), so every transition avoided is a render pass
	// teardown avoided - on a tiler, a tile store plus a full reload.
	//
	// Both knobs live here rather than next to vk::render_target because the render pass builder
	// has to see them too. The barrier that parking removes is the one that used to carry the
	// read -> write dependency across the cycle, so the pass has to declare that dependency
	// itself; see the VK_SUBPASS_EXTERNAL dependency in get_renderpass. Setting these to 0 has to
	// take that declaration away with them, otherwise "0 reproduces current behaviour" is a lie.
	//
	// Both are counted in flips, not binds. A render graph repeats per frame, so "was this
	// surface used this way recently" is a per-frame question; the number of unrelated binds
	// collected in between is a property of the scene's draw count and is not a useful unit.
	// Two frames of slack rather than one so a surface used on alternate frames, or one that
	// misses a frame to a paused or duplicate flip, does not oscillate.

	// Feedback loops: a surface written and sampled by the same draw, parked in
	// ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT where the device supports it and GENERAL otherwise.
	// Iteration 3. Measured effect on Arkham City: close to zero, because the teardown it
	// removes at the bind site relocates to the draw site as a framebuffer mismatch.
	// Held at 0 pending the baseline `redundant` reading: iteration 3 measured close to zero here,
	// and at 0 this also drops the extra pass dependency below, which is the only thing that was
	// covering the read->write hazard this park had been running without.
	inline constexpr u64 s_feedback_park_frames = 0;

	// Non-cyclic sampling of a render target: the RTT is left in GENERAL for the read instead of
	// being dragged to SHADER_READ_ONLY_OPTIMAL and back. GENERAL, not the feedback-loop layout,
	// deliberately - see the note in render_target::get_sample_park_layout.
	//
	// This is the independent A/B knob. Set to 0 to get the previous behaviour exactly while
	// leaving feedback parking alone, and vice versa.
	// Held at 0 for the same reason. `redundant` in the RSXPROF line is the hard ceiling on what
	// suppressing these teardowns can win; read that first, because if it is small then this trade
	// (GENERAL costs UBWC while rendering, and HiZ on depth) cannot pay for itself and the answer
	// is to leave both at 0.
	inline constexpr u64 s_sample_park_frames = 0;

	// Any parking at all. Gates the extra render pass dependency that makes parking safe.
	inline constexpr bool s_surface_parking_enabled = (s_feedback_park_frames != 0) || (s_sample_park_frames != 0);

	u64 get_renderpass_key(const std::vector<vk::image*>& images, const std::vector<u8>& input_attachment_ids = {});
	u64 get_renderpass_key(const std::vector<vk::image*>& images, u64 previous_key);
	u64 get_renderpass_key(VkFormat surface_format, u8 sample_count = 1);
	u64 get_renderpass_key(VkFormat color_format, VkFormat depth_format, u8 sample_count = 1);
	VkRenderPass get_renderpass(VkDevice dev, u64 renderpass_key);

	void clear_renderpass_cache(VkDevice dev);

	// Renderpass scope management helpers.
	// NOTE: These are not thread safe by design.
	void begin_renderpass(VkDevice dev, const vk::command_buffer& cmd, u64 renderpass_key, VkFramebuffer target, const coordu& framebuffer_region);
	void begin_renderpass(const vk::command_buffer& cmd, VkRenderPass pass, VkFramebuffer target, const coordu& framebuffer_region);
	void end_renderpass(const vk::command_buffer& cmd);
	bool is_renderpass_open(const vk::command_buffer& cmd);

	// Attachment set of the pass currently open on this command buffer.
	//
	// VUID-vkCmdPipelineBarrier-image-04073 only allows a barrier to be recorded inside a render
	// pass instance when the image is an attachment of the current subpass. "The caller only ever
	// does this for a bound render target" is not the same statement: the pass that is open is the
	// one from the previous draw, and it is not re-created until the draw call itself, so between
	// a framebuffer switch and the next draw the open pass belongs to the *previous* framebuffer.
	// A surface that needs no layout change (because it is parked) does not end the pass on the
	// way in, so it can reach a barrier site with a stale pass open and its own image absent from
	// it. That is the corruption an earlier attempt hit; this makes the rule checkable instead of
	// assumed.
	//
	// The set is cleared whenever a pass begins, so any pass opened by code that does not publish
	// its attachments (overlays, texture cache, present) reads as covering nothing and in-pass
	// barriers are refused for it. Fail-closed by construction.
	void set_renderpass_attachments(const vk::command_buffer& cmd, const std::vector<vk::image*>& images);
	bool renderpass_covers_image(const vk::command_buffer& cmd, VkImage image);

	using renderpass_op_callback_t = std::function<void(const vk::command_buffer&, VkRenderPass, VkFramebuffer)>;
	void renderpass_op(const vk::command_buffer& cmd, const renderpass_op_callback_t& op);
}
