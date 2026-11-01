package com.armsx2.ui.xmb

import androidx.compose.runtime.mutableStateOf
import com.armsx2.i18n.I18n
import com.armsx3.core.ARMSX3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Playlists, as the console's Music and Photo columns keep them: named lists of songs or pictures,
 * in the order they were added. Kept in ARMSX3's data folder (xmb/), so they go where the music
 * and the screenshots go. Paths are stored whole; one whose file has gone is left out when read.
 */
object XmbPlaylists {
    enum class Kind(val file: String) { Music("music-playlists.json"), Photo("photo-playlists.json") }

    val music = mutableStateOf<Map<String, List<String>>>(emptyMap())
    val photo = mutableStateOf<Map<String, List<String>>>(emptyMap())
    @Volatile private var loaded = false

    private fun state(kind: Kind) = if (kind == Kind.Music) music else photo

    private fun file(kind: Kind): File = File(ARMSX3.rootDirectory, "xmb/${kind.file}")

    /** Read both, once. IO thread. */
    fun load() {
        if (loaded) return
        loaded = true
        Kind.entries.forEach { kind -> state(kind).value = read(kind) }
    }

    private fun read(kind: Kind): Map<String, List<String>> = runCatching {
        val o = JSONObject(file(kind).readText())
        val out = LinkedHashMap<String, List<String>>()
        o.keys().forEach { name ->
            val list = o.optJSONArray(name) ?: JSONArray()
            out[name] = (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
        }
        out
    }.getOrDefault(emptyMap())

    private fun save(kind: Kind, value: Map<String, List<String>>) {
        state(kind).value = value
        runCatching {
            val o = JSONObject()
            value.forEach { (name, paths) -> o.put(name, JSONArray(paths)) }
            file(kind).apply { parentFile?.mkdirs() }.writeText(o.toString())
        }
    }

    /** The files of [name] that are still there, in order. */
    fun files(kind: Kind, name: String): List<File> =
        state(kind).value[name].orEmpty().map(::File).filter { it.isFile }

    /** Add [path] to [name], making the playlist when it is new; true when it was not in it already. */
    fun add(kind: Kind, name: String, path: String): Boolean {
        val now = LinkedHashMap(state(kind).value)
        val list = now[name].orEmpty()
        if (path in list) return false
        now[name] = list + path
        save(kind, now)
        return true
    }

    /** A new, empty playlist called [name]; nothing when there is one already. */
    fun create(kind: Kind, name: String) {
        if (name in state(kind).value) return
        save(kind, LinkedHashMap(state(kind).value).apply { put(name, emptyList()) })
    }

    fun remove(kind: Kind, name: String, path: String) {
        val now = LinkedHashMap(state(kind).value)
        now[name] = now[name].orEmpty() - path
        save(kind, now)
    }

    fun delete(kind: Kind, name: String) {
        val now = LinkedHashMap(state(kind).value)
        now.remove(name)
        save(kind, now)
    }

    /**
     * Triangle's Add to Playlist on [path]: the playlists there are, and New Playlist, its name
     * typed on the pad keyboard. Said in the XMB's own dialog once it is in.
     */
    fun offer(controller: XmbController, kind: Kind, path: String) {
        val ok = I18n.get("xmb.ok")
        val added = I18n.get("xmb.playlist.added")
        fun put(name: String) {
            add(kind, name, path)
            controller.tell(added.format(name), ok, error = false)
        }
        val names = state(kind).value.keys.toList()
        controller.options = XmbController.Options(
            I18n.get("xmb.playlist.add"),
            names.map { name -> XmbOption(name) { put(name) } } +
                XmbOption(I18n.get("xmb.playlist.new")) {
                    XmbKeyboard.ask(I18n.get("xmb.playlist.name")) { typed ->
                        val name = typed.trim()
                        if (name.isNotEmpty()) put(name)
                    }
                },
        )
    }
}
