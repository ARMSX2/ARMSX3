package com.armsx2.ui.home

import java.io.IOException
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads a dynamic PS3 theme's animated background far enough to play it as a slideshow.
 *
 * A dynamic theme's bgimage "anim" (see [P3tTheme]) is "_RAF", a u32, then a zlib stream that
 * unpacks to a RAF scene, magic "RAFO". The scene is laid out like the theme itself: a header of
 * six (offset, size) tables, then elements with 16-byte typed attributes. In a scene, the id
 * table's entries are a u32 element offset followed by the name; attributes of type 7 (an
 * element's own id) and 8 (a reference to one) hold an offset into that table; type 5 is an
 * (index, count) run in the float table; type 6 an (offset, size) in the file table.
 *
 * What a slideshow needs out of it:
 *  - scene/actor: a drawn layer, in draw order. Its "material" names a material whose "_texture"
 *    child names a texture, whose "fileref" names a file: a PS3 GPU texture (.gtf).
 *  - script: compiled JavaScript (VSMX) that animates the actors. See [timing].
 *
 * The dynamic themes people share are mostly built from slideshow templates: every actor is a flat
 * quad, the full-screen ones on one plane. Opaque ones are slides, which the script fades between
 * on a timer; see-through ones are drawn over them. That is what this reads: the full-screen
 * layers, where their pixels are, and the timing. A theme with real 3D content has at most one
 * full-screen layer, so it gets no slideshow; [RafScene] and [ThemeScene] play it instead.
 *
 * All of it is user input and read as hostile, the same way as [P3tTheme]: every offset and size
 * is checked against its table before use, in 64-bit arithmetic, and sizes are capped.
 */
object P3tAnimation {
    /**
     * A texture in the scene: its GCM format byte, and its pixel data at [offset] in the scene. Or,
     * with [format] [PICTURE], a whole JPEG or PNG file there, which some themes use instead of a
     * GTF (the anonymous theme's "mask.jpg"); the platform decodes those (see [isPicture]).
     */
    class Texture(val format: Int, val width: Int, val height: Int, val pitch: Int, val offset: Long, val size: Int)

    /** [Texture.format] of a texture that is a JPEG or PNG file. */
    const val PICTURE = 0x1000

    /** A full-screen layer, in draw order. [z] is its depth, for the zoom below, when it has one. */
    class Layer(val name: String, val texture: Texture, val z: Float?)

    /**
     * How the show moves, in seconds. [interval] runs from one change to the next and includes the
     * [fade]. When [zoom] is above zero the camera breathes: every [zoomInterval] it spends
     * [zoomMove] moving between two distances, which scales the picture between 1 and 1 + [zoom].
     */
    class Timing(val interval: Float, val fade: Float, val zoom: Float, val zoomInterval: Float, val zoomMove: Float)

    class Scene(val layers: List<Layer>, val timing: Timing)

    /** What the slideshow templates do, for a script with nothing readable in it. */
    val DEFAULT_TIMING = Timing(interval = 10f, fade = 1.5f, zoom = 0f, zoomInterval = 0f, zoomMove = 0f)

    private const val RAF_HEADER_BYTES = 8L
    private const val RAFO = 0x5241464FL // "RAFO"
    private const val HEADER_BYTES = 56L
    private const val ELEMENT_BYTES = 28L
    private const val ATTRIBUTE_BYTES = 16L
    private const val TYPE_VECTOR = 5L
    private const val TYPE_FILE = 6L
    private const val TYPE_ID = 7L
    private const val TYPE_REF = 8L
    private const val GTF_HEADER_BYTES = 48

    // GCM texture formats, with the linear (0x20) and unnormalised (0x40) flags taken off.
    private const val FORMAT_FLAGS = 0x60
    private const val DXT1 = 0x86
    private const val DXT3 = 0x87
    private const val DXT5 = 0x88

    private const val MAX_TABLE_BYTES = 16 shl 20
    private const val MAX_TEXTURE_BYTES = 16 shl 20
    private const val MAX_SCRIPT_BYTES = 1 shl 20
    private const val MAX_MESH_BYTES = 4L shl 20
    private const val MAX_SIDE = 4096
    private const val MAX_NAME_BYTES = 256
    private const val MAX_VECTOR = 16
    private const val CHUNK_BYTES = 256 shl 10

    // A full-screen layer: at least this wide, and between 3:2 and 2:1.
    private const val MIN_SCREEN_WIDTH = 960
    private const val MIN_ASPECT = 1.5f
    private const val MAX_ASPECT = 2.0f

    /**
     * Unpack the "anim" entry at [offset], [size] bytes long, into [sink]. False when it is not a
     * RAF, is damaged, or would unpack to more than [cap] bytes.
     */
    fun unpack(src: P3tTheme.Bytes, offset: Long, size: Long, sink: OutputStream, cap: Long): Boolean {
        if (offset < 0 || size < RAF_HEADER_BYTES || offset > src.size || size > src.size - offset) return false
        if (String(src.read(offset, 4), Charsets.ISO_8859_1) != "_RAF") return false
        val inflater = Inflater()
        try {
            val output = ByteArray(64 shl 10)
            val end = offset + size
            var at = offset + RAF_HEADER_BYTES
            var written = 0L
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (at >= end) return false // the stream stops short
                    val chunk = src.read(at, minOf(end - at, CHUNK_BYTES.toLong()).toInt())
                    at += chunk.size
                    inflater.setInput(chunk)
                }
                val n = inflater.inflate(output)
                if (n == 0 && inflater.needsDictionary()) return false
                written += n
                if (written > cap) return false
                sink.write(output, 0, n)
            }
            return written > 0
        } catch (_: DataFormatException) {
            return false
        } catch (_: IOException) {
            return false
        } finally {
            inflater.end()
        }
    }

    /** The scene's full-screen layers and timing, or null when there is no slideshow in it. */
    fun scene(raf: P3tTheme.Bytes): Scene? = try {
        parse(raf)
    } catch (_: Damaged) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // The backstop for a check that missed, as in P3tTheme: no slideshow, never a crash.
        null
    }

    /** Anything structurally wrong with the scene. Thrown by every bounds check. */
    private class Damaged : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Attr(val type: Long, val a: Long, val b: Long)

    private fun parse(raf: P3tTheme.Bytes): Scene? {
        if (raf.size < HEADER_BYTES) return null
        val header = raf.read(0, HEADER_BYTES.toInt())
        if (be32(header, 0) != RAFO) return null
        val tables = List(6) { i ->
            val offset = be32(header, 8 + i * 8)
            val size = be32(header, 12 + i * 8)
            if (offset > raf.size || size > raf.size - offset) throw Damaged()
            offset to size
        }
        fun table(i: Int): ByteArray {
            val (offset, size) = tables[i]
            if (size > MAX_TABLE_BYTES) throw Damaged()
            return raf.read(offset, size.toInt())
        }
        val elements = table(0)
        val ids = table(1)
        val names = table(2)
        val floats = table(4)
        val (filesAt, filesSize) = tables[5]

        // Everything is joined by id, an offset into the id table: actor -> material -> texture -> file,
        // and actor -> model -> geometry -> file.
        class Actor(val id: Long, val material: Long, val model: Long?, val z: Float?)
        val actors = ArrayList<Actor>()
        val materialAt = HashMap<Long, Long>()       // material element offset -> its id
        val materialTexture = HashMap<Long, Long>()
        val modelAt = HashMap<Long, Long>()          // model element offset -> its id
        val modelGeometry = HashMap<Long, Long>()
        val textureFile = HashMap<Long, Long>()
        val fileSpan = HashMap<Long, Attr>()
        var script: Long? = null

        var pos = 0L
        while (pos + ELEMENT_BYTES <= elements.size) {
            val name = cString(names, be32(elements, pos.toInt()))
            val count = be32(elements, pos.toInt() + 4)
            val parent = be32(elements, pos.toInt() + 8)
            if (count > (elements.size - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES) throw Damaged()
            val attrs = HashMap<String, Attr>()
            if (name in WANTED) for (i in 0L until count) {
                val at = (pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES).toInt()
                attrs[cString(names, be32(elements, at))] =
                    Attr(be32(elements, at + 4), be32(elements, at + 8), be32(elements, at + 12))
            }
            fun ref(key: String): Long? = attrs[key]?.takeIf { it.type == TYPE_ID || it.type == TYPE_REF }?.a
            when (name) {
                "actor" -> {
                    val id = ref("id")
                    val material = ref("material")
                    if (id != null && material != null) actors += Actor(id, material, ref("model"), vector(floats, attrs["position"])?.getOrNull(2))
                }
                "material" -> ref("id")?.let { materialAt[pos] = it }
                "model" -> ref("id")?.let { modelAt[pos] = it }
                "geometry" -> {
                    val model = modelAt[parent]
                    val file = ref("fileref")
                    if (model != null && file != null) modelGeometry[model] = file
                }
                "_texture" -> {
                    val material = materialAt[parent]
                    val texture = ref("texref")
                    if (material != null && texture != null) materialTexture[material] = texture
                }
                "texture" -> {
                    val id = ref("id")
                    val file = ref("fileref")
                    if (id != null && file != null) textureFile[id] = file
                }
                "file" -> {
                    val id = ref("id")
                    val src = attrs["src"]
                    if (id != null && src != null && src.type == TYPE_FILE) fileSpan[id] = src
                }
                "script" -> if (script == null) script = ref("fileref")
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        // (offset in the scene, size) of a file, or null when it points outside the file table.
        fun file(id: Long): Pair<Long, Long>? {
            val span = fileSpan[id] ?: return null
            if (span.a > filesSize || span.b > filesSize - span.a) return null
            return (filesAt + span.a) to span.b
        }
        // A mesh that draws nothing is how theme authors hide a layer they do not want: Fallout NV
        // Custom Dynamic keeps the Prince of Persia template's rocks that way (see RafScene.collapsed).
        fun hidden(actor: Actor): Boolean {
            val (offset, size) = actor.model?.let(modelGeometry::get)?.let(::file) ?: return false
            return size in 1..MAX_MESH_BYTES && RafScene.collapsed(raf.read(offset, size.toInt()))
        }
        val layers = ArrayList<Layer>()
        for (actor in actors) {
            if (hidden(actor)) continue
            val texture = materialTexture[actor.material]?.let(textureFile::get)?.let(::file)
                ?.let { (offset, size) -> gtf(raf, offset, size) } ?: continue
            val aspect = texture.width.toFloat() / texture.height
            if (texture.width < MIN_SCREEN_WIDTH || aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue
            layers += Layer(cString(ids, actor.id + 4), texture, actor.z)
        }
        if (layers.isEmpty()) return null
        val code = script?.let(::file)?.takeIf { it.second in 1..MAX_SCRIPT_BYTES }
            ?.let { (offset, size) -> raf.read(offset, size.toInt()) }
        return Scene(layers, timing(code, layers.first().z))
    }

    private val WANTED = setOf("actor", "material", "_texture", "texture", "file", "script", "model", "geometry")

    /** A type-5 attribute's floats, when it is one and fits the float table. */
    private fun vector(floats: ByteArray, attr: Attr?): FloatArray? {
        if (attr == null || attr.type != TYPE_VECTOR || attr.b !in 1..MAX_VECTOR) return null
        if (attr.a > floats.size / 4 || attr.b > floats.size / 4 - attr.a) return null
        return FloatArray(attr.b.toInt()) { Float.fromBits(be32(floats, ((attr.a + it) * 4).toInt()).toInt()) }
    }

    /**
     * The first texture in a GTF file, when it is a plain 2D one in a format decoded here. The header
     * is a version, a size and a texture count, then per texture its offset and size in the file and
     * a CellGcmTexture: format, mip count, dimension, cube flag, remap, width, height, depth,
     * location, pitch.
     */
    /** A scene's texture file: a GTF, or a JPEG or PNG picture. */
    internal fun texture(raf: P3tTheme.Bytes, offset: Long, size: Long): Texture? = gtf(raf, offset, size) ?: picture(raf, offset, size)

    fun isPicture(t: Texture): Boolean = t.format == PICTURE

    /** A JPEG or PNG texture file, with its size read from its header. */
    private fun picture(raf: P3tTheme.Bytes, offset: Long, size: Long): Texture? {
        if (size < 24 || size > MAX_TEXTURE_BYTES) return null
        val head = raf.read(offset, minOf(size, PICTURE_PROBE_BYTES).toInt())
        val png = head[0] == 0x89.toByte() && String(head, 1, 3, Charsets.ISO_8859_1) == "PNG"
        val dims = if (png) {
            be32(head, 16).toInt() to be32(head, 20).toInt()
        } else {
            P3tTheme.jpegSize(head) ?: (if (size > head.size) P3tTheme.jpegSize(raf.read(offset, size.toInt())) else null) ?: return null
        }
        if (dims.first !in 1..MAX_SIDE || dims.second !in 1..MAX_SIDE) return null
        return Texture(PICTURE, dims.first, dims.second, 0, offset, size.toInt())
    }

    private const val PICTURE_PROBE_BYTES = 64L shl 10

    internal fun gtf(raf: P3tTheme.Bytes, offset: Long, size: Long): Texture? {
        if (size < GTF_HEADER_BYTES) return null
        val h = raf.read(offset, GTF_HEADER_BYTES)
        if (be32(h, 8) < 1) return null
        val data = be32(h, 16)
        val length = be32(h, 20)
        val format = h[24].toInt() and 0xFF
        val dimension = h[26].toInt() and 0xFF
        val cube = h[27].toInt() and 0xFF
        val width = be16(h, 32)
        val height = be16(h, 34)
        val pitch = be32(h, 40)
        if (dimension != 2 || cube != 0 || width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        val block = blockBytes(format) ?: return null
        val row = ((width + 3) / 4).toLong() * block
        val rowPitch = if (pitch >= row) pitch else row
        val needed = rowPitch * ((height + 3) / 4 - 1) + row
        if (data > size || length > size - data || needed > length || needed > MAX_TEXTURE_BYTES) return null
        return Texture(format, width, height, rowPitch.toInt(), offset + data, needed.toInt())
    }

    /**
     * Which DXT a texture is, for drawing its blocks as they are (ThemeSceneRenderer decodes them
     * in the shader): 1, 3 or 5, or 0 for a format that is not block compressed.
     */
    internal fun dxt(t: Texture): Int = when (t.format and FORMAT_FLAGS.inv()) {
        DXT1 -> 1
        DXT3 -> 3
        DXT5 -> 5
        else -> 0
    }

    private fun blockBytes(format: Int): Int? = when (format and FORMAT_FLAGS.inv()) {
        DXT1 -> 8
        DXT3, DXT5 -> 16
        else -> null
    }

    /**
     * The texture's pixels in Android's ARGB order, or null when they cannot be read. DXT1/3/5 are
     * the S3TC block formats, stored on the PS3 in the same little-endian blocks as anywhere else.
     */
    fun decode(raf: P3tTheme.Bytes, t: Texture): IntArray? = try {
        decodeBlocks(raf.read(t.offset, t.size), t)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    internal fun decodeBlocks(data: ByteArray, t: Texture): IntArray? {
        if (isPicture(t)) return null // the platform's to decode
        val kind = t.format and FORMAT_FLAGS.inv()
        val block = blockBytes(t.format) ?: return null
        val wide = (t.width + 3) / 4
        val high = (t.height + 3) / 4
        if (data.size < t.pitch.toLong() * (high - 1) + wide.toLong() * block) return null
        val out = IntArray(t.width * t.height)
        val palette = IntArray(4)
        val alpha = IntArray(16)
        val colorAt = if (kind == DXT1) 0 else 8
        for (by in 0 until high) for (bx in 0 until wide) {
            val b = by * t.pitch + bx * block
            when (kind) {
                DXT3 -> for (k in 0 until 16) alpha[k] = ((data[b + k / 2].toInt() ushr ((k and 1) * 4)) and 0xF) * 17
                DXT5 -> {
                    val a0 = data[b].toInt() and 0xFF
                    val a1 = data[b + 1].toInt() and 0xFF
                    var bits = 0L
                    for (i in 0 until 6) bits = bits or ((data[b + 2 + i].toLong() and 0xFF) shl (8 * i))
                    for (k in 0 until 16) {
                        val i = ((bits ushr (3 * k)) and 7).toInt()
                        alpha[k] = when {
                            i == 0 -> a0
                            i == 1 -> a1
                            a0 > a1 -> ((8 - i) * a0 + (i - 1) * a1) / 7
                            i == 6 -> 0
                            i == 7 -> 255
                            else -> ((6 - i) * a0 + (i - 1) * a1) / 5
                        }
                    }
                }
            }
            colors(data, b + colorAt, kind == DXT1, palette)
            val indices = le32(data, b + colorAt + 4)
            for (k in 0 until 16) {
                val x = bx * 4 + (k and 3)
                val y = by * 4 + (k shr 2)
                if (x >= t.width || y >= t.height) continue
                val c = palette[((indices ushr (2 * k)) and 3).toInt()]
                out[y * t.width + x] = if (kind == DXT1) c else (c and 0xFFFFFF) or (alpha[k] shl 24)
            }
        }
        return out
    }

    /** A colour block's four colours. DXT1 with c0 <= c1 has three, and a see-through fourth. */
    private fun colors(data: ByteArray, at: Int, dxt1: Boolean, palette: IntArray) {
        val c0 = le16(data, at)
        val c1 = le16(data, at + 2)
        val r0 = expand5(c0 ushr 11); val g0 = expand6((c0 ushr 5) and 63); val b0 = expand5(c0 and 31)
        val r1 = expand5(c1 ushr 11); val g1 = expand6((c1 ushr 5) and 63); val b1 = expand5(c1 and 31)
        palette[0] = argb(r0, g0, b0)
        palette[1] = argb(r1, g1, b1)
        if (!dxt1 || c0 > c1) {
            palette[2] = argb((2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3)
            palette[3] = argb((r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3)
        } else {
            palette[2] = argb((r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
            palette[3] = 0
        }
    }

    private fun expand5(v: Int) = (v shl 3) or (v ushr 2)
    private fun expand6(v: Int) = (v shl 2) or (v ushr 4)
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Whether a layer is a slide rather than one drawn over the slides. DXT1 always is: its one-bit
     * alpha is how the templates' encoder stores pure black, up to 4% of a dark slide in the themes
     * seen so far. A format with real alpha is a slide when next to nothing in it is see-through.
     */
    fun isSlide(texture: Texture, argb: IntArray): Boolean =
        (texture.format and FORMAT_FLAGS.inv()) == DXT1 || clearShare(argb) <= MAX_SLIDE_CLEAR_SHARE

    private const val MAX_SLIDE_CLEAR_SHARE = 0.01f

    /** Share of [argb] that is see-through (alpha under 250). */
    fun clearShare(argb: IntArray): Float {
        if (argb.isEmpty()) return 1f
        var clear = 0
        for (p in argb) if ((p ushr 24) < 250) clear++
        return clear.toFloat() / argb.size
    }

    // ---- the script ----
    //
    // VSMX is compiled JavaScript: a header of little-endian words (magic, version, then offset and
    // size for the code, and offset, size and count for the text, property and name sections), then
    // 8-byte instructions (opcode, operand). Names are 8-bit strings, properties UTF-16; both are
    // NUL-separated and referred to by index. Only the handful of opcodes the slideshow templates
    // use to set up their timers is recognised; anything else just is not matched.

    private const val VSMX = 0x584D5356L // "VSMX", little-endian
    private const val VSMX_HEADER_BYTES = 52
    private const val MAX_OPS = 1 shl 16
    private const val MAX_STRINGS = 1 shl 12
    private const val OP_ASSIGN = 0x01
    private const val OP_NEGATE = 0x08
    private const val OP_END = 0x22
    private const val OP_INT = 0x26
    private const val OP_FLOAT = 0x27
    private const val OP_FUNCTION = 0x2A // low byte; the rest are flags
    private const val OP_GLOBAL = 0x2E
    private const val OP_METHOD = 0x30
    private const val OP_JUMP = 0x39
    private const val OP_CALL_METHOD = 0x3D
    private const val OP_NEW = 0x3E
    private const val OP_ARRAY = 0x49

    private class Script(val names: List<String>, val props: List<String>, val op: IntArray, val arg: IntArray)

    private fun readScript(b: ByteArray): Script? {
        if (b.size < VSMX_HEADER_BYTES || (le32(b, 0).toLong() and 0xFFFFFFFFL) != VSMX) return null
        val word = { i: Int -> le32(b, 4 * i).toLong() and 0xFFFFFFFFL }
        val codeAt = word(2)
        val codeSize = word(3)
        if (codeAt > b.size || codeSize > b.size - codeAt || codeSize / 8 > MAX_OPS) return null
        val props = strings(b, word(7), word(8), word(9), wide = true) ?: return null
        val names = strings(b, word(10), word(11), word(12), wide = false) ?: return null
        val count = (codeSize / 8).toInt()
        val op = IntArray(count) { le32(b, (codeAt + 8L * it).toInt()) }
        val arg = IntArray(count) { le32(b, (codeAt + 8L * it + 4).toInt()) }
        return Script(names, props, op, arg)
    }

    private fun strings(b: ByteArray, at: Long, size: Long, count: Long, wide: Boolean): List<String>? {
        if (at > b.size || size > b.size - at || count > MAX_STRINGS) return null
        val text = String(b, at.toInt(), size.toInt(), if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1)
        return text.split('\u0000').take(count.toInt())
    }

    /**
     * The slideshow's timing, out of the script that drives it. [planeZ] is the slides' depth.
     *
     * The templates set their numbers up front (`duration = 10;`, `fade = 1.5;`), then start an
     * `IntervalTimer(duration, change)` whose callback fades the slides with
     * `slide.setColor(color, fade, INTERPOLATION_LINEAR)`. The interval is the timer's, the fade the
     * time given to setColor. A template with a zoom runs a second timer whose callback moves the
     * camera between two positions with setPosition; how far apart they are, against how far the
     * slides are, says how much the picture grows. Anything not found keeps [DEFAULT_TIMING].
     */
    internal fun timing(script: ByteArray?, planeZ: Float?): Timing {
        val s = script?.let(::readScript) ?: return DEFAULT_TIMING
        val n = s.op.size
        fun op(i: Int) = if (i in 0 until n) s.op[i] else -1
        fun global(i: Int) = if (op(i) == OP_GLOBAL) s.arg[i] else -1
        fun name(i: Int) = s.names.getOrNull(global(i))
        // A number written in place: one instruction, or two for a negative one.
        fun constant(i: Int): Pair<Float, Int>? {
            val v = when (op(i)) {
                OP_INT -> s.arg[i].toFloat()
                OP_FLOAT -> Float.fromBits(s.arg[i])
                else -> return null
            }
            return if (op(i + 1) == OP_NEGATE) -v to 2 else v to 1
        }

        val numbers = HashMap<Int, Float>()        // global -> the number assigned to it
        val vectors = HashMap<Int, FloatArray>()   // global -> the array of numbers assigned to it
        val bodies = HashMap<Int, IntRange>()      // global -> the function assigned to it
        val cameras = HashSet<Int>()               // globals holding a new Camera(...)
        for (i in 0 until n) {
            val target = global(i)
            if (target < 0) continue
            constant(i + 1)?.let { (v, used) -> if (op(i + 1 + used) == OP_ASSIGN) numbers[target] = v }
            var j = i + 1
            val values = ArrayList<Float>()
            while (values.size < MAX_VECTOR) {
                val (v, used) = constant(j) ?: break
                values += v
                j += used
            }
            if (values.isNotEmpty() && op(j) == OP_ARRAY && s.arg[j] == values.size && op(j + 1) == OP_ASSIGN) {
                vectors[target] = values.toFloatArray()
            }
            // name = function () { ... }; the body runs from the function's operand to the jump past it.
            if ((op(i + 1) and 0xFF) == OP_FUNCTION && op(i + 2) == OP_ASSIGN && op(i + 3) == OP_END && op(i + 4) == OP_JUMP) {
                val start = s.arg[i + 1]
                val end = s.arg[i + 4]
                if (start in 0..end && end <= n) bodies[target] = start until end
            }
            if (name(i + 1) == "Camera") {
                val made = (i + 2..minOf(i + 6, n - 1)).firstOrNull { op(it) == OP_NEW }
                if (made != null && op(made + 1) == OP_ASSIGN) cameras += target
            }
        }

        // receiver.method(first, time, INTERPOLATION_...) or receiver.method(first, time) calls in a
        // function body: the time is the argument before the interpolation, or the last one. The
        // AlphaSlideshow template (Persona 5 Slideshow) passes no interpolation at all.
        class Call(val method: String, val receiver: Int, val first: Int, val time: Float?)
        fun number(i: Int): Float? = constant(i)?.first ?: numbers[global(i)]
        fun calls(body: IntRange): List<Call> {
            val found = ArrayList<Call>()
            for (k in body) {
                if (op(k) != OP_CALL_METHOD) continue
                val time = when {
                    s.arg[k] == 3 && name(k - 1)?.startsWith("INTERPOLATION_") == true -> number(k - 2)
                    s.arg[k] == 2 -> number(k - 1)
                    else -> continue
                }
                var m = k - 2
                while (m >= body.first && op(m) != OP_METHOD) m--
                if (m < body.first) continue
                val method = s.props.getOrNull(s.arg[m]) ?: continue
                found += Call(method, global(m - 1), m + 1, time)
            }
            return found
        }

        var interval: Float? = null
        var fade: Float? = null
        var zoomInterval: Float? = null
        var zoomMove: Float? = null
        var distances: List<Float> = emptyList()
        for (i in 0 until n) {
            if (name(i) != "IntervalTimer" || op(i + 3) != OP_NEW || s.arg[i + 3] != 2) continue
            val every = constant(i + 1)?.takeIf { it.second == 1 }?.first ?: numbers[global(i + 1)] ?: continue
            val body = bodies[global(i + 2)] ?: continue
            val found = calls(body)
            val fades = found.filter { it.method == "setColor" }.mapNotNull { it.time }
            if (interval == null && fades.isNotEmpty()) {
                interval = every
                fade = fades.max()
            }
            val moves = found.filter { it.method == "setPosition" && it.receiver in cameras }
            if (zoomInterval == null && moves.isNotEmpty()) {
                zoomInterval = every
                zoomMove = moves.mapNotNull { it.time }.maxOrNull()
                distances = moves.mapNotNull { call ->
                    val z = vectors[global(call.first)]?.getOrNull(2) ?: return@mapNotNull null
                    if (planeZ != null) z - planeZ else z
                }
            }
        }

        val slideEvery = (interval ?: DEFAULT_TIMING.interval).coerceIn(2f, 120f)
        val slideFade = (fade ?: DEFAULT_TIMING.fade).coerceIn(0f, slideEvery)
        val near = distances.minOrNull()
        val far = distances.maxOrNull()
        val zoom = if (zoomInterval != null && near != null && far != null && near > 0f) (far / near - 1f).coerceIn(0f, 0.25f) else 0f
        if (zoom < 0.005f) return Timing(slideEvery, slideFade, 0f, 0f, 0f)
        val zoomEvery = zoomInterval!!.coerceIn(2f, 120f)
        return Timing(slideEvery, slideFade, zoom, zoomEvery, (zoomMove ?: zoomEvery).coerceIn(0.5f, zoomEvery))
    }

    private fun cString(table: ByteArray, offset: Long): String {
        if (offset < 0 || offset >= table.size) throw Damaged()
        val start = offset.toInt()
        var end = start
        while (end < table.size && end - start < MAX_NAME_BYTES && table[end] != 0.toByte()) end++
        return String(table, start, end - start, Charsets.ISO_8859_1)
    }

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) throw Damaged()
        return ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
