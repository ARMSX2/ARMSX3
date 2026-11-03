package com.armsx2.ui.xmb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil.compose.AsyncImage
import com.armsx2.LibraryMusic
import com.armsx2.i18n.str
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.delay

/**
 * Now Playing: the music player, as a panel over the XMB.
 *
 * The song's cover with its reflection under it, its title, artist and album, where it stands in
 * its list, the time bar and the play mode, over the visualizer; along the bottom the console's
 * control panel, its buttons the firmware's own (Previous, Pause, Next, Stop, Repeat, Shuffle and
 * Volume). On the pad: Left and Right along the buttons and Cross to press one; Up to the time bar,
 * where Left and Right go back and on ten seconds; L1 and R1 the previous and next song; Square the
 * visualizer on its own, full size, and back. Every button and the time bar take a touch too, and
 * a touch on the cover opens the visualizer. Circle closes it and the music plays on behind the XMB.
 */
@Composable
internal fun XmbNowPlaying(close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val font = rememberXmbFont()
    val full = "explore_plugin_full"
    val music = "explore_category_music"
    val song = LibraryMusic.current()
    LibraryMusic.trackSerial.intValue
    val lib = XmbMusicLibrary.library.value
    val track = song?.let { lib?.byId?.get(it) }
    val album = song?.let { lib?.albumOf(it) }
    val cover = song?.let { lib?.coverOf(it) }
    val title = track?.title ?: song?.let { LibraryMusic.songName(it).substringBeforeLast('.') } ?: str("xmb.music.builtin")
    val artist = track?.artist ?: album?.artist
    val albumTitle = track?.album ?: album?.title
    val queue = LibraryMusic.queue.value
    val index = LibraryMusic.queueIndex.intValue
    val paused = LibraryMusic.userPaused.value || !LibraryMusic.enabled.value
    val repeat = LibraryMusic.repeat.value
    val shuffle = LibraryMusic.shuffle.value
    val chosen = LibraryMusic.chosen.value
    val volume = if (chosen) LibraryMusic.playerVolumePercent.value else LibraryMusic.volumePercent.value
    val noTrack = fw(full, "msg_error_no_nexttrack", "xmb.music.noTrack")
    val visualizerWord = str("xmb.music.visualizer")
    var visualizerOnly by remember { mutableStateOf(false) }
    // Which visualizer Square shows, kept for next time; Left and Right change it while it shows.
    var kind by remember {
        mutableStateOf(
            XmbVisualizerKind.entries.firstOrNull { it.id == com.armsx2.runtime.MainActivityRuntime.prefs.getString(VISUALIZER_KEY, null) }
                ?: XmbVisualizerKind.Waves,
        )
    }
    var kindChanged by remember { mutableIntStateOf(0) }
    var kindNameShown by remember { mutableStateOf(false) }
    LaunchedEffect(visualizerOnly, kindChanged) {
        kindNameShown = visualizerOnly
        if (visualizerOnly) {
            delay(3_500)
            kindNameShown = false
        }
    }
    fun switchVisualizer(step: Int) {
        val all = XmbVisualizerKind.entries
        kind = all[(all.indexOf(kind) + step + all.size) % all.size]
        com.armsx2.runtime.MainActivityRuntime.prefs.edit { putString(VISUALIZER_KEY, kind.id) }
        XmbSounds.play(XmbSounds.Sfx.CURSOR)
        kindChanged++
    }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    // Where the song is: read from the player four times a second, and run on between reads while
    // it plays, so the time bar and the visualizer move smoothly.
    val clock = remember { PlayClock() }
    LaunchedEffect(Unit) {
        while (true) {
            clock.sample(LibraryMusic.positionMs().toLong(), LibraryMusic.durationMs().toLong(), LibraryMusic.isPlaying())
            delay(250)
        }
    }
    // A song left playing here keeps the screensaver away: the visualizer is the thing to watch.
    LaunchedEffect(paused) {
        while (!paused) {
            com.armsx2.ui.home.LibraryScreensaver.reset()
            delay(1_000)
        }
    }
    val levels by produceState<XmbVisualizer.Levels?>(null, song, LibraryMusic.trackSerial.intValue) {
        value = null
        value = song?.let { XmbVisualizer.levels(context, it) }
    }
    // Valley is drawn from the firmware's canyon pack; without it, Valley shows the lines.
    val valleyReady by produceState(false) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { XmbValleyFirmware.load() != null }
    }
    val gaiaReady by produceState(false) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { XmbGaiaFirmware.available() }
    }
    val month = XmbOriginalColors.now()
    val accent = remember(month) {
        val c = Color(month.end[0], month.end[1], month.end[2])
        Color(c.red + (1f - c.red) * 0.35f, c.green + (1f - c.green) * 0.35f, c.blue + (1f - c.blue) * 0.35f)
    }

    fun skip(step: Int) {
        if (LibraryMusic.skip(context, step)) {
            XmbSounds.play(XmbSounds.Sfx.CURSOR)
        } else {
            XmbSounds.play(XmbSounds.Sfx.ERROR)
            message = noTrack
        }
    }
    fun seekBy(ms: Long) {
        val d = clock.duration
        if (d <= 0) return
        val to = (clock.now() + ms).coerceIn(0, d - 500)
        LibraryMusic.seekTo(to.toInt())
        clock.jump(to)
    }
    fun seekTo(fraction: Float) {
        val d = clock.duration
        if (d <= 0) return
        val to = (d * fraction.coerceIn(0f, 1f)).toLong().coerceAtMost(d - 500)
        LibraryMusic.seekTo(to.toInt())
        clock.jump(to)
    }

    val buttons = listOf(
        CpButton("prev", "tex_cp_prev", "⏮") { skip(-1) },
        CpButton("play", "tex_cp_pause", "⏯") { LibraryMusic.playPause(context) },
        CpButton("next", "tex_cp_next", "⏭") { skip(1) },
        CpButton("stop", "tex_cp_stop", "⏹") { LibraryMusic.stopSong() },
        CpButton("repeat", "tex_cp_repeat", "🔁") {
            LibraryMusic.setRepeat(LibraryMusic.Repeat.entries[(repeat.ordinal + 1) % LibraryMusic.Repeat.entries.size])
        },
        CpButton("shuffle", "tex_cp_shuffle", "🔀") { LibraryMusic.setShuffle(!shuffle) },
        CpButton("volume", "tex_cp_volume", "🔊") {
            // A step down each press, round to full again: as the console's panel steps it.
            val next = VOLUME_STEPS.firstOrNull { it < volume } ?: VOLUME_STEPS.first()
            if (chosen) LibraryMusic.setPlayerVolume(next) else LibraryMusic.setVolume(next)
        },
    )

    XmbPanelKeysEffect { key ->
        when (key) {
            XmbPanelKeys.Key.L1 -> skip(-1)
            XmbPanelKeys.Key.R1 -> skip(1)
            XmbPanelKeys.Key.Square -> visualizerOnly = !visualizerOnly
        }
        true
    }
    // The pad starts on Pause, as the console's panel opens on it; on the visualizer, the visualizer.
    LaunchedEffect(visualizerOnly) {
        val id = if (visualizerOnly) "xmb.music.vis" else "xmb.music.cp.play"
        var tries = 0
        while (!SettingsControllerNav.selectById(id) && tries++ < 10) withFrameNanos {}
    }

    // Its own ground, dark, with the month's colour coming up from the bottom: the XMB's lists do not
    // show through, and the visualizer's light reads against it.
    val ground = remember(month) {
        val c = Color(month.start[0], month.start[1], month.start[2])
        Brush.verticalGradient(listOf(Color(0xF4050507), Color(0xF0050507), Color(c.red * 0.22f, c.green * 0.22f, c.blue * 0.22f, 0.94f)))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(ground)) {
        val landscape = maxWidth >= maxHeight
        val u = if (landscape) minOf(maxWidth.value / 1232f, maxHeight.value / 620f) else maxWidth.value / 860f
        fun d(v: Float): Dp = (v * u).dp

        if (visualizerOnly && kind == XmbVisualizerKind.Waves) {
            XmbWavesVisualizer(levels, positionMs = { clock.now() }, playing = { !paused && clock.playing }, modifier = Modifier.fillMaxSize())
        } else if (visualizerOnly && kind == XmbVisualizerKind.Valley && valleyReady) {
            XmbValleyVisualizer(levels, positionMs = { clock.now() }, playing = { !paused && clock.playing }, modifier = Modifier.fillMaxSize())
        } else if (visualizerOnly && kind == XmbVisualizerKind.Gaia && gaiaReady) {
            XmbGaiaVisualizer(modifier = Modifier.fillMaxSize())
        } else {
            XmbVisualizerView(
                levels = levels,
                positionMs = { clock.now() },
                playing = { !paused && clock.playing },
                color = accent,
                strength = if (visualizerOnly) 1f else 0.8f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (visualizerOnly) maxHeight else maxHeight * 0.46f)
                    .align(if (visualizerOnly) Alignment.Center else Alignment.BottomCenter)
                    .padding(bottom = if (visualizerOnly) 0.dp else d(96f)),
            )
        }

        // The title row, as the XMB's panels have it: the player's icon and name, and Back. The
        // visualizer has the screen to itself, as the console's does.
        if (!visualizerOnly) Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(start = d(40f), end = d(10f), top = d(18f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XmbIconView(XmbIcon(music, "tex_now_playing", "▶"), d(40f))
            Spacer(Modifier.width(d(14f)))
            Text(str("xmb.music.nowPlaying"), style = xmbText(font, 23f * u), modifier = Modifier.weight(1f))
            // Back, a finger's worth round the words.
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = close)
                    .padding(horizontal = d(20f), vertical = d(14f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("○", style = xmbText(font, 16f * u).copy(color = CircleRed))
                Text(XmbText.get("xmb_ingame", "msg_help_back", ": " + str("xmb.back")), style = xmbText(font, 16f * u, 0.9f))
            }
        }

        AnimatedVisibility(!visualizerOnly, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize().padding(top = d(64f))) {
                val info: @Composable (Modifier) -> Unit = { mod ->
                    Column(mod) {
                        Text(title, style = xmbText(font, 34f * u), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (artist != null) {
                            Spacer(Modifier.height(d(6f)))
                            Text(artist, style = xmbText(font, 22f * u, 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (albumTitle != null) {
                            Spacer(Modifier.height(d(2f)))
                            Text(albumTitle, style = xmbText(font, 18f * u, 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (queue.size > 1) {
                            Spacer(Modifier.height(d(10f)))
                            Text("${index + 1} / ${queue.size}", style = xmbText(font, 16f * u, 0.7f))
                        }
                        Spacer(Modifier.height(d(26f)))
                        TimeBar(clock, u, font, onSeekBy = ::seekBy, onSeekTo = ::seekTo)
                        Spacer(Modifier.height(d(10f)))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10f))) {
                            XmbIconView(XmbIcon(music, if (paused) "tex_pause_status" else "tex_play_status", if (paused) "⏸" else "▶", shadow = false), d(26f))
                            if (shuffle) XmbIconView(XmbIcon(music, "tex_shuffle_status", "🔀", shadow = false), d(26f), width = d(34f))
                            when (repeat) {
                                LibraryMusic.Repeat.All -> XmbIconView(XmbIcon(music, "tex_repeat_status", "🔁", shadow = false), d(26f), width = d(43f))
                                LibraryMusic.Repeat.One -> Box(contentAlignment = Alignment.Center) {
                                    XmbIconView(XmbIcon(music, "tex_repeat_status", "🔂", shadow = false), d(26f), width = d(43f))
                                    XmbIconView(XmbIcon(music, "tex_1repeat_status", "1", shadow = false), d(13f), width = d(26f))
                                }
                                LibraryMusic.Repeat.Off -> {}
                            }
                        }
                    }
                }
                if (landscape) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = d(40f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(cover, d(300f)) { visualizerOnly = true }
                        Spacer(Modifier.width(d(44f)))
                        info(Modifier.weight(1f))
                    }
                } else {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = d(40f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Cover(cover, d(420f)) { visualizerOnly = true }
                        Spacer(Modifier.height(d(30f)))
                        info(Modifier.fillMaxWidth())
                    }
                }
                ControlPanel(buttons, u, paused, repeat, shuffle, volume, font, Modifier.align(Alignment.CenterHorizontally).padding(bottom = d(18f)))
            }
        }

        // Over the visualizer, the song as the console labels it there: bottom left, its icon, the
        // title, and "artist / album" under it. The pad's one control is the visualizer itself:
        // Left and Right change it, Cross plays and pauses. By touch, a tap brings its name up with
        // arrows either side (and Back): the arrows change it, and a tap anywhere else goes back.
        if (visualizerOnly) {
            Box(
                Modifier
                    .fillMaxSize()
                    .controllerFocusable(
                        "xmb.music.vis",
                        ring = false,
                        onConfirm = { LibraryMusic.playPause(context) },
                        onLeft = { switchVisualizer(-1) },
                        onRight = { switchVisualizer(1) },
                    )
                    // A tap with the name up goes back to the player, without it brings the name
                    // up; a swipe moves Valley's and Gaia's presets on, as the right stick does (for
                    // Valley: across another of the group, up or down another group). Told apart by
                    // how far the touch went, so a slow frame that brings only its start and its end
                    // is still a swipe.
                    .pointerInput(kind) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val nameUp = kindNameShown
                            var end = down.position
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                end = change.position
                                if (!change.pressed) break
                            }
                            val dx = end.x - down.position.x
                            val dy = end.y - down.position.y
                            val far = 60.dp.toPx()
                            val across = kotlin.math.abs(dx) >= kotlin.math.abs(dy) && kotlin.math.abs(dx) > far
                            val upDown = !across && kotlin.math.abs(dy) > far
                            when {
                                !across && !upDown -> if (nameUp) visualizerOnly = false else kindChanged++
                                kind == XmbVisualizerKind.Gaia -> XmbGaiaPresets.flick()
                                kind == XmbVisualizerKind.Valley && across -> XmbValleyPresets.flick(if (dx < 0) -1 else 1, 0)
                                kind == XmbVisualizerKind.Valley -> XmbValleyPresets.flick(0, if (dy < 0) -1 else 1)
                            }
                        }
                    },
            )
            AnimatedVisibility(kindNameShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = d(12f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "◀",
                        style = xmbText(font, 22f * u, 0.95f),
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { switchVisualizer(-1) }
                            .padding(horizontal = d(22f), vertical = d(18f)),
                    )
                    Text(kind.title, style = xmbText(font, 22f * u, 0.95f))
                    Text(
                        "▶",
                        style = xmbText(font, 22f * u, 0.95f),
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { switchVisualizer(1) }
                            .padding(horizontal = d(22f), vertical = d(18f)),
                    )
                }
            }
            AnimatedVisibility(kindNameShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(top = d(12f), end = d(12f))) {
                Row(
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { visualizerOnly = false }
                        .padding(horizontal = d(18f), vertical = d(18f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("○", style = xmbText(font, 16f * u).copy(color = CircleRed))
                    Text(XmbText.get("xmb_ingame", "msg_help_back", ": " + str("xmb.back")), style = xmbText(font, 16f * u, 0.9f))
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = d(70f), bottom = d(40f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = cover?.let { XmbIcon(file = it, glyph = "♪") } ?: XmbIcon(music, "music_tex_default", "♪")
                XmbIconView(icon, d(52f))
                Spacer(Modifier.width(d(12f)))
                Column {
                    Text(title, style = xmbText(font, 21f * u), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = listOfNotNull(artist, albumTitle).joinToString(" / ")
                    if (line.isNotEmpty()) Text(line, style = xmbText(font, 15f * u, 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Square's hint, bottom right, where the XMB's own hints sit.
        if (!visualizerOnly) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = d(10f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { visualizerOnly = !visualizerOnly }
                .padding(start = d(20f), end = d(20f), top = d(14f), bottom = d(10f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("□", style = xmbText(font, 16f * u).copy(color = SquarePink))
            Text(" $visualizerWord", style = xmbText(font, 16f * u, 0.9f))
        }
        message?.let {
            Text(
                it,
                style = xmbText(font, 20f * u),
                modifier = Modifier.align(Alignment.Center).padding(d(20f)),
            )
        }
    }
}

private val SquarePink = Color(0xFFE48BD6)
private val CircleRed = Color(0xFFE85C5C)
private const val VISUALIZER_KEY = "xmb.music.visualizer"
private val VOLUME_STEPS = listOf(100, 75, 50, 25)

private class CpButton(val id: String, val texture: String, val glyph: String, val press: () -> Unit)

/** The cover, the console's way: framed in white, on its own reflection. Tapping it plays or pauses. */
@Composable
private fun Cover(cover: java.io.File?, size: Dp, onTap: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val picture: @Composable (Modifier) -> Unit = { mod ->
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = mod)
            } else {
                Box(mod, contentAlignment = Alignment.Center) {
                    XmbIconView(XmbIcon("explore_category_music", "music_tex_album_default", "♪"), size * 0.6f)
                }
            }
        }
        picture(
            Modifier
                .size(size)
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.55f), shape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
        // The reflection: the picture upside down, fading out a third of the way down.
        picture(
            Modifier
                .padding(top = 3.dp)
                .size(size, size * 0.3f)
                .clip(shape)
                .graphicsLayer {
                    scaleY = -1f
                    alpha = 0.32f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), blendMode = BlendMode.DstIn)
                },
        )
    }
}

/**
 * The time bar: the time gone, the bar, the song's length. Selected with Up from the buttons, Left
 * and Right go back and on ten seconds; a tap or a drag along it goes there.
 */
@Composable
private fun TimeBar(clock: PlayClock, u: Float, font: androidx.compose.ui.text.font.FontFamily, onSeekBy: (Long) -> Unit, onSeekTo: (Float) -> Unit) {
    val tick = clock.tick
    val selected = SettingsControllerNav.isSelected("xmb.music.seek")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(xmbClockTime(clock.now()), style = xmbText(font, 16f * u, 0.9f), modifier = Modifier.width((64f * u).dp))
        Box(
            Modifier
                .width((440f * u).dp)
                .height((24f * u).dp)
                .controllerFocusable("xmb.music.seek", ring = false, onLeft = { onSeekBy(-10_000) }, onRight = { onSeekBy(10_000) })
                .pointerInput(Unit) { detectTapGestures { onSeekTo(it.x / size.width) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSeekTo(change.position.x / size.width) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = if (clock.duration > 0) (clock.now().toFloat() / clock.duration).coerceIn(0f, 1f) else 0f
            tick.hashCode()
            Canvas(Modifier.fillMaxSize()) {
                val h = (if (selected) 5f else 3.5f) * u * density
                val y = size.height / 2f - h / 2f
                drawRoundRect(Color.White.copy(alpha = 0.24f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(Color.White.copy(alpha = if (selected) 1f else 0.85f), Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h / 2))
                if (selected) {
                    drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(-4f * density, 0f), Size(size.width + 8f * density, size.height), CornerRadius(size.height / 2))
                }
            }
            // The firmware's slider for the thumb.
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.001f)), contentAlignment = Alignment.CenterEnd) {
                XmbIconView(XmbIcon("explore_category_music", "tex_sb_slider", "", shadow = false), (12f * u).dp, width = (34f * u).dp, modifier = Modifier.padding(start = 0.dp))
            }
        }
        Text(
            xmbClockTime(clock.duration),
            style = xmbText(font, 16f * u, 0.9f),
            modifier = Modifier.padding(start = (14f * u).dp),
        )
    }
}

/** The console's control panel: its button pictures in a row, the selected one lit from behind. */
@Composable
private fun ControlPanel(
    buttons: List<CpButton>,
    u: Float,
    paused: Boolean,
    repeat: LibraryMusic.Repeat,
    shuffle: Boolean,
    volume: Int,
    font: androidx.compose.ui.text.font.FontFamily,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((6f * u).dp)) {
        for (b in buttons) {
            val id = "xmb.music.cp.${b.id}"
            val selected = SettingsControllerNav.isSelected(id)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size((96f * u).dp, (66f * u).dp)
                        .controllerFocusable(id, ring = false, onConfirm = b.press)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            SettingsControllerNav.selectById(id)
                            b.press()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) XmbIconView(XmbIcon("explore_category_music", "tex_cp_focus", "", shadow = false), (66f * u).dp, width = (100f * u).dp)
                    XmbIconView(XmbIcon("explore_category_music", b.texture, b.glyph, shadow = false), (58f * u).dp, width = (87f * u).dp)
                }
                // Under a button, what it is set to: the play mode's state, the volume.
                val state = when (b.id) {
                    "play" -> if (paused) "▶" else null
                    "repeat" -> when (repeat) {
                        LibraryMusic.Repeat.Off -> null
                        LibraryMusic.Repeat.All -> "●"
                        LibraryMusic.Repeat.One -> "1"
                    }
                    "shuffle" -> if (shuffle) "●" else null
                    "volume" -> "$volume%"
                    else -> null
                }
                Text(state ?: "", style = xmbText(font, 13f * u, 0.9f), modifier = Modifier.height((18f * u).dp))
            }
        }
    }
}

/** The player's position, read now and then and run on in between while it plays. */
private class PlayClock {
    private var position = 0L
    private var at = 0L
    var duration by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Goes up four times a second, for the time shown. */
    var tick by mutableIntStateOf(0)
        private set

    fun sample(position: Long, duration: Long, playing: Boolean) {
        this.position = position
        at = android.os.SystemClock.uptimeMillis()
        this.duration = duration
        this.playing = playing
        tick++
    }

    fun jump(to: Long) {
        position = to
        at = android.os.SystemClock.uptimeMillis()
        tick++
    }

    fun now(): Long {
        val run = if (playing) android.os.SystemClock.uptimeMillis() - at else 0L
        return (position + run).coerceIn(0L, duration.coerceAtLeast(0L))
    }
}
