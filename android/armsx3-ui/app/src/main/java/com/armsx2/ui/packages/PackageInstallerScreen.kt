package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.armsx2.data.library.Licences
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

/**
 * Licence files sitting in exdata.
 *
 * Installing one is a silent copy into a directory nothing else on this screen reads, so a
 * success looked exactly like a failure: a licence belongs to no title, never appears under
 * Installed titles, and left no trace anywhere in the app. Reported as "I installed the .rap
 * but nothing seemed to happen" -- the file was there the whole time.
 *
 * Listed through Licences.exdataDir() rather than a path assembled here, so it follows the
 * logged-in user instead of assuming 00000001, and cannot drift from where installs land.
 */
private fun readLicences(): List<java.io.File> =
    Licences.exdataDir()
        .listFiles()
        ?.filter { it.isFile && isLicence(it) }
        ?.sortedBy { it.name }
        .orEmpty()

private fun readInstalled(): List<java.io.File> =
    java.io.File(RPCSX.rootDirectory, "config/dev_hdd0/game")
        .listFiles()
        ?.filter { it.isDirectory && isInstalledContent(it) }
        ?.sortedBy { it.name }
        .orEmpty()

/**
 * True for a directory under dev_hdd0/game that is actually installed content.
 *
 * Everything in there used to be listed with an Uninstall button beside it, including
 * things that are not titles at all. RPCS3 keeps its own "$locks" directory in here
 * (rpcs3::utils::get_hdd0_locks_dir is get_hdd0_game_dir() + "$locks/", which reaches
 * Android as the escaped ＄locks), so the screen offered to delete the emulator's lock
 * state, and any stray folder a failed install left behind was offered as a title too.
 *
 * A PARAM.SFO is the test. Game data installs keep theirs and are deliberately still
 * listed: a 1.1GB BLUS30464_INSTALL is exactly the kind of thing someone comes here to
 * reclaim, even though it is not bootable.
 */
private fun isInstalledContent(dir: java.io.File): Boolean {
    if (dir.name.startsWith("$") || dir.name.startsWith("＄")) return false
    return runCatching {
        dir.listFiles()?.any { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
    }.getOrNull() == true
}

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
    var licences by remember { mutableStateOf(readLicences()) }
    var confirmRemove by remember { mutableStateOf<java.io.File?>(null) }

    // getItem returns MutableState<ProgressEntry>; reading .value here and .longValue
    // below is what subscribes this composable to the native progress callbacks.
    val progress = ProgressRepository.getItem(progressId)?.value
    val fraction = progress?.let {
        if (it.isIndeterminate()) null
        else (it.value.longValue.toFloat() / it.max.longValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // One path for every pick: a lone package, several parts of a split one, a licence, or
    // a package together with the licence that unlocks it.
    //
    // The selection is SPLIT BY KIND rather than by count. It used to be routed on count
    // alone, so picking a game's .pkg and its .rap together -- which the file browser
    // invites, since it allows multiple selection -- handed both to installSplitPkg, whose
    // first act is to reject anything that is not a .pkg part. Choosing a game and its
    // licence, the obvious thing to do, could therefore only ever fail.
    //
    // Packages install FIRST: a licence unlocks content the package has to have written.
    /**
     * Install from a Storage Access Framework pick.
     *
     * The in-app browser walks java.io.File, which only reaches storage this process can open
     * by path -- internal, and its own external dirs. A .pkg on a USB-OTG drive or on some SD
     * cards is not reachable that way at all, so those users had to copy multi-gigabyte files
     * to internal storage first. Reported as issue #16.
     *
     * Packages are handed over as the descriptor SAF already gave us: the native side takes a
     * raw fd, so nothing is copied and a 4 GB package costs no extra space. Licences are 16
     * bytes and their installer wants a real file, so those alone are staged into the cache.
     */
    fun installFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        busy = true
        message = null
        MainActivityRuntime.invoke {
            var nativeFailure: String? = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver

                    fun displayName(uri: android.net.Uri): String =
                        runCatching {
                            resolver.query(uri, null, null, null, null)?.use { c ->
                                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

                    val named = uris.map { it to displayName(it) }
                    val licenceUris = named.filter { (_, n) ->
                        n.endsWith(".rap", true) || n.endsWith(".edat", true)
                    }
                    val packageUris = named.filterNot { (_, n) ->
                        n.endsWith(".rap", true) || n.endsWith(".edat", true)
                    }

                    val label = if (uris.size == 1) named[0].second else "${uris.size} files"
                    val id = ProgressRepository.create(context, "Installing $label")
                    progressId = id
                    val progressEntry = ProgressRepository.getItem(id)

                    var result = true
                    if (packageUris.isNotEmpty()) {
                        val descriptors = packageUris.mapNotNull { (uri, _) ->
                            runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
                        }
                        if (descriptors.size != packageUris.size) {
                            descriptors.forEach { runCatching { it.close() } }
                            return@runCatching false
                        }
                        try {
                            result = if (descriptors.size == 1) {
                                RPCSX.instance.install(descriptors[0].fd, id)
                            } else {
                                RPCSX.instance.installSplitPkg(descriptors.map { it.fd }.toIntArray(), id)
                            }
                        } finally {
                            descriptors.forEach { runCatching { it.close() } }
                        }
                    }

                    for ((uri, name) in licenceUris) {
                        if (!result) break
                        val staged = java.io.File(context.cacheDir, name)
                        val copied = runCatching {
                            resolver.openInputStream(uri)?.use { input ->
                                staged.outputStream().use { out -> input.copyTo(out) }
                            } != null
                        }.getOrDefault(false)
                        if (!copied) { result = false; break }
                        result = if (name.endsWith(".rap", true)) {
                            Licences.installRap(staged)
                        } else {
                            val d = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
                            try { RPCSX.instance.installKey(d.fd, id, "") } finally { runCatching { d.close() } }
                        }
                        runCatching { staged.delete() }
                    }

                    progressEntry?.value?.takeIf { it.isFailed() }?.let {
                        nativeFailure = it.message.value
                    }
                    result
                }.getOrDefault(false)
            }
            busy = false
            progressId = null
            message = if (ok) {
                GameLibraryRepository(context).invalidateCache()
                installed = readInstalled()
                licences = readLicences()
                I18n.get("packages.install.done")
            } else {
                nativeFailure?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed")
            }
        }
    }

    fun install(files: List<java.io.File>) {
        if (files.isEmpty()) return
        showBrowser = false
        busy = true
        message = null
        MainActivityRuntime.invoke {
            // The reason the native side reported, if it failed. Held here because
            // ProgressRepository drops its handler entry as soon as a request finishes, so
            // the entry has to be captured while the install is still running.
            var nativeFailure: String? = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val licences = files.filter { isLicence(it) }
                    val packages = files.filterNot { isLicence(it) }
                    val label = if (files.size == 1) files[0].name
                    else "${files.size} files"
                    val id = ProgressRepository.create(context, "Installing $label")
                    progressId = id
                    val progressEntry = ProgressRepository.getItem(id)

                    // Every descriptor stays open for the whole install: the native side
                    // takes raw fds and releases the handles itself, so closing them early
                    // would pull the file out from under the extractor.
                    var result = true
                    if (packages.isNotEmpty()) {
                        val descriptors = packages.map {
                            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                        }
                        try {
                            result = if (descriptors.size == 1) {
                                RPCSX.instance.install(descriptors[0].fd, id)
                            } else {
                                RPCSX.instance.installSplitPkg(
                                    descriptors.map { it.fd }.toIntArray(), id,
                                )
                            }
                        } finally {
                            descriptors.forEach { runCatching { it.close() } }
                        }
                    }

                    for (licence in licences) {
                        if (!result) break
                        result = if (licence.extension.equals("rap", ignoreCase = true)) {
                            Licences.installRap(licence)
                        } else {
                            // EDAT carries its own content id, so installKey works out the
                            // exdata name from the file itself and needs no game path.
                            val descriptor = ParcelFileDescriptor.open(
                                licence, ParcelFileDescriptor.MODE_READ_ONLY,
                            )
                            try {
                                RPCSX.instance.installKey(descriptor.fd, id, "")
                            } finally {
                                runCatching { descriptor.close() }
                            }
                        }
                    }

                    // Read after the calls return: onProgressEvent writes the message on the
                    // calling thread before it reports, so a failure reason is already there.
                    progressEntry?.value?.takeIf { it.isFailed() }?.let {
                        nativeFailure = it.message.value
                    }
                    result
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
                licences = readLicences()
                I18n.get("packages.install.done")
            } else {
                // The native reason names the actual problem ("Game is broken: PARAM.SFO not
                // found", "Every selected file must be a .pkg part"); the generic string
                // guesses, and used to be all the user ever saw.
                nativeFailure?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed")
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
                        licences = readLicences()
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

    val safPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (!uris.isNullOrEmpty()) installFromUris(uris) }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("packages.select.title"),
            // RAP and EDAT are licence files, not packages, and install() routes them
            // accordingly. Some titles need both: the .pkg carries the content and the .rap
            // is what unlocks it, so both can be selected in one go. PUP stays out on
            // purpose, firmware has its own screen and should not be installable from a
            // menu that says nothing about it.
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
                        // Reaches storage the in-app browser cannot open by path: USB-OTG,
                        // and SD cards on devices that only expose them through SAF.
                        Button(
                            onClick = { safPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(str("packages.select.external"))
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

            // Installed licences. Read from exdata, which is where Licences.installRap puts
            // them: without this the screen showed nothing at all after a .rap install and
            // a success was indistinguishable from a failure.
            if (licences.isNotEmpty()) {
                Text(
                    str("packages.licences.header"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                licences.forEach { file ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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
