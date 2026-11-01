package com.armsx2.ui.xmb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import coil.compose.AsyncImage
import com.armsx2.EnglishTitles
import com.armsx2.GameInfo
import com.armsx2.LibraryMusic
import com.armsx2.PlayTime
import com.armsx2.R
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.navigation.AppRoute
import com.armsx2.navigation.DiscordUrl
import com.armsx2.navigation.GithubUrl
import com.armsx2.navigation.UiNavigator
import com.armsx2.navigation.WebsiteUrl
import com.armsx2.navigation.openExternalUrl
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.home.HomeUiState
import com.armsx2.ui.home.HomeViewModel
import com.armsx2.ui.settings.controllerFocusable
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * XMB mode's home: the app laid out as the console's XMB, with the boot animation when it comes up.
 *
 * The columns are the console's, left to right, with its words and icons from the firmware: Users,
 * Settings, Photo, Music, Game and Network. ARMSX3 stands where PlayStation Network was, with
 * ARMSX3's mark for the console's blue logo, and RPCN where Friends was. Video and TV/Video
 * Services have nothing of ARMSX3's to show and are left out.
 */
object XmbHomeState {
    private const val ColumnKey = "xmb.home.column"
    private const val FocusKey = "xmb.home.focus"

    /** One controller for the run, so leaving for a screen and coming back keeps the place. */
    val controller: XmbController by lazy {
        val prefs = MainActivityRuntime.prefs
        val focus = runCatching {
            val json = JSONObject(prefs.getString(FocusKey, "{}") ?: "{}")
            json.keys().asSequence().associateWith { json.optInt(it) }
        }.getOrDefault(emptyMap())
        XmbController(prefs.getString(ColumnKey, "game"), focus)
    }

    fun save() {
        val c = controller
        val json = JSONObject()
        c.focus.forEach { (k, v) -> json.put(k, v) }
        MainActivityRuntime.prefs.edit {
            putString(ColumnKey, c.currentColumn()?.key)
            putString(FocusKey, json.toString())
        }
    }
}

@Composable
fun XmbHome(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onGameMenu: (GameInfo) -> Unit,
) {
    val context = LocalContext.current
    val controller = XmbHomeState.controller
    LaunchedEffect(Unit) {
        // A search typed in the library would otherwise hide games here, with no search box to clear it.
        if (viewModel.state.value.query.isNotEmpty()) viewModel.setQuery("")
    }
    // The firmware's words, and the theme's icons and sounds or the firmware's, read again whenever
    // the language or the theme changes.
    val language = I18n.current
    val theme = XmbTheme.entry()
    LaunchedEffect(language, theme?.folder) { XmbFirmwareReady.load(context.applicationContext, language, theme) }
    LaunchedEffect(controller) {
        snapshotFlow { controller.column to controller.focus.toMap() }.collect { XmbHomeState.save() }
    }

    val columns = xmbHomeColumns(state, viewModel, controller, onGameMenu)
    var bootT by remember { mutableFloatStateOf(if (XmbMode.booting.value) 0f else XmbBootTimeline.END) }
    val reveal = if (XmbMode.booting.value) XmbBootTimeline.reveal(bootT) else 1f

    Box(Modifier.fillMaxSize()) {
        XmbView(
            controller = controller,
            columns = columns,
            reveal = reveal,
            backdrop = { XmbGameBackdrop(it) },
        )
        XmbSnd0Preview(controller)
        if (XmbMode.booting.value) {
            XmbBoot(
                onProgress = { bootT = it },
                onFinished = {
                    bootT = XmbBootTimeline.END
                    XmbMode.bootFinished()
                },
            )
        }
    }
}

@Composable
private fun xmbHomeColumns(
    state: HomeUiState,
    viewModel: HomeViewModel,
    controller: XmbController,
    onGameMenu: (GameInfo) -> Unit,
): List<XmbColumn> {
    val context = LocalContext.current
    val forceEn = EnglishTitles.enabled.value
    fun go(route: AppRoute) = UiNavigator.navigate(route)
    val full = "explore_plugin_full"
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")

    val turnOffAsk = fw(full, "msg_turnoff_system_ask", "xmb.turnOff.ask")
    val exitAsk = str("xmb.exit.ask")
    // The ARMSX3 mark, white like the console's item icons, and on a round badge where the console's
    // PlayStation Network badge stood.
    val markIcon = remember { XmbWordmark.markIcon(192).asImageBitmap() }
    val markBadge = remember { XmbWordmark.markBadge(192).asImageBitmap() }
    val users = XmbColumn(
        key = "users",
        title = fw("xmb_ingame", "msg_user", "xmb.cat.users"),
        icon = XmbIcon("xmb_ingame", "tex_user", "👤"),
        items = listOf(
            XmbNode.Action("users.off", fw(full, "msg_power_off_console2", "xmb.turnOff"), XmbIcon("explore_category_user", "tex_power_off", "⏻")) {
                controller.ask(turnOffAsk, yes, no) { MainActivityRuntime.exitApp() }
            },
            XmbNode.Action("users.exit", str("xmb.exit"), XmbIcon(bitmap = markIcon), str("xmb.exit.desc")) {
                controller.ask(exitAsk, yes, no) { XmbShutdown.start() }
            },
        ),
        defaultFocus = 1,
    )

    val settings = XmbColumn(
        key = "settings",
        title = fw("xmb_ingame", "msg_setting", "xmb.cat.settings"),
        icon = XmbIcon("xmb_ingame", "tex_sysconf", "⚙"),
        items = xmbSettingsItems(scope = com.armsx2.config.SettingsScope.Global, serial = null),
    )

    val photo = xmbPhotoColumn(controller)
    val music = xmbMusicColumn(controller)

    val utilities = listOf(
        // The PKG/Data Manager as XMB lists: packages and licences from storage, game updates and DLC.
        xmbPackagesNode(),
        XmbNode.Action("game.file", str("action.launchGame"), XmbIcon("explore_category_game", "item_tex_plain_folder", "📂")) {
            MainActivityRuntime.promptLaunchGame()
        },
        XmbNode.Action("game.xmb", str("bios.boot.title"), XmbIcon("explore_category_game", "item_tex_ps3logo", "▶")) {
            MainActivityRuntime.startBios()
        },
        xmbTrophiesNode(inGame = false),
        xmbSavesNode(),
        xmbModsNode(),
    )
    // Where each game's media is, worked out off the main thread: a stat or three per game adds up
    // over a whole library on an SD card.
    val dirs by produceState<Map<String, File?>>(emptyMap(), state.visibleGames) {
        value = withContext(Dispatchers.IO) {
            state.visibleGames.associate { it.uri.toString() to XmbMedia.dirOf(context, it) }
        }
    }
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val lastPlayed = remember(locale) { lastPlayedFormat(context, locale) }

    // A game's own settings as the Settings column has them, from its options or with Square, and
    // composed only once one is asked for.
    var settingsGame by remember { mutableStateOf<GameInfo?>(null) }
    val gameSettings = settingsGame?.let { g -> xmbSettingsItems(com.armsx2.config.SettingsScope.Game, g.serial, game = g) }
    val gameSettingsList = remember { mutableStateOf<List<XmbNode>>(emptyList()) }
    androidx.compose.runtime.SideEffect { gameSettingsList.value = gameSettings.orEmpty() }
    val settingsIcon = XmbIcon("explore_category_sysconf", "tex_sett", "\u2699")
    fun openSettings(game: GameInfo) {
        settingsGame = game
        controller.open(
            XmbNode.Folder("game.settings.${game.uri}", game.displayTitle(forceEn), settingsIcon, live = true) { gameSettingsList.value },
        )
    }

    // The console's options for a game (Triangle), as its side menu: play it, its settings, clear
    // what it has compiled, or pin it to the home screen.
    val playWord = str("action.play")
    val settingsWord = str("action.settings")
    val clearCacheWord = str("games.clearCache")
    val clearCacheAsk = str("perf.clearPpuCache.confirmGame")
    val cacheStopFirst = str("perf.clearCache.stopFirst")
    val cacheNoSerial = str("games.clearCache.noSerial")
    val cacheEmpty = str("games.clearCache.empty")
    val cacheDone = str("perf.clearCache.done")
    val deleteWord = str("action.delete")
    val cancel = str("action.cancel")
    val ok = str("action.ok")
    val addHome = str("games.addToHome")
    val addHomeFailed = str("games.addToHome.unsupported")
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun clearCache(game: GameInfo) {
        if (MainActivityRuntime.eState.value != com.armsx2.EmuState.STOPPED) {
            controller.tell(cacheStopFirst, ok)
            return
        }
        controller.ask(clearCacheAsk, deleteWord, cancel, focusNo = true) {
            val dir = com.armsx2.cache.titleCacheDir(context, game.serial)
            val message = when {
                MainActivityRuntime.eState.value != com.armsx2.EmuState.STOPPED -> cacheStopFirst
                dir == null -> cacheNoSerial
                !dir.isDirectory -> cacheEmpty
                else -> {
                    val (count, bytes) = com.armsx2.cache.clearRecompilerCache(dir, spuOnly = false)
                    if (count > 0) cacheDone.format(count, com.armsx2.cache.formatBytes(bytes)) else cacheEmpty
                }
            }
            controller.tell(message, ok, error = false)
        }
    }
    fun gameOptions(game: GameInfo, title: String) {
        controller.options = XmbController.Options(
            title,
            listOf(
                XmbOption(playWord) { viewModel.launch(game) },
                XmbOption(settingsWord) { openSettings(game) },
                XmbOption(clearCacheWord) { clearCache(game) },
                XmbOption(addHome) {
                    scope.launch { if (!com.armsx2.HomeShortcuts.pin(context, game)) controller.tell(addHomeFailed, ok) }
                },
            ),
        )
    }

    val games = state.visibleGames.map { game ->
        val dir = dirs[game.uri.toString()]
        // Under the focused game, when it was last played, as the console shows it.
        val played = PlayTime.lastPlayedMillis(game.serial)
        XmbNode.Game(
            game = game,
            title = game.displayTitle(forceEn),
            icon = XmbIcon(model = XmbMedia.icon0(game, dir), glyph = "🎮"),
            sub = if (played > 0L) lastPlayed.format(java.util.Date(played)) else game.serial?.takeIf { it.isNotBlank() },
            onLaunch = { viewModel.launch(game) },
            onOptions = { gameOptions(game, game.displayTitle(forceEn)) },
            onSettings = { openSettings(game) },
            mediaDir = dir,
        )
    }
    // The tools have a column of their own, before the games, so the Game column is the games.
    val tools = XmbColumn(
        key = "tools",
        title = str("xmb.cat.tools"),
        icon = XmbIcon("explore_category_game", "item_tex_ps3util", "🧰"),
        items = utilities,
    )
    val game = XmbColumn(
        key = "game",
        title = fw("xmb_ingame", "msg_game", "xmb.cat.game"),
        icon = XmbIcon("xmb_ingame", "tex_game", "🎮"),
        items = games.ifEmpty {
            listOf(XmbNode.Action("game.none", str("xmb.game.none"), XmbIcon("explore_category_game", "item_tex_ps3game_folder", "🎮"), str("xmb.game.none.desc")) {})
        },
    )

    val network = XmbColumn(
        key = "network",
        title = fw("xmb_ingame", "msg_net", "xmb.cat.network"),
        icon = XmbIcon("xmb_ingame", "tex_network", "🌐"),
        items = listOf(
            com.armsx2.remoteplay.xmbRemotePlayJoin(),
            XmbNode.Action("net.browser", fw(full, "msg_browser", "xmb.browser"), XmbIcon("explore_category_network", "tex_browser", "🌐"), WebsiteUrl.removePrefix("https://").trimEnd('/')) {
                openExternalUrl(context, WebsiteUrl)
            },
        ),
    )

    // Where PlayStation Network was: ARMSX3's own news and links, under ARMSX3's mark.
    val armsx3 = XmbColumn(
        key = "armsx3",
        title = "ARMSX3",
        icon = XmbIcon(bitmap = markBadge, glyph = "◉"),
        items = listOf(
            xmbNewsNode(),
            XmbNode.Action("armsx3.discord", str("about.discord"), XmbIcon(model = R.drawable.ic_discord, glyph = "💬")) {
                openExternalUrl(context, DiscordUrl)
            },
            XmbNode.Action("armsx3.github", str("about.github"), XmbIcon(model = R.drawable.ic_github, glyph = "🐙")) {
                openExternalUrl(context, GithubUrl)
            },
            xmbAboutNode(XmbIcon(bitmap = markIcon)),
        ),
    )

    // Derived, so moving between the other columns does not rebuild all of these.
    val rpcnShown by remember(controller) {
        derivedStateOf { controller.currentColumn()?.key == "rpcn" && !XmbMode.booting.value }
    }
    val rpcn = xmbRpcnColumn(controller, rpcnShown, inGame = false)

    return listOf(users, settings, photo, music, tools, game, network, armsx3, rpcn)
}

/** The console's date and time for "last played": the locale's day, month and year, and the time. */
private fun lastPlayedFormat(context: android.content.Context, locale: java.util.Locale): java.text.SimpleDateFormat {
    val date = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMd")
    val time = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
    return java.text.SimpleDateFormat("$date $time", locale)
}

/**
 * Photo: the screenshots taken in ARMSX3 (the screenshot hotkey, or Take Screenshot in the in-game
 * XMB), newest first, in an album as the console's Photo column lists folders. Each picture opens
 * full screen, and Left and Right go through the album. The console's "There are no images." when
 * there are none.
 */
@Composable
internal fun xmbPhotoColumn(controller: XmbController): XmbColumn {
    val context = LocalContext.current
    val albums by produceState(emptyList<XmbPhotos.Album>()) {
        value = withContext(Dispatchers.IO) { XmbPhotos.albums(context) }
    }
    val full = "explore_plugin_full"
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val taken = remember(locale) { lastPlayedFormat(context, locale) }
    val screenshots = str("xmb.photo.screenshots")
    val count = str("xmb.photo.count")
    val folderIcon = XmbIcon("explore_category_photo", "photo_tex_folder", "🖼")
    val items = if (albums.isEmpty()) {
        listOf(
            XmbNode.Action("photo.none", fw(full, "msg_error_no_image", "xmb.photo.none"), XmbIcon("explore_category_photo", "photo_tex_default", "🖼")) {},
        )
    } else {
        albums.map { album ->
            val title = when (album.kind) {
                XmbPhotos.Kind.Screenshots -> screenshots
            }
            val sub = listOfNotNull(album.serial, count.format(album.files.size)).joinToString("   ")
            XmbNode.Folder("photo.album.${album.key}", title, XmbIcon(file = album.files.first(), glyph = "🖼"), sub) {
                album.files.mapIndexed { i, file ->
                    val name = taken.format(java.util.Date(file.lastModified()))
                    val viewer = XmbNode.Panel("photo.view.${album.key}.${file.name}", title, folderIcon) { _ ->
                        XmbPhotoViewer(album.files, i)
                    }
                    XmbNode.Action("photo.${album.key}.${file.name}", file.nameWithoutExtension, XmbIcon(file = file, glyph = "🖼"), name) {
                        controller.panel = viewer
                    }
                }
            }
        }
    }
    return XmbColumn(
        key = "photo",
        title = fw("xmb_ingame", "msg_photo", "xmb.cat.photo"),
        icon = XmbIcon("xmb_ingame", "tex_photo", "🖼"),
        items = items,
    )
}

/** An album's pictures, one at a time as large as the panel allows, on black; Left and Right page. */
@Composable
private fun XmbPhotoViewer(files: List<File>, start: Int) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { files.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun go(step: Int) {
        val next = (pager.currentPage + step).coerceIn(0, files.lastIndex)
        if (next != pager.currentPage) scope.launch { pager.animateScrollToPage(next) }
    }
    // The pad's Left and Right page through it: the viewer is the one control, selected as it opens.
    LaunchedEffect(Unit) {
        var tries = 0
        while (!com.armsx2.ui.settings.SettingsControllerNav.selectById("xmb.photo.viewer") && tries++ < 10) {
            androidx.compose.runtime.withFrameNanos {}
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .controllerFocusable("xmb.photo.viewer", ring = false, onLeft = { go(-1) }, onRight = { go(1) }),
    ) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), key = { files[it].absolutePath }) { page ->
            AsyncImage(model = files[page], contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** ARMSX3's pictures: its screenshots. */
object XmbPhotos {
    enum class Kind { Screenshots }

    class Album(val key: String, val kind: Kind, val taken: Long, val serial: String?, val files: List<File>)

    private val IMAGES = setOf("png", "jpg", "jpeg", "webp")

    private fun images(dir: File?): List<File> =
        dir?.listFiles()?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in IMAGES } ?: emptyList()

    fun albums(context: android.content.Context): List<Album> = buildList {
        val root = runCatching { MainActivityRuntime.assetCopyRoot(context) }.getOrNull()
        val shots = listOf("snaps", "screenshots").flatMap { images(root?.let { r -> File(r, it) }) }
            .sortedByDescending { it.lastModified() }
        if (shots.isNotEmpty()) add(Album("screenshots", Kind.Screenshots, shots.first().lastModified(), null, shots))
    }
}
