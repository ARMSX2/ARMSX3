#pragma once

#include "util/types.hpp"
#include "util/tsc.hpp"

#include <array>
#include <atomic>

/**
 * Exclusive time accounting for the RSX thread.
 *
 * The overlay's RSX percentage cannot answer "what is the RSX thread doing", because it
 * only measures what the thread is NOT doing: rsx::thread::get_load() counts everything
 * except four idle sites (frame limiter sleep, FIFO starvation, FIFO spin, guest semaphore
 * wait). A thread burning CPU decoding commands and a thread spinning on a Vulkan fence
 * both read as 100% load, which is exactly the distinction that decides what to optimise.
 *
 * This splits that time into exclusive buckets. Exclusive, not inclusive: entering a scope
 * charges the elapsed time to whatever bucket was active and then switches, so nested
 * scopes attribute to the innermost one and the totals sum to wall-clock rather than
 * double counting a caller and its callee.
 *
 * Reads the counter-timer directly rather than get_system_time(), because the fine scopes
 * run thousands of times per frame. On Qualcomm the virtual counter ticks at 19.2MHz, so a
 * single scope resolves to about 52ns; individual samples are coarse but the per-frame
 * aggregate is not, which is what gets reported.
 *
 * Off by default and gated on a relaxed atomic load, so an untouched build pays for one
 * predictable branch per scope.
 */
namespace rsx::prof
{
	enum class bucket : u8
	{
		fifo_decode,      // Reading and dispatching FIFO commands
		draw_setup,       // Draw clause setup and state validation
		vertex,           // Vertex and index processing, including layout conversion
		shader_translate, // RSX shader decompilation to GLSL/SPIR-V
		shader_compile,   // Host driver compiling the translated shader
		pipeline,         // Vulkan pipeline lookup and creation
		descriptors,      // Descriptor set lookup and update
		texcache_lookup,  // Texture cache address search and match
		texture_upload,   // Texture upload, deswizzle and format conversion
		rt_prep,          // Render target allocation and binding
		blit_resolve,     // Framebuffer copies, resolves and blits
		barrier,          // Pipeline barriers and render pass transitions
		cmdbuf,           // Command buffer recording
		submit,           // Queue submission
		fence_wait,       // Waiting on fences and events, including readback sync
		present_wait,     // Swapchain acquire and present
		idle,             // Deliberately idle: FIFO empty, frame limiter, semaphore
		unclassified,     // RSX thread time not covered by any scope above

		count
	};

	inline constexpr usz bucket_count = static_cast<usz>(bucket::count);

	const char* name_of(bucket b);

	/** Live totals in counter ticks, plus the frame count they were gathered over. */
	struct accounting
	{
		std::array<u64, bucket_count> ticks{};
		u64 frames = 0;
		u64 window_start = 0;
	};

	extern std::atomic<bool> g_enabled;
	extern thread_local accounting g_acc;
	extern thread_local bucket g_current;
	extern thread_local u64 g_last_switch;

	/**
	 * Charge elapsed time to the active bucket and make `next` active. Returns the old one.
	 *
	 * Per-thread, deliberately. Some of these scopes sit on paths that run on whichever
	 * guest thread faulted rather than on the RSX thread: on_access_violation is reached
	 * from the PPU or SPU that touched GPU-written memory. Sharing one accumulator would
	 * mix another thread's wall clock into the RSX figures, and since the first switch on
	 * a fresh thread has no previous timestamp it would charge `now` itself as a duration.
	 * Only the RSX thread's copy is ever reported, so work attributed to a guest thread is
	 * simply not counted rather than counted wrongly.
	 */
	inline bucket switch_to(bucket next)
	{
		const u64 now = utils::get_tsc();
		const bucket prev = g_current;

		// Zero means this thread has never switched, so there is no interval to charge.
		if (g_last_switch) [[likely]]
		{
			g_acc.ticks[static_cast<usz>(prev)] += now - g_last_switch;
		}

		g_current = next;
		g_last_switch = now;

		return prev;
	}

	/** RAII bucket switch. Restores the enclosing bucket, so nesting attributes inward. */
	class scope
	{
		bucket m_prev;
		bool m_active;

	public:
		explicit scope(bucket b)
			: m_prev(bucket::unclassified)
			, m_active(g_enabled.load(std::memory_order_relaxed))
		{
			if (m_active) [[unlikely]]
			{
				m_prev = switch_to(b);
			}
		}

		~scope()
		{
			if (m_active) [[unlikely]]
			{
				switch_to(m_prev);
			}
		}

		scope(const scope&) = delete;
		scope& operator=(const scope&) = delete;
	};

	/** Call once per frame from the RSX thread so the report can express per-frame cost. */
	void tick_frame();

	/** Write the current window to the log and start a new one. Safe to call from anywhere. */
	void dump_and_reset();

	void set_enabled(bool enabled);
	inline bool enabled() { return g_enabled.load(std::memory_order_relaxed); }
}

// Two levels, so __LINE__ expands to its value before being pasted rather than literally.
#define RSX_PROF_CAT_(a, b) a##b
#define RSX_PROF_CAT(a, b) RSX_PROF_CAT_(a, b)
#define RSX_PROF_SCOPE(b) ::rsx::prof::scope RSX_PROF_CAT(rsx_prof_scope_, __LINE__) { ::rsx::prof::bucket::b }
