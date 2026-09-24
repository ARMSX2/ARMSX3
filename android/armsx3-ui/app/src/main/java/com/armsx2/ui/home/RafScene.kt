package com.armsx2.ui.home

import java.io.IOException

/**
 * A dynamic PS3 theme's whole RAF scene, for playing it (see [ThemeScene]): every actor with its
 * mesh, material and transform, the camera, and the script. [P3tAnimation] reads the same file
 * for the simpler slideshow case.
 *
 * Layout (big-endian; the table format is described in [P3tAnimation]):
 *  - actor: position, rotation (radians, applied X then Y then Z), scale, color (RGBA multiplier),
 *    uv_offset / uv_scale (applied to the mesh's UVs; textures wrap), and id refs to its material
 *    and model.
 *  - material: effect, a name such as "pure_texture_alpha_1_depth_0" (alpha blended, no depth
 *    test) or "pure_texture" (opaque), and a "_texture" child naming a texture.
 *  - model: a "geometry" child naming an .edge file (below).
 *  - camera: position, direction, up, yfov (vertical, radians), znear, zfar.
 *
 * An .edge file is a Sony Edge geometry segment. The header holds the vertex and index counts at
 * 0x18, then offsets and sizes: indexes at 0x20 (size in the high half of 0x24), positions at 0x28
 * (float3, size in the high half of 0x30), and further vertex streams whose layout is given by
 * format descriptors (see [texcoords]). The indexes are compressed; see [edgeIndices].
 *
 * Everything is user input and checked the way [P3tTheme] and [P3tAnimation] check it: anything
 * out of bounds or inconsistent leaves that actor out rather than reading past the data.
 */
object RafScene {
    /**
     * [skinned]: the mesh is bent by a skeleton (Ape Escape's monkeys, birds, butterflies). Skeletal
     * animation (.skel, .anim, .invbind) is not decoded yet, so such meshes only have their bind pose.
     */
    class Mesh(val positions: FloatArray, val uvs: FloatArray, val indices: IntArray, val skinned: Boolean = false) {
        val vertexCount: Int get() = positions.size / 3

        /** Total triangle area; zero for a mesh a theme's author collapsed to hide it. */
        fun area(): Double {
            var sum = 0.0
            for (t in indices.indices step 3) {
                val a = indices[t] * 3; val b = indices[t + 1] * 3; val c = indices[t + 2] * 3
                val ux = (positions[b] - positions[a]).toDouble(); val uy = (positions[b + 1] - positions[a + 1]).toDouble(); val uz = (positions[b + 2] - positions[a + 2]).toDouble()
                val vx = (positions[c] - positions[a]).toDouble(); val vy = (positions[c + 1] - positions[a + 1]).toDouble(); val vz = (positions[c + 2] - positions[a + 2]).toDouble()
                val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
                sum += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz)
            }
            return sum
        }
    }

    /**
     * Effects seen in real themes: pure_texture, pure_texture_alpha_1_depth_0 / _depth_1,
     * color_map, basic_lighting, basic_lighting_edge_lit and basic_lighting_alpha_add. Everything
     * but the lit ones is drawn with its texture's alpha: Ape Escape's sun, shadows and butterflies
     * are "pure_texture" sprites cut out by it, and the slideshow templates fade whole slides
     * through their colour's alpha whatever the effect. Lighting itself is not modelled; themes'
     * textures carry most of it.
     */
    class Material(val effect: String, val texture: P3tAnimation.Texture?) {
        /** Lit and not see-through: drawn solid, and hides what is drawn behind it later. */
        val opaque: Boolean get() = effect.startsWith("basic_lighting") && "alpha" !in effect
        /** "alpha_add": added onto what is behind it, weighted by alpha. */
        val additive: Boolean get() = "alpha_add" in effect
        /** "depth_0": drawn in actor order with no depth test. */
        val depthTest: Boolean get() = "depth_0" !in effect
    }

    class Actor(
        val name: String,
        val mesh: Mesh?,
        val material: Material?,
        val position: FloatArray,
        val rotation: FloatArray,
        val scale: FloatArray,
        val color: FloatArray,
        val uvOffset: FloatArray,
        val uvScale: FloatArray,
    )

    class Camera(val position: FloatArray, val direction: FloatArray, val up: FloatArray, val yfov: Float, val znear: Float, val zfar: Float)

    class Scene(val actors: List<Actor>, val camera: Camera, val script: ByteArray?)

    private const val RAFO = 0x5241464FL
    private const val HEADER_BYTES = 56L
    private const val ELEMENT_BYTES = 28L
    private const val ATTRIBUTE_BYTES = 16L
    private const val TYPE_FLOAT = 2L
    private const val TYPE_STRING = 3L
    private const val TYPE_VECTOR = 5L
    private const val TYPE_FILE = 6L
    private const val TYPE_ID = 7L
    private const val TYPE_REF = 8L
    private const val MAX_TABLE_BYTES = 16 shl 20
    private const val MAX_MESH_BYTES = 4 shl 20
    private const val MAX_SCRIPT_BYTES = 1 shl 20
    private const val MAX_NAME_BYTES = 256
    private const val MAX_VECTOR = 16
    private const val MAX_ACTORS = 512

    /** The scene, or null when the file is not a RAF scene or has no camera. */
    fun read(raf: P3tTheme.Bytes): Scene? = try {
        parse(raf)
    } catch (_: Damaged) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

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

        class ActorRefs(val id: Long, val material: Long?, val model: Long?, val attrs: Map<String, Attr>)
        val actors = ArrayList<ActorRefs>()
        var camera: Map<String, Attr>? = null
        var script: Long? = null
        val materialEffect = HashMap<Long, String>()
        val materialAt = HashMap<Long, Long>()
        val materialTexture = HashMap<Long, Long>()
        val modelAt = HashMap<Long, Long>()
        val modelGeometry = HashMap<Long, Long>()
        val textureFile = HashMap<Long, Long>()
        val fileSpan = HashMap<Long, Attr>()

        var pos = 0L
        while (pos + ELEMENT_BYTES <= elements.size) {
            val name = cString(names, be32(elements, pos.toInt()))
            val count = be32(elements, pos.toInt() + 4)
            val parent = be32(elements, pos.toInt() + 8)
            if (count > (elements.size - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES) throw Damaged()
            val attrs = HashMap<String, Attr>()
            for (i in 0L until count) {
                val at = (pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES).toInt()
                attrs[cString(names, be32(elements, at))] = Attr(be32(elements, at + 4), be32(elements, at + 8), be32(elements, at + 12))
            }
            fun ref(key: String): Long? = attrs[key]?.takeIf { it.type == TYPE_ID || it.type == TYPE_REF }?.a
            when (name) {
                "actor" -> ref("id")?.let { if (actors.size < MAX_ACTORS) actors += ActorRefs(it, ref("material"), ref("model"), attrs) }
                "camera" -> if (camera == null) camera = attrs
                "script" -> if (script == null) script = ref("fileref")
                "material" -> ref("id")?.let { id ->
                    materialAt[pos] = id
                    attrs["effect"]?.takeIf { it.type == TYPE_STRING }?.let { materialEffect[id] = cString(names, it.a).trim() }
                }
                // Lit materials list their colour map first and a shared lighting map (bg_obj_s in Ape
                // Escape's theme) after it; the first is the one to draw with.
                "_texture" -> { val m = materialAt[parent]; val t = ref("texref"); if (m != null && t != null) materialTexture.putIfAbsent(m, t) }
                "model" -> ref("id")?.let { modelAt[pos] = it }
                "geometry" -> { val m = modelAt[parent]; val f = ref("fileref"); if (m != null && f != null) modelGeometry[m] = f }
                "texture" -> { val id = ref("id"); val f = ref("fileref"); if (id != null && f != null) textureFile[id] = f }
                "file" -> { val id = ref("id"); val src = attrs["src"]; if (id != null && src != null && src.type == TYPE_FILE) fileSpan[id] = src }
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        fun file(id: Long): Pair<Long, Long>? {
            val span = fileSpan[id] ?: return null
            if (span.a > filesSize || span.b > filesSize - span.a) return null
            return (filesAt + span.a) to span.b
        }
        fun vector(attrs: Map<String, Attr>, key: String, fallback: FloatArray): FloatArray {
            val attr = attrs[key] ?: return fallback
            if (attr.type != TYPE_VECTOR || attr.b !in 1..MAX_VECTOR) return fallback
            if (attr.a > floats.size / 4 || attr.b > floats.size / 4 - attr.a) return fallback
            val v = FloatArray(attr.b.toInt()) { Float.fromBits(be32(floats, ((attr.a + it) * 4).toInt()).toInt()) }
            return if (v.size >= fallback.size) v.copyOf(fallback.size) else fallback.copyOf().also { v.copyInto(it) }
        }
        fun scalar(attrs: Map<String, Attr>, key: String, fallback: Float): Float {
            val attr = attrs[key] ?: return fallback
            return when (attr.type) {
                TYPE_FLOAT -> Float.fromBits(attr.a.toInt())
                TYPE_VECTOR -> vector(attrs, key, floatArrayOf(fallback))[0]
                else -> fallback
            }
        }

        val meshes = HashMap<Long, Mesh?>()
        val textures = HashMap<Long, P3tAnimation.Texture?>()
        val built = actors.map { a ->
            val mesh = a.model?.let { model ->
                meshes.getOrPut(model) {
                    modelGeometry[model]?.let(::file)?.takeIf { it.second in 1..MAX_MESH_BYTES.toLong() }
                        ?.let { (offset, size) -> edgeMesh(raf.read(offset, size.toInt())) }
                }
            }
            val material = a.material?.let { m ->
                val texture = materialTexture[m]?.let { t ->
                    textures.getOrPut(t) {
                        textureFile[t]?.let(::file)?.let { (offset, size) -> runCatching { P3tAnimation.gtf(raf, offset, size) }.getOrNull() }
                    }
                }
                Material(materialEffect[m] ?: "", texture)
            }
            Actor(
                name = cString(ids, a.id + 4),
                mesh = mesh,
                material = material,
                position = vector(a.attrs, "position", FloatArray(3)),
                rotation = vector(a.attrs, "rotation", FloatArray(3)),
                scale = vector(a.attrs, "scale", floatArrayOf(1f, 1f, 1f)),
                color = vector(a.attrs, "color", floatArrayOf(1f, 1f, 1f, 1f)),
                uvOffset = vector(a.attrs, "uv_offset", FloatArray(2)),
                uvScale = vector(a.attrs, "uv_scale", floatArrayOf(1f, 1f)),
            )
        }
        val cam = camera ?: return null
        val sceneCamera = Camera(
            position = vector(cam, "position", FloatArray(3)),
            direction = vector(cam, "direction", floatArrayOf(0f, 0f, -1f)),
            up = vector(cam, "up", floatArrayOf(0f, 1f, 0f)),
            yfov = scalar(cam, "yfov", 0.9273f).takeIf { it > 0.01f && it < 3.1f } ?: 0.9273f,
            znear = scalar(cam, "znear", 0.1f).coerceAtLeast(1e-4f),
            zfar = scalar(cam, "zfar", 5000f).coerceAtLeast(1f),
        )
        val code = script?.let(::file)?.takeIf { it.second in 1..MAX_SCRIPT_BYTES.toLong() }?.let { (offset, size) -> raf.read(offset, size.toInt()) }
        return Scene(built, sceneCamera, code)
    }

    /** An .edge file's mesh, or null when it does not decode cleanly. */
    fun edgeMesh(b: ByteArray): Mesh? = try {
        decodeMesh(b)
    } catch (_: Damaged) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * True when an .edge mesh draws nothing, which is how theme authors hide an actor they do not
     * want: a mesh with no area, or one whose indexes are all zero. Fallout NV Custom Dynamic hides
     * most of the Prince of Persia template the second way, some of it with stray points left.
     */
    fun collapsed(b: ByteArray): Boolean = try {
        edgeMesh(b)?.let { it.area() < 1e-6 } ?: run {
            val at = be32(b, 0x20)
            val size = be32(b, 0x24) ushr 16
            size >= 8 && at + size <= b.size && (at until at + size).all { b[it.toInt()] == 0.toByte() }
        }
    } catch (_: Damaged) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun decodeMesh(b: ByteArray): Mesh? {
        if (b.size < 0x44) return null
        val vertices = be16(b, 0x18)
        val indexCount = be16(b, 0x1a)
        if (vertices < 3 || indexCount < 3 || indexCount % 3 != 0) return null
        val indexAt = be32(b, 0x20)
        val indexSize = be32(b, 0x24) ushr 16
        val positionsAt = be32(b, 0x28)
        val positionsSize = be32(b, 0x30) ushr 16
        fun inside(at: Long, size: Long) = at <= b.size && size <= b.size - at
        if (!inside(indexAt, indexSize) || !inside(positionsAt, positionsSize)) return null
        if (positionsSize < 12L * vertices) return null
        val positions = FloatArray(vertices * 3) { Float.fromBits(be32(b, (positionsAt + 4L * it).toInt()).toInt()) }
        if (positions.any { !it.isFinite() }) return null
        val uvs = texcoords(b, vertices) ?: return null
        val block = b.copyOfRange(indexAt.toInt(), (indexAt + indexSize).toInt())
        val indices = edgeIndices(block, indexCount, vertices) ?: return null
        // Skinning info (matrix palette sizes and stream offsets) sits at 0x44; rigid meshes leave it zero.
        val skinned = (0x44 until 0x50).any { b[it] != 0.toByte() }
        return Mesh(positions, uvs, indices, skinned)
    }

    /**
     * Edge's compressed triangle list. After an 8-byte header -- the count of reused-vertex values, a
     * bias for them, the byte length of the flag stream, and the reused values' bit width in the
     * high byte of the last word -- come three MSB-first bit streams, each starting on a byte:
     *  1. one bit per index read: 0 = the next new vertex, 1 = an existing vertex from stream 3;
     *  2. two bits per triangle: 3 = read all three indices, otherwise share an edge of the previous
     *     triangle (p0 p1 p2) and read only the third: 0 = (p0, p2, x), 1 = (p2, p1, x),
     *     2 = (p1, p0, x), which keeps the winding;
     *  3. the reused-vertex values: each, less the bias, is a delta added to the index decoded eight
     *     reads earlier -- eight running sums, one per 16-bit lane of an SPU register.
     * Worked out from real theme meshes: a 5x5 grid whose 19 reused vertices only tile it this way,
     * and every mesh of Sony's Ape Escape theme (up to 1,152 vertices), all of which then use every
     * vertex, with no edge shared by more than two triangles.
     */
    fun edgeIndices(block: ByteArray, indexCount: Int, vertexCount: Int): IntArray? {
        if (block.size < 8) return null
        val reused = be16(block, 0)
        val bias = be16(block, 2)
        val flagBytes = be16(block, 4)
        val width = block[6].toInt() and 0xFF
        val triangles = indexCount / 3
        if (flagBytes < 1 || width > 16) return null
        val reads = flagBytes.toLong() * 8
        val flagsAt = 8
        val codesAt = flagsAt + flagBytes
        val valuesAt = codesAt + (2 * triangles + 7) / 8
        if (valuesAt.toLong() * 8 + reused.toLong() * width > block.size.toLong() * 8) return null

        fun bits(byteStart: Int, bitIndex: Long, count: Int): Int {
            var value = 0
            var p = byteStart * 8L + bitIndex
            repeat(count) {
                val byte = block[(p ushr 3).toInt()].toInt()
                value = (value shl 1) or ((byte ushr (7 - (p and 7).toInt())) and 1)
                p++
            }
            return value
        }

        val lanes = IntArray(8)
        var readIndex = 0L
        var reuseIndex = 0
        var next = 0
        fun index(): Int {
            if (readIndex >= reads) throw Damaged()
            val flag = bits(flagsAt, readIndex++, 1)
            if (flag == 0) return next++
            if (reuseIndex >= reused) throw Damaged()
            val lane = reuseIndex % 8
            lanes[lane] = (lanes[lane] + bits(valuesAt, reuseIndex.toLong() * width, width) - bias) and 0xFFFF
            reuseIndex++
            return lanes[lane]
        }

        val out = IntArray(indexCount)
        return try {
            var p0 = -1; var p1 = -1; var p2 = -1
            for (t in 0 until triangles) {
                val code = bits(codesAt, 2L * t, 2)
                val a: Int; val c: Int; val d: Int
                if (code == 3) {
                    a = index(); c = index(); d = index()
                } else {
                    if (p0 < 0) return null // an edge to share needs a triangle before it
                    val x = index()
                    when (code) {
                        0 -> { a = p0; c = p2; d = x }
                        1 -> { a = p2; c = p1; d = x }
                        else -> { a = p1; c = p0; d = x }
                    }
                }
                out[3 * t] = a; out[3 * t + 1] = c; out[3 * t + 2] = d
                p0 = a; p1 = c; p2 = d
            }
            if (out.any { it !in 0 until vertexCount }) null else out
        } catch (_: Damaged) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    // (format descriptor pointer, stream offset, stream size) of the streams that can carry texture
    // coordinates: the RSX-only stream, then the secondary input stream.
    private val UV_STREAMS = listOf(Triple(0x84, 0x3c, 0x40), Triple(0x7c, 0x2c, 0x34))

    /**
     * Texture coordinate 0 of every vertex. A stream's format descriptor is an attribute count and a
     * stride, then 8 bytes per attribute: offset, type (2 = float, 3 = half), component count, and
     * id (1 position, 2 normal, 3 tangent, 5 texture coordinate 0, 6 texture coordinate 1).
     */
    private fun texcoords(b: ByteArray, vertices: Int): FloatArray? {
        for ((descSlot, streamSlot, sizeSlot) in UV_STREAMS) {
            val desc = be32(b, descSlot)
            val streamAt = be32(b, streamSlot)
            val streamSize = be32(b, sizeSlot)
            if (desc == 0L || desc + 8 > b.size) continue
            val count = b[desc.toInt()].toInt() and 0xFF
            val stride = b[desc.toInt() + 1].toInt() and 0xFF
            if (stride == 0 || count > 16 || desc + 8 + 8L * count > b.size) continue
            if (streamAt > b.size || streamSize > b.size - streamAt || stride.toLong() * vertices > streamSize) continue
            for (i in 0 until count) {
                val a = (desc + 8 + 8 * i).toInt()
                val offset = b[a].toInt() and 0xFF
                val type = b[a + 1].toInt() and 0xFF
                val components = b[a + 2].toInt() and 0xFF
                val id = b[a + 3].toInt() and 0xFF
                if (id != 5 || components < 2) continue
                val size = when (type) { 3 -> 2; 2 -> 4; else -> continue }
                if (offset + 2 * size > stride) continue
                return FloatArray(vertices * 2) { k ->
                    val at = (streamAt + stride.toLong() * (k / 2) + offset + size * (k % 2)).toInt()
                    if (type == 3) halfToFloat(be16(b, at)) else Float.fromBits(be32(b, at).toInt())
                }
            }
        }
        return null
    }

    internal fun halfToFloat(h: Int): Float {
        val sign = if (h and 0x8000 != 0) -1f else 1f
        val exp = (h ushr 10) and 0x1F
        val frac = h and 0x3FF
        return when (exp) {
            0 -> sign * frac * (1f / (1 shl 24))
            31 -> if (frac == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + frac / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
    }

    private fun cString(table: ByteArray, offset: Long): String {
        if (offset < 0 || offset >= table.size) throw Damaged()
        val start = offset.toInt()
        var end = start
        while (end < table.size && end - start < MAX_NAME_BYTES && table[end] != 0.toByte()) end++
        return String(table, start, end - start, Charsets.ISO_8859_1)
    }

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
