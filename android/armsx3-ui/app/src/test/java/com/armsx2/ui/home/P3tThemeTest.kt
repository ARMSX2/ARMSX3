package com.armsx2.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Deflater

class P3tThemeTest {

    /** Writes a theme in the P3T layout: header, elements, ids, strings, ints, floats, files. */
    private class Theme {
        private val strings = ByteArrayOutputStream()
        private val names = HashMap<String, Int>()
        private val elements = ByteArrayOutputStream()
        private val files = ByteArrayOutputStream()

        class Attr(val name: String, val type: Int, val a: Long, val b: Long)

        fun name(s: String): Int = names.getOrPut(s) {
            val at = strings.size(); strings.write(s.toByteArray()); strings.write(0); at
        }

        fun file(bytes: ByteArray): Pair<Long, Long> {
            val at = files.size().toLong(); files.write(bytes); return at to bytes.size.toLong()
        }

        fun fileAttr(name: String, bytes: ByteArray): Attr = file(bytes).let { Attr(name, 6, it.first, it.second) }

        fun element(name: String, vararg attrs: Attr, count: Long = attrs.size.toLong()) {
            val b = ByteBuffer.allocate(28 + attrs.size * 16)
            b.putInt(name(name)).putInt(count.toInt()).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
            for (a in attrs) b.putInt(name(a.name)).putInt(a.type).putInt(a.a.toInt()).putInt(a.b.toInt())
            elements.write(b.array())
        }

        fun bytes(stringOffsetOverride: Long? = null): ByteArray {
            val header = 64
            val e = elements.toByteArray(); val s = strings.toByteArray(); val f = files.toByteArray()
            val eOff = header; val iOff = eOff + e.size; val sOff = iOff; val nOff = sOff + s.size; val fOff = nOff
            val out = ByteBuffer.allocate(fOff + f.size)
            out.put("P3TF".toByteArray()).putInt(0x110)
            out.putInt(eOff).putInt(e.size)                   // elements
            out.putInt(iOff).putInt(0)                        // ids
            out.putInt((stringOffsetOverride ?: sOff.toLong()).toInt()).putInt(s.size) // strings
            out.putInt(nOff).putInt(0).putInt(nOff).putInt(0) // integers, floats
            out.putInt(fOff).putInt(f.size)                   // files
            out.position(header)
            out.put(e).put(s).put(f)
            return out.array()
        }
    }

    /** Enough of a JPEG for the frame header to be found: SOI, SOF0 with the size, EOI. */
    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(17)
            .put(0xFF.toByte()).put(0xD8.toByte())                          // SOI
            .put(0xFF.toByte()).put(0xC0.toByte()).putShort(11).put(8)      // SOF0, 8-bit
            .putShort(height.toShort()).putShort(width.toShort())
            .put(1).put(1).put(0x11).put(0)                                 // one component
            .put(0xFF.toByte()).put(0xD9.toByte())                          // EOI
            .array()

    /** A big-endian RGBA8888 GIM whose rows are padded to 16 bytes, zlib-packed like a preview. */
    private fun packedPreview(width: Int, height: Int, pixels: ByteArray): ByteArray {
        val row = width * 4
        val pitch = (row + 15) / 16 * 16
        val image = 0x10 + 0x40 + pitch * height
        val gim = ByteBuffer.allocate(0x10 + 0x10 + 0x10 + image)
        gim.put(".GIM1.00".toByteArray()).put(0).put("PSP".toByteArray()).putInt(0)
        gim.putShort(2).putShort(0).putInt(0x20 + image).putInt(0x10).putInt(0x10)  // root
        gim.putShort(3).putShort(0).putInt(0x10 + image).putInt(0x10).putInt(0x10)  // picture
        gim.putShort(4).putShort(0).putInt(image).putInt(image).putInt(0x10)        // image
        val header = gim.position()
        gim.putShort(0x30).putShort(0).putShort(3).putShort(0)                      // size, ref, RGBA8888, linear
        gim.putShort(width.toShort()).putShort(height.toShort()).putShort(32).putShort(16)
        gim.putShort(1).putShort(2).putShort(0).putShort(0)
        gim.putInt(0x30).putInt(0x40).putInt(0x40 + pitch * height)
        gim.position(header + 0x40)
        for (y in 0 until height) {
            gim.put(pixels, y * row, row)
            gim.position(gim.position() + pitch - row)
        }
        return deflate(gim.array())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = P3tTheme.read(P3tTheme.ArrayBytes(bytes))

    @Test
    fun staticThemePrefersTheLargestHdPicture() {
        val t = Theme()
        val small = jpeg(1280, 720); val big = jpeg(1920, 1080)
        t.element("theme"); t.element("bgimagetable")
        t.element("bgimage", t.fileAttr("hd", small), t.fileAttr("sd", jpeg(720, 480)))
        t.element("bgimage", t.fileAttr("sd", jpeg(854, 480)), t.fileAttr("hd", big))
        val r = read(t.bytes())
        assertNull(r.failure)
        assertEquals(P3tTheme.Source.HD, r.picture!!.source)
        assertEquals(1920, r.picture!!.width); assertEquals(1080, r.picture!!.height)
        assertArrayEquals(big, r.picture!!.jpeg)
        assertTrue(!r.dynamic)
    }

    @Test
    fun sdIsUsedWhenThereIsNoHd() {
        val t = Theme()
        t.element("bgimage", t.fileAttr("sd", jpeg(720, 480)))
        val r = read(t.bytes())
        assertEquals(P3tTheme.Source.SD, r.picture!!.source)
    }

    @Test
    fun dynamicThemeFallsBackToItsPreview() {
        val pixels = ByteArray(3 * 2 * 4) { (it * 7).toByte() }
        val t = Theme()
        t.element("bgimage", t.fileAttr("anim", "_RAF".toByteArray() + ByteArray(64)))
        t.element("info", t.fileAttr("preview", packedPreview(3, 2, pixels)))
        val r = read(t.bytes())
        assertNull(r.failure)
        assertTrue(r.dynamic)
        assertEquals(68L, r.anim!!.size) // "_RAF" and 64 bytes: handed on to P3tAnimation
        assertEquals(P3tTheme.Source.PREVIEW, r.picture!!.source)
        assertEquals(3, r.picture!!.width); assertEquals(2, r.picture!!.height)
        assertArrayEquals(pixels, r.picture!!.rgba)
    }

    @Test
    fun dynamicThemeWithoutAPreviewSaysSo() {
        val t = Theme()
        t.element("bgimage", t.fileAttr("anim", "_RAF".toByteArray() + ByteArray(64)))
        val r = read(t.bytes())
        assertEquals(P3tTheme.Failure.DYNAMIC_ONLY, r.failure)
        assertNotNull(r.anim) // still worth trying as a slideshow
    }

    @Test
    fun themeWithNoPictureAtAll() {
        val t = Theme()
        t.element("theme"); t.element("icontable")
        assertEquals(P3tTheme.Failure.NO_PICTURE, read(t.bytes()).failure)
    }

    @Test
    fun otherFilesAreNotThemes() {
        assertEquals(P3tTheme.Failure.NOT_A_THEME, read(jpeg(10, 10) + ByteArray(100)).failure)
        assertEquals(P3tTheme.Failure.NOT_A_THEME, read(ByteArray(3)).failure)
    }

    @Test
    fun truncatedThemeIsDamaged() {
        val t = Theme(); t.element("bgimage", t.fileAttr("hd", jpeg(1920, 1080)))
        assertEquals(P3tTheme.Failure.DAMAGED, read(t.bytes().copyOf(40)).failure)
        // Cut inside the file table: the tables no longer fit.
        val whole = t.bytes()
        assertEquals(P3tTheme.Failure.DAMAGED, read(whole.copyOf(whole.size - 5)).failure)
    }

    @Test
    fun tablePointingPastTheEndIsDamaged() {
        val t = Theme(); t.element("bgimage", t.fileAttr("hd", jpeg(1920, 1080)))
        assertEquals(P3tTheme.Failure.DAMAGED, read(t.bytes(stringOffsetOverride = 0x7FFFFFF0)).failure)
    }

    @Test
    fun absurdAttributeCountIsDamaged() {
        val t = Theme(); t.element("bgimage", count = 0x7FFFFFFF)
        assertEquals(P3tTheme.Failure.DAMAGED, read(t.bytes()).failure)
    }

    @Test
    fun pictureReferenceOutsideTheFileIsSkipped() {
        val t = Theme()
        t.element("bgimage", Theme.Attr("hd", 6, 0x7FFF0000, 1000))
        assertEquals(P3tTheme.Failure.NO_PICTURE, read(t.bytes()).failure)
    }

    @Test
    fun previewThatInflatesWithoutLimitIsRefused() {
        val bomb = deflate(ByteArray(80 shl 20))   // 80 MB of zeros, a few KB packed
        val t = Theme()
        t.element("bgimage", t.fileAttr("anim", "_RAF".toByteArray() + ByteArray(8)))
        t.element("info", t.fileAttr("preview", bomb))
        assertEquals(P3tTheme.Failure.DYNAMIC_ONLY, read(t.bytes()).failure)
    }

    /** A real theme, when one is supplied: P3T_SAMPLE=/path/to/theme.p3t */
    @Test
    fun realThemeFromDisk() {
        val path = System.getenv("P3T_SAMPLE")
        assumeTrue(path != null && File(path).isFile)
        val r = read(File(path!!).readBytes())
        assertNull(r.failure)
        assertNotNull(r.picture)
        println("P3T_SAMPLE: dynamic=${r.dynamic} source=${r.picture!!.source} ${r.picture!!.width}x${r.picture!!.height}")
    }
}
