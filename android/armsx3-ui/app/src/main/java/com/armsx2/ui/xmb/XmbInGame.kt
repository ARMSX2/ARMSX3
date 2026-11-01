package com.armsx2.ui.xmb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.armsx2.EnglishTitles
import com.armsx2.GameInfo
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.InGameScreen
import com.armsx2.ui.WindowImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * XMB mode's in-game menu: the XMB over the paused game, as the console brings it up with the PS
 * button, with ARMSX3's settings for the running game in its Settings column. It stands in for
 * the pause menu while XMB mode is on, and Quick Menu opens that pause menu for what the XMB does
 * not carry (the on-screen display, discs, toy portals). All of it takes touch: [XmbView] takes
 * taps and swipes, and an app screen in one of its panels is the touch screen it always was.
 *
 * The pad comes here through EmulationMenuInputController, which hands every press over while
 * [takesPad]; with a panel open, presses go to the app screen in it.
 */
object XmbInGame {
    private var forGame: String? = null

    /** The running game's XMB: made again for each game, so each starts on its own Game column. */
    var controller: XmbController = XmbController("game")
        private set

    /** The pause menu in place of the XMB, from Quick Menu, until the menu next opens. */
    val classic = mutableStateOf(false)

    fun controllerFor(game: String?): XmbController {
        if (game != forGame) {
            forGame = game
            controller = XmbController("game")
        }
        return controller
    }

    /** The XMB is the in-game menu, and it is up. */
    fun takesPad(): Boolean = XmbMode.active.value && !classic.value && WindowImpl.overlayVisible.value

    private val nav get() = com.armsx2.ui.settings.SettingsControllerNav

    fun move(dx: Int, dy: Int) {
        val c = controller
        if (c.panel != null) {
            when {
                dy != 0 -> nav.moveSpatial(0, dy)
                dx != 0 -> if (!nav.adjust(dx)) nav.moveSpatial(dx, 0)
            }
            return
        }
        c.move(dx, dy)
    }

    fun confirm() {
        if (controller.panel != null) nav.confirm() else controller.confirm()
    }

    fun back() = controller.back()

    /** L1 / R1: a page of items at a time. */
    fun page(dir: Int) {
        val c = controller
        if (c.panel == null && !c.busy) repeat(5) { c.move(0, dir) }
    }

    /** Triangle: the focused item's options. */
    fun options() {
        if (controller.panel == null) controller.openOptions()
    }
}

/** The in-game XMB, drawn by WindowImpl over the paused game while XMB mode is on. */
@Composable
fun XmbInGameMenu() {
    val context = LocalContext.current
    val game = MainActivityRuntime.currentGame.value
    val controller = remember(game?.uri) { XmbInGame.controllerFor(game?.uri?.toString()) }
    // Circle with nothing open is back to the game, as on the console.
    controller.onBackAtRoot = { InGameOverlay.toggle() }
    val language = I18n.current
    val theme = XmbTheme.entry()
    LaunchedEffect(language, theme?.folder) { XmbFirmwareReady.load(context.applicationContext, language, theme) }
    androidx.activity.compose.BackHandler(enabled = !controller.busy && controller.levels.isEmpty()) { controller.back() }

    // The game dims behind and the XMB fades in over it.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.6f)),
    ) {
        XmbView(
            controller = controller,
            columns = xmbInGameColumns(controller, game),
            takesPad = false,
            backToGame = true,
        )
    }
}

@Composable
private fun xmbInGameColumns(controller: XmbController, game: GameInfo?): List<XmbColumn> {
    val context = LocalContext.current
    val full = "explore_plugin_full"
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    fun open(screen: InGameScreen) = WindowImpl.openInGameScreen(screen)

    val turnOffAsk = fw(full, "msg_turnoff_system_ask", "xmb.turnOff.ask")
    val users = XmbColumn(
        key = "users",
        title = fw("xmb_ingame", "msg_user", "xmb.cat.users"),
        icon = XmbIcon("xmb_ingame", "tex_user", "👤"),
        items = listOf(
            XmbNode.Action("users.off", fw(full, "msg_power_off_console2", "xmb.turnOff"), XmbIcon("explore_category_user", "tex_power_off", "⏻")) {
                controller.ask(turnOffAsk, yes, no) { MainActivityRuntime.exitApp() }
            },
        ),
    )

    // The running title's own settings, where the in-game menu keeps them.
    val settings = XmbColumn(
        key = "settings",
        title = fw("xmb_ingame", "msg_setting", "xmb.cat.settings"),
        icon = XmbIcon("xmb_ingame", "tex_sysconf", "⚙"),
        items = xmbSettingsItems(InGameOverlay.settingsScope.value, InGameOverlay.currentSerial.value, game, inGame = true),
    )

    val resume = str("action.resume")
    val restart = str("memcard.restart")
    val restartAsk = str("xmb.restart.ask")
    val quit = fw("impose_plugin", "msg_game_quit2", "xmb.quitGame")
    val quitAsk = fw("impose_plugin", "msg_game_quit_ask", "xmb.quitGame.ask")
    fun askRestart() = controller.ask(restartAsk, yes, no) { MainActivityRuntime.restart() }
    fun askQuit() = controller.ask(quitAsk, yes, no) { MainActivityRuntime.closeGame() }
    val dir by produceState<java.io.File?>(null, game?.uri) {
        value = game?.let { withContext(Dispatchers.IO) { XmbMedia.dirOf(context, it) } }
    }
    val running = game?.let { g ->
        val title = g.displayTitle(EnglishTitles.enabled.value)
        XmbNode.Game(
            game = g,
            title = title,
            icon = XmbIcon(model = XmbMedia.icon0(g, dir), glyph = "🎮"),
            sub = resume,
            onLaunch = { InGameOverlay.toggle() },
            onOptions = {
                controller.options = XmbController.Options(
                    title,
                    listOf(
                        XmbOption(resume) { InGameOverlay.toggle() },
                        XmbOption(restart) { askRestart() },
                        XmbOption(quit) { askQuit() },
                    ),
                )
            },
            onSettings = { controller.selectColumn(1) },
            mediaDir = dir,
        )
    }
    val gameItems = listOfNotNull(running) + listOf(
        XmbNode.Action("ingame.save", str("savestate.title.save"), XmbIcon("explore_category_game", "item_tex_ps3util", "↥")) {
            open(InGameScreen.SaveState)
        },
        XmbNode.Action("ingame.load", str("xmb.loadState"), XmbIcon("explore_category_game", "item_tex_ps3util", "↧")) {
            open(InGameScreen.LoadState)
        },
        XmbNode.Action("ingame.trophies", fw(full, "msg_trophy_collection", "trophies.title"), XmbIcon("explore_category_psn", "item_tex_trophy", "🏆")) {
            open(InGameScreen.Trophies)
        },
        XmbNode.Action("ingame.quick", str("xmb.quickMenu"), XmbIcon("explore_category_sysconf", "tex_sett", "☰"), str("xmb.quickMenu.desc")) {
            XmbInGame.classic.value = true
        },
        XmbNode.Action("ingame.restart", restart, XmbIcon("explore_category_game", "tex_quit", "↻")) { askRestart() },
        XmbNode.Action("ingame.quit", quit, XmbIcon("explore_category_game", "tex_quit", "■")) { askQuit() },
    )
    val gameColumn = XmbColumn(
        key = "game",
        title = fw("xmb_ingame", "msg_game", "xmb.cat.game"),
        icon = XmbIcon("xmb_ingame", "tex_game", "🎮"),
        items = gameItems,
    )
    return listOf(users, settings, gameColumn)
}
