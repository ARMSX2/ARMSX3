package com.armsx2.ui.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.home.LibraryBackground
import com.armsx2.ui.home.P3tTheme
import java.io.File

/**
 * XMB mode's theme, as Theme Settings > Theme picks it on the console: Original (the firmware's
 * wave, icons and sounds) or a PS3 theme the user imported, shown with its own background, icons
 * and system sounds. The themes are the library's saved backgrounds ([LibraryBackground.saved]),
 * so one imported for the library is here too; the theme's icons and sounds are kept beside its
 * background in [LibraryBackground.XMB_DIR] when it is imported. A theme imported before that was
 * kept gets them from its .p3t on the device at the next start ([LibraryBackground.restoreXmbAssets]),
 * and brings its background alone only when that file is gone, until it is imported again.
 *
 * The library's own background stays its own: picking a theme here changes only XMB mode, where
 * the theme's sounds can play too (the library has its music and menu sounds).
 */
object XmbTheme {
    private const val Key = "xmb.theme"

    /** The saved background's folder name, or null for Original. */
    val selected = mutableStateOf<String?>(null)

    /** Moves on whenever the theme's icons change, so an icon on screen is looked up again. */
    val revision = mutableIntStateOf(0)

    @Volatile private var icons: Map<String, File> = emptyMap()
    @Volatile private var sounds: Map<String, File> = emptyMap()
    private val bitmaps = HashMap<String, ImageBitmap?>()
    private var applied: String? = null

    fun load() {
        selected.value = MainActivityRuntime.prefs.getString(Key, null)
    }

    /** The theme in use, or null for Original. Reads [selected] and the saved backgrounds. */
    fun entry(): LibraryBackground.Saved? {
        val name = selected.value ?: return null
        return LibraryBackground.saved.value.firstOrNull { it.folder.name == name }
    }

    fun select(entry: LibraryBackground.Saved?) {
        selected.value = entry?.folder?.name
        MainActivityRuntime.prefs.edit { if (entry == null) remove(Key) else putString(Key, entry.folder.name) }
    }

    /**
     * Read the icons and sounds of [entry] (null for Original), then load the sounds. Off the main
     * thread.
     */
    @Synchronized
    fun apply(context: Context, entry: LibraryBackground.Saved?) {
        // Again once the firmware turns up: until then the sounds are the app's own.
        val key = (entry?.folder?.path ?: "") + "|" + (XmbFirmware.devFlash() != null)
        if (key == applied && XmbSounds.loaded()) return
        applied = key
        val xmb = entry?.folder?.let { File(it, LibraryBackground.XMB_DIR) }?.takeIf { it.isDirectory }
        val found = HashMap<String, File>()
        xmb?.let { File(it, "icons").listFiles() }?.forEach { f ->
            if (f.isFile && f.extension == "png") ICONS[f.nameWithoutExtension]?.forEach { found[it] = f }
        }
        val heard = HashMap<String, File>()
        xmb?.let { File(it, "sounds").listFiles() }?.forEach { f ->
            if (f.isFile && f.extension == "wav") SOUNDS[f.nameWithoutExtension]?.forEach { heard[it] = f }
        }
        synchronized(bitmaps) { bitmaps.clear() }
        icons = found
        sounds = heard
        if (entry != null) {
            android.util.Log.i(
                "ARMSX3-Theme",
                if (xmb == null) "'${entry.name}': no icons or sounds were kept with it, only its background"
                else "'${entry.name}': ${found.size} XMB icons and ${heard.size} sounds replaced",
            )
        }
        XmbSounds.reload(context)
        revision.intValue++
    }

    /**
     * Read the theme in use again, its icons or sounds having changed on disk, when XMB mode has
     * read it already; otherwise XMB mode reads it as it is when it starts. Off the main thread.
     */
    fun reapply(context: Context) {
        synchronized(this) {
            if (applied == null) return
            applied = null
        }
        apply(context, entry())
    }

    /** The theme's picture for the firmware icon [rco]/[name], decoded, or null. Off the main thread. */
    fun image(rco: String, name: String): ImageBitmap? {
        val file = icons["$rco/$name"] ?: return null
        synchronized(bitmaps) { bitmaps[file.path]?.let { return it } }
        val decoded = runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        synchronized(bitmaps) { bitmaps[file.path] = decoded }
        return decoded
    }

    /** The theme's sound for the firmware's [name] (snd_cursor), as a WAV file, or null. */
    fun sound(name: String): File? = sounds[name]

    /**
     * How many icons and sounds were kept with [entry], or null when none were ever read for it: a
     * picture, or a theme that [LibraryBackground.lacksXmbAssets]. Blocking, but small.
     */
    fun assetCounts(entry: LibraryBackground.Saved): Pair<Int, Int>? {
        val xmb = File(entry.folder, LibraryBackground.XMB_DIR).takeIf { it.isDirectory } ?: return null
        return (File(xmb, "icons").list()?.size ?: 0) to (File(xmb, "sounds").list()?.size ?: 0)
    }

    /** A saved theme's own icon, when it has one. */
    fun iconOf(entry: LibraryBackground.Saved): File? =
        File(File(entry.folder, LibraryBackground.XMB_DIR), "icon.png").takeIf { it.isFile }

    /**
     * A theme's icon ids, as the console names them in a theme, against the firmware icons they
     * stand in for here. Taken from real themes' icon tables (icon_user ... icon_tv, then the
     * items); the console's own theme has no PlayStation Network icon to replace.
     */
    private val ICONS: Map<String, List<String>> = mapOf(
        "icon_user" to listOf("xmb_ingame/tex_user"),
        "icon_setting" to listOf("xmb_ingame/tex_sysconf"),
        "icon_photo" to listOf("xmb_ingame/tex_photo"),
        "icon_music" to listOf("xmb_ingame/tex_music"),
        "icon_video" to listOf("xmb_ingame/tex_video"),
        "icon_tv" to listOf("xmb_ingame/tex_tv"),
        "icon_game" to listOf("xmb_ingame/tex_game"),
        "icon_network" to listOf("xmb_ingame/tex_network"),
        "icon_friend" to listOf("xmb_ingame/tex_friend"),
        "icon_poweroff" to listOf("explore_category_user/tex_power_off"),
        "icon_newuser" to listOf("explore_category_user/item_tex_NewUser"),
        "icon_update" to listOf("explore_category_sysconf/tex_update"),
        "icon_game_setting" to listOf("explore_category_sysconf/tex_game"),
        "icon_bdvd_setting" to listOf("explore_category_sysconf/tex_bddvd"),
        "icon_music_setting" to listOf("explore_category_sysconf/tex_music"),
        "icon_chat_setting" to listOf("explore_category_sysconf/tex_chat"),
        "icon_system_setting" to listOf("explore_category_sysconf/tex_console"),
        "icon_theme_setting" to listOf("explore_category_sysconf/tex_thema"),
        "icon_datetime_setting" to listOf("explore_category_sysconf/tex_datetime"),
        "icon_powersave_setting" to listOf("explore_category_sysconf/tex_power"),
        "icon_accessory" to listOf("explore_category_sysconf/tex_pd"),
        "icon_printer_setting" to listOf("explore_category_sysconf/tex_printer"),
        "icon_display_setting" to listOf("explore_category_sysconf/tex_display"),
        "icon_sound_setting" to listOf("explore_category_sysconf/tex_sound"),
        "icon_security_setting" to listOf("explore_category_sysconf/tex_security"),
        "icon_remoteplay_setting" to listOf("explore_category_sysconf/tex_remote"),
        "icon_network_setting" to listOf("explore_category_sysconf/tex_network"),
        "icon_setting_item" to listOf("explore_category_sysconf/tex_sett"),
        "icon_savedata" to listOf("explore_category_game/item_tex_ps3util"),
        // Game Data Utility's: the PKG/Data Manager stands where it was.
        "icon_gamedata" to listOf("explore_category_game/item_tex_disc_install"),
        "icon_trophy" to listOf("explore_category_psn/item_tex_trophy"),
        "icon_browser" to listOf("explore_category_network/tex_browser"),
        "icon_remoteplay" to listOf("explore_category_network/tex_premo"),
        "icon_onlinemanual" to listOf("explore_category_network/tex_onlinemanual"),
        "icon_inet_search" to listOf("explore_category_network/tex_kensaku"),
        "icon_download" to listOf("explore_category_network/tex_dl_manage"),
        "icon_accountmanage" to listOf("explore_category_psn/item_tex_account"),
        "icon_addfriend" to listOf("explore_category_friend/item_tex_NewFriend"),
        "icon_playermet" to listOf("explore_category_friend/item_tex_Players"),
        "icon_photo_default" to listOf("explore_category_photo/photo_tex_default"),
        "icon_photo_album_default" to listOf("explore_category_photo/photo_tex_folder"),
        "icon_music_default" to listOf("explore_category_music/music_tex_default"),
        "icon_music_album_default" to listOf("explore_category_music/music_tex_album_default"),
        "icon_video_default" to listOf("explore_category_video/video_tex_default"),
    )

    /** A theme's system sounds against the firmware's, which they replace. */
    private val SOUNDS: Map<String, List<String>> = mapOf(
        "se_cursor" to listOf("snd_cursor", "snd_category_decide"),
        "se_decide" to listOf("snd_decide"),
        "se_cancel" to listOf("snd_cancel"),
        "se_optionmenu" to listOf("snd_option"),
        "se_system_ok" to listOf("snd_system_ok"),
    )
}

/**
 * Takes a theme's XMB icons and system sounds out at import, into the folder that is kept with
 * it: icons/<id>.png, sounds/<id>.wav and the theme's own icon.png. PNG and WAV so nothing has to
 * be decoded again to show or play them.
 */
object XmbThemeAssets {
    /** True when anything was taken out of [theme] into [folder]. Blocking. */
    fun extract(theme: P3tTheme.Bytes, folder: File): Boolean {
        var any = false
        for (asset in P3tTheme.assets(theme)) {
            val name = asset.id.filter { it.isLetterOrDigit() || it == '_' }.take(64)
            if (name.isEmpty()) continue
            when (asset.kind) {
                "icon" -> {
                    val gim = P3tTheme.iconGim(theme, asset) ?: continue
                    val bitmap = Gim.decode(gim) ?: continue
                    val out = if (asset.id == "theme") File(folder, "icon.png") else File(File(folder, "icons").apply { mkdirs() }, "$name.png")
                    val ok = runCatching { out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
                    bitmap.recycle()
                    if (ok) any = true
                }
                "se" -> {
                    val channels = asset.parts.map { (offset, size) ->
                        runCatching { Vag.decode(theme.read(offset, size)) }.getOrNull()
                    }
                    if (channels.isEmpty() || channels.any { it == null }) continue
                    if (Vag.writeWav(channels.filterNotNull(), File(File(folder, "sounds"), "$name.wav"))) any = true
                }
            }
        }
        return any
    }
}
