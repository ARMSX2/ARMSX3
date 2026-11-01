package com.armsx2.ui.xmb

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.armsx2.MenuSfx
import com.armsx2.Ps3Sfo
import java.io.File

/**
 * The XMB's own look and sound, taken from the firmware the user installed: its icons, its Rodin
 * font and its system sounds. Nothing of the console's is bundled with the app; without firmware
 * the XMB falls back to the app's own icons, font and menu sounds.
 *
 * Where things are in dev_flash/vsh/resource (all found by reading the files, see [Rco]):
 *  - xmb_ingame.rco: the category icons, tex_user, tex_sysconf, tex_photo, tex_music, tex_video,
 *    tex_tv, tex_game, tex_network, tex_psn and tex_friend, 128px.
 *  - explore_category_<category>.rco: that category's item icons (tex_power_off, tex_update,
 *    tex_premo, item_tex_trophy, item_tex_ps3util and the rest).
 *  - system_plugin.rco: snd_cursor, snd_decide, snd_cancel, snd_category_decide, snd_option and
 *    others, stereo VAG at 48 kHz.
 *  - Every one of these also holds the words it shows, in each of the console's languages: the
 *    category names in xmb_ingame.rco (msg_user, msg_setting ... msg_friend), each column's items
 *    in its explore_category_<category>.rco (msg_update, msg_game_setting, msg_gamedata_utility,
 *    msg_power_off_console2), and the shared ones in explore_plugin_full.rco (msg_info, msg_del).
 * The fonts are dev_flash/data/font/SCE-PS3-*.TTF.
 *
 * Everything here reads files, so call it off the main thread; results are cached for the run.
 */
object XmbFirmware {
    /** dev_flash of the installed firmware, or null when there is none. */
    fun devFlash(): File? = Ps3Sfo.storageRoot()
        ?.let { File(it, "config/dev_flash") }
        ?.takeIf { File(it, "vsh/resource").isDirectory }

    private val rcos = HashMap<String, Rco?>()
    private val images = HashMap<String, ImageBitmap?>()

    // Nothing is remembered as missing while the firmware cannot be found at all: that is the case
    // for a moment after launch, until the core has its data folder, and a miss remembered then
    // kept the XMB on the app's own icons, font and sounds for the whole run.

    @Synchronized
    private fun rco(name: String): Rco? {
        if (rcos.containsKey(name)) return rcos[name]
        val dir = devFlash() ?: return null
        return Rco.open(File(dir, "vsh/resource/$name.rco")).also { rcos[name] = it }
    }

    /**
     * The image [name] from the firmware's [rco] (no extension), decoded, or null; over a soft dark
     * shadow of itself with [shadow], as the XMB sets its icons off from the colour behind them
     * (the firmware keeps such shadows for some, tex_game_shadow and the like, and makes the rest).
     */
    @Synchronized
    fun image(rco: String, name: String, shadow: Boolean = false): ImageBitmap? {
        val key = "$rco/$name" + if (shadow) "#shadow" else ""
        if (images.containsKey(key)) return images[key]
        val container = rco(rco) ?: return null
        val base = name.substringBefore('#')
        val decoded = container.image(base)?.let(Gim::decode)
            ?.let { if (name.endsWith("#restart")) restartFrom(it) else it }
            ?.let { if (shadow) withShadow(it) else it }
        return decoded?.asImageBitmap().also { images[key] = it }
    }

    /**
     * Restart, which the firmware has no icon for, made from its Quit icon (a ring crossed out): the
     * ring alone, opened at the top right with an arrowhead going round, in the ring's own shades.
     * Asked for as "tex_quit#restart".
     */
    private fun restartFrom(quit: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = quit.width
        val h = quit.height
        val px = IntArray(w * h)
        quit.getPixels(px, 0, w, 0, 0, w, h)
        val cx = w / 2f
        val cy = h / 2f
        // The ring's edges, along the middle row in from the left.
        val mid = (h / 2) * w
        val outer = (0 until w / 2).firstOrNull { (px[mid + it] ushr 24) > 96 } ?: return quit
        var inner = outer
        while (inner < w / 2 && (px[mid + inner] ushr 24) > 96) inner++
        val rOut = cx - outer
        val rIn = cx - inner
        val thick = rOut - rIn
        if (thick < 2f) return quit
        fun at(r: Float, deg: Float): Int {
            val a = Math.toRadians(deg.toDouble())
            val x = (cx + r * kotlin.math.cos(a)).toInt().coerceIn(0, w - 1)
            val y = (cy + r * kotlin.math.sin(a)).toInt().coerceIn(0, h - 1)
            return px[y * w + x]
        }
        // Where the cross-bars meet the ring (opaque just inside it), so as to fill those with ring.
        val joints = BooleanArray(360) { d -> (at(rIn - 2f, d.toFloat()) ushr 24) > 96 }
        fun nearJoint(deg: Float): Boolean {
            val d = ((deg.toInt() % 360) + 360) % 360
            return (-14..14).any { joints[(d + it + 360) % 360] }
        }
        val out = IntArray(w * h)
        val gapFrom = -80f
        val gapTo = -22f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            if (r < rIn - 1.5f || r > rOut + 1.5f) continue
            val deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
            if (deg in gapFrom..gapTo) continue
            out[y * w + x] = if (nearJoint(deg)) {
                var d = deg + 40f
                while (nearJoint(d)) d += 20f
                at(r, d)
            } else px[y * w + x]
        }
        val bmp = android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        // The arrowhead at the ring's end by the gap, going round clockwise.
        val t0 = Math.toRadians(gapFrom.toDouble())
        val rm = (rIn + rOut) / 2f
        val bx = cx + rm * kotlin.math.cos(t0).toFloat()
        val by = cy + rm * kotlin.math.sin(t0).toFloat()
        val radX = kotlin.math.cos(t0).toFloat()
        val radY = kotlin.math.sin(t0).toFloat()
        val tanX = -radY
        val tanY = radX
        val half = thick * 1.25f
        val len = thick * 1.9f
        val path = android.graphics.Path().apply {
            moveTo(bx + radX * half, by + radY * half)
            lineTo(bx - radX * half, by - radY * half)
            lineTo(bx + tanX * len, by + tanY * len)
            close()
        }
        val light = at(rm + thick * 0.3f, -110f) or (0xff shl 24)
        val dark = at(rm - thick * 0.3f, -110f) or (0xff shl 24)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                bx + radX * half, by + radY * half, bx - radX * half, by - radY * half,
                light, dark, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        android.graphics.Canvas(bmp).drawPath(path, paint)
        return bmp
    }

    private fun withShadow(icon: android.graphics.Bitmap): android.graphics.Bitmap {
        val offset = IntArray(2)
        val blur = android.graphics.Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter((icon.width * 0.05f).coerceAtLeast(2f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val alpha = icon.extractAlpha(blur, offset)
        val out = android.graphics.Bitmap.createBitmap(icon.width, icon.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(
            alpha,
            offset[0].toFloat(),
            offset[1] + icon.height * 0.02f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                this.alpha = 100
            },
        )
        canvas.drawBitmap(icon, 0f, 0f, null)
        alpha.recycle()
        icon.recycle()
        return out
    }

    /** The sound [name] from system_plugin.rco as channels of PCM, or null. */
    fun sound(name: String): List<Vag.Pcm>? {
        val channels = rco("system_plugin")?.sound(name) ?: return null
        return channels.map { Vag.decode(it) ?: return null }
    }

    private val texts = HashMap<String, Map<String, String>>()

    /**
     * Every string in [rco] in the console's [language] (see [Rco]), or empty. A container opened
     * only for its words is not kept: explore_plugin_full.rco alone is 2.4 MB.
     */
    @Synchronized
    fun strings(rco: String, language: Int): Map<String, String> {
        val key = "$rco/$language"
        texts[key]?.let { return it }
        val dir = devFlash() ?: return emptyMap()
        val container = rcos[rco] ?: Rco.open(File(dir, "vsh/resource/$rco.rco"))
        return (container?.strings(language) ?: emptyMap()).also { texts[key] = it }
    }

    /** The console's number for the app's language [code], or null for one the console never had. */
    fun consoleLanguage(code: String): Int? = when (code) {
        "ja" -> 0
        "en" -> 1
        "fr" -> 2
        "es" -> 3
        "de" -> 4
        "it" -> 5
        "ru" -> 8
        "ko" -> 9
        "zh-TW" -> 10
        "zh-CN" -> 11
        "pl" -> 16
        "pt-BR" -> 17
        "tr" -> 19
        else -> null
    }

    /** A file under dev_flash, or null. */
    fun file(relative: String): File? = devFlash()?.let { File(it, relative) }?.takeIf { it.isFile }

    @Volatile private var typefaceLoaded = false
    @Volatile private var cachedTypeface: Typeface? = null

    /**
     * The XMB's typeface: Rodin (SCE-PS3-RD-R-LATIN) with the firmware's Japanese and Korean fonts
     * behind it for titles in those scripts, then the system's own for anything else. Null without
     * firmware.
     */
    fun typeface(): Typeface? {
        if (typefaceLoaded) return cachedTypeface
        // Not remembered as missing until the firmware can be found (see rco above).
        val fontDir = devFlash()?.let { File(it, "data/font") } ?: return null
        val made = runCatching {
            fun family(name: String): FontFamily? = File(fontDir, name).takeIf { it.isFile }
                ?.let { runCatching { FontFamily.Builder(Font.Builder(it).build()).build() }.getOrNull() }
            val latin = family("SCE-PS3-RD-R-LATIN.TTF") ?: return@runCatching null
            val builder = Typeface.CustomFallbackBuilder(latin)
            listOf("SCE-PS3-RD-R-LATIN2.TTF", "SCE-PS3-NR-R-JPN.TTF", "SCE-PS3-YG-R-KOR.TTF", "SCE-PS3-DH-R-CGB.TTF")
                .forEach { family(it)?.let(builder::addCustomFallback) }
            builder.setSystemFallback("sans-serif").build()
        }.getOrNull()
        cachedTypeface = made
        typefaceLoaded = true
        return made
    }
}

/**
 * Loading what the XMB takes from the firmware once the firmware can be found. That is a moment
 * after launch, once the core has its data folder, so the XMB waits for it a while rather than
 * settling for the app's own words, icons and sounds.
 */
object XmbFirmwareReady {
    private const val WAIT_MS = 20_000L
    private const val STEP_MS = 250L

    /** The firmware's words for [language], and [theme]'s icons and sounds (or the firmware's). */
    suspend fun load(context: Context, language: String, theme: com.armsx2.ui.home.LibraryBackground.Saved?) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            awaitFirmware()
            XmbText.load(language)
            XmbTheme.apply(context, theme)
        }
    }

    /** Wait for the firmware, up to [WAIT_MS]: true once it is there, false without it. */
    suspend fun awaitFirmware(limit: Long = WAIT_MS): Boolean {
        var waited = 0L
        while (XmbFirmware.devFlash() == null) {
            if (waited >= limit) return false
            kotlinx.coroutines.delay(STEP_MS)
            waited += STEP_MS
        }
        return true
    }
}

/**
 * The XMB's words: the firmware's own, in the app's language, wherever the console said the same
 * thing (the category names, Turn Off System, Game Data Utility, Information and the rest). In a
 * language the console never had, without firmware, or before [load] has read it, the app's own
 * string stands in. ARMSX3's own items (RPCN, Exit XMB Mode) are always the app's.
 */
object XmbText {
    /**
     * The containers the XMB takes words from: the category names and button hints, nearly every
     * item and question, the Settings column, and the in-game menu's.
     */
    private val SOURCES = listOf("xmb_ingame", "explore_plugin_full", "explore_category_sysconf", "impose_plugin")

    private val table = androidx.compose.runtime.mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    @Volatile private var loadedFor: String? = null

    /** Read the firmware's words for the app's language [code]. Off the main thread. */
    fun load(code: String) {
        if (loadedFor == code) return
        val language = XmbFirmware.consoleLanguage(code)
        if (language != null && XmbFirmware.devFlash() == null) return
        table.value = if (language == null) emptyMap() else SOURCES.associateWith { XmbFirmware.strings(it, language) }
        loadedFor = code
    }

    /** The firmware's [id] from [rco], or [fallback]. Reading it in composition follows [load]. */
    fun get(rco: String, id: String, fallback: String): String = find(rco, id) ?: fallback

    /**
     * The firmware's [id] from [rco], or null. The console's button marks (private-use characters
     * its own font draws as the Triangle or Circle) are left out: the XMB draws its own beside the
     * words, and any other font draws them as empty boxes.
     */
    fun find(rco: String, id: String): String? =
        table.value[rco]?.get(id)?.filterNot { it in '\uE000'..'\uF8FF' }?.trim()?.takeIf { it.isNotBlank() }
}

/** The firmware's words for [id] in [rco], or the app's string [key]. */
@androidx.compose.runtime.Composable
fun fw(rco: String, id: String, key: String): String = XmbText.get(rco, id, com.armsx2.i18n.str(key))

/** The XMB's system sounds, from the firmware when it has them, else the app's own menu sounds. */
object XmbSounds {
    enum class Sfx(val firmwareName: String, val fallback: MenuSfx.Event) {
        CURSOR("snd_cursor", MenuSfx.Event.NAV),
        DECIDE("snd_decide", MenuSfx.Event.SELECT),
        CANCEL("snd_cancel", MenuSfx.Event.BACK),
        CATEGORY("snd_category_decide", MenuSfx.Event.NAV),
        OPTION("snd_option", MenuSfx.Event.POPUP_OPEN),
        ERROR("snd_error", MenuSfx.Event.BACK),
        SYSTEM_OK("snd_system_ok", MenuSfx.Event.SELECT),
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Sfx, Int>()
    private val ready = HashSet<Int>()
    @Volatile private var loading = false

    /**
     * Decode the firmware's sounds into the cache and load them, the XMB theme's in place of any it
     * has ([XmbTheme.sound]). Off the main thread.
     */
    @Synchronized
    fun load(context: Context) {
        if (pool != null || loading) return
        loading = true
        val dir = File(context.cacheDir, "xmb-sounds")
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
        for (sfx in Sfx.entries) {
            XmbTheme.sound(sfx.firmwareName)?.let {
                ids[sfx] = sp.load(it.absolutePath, 1)
                continue
            }
            val wav = File(dir, "${sfx.firmwareName}.wav")
            if (!wav.isFile) {
                val pcm = XmbFirmware.sound(sfx.firmwareName) ?: continue
                if (!Vag.writeWav(pcm, wav)) continue
            }
            ids[sfx] = sp.load(wav.absolutePath, 1)
        }
        pool = sp
        loading = false
    }

    /** True once [load] has made a sound pool. */
    fun loaded(): Boolean = pool != null

    /** Load again, for a change of theme. Off the main thread. */
    @Synchronized
    fun reload(context: Context) {
        val old = pool
        pool = null
        ids.clear()
        synchronized(ready) { ready.clear() }
        old?.release()
        loading = false
        load(context)
    }

    fun play(sfx: Sfx) {
        if (!MenuSfx.enabled.value) return
        val p = pool
        val id = ids[sfx]
        if (p == null || id == null || synchronized(ready) { id !in ready }) {
            MenuSfx.play(sfx.fallback)
            return
        }
        // The firmware's clips are mastered quiet (around -20 dB), so they sit a little above the
        // app's own menu volume rather than at it.
        val gain = (MenuSfx.volumePercent.value.coerceIn(0, 100) / 100f * 2.2f).coerceAtMost(1f)
        runCatching { p.play(id, gain, gain, 1, 0, 1f) }
    }
}
