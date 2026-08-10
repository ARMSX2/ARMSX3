package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.FileBrowserDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/**
 * Install a .pkg (game, update or DLC) into the emulator's own storage.
 *
 * The native side already did all of this -- _rpcsx_install dispatches on the file's
 * magic and hands a PKG to installPkg -- but RPCSX.instance.install() had ZERO callers,
 * so there was no way to reach it from the UI. Reported by two testers as a serious
 * oversight, and it read as a missing feature rather than a missing button.
 *
 * Installs land in config/dev_hdd0/game, which GameLibraryRepository now scans, so the
 * title shows up in the library on the next scan. The cache is keyed by folder set and
 * would not notice a new game inside a folder it already knows, hence invalidateCache().
 */
/**
 * Titles installed into the emulator's own storage, i.e. everything a PKG install
 * produced. Read from disk rather than from the library, so uninstall still works when
 * the library cache is stale and can never point at a user's ROM folder.
 */
/** Licence files, which install through a different native entry point than packages. */
private fun isLicence(file: java.io.File): Boolean =
    file.extension.equals("rap", ignoreCase = true) ||
        file.extension.equals("edat", ignoreCase = true)

private fun readInstalled(): List<java.io.File> =
    java.io.File(RPCSX.rootDirectory, "config/dev_hdd0/game")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()

/**
 * A title's compiled-code and shader cache.
 *
 * Keyed by title id, which is also the name of the title's folder under dev_hdd0/game --
 * rpcs3::utils::get_cache_dir() appends Emu.GetTitleID() to <root>/cache/cache/, and a PKG
 * installs into a directory named for the same id. Measured at 7-58 MB per title, which is
 * why uninstalling without it leaves the bulk of the disk usage behind.
 */
private fun cacheDirFor(titleId: String): java.io.File =
    java.io.File(RPCSX.rootDirectory, "cache/cache/$titleId")

private fun dirSize(dir: java.io.File): Long =
    if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

/**
 * Delete a title's cache directory.
 *
 * The name is checked rather than trusted: it comes from a directory listing, but a path
 * separator or a dot-dot in it would resolve outside the per-title folder and take the whole
 * cache — or more — with it. Anything but a single plain segment is refused.
 */
private fun removeCacheFor(titleId: String): Boolean = runCatching {
    if (titleId.isBlank() || titleId == "." || titleId == ".." ||
        titleId.contains('/') || titleId.contains('\\')
    ) return false
    val dir = cacheDirFor(titleId)
    if (!dir.isDirectory) return false
    dir.deleteRecursively()
}.getOrDefault(false)

@Composable
fun PackageInstallerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var progressId by remember { mutableStateOf<Long?>(null) }
    var installed by remember { mutableStateOf(readInstalled()) }
    var confirmRemove by remember { mutableStateOf<java.io.File?>(null) }

    // getItem returns MutableState<ProgressEntry>; reading .value here and .longValue
    // below is what subscribes this composable to the native progress callbacks.
    val progress = ProgressRepository.getItem(progressId)?.value
    val fraction = progress?.let {
        if (it.isIndeterminate()) null
        else (it.value.longValue.toFloat() / it.max.longValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // One path for both the single pick and the multi-part pick. Every descriptor stays
    // open for the whole install: the native side takes raw fds and releases the handles
    // itself, so closing them early would pull the file out from under the extractor.
    fun install(files: List<java.io.File>) {
        if (files.isEmpty()) return
        showBrowser = false
        busy = true
        message = null
        MainActivityRuntime.invoke {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val label = if (files.size == 1) files[0].name
                    else "${files.size} package parts"
                    val id = ProgressRepository.create(context, "Installing $label")
                    progressId = id
                    val descriptors = files.map {
                        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                    try {
                        if (descriptors.size == 1 && isLicence(files[0])) {
                            // installKey, not install: _rpcsx_install rejects a RAP outright
                            // ("cannot be preinstalled"). Empty game path means "work it out
                            // from the licence", which is what the exdata directory needs.
                            RPCSX.instance.installKey(descriptors[0].fd, id, "")
                        } else if (descriptors.size == 1) {
                            RPCSX.instance.install(descriptors[0].fd, id)
                        } else {
                            RPCSX.instance.installSplitPkg(
                                descriptors.map { it.fd }.toIntArray(), id,
                            )
                        }
                    } finally {
                        descriptors.forEach { runCatching { it.close() } }
                    }
                }.getOrDefault(false)
            }
            busy = false
            progressId = null
            // I18n.get, not str(): this runs inside a coroutine, and str() is a
            // @Composable that can only be called during composition.
            message = if (ok) {
                // Force the library to re-read storage; the folder set is unchanged
                // so nothing else would prompt a rescan.
                GameLibraryRepository(context).invalidateCache()
                installed = readInstalled()
                I18n.get("packages.install.done")
            } else {
                I18n.get("packages.install.failed")
            }
        }
    }

    // Deleting a game folder is not undoable, so it is confirmed rather than done on tap.
    confirmRemove?.let { target ->
        // Uninstall only ever removed dev_hdd0/game/<TITLEID>, so the title's compiled-code and
        // shader cache -- by far the larger of the two on disk -- stayed forever. Offered as a
        // checkbox rather than done silently, and defaulted on, which is how RPCS3 desktop's own
        // remove dialog treats caches. Save data, trophies and licences are deliberately NOT
        // touched: those are the user's, not the install's.
        var alsoRemoveCache by remember(target) { mutableStateOf(true) }
        // Off the main thread: a cache directory holds hundreds of files and this runs while the
        // dialog is opening.
        val cacheBytes by androidx.compose.runtime.produceState(0L, target) {
            value = withContext(Dispatchers.IO) { dirSize(cacheDirFor(target.name)) }
        }
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(str("packages.uninstall.confirmTitle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(str("packages.uninstall.confirmBody").format(target.name))
                    // Hidden when there is no cache, so the row never offers to free nothing.
                    if (cacheBytes > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoRemoveCache = !alsoRemoveCache },
                        ) {
                            Checkbox(checked = alsoRemoveCache, onCheckedChange = { alsoRemoveCache = it })
                            Text(
                                str("packages.uninstall.alsoCache").format(formatSize(cacheBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    val removeCache = alsoRemoveCache
                    val titleId = target.name
                    MainActivityRuntime.invoke {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                RPCSX.instance.uninstallGame(target.absolutePath)
                            }.getOrDefault(false)
                        }
                        // Only after the game itself is gone: dropping the cache for a title that
                        // is still installed would just cost the user a recompile.
                        if (ok && removeCache) {
                            withContext(Dispatchers.IO) { removeCacheFor(titleId) }
                        }
                        installed = readInstalled()
                        if (ok) GameLibraryRepository(context).invalidateCache()
                        message = I18n.get(
                            if (ok) "packages.uninstall.done" else "packages.uninstall.failed",
                        )
                    }
                }) { Text(str("packages.uninstall")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(str("action.cancel")) }
            },
        )
    }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("packages.select.title"),
            // PUP is deliberately absent: firmware has its own screen, and routing it
            // through here would let someone install firmware from a menu that says
            // nothing about it. EDAT rides along because _rpcsx_install handles it and
            // it is what DLC licences arrive as.
            // RAP and EDAT are licence files, not packages, so they go through installKey
            // rather than install. Some titles need both: the .pkg carries the content and
            // the .rap is what unlocks it. PUP stays out on purpose, firmware has its own
            // screen and should not be installable from a menu that says nothing about it.
            extensions = setOf("pkg", "rap", "edat"),
            // Split releases ship as several .pkg parts that only install correctly when
            // handed to the installer together, the way RPCS3 desktop does it.
            allowMultiple = true,
            onPickMultiple = { files -> install(files) },
            onPick = { file -> install(listOf(file)) },
            onDismiss = { showBrowser = false },
        )
    }

    ArmsBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                str("packages.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        str("packages.description"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        str("packages.multiHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (busy) {
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            str("packages.installing"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { showBrowser = true }) {
                            Text(str("packages.select.action"))
                        }
                    }

                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Installed titles, with uninstall. Only what is under the emulator's own
            // dev_hdd0/game is listed, so this can never remove a disc or ROM folder.
            if (installed.isNotEmpty()) {
                Text(
                    str("packages.installed.header"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(installed, key = { it.absolutePath }) { dir ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { confirmRemove = dir }) {
                                    Text(str("packages.uninstall"))
                                }
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onBack) { Text(str("action.back")) }
        }
    }
}
