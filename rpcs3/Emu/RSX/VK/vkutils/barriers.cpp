#include "Emu/RSX/rsx_profiler.h"
#include "barriers.h"
#include "commands.h"
#include "image.h"

#include "../../rsx_methods.h"
#include "../VKRenderPass.h"

namespace vk
{
	// When a barrier may stay inside an open render pass instance, and when it may not.
	//
	// Written out because getting this wrong is not a validation warning, it is corruption. An
	// earlier attempt at cutting the render pass count on Android set preserve_renderpass on
	// every sampled render target and produced garbage output, because two of the three rules
	// below were never checked.
	//
	// 1. VUID-vkCmdPipelineBarrier-None-07890. The render pass must declare a dependency from
	//    the current subpass to itself, with stage and access scopes that are supersets of the
	//    barrier's, and it must not declare VK_DEPENDENCY_BY_REGION_BIT unless the barrier
	//    passes that flag too. VKRenderPass.cpp declares exactly one self-dependency and it does
	//    carry BY_REGION, so an in-pass barrier here has to pass VK_DEPENDENCY_BY_REGION_BIT,
	//    and may only name framebuffer-space stages: COLOR_ATTACHMENT_OUTPUT,
	//    EARLY/LATE_FRAGMENT_TESTS and FRAGMENT_SHADER. Naming VERTEX_SHADER or TRANSFER, or
	//    leaving dependencyFlags at zero, makes the barrier invalid against that declaration.
	//
	// 2. VUID-vkCmdPipelineBarrier-oldLayout-01181. Inside a render pass instance oldLayout and
	//    newLayout must be equal. A real layout transition can never stay inside the pass, no
	//    matter what the self-dependency says. This is the hard floor on how far the pass count
	//    can be cut by suppressing barriers, and it is why the useful work is in removing the
	//    need for a transition rather than in hiding the pass break it forces.
	//
	// 3. VUID-vkCmdPipelineBarrier-image-04073. The image must be an attachment of the current
	//    subpass. A surface the draw merely samples is not one, and a subpass self-dependency
	//    says nothing about it. Only the feedback case, an attachment sampled while it is still
	//    bound, qualifies - render_target::texture_barrier and its fall-out counterpart
	//    render_target::post_texture_barrier, and nothing else.
	//
	// The strict reading of rule 3 also wants the attachment declared as an input attachment of
	// the subpass. The subpass built in VKRenderPass.cpp never declares one: input_attachments_mask
	// exists but no caller ever sets it. Declaring one would change render pass compatibility and
	// therefore invalidate every cached pipeline, so it is left alone here and noted rather than
	// silently assumed away.
	//
	// None of this is gated per-platform because every caller that asks to preserve the pass is
	// already gated: texture_barrier and post_texture_barrier pass preserve_renderpass = true on
	// Android only. On every other target the in-pass paths below are unreachable.

	// Rules 2 and 3 in one place, so no caller can opt out of them by asking nicely.
	//
	// Rule 3 is checked against the attachments the open pass actually has, not against what the
	// caller believes is bound. The two differ: the render pass in flight is the previous draw's,
	// and it is only re-created at the draw call, so a barrier issued during draw setup after a
	// framebuffer switch sees a pass whose attachments are the *old* surfaces. That window used
	// to be closed by accident - the layout change on the way in ended the pass - and surface
	// parking is precisely the change that stops ending it.
	static bool can_keep_pass_open(
		const vk::command_buffer& cmd, VkImage image,
		VkImageLayout current_layout, VkImageLayout new_layout,
		bool preserve_renderpass)
	{
		return preserve_renderpass &&
			current_layout == new_layout &&           // rule 2
			vk::renderpass_covers_image(cmd, image);  // rule 3
	}

	void insert_image_memory_barrier(
		const vk::command_buffer& cmd, VkImage image,
		VkImageLayout current_layout, VkImageLayout new_layout,
		VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
		VkAccessFlags src_mask, VkAccessFlags dst_mask,
		const VkImageSubresourceRange& range,
		bool preserve_renderpass)
	{
		// A caller asking to preserve the pass across a layout change, or for an image the open
		// pass does not have attached, is asking for an illegal barrier. Take the teardown
		// instead of recording one.
		const bool keep_pass_open = can_keep_pass_open(cmd, image, current_layout, new_layout, preserve_renderpass);
		bool inside_renderpass = false;

		if (vk::is_renderpass_open(cmd))
		{
			if (!keep_pass_open)
			{
				if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[10]++; vk::end_renderpass(cmd);
			}
			else
			{
				inside_renderpass = true;
				if (rsx::prof::enabled()) [[unlikely]] rsx::prof::note_pass_barrier(false);
			}
		}

		// Rule 1. Must match the BY_REGION self-dependency when the barrier lands inside the pass.
		const VkDependencyFlags dependency_flags = inside_renderpass ? VK_DEPENDENCY_BY_REGION_BIT : 0;

		VkImageMemoryBarrier barrier = {};
		barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		barrier.newLayout = new_layout;
		barrier.oldLayout = current_layout;
		barrier.image = image;
		barrier.srcAccessMask = src_mask;
		barrier.dstAccessMask = dst_mask;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.subresourceRange = range;

		vkCmdPipelineBarrier(cmd, src_stage, dst_stage, dependency_flags, 0, nullptr, 0, nullptr, 1, &barrier);
	}

	void insert_buffer_memory_barrier(
		const vk::command_buffer& cmd,
		VkBuffer buffer,
		VkDeviceSize offset, VkDeviceSize length,
		VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
		VkAccessFlags src_mask, VkAccessFlags dst_mask,
		bool preserve_renderpass)
	{
		if (!preserve_renderpass && vk::is_renderpass_open(cmd))
		{
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[11]++; vk::end_renderpass(cmd);
		}

		VkBufferMemoryBarrier barrier = {};
		barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
		barrier.buffer = buffer;
		barrier.offset = offset;
		barrier.size = length;
		barrier.srcAccessMask = src_mask;
		barrier.dstAccessMask = dst_mask;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;

		vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, nullptr, 1, &barrier, 0, nullptr);
	}

	void insert_global_memory_barrier(
		const vk::command_buffer& cmd,
		VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
		VkAccessFlags src_access, VkAccessFlags dst_access,
		bool preserve_renderpass)
	{
		if (!preserve_renderpass && vk::is_renderpass_open(cmd))
		{
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[12]++; vk::end_renderpass(cmd);
		}

		VkMemoryBarrier barrier = {};
		barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
		barrier.srcAccessMask = src_access;
		barrier.dstAccessMask = dst_access;
		vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 1, &barrier, 0, nullptr, 0, nullptr);
	}

	void insert_texture_barrier(
		const vk::command_buffer& cmd,
		VkImage image,
		VkImageLayout current_layout, VkImageLayout new_layout,
		VkImageSubresourceRange range,
		bool preserve_renderpass)
	{
		// NOTE: Sampling from an attachment in ATTACHMENT_OPTIMAL layout on some hw ends up with garbage output
		// Transition to GENERAL if this resource is both input and output
		// TODO: This implicitly makes the target incompatible with the renderpass declaration; investigate a proper workaround
		// TODO: This likely throws out hw optimizations on the rest of the renderpass, manage carefully

		// Rule 2 again, and this one does fire. The caller parks the surface in GENERAL or
		// ATTACHMENT_FEEDBACK_LOOP_OPTIMAL for the duration of a feedback loop, so the first
		// call of a loop moves the layout while later calls do not. Only the later ones may
		// stay inside the pass; the first has to end it, even though Android asks to preserve.
		// This was previously recorded as an in-pass layout change, which is undefined.
		//
		// Rule 3 fires here too, and only became reachable with parking: a parked surface no
		// longer ends the pass when it is re-bound, so this can now be called while the previous
		// draw's pass is still open and does not have this image attached.
		const bool keep_pass_open = can_keep_pass_open(cmd, image, current_layout, new_layout, preserve_renderpass);
		bool inside_renderpass = false;

		if (vk::is_renderpass_open(cmd))
		{
			if (!keep_pass_open)
			{
				if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_rp_sites[13]++; vk::end_renderpass(cmd);
			}
			else
			{
				inside_renderpass = true;

				// Kept the pass open, so the barrier lands inside it. On a tiler that is a resolve
				// and a re-fetch of the tile, which is the cost this is here to find.
				if (rsx::prof::enabled()) [[unlikely]] rsx::prof::note_pass_barrier(true);
			}
		}

		VkAccessFlags src_access, dst_access;
		VkPipelineStageFlags src_stage, dst_stage;
		if (range.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT)
		{
			src_access = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
			dst_access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
			src_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
			dst_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
		}
		else
		{
			src_access = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
			dst_access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
			src_stage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
			dst_stage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
		}

		if (inside_renderpass)
		{
			// Issued inside the pass, so it must match the by-region self-dependency the
			// render pass declares, and that permits framebuffer-local stages only. The
			// vertex stage is not one, and naming it here would make the barrier invalid.
			//
			// Correct for what this is used for: the feedback case is a fragment shader
			// sampling the attachment its own fragments write. A vertex shader sampling a
			// live render target would need the pass ended anyway, which is what the caller
			// gets by leaving preserve_renderpass false.
			//
			// Keyed off inside_renderpass rather than preserve_renderpass: when the pass had
			// to be ended for a layout change the barrier is outside it again, and the wider
			// scope is both legal and wanted.
			dst_stage |= VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
		}
		else
		{
			dst_stage |= VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
		}

		// Rule 1. The declared self-dependency carries VK_DEPENDENCY_BY_REGION_BIT, so a barrier
		// recorded inside the pass has to carry it as well or it does not match the declaration.
		// It was being recorded with dependencyFlags = 0, on every in-pass texture barrier
		// Android takes - 20 to 23 a frame in Arkham City. BY_REGION is also the semantics this
		// wants: a fragment reading the pixel its own draw wrote is a tile-local dependency, and
		// a tiler handed a non-by-region dependency mid-pass has to assume the whole framebuffer
		// is involved.
		const VkDependencyFlags dependency_flags = inside_renderpass ? VK_DEPENDENCY_BY_REGION_BIT : 0;

		VkImageMemoryBarrier barrier = {};
		barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		barrier.newLayout = new_layout;
		barrier.oldLayout = current_layout;
		barrier.image = image;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.subresourceRange = range;
		barrier.srcAccessMask = src_access;
		barrier.dstAccessMask = dst_access;

		vkCmdPipelineBarrier(cmd, src_stage, dst_stage, dependency_flags, 0, nullptr, 0, nullptr, 1, &barrier);
	}

	void insert_texture_barrier(const vk::command_buffer& cmd, vk::image* image, VkImageLayout new_layout, bool preserve_renderpass)
	{
		insert_texture_barrier(cmd, image->value, image->current_layout, new_layout, { image->aspect(), 0, 1, 0, 1 }, preserve_renderpass);
		image->current_layout = new_layout;
	}
}
