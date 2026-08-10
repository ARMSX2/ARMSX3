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
		draw_setup,       // Draw clause iteration glue left over once the scopes below are charged
		draw_prologue,    // rsx::thread::begin: conditional render eval and draw mode classify
		draw_epilogue,    // rsx::thread::end: clause cleanup, push buffers, ZCULL on_draw
		wr_barrier,       // Depth/colour surface write barriers issued before binding resources
		rtt_write,        // Render target on_write bookkeeping after the draw is recorded
		tex_release,      // Releasing uncached temporary texture subresources
		present_check,    // Mid-draw present status check when the frame context went dirty
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
		page_protect,     // mprotect for guest write tracking, including its TLB maintenance
		zcull,            // ZCULL occlusion report update
		local_task,       // Backend local task queue drained from the FIFO loop
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

	/**
	 * Plain globals, guarded by a thread-pointer check, rather than thread locals.
	 *
	 * These are read on every scope enter and exit. As thread locals the generic TLS model
	 * routed each access through the linker's tlsdesc resolver, which measured 21% of RSX
	 * thread samples against 0.07% on an uninstrumented build: the profiler dominated the
	 * FIFO bucket it was supposed to be measuring. Pinning them to initial-exec removed that
	 * cost but stopped every game booting, so that route is closed.
	 *
	 * Only the RSX thread's numbers are ever reported, so instead of giving every thread its
	 * own copy, there is one copy and everyone else is turned away at the door. On ARM64 the
	 * thread pointer is a single register read with no relocation and no resolver call, which
	 * makes the check cheaper than one tlsdesc access, never mind the six a scope used to do.
	 */
	extern accounting g_acc;
	extern bucket g_current;
	extern u64 g_last_switch;
	extern const void* g_owner_thread;

	/** Cheap identity check: one register read, no TLS machinery. */
	inline const void* current_thread_token()
	{
#if defined(__aarch64__)
		return __builtin_thread_pointer();
#else
		// Falls back to a real thread local off the hot path's critical arch.
		static thread_local char anchor = 0;
		return &anchor;
#endif
	}

	/** Claim the accounting for this thread. Called from the RSX thread when arming. */
	void bind_to_current_thread();

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
			, m_active(g_enabled.load(std::memory_order_relaxed) &&
				current_thread_token() == g_owner_thread)
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

	/**
	 * Ad-hoc counters, reported alongside the buckets.
	 *
	 * fetch_u32 refills the 1KB FIFO cache under a reservation lock, copying and then
	 * re-comparing every 128-byte line, so a refill moves 256 bytes per line fetched. Serving
	 * 256 commands that is negligible; once per command it is not. The refill also trims
	 * itself to whatever the guest has actually written, so when RSX keeps pace with the
	 * producer the cache can shrink to a few bytes and refill constantly. These say which of
	 * those two worlds we are in.
	 */
	extern u64 g_fifo_refills;
	extern u64 g_fifo_refill_bytes;

	/**
	 * Iterations of the RSX dispatch loop, one per FIFO command.
	 *
	 * With every sub-unit in that loop now measured and small, whatever remains in
	 * fifo_decode is the dispatch itself, and the only question left is whether that is a
	 * lot of commands at a reasonable cost each or a few at an unreasonable one. Those want
	 * opposite fixes, and the per-command cost this yields is the number that decides.
	 *
	 * An increment behind the existing enabled() branch, with no counter-timer read, so it
	 * does not repeat the mistake of a per-command scope measuring mostly itself.
	 */
	extern u64 g_fifo_commands;

	/**
	 * Methods actually dispatched, as opposed to [g_fifo_commands], which counts entries
	 * into run_FIFO. One of those drains a WHOLE packet, so dividing by it prices a packet
	 * and not a method. Sonic '06 averages roughly 17 methods per packet, so the two differ
	 * by more than an order of magnitude and the per-packet figure was being read as a
	 * per-command one.
	 */
	extern u64 g_fifo_dispatches;

	/**
	 * Draws that reached rsx::thread::end, so the per-draw buckets can be priced.
	 *
	 * Draw setup being the largest bucket says nothing on its own: a lot of draws at a sane
	 * cost each and a few at an insane one are the same number and want opposite fixes. This
	 * is the denominator that tells them apart, the same way g_fifo_dispatches did for decode.
	 */
	extern u64 g_draw_calls;

	/**
	 * Commands seen per RSX method register, indexed by (id >> 2).
	 *
	 * The per-command figure came out around a microsecond, which is far too much for
	 * reading a word and calling a handler, so the cost is in a handler rather than spread
	 * evenly. Counting says which methods make up the volume; a handful dominating means a
	 * fast path is worth writing, an even spread means the dispatch itself is the problem.
	 *
	 * 64KB of counters, only touched while profiling is armed.
	 */
	inline constexpr usz method_slot_count = 0x4000;
	extern u32 g_method_counts[method_slot_count];

	// Refills that could not take their data immediately and fell into the retry spin, plus
	// the microseconds burned there. That spin is billed to the FIFO bucket rather than to
	// idle, so without these there is no way to tell RSX doing work from RSX waiting on the
	// guest to produce commands.
	extern u64 g_fifo_refill_stalls;
	extern u64 g_fifo_refill_stall_us;

	/**
	 * Pipeline drains by cause.
	 *
	 * Each flush_command_queue submits and then the next readback wait drains everything
	 * queued, which is what keeps CPU and GPU serialised: GPU work and fence wait sum to the
	 * whole frame instead of overlapping. Texture cache misses are now zero, so the readback
	 * faults that were assumed responsible are not, and these say what is.
	 */
	// One counter per submission site. Three hand-picked candidates all came back at zero
	// while submissions stayed near five per frame, so this covers every caller rather than
	// guessing another.
	// Render pass begins per frame.
	//
	// Each one is a tile store and reload on a tiled GPU such as Adreno, which is the most
	// expensive thing that architecture does. The GPU timer could not measure them because
	// its per-frame event cap was blown out by roughly 1800 per frame, so count them plainly.
	extern u64 g_render_passes;

	// Page protection traffic. Every change is an mprotect, which on ARM forces TLB
	// maintenance, and every fault on a protected page is a SIGSEGV round trip through the
	// handler before it. The RSX thread was measured at about 34% kernel time reached
	// through exactly that path, from a memcpy in the vertex upload.
	extern u64 g_mprotect_calls;
	extern u64 g_mprotect_bytes;
	extern u64 g_access_violations;

	// Which site tore the pass down. Every one of these is a tile store and reload on a
	// tiler, so the distribution decides what is worth batching or deferring.
	inline constexpr u32 rp_site_count = 20;
	extern u64 g_rp_sites[rp_site_count];
	extern const char* g_rp_site_names[rp_site_count];

	inline constexpr u32 flush_site_count = 21;
	extern u64 g_flush_sites[flush_site_count];
	extern const char* g_flush_site_names[flush_site_count];

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
