package com.armsx2.ui.xmb

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.armsx2.LibraryMusic
import com.armsx2.MenuSfx
import com.armsx2.PauseMusic
import com.armsx2.TrophySound
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx3.core.ARMSX3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The XMB's music: the Music column as the console's music player, and Music Settings.
 *
 * The column lists the tracks in the music folder (ARMSX3's data folder, music/), with the app's
 * own track first. Cross on a track plays it as the music behind the XMB, and on the one playing
 * pauses or plays it; Triangle makes it the pause menu's music, or deletes it. Import Music copies
 * audio files in with the system picker. Playing goes through LibraryMusic, as the library's music
 * always has, so the choice holds across the app.
 */
object XmbMusicLibrary {
    private val AUDIO = setOf("mp3", "ogg", "oga", "opus", "m4a", "aac", "flac", "wav", "mid", "midi", "3gp", "amr")

    fun folder(): File = File(ARMSX3.rootDirectory, "music").apply { mkdirs() }

    fun tracks(): List<File> =
        folder().listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Copy picked files into the folder; how many came in. IO thread. */
    fun import(context: Context, uris: List<Uri>): Int = uris.count { uri ->
        runCatching {
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
                ?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "track-${System.currentTimeMillis()}.mp3"
            var target = File(folder(), name)
            var n = 1
            while (target.exists()) target = File(folder(), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "mp3")}")
            context.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } } != null &&
                target.length() > 0
        }.getOrDefault(false)
    }
}

/**
 * The Music column. At home it plays the library's music; over a game, the pause menu's, which
 * is the music playing there, so picking a track changes what plays behind the in-game XMB.
 */
@Composable
fun xmbMusicColumn(controller: XmbController, inGame: Boolean = false): XmbColumn {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val full = "explore_plugin_full"
    val ok = fw(full, "msg_ok", "xmb.ok")
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    var refresh by remember { mutableIntStateOf(0) }
    val tracks by produceState(emptyList<File>(), refresh) {
        value = withContext(Dispatchers.IO) { XmbMusicLibrary.tracks() }
    }
    // The player this column drives: the library's music, or over a game the pause menu's.
    val playing = if (inGame) PauseMusic.enabled.value else LibraryMusic.enabled.value
    val current = if (inGame) PauseMusic.customName.value else LibraryMusic.customName.value
    fun play(on: Boolean) = if (inGame) PauseMusic.set(context, on) else LibraryMusic.set(context, on)
    fun useBuiltin() = if (inGame) PauseMusic.clearCustomTrack(context) else LibraryMusic.clearCustomTrack(context)
    fun useTrack(file: File) {
        if (inGame) PauseMusic.setCustomTrack(context, Uri.fromFile(file), file.name)
        else LibraryMusic.setCustomTrack(context, Uri.fromFile(file), file.name)
    }
    fun enabledNow() = if (inGame) PauseMusic.enabled.value else LibraryMusic.enabled.value
    val nowPlaying = fw(full, "msg_play", "action.play")
    val paused = fw(full, "msg_pause", "xmb.music.paused")
    val builtin = str("xmb.music.builtin")
    val importTitle = str("xmb.music.import")
    val importDesc = str("xmb.music.import.desc")
    val imported = str("xmb.music.imported")
    // Triangle offers the other player: the pause menu's at home, the library's over a game.
    val asOther = str(if (inGame) "xmb.music.asLibrary" else "xmb.music.asPause")
    val deleteTitle = str("action.delete")
    val deleteAsk = str("xmb.music.delete.ask")
    val trackIcon = XmbIcon("explore_category_music", "music_tex_default", "♪")
    val pausedIcon = XmbIcon("explore_category_music", "music_tex_album_default", "♪")
    val listIcon = XmbIcon("explore_category_music", "tex_playlist", "♫")
    val addToList = str("xmb.playlist.add")
    val removeFromList = str("xmb.playlist.remove")
    val deleteList = str("xmb.playlist.delete")
    val playAll = str("xmb.playlist.playAll")
    val trackCount = str("xmb.playlist.tracks")
    val emptyList = str("xmb.playlist.empty")
    val playlistsTitle = str("xmb.playlist.title")
    val playlistsDesc = str("xmb.playlist.desc")
    val newList = str("xmb.playlist.new")
    val listName = str("xmb.playlist.name")
    val addSongs = str("xmb.playlist.addSongs")
    val (onWord, offWord) = xmbOnOff()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { XmbPlaylists.load() } }
    // A playlist plays in order at home; over a game the pause menu's music has one track, the one picked.
    fun playList(files: List<File>, start: Int) {
        val file = files.getOrNull(start) ?: return
        if (inGame) {
            scope.launch {
                withContext(Dispatchers.IO) { useTrack(file) }
                if (!enabledNow()) play(true)
            }
        } else {
            LibraryMusic.playQueue(context, files, start)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) { XmbMusicLibrary.import(context, uris) }
            refresh++
            controller.tell(imported.format(n), ok)
        }
    }

    fun status(isCurrent: Boolean) = when {
        !isCurrent -> null
        playing -> nowPlaying
        else -> paused
    }

    val items = buildList {
        add(xmbMusicSettings())
        add(XmbNode.Action("music.import", importTitle, trackIcon, importDesc) { importer.launch(arrayOf("audio/*")) })
        val builtinCurrent = current == null
        add(
            XmbNode.Action("music.builtin", builtin, if (builtinCurrent && playing) trackIcon else pausedIcon, status(builtinCurrent)) {
                if (builtinCurrent) {
                    play(!playing)
                } else {
                    useBuiltin()
                    if (!playing) play(true)
                }
            },
        )
        // Playlists: New Playlist, then each one a list of its own: Play All, Add Songs (every song
        // with a switch), its songs, and Delete Playlist.
        fun playlist(name: String) =
                XmbNode.Folder("music.playlist.$name", name, listIcon, trackCount.format(XmbPlaylists.music.value[name].orEmpty().size), live = true) {
                    val files = XmbPlaylists.files(XmbPlaylists.Kind.Music, name)
                    buildList {
                        if (files.isEmpty()) add(XmbNode.Action("music.playlist.$name.none", emptyList, listIcon) {})
                        else add(XmbNode.Action("music.playlist.$name.all", playAll, trackIcon) { playList(files, 0) })
                        add(
                            XmbNode.Folder("music.playlist.$name.add", addSongs, listIcon, live = true) {
                                tracks.map { f ->
                                    val inList = { f.absolutePath in XmbPlaylists.music.value[name].orEmpty() }
                                    XmbNode.Setting(
                                        "music.playlist.$name.add.${f.name}", f.nameWithoutExtension, pausedIcon,
                                        XmbEditor.Toggle(inList) { on ->
                                            if (on) XmbPlaylists.add(XmbPlaylists.Kind.Music, name, f.absolutePath)
                                            else XmbPlaylists.remove(XmbPlaylists.Kind.Music, name, f.absolutePath)
                                        },
                                        { if (inList()) onWord else offWord },
                                    )
                                }
                            },
                        )
                        files.forEachIndexed { i, f ->
                            val on = current == f.name
                            add(
                                XmbNode.Action(
                                    "music.playlist.$name.$i.${f.name}", f.nameWithoutExtension,
                                    if (on && playing) trackIcon else pausedIcon, status(on),
                                    listOf(XmbOption(removeFromList) { XmbPlaylists.remove(XmbPlaylists.Kind.Music, name, f.absolutePath) }),
                                ) { if (on) play(!playing) else playList(files, i) },
                            )
                        }
                        add(XmbNode.Action("music.playlist.$name.delete", deleteList, listIcon) {
                            controller.ask(deleteAsk.format(name), yes, no, focusNo = true) { XmbPlaylists.delete(XmbPlaylists.Kind.Music, name) }
                        })
                    }
                }
        add(
            XmbNode.Folder("music.playlists", playlistsTitle, listIcon, playlistsDesc, live = true) {
                listOf<XmbNode>(
                    XmbNode.Action("music.playlists.new", newList, listIcon) {
                        XmbKeyboard.ask(listName) { typed -> typed.trim().takeIf { it.isNotEmpty() }?.let { XmbPlaylists.create(XmbPlaylists.Kind.Music, it) } }
                    },
                ) + XmbPlaylists.music.value.keys.map(::playlist)
            },
        )
        tracks.forEach { file ->
            val isCurrent = current == file.name
            val title = file.name.substringBeforeLast('.')
            val options = listOf(
                XmbOption(addToList) { XmbPlaylists.offer(controller, XmbPlaylists.Kind.Music, file.absolutePath) },
                XmbOption(asOther) {
                    scope.launch(Dispatchers.IO) {
                        if (inGame) {
                            LibraryMusic.setCustomTrack(context, Uri.fromFile(file), file.name)
                        } else {
                            PauseMusic.setCustomTrack(context, Uri.fromFile(file), file.name)
                            if (!PauseMusic.enabled.value) withContext(Dispatchers.Main) { PauseMusic.set(context, true) }
                        }
                    }
                },
                XmbOption(deleteTitle) {
                    controller.ask(deleteAsk.format(title), yes, no, focusNo = true) {
                        scope.launch {
                            withContext(Dispatchers.IO) { file.delete() }
                            if (isCurrent) useBuiltin()
                            refresh++
                        }
                    }
                },
            )
            add(
                XmbNode.Action("music.track.${file.name}", title, if (isCurrent && playing) trackIcon else pausedIcon, status(isCurrent), options) {
                    if (isCurrent) {
                        play(!playing)
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) { useTrack(file) }
                            if (!enabledNow()) play(true)
                        }
                    }
                },
            )
        }
    }
    return XmbColumn(
        key = "music",
        title = fw("xmb_ingame", "msg_music", "xmb.cat.music"),
        icon = XmbIcon("xmb_ingame", "tex_music", "♫"),
        items = items,
        // The track playing, as the console's player opens on it.
        defaultFocus = 3 + (tracks.indexOfFirst { it.name == current } + 1).coerceAtLeast(0),
    )
}

/**
 * Music Settings: the game previews (SND0), the music behind the XMB and the library, the pause
 * menu's music, the menu sounds and the trophy sound, each with its own track or pack, as the
 * settings screen has them.
 */
@Composable
fun xmbMusicSettings(): XmbNode {
    val context = LocalContext.current.applicationContext
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", "tex_music", "♪")
    val gameMusic = str("xmb.music.game")
    val gameMusicDesc = str("xmb.music.game.desc")
    val library = str("app.libraryMusic")
    val libraryDesc = str("app.libraryMusic.desc")
    val libraryVolume = str("app.libraryMusic.volume")
    val libraryChoose = str("app.libraryMusic.choose")
    val libraryReset = str("app.libraryMusic.reset")
    val libraryCurrent = str("app.libraryMusic.current")
    val libraryDefault = str("app.libraryMusic.default")
    val pause = str("app.pauseMusic")
    val pauseDesc = str("app.pauseMusic.desc")
    val pauseVolume = str("app.pauseMusic.volume")
    val pauseChoose = str("app.pauseMusic.choose")
    val pauseReset = str("app.pauseMusic.reset")
    val pauseCurrent = str("app.pauseMusic.current")
    val pauseDefault = str("app.pauseMusic.default")
    val sounds = str("app.menuSfx")
    val soundsDesc = str("app.menuSfx.desc")
    val soundsVolume = str("app.menuSfx.volume")
    val soundsChoose = str("app.menuSfx.choose")
    val soundsReset = str("app.menuSfx.reset")
    val soundsHint = str("app.menuSfx.hint")
    val trophy = str("app.trophySound")
    val trophyDesc = str("app.trophySound.desc")
    val trophyChoose = str("app.trophySound.choose")
    val trophyTest = str("app.trophySound.test")
    val trophyClear = str("app.trophySound.clear")
    val trophyVolume = str("app.trophySound.volume")
    val trophyCurrent = str("app.trophySound.current")
    val trophyNone = str("app.trophySound.none")

    fun named(uri: Uri) = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "Custom track"
    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LibraryMusic.setCustomTrack(context, uri, named(uri))
    }
    val pausePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PauseMusic.setCustomTrack(context, uri, named(uri))
    }
    val soundsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val n = MenuSfx.importFromTree(context, uri)
            android.widget.Toast.makeText(
                context,
                if (n > 0) I18n.get("app.menuSfx.imported").format(n) else I18n.get("app.menuSfx.importNone"),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    val trophyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && TrophySound.import(context, uri)) TrophySound.preview()
    }

    return XmbNode.Folder(
        key = "settings.music",
        title = fw("explore_category_sysconf", "msg_music_setting", "xmb.set.music"),
        icon = icon,
        sub = XmbText.find("explore_category_sysconf", "msg_music_setting_explanation"),
        live = true,
    ) {
        buildList {
            add(onOff("music.game", gameMusic, icon, on, off, gameMusicDesc, { XmbMode.gameMusic.value }) { XmbMode.setGameMusic(it) })
            add(onOff("music.library", library, icon, on, off, libraryDesc, { LibraryMusic.enabled.value }) { LibraryMusic.set(context, it) })
            if (LibraryMusic.enabled.value) {
                add(volume("music.library.volume", libraryVolume, icon, { LibraryMusic.volumePercent.value }) { LibraryMusic.setVolume(it) })
                val name = LibraryMusic.customName.value
                add(XmbNode.Action("music.library.choose", libraryChoose, icon, name?.let { libraryCurrent.format(it) } ?: libraryDefault) {
                    libraryPicker.launch(arrayOf("audio/*"))
                })
                if (name != null) add(XmbNode.Action("music.library.reset", libraryReset, icon) { LibraryMusic.clearCustomTrack(context) })
            }
            add(onOff("music.pause", pause, icon, on, off, pauseDesc, { PauseMusic.enabled.value }) { PauseMusic.set(context, it) })
            if (PauseMusic.enabled.value) {
                add(volume("music.pause.volume", pauseVolume, icon, { PauseMusic.volumePercent.value }) { PauseMusic.setVolume(it) })
                val name = PauseMusic.customName.value
                add(XmbNode.Action("music.pause.choose", pauseChoose, icon, name?.let { pauseCurrent.format(it) } ?: pauseDefault) {
                    pausePicker.launch(arrayOf("audio/*"))
                })
                if (name != null) add(XmbNode.Action("music.pause.reset", pauseReset, icon) { PauseMusic.clearCustomTrack(context) })
            }
            add(onOff("music.sfx", sounds, icon, on, off, soundsDesc, { MenuSfx.enabled.value }) { MenuSfx.set(context, it) })
            if (MenuSfx.enabled.value) {
                add(volume("music.sfx.volume", soundsVolume, icon, { MenuSfx.volumePercent.value }) { MenuSfx.setVolume(it) })
                add(XmbNode.Action("music.sfx.choose", soundsChoose, icon, soundsHint) { soundsPicker.launch(null) })
                if (MenuSfx.packName.value != null) add(XmbNode.Action("music.sfx.reset", soundsReset, icon) { MenuSfx.clear(context) })
            }
            val chosen = TrophySound.fileName.value
            add(XmbNode.Action("music.trophy.choose", "$trophy: $trophyChoose", icon, chosen?.let { trophyCurrent.format(it) } ?: trophyNone) {
                trophyPicker.launch(arrayOf("audio/*"))
            })
            if (chosen != null) {
                add(volume("music.trophy.volume", trophyVolume, icon, { TrophySound.volumePercent.value }) { TrophySound.setVolume(it) })
                add(XmbNode.Action("music.trophy.test", trophyTest, icon, trophyDesc) { TrophySound.preview() })
                add(XmbNode.Action("music.trophy.clear", trophyClear, icon) { TrophySound.clear() })
            }
        }
    }
}

private fun onOff(
    key: String,
    title: String,
    icon: XmbIcon,
    on: String,
    off: String,
    description: String?,
    get: () -> Boolean,
    set: (Boolean) -> Unit,
) = XmbNode.Setting(
    key = key,
    title = title,
    icon = icon,
    editor = XmbEditor.Toggle(get, set),
    value = { if (get()) on else off },
    description = description,
)

private fun volume(key: String, title: String, icon: XmbIcon, get: () -> Int, set: (Int) -> Unit) = XmbNode.Setting(
    key = key,
    title = title,
    icon = icon,
    editor = XmbEditor.Range(0f, 100f, 5f, { get().toFloat() }, { set(it.roundToInt()) }) { "${it.roundToInt()}%" },
    value = { "${get()}%" },
)
