package com.armsx2.ui.xmb

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The XMB's music: the Music column as the console's music player, and Music Settings.
 *
 * The column lists the music folder (ARMSX3's data folder, music/; see [XmbMusicLibrary]) as the
 * console does: its albums as folders under their covers, then the songs that belong to no album,
 * with the app's own track and the playlists before them. Cross on a song plays it and the rest of
 * its album or list after it, as the music behind the XMB; on the song playing it opens Now Playing,
 * the player with its controls and visualizer, which also heads the column while a song is on.
 * Triangle adds a song to a playlist, makes it the pause menu's music, or deletes it. Import Music
 * copies songs in with the system picker, Import Album a whole folder, covers and all. Playing goes
 * through LibraryMusic, as the library's music always has, so the choice holds across the app.
 *
 * Over a game the column is the pause menu's music instead: the song picked there is the one the
 * pause menu plays, on its own.
 */
@Composable
fun xmbMusicColumn(controller: XmbController, inGame: Boolean = false, shown: Boolean = false): XmbColumn {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val full = "explore_plugin_full"
    val ok = fw(full, "msg_ok", "xmb.ok")
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    var refresh by remember { mutableIntStateOf(0) }
    // Coming into view, the column reads the music folder again (only songs that changed are read
    // afresh): songs copied in from elsewhere show.
    LaunchedEffect(shown) { if (shown) refresh++ }
    val library by produceState(XmbMusicLibrary.library.value, refresh) {
        value = withContext(Dispatchers.IO) { XmbMusicLibrary.scan(context) }
    }
    // The player this column drives: the library's music, or over a game the pause menu's. Its state
    // is read where it is used rather than once here: an open album's songs are built as its list
    // is drawn, not when this column is, and have to follow the player as it moves on.
    fun play(on: Boolean) = if (inGame) PauseMusic.set(context, on) else LibraryMusic.set(context, on)
    fun useBuiltin() = if (inGame) PauseMusic.clearCustomTrack(context) else LibraryMusic.clearCustomTrack(context)
    fun enabledNow() = if (inGame) PauseMusic.enabled.value else LibraryMusic.enabled.value
    fun playingNow() = enabledNow() && (inGame || !LibraryMusic.userPaused.value)
    fun currentName() = if (inGame) PauseMusic.customName.value else LibraryMusic.customName.value
    fun currentFile() = if (inGame) null else LibraryMusic.current()
    // At home the song playing is the playlist's; over a game, the pause menu's one track.
    fun isCurrent(file: File) =
        if (inGame) currentName() == file.name else enabledNow() && currentFile()?.absolutePath == file.absolutePath
    val nowPlaying = fw(full, "msg_play", "action.play")
    val paused = fw(full, "msg_pause", "xmb.music.paused")
    val builtin = str("xmb.music.builtin")
    val importTitle = str("xmb.music.import")
    val importDesc = str("xmb.music.import.desc")
    val imported = str("xmb.music.imported")
    val importAlbumTitle = str("xmb.music.importAlbum")
    val importAlbumDesc = str("xmb.music.importAlbum.desc")
    val nowPlayingTitle = str("xmb.music.nowPlaying")
    val variousArtists = fw(full, "msg_various_artists", "xmb.music.variousArtists")
    val tracksCount = fw(full, "msg_tracks_count_value", "xmb.music.tracks")
    // Triangle offers the other player: the pause menu's at home, the library's over a game.
    val asOther = str(if (inGame) "xmb.music.asLibrary" else "xmb.music.asPause")
    val deleteTitle = str("action.delete")
    val deleteAsk = str("xmb.music.delete.ask")
    val trackIcon = XmbIcon("explore_category_music", "music_tex_default", "♪")
    val pausedIcon = XmbIcon("explore_category_music", "music_tex_album_default", "♪")
    val albumIcon = XmbIcon("explore_category_music", "music_tex_album_default", "♪")
    val nowIcon = XmbIcon("explore_category_music", "tex_now_playing", "▶")
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

    fun openNowPlaying() {
        controller.panel = XmbNode.Panel("music.nowplaying", nowPlayingTitle, nowIcon, fullscreen = true) { close -> XmbNowPlaying(close) }
    }

    // Over a game a song is the pause menu's one track; at home a list plays on from the song picked.
    fun playList(files: List<File>, start: Int) {
        val file = files.getOrNull(start) ?: return
        if (inGame) {
            scope.launch {
                withContext(Dispatchers.IO) { PauseMusic.setCustomTrack(context, Uri.fromFile(file), file.name) }
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
            controller.tell(imported.format(n), ok, error = n == 0)
        }
    }
    val albumImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) { XmbMusicLibrary.importFolder(context, uri) }
            refresh++
            controller.tell(imported.format(n), ok, error = n == 0)
        }
    }

    fun status(isCurrent: Boolean) = when {
        !isCurrent -> null
        playingNow() -> nowPlaying
        else -> paused
    }

    fun songIcon(file: File, current: Boolean): XmbIcon =
        library?.coverOf(file)?.let { XmbIcon(file = it, glyph = "♪") }
            ?: if (current && playingNow()) trackIcon else XmbIcon("explore_category_music", XmbMusicLibrary.formatIcon(file), "♪")

    // A song: Cross plays it and on through [list] from it (or opens Now Playing on the one playing),
    // Triangle has its playlist, pause-menu and delete options.
    fun songNode(prefix: String, title: String, sub: String?, file: File, list: List<File>, i: Int, extra: List<XmbOption> = emptyList()): XmbNode.Action {
        val current = isCurrent(file)
        val options = extra + listOf(
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
                        if (current) useBuiltin()
                        refresh++
                    }
                }
            },
        )
        return XmbNode.Action("$prefix.$i.${file.name}", title, songIcon(file, current), status(current) ?: sub, options) {
            when {
                inGame && current -> play(!playingNow())
                !inGame && current -> openNowPlaying()
                else -> playList(list, i)
            }
        }
    }

    fun trackSub(t: XmbMusicLibrary.Track) = listOfNotNull(t.artist, xmbLength(t.durationMs).ifEmpty { null }).joinToString("   ")

    val items = buildList {
        // Now Playing heads the column while a song of the player's is on, as the console's does.
        val nowFile = currentFile()?.takeIf { enabledNow() }
        if (nowFile != null) {
            val t = library?.byPath?.get(nowFile.absolutePath)
            add(
                XmbNode.Action(
                    "music.now",
                    t?.title ?: nowFile.nameWithoutExtension,
                    library?.coverOf(nowFile)?.let { XmbIcon(file = it, glyph = "▶") } ?: nowIcon,
                    listOfNotNull(nowPlayingTitle, t?.artist, if (playingNow()) null else paused).joinToString("   "),
                ) { openNowPlaying() },
            )
        }
        add(xmbMusicSettings())
        add(XmbNode.Action("music.import", importTitle, trackIcon, importDesc) { importer.launch(arrayOf("audio/*")) })
        add(XmbNode.Action("music.importAlbum", importAlbumTitle, albumIcon, importAlbumDesc) { albumImporter.launch(null) })
        val builtinCurrent = currentName() == null && (inGame || LibraryMusic.queue.value.isEmpty())
        add(
            XmbNode.Action("music.builtin", builtin, if (builtinCurrent && playingNow()) trackIcon else pausedIcon, status(builtinCurrent)) {
                when {
                    builtinCurrent && inGame -> play(!playingNow())
                    builtinCurrent && !enabledNow() -> play(true)
                    builtinCurrent -> LibraryMusic.playPause(context)
                    else -> {
                        useBuiltin()
                        if (!enabledNow()) play(true)
                    }
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
                                XmbMusicLibrary.tracks().map { f ->
                                    val inList = { f.absolutePath in XmbPlaylists.music.value[name].orEmpty() }
                                    XmbNode.Setting(
                                        "music.playlist.$name.add.${f.absolutePath}", library?.byPath?.get(f.absolutePath)?.title ?: f.nameWithoutExtension,
                                        library?.coverOf(f)?.let { XmbIcon(file = it, glyph = "♪") } ?: pausedIcon,
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
                            val t = library?.byPath?.get(f.absolutePath)
                            add(
                                songNode(
                                    "music.playlist.$name", t?.title ?: f.nameWithoutExtension, t?.let(::trackSub), f, files, i,
                                    listOf(XmbOption(removeFromList) { XmbPlaylists.remove(XmbPlaylists.Kind.Music, name, f.absolutePath) }),
                                ),
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
        val lib = library
        // Each album a folder under its cover: its songs in disc and track order.
        lib?.albums?.forEach { a ->
            val files = a.tracks.map { it.file }
            add(
                XmbNode.Folder(
                    "music.album.${a.key}",
                    a.title,
                    a.cover?.let { XmbIcon(file = it, glyph = "♪") } ?: albumIcon,
                    listOf(a.artist ?: variousArtists, tracksCount.replace("%1", a.tracks.size.toString())).joinToString("   "),
                    live = true,
                ) {
                    a.tracks.mapIndexed { i, t ->
                        // An album of one artist names the length; of several, whose song it is too.
                        val sub = if (a.artist != null) xmbLength(t.durationMs).ifEmpty { null } else trackSub(t)
                        songNode("music.album.${a.key}", t.title, sub, t.file, files, i)
                    }
                },
            )
        }
        val loose = lib?.loose.orEmpty()
        val looseFiles = loose.map { it.file }
        loose.forEachIndexed { i, t -> add(songNode("music.track", t.title, trackSub(t), t.file, looseFiles, i)) }
    }
    return XmbColumn(
        key = "music",
        title = fw("xmb_ingame", "msg_music", "xmb.cat.music"),
        icon = XmbIcon("xmb_ingame", "tex_music", "♫"),
        items = items,
        // Now Playing, or the app's own track, as the console's player opens on what is playing.
        defaultFocus = items.indexOfFirst { it.key == "music.now" }.takeIf { it >= 0 } ?: items.indexOfFirst { it.key == "music.builtin" }.coerceAtLeast(0),
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
