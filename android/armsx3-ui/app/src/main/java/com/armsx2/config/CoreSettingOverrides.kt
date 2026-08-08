package com.armsx2.config

import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import net.rpcsx.RPCSX
import org.json.JSONObject

/**
 * Edits made on the All Core Settings screen, kept so they survive an apply.
 *
 * There are two sources of truth for the core config and only one of them was durable.
 * [Settings.applyTo] pushes the curated store over roughly 165 nodes on every settings
 * change and on every boot, so anything typed into the core screen that overlaps one of
 * them was silently reverted moments later. Reported as "I enable Use GPU texture scaling
 * and after a while it turns itself off": nothing turned it off, applyTo wrote the curated
 * value back over it.
 *
 * Rather than map every core node onto a curated field, which would have to be maintained
 * forever and could never cover nodes the curated store does not model, the edits are
 * recorded here by path and replayed AFTER the curated push. Last writer wins, and the last
 * writer is the user's explicit choice.
 *
 * Global rather than per-game, matching the screen itself, which edits the live config tree
 * and not a per-title layer.
 */
object CoreSettingOverrides {
    private const val KEY = "config.coreOverrides"

    /** path ("Video@@Write Color Buffers") to the JSON-encoded value settingsSet expects. */
    fun load(): Map<String, String> {
        val raw = MainActivityRuntime.prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { put(it, json.getString(it)) } }
        }.getOrDefault(emptyMap())
    }

    fun record(path: String, encodedValue: String) {
        val json = JSONObject()
        load().forEach { (k, v) -> json.put(k, v) }
        json.put(path, encodedValue)
        MainActivityRuntime.prefs.edit { putString(KEY, json.toString()) }
    }

    fun clear() = MainActivityRuntime.prefs.edit { remove(KEY) }

    fun count(): Int = load().size

    /**
     * Re-push every recorded edit. Called at the tail of applyTo, so these land after the
     * curated store has written the same nodes.
     *
     * Batched: each settingsSet otherwise serialises the whole config to YAML and writes it
     * out, and this can be a long list.
     */
    fun replay() {
        val overrides = load()
        if (overrides.isEmpty()) return

        runCatching { RPCSX.instance.settingsBeginBatch() }
        try {
            overrides.forEach { (path, value) ->
                runCatching { RPCSX.instance.settingsSet(path, value) }
            }
        } finally {
            runCatching { RPCSX.instance.settingsEndBatch() }
        }
    }
}
