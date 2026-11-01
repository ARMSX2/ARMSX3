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
    fun tab(category: SettingsCategory, title: String, icon: String, sub: String? = null): XmbNode {
        val i = XmbIcon("explore_category_sysconf", icon, "\u2699")
        // The patch and mod lists, and a game's details, are pictures and lists of their own, not rows
        // of settings: those stay the app's screens, in a panel.
        if (category in PANEL_TABS) {
            return XmbNode.Panel("settings.tab.${category.name}", title, i, sub) { _ -> XmbSettingsTabPanel(category, game) }
        }
        val collector = XmbCollector(on, off, i)
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
        ) { collector.nodes }
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
                add(XmbNode.Action("settings.underclock", underclock, XmbIcon("explore_category_sysconf", "tex_power", "\u2699")) {
                    UiNavigator.navigate(AppRoute.Underclocking)
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

    val update = XmbNode.Action(
        "settings.update",
        sysconf("msg_update", "xmb.set.update"),
        XmbIcon("explore_category_sysconf", "tex_update", "\u2699"),
        explanation("msg_update_explanation"),
    ) { UiNavigator.navigate(AppRoute.BiosManager) }
    val system = tab(SettingsCategory.General, sysconf("msg_console_setting", "xmb.set.system"), "tex_console", explanation("msg_console_setting_explanation"))
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

/** Tabs that are not rows of settings, shown as the app's own screens in a panel. */
private val PANEL_TABS = setOf(SettingsCategory.Info, SettingsCategory.Patches, SettingsCategory.Mods)

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
    val colorTitle = str("xmb.theme.color")
    val colorDesc = str("xmb.theme.color.desc")
    val colorDefault = str("xmb.theme.color.default")
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val months = remember(locale) {
        java.text.DateFormatSymbols.getInstance(locale).months.take(12).map { m -> m.replaceFirstChar { it.titlecase(locale) } }
    }
    val colorLabels = listOf(colorDefault) + months
    val backgrounds = str("xmb.theme.backgrounds")
    val backgroundsDesc = str("xmb.theme.backgrounds.desc")
    val icons = str("xmb.theme.icons")
    val iconsDesc = str("xmb.theme.icons.desc")
    return XmbNode.Folder(
        key = "settings.theme",
        title = sysconf("msg_theme_settings", "xmb.set.theme"),
        icon = icon,
        sub = explanation("msg_theme_settings_explanation"),
    ) {
        listOf(
            XmbNode.Setting(
                key = "theme.theme",
                title = themeTitle,
                icon = icon,
                editor = XmbEditor.Choice(
                    themeLabels,
                    { themes.indexOfFirst { it.folder.name == XmbTheme.selected.value } + 1 },
                ) { XmbTheme.select(themes.getOrNull(it - 1)) },
                value = { XmbTheme.entry()?.name ?: original },
                description = themeDesc,
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
                    }
                },
                value = { colorLabels.getOrElse(XmbMode.themeColor.value + 1) { colorDefault } },
                description = colorDesc,
            ),
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

    private fun load(): List<CoreSetting> = runCatching {
        val raw = ARMSX3.instance.settingsGet("")
        if (raw.isBlank()) emptyList() else buildList { flatten(JSONObject(raw), "", "", this) }
    }.getOrDefault(emptyList())

    fun sections(scope: SettingsScope, serial: String?): List<XmbNode> {
        val all = load()
        all.forEach { values[it.path] = it.value }
        return all.groupBy { it.section }.map { (section, settings) ->
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
        // What the core actually kept (it can clamp), else what was sent.
        val reread = runCatching { ARMSX3.instance.settingsGet(s.path) }.getOrNull()
        val kept = reread?.let { raw2 -> runCatching { JSONObject(raw2).opt("value")?.toString() }.getOrNull() }
        values[s.path] = kept ?: raw
    }

    private fun node(s: CoreSetting, scope: SettingsScope, serial: String?): XmbNode? {
        val icon = XmbIcon()
        val changed = { !sameValue(s.type, current(s), s.default) }
        return when {
            s.type == "bool" -> XmbNode.Setting(
                key = s.path,
                title = s.label,
                icon = icon,
                editor = XmbEditor.Toggle(get = { current(s) == "true" }, set = { write(s, it.toString(), scope, serial) }),
                value = { I18n.get(if (current(s) == "true") "xmb.on" else "xmb.off") },
                changed = changed,
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
                value = { current(s) },
                changed = changed,
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
                    value = { current(s) },
                    changed = changed,
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
