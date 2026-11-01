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
import com.armsx2.ui.settings.RpcnFriends
import com.armsx2.ui.settings.parseRpcnFriends
import com.armsx2.ui.settings.rpcnSessionDispatcher
import com.armsx2.ui.settings.rpcnSessionExecutor
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
                XmbMode.exit()
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
    val music = xmbMusicColumn()

    val utilities = listOf(
        XmbNode.Action("game.install", fw(full, "msg_tool_install_file", "xmb.installPackages"), XmbIcon("explore_category_game", "item_tex_disc_install", "📦")) {
            go(AppRoute.PackageInstaller)
        },
        XmbNode.Action("game.saves", str("savestate.title.loadManage"), XmbIcon("explore_category_game", "item_tex_ps3util", "💾")) {
            go(AppRoute.SaveManager())
        },
        XmbNode.Action("game.trophies", fw(full, "msg_trophy_collection", "trophies.title"), XmbIcon("explore_category_psn", "item_tex_trophy", "🏆")) {
            go(AppRoute.Trophies)
        },
        XmbNode.Action("game.mods", str("mods.library.title"), XmbIcon("explore_category_game", "item_tex_ps3game_folder", "🛠")) {
            go(AppRoute.ModLibrary)
        },
        XmbNode.Action("game.xmb", str("bios.boot.title"), XmbIcon("explore_category_game", "item_tex_ps3logo", "▶")) {
            MainActivityRuntime.startBios()
        },
        XmbNode.Action("game.file", str("action.launchGame"), XmbIcon("explore_category_game", "item_tex_plain_folder", "📂")) {
            MainActivityRuntime.promptLaunchGame()
        },
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
            onOptions = { onGameMenu(game) },
            onSettings = { go(AppRoute.Settings(game = game, returnTo = AppRoute.Home)) },
            mediaDir = dir,
        )
    }
    val game = XmbColumn(
        key = "game",
        title = fw("xmb_ingame", "msg_game", "xmb.cat.game"),
        icon = XmbIcon("xmb_ingame", "tex_game", "🎮"),
        items = utilities + games,
        // The first game, as the console lands on its newest title rather than on the utilities.
        defaultFocus = if (games.isEmpty()) 0 else utilities.size,
    )

    val network = XmbColumn(
        key = "network",
        title = fw("xmb_ingame", "msg_net", "xmb.cat.network"),
        icon = XmbIcon("xmb_ingame", "tex_network", "🌐"),
        items = listOf(
            XmbNode.Action("net.remote", fw(full, "msg_psp_connection", "remoteplay.title"), XmbIcon("explore_category_network", "tex_premo", "📡")) {
                go(AppRoute.RemotePlay)
            },
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
            XmbNode.Action("armsx3.news", str("news.title"), XmbIcon("explore_category_network", "tex_onlinemanual", "📰")) {
                go(AppRoute.News)
            },
            XmbNode.Action("armsx3.discord", str("about.discord"), XmbIcon(model = R.drawable.ic_discord, glyph = "💬")) {
                openExternalUrl(context, DiscordUrl)
            },
            XmbNode.Action("armsx3.github", str("about.github"), XmbIcon(model = R.drawable.ic_github, glyph = "🐙")) {
                openExternalUrl(context, GithubUrl)
            },
            XmbNode.Action("armsx3.about", str("about.title"), XmbIcon(bitmap = markIcon)) {
                go(AppRoute.About)
            },
        ),
    )

    // Derived, so moving between the other columns does not rebuild all of these.
    val rpcnShown by remember(controller) {
        derivedStateOf { controller.currentColumn()?.key == "rpcn" && !XmbMode.booting.value }
    }
    val rpcn = xmbRpcnColumn(rpcnShown)

    return listOf(users, settings, photo, music, game, network, armsx3, rpcn)
}

/** The console's date and time for "last played": the locale's day, month and year, and the time. */
private fun lastPlayedFormat(context: android.content.Context, locale: java.util.Locale): java.text.SimpleDateFormat {
    val date = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMd")
    val time = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
    return java.text.SimpleDateFormat("$date $time", locale)
}

/**
 * Photo: the screenshots taken in ARMSX3, newest first, each opening full screen. The console's
 * "There are no images." when there are none.
 */
@Composable
private fun xmbPhotoColumn(controller: XmbController): XmbColumn {
    val context = LocalContext.current
    val shots by produceState(emptyList<File>()) {
        value = withContext(Dispatchers.IO) { XmbPhotos.list(context) }
    }
    val full = "explore_plugin_full"
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val taken = remember(locale) { lastPlayedFormat(context, locale) }
    val items = if (shots.isEmpty()) {
        listOf(
            XmbNode.Action("photo.none", fw(full, "msg_error_no_image", "xmb.photo.none"), XmbIcon("explore_category_photo", "photo_tex_default", "🖼")) {},
        )
    } else {
        shots.map { file ->
            val title = taken.format(java.util.Date(file.lastModified()))
            val viewer = XmbNode.Panel("photo.view.${file.name}", title, XmbIcon("explore_category_photo", "tex_screenshot", "🖼")) { _ ->
                XmbPhotoViewer(file)
            }
            XmbNode.Action("photo.${file.name}", title, XmbIcon(file = file, glyph = "🖼"), file.name) {
                controller.panel = viewer
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

/** A screenshot as large as the panel allows, on black. */
@Composable
private fun XmbPhotoViewer(file: File) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** Screenshots ARMSX3 has taken. */
object XmbPhotos {
    fun list(context: android.content.Context): List<File> {
        val root = runCatching { MainActivityRuntime.assetCopyRoot(context) }.getOrNull() ?: return emptyList()
        return listOf("snaps", "screenshots")
            .flatMap { File(root, it).listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            .sortedByDescending { it.lastModified() }
    }
}

/**
 * Music: the library's track, playing or stopped with Cross, as the console's Music column plays
 * what it lists. The track itself is chosen in System Settings, with the rest of the app's.
 */
@Composable
private fun xmbMusicColumn(): XmbColumn {
    val context = LocalContext.current.applicationContext
    val full = "explore_plugin_full"
    val playing = LibraryMusic.enabled.value
    val track = LibraryMusic.customName.value ?: str("xmb.music.builtin")
    val status = if (playing) fw(full, "msg_play", "action.play") else fw(full, "msg_pause", "xmb.music.paused")
    return XmbColumn(
        key = "music",
        title = fw("xmb_ingame", "msg_music", "xmb.cat.music"),
        icon = XmbIcon("xmb_ingame", "tex_music", "♫"),
        items = listOf(
            XmbNode.Action(
                "music.library",
                track,
                XmbIcon("explore_category_music", if (playing) "music_tex_default" else "music_tex_album_default", "♫"),
                status,
            ) { LibraryMusic.set(context, !playing) },
        ),
    )
}

/**
 * RPCN, where the console's Friends column was: the account, adding a friend, and every friend
 * with whether they are online and what they are playing, read every two seconds while the
 * column is the one shown.
 */
@Composable
private fun xmbRpcnColumn(rpcnShown: Boolean): XmbColumn {
    val full = "explore_plugin_full"
    val friends = rememberRpcnFriends(rpcnShown)
    val online = fw(full, "msg_online", "xmb.rpcn.online")
    val offline = str("xmb.rpcn.offline")
    val items = buildList {
        add(
            XmbNode.Action("rpcn.account", fw(full, "msg_account_manage", "xmb.rpcn.account"), XmbIcon("explore_category_psn", "item_tex_account", "🛰")) {
                UiNavigator.navigate(AppRoute.Rpcn)
            },
        )
        add(
            XmbNode.Action("rpcn.add", fw(full, "msg_friend_register", "xmb.rpcn.add"), XmbIcon("explore_category_friend", "item_tex_NewFriend", "➕")) {
                UiNavigator.navigate(AppRoute.Friends)
            },
        )
        friends?.friends?.forEach { f ->
            val sub = when {
                !f.online -> offline
                f.title.isNotBlank() -> f.title
                else -> online
            }
            add(
                XmbNode.Action(
                    "rpcn.friend.${f.name}",
                    f.name,
                    XmbIcon("explore_category_friend", "tex_Avatar_Default", "🙂"),
                    sub,
                ) { UiNavigator.navigate(AppRoute.Friends) },
            )
        }
    }
    return XmbColumn(
        key = "rpcn",
        title = str("rpcn.screen.title"),
        icon = XmbIcon("xmb_ingame", "tex_friend", "👥"),
        items = items,
    )
}

/**
 * The RPCN friend list while [shown]: with an account saved, a session held open for as long as the
 * column is shown (as the Friends screen does, one open and one close per showing), and the list
 * read every two seconds. Null without an account.
 */
@Composable
private fun rememberRpcnFriends(shown: Boolean): RpcnFriends? {
    var data by remember { mutableStateOf<RpcnFriends?>(null) }
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        val configured = withContext(Dispatchers.IO) {
            runCatching { JSONObject(Rpcs3Bridge.rpcnStatus()).optBoolean("configured") }.getOrDefault(false)
        }
        if (!configured) {
            data = null
            return@LaunchedEffect
        }
        try {
            // Runs to the end even when the column is left meanwhile, so the close below always
            // has an open to match.
            withContext(NonCancellable + rpcnSessionDispatcher) { Rpcs3Bridge.rpcnFriendsSession(true) }
            while (true) {
                data = parseRpcnFriends(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() })
                delay(2_000)
            }
        } finally {
            rpcnSessionExecutor.execute { Rpcs3Bridge.rpcnFriendsSession(false) }
        }
    }
    return data
}
