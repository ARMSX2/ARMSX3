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
    private const val VolumeKey = "ui.trophySound.volume"

    /** Louder than the menu blips, which sit at 15%. Those fire on every button press and have
     *  to stay under the UI; a trophy fires a handful of times in a session and is the point of
     *  the moment, so it should be heard over the game. */
    private const val DefaultVolumePercent = 70

    /** What the core asks for, from rsx::overlays::get_sound_filepath. */
    private const val CORE_NAME = "snd_trophy"

    /** SoundPool reads these happily and the core never inspects the extension, so a user is
     *  not made to convert a file they already have. */
    private val ACCEPTED = listOf("wav", "ogg", "mp3")

    /** Display name of the chosen file, or null when no sound is set. */
    val fileName = mutableStateOf<String?>(null)

    val volumePercent = mutableStateOf(DefaultVolumePercent)

    /**
     * Playback level for this sound, 0..1.
     *
     * Separate from the menu level for the same reason the file is: sharing that slider meant a
     * user who had turned the interface blips down to a background tick also silenced their
     * trophy, from a control that never mentioned trophies.
     */
    fun gain(): Float = volumePercent.value.coerceIn(0, 100) / 100f

    fun isSet(): Boolean = current() != null

    fun load() {
        volumePercent.value = MainActivityRuntime.prefs.getInt(VolumeKey, DefaultVolumePercent)
        fileName.value = MainActivityRuntime.prefs.getString(NameKey, null)
            ?.takeIf { current() != null }
        ensurePlayback()
    }

    fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        volumePercent.value = p
        MainActivityRuntime.prefs.edit().putInt(VolumeKey, p).apply()
    }

    /**
     * The directory the core reads its sounds from.
     *
     * currentInitDataRoot is what NativeApp.initialize() was actually handed, so it wins: if the
     * user changes storage location mid-session the core is still reading the old root, and a
     * sound written to the new one would not be found.
     *
     * But it is a RECORD of that call, not a resolver, and it is null until the call happens --
     * which is later in onCreate than this object loads, and later still if the setup wizard is
     * showing. Reading it alone meant load() could not see an existing sound (so the app forgot
     * the user's choice on every cold start) and import() had nowhere to put one. assetCopyRoot
     * resolves the same path the core will be handed, so it is the right answer before the pin.
     */
    private fun soundsDir(): File? {
        val pinned = MainActivityRuntime.currentInitDataRoot()?.takeIf { it.isNotBlank() }
        val root = pinned
            ?: MainActivityRuntime.instance?.applicationContext
                ?.let { MainActivityRuntime.assetCopyRoot(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        return File(File(root, "config"), "sounds")
    }

    /** The sound file currently in place, whichever extension it has. */
    private fun current(): File? = soundsDir()?.let { dir ->
        ACCEPTED.firstNotNullOfOrNull { ext ->
            File(dir, "$CORE_NAME.$ext").takeIf { it.isFile && it.length() > 0L }
        }
    }

    /** Whether [path] is this sound. By stem, since the file keeps whatever extension the user
     *  picked and the core always asks for the .wav spelling. */
    fun owns(path: String): Boolean =
        File(path).nameWithoutExtension.equals(CORE_NAME, ignoreCase = true)

    /**
     * Playback runs through [MenuSfx]'s pool, which is otherwise only built when the launcher's
     * own sounds are on. Bring it up so a trophy is audible for a user who has those off.
     */
    private fun ensurePlayback() {
        if (!isSet()) return
        MainActivityRuntime.instance?.applicationContext?.let { MenuSfx.ensurePool(it) }
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
        MenuSfx.ensurePool(context)
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

    /** Play whatever is set, so the settings row can preview it. Negative volume so it comes
     *  out at the level a real trophy would, which is the point of a test button. */
    fun preview() {
        ensurePlayback()
        current()?.let { MenuSfx.playFile(it.absolutePath, -1f) }
    }
}
