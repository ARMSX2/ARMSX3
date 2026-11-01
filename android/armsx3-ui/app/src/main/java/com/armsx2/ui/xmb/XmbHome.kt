package com.armsx2.ui.xmb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.armsx2.EnglishTitles
import com.armsx2.GameInfo
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.navigation.AppRoute
import com.armsx2.navigation.UiNavigator
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.home.HomeUiState
import com.armsx2.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * XMB mode's home: the library and the app laid out as the console's XMB, with the boot animation
 * when it comes up.
 *
 * Columns, left to right as on the console: Users (leaving XMB mode, and turning ARMSX3 off),
 * Settings (ARMSX3's own: App Settings and All Core Settings), Game (installs, saves, trophies, the
 * real XMB, then every game), Network (Remote Play, news, about) and RPCN, which stands where the
 * console's Friends and PlayStation Network were.
 */
object XmbHomeState {
    private const val ColumnKey = "xmb.home.column"
    private const val FocusKey = "xmb.home.focus"

    /** One controller for the run, so leaving for a screen and coming back keeps the place. */
    val controller: XmbController by lazy {
        val prefs = MainActivityRuntime.prefs
        val focus = runCatching {
            val json = org.json.JSONObject(prefs.getString(FocusKey, "{}") ?: "{}")
            json.keys().asSequence().associateWith { json.optInt(it) }
        }.getOrDefault(emptyMap())
        XmbController(prefs.getString(ColumnKey, "game"), focus)
    }

    fun save() {
        val c = controller
        val json = org.json.JSONObject()
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
        withContext(Dispatchers.IO) { XmbSounds.load(context.applicationContext) }
        // A search typed in the library would otherwise hide games here, with no search box to clear it.
        if (viewModel.state.value.query.isNotEmpty()) viewModel.setQuery("")
    }
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

    val users = XmbColumn(
        key = "users",
        title = str("xmb.cat.users"),
        icon = XmbIcon("xmb_ingame", "tex_user", "👤"),
        items = listOf(
            XmbNode.Action("users.exit", str("xmb.exit"), XmbIcon(model = com.armsx2.R.drawable.ic_armsx3_mark), str("xmb.exit.desc")) {
                XmbMode.exit()
            },
            XmbNode.Action("users.off", str("xmb.turnOff"), XmbIcon("explore_category_user", "tex_power_off", "⏻")) {
                controller.options = XmbController.Options(
                    I18n.get("games.exit.title"),
                    listOf(
                        XmbOption(I18n.get("xmb.turnOff")) { MainActivityRuntime.exitApp() },
                        XmbOption(I18n.get("action.cancel")) {},
                    ),
                )
            },
        ),
    )

    val settings = XmbColumn(
        key = "settings",
        title = str("xmb.cat.settings"),
        icon = XmbIcon("xmb_ingame", "tex_sysconf", "⚙"),
        items = xmbSettingsItems(scope = com.armsx2.config.SettingsScope.Global, serial = null),
    )

    val utilities = listOf(
        XmbNode.Action("game.install", str("packages.title"), XmbIcon("explore_category_game", "item_tex_disc_install", "📦")) {
            go(AppRoute.PackageInstaller)
        },
        XmbNode.Action("game.saves", str("savestate.title.loadManage"), XmbIcon("explore_category_game", "item_tex_ps3util", "💾")) {
            go(AppRoute.SaveManager())
        },
        XmbNode.Action("game.trophies", str("trophies.title"), XmbIcon("explore_category_psn", "item_tex_trophy", "🏆")) {
            go(AppRoute.Trophies)
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
    val dirs by androidx.compose.runtime.produceState<Map<String, java.io.File?>>(emptyMap(), state.visibleGames) {
        value = withContext(Dispatchers.IO) {
            state.visibleGames.associate { it.uri.toString() to XmbMedia.dirOf(context, it) }
        }
    }
    val games = state.visibleGames.map { game ->
        val dir = dirs[game.uri.toString()]
        XmbNode.Game(
            game = game,
            title = game.displayTitle(forceEn),
            icon = XmbIcon(model = XmbMedia.icon0(game, dir), glyph = "🎮"),
            sub = listOfNotNull(game.serial?.takeIf { it.isNotBlank() }, game.versionTag).joinToString("   ").ifEmpty { null },
            onLaunch = { viewModel.launch(game) },
            onOptions = { onGameMenu(game) },
            onSettings = { go(AppRoute.Settings(game = game, returnTo = AppRoute.Home)) },
            mediaDir = dir,
        )
    }
    val game = XmbColumn(
        key = "game",
        title = str("xmb.cat.game"),
        icon = XmbIcon("xmb_ingame", "tex_game", "🎮"),
        items = utilities + games,
        // The first game, as the console lands on its newest title rather than on the utilities.
        defaultFocus = if (games.isEmpty()) 0 else utilities.size,
    )

    val network = XmbColumn(
        key = "network",
        title = str("xmb.cat.network"),
        icon = XmbIcon("xmb_ingame", "tex_network", "🌐"),
        items = listOf(
            XmbNode.Action("net.remote", str("remoteplay.title"), XmbIcon("explore_category_network", "tex_premo", "📡")) {
                go(AppRoute.RemotePlay)
            },
            XmbNode.Action("net.news", str("news.title"), XmbIcon("explore_category_network", "tex_onlinemanual", "📰")) {
                go(AppRoute.News)
            },
            XmbNode.Action("net.about", str("about.title"), XmbIcon("explore_category_network", "tex_browser", "ℹ")) {
                go(AppRoute.About)
            },
        ),
    )

    val rpcn = XmbColumn(
        key = "rpcn",
        title = str("rpcn.screen.title"),
        icon = XmbIcon("xmb_ingame", "tex_friend", "👥"),
        items = listOf(
            XmbNode.Action("rpcn.account", str("xmb.rpcn.account"), XmbIcon("explore_category_psn", "tex_Sign_In", "🛰")) {
                go(AppRoute.Rpcn)
            },
            XmbNode.Action("rpcn.friends", str("friends.title"), XmbIcon("explore_category_friend", "item_tex_Players", "👥")) {
                go(AppRoute.Friends)
            },
        ),
    )

    return listOf(users, settings, game, network, rpcn)
}
