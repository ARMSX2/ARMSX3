package com.armsx2.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class P3tAnimationTest {

    /** Writes a RAF scene: the P3T table layout, with parent links, an id table and floats. */
    private class SceneBuilder {
        private val names = ByteArrayOutputStream()
        private val nameAt = HashMap<String, Int>()
        private val ids = ByteArrayOutputStream()
        private val idAt = HashMap<String, Int>()
        private val floats = ByteArrayOutputStream()
        private val files = ByteArrayOutputStream()
        private class Element(val name: Int, val parent: Int, val attrs: List<IntArray>)
        private val elements = ArrayList<Element>()

        fun name(s: String): Int = nameAt.getOrPut(s) {
            names.size().also { names.write(s.toByteArray()); names.write(0) }
        }

        fun id(s: String): Int = idAt.getOrPut(s) {
            ids.size().also { ids.write(ByteArray(4)); ids.write(s.toByteArray()); ids.write(0) }
        }

        fun own(s: String) = intArrayOf(name("id"), 7, id(s), 0)
        fun ref(key: String, s: String) = intArrayOf(name(key), 8, id(s), 0)
        fun int(key: String, v: Int) = intArrayOf(name(key), 1, v, 0)

        fun vector(key: String, vararg v: Float): IntArray {
            val at = floats.size() / 4
            v.forEach { floats.write(ByteBuffer.allocate(4).putFloat(it).array()) }
            return intArrayOf(name(key), 5, at, v.size)
        }

        fun file(key: String, bytes: ByteArray): IntArray {
            val at = files.size()
            files.write(bytes)
            return intArrayOf(name(key), 6, at, bytes.size)
        }

        /** Adds an element and returns its index, for children to name as their parent. */
        fun element(tag: String, parent: Int, vararg attrs: IntArray): Int {
            elements += Element(name(tag), parent, attrs.toList())
            return elements.size - 1
        }

        /**
         * An actor drawing [gtf], with the material, texture and file entries it goes through, and
         * when there is a [mesh], the model, geometry and file entries for that.
         */
        fun layer(scene: Int, root: Int, name: String, gtf: ByteArray, z: Float = -8f, mesh: ByteArray? = null) {
            val attrs = arrayListOf(own(name), ref("material", "mtrl_$name"), vector("position", 15f, 9f, z))
            if (mesh != null) {
                attrs += ref("model", "mdl_$name")
                val model = element("model", root, own("mdl_$name"))
                element("geometry", model, ref("fileref", "$name.edge"))
                element("file", root, own("$name.edge"), file("src", mesh), int("type", 3))
            }
            element("actor", scene, *attrs.toTypedArray())
            val material = element("material", root, own("mtrl_$name"))
            element("_texture", material, ref("texref", "_$name.gtf"))
            element("texture", root, own("_$name.gtf"), ref("fileref", "$name.gtf"))
            element("file", root, own("$name.gtf"), file("src", gtf), int("type", 4))
        }

        fun script(root: Int, code: ByteArray) {
            element("script", root, ref("fileref", "show.jsx"))
            element("file", root, own("show.jsx"), file("src", code), int("type", 5))
        }

        fun bytes(elementCountOverride: Int? = null, filesOffsetOverride: Int? = null): ByteArray {
            val offsets = IntArray(elements.size)
            var size = 0
            elements.forEachIndexed { i, e -> offsets[i] = size; size += 28 + 16 * e.attrs.size }
            val el = ByteBuffer.allocate(size)
            elements.forEachIndexed { i, e ->
                el.putInt(e.name).putInt(if (i == 0 && elementCountOverride != null) elementCountOverride else e.attrs.size)
                    .putInt(if (e.parent < 0) -1 else offsets[e.parent]).putInt(-1).putInt(-1).putInt(-1).putInt(-1)
                for (a in e.attrs) el.putInt(a[0]).putInt(a[1]).putInt(a[2]).putInt(a[3])
            }
            val tables = listOf(el.array(), ids.toByteArray(), names.toByteArray(), ByteArray(0), floats.toByteArray(), files.toByteArray())
            val out = ByteBuffer.allocate(56 + tables.sumOf { it.size })
            out.put("RAFO".toByteArray()).putInt(0x110)
            var at = 56
            tables.forEachIndexed { i, t ->
                out.putInt(if (i == 5 && filesOffsetOverride != null) filesOffsetOverride else at).putInt(t.size)
                at += t.size
            }
            tables.forEach { out.put(it) }
            return out.array()
        }
    }

    /** A GTF file holding one texture of [width] x [height] in [format], every block [block]. */
    private fun gtf(format: Int, width: Int, height: Int, block: ByteArray): ByteArray {
        val wide = (width + 3) / 4
        val high = (height + 3) / 4
        val data = ByteArray(wide * high * block.size)
        for (i in 0 until wide * high) block.copyInto(data, i * block.size)
        val b = ByteBuffer.allocate(0x80 + data.size)
        b.putInt(0x020101FF).putInt(data.size).putInt(1)            // version, size, texture count
        b.putInt(0).putInt(0x80).putInt(data.size)                  // id, offset, size
        b.put(format.toByte()).put(1).put(2).put(0).putInt(0xAAE4)  // format, mips, 2D, not a cube, remap
        b.putShort(width.toShort()).putShort(height.toShort()).putShort(1).put(0).put(0)
        b.putInt(wide * block.size).putInt(0)                       // pitch, offset
        b.position(0x80)
        b.put(data)
        return b.array()
    }

    private fun dxt1(c0: Int, c1: Int, indices: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(c0.toShort()).putShort(c1.toShort()).putInt(indices).array()

    private fun dxt5(a0: Int, a1: Int, alphaBits: Long, color: ByteArray): ByteArray = ByteArray(16).also { b ->
        b[0] = a0.toByte()
        b[1] = a1.toByte()
        for (i in 0 until 6) b[2 + i] = (alphaBits ushr (8 * i)).toByte()
        color.copyInto(b, 8)
    }

    private val red = 0xF800
    private val blue = 0x001F
    private val white = 0xFFFF

    private fun texture(format: Int, width: Int, height: Int, block: Int) =
        P3tAnimation.Texture(format, width, height, (width + 3) / 4 * block, 0, 0)

    /** Builds a script for [timing]'s pattern matcher: named globals, properties, instructions. */
    private class Ops(private val names: List<String>) {
        val list = ArrayList<Pair<Int, Int>>()
        fun g(n: String) { list += 0x2E to names.indexOf(n).also { require(it >= 0) { n } } }
        fun f(v: Float) { list += 0x27 to v.toRawBits() }
        fun i(v: Int) { list += 0x26 to v }
        fun op(o: Int, v: Int = 0) { list += o to v }
        fun assign() { op(0x01); op(0x22) }
        fun function(name: String, body: Ops.() -> Unit) {
            g(name)
            val fn = list.size
            op(0x100002A)
            assign()
            val jump = list.size
            op(0x39)
            val start = list.size
            body()
            list[fn] = 0x100002A to start
            list[jump] = 0x39 to list.size
        }
        fun call(receiver: String, method: Int, first: Ops.() -> Unit, time: Ops.() -> Unit, interpolation: String) {
            g(receiver); op(0x30, method); first(); time(); g(interpolation); op(0x3D, 3); op(0x22)
        }
        fun timer(every: Ops.() -> Unit, callback: String) {
            g("IntervalTimer"); every(); g(callback); op(0x3E, 2); op(0x22)
        }
    }

    private fun vsmx(names: List<String>, props: List<String>, ops: List<Pair<Int, Int>>): ByteArray {
        val code = ByteBuffer.allocate(ops.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        ops.forEach { code.putInt(it.first).putInt(it.second) }
        val propBytes = props.joinToString("") { it + "\u0000" }.toByteArray(Charsets.UTF_16LE)
        val nameBytes = names.joinToString("") { it + "\u0000" }.toByteArray(Charsets.ISO_8859_1)
        val codeAt = 52
        val propAt = codeAt + ops.size * 8
        val nameAt = propAt + propBytes.size
        val b = ByteBuffer.allocate(nameAt + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("VSMX".toByteArray()).putInt(0x20000).putInt(codeAt).putInt(ops.size * 8)
        b.putInt(propAt).putInt(0).putInt(0)                           // no text
        b.putInt(propAt).putInt(propBytes.size).putInt(props.size)
        b.putInt(nameAt).putInt(nameBytes.size).putInt(names.size)
        b.put(code.array()).put(propBytes).put(nameBytes)
        return b.array()
    }

    /** The PS4 On PS3 template: `duration = 10; fade = 1.5;` and a timer fading the slides. */
    private fun slideshowScript(): ByteArray {
        val names = listOf("duration", "fade", "change_bg", "bg", "color", "INTERPOLATION_LINEAR", "IntervalTimer")
        val o = Ops(names)
        o.g("duration"); o.f(10f); o.assign()
        o.g("fade"); o.f(1.5f); o.assign()
        o.function("change_bg") { call("bg", 0, { g("color") }, { g("fade") }, "INTERPOLATION_LINEAR") }
        o.timer({ g("duration") }, "change_bg")
        return vsmx(names, listOf("setColor"), o.list)
    }

    /** The JUJU7U template: integer timings, plus a camera moving between z 45 and 48. */
    private fun zoomScript(): ByteArray {
        val names = listOf(
            "camera", "Camera", "camera_start", "camera_end", "duration4", "fade4", "changeAnim4", "camera_zoom",
            "slide", "color", "INTERPOLATION_LINEAR", "INTERPOLATION_BEZIER", "IntervalTimer",
        )
        val o = Ops(names)
        o.g("camera"); o.g("Camera"); o.op(0x28, 0); o.op(0x3E, 1); o.assign()
        o.g("camera_start"); o.f(15.5f); o.f(9.5f); o.i(45); o.op(0x49, 3); o.assign()
        o.g("camera_end"); o.f(15.5f); o.f(9.5f); o.i(48); o.op(0x49, 3); o.assign()
        o.g("duration4"); o.i(7); o.assign()
        o.g("fade4"); o.i(1); o.assign()
        o.function("changeAnim4") { call("slide", 0, { g("color") }, { g("fade4") }, "INTERPOLATION_LINEAR") }
        o.function("camera_zoom") {
            call("camera", 1, { g("camera_end") }, { f(4f) }, "INTERPOLATION_BEZIER")
            call("camera", 1, { g("camera_start") }, { f(4f) }, "INTERPOLATION_BEZIER")
        }
        o.timer({ g("duration4") }, "changeAnim4")
        o.timer({ f(5f) }, "camera_zoom")
        return vsmx(names, listOf("setColor", "setPosition"), o.list)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun raf(scene: ByteArray): ByteArray =
        "_RAF".toByteArray() + ByteBuffer.allocate(4).putInt(scene.size).array() + deflate(scene)

    private fun bytes(b: ByteArray) = P3tTheme.ArrayBytes(b)

    // ---- decoding ----

    @Test
    fun dxt1FourColourBlock() {
        // Pixels 0-3 use indices 0-3; the rest index 0.
        val block = dxt1(red, blue, 0 or (1 shl 2) or (2 shl 4) or (3 shl 6))
        val px = P3tAnimation.decodeBlocks(block, texture(0xA6, 4, 4, 8))!!
        assertEquals(0xFFFF0000.toInt(), px[0])
        assertEquals(0xFF0000FF.toInt(), px[1])
        assertEquals(0xFFAA0055.toInt(), px[2]) // two thirds red
        assertEquals(0xFF5500AA.toInt(), px[3]) // two thirds blue
        assertEquals(0xFFFF0000.toInt(), px[15])
    }

    @Test
    fun dxt1ThreeColourBlockHasASeeThroughFourth() {
        val block = dxt1(blue, red, (2 shl 0) or (3 shl 2))
        val px = P3tAnimation.decodeBlocks(block, texture(0x86, 4, 4, 8))!!
        assertEquals(0xFF7F007F.toInt(), px[0]) // halfway
        assertEquals(0, px[1])                   // see-through black
        assertEquals(0xFF0000FF.toInt(), px[2])
    }

    @Test
    fun dxt5AlphaBothModes() {
        val eight = dxt5(255, 0, 0L or (1L shl 3) or (2L shl 6) or (7L shl 9), dxt1(white, 0, 0))
        val px = P3tAnimation.decodeBlocks(eight, texture(0xA8, 4, 4, 16))!!
        assertEquals(listOf(255, 0, 218, 36), (0..3).map { px[it] ushr 24 })
        assertEquals(0xFFFFFF, px[0] and 0xFFFFFF)

        val six = dxt5(0, 255, 2L or (6L shl 3) or (7L shl 6), dxt1(white, 0, 0))
        val px6 = P3tAnimation.decodeBlocks(six, texture(0xA8, 4, 4, 16))!!
        assertEquals(listOf(51, 0, 255), (0..2).map { px6[it] ushr 24 })
    }

    @Test
    fun dxt3AlphaIsStoredPerPixel() {
        val block = ByteArray(16) { 0xFF.toByte() }
        block[0] = 0x0F        // pixel 0: 15, pixel 1: 0
        block[1] = 0xF8.toByte() // pixel 2: 8, pixel 3: 15
        dxt1(white, 0, 0).copyInto(block, 8)
        val px = P3tAnimation.decodeBlocks(block, texture(0x87, 4, 4, 16))!!
        assertEquals(listOf(255, 0, 136, 255, 255), (0..4).map { px[it] ushr 24 })
    }

    @Test
    fun edgeBlocksAreCroppedToTheTexture() {
        // 6 x 5 is two blocks each way: red, blue over white, red.
        val data = dxt1(red, red, 0) + dxt1(blue, blue, 0) + dxt1(white, white, 0) + dxt1(red, red, 0)
        val px = P3tAnimation.decodeBlocks(data, P3tAnimation.Texture(0xA6, 6, 5, 16, 0, data.size))!!
        assertEquals(30, px.size)
        assertEquals(0xFF0000FF.toInt(), px[5])          // (5, 0): second block
        assertEquals(0xFFFFFFFF.toInt(), px[4 * 6])      // (0, 4): third block
        assertEquals(0xFFFF0000.toInt(), px[4 * 6 + 5])  // (5, 4): fourth block
    }

    @Test
    fun shortDataIsNotDecoded() {
        assertNull(P3tAnimation.decodeBlocks(ByteArray(7), texture(0xA6, 4, 4, 8)))
    }

    @Test
    fun clearShareCountsSeeThroughPixels() {
        assertEquals(0f, P3tAnimation.clearShare(IntArray(10) { -1 }), 0f)
        assertEquals(0.5f, P3tAnimation.clearShare(IntArray(4) { if (it < 2) 0x80FFFFFF.toInt() else -1 }), 0f)
    }

    @Test
    fun dxt1IsAlwaysASlideAlphaFormatsGoByHowClearTheyAre() {
        // JUJU7U's slides store black as DXT1's see-through colour: up to 4% of a slide.
        val someBlack = IntArray(100) { if (it < 4) 0 else -1 }
        assertTrue(P3tAnimation.isSlide(texture(0xA6, 4, 4, 8), someBlack))
        assertFalse(P3tAnimation.isSlide(texture(0xA8, 4, 4, 16), someBlack))
        assertTrue(P3tAnimation.isSlide(texture(0xA8, 4, 4, 16), IntArray(100) { -1 }))
    }

    // ---- unpacking ----

    @Test
    fun unpackRoundTrips() {
        val scene = ByteArray(300_000) { (it % 251).toByte() }
        val packed = raf(scene)
        val out = ByteArrayOutputStream()
        assertTrue(P3tAnimation.unpack(bytes(packed), 0, packed.size.toLong(), out, 1L shl 20))
        assertArrayEquals(scene, out.toByteArray())
    }

    @Test
    fun unpackRefusesWhatIsNotAScene() {
        val packed = raf(ByteArray(1000))
        val notRaf = packed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFalse(P3tAnimation.unpack(bytes(notRaf), 0, notRaf.size.toLong(), ByteArrayOutputStream(), 1L shl 20))
        // Cut short: the stream never finishes.
        assertFalse(P3tAnimation.unpack(bytes(packed), 0, packed.size - 4L, ByteArrayOutputStream(), 1L shl 20))
        // Bigger than allowed.
        assertFalse(P3tAnimation.unpack(bytes(packed), 0, packed.size.toLong(), ByteArrayOutputStream(), 100))
        // Pointing past the end.
        assertFalse(P3tAnimation.unpack(bytes(packed), 10, packed.size.toLong(), ByteArrayOutputStream(), 1L shl 20))
    }

    // ---- the scene ----

    private val screenW = 960
    private val screenH = 540

    private fun slideshowScene(script: ByteArray? = null): SceneBuilder {
        val s = SceneBuilder()
        val root = s.element("raf", -1)
        val scene = s.element("scene", root)
        s.layer(scene, root, "bg01", gtf(0xA6, screenW, screenH, dxt1(red, red, 0)))
        s.layer(scene, root, "badge", gtf(0xA8, 256, 64, dxt5(255, 255, 0, dxt1(white, 0, 0))))   // not full-screen
        s.layer(scene, root, "wave", gtf(0xA8, screenW, screenH, dxt5(128, 128, 0, dxt1(white, 0, 0))))
        s.layer(scene, root, "bg02", gtf(0xA6, screenW, screenH, dxt1(blue, blue, 0)))
        s.layer(scene, root, "odd", gtf(0x85, screenW, screenH, ByteArray(16)))                     // ARGB: not decoded here
        if (script != null) s.script(root, script)
        return s
    }

    @Test
    fun sceneListsFullScreenLayersInDrawOrder() {
        val scene = P3tAnimation.scene(bytes(slideshowScene().bytes()))!!
        assertEquals(listOf("bg01", "wave", "bg02"), scene.layers.map { it.name })
        assertEquals(screenW, scene.layers[0].texture.width)
        assertEquals(-8f, scene.layers[0].z)
        val raf = slideshowScene().bytes()
        val first = P3tAnimation.scene(bytes(raf))!!.layers
        assertEquals(0xFFFF0000.toInt(), P3tAnimation.decode(bytes(raf), first[0].texture)!![0])
        assertEquals(128, P3tAnimation.decode(bytes(raf), first[1].texture)!![0] ushr 24)
        // No script: the templates' usual timing.
        assertEquals(10f, scene.timing.interval, 0f)
        assertEquals(1.5f, scene.timing.fade, 0f)
        assertEquals(0f, scene.timing.zoom, 0f)
    }

    @Test
    fun timingComesFromTheScript() {
        val timing = P3tAnimation.scene(bytes(slideshowScene(slideshowScript()).bytes()))!!.timing
        assertEquals(10f, timing.interval, 0f)
        assertEquals(1.5f, timing.fade, 0f)
        assertEquals(0f, timing.zoom, 0f)
    }

    @Test
    fun zoomComesFromTheCameraTimer() {
        val timing = P3tAnimation.timing(zoomScript(), planeZ = -7.995f)
        assertEquals(7f, timing.interval, 0f)
        assertEquals(1f, timing.fade, 0f)
        assertEquals(55.995f / 52.995f - 1f, timing.zoom, 1e-4f)
        assertEquals(5f, timing.zoomInterval, 0f)
        assertEquals(4f, timing.zoomMove, 0f)
    }

    @Test
    fun alphaSlideshowTemplateFadesWithoutAnInterpolation() {
        // Persona 5 Slideshow's AlphaSlideshow.jsx: `duration = 8; fadeduration = 2.3;` and a timer
        // whose callback calls slide.setColor(first, fadeduration) with just two arguments.
        val names = listOf("duration", "fadeduration", "bgchange", "slide", "first", "IntervalTimer")
        val o = Ops(names)
        o.g("duration"); o.f(8f); o.assign()
        o.g("fadeduration"); o.f(2.3f); o.assign()
        o.function("bgchange") { g("slide"); op(0x30, 0); g("first"); g("fadeduration"); op(0x3D, 2); op(0x22) }
        o.timer({ g("duration") }, "bgchange")
        val timing = P3tAnimation.timing(vsmx(names, listOf("setColor"), o.list), -8f)
        assertEquals(8f, timing.interval, 0f)
        assertEquals(2.3f, timing.fade, 1e-6f)
        assertEquals(0f, timing.zoom, 0f)
    }

    @Test
    fun unreadableScriptsKeepTheDefaults() {
        for (script in listOf(null, ByteArray(0), ByteArray(64) { 0x55 }, slideshowScript().copyOf(60))) {
            val timing = P3tAnimation.timing(script, -8f)
            assertEquals(10f, timing.interval, 0f)
            assertEquals(1.5f, timing.fade, 0f)
            assertEquals(0f, timing.zoom, 0f)
        }
    }

    @Test
    fun sceneWithoutFullScreenLayersIsNoSlideshow() {
        val s = SceneBuilder()
        val root = s.element("raf", -1)
        val scene = s.element("scene", root)
        s.layer(scene, root, "badge", gtf(0xA8, 256, 64, dxt5(255, 255, 0, dxt1(white, 0, 0))))
        assertNull(P3tAnimation.scene(bytes(s.bytes())))
    }

    @Test
    fun damagedScenesAreRefused() {
        val good = slideshowScene().bytes()
        assertNull(P3tAnimation.scene(bytes(good.copyOf(40))))
        assertNull(P3tAnimation.scene(bytes(good.copyOf().also { it[0] = 'X'.code.toByte() })))
        assertNull(P3tAnimation.scene(bytes(slideshowScene().bytes(elementCountOverride = 0x7FFFFFFF))))
        assertNull(P3tAnimation.scene(bytes(slideshowScene().bytes(filesOffsetOverride = 0x7FFFFFF0))))
    }

    @Test
    fun layersHiddenByACollapsedMeshAreLeftOut() {
        // Fallout NV Custom Dynamic hides the template's rocks by zeroing their meshes; their
        // textures are still full-screen pictures.
        val s = SceneBuilder()
        val root = s.element("raf", -1)
        val scene = s.element("scene", root)
        s.layer(scene, root, "bg01", gtf(0xA6, screenW, screenH, dxt1(red, red, 0)), mesh = EdgeSamples.smokeSheet)
        s.layer(scene, root, "rocks", gtf(0xA6, screenW, screenH, dxt1(blue, blue, 0)), mesh = EdgeSamples.collapsed)
        s.layer(scene, root, "bg02", gtf(0xA6, screenW, screenH, dxt1(white, white, 0)))
        assertEquals(listOf("bg01", "bg02"), P3tAnimation.scene(bytes(s.bytes()))!!.layers.map { it.name })
    }

    @Test
    fun layerWhosePixelsAreCutOffIsDropped() {
        val s = SceneBuilder()
        val root = s.element("raf", -1)
        val scene = s.element("scene", root)
        s.layer(scene, root, "bg01", gtf(0xA6, screenW, screenH, dxt1(red, red, 0)))
        s.layer(scene, root, "bg02", gtf(0xA6, screenW, screenH, dxt1(blue, blue, 0)))
        val whole = s.bytes()
        // Shorten the file table, which ends with bg02's pixels: it must be dropped, not read past the end.
        val cut = whole.copyOf(whole.size - 1000)
        ByteBuffer.wrap(cut).apply { putInt(52, getInt(52) - 1000) }
        assertEquals(listOf("bg01"), P3tAnimation.scene(bytes(cut))!!.layers.map { it.name })
    }

    // ---- real themes ----

    /** Real dynamic themes, when given: P3T_DYNAMIC=/path/a.p3t,/path/b.p3t */
    @Test
    fun realDynamicThemes() {
        val paths = System.getenv("P3T_DYNAMIC")?.split(',')?.filter { File(it).isFile }.orEmpty()
        assumeTrue(paths.isNotEmpty())
        for (path in paths) {
            val theme = bytes(File(path).readBytes())
            val anim = P3tTheme.read(theme).anim
            assertNotNull(anim)
            val out = ByteArrayOutputStream()
            assertTrue(P3tAnimation.unpack(theme, anim!!.offset, anim.size, out, 256L shl 20))
            val raf = bytes(out.toByteArray())
            val scene = P3tAnimation.scene(raf)
            assertNotNull(scene)
            val t = scene!!.timing
            println("P3T_DYNAMIC ${File(path).name}: ${scene.layers.size} layers, every ${t.interval}s, fade ${t.fade}s, zoom ${t.zoom} every ${t.zoomInterval}s over ${t.zoomMove}s")
            var slides = 0
            for (layer in scene.layers) {
                val px = P3tAnimation.decode(raf, layer.texture)
                assertNotNull(layer.name, px)
                val slide = P3tAnimation.isSlide(layer.texture, px!!)
                if (slide) slides++
                println("  ${layer.name} ${layer.texture.width}x${layer.texture.height} z=${layer.z} clear=${"%.4f".format(P3tAnimation.clearShare(px))} ${if (slide) "slide" else "over"}")
            }
            // Known samples: slides, then (interval, fade) from their scripts. Fallout NV is a scene
            // theme (Sony's Prince of Persia Trilogy template): one slide, with the template's other
            // full-screen layers collapsed away; its smoke is played live (see ThemeSceneTest).
            val known = listOf(
                Triple("PS4_On_PS3", 8, 10f to 1.5f),
                Triple("JUJU7U", 12, 7f to 1f),
                Triple("Persona 5", 8, 8f to 2.3f),
                Triple("Fallout NV", 1, 10f to 1.5f),
            ).firstOrNull { path.contains(it.first) }
            if (known != null) {
                assertEquals(known.second, slides)
                assertEquals(known.third.first, t.interval, 1e-4f)
                assertEquals(known.third.second, t.fade, 1e-4f)
            } else {
                assertTrue(slides >= 1)
            }
        }
    }
}
