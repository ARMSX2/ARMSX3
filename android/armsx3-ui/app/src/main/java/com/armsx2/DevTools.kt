package com.armsx2

import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import net.rpcsx.RPCSX
import java.io.File

/**
 * Developer tools, under Settings, Advanced: replaying RSX captures and dumping frames.
 *
 * Both start from the in-game menu. Capture RSX frame records one frame's GPU commands as an .rrc;
 * replaying it draws that frame again without the game, here or in RPCS3 on a PC. Dump this frame
 * saves a frame's render targets as PNGs (rsx_frame_dump.h in the core). Everything lands in
 * config/captures/ under the data folder.
 */
object DevTools {
    /** The image the flip shows, and the screen buffer when it can be read. */
    const val DUMP_FINAL_IMAGE = 1

    /** Every render target as the frame finishes with it, then the final image. */
    const val DUMP_EVERY_PASS = 2

    private const val KEY_DUMP_MODE = "devtools.frameDumpMode"

    /** What the in-game Dump this frame saves. */
    var frameDumpMode: Int
        get() = runCatching { MainActivityRuntime.prefs.getInt(KEY_DUMP_MODE, DUMP_FINAL_IMAGE) }
            .getOrDefault(DUMP_FINAL_IMAGE)
            .let { if (it == DUMP_EVERY_PASS) DUMP_EVERY_PASS else DUMP_FINAL_IMAGE }
        set(value) {
            runCatching { MainActivityRuntime.prefs.edit { putInt(KEY_DUMP_MODE, value) } }
        }

    fun capturesDir(): File = File(RPCSX.rootDirectory, "config/captures")

    fun isCapture(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".rrc") || lower.endsWith(".rrc.gz") || lower.endsWith(".rrc.zst")
    }

    /** RSX captures in config/captures/, newest first. */
    fun captures(): List<File> =
        capturesDir().listFiles { f -> f.isFile && isCapture(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Replay a capture. Back to the library first, as the settings screen's Play action does, so
     * settings are not left on the back stack behind it; then boot the file like a game, with a bare
     * path, which the core recognises as a capture by its extension.
     */
    fun replay(file: File) {
        com.armsx2.navigation.UiNavigator.navigate(com.armsx2.navigation.AppRoute.Home)
        MainActivityRuntime.launchGame(file.absolutePath)
    }
}
