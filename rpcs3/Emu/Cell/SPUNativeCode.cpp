// ARMSX3: native versions of specific SPU programs (see SPUNativeCode.h).
// Build with -ffp-contract=off: each float step must round where the program's does.
#include "SPUNativeCode.h"

#include <algorithm>
#include <cstring>

#if defined(__aarch64__) || defined(_M_ARM64)
#include <arm_neon.h>
#define ARMSX3_SPU_NATIVE 1
#endif

namespace spu_native_code
{
#ifdef ARMSX3_SPU_NATIVE
	namespace
	{
		using u8 = std::uint8_t;
		using u32 = std::uint32_t;
		using u64 = std::uint64_t;
		using s16 = std::int16_t;
		using s32 = std::int32_t;

		constexpr u32 c_ls_mask = 0x3fff0;

		// ------------------------------------------------------------------------------------------------
		// Registers and local store. Vectors hold SPU word i in lane i.

		// spu_thread::gpr keeps each quadword byte-reversed: host lane j holds SPU word 3 - j
		inline uint32x4_t reg(const u8* gpr, u32 n)
		{
			const uint32x4_t h = vrev64q_u32(vld1q_u32(reinterpret_cast<const u32*>(gpr + n * 16)));
			return vextq_u32(h, h, 2);
		}

		inline u32 reg_w0(const u8* gpr, u32 n)
		{
			u32 v;
			std::memcpy(&v, gpr + n * 16 + 12, 4);
			return v;
		}

		// Local store is big-endian; one byte reversal per word gives SPU word order in host lanes
		inline uint32x4_t ls_load(const u8* ls, u32 addr)
		{
			return vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(ls + (addr & c_ls_mask))));
		}

		inline void ls_store(u8* ls, u32 addr, uint32x4_t v)
		{
			vst1q_u8(ls + (addr & c_ls_mask), vrev32q_u8(vreinterpretq_u8_u32(v)));
		}

		// ------------------------------------------------------------------------------------------------
		// SPU single precision as the LLVM recompiler compiles it under Approximate xfloat. Round toward
		// zero and flush-to-zero come from the SPU thread's FPCR (SPUThread.cpp), so these give the
		// recompiler's bits only on an SPU thread.

		inline float32x4_t F(uint32x4_t v) { return vreinterpretq_f32_u32(v); }
		inline uint32x4_t U(float32x4_t v) { return vreinterpretq_u32_f32(v); }

		inline uint32x4_t clamp_smax(uint32x4_t v)
		{
			v = vminq_u32(v, vdupq_n_u32(0xff7fffffu));
			return vreinterpretq_u32_s32(vminq_s32(vreinterpretq_s32_u32(v), vdupq_n_s32(0x7f7fffff)));
		}

		// Float compare with zero as the hardware does it, which under flush-to-zero counts a denormal as
		// zero. Kept in assembly: clang turns the compare intrinsic into a test of the bits without the
		// sign, which counts a denormal as non-zero, even when told denormals are flushed.
		inline uint32x4_t fcmeq_zero(uint32x4_t v)
		{
			uint32x4_t r;
			__asm__("fcmeq %0.4s, %1.4s, #0.0" : "=w"(r) : "w"(v));
			return r;
		}

		// All ones unless the value is +-0 (or a denormal, flushed to zero)
		inline uint32x4_t ne0(uint32x4_t v)
		{
			return vmvnq_u32(fcmeq_zero(v));
		}

		inline uint32x4_t spu_fa(uint32x4_t a, uint32x4_t b)
		{
			return U(vaddq_f32(F(a), F(b)));
		}

		inline uint32x4_t spu_fs(uint32x4_t a, uint32x4_t b)
		{
			return U(vsubq_f32(F(a), F(clamp_smax(b))));
		}

		inline uint32x4_t spu_fm(uint32x4_t a, uint32x4_t b)
		{
			return vandq_u32(vandq_u32(U(vmulq_f32(F(a), F(b))), ne0(a)), ne0(b));
		}

		// a * b of a value with itself: no zero guard (the recompiler's FM of one register)
		inline uint32x4_t spu_fm_same(uint32x4_t a)
		{
			return U(vmulq_f32(F(a), F(a)));
		}

		inline uint32x4_t spu_fma(uint32x4_t a, uint32x4_t b, uint32x4_t c)
		{
			return vbslq_u32(vandq_u32(ne0(a), ne0(b)), U(vfmaq_f32(F(c), F(a), F(b))), c);
		}

		// c - a * b
		inline uint32x4_t spu_fnms(uint32x4_t a, uint32x4_t b, uint32x4_t c)
		{
			return U(vfmaq_f32(F(c), vnegq_f32(F(clamp_smax(a))), F(clamp_smax(b))));
		}

		// a > b, comparing the bit patterns the way the recompiler does
		inline uint32x4_t spu_fcgt(uint32x4_t a, uint32x4_t b)
		{
			const int32x4_t ai = vreinterpretq_s32_u32(a);
			const int32x4_t bi = vreinterpretq_s32_u32(b);
			const uint32x4_t unordered_ne = vmvnq_u32(vceqq_f32(F(a), F(b)));
			const uint32x4_t both_negative = vcltzq_s32(vandq_s32(ai, bi));
			return vandq_u32(unordered_ne, vbslq_u32(both_negative, vcltq_s32(ai, bi), vcgtq_s32(ai, bi)));
		}

		// mask ? t : f
		inline uint32x4_t sel(uint32x4_t mask, uint32x4_t t, uint32x4_t f)
		{
			return vbslq_u32(mask, t, f);
		}

		// Exact shortcuts for selects on a compare with a positive constant k (1.0, 5.0). With k's bits
		// positive, spu_fcgt(k, x) is the signed compare of the bit patterns for every x, NaN and -0
		// included (both sides of the recompiler's formula reduce to it), so
		//   sel(spu_fcgt(k, x), x, k) == min_s32(k, x)  and  sel(spu_fcgt(k, x), k, x) == max_s32(k, x).
		inline uint32x4_t min_bits(uint32x4_t k, uint32x4_t x)
		{
			return vreinterpretq_u32_s32(vminq_s32(vreinterpretq_s32_u32(k), vreinterpretq_s32_u32(x)));
		}

		inline uint32x4_t max_bits(uint32x4_t k, uint32x4_t x)
		{
			return vreinterpretq_u32_s32(vmaxq_s32(vreinterpretq_s32_u32(k), vreinterpretq_s32_u32(x)));
		}

		// sel(spu_fcgt(0, x), 0, x): spu_fcgt(0, x) is "x has its sign bit set and is not +-0 (or a
		// flushed denormal)", -0 and NaNs following the recompiler's formula exactly
		inline uint32x4_t clamp_below_zero(uint32x4_t x)
		{
			const uint32x4_t below = vbicq_u32(vreinterpretq_u32_s32(vcltzq_s32(vreinterpretq_s32_u32(x))), fcmeq_zero(x));
			return vbicq_u32(x, below);
		}

		// Clamp to [0, 1] the way the programs select: 1 > x ? (0 > x ? 0 : x) : 1
		inline uint32x4_t clamp01(uint32x4_t x, uint32x4_t one)
		{
			return clamp_below_zero(min_bits(one, x));
		}

		// Magnitude bits, for a running maximum: any lane at 0x7f800000 or above held an Inf or a NaN.
		// Under round-toward-zero, arithmetic on finite values never reaches Inf (overflow gives the
		// largest finite value), so a program whose float inputs are all finite never sees a NaN either;
		// a NaN's payload and sign would otherwise depend on which operand the recompiler's code takes
		// first, which only the translated code reproduces.
		inline uint32x4_t magnitude(uint32x4_t v)
		{
			return vandq_u32(v, vdupq_n_u32(0x7fffffffu));
		}

		inline bool all_finite(uint32x4_t max_magnitude)
		{
			return vmaxvq_u32(max_magnitude) < 0x7f800000u;
		}

		// All four words equal and a normal float (not zero, a denormal, an Inf or a NaN)
		inline bool splat_normal(uint32x4_t v)
		{
			const uint32x4_t same = vceqq_u32(v, vdupq_laneq_u32(v, 0));
			const uint32x4_t ex = vandq_u32(v, vdupq_n_u32(0x7f800000u));
			const uint32x4_t normal = vbicq_u32(vmvnq_u32(vceqzq_u32(ex)), vceqq_u32(ex, vdupq_n_u32(0x7f800000u)));
			return vminvq_u32(vandq_u32(same, normal)) == 0xffffffffu;
		}

		// The recompiler's FM and FMA for finite operands only (no NaN can meet another, so operand
		// order is free), times a constant that is a normal non-zero float: its zero guard always
		// passes, so only the variable's remains. The constant is lane I % 4 of k[I / 4].
		template <u32 I>
		inline uint32x4_t fm_k(uint32x4_t a, const float32x4_t* k)
		{
			return vbicq_u32(U(vmulq_laneq_f32(F(a), k[I / 4], I % 4)), fcmeq_zero(a));
		}

		// c + a * k
		template <u32 I>
		inline uint32x4_t fma_k(uint32x4_t a, uint32x4_t c, const float32x4_t* k)
		{
			return vbslq_u32(fcmeq_zero(a), c, U(vfmaq_laneq_f32(F(c), F(a), k[I / 4], I % 4)));
		}

		// Finite operands, both variable
		inline uint32x4_t fm_v(uint32x4_t a, uint32x4_t b)
		{
			return vbicq_u32(U(vmulq_f32(F(a), F(b))), vorrq_u32(fcmeq_zero(a), fcmeq_zero(b)));
		}

		inline uint32x4_t fma_v(uint32x4_t a, uint32x4_t b, uint32x4_t c)
		{
			return vbslq_u32(vorrq_u32(fcmeq_zero(a), fcmeq_zero(b)), c, U(vfmaq_f32(F(c), F(a), F(b))));
		}

		// c - a * b, fused: FNMS, whose clamps change nothing for finite a and b
		inline uint32x4_t fnms_v(uint32x4_t a, uint32x4_t b, uint32x4_t c)
		{
			return U(vfmsq_f32(F(c), F(a), F(b)));
		}

		const u32 c_frest_lut[32] = {
			0x7FFBE0, 0x7F87A6, 0x70EF72, 0x708B40, 0x638B12, 0x633AEA, 0x5792C4, 0x574AA0,
			0x4CCA7E, 0x4C9262, 0x430A44, 0x42D62A, 0x3A2E12, 0x39FDFA, 0x3215E4, 0x31F1D2,
			0x2AA9BE, 0x2A85AC, 0x23D59A, 0x23BD8E, 0x1D8576, 0x1D8576, 0x17AD5A, 0x17AD5A,
			0x124543, 0x124543, 0x0D392D, 0x0D392D, 0x08851A, 0x08851A, 0x041D07, 0x041D07};

		// FREST then FI: the SPU's reciprocal estimate, from the same table and integer steps. Four plain
		// loads look the table up (a 128-byte register table would hold 8 vector registers in a loop).
		inline uint32x4_t frest_lookup(uint32x4_t a)
		{
			const uint32x4_t entry = vandq_u32(vshrq_n_u32(a, 18), vdupq_n_u32(0x1f));
			uint32x4_t lut = vdupq_n_u32(c_frest_lut[vgetq_lane_u32(entry, 0)]);
			lut = vsetq_lane_u32(c_frest_lut[vgetq_lane_u32(entry, 1)], lut, 1);
			lut = vsetq_lane_u32(c_frest_lut[vgetq_lane_u32(entry, 2)], lut, 2);
			lut = vsetq_lane_u32(c_frest_lut[vgetq_lane_u32(entry, 3)], lut, 3);
			return lut;
		}

		__attribute__((always_inline)) inline uint32x4_t frest_fi(uint32x4_t a)
		{
			const uint32x4_t ex = vandq_u32(a, vdupq_n_u32(0x7f800000u));
			const uint32x4_t rex = vshlq_n_u32(vqsubq_u32(vdupq_n_u32(0x7e80), vshrq_n_u32(ex, 16)), 16);
			const uint32x4_t fix = vbslq_u32(vceqzq_u32(ex), vdupq_n_u32(0x7f800000u), rex);
			const uint32x4_t b = vorrq_u32(vorrq_u32(frest_lookup(a), fix), vandq_u32(a, vdupq_n_u32(0x80000000u)));

			const uint32x4_t base = vshlq_n_u32(vandq_u32(b, vdupq_n_u32(0x007ffc00u)), 9);
			const uint32x4_t ymul = vmulq_u32(vandq_u32(b, vdupq_n_u32(0x3ff)), vandq_u32(a, vdupq_n_u32(0x7ffff)));
			const uint32x4_t adjust = vcgtq_u32(ymul, base);
			const int32x4_t shift = vbslq_s32(adjust, vdupq_n_s32(-8), vdupq_n_s32(-9));
			const uint32x4_t bnew = vshlq_u32(vsubq_u32(base, ymul), shift);
			const uint32x4_t exb = clamp_smax(vsubq_u32(vandq_u32(b, vdupq_n_u32(0xff800000u)), vandq_u32(adjust, vdupq_n_u32(1u << 23))));
			return vorrq_u32(exb, vandq_u32(bnew, vdupq_n_u32(0x007fffffu)));
		}

		// The recompiler's spu_re_acc: a reciprocal by division, with the PS3's "Inf => NaN, NaN => 0"
		inline uint32x4_t reciprocal_accurate(uint32x4_t d, uint32x4_t one)
		{
			const uint32x4_t r = U(vdivq_f32(F(one), F(d)));
			const uint32x4_t mag = vandq_u32(r, vdupq_n_u32(0x7fffffffu));
			const uint32x4_t is_inf = vceqq_u32(mag, vdupq_n_u32(0x7f800000u));
			const uint32x4_t not_nan = vcleq_u32(mag, vdupq_n_u32(0x7f800000u));
			return vorrq_u32(vandq_u32(r, not_nan), vandq_u32(is_inf, vdupq_n_u32(0x0fffffffu)));
		}

		// FI: the SPU's interpolation step from an estimate b of a's reciprocal or reciprocal square root
		inline uint32x4_t spu_fi(uint32x4_t a, uint32x4_t b)
		{
			const uint32x4_t base = vshlq_n_u32(vandq_u32(b, vdupq_n_u32(0x007ffc00u)), 9);
			const uint32x4_t ymul = vmulq_u32(vandq_u32(b, vdupq_n_u32(0x3ff)), vandq_u32(a, vdupq_n_u32(0x7ffff)));
			const uint32x4_t adjust = vcgtq_u32(ymul, base);
			const int32x4_t shift = vbslq_s32(adjust, vdupq_n_s32(-8), vdupq_n_s32(-9));
			const uint32x4_t bnew = vshlq_u32(vsubq_u32(base, ymul), shift);
			const uint32x4_t exb = clamp_smax(vsubq_u32(vandq_u32(b, vdupq_n_u32(0xff800000u)), vandq_u32(adjust, vdupq_n_u32(1u << 23))));
			return vorrq_u32(exb, vandq_u32(bnew, vdupq_n_u32(0x007fffffu)));
		}

		const u32 c_frsqest_lut[64] = {
			0x350160, 0x34E954, 0x2F993D, 0x2F993D, 0x2AA523, 0x2AA523, 0x26190D, 0x26190D, 0x21E4F9, 0x21E4F9, 0x1E00E9, 0x1E00E9, 0x1A5CD9, 0x1A5CD9, 0x16F8CB, 0x16F8CB,
			0x13CCC0, 0x13CCC0, 0x10CCB3, 0x10CCB3, 0x0E00AA, 0x0E00AA, 0x0B58A1, 0x0B58A1, 0x08D498, 0x08D498, 0x067491, 0x067491, 0x043089, 0x043089, 0x020C83, 0x020C83,
			0x7FFDF4, 0x7FD1DE, 0x7859C8, 0x783DBA, 0x71559C, 0x71559C, 0x6AE57C, 0x6AE57C, 0x64F561, 0x64F561, 0x5F7149, 0x5F7149, 0x5A4D33, 0x5A4D33, 0x55811F, 0x55811F,
			0x51050F, 0x51050F, 0x4CC8FE, 0x4CC8FE, 0x48D0F0, 0x48D0F0, 0x4510E4, 0x4510E4, 0x4180D7, 0x4180D7, 0x3E24CC, 0x3E24CC, 0x3AF4C3, 0x3AF4C3, 0x37E8BA, 0x37E8BA};

		// FRSQEST: the SPU's reciprocal square root estimate (of the magnitude), from its table: the
		// fraction's top 5 bits and the exponent's lowest bit pick the entry. Four plain loads: a
		// 256-byte table lookup would hold 16 vector registers through a loop.
		inline uint32x4_t frsqest(uint32x4_t a)
		{
			const uint32x4_t entry = vandq_u32(vshrq_n_u32(a, 18), vdupq_n_u32(0x3f));
			uint32x4_t lut = vdupq_n_u32(c_frsqest_lut[vgetq_lane_u32(entry, 0)]);
			lut = vsetq_lane_u32(c_frsqest_lut[vgetq_lane_u32(entry, 1)], lut, 1);
			lut = vsetq_lane_u32(c_frsqest_lut[vgetq_lane_u32(entry, 2)], lut, 2);
			lut = vsetq_lane_u32(c_frsqest_lut[vgetq_lane_u32(entry, 3)], lut, 3);

			const uint32x4_t ex = vandq_u32(a, vdupq_n_u32(0x7f800000u));
			const uint32x4_t half_ex = vshrq_n_u32(vaddq_u32(ex, vandq_u32(ex, vdupq_n_u32(1u << 23))), 1);
			const uint32x4_t fix = vbslq_u32(vceqzq_u32(ex), vdupq_n_u32(0x7f800000u), vsubq_u32(vdupq_n_u32(190u << 23), half_ex));
			return vorrq_u32(lut, fix);
		}

		// CFLTU with its scale already as a factor: truncate toward zero, negatives to 0, 2^32 and up
		// (and positive NaNs) to all ones
		inline uint32x4_t spu_cfltu(uint32x4_t x, float scale)
		{
			const float32x4_t a = vmulq_f32(F(x), vdupq_n_f32(scale));
			const int32x4_t bits = vreinterpretq_s32_f32(a);
			const uint32x4_t big = vcgtq_s32(bits, vdupq_n_s32(((32 + 127) << 23) - 1));
			const uint32x4_t value = vbicq_u32(vcvtq_u32_f32(a), vreinterpretq_u32_s32(vshrq_n_s32(bits, 31)));
			return vbslq_u32(big, vdupq_n_u32(0xffffffffu), value);
		}

		// CUFLT with its scale as a factor (the conversion rounds as the FPCR says)
		inline uint32x4_t spu_cuflt(uint32x4_t x, float scale)
		{
			return U(vmulq_f32(vcvtq_f32_u32(x), vdupq_n_f32(scale)));
		}

		// ------------------------------------------------------------------------------------------------
		// The Last of Us (BCUS98174 and its other releases share the job): a depth-aware screen-space
		// shading filter, 4 pixels per step over a padded 2D grid. Each step reads the pixel's depth and 8
		// neighbours, turns depth differences into two clamped weight sets, averages them, normalises,
		// fades with depth and writes the square. The recompiler computes 1/depth with a division (its
		// accurate-reciprocal rewrite) but the normalising reciprocal with the SPU's estimate-and-refine
		// steps, so this does the same.

		// shufb masks at local store 0x44c0: entry m picks word i from the second operand when bit 3 - i
		// of m is set, i.e. a per-word select on the gathered compare bits
		bool is_select_table(const u8* ls)
		{
			for (u32 m = 0; m < 16; m++)
			{
				for (u32 i = 0; i < 4; i++)
				{
					const u32 from = ((m >> (3 - i)) & 1) ? 16 : 0;

					for (u32 j = 0; j < 4; j++)
					{
						if (ls[0x44c0 + m * 16 + i * 4 + j] != from + i * 4 + j)
						{
							return false;
						}
					}
				}
			}

			return true;
		}

		bool ranges_overlap(u32 a0, u32 a1, u32 b0, u32 b1)
		{
			return a0 < b1 && b0 < a1;
		}

		u32 tlou_depth_filter(u8* gpr, u8* ls)
		{
			// What this version relies on; anything else runs the translated code
			static constexpr u8 c_queue_shift[16] = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};

			if (std::memcmp(ls + 0x45c0, c_queue_shift, 16) != 0 || !is_select_table(ls))
			{
				return c_declined;
			}

			const u32 sp = reg_w0(gpr, 1) - 496;
			const u32 a3 = reg_w0(gpr, 3), a4 = reg_w0(gpr, 4), a5 = reg_w0(gpr, 5);
			const u32 a6 = reg_w0(gpr, 6), a7 = reg_w0(gpr, 7), a8 = reg_w0(gpr, 8);
			const u32 in_base = reg_w0(gpr, 10);
			const u32 out_base = reg_w0(gpr, 11);
			const u32 offsets = reg_w0(gpr, 12);

			const u32 skip = (a8 + a6) << 2;
			const u32 pos_wrap = 0x30 - skip;
			const u32 idx_wrap = 0x110 - skip;
			const u32 rows = a3 + a4 + a5 - 1;
			const u32 row_end0 = ((a6 + a7 + a8) << 2) - 16;
			const u32 end = static_cast<u32>(s32{static_cast<s16>(0x120)} * static_cast<s16>(rows)) + row_end0;

			// The translated loop stores its first result after three steps of warm-up; an end at 0 would
			// keep a warm-up value, which only the translated code reproduces
			if (end == 0)
			{
				return c_declined;
			}

			u32 tap[8];

			for (u32 k = 0; k < 8; k++)
			{
				tap[k] = in_base + vgetq_lane_u32(ls_load(ls, offsets + k * 16), 0);
			}

			// Walk the grid once to find the last step and the address ranges touched
			u32 pos = 0, idx = 0, row_end = row_end0, steps = 0;

			while (pos != end)
			{
				const bool wrap = pos == row_end;
				row_end += wrap ? 0x120 : 0;
				idx += wrap ? idx_wrap : 0x10;
				pos += wrap ? pos_wrap : 0x10;

				if (++steps > 0x8000)
				{
					return c_declined;
				}
			}

			// Outputs and inputs must not overlap (the translated loop reads ahead of its stores), and
			// nothing may wrap around the end of local store
			const u32 last_idx = idx;
			u32 in_lo = in_base, in_hi = in_base + last_idx + 16;

			for (u32 k = 0; k < 8; k++)
			{
				in_lo = std::min(in_lo, tap[k]);
				in_hi = std::max(in_hi, tap[k] + last_idx + 16);
			}

			const u32 out_lo = out_base, out_hi = out_base + end + 16;

			if (in_hi > 0x40000 || out_hi > 0x40000 || in_lo >= 0x40000 || out_lo >= 0x40000 ||
				ranges_overlap(out_lo, out_hi, in_lo, in_hi) || ranges_overlap(out_lo, out_hi, 0x44c0, 0x4ad0) ||
				ranges_overlap(out_lo, out_hi, offsets & c_ls_mask, (offsets & c_ls_mask) + 0x80))
			{
				return c_declined;
			}

			// Parameters
			const uint32x4_t k13 = reg(gpr, 13), k14 = reg(gpr, 14), k15 = reg(gpr, 15), k16 = reg(gpr, 16);
			const uint32x4_t k17 = reg(gpr, 17), k18 = reg(gpr, 18), k19 = reg(gpr, 19), k20 = reg(gpr, 20);

			// Float inputs must be finite (see magnitude()): the parameters and every depth in reach. A
			// depth of 0 still makes a NaN (its accurate reciprocal), but only that one per pixel, so
			// operand order cannot matter.
			{
				uint32x4_t mag = vmaxq_u32(vmaxq_u32(vmaxq_u32(magnitude(k13), magnitude(k14)), vmaxq_u32(magnitude(k15), magnitude(k16))),
					vmaxq_u32(vmaxq_u32(magnitude(k17), magnitude(k18)), vmaxq_u32(magnitude(k19), magnitude(k20))));

				// A word's top half tells: in host order and without the sign it is 0x7f80 or more exactly
				// when the exponent is all ones. Two loads give the top halves of 8 words.
				uint16x8_t top = vdupq_n_u16(0);
				u32 a = in_lo & c_ls_mask;

				for (; a + 32 <= in_hi; a += 32)
				{
					const uint16x8_t halves = vld2q_u16(reinterpret_cast<const std::uint16_t*>(ls + a)).val[0];
					top = vmaxq_u16(top, vandq_u16(vreinterpretq_u16_u8(vrev16q_u8(vreinterpretq_u8_u16(halves))), vdupq_n_u16(0x7fff)));
				}

				for (; a < in_hi; a += 16)
				{
					mag = vmaxq_u32(mag, magnitude(ls_load(ls, a)));
				}

				if (!all_finite(mag) || vmaxvq_u16(top) >= 0x7f80)
				{
					return c_declined;
				}
			}
			const uint32x4_t half = vdupq_n_u32(0x3f000000u);
			const uint32x4_t one = vdupq_n_u32(0x3f800000u);
			const uint32x4_t two = vdupq_n_u32(0x40000000u);
			const uint32x4_t five = vdupq_n_u32(0x40a00000u);

			// The two quadwords the program saves on its stack
			{
				const uint32x4_t r80 = reg(gpr, 80), r81 = reg(gpr, 81);
				ls_store(ls, sp + 32, r80);
				ls_store(ls, sp + 48, r81);
			}

			// One 4-pixel step: the pixel quadword at `at` (an offset into the depth grid)
			const auto shade = [&](u32 at) __attribute__((always_inline)) -> uint32x4_t
			{
					const uint32x4_t c = ls_load(ls, in_base + at);
					const uint32x4_t inv = reciprocal_accurate(c, one);

					// Fade with depth: clamp((c - k19) * k20, 0, 1)
					const uint32x4_t fade_raw = spu_fm(spu_fs(c, k19), k20);
					const uint32x4_t fade = clamp_below_zero(min_bits(one, fade_raw));

					// Neighbours
					uint32x4_t t[8];

					for (u32 k = 0; k < 8; k++)
					{
						t[k] = ls_load(ls, tap[k] + at);
					}

					// First weights: 1 + ((n - c) + c * k13) * (k14 / c), as pairwise minima clamped to [0, 1]
					const uint32x4_t ck13 = spu_fm(c, k13);
					const uint32x4_t gain = spu_fm(inv, k14);
					uint32x4_t y[8];

					for (u32 k = 0; k < 8; k++)
					{
						y[k] = spu_fma(spu_fa(spu_fs(t[k], c), ck13), gain, one);
					}

					const auto pair = [&](uint32x4_t first, uint32x4_t second) __attribute__((always_inline))
					{
						const uint32x4_t m1 = min_bits(one, first);
						const uint32x4_t m2 = sel(spu_fcgt(m1, second), second, m1);
						return clamp_below_zero(m2);
					};

					const uint32x4_t p01 = pair(y[0], y[1]);
					const uint32x4_t p23 = pair(y[2], y[3]);
					const uint32x4_t p45 = pair(y[4], y[5]);
					const uint32x4_t p67 = pair(y[6], y[7]);

					// Second weights: clamp((n - (c - c * k15 * k17)) * (k16 / c * k18 * 0.5), 0, 1)
					const uint32x4_t base2 = spu_fs(c, spu_fm(spu_fm(c, k15), k17));
					const uint32x4_t scale2 = spu_fm(spu_fm(spu_fm(inv, k16), k18), half);
					uint32x4_t q[8];

					for (u32 k = 0; k < 8; k++)
					{
						const uint32x4_t z = spu_fm(spu_fs(t[k], base2), scale2);
						q[k] = clamp_below_zero(min_bits(one, z));
					}

					// Combine, normalise by max(5, 2 * sum) and fade
					const uint32x4_t acc = spu_fma(spu_fa(q[5], q[4]), p45, spu_fma(spu_fa(q[2], q[3]), p23, spu_fm(spu_fa(q[1], q[0]), p01)));
					const uint32x4_t sum = spu_fa(spu_fa(p45, spu_fa(p01, p23)), p67);
					const uint32x4_t num = spu_fnms(two, sum, spu_fma(spu_fa(q[6], q[7]), p67, acc));
					const uint32x4_t twice = spu_fm(sum, two);
					const uint32x4_t den = max_bits(five, twice);
					const uint32x4_t est = frest_fi(den);
					const uint32x4_t rcp = spu_fma(est, spu_fnms(est, den, one), est);
					const uint32x4_t lit_raw = spu_fm(spu_fma(num, rcp, one), two);
					const uint32x4_t lit = min_bits(one, lit_raw);
					const uint32x4_t v = spu_fma(spu_fs(one, fade), lit, fade);

				return spu_fm_same(v);
			};

			// The same step when the eight parameters are each equal in all lanes and normal non-zero
			// floats (so in every capture so far): their zero guards always pass, so they ride in lanes and
			// multiply by lane. Every value is finite but the reciprocal of a depth of 0 and what it
			// scales, one NaN per pixel that never meets another, so operand order stays free; and every
			// FS and FNMS subtracts a finite value, so their clamps change nothing.
			constexpr u32 c13 = 0, c14 = 1, c15 = 2, c16 = 3, c17 = 4, c18 = 5, c20 = 6, c_half = 7, c_two = 8;
			const bool fast = splat_normal(k13) && splat_normal(k14) && splat_normal(k15) && splat_normal(k16) &&
				splat_normal(k17) && splat_normal(k18) && splat_normal(k19) && splat_normal(k20);
			float32x4_t kp[3];
			{
				const u32 bits[12] = {vgetq_lane_u32(k13, 0), vgetq_lane_u32(k14, 0), vgetq_lane_u32(k15, 0), vgetq_lane_u32(k16, 0),
					vgetq_lane_u32(k17, 0), vgetq_lane_u32(k18, 0), vgetq_lane_u32(k20, 0), 0x3f000000u, 0x40000000u, 0, 0, 0};

				for (u32 j = 0; j < 3; j++)
				{
					kp[j] = vreinterpretq_f32_u32(vld1q_u32(bits + j * 4));
				}
			}

			const auto sub = [](uint32x4_t a, uint32x4_t b) __attribute__((always_inline)) { return U(vsubq_f32(F(a), F(b))); };
			const auto add = [](uint32x4_t a, uint32x4_t b) __attribute__((always_inline)) { return U(vaddq_f32(F(a), F(b))); };

			const auto shade_fast = [&](u32 at) __attribute__((always_inline)) -> uint32x4_t
			{
				const uint32x4_t c = ls_load(ls, in_base + at);
				const uint32x4_t inv = reciprocal_accurate(c, one);
				const uint32x4_t fade = clamp01(fm_k<c20>(sub(c, k19), kp), one);
				uint32x4_t t[8];

				for (u32 k = 0; k < 8; k++)
				{
					t[k] = ls_load(ls, tap[k] + at);
				}

				const uint32x4_t ck13 = fm_k<c13>(c, kp);
				const uint32x4_t gain = fm_k<c14>(inv, kp);
				uint32x4_t y[8];

				for (u32 k = 0; k < 8; k++)
				{
					y[k] = fma_v(add(sub(t[k], c), ck13), gain, one);
				}

				const auto pair = [&](uint32x4_t first, uint32x4_t second) __attribute__((always_inline))
				{
					const uint32x4_t m1 = min_bits(one, first);
					const uint32x4_t m2 = sel(spu_fcgt(m1, second), second, m1);
					return clamp_below_zero(m2);
				};

				const uint32x4_t p01 = pair(y[0], y[1]);
				const uint32x4_t p23 = pair(y[2], y[3]);
				const uint32x4_t p45 = pair(y[4], y[5]);
				const uint32x4_t p67 = pair(y[6], y[7]);

				const uint32x4_t base2 = sub(c, fm_k<c17>(fm_k<c15>(c, kp), kp));
				const uint32x4_t scale2 = fm_k<c_half>(fm_k<c18>(fm_k<c16>(inv, kp), kp), kp);
				uint32x4_t q[8];

				for (u32 k = 0; k < 8; k++)
				{
					q[k] = clamp01(fm_v(sub(t[k], base2), scale2), one);
				}

				const uint32x4_t acc = fma_v(add(q[5], q[4]), p45, fma_v(add(q[2], q[3]), p23, fm_v(add(q[1], q[0]), p01)));
				const uint32x4_t sum = add(add(p45, add(p01, p23)), p67);
				const uint32x4_t num = fnms_v(two, sum, fma_v(add(q[6], q[7]), p67, acc));
				const uint32x4_t den = max_bits(five, fm_k<c_two>(sum, kp));
				const uint32x4_t est = frest_fi(den);
				const uint32x4_t rcp = fma_v(est, fnms_v(est, den, one), est);
				const uint32x4_t lit = min_bits(one, fm_k<c_two>(fma_v(num, rcp, one), kp));
				const uint32x4_t v = fma_v(sub(one, fade), lit, fade);

				return spu_fm_same(v);
			};

			pos = 0;
			idx = 0;
			row_end = row_end0;

			const auto advance = [&]() __attribute__((always_inline))
			{
				const bool wrap = pos == row_end;
				row_end += wrap ? 0x120 : 0;
				idx += wrap ? idx_wrap : 0x10;
				pos += wrap ? pos_wrap : 0x10;
			};

			// Two independent steps per pass, so the core can overlap their long dependency chains (the
			// outputs never overlap the inputs, checked above)
			const auto run = [&](const auto& step) __attribute__((always_inline))
			{
				while (true)
				{
					const u32 idx0 = idx, pos0 = pos;

					if (pos0 == end)
					{
						ls_store(ls, out_base + pos0, step(idx0));
						break;
					}

					advance();
					const u32 idx1 = idx, pos1 = pos;
					const uint32x4_t v0 = step(idx0);
					const uint32x4_t v1 = step(idx1);
					ls_store(ls, out_base + pos0, v0);
					ls_store(ls, out_base + pos1, v1);

					if (pos1 == end)
					{
						break;
					}

					advance();
				}
			};

			if (fast)
			{
				run(shade_fast);
			}
			else
			{
				run(shade);
			}

			// bi $0
			return reg_w0(gpr, 0) & 0x3fffc;
		}

		// ------------------------------------------------------------------------------------------------
		// Quadwords in SPU byte order, for setting up a call: the exact semantics of the few integer and
		// shuffle instructions a program's setup uses, so tables built from them match bit for bit.

		struct qw
		{
			u8 b[16];
		};

		inline u32 qw_word(const qw& q, u32 i)
		{
			return u32{q.b[i * 4]} << 24 | u32{q.b[i * 4 + 1]} << 16 | u32{q.b[i * 4 + 2]} << 8 | q.b[i * 4 + 3];
		}

		inline void qw_set(qw& q, u32 i, u32 v)
		{
			q.b[i * 4] = static_cast<u8>(v >> 24);
			q.b[i * 4 + 1] = static_cast<u8>(v >> 16);
			q.b[i * 4 + 2] = static_cast<u8>(v >> 8);
			q.b[i * 4 + 3] = static_cast<u8>(v);
		}

		qw qw_reg(const u8* gpr, u32 n)
		{
			qw q;

			for (u32 j = 0; j < 16; j++)
			{
				q.b[j] = gpr[n * 16 + 15 - j];
			}

			return q;
		}

		qw qw_ls(const u8* ls, u32 addr)
		{
			qw q;
			std::memcpy(q.b, ls + (addr & c_ls_mask), 16);
			return q;
		}

		qw qw_splat(u32 v)
		{
			qw q;

			for (u32 i = 0; i < 4; i++)
			{
				qw_set(q, i, v);
			}

			return q;
		}

		template <typename F>
		qw qw_map(const qw& a, const qw& b, F f)
		{
			qw r;

			for (u32 i = 0; i < 4; i++)
			{
				qw_set(r, i, f(qw_word(a, i), qw_word(b, i)));
			}

			return r;
		}

		qw qw_add(const qw& a, const qw& b) { return qw_map(a, b, [](u32 x, u32 y) { return x + y; }); }
		qw qw_sub(const qw& a, const qw& b) { return qw_map(a, b, [](u32 x, u32 y) { return x - y; }); }
		qw qw_and(const qw& a, u32 imm) { return qw_map(a, a, [imm](u32 x, u32) { return x & imm; }); }
		qw qw_shr(const qw& a, u32 n) { return qw_map(a, a, [n](u32 x, u32) { return n < 32 ? x >> n : 0u; }); }

		// SHL: each word shifted left by the low 6 bits of b's word
		qw qw_shl(const qw& a, const qw& b)
		{
			return qw_map(a, b, [](u32 x, u32 y) { return (y & 0x3f) < 32 ? x << (y & 0x3f) : 0u; });
		}

		qw qw_rotl(const qw& a, u32 n)
		{
			return qw_map(a, a, [n](u32 x, u32) { return (n & 31) ? (x << (n & 31) | x >> (32 - (n & 31))) : x; });
		}

		qw qw_shufb(const qw& a, const qw& b, const qw& c)
		{
			qw r;

			for (u32 j = 0; j < 16; j++)
			{
				const u8 m = c.b[j];

				if ((m & 0xc0) == 0x80) r.b[j] = 0x00;
				else if ((m & 0xe0) == 0xc0) r.b[j] = 0xff;
				else if ((m & 0xe0) == 0xe0) r.b[j] = 0x80;
				else r.b[j] = (m & 0x1f) < 16 ? a.b[m & 0x1f] : b.b[(m & 0x1f) - 16];
			}

			return r;
		}

		inline uint32x4_t qw_vec(const qw& q)
		{
			return vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(q.b)));
		}

		// A shufb with a fixed control over a 32-byte window, as one table lookup: normal bytes index the
		// window, special bytes (0x80-0xff) become their constant through an OR
		struct gather
		{
			uint8x16_t index;
			uint8x16_t fill;

			explicit gather(const qw& control)
			{
				u8 idx[16], val[16];

				for (u32 j = 0; j < 16; j++)
				{
					const u8 m = control.b[j];
					const bool special = (m & 0x80) != 0;
					idx[j] = special ? 0xff : (m & 0x1f);
					val[j] = !special ? 0x00 : (m & 0xc0) == 0x80 ? 0x00 : (m & 0xe0) == 0xc0 ? 0xff : 0x80;
				}

				index = vld1q_u8(idx);
				fill = vld1q_u8(val);
			}

			// The window's bytes in SPU order; the result's words in host lanes
			uint32x4_t operator()(const uint8x16x2_t& window) const
			{
				return vreinterpretq_u32_u8(vrev32q_u8(vorrq_u8(vqtbl2q_u8(window, index), fill)));
			}
		};

		// Each word rotated left by its own amount (low 5 bits)
		inline uint32x4_t rotl_each(uint32x4_t x, uint32x4_t amount)
		{
			const int32x4_t s = vreinterpretq_s32_u32(vandq_u32(amount, vdupq_n_u32(31)));
			return vorrq_u32(vshlq_u32(x, s), vshlq_u32(x, vsubq_s32(s, vdupq_n_s32(32))));
		}

		// ------------------------------------------------------------------------------------------------
		// The Last of Us: a bit-field decoder (vertex or animation data). Each pass reads two 32-byte windows
		// of a packed stream (at r3 and halfway through the pass's bytes), cuts 8 groups of 4 fields out of
		// them at fixed bit positions and widths, puts each field under a fixed exponent (r2) and bias
		// (r10), scales and offsets it with a multiply-add (r11, r12) and writes 8 quadwords. The positions,
		// widths and masks come from registers the caller set; they are fixed for the call, so the
		// shuffles they drive become table lookups built once.
		u32 tlou_bit_unpack(u8* gpr, u8* ls)
		{
			static constexpr u8 c_sh1[16] = {0, 1, 2, 3, 4, 5, 6, 7, 0x10, 0x11, 0x12, 0x13, 0x80, 0x80, 0x80, 0x80};
			static constexpr u8 c_sh2[16] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0x10, 0x11, 0x12, 0x13};
			static constexpr u8 c_sh3[16] = {3, 3, 3, 3, 7, 7, 7, 7, 11, 11, 11, 11, 15, 15, 15, 15};
			static constexpr u8 c_identity[16] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
			static constexpr u8 c_queue[16] = {4, 5, 6, 7, 0x10, 0x11, 0x12, 0x13, 0x10, 0x11, 0x12, 0x13, 0x10, 0x11, 0x12, 0x13};

			const qw sh1 = qw_ls(ls, 0x13080), sh2 = qw_ls(ls, 0x13040), sh3 = qw_ls(ls, 0x133d0);
			const qw r14 = qw_reg(gpr, 14), r16 = qw_reg(gpr, 16);

			// The window alignment (r16, with the program's 0x03030303) and the store queue (r14) are what
			// this version computes directly; the three tables only need to be the ones checked here
			if (std::memcmp(sh1.b, c_sh1, 16) || std::memcmp(sh2.b, c_sh2, 16) || std::memcmp(sh3.b, c_sh3, 16) ||
				std::memcmp(r16.b, c_identity, 16) || std::memcmp(r14.b, c_queue, 16))
			{
				return c_declined;
			}

			// The scale and offset must be finite (see magnitude()): a field that decodes to a NaN then
			// meets no other NaN, so the result does not depend on operand order
			if (!all_finite(vmaxq_u32(magnitude(reg(gpr, 11)), magnitude(reg(gpr, 12)))))
			{
				return c_declined;
			}

			const qw R[37] = {
				qw_reg(gpr, 0), qw_reg(gpr, 1), qw_reg(gpr, 2), qw_reg(gpr, 3), qw_reg(gpr, 4), qw_reg(gpr, 5), qw_reg(gpr, 6),
				qw_reg(gpr, 7), qw_reg(gpr, 8), qw_reg(gpr, 9), qw_reg(gpr, 10), qw_reg(gpr, 11), qw_reg(gpr, 12), qw_reg(gpr, 13),
				r14, qw_reg(gpr, 15), r16, qw_reg(gpr, 17), qw_reg(gpr, 18), qw_reg(gpr, 19), qw_reg(gpr, 20), qw_reg(gpr, 21),
				qw_reg(gpr, 22), qw_reg(gpr, 23), qw_reg(gpr, 24), qw_reg(gpr, 25), qw_reg(gpr, 26), qw_reg(gpr, 27), qw_reg(gpr, 28),
				qw_reg(gpr, 29), qw_reg(gpr, 30), qw_reg(gpr, 31), qw_reg(gpr, 32), qw_reg(gpr, 33), qw_reg(gpr, 34), qw_reg(gpr, 35),
				qw_reg(gpr, 36)};

			// Passes: the output offset runs from -r29 up to 0 in steps of 128
			const u32 span = qw_word(R[29], 0);

			if (span == 0 || span % 0x80 || span > 0x40000)
			{
				return c_declined;
			}

			const u32 passes = span / 0x80 + 1;
			const u32 stream = qw_word(R[3], 0);
			const u32 step = qw_word(R[13], 0);
			const u32 half = step >> 1;
			const u32 out = qw_word(R[4], 0);

			// Everything read and written stays inside local store and apart
			const u64 in_lo = stream, in_hi = u64{stream} + u64{step} * (passes - 1) + half + 48;
			const u64 out_lo = u64{out} - span, out_hi = u64{out} + 0x80;

			if (step > 0x1000 || in_hi > 0x40000 || out > 0x40000 || span > out || out_hi > 0x40000 ||
				(out_lo < in_hi && in_lo < out_hi) || (out_lo < 0x13400 && 0x13040 < out_hi) || (out_lo < 0x57b0 && 0x54c8 < out_hi))
			{
				return c_declined;
			}

			// Field widths, the mask that keeps the exponent bits, and the bit positions of each group
			const qw widths = qw_shufb(qw_shufb(qw_shufb(R[6], R[7], R[19]), R[8], sh1), R[9], sh2);
			const qw mask = qw_rotl(qw_shl(R[17], widths), 2);
			const auto group = [&](const qw& a, const qw& b, const qw& c, const qw& d)
			{
				return qw_shufb(qw_shufb(qw_shufb(a, b, R[19]), c, sh1), d, sh2);
			};

			const qw base = qw_and(qw_add(R[36], R[9]), 7);
			const qw t4 = qw_add(qw_add(qw_add(R[6], base), R[7]), R[8]);
			const qw u = qw_add(t4, R[9]);
			const qw t5 = qw_add(qw_add(qw_add(qw_add(qw_add(t4, R[9]), R[6]), R[7]), R[8]), R[9]);
			const qw v = qw_add(qw_add(qw_add(t5, R[6]), R[7]), R[8]);
			const qw t6 = qw_add(qw_add(R[9], v), R[6]);

			// Output order 0x00..0x70: the first four from the window at r3, the last four from the one
			// half a pass further on
			const qw pos[8] = {
				group(R[22], R[20], R[18], R[21]),
				group(R[24], R[25], R[27], R[26]),
				group(R[28], R[30], R[31], R[32]),
				group(R[33], R[34], R[35], R[36]),
				group(base, qw_add(R[6], base), qw_add(qw_add(R[6], base), R[7]), t4),
				group(u, qw_add(u, R[6]), qw_add(qw_add(u, R[6]), R[7]), qw_add(qw_add(qw_add(u, R[6]), R[7]), R[8])),
				group(t5, qw_add(t5, R[6]), qw_add(qw_add(t5, R[6]), R[7]), qw_add(qw_add(qw_add(t5, R[6]), R[7]), R[8])),
				group(qw_add(R[9], v), t6, qw_add(R[7], t6), qw_add(R[8], qw_add(R[7], t6))),
			};

			gather cut[8] = {gather(qw{}), gather(qw{}), gather(qw{}), gather(qw{}), gather(qw{}), gather(qw{}), gather(qw{}), gather(qw{})};
			uint32x4_t turn[8];

			for (u32 o = 0; o < 8; o++)
			{
				const qw bytes = qw_shr(pos[o], 3);
				cut[o] = gather(qw_add(qw_splat(0x10203), qw_shufb(bytes, bytes, sh3)));
				turn[o] = qw_vec(qw_sub(qw_splat(0), qw_sub(qw_sub(qw_splat(0x1e), widths), qw_and(pos[o], 7))));
			}

			const uint32x4_t keep = qw_vec(mask);
			const uint32x4_t exponent = reg(gpr, 2);
			const uint32x4_t bias = reg(gpr, 10);
			const uint32x4_t scale = reg(gpr, 11);
			const uint32x4_t offset = reg(gpr, 12);

			for (u32 j = 0; j < passes; j++)
			{
				const u32 at = stream + j * step;
				const uint8x16x2_t win_b = {vld1q_u8(ls + at), vld1q_u8(ls + at + 16)};
				const uint8x16x2_t win_a = {vld1q_u8(ls + at + half), vld1q_u8(ls + at + half + 16)};
				const u32 dst = out - span + j * 0x80;

				for (u32 o = 0; o < 8; o++)
				{
					const uint32x4_t field = rotl_each(cut[o](o < 4 ? win_b : win_a), turn[o]);
					const uint32x4_t value = vaddq_u32(sel(keep, exponent, field), bias);
					ls_store(ls, dst + o * 16, spu_fma(value, scale, offset));
				}
			}

			// bi $0
			return reg_w0(gpr, 0) & 0x3fffc;
		}

		// ------------------------------------------------------------------------------------------------
		// The Last of Us: per-vertex lighting, 4 vertices per quadword, r6 quadwords from index r4. For each
		// vertex: its position relative to a light (three streams) turned into the light's space, a falloff
		// from the squared distance and an attenuation from the inverse distance; its normal (three more
		// streams) turned the same way, normalised and dotted with the light direction; a byte from a 2D
		// table indexed by distance and that dot product. Two quadratic curves of the results scale the two
		// output arrays in place.
		//
		// The loop is software-pipelined: its first four steps store into a spare slot (r5, which the caller
		// points one past the arrays), multiplying it by the curves of whatever the registers held, i.e. the
		// previous call's last steps. That is reproduced from the registers too, so local store matches.
		u32 tlou_vertex_light(u8* gpr, u8* ls)
		{
			// The program's constants: 0, 1, 0.5, 1.5, 127/128, 511/512 and two byte-gather masks
			static constexpr u8 c_consts[0x80] = {
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0x3f, 0x80, 0, 0, 0x3f, 0x80, 0, 0, 0x3f, 0x80, 0, 0, 0x3f, 0x80, 0, 0,
				0x3f, 0, 0, 0, 0x3f, 0, 0, 0, 0x3f, 0, 0, 0, 0x3f, 0, 0, 0,
				0x3f, 0xc0, 0, 0, 0x3f, 0xc0, 0, 0, 0x3f, 0xc0, 0, 0, 0x3f, 0xc0, 0, 0,
				0x3f, 0x7e, 0, 0, 0x3f, 0x7e, 0, 0, 0x3f, 0x7e, 0, 0, 0x3f, 0x7e, 0, 0,
				0x3f, 0x7f, 0x80, 0, 0x3f, 0x7f, 0x80, 0, 0x3f, 0x7f, 0x80, 0, 0x3f, 0x7f, 0x80, 0,
				0x80, 0x80, 0x80, 0x00, 0x80, 0x80, 0x80, 0x10, 0x80, 0x80, 0x80, 0x00, 0x80, 0x80, 0x80, 0x10,
				0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f};

			if (std::memcmp(ls + 0x4040, c_consts, sizeof(c_consts)) != 0)
			{
				return c_declined;
			}

			// Results are computed into these before anything is stored, so a declined call changes nothing
			constexpr u32 c_max_steps = 256;
			const u32 n = reg_w0(gpr, 6);

			if (n > c_max_steps)
			{
				return c_declined;
			}

			const u32 params = reg_w0(gpr, 3);
			const u32 first = reg_w0(gpr, 4);
			const u32 spare = reg_w0(gpr, 5);
			const auto param = [&](u32 offset) { return ls_load(ls, params + offset); };
			const auto param_w0 = [&](u32 offset) { return vgetq_lane_u32(param(offset), 0); };

			// Every stream, both outputs and the parameter block as non-wrapping line ranges
			const u32 len = n * 16;
			u32 stream[6];

			for (u32 k = 0; k < 6; k++)
			{
				stream[k] = (param_w0(0x1a0 + k * 16) + first) & c_ls_mask;
			}

			const u32 out0 = param_w0(0x240), out1 = param_w0(0x250);
			const u32 o0 = (out0 + first) & c_ls_mask, o1 = (out1 + first) & c_ls_mask;
			const u32 d0 = (out0 + spare) & c_ls_mask, d1 = (out1 + spare) & c_ls_mask;
			const u32 par = params & c_ls_mask;
			const u32 table = param_w0(0x2c0);

			if (o0 + len > 0x40000 || o1 + len > 0x40000 || par + 0x2d0 > 0x40000)
			{
				return c_declined;
			}

			const auto stored = [&](u32 line_lo, u32 line_hi)
			{
				return ranges_overlap(line_lo, line_hi, o0, o0 + len) || ranges_overlap(line_lo, line_hi, o1, o1 + len) ||
					ranges_overlap(line_lo, line_hi, d0, d0 + 16) || ranges_overlap(line_lo, line_hi, d1, d1 + 16);
			};

			// The stores never meet each other or anything the loop reads (its order would then matter)
			if (ranges_overlap(o0, o0 + len, o1, o1 + len) || d0 == d1 || ranges_overlap(d0, d0 + 16, o0, o0 + len) ||
				ranges_overlap(d0, d0 + 16, o1, o1 + len) || ranges_overlap(d1, d1 + 16, o0, o0 + len) ||
				ranges_overlap(d1, d1 + 16, o1, o1 + len) || stored(par, par + 0x2d0) || stored(0x4040, 0x4480))
			{
				return c_declined;
			}

			for (u32 k = 0; k < 6; k++)
			{
				if (stream[k] + len > 0x40000 || stored(stream[k], stream[k] + len))
				{
					return c_declined;
				}
			}

			const uint32x4_t zero = vdupq_n_u32(0);
			const uint32x4_t one = vdupq_n_u32(0x3f800000u);
			const uint32x4_t half = vdupq_n_u32(0x3f000000u);
			const uint32x4_t three_halves = vdupq_n_u32(0x3fc00000u);
			const uint32x4_t row_scale = vdupq_n_u32(0x3f7e0000u);
			const uint32x4_t col_scale = vdupq_n_u32(0x3f7f8000u);

			const uint32x4_t p000 = param(0x000), p010 = param(0x010), p020 = param(0x020);
			const uint32x4_t p030 = param(0x030), p040 = param(0x040), p050 = param(0x050);
			const uint32x4_t p060 = param(0x060), p070 = param(0x070), p080 = param(0x080);
			const uint32x4_t p090 = param(0x090), p0a0 = param(0x0a0), p0b0 = param(0x0b0);
			const uint32x4_t p0c0 = param(0x0c0), p0d0 = param(0x0d0), p0e0 = param(0x0e0);
			const uint32x4_t p0f0 = param(0x0f0), p100 = param(0x100), p110 = param(0x110);
			const uint32x4_t p120 = param(0x120), p130 = param(0x130), p140 = param(0x140);
			const uint32x4_t p150 = param(0x150), p160 = param(0x160), p170 = param(0x170);
			const uint32x4_t p180 = param(0x180), p190 = param(0x190);
			const uint32x4_t p260 = param(0x260), p270 = param(0x270), p280 = param(0x280);
			const uint32x4_t p290 = param(0x290), p2a0 = param(0x2a0), p2b0 = param(0x2b0);

			// Float inputs must be finite (see magnitude()): the parameters, the in-flight registers that
			// feed the spare slot (r10, r11 and the output arrays are only ever multiplied by finite
			// values, where a NaN comes out the same either way) and, per step, the six streams
			uint32x4_t mag = vdupq_n_u32(0);

			for (const uint32x4_t p : {p000, p010, p020, p030, p040, p050, p060, p070, p080, p090, p0a0, p0b0, p0c0, p0d0, p0e0, p0f0,
					 p100, p110, p120, p130, p140, p150, p160, p170, p180, p190, p260, p270, p280, p290, p2a0, p2b0})
			{
				mag = vmaxq_u32(mag, magnitude(p));
			}

			for (const u32 r : {2u, 7u, 14u, 15u, 16u, 17u, 18u, 19u, 20u, 21u, 22u, 23u})
			{
				mag = vmaxq_u32(mag, magnitude(reg(gpr, r)));
			}

			mag = vmaxq_u32(mag, magnitude(sel(reg(gpr, 9), zero, reg(gpr, 12))));
			mag = vmaxq_u32(mag, magnitude(sel(reg(gpr, 9), zero, reg(gpr, 13))));

			// y + 1.5 y (1 - y y x): one refinement of an estimate y of 1 / sqrt(x)
			const auto refine = [&](uint32x4_t x, uint32x4_t y) __attribute__((always_inline))
			{
				return spu_fma(spu_fm(y, three_halves), spu_fnms(y, spu_fm(y, x), one), y);
			};

			// -(a0 b0 + a1 b1 + a2 b2) r, as three multiply-subtracts from zero
			const auto dot3 = [&](uint32x4_t a0, uint32x4_t b0, uint32x4_t a1, uint32x4_t b1, uint32x4_t a2, uint32x4_t b2, uint32x4_t r) __attribute__((always_inline))
			{
				return spu_fnms(a2, spu_fm(b2, r), spu_fnms(a1, spu_fm(b1, r), spu_fnms(a0, spu_fm(b0, r), zero)));
			};

			// The table byte of each vertex: column from the dot product, row from the distance
			const auto table_index = [&](uint32x4_t inv_dist, uint32x4_t dot) __attribute__((always_inline))
			{
				const uint32x4_t col = spu_cfltu(spu_fm(col_scale, spu_fma(spu_fm(inv_dist, dot), half, half)), 512.0f);
				const uint32x4_t row = spu_cfltu(spu_fm(row_scale, spu_fm(p160, inv_dist)), 128.0f);
				return vaddq_u32(col, vshlq_n_u32(row, 9));
			};

			// Indexes of the table reads made after the first stores whose bytes are used (a vertex closer
			// than the cutoff drops its byte), to check them against the stores
			uint32x4_t index_min = vdupq_n_u32(0xffffffffu), index_max = vdupq_n_u32(0);

			// The quadword at table + (index & ~15), byte index & 15, for each lane; `track` marks the
			// lanes to check
			const auto table_bytes = [&](uint32x4_t index, uint32x4_t track) __attribute__((always_inline))
			{
				index_min = vminq_u32(index_min, vornq_u32(index, track));
				index_max = vmaxq_u32(index_max, vandq_u32(index, track));

				u32 idx[4], bytes[4];
				vst1q_u32(idx, index);

				for (u32 k = 0; k < 4; k++)
				{
					bytes[k] = ls[((table + (idx[k] & ~15u)) & c_ls_mask) + (idx[k] & 15)];
				}

				return vld1q_u32(bytes);
			};

			// The two curves, from the inverse distance and the falloff (and the table byte)
			const auto curve0 = [&](uint32x4_t s) __attribute__((always_inline))
			{
				return spu_fma(spu_fma(p260, s, p270), s, p280);
			};

			const auto curve1 = [&](uint32x4_t s) __attribute__((always_inline))
			{
				return spu_fma(spu_fma(p290, s, p2a0), s, p2b0);
			};

			const auto gain0 = [&](uint32x4_t inv_dist, uint32x4_t falloff) __attribute__((always_inline))
			{
				const uint32x4_t c = clamp01(spu_fm(p160, spu_fm(spu_fm(inv_dist, inv_dist), p160)), one);
				return curve0(sel(spu_fcgt(inv_dist, p170), zero, spu_fma(falloff, c, spu_fs(one, c))));
			};

			const auto gain1 = [&](uint32x4_t inv_dist, uint32x4_t falloff, uint32x4_t byte) __attribute__((always_inline))
			{
				const uint32x4_t x = spu_fs(one, spu_cuflt(vaddq_u32(byte, vdupq_n_u32(1)), 1.0f / 256));
				return curve1(sel(spu_fcgt(inv_dist, p170), zero, spu_fma(falloff, x, spu_fs(one, x))));
			};

			// The four steps in flight when the program starts, from the registers they live in:
			// the oldest has both products ready (r11, r10), the next both curve inputs under a mask (r12,
			// r13, r9), the next its inverse distance, falloff and table index (r14, r22, r8), the newest
			// its estimate step (r2, r7), normal terms (r15 to r20), inverse distance and falloff (r21, r23)
			const uint32x4_t g0_a = curve0(sel(reg(gpr, 9), zero, reg(gpr, 12)));
			const uint32x4_t g1_a = curve1(sel(reg(gpr, 9), zero, reg(gpr, 13)));
			const uint32x4_t g0_b = gain0(reg(gpr, 14), reg(gpr, 22));
			const uint32x4_t g1_b = gain1(reg(gpr, 14), reg(gpr, 22), table_bytes(reg(gpr, 8), zero));
			const uint32x4_t r_c = refine(reg(gpr, 2), reg(gpr, 7));
			const uint32x4_t dot_c = dot3(reg(gpr, 18), reg(gpr, 16), reg(gpr, 19), reg(gpr, 15), reg(gpr, 20), reg(gpr, 17), r_c);
			const uint32x4_t g0_c = gain0(reg(gpr, 21), reg(gpr, 23));
			const uint32x4_t g1_c = gain1(reg(gpr, 21), reg(gpr, 23), table_bytes(table_index(reg(gpr, 21), dot_c), vmvnq_u32(spu_fcgt(reg(gpr, 21), p170))));
			const uint32x4_t spare0 = spu_fm(spu_fm(spu_fm(reg(gpr, 11), g0_a), g0_b), g0_c);
			const uint32x4_t spare1 = spu_fm(spu_fm(spu_fm(reg(gpr, 10), g1_a), g1_b), g1_c);

			// One step: 4 vertices at offset `at` into the streams
			const auto light = [&](u32 at, uint32x4_t& g0, uint32x4_t& g1) __attribute__((always_inline))
			{
				const uint32x4_t s0 = ls_load(ls, stream[0] + at);
				const uint32x4_t s1 = ls_load(ls, stream[1] + at);
				const uint32x4_t s2 = ls_load(ls, stream[2] + at);
				const uint32x4_t n0 = ls_load(ls, stream[3] + at);
				const uint32x4_t n1 = ls_load(ls, stream[4] + at);
				const uint32x4_t n2 = ls_load(ls, stream[5] + at);
				mag = vmaxq_u32(mag, vmaxq_u32(vmaxq_u32(magnitude(s0), magnitude(s1)), vmaxq_u32(magnitude(s2), magnitude(n0))));
				mag = vmaxq_u32(mag, vmaxq_u32(magnitude(n1), magnitude(n2)));

				const uint32x4_t px = spu_fs(s0, p000);
				const uint32x4_t py = spu_fs(s1, p010);
				const uint32x4_t pz = spu_fs(s2, p020);
				const uint32x4_t lx = spu_fma(pz, p050, spu_fma(p040, py, spu_fm(p030, px)));
				const uint32x4_t ly = spu_fma(pz, p080, spu_fma(py, p070, spu_fm(px, p060)));
				const uint32x4_t lz = spu_fma(pz, p0b0, spu_fma(p0a0, py, spu_fm(p090, px)));
				const uint32x4_t sq2 = spu_fma(ly, ly, spu_fm(lx, lx));
				const uint32x4_t falloff = clamp01(spu_fm(p190, spu_fs(spu_fma(lz, lz, sq2), p180)), one);
				const uint32x4_t lzs = spu_fm(p150, lz);
				const uint32x4_t dist2 = spu_fma(lzs, lzs, sq2);
				const uint32x4_t inv_dist = refine(dist2, spu_fi(dist2, frsqest(dist2)));

				const uint32x4_t nz = spu_fma(n2, p140, spu_fma(p130, n1, spu_fm(p120, n0)));
				const uint32x4_t ny = spu_fma(n2, p110, spu_fma(p100, n1, spu_fm(p0f0, n0)));
				const uint32x4_t nx = spu_fma(p0e0, n2, spu_fma(n1, p0d0, spu_fm(p0c0, n0)));
				const uint32x4_t nzs = spu_fm(p150, nz);
				const uint32x4_t nlen2 = spu_fma(nzs, nzs, spu_fma(ny, ny, spu_fm(nx, nx)));
				const uint32x4_t inv_nlen = refine(nlen2, spu_fi(nlen2, frsqest(nlen2)));
				const uint32x4_t dot = dot3(lx, nx, ly, ny, lzs, nzs, inv_nlen);

				g0 = gain0(inv_dist, falloff);
				g1 = gain1(inv_dist, falloff, table_bytes(table_index(inv_dist, dot), vmvnq_u32(spu_fcgt(inv_dist, p170))));
			};

			// The same step when every parameter that multiplies is equal in all lanes and a normal,
			// non-zero float (so in every capture so far): their zero guards always pass, so they ride
			// in lanes of a few registers and multiply by lane. A non-finite stream value would turn the
			// squared lengths non-finite (every row has non-zero factors), so those two are what is
			// checked; with finite values, FS and FNMS need no clamps and operand order is free.
			constexpr u32 k030 = 0, k040 = 1, k050 = 2, k060 = 3, k070 = 4, k080 = 5, k090 = 6, k0a0 = 7;
			constexpr u32 k0b0 = 8, k0c0 = 9, k0d0 = 10, k0e0 = 11, k0f0 = 12, k100 = 13, k110 = 14, k120 = 15;
			constexpr u32 k130 = 16, k140 = 17, k150 = 18, k160 = 19, k190 = 20, k260 = 21, k290 = 22;
			constexpr u32 k_half = 23, k_three_halves = 24, k_row = 25, k_col = 26;

			float32x4_t kp[7];
			bool fast = true;
			{
				const uint32x4_t factors[23] = {p030, p040, p050, p060, p070, p080, p090, p0a0, p0b0, p0c0, p0d0, p0e0,
					p0f0, p100, p110, p120, p130, p140, p150, p160, p190, p260, p290};
				u32 bits[28] = {};

				for (u32 i = 0; i < 23; i++)
				{
					fast = fast && splat_normal(factors[i]);
					bits[i] = vgetq_lane_u32(factors[i], 0);
				}

				bits[k_half] = 0x3f000000u;
				bits[k_three_halves] = 0x3fc00000u;
				bits[k_row] = 0x3f7e0000u;
				bits[k_col] = 0x3f7f8000u;

				for (u32 j = 0; j < 7; j++)
				{
					kp[j] = vreinterpretq_f32_u32(vld1q_u32(bits + j * 4));
				}
			}

			const auto light_fast = [&](u32 at, uint32x4_t& g0, uint32x4_t& g1) __attribute__((always_inline))
			{
				const auto refine_fast = [&](uint32x4_t x, uint32x4_t y) __attribute__((always_inline))
				{
					return fma_v(fm_k<k_three_halves>(y, kp), fnms_v(y, fm_v(y, x), one), y);
				};

				const uint32x4_t px = U(vsubq_f32(F(ls_load(ls, stream[0] + at)), F(p000)));
				const uint32x4_t py = U(vsubq_f32(F(ls_load(ls, stream[1] + at)), F(p010)));
				const uint32x4_t pz = U(vsubq_f32(F(ls_load(ls, stream[2] + at)), F(p020)));
				const uint32x4_t lx = fma_k<k050>(pz, fma_k<k040>(py, fm_k<k030>(px, kp), kp), kp);
				const uint32x4_t ly = fma_k<k080>(pz, fma_k<k070>(py, fm_k<k060>(px, kp), kp), kp);
				const uint32x4_t lz = fma_k<k0b0>(pz, fma_k<k0a0>(py, fm_k<k090>(px, kp), kp), kp);
				const uint32x4_t sq2 = fma_v(ly, ly, fm_v(lx, lx));
				const uint32x4_t sq3 = fma_v(lz, lz, sq2);
				const uint32x4_t falloff = clamp01(fm_k<k190>(U(vsubq_f32(F(sq3), F(p180))), kp), one);
				const uint32x4_t lzs = fm_k<k150>(lz, kp);
				const uint32x4_t dist2 = fma_v(lzs, lzs, sq2);
				const uint32x4_t inv_dist = refine_fast(dist2, spu_fi(dist2, frsqest(dist2)));

				const uint32x4_t n0 = ls_load(ls, stream[3] + at);
				const uint32x4_t n1 = ls_load(ls, stream[4] + at);
				const uint32x4_t n2 = ls_load(ls, stream[5] + at);
				const uint32x4_t nz = fma_k<k140>(n2, fma_k<k130>(n1, fm_k<k120>(n0, kp), kp), kp);
				const uint32x4_t ny = fma_k<k110>(n2, fma_k<k100>(n1, fm_k<k0f0>(n0, kp), kp), kp);
				const uint32x4_t nx = fma_k<k0e0>(n2, fma_k<k0d0>(n1, fm_k<k0c0>(n0, kp), kp), kp);
				const uint32x4_t nzs = fm_k<k150>(nz, kp);
				const uint32x4_t nlen2 = fma_v(nzs, nzs, fma_v(ny, ny, fm_v(nx, nx)));
				const uint32x4_t inv_nlen = refine_fast(nlen2, spu_fi(nlen2, frsqest(nlen2)));
				const uint32x4_t dot = fnms_v(lzs, fm_v(nzs, inv_nlen), fnms_v(ly, fm_v(ny, inv_nlen), fnms_v(lx, fm_v(nx, inv_nlen), zero)));
				mag = vmaxq_u32(mag, vmaxq_u32(magnitude(sq3), magnitude(nlen2)));

				const uint32x4_t beyond = spu_fcgt(inv_dist, p170);
				const uint32x4_t c = clamp01(fm_k<k160>(fm_k<k160>(fm_v(inv_dist, inv_dist), kp), kp), one);
				const uint32x4_t s0 = sel(beyond, zero, fma_v(falloff, c, U(vsubq_f32(F(one), F(c)))));
				g0 = fma_v(fma_k<k260>(s0, p270, kp), s0, p280);

				const uint32x4_t col = spu_cfltu(fm_k<k_col>(fma_k<k_half>(fm_v(inv_dist, dot), half, kp), kp), 512.0f);
				const uint32x4_t row = spu_cfltu(fm_k<k_row>(fm_k<k160>(inv_dist, kp), kp), 128.0f);
				const uint32x4_t byte = table_bytes(vaddq_u32(col, vshlq_n_u32(row, 9)), vmvnq_u32(beyond));
				const uint32x4_t x = U(vsubq_f32(F(one), F(spu_cuflt(vaddq_u32(byte, vdupq_n_u32(1)), 1.0f / 256))));
				const uint32x4_t s1 = sel(beyond, zero, fma_v(falloff, x, U(vsubq_f32(F(one), F(x)))));
				g1 = fma_v(fma_k<k290>(s1, p2a0, kp), s1, p2b0);
			};

			uint32x4_t res0[c_max_steps], res1[c_max_steps];
			u32 e = 0;

			if (fast)
			{
				for (; e < n; e++)
				{
					uint32x4_t g0, g1;
					light_fast(e * 16, g0, g1);
					res0[e] = spu_fm(ls_load(ls, o0 + e * 16), g0);
					res1[e] = spu_fm(ls_load(ls, o1 + e * 16), g1);
				}
			}
			else
			{
				for (; e + 2 <= n; e += 2)
				{
					uint32x4_t g0a, g1a, g0b, g1b;
					light(e * 16, g0a, g1a);
					light(e * 16 + 16, g0b, g1b);
					res0[e] = spu_fm(ls_load(ls, o0 + e * 16), g0a);
					res1[e] = spu_fm(ls_load(ls, o1 + e * 16), g1a);
					res0[e + 1] = spu_fm(ls_load(ls, o0 + e * 16 + 16), g0b);
					res1[e + 1] = spu_fm(ls_load(ls, o1 + e * 16 + 16), g1b);
				}

				if (e < n)
				{
					uint32x4_t g0, g1;
					light(e * 16, g0, g1);
					res0[e] = spu_fm(ls_load(ls, o0 + e * 16), g0);
					res1[e] = spu_fm(ls_load(ls, o1 + e * 16), g1);
				}
			}

			// The table reads made after the first stores must stay clear of every stored line (their
			// order would matter otherwise)
			const u32 first_index = vminvq_u32(index_min), last_index = vmaxvq_u32(index_max);
			const u64 read_lo = (u64{table} + (first_index & ~15u)) & ~u64{15};
			const u64 read_hi = ((u64{table} + (last_index & ~15u)) & ~u64{15}) + 16;
			const bool reads_clear = first_index > last_index || (read_hi <= 0x40000 && !stored(static_cast<u32>(read_lo), static_cast<u32>(read_hi)));

			if (!reads_clear || !all_finite(mag))
			{
				return c_declined;
			}

			ls_store(ls, d0, spare0);
			ls_store(ls, d1, spare1);

			for (e = 0; e < n; e++)
			{
				ls_store(ls, o0 + e * 16, res0[e]);
				ls_store(ls, o1 + e * 16, res1[e]);
			}

			// bi $0
			return reg_w0(gpr, 0) & 0x3fffc;
		}

		// A shufb with a fixed control as one table lookup over the 32 bytes of its two operands, all in
		// SPU byte order: normal control bytes index the operands, special ones (0x80-0xff) become their
		// constant through an OR
		struct byte_shuffle
		{
			uint8x16_t index;
			uint8x16_t fill;

			byte_shuffle() = default;

			explicit byte_shuffle(const u8* control)
			{
				u8 idx[16], val[16];

				for (u32 j = 0; j < 16; j++)
				{
					const u8 m = control[j];
					const bool special = (m & 0x80) != 0;
					idx[j] = special ? 0xff : (m & 0x1f);
					val[j] = !special ? 0x00 : (m & 0xc0) == 0x80 ? 0x00 : (m & 0xe0) == 0xc0 ? 0xff : 0x80;
				}

				index = vld1q_u8(idx);
				fill = vld1q_u8(val);
			}

			uint8x16_t operator()(uint8x16_t a, uint8x16_t b) const
			{
				return vorrq_u8(vqtbl2q_u8(uint8x16x2_t{a, b}, index), fill);
			}
		};

		// ------------------------------------------------------------------------------------------------
		// The Last of Us: a decoder of 16-bit triangle indices. Each control byte (from r4 on) gives two
		// nibbles; each nibble picks one of 16 shuffle patterns (local store 0x12d50) that builds a
		// quadword from the previous quadword and the next bytes of the data stream (from r3 on), and
		// a size of 0, 4, 8 or 12 bytes to move past (2 bits per nibble value in 0xa5aafaf5). The high
		// nibble's quadword then the low nibble's are written as a pair, 32 bytes per control byte, from
		// r5 on.
		//
		// The loop is software-pipelined: its first two passes write results built from the registers
		// (the previous call's) to the first pair, which the third pass overwrites with the first real
		// pair; that first pair can still take bytes of the previous quadword, which comes from those
		// registers, so it is reproduced from them.
		u32 tlou_index_decode(u8* gpr, u8* ls)
		{
			static constexpr u8 c_identity[16] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
			static constexpr u8 c_rotate[16] = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 12, 13, 14, 15};
			constexpr u32 c_patterns = 0x12d50;
			constexpr u32 c_sizes = 0xa5aafaf5;

			// The alignment shuffle base and the pattern that delays the output pointer by two passes
			if (std::memcmp(ls + 0x12fa0, c_identity, 16) != 0 || std::memcmp(ls + 0x13250, c_rotate, 16) != 0)
			{
				return c_declined;
			}

			// The loop runs ((r6 + 3) >> 2) + 2 passes; the last two passes' results stay in flight, so it
			// writes pairs = passes - 2. The quadword it leaves in r15 is the next one's second half,
			// which a later call can take bytes of (see above), so that is set exactly too.
			const u32 passes = ((reg_w0(gpr, 6) + 3) >> 2) + 2;

			if (passes < 3 || passes > 0x1000)
			{
				return c_declined;
			}

			const u32 pairs = passes - 2;
			const u32 data = reg_w0(gpr, 3);
			const u32 control = reg_w0(gpr, 4);
			const u32 out = reg_w0(gpr, 5);

			u8 size[16];

			for (u32 n = 0; n < 16; n++)
			{
				size[n] = static_cast<u8>(((c_sizes << (2 * n)) | (n ? c_sizes >> (32 - 2 * n) : 0)) & 12);
			}

			// Walk the control bytes once for the data they consume: the pairs' and the one after
			if (control + pairs + 1 > 0x40000)
			{
				return c_declined;
			}

			u32 consumed = 0;

			for (u32 k = 0; k <= pairs; k++)
			{
				const u8 c = ls[control + k];
				consumed += size[c >> 4] + size[c & 15];
			}

			// What is read and written stays inside local store and apart: the data (a 16-byte read at
			// most 12 bytes past a step's start), the control bytes, the patterns, the program
			const u32 data_lo = data, data_hi = data + consumed + 32;
			const u32 out_lo = out & c_ls_mask, out_hi = out_lo + pairs * 32;

			if (data >= 0x40000 || data_hi > 0x40000 || out_hi > 0x40000 || ranges_overlap(out_lo, out_hi, data_lo, data_hi) ||
				ranges_overlap(out_lo, out_hi, control, control + pairs + 1) || ranges_overlap(out_lo, out_hi, c_patterns, c_patterns + 0x100) ||
				ranges_overlap(out_lo, out_hi, 0x05c40, 0x05d20))
			{
				return c_declined;
			}

			byte_shuffle pattern[16];

			for (u32 n = 0; n < 16; n++)
			{
				pattern[n] = byte_shuffle(ls + c_patterns + n * 16);
			}

			// The quadword before the first pair, from the registers: the data window (r18, r19 aligned
			// by r22) through the pattern in r17 after r15, then through the pattern r16 points at
			const qw window = qw_shufb(qw_reg(gpr, 18), qw_reg(gpr, 19), qw_reg(gpr, 22));
			const qw before = qw_shufb(qw_shufb(qw_reg(gpr, 15), window, qw_reg(gpr, 17)), window, qw_ls(ls, c_patterns + reg_w0(gpr, 16)));

			uint8x16_t prev = vld1q_u8(before.b);
			u32 at = data;

			for (u32 k = 0; k <= pairs; k++)
			{
				const u8 c = ls[control + k];
				const u32 hi = c >> 4, lo = c & 15;
				const uint8x16_t first = pattern[hi](prev, vld1q_u8(ls + at));
				const uint8x16_t second = pattern[lo](first, vld1q_u8(ls + at + size[hi]));
				at += size[hi] + size[lo];
				prev = second;

				if (k < pairs)
				{
					vst1q_u8(ls + out_lo + k * 32, first);
					vst1q_u8(ls + out_lo + k * 32 + 16, second);
				}
			}

			// r15 in spu_thread::gpr order (byte-reversed)
			vst1q_u8(gpr + 15 * 16, vqtbl1q_u8(prev, uint8x16_t{15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}));

			// bi $0
			return reg_w0(gpr, 0) & 0x3fffc;
		}

		struct entry
		{
			const char* name;
			program_fn fn;
		};

		// Programs with a native version. Names are exact: the entry address and the SHA-1 of the
		// program's words, so nothing else ever matches.
		constexpr entry s_programs[] =
		{
			{"__spu-a-0x045d0-N8Lay9SfvF5ULS9svvzgLdFhT4N6", tlou_depth_filter},
			{"__spu-a-0x054c8-eUh72AA0iP6TneVa58UPuioFeTsn", tlou_bit_unpack},
			{"__spu-a-0x040c0-9yspV9NGJJpXzWuaJxKGNw1Ukh0b", tlou_vertex_light},
			{"__spu-a-0x05c40-9b4LqnRNfXg9bxExaNKLzZfGYSmU", tlou_index_decode},
		};
	}

	program_fn find(const char* name)
	{
		for (const auto& e : s_programs)
		{
			if (e.name && std::strcmp(e.name, name) == 0)
			{
				return e.fn;
			}
		}

		return nullptr;
	}
#else
	program_fn find(const char*)
	{
		return nullptr;
	}
#endif
}
