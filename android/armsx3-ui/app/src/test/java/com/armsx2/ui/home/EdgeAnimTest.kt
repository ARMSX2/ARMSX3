package com.armsx2.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class EdgeAnimTest {

    // ---- number formats ----

    /** A spec field: sign bits, exponent bits, mantissa bits. */
    private fun field(s: Int, e: Int, m: Int) = (s shl 9) or (e shl 5) or m

    /** Bytes holding [bits], a string of 0s and 1s, MSB first. */
    private fun bits(bits: String): EdgeAnim.Bits {
        val b = ByteArray((bits.length + 7) / 8 + 1)
        bits.forEachIndexed { i, c -> if (c == '1') b[i / 8] = (b[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        return EdgeAnim.Bits(b, 0)
    }

    private fun read(s: Int, e: Int, m: Int, value: String) = EdgeAnim.readComponent(bits(value), field(s, e, m))

    @Test
    fun unsignedFixedPointReachesOne() {
        // A shadow's scale that is either off or on, in one bit; a scale's 1.0 in ten.
        assertEquals(1f, read(0, 0, 1, "1"))
        assertEquals(0f, read(0, 0, 1, "0"))
        assertEquals(1f, read(0, 0, 10, "1111111111"))
        assertEquals(0.5f, read(0, 0, 10, "1000000000"), 1e-3f)
    }

    @Test
    fun signedFixedPointIsTwosComplement() {
        assertEquals(0.5f, read(1, 0, 10, "01000000000"))
        assertEquals(-1f, read(1, 0, 10, "10000000000"))
        assertEquals(-1f / 1024, read(1, 0, 10, "11111111111"))
        // Just a sign: 0 or -1.
        assertEquals(0f, read(1, 0, 0, "0"))
        assertEquals(-1f, read(1, 0, 0, "1"))
    }

    @Test
    fun floatsHaveNoDenormals() {
        // The shadow's resting depth: exponent 7 of 3 bits, mantissa 9216 of 14 bits.
        assertEquals(25f, read(0, 3, 14, "111" + "10010000000000"))
        // Exponent 0 is an ordinary exponent: a scale's 1.0 in a one-bit exponent.
        assertEquals(1f, read(0, 1, 10, "0" + "0000000000"))
        assertEquals(2f, read(0, 1, 10, "1" + "0000000000"))
        // Sign first.
        assertEquals(-2f, read(1, 5, 14, "1" + "10000" + "00000000000000"))
    }

    @Test
    fun nothingStoredIsNaN() {
        assertTrue(read(0, 0, 0, "").isNaN())
    }

    // ---- sampling and matrices ----

    /** Two joints in a row along x; the child turns about z, a quarter turn over 10 frames. */
    private val arm = EdgeAnim.Skeleton(
        intArrayOf(-1, 0),
        floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f,
            0f, 0f, 0f, 1f, 2f, 0f, 0f, 1f, 1f, 1f,
        ),
    )

    private fun turn(angle: Double) = floatArrayOf(0f, 0f, sin(angle / 2).toFloat(), cos(angle / 2).toFloat())

    private val bend = EdgeAnim.Clip(
        duration = 1f, frameRate = 10f, frames = 11,
        channels = listOf(EdgeAnim.Channel(1, EdgeAnim.ROTATION, floatArrayOf(0f, 10f), turn(0.0) + turn(PI / 2))),
    )

    @Test
    fun sampledRotationsInterpolate() {
        val pose = arm.rest.copyOf()
        EdgeAnim.sample(bend, 5f, pose)
        val q = pose.copyOfRange(10, 14)
        val expected = turn(PI / 4)
        for (k in 0 until 4) assertEquals(expected[k], q[k], 1e-4f)
    }

    @Test
    fun worldMatricesFollowTheParent() {
        val pose = arm.rest.copyOf()
        EdgeAnim.sample(bend, 10f, pose)
        // Put the root 1 unit up and let the child's quarter turn point x to y.
        pose[5] = 1f
        val worlds = EdgeAnim.worlds(arm, pose)
        // The child sits 2 along x from the root: at (2, 1, 0).
        assertEquals(2f, worlds[12 + 3], 1e-5f)
        assertEquals(1f, worlds[12 + 7], 1e-5f)
        // Its x axis now points along y.
        assertEquals(0f, worlds[12 + 0], 1e-5f)
        assertEquals(1f, worlds[12 + 4], 1e-5f)
    }

    @Test
    fun skinningTheRestPoseLeavesTheMeshAlone() {
        val worlds = EdgeAnim.worlds(arm, arm.rest.copyOf())
        // Inverse binds of the rest pose: the root is identity, the child undoes its 2 along x.
        val inverse = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, -2f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        val skin = EdgeAnim.skinMatrices(worlds, inverse)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        for (j in 0 until 2) for (k in 0 until 12) assertEquals(identity[k], skin[12 * j + k], 1e-5f)
    }

    @Test
    fun partialWeightsBlendTowardTheClip() {
        val pose = arm.rest.copyOf()
        EdgeAnim.sample(bend, 10f, pose, weight = 0.5f)
        // Halfway between no turn and a quarter turn, renormalised: an eighth of a turn.
        val expected = turn(PI / 4)
        for (k in 0 until 4) assertEquals(expected[k], pose[10 + k], 1e-4f)
    }

    @Test
    fun aClipWithNoChannelsHoldsTheRestPose() {
        // Batman Arkham City Dynamic's "bg.anim": one frame, no length, nothing in it.
        val b = ByteArray(0x90)
        "EA03".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        val rate = 30f.toRawBits()
        for (k in 0 until 4) b[8 + k] = (rate ushr (24 - 8 * k)).toByte()
        b[0x0F] = 1; b[0x11] = 1; b[0x13] = 1 // joints, frames, frame sets
        val clip = EdgeAnim.clip(b, 1)
        assertNotNull(clip)
        assertTrue(clip!!.channels.isEmpty())
    }

    @Test
    fun notAClipIsRefused() {
        assertNull(EdgeAnim.clip(ByteArray(0x80), 4))
        assertNull(EdgeAnim.skeleton(ByteArray(0x40)))
    }

    // ---- real themes ----

    private fun saru(): RafScene.Scene? {
        val path = System.getenv("P3T_DYNAMIC")?.split(',')?.firstOrNull { "SARUTHEME" in it && File(it).isFile } ?: return null
        val theme = P3tTheme.ArrayBytes(File(path).readBytes())
        val anim = P3tTheme.read(theme).anim ?: return null
        val out = ByteArrayOutputStream()
        if (!P3tAnimation.unpack(theme, anim.offset, anim.size, out, 256L shl 20)) return null
        return RafScene.read(P3tTheme.ArrayBytes(out.toByteArray()))
    }

    /** Sony's Ape Escape theme, when given in P3T_DYNAMIC. */
    @Test
    fun apeEscapeRigsDecode() {
        val scene = saru()
        assumeTrue(scene != null)
        scene!!
        val skinned = scene.actors.filter { it.mesh?.skinned == true }
        assertEquals(18, skinned.size)
        for (a in skinned) {
            val rig = a.rig
            assertNotNull(a.name, rig)
            rig!!
            assertTrue(a.name, rig.clips.isNotEmpty() && rig.clips.all { it != null })
            // Every rotation key is a unit quaternion.
            for (clip in rig.clips.filterNotNull()) for (ch in clip.channels) if (ch.kind == EdgeAnim.ROTATION) {
                for (i in ch.frames.indices) {
                    val q = ch.values.copyOfRange(4 * i, 4 * i + 4)
                    assertEquals(1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-3f)
                }
            }
            // Where no clip moves a joint, or anything above it, its rest pose is what is drawn, and
            // skinning that gives back the mesh: world x inverse bind is the identity. (Joints the
            // clips do move can rest anywhere: the birds' lead joints rest stretched.)
            val driven = rig.clips.filterNotNull().flatMap { c -> c.channels.map { it.joint } }.toSet()
            fun still(j: Int): Boolean = j !in driven && (rig.skeleton.parents[j] < 0 || still(rig.skeleton.parents[j]))
            val skin = EdgeAnim.skinMatrices(EdgeAnim.worlds(rig.skeleton, rig.skeleton.rest.copyOf()), rig.inverseBinds)
            for (j in a.mesh!!.segments.flatMap { it.palette?.toList().orEmpty() }.toSet().filter(::still)) {
                for (k in 0 until 12) {
                    val want = if (k == 0 || k == 5 || k == 10) 1f else 0f
                    assertEquals("${a.name} joint $j", want, skin[12 * j + k], 2e-3f)
                }
            }
        }
    }

    @Test
    fun apeEscapeClipsPlaceTheirCharacters() {
        val scene = saru()
        assumeTrue(scene != null)
        fun rig(actor: String) = scene!!.actors.first { it.name == actor }.rig!!
        // One monkey's root at the start of its clip: under the shadow the shadow clip puts there.
        val ape = rig("ape04")
        val pose = ape.skeleton.rest.copyOf()
        EdgeAnim.sample(ape.clips[0]!!, 0f, pose)
        assertEquals(-56.08f, pose[4], 0.01f)
        assertEquals(4.44f, pose[5], 0.01f)
        assertEquals(30.46f, pose[6], 0.01f)
        val shadow = rig("ape01_kage")
        val shadowPose = shadow.skeleton.rest.copyOf()
        EdgeAnim.sample(shadow.clips[0]!!, 0f, shadowPose)
        assertEquals(-55.166f, shadowPose[30 + 4], 0.01f)
        assertEquals(30f, shadowPose[30 + 6], 0.01f)
        // The butterfly is a flipbook: its first wing strip shows at frame 0 and hides at frame 1.
        val fly = rig("fx_DT_Butterfly_001")
        val on = fly.skeleton.rest.copyOf().also { EdgeAnim.sample(fly.clips[0]!!, 0f, it) }
        val off = fly.skeleton.rest.copyOf().also { EdgeAnim.sample(fly.clips[0]!!, 1f, it) }
        assertEquals(3f, on[10 + 7], 1e-4f)
        assertTrue(abs(off[10 + 7]) < 1e-3f)
    }
}
