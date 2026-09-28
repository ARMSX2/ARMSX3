package com.armsx2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The user's own boot intro, played by [BootSplashActivity] in place of the bundled
 * res/raw/boot_intro.mp4. The app never ships or redistributes it: it only plays a video the
 * user picked from their own device, the same model as the custom library track.
 *
 * The video is copied into app storage rather than played from its SAF uri, so the intro does
 * not vanish when the source file moves or the SD card is out. The copy lands in a temp file
 * and is checked for a decodable picture before it replaces anything, so a bad pick leaves the
 * current intro in place.
 *
 * Whether a custom intro is active is decided by the FILE, not the stored name. A backup
 * restores preferences but not app storage, and a name with no file behind it must fall back
 * to the bundled intro rather than to a black screen.
 */
object BootIntro {
    private const val NameKey = "ui.bootLogo.customName"

    /** Extra that turns [BootSplashActivity] into a one-off preview. */
    const val EXTRA_PREVIEW = "com.armsx2.bootintro.PREVIEW"

    /** Big enough for any intro, small enough that picking a whole film by mistake is refused
     *  instead of copying gigabytes into app storage. */
    const val MAX_BYTES = 256L * 1024 * 1024

    enum class ImportResult { Ok, TooLarge, NotVideo, Unreadable }

    /** Display name of the user's intro, or null when the bundled one plays. Backs App settings. */
    val customName = mutableStateOf<String?>(null)

    private fun dir(context: Context): File = File(context.filesDir, "bootintro")

    // Extension-less, like the library track: MediaPlayer sniffs the container.
    private fun customFile(context: Context): File = File(dir(context), "video")

    // BootSplashActivity runs before MainActivityRuntime.prefs exists, so both sides open the
    // "ARMSX2" preferences directly. Same name, same cached instance.
    private fun prefs(context: Context) = context.getSharedPreferences("ARMSX2", Context.MODE_PRIVATE)

    /** The user's video, or null to play the bundled intro. */
    fun installed(context: Context): File? = customFile(context).takeIf { it.length() > 0L }

    fun load(context: Context) {
        customName.value = if (installed(context) != null) {
            prefs(context).getString(NameKey, null) ?: "video"
        } else {
            null
        }
    }

    /** Copy [uri] in as the boot intro. Blocking and potentially hundreds of MB: call on IO. */
    fun importVideo(context: Context, uri: Uri): ImportResult {
        val source = DocumentFile.fromSingleUri(context, uri)
        // length() is 0 when the provider does not know the size; the copy enforces it then.
        if ((source?.length() ?: 0L) > MAX_BYTES) return ImportResult.TooLarge

        val incoming = File(dir(context).apply { mkdirs() }, "incoming")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { output -> copyCapped(input, output) }
            } ?: return ImportResult.Unreadable
            if (copied < 0L) return ImportResult.TooLarge
            if (copied == 0L) return ImportResult.Unreadable
            if (!hasPicture(incoming)) return ImportResult.NotVideo
            if (!incoming.renameTo(customFile(context))) return ImportResult.Unreadable

            val name = source?.name ?: "video"
            prefs(context).edit { putString(NameKey, name) }
            customName.value = name
            return ImportResult.Ok
        } catch (_: Exception) {
            return ImportResult.Unreadable
        } finally {
            incoming.delete()
        }
    }

    /** Play the current intro now. It otherwise shows only on a cold start, so without this
     *  the only way to see a new pick is to close the app completely. */
    fun preview(context: Context) {
        val intent = Intent(context, BootSplashActivity::class.java).putExtra(EXTRA_PREVIEW, true)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Drop the user's video and go back to the bundled intro. */
    fun clear(context: Context) {
        customFile(context).delete()
        prefs(context).edit { remove(NameKey) }
        customName.value = null
    }

    /** Bytes copied, or -1 once the copy passes [MAX_BYTES]. */
    private fun copyCapped(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > MAX_BYTES) return -1L
            output.write(buffer, 0, read)
        }
    }

    /**
     * A video track alone is not enough: a codec the device cannot decode (AV1 or 10-bit HEVC
     * on older chips) still reports one, and at boot that fails silently straight to the
     * library. Decoding a small first frame catches it here, where the user can be told.
     */
    private fun hasPicture(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    64,
                    64,
                ) != null
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
