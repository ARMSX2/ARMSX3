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
 * copies songs in with the system picker, Import Album a whole folder, covers and all; Add Folder
 * plays a folder's songs where they are, on the device or a drive, without copying them (its songs
 * join the albums, and the folder is listed with Remove from Music at its end). Playing goes
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
    fun currentSong() = if (inGame) null else LibraryMusic.current()
    // At home the song playing is the playlist's; over a game, the pause menu's one track.
    fun isCurrent(song: String, name: String) =
        if (inGame) currentName() == name else enabledNow() && currentSong() == song
    fun uriOf(song: String): Uri = if (LibraryMusic.isDocument(song)) Uri.parse(song) else Uri.fromFile(File(song))
    fun nameOf(song: String) = library?.byId?.get(song)?.name ?: LibraryMusic.songName(song)
    val nowPlaying = fw(full, "msg_play", "action.play")
    val paused = fw(full, "msg_pause", "xmb.music.paused")
    val builtin = str("xmb.music.builtin")
    val importTitle = str("xmb.music.import")
    val importDesc = str("xmb.music.import.desc")
    val imported = str("xmb.music.imported")
    val importAlbumTitle = str("xmb.music.importAlbum")
    val importAlbumDesc = str("xmb.music.importAlbum.desc")
    val addFolderTitle = str("xmb.video.addFolder")
    val addFolderDesc = str("xmb.music.addFolder.desc")
    val removeFolderTitle = str("xmb.music.removeFolder")
    val noTitles = fw(full, "msg_error_no_title", "xmb.video.none")
    val folderIcon = XmbIcon("explore_category_video", "item_tex_plain_folder", "📁")
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
    fun playList(songs: List<String>, start: Int) {
        val song = songs.getOrNull(start) ?: return
        if (inGame) {
            val name = nameOf(song)
            scope.launch {
                withContext(Dispatchers.IO) { PauseMusic.setCustomTrack(context, uriOf(song), name) }
                if (!enabledNow()) play(true)
            }
        } else {
            LibraryMusic.playQueue(context, songs, start)
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
    // Add Folder: the folder is kept and read where it is; one with no songs says so.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val songs = withContext(Dispatchers.IO) {
                XmbMusicLibrary.addFolder(context, uri)
                XmbMusicLibrary.scan(context).inFolder(uri).size
            }
            refresh++
            if (songs == 0) controller.tell(noTitles, ok, error = true)
        }
    }

    fun status(isCurrent: Boolean) = when {
        !isCurrent -> null
        playingNow() -> nowPlaying
        else -> paused
    }

    fun songIcon(song: String, name: String, current: Boolean): XmbIcon =
        library?.coverOf(song)?.let { XmbIcon(file = it, glyph = "♪") }
            ?: if (current && playingNow()) trackIcon else XmbIcon("explore_category_music", XmbMusicLibrary.formatIcon(name), "♪")

    // A song (its path, or its content Uri in an added folder): Cross plays it and on through [list]
    // from it (or opens Now Playing on the one playing), Triangle has its playlist and pause-menu
    // options, and Delete for a song in the music folder (an added folder's songs are the player's own).
    fun songNode(prefix: String, title: String, sub: String?, song: String, list: List<String>, i: Int, extra: List<XmbOption> = emptyList()): XmbNode.Action {
        val name = nameOf(song)
        val current = isCurrent(song, name)
        val options = extra + listOfNotNull(
            XmbOption(addToList) { XmbPlaylists.offer(controller, XmbPlaylists.Kind.Music, song) },
            XmbOption(asOther) {
                scope.launch(Dispatchers.IO) {
                    if (inGame) {
                        LibraryMusic.setCustomTrack(context, uriOf(song), name)
                    } else {
                        PauseMusic.setCustomTrack(context, uriOf(song), name)
                        if (!PauseMusic.enabled.value) withContext(Dispatchers.Main) { PauseMusic.set(context, true) }
                    }
                }
            },
            if (LibraryMusic.isDocument(song)) null else XmbOption(deleteTitle) {
                controller.ask(deleteAsk.format(title), yes, no, focusNo = true) {
                    scope.launch {
                        withContext(Dispatchers.IO) { File(song).delete() }
                        if (current) useBuiltin()
                        refresh++
                    }
                }
            },
        )
        return XmbNode.Action("$prefix.$i.$name", title, songIcon(song, name, current), status(current) ?: sub, options) {
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
        val nowSong = currentSong()?.takeIf { enabledNow() }
        if (nowSong != null) {
            val t = library?.byId?.get(nowSong)
            add(
                XmbNode.Action(
                    "music.now",
                    t?.title ?: nameOf(nowSong).substringBeforeLast('.'),
                    library?.coverOf(nowSong)?.let { XmbIcon(file = it, glyph = "▶") } ?: nowIcon,
                    listOfNotNull(nowPlayingTitle, t?.artist, if (playingNow()) null else paused).joinToString("   "),
                ) { openNowPlaying() },
            )
        }
        add(xmbMusicSettings())
        add(XmbNode.Action("music.import", importTitle, trackIcon, importDesc) { importer.launch(arrayOf("audio/*")) })
        add(XmbNode.Action("music.importAlbum", importAlbumTitle, albumIcon, importAlbumDesc) { albumImporter.launch(null) })
        if (!inGame) add(XmbNode.Action("music.addFolder", addFolderTitle, folderIcon, addFolderDesc) { folderPicker.launch(null) })
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
                    val songs = XmbPlaylists.songs(name) { library?.byId?.containsKey(it) == true }
                    buildList {
                        if (songs.isEmpty()) add(XmbNode.Action("music.playlist.$name.none", emptyList, listIcon) {})
                        else add(XmbNode.Action("music.playlist.$name.all", playAll, trackIcon) { playList(songs, 0) })
                        add(
                            XmbNode.Folder("music.playlist.$name.add", addSongs, listIcon, live = true) {
                                library?.all.orEmpty().map { t ->
                                    val inList = { t.id in XmbPlaylists.music.value[name].orEmpty() }
                                    XmbNode.Setting(
                                        "music.playlist.$name.add.${t.id}", t.title,
                                        library?.coverOf(t.id)?.let { XmbIcon(file = it, glyph = "♪") } ?: pausedIcon,
                                        XmbEditor.Toggle(inList) { on ->
                                            if (on) XmbPlaylists.add(XmbPlaylists.Kind.Music, name, t.id)
                                            else XmbPlaylists.remove(XmbPlaylists.Kind.Music, name, t.id)
                                        },
                                        { if (inList()) onWord else offWord },
                                    )
                                }
                            },
                        )
                        songs.forEachIndexed { i, song ->
                            val t = library?.byId?.get(song)
                            add(
                                songNode(
                                    "music.playlist.$name", t?.title ?: nameOf(song).substringBeforeLast('.'), t?.let(::trackSub), song, songs, i,
                                    listOf(XmbOption(removeFromList) { XmbPlaylists.remove(XmbPlaylists.Kind.Music, name, song) }),
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
        // Each added folder: its songs where they are, album by album, then Remove from Music.
        if (!inGame) XmbMusicLibrary.folders.value.forEach { f ->
            val inFolder = lib?.inFolder(f.uri).orEmpty()
            add(
                XmbNode.Folder("music.folder.${f.uri}", f.name, folderIcon, tracksCount.replace("%1", inFolder.size.toString()), live = true) {
                    val songs = inFolder.map { it.id }
                    inFolder.mapIndexed { i, t -> songNode("music.folder.${f.uri}", t.title, trackSub(t), t.id, songs, i) } +
                        XmbNode.Action("music.folder.${f.uri}.remove", removeFolderTitle, folderIcon) {
                            XmbMusicLibrary.removeFolder(context, f.uri)
                            controller.back()
                            refresh++
                        }
                },
            )
        }
        // Each album a folder under its cover: its songs in disc and track order.
        lib?.albums?.forEach { a ->
            val songs = a.tracks.map { it.id }
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
                        songNode("music.album.${a.key}", t.title, sub, t.id, songs, i)
                    }
                },
            )
        }
        val loose = lib?.loose.orEmpty()
        val looseSongs = loose.map { it.id }
        loose.forEachIndexed { i, t -> add(songNode("music.track", t.title, trackSub(t), t.id, looseSongs, i)) }
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
