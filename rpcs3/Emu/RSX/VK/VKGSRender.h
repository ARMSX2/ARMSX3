#pragma once

#include "upscalers/upscaling.h"

#include "vkutils/descriptors.h"
#include "vkutils/data_heap.h"
#include "vkutils/ex.h"
#include "vkutils/instance.h"
#include "vkutils/sync.h"
#include "vkutils/swapchain.h"

#include "VKGSRenderTypes.hpp"
#include "VKTextureCache.h"
#include "VKRenderTargets.h"
#include "VKFormats.h"
#include "VKOverlays.h"
#include "VKProgramBuffer.h"
#include "VKFramebuffer.h"
#include "VKShaderInterpreter.h"
#include "VKQueryPool.h"

#include "Emu/RSX/Core/RSXRenderBackend.h"
#include "Emu/RSX/GSFrameBase.h"
#include "Emu/RSX/RSXThread.h"
#include "Emu/RSX/Host/RSXDMAWriter.h"
#include "Emu/RSX/rsx_frame_dump.h"
#include "Emu/RSX/remote_capture.h"
#include <functional>
#include <initializer_list>

using namespace vk::vmm_allocation_pool_; // clang workaround.
using namespace vk::upscaling_flags_;     // ditto

using vs_binding_table_t = decltype(VKVertexProgram::binding_table);
using fs_binding_table_t = decltype(VKFragmentProgram::binding_table);

namespace vk
{
	using host_data_t = rsx::host_gpu_context_t;

	// Per-context caches, stored on the context
	struct context_resources final : rsx::context_resources
	{
		std::unique_ptr<vk::vertex_cache> vertex_cache;
		vk::texture_cache texture_cache;
		vk::surface_cache surface_cache;
	};
}

class VKGSRender : public rsx::render_backend, public ::rsx::reports::occlusion_query_provider
{
private:
	enum frame_context_state : u32
	{
		dirty = 1
	};

	enum flush_queue_state : u32
	{
		ok = 0,
		flushing = 1,
		deadlock = 2
	};

private:
	const VKFragmentProgram *m_fragment_prog = nullptr;
	const VKVertexProgram *m_vertex_prog = nullptr;
	vk::glsl::program *m_program = nullptr;
	vk::glsl::program *m_prev_program = nullptr;
	vk::pipeline_props m_pipeline_properties;           // Decoded pipeline properties cached
	u32 m_pipeline_properties_ctrl = 0;                 // Fragment program control bits that m_pipeline_properties was decoded with

	const vs_binding_table_t* m_vs_binding_table = nullptr;
	const fs_binding_table_t* m_fs_binding_table = nullptr;


	std::unique_ptr<vk::buffer> null_buffer;
	std::unique_ptr<vk::buffer_view> null_buffer_view;

	std::unique_ptr<vk::upscaler> m_upscaler;
	output_scaling_mode m_output_scaling{output_scaling_mode::bilinear};

	std::unique_ptr<vk::buffer> m_cond_render_buffer;
	u64 m_cond_render_sync_tag = 0;

	shared_mutex m_sampler_mutex;
	atomic_t<bool> m_samplers_dirty = { true };
	std::unique_ptr<vk::sampler> m_stencil_mirror_sampler;
	std::array<vk::sampler*, rsx::limits::fragment_textures_count> fs_sampler_handles{};
	std::array<vk::sampler*, rsx::limits::vertex_textures_count> vs_sampler_handles{};

	std::unique_ptr<vk::buffer_view> m_persistent_attribute_storage;
	std::unique_ptr<vk::buffer_view> m_volatile_attribute_storage;

	VkDependencyInfoKHR m_async_compute_dependency_info {};
	VkMemoryBarrier2KHR m_async_compute_memory_barrier {};

	std::pair<const vs_binding_table_t*, const fs_binding_table_t*> get_binding_table() const;

public:
	//vk::fbo draw_fbo;
	std::unique_ptr<vk::shader_cache> m_shaders_cache;

private:
	std::unique_ptr<vk::program_cache> m_prog_buffer;

	std::unique_ptr<vk::swapchain_base> m_swapchain;
	// Set when a swapchain call reported VK_ERROR_SURFACE_LOST. The VkSurfaceKHR
	// has to be rebuilt from the current native window before anything else can
	// succeed -- recreating only the swapchain against a dead surface just fails
	// again. Android hits this every time the app is backgrounded.
	bool m_surface_lost = false;
	vk::instance m_instance;
	vk::render_device *m_device;

	//Vulkan internals
	std::unique_ptr<vk::query_pool_manager> m_occlusion_query_manager;
	bool m_occlusion_query_active = false;
	rsx::reports::occlusion_query_info *m_active_query_info = nullptr;
	std::vector<vk::occlusion_data> m_occlusion_map;

	shared_mutex m_secondary_cb_guard;
	vk::command_pool m_secondary_command_buffer_pool;
	vk::command_buffer_chain<VK_MAX_ASYNC_CB_COUNT> m_secondary_cb_list;

	vk::command_pool m_command_buffer_pool;
	vk::command_buffer_chain<VK_MAX_ASYNC_CB_COUNT> m_primary_cb_list;
	vk::command_buffer_chunk* m_current_command_buffer = nullptr;

	std::unique_ptr<vk::buffer> m_host_object_data;
	vk::framebuffer_holder* m_draw_fbo = nullptr;

	sizeu m_swapchain_dims{};
	bool swapchain_unavailable = false;
	u64 m_display_epoch = 0;   // GSFrameBase::display_epoch the swapchain was built against

	// Consecutive VK_SUBOPTIMAL_KHR presents, and the size we were at when we last acted on them.
	// See the present path: Android needs this signal, but cannot be allowed to rebuild on it
	// every frame.
	u32 m_suboptimal_present_count = 0;
	u32 m_suboptimal_handled_at = 0;
	bool should_reinitialize_swapchain = false;

	u64 m_last_heap_sync_time = 0;
	u32 m_texbuffer_view_size = 0;

	vk::data_heap m_attrib_ring_info;                         // Vertex data
	vk::data_heap m_fragment_constants_ring_info;             // Fragment program constants
	vk::data_heap m_transform_constants_ring_info;            // Transform program constants
	vk::data_heap m_fragment_env_ring_info;                   // Fragment environment params
	vk::data_heap m_vertex_env_ring_info;                     // Vertex environment params
	vk::data_heap m_fragment_texture_params_ring_info;        // Fragment texture params
	vk::data_heap m_vertex_layout_ring_info;                  // Vertex layout structure
	vk::data_heap m_index_buffer_ring_info;                   // Index data
	vk::data_heap m_texture_upload_buffer_ring_info;          // Texture upload heap
	vk::data_heap m_raster_env_ring_info;                     // Raster control such as polygon and line stipple
	vk::data_heap m_instancing_buffer_ring_info;              // Instanced rendering data (constants indirection table + instanced constants)

	vk::data_heap m_fragment_instructions_buffer;             // Interpreter FP block
	vk::data_heap m_vertex_instructions_buffer;               // Interpreter VP block

	rsx::simple_array<vk::data_heap*> m_flushable_data_heaps; // List of heaps that can be 'dirty' and need manual flush

	VkDescriptorBufferInfoEx m_vertex_env_buffer_info {};
	VkDescriptorBufferInfoEx m_fragment_env_buffer_info {};
	VkDescriptorBufferInfoEx m_vertex_layout_stream_info {};
	VkDescriptorBufferInfoEx m_vertex_constants_buffer_info {};
	VkDescriptorBufferInfoEx m_fragment_constants_buffer_info {};
	VkDescriptorBufferInfoEx m_fragment_texture_params_buffer_info {};
	VkDescriptorBufferInfoEx m_raster_env_buffer_info {};
	VkDescriptorBufferInfoEx m_instancing_indirection_buffer_info {};
	VkDescriptorBufferInfoEx m_instancing_constants_array_buffer_info{};

	VkDescriptorBufferInfoEx m_vertex_instructions_buffer_info {};
	VkDescriptorBufferInfoEx m_fragment_instructions_buffer_info {};

	rsx::simple_array<u8> m_multidraw_parameters_buffer;
	u32 m_draw_parameters_index = 0;                   // Cached - current subdraw id + layout offset id
	u64 m_xform_constants_dynamic_offset = 0;          // We manage transform_constants dynamic offset manually to alleviate performance penalty of doing a hot-patch of constants.
	u64 m_vertex_env_dynamic_offset = 0;
	u64 m_vertex_layout_dynamic_offset = 0;
	u64 m_fragment_constants_dynamic_offset = 0;
	u64 m_fragment_env_dynamic_offset = 0;
	u64 m_texture_parameters_dynamic_offset = 0;
	u64 m_stipple_array_dynamic_offset = 0;

	std::unique_ptr<rsx::data_heap::bulk_allocator<256, 96>> m_vertex_env_allocator;
	std::unique_ptr<rsx::data_heap::bulk_allocator<256, 16>> m_transform_constants_allocator;
	std::unique_ptr<rsx::data_heap::bulk_allocator<256, 16>> m_fragment_constants_allocator;

	std::vector<vk::frame_context_t> m_frame_context_storage;
	u32 m_max_async_frames = 0u;
	// Temp frame context to use if the real frame queue is overburdened. Only used for storage
	vk::frame_context_t m_aux_frame_context;

	u32 m_current_queue_index = 0;
	vk::frame_context_t* m_current_frame = nullptr;
	std::deque<vk::frame_context_t*> m_queued_frames;

	// Frame generation interpolates between the two most recent frames that have actually reached
	// the GPU, so the generated image belongs BEFORE the newer of that pair -- which means that real
	// frame has to be held back one present. Null when nothing is being held, which is every frame
	// with frame generation off.
	vk::frame_context_t* m_deferred_present_frame = nullptr;

	// Whether that holding-back is engaged. It costs one more swapchain image in flight than normal
	// presentation does, so a shallow swapchain keeps the old serialised path instead of starving
	// the generated frames of an image to be presented from.
	bool m_framegen_pipelined = false;

	// Acquire/present semaphores for GENERATED frames.
	//
	// The real frame has had a pair of these all along (frame_context_t's
	// acquire_signal_semaphore / present_wait_semaphore). Generated frames had none: they were
	// acquired with VK_NULL_HANDLE and written immediately, which is a use of a swapchain image
	// before the presentation engine has finished with it -- undefined, and on Adreno it shows as
	// smearing during motion that no interpolation setting can change, because the interpolation
	// was never the thing at fault.
	std::vector<VkSemaphore> m_framegen_present_sems;
	u32 m_framegen_sem_index = 0;
	// Frame generation acquires with this rather than a semaphore; see present_generated_frame.
	VkFence m_framegen_acquire_fence = VK_NULL_HANDLE;

	// Consecutive failed swapchain rebuilds in the acquire path. Reset on any success; once it
	// passes the threshold the swapchain is treated as unrecoverable rather than retried forever.
	u32 m_consecutive_swapchain_rebuild_failures = 0;

	// The command buffers the previous frame's generated images were blitted with. framegen writes
	// the same output images every generation, so those blits have to have retired before the next
	// generation starts overwriting them.
	vk::command_buffer_chunk* m_framegen_blit_cb[3] = {};
	u32 m_framegen_blit_cb_count = 0;

	// How the real frame reached the screen, for generated frames captured at the game's
	// resolution (vk::frame_gen::capture_game_frame): they are scaled into the same area with the
	// same filter, then get the overlays drawn on them just as the real frame did.
	areai m_framegen_present_area {};
	VkFilter m_framegen_filter = VK_FILTER_LINEAR;

	// Whether frame generation was on when the swapchain was built. The present mode depends on it
	// (swapchain.cpp forces FIFO while it is on), so a change has to rebuild the swapchain, the same
	// as a VSync change does.
	bool m_swapchain_framegen = false;

	VkViewport m_viewport {};
	VkRect2D m_scissor {};

	std::vector<u8> m_draw_buffers;

	shared_mutex m_flush_queue_mutex;
	vk::flush_request_task m_flush_requests;

	ullong m_last_cond_render_eval_hint = 0;

	// Offloader thread deadlock recovery
	rsx::atomic_bitmask_t<flush_queue_state> m_queue_status;
	// A fault has no owning context: the core asks every context in turn. This is the one whose cache the pending deadlock replay invalidates.
	rsx::context* m_offloader_fault_ctx = nullptr;
	utils::address_range32 m_offloader_fault_range;
	rsx::invalidation_cause m_offloader_fault_cause;

	vk::draw_call_t m_current_draw {};

	// The topology the draw actually wants, which stops being readable from m_pipeline_properties
	// once VK_EXT_extended_dynamic_state reduces that to a topology class. Written by
	// load_program(), which always runs before the subdraws of the clause it decoded.
	VkPrimitiveTopology m_current_primitive_topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

	u64 m_current_renderpass_key = 0;
	VkRenderPass m_cached_renderpass = VK_NULL_HANDLE;
	std::vector<vk::image*> m_fbo_images;

	std::unique_ptr<vk::image> m_overlay_recording_img;

	// ARMSX3 remote play (android/src/remote_play.cpp, Emu/RSX/remote_capture.h). Each captured frame is
	// drawn on the GPU into a slot and marked done by a GPU-written sequence number; the app's video
	// thread watches those numbers and takes finished frames itself, so the RSX thread records a few
	// commands per frame and never touches the pixels. A slot is one of:
	//  - an AHardwareBuffer the frame is copied into as RGBA, which the app hands to the video encoder
	//    as a GL texture: nothing passes through the CPU (needs VK_ANDROID_external_memory_android_
	//    hardware_buffer, and the app asking for it);
	//  - otherwise a mapped buffer holding the frame as the encoder's NV12, drawn by two small fragment
	//    passes (Y and UV), which the app copies out once.
	// A slot's shareable buffer and its Vulkan image. Destroyed through the resource manager, after
	// the frames that used it have completed.
	struct remote_capture_hardware
	{
		VkDevice device = VK_NULL_HANDLE;
		AHardwareBuffer* buffer = nullptr;
		VkImage image = VK_NULL_HANDLE;
		VkDeviceMemory memory = VK_NULL_HANDLE;
		u32 width = 0;
		u32 height = 0;

		remote_capture_hardware() = default;
		remote_capture_hardware(const remote_capture_hardware&) = delete;
		~remote_capture_hardware();
	};
	remote_capture::ring m_remote_capture_ring;
	std::array<std::unique_ptr<vk::buffer>, 4> m_remote_capture_buffers;
	std::array<std::unique_ptr<remote_capture_hardware>, 4> m_remote_capture_hardware;
	std::unique_ptr<vk::image> m_remote_capture_y;
	std::unique_ptr<vk::image> m_remote_capture_uv;
	std::unique_ptr<vk::image> m_remote_capture_rgba;
	std::unique_ptr<vk::buffer> m_remote_capture_marker;
	u32 m_remote_capture_seq = 0;
	bool m_remote_capture_published = false;
	bool m_remote_capture_hardware_failed = false;
	void remote_capture_record(vk::viewable_image* src, u32 width, u32 height, u32 max_size);
	std::unique_ptr<remote_capture_hardware> remote_capture_create_hardware(u32 width, u32 height);
	void remote_capture_release(bool device_idle);

	// Vertex layout
	rsx::vertex_input_layout m_vertex_layout;

	// Constant buffers whose host copy is out of date.
	rsx::bitmask_t<rsx::draw_state_t::group> m_stale_constants;

	vk::shader_interpreter m_shader_interpreter;
	rsx::bitmask_t<rsx::draw_state_t::group> m_interpreter_state;

#if defined(HAVE_X11) && defined(HAVE_VULKAN)
	Display *m_display_handle = nullptr;
#endif

public:
	~VKGSRender() override;

	VKGSRender(rsx::context* ctx, rsx::display_output* output) noexcept;

private:
	// Frame dumps (rsx_frame_dump.h, VKFrameDump.cpp). Inactive and free unless one was requested.
	struct frame_dump_state
	{
		rsx::frame_dump::dump_mode mode = rsx::frame_dump::dump_mode::none;
		std::string dir;          // this dump's folder, with a trailing slash
		std::string index;        // frame.txt, written when the dump ends
		std::string fb_desc;      // the render target set currently bound
		std::string stop_reason;  // set when a limit was reached; nothing more is written
		u64 bytes = 0;            // PNG bytes written
		u32 images = 0;           // images written
		u32 draws = 0;            // draws since the last save
		u32 frame_draws = 0;      // draws in the frame being dumped
		bool armed = false;       // asked for, waiting for the game's next flip to start the frame
		bool active = false;      // inside the frame being dumped
		bool take_final = false;  // save the image this flip shows
		bool take_screen = false; // read the screen buffer back on this flip
	} m_frame_dump;

	u64 m_frame_dump_flips = 0;   // flips since boot, for ARMSX3_DUMP_PASSES

	// A copy recorded into the command stream, written out as a PNG once the GPU has done it.
	struct frame_dump_copy
	{
		std::unique_ptr<vk::buffer> buf;
		std::string name, why, desc;
		u32 draws = 0;            // draws into it since the last save; shown for passes only
		u32 width = 0, height = 0, bpp = 0;
		VkFormat format = VK_FORMAT_UNDEFINED;
		u64 size = 0;
	};
	std::vector<frame_dump_copy> m_frame_dump_pending;

	void frame_dump_on_flip(const rsx::display_flip_info_t& info);
	void frame_dump_capture(const char* why);
	void frame_dump_save(const std::vector<vk::image*>& images, const char* why, const std::string& desc);
	void frame_dump_record(const std::vector<vk::image*>& images, const char* tag, const char* why, const std::string& desc);
	void frame_dump_write_pending();
	bool frame_dump_write(const frame_dump_copy& copy, const void* data);
	void frame_dump_finish();

	void close_and_submit_command_buffer(
		vk::fence* fence = nullptr,
		VkSemaphore wait_semaphore = VK_NULL_HANDLE,
		VkSemaphore signal_semaphore = VK_NULL_HANDLE,
		VkPipelineStageFlags pipeline_stage_flags = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

	void flush_command_queue(bool hard_sync = false, bool do_not_switch = false);
	void queue_swap_request();
	void frame_context_cleanup(vk::frame_context_t *ctx);
	void advance_queued_frames();
	void present(vk::frame_context_t *ctx);
	vk::command_buffer_chunk* present_generated_frame(VkImage src);
	bool reinitialize_swapchain();

	vk::viewable_image* get_present_source(vk::present_surface_info* info, const rsx::avconf& avconfig);

	void begin_render_pass();
	void close_render_pass();
	VkRenderPass get_render_pass();
	void invalidate_render_pass();

	void update_draw_state();
	void set_extended_dynamic_state();
	void check_present_status();

	vk::vertex_upload_info upload_vertex_data();
	rsx::simple_array<u8> m_scratch_mem;

	// Warm the shader interpreter and the on-disk pipeline cache
	void preload_shaders();

	bool load_program();
	void load_program_env();
	void update_vertex_env(u32 id, const vk::vertex_upload_info& vertex_info);
	void upload_transform_constants(const rsx::io_buffer& buffer);

	rsx::flags32_t load_texture_env();
	bool bind_texture_env();
	bool bind_interpreter_texture_env();

	static vk::context_resources* res(rsx::context* ctx) { return static_cast<vk::context_resources*>(ctx->resources.get()); }
	vk::context_resources* res() const { return res(m_ctx); }

public:
	void set_viewport();
	void set_scissor();
	void bind_viewport();

	// Sync
	void write_barrier(u32 address, u32 range) override;
	void on_sync_hint(rsx::FIFO::interrupt_hint hint, rsx::reports::sync_hint_payload_t payload) override;
	bool release_GCM_label(u32 type, u32 address, u32 data) override;

	void begin_occlusion_query(rsx::reports::occlusion_query_info* query) override;
	void end_occlusion_query(rsx::reports::occlusion_query_info* query) override;
	bool check_occlusion_query_status(rsx::reports::occlusion_query_info* query) override;
	void get_occlusion_query_result(rsx::reports::occlusion_query_info* query) override;
	void discard_occlusion_query(rsx::reports::occlusion_query_info* query) override;
	rsx::reports::occlusion_query_provider* occlusion_queries() override { return this; }

	// External callback in case we need to suddenly submit a commandlist unexpectedly, e.g in a violation handler
	void emergency_query_cleanup(vk::command_buffer* commands);

	// Retire finished work so the data heaps can reclaim. Narrow public entry point for
	// vk::reclaim_ring_memory(); flush_command_queue itself stays private.
	void retire_completed_work();

	// External callback to handle out of video memory problems
	bool on_vram_exhausted(rsx::problem_severity severity);

	// Handle pool creation failure due to fragmentation
	void on_descriptor_pool_fragmentation(bool is_fatal);

	// Conditional rendering
	void on_begin_conditional_rendering(const std::vector<rsx::reports::occlusion_query_info*>& sources) override;

	// Host sync object
	std::pair<volatile vk::host_data_t*, VkBuffer> map_host_object_data() const;
	void on_guest_texture_read(const vk::command_buffer& cmd);

	// GRAPH backend
	void patch_transform_constants(rsx::context* ctx, u32 index, u32 count) override;

	// Misc
	bool is_current_program_interpreted() const override;

	// Identifiers
	std::string logical_id() const override;
	std::string configured_logical_id() const override;

protected:
	void clear() override;
	void begin_draw() override;
	bool resolve_sampler_state(rsx::flags32_t& invalidated_programs) override;
	// ARMSX3: no frame context means no swapchain right now (a failed rebuild after the surface
	// went away) or a torn-down renderer; see rsx::render_backend::can_draw.
	bool can_draw() const override { return m_current_frame != nullptr; }
	bool end_draw() override;
	void emit_geometry(u32 sub_index) override;

	bool on_framebuffer_bound(const rsx::framebuffer_layout& layout) override;
	bool is_framebuffer_bound() const override;

	void on_init() override;
	void on_shutdown() override;
	std::unique_ptr<rsx::context_resources> create_context_resources(rsx::context* ctx) override;
	rsx::device_status check_device_status() const override;
	void on_bind_context(rsx::context* ctx) override;
	void on_unbind_context(rsx::context* ctx) override;
	void on_release_context_resources(rsx::context* ctx) override;
	bool present_frame(const rsx::display_flip_info_t& info) override;
	void post_present() override;

	bool on_renderctl(u32 request_code, void* args) override;

	bool on_local_task(rsx::FIFO::state state) override;
	bool scaled_image_from_memory(const rsx::blit_src_info& src, const rsx::blit_dst_info& dst, bool interpolate) override;
	void notify_tile_unbound(u32 tile) override;

	bool on_access_violation(rsx::context* ctx, u32 address, bool is_writing) override;
	void on_invalidate_memory_range(rsx::context* ctx, const utils::address_range32 &range, rsx::invalidation_cause cause) override;
	void on_unmap_memory_range(const utils::address_range32& range) override;
	void on_semaphore_acquire_wait() override;
};
