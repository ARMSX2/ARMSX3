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
 * Two tiers now, the same shape [ConfigStore] gives curated settings: a global set, and a
 * per-game set keyed by serial. It used to be global-only, on the reasoning that the screen
 * edits the live config tree rather than a per-title layer. The consequence was that a node
 * set to get one title running was then set for every title: turn on Stub PPU Traps for
 * Uncharted 3 and every game booted afterwards ran with it too, with nothing on screen to
 * say so. [replay] pushes global first and the running title's set on top, so a per-game
 * value wins where the two disagree and every node the title never touched still follows
 * global.
 */
object CoreSettingOverrides {
    private const val KEY_GLOBAL = "config.coreOverrides"

    /** Per-title key. Deliberately NOT under the "config.game." prefix: ConfigStore's
     *  in-folder backup mirror scans prefs for that prefix and copies each hit out as a
     *  Settings blob, and these are path-to-value maps, which would not survive the trip. */
    private fun keyForGame(serial: String) = "config.coreOverrides.game.$serial"

    /** Pref key a (scope, serial) pair reads and writes. Game scope with no usable serial
     *  resolves to global, matching [ConfigStore.save]: there is no title to attribute the
     *  edit to, and dropping it on the floor would look like the screen doing nothing. */
    private fun keyFor(scope: SettingsScope, serial: String?): String {
        val title = serial?.trim().orEmpty()
        return if (scope == SettingsScope.Game && title.isNotEmpty()) keyForGame(title) else KEY_GLOBAL
    }

    private fun read(key: String): Map<String, String> {
        val raw = MainActivityRuntime.prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { put(it, json.getString(it)) } }
        }.getOrDefault(emptyMap())
    }

    private fun write(key: String, values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (k, v) -> json.put(k, v) }
        MainActivityRuntime.prefs.edit { putString(key, json.toString()) }
    }

    /** path ("Video@@Write Color Buffers") to the JSON-encoded value settingsSet expects. */
    fun load(scope: SettingsScope, serial: String?): Map<String, String> = read(keyFor(scope, serial))

    fun record(scope: SettingsScope, serial: String?, path: String, encodedValue: String) {
        val key = keyFor(scope, serial)
        write(key, read(key) + (path to encodedValue))
    }

    fun clear(scope: SettingsScope, serial: String?) =
        MainActivityRuntime.prefs.edit { remove(keyFor(scope, serial)) }

    /** Drop recorded edits by path. For nodes a build no longer has, or no longer wants set. */
    /**
     * Drop [paths] from the global store and from every per-title store.
     *
     * Raw overrides re-push after the curated settings, so a stale one silently beats the UI: the
     * settings screen can read Safe while config.yml reads Mega, and nothing on screen explains
     * it. Clearing one scope is not enough either, because a title can carry its own entry for a
     * key the global also holds -- Batman: Arkham City had Accurate SPU Reservations forced true
     * per-title while the global forced it false.
     */
    fun forgetEverywhere(vararg paths: String) {
        forget(SettingsScope.Global, null, *paths)

        for (key in MainActivityRuntime.prefs.all.keys) {
            if (!key.startsWith("config.coreOverrides.game.")) continue

            val current = read(key)
            if (paths.none { it in current }) continue

            write(key, current.filterKeys { it !in paths })
        }
    }

    fun forget(scope: SettingsScope, serial: String?, vararg paths: String) {
        val key = keyFor(scope, serial)
        val current = read(key)
        if (paths.none { it in current }) return
        write(key, current.filterKeys { it !in paths })
    }

    fun count(scope: SettingsScope, serial: String?): Int = load(scope, serial).size

    /**
     * Re-push every recorded edit for [serial] and the global set beneath it. Called at the
     * tail of applyTo, so these land after the curated store has written the same nodes.
     *
     * Pass the running title's settings key, or null for a boot that carries no title (the
     * BIOS, a disc with no GameInfo): the per-game tier is then skipped rather than guessed
     * at, so no previous game's core edits ride along.
     *
     * Batched: each settingsSet otherwise serialises the whole config to YAML and writes it
     * out, and this can be a long list.
     */
    fun replay(serial: String?) {
        val global = read(KEY_GLOBAL)
        val perGame = serial?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { read(keyForGame(it)) }.orEmpty()
        if (global.isEmpty() && perGame.isEmpty()) return

        // Merged rather than pushed as two passes so a path held by both tiers is written once,
        // with the title's value. Insertion order keeps global's paths where they were.
        val merged = LinkedHashMap(global)
        merged.putAll(perGame)

        runCatching { RPCSX.instance.settingsBeginBatch() }
        try {
            merged.forEach { (path, value) ->
                // Report per setting rather than assuming. A stored override that silently
                // fails to apply looks identical to one that was never recorded, which is
                // exactly the confusion Vblank Rate caused. The tier goes in the line too:
                // "why is this game different" is answered by which set the value came from.
                val ok = runCatching { RPCSX.instance.settingsSet(path, value) }.getOrDefault(false)
                val tier = if (path in perGame) "game" else "global"
                android.util.Log.i("ARMSX3-Override", "replay [$tier] $path = $value -> $ok")
            }
        } finally {
            runCatching { RPCSX.instance.settingsEndBatch() }
        }
    }
}
