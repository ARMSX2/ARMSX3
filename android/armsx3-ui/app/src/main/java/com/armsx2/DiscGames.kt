package com.armsx2

import android.net.Uri

/**
 * Multi-game discs: collections that put several complete games on one Blu-ray.
 *
 * The first game lives in PS3_GAME as usual and the others in PS3_GM01, PS3_GM02 and so on,
 * each with its own PARAM.SFO, ICON0.PNG and title ID. The XMB lists every one of them, and so
 * does upstream's game list. This library used to read PS3_GAME alone, so a collection showed
 * one game and the rest of the disc was unreachable (reported with the Disgaea Triple Play
 * Collection).
 *
 * A folder disc needs nothing special to boot the others: each PS3_GMxx folder is a path of its
 * own, and the core walks up from it to the disc root. A disc IMAGE is one file, so the entry
 * carries the game folder as its URI fragment, and the launch path appends it as
 * "<image>//PS3_GM01", the key upstream files these under. The native boot splits it back off.
 */
object DiscGames {
    const val SEPARATOR = "//"

    private val gameDirName = Regex("^PS3_GM[0-9]{2}$")

    /** True for PS3_GM01..PS3_GM99. Case-sensitive, like the core's is_ps3_gm_dir_name: a
     *  folder the core will not recognise must not be listed as a game it cannot boot. */
    fun isGameDir(name: String): Boolean = gameDirName.matches(name)

    /** Which game on a disc image [uri] is, or null for anything else. */
    fun gameDirOf(uri: Uri): String? = uri.fragment?.takeIf(::isGameDir)

    /** What the core is handed to boot [uri]. A file is its bare path, since the native boot
     *  cannot open "file:///", and a picked-folder game is its document URI, which the bridge
     *  turns into a device path. */
    fun launchPath(uri: Uri): String {
        // Anything that is not a disc game keeps the old conversion exactly. A bare path handed
        // in from outside parses with a '#' as a fragment, and rebuilding the URI would cut a
        // file named "Disgaea #1.iso" off at the '#'.
        val gameDir = gameDirOf(uri)
            ?: return if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
        val base = uri.buildUpon().fragment(null).build()
        val path = if (base.scheme == "file") base.path ?: base.toString() else base.toString()
        return join(path, gameDir)
    }

    fun join(path: String, gameDir: String?): String =
        if (gameDir != null) path + SEPARATOR + gameDir else path

    /** [launchPath] taken apart again: the image, and the game on it when there is one. */
    fun split(launchPath: String): Pair<String, String?> {
        val at = launchPath.lastIndexOf(SEPARATOR)
        if (at < 0) return launchPath to null
        val gameDir = launchPath.substring(at + SEPARATOR.length)
        return if (isGameDir(gameDir)) launchPath.substring(0, at) to gameDir else launchPath to null
    }
}
