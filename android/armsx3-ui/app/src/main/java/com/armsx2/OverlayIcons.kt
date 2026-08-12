package com.armsx2

import android.content.Context
import com.armsx2.runtime.MainActivityRuntime
import java.io.File

/**
 * Stage the core's overlay icons into the data folder.
 *
 * `overlay_controls.cpp` loads a fixed set of PNGs -- the button glyphs, plus `save.png`,
 * `new.png` and `spinner-24.png` -- through `fs::get_config_dir() + "Icons/ui/"`. Desktop
 * builds ship them beside the binary; nothing put them on Android, so every one failed to
 * open and the log filled with "Image resource file ... could not be opened".
 *
 * The visible cost was the save-data list: `cellSaveData`'s list is a native overlay that
 * draws its entries with save.png/new.png, so the menu a game opens to load a save never
 * appeared. Reported against Ratchet & Clank: Tools of Destruction and Devil May Cry 4,
 * both working on other PS3 emulators, which ship the icons.
 *
 * Guarded by a stored revision rather than copied every boot: it is one preference read once
 * the revision matches. Bump [REVISION] when the bundled icons change.
 */
object OverlayIcons {

    private const val PREFS_NAME = "armsx3.overlay.icons"
    private const val KEY_REVISION = "revision"
    private const val REVISION = 1

    /** Matches the path overlay_controls.cpp builds from fs::get_config_dir(). */
    private const val ASSET_DIR = "Icons/ui"
    private const val DEST_DIR = "config/Icons/ui"

    fun ensureBundled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= REVISION) return

        // Same root resolution the rest of the app uses: systemDirPosix is null on a default
        // install, where the core roots its config at getExternalFilesDir instead.
        val root = MainActivityRuntime.systemDirPosix()
            ?: context.getExternalFilesDir(null)?.absolutePath
            ?: return

        val copied = runCatching { copyTree(context, ASSET_DIR, File(root, DEST_DIR)) }
            .getOrDefault(0)

        if (copied <= 0) {
            android.util.Log.e("ARMSX3", "overlay icons: nothing copied from assets/$ASSET_DIR")
            return
        }

        prefs.edit().putInt(KEY_REVISION, REVISION).apply()
        android.util.Log.i("ARMSX3", "overlay icons: staged $copied file(s) into $DEST_DIR")
    }

    /** Recursive because the set has a `home/` subfolder alongside the flat PNGs. */
    private fun copyTree(context: Context, assetPath: String, dest: File): Int {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            // A leaf: assets.list returns empty for files.
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return 1
        }

        dest.mkdirs()
        var n = 0
        for (entry in entries) {
            n += copyTree(context, "$assetPath/$entry", File(dest, entry))
        }
        return n
    }
}
