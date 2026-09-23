package com.armsx2.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import com.armsx2.runtime.MainActivityRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Optional user-chosen library background (#9). Stores a persisted content URI;
 * when unset the library falls back to the bundled default XMB-wave still image
 * (R.drawable.library_bg_xmb, drawn in HomeScreen). The user can pick a still image
 * or an animated GIF/WebP (Coil handles both), or a PS3 theme (.p3t), whose best picture is
 * imported into app storage by [importTheme]; `clear()` reverts to the default.
 * (The background used to be a looping MP4 but that hurt performance, so it's a
 * static image now.)
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
    val uri = mutableStateOf<String?>(null)

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

    fun set(context: Context, value: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(value, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        forgetImported()
        uri.value = value.toString()
        runCatching { MainActivityRuntime.prefs.edit().putString(PREF, value.toString()).apply() }
    }

    fun clear() {
        forgetImported()
        uri.value = null
        runCatching { MainActivityRuntime.prefs.edit().remove(PREF).apply() }
    }

    // ---- PS3 themes (.p3t) ----
    //
    // A theme is not a picture, so it is not handed to Coil. The importer takes the best picture out
    // of it (see P3tTheme), saves that in app storage, and the background then points at the saved
    // file like any other image. Unlike a picked picture, which stays where the user keeps it, this
    // one is ours, so it is deleted when the background changes or is cleared.

    private const val IMPORTED_DIR = "library_backgrounds"
    private const val IMPORTED_PREFIX = "p3t_"
    private const val MAX_STREAMED_THEME_BYTES = 128 shl 20

    /** The outcome of an import: the saved picture, and a message key for anything worth saying. */
    class ThemeImport(val file: File?, val messageKey: String?)

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
     * Take the best picture out of a PS3 theme and save it for the background. Blocking: call it
     * off the main thread, then hand the file to [setImported] on the main thread.
     */
    fun importTheme(context: Context, source: Uri): ThemeImport {
        val result = runCatching { readTheme(context, source) }.getOrNull()
            ?: return ThemeImport(null, "library.bg.readFailed")
        val picture = result.picture ?: return ThemeImport(
            null,
            when (result.failure) {
                P3tTheme.Failure.DYNAMIC_ONLY -> "library.bg.themeDynamic"
                P3tTheme.Failure.NO_PICTURE -> "library.bg.themeNoPicture"
                P3tTheme.Failure.NOT_A_THEME -> "library.bg.notPicture"
                else -> "library.bg.themeDamaged"
            },
        )
        val dir = File(context.filesDir, IMPORTED_DIR).apply { mkdirs() }
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
        // A dynamic theme only gets its preview, which has the theme's own icons drawn into it.
        return ThemeImport(saved, if (picture.source == P3tTheme.Source.PREVIEW) "library.bg.themePreview" else null)
    }

    /** Make a picture saved by [importTheme] the background. */
    fun setImported(file: File) {
        val value = Uri.fromFile(file).toString()
        if (uri.value != value) forgetImported()
        uri.value = value
        runCatching { MainActivityRuntime.prefs.edit().putString(PREF, value).apply() }
    }

    /** Delete the current background's file when it is one the importer saved. */
    private fun forgetImported() {
        val current = uri.value ?: return
        if (!current.startsWith("file:")) return
        val file = Uri.parse(current).path?.let(::File) ?: return
        if (file.parentFile?.name == IMPORTED_DIR && file.name.startsWith(IMPORTED_PREFIX)) runCatching { file.delete() }
    }

    /** Random access when the provider allows it, so a large dynamic theme is never read whole. */
    private fun readTheme(context: Context, source: Uri): P3tTheme.Result {
        context.contentResolver.openFileDescriptor(source, "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val channel = input.channel
                val size = runCatching { channel.size() }.getOrDefault(0L)
                if (size > 0) return P3tTheme.read(ChannelBytes(channel, size))
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
        return P3tTheme.read(P3tTheme.ArrayBytes(bytes))
    }

    private class ChannelBytes(private val channel: FileChannel, override val size: Long) : P3tTheme.Bytes {
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
