package com.armsx2

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import com.armsx2.runtime.MainActivityRuntime
import java.io.File

/**
 * The sound played when a trophy unlocks.
 *
 * Kept apart from [MenuSfx] on purpose even though both end up in the same sound pool. The
 * menu sounds are the launcher's own and ship with a built-in set; this one belongs to the
 * emulator, has no default at all, and is a single file rather than a pack. Folding it into
 * the pack importer meant "import a folder of clips" also silently governed the trophy, which
 * is not what anyone would expect from that control.
 *
 * ## Why there is no built-in
 *
 * The real PS3 sound is Sony's. RPCS3 ships none either: it plays whatever is in
 * config/sounds/ and is silent otherwise, which is the behaviour copied here. A user who
 * wants the authentic one already has it, inside the firmware they installed.
 */
object TrophySound {

    private const val NameKey = "ui.trophySound.name"

    /** What the core asks for, from rsx::overlays::get_sound_filepath. */
    private const val CORE_NAME = "snd_trophy"

    /** SoundPool reads these happily and the core never inspects the extension, so a user is
     *  not made to convert a file they already have. */
    private val ACCEPTED = listOf("wav", "ogg", "mp3")

    /** Display name of the chosen file, or null when no sound is set. */
    val fileName = mutableStateOf<String?>(null)

    fun load() {
        fileName.value = MainActivityRuntime.prefs.getString(NameKey, null)
            ?.takeIf { current() != null }
    }

    /** The directory the core reads its sounds from, or null before a data root exists. */
    private fun soundsDir(): File? =
        MainActivityRuntime.currentInitDataRoot()?.takeIf { it.isNotBlank() }
            ?.let { File(File(it, "config"), "sounds") }

    /** The sound file currently in place, whichever extension it has. */
    private fun current(): File? = soundsDir()?.let { dir ->
        ACCEPTED.firstNotNullOfOrNull { ext ->
            File(dir, "$CORE_NAME.$ext").takeIf { it.isFile && it.length() > 0L }
        }
    }

    /**
     * Copy a user-picked sound into place.
     *
     * Copied rather than referenced: a document grant does not survive a reboot, and the core
     * opens this by path from its own thread with no access to the picker's permission.
     */
    fun import(context: Context, uri: Uri): Boolean {
        val dir = soundsDir() ?: return false
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: "trophy.wav"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in ACCEPTED } ?: "wav"

        val ok = runCatching {
            dir.mkdirs()
            // Any other spelling first, or two files would sit there and the core would take
            // whichever it looked for rather than the one just chosen.
            clearFiles()
            context.contentResolver.openInputStream(uri)?.use { ins ->
                File(dir, "$CORE_NAME.$ext").outputStream().use { ins.copyTo(it) }
            } != null
        }.getOrDefault(false)

        if (!ok) return false

        fileName.value = name
        MainActivityRuntime.prefs.edit().putString(NameKey, name).apply()
        MenuSfx.forgetCachedFile(File(dir, "$CORE_NAME.$ext").absolutePath)
        return true
    }

    fun clear() {
        clearFiles()
        fileName.value = null
        MainActivityRuntime.prefs.edit().remove(NameKey).apply()
    }

    private fun clearFiles() {
        val dir = soundsDir() ?: return
        ACCEPTED.forEach { ext ->
            val f = File(dir, "$CORE_NAME.$ext")
            if (f.isFile) {
                MenuSfx.forgetCachedFile(f.absolutePath)
                runCatching { f.delete() }
            }
        }
    }

    /** Play whatever is set, so the settings row can preview it. */
    fun preview() {
        current()?.let { MenuSfx.playFile(it.absolutePath, -1f) }
    }
}
