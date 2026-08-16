#include <gtest/gtest.h>

#include "Emu/Cell/SPUFailedBlocks.h"

#include <algorithm>
#include <cstdint>
#include <vector>

// Both lookups in spu_failed_block_set locate a candidate with upper_bound and step back exactly
// one entry, so they only ever examine a single range. That is correct if and only if no stored
// range can hide another, which is what this file pins down.
//
// What rejects what, measured rather than asserted:
//
//   EntryOnlyMarkInsideProgramMarkStaysVisible is the hole detector. Revert mark() to a plain
//   insert keyed on begin and its contains() expectations fail.
//
//   MatchesReferenceCoverage is the oracle. It compares the set against an independent bitmap over
//   a small address space, so it constrains the union, the maximality of range_of and the
//   "coverage grew" return value at once, for sequences nobody chose by hand. It is what rejects a
//   merge that is subtly wrong rather than absent.
//
//   is_disjoint() is a witness, not a test. Through this class's public API no sequence can
//   produce a non-disjoint state, so every EXPECT on it passes unconditionally and would keep
//   passing if its body were replaced by `return true`. It is retained because it localises a
//   future regression, NOT because it demonstrates anything here; MatchesReferenceCoverage is the
//   check with a demonstrated negative.
//
// Not covered by this file, and not coverable from it: spu_arm_interp_fallback,
// spu_run_interp_fallback, spu_interpreter_fallback_available, the per-session reset in the
// spu_runtime constructor, and every escape path out of the fallback interpreter. Those need a
// spu_thread and a live gateway frame. Their absence here is not evidence about them.

namespace
{
	// Independent of the implementation: a flat bitmap of "is this address covered".
	class reference_coverage
	{
	public:
		explicit reference_coverage(std::uint32_t limit)
			: m_covered(limit, false)
		{
		}

		void mark(std::uint32_t begin, std::uint32_t end)
		{
			for (std::uint32_t a = begin; a < end && a < m_covered.size(); ++a)
			{
				m_covered[a] = true;
			}
		}

		bool contains(std::uint32_t addr) const
		{
			return addr < m_covered.size() && m_covered[addr];
		}

		std::size_t count() const
		{
			std::size_t n = 0;
			for (bool b : m_covered) n += b ? 1 : 0;
			return n;
		}

		// The maximal contiguous covered interval containing addr, or {0,0}.
		std::pair<std::uint32_t, std::uint32_t> range_of(std::uint32_t addr) const
		{
			if (!contains(addr))
			{
				return {};
			}

			std::uint32_t b = addr;
			while (b > 0 && m_covered[b - 1]) --b;

			std::uint32_t e = addr + 1;
			while (e < m_covered.size() && m_covered[e]) ++e;

			return {b, e};
		}

	private:
		std::vector<bool> m_covered;
	};
}

TEST(SPUFailedBlocks, EntryOnlyMarkInsideProgramMarkStaysVisible)
{
	spu_failed_block_set set;

	// An analysed program that could not be compiled records its whole extent; a path with no
	// program to describe records the entry alone. The entry-only range always ends first, so
	// stored as-is it wins the upper_bound step-back and hides the enclosing one.
	EXPECT_TRUE(set.mark(0x5370, 0x5374));
	EXPECT_TRUE(set.mark(0x5000, 0x6000));

	EXPECT_TRUE(set.contains(0x5000));
	EXPECT_TRUE(set.contains(0x5372));
	EXPECT_TRUE(set.contains(0x5800));
	EXPECT_TRUE(set.contains(0x5fff));

	EXPECT_EQ(set.range_of(0x5800), std::make_pair(0x5000u, 0x6000u));
	EXPECT_TRUE(set.is_disjoint());
}

TEST(SPUFailedBlocks, OverlappingProgramMarksStayVisible)
{
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x7000, 0x7400));
	// A smaller program starting inside the first one. The coverage expectations come first
	// deliberately: an ASSERT here would abort the case before them, and they are the property
	// worth measuring.
	const bool grew = set.mark(0x7100, 0x7140);

	EXPECT_TRUE(set.contains(0x7120));
	EXPECT_TRUE(set.contains(0x7200));
	EXPECT_TRUE(set.contains(0x7380));
	EXPECT_TRUE(set.is_disjoint());

	// Already inside a stored range, so nothing was added and the caller must not log.
	EXPECT_FALSE(grew);
}

TEST(SPUFailedBlocks, MatchesReferenceCoverage)
{
	// Deterministic LCG rather than <random>, so the sequence is identical everywhere.
	std::uint32_t rng = 0x1234567u;
	const auto next = [&rng](std::uint32_t bound)
	{
		rng = rng * 1664525u + 1013904223u;
		return (rng >> 16) % bound;
	};

	constexpr std::uint32_t limit = 512;

	for (int trial = 0; trial < 200; ++trial)
	{
		spu_failed_block_set set;
		reference_coverage ref(limit);

		for (int op = 0; op < 24; ++op)
		{
			const std::uint32_t begin = next(limit);
			// Clamped to the bitmap. Without this the set legitimately covers past the reference's
			// last address and every comparison near the top reports a mismatch that is the
			// oracle's, not the implementation's.
			const std::uint32_t end = std::min(begin + next(40), limit);

			const std::size_t before = ref.count();
			const bool grew = set.mark(begin, end);
			ref.mark(begin, end);

			// The return value drives whether the caller logs, so it has to mean exactly
			// "the covered set grew".
			ASSERT_EQ(grew, ref.count() != before)
				<< "trial " << trial << " op " << op << " [" << begin << "," << end << ")";
		}

		ASSERT_TRUE(set.is_disjoint()) << "trial " << trial;

		for (std::uint32_t a = 0; a < limit; ++a)
		{
			ASSERT_EQ(set.contains(a), ref.contains(a)) << "trial " << trial << " addr " << a;
			ASSERT_EQ(set.range_of(a), ref.range_of(a)) << "trial " << trial << " addr " << a;
		}
	}
}

TEST(SPUFailedBlocks, EveryStoredRangeIsNonEmpty)
{
	spu_failed_block_set set;

	// An empty range is indistinguishable from "not found" at every reader.
	EXPECT_FALSE(set.mark(0x100, 0x100));
	EXPECT_FALSE(set.mark(0x200, 0x100));
	EXPECT_EQ(set.size(), 0u);
	EXPECT_FALSE(set.contains(0x100));

	EXPECT_TRUE(set.mark(0x100, 0x104));
	const auto range = set.range_of(0x100);
	EXPECT_GT(range.second, range.first);
}

TEST(SPUFailedBlocks, BoundariesAreHalfOpen)
{
	spu_failed_block_set set;
	EXPECT_TRUE(set.mark(0x1000, 0x1010));

	EXPECT_FALSE(set.contains(0x0fff));
	EXPECT_TRUE(set.contains(0x1000));
	EXPECT_TRUE(set.contains(0x100f));
	EXPECT_FALSE(set.contains(0x1010));
}

TEST(SPUFailedBlocks, TouchingRangesMergeFromTheLeft)
{
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x1000, 0x2000));
	EXPECT_TRUE(set.mark(0x2000, 0x3000));

	EXPECT_EQ(set.size(), 1u);
	EXPECT_EQ(set.range_of(0x2800), std::make_pair(0x1000u, 0x3000u));
	EXPECT_TRUE(set.is_disjoint());
}

TEST(SPUFailedBlocks, TouchingRangesMergeFromTheRight)
{
	// The mirror of the case above. Absorbing a left neighbour and absorbing following neighbours
	// are two different branches of mark(); one order exercises only one of them.
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x2000, 0x3000));
	EXPECT_TRUE(set.mark(0x1000, 0x2000));

	EXPECT_EQ(set.size(), 1u);
	EXPECT_EQ(set.range_of(0x2800), std::make_pair(0x1000u, 0x3000u));
	EXPECT_TRUE(set.is_disjoint());
}

TEST(SPUFailedBlocks, AlreadyCoveredMarkReportsNoGrowth)
{
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x1000, 0x2000));

	// The caller logs on a true return. Re-marking a covered block is the normal case -- a title
	// ping-ponging between two addresses inside one bad block -- and must stay silent.
	EXPECT_FALSE(set.mark(0x1400, 0x1500));
	EXPECT_FALSE(set.mark(0x1000, 0x2000));
	EXPECT_EQ(set.size(), 1u);
}

TEST(SPUFailedBlocks, WideMarkAbsorbsSeveralRanges)
{
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x1000, 0x1100));
	EXPECT_TRUE(set.mark(0x1200, 0x1300));
	EXPECT_TRUE(set.mark(0x1400, 0x1500));
	EXPECT_EQ(set.size(), 3u);

	EXPECT_TRUE(set.mark(0x1050, 0x1450));

	EXPECT_EQ(set.size(), 1u);
	EXPECT_EQ(set.range_of(0x1350), std::make_pair(0x1000u, 0x1500u));
	EXPECT_TRUE(set.contains(0x1180));
	EXPECT_TRUE(set.is_disjoint());
}

TEST(SPUFailedBlocks, ClearEmptiesTheSet)
{
	spu_failed_block_set set;

	EXPECT_TRUE(set.mark(0x7000, 0x8000));
	EXPECT_TRUE(set.contains(0x7500));

	set.clear();

	EXPECT_EQ(set.size(), 0u);
	EXPECT_FALSE(set.contains(0x7500));
	EXPECT_EQ(set.range_of(0x7500), std::make_pair(0u, 0u));
}

TEST(SPUFailedBlocks, LocalStoreExtremesAreRepresentable)
{
	spu_failed_block_set set;

	// Local store is 0x40000 bytes; the last instruction slot is 0x3fffc and an entry-only mark
	// records [pc, pc + 4).
	EXPECT_TRUE(set.mark(0x3fffc, 0x40000));

	EXPECT_TRUE(set.contains(0x3fffc));
	EXPECT_FALSE(set.contains(0x40000));
	EXPECT_EQ(set.range_of(0x3fffc), std::make_pair(0x3fffcu, 0x40000u));

	EXPECT_TRUE(set.mark(0, 4));
	EXPECT_TRUE(set.contains(0));
	EXPECT_TRUE(set.is_disjoint());
}
