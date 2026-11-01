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

    /** An app screen over the XMB (a panel, or the RPCN avatar picker): the pad drives it as settings. */
    private fun appScreen() = controller.panel != null || com.armsx2.ui.settings.RpcnAvatarPicker.open.value

    fun move(dx: Int, dy: Int) {
        val c = controller
        if (appScreen()) {
            when {
                dy != 0 -> nav.moveSpatial(0, dy)
                dx != 0 -> if (!nav.adjust(dx)) nav.moveSpatial(dx, 0)
            }
            return
        }
        c.move(dx, dy)
    }

    fun confirm() {
        if (appScreen()) nav.confirm() else controller.confirm()
    }

    fun back() {
        val picker = com.armsx2.ui.settings.RpcnAvatarPicker.open
        if (picker.value) picker.value = false else controller.back()
    }

    /** L1 / R1: a page of items at a time. */
    fun page(dir: Int) {
        val c = controller
        if (!appScreen() && !c.busy) repeat(5) { c.move(0, dir) }
    }

    /** Triangle: the focused item's options. */
    fun options() {
        if (!appScreen()) controller.openOptions()
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

    // A friend asking to join the game being hosted: the XMB comes up on RPCN with the question,
    // as the pause menu opens on its Friends panel for it.
    val hosting by com.armsx2.remoteplay.RemotePlayHost.status
    val askTitle = str("remoteplay.request.title")
    val askAccept = str("remoteplay.request.accept")
    val askDecline = str("remoteplay.request.decline")
    LaunchedEffect(hosting) {
        val ask = hosting as? com.armsx2.remoteplay.RemotePlayHost.Status.Asking ?: return@LaunchedEffect
        controller.closeAll()
        controller.columns.indexOfFirst { it.key == "rpcn" }.takeIf { it >= 0 }?.let { controller.selectColumn(it) }
        controller.dialog = XmbController.Dialog(
            askTitle.format(ask.viewer),
            listOf(
                XmbOption(askAccept) { com.armsx2.remoteplay.RemotePlayHost.accept() },
                XmbOption(askDecline) { com.armsx2.remoteplay.RemotePlayHost.decline() },
            ),
        )
        XmbSounds.play(XmbSounds.Sfx.OPTION)
    }

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
    // The pause menu's first tab, in its order: the game itself, then the on-screen display, and
    // save states last, as they do not work in every game.
    val gameItems = buildList {
        running?.let(::add)
        // Restart has no firmware icon; it is drawn from Quit's ring (XmbFirmware.restartFrom).
        add(XmbNode.Action("ingame.restart", restart, XmbIcon("explore_category_game", "tex_quit#restart", "↻")) { askRestart() })
        add(xmbDiscNode(swap = false))
        if (com.armsx3.Rpcs3Bridge.discSwapState() != 0) add(xmbDiscNode(swap = true))
        if (com.armsx2.input.ToyPortals.menuEnabled()) add(xmbPortalNode(controller))
        add(XmbNode.Action("ingame.quit", quit, XmbIcon("explore_category_game", "tex_quit", "■")) { askQuit() })
        add(xmbOnScreenNode())
        // A screenshot of the game, without the XMB: it closes, and the shot is taken a moment later.
        add(
            XmbNode.Action("ingame.screenshot", str("xmb.screenshot"), XmbIcon("explore_category_photo", "tex_screenshot", "\uD83D\uDCF7"), str("xmb.screenshot.desc")) {
                InGameOverlay.toggle()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ com.armsx2.Screenshots.capture(context.applicationContext) }, 600)
            },
        )
        // The graphics API and driver, here as well as in Display Settings: a game is where they get changed.
        add(xmbGraphicsNode())
        // The pause menu's other tabs, as it has them: the picture, performance (speed, the CPU
        // decoders, frame captures) and controls. Its Settings tab is not here: All Core Settings
        // just below and the Settings column hold all of it.
        add(xmbPauseNode(com.armsx2.ui.emulation.EmulationMenuTab.Graphics, "tex_display", "\u25a3"))
        add(xmbPauseNode(com.armsx2.ui.emulation.EmulationMenuTab.Performance, "tex_power", "\u26a1"))
        add(xmbConfigDbNode())
        // The pause menu's wrench tab: this title's config database entries, each switchable, and the fixes.
        add(xmbPauseNode(com.armsx2.ui.emulation.EmulationMenuTab.Fixes, "tex_security", "\ud83d\udd27"))
        add(xmbPauseNode(com.armsx2.ui.emulation.EmulationMenuTab.Controls, "tex_pd", "\ud83c\udfae"))
        add(xmbCoreSettingsNode(InGameOverlay.settingsScope.value, InGameOverlay.currentSerial.value))
        add(xmbTrophiesNode(inGame = true))
        addAll(xmbStateNodes(controller))
    }
    val gameColumn = XmbColumn(
        key = "game",
        title = fw("xmb_ingame", "msg_game", "xmb.cat.game"),
        icon = XmbIcon("xmb_ingame", "tex_game", "🎮"),
        items = gameItems,
    )

    // Friends: RPCN, read while it is the column shown, with this game's invites.
    val rpcnShown by remember(controller) { androidx.compose.runtime.derivedStateOf { controller.currentColumn()?.key == "rpcn" } }
    val friends = xmbRpcnColumn(controller, rpcnShown, inGame = true)

    return listOf(users, settings, xmbPhotoColumn(controller), xmbMusicColumn(controller, inGame = true), gameColumn, friends)
}

/**
 * Change Disc (or Swap Disc, while the game takes one): the discs of the game's playlist, the one
 * in the drive marked, and a file to choose. The same calls as the pause menu's disc card.
 */
@Composable
private fun xmbDiscNode(swap: Boolean): XmbNode {
    val title = str(if (swap) "action.swapDisc" else "action.changeDisc")
    val sub = str(if (swap) "action.swapDisc.detail" else "action.changeDisc.detail")
    val inDrive = str("disc.swap.inDrive")
    val discN = str("disc.swap.discN")
    val pick = str("disc.swap.pickFile")
    val pickDesc = str("disc.swap.pickFile.desc")
    val icon = XmbIcon("explore_category_game", "item_tex_disc_icon", "⏏")
    return XmbNode.Folder(if (swap) "ingame.swapDisc" else "ingame.changeDisc", title, icon, sub) {
        val playlist = com.armsx3.Rpcs3Bridge.discPlaylist()
        buildList {
            playlist.discs.forEachIndexed { index, disc ->
                val current = disc == playlist.current
                add(
                    XmbNode.Action("disc.$index", disc.substringAfterLast('/'), icon, if (current) inDrive else discN.format(index + 1)) {
                        if (!current) {
                            InGameOverlay.toggle()
                            if (swap) MainActivityRuntime.swapDiscTo(disc) else MainActivityRuntime.bootInstead(disc)
                        }
                    },
                )
            }
            add(XmbNode.Action("disc.pick", pick, icon, pickDesc) { MainActivityRuntime.promptDiscFile(swap) })
        }
    }
}

/**
 * On-Screen Display, as the pause menu's first tab has it: the stats overlay's mode, colour and
 * place, the compile notices, the pause button's tap to reveal, and the on-screen controls with
 * their layout.
 */
@Composable
private fun xmbOnScreenNode(): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", "tex_display", "▤")
    val modes = InGameOverlay.OsdMode.entries
    val modeLabels = modes.map { InGameOverlay.osdModeLabel(it) }
    val colorLabels = com.armsx2.ui.settings.OSD_COLOR_LABEL_KEYS.map { str(it) }
    val custom = str("overlay.osdColor.custom")
    val positionLabels = listOf(
        "overlay.position.topLeft", "overlay.position.topRight",
        "overlay.position.bottomLeft", "overlay.position.bottomRight",
    ).map { str(it) }
    val tapReveal = str("overlay.pauseTapReveal.label")
    val tapRevealDesc = str("overlay.pauseTapReveal.desc")
    val master = str("overlay.master.label")
    val colour = str("overlay.osdColor.label")
    val shaderHint = str("overlay.shaderHint.label")
    val shaderHintDesc = str("overlay.shaderHint.desc")
    val spuHint = str("overlay.spuHint.label")
    val spuHintDesc = str("overlay.spuHint.desc")
    val position = str("overlay.position.label")
    val touch = str("pad.onScreenControls.label")
    val editLayout = str("pad.editTouchLayout")
    fun ps3() = InGameOverlay.settingsState.value.ps3
    fun update(change: (com.armsx2.config.Ps3Settings) -> com.armsx2.config.Ps3Settings) {
        val s = InGameOverlay.settingsState.value
        InGameOverlay.saveSettings(s.copy(ps3 = change(s.ps3)))
    }
    fun toggle(key: String, title: String, desc: String?, get: () -> Boolean, set: (Boolean) -> Unit) = XmbNode.Setting(
        key = key, title = title, icon = icon, editor = XmbEditor.Toggle(get, set), value = { if (get()) on else off }, description = desc,
    )
    fun choice(key: String, title: String, labels: List<String>, get: () -> Int, set: (Int) -> Unit, value: () -> String = { labels.getOrElse(get()) { "" } }) = XmbNode.Setting(
        key = key, title = title, icon = icon, editor = XmbEditor.Choice(labels, get, set), value = value,
    )
    return XmbNode.Folder("ingame.osd", str("tab.overlay"), icon, live = true) {
        listOf(
            choice("osd.mode", master, modeLabels, { modes.indexOf(InGameOverlay.osdMode.value).coerceAtLeast(0) }, { InGameOverlay.setOsdMode(modes[it]) }),
            choice(
                "osd.colour",
                colour,
                colorLabels,
                { com.armsx2.ui.settings.osdPresetIndex(ps3().overlayBodyColor).coerceAtLeast(0) },
                { i -> update { it.copy(overlayBodyColor = com.armsx2.ui.settings.OSD_COLORS[i]) } },
            ) { com.armsx2.ui.settings.osdPresetIndex(ps3().overlayBodyColor).let { if (it >= 0) colorLabels[it] else custom } },
            choice("osd.position", position, positionLabels, { ps3().overlayPosition.coerceIn(0, 3) }, { i -> update { it.copy(overlayPosition = i) } }),
            toggle("osd.shaderHint", shaderHint, shaderHintDesc, { ps3().showShaderCompileHint }) { v -> update { it.copy(showShaderCompileHint = v) } },
            toggle("osd.spuHint", spuHint, spuHintDesc, { ps3().showSpuCompileHint }) { v -> update { it.copy(showSpuCompileHint = v) } },
            toggle("osd.tapReveal", tapReveal, tapRevealDesc, { com.armsx2.ui.touch.TouchControls.pauseTapToReveal.value }) {
                com.armsx2.ui.touch.TouchControls.setPauseTapToReveal(it)
            },
            toggle("osd.touch", touch, null, { com.armsx2.ui.touch.TouchControls.visible.value }) {
                com.armsx2.ui.touch.TouchControls.visible.value = it
            },
            XmbNode.Action("osd.editLayout", editLayout, icon) { InGameOverlay.editTouchLayout() },
        )
    }
}

/**
 * Graphics API and Driver, in the in-game Game column: the renderer backend rows of Display
 * Settings (the API, the Vulkan driver with its downloads, ANGLE for OpenGL, Apply and Restart),
 * composed for the XMB as Display Settings composes them.
 */
@Composable
private fun xmbGraphicsNode(): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", "tex_display", "\u2699")
    val collector = remember { XmbCollector(on, off, icon) }
    return XmbNode.Folder(
        key = "ingame.graphics",
        title = str("xmb.graphics"),
        icon = icon,
        sub = str("xmb.graphics.desc"),
        live = true,
        host = {
            androidx.compose.runtime.CompositionLocalProvider(LocalXmbCollector provides collector) {
                com.armsx2.ui.settings.RendererBackendSection(InGameOverlay.settingsState)
            }
        },
    ) { collector.nodes }
}

/** A tab of the pause menu, composed for the XMB (see XmbPauseTab): the rows it shows, as lists. */
@Composable
private fun xmbPauseNode(tab: com.armsx2.ui.emulation.EmulationMenuTab, tex: String, glyph: String): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", tex, glyph)
    val collector = remember(tab) { XmbCollector(on, off, icon) }
    return XmbNode.Folder(
        key = "ingame.pause.${tab.name}",
        title = str(tab.titleKey),
        icon = icon,
        live = true,
        host = {
            androidx.compose.runtime.CompositionLocalProvider(LocalXmbCollector provides collector) {
                com.armsx2.ui.emulation.XmbPauseTab(tab)
            }
        },
    ) { collector.nodes }
}

/**
 * The running title's RPCS3 config database entries, as the pause menu's wrench tab lists them:
 * each one a switch, on to use the database's value, off to keep yours. Turning one on clears a
 * value you set for this game yourself, as the core gives yours priority.
 */
@Composable
private fun xmbConfigDbNode(): XmbNode {
    val serial = InGameOverlay.currentSerial.value?.takeIf { it.isNotBlank() }
    val (on, off) = xmbOnOff()
    val title = str("perf.configDb.title")
    val help = str("perf.configDb.help")
    val yoursText = str("perf.configDb.yours")
    val offText = str("perf.configDb.off")
    val none = str("xmb.configDb.none")
    val icon = XmbIcon("explore_category_sysconf", "tex_update", "\u2699")
    val entries = remember(serial) { serial?.let { com.armsx2.config.ConfigDatabase.entriesFor(it) }.orEmpty() }
    val revision = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    return XmbNode.Folder("ingame.configdb", title, icon, if (entries.isEmpty()) none else help, live = true) {
        @Suppress("UNUSED_EXPRESSION") revision.intValue
        if (serial == null || entries.isEmpty()) return@Folder listOf(XmbNode.Action("configdb.none", none, icon) {})
        val ignored = com.armsx2.config.ConfigDatabase.ignoredFor(serial)
        val yours = com.armsx2.config.CoreSettingOverrides.load(com.armsx2.config.SettingsScope.Game, serial).keys
        entries.map { (path, value) ->
            val withheld = path in ignored || path in yours
            XmbNode.Setting(
                "configdb.$path",
                "${path.replace("@@", " / ")}: $value",
                icon,
                XmbEditor.Toggle({ !withheld }) { applyThis ->
                    com.armsx2.config.ConfigDatabase.setIgnored(serial, path, !applyThis)
                    if (applyThis) {
                        com.armsx2.config.CoreSettingOverrides.forget(com.armsx2.config.SettingsScope.Game, serial, path)
                    }
                    val now = com.armsx2.config.CoreSettingOverrides.load(com.armsx2.config.SettingsScope.Game, serial).keys
                    com.armsx2.config.ConfigDatabase.writeUserKeys(serial, now)
                    revision.intValue++
                },
                { if (!withheld) on else off },
                description = when {
                    path in yours -> yoursText
                    withheld -> offText
                    else -> null
                },
                changed = { withheld },
            )
        }
    }
}
