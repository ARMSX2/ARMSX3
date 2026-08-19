package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
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
        out += CoreSetting(
            path = path,
            name = key,
            section = section.ifEmpty { key },
            type = type,
            // Bools arrive as real JSON booleans, everything else as strings.
            value = if (type == "bool") child.optBoolean("value").toString()
            else child.optString("value"),
            default = if (type == "bool") child.optBoolean("default").toString()
            else child.optString("default"),
            variants = variants,
            min = child.optString("min").toLongOrNull(),
            max = child.optString("max").toLongOrNull(),
        )
    }
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
    }

    // settingsSet takes JSON: bools and numbers bare, enums and strings quoted.
    fun encode(type: String, raw: String): String = when (type) {
        "bool", "int", "uint", "float" -> raw
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

    fun clearOne(setting: CoreSetting) {
        runCatching { CoreSettingOverrides.forget(scope, serial, setting.path) }
        runCatching { RPCSX.instance.settingsSet(setting.path, encode(setting.type, setting.default)) }
        reapplyCurated()
        revision++
    }

    fun clearAll() {
        val cleared = overrides.keys.toSet()
        runCatching { CoreSettingOverrides.clear(scope, serial) }
        // Defaults first, curated second: applyTo below rewrites every node it owns, so the
        // only ones this actually decides are the nodes no curated screen touches.
        all.asSequence().filter { it.path in cleared }.forEach {
            runCatching { RPCSX.instance.settingsSet(it.path, encode(it.type, it.default)) }
        }
        reapplyCurated()
        confirmingReset = false
        revision++
    }

    fun write(setting: CoreSetting, raw: String) {
        val encoded = encode(setting.type, raw)
        runCatching { RPCSX.instance.settingsSet(setting.path, encoded) }
        // Remember it, or applyTo will write the curated store back over this node the next
        // time any setting changes or the next time a game boots. Into this screen's tier:
        // a node set from the in-game menu belongs to the title being played, not to the
        // next game that boots.
        runCatching { CoreSettingOverrides.record(scope, serial, setting.path, encoded) }
        revision++
    }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.section.contains(query, ignoreCase = true)
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
                    TextButton(onClick = { if (confirmingReset) clearAll() else confirmingReset = true }) {
                        Text(
                            if (confirmingReset) str("core.settings.resetConfirm")
                            else str("core.settings.reset"),
                            color = MaterialTheme.colorScheme.error,
                        )
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

            // weight(1f), not just fillMaxWidth: an unweighted LazyColumn in a Column takes the
            // whole remaining height, which left the Back button below it with nothing to lay out
            // in. The button was always here, it was simply off the bottom of the screen, and on a
            // touch-only device that made this the one screen with no visible way out. Weighting
            // the list makes it share the space and keeps Back on screen at every list length.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.path }) { setting ->
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
                                }
                            }
                            CoreSettingRow(setting) { write(setting, it) }
                        }
                    }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Text(str("action.back"))
            }
        }
    }
}

/** Pick a widget from the node's declared type, not from a hardcoded table. */
@Composable
private fun CoreSettingRow(setting: CoreSetting, onWrite: (String) -> Unit) {
    when {
        setting.type == "bool" -> ToggleRow(
            setting.name,
            setting.value == "true",
        ) { onWrite(it.toString()) }

        setting.variants.isNotEmpty() -> SegmentedGridRow(
            label = setting.name,
            options = setting.variants,
            selectedIndex = setting.variants.indexOf(setting.value).coerceAtLeast(0),
            columns = 2,
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
            onChange = { onWrite(it.toString()) },
        )

        else -> {
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
