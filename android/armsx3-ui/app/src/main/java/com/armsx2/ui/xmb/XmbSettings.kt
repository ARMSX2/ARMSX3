package com.armsx2.ui.xmb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.armsx2.GameInfo
import com.armsx2.LibraryMusic
import com.armsx2.MenuSfx
import com.armsx2.config.CoreSettingOverrides
import com.armsx2.config.SettingsScope
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.navigation.AppRoute
import com.armsx2.navigation.SettingsCategory
import com.armsx2.navigation.UiNavigator
import com.armsx2.ui.settings.CoreSetting
import com.armsx2.ui.settings.flatten
import com.armsx2.ui.settings.sameValue
import com.armsx2.ui.settingshub.CategoryContent
import com.armsx2.ui.settingshub.SettingsViewModel
import com.armsx2.ui.home.LibraryBackground
import com.armsx2.ui.settingshub.categoryTitle
import com.armsx3.core.ARMSX3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The XMB's Settings column with ARMSX3's settings behind it: the console's own entries, in its
 * order and with its words, each opening what ARMSX3 has for it. Display Settings is the renderer
 * tab, Sound Settings the audio tab, Accessory Settings the controls, hotkeys, on-screen controls
 * and skins, Network Settings the network tab, Game Settings how games run, System Settings the
 * app's own, Debug Settings the advanced tab, and System Update the firmware. All Core Settings
 * stands on its own beside Game Settings. Music Settings and Theme Settings are XMB lists of their
 * own.
 *
 * A tab opens as an XMB list of its own rows (see XmbCollector): the tab itself is composed out of
 * sight and each of its settings becomes an item, so it saves exactly as it does elsewhere, in the
 * same scope and with the same per-game overrides. [scope] and [serial] are where edits are
 * remembered: global from the library, the running title in game.
 */
@Composable
fun xmbSettingsItems(scope: SettingsScope, serial: String?, game: GameInfo? = null, inGame: Boolean = false): List<XmbNode> {
    val forGame = game != null
    val (on, off) = xmbOnOff()
    // One collector per tab for as long as this list is up: the column is rebuilt as it recomposes,
    // and a tab's rows must keep landing in the collector its open list reads.
    val collectors = remember(game?.uri, inGame, on, off) { HashMap<SettingsCategory, XmbCollector>() }
    val updateIcon = XmbIcon("explore_category_sysconf", "tex_update", "\u2699")
    val updateCollector = remember(on, off) { XmbCollector(on, off, updateIcon) }
    val context = LocalContext.current
    val underclockBody = str("underclock.body")
    val underclockNote = str("underclock.note")
    val underclockOpen = str("underclock.open")
    val controllerMapping = xmbControllerMappingNode()
    val systemIcons = xmbSystemIcons()
    fun tab(category: SettingsCategory, title: String, icon: String, sub: String? = null): XmbNode {
        val i = XmbIcon("explore_category_sysconf", icon, "\u2699")
        // The patch and mod lists, and a game's details, are pictures and lists of their own, not rows
        // of settings: those stay the app's screens, in a panel.
        if (category in PANEL_TABS) {
            return XmbNode.Panel("settings.tab.${category.name}", title, i, sub) { _ -> XmbSettingsTabPanel(category, game) }
        }
        val collector = collectors.getOrPut(category) { XmbCollector(on, off, i) }
        val search = xmbSearchNode("settings.tab.${category.name}") { collector.nodes }
        return XmbNode.Folder(
            key = "settings.tab.${category.name}",
            title = title,
            icon = i,
            sub = sub,
            live = true,
            host = {
                androidx.compose.runtime.CompositionLocalProvider(LocalXmbCollector provides collector) {
                    XmbSettingsTabHost(category, game)
                }
            },
        ) { collector.nodes.let { rows -> if (rows.isEmpty()) rows else listOf(search) + rows } }
    }

    val performance = categoryTitle(SettingsCategory.Performance)
    val patches = categoryTitle(SettingsCategory.Patches)
    val mods = categoryTitle(SettingsCategory.Mods)
    val info = categoryTitle(SettingsCategory.Info)
    val underclock = str("underclock.title")
    val gameSettings = XmbNode.Folder(
        key = "settings.game",
        title = sysconf("msg_game_setting", "xmb.set.game"),
        icon = XmbIcon("explore_category_sysconf", "tex_game", "\u2699"),
        sub = explanation("msg_game_setting_explanation"),
    ) {
        buildList {
            if (forGame) add(tab(SettingsCategory.Info, info, "tex_bddvd"))
            add(tab(SettingsCategory.Performance, performance, "tex_game"))
            add(tab(SettingsCategory.Patches, patches, "tex_update"))
            if (forGame) {
                add(tab(SettingsCategory.Mods, mods, "tex_edyviewer"))
            } else if (!inGame) {
                // Underclocking: what ClusterTune does and the way to it, as the screen has them.
                val power = XmbIcon("explore_category_sysconf", "tex_power", "\u2699")
                add(XmbNode.Folder("settings.underclock", underclock, power, underclockBody.substringBefore(". ") + ".") {
                    listOf(
                        XmbNode.Panel("underclock.about", underclock, power) { _ -> XmbTextPanel(paragraphsOf("$underclockBody\n\n$underclockNote")) },
                        XmbNode.Action("underclock.open", underclockOpen, power) {
                            com.armsx2.navigation.openExternalUrl(context, com.armsx2.ui.underclock.CLUSTERTUNE_URL)
                        },
                    )
                })
            }
        }
    }

    val controls = categoryTitle(SettingsCategory.Controls)
    val hotkeys = categoryTitle(SettingsCategory.Hotkeys)
    val onScreen = categoryTitle(SettingsCategory.OnScreen)
    val skins = categoryTitle(SettingsCategory.Skins)
    val accessory = XmbNode.Folder(
        key = "settings.accessory",
        title = sysconf("msg_pd_setting", "xmb.set.accessory"),
        icon = XmbIcon("explore_category_sysconf", "tex_pd", "\u2699"),
        sub = explanation("msg_pd_setting_explanation"),
    ) {
        buildList {
            // Controller Mapping first, as its own lists: every button, motion, profile and hotkey.
            add(controllerMapping)
            add(tab(SettingsCategory.Controls, controls, "tex_pd"))
            add(tab(SettingsCategory.Hotkeys, hotkeys, "tex_remote"))
            add(tab(SettingsCategory.OnScreen, onScreen, "tex_display"))
            add(tab(SettingsCategory.Skins, skins, "tex_thema"))
        }
    }

    val display = tab(SettingsCategory.Graphics, sysconf("msg_display_setting", "xmb.set.display"), "tex_display", explanation("msg_display_setting_explanation"))
    val sound = tab(SettingsCategory.Audio, sysconf("msg_sound_setting", "xmb.set.sound"), "tex_sound", explanation("msg_sound_setting_explanation"))
    val network = tab(SettingsCategory.Network, sysconf("msg_net_setting", "xmb.set.network"), "tex_network", explanation("msg_net_setting_explanation"))
    // The advanced tab is what a debug console's Debug Settings would hold.
    val debug = tab(SettingsCategory.Advanced, sysconf("msg_tool_debug_settings", "xmb.set.debug"), "tex_security", categoryTitle(SettingsCategory.Advanced))
    val core = xmbCoreSettingsNode(scope, serial)
    val music = xmbMusicSettings()

    // In a game, the running title's settings: the app's own and the firmware are for the library.
    if (forGame || inGame) return listOf(gameSettings, core, display, sound, accessory, music, network, debug)

    val update = XmbNode.Folder(
        "settings.update",
        sysconf("msg_update", "xmb.set.update"),
        updateIcon,
        explanation("msg_update_explanation"),
        live = true,
        host = {
            androidx.compose.runtime.CompositionLocalProvider(LocalXmbCollector provides updateCollector) {
                com.armsx2.ui.bios.BiosManagerScreen(onBack = {})
            }
        },
    ) { updateCollector.nodes }
    val system = tab(SettingsCategory.General, sysconf("msg_console_setting", "xmb.set.system"), "tex_console", explanation("msg_console_setting_explanation"))
    collectors[SettingsCategory.General]?.iconFor = systemIcons
    return listOf(
        update,
        gameSettings,
        core,
        music,
        system,
        xmbThemeSettings(),
        accessory,
        display,
        sound,
        network,
        debug,
    )
}

/**
 * Search, for a list of settings: typed on the pad keyboard, and what matches (in the list or the
 * lists inside it, by name or by its line of explanation) opens as a list of its own.
 */
internal fun xmbSearchNode(key: String, rows: () -> List<XmbNode>): XmbNode.Action {
    val title = I18n.get("xmb.core.search")
    val icon = XmbIcon("explore_category_sysconf", "tex_sett", "\uD83D\uDD0D")
    return XmbNode.Action("$key.search", title, icon, I18n.get("xmb.settings.search.desc")) {
        XmbKeyboard.ask(title) { typed ->
            val query = typed.trim()
            if (query.isEmpty()) return@ask
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            fun every(list: List<XmbNode>): List<XmbNode> = list.flatMap { n ->
                if (n is XmbNode.Folder) every(n.children()) else listOf(n)
            }
            val hits = every(rows()).filter { n ->
                if (n.key.endsWith(".search")) return@filter false
                val hay = listOfNotNull(n.title, (n as? XmbNode.Setting)?.description, (n as? XmbNode.Action)?.subtitle).joinToString(" ").lowercase()
                words.all { it in hay }
            }
            val c = XmbDialogs.target() ?: return@ask
            if (hits.isEmpty()) {
                c.tell(I18n.get("xmb.core.noResults").format(query), I18n.get("xmb.ok"))
            } else {
                val results = XmbNode.Folder("$key.results", "\u201C$query\u201D", icon, "${hits.size}") { hits }
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                c.levels.add(XmbController.Level(results, hits, 0))
            }
        }
    }
}

/**
 * System Settings' rows each with an icon for what they are (music, trophies, saved data, the
 * theme...), not all the console's: by the row's name, or by its key where the name changes.
 */
@Composable
private fun xmbSystemIcons(): (XmbNode) -> XmbIcon? {
    val language = I18n.current
    return remember(language) {
        fun sys(name: String) = XmbIcon("explore_category_sysconf", name, "\u2699")
        val theme = sys("tex_thema")
        val music = sys("tex_music")
        val sound = sys("tex_sound")
        val power = sys("tex_power")
        val display = sys("tex_display")
        val security = sys("tex_security")
        val update = sys("tex_update")
        val words = sys("tex_chat")
        val library = sys("tex_game")
        val trophy = XmbIcon("explore_category_psn", "item_tex_trophy", "\uD83C\uDFC6")
        val saved = XmbIcon("explore_category_photo", "ps3sd_tex_default", "\uD83D\uDCBE")
        val folder = XmbIcon("explore_category_game", "item_tex_plain_folder", "\uD83D\uDCC2")
        val export = XmbIcon("explore_category_video", "item_tex_upload", "\u2B06")
        val logo = XmbIcon("explore_category_game", "item_tex_ps3logo", "\u25B6")
        val byTitle = HashMap<String, XmbIcon>()
        fun put(icon: XmbIcon, vararg keys: String) = keys.forEach { byTitle[I18n.get(it)] = icon }
        put(theme, "app.theme", "app.bg.simple", "app.bg.flurry", "app.bg.saver", "app.bg.flurry.preset", "app.bg.flux.preset",
            "app.barOpacity", "app.barColor", "app.bgDimming", "app.bgColor", "app.bgColor.rgb", "app.bgColor.monthly")
        put(music, "app.libraryMusic", "app.libraryMusic.volume", "app.libraryMusic.choose", "app.libraryMusic.reset",
            "app.pauseMusic", "app.pauseMusic.volume", "app.pauseMusic.choose", "app.pauseMusic.reset", "xmb.music.import")
        put(sound, "app.menuSfx", "app.menuSfx.volume", "app.menuSfx.choose", "app.menuSfx.reset")
        put(trophy, "app.trophySound", "app.trophySound.volume", "app.trophySound.choose", "app.trophySound.clear", "app.trophySound.test")
        put(power, "app.batteryWarnings")
        put(display, "secondScreen.label", "secondScreen.moveOsd", "app.launcherRotation")
        put(security, "app.blockHome", "app.resetAll", "app.reset")
        put(logo, "app.bootLogo", "app.bootLogo.choose", "app.bootLogo.reset")
        put(words, "app.language", "app.keyboard.systemIme")
        put(library, "app.library.search", "app.library.recents", "app.library.autoPlaylists", "games.overflow.gridNames",
            "app.library.coverSize", "app.library.opacity", "app.toolbarPosition", "app.shareInstalled", "app.clearCache")
        put(saved, "app.backup.export", "app.backup.import", "app.savedata.import", "app.savedata.importFolder", "app.savedata.manage")
        put(folder, "app.gameFolders")
        put(export, "app.frontendExport")
        put(update, "perf.configDb.label", "perf.configDb.apply", "perf.configDb.remove")
        val byKey = mapOf(
            "xmb.language" to words,
            "xmb.gameFolders" to folder,
            "xmb.frontendExport" to export,
            "xmb.configDb.download" to update,
            "xmb.configDb.apply" to update,
            "xmb.configDb.remove" to update,
        )
        val fn: (XmbNode) -> XmbIcon? = { n -> byKey[n.key] ?: byTitle[n.title] }
        fn
    }
}

/** Tabs that are not rows of settings, shown as the app's own screens in a panel. */
private val PANEL_TABS = emptySet<SettingsCategory>()

/** All Core Settings: every setting the core has, by section, from its own config tree. */
@Composable
fun xmbCoreSettingsNode(scope: SettingsScope, serial: String?): XmbNode = XmbNode.Folder(
    key = "settings.core",
    title = str("core.settings.title"),
    icon = XmbIcon("explore_category_sysconf", "tex_sett", "\uD83E\uDDE9"),
    sub = str("xmb.core.desc"),
) { XmbCoreSettings.sections(scope, serial) }

/** A tab's rows, for the XMB to list: the tab composed as the settings screen composes it. */
@Composable
private fun XmbSettingsTabHost(category: SettingsCategory, game: GameInfo?) {
    val vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "xmb.settings.${category.name}.${game?.uri}")
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(category, game?.uri) {
        vm.load(category, game)
        loaded = true
    }
    if (loaded) CategoryContent(category, vm)
}

/** The Settings column's own words, from the firmware or the app. */
@Composable
private fun sysconf(id: String, key: String): String = fw("explore_category_sysconf", id, key)

/** The line the console shows under a focused setting, when the firmware has it in this language. */
@Composable
private fun explanation(id: String): String? = XmbText.find("explore_category_sysconf", id)

/** On and Off as the console words them. */
@Composable
internal fun xmbOnOff(): Pair<String, String> =
    fw("explore_plugin_full", "msg_on", "xmb.on") to fw("explore_plugin_full", "msg_off", "xmb.off")

/**
 * Theme Settings: the theme (Original, or a PS3 theme with its background, icons and sounds) and
 * importing one, the XMB's colour, and how much of each game's own media it shows.
 */
@Composable
private fun xmbThemeSettings(): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", "tex_thema", "\uD83C\uDFA8")
    val themes = LibraryBackground.saved.value
    val original = str("xmb.theme.original")
    val themeLabels = listOf(original) + themes.map { it.name }
    val themeTitle = str("xmb.theme.theme")
    val themeDesc = str("xmb.theme.theme.desc")
    val importTitle = str("xmb.theme.import")
    val importDesc = str("xmb.theme.import.desc")
    val importTheme = rememberXmbThemeImport()
    // What the theme in use brought for the XMB: its icons and its sounds, counted, or word that a
    // theme added before ARMSX3 read them, whose .p3t was not found at startup to read them from
    // (LibraryBackground.restoreXmbAssets), has only its picture until it is installed again.
    val assetsFormat = str("xmb.theme.assets")
    val noAssets = str("xmb.theme.noAssets")
    val selectedTheme = XmbTheme.selected.value
    // Read again when the theme is: its icons found at startup move the revision on.
    val revision = XmbTheme.revision.intValue
    val inUse = remember(selectedTheme, themes) { XmbTheme.entry() }
    val unread = remember(inUse, revision) { inUse?.let(LibraryBackground::lacksXmbAssets) == true }
    val assets = remember(inUse, revision) { inUse?.let(XmbTheme::assetCounts) }
    val ok = str("action.ok")
    val assetsLine = if (unread) noAssets else assets?.let { (i, s) -> assetsFormat.format(i, s) }
    val colorTitle = str("xmb.theme.color")
    val colorDesc = str("xmb.theme.color.desc")
    val colorDefault = str("xmb.theme.color.default")
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val months = remember(locale) {
        java.text.DateFormatSymbols.getInstance(locale).months.take(12).map { m -> m.replaceFirstChar { it.titlecase(locale) } }
    }
    val colorLabels = listOf(colorDefault) + months + str("xmb.theme.color.cycle")
    val backgrounds = str("xmb.theme.backgrounds")
    val backgroundsDesc = str("xmb.theme.backgrounds.desc")
    val icons = str("xmb.theme.icons")
    val iconsDesc = str("xmb.theme.icons.desc")
    // What moves behind the XMB with no theme: the console's wave, the simple 2D wave, or one of
    // the library's screensavers. The same choice the library makes, so both stay in step.
    val bgLabels = listOf(str("xmb.theme.background.wave"), str("xmb.theme.background.simple"), str("xmb.theme.background.saver"))
    val background = XmbNode.Setting(
        key = "theme.background",
        title = str("xmb.theme.background"),
        icon = icon,
        editor = XmbEditor.Choice(bgLabels, { xmbBackground() }) { i ->
            com.armsx2.ui.home.LibraryBackground.setFlurry(i == 2)
            com.armsx2.ui.home.LibraryBackground.setAnimated2D(i == 1)
        },
        value = { bgLabels[xmbBackground()] },
        description = str("xmb.theme.background.desc"),
    )
    val saverTitle = str("app.bg.saver")
    val flurryPreset = str("app.bg.flurry.preset")
    val fluxPreset = str("app.bg.flux.preset")
    val random = str("app.bg.flurry.random")
    fun saverNodes(): List<XmbNode> {
        if (xmbBackground() != 2) return emptyList()
        val lb = com.armsx2.ui.home.LibraryBackground
        val kinds = listOf("Flurry", "Flux", "Plasma", "SolarWinds", "Hyperspace", "Lattice", "Skyrocket")
        val kind = lb.saverKind.value
        val nodes = mutableListOf<XmbNode>(
            XmbNode.Setting("theme.saver", saverTitle, icon, XmbEditor.Choice(kinds, { lb.saverKind.value }) { lb.setSaverKind(it) }, { kinds.getOrElse(lb.saverKind.value) { "" } }),
        )
        if (kind == 0) {
            val values = listOf(99, 0, 1, 2, 3, 4, 5, -1)
            val names = listOf(random, "Water", "Fire", "Psychedelic", "RGB", "Binary", "Classic", "Insane")
            nodes += XmbNode.Setting(
                "theme.saver.flurry", flurryPreset, icon,
                XmbEditor.Choice(names, { values.indexOf(lb.flurryPreset.value).coerceAtLeast(0) }) { lb.setFlurryPreset(values[it]) },
                { names[values.indexOf(lb.flurryPreset.value).coerceAtLeast(0)] },
            )
        } else if (kind != 4 && kind != 6) {
            val values = listOf(99, 1, 2, 3, 4, 5, 6)
            val names = listOf(random) + when (kind) {
                1 -> listOf("Regular", "Hypnotic", "Insane", "Sparklers", "Paradigm", "Galactic")
                2 -> listOf("Classic", "Tight", "Wide", "Fast", "Slow drift", "Coarse")
                5 -> listOf("Regular", "Chainmail", "Brass Mesh", "Computer", "Slick", "Tasty")
                else -> listOf("Regular", "Cosmic Strings", "Cold Pricklies", "Space Fur", "Jiggly", "Undertow")
            }
            nodes += XmbNode.Setting(
                "theme.saver.preset", fluxPreset, icon,
                XmbEditor.Choice(names, { values.indexOf(lb.rssPreset.value).coerceAtLeast(0) }) { lb.setRssPreset(values[it]) },
                { names[values.indexOf(lb.rssPreset.value).coerceAtLeast(0)] },
            )
        }
        return nodes
    }
    return XmbNode.Folder(
        key = "settings.theme",
        title = sysconf("msg_theme_settings", "xmb.set.theme"),
        icon = icon,
        sub = explanation("msg_theme_settings_explanation"),
        live = true,
    ) {
        listOf(
            XmbNode.Setting(
                key = "theme.theme",
                title = themeTitle,
                icon = icon,
                editor = XmbEditor.Choice(
                    themeLabels,
                    { themes.indexOfFirst { it.folder.name == XmbTheme.selected.value } + 1 },
                ) { i ->
                    val picked = themes.getOrNull(i - 1)
                    XmbTheme.select(picked)
                    // Said as it is picked, not only in the description: a theme added before its
                    // icons were read otherwise just looks like one whose icons do not work.
                    if (picked != null && LibraryBackground.lacksXmbAssets(picked)) {
                        XmbDialogs.target()?.tell(noAssets, ok, error = false)
                    }
                },
                value = { XmbTheme.entry()?.name ?: original },
                description = listOfNotNull(assetsLine, themeDesc).joinToString("\n"),
            ),
            XmbNode.Action("theme.import", importTitle, icon, importDesc) { importTheme() },
            XmbNode.Setting(
                key = "theme.color",
                title = colorTitle,
                icon = icon,
                editor = XmbEditor.Choice(colorLabels, { XmbMode.themeColor.value + 1 }) { XmbMode.setThemeColor(it - 1) }.apply {
                    // Default as the console marks it, in silver; then each month's own colour.
                    swatches = listOf(androidx.compose.ui.graphics.Color(0xFFC0C0C0)) + (0 until 12).map { month ->
                        val c = XmbOriginalColors.swatch(month)
                        androidx.compose.ui.graphics.Color(c[0], c[1], c[2])
                    } + androidx.compose.ui.graphics.Color(0xFFF2F2F2)
                },
                value = { colorLabels.getOrElse(XmbMode.themeColor.value + 1) { colorDefault } },
                description = colorDesc,
            ),
            background,
        ) + saverNodes() + listOf(
            toggle("theme.backgrounds", backgrounds, icon, on, off, backgroundsDesc, { XmbMode.gameBackgrounds.value }) { XmbMode.setGameBackgrounds(it) },
            toggle("theme.icons", icons, icon, on, off, iconsDesc, { XmbMode.animatedIcons.value }) { XmbMode.setAnimatedIcons(it) },
        )
    }
}

/**
 * Import Theme: the system picker, then the theme imported as the library imports one (its
 * background, and now its XMB icons and sounds too), kept with the library's saved backgrounds
 * and made the XMB's theme. The library's own background is left as it is.
 */
@Composable
private fun rememberXmbThemeImport(): () -> Unit {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (message, kept) = withContext(Dispatchers.IO) {
                if (!LibraryBackground.isTheme(context, picked)) return@withContext "xmb.theme.notTheme" to null
                val outcome = LibraryBackground.importTheme(context, picked)
                val name = outcome.name ?: LibraryBackground.displayName(context, picked)?.substringBeforeLast('.') ?: "PS3 theme"
                outcome.messageKey to outcome.file?.let { LibraryBackground.keep(context, outcome, name) }
            }
            kept?.let {
                LibraryBackground.add(it)
                XmbTheme.select(it)
                val gone = LibraryBackground.replaceOlder(it)
                withContext(Dispatchers.IO) { gone.forEach { f -> runCatching { f.deleteRecursively() } } }
            }
            message?.let { android.widget.Toast.makeText(context, I18n.get(it), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    return {
        com.armsx2.ui.common.SystemPickers.launchWithFallback(picker, arrayOf("*/*")) {
            android.widget.Toast.makeText(context, I18n.get("browse.noSystemPicker"), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

private fun toggle(
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

/** One settings tab, as the settings screen shows it, in the XMB's panel. */
@Composable
private fun XmbSettingsTabPanel(category: SettingsCategory, game: GameInfo?) {
    val vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(category, game?.uri) {
        vm.load(category, game)
        loaded = true
    }
    if (!loaded) return
    XmbPanelTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CategoryContent(category, vm)
            Spacer(Modifier.height(64.dp))
        }
    }
}

/** The app's own screens, dressed for the XMB: dark glass over the wave, in the XMB's font. */
@Composable
fun XmbPanelTheme(content: @Composable () -> Unit) {
    val font = rememberXmbFont()
    val base = MaterialTheme.colorScheme
    val scheme = base.copy(
        background = Color.Transparent,
        surface = Color(0x66101018),
        surfaceVariant = Color(0x55202030),
        surfaceContainer = Color(0x55181822),
        surfaceContainerLow = Color(0x44141420),
        surfaceContainerHigh = Color(0x66202030),
        surfaceContainerHighest = Color(0x772A2A3A),
        onSurface = Color.White,
        onBackground = Color.White,
        onSurfaceVariant = Color(0xFFD6D6E0),
    )
    MaterialTheme(colorScheme = scheme, typography = withFont(MaterialTheme.typography, font), content = content)
}

private fun withFont(t: Typography, font: FontFamily): Typography = Typography(
    displayLarge = t.displayLarge.copy(fontFamily = font),
    displayMedium = t.displayMedium.copy(fontFamily = font),
    displaySmall = t.displaySmall.copy(fontFamily = font),
    headlineLarge = t.headlineLarge.copy(fontFamily = font),
    headlineMedium = t.headlineMedium.copy(fontFamily = font),
    headlineSmall = t.headlineSmall.copy(fontFamily = font),
    titleLarge = t.titleLarge.copy(fontFamily = font),
    titleMedium = t.titleMedium.copy(fontFamily = font),
    titleSmall = t.titleSmall.copy(fontFamily = font),
    bodyLarge = t.bodyLarge.copy(fontFamily = font),
    bodyMedium = t.bodyMedium.copy(fontFamily = font),
    bodySmall = t.bodySmall.copy(fontFamily = font),
    labelLarge = t.labelLarge.copy(fontFamily = font),
    labelMedium = t.labelMedium.copy(fontFamily = font),
    labelSmall = t.labelSmall.copy(fontFamily = font),
)

/**
 * All Core Settings as XMB lists, read from the core's config tree (settingsGet, the same source
 * as the All Core Settings screen) and written the way that screen writes: settingsSet, and the
 * edit recorded for [SettingsScope] so the curated settings do not write over it on the next apply.
 */
object XmbCoreSettings {
    /** What each node holds now, by path, so a picker's result shows at once. */
    private val values = mutableStateMapOf<String, String>()

    /** What this list's tier remembers (CoreSettingOverrides), by path: the nodes Forget is for. */
    private val remembered = mutableStateOf<Map<String, String>>(emptyMap())

    /**
     * What each node holds on a stock install, as the All Core Settings screen works it out (the
     * curated push's value where a curated field owns the node, the core's default elsewhere), so
     * both mark the same rows Not default. Null, and nothing marked, until it has been worked out.
     */
    private val stock = mutableStateOf<Map<String, String>?>(null)
    private var stockAsked = false

    private fun askStock() {
        if (stockAsked) return
        stockAsked = true
        kotlin.concurrent.thread(name = "xmb-core-stock", isDaemon = true) {
            val found = runCatching {
                com.armsx2.ui.settings.StockSettings.coreWrites().mapValues { com.armsx2.ui.settings.decodeJson(it.value) }
            }.getOrDefault(emptyMap())
            android.os.Handler(android.os.Looper.getMainLooper()).post { stock.value = found }
        }
    }

    private fun load(): List<CoreSetting> = runCatching {
        val raw = ARMSX3.instance.settingsGet("")
        if (raw.isBlank()) emptyList() else buildList { flatten(JSONObject(raw), "", "", this) }
    }.getOrDefault(emptyList())

    fun sections(scope: SettingsScope, serial: String?): List<XmbNode> {
        val all = load()
        all.forEach { values[it.path] = it.value }
        askStock()
        remembered.value = runCatching { CoreSettingOverrides.load(scope, serial) }.getOrDefault(emptyMap())
        val icon = XmbIcon("explore_category_sysconf", "tex_sett", "\u2699")
        // The remembered ones on their own, as the screen's "Show only these": each can be forgotten,
        // or all of them at once.
        val rememberedFolder = XmbNode.Folder(
            "core.remembered",
            I18n.get("core.settings.overrideCount"),
            icon,
            I18n.get(if (scope == SettingsScope.Game) "core.settings.scope.game" else "core.settings.scope.global"),
            live = true,
        ) {
            val now = remembered.value
            buildList {
                if (now.isEmpty()) {
                    add(XmbNode.Action("core.remembered.none", I18n.get("xmb.core.rememberedNone"), icon) {})
                } else {
                    add(XmbNode.Action("core.remembered.forgetAll", I18n.get("core.settings.reset"), icon, "${now.size}") {
                        XmbDialogs.target()?.ask(I18n.get("xmb.core.forgetAllAsk"), I18n.get("core.settings.reset"), I18n.get("action.cancel"), focusNo = true) {
                            forget(all.filter { it.path in now }, scope, serial, everything = true)
                        }
                    })
                    all.filter { it.path in now }.forEach { s -> node(s, scope, serial)?.let(::add) }
                }
            }
        }
        // Search first: typed on the pad keyboard, the matches open as a list of their own.
        val searchTitle = I18n.get("xmb.core.search")
        val search = XmbNode.Action("core.search", searchTitle, XmbIcon("explore_category_sysconf", "tex_sett", "\uD83D\uDD0D"), I18n.get("xmb.core.search.desc")) {
            XmbKeyboard.ask(searchTitle) { typed ->
                val query = typed.trim()
                if (query.isEmpty()) return@ask
                val words = query.lowercase().split(' ').filter { it.isNotBlank() }
                val hits = all.filter { s ->
                    val hay = "${s.label} ${s.path} ${s.section}".lowercase()
                    words.all { it in hay }
                }.mapNotNull { node(it, scope, serial) }
                val c = XmbDialogs.target() ?: return@ask
                if (hits.isEmpty()) {
                    c.tell(I18n.get("xmb.core.noResults").format(query), I18n.get("xmb.ok"))
                } else {
                    val results = XmbNode.Folder("core.results", "\u201C$query\u201D", XmbIcon("explore_category_sysconf", "tex_sett", "\uD83D\uDD0D"), "${hits.size}") { hits }
                    XmbSounds.play(XmbSounds.Sfx.DECIDE)
                    c.levels.add(XmbController.Level(results, hits, 0))
                }
            }
        }
        return listOf(search, rememberedFolder) + all.groupBy { it.section }.map { (section, settings) ->
            XmbNode.Folder(
                key = "core.$section",
                title = section,
                icon = XmbIcon("explore_category_sysconf", iconFor(section), "⚙"),
                sub = I18n.get("xmb.core.count").format(settings.size),
            ) { settings.mapNotNull { node(it, scope, serial) } }
        }
    }

    private fun iconFor(section: String): String = when (section.lowercase()) {
        "core" -> "tex_game"
        "video" -> "tex_display"
        "audio" -> "tex_sound"
        "input/output" -> "tex_pd"
        "system" -> "tex_console"
        "net" -> "tex_network"
        "savestate" -> "tex_power"
        else -> "tex_security"
    }

    private fun current(s: CoreSetting) = values[s.path] ?: s.value

    private fun encode(type: String, raw: String): String = when (type) {
        "bool", "int", "uint", "float", "set" -> raw
        else -> JSONObject.quote(raw)
    }

    private fun write(s: CoreSetting, raw: String, scope: SettingsScope, serial: String?) {
        val encoded = encode(s.type, raw)
        runCatching { ARMSX3.instance.settingsSet(s.path, encoded) }
        runCatching { CoreSettingOverrides.record(scope, serial, s.path, encoded) }
        android.util.Log.i("ARMSX3-Override", "record [$scope ${serial.orEmpty()}] ${s.path} = $encoded (XMB)")
        remembered.value = remembered.value + (s.path to encoded)
        // What the core actually kept (it can clamp), else what was sent.
        val reread = runCatching { ARMSX3.instance.settingsGet(s.path) }.getOrNull()
        val kept = reread?.let { raw2 -> runCatching { JSONObject(raw2).opt("value")?.toString() }.getOrNull() }
        values[s.path] = kept ?: raw
    }

    /**
     * Forget what this tier remembers for [settings] and put them back, as the screen's Forget and
     * Forget all do: the record dropped, a curated field holding the node reset, the core's default
     * written, and the curated settings pushed again over the nodes they own.
     */
    private fun forget(settings: List<CoreSetting>, scope: SettingsScope, serial: String?, everything: Boolean = false) {
        val paths = settings.map { it.path }
        runCatching {
            if (everything) CoreSettingOverrides.clear(scope, serial) else CoreSettingOverrides.forget(scope, serial, *paths.toTypedArray())
        }
        val reset = runCatching { com.armsx2.config.ConfigStore.resetFieldsWriting(scope, serial, paths) }.getOrDefault(emptySet())
        android.util.Log.i("ARMSX3-Override", "forget [$scope ${serial.orEmpty()}] $paths -> reset fields $reset (XMB)")
        if (reset.isNotEmpty()) com.armsx2.ui.InGameOverlay.reloadSettings()
        settings.forEach { runCatching { ARMSX3.instance.settingsSet(it.path, encode(it.type, it.default)) } }
        runCatching {
            com.armsx2.config.ConfigStore.resolveForGame(com.armsx2.runtime.MainActivityRuntime.currentGame.value?.settingsKey).applyTo()
        }
        settings.forEach { s ->
            val reread = runCatching { ARMSX3.instance.settingsGet(s.path) }.getOrNull()
            values[s.path] = reread?.let { r -> runCatching { JSONObject(r).opt("value")?.toString() }.getOrNull() } ?: s.default
        }
        remembered.value = runCatching { CoreSettingOverrides.load(scope, serial) }.getOrDefault(emptyMap())
    }

    private fun node(s: CoreSetting, scope: SettingsScope, serial: String?): XmbNode? {
        val icon = XmbIcon()
        // Not default as the screen measures it (see stock), and Remembered here beside the value.
        val changed = { stock.value?.let { st -> !sameValue(s.type, current(s), st[s.path] ?: s.default) } ?: false }
        val rememberedWord = I18n.get("core.settings.overridden")
        fun shown(text: String) = if (s.path in remembered.value) "$text   $rememberedWord" else text
        // Forget, or for a node only a curated field holds off its default, the screen's Reset: both
        // put the node back the same way.
        val forgetOption = XmbOption(I18n.get("core.settings.clearOne")) { forget(listOf(s), scope, serial) }
        return when {
            s.type == "bool" -> XmbNode.Setting(
                key = s.path,
                title = s.label,
                icon = icon,
                editor = XmbEditor.Toggle(get = { current(s) == "true" }, set = { write(s, it.toString(), scope, serial) }),
                value = { shown(I18n.get(if (current(s) == "true") "xmb.on" else "xmb.off")) },
                changed = changed,
                options = listOf(forgetOption),
            )
            s.variants.isNotEmpty() -> XmbNode.Setting(
                key = s.path,
                title = s.label,
                icon = icon,
                editor = XmbEditor.Choice(
                    labels = s.variants,
                    get = { s.variants.indexOf(current(s)).coerceAtLeast(0) },
                    set = { i -> s.variants.getOrNull(i)?.let { write(s, it, scope, serial) } },
                ),
                value = { shown(current(s)) },
                changed = changed,
                options = listOf(forgetOption),
            )
            (s.type == "int" || s.type == "uint") && s.min != null && s.max != null && s.max > s.min -> {
                val span = (s.max - s.min).toFloat()
                // A wide range moves in steps of about a hundredth of it, a narrow one by 1.
                val step = if (span > 200f) maxOf(1f, Math.round(span / 100f).toFloat()) else 1f
                XmbNode.Setting(
                    key = s.path,
                    title = s.label,
                    icon = icon,
                    editor = XmbEditor.Range(
                        min = s.min.toFloat(),
                        max = s.max.toFloat(),
                        step = step,
                        get = { current(s).toFloatOrNull() ?: s.min.toFloat() },
                        set = { v -> write(s, v.toLong().toString(), scope, serial) },
                        format = { it.toLong().toString() },
                    ),
                    value = { shown(current(s)) },
                    changed = changed,
                    options = listOf(forgetOption),
                )
            }
            // Text, sets and unbounded numbers: shown, and edited on the full screen.
            else -> XmbNode.Action(
                key = s.path,
                title = s.label,
                icon = icon,
                sub = current(s).take(48),
            ) {
                // The full screen for what has no picker here: over the game when in one.
                if (com.armsx2.ui.WindowImpl.overlayVisible.value) {
                    com.armsx2.ui.WindowImpl.openInGameScreen(com.armsx2.ui.InGameScreen.CoreSettings)
                } else {
                    com.armsx2.navigation.UiNavigator.navigate(com.armsx2.navigation.AppRoute.CoreSettings)
                }
            }
        }
    }
}

/** XMB mode's background with no theme: 0 the console's wave, 1 the simple 2D wave, 2 a screensaver. */
internal fun xmbBackground(): Int = when {
    com.armsx2.ui.home.LibraryBackground.flurry.value -> 2
    com.armsx2.ui.home.LibraryBackground.animated2D.value -> 1
    else -> 0
}
