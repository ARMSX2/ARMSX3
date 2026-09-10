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
		fifo_refill,      // Refilling the FIFO cache from guest memory under a reservation lock
		method_call,      // Inside a method handler, as opposed to the loop that dispatches it
		rsx_barrier,      // read_barrier/write_barrier from the DMA and blit engines
		dma_copy,         // The memory copy a DMA or blit transfer exists to perform
		blit_scale,       // Software scale/convert fallback in the 2D blit engine
		xform_program,    // NV4097_SET_TRANSFORM_PROGRAM: vertex ucode upload and dirty check
		xform_const,      // NV4097_SET_TRANSFORM_CONSTANT: transform constant upload
		draw_setup,       // Draw clause iteration glue left over once the scopes below are charged
		draw_prologue,    // rsx::thread::begin: conditional render eval and draw mode classify
		draw_epilogue,    // rsx::thread::end: clause cleanup, push buffers, ZCULL on_draw
		wr_barrier,       // Depth/colour surface write barriers issued before binding resources
		rtt_write,        // Render target on_write bookkeeping after the draw is recorded
		tex_release,      // Releasing uncached temporary texture subresources
		present_check,    // Mid-draw present status check when the frame context went dirty
		swap_wait,        // Blocking wait on the presented frame's command buffer fence
		res_trim,         // Per-frame resource reclaim: overlay dispose, heap trim, snapshot restore
		res_gc,           // Destroying the GPU objects retired by a completed event
		fence_poll,       // vkGetFenceStatus on the presented frame's fence
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
		idle,             // Deliberately idle: frame limiter and anything not split out below
		idle_fifo,        // FIFO empty: the guest has not queued any commands
		idle_sema,        // nv406e semaphore acquire: waiting for the guest to release us
		idle_pause,       // cpu_wait: emulator-level pause/sync, not the guest
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

		/**
		 * End the scope before it goes out of scope. Idempotent.
		 *
		 * For the case where the region worth measuring ends part-way through a function and
		 * the rest belongs to a different bucket, without having to introduce a block whose
		 * only purpose is to close a brace.
		 */
		void close()
		{
			if (m_active) [[unlikely]]
			{
				switch_to(m_prev);
				m_active = false;
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
	 * Entries into the mid-draw present check, and frame contexts actually reclaimed by one.
	 *
	 * The block is written as a rare async-flip fixup and measured as two thirds of the RSX
	 * thread, so the first thing to establish is whether that is one long stall per frame or
	 * a short one repeated per draw. Those are the same milliseconds and different bugs.
	 */
	extern u64 g_present_checks;
	extern u64 g_frame_cleanups;

	/**
	 * vkGetFenceStatus calls, and how many came back VK_NOT_READY.
	 *
	 * The poll bucket is over half the RSX thread while every call site that reaches it looks
	 * like it runs once or twice a frame. Either a status query is blocking for milliseconds,
	 * which would make it a driver problem, or it is being called orders of magnitude more
	 * often than the call sites suggest, which would make it ours. The count decides, and the
	 * not-ready share says whether the driver is being asked about work that is still running.
	 */
	extern u64 g_fence_polls;
	extern u64 g_fence_polls_not_ready;

	/**
	 * Calls into the two batching FIFO handlers, and methods each consumed.
	 *
	 * Both take a run of methods and skip the rest, so their share of the method histogram is
	 * methods, not calls, and dividing the bucket by the histogram would price a batch as if
	 * it were one method. These are the call counts the buckets actually divide by.
	 */
	/**
	 * Draws issued inside each render pass, keyed by the pass's ordinal within the frame.
	 *
	 * Pairs with the GPU timer's per-pass cost. A pass that is expensive with few draws is
	 * expensive per draw; one that is expensive with most of the frame's draws in it is
	 * carrying the geometry. Same milliseconds, opposite fixes.
	 */
	inline constexpr u32 pass_slot_count = 96;
	extern u32 g_pass_ordinal;
	extern u64 g_pass_draws[pass_slot_count];

	/**
	 * What each pass is, so an expensive ordinal can be named in the game's terms.
	 *
	 * Pass #6 costs 76us a draw against 2.4us in pass #8, and is insensitive to resolution, so
	 * it is not fragment work. Target size says whether it is a shadow map, a reflection or the
	 * main scene; vertices per draw separates a lot of geometry from a lot of cost per vertex.
	 * Those want different fixes and nothing measured so far tells them apart.
	 */
	extern u16 g_pass_width[pass_slot_count];
	extern u16 g_pass_height[pass_slot_count];
	extern u64 g_pass_vertices[pass_slot_count];

	/**
	 * Barriers issued while a pass is open, and how many were cyclic-reference barriers.
	 *
	 * Pass #6 spends 9.8 ms on 44 draws and 33k vertices, which is neither vertex work nor,
	 * apparently, pixel work. What is left is the GPU being serialised inside the pass. A
	 * by-region self-dependency barrier makes a tiler resolve the tile and fetch it back, so
	 * one per draw would cost about what is being measured -- and texture_barrier now
	 * deliberately keeps the pass open on Android and issues exactly that.
	 *
	 * Counted separately from the render pass teardowns, which are the cost this replaced.
	 */
	extern u64 g_pass_barriers[pass_slot_count];
	extern u64 g_pass_cyclic[pass_slot_count];

	/**
	 * Shader ucode length summed per pass, so cost per vertex can be read against complexity.
	 *
	 * Pass six costs about 26 times what pass eight does per vertex, with no barriers, no
	 * sensitivity to resolution and no change when tiling is bypassed entirely. The only thing
	 * left that behaves that way is the vertex shader, and this decides whether there is a bug
	 * here at all: shaders genuinely that much longer are the game's own workload and nothing
	 * to fix, while comparable ones mean something is happening to them that should not be.
	 */
	extern u64 g_pass_vp_words[pass_slot_count];
	extern u64 g_pass_fp_words[pass_slot_count];

	/**
	 * Draws the GPU actually receives, as opposed to draw clauses the guest issued.
	 *
	 * A clause is expanded over its subranges, so one entry in the draw count can become
	 * thousands of draws. Batching them through VK_EXT_multi_draw saves the command overhead
	 * on our side and changes nothing about how many the GPU processes.
	 *
	 * This is the one thing left that fits pass six: shorter shaders than the cheap passes, a
	 * quarter of their vertices, no barriers, indifferent to resolution and to tiling being
	 * switched off, and seven times the cost. Thousands of tiny draws at a fixed cost each look
	 * exactly like that, and nothing measured so far would show them.
	 */
	extern u64 g_pass_subdraws[pass_slot_count];

	/**
	 * Occlusion queries opened inside each pass.
	 *
	 * The last category not measured. Everything counted so far describes what a draw
	 * CONTAINS -- vertices, pixels, shader length, subdraws, barriers -- and by all of those
	 * pass six should be the cheapest of the expensive passes rather than the dearest. An
	 * occlusion query is none of those things: on a tiler it makes the visibility stream
	 * resolve, it costs the same whatever the framebuffer size, and it is invisible to every
	 * other counter here. A pass running one per draw would look exactly like this one.
	 */
	extern u64 g_pass_queries[pass_slot_count];

	inline void note_pass_query()
	{
		if (g_pass_ordinal >= pass_slot_count) return;
		g_pass_queries[g_pass_ordinal]++;
	}

	inline void note_subdraws(u32 count)
	{
		if (g_pass_ordinal >= pass_slot_count) return;
		g_pass_subdraws[g_pass_ordinal] += count;
	}

	inline void note_pass_barrier(bool cyclic)
	{
		if (g_pass_ordinal >= pass_slot_count) return;
		g_pass_barriers[g_pass_ordinal]++;
		if (cyclic) g_pass_cyclic[g_pass_ordinal]++;
	}

	extern u64 g_xform_program_calls;
	extern u64 g_xform_program_words;
	extern u64 g_xform_const_calls;
	extern u64 g_xform_const_words;

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

	/**
	 * Counter ticks spent inside each method's handler, indexed the same way as the counts.
	 *
	 * The handler bodies are 60% of the RSX thread in Arkham City and the dispatch machinery
	 * around them is 3.5%, so the question is which handlers, and volume does not answer it:
	 * the busiest method by count may be trivial and a rare one may be doing the work.
	 *
	 * Free to collect. The dispatch site already brackets the call with two counter reads to
	 * fill the method_call bucket; this keeps the difference instead of discarding it.
	 */
	extern u64 g_method_ticks[method_slot_count];

	/**
	 * Bucket switch that also bills the elapsed time to a method slot.
	 *
	 * Inclusive of anything the handler calls into, including scopes that charge themselves
	 * elsewhere -- for finding which handler is expensive that is the useful reading, and the
	 * per-bucket totals remain exclusive as before.
	 */
	class method_scope
	{
		bucket m_prev;
		bool m_active;
		u32 m_slot;
		u64 m_start;

	public:
		explicit method_scope(u32 slot)
			: m_prev(bucket::unclassified)
			, m_active(g_enabled.load(std::memory_order_relaxed) &&
				current_thread_token() == g_owner_thread)
			, m_slot(slot)
			, m_start(0)
		{
			if (m_active) [[unlikely]]
			{
				m_prev = switch_to(bucket::method_call);
				m_start = g_last_switch;
			}
		}

		~method_scope()
		{
			if (m_active) [[unlikely]]
			{
				switch_to(m_prev);
				g_method_ticks[m_slot] += g_last_switch - m_start;
			}
		}

		method_scope(const method_scope&) = delete;
		method_scope& operator=(const method_scope&) = delete;
	};

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

	// Wall time spent inside on_access_violation, summed across GUEST threads. Not a bucket:
	// buckets only accumulate on the RSX thread, and this handler never runs there.
	extern std::atomic<u64> g_access_violation_tsc;

	// Which site tore the pass down. Every one of these is a tile store and reload on a
	// tiler, so the distribution decides what is worth batching or deferring.
	inline constexpr u32 rp_site_count = 20;
	extern u64 g_rp_sites[rp_site_count];
	extern const char* g_rp_site_names[rp_site_count];

	/**
	 * Render pass teardowns attributed by return address.
	 *
	 * change_image_layout ends the open pass and is reached from 75 call sites, so the single
	 * counter on it says how many teardowns happened and never which code wanted them. Tagging
	 * 75 sites by hand is not worth it and would still miss the next one; recording the return
	 * address costs one instruction on a path taken about twenty times a frame.
	 *
	 * Two levels, because most callers do not call it directly: image::change_layout funnels
	 * them, so a single level would report that function for nearly everything. The first table
	 * is whoever called change_image_layout, the second is whoever called change_layout.
	 *
	 * Addresses are reported raw plus an offset from the module base, which llvm-symbolizer
	 * turns into names against the unstripped build-android copy.
	 */
	inline constexpr usz rp_caller_slots = 24;
	extern const void* g_rp_callers[2][rp_caller_slots];
	extern u64 g_rp_caller_counts[2][rp_caller_slots];

	void note_rp_teardown(const void* caller, u32 level);

	inline constexpr u32 flush_site_count = 21;
	extern u64 g_flush_sites[flush_site_count];
	extern const char* g_flush_site_names[flush_site_count];

	/** Call once per frame from the RSX thread so the report can express per-frame cost. */
	void tick_frame();

	// Timestamp of the last flip, and a watchdog sampled from the vblank thread (~60Hz).
	//
	// The spike trace cannot answer "what was the guest doing during the stall": it runs
	// at the flip that ENDS the slow frame, by which point the guest has finished its work
	// and gone back to waiting. Sampling there showed every thread parked on a semaphore --
	// which is also exactly what a healthy frame looks like at that same instant. Useless.
	//
	// This one fires while the stall is still happening.
	extern std::atomic<u64> g_last_frame_tsc;
	void stall_watchdog();

	// Statistical guest profiler. Runs on its own thread so samples are not phase-locked to
	// the frame clock; start_sampler() is idempotent.
	// Ring of the last FIFO methods the RSX executed.
	//
	// On this hang the guest stops submitting while every thread and sync object stays healthy, so
	// the question is what it set up immediately before going quiet. The FIFO dump already shows
	// the last command word (always 0x00041d70 = NV4097_BACK_END_WRITE_SEMAPHORE_RELEASE here);
	// the ring gives the sequence around it, which is what says WHICH label and what the guest is
	// now waiting to observe.
	struct fifo_rec_t
	{
		u32 reg;
		u32 arg;
	};

	inline constexpr u32 fifo_ring_size = 64;
	extern fifo_rec_t g_fifo_ring[fifo_ring_size];
	extern u32 g_fifo_ring_pos; // racy by design; a torn slot only costs one ring entry

	void sample_guest_pc();
	void start_sampler();

	// Joins the sampler. Must run before Emu teardown destroys what it walks.
	void stop_sampler();

	/** Write the current window to the log and start a new one. Safe to call from anywhere. */
	void dump_and_reset();

	/** Report the bucket the RSX thread is stuck in when frames have stopped arriving.
	 *  Rate-limited internally; safe to call from the FIFO loop.
	 *  Returns true on the calls that actually reported, so a caller can attach its own
	 *  diagnosis to the same condition without repeating the rate limit. */
	bool poll_stall();

	void set_enabled(bool enabled);
	inline bool enabled() { return g_enabled.load(std::memory_order_relaxed); }
}

// Two levels, so __LINE__ expands to its value before being pasted rather than literally.
#define RSX_PROF_CAT_(a, b) a##b
#define RSX_PROF_CAT(a, b) RSX_PROF_CAT_(a, b)
#define RSX_PROF_SCOPE(b) ::rsx::prof::scope RSX_PROF_CAT(rsx_prof_scope_, __LINE__) { ::rsx::prof::bucket::b }
