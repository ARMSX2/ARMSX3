package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.config.ConfigStore
import com.armsx2.config.CoreSettingOverrides
import com.armsx2.config.SettingsScope
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every RPCS3 setting, generated from the core's own config tree.
 *
 * The curated tabs are deliberately small and opinionated -- they cover what a handheld
 * player actually touches. But the PS3 config is far larger than any hand-written screen
 * can track, and a static UI silently goes stale the moment upstream adds or renames a
 * node (which is exactly how 176 of the 245 search-index entries ended up naming PCSX2
 * settings). Nothing here is hardcoded: _rpcsx_settingsGet("") walks g_cfg and emits the
 * whole tree with each node's type, current value, default, enum variants and range, and
 * this renders whatever it finds.
 *
 * Writes go straight back through settingsSet, so a node added upstream tomorrow is
 * editable here with no app change.
 */

/** One firmware library Libraries Control can name, and how it runs when it is not named. */
private data class FirmwareLibrary(val name: String, val defaultHle: Boolean)

/** One editable leaf of the core config tree. */
private data class CoreSetting(
    val path: String,
    val name: String,
    val section: String,
    val type: String,
    val value: String,
    val default: String,
    val variants: List<String>,
    val min: Long?,
    val max: Long?,
    // What the row is called on screen. The node name, except for Libraries Control, which
    // RPCS3's own settings dialog calls Firmware Libraries: that is the name people search for.
    val label: String = name,
    // Libraries Control only: every library it can name. A "set" leaf's value and default
    // are JSON arrays, the shape settingsSet takes for it.
    val choices: List<FirmwareLibrary> = emptyList(),
)

/** Flatten the tree the core emits into a list of leaves, remembering each one's path. */
private fun flatten(
    node: JSONObject,
    prefix: String,
    section: String,
    out: MutableList<CoreSetting>,
) {
    for (key in node.keys()) {
        val child = node.optJSONObject(key) ?: continue
        // A leaf carries "type"; anything else is a container. That is the only structural
        // signal the emitter gives, and it is unambiguous: cfg::node never emits "type".
        val type = child.optString("type", "")
        // Paths use "@@" because that is what find_cfg_node splits on.
        val path = if (prefix.isEmpty()) key else "$prefix@@$key"
        if (type.isEmpty()) {
            flatten(child, path, if (prefix.isEmpty()) key else section, out)
            continue
        }
        val variants = child.optJSONArray("variants")?.let { array ->
            List(array.length()) { array.optString(it) }
        }.orEmpty()
        val choices = child.optJSONArray("choices")?.let { array ->
            List(array.length()) { array.optJSONObject(it) }.mapNotNull { choice ->
                val name = choice?.optString("name").orEmpty()
                if (name.isEmpty()) null else FirmwareLibrary(name, choice!!.optBoolean("hle"))
            }
        }.orEmpty()
        out += CoreSetting(
            path = path,
            name = key,
            section = section.ifEmpty { key },
            type = type,
            // Bools arrive as real JSON booleans, sets as arrays, everything else as strings.
            value = when (type) {
                "bool" -> child.optBoolean("value").toString()
                "set" -> (child.optJSONArray("value") ?: JSONArray()).toString()
                else -> child.optString("value")
            },
            default = when (type) {
                "bool" -> child.optBoolean("default").toString()
                "set" -> (child.optJSONArray("default") ?: JSONArray()).toString()
                else -> child.optString("default")
            },
            variants = variants,
            min = child.optString("min").toLongOrNull(),
            max = child.optString("max").toLongOrNull(),
            label = if (choices.isNotEmpty()) I18n.get("core.settings.libraries.title") else key,
            choices = choices,
        )
    }
}

/** A value as settingsSet takes it (JSON) in the form the tree reports it: bools and numbers bare,
 *  strings unquoted, sets as their array. */
private fun decodeJson(json: String): String =
    runCatching { JSONArray("[$json]").get(0).toString() }.getOrDefault(json)

/** Whether two values of a node read the same. By number where both are one, since the curated
 *  push writes a float as "1.0" and the tree can report the same value as "1"; by membership for a
 *  set, which has no order. */
private fun sameValue(type: String, a: String, b: String): Boolean {
    if (a == b) return true
    if (type == "set") {
        fun members(json: String) = runCatching {
            val array = JSONArray(json)
            List(array.length()) { array.optString(it) }.toSet()
        }.getOrNull()
        return members(a)?.let { it == members(b) } ?: false
    }
    val x = a.toDoubleOrNull() ?: return false
    val y = b.toDoubleOrNull() ?: return false
    return x == y
}

/**
 * [scope] and [serial] say where an edit is REMEMBERED, not where it is applied: every write
 * goes straight into the live config tree either way, and the store is what decides whether the
 * value comes back for one title or for all of them on the next apply.
 *
 * Both are passed in rather than read from the overlay here, because the two entry points want
 * different answers and the shared scope state cannot tell them apart: the library drawer is a
 * global screen and would inherit whatever scope the last per-game settings visit left behind,
 * while the in-game menu opens with the running title already resolved.
 */
@Composable
fun CoreSettingsScreen(onBack: () -> Unit, scope: SettingsScope, serial: String?) {
    val perGame = scope == SettingsScope.Game && !serial.isNullOrBlank()
    var all by remember { mutableStateOf<List<CoreSetting>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after every write so the tree is re-read and dependent nodes (a value the core
    // clamped, say) show what the core actually stored rather than what we sent.
    var revision by remember { mutableStateOf(0) }
    // What this scope currently remembers. Read alongside the tree so a row can say whether
    // its value is a recorded override rather than whatever the curated settings last wrote.
    //
    // Until this existed there was no way to see, let alone undo, a recorded path: the store
    // re-pushes at the tail of applyTo, so an override silently beats every curated screen
    // forever, and the only evidence is a config.yml that disagrees with the UI. Two separate
    // one-shot migrations had already been written to purge leftovers by name; both had run
    // and RSX Profiler was still recorded on a test device.
    var overrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Reset is two taps rather than an AlertDialog: dialogs swallow gamepad keys here, and
    // this screen is reachable from the in-game menu with only a controller in hand.
    var confirmingReset by remember { mutableStateOf(false) }
    // Narrow the list to just the remembered settings.
    //
    // Every override already has its own Forget button, but finding them meant scrolling the
    // whole core list -- which is every node the core exposes -- so in practice the only usable
    // control was "Forget all". A stale override on ONE setting is the common case and cost a
    // long debugging session here, so it needs to be findable on its own.
    var onlyOverridden by remember { mutableStateOf(false) }
    // What each node holds on a stock install, for the Not default line: the value the curated push
    // writes there from stock Settings, where a curated field owns the node, and the core's own
    // default everywhere else. The curated value rather than RPCS3's where the two differ, so this
    // screen and the normal ones agree on what default means. Null until the dry run is back, and
    // no row is marked until then rather than every curated node flashing up against RPCS3's value.
    var curatedDefaults by remember { mutableStateOf<Map<String, String>?>(null) }
    // Nodes this scope's own curated settings hold off their default: set on a normal screen, so
    // there is no record here to Forget, and those rows get a Reset instead.
    var heldByTier by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        curatedDefaults = withContext(Dispatchers.Default) {
            runCatching { StockSettings.coreWrites().mapValues { decodeJson(it.value) } }.getOrDefault(emptyMap())
        }
    }

    LaunchedEffect(revision) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val raw = RPCSX.instance.settingsGet("")
                if (raw.isBlank()) return@runCatching emptyList<CoreSetting>()
                buildList { flatten(JSONObject(raw), "", "", this) }
            }
        }
        // I18n.get, not str(): this is a coroutine body, and str() is @Composable.
        loaded.onSuccess {
            all = it
            error = if (it.isEmpty()) I18n.get("core.settings.unavailable") else null
        }
        loaded.onFailure { error = I18n.get("core.settings.unavailable") }
        overrides = runCatching { CoreSettingOverrides.load(scope, serial) }.getOrDefault(emptyMap())
        heldByTier = runCatching { ConfigStore.nodesHeldBy(scope, serial) }.getOrDefault(emptySet())
    }

    // settingsSet takes JSON: bools and numbers bare, enums and strings quoted, sets as the
    // array they already are.
    fun encode(type: String, raw: String): String = when (type) {
        "bool", "int", "uint", "float", "set" -> raw
        else -> JSONObject.quote(raw)
    }

    /**
     * Re-push the curated settings after dropping a record.
     *
     * Forgetting an override only stops it being replayed; the value it already wrote is still
     * live in the core. For a node one of the normal screens also writes, this puts that screen's
     * value back immediately. For a node nothing curated owns, the caller restores the core's own
     * default first -- otherwise "reset" would leave the value exactly where the override put it
     * and look like it did nothing.
     */
    fun reapplyCurated() {
        runCatching {
            ConfigStore.resolveForGame(MainActivityRuntime.currentGame.value?.settingsKey).applyTo()
        }
    }

    /**
     * Put forgotten nodes back to their defaults, which takes more than dropping the record.
     *
     * A curated field in this tier can hold the same node, and the re-push would write its value
     * straight back: Forget on Web of Shadows' Accurate SPU Reservations dropped the core edit and
     * the switch stayed off, because the title's Performance settings had it off too. So those
     * fields go first, out of the title's overrides or back to stock (ConfigStore.resetFieldsWriting),
     * and the menus' copy of the settings is re-read so their next save cannot restore them.
     */
    fun restoreDefaults(settings: List<CoreSetting>) {
        val reset = runCatching {
            ConfigStore.resetFieldsWriting(scope, serial, settings.map { it.path })
        }.getOrDefault(emptySet())
        android.util.Log.i("ARMSX3-Override", "restore [$scope ${serial.orEmpty()}] ${settings.map { it.path }} -> reset fields $reset")
        if (reset.isNotEmpty()) InGameOverlay.reloadSettings()
        // Defaults first, curated second: applyTo below rewrites every node it owns, so the
        // only ones this actually decides are the nodes no curated screen touches.
        settings.forEach {
            runCatching { RPCSX.instance.settingsSet(it.path, encode(it.type, it.default)) }
        }
        reapplyCurated()
    }

    fun clearOne(setting: CoreSetting) {
        runCatching { CoreSettingOverrides.forget(scope, serial, setting.path) }
        restoreDefaults(listOf(setting))
        revision++
    }

    fun clearAll() {
        val cleared = overrides.keys.toSet()
        runCatching { CoreSettingOverrides.clear(scope, serial) }
        restoreDefaults(all.filter { it.path in cleared })
        confirmingReset = false
        onlyOverridden = false
        revision++
    }

    fun notDefault(setting: CoreSetting): Boolean {
        val stock = curatedDefaults ?: return false
        return !sameValue(setting.type, setting.value, stock[setting.path] ?: setting.default)
    }

    fun write(setting: CoreSetting, raw: String) {
        val encoded = encode(setting.type, raw)
        runCatching { RPCSX.instance.settingsSet(setting.path, encoded) }
        // Remember it, or applyTo will write the curated store back over this node the next
        // time any setting changes or the next time a game boots. Into this screen's tier:
        // a node set from the in-game menu belongs to the title being played, not to the
        // next game that boots.
        runCatching { CoreSettingOverrides.record(scope, serial, setting.path, encoded) }
        android.util.Log.i("ARMSX3-Override", "record [$scope ${serial.orEmpty()}] ${setting.path} = $encoded")
        revision++
    }

    val filtered = remember(all, query, onlyOverridden, overrides) {
        // Guarded on isNotEmpty: forgetting the last override would otherwise leave the filter
        // on with nothing to show and its own toggle hidden, i.e. an empty screen and no way out.
        val base = if (onlyOverridden && overrides.isNotEmpty()) all.filter { it.path in overrides } else all

        if (query.isBlank()) base
        else base.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.label.contains(query, ignoreCase = true) ||
                it.section.contains(query, ignoreCase = true) ||
                it.choices.any { library -> library.name.contains(query, ignoreCase = true) }
        }
    }

    ArmsBackdrop {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                str("core.settings.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                str("core.settings.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Which tier the next edit lands in. The screen looks identical either way, and a
            // per-game edit that quietly went global is the whole reason this store grew a
            // second tier, so it says so rather than leaving it to be discovered.
            Text(
                if (perGame) "${str("core.settings.scope.game")} · $serial"
                else str("core.settings.scope.global"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (overrides.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${str("core.settings.overrideCount")}: ${overrides.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onlyOverridden = !onlyOverridden }) {
                            Text(
                                if (onlyOverridden) str("core.settings.showAll")
                                else str("core.settings.showOnlyOverridden"),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = { if (confirmingReset) clearAll() else confirmingReset = true }) {
                            Text(
                                if (confirmingReset) str("core.settings.resetConfirm")
                                else str("core.settings.reset"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(str("action.search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // weight(1f), not just fillMaxWidth: an unweighted list in a Column takes the whole
            // remaining height, which left the Back button below it with nothing to lay out in.
            // The button was always here, it was simply off the bottom of the screen, and on a
            // touch-only device that made this the one screen with no visible way out. Weighting
            // the list makes it share the space and keeps Back on screen at every list length.
            //
            // A scrolling Column, not a LazyColumn, because the D-pad walks the controller nav
            // registry and a row is only in it while it is composed. A LazyColumn composes what
            // is on screen, two or three rows at this height, so Down from the last visible row
            // found nothing below it and stopped: the list could not be scrolled with a controller
            // past PPU Threads. Every other settings screen already composes all of its rows; this
            // one has about 270, which is fine to compose once.
            val listScroll = rememberScrollState()
            ControllerAutoScroll(listScroll)
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).verticalScroll(listScroll),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filtered.forEach { setting -> key(setting.path) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    setting.section,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Marked per row, not just counted in the header: the point of
                                // the count is to notice, the point of this is to know WHICH
                                // node is ignoring the normal settings screens.
                                if (setting.path in overrides) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            str("core.settings.overridden"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        TextButton(onClick = { clearOne(setting) }) {
                                            Text(
                                                str("core.settings.clearOne"),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                } else if (setting.path in heldByTier && notDefault(setting)) {
                                    // Off its default because of this scope's normal settings, with
                                    // nothing recorded here: the same reset as Forget, minus the record.
                                    TextButton(onClick = { clearOne(setting) }) {
                                        Text(
                                            str("action.reset"),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            CoreSettingRow(setting, overrides[setting.path], query, notDefault(setting)) { write(setting, it) }
                        }
                    }
                } }
            }

            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Text(str("action.back"))
            }
        }
    }
}

/** Pick a widget from the node's declared type, not from a hardcoded table.
 *
 *  Nav ids come from the node's path, not its name: names repeat across sections (Video and
 *  Audio both have a Renderer), and with every row registered at once two rows sharing an id
 *  share one registry slot, so one of them could never be reached with a controller. */
@Composable
private fun CoreSettingRow(
    setting: CoreSetting,
    remembered: String?,
    query: String,
    notDefault: Boolean,
    onWrite: (String) -> Unit,
) {
    when {
        // Marks itself: it shows the remembered list rather than the live one, and counts moved
        // libraries against each one's own default.
        setting.type == "set" && setting.choices.isNotEmpty() ->
            FirmwareLibrariesRow(setting, remembered, query, onWrite)

        setting.type == "bool" -> ToggleRow(
            setting.name,
            setting.value == "true",
            controllerId = "core:${setting.path}",
            notDefault = notDefault,
        ) { onWrite(it.toString()) }

        setting.variants.isNotEmpty() -> SegmentedGridRow(
            label = setting.name,
            options = setting.variants,
            selectedIndex = setting.variants.indexOf(setting.value).coerceAtLeast(0),
            columns = 2,
            controllerId = "core:${setting.path}",
            notDefault = notDefault,
            onChange = { onWrite(setting.variants[it]) },
        )

        // Only ranged numerics get a slider. An unbounded int (a port number, a byte
        // count) would give a slider covering the whole 32-bit range, which is useless.
        (setting.type == "int" || setting.type == "uint") &&
            setting.min != null && setting.max != null &&
            setting.max - setting.min in 1..100_000 -> IntSliderRow(
            label = setting.name,
            value = (setting.value.toLongOrNull() ?: setting.min).coerceIn(setting.min, setting.max).toInt(),
            min = setting.min.toInt(),
            max = setting.max.toInt(),
            notDefault = notDefault,
            onChange = { onWrite(it.toString()) },
        )

        else -> {
            if (notDefault) NotDefaultLabel(Modifier.padding(top = 6.dp))
            var text by remember(setting.path, setting.value) { mutableStateOf(setting.value) }
            // rememberUpdatedState so the focus callback -- which Compose may hold across
            // recompositions -- reads the CURRENT text rather than whatever it captured
            // when the modifier was first built.
            val latest by rememberUpdatedState(text)
            val original by rememberUpdatedState(setting.value)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(setting.name) },
                singleLine = true,
                // Commit on focus loss, not per keystroke: every write re-reads the whole
                // tree, and doing that per character would rebuild the list constantly.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .onFocusChanged { state ->
                        if (!state.isFocused && latest != original) onWrite(latest)
                    },
            )
        }
    }
}

/**
 * RPCS3's Firmware Libraries list, for one phone-sized column.
 *
 * Libraries Control holds overrides, not a full list: "<library>:lle" or "<library>:hle" for each
 * library forced away from the way it runs by default, exactly as RPCS3's settings dialog stores
 * them. Here every library gets one switch, on for LLE (the console's own code) and off for HLE
 * (the emulator's), starting from the default, and only the ones moved off their default are
 * written. Moved ones sort first, like the desktop lists.
 *
 * Collapsed until asked for: 142 rows would bury every setting below this one.
 *
 * [remembered] is what this screen's tier has on record. It is shown in preference to the live
 * value because the core will not change the list under a running game (sys_prx reads it when a
 * game loads a module), so an edit from the in-game menu is on record but not yet live; the
 * record is replayed at the next boot, before anything reads the list.
 */
@Composable
private fun FirmwareLibrariesRow(setting: CoreSetting, remembered: String?, query: String, onWrite: (String) -> Unit) {
    fun parse(json: String): Set<String> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    val shown = remembered ?: setting.value
    val overrides = remember(shown) { parse(shown) }
    val pending = remembered != null && parse(remembered) != parse(setting.value)

    fun isLle(library: FirmwareLibrary): Boolean = when {
        "${library.name}:lle" in overrides -> true
        "${library.name}:hle" in overrides -> false
        else -> !library.defaultHle
    }

    val changed = setting.choices.count { isLle(it) == it.defaultHle }

    // A search on the screen above that names a library opens the list already narrowed to it.
    val outerMatch = query.isNotBlank() && setting.choices.any { it.name.contains(query, ignoreCase = true) }
    // Keyed on the match so a search opens it and Hide still closes it.
    var open by remember(outerMatch) { mutableStateOf(outerMatch) }
    var search by remember { mutableStateOf("") }
    val filter = search.ifBlank { if (outerMatch) query else "" }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (changed > 0) NotDefaultLabel()
                Text(setting.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${str("core.settings.libraries.changed")}: $changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (changed > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { open = !open }) {
                Text(if (open) str("core.settings.libraries.hide") else str("core.settings.libraries.show"))
            }
        }

        if (pending) {
            Text(
                str("core.settings.libraries.pending"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (open) {
            Text(
                str("core.settings.libraries.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(str("core.settings.libraries.search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val rows = setting.choices
                .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                .sortedWith(compareBy<FirmwareLibrary> { isLle(it) != it.defaultHle }.thenBy { it.name })

            rows.forEach { library -> key(library.name) {
                val lle = isLle(library)
                ToggleRow(
                    label = library.name,
                    value = lle,
                    description = buildString {
                        append(if (library.defaultHle) str("core.settings.libraries.defaultHle") else str("core.settings.libraries.defaultLle"))
                        // RPCS3 says the same in its tooltip for these.
                        if (library.name.startsWith("libsysutil")) append(". ").append(str("core.settings.libraries.sysutil"))
                    },
                    controllerId = "core:libs:${library.name}",
                    notDefault = lle == library.defaultHle,
                ) { wantLle ->
                    val next = overrides.filterNot { it == "${library.name}:lle" || it == "${library.name}:hle" }.toMutableSet()
                    // Only a library moved off its default is written, the way RPCS3 stores it.
                    if (wantLle == library.defaultHle) next += "${library.name}:${if (wantLle) "lle" else "hle"}"
                    onWrite(JSONArray(next.sorted()).toString())
                }
            } }
        }
    }
}
