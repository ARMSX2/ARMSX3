// SPDX-License-Identifier: GPL-2.0-only
// The learned JIT pool split: this device's stored session peaks and the pure C11 rule that sizes the next launch's prepare.
#pragma once

#include <stdint.h>
#include <string.h>

// Outside the backup allow-list on purpose: the rate is per device.
#define ARMSX3_JIT_HISTORY_KEY "armsx3.jit.poolHistory"
// A layout change bumps it, so an old store reads as no history.
#define ARMSX3_JIT_HISTORY_MAGIC 0x41334a31u
#define ARMSX3_JIT_HISTORY_SESSIONS 8

// The StikDebug script's time budget in ms; per device and outside the backup allow-list, set only by the Debug leg.
#define ARMSX3_JIT_BUDGET_KEY "armsx3.jit.scriptBudgetMs"
// Long enough to prove the stubs, pool 1 and a useful share of pools 2 and 3 at the slowest measured walk.
#define ARMSX3_JIT_BUDGET_MIN_MS 5000
// Below the app's 30 s prepare wait, so a walk that spends its budget is never cut off.
#define ARMSX3_JIT_BUDGET_MAX_MS 25000

// MiB. The defaults cover the largest measured pool 2 and pool 3 peaks inside a 15 s walk.
#define ARMSX3_JIT_SPLIT_SHARE2_MIB 152ull
#define ARMSX3_JIT_SPLIT_SHARE3_MIB 240ull
#define ARMSX3_JIT_SPLIT_FLOOR2_MIB 64ull
#define ARMSX3_JIT_SPLIT_FLOOR3_MIB 128ull
// Proven before pool 3's share, so a walk that runs out of time still leaves pool 2 room to start.
#define ARMSX3_JIT_SPLIT_WALK2_MIB 16ull
#define ARMSX3_JIT_SPLIT_MARGIN_MIB 32ull
#define ARMSX3_JIT_SPLIT_GROW_MIB 64ull
#define ARMSX3_JIT_SPLIT_STEP_MIB 2ull
#define ARMSX3_JIT_SPLIT_BUDGET_PERCENT 90ull

#define ARMSX3_JIT_SPLIT_ROUND 0x200000ull
#define ARMSX3_JIT_SPLIT_PAGE 0x4000ull
#define ARMSX3_JIT_SPLIT_RATE_MAX_MS 60000u
#define ARMSX3_JIT_SPLIT_RATE_MAX_PAGES 131072u

#define ARMSX3_JIT_SPLIT_MIB(v) ((v) * 0x100000ull)

struct armsx3_jit_pool_session
{
	uint32_t peak2_mib;
	uint32_t peak3_mib;
	uint32_t hit2;
	uint32_t hit3;
};

struct armsx3_jit_pool_history
{
	uint32_t magic;
	uint32_t count;
	struct armsx3_jit_pool_session session[ARMSX3_JIT_HISTORY_SESSIONS];
	uint32_t rate_pages;
	uint32_t rate_ms;
};

// Bytes, except budget_pages (16 KiB pages) and sessions.
struct armsx3_jit_split
{
	unsigned long long size2, size3, share2, share3, need2, need3, budget_pages;
	unsigned sessions;
};

static inline unsigned long long armsx3_jit_split_need(unsigned long long raw, unsigned long long floor_bytes, unsigned long long max_bytes)
{
	const unsigned long long quarter = raw / 4;
	const unsigned long long margin = quarter > ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_MARGIN_MIB) ? quarter : ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_MARGIN_MIB);
	unsigned long long need = (raw + margin + ARMSX3_JIT_SPLIT_ROUND - 1) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
	need = need < floor_bytes ? floor_bytes : need;
	return need > max_bytes ? max_bytes : need;
}

static inline unsigned long long armsx3_jit_split_raw(uint32_t peak_mib, uint32_t hit)
{
	const unsigned long long peak = ARMSX3_JIT_SPLIT_MIB(peak_mib);
	const unsigned long long grown = peak * 3 / 2 > peak + ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_GROW_MIB) ? peak * 3 / 2 : peak + ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_GROW_MIB);
	return hit ? grown : peak;
}

// A pool's bare peak: the measured (or grown) peak rounded up to ROUND, never above its need.
static inline unsigned long long armsx3_jit_split_bare(unsigned long long raw, unsigned long long need)
{
	const unsigned long long bare = (raw + ARMSX3_JIT_SPLIT_ROUND - 1) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
	return bare < need ? bare : need;
}

static inline unsigned long long armsx3_jit_split_scale(unsigned long long share, unsigned long long floor_bytes, unsigned long long avail, unsigned long long total)
{
	const unsigned long long low = floor_bytes < share ? floor_bytes : share;
	const unsigned long long scaled = share * avail / total / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
	return scaled > low ? scaled : low;
}

static inline void armsx3_jit_split_compute(const struct armsx3_jit_pool_history* h, int low_memory, unsigned long long tier2,
	unsigned long long tier3, unsigned long long pool2_max, unsigned long long pool3_max, unsigned long long fixed_pages,
	unsigned long long budget_ms, struct armsx3_jit_split* out)
{
	const unsigned long long floor2 = ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR2_MIB);
	const unsigned long long floor3 = ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_FLOOR3_MIB);
	unsigned long long raw2 = 0, raw3 = 0;
	unsigned sessions = 0;

	memset(out, 0, sizeof *out);

	// (1) The history counts only with the right magic and count, a session only with plausible peaks and flags.
	const int usable = h != NULL && h->magic == ARMSX3_JIT_HISTORY_MAGIC && h->count <= ARMSX3_JIT_HISTORY_SESSIONS;

	for (uint32_t i = 0; usable && i < h->count; i++)
	{
		const struct armsx3_jit_pool_session* s = &h->session[i];

		if (ARMSX3_JIT_SPLIT_MIB(s->peak2_mib) > pool2_max || ARMSX3_JIT_SPLIT_MIB(s->peak3_mib) > pool3_max || s->hit2 > 1 || s->hit3 > 1)
		{
			continue;
		}

		// (2) The largest peak, grown by half (at least GROW) where that pool's cap was hit.
		const unsigned long long r2 = armsx3_jit_split_raw(s->peak2_mib, s->hit2);
		const unsigned long long r3 = armsx3_jit_split_raw(s->peak3_mib, s->hit3);
		raw2 = r2 > raw2 ? r2 : raw2;
		raw3 = r3 > raw3 ? r3 : raw3;
		sessions++;
	}

	if (sessions == 0)
	{
		// (3) With no counted session take the tiers and default shares; a stored rate alone is ignored.
		out->size2 = tier2;
		out->size3 = tier3;
		out->share2 = ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) < tier2 ? ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE2_MIB) : tier2;
		out->share3 = ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) < tier3 ? ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_SHARE3_MIB) : tier3;
		return;
	}

	out->need2 = armsx3_jit_split_need(raw2, floor2, pool2_max);
	out->need3 = armsx3_jit_split_need(raw3, floor3, pool3_max);

	// (3) A 4 GB device prepares the need itself; any other never less than its tier.
	out->size2 = low_memory || out->need2 > tier2 ? out->need2 : tier2;
	out->size3 = low_memory || out->need3 > tier3 ? out->need3 : tier3;
	out->share2 = out->need2;
	out->share3 = out->need3;

	// (4) If the needs overrun 90 % of the walk the margins go first, then the bare peaks, in proportion, never below min(floor, peak).
	if (h->rate_ms > 0 && h->rate_ms <= ARMSX3_JIT_SPLIT_RATE_MAX_MS && h->rate_pages > 0 && h->rate_pages <= ARMSX3_JIT_SPLIT_RATE_MAX_PAGES)
	{
		out->budget_pages = h->rate_pages * budget_ms / h->rate_ms;
	}

	if (out->budget_pages != 0)
	{
		const unsigned long long target = out->budget_pages * ARMSX3_JIT_SPLIT_BUDGET_PERCENT / 100;
		const unsigned long long total = out->share2 / ARMSX3_JIT_SPLIT_PAGE + out->share3 / ARMSX3_JIT_SPLIT_PAGE;

		if (fixed_pages + total > target)
		{
			const unsigned long long bare2 = armsx3_jit_split_bare(raw2, out->need2);
			const unsigned long long bare3 = armsx3_jit_split_bare(raw3, out->need3);
			const unsigned long long bare = bare2 / ARMSX3_JIT_SPLIT_PAGE + bare3 / ARMSX3_JIT_SPLIT_PAGE;

			if (fixed_pages + bare <= target)
			{
				// Here total > bare, so the margins are not empty.
				const unsigned long long spare = target - fixed_pages - bare;
				out->share2 = bare2 + (out->need2 - bare2) * spare / (total - bare) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
				out->share3 = bare3 + (out->need3 - bare3) * spare / (total - bare) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
			}
			else if (fixed_pages + bare <= out->budget_pages)
			{
				out->share2 = bare2;
				out->share3 = bare3;
			}
			else
			{
				const unsigned long long avail = out->budget_pages > fixed_pages ? out->budget_pages - fixed_pages : 0;
				out->share2 = armsx3_jit_split_scale(bare2, floor2, avail, bare);
				out->share3 = armsx3_jit_split_scale(bare3, floor3, avail, bare);
			}
		}
	}

	// Pool 2's share starts after the walk's pool 2 floor, so it is never smaller, unless pool 2 itself is.
	const unsigned long long walk2 = ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_WALK2_MIB) < out->size2 ? ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_WALK2_MIB) : out->size2;
	out->share2 = out->share2 < walk2 ? walk2 : out->share2;

	// (5) The counted sessions; the six byte values are set above.
	out->sessions = sessions;
}

// Each peak moves up only by at least STEP MiB, each hit flag only turns on; 1 when anything moved.
static inline int armsx3_jit_session_merge(struct armsx3_jit_pool_session* noted, const struct armsx3_jit_pool_session* now)
{
	int moved = 0;

	if (now->peak2_mib >= noted->peak2_mib + ARMSX3_JIT_SPLIT_STEP_MIB)
	{
		noted->peak2_mib = now->peak2_mib;
		moved = 1;
	}

	if (now->peak3_mib >= noted->peak3_mib + ARMSX3_JIT_SPLIT_STEP_MIB)
	{
		noted->peak3_mib = now->peak3_mib;
		moved = 1;
	}

	if (now->hit2 && !noted->hit2)
	{
		noted->hit2 = 1;
		moved = 1;
	}

	if (now->hit3 && !noted->hit3)
	{
		noted->hit3 = 1;
		moved = 1;
	}

	return moved;
}

// This process's session keeps its own slot; the first put takes the next one, dropping the oldest from a full ring.
static inline void armsx3_jit_history_put(struct armsx3_jit_pool_history* h, int* slot, const struct armsx3_jit_pool_session* s)
{
	if (h->count > ARMSX3_JIT_HISTORY_SESSIONS)
	{
		h->count = ARMSX3_JIT_HISTORY_SESSIONS;
	}

	if (*slot < 0)
	{
		if (h->count == ARMSX3_JIT_HISTORY_SESSIONS)
		{
			memmove(&h->session[0], &h->session[1], sizeof h->session[0] * (ARMSX3_JIT_HISTORY_SESSIONS - 1));
			h->count--;
		}

		*slot = h->count;  // at most ARMSX3_JIT_HISTORY_SESSIONS - 1 here
		h->count++;
	}

	h->session[*slot] = *s;
}

// A stored budget counts only inside MIN..MAX, else the compiled fallback; no cast, since the C++ build rejects C-style ones.
static inline int armsx3_jit_budget_ms(long long stored, int fallback)
{
	return stored >= ARMSX3_JIT_BUDGET_MIN_MS && stored <= ARMSX3_JIT_BUDGET_MAX_MS ? stored : fallback;
}

// The second StikDebug pass, mid-game when a pool nears its cap: two ranges, then four empty ones.
#define ARMSX3_JIT_SECOND_WORDS 12

// One pool's step above its cap, bounded because every prepared page stays resident.
static inline unsigned long long armsx3_jit_second_step(unsigned long long cap, unsigned long long max)
{
	const unsigned long long half = cap / 2;
	const unsigned long long grow = half > ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_GROW_MIB) ? half : ARMSX3_JIT_SPLIT_MIB(ARMSX3_JIT_SPLIT_GROW_MIB);
	const unsigned long long step = (grow + ARMSX3_JIT_SPLIT_ROUND - 1) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
	return cap >= max ? 0 : step < max - cap ? step : max - cap;
}

// The second walk in block offsets: the triggering pool first, then the other; allow bounds both steps, trimming the second.
static inline void armsx3_jit_second_walk(int first, unsigned long long cap2, unsigned long long max2, unsigned long long base3,
	unsigned long long cap3, unsigned long long max3, unsigned long long allow, unsigned long long* w)
{
	unsigned long long step2 = armsx3_jit_second_step(cap2, max2);
	unsigned long long step3 = armsx3_jit_second_step(cap3, max3);
	unsigned long long* const lead = first == 2 ? &step2 : &step3;
	unsigned long long* const next = first == 2 ? &step3 : &step2;
	int i;

	if (allow != 0)
	{
		allow = allow / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
		*lead = *lead < allow ? *lead : allow;
		*next = *next < allow - *lead ? *next : allow - *lead;
	}

	for (i = 0; i < ARMSX3_JIT_SECOND_WORDS; i++)
	{
		w[i] = 0;
	}

	w[first == 2 ? 0 : 2] = cap2;
	w[first == 2 ? 1 : 3] = cap2 + step2;
	w[first == 2 ? 2 : 0] = base3 + cap3;
	w[first == 2 ? 3 : 1] = base3 + cap3 + step3;
}

// The caps the second walk's first pages prove; unsigned throughout, so the C++ build's warnings need no cast.
static inline void armsx3_jit_second_extents(const unsigned long long* w, unsigned long long pages, unsigned long long stop,
	unsigned long long cap2, unsigned long long base3, unsigned long long cap3, unsigned long long* new2, unsigned long long* new3)
{
	unsigned long long end2 = cap2;
	unsigned long long end3 = base3 + cap3;
	int i;

	for (i = 0; i < ARMSX3_JIT_SECOND_WORDS && pages != 0; i += 2)
	{
		unsigned long long a;

		for (a = w[i]; a < w[i + 1] && pages != 0; a += ARMSX3_JIT_SPLIT_PAGE)
		{
			if (a == stop)
			{
				pages = 0;
				break;
			}

			pages--;

			if (a == end2 && a < base3)
			{
				end2 = a + ARMSX3_JIT_SPLIT_PAGE;
			}
			else if (a == end3)
			{
				end3 = a + ARMSX3_JIT_SPLIT_PAGE;
			}
		}
	}

	*new2 = end2;
	*new3 = (end3 - base3) / ARMSX3_JIT_SPLIT_ROUND * ARMSX3_JIT_SPLIT_ROUND;
}

#ifdef __cplusplus
extern "C" {
#endif

// Defined in StikJITExtensionLauncher.m and reached through dlsym; 1 with *out filled when exactly one history is stored.
int armsx3_jit_pool_history_read(struct armsx3_jit_pool_history* out, long long* budget_ms);
// Stores `*in` and synchronises. Starts no extension.
void armsx3_jit_pool_history_write(const struct armsx3_jit_pool_history* in);

#ifdef __cplusplus
}
#endif
