package com.armsx2.ui.home

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.TreeMap

/**
 * Not a check but a survey: runs every theme in a folder through what the library does with it and
 * lists what the player does not handle, to find what real themes need.
 *
 *     P3T_CORPUS=/path/to/themes P3T_REPORT=/path/to/report.txt \
 *         ./gradlew :app:testGithubDebugUnitTest --tests '*ThemeCorpusAudit*'
 *
 * For each theme: how the library would show it (slideshow, live scene, still, preview), and every
 * part of its scene the player skips: elements and attributes it does not read, material effects
 * and texture formats it does not know, meshes and rigs that do not decode, script instructions it
 * does not run, and what the script asks the host for that is not there (from two minutes of it
 * running, with a few different random numbers).
 */
class ThemeCorpusAudit {

    /** Feature -> the themes that use it, with a sample of what it looked like. */
    private class Tally {
        val themes = TreeMap<String, MutableSet<String>>()
        val samples = HashMap<String, String>()
        fun add(feature: String, theme: String, sample: String? = null) {
            themes.getOrPut(feature) { sortedSetOf() } += theme
            if (sample != null) samples.putIfAbsent(feature, sample)
        }
    }

    private class Attr(val type: Long, val a: Long, val b: Long)

    private class Element(val at: Long, val name: String, val parent: Long, val attrs: LinkedHashMap<String, Attr>)

    @Test
    fun audit() {
        val dir = System.getenv("P3T_CORPUS")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val files = dir!!.walkTopDown().filter { it.isFile && it.name.endsWith(".p3t", ignoreCase = true) }.sortedBy { it.name.lowercase() }.toList()
        val tally = Tally()
        val outcomes = TreeMap<String, Int>()
        val lines = ArrayList<String>()
        val scratch = File.createTempFile("raf", ".bin").apply { deleteOnExit() }
        for (file in files) {
            val notes = ArrayList<String>()
            val outcome = try {
                audit(file, scratch, tally, notes)
            } catch (t: Throwable) {
                "crash: $t".also { tally.add("audit crash", file.name, t.toString()) }
            }
            outcomes.merge(outcome.substringBefore(' '), 1, Int::plus)
            lines += "${file.name}\t$outcome" + if (notes.isEmpty()) "" else "\n    " + notes.joinToString("\n    ")
        }
        scratch.delete()

        val report = buildString {
            appendLine("${files.size} themes")
            for ((k, v) in outcomes) appendLine("  $k: $v")
            appendLine()
            appendLine("Not handled, by how many themes need it:")
            for ((feature, themes) in tally.themes.entries.sortedByDescending { it.value.size }) {
                append("  ${themes.size}\t$feature")
                tally.samples[feature]?.let { append("\t(e.g. $it)") }
                appendLine()
                appendLine("      " + themes.take(6).joinToString(", ") + if (themes.size > 6) ", ..." else "")
            }
            appendLine()
            appendLine("Themes:")
            lines.forEach(::appendLine)
        }
        val out = System.getenv("P3T_REPORT")
        if (out != null) File(out).writeText(report) else println(report)
    }

    /** One theme: what the library makes of it. Everything it skips goes into [tally] and [notes]. */
    private fun audit(file: File, scratch: File, tally: Tally, notes: MutableList<String>): String {
        val name = file.name
        val theme = RandomAccessFile(file, "r").use { f -> P3tTheme.ArrayBytes(ByteArray(f.length().toInt()).also { f.readFully(it) }) }
        val result = P3tTheme.read(theme)
        val anim = result.anim ?: return when {
            result.picture != null -> "static"
            else -> "unreadable ${result.failure}".also { tally.add("theme unreadable: ${result.failure}", name) }
        }
        val unpacked = scratch.outputStream().buffered().use { P3tAnimation.unpack(theme, anim.offset, anim.size, it, 256L shl 20) }
        if (!unpacked) {
            tally.add("scene does not unpack", name)
            return if (result.picture != null) "preview (scene does not unpack)" else "nothing (scene does not unpack)"
        }
        return RandomAccessFile(scratch, "r").use { f ->
            val raf = LibraryBackground.ChannelBytes(f.channel, f.length())
            walk(raf, name, tally, notes)
            val slides = slides(raf)
            val scene = RafScene.read(raf)
            if (scene == null) tally.add("scene unreadable by RafScene", name)
            val live = scene?.let { play(it, name, tally, notes) } ?: false
            scene?.let { checkActors(it, raf, name, tally, notes) }
            val size = "%.1f MB".format(f.length() / 1e6)
            // As LibraryBackground.importAnimation decides: live when the scene moves, else slides.
            when {
                live -> {
                    val covered = coverage(scene!!)
                    if (covered < 0.6) tally.add("live scene leaves the screen mostly empty", name, "%.0f%% covered".format(100 * covered))
                    "live ${scene.actors.size} actors, ${scene.actors.count { it.drawable() }} drawable, %.0f%% of the screen, $size".format(100 * covered)
                }
                slides > 1 -> "slideshow $slides slides, $size"
                slides == 1 || scene != null -> "still ($size)"
                result.picture != null -> "preview ($size)"
                else -> "nothing"
            }
        }
    }

    private fun RafScene.Actor.drawable() = mesh != null && material?.texture != null && (!mesh.skinned || rig != null) && mesh.area() > 1e-6

    /** How many slides the library would bake, as LibraryBackground.bake counts them. */
    private fun slides(raf: P3tTheme.Bytes): Int {
        val show = P3tAnimation.scene(raf) ?: return 0
        return show.layers.count { layer -> P3tAnimation.decode(raf, layer.texture)?.let { P3tAnimation.isSlide(layer.texture, it) } == true }
    }

    // ---- the element tree, read raw ----

    private fun walk(raf: P3tTheme.Bytes, name: String, tally: Tally, notes: MutableList<String>) {
        val header = raf.read(0, 56)
        val tables = List(6) { be32(header, 8 + 8 * it) to be32(header, 12 + 8 * it) }
        fun table(i: Int) = raf.read(tables[i].first, tables[i].second.toInt())
        val elements = table(0)
        val ids = table(1)
        val names = table(2)
        val floats = table(4)
        val (filesAt, filesSize) = tables[5]

        val all = ArrayList<Element>()
        var pos = 0L
        while (pos + 28 <= elements.size) {
            val count = be32(elements, pos.toInt() + 4)
            val attrs = LinkedHashMap<String, Attr>()
            for (i in 0L until count) {
                val at = (pos + 28 + 16 * i).toInt()
                attrs[cString(names, be32(elements, at))] = Attr(be32(elements, at + 4), be32(elements, at + 8), be32(elements, at + 12))
            }
            all += Element(pos, cString(names, be32(elements, pos.toInt())), be32(elements, pos.toInt() + 8), attrs)
            pos += 28 + 16 * count
        }
        val byOffset = all.associateBy { it.at }

        fun value(attr: Attr): String = when (attr.type) {
            1L -> attr.a.toInt().toString()
            2L -> Float.fromBits(attr.a.toInt()).toString()
            3L -> "\"" + cString(names, attr.a) + "\""
            5L -> (0 until minOf(attr.b, 6L).toInt()).joinToString(",", "[", if (attr.b > 6) ",...]" else "]") {
                "%.4g".format(Float.fromBits(be32(floats, ((attr.a + it) * 4).toInt()).toInt()))
            }
            6L -> "file ${attr.b} bytes"
            7L, 8L -> "#" + cString(ids, attr.a + 4)
            else -> "type ${attr.type}"
        }

        val fileSpan = HashMap<Long, Attr>()
        for (e in all) if (e.name == "file") {
            val id = e.attrs["id"]?.a
            val src = e.attrs["src"]
            if (id != null && src != null) fileSpan[id] = src
        }
        fun fileBytes(ref: Attr?, max: Int = 4 shl 20): ByteArray? {
            val span = ref?.let { fileSpan[it.a] } ?: return null
            if (span.a + span.b > filesSize || span.b > max) return null
            return raf.read(filesAt + span.a, span.b.toInt())
        }

        val counts = TreeMap<String, Int>()
        for (e in all) {
            counts.merge(e.name, 1, Int::plus)
            val handled = HANDLED[e.name]
            if (handled == null) {
                if (e.name !in CONTAINERS) tally.add("element <${e.name}>", name, e.attrs.entries.joinToString(" ") { "${it.key}=${value(it.value)}" })
            } else {
                for ((key, attr) in e.attrs) {
                    if (key in handled || key in METADATA) continue
                    val v = value(attr)
                    val expected = EXPECTED["${e.name}.$key"]
                    if (expected == null) tally.add("attribute ${e.name}.$key", name, v)
                    else if (v !in expected) tally.add("attribute ${e.name}.$key = $v", name)
                }
            }
            for ((key, attr) in e.attrs) if (attr.type !in 1L..8L || attr.type == 4L) tally.add("attribute type ${attr.type}", name, "${e.name}.$key")
            when (e.name) {
                "actor" -> {
                    val parent = byOffset[e.parent]?.name
                    if (parent != null && parent != "scene") tally.add("actor inside <$parent>", name)
                }
                "material" -> {
                    val effect = e.attrs["effect"]?.let { cString(names, it.a).trim() } ?: ""
                    if (effect !in KNOWN_EFFECTS) tally.add("effect \"$effect\"", name)
                    val textures = all.count { it.parent == e.at && it.name == "_texture" }
                    if (textures > 2) tally.add("material with $textures textures", name, effect)
                }
                "light" -> {
                    val type = e.attrs["type"]?.a?.toInt() ?: 0
                    if (type !in 0..2) tally.add("light type $type", name)
                }
                "texture" -> {
                    val gtf = fileBytes(e.attrs["fileref"], 64 shl 20)
                    if (gtf == null || gtf.size < 48) {
                        tally.add("texture file missing", name)
                    } else if ((gtf[0] == 0xFF.toByte() && gtf[1] == 0xD8.toByte()) || (gtf[0] == 0x89.toByte() && gtf[1] == 'P'.code.toByte())) {
                        // A JPEG or PNG, which the player decodes with the platform's decoder.
                        notes += "picture texture #${e.attrs["id"]?.let { cString(ids, it.a + 4) }}"
                    } else {
                        val format = gtf[24].toInt() and 0xFF
                        val base = format and 0x9F
                        val dimension = gtf[26].toInt() and 0xFF
                        val cube = gtf[27].toInt() and 0xFF
                        if (base !in 0x86..0x88) {
                            tally.add("texture format ${FORMATS[base] ?: "0x%02x".format(base)}${if (format and 0x20 == 0) " swizzled" else ""}", name,
                                "${be16(gtf, 32)}x${be16(gtf, 34)} #${e.attrs["id"]?.let { cString(ids, it.a + 4) }}")
                        }
                        if (cube != 0) tally.add("texture cube map", name)
                        if (dimension != 2) tally.add("texture dimension $dimension", name)
                        if (be32(gtf, 8) > 1) tally.add("texture file with ${be32(gtf, 8)} textures", name)
                    }
                }
                "geometry" -> {
                    val edge = fileBytes(e.attrs["fileref"])
                    when {
                        edge == null -> tally.add("mesh file missing", name)
                        RafScene.edgeMesh(edge) != null -> {}
                        RafScene.collapsed(edge) || blanked(edge) -> {}
                        else -> {
                            val model = byOffset[e.parent]?.attrs?.get("id")?.let { cString(ids, it.a + 4) }
                            tally.add("mesh does not decode", name, "$model: ${edgeProbe(edge)}")
                            notes += "mesh $model: ${edgeProbe(edge)}"
                        }
                    }
                }
                "animation" -> {
                    val clip = fileBytes(e.attrs["fileref"])
                    if (clip != null && clip.size >= 0x26) {
                        val user = be16(clip, 0x16 + 6) + be16(clip, 0x16 + 14)
                        if (user > 0) tally.add("clip with user channels", name, "$user channels")
                    }
                }
            }
        }
        notes += "elements: " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }

    // ---- the scene as the player reads it ----

    private fun checkActors(scene: RafScene.Scene, raf: P3tTheme.Bytes, name: String, tally: Tally, notes: MutableList<String>) {
        var noTexture = 0
        var noRig = 0
        var noClip = 0
        for (a in scene.actors) {
            if (a.material != null && a.material.texture == null) noTexture++
            if (a.mesh?.skinned == true && a.rig == null) noRig++
            a.rig?.clips?.count { it == null }?.let { noClip += it }
            val widest = a.mesh?.segments?.maxOfOrNull { it.palette?.size ?: 0 } ?: 0
            if (widest > 72) tally.add("palette over 72 joints", name, "${a.name}: $widest")
        }
        if (noTexture > 0) tally.add("actor texture undecoded", name, "$noTexture actors")
        if (noRig > 0) tally.add("skinned mesh without a rig", name, "$noRig actors")
        if (noClip > 0) tally.add("animation clip undecoded", name, "$noClip clips")
        if (noTexture + noRig + noClip > 0) notes += "actors: $noTexture no texture, $noRig no rig, $noClip clips undecoded"
        if (scene.camera.yfov == 0.9273f) notes += "camera: default yfov"
    }

    /**
     * Share of a 16:9 screen the scene's shown actors cover a second in: each triangle's bounding
     * box marked on a coarse grid. A scene read wrong (camera, placement) tends to cover little.
     */
    private fun coverage(scene: RafScene.Scene): Double {
        val play = ThemeScene(scene) { 0.7 }
        repeat(60) { play.advance(ThemeScene.TICK) }
        val lens = scene.camera
        val vp = ThemeScene.multiply(
            ThemeScene.projection(play.camera.yfov, 16f / 9f, lens.znear, lens.zfar),
            ThemeScene.viewMatrix(play.camera.position, play.camera.direction, play.camera.up),
        )
        val cols = 64
        val rows = 36
        val grid = BooleanArray(cols * rows)
        val model = FloatArray(16)
        val mvp = FloatArray(16)
        for (a in play.actors) {
            if (!a.shown) continue
            val mesh = a.source.mesh ?: continue
            ThemeScene.modelMatrix(a.position, a.rotation, a.scale, model)
            ThemeScene.multiply(vp, model, mvp)
            val xs = FloatArray(mesh.vertexCount)
            val ys = FloatArray(mesh.vertexCount)
            val front = BooleanArray(mesh.vertexCount)
            // Where the renderer puts each vertex: bent by its joints first, when it has them.
            val skin = a.skinMatrices()
            val segmentOf = IntArray(mesh.vertexCount)
            if (skin != null) for (s in mesh.segments) for (i in s.firstIndex until s.firstIndex + s.indexCount) segmentOf[mesh.indices[i]] = mesh.segments.indexOf(s)
            for (v in 0 until mesh.vertexCount) {
                var x = mesh.positions[3 * v]; var y = mesh.positions[3 * v + 1]; var z = mesh.positions[3 * v + 2]
                val palette = mesh.segments[segmentOf[v]].palette
                if (skin != null && palette != null && mesh.joints != null && mesh.weights != null) {
                    var sx = 0f; var sy = 0f; var sz = 0f
                    for (k in 0 until 4) {
                        val w = mesh.weights[4 * v + k]
                        if (w <= 0f) continue
                        val m = 12 * palette[mesh.joints[4 * v + k]]
                        sx += w * (skin[m] * x + skin[m + 1] * y + skin[m + 2] * z + skin[m + 3])
                        sy += w * (skin[m + 4] * x + skin[m + 5] * y + skin[m + 6] * z + skin[m + 7])
                        sz += w * (skin[m + 8] * x + skin[m + 9] * y + skin[m + 10] * z + skin[m + 11])
                    }
                    x = sx; y = sy; z = sz
                }
                val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
                val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
                val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
                front[v] = cw > 1e-4f
                if (front[v]) { xs[v] = cx / cw; ys[v] = cy / cw }
            }
            for (t in mesh.indices.indices step 3) {
                val i = mesh.indices[t]; val j = mesh.indices[t + 1]; val k = mesh.indices[t + 2]
                if (!front[i] || !front[j] || !front[k]) continue
                val x0 = minOf(xs[i], xs[j], xs[k]); val x1 = maxOf(xs[i], xs[j], xs[k])
                val y0 = minOf(ys[i], ys[j], ys[k]); val y1 = maxOf(ys[i], ys[j], ys[k])
                if (x1 < -1 || x0 > 1 || y1 < -1 || y0 > 1) continue
                val c0 = ((x0 + 1) / 2 * cols).toInt().coerceIn(0, cols - 1); val c1 = ((x1 + 1) / 2 * cols).toInt().coerceIn(0, cols - 1)
                val r0 = ((y0 + 1) / 2 * rows).toInt().coerceIn(0, rows - 1); val r1 = ((y1 + 1) / 2 * rows).toInt().coerceIn(0, rows - 1)
                for (r in r0..r1) for (c in c0..c1) grid[r * cols + c] = true
            }
        }
        return grid.count { it }.toDouble() / grid.size
    }

    /** Run the script for two minutes, three times; true when it moves anything drawn. */
    private fun play(scene: RafScene.Scene, name: String, tally: Tally, notes: MutableList<String>): Boolean {
        val program = scene.script?.let(VsmxVm.Program::parse)
        if (scene.script != null && program == null) tally.add("script unreadable", name)
        if (program != null) {
            val unknown = program.op.map { it and 0xFF }.filter { it !in KNOWN_OPS }.groupingBy { it }.eachCount()
            for ((op, n) in unknown) tally.add("opcode 0x%02x".format(op), name, "$n times")
        }
        var moves = false
        for (seed in listOf(0.7, 0.05, 0.35)) {
            val asked = sortedSetOf<String>()
            val play = ThemeScene(scene) { seed }
            play.unsupported = { asked += it }
            play.start()
            val errors = sortedSetOf<String>()
            play.scriptError?.let { errors += "top level: $it" }
            repeat((120 / ThemeScene.TICK).toInt()) {
                play.advance(ThemeScene.TICK)
                play.scriptError?.let { errors += it }
            }
            for (a in asked) tally.add("host: $a", name)
            for (e in errors) tally.add("script: " + e.replace(Regex("\\b\\d+\\b"), "N"), name, e)
            if (seed == 0.7) {
                if (asked.isNotEmpty()) notes += "asks for: " + asked.joinToString(", ")
                if (errors.isNotEmpty()) notes += "script stops: " + errors.joinToString(" | ")
            }
        }
        moves = ThemeScene.animates(scene, random = { 0.7 })
        if (scene.script == null) tally.add("no script (clips only)", name)
        return moves
    }

    /**
     * True when every segment's triangle codes are zero, which no real mesh has (its first triangle
     * reads three indexes, code 3): another way authors hide a mesh. Fallout NV Custom Dynamic's
     * CirculraSmoke_01 keeps its header and index flags but has no codes.
     */
    private fun blanked(b: ByteArray): Boolean = runCatching {
        val count = be32(b, 0).toInt()
        val at = be32(b, 4).toInt()
        (0 until count).all { i ->
            val s = at + 0x80 * i
            val index = be32(b, s + 0x10).toInt()
            val codesAt = index + 8 + be16(b, index + 4)
            val codeBytes = (2 * (be16(b, s + 10) / 3) + 7) / 8
            (codesAt until codesAt + codeBytes).all { b[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    /** An .edge file's segments as their headers describe them. */
    private fun edgeProbe(b: ByteArray): String = runCatching {
        val count = be32(b, 0).toInt()
        val at = be32(b, 4).toInt()
        (0 until minOf(count, 4)).joinToString(" | ", "$count segments: ") { i ->
            val s = at + 0x80 * i
            fun desc(slot: Int): String {
                val d = be32(b, s + slot).toInt()
                if (d == 0 || d + 8 > b.size) return "-"
                val n = b[d].toInt() and 0xFF
                return "stride ${b[d + 1].toInt() and 0xFF} [" + (0 until minOf(n, 8)).joinToString(" ") { k ->
                    val a = d + 8 + 8 * k
                    "id${b[a + 3].toInt() and 0xFF}:t${b[a + 1].toInt() and 0xFF}x${b[a + 2].toInt() and 0xFF}@${b[a].toInt() and 0xFF}"
                } + "]"
            }
            "flav %02x%02x v%d i%d idx+%d/%d pos %d rsx %s in2 %s".format(
                b[s + 6], b[s + 7], be16(b, s + 8), be16(b, s + 10), be16(b, s + 0x14), be16(b, s + 0x16),
                be16(b, s + 0x20) + be16(b, s + 0x22) + be16(b, s + 0x24), desc(0x74), desc(0x6C),
            )
        }
    }.getOrElse { "unreadable header ($it)" }

    // ---- bytes ----

    private fun cString(table: ByteArray, offset: Long): String {
        if (offset < 0 || offset >= table.size) return "?"
        var end = offset.toInt()
        while (end < table.size && table[end] != 0.toByte()) end++
        return String(table, offset.toInt(), end - offset.toInt(), Charsets.ISO_8859_1)
    }

    private fun be16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private companion object {
        /** Elements the player reads, and the attributes it reads from each. */
        val HANDLED = mapOf(
            "actor" to setOf("id", "material", "model", "position", "rotation", "scale", "color", "uv_offset", "uv_scale", "anim_weight", "anim_speed", "anim_time"),
            "camera" to setOf("id", "position", "direction", "up", "yfov", "znear", "zfar"),
            "light" to setOf("id", "type", "color", "position", "direction"),
            "script" to setOf("id", "fileref"),
            "material" to setOf("id", "effect"),
            "_texture" to setOf("texref"),
            "model" to setOf("id"),
            "geometry" to setOf("fileref"),
            "skeleton" to setOf("fileref"),
            "inv-bind" to setOf("fileref"),
            "animation" to setOf("fileref"),
            "texture" to setOf("id", "fileref"),
            "file" to setOf("id", "src"),
        )

        /** Bookkeeping of the tools that wrote the file, nothing drawn. */
        val METADATA = setOf("fileindex", "type_", "ext")

        /**
         * Attributes the player does not read because every value seen so far is what it does anyway:
         * reported only when a theme has another value.
         */
        val EXPECTED = mapOf(
            "actor.zsort" to setOf("\"\""),
            "camera.type" to setOf("0"),
            "camera.ymag" to setOf("0.0"),
            "file.type" to setOf("0"),
            "texture.type" to setOf("0"),
            "texture.wrap_s" to setOf("1"),
            "texture.wrap_t" to setOf("1"),
            "texture.wrap_p" to setOf("1"),
            "texture.mag_filter" to setOf("0"),
            "texture.min_filter" to setOf("0"),
            "texture.mip_filter" to setOf("0"),
        )

        /** Elements that only hold others. */
        val CONTAINERS = setOf("raf", "scene", "model-table", "material-table", "texture-table", "file-table")

        val KNOWN_EFFECTS = setOf("pure_texture", "color_map", "basic_lighting", "basic_lighting_edge_lit", "basic_lighting_alpha_add") +
            (0..2).flatMap { a -> (0..1).map { d -> "pure_texture_alpha_${a}_depth_$d" } }

        /** What VsmxVm runs. */
        val KNOWN_OPS = (0x01..0x15).toSet() + (0x20..0x31) + setOf(0x33, 0x34, 0x36) + (0x38..0x3f) + (0x41..0x45) + setOf(0x49, 0x4a, 0x4d)

        /** CellGcm texture formats, without the linear and unnormalised flags. */
        val FORMATS = mapOf(
            0x81 to "B8", 0x82 to "A1R5G5B5", 0x83 to "A4R4G4B4", 0x84 to "R5G6B5", 0x85 to "A8R8G8B8",
            0x86 to "DXT1", 0x87 to "DXT3", 0x88 to "DXT5", 0x8B to "G8B8", 0x8F to "R6G5B5",
            0x97 to "R5G5B5A1", 0x9A to "RGBA16F", 0x9B to "RGBA32F", 0x9D to "D1R5G5B5", 0x9E to "D8R8G8B8",
        )
    }
}
