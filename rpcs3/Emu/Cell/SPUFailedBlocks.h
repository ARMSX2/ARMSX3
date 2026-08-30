#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <map>
#include <utility>

// Disjoint interval set over SPU local-store addresses, holding the ranges whose programs could
// not be compiled by the recompiler backend.
//
// Disjointness is the load-bearing property, not an optimization. Both lookups locate a candidate
// with upper_bound and step back exactly one entry, so they only ever examine a single range. That
// is correct if and only if no range can hide another. It is not free: the two marking call sites
// record different extents -- one records a whole analysed program, the other records only an entry
// point when there is no program to describe -- so an entry-only mark landing inside a program-sized
// mark is ordinary, and the entry-only one always ends first. With the ranges stored as inserted,
// the shorter one wins the upper_bound step-back and the enclosing one becomes invisible for every
// address past its end.
//
// mark() therefore merges on insert instead, so the invariant holds by construction and the cheap
// lookup stays cheap. Header-only and free of engine dependencies so it can be tested directly
// rather than through a model of it.
class spu_failed_block_set
{
public:
	// Record [begin, end). Returns true if this call actually grew the covered set, which is the
	// condition worth logging: re-marking an already-covered block is the normal case and logging
	// it floods (a title ping-ponging between two addresses produced thousands of lines a second).
	bool mark(std::uint32_t begin, std::uint32_t end)
	{
		if (end <= begin)
		{
			// An empty range would be indistinguishable from "not found" at every reader.
			return false;
		}

		if (auto covering = range_of(begin); covering.second >= end && covering.first <= begin)
		{
			return false;
		}

		// Absorb the left neighbour when it overlaps or merely touches, then every following range
		// the widened interval reaches. Touching ranges are merged as well: they describe the same
		// fact about adjacent code and keeping them apart only grows the map.
		auto it = m_map.upper_bound(begin);

		if (it != m_map.begin())
		{
			auto prev = std::prev(it);

			if (prev->second >= begin)
			{
				begin = prev->first;
				end = std::max(end, prev->second);
				it = m_map.erase(prev);
			}
		}

		while (it != m_map.end() && it->first <= end)
		{
			end = std::max(end, it->second);
			it = m_map.erase(it);
		}

		m_map.emplace(begin, end);
		return true;
	}

	// The range containing addr, or {0, 0}. A stored range always has end > begin, so {0, 0} is
	// unambiguous.
	std::pair<std::uint32_t, std::uint32_t> range_of(std::uint32_t addr) const
	{
		auto it = m_map.upper_bound(addr);

		if (it == m_map.begin())
		{
			return {};
		}

		--it;

		if (addr >= it->first && addr < it->second)
		{
			return {it->first, it->second};
		}

		return {};
	}

	bool contains(std::uint32_t addr) const
	{
		const auto range = range_of(addr);
		return range.second > range.first;
	}

	void clear()
	{
		m_map.clear();
	}

	std::size_t size() const
	{
		return m_map.size();
	}

	// Test-only: verify the invariant every lookup depends on.
	bool is_disjoint() const
	{
		std::uint32_t last_end = 0;
		bool first = true;

		for (const auto& [begin, end] : m_map)
		{
			if (end <= begin)
			{
				return false;
			}

			if (!first && begin <= last_end)
			{
				return false;
			}

			last_end = end;
			first = false;
		}

		return true;
	}

private:
	std::map<std::uint32_t, std::uint32_t> m_map;
};
