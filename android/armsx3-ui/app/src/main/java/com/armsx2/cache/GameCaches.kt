package com.armsx2.cache

import android.content.Context
import com.armsx2.runtime.MainActivityRuntime
import java.io.File

// Recompiler and shader cache locations, and the deletes over them.
//
// These were private to PerformanceTab, which clears every game at once, or one game when the
// settings are opened for it. The long-press menu in the library needs the same deletes scoped to
// one title, and a second copy of the layout knowledge is exactly the thing that goes stale, so
// they live here and both callers share them.

/**
 * Root of RPCS3's compiled-code cache: `<files>/cache/cache/`.
 *
 * Holds `ppu-<hash>-<name>` directories for firmware modules at the top level, plus
 * `<TITLEID>/ppu-<hash>-EBOOT.BIN` per game. The SPU caches live INSIDE those directories -- a
 * `spu-*.dat` file and a `spuobj-v<n>-<key>/` directory of compiled objects -- which is why
 * clearing PPU necessarily clears SPU with it.
 */
internal fun recompilerCacheRoot(context: Context): File =
    File(MainActivityRuntime.assetCopyRoot(context), "cache/cache")

/** Every `ppu-*` directory, both the top-level firmware ones and the per-title ones. */
internal fun ppuCacheDirs(root: File): List<File> = buildList {
    root.listFiles()?.forEach { entry ->
        if (!entry.isDirectory) return@forEach
        if (entry.name.startsWith("ppu-")) add(entry)
        // A title id directory; its ppu-* dirs live one level down.
        else entry.listFiles()?.forEach { if (it.isDirectory && it.name.startsWith("ppu-")) add(it) }
    }
}

internal fun File.sizeRecursive(): Long =
    runCatching { walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "0 KB"
}

/**
 * Where the core keeps the compiled SPU objects of the caches under [root]: the app's private cache
 * folder, at the place [root] has under the data folder's cache (spu_obj_cache_parent in
 * SPUCommonRecompiler.cpp). For the root of every game's caches it is the whole private folder,
 * which also takes the hashed folders the core gives a cache that was not under the data folder.
 * Null when [root] is not under the data folder's cache.
 *
 * Not ARMSX3.rootDirectory: that stays empty until a game has been booted, and a clear pressed
 * straight after opening the app would have missed the folder again.
 */
private fun privateSpuObjDir(root: File): File? = runCatching {
    val context = com.armsx2.Pasx2Application.appContext
    val objects = File(context.cacheDir, "spuobj")
    val target = root.canonicalFile

    // The folder the core was started with first, then the one [root] was made from
    // (recompilerCacheRoot); the same folder unless the data folder was moved this session.
    val dataRoots = listOfNotNull(
        MainActivityRuntime.currentInitDataRoot()?.takeIf { it.isNotBlank() },
        MainActivityRuntime.assetCopyRoot(context),
    )

    dataRoots.firstNotNullOfOrNull { dataRoot ->
        val dataCache = File(dataRoot, "cache").canonicalFile
        val allGames = File(dataCache, "cache")

        when {
            allGames.path == target.path || allGames.path.startsWith(target.path + File.separator) -> objects
            target.path.startsWith(dataCache.path + File.separator) -> File(objects, target.relativeTo(dataCache).path)
            else -> null
        }
    }
}.getOrNull()

/**
 * Delete the SPU cache only, or the whole PPU cache.
 *
 * SPU-only leaves the compiled PPU modules in place, so booting stays as fast as it was and
 * only the SPU programs rebuild. Clearing PPU removes the directories outright, which takes
 * the SPU caches nested inside them too.
 */
internal fun clearRecompilerCache(root: File, spuOnly: Boolean): Pair<Int, Long> {
    var count = 0
    var bytes = 0L

    // Since 1.6.7 the compiled SPU objects are not under the data folder at all: the core keeps them
    // in the app's private cache folder (see privateSpuObjDir), so the walks below stopped reaching
    // them and both buttons left them behind. A cleared game still got its old SPU code back, and its
    // next boot was no first boot.
    privateSpuObjDir(root)?.let { private ->
        private.walkTopDown()
            .filter { it.isDirectory && it.name.startsWith("spuobj-") }
            .toList() // materialise before deleting, as below
            .forEach { dir ->
                val size = dir.sizeRecursive()
                if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                    count++
                    bytes += size
                }
            }
    }

    if (spuOnly) {
        // Two things live here, not one. `spu-*.dat` is the original SPU cache; the persistent SPU
        // LLVM object cache sits beside it in `spuobj-v<n>-<key>/` directories and is by far the
        // larger of the two. Clearing only the .dat files handed the recompiler straight back the
        // objects the user pressed this button to be rid of -- which is exactly what someone
        // clearing an SPU cache to escape stale compiled code needs not to happen.
        val objDirs = root.walkTopDown()
            .filter { it.isDirectory && it.name.startsWith("spuobj-") }
            .toList() // materialise before deleting, so the walk is not mutated under itself
        val datFiles = root.walkTopDown()
            .filter { it.isFile && it.name.startsWith("spu-") && it.extension == "dat" }
            .toList()

        objDirs.forEach { dir ->
            val size = dir.sizeRecursive()
            if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }

        datFiles.forEach { file ->
            // exists() because a .dat inside one of the directories above is already gone.
            if (!file.exists()) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }

        return count to bytes
    }

    ppuCacheDirs(root).forEach { dir ->
        val size = dir.sizeRecursive()
        if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
            count++
            bytes += size
        }
    }
    return count to bytes
}

/**
 * The cache directory for one title, or null when the game has no serial to key on.
 *
 * Everything for a game hangs off here: `ppu-<hash>-EBOOT.BIN/` holds the compiled PPU modules,
 * the SPU cache (`spu-*.dat` and `spuobj-v<n>-<key>/`) and `shaders_cache/` all nested inside it.
 */
internal fun titleCacheDir(context: Context, serial: String?): File? =
    serial?.takeIf { it.isNotBlank() }?.let { File(recompilerCacheRoot(context), it) }
