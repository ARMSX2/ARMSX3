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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.armsx2.GameInfo
import com.armsx2.config.CoreSettingOverrides
import com.armsx2.config.SettingsScope
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.navigation.SettingsCategory
import com.armsx2.ui.settings.CoreSetting
import com.armsx2.ui.settings.flatten
import com.armsx2.ui.settings.sameValue
import com.armsx2.ui.settingshub.CategoryContent
import com.armsx2.ui.settingshub.SettingsViewModel
import com.armsx2.ui.settingshub.categoryTitle
import com.armsx3.core.ARMSX3
import org.json.JSONObject

/**
 * ARMSX3's settings in the XMB's Settings column: every tab of App Settings, each opening as a panel
 * over the XMB with the tab itself in it (so it behaves exactly as it does elsewhere: the same
 * scope, the same saving, the same per-game overrides), and All Core Settings as XMB lists, section
 * by section, edited with the XMB's own pickers.
 *
 * [scope] and [serial] are where edits are remembered: global from the library, the running title
 * from the in-game menu.
 */
@Composable
fun xmbSettingsItems(scope: SettingsScope, serial: String?, game: GameInfo? = null): List<XmbNode> {
    val forGame = game != null
    val tabs = buildList {
        if (!forGame) add(SettingsCategory.General to "tex_console")
        if (forGame) add(SettingsCategory.Info to "tex_bddvd")
        add(SettingsCategory.Performance to "tex_game")
        add(SettingsCategory.Graphics to "tex_display")
        add(SettingsCategory.Audio to "tex_sound")
        add(SettingsCategory.Controls to "tex_pd")
        add(SettingsCategory.Hotkeys to "tex_remote")
        add(SettingsCategory.Network to "tex_network")
        add(SettingsCategory.OnScreen to "tex_datetime")
        add(SettingsCategory.Skins to "tex_thema")
        add(SettingsCategory.Advanced to "tex_security")
        add(SettingsCategory.Patches to "tex_update")
        if (forGame) add(SettingsCategory.Mods to "tex_edyviewer")
    }
    val items = tabs.map { (category, icon) ->
        XmbNode.Panel(
            key = "settings.tab.${category.name}",
            title = categoryTitle(category),
            icon = XmbIcon("explore_category_sysconf", icon, "⚙"),
        ) { _ -> XmbSettingsTabPanel(category, game) }
    }
    val core = XmbNode.Folder(
        key = "settings.core",
        title = str("core.settings.title"),
        icon = XmbIcon("explore_category_sysconf", "tex_sett", "🧩"),
        sub = str("xmb.core.desc"),
    ) { XmbCoreSettings.sections(scope, serial) }
    return items + core
}

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
                com.armsx2.navigation.UiNavigator.navigate(com.armsx2.navigation.AppRoute.CoreSettings)
            }
        }
    }
}
