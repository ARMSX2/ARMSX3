package com.armsx2.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import com.armsx2.runtime.MainActivityRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Optional user-chosen library background (#9). When unset the library falls back to the default
 * (the XMB wave, drawn in HomeScreen). The user can add a still image or an animated GIF/WebP
 * (Coil handles both), or a PS3 theme (.p3t), which [importTheme] turns into pictures in app
 * storage: its best picture, or for a dynamic theme its slides, played by [ThemeSlideshow], or its
 * whole scene, played live by [ThemeSceneView]. Everything added is kept (see [saved]) until the
 * user removes it, so they can switch between backgrounds without adding them again.
 * [useDefault] reverts to the default.
 */
object LibraryBackground {
    private const val PREF = "library.background.uri"
    private const val PREF_ANIM = "library.background.animated2d"
    private const val PREF_FLURRY = "library.background.flurry"
    private const val PREF_FLURRY_PRESET = "library.background.flurry.preset"
    private const val PREF_SAVER_KIND = "library_saver_kind"
    private const val PREF_RSS_PRESET = "library_rss_preset"

    /**
     * Which saver is CURRENTLY running, written synchronously before its GL thread starts and
     * cleared when that thread exits in an orderly way. See [armSaver].
     */
    private const val PREF_ARMED = "library.saver.armed"
    private const val PREF_SLIDESHOW = "library.background.slideshow"
    private const val PREF_SCENE = "library.background.scene"
    val uri = mutableStateOf<String?>(null)

    /**
     * A dynamic theme's slides, saved at import, and how to play them. [uri] points at the first
     * slide at the same time, so anything that shows the background as a still keeps working.
     */
    class Slideshow(val frames: List<File>, val timing: P3tAnimation.Timing)

    /** Set while the background is a dynamic theme; the library then plays it (see [ThemeSlideshow]). */
    val slideshow = mutableStateOf<Slideshow?>(null)

    /**
     * Set while the background is a dynamic theme played live: its unpacked scene, saved at import
     * in the same folder as the still [uri] points at (see [ThemeSceneView]).
     */
    val scene = mutableStateOf<File?>(null)

    /**
     * Force the lightweight 2D animated background ([LibraryWaveBackground]) even on devices where
     * the GLES3 XMB wave ([XmbGlView]) would run — i.e. let capable devices opt into the same
     * backdrop Mali / GL-fail devices already get. A user preference (#Luminz). Off = the GL wave.
     * No effect when a custom background image is set (that overrides everything), and no effect for
     * the devices that already fall back to the 2D wave.
     */
    val animated2D = mutableStateOf(false)

    /**
     * Draw Calum Robinson's Flurry ([FlurryGlView]) instead of the XMB wave.
     *
     * Takes precedence over [animated2D] and, like it, is overridden by a custom background
     * image. Off by default: it is a live particle simulation, and the library's animated
     * background has already been walked back once on performance grounds -- ARMSX2 shipped a
     * looping video here and removed it in 2.5.9 for exactly that reason. Opt-in keeps the
     * default cost where it is.
     */
    val flurry = mutableStateOf(false)

    /** Preset for the above. 99 = pick one at random each time the library opens. */
    val flurryPreset = mutableStateOf(99)

    /**
     * Which saver [flurry] runs: 0 = Flurry, then the Really Slick savers in the order of the
     * table in savers_jni.cpp -- 1 = Flux, 2 = Plasma, 3 = SolarWinds, 4 = Hyperspace, 5 = Lattice, 6 = Skyrocket.
     *
     * Flurry is Calum Robinson's (BSD-3-clause); the rest are Terry Welsh's Really Slick
     * Screensavers (GPL-2.0-or-later). They share the toggle above because only one background
     * can run at a time, and a single "animated background: on" reads better than one switch
     * per saver.
     */
    val saverKind = mutableStateOf(0)

    /** Preset for whichever Really Slick saver is selected, 1..6. 99 = pick one each time. */
    val rssPreset = mutableStateOf(99)

    /**
     * Set at startup when the previous run died with a saver on screen. Holds the [saverKind]
     * that was running so the library can say which one, and so the user knows their background
     * was turned off deliberately rather than forgotten. Read once and cleared by the reader.
     */
    val crashedSaver = mutableStateOf<Int?>(null)

    /** Display name for a [saverKind], for the message above. Matches the settings list. */
    fun saverName(kind: Int): String = when (kind) {
        1 -> "Flux"; 2 -> "Plasma"; 3 -> "SolarWinds"
        4 -> "Hyperspace"; 5 -> "Lattice"; 6 -> "Skyrocket"
        else -> "Flurry"
    }

    /**
     * Crash-loop breaker.
     *
     * The savers are native GL code, and native GL code can take the process down in ways no
     * `runCatching` can see -- a SIGSEGV in a driver, or a hang that Android resolves by killing
     * us. Because the choice is persisted and the library is the FIRST screen, a saver that dies
     * on startup dies again on every launch: the app never gets far enough for anyone to reach
     * Settings and switch it off. The only escape is clearing app data, which on Android takes
     * the memory cards and save states with it.
     *
     * So the setting arms itself before the GL thread starts and disarms when that thread exits
     * normally. Finding it still armed at startup means last run ended while a saver was on
     * screen -- the background is switched off and the user is told which one did it. The write
     * must be commit() rather than apply(): apply() is asynchronous, and the whole point is that
     * the process may be about to die.
     */
    fun armSaver() {
        runCatching {
            MainActivityRuntime.prefs.edit().putInt(PREF_ARMED, saverKind.value).commit()
        }
    }

    /**
     * Orderly teardown -- the saver ran without taking the process with it. Idempotent.
     *
     * apply() rather than commit() on purpose, and the asymmetry with [armSaver] is the point:
     * this one is not racing the process's death, and if it were lost the cost is a background
     * switched off for no reason, which is recoverable from Settings. The arm must never be lost.
     */
    fun disarmSaver() {
        runCatching { MainActivityRuntime.prefs.edit().remove(PREF_ARMED).apply() }
    }

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        uri.value = runCatching { MainActivityRuntime.prefs.getString(PREF, null) }.getOrNull()
        // Only when it still belongs to the background: its first slide is what uri points at.
        slideshow.value = runCatching { MainActivityRuntime.prefs.getString(PREF_SLIDESHOW, null) }.getOrNull()
            ?.let(::slideshowFrom)
            ?.takeIf { uri.value == Uri.fromFile(it.frames.first()).toString() }
        // Likewise the live scene: it sits beside the still.
        scene.value = runCatching { MainActivityRuntime.prefs.getString(PREF_SCENE, null) }.getOrNull()
            ?.let(::File)
            ?.takeIf { it.isFile && uri.value?.let { u -> Uri.parse(u).path }?.let(::File)?.parentFile == it.parentFile }
        animated2D.value = runCatching { MainActivityRuntime.prefs.getBoolean(PREF_ANIM, false) }.getOrDefault(false)
        flurry.value = runCatching { MainActivityRuntime.prefs.getBoolean(PREF_FLURRY, false) }.getOrDefault(false)
        flurryPreset.value = runCatching { MainActivityRuntime.prefs.getInt(PREF_FLURRY_PRESET, 99) }.getOrDefault(99)
        saverKind.value = runCatching { MainActivityRuntime.prefs.getInt(PREF_SAVER_KIND, 0) }.getOrDefault(0)
        rssPreset.value = runCatching { MainActivityRuntime.prefs.getInt(PREF_RSS_PRESET, 99) }.getOrDefault(99)

        // Still armed = the previous run died with a saver up. Break the loop (see armSaver).
        val armed = runCatching { MainActivityRuntime.prefs.getInt(PREF_ARMED, -1) }.getOrDefault(-1)
        if (armed >= 0) {
            crashedSaver.value = armed
            setFlurry(false)
            disarmSaver()
        }
    }

    fun setAnimated2D(on: Boolean) {
        animated2D.value = on
        runCatching { MainActivityRuntime.prefs.edit().putBoolean(PREF_ANIM, on).apply() }
    }

    fun setFlurry(on: Boolean) {
        flurry.value = on
        runCatching { MainActivityRuntime.prefs.edit().putBoolean(PREF_FLURRY, on).apply() }
    }

    fun setFlurryPreset(preset: Int) {
        flurryPreset.value = preset
        runCatching { MainActivityRuntime.prefs.edit().putInt(PREF_FLURRY_PRESET, preset).apply() }
    }

    fun setSaverKind(kind: Int) {
        saverKind.value = kind
        runCatching { MainActivityRuntime.prefs.edit().putInt(PREF_SAVER_KIND, kind).apply() }
    }

    fun setRssPreset(preset: Int) {
        rssPreset.value = preset
        runCatching { MainActivityRuntime.prefs.edit().putInt(PREF_RSS_PRESET, preset).apply() }
    }

    /**
     * What the library should actually run right now, with 99 ("random") resolved to a concrete
     * preset. Called once when the view is created, so random means once per library open
     * rather than once per frame.
     */
    fun currentSpec(): SaverSpec = when (val kind = saverKind.value) {
        in 1..6 -> SaverSpec.Rss(
            effect = kind - 1,  // indexes the table in savers_jni.cpp
            preset = rssPreset.value.let { if (it in 1..6) it else (1..6).random() },
        )
        else -> SaverSpec.Flurry(flurryPreset.value)
    }

    // ---- saved backgrounds ----
    //
    // Everything the user adds stays, each in a folder of its own under IMPORTED_DIR with a
    // meta.json saying what it is, until they remove it, so going back to one needs no re-import:
    // the library's menu lists them. The one on show is whichever [uri] points into.

    private const val SAVED_PREFIX = "bg_"
    private const val META_NAME = "meta.json"
    private const val MAX_PICTURE_BYTES = 64L shl 20

    /** A background the user added: its picture, and its slides or live scene when it has them. */
    class Saved(val folder: File, val name: String, val picture: File, val slideshow: Slideshow?, val scene: File?, val added: Long)

    /** Every saved background, oldest first. */
    val saved = mutableStateOf<List<Saved>>(emptyList())

    /** The saved background on show, if the background is one. Reads [uri] and [saved]. */
    fun current(): Saved? {
        val shown = uri.value ?: return null
        return saved.value.firstOrNull { Uri.fromFile(it.picture).toString() == shown }
    }

    /** Show [entry], adding it to [saved] when it is new, or the default background for null. */
    fun select(entry: Saved?) {
        if (entry == null) return useDefault()
        if (saved.value.none { it.folder == entry.folder }) saved.value = saved.value + entry
        setImported(entry.picture, entry.slideshow, entry.scene)
    }

    /** Back to the default background. Saved ones stay saved. */
    fun useDefault() {
        uri.value = null
        slideshow.value = null
        scene.value = null
        runCatching { MainActivityRuntime.prefs.edit().remove(PREF).remove(PREF_SLIDESHOW).remove(PREF_SCENE).apply() }
    }

    /** Delete [entry]'s files; the default background comes back when it was on show. */
    fun remove(entry: Saved) {
        if (current()?.folder == entry.folder) useDefault()
        saved.value = saved.value.filter { it.folder != entry.folder }
        runCatching { entry.folder.deleteRecursively() }
    }

    /**
     * Keep an import's [result] as a saved background called [name], its files moved into a folder
     * of their own. Null when there is nothing to keep. Blocking: call it off the main thread, then
     * hand the result to [select] on it.
     */
    fun keep(context: Context, result: ThemeImport, name: String): Saved? {
        val file = result.file ?: return null
        val dir = File(context.filesDir, IMPORTED_DIR)
        val folder = newSavedFolder(dir) ?: return null
        return try {
            val parent = file.parentFile
            if (parent != null && parent.parentFile == dir && parent.name.startsWith(IMPORTED_PREFIX)) {
                // Slides or a live scene: their whole folder becomes this one.
                folder.delete()
                if (!parent.renameTo(folder)) throw IOException("could not move ${parent.name}")
            } else if (!file.renameTo(File(folder, file.name))) {
                throw IOException("could not move ${file.name}")
            }
            fun moved(f: File) = File(folder, f.name)
            val entry = Saved(
                folder, name, moved(file),
                result.slideshow?.let { Slideshow(it.frames.map(::moved), it.timing) },
                result.scene?.let(::moved),
                System.currentTimeMillis(),
            )
            writeMeta(entry)
            entry
        } catch (_: Exception) {
            folder.deleteRecursively()
            null
        }
    }

    /** Copy a picked picture into a saved background called [name]. Null when it cannot be read. Blocking. */
    fun keepPicture(context: Context, source: Uri, name: String): Saved? {
        val dir = File(context.filesDir, IMPORTED_DIR).apply { mkdirs() }
        val folder = newSavedFolder(dir) ?: return null
        return try {
            val extension = when (context.contentResolver.getType(source)) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val file = File(folder, "picture.$extension")
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("unreadable")
            input.use { from ->
                file.outputStream().use { to ->
                    val buffer = ByteArray(64 shl 10)
                    var total = 0L
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PICTURE_BYTES) throw IOException("picture too large")
                        to.write(buffer, 0, n)
                    }
                }
            }
            Saved(folder, name, file, null, null, System.currentTimeMillis()).also(::writeMeta)
        } catch (_: Exception) {
            folder.deleteRecursively()
            null
        }
    }

    /** The picked file's name without its extension, to name what it becomes. */
    fun displayName(context: Context, source: Uri): String? = runCatching {
        context.contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Read the saved backgrounds, and keep what an older version left as the background, which
     * it deleted on the next change: an imported theme's files, or a picture used where the user
     * kept it. Returns that one, to [select] on the main thread. Blocking.
     */
    fun loadSaved(context: Context): Saved? {
        val dir = File(context.filesDir, IMPORTED_DIR)
        val found = dir.listFiles()?.filter { it.isDirectory && it.name.startsWith(SAVED_PREFIX) }?.mapNotNull(::readMeta)
            ?.sortedBy { it.added }.orEmpty()
        saved.value = found
        val shown = uri.value ?: return null
        if (found.any { Uri.fromFile(it.picture).toString() == shown }) return null
        return if (shown.startsWith("file:")) {
            val file = Uri.parse(shown).path?.let(::File)?.takeIf { it.isFile } ?: return null
            keep(context, ThemeImport(file, null, slideshow.value, scene.value), "PS3 theme")
        } else {
            val source = Uri.parse(shown)
            keepPicture(context, source, displayName(context, source) ?: "Picture")
        }
    }

    private fun newSavedFolder(dir: File): File? {
        repeat(4) {
            val folder = File(dir, SAVED_PREFIX + java.lang.Long.toHexString(System.currentTimeMillis()) + "_" + (0..0xfff).random().toString(16))
            if (folder.mkdirs()) return folder
        }
        return null
    }

    private fun writeMeta(entry: Saved) {
        val o = JSONObject()
            .put("name", entry.name)
            .put("added", entry.added)
            .put("picture", entry.picture.name)
        entry.scene?.let { o.put("scene", it.name) }
        entry.slideshow?.let { show ->
            o.put("slideshow", JSONObject(slideshowJson(show)).put("frames", JSONArray(show.frames.map { it.name })))
        }
        File(entry.folder, META_NAME).writeText(o.toString())
    }

    private fun readMeta(folder: File): Saved? = runCatching {
        val o = JSONObject(File(folder, META_NAME).readText())
        val picture = File(folder, o.getString("picture")).takeIf { it.isFile } ?: return null
        val scene = o.optString("scene").takeIf { it.isNotEmpty() }?.let { File(folder, it) }?.takeIf { it.isFile }
        val show = o.optJSONObject("slideshow")?.let { s ->
            val names = s.getJSONArray("frames")
            s.put("frames", JSONArray(List(names.length()) { File(folder, names.getString(it)).path }))
            slideshowFrom(s.toString())
        }
        Saved(folder, o.optString("name").ifEmpty { folder.name }, picture, show, scene, o.optLong("added"))
    }.getOrNull()

    // ---- PS3 themes (.p3t) ----
    //
    // A theme is not a picture, so it is not handed to Coil. The importer takes the best picture out
    // of it (see P3tTheme), saves that in app storage, and the background then points at the saved
    // file like any other image. A dynamic theme whose scene is a slideshow (see P3tAnimation) has
    // its slides saved instead, in a folder of their own, and plays them; any other scene its script
    // moves is kept whole in such a folder and played live (see ThemeScene). Each import is then
    // kept as a saved background (see keep), in a folder of its own, until the user removes it.

    private const val IMPORTED_DIR = "library_backgrounds"
    private const val IMPORTED_PREFIX = "p3t_"
    private const val MAX_STREAMED_THEME_BYTES = 128 shl 20
    private const val MAX_SCENE_BYTES = 256L shl 20
    private const val MAX_OVERLAYS = 4
    private const val SLIDE_QUALITY = 90
    private const val SCENE_NAME = "scene.raf"
    private const val STILL_NAME = "still.jpg"
    private const val STILL_WIDTH = 1920
    private const val STILL_HEIGHT = 1080

    /**
     * The outcome of an import: the saved picture, the slideshow or live scene when the theme plays
     * one, and a message key for anything worth saying.
     */
    class ThemeImport(
        val file: File?,
        val messageKey: String?,
        val slideshow: Slideshow? = null,
        val scene: File? = null,
        /** The theme's own name, when it has one. */
        val name: String? = null,
    )

    /** True when [source] starts with the PS3 theme magic. Reads four bytes. */
    fun isTheme(context: Context, source: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(source)?.use { input ->
            val head = ByteArray(4)
            var n = 0
            while (n < head.size) {
                val read = input.read(head, n, head.size - n)
                if (read < 0) break
                n += read
            }
            n == head.size && P3tTheme.isTheme(head)
        } ?: false
    }.getOrDefault(false)

    /**
     * Take the best picture out of a PS3 theme, or a dynamic theme's slides, and save them for the
     * background. Blocking: call it off the main thread, then hand the result to [setImported] on
     * the main thread.
     */
    fun importTheme(context: Context, source: Uri): ThemeImport =
        runCatching { withTheme(context, source) { importFrom(context, it) } }.getOrNull()
            ?: ThemeImport(null, "library.bg.readFailed")

    private fun importFrom(context: Context, theme: P3tTheme.Bytes): ThemeImport {
        val result = P3tTheme.read(theme)
        val dir = File(context.filesDir, IMPORTED_DIR).apply { mkdirs() }
        sweepImported(dir)
        // A dynamic theme plays its slides when its scene is a slideshow, and the scene itself when
        // its script moves anything else; otherwise it falls through to its best picture like any
        // other theme.
        result.anim?.let { anim ->
            runCatching { importAnimation(theme, anim, dir) }.getOrNull()?.let {
                return ThemeImport(it.file, it.messageKey, it.slideshow, it.scene, result.name)
            }
        }
        val picture = result.picture ?: return ThemeImport(
            null,
            when (result.failure) {
                P3tTheme.Failure.DYNAMIC_ONLY -> "library.bg.themeDynamic"
                P3tTheme.Failure.NO_PICTURE -> "library.bg.themeNoPicture"
                P3tTheme.Failure.NOT_A_THEME -> "library.bg.notPicture"
                else -> "library.bg.themeDamaged"
            },
        )
        val saved = runCatching {
            val jpeg = picture.jpeg
            if (jpeg != null) {
                // Only a picture Android can decode becomes the background: Coil would show nothing.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ThemeImport(null, "library.bg.themeDamaged")
                File(dir, "$IMPORTED_PREFIX${digest(jpeg)}.jpg").apply { writeBytes(jpeg) }
            } else {
                val rgba = picture.rgba ?: return ThemeImport(null, "library.bg.themeDamaged")
                val argb = IntArray(picture.width * picture.height) { i ->
                    val p = i * 4
                    ((rgba[p + 3].toInt() and 0xFF) shl 24) or ((rgba[p].toInt() and 0xFF) shl 16) or
                        ((rgba[p + 1].toInt() and 0xFF) shl 8) or (rgba[p + 2].toInt() and 0xFF)
                }
                val bitmap = Bitmap.createBitmap(argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
                try {
                    File(dir, "$IMPORTED_PREFIX${digest(rgba)}.png").apply {
                        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrNull() ?: return ThemeImport(null, "library.bg.readFailed")
        // A dynamic theme that is not a slideshow only gets its preview, which has the theme's own
        // icons drawn into it.
        return ThemeImport(saved, if (picture.source == P3tTheme.Source.PREVIEW) "library.bg.themePreview" else null, name = result.name)
    }

    /**
     * Unpack a dynamic theme's scene into a new folder (it is tens of MB, too much to hold) and make
     * the background out of it: a slideshow template's slides, or when the scene is anything else
     * its script moves, the scene itself, kept to be played live with its opening frame as the
     * still. Null when neither works, so the caller falls back to a picture.
     *
     * The scene is unpacked where a live one stays rather than moved there from the cache: a file
     * keeps the cache's group when moved, and would be counted as cache.
     */
    private fun importAnimation(theme: P3tTheme.Bytes, anim: P3tTheme.Anim, dir: File): ThemeImport? {
        val folder = File(dir, "$IMPORTED_PREFIX${System.currentTimeMillis().toString(16)}")
        val raw = File(folder, SCENE_NAME)
        var keep = false
        try {
            if (!folder.mkdirs()) return null
            val unpacked = raw.outputStream().buffered().use {
                P3tAnimation.unpack(theme, anim.offset, anim.size, it, MAX_SCENE_BYTES)
            }
            if (!unpacked) return null
            val result = RandomAccessFile(raw, "r").use { file ->
                val scene = ChannelBytes(file.channel, file.length())
                val slides = P3tAnimation.scene(scene)?.let { parsed ->
                    bake(scene, parsed.layers, folder)?.let { Slideshow(it, parsed.timing) }
                }
                if (slides != null && slides.frames.size > 1) return@use ThemeImport(slides.frames.first(), null, slides)
                val live = RafScene.read(scene)?.takeIf { ThemeScene.animates(it) }
                val still = live?.let { still(scene, it, folder) } ?: slides?.frames?.first()
                when {
                    still == null -> null
                    live != null -> ThemeImport(still, null, scene = raw)
                    // One slide and nothing moving is just a picture: the theme's own art, still.
                    else -> ThemeImport(still, null)
                }
            } ?: return null
            // Only a live scene keeps its unpacked file, next to its still.
            if (result.scene == null) raw.delete()
            keep = true
            return result
        } finally {
            if (!keep) folder.deleteRecursively()
        }
    }

    /**
     * The live scene's opening frame, saved in [folder] in place of any slides baked there. Null when
     * it cannot be drawn, leaving them.
     */
    private fun still(scene: P3tTheme.Bytes, live: RafScene.Scene, folder: File): File? {
        val bitmap = ThemeSceneRenderer.still(scene, live, STILL_WIDTH, STILL_HEIGHT) ?: return null
        val file = try {
            File(folder, STILL_NAME).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, SLIDE_QUALITY, it) } }
        } finally {
            bitmap.recycle()
        }
        folder.listFiles()?.forEach { if (it != file && it.name != SCENE_NAME) it.delete() }
        return file
    }

    /**
     * Draw each opaque layer (a slide) with the see-through layers above it, the way the PS3 draws
     * the scene, and save the results as JPEGs in [folder]. Goes from the top layer down, so the
     * layers above a slide are already decoded when it is reached and each texture is decoded once.
     */
    private fun bake(scene: P3tTheme.Bytes, layers: List<P3tAnimation.Layer>, folder: File): List<File>? {
        val width = layers.maxOf { it.texture.width }
        val height = layers.maxOf { it.texture.height }
        val area = Rect(0, 0, width, height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val above = ArrayList<Bitmap>() // bottom first
        val frames = arrayOfNulls<File>(layers.size)
        try {
            for (i in layers.indices.reversed()) {
                val texture = layers[i].texture
                val pixels = P3tAnimation.decode(scene, texture) ?: continue
                val picture = Bitmap.createBitmap(pixels, texture.width, texture.height, Bitmap.Config.ARGB_8888)
                if (!P3tAnimation.isSlide(texture, pixels)) {
                    if (above.size < MAX_OVERLAYS) above.add(0, picture) else picture.recycle()
                    continue
                }
                val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(frame).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(picture, null, area, paint)
                        for (layer in above) drawBitmap(layer, null, area, paint)
                    }
                    frames[i] = File(folder, "slide_%03d.jpg".format(i)).apply {
                        outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, SLIDE_QUALITY, it) }
                    }
                } finally {
                    frame.recycle()
                    picture.recycle()
                }
            }
        } finally {
            above.forEach { it.recycle() }
        }
        return frames.filterNotNull().ifEmpty { null }
    }

    /**
     * Delete what earlier imports left behind that is not the background any more: an import the
     * app did not live to finish leaves its slides. The current background is kept.
     */
    private fun sweepImported(dir: File) {
        val current = uri.value?.takeIf { it.startsWith("file:") }?.let { Uri.parse(it).path }?.let(::File)
        val keep = setOfNotNull(current, current?.parentFile)
        dir.listFiles()?.forEach { if (it.name.startsWith(IMPORTED_PREFIX) && it !in keep) runCatching { it.deleteRecursively() } }
    }

    /**
     * Make a picture saved by [importTheme] the background, playing [show] or the scene [live] when
     * there is one.
     */
    private fun setImported(file: File, show: Slideshow? = null, live: File? = null) {
        val value = Uri.fromFile(file).toString()
        uri.value = value
        slideshow.value = show
        scene.value = live
        runCatching {
            val edit = MainActivityRuntime.prefs.edit().putString(PREF, value)
            if (show != null) edit.putString(PREF_SLIDESHOW, slideshowJson(show)) else edit.remove(PREF_SLIDESHOW)
            if (live != null) edit.putString(PREF_SCENE, live.path) else edit.remove(PREF_SCENE)
            edit.apply()
        }
    }

    private fun slideshowJson(show: Slideshow): String = JSONObject()
        .put("frames", JSONArray(show.frames.map { it.path }))
        .put("interval", show.timing.interval.toDouble())
        .put("fade", show.timing.fade.toDouble())
        .put("zoom", show.timing.zoom.toDouble())
        .put("zoomInterval", show.timing.zoomInterval.toDouble())
        .put("zoomMove", show.timing.zoomMove.toDouble())
        .toString()

    /** The saved slideshow, when every slide is still there. */
    private fun slideshowFrom(json: String): Slideshow? = runCatching {
        val o = JSONObject(json)
        val list = o.getJSONArray("frames")
        val frames = List(list.length()) { File(list.getString(it)) }
        if (frames.size < 2 || frames.any { !it.isFile }) return null
        val timing = P3tAnimation.Timing(
            o.getDouble("interval").toFloat(),
            o.getDouble("fade").toFloat(),
            o.optDouble("zoom", 0.0).toFloat(),
            o.optDouble("zoomInterval", 0.0).toFloat(),
            o.optDouble("zoomMove", 0.0).toFloat(),
        )
        Slideshow(frames, timing)
    }.getOrNull()

    /**
     * Open the theme for [block]: random access when the provider allows it, so a large dynamic
     * theme is never read whole, and otherwise read whole within a limit.
     */
    private fun <T> withTheme(context: Context, source: Uri, block: (P3tTheme.Bytes) -> T): T {
        context.contentResolver.openFileDescriptor(source, "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val channel = input.channel
                val size = runCatching { channel.size() }.getOrDefault(0L)
                if (size > 0) return block(ChannelBytes(channel, size))
            }
        }
        // A provider that can only stream: read it whole, within a limit.
        val bytes = context.contentResolver.openInputStream(source)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 shl 10)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_STREAMED_THEME_BYTES) throw IOException("theme too large")
            }
            out.toByteArray()
        } ?: throw IOException("unreadable")
        return block(P3tTheme.ArrayBytes(bytes))
    }

    internal class ChannelBytes(private val channel: FileChannel, override val size: Long) : P3tTheme.Bytes {
        override fun read(offset: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var at = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                if (n < 0) throw IOException("short read")
                at += n
            }
            return buffer.array()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
