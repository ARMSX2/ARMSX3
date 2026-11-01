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

    @Synchronized
    private fun rco(name: String): Rco? = rcos.getOrPut(name) {
        devFlash()?.let { Rco.open(File(it, "vsh/resource/$name.rco")) }
    }

    /** The image [name] from the firmware's [rco] (no extension), decoded, or null. */
    @Synchronized
    fun image(rco: String, name: String): ImageBitmap? = images.getOrPut("$rco/$name") {
        rco(rco)?.image(name)?.let(Gim::decode)?.asImageBitmap()
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
    fun strings(rco: String, language: Int): Map<String, String> = texts.getOrPut("$rco/$language") {
        val container = rcos[rco] ?: devFlash()?.let { Rco.open(File(it, "vsh/resource/$rco.rco")) }
        container?.strings(language) ?: emptyMap()
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
        val made = runCatching {
            val fontDir = devFlash()?.let { File(it, "data/font") } ?: return@runCatching null
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
        table.value = if (language == null) emptyMap() else SOURCES.associateWith { XmbFirmware.strings(it, language) }
        loadedFor = code
    }

    /** The firmware's [id] from [rco], or [fallback]. Reading it in composition follows [load]. */
    fun get(rco: String, id: String, fallback: String): String = find(rco, id) ?: fallback

    /** The firmware's [id] from [rco], or null. */
    fun find(rco: String, id: String): String? = table.value[rco]?.get(id)?.takeIf { it.isNotBlank() }
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

    /** Decode the firmware's sounds into the cache and load them. Off the main thread. */
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
