package com.armsx2.ui.home

import kotlin.math.sqrt

/**
 * A skeleton and the clips that move it, from a dynamic theme's Sony Edge files: `.skel` (ES01),
 * `.invbind`, and `.anim` (EA03). No specification of these was found; the layout below was
 * worked out from Sony's Ape Escape theme (27 skeletons, 17 clips) and checked the way the mesh
 * format was: every clip's sections decode to exactly their declared sizes, all 56,050 rotations
 * come out valid quaternions, rest poses reproduce the bind pose exactly, and the posed monkeys
 * match the PS3's own preview of the theme.
 *
 * Skeleton (big-endian): joint count at 0x0C, then self-relative offsets at 0x14 (the rest pose:
 * per joint a quaternion x y z w, a translation and a scale, 16 bytes each) and 0x18 (parents,
 * int16, -1 for a root, always before their children). Inverse bind matrices are 3x4, row-major,
 * one per joint.
 *
 * Clip: duration and sample rate (floats at 4 and 8), frame and frame-set counts at 0x10, the
 * number of constant and animated rotation, translation, scale and user channels at 0x16, and
 * self-relative offsets at 0x34 (frame sets: size, offset), 0x38 (first frame, in-between frames),
 * 0x3C-0x48 (constant values) and 0x4C (packing specs). Joint tables start at 0x60, one per
 * channel list, padded to 4 entries with the joint count (constant rotations to 8).
 *
 * Every value is bit-packed per its channel's spec: three 10-bit fields (x, y, z; for rotations the
 * three stored quaternion components) of sign bits, exponent bits and mantissa bits, and a 2-bit
 * tail naming the quaternion component left out, rebuilt as sqrt(1 - the others' squares). See
 * [readComponent] for the number formats.
 *
 * A frame set holds a 16-byte header (byte sizes of its key frame's rotation, translation, scale
 * and user parts, then of its in-between rotation, translation and scale data), the key frame,
 * one bit per channel per in-between frame saying which have a key, then those keys' values,
 * channel by channel. Values between keys are interpolated, up to the next set's key frame.
 */
object EdgeAnim {

    class Skeleton(
        val parents: IntArray,
        /** 10 floats a joint: rotation x y z w, translation x y z, scale x y z. */
        val rest: FloatArray,
    ) {
        val joints: Int get() = parents.size
    }

    /** One animated property of one joint: its keys' frames and values (4 floats for rotations, 3 otherwise). */
    class Channel(val joint: Int, val kind: Int, val frames: FloatArray, val values: FloatArray) {
        val width: Int get() = if (kind == ROTATION) 4 else 3
    }

    class Clip(val duration: Float, val frameRate: Float, val frames: Int, val channels: List<Channel>)

    const val ROTATION = 0
    const val TRANSLATION = 1
    const val SCALE = 2

    private const val SKELETON_TAG = 0x45533031L // "ES01"
    private const val CLIP_TAG = 0x45413033L // "EA03"
    private const val MAX_JOINTS = 256
    private const val MAX_FRAMES = 1 shl 16
    private const val MAX_CHANNELS = 1024

    private class Damaged : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun skeleton(b: ByteArray): Skeleton? = try {
        if (b.size < 0x30 || be32(b, 0) != SKELETON_TAG) null else {
            val n = be16(b, 0x0C)
            if (n > MAX_JOINTS) throw Damaged()
            val restAt = rel(b, 0x14)
            val parentsAt = rel(b, 0x18)
            val rest = FloatArray(n * 10)
            val parents = IntArray(n)
            for (j in 0 until n) {
                val at = restAt + 48 * j
                for (k in 0 until 4) rest[10 * j + k] = f32(b, at + 4 * k)
                for (k in 0 until 3) rest[10 * j + 4 + k] = f32(b, at + 16 + 4 * k)
                for (k in 0 until 3) rest[10 * j + 7 + k] = f32(b, at + 32 + 4 * k)
                val p = be16(b, parentsAt + 2 * j).toShort().toInt()
                if (p >= j) throw Damaged() // parents come first
                parents[j] = p
            }
            if (rest.any { !it.isFinite() }) null else Skeleton(parents, rest)
        }
    } catch (_: Damaged) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** 12 floats a joint: a 3x4 matrix, row-major. Null when there are not [joints] of them. */
    fun inverseBinds(b: ByteArray, joints: Int): FloatArray? {
        if (b.size < 48L * joints) return null
        val out = FloatArray(12 * joints) { f32(b, 4 * it) }
        return if (out.all { it.isFinite() }) out else null
    }

    /** The clip, or null when it is not an EA03 clip or does not decode cleanly for [joints] joints. */
    fun clip(b: ByteArray, joints: Int): Clip? = try {
        decodeClip(b, joints)
    } catch (_: Damaged) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun decodeClip(b: ByteArray, joints: Int): Clip? {
        if (b.size < 0x60 || be32(b, 0) != CLIP_TAG) return null
        val duration = f32(b, 4)
        val rate = f32(b, 8)
        val clipJoints = be16(b, 0x0E)
        val frames = be16(b, 0x10)
        val sets = be16(b, 0x12)
        if (!(rate > 0f) || frames !in 1..MAX_FRAMES || sets !in 1..frames) return null
        val counts = IntArray(8) { be16(b, 0x16 + 2 * it) } // const R T S U, anim R T S U
        // A clip that holds the rest pose: no channels, one frame, no length. Batman Arkham City
        // Dynamic and Bloodborne Dynamic give every quad one.
        if (counts.all { it == 0 }) return Clip(0f, rate, frames, emptyList())
        if (!(duration > 0f)) return null
        if (counts.sum() > MAX_CHANNELS) throw Damaged()
        val dma = rel(b, 0x34)
        val info = rel(b, 0x38)
        val constAt = IntArray(3) { rel(b, 0x3C + 4 * it) }
        val specAt = rel(b, 0x4C)

        // Joint tables: which joint each channel moves. Padding entries name clipJoints.
        var at = 0x60
        val tables = Array(8) { t ->
            val n = counts[t]
            val table = IntArray(n) { be16(b, at + 2 * it) }
            at += 2 * (if (t == 0) (n + 7) and 7.inv() else (n + 3) and 3.inv())
            table
        }
        // Specs: const R, anim R..., const T, anim T..., const S, anim S..., const U.
        val constSpec = IntArray(3)
        val animSpec = arrayOfNulls<IntArray>(3)
        var w = specAt
        for (kind in 0 until 3) {
            constSpec[kind] = be32(b, w).toInt(); w += 4
            val n = counts[4 + kind]
            animSpec[kind] = IntArray(n) { be32(b, w + 4 * it).toInt() }
            w += 4 * n
        }

        val channels = ArrayList<Channel>()
        // Constant channels: one value for the whole clip.
        for (kind in 0 until 3) {
            val bits = Bits(b, constAt[kind])
            for (j in tables[kind]) {
                val v = readValue(bits, constSpec[kind], kind)
                if (j < joints && j < clipJoints) channels += Channel(j, kind, floatArrayOf(0f), v)
            }
        }

        // Animated channels: gather each one's keys across the frame sets.
        val animated = counts[4] + counts[5] + counts[6] + counts[7]
        val keyFrames = Array(animated) { FloatList() }
        val keyValues = Array(animated) { FloatList() }
        val kindOf = IntArray(animated) { c -> if (c < counts[4]) 0 else if (c < counts[4] + counts[5]) 1 else if (c < counts[4] + counts[5] + counts[6]) 2 else 3 }
        val specOf = IntArray(animated) { c -> if (kindOf[c] == 3) 0 else animSpec[kindOf[c]]!![c - kindStart(counts, kindOf[c])] }
        for (s in 0 until sets) {
            val offset = be32(b, dma + 8 * s + 4).toInt()
            val first = be16(b, info + 4 * s)
            val between = be16(b, info + 4 * s + 2)
            if (offset < 0 || offset + 16 > b.size || first + between >= frames) throw Damaged()
            val keyBytes = IntArray(4) { be16(b, offset + 2 * it) }
            val dataBytes = IntArray(3) { be16(b, offset + 8 + 2 * it) }
            // The key frame.
            var section = offset + 16
            for (kind in 0 until 3) {
                val bits = Bits(b, section)
                for (c in 0 until animated) if (kindOf[c] == kind) {
                    keyFrames[c].add(first.toFloat())
                    keyValues[c].addAll(readValue(bits, specOf[c], kind))
                }
                section += keyBytes[kind]
            }
            if (between == 0) continue
            // Which channels have a key in each in-between frame.
            val masksAt = offset + 16 + keyBytes.sum()
            val maskBits = Bits(b, masksAt)
            val masks = Array(animated) { BooleanArray(between) { maskBits.read(1) == 1 } }
            var dataAt = masksAt + (animated * between + 7) / 8
            for (kind in 0 until 3) {
                val bits = Bits(b, dataAt)
                for (c in 0 until animated) if (kindOf[c] == kind) {
                    for (f in 0 until between) if (masks[c][f]) {
                        keyFrames[c].add((first + 1 + f).toFloat())
                        keyValues[c].addAll(readValue(bits, specOf[c], kind))
                    }
                }
                if (bits.position > (dataAt + dataBytes[kind]).toLong() * 8) throw Damaged()
                dataAt += dataBytes[kind]
            }
        }
        for (c in 0 until animated) {
            val kind = kindOf[c]
            if (kind == 3) continue // user channels drive nothing drawn
            val j = tables[4 + kind][c - kindStart(counts, kind)]
            if (j >= joints || j >= clipJoints || keyFrames[c].size == 0) continue
            channels += Channel(j, kind, keyFrames[c].toArray(), keyValues[c].toArray())
        }
        return Clip(duration, rate, frames, channels)
    }

    private fun kindStart(counts: IntArray, kind: Int): Int = when (kind) {
        0 -> 0
        1 -> counts[4]
        2 -> counts[4] + counts[5]
        else -> counts[4] + counts[5] + counts[6]
    }

    /**
     * One value per its spec: a quaternion (x y z w) for rotations, else x y z, with NaN for a
     * component the spec does not store (it keeps the rest pose).
     */
    private fun readValue(bits: Bits, spec: Int, kind: Int): FloatArray {
        val fields = IntArray(3) { (spec ushr (22 - 10 * it)) and 0x3FF }
        val stored = FloatArray(3) { readComponent(bits, fields[it]) }
        if (kind != ROTATION) return stored
        val left = spec and 3
        val q = FloatArray(4)
        var k = 0
        var sum = 0f
        for (i in 0 until 4) {
            if (i == left) continue
            val v = if (stored[k].isNaN()) 0f else stored[k]
            q[i] = v
            sum += v * v
            k++
        }
        q[left] = sqrt(maxOf(0f, 1f - sum))
        return q
    }

    /**
     * One component. [field] is sign bits (1), exponent bits (4) and mantissa bits (5). With no
     * exponent bits it is fixed point: two's complement over 2^m when signed, M / (2^m - 1) when
     * not (so a scale's 1.0 is exact). Otherwise a small float, 2^(E - bias) x (1 + M / 2^m) with
     * the IEEE bias 2^(e-1) - 1 and no denormals: an exponent of 0 is an ordinary one, which is
     * how a scale's resting 1.0 is stored in one exponent bit. NaN when nothing is stored.
     */
    internal fun readComponent(bits: Bits, field: Int): Float {
        val s = (field ushr 9) and 1
        val e = (field ushr 5) and 0xF
        val m = field and 0x1F
        if (s + e + m == 0) return Float.NaN
        val sign = if (s == 1) bits.read(1) else 0
        val exponent = if (e > 0) bits.read(e) else 0
        val mantissa = if (m > 0) bits.read(m) else 0
        if (e == 0) {
            if (s == 1) {
                var n = (sign shl m) or mantissa
                if (sign == 1) n -= 1 shl (m + 1)
                return n.toFloat() / (1 shl m)
            }
            return if (m == 0) 0f else mantissa.toFloat() / ((1 shl m) - 1)
        }
        val bias = (1 shl (e - 1)) - 1
        val v = Math.scalb(1f + mantissa.toFloat() / (1 shl m), exponent - bias)
        return if (sign == 1) -v else v
    }

    /**
     * Each joint's local transform at [frame] (fractional; the clip loops), into [pose] (10 floats a
     * joint as [Skeleton.rest]), weighted by [weight]: 1 replaces what is there, less blends toward it.
     */
    fun sample(clip: Clip, frame: Float, pose: FloatArray, weight: Float = 1f) {
        val tmp = FloatArray(4)
        for (ch in clip.channels) {
            val w = ch.width
            val n = ch.frames.size
            // The last key at or before the frame, and the one after it.
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (ch.frames[mid] <= frame) lo = mid else hi = mid - 1
            }
            val a = lo
            val bIdx = if (a + 1 < n) a + 1 else a
            val span = ch.frames[bIdx] - ch.frames[a]
            val t = if (span > 0f) ((frame - ch.frames[a]) / span).coerceIn(0f, 1f) else 0f
            val base = 10 * ch.joint + when (ch.kind) { ROTATION -> 0; TRANSLATION -> 4; else -> 7 }
            if (ch.kind == ROTATION) {
                var dot = 0f
                for (k in 0 until 4) dot += ch.values[4 * a + k] * ch.values[4 * bIdx + k]
                val flip = if (dot < 0f) -1f else 1f
                for (k in 0 until 4) tmp[k] = ch.values[4 * a + k] + (flip * ch.values[4 * bIdx + k] - ch.values[4 * a + k]) * t
                blendRotation(pose, base, tmp, weight)
            } else {
                for (k in 0 until 3) {
                    val va = ch.values[w * a + k]
                    if (va.isNaN()) continue
                    val v = va + (ch.values[w * bIdx + k] - va) * t
                    pose[base + k] = pose[base + k] + (v - pose[base + k]) * weight
                }
            }
        }
    }

    private fun blendRotation(pose: FloatArray, at: Int, q: FloatArray, weight: Float) {
        var dot = 0f
        for (k in 0 until 4) dot += pose[at + k] * q[k]
        val flip = if (dot < 0f) -1f else 1f
        var len = 0f
        for (k in 0 until 4) {
            val v = pose[at + k] + (flip * q[k] - pose[at + k]) * weight
            pose[at + k] = v
            len += v * v
        }
        val inv = if (len > 1e-12f) 1f / sqrt(len) else 0f
        for (k in 0 until 4) pose[at + k] *= inv
    }

    /**
     * World matrices from local transforms: 12 floats a joint, 3x4 row-major, parent x local, where
     * local = translate x rotate x scale.
     */
    fun worlds(skeleton: Skeleton, pose: FloatArray, out: FloatArray = FloatArray(12 * skeleton.joints)): FloatArray {
        val m = FloatArray(12)
        val scratch = FloatArray(12)
        for (j in 0 until skeleton.joints) {
            localMatrix(pose, 10 * j, m)
            val p = skeleton.parents[j]
            if (p < 0) m.copyInto(out, 12 * j) else multiply34(out, 12 * p, m, 0, out, 12 * j, scratch)
        }
        return out
    }

    /** For skinning: world x inverse bind, per joint, 12 floats each. */
    fun skinMatrices(worlds: FloatArray, inverseBinds: FloatArray, out: FloatArray = FloatArray(worlds.size)): FloatArray {
        val scratch = FloatArray(12)
        for (j in 0 until minOf(worlds.size, inverseBinds.size) / 12) multiply34(worlds, 12 * j, inverseBinds, 12 * j, out, 12 * j, scratch)
        return out
    }

    private fun localMatrix(pose: FloatArray, at: Int, m: FloatArray) {
        val x = pose[at]; val y = pose[at + 1]; val z = pose[at + 2]; val w = pose[at + 3]
        val n = x * x + y * y + z * z + w * w
        val s = if (n > 1e-12f) 2f / n else 0f
        val sx = pose[at + 7]; val sy = pose[at + 8]; val sz = pose[at + 9]
        m[0] = (1 - s * (y * y + z * z)) * sx; m[1] = s * (x * y - z * w) * sy; m[2] = s * (x * z + y * w) * sz; m[3] = pose[at + 4]
        m[4] = s * (x * y + z * w) * sx; m[5] = (1 - s * (x * x + z * z)) * sy; m[6] = s * (y * z - x * w) * sz; m[7] = pose[at + 5]
        m[8] = s * (x * z - y * w) * sx; m[9] = s * (y * z + x * w) * sy; m[10] = (1 - s * (x * x + y * y)) * sz; m[11] = pose[at + 6]
    }

    /** out = a x b for 3x4 affine matrices (an implied 0 0 0 1 bottom row), through [r]. */
    private fun multiply34(a: FloatArray, ai: Int, b: FloatArray, bi: Int, out: FloatArray, oi: Int, r: FloatArray) {
        for (row in 0 until 3) {
            val a0 = a[ai + 4 * row]; val a1 = a[ai + 4 * row + 1]; val a2 = a[ai + 4 * row + 2]; val a3 = a[ai + 4 * row + 3]
            for (col in 0 until 4) {
                var v = a0 * b[bi + col] + a1 * b[bi + 4 + col] + a2 * b[bi + 8 + col]
                if (col == 3) v += a3
                r[4 * row + col] = v
            }
        }
        r.copyInto(out, oi)
    }

    internal class Bits(private val data: ByteArray, byteStart: Int) {
        var position: Long = byteStart.toLong() * 8
            private set

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val at = (position ushr 3).toInt()
                if (at >= data.size) throw Damaged()
                v = (v shl 1) or ((data[at].toInt() ushr (7 - (position and 7).toInt())) and 1)
                position++
            }
            return v
        }
    }

    private class FloatList {
        private var data = FloatArray(16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun addAll(v: FloatArray) = v.forEach(::add)
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private fun rel(b: ByteArray, at: Int): Int {
        val v = be32(b, at)
        val target = at + v
        if (v == 0L || target > b.size) throw Damaged()
        return target.toInt()
    }

    private fun f32(b: ByteArray, at: Int): Float = Float.fromBits(be32(b, at).toInt())

    private fun be16(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) throw Damaged()
        return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }

    private fun be32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) throw Damaged()
        return ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    }
}
