package com.armsx2.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RafSceneTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // ---- compressed indexes ----

    /** Header: reused count, bias, flag-stream bytes, value width; then flags, codes, values. */
    private fun block(reused: Int, bias: Int, flagBytes: Int, width: Int, vararg streams: Int): ByteArray =
        byteArrayOf(
            (reused shr 8).toByte(), reused.toByte(), (bias shr 8).toByte(), bias.toByte(),
            (flagBytes shr 8).toByte(), flagBytes.toByte(), width.toByte(), 0,
        ) + ByteArray(streams.size) { streams[it].toByte() }

    @Test
    fun quadSharesAnEdge() {
        // Four new vertices, read one at a time (flags 0); triangle 1 reads three (code 3),
        // triangle 2 shares its first and last (code 0: p0, p2, new).
        val quad = block(0, 0, 1, 0, 0x00, 0xC0)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3), RafScene.edgeIndices(quad, 6, 4))
    }

    @Test
    fun theOtherEdgeCodesKeepTheWinding() {
        // Triangle (0 1 2), then code 1 (p2, p1, new) and code 2 (p1, p0, new).
        val strip = block(0, 0, 1, 0, 0x00, 0xD8)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 1, 3, 1, 2, 4), RafScene.edgeIndices(strip, 9, 5))
    }

    @Test
    fun reusedVerticesComeFromTheirLane() {
        // Triangle (0 1 2), then (0 2 x) where x's read is flagged: the first reused value, 4 bits
        // wide, is 4, less the bias of 1, added to lane 0 (from 0): x = 3.
        val b = block(1, 1, 1, 4, 0x10, 0xC0, 0x40)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3), RafScene.edgeIndices(b, 6, 4))
        // Nine reads, all reused, values 1..9: the first eight fill lanes 0..7, and the ninth adds
        // to lane 0 again, 1 + 9.
        val lanes = block(
            9, 0, 2, 4,
            0xFF, 0x80,                   // flags: nine reused
            0xFC,                         // codes: three triangles read whole
            0x12, 0x34, 0x56, 0x78, 0x90, // values
        )
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10), RafScene.edgeIndices(lanes, 9, 11))
    }

    @Test
    fun badStreamsAreRefused() {
        // An edge code with no triangle before it.
        assertNull(RafScene.edgeIndices(block(0, 0, 1, 0, 0x00, 0x00), 3, 3))
        // An index past the vertices.
        assertNull(RafScene.edgeIndices(block(0, 0, 1, 0, 0x00, 0xC0), 6, 3))
        // More reads than the flag stream holds.
        assertNull(RafScene.edgeIndices(block(0, 0, 1, 0, 0x00, 0xFF, 0xF0), 18, 18))
        // Too short for its own header.
        assertNull(RafScene.edgeIndices(ByteArray(5), 3, 3))
        // A reused value that is not there.
        assertNull(RafScene.edgeIndices(block(3, 0, 1, 16, 0xE0, 0xC0), 3, 3))
    }

    /** Fallout NV Custom Dynamic's backdrop: a 5x5 grid, 19 of its 96 indexes reused. */
    @Test
    fun realGrid() {
        val b = hex("00130000000604000622714d5ae0d754010d511744e723465cba75daa29bae60")
        val expected = intArrayOf(
            0, 1, 2, 2, 1, 3, 3, 1, 4, 2, 3, 5, 5, 3, 6, 6, 3, 7, 7, 3, 4, 7, 4, 8, 7, 8, 9, 7, 9, 10, 7, 10,
            6, 6, 10, 11, 6, 11, 12, 6, 12, 5, 12, 11, 13, 13, 11, 14, 14, 11, 15, 15, 11, 10, 15, 10, 16, 16,
            10, 9, 16, 9, 17, 17, 9, 18, 18, 9, 8, 17, 19, 16, 16, 19, 20, 16, 20, 15, 15, 20, 21, 15, 21, 14,
            20, 22, 21, 22, 20, 23, 23, 20, 19, 22, 23, 24,
        )
        assertArrayEquals(expected, RafScene.edgeIndices(b, 96, 25))
    }

    /** Ape Escape's beach bed: 495 vertices, 57 reused values 9 bits wide with a bias of 13. */
    @Test
    fun realBiasedMesh() {
        val b = hex(
            "0039000d004509000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "0336d0ba9255db5ccd68aeefbdd74ddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd" +
                "dddddddddddcdf38013110545111f39c141445154440375350e8f43a3d3e9f53abd40904c22160a838120b0b0542a0c0607846220405c141b06044220f0d82c3" +
                "0080b81406150a8582c161008c32080a85c2a1505838181b0000000000000000",
        )
        val out = RafScene.edgeIndices(b, 906, 495)
        assertNotNull(out)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 1, 3, 3, 1, 4, 5, 6, 7, 7, 6, 8, 7, 8, 9, 10, 11, 12, 12, 11, 13), out!!.copyOf(24))
        assertEquals(259206, out.sum())
        assertEquals(495, out.toSet().size)
        var hash = 0L
        for (i in out) hash = (hash * 31 + i) and 0xFFFFFFFFL
        assertEquals(625667672L, hash)
        // A closed surface's worth of triangles: no edge in more than two.
        val edges = HashMap<Pair<Int, Int>, Int>()
        for (t in out.indices step 3) for (k in 0 until 3) {
            val a = out[t + k]
            val c = out[t + (k + 1) % 3]
            edges.merge(minOf(a, c) to maxOf(a, c), 1, Int::plus)
        }
        assertTrue(edges.values.all { it <= 2 })
    }

    // ---- meshes ----

    private val smokeSheet = EdgeSamples.smokeSheet

    @Test
    fun realMeshDecodes() {
        val mesh = RafScene.edgeMesh(smokeSheet)
        assertNotNull(mesh)
        mesh!!
        assertEquals(4, mesh.vertexCount)
        assertFalse(mesh.skinned)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3), mesh.indices)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), mesh.uvs, 0f)
        assertEquals(-25.6f, mesh.positions[0], 1e-4f)
        assertEquals(25.6f, mesh.positions[8], 1e-4f)
        assertEquals(51.2 * 51.2, mesh.area(), 1e-2)
        assertFalse(RafScene.collapsed(smokeSheet))
    }

    @Test
    fun collapsedMeshesAreFound() {
        // Positions zeroed: a mesh with no area.
        val flat = smokeSheet.copyOf()
        for (i in 0x110 until 0x140) flat[i] = 0
        assertTrue(RafScene.collapsed(flat))
        // Indexes zeroed too, as Fallout NV's author did: undecodable, and nothing to draw.
        val gone = flat.copyOf()
        for (i in 0x100 until 0x110) gone[i] = 0
        assertNull(RafScene.edgeMesh(gone))
        assertTrue(RafScene.collapsed(gone))
        // Indexes zeroed with the points left (that theme's FrontRocks): still nothing to draw.
        val rocks = smokeSheet.copyOf()
        for (i in 0x100 until 0x110) rocks[i] = 0
        assertTrue(RafScene.collapsed(rocks))
        // Indexes that are garbage: damaged, not hidden.
        val damaged = smokeSheet.copyOf()
        for (i in 0x100 until 0x110) damaged[i] = 0x5A
        assertNull(RafScene.edgeMesh(damaged))
        assertFalse(RafScene.collapsed(damaged))
    }

    @Test
    fun truncatedMeshesAreRefused() {
        for (n in listOf(0, 0x20, 0x44, 0x100, 0x120, smokeSheet.size - 60)) {
            assertNull("cut at $n", RafScene.edgeMesh(smokeSheet.copyOf(n)))
        }
    }

    @Test
    fun materialsReadTheirEffect() {
        val lit = RafScene.Material("basic_lighting", null)
        assertTrue(lit.opaque); assertTrue(lit.depthTest); assertFalse(lit.additive)
        val flare = RafScene.Material("basic_lighting_alpha_add", null)
        assertFalse(flare.opaque); assertTrue(flare.additive)
        val backdrop = RafScene.Material("pure_texture_alpha_1_depth_0", null)
        assertFalse(backdrop.opaque); assertFalse(backdrop.depthTest)
        val sprite = RafScene.Material("pure_texture", null)
        assertFalse(sprite.opaque); assertTrue(sprite.depthTest)
    }
}
